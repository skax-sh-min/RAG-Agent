package com.example.ragagent.repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface MemoryRepository {
    String getHistory(String userId, String threadId, int maxChars);

    /** Returns the generated turn id (conversation_turns.id). {@code responseMode}: the turn's
     *  S/M/L answer-length mode ({@code ResponseMode.name()}), null-safe (nullable column).
     *  {@code selectedTags}: the search-scope tags this question was asked under (comma-joined,
     *  null/blank = 전체 검색) — read back by {@code CuratedQaService.onLike} so a 👍-promoted
     *  answer inherits the scope it was actually answered in. */
    long addTurn(String userId, String threadId, String question, String answer,
                 String askedAt, int inputTokens, int outputTokens,
                 int elapsedMs, String provider, int llmCalls, String responseMode,
             String selectedTags, boolean directMode, Long reusedFromTurnId);

        default long addTurn(String userId, String threadId, String question, String answer,
             String askedAt, int inputTokens, int outputTokens,
             int elapsedMs, String provider, int llmCalls, String responseMode,
             String selectedTags, Long reusedFromTurnId) {
        return addTurn(userId, threadId, question, answer, askedAt, inputTokens, outputTokens,
            elapsedMs, provider, llmCalls, responseMode, selectedTags, false, reusedFromTurnId);
        }

    default long addTurn(String userId, String threadId, String question, String answer,
                 String askedAt, int inputTokens, int outputTokens,
                 int elapsedMs, String provider, int llmCalls, String responseMode,
                 String selectedTags) {
        return addTurn(userId, threadId, question, answer, askedAt, inputTokens, outputTokens,
            elapsedMs, provider, llmCalls, responseMode, selectedTags, false, null);
    }

        default long addTurn(String userId, String threadId, String question, String answer,
             String askedAt, int inputTokens, int outputTokens,
             int elapsedMs, String provider, int llmCalls, String responseMode,
             String selectedTags, boolean directMode) {
        return addTurn(userId, threadId, question, answer, askedAt, inputTokens, outputTokens,
            elapsedMs, provider, llmCalls, responseMode, selectedTags, directMode, null);
        }

    void clearHistory(String userId, String threadId);

    /** Persist image refs shown with a turn (answer thumbnails in chat UI). */
    void saveTurnImageRefs(long turnId, String userId, String threadId, List<String> imageRefs);

    /** Active image refs by turn id, for restoring chat history. */
    Map<Long, List<String>> getTurnImageRefs(String userId, String threadId);

    /** Hides one image from a turn in this conversation (soft delete). */
    void excludeTurnImageRef(String userId, String threadId, long turnId, String imageRef);

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

    /** Single turn lookup (question/answer + metadata) for callers that don't need the whole
     * thread — e.g. curated-Q&A promotion on like. Empty = turn not found / not owned. */
    Optional<Turn> getTurn(String userId, String threadId, long turnId);

    /** {@code feedback}: {@code "LIKE" | "DISLIKE" | null}. No-op if the turn isn't owned by userId/threadId. */
    void updateFeedback(String userId, String threadId, long turnId, String feedback);

    /**
     * 3단계 — stores the turn's per-source retrieval diagnostics as a JSON array (see
     * {@code RetrievalMetricsView}). Written right after the turn insert, in the same
     * post-insert slot as {@link #saveTurnImageRefs}. Diagnostic only: a failure here must never
     * cost the user their answer, so callers swallow, and {@code null}/blank is a no-op.
     */
    void saveRetrievalMetrics(long turnId, String metricsJson);

    /**
     * Recent turns that actually carry diagnostics, newest first — backs the {@code /admin}
     * tuning panel. Deliberately <b>not</b> user-scoped: it is an operator view of how retrieval
     * is behaving across the deployment, gated by {@code /admin/**}'s ROLE_ADMIN like every other
     * panel there.
     */
    List<MetricsRow> findRecentRetrievalMetrics(int offset, int limit);

    /** Total turns carrying diagnostics — for the panel's pagination. */
    int countRetrievalMetrics();

    /** One row of the {@code /admin} diagnostics panel; {@code metricsJson} is parsed by the service. */
    record MetricsRow(long turnId, String askedAt, String question, String responseMode,
                      String provider, String metricsJson) {}

    record Turn(long id, String question, String answer,
                String askedAt, String answeredAt,
                int inputTokens, int outputTokens,
                int elapsedMs, String provider, int llmCalls,
                String feedback, String responseMode, String selectedTags) {}

    /** Wraps a nullable feedback value so "found with NULL feedback" is distinguishable from "not found". */
    record FeedbackRow(String feedback) {}
}
