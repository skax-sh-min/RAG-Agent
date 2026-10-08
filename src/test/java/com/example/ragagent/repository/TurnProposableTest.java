package com.example.ragagent.repository;

import com.example.ragagent.model.ResponseMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 좋아요 버튼이 <b>실제 동작과 같은 것을 보여주는가</b> — 대화 기록 렌더러가 읽는 파생 값을 고정한다.
 *
 * <p>좋아요가 지식 제안을 열어 주지 않는 모드에서 버튼을 그대로 두면, 눌린 상태로 남아 사용자는
 * 공유 지식에 기여했다고 믿게 된다. 그 거짓말을 없애는 것이
 * {@link MemoryRepository.Turn#proposable()}이고, 규칙의 출처는 {@link ResponseMode} 하나여야
 * 한다({@code SourceRef.staleBadge()} 선례).
 */
class TurnProposableTest {

    private static MemoryRepository.Turn turn(String responseMode) {
        return new MemoryRepository.Turn(1L, "질문", "답변", "2026-08-25T00:00:00Z", null,
                0, 0, 0, "local", 1, null, responseMode, null, false);
    }

    @Test
    @DisplayName("S 턴만 좋아요가 아무 일도 하지 않는다 — C 는 §10.11 에서 열렸다")
    void onlySummaryIsBlocked() {
        assertThat(turn("S").proposable()).isFalse();
        assertThat(turn("C").proposable()).isTrue();
        assertThat(turn("N").proposable()).isTrue();
    }

    @Test
    @DisplayName("판정의 출처는 ResponseMode 하나다 — 값 목록을 여기에 복제하지 않는다")
    void derivesFromTheEnumForEveryMode() {
        for (ResponseMode mode : ResponseMode.values()) {
            assertThat(turn(mode.name()).proposable())
                    .as("%s", mode).isEqualTo(mode.allowsSubmission());
            assertThat(turn(mode.name()).submissionBlockedMessageKey())
                    .as("%s", mode).isEqualTo(mode.submissionBlockedMessageKey());
        }
    }

    @Test
    @DisplayName("사유 키는 막힌 모드에만 붙고, 제안 가능한 모드에서는 null")
    void blockedReasonIsPerModeAndNullWhenAllowed() {
        // 불린 하나로는 툴팁을 쓸 수 없다 — "안 됩니다"만 말하고 사유를 감추면 버그로 읽힌다.
        assertThat(turn("S").submissionBlockedMessageKey()).isEqualTo("feedback.like.disabled.s");
        assertThat(turn("C").submissionBlockedMessageKey()).isNull();
        assertThat(turn("N").submissionBlockedMessageKey()).isNull();
    }

    @Test
    @DisplayName("레거시·미지 모드 값은 N으로 읽혀 좋아요가 계속 동작한다")
    void legacyAndUnknownModesStayProposable() {
        // parse()의 관대함과 같은 방향 — 값을 못 읽었다는 이유로 기존 동작(좋아요 가능)을
        // 빼앗지 않는다. 컬럼이 nullable이라 이 경로가 실제로 존재한다.
        for (String legacy : new String[]{null, "", "  ", "M", "L", "몰라"}) {
            assertThat(turn(legacy).proposable()).as("%s", legacy).isTrue();
            assertThat(turn(legacy).submissionBlockedMessageKey()).as("%s", legacy).isNull();
        }
    }

    @Test
    @DisplayName("템플릿이 쓰는 SpEL 프로퍼티 표기로 접근된다")
    void accessibleAsSpelProperty() {
        // chat.html이 ${turn.proposable}/${turn.submissionBlockedMessageKey}로 읽는다. 둘 다 레코드
        // 컴포넌트가 아닌 일반 메서드라, 여기서 고정해 두지 않으면 접근자 규칙이 바뀌었을 때
        // 컴파일도 테스트도 통과한 채로 화면에서만 조용히 버튼이 되살아난다.
        var parser = new SpelExpressionParser();
        var ctx = new StandardEvaluationContext(turn("S"));

        assertThat(parser.parseExpression("proposable").getValue(ctx)).isEqualTo(false);
        assertThat(parser.parseExpression("submissionBlockedMessageKey").getValue(ctx))
                .isEqualTo("feedback.like.disabled.s");
    }
}
