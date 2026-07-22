package com.example.ragagent.ingestion;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * §10.10 — text transform specific to curated Q&A (liked chat turns promoted to a separate
 * searchable knowledge axis, see documents/PLAN.md §10.10). Pure text util, no {@code @Component}
 * — mirrors {@link MarkdownNoiseNormalizer}'s shape.
 */
public final class CuratedTextUtils {

    // prompt.answer.system (messages_ko.properties) instructs the LLM to always end an answer
    // with a "## 참고" section listing cited [filename | p.N] (heading) bullets. This is literal
    // text inside the answer string — not a separately computed source list — and always the
    // LAST section per the prompt's fixed format, so truncating at the last match is safe.
    private static final Pattern REFERENCE_HEADING = Pattern.compile("(?m)^#{0,3}\\s*참고\\s*$");

    private CuratedTextUtils() {}

    /**
     * Strips the trailing "## 참고" citation section (filenames/page numbers — structural noise
     * for question-driven semantic matching) from curated Q&A embedding input. Never applied to
     * the stored/displayed answer — only to the derived text used to compute the search vector.
     */
    public static String stripReferenceSection(String answer) {
        if (answer == null) return "";
        Matcher m = REFERENCE_HEADING.matcher(answer);
        int lastStart = -1;
        while (m.find()) lastStart = m.start();
        return lastStart >= 0 ? answer.substring(0, lastStart).strip() : answer.strip();
    }
}
