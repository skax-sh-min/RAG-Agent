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
 * <p>LIKE의 유일한 소비자가 큐레이션 승격이라, {@code allowsCuration()}이 false인 모드에서는
 * {@code CuratedQaService.onLike()}가 {@code curated_qa} 행조차 만들지 않는다. 그런데 피드백 값은
 * 저장되므로, 버튼을 그대로 두면 눌린 상태로 남아 사용자는 공유 지식에 기여했다고 믿게 된다.
 * 그 거짓말을 없애는 것이 {@link MemoryRepository.Turn#curatable()}이고, 규칙의 출처는
 * {@link ResponseMode} 하나여야 한다({@code SourceRef.staleBadge()} 선례).
 */
class TurnCuratableTest {

    private static MemoryRepository.Turn turn(String responseMode) {
        return new MemoryRepository.Turn(1L, "질문", "답변", "2026-08-25T00:00:00Z", null,
                0, 0, 0, "local", 1, null, responseMode, null);
    }

    @Test
    @DisplayName("S·C 턴은 좋아요가 아무 일도 하지 않으므로 curatable=false")
    void summaryAndCreativeAreNotCuratable() {
        assertThat(turn("S").curatable()).isFalse();
        assertThat(turn("C").curatable()).isFalse();
        assertThat(turn("N").curatable()).isTrue();
    }

    @Test
    @DisplayName("판정의 출처는 ResponseMode 하나다 — 값 목록을 여기에 복제하지 않는다")
    void derivesFromTheEnumForEveryMode() {
        for (ResponseMode mode : ResponseMode.values()) {
            assertThat(turn(mode.name()).curatable())
                    .as("%s", mode).isEqualTo(mode.allowsCuration());
            assertThat(turn(mode.name()).curationBlockedMessageKey())
                    .as("%s", mode).isEqualTo(mode.curationBlockedMessageKey());
        }
    }

    @Test
    @DisplayName("사유 키는 모드마다 다르고, 큐레이션 가능한 모드에서는 null")
    void blockedReasonIsPerModeAndNullWhenAllowed() {
        // 불린 하나로 툴팁을 쓸 수 없는 이유 — S는 임베딩할 본문이 남지 않아서, C는 모델
        // 생성물이 다음 턴의 "문서"가 되는 것을 막기 위해서다. 같은 문구를 쓰면 둘 중 하나는
        // 거짓이 된다.
        assertThat(turn("S").curationBlockedMessageKey()).isEqualTo("feedback.like.disabled.s");
        assertThat(turn("C").curationBlockedMessageKey()).isEqualTo("feedback.like.disabled.c");
        assertThat(turn("N").curationBlockedMessageKey()).isNull();
    }

    @Test
    @DisplayName("레거시·미지 모드 값은 N으로 읽혀 좋아요가 계속 동작한다")
    void legacyAndUnknownModesStayCuratable() {
        // parse()의 관대함과 같은 방향 — 값을 못 읽었다는 이유로 기존 동작(좋아요 가능)을
        // 빼앗지 않는다. 컬럼이 nullable이라 이 경로가 실제로 존재한다.
        for (String legacy : new String[]{null, "", "  ", "M", "L", "몰라"}) {
            assertThat(turn(legacy).curatable()).as("%s", legacy).isTrue();
            assertThat(turn(legacy).curationBlockedMessageKey()).as("%s", legacy).isNull();
        }
    }

    @Test
    @DisplayName("템플릿이 쓰는 SpEL 프로퍼티 표기로 접근된다")
    void accessibleAsSpelProperty() {
        // chat.html이 ${turn.curatable}/${turn.curationBlockedMessageKey}로 읽는다. 둘 다 레코드
        // 컴포넌트가 아닌 일반 메서드라, 여기서 고정해 두지 않으면 접근자 규칙이 바뀌었을 때
        // 컴파일도 테스트도 통과한 채로 화면에서만 조용히 버튼이 되살아난다.
        var parser = new SpelExpressionParser();
        var ctx = new StandardEvaluationContext(turn("C"));

        assertThat(parser.parseExpression("curatable").getValue(ctx)).isEqualTo(false);
        assertThat(parser.parseExpression("curationBlockedMessageKey").getValue(ctx))
                .isEqualTo("feedback.like.disabled.c");
    }
}
