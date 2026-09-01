package com.example.ragagent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인덱싱 호출의 출력 예약 상한.
 *
 * <p>여기서 지키는 것은 두 가지다 — <b>설정값에서 파생될 것</b>(운영자가 `app.llm.max-tokens` 를
 * 내리면 인덱싱 예약도 따라 내려가야 한다)과 <b>내리기만 할 것</b>(상한을 새로 만들거나 올리지 않는다).
 */
class IndexingOutputCapTest {

    private static final int MAX = 10_000;

    @Test
    @DisplayName("재작성 — 출력 예약이 입력 크기를 따라간다 (여유 1.5배)")
    void rewriteFollowsInputSize() {
        String korean2000 = "가".repeat(2_000);   // 추정 2,000 토큰

        assertThat(IndexingOutputCap.forRewrite(korean2000, MAX)).isEqualTo(3_000);
    }

    @Test
    @DisplayName("재작성 — 설정 상한을 넘지 않는다")
    void rewriteNeverExceedsConfigured() {
        String huge = "가".repeat(50_000);

        assertThat(IndexingOutputCap.forRewrite(huge, MAX)).isEqualTo(MAX);
    }

    @Test
    @DisplayName("재작성 — 짧은 조각에도 설정값 비율의 바닥을 준다 (교정이 표·펜스를 복원하며 커질 수 있다)")
    void rewriteHasAProportionalFloor() {
        String tiny = "가".repeat(100);   // 추정 150 → 바닥(10,000의 10% = 1,000)이 이긴다

        assertThat(IndexingOutputCap.forRewrite(tiny, MAX)).isEqualTo(1_000);
    }

    @Test
    @DisplayName("고정 크기 — 설정값의 비율로 계산된다 (배치는 건수를 곱해 넘긴다)")
    void fixedIsAFractionOfConfigured() {
        // 단건은 0.05 × 10,000 = 500 이라 바닥(512)과 거의 같은 자리에 떨어진다 — 두 값이 기본
        // 설정에서 일치하도록 고른 비율이고, 비율이 실제로 지배하는 것은 배치와 더 큰 max-tokens 다.
        assertThat(IndexingOutputCap.forFixed(0.05, MAX)).isEqualTo(IndexingOutputCap.MIN_OUTPUT_TOKENS);
        assertThat(IndexingOutputCap.forFixed(0.05 * 4, MAX)).isEqualTo(2_000);
        assertThat(IndexingOutputCap.forFixed(0.05, 40_000)).isEqualTo(2_000);
    }

    @Test
    @DisplayName("설정값을 내리면 예약도 함께 내려간다 — 단일 진실 소스가 실제로 지배한다")
    void loweringConfiguredLowersEverything() {
        String korean2000 = "가".repeat(2_000);

        // max-tokens 2,000 배포: 재작성은 상한에서, 고정은 바닥에서 잘린다.
        assertThat(IndexingOutputCap.forRewrite(korean2000, 2_000)).isEqualTo(2_000);
        assertThat(IndexingOutputCap.forFixed(0.05, 2_000)).isEqualTo(IndexingOutputCap.MIN_OUTPUT_TOKENS);
    }

    @Test
    @DisplayName("아무리 작은 비율이어도 응답이 들어갈 자리는 남긴다")
    void neverBelowTheAbsoluteFloor() {
        assertThat(IndexingOutputCap.forFixed(0.0001, MAX)).isEqualTo(IndexingOutputCap.MIN_OUTPUT_TOKENS);
        assertThat(IndexingOutputCap.forRewrite("", MAX)).isGreaterThanOrEqualTo(IndexingOutputCap.MIN_OUTPUT_TOKENS);
    }

    @Test
    @DisplayName("설정값이 0 이하면 0 — 호출부가 프로바이더 기본값을 그대로 쓴다")
    void zeroConfiguredMeansLeaveTheProviderDefault() {
        assertThat(IndexingOutputCap.forRewrite("가".repeat(1_000), 0)).isZero();
        assertThat(IndexingOutputCap.forFixed(0.5, 0)).isZero();
    }

    @Test
    @DisplayName("영어 입력은 chars/4 로 세어져 한국어보다 작은 예약을 받는다")
    void englishInputReservesLess() {
        String english = "a".repeat(2_000);   // 추정 500 토큰 → 750, 바닥 1,000 이 이긴다
        String korean = "가".repeat(2_000);   // 추정 2,000 토큰 → 3,000

        assertThat(IndexingOutputCap.forRewrite(english, MAX))
                .isLessThan(IndexingOutputCap.forRewrite(korean, MAX));
    }
}
