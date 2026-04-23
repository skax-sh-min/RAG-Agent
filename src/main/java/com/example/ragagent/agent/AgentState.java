package com.example.ragagent.agent;

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
        List<String> sources,
        List<String> retrievalWarnings,
        String answer,
        int retryCount,
        boolean needsRetry,
        String conversationHistory
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
                conversationHistory);
    }

    public AgentState withQuestionType(String questionType) {
        return new AgentState(question, version, threadId, questionType,
                retrievedDocs, sources, retrievalWarnings,
                answer, retryCount, needsRetry, conversationHistory);
    }

    public AgentState withRetrievedDocs(List<Document> retrievedDocs) {
        return new AgentState(question, version, threadId, questionType,
                retrievedDocs, sources, retrievalWarnings,
                answer, retryCount, needsRetry, conversationHistory);
    }

    public AgentState withSources(List<String> sources) {
        return new AgentState(question, version, threadId, questionType,
                retrievedDocs, sources, retrievalWarnings,
                answer, retryCount, needsRetry, conversationHistory);
    }

    public AgentState withRetrievalWarnings(List<String> retrievalWarnings) {
        return new AgentState(question, version, threadId, questionType,
                retrievedDocs, sources, retrievalWarnings,
                answer, retryCount, needsRetry, conversationHistory);
    }

    public AgentState withAnswer(String answer) {
        return new AgentState(question, version, threadId, questionType,
                retrievedDocs, sources, retrievalWarnings,
                answer, retryCount, needsRetry, conversationHistory);
    }

    public AgentState withNeedsRetry(boolean needsRetry) {
        return new AgentState(question, version, threadId, questionType,
                retrievedDocs, sources, retrievalWarnings,
                answer, retryCount, needsRetry, conversationHistory);
    }

    public AgentState withRetryCountIncremented() {
        return new AgentState(question, version, threadId, questionType,
                retrievedDocs, sources, retrievalWarnings,
                answer, retryCount + 1, needsRetry, conversationHistory);
    }
}
