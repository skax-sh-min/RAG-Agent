package com.example.ragagent.service;

import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFAutoShape;
import org.apache.poi.xslf.usermodel.XSLFConnectorShape;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts embedded images from PPTX slides, and rasterizes "drawing tool" shapes that the
 * markdown converter's text-shape walk can never see: grouped shapes ({@link XSLFGroupShape} —
 * the author's own "these belong together" signal, so the whole group becomes one bundled image),
 * standalone connectors ({@link XSLFConnectorShape}, which never carry text), and standalone
 * auto/freeform shapes with no text (decorative diagram scaffolding — a shape with real text is
 * already captured as normal body text by {@link PptxToMarkdownConverter} and isn't re-rendered
 * here). Plain {@link XSLFTextBox}es are never rasterized even when empty — they're just empty
 * text containers, not drawn shapes. A minimum bounding-box dimension filters out trivial
 * icons/dividers. Rendering failures are skipped silently (graceful degradation, like the
 * EMF/WMF converters) rather than failing the whole extraction.
 *
 * Saves to imagesDir as s{slide}_img{n}.{ext} (real pictures) or s{slide}_img{n}.png (rasterized
 * shapes) — a single shared per-slide counter, so callers see one flat image list either way.
 */
@Component
public class PptxImageExtractor {

    /** Rendered pixels per anchor point — anchor sizes are modest, so upscale for legibility. */
    private static final double RENDER_SCALE = 2.0;
    /** Shapes whose longer side is below this (in points) are treated as icons/dividers, not diagrams. */
    private static final double MIN_SHAPE_DIMENSION_PT = 30.0;

    /** @return {slideNum(1-based) → relative image paths from dataDir} */
    public Map<Integer, List<String>> extract(Path pptxPath, String docId, Path imagesDir)
            throws IOException {
        Files.createDirectories(imagesDir);
        Map<Integer, List<String>> result = new LinkedHashMap<>();

        try (XMLSlideShow pptx = new XMLSlideShow(Files.newInputStream(pptxPath))) {
            int slideNum = 0;
            for (XSLFSlide slide : pptx.getSlides()) {
                slideNum++;
                List<String> paths = new ArrayList<>();
                int imgIdx = 0;
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFPictureShape pic) {
                        XSLFPictureData pd = pic.getPictureData();
                        PictureData.PictureType type = pd.getType();
                        // PictureType.extension already includes the leading dot (e.g. ".png") —
                        // strip it so "." + ext below doesn't double up into "img1..png".
                        String ext = (type != null) ? type.extension : "bin";
                        if (ext.startsWith(".")) ext = ext.substring(1);
                        imgIdx++;
                        String fileName = "s" + slideNum + "_img" + imgIdx + "." + ext;
                        Files.write(imagesDir.resolve(fileName), pd.getData());
                        paths.add("images/" + docId + "/" + fileName);
                    } else if (isRasterizableDrawingShape(shape)) {
                        String fileName = "s" + slideNum + "_img" + (imgIdx + 1) + ".png";
                        if (rasterize(shape, imagesDir.resolve(fileName))) {
                            imgIdx++;
                            paths.add("images/" + docId + "/" + fileName);
                        }
                    }
                }
                if (!paths.isEmpty()) result.put(slideNum, paths);
            }
        }
        return result;
    }

    /**
     * 텍스트 도형 순회에서는 절대 잡히지 않는 "그리기 도구" 요소인지 판정한다: 그룹(저자가 직접
     * 묶었다는 신호이므로 크기만 통과하면 항상 대상), 커넥터(화살표/선 — 원래 텍스트를 가질 수
     * 없음), 텍스트가 비어있는 일반/자유형 도형(장식용 도형 스캐폴딩 — 텍스트가 있으면 이미
     * PptxToMarkdownConverter가 본문으로 캡처하므로 중복 렌더링하지 않는다). 순수 텍스트 상자
     * (XSLFTextBox)는 비어 있어도 제외한다 — 그릴 내용이 없는 빈 텍스트 컨테이너일 뿐이다.
     */
    private boolean isRasterizableDrawingShape(XSLFShape shape) {
        if (shape instanceof XSLFGroupShape) return passesSizeFilter(shape);
        if (shape instanceof XSLFConnectorShape) return passesSizeFilter(shape);
        if (shape instanceof XSLFTextBox) return false;
        if (shape instanceof XSLFAutoShape autoShape) {
            String text = autoShape.getText();
            return (text == null || text.isBlank()) && passesSizeFilter(shape);
        }
        return false;
    }

    /** 가로/세로 중 큰 쪽만 기준으로 삼아, 가늘고 긴 커넥터가 걸러지지 않도록 한다. */
    private boolean passesSizeFilter(XSLFShape shape) {
        Rectangle2D anchor = shape.getAnchor();
        return Math.max(anchor.getWidth(), anchor.getHeight()) >= MIN_SHAPE_DIMENSION_PT;
    }

    /**
     * 도형을 자신의 anchor 크기에 {@link #RENDER_SCALE}를 곱한 해상도로 PNG에 래스터라이즈한다.
     * 렌더링 실패 시 파일을 남기지 않고 false를 반환해 호출자가 카운터/목록에 반영하지 않게 한다.
     */
    private boolean rasterize(XSLFShape shape, Path targetFile) {
        Rectangle2D anchor = shape.getAnchor();
        int width = (int) Math.ceil(anchor.getWidth() * RENDER_SCALE);
        int height = (int) Math.ceil(anchor.getHeight() * RENDER_SCALE);
        if (width <= 0 || height <= 0) return false;

        try {
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = img.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, width, height);
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                shape.draw(graphics, new Rectangle2D.Double(0, 0, width, height));
            } finally {
                graphics.dispose();
            }
            return ImageIO.write(img, "png", targetFile.toFile());
        } catch (Exception e) {
            return false;
        }
    }
}
