package com.example.ragagent.service;

import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Converts a DOCX to Markdown, extracting embedded images to imagesDir.
 *
 * Heading styles → #/##/###  |  bold/italic runs → ** / _
 * Tables → pipe-table  |  image runs → [이미지: relPath] markers
 * WMF/EMF → saved as-is with [이미지(변환불가): relPath] marker (Phase F handles conversion).
 *
 * Thread-safe: opens a new XWPFDocument per call (no shared state).
 */
@Component
public class DocxToMarkdownConverter {

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
        int[] paraIdx   = {0};

        try (XWPFDocument docx = new XWPFDocument(Files.newInputStream(docxPath))) {
            for (IBodyElement elem : docx.getBodyElements()) {
                if (elem instanceof XWPFParagraph para) {
                    appendParagraph(sb, para, docId, imagesDir, imgCounter, paraIdx[0]++);
                } else if (elem instanceof XWPFTable table) {
                    appendTable(sb, table);
                }
            }
        }
        return sb.toString();
    }

    private void appendParagraph(StringBuilder sb, XWPFParagraph para,
                                  String docId, Path imagesDir,
                                  int[] imgCounter, int paraIdx) throws IOException {
        String style = para.getStyle();
        if (style != null && style.toLowerCase().startsWith("heading")) {
            int level = extractHeadingLevel(style);
            sb.append("#".repeat(Math.min(level, 3)))
              .append(" ").append(para.getText().strip())
              .append("\n\n");
            return;
        }

        StringBuilder line = new StringBuilder();
        for (XWPFRun run : para.getRuns()) {
            for (XWPFPicture pic : run.getEmbeddedPictures()) {
                XWPFPictureData pd = pic.getPictureData();
                String ext = pd.suggestFileExtension();
                imgCounter[0]++;
                String fileName = "d" + paraIdx + "_img" + imgCounter[0] + "." + ext;
                Files.write(imagesDir.resolve(fileName), pd.getData());
                String relPath = "images/" + docId + "/" + fileName;
                boolean isVector = "wmf".equalsIgnoreCase(ext) || "emf".equalsIgnoreCase(ext);
                line.append(isVector
                        ? "[이미지(변환불가): " + relPath + "]"
                        : "[이미지: " + relPath + "]");
            }

            String text = run.getText(0);
            if (text != null && !text.isEmpty()) {
                boolean b = run.isBold(), i = run.isItalic();
                if (b && i)     line.append("***").append(text).append("***");
                else if (b)     line.append("**").append(text).append("**");
                else if (i)     line.append("_").append(text).append("_");
                else            line.append(text);
            }
        }

        String lineStr = line.toString().strip();
        if (!lineStr.isEmpty()) sb.append(lineStr);
        sb.append("\n");
    }

    private void appendTable(StringBuilder sb, XWPFTable table) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) return;
        sb.append("\n");
        for (int r = 0; r < rows.size(); r++) {
            List<XWPFTableCell> cells = rows.get(r).getTableCells();
            sb.append("| ");
            for (XWPFTableCell cell : cells) {
                sb.append(cell.getText().replace("|", "\\|")).append(" | ");
            }
            sb.append("\n");
            if (r == 0) {
                sb.append("| ");
                for (int i = 0; i < cells.size(); i++) sb.append("--- | ");
                sb.append("\n");
            }
        }
        sb.append("\n");
    }

    private int extractHeadingLevel(String style) {
        String num = style.toLowerCase().replace(" ", "").replaceFirst("^heading", "").trim();
        try { return Integer.parseInt(num); } catch (NumberFormatException e) { return 1; }
    }
}
