package com.example.ragagent.service;

import com.example.ragagent.agent.AgentGraph;
import com.example.ragagent.agent.AgentState;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.model.ChatForm;
import com.example.ragagent.model.SourceRef;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * SSE streaming pipeline orchestrator.
 * Mirrors AgentService.chat() setup but drives AgentGraph.runStreaming()
 * and forwards node/token/sources events to a SseEmitter.
 */
@Service
public class StreamingAgentService {

    private static final Logger log = LoggerFactory.getLogger(StreamingAgentService.class);

    private final AgentGraph agentGraph;
    private final MemoryService memoryService;
    private final ClassifierService classifierService;
    private final ThreadMetaService threadMetaService;
    private final ObjectMapper objectMapper;

    public StreamingAgentService(AgentGraph agentGraph,
                                  MemoryService memoryService,
                                  ClassifierService classifierService,
                                  ThreadMetaService threadMetaService,
                                  ObjectMapper objectMapper) {
        this.agentGraph = agentGraph;
        this.memoryService = memoryService;
        this.classifierService = classifierService;
        this.threadMetaService = threadMetaService;
        this.objectMapper = objectMapper;
    }

    /**
     * Runs the full agent pipeline in a Virtual Thread.
     * Caller should already have called threadMetaService.getOrCreate() before this.
     */
    public void run(ChatForm form, SseEmitter emitter) {
        long startNs = System.nanoTime();
        try {
            AgentState initial;
            RoutingMode rm = parseRoutingMode(form.routingMode());

            if (form.directMode()) {
                // directMode: classifier 생략, history만 로드
                String history = memoryService.getHistory(form.threadId());
                initial = AgentState.of(form.question(), form.version(), form.threadId(),
                        history, rm, true);
            } else {
                // 일반 RAG 모드: history 로드 + 분류 병렬 실행
                try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
                    CompletableFuture<String> historyF = CompletableFuture.supplyAsync(
                            () -> memoryService.getHistory(form.threadId()), exec);
                    CompletableFuture<String> typeF = CompletableFuture.supplyAsync(
                            () -> classifierService.classifyOnly(form.question()), exec);

                    initial = AgentState.of(form.question(), form.version(), form.threadId(),
                                    historyF.join(), rm)
                            .withQuestionType(typeF.join());
                }
            }

            SseGraphListener listener = new SseGraphListener(emitter);
            AgentState result = agentGraph.runStreaming(initial, listener);

            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
            sendEvent(emitter, "done", buildDonePayload(result, elapsedMs));
            emitter.complete();

            threadMetaService.generateTitleAsync(form.threadId(), form.version(), form.question());

        } catch (Exception e) {
            log.error("SSE streaming error", e);
            trySendError(emitter, e.getMessage());
            emitter.completeWithError(e);
        }
    }

    // ── SseGraphListener ─────────────────────────────────────────────────────

    private class SseGraphListener implements GraphListener {

        private final SseEmitter emitter;

        SseGraphListener(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void onNodeEnter(String nodeName) {
            Map<String, String> payload = Map.of("id", nodeName, "text", stageText(nodeName));
            sendEvent(emitter, "stage", payload);
        }

        @Override
        public void onToken(String text) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("text", text);
            payload.put("tab", null);
            sendEvent(emitter, "token", payload);
        }

        @Override
        public void onToken(String tab, String text) {
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
