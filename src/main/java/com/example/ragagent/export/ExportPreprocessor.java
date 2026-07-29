package com.example.ragagent.export;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns reassembled chunk markdown into reader-facing markdown: rewrites the indexing markers that
 * only ever existed to carry structure through the pipeline, and optionally adds hierarchical
 * heading numbers plus a table of contents.
 *
 * <p>Marker handling (see USER_MANUAL §2.3 for the operator-facing summary):
 * <ul>
 *   <li>{@code [이미지: images/{id}/f.png]} → delegated to the caller's {@code imageRewriter}, since
 *       each format embeds images differently (MD links them, DOCX embeds bytes, TXT names them);</li>
 *   <li>{@code [이미지 설명: …]} → a {@code >} blockquote, or dropped entirely. <b>This marker spans
 *       multiple lines</b> and its body contains markdown and blank lines, so it is matched by
 *       bracket depth rather than by a line regex — the existing single-line
 *       {@code IMAGE_DESC_LINE_FULL} pattern in {@code MarkdownCorrectionService} cannot see these;</li>
 *   <li>{@code [페이지: N]} and the {@code [도형 그룹]}/{@code [다이어그램]} scaffolding tags → removed;
 *       they mark structure for the indexer and read as noise in an exported document. The text
 *       inside a shape group is kept — only the wrapper tags go.</li>
 * </ul>
 */
public final class ExportPreprocessor {

    private static final Pattern IMAGE_MARKER =
            Pattern.compile("^\\[이미지(?:\\([^)]*\\))?:\\s*([^\\]]+?)]$");
    private static final Pattern IMAGE_DESC_START = Pattern.compile("^\\[이미지 설명:\\s*(.*)$");
    private static final Pattern BLOCKQUOTE_DESC  = Pattern.compile("^>\\s*이미지 설명:\\s*(.*)$");
    private static final Pattern PAGE_MARKER      = Pattern.compile("^\\[페이지:\\s*\\d+]$");
    private static final Pattern SCAFFOLD_TAG     = Pattern.compile("^\\[/?(?:다이어그램|도형 그룹)(?:\\s*\\d+)?]$");
    private static final Pattern CHART_TAG        = Pattern.compile("^\\[차트(?:\\s*\\d+)?:\\s*([^\\]]*)]$");
    private static final Pattern HEADING          = Pattern.compile("^(#{1,6})\\s+(.*\\S)\\s*$");
    /** A heading number already applied at upload time, e.g. {@code "## 1.2 제목"}. */
    private static final Pattern EXISTING_NUMBER  = Pattern.compile("^\\d+(?:\\.\\d+)*\\.?\\s+");

    private ExportPreprocessor() {}

    /**
     * @param imageRewriter maps an image path (as written in the marker) to the replacement line;
     *                      returning {@code null} drops the image line entirely
     */
    /**
     * @param imageRewriter {@code (imagePath, atLineStart) -> replacement}. {@code atLineStart} is
     *                      false when the marker sits mid-line (typically inside a table cell,
     *                      where {@code MarkdownCorrectionService} joins with {@code <br>}) — a
     *                      renderer must not emit block-level output there or it breaks the table.
     *                      Returning {@code null}/blank drops the image.
     */
    public static String preprocess(String markdown, boolean includeImageDescriptions,
                                    boolean addHeadingNumbersAndToc,
                                    BiFunction<String, Boolean, String> imageRewriter) {
        if (markdown == null || markdown.isBlank()) return "";

        // Image + description markers first, document-wide: both occur inline as well as on their
        // own line, so they can't be handled by a per-line regex (that was missing every marker
        // embedded in a table cell).
        String body = rewriteImageMarkers(markdown, includeImageDescriptions, imageRewriter);
        body = rewriteLineMarkers(body, includeImageDescriptions);
        if (addHeadingNumbersAndToc) {
            body = applyHeadingNumbers(body);
            body = buildToc(body) + body;
        }
        return collapseBlankRuns(body);
    }

    /**
     * Rewrites every {@code [이미지: …]}, {@code [이미지(변환불가): …]} and {@code [이미지 설명: …]}
     * occurrence, wherever it sits. Extents are found by {@code [}/{@code ]} depth rather than by a
     * line pattern, because a description body legitimately contains brackets, markdown and blank
     * lines and often runs for many lines (or is glued mid-line after a {@code <br>}).
     */
    private static String rewriteImageMarkers(String markdown, boolean includeDescriptions,
                                              BiFunction<String, Boolean, String> imageRewriter) {
        StringBuilder out = new StringBuilder(markdown.length());
        int i = 0;
        while (i < markdown.length()) {
            char c = markdown.charAt(i);
            if (c != '[') {
                out.append(c);
                i++;
                continue;
            }
            MarkerKind kind = kindAt(markdown, i);
            if (kind == null) {
                out.append(c);
                i++;
                continue;
            }
            int close = matchingClose(markdown, i);
            if (close < 0) {                       // never closed (truncated source) — leave as-is
                out.append(c);
                i++;
                continue;
            }
            String inner = markdown.substring(i, close + 1);
            boolean atLineStart = isAtLineStart(out);

            if (kind == MarkerKind.DESCRIPTION) {
                if (includeDescriptions) {
                    String body = inner.substring(inner.indexOf(':') + 1, inner.length() - 1).strip();
                    trimTrailingBreak(out);        // drop the <br>/whitespace that glued the marker on
                    out.append(atLineStart ? asBlockquote(body) : "(이미지 설명: " + oneLine(body) + ")");
                } else {
                    trimTrailingBreak(out);
                }
            } else {
                String path = inner.substring(inner.indexOf(':') + 1, inner.length() - 1).strip();
                String replacement = imageRewriter.apply(path, atLineStart);
                if (replacement != null && !replacement.isBlank()) out.append(replacement);
            }
            i = close + 1;
        }
        return out.toString();
    }

    private enum MarkerKind { IMAGE, DESCRIPTION }

    /** Which marker (if any) starts at {@code idx}; {@code null} for an ordinary {@code [}. */
    private static MarkerKind kindAt(String s, int idx) {
        if (s.startsWith("[이미지 설명:", idx)) return MarkerKind.DESCRIPTION;
        if (s.startsWith("[이미지:", idx)) return MarkerKind.IMAGE;
        if (s.startsWith("[이미지(", idx)) {          // [이미지(변환불가): …]
            int colon = s.indexOf(':', idx);
            int bracketEnd = s.indexOf(')', idx);
            if (colon > 0 && bracketEnd > 0 && bracketEnd < colon) return MarkerKind.IMAGE;
        }
        return null;
    }

    /** Index of the {@code ]} closing the bracket opened at {@code open}, or -1 if unbalanced. */
    private static int matchingClose(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[') depth++;
            else if (c == ']' && --depth == 0) return i;
        }
        return -1;
    }

    /** True when nothing but whitespace has been emitted since the last newline. */
    private static boolean isAtLineStart(CharSequence out) {
        for (int i = out.length() - 1; i >= 0; i--) {
            char c = out.charAt(i);
            if (c == '\n') return true;
            if (!Character.isWhitespace(c)) return false;
        }
        return true;
    }

    /** Removes the {@code <br>} / trailing spaces that attached a marker to the preceding text. */
    private static void trimTrailingBreak(StringBuilder out) {
        while (out.length() > 0 && (out.charAt(out.length() - 1) == ' ' || out.charAt(out.length() - 1) == '\t')) {
            out.setLength(out.length() - 1);
        }
        for (String br : new String[]{"<br>", "<br/>", "<br />"}) {
            if (out.length() >= br.length()
                    && out.substring(out.length() - br.length()).equalsIgnoreCase(br)) {
                out.setLength(out.length() - br.length());
                return;
            }
        }
    }

    /** Block-level rendering of a description — its own blockquote, kept multi-paragraph. */
    private static String asBlockquote(String body) {
        StringBuilder sb = new StringBuilder("> **이미지 설명**\n");
        for (String line : body.split("\n", -1)) {
            String t = line.strip();
            sb.append(t.isEmpty() ? ">" : "> " + (t.startsWith(">") ? t.substring(1).strip() : t)).append('\n');
        }
        return sb.toString();
    }

    /** Inline rendering — newlines would break the table cell the marker is sitting in. */
    private static String oneLine(String body) {
        return body.replaceAll("\\s*\n\\s*", " ").strip();
    }

    /** Per-line pass for the markers that only ever occupy a whole line. */
    private static String rewriteLineMarkers(String markdown, boolean includeDescriptions) {
        List<String> out = new ArrayList<>();
        for (String line : markdown.split("\n", -1)) {
            String t = line.strip();
            if (PAGE_MARKER.matcher(t).matches() || SCAFFOLD_TAG.matcher(t).matches()) continue;

            Matcher chart = CHART_TAG.matcher(t);
            if (chart.matches()) {                        // keep the chart's label, drop the wrapper
                if (!chart.group(1).isBlank()) out.add(chart.group(1).strip());
                continue;
            }
            if (BLOCKQUOTE_DESC.matcher(t).matches()) {   // "> 이미지 설명: …" variant
                if (includeDescriptions) out.add(line);
                continue;
            }
            out.add(line);
        }
        return String.join("\n", out);
    }

    /**
     * Hierarchical numbering for H2–H6, fence-aware. Any number already present is stripped first,
     * so re-numbering an already-numbered document is idempotent rather than cumulative — the same
     * contract {@code MarkdownCorrectionService.addHierarchicalHeadingNumbers()} follows at indexing
     * time (reimplemented here because that method is private to the indexing pipeline).
     */
    static String applyHeadingNumbers(String markdown) {
        int[] counters = new int[5];                    // ## … ######
        boolean inFence = false;
        List<String> out = new ArrayList<>();

        for (String line : markdown.split("\n", -1)) {
            if (line.strip().startsWith("```")) inFence = !inFence;
            Matcher m = inFence ? null : HEADING.matcher(line.strip());
            if (m == null || !m.matches() || m.group(1).length() < 2) {
                out.add(line);
                continue;
            }
            int level = m.group(1).length();            // 2..6
            int idx = level - 2;
            counters[idx]++;
            for (int deeper = idx + 1; deeper < counters.length; deeper++) counters[deeper] = 0;

            StringBuilder num = new StringBuilder();
            for (int k = 0; k <= idx; k++) {
                if (k > 0) num.append('.');
                num.append(counters[k]);
            }
            String text = EXISTING_NUMBER.matcher(m.group(2)).replaceFirst("");
            out.add(m.group(1) + " " + num + " " + text);
        }
        return String.join("\n", out);
    }

    /** Markdown TOC of the H2–H6 headings, emitted above the body. Empty when there are none. */
    static String buildToc(String markdown) {
        List<String> entries = new ArrayList<>();
        boolean inFence = false;

        for (String line : markdown.split("\n", -1)) {
            if (line.strip().startsWith("```")) inFence = !inFence;
            if (inFence) continue;
            Matcher m = HEADING.matcher(line.strip());
            if (m.matches() && m.group(1).length() >= 2) {
                entries.add("  ".repeat(m.group(1).length() - 2) + "- " + m.group(2));
            }
        }
        if (entries.isEmpty()) return "";
        return "## 목차\n\n" + String.join("\n", entries) + "\n\n---\n\n";
    }

    /** Collapses 3+ consecutive blank lines (marker removal leaves gaps) down to one. */
    private static String collapseBlankRuns(String markdown) {
        return markdown.replaceAll("\n{3,}", "\n\n").strip();
    }
}
