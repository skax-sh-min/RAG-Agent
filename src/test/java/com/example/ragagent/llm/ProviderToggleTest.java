package com.example.ragagent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** ProviderToggle: in-memory enable/disable state (§A). */
class ProviderToggleTest {

    @Test
    @DisplayName("기본값은 활성; setEnabled(false)로 비활성, setEnabled(true)로 복구")
    void enableDisableRoundTrip() {
        ProviderToggle t = new ProviderToggle();

        assertThat(t.isEnabled("p")).isTrue();
        assertThat(t.isDisabled("p")).isFalse();
        assertThat(t.disabledNames()).isEmpty();

        t.setEnabled("p", false);
        assertThat(t.isEnabled("p")).isFalse();
        assertThat(t.isDisabled("p")).isTrue();
        assertThat(t.disabledNames()).containsExactly("p");

        t.setEnabled("p", true);
        assertThat(t.isEnabled("p")).isTrue();
        assertThat(t.disabledNames()).isEmpty();
    }

    @Test
    @DisplayName("setEnabled는 멱등 — 같은 상태를 반복 설정해도 안전")
    void idempotent() {
        ProviderToggle t = new ProviderToggle();
        t.setEnabled("p", false);
        t.setEnabled("p", false);
        assertThat(t.isDisabled("p")).isTrue();
        assertThat(t.disabledNames()).containsExactly("p");

        t.setEnabled("p", true);
        t.setEnabled("p", true);
        assertThat(t.isEnabled("p")).isTrue();
    }
}
