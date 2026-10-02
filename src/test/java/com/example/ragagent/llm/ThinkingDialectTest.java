package com.example.ragagent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 수준 → 필드 변환표 전수(PLAN §6.29 ③). 화면 표시(5단계)와 실제 전송이 이 한 함수를 같이 쓴다. */
class ThinkingDialectTest {

    private static final String KWARGS = ThinkingDialect.TEMPLATE_KWARGS_FIELD;

    @Test
    @DisplayName("auto 는 현행 규칙이다 — LOCAL 이면 template-kwargs, 아니면 none. 명시한 값은 그대로")
    void autoResolvesToTheOldRule() {
        assertThat(ThinkingDialect.resolve(ThinkingDialect.AUTO, true)).isEqualTo(ThinkingDialect.TEMPLATE_KWARGS);
        assertThat(ThinkingDialect.resolve(ThinkingDialect.AUTO, false)).isEqualTo(ThinkingDialect.NONE);
        assertThat(ThinkingDialect.resolve(null, true)).isEqualTo(ThinkingDialect.TEMPLATE_KWARGS);
        assertThat(ThinkingDialect.resolve(ThinkingDialect.OPENAI_EFFORT, true)).isEqualTo(ThinkingDialect.OPENAI_EFFORT);
        assertThat(ThinkingDialect.resolve(ThinkingDialect.NONE, true)).as("LOCAL 이어도 명시한 none 이 이긴다")
                .isEqualTo(ThinkingDialect.NONE);
    }

    @Test
    @DisplayName("설정 값은 하이픈·대소문자를 가리지 않고, 모르는 값은 비어 있다")
    void parsesConfigValues() {
        assertThat(ThinkingDialect.parse("template-kwargs")).contains(ThinkingDialect.TEMPLATE_KWARGS);
        assertThat(ThinkingDialect.parse(" OPENAI_EFFORT ")).contains(ThinkingDialect.OPENAI_EFFORT);
        assertThat(ThinkingDialect.parse("auto")).contains(ThinkingDialect.AUTO);
        assertThat(ThinkingDialect.parse("thinking")).isEmpty();
        assertThat(ThinkingDialect.parse(" ")).isEmpty();
        assertThat(ThinkingDialect.TEMPLATE_KWARGS_EFFORT.value()).isEqualTo("template-kwargs-effort");
    }

    @Test
    @DisplayName("template-kwargs — 끔만 false, 낮게·중간·높게는 같은 true 로 접힌다")
    void templateKwargs() {
        ThinkingWire off = ThinkingDialect.TEMPLATE_KWARGS.wire(ThinkingLevel.OFF);
        assertThat(off.extraBody()).isEqualTo(Map.of(KWARGS, Map.of("enable_thinking", false)));
        assertThat(off.sent()).isEqualTo(ThinkingWire.Sent.OFF);

        ThinkingWire low = ThinkingDialect.TEMPLATE_KWARGS.wire(ThinkingLevel.LOW);
        assertThat(low.extraBody()).isEqualTo(Map.of(KWARGS, Map.of("enable_thinking", true)));
        assertThat(low.sent()).isEqualTo(ThinkingWire.Sent.ON);
        assertThat(ThinkingDialect.TEMPLATE_KWARGS.wire(ThinkingLevel.MEDIUM)).isEqualTo(low);
        assertThat(ThinkingDialect.TEMPLATE_KWARGS.wire(ThinkingLevel.HIGH)).isEqualTo(low);
    }

    @Test
    @DisplayName("template-kwargs-effort(gpt-oss) — 끌 수 없어 끔이 low 로 접히고, 그때도 생각한다")
    void templateKwargsEffort() {
        assertThat(ThinkingDialect.TEMPLATE_KWARGS_EFFORT.wire(ThinkingLevel.OFF))
                .isEqualTo(new ThinkingWire(Map.of(KWARGS, Map.of("reasoning_effort", "low")), null, ThinkingWire.Sent.ON));
        assertThat(ThinkingDialect.TEMPLATE_KWARGS_EFFORT.wire(ThinkingLevel.MEDIUM).extraBody())
                .isEqualTo(Map.of(KWARGS, Map.of("reasoning_effort", "medium")));
        assertThat(ThinkingDialect.TEMPLATE_KWARGS_EFFORT.wire(ThinkingLevel.HIGH).extraBody())
                .isEqualTo(Map.of(KWARGS, Map.of("reasoning_effort", "high")));
    }

    @Test
    @DisplayName("openai-effort — 표준 필드 하나. 끔은 none")
    void openAiEffort() {
        assertThat(ThinkingDialect.OPENAI_EFFORT.wire(ThinkingLevel.OFF))
                .isEqualTo(new ThinkingWire(Map.of(), "none", ThinkingWire.Sent.OFF));
        assertThat(ThinkingDialect.OPENAI_EFFORT.wire(ThinkingLevel.LOW).reasoningEffort()).isEqualTo("low");
        assertThat(ThinkingDialect.OPENAI_EFFORT.wire(ThinkingLevel.MEDIUM).reasoningEffort()).isEqualTo("medium");
        assertThat(ThinkingDialect.OPENAI_EFFORT.wire(ThinkingLevel.HIGH).reasoningEffort()).isEqualTo("high");
        assertThat(ThinkingDialect.OPENAI_EFFORT.wire(ThinkingLevel.HIGH).extraBody()).isEmpty();
    }

    @Test
    @DisplayName("none — 어느 수준이든 아무것도 싣지 않는다(서버가 정한다)")
    void noneSendsNothing() {
        for (ThinkingLevel level : ThinkingLevel.values()) {
            assertThat(ThinkingDialect.NONE.wire(level)).isSameAs(ThinkingWire.NOTHING);
        }
    }

    @Test
    @DisplayName("auto 를 풀지 않고 변환하면 실패한다 — 조용히 아무것도 안 싣는 쪽으로 떨어지면 LOCAL 의 생각 끄기가 사라진다")
    void autoMustBeResolvedFirst() {
        assertThatThrownBy(() -> ThinkingDialect.AUTO.wire(ThinkingLevel.OFF)).isInstanceOf(IllegalStateException.class);
    }
}
