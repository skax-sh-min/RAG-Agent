package com.example.ragagent.service;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * Converts extracted PDF page text into Markdown: one {@code [페이지: N]} marker per page with any
 * text or image (N = 1-based page number, independent of whether earlier pages were skipped, so
 * numbering always matches the real PDF page).
 *
 * The {@code [페이지: N]} marker is itself the per-page section boundary —
 * {@code DocumentLoaderService.splitMarkdownBySections()} starts a new section on it and
 * {@code MarkdownCorrectionService.splitBySections()} splits correction sections on it, so no
 * synthetic {@code ## N페이지} heading is emitted anymore. That heading carried no real structural
 * meaning (the page number is already tracked as {@code page_or_slide}) and only showed up as noise
 * in the stored/searched chunk text, the {@code /admin} chunk view, and the answer prompt. Pages
 * with neither text nor an image are skipped entirely (no marker) so they neither waste a
 * near-empty chunk nor shift the page numbering of the pages around them.
 *
 * No heading synthesis (e.g. from font size/layout analysis) is attempted here — plain PDF text
 * extraction has no structural signal comparably reliable to PPTX's title placeholder.
 *
 * Images are handled inline here, like {@link DocxToMarkdownConverter}: {@link PdfImageExtractor}
 * extracts each page's embedded images to {@code imagesDir} up front, and their relative paths
 * are emitted as {@code [이미지: ...]} markers right after the page's marker. {@code
 * loadFromMarkdown()} then promotes those markers into {@code image_paths} metadata exactly as it
 * does for DOCX — no separate metadata-attachment step is needed downstream.
 */
@Component
public class PdfToMarkdownConverter {

    private static final Pattern DATE_TOKEN_PATTERN = Pattern.compile("\\b(?:\\d{8}|\\d{4}[-._]?\\d{2}[-._]?\\d{2})\\b");

    private final PdfImageExtractor imageExtractor;

    public PdfToMarkdownConverter(PdfImageExtractor imageExtractor) {
        this.imageExtractor = imageExtractor;
    }

    /** {@link #convert(List, Path, String, Path, BiConsumer)} without a progress callback. */
    public String convert(List<Document> pages, Path pdfPath, String imageId, Path imagesDir) throws IOException {
        return convert(pages, pdfPath, imageId, imagesDir, null);
    }

    /**
     * @param pages    per-page text, one {@link Document} per PDF page in order (as returned by
     *                 {@code DocumentLoaderService.loadPdfPagesForConversion})
     * @param pdfPath  source PDF file, used to extract images and to derive the document-title
     *                 fallback from its filename
     * @param imageId  content-hash key for the images subdirectory (see DocumentIndexer.imageId)
     * @param imagesDir directory where extracted images are saved
     * @return full markdown text with a {@code [페이지: N]} marker per non-blank page (the marker
     *         is the section boundary — no synthetic heading), with {@code [이미지: ...]} markers
     *         for any images on that page
     */
    public String convert(List<Document> pages, Path pdfPath, String imageId, Path imagesDir,
                          BiConsumer<Integer, Integer> onProgress) throws IOException {
        Map<Integer, List<String>> imageMap = imageExtractor.extract(pdfPath, imageId, imagesDir, onProgress);

        StringBuilder sb = new StringBuilder();
        String title = titleFromFilename(pdfPath);
        if (!title.isBlank()) {
            sb.append("# ").append(title).append("\n\n");
        }

        for (int i = 0; i < pages.size(); i++) {
            String text = pages.get(i).getText();
            int pageNum = i + 1;
            List<String> images = imageMap.getOrDefault(pageNum, List.of());
            boolean hasText = text != null && !text.isBlank();
            if (!hasText && images.isEmpty()) continue; // 텍스트도 이미지도 없는 페이지 — 건너뜀

            sb.append("[페이지: ").append(pageNum).append("]\n");
            for (String path : images) {
                sb.append("[이미지: ").append(path).append("]\n");
            }
            if (!images.isEmpty()) {
                sb.append("\n");
            }
            if (hasText) {
                sb.append(text.strip()).append("\n\n");
            } else {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /** 확장자/날짜 토큰/구분자를 제거해 파일명에서 읽기 쉬운 제목을 생성한다. */
    private String titleFromFilename(Path pdfPath) {
        String file = pdfPath.getFileName() != null ? pdfPath.getFileName().toString() : "Document";
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
