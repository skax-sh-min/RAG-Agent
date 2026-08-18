package com.example.ragagent.model;

import org.junit.jupiter.api.DisplayName;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SourceRefStaleBadgeTest {

    private static SourceRef ref(Double answerShare, String stale) {
        return new SourceRef("doc.pptx | p.160", "preview", "c1", "d1", "160",
                0.56, null, null, answerShare, stale);
    }

    @Test
    @DisplayName("수정된 출처는 응답 지분이 있을 때만 배지가 붙는다")
    void modified_onlyWhenContributing() {
        assertThat(ref(0.31, SourceRef.STALE_MODIFIED).staleBadge()).isEqualTo("수정됨");
        assertThat(ref(0.0, SourceRef.STALE_MODIFIED).staleBadge()).isNull();
        assertThat(ref(null, SourceRef.STALE_MODIFIED).staleBadge()).isNull();
    }

    @Test
    @DisplayName("삭제된 출처는 응답 지분과 무관하게 항상 배지가 붙는다")
    void deleted_alwaysShown() {
        assertThat(ref(0.31, SourceRef.STALE_DELETED).staleBadge()).isEqualTo("삭제됨");
        assertThat(ref(0.0, SourceRef.STALE_DELETED).staleBadge()).isEqualTo("삭제됨");
        assertThat(ref(null, SourceRef.STALE_DELETED).staleBadge()).isEqualTo("삭제됨");
    }

    @Test
    @DisplayName("변경되지 않은 출처에는 배지가 없다")
    void active_noBadge() {
        assertThat(ref(0.31, null).staleBadge()).isNull();
        assertThat(ref(0.31, "active").staleBadge()).isNull();
    }

    @Test
    @DisplayName("기존 생성자로 만든 출처는 stale 상태가 없다")
    void legacyConstructors_haveNoStaleStatus() {
        assertThat(new SourceRef("l", "p", "c1", "d1", "1").staleStatus()).isNull();
        assertThat(new SourceRef("l", "p", "c1", "d1", "1", 0.5, 0.2, "vec:1").staleBadge()).isNull();
    }

    @Test
    @DisplayName("템플릿이 쓰는 SpEL 프로퍼티 표기로 접근된다")
    void accessibleAsSpelProperty() {
        // chat.html은 ${src.staleBadge}/${src.staleStatus}로 읽는다. staleBadge()는 레코드
        // 컴포넌트가 아니라 일반 메서드라, 여기서 고정해 두지 않으면 접근자 규칙이 바뀌었을 때
        // 컴파일도 테스트도 통과한 채로 대화 화면에서만 조용히 배지가 사라진다.
        var parser = new SpelExpressionParser();
        var ctx = new StandardEvaluationContext(ref(0.31, SourceRef.STALE_MODIFIED));

        assertThat(parser.parseExpression("staleBadge").getValue(ctx)).isEqualTo("수정됨");
        assertThat(parser.parseExpression("staleStatus").getValue(ctx)).isEqualTo("modified");
    }
}
