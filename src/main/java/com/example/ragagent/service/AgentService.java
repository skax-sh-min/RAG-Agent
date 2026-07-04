package com.example.ragagent.service;

import com.example.ragagent.agent.AgentGraph;
import com.example.ragagent.agent.AgentState;
import com.example.ragagent.context.ThreadContext;
import com.example.ragagent.model.ChatRequest;
import com.example.ragagent.model.ChatResponse;
import com.example.ragagent.security.PromptInjectionGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public AgentService(AgentGraph agentGraph, MemoryService memoryService,
                        ClassifierService classifierService,
                        ConversationSummarizerService summarizerService) {
        this.agentGraph = agentGraph;
        this.memoryService = memoryService;
        this.classifierService = classifierService;
        this.summarizerService = summarizerService;
    }

    public ChatResponse chat(ThreadContext ctx, ChatRequest request) {
        PromptInjectionGuard.validate(request.question());
        String userId = ctx.userId();
        log.debug("[AgentService] chat start — directMode={} routingMode={} thread={} locale={}",
                request.directMode(), request.routingMode(), request.threadId(), ctx.locale().getLanguage());
        AgentState initial;
        if (request.directMode()) {
            String history = resolveHistory(userId, request.threadId());
            initial = AgentState.of(request.question(), request.version(), request.threadId(),
                    userId, history, request.routingMode(), true, ctx.locale());
        } else {
            try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
                CompletableFuture<String> historyF = CompletableFuture.supplyAsync(
                        () -> resolveHistory(userId, request.threadId()), exec);
                CompletableFuture<String> typeF = CompletableFuture.supplyAsync(
                        () -> classifierService.classifyOnly(request.question(), ctx.locale()), exec);
                initial = AgentState.of(
                        request.question(),
                        request.version(),
                        request.threadId(),
                        userId,
                        historyF.join(),
                        request.routingMode(),
                        false, ctx.locale())
                    .toBuilder().questionType(typeF.join()).build();
            }
        }
        // carry the selected search-scope tags into the graph state.
        initial = initial.toBuilder().selectedTags(request.selectedTags()).build();

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
                    (int) elapsedMs, result.usedProvider(), result.llmCallCount());
            summarizerService.invalidate(request.threadId());
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
                result.dualLocalAnswer(),
                result.dualLocalProvider(),
                turnId
        );
    }

    // §6.10: use the precomputed summary + recent turns when available, else full raw history.
    private String resolveHistory(String userId, String threadId) {
        String precomputed = summarizerService.buildContext(userId, threadId);
        return precomputed != null ? precomputed : memoryService.getHistory(userId, threadId);
    }
}
