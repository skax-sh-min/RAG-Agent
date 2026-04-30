package com.example.ragagent.model;

import com.example.ragagent.llm.RoutingMode;
import com.fasterxml.jackson.annotation.JsonProperty;

public record ChatRequest(
        String question,
        String version,
        @JsonProperty("thread_id") String threadId,
        @JsonProperty("routing_mode") RoutingMode routingMode
) {
    public ChatRequest {
        if (version == null || version.isBlank()) version = "latest";
        if (threadId == null || threadId.isBlank()) threadId = "default";
        if (routingMode == null) routingMode = RoutingMode.COST_FIRST;
    }
}
