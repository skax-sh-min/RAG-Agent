package com.example.ragagent.model;

import com.fasterxml.jackson.annotation.JsonProperty;

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
        @JsonProperty("axis_ranks") String axisRanks
) {
    /** Metric-less sources (DB-reuse, restored history, fallback path). */
    public SourceRef(String label, String preview, String chunkId, String docId, Object pageOrSlide) {
        this(label, preview, chunkId, docId, pageOrSlide, null, null, null);
    }
}
