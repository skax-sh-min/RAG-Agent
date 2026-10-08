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
        int decorative = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (isRegexWhitespace(c)) continue;
            if (Character.isLetterOrDigit(c)) return false;   // alnum/CJK → real content
            if (DECORATIVE_CHARS.indexOf(c) < 0) return false; // unknown symbol → be conservative
            decorative++;
        }
        return decorative >= MIN_DECORATIVE_LEN;
    }

    /**
     * 자바 정규식 {@code \s} 의 기본(ASCII) 집합과 <b>같은</b> 공백 판정.
     *
     * <p>예전에는 {@code trimmed.replaceAll("\\s+", "")} 로 공백을 지운 문자열을 만들어 그것을
     * 검사했다. {@link String#replaceAll} 은 호출마다 {@link Pattern} 을 새로 컴파일하는데 그게
     * <b>줄마다</b> 돌았다 — 이 클래스는 답변 프롬프트·검증 발췌뿐 아니라 {@code ChunkSplitter}
     * 가 일곱 곳(그중 셋은 루프 안)에서 부르는 인덱싱 경로의 상시 호출 지점이라, 청크가 수백
     * 개인 문서 하나에 컴파일이 수만 번 일어났다. 판정에 필요한 것은 "공백을 뺀 나머지가 전부
     * 장식 문자인가" 하나뿐이라 중간 문자열도 정규식도 필요 없다.
     *
     * <p><b>{@link Character#isWhitespace} 를 쓰지 않는 이유</b>: 그쪽은 U+2000 같은 유니코드
     * 공백까지 공백으로 보는데, 예전 판정은 정규식 {@code \s} 가 그것을 안 잡아 <b>모르는
     * 기호</b>로 취급해 "장식 아님"으로 떨어뜨렸다(이 클래스가 명시한 보수적인 쪽). 여기서 만든
     * 텍스트가 임베딩·FTS 입력이 되므로 그 차이는 저장되는 검색 텍스트를 바꾼다 — 빠르게만
     * 만들고 판정은 그대로 둔다.
     */
    private static boolean isRegexWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r';
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
