package com.example.ragagent.ingestion;

import com.example.ragagent.model.MetaKey;
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
 * formats (MD/DOCX/TXT/PPTX/non-scanned PDF), plain sliding window otherwise (scanned PDF,
 * via {@code source_type=ocr}). Pure text logic — no external dependencies, so every helper
 * below is independently unit-testable.
 */
@Component
public class ChunkSplitter {

    private static final Logger log = LoggerFactory.getLogger(ChunkSplitter.class);

    /**
     * Inline markers noting that a fenced code block was cut across a chunk boundary — inserted
     * as plain text lines outside the fence (never inside it, so the code content itself stays
     * byte-for-byte reproducible). Since {@code Document.getText()} is stored/displayed verbatim
     * (source citation, {@code /admin} chunk view), these are visible there as-is, and since they
     * also flow into {@link SearchTextBuilder}'s embedding/FTS input like any other chunk text, a
     * query such as "함수 뒷부분" can still surface the neighboring chunk.
     */
    static final String CODE_CONTINUATION_BEFORE = "[코드 이어짐: 이전 청크에서 계속]";
    static final String CODE_CONTINUATION_AFTER  = "[코드 이어짐: 다음 청크로 계속]";

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

        // Two structured strategies split section-wise; scanned PDF (source_type=ocr) never goes
        // through MD conversion, so it stays on the plain sliding-window path below.
        //   chapter-structured (md/docx/txt): real author headings → minChunkSize-based chapter
        //     merge (mergeSectionsByChapter) + parent-heading breadcrumb + backward-merge.
        //   page-structured (pptx / non-scanned pdf): synthetic per-page/slide sections → legacy
        //     mergeShortSections, keeping the "1 chunk = 1 slide/page" page_or_slide guarantee.
        boolean chapterStructured = lower.endsWith(".md") || lower.endsWith(".docx") || lower.endsWith(".txt");
        boolean pageStructured = lower.endsWith(".pptx") || (lower.endsWith(".pdf") && !isOcrSourced(docs));

        if (chapterStructured) {
            return splitChapterStructured(docs, filename, chunkSize, overlap, minChunkSize, maxChunkChars);
        }

        if (pageStructured) {
            List<Document> dualHeadingMerged = mergeIdenticalHeadingSlides(docs, chunkSize);
            List<Document> sectionMerged = mergeShortSections(dualHeadingMerged, chunkSize);
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
            log.debug("[SPLIT] {} → 페이지 섹션 전략, {}섹션 → 병합 {}섹션 → {}청크",
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
     * Chapter-structured (md/docx/txt) split pipeline (see {@link #splitDocuments}):
     * <ol>
     *   <li>{@link #mergeSectionsByChapter} — forward-merge sections below {@code minChunkSize} into
     *       chapter groups (parent-heading merges forbidden), each group tracking its starting
     *       original-section index for parent lookup;</li>
     *   <li>per group: sliding-window split when over {@code chunkSize} + own-heading reinjection,
     *       then a parent-chapter breadcrumb on the first (non-tail) piece of a child chapter;</li>
     *   <li>{@link #backwardMergeShortChunks} — pull any still-tiny chunk into the previous one;</li>
     *   <li>{@link #enforceMaxChars} final ceiling.</li>
     * </ol>
     */
    private List<Document> splitChapterStructured(List<Document> docs, String filename,
                                                  int chunkSize, int overlap, int minChunkSize, int maxChunkChars) {
        List<SectionGroup> groups = mergeSectionsByChapter(docs, chunkSize, overlap, minChunkSize);

        List<Document> result = new ArrayList<>();
        for (SectionGroup group : groups) {
            Document doc = group.doc();
            if (doc.getText() == null || doc.getText().isBlank()) continue;

            List<Document> pieces;
            if (doc.getText().length() <= chunkSize) {
                pieces = List.of(doc);
            } else {
                log.debug("[SPLIT] 섹션 {}자 > chunkSize={}, 슬라이딩 윈도우 적용",
                        doc.getText().length(), chunkSize);
                List<Document> raw = slidingWindow(doc, chunkSize, overlap, minChunkSize);
                pieces = reinjectHeadingForSplitPieces(doc.getText(), raw);
            }
            result.addAll(prependParentBreadcrumb(docs, group.startIndex(), pieces));
        }

        result = backwardMergeShortChunks(result, minChunkSize);
        log.debug("[SPLIT] {} → 챕터 섹션 전략, {}섹션 → 병합 {}그룹 → {}청크",
                filename, docs.size(), groups.size(), result.size());
        return enforceMaxChars(result, maxChunkChars, filename);
    }

    /** A forward-merged chapter group plus the index of its first section in the original list
     *  (used to look up the parent-chapter heading — see {@link #prependParentBreadcrumb}). */
    record SectionGroup(Document doc, int startIndex) {
    }

    /** True when the (non-empty) raw doc list is tagged as OCR output — i.e. a scanned PDF. */
    private boolean isOcrSourced(List<Document> docs) {
        if (docs.isEmpty()) return false;
        return "ocr".equals(docs.get(0).getMetadata().get(MetaKey.SOURCE_TYPE));
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
            // Gate measures normalized length (§10.1-보완) — the embedding-time payload
            // (SearchTextBuilder) is context+normalize(text), not raw text, so that's the size
            // that actually needs bounding. The split below still packs raw text/raw maxChars —
            // reinterpreting maxChars against normalized length there would need a raw↔normalized
            // offset mapping, which is exactly the complexity this measure-only change avoids.
            if (text == null || MarkdownNoiseNormalizer.normalize(text).length() <= maxChars) {
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

    /** Cap on how many consecutive identical-heading slides {@link #mergeIdenticalHeadingSlides}
     *  will fold into one chunk — unbounded chaining risked pulling long unrelated runs of slides
     *  into a single oversized-feeling chunk, so a merge group tops out at a pair of slides. */
    private static final int MAX_IDENTICAL_HEADING_MERGE_SLIDES = 2;

    /**
     * PPTX/PDF page-structured pre-pass, run before {@link #mergeShortSections}: when the current
     * slide and the immediately following slide carry an <em>exactly identical</em> {@code ##}+
     * {@code ###} heading pair — the common "same sub-chapter continued across a couple of physical
     * slides" pattern — merges them into one chunk, up to {@link #MAX_IDENTICAL_HEADING_MERGE_SLIDES}
     * (default 2) slides per group. Stops (even short of the cap) once the combined (deduplicated,
     * normalized) size would exceed {@code chunkSize}, or the next slide is missing either heading
     * level, or its headings differ even slightly — the remaining slides then fall through to
     * {@link #mergeShortSections}'s normal per-slide handling. Once a merge group hits the cap, the
     * NEXT slide starts a fresh group of its own (so four consecutive identical-heading slides yield
     * two 2-slide chunks, not one 4-slide chunk). Every slide after the first in a group has its
     * duplicate {@code ##}/{@code ###} heading lines dropped from the merged body, but a
     * {@code [페이지: N]} marker is inserted in their place so the merged chunk still shows where
     * each slide's content began — like {@link #mergeShortSections}, the merged {@link Document}'s
     * {@link MetaKey#PAGE_OR_SLIDE} metadata itself still only reflects the first slide. No-op for
     * DOCX/TXT/MD (never emit a {@code ###} alongside a matching {@code ##}, or
     * {@link MetaKey#PAGE_OR_SLIDE} at all) and for non-scanned PDF (never emits any heading), so
     * this only ever fires for PPTX in practice.
     */
    List<Document> mergeIdenticalHeadingSlides(List<Document> docs, int chunkSize) {
        if (docs == null || docs.isEmpty()) return docs;

        List<List<Document>> bundles = groupByPageOrSlide(docs);
        List<Document> result = new ArrayList<>();

        int i = 0;
        while (i < bundles.size()) {
            List<Document> bundle = bundles.get(i);
            String mergedText = joinBundleText(bundle);
            HeadingPair headings = extractDualHeading(mergedText);

            int j = i;
            int mergedSlideCount = 1;
            if (headings != null) {
                while (mergedSlideCount < MAX_IDENTICAL_HEADING_MERGE_SLIDES && j + 1 < bundles.size()) {
                    List<Document> nextBundle = bundles.get(j + 1);
                    String nextText = joinBundleText(nextBundle);
                    HeadingPair nextHeadings = extractDualHeading(nextText);
                    if (nextHeadings == null || !nextHeadings.equals(headings)) break;

                    Integer nextPage = pageOrSlideOf(nextBundle.get(0));
                    String pageMarker = nextPage != null ? "[페이지: " + nextPage + "]\n\n" : "";
                    String candidate = mergedText + "\n\n" + pageMarker + stripDualHeadingLines(nextText);
                    if (MarkdownNoiseNormalizer.normalize(candidate).length() > chunkSize) break;

                    mergedText = candidate;
                    j++;
                    mergedSlideCount++;
                }
            }

            if (j > i) {
                result.add(new Document(mergedText, new HashMap<>(bundle.get(0).getMetadata())));
            } else {
                result.addAll(bundle);
            }
            i = j + 1;
        }
        return result;
    }

    /** Groups consecutive docs sharing the same {@link MetaKey#PAGE_OR_SLIDE} value into per-slide
     *  bundles — a dual-heading PPTX slide's raw {@code ##}-only section and its {@code ###}+body
     *  section land in the same bundle. A doc without the metadata always starts its own singleton
     *  bundle (never grouped — matches {@link #isMergeForbiddenByPageMismatch}'s null handling). */
    private List<List<Document>> groupByPageOrSlide(List<Document> docs) {
        List<List<Document>> bundles = new ArrayList<>();
        Integer currentPage = null;
        List<Document> current = null;
        for (Document doc : docs) {
            Integer page = pageOrSlideOf(doc);
            if (current == null || page == null || !page.equals(currentPage)) {
                current = new ArrayList<>();
                bundles.add(current);
                currentPage = page;
            }
            current.add(doc);
        }
        return bundles;
    }

    private String joinBundleText(List<Document> bundle) {
        StringBuilder sb = new StringBuilder();
        for (Document doc : bundle) {
            String text = doc.getText();
            if (text == null || text.isBlank()) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(text);
        }
        return sb.toString();
    }

    record HeadingPair(String outer, String inner) {
    }

    /** Extracts the ({@code ##}, {@code ###}) heading-text pair from a slide bundle's assembled
     *  text — both levels must be present as their own ATX heading lines (matches
     *  {@code PptxToMarkdownConverter}'s emission order: {@code ##} first, then {@code ###}).
     *  {@code null} when either level is missing (e.g. a single-heading or heading-less slide). */
    HeadingPair extractDualHeading(String text) {
        if (text == null) return null;
        String outer = null;
        String inner = null;
        for (String line : text.split("\n", -1)) {
            String trimmed = line.strip();
            int level = headingLevelOf(trimmed);
            if (level == 2 && outer == null) {
                outer = normalizeHeadingForCompare(trimmed.substring(level + 1));
            } else if (level == 3 && inner == null) {
                inner = normalizeHeadingForCompare(trimmed.substring(level + 1));
            }
        }
        return (outer != null && inner != null) ? new HeadingPair(outer, inner) : null;
    }

    /** Removes the first {@code ##}-level and first {@code ###}-level heading lines from
     *  {@code text} (plus the blank lines they leave behind), returning just the remaining body.
     *  Only called after {@link #extractDualHeading} has already confirmed both levels exist. */
    String stripDualHeadingLines(String text) {
        List<String> out = new ArrayList<>();
        boolean outerRemoved = false;
        boolean innerRemoved = false;
        for (String line : text.split("\n", -1)) {
            int level = headingLevelOf(line.strip());
            if (!outerRemoved && level == 2) { outerRemoved = true; continue; }
            if (!innerRemoved && level == 3) { innerRemoved = true; continue; }
            out.add(line);
        }
        return String.join("\n", out).strip();
    }

    /** ATX heading level (1–6) of an already-{@code strip()}ped line, or 0 if not a valid heading. */
    private int headingLevelOf(String trimmedLine) {
        int level = 0;
        while (level < trimmedLine.length() && trimmedLine.charAt(level) == '#') level++;
        if (level < 1 || level > 6 || level >= trimmedLine.length() || trimmedLine.charAt(level) != ' ') return 0;
        return level;
    }

    /** Trim + internal-whitespace collapse so incidental spacing differences don't defeat the
     *  "완전히 동일" (exactly identical) heading-pair comparison in {@link #mergeIdenticalHeadingSlides}. */
    private String normalizeHeadingForCompare(String s) {
        return s == null ? "" : s.strip().replaceAll("\\s+", " ");
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
            // Merge decisions measure normalized length (§10.1-보완 — decorative markdown shouldn't
            // consume the merge budget); acc itself stays raw text. Running counter instead of
            // renormalizing acc.toString() on every iteration to avoid O(n^2) on long sections.
            int normalizedLen = MarkdownNoiseNormalizer.normalize(acc.toString()).length();

            int currentHeadingLevel = sectionHeadingLevel(base);
            Integer pageOrSlide = pageOrSlideOf(base);
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
                if (isMergeForbiddenByPageMismatch(pageOrSlide, pageOrSlideOf(next))) {
                    break;
                }

                int normalizedNextLen = MarkdownNoiseNormalizer.normalize(nextText).length();
                int combinedNormalizedLen = normalizedLen + 2 + normalizedNextLen;

                boolean includeNext = false;
                if (normalizedLen < threshold40) {
                    includeNext = true;
                } else if (normalizedLen < threshold75 && combinedNormalizedLen < chunkSize) {
                    includeNext = true;
                }

                if (!includeNext) break;

                if (acc.length() > 0) acc.append("\n\n");
                acc.append(nextText);
                normalizedLen = combinedNormalizedLen;
                j++;
                currentHeadingLevel = nextHeadingLevel > 0 ? nextHeadingLevel : currentHeadingLevel;
            }

            merged.add(new Document(acc.toString(), metadata));
            i = j + 1;
        }

        return merged;
    }

    /**
     * Chapter-aware forward merge for md/docx/txt (replaces the 40%/75%-threshold
     * {@link #mergeShortSections} on that path). Only a section still below {@code minChunkSize}
     * pulls in the following one, and never across a heading that opens a <em>higher</em> (parent)
     * chapter. When the current group is tiny, the next section's size decides:
     * <ol>
     *   <li><b>규칙1</b> current+next fits in {@code chunkSize} → merge, keep accumulating;</li>
     *   <li><b>규칙2</b> next alone fits in {@code chunkSize} → stop (next stays a clean chunk);</li>
     *   <li><b>규칙3</b> next exceeds {@code chunkSize} (will be sliding-split) → merge only if
     *       prepending current keeps the split's last piece ≥ {@code minChunkSize * 1.5}
     *       (avoids leaving an awkward tail), otherwise stop.</li>
     * </ol>
     * Size is normalized length (§10.1-보완). Each returned {@link SectionGroup} keeps the first
     * section's metadata and its original index (for {@link #prependParentBreadcrumb}). A tiny
     * group that could not merge forward is later pulled backward by {@link #backwardMergeShortChunks}.
     */
    List<SectionGroup> mergeSectionsByChapter(List<Document> docs, int chunkSize, int overlap, int minChunkSize) {
        List<SectionGroup> groups = new ArrayList<>();
        if (docs == null || docs.isEmpty()) return groups;

        int n = docs.size();
        int[] size = new int[n];
        int[] level = new int[n];
        for (int k = 0; k < n; k++) {
            String t = docs.get(k).getText();
            size[k] = t == null ? 0 : MarkdownNoiseNormalizer.normalize(t).length();
            level[k] = sectionHeadingLevel(docs.get(k));
        }

        int i = 0;
        while (i < n) {
            int start = i;
            StringBuilder acc = new StringBuilder(docs.get(i).getText() == null ? "" : docs.get(i).getText());
            Map<String, Object> metadata = new HashMap<>(docs.get(i).getMetadata());
            int accSize = size[i];
            int accLevel = level[i]; // group's top (most-senior) heading level; 0 = no heading yet
            int j = i;

            while (accSize < minChunkSize && j + 1 < n) {
                int next = j + 1;
                String nextText = docs.get(next).getText();
                if (nextText == null || nextText.isBlank()) { // absorb blank section, keep going
                    j = next;
                    continue;
                }

                // 상위 챕터로의 병합 금지: next가 더 상위(작은 레벨) 헤딩이면 중단.
                if (accLevel > 0 && level[next] > 0 && level[next] < accLevel) break;
                if (isMergeForbiddenByPageMismatch(pageOrSlideOf(docs.get(start)), pageOrSlideOf(docs.get(next)))) break;

                int combined = accSize + 2 + size[next];
                if (combined <= chunkSize) {                 // 규칙1: 합쳐도 chunkSize 이내 → 병합
                    appendSection(acc, nextText);
                    accSize = combined;
                    accLevel = mergedTopLevel(accLevel, level[next]);
                    j = next;
                    continue;
                }
                if (size[next] <= chunkSize) break;          // 규칙2: next 단독이 chunkSize 이내 → 분리

                // 규칙3: next가 chunkSize 초과 → prepend 후 분할 시 마지막 조각이 너무 작아지면 병합 안 함.
                String combinedText = acc + "\n\n" + nextText;
                List<String> pieces = rawSlidingPieces(combinedText, chunkSize, overlap);
                int lastLen = pieces.isEmpty() ? 0 : pieces.get(pieces.size() - 1).strip().length();
                if (lastLen < minChunkSize * 1.5) break;
                appendSection(acc, nextText);
                accSize = combined;
                accLevel = mergedTopLevel(accLevel, level[next]);
                j = next;
                break;                                       // next는 이미 큼 → 그 뒤로는 병합 시도 안 함
            }

            // "0"인 CHAPTER_NO는 아직 실제 챕터를 못 만난 프롤로그 플레이스홀더(H1 제목만 있거나
            // 헤딩이 아예 없는 경우 — DocumentLoaderService#splitMarkdownBySections 참고: 챕터
            // 카운터는 H2부터만 증가한다)일 뿐 "0장"이 실재하는 게 아니다. 이런 그룹이 forward-merge
            // 로 실제 챕터 헤딩(예: "## 1장")을 흡수했다면 병합된 청크는 사실상 그 챕터 안에서
            // 시작하는 것이므로, 그룹 내에서 처음 발견되는 진짜 챕터 번호로 교체한다. 시작 섹션이
            // 이미 실제 챕터 번호를 갖고 있으면(또는 병합된 어떤 섹션에도 실제 번호가 없으면) 기존
            // "첫 섹션 메타데이터 우선" 관례를 그대로 따른다.
            if ("0".equals(metadata.get(MetaKey.CHAPTER_NO))) {
                metadata.put(MetaKey.CHAPTER_NO, firstRealChapterNo(docs, start, j));
            }
            groups.add(new SectionGroup(new Document(acc.toString(), metadata), start));
            i = j + 1;
        }
        return groups;
    }

    /** First non-"0" {@link MetaKey#CHAPTER_NO} among {@code docs[start..end]}, or "0" if none. */
    private static String firstRealChapterNo(List<Document> docs, int start, int end) {
        for (int k = start; k <= end; k++) {
            Object v = docs.get(k).getMetadata().get(MetaKey.CHAPTER_NO);
            if (v instanceof String s && !"0".equals(s)) return s;
        }
        return "0";
    }

    private static void appendSection(StringBuilder acc, String text) {
        if (acc.length() > 0) acc.append("\n\n");
        acc.append(text);
    }

    /** Group's representative (most-senior) heading level: the min of the non-zero levels seen,
     *  0 only while no section in the group has a heading. */
    private static int mergedTopLevel(int accLevel, int nextLevel) {
        if (nextLevel <= 0) return accLevel;
        if (accLevel <= 0) return nextLevel;
        return Math.min(accLevel, nextLevel);
    }

    /**
     * Prepends the immediate parent-chapter heading (one level up) as a single breadcrumb line to
     * the <em>first (non-tail) piece only</em> of a child-chapter group — giving that chunk its
     * chapter context. No-op unless the group's leading section is a child chapter (heading level
     * ≥ 3; a top-level {@code ##} or heading-less section is skipped). The parent heading is the
     * nearest preceding section (in the original ordered list) whose heading level is lower;
     * skipped when none exists. Tail pieces (2nd+) already carry their own reinjected heading
     * ({@link #reinjectHeadingForSplitPieces}) and are left untouched.
     */
    List<Document> prependParentBreadcrumb(List<Document> sections, int startIndex, List<Document> pieces) {
        if (pieces.isEmpty()) return pieces;

        int ownLevel = sectionHeadingLevel(sections.get(startIndex));
        if (ownLevel < 3) return pieces; // 조건2: ## 최상위(또는 헤딩 없음/#)는 대상 아님

        HeadingInfo parent = null;
        for (int k = startIndex - 1; k >= 0; k--) {
            int lvl = sectionHeadingLevel(sections.get(k));
            if (lvl > 0 && lvl < ownLevel) {
                parent = extractLeadingHeading(sections.get(k).getText());
                break;
            }
        }
        if (parent == null) return pieces;

        String parentLine = parent.marker() + " " + parent.text();
        List<Document> out = new ArrayList<>(pieces);
        Document first = out.get(0);
        out.set(0, new Document(parentLine + "\n" + first.getText(), new HashMap<>(first.getMetadata())));
        return out;
    }

    /**
     * Cross-section analog of {@link #mergeTinyChunks}: any emitted chunk still below
     * {@code minChunkSize} (normalized) is merged into the previous chunk; a tiny leading chunk with
     * no predecessor is prepended to the following one instead. This is what pulls a small section
     * that could not merge forward (next was a parent chapter, or 규칙2 kept next clean) backward
     * into its natural home. The {@code page_or_slide} guard is a safety no-op for md/docx/txt.
     */
    List<Document> backwardMergeShortChunks(List<Document> chunks, int minChunkSize) {
        if (chunks == null || chunks.isEmpty()) return chunks;

        List<Document> out = new ArrayList<>();
        String pendingPrefix = "";
        Map<String, Object> pendingMeta = null;

        for (Document chunk : chunks) {
            String text = chunk.getText() == null ? "" : chunk.getText();
            if (text.isBlank()) continue;

            Map<String, Object> meta = new HashMap<>(chunk.getMetadata());
            if (!pendingPrefix.isEmpty()) {           // flush leading orphan(s) into this chunk
                text = pendingPrefix + "\n\n" + text;
                pendingPrefix = "";
                pendingMeta = null;
            }

            if (MarkdownNoiseNormalizer.normalize(text).length() < minChunkSize) {
                if (!out.isEmpty()) {
                    Document prev = out.get(out.size() - 1);
                    if (isMergeForbiddenByPageMismatch(pageOrSlideOf(prev), pageOrSlideOf(chunk))) {
                        out.add(new Document(text, meta));
                    } else {
                        out.set(out.size() - 1,
                                new Document(prev.getText() + "\n\n" + text, new HashMap<>(prev.getMetadata())));
                    }
                } else {
                    pendingPrefix = text;
                    pendingMeta = meta;
                }
                continue;
            }
            out.add(new Document(text, meta));
        }

        if (!pendingPrefix.isEmpty()) { // every chunk was tiny, or a tiny tail never found a successor
            if (!out.isEmpty()) {
                Document first = out.get(0);
                out.set(0, new Document(pendingPrefix + "\n\n" + first.getText(), new HashMap<>(first.getMetadata())));
            } else {
                out.add(new Document(pendingPrefix, pendingMeta != null ? pendingMeta : new HashMap<>()));
            }
        }
        return out;
    }

    /**
     * Blocks merging across a slide/page boundary. PPTX/non-scanned-PDF sections are always
     * tagged with {@link MetaKey#PAGE_OR_SLIDE}; letting them merge across a different value would
     * silently drop every merged-in slide/page number but the first ({@link #mergeShortSections}
     * keeps only the base section's metadata), breaking the "1 chunk = 1 slide/page = exact
     * citation" guarantee those formats rely on. No-op when either side lacks the field (DOCX/
     * TXT/MD don't set it) — matches today's behavior for those formats exactly.
     */
    boolean isMergeForbiddenByPageMismatch(Integer currentPageOrSlide, Integer nextPageOrSlide) {
        if (currentPageOrSlide == null || nextPageOrSlide == null) return false;
        return !currentPageOrSlide.equals(nextPageOrSlide);
    }

    private Integer pageOrSlideOf(Document doc) {
        Object v = doc.getMetadata().get(MetaKey.PAGE_OR_SLIDE);
        return v instanceof Integer i ? i : null;
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
        List<String> rawChunks = rawSlidingPieces(doc.getText(), chunkSize, overlap);
        for (String merged : mergeTinyChunks(rawChunks, minChunkSize)) {
            result.add(new Document(merged, new HashMap<>(doc.getMetadata())));
        }
        return result;
    }

    /**
     * The raw sliding-window boundary pass (code-fence/table-aware, with {@link #reopenTruncatedBlock}
     * re-wrapping) BEFORE the tiny-chunk merge — extracted from {@link #slidingWindow} so the
     * chapter-merge rule-3 look-ahead ({@link #mergeSectionsByChapter}) measures the exact same
     * boundaries the real split will produce. {@link #slidingWindow} is now this plus
     * {@link #mergeTinyChunks} plus metadata mapping (behavior unchanged).
     */
    List<String> rawSlidingPieces(String text, int chunkSize, int overlap) {
        List<String> rawChunks = new ArrayList<>();
        if (text == null || text.isBlank()) return rawChunks;
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
        return rawChunks;
    }

    /**
     * When a sliding-window boundary falls inside a table or fenced code block too large to keep
     * whole (see {@link #adjustEndForCodeBlock}/{@link #adjustEndForTableBlock}, which only snap
     * boundaries when doing so is cheap), the piece reads as a headerless data row or a bare code
     * fragment with no language context — and, for code, gives no indication that the block
     * continues in a neighboring chunk. Detects that case and re-wraps the piece so it is
     * self-contained: table pieces get the original header+separator row prepended; code pieces
     * get the original opening fence line prepended (when this piece doesn't contain the block's
     * real start) and a closing fence appended (when the block doesn't close within this piece) —
     * either side also gets a {@link #CODE_CONTINUATION_BEFORE}/{@link #CODE_CONTINUATION_AFTER}
     * marker line. No-op when {@code start}/{@code end} aren't a continuation of either.
     */
    String reopenTruncatedBlock(String fullText, int start, int end, String chunk) {
        Range codeRange = findFencedCodeRangeContaining(fullText, start + 1);
        if (codeRange != null && (codeRange.start() < start || end < codeRange.end())) {
            return reopenCodeFence(fullText, codeRange, start, end, chunk);
        }
        Range tableRange = findTableRangeContaining(fullText, start + 1);
        if (tableRange != null && tableRange.start() < start) {
            return reinjectTableHeader(fullText, tableRange, chunk);
        }
        return chunk;
    }

    /**
     * Re-wraps a code-fence piece and annotates it with {@link #CODE_CONTINUATION_BEFORE}/
     * {@link #CODE_CONTINUATION_AFTER} markers as plain text lines outside the fence — never
     * inside it, so the reconstructed code content stays exactly what {@code fullText} had.
     */
    String reopenCodeFence(String fullText, Range codeRange, int chunkStart, int chunkEnd, String chunk) {
        boolean continuesFromBefore = codeRange.start() < chunkStart;
        boolean continuesAfter = chunkEnd < codeRange.end();

        StringBuilder sb = new StringBuilder();
        if (continuesFromBefore) {
            int fenceLineEnd = fullText.indexOf('\n', codeRange.start());
            if (fenceLineEnd == -1) fenceLineEnd = fullText.length();
            String openingFenceLine = fullText.substring(codeRange.start(), fenceLineEnd).strip();
            sb.append(CODE_CONTINUATION_BEFORE).append('\n').append(openingFenceLine).append('\n');
        }
        sb.append(chunk);
        if (continuesAfter) {
            // block doesn't close within this piece — close it so it stays valid, then note the
            // continuation (marker sits after the fence, not inside it).
            sb.append("\n```\n").append(CODE_CONTINUATION_AFTER);
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
