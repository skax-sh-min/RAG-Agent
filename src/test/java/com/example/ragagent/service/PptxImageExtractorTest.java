package com.example.ragagent.service;

import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFAutoShape;
import org.apache.poi.xslf.usermodel.XSLFConnectorShape;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

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

    private final PptxImageExtractor extractor = new PptxImageExtractor();
    private Path pptxPath;
    private Path imagesDir;

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

    private void writePptx(Consumer<XMLSlideShow> builder) throws IOException {
        try (XMLSlideShow pptx = new XMLSlideShow()) {
            builder.accept(pptx);
            try (OutputStream out = Files.newOutputStream(pptxPath)) {
                pptx.write(out);
            }
        }
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
    @DisplayName("텍스트 없는 도형은 래스터라이즈되지만, 텍스트가 있는 도형은 본문에서 이미 캡처되므로 중복 렌더링되지 않는다")
    void blankAutoShapeIsRasterizedButTextBearingIsNot() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            XSLFAutoShape blank = slide.createAutoShape();
            blank.setAnchor(new Rectangle2D.Double(0, 0, 100, 50));
            blank.setFillColor(Color.BLUE);

            XSLFAutoShape withText = slide.createAutoShape();
            withText.setAnchor(new Rectangle2D.Double(0, 60, 100, 50));
            withText.setFillColor(Color.GREEN);
            withText.setText("본문에 이미 캡처되는 라벨");
        });

        Map<Integer, List<String>> result = extractor.extract(pptxPath, "doc1", imagesDir);

        assertThat(result).containsKey(1);
        assertThat(result.get(1)).hasSize(1); // 텍스트 있는 도형은 제외되어 1개만 생성됨
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

    private static String fileNameOf(String relPath) {
        return relPath.substring(relPath.lastIndexOf('/') + 1);
    }
}
