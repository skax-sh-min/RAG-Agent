package com.example.ragagent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ThinkingWireTest {

    private static final String KWARGS = ThinkingDialect.TEMPLATE_KWARGS_FIELD;

    @Test
    @DisplayName("필드 이름 — 본문 최상위 이름 단위다(거부 판정·기억의 단위)")
    void fields() {
        assertThat(ThinkingDialect.TEMPLATE_KWARGS.wire(ThinkingLevel.OFF).fields()).containsExactly(KWARGS);
        assertThat(ThinkingDialect.OPENAI_EFFORT.wire(ThinkingLevel.LOW).fields())
                .containsExactly(ThinkingWire.REASONING_EFFORT_FIELD);
        assertThat(ThinkingWire.NOTHING.fields()).isEmpty();
    }

    @Test
    @DisplayName("거부된 필드를 빼면 남는 게 없을 때 '보내지 않음'이 된다")
    void withoutRejectedFieldBecomesNothing() {
        ThinkingWire off = ThinkingDialect.TEMPLATE_KWARGS.wire(ThinkingLevel.OFF);
        assertThat(off.without(Set.of(KWARGS))).isSameAs(ThinkingWire.NOTHING);
        assertThat(off.without(Set.of(ThinkingWire.REASONING_EFFORT_FIELD))).as("관계없는 거부는 영향 없음").isEqualTo(off);
        assertThat(off.without(Set.of())).isSameAs(off);
        assertThat(ThinkingDialect.OPENAI_EFFORT.wire(ThinkingLevel.HIGH)
                .without(Set.of(ThinkingWire.REASONING_EFFORT_FIELD))).isSameAs(ThinkingWire.NOTHING);
    }

    @Test
    @DisplayName("로그용 설명 — 실은 값 그대로, 아무것도 없으면 '보내지 않음'")
    void describe() {
        assertThat(ThinkingDialect.TEMPLATE_KWARGS.wire(ThinkingLevel.OFF).describe())
                .isEqualTo("chat_template_kwargs={enable_thinking=false}");
        assertThat(ThinkingDialect.OPENAI_EFFORT.wire(ThinkingLevel.MEDIUM).describe())
                .isEqualTo("reasoning_effort=medium");
        assertThat(ThinkingWire.NOTHING.describe()).isEqualTo("보내지 않음");
    }

    @Test
    @DisplayName("본문 맵은 복사해 고정한다 — 밖에서 바꿔도 실을 값이 흔들리지 않는다")
    void extraBodyIsImmutable() {
        var source = new java.util.HashMap<String, Object>(Map.of(KWARGS, Map.of("enable_thinking", true)));
        ThinkingWire wire = new ThinkingWire(source, null, ThinkingWire.Sent.ON);
        source.clear();
        assertThat(wire.extraBody()).containsKey(KWARGS);
    }
}
