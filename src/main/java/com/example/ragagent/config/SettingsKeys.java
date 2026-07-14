package com.example.ragagent.config;

import java.util.List;

/**
 * Canonical keys for the runtime settings-override layer.
 *
 * <p>Each key is the {@code application.properties} suffix (without the {@code app.} prefix) of a
 * <b>hot-editable</b> search-tuning value — one that {@link RetrievalService} re-reads from
 * {@code props.xxxSafe()} on every retrieval, so an override applied here takes effect on the next
 * search without a restart. The same string is used three ways, kept in one place so they never
 * drift:
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

    public static final String SEARCH_SIMILARITY_THRESHOLD    = "search-similarity-threshold";
    public static final String SEARCH_RRF_KEYWORD_WEIGHT      = "search-rrf-keyword-weight";
    public static final String SEARCH_RRF_K                   = "search-rrf-k";
    public static final String SEARCH_CANDIDATE_MULTIPLIER    = "search-candidate-multiplier";
    public static final String SEARCH_TAG_CANDIDATE_MULTIPLIER = "search-tag-candidate-multiplier";
    public static final String SEARCH_MULTIQUERY_MIN_LENGTH   = "search-multiquery-min-length";
    public static final String SEARCH_RETRY_ESCALATE          = "search-retry-escalate";

    /** All hot-editable keys, in the order they are grouped on the settings page. */
    public static final List<String> HOT_EDITABLE = List.of(
            SEARCH_SIMILARITY_THRESHOLD,
            SEARCH_RRF_KEYWORD_WEIGHT,
            SEARCH_RRF_K,
            SEARCH_CANDIDATE_MULTIPLIER,
            SEARCH_TAG_CANDIDATE_MULTIPLIER,
            SEARCH_MULTIQUERY_MIN_LENGTH,
            SEARCH_RETRY_ESCALATE
    );
}
