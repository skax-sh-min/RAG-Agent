package com.example.ragagent.model;

import java.util.List;

/**
 * Backend-agnostic model for the {@code /settings} page. Built by
 * {@code SettingsService.buildView()} from the effective {@code AppProperties} values (overrides
 * already applied), the registered LLM providers, and the circuit-breaker state.
 */
public record SettingsView(
        List<ProviderRow> providers,
        String defaultRoutingMode,
        String temperature,
        String maxTokens,
        String embeddingModel,
        String embeddingBaseUrl,
        String embeddingDimensions,
        String vectorStoreType,
        List<SettingGroup> groups
) {

    /**
     * One registered chat/vision LLM provider. API keys are never included.
     *
     * @param baseUrl    the configured endpoint address (not a secret — safe to display)
     * @param configured true when this provider passes {@code AppProperties.ProviderConfig#isEnabled()}
     *                   (LlmConfig's actual G1+G2 registration gate) — false means it was never wired
     *                   up as a live {@code LlmProvider} regardless of what its api-key/toggle look like
     * @param enabled    false when an operator has disabled this provider at runtime (§A) — routing skips
     *                   it until re-enabled or the app restarts (the toggle is in-memory/volatile)
     */
    public record ProviderRow(
            String name,
            String role,
            int priority,
            String model,
            String baseUrl,
            boolean configured,
            boolean blocked,
            String blockedUntil,
            boolean enabled
    ) {}

    /** A titled group of settings on the page (e.g. "검색 튜닝 (핫 수정)"). */
    public record SettingGroup(String id, String title, List<SettingItem> items) {}

    /**
     * A single setting row.
     *
     * @param key        override key ({@link com.example.ragagent.config.SettingsKeys}) when editable, else null
     * @param label      human-readable label
     * @param value      effective value as a string (override applied)
     * @param type       input type hint: {@code "number"} | {@code "bool"} | {@code "text"}
     * @param editable   true → hot-editable (renders an input); false → read-only
     * @param overridden true → a persisted override is currently active for this key
     * @param note       restart-required reason or other note (nullable)
     * @param min        numeric lower bound (nullable / number type only)
     * @param max        numeric upper bound (nullable / number type only)
     * @param step       numeric input step (nullable / number type only)
     */
    public record SettingItem(
            String key,
            String label,
            String value,
            String type,
            boolean editable,
            boolean overridden,
            String note,
            Double min,
            Double max,
            Double step
    ) {}
}
