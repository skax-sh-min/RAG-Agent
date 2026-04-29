package com.example.ragagent.agent;

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
        String answer,
        int retryCount,
        boolean needsRetry,
        String conversationHistory,
        int totalInputTokens,
        int totalOutputTokens,
        int llmCallCount
) {
    // Defensive copy — guarantees List fields are always unmodifiable
    public AgentState {
        retrievedDocs     = retrievedDocs     == null ? List.of() : List.copyOf(retrievedDocs);
        sources           = sources           == null ? List.of() : List.copyOf(sources);
        retrievalWarnings = retrievalWarnings == null ? List.of() : List.copyOf(retrievalWarnings);
    }

    public static AgentState of(String question, String version, String threadId, String conversationHistory) {
        return new AgentState(
                question, version, threadId,
                null, List.of(), List.of(), List.of(),
                null, 0, false,
                conversationHistory,
                0, 0, 0);
    }

    public AgentState withQuestionType(String questionType) {
        return new AgentState(question, version, threadId, questionType,
                retrievedDocs, sources, retrievalWarnings,
                answer, retryCount, needsRetry, conversationHistory,
                totalInputTokens, totalOutputTokens, llmCallCount);
    }

    public AgentState withRetrievedDocs(List<Document> retrievedDocs) {
        return new AgentState(question, version, threadId, questionType,
                retrievedDocs, sources, retrievalWarnings,
                answer, retryCount, needsRetry, conversationHistory,
                totalInputTokens, totalOutputTokens, llmCallCount);
    }

    public AgentState withSources(List<SourceRef> sources) {
        return new AgentState(question, version, threadId, questionType,
                retrievedDocs, sources, retrievalWarnings,
                answer, retryCount, needsRetry, conversationHistory,
                totalInputTokens, totalOutputTokens, llmCallCount);
    }

    public AgentState withRetrievalWarnings(List<String> retrievalWarnings) {
        return new AgentState(question, version, threadId, questionType,
                retrievedDocs, sources, retrievalWarnings,
                answer, retryCount, needsRetry, conversationHistory,
                totalInputTokens, totalOutputTokens, llmCallCount);
    }

    public AgentState withAnswer(String answer) {
        return new AgentState(question, version, threadId, questionType,
                retrievedDocs, sources, retrievalWarnings,
                answer, retryCount, needsRetry, conversationHistory,
                totalInputTokens, totalOutputTokens, llmCallCount);
    }

    public AgentState withNeedsRetry(boolean needsRetry) {
        return new AgentState(question, version, threadId, questionType,
                retrievedDocs, sources, retrievalWarnings,
                answer, retryCount, needsRetry, conversationHistory,
                totalInputTokens, totalOutputTokens, llmCallCount);
    }

    public AgentState withRetryCountIncremented() {
        return new AgentState(question, version, threadId, questionType,
                retrievedDocs, sources, retrievalWarnings,
                answer, retryCount + 1, needsRetry, conversationHistory,
                totalInputTokens, totalOutputTokens, llmCallCount);
    }

    public AgentState withTokensAccumulated(int inputTokens, int outputTokens) {
        return new AgentState(question, version, threadId, questionType,
                retrievedDocs, sources, retrievalWarnings,
                answer, retryCount, needsRetry, conversationHistory,
                totalInputTokens + inputTokens,
                totalOutputTokens + outputTokens,
                llmCallCount + 1);
    }
}
