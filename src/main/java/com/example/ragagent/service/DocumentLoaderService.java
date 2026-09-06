package com.example.ragagent.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import com.example.ragagent.model.MetaKey;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Loads documents from various file formats into Spring AI Document objects.
 * PDF is handled directly here (OCR path for scanned pages via {@link #loadPdfPagesForConversion}
 * / {@link #load}); DOCX/TXT/MD go through Markdown conversion + {@link #loadFromMarkdown}.
 * PPTX and non-scanned PDF are converted to Markdown upstream by {@code PptxToMarkdownConverter}/
 * {@code PdfToMarkdownConverter} before reaching {@link #loadFromMarkdown} — this class no longer
 * loads PPTX directly.
 */
@Service
public class DocumentLoaderService {

    private static final Logger log = LoggerFactory.getLogger(DocumentLoaderService.class);

    /** 스캔 문서 OCR 의 기준 해상도. 페이지가 작으면 이 값을 그대로 쓴다. */
    private static final int OCR_TARGET_DPI = 300;

    /**
     * 한 페이지를 렌더할 때 허용하는 최대 픽셀 수.
     *
     * <p>{@code renderImageWithDPI} 는 페이지 전체를 <b>한 장의</b> {@code BufferedImage} 로 만든다
     * ({@code ImageType.RGB} → 픽셀당 4바이트). 300 DPI 고정이면 그 크기가 종이 크기에 비례해
     * 자란다 — A4 는 2480×3508(약 35MB)이라 괜찮지만 <b>A0 도면은 9933×14043, 한 장이 약 560MB</b>
     * 다. 인덱싱은 백그라운드에서 도는 작업이라 그 OOM 은 사용자에게 "업로드가 실패했다"로만 보인다.
     *
     * <p>4천만 픽셀이면 A2 까지는 300 DPI 가 그대로 유지되고(A2 300DPI = 약 3,500만), A1 은 약
     * 227 DPI, A0 는 약 160 DPI 로 내려간다 — 스캔 문서 OCR 에 충분한 범위다. 상한에 닿은 최악이
     * 약 160MB.
     */
    private static final long MAX_OCR_RENDER_PIXELS = 40_000_000L;

    /**
     * 아무리 큰 페이지라도 이 아래로는 내리지 않는다 — 그 밑은 OCR 이 사실상 글자를 읽지 못해
     * 렌더링 비용만 버리는 셈이 된다. 이 하한에서 상한을 넘으려면 페이지가 6m 를 넘어야 하므로
     * (현실의 도면에는 없다) 두 제약이 실제로 충돌하지는 않는다.
     */
    private static final float MIN_OCR_DPI = 72f;

    /**
     * 이 크기의 페이지를 몇 DPI 로 렌더할 것인가 — {@link #MAX_OCR_RENDER_PIXELS} 만 보는 순수 계산.
     *
     * <p>픽셀 수는 DPI 의 <b>제곱</b>에 비례하므로(양 축이 함께 늘어난다) 축소 배율은 비율의
     * 제곱근이다. 그래서 상한을 두 배 넘긴 페이지의 DPI 는 절반이 아니라 약 0.71 배가 된다.
     *
     * @param widthPt/heightPt 페이지 크기(포인트, 1/72 인치). 0 이하면 기준 DPI 를 그대로 쓴다
     *                         (깨진 상자 때문에 해상도를 바꾸지는 않는다 — 렌더가 알아서 실패한다).
     */
    static float ocrRenderDpi(float widthPt, float heightPt) {
        if (widthPt <= 0 || heightPt <= 0) return OCR_TARGET_DPI;
        double inches = (widthPt / 72.0) * (heightPt / 72.0);
        double pixelsAtTarget = inches * OCR_TARGET_DPI * OCR_TARGET_DPI;
        if (pixelsAtTarget <= MAX_OCR_RENDER_PIXELS) return OCR_TARGET_DPI;
        double scale = Math.sqrt(MAX_OCR_RENDER_PIXELS / pixelsAtTarget);
        double exact = OCR_TARGET_DPI * scale;
        float dpi = (float) exact;
        // float 로 좁히면서 위로 반올림되면 상한을 몇 픽셀 넘긴다. 렌더 결과로는 무의미한 차이지만
        // "상한 이하"라는 계약이 흔들리므로, 그런 경우에만 한 칸 내린다.
        if (dpi > exact) dpi = Math.nextDown(dpi);
        return Math.max(MIN_OCR_DPI, dpi);
    }

    private static final Pattern IMAGE_PATH_MARKER = Pattern.compile("\\[이미지: ([^\\]]+?)]");
    private static final Pattern HEADING_PAGE_MARKER = Pattern.compile("^\\[헤딩페이지:\\s*(\\d+)]\\s*$");
    private static final Pattern PAGE_MARKER = Pattern.compile("^\\[페이지:\\s*(\\d+)]\\s*$");

    private final DocxToMarkdownConverter converter;
    private final OcrService ocrService; // null when disabled

    public DocumentLoaderService(DocxToMarkdownConverter converter,
                                 Optional<OcrService> ocrServiceOpt) {
        this.converter = converter;
        this.ocrService = ocrServiceOpt.orElse(null);
    }

    public List<Document> load(Path filePath) throws IOException {
        return load(filePath, null);
    }

    /**
     * Same as {@link #load(Path)} but calls {@code onOcrProgress(done, total)} per page
     * when the PDF requires OCR rendering (scanned document).
     */
    public List<Document> load(Path filePath, BiConsumer<Integer, Integer> onOcrProgress)
            throws IOException {
        String name = filePath.getFileName().toString().toLowerCase();
        log.debug("[LOADER] 로드 시작: {} ({}B)", filePath.getFileName(), Files.size(filePath));
        if (name.endsWith(".pdf")) return loadPdf(filePath, onOcrProgress);
        if (name.endsWith(".docx")) return loadDocx(filePath);
        if (name.endsWith(".txt") || name.endsWith(".md")) return loadText(filePath);
        throw new IllegalArgumentException("Unsupported file type: " + name);
    }

    /**
     * Per-page PDF text extracted via {@link PagePdfDocumentReader}, plus whether the PDF looks
     * scanned ({@link #isScannedByEmptyPageRatio}). No OCR is run here — callers pick between the
     * OCR path ({@link #load}) and Markdown conversion ({@code PdfToMarkdownConverter}) using
     * {@code scanned()} before committing to either.
     */
    public record PdfPages(List<Document> pages, boolean scanned) {}

    /**
     * Extracts per-page text and reports whether the PDF looks scanned, for callers that must
     * choose between the OCR path and Markdown-conversion path (e.g. {@code DocumentIndexer}
     * routing non-scanned PDFs to {@code PdfToMarkdownConverter}) without extracting twice.
     */
    public PdfPages loadPdfPagesForConversion(Path filePath) {
        List<Document> pages = extractPdfPages(filePath);
        return new PdfPages(pages, isScannedByEmptyPageRatio(pages));
    }

    private List<Document> extractPdfPages(Path filePath) {
        var config = PdfDocumentReaderConfig.builder()
                .withPagesPerDocument(1)
                .build();
        var reader = new PagePdfDocumentReader(new FileSystemResource(filePath.toFile()), config);
        return reader.get();
    }

    /** More than half the pages near-empty (&lt;50 chars) → heuristic for a scanned PDF. */
    static boolean isScannedByEmptyPageRatio(List<Document> pages) {
        long emptyPages = pages.stream()
                .filter(d -> d.getText() == null || d.getText().trim().length() < 50)
                .count();
        return emptyPages > pages.size() * 0.5;
    }

    private List<Document> loadPdf(Path filePath, BiConsumer<Integer, Integer> onOcrProgress)
            throws IOException {
        List<Document> docs = extractPdfPages(filePath);
        boolean isScanned = isScannedByEmptyPageRatio(docs);
        log.debug("[LOADER:PDF] {} → {}페이지, 스캔문서={}", filePath.getFileName(), docs.size(), isScanned);

        if (isScanned && ocrService != null) {
            log.debug("[LOADER:PDF] OCR 모드로 전환: {}", filePath.getFileName());
            return ocrWithPdfRenderer(filePath, docs, onOcrProgress);
        }

        String sourceType = isScanned ? "ocr" : "file";
        return docs.stream().map(d -> {
            Map<String, Object> meta = new HashMap<>(d.getMetadata());
            meta.put(MetaKey.SOURCE_TYPE, sourceType);
            return new Document(d.getText(), meta);
        }).toList();
    }

    private List<Document> ocrWithPdfRenderer(Path filePath, List<Document> originalDocs,
                                               BiConsumer<Integer, Integer> onProgress)
            throws IOException {
        List<Document> result = new ArrayList<>();
        try (PDDocument pdDoc = Loader.loadPDF(filePath.toFile())) {
            PDFRenderer renderer = new PDFRenderer(pdDoc);
            int pageCount = pdDoc.getNumberOfPages();
            log.debug("[LOADER:OCR] {} → {}페이지 렌더링 시작", filePath.getFileName(), pageCount);
            for (int i = 0; i < pageCount; i++) {
                // 페이지 크기에 맞춰 DPI 를 정한다 — 300 고정은 큰 도면에서 한 장이 수백 MB 다.
                PDRectangle box = pdDoc.getPage(i).getCropBox();   // 렌더가 기준으로 삼는 상자
                float dpi = ocrRenderDpi(box.getWidth(), box.getHeight());
                if (dpi < OCR_TARGET_DPI) {
                    log.info("[LOADER:OCR] 페이지 {} 이(가) 커서 해상도를 낮춥니다: {}x{}pt → {} DPI (기본 {} DPI)",
                            i + 1, Math.round(box.getWidth()), Math.round(box.getHeight()),
                            Math.round(dpi), OCR_TARGET_DPI);
                }
                BufferedImage img = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);
                String text = ocrService.extractText(img);
                if (text == null || text.isBlank()) {
                    log.debug("[LOADER:OCR] 페이지 {} 텍스트 없음, 스킵", i + 1);
                } else {
                    Map<String, Object> meta = i < originalDocs.size()
                            ? new HashMap<>(originalDocs.get(i).getMetadata())
                            : new HashMap<>();
                    meta.put(MetaKey.SOURCE_TYPE, "ocr");
                    result.add(new Document(text.trim(), meta));
                }
                if (onProgress != null) onProgress.accept(i + 1, pageCount);
            }
        }
        log.debug("[LOADER:OCR] {} 완료 → {}페이지 텍스트 추출", filePath.getFileName(), result.size());
        return result;
    }

    /**
     * Converts a DOCX file to a Markdown string via {@link DocxToMarkdownConverter}.
     * Called by RagService before optional LLM format correction.
     */
    public String convertDocxToMd(Path filePath, String imageId, Path imagesDir) throws IOException {
        log.debug("[LOADER:DOCX] MD 변환 시작: {}", filePath.getFileName());
        String md = converter.convert(filePath, imageId, imagesDir);
        log.debug("[LOADER:DOCX] MD 변환 완료: {} → {}자", filePath.getFileName(), md.length());
        return md;
    }

    /**
     * Same as {@link #loadFromMarkdown(String, boolean)} — chapter numbers computed normally
     * (real, author-written headings; see the other overload for when to pass {@code true} instead).
     */
    public List<Document> loadFromMarkdown(String md) {
        return loadFromMarkdown(md, false);
    }

    /**
     * Splits a Markdown string into section-level Spring AI Documents and extracts
     * [이미지: ...] path markers into image_paths metadata.
     * Used both after DOCX→MD conversion and during MD re-indexing.
     *
     * @param skipChapterNumbers true for a document whose ##/### headings are synthetic
     *                           per-page/per-slide labels rather than a real table-of-contents —
     *                           PPTX ({@code DocumentIndexer}'s PPTX branch: slide title/subtitle)
     *                           and non-scanned PDF (its PDF branch: {@code PdfToMarkdownConverter}
     *                           emits one synthetic {@code "## N페이지"} H2 heading per page, purely
     *                           so each page gets its own section — never a real chapter). Treating
     *                           either as real chapter structure would make every section's
     *                           {@link MetaKey#CHAPTER_NO} a near-duplicate of the page/slide number
     *                           at best, and for PDF actively WRONG at worst (a page with neither
     *                           text nor an image is skipped entirely — see
     *                           {@code PdfToMarkdownConverter} — so the heading-count-based chapter
     *                           number silently drifts from the real page number after any gap).
     *                           When true, every section's {@link MetaKey#CHAPTER_NO} stays
     *                           {@code "0"} and only {@link MetaKey#PAGE_OR_SLIDE} is meaningful.
     */
    public List<Document> loadFromMarkdown(String md, boolean skipChapterNumbers) {
        List<Document> result = splitMarkdownBySections(md, skipChapterNumbers).stream()
                .map(doc -> {
                    List<String> imgs = extractImagePaths(doc.getText());
                    if (imgs.isEmpty()) return doc;
                    Map<String, Object> meta = new HashMap<>(doc.getMetadata());
                    meta.put(MetaKey.IMAGE_PATHS, String.join(",", imgs));
                    return new Document(doc.getText(), meta);
                })
                .toList();
        long withImages = result.stream()
                .filter(d -> d.getMetadata().containsKey(MetaKey.IMAGE_PATHS))
                .count();
        log.debug("[LOADER:MD] {}자 → {}섹션 (이미지 참조 포함: {}개)", md.length(), result.size(), withImages);
        return result;
    }

    /**
     * Image-aware DOCX loader: converts via DocxToMarkdownConverter,
     * then splits by headings and extracts [이미지: ...] paths into image_paths metadata.
     * Called from RagService when imageId and imagesDir are available.
     * If mdOutputPath is non-null the converted Markdown is also saved there for inspection.
     */
    public List<Document> loadDocx(Path filePath, String imageId, Path imagesDir,
                                   Path mdOutputPath) throws IOException {
        String md = convertDocxToMd(filePath, imageId, imagesDir);
        if (mdOutputPath != null) {
            Files.createDirectories(mdOutputPath.getParent());
            Files.writeString(mdOutputPath, md);
        }
        return loadFromMarkdown(md);
    }

    private List<String> extractImagePaths(String text) {
        if (text == null) return List.of();
        List<String> paths = new ArrayList<>();
        Matcher m = IMAGE_PATH_MARKER.matcher(text);
        while (m.find()) paths.add(m.group(1));
        return paths;
    }

    private List<Document> loadDocx(Path filePath) throws IOException {
        try (XWPFDocument docx = new XWPFDocument(new FileInputStream(filePath.toFile()))) {
            List<Document> sections = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            String currentHeading = "";
            int sectionNum = 0;

            for (XWPFParagraph para : docx.getParagraphs()) {
                String style = para.getStyle();
                boolean isHeading = style != null && style.toLowerCase().startsWith("heading");
                String text = para.getText();

                if (isHeading && !current.isEmpty()) {
                    sections.add(new Document(current.toString().strip(), Map.of(
                            MetaKey.SOURCE_TYPE, "file", "section", sectionNum, MetaKey.HEADING, currentHeading)));
                    current = new StringBuilder();
                    sectionNum++;
                }
                if (isHeading) currentHeading = text != null ? text : "";
                if (text != null && !text.isBlank()) current.append(text).append("\n");
            }
            if (!current.isEmpty()) {
                sections.add(new Document(current.toString().strip(), Map.of(
                        MetaKey.SOURCE_TYPE, "file", "section", sectionNum, MetaKey.HEADING, currentHeading)));
            }

            // No headings found → return as single flat document
            if (sections.isEmpty()) {
                String flat = docx.getParagraphs().stream()
                        .map(XWPFParagraph::getText)
                        .filter(t -> t != null && !t.isBlank())
                        .collect(Collectors.joining("\n"));
                return flat.isBlank() ? List.of()
                        : List.of(new Document(flat, Map.of(MetaKey.SOURCE_TYPE, "file")));
            }
            return sections;
        }
    }

    // MD image/link preprocessing patterns
    private static final Pattern MD_URL_IMAGE   = Pattern.compile("!\\[([^\\]]*)]\\(https?://[^)]*\\)");
    private static final Pattern MD_LOCAL_IMAGE = Pattern.compile("!\\[([^\\]]*)]\\([^)]+\\)");
    private static final Pattern MD_FILE_LINK   = Pattern.compile("\\[([^\\]]*)]\\([^)]*\\.(?:pdf|docx|xlsx|pptx|doc|xls)[^)]*\\)",
            Pattern.CASE_INSENSITIVE);

    private List<Document> loadText(Path filePath) throws IOException {
        String content = Files.readString(filePath);
        String lower = filePath.getFileName().toString().toLowerCase();
        if (lower.endsWith(".md")) {
            List<Document> sections = splitMarkdownBySections(preprocessMarkdown(content), false);
            log.debug("[LOADER:MD] {} → {}섹션", filePath.getFileName(), sections.size());
            return sections;
        }
        log.debug("[LOADER:TXT] {} → {}자 단일 문서", filePath.getFileName(), content.length());
        return List.of(new Document(content, Map.of(MetaKey.SOURCE_TYPE, "file")));
    }

    /** Strips MD image/link syntax from markdown before indexing. */
    private String preprocessMarkdown(String content) {
        content = MD_URL_IMAGE.matcher(content).replaceAll("$1");         // ![alt](http://...) → alt
        content = MD_LOCAL_IMAGE.matcher(content).replaceAll("[이미지: $1]"); // ![alt](local) → [이미지: alt]
        content = MD_FILE_LINK.matcher(content).replaceAll("$1");          // [text](file.pdf) → text
        return content;
    }

    /**
     * @param skipChapterNumbers see {@link #loadFromMarkdown(String, boolean)} — true skips
     *                           chapter-number computation (kept at {@code "0"} for every section).
     */
    private List<Document> splitMarkdownBySections(String content, boolean skipChapterNumbers) {
        List<Document> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentHeading = "";
        Integer currentHeadingPage = null;
        Integer pendingHeadingPage = null;
        int currentPage = 1;
        int currentSectionPage = 1;
        int sectionNum = 0;
        boolean insideFence = false;

        // Chapter numbering (H2-H6 → "1"/"1.1"/"1.5.3", same hierarchical-counter scheme as
        // MarkdownCorrectionService.addHierarchicalHeadingNumbers()). Starts at "0" for the prologue
        // (before the first such heading) and only advances past a well-formed H2-H6 ATX heading —
        // an H1 or malformed heading doesn't touch it, the section just inherits the current value.
        int[] chapterCounters = new int[5]; // ##..###### => 5 levels
        String currentChapterNo = "0";

        for (String line : content.split("\n", -1)) {
            String trimmed = line.strip();

            // Track fenced code blocks (``` / ~~~) so their contents — e.g. shell/python
            // comments starting with '#', or lines that resemble page markers — are treated
            // as plain code, never as section headings or metadata markers.
            boolean isFenceLine = trimmed.startsWith("```") || trimmed.startsWith("~~~");
            boolean wasInsideFence = insideFence;
            if (isFenceLine) insideFence = !insideFence;
            boolean skipStructural = isFenceLine || wasInsideFence;

            if (!skipStructural) {
                Matcher genericPageMarker = PAGE_MARKER.matcher(trimmed);
                if (genericPageMarker.matches()) {
                    int page = Integer.parseInt(genericPageMarker.group(1));
                    // [페이지: N] is a hard per-page/slide section boundary. PPTX/PDF no longer emit a
                    // synthetic "## N페이지"/"## N번 슬라이드" heading, so this marker is the sole
                    // boundary for title-less pages/slides. Flush the section that just ended; a real
                    // title heading (PPTX) still follows on its own line and refines heading/section
                    // without an extra empty section (the guard below is a no-op once current is empty).
                    if (!current.isEmpty()) {
                        Integer resolvedPage = resolveSectionPage(currentHeadingPage, currentSectionPage, pendingHeadingPage);
                        sections.add(sectionDocument(current.toString().strip(), sectionNum, currentHeading,
                                resolvedPage, currentChapterNo));
                        current = new StringBuilder();
                        sectionNum++;
                        currentHeading = "";
                        currentHeadingPage = null;
                        pendingHeadingPage = null;
                    }
                    currentPage = page;
                    currentSectionPage = page;
                    currentHeadingPage = page; // a heading-less page/slide section still carries page_or_slide
                    continue; // marker itself is metadata-only, never appended to section body
                }

                Matcher pageMarker = HEADING_PAGE_MARKER.matcher(trimmed);
                if (pageMarker.matches()) {
                    pendingHeadingPage = Integer.parseInt(pageMarker.group(1));
                    continue; // marker is metadata-only, not searchable content
                }

                if (isAtxHeading(line)) {
                    if (!current.isEmpty()) {
                        Integer resolvedPage = resolveSectionPage(currentHeadingPage, currentSectionPage, pendingHeadingPage);
                        sections.add(sectionDocument(current.toString().strip(), sectionNum, currentHeading,
                                resolvedPage, currentChapterNo));
                        current = new StringBuilder();
                        sectionNum++;
                    }
                    currentHeading = line.replaceFirst("^#+\\s*", "");
                    currentHeadingPage = pendingHeadingPage != null ? pendingHeadingPage : currentPage;
                    currentSectionPage = currentHeadingPage;
                    pendingHeadingPage = null;

                    if (!skipChapterNumbers) {
                        int level = markdownHeadingLevel(line);
                        if (level >= 2 && level <= 6) {
                            int idx = level - 2;
                            chapterCounters[idx]++;
                            for (int j = idx + 1; j < chapterCounters.length; j++) chapterCounters[j] = 0;
                            currentChapterNo = buildChapterNumber(chapterCounters, idx);
                        }
                    }
                }
            }

            if (current.isEmpty()) {
                currentSectionPage = currentHeadingPage != null ? currentHeadingPage : currentPage;
            }
            current.append(line).append("\n");
        }
        if (!current.isEmpty()) {
            Integer resolvedPage = currentHeadingPage != null ? currentHeadingPage : currentSectionPage;
            sections.add(sectionDocument(current.toString().strip(), sectionNum, currentHeading,
                    resolvedPage, currentChapterNo));
        }
        return sections.isEmpty()
                ? List.of(new Document(content, Map.of(MetaKey.SOURCE_TYPE, "file", MetaKey.CHAPTER_NO, "0")))
                : sections;
    }

    /**
     * CommonMark ATX 헤딩 규칙: {@code #}가 1개 이상 이어지고, 그 뒤가 공백이거나 줄 끝이어야
     * 헤딩으로 인정한다. 이 검증이 없으면(예: 예전의 단순 {@code line.startsWith("#")}) 슬라이드/
     * 문단에 흔한 해시태그(예: "#캠페인")처럼 우연히 '#'로 시작하는 평문이 가짜 헤딩/섹션 경계로
     * 오인된다 — DOCX·PPTX 등 어느 변환기에서 나온 텍스트든 공유되는 파싱 단계라 포맷 불문 적용.
     */
    private boolean isAtxHeading(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == '#') i++;
        if (i == 0) return false;
        return i == line.length() || Character.isWhitespace(line.charAt(i));
    }

    /**
     * ATX heading level (1-6) for a line already confirmed by {@link #isAtxHeading} — bounds the
     * '#' run to 1-6 (7+ isn't a valid heading level, only used for chapter-number bookkeeping since
     * {@link #isAtxHeading} itself has no upper bound and still splits on it).
     */
    private int markdownHeadingLevel(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == '#') i++;
        if (i < 1 || i > 6) return 0;
        return i;
    }

    /** Dot-joined chapter number from hierarchical counters, e.g. counters=[1,5,3,0,0], idx=2 → "1.5.3". */
    private static String buildChapterNumber(int[] counters, int idx) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= idx; i++) {
            if (i > 0) sb.append('.');
            sb.append(Math.max(counters[i], 1));
        }
        return sb.toString();
    }

    private Integer resolveSectionPage(Integer currentHeadingPage, int currentSectionPage, Integer pendingHeadingPage) {
        if (currentHeadingPage != null) return currentHeadingPage;
        // Prologue fallback: if the first heading has an anchor, apply it to the pre-heading block.
        if (pendingHeadingPage != null) return pendingHeadingPage;
        return currentSectionPage;
    }

    private Document sectionDocument(String text, int sectionNum, String heading, Integer headingPage, String chapterNo) {
        Map<String, Object> meta = new HashMap<>();
        meta.put(MetaKey.SOURCE_TYPE, "file");
        meta.put("section", sectionNum);
        meta.put(MetaKey.HEADING, heading);
        meta.put(MetaKey.CHAPTER_NO, chapterNo);
        if (headingPage != null) {
            meta.put(MetaKey.HEADING_PAGE, headingPage);
            meta.put(MetaKey.PAGE_OR_SLIDE, headingPage);
        }
        return new Document(text, meta);
    }
}
