package com.example.ragagent.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record DocumentInfo(
        @JsonProperty("doc_id") String docId,
        String filename,
        String version,
        int chunks,
        @JsonProperty("indexed_at") String indexedAt,
        String sha256,
        List<String> tags,
        List<String> errors
) {}
