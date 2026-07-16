package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.xddf.usermodel.chart.AxisPosition;
import org.apache.poi.xddf.usermodel.chart.ChartTypes;
import org.apache.poi.xddf.usermodel.chart.XDDFBarChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFCategoryDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFChartAxis;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory;
import org.apache.poi.xddf.usermodel.chart.XDDFNumericalDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFValueAxis;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFAutoShape;
import org.apache.poi.xslf.usermodel.XSLFChart;
import org.apache.poi.xslf.usermodel.XSLFConnectorShape;
import org.apache.poi.xslf.usermodel.XSLFDiagram;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFObjectShape;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA — PptxImageExtractor
 *
 * Regression for a filename bug: {@code PictureData.PictureType.extension} already includes
 * the leading dot (e.g. ".png"), so building the filename as {@code "..." + "." + ext} produced
 * a double dot ("s1_img1..png"). Such filenames trip DocumentController.getImage()'s path-
 * traversal guard (rejects any filename containing "..") and 400 even though the file exists.
 *
 * Also covers rasterization of drawing-tool shapes (groups, standalone connectors, textless
 * auto/freeform shapes) that the markdown converter's text-shape walk never sees: verifies actual
 * rendered pixel content (not just file existence), the size filter for trivial icons, the
 * text-bearing-shape exclusion (avoids double-capturing content already extracted as body text),
 * and that plain empty text boxes are never rasterized.
 */
class PptxImageExtractorTest {

    // 아래 대부분 테스트는 근접 클러스터링 경로를 검증하므로 rasterize-shapes=true로 실행한다
    // (프로덕션 기본값은 false — 클러스터링 안 함. false 기본 동작·표 합성은 별도 테스트에서 검증).
    private final PptxImageExtractor extractor = extractorWith(30.0, 15.0, true, true);
    private Path pptxPath;
    private Path imagesDir;

    /** app.pptx-image.* 설정값을 다르게 주입한 추출기를 만든다 — 옵션화된 값들이 실제로 동작을 바꾸는지 검증용. */
    private static PptxImageExtractor extractorWith(double minShapeDimensionPt, double clusterProximityPaddingPt) {
        return extractorWith(minShapeDimensionPt, clusterProximityPaddingPt, true, true);
    }

    private static PptxImageExtractor extractorWith(double minShapeDimensionPt, double clusterProximityPaddingPt,
                                                      boolean mergeAnnotatedPictures) {
        return extractorWith(minShapeDimensionPt, clusterProximityPaddingPt, mergeAnnotatedPictures, true);
    }

    private static PptxImageExtractor extractorWith(double minShapeDimensionPt, double clusterProximityPaddingPt,
                                                      boolean mergeAnnotatedPictures, boolean rasterizeShapes) {
        AppProperties props = mock(AppProperties.class);
        when(props.pptxImageSafe()).thenReturn(new AppProperties.PptxShapeExtractionConfig(
                minShapeDimensionPt, clusterProximityPaddingPt, mergeAnnotatedPictures, rasterizeShapes));
        return new PptxImageExtractor(props);
    }

    @BeforeEach
    void setUp() throws IOException {
        pptxPath = Files.createTempFile("pptx-test-", ".pptx");
        imagesDir = Files.createTempDirectory("pptx-images-");
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(pptxPath);
        deleteRecursively(imagesDir);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        }
    }

    private void writePptxWithOnePng() throws IOException {
        byte[] fakePng = "fake-png-bytes".getBytes();
        try (XMLSlideShow pptx = new XMLSlideShow()) {
            XSLFPictureData pd = pptx.addPicture(fakePng, PictureData.PictureType.PNG);
            XSLFSlide slide = pptx.createSlide();
            slide.createPicture(pd);
            try (OutputStream out = Files.newOutputStream(pptxPath)) {
                pptx.write(out);
            }
        }
    }

    /**
     * POI의 공개 API(createPicture(PictureData))는 항상 r:embed 관계만 만들 수 있어 외부 링크
     * 사진을 만들 방법이 없다 — 슬라이드 XML을 직접 조작해 {@code r:embed} 대신
     * {@code r:link}(TargetMode="External")를 쓰는 {@code <p:pic>}를 주입한다. 이러면
     * {@code XSLFPictureShape.getPictureData()}가 null을 반환한다(POI 소스 확인:
     * getBlipId()==null이면 embed 관계가 없다는 뜻이라 조기 반환).
     */
    private static void writeExternalLinkedPicturePptx(Path target) throws IOException {
        Files.deleteIfExists(target);
        try (XMLSlideShow pptx = new XMLSlideShow()) {
            pptx.createSlide();
            try (OutputStream out = Files.newOutputStream(target)) {
                pptx.write(out);
            }
        }

        Map<String, String> env = new HashMap<>();
        env.put("create", "false");
        URI uri = URI.create("jar:" + target.toUri());
        try (FileSystem zipfs = FileSystems.newFileSystem(uri, env)) {
            Path slideRelsPath = zipfs.getPath("/ppt/slides/_rels/slide1.xml.rels");
            String newRels = Files.readString(slideRelsPath).replace("</Relationships>",
                      "<Relationship Id=\"rIdExtPic\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" "
                    + "Target=\"https://example.com/photo.png\" TargetMode=\"External\"/></Relationships>");
            Files.writeString(zipfs.getPath("/ppt/slides/_rels/slide1.xml.rels"), newRels);

            Path slidePath = zipfs.getPath("/ppt/slides/slide1.xml");
            String pic =
                  "<p:pic>"
                + "<p:nvPicPr><p:cNvPr id=\"10\" name=\"ExternalPic\"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr>"
                + "<p:blipFill><a:blip xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" r:link=\"rIdExtPic\"/>"
                + "<a:stretch><a:fillRect/></a:stretch></p:blipFill>"
                + "<p:spPr><a:xfrm><a:off x=\"914400\" y=\"914400\"/><a:ext cx=\"1828800\" cy=\"1828800\"/></a:xfrm>"
                + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr>"
                + "</p:pic>";
            Files.writeString(zipfs.getPath("/ppt/slides/slide1.xml"),
                    Files.readString(slidePath).replace("</p:spTree>", pic + "</p:spTree>"));
        }
    }

    private void writePptx(Consumer<XMLSlideShow> builder) throws IOException {
        try (XMLSlideShow pptx = new XMLSlideShow()) {
            builder.accept(pptx);
            try (OutputStream out = Files.newOutputStream(pptxPath)) {
                pptx.write(out);
            }
        }
    }

    /**
     * 실제로 디코딩 가능한 최소 PNG 바이트를 만든다 — 클러스터에 합류한 사진은 (원본 그대로
     * 복사되는 verbatim 경로와 달리) POI의 DrawPictureShape가 실제로 디코딩해서 그리므로,
     * writePptxWithOnePng()의 가짜 바이트("fake-png-bytes")로는 래스터라이즈가 예외로 실패한다.
     */
    private static byte[] renderPngBytes(Color color, int width, int height) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** 지정한 파일에 흰색(0xFFFFFF)이 아닌 픽셀이 하나라도 있는지 확인한다 — 빈 캔버스가 아니라 실제로 뭔가 그려졌는지 검증. */
    private static boolean containsNonWhitePixel(Path pngFile) throws IOException {
        BufferedImage img = ImageIO.read(pngFile.toFile());
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) & 0xFFFFFF) != 0xFFFFFF) return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("extract — 파일명에 점(.)이 정확히 하나만 있다 (PictureType.extension 이 이미 점을 포함하므로 이중 점 방지)")
    void extract_fileNameHasSingleDot_notDoubled() throws IOException {
        writePptxWithOnePng();

        Map<Integer, List<String>> result = extractor.extract(pptxPath, "doc1", imagesDir);

        assertThat(result).containsKey(1);
        String relPath = result.get(1).get(0);
        String fileName = relPath.substring(relPath.lastIndexOf('/') + 1);

        assertThat(fileName).isEqualTo("s1_img1.png");
        assertThat(fileName).doesNotContain("..");
        assertThat(Files.exists(imagesDir.resolve(fileName))).isTrue();
    }

    @Test
    @DisplayName("그룹 도형(XSLFGroupShape)은 하나의 PNG로 래스터라이즈된다")
    void groupShapeIsRasterizedToNonBlankPng() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            XSLFGroupShape group = slide.createGroup();
            Rectangle2D bounds = new Rectangle2D.Double(0, 0, 200, 100);
            group.setAnchor(bounds);
            group.setInteriorAnchor(bounds);
            XSLFAutoShape box = group.createAutoShape();
            box.setAnchor(new Rectangle2D.Double(10, 10, 80, 40));
            box.setFillColor(Color.RED);
        });

        Map<Integer, List<String>> result = extractor.extract(pptxPath, "doc1", imagesDir);

        assertThat(result).containsKey(1);
        String fileName = fileNameOf(result.get(1).get(0));
        assertThat(fileName).isEqualTo("s1_img1.png");
        assertThat(containsNonWhitePixel(imagesDir.resolve(fileName))).isTrue();
    }

    @Test
    @DisplayName("extractWithOwners — 그룹 도형 이미지는 자신의 slide.getShapes() 인덱스로 태깅된다")
    void extractWithOwners_groupShapeImageIsTaggedWithOwnerIndex() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            XSLFGroupShape group = slide.createGroup();
            Rectangle2D bounds = new Rectangle2D.Double(0, 0, 200, 100);
            group.setAnchor(bounds);
            group.setInteriorAnchor(bounds);
            XSLFAutoShape box = group.createAutoShape();
            box.setAnchor(new Rectangle2D.Double(10, 10, 80, 40));
            box.setFillColor(Color.RED);
        });

        try (XMLSlideShow pptx = new XMLSlideShow(Files.newInputStream(pptxPath))) {
            Map<Integer, List<PptxImageExtractor.ExtractedImage>> result =
                    extractor.extractWithOwners(pptx, "doc1", imagesDir);

            assertThat(result).containsKey(1);
            PptxImageExtractor.ExtractedImage image = result.get(1).get(0);
            assertThat(image.ownerShapeIndices()).containsExactly(0); // 슬라이드에 그룹 하나뿐 — 인덱스 0
        }
    }

    @Test
    @DisplayName("extractWithOwners — 일반 사진은 소유 도형이 없다(빈 Set)")
    void extractWithOwners_plainPictureHasNoOwner() throws IOException {
        writePptxWithOnePng();

        try (XMLSlideShow pptx = new XMLSlideShow(Files.newInputStream(pptxPath))) {
            Map<Integer, List<PptxImageExtractor.ExtractedImage>> result =
                    extractor.extractWithOwners(pptx, "doc1", imagesDir);

            assertThat(result).containsKey(1);
            PptxImageExtractor.ExtractedImage image = result.get(1).get(0);
            assertThat(image.ownerShapeIndices()).isEmpty();
        }
    }

    @Test
    @DisplayName("extractWithOwners — SmartArt 이미지는 내부 렌더 도형이 아니라 바깥쪽 XSLFDiagram 프레임의 인덱스로 태깅된다")
    void extractWithOwners_smartArtImageOwnedByOuterDiagramIndex() throws IOException {
        PptxSmartArtFixture.write(pptxPath, List.of("기획팀", "개발팀"));

        try (XMLSlideShow pptx = new XMLSlideShow(Files.newInputStream(pptxPath))) {
            XSLFSlide slide = pptx.getSlides().get(0);
            List<XSLFShape> shapes = slide.getShapes();
            int diagramIndex = -1;
            for (int i = 0; i < shapes.size(); i++) {
                if (shapes.get(i) instanceof XSLFDiagram) {
                    diagramIndex = i;
                    break;
                }
            }
            assertThat(diagramIndex).isGreaterThanOrEqualTo(0);

            Map<Integer, List<PptxImageExtractor.ExtractedImage>> result =
                    extractor.extractWithOwners(pptx, "doc1", imagesDir);

            assertThat(result).containsKey(1);
            PptxImageExtractor.ExtractedImage image = result.get(1).get(0);
            assertThat(image.ownerShapeIndices()).containsExactly(diagramIndex);
        }
    }

    @Test
    @DisplayName("독립된 커넥터(화살표/선)도 래스터라이즈된다 — 가늘고 긴 형태도 크기 필터를 통과한다")
    void standaloneConnectorIsRasterized() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            XSLFConnectorShape connector = slide.createConnector();
            connector.setAnchor(new Rectangle2D.Double(0, 0, 200, 2)); // 길지만 얇음
            connector.setLineColor(Color.BLACK);
            connector.setLineWidth(2.0);
        });

        Map<Integer, List<String>> result = extractor.extract(pptxPath, "doc1", imagesDir);

        assertThat(result).containsKey(1);
        String fileName = fileNameOf(result.get(1).get(0));
        assertThat(containsNonWhitePixel(imagesDir.resolve(fileName))).isTrue();
    }

    @Test
    @DisplayName("텍스트 없는 도형은 래스터라이즈되지만, 근처에 시드(빈 도형/커넥터/그룹)가 없는 텍스트 도형은 혼자서는 렌더링되지 않는다")
    void blankAutoShapeIsRasterizedButIsolatedTextBearingIsNot() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            XSLFAutoShape blank = slide.createAutoShape();
            blank.setAnchor(new Rectangle2D.Double(0, 0, 100, 50));
            blank.setFillColor(Color.BLUE);

            // 클러스터링 근접 판정(패딩 15pt)에 걸리지 않도록 충분히 멀리 떨어뜨림 — 이 도형이
            // 혼자 있을 때는(근처에 시드가 없으면) 텍스트가 있어도 절대 래스터라이즈되지 않음을 검증.
            XSLFAutoShape withText = slide.createAutoShape();
            withText.setAnchor(new Rectangle2D.Double(0, 500, 100, 50));
            withText.setFillColor(Color.GREEN);
            withText.setText("본문에 이미 캡처되는 라벨");
        });

        Map<Integer, List<String>> result = extractor.extract(pptxPath, "doc1", imagesDir);

        assertThat(result).containsKey(1);
        assertThat(result.get(1)).hasSize(1); // 텍스트 있는 독립 도형은 제외되어 blank 도형 1개만 생성됨
    }

    @Test
    @DisplayName("빈 텍스트 상자(XSLFTextBox)는 도형이 아니라 텍스트 컨테이너이므로 래스터라이즈되지 않는다")
    void blankTextBoxIsNeverRasterized() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            XSLFTextBox textBox = slide.createTextBox();
            textBox.setAnchor(new Rectangle2D.Double(0, 0, 100, 50));
            // 텍스트를 설정하지 않음 — 완전히 빈 텍스트 상자
        });

        Map<Integer, List<String>> result = extractor.extract(pptxPath, "doc1", imagesDir);

        assertThat(result).doesNotContainKey(1);
    }

    @Test
    @DisplayName("최소 크기 미만의 도형은 아이콘/구분선으로 보고 래스터라이즈에서 제외된다")
    void tinyShapeBelowSizeThresholdIsFiltered() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            XSLFAutoShape tiny = slide.createAutoShape();
            tiny.setAnchor(new Rectangle2D.Double(0, 0, 10, 10)); // MIN_SHAPE_DIMENSION_PT(30) 미만
            tiny.setFillColor(Color.RED);
        });

        Map<Integer, List<String>> result = extractor.extract(pptxPath, "doc1", imagesDir);

        assertThat(result).doesNotContainKey(1);
    }

    @Test
    @DisplayName("커넥터가 두 도형 사이 '틈'에 있어 어느 쪽과도 겹치지 않아도, 패딩된 근접 판정으로 하나의 이미지로 묶인다")
    void connectorBridgesGapBetweenTwoShapesIntoOneCluster() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            XSLFAutoShape boxA = slide.createAutoShape();
            boxA.setAnchor(new Rectangle2D.Double(0, 0, 60, 60));
            boxA.setFillColor(Color.RED);

            // boxA(0~60)와도, boxB(120~180)와도 겹치지 않는 10pt 틈에 위치 — 순수 bbox 교차
            // 검사라면 어느 쪽 클러스터에도 속하지 못했을 케이스.
            XSLFConnectorShape connector = slide.createConnector();
            connector.setAnchor(new Rectangle2D.Double(70, 28, 40, 4));
            connector.setLineColor(Color.BLACK);
            connector.setLineWidth(2.0);

            XSLFAutoShape boxB = slide.createAutoShape();
            boxB.setAnchor(new Rectangle2D.Double(120, 0, 60, 60));
            boxB.setFillColor(Color.BLUE);
        });

        Map<Integer, List<String>> result = extractor.extract(pptxPath, "doc1", imagesDir);

        assertThat(result).containsKey(1);
        assertThat(result.get(1)).hasSize(1); // 세 도형이 하나의 번들 이미지로 묶임
        assertThat(containsNonWhitePixel(imagesDir.resolve(fileNameOf(result.get(1).get(0))))).isTrue();
    }

    @Test
    @DisplayName("텍스트가 있는 도형도 근처에 시드(빈 도형)가 있으면 함께 묶여 하나의 번들 이미지가 된다")
    void textBearingShapeJoinsNearbyClusterAsPassenger() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            XSLFAutoShape blank = slide.createAutoShape();
            blank.setAnchor(new Rectangle2D.Double(0, 0, 100, 50));
            blank.setFillColor(Color.BLUE);

            // 패딩(15pt) 이내의 5pt 틈 — 근접으로 판정되어 같은 클러스터에 합류해야 함.
            XSLFAutoShape withText = slide.createAutoShape();
            withText.setAnchor(new Rectangle2D.Double(0, 55, 100, 50));
            withText.setFillColor(Color.GREEN);
            withText.setText("연동거래 상세");
        });

        Map<Integer, List<String>> result = extractor.extract(pptxPath, "doc1", imagesDir);

        assertThat(result).containsKey(1);
        assertThat(result.get(1)).hasSize(1); // 별도 이미지 2장이 아니라 번들 이미지 1장
    }

    @Test
    @DisplayName("그룹 도형도 근처의 독립 커넥터와 하나의 클러스터로 묶인다")
    void groupBundlesWithNearbyConnector() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            XSLFGroupShape group = slide.createGroup();
            Rectangle2D bounds = new Rectangle2D.Double(0, 0, 100, 100);
            group.setAnchor(bounds);
            group.setInteriorAnchor(bounds);
            XSLFAutoShape inner = group.createAutoShape();
            inner.setAnchor(new Rectangle2D.Double(10, 10, 80, 80));
            inner.setFillColor(Color.RED);

            // 그룹 오른쪽 경계(x=100)에서 10pt 떨어진 커넥터 — 패딩(15pt) 이내.
            XSLFConnectorShape connector = slide.createConnector();
            connector.setAnchor(new Rectangle2D.Double(110, 40, 40, 4));
            connector.setLineColor(Color.BLACK);
            connector.setLineWidth(2.0);
        });

        Map<Integer, List<String>> result = extractor.extract(pptxPath, "doc1", imagesDir);

        assertThat(result).containsKey(1);
        assertThat(result.get(1)).hasSize(1); // 그룹 + 커넥터가 하나의 번들 이미지로 묶임
    }

    @Test
    @DisplayName("서로 멀리 떨어진 두 클러스터는 하나로 합쳐지지 않고 별개의 이미지로 남는다")
    void distantClustersStaySeparate() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            XSLFAutoShape first = slide.createAutoShape();
            first.setAnchor(new Rectangle2D.Double(0, 0, 60, 60));
            first.setFillColor(Color.RED);

            XSLFAutoShape second = slide.createAutoShape();
            second.setAnchor(new Rectangle2D.Double(500, 500, 60, 60)); // 패딩을 훨씬 벗어난 거리
            second.setFillColor(Color.BLUE);
        });

        Map<Integer, List<String>> result = extractor.extract(pptxPath, "doc1", imagesDir);

        assertThat(result).containsKey(1);
        assertThat(result.get(1)).hasSize(2); // 하나로 뭉치지 않고 각각 별도 이미지
    }

    @Test
    @DisplayName("app.pptx-image.cluster-proximity-padding-pt=0 이면 겹치지 않는 도형은 더 이상 하나로 묶이지 않는다")
    void clusterProximityPaddingIsConfigurable() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            XSLFAutoShape boxA = slide.createAutoShape();
            boxA.setAnchor(new Rectangle2D.Double(0, 0, 60, 60));
            boxA.setFillColor(Color.RED);

            // 기본값(15pt)이면 묶이지만, 패딩을 0으로 낮추면 겹치지 않는 이 10pt 틈은 더 이상
            // 이어지지 않아야 한다.
            XSLFAutoShape boxB = slide.createAutoShape();
            boxB.setAnchor(new Rectangle2D.Double(70, 0, 60, 60));
            boxB.setFillColor(Color.BLUE);
        });

        PptxImageExtractor zeroPadding = extractorWith(30.0, 0.0);
        Map<Integer, List<String>> result = zeroPadding.extract(pptxPath, "doc1", imagesDir);

        assertThat(result).containsKey(1);
        assertThat(result.get(1)).hasSize(2); // 패딩 0 → 별개의 클러스터 2개
    }

    @Test
    @DisplayName("app.pptx-image.min-shape-dimension-pt를 높이면 더 큰 도형도 아이콘으로 취급되어 제외된다")
    void minShapeDimensionIsConfigurable() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            XSLFAutoShape shape = slide.createAutoShape();
            shape.setAnchor(new Rectangle2D.Double(0, 0, 100, 50)); // 기본값(30pt)이면 통과하는 크기
            shape.setFillColor(Color.RED);
        });

        PptxImageExtractor strictThreshold = extractorWith(200.0, 15.0);
        Map<Integer, List<String>> result = strictThreshold.extract(pptxPath, "doc1", imagesDir);

        assertThat(result).doesNotContainKey(1); // 임계값을 200pt로 올리면 100x50 도형도 제외됨
    }

    @Test
    @DisplayName("사진 위에 겹친 주석 도형(시드)이 있으면 별도 이미지 2장이 아니라 하나의 합성 이미지로 합쳐진다")
    void pictureOverlappedByAnnotationShapeMergesIntoOneComposite() throws IOException {
        byte[] realPng = renderPngBytes(Color.RED, 100, 100);
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            XSLFPictureData pd = pptx.addPicture(realPng, PictureData.PictureType.PNG);
            XSLFPictureShape pic = slide.createPicture(pd);
            pic.setAnchor(new Rectangle2D.Double(0, 0, 100, 100));

            // 사진 위에 직접 겹쳐 그려진 강조 표시(텍스트 없는 도형 = 시드) — 화면 캡처 위에
            // 오류 부분을 동그라미로 표시하는 실제 사용 패턴을 흉내낸다.
            XSLFAutoShape mark = slide.createAutoShape();
            mark.setAnchor(new Rectangle2D.Double(20, 20, 40, 40));
            mark.setFillColor(Color.BLUE);
        });

        Map<Integer, List<String>> result = extractor.extract(pptxPath, "doc1", imagesDir);

        assertThat(result).containsKey(1);
        assertThat(result.get(1)).hasSize(1); // 사진 + 주석이 하나의 합성 PNG로 병합됨
        String fileName = fileNameOf(result.get(1).get(0));
        assertThat(fileName).isEqualTo("s1_img1.png"); // 래스터라이즈 결과이므로 .png (원본 확장자 아님)
        assertThat(containsNonWhitePixel(imagesDir.resolve(fileName))).isTrue();
    }

    @Test
    @DisplayName("사진 근처에 캡션(텍스트만 있는 도형)만 있고 진짜 시드가 없으면 병합되지 않고 사진은 원본 그대로 추출된다")
    void pictureNearCaptionOnlyDoesNotMerge() throws IOException {
        byte[] realPng = renderPngBytes(Color.RED, 100, 100);
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            XSLFPictureData pd = pptx.addPicture(realPng, PictureData.PictureType.PNG);
            XSLFPictureShape pic = slide.createPicture(pd);
            pic.setAnchor(new Rectangle2D.Double(0, 0, 100, 100));

            // 패딩(15pt) 이내로 인접한 캡션 — 하지만 텍스트가 있는 도형은 candidate일 뿐 시드가
            // 아니므로, 시드 없는 클러스터는 형성되지 않고 사진은 병합되지 않아야 한다.
            XSLFAutoShape caption = slide.createAutoShape();
            caption.setAnchor(new Rectangle2D.Double(0, 105, 100, 30));
            caption.setText("그림 1. 예시 화면");
        });

        Map<Integer, List<String>> result = extractor.extract(pptxPath, "doc1", imagesDir);

        assertThat(result).containsKey(1);
        assertThat(result.get(1)).hasSize(1); // 캡션은 본문 텍스트로 이미 캡처되므로 사진 1장만 남음
        String fileName = fileNameOf(result.get(1).get(0));
        assertThat(fileName).isEqualTo("s1_img1.png");
        assertThat(Files.readAllBytes(imagesDir.resolve(fileName))).isEqualTo(realPng); // 원본 바이트 그대로(재인코딩 아님)
    }

    @Test
    @DisplayName("merge-annotated-pictures=false 이면 사진과 근접한(그룹 아닌) 주석 도형이 있어도 병합되지 않고 별도 이미지로 남는다")
    void mergeDisabled_proximityOnlyPictureAndShape_stayAsSeparateImages() throws IOException {
        byte[] realPng = renderPngBytes(Color.RED, 100, 100);
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            XSLFPictureData pd = pptx.addPicture(realPng, PictureData.PictureType.PNG);
            XSLFPictureShape pic = slide.createPicture(pd);
            pic.setAnchor(new Rectangle2D.Double(0, 0, 100, 100));

            XSLFAutoShape mark = slide.createAutoShape();
            mark.setAnchor(new Rectangle2D.Double(20, 20, 40, 40));
            mark.setFillColor(Color.BLUE);
        });

        PptxImageExtractor mergeDisabled = extractorWith(30.0, 15.0, false);
        Map<Integer, List<String>> result = mergeDisabled.extract(pptxPath, "doc1", imagesDir);

        assertThat(result).containsKey(1);
        assertThat(result.get(1)).hasSize(2); // 사진(원본 그대로) + 도형(단독 래스터라이즈) 각각 별도 이미지
    }

    @Test
    @DisplayName("merge-annotated-pictures=false 여도 PPTX에서 실제로 그룹(Ctrl+G)된 사진+도형은 여전히 하나의 이미지로 합쳐진다")
    void mergeDisabled_realPptxGroupOfPictureAndShape_stillMergesIntoOneImage() throws IOException {
        byte[] realPng = renderPngBytes(Color.RED, 100, 100);
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            XSLFGroupShape group = slide.createGroup();
            Rectangle2D bounds = new Rectangle2D.Double(0, 0, 100, 100);
            group.setAnchor(bounds);
            group.setInteriorAnchor(bounds);

            XSLFPictureData pd = pptx.addPicture(realPng, PictureData.PictureType.PNG);
            XSLFPictureShape pic = group.createPicture(pd);
            pic.setAnchor(new Rectangle2D.Double(0, 0, 100, 100));

            XSLFAutoShape mark = group.createAutoShape();
            mark.setAnchor(new Rectangle2D.Double(20, 20, 40, 40));
            mark.setFillColor(Color.BLUE);
        });

        PptxImageExtractor mergeDisabled = extractorWith(30.0, 15.0, false);
        Map<Integer, List<String>> result = mergeDisabled.extract(pptxPath, "doc1", imagesDir);

        assertThat(result).containsKey(1);
        assertThat(result.get(1)).hasSize(1); // 실제 PPTX 그룹은 옵션과 무관하게 POI가 통째로 그려 항상 하나로 합쳐짐
        String fileName = fileNameOf(result.get(1).get(0));
        assertThat(containsNonWhitePixel(imagesDir.resolve(fileName))).isTrue();
    }

    @Test
    @DisplayName("OLE 객체의 내장 미리보기 그림은 실제 픽처처럼 그대로 추출된다")
    void oleObjectPreviewPictureIsExtractedVerbatim() throws IOException {
        byte[] fakePreviewPng = "fake-ole-preview-bytes".getBytes();
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            XSLFPictureData pd = pptx.addPicture(fakePreviewPng, PictureData.PictureType.PNG);
            XSLFObjectShape ole = slide.createOleShape(pd);
            ole.setAnchor(new Rectangle2D.Double(10, 10, 100, 100));
        });

        Map<Integer, List<String>> result = extractor.extract(pptxPath, "doc1", imagesDir);

        assertThat(result).containsKey(1);
        String fileName = fileNameOf(result.get(1).get(0));
        assertThat(Files.readAllBytes(imagesDir.resolve(fileName))).isEqualTo(fakePreviewPng);
    }

    @Test
    @DisplayName("SmartArt(다이어그램)의 렌더링 레이어(getGroupShape())는 하나의 PNG로 래스터라이즈된다")
    void smartArtGroupShapeIsRasterizedToNonBlankPng() throws IOException {
        PptxSmartArtFixture.write(pptxPath, List.of("기획팀", "개발팀"));

        Map<Integer, List<String>> result = extractor.extract(pptxPath, "doc1", imagesDir);

        assertThat(result).containsKey(1);
        String fileName = fileNameOf(result.get(1).get(0));
        assertThat(containsNonWhitePixel(imagesDir.resolve(fileName))).isTrue();
    }

    // ── rasterize-shapes 플래그 (기본 false: 느슨한 도형 클러스터링 안 함) ──────────────

    @Test
    @DisplayName("rasterize-shapes=false(기본): 아무것에도 안 겹친 느슨한 단일 도형은 이미지로 뽑히지 않는다")
    void rasterizeFalse_looseStandaloneShapeIsNotRasterized() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            XSLFAutoShape shape = slide.createAutoShape();
            shape.setAnchor(new Rectangle2D.Double(0, 0, 100, 50));
            shape.setFillColor(Color.BLUE);
        });

        PptxImageExtractor def = extractorWith(30.0, 15.0, true, false);
        Map<Integer, List<String>> result = def.extract(pptxPath, "doc1", imagesDir);

        assertThat(result).doesNotContainKey(1); // 클러스터링 없음 → 느슨한 도형은 드롭
    }

    @Test
    @DisplayName("rasterize-shapes=false(기본): 겹친 느슨한 도형들도 하나로 병합되지 않고 전부 드롭된다")
    void rasterizeFalse_overlappingLooseShapesDoNotMerge() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            XSLFAutoShape a = slide.createAutoShape();
            a.setAnchor(new Rectangle2D.Double(0, 0, 100, 50));
            a.setFillColor(Color.BLUE);
            XSLFAutoShape b = slide.createAutoShape();
            b.setAnchor(new Rectangle2D.Double(40, 20, 100, 50)); // a와 겹침
            b.setFillColor(Color.GREEN);
        });

        PptxImageExtractor def = extractorWith(30.0, 15.0, true, false);
        Map<Integer, List<String>> result = def.extract(pptxPath, "doc1", imagesDir);

        assertThat(result).doesNotContainKey(1); // 겹쳐도 병합 안 함 — 둘 다 드롭
    }

    @Test
    @DisplayName("rasterize-shapes=true: 겹친 느슨한 도형들은 기존대로 하나의 다이어그램 이미지로 병합된다")
    void rasterizeTrue_overlappingLooseShapesMergeIntoOne() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            XSLFAutoShape a = slide.createAutoShape();
            a.setAnchor(new Rectangle2D.Double(0, 0, 100, 50));
            a.setFillColor(Color.BLUE);
            XSLFAutoShape b = slide.createAutoShape();
            b.setAnchor(new Rectangle2D.Double(40, 20, 100, 50));
            b.setFillColor(Color.GREEN);
        });

        PptxImageExtractor on = extractorWith(30.0, 15.0, true, true);
        Map<Integer, List<String>> result = on.extract(pptxPath, "doc1", imagesDir);

        assertThat(result).containsKey(1);
        assertThat(result.get(1)).hasSize(1); // union-find 클러스터링 → 한 장
    }

    @Test
    @DisplayName("rasterize-shapes=false(기본)여도 그룹(Ctrl+G)은 항상 한 장의 이미지로 유지된다")
    void rasterizeFalse_groupIsStillRasterizedStandalone() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            XSLFGroupShape group = slide.createGroup();
            Rectangle2D bounds = new Rectangle2D.Double(0, 0, 100, 100);
            group.setAnchor(bounds);
            group.setInteriorAnchor(bounds);
            XSLFAutoShape inner = group.createAutoShape();
            inner.setAnchor(new Rectangle2D.Double(10, 10, 60, 60));
            inner.setFillColor(Color.BLUE);
        });

        PptxImageExtractor def = extractorWith(30.0, 15.0, true, false);
        Map<Integer, List<String>> result = def.extract(pptxPath, "doc1", imagesDir);

        assertThat(result).containsKey(1);
        assertThat(result.get(1)).hasSize(1);
        assertThat(containsNonWhitePixel(imagesDir.resolve(fileNameOf(result.get(1).get(0))))).isTrue();
    }

    @Test
    @DisplayName("사진+주석도형 병합은 rasterize-shapes와 무관하게 동작한다 (기본 false에서도 합성됨)")
    void pictureAnnotationMergeIsIndependentOfRasterizeShapes() throws IOException {
        byte[] realPng = renderPngBytes(Color.RED, 100, 100);
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            XSLFPictureData pd = pptx.addPicture(realPng, PictureData.PictureType.PNG);
            XSLFPictureShape pic = slide.createPicture(pd);
            pic.setAnchor(new Rectangle2D.Double(0, 0, 100, 100));
            XSLFAutoShape mark = slide.createAutoShape();
            mark.setAnchor(new Rectangle2D.Double(20, 20, 40, 40)); // 사진 위에 겹침
            mark.setFillColor(Color.BLUE);
        });

        // merge=true, rasterize-shapes=false(기본) — 그래도 사진+주석은 앵커 기반으로 합성되어야 함
        PptxImageExtractor def = extractorWith(30.0, 15.0, true, false);
        Map<Integer, List<String>> result = def.extract(pptxPath, "doc1", imagesDir);

        assertThat(result).containsKey(1);
        assertThat(result.get(1)).hasSize(1); // 사진+주석 = 합성 1장
        assertThat(fileNameOf(result.get(1).get(0))).isEqualTo("s1_img1.png"); // 원본이 아니라 래스터라이즈본
    }

    @Test
    @DisplayName("표 위에 겹친 시드 도형이 있으면 표+도형이 하나의 합성 이미지로 만들어진다 (rasterize-shapes=false에서도)")
    void tableWithOverlappingSeedShapeCompositesIntoOneImage() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            XSLFTable table = slide.createTable(2, 2);
            table.setAnchor(new Rectangle2D.Double(0, 0, 200, 100));
            table.getCell(0, 0).setText("A");
            table.getCell(0, 1).setText("B");

            XSLFAutoShape mark = slide.createAutoShape();
            mark.setAnchor(new Rectangle2D.Double(20, 20, 50, 40)); // 표 영역 위에 겹침
            mark.setFillColor(Color.RED);
        });

        PptxImageExtractor def = extractorWith(30.0, 15.0, true, false);
        Map<Integer, List<String>> result = def.extract(pptxPath, "doc1", imagesDir);

        assertThat(result).containsKey(1);
        assertThat(result.get(1)).hasSize(1); // 표+도형 합성 1장 (표는 별도로 MD 파이프 표로도 유지 — 변환기 담당)
        String fileName = fileNameOf(result.get(1).get(0));
        assertThat(fileName).isEqualTo("s1_img1.png");
        assertThat(containsNonWhitePixel(imagesDir.resolve(fileName))).isTrue();
    }

    @Test
    @DisplayName("표 위에 겹친 도형이 없으면 표는 이미지로 만들어지지 않는다 (MD 파이프 표로만)")
    void tableWithoutOverlappingShapeProducesNoImage() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            XSLFTable table = slide.createTable(2, 2);
            table.setAnchor(new Rectangle2D.Double(0, 0, 200, 100));
            table.getCell(0, 0).setText("A");
        });

        PptxImageExtractor def = extractorWith(30.0, 15.0, true, false);
        Map<Integer, List<String>> result = def.extract(pptxPath, "doc1", imagesDir);

        assertThat(result).doesNotContainKey(1); // 겹친 도형 없음 → 표 이미지 안 만듦
    }

    @Test
    @DisplayName("mc:Fallback 미리보기 그림이 없는 차트는 (POI가 라이브 렌더링을 지원하지 않으므로) 빈 이미지를 남기지 않고 조용히 건너뛴다")
    void chartWithoutFallbackPictureProducesNoImage() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            XSLFChart chart = pptx.createChart();
            chart.setTitleText("연도별 매출 추이");

            XDDFCategoryDataSource catDs = XDDFDataSourcesFactory.fromArray(new String[] {"2023", "2024"});
            XDDFNumericalDataSource<Double> valDs = XDDFDataSourcesFactory.fromArray(new Double[] {10.0, 20.0});
            XDDFChartAxis catAxis = chart.createCategoryAxis(AxisPosition.BOTTOM);
            XDDFValueAxis valAxis = chart.createValueAxis(AxisPosition.LEFT);
            XDDFBarChartData bar = (XDDFBarChartData) chart.createData(ChartTypes.BAR, catAxis, valAxis);
            bar.addSeries(catDs, valDs);
            chart.plot(bar);

            slide.addChart(chart, new Rectangle2D.Double(50, 50, 300, 200));
        });

        Map<Integer, List<String>> result = extractor.extract(pptxPath, "doc1", imagesDir);

        assertThat(result).doesNotContainKey(1); // POI가 직접 만든 차트는 mc:Fallback이 없음 — 빈 이미지 대신 스킵
    }

    @Test
    @DisplayName("이미 열린 XMLSlideShow를 넘기는 오버로드는 파일 경로를 넘기는 것과 동일한 결과를 낸다")
    void extractWithAlreadyOpenSlideShowMatchesPathOverload() throws IOException {
        writePptxWithOnePng();

        // PptxToMarkdownConverter는 이 오버로드를 써서 같은 파일을 XMLSlideShow로 두 번 열지 않는다
        // (한 번은 이미지 추출용, 한 번은 텍스트 변환용) — 결과가 Path 오버로드와 동일해야 한다.
        Map<Integer, List<String>> viaPath = extractor.extract(pptxPath, "doc1", imagesDir);

        Path imagesDir2 = Files.createTempDirectory("pptx-images-2-");
        try (XMLSlideShow pptx = new XMLSlideShow(Files.newInputStream(pptxPath))) {
            Map<Integer, List<String>> viaOpenSlideShow = extractor.extract(pptx, "doc1", imagesDir2);
            assertThat(viaOpenSlideShow.keySet()).isEqualTo(viaPath.keySet());
            assertThat(viaOpenSlideShow.get(1)).isEqualTo(viaPath.get(1));
        } finally {
            deleteRecursively(imagesDir2);
        }
    }

    @Test
    @DisplayName("외부 링크(embed 아닌) 사진은 로컬에 저장할 바이트가 없어 NPE 없이 조용히 건너뛴다")
    void externallyLinkedPictureIsSkippedWithoutThrowing() throws IOException {
        // XSLFPictureShape.getPictureData()는 getBlipId()==null(embed 관계 없음, 링크만 있음)이면
        // null을 반환한다 — addPictureData()에 null 가드가 없던 예전 코드였다면 pd.getType()에서
        // NPE가 나 문서 인덱싱 전체가 실패했을 것이다.
        writeExternalLinkedPicturePptx(pptxPath);

        Map<Integer, List<String>> result = extractor.extract(pptxPath, "doc1", imagesDir);

        assertThat(result).doesNotContainKey(1); // 저장할 바이트가 없으므로 이미지 목록에 나타나지 않음
    }

    private static String fileNameOf(String relPath) {
        return relPath.substring(relPath.lastIndexOf('/') + 1);
    }
}
