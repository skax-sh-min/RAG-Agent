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
        return stripVersionPrefix(title);
    }

    /**
     * {@link #displayTitle()} for callers that hold a bare title string rather than a whole
     * {@code ThreadMeta} — the {@code /admin} 대화 목록 rows (§6.25) read the title out of an
     * aggregate query, and the two lists must strip the same prefix or the same conversation
     * reads differently in the sidebar and in the admin panel.
     */
    public static String stripVersionPrefix(String title) {
        return title == null ? "" : LEADING_VERSION_BRACKET.matcher(title).replaceFirst("");
    }

    /** Comma-and-space-joined tag list for display (empty string when none). */
    public String tagsDisplay() {
        return String.join(", ", TagUtils.parseTagList(tags));
    }
}
