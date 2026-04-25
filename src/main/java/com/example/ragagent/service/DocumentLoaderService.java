package com.example.ragagent.service;

import org.apache.poi.sl.usermodel.TextShape;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Loads documents from various file formats into Spring AI Document objects.
 * Supports: PDF, PPTX, DOCX, TXT, MD
 */
@Service
public class DocumentLoaderService {

    public List<Document> load(Path filePath) throws IOException {
        String name = filePath.getFileName().toString().toLowerCase();
        if (name.endsWith(".pdf")) return loadPdf(filePath);
        if (name.endsWith(".pptx")) return loadPptx(filePath);
        if (name.endsWith(".docx")) return loadDocx(filePath);
        if (name.endsWith(".txt") || name.endsWith(".md")) return loadText(filePath);
        throw new IllegalArgumentException("Unsupported file type: " + name);
    }

    private List<Document> loadPdf(Path filePath) {
        var config = PdfDocumentReaderConfig.builder()
                .withPagesPerDocument(1)
                .build();
        var reader = new PagePdfDocumentReader(new FileSystemResource(filePath.toFile()), config);
        List<Document> docs = reader.get();

        // Tag source_type: check if pages are mostly empty (scanned) → mark as ocr
        long emptyPages = docs.stream()
                .filter(d -> d.getText() == null || d.getText().trim().length() < 50)
                .count();
        String sourceType = (emptyPages > docs.size() * 0.5) ? "ocr" : "file";

        return docs.stream().map(d -> {
            Map<String, Object> meta = new HashMap<>(d.getMetadata());
            meta.put("source_type", sourceType);
            return new Document(d.getText(), meta);
        }).toList();
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
                            "source_type", "ppt",
                            "page_or_slide", slideNum
                    )));
                }
            }
        }
        return docs;
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
                            "source_type", "file", "section", sectionNum, "heading", currentHeading)));
                    current = new StringBuilder();
                    sectionNum++;
                }
                if (isHeading) currentHeading = text != null ? text : "";
                if (text != null && !text.isBlank()) current.append(text).append("\n");
            }
            if (!current.isEmpty()) {
                sections.add(new Document(current.toString().strip(), Map.of(
                        "source_type", "file", "section", sectionNum, "heading", currentHeading)));
            }

            // No headings found → return as single flat document
            if (sections.isEmpty()) {
                String flat = docx.getParagraphs().stream()
                        .map(XWPFParagraph::getText)
                        .filter(t -> t != null && !t.isBlank())
                        .collect(Collectors.joining("\n"));
                return flat.isBlank() ? List.of()
                        : List.of(new Document(flat, Map.of("source_type", "file")));
            }
            return sections;
        }
    }

    private List<Document> loadText(Path filePath) throws IOException {
        String content = Files.readString(filePath);
        String lower = filePath.getFileName().toString().toLowerCase();
        return lower.endsWith(".md") ? splitMarkdownBySections(content)
                : List.of(new Document(content, Map.of("source_type", "file")));
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
                            "source_type", "file", "section", sectionNum, "heading", currentHeading)));
                    current = new StringBuilder();
                    sectionNum++;
                }
                currentHeading = line.replaceFirst("^#+\\s*", "");
            }
            current.append(line).append("\n");
        }
        if (!current.isEmpty()) {
            sections.add(new Document(current.toString().strip(), Map.of(
                    "source_type", "file", "section", sectionNum, "heading", currentHeading)));
        }
        return sections.isEmpty()
                ? List.of(new Document(content, Map.of("source_type", "file")))
                : sections;
    }
}
