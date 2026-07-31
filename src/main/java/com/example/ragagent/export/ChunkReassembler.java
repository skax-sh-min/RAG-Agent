package com.example.ragagent.export;

import com.example.ragagent.ingestion.ChunkSplitter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rebuilds a readable document from the indexed chunks of one document, undoing the
 * search-oriented artifacts {@link ChunkSplitter} deliberately introduces. Pure (no Spring deps)
 * so the whole dedup contract is unit-testable.
 *
 * <p>Chunks are optimized for retrieval, not reading: each one is made self-describing, which
 * means the same heading or the same sentence can appear in two neighbouring chunks. Naively
 * concatenating them produces duplicated subheadings and repeated text. This class strips each
 * artifact in the <em>reverse</em> of the order {@code ChunkSplitter} applies them:
 *
 * <ol>
 *   <li>{@code prependParentBreadcrumb} — a parent heading line copied onto a child chapter's
 *       first piece ({@link #stripParentBreadcrumb});</li>
 *   <li>{@code reinjectHeadingForSplitPieces} — {@code "## 소제목 (2)"} re-injected on pieces 2+
 *       ({@link #stripReinjectedHeading});</li>
 *   <li>{@code reopenCodeFence} — {@link ChunkSplitter#CODE_CONTINUATION_BEFORE}/{@code _AFTER}
 *       markers plus the re-opened fence line ({@link #stripLeadingCodeContinuation} /
 *       {@link #stripTrailingCodeContinuation}), which rejoins the fence exactly;</li>
 *   <li>{@code reinjectTableHeader} — the table header+separator rows copied onto a continuation
 *       piece ({@link #stripReinjectedTableHeader});</li>
 *   <li>the sliding window's {@code CHUNK_OVERLAP} character overlap ({@link #stripOverlap}).</li>
 * </ol>
 *
 * <p>Steps 1–4 are exact: each removes a syntactically recognizable marker and (for the two
 * heading cases) only when that heading was already emitted earlier, so a legitimately repeated
 * heading is never dropped. Step 5 is the one heuristic — it finds the longest suffix of the text
 * so far that is also a prefix of the next chunk. {@link #MIN_OVERLAP_CHARS} guards against a
 * coincidental short match; a genuine overlap is far longer than that in practice.
 */
public final class ChunkReassembler {

    /** Shortest suffix/prefix match accepted as a real sliding-window overlap (not a coincidence). */
    static final int MIN_OVERLAP_CHARS = 16;

    /** How far past {@code overlap} to search — boundary snapping (newline/fence/table) can push the
     *  real overlap somewhat past the nominal value, so the window is deliberately generous. */
    private static final int OVERLAP_SEARCH_FACTOR = 3;

    /** {@code "## 소제목 (2)"} — a heading re-injected onto sliding-window piece N. */
    private static final Pattern REINJECTED_HEADING =
            Pattern.compile("^(#{1,6})\\s+(.*?)\\s+\\((\\d+)\\)\\s*$");

    /** Any ATX heading line. */
    private static final Pattern HEADING_LINE = Pattern.compile("^(#{1,6})\\s+(.*\\S)\\s*$");

    /** A markdown table separator row, e.g. {@code | --- | :--: |}. */
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\|[\\s:|-]+\\|?\\s*$");

    private ChunkReassembler() {}

    /**
     * @param chunkTexts chunk texts in document order (metadata {@code chunk_index} order)
     * @param overlap    the {@code CHUNK_OVERLAP} the document was indexed with — only sizes the
     *                   overlap search window, so a stale value degrades gracefully
     * @return reassembled markdown; blank when there are no usable chunks
     */
    public static String reassemble(List<String> chunkTexts, int overlap) {
        if (chunkTexts == null || chunkTexts.isEmpty()) return "";

        int window = Math.max(overlap, 0) * OVERLAP_SEARCH_FACTOR;
        StringBuilder acc = new StringBuilder();
        Set<String> seenHeadings = new HashSet<>();
        boolean first = true;

        for (String raw : chunkTexts) {
            if (raw == null || raw.isBlank()) continue;
            String cur = stripTrailingCodeContinuation(raw.strip());

            boolean overlapJoined = false;
            if (!first) {
                cur = stripParentBreadcrumb(cur, seenHeadings);
                cur = stripReinjectedHeading(cur, seenHeadings);
                cur = stripLeadingCodeContinuation(cur);
                cur = stripReinjectedTableHeader(cur, acc);
                String beforeOverlap = cur;
                cur = stripOverlap(acc, cur, window);
                overlapJoined = cur.length() != beforeOverlap.length();
            }

            cur = cur.strip();
            if (cur.isBlank()) continue;
            recordHeadings(cur, seenHeadings);

            if (!first) {
                // An overlap-joined seam continues mid-section (the window cut just after a
                // newline), so a single newline reproduces the original break. Everything else is
                // a section boundary and gets a blank line. postProcess() downstream repairs the
                // blank lines block constructs (headings/tables/fences) additionally need.
                acc.append(overlapJoined ? "\n" : "\n\n");
            }
            acc.append(cur);
            first = false;
        }
        return cleanupResidual(acc.toString());
    }

    /**
     * Document-wide second pass for artifacts the per-seam strippers above structurally cannot see.
     * {@code backwardMergeShortChunks}/{@code mergeTinyChunks} concatenate pieces <em>before</em>
     * chunks are stored, so a continuation marker or a parent breadcrumb can end up in the middle
     * of a stored chunk rather than at its edge. Rules are the same as the seam strippers — a
     * heading is only dropped when an identical one was already emitted — so this pass can only
     * remove genuine duplicates, never author content.
     */
    static String cleanupResidual(String doc) {
        if (doc == null || doc.isBlank()) return "";

        List<String> lines = lines(doc);
        List<String> out = new ArrayList<>(lines.size());
        Set<String> seen = new HashSet<>();
        boolean awaitingReopenedFence = false;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String t = line.strip();

            // ── code fence cut across a merge: drop "``` + AFTER" … "BEFORE + ```lang" ──────────
            if (ChunkSplitter.CODE_CONTINUATION_AFTER.equals(t)) {
                dropTrailingFence(out);
                awaitingReopenedFence = true;
                continue;
            }
            if (awaitingReopenedFence) {
                if (t.isBlank() || ChunkSplitter.CODE_CONTINUATION_BEFORE.equals(t)) continue;
                if (t.startsWith("```")) {           // the re-opened fence — the pair is now rejoined
                    awaitingReopenedFence = false;
                    continue;
                }
                awaitingReopenedFence = false;       // fence never came; fall through and keep the line
            }
            if (ChunkSplitter.CODE_CONTINUATION_BEFORE.equals(t)) {
                awaitingReopenedFence = true;
                continue;
            }

            // ── duplicated headings ────────────────────────────────────────────────────────────
            Matcher heading = HEADING_LINE.matcher(t);
            if (heading.matches()) {
                Matcher reinjected = REINJECTED_HEADING.matcher(t);
                if (reinjected.matches()
                        && seen.contains(normalizeHeading(reinjected.group(1), reinjected.group(2)))) {
                    continue;
                }
                String key = normalizeHeading(heading.group(1), heading.group(2));
                if (seen.contains(key) && followedByDeeperHeading(lines, i, heading.group(1).length())) {
                    continue;                        // parent breadcrumb copied onto a child chapter
                }
                seen.add(key);
            }
            out.add(line);
        }
        return String.join("\n", out).strip();
    }

    /** True when the next non-blank line is a heading strictly deeper than {@code level}. */
    private static boolean followedByDeeperHeading(List<String> lines, int from, int level) {
        int next = nextNonBlank(lines, from + 1);
        if (next < 0) return false;
        Matcher m = HEADING_LINE.matcher(lines.get(next).strip());
        return m.matches() && m.group(1).length() > level;
    }

    /** Pops trailing blank lines and a closing {@code ```} fence, if present, from {@code out}. */
    private static void dropTrailingFence(List<String> out) {
        while (!out.isEmpty() && out.get(out.size() - 1).isBlank()) out.remove(out.size() - 1);
        if (!out.isEmpty() && out.get(out.size() - 1).strip().equals("```")) out.remove(out.size() - 1);
    }

    // ── individual artifact strippers (package-private for focused unit tests) ────────────────

    /**
     * Drops a leading parent-chapter breadcrumb: a heading line immediately followed by a
     * <em>deeper</em> heading, where the shallower one was already emitted. Both conditions must
     * hold, so a chapter that genuinely opens with its own heading is never touched.
     */
    static String stripParentBreadcrumb(String chunk, Set<String> seenHeadings) {
        List<String> lines = lines(chunk);
        int firstIdx = nextNonBlank(lines, 0);
        if (firstIdx < 0) return chunk;
        Matcher first = HEADING_LINE.matcher(lines.get(firstIdx).strip());
        if (!first.matches()) return chunk;

        int secondIdx = nextNonBlank(lines, firstIdx + 1);
        if (secondIdx < 0) return chunk;
        Matcher second = HEADING_LINE.matcher(lines.get(secondIdx).strip());
        if (!second.matches()) return chunk;

        if (second.group(1).length() <= first.group(1).length()) return chunk; // not a parent→child pair
        if (!seenHeadings.contains(normalizeHeading(first.group(1), first.group(2)))) return chunk;

        return joinFrom(lines, firstIdx + 1);
    }

    /**
     * Drops a leading {@code "## 소제목 (2)"} re-injected heading, but only when the un-suffixed
     * {@code "## 소제목"} was already emitted — otherwise the {@code (2)} is the author's own text.
     */
    static String stripReinjectedHeading(String chunk, Set<String> seenHeadings) {
        List<String> lines = lines(chunk);
        int idx = nextNonBlank(lines, 0);
        if (idx < 0) return chunk;

        Matcher m = REINJECTED_HEADING.matcher(lines.get(idx).strip());
        if (!m.matches()) return chunk;
        if (!seenHeadings.contains(normalizeHeading(m.group(1), m.group(2)))) return chunk;

        return joinFrom(lines, idx + 1);
    }

    /**
     * Drops the {@code CODE_CONTINUATION_BEFORE} marker and the re-opened fence line that follows
     * it, so the fence rejoins the one {@link #stripTrailingCodeContinuation} left open.
     */
    static String stripLeadingCodeContinuation(String chunk) {
        List<String> lines = lines(chunk);
        int idx = nextNonBlank(lines, 0);
        if (idx < 0 || !ChunkSplitter.CODE_CONTINUATION_BEFORE.equals(lines.get(idx).strip())) return chunk;

        int next = nextNonBlank(lines, idx + 1);
        // The marker is always emitted with the opening fence right behind it; drop both.
        int cut = (next >= 0 && lines.get(next).strip().startsWith("```")) ? next + 1 : idx + 1;
        return joinFrom(lines, cut);
    }

    /** Drops the trailing synthetic {@code ```} + {@code CODE_CONTINUATION_AFTER} pair. */
    static String stripTrailingCodeContinuation(String chunk) {
        List<String> lines = lines(chunk);
        int idx = prevNonBlank(lines, lines.size() - 1);
        if (idx < 0 || !ChunkSplitter.CODE_CONTINUATION_AFTER.equals(lines.get(idx).strip())) return chunk;

        int fence = prevNonBlank(lines, idx - 1);
        int cut = (fence >= 0 && lines.get(fence).strip().equals("```")) ? fence : idx;
        return joinUntil(lines, cut);
    }

    /**
     * Drops a table header row (plus its separator row) re-injected onto a continuation piece —
     * recognized by the header line already appearing in the text emitted so far.
     */
    static String stripReinjectedTableHeader(String chunk, CharSequence acc) {
        List<String> lines = lines(chunk);
        int idx = nextNonBlank(lines, 0);
        if (idx < 0) return chunk;

        String header = lines.get(idx).strip();
        if (!header.startsWith("|") || !acc.toString().contains(header)) return chunk;

        int sep = nextNonBlank(lines, idx + 1);
        if (sep < 0 || !TABLE_SEPARATOR.matcher(lines.get(sep).strip()).matches()) return chunk;

        return joinFrom(lines, sep + 1);
    }

    /**
     * Removes the sliding-window overlap: the longest prefix of {@code chunk} (≥
     * {@link #MIN_OVERLAP_CHARS}) that the accumulated text already ends with. Trailing whitespace
     * is ignored on both sides because {@code ChunkSplitter} strips every emitted piece, so the
     * seam's original whitespace survives on neither side.
     */
    static String stripOverlap(CharSequence acc, String chunk, int window) {
        if (window < MIN_OVERLAP_CHARS || acc.length() == 0 || chunk.isEmpty()) return chunk;

        String tail = acc.subSequence(Math.max(0, acc.length() - window), acc.length()).toString().stripTrailing();
        int max = Math.min(window, Math.min(chunk.length(), tail.length()));

        for (int k = max; k >= MIN_OVERLAP_CHARS; k--) {
            String candidate = chunk.substring(0, k).stripTrailing();
            if (candidate.length() >= MIN_OVERLAP_CHARS && tail.endsWith(candidate)) {
                return chunk.substring(k).stripLeading();
            }
        }
        return chunk;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private static void recordHeadings(String text, Set<String> seen) {
        for (String line : lines(text)) {
            Matcher m = HEADING_LINE.matcher(line.strip());
            if (m.matches()) seen.add(normalizeHeading(m.group(1), m.group(2)));
        }
    }

    /** Heading identity = level + text, whitespace-collapsed (formatting drift shouldn't matter). */
    private static String normalizeHeading(String marker, String text) {
        return marker + " " + text.strip().replaceAll("\\s+", " ");
    }

    private static List<String> lines(String text) {
        return new ArrayList<>(List.of(text.split("\n", -1)));
    }

    private static int nextNonBlank(List<String> lines, int from) {
        for (int i = Math.max(from, 0); i < lines.size(); i++) {
            if (!lines.get(i).isBlank()) return i;
        }
        return -1;
    }

    private static int prevNonBlank(List<String> lines, int from) {
        for (int i = Math.min(from, lines.size() - 1); i >= 0; i--) {
            if (!lines.get(i).isBlank()) return i;
        }
        return -1;
    }

    private static String joinFrom(List<String> lines, int from) {
        return from >= lines.size() ? "" : String.join("\n", lines.subList(from, lines.size())).strip();
    }

    private static String joinUntil(List<String> lines, int toExclusive) {
        return toExclusive <= 0 ? "" : String.join("\n", lines.subList(0, toExclusive)).strip();
    }
}
