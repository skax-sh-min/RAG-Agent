package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.ingestion.CuratedTextUtils;
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
 * Precomputes a deduped conversation summary, so AgentService/StreamingAgentService can use
 * "summary + last few raw turns" instead of the full raw history once it's ready. The primary
 * trigger is {@link #precomputeAfterTurn} — fired right after a turn's answer is persisted, so
 * the summary has the whole "user reads the answer" window to finish. {@link #precompute}
 * (originally §6.10 in PLAN.md, fired while the user is still typing) remains as a cold-start
 * safety net for threads whose cache hasn't been warmed yet. Best-effort only — every public
 * method fails open (returns null / no-ops) so a slow or unavailable LOCAL provider never blocks
 * or degrades chat.
 *
 * <p>The summary itself is built without an LLM whenever the turns' answers already carry their
 * own "## 요약" section — a RAG answer always does ({@code prompt.answer.system} mandates it), so
 * in practice the LLM path only ever exists to compress Direct-mode/meta answers, which have no
 * such section. And even then it runs only when the dedicated MICRO_TEXT offload model is
 * configured; without it the text is still assembled from the 요약 sections, with un-summarized
 * answers capped instead. See {@link #summarize}.
 */
@Service
public class ConversationSummarizerService {

    private static final Logger log = LoggerFactory.getLogger(ConversationSummarizerService.class);

    /**
     * Per-answer cap for an answer that has no "## 요약" section, used only when assembling the
     * summary without an LLM (see {@link #summarize}). A real RAG 요약 is 2-4 sentences, so a
     * Direct-mode answer standing in for one has to be held to roughly that size — otherwise a
     * single long answer would dominate a summary that is supposed to be a per-turn digest.
     */
    private static final int UNSUMMARIZED_ANSWER_CAP = 300;

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
     *
     * <p>Kept as the entry point for the frontend's "user starts typing" trigger, which acts as a
     * cold-start safety net (e.g. reopening an old thread whose cache was never warmed) now that
     * {@link #precomputeAfterTurn} is the primary trigger — see there.
     */
    public void precompute(String userId, String threadId, Locale locale) {
        precompute(userId, threadId, null, locale);
    }

    /**
     * Triggers precompute right after a turn's answer is persisted, instead of waiting for the
     * user to start typing the next question — the summary has the whole "reading the answer"
     * window to finish instead of racing the next keystroke. Runs on its own virtual thread
     * (fire-and-forget, best-effort, never blocks the caller).
     *
     * <p>Invalidates first so the TTL debounce in {@link #precompute} — meant to suppress
     * duplicate calls from the same trigger, not to block this new one — never suppresses this
     * post-turn run just because the frontend's keystroke trigger already fired for the same
     * thread moments earlier (while the user was composing the question that just got answered).
     *
     * <p>{@code turnId} lets {@link #precompute} discard the result instead of caching it if the
     * user marks this exact turn DISLIKE while the LLM summarization call is still in flight —
     * see the dislike check there.
     */
    public void precomputeAfterTurn(String userId, String threadId, Long turnId, Locale locale) {
        invalidate(threadId);
        Thread.ofVirtual().start(() -> precompute(userId, threadId, turnId, locale));
    }

    /** Package-private for unit testing (bypasses the {@link #precomputeAfterTurn} background thread). */
    void precompute(String userId, String threadId, Long turnId, Locale locale) {
        long now = System.currentTimeMillis();
        Long last = lastPrecomputeAt.get(threadId);
        if (last != null && now - last < precomputeTtlMillis) return;
        lastPrecomputeAt.put(threadId, now);

        List<MemoryRepository.Turn> turns = dedupeTurns(memoryService.getRecentTurns(userId, threadId));
        if (turns.isEmpty()) return;

        try {
            String summary = summarize(turns, threadId, locale);
            if (summary == null || summary.isBlank()) return;

            // Summarizing can take a few seconds (when it goes to the LLM at all); if the user
            // disliked this exact turn meanwhile, the summary may have been built from (and may
            // reference) the now-disliked answer. Discard it rather than cache it — the next
            // precompute will naturally exclude the disliked turn via dedupeTurns()' own filter.
            if (turnId != null && isDisliked(userId, threadId, turnId)) {
                log.debug("[SUMMARY] discarded for thread={} — turnId={} disliked during precompute",
                        threadId, turnId);
                return;
            }

            summaryCache.put(threadId, truncate(summary));
            log.debug("[SUMMARY] precomputed thread={} summaryChars={}", threadId, summary.length());
        } catch (Exception e) {
            // LOCAL provider missing/unavailable/timed out — leave no cache entry, caller falls back.
            log.debug("[SUMMARY] precompute skipped for thread={}: {}", threadId, e.getMessage());
        }
    }

    /**
     * Produces the thread summary, preferring the answers' own "## 요약" sections over an LLM call.
     *
     * <p>A RAG answer already opens with an LLM-written recap of itself
     * ({@code prompt.answer.system}'s fixed 요약 → 상세 설명 → … format), so re-summarizing it is
     * paying a second time for text the first call already produced. {@link #buildSummaryInput}
     * therefore substitutes each answer with its own 요약 section where one exists:
     * <ul>
     *   <li>every turn has one → return the assembled text as the summary, no LLM call at all;</li>
     *   <li>some turn has none (Direct-mode/meta answers, older turns) → one LLM call as before,
     *       but over the already-shrunk input, so the model is effectively only summarizing the
     *       answers that lacked a 요약 section;</li>
     *   <li>…unless the dedicated MICRO_TEXT offload model is not configured
     *       ({@code LOCAL_FAST_LLM_URL} unset → {@link LlmRouter#hasMicroTextOffloadProvider()}
     *       false) — summarization is an optional nicety and must never borrow the answer-serving
     *       tier (MICRO_TEXT would otherwise fall through to it) to compute itself. In that case
     *       the text is still assembled <em>without</em> an LLM, with each un-summarized answer
     *       capped at {@link #UNSUMMARIZED_ANSWER_CAP} chars. Returning null here instead would
     *       throw away every {@code ## 요약} already extracted just because one Direct-mode turn
     *       couldn't be compressed — and since {@code LOCAL_FAST_LLM_URL} has no default, that is
     *       the common deployment, not an edge case.</li>
     * </ul>
     */
    private String summarize(List<MemoryRepository.Turn> turns, String threadId, Locale locale) {
        SummaryInput input = buildSummaryInput(turns, 0);
        if (input.text().isBlank()) return null;

        if (input.fullyPreSummarized()) {
            log.debug("[SUMMARY] thread={} — all {} turn(s) carry a '## 요약' section, LLM call skipped",
                    threadId, turns.size());
            return input.text();
        }
        if (!llmRouter.hasMicroTextOffloadProvider()) {
            String assembled = buildSummaryInput(turns, UNSUMMARIZED_ANSWER_CAP).text();
            log.debug("[SUMMARY] thread={} — no MICRO_TEXT offload provider; assembled from the "
                            + "'## 요약' sections without an LLM ({}자 초과 답변은 절단)",
                    threadId, UNSUMMARIZED_ANSWER_CAP);
            return assembled.isBlank() ? null : assembled;
        }

        String systemPrompt = messageSource.getMessage("prompt.summary.system", null, locale);
        return llmRouter.executeWithTracking(TaskType.MICRO_TEXT, RoutingMode.LOCAL_ONLY,
                BackgroundUsage.SUMMARY_PREFIX,
                model -> model.call(new Prompt(List.of(
                        new SystemMessage(systemPrompt),
                        new UserMessage(input.text())))));
    }

    /**
     * Rendered Q/A history for {@link #summarize}, plus whether EVERY turn's answer carried its own
     * "## 요약" section (i.e. nothing is left that still needs an LLM to be summarized).
     */
    private record SummaryInput(String text, boolean fullyPreSummarized) {}

    /**
     * @param rawAnswerCap when &gt; 0, an answer with no "## 요약" section is cut to this many
     *        chars. Used only by the no-LLM assembly path — {@link #truncate} caps the finished
     *        summary from the <em>front</em>, so one long Direct-mode answer early in the thread
     *        would otherwise consume the whole {@code app.summary.max-summary-chars} budget and
     *        push the newest (most relevant) turns out of it entirely. The LLM path passes 0 and
     *        keeps seeing the full text — compressing it is exactly that call's job.
     */
    private static SummaryInput buildSummaryInput(List<MemoryRepository.Turn> turns, int rawAnswerCap) {
        StringBuilder sb = new StringBuilder();
        boolean fullyPreSummarized = true;
        for (MemoryRepository.Turn t : turns) {
            String ownSummary = CuratedTextUtils.extractSummarySection(t.answer());
            String rendered;
            if (ownSummary.isBlank()) {
                fullyPreSummarized = false;
                rendered = capAnswer(t.answer(), rawAnswerCap);
            } else {
                rendered = ownSummary;
            }
            sb.append("Q: ").append(t.question())
              .append("\nA: ").append(rendered)
              .append("\n\n");
        }
        return new SummaryInput(sb.toString().strip(), fullyPreSummarized);
    }

    private static String capAnswer(String answer, int cap) {
        if (answer == null) return "";
        if (cap <= 0 || answer.length() <= cap) return answer;
        return answer.substring(0, cap).strip() + "…";
    }

    private boolean isDisliked(String userId, String threadId, long turnId) {
        return memoryService.getFeedback(userId, threadId, turnId)
                .map(f -> "DISLIKE".equals(f.feedback()))
                .orElse(false);
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

        List<MemoryRepository.Turn> turns = memoryService.getRecentTurns(userId, threadId);
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
    // Returns turns (not rendered text) so buildSummaryInput() can decide per answer whether it
    // already carries its own "## 요약" section. Package-private for unit testing.
    List<MemoryRepository.Turn> dedupeTurns(List<MemoryRepository.Turn> turns) {
        Map<String, MemoryRepository.Turn> byNormalizedQuestion = new LinkedHashMap<>();
        for (MemoryRepository.Turn t : turns) {
            if ("DISLIKE".equals(t.feedback())) continue;
            byNormalizedQuestion.put(normalize(t.question()), t);
        }
        return List.copyOf(byNormalizedQuestion.values());
    }

    private static String normalize(String s) {
        return s == null ? "" : s.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private String truncate(String s) {
        return s.length() > maxSummaryChars ? s.substring(0, maxSummaryChars) : s;
    }
}
