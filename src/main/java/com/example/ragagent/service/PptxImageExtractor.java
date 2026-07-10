package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import org.apache.poi.sl.draw.DrawFactory;
import org.apache.poi.sl.draw.Drawable;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFAutoShape;
import org.apache.poi.xslf.usermodel.XSLFConnectorShape;
import org.apache.poi.xslf.usermodel.XSLFDiagram;
import org.apache.poi.xslf.usermodel.XSLFGraphicFrame;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFObjectShape;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
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
 * the author's own "these belong together" signal), standalone connectors
 * ({@link XSLFConnectorShape}, which never carry text), and auto/freeform shapes with no text
 * (decorative diagram scaffolding). A shape with real text is normally already captured as body
 * text by {@link PptxToMarkdownConverter}, so it's only rasterized here when it's part of a
 * cluster (see below) — never on its own, to avoid a redundant duplicate image.
 *
 * <b>Proximity clustering</b>: a real diagram is rarely one shape — a connector usually sits in
 * the *gap* between the boxes it links, not overlapping either one, so strict bounding-box
 * intersection would miss it. Each shape's bounding box is padded outward by
 * {@code app.pptx-image.cluster-proximity-padding-pt} ({@link AppProperties.PptxShapeExtractionConfig})
 * before testing intersection, and connected shapes (union-find over that padded-intersection
 * graph) are rasterized together as a single bundled image, preserving each member's relative
 * position and paint order. A cluster is only rasterized if it contains at least one "seed" shape
 * (group/connector/textless auto-shape) — a cluster made up purely of nearby text-bearing shapes
 * with no drawing element isn't a diagram and is left alone. Text-bearing shapes join a cluster as
 * passengers, never as the reason one forms. A cluster larger than {@link #MAX_CLUSTER_SHAPES} (a
 * crowded/busy slide) falls back to rasterizing just its seed members individually, to avoid one
 * giant slide-sized image.
 *
 * {@link XSLFTable} and {@link XSLFPictureShape} never join a cluster — tables stay as structured
 * markdown pipe-tables ({@code PptxToMarkdownConverter.appendTable()}), and real pictures are
 * extracted verbatim below. Plain {@link XSLFTextBox}es are never rasterized when empty — they're
 * just empty text containers, not drawn shapes.
 *
 * A minimum bounding-box dimension ({@code app.pptx-image.min-shape-dimension-pt}) filters out
 * trivial icons/dividers before they can seed a cluster. Rendering failures are skipped silently
 * (graceful degradation, like the EMF/WMF converters) rather than failing the whole extraction.
 *
 * <b>{@code XSLFGraphicFrame} variants</b> ({@code XSLFTable} aside) get their own handling since
 * none of them can be drawn "live" by POI: an OLE embed ({@link XSLFObjectShape}) always carries
 * its own embedded preview picture, extracted verbatim like a real picture. A SmartArt frame
 * ({@link XSLFDiagram}) has no drawable frame of its own — {@code getGroupShape()} is the actual
 * rendered box/connector layer, so it's fed into the same proximity-clustering pipeline as an
 * ordinary {@link XSLFGroupShape} (as one seed, not its individual children). A chart frame has no
 * rendering path in POI at all; its only recoverable image is a {@code mc:Fallback} preview
 * picture PowerPoint may or may not have embedded — extracted verbatim when present, silently
 * skipped otherwise (its title text is still captured by {@code PptxToMarkdownConverter}).
 *
 * Saves to imagesDir as s{slide}_img{n}.{ext} (real pictures) or s{slide}_img{n}.png (rasterized
 * shapes/clusters) — a single shared per-slide counter, so callers see one flat image list either way.
 */
@Component
public class PptxImageExtractor {

    /** Rendered pixels per anchor point — anchor sizes are modest, so upscale for legibility. */
    private static final double RENDER_SCALE = 2.0;
    /** Clusters larger than this are treated as a crowded slide, not a diagram — see class javadoc. */
    private static final int MAX_CLUSTER_SHAPES = 25;

    private final double minShapeDimensionPt;
    private final double clusterProximityPaddingPt;

    public PptxImageExtractor(AppProperties props) {
        AppProperties.PptxShapeExtractionConfig config = props.pptxImageSafe();
        this.minShapeDimensionPt = config.minShapeDimensionPt();
        this.clusterProximityPaddingPt = config.clusterProximityPaddingPt();
    }

    private enum ShapeRole { SEED, CANDIDATE, NOT_ELIGIBLE }

    /** A shape eligible for clustering, tagged with whether it can seed a cluster on its own. */
    private record Clusterable(XSLFShape shape, boolean seed) {
    }

    /** @return {slideNum(1-based) → relative image paths from dataDir} */
    public Map<Integer, List<String>> extract(Path pptxPath, String docId, Path imagesDir)
            throws IOException {
        try (XMLSlideShow pptx = new XMLSlideShow(Files.newInputStream(pptxPath))) {
            return extract(pptx, docId, imagesDir);
        }
    }

    /**
     * Same as {@link #extract(Path, String, Path)} but reuses an already-open
     * {@link XMLSlideShow} instead of parsing the file again — {@link PptxToMarkdownConverter}
     * needs its own open slideshow for text conversion anyway, so it calls this overload to avoid
     * parsing the same PPTX twice (real cost on large decks; harmless to correctness either way).
     */
    public Map<Integer, List<String>> extract(XMLSlideShow pptx, String docId, Path imagesDir)
            throws IOException {
        Files.createDirectories(imagesDir);
        Map<Integer, List<String>> result = new LinkedHashMap<>();

        int slideNum = 0;
        for (XSLFSlide slide : pptx.getSlides()) {
            slideNum++;
            List<String> paths = processSlide(slide, slideNum, docId, imagesDir);
            if (!paths.isEmpty()) result.put(slideNum, paths);
        }
        return result;
    }

    private List<String> processSlide(XSLFSlide slide, int slideNum, String docId, Path imagesDir)
            throws IOException {
        List<String> paths = new ArrayList<>();
        int[] imgIdx = {0};

        // Preserves slide.getShapes() order — needed so clusters render members back-to-front
        // in their original paint order.
        List<Clusterable> clusterable = new ArrayList<>();

        for (XSLFShape shape : slide.getShapes()) {
            if (shape instanceof XSLFPictureShape pic) {
                addPicture(pic, slideNum, imgIdx, docId, imagesDir, paths);
            } else if (shape instanceof XSLFObjectShape ole) {
                // OLE embed: always carries its own preview picture (that's how OOXML lets a
                // viewer render it without running the source app) — save it directly, no
                // clustering/rasterization needed.
                addOlePreview(ole, slideNum, imgIdx, docId, imagesDir, paths);
            } else if (shape instanceof XSLFDiagram diagram) {
                // SmartArt: getGroupShape() is the real rendered drawing (actual box/connector
                // shapes with real anchors), unlike the outer XSLFDiagram frame itself — POI's
                // DrawFactory only knows how to draw the frame's (usually absent) fallback
                // picture, not the diagram live. Feed the group shape into the same
                // proximity-clustering/rasterization pipeline as an ordinary XSLFGroupShape.
                XSLFDiagram.XSLFDiagramGroupShape diagramGroup = diagram.getGroupShape();
                if (diagramGroup != null && passesSizeFilter(diagramGroup)) {
                    clusterable.add(new Clusterable(diagramGroup, true));
                }
            } else if (shape instanceof XSLFGraphicFrame frame && frame.hasChart()) {
                // Charts have no live-rendering path in POI — only a best-effort mc:Fallback
                // preview picture that PowerPoint may or may not have embedded.
                XSLFPictureShape fallback = frame.getFallbackPicture();
                if (fallback != null) addPicture(fallback, slideNum, imgIdx, docId, imagesDir, paths);
            } else if (!(shape instanceof XSLFTable)) {
                ShapeRole role = classify(shape);
                if (role != ShapeRole.NOT_ELIGIBLE) {
                    clusterable.add(new Clusterable(shape, role == ShapeRole.SEED));
                }
            }
        }

        for (List<Clusterable> cluster : clusterByProximity(clusterable)) {
            if (cluster.size() > MAX_CLUSTER_SHAPES) {
                // Too crowded to be one coherent diagram — fall back to capturing just the seeds.
                for (Clusterable c : cluster) {
                    if (c.seed()) tryRasterize(List.of(c.shape()), slideNum, imgIdx, docId, imagesDir, paths);
                }
            } else {
                List<XSLFShape> members = cluster.stream().map(Clusterable::shape).toList();
                tryRasterize(members, slideNum, imgIdx, docId, imagesDir, paths);
            }
        }

        return paths;
    }

    private void addPicture(XSLFPictureShape pic, int slideNum, int[] imgIdx, String docId,
                             Path imagesDir, List<String> paths) throws IOException {
        addPictureData(pic.getPictureData(), slideNum, imgIdx, docId, imagesDir, paths);
    }

    /** OLE 객체의 내장 미리보기 그림을 저장한다 — 외부 링크 OLE(내장 미리보기 없음)는 조용히 건너뛴다. */
    private void addOlePreview(XSLFObjectShape ole, int slideNum, int[] imgIdx, String docId,
                                Path imagesDir, List<String> paths) throws IOException {
        XSLFPictureData pd = ole.getPictureData();
        if (pd == null) return;
        addPictureData(pd, slideNum, imgIdx, docId, imagesDir, paths);
    }

    private void addPictureData(XSLFPictureData pd, int slideNum, int[] imgIdx, String docId,
                                 Path imagesDir, List<String> paths) throws IOException {
        PictureData.PictureType type = pd.getType();
        // PictureType.extension already includes the leading dot (e.g. ".png") — strip it so
        // "." + ext below doesn't double up into "img1..png".
        String ext = (type != null) ? type.extension : "bin";
        if (ext.startsWith(".")) ext = ext.substring(1);
        imgIdx[0]++;
        String fileName = "s" + slideNum + "_img" + imgIdx[0] + "." + ext;
        Files.write(imagesDir.resolve(fileName), pd.getData());
        paths.add("images/" + docId + "/" + fileName);
    }

    private void tryRasterize(List<XSLFShape> members, int slideNum, int[] imgIdx, String docId,
                               Path imagesDir, List<String> paths) {
        String fileName = "s" + slideNum + "_img" + (imgIdx[0] + 1) + ".png";
        if (rasterize(members, imagesDir.resolve(fileName))) {
            imgIdx[0]++;
            paths.add("images/" + docId + "/" + fileName);
        }
    }

    /**
     * 클러스터링 대상인지, 대상이면 단독으로 클러스터를 시작할 수 있는 "시드"인지 판정한다.
     * 그룹(저자가 직접 묶었다는 신호)·커넥터(화살표/선 — 텍스트를 가질 수 없음)·텍스트 없는
     * 일반/자유형 도형은 시드. 텍스트가 있는 도형(텍스트 상자 포함)은 시드 주변에 있을 때만
     * 함께 묶이는 candidate — 이미 본문 텍스트로 캡처되므로 혼자서는 절대 래스터라이즈하지
     * 않는다. 빈 텍스트 상자는 그릴 내용이 없으므로 대상에서 제외한다.
     */
    private ShapeRole classify(XSLFShape shape) {
        if (shape instanceof XSLFGroupShape) {
            return passesSizeFilter(shape) ? ShapeRole.SEED : ShapeRole.NOT_ELIGIBLE;
        }
        if (shape instanceof XSLFConnectorShape) {
            return passesSizeFilter(shape) ? ShapeRole.SEED : ShapeRole.NOT_ELIGIBLE;
        }
        if (shape instanceof XSLFTextBox textBox) {
            String text = textBox.getText();
            return (text != null && !text.isBlank()) ? ShapeRole.CANDIDATE : ShapeRole.NOT_ELIGIBLE;
        }
        if (shape instanceof XSLFAutoShape autoShape) {
            String text = autoShape.getText();
            if (text == null || text.isBlank()) {
                return passesSizeFilter(shape) ? ShapeRole.SEED : ShapeRole.NOT_ELIGIBLE;
            }
            return ShapeRole.CANDIDATE;
        }
        return ShapeRole.NOT_ELIGIBLE;
    }

    /** 가로/세로 중 큰 쪽만 기준으로 삼아, 가늘고 긴 커넥터가 걸러지지 않도록 한다. */
    private boolean passesSizeFilter(XSLFShape shape) {
        Rectangle2D anchor = shape.getAnchor();
        return Math.max(anchor.getWidth(), anchor.getHeight()) >= minShapeDimensionPt;
    }

    /**
     * 패딩된 바운딩박스 교차를 간선으로 하는 union-find로 연결 요소를 구한 뒤, 시드가 하나도
     * 없는 요소(텍스트 도형끼리만 우연히 가까운 경우)는 다이어그램이 아니므로 버린다. 각 연결
     * 요소 내부 순서는 slide.getShapes() 원래 순서를 그대로 유지한다 — 클러스터를 그릴 때
     * paint order(z-order)가 뒤바뀌지 않도록.
     */
    private List<List<Clusterable>> clusterByProximity(List<Clusterable> shapes) {
        int n = shapes.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        Rectangle2D[] padded = new Rectangle2D[n];
        for (int i = 0; i < n; i++) padded[i] = pad(shapes.get(i).shape().getAnchor());

        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (padded[i].intersects(padded[j])) {
                    union(parent, i, j);
                }
            }
        }

        Map<Integer, List<Clusterable>> byRoot = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            byRoot.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(shapes.get(i));
        }

        List<List<Clusterable>> result = new ArrayList<>();
        for (List<Clusterable> component : byRoot.values()) {
            boolean hasSeed = component.stream().anyMatch(Clusterable::seed);
            if (hasSeed) result.add(component);
        }
        return result;
    }

    private Rectangle2D pad(Rectangle2D r) {
        return new Rectangle2D.Double(
                r.getX() - clusterProximityPaddingPt,
                r.getY() - clusterProximityPaddingPt,
                r.getWidth() + 2 * clusterProximityPaddingPt,
                r.getHeight() + 2 * clusterProximityPaddingPt);
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int i, int j) {
        int ri = find(parent, i);
        int rj = find(parent, j);
        if (ri != rj) parent[ri] = rj;
    }

    /**
     * 도형 목록(단일 도형 또는 클러스터)을 하나의 PNG로 래스터라이즈한다. 전체 도형을 감싸는
     * 바운딩박스를 캔버스로 잡고, 좌표축을 한 번만 이동/확대한 뒤 각 도형을 원래 좌표 그대로
     * 순서대로 그린다 — 개별 도형을 캔버스 전체에 맞춰 그리면(fit) 여러 도형의 상대적 위치가
     * 무너지므로, 공유된 좌표 변환 하나로 모든 도형을 그리는 방식이 필요하다. 렌더링 실패 시
     * 파일을 남기지 않고 false를 반환해 호출자가 카운터/목록에 반영하지 않게 한다.
     */
    private boolean rasterize(List<XSLFShape> members, Path targetFile) {
        if (members.isEmpty()) return false;

        Rectangle2D union = null;
        for (XSLFShape shape : members) {
            Rectangle2D anchor = shape.getAnchor();
            union = (union == null) ? anchor : union.createUnion(anchor);
        }

        int width = (int) Math.ceil(union.getWidth() * RENDER_SCALE);
        int height = (int) Math.ceil(union.getHeight() * RENDER_SCALE);
        if (width <= 0 || height <= 0) return false;

        try {
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = img.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, width, height);
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // POI's own slide renderer seeds this hint before drawing any top-level shape;
                // DrawGroupShape.draw() reads it to compute its child coordinate transform and
                // NPEs if it's absent — bypassing DrawSheet/DrawSlide (as this single/multi-shape
                // renderer does) means we have to seed it ourselves.
                graphics.setRenderingHint(Drawable.GROUP_TRANSFORM, new AffineTransform());
                graphics.scale(RENDER_SCALE, RENDER_SCALE);
                graphics.translate(-union.getX(), -union.getY());

                DrawFactory factory = DrawFactory.getInstance(graphics);
                for (XSLFShape shape : members) {
                    factory.getDrawable(shape).draw(graphics);
                }
            } finally {
                graphics.dispose();
            }
            return ImageIO.write(img, "png", targetFile.toFile());
        } catch (Exception e) {
            return false;
        }
    }
}
