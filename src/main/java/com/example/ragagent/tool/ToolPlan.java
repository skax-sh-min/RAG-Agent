package com.example.ragagent.tool;

/**
 * Tool 선택 결과(§6.33 3단계) — 이 턴에서 모델이 고른 Tool 하나와 그 인자. <b>고르지 않았거나 읽지 못했으면 계획 자체가 없다</b>
 * ({@code null}, 그 턴은 늘 하던 {@code RETRIEVAL}) — "Tool 없음"을 잡담으로 읽지 않는다(설계서 D3).
 *
 * @param toolName      {@link ToolCatalog} 에 있는 이름
 * @param argumentsJson 스키마 검증을 통과한 인자 JSON 객체
 */
public record ToolPlan(String toolName, String argumentsJson) {

    /** 검색 Tool — 이 Tool 을 고르면 그래프는 새 경로를 타지 않고 기존 {@code RETRIEVAL}(검색 루프)로 간다(설계서 D1). */
    public static final String SEARCH_TOOL = "searchKnowledge";

    /** 그래프가 {@code TOOL} 노드로 보내야 하는가 — 검색 Tool 이 아닌 Tool 을 골랐을 때만. */
    public boolean routesToToolNode() {
        return !SEARCH_TOOL.equals(toolName);
    }
}
