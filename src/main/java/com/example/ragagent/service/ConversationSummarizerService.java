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
import org.springframework.ai.openai.OpenAiChatOptions;
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

    /**
     * Per-answer cap for a Direct/meta answer kept verbatim in {@link #buildContext}'s
     * {@code [Recent]} block — see {@link #renderRecentAnswer}. Deliberately far larger than
     * {@link #UNSUMMARIZED_ANSWER_CAP}: that one packs many turns into a 2,000-char summary, this
     * one holds the last couple of turns at full fidelity so a follow-up's pronouns
     * ("그거", "위에서 두 번째") still have something to resolve against. Sized so that
     * {@code app.summary.recent-raw-turns}=2 actually fits two turns inside the history budget
     * (LLM_MAX_TOKENS/2, 3,000 chars by default) instead of the first one crowding out the second.
     */
    private static final int RECENT_DIRECT_ANSWER_CAP = 1_200;

    private final MemoryService memoryService;
    private final LlmRouter llmRouter;
    private final MessageSource messageSource;
    private final AppProperties props;

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
        this.props = props;

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

        // 요약과 [Recent] 는 서로 겹치지 않게 나눈다: 뒤쪽 recentRawTurns 개는 buildContext() 가
        // 직접 담당하므로 요약 대상에서 뺀다. 안 빼면 같은 턴이 양쪽에 들어가는데, RAG 턴은 이제
        // [Recent] 도 '## 요약' 을 쓰므로 문자 그대로 동일한 중복이 된다(3턴 대화 기준 문맥의 약 40%).
        List<MemoryRepository.Turn> older =
                turns.subList(0, Math.max(0, turns.size() - recentRawTurns));

        try {
            String summary;
            if (older.isEmpty()) {
                // 요약할 이전 턴이 없다 — 실패가 아니라 "최근 창이 곧 대화 전체"인 상태다.
                // 빈 문자열을 캐시해 buildContext() 가 [Recent] 만으로 문맥을 만들게 한다.
                // 여기서 캐시를 비워 두면 원본 폴백(getHistory())으로 떨어져, 방금 요약/절단으로
                // 줄인 답변 전문이 그대로 되돌아온다.
                summary = "";
            } else {
                summary = summarize(older, threadId, locale);
                if (summary == null || summary.isBlank()) return;
            }

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
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .temperature(props.llmSafe().indexingTemperature()).build();
        return llmRouter.executeWithTracking(TaskType.MICRO_TEXT, RoutingMode.LOCAL_ONLY,
                BackgroundUsage.SUMMARY_PREFIX,
                model -> model.call(new Prompt(List.of(
                        new SystemMessage(systemPrompt),
                        new UserMessage(input.text())), options)));
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

    /**
     * How a turn's answer is rendered inside {@link #buildContext}'s verbatim {@code [Recent]}
     * block. The two answer kinds are worth different amounts here:
     *
     * <ul>
     *   <li><b>RAG answer</b> (has a {@code ## 요약} section) → the 요약 only. Its full text is a
     *       restatement of document chunks that the <em>next</em> turn re-retrieves anyway — every
     *       turn runs its own search — so feeding the whole thing back duplicates
     *       {@code [검색된 문서]} at several thousand chars, and does it with the model's own
     *       unverified prose rather than the source. A 2,700-char answer collapses to ~250.</li>
     *   <li><b>Direct/meta answer</b> (no such section) → the answer, capped at
     *       {@link #RECENT_DIRECT_ANSWER_CAP}. There is no retrieval behind it, so this text is the
     *       only record of what was said; dropping it would lose the exchange entirely. Capping
     *       bounds the cost without erasing it.</li>
     * </ul>
     *
     * <p>The discriminator is "does the answer carry a {@code ## 요약} heading", not a persisted
     * direct-mode flag ({@code conversation_turns} has none) — the same proxy
     * {@link #buildSummaryInput} already relies on, and accurate because
     * {@code prompt.answer.system} mandates that section while {@code prompt.direct.system} never
     * asks for it. A RAG answer where the model ignored the format degrades to the capped branch,
     * which is the safe direction.
     */
    private static String renderRecentAnswer(String answer) {
        String ownSummary = CuratedTextUtils.extractSummarySection(answer);
        return ownSummary.isBlank() ? capAnswer(answer, RECENT_DIRECT_ANSWER_CAP) : ownSummary;
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

        // dedupeTurns() 필수 — getRecentTurns()의 SQL 에는 feedback 조건이 없다. 이걸 빠뜨리면
        // 싫어요를 누른 턴이 요약(dedupeTurns 경유)에서는 빠지면서 아래 [Recent] 에는 **원문 그대로**
        // 들어간다. UI 가 "다음 대화 컨텍스트에서 제외"라고 약속한 것의 정반대이고, 원본 폴백
        // 경로(getHistory() 의 WHERE feedback <> 'DISLIKE')와도 어긋난다. 게다가 RAG 답변은
        // 2~3천 자라 그 한 건이 아래 문자 예산을 통째로 먹고 정상 턴을 밀어낸다.
        List<MemoryRepository.Turn> turns = dedupeTurns(memoryService.getRecentTurns(userId, threadId));
        if (turns.isEmpty()) return null;

        int budget = memoryService.maxConversationChars();
        // 빈 요약 = 요약할 이전 턴이 없음(precompute 참고). 헤더만 남기면 LLM 에게 빈 섹션을
        // 보여주는 꼴이라 통째로 생략한다.
        String summaryBlock = summary.isEmpty() ? "" : "[Conversation Summary]\n" + summary;

        List<MemoryRepository.Turn> recent =
                turns.subList(Math.max(0, turns.size() - recentRawTurns), turns.size());

        // Reserve the "[Recent]" header up front so the budget check stays honest even before we
        // know whether any recent turn fits; unused reservation is harmless (conservative).
        String recentHeader = "\n\n[Recent]\n";
        int used = summaryBlock.length() + recentHeader.length();
        StringBuilder recentSb = new StringBuilder();
        for (int i = recent.size() - 1; i >= 0; i--) {
            MemoryRepository.Turn t = recent.get(i);
            String entry = "Q: " + t.question() + "\nA: " + renderRecentAnswer(t.answer()) + "\n\n";
            if (used + entry.length() > budget) break;
            recentSb.insert(0, entry);
            used += entry.length();
        }

        StringBuilder sb = new StringBuilder(summaryBlock);
        if (recentSb.length() > 0) sb.append(recentHeader).append(recentSb);
        String result = sb.toString().strip();

        // 요약도 비고 예산에 들어간 최근 턴도 없으면 줄 게 없다 — null 을 돌려 호출자가 원본
        // 폴백을 쓰게 한다(빈 문자열을 주면 "이력 없음"으로 확정돼 폴백 기회가 사라진다).
        if (result.isBlank()) return null;

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
    //
    // **Every consumer of getRecentTurns() in this class must go through here.** That method's SQL
    // deliberately has no feedback filter (other callers want the raw rows), so this is the only
    // place the DISLIKE exclusion is applied on the summary path — both for the summary itself and
    // for buildContext()'s verbatim [Recent] block.
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
