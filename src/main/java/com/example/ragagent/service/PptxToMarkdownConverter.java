package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFChart;
import org.apache.poi.xslf.usermodel.XSLFDiagram;
import org.apache.poi.xslf.usermodel.XSLFGraphicFrame;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFHyperlink;
import org.apache.poi.xslf.usermodel.XSLFObjectShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFShapeContainer;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts a PPTX to Markdown, one {@code [페이지: N]} block per slide. The {@code [페이지: N]}
 * marker is itself the per-slide section boundary (both {@code DocumentLoaderService} and
 * {@code MarkdownCorrectionService} split on it), so a title-less slide emits the marker alone with
 * no synthetic heading. A slide WITH a real title still renders it as the {@code ##} (and optional
 * {@code ###} subtitle) heading.
 *
 * The slide's title placeholder (TITLE/CENTERED_TITLE) becomes the primary {@code ##} heading
 * when present. Many real-world decks also carry a second, non-placeholder "title-like" text box
 * (e.g. a running chapter label plus a slide-specific subtitle) that POI can't identify via
 * {@link Placeholder} — on slides that have bulleted content further down, {@link #extractSlide}
 * additionally promotes up to one more short, fully bold, non-bulleted paragraph appearing before
 * the first bullet into a second heading candidate. Slides with no bullets at all (typical
 * cover/section-divider slides) never go through this promotion, so a short bold caption or
 * subtitle there stays plain text exactly as before. Everything else (body/content placeholders,
 * freeform text boxes, and any pre-bullet paragraph that doesn't look heading-like) is rendered as
 * plain text or, for bulleted paragraphs, a nested list line keyed off {@code getIndentLevel()}.
 * Indent depth itself is never promoted to its own heading, regardless of nesting.
 *
 * When a slide has two heading candidates, {@link #calibrateHeadingOrder} decides which is the
 * outer ({@code ##}) vs. inner ({@code ###}) heading by cross-slide frequency: whichever exact
 * text recurs on more slides reads as the higher-level (chapter/section) heading — position of
 * discovery is only a tiebreaker. This matters because a "chapter" label and its per-slide
 * subtitle can appear in either shape order depending on the deck's layout.
 *
 * Authors commonly restate a subtitle as the first bullet of its own content placeholder —
 * {@link #stripLeadingDuplicateBullet} drops that one bullet line when it exactly matches (modulo
 * emphasis markers) one of the slide's heading texts, so it isn't kept twice.
 *
 * A slide with no heading, no body text, and no extracted image (blank divider, etc.) is skipped
 * entirely — no marker, no fallback heading — so it never becomes a content-free chunk. Slide
 * numbering ({@code [페이지: N]}) is unaffected by skipped slides; it always reflects the real
 * slide index.
 *
 * Images are handled inline here, like {@link DocxToMarkdownConverter}: {@link PptxImageExtractor}
 * extracts each slide's pictures to {@code imagesDir} up front, and their relative paths are
 * emitted as {@code [이미지: ...]} markers. {@code loadFromMarkdown()} then promotes those markers
 * into {@code image_paths} metadata exactly as it does for DOCX — no separate metadata-attachment
 * step is needed downstream. An image with no correlatable owner (a plain top-level picture, OLE
 * preview, ...) is always hoisted right after the slide's heading(s), regardless of where the
 * picture actually sits among the slide's shapes — an intentional simplification, not a bug: a
 * whole slide is always one chunk (§6.3-bis in PIPELINE.md), so exact marker position within that
 * chunk doesn't affect what's retrievable, only how the raw chunk text reads if inspected directly
 * (e.g. in {@code /admin}). An image {@link PptxImageExtractor} tags with an owner — a group,
 * SmartArt, or chart's own rasterized/fallback picture (via
 * {@link PptxImageExtractor#extractWithOwners}, matched here by {@code slide.getShapes()} index —
 * see {@link #extractSlide}) — is the one exception: its marker is placed inline inside that
 * shape's own {@code [도형 그룹]}/{@code [다이어그램]}/{@code [차트: ...]} block instead, so the
 * image-to-text correlation is visible directly in the marker layout. Tables ({@code XSLFTable}) are rendered as a markdown pipe table by
 * {@link #appendTable} wherever they appear in shape order — merge-continuation cells
 * ({@code XSLFTableCell#isMerged()}) render blank, mirroring how {@link DocxToMarkdownConverter}
 * handles merged DOCX table cells.
 *
 * {@code FOOTER}/{@code SLIDE_NUMBER}/{@code DATETIME} placeholders (page footers, running slide
 * numbers, dates) are skipped entirely — they repeat verbatim on every slide and would otherwise
 * pollute every chunk's embedding. Bulleted paragraphs render as {@code "1. "} when the bullet is
 * an auto-numbered list ({@code getAutoNumberingScheme() != null}) or {@code "- "} otherwise,
 * mirroring {@link DocxToMarkdownConverter}'s ordered/unordered distinction. Hyperlinked runs
 * render as {@code [text](url)} via {@code XSLFTextRun#getHyperlink()}.
 *
 * {@code XSLFTable}, {@code XSLFDiagram} (SmartArt), {@code XSLFObjectShape} (OLE embeds), and
 * chart frames are all {@code XSLFGraphicFrame} subclasses/variants that the plain
 * {@code XSLFTextShape} walk above never sees on its own. SmartArt's box/label text and a plain
 * group's inner text are each wrapped in a {@code [다이어그램] … [/다이어그램]} /
 * {@code [도형 그룹] … [/도형 그룹]} marker block by {@link #appendShapeGroup} so all labels pulled
 * from one shape stay bundled together and are visibly marked as shape-extracted (rather than
 * bleeding into the surrounding body text); a chart frame contributes only its title, rendered as
 * {@code [차트: 제목]} (series/category values aren't reliably extractable without re-implementing
 * per-chart-type layout); an OLE embed contributes no text at all — {@link PptxImageExtractor}
 * separately pulls its embedded preview picture and, best-effort, a chart's {@code mc:Fallback}
 * preview picture when PowerPoint included one. These bracket markers live in {@code Document.getText()}
 * itself, so they flow into the embedding/FTS input, the {@code /admin} chunk view, and the answer
 * prompt (like {@code [이미지: ...]}); they start with '[' not '#', so {@code splitMarkdownBySections()}
 * never treats them as section boundaries and {@code MarkdownNoiseNormalizer} never strips them.
 *
 * Shapes are walked in reading order, not {@code slide.getShapes()}'s z-order (paint order) —
 * {@link #inReadingOrder} sorts by anchor position (top first, then left) before the body loop
 * runs, so a text box added last (and therefore drawn last / listed last in z-order) but placed
 * near the top of the slide is still emitted first, matching how a human reads the slide. This
 * also improves heading-candidate detection, since {@link #looksLikeHeadingCandidate} promotion
 * depends on "appears before the first bullet" in the same walk order.
 *
 * Thread-safe: opens a new {@link XMLSlideShow} per call (no shared state).
 */
@Component
public class PptxToMarkdownConverter {

    private static final Pattern DATE_TOKEN_PATTERN = Pattern.compile("\\b(?:\\d{8}|\\d{4}[-._]?\\d{2}[-._]?\\d{2})\\b");
    // Bold's delimiter (** vs ***) needs its own capture group (1) to backreference the same
    // delimiter at the close, pushing content to group 2. Italic's delimiter is always a literal
    // single '_' guarded by lookaround (see stripEmphasisMarkers), so it needs no delimiter group
    // and content is group 1. stripMarkerPattern() takes the content-group index explicitly since
    // the two patterns don't share a layout.
    private static final Pattern BOLD_EMPHASIS_PATTERN = Pattern.compile("(\\*\\*\\*|\\*\\*)(.*?)\\1");
    private static final Pattern ITALIC_EMPHASIS_PATTERN = Pattern.compile("(?<!\\w)_(.*?)_(?!\\w)");
    private static final int MAX_HEADING_CANDIDATES = 2;
    private static final int MAX_HEADING_CANDIDATE_LENGTH = 40;
    /** 슬라이드 하나에 이 값 이상 볼드 스팬이 있으면 강조로서 의미가 없다고 보고 마커를 전부 제거한다. */
    private static final int EXCESSIVE_BOLD_THRESHOLD = 10;
    /** 도형 그룹/표 하나 안에 이 값 이상 볼드 스팬이 있으면 그 블록만 볼드 마커를 전부 제거한다
     *  ({@link #EXCESSIVE_BOLD_THRESHOLD}는 슬라이드 전체 기준이라, 볼드가 한 블록에만 몰려 있고
     *  슬라이드 전체 개수는 임계값 미만인 경우를 놓친다). */
    private static final int BLOCK_BOLD_COUNT_THRESHOLD = 6;
    /** 도형 그룹/표 하나에서 볼드로 덮인 글자 수가 전체 글자 수의 이 비율 이상이면(개수와 무관하게)
     *  그 블록만 볼드 마커를 전부 제거한다 — 스팬 몇 개뿐이라도 그게 텍스트 대부분을 차지하면
     *  개수 기준({@link #BLOCK_BOLD_COUNT_THRESHOLD})만으로는 못 잡기 때문. */
    private static final double BLOCK_BOLD_RATIO_THRESHOLD = 0.5;

    private static final Logger log = LoggerFactory.getLogger(PptxToMarkdownConverter.class);

    private final PptxImageExtractor imageExtractor;
    private final AppProperties props;

    public PptxToMarkdownConverter(PptxImageExtractor imageExtractor, AppProperties props) {
        this.imageExtractor = imageExtractor;
        this.props = props;
    }

    /**
     * Pure per-slide extraction result: heading candidates (discovery order) + rendered body +
     * the image paths already placed inline inside a group/diagram/chart's own bracket block
     * (excluded from the top-of-slide hoisted image list by the caller).
     */
    private record SlideExtract(List<String> headingCandidates, String body, Set<String> consumedImagePaths) {
    }

    /**
     * @param pptxPath  source PPTX file
     * @param imageId   content-hash key for the images subdirectory (see DocumentIndexer.imageId)
     * @param imagesDir directory where extracted images are saved
     * @return full markdown text with a {@code [페이지: N]}-tagged heading per slide, with
     *         {@code [이미지: ...]} markers for any pictures on that slide
     */
    public String convert(Path pptxPath, String imageId, Path imagesDir) throws IOException {
        StringBuilder sb = new StringBuilder();
        // One XMLSlideShow, reused for both image extraction and text conversion — parsing a large
        // deck's XML twice is real, avoidable cost (correctness is unaffected either way, since the
        // two passes don't mutate shared state).
        try (XMLSlideShow pptx = new XMLSlideShow(Files.newInputStream(pptxPath))) {
            Map<Integer, List<PptxImageExtractor.ExtractedImage>> imageMap =
                    imageExtractor.extractWithOwners(pptx, imageId, imagesDir);

            String title = resolveDocumentTitle(pptx, pptxPath);
            if (!title.isBlank()) {
                sb.append("# ").append(title).append("\n\n");
            }

            List<XSLFSlide> slides = pptx.getSlides();

            // Pass 1: extract each slide's heading candidates + body once, and count how many
            // distinct slides each exact heading text appears on (needed for calibration below).
            List<SlideExtract> extracts = new ArrayList<>(slides.size());
            Map<String, Integer> headingFrequency = new HashMap<>();
            for (int i = 0; i < slides.size(); i++) {
                List<PptxImageExtractor.ExtractedImage> slideImages = imageMap.getOrDefault(i + 1, List.of());
                SlideExtract extract = extractSlide(slides.get(i), slideImages);
                extracts.add(extract);
                for (String heading : new LinkedHashSet<>(extract.headingCandidates())) {
                    headingFrequency.merge(heading, 1, Integer::sum);
                }
            }

            // Duplicate/table-of-contents slide removal (§ app.pptx-remove-duplicate-slides, default on):
            // read fresh per conversion so a /settings-style override applies on the next indexing.
            boolean dedup = props != null && props.pptxRemoveDuplicateSlidesSafe();
            // Section-divider (title-only, body-less, image-less) slide removal (§ app.pptx-drop-divider-slides).
            boolean dropDividers = props != null && props.pptxDropDividerSlidesSafe();
            // "Preview title" slide removal — title-only slide whose text is restated on the next slide
            // (§ app.pptx-drop-redundant-title-slides).
            boolean dropRedundantTitles = props != null && props.pptxDropRedundantTitleSlidesSafe();
            // Last-slide ending-marker (끝/END/The End) removal (§ app.pptx-drop-ending-slide).
            boolean dropEndingSlide = props != null && props.pptxDropEndingSlideSafe();
            // Every heading text across the deck (normalized for comparison) — the reference set the
            // table-of-contents heuristic matches a slide's bullets against.
            Set<String> allHeadingsNormalized = headingFrequency.keySet().stream()
                    .map(this::normalizeForCompare)
                    .filter(s -> !s.isBlank())
                    .collect(java.util.stream.Collectors.toSet());
            Set<String> seenFingerprints = new HashSet<>();

            // Pass 2: emit, resolving outer/inner heading order per slide from the global counts.
            for (int i = 0; i < slides.size(); i++) {
                int slideNum = i + 1;
                SlideExtract extract = extracts.get(i);
                // Images this slide's groups/diagrams/charts already placed inline in their own
                // bracket blocks (extractSlide()) are excluded here — only genuinely unassociated
                // images (plain pictures, OLE previews, ...) still get hoisted to the top.
                List<String> hoistedImages = imageMap.getOrDefault(slideNum, List.of()).stream()
                        .map(PptxImageExtractor.ExtractedImage::path)
                        .filter(path -> !extract.consumedImagePaths().contains(path))
                        .toList();
                boolean hasImages = !hoistedImages.isEmpty() || !extract.consumedImagePaths().isEmpty();
                if (dedup && shouldDropSlide(extract, hasImages, seenFingerprints, allHeadingsNormalized, slideNum)) {
                    continue; // dropped — skipped slide's number never shifts the following slides (slideNum = i+1)
                }
                if (dropDividers && isSectionDividerSlide(extract, hasImages)) {
                    log.debug("[PPTX] {}번 슬라이드 제거 — 본문·이미지 없이 구분용 제목만 있는 섹션 구분 슬라이드", slideNum);
                    continue;
                }
                if (dropRedundantTitles) {
                    SlideExtract nextExtract = (i + 1 < slides.size()) ? extracts.get(i + 1) : null;
                    if (isRedundantTitlePreviewSlide(extract, hasImages, nextExtract)) {
                        log.debug("[PPTX] {}번 슬라이드 제거 — 이미지·도형 없이 제목만 있고 다음 슬라이드에 같은 내용이 포함됨(예고 제목 슬라이드)", slideNum);
                        continue;
                    }
                }
                if (dropEndingSlide && i == slides.size() - 1 && isEndingOnlySlide(extract, hasImages)) {
                    log.debug("[PPTX] {}번 슬라이드 제거 — 마지막 슬라이드이고 종료 표시('끝'/'END' 등)만 있음", slideNum);
                    continue;
                }
                appendSlide(sb, extract, slideNum, hoistedImages, headingFrequency);
            }
        }
        return sb.toString();
    }

    /**
     * 슬라이드 하나에서 헤딩 후보(제목 placeholder + 불릿 이전에 등장하는, 짧고 전체가 굵은
     * 비불릿 문단 1개)와 본문 텍스트를 한 번에 추출한다. 헤딩 후보로 승격된 문단은 본문에서
     * 제외된다. 최대 {@link #MAX_HEADING_CANDIDATES}개까지만 승격하고, 첫 불릿을 만난 이후로는
     * 더 이상 헤딩 후보를 찾지 않는다(표지/구분 슬라이드처럼 본문이 프로즈로만 이어지는 경우
     * 오탐을 줄이기 위함).
     */
    private SlideExtract extractSlide(XSLFSlide slide, List<PptxImageExtractor.ExtractedImage> slideImages) {
        List<String> headingCandidates = new ArrayList<>();
        String slideTitle = slide.getTitle();
        if (slideTitle != null && !slideTitle.isBlank()) {
            headingCandidates.add(normalizeHeadingText(slideTitle));
        }

        // Only slides that actually have bulleted content further down match the "title(s) then
        // bullets" pattern this heuristic targets. Cover/section-divider slides typically have no
        // bullets at all, so skipping promotion there keeps them exactly as before (no false
        // positives from a short bold subtitle/caption that isn't meant as a heading).
        boolean slideHasBullets = slideHasAnyBullet(slide);

        // Original z-order shape list — the same index space PptxImageExtractor used to tag each
        // ExtractedImage's ownerShapeIndices() (see its javadoc). Must NOT be inReadingOrder()'s
        // resorted view, or indices would disagree between the two components.
        List<XSLFShape> topLevelShapes = slide.getShapes();
        IdentityHashMap<XSLFShape, Integer> topLevelIndexOf = new IdentityHashMap<>();
        for (int i = 0; i < topLevelShapes.size(); i++) {
            topLevelIndexOf.put(topLevelShapes.get(i), i);
        }
        Map<Integer, List<String>> imagesByOwnerIndex = new HashMap<>();
        for (PptxImageExtractor.ExtractedImage img : slideImages) {
            for (Integer idx : img.ownerShapeIndices()) {
                imagesByOwnerIndex.computeIfAbsent(idx, k -> new ArrayList<>()).add(img.path());
            }
        }
        // 같은 종류(그룹/다이어그램/차트)가 슬라이드에 2개 이상일 때만 라벨에 순번을 붙인다 — 1개뿐이면
        // 기존 라벨("도형 그룹"/"다이어그램"/"차트")을 그대로 유지해 출력/테스트 호환성을 지킨다.
        long groupCount = topLevelShapes.stream().filter(s -> s instanceof XSLFGroupShape).count();
        long diagramCount = topLevelShapes.stream().filter(s -> s instanceof XSLFDiagram).count();
        long chartCount = topLevelShapes.stream()
                .filter(s -> s instanceof XSLFGraphicFrame f && f.hasChart())
                .count();
        int groupSeen = 0;
        int diagramSeen = 0;
        int chartSeen = 0;
        Set<String> consumedImagePaths = new LinkedHashSet<>();

        StringBuilder body = new StringBuilder();
        boolean bulletSeen = false;
        // 직전에 추가한 본문 줄(정규화된 텍스트) — shape 경계를 넘어 연속 중복 줄을 잡아내기 위해
        // 도형 루프 밖(메서드 레벨)에 둔다. 표/그룹/다이어그램/차트를 만나도 리셋하지 않는다 — 흔한
        // 케이스(복붙으로 같은 줄이 연달아 들어간 경우)만 노린 단순한 규칙으로 충분하다고 본다.
        String lastBodyLine = null;
        for (XSLFShape shape : inReadingOrder(topLevelShapes)) {
            if (shape instanceof XSLFTable table) {
                appendTable(body, table);
                continue;
            }
            if (shape instanceof XSLFDiagram diagram) {
                // SmartArt: getGroupShape() is the rendered drawing layer (real box/text shapes),
                // reused via the same text walk as a plain group — extracting from the diagram's
                // own data model would require re-implementing POI's layout engine.
                diagramSeen++;
                String label = diagramCount > 1 ? "다이어그램 " + diagramSeen : "다이어그램";
                List<String> owned = imagesByOwnerIndex.getOrDefault(topLevelIndexOf.get(shape), List.of());
                XSLFShapeContainer diagramGroup = diagram.getGroupShape();
                if (diagramGroup != null) {
                    appendShapeGroup(body, diagramGroup, label, owned, consumedImagePaths);
                }
                continue;
            }
            if (shape instanceof XSLFObjectShape) {
                continue; // OLE 객체 — 텍스트 없음, 미리보기 이미지는 PptxImageExtractor가 별도로 추출
            }
            if (shape instanceof XSLFGraphicFrame frame && frame.hasChart()) {
                chartSeen++;
                String label = chartCount > 1 ? "차트 " + chartSeen : "차트";
                List<String> owned = imagesByOwnerIndex.getOrDefault(topLevelIndexOf.get(shape), List.of());
                appendChartText(body, frame, label, owned, consumedImagePaths);
                continue;
            }
            if (shape instanceof XSLFGroupShape group) {
                // The group is also rasterized as one bundled image (PptxImageExtractor), but
                // that alone is invisible without a Vision-capable LLM — extract its text too so
                // it stays searchable even when addImageDescriptions/Vision isn't available.
                groupSeen++;
                String label = groupCount > 1 ? "도형 그룹 " + groupSeen : "도형 그룹";
                List<String> owned = imagesByOwnerIndex.getOrDefault(topLevelIndexOf.get(shape), List.of());
                appendShapeGroup(body, group, label, owned, consumedImagePaths);
                continue;
            }
            if (!(shape instanceof XSLFTextShape textShape)) continue;
            Placeholder type = textShape.getTextType();
            if (isTitlePlaceholder(type)) {
                continue; // already captured via slide.getTitle() above
            }
            if (isNoiseFooterPlaceholder(type)) {
                continue; // footer/slide-number/date-time placeholders repeat on every slide — noise, not content
            }

            // 도형 하나의 살아남은 줄을 모은 뒤 한꺼번에 렌더링한다 — 여러 줄이 코드면 통째로 펜스로
            // 감싸야 하므로(appendSlideBodyShape), 줄 단위로 즉시 append 할 수 없다. 헤딩 승격·연속
            // 중복 판정은 기존과 똑같이 문단 순서대로 진행해 동작을 그대로 유지한다.
            List<BodyLine> shapeLines = new ArrayList<>();
            for (XSLFTextParagraph para : textShape.getTextParagraphs()) {
                String rawFull = rawParagraphText(para);
                String raw = rawFull.trim();
                if (raw.isBlank()) {
                    if (!shapeLines.isEmpty()) shapeLines.add(BodyLine.blank()); // 중간 빈 줄만 보존
                    continue;
                }

                boolean isBullet = para.isBullet();
                if (!isBullet && !bulletSeen && slideHasBullets
                        && headingCandidates.size() < MAX_HEADING_CANDIDATES
                        && looksLikeHeadingCandidate(para, raw)) {
                    headingCandidates.add(normalizeHeadingText(raw));
                    continue; // promoted to a heading, not body
                }

                if (isBullet) bulletSeen = true;
                String rendered = paragraphText(para);
                String normalized = normalizeForDedup(rendered);
                if (normalized.equals(lastBodyLine)) continue; // 직전 줄과 내용이 같음 — 연속 중복 스킵
                shapeLines.add(new BodyLine(para, rendered, rawFull.stripTrailing()));
                lastBodyLine = normalized;
            }
            appendSlideBodyShape(body, shapeLines);
        }

        return new SlideExtract(headingCandidates, stripExcessiveBold(body.toString()), consumedImagePaths);
    }

    /** 슬라이드 본문 한 줄. {@code para}가 {@code null}이면 문단 사이 빈 줄로, 코드 펜스 경로에서만
     *  원문 재현을 위해 보존되고 일반 경로에서는 기존처럼 버려진다. */
    private record BodyLine(XSLFTextParagraph para, String rendered, String raw) {
        static BodyLine blank() { return new BodyLine(null, "", ""); }
    }

    /**
     * 그룹에 속하지 않은 <b>단독 텍스트 상자</b> 하나의 본문을 버퍼에 추가한다 —
     * {@link #appendShapeTextBlock}(그룹 내부 도형)의 슬라이드 본문 판. 여러 줄이고 불릿이 없으며
     * {@link #looksLikeCodeBlock}가 코드로 판정하면 통째로 {@code ```} 펜스로 감싸고(원문
     * {@link #rawParagraphText} 사용 — 들여쓰기 보존), 그 외에는 줄마다 {@link #appendBodyLine}로
     * 넘겨 기존 동작(비불릿은 {@code \n\n} 문단 분리, 불릿은 들여쓰기+마커)을 그대로 유지한다.
     *
     * <p>코드가 아닌 경우 그룹 경로와 달리 별도의 빈 줄 블록 처리를 하지 않는다 —
     * {@link #appendBodyLine}가 이미 비불릿 문단마다 {@code \n\n}를 넣어 문단으로 갈라놓기 때문.
     */
    private void appendSlideBodyShape(StringBuilder body, List<BodyLine> shapeLines) {
        while (!shapeLines.isEmpty() && shapeLines.get(shapeLines.size() - 1).para() == null) {
            shapeLines.remove(shapeLines.size() - 1); // 꼬리 빈 줄 제거
        }
        List<BodyLine> content = shapeLines.stream().filter(l -> l.para() != null).toList();
        if (content.isEmpty()) return;

        boolean anyBullet = content.stream().anyMatch(l -> l.para().isBullet());
        if (content.size() > 1 && !anyBullet
                && looksLikeCodeBlock(shapeLines.stream().map(BodyLine::raw).toList())) {
            appendBlankLineIfNeeded(body);
            body.append("```\n");
            for (BodyLine line : shapeLines) body.append(line.raw()).append("\n");
            body.append("```\n\n");
            return;
        }
        for (BodyLine line : content) appendBodyLine(body, line.para(), line.rendered());
    }

    /** 도형 하나 + 정렬 키(anchor 좌상단 y, x)를 함께 들고 다니기 위한 보조 레코드. */
    private record ShapeWithPosition(XSLFShape shape, double y, double x) {
    }

    /**
     * {@code slide.getShapes()}가 반환하는 z-order(그린 순서·paint order)는 읽기 순서와 다를 수
     * 있다 — 저자가 나중에 슬라이드 상단에 텍스트 상자를 추가하면 z-order상으로는 맨 뒤에 오지만
     * 화면에는 맨 위에 보인다. 각 도형의 anchor 좌상단 좌표(y 우선, 동률이면 x)로 재정렬해 본문이
     * 화면에 보이는 순서에 더 가깝게 조립되도록 한다. anchor가 없는 도형(레이아웃에서 위치를
     * 상속받아 로컬 {@code xfrm}이 없는 placeholder 등)은 {@link Double#MAX_VALUE}로 취급해 맨
     * 뒤로 보내되, {@link List#sort}가 안정 정렬이므로 anchor 없는 도형들끼리는 원래 z-order가
     * 그대로 유지된다 — 위치 정보가 없으니 그 이상은 추정하지 않는다.
     */
    private List<XSLFShape> inReadingOrder(List<XSLFShape> shapes) {
        List<ShapeWithPosition> positioned = new ArrayList<>(shapes.size());
        for (XSLFShape shape : shapes) {
            Rectangle2D anchor = safeAnchor(shape);
            positioned.add(new ShapeWithPosition(shape,
                    anchor != null ? anchor.getY() : Double.MAX_VALUE,
                    anchor != null ? anchor.getX() : Double.MAX_VALUE));
        }
        positioned.sort(Comparator.comparingDouble(ShapeWithPosition::y)
                .thenComparingDouble(ShapeWithPosition::x));
        return positioned.stream().map(ShapeWithPosition::shape).toList();
    }

    /**
     * {@code XSLFGraphicFrame}(표·SmartArt·차트·OLE) 계열은 {@code xfrm}이 없으면
     * {@code getAnchor()}가 예외를 던진다(스펙상 필수 요소지만 손상된 파일에 대비) — 나머지 도형은
     * {@code null}만 반환하므로 두 경우 모두 {@code null}로 통일해 정렬 키 계산을 단순화한다.
     */
    private Rectangle2D safeAnchor(XSLFShape shape) {
        try {
            return shape.getAnchor();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 그룹/다이어그램 도형 하나에서 추출한 텍스트를 {@code [label] ... [/label]} 마커로 감싸 본문에
     * 추가한다. 같은 도형에서 나온 여러 라벨(예: 조직도 부서, 프로세스 단계)이 하나의 블록으로
     * 묶여, 뒤따르는 일반 본문 텍스트와 섞이지 않고 "이 텍스트는 도형에서 뽑은 것"임이 드러난다.
     * 텍스트가 하나도 없는 도형(순수 장식 그룹 등)은 마커도 붙이지 않는다.
     *
     * <p>이 마커는 {@code Document.getText()}에 그대로 남아 검색(임베딩/FTS)·{@code /admin} 표시·
     * 답변 프롬프트에 모두 흘러간다({@code [이미지: ...]}·코드 연속 마커와 동일한 취급). {@code #}로
     * 시작하지 않으므로 {@code DocumentLoaderService.splitMarkdownBySections()}가 섹션 경계로
     * 오인하지 않고, 대괄호 라벨 줄은 {@code MarkdownNoiseNormalizer}가 장식/강조로 지우지 않는다.
     */
    private void appendShapeGroup(StringBuilder body, XSLFShapeContainer container, String label,
                                   List<String> ownedImages, Set<String> consumedImagePaths) {
        StringBuilder inner = new StringBuilder();
        appendGroupText(inner, container, new HashSet<>());
        // 텍스트 없는 도형은 이미지가 있어도 마커 블록 자체를 만들지 않는다(기존 불변식 유지) —
        // 그 이미지는 소비 처리되지 않은 채 슬라이드 상단 hoist 목록에 그대로 남는다.
        if (inner.toString().isBlank()) return;

        body.append("[").append(label).append("]\n");
        for (String path : ownedImages) {
            body.append("[이미지: ").append(path).append("]\n");
            consumedImagePaths.add(path);
        }
        body.append(stripBoldIfExcessive(inner.toString().strip())).append("\n");
        body.append("[/").append(label).append("]\n\n");
    }

    /**
     * 그룹 도형 내부를 재귀적으로 순회해 텍스트(및 중첩된 표)를 버퍼에 추가한다 — 그룹 자체는
     * 이미지로도 래스터라이즈되지만(PptxImageExtractor), Vision 설명이 없는 환경에서도 검색 가능한
     * 텍스트가 남도록 별도로 추출해 둔다. 그룹 내부 라벨을 슬라이드 제목으로 오인하지 않도록,
     * 최상위 슬라이드에서만 적용되는 헤딩 후보 승격은 여기서는 적용하지 않는다. 중첩 그룹은 별도
     * 마커로 감싸지 않고 그대로 이어붙인다 — 최상위 {@link #appendShapeGroup} 마커 하나가 도형
     * 전체를 이미 감싸므로, 안쪽까지 매번 마커를 붙이면 오히려 지저분해진다.
     *
     * <p>{@code seenShapeTexts}는 도형 하나(모든 문단을 합친 텍스트, {@link #combineShapeText})
     * 단위의 중복 판정 집합이다 — 같은 그룹(중첩 서브그룹 포함) 안에서 이미 나온 것과 내용이 완전히
     * 같은 도형은 통째로 건너뛴다({@link #appendShapeGroup}가 그룹 하나당 새 Set을 만들어 넘기므로
     * 판정 범위는 그 그룹 전체로 한정된다).
     */
    private void appendGroupText(StringBuilder body, XSLFShapeContainer container, Set<String> seenShapeTexts) {
        for (XSLFShape shape : container.getShapes()) {
            if (shape instanceof XSLFTable table) {
                appendTable(body, table);
            } else if (shape instanceof XSLFGroupShape nestedGroup) {
                appendGroupText(body, nestedGroup, seenShapeTexts);
            } else if (shape instanceof XSLFTextShape textShape) {
                String combined = combineShapeText(textShape);
                if (combined.isBlank()) continue;
                if (!seenShapeTexts.add(normalizeForDedup(combined))) continue; // 그룹 내 다른 도형과 내용 중복 — 스킵

                appendShapeTextBlock(body, textShape);
            }
        }
    }

    /** 도형 하나에 속한 모든 문단을 공백으로 이어붙인다 — 도형 단위 중복 비교를 위한 텍스트 뭉치({@link #tableCellText}와 동일한 접근). */
    private String combineShapeText(XSLFTextShape textShape) {
        StringBuilder out = new StringBuilder();
        for (XSLFTextParagraph para : textShape.getTextParagraphs()) {
            String text = paragraphText(para).trim();
            if (text.isEmpty()) continue;
            if (!out.isEmpty()) out.append(" ");
            out.append(text);
        }
        return out.toString();
    }

    /** 강조 마커·공백 차이를 무시하고 내용만 비교하기 위한 정규화 ({@link #appendGroupText}·본문 연속중복 제거 공용). */
    private String normalizeForDedup(String text) {
        return stripEmphasisMarkers(text).trim().replaceAll("\\s+", " ");
    }

    /**
     * 그룹 내부 텍스트 한 줄을 렌더링한다. 불릿은 {@link #appendBodyLine}과 동일하게 들여쓰기+마커를
     * 붙이고, 비불릿 일반 텍스트는 그대로 둔다. 줄 사이는 문단 분리({@code \n\n}) 대신 한 줄 개행
     * ({@code \n})으로 촘촘하게 이어붙인다 — 도형 내부 라벨들은 서로 관계있는 짧은 항목(노드·단계)이
     * 대부분이라, 마커 블록 안에서 빈 줄로 벌어지지 않고 하나로 묶여 보이는 편이 낫다.
     */
    private String renderGroupLine(XSLFTextParagraph para, String text) {
        if (!para.isBullet()) return text;
        String indent = "  ".repeat(Math.max(0, para.getIndentLevel()));
        String marker = para.getAutoNumberingScheme() != null ? "1." : "-";
        return indent + marker + " " + text;
    }

    /**
     * 도형 하나의 텍스트를 버퍼에 추가한다. 줄이 하나뿐이면 {@link #renderGroupLine} 결과를 그대로
     * (짧은 노드/단계 라벨이 대부분이라 촘촘하게 이어붙이는 기존 동작 유지). <b>여러 줄</b>이면
     * 도형 하나가 곧 하나의 덩어리이므로 아래처럼 블록으로 묶어, 인접한 다른 도형의 라벨과
     * 뒤섞여 한 문단으로 읽히지 않게 한다:
     * <ul>
     *   <li>{@link #looksLikeCodeBlock}가 코드로 판정하면 앞뒤에 {@code ```} 펜스를 두른다 —
     *       펜스 안은 {@code MarkdownNoiseNormalizer}가 손대지 않고
     *       {@code DocumentLoaderService.splitMarkdownBySections()}도 구조로 파싱하지 않아,
     *       들여쓰기·{@code ---} 같은 줄이 장식으로 오인돼 지워지는 일이 없다. 펜스 안에는 강조
     *       마커가 없는 원문({@link #rawParagraphText})을 넣어 코드가 그대로 재현되게 한다.</li>
     *   <li>코드가 아니면 앞뒤에 빈 줄만 넣어 하나의 문단 블록으로 분리한다(줄 사이는 기존처럼
     *       한 줄 개행 유지).</li>
     * </ul>
     */
    private void appendShapeTextBlock(StringBuilder body, XSLFTextShape textShape) {
        List<String> rendered = new ArrayList<>(); // 빈 줄 제외 — 문단 블록 경로용(기존 동작)
        List<String> raw = new ArrayList<>();      // 중간 빈 줄 보존 — 코드 펜스 경로용(원문 재현)
        boolean anyBullet = false;

        for (XSLFTextParagraph para : textShape.getTextParagraphs()) {
            String text = paragraphText(para);
            if (text.isBlank()) {
                if (!raw.isEmpty()) raw.add(""); // 선두 빈 줄은 버리고 중간 빈 줄만 남긴다
                continue;
            }
            if (para.isBullet()) anyBullet = true;
            rendered.add(renderGroupLine(para, text));
            raw.add(rawParagraphText(para).stripTrailing());
        }
        while (!raw.isEmpty() && raw.get(raw.size() - 1).isBlank()) raw.remove(raw.size() - 1);
        if (rendered.isEmpty()) return;

        if (rendered.size() == 1) {
            body.append(rendered.get(0)).append("\n"); // 단일 라벨 — 기존 동작 그대로
            return;
        }

        appendBlankLineIfNeeded(body);
        if (!anyBullet && looksLikeCodeBlock(raw)) {
            body.append("```\n");
            for (String line : raw) body.append(line).append("\n");
            body.append("```\n\n");
            return;
        }
        for (String line : rendered) body.append(line).append("\n");
        body.append("\n");
    }

    /** 버퍼가 이미 빈 줄로 끝나 있지 않으면 빈 줄 하나를 만들어 준다(선두에서는 아무것도 하지 않음). */
    private void appendBlankLineIfNeeded(StringBuilder body) {
        if (body.isEmpty()) return;
        if (body.charAt(body.length() - 1) != '\n') {
            body.append("\n\n");
        } else if (body.length() >= 2 && body.charAt(body.length() - 2) != '\n') {
            body.append("\n");
        }
    }

    // ── 도형 여러 줄 텍스트의 코드 여부 판정 ────────────────────────────────────────────────
    /** 목록 표시로 시작하는 줄 — 하나라도 있으면 코드가 아니라 목록으로 본다(요구사항). */
    private static final Pattern BULLET_LINE = Pattern.compile("^\\s*(?:[-*+•·‧]\\s+|\\d+[.)]\\s+)");
    /**
     * 한 줄이 '코드스러운지' 보는 신호. 다이어그램 라벨에 흔한 화살표({@code ->}/{@code =>})는
     * 일부러 신호에서 뺐다 — 도형 그룹 안에서는 "요청 -> 응답" 같은 평범한 흐름 라벨이 훨씬 흔해
     * 오탐 위험이 크기 때문.
     */
    private static final Pattern CODE_LINE_SIGNAL = Pattern.compile(
            "[;{}]\\s*$"                                              // 문장/블록 종결
          + "|^\\s*(?://|/\\*|\\*/|#include\\b|#define\\b)"           // 주석/전처리기
          + "|\\b(?:public|private|protected|static|final|void|class|interface|enum"
          + "|extends|implements|import|package|return|throw|throws|new"
          + "|function|def|var|let|const|async|await|lambda|elif|except)\\b"
          + "|\\b(?:SELECT|INSERT|UPDATE|DELETE|FROM|WHERE|JOIN|VALUES)\\b"
          + "|(?:::|&&|\\|\\||!=|==|<=|>=)"
          + "|^\\s*</?[A-Za-z][\\w.:-]*(?:\\s[^>]*)?/?>\\s*$"         // XML/HTML 태그 한 줄
          + "|^\\s*[\\w.$\\[\\]]+\\s*=[^=]"                           // 대입문
    );
    /** 코드로 보려면 (빈 줄 제외) 전체 줄의 이 비율 이상이 코드 신호를 가져야 한다. */
    private static final int CODE_LINE_RATIO_PERCENT = 60;

    /**
     * 도형에서 뽑은 여러 줄 텍스트가 코드 블록인지 보수적으로 판정한다. 오탐(평문을 펜스로 감쌈)은
     * 검색·표시 품질을 해치는 반면 미탐은 기존 동작(빈 줄 블록)으로 남을 뿐이라, 애매하면 코드가
     * 아니라고 본다.
     *
     * <p><b>빈 줄은 판정에서 완전히 제외한다</b> — 분자(코드 신호 적중 줄)에도, 분모(전체 줄 수)에도
     * 세지 않는다. 코드 스니펫 중간의 논리 구분용 빈 줄이 분모만 키워 적중률을 떨어뜨리면(예: 코드
     * 3줄 + 빈 줄 2줄 → 3/5 = 60%로 아슬아슬해짐) 멀쩡한 코드가 평문으로 새기 때문. 호출자가 빈 줄을
     * 걸러 넘기든 그대로 넘기든 같은 결과가 나오도록 여기서 직접 거른다.
     *
     * <p>{@code false}로 즉시 확정하는 경우: ① 줄 하나라도 {@code -}/{@code *}/{@code 1.} 같은 목록
     * 표시로 시작(요구사항 2 — 불릿이 섞여 있으면 코드가 아니다), ② 본문에 {@code ```}가 이미 들어
     * 있어 펜스를 두르면 마크다운이 깨지는 경우, ③ 빈 줄을 뺀 내용이 한 줄 이하인 경우. 그 외에는
     * 줄별 {@link #CODE_LINE_SIGNAL} 적중률이 {@link #CODE_LINE_RATIO_PERCENT}% 이상일 때만 코드로
     * 본다(2줄짜리는 둘 다 맞아야 함).
     */
    boolean looksLikeCodeBlock(List<String> lines) {
        if (lines == null) return false;

        List<String> content = new ArrayList<>();
        for (String line : lines) {
            if (line != null && !line.isBlank()) content.add(line);
        }
        if (content.size() < 2) return false;

        for (String line : content) {
            if (line.contains("```")) return false;
            if (BULLET_LINE.matcher(line).find()) return false;
        }

        int signals = 0;
        for (String line : content) {
            if (CODE_LINE_SIGNAL.matcher(line).find()) signals++;
        }
        return signals * 100 >= content.size() * CODE_LINE_RATIO_PERCENT;
    }

    /**
     * 차트 프레임의 제목 텍스트를 본문에 추가한다. 시리즈/카테고리 값은 POI의 청크 API로
     * 안전하게 뽑아내기 어렵다(차트 종류별로 구조가 다르고, 실제 값은 캐시된 XML에 있음) — 검색
     * 가치가 큰 제목만 추출해 "완전 소실"은 막는다. 시각 자료는 {@code PptxImageExtractor}가
     * {@code mc:Fallback} 미리보기 그림을 발견하면 별도 이미지 마커로 남긴다.
     */
    private void appendChartText(StringBuilder body, XSLFGraphicFrame frame, String chartLabel,
                                  List<String> ownedImages, Set<String> consumedImagePaths) {
        XSLFChart chart = frame.getChart();
        if (chart == null) return;

        XSLFTextShape titleShape = chart.getTitleShape();
        if (titleShape == null) return;

        String title = titleShape.getText();
        if (title == null || title.isBlank()) return;

        // 차트의 mc:Fallback 미리보기 그림(있으면)을 제목 바로 앞에 배치해 상관관계를 드러낸다.
        for (String path : ownedImages) {
            body.append("[이미지: ").append(path).append("]\n");
            consumedImagePaths.add(path);
        }
        // "[차트: ...]" 라벨로 감싸 이 텍스트가 차트 제목(도형에서 추출)임을 드러낸다 — 그러지 않으면
        // 본문에 덩그러니 남은 제목이 일반 문장인지 도형 출처인지 구분되지 않는다.
        body.append("[").append(chartLabel).append(": ").append(title.trim()).append("]\n\n");
    }

    /**
     * PPTX 표를 마크다운 파이프 테이블로 변환해 본문 버퍼에 추가한다. PPTX의 표 모델은 DOCX와
     * 달리 병합된 셀도 행의 셀 목록에서 빠지지 않고 그대로 남아있으므로(각 행은 항상
     * {@code getNumberOfColumns()}개의 셀을 가짐), DOCX처럼 gridSpan을 계산해 셀 목록을 다시
     * 채워 넣을 필요가 없다 — 병합 연속 셀({@link XSLFTableCell#isMerged()})만 빈 칸으로 렌더링하면
     * 충분하다.
     */
    private void appendTable(StringBuilder body, XSLFTable table) {
        List<XSLFTableRow> rows = table.getRows();
        if (rows.isEmpty()) return;

        // Assembled into its own buffer (not appended to body directly) so stripBoldIfExcessive can
        // judge/strip bold over this table alone, not the slide text already in body.
        StringBuilder tableMd = new StringBuilder("\n");
        for (int r = 0; r < rows.size(); r++) {
            List<XSLFTableCell> cells = rows.get(r).getCells();
            if (cells.isEmpty()) continue;

            tableMd.append("|");
            for (XSLFTableCell cell : cells) {
                String text = cell.isMerged() ? "" : tableCellText(cell);
                tableMd.append(" ").append(text.replace("|", "\\|")).append(" |");
            }
            tableMd.append("\n");

            if (r == 0) {
                tableMd.append("|");
                for (int c = 0; c < cells.size(); c++) {
                    tableMd.append(" --- |");
                }
                tableMd.append("\n");
            }
        }
        tableMd.append("\n");
        body.append(stripBoldIfExcessive(tableMd.toString()));
    }

    /**
     * 표 셀 내 모든 문단을 공백으로 이어붙인다 — 파이프 표 행 내부이므로 개행을 넣을 수 없다.
     * 문단 하나 안에 줄바꿈({@code <a:br/>}, Shift+Enter)이 있으면 {@link #paragraphText}가 그
     * 자리에 리터럴 {@code "\n"}을 그대로 반환하므로(POI {@code XSLFTextRun.getRawText()}가
     * {@code CTTextLineBreak}를 {@code "\n"}으로 반환), 그걸 공백 하나로 치환해 파이프 테이블 행이
     * 여러 줄로 쪼개져 깨지는 것을 막는다.
     */
    private String tableCellText(XSLFTableCell cell) {
        StringBuilder out = new StringBuilder();
        for (XSLFTextParagraph para : cell.getTextParagraphs()) {
            String text = paragraphText(para).replaceAll("\\s*\n\\s*", " ").trim();
            if (text.isEmpty()) continue;
            if (!out.isEmpty()) out.append(" ");
            out.append(text);
        }
        return out.toString();
    }

    /** 슬라이드의 제목 이외 shape들 중 불릿 문단이 하나라도 있는지 확인한다(빈 불릿은 제외). */
    private boolean slideHasAnyBullet(XSLFSlide slide) {
        for (XSLFShape shape : slide.getShapes()) {
            if (!(shape instanceof XSLFTextShape textShape)) continue;
            Placeholder type = textShape.getTextType();
            if (isTitlePlaceholder(type) || isNoiseFooterPlaceholder(type)) continue;

            for (XSLFTextParagraph para : textShape.getTextParagraphs()) {
                if (para.isBullet() && !rawParagraphText(para).isBlank()) return true;
            }
        }
        return false;
    }

    /** 슬라이드 제목으로 이미 처리되는 placeholder 종류(별도 헤딩 추출 경로가 있음). */
    private boolean isTitlePlaceholder(Placeholder type) {
        return type == Placeholder.TITLE || type == Placeholder.CENTERED_TITLE;
    }

    /**
     * 모든 슬라이드에 반복되는 푸터성 placeholder — 본문으로 들어가면 "대외비" 같은 문구가
     * 청크마다 중복되어 임베딩 유사도를 오염시키므로 완전히 건너뛴다.
     */
    private boolean isNoiseFooterPlaceholder(Placeholder type) {
        return type == Placeholder.FOOTER || type == Placeholder.SLIDE_NUMBER || type == Placeholder.DATETIME;
    }

    /** 헤딩 후보 텍스트를 정규화한다(앞뒤 공백 제거 + 내부 연속 공백을 한 칸으로) — 제목 placeholder와
     * 승격된 텍스트박스 후보가 같은 규칙을 거치게 해, 같은 라벨이 정규화 차이로 다른 문자열로
     * 갈리며 빈도 집계({@link #calibrateHeadingOrder})가 틀어지는 것을 막는다. */
    private String normalizeHeadingText(String text) {
        return text.trim().replaceAll("\\s+", " ");
    }

    /**
     * 문단 하나를 본문 버퍼에 목록 항목 또는 일반 텍스트로 추가한다. 불릿이 자동 번호
     * ({@link XSLFTextParagraph#getAutoNumberingScheme()} != null)이면 순서형 마커("1.")를,
     * 그 외 불릿은 "-"를 사용한다 — DocxToMarkdownConverter가 numPr의 numFmt로 ordered/unordered를
     * 구분하는 것과 동일한 원칙.
     */
    private void appendBodyLine(StringBuilder body, XSLFTextParagraph para, String text) {
        if (para.isBullet()) {
            String indent = "  ".repeat(Math.max(0, para.getIndentLevel()));
            String marker = para.getAutoNumberingScheme() != null ? "1." : "-";
            body.append(indent).append(marker).append(" ").append(text).append("\n");
        } else {
            body.append(text).append("\n\n");
        }
    }

    /**
     * 문단이 헤딩 후보(제목 성격의 짧은 굵은 텍스트)로 보이는지 판정한다: 길이가
     * {@link #MAX_HEADING_CANDIDATE_LENGTH} 이하이고, 비어있지 않은 모든 run이 bold여야 한다.
     */
    private boolean looksLikeHeadingCandidate(XSLFTextParagraph para, String raw) {
        if (raw.length() > MAX_HEADING_CANDIDATE_LENGTH) return false;

        boolean anyRun = false;
        for (XSLFTextRun run : para.getTextRuns()) {
            String text = run.getRawText();
            if (text == null || text.isBlank()) continue;
            anyRun = true;
            if (!run.isBold()) return false;
        }
        return anyRun;
    }

    /**
     * 슬라이드에 헤딩 후보가 2개면, 전체 슬라이드에 걸쳐 더 자주(더 많은 슬라이드에) 등장하는
     * 텍스트를 상위(##) 헤딩으로 판단한다 — "장 제목"처럼 반복되는 텍스트가 실제로는 상위
     * 개념이고, 슬라이드마다 달라지는 텍스트가 그 하위 주제이기 때문. 빈도가 같으면 발견 순서를
     * 그대로 유지한다(제목 placeholder가 있으면 그것이 항상 먼저 발견됨).
     */
    private List<String> calibrateHeadingOrder(List<String> candidates, Map<String, Integer> headingFrequency) {
        if (candidates.size() < 2) return candidates;

        String first = candidates.get(0);
        String second = candidates.get(1);
        int freqFirst = headingFrequency.getOrDefault(first, 0);
        int freqSecond = headingFrequency.getOrDefault(second, 0);
        return freqSecond > freqFirst ? List.of(second, first) : List.of(first, second);
    }

    /**
     * 슬라이드 하나를 [페이지: N] 마커 + (있으면) 헤딩(들) + 이미지 마커 + 본문(목록/텍스트)으로
     * 출력 버퍼에 추가한다. [페이지: N] 마커 자체가 슬라이드 단위 섹션 경계라, 제목이 없는
     * 슬라이드는 헤딩 없이 마커만 붙는다(예전의 "N번 슬라이드" 폴백 헤딩은 실제 구조가 아니라
     * page_or_slide로 이미 관리되는 번호를 본문에 노이즈로 남길 뿐이라 제거됨). 헤딩도, 본문도,
     * 이미지도 없는 슬라이드(완전 공백 구분 슬라이드 등)만 아무것도 추가하지 않고 건너뛴다 — 그런
     * 슬라이드까지 마커만 붙여 청크로 만들면 내용 없는 청크가 임베딩/검색 인덱스에 그대로 남아
     * 노이즈가 된다(PdfToMarkdownConverter의 빈 페이지 스킵과 동일한 이유). 슬라이드
     * 번호(page_or_slide)는 스킵 여부와 무관하게 실제 슬라이드 순서를 그대로 유지한다.
     */
    private void appendSlide(StringBuilder sb, SlideExtract extract, int slideNum, List<String> images,
                              Map<String, Integer> headingFrequency) {
        List<String> headings = calibrateHeadingOrder(extract.headingCandidates(), headingFrequency);
        String body = extract.body();

        if (headings.isEmpty() && body.isEmpty() && images.isEmpty()) {
            return; // 헤딩·본문·이미지 모두 없음 — 의미 없는 헤딩 전용 청크를 만들지 않는다
        }

        // [페이지: N] is itself the per-slide section boundary (DocumentLoaderService splits on it),
        // so a title-less slide emits no synthetic "## N번 슬라이드" heading anymore — that heading
        // carried no real structure (the slide number is already tracked as page_or_slide) and only
        // showed up as noise in the stored/searched chunk text. Slides WITH a real title still keep
        // it as the ## (and optional ### subtitle) heading.
        sb.append("[페이지: ").append(slideNum).append("]\n");
        if (!headings.isEmpty()) {
            sb.append("## ").append(headings.get(0)).append("\n\n");
            if (headings.size() > 1) {
                sb.append("### ").append(headings.get(1)).append("\n\n");
            }
        }
        for (String path : images) {
            sb.append("[이미지: ").append(path).append("]\n");
        }
        if (!images.isEmpty()) {
            sb.append("\n");
        }
        sb.append(stripLeadingDuplicateBullet(body, headings));
        sb.append("\n");
    }

    // ── 중복/목차 슬라이드 제거 (app.pptx-remove-duplicate-slides) ──────────────────────
    // 목차형 슬라이드로 판정하려면 본문 줄이 최소 이만큼 다른 슬라이드의 제목과 일치해야 한다.
    private static final int TOC_MIN_MATCHED_HEADINGS = 3;
    // 그리고 그 일치 줄이 본문 전체 줄의 이 비율 이상이어야 한다(둘 다 만족해야 목차로 봄).
    private static final double TOC_MATCH_RATIO = 0.6;

    /**
     * 이미지가 없는 슬라이드에 한해, (1) 앞서 나온 슬라이드와 정규화 텍스트가 완전히 같으면(중복)
     * 또는 (2) 본문이 다른 슬라이드 제목들의 목록에 가까우면(목차형) {@code true}. 이미지가 있는
     * 슬라이드는 절대 제거하지 않는다 — 추출된 이미지가 고아가 되고, 텍스트가 같아도 사진이 다르면
     * 진짜 중복이 아니기 때문. 지문은 유지/제거 여부와 무관하게 {@code seenFingerprints}에 등록되어
     * 뒤따르는 동일 슬라이드가 (1)로 걸리게 한다.
     */
    private boolean shouldDropSlide(SlideExtract extract, boolean hasImages,
                                    Set<String> seenFingerprints, Set<String> allHeadingsNormalized, int slideNum) {
        if (hasImages) return false;
        String fingerprint = slideFingerprint(extract);
        if (!fingerprint.isBlank() && !seenFingerprints.add(fingerprint)) {
            log.debug("[PPTX] {}번 슬라이드 제거 — 앞선 슬라이드와 내용이 완전히 동일(중복 슬라이드)", slideNum);
            return true;
        }
        if (looksLikeTableOfContents(extract, allHeadingsNormalized)) {
            log.debug("[PPTX] {}번 슬라이드 제거 — 본문이 다른 슬라이드 제목 목록(목차형 슬라이드)", slideNum);
            return true;
        }
        return false;
    }

    /** 슬라이드의 제목 후보 + 본문을 합쳐 강조 마커·공백 차이를 무시한 비교용 지문으로 만든다. */
    private String slideFingerprint(SlideExtract extract) {
        StringBuilder sb = new StringBuilder();
        for (String h : extract.headingCandidates()) sb.append(h).append('\n');
        sb.append(extract.body());
        return normalizeForCompare(sb.toString());
    }

    /** 강조 마커 제거 + 모든 공백을 공백 하나로 축약해 정규화 — 지문·목차 판정의 공통 비교 형식. */
    private String normalizeForCompare(String s) {
        return stripEmphasisMarkers(s).replaceAll("\\s+", " ").strip();
    }

    /**
     * 본문 불릿 줄(선두 목록 마커 제거 후)이 {@code allHeadingsNormalized}(덱 전체의 다른 슬라이드
     * 제목들)와 {@link #TOC_MIN_MATCHED_HEADINGS}개 이상 그리고 전체 줄의 {@link #TOC_MATCH_RATIO}
     * 이상 일치하면 목차/agenda 슬라이드로 본다 — 섹션 제목을 나열한 항해용 슬라이드는 실제 내용이
     * 각 섹션 슬라이드에 이미 있으므로 검색 인덱스에 남길 가치가 없다. 두 임계값(절대 개수 + 비율)을
     * 모두 요구해 우연히 제목 몇 개를 언급하는 진짜 본문 슬라이드를 오탐하지 않도록 한다.
     */
    private boolean looksLikeTableOfContents(SlideExtract extract, Set<String> allHeadingsNormalized) {
        if (allHeadingsNormalized.isEmpty()) return false;
        List<String> lines = new ArrayList<>();
        for (String raw : extract.body().split("\n", -1)) {
            String t = raw.strip();
            if (t.isEmpty()) continue;
            if (t.startsWith("- ")) t = t.substring(2);
            else if (t.startsWith("1. ")) t = t.substring(3);
            lines.add(normalizeForCompare(t));
        }
        if (lines.size() < TOC_MIN_MATCHED_HEADINGS) return false;
        long matched = lines.stream().filter(allHeadingsNormalized::contains).count();
        return matched >= TOC_MIN_MATCHED_HEADINGS && matched >= Math.ceil(TOC_MATCH_RATIO * lines.size());
    }

    // ── 구분용 제목만 있는 섹션 구분 슬라이드 제거 (app.pptx-drop-divider-slides) ──────────
    // "3장", "PART 2", "제1절", "STEP 3", "II.", "1)", "부록 A" 등 번호/라벨형 섹션 표지 패턴.
    private static final java.util.regex.Pattern SECTION_LABEL = java.util.regex.Pattern.compile(
            "^(?:제\\s*)?\\d+\\s*(?:장|절|부|편|과|강)"
          + "|(?i)^(?:part|chapter|step|section|phase|unit|lesson)\\s*(?:\\d+|[ivxlcm]+)"
          + "|^[IVXLC]+\\s*[.)]"
          + "|^\\d+\\s*[.)]"
          + "|(?i)^(?:부록|appendix)\\b");
    // 제목이 이 단어(정규화 후) 자체이면 섹션 표지로 본다 — "요"로 끝나 문장 종결로 오인되기 쉬운
    // "개요" 같은 것을 확실히 잡기 위한 명시적 목록.
    private static final Set<String> DIVIDER_KEYWORDS = Set.of(
            "목차", "차례", "개요", "서론", "본론", "결론", "요약", "정리", "부록",
            "agenda", "overview", "summary", "contents", "index", "introduction", "conclusion", "appendix", "toc");
    // 번호/라벨/키워드에 해당하지 않는 제목은 "짧은 명사구"일 때만 구분용으로 본다.
    private static final int DIVIDER_MAX_WORDS = 3;
    private static final int DIVIDER_MAX_CHARS = 12;
    // 주어/목적어 조사가 붙어 있으면(예: "가치는", "만족을") 명사구가 아니라 문장/키 메시지로 보고 유지.
    private static final java.util.regex.Pattern CLAUSE_PARTICLE =
            java.util.regex.Pattern.compile("(?:은|는|이|가|을|를)(?:\\s|$)");
    // 서술어 종결(문장) — 이런 제목은 키 메시지일 수 있어 제거하지 않는다.
    private static final java.util.regex.Pattern SENTENCE_ENDING =
            java.util.regex.Pattern.compile("(?:니다|습니다|합니다|입니다|됩니다|세요|해요|어요|아요|에요|예요|네요|다|자)$");

    /**
     * 본문·이미지 없이 제목(들)만 있는 슬라이드 중, 그 제목이 전부 '구분용 제목'(번호/라벨형이거나
     * 짧은 명사구)일 때만 {@code true} — 섹션 사이 표지/구분 슬라이드로, 검색에 줄 내용이 없다.
     * 문장형(키 메시지) 제목("… 합니다", "우리는 성장한다")이 하나라도 있으면 유지한다(오탐 방지).
     * 이미지가 있으면 여기서 다루지 않는다(이미지 있는 슬라이드는 제거 대상 아님).
     */
    private boolean isSectionDividerSlide(SlideExtract extract, boolean hasImages) {
        if (hasImages) return false;
        if (!extract.body().isBlank()) return false;          // 본문이 있으면 제목만 있는 게 아님
        if (extract.headingCandidates().isEmpty()) return false; // 제목이 없으면 대상 아님(마커만 있는 슬라이드)
        for (String heading : extract.headingCandidates()) {
            if (!looksLikeSectionDivider(heading)) return false; // 하나라도 구분용이 아니면(키 메시지 등) 유지
        }
        return true;
    }

    /** 제목 텍스트가 섹션 구분용 라벨/짧은 명사구인지 — 문장형(서술어 종결·조사 포함)은 제외. */
    private boolean looksLikeSectionDivider(String heading) {
        String t = stripEmphasisMarkers(heading).strip();
        if (t.isEmpty()) return false;
        if (SECTION_LABEL.matcher(t).find()) return true;
        String normalized = t.toLowerCase(java.util.Locale.ROOT);
        if (DIVIDER_KEYWORDS.contains(normalized)) return true;
        // 문장부호/서술어 종결/조사가 있으면 문장(키 메시지)로 보고 유지
        if (t.endsWith(".") || t.endsWith("!") || t.endsWith("?") || t.endsWith("…")) return false;
        if (CLAUSE_PARTICLE.matcher(t).find()) return false;
        if (SENTENCE_ENDING.matcher(t).find()) return false;
        // 남은 것은 조사·서술어 없는 명사구 — 짧을 때만 구분용으로 본다
        int words = t.split("\\s+").length;
        return words <= DIVIDER_MAX_WORDS && t.length() <= DIVIDER_MAX_CHARS;
    }

    // ── 예고 제목 슬라이드 제거 (app.pptx-drop-redundant-title-slides) ─────────────────────

    /**
     * 이미지·도형(표/그룹/다이어그램/차트 텍스트는 전부 {@link SlideExtract#body()}로 들어가므로
     * 본문 없음이 곧 "추가 정보 없음"이다) 없이 짧은 제목 한 줄만 있는 슬라이드 중, 그 제목이 바로
     * 다음 슬라이드의 내용(제목+본문)에 그대로 포함되어 있으면 {@code true} — 다음 슬라이드가 같은
     * 제목을 헤딩으로 다시 쓰며 실제 내용을 담는 흔한 "예고" 패턴으로, 앞 슬라이드는 실질적으로
     * 빈 예고편이라 검색 인덱스에 남길 가치가 없다. 마지막 슬라이드(다음이 없음)는 항상 유지된다.
     * 정규화된 제목이 1글자뿐이면(우연한 부분 일치 위험) 대상에서 제외한다.
     */
    private boolean isRedundantTitlePreviewSlide(SlideExtract extract, boolean hasImages, SlideExtract nextExtract) {
        if (hasImages) return false;
        if (!extract.body().isBlank()) return false;
        if (extract.headingCandidates().isEmpty()) return false;
        if (nextExtract == null) return false;

        String nextText = normalizeForCompare(
                String.join(" ", nextExtract.headingCandidates()) + " " + nextExtract.body());
        if (nextText.isBlank()) return false;

        for (String heading : extract.headingCandidates()) {
            String normalizedHeading = normalizeForCompare(heading);
            if (normalizedHeading.length() >= 2 && nextText.contains(normalizedHeading)) {
                return true;
            }
        }
        return false;
    }

    // ── 마지막 종료 슬라이드 제거 (app.pptx-drop-ending-slide) ─────────────────────────────
    // 정규화(공백/구두점 제거 + 소문자화) 후 이 값들 중 하나를 포함하면 종료 표시로 본다.
    private static final Set<String> ENDING_MARKERS =
            Set.of("끝", "end", "theend", "감사합니다", "thankyou");
    // 종료 표시를 뺀 나머지 글자가 이 값 이하이면(인사말 뒤 짧은 서명 등) 여전히 종료 슬라이드로 본다.
    private static final int ENDING_SLIDE_MAX_EXTRA_CHARS = 10;

    /**
     * 덱의 마지막 슬라이드이고, 이미지 없이 내용 전체(제목+본문)를 정규화했을 때 '끝'/'END'/
     * 'The End'/'감사합니다'/'Thank you' 중 하나를 포함하며, 그 표시를 뺀 나머지 글자 수가
     * {@link #ENDING_SLIDE_MAX_EXTRA_CHARS} 이하이면 {@code true} — 실질적 내용이 없는 마무리
     * 슬라이드라 검색 인덱스에 남길 가치가 없다(짧은 서명·이모지 등은 허용하되, 연락처처럼 긴
     * 추가 내용이 있으면 유지). 마지막 슬라이드인지는 호출자가 먼저 판단한다.
     */
    private boolean isEndingOnlySlide(SlideExtract extract, boolean hasImages) {
        if (hasImages) return false;
        String combined = String.join(" ", extract.headingCandidates()) + " " + extract.body();
        String normalized = normalizeForEndingCompare(combined);
        if (normalized.isEmpty()) return false;

        for (String marker : ENDING_MARKERS) {
            if (normalized.contains(marker)
                    && normalized.length() - marker.length() <= ENDING_SLIDE_MAX_EXTRA_CHARS) {
                return true;
            }
        }
        return false;
    }

    /** 공백·구두점을 전부 제거하고 소문자화해 '끝'/'END'/'The End' 같은 종료 표시를 비교하기
     *  위한 정규화 — 문자(한글 포함)·숫자만 남긴다. */
    private String normalizeForEndingCompare(String text) {
        String stripped = stripEmphasisMarkers(text).toLowerCase(java.util.Locale.ROOT);
        return stripped.replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /**
     * 본문 선두의 불릿 줄들이 슬라이드 헤딩 텍스트(강조 마커 제거 후)와 정확히 같으면 그만큼
     * 제거한다. 저자가 하위 주제 제목을 콘텐츠 placeholder의 첫 불릿으로 그대로 반복 입력하는
     * 경우가 흔해, 그대로 두면 같은 텍스트가 헤딩과 본문에 중복으로 남는다. 헤딩이 ##·### 둘 다
     * 있고 본문 선두에 둘 다 반복되는 경우도 커버하도록, 슬라이드당 헤딩 개수만큼(최대
     * {@link #MAX_HEADING_CANDIDATES}개)까지 연속으로 제거를 허용한다 — 헤딩 하나당 중복 제거는
     * 최대 한 번뿐이라(집합에서 소진되면 재사용 불가), 우연히 헤딩 텍스트를 반복하는 진짜 본문
     * 내용까지 계속 지워지지는 않는다. 선두 빈 줄은 건너뛰고, 불릿이 아니거나 남은 헤딩과
     * 일치하지 않는 줄을 만나면 즉시 멈춘다.
     */
    private String stripLeadingDuplicateBullet(String body, List<String> headings) {
        if (headings.isEmpty() || body.isEmpty()) return body;

        Set<String> remainingHeadings = new HashSet<>(headings);
        List<String> lines = new ArrayList<>(List.of(body.split("\n", -1)));

        int i = 0;
        while (i < lines.size() && !remainingHeadings.isEmpty()) {
            String trimmed = lines.get(i).strip();
            if (trimmed.isEmpty()) {
                i++;
                continue;
            }

            String marker = trimmed.startsWith("- ") ? "- " : trimmed.startsWith("1. ") ? "1. " : null;
            if (marker == null) break; // first content line isn't a bullet

            String bulletText = stripEmphasisMarkers(trimmed.substring(marker.length()).strip());
            if (!remainingHeadings.remove(bulletText)) break; // doesn't match any remaining heading

            lines.remove(i); // next line shifts into this index — don't advance i
        }

        return String.join("\n", lines);
    }

    /**
     * {@code **}/{@code ***}/{@code _} 강조 마커를 제거해 순수 텍스트만 비교할 수 있게 한다.
     * {@code _} 쌍은 CommonMark의 intraword 규칙(앞뒤가 단어 문자가 아니어야 함)을 적용해, 굵게/
     * 기울임 표시가 아닌 식별자(예: {@code user_name_field})의 언더스코어를 강조 마커로 오인해
     * 뭉개지 않도록 한다 — 여는/닫는 {@code _}가 각각 좌우로 단어 문자와 붙어 있으면(intraword)
     * 매치하지 않는다.
     */
    private String stripEmphasisMarkers(String text) {
        String result = stripMarkerPattern(text, BOLD_EMPHASIS_PATTERN, 2);
        return stripMarkerPattern(result, ITALIC_EMPHASIS_PATTERN, 1);
    }

    /**
     * 슬라이드 하나의 최종 조립된 본문(본문·표·그룹 텍스트가 전부 합쳐진 뒤)에서 볼드 스팬 개수를
     * 세어 {@link #EXCESSIVE_BOLD_THRESHOLD}개 이상이면 전부 제거한다 — 슬라이드 전체가 볼드로
     * 처리된 경우 등, 그 정도로 과도하면 강조 표시로서 의미가 없다고 보기 때문. 이탤릭({@code _..._})은
     * 대상이 아니다.
     */
    private String stripExcessiveBold(String body) {
        Matcher matcher = BOLD_EMPHASIS_PATTERN.matcher(body);
        int count = 0;
        while (matcher.find()) count++;
        if (count < EXCESSIVE_BOLD_THRESHOLD) return body;
        return stripMarkerPattern(body, BOLD_EMPHASIS_PATTERN, 2);
    }

    /**
     * 도형 그룹/표 텍스트 하나({@link #appendShapeGroup}/{@link #appendTable} 전용, 슬라이드 전체가
     * 아님)에서 볼드 스팬 개수와 볼드로 덮인 글자 비율을 재서, 둘 중 하나라도
     * {@link #BLOCK_BOLD_COUNT_THRESHOLD}/{@link #BLOCK_BOLD_RATIO_THRESHOLD}를 넘으면 그 블록
     * 안의 {@code **}과 {@code ***} 마커를 전부 제거한다. {@link #stripExcessiveBold}(슬라이드 전체,
     * 개수만 판단)와는 독립적으로 작동 — 볼드가 도형 그룹/표 하나에만 몰려 있어서 슬라이드 전체
     * 개수는 임계값 미만인 경우도 잡아낸다.
     */
    private String stripBoldIfExcessive(String text) {
        if (text == null || text.isEmpty()) return text;
        Matcher matcher = BOLD_EMPHASIS_PATTERN.matcher(text);
        int count = 0;
        int boldChars = 0;
        while (matcher.find()) {
            count++;
            boldChars += matcher.group(2).length();
        }
        if (count == 0) return text;
        boolean excessive = count >= BLOCK_BOLD_COUNT_THRESHOLD
                || boldChars >= text.length() * BLOCK_BOLD_RATIO_THRESHOLD;
        return excessive ? stripMarkerPattern(text, BOLD_EMPHASIS_PATTERN, 2) : text;
    }

    /** 주어진 패턴의 각 매치에서 {@code contentGroup}(마커 안쪽 내용)만 남기고 마커 문자 자체는 제거한다. */
    private String stripMarkerPattern(String text, Pattern pattern, int contentGroup) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder result = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            result.append(text, last, matcher.start()).append(matcher.group(contentGroup));
            last = matcher.end();
        }
        result.append(text.substring(last));
        return result.toString();
    }

    /** run 텍스트만 이어붙인, 강조 마커 없는 순수 텍스트 — 헤딩 후보 판정/길이 측정에 사용. */
    private String rawParagraphText(XSLFTextParagraph para) {
        StringBuilder sb = new StringBuilder();
        for (XSLFTextRun run : para.getTextRuns()) {
            String text = run.getRawText();
            if (text != null) sb.append(text);
        }
        return sb.toString();
    }

    /**
     * 문단 run(텍스트/스타일)으로 인라인 마크다운 텍스트를 구성한다. 인접한 동일 스타일 run을
     * 먼저 병합한 뒤 강조 마커를 한 번만 적용해 중복 마커를 방지한다 — DocxToMarkdownConverter와
     * 동일한 접근.
     */
    private String paragraphText(XSLFTextParagraph para) {
        StringBuilder sb = new StringBuilder();
        StringBuilder pending = new StringBuilder();
        boolean pendingBold = false;
        boolean pendingItalic = false;
        boolean hasPending = false;

        for (XSLFTextRun run : para.getTextRuns()) {
            String text = run.getRawText();
            if (text == null || text.isEmpty()) continue;

            XSLFHyperlink link = run.getHyperlink();
            String url = link != null ? link.getAddress() : null;
            if (url != null && !url.isBlank()) {
                if (hasPending) {
                    sb.append(applyRunStyle(pending.toString(), pendingBold, pendingItalic));
                    pending.setLength(0);
                    hasPending = false;
                }
                sb.append("[").append(text).append("](").append(url).append(")");
                continue;
            }

            boolean bold = run.isBold();
            boolean italic = run.isItalic();
            if (hasPending && (bold != pendingBold || italic != pendingItalic)) {
                sb.append(applyRunStyle(pending.toString(), pendingBold, pendingItalic));
                pending.setLength(0);
            }
            pending.append(text);
            pendingBold = bold;
            pendingItalic = italic;
            hasPending = true;
        }
        if (hasPending) {
            sb.append(applyRunStyle(pending.toString(), pendingBold, pendingItalic));
        }
        return sb.toString();
    }

    /**
     * run의 bold/italic 스타일에 따라 마크다운 강조 마커를 적용한다. CommonMark는 강조 마커
     * 안쪽에 공백이 붙으면 강조로 파싱되지 않으므로, 앞뒤 공백은 마커 밖으로 빼낸다
     * (DocxToMarkdownConverter.applyRunStyle()과 동일).
     */
    private String applyRunStyle(String text, boolean bold, boolean italic) {
        if (!bold && !italic) return text;

        int start = 0;
        int end = text.length();
        while (start < end && Character.isWhitespace(text.charAt(start))) start++;
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
        if (start == end) return text;

        String lead = text.substring(0, start);
        String core = text.substring(start, end);
        String trail = text.substring(end);
        String marker = bold && italic ? "***" : bold ? "**" : "_";
        return lead + marker + core + marker + trail;
    }

    /** 문서 제목을 core properties에서 우선 조회하고, 없으면 파일명 기반 제목으로 대체한다. */
    private String resolveDocumentTitle(XMLSlideShow pptx, Path pptxPath) {
        try {
            String fromCore = pptx.getProperties().getCoreProperties().getTitle();
            if (fromCore != null && !fromCore.isBlank()) {
                return fromCore.trim().replaceAll("\\s+", " ");
            }
        } catch (Exception ignored) {
            // core properties를 사용할 수 없으면 파일명 기반 제목으로 대체한다.
        }
        return titleFromFilename(pptxPath);
    }

    /** 확장자/날짜 토큰/구분자를 제거해 파일명에서 읽기 쉬운 제목을 생성한다. */
    private String titleFromFilename(Path pptxPath) {
        String file = pptxPath.getFileName() != null ? pptxPath.getFileName().toString() : "Document";
        String noExt = file.replaceFirst("\\.[^.]+$", "");

        String cleaned = noExt.replace('_', ' ');
        cleaned = DATE_TOKEN_PATTERN.matcher(cleaned).replaceAll(" ");
        cleaned = cleaned.replaceAll("[-()\\[\\]]+", " ");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();

        if (!cleaned.isBlank()) return cleaned;

        String fallback = noExt.replace('_', ' ').replaceAll("\\s+", " ").trim();
        return fallback.isBlank() ? "Document" : fallback;
    }
}
