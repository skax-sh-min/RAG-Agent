package com.example.ragagent.service;

import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Converts a PPTX to Markdown, one {@code [페이지: N]} + {@code ## title} block per slide.
 *
 * Only the slide's title placeholder (TITLE/CENTERED_TITLE) becomes a heading — every other
 * shape (body/content/subtitle placeholders, freeform text boxes) is rendered as plain text or,
 * for bulleted paragraphs, a nested list line keyed off {@code getIndentLevel()}. Indent depth is
 * never promoted to its own heading, regardless of nesting.
 *
 * Images are not handled here: {@code ImageExtractorService}/{@code PptxImageExtractor} still
 * attach them as {@code image_paths} metadata after {@code loadFromMarkdown()}, matched by
 * {@code page_or_slide}. Tables ({@code XSLFTable}) are not handled either — it implements
 * {@code TableShape}, not {@code TextShape}, matching the pre-existing scope of the old flat
 * PPTX loader, which also never read table content.
 *
 * Thread-safe: opens a new {@link XMLSlideShow} per call (no shared state).
 */
@Component
public class PptxToMarkdownConverter {

    private static final Pattern DATE_TOKEN_PATTERN = Pattern.compile("\\b(?:\\d{8}|\\d{4}[-._]?\\d{2}[-._]?\\d{2})\\b");

    /**
     * @param pptxPath source PPTX file
     * @return full markdown text with a {@code [페이지: N]}-tagged {@code ##} heading per slide
     */
    public String convert(Path pptxPath) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (XMLSlideShow pptx = new XMLSlideShow(Files.newInputStream(pptxPath))) {
            String title = resolveDocumentTitle(pptx, pptxPath);
            if (!title.isBlank()) {
                sb.append("# ").append(title).append("\n\n");
            }

            int slideNum = 0;
            for (XSLFSlide slide : pptx.getSlides()) {
                slideNum++;
                appendSlide(sb, slide, slideNum);
            }
        }
        return sb.toString();
    }

    /** 슬라이드 하나를 [페이지: N] 마커 + 제목 헤딩 + 본문(목록/텍스트)으로 출력 버퍼에 추가한다. */
    private void appendSlide(StringBuilder sb, XSLFSlide slide, int slideNum) {
        sb.append("[페이지: ").append(slideNum).append("]\n");

        String slideTitle = slide.getTitle();
        String headingText = (slideTitle != null && !slideTitle.isBlank())
                ? slideTitle.trim().replaceAll("\\s+", " ")
                : slideNum + "번 슬라이드";
        sb.append("## ").append(headingText).append("\n\n");

        for (XSLFShape shape : slide.getShapes()) {
            if (!(shape instanceof XSLFTextShape textShape)) continue;
            Placeholder type = textShape.getTextType();
            if (type == Placeholder.TITLE || type == Placeholder.CENTERED_TITLE) {
                continue; // already emitted as the slide heading above
            }

            for (XSLFTextParagraph para : textShape.getTextParagraphs()) {
                String text = paragraphText(para);
                if (text.isBlank()) continue;

                if (para.isBullet()) {
                    String indent = "  ".repeat(Math.max(0, para.getIndentLevel()));
                    sb.append(indent).append("- ").append(text).append("\n");
                } else {
                    sb.append(text).append("\n\n");
                }
            }
        }
        sb.append("\n");
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
