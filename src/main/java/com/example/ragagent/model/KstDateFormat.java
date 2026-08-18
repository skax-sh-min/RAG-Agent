package com.example.ragagent.model;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Renders a stored UTC instant string ({@code Instant.now().toString()}, e.g.
 * {@code DocRegistry.DocRegistryEntry.indexedAt()}) as Korea Standard Time for display —
 * {@code documents.html} and {@code admin.html}'s document registry table both show "인덱싱 시간"
 * in KST rather than the raw UTC value. Referenced from Thymeleaf via
 * {@code T(com.example.ragagent.model.KstDateFormat).toKst(...)}; the underlying stored/returned
 * value (also the REST API's {@code indexed_at} field) is left untouched — this is display-only.
 */
public final class KstDateFormat {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private KstDateFormat() {}

    /** {@code isoInstant} formatted as {@code "yyyy-MM-dd HH:mm"} in Asia/Seoul. Returns the input
     *  unchanged when null/blank/unparseable, so a malformed value degrades to the raw string
     *  instead of breaking the page. */
    public static String toKst(String isoInstant) {
        if (isoInstant == null || isoInstant.isBlank()) return isoInstant;
        try {
            return FORMAT.withZone(KST).format(Instant.parse(isoInstant));
        } catch (Exception e) {
            return isoInstant;
        }
    }
}
