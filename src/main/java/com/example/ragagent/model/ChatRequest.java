package com.example.ragagent.model;

import com.example.ragagent.llm.RoutingMode;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record ChatRequest(
        String question,
        String version,
        @JsonProperty("thread_id") String threadId,
        @JsonProperty("routing_mode") RoutingMode routingMode,
        @JsonProperty("direct_mode") boolean directMode,
        @JsonProperty("selected_tags") List<String> selectedTags,
        @JsonProperty("response_mode") ResponseMode responseMode
) {
    public ChatRequest {
        if (version == null || version.isBlank()) version = "latest";
        if (threadId == null || threadId.isBlank()) threadId = "default";
        if (routingMode == null) routingMode = RoutingMode.COST_FIRST;
        if (responseMode == null) responseMode = ResponseMode.DEFAULT;
        // Direct 배타 (PLAN §6.24 Step 4-a) — 검색 결과가 전제인 모드는 검색 없는 호출과 함께
        // 성립할 수 없다. REST 는 채팅 UI 를 거치지 않으므로 클라이언트 비활성화가 존재하지 않는
        // 경로이고, 구 L 은 이 가드가 없어 손으로 만든 요청이 그대로 통과했다.
        if (directMode && !responseMode.allowsDirect()) responseMode = ResponseMode.DEFAULT;
        selectedTags = selectedTags == null ? List.of() : List.copyOf(selectedTags);
    }

    public ChatRequest(String question, String version, String threadId, RoutingMode routingMode) {
        this(question, version, threadId, routingMode, false, List.of(), ResponseMode.DEFAULT);
    }

    public ChatRequest(String question, String version, String threadId, RoutingMode routingMode, boolean directMode) {
        this(question, version, threadId, routingMode, directMode, List.of(), ResponseMode.DEFAULT);
    }

    public ChatRequest(String question, String version, String threadId, RoutingMode routingMode,
                       boolean directMode, List<String> selectedTags) {
        this(question, version, threadId, routingMode, directMode, selectedTags, ResponseMode.DEFAULT);
    }
}
