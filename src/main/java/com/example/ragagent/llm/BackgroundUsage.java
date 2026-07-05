package com.example.ragagent.llm;

import java.util.Set;

/**
 * Reserved provider-name prefixes for non-chat/background LLM calls (conversation
 * summarization, indexing keyword extraction, document format correction, TXT→MD structuring,
 * thread title generation) — recorded into {@code llm_usage} via
 * {@link LlmRouter#executeWithTracking(TaskType, RoutingMode, String, java.util.function.Function)}
 * the same way {@link TrackingEmbeddingModel#PROVIDER_PREFIX} separates embedding usage from
 * chat provider rows, without any schema change.
 */
public final class BackgroundUsage {

    public static final String SUMMARY_PREFIX   = "summary:";
    public static final String KEYWORD_PREFIX   = "keyword:";
    public static final String MDCORRECT_PREFIX = "mdcorrect:";
    public static final String TXT2MD_PREFIX    = "txt2md:";
    public static final String TITLE_PREFIX     = "title:";

    private static final Set<String> PREFIXES = Set.of(
            SUMMARY_PREFIX, KEYWORD_PREFIX, MDCORRECT_PREFIX, TXT2MD_PREFIX, TITLE_PREFIX);

    /** True when {@code providerName} was recorded by one of the background call sites above. */
    public static boolean isBackground(String providerName) {
        return PREFIXES.stream().anyMatch(providerName::startsWith);
    }

    private BackgroundUsage() {}
}
