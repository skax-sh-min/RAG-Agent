package com.example.ragagent.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 응답 모드(S/N/C) 파싱·예산·성질 플래그 단위 테스트 (PLAN §6.24 Step 0-a·2-a). */
class ResponseModeTest {

    @Test
    @DisplayName("parse — 대소문자·공백 무관하게 S/N/C를 인식한다")
    void parse_acceptsAnyCase() {
        assertThat(ResponseMode.parse("S")).isEqualTo(ResponseMode.S);
        assertThat(ResponseMode.parse("n")).isEqualTo(ResponseMode.N);
        assertThat(ResponseMode.parse(" N ")).isEqualTo(ResponseMode.N);
        assertThat(ResponseMode.parse("c")).isEqualTo(ResponseMode.C);
        assertThat(ResponseMode.parse(" C ")).isEqualTo(ResponseMode.C);
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
    @DisplayName("tokenRatio / minChars — S=15%·2000, N·C=70%·5000(구 L의 몫을 물려받음)")
    void ratiosAndFloors() {
        assertThat(ResponseMode.S.tokenRatio()).isEqualTo(0.15);
        assertThat(ResponseMode.N.tokenRatio()).isEqualTo(0.70);
        assertThat(ResponseMode.S.minChars()).isEqualTo(2_000);
        assertThat(ResponseMode.N.minChars()).isEqualTo(5_000);
        // C의 분량 성격은 N과 같다(둘 다 프롬프트에 숫자를 두지 않는다) → 예산도 같다.
        assertThat(ResponseMode.C.tokenRatio()).isEqualTo(ResponseMode.N.tokenRatio());
        assertThat(ResponseMode.C.minChars()).isEqualTo(ResponseMode.N.minChars());
    }

    @Test
    @DisplayName("maxTokens — 설정값이 커서 비율항이 minChars를 넘으면 비율이 채택된다")
    void maxTokens_appliesConfiguredRatioWhenLargerThanFloor() {
        assertThat(ResponseMode.S.maxTokens(16_000)).isEqualTo(2_400);  // 15% = 2,400 > 2,000
        assertThat(ResponseMode.N.maxTokens(16_000)).isEqualTo(11_200); // 70% = 11,200 > 5,000
    }

    @Test
    @DisplayName("maxTokens — 두 모드의 전환점이 서로 다르다(S 13,334 / N 7,143)")
    void maxTokens_floorAndRatioCrossoverDiffersPerMode() {
        // max-tokens=6,000: 비율항(900 / 4,200)이 둘 다 minChars 아래 → 바닥이 받쳐준다.
        assertThat(ResponseMode.S.maxTokens(6_000)).isEqualTo(2_000);
        assertThat(ResponseMode.N.maxTokens(6_000)).isEqualTo(5_000);
        // 12,000: N은 이미 비율(8,400)이 이기지만 S는 아직 바닥(1,800 < 2,000)이다.
        assertThat(ResponseMode.S.maxTokens(12_000)).isEqualTo(2_000);
        assertThat(ResponseMode.N.maxTokens(12_000)).isEqualTo(8_400);
        // 전환점 직전/직후 — N은 5,000/0.70 ≈ 7,143 에서 갈린다.
        assertThat(ResponseMode.N.maxTokens(7_000)).isEqualTo(5_000);   // 4,900 < 5,000
        assertThat(ResponseMode.N.maxTokens(7_200)).isEqualTo(5_040);   // 5,040 > 5,000
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
    @DisplayName("성질 플래그 — S만 검증을 건너뛰고, C만 Direct 불가. 검색 부스트는 아직 전부 0")
    void capabilityFlags() {
        assertThat(ResponseMode.S.skipsVerification()).isTrue();
        assertThat(ResponseMode.S.evalPromptKey()).isNull();
        assertThat(ResponseMode.N.skipsVerification()).isFalse();
        assertThat(ResponseMode.N.evalPromptKey()).isEqualTo("prompt.answer.eval");
        // C는 검증을 '끄지' 않고 바꿔 낀다 — 통째로 끄면 문서에 없는 API 발명이 무방비가 된다.
        assertThat(ResponseMode.C.skipsVerification()).isFalse();
        assertThat(ResponseMode.C.evalPromptKey()).isEqualTo("prompt.answer.eval.creative");

        // 검색 결과가 전제인 모드는 Direct 로 부를 수 없다 — 프롬프트 키 부재가 곧 그 사실이다.
        assertThat(ResponseMode.C.allowsDirect()).isFalse();
        assertThat(ResponseMode.C.directSystemPromptKey()).isNull();
        for (ResponseMode mode : List.of(ResponseMode.S, ResponseMode.N)) {
            assertThat(mode.allowsDirect()).as("%s.allowsDirect", mode).isTrue();
            assertThat(mode.directSystemPromptKey()).as("%s.directSystemPromptKey", mode).isNotNull();
        }
        // 부스트를 0보다 올리려면 MAX_EVAL_EXCERPT_CHARS 상향이 선행돼야 한다(§6.24 Step 4-c) —
        // 이 단언이 깨지면 그 선행 조건을 먼저 확인하라는 뜻이다.
        for (ResponseMode mode : ResponseMode.values()) {
            assertThat(mode.retrievalBoost()).as("%s.retrievalBoost", mode).isZero();
        }
    }

    @Test
    @DisplayName("성질 플래그 — 창의 온도/창의 검증은 C에서만 켜진다")
    void creativeFlagsAreExclusiveToCreativeMode() {
        assertThat(ResponseMode.C.usesCreativeTemperature()).isTrue();
        assertThat(ResponseMode.C.usesCreativeEval()).isTrue();
        for (ResponseMode mode : List.of(ResponseMode.S, ResponseMode.N)) {
            assertThat(mode.usesCreativeTemperature()).as("%s.usesCreativeTemperature", mode).isFalse();
            assertThat(mode.usesCreativeEval()).as("%s.usesCreativeEval", mode).isFalse();
        }
        // 창의 검증 프롬프트가 있는 모드만 창의 파서를 쓴다 — 프롬프트와 파서는 반드시 짝이다.
        for (ResponseMode mode : ResponseMode.values()) {
            assertThat(mode.usesCreativeEval())
                    .as("%s — 창의 파서와 창의 프롬프트가 어긋났다", mode)
                    .isEqualTo("prompt.answer.eval.creative".equals(mode.evalPromptKey()));
        }
    }

    @Test
    @DisplayName("allowsReuse — C는 재사용 후보가 아니다 (\"다시 만들어줘\"에 저장된 코드를 돌려줄 수 없다)")
    void creativeModeIsNeverReused() {
        // 이 모드의 요청은 "찾아 달라"가 아니라 "만들어 달라"다 — 캐시가 기능 자체를 배신한다.
        assertThat(ResponseMode.C.allowsReuse()).isFalse();
        // S 는 이 플래그가 생기기 전부터 SQL 리터럴로 제외돼 있었다(동작 변화 없음).
        assertThat(ResponseMode.S.allowsReuse()).isFalse();
        assertThat(ResponseMode.N.allowsReuse()).isTrue();
    }

    @Test
    @DisplayName("allowsCuration — C는 절대 큐레이션되지 않는다(모델이 지어낸 코드가 다음 턴의 '문서'가 되는 되먹임)")
    void creativeModeIsNeverCuratable() {
        // 이 설계에서 가장 위험한 단일 지점 — 되돌리려면 벡터를 찾아 지워야 한다(§6.24 Step 3-a).
        assertThat(ResponseMode.C.allowsCuration()).isFalse();
    }

    @Test
    @DisplayName("allowsCuration — S는 큐레이션 대상이 아니다(좋아요 무동작), N만 승격된다")
    void summaryModeIsNotCuratable() {
        // S 답변은 전체가 "## 요약" 한 섹션이라 큐레이션 임베딩 입력에서 본문이 통째로 사라진다
        // (stripStructuralSections 가 요약·참고를 걷어내는데 S에는 남을 것이 없다).
        assertThat(ResponseMode.S.allowsCuration()).isFalse();
        assertThat(ResponseMode.N.allowsCuration()).isTrue();
    }

    @Test
    @DisplayName("summaryOnly — S만 요약 전용 후처리 대상이다(skipsVerification과 값이 같아도 별개 성질)")
    void summaryOnlyIsSeparateFromSkipsVerification() {
        assertThat(ResponseMode.S.summaryOnly()).isTrue();
        assertThat(ResponseMode.N.summaryOnly()).isFalse();
        // 지금은 S에서 두 값이 우연히 같다. 뒤에 붙는 모드에서 갈릴 수 있으므로 하나로 합치지
        // 않는다 — 합치면 그때 한쪽을 조용히 잘못 상속한다.
        assertThat(ResponseMode.S.summaryOnly()).isEqualTo(ResponseMode.S.skipsVerification());
    }

    @Test
    @DisplayName("프롬프트 키 — 모드별 전용 시스템 프롬프트 키를 노출한다(번들은 Step 1-a에서 추가)")
    void systemPromptKeys() {
        assertThat(ResponseMode.S.answerSystemPromptKey()).isEqualTo("prompt.answer.system.s");
        assertThat(ResponseMode.N.answerSystemPromptKey()).isEqualTo("prompt.answer.system.n");
        assertThat(ResponseMode.C.answerSystemPromptKey()).isEqualTo("prompt.answer.system.c");
        assertThat(ResponseMode.S.directSystemPromptKey()).isEqualTo("prompt.direct.system.s");
        assertThat(ResponseMode.N.directSystemPromptKey()).isEqualTo("prompt.direct.system.n");
    }
}
