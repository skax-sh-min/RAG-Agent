package com.example.ragagent.model;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Tag normalization, validation, and matching for the tag-based search scope (Step 5.9).
 *
 * <p>Policy: lowercase + trim + dedupe + drop blanks; at most {@link #MAX_TAGS} tags,
 * each at most {@link #MAX_TAG_LEN} chars. {@link #normalize} throws on policy violation
 * (caller maps to HTTP 400). Matching is strict AND: a chunk passes only if it carries
 * every selected tag.
 */
public final class TagUtils {

    public static final int MAX_TAGS = 10;
    public static final int MAX_TAG_LEN = 32;

    private TagUtils() {}

    /** Lowercase/trim/dedupe/drop-blanks; throws {@link IllegalArgumentException} on policy violation. */
    public static List<String> normalize(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String t : raw) {
            if (t == null) continue;
            String s = t.strip().toLowerCase(Locale.ROOT);
            if (s.isEmpty()) continue;
            if (s.length() > MAX_TAG_LEN) {
                throw new IllegalArgumentException(
                        "태그는 최대 " + MAX_TAG_LEN + "자입니다 (위반: '" + s + "')");
            }
            out.add(s);
        }
        if (out.size() > MAX_TAGS) {
            throw new IllegalArgumentException(
                    "태그는 최대 " + MAX_TAGS + "개까지 가능합니다 (현재 " + out.size() + ")");
        }
        return List.copyOf(out);
    }

    /** Parse a comma-separated string into a normalized tag list. */
    public static List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return normalize(Arrays.asList(csv.split(",")));
    }

    /** Comma-joined storage form for chunk metadata (matches the {@code image_paths} convention). */
    public static String toMetaValue(List<String> tags) {
        return tags == null ? "" : String.join(",", normalizeLenient(tags));
    }

    /**
     * Defensive read of a {@code tags} metadata value. Backends/serializers may return it as a
     * comma-joined {@code String}, a JSON-array string {@code [a,b]}, or a {@code Collection}.
     * Never throws — used on the read/filter path.
     */
    public static List<String> parseTagList(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof Collection<?> c) {
            return c.stream()
                    .filter(Objects::nonNull)
                    .map(o -> o.toString().strip().toLowerCase(Locale.ROOT))
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .toList();
        }
        String s = raw.toString().strip();
        if (s.isEmpty()) return List.of();
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length() - 1);
        return Arrays.stream(s.split(","))
                .map(t -> t.strip().replace("\"", "").toLowerCase(Locale.ROOT))
                .filter(t -> !t.isEmpty())
                .distinct()
                .toList();
    }

    /** Strict AND: returns true when {@code chunkTags} contains every {@code selected} tag. */
    public static boolean matchesAnd(List<String> chunkTags, List<String> selected) {
        if (selected == null || selected.isEmpty()) return true;
        if (chunkTags == null || chunkTags.isEmpty()) return false;
        return new HashSet<>(chunkTags).containsAll(selected);
    }

    /** normalize without throwing — for storage of already-validated tags. */
    private static List<String> normalizeLenient(List<String> raw) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String t : raw) {
            if (t == null) continue;
            String s = t.strip().toLowerCase(Locale.ROOT);
            if (!s.isEmpty()) out.add(s);
        }
        return List.copyOf(out);
    }
}
