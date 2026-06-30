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
        @JsonProperty("selected_tags") List<String> selectedTags
) {
    public ChatRequest {
        if (version == null || version.isBlank()) version = "latest";
        if (threadId == null || threadId.isBlank()) threadId = "default";
        if (routingMode == null) routingMode = RoutingMode.COST_FIRST;
        selectedTags = selectedTags == null ? List.of() : List.copyOf(selectedTags);
    }

    public ChatRequest(String question, String version, String threadId, RoutingMode routingMode) {
        this(question, version, threadId, routingMode, false, List.of());
    }

    public ChatRequest(String question, String version, String threadId, RoutingMode routingMode, boolean directMode) {
        this(question, version, threadId, routingMode, directMode, List.of());
    }
}
