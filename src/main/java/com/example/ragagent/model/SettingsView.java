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
        String embeddingDimensions,
        String vectorStoreType,
        List<SettingGroup> groups
) {

    /** One registered chat/vision LLM provider. API keys are never included (only a configured flag). */
    public record ProviderRow(
            String name,
            String role,
            int priority,
            String model,
            boolean configured,
            boolean blocked,
            String blockedUntil
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
