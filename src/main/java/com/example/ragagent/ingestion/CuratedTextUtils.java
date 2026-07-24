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

    // prompt.answer.system's fixed 5-section format: 요약 → 상세 설명 → 예시/코드 → 설정/주의사항 → 참고.
    // "상세 설명" is always the section right after the summary, so slicing from here to the end
    // (참고 already stripped by stripReferenceSection) keeps 상세 설명/예시·코드/설정·주의사항 and
    // drops only 요약 (redundant once the detail is included) — see extractCoreSections.
    private static final Pattern DETAIL_HEADING = Pattern.compile("(?m)^#{1,3}\\s*상세\\s*설명\\s*$");

    // Markdown strong-emphasis markers (**bold**, __bold__) — pure size reduction for the
    // embedding-fallback input, no semantic loss (the emphasized text itself is kept).
    private static final Pattern EMPHASIS = Pattern.compile("\\*\\*(.+?)\\*\\*|__(.+?)__");

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

    /**
     * §10.10 embedding-fallback — when embedding the full question+answer fails (typically: the
     * combined text exceeds the embedding server's input limit), {@code CuratedQaService} retries
     * with just this narrower slice: the answer's "상세 설명"/"예시/코드"/"설정/주의사항" sections,
     * with "**"/"__" emphasis markers stripped for further size reduction. Drops "요약" (redundant
     * once the detail section is included) and "참고" (citation noise, already excluded by
     * {@link #stripReferenceSection}).
     *
     * <p>Returns {@code ""} when the answer has no "## 상세 설명" heading at all — a Direct-mode/
     * meta answer (greeting, chit-chat) never follows this RAG-answer format, so there is no
     * shorter fallback to extract; callers should treat an empty result as "give up," not retry
     * with an empty embedding input.
     */
    public static String extractCoreSections(String answer) {
        String withoutReferences = stripReferenceSection(answer);
        if (withoutReferences.isEmpty()) return "";
        Matcher m = DETAIL_HEADING.matcher(withoutReferences);
        if (!m.find()) return "";
        return stripEmphasis(withoutReferences.substring(m.start()).strip());
    }

    private static String stripEmphasis(String text) {
        return EMPHASIS.matcher(text).replaceAll(mr -> mr.group(1) != null ? mr.group(1) : mr.group(2));
    }
}
