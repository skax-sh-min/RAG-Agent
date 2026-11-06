package com.example.ragagent.tool;

import com.example.ragagent.ingestion.MarkdownNoiseNormalizer;

/**
 * Tool 결과를 짧게 만드는 규칙 (AGENT_MCP_DESIGN §3.1 — 발췌 300자, 50행). 결과는 모델의 입력 토큰이 되므로 상한은 여기 한 곳이다.
 */
public final class ToolResultFormatter {

    public static final int MAX_SNIPPET_CHARS = 300;
    public static final int MAX_ROWS = 50;
    /** LLM·외부 클라이언트가 보낸 검색어·인자의 길이 상한 — 이보다 긴 입력은 거절한다. */
    public static final int MAX_QUERY_CHARS = 500;

    private ToolResultFormatter() {}

    /** 검색용 장식을 걷고 공백을 한 칸으로 줄여 {@value #MAX_SNIPPET_CHARS}자로 자른다. */
    public static String snippet(String text) {
        if (text == null) return "";
        String flat = MarkdownNoiseNormalizer.normalize(text).replaceAll("\\s+", " ").strip();
        return flat.length() <= MAX_SNIPPET_CHARS ? flat : flat.substring(0, MAX_SNIPPET_CHARS) + "…";
    }

    /** 필수 문자열 인자 — 비었거나 너무 길면 {@link IllegalArgumentException}. */
    public static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 이(가) 필요합니다");
        }
        String v = value.strip();
        if (v.length() > MAX_QUERY_CHARS) {
            throw new IllegalArgumentException(name + " 이(가) 너무 깁니다(" + MAX_QUERY_CHARS + "자 이하)");
        }
        return v;
    }

    /** 범위로 누른 정수 — {@code null} 이면 기본값. */
    public static int clamp(Integer value, int min, int max, int defaultValue) {
        if (value == null) return defaultValue;
        return Math.max(min, Math.min(max, value));
    }
}
