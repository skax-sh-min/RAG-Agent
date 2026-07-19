package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort DOCX "annotation shape drawn over a picture" merge for {@link DocxToMarkdownConverter}.
 *
 * <p>PPTX's equivalent feature ({@link PptxImageExtractor}) can detect a real geometric overlap
 * because every {@code XSLFShape} exposes an absolute, page-relative anchor rectangle, and POI ships
 * a full slideshow renderer ({@code DrawFactory}) that already knows how to paint any shape type.
 * Neither exists for WordprocessingML: {@code XWPFPicture} exposes no position at all (only its own
 * width/height), an inline picture has no page coordinate until the document is actually laid out,
 * and POI has no shape-type hierarchy or renderer for DOCX drawing objects — only pictures are
 * modeled. A geometrically faithful port is therefore not practical.
 *
 * <p>This class instead uses a coarse <b>proximity approximation</b>: any legacy VML shape
 * ({@code v:rect}/{@code v:oval}/{@code v:roundrect}/{@code v:line} — reachable via typed POI
 * bindings, unlike modern {@code wps:wsp} DrawingML shapes, which have no POI bindings at all and
 * are out of scope here) found anywhere in the <b>same paragraph</b> as a picture is treated as an
 * annotation on that picture and rendered into one composite image with it. This is not a true
 * overlap test — a shape in the same paragraph that happens to be unrelated could still merge — but
 * it matches the common authoring pattern (a highlight circle/arrow drawn directly over a
 * screenshot, anchored to the same paragraph) without needing page-layout geometry POI can't
 * provide.
 *
 * <p>Rendering is hand-rolled Java2D (decode the picture, then paint each shape's outline/fill on
 * top) since POI has no {@code Drawable}-style renderer for VML. Each VML shape's own {@code style}
 * (rect/oval/roundrect: CSS-like {@code left}/{@code top}/{@code width}/{@code height}) or
 * {@code from}/{@code to} (line) attributes are parsed to place it relative to the picture; a shape
 * that can't be positioned is dropped rather than guessed at. Toggle:
 * {@code app.docx-image.merge-annotated-shapes} ({@link AppProperties.DocxShapeExtractionConfig}).
 */
@Component
public class DocxAnnotationShapeMerger {

    private static final String VML_NS = "urn:schemas-microsoft-com:vml";
    /** Local tag names of legacy VML shapes treated as annotations (must also be in {@link #VML_NS}). */
    private static final java.util.Set<String> SHAPE_TAGS = java.util.Set.of("rect", "oval", "roundrect", "line");

    private static final Pattern STYLE_DIM_PATTERN = Pattern.compile(
            "(left|top|width|height)\\s*:\\s*(-?[\\d.]+)\\s*(pt|in|cm|mm|px)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern POINT_PAIR_PATTERN = Pattern.compile(
            "(-?[\\d.]+)\\s*(pt|in|cm|mm|px)?\\s*,\\s*(-?[\\d.]+)\\s*(pt|in|cm|mm|px)?");

    /** Absolute cap on the composite canvas — guards against a garbled/adversarial style value. */
    private static final int MAX_CANVAS_DIMENSION_PX = 6000;
    private static final float STROKE_WIDTH_PX = 2f;

    private final boolean mergeAnnotatedShapes;

    public DocxAnnotationShapeMerger(AppProperties props) {
        this.mergeAnnotatedShapes = props.docxImageSafe().mergeAnnotatedShapes();
    }

    /**
     * A positioned legacy VML shape. {@code left}/{@code top}/{@code width}/{@code height} are the
     * canonical bounding box in points (for {@code line}, derived from {@code from}/{@code to});
     * {@code fromX/fromY/toX/toY} are only populated for {@code line} and used to draw the actual
     * segment instead of a filled/stroked box.
     */
    record VmlShape(String tag, double left, double top, double width, double height,
                     Double fromX, Double fromY, Double toX, Double toY,
                     String strokeColor, String fillColor, boolean filled) {
    }

    /**
     * Finds every legacy-VML annotation shape in the paragraph (plain DOM walk — XmlBeans'
     * {@code selectPath} would need Saxon for anything beyond trivial paths). Returns an empty
     * list when the feature is disabled, the paragraph has no shapes, or every candidate shape's
     * position couldn't be parsed.
     */
    List<VmlShape> findShapes(XWPFParagraph para) {
        if (!mergeAnnotatedShapes || para == null || para.getCTP() == null) return List.of();
        Node root = para.getCTP().getDomNode();
        if (root == null) return List.of();
        List<VmlShape> out = new ArrayList<>();
        collectShapes(root, out);
        return out;
    }

    private void collectShapes(Node node, List<VmlShape> out) {
        if (node instanceof Element el
                && VML_NS.equals(el.getNamespaceURI())
                && SHAPE_TAGS.contains(el.getLocalName())) {
            VmlShape shape = parseShape(el);
            if (shape != null) out.add(shape);
            return; // don't descend into a matched shape (nested/grouped inner shapes not modeled)
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            collectShapes(child, out);
        }
    }

    private VmlShape parseShape(Element el) {
        String tag = el.getLocalName();
        if (tag == null) return null;

        String strokeColor = emptyToNull(el.getAttribute("strokecolor"));
        String fillColor = emptyToNull(el.getAttribute("fillcolor"));
        String filledAttr = emptyToNull(el.getAttribute("filled"));
        // VML's own spec default for a missing `filled` is true, but for an annotation overlay an
        // un-flagged shape is far more likely a highlight outline than an intentional opaque box —
        // defaulting to filled here would risk silently painting over the picture it's meant to
        // annotate, so this deliberately inverts the spec default.
        boolean filled = "t".equalsIgnoreCase(filledAttr) || "true".equalsIgnoreCase(filledAttr);

        if ("line".equals(tag)) {
            double[] from = parsePointPair(el.getAttribute("from"));
            double[] to = parsePointPair(el.getAttribute("to"));
            if (from == null || to == null) return null;
            double left = Math.min(from[0], to[0]);
            double top = Math.min(from[1], to[1]);
            double width = Math.abs(to[0] - from[0]);
            double height = Math.abs(to[1] - from[1]);
            return new VmlShape(tag, left, top, width, height, from[0], from[1], to[0], to[1],
                    strokeColor, fillColor, filled);
        }

        Map<String, Double> box = parseStyleBox(el.getAttribute("style"));
        Double left = box.get("left");
        Double top = box.get("top");
        Double width = box.get("width");
        Double height = box.get("height");
        if (left == null || top == null || width == null || height == null || width <= 0 || height <= 0) {
            return null; // can't place this shape sensibly — drop it, no fallback guessing
        }
        return new VmlShape(tag, left, top, width, height, null, null, null, null,
                strokeColor, fillColor, filled);
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private Map<String, Double> parseStyleBox(String style) {
        if (style == null || style.isBlank()) return Map.of();
        Map<String, Double> out = new HashMap<>();
        Matcher m = STYLE_DIM_PATTERN.matcher(style);
        while (m.find()) {
            String key = m.group(1).toLowerCase(Locale.ROOT);
            double value = Double.parseDouble(m.group(2));
            out.put(key, toPoints(value, m.group(3)));
        }
        return out;
    }

    private double[] parsePointPair(String value) {
        if (value == null) return null;
        Matcher m = POINT_PAIR_PATTERN.matcher(value.trim());
        if (!m.matches()) return null;
        double x = toPoints(Double.parseDouble(m.group(1)), m.group(2));
        double y = toPoints(Double.parseDouble(m.group(3)), m.group(4));
        return new double[]{x, y};
    }

    /** Converts a VML/CSS length to points. No unit suffix is treated as already-points (Word's own convention for these attributes). */
    private double toPoints(double value, String unit) {
        if (unit == null) return value;
        return switch (unit.toLowerCase(Locale.ROOT)) {
            case "in" -> value * 72.0;
            case "cm" -> value * 28.3465;
            case "mm" -> value * 2.83465;
            case "px" -> value * 0.75;
            default -> value; // "pt"
        };
    }

    /**
     * Composites the picture with every shape painted on top, scaled from points into the
     * picture's own pixel space. Returns {@code null} (caller falls back to verbatim extraction) if
     * the picture can't be decoded, no shape survived positioning, or the resulting canvas would
     * exceed the safety cap.
     *
     * @param pictureBytes already-raster-decodable image bytes (EMF/WMF must be pre-converted by the caller)
     * @param picWidthPt   the picture's displayed width in points ({@code XWPFPicture.getWidth()})
     * @param picHeightPt  the picture's displayed height in points ({@code XWPFPicture.getDepth()})
     */
    byte[] compose(byte[] pictureBytes, double picWidthPt, double picHeightPt, List<VmlShape> shapes) {
        if (shapes == null || shapes.isEmpty()) return null;

        BufferedImage picImg;
        try {
            picImg = ImageIO.read(new ByteArrayInputStream(pictureBytes));
        } catch (IOException e) {
            return null;
        }
        if (picImg == null) return null;

        double scaleX = picWidthPt > 0 ? picImg.getWidth() / picWidthPt : 1.0;
        double scaleY = picHeightPt > 0 ? picImg.getHeight() / picHeightPt : 1.0;

        Rectangle2D union = new Rectangle2D.Double(0, 0, picImg.getWidth(), picImg.getHeight());
        for (VmlShape s : shapes) {
            union.add(new Rectangle2D.Double(s.left() * scaleX, s.top() * scaleY,
                    s.width() * scaleX, s.height() * scaleY));
        }

        int canvasW = (int) Math.ceil(union.getWidth());
        int canvasH = (int) Math.ceil(union.getHeight());
        if (canvasW <= 0 || canvasH <= 0 || canvasW > MAX_CANVAS_DIMENSION_PX || canvasH > MAX_CANVAS_DIMENSION_PX) {
            return null;
        }

        BufferedImage canvas = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double dx = -union.getX();
        double dy = -union.getY();
        g.drawImage(picImg, (int) Math.round(dx), (int) Math.round(dy), null);
        g.setStroke(new BasicStroke(STROKE_WIDTH_PX));
        for (VmlShape s : shapes) {
            drawShape(g, s, scaleX, scaleY, dx, dy);
        }
        g.dispose();

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(canvas, "png", out)) return null;
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private void drawShape(Graphics2D g, VmlShape shape, double scaleX, double scaleY, double dx, double dy) {
        Color strokeColor = parseColor(shape.strokeColor()).orElse(Color.RED);
        Color fillColor = shape.filled() ? parseColor(shape.fillColor()).orElse(null) : null;

        if ("line".equals(shape.tag())) {
            double x1 = shape.fromX() * scaleX + dx;
            double y1 = shape.fromY() * scaleY + dy;
            double x2 = shape.toX() * scaleX + dx;
            double y2 = shape.toY() * scaleY + dy;
            g.setColor(strokeColor);
            g.draw(new Line2D.Double(x1, y1, x2, y2));
            return;
        }

        double x = shape.left() * scaleX + dx;
        double y = shape.top() * scaleY + dy;
        double w = shape.width() * scaleX;
        double h = shape.height() * scaleY;
        java.awt.Shape awtShape = "oval".equals(shape.tag())
                ? new Ellipse2D.Double(x, y, w, h)
                : new Rectangle2D.Double(x, y, w, h); // rect, roundrect (corner radius not modeled)
        if (fillColor != null) {
            g.setColor(fillColor);
            g.fill(awtShape);
        }
        g.setColor(strokeColor);
        g.draw(awtShape);
    }

    private static final Map<String, Color> NAMED_COLORS = Map.ofEntries(
            Map.entry("red", Color.RED), Map.entry("blue", Color.BLUE), Map.entry("green", Color.GREEN),
            Map.entry("black", Color.BLACK), Map.entry("white", Color.WHITE), Map.entry("yellow", Color.YELLOW),
            Map.entry("orange", Color.ORANGE), Map.entry("purple", new Color(128, 0, 128)),
            Map.entry("gray", Color.GRAY), Map.entry("grey", Color.GRAY));

    private Optional<Color> parseColor(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        String v = value.trim();
        if ("none".equalsIgnoreCase(v) || "transparent".equalsIgnoreCase(v)) return Optional.empty();
        try {
            if (v.startsWith("#")) return Optional.of(Color.decode(v));
            Color named = NAMED_COLORS.get(v.toLowerCase(Locale.ROOT));
            if (named != null) return Optional.of(named);
        } catch (NumberFormatException ignored) {
            // fall through to empty — caller applies its own default
        }
        return Optional.empty();
    }
}
