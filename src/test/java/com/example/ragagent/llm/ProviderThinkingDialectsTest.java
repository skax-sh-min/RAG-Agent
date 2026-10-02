package com.example.ragagent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderThinkingDialectsTest {

    @Test
    @DisplayName("기록할 때 auto 를 풀어 둔다 — 설정값과 실제 값을 함께 남긴다(화면이 'auto → template-kwargs' 를 보이게)")
    void recordsConfiguredAndResolved() {
        ProviderThinkingDialects d = new ProviderThinkingDialects();
        d.record("local", ThinkingDialect.AUTO, true);
        d.record("gemini", null, false);

        assertThat(d.find("local")).get().extracting(ProviderThinkingDialects.Entry::configured,
                ProviderThinkingDialects.Entry::resolved)
                .containsExactly(ThinkingDialect.AUTO, ThinkingDialect.TEMPLATE_KWARGS);
        assertThat(d.dialectOf("gemini")).isEqualTo(ThinkingDialect.NONE);
    }

    @Test
    @DisplayName("모르는 프로바이더·null 이름은 none — 표준 밖 필드를 모르는 서버에 보내 차단당하는 것보다 낫다")
    void unknownProviderIsNone() {
        ProviderThinkingDialects d = new ProviderThinkingDialects();
        assertThat(d.dialectOf("who")).isEqualTo(ThinkingDialect.NONE);
        assertThat(d.dialectOf(null)).isEqualTo(ThinkingDialect.NONE);
        assertThat(d.wireFor("who", ThinkingLevel.OFF)).isSameAs(ThinkingWire.NOTHING);
    }

    @Test
    @DisplayName("거부한 필드는 그 프로바이더에서만 빠진다 — 처음 기억할 때만 true(경고는 한 번)")
    void rejectionIsPerProvider() {
        ProviderThinkingDialects d = new ProviderThinkingDialects();
        d.record("local", ThinkingDialect.AUTO, true);
        d.record("local-2", ThinkingDialect.AUTO, true);

        assertThat(d.markRejected("local", ThinkingDialect.TEMPLATE_KWARGS_FIELD)).isTrue();
        assertThat(d.markRejected("local", ThinkingDialect.TEMPLATE_KWARGS_FIELD)).isFalse();

        assertThat(d.wireFor("local", ThinkingLevel.OFF)).isSameAs(ThinkingWire.NOTHING);
        assertThat(d.wireFor("local-2", ThinkingLevel.OFF).sent()).isEqualTo(ThinkingWire.Sent.OFF);
        assertThat(d.rejectedFields("local-2")).isEmpty();
    }
}
