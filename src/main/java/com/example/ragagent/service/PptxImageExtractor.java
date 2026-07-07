package com.example.ragagent.service;

import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts embedded images from PPTX slides.
 * Saves to imagesDir as s{slide}_img{n}.{ext}.
 */
@Component
public class PptxImageExtractor {

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
                    }
                }
                if (!paths.isEmpty()) result.put(slideNum, paths);
            }
        }
        return result;
    }
}
