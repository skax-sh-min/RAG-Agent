package com.example.ragagent.service;

import com.example.ragagent.model.KstDateFormat;
import com.example.ragagent.model.ResponseMode;
import com.example.ragagent.model.SourceRef;
import com.example.ragagent.model.ThreadMeta;
import com.example.ragagent.repository.MemoryRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read side of the 검색 진단 수치 (3단계) — turns the JSON blob stored per turn back into rows for
 * the {@code /admin} tuning panel.
 *
 * <p>The panel exists because the per-turn numbers in chat are only visible <em>in the moment</em>:
 * to decide whether {@code SEARCH_RRF_KEYWORD_WEIGHT} or {@code SEARCH_SIMILARITY_THRESHOLD} needs
 * changing, an operator has to look across many turns, and the interesting pattern is usually a
 * mismatch (high similarity that never reaches the answer, or the keyword axis carrying everything).
 * So each row also carries the two summary numbers that make that scannable without expanding it.
 */
@Service
public class RetrievalMetricsService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalMetricsService.class);

    private static final TypeReference<List<SourceRef>> SOURCE_LIST = new TypeReference<>() {};

    /** Questions are shown for context only — the panel is a tuning view, not a transcript reader. */
    private static final int MAX_QUESTION_PREVIEW = 120;

    private final MemoryService memoryService;

    /**
     * Reads stored blobs <b>tolerantly</b>, with unknown properties ignored.
     *
     * <p>These rows outlive the code that wrote them: a blob persisted before {@code answerShare}
     * existed, or after a future field is removed, must still render. With the default strict
     * reader a single added field would silently blank out every historical row — exactly the
     * accumulated data this panel exists to look at. Derived from the injected mapper rather than
     * a fresh one so any app-wide serialization config still applies.
     */
    private final com.fasterxml.jackson.databind.ObjectReader reader;

    public RetrievalMetricsService(MemoryService memoryService, ObjectMapper objectMapper) {
        this.memoryService = memoryService;
        this.reader = objectMapper
                .reader()
                .without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .forType(SOURCE_LIST);
    }

    /**
     * One turn in the panel.
     *
     * @param maxSimilarity best similarity among the turn's sources — "did retrieval find anything
     *                      close at all", null when no source carried a vector score
     * @param usedSourceCount how many sources actually received an answer share. A turn where this
     *                      is far below {@code sources.size()} retrieved a lot and used little,
     *                      which is the signal worth chasing (topK too high, or a prompt problem).
     */
    public record TurnMetrics(long turnId, String askedAt, String question, String responseMode,
                              String provider, List<SourceRef> sources,
                              Double maxSimilarity, int usedSourceCount,
                              String userId, String threadId, String threadTitle) {

        /** {@code asked_at} 은 UTC 로 저장된다 — 화면에는 KST 로 낸다(§6.25 결정 2). 원본은
         *  {@link #askedAt()} 로 남는다. */
        public String askedAtKst() {
            return KstDateFormat.utcStampToKst(askedAt);
        }

        /**
         * Shortened owner id for the table cell — a no-auth guest id is {@code guest-<12 hex>}
         * (an HMAC). Same rule the conversation list uses, so the two panels abbreviate
         * identically; the full value stays available as the cell's tooltip.
         */
        public String shortUserId() {
            return userId == null ? "" : userId.length() <= 14 ? userId : userId.substring(0, 14) + "…";
        }

        /** Conversation label: its title, or the raw thread id when {@code thread_meta} is gone
         *  (an orphan turn — the diagnostics are still valid, only the conversation is missing). */
        public String threadLabel() {
            String t = ThreadMeta.stripVersionPrefix(threadTitle);
            return t.isBlank() ? (threadId == null ? "-" : threadId) : t;
        }

        /**
         * Whether "사용/검색" means anything for this turn.
         *
         * <p>The creative evaluator never asks which excerpts the answer used
         * ({@code usedDocs}), so {@code AnswerAttribution} has nothing to narrow candidates with
         * and every share collapses to zero. Rendering that as {@code 0/8} reads as "retrieval
         * found nothing usable", which is a bug report the operator would chase forever — it is
         * structural, not a measurement (§6.24 남은 이슈 b).
         *
         * <p>Branches on a capability rather than comparing the enum value —
         * {@code ResponseModeBranchConventionTest} fails the build on a value comparison, and the
         * question here really is "was this turn judged by the creative evaluator", which is
         * exactly what {@link ResponseMode#usesCreativeEval()} names. (That guard is a plain text
         * scan, so it flags the forbidden form in a comment too; keeping it blunt is the point.)
         */
        public boolean attributionApplies() {
            return !ResponseMode.parse(responseMode).usesCreativeEval();
        }
    }

    /** Unfiltered — the pre-§6.25 call shape. */
    public List<TurnMetrics> recent(int offset, int limit) {
        return recent(null, null, offset, limit);
    }

    /**
     * @param userId   optional owner filter
     * @param threadId optional conversation filter — set by the 대화 목록 panel's 진단 button
     */
    public List<TurnMetrics> recent(String userId, String threadId, int offset, int limit) {
        List<TurnMetrics> out = new ArrayList<>();
        for (MemoryRepository.MetricsRow row :
                memoryService.findRecentRetrievalMetrics(userId, threadId, offset, limit)) {
            List<SourceRef> sources = parse(row);
            if (sources.isEmpty()) continue;   // unreadable blob — skip the row rather than break the panel
            out.add(new TurnMetrics(
                    row.turnId(),
                    row.askedAt(),
                    truncate(row.question()),
                    row.responseMode(),
                    row.provider(),
                    sources,
                    sources.stream().map(SourceRef::similarity).filter(java.util.Objects::nonNull)
                           .max(Double::compare).orElse(null),
                    (int) sources.stream().filter(s -> s.answerShare() != null).count(),
                    row.userId(),
                    row.threadId(),
                    row.threadTitle()));
        }
        return out;
    }

    public int count() {
        return count(null, null);
    }

    /** Must take the same filters {@link #recent} was given — see
     *  {@link MemoryRepository#countRetrievalMetrics(String, String)}. */
    public int count(String userId, String threadId) {
        return memoryService.countRetrievalMetrics(userId, threadId);
    }

    /** Owners that have diagnostics — the panel's user dropdown. */
    public List<String> userIds() {
        return memoryService.distinctRetrievalMetricsUserIds();
    }

    /** The sources of one turn, for the conversation panel's per-turn 출처 view — the same
     *  {@code SourceRef} list this panel renders, so both go through the shared table fragment. */
    public List<SourceRef> sourcesForTurn(long turnId) {
        String blob = memoryService.findRetrievalMetricsByTurnIds(List.of(turnId)).get(turnId);
        if (blob == null) return List.of();
        return parse(new MemoryRepository.MetricsRow(turnId, null, null, null, null, blob));
    }

    /**
     * Re-attaches stored diagnostics to a reopened thread's source lists (`/chat/{threadId}`).
     *
     * <p>The passed-in lists stay authoritative — they are rebuilt from the chunks as they exist
     * <em>now</em> (labels, previews, deleted-chunk placeholders), which the stored blob cannot
     * know about. Only the four numbers are merged in, matched by {@code chunkId}; a source with
     * no stored entry is returned untouched rather than dropped.
     *
     * <p>A DB-reuse turn has no entry at all: it answered from a stored answer without running
     * retrieval, so there is nothing to report even though its previews are borrowed from the
     * original turn.
     */
    public Map<Long, List<SourceRef>> enrich(Map<Long, List<SourceRef>> sourcesByTurn) {
        if (sourcesByTurn == null || sourcesByTurn.isEmpty()) return sourcesByTurn;
        Map<Long, String> blobs = memoryService.findRetrievalMetricsByTurnIds(
                List.copyOf(sourcesByTurn.keySet()));
        if (blobs.isEmpty()) return sourcesByTurn;

        Map<Long, List<SourceRef>> out = new java.util.LinkedHashMap<>(sourcesByTurn.size());
        for (var entry : sourcesByTurn.entrySet()) {
            String blob = blobs.get(entry.getKey());
            List<SourceRef> stored = blob == null
                    ? List.of()
                    : parse(new MemoryRepository.MetricsRow(entry.getKey(), null, null, null, null, blob));
            if (stored.isEmpty()) {
                out.put(entry.getKey(), entry.getValue());
                continue;
            }
            Map<String, SourceRef> byChunkId = new java.util.HashMap<>();
            for (SourceRef s : stored) {
                if (s.chunkId() != null) byChunkId.put(s.chunkId(), s);
            }
            // 병합으로 참여도/유사도가 채워진 '뒤에' 표시 순서를 잡는다 — 넘어온 목록은
            // turn_source_ref 행 순서라 두 값을 모르는 상태이고, 정렬 키가 여기서 생긴다.
            out.put(entry.getKey(), SourceRef.sortedForDisplay(entry.getValue().stream()
                    .map(live -> {
                        SourceRef m = live.chunkId() == null ? null : byChunkId.get(live.chunkId());
                        return m == null ? live : new SourceRef(
                                live.label(), live.preview(), live.chunkId(), live.docId(),
                                live.pageOrSlide(),
                                m.similarity(), m.retrievalShare(), m.axisRanks(), m.answerShare(),
                                // 저장된 blob이 아니라 현재 DB 상태에서 온 값 — 병합으로 덮으면 안 된다.
                                live.staleStatus(),
                                // 반대로 이건 그 턴의 사실이라 blob 쪽이 정답이다 — 지금 다시
                                // 계산할 수 없고(그때의 창·예산을 모른다) 계산해서도 안 된다.
                                m.promptExcluded());
                    })
                    .toList()));
        }
        return out;
    }

    private List<SourceRef> parse(MemoryRepository.MetricsRow row) {
        try {
            List<SourceRef> parsed = reader.readValue(row.metricsJson());
            return parsed == null ? List.of() : parsed;
        } catch (Exception e) {
            // A blob written by an older/newer field set must not take down the whole panel.
            log.warn("[METRICS] 진단 수치 파싱 실패 turnId={} — 이 행만 건너뜀: {}", row.turnId(), e.toString());
            return List.of();
        }
    }

    private static String truncate(String question) {
        if (question == null) return "";
        String oneLine = question.replaceAll("\\s+", " ").strip();
        return oneLine.length() > MAX_QUESTION_PREVIEW
                ? oneLine.substring(0, MAX_QUESTION_PREVIEW) + "…"
                : oneLine;
    }
}
