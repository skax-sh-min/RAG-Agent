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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * {@link XSLFTable} never joins a cluster — tables stay as structured markdown pipe-tables
 * ({@code PptxToMarkdownConverter.appendTable()}). {@link XSLFPictureShape} is the one exception
 * to "verbatim extraction": authors frequently draw markup (a highlight circle, an arrow, a
 * callout) directly on top of a screenshot/photo, and extracting the picture and that markup as
 * two disconnected images would strand the annotation with no context. A picture therefore also
 * joins the same proximity-clustering pass — as a passenger only, never a seed (a lone picture
 * must never pull in unrelated nearby shapes) — and gets flattened together with any overlapping
 * seed cluster into one composite PNG. A picture with no nearby seed, or whose cluster fails to
 * rasterize, falls back to the original verbatim extraction exactly as before. Plain
 * {@link XSLFTextBox}es are never rasterized when empty — they're just empty text containers, not
 * drawn shapes.
 *
 * <b>{@code app.pptx-image.merge-annotated-pictures}</b> ({@code true} by default) toggles the
 * paragraph above: {@code false} disables proximity-based merging for pictures entirely — a
 * top-level picture always extracts verbatim, and only pictures the author actually grouped in
 * PowerPoint (nested inside a real {@link XSLFGroupShape}, never reaching the top-level shape
 * dispatch below) still merge with their group-mates, since that is POI's own object model and is
 * unaffected by this flag either way.
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
    private final boolean mergeAnnotatedPictures;

    public PptxImageExtractor(AppProperties props) {
        AppProperties.PptxShapeExtractionConfig config = props.pptxImageSafe();
        this.minShapeDimensionPt = config.minShapeDimensionPt();
        this.clusterProximityPaddingPt = config.clusterProximityPaddingPt();
        this.mergeAnnotatedPictures = config.mergeAnnotatedPictures();
    }

    private enum ShapeRole { SEED, CANDIDATE, NOT_ELIGIBLE }

    /**
     * A shape eligible for clustering, tagged with whether it can seed a cluster on its own, and
     * (if it's a group/SmartArt shape) which top-level {@code slide.getShapes()} index "owns" it —
     * see {@link ExtractedImage} for why this index exists. {@code ownerIndex} is {@code -1} for
     * anything that isn't itself a correlatable group/diagram source (connectors, auto-shapes,
     * text-bearing passengers, pictures).
     */
    private record Clusterable(XSLFShape shape, boolean seed, int ownerIndex) {
    }

    /**
     * One extracted/rasterized image, plus the 0-based index/indices (into that slide's
     * {@code slide.getShapes()} — the same list {@link PptxToMarkdownConverter#extractSlide} can
     * independently compute, since both classes share one already-open {@link XMLSlideShow}) of
     * the top-level shape(s) that "own" it: a plain {@code XSLFGroupShape}'s own index, or (for
     * SmartArt) the outer {@code XSLFDiagram} frame's index — never the inner
     * {@code getGroupShape()} render layer, which doesn't appear in {@code slide.getShapes()} at
     * all. Empty for anything {@link PptxToMarkdownConverter} doesn't wrap in its own bracket
     * block (plain pictures, OLE previews, chart frames aside — see below): those stay hoisted at
     * the top of the slide exactly as before, unaffected by ownership. A cluster that merges two
     * adjacent top-level groups (rare — padded bounding boxes happened to intersect) legitimately
     * reports both indices; the caller may then place the same image marker in both groups' blocks.
     */
    public record ExtractedImage(String path, Set<Integer> ownerShapeIndices) {
    }

    /** @return {slideNum(1-based) → relative image paths from dataDir} */
    public Map<Integer, List<String>> extract(Path pptxPath, String imageId, Path imagesDir)
            throws IOException {
        try (XMLSlideShow pptx = new XMLSlideShow(Files.newInputStream(pptxPath))) {
            return extract(pptx, imageId, imagesDir);
        }
    }

    /**
     * Same as {@link #extract(Path, String, Path)} but reuses an already-open
     * {@link XMLSlideShow} instead of parsing the file again — {@link PptxToMarkdownConverter}
     * needs its own open slideshow for text conversion anyway, so it calls this overload to avoid
     * parsing the same PPTX twice (real cost on large decks; harmless to correctness either way).
     */
    public Map<Integer, List<String>> extract(XMLSlideShow pptx, String imageId, Path imagesDir)
            throws IOException {
        Map<Integer, List<ExtractedImage>> withOwners = extractWithOwners(pptx, imageId, imagesDir);
        Map<Integer, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<ExtractedImage>> entry : withOwners.entrySet()) {
            result.put(entry.getKey(), entry.getValue().stream().map(ExtractedImage::path).toList());
        }
        return result;
    }

    /**
     * Same as {@link #extract(XMLSlideShow, String, Path)} but also reports, for each image,
     * which top-level shape(s) on that slide "own" it ({@link ExtractedImage}) — used by
     * {@link PptxToMarkdownConverter} to place a group/diagram/chart's own image marker inside
     * that shape's bracket block instead of only ever hoisting every image to the top of the
     * slide.
     */
    public Map<Integer, List<ExtractedImage>> extractWithOwners(XMLSlideShow pptx, String imageId, Path imagesDir)
            throws IOException {
        Files.createDirectories(imagesDir);
        Map<Integer, List<ExtractedImage>> result = new LinkedHashMap<>();

        int slideNum = 0;
        for (XSLFSlide slide : pptx.getSlides()) {
            slideNum++;
            List<ExtractedImage> images = processSlide(slide, slideNum, imageId, imagesDir);
            if (!images.isEmpty()) result.put(slideNum, images);
        }
        return result;
    }

    private List<ExtractedImage> processSlide(XSLFSlide slide, int slideNum, String imageId, Path imagesDir)
            throws IOException {
        List<ExtractedImage> images = new ArrayList<>();
        int[] imgIdx = {0};

        // Preserves slide.getShapes() order — needed so clusters render members back-to-front
        // in their original paint order.
        List<Clusterable> clusterable = new ArrayList<>();
        // Real pictures are collected here instead of extracted immediately — a picture that
        // ends up in a rasterized cluster (see below) is "consumed" and must NOT also be
        // extracted verbatim; only leftover, unconsumed pictures fall back to that.
        List<XSLFPictureShape> pictures = new ArrayList<>();

        int topLevelIndex = -1;
        for (XSLFShape shape : slide.getShapes()) {
            topLevelIndex++;
            if (shape instanceof XSLFPictureShape pic) {
                if (mergeAnnotatedPictures) {
                    pictures.add(pic);
                    // Never a seed on its own — a picture with no nearby annotation shape must
                    // not spontaneously pull in unrelated nearby shapes into a merge. Never a
                    // correlation owner either — PptxToMarkdownConverter never wraps a plain
                    // picture in its own bracket block.
                    clusterable.add(new Clusterable(pic, false, -1));
                } else {
                    // app.pptx-image.merge-annotated-pictures=false: never join proximity
                    // clustering — a top-level picture always extracts verbatim. A picture that
                    // is genuinely grouped with other shapes in PowerPoint never reaches this
                    // branch at all (it's nested inside the XSLFGroupShape below, not a top-level
                    // shape), so real author-made groups still merge either way.
                    addPicture(pic, slideNum, imgIdx, imageId, imagesDir, images, Set.of());
                }
            } else if (shape instanceof XSLFObjectShape ole) {
                // OLE embed: always carries its own preview picture (that's how OOXML lets a
                // viewer render it without running the source app) — save it directly, no
                // clustering/rasterization needed.
                addOlePreview(ole, slideNum, imgIdx, imageId, imagesDir, images);
            } else if (shape instanceof XSLFDiagram diagram) {
                // SmartArt: getGroupShape() is the real rendered drawing (actual box/connector
                // shapes with real anchors), unlike the outer XSLFDiagram frame itself — POI's
                // DrawFactory only knows how to draw the frame's (usually absent) fallback
                // picture, not the diagram live. Feed the group shape into the same
                // proximity-clustering/rasterization pipeline as an ordinary XSLFGroupShape.
                // Ownership is tagged with topLevelIndex — the OUTER XSLFDiagram frame's own
                // index — since diagramGroup itself never appears in slide.getShapes() and is
                // therefore not an index PptxToMarkdownConverter could ever resolve.
                XSLFDiagram.XSLFDiagramGroupShape diagramGroup = diagram.getGroupShape();
                if (diagramGroup != null && passesSizeFilter(diagramGroup)) {
                    clusterable.add(new Clusterable(diagramGroup, true, topLevelIndex));
                }
            } else if (shape instanceof XSLFGraphicFrame frame && frame.hasChart()) {
                // Charts have no live-rendering path in POI — only a best-effort mc:Fallback
                // preview picture that PowerPoint may or may not have embedded.
                XSLFPictureShape fallback = frame.getFallbackPicture();
                if (fallback != null) {
                    addPicture(fallback, slideNum, imgIdx, imageId, imagesDir, images, Set.of(topLevelIndex));
                }
            } else if (!(shape instanceof XSLFTable)) {
                ShapeRole role = classify(shape);
                if (role != ShapeRole.NOT_ELIGIBLE) {
                    // Only a plain XSLFGroupShape is a correlation owner here — connectors/
                    // auto-shapes/text-bearing candidates never get their own bracket block from
                    // PptxToMarkdownConverter, so tagging their index would have no consumer.
                    int owner = (shape instanceof XSLFGroupShape) ? topLevelIndex : -1;
                    clusterable.add(new Clusterable(shape, role == ShapeRole.SEED, owner));
                }
            }
        }

        // Identity-based: two POI shape wrappers are only "the same picture" by reference here,
        // not by equals()/hashCode() (unspecified for XSLFShape).
        Set<XSLFPictureShape> consumedPictures = Collections.newSetFromMap(new IdentityHashMap<>());
        for (List<Clusterable> cluster : clusterByProximity(clusterable)) {
            if (cluster.size() > MAX_CLUSTER_SHAPES) {
                // Too crowded to be one coherent diagram — fall back to capturing just the seeds.
                // Pictures are never seeds, so any picture caught in an oversized cluster is left
                // unconsumed and extracted verbatim below, same as if it had no cluster at all.
                for (Clusterable c : cluster) {
                    if (c.seed()) {
                        Set<Integer> owners = c.ownerIndex() >= 0 ? Set.of(c.ownerIndex()) : Set.of();
                        tryRasterize(List.of(c.shape()), slideNum, imgIdx, imageId, imagesDir, images, owners);
                    }
                }
            } else {
                List<XSLFShape> members = cluster.stream().map(Clusterable::shape).toList();
                // A cluster can (rarely) merge more than one group/diagram — e.g. two adjacent
                // top-level groups whose padded bounding boxes happened to intersect — in which
                // case the resulting composite image legitimately belongs to all of them.
                Set<Integer> owners = new LinkedHashSet<>();
                for (Clusterable c : cluster) {
                    if (c.ownerIndex() >= 0) owners.add(c.ownerIndex());
                }
                if (tryRasterize(members, slideNum, imgIdx, imageId, imagesDir, images, owners)) {
                    for (Clusterable c : cluster) {
                        if (c.shape() instanceof XSLFPictureShape pic) consumedPictures.add(pic);
                    }
                }
                // rasterize() failure (e.g. undecodable picture bytes) leaves every member of
                // this cluster — including any picture — unconsumed, so pictures still fall back
                // to verbatim extraction below rather than being silently dropped.
            }
        }

        for (XSLFPictureShape pic : pictures) {
            if (!consumedPictures.contains(pic)) {
                addPicture(pic, slideNum, imgIdx, imageId, imagesDir, images, Set.of());
            }
        }

        return images;
    }

    private void addPicture(XSLFPictureShape pic, int slideNum, int[] imgIdx, String imageId,
                             Path imagesDir, List<ExtractedImage> images, Set<Integer> owners) throws IOException {
        addPictureData(pic.getPictureData(), slideNum, imgIdx, imageId, imagesDir, images, owners);
    }

    /** OLE 객체의 내장 미리보기 그림을 저장한다 — 외부 링크 OLE(내장 미리보기 없음)는 addPictureData()가 조용히 건너뛴다. */
    private void addOlePreview(XSLFObjectShape ole, int slideNum, int[] imgIdx, String imageId,
                                Path imagesDir, List<ExtractedImage> images) throws IOException {
        addPictureData(ole.getPictureData(), slideNum, imgIdx, imageId, imagesDir, images, Set.of());
    }

    /**
     * {@code pd}는 외부 링크(embed가 아닌 {@code r:link}) 픽처·OLE에서 null일 수 있다
     * ({@code XSLFPictureShape.getPictureData()}는 {@code getBlipId() == null}이면 null 반환) —
     * 저장할 로컬 바이트가 없으므로 조용히 건너뛴다. 실사진·OLE 미리보기·차트 fallback 세 호출
     * 경로가 모두 이 메서드를 거치므로 가드를 한 곳에 두면 셋 다 동일하게 보호된다.
     */
    private void addPictureData(XSLFPictureData pd, int slideNum, int[] imgIdx, String imageId,
                                 Path imagesDir, List<ExtractedImage> images, Set<Integer> owners) throws IOException {
        if (pd == null) return;
        PictureData.PictureType type = pd.getType();
        // PictureType.extension already includes the leading dot (e.g. ".png") — strip it so
        // "." + ext below doesn't double up into "img1..png".
        String ext = (type != null) ? type.extension : "bin";
        if (ext.startsWith(".")) ext = ext.substring(1);
        imgIdx[0]++;
        String fileName = "s" + slideNum + "_img" + imgIdx[0] + "." + ext;
        Files.write(imagesDir.resolve(fileName), pd.getData());
        images.add(new ExtractedImage("images/" + imageId + "/" + fileName, owners));
    }

    /** @return true if the composite was actually written (members can be treated as "consumed") */
    private boolean tryRasterize(List<XSLFShape> members, int slideNum, int[] imgIdx, String imageId,
                                  Path imagesDir, List<ExtractedImage> images, Set<Integer> owners) {
        String fileName = "s" + slideNum + "_img" + (imgIdx[0] + 1) + ".png";
        if (rasterize(members, imagesDir.resolve(fileName))) {
            imgIdx[0]++;
            images.add(new ExtractedImage("images/" + imageId + "/" + fileName, owners));
            return true;
        }
        return false;
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
