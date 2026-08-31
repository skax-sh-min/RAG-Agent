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

    /** How much of the thread id the chat sidebar shows next to the copy button. */
    private static final int SHORT_ID_LENGTH = 8;

    /**
     * Thread id shortened for display — the full value stays available for the copy button.
     *
     * <p>The template used to do this itself with {@code #strings.substring(meta.threadId, 0, 8)},
     * which threw {@code StringIndexOutOfBoundsException} and took the whole chat page down with a
     * 500 for any id shorter than 8 characters. Thread ids are normally UUIDs, but not all of them
     * are: a real deployment carried a legacy thread literally named {@code "default"} (7 chars),
     * and opening it made {@code /chat/default} unreachable rather than merely ugly.
     *
     * <p>Lives here rather than in the template for the same reason {@link #displayTitle()} does —
     * a display rule with an edge case belongs somewhere a test can reach it.
     */
    public String shortThreadId() {
        if (threadId == null) return "";
        return threadId.length() <= SHORT_ID_LENGTH ? threadId : threadId.substring(0, SHORT_ID_LENGTH);
    }
}
