package com.example.ragagent.service;

import com.example.ragagent.agent.AgentGraph;
import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.exception.LlmBackpressureException;
import com.example.ragagent.exception.LlmProviderExhaustedException;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.model.ChatForm;
import com.example.ragagent.model.TagUtils;
import com.example.ragagent.model.SourceRef;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

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
    private final ConversationSummarizerService summarizerService;
    private final AppProperties props;
    private final ChatImageAnalysisSkipRegistry imageSkipRegistry;
    private final QuestionReuseService questionReuseService;

    /**
     * Clock backing the idle watchdog (below). Production passes {@code System::nanoTime}; tests
     * substitute a hand-advanced source so the watchdog's verdict depends only on simulated
     * progress, not on how much wall-clock time the JVM actually spent — under a parallel test
     * suite a CPU stall would otherwise look exactly like an idle pipeline.
     */
    private final LongSupplier nanoTimeSource;

    // Required now that the class has two constructors — Spring's "single public constructor"
    // auto-detection only fires when exactly one is visible; with two, autowiring needs an
    // explicit @Autowired on the one Spring should call, or bean creation fails at startup
    // (NoSuchMethodException: <init>() — Spring falls back to looking for a no-arg constructor).
    @Autowired
    public StreamingAgentService(AgentGraph agentGraph,
                                 MemoryService memoryService,
                                 ClassifierService classifierService,
                                 ThreadMetaService threadMetaService,
                                 ObjectMapper objectMapper,
                                 MessageSource messageSource,
                                 ConversationSummarizerService summarizerService,
                                 AppProperties props,
                                 ChatImageAnalysisSkipRegistry imageSkipRegistry,
                                 QuestionReuseService questionReuseService) {
        this(agentGraph, memoryService, classifierService, threadMetaService, objectMapper,
                messageSource, summarizerService, props, imageSkipRegistry, questionReuseService,
                System::nanoTime);
    }

    // Backward-compatible constructor for tests that don't care about question reuse.
    public StreamingAgentService(AgentGraph agentGraph,
                                 MemoryService memoryService,
                                 ClassifierService classifierService,
                                 ThreadMetaService threadMetaService,
                                 ObjectMapper objectMapper,
                                 MessageSource messageSource,
                                 ConversationSummarizerService summarizerService,
                                 AppProperties props,
                                 ChatImageAnalysisSkipRegistry imageSkipRegistry,
                                 LongSupplier nanoTimeSource) {
        this(agentGraph, memoryService, classifierService, threadMetaService, objectMapper,
                messageSource, summarizerService, props, imageSkipRegistry, null, nanoTimeSource);
    }

    /** Test seam — see {@link #nanoTimeSource}. */
    StreamingAgentService(AgentGraph agentGraph,
                          MemoryService memoryService,
                          ClassifierService classifierService,
                          ThreadMetaService threadMetaService,
                          ObjectMapper objectMapper,
                          MessageSource messageSource,
                          ConversationSummarizerService summarizerService,
                          AppProperties props,
                          ChatImageAnalysisSkipRegistry imageSkipRegistry,
                          QuestionReuseService questionReuseService,
                          LongSupplier nanoTimeSource) {
        this.agentGraph = agentGraph;
        this.memoryService = memoryService;
        this.classifierService = classifierService;
        this.threadMetaService = threadMetaService;
        this.objectMapper = objectMapper;
        this.messageSource = messageSource;
        this.summarizerService = summarizerService;
        this.props = props;
        this.imageSkipRegistry = imageSkipRegistry;
        this.questionReuseService = questionReuseService;
        this.nanoTimeSource = nanoTimeSource;
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
        // run() executes as the body of the worker virtual thread (see ChatController),
        // so this IS the thread to interrupt when the pipeline goes idle too long.
        Thread worker = Thread.currentThread();
        AtomicLong lastActivityNanos = new AtomicLong(nanoTimeSource.getAsLong());
        long idleTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(props.sseIdleTimeoutMs());
        // Resets any stale flag a prior turn on this same thread might have left set — the registry
        // is keyed by threadId, not per-turn, so a fresh begin() here is what makes a leftover skip
        // click from turn N harmless to turn N+1.
        imageSkipRegistry.begin(form.threadId());

        ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleAtFixedRate(() -> {
            try { emitter.send(SseEmitter.event().comment("heartbeat")); }
            catch (Exception ignored) {}
        }, 15, 15, TimeUnit.SECONDS);
        // Idle watchdog: aborts only when the graph makes NO forward progress (no node
        // transition, token, or sources-ready event) for sseIdleTimeoutMs — a slow-but-actively-
        // generating local LLM response is never cut off. SseEmitter's own timeout
        // (props.sseTimeoutMs(), see ChatController) stays as a generous absolute backstop.
        // Check interval scales with the configured idle timeout (~6 checks per window) so a
        // shorter-than-default idle timeout is still detected promptly.
        long checkIntervalMs = Math.max(100, props.sseIdleTimeoutMs() / 6);
        ScheduledFuture<?> idleWatchdog = heartbeatScheduler.scheduleWithFixedDelay(() -> {
            long idleNanos = nanoTimeSource.getAsLong() - lastActivityNanos.get();
            if (idleNanos > idleTimeoutNanos) {
                log.warn("[TIMEOUT:SSE_IDLE] thread={} idleMs={} (app.sse-idle-timeout-seconds={}s)",
                        form.threadId(), TimeUnit.NANOSECONDS.toMillis(idleNanos), props.sseIdleTimeoutMs() / 1000);
                worker.interrupt();
            }
        }, checkIntervalMs, checkIntervalMs, TimeUnit.MILLISECONDS);
        try {
            AgentState initial;
            RoutingMode rm = parseRoutingMode(form.routingMode());
            Locale locale = LocaleContextHolder.getLocale();

            if (form.isDirectMode()) {
                // directMode: classifier 생략, history만 로드
                String history = resolveHistory(userId, form.threadId());
                initial = AgentState.of(form.question(), form.version(), form.threadId(),
                        userId, history, rm, true, locale);
            } else {
                // 일반 RAG 모드: history 로드 + 분류 병렬 실행
                try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
                    CompletableFuture<String> historyF = CompletableFuture.supplyAsync(
                            () -> resolveHistory(userId, form.threadId()), exec);
                    CompletableFuture<String> typeF = CompletableFuture.supplyAsync(
                            () -> classifierService.classifyOnly(form.question(), locale), exec);

                    initial = AgentState.of(form.question(), form.version(), form.threadId(),
                                    userId, historyF.join(), rm, false, locale)
                            .toBuilder().questionType(typeF.join()).build();
                }
            }
            // carry the selected search-scope tags + answer-length mode into the graph state.
            initial = initial.toBuilder()
                    .selectedTags(form.selectedTags())
                    .responseMode(form.responseModeOrDefault())
                    .build();

            listener = new SseGraphListener(emitter, lastActivityNanos);
            AgentState result = agentGraph.runStreaming(initial, listener);

            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

            Long turnId = null;
            if (result.answer() != null && !result.answer().isBlank()) {
                turnId = memoryService.addTurn(userId, form.threadId(), form.question(), result.answer(),
                        askedAt, result.totalInputTokens(), result.totalOutputTokens(),
                        (int) elapsedMs, result.usedProvider(), result.llmCallCount(),
                    form.responseModeOrDefault().name(), TagUtils.toMetaValue(form.selectedTags()),
                    form.isDirectMode());
                memoryService.saveTurnImageRefs(turnId, userId, form.threadId(), result.imageRefs());
                memoryService.saveRetrievalMetrics(turnId, result.sources());
                if (questionReuseService != null) {
                    questionReuseService.recordTurnSources(turnId, userId, form.threadId(), result.retrievedDocs());
                }
                summarizerService.precomputeAfterTurn(userId, form.threadId(), turnId, locale);
            }

            sendEvent(emitter, "done", buildDonePayload(result, elapsedMs, turnId));
            emitter.complete();

            threadMetaService.generateTitleAsync(userId, form.threadId(), form.version(), form.question());

        } catch (LlmProviderExhaustedException e) {
            log.warn("LLM providers exhausted: {}", e.getMessage());
            String msg = messageSource.getMessage("error.llm.exhausted", null,
                    "LLM 서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.", LocaleContextHolder.getLocale());
            trySendError(emitter, msg);
            emitter.complete();
        } catch (LlmBackpressureException e) {
            // Provider is healthy but momentarily at capacity — not an error, just backpressure.
            log.warn("LLM backpressure: {}", e.getMessage());
            String msg = messageSource.getMessage("error.llm.backpressure", null,
                    "현재 요청이 몰려 있습니다. 잠시 후 다시 시도해 주세요.", LocaleContextHolder.getLocale());
            trySendError(emitter, msg);
            emitter.complete();
        } catch (Exception e) {
            // interrupt signals client disconnect / SSE timeout — not an error
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
            // persist whatever answer was streamed so subsequent turns have context
            if (listener != null) {
                String partial = listener.getAccumulatedAnswer();
                if (!partial.isBlank()) {
                    try {
                        memoryService.addTurn(userId, form.threadId(), form.question(),
                                partial + "\n[오류로 중단됨]",
                                askedAt, 0, 0, 0, null, 0, form.responseModeOrDefault().name(),
                            TagUtils.toMetaValue(form.selectedTags()), form.isDirectMode());
                        log.debug("partial answer persisted ({} chars) thread={}",
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
            idleWatchdog.cancel(false);
            imageSkipRegistry.end(form.threadId());
        }
    }

    // ── SseGraphListener ─────────────────────────────────────────────────────

    private class SseGraphListener implements GraphListener {

        private final SseEmitter emitter;
        private final AtomicLong lastActivityNanos;
        private final StringBuilder accumulated = new StringBuilder();
        private boolean waitingFirstAnswerToken;

        SseGraphListener(SseEmitter emitter, AtomicLong lastActivityNanos) {
            this.emitter = emitter;
            this.lastActivityNanos = lastActivityNanos;
        }

        String getAccumulatedAnswer() { return accumulated.toString(); }

        @Override
        public void onNodeEnter(String nodeName) {
            lastActivityNanos.set(nanoTimeSource.getAsLong());
            waitingFirstAnswerToken = "answer".equals(nodeName);
            Map<String, String> payload = Map.of("id", nodeName, "text", stageText(nodeName));
            sendEvent(emitter, "stage", payload);
        }

        @Override
        public void onToken(String text) {
            lastActivityNanos.set(nanoTimeSource.getAsLong());
            if (waitingFirstAnswerToken) {
                waitingFirstAnswerToken = false;
                sendEvent(emitter, "stage", Map.of("id", "answer", "text", "답변 생성 중..."));
            }
            accumulated.append(text);
            Map<String, Object> payload = new HashMap<>();
            payload.put("text", text);
            sendEvent(emitter, "token", payload);
        }

        @Override
        public void onSourcesReady(List<SourceRef> sources) {
            lastActivityNanos.set(nanoTimeSource.getAsLong());
            sendEvent(emitter, "sources", sources);
        }

        @Override
        public void onImagesReady(List<String> imageRefs) {
            lastActivityNanos.set(nanoTimeSource.getAsLong());
            if (!imageRefs.isEmpty()) sendEvent(emitter, "images", imageRefs);
        }

        @Override
        public void onImageAnalysisProgress(int done, int total) {
            lastActivityNanos.set(nanoTimeSource.getAsLong());
            // Reuses the "stage" event (id="image_analysis", distinct from "retrieval") rather than
            // a dedicated event type — chat-stream.js's onStage() already renders whatever text the
            // server sends into the same stage badge, so no new client handler is needed. A distinct
            // id (not "retrieval") matters: onStage() clears the images/sources panels on id="retrieval"
            // re-entry (retry semantics), and this fires several times per turn.
            Map<String, String> payload = Map.of(
                    "id", "image_analysis",
                    "text", "이미지 분석 중 (" + done + "/" + total + ")");
            sendEvent(emitter, "stage", payload);
        }

        @Override
        public void onUpgrade(String provider) {
            lastActivityNanos.set(nanoTimeSource.getAsLong());
            Map<String, String> payload = Map.of(
                    "id", "upgrade",
                    "text", "고추론 재분석 중: " + provider);
            sendEvent(emitter, "stage", payload);
        }

        @Override
        public void onVerifying() {
            lastActivityNanos.set(nanoTimeSource.getAsLong());
            sendEvent(emitter, "verifying", Map.of());
        }

        @Override
        public void onRetry(String reason, int retryCount, String detail) {
            lastActivityNanos.set(nanoTimeSource.getAsLong());
            Map<String, Object> payload = new HashMap<>();
            payload.put("reason", reason);
            payload.put("retryCount", retryCount);
            payload.put("detail", detail);
            payload.put("text", "이 답변이 검증 조건을 통과하지 못해, 검색 범위를 넓혀 다시 시도하고 있습니다… (재시도 "
                    + retryCount + ")");
            sendEvent(emitter, "retry", payload);
            // Superseded attempts are never persisted (only the final answer is), so drop the
            // accumulated buffer here — an error mid-retry then persists only the latest attempt.
            accumulated.setLength(0);
        }

        private String stageText(String nodeId) {
            return switch (nodeId) {
                case "classifier" -> "질문 분류 중...";
                case "retrieval"  -> "관련 문서 검색 중...";
                case "answer"     -> "답변 생각 중...";
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

    private Map<String, Object> buildDonePayload(AgentState result, long elapsedMs, Long turnId) {
        Map<String, Object> m = new HashMap<>();
        m.put("usedProvider",      result.usedProvider());
        m.put("inputTokens",       result.totalInputTokens());
        m.put("outputTokens",      result.totalOutputTokens());
        m.put("llmCalls",          result.llmCallCount());
        m.put("elapsedMs",         elapsedMs);
        m.put("premiumUpgraded",   result.premiumUpgraded());
        m.put("questionType",      result.questionType());
        m.put("grounded",          result.grounded());
        // 재시도를 다 쓰고도 검증을 통과하지 못한 채 전달되는 답변이 있다 — 그 경우 미검증 배지만
        // 띄우고 이유를 감추면 사용자가 할 수 있는 게 없다. 통과했으면 null 이라 UI가 그냥 생략한다.
        m.put("evalReason",        result.evalReason());
        // 경로·주소·포트·환경변수 값 안내 — 검증 통과 여부와 무관하게 실릴 수 있다(통과한 답변에도
        // "이 경로는 본인 환경 기준으로 바꿔야 한다"는 안내가 필요하다).
        m.put("envNote",           result.envNote());
        // 2단계 응답 참여도 — 출처 배지는 RETRIEVAL 직후의 `sources` 이벤트로 이미 그려졌고, 참여도는
        // 답변이 끝나야 나오므로 여기서 chunkId 기준으로 사후 갱신한다(값이 없으면 키 자체가 빠져
        // 클라이언트가 아무것도 하지 않는다).
        Map<String, Double> shares = new LinkedHashMap<>();
        for (SourceRef s : result.sources()) {
            if (s.chunkId() != null && s.answerShare() != null) shares.put(s.chunkId(), s.answerShare());
        }
        if (!shares.isEmpty()) m.put("attribution", shares);
        m.put("refreshThreadList", true);
        m.put("turnId",            turnId);
        return m;
    }

    // §6.10: use the precomputed summary + recent turns when available, else full raw history.
    private String resolveHistory(String userId, String threadId) {
        String precomputed = summarizerService.buildContext(userId, threadId);
        return precomputed != null ? precomputed : memoryService.getHistory(userId, threadId);
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
