package com.example.ragagent.service;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 내장 이미지 추출의 <b>메모리 경계</b>.
 *
 * <p>{@code PDImageXObject.getImage()} 는 픽셀당 최소 4바이트짜리 {@code BufferedImage} 를 한 번에
 * 할당하므로, PDF 가 부르는 크기가 그대로 힙 요구가 된다 — 상한이 없으면 20000×20000 한 장이 1.6GB 다.
 *
 * <p><b>왜 판정을 직접 검증하는가.</b> 처음에는 딕셔너리 크기만 30000×30000 으로 부풀린 PDF 를
 * 넣어 "추출 결과가 비어 있다"를 봤는데, 그 테스트는 <b>상한을 지워도 그대로 통과했다</b> —
 * {@code getImage()} 가 데이터 부족을 먼저 감지해 예외를 던지고, 호출부가 그것을 삼켜 같은 결과를
 * 내기 때문이다. 통과하지만 아무것도 지키지 못하는 테스트라 지웠다. 진짜 5천만 픽셀 이미지를
 * 만드는 방법도 있지만 그건 테스트가 먼저 수백 MB 를 쓴다. 그래서 판정은
 * {@link PdfImageExtractor#withinDecodeLimit} 로 직접 재고, 그 판정이 {@code getImage()} 앞에
 * 있다는 것은 호출부를 읽어 확인한다.
 */
class PdfImageExtractorTest {

    private final PdfImageExtractor extractor = new PdfImageExtractor();

    // ── 크기 판정 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("문서에서 실제로 읽을 만한 크기는 통과한다 — A4 600DPI 스캔(약 3,500만 픽셀)")
    void realisticScanSizes_pass() {
        assertThat(PdfImageExtractor.withinDecodeLimit(4960, 7016)).isTrue();   // A4 600DPI
        assertThat(PdfImageExtractor.withinDecodeLimit(2480, 3508)).isTrue();   // A4 300DPI
        assertThat(PdfImageExtractor.withinDecodeLimit(6000, 4000)).isTrue();   // 24MP 사진
    }

    @Test
    @DisplayName("상한을 넘는 크기는 막는다 — 이 한 장이 1.6GB 할당이다")
    void oversizedDimensions_areRejected() {
        assertThat(PdfImageExtractor.withinDecodeLimit(20_000, 20_000)).isFalse();
        assertThat(PdfImageExtractor.withinDecodeLimit(30_000, 30_000)).isFalse();
        assertThat(PdfImageExtractor.withinDecodeLimit(1, 60_000_000)).isFalse();  // 한 축만 커도 마찬가지
    }

    /**
     * {@code int} 로 곱하면 46341×46341 부터 넘쳐 음수가 되고, 그러면 <b>가장 큰 이미지가 검사를
     * 통과한다</b> — 막으려던 바로 그 경우다.
     */
    @Test
    @DisplayName("곱셈 오버플로로 거대 이미지가 통과하지 않는다")
    void hugeDimensions_doNotOverflowIntoPassing() {
        assertThat(PdfImageExtractor.withinDecodeLimit(46_341, 46_341)).isFalse();
        assertThat(PdfImageExtractor.withinDecodeLimit(Integer.MAX_VALUE, 2)).isFalse();
    }

    @Test
    @DisplayName("0 이하 크기는 막는다 (깨진 딕셔너리)")
    void nonPositiveDimensions_areRejected() {
        assertThat(PdfImageExtractor.withinDecodeLimit(0, 100)).isFalse();
        assertThat(PdfImageExtractor.withinDecodeLimit(100, -1)).isFalse();
    }

    // ── 추출 경로 회귀 가드 ──────────────────────────────────────────────────

    @Test
    @DisplayName("정상 크기 이미지는 그대로 추출된다")
    void normalSizedImage_isExtracted(@TempDir Path dir) throws IOException {
        Path pdf = dir.resolve("normal.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            page.setResources(new PDResources());
            doc.addPage(page);
            PDImageXObject img = LosslessFactory.createFromImage(doc, solid(200, 200));
            page.getResources().put(COSName.getPDFName("Im1"), img);
            doc.save(pdf.toFile());
        }

        Map<Integer, List<String>> result = extractor.extract(pdf, "img1", dir.resolve("images"));

        assertThat(result).containsKey(1);
        assertThat(result.get(1)).hasSize(1);
    }

    private static BufferedImage solid(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }
}
