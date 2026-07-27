package com.example.ragagent.model;

import java.util.List;

public record ChatForm(
        String question,
        String threadId,
        String version,
        String routingMode,
        Boolean directMode,
        String tags,
        String responseMode
) {
    public boolean isDirectMode() {
        return Boolean.TRUE.equals(directMode);
    }

    /** Lenient parse of the comma-separated tag input (never throws — empty on blank/invalid). */
    public List<String> selectedTags() {
        return TagUtils.parseTagList(tags);
    }

    /** Lenient parse of the S/M/L selector (never throws — {@link ResponseMode#DEFAULT} on blank/unknown). */
    public ResponseMode responseModeOrDefault() {
        return ResponseMode.parse(responseMode);
    }
}
