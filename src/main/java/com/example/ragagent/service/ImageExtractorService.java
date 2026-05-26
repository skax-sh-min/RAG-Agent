package com.example.ragagent.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Orchestrates image extraction for PPTX and PDF.
 * DOCX images are handled inline by DocxToMarkdownConverter — do not pass DOCX here.
 */
@Service
public class ImageExtractorService {

    private final PptxImageExtractor pptxExtractor;
    private final PdfImageExtractor  pdfExtractor;

    public ImageExtractorService(PptxImageExtractor pptxExtractor, PdfImageExtractor pdfExtractor) {
        this.pptxExtractor = pptxExtractor;
        this.pdfExtractor  = pdfExtractor;
    }

    /**
     * @param filePath  PPTX or PDF file
     * @param docId     unique document ID
     * @param imagesDir target directory for extracted images
     * @return map from page/slide number (1-based) to relative image paths from dataDir
     */
    public Map<Integer, List<String>> extract(Path filePath, String docId, Path imagesDir)
            throws IOException {
        return extract(filePath, docId, imagesDir, null);
    }

    /**
     * Same as {@link #extract(Path, String, Path)} but calls {@code onProgress(done, total)}
     * after each page/slide is processed — PDF only (PPTX extraction is fast).
     */
    public Map<Integer, List<String>> extract(Path filePath, String docId, Path imagesDir,
                                              BiConsumer<Integer, Integer> onProgress)
            throws IOException {
        String name = filePath.getFileName().toString().toLowerCase();
        if (name.endsWith(".pptx")) return pptxExtractor.extract(filePath, docId, imagesDir);
        if (name.endsWith(".pdf"))  return pdfExtractor.extract(filePath, docId, imagesDir, onProgress);
        return Map.of();
    }
}
