package com.example.ragagent.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 응답 모드(S/M/L) 파싱·한도 단위 테스트. */
class ResponseModeTest {

    @Test
    @DisplayName("parse — 대소문자 무관하게 S/M/L을 인식한다")
    void parse_acceptsAnyCase() {
        assertThat(ResponseMode.parse("S")).isEqualTo(ResponseMode.S);
        assertThat(ResponseMode.parse("m")).isEqualTo(ResponseMode.M);
        assertThat(ResponseMode.parse(" l ")).isEqualTo(ResponseMode.L);
    }

    @Test
    @DisplayName("parse — null/공백/알 수 없는 값은 기본값 M으로 폴백한다(예외 없음)")
    void parse_fallsBackToDefault() {
        assertThat(ResponseMode.parse(null)).isEqualTo(ResponseMode.M);
        assertThat(ResponseMode.parse("")).isEqualTo(ResponseMode.M);
        assertThat(ResponseMode.parse("   ")).isEqualTo(ResponseMode.M);
        assertThat(ResponseMode.parse("XL")).isEqualTo(ResponseMode.M);
        assertThat(ResponseMode.DEFAULT).isEqualTo(ResponseMode.M);
    }

    @Test
    @DisplayName("tokenRatio — S/M/L 각각 15% / 40% / 70%")
    void tokenRatios() {
        assertThat(ResponseMode.S.tokenRatio()).isEqualTo(0.15);
        assertThat(ResponseMode.M.tokenRatio()).isEqualTo(0.40);
        assertThat(ResponseMode.L.tokenRatio()).isEqualTo(0.70);
    }

    @Test
    @DisplayName("minChars — S/M/L 각각 2000 / 5000 / 10000")
    void minChars() {
        assertThat(ResponseMode.S.minChars()).isEqualTo(2_000);
        assertThat(ResponseMode.M.minChars()).isEqualTo(5_000);
        assertThat(ResponseMode.L.minChars()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("maxTokens — 설정값이 커서 비율항이 minChars를 넘으면 비율이 채택된다")
    void maxTokens_appliesConfiguredRatioWhenLargerThanFloor() {
        assertThat(ResponseMode.S.maxTokens(16_000)).isEqualTo(2_400); // 15% = 2400 > 2000
        assertThat(ResponseMode.M.maxTokens(16_000)).isEqualTo(6_400); // 40% = 6400 > 5000
        assertThat(ResponseMode.L.maxTokens(16_000)).isEqualTo(11_200); // 70% = 11200 > 10000
    }

    @Test
    @DisplayName("maxTokens — 현실적인 설정 범위(6000~12000)에서는 minChars 바닥값이 채택된다(항상 두 값 중 큰 값)")
    void maxTokens_flooredAtMinCharsForTypicalConfig() {
        // default LLM_MAX_TOKENS=6000: ratio항(900/2400/4200)이 모두 minChars보다 작음
        assertThat(ResponseMode.S.maxTokens(6_000)).isEqualTo(2_000);
        assertThat(ResponseMode.M.maxTokens(6_000)).isEqualTo(5_000);
        assertThat(ResponseMode.L.maxTokens(6_000)).isEqualTo(10_000);
        // 12000으로 올려도 ratio항(1800/4800/8400)이 여전히 minChars보다 작음
        assertThat(ResponseMode.S.maxTokens(12_000)).isEqualTo(2_000);
        assertThat(ResponseMode.M.maxTokens(12_000)).isEqualTo(5_000);
        assertThat(ResponseMode.L.maxTokens(12_000)).isEqualTo(10_000);
    }

    @Test
    @DisplayName("maxTokens — 설정값이 아주 작아도 각 모드의 minChars 밑으로는 내려가지 않는다")
    void maxTokens_flooredAtMinChars() {
        assertThat(ResponseMode.S.maxTokens(100)).isEqualTo(2_000);
        assertThat(ResponseMode.M.maxTokens(100)).isEqualTo(5_000);
        assertThat(ResponseMode.L.maxTokens(100)).isEqualTo(10_000);
    }

    @Test
    @DisplayName("maxTokens — 설정값이 0/음수면 0(프로바이더 기본값 유지)을 반환한다")
    void maxTokens_zeroWhenUnconfigured() {
        assertThat(ResponseMode.M.maxTokens(0)).isZero();
        assertThat(ResponseMode.M.maxTokens(-1)).isZero();
    }

    @Test
    @DisplayName("promptKey — 모드별 i18n 키를 소문자로 만든다")
    void promptKey() {
        assertThat(ResponseMode.S.promptKey()).isEqualTo("prompt.answer.style.s");
        assertThat(ResponseMode.M.promptKey()).isEqualTo("prompt.answer.style.m");
        assertThat(ResponseMode.L.promptKey()).isEqualTo("prompt.answer.style.l");
    }
}
