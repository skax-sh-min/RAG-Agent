package com.example.ragagent.repository;

import java.util.List;
import java.util.Optional;

public interface MemoryRepository {
    String getHistory(String userId, String threadId, int maxChars);

    /** Returns the generated turn id (conversation_turns.id). */
    long addTurn(String userId, String threadId, String question, String answer,
                 String askedAt, int inputTokens, int outputTokens,
                 int elapsedMs, String provider, int llmCalls);

    void clearHistory(String userId, String threadId);

    /** Returns all turns for the thread in chronological order (oldest first). */
    List<Turn> getTurns(String userId, String threadId);

    /**
     * Same as {@link #getTurns}, but capped to the most recent {@code app.memory.fetch-limit-turns}
     * (the same bound {@link #getHistory} applies) — for callers that feed turns into an LLM call
     * (e.g. summarization) and must not let cost grow unbounded with conversation length. Still
     * returned in chronological order (oldest first).
     */
    List<Turn> getRecentTurns(String userId, String threadId);

    /** Current feedback value for ownership check + audit "from". Empty = turn not found / not owned. */
    Optional<FeedbackRow> getFeedback(String userId, String threadId, long turnId);

    /** {@code feedback}: {@code "LIKE" | "DISLIKE" | null}. No-op if the turn isn't owned by userId/threadId. */
    void updateFeedback(String userId, String threadId, long turnId, String feedback);

    record Turn(long id, String question, String answer,
                String askedAt, String answeredAt,
                int inputTokens, int outputTokens,
                int elapsedMs, String provider, int llmCalls,
                String feedback) {}

    /** Wraps a nullable feedback value so "found with NULL feedback" is distinguishable from "not found". */
    record FeedbackRow(String feedback) {}
}
