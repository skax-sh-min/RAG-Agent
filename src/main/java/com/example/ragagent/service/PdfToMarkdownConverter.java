package com.example.ragagent.service;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Converts extracted PDF page text into Markdown: one {@code [페이지: N]} marker + synthetic
 * {@code ## N페이지} heading per non-blank page (N = 1-based page number, independent of whether
 * earlier pages were skipped, so numbering always matches the real PDF page).
 *
 * A synthetic per-page heading is required even though plain PDF text carries no reliable
 * structural signal of its own — {@code DocumentLoaderService.splitMarkdownBySections()} only
 * starts a new section on a heading line, so without one a whole multi-page PDF would collapse
 * into a single section and lose per-page attribution for every page after the first. Blank
 * pages are skipped entirely (no marker, no heading) so they neither waste a near-empty chunk
 * nor shift the page numbering of the pages around them.
 *
 * No further heading synthesis (e.g. from font size/layout analysis) is attempted here — plain
 * PDF text extraction has no structural signal comparably reliable to PPTX's title placeholder.
 */
@Component
public class PdfToMarkdownConverter {

    private static final Pattern DATE_TOKEN_PATTERN = Pattern.compile("\\b(?:\\d{8}|\\d{4}[-._]?\\d{2}[-._]?\\d{2})\\b");

    /**
     * @param pages   per-page text, one {@link Document} per PDF page in order (as returned by
     *                {@code DocumentLoaderService.loadPdfPagesForConversion})
     * @param pdfPath source PDF file, used only to derive the document-title fallback from its
     *                filename (no second parse of the PDF itself)
     * @return full markdown text with a {@code [페이지: N]}-tagged {@code ##} heading per non-blank page
     */
    public String convert(List<Document> pages, Path pdfPath) {
        StringBuilder sb = new StringBuilder();

        String title = titleFromFilename(pdfPath);
        if (!title.isBlank()) {
            sb.append("# ").append(title).append("\n\n");
        }

        for (int i = 0; i < pages.size(); i++) {
            String text = pages.get(i).getText();
            if (text == null || text.isBlank()) continue;

            int pageNum = i + 1;
            sb.append("[페이지: ").append(pageNum).append("]\n");
            sb.append("## ").append(pageNum).append("페이지\n\n");
            sb.append(text.strip()).append("\n\n");
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
