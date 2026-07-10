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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts a PPTX to Markdown, one {@code [페이지: N]} + heading block per slide.
 *
 * The slide's title placeholder (TITLE/CENTERED_TITLE) becomes the primary {@code ##} heading
 * when present. Many real-world decks also carry a second, non-placeholder "title-like" text box
 * (e.g. a running chapter label plus a slide-specific subtitle) that POI can't identify via
 * {@link Placeholder} — on slides that have bulleted content further down, {@link #extractSlide}
 * additionally promotes up to one more short, fully bold, non-bulleted paragraph appearing before
 * the first bullet into a second heading candidate. Slides with no bullets at all (typical
 * cover/section-divider slides) never go through this promotion, so a short bold caption or
 * subtitle there stays plain text exactly as before. Everything else (body/content placeholders,
 * freeform text boxes, and any pre-bullet paragraph that doesn't look heading-like) is rendered as
 * plain text or, for bulleted paragraphs, a nested list line keyed off {@code getIndentLevel()}.
 * Indent depth itself is never promoted to its own heading, regardless of nesting.
 *
 * When a slide has two heading candidates, {@link #calibrateHeadingOrder} decides which is the
 * outer ({@code ##}) vs. inner ({@code ###}) heading by cross-slide frequency: whichever exact
 * text recurs on more slides reads as the higher-level (chapter/section) heading — position of
 * discovery is only a tiebreaker. This matters because a "chapter" label and its per-slide
 * subtitle can appear in either shape order depending on the deck's layout.
 *
 * Authors commonly restate a subtitle as the first bullet of its own content placeholder —
 * {@link #stripLeadingDuplicateBullet} drops that one bullet line when it exactly matches (modulo
 * emphasis markers) one of the slide's heading texts, so it isn't kept twice.
 *
 * A slide with no heading, no body text, and no extracted image (blank divider, etc.) is skipped
 * entirely — no marker, no fallback heading — so it never becomes a content-free chunk. Slide
 * numbering ({@code [페이지: N]}) is unaffected by skipped slides; it always reflects the real
 * slide index.
 *
 * Images are handled inline here, like {@link DocxToMarkdownConverter}: {@link PptxImageExtractor}
 * extracts each slide's pictures to {@code imagesDir} up front, and their relative paths are
 * emitted as {@code [이미지: ...]} markers right after the slide's heading(s). {@code loadFromMarkdown()}
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
    private static final Pattern EMPHASIS_PATTERN = Pattern.compile("(\\*\\*\\*|\\*\\*|_)(.*?)\\1");
    private static final int MAX_HEADING_CANDIDATES = 2;
    private static final int MAX_HEADING_CANDIDATE_LENGTH = 40;

    private final PptxImageExtractor imageExtractor;

    public PptxToMarkdownConverter(PptxImageExtractor imageExtractor) {
        this.imageExtractor = imageExtractor;
    }

    /** Pure per-slide extraction result: heading candidates (discovery order) + rendered body. */
    private record SlideExtract(List<String> headingCandidates, String body) {
    }

    /**
     * @param pptxPath  source PPTX file
     * @param docId     unique document ID (used to name the image subdirectory)
     * @param imagesDir directory where extracted images are saved
     * @return full markdown text with a {@code [페이지: N]}-tagged heading per slide, with
     *         {@code [이미지: ...]} markers for any pictures on that slide
     */
    public String convert(Path pptxPath, String docId, Path imagesDir) throws IOException {
        Map<Integer, List<String>> imageMap = imageExtractor.extract(pptxPath, docId, imagesDir);

        StringBuilder sb = new StringBuilder();
        try (XMLSlideShow pptx = new XMLSlideShow(Files.newInputStream(pptxPath))) {
            String title = resolveDocumentTitle(pptx, pptxPath);
            if (!title.isBlank()) {
                sb.append("# ").append(title).append("\n\n");
            }

            List<XSLFSlide> slides = pptx.getSlides();

            // Pass 1: extract each slide's heading candidates + body once, and count how many
            // distinct slides each exact heading text appears on (needed for calibration below).
            List<SlideExtract> extracts = new ArrayList<>(slides.size());
            Map<String, Integer> headingFrequency = new HashMap<>();
            for (XSLFSlide slide : slides) {
                SlideExtract extract = extractSlide(slide);
                extracts.add(extract);
                for (String heading : new LinkedHashSet<>(extract.headingCandidates())) {
                    headingFrequency.merge(heading, 1, Integer::sum);
                }
            }

            // Pass 2: emit, resolving outer/inner heading order per slide from the global counts.
            for (int i = 0; i < slides.size(); i++) {
                int slideNum = i + 1;
                List<String> images = imageMap.getOrDefault(slideNum, List.of());
                appendSlide(sb, extracts.get(i), slideNum, images, headingFrequency);
            }
        }
        return sb.toString();
    }

    /**
     * 슬라이드 하나에서 헤딩 후보(제목 placeholder + 불릿 이전에 등장하는, 짧고 전체가 굵은
     * 비불릿 문단 1개)와 본문 텍스트를 한 번에 추출한다. 헤딩 후보로 승격된 문단은 본문에서
     * 제외된다. 최대 {@link #MAX_HEADING_CANDIDATES}개까지만 승격하고, 첫 불릿을 만난 이후로는
     * 더 이상 헤딩 후보를 찾지 않는다(표지/구분 슬라이드처럼 본문이 프로즈로만 이어지는 경우
     * 오탐을 줄이기 위함).
     */
    private SlideExtract extractSlide(XSLFSlide slide) {
        List<String> headingCandidates = new ArrayList<>();
        String slideTitle = slide.getTitle();
        if (slideTitle != null && !slideTitle.isBlank()) {
            headingCandidates.add(slideTitle.trim().replaceAll("\\s+", " "));
        }

        // Only slides that actually have bulleted content further down match the "title(s) then
        // bullets" pattern this heuristic targets. Cover/section-divider slides typically have no
        // bullets at all, so skipping promotion there keeps them exactly as before (no false
        // positives from a short bold subtitle/caption that isn't meant as a heading).
        boolean slideHasBullets = slideHasAnyBullet(slide);

        StringBuilder body = new StringBuilder();
        boolean bulletSeen = false;
        for (XSLFShape shape : slide.getShapes()) {
            if (!(shape instanceof XSLFTextShape textShape)) continue;
            Placeholder type = textShape.getTextType();
            if (type == Placeholder.TITLE || type == Placeholder.CENTERED_TITLE) {
                continue; // already captured via slide.getTitle() above
            }

            for (XSLFTextParagraph para : textShape.getTextParagraphs()) {
                String raw = rawParagraphText(para).trim();
                if (raw.isBlank()) continue;

                boolean isBullet = para.isBullet();
                if (!isBullet && !bulletSeen && slideHasBullets
                        && headingCandidates.size() < MAX_HEADING_CANDIDATES
                        && looksLikeHeadingCandidate(para, raw)) {
                    headingCandidates.add(raw);
                    continue; // promoted to a heading, not body
                }

                String text = paragraphText(para);
                if (isBullet) {
                    bulletSeen = true;
                    String indent = "  ".repeat(Math.max(0, para.getIndentLevel()));
                    body.append(indent).append("- ").append(text).append("\n");
                } else {
                    body.append(text).append("\n\n");
                }
            }
        }

        return new SlideExtract(headingCandidates, body.toString());
    }

    /** 슬라이드의 제목 이외 shape들 중 불릿 문단이 하나라도 있는지 확인한다(빈 불릿은 제외). */
    private boolean slideHasAnyBullet(XSLFSlide slide) {
        for (XSLFShape shape : slide.getShapes()) {
            if (!(shape instanceof XSLFTextShape textShape)) continue;
            Placeholder type = textShape.getTextType();
            if (type == Placeholder.TITLE || type == Placeholder.CENTERED_TITLE) continue;

            for (XSLFTextParagraph para : textShape.getTextParagraphs()) {
                if (para.isBullet() && !rawParagraphText(para).isBlank()) return true;
            }
        }
        return false;
    }

    /**
     * 문단이 헤딩 후보(제목 성격의 짧은 굵은 텍스트)로 보이는지 판정한다: 길이가
     * {@link #MAX_HEADING_CANDIDATE_LENGTH} 이하이고, 비어있지 않은 모든 run이 bold여야 한다.
     */
    private boolean looksLikeHeadingCandidate(XSLFTextParagraph para, String raw) {
        if (raw.length() > MAX_HEADING_CANDIDATE_LENGTH) return false;

        boolean anyRun = false;
        for (XSLFTextRun run : para.getTextRuns()) {
            String text = run.getRawText();
            if (text == null || text.isBlank()) continue;
            anyRun = true;
            if (!run.isBold()) return false;
        }
        return anyRun;
    }

    /**
     * 슬라이드에 헤딩 후보가 2개면, 전체 슬라이드에 걸쳐 더 자주(더 많은 슬라이드에) 등장하는
     * 텍스트를 상위(##) 헤딩으로 판단한다 — "장 제목"처럼 반복되는 텍스트가 실제로는 상위
     * 개념이고, 슬라이드마다 달라지는 텍스트가 그 하위 주제이기 때문. 빈도가 같으면 발견 순서를
     * 그대로 유지한다(제목 placeholder가 있으면 그것이 항상 먼저 발견됨).
     */
    private List<String> calibrateHeadingOrder(List<String> candidates, Map<String, Integer> headingFrequency) {
        if (candidates.size() < 2) return candidates;

        String first = candidates.get(0);
        String second = candidates.get(1);
        int freqFirst = headingFrequency.getOrDefault(first, 0);
        int freqSecond = headingFrequency.getOrDefault(second, 0);
        return freqSecond > freqFirst ? List.of(second, first) : List.of(first, second);
    }

    /**
     * 슬라이드 하나를 [페이지: N] 마커 + 헤딩(들) + 이미지 마커 + 본문(목록/텍스트)으로 출력
     * 버퍼에 추가한다. 헤딩도, 본문도, 이미지도 없는 슬라이드(완전 공백 구분 슬라이드 등)만
     * 아무것도 추가하지 않고 건너뛴다 — 그런 슬라이드까지 폴백 헤딩("N번 슬라이드")만 붙여 청크로
     * 만들면 내용 없는 청크가 임베딩/검색 인덱스에 그대로 남아 노이즈가 된다(PdfToMarkdownConverter의
     * 빈 페이지 스킵과 동일한 이유). 슬라이드 번호(page_or_slide)는 스킵 여부와 무관하게 실제 슬라이드
     * 순서를 그대로 유지한다.
     */
    private void appendSlide(StringBuilder sb, SlideExtract extract, int slideNum, List<String> images,
                              Map<String, Integer> headingFrequency) {
        List<String> headings = calibrateHeadingOrder(extract.headingCandidates(), headingFrequency);
        String body = extract.body();

        if (headings.isEmpty() && body.isEmpty() && images.isEmpty()) {
            return; // 헤딩·본문·이미지 모두 없음 — 의미 없는 헤딩 전용 청크를 만들지 않는다
        }

        sb.append("[페이지: ").append(slideNum).append("]\n");
        if (headings.isEmpty()) {
            sb.append("## ").append(slideNum).append("번 슬라이드\n\n");
        } else {
            sb.append("## ").append(headings.get(0)).append("\n\n");
            if (headings.size() > 1) {
                sb.append("### ").append(headings.get(1)).append("\n\n");
            }
        }
        for (String path : images) {
            sb.append("[이미지: ").append(path).append("]\n");
        }
        if (!images.isEmpty()) {
            sb.append("\n");
        }
        sb.append(stripLeadingDuplicateBullet(body, headings));
        sb.append("\n");
    }

    /**
     * 본문의 첫 내용 줄이 불릿이면서 그 텍스트(강조 마커 제거 후)가 슬라이드 헤딩 텍스트 중
     * 하나와 정확히 같으면 그 한 줄만 제거한다. 저자가 하위 주제 제목을 콘텐츠 placeholder의
     * 첫 불릿으로 그대로 반복 입력하는 경우가 흔해, 그대로 두면 같은 텍스트가 헤딩과 본문에
     * 중복으로 남는다. 첫 줄이 불릿이 아니거나 헤딩과 다르면 본문을 그대로 반환한다.
     */
    private String stripLeadingDuplicateBullet(String body, List<String> headings) {
        if (headings.isEmpty() || body.isEmpty()) return body;

        Set<String> headingSet = new HashSet<>(headings);
        String[] lines = body.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].strip();
            if (trimmed.isEmpty()) continue;
            if (!trimmed.startsWith("- ")) return body; // first content line isn't a bullet

            String bulletText = stripEmphasisMarkers(trimmed.substring(2).strip());
            if (!headingSet.contains(bulletText)) return body;

            StringBuilder result = new StringBuilder();
            for (int j = 0; j < lines.length; j++) {
                if (j == i) continue;
                result.append(lines[j]);
                if (j < lines.length - 1) result.append("\n");
            }
            return result.toString();
        }
        return body;
    }

    /** {@code **}/{@code ***}/{@code _} 강조 마커를 제거해 순수 텍스트만 비교할 수 있게 한다. */
    private String stripEmphasisMarkers(String text) {
        Matcher matcher = EMPHASIS_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            result.append(text, last, matcher.start()).append(matcher.group(2));
            last = matcher.end();
        }
        result.append(text.substring(last));
        return result.toString();
    }

    /** run 텍스트만 이어붙인, 강조 마커 없는 순수 텍스트 — 헤딩 후보 판정/길이 측정에 사용. */
    private String rawParagraphText(XSLFTextParagraph para) {
        StringBuilder sb = new StringBuilder();
        for (XSLFTextRun run : para.getTextRuns()) {
            String text = run.getRawText();
            if (text != null) sb.append(text);
        }
        return sb.toString();
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
