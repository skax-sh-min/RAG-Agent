package com.example.ragagent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PPTX 도형 래스터라이즈의 <b>한 장당 메모리 경계</b> — {@code DocumentLoaderOcrDpiTest} 와 같은 성격.
 *
 * <p>{@code rasterize()} 는 도형 앵커의 합집합을 한 장의 {@code BufferedImage} 로 만든다. 그 크기는
 * 슬라이드가 아니라 앵커에서 오므로, 슬라이드 밖 멀리 놓인 도형 하나가 상한 없이는 수 GB 할당이
 * 됐다. 여기서 고정하는 것은 "정상 도형은 배율이 그대로다", "어떤 앵커든 한 장이 상한 안에 든다",
 * "원래 크기보다 작게 그려야 할 정도면 그리지 않는다" 세 가지다.
 */
class PptxImageExtractorRenderScaleTest {

    private static long pixelsAt(double widthPt, double heightPt, double scale) {
        return (long) (Math.ceil(widthPt * scale) * Math.ceil(heightPt * scale));
    }

    @Test
    @DisplayName("슬라이드 안의 정상 도형은 기준 배율(2배)을 그대로 쓴다")
    void ordinaryShapesKeepTheTargetScale() {
        assertThat(PptxImageExtractor.renderScale(300, 200)).isEqualTo(2.0);    // 작은 다이어그램
        assertThat(PptxImageExtractor.renderScale(960, 540)).isEqualTo(2.0);    // 16:9 슬라이드 전체
        assertThat(PptxImageExtractor.renderScale(1920, 1080)).isEqualTo(2.0);  // 슬라이드 4배 면적
    }

    @Test
    @DisplayName("큰 합집합은 배율을 낮춰 상한 안에 들어온다")
    void largeUnionsAreScaledDownUnderTheCap() {
        double[][] large = {{3000, 2000}, {3800, 3800}, {5000, 1500}};
        for (double[] box : large) {
            double scale = PptxImageExtractor.renderScale(box[0], box[1]);

            assertThat(scale).as("%.0fx%.0fpt 는 낮춰져야 한다", box[0], box[1]).isLessThan(2.0);
            assertThat(scale).isGreaterThanOrEqualTo(1.0);
            assertThat(pixelsAt(box[0], box[1], scale))
                    .as("%.0fx%.0fpt @ %.3f", box[0], box[1], scale)
                    .isLessThanOrEqualTo(PptxImageExtractor.MAX_RASTER_PIXELS);
        }
    }

    @Test
    @DisplayName("원래 크기보다 작게 그려야 상한에 드는 앵커(슬라이드 밖 사고)는 그리지 않는다 — 0")
    void anchorsBeyondTheFloorAreSkipped() {
        // 4,000×4,000pt 를 넘으면 1배로도 상한을 넘는다 — 슬라이드 약 30배 면적.
        assertThat(PptxImageExtractor.renderScale(5000, 5000)).isZero();
        assertThat(PptxImageExtractor.renderScale(100_000, 1000)).isZero();
        // 한 변이 길어도 1배 면적이 상한 안이면 그린다 — 폭이 아니라 면적이 기준이다.
        assertThat(PptxImageExtractor.renderScale(100_000, 100)).isBetween(1.0, 2.0);
    }

    /**
     * 픽셀 수는 배율의 제곱에 비례하므로 축소는 면적의 제곱근을 따른다. 배율이 [1, 2] 안에서만
     * 움직이므로(그 밑은 0) 면적 4배는 그 범위를 통째로 넘는다 — 변마다 1.5배(면적 2.25배)로 잰다.
     */
    @Test
    @DisplayName("축소는 면적의 제곱근을 따른다 — 변마다 1.5배면 배율은 1/1.5")
    void scalingFollowsTheSquareRootOfArea() {
        double s = PptxImageExtractor.renderScale(2100, 2000);            // 2배로 그리면 상한 살짝 초과
        double sLarger = PptxImageExtractor.renderScale(2100 * 1.5, 2000 * 1.5);

        assertThat(s).isLessThan(2.0).isGreaterThanOrEqualTo(1.0);
        assertThat(sLarger).isCloseTo(s / 1.5, org.assertj.core.data.Offset.offset(0.005));
    }

    @Test
    @DisplayName("크기를 알 수 없으면(0/음수) 기준 배율을 준다 — 호출자가 따로 거른다")
    void brokenBoxesFallBackToTheTarget() {
        assertThat(PptxImageExtractor.renderScale(0, 200)).isEqualTo(2.0);
        assertThat(PptxImageExtractor.renderScale(300, -1)).isEqualTo(2.0);
    }
}
