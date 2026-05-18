package com.example.ragagent.repository;

import java.util.List;

public interface MemoryRepository {
    String getHistory(String userId, String threadId, int maxChars);

    void addTurn(String userId, String threadId, String question, String answer,
                 String askedAt, int inputTokens, int outputTokens,
                 int elapsedMs, String provider, int llmCalls);

    void clearHistory(String userId, String threadId);

    /** Returns all turns for the thread in chronological order (oldest first). */
    List<Turn> getTurns(String userId, String threadId);

    record Turn(String question, String answer,
                String askedAt, String answeredAt,
                int inputTokens, int outputTokens,
                int elapsedMs, String provider, int llmCalls) {}
}
