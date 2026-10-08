package com.example.ragagent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스캔 PDF OCR 의 <b>페이지당 렌더 메모리 경계</b>.
 *
 * <p>{@code renderImageWithDPI} 는 페이지 전체를 한 장의 {@code BufferedImage} 로 만들고, 300 DPI
 * 고정이면 그 크기가 종이 크기에 비례해 자란다 — A4 는 약 35MB 지만 A0 도면은 <b>한 장이 약
 * 560MB</b> 였다. 여기서 고정하는 것은 "얼마나 큰 종이든 한 장이 상한 안에 들어온다"와 "일반
 * 문서 크기에서는 해상도가 그대로다" 두 가지다.
 *
 * <p>포인트 단위는 1/72 인치다(A4 = 595×842pt).
 */
class DocumentLoaderOcrDpiTest {

    /** {@code MAX_OCR_RENDER_PIXELS} 와 같은 값 — 아래 단언들이 재는 기준. */
    private static final long MAX_PIXELS = 40_000_000L;

    private static long pixelsAt(float widthPt, float heightPt, float dpi) {
        return Math.round((widthPt / 72.0) * dpi * (heightPt / 72.0) * dpi);
    }

    @Test
    @DisplayName("일반 문서 크기는 기준 해상도(300 DPI)를 그대로 쓴다")
    void ordinaryPageSizesKeepTheTargetDpi() {
        assertThat(DocumentLoaderService.ocrRenderDpi(595, 842)).isEqualTo(300f);    // A4
        assertThat(DocumentLoaderService.ocrRenderDpi(612, 792)).isEqualTo(300f);    // US Letter
        assertThat(DocumentLoaderService.ocrRenderDpi(842, 1191)).isEqualTo(300f);   // A3
        assertThat(DocumentLoaderService.ocrRenderDpi(1191, 1684)).isEqualTo(300f);  // A2
    }

    @Test
    @DisplayName("큰 도면은 해상도를 낮춰 상한 안에 들어온다 — 300 DPI 고정이면 A0 한 장이 약 560MB")
    void largePagesAreScaledDownUnderTheCap() {
        float[][] large = {{1684, 2384}, {2384, 3370}, {4768, 6740}};   // A1, A0, 2×A0
        for (float[] page : large) {
            float dpi = DocumentLoaderService.ocrRenderDpi(page[0], page[1]);

            assertThat(dpi).as("%.0fx%.0fpt 는 낮춰져야 한다", page[0], page[1]).isLessThan(300f);
            assertThat(pixelsAt(page[0], page[1], dpi))
                    .as("%.0fx%.0fpt @ %.1f DPI", page[0], page[1], dpi)
                    .isLessThanOrEqualTo(MAX_PIXELS);
        }
    }

    @Test
    @DisplayName("OCR 이 글자를 읽을 수 있는 하한(72 DPI) 아래로는 내려가지 않는다")
    void neverGoesBelowTheLegibilityFloor() {
        // 현실에 없는 크기(약 8.5m × 8.5m)까지 밀어붙여도 하한을 지킨다.
        assertThat(DocumentLoaderService.ocrRenderDpi(24_000, 24_000)).isGreaterThanOrEqualTo(72f);
    }

    /**
     * 픽셀 수는 DPI 의 <b>제곱</b>에 비례한다(양 축이 함께 늘어난다). 그래서 면적이 4배인 페이지의
     * DPI 는 1/4 이 아니라 1/2 이다 — 이 관계를 선형으로 잘못 잡으면 큰 페이지가 필요 이상으로
     * 뭉개지거나(또는 상한을 넘거나) 한다.
     */
    @Test
    @DisplayName("축소는 면적의 제곱근을 따른다 — 면적 4배면 DPI 절반")
    void scalingFollowsTheSquareRootOfArea() {
        float dpiA0 = DocumentLoaderService.ocrRenderDpi(2384, 3370);
        float dpiFourTimesA0 = DocumentLoaderService.ocrRenderDpi(2384 * 2, 3370 * 2);

        assertThat(dpiFourTimesA0).isCloseTo(dpiA0 / 2, org.assertj.core.data.Offset.offset(0.5f));
    }

    @Test
    @DisplayName("크기를 알 수 없으면(0/음수) 기준 해상도를 쓴다 — 깨진 상자 때문에 해상도를 바꾸지 않는다")
    void brokenBoxesFallBackToTheTarget() {
        assertThat(DocumentLoaderService.ocrRenderDpi(0, 842)).isEqualTo(300f);
        assertThat(DocumentLoaderService.ocrRenderDpi(595, -1)).isEqualTo(300f);
    }
}
