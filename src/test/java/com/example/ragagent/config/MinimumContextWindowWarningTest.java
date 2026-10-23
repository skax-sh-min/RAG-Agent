package com.example.ragagent.config;

import com.example.ragagent.llm.ProviderContextWindows;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기동 시 "창 16K 미만" 경고의 판정. 경계는 {@code max-tokens} 하한의 두 배 — 출력 예약이 창의 절반을 넘지 않는
 * 가장 작은 창이다({@code ProviderContextWindows.cappedMaxTokens} 와 같은 경계).
 */
class MinimumContextWindowWarningTest {

    private static final ProviderContextWindows.Source PROBED = ProviderContextWindows.Source.PROBED;

    @Test
    @DisplayName("경계는 max-tokens 하한의 두 배(16,000) — 하한이 바뀌면 함께 움직인다")
    void thresholdIsTwiceTheMaxTokensFloor() {
        assertThat(LlmConfig.MIN_SUPPORTED_CONTEXT_TOKENS)
                .isEqualTo(2 * AppProperties.MIN_MAX_TOKENS)
                .isEqualTo(16_000);
    }

    @Test
    @DisplayName("창을 모르면 아무것도 하지 않는다")
    void unknownWindowIsSilent() {
        assertThat(LlmConfig.warnIfBelowSupportedWindow("p", null, PROBED)).isFalse();
        assertThat(LlmConfig.warnIfBelowSupportedWindow("p", 0, PROBED)).isFalse();
    }

    @Test
    @DisplayName("16K 로 띄운 서버(16,384)와 경계값(16,000)은 통과한다")
    void sixteenKPasses() {
        assertThat(LlmConfig.warnIfBelowSupportedWindow("p", 16_384, PROBED)).isFalse();
        assertThat(LlmConfig.warnIfBelowSupportedWindow("p", 16_000, ProviderContextWindows.Source.CONFIGURED)).isFalse();
        assertThat(LlmConfig.warnIfBelowSupportedWindow("p", 131_072, PROBED)).isFalse();
    }

    @Test
    @DisplayName("그보다 작으면 경고한다 — 선언값이든 탐지값이든")
    void smallerWindowsWarn() {
        assertThat(LlmConfig.warnIfBelowSupportedWindow("p", 15_999, PROBED)).isTrue();
        assertThat(LlmConfig.warnIfBelowSupportedWindow("p", 12_288, PROBED)).isTrue();
        assertThat(LlmConfig.warnIfBelowSupportedWindow("p", 8_192, ProviderContextWindows.Source.CONFIGURED)).isTrue();
    }
}
