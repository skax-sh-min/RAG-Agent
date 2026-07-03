package com.example.ragagent.service;

import com.example.ragagent.repository.MemoryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Multi-turn conversation memory keyed by userId + thread_id.
 * Equivalent to LangGraph MemorySaver in the Python version.
 * Delegates storage to MemoryRepository (default: SQLite).
 */
@Service
public class MemoryService {

    private final int maxConversationChars;
    private final MemoryRepository repository;

    public MemoryService(MemoryRepository repository,
                         @Value("${spring.ai.openai.chat.options.max-tokens:8000}") int llmMaxTokens) {
        this.maxConversationChars = Math.max(1_000, llmMaxTokens * 3 / 4);
        this.repository = repository;
    }

    public String getHistory(String userId, String threadId) {
        return repository.getHistory(userId, threadId, maxConversationChars);
    }

    /** Returns the generated turn id (conversation_turns.id). */
    public long addTurn(String userId, String threadId, String question, String answer,
                        String askedAt, int inputTokens, int outputTokens,
                        int elapsedMs, String provider, int llmCalls) {
        return repository.addTurn(userId, threadId, question, answer,
                askedAt, inputTokens, outputTokens, elapsedMs, provider, llmCalls);
    }

    public void clearHistory(String userId, String threadId) {
        repository.clearHistory(userId, threadId);
    }

    public List<MemoryRepository.Turn> getTurns(String userId, String threadId) {
        return repository.getTurns(userId, threadId);
    }

    public Optional<MemoryRepository.FeedbackRow> getFeedback(String userId, String threadId, long turnId) {
        return repository.getFeedback(userId, threadId, turnId);
    }

    public void updateFeedback(String userId, String threadId, long turnId, String feedback) {
        repository.updateFeedback(userId, threadId, turnId, feedback);
    }
}
