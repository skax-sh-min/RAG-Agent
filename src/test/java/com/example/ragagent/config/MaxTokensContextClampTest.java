package com.example.ragagent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 출력 예약이 컨텍스트 창을 통째로 먹는 설정을 기동 시점에 잡아내는 규칙.
 *
 * <p>OpenAI 호환 서버에서 {@code max_tokens} 는 <b>예약</b>이라, {@code max-tokens >= context-size} 면
 * 입력에 남는 자리가 0 이하가 되어 <b>어떤 요청도 성공할 수 없다</b>. 설정만 보고는 아무도 눈치채지
 * 못하고 매 질문이 "Context size has been exceeded" 로만 실패한다.
 */
class MaxTokensContextClampTest {

    @Test
    @DisplayName("창을 모르면 아무것도 하지 않는다 — 모르는 값으로 남의 설정을 깎지 않는다")
    void unknownContextLeavesRequestedValueAlone() {
        assertThat(LlmConfig.capMaxTokensToContext("p", 10_000, null)).isEqualTo(10_000);
    }

    @Test
    @DisplayName("창보다 작으면 그대로 둔다")
    void fittingValueIsUntouched() {
        assertThat(LlmConfig.capMaxTokensToContext("p", 4_000, 16_384)).isEqualTo(4_000);
        // 경계: 딱 1 작은 값도 통과해야 한다(입력 자리가 1 이라도 남으면 설정 자체는 성립한다).
        assertThat(LlmConfig.capMaxTokensToContext("p", 8_191, 8_192)).isEqualTo(8_191);
    }

    @Test
    @DisplayName("창과 같거나 크면 창의 절반으로 낮춘다 — 입력이 출력보다 작아지면 안 된다")
    void reservationThatLeavesNoRoomIsHalved() {
        // 사용자가 실제로 겪은 조합: 10,000 예약 + 8,192 창 → 입력 자리 없음.
        assertThat(LlmConfig.capMaxTokensToContext("local", 10_000, 8_192)).isEqualTo(4_096);
        // 같을 때도 마찬가지다(입력 자리 정확히 0).
        assertThat(LlmConfig.capMaxTokensToContext("local", 8_192, 8_192)).isEqualTo(4_096);
    }

    @Test
    @DisplayName("아주 작은 창에서도 최소 256 은 남긴다 — 0 토큰짜리 응답은 설정이 아니라 고장이다")
    void keepsAFloorOnTinyWindows() {
        assertThat(LlmConfig.capMaxTokensToContext("tiny", 1_000, 100)).isEqualTo(256);
    }
}
