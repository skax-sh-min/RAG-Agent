package com.example.ragagent.repository;

import java.util.List;

public interface MemoryRepository {
    String getHistory(String threadId, int maxChars);

    void addTurn(String threadId, String question, String answer,
                 String askedAt, int inputTokens, int outputTokens,
                 int elapsedMs, String provider, int llmCalls);

    void clearHistory(String threadId);

    /** Returns all turns for the thread in chronological order (oldest first). */
    List<Turn> getTurns(String threadId);

    record Turn(String question, String answer,
                String askedAt, String answeredAt,
                int inputTokens, int outputTokens,
                int elapsedMs, String provider, int llmCalls) {}
}
