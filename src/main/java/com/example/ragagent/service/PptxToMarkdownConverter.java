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
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Converts a PPTX to Markdown, one {@code [페이지: N]} + {@code ## title} block per slide.
 *
 * Only the slide's title placeholder (TITLE/CENTERED_TITLE) becomes a heading — every other
 * shape (body/content/subtitle placeholders, freeform text boxes) is rendered as plain text or,
 * for bulleted paragraphs, a nested list line keyed off {@code getIndentLevel()}. Indent depth is
 * never promoted to its own heading, regardless of nesting.
 *
 * A slide with no title, no body text, and no extracted image (blank divider, etc.) is skipped
 * entirely — no marker, no fallback heading — so it never becomes a content-free chunk. Slide
 * numbering ({@code [페이지: N]}) is unaffected by skipped slides; it always reflects the real
 * slide index.
 *
 * Images are handled inline here, like {@link DocxToMarkdownConverter}: {@link PptxImageExtractor}
 * extracts each slide's pictures to {@code imagesDir} up front, and their relative paths are
 * emitted as {@code [이미지: ...]} markers right after the slide's heading. {@code loadFromMarkdown()}
 * then promotes those markers into {@code image_paths} metadata exactly as it does for DOCX — no
 * separate metadata-attachment step is needed downstream. Tables ({@code XSLFTable}) are not
 * handled — it implements {@code TableShape}, not {@code TextShape}, matching the pre-existing
 * scope of the old flat PPTX loader, which also never read table content.
 *
 * Thread-safe: opens a new {@link XMLSlideShow} per call (no shared state).
 */
@Component
public class PptxToMarkdownConverter {

    private static final Pattern DATE_TOKEN_PATTERN = Pattern.compile("\\b(?:\\d{8}|\\d{4}[-._]?\\d{2}[-._]?\\d{2})\\b");

    private final PptxImageExtractor imageExtractor;

    public PptxToMarkdownConverter(PptxImageExtractor imageExtractor) {
        this.imageExtractor = imageExtractor;
    }

    /**
     * @param pptxPath  source PPTX file
     * @param docId     unique document ID (used to name the image subdirectory)
     * @param imagesDir directory where extracted images are saved
     * @return full markdown text with a {@code [페이지: N]}-tagged {@code ##} heading per slide,
     *         with {@code [이미지: ...]} markers for any pictures on that slide
     */
    public String convert(Path pptxPath, String docId, Path imagesDir) throws IOException {
        Map<Integer, List<String>> imageMap = imageExtractor.extract(pptxPath, docId, imagesDir);

        StringBuilder sb = new StringBuilder();
        try (XMLSlideShow pptx = new XMLSlideShow(Files.newInputStream(pptxPath))) {
            String title = resolveDocumentTitle(pptx, pptxPath);
            if (!title.isBlank()) {
                sb.append("# ").append(title).append("\n\n");
            }

            int slideNum = 0;
            for (XSLFSlide slide : pptx.getSlides()) {
                slideNum++;
                List<String> images = imageMap.getOrDefault(slideNum, List.of());
                appendSlide(sb, slide, slideNum, images);
            }
        }
        return sb.toString();
    }

    /**
     * 슬라이드 하나를 [페이지: N] 마커 + 제목 헤딩 + 이미지 마커 + 본문(목록/텍스트)으로 출력
     * 버퍼에 추가한다. 제목도, 본문도, 이미지도 없는 슬라이드(완전 공백 구분 슬라이드 등)만
     * 아무것도 추가하지 않고 건너뛴다 — 그런 슬라이드까지 폴백 헤딩("N번 슬라이드")만 붙여 청크로
     * 만들면 내용 없는 청크가 임베딩/검색 인덱스에 그대로 남아 노이즈가 된다(PdfToMarkdownConverter의
     * 빈 페이지 스킵과 동일한 이유). 슬라이드 번호(page_or_slide)는 스킵 여부와 무관하게 실제 슬라이드
     * 순서를 그대로 유지한다.
     */
    private void appendSlide(StringBuilder sb, XSLFSlide slide, int slideNum, List<String> images) {
        String slideTitle = slide.getTitle();
        boolean hasTitle = slideTitle != null && !slideTitle.isBlank();

        StringBuilder body = new StringBuilder();
        for (XSLFShape shape : slide.getShapes()) {
            if (!(shape instanceof XSLFTextShape textShape)) continue;
            Placeholder type = textShape.getTextType();
            if (type == Placeholder.TITLE || type == Placeholder.CENTERED_TITLE) {
                continue; // already emitted as the slide heading below
            }

            for (XSLFTextParagraph para : textShape.getTextParagraphs()) {
                String text = paragraphText(para);
                if (text.isBlank()) continue;

                if (para.isBullet()) {
                    String indent = "  ".repeat(Math.max(0, para.getIndentLevel()));
                    body.append(indent).append("- ").append(text).append("\n");
                } else {
                    body.append(text).append("\n\n");
                }
            }
        }

        if (!hasTitle && body.isEmpty() && images.isEmpty()) {
            return; // 제목·본문·이미지 모두 없음 — 의미 없는 헤딩 전용 청크를 만들지 않는다
        }

        sb.append("[페이지: ").append(slideNum).append("]\n");
        String headingText = hasTitle
                ? slideTitle.trim().replaceAll("\\s+", " ")
                : slideNum + "번 슬라이드";
        sb.append("## ").append(headingText).append("\n\n");
        for (String path : images) {
            sb.append("[이미지: ").append(path).append("]\n");
        }
        if (!images.isEmpty()) {
            sb.append("\n");
        }
        sb.append(body);
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
