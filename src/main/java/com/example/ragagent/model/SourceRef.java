package com.example.ragagent.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One retrieved chunk as shown in the answer's source list.
 *
 * <p>The last three components are <b>retrieval diagnostics</b> (1단계): they explain why this
 * chunk was retrieved, not how much of the answer came from it. All three are nullable and every
 * consumer must treat {@code null} as "not measured" rather than zero — a chunk that only matched
 * on the BM25/curated axes genuinely has no vector similarity, an answer restored from history or
 * reused from the DB has no fusion state at all, and the expansion-failure fallback path in
 * {@code RetrievalService} skips RRF entirely.
 *
 * <p>The 5-arg constructor exists for exactly those metric-less paths. Jackson always uses the
 * canonical (8-arg) one, so a record persisted before these fields existed deserializes with nulls.
 */
public record SourceRef(
        String label,
        String preview,
        @JsonProperty("chunk_id") String chunkId,
        @JsonProperty("doc_id") String docId,
        @JsonProperty("page_or_slide") Object pageOrSlide,
        /** Cosine similarity to the query, {@code 1 - distance}, 0.0~1.0. Null off the vector axes. */
        @JsonProperty("similarity") Double similarity,
        /**
         * This chunk's weighted-RRF score as a fraction of the whole final cut (Σ = 1.0 across the
         * turn's sources). Rank-based, so it is deliberately flat — see {@code axisRanks} for the
         * discriminating detail and {@code similarity} for actual closeness.
         */
        @JsonProperty("retrieval_share") Double retrievalShare,
        /** Pre-formatted per-axis rank, e.g. {@code "vec:2, bm25:5"}. Null when no fusion ran. */
        @JsonProperty("axis_ranks") String axisRanks,
        /**
         * 2단계 — estimated share of the final answer's text attributable to this chunk (0.0~1.0,
         * summing to 1.0 across the chunks that received anything). Unlike the three fields above
         * this is not known at retrieval time; it is filled in after the answer exists. Null means
         * either "not computed for this turn" or "this chunk matched nothing" — see
         * {@code AnswerAttribution}, and note it is an estimate, never a causal measurement.
         */
        @JsonProperty("answer_share") Double answerShare,
        /**
         * 이 출처 청크가 턴이 기록된 뒤 삭제/수정되었는지({@code turn_source_ref.status}).
         * {@code null}/{@code "active"}는 스냅샷 당시와 동일하다는 뜻. 검색 시점에는 정의상 존재할
         * 수 없고, 대화 기록을 다시 열 때만 채워진다 — 그래서 {@code retrieval_metrics} blob에
         * 저장되지 않으며 매번 현재 DB 상태로 새로 계산된다.
         */
        @JsonProperty("stale") String staleStatus
) {
    public static final String STALE_DELETED = "deleted";
    public static final String STALE_MODIFIED = "modified";

    /** Metric-less sources (DB-reuse, restored history, fallback path). */
    public SourceRef(String label, String preview, String chunkId, String docId, Object pageOrSlide) {
        this(label, preview, chunkId, docId, pageOrSlide, null, null, null, null, null);
    }

    /** Retrieval-time construction — the answer share is attached later. */
    public SourceRef(String label, String preview, String chunkId, String docId, Object pageOrSlide,
                     Double similarity, Double retrievalShare, String axisRanks) {
        this(label, preview, chunkId, docId, pageOrSlide, similarity, retrievalShare, axisRanks, null, null);
    }

    /**
     * 대화 기록에서 이 출처에 "삭제됨/수정됨" 배지를 붙일지, 붙인다면 무슨 문구로.
     *
     * <p><b>수정은 답변에 지분이 있었던 출처에만</b> 표시한다 — topK개가 검색돼도 답변을 실제로
     * 떠받친 건 보통 두세 개이고, 한 글자도 반영되지 않은 청크가 손질됐다는 사실은 이 답변을 다시
     * 읽는 사람에게 아무 의미가 없다. 전부 표시하면 문서를 한 번 손볼 때마다 과거 대화 전체가
     * 배지로 뒤덮여 정작 중요한 경고가 묻힌다.
     *
     * <p><b>삭제는 지분과 무관하게 항상</b> 표시한다 — 근거로 쓰였든 아니든 클릭해도 원문이 없고,
     * 침묵하면 "출처는 있는데 열리지 않는" 상태가 되기 때문이다.
     *
     * @return 배지 문구, 또는 표시하지 않을 때 {@code null}
     */
    public String staleBadge() {
        if (STALE_DELETED.equals(staleStatus)) return "삭제됨";
        if (staleStatus == null || "active".equals(staleStatus)) return null;
        return (answerShare != null && answerShare > 0.0) ? "수정됨" : null;
    }

    /**
     * 출처 목록의 표시 순서 — <b>1순위 응답 참여도, 2순위 유사도</b>, 둘 다 내림차순.
     *
     * <p>검색 순서(RRF 랭킹)가 아니라 이 순서로 보여주는 이유: 답변을 읽는 사람이 "이 문장은
     * 어디서 왔나"를 확인하려고 목록을 여는데, 검색 랭킹 1위가 답변에 한 글자도 기여하지 않은
     * 경우가 흔하기 때문이다. 실제로 답변을 떠받친 청크가 맨 앞에 와야 한다.
     *
     * <p>두 값 모두 nullable이고 {@code null}은 "측정 안 됨"이지 0이 아니지만(§ SourceRef),
     * 정렬에서는 <b>측정된 값이 항상 앞</b>이다 — 숫자를 아는 출처를 모르는 출처 뒤로 보내면
     * 이 정렬이 존재하는 이유가 사라진다. 비교가 완전히 동률이면 원래(검색) 순서를 유지한다
     * ({@link List#sort} 는 안정 정렬).
     */
    public static final Comparator<SourceRef> DISPLAY_ORDER =
            Comparator.<SourceRef, Double>comparing(SourceRef::answerShare,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(SourceRef::similarity,
                            Comparator.nullsLast(Comparator.reverseOrder()));

    /** {@link #DISPLAY_ORDER} 로 정렬한 새 목록. null/빈 목록은 그대로 돌려준다. */
    public static List<SourceRef> sortedForDisplay(List<SourceRef> sources) {
        if (sources == null || sources.size() < 2) return sources;
        List<SourceRef> out = new ArrayList<>(sources);
        out.sort(DISPLAY_ORDER);
        return List.copyOf(out);
    }
}
