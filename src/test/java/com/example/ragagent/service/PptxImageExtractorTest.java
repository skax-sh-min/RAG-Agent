package com.example.ragagent.service;

import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA — PptxImageExtractor
 *
 * Regression for a filename bug: {@code PictureData.PictureType.extension} already includes
 * the leading dot (e.g. ".png"), so building the filename as {@code "..." + "." + ext} produced
 * a double dot ("s1_img1..png"). Such filenames trip DocumentController.getImage()'s path-
 * traversal guard (rejects any filename containing "..") and 400 even though the file exists.
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
}
