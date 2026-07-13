package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPrGeneral;
import org.springframework.stereotype.Component;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTNumPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts a DOCX to Markdown, extracting embedded images to imagesDir.
 *
 * Document title → # ... (core-properties title or filename fallback)
 * Heading styles → ##/###/####  |  bold/italic runs → ** / _
 * Tables → pipe-table  |  image runs → [이미지: relPath] markers
 * EMF: EmfToPngConverter (Batik) when docx-emf-convert=true, else [이미지(변환불가): ...]
 * WMF: LibreOfficeConverter when docx-wmf-convert=true, else [이미지(변환불가): ...]
 *
 * Thread-safe: opens a new XWPFDocument per call (no shared state).
 */
@Component
public class DocxToMarkdownConverter {

    private record ListInfo(BigInteger numId, int ilvl) {}
    private record TextListInfo(boolean ordered, int ilvl, String content) {}

    private static final Pattern TEXT_UNORDERED_LIST_PATTERN = Pattern.compile(
        "^([\\t ]*)([-*+\\u2022\\u25E6\\u25AA\\u25CF\\u25C6\\u25B6])\\s+(.+)$");
    private static final Pattern TEXT_ORDERED_LIST_PATTERN = Pattern.compile(
        "^([\\t ]*)(\\(?\\d+\\)|\\d+[\\.)]|[A-Za-z][\\.)]|[가-힣][\\.)]|[①-⑳])\\s+(.+)$");
    private static final Pattern DATE_TOKEN_PATTERN = Pattern.compile("\\b(?:\\d{8}|\\d{4}[-._]?\\d{2}[-._]?\\d{2})\\b");

    private final Optional<EmfToPngConverter> emfConverter;
    private final Optional<LibreOfficeConverter> wmfConverter;
    private final AppProperties props;

    /** 선택적 이미지 변환기와 애플리케이션 변환 옵션을 초기화한다. */
    public DocxToMarkdownConverter(Optional<EmfToPngConverter> emfConverter,
                                   Optional<LibreOfficeConverter> wmfConverter,
                                   AppProperties props) {
        this.emfConverter = emfConverter;
        this.wmfConverter = wmfConverter;
        this.props = props;
    }

    /**
     * @param docxPath  source DOCX file
     * @param imageId   content-hash key for the images subdirectory (see DocumentIndexer.imageId)
     * @param imagesDir directory where extracted images are saved
     * @return full markdown text with [이미지: ...] markers for embedded images
     */
    public String convert(Path docxPath, String imageId, Path imagesDir) throws IOException {
        Files.createDirectories(imagesDir);
        StringBuilder sb = new StringBuilder();
        int[] imgCounter = {0};
        int[] paraIdx = {0};

        try (XWPFDocument docx = new XWPFDocument(Files.newInputStream(docxPath))) {
            String title = resolveDocumentTitle(docx, docxPath);
            if (!title.isBlank()) {
                sb.append("# ").append(title).append("\n\n");
            }

            for (IBodyElement elem : docx.getBodyElements()) {
                if (elem instanceof XWPFParagraph para) {
                    appendParagraph(docx, sb, para, imageId, imagesDir, imgCounter, paraIdx[0]++);
                } else if (elem instanceof XWPFTable table) {
                    appendTable(docx, sb, table, imageId, imagesDir, imgCounter, paraIdx);
                }
            }
        }
        return sb.toString();
    }

    /** 단일 문단을 제목/목록/일반 텍스트 마크다운으로 변환해 출력 버퍼에 추가한다. */
    private void appendParagraph(XWPFDocument doc, StringBuilder sb, XWPFParagraph para,
                                 String imageId, Path imagesDir,
                                 int[] imgCounter, int paraIdx) throws IOException {
        int heading = headingLevel(doc, para);
        String text = paragraphText(doc, para, imageId, imagesDir, imgCounter, paraIdx, false);
        ListInfo listInfo = resolveListInfo(doc, para);

        if (heading > 0) {
            if (!text.isBlank()) {
                sb.append("#".repeat(Math.min(heading + 1, 6)))
                        .append(" ").append(text.trim())
                        .append("\n\n");
            }
            return;
        }

        if (listInfo != null) {
            String indent = "  ".repeat(Math.max(0, listInfo.ilvl()));
            String marker = isOrderedList(doc, listInfo.numId(), listInfo.ilvl()) ? "1." : "-";
            sb.append(indent).append(marker).append(" ").append(text).append("\n");
            return;
        }

        TextListInfo textListInfo = resolveTextListInfo(para, text);
        if (textListInfo != null) {
            String indent = "  ".repeat(Math.max(0, textListInfo.ilvl()));
            String marker = textListInfo.ordered() ? "1." : "-";
            sb.append(indent).append(marker).append(" ").append(textListInfo.content()).append("\n");
            return;
        }

        if (!text.isBlank()) {
            sb.append(text).append("\n\n");
        } else {
            sb.append("\n");
        }
    }

    /**
     * 문단 run(텍스트/링크/스타일/이미지 마커)으로 인라인 마크다운 텍스트를 구성한다.
     * DOCX는 리비전/맞춤법 검사 등의 이유로 시각적으로 동일한 문구를 스타일이 같은
     * 여러 run으로 쪼개 저장하는 경우가 흔하므로, 인접한 동일 스타일 run을 먼저 병합한 뒤
     * 강조 마커를 한 번만 적용한다(그렇지 않으면 "**a****b**" 같은 중복 마커가 생김).
     * plain=true(코드블록으로 렌더링될 셀)이면 bold/italic 강조 마커를 전혀 붙이지 않는다.
     */
    private String paragraphText(XWPFDocument doc, XWPFParagraph para,
                                 String imageId, Path imagesDir,
                                 int[] imgCounter, int paraIdx, boolean plain) throws IOException {
        StringBuilder sb = new StringBuilder();
        StringBuilder pending = new StringBuilder();
        boolean pendingBold = false;
        boolean pendingItalic = false;
        boolean hasPending = false;

        for (XWPFRun run : para.getRuns()) {
            List<XWPFPicture> pics = run.getEmbeddedPictures();
            if (!pics.isEmpty()) {
                if (hasPending) {
                    sb.append(applyRunStyle(pending.toString(), pendingBold, pendingItalic));
                    pending.setLength(0);
                    hasPending = false;
                }
                for (XWPFPicture pic : pics) {
                    sb.append(extractPictureMarker(pic.getPictureData(), imageId, imagesDir, imgCounter, paraIdx));
                }
                continue;
            }

            String text = runText(run);
            if (text.isEmpty()) continue;

            if (run instanceof XWPFHyperlinkRun hyperlinkRun) {
                String url = resolveHyperlinkUrl(doc, hyperlinkRun);
                if (url != null && !url.isBlank()) {
                    if (hasPending) {
                        sb.append(applyRunStyle(pending.toString(), pendingBold, pendingItalic));
                        pending.setLength(0);
                        hasPending = false;
                    }
                    sb.append("[").append(text).append("](").append(url).append(")");
                    continue;
                }
            }

            boolean bold = !plain && run.isBold();
            boolean italic = !plain && run.isItalic();
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

    /** 내장 이미지를 추출하고 필요 시 EMF/WMF를 PNG로 변환한 뒤 이미지 마커를 반환한다. */
    private String extractPictureMarker(XWPFPictureData picData, String imageId, Path imagesDir,
                                        int[] imgCounter, int paraIdx) throws IOException {
        String ext = picData.suggestFileExtension();
        if (ext == null || ext.isBlank()) ext = "bin";
        imgCounter[0]++;

        boolean isEmf = "emf".equalsIgnoreCase(ext);
        boolean isWmf = "wmf".equalsIgnoreCase(ext);
        byte[] imageBytes = picData.getData();
        String savedExt = ext;
        boolean converted = false;

        boolean emfConvert = props.imageDescriptionSafe().docxEmfConvert();
        boolean wmfConvert = props.imageDescriptionSafe().docxWmfConvert();

        if (isEmf && emfConvert && emfConverter.isPresent()) {
            Optional<byte[]> png = emfConverter.get().convert(imageBytes);
            if (png.isPresent()) {
                imageBytes = png.get();
                savedExt = "png";
                converted = true;
            }
        } else if (isWmf && wmfConvert && wmfConverter.isPresent()) {
            Optional<byte[]> png = wmfConverter.get().convert(imageBytes, ext);
            if (png.isPresent()) {
                imageBytes = png.get();
                savedExt = "png";
                converted = true;
            }
        }

        String fileName = "d" + paraIdx + "_img" + imgCounter[0] + "." + savedExt;
        Files.write(imagesDir.resolve(fileName), imageBytes);
        String relPath = "images/" + imageId + "/" + fileName;
        boolean unconvertable = (isEmf || isWmf) && !converted;
        return unconvertable
                ? "[이미지(변환불가): " + relPath + "]"
                : "[이미지: " + relPath + "]";
    }

    /**
     * run의 bold/italic 스타일에 따라 마크다운 강조 마커를 적용한다.
     * CommonMark는 강조 마커 안쪽에 공백이 붙으면 강조로 파싱되지 않으므로,
     * 앞뒤 공백은 마커 밖으로 빼내고 순수 공백/빈 문자열에는 마커를 붙이지 않는다.
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

    /** run 내부의 모든 텍스트 세그먼트를 안전하게 이어 붙인다. */
    private String runText(XWPFRun run) {
        int size = run.getCTR().sizeOfTArray();
        if (size == 0) return "";
        if (size == 1) {
            String t = run.getText(0);
            return t != null ? t : "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < size; i++) {
            String t = run.getText(i);
            if (t != null) sb.append(t);
        }
        return sb.toString();
    }

    /** 하이퍼링크 run의 URL을 조회한다. */
    private String resolveHyperlinkUrl(XWPFDocument doc, XWPFHyperlinkRun run) {
        String id = run.getHyperlinkId();
        if (id == null) return null;
        XWPFHyperlink link = doc.getHyperlinkByID(id);
        return link != null ? link.getURL() : null;
    }

    /** DOCX 표를 마크다운 표로 변환한다(1x1 콜아웃 표는 fenced code로 처리). */
    private void appendTable(XWPFDocument doc, StringBuilder sb, XWPFTable table,
                             String imageId, Path imagesDir,
                             int[] imgCounter, int[] paraIdx) throws IOException {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) return;

        // DOCX에서 1x1 표는 코드형 콜아웃으로 자주 쓰이므로,
        // 이미지/비텍스트 요소가 없는 경우에는 fenced code로 렌더링한다.
        if (isSingleCellTable(rows)) {
            XWPFTableCell singleCell = rows.get(0).getTableCells().get(0);
            boolean textOnly = isTextOnlyCell(singleCell);
            // fenced code로 나갈 셀은 bold/italic 마커 없이 원문 그대로 담는다(plain=true).
            String code = cellContent(doc, singleCell, imageId, imagesDir, imgCounter, paraIdx, "\n", textOnly);
            if (textOnly) {
                sb.append("\n```\n").append(code).append("\n```\n\n");
            }
            else {
                sb.append("\n").append(code).append("\n\n");
            }
            return;
        }

        int maxCols = rows.stream()
                .mapToInt(this::rowColumnWidth)
                .max()
                .orElse(0);
        if (maxCols <= 0) return;

        sb.append("\n");
        for (int i = 0; i < rows.size(); i++) {
            XWPFTableRow row = rows.get(i);
            List<String> flatCells = flattenRow(doc, row, imageId, imagesDir, imgCounter, paraIdx, maxCols);

            sb.append("|");
            for (String text : flatCells) {
                sb.append(" ").append(text.replace("|", "\\|")).append(" |");
            }
            sb.append("\n");

            if (i == 0) {
                sb.append("|");
                for (int j = 0; j < maxCols; j++) {
                    sb.append(" --- |");
                }
                sb.append("\n");
            }
        }
        sb.append("\n");
    }

    /** 각 셀의 가로 grid span을 반영해 표의 유효 너비를 계산한다. */
    private int rowColumnWidth(XWPFTableRow row) {
        int width = 0;
        for (XWPFTableCell cell : row.getTableCells()) {
            width += gridSpan(cell);
        }
        return width;
    }

    /**
     * Flattens merged cells for markdown output.
     * - horizontal merge(gridSpan): first column keeps text, expanded columns are blank
     * - vertical merge(vMerge continue): blank cell
     */
    private List<String> flattenRow(XWPFDocument doc, XWPFTableRow row,
                                    String imageId, Path imagesDir,
                                    int[] imgCounter, int[] paraIdx,
                                    int maxCols) throws IOException {
        List<String> out = new java.util.ArrayList<>(maxCols);
        for (XWPFTableCell cell : row.getTableCells()) {
            int span = gridSpan(cell);
            String text = isVerticalMergeContinuation(cell)
                    ? "-"
                    : cellContent(doc, cell, imageId, imagesDir, imgCounter, paraIdx, " ", false);

            out.add(text);
            for (int i = 1; i < span; i++) out.add("-");
        }
        while (out.size() < maxCols) out.add("");
        if (out.size() > maxCols) return out.subList(0, maxCols);
        return out;
    }

    /** 표 셀의 가로 span을 반환하며, 지정되지 않았으면 기본값 1을 사용한다. */
    private int gridSpan(XWPFTableCell cell) {
        CTTcPr tcPr = cell.getCTTc() != null ? cell.getCTTc().getTcPr() : null;
        if (tcPr == null || tcPr.getGridSpan() == null || tcPr.getGridSpan().getVal() == null) return 1;
        return Math.max(1, tcPr.getGridSpan().getVal().intValue());
    }

    /** 셀이 세로 병합의 연속 셀인지 판별한다(마크다운에서 자체 내용 없음). */
    private boolean isVerticalMergeContinuation(XWPFTableCell cell) {
        CTTcPr tcPr = cell.getCTTc() != null ? cell.getCTTc().getTcPr() : null;
        if (tcPr == null || tcPr.getVMerge() == null) return false;
        if (tcPr.getVMerge().getVal() == null) return true; // <w:vMerge/> 는 연속 셀을 의미
        return tcPr.getVMerge().getVal() != STMerge.RESTART;
    }

    /** 표가 코드/콜아웃 용도의 단일 셀 블록인지 확인한다. */
    private boolean isSingleCellTable(List<XWPFTableRow> rows) {
        return rows.size() == 1 && rows.get(0).getTableCells().size() == 1;
    }

    /** 셀이 텍스트만 포함하는지 확인한다(이미지/드로잉/중첩 표가 있으면 false). */
    private boolean isTextOnlyCell(XWPFTableCell cell) {
        if (!cell.getTables().isEmpty()) return false;
        for (XWPFParagraph paragraph : cell.getParagraphs()) {
            for (XWPFRun run : paragraph.getRuns()) {
                if (!run.getEmbeddedPictures().isEmpty()) return false;
                if (run.getCTR().sizeOfDrawingArray() > 0) return false;
            }
        }
        return true;
    }

    /**
     * 셀 내부 모든 문단을 변환해 구분자로 연결한 단일 마크다운 문자열로 만든다.
     * plain=true이면 bold/italic 강조 마커 없이 원문 텍스트만 담는다(fenced code 셀용).
     */
    private String cellContent(XWPFDocument doc, XWPFTableCell cell,
                               String imageId, Path imagesDir,
                               int[] imgCounter, int[] paraIdx,
                               String separator, boolean plain) throws IOException {
        StringBuilder out = new StringBuilder();
        for (XWPFParagraph p : cell.getParagraphs()) {
            String line = paragraphText(doc, p, imageId, imagesDir, imgCounter, paraIdx[0]++, plain).trim();
            if (line.isEmpty()) continue;

            ListInfo li = resolveListInfo(doc, p);
            if (li != null) {
                String indent = "  ".repeat(Math.max(0, li.ilvl()));
                String marker = isOrderedList(doc, li.numId(), li.ilvl()) ? "1." : "-";
                line = indent + marker + " " + line;
            } else {
                TextListInfo textLi = resolveTextListInfo(p, line);
                if (textLi != null) {
                    String indent = "  ".repeat(Math.max(0, textLi.ilvl()));
                    String marker = textLi.ordered() ? "1." : "-";
                    line = indent + marker + " " + textLi.content();
                }
            }

            if (!out.isEmpty()) out.append(separator);
            out.append(line);
        }
        return out.toString();
    }

    /** DOCX 제목 스타일 메타데이터를 마크다운 제목 레벨(1..6)로 매핑한다. */
    private int headingLevel(XWPFDocument doc, XWPFParagraph para) {
        String styleId = para.getStyleID();
        if (styleId == null) return 0;

        if (styleId.matches("(?i)Heading[1-6]")) {
            return styleId.charAt(styleId.length() - 1) - '0';
        }

        XWPFStyles styles = doc.getStyles();
        if (styles == null) return 0;
        XWPFStyle style = styles.getStyle(styleId);
        if (style == null) return 0;
        String name = style.getName();
        if (name == null) return 0;
        String lower = name.toLowerCase().trim();
        if (lower.startsWith("heading ")) {
            try {
                return Math.min(Integer.parseInt(lower.substring(8).trim()), 6);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        if (lower.startsWith("제목 ")) {
            try {
                return Math.min(Integer.parseInt(lower.substring(3).trim()), 6);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    /** 지정 레벨의 번호 매기기 정의가 순서형 목록인지(불릿 아님) 판별한다. */
    private boolean isOrderedList(XWPFDocument doc, BigInteger numId, int ilvl) {
        try {
            if (numId == null) return false;
            XWPFNumbering numbering = doc.getNumbering();
            if (numbering == null) return false;
            XWPFNum num = numbering.getNum(numId);
            if (num == null) return false;
            BigInteger abstractNumId = num.getCTNum().getAbstractNumId().getVal();
            XWPFAbstractNum abstractNum = numbering.getAbstractNum(abstractNumId);
            if (abstractNum == null) return false;
            CTLvl lvl = abstractNum.getCTAbstractNum().getLvlArray(ilvl);
            if (lvl == null || lvl.getNumFmt() == null) return false;
            return !"bullet".equals(lvl.getNumFmt().getVal().toString());
        } catch (Exception e) {
            return false;
        }
    }

    /** 문단 numPr 또는 상속된 스타일 체인의 numPr에서 목록 메타데이터를 해석한다. */
    private ListInfo resolveListInfo(XWPFDocument doc, XWPFParagraph para) {
        BigInteger numId = para.getNumID();
        int ilvl = para.getNumIlvl() != null ? para.getNumIlvl().intValue() : 0;
        if (numId != null) return new ListInfo(numId, ilvl);

        CTPPr paraPPr = para.getCTP() != null ? para.getCTP().getPPr() : null;
        if (paraPPr != null && paraPPr.getNumPr() != null) {
            CTNumPr numPr = paraPPr.getNumPr();
            if (numPr.getNumId() != null && numPr.getNumId().getVal() != null) {
                BigInteger inheritedNumId = numPr.getNumId().getVal();
                int inheritedIlvl = (numPr.getIlvl() != null && numPr.getIlvl().getVal() != null)
                        ? numPr.getIlvl().getVal().intValue() : ilvl;
                return new ListInfo(inheritedNumId, inheritedIlvl);
            }
        }

        XWPFStyles styles = doc.getStyles();
        if (styles == null) return null;

        String styleId = para.getStyleID();
        if (styleId == null || styleId.isBlank()) return null;

        String current = styleId;
        int hop = 0;
        while (current != null && hop++ < 12) {
            XWPFStyle style = styles.getStyle(current);
            if (style == null || style.getCTStyle() == null) break;

            CTStyle ctStyle = style.getCTStyle();
            CTPPrGeneral ppr = ctStyle.getPPr();
            if (ppr != null && ppr.getNumPr() != null) {
                CTNumPr numPr = ppr.getNumPr();
                if (numPr.getNumId() != null && numPr.getNumId().getVal() != null) {
                    BigInteger inheritedNumId = numPr.getNumId().getVal();
                    int inheritedIlvl = (numPr.getIlvl() != null && numPr.getIlvl().getVal() != null)
                            ? numPr.getIlvl().getVal().intValue() : ilvl;
                    return new ListInfo(inheritedNumId, inheritedIlvl);
                }
            }

            if (ctStyle.getBasedOn() == null || ctStyle.getBasedOn().getVal() == null) break;
            current = ctStyle.getBasedOn().getVal();
        }
        return null;
    }

    /** DOCX 번호 메타데이터가 없을 때 텍스트 기반 목록 마커를 감지한다. */
    private TextListInfo resolveTextListInfo(XWPFParagraph para, String text) {
        if (text == null || text.isBlank()) return null;
        String leadingNormalized = normalizeLeadingMarkerStyle(text);

        Matcher unordered = TEXT_UNORDERED_LIST_PATTERN.matcher(leadingNormalized);
        if (unordered.matches()) {
            int ilvl = textIndentLevel(para, unordered.group(1));
            return new TextListInfo(false, ilvl, unordered.group(3).trim());
        }

        Matcher ordered = TEXT_ORDERED_LIST_PATTERN.matcher(leadingNormalized);
        if (ordered.matches()) {
            int ilvl = textIndentLevel(para, ordered.group(1));
            return new TextListInfo(true, ilvl, ordered.group(3).trim());
        }
        return null;
    }

    /** 선행 목록 마커 주변의 인라인 강조를 제거해 마커 정규식 매칭을 안정화한다. */
    private String normalizeLeadingMarkerStyle(String text) {
        String out = text;
        out = out.replaceFirst("^(\\s*)\\*\\*([^*]{1,6})\\*\\*(\\s+)", "$1$2$3");
        out = out.replaceFirst("^(\\s*)_([^_]{1,6})_(\\s+)", "$1$2$3");
        return out;
    }

    /** 선행 공백과 문단 들여쓰기 설정을 바탕으로 목록 들여쓰기 레벨을 추정한다. */
    private int textIndentLevel(XWPFParagraph para, String leadingWhitespace) {
        int byText = 0;
        if (leadingWhitespace != null && !leadingWhitespace.isEmpty()) {
            int width = 0;
            for (int i = 0; i < leadingWhitespace.length(); i++) {
                width += leadingWhitespace.charAt(i) == '\t' ? 2 : 1;
            }
            byText = width / 2;
        }

        int byPara = 0;
        int left = Math.max(0, para.getIndentationLeft());
        if (left > 0) byPara = left / 360;

        return Math.max(byText, byPara);
    }

    /** 문서 제목을 core properties에서 우선 조회하고, 없으면 파일명 기반 제목으로 대체한다. */
    private String resolveDocumentTitle(XWPFDocument docx, Path docxPath) {
        try {
            String fromCore = docx.getProperties().getCoreProperties().getTitle();
            if (fromCore != null && !fromCore.isBlank()) {
                return fromCore.trim().replaceAll("\\s+", " ");
            }
        } catch (Exception ignored) {
            // core properties를 사용할 수 없으면 파일명 기반 제목으로 대체한다.
        }
        return titleFromFilename(docxPath);
    }

    /** 확장자/날짜 토큰/구분자를 제거해 파일명에서 읽기 쉬운 제목을 생성한다. */
    private String titleFromFilename(Path docxPath) {
        String file = docxPath.getFileName() != null ? docxPath.getFileName().toString() : "Document";
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
