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
    // Tolerates heading-text variants an LLM (especially a smaller/local one) might produce instead of
    // the exact instructed word — "참고자료"/"참고 사항"/"참고문헌" etc. — as long as a '#' marks it
    // as an actual heading; the hash-less bare-heading branch stays an EXACT "참고" match on
    // purpose, since loosening that one risks matching ordinary prose that merely starts a line
    // with the word "참고" (not a heading at all).
    private static final Pattern REFERENCE_HEADING =
            Pattern.compile("(?m)^(?:#{1,3}\\s*참고.*|참고)\\s*$");

    // prompt.answer.system's fixed 5-section format: 요약 → 상세 설명 → 예시/코드 → 설정/주의사항 → 참고.
    // Same heading-variant tolerance as REFERENCE_HEADING ("## 상세 설명 및 배경" etc. still matches).
    private static final Pattern DETAIL_HEADING = Pattern.compile("(?m)^#{1,3}\\s*상세\\s*설명.*$");

    // The leading "## 요약" section — stripped by stripSummarySection (see below). Same
    // heading-variant tolerance as REFERENCE_HEADING/DETAIL_HEADING.
    private static final Pattern SUMMARY_HEADING = Pattern.compile("(?m)^#{1,3}\\s*요약.*$");

    // Generic markdown heading line (any level, any text) — used by stripSummarySection to find
    // where the summary section ends (the next heading, whatever it is).
    private static final Pattern ANY_HEADING = Pattern.compile("(?m)^#{1,6}\\s*\\S.*$");

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
     * Strips the leading "## 요약" section (up to whatever heading follows it, or to the end if
     * 요약 is the last/only content) from curated Q&A embedding input — a 1-2 sentence recap that
     * only dilutes question-driven semantic matching once "## 상세 설명" carries the same
     * information. A no-op (returns the input unchanged, just stripped of surrounding whitespace)
     * when there's no "## 요약" heading at all — e.g. a Direct-mode/meta answer, which never
     * follows this RAG-answer format and has nothing to strip. Never applied to the
     * stored/displayed answer — only to the derived text used to compute the search vector.
     */
    public static String stripSummarySection(String answer) {
        if (answer == null) return "";
        Matcher summaryMatch = SUMMARY_HEADING.matcher(answer);
        if (!summaryMatch.find()) return answer.strip();
        int start = summaryMatch.start();
        Matcher nextHeading = ANY_HEADING.matcher(answer);
        int end = answer.length();
        while (nextHeading.find()) {
            if (nextHeading.start() > summaryMatch.end()) {
                end = nextHeading.start();
                break;
            }
        }
        return (answer.substring(0, start) + answer.substring(end)).strip();
    }

    /**
     * Strips both structural sections that dilute question-driven semantic matching: the leading
     * "## 요약" ({@link #stripSummarySection}) and the trailing "## 참고" ({@link
     * #stripReferenceSection}). This is the whole of {@code CuratedQaService}'s default embed
     * text (question + this, see {@code defaultSearchText}) — {@link #extractCoreSections} builds
     * on the same pipeline for its narrower size-reduction fallback.
     */
    public static String stripStructuralSections(String answer) {
        return stripSummarySection(stripReferenceSection(answer));
    }

    /**
     * §10.10 embedding-fallback — when embedding fails (typically: the combined text exceeds the
     * embedding server's input limit) even after {@code CuratedQaService}'s default search text
     * ({@link #stripStructuralSections}) already dropped "요약"/"참고", this narrows further to
     * just the answer's "상세 설명"/"예시/코드"/"설정/주의사항" sections, with "**"/"__" emphasis
     * markers additionally stripped for further size reduction. Since {@link #stripSummarySection}
     * only removes a leading "## 요약" when one is actually present, the "## 상세 설명" existence
     * check below is what actually enforces "narrower than the default" here — without it, an
     * answer that already has no "## 요약" (or no structure at all) would make this method return
     * its input back unchanged instead of a genuinely smaller fallback.
     *
     * <p>Returns {@code ""} when the answer has no "## 상세 설명" heading at all — a Direct-mode/
     * meta answer (greeting, chit-chat) never follows this RAG-answer format, so there is no
     * shorter fallback to extract; callers should treat an empty result as "give up," not retry
     * with an empty embedding input.
     */
    public static String extractCoreSections(String answer) {
        String withoutReferences = stripReferenceSection(answer);
        if (withoutReferences.isEmpty() || !DETAIL_HEADING.matcher(withoutReferences).find()) {
            return "";
        }
        return stripEmphasis(stripStructuralSections(answer));
    }

    private static String stripEmphasis(String text) {
        return EMPHASIS.matcher(text).replaceAll(mr -> mr.group(1) != null ? mr.group(1) : mr.group(2));
    }
}
