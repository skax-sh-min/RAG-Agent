package com.example.ragagent.model;

import java.util.regex.Pattern;

public record ThreadMeta(
        String threadId,
        String userId,
        String title,
        String version,
        String createdAt,
        String updatedAt,
        String routingMode,
        String tags
) {

    private static final Pattern LEADING_VERSION_BRACKET = Pattern.compile("^\\[[^\\]]*]\\s*");

    /**
     * {@link #title} with any leading {@code "[version]"} bracket stripped — historically the
     * version was baked into the stored title text ({@code ThreadMetaService}); the sidebar now
     * shows {@link #version} on its own line instead, so the bracket would otherwise be duplicated.
     */
    public String displayTitle() {
        return title == null ? "" : LEADING_VERSION_BRACKET.matcher(title).replaceFirst("");
    }

    /** Comma-and-space-joined tag list for display (empty string when none). */
    public String tagsDisplay() {
        return String.join(", ", TagUtils.parseTagList(tags));
    }
}
