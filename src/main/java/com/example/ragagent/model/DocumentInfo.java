package com.example.ragagent.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record DocumentInfo(
        @JsonProperty("doc_id") String docId,
        String filename,
        /** Cosmetic alias override (§ 표시 이름) — null/blank when unset. See {@link
         *  com.example.ragagent.ingestion.DocRegistry.DocRegistryEntry#displayName}. */
        @JsonProperty("display_name") String displayName,
        String version,
        int chunks,
        @JsonProperty("indexed_at") String indexedAt,
        String sha256,
        List<String> tags,
        List<String> errors
) {
    /** Legacy 8-arg form — no display-name override. Kept so existing call sites and tests
     *  don't have to state a value most documents don't have. */
    public DocumentInfo(String docId, String filename, String version, int chunks,
                         String indexedAt, String sha256, List<String> tags, List<String> errors) {
        this(docId, filename, null, version, chunks, indexedAt, sha256, tags, errors);
    }

    /** The name to show the user: the override if set, else the real filename. */
    public String displayLabel() {
        return (displayName != null && !displayName.isBlank()) ? displayName : filename;
    }
}
