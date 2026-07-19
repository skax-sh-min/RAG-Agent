package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.BackgroundUsage;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.repository.MemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Precomputes a deduped conversation summary while the user is still typing (§6.10 in PLAN.md),
 * so AgentService/StreamingAgentService can use "summary + last few raw turns" instead of the
 * full raw history once it's ready. Best-effort only — every public method fails open (returns
 * null / no-ops) so a slow or unavailable LOCAL provider never blocks or degrades chat.
 */
@Service
public class ConversationSummarizerService {

    private static final Logger log = LoggerFactory.getLogger(ConversationSummarizerService.class);

    private final MemoryService memoryService;
    private final LlmRouter llmRouter;
    private final MessageSource messageSource;

    // §6.11: previously hardcoded constants, now sourced from app.summary.* (null-safe defaults).
    private final int maxSummaryChars;
    private final int recentRawTurns;
    private final long precomputeTtlMillis;

    // Bounded to the most recently used threads — access-order LinkedHashMap evicts the
    // least-recently-used entry once size exceeds maxCachedThreads.
    private final Map<String, String> summaryCache;
    private final Map<String, Long> lastPrecomputeAt = new ConcurrentHashMap<>();

    public ConversationSummarizerService(MemoryService memoryService, LlmRouter llmRouter,
                                         MessageSource messageSource, AppProperties props) {
        this.memoryService = memoryService;
        this.llmRouter = llmRouter;
        this.messageSource = messageSource;

        AppProperties.SummaryConfig cfg = props.summarySafe();
        int maxCachedThreads = cfg.maxCachedThreads();
        this.maxSummaryChars = cfg.maxSummaryChars();
        this.recentRawTurns = cfg.recentRawTurns();
        this.precomputeTtlMillis = cfg.precomputeTtlSeconds() * 1_000L;
        this.summaryCache = Collections.synchronizedMap(
                new LinkedHashMap<>(maxCachedThreads + 1, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                        return size() > maxCachedThreads;
                    }
                });
    }

    /**
     * Dedupes thread history and summarizes it via a LOCAL-only LLM call, caching the result
     * for {@link #buildContext}. No-ops (without calling the LLM) if this thread was already
     * precomputed within the last {@code app.summary.precompute-ttl-seconds} (§6.11) — the
     * frontend already debounces the trigger, this is just a safety net against duplicate
     * tabs/requests.
     */
    public void precompute(String userId, String threadId, Locale locale) {
        long now = System.currentTimeMillis();
        Long last = lastPrecomputeAt.get(threadId);
        if (last != null && now - last < precomputeTtlMillis) return;
        lastPrecomputeAt.put(threadId, now);

        String deduped = dedupe(memoryService.getTurns(userId, threadId));
        if (deduped.isBlank()) return;

        try {
            String systemPrompt = messageSource.getMessage("prompt.summary.system", null, locale);
            String summary = llmRouter.executeWithTracking(TaskType.MICRO_TEXT, RoutingMode.LOCAL_ONLY,
                    BackgroundUsage.SUMMARY_PREFIX,
                    model -> model.call(new Prompt(List.of(
                            new SystemMessage(systemPrompt),
                            new UserMessage(deduped)))));
            if (summary == null || summary.isBlank()) return;
            summaryCache.put(threadId, truncate(summary));
            log.debug("[SUMMARY] precomputed thread={} summaryChars={}", threadId, summary.length());
        } catch (Exception e) {
            // LOCAL provider missing/unavailable/timed out — leave no cache entry, caller falls back.
            log.debug("[SUMMARY] precompute skipped for thread={}: {}", threadId, e.getMessage());
        }
    }

    /**
     * Combines the cached summary (if any) with the last few raw turns, so the model sees
     * both the compacted history and full-fidelity detail on the most recent exchange.
     * Returns null when nothing is cached — caller must fall back to MemoryService.getHistory().
     *
     * <p>§6.11: honors the exact same char budget as the fallback path
     * ({@link MemoryService#maxConversationChars()}). The summary is preserved first; recent raw
     * turns are then filled newest-first within the remaining budget (the same latest-first fill
     * strategy {@code MemoryRepository.getHistory()} uses), so the summary path can never send a
     * larger context to the LLM than the fallback path.
     */
    public String buildContext(String userId, String threadId) {
        String summary = summaryCache.get(threadId);
        if (summary == null) return null;

        List<MemoryRepository.Turn> turns = memoryService.getTurns(userId, threadId);
        if (turns.isEmpty()) return null;

        int budget = memoryService.maxConversationChars();
        String summaryBlock = "[Conversation Summary]\n" + summary;

        List<MemoryRepository.Turn> recent =
                turns.subList(Math.max(0, turns.size() - recentRawTurns), turns.size());

        // Reserve the "[Recent]" header up front so the budget check stays honest even before we
        // know whether any recent turn fits; unused reservation is harmless (conservative).
        String recentHeader = "\n\n[Recent]\n";
        int used = summaryBlock.length() + recentHeader.length();
        StringBuilder recentSb = new StringBuilder();
        for (int i = recent.size() - 1; i >= 0; i--) {
            MemoryRepository.Turn t = recent.get(i);
            String entry = "Q: " + t.question() + "\nA: " + t.answer() + "\n\n";
            if (used + entry.length() > budget) break;
            recentSb.insert(0, entry);
            used += entry.length();
        }

        StringBuilder sb = new StringBuilder(summaryBlock);
        if (recentSb.length() > 0) sb.append(recentHeader).append(recentSb);
        String result = sb.toString().strip();

        // Hard-cap guard: only reachable when the summary alone already exceeds the budget
        // (e.g. a very small LLM_MAX_TOKENS). Truncate so the invariant "never exceeds budget" holds.
        return result.length() > budget ? result.substring(0, budget).strip() : result;
    }

    /** Call after a new turn is persisted so the next precompute regenerates a fresh summary. */
    public void invalidate(String threadId) {
        summaryCache.remove(threadId);
        lastPrecomputeAt.remove(threadId);
    }

    // Normalizes + keeps only the latest occurrence of each distinct question (drops repeated/
    // resent questions), and honors the same DISLIKE hard-exclusion getHistory() applies (§6.9).
    // Package-private for unit testing.
    String dedupe(List<MemoryRepository.Turn> turns) {
        Map<String, MemoryRepository.Turn> byNormalizedQuestion = new LinkedHashMap<>();
        for (MemoryRepository.Turn t : turns) {
            if ("DISLIKE".equals(t.feedback())) continue;
            byNormalizedQuestion.put(normalize(t.question()), t);
        }
        StringBuilder sb = new StringBuilder();
        for (MemoryRepository.Turn t : byNormalizedQuestion.values()) {
            sb.append("Q: ").append(t.question()).append("\nA: ").append(t.answer()).append("\n\n");
        }
        return sb.toString().strip();
    }

    private static String normalize(String s) {
        return s == null ? "" : s.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private String truncate(String s) {
        return s.length() > maxSummaryChars ? s.substring(0, maxSummaryChars) : s;
    }
}
