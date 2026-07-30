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

    /** The declared column widths (twips) of a table's {@code <w:tblGrid>}. */
    private static List<Integer> gridWidths(XWPFTable table) {
        return table.getCTTbl().getTblGrid().getGridColList().stream()
                .map(col -> ((java.math.BigInteger) col.getW()).intValue())
                .toList();
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
        @DisplayName("tblGrid와 셀 너비가 열 수만큼 기록된다 — Pages에서 표가 1열로 무너지지 않도록")
        void writesTableGrid() throws Exception {
            // POI 의 createTable() 은 <w:tblGrid> 를 아예 쓰지 않는다. Word 는 <w:tc> 개수로 열을
            // 복원하지만 Apple Pages 는 그러지 않아 모든 셀이 한 열에 세로로 쌓인다.
            XWPFDocument doc = render("| A | B | C |\n| --- | --- | --- |\n| 1 | 2 | 3 |");

            XWPFTable table = doc.getTables().get(0);
            assertThat(table.getCTTbl().getTblGrid()).isNotNull();
            assertThat(table.getCTTbl().getTblGrid().getGridColList()).hasSize(3);
            // 각 셀도 같은 너비를 들고 있어야 한다(그리드만 있고 tcW 가 없으면 뷰어별 해석이 또 갈린다)
            assertThat(table.getRow(0).getCell(0).getWidth()).isPositive();
            assertThat(table.getRow(1).getCell(2).getWidth()).isPositive();
        }

        @Test
        @DisplayName("열 너비가 내용 길이에 비례한다 — 라벨 열이 설명 열만큼 넓어지지 않도록")
        void distributesColumnWidthByContent() throws Exception {
            XWPFDocument doc = render("""
                    | 옵션 | 설명 |
                    | --- | --- |
                    | mode | 이미지 참조 처리 방식을 정한다. strip 이면 마커를 제거하고 describe 면 설명을 포함한다. |
                    | ocr | 스캔 PDF 페이지에 대해 Tesseract OCR 처리를 활성화할지 여부를 지정한다. |""");

            List<Integer> w = gridWidths(doc.getTables().get(0));
            assertThat(w).hasSize(2);
            assertThat(w.get(1)).isGreaterThan(w.get(0) * 2);      // 설명 열이 확실히 넓다
            assertThat(w.get(0) + w.get(1)).isEqualTo(451 * 20);   // 합계 = 본문 폭
        }

        @Test
        @DisplayName("내용 길이가 비슷하면 거의 균등하게 나뉜다")
        void keepsSimilarColumnsEven() throws Exception {
            XWPFDocument doc = render("| a | b | c |\n| --- | --- | --- |\n| 111 | 222 | 333 |");

            List<Integer> w = gridWidths(doc.getTables().get(0));
            int even = 451 * 20 / 3;
            assertThat(w).allSatisfy(x -> assertThat(x).isBetween((int) (even * 0.9), (int) (even * 1.1)));
            assertThat(w.stream().mapToInt(Integer::intValue).sum()).isEqualTo(451 * 20);
        }

        @Test
        @DisplayName("한 셀이 지나치게 길어도 나머지 열이 최소 폭을 유지한다")
        void keepsMinimumWidthForShortColumns() throws Exception {
            XWPFDocument doc = render("| A | B |\n| --- | --- |\n| 1 | " + "아주 긴 설명 ".repeat(40) + " |");

            List<Integer> w = gridWidths(doc.getTables().get(0));
            assertThat(w.get(0)).isGreaterThanOrEqualTo((int) (451 * 20 * 0.09));  // 최소 10% 바닥
            assertThat(w.get(0) + w.get(1)).isEqualTo(451 * 20);
        }

        @Test
        @DisplayName("테두리에 굵기(w:sz)가 명시된다 — Word 외 뷰어에서 선이 사라지지 않도록")
        void writesExplicitBorderWidth() throws Exception {
            // POI 의 createTable() 은 <w:top w:val="single"/> 처럼 선 스타일만 쓰고 w:sz 를 빼먹는다.
            // Word 는 기본 굵기를 채워 그리지만 Google Docs·Pages·LibreOffice 는 0 으로 읽어 아무것도
            // 그리지 않는다 — 코드 블록의 "박스"가 통째로 사라지는 원인.
            XWPFDocument doc = render("```java\nint a = 1;\n```\n\n| A | B |\n| --- | --- |\n| 1 | 2 |");

            assertThat(doc.getTables()).hasSize(2);
            for (XWPFTable table : doc.getTables()) {
                assertThat(table.getTopBorderSize()).isPositive();
                assertThat(table.getBottomBorderSize()).isPositive();
                assertThat(table.getLeftBorderSize()).isPositive();
                assertThat(table.getRightBorderSize()).isPositive();
                assertThat(table.getInsideHBorderSize()).isPositive();
                assertThat(table.getInsideVBorderSize()).isPositive();
            }
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
