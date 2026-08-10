package com.example.ragagent.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SourceRef(
        String label,
        String preview,
        @JsonProperty("chunk_id") String chunkId,
        @JsonProperty("doc_id") String docId,
        @JsonProperty("page_or_slide") Object pageOrSlide
) {}
