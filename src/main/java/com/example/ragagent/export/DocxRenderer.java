package com.example.ragagent.export;

import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblGrid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders export markdown into a .docx via POI. Deliberately a small, line-driven renderer covering
 * exactly what the export pipeline emits — headings, paragraphs, pipe tables, fenced code, list
 * items, blockquotes and images — rather than a general Markdown implementation.
 *
 * <p>Images arrive as {@link #imageToken(String)} markers (written by the caller's image rewriter)
 * holding a filesystem path, and are embedded as real picture runs scaled to fit their container: a
 * marker on a line of its own becomes a centered, text-column-wide picture, while one inside a
 * table cell is embedded in place, scaled down to that column's share of the page.
 *
 * <p>A fenced code block is rendered as a single-cell table — the cell border draws the code box
 * Word has no native style for — left-aligned, monospaced, with comment spans colored.
 */
public final class DocxRenderer {

    private static final Logger log = LoggerFactory.getLogger(DocxRenderer.class);

    /**
     * Delimiters of the image marker the export image-rewriter emits for DOCX; the payload between
     * them is an absolute file path. NUL is used so the marker can never collide with document text
     * and stays invisible if any of it ever leaks into a run.
     */
    public static final String IMAGE_TOKEN_START = "\0DOCX_IMAGE:";
    public static final String IMAGE_TOKEN_END = "\0";

    private static final Pattern IMAGE_TOKEN = Pattern.compile(
            Pattern.quote(IMAGE_TOKEN_START) + "([^\\x00]*)" + Pattern.quote(IMAGE_TOKEN_END));

    private static final Pattern HEADING   = Pattern.compile("^(#{1,6})\\s+(.*\\S)\\s*$");
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-*+]|\\d+\\.)\\s+(.*)$");
    private static final Pattern SEPARATOR = Pattern.compile("^\\|[\\s:|-]+\\|?\\s*$");
    /** Bold/italic runs — the only inline emphasis the pipeline produces in quantity. */
    private static final Pattern EMPHASIS  = Pattern.compile("\\*\\*(.+?)\\*\\*|\\*(.+?)\\*|`(.+?)`");
    /** Table cells carry their line breaks as {@code <br>} (MarkdownCorrectionService joins that way). */
    private static final Pattern LINE_BREAK = Pattern.compile("(?i)<br\\s*/?>");

    /** Max picture width in EMU — A4 (595pt) minus 1" margins each side, i.e. the text column. */
    private static final int MAX_IMAGE_WIDTH_EMU = Units.toEMU(451);
    private static final int MAX_IMAGE_HEIGHT_EMU = Units.toEMU(600);

    /** Text-column width in twips (1pt = 20 twips) — A4 minus 1" margins, matching MAX_IMAGE_WIDTH_EMU. */
    private static final int TEXT_WIDTH_TWIPS = 451 * 20;

    /** Column-width heuristics — see columnWidths(). Shares are of the text column. */
    private static final double MIN_COL_SHARE = 0.10;
    /** Per-cell length cap (in display units) before averaging, so one long cell can't swallow the table. */
    private static final int CELL_WIDTH_CAP = 80;
    /** How much column width an embedded image asks for, in the same display units. */
    private static final int IMAGE_WIDTH_UNITS = 12;

    /** Table border width in eighths of a point (4 = 0.5pt) and color; see applyVisibleBorders(). */
    private static final int BORDER_SIZE = 4;
    private static final String BORDER_COLOR = "auto";

    private static final String CODE_FONT = "Consolas";
    private static final int CODE_FONT_SIZE = 9;
    /** Comment spans inside a code block. */
    private static final String COMMENT_COLOR = "008000";

    private DocxRenderer() {}

    /** The marker an image rewriter emits for DOCX; {@code absolutePath} is embedded verbatim. */
    public static String imageToken(String absolutePath) {
        return IMAGE_TOKEN_START + absolutePath + IMAGE_TOKEN_END;
    }

    public static byte[] render(String markdown, String title) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (title != null && !title.isBlank()) {
                XWPFParagraph p = doc.createParagraph();
                p.setAlignment(ParagraphAlignment.CENTER);
                XWPFRun r = p.createRun();
                r.setText(title);
                r.setBold(true);
                r.setFontSize(20);
            }

            List<String> lines = List.of(markdown.split("\n", -1));
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String t = line.strip();

                if (t.isEmpty()) continue;

                Matcher token = IMAGE_TOKEN.matcher(t);
                if (token.matches()) {                     // the whole line is one image
                    embedImage(doc, token.group(1).strip());
                    continue;
                }
                if (t.startsWith("```")) {
                    i = writeCodeBlock(doc, lines, i);
                    continue;
                }
                if (isTableRow(t) && i + 1 < lines.size() && SEPARATOR.matcher(lines.get(i + 1).strip()).matches()) {
                    i = writeTable(doc, lines, i);
                    continue;
                }

                Matcher h = HEADING.matcher(t);
                if (h.matches()) {
                    writeHeading(doc, h.group(1).length(), h.group(2));
                    continue;
                }
                if (t.equals("---") || t.equals("***")) {
                    doc.createParagraph().setBorderBottom(org.apache.poi.xwpf.usermodel.Borders.SINGLE);
                    continue;
                }
                writeBody(doc, t);
            }
            doc.write(out);
            return out.toByteArray();
        }
    }

    private static void writeHeading(XWPFDocument doc, int level, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setStyle("Heading" + Math.min(level, 6));   // may not exist in a blank doc → size fallback
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setBold(true);
        r.setFontSize(Math.max(11, 20 - level * 2));
        p.setSpacingBefore(200);
    }

    private static void writeBody(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        boolean quote = text.startsWith(">");
        if (quote) {
            text = text.substring(1).strip();
            p.setIndentationLeft(360);
        }
        Matcher list = LIST_ITEM.matcher(text);
        if (list.matches()) {
            text = "• " + list.group(1);
            p.setIndentationLeft(p.getIndentationLeft() + 360);
        }
        writeInline(p, text, quote, MAX_IMAGE_WIDTH_EMU);
    }

    /**
     * Writes one markdown fragment into {@code p}: {@code <br>} becomes a line break, image tokens
     * become embedded pictures, and the rest is emphasis-formatted text.
     */
    private static void writeInline(XWPFParagraph p, String text, boolean italicAll, int maxImageWidthEmu) {
        String[] parts = LINE_BREAK.split(text, -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) p.createRun().addBreak();
            writeInlineImages(p, parts[i], italicAll, maxImageWidthEmu);
        }
    }

    /** Splits off the image tokens, embedding each picture where it sits in the text. */
    private static void writeInlineImages(XWPFParagraph p, String text, boolean italicAll,
                                          int maxImageWidthEmu) {
        Matcher m = IMAGE_TOKEN.matcher(text);
        int last = 0;
        while (m.find()) {
            writeEmphasis(p, text.substring(last, m.start()), italicAll);
            addPicture(p, m.group(1).strip(), maxImageWidthEmu);
            last = m.end();
        }
        writeEmphasis(p, text.substring(last), italicAll);
    }

    /** Applies {@code **bold**}, {@code *italic*} and {@code `code`} as separate runs. */
    private static void writeEmphasis(XWPFParagraph p, String text, boolean italicAll) {
        Matcher m = EMPHASIS.matcher(text);
        int last = 0;
        while (m.find()) {
            addRun(p, text.substring(last, m.start()), false, false, italicAll);
            if (m.group(1) != null)      addRun(p, m.group(1), true, false, italicAll);
            else if (m.group(2) != null) addRun(p, m.group(2), false, true, italicAll);
            else                         addRun(p, m.group(3), false, false, italicAll, true);
            last = m.end();
        }
        addRun(p, text.substring(last), false, false, italicAll);
    }

    private static void addRun(XWPFParagraph p, String text, boolean bold, boolean italic, boolean quote) {
        addRun(p, text, bold, italic, quote, false);
    }

    private static void addRun(XWPFParagraph p, String text, boolean bold, boolean italic,
                               boolean quote, boolean mono) {
        if (text == null || text.isEmpty()) return;
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setBold(bold);
        r.setItalic(italic || quote);
        if (mono) r.setFontFamily(CODE_FONT);
        if (quote) r.setColor("555555");
    }

    /**
     * Writes the fenced block into a single-cell table — the cell border is the code box — with the
     * body left-aligned, monospaced and verbatim; comment spans are colored. Returns the index of
     * the closing fence.
     */
    private static int writeCodeBlock(XWPFDocument doc, List<String> lines, int start) {
        List<String> body = new ArrayList<>();
        int i = start + 1;
        for (; i < lines.size() && !lines.get(i).strip().startsWith("```"); i++) body.add(lines.get(i));

        XWPFTable table = doc.createTable(1, 1);
        table.setWidth("100%");
        applyGrid(table, new int[]{TEXT_WIDTH_TWIPS});
        applyVisibleBorders(table);
        XWPFParagraph p = table.getRow(0).getCell(0).getParagraphs().get(0);
        p.setAlignment(ParagraphAlignment.LEFT);
        p.setSpacingBefore(0);
        p.setSpacingAfter(0);

        boolean[] inBlockComment = {false};
        for (int k = 0; k < body.size(); k++) {
            if (k > 0) codeRun(p).addBreak();
            for (CodeSegment seg : splitComments(body.get(k), inBlockComment)) {
                XWPFRun r = codeRun(p);
                r.setText(seg.text());
                if (seg.comment()) r.setColor(COMMENT_COLOR);
            }
        }
        return i;
    }

    private static XWPFRun codeRun(XWPFParagraph p) {
        XWPFRun r = p.createRun();
        r.setFontFamily(CODE_FONT);
        r.setFontSize(CODE_FONT_SIZE);
        return r;
    }

    /** One stretch of a code line, either comment or not. */
    private record CodeSegment(String text, boolean comment) {}

    /**
     * Splits one code line into comment / non-comment stretches, covering the comment syntaxes the
     * pipeline actually sees: {@code //}, {@code #} and {@code /* … *}{@code /} (whose open state
     * carries across lines via {@code inBlockComment}).
     *
     * <p>Deliberately conservative about false positives: string literals are tracked so a
     * {@code "#"} inside one is left alone, and {@code #} only starts a comment at a token boundary
     * (which also keeps {@code http://} out of the {@code //} case, since it is inside no quote but
     * is preceded by {@code :}).
     */
    private static List<CodeSegment> splitComments(String line, boolean[] inBlockComment) {
        List<CodeSegment> segments = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        char quote = 0;
        int i = 0;

        while (i < line.length()) {
            if (inBlockComment[0]) {
                int end = line.indexOf("*/", i);
                int stop = end < 0 ? line.length() : end + 2;
                segments.add(new CodeSegment(line.substring(i, stop), true));
                inBlockComment[0] = end < 0;
                i = stop;
                continue;
            }
            char c = line.charAt(i);
            if (quote != 0) {                                   // inside a string literal
                plain.append(c);
                if (c == quote && (i == 0 || line.charAt(i - 1) != '\\')) quote = 0;
                i++;
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                plain.append(c);
                i++;
                continue;
            }
            if (line.startsWith("/*", i)) {
                flush(segments, plain);
                inBlockComment[0] = true;
                continue;
            }
            if (line.startsWith("//", i) || (c == '#' && (i == 0 || !isWordChar(line.charAt(i - 1))))) {
                flush(segments, plain);
                segments.add(new CodeSegment(line.substring(i), true));
                return segments;                                // rest of the line is comment
            }
            plain.append(c);
            i++;
        }
        flush(segments, plain);
        return segments;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static void flush(List<CodeSegment> segments, StringBuilder plain) {
        if (plain.length() > 0) {
            segments.add(new CodeSegment(plain.toString(), false));
            plain.setLength(0);
        }
    }

    /**
     * Writes the {@code <w:tblGrid>} POI omits, plus a matching width on every cell.
     *
     * <p>{@code createTable()} emits {@code <w:tbl>} with no {@code <w:tblGrid>} and no
     * {@code <w:tcW>} at all, even though the schema lists the grid as a required child of
     * {@code CT_Tbl}. Word reconstructs the columns from the {@code <w:tc>} count, but Apple Pages
     * does not — with no grid it renders every cell in a single column, so a 2-column table comes
     * out as one long vertical strip. Declaring the grid explicitly fixes it everywhere.
     *
     * @param widths per-column width in twips; the sum should equal {@link #TEXT_WIDTH_TWIPS}
     */
    private static void applyGrid(XWPFTable table, int[] widths) {
        // null check rather than isSetTblGrid() — poi-ooxml-lite trims the isSet* accessors.
        CTTblGrid existing = table.getCTTbl().getTblGrid();
        CTTblGrid grid = (existing != null) ? existing : table.getCTTbl().addNewTblGrid();
        for (int w : widths) {
            grid.addNewGridCol().setW(BigInteger.valueOf(w));
        }
        for (XWPFTableRow row : table.getRows()) {
            for (int c = 0; c < row.getTableCells().size() && c < widths.length; c++) {
                row.getCell(c).setWidth(String.valueOf(widths[c]));   // dxa, matching the grid
            }
        }
    }

    /**
     * Splits the text column across {@code cols} proportionally to how much each column actually
     * holds, so a "label | long description" table doesn't waste half the page on the labels.
     *
     * <p>The weight per column is the mean {@link #displayWidth} of its cells (header included),
     * with each cell capped at {@link #CELL_WIDTH_CAP} first — one runaway paragraph should widen a
     * column, not swallow the table.
     *
     * <p>A column whose share falls under {@code minShare} is pinned at that floor and the rest are
     * re-proportioned over what's left (repeatedly, since pinning one can push another under). Doing
     * it this way rather than clamping once matters: a single pass that clamps and then dumps the
     * leftover on the widest column silently undoes its own floor/ceiling. The final rounding
     * remainder goes to the widest un-pinned column so the grid sums exactly to the text width.
     *
     * @implNote These widths are a hint, not a contract — without {@code <w:tblLayout w:type="fixed"/>}
     *           both Word and Pages autofit to content and will adjust them. The point is to start
     *           from a sane proportion instead of forcing every column to be equal.
     */
    private static int[] columnWidths(List<List<String>> rows, int cols) {
        if (cols <= 1) return new int[]{TEXT_WIDTH_TWIPS};

        double[] weight = new double[cols];
        for (int c = 0; c < cols; c++) {
            double sum = 0;
            for (List<String> row : rows) {
                String cell = c < row.size() ? row.get(c) : "";
                sum += Math.min(CELL_WIDTH_CAP, displayWidth(cell));
            }
            weight[c] = Math.max(1.0, sum / rows.size());        // never 0 — an empty column still needs room
        }

        // With many columns the floor has to shrink, or the minimums alone would exceed 100%.
        double minShare = Math.min(MIN_COL_SHARE, 0.5 / cols);
        double[] share = new double[cols];
        boolean[] pinned = new boolean[cols];
        double remaining = 1.0;

        for (int pass = 0; pass <= cols; pass++) {
            double freeWeight = 0;
            for (int c = 0; c < cols; c++) if (!pinned[c]) freeWeight += weight[c];
            if (freeWeight <= 0) break;

            boolean pinnedAny = false;
            for (int c = 0; c < cols; c++) {
                if (pinned[c]) continue;
                share[c] = weight[c] / freeWeight * remaining;
                if (share[c] < minShare) {
                    share[c] = minShare;
                    pinned[c] = true;
                    remaining -= minShare;
                    pinnedAny = true;
                }
            }
            if (!pinnedAny) break;
        }

        int[] widths = new int[cols];
        int assigned = 0, widest = -1;
        for (int c = 0; c < cols; c++) {
            widths[c] = Math.max(1, (int) Math.round(TEXT_WIDTH_TWIPS * share[c]));
            assigned += widths[c];
            if (!pinned[c] && (widest < 0 || weight[c] > weight[widest])) widest = c;
        }
        if (widest < 0) widest = 0;
        widths[widest] = Math.max(1, widths[widest] + (TEXT_WIDTH_TWIPS - assigned));
        return widths;
    }

    /**
     * Rough rendered width of a cell in "character units": CJK counts double (a Hangul glyph is
     * about twice a latin one), markdown emphasis markers don't count at all, an embedded image
     * counts as a nominal block, and a {@code <br>}-separated cell is measured by its longest line
     * rather than its total length — that line is what actually has to fit.
     */
    private static double displayWidth(String cell) {
        if (cell == null || cell.isBlank()) return 0;
        String text = IMAGE_TOKEN.matcher(cell).replaceAll("x".repeat(IMAGE_WIDTH_UNITS));
        text = text.replaceAll("\\*\\*|\\*|`", "");
        double widest = 0;
        for (String line : LINE_BREAK.split(text, -1)) {
            double w = 0;
            for (int i = 0; i < line.length(); i++) {
                w += isWide(line.charAt(i)) ? 2 : 1;
            }
            widest = Math.max(widest, w);
        }
        return widest;
    }

    /** Hangul / Han / Kana — the ranges this pipeline actually sees in double-width form. */
    private static boolean isWide(char c) {
        return (c >= 0xAC00 && c <= 0xD7A3)     // Hangul syllables
                || (c >= 0x1100 && c <= 0x11FF) // Hangul Jamo
                || (c >= 0x3130 && c <= 0x318F) // Hangul compatibility Jamo
                || (c >= 0x4E00 && c <= 0x9FFF) // CJK unified ideographs
                || (c >= 0x3040 && c <= 0x30FF) // Kana
                || (c >= 0xFF01 && c <= 0xFF60);// fullwidth forms
    }

    /**
     * Restates every border with an explicit width and color.
     *
     * <p>{@code XWPFDocument.createTable()} emits {@code <w:top w:val="single"/>} — the line style
     * only, with no {@code w:sz}. Word fills in a default width there and draws the line, but Google
     * Docs, Apple Pages and LibreOffice read the missing width as 0 and draw nothing, so a table
     * that looks correct in Word arrives borderless everywhere else (and the fenced-code box, which
     * IS a 1x1 table's border, disappears entirely). Writing the width explicitly is the fix.
     *
     * @implNote size is in eighths of a point, so {@code 4} is a 0.5pt hairline; {@code "auto"}
     *           lets the consumer pick a theme-appropriate color (black on a default document).
     */
    private static void applyVisibleBorders(XWPFTable table) {
        table.setTopBorder(XWPFTable.XWPFBorderType.SINGLE, BORDER_SIZE, 0, BORDER_COLOR);
        table.setBottomBorder(XWPFTable.XWPFBorderType.SINGLE, BORDER_SIZE, 0, BORDER_COLOR);
        table.setLeftBorder(XWPFTable.XWPFBorderType.SINGLE, BORDER_SIZE, 0, BORDER_COLOR);
        table.setRightBorder(XWPFTable.XWPFBorderType.SINGLE, BORDER_SIZE, 0, BORDER_COLOR);
        table.setInsideHBorder(XWPFTable.XWPFBorderType.SINGLE, BORDER_SIZE, 0, BORDER_COLOR);
        table.setInsideVBorder(XWPFTable.XWPFBorderType.SINGLE, BORDER_SIZE, 0, BORDER_COLOR);
    }

    private static boolean isTableRow(String t) {
        return t.startsWith("|") && t.length() > 1;
    }

    /** Writes a pipe table; returns the index of its last row. */
    private static int writeTable(XWPFDocument doc, List<String> lines, int start) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(splitRow(lines.get(start).strip()));

        int i = start + 2;                                    // skip header + separator
        for (; i < lines.size() && isTableRow(lines.get(i).strip()); i++) {
            rows.add(splitRow(lines.get(i).strip()));
        }

        int cols = rows.stream().mapToInt(List::size).max().orElse(1);
        int[] widths = columnWidths(rows, cols);
        XWPFTable table = doc.createTable(rows.size(), cols);
        table.setWidth("100%");
        applyGrid(table, widths);
        applyVisibleBorders(table);

        for (int r = 0; r < rows.size(); r++) {
            XWPFTableRow row = table.getRow(r);
            for (int c = 0; c < cols; c++) {
                String cell = c < rows.get(r).size() ? rows.get(r).get(c) : "";
                XWPFTableCell tableCell = row.getCell(c);
                XWPFParagraph p = tableCell.getParagraphs().get(0);
                // A picture must fit its own column, which is no longer an equal share.
                int cellImageWidth = Math.max(Units.toEMU(40), Units.toEMU(widths[c] / 20.0 - 10));
                writeInline(p, cell, false, cellImageWidth);
                if (r == 0) p.getRuns().forEach(run -> run.setBold(true));
            }
        }
        return i - 1;
    }

    private static List<String> splitRow(String line) {
        String body = line.startsWith("|") ? line.substring(1) : line;
        if (body.endsWith("|")) body = body.substring(0, body.length() - 1);
        List<String> cells = new ArrayList<>();
        for (String cell : body.split("\\|", -1)) cells.add(cell.strip());
        return cells;
    }

    /** Embeds one picture in a centered paragraph of its own, scaled to the text column. */
    private static void embedImage(XWPFDocument doc, String pathStr) {
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(ParagraphAlignment.CENTER);
        addPicture(p, pathStr, MAX_IMAGE_WIDTH_EMU);
    }

    /**
     * Adds one picture run to {@code p}, scaled proportionally to fit {@code maxWidthEmu}. A
     * missing/unreadable file degrades to a short note rather than failing the whole export — a
     * partially-illustrated document is far more useful than none.
     */
    private static void addPicture(XWPFParagraph p, String pathStr, int maxWidthEmu) {
        Path path = Path.of(pathStr);
        try {
            if (!Files.isRegularFile(path)) {
                addRun(p, "(이미지 없음: " + path.getFileName() + ")", false, true, false);
                return;
            }
            int type = pictureType(path);
            int[] size = scaledSize(path, maxWidthEmu);
            XWPFRun r = p.createRun();
            try (InputStream in = Files.newInputStream(path)) {
                r.addPicture(in, type, path.getFileName().toString(), size[0], size[1]);
            }
        } catch (Exception e) {
            log.warn("[EXPORT] DOCX 이미지 삽입 실패 {}: {}", path.getFileName(), e.getMessage());
            addRun(p, "(이미지 삽입 실패: " + path.getFileName() + ")", false, true, false);
        }
    }

    private static int[] scaledSize(Path path, int maxWidthEmu) {
        int maxHeight = Math.min(MAX_IMAGE_HEIGHT_EMU, maxWidthEmu * 4);
        try {
            BufferedImage img = ImageIO.read(path.toFile());
            if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) {
                return new int[]{maxWidthEmu, maxWidthEmu};
            }
            double scale = Math.min(1.0, (double) maxWidthEmu / Units.toEMU(img.getWidth()));
            int h = (int) (Units.toEMU(img.getHeight()) * scale);
            if (h > maxHeight) scale *= (double) maxHeight / h;
            return new int[]{(int) (Units.toEMU(img.getWidth()) * scale),
                             (int) (Units.toEMU(img.getHeight()) * scale)};
        } catch (Exception e) {
            return new int[]{maxWidthEmu, maxWidthEmu};
        }
    }

    private static int pictureType(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return Document.PICTURE_TYPE_JPEG;
        if (name.endsWith(".gif")) return Document.PICTURE_TYPE_GIF;
        if (name.endsWith(".bmp")) return Document.PICTURE_TYPE_BMP;
        if (name.endsWith(".tif") || name.endsWith(".tiff")) return Document.PICTURE_TYPE_TIFF;
        return Document.PICTURE_TYPE_PNG;
    }
}
