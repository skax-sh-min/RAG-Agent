package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.xmlbeans.XmlCursor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.xml.namespace.QName;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DocxAnnotationShapeMerger} — VML 도형 파싱(findShapes)과 Java2D 합성(compose) 단위테스트.
 * DOCX 전체 변환 경유 통합 동작은 {@code DocxToMarkdownConverterTest}에서 검증한다.
 */
class DocxAnnotationShapeMergerTest {

    private static final String W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String VML_NS = "urn:schemas-microsoft-com:vml";

    private DocxAnnotationShapeMerger merger(boolean enabled) {
        AppProperties props = mock(AppProperties.class);
        when(props.docxImageSafe()).thenReturn(new AppProperties.DocxShapeExtractionConfig(enabled));
        return new DocxAnnotationShapeMerger(props);
    }

    /** w:r 아래에 w:pict > v:{tag}(attrs) DOM 구조를 만들어 넣는다(실제 Word가 쓰는 레거시 VML 형태). */
    static void addVmlShape(XWPFParagraph p, String tag, Map<String, String> attrs) {
        XWPFRun run = p.createRun();
        try (XmlCursor cur = run.getCTR().newCursor()) {
            cur.toEndToken();
            cur.beginElement(new QName(W_NS, "pict", "w"));
            cur.beginElement(new QName(VML_NS, tag, "v"));
            for (Map.Entry<String, String> e : attrs.entrySet()) {
                cur.insertAttributeWithValue(e.getKey(), e.getValue());
            }
        }
    }

    static byte[] pngBytes(int width, int height, Color fill) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(fill);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    // ── findShapes ────────────────────────────────────────────────────────

    @Test
    @DisplayName("findShapes: style 좌표가 있는 v:rect/v:oval을 파싱한다")
    void findShapes_parsesRectAndOval() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph p = doc.createParagraph();
            addVmlShape(p, "rect", Map.of(
                    "style", "position:absolute;left:10pt;top:20pt;width:50pt;height:30pt",
                    "strokecolor", "red"));
            addVmlShape(p, "oval", Map.of(
                    "style", "position:absolute;left:5pt;top:5pt;width:40pt;height:40pt"));

            List<DocxAnnotationShapeMerger.VmlShape> shapes = merger(true).findShapes(p);

            assertThat(shapes).hasSize(2);
            DocxAnnotationShapeMerger.VmlShape rect = shapes.get(0);
            assertThat(rect.tag()).isEqualTo("rect");
            assertThat(rect.left()).isEqualTo(10.0);
            assertThat(rect.top()).isEqualTo(20.0);
            assertThat(rect.width()).isEqualTo(50.0);
            assertThat(rect.height()).isEqualTo(30.0);
            assertThat(rect.strokeColor()).isEqualTo("red");
            assertThat(shapes.get(1).tag()).isEqualTo("oval");
        }
    }

    @Test
    @DisplayName("findShapes: v:line은 from/to 속성에서 바운딩박스를 유도한다")
    void findShapes_parsesLineFromTo() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph p = doc.createParagraph();
            addVmlShape(p, "line", Map.of("from", "10pt,40pt", "to", "60pt,15pt"));

            List<DocxAnnotationShapeMerger.VmlShape> shapes = merger(true).findShapes(p);

            assertThat(shapes).hasSize(1);
            DocxAnnotationShapeMerger.VmlShape line = shapes.get(0);
            assertThat(line.tag()).isEqualTo("line");
            assertThat(line.left()).isEqualTo(10.0);
            assertThat(line.top()).isEqualTo(15.0);
            assertThat(line.width()).isEqualTo(50.0);
            assertThat(line.height()).isEqualTo(25.0);
            assertThat(line.fromY()).isEqualTo(40.0);
            assertThat(line.toY()).isEqualTo(15.0);
        }
    }

    @Test
    @DisplayName("findShapes: 위치를 해석할 수 없는 도형은 버린다 (style 없음)")
    void findShapes_dropsUnpositionableShape() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph p = doc.createParagraph();
            addVmlShape(p, "rect", Map.of("strokecolor", "blue")); // no style box

            assertThat(merger(true).findShapes(p)).isEmpty();
        }
    }

    @Test
    @DisplayName("findShapes: 기능 비활성(merge-annotated-shapes=false)이면 항상 빈 리스트")
    void findShapes_disabledReturnsEmpty() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph p = doc.createParagraph();
            addVmlShape(p, "rect", Map.of(
                    "style", "position:absolute;left:10pt;top:20pt;width:50pt;height:30pt"));

            assertThat(merger(false).findShapes(p)).isEmpty();
        }
    }

    @Test
    @DisplayName("findShapes: 도형 없는 문단은 빈 리스트")
    void findShapes_noShapes() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph p = doc.createParagraph();
            p.createRun().setText("텍스트만 있는 문단");

            assertThat(merger(true).findShapes(p)).isEmpty();
        }
    }

    @Test
    @DisplayName("findShapes: in/cm 단위 좌표는 pt로 정규화된다")
    void findShapes_normalizesUnitsToPoints() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph p = doc.createParagraph();
            addVmlShape(p, "rect", Map.of(
                    "style", "position:absolute;left:1in;top:0pt;width:2cm;height:10pt"));

            List<DocxAnnotationShapeMerger.VmlShape> shapes = merger(true).findShapes(p);

            assertThat(shapes).hasSize(1);
            assertThat(shapes.get(0).left()).isEqualTo(72.0);            // 1in = 72pt
            assertThat(shapes.get(0).width()).isCloseTo(56.693, org.assertj.core.data.Offset.offset(0.01)); // 2cm
        }
    }

    // ── compose ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("compose: 사진 위에 도형 스트로크가 그려진 합성 PNG를 만든다")
    void compose_paintsShapeOverPicture() throws Exception {
        byte[] pic = pngBytes(100, 60, Color.BLUE);
        var rect = new DocxAnnotationShapeMerger.VmlShape(
                "rect", 10, 10, 40, 20, null, null, null, null, "red", null, false);

        byte[] composite = merger(true).compose(pic, 100.0, 60.0, List.of(rect));

        assertThat(composite).isNotNull();
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(composite));
        assertThat(img.getWidth()).isEqualTo(100);
        assertThat(img.getHeight()).isEqualTo(60);
        // 사진 픽셀은 파란색 유지 (도형 밖 지점)
        assertThat(new Color(img.getRGB(80, 50)).getBlue()).isGreaterThan(200);
        // 도형 상단 변(중앙) 근처에서 빨간 스트로크 픽셀을 찾을 수 있어야 함
        boolean foundRed = false;
        for (int y = 8; y <= 12 && !foundRed; y++) {
            Color c = new Color(img.getRGB(30, y));
            foundRed = c.getRed() > 150 && c.getGreen() < 100 && c.getBlue() < 100;
        }
        assertThat(foundRed).as("red stroke on rect top edge").isTrue();
    }

    @Test
    @DisplayName("compose: 사진 경계를 벗어나는 도형은 캔버스를 확장해 담는다")
    void compose_expandsCanvasForOutOfBoundsShape() throws Exception {
        byte[] pic = pngBytes(100, 60, Color.WHITE);
        var rect = new DocxAnnotationShapeMerger.VmlShape(
                "rect", 80, 40, 50, 40, null, null, null, null, null, null, false);

        byte[] composite = merger(true).compose(pic, 100.0, 60.0, List.of(rect));

        assertThat(composite).isNotNull();
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(composite));
        assertThat(img.getWidth()).isEqualTo(130);  // union(0..100, 80..130)
        assertThat(img.getHeight()).isEqualTo(80);  // union(0..60, 40..80)
    }

    @Test
    @DisplayName("compose: 디코드 불가 바이트/빈 도형 목록이면 null (호출부 원본 폴백)")
    void compose_returnsNullOnBadInput() throws Exception {
        byte[] pic = pngBytes(50, 50, Color.GRAY);
        var rect = new DocxAnnotationShapeMerger.VmlShape(
                "rect", 0, 0, 10, 10, null, null, null, null, null, null, false);

        assertThat(merger(true).compose(new byte[]{1, 2, 3}, 50, 50, List.of(rect))).isNull();
        assertThat(merger(true).compose(pic, 50, 50, List.of())).isNull();
    }

    @Test
    @DisplayName("compose: 비정상적으로 큰 도형 좌표는 안전 상한에 걸려 null")
    void compose_rejectsOversizedCanvas() throws Exception {
        byte[] pic = pngBytes(50, 50, Color.GRAY);
        var huge = new DocxAnnotationShapeMerger.VmlShape(
                "rect", 0, 0, 100_000, 100_000, null, null, null, null, null, null, false);

        assertThat(merger(true).compose(pic, 50, 50, List.of(huge))).isNull();
    }
}
