package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Regression — DOCX run-splitting produced duplicated/garbled emphasis markers on conversion
 * (e.g. "라우팅 전략" saved by Word as adjacent same-style runs came out as
 * "**라우팅****전략**" or "**** ****라우팅**** ****"), and bold text inside single-cell
 * "code callout" tables leaked "**" into the fenced code block. Fixed by merging adjacent
 * same-style runs before applying emphasis markers, trimming whitespace out of the marked
 * span, and skipping emphasis entirely for cells rendered as fenced code.
 */
class DocxToMarkdownConverterTest {

    private final DocxToMarkdownConverter converter = new DocxToMarkdownConverter(
            Optional.empty(), Optional.empty(), mock(AppProperties.class));

    @Test
    void mergesAdjacentBoldRunsWithoutDuplicatingMarkers() throws Exception {
        Path docxPath = writeDocx(doc -> {
            XWPFParagraph p = doc.createParagraph();
            addRun(p, "라우팅", true);
            addRun(p, "전략", true);
        });

        String md = converter.convert(docxPath, "doc1", tempDir());

        assertThat(md).contains("**라우팅전략**");
        assertThat(md).doesNotContain("****");
    }

    @Test
    void keepsWhitespaceBetweenBoldRunsOutsideMarkers() throws Exception {
        Path docxPath = writeDocx(doc -> {
            XWPFParagraph p = doc.createParagraph();
            addRun(p, "라우팅", true);
            addRun(p, " ", true);
            addRun(p, "전략", true);
        });

        String md = converter.convert(docxPath, "doc1", tempDir());

        assertThat(md).contains("**라우팅 전략**");
        assertThat(md).doesNotContain("** **");
        assertThat(md).doesNotContain("****");
    }

    @Test
    void skipsEmphasisInsideFencedCodeSingleCellTable() throws Exception {
        Path docxPath = writeDocx(doc -> {
            XWPFTable table = doc.createTable(1, 1);
            XWPFParagraph cellPara = table.getRow(0).getCell(0).getParagraphs().get(0);
            addRun(cellPara, "public", true);
            addRun(cellPara, " void main()", false);
        });

        String md = converter.convert(docxPath, "doc1", tempDir());

        assertThat(md).contains("```");
        assertThat(md).doesNotContain("**public**");
        assertThat(md).contains("public void main()");
    }

    private void addRun(XWPFParagraph p, String text, boolean bold) {
        XWPFRun run = p.createRun();
        run.setBold(bold);
        run.setText(text);
    }

    private Path writeDocx(java.util.function.Consumer<XWPFDocument> builder) throws Exception {
        Path path = Files.createTempFile("docx-test", ".docx");
        path.toFile().deleteOnExit();
        try (XWPFDocument doc = new XWPFDocument()) {
            builder.accept(doc);
            try (var out = Files.newOutputStream(path)) {
                doc.write(out);
            }
        }
        return path;
    }

    private Path tempDir() throws Exception {
        Path dir = Files.createTempDirectory("docx-test-images");
        dir.toFile().deleteOnExit();
        return dir;
    }
}
