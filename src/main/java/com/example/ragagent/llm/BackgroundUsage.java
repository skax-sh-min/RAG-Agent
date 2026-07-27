package com.example.ragagent.llm;

import java.util.Set;

/**
 * Reserved provider-name prefixes for non-chat/background LLM calls (conversation
 * summarization, indexing keyword extraction, document format correction, TXT→MD structuring,
 * indexing-time image (Vision) description, thread title generation) — recorded into {@code llm_usage} via
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
    // Indexing-time Vision descriptions (MarkdownCorrectionService, when "add image descriptions"
    // is on) — kept separate from search-time Vision (VisionDescriptionService, recorded under the
    // bare provider name) so the dashboard shows indexing image cost distinctly.
    public static final String IMAGE_PREFIX     = "image:";
    // §10.1 — KeywordExtractor now extracts keywords + context in one call, tracked under this
    // label. KEYWORD_PREFIX is kept below (no new rows) so isBackground() still recognizes
    // historical keyword: rows recorded before this switch.
    public static final String CONTEXT_PREFIX   = "context:";

    private static final Set<String> PREFIXES = Set.of(
            SUMMARY_PREFIX, KEYWORD_PREFIX, MDCORRECT_PREFIX, TXT2MD_PREFIX, TITLE_PREFIX, IMAGE_PREFIX, CONTEXT_PREFIX);

    /** True when {@code providerName} was recorded by one of the background call sites above. */
    public static boolean isBackground(String providerName) {
        return PREFIXES.stream().anyMatch(providerName::startsWith);
    }

    /** All known background-usage prefixes (each ending in {@code ':'}) — /llm-usage iterates these
     *  to find which categories have any recorded usage, merged across whichever underlying LOCAL
     *  provider(s) actually served each call (see {@link #label}). */
    public static Set<String> prefixes() {
        return PREFIXES;
    }

    /** Display label for a prefix — the prefix without its trailing {@code ':'} (e.g. {@code "title:"} → {@code "title"}). */
    public static String label(String prefix) {
        return prefix.endsWith(":") ? prefix.substring(0, prefix.length() - 1) : prefix;
    }

    private BackgroundUsage() {}
}
