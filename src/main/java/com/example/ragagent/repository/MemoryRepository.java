package com.example.ragagent.repository;

import java.util.List;

public interface MemoryRepository {
    String getHistory(String threadId, int maxChars);
    void addTurn(String threadId, String question, String answer);
    void clearHistory(String threadId);

    /** Returns all turns for the thread in chronological order (oldest first). */
    List<Turn> getTurns(String threadId);

    record Turn(String question, String answer, String createdAt) {}
}
