package com.example.ragagent.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits loaded documents into chunks: section-aware merge/split for structured
 * formats (MD/DOCX/TXT), plain sliding window otherwise. Pure text logic — no
 * external dependencies, so every helper below is independently unit-testable.
 */
@Component
public class ChunkSplitter {

    private static final Logger log = LoggerFactory.getLogger(ChunkSplitter.class);

    public List<Document> splitDocuments(List<Document> docs, String filename,
                                          int chunkSize, int overlap, int minChunkSize) {
        return splitDocuments(docs, filename, chunkSize, overlap, minChunkSize, 0);
    }

    /**
     * @param maxChunkChars hard per-chunk character ceiling (0 = disabled). Applied as a final
     *                      safety pass after all semantic splitting/merging so no emitted chunk
     *                      exceeds the embedding server's batch/token limit — see
     *                      {@code app.embedding.max-chunk-chars}.
     */
    public List<Document> splitDocuments(List<Document> docs, String filename,
                                          int chunkSize, int overlap, int minChunkSize, int maxChunkChars) {
        String lower = filename.toLowerCase();

        if (lower.endsWith(".pptx")) {
            log.debug("[SPLIT] {} → 슬라이드 유지 (분할 없음), {}개", filename, docs.size());
            return enforceMaxChars(new ArrayList<>(docs), maxChunkChars, filename);
        }

        // .txt is converted to structured MD before this point, so it splits section-wise too.
        if (lower.endsWith(".md") || lower.endsWith(".docx") || lower.endsWith(".txt")) {
            List<Document> sectionMerged = mergeShortSections(docs, chunkSize);
            List<Document> result = new ArrayList<>();
            for (Document doc : sectionMerged) {
                if (doc.getText() == null || doc.getText().isBlank()) continue;
                if (doc.getText().length() <= chunkSize) {
                    result.add(doc);
                } else {
                    log.debug("[SPLIT] 섹션 {}자 > chunkSize={}, 슬라이딩 윈도우 적용",
                            doc.getText().length(), chunkSize);
                    List<Document> pieces = slidingWindow(doc, chunkSize, overlap, minChunkSize);
                    result.addAll(reinjectHeadingForSplitPieces(doc.getText(), pieces));
                }
            }
            log.debug("[SPLIT] {} → 섹션 분할 전략, {}섹션 → 병합 {}섹션 → {}청크",
                    filename, docs.size(), sectionMerged.size(), result.size());
            return enforceMaxChars(result, maxChunkChars, filename);
        }

        List<Document> result = new ArrayList<>();
        for (Document doc : docs) {
            result.addAll(slidingWindow(doc, chunkSize, overlap, minChunkSize));
        }
        log.debug("[SPLIT] {} → 슬라이딩 윈도우 전략, {}섹션 → {}청크", filename, docs.size(), result.size());
        return enforceMaxChars(result, maxChunkChars, filename);
    }

    /**
     * Final safety net: guarantees no emitted chunk exceeds {@code maxChars} characters, force-
     * splitting oversized ones at line boundaries (and hard-cutting any single line longer than the
     * limit). Runs after heading reinjection / tiny-chunk merges — all of which can push a chunk
     * past the target size — so it is the last word on chunk size. No-op when {@code maxChars <= 0}.
     */
    List<Document> enforceMaxChars(List<Document> docs, int maxChars, String filename) {
        if (maxChars <= 0) return docs;

        List<Document> out = new ArrayList<>(docs.size());
        int split = 0;
        for (Document doc : docs) {
            String text = doc.getText();
            if (text == null || text.length() <= maxChars) {
                out.add(doc);
                continue;
            }
            split++;
            for (String piece : hardSplitByLines(text, maxChars)) {
                out.add(new Document(piece, new HashMap<>(doc.getMetadata())));
            }
        }
        if (split > 0) {
            log.debug("[SPLIT] {} → 임베딩 한계({}자) 초과 청크 {}개 강제 재분할 → 총 {}청크",
                    filename, maxChars, split, out.size());
        }
        return out;
    }

    /** Greedily packs whole lines up to {@code maxChars}; a single over-long line is char-cut. */
    List<String> hardSplitByLines(String text, int maxChars) {
        List<String> pieces = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (line.length() > maxChars) {
                if (cur.length() > 0) {
                    addIfNotBlank(pieces, cur.toString());
                    cur.setLength(0);
                }
                for (int i = 0; i < line.length(); i += maxChars) {
                    addIfNotBlank(pieces, line.substring(i, Math.min(i + maxChars, line.length())));
                }
                continue;
            }
            int addition = (cur.length() == 0 ? 0 : 1) + line.length(); // +1 for the joining '\n'
            if (cur.length() > 0 && cur.length() + addition > maxChars) {
                addIfNotBlank(pieces, cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append('\n');
            cur.append(line);
        }
        if (cur.length() > 0) addIfNotBlank(pieces, cur.toString());
        return pieces;
    }

    private static void addIfNotBlank(List<String> pieces, String s) {
        String stripped = s.strip();
        if (!stripped.isBlank()) pieces.add(stripped);
    }

    List<Document> mergeShortSections(List<Document> docs, int chunkSize) {
        if (docs == null || docs.isEmpty()) return List.of();

        final int threshold40 = (int) Math.floor(chunkSize * 0.40);
        final int threshold75 = (int) Math.floor(chunkSize * 0.75);

        List<Document> merged = new ArrayList<>();
        int i = 0;
        while (i < docs.size()) {
            Document base = docs.get(i);
            StringBuilder acc = new StringBuilder(base.getText() == null ? "" : base.getText());
            Map<String, Object> metadata = new HashMap<>(base.getMetadata());

            int currentHeadingLevel = sectionHeadingLevel(base);
            int j = i;

            while (j + 1 < docs.size()) {
                Document next = docs.get(j + 1);
                String nextText = next.getText() == null ? "" : next.getText();
                if (nextText.isBlank()) {
                    j++;
                    continue;
                }

                int nextHeadingLevel = sectionHeadingLevel(next);
                if (isMergeForbiddenByHeadingJump(currentHeadingLevel, nextHeadingLevel)) {
                    break;
                }

                int currentLen = acc.length();
                int combinedLen = currentLen + 2 + nextText.length();

                boolean includeNext = false;
                if (currentLen < threshold40) {
                    includeNext = true;
                } else if (currentLen < threshold75 && combinedLen < chunkSize) {
                    includeNext = true;
                }

                if (!includeNext) break;

                if (acc.length() > 0) acc.append("\n\n");
                acc.append(nextText);
                j++;
                currentHeadingLevel = nextHeadingLevel > 0 ? nextHeadingLevel : currentHeadingLevel;
            }

            merged.add(new Document(acc.toString(), metadata));
            i = j + 1;
        }

        return merged;
    }

    boolean isMergeForbiddenByHeadingJump(int currentHeadingLevel, int nextHeadingLevel) {
        if (currentHeadingLevel <= 0 || nextHeadingLevel <= 0) return false;
        return (currentHeadingLevel - nextHeadingLevel) >= 2;
    }

    int sectionHeadingLevel(Document doc) {
        HeadingInfo heading = extractLeadingHeading(doc.getText());
        return heading == null ? 0 : heading.marker().length();
    }

    /**
     * When a section's markdown heading (e.g. {@code "## 소제목"}) survives only in the first
     * sliding-window piece, later pieces lose all indication of which section they belong to.
     * Re-injects the same heading — suffixed with its piece number, e.g. {@code "## 소제목 (2)"} —
     * at the top of every piece after the first, so each embedded chunk is self-describing.
     * No-op when the section has no leading heading, or when it wasn't split into multiple pieces.
     */
    List<Document> reinjectHeadingForSplitPieces(String originalSectionText, List<Document> pieces) {
        if (pieces.size() <= 1) return pieces;

        HeadingInfo heading = extractLeadingHeading(originalSectionText);
        if (heading == null) return pieces;

        List<Document> result = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            Document piece = pieces.get(i);
            if (i == 0) {
                result.add(piece);
                continue;
            }
            String marker = heading.marker() + " " + heading.text() + " (" + i + ")";
            result.add(new Document(marker + "\n\n" + piece.getText(), new HashMap<>(piece.getMetadata())));
        }
        return result;
    }

    /**
     * Extracts the {@code #}-marker and text of the section's leading heading line, if any.
     * Only a valid ATX heading (1–6 {@code #} followed by a space) at the very start of the
     * section counts. Anything else — a code fence ({@code ```}), a table row ({@code |}),
     * {@code #######}+ over-long markers, or a bare {@code #comment} without a space — yields
     * {@code null}, so heading reinjection never fires on code comments or table fragments.
     */
    HeadingInfo extractLeadingHeading(String text) {
        if (text == null || text.isBlank()) return null;

        String[] lines = text.split("\\n", -1);
        for (String line : lines) {
            String trimmed = line.stripLeading();
            if (trimmed.isBlank()) continue;
            if (!trimmed.startsWith("#")) return null; // fences (```), table rows (|), body text

            int level = 0;
            while (level < trimmed.length() && trimmed.charAt(level) == '#') {
                level++;
            }
            if (level >= 1 && level <= 6 && level < trimmed.length() && trimmed.charAt(level) == ' ') {
                return new HeadingInfo("#".repeat(level), trimmed.substring(level + 1).strip());
            }
            return null;
        }
        return null;
    }

    record HeadingInfo(String marker, String text) {
    }

    List<Document> slidingWindow(Document doc, int chunkSize, int overlap, int minChunkSize) {
        List<Document> result = new ArrayList<>();
        List<String> rawChunks = new ArrayList<>();
        String text = doc.getText();
        if (text == null || text.isBlank()) return result;
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            if (end < text.length()) {
                int lastNl = text.lastIndexOf('\n', end);
                if (lastNl > start + overlap) end = lastNl + 1;

                end = adjustEndForCodeBlock(text, start, end, overlap);
                end = adjustEndForTableBlock(text, start, end, overlap);

                // 남은 꼬리 길이가 overlap 이하이면 새 청크를 만들지 않고 현재 청크에 포함한다.
                int remaining = text.length() - end;
                if (remaining <= overlap) end = text.length();

                // 경계 보정으로 end가 start와 같아진 경우 무한 루프를 방지한다.
                if (end <= start) end = Math.min(start + chunkSize, text.length());
            }
            String chunk = text.substring(start, end).strip();
            if (!chunk.isBlank()) {
                rawChunks.add(reopenTruncatedBlock(text, start, end, chunk));
            }
            if (end >= text.length()) break;
            start = Math.max(start + 1, end - overlap);
        }

        for (String merged : mergeTinyChunks(rawChunks, minChunkSize)) {
            result.add(new Document(merged, new HashMap<>(doc.getMetadata())));
        }
        return result;
    }

    /**
     * When a sliding-window boundary falls inside a table or fenced code block too large to keep
     * whole (see {@link #adjustEndForCodeBlock}/{@link #adjustEndForTableBlock}, which only snap
     * boundaries when doing so is cheap), the piece starting at {@code start} continues mid-block
     * with no header row / opening fence — it reads as a headerless data row or a bare code
     * fragment with no language context. Detects that case and re-wraps the piece so it is
     * self-contained: table pieces get the original header+separator row prepended; code pieces
     * get the original opening fence line prepended, plus a closing fence appended if the block
     * doesn't close within this piece. No-op when {@code start} isn't a continuation of either.
     */
    String reopenTruncatedBlock(String fullText, int start, int end, String chunk) {
        Range codeRange = findFencedCodeRangeContaining(fullText, start + 1);
        if (codeRange != null && codeRange.start() < start) {
            return reopenCodeFence(fullText, codeRange, end, chunk);
        }
        Range tableRange = findTableRangeContaining(fullText, start + 1);
        if (tableRange != null && tableRange.start() < start) {
            return reinjectTableHeader(fullText, tableRange, chunk);
        }
        return chunk;
    }

    String reopenCodeFence(String fullText, Range codeRange, int chunkEnd, String chunk) {
        int fenceLineEnd = fullText.indexOf('\n', codeRange.start());
        if (fenceLineEnd == -1) fenceLineEnd = fullText.length();
        String openingFenceLine = fullText.substring(codeRange.start(), fenceLineEnd).strip();

        StringBuilder sb = new StringBuilder(openingFenceLine).append('\n').append(chunk);
        if (chunkEnd < codeRange.end()) {
            sb.append("\n```"); // block doesn't close within this piece — close it so it stays valid
        }
        return sb.toString();
    }

    String reinjectTableHeader(String fullText, Range tableRange, String chunk) {
        int headerLineEnd = findLineEndExclusive(fullText, tableRange.start());
        String headerBlock = fullText.substring(tableRange.start(), headerLineEnd);
        if (isTableLine(fullText, headerLineEnd)) {
            int sepLineEnd = findLineEndExclusive(fullText, headerLineEnd);
            headerBlock += fullText.substring(headerLineEnd, sepLineEnd);
        }
        headerBlock = headerBlock.stripTrailing();
        return headerBlock.isBlank() ? chunk : headerBlock + "\n" + chunk;
    }

    List<String> mergeTinyChunks(List<String> chunks, int minLength) {
        if (chunks == null || chunks.isEmpty()) return List.of();

        List<String> merged = new ArrayList<>();
        String pendingPrefix = "";

        for (String original : chunks) {
            String text = original == null ? "" : original.strip();
            if (text.isBlank()) continue;

            if (!pendingPrefix.isEmpty()) {
                text = mergeAdjacentText(pendingPrefix, text, minLength);
                pendingPrefix = "";
            }

            if (text.length() < minLength) {
                if (!merged.isEmpty()) {
                    int last = merged.size() - 1;
                    merged.set(last, mergeAdjacentText(merged.get(last), text, minLength));
                } else {
                    pendingPrefix = text;
                }
                continue;
            }

            merged.add(text);
        }

        if (!pendingPrefix.isEmpty()) {
            if (!merged.isEmpty()) {
                merged.set(0, mergeAdjacentText(pendingPrefix, merged.get(0), minLength));
            } else {
                merged.add(pendingPrefix);
            }
        }

        return merged;
    }

    String mergeAdjacentText(String left, String right, int maxOverlap) {
        if (left == null || left.isBlank()) return right == null ? "" : right;
        if (right == null || right.isBlank()) return left;

        int limit = Math.min(Math.min(maxOverlap, left.length()), right.length());
        int overlapLen = 0;
        for (int k = limit; k >= 1; k--) {
            if (left.regionMatches(left.length() - k, right, 0, k)) {
                overlapLen = k;
                break;
            }
        }

        if (overlapLen > 0) {
            return left + right.substring(overlapLen);
        }
        return left + "\n\n" + right;
    }

    int adjustEndForCodeBlock(String text, int start, int end, int overlap) {
        Range range = findFencedCodeRangeContaining(text, end);
        if (range == null) return end;

        int remaining = range.end() - end;
        if (remaining <= overlap) {
            return range.end();
        }

        int currentCodeLen = end - range.start();
        if (currentCodeLen < overlap * 2 && range.start() > start) {
            return range.start();
        }

        return end;
    }

    int adjustEndForTableBlock(String text, int start, int end, int overlap) {
        Range range = findTableRangeContaining(text, end);
        if (range == null) return end;

        int remaining = range.end() - end;
        if (remaining <= overlap) {
            return range.end();
        }

        if (range.start() > start) {
            return range.start();
        }

        return end;
    }

    Range findFencedCodeRangeContaining(String text, int boundary) {
        if (text.isEmpty()) return null;

        int probe = Math.max(0, Math.min(boundary - 1, text.length() - 1));
        int idx = 0;
        int openFenceStart = -1;

        while (idx < text.length()) {
            int lineEnd = text.indexOf('\n', idx);
            if (lineEnd == -1) lineEnd = text.length();
            int nextLineStart = lineEnd < text.length() ? lineEnd + 1 : lineEnd;

            String line = text.substring(idx, lineEnd).stripLeading();
            if (line.startsWith("```")) {
                if (openFenceStart < 0) {
                    openFenceStart = idx;
                } else {
                    int codeEnd = nextLineStart;
                    if (probe >= openFenceStart && probe < codeEnd) {
                        return new Range(openFenceStart, codeEnd);
                    }
                    openFenceStart = -1;
                }
            }

            if (nextLineStart > probe && openFenceStart < 0) {
                return null;
            }

            idx = nextLineStart;
        }

        if (openFenceStart >= 0 && probe >= openFenceStart) {
            return new Range(openFenceStart, text.length());
        }

        return null;
    }

    Range findTableRangeContaining(String text, int boundary) {
        if (text.isEmpty()) return null;

        int probe = Math.max(0, Math.min(boundary - 1, text.length() - 1));
        int lineStart = findLineStart(text, probe);
        if (!isTableLine(text, lineStart)) return null;

        int tableStart = lineStart;
        while (tableStart > 0) {
            int prevLineEnd = tableStart - 1;
            int prevLineStart = findLineStart(text, prevLineEnd);
            if (!isTableLine(text, prevLineStart)) break;
            tableStart = prevLineStart;
        }

        int tableEnd = findLineEndExclusive(text, lineStart);
        int cursor = tableEnd;
        while (cursor < text.length()) {
            if (!isTableLine(text, cursor)) break;
            tableEnd = findLineEndExclusive(text, cursor);
            cursor = tableEnd;
        }

        return new Range(tableStart, tableEnd);
    }

    int findLineStart(String text, int pos) {
        int i = Math.max(0, Math.min(pos, text.length() - 1));
        while (i > 0 && text.charAt(i - 1) != '\n') i--;
        return i;
    }

    int findLineEndExclusive(String text, int lineStart) {
        int lineEnd = text.indexOf('\n', lineStart);
        if (lineEnd == -1) return text.length();
        return lineEnd + 1;
    }

    boolean isTableLine(String text, int lineStart) {
        int lineEnd = text.indexOf('\n', lineStart);
        if (lineEnd == -1) lineEnd = text.length();

        String line = text.substring(lineStart, lineEnd).trim();
        if (line.isEmpty() || !line.startsWith("|")) return false;

        long pipes = line.chars().filter(ch -> ch == '|').count();
        return pipes >= 2;
    }

    record Range(int start, int end) {
    }
}
