package com.example.ragagent.service;

import com.example.ragagent.agent.AgentGraph;
import com.example.ragagent.agent.AgentState;
import com.example.ragagent.exception.LlmProviderExhaustedException;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.model.ChatForm;
import com.example.ragagent.model.SourceRef;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * SSE streaming pipeline orchestrator.
 * Mirrors AgentService.chat() setup but drives AgentGraph.runStreaming()
 * and forwards node/token/sources events to a SseEmitter.
 */
@Service
public class StreamingAgentService {

    private static final Logger log = LoggerFactory.getLogger(StreamingAgentService.class);

    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    private final AgentGraph agentGraph;
    private final MemoryService memoryService;
    private final ClassifierService classifierService;
    private final ThreadMetaService threadMetaService;
    private final ObjectMapper objectMapper;
    private final MessageSource messageSource;

    public StreamingAgentService(AgentGraph agentGraph,
                                  MemoryService memoryService,
                                  ClassifierService classifierService,
                                  ThreadMetaService threadMetaService,
                                  ObjectMapper objectMapper,
                                  MessageSource messageSource) {
        this.agentGraph = agentGraph;
        this.memoryService = memoryService;
        this.classifierService = classifierService;
        this.threadMetaService = threadMetaService;
        this.objectMapper = objectMapper;
        this.messageSource = messageSource;
    }

    /**
     * Runs the full agent pipeline in a Virtual Thread.
     * Caller should already have called threadMetaService.getOrCreate() before this.
     */
    private static final DateTimeFormatter DB_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    public void run(String userId, ChatForm form, SseEmitter emitter) {
        long startNs = System.nanoTime();
        String askedAt = DB_FMT.format(Instant.now());
        SseGraphListener listener = null;
        ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleAtFixedRate(() -> {
            try { emitter.send(SseEmitter.event().comment("heartbeat")); }
            catch (Exception ignored) {}
        }, 15, 15, TimeUnit.SECONDS);
        try {
            AgentState initial;
            RoutingMode rm = parseRoutingMode(form.routingMode());
            Locale locale = LocaleContextHolder.getLocale();

            if (form.isDirectMode()) {
                // directMode: classifier 생략, history만 로드
                String history = memoryService.getHistory(userId, form.threadId());
                initial = AgentState.of(form.question(), form.version(), form.threadId(),
                        userId, history, rm, true, locale);
            } else {
                // 일반 RAG 모드: history 로드 + 분류 병렬 실행
                try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
                    CompletableFuture<String> historyF = CompletableFuture.supplyAsync(
                            () -> memoryService.getHistory(userId, form.threadId()), exec);
                    CompletableFuture<String> typeF = CompletableFuture.supplyAsync(
                            () -> classifierService.classifyOnly(form.question(), locale), exec);

                    initial = AgentState.of(form.question(), form.version(), form.threadId(),
                                    userId, historyF.join(), rm, false, locale)
                            .withQuestionType(typeF.join());
                }
            }
            // carry the selected search-scope tags into the graph state.
            initial = initial.toBuilder().selectedTags(form.selectedTags()).build();

            listener = new SseGraphListener(emitter);
            AgentState result = agentGraph.runStreaming(initial, listener);

            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

            if (result.answer() != null && !result.answer().isBlank()) {
                memoryService.addTurn(userId, form.threadId(), form.question(), result.answer(),
                        askedAt, result.totalInputTokens(), result.totalOutputTokens(),
                        (int) elapsedMs, result.usedProvider(), result.llmCallCount());
            }

            sendEvent(emitter, "done", buildDonePayload(result, elapsedMs));
            emitter.complete();

            threadMetaService.generateTitleAsync(userId, form.threadId(), form.version(), form.question());

        } catch (LlmProviderExhaustedException e) {
            log.warn("LLM providers exhausted: {}", e.getMessage());
            String msg = messageSource.getMessage("error.llm.exhausted", null,
                    "LLM 서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.", LocaleContextHolder.getLocale());
            trySendError(emitter, msg);
            emitter.complete();
        } catch (Exception e) {
            // B-20: interrupt signals client disconnect / SSE timeout — not an error
            boolean interrupted = e instanceof InterruptedException
                    || e.getCause() instanceof InterruptedException
                    || Thread.currentThread().isInterrupted();
            if (interrupted) {
                log.debug("SSE worker cancelled (timeout/disconnect) thread={} causeType={} causeMsg={}",
                        form.threadId(), e.getClass().getSimpleName(), e.getMessage());
                Thread.currentThread().interrupt();
            } else {
                log.error("SSE streaming error", e);
            }
            // B-13: persist whatever answer was streamed so subsequent turns have context
            if (listener != null) {
                String partial = listener.getAccumulatedAnswer();
                if (!partial.isBlank()) {
                    try {
                        memoryService.addTurn(userId, form.threadId(), form.question(),
                                partial + "\n[오류로 중단됨]",
                                askedAt, 0, 0, 0, null, 0);
                        log.debug("[B-13] partial answer persisted ({} chars) thread={}",
                                partial.length(), form.threadId());
                    } catch (Exception persistEx) {
                        log.warn("Failed to persist partial answer on streaming error", persistEx);
                    }
                }
            }
            trySendError(emitter, e.getMessage());
            emitter.completeWithError(e);
        } finally {
            heartbeat.cancel(false);
        }
    }

    // ── SseGraphListener ─────────────────────────────────────────────────────

    private class SseGraphListener implements GraphListener {

        private final SseEmitter emitter;
        private final StringBuilder accumulated = new StringBuilder();

        SseGraphListener(SseEmitter emitter) {
            this.emitter = emitter;
        }

        String getAccumulatedAnswer() { return accumulated.toString(); }

        @Override
        public void onNodeEnter(String nodeName) {
            Map<String, String> payload = Map.of("id", nodeName, "text", stageText(nodeName));
            sendEvent(emitter, "stage", payload);
        }

        @Override
        public void onToken(String text) {
            accumulated.append(text);
            Map<String, Object> payload = new HashMap<>();
            payload.put("text", text);
            payload.put("tab", null);
            sendEvent(emitter, "token", payload);
        }

        @Override
        public void onToken(String tab, String text) {
            accumulated.append(text);
            Map<String, Object> payload = new HashMap<>();
            payload.put("text", text);
            payload.put("tab", tab);
            sendEvent(emitter, "token", payload);
        }

        @Override
        public void onSourcesReady(List<SourceRef> sources) {
            sendEvent(emitter, "sources", sources);
        }

        @Override
        public void onUpgrade(String provider) {
            Map<String, String> payload = Map.of(
                    "id", "upgrade",
                    "text", "고추론 재분석 중: " + provider);
            sendEvent(emitter, "stage", payload);
        }

        private String stageText(String nodeId) {
            return switch (nodeId) {
                case "classifier" -> "질문 분류 중...";
                case "retrieval"  -> "관련 문서 검색 중...";
                case "answer"     -> "답변 생성 중...";
                case "critic"     -> "답변 검증 중...";
                default           -> nodeId;
            };
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            emitter.send(SseEmitter.event().name(name).data(json));
        } catch (JsonProcessingException e) {
            log.warn("JSON serialization failed for SSE event '{}': {}", name, e.getMessage());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void trySendError(SseEmitter emitter, String message) {
        try {
            String json = objectMapper.writeValueAsString(
                    Map.of("message", message != null ? message : "서버 오류가 발생했습니다."));
            emitter.send(SseEmitter.event().name("error").data(json));
        } catch (Exception ignored) {}
    }

    private Map<String, Object> buildDonePayload(AgentState result, long elapsedMs) {
        Map<String, Object> m = new HashMap<>();
        m.put("usedProvider",      result.usedProvider());
        m.put("dualLocalProvider", result.dualLocalProvider());
        m.put("inputTokens",       result.totalInputTokens());
        m.put("outputTokens",      result.totalOutputTokens());
        m.put("llmCalls",          result.llmCallCount());
        m.put("elapsedMs",         elapsedMs);
        m.put("premiumUpgraded",   result.premiumUpgraded());
        m.put("questionType",      result.questionType());
        m.put("grounded",          result.grounded());
        m.put("refreshThreadList", true);
        return m;
    }

    private static RoutingMode parseRoutingMode(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return RoutingMode.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
