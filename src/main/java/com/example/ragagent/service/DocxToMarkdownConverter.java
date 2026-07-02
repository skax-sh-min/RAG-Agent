package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBrType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
        int[] paraIdx   = {0};
        int[] currentPage = {1};

        try (XWPFDocument docx = new XWPFDocument(Files.newInputStream(docxPath))) {
            for (IBodyElement elem : docx.getBodyElements()) {
                if (elem instanceof XWPFParagraph para) {
                    appendParagraph(sb, para, docId, imagesDir, imgCounter, paraIdx[0]++, currentPage);
                } else if (elem instanceof XWPFTable table) {
                    appendTable(sb, table);
                }
            }
        }
        return sb.toString();
    }

    private void appendParagraph(StringBuilder sb, XWPFParagraph para,
                                  String docId, Path imagesDir,
                      int[] imgCounter, int paraIdx,
                      int[] currentPage) throws IOException {
        String style = para.getStyle();
        boolean isHeading = style != null && style.toLowerCase().startsWith("heading");
        if (isHeading) {
            int level = extractHeadingLevel(style);
            // Keep heading-level page anchors so downstream indexing can preserve source position.
            sb.append("[헤딩페이지: ").append(currentPage[0]).append("]\n");
            sb.append("#".repeat(Math.min(level, 3)))
                .append(" ").append(para.getText().strip())
                .append("\n\n");
            advancePageIfNeeded(sb, para, currentPage);
            return;
        }

        boolean emfConvert = props.imageDescriptionSafe().docxEmfConvert();
        boolean wmfConvert = props.imageDescriptionSafe().docxWmfConvert();

        StringBuilder line = new StringBuilder();
        for (XWPFRun run : para.getRuns()) {
            for (XWPFPicture pic : run.getEmbeddedPictures()) {
                XWPFPictureData pd = pic.getPictureData();
                String ext = pd.suggestFileExtension();
                imgCounter[0]++;

                boolean isEmf = "emf".equalsIgnoreCase(ext);
                boolean isWmf = "wmf".equalsIgnoreCase(ext);
                byte[] imageBytes = pd.getData();
                String savedExt = ext;
                boolean converted = false;

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
                line.append(unconvertable
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
        advancePageIfNeeded(sb, para, currentPage);
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
