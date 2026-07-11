package com.example.ragagent.ingestion;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strips markdown decoration (separator lines, emphasis markers) from chunk text before it is
 * used as embedding/FTS/answer-prompt input — never for the text shown to users. Pure text
 * transform, no offset mapping back to the original string (§10.1-보완).
 */
public final class MarkdownNoiseNormalizer {

    private static final Pattern BOLD      = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern ITALIC    = Pattern.compile("\\*(.+?)\\*");
    private static final Pattern UNDERLINE = Pattern.compile("(?i)<u>(.*?)</u>");
    // Peels a leading list marker ("- ", "* ", "1. ") before emphasis-stripping the rest of the
    // line, so a bullet's own "* " is never mistaken for an opening italic marker.
    private static final Pattern LIST_MARKER = Pattern.compile("^(\\s*(?:[-*+]|\\d+[.)])\\s+)(.*)$");
    private static final Pattern BLANK_RUN = Pattern.compile("\n{3,}");

    // Deliberately conservative: an unrecognized symbol-only line is left untouched rather than
    // risk a false positive — a miss just costs a few tokens, a false positive corrupts content.
    private static final String DECORATIVE_CHARS = "-=_~*#+.·•‧━─═";
    private static final int MIN_DECORATIVE_LEN = 3;

    private MarkdownNoiseNormalizer() {}

    public static String normalize(String text) {
        if (text == null) return "";
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length());
        boolean insideFence = false;
        for (String line : lines) {
            String trimmed = line.strip();
            boolean isFenceLine = trimmed.startsWith("```") || trimmed.startsWith("~~~");
            boolean wasInsideFence = insideFence;
            if (isFenceLine) insideFence = !insideFence;

            String processed;
            if (isFenceLine || wasInsideFence) {
                processed = line;                 // code fence delimiter/interior: untouched
            } else if (isTableLine(trimmed)) {
                processed = line;                 // table row/separator: untouched
            } else if (isDecorativeLine(trimmed)) {
                processed = null;                 // drop the whole line
            } else {
                processed = stripEmphasis(line);
            }
            if (processed != null) {
                if (!out.isEmpty()) out.append('\n');
                out.append(processed);
            }
        }
        return BLANK_RUN.matcher(out).replaceAll("\n\n").strip();
    }

    private static boolean isTableLine(String trimmed) {
        return trimmed.startsWith("|") && trimmed.chars().filter(c -> c == '|').count() >= 2;
    }

    private static boolean isDecorativeLine(String trimmed) {
        String noWs = trimmed.replaceAll("\\s+", "");
        if (noWs.length() < MIN_DECORATIVE_LEN) return false;
        for (int i = 0; i < noWs.length(); i++) {
            char c = noWs.charAt(i);
            if (Character.isLetterOrDigit(c)) return false;   // alnum/CJK → real content
            if (DECORATIVE_CHARS.indexOf(c) < 0) return false; // unknown symbol → be conservative
        }
        return true;
    }

    private static String stripEmphasis(String line) {
        Matcher m = LIST_MARKER.matcher(line);
        String prefix = "";
        String rest = line;
        if (m.matches()) {
            prefix = m.group(1);
            rest = m.group(2);
        }
        rest = BOLD.matcher(rest).replaceAll("$1");
        rest = ITALIC.matcher(rest).replaceAll("$1");
        rest = UNDERLINE.matcher(rest).replaceAll("$1");
        return prefix + rest;
    }
}
