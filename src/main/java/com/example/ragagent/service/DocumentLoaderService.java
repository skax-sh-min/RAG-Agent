package com.example.ragagent.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.sl.usermodel.TextShape;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import com.example.ragagent.model.MetaKey;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Loads documents from various file formats into Spring AI Document objects.
 * Supports: PDF, PPTX, DOCX, TXT, MD
 */
@Service
public class DocumentLoaderService {

    private static final Pattern IMAGE_PATH_MARKER = Pattern.compile("\\[이미지: ([^\\]]+?)]");

    private final DocxToMarkdownConverter converter;
    private final OcrService ocrService; // null when disabled

    public DocumentLoaderService(DocxToMarkdownConverter converter,
                                 Optional<OcrService> ocrServiceOpt) {
        this.converter = converter;
        this.ocrService = ocrServiceOpt.orElse(null);
    }

    public List<Document> load(Path filePath) throws IOException {
        String name = filePath.getFileName().toString().toLowerCase();
        if (name.endsWith(".pdf")) return loadPdf(filePath);
        if (name.endsWith(".pptx")) return loadPptx(filePath);
        if (name.endsWith(".docx")) return loadDocx(filePath);
        if (name.endsWith(".txt") || name.endsWith(".md")) return loadText(filePath);
        throw new IllegalArgumentException("Unsupported file type: " + name);
    }

    private List<Document> loadPdf(Path filePath) throws IOException {
        var config = PdfDocumentReaderConfig.builder()
                .withPagesPerDocument(1)
                .build();
        var reader = new PagePdfDocumentReader(new FileSystemResource(filePath.toFile()), config);
        List<Document> docs = reader.get();

        // Tag source_type: check if pages are mostly empty (scanned) → mark as ocr
        long emptyPages = docs.stream()
                .filter(d -> d.getText() == null || d.getText().trim().length() < 50)
                .count();
        boolean isScanned = emptyPages > docs.size() * 0.5;

        if (isScanned && ocrService != null) {
            return ocrWithPdfRenderer(filePath, docs);
        }

        String sourceType = isScanned ? "ocr" : "file";
        return docs.stream().map(d -> {
            Map<String, Object> meta = new HashMap<>(d.getMetadata());
            meta.put(MetaKey.SOURCE_TYPE, sourceType);
            return new Document(d.getText(), meta);
        }).toList();
    }

    private List<Document> ocrWithPdfRenderer(Path filePath, List<Document> originalDocs) throws IOException {
        List<Document> result = new ArrayList<>();
        try (PDDocument pdDoc = Loader.loadPDF(filePath.toFile())) {
            PDFRenderer renderer = new PDFRenderer(pdDoc);
            int pageCount = pdDoc.getNumberOfPages();
            for (int i = 0; i < pageCount; i++) {
                BufferedImage img = renderer.renderImageWithDPI(i, 300, ImageType.RGB);
                String text = ocrService.extractText(img);
                if (text == null || text.isBlank()) continue;
                Map<String, Object> meta = i < originalDocs.size()
                        ? new HashMap<>(originalDocs.get(i).getMetadata())
                        : new HashMap<>();
                meta.put(MetaKey.SOURCE_TYPE, "ocr");
                result.add(new Document(text.trim(), meta));
            }
        }
        return result;
    }

    private List<Document> loadPptx(Path filePath) throws IOException {
        List<Document> docs = new ArrayList<>();
        try (XMLSlideShow pptx = new XMLSlideShow(new FileInputStream(filePath.toFile()))) {
            int slideNum = 0;
            for (XSLFSlide slide : pptx.getSlides()) {
                slideNum++;
                StringBuilder text = new StringBuilder();
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof TextShape<?, ?> textShape) {
                        String t = textShape.getText();
                        if (t != null && !t.isBlank()) {
                            text.append(t).append("\n");
                        }
                    }
                }
                if (!text.isEmpty()) {
                    docs.add(new Document(text.toString(), Map.of(
                            MetaKey.SOURCE_TYPE, "ppt",
                            MetaKey.PAGE_OR_SLIDE, slideNum
                    )));
                }
            }
        }
        return docs;
    }

    /**
     * Image-aware DOCX loader: converts via DocxToMarkdownConverter,
     * then splits by headings and extracts [이미지: ...] paths into image_paths metadata.
     * Called from RagService when docId and imagesDir are available.
     * If mdOutputPath is non-null the converted Markdown is also saved there for inspection.
     */
    public List<Document> loadDocx(Path filePath, String docId, Path imagesDir,
                                   Path mdOutputPath) throws IOException {
        String md = converter.convert(filePath, docId, imagesDir);
        if (mdOutputPath != null) {
            Files.createDirectories(mdOutputPath.getParent());
            Files.writeString(mdOutputPath, md);
        }
        return splitMarkdownBySections(md).stream()
                .map(doc -> {
                    List<String> imgs = extractImagePaths(doc.getText());
                    if (imgs.isEmpty()) return doc;
                    Map<String, Object> meta = new HashMap<>(doc.getMetadata());
                    meta.put(MetaKey.IMAGE_PATHS, String.join(",", imgs));
                    return new Document(doc.getText(), meta);
                })
                .toList();
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
                            MetaKey.SOURCE_TYPE, "file", "section", sectionNum, "heading", currentHeading)));
                    current = new StringBuilder();
                    sectionNum++;
                }
                if (isHeading) currentHeading = text != null ? text : "";
                if (text != null && !text.isBlank()) current.append(text).append("\n");
            }
            if (!current.isEmpty()) {
                sections.add(new Document(current.toString().strip(), Map.of(
                        MetaKey.SOURCE_TYPE, "file", "section", sectionNum, "heading", currentHeading)));
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
        return lower.endsWith(".md") ? splitMarkdownBySections(preprocessMarkdown(content))
                : List.of(new Document(content, Map.of(MetaKey.SOURCE_TYPE, "file")));
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
        int sectionNum = 0;

        for (String line : content.split("\n", -1)) {
            if (line.startsWith("#")) {
                if (!current.isEmpty()) {
                    sections.add(new Document(current.toString().strip(), Map.of(
                            MetaKey.SOURCE_TYPE, "file", "section", sectionNum, "heading", currentHeading)));
                    current = new StringBuilder();
                    sectionNum++;
                }
                currentHeading = line.replaceFirst("^#+\\s*", "");
            }
            current.append(line).append("\n");
        }
        if (!current.isEmpty()) {
            sections.add(new Document(current.toString().strip(), Map.of(
                    MetaKey.SOURCE_TYPE, "file", "section", sectionNum, "heading", currentHeading)));
        }
        return sections.isEmpty()
                ? List.of(new Document(content, Map.of(MetaKey.SOURCE_TYPE, "file")))
                : sections;
    }
}
