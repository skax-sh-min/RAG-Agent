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
        String lower = filename.toLowerCase();

        if (lower.endsWith(".pptx")) {
            log.debug("[SPLIT] {} → 슬라이드 유지 (분할 없음), {}개", filename, docs.size());
            return new ArrayList<>(docs);
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
                    result.addAll(slidingWindow(doc, chunkSize, overlap, minChunkSize));
                }
            }
            log.debug("[SPLIT] {} → 섹션 분할 전략, {}섹션 → 병합 {}섹션 → {}청크",
                    filename, docs.size(), sectionMerged.size(), result.size());
            return result;
        }

        List<Document> result = new ArrayList<>();
        for (Document doc : docs) {
            result.addAll(slidingWindow(doc, chunkSize, overlap, minChunkSize));
        }
        log.debug("[SPLIT] {} → 슬라이딩 윈도우 전략, {}섹션 → {}청크", filename, docs.size(), result.size());
        return result;
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
        String text = doc.getText();
        if (text == null || text.isBlank()) return 0;

        String[] lines = text.split("\\n", -1);
        for (String line : lines) {
            String trimmed = line.stripLeading();
            if (trimmed.isBlank()) continue;
            if (!trimmed.startsWith("#")) return 0;

            int level = 0;
            while (level < trimmed.length() && trimmed.charAt(level) == '#') {
                level++;
            }
            if (level > 0 && level < trimmed.length() && trimmed.charAt(level) == ' ') {
                return level;
            }
            return 0;
        }
        return 0;
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
                rawChunks.add(chunk);
            }
            if (end >= text.length()) break;
            start = Math.max(start + 1, end - overlap);
        }

        for (String merged : mergeTinyChunks(rawChunks, minChunkSize)) {
            result.add(new Document(merged, new HashMap<>(doc.getMetadata())));
        }
        return result;
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
