package com.example.ragagent.agent;

import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.model.SourceRef;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * Immutable state record passed through all agent graph nodes.
 * Each node returns a new instance via withXxx() — no shared mutable state.
 * Equivalent to LangGraph's TypedDict AgentState in the Python version.
 */
public record AgentState(
        String question,
        String version,
        String threadId,
        String questionType,
        List<Document> retrievedDocs,
        List<SourceRef> sources,
        List<String> retrievalWarnings,
        List<String> imageRefs,
        String answer,
        int retryCount,
        boolean needsRetry,
        String conversationHistory,
        int totalInputTokens,
        int totalOutputTokens,
        int llmCallCount,
        RoutingMode routingMode,
        String usedProvider,
        String premiumUpgraded,   // PROGRESSIVE: PREMIUM 프로바이더명 (null=미적용)
        String dualLocalAnswer,   // DUAL: LOCAL 모델 답변
        String dualLocalProvider, // DUAL: LOCAL 프로바이더명
        Boolean grounded,         // CRITIC 결과 (null=CRITIC 미실행)
        boolean directMode        // RAG 없이 LLM 직접 호출
) {
    public AgentState {
        retrievedDocs     = retrievedDocs     == null ? List.of() : List.copyOf(retrievedDocs);
        sources           = sources           == null ? List.of() : List.copyOf(sources);
        retrievalWarnings = retrievalWarnings == null ? List.of() : List.copyOf(retrievalWarnings);
        imageRefs         = imageRefs         == null ? List.of() : List.copyOf(imageRefs);
        if (routingMode == null) routingMode = RoutingMode.COST_FIRST;
    }

    public static AgentState of(String question, String version, String threadId,
                                 String conversationHistory, RoutingMode routingMode) {
        return of(question, version, threadId, conversationHistory, routingMode, false);
    }

    public static AgentState of(String question, String version, String threadId,
                                 String conversationHistory, RoutingMode routingMode,
                                 boolean directMode) {
        return new AgentState(
                question, version, threadId,
                null, List.of(), List.of(), List.of(), List.of(),
                null, 0, false,
                conversationHistory,
                0, 0, 0,
                routingMode, null, null, null, null, null,
                directMode);
    }

    public boolean isDualMode()  { return routingMode == RoutingMode.DUAL; }
    public boolean wasUpgraded() { return premiumUpgraded != null; }

    public AgentState withQuestionType(String questionType) {
        return new AgentState(question, version, threadId, questionType,
                retrievedDocs, sources, retrievalWarnings, imageRefs,
                answer, retryCount, needsRetry, conversationHistory,
                totalInputTokens, totalOutputTokens, llmCallCount,
                routingMode, usedProvider, premiumUpgraded, dualLocalAnswer, dualLocalProvider, grounded,
                directMode);
    }

    public AgentState withRetrievedDocs(List<Document> retrievedDocs) {
        return new AgentState(question, version, threadId, questionType,
                retrievedDocs, sources, retrievalWarnings, imageRefs,
                answer, retryCount, needsRetry, conversationHistory,
                totalInputTokens, totalOutputTokens, llmCallCount,
                routingMode, usedProvider, premiumUpgraded, dualLocalAnswer, dualLocalProvider, grounded,
                directMode);
    }

    public AgentState withSources(List<SourceRef> sources) {
        return new AgentState(question, version, threadId, questionType,
                retrievedDocs, sources, retrievalWarnings, imageRefs,
                answer, retryCount, needsRetry, conversationHistory,
                totalInputTokens, totalOutputTokens, llmCallCount,
                routingMode, usedProvider, premiumUpgraded, dualLocalAnswer, dualLocalProvider, grounded,
                directMode);
    }

    public AgentState withRetrievalWarnings(List<String> retrievalWarnings) {
        return new AgentState(question, version, threadId, questionType,
                retrievedDocs, sources, retrievalWarnings, imageRefs,
                answer, retryCount, needsRetry, conversationHistory,
                totalInputTokens, totalOutputTokens, llmCallCount,
                routingMode, usedProvider, premiumUpgraded, dualLocalAnswer, dualLocalProvider, grounded,
                directMode);
    }

    public AgentState withImageRefs(List<String> imageRefs) {
        return new AgentState(question, version, threadId, questionType,
                retrievedDocs, sources, retrievalWarnings, imageRefs,
                answer, retryCount, needsRetry, conversationHistory,
                totalInputTokens, totalOutputTokens, llmCallCount,
                routingMode, usedProvider, premiumUpgraded, dualLocalAnswer, dualLocalProvider, grounded,
                directMode);
    }

    public AgentState withAnswer(String answer) {
        return new AgentState(question, version, threadId, questionType,
                retrievedDocs, sources, retrievalWarnings, imageRefs,
                answer, retryCount, needsRetry, conversationHistory,
                totalInputTokens, totalOutputTokens, llmCallCount,
                routingMode, usedProvider, premiumUpgraded, dualLocalAnswer, dualLocalProvider, grounded,
                directMode);
    }

    public AgentState withNeedsRetry(boolean needsRetry) {
        return new AgentState(question, version, threadId, questionType,
                retrievedDocs, sources, retrievalWarnings, imageRefs,
                answer, retryCount, needsRetry, conversationHistory,
                totalInputTokens, totalOutputTokens, llmCallCount,
                routingMode, usedProvider, premiumUpgraded, dualLocalAnswer, dualLocalProvider, grounded,
                directMode);
    }

    public AgentState withRetryCountIncremented() {
        return new AgentState(question, version, threadId, questionType,
                retrievedDocs, sources, retrievalWarnings, imageRefs,
                answer, retryCount + 1, needsRetry, conversationHistory,
                totalInputTokens, totalOutputTokens, llmCallCount,
                routingMode, usedProvider, premiumUpgraded, dualLocalAnswer, dualLocalProvider, grounded,
                directMode);
    }

    public AgentState withTokensAccumulated(int inputTokens, int outputTokens) {
        return new AgentState(question, version, threadId, questionType,
                retrievedDocs, sources, retrievalWarnings, imageRefs,
                answer, retryCount, needsRetry, conversationHistory,
                totalInputTokens + inputTokens,
                totalOutputTokens + outputTokens,
                llmCallCount + 1,
                routingMode, usedProvider, premiumUpgraded, dualLocalAnswer, dualLocalProvider, grounded,
                directMode);
    }

    public AgentState withUsedProvider(String usedProvider) {
        return new AgentState(question, version, threadId, questionType,
                retrievedDocs, sources, retrievalWarnings, imageRefs,
                answer, retryCount, needsRetry, conversationHistory,
                totalInputTokens, totalOutputTokens, llmCallCount,
                routingMode, usedProvider, premiumUpgraded, dualLocalAnswer, dualLocalProvider, grounded,
                directMode);
    }

    public AgentState withPremiumUpgraded(String premiumUpgraded) {
        return new AgentState(question, version, threadId, questionType,
                retrievedDocs, sources, retrievalWarnings, imageRefs,
                answer, retryCount, needsRetry, conversationHistory,
                totalInputTokens, totalOutputTokens, llmCallCount,
                routingMode, usedProvider, premiumUpgraded, dualLocalAnswer, dualLocalProvider, grounded,
                directMode);
    }

    public AgentState withDualResult(String dualLocalAnswer, String dualLocalProvider) {
        return new AgentState(question, version, threadId, questionType,
                retrievedDocs, sources, retrievalWarnings, imageRefs,
                answer, retryCount, needsRetry, conversationHistory,
                totalInputTokens, totalOutputTokens, llmCallCount,
                routingMode, usedProvider, premiumUpgraded, dualLocalAnswer, dualLocalProvider, grounded,
                directMode);
    }

    public AgentState withGrounded(Boolean grounded) {
        return new AgentState(question, version, threadId, questionType,
                retrievedDocs, sources, retrievalWarnings, imageRefs,
                answer, retryCount, needsRetry, conversationHistory,
                totalInputTokens, totalOutputTokens, llmCallCount,
                routingMode, usedProvider, premiumUpgraded, dualLocalAnswer, dualLocalProvider, grounded,
                directMode);
    }
}
