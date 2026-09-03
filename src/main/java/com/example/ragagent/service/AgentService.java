package com.example.ragagent.service;

import com.example.ragagent.agent.AgentGraph;
import com.example.ragagent.agent.AgentState;
import com.example.ragagent.context.ThreadContext;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.model.ResponseMode;
import com.example.ragagent.model.ChatRequest;
import com.example.ragagent.model.TagUtils;
import com.example.ragagent.model.VerificationSnapshot;
import com.example.ragagent.model.ChatResponse;
import com.example.ragagent.security.PromptInjectionGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * Entry point for the agent pipeline. Builds initial AgentState,
 * injects conversation history, runs the graph, and returns ChatResponse.
 *
 * Equivalent to run_agent() in agents.py.
 */
@Service
public class AgentService {

        private static final Logger log = LoggerFactory.getLogger(AgentService.class);

        private final AgentGraph agentGraph;
        private final MemoryService memoryService;
        private final ClassifierService classifierService;
        private final ConversationSummarizerService summarizerService;
        private final QuestionReuseService questionReuseService;
        /** §10.12 — 짧은 후속 질문의 독립화. null 이면 이 단계 없이 원문으로 검색한다(테스트 편의). */
        private final QuestionCondenser questionCondenser;

        @Autowired
        public AgentService(AgentGraph agentGraph, MemoryService memoryService,
                                                ClassifierService classifierService,
                                                ConversationSummarizerService summarizerService,
                                                QuestionReuseService questionReuseService,
                                                QuestionCondenser questionCondenser) {
                this.agentGraph = agentGraph;
                this.memoryService = memoryService;
                this.classifierService = classifierService;
                this.summarizerService = summarizerService;
                this.questionReuseService = questionReuseService;
                this.questionCondenser = questionCondenser;
        }

        // Test/backward-compatible constructors.
        public AgentService(AgentGraph agentGraph, MemoryService memoryService,
                                                ClassifierService classifierService,
                                                ConversationSummarizerService summarizerService,
                                                QuestionReuseService questionReuseService) {
                this(agentGraph, memoryService, classifierService, summarizerService,
                        questionReuseService, null);
        }

        public AgentService(AgentGraph agentGraph, MemoryService memoryService,
                                                ClassifierService classifierService,
                                                ConversationSummarizerService summarizerService) {
                this(agentGraph, memoryService, classifierService, summarizerService, null, null);
        }

    public ChatResponse chat(ThreadContext ctx, ChatRequest request) {
        PromptInjectionGuard.validate(request.question());
        String userId = ctx.userId();
        log.debug("[AgentService] chat start — directMode={} routingMode={} thread={} locale={}",
                request.directMode(), request.routingMode(), request.threadId(), ctx.locale().getLanguage());
        AgentState initial;
        if (request.directMode()) {
            String history = resolveHistory(userId, request.threadId(), true,
                    request.responseMode(), request.routingMode(), request.question());
            initial = AgentState.of(request.question(), request.version(), request.threadId(),
                    userId, history, request.routingMode(), true, ctx.locale());
        } else {
            try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
                CompletableFuture<String> historyF = CompletableFuture.supplyAsync(
                        () -> resolveHistory(userId, request.threadId(), false,
                                request.responseMode(), request.routingMode(), request.question()), exec);
                // §10.12 — 짧은 후속 질문이면 먼저 독립화하고, 분류는 그 결과를 본다. 게이트가
                // 순수해서(길이만 본다) 긴 질문에서는 이 future 가 이미 완료된 채로 만들어지므로
                // 분류가 즉시 출발한다 — 오늘의 병렬성이 그대로다.
                CompletableFuture<QuestionCondenser.Condensed> condensedF =
                        condenseAsync(userId, request.threadId(), request.question(), ctx.locale(), exec);
                CompletableFuture<String> typeF = condensedF.thenApplyAsync(
                        c -> classifierService.classifyOnly(
                                c == null ? request.question() : c.searchQuestion(), ctx.locale()), exec);
                QuestionCondenser.Condensed condensed = condensedF.join();
                AgentState.Builder builder = AgentState.of(
                        request.question(),
                        request.version(),
                        request.threadId(),
                        userId,
                        historyF.join(),
                        request.routingMode(),
                        false, ctx.locale())
                    .toBuilder().questionType(typeF.join());
                if (condensed != null) {
                    // 그래프 바깥에서 일어난 호출이라 여기서 실어 준다 — 안 실으면 사용자가 보는
                    // LLM 호출 수·토큰이 실제보다 적게 나온다(classifyOnly 의 알려진 누락과 같은 함정).
                    builder.searchQuestion(condensed.searchQuestion())
                           .accumulateTokens(condensed.inputTokens(), condensed.outputTokens());
                }
                initial = builder.build();
            }
        }
        // carry the selected search-scope tags + answer-length mode into the graph state.
        initial = initial.toBuilder()
                .selectedTags(request.selectedTags())
                .responseMode(request.responseMode())
                .build();

        String askedAt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC).format(Instant.now());
        long startNano = System.nanoTime();
        AgentState result = agentGraph.run(initial);
        long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;
        double elapsedSeconds = elapsedMs / 1000.0;

        Long turnId = null;
        if (result.answer() != null && !result.answer().isBlank()) {
            turnId = memoryService.addTurn(userId, request.threadId(), request.question(), result.answer(),
                    askedAt, result.totalInputTokens(), result.totalOutputTokens(),
                    (int) elapsedMs, result.usedProvider(), result.llmCallCount(),
                    request.responseMode().name(), TagUtils.toMetaValue(request.selectedTags()),
                    request.directMode());
                        memoryService.saveTurnImageRefs(turnId, userId, request.threadId(), result.imageRefs());
            memoryService.saveRetrievalMetrics(turnId, result.sources());
            memoryService.saveVerification(turnId, new VerificationSnapshot(
                    result.grounded(), result.responseMode().generative(),
                    result.evalReason(), result.envNote(), result.inventedSymbols(),
                    result.budgetNote(),
                    result.wasCondensed() ? result.searchQuestion() : null));
            if (questionReuseService != null) {
                questionReuseService.recordTurnSources(turnId, userId, request.threadId(),
                        result.retrievedDocs(), result.sources());
            }
            summarizerService.precomputeAfterTurn(userId, request.threadId(), turnId, ctx.locale());
        }

        return new ChatResponse(
                result.answer(),
                result.questionType(),
                result.sources(),
                result.imageRefs(),
                result.totalInputTokens(),
                result.totalOutputTokens(),
                result.llmCallCount(),
                elapsedSeconds,
                result.premiumUpgraded(),
                result.usedProvider(),
                turnId,
                result.grounded(),
                result.evalReason(),
                result.envNote(),
                result.budgetNote(),
                result.responseMode().generative(),
                result.inventedSymbols(),
                result.wasCondensed() ? result.searchQuestion() : null
        );
    }

    /**
     * §10.12 — 게이트가 닫혀 있으면 <b>LLM 을 부르지 않고</b> 이미 완료된 future 를 돌려준다.
     * 값이 {@code null} 이면 "재작성 없음"이고, 그 경우 검색·분류는 원문을 쓴다.
     */
    private CompletableFuture<QuestionCondenser.Condensed> condenseAsync(
            String userId, String threadId, String question,
            java.util.Locale locale, java.util.concurrent.Executor exec) {
        if (questionCondenser == null || !questionCondenser.gateOpen(question)) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(
                () -> questionCondenser.condense(userId, threadId, question, locale).orElse(null), exec);
    }

    /**
     * §6.10: use the precomputed summary + recent turns when available, else full raw history.
     *
     * <p>§10.13 — 예산과 "지금 묻는 턴이 Direct 인가"를 <b>두 경로에 같이</b> 넘긴다. 한쪽만 받으면
     * 같은 스레드가 요약 캐시 유무에 따라 다른 맥락을 보고, 캐시 TTL 이 지나는 순간 이력이 갑자기
     * 달라진다. {@code streaming=false} — 이 경로는 블로킹 호출이라 출력 예약이 그만큼 크다.
     */
    private String resolveHistory(String userId, String threadId, boolean askingDirect,
                                  ResponseMode mode, RoutingMode routingMode, String question) {
        int budget = memoryService.maxConversationChars(askingDirect, mode, routingMode, false, question);
        String precomputed = summarizerService.buildContext(userId, threadId, budget, askingDirect);
        return precomputed != null ? precomputed
                : memoryService.getHistory(userId, threadId, budget, askingDirect);
    }
}
