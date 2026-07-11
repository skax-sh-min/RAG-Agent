package com.example.ragagent.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Orchestrates image extraction for scanned PDFs — the one format/branch that never produces a
 * markdown conversion (there's no {@code XxxToMarkdownConverter} call to hang inline extraction
 * off of, unlike every other format). DOCX, PPTX, and non-scanned PDF all extract their images
 * inline instead: {@code DocxToMarkdownConverter}/{@code PptxToMarkdownConverter}/
 * {@code PdfToMarkdownConverter} each inject their own extractor
 * ({@code PdfImageExtractor}/{@code PptxImageExtractor}) directly and call it as part of markdown
 * conversion — do not route those formats through this class.
 */
@Service
public class ImageExtractorService {

    private final PdfImageExtractor pdfExtractor;

    public ImageExtractorService(PdfImageExtractor pdfExtractor) {
        this.pdfExtractor = pdfExtractor;
    }

    /**
     * @param pdfPath   scanned PDF file
     * @param docId     unique document ID
     * @param imagesDir target directory for extracted images
     * @return map from page number (1-based) to relative image paths from dataDir
     */
    public Map<Integer, List<String>> extract(Path pdfPath, String docId, Path imagesDir)
            throws IOException {
        return extract(pdfPath, docId, imagesDir, null);
    }

    /** Same as {@link #extract(Path, String, Path)} but calls {@code onProgress(done, total)} after each page. */
    public Map<Integer, List<String>> extract(Path pdfPath, String docId, Path imagesDir,
                                              BiConsumer<Integer, Integer> onProgress)
            throws IOException {
        return pdfExtractor.extract(pdfPath, docId, imagesDir, onProgress);
    }
}
