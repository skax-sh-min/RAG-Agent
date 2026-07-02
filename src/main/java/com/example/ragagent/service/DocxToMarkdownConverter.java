package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPrGeneral;
import org.springframework.stereotype.Component;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTNumLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTNumPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.HashMap;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts a DOCX to Markdown, extracting embedded images to imagesDir.
 *
 * Heading styles → #/##/###  |  bold/italic runs → ** / _
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

    private final Optional<EmfToPngConverter> emfConverter;
    private final Optional<LibreOfficeConverter> wmfConverter;
    private final AppProperties props;

    public DocxToMarkdownConverter(Optional<EmfToPngConverter> emfConverter,
                                   Optional<LibreOfficeConverter> wmfConverter,
                                   AppProperties props) {
        this.emfConverter = emfConverter;
        this.wmfConverter = wmfConverter;
        this.props = props;
    }

    /**
     * @param docxPath  source DOCX file
     * @param docId     unique document ID (used to name the image subdirectory)
     * @param imagesDir directory where extracted images are saved
     * @return full markdown text with [이미지: ...] markers for embedded images
     */
    public String convert(Path docxPath, String docId, Path imagesDir) throws IOException {
        Files.createDirectories(imagesDir);
        StringBuilder sb = new StringBuilder();
        int[] imgCounter = {0};
        int[] paraIdx = {0};
        Map<String, int[]> headingCounters = new HashMap<>();
        Map<String, boolean[]> headingCounterInit = new HashMap<>();

        try (XWPFDocument docx = new XWPFDocument(Files.newInputStream(docxPath))) {
            for (IBodyElement elem : docx.getBodyElements()) {
                if (elem instanceof XWPFParagraph para) {
                    appendParagraph(docx, sb, para, docId, imagesDir, imgCounter, paraIdx[0]++,
                            headingCounters, headingCounterInit);
                } else if (elem instanceof XWPFTable table) {
                    appendTable(docx, sb, table, docId, imagesDir, imgCounter, paraIdx);
                }
            }
        }
        return sb.toString();
    }

    private void appendParagraph(XWPFDocument doc, StringBuilder sb, XWPFParagraph para,
                                 String docId, Path imagesDir,
                                 int[] imgCounter, int paraIdx,
                                 Map<String, int[]> headingCounters,
                                 Map<String, boolean[]> headingCounterInit) throws IOException {
        int heading = headingLevel(doc, para);
        String text = paragraphText(doc, para, docId, imagesDir, imgCounter, paraIdx);
        ListInfo listInfo = resolveListInfo(doc, para);

        if (heading > 0) {
            String numberingPrefix = resolveHeadingNumberPrefix(doc, listInfo, headingCounters, headingCounterInit);
            if (!numberingPrefix.isBlank() && !startsWithHeadingNumber(text)) {
                text = numberingPrefix + " " + text;
            }
            if (!text.isBlank()) {
                sb.append("#".repeat(Math.min(heading, 6)))
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

    private String paragraphText(XWPFDocument doc, XWPFParagraph para,
                                 String docId, Path imagesDir,
                                 int[] imgCounter, int paraIdx) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (XWPFRun run : para.getRuns()) {
            List<XWPFPicture> pics = run.getEmbeddedPictures();
            if (!pics.isEmpty()) {
                for (XWPFPicture pic : pics) {
                    sb.append(extractPictureMarker(pic.getPictureData(), docId, imagesDir, imgCounter, paraIdx));
                }
                continue;
            }

            String text = runText(run);
            if (text.isEmpty()) continue;

            if (run instanceof XWPFHyperlinkRun hyperlinkRun) {
                String url = resolveHyperlinkUrl(doc, hyperlinkRun);
                if (url != null && !url.isBlank()) {
                    sb.append("[").append(text).append("](").append(url).append(")");
                    continue;
                }
            }

            sb.append(applyRunStyle(text, run.isBold(), run.isItalic()));
        }
        return sb.toString();
    }

    private String extractPictureMarker(XWPFPictureData picData, String docId, Path imagesDir,
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
        String relPath = "images/" + docId + "/" + fileName;
        boolean unconvertable = (isEmf || isWmf) && !converted;
        return unconvertable
                ? "[이미지(변환불가): " + relPath + "]"
                : "[이미지: " + relPath + "]";
    }

    private String applyRunStyle(String text, boolean bold, boolean italic) {
        if (bold && italic) return "***" + text + "***";
        if (bold) return "**" + text + "**";
        if (italic) return "_" + text + "_";
        return text;
    }

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

    private String resolveHyperlinkUrl(XWPFDocument doc, XWPFHyperlinkRun run) {
        String id = run.getHyperlinkId();
        if (id == null) return null;
        XWPFHyperlink link = doc.getHyperlinkByID(id);
        return link != null ? link.getURL() : null;
    }

    private void appendTable(XWPFDocument doc, StringBuilder sb, XWPFTable table,
                             String docId, Path imagesDir,
                             int[] imgCounter, int[] paraIdx) throws IOException {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) return;

        // 1x1 tables are often used as code-like callout blocks in DOCX. Render as fenced code.
        if (isSingleCellTable(rows)) {
            String code = cellContent(doc, rows.get(0).getTableCells().get(0), docId, imagesDir, imgCounter, paraIdx, "\n");
            sb.append("\n```\n").append(code).append("\n```\n\n");
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
            List<String> flatCells = flattenRow(doc, row, docId, imagesDir, imgCounter, paraIdx, maxCols);

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
                                    String docId, Path imagesDir,
                                    int[] imgCounter, int[] paraIdx,
                                    int maxCols) throws IOException {
        List<String> out = new java.util.ArrayList<>(maxCols);
        for (XWPFTableCell cell : row.getTableCells()) {
            int span = gridSpan(cell);
            String text = isVerticalMergeContinuation(cell)
                    ? ""
                    : cellContent(doc, cell, docId, imagesDir, imgCounter, paraIdx, " ");

            out.add(text);
            for (int i = 1; i < span; i++) out.add("");
        }
        while (out.size() < maxCols) out.add("");
        if (out.size() > maxCols) return out.subList(0, maxCols);
        return out;
    }

    private int gridSpan(XWPFTableCell cell) {
        CTTcPr tcPr = cell.getCTTc() != null ? cell.getCTTc().getTcPr() : null;
        if (tcPr == null || tcPr.getGridSpan() == null || tcPr.getGridSpan().getVal() == null) return 1;
        return Math.max(1, tcPr.getGridSpan().getVal().intValue());
    }

    private boolean isVerticalMergeContinuation(XWPFTableCell cell) {
        CTTcPr tcPr = cell.getCTTc() != null ? cell.getCTTc().getTcPr() : null;
        if (tcPr == null || tcPr.getVMerge() == null) return false;
        if (tcPr.getVMerge().getVal() == null) return true; // <w:vMerge/> means continuation
        return tcPr.getVMerge().getVal() != STMerge.RESTART;
    }

    private boolean isSingleCellTable(List<XWPFTableRow> rows) {
        return rows.size() == 1 && rows.get(0).getTableCells().size() == 1;
    }

    private String cellContent(XWPFDocument doc, XWPFTableCell cell,
                               String docId, Path imagesDir,
                               int[] imgCounter, int[] paraIdx,
                               String separator) throws IOException {
        StringBuilder out = new StringBuilder();
        for (XWPFParagraph p : cell.getParagraphs()) {
            String line = paragraphText(doc, p, docId, imagesDir, imgCounter, paraIdx[0]++).trim();
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

    private String normalizeLeadingMarkerStyle(String text) {
        String out = text;
        out = out.replaceFirst("^(\\s*)\\*\\*([^*]{1,6})\\*\\*(\\s+)", "$1$2$3");
        out = out.replaceFirst("^(\\s*)_([^_]{1,6})_(\\s+)", "$1$2$3");
        return out;
    }

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

    private String resolveHeadingNumberPrefix(XWPFDocument doc, ListInfo listInfo,
                                              Map<String, int[]> countersByNumId,
                                              Map<String, boolean[]> initByNumId) {
        try {
            if (listInfo == null || listInfo.numId() == null) return "";
            BigInteger numId = listInfo.numId();

            // Only numeric ordered headings should get synthetic numbering prefixes.
            if (!isOrderedList(doc, listInfo.numId(), listInfo.ilvl())) return "";

            int ilvl = listInfo.ilvl();
            String key = numId.toString();
            int[] counters = countersByNumId.computeIfAbsent(key, k -> new int[9]);
            boolean[] init = initByNumId.computeIfAbsent(key, k -> new boolean[9]);

            int start = resolveStartNumber(doc, numId, ilvl);
            if (!init[ilvl] || counters[ilvl] <= 0) {
                counters[ilvl] = Math.max(1, start);
                init[ilvl] = true;
            } else {
                counters[ilvl]++;
            }
            for (int i = ilvl + 1; i < counters.length; i++) {
                counters[i] = 0;
                init[i] = false;
            }

            StringBuilder prefix = new StringBuilder();
            for (int i = 0; i <= ilvl; i++) {
                if (counters[i] <= 0) continue;
                if (prefix.length() > 0) prefix.append('.');
                prefix.append(counters[i]);
            }
            return prefix.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private int resolveStartNumber(XWPFDocument doc, BigInteger numId, int ilvl) {
        try {
            XWPFNumbering numbering = doc.getNumbering();
            if (numbering == null) return 1;
            XWPFNum num = numbering.getNum(numId);
            if (num == null || num.getCTNum() == null) return 1;

            for (CTNumLvl ov : num.getCTNum().getLvlOverrideList()) {
                if (ov != null && ov.getIlvl() != null && ov.getIlvl().intValue() == ilvl && ov.isSetStartOverride()) {
                    BigInteger v = ov.getStartOverride().getVal();
                    return v != null ? Math.max(1, v.intValue()) : 1;
                }
            }

            BigInteger abstractNumId = num.getCTNum().getAbstractNumId().getVal();
            XWPFAbstractNum abstractNum = numbering.getAbstractNum(abstractNumId);
            if (abstractNum == null) return 1;
            CTLvl lvl = abstractNum.getCTAbstractNum().getLvlArray(ilvl);
            if (lvl != null && lvl.getStart() != null && lvl.getStart().getVal() != null) {
                return Math.max(1, lvl.getStart().getVal().intValue());
            }
        } catch (Exception ignored) {
            // Fallback to 1 when numbering metadata is incomplete.
        }
        return 1;
    }

    private boolean startsWithHeadingNumber(String text) {
        if (text == null) return false;
        return text.trim().matches("^\\d+(\\.\\d+)*[\\.)]?\\s+.*");
    }
}
