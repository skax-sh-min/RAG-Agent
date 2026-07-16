package com.example.ragagent.config;

import java.util.List;

/**
 * Canonical keys for the runtime settings-override layer.
 *
 * <p>Each key is the {@code application.properties} suffix (without the {@code app.} prefix) of a
 * <b>hot-editable</b> value — one whose consumer re-reads it from {@code props.xxxSafe()} on every
 * use, so an override applied here takes effect without a restart. Two families:
 * <ul>
 *   <li><b>search tuning</b> — {@code RetrievalService} re-reads them per retrieval → apply on the
 *       next search (similarity threshold, RRF, candidate multipliers, multiquery, topK, hybrid);</li>
 *   <li><b>indexing/chunking</b> — {@code DocumentIndexer} re-reads them per index → apply on the
 *       next indexing or {@code /admin} ↺ re-index (chunk size/overlap/min, file concurrency).</li>
 * </ul>
 * The same string is used three ways, kept in one place so they never drift:
 * <ol>
 *   <li>the {@code settings_override.key} column ({@code SettingsOverrideRepository}),</li>
 *   <li>the lookup {@link AppProperties} does inside each hot {@code xxxSafe()} accessor,</li>
 *   <li>the catalog {@code SettingsService} builds the {@code /settings} view/validation from.</li>
 * </ol>
 *
 * <p>Restart-required values (rerank/hybrid enabled, vectorstore type, embedding dimensions, auth,
 * DB paths) are intentionally absent — they are decided at bean-creation time and cannot be
 * hot-swapped, so the settings page shows them read-only rather than storing an override that would
 * silently do nothing.
 */
public final class SettingsKeys {

    private SettingsKeys() {}

    // ── Search tuning (apply on the next search) ─────────────────────────────
    public static final String SEARCH_SIMILARITY_THRESHOLD    = "search-similarity-threshold";
    public static final String SEARCH_RRF_KEYWORD_WEIGHT      = "search-rrf-keyword-weight";
    public static final String SEARCH_RRF_K                   = "search-rrf-k";
    public static final String SEARCH_CANDIDATE_MULTIPLIER    = "search-candidate-multiplier";
    public static final String SEARCH_TAG_CANDIDATE_MULTIPLIER = "search-tag-candidate-multiplier";
    public static final String SEARCH_MULTIQUERY_MIN_LENGTH   = "search-multiquery-min-length";
    public static final String SEARCH_RETRY_ESCALATE          = "search-retry-escalate";
    public static final String SEARCH_TOP_K                   = "search-top-k";
    public static final String SEARCH_MULTIQUERY_ENABLED      = "search-multiquery-enabled";
    public static final String SEARCH_HYBRID_ENABLED          = "search-hybrid-enabled";

    // ── Indexing / chunking (apply on the next indexing / ↺ re-index) ────────
    public static final String CHUNK_SIZE                     = "chunk-size";
    public static final String CHUNK_OVERLAP                  = "chunk-overlap";
    public static final String MIN_CHUNK_SIZE                 = "min-chunk-size";
    public static final String INDEXING_MAX_CONCURRENT_FILES  = "indexing.max-concurrent-files";

    /** All hot-editable keys, in the order they are grouped on the settings page. */
    public static final List<String> HOT_EDITABLE = List.of(
            SEARCH_SIMILARITY_THRESHOLD,
            SEARCH_RRF_KEYWORD_WEIGHT,
            SEARCH_RRF_K,
            SEARCH_CANDIDATE_MULTIPLIER,
            SEARCH_TAG_CANDIDATE_MULTIPLIER,
            SEARCH_MULTIQUERY_MIN_LENGTH,
            SEARCH_RETRY_ESCALATE,
            SEARCH_TOP_K,
            SEARCH_MULTIQUERY_ENABLED,
            SEARCH_HYBRID_ENABLED,
            CHUNK_SIZE,
            CHUNK_OVERLAP,
            MIN_CHUNK_SIZE,
            INDEXING_MAX_CONCURRENT_FILES
    );
}
