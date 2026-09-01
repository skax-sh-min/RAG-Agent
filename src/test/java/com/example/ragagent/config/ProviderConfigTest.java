package com.example.ragagent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AppProperties.ProviderConfig#isEnabled()} — the single source of truth
 * for "will this provider actually be registered", shared by {@code LlmConfig.llmRouter()}'s
 * bean-construction filter and the {@code /llm-usage} status badge (OperationsController).
 */
class ProviderConfigTest {

    private static AppProperties.ProviderConfig config(String apiKey, String role, String baseUrl) {
        return new AppProperties.ProviderConfig("p", baseUrl, apiKey, "model", "TEXT", role, 0, true, null, null);
    }

    @Test
    @DisplayName("LOCAL role + blank api-key + non-blank base-url → enabled (api-key exempt)")
    void localRoleBlankApiKeyNonBlankBaseUrl_enabled() {
        assertThat(config("", "LOCAL", "http://localhost:1234/v1").isEnabled()).isTrue();
        assertThat(config(null, "local", "http://localhost:1234/v1").isEnabled()).isTrue(); // case-insensitive role
    }

    @Test
    @DisplayName("LOCAL role + blank base-url → disabled, regardless of api-key — the reported bug")
    void localRoleBlankBaseUrl_disabledEvenWithApiKeyPlaceholder() {
        // Mirrors application.properties' LOCAL_FAST_LLM_KEY/LOCAL_LLM_KEY_2 default of "no-key" —
        // a non-blank placeholder that must NOT make an unconfigured base-url look "enabled".
        assertThat(config("no-key", "LOCAL", "").isEnabled()).isFalse();
        assertThat(config("no-key", "LOCAL", null).isEnabled()).isFalse();
        assertThat(config("no-key", "LOCAL", "   ").isEnabled()).isFalse();
    }

    @Test
    @DisplayName("NORMAL/PREMIUM role + blank api-key → disabled even with a base-url (cloud needs a real key)")
    void cloudRoleBlankApiKey_disabled() {
        assertThat(config("", "NORMAL", "https://api.example.com").isEnabled()).isFalse();
        assertThat(config(null, "PREMIUM", "https://api.example.com").isEnabled()).isFalse();
    }

    @Test
    @DisplayName("NORMAL/PREMIUM role + api-key + base-url → enabled")
    void cloudRoleWithKeyAndBaseUrl_enabled() {
        assertThat(config("sk-real-key", "NORMAL", "https://api.example.com").isEnabled()).isTrue();
        assertThat(config("sk-real-key", "PREMIUM", "https://api.example.com").isEnabled()).isTrue();
    }

    @Test
    @DisplayName("role=null (신규 §6.20 이전 데이터 등) → LOCAL 취급 아님, cloud 규칙 적용")
    void nullRole_treatedAsNonLocal() {
        assertThat(config("", null, "https://api.example.com").isEnabled()).isFalse();
        assertThat(config("sk-real-key", null, "https://api.example.com").isEnabled()).isTrue();
    }
}
