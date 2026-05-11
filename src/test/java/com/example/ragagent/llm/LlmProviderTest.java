package com.example.ragagent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA — LlmProvider record
 *
 * Verifies the supports(TaskType) matrix and hasValidApiKey() contract.
 * These determine which providers LlmRouter considers eligible per request.
 */
class LlmProviderTest {

    private LlmProvider provider(TaskType type, String apiKey) {
        return new LlmProvider("p1", type, ProviderRole.NORMAL, 1, apiKey, null);
    }

    @Test
    @DisplayName("LIGHT_TEXT 프로바이더는 LIGHT_TEXT 만 지원")
    void lightTextSupportsOnlyLightText() {
        LlmProvider p = provider(TaskType.LIGHT_TEXT, "k");
        assertThat(p.supports(TaskType.LIGHT_TEXT)).isTrue();
        assertThat(p.supports(TaskType.TEXT)).isFalse();
        assertThat(p.supports(TaskType.VISION)).isFalse();
    }

    @Test
    @DisplayName("TEXT 프로바이더는 TEXT 만 지원")
    void textSupportsOnlyText() {
        LlmProvider p = provider(TaskType.TEXT, "k");
        assertThat(p.supports(TaskType.TEXT)).isTrue();
        assertThat(p.supports(TaskType.LIGHT_TEXT)).isFalse();
        assertThat(p.supports(TaskType.VISION)).isFalse();
    }

    @Test
    @DisplayName("VISION 프로바이더는 VISION 만 지원")
    void visionSupportsOnlyVision() {
        LlmProvider p = provider(TaskType.VISION, "k");
        assertThat(p.supports(TaskType.VISION)).isTrue();
        assertThat(p.supports(TaskType.TEXT)).isFalse();
    }

    @Test
    @DisplayName("LIGHT_BOTH 는 LIGHT_TEXT + VISION 지원, TEXT 거부")
    void lightBothSupportsLightTextAndVision() {
        LlmProvider p = provider(TaskType.LIGHT_BOTH, "k");
        assertThat(p.supports(TaskType.LIGHT_TEXT)).isTrue();
        assertThat(p.supports(TaskType.VISION)).isTrue();
        assertThat(p.supports(TaskType.TEXT)).isFalse();
    }

    @Test
    @DisplayName("BOTH 는 모든 TaskType 지원")
    void bothSupportsAll() {
        LlmProvider p = provider(TaskType.BOTH, "k");
        for (TaskType t : TaskType.values()) {
            assertThat(p.supports(t)).as("BOTH should support %s", t).isTrue();
        }
    }

    @Test
    @DisplayName("hasValidApiKey — null/blank 모두 false, 그 외 true")
    void hasValidApiKey() {
        assertThat(provider(TaskType.TEXT, null).hasValidApiKey()).isFalse();
        assertThat(provider(TaskType.TEXT, "").hasValidApiKey()).isFalse();
        assertThat(provider(TaskType.TEXT, "  ").hasValidApiKey()).isFalse();
        assertThat(provider(TaskType.TEXT, "sk-abc").hasValidApiKey()).isTrue();
    }
}
