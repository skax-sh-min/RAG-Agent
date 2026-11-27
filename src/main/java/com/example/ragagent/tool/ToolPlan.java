package com.example.ragagent.tool;

/**
 * Tool 선택 결과(§6.33 3단계) — 이 턴에서 모델이 고른 Tool 하나와 그 인자. <b>고르지 않았거나 읽지 못했으면 계획 자체가 없다</b>
 * ({@code null}, 그 턴은 늘 하던 {@code RETRIEVAL}) — "Tool 없음"을 잡담으로 읽지 않는다(설계서 D3).
 *
 * @param toolName      {@link ToolCatalog} 에 있는 이름
 * @param argumentsJson 스키마 검증을 통과한 인자 JSON 객체
 * @param confirmed     사용자가 되묻기에 "네" 라고 답해 <b>확정된</b> 계획인가 — 모델이 고른 것이 아니라 {@link PendingChangeConfirmations} 가 만든다.
 *                      유지보수 Tool 은 확정된 계획만 실제로 시작한다(처음 고른 계획은 되묻기만 한다)
 */
public record ToolPlan(String toolName, String argumentsJson, boolean confirmed) {

    /** 모델이 고른 계획 — 확정되지 않았다. */
    public ToolPlan(String toolName, String argumentsJson) {
        this(toolName, argumentsJson, false);
    }

    /** 검색 Tool — 이 Tool 을 고르면 그래프는 새 경로를 타지 않고 기존 {@code RETRIEVAL}(검색 루프)로 간다(설계서 D1). */
    public static final String SEARCH_TOOL = "searchKnowledge";

    /**
     * 만료된 되묻기에 "네" 라고 답한 턴의 계획 이름 — Tool 이 아니라 표지다. {@link ToolDispatchService} 가 아무것도 부르지 않고 만료 안내만 답으로 남긴다.
     * 확정 계획({@code confirmed=true})으로 만드는 것은 그래프가 meta 분류보다 앞서 {@code TOOL} 로 보내게 하려는 것이다("네" 는 잡담으로 분류되기 쉽다).
     */
    public static final String EXPIRED_CONFIRMATION = "__expiredChangeConfirmation";

    /** 만료 안내 계획 — 인자는 만료된 요청을 알리는 데만 쓴다(관리자 화면의 되묻기 대기에서 온 값이지 모델이 만든 값이 아니다). */
    public static ToolPlan expiredConfirmation(String argumentsJson) {
        return new ToolPlan(EXPIRED_CONFIRMATION, argumentsJson, true);
    }

    /** 만료 안내 계획인가. */
    public boolean isExpiredConfirmation() {
        return EXPIRED_CONFIRMATION.equals(toolName);
    }

    /** 그래프가 {@code TOOL} 노드로 보내야 하는가 — 검색 Tool 이 아닌 Tool 을 골랐을 때만. */
    public boolean routesToToolNode() {
        return !SEARCH_TOOL.equals(toolName);
    }
}
