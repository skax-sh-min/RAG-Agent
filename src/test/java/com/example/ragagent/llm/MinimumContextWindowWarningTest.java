package com.example.ragagent.llm;

import com.example.ragagent.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "창 16K 미만" 경고의 판정 — 기동·재탐지 버튼·자동 재탐지 세 자리가 같은 함수를 부른다. 경계는 {@code max-tokens} 하한의
 * 두 배, 출력 예약이 창의 절반을 넘지 않는 가장 작은 창이다({@link ProviderContextWindows#cappedMaxTokens} 와 같은 경계).
 */
class MinimumContextWindowWarningTest {

    private static final ProviderContextWindows.Source PROBED = ProviderContextWindows.Source.PROBED;

    @Test
    @DisplayName("경계는 max-tokens 하한의 두 배(16,000) — 하한이 바뀌면 함께 움직인다")
    void thresholdIsTwiceTheMaxTokensFloor() {
        assertThat(ProviderContextWindows.MIN_SUPPORTED_TOKENS)
                .isEqualTo(2 * AppProperties.MIN_MAX_TOKENS)
                .isEqualTo(16_000);
    }

    @Test
    @DisplayName("창을 모르면 아무것도 하지 않는다")
    void unknownWindowIsSilent() {
        assertThat(ProviderContextWindows.belowSupportedMinimum(null)).isFalse();
        assertThat(ProviderContextWindows.belowSupportedMinimum(0)).isFalse();
        assertThat(ProviderContextWindows.warnIfBelowSupportedMinimum("p", null, PROBED, "기동")).isFalse();
    }

    @Test
    @DisplayName("16K 로 띄운 서버(16,384)와 경계값(16,000)은 통과한다")
    void sixteenKPasses() {
        assertThat(ProviderContextWindows.warnIfBelowSupportedMinimum("p", 16_384, PROBED, "기동")).isFalse();
        assertThat(ProviderContextWindows.warnIfBelowSupportedMinimum("p", 16_000,
                ProviderContextWindows.Source.CONFIGURED, "기동")).isFalse();
        assertThat(ProviderContextWindows.warnIfBelowSupportedMinimum("p", 131_072, PROBED, "재탐지")).isFalse();
    }

    @Test
    @DisplayName("그보다 작으면 경고한다 — 선언값이든 탐지값이든, 어느 자리에서 알았든")
    void smallerWindowsWarn() {
        assertThat(ProviderContextWindows.warnIfBelowSupportedMinimum("p", 15_999, PROBED, "기동")).isTrue();
        assertThat(ProviderContextWindows.warnIfBelowSupportedMinimum("p", 12_288, PROBED, "재탐지")).isTrue();
        assertThat(ProviderContextWindows.warnIfBelowSupportedMinimum("p", 8_192,
                ProviderContextWindows.Source.CONFIGURED, "자동 재탐지")).isTrue();
    }
}
