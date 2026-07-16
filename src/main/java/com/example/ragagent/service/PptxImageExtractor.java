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
 * text by {@link PptxToMarkdownConverter}, so it's never rasterized on its own (only as a cluster
 * passenger — see below — to avoid a redundant duplicate image).
 *
 * <p><b>Two shape-emission modes, chosen by {@code app.pptx-image.rasterize-shapes}
 * ({@link AppProperties.PptxShapeExtractionConfig}, default {@code false}):</b>
 *
 * <p><b>{@code rasterize-shapes=true}</b> — the pre-existing <b>proximity clustering</b>: a real
 * diagram is rarely one shape — a connector usually sits in the *gap* between the boxes it links,
 * not overlapping either, so each shape's bounding box is padded outward by
 * {@code app.pptx-image.cluster-proximity-padding-pt} before testing intersection, and connected
 * shapes (union-find over that padded-intersection graph) are rasterized together as one bundled
 * image, preserving each member's relative position and paint order. A cluster is only rasterized
 * if it contains at least one "seed" (group/connector/textless auto-shape); text-bearing shapes and
 * tables join only as passengers, never as the reason one forms. A cluster larger than
 * {@link #MAX_CLUSTER_SHAPES} falls back to rasterizing just its seeds individually.
 *
 * <p><b>{@code rasterize-shapes=false} (default)</b> — no loose-shape clustering: overlapping loose
 * shapes are NOT merged into one blob, and a lone standalone connector/auto-shape produces no image
 * at all. Only "anchor" objects emit: groups / SmartArt (one image each), a table with an
 * overlapping seed shape (composited — see below), and pictures (with overlapping annotation seeds
 * composited on, per {@code merge-annotated-pictures}). Loose seeds not consumed by an anchor are
 * dropped. See {@link #rasterizeAnchorsOnly}; the clustering path is {@link #rasterizeWithClustering}.
 *
 * <p><b>Always (both modes):</b>
 * <ul>
 *   <li><b>Groups / SmartArt</b> render as one image each — a real author grouping ({@link
 *       XSLFGroupShape}) or a SmartArt frame ({@link XSLFDiagram}, whose {@code getGroupShape()} is
 *       the actual drawable layer) is never split apart.</li>
 *   <li><b>Tables + overlapping seed shape</b>: authors draw markup (a highlight circle, arrow) on
 *       top of a table to flag a cell — a {@link XSLFTable} with any overlapping seed is composited
 *       with it into one image. The table is <em>also</em> emitted as a markdown pipe-table by
 *       {@code PptxToMarkdownConverter.appendTable()} (independent path); a table with no overlapping
 *       seed produces no image (markdown-only), and never rasterizes on its own.</li>
 *   <li><b>Pictures + overlapping annotation seed</b> ({@code app.pptx-image.merge-annotated-pictures},
 *       {@code true} by default, independent of {@code rasterize-shapes}): a highlight/arrow drawn on
 *       a screenshot composites with it into one PNG rather than stranding the annotation as a
 *       separate image; a picture with no overlapping seed extracts verbatim. {@code false} disables
 *       this — pictures always extract verbatim (author-made PowerPoint groups still merge via POI's
 *       own group rendering, unaffected by any flag).</li>
 * </ul>
 *
 * <p>A minimum bounding-box dimension ({@code app.pptx-image.min-shape-dimension-pt}) filters out
 * trivial icons/dividers before they can seed. Empty {@link XSLFTextBox}es are never rasterized.
 * Rendering failures are skipped silently (graceful degradation, like the EMF/WMF converters).
 *
 * <p><b>Other {@code XSLFGraphicFrame} variants</b>: an OLE embed ({@link XSLFObjectShape}) carries
 * its own preview picture, extracted verbatim. A chart frame has no live-rendering path in POI; its
 * only recoverable image is a {@code mc:Fallback} preview PowerPoint may or may not have embedded —
 * extracted when present, silently skipped otherwise (its title text is captured by the converter).
 *
 * <p>Saves to imagesDir as s{slide}_img{n}.{ext} (real pictures) or s{slide}_img{n}.png (rasterized
 * shapes/clusters/composites) — a single shared per-slide counter, so callers see one flat list.
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
    private final boolean rasterizeShapes;

    public PptxImageExtractor(AppProperties props) {
        AppProperties.PptxShapeExtractionConfig config = props.pptxImageSafe();
        this.minShapeDimensionPt = config.minShapeDimensionPt();
        this.clusterProximityPaddingPt = config.clusterProximityPaddingPt();
        this.mergeAnnotatedPictures = config.mergeAnnotatedPictures();
        this.rasterizeShapes = config.rasterizeShapes();
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

        // Categorize top-level shapes in slide.getShapes() (paint) order. OLE previews and chart
        // fallback pictures are emitted immediately — they're neither anchors nor cluster members.
        List<XSLFPictureShape> pictures = new ArrayList<>();
        List<XSLFTable> tables = new ArrayList<>();
        // "always" seeds (groups / SmartArt) render as one image each regardless of rasterizeShapes;
        // "loose" seeds (connectors / textless auto-shapes) only cluster (true) or annotate an
        // anchor (false), and are otherwise dropped.
        List<Clusterable> alwaysSeeds = new ArrayList<>();
        List<Clusterable> looseSeeds = new ArrayList<>();
        List<XSLFShape> candidates = new ArrayList<>(); // text-bearing shapes: true-path passengers only

        int topLevelIndex = -1;
        for (XSLFShape shape : slide.getShapes()) {
            topLevelIndex++;
            if (shape instanceof XSLFPictureShape pic) {
                pictures.add(pic);
            } else if (shape instanceof XSLFObjectShape ole) {
                // OLE embed always carries its own preview picture — save directly.
                addOlePreview(ole, slideNum, imgIdx, imageId, imagesDir, images);
            } else if (shape instanceof XSLFDiagram diagram) {
                // SmartArt: getGroupShape() is the real rendered layer (the outer XSLFDiagram frame
                // isn't drawable live). Owner is the OUTER frame's index since diagramGroup never
                // appears in slide.getShapes().
                XSLFDiagram.XSLFDiagramGroupShape diagramGroup = diagram.getGroupShape();
                if (diagramGroup != null && passesSizeFilter(diagramGroup)) {
                    alwaysSeeds.add(new Clusterable(diagramGroup, true, topLevelIndex));
                }
            } else if (shape instanceof XSLFGraphicFrame frame && frame.hasChart()) {
                // Charts: only a best-effort mc:Fallback preview picture, if PowerPoint embedded one.
                XSLFPictureShape fallback = frame.getFallbackPicture();
                if (fallback != null) {
                    addPicture(fallback, slideNum, imgIdx, imageId, imagesDir, images, Set.of(topLevelIndex));
                }
            } else if (shape instanceof XSLFTable table) {
                tables.add(table);
            } else {
                ShapeRole role = classify(shape);
                if (role == ShapeRole.SEED) {
                    if (shape instanceof XSLFGroupShape) {
                        alwaysSeeds.add(new Clusterable(shape, true, topLevelIndex));
                    } else {
                        // Connectors / textless auto-shapes: never a correlation owner (index -1).
                        looseSeeds.add(new Clusterable(shape, true, -1));
                    }
                } else if (role == ShapeRole.CANDIDATE) {
                    candidates.add(shape);
                }
            }
        }

        if (rasterizeShapes) {
            rasterizeWithClustering(pictures, tables, alwaysSeeds, looseSeeds, candidates,
                    slideNum, imgIdx, imageId, imagesDir, images);
        } else {
            rasterizeAnchorsOnly(pictures, tables, alwaysSeeds, looseSeeds,
                    slideNum, imgIdx, imageId, imagesDir, images);
        }
        return images;
    }

    /**
     * {@code rasterize-shapes=true} — the pre-existing union-find proximity clustering. Every seed
     * (groups, SmartArt, connectors, textless auto-shapes) plus non-seed passengers (text-bearing
     * shapes, pictures when {@code merge-annotated-pictures}, and tables) is unioned by padded
     * bounding-box overlap and each connected component with ≥1 seed is rasterized together into
     * one image. Tables join as passengers only (a table alone forms no cluster → stays
     * markdown-only), so a shape drawn over a table still composites with it.
     */
    private void rasterizeWithClustering(List<XSLFPictureShape> pictures, List<XSLFTable> tables,
                                         List<Clusterable> alwaysSeeds, List<Clusterable> looseSeeds,
                                         List<XSLFShape> candidates, int slideNum, int[] imgIdx,
                                         String imageId, Path imagesDir, List<ExtractedImage> images)
            throws IOException {
        List<Clusterable> clusterable = new ArrayList<>();
        clusterable.addAll(alwaysSeeds);
        clusterable.addAll(looseSeeds);
        for (XSLFShape c : candidates) clusterable.add(new Clusterable(c, false, -1));
        if (mergeAnnotatedPictures) {
            for (XSLFPictureShape pic : pictures) clusterable.add(new Clusterable(pic, false, -1));
        }
        // Tables are always non-seed passengers — a lone table never rasterizes (no seed), but a
        // shape over a table pulls it into that cluster.
        for (XSLFTable table : tables) clusterable.add(new Clusterable(table, false, -1));

        // Identity-based: two POI shape wrappers are only "the same picture" by reference here.
        Set<XSLFPictureShape> consumedPictures = Collections.newSetFromMap(new IdentityHashMap<>());
        for (List<Clusterable> cluster : clusterByProximity(clusterable)) {
            if (cluster.size() > MAX_CLUSTER_SHAPES) {
                // Too crowded to be one coherent diagram — capture just the seeds individually.
                for (Clusterable c : cluster) {
                    if (c.seed()) {
                        Set<Integer> owners = c.ownerIndex() >= 0 ? Set.of(c.ownerIndex()) : Set.of();
                        tryRasterize(List.of(c.shape()), slideNum, imgIdx, imageId, imagesDir, images, owners);
                    }
                }
            } else {
                List<XSLFShape> members = cluster.stream().map(Clusterable::shape).toList();
                Set<Integer> owners = new LinkedHashSet<>();
                for (Clusterable c : cluster) {
                    if (c.ownerIndex() >= 0) owners.add(c.ownerIndex());
                }
                if (tryRasterize(members, slideNum, imgIdx, imageId, imagesDir, images, owners)) {
                    for (Clusterable c : cluster) {
                        if (c.shape() instanceof XSLFPictureShape pic) consumedPictures.add(pic);
                    }
                }
            }
        }

        for (XSLFPictureShape pic : pictures) {
            if (!consumedPictures.contains(pic)) {
                addPicture(pic, slideNum, imgIdx, imageId, imagesDir, images, Set.of());
            }
        }
    }

    /**
     * {@code rasterize-shapes=false} (default) — no loose-shape clustering. Only "anchor" objects
     * emit images: a picture (with any overlapping loose seed composited on top, when
     * {@code merge-annotated-pictures}), a table with an overlapping loose seed (table also stays a
     * markdown pipe-table), and every group / SmartArt on its own. Loose seeds not consumed by an
     * anchor are dropped — a lone standalone connector/shape produces no image.
     */
    private void rasterizeAnchorsOnly(List<XSLFPictureShape> pictures, List<XSLFTable> tables,
                                      List<Clusterable> alwaysSeeds, List<Clusterable> looseSeeds,
                                      int slideNum, int[] imgIdx, String imageId, Path imagesDir,
                                      List<ExtractedImage> images) throws IOException {
        Set<XSLFShape> consumed = Collections.newSetFromMap(new IdentityHashMap<>());

        // 1) Picture anchors — composite with overlapping loose seeds (annotation markup), else verbatim.
        for (XSLFPictureShape pic : pictures) {
            List<XSLFShape> overlapping = mergeAnnotatedPictures
                    ? overlappingLooseSeeds(pic, looseSeeds, consumed) : List.of();
            if (!overlapping.isEmpty()) {
                List<XSLFShape> members = new ArrayList<>();
                members.add(pic);
                members.addAll(overlapping);
                if (tryRasterize(members, slideNum, imgIdx, imageId, imagesDir, images, Set.of())) {
                    consumed.addAll(overlapping);
                } else {
                    addPicture(pic, slideNum, imgIdx, imageId, imagesDir, images, Set.of());
                }
            } else {
                addPicture(pic, slideNum, imgIdx, imageId, imagesDir, images, Set.of());
            }
        }

        // 2) Table anchors — composite only when a loose seed overlaps (else markdown-only, no image).
        for (XSLFTable table : tables) {
            List<XSLFShape> overlapping = overlappingLooseSeeds(table, looseSeeds, consumed);
            if (!overlapping.isEmpty()) {
                List<XSLFShape> members = new ArrayList<>();
                members.add(table);
                members.addAll(overlapping);
                if (tryRasterize(members, slideNum, imgIdx, imageId, imagesDir, images, Set.of())) {
                    consumed.addAll(overlapping);
                }
            }
        }

        // 3) Groups / SmartArt — always one image each, standalone (no clustering with neighbors).
        for (Clusterable seed : alwaysSeeds) {
            Set<Integer> owners = seed.ownerIndex() >= 0 ? Set.of(seed.ownerIndex()) : Set.of();
            tryRasterize(List.of(seed.shape()), slideNum, imgIdx, imageId, imagesDir, images, owners);
        }
        // 4) Loose seeds not consumed by any anchor are intentionally dropped.
    }

    /** Loose seeds whose padded bounding box overlaps the anchor and haven't been consumed yet. */
    private List<XSLFShape> overlappingLooseSeeds(XSLFShape anchor, List<Clusterable> looseSeeds,
                                                  Set<XSLFShape> consumed) {
        Rectangle2D anchorBox = pad(anchor.getAnchor());
        List<XSLFShape> out = new ArrayList<>();
        for (Clusterable ls : looseSeeds) {
            if (consumed.contains(ls.shape())) continue;
            if (anchorBox.intersects(pad(ls.shape().getAnchor()))) out.add(ls.shape());
        }
        return out;
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
