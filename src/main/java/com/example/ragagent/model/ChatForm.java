package com.example.ragagent.model;

import java.util.List;

public record ChatForm(
        String question,
        String threadId,
        String version,
        String routingMode,
        Boolean directMode,
        String tags          // Step 5.9: 쉼표 구분 태그 (검색 스코프)
) {
    public boolean isDirectMode() {
        return Boolean.TRUE.equals(directMode);
    }

    /** Lenient parse of the comma-separated tag input (never throws — empty on blank/invalid). */
    public List<String> selectedTags() {
        return TagUtils.parseTagList(tags);
    }
}
