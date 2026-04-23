package com.example.ragagent.repository;

public interface MemoryRepository {
    String getHistory(String threadId, int maxChars);
    void addTurn(String threadId, String question, String answer);
    void clearHistory(String threadId);
}
