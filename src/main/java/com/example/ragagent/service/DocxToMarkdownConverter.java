package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBrType;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.HashMap;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
        int[] currentPage = {1};
        Map<String, int[]> headingCounters = new HashMap<>();

        try (XWPFDocument docx = new XWPFDocument(Files.newInputStream(docxPath))) {
            for (IBodyElement elem : docx.getBodyElements()) {
                if (elem instanceof XWPFParagraph para) {
                    appendParagraph(docx, sb, para, docId, imagesDir, imgCounter, paraIdx[0]++, currentPage, headingCounters);
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
                                 int[] currentPage,
                                 Map<String, int[]> headingCounters) throws IOException {
        int heading = headingLevel(doc, para);
        String text = paragraphText(doc, para, docId, imagesDir, imgCounter, paraIdx);

        if (heading > 0) {
            String numberingPrefix = resolveHeadingNumberPrefix(doc, para, headingCounters);
            if (!numberingPrefix.isBlank() && !startsWithHeadingNumber(text)) {
                text = numberingPrefix + " " + text;
            }
            // Keep heading-level page anchors so downstream indexing can preserve source position.
            sb.append("[헤딩페이지: ").append(currentPage[0]).append("]\n");
            if (!text.isBlank()) {
                sb.append("#".repeat(Math.min(heading, 6)))
                        .append(" ").append(text.trim())
                        .append("\n\n");
            }
            advancePageIfNeeded(sb, para, currentPage);
            return;
        }

        if (para.getNumID() != null) {
            int ilvl = para.getNumIlvl() != null ? para.getNumIlvl().intValue() : 0;
            String indent = "  ".repeat(Math.max(0, ilvl));
            String marker = isOrderedList(doc, para) ? "1." : "-";
            sb.append(indent).append(marker).append(" ").append(text).append("\n");
            advancePageIfNeeded(sb, para, currentPage);
            return;
        }

        if (!text.isBlank()) {
            sb.append(text).append("\n\n");
        } else {
            sb.append("\n");
        }
        advancePageIfNeeded(sb, para, currentPage);
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

    private void advancePageIfNeeded(StringBuilder sb, XWPFParagraph para, int[] currentPage) {
        if (!hasExplicitPageBreak(para)) return;
        currentPage[0]++;
        // Page anchor marker for sections that have no headings (e.g., prologue blocks).
        sb.append("[페이지: ").append(currentPage[0]).append("]\n");
    }

    private boolean hasExplicitPageBreak(XWPFParagraph para) {
        for (XWPFRun run : para.getRuns()) {
            for (CTBr br : run.getCTR().getBrList()) {
                if (br.isSetType() && STBrType.PAGE.equals(br.getType())) {
                    return true;
                }
            }
        }
        return false;
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

        sb.append("\n");
        for (int i = 0; i < rows.size(); i++) {
            XWPFTableRow row = rows.get(i);
            List<XWPFTableCell> cells = row.getTableCells();
            sb.append("|");
            for (XWPFTableCell cell : cells) {
                String text = cellContent(doc, cell, docId, imagesDir, imgCounter, paraIdx, " ")
                        .replace("|", "\\|");
                sb.append(" ").append(text).append(" |");
            }
            sb.append("\n");
            if (i == 0) {
                sb.append("|");
                for (int j = 0; j < row.getTableCells().size(); j++) {
                    sb.append(" --- |");
                }
                sb.append("\n");
            }
        }
        sb.append("\n");
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

    private boolean isOrderedList(XWPFDocument doc, XWPFParagraph para) {
        try {
            BigInteger numId = para.getNumID();
            if (numId == null) return false;
            XWPFNumbering numbering = doc.getNumbering();
            if (numbering == null) return false;
            XWPFNum num = numbering.getNum(numId);
            if (num == null) return false;
            BigInteger abstractNumId = num.getCTNum().getAbstractNumId().getVal();
            XWPFAbstractNum abstractNum = numbering.getAbstractNum(abstractNumId);
            if (abstractNum == null) return false;
            int ilvl = para.getNumIlvl() != null ? para.getNumIlvl().intValue() : 0;
            CTLvl lvl = abstractNum.getCTAbstractNum().getLvlArray(ilvl);
            if (lvl == null || lvl.getNumFmt() == null) return false;
            return !"bullet".equals(lvl.getNumFmt().getVal().toString());
        } catch (Exception e) {
            return false;
        }
    }

    private String resolveHeadingNumberPrefix(XWPFDocument doc, XWPFParagraph para, Map<String, int[]> countersByNumId) {
        try {
            BigInteger numId = para.getNumID();
            if (numId == null) return "";

            // Only numeric ordered headings should get synthetic numbering prefixes.
            if (!isOrderedList(doc, para)) return "";

            int ilvl = para.getNumIlvl() != null ? para.getNumIlvl().intValue() : 0;
            String key = numId.toString();
            int[] counters = countersByNumId.computeIfAbsent(key, k -> new int[9]);
            counters[ilvl]++;
            for (int i = ilvl + 1; i < counters.length; i++) counters[i] = 0;

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

    private boolean startsWithHeadingNumber(String text) {
        if (text == null) return false;
        return text.trim().matches("^\\d+(\\.\\d+)*[\\.)]?\\s+.*");
    }
}
