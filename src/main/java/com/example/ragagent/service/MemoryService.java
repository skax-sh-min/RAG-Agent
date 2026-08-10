package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.repository.MemoryRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
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

    // Single source of truth for "LLM max tokens" (app.llm.max-tokens / LLM_MAX_TOKENS, default
    // 6000) — used to read the separate, dead spring.ai.openai.chat.options.max-tokens property
    // (default 8000), which config'd nothing (Spring AI's autoconfigured ChatModel bean is skipped
    // since LlmConfig.primaryChatModel() already satisfies its @ConditionalOnMissingBean).
    public MemoryService(MemoryRepository repository, AppProperties props) {
        this.maxConversationChars = Math.max(1_000, props.llmSafe().maxTokens() / 2);
        this.repository = repository;
    }

    public String getHistory(String userId, String threadId) {
        return repository.getHistory(userId, threadId, maxConversationChars);
    }

    /**
     * Char budget applied to conversation history (LLM_MAX_TOKENS × 0.5, floor 1,000).
     * Exposed so the summary path ({@code ConversationSummarizerService.buildContext()}) can
     * respect the exact same ceiling as this fallback path — single source of truth (§6.11).
     */
    public int maxConversationChars() {
        return maxConversationChars;
    }

    /** Returns the generated turn id (conversation_turns.id). {@code selectedTags} is the
     *  comma-joined search scope this question was asked under — see
     *  {@link MemoryRepository#addTurn}. */
    public long addTurn(String userId, String threadId, String question, String answer,
                        String askedAt, int inputTokens, int outputTokens,
                        int elapsedMs, String provider, int llmCalls, String responseMode,
                        String selectedTags) {
        return repository.addTurn(userId, threadId, question, answer,
                askedAt, inputTokens, outputTokens, elapsedMs, provider, llmCalls, responseMode,
                selectedTags);
    }

        public long addTurn(String userId, String threadId, String question, String answer,
                String askedAt, int inputTokens, int outputTokens,
                int elapsedMs, String provider, int llmCalls, String responseMode,
                String selectedTags, boolean directMode) {
        return repository.addTurn(userId, threadId, question, answer,
            askedAt, inputTokens, outputTokens, elapsedMs, provider, llmCalls, responseMode,
            selectedTags, directMode);
        }

    public long addTurn(String userId, String threadId, String question, String answer,
                        String askedAt, int inputTokens, int outputTokens,
                        int elapsedMs, String provider, int llmCalls, String responseMode,
                        String selectedTags, Long reusedFromTurnId) {
        return repository.addTurn(userId, threadId, question, answer,
                askedAt, inputTokens, outputTokens, elapsedMs, provider, llmCalls, responseMode,
                selectedTags, reusedFromTurnId);
    }

        public long addTurn(String userId, String threadId, String question, String answer,
                String askedAt, int inputTokens, int outputTokens,
                int elapsedMs, String provider, int llmCalls, String responseMode,
                String selectedTags, boolean directMode, Long reusedFromTurnId) {
        return repository.addTurn(userId, threadId, question, answer,
            askedAt, inputTokens, outputTokens, elapsedMs, provider, llmCalls, responseMode,
            selectedTags, directMode, reusedFromTurnId);
        }

    public void clearHistory(String userId, String threadId) {
        repository.clearHistory(userId, threadId);
    }

    public void saveTurnImageRefs(long turnId, String userId, String threadId, List<String> imageRefs) {
        repository.saveTurnImageRefs(turnId, userId, threadId, imageRefs);
    }

    public Map<Long, List<String>> getTurnImageRefs(String userId, String threadId) {
        return repository.getTurnImageRefs(userId, threadId);
    }

    public void excludeTurnImageRef(String userId, String threadId, long turnId, String imageRef) {
        repository.excludeTurnImageRef(userId, threadId, turnId, imageRef);
    }

    public List<MemoryRepository.Turn> getTurns(String userId, String threadId) {
        return repository.getTurns(userId, threadId);
    }

    /**
     * Same as {@link #getTurns}, capped to the most recent {@code app.memory.fetch-limit-turns}
     * turns. Use this instead of {@link #getTurns} for anything that feeds the result into an LLM
     * call (e.g. {@code ConversationSummarizerService}) — {@link #getTurns} is unbounded and only
     * safe for UI-only uses (like restoring a thread's full message history on page load).
     */
    public List<MemoryRepository.Turn> getRecentTurns(String userId, String threadId) {
        return repository.getRecentTurns(userId, threadId);
    }

    /** Single turn lookup — used by {@link CuratedQaService} to snapshot question/answer on like. */
    public Optional<MemoryRepository.Turn> getTurn(String userId, String threadId, long turnId) {
        return repository.getTurn(userId, threadId, turnId);
    }

    public Optional<MemoryRepository.FeedbackRow> getFeedback(String userId, String threadId, long turnId) {
        return repository.getFeedback(userId, threadId, turnId);
    }

    public void updateFeedback(String userId, String threadId, long turnId, String feedback) {
        repository.updateFeedback(userId, threadId, turnId, feedback);
    }
}
