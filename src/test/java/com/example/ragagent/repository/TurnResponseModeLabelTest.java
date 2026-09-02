package com.example.ragagent.repository;

import com.example.ragagent.model.ResponseMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 질문 버블 표기 규칙 — <b>두 글자</b>다. 앞이 검색 축(R/D), 뒤가 답변의 성격(S/N/C).
 *
 * <p>예전에는 성격만 적어서(`[N]`) 같은 질문을 문서로 물었는지 모델 지식으로 물었는지가 화면
 * 어디에도 없었다. 두 축은 직교하므로 한 글자로 뭉칠 수 없다 — 특히 {@code DS} 와 {@code DN} 은
 * 프롬프트({@code prompt.direct.system.s/n})도 후처리({@code SummaryOnlyGuard})도 다른 별개의
 * 조합이다.
 *
 * <p><b>이 테스트가 지키는 것</b>은 규칙의 출처가 하나라는 것이다. 클라이언트 렌더러 셋이 같은
 * 규칙을 {@code base.html} 의 {@code bubbleModeLabel()} 로 구현하고 있어, 서버 쪽이 조용히
 * 달라지면 같은 대화가 새로고침 전후로 다른 표기를 달게 된다.
 */
class TurnResponseModeLabelTest {

    private static MemoryRepository.Turn turn(String responseMode, boolean directMode) {
        return new MemoryRepository.Turn(1L, "질문", "답변", "2026-09-02T00:00:00Z", null,
                0, 0, 0, "local", 1, null, responseMode, null, directMode);
    }

    @Test
    @DisplayName("검색 축이 앞, 성격이 뒤 — 실제로 나오는 다섯 조합")
    void bothAxesAppear() {
        assertThat(turn("S", false).responseModeLabel()).isEqualTo("RS");
        assertThat(turn("N", false).responseModeLabel()).isEqualTo("RN");
        assertThat(turn("C", false).responseModeLabel()).isEqualTo("RC");
        assertThat(turn("S", true).responseModeLabel()).isEqualTo("DS");
        assertThat(turn("N", true).responseModeLabel()).isEqualTo("DN");
    }

    @Test
    @DisplayName("C 의 R 은 정보를 안 나르지만 그대로 둔다 — 혼자 [C] 면 렌더링 오류로 읽힌다")
    void creativeKeepsItsRedundantPrefix() {
        assertThat(turn("C", false).responseModeLabel()).hasSize(2).startsWith("R");
    }

    @Test
    @DisplayName("성격 글자의 출처는 ResponseMode 하나다 — 값 목록을 여기에 복제하지 않는다")
    void theModeLetterComesFromTheEnum() {
        for (ResponseMode mode : ResponseMode.values()) {
            assertThat(turn(mode.name(), false).responseModeLabel())
                    .as("%s", mode).isEqualTo("R" + mode.name());
        }
    }

    @Test
    @DisplayName("구 M/L·NULL 은 실제 동작과 같은 N 으로 표기된다 — 저장 문자열을 그대로 쓰지 않는 이유")
    void legacyValuesRenderAsTheModeTheyActuallyBehaveAs() {
        assertThat(turn("M", false).responseModeLabel()).isEqualTo("RN");
        assertThat(turn("L", true).responseModeLabel()).isEqualTo("DN");
        assertThat(turn(null, false).responseModeLabel()).isEqualTo("RN");
    }

    @Test
    @DisplayName("direct_mode 컬럼이 생기기 전 턴은 전부 R 로 읽힌다 — DEFAULT 0, 구분할 방법이 없다")
    void turnsFromBeforeTheColumnReadAsRag() {
        assertThat(turn("N", false).responseModeLabel()).startsWith("R");
    }

    @Test
    @DisplayName("템플릿이 ${turn.responseModeLabel} 로 읽으므로 SpEL 접근이 가능해야 한다")
    void isReachableFromSpel() {
        // 레코드 컴포넌트가 아닌 파생 메서드라, 접근 가능 여부가 컴파일러에 잡히지 않는다.
        Object value = new SpelExpressionParser()
                .parseExpression("responseModeLabel")
                .getValue(new StandardEvaluationContext(turn("S", true)));

        assertThat(value).isEqualTo("DS");
    }
}
