package com.example.ragagent.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 응답 모드(S/N) 파싱·예산·성질 플래그 단위 테스트 (PLAN §6.24 Step 0-a). */
class ResponseModeTest {

    @Test
    @DisplayName("parse — 대소문자·공백 무관하게 S/N을 인식한다")
    void parse_acceptsAnyCase() {
        assertThat(ResponseMode.parse("S")).isEqualTo(ResponseMode.S);
        assertThat(ResponseMode.parse("n")).isEqualTo(ResponseMode.N);
        assertThat(ResponseMode.parse(" N ")).isEqualTo(ResponseMode.N);
    }

    @Test
    @DisplayName("parse — null/공백/알 수 없는 값은 기본값 N으로 폴백한다(예외 없음)")
    void parse_fallsBackToDefault() {
        assertThat(ResponseMode.parse(null)).isEqualTo(ResponseMode.N);
        assertThat(ResponseMode.parse("")).isEqualTo(ResponseMode.N);
        assertThat(ResponseMode.parse("   ")).isEqualTo(ResponseMode.N);
        assertThat(ResponseMode.parse("XL")).isEqualTo(ResponseMode.N);
        assertThat(ResponseMode.DEFAULT).isEqualTo(ResponseMode.N);
    }

    @Test
    @DisplayName("parse — 옛 M/L 기록은 마이그레이션 없이 N으로 흡수된다(DB·localStorage 하위호환)")
    void parse_legacyModesDegradeToDefault() {
        // conversation_turns.response_mode 에 남은 문자열과 브라우저 localStorage 의 옛 선택값이
        // 모두 이 경로를 탄다 — 그래서 M→N 개명에 스키마 마이그레이션이 필요 없다.
        assertThat(ResponseMode.parse("M")).isEqualTo(ResponseMode.N);
        assertThat(ResponseMode.parse("m")).isEqualTo(ResponseMode.N);
        assertThat(ResponseMode.parse("L")).isEqualTo(ResponseMode.N);
        assertThat(ResponseMode.parse(" l ")).isEqualTo(ResponseMode.N);
    }

    @Test
    @DisplayName("tokenRatio / minChars — S=15%·2000, N=40%·5000")
    void ratiosAndFloors() {
        assertThat(ResponseMode.S.tokenRatio()).isEqualTo(0.15);
        assertThat(ResponseMode.N.tokenRatio()).isEqualTo(0.40);
        assertThat(ResponseMode.S.minChars()).isEqualTo(2_000);
        assertThat(ResponseMode.N.minChars()).isEqualTo(5_000);
    }

    @Test
    @DisplayName("maxTokens — 설정값이 커서 비율항이 minChars를 넘으면 비율이 채택된다")
    void maxTokens_appliesConfiguredRatioWhenLargerThanFloor() {
        assertThat(ResponseMode.S.maxTokens(16_000)).isEqualTo(2_400); // 15% = 2400 > 2000
        assertThat(ResponseMode.N.maxTokens(16_000)).isEqualTo(6_400); // 40% = 6400 > 5000
    }

    @Test
    @DisplayName("maxTokens — 실사용 설정(6000~12000)에서는 minChars 바닥값이 채택된다")
    void maxTokens_flooredAtMinCharsForTypicalConfig() {
        // LLM_MAX_TOKENS=6000: ratio항(900/2400)이 모두 minChars보다 작다.
        assertThat(ResponseMode.S.maxTokens(6_000)).isEqualTo(2_000);
        assertThat(ResponseMode.N.maxTokens(6_000)).isEqualTo(5_000);
        // 12000으로 올려도 ratio항(1800/4800)이 여전히 minChars보다 작다 — 전환점은 S 13,334 / N 12,501.
        assertThat(ResponseMode.S.maxTokens(12_000)).isEqualTo(2_000);
        assertThat(ResponseMode.N.maxTokens(12_000)).isEqualTo(5_000);
    }

    @Test
    @DisplayName("maxTokens — 운영자가 설정한 max-tokens를 절대 넘지 않는다(§6.24 클램프)")
    void maxTokens_neverExceedsConfiguredCeiling() {
        // 예전에는 minChars 바닥이 상한을 뚫어 기본 설정(6000)에서 L이 10,000을 요청했다.
        // 이제 두 모드 모두 설정값에서 잘린다.
        assertThat(ResponseMode.S.maxTokens(1_000)).isEqualTo(1_000);
        assertThat(ResponseMode.N.maxTokens(1_000)).isEqualTo(1_000);
        assertThat(ResponseMode.N.maxTokens(3_000)).isEqualTo(3_000);
        for (ResponseMode mode : ResponseMode.values()) {
            for (int configured : new int[]{100, 1_000, 6_000, 12_000, 16_000}) {
                assertThat(mode.maxTokens(configured))
                        .as("%s.maxTokens(%d) must not exceed the configured ceiling", mode, configured)
                        .isLessThanOrEqualTo(configured);
            }
        }
    }

    @Test
    @DisplayName("maxTokens — 설정값이 0/음수면 0(프로바이더 기본값 유지)을 반환한다")
    void maxTokens_zeroWhenUnconfigured() {
        assertThat(ResponseMode.N.maxTokens(0)).isZero();
        assertThat(ResponseMode.N.maxTokens(-1)).isZero();
    }

    @Test
    @DisplayName("성질 플래그 — S만 검증을 건너뛰고, 두 모드 다 Direct 가능·큐레이션 가능·검색 부스트 없음")
    void capabilityFlags() {
        assertThat(ResponseMode.S.skipsVerification()).isTrue();
        assertThat(ResponseMode.S.evalPromptKey()).isNull();
        assertThat(ResponseMode.N.skipsVerification()).isFalse();
        assertThat(ResponseMode.N.evalPromptKey()).isEqualTo("prompt.answer.eval");

        for (ResponseMode mode : ResponseMode.values()) {
            assertThat(mode.allowsDirect()).as("%s.allowsDirect", mode).isTrue();
            assertThat(mode.allowsCuration()).as("%s.allowsCuration", mode).isTrue();
            assertThat(mode.retrievalBoost()).as("%s.retrievalBoost", mode).isZero();
            assertThat(mode.directSystemPromptKey()).as("%s.directSystemPromptKey", mode).isNotNull();
        }
    }

    @Test
    @DisplayName("프롬프트 키 — 모드별 전용 시스템 프롬프트 키를 노출한다(번들은 Step 1-a에서 추가)")
    void systemPromptKeys() {
        assertThat(ResponseMode.S.answerSystemPromptKey()).isEqualTo("prompt.answer.system.s");
        assertThat(ResponseMode.N.answerSystemPromptKey()).isEqualTo("prompt.answer.system.n");
        assertThat(ResponseMode.S.directSystemPromptKey()).isEqualTo("prompt.direct.system.s");
        assertThat(ResponseMode.N.directSystemPromptKey()).isEqualTo("prompt.direct.system.n");
    }

    @Test
    @DisplayName("promptKey — 모드별 i18n 키를 소문자로 만든다(Step 1-a에서 이 층 자체가 사라진다)")
    void promptKey() {
        assertThat(ResponseMode.S.promptKey()).isEqualTo("prompt.answer.style.s");
        assertThat(ResponseMode.N.promptKey()).isEqualTo("prompt.answer.style.n");
    }
}
