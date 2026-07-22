package com.example.ragagent.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA — PdfToMarkdownConverter: one [페이지: N] marker per non-blank page (the marker is the
 * section boundary — no synthetic "## N페이지" heading), page numbers matching the true (1-based)
 * page index even when earlier pages were blank and skipped, inline [이미지: ...] markers per page
 * (like DOCX — image_paths metadata is promoted downstream by loadFromMarkdown()).
 */
class PdfToMarkdownConverterTest {

    private final PdfToMarkdownConverter converter = new PdfToMarkdownConverter(new PdfImageExtractor());
    private Path pdfPath;
    private Path imagesDir;

    @BeforeEach
    void setUp() throws IOException {
        pdfPath = Files.createTempFile("pdf-md-test-", ".pdf");
        imagesDir = Files.createTempDirectory("pdf-md-images-");
        writeBlankPdf(1);
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(pdfPath);
        deleteRecursively(imagesDir);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        }
    }

    /** A real, openable PDF with {@code pageCount} blank pages — no text, no images. */
    private void writeBlankPdf(int pageCount) throws IOException {
        Files.deleteIfExists(pdfPath); // avoid PDFBox's "overwriting existing file" warning
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pageCount; i++) {
                doc.addPage(new PDPage());
            }
            doc.save(pdfPath.toFile());
        }
    }

    /** A real PDF with {@code pageCount} pages, embedding one real (decodable) image on one page. */
    private void writePdfWithImageOnPage(int pageCount, int imagePageIndex) throws IOException {
        Files.deleteIfExists(pdfPath); // avoid PDFBox's "overwriting existing file" warning
        try (PDDocument doc = new PDDocument()) {
            PDPage[] pages = new PDPage[pageCount];
            for (int i = 0; i < pageCount; i++) {
                pages[i] = new PDPage();
                doc.addPage(pages[i]);
            }
            // 20x20 → 20*20*3 = 1200 bytes ≥ PdfImageExtractor.MIN_IMAGE_BYTES(1000), so it isn't
            // filtered out as an icon/background.
            BufferedImage img = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
            PDImageXObject pdImage = LosslessFactory.createFromImage(doc, img);
            try (PDPageContentStream cs = new PDPageContentStream(doc, pages[imagePageIndex])) {
                cs.drawImage(pdImage, 10, 10, 20, 20);
            }
            doc.save(pdfPath.toFile());
        }
    }

    private String convert(List<Document> pages) throws IOException {
        return converter.convert(pages, pdfPath, "doc1", imagesDir);
    }

    @Test
    @DisplayName("페이지마다 [페이지: N] 마커가 순서대로 생성되고, 합성 페이지 헤딩은 넣지 않는다")
    void multiplePagesGetOrderedPageMarkers() throws IOException {
        writeBlankPdf(3);
        List<Document> pages = List.of(
                new Document("첫 페이지 내용", Map.of()),
                new Document("둘째 페이지 내용", Map.of()),
                new Document("셋째 페이지 내용", Map.of()));

        String md = convert(pages);

        int idx1 = md.indexOf("[페이지: 1]");
        int idx2 = md.indexOf("[페이지: 2]");
        int idx3 = md.indexOf("[페이지: 3]");
        assertThat(idx1).isGreaterThanOrEqualTo(0);
        assertThat(idx2).isGreaterThan(idx1);
        assertThat(idx3).isGreaterThan(idx2);
        // 합성 "## N페이지" 헤딩은 더 이상 넣지 않는다 — [페이지: N] 마커가 섹션 경계 역할을 겸한다.
        assertThat(md).doesNotContain("## 1페이지").doesNotContain("## 2페이지").doesNotContain("## 3페이지");
        assertThat(md).contains("첫 페이지 내용").contains("둘째 페이지 내용").contains("셋째 페이지 내용");
    }

    @Test
    @DisplayName("빈 페이지는 건너뛰지만 이후 페이지 번호는 실제 페이지 인덱스를 그대로 유지한다")
    void blankPageSkippedWithoutShiftingSubsequentPageNumbers() throws IOException {
        writeBlankPdf(3);
        List<Document> pages = List.of(
                new Document("첫 페이지 내용", Map.of()),
                new Document("   ", Map.of()),   // blank page (e.g. divider slide-equivalent)
                new Document("셋째 페이지 내용", Map.of()));

        String md = convert(pages);

        assertThat(md).doesNotContain("[페이지: 2]");
        assertThat(md).contains("[페이지: 1]");
        assertThat(md).contains("[페이지: 3]"); // real page index, not a re-numbered "[페이지: 2]"
        assertThat(md).doesNotContain("## 3페이지");
    }

    @Test
    @DisplayName("문서 제목은 파일명에서 유도되어 H1으로 추가된다")
    void documentTitleDerivedFromFilename() throws IOException {
        Path namedPdf = pdfPath.getParent().resolve("2024_사용자_가이드.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(namedPdf.toFile());
        }
        try {
            List<Document> pages = List.of(new Document("본문", Map.of()));

            String md = converter.convert(pages, namedPdf, "doc1", imagesDir);

            assertThat(md).startsWith("# ");
            assertThat(md).contains("사용자 가이드");
        } finally {
            Files.deleteIfExists(namedPdf);
        }
    }

    @Test
    @DisplayName("텍스트 없이 이미지만 있는 페이지도 건너뛰지 않고 [페이지: N] + [이미지: ...] 마커를 받는다")
    void pageWithOnlyImageIsNotSkippedAndGetsImageMarker() throws IOException {
        writePdfWithImageOnPage(1, 0);
        List<Document> pages = List.of(new Document("   ", Map.of())); // no extractable text, image only

        String md = convert(pages);

        assertThat(md).contains("[페이지: 1]");
        assertThat(md).doesNotContain("## 1페이지");
        assertThat(md).contains("[이미지: images/doc1/p1_img1.png]");
        assertThat(Files.exists(imagesDir.resolve("p1_img1.png"))).isTrue();
    }

    @Test
    @DisplayName("텍스트와 이미지가 모두 있는 페이지는 [페이지: N] 다음 이미지 마커, 그다음 본문 순서로 나온다")
    void pageWithTextAndImageOrdersImageMarkerBeforeText() throws IOException {
        writePdfWithImageOnPage(1, 0);
        List<Document> pages = List.of(new Document("페이지 본문 내용", Map.of()));

        String md = convert(pages);

        int pageMarkerIdx = md.indexOf("[페이지: 1]");
        int imageIdx = md.indexOf("[이미지:");
        int textIdx = md.indexOf("페이지 본문 내용");
        assertThat(pageMarkerIdx).isGreaterThanOrEqualTo(0);
        assertThat(imageIdx).isGreaterThan(pageMarkerIdx);
        assertThat(textIdx).isGreaterThan(imageIdx);
    }
}
