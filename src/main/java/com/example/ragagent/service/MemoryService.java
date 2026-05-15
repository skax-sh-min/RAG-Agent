package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.repository.MemoryRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Multi-turn conversation memory keyed by thread_id.
 * Equivalent to LangGraph MemorySaver in the Python version.
 * Delegates storage to MemoryRepository (default: SQLite).
 */
@Service
public class MemoryService {

    private final int maxConversationChars;
    private final MemoryRepository repository;

    public MemoryService(AppProperties appProperties, MemoryRepository repository) {
        this.maxConversationChars = appProperties.maxConversationChars();
        this.repository = repository;
    }

    public String getHistory(String threadId) {
        return repository.getHistory(threadId, maxConversationChars);
    }

    public void addTurn(String threadId, String question, String answer,
                        String askedAt, int inputTokens, int outputTokens,
                        int elapsedMs, String provider, int llmCalls) {
        repository.addTurn(threadId, question, answer,
                askedAt, inputTokens, outputTokens, elapsedMs, provider, llmCalls);
    }

    public void clearHistory(String threadId) {
        repository.clearHistory(threadId);
    }

    public List<MemoryRepository.Turn> getTurns(String threadId) {
        return repository.getTurns(threadId);
    }
}
