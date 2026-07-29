package com.example.ragagent.export;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** DOCX rendering of the export markdown: images, tables and code blocks. */
class DocxRendererTest {

    @TempDir
    Path tmp;

    private Path samplePng(String name) throws Exception {
        BufferedImage img = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        img.createGraphics().setColor(Color.RED);
        Path file = tmp.resolve(name);
        ImageIO.write(img, "png", file.toFile());
        return file;
    }

    private static XWPFDocument render(String markdown) throws Exception {
        return new XWPFDocument(new ByteArrayInputStream(DocxRenderer.render(markdown, "제목")));
    }

    /** Every picture in the document, wherever it sits (body paragraph or table cell). */
    private static long pictureCount(XWPFDocument doc) {
        long body = doc.getParagraphs().stream().flatMap(p -> p.getRuns().stream())
                .mapToLong(r -> r.getEmbeddedPictures().size()).sum();
        long inTables = doc.getTables().stream()
                .flatMap(t -> t.getRows().stream())
                .flatMap(r -> r.getTableCells().stream())
                .flatMap(c -> c.getParagraphs().stream())
                .flatMap(p -> p.getRuns().stream())
                .mapToLong(r -> r.getEmbeddedPictures().size()).sum();
        return body + inTables;
    }

    @Nested
    @DisplayName("이미지 삽입")
    class Images {

        @Test
        @DisplayName("한 줄짜리 이미지 토큰을 실제 그림으로 삽입한다")
        void embedsBlockImage() throws Exception {
            String md = DocxRenderer.imageToken(samplePng("a.png").toAbsolutePath().toString());

            assertThat(pictureCount(render(md))).isEqualTo(1);
        }

        @Test
        @DisplayName("표 셀 안 인라인 토큰도 셀 안에 그림으로 삽입한다")
        void embedsImageInsideTableCell() throws Exception {
            String token = DocxRenderer.imageToken(samplePng("b.png").toAbsolutePath().toString());
            String md = "| 항목 | 그림 |\n| --- | --- |\n| A | " + token + "(이미지 설명: 표 그림) |";

            XWPFDocument doc = render(md);

            assertThat(pictureCount(doc)).isEqualTo(1);
            XWPFTable table = doc.getTables().get(0);
            String cell = table.getRow(1).getCell(1).getText();
            assertThat(cell).contains("(이미지 설명: 표 그림)");
            assertThat(cell).doesNotContain("DOCX_IMAGE");   // 토큰이 글자로 새어나오지 않음
        }

        @Test
        @DisplayName("파일이 없으면 실패 대신 안내 문구를 남긴다")
        void degradesWhenFileMissing() throws Exception {
            String md = DocxRenderer.imageToken(tmp.resolve("gone.png").toAbsolutePath().toString());

            XWPFDocument doc = render(md);

            assertThat(pictureCount(doc)).isZero();
            assertThat(doc.getParagraphs().stream().map(XWPFParagraph::getText))
                    .anyMatch(t -> t.contains("이미지 없음"));
        }

        @Test
        @DisplayName("전처리부터 렌더까지: 글머리표 안 이미지 마커도 그림이 된다")
        void embedsImageFromListItemEndToEnd() throws Exception {
            Path png = samplePng("c.png");
            // DocumentExportService.renderDocx()의 리라이터와 같은 모양
            String md = ExportPreprocessor.preprocess(
                    "- [이미지: images/d1458/c.png]\n\n다음 본문", true, false,
                    (path, spot) -> {
                        String token = DocxRenderer.imageToken(png.toAbsolutePath().toString());
                        return spot == ExportPreprocessor.ImageSpot.TABLE_CELL
                                ? ExportPreprocessor.ImageReplacement.inline(token)
                                : ExportPreprocessor.ImageReplacement.ownLine(token);
                    });

            XWPFDocument doc = render(md);

            assertThat(pictureCount(doc)).isEqualTo(1);
            assertThat(doc.getParagraphs().stream().map(XWPFParagraph::getText))
                    .noneMatch(t -> t.contains("(이미지:"))     // 텍스트 대체로 격하되지 않음
                    .anyMatch(t -> t.contains("다음 본문"));
        }

        @Test
        @DisplayName("표 셀의 <br>는 글자 그대로 남지 않는다")
        void rendersCellLineBreaks() throws Exception {
            XWPFDocument doc = render("| A | 첫 줄<br>둘째 줄 |\n| --- | --- |\n| B | C |");

            assertThat(doc.getTables().get(0).getRow(0).getCell(1).getText())
                    .contains("첫 줄").contains("둘째 줄").doesNotContain("<br>");
        }
    }

    @Nested
    @DisplayName("코드 블록")
    class CodeBlocks {

        @Test
        @DisplayName("1x1 표 안에 좌측 정렬로 넣고 주석만 녹색으로 칠한다")
        void rendersCodeBlockAsSingleCellTable() throws Exception {
            String md = """
                    ```java
                    // 주석 줄
                    int a = 1;  // 뒤쪽 주석
                    /* 여러 줄
                       주석 */
                    String s = "// 문자열 안은 주석 아님";
                    ```""";

            XWPFDocument doc = render(md);

            assertThat(doc.getTables()).hasSize(1);
            XWPFTable table = doc.getTables().get(0);
            assertThat(table.getNumberOfRows()).isEqualTo(1);
            assertThat(table.getRow(0).getTableCells()).hasSize(1);

            XWPFParagraph p = table.getRow(0).getCell(0).getParagraphs().get(0);
            assertThat(p.getAlignment()).isEqualTo(org.apache.poi.xwpf.usermodel.ParagraphAlignment.LEFT);

            List<XWPFRun> green = p.getRuns().stream().filter(r -> "008000".equals(r.getColor())).toList();
            String greenText = green.stream().map(XWPFRun::text).reduce("", String::concat);
            String plainText = p.getRuns().stream().filter(r -> !"008000".equals(r.getColor()))
                    .map(XWPFRun::text).reduce("", String::concat);

            assertThat(greenText).contains("// 주석 줄", "// 뒤쪽 주석", "/* 여러 줄", "주석 */");
            assertThat(plainText).contains("int a = 1;");
            // 문자열 리터럴 안의 //는 주석이 아니다
            assertThat(plainText).contains("\"// 문자열 안은 주석 아님\"");
            assertThat(greenText).doesNotContain("문자열 안은");
        }

        @Test
        @DisplayName("# 주석은 칠하되 토큰 중간의 #은 건드리지 않는다")
        void colorsHashComments() throws Exception {
            XWPFDocument doc = render("```bash\n# 설치\napt install x  # 주석\necho a#b\n```");

            XWPFParagraph p = doc.getTables().get(0).getRow(0).getCell(0).getParagraphs().get(0);
            String greenText = p.getRuns().stream().filter(r -> "008000".equals(r.getColor()))
                    .map(XWPFRun::text).reduce("", String::concat);

            assertThat(greenText).contains("# 설치", "# 주석");
            assertThat(greenText).doesNotContain("a#b");
        }

        @Test
        @DisplayName("들여쓰기와 빈 줄을 그대로 보존한다")
        void keepsIndentation() throws Exception {
            XWPFDocument doc = render("```java\nclass A {\n    int x;\n}\n```");

            String text = doc.getTables().get(0).getRow(0).getCell(0).getText();
            assertThat(text).contains("    int x;");
        }
    }

    @Test
    @DisplayName("생성된 문서가 열리는 유효한 docx 바이트다")
    void producesReadableDocx() throws Exception {
        byte[] bytes = DocxRenderer.render("## 소제목\n\n본문 **강조**.\n\n- 항목 1", "문서");

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            assertThat(doc.getParagraphs().stream().map(XWPFParagraph::getText))
                    .anyMatch(t -> t.contains("소제목"))
                    .anyMatch(t -> t.contains("본문 강조"))
                    .anyMatch(t -> t.contains("• 항목 1"));
        }
        assertThat(Files.exists(tmp)).isTrue();
    }
}
