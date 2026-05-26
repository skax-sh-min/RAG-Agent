package com.example.ragagent.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Extracts embedded raster images from PDF pages using PDFBox 3.x.
 * Saves each image as p{page}_img{n}.png.
 * L0 filter: skips images smaller than MIN_IMAGE_BYTES (estimated) to exclude icons/backgrounds.
 */
@Component
public class PdfImageExtractor {

    private static final int MIN_IMAGE_BYTES = 1_000;

    /** @return {pageNum(1-based) → relative image paths from dataDir} */
    public Map<Integer, List<String>> extract(Path pdfPath, String docId, Path imagesDir)
            throws IOException {
        return extract(pdfPath, docId, imagesDir, null);
    }

    /**
     * Same as {@link #extract(Path, String, Path)} but calls {@code onProgress(done, total)}
     * after each page is processed.
     */
    public Map<Integer, List<String>> extract(Path pdfPath, String docId, Path imagesDir,
                                              BiConsumer<Integer, Integer> onProgress)
            throws IOException {
        Files.createDirectories(imagesDir);
        Map<Integer, List<String>> result = new LinkedHashMap<>();

        try (PDDocument pdf = Loader.loadPDF(pdfPath.toFile())) {
            int totalPages = pdf.getNumberOfPages();
            int pageNum = 0;
            for (PDPage page : pdf.getPages()) {
                pageNum++;
                PDResources resources = page.getResources();
                if (resources != null) {
                    List<String> paths = new ArrayList<>();
                    int imgIdx = 0;
                    for (COSName name : resources.getXObjectNames()) {
                        PDXObject xObj;
                        try {
                            xObj = resources.getXObject(name);
                        } catch (Exception ignored) {
                            continue;
                        }
                        if (!(xObj instanceof PDImageXObject img)) continue;

                        BufferedImage bi;
                        try {
                            bi = img.getImage();
                        } catch (Exception ignored) {
                            continue;
                        }
                        if (bi == null) continue;
                        // L0 filter: skip tiny images (icons, watermarks)
                        if ((long) bi.getWidth() * bi.getHeight() * 3 < MIN_IMAGE_BYTES) continue;

                        imgIdx++;
                        String fileName = "p" + pageNum + "_img" + imgIdx + ".png";
                        Path imgFile = imagesDir.resolve(fileName);
                        ImageIO.write(bi, "png", imgFile.toFile());
                        paths.add("images/" + docId + "/" + fileName);
                    }
                    if (!paths.isEmpty()) result.put(pageNum, paths);
                }
                if (onProgress != null) onProgress.accept(pageNum, totalPages);
            }
        }
        return result;
    }
}
