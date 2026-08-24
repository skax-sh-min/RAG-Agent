package com.example.ragagent.model;

import com.example.ragagent.llm.RoutingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct 배타의 <b>서버</b> 가드 (PLAN §6.24 Step 4-a).
 *
 * <p>C는 검색 결과가 전제라 Direct(검색 없음)와 함께 성립하지 않는다. 채팅 화면이 그 조합의 버튼을
 * 비활성화하지만 그것은 편의일 뿐이다 — <b>구 L 모드는 서버 가드가 없어 손으로 만든
 * {@code responseMode=L&directMode=true} 요청이 그대로 통과했다.</b> 그래서 두 진입점 값 객체가
 * 각각 같은 성질을 묻는다: REST 는 {@link ChatRequest}, HTMX 폼과 SSE 스트리밍은
 * {@link ChatForm#responseModeOrDefault()}(둘이 공유하는 유일한 지점 — 컨트롤러의 라디오 정규화는
 * SSE 경로를 지나가지 않는다).
 *
 * <p>강등 결과가 <b>저장되는 값까지</b> N이어야 한다는 점이 이 가드를 그래프 쪽 가드와 구분한다.
 * {@code AgentGraph} 도 같은 성질을 묻지만 그쪽은 널 프롬프트 키로 터지지 않게 하는 그래프 자신의
 * 정합성 보장이고, 거기서만 막으면 {@code conversation_turns.response_mode} 에는 C가 남은 채
 * 실제로는 N으로 답한 턴이 된다.
 */
class ResponseModeDirectExclusivityTest {

    private static ChatForm form(String responseMode, Boolean directMode) {
        return new ChatForm("질문", "t1", "latest", "COST_FIRST", directMode, "", responseMode);
    }

    private static ChatRequest request(ResponseMode mode, boolean directMode) {
        return new ChatRequest("질문", "latest", "t1", RoutingMode.COST_FIRST, directMode, List.of(), mode);
    }

    @Test
    @DisplayName("REST — responseMode=C + directMode=true 는 N으로 강등된다")
    void restRequest_creativeWithDirect_downgradesToDefault() {
        assertThat(request(ResponseMode.C, true).responseMode()).isEqualTo(ResponseMode.N);
    }

    @Test
    @DisplayName("REST — Direct가 아니면 C는 그대로 통과한다")
    void restRequest_creativeWithoutDirect_isKept() {
        assertThat(request(ResponseMode.C, false).responseMode()).isEqualTo(ResponseMode.C);
    }

    @Test
    @DisplayName("HTMX/SSE 폼 — responseMode=C + directMode=true 는 N으로 강등된다")
    void form_creativeWithDirect_downgradesToDefault() {
        assertThat(form("C", true).responseModeOrDefault()).isEqualTo(ResponseMode.N);
        assertThat(form("c", true).responseModeOrDefault()).isEqualTo(ResponseMode.N);
    }

    @Test
    @DisplayName("HTMX/SSE 폼 — Direct가 아니면 C는 그대로 통과한다")
    void form_creativeWithoutDirect_isKept() {
        assertThat(form("C", false).responseModeOrDefault()).isEqualTo(ResponseMode.C);
        assertThat(form("C", null).responseModeOrDefault()).isEqualTo(ResponseMode.C);
    }

    @Test
    @DisplayName("Direct를 허용하는 모드는 강등되지 않는다 (S/N은 영향 없음)")
    void directCapableModesAreUntouched() {
        for (ResponseMode mode : ResponseMode.values()) {
            if (!mode.allowsDirect()) continue;
            assertThat(request(mode, true).responseMode())
                    .as("%s 는 Direct 가능한 모드라 강등 대상이 아니다", mode).isEqualTo(mode);
            assertThat(form(mode.name(), true).responseModeOrDefault())
                    .as("%s (form)", mode).isEqualTo(mode);
        }
    }

    @Test
    @DisplayName("가드는 값이 아니라 allowsDirect() 로 판정한다 — 모드가 늘어도 자동으로 덮인다")
    void guardCoversEveryDirectIncapableMode() {
        List<ResponseMode> blocked = java.util.Arrays.stream(ResponseMode.values())
                .filter(m -> !m.allowsDirect()).toList();
        assertThat(blocked).as("Direct 불가 모드가 하나도 없으면 이 가드는 아무것도 지키지 않는다")
                .isNotEmpty();
        for (ResponseMode mode : blocked) {
            assertThat(request(mode, true).responseMode()).as("%s (REST)", mode).isEqualTo(ResponseMode.DEFAULT);
            assertThat(form(mode.name(), true).responseModeOrDefault()).as("%s (form)", mode).isEqualTo(ResponseMode.DEFAULT);
        }
    }
}
