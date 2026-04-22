package com.example.ragagent.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ChatRequest(
        String question,
        String version,
        @JsonProperty("thread_id") String threadId
) {
    public ChatRequest {
        if (version == null || version.isBlank()) version = "latest";
        if (threadId == null || threadId.isBlank()) threadId = "default";
    }
}
