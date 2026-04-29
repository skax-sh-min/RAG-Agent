package com.example.ragagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String dataDir,
        int maxRetryCount,
        int maxConversationChars,
        int chunkSize,
        int chunkOverlap,
        int searchTopK,
        LlmConfig llm
) {
    public record LlmConfig(
            List<ProviderConfig> providers,
            int circuitBreakerMinutes
    ) {}

    public record ProviderConfig(
            String name,
            String baseUrl,
            String apiKey,
            String model,
            String type,
            int priority
    ) {}

    /** Null-safe accessor — returns an empty LlmConfig when app.llm is not configured. */
    public LlmConfig llmSafe() {
        if (llm == null) return new LlmConfig(List.of(), 2);
        List<ProviderConfig> providers = llm.providers() != null ? llm.providers() : List.of();
        int minutes = llm.circuitBreakerMinutes() > 0 ? llm.circuitBreakerMinutes() : 2;
        return new LlmConfig(providers, minutes);
    }
}
