package com.example.ragagent.service;

import org.apache.poi.xslf.usermodel.XMLSlideShow;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-builds a minimal, valid SmartArt (diagram) PPTX fixture for tests.
 *
 * POI has no public builder API for creating SmartArt — {@code XSLFDiagram} is parse-only
 * ({@code @Beta}, package-private constructor). This writes a plain one-slide pptx via POI, then
 * injects the diagram parts directly into the zip package: the standard {@code data1.xml} /
 * {@code layout1.xml} / {@code quickStyle1.xml} / {@code colors1.xml} (referenced by the slide's
 * {@code dgm:relIds}, but only {@code data1.xml}'s relationship is actually resolved by
 * {@code XSLFDiagram}) plus Microsoft's {@code drawing1.xml} extension part, which is the one
 * {@code XSLFDiagram#getGroupShape()} actually reads (verified against POI 5.5.1's
 * {@code XSLFDiagram}/{@code XSLFDiagramDrawing} source — {@code readDiagramDrawing()} resolves
 * the {@code dm} relationship only to derive {@code drawing1.xml}'s part name by string
 * substitution, then looks up a sibling slide relationship of type {@code XSLFRelation.DIAGRAM_DRAWING}
 * pointing at that part name).
 */
final class PptxSmartArtFixture {

    private static final String DGM_NS = "http://schemas.openxmlformats.org/drawingml/2006/diagram";
    private static final String REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String DSP_NS = "http://schemas.microsoft.com/office/drawing/2008/diagram";

    private PptxSmartArtFixture() {
    }

    /**
     * Writes a one-slide pptx to {@code target} with a single SmartArt graphic frame whose
     * rendered drawing layer contains one text box per given label (mirrors an org-chart/process
     * diagram with one box per label).
     */
    static void write(Path target, List<String> labels) throws IOException {
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
            Files.createDirectory(zipfs.getPath("/ppt/diagrams"));
            writePart(zipfs, "/ppt/diagrams/data1.xml", "<dgm:dataModel xmlns:dgm=\"" + DGM_NS + "\"/>");
            writePart(zipfs, "/ppt/diagrams/layout1.xml", "<dgm:dataModel xmlns:dgm=\"" + DGM_NS + "\"/>");
            writePart(zipfs, "/ppt/diagrams/quickStyle1.xml", "<dgm:styleDefHdrLst xmlns:dgm=\"" + DGM_NS + "\"/>");
            writePart(zipfs, "/ppt/diagrams/colors1.xml", "<dgm:colorsDefHdrLst xmlns:dgm=\"" + DGM_NS + "\"/>");
            writePart(zipfs, "/ppt/diagrams/drawing1.xml", drawingXml(labels));

            Path slideRelsPath = zipfs.getPath("/ppt/slides/_rels/slide1.xml.rels");
            String newRels = readString(slideRelsPath).replace("</Relationships>",
                      "<Relationship Id=\"rIdDgmData\" Type=\"" + REL_NS + "/diagramData\" Target=\"../diagrams/data1.xml\"/>"
                    + "<Relationship Id=\"rIdDgmLayout\" Type=\"" + REL_NS + "/diagramLayout\" Target=\"../diagrams/layout1.xml\"/>"
                    + "<Relationship Id=\"rIdDgmStyle\" Type=\"" + REL_NS + "/diagramQuickStyle\" Target=\"../diagrams/quickStyle1.xml\"/>"
                    + "<Relationship Id=\"rIdDgmColors\" Type=\"" + REL_NS + "/diagramColors\" Target=\"../diagrams/colors1.xml\"/>"
                    + "<Relationship Id=\"rIdDgmDrawing\" Type=\"http://schemas.microsoft.com/office/2007/relationships/diagramDrawing\" Target=\"../diagrams/drawing1.xml\"/>"
                    + "</Relationships>");
            writePart(zipfs, "/ppt/slides/_rels/slide1.xml.rels", newRels);

            Path slidePath = zipfs.getPath("/ppt/slides/slide1.xml");
            String graphicFrame =
                  "<p:graphicFrame>"
                + "<p:nvGraphicFramePr><p:cNvPr id=\"10\" name=\"SmartArt1\"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr>"
                + "<p:xfrm><a:off x=\"914400\" y=\"914400\"/><a:ext cx=\"3657600\" cy=\"1828800\"/></p:xfrm>"
                + "<a:graphic><a:graphicData uri=\"" + DGM_NS + "\">"
                + "<dgm:relIds xmlns:dgm=\"" + DGM_NS + "\" xmlns:r=\"" + REL_NS + "\" "
                + "r:dm=\"rIdDgmData\" r:lo=\"rIdDgmLayout\" r:qs=\"rIdDgmStyle\" r:cs=\"rIdDgmColors\"/>"
                + "</a:graphicData></a:graphic>"
                + "</p:graphicFrame>";
            writePart(zipfs, "/ppt/slides/slide1.xml",
                    readString(slidePath).replace("</p:spTree>", graphicFrame + "</p:spTree>"));

            Path ctPath = zipfs.getPath("/[Content_Types].xml");
            String newCt = readString(ctPath).replace("</Types>",
                      "<Override PartName=\"/ppt/diagrams/data1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawingml.diagramData+xml\"/>"
                    + "<Override PartName=\"/ppt/diagrams/layout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawingml.diagramLayout+xml\"/>"
                    + "<Override PartName=\"/ppt/diagrams/quickStyle1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawingml.diagramStyle+xml\"/>"
                    + "<Override PartName=\"/ppt/diagrams/colors1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawingml.diagramColors+xml\"/>"
                    + "<Override PartName=\"/ppt/diagrams/drawing1.xml\" ContentType=\"application/vnd.ms-office.drawingml.diagramDrawing+xml\"/>"
                    + "</Types>");
            writePart(zipfs, "/[Content_Types].xml", newCt);
        }
    }

    /** {@code dsp:drawing} > {@code dsp:spTree} — the rendered drawing layer {@code XSLFDiagram#getGroupShape()} converts. */
    private static String drawingXml(List<String> labels) {
        StringBuilder shapes = new StringBuilder();
        int id = 2;
        long y = 0;
        for (String label : labels) {
            shapes.append("<dsp:sp modelId=\"{00000000-0000-0000-0000-").append(String.format("%012d", id)).append("}\">")
                    .append("<dsp:nvSpPr><dsp:cNvPr id=\"").append(id).append("\" name=\"\"/><dsp:cNvSpPr/></dsp:nvSpPr>")
                    .append("<dsp:spPr><a:xfrm><a:off x=\"0\" y=\"").append(y).append("\"/><a:ext cx=\"1000000\" cy=\"500000\"/></a:xfrm>")
                    .append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></dsp:spPr>")
                    .append("<dsp:style/>")
                    .append("<dsp:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>").append(escapeXml(label)).append("</a:t></a:r></a:p></dsp:txBody>")
                    .append("<dsp:txXfrm><a:off x=\"0\" y=\"").append(y).append("\"/><a:ext cx=\"1000000\" cy=\"500000\"/></dsp:txXfrm>")
                    .append("</dsp:sp>");
            id++;
            y += 600000;
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
             + "<dsp:drawing xmlns:dsp=\"" + DSP_NS + "\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">"
             + "<dsp:spTree>"
             + "<dsp:nvGrpSpPr><dsp:cNvPr id=\"1\" name=\"\"/><dsp:cNvGrpSpPr/></dsp:nvGrpSpPr>"
             + "<dsp:grpSpPr/>"
             + shapes
             + "</dsp:spTree>"
             + "</dsp:drawing>";
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String readString(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static void writePart(FileSystem zipfs, String path, String content) throws IOException {
        Files.write(zipfs.getPath(path), content.getBytes(StandardCharsets.UTF_8));
    }
}
