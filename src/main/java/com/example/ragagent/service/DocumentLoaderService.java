package com.example.ragagent.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
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
                BufferedImage img = renderer.renderImageWithDPI(i, 300, ImageType.RGB);
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
     * Splits a Markdown string into section-level Spring AI Documents and extracts
     * [이미지: ...] path markers into image_paths metadata.
     * Used both after DOCX→MD conversion and during MD re-indexing.
     */
    public List<Document> loadFromMarkdown(String md) {
        List<Document> result = splitMarkdownBySections(md).stream()
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
            List<Document> sections = splitMarkdownBySections(preprocessMarkdown(content));
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

    private List<Document> splitMarkdownBySections(String content) {
        List<Document> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentHeading = "";
        Integer currentHeadingPage = null;
        Integer pendingHeadingPage = null;
        int currentPage = 1;
        int currentSectionPage = 1;
        int sectionNum = 0;
        boolean insideFence = false;

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
                    currentPage = Integer.parseInt(genericPageMarker.group(1));
                    if (current.isEmpty()) currentSectionPage = currentPage;
                    continue; // metadata-only marker
                }

                Matcher pageMarker = HEADING_PAGE_MARKER.matcher(trimmed);
                if (pageMarker.matches()) {
                    pendingHeadingPage = Integer.parseInt(pageMarker.group(1));
                    continue; // marker is metadata-only, not searchable content
                }

                if (isAtxHeading(line)) {
                    if (!current.isEmpty()) {
                        Integer resolvedPage = resolveSectionPage(currentHeadingPage, currentSectionPage, pendingHeadingPage);
                        sections.add(sectionDocument(current.toString().strip(), sectionNum, currentHeading, resolvedPage));
                        current = new StringBuilder();
                        sectionNum++;
                    }
                    currentHeading = line.replaceFirst("^#+\\s*", "");
                    currentHeadingPage = pendingHeadingPage != null ? pendingHeadingPage : currentPage;
                    currentSectionPage = currentHeadingPage;
                    pendingHeadingPage = null;
                }
            }

            if (current.isEmpty()) {
                currentSectionPage = currentHeadingPage != null ? currentHeadingPage : currentPage;
            }
            current.append(line).append("\n");
        }
        if (!current.isEmpty()) {
            Integer resolvedPage = currentHeadingPage != null ? currentHeadingPage : currentSectionPage;
            sections.add(sectionDocument(current.toString().strip(), sectionNum, currentHeading, resolvedPage));
        }
        return sections.isEmpty()
                ? List.of(new Document(content, Map.of(MetaKey.SOURCE_TYPE, "file")))
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

    private Integer resolveSectionPage(Integer currentHeadingPage, int currentSectionPage, Integer pendingHeadingPage) {
        if (currentHeadingPage != null) return currentHeadingPage;
        // Prologue fallback: if the first heading has an anchor, apply it to the pre-heading block.
        if (pendingHeadingPage != null) return pendingHeadingPage;
        return currentSectionPage;
    }

    private Document sectionDocument(String text, int sectionNum, String heading, Integer headingPage) {
        Map<String, Object> meta = new HashMap<>();
        meta.put(MetaKey.SOURCE_TYPE, "file");
        meta.put("section", sectionNum);
        meta.put(MetaKey.HEADING, heading);
        if (headingPage != null) {
            meta.put(MetaKey.HEADING_PAGE, headingPage);
            meta.put(MetaKey.PAGE_OR_SLIDE, headingPage);
        }
        return new Document(text, meta);
    }
}
