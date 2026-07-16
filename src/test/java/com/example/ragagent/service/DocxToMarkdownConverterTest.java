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
import static org.mockito.Mockito.when;

/**
 * Regression — DOCX run-splitting produced duplicated/garbled emphasis markers on conversion
 * (e.g. "라우팅 전략" saved by Word as adjacent same-style runs came out as
 * "**라우팅****전략**" or "**** ****라우팅**** ****"), and bold text inside single-cell
 * "code callout" tables leaked "**" into the fenced code block. Fixed by merging adjacent
 * same-style runs before applying emphasis markers, trimming whitespace out of the marked
 * span, and skipping emphasis entirely for cells rendered as fenced code.
 */
class DocxToMarkdownConverterTest {

    private final DocxToMarkdownConverter converter = newConverter(false);

    private static DocxToMarkdownConverter newConverter(boolean mergeAnnotatedShapes) {
        AppProperties props = mock(AppProperties.class);
        when(props.docxImageSafe()).thenReturn(new AppProperties.DocxShapeExtractionConfig(mergeAnnotatedShapes));
        when(props.imageDescriptionSafe()).thenReturn(new AppProperties.ImageDescriptionProperties(
                "off", false, false, null, 0, false, false, false));
        return new DocxToMarkdownConverter(
                Optional.empty(), Optional.empty(), props, new DocxAnnotationShapeMerger(props));
    }

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

    // ── DOCX 이미지+VML 도형 합성 (DocxAnnotationShapeMerger 연동) ──────────

    @Test
    void mergesPictureWithSameParagraphVmlShapeIntoOneCompositePng() throws Exception {
        byte[] png = DocxAnnotationShapeMergerTest.pngBytes(100, 60, java.awt.Color.BLUE);
        Path docxPath = writeDocx(doc -> {
            XWPFParagraph p = doc.createParagraph();
            addPicture(p, png, 100, 60);
            DocxAnnotationShapeMergerTest.addVmlShape(p, "rect", java.util.Map.of(
                    "style", "position:absolute;left:10pt;top:10pt;width:40pt;height:20pt",
                    "strokecolor", "red"));
        });
        Path imagesDir = tempDir();

        String md = newConverter(true).convert(docxPath, "doc1", imagesDir);

        assertThat(md).contains("[이미지: images/doc1/d0_img1.png]");
        try (var files = Files.list(imagesDir)) {
            var saved = files.toList();
            assertThat(saved).hasSize(1); // 합성본 1장 — 원본/도형이 별도 파일로 남지 않음
            byte[] compositeBytes = Files.readAllBytes(saved.get(0));
            assertThat(compositeBytes).isNotEqualTo(png); // 원본 그대로가 아니라 합성본
            var img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(compositeBytes));
            assertThat(img.getWidth()).isEqualTo(100);
            assertThat(img.getHeight()).isEqualTo(60);
        }
    }

    @Test
    void mergeDisabled_pictureWithSameParagraphShapeExtractsVerbatim() throws Exception {
        byte[] png = DocxAnnotationShapeMergerTest.pngBytes(100, 60, java.awt.Color.GREEN);
        Path docxPath = writeDocx(doc -> {
            XWPFParagraph p = doc.createParagraph();
            addPicture(p, png, 100, 60);
            DocxAnnotationShapeMergerTest.addVmlShape(p, "rect", java.util.Map.of(
                    "style", "position:absolute;left:10pt;top:10pt;width:40pt;height:20pt"));
        });
        Path imagesDir = tempDir();

        String md = newConverter(false).convert(docxPath, "doc1", imagesDir);

        assertThat(md).contains("[이미지: images/doc1/d0_img1.png]");
        try (var files = Files.list(imagesDir)) {
            var saved = files.toList();
            assertThat(saved).hasSize(1);
            assertThat(Files.readAllBytes(saved.get(0))).isEqualTo(png); // 원본 그대로
        }
    }

    @Test
    void pictureWithoutShapesExtractsVerbatimEvenWhenMergeEnabled() throws Exception {
        byte[] png = DocxAnnotationShapeMergerTest.pngBytes(80, 40, java.awt.Color.GRAY);
        Path docxPath = writeDocx(doc -> {
            XWPFParagraph p = doc.createParagraph();
            addPicture(p, png, 80, 40);
        });
        Path imagesDir = tempDir();

        String md = newConverter(true).convert(docxPath, "doc1", imagesDir);

        assertThat(md).contains("[이미지: images/doc1/d0_img1.png]");
        try (var files = Files.list(imagesDir)) {
            var saved = files.toList();
            assertThat(saved).hasSize(1);
            assertThat(Files.readAllBytes(saved.get(0))).isEqualTo(png);
        }
    }

    @Test
    void secondPictureInSameParagraphIsNotMerged() throws Exception {
        byte[] png1 = DocxAnnotationShapeMergerTest.pngBytes(100, 60, java.awt.Color.BLUE);
        byte[] png2 = DocxAnnotationShapeMergerTest.pngBytes(50, 50, java.awt.Color.ORANGE);
        Path docxPath = writeDocx(doc -> {
            XWPFParagraph p = doc.createParagraph();
            addPicture(p, png1, 100, 60);
            addPicture(p, png2, 50, 50);
            DocxAnnotationShapeMergerTest.addVmlShape(p, "oval", java.util.Map.of(
                    "style", "position:absolute;left:20pt;top:15pt;width:30pt;height:30pt"));
        });
        Path imagesDir = tempDir();

        newConverter(true).convert(docxPath, "doc1", imagesDir);

        try (var files = Files.list(imagesDir)) {
            var saved = files.sorted().toList();
            assertThat(saved).hasSize(2); // 첫 사진은 합성, 두 번째 사진은 원본 그대로
            assertThat(Files.readAllBytes(saved.get(1))).isEqualTo(png2);
        }
    }

    private void addPicture(XWPFParagraph p, byte[] pngBytes, double widthPt, double heightPt) {
        try {
            p.createRun().addPicture(new java.io.ByteArrayInputStream(pngBytes),
                    XWPFDocument.PICTURE_TYPE_PNG, "pic.png",
                    org.apache.poi.util.Units.toEMU(widthPt), org.apache.poi.util.Units.toEMU(heightPt));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
