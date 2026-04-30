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
            int circuitBreakerMinutes,
            String defaultRoutingMode,
            double progressiveThreshold
    ) {}

    public record ProviderConfig(
            String name,
            String baseUrl,
            String apiKey,
            String model,
            String type,
            String role,
            int priority
    ) {}

    /** Null-safe accessor — returns an empty LlmConfig when app.llm is not configured. */
    public LlmConfig llmSafe() {
        if (llm == null) return new LlmConfig(List.of(), 2, "COST_FIRST", 0.6);
        List<ProviderConfig> providers = llm.providers() != null ? llm.providers() : List.of();
        int minutes = llm.circuitBreakerMinutes() > 0 ? llm.circuitBreakerMinutes() : 2;
        String mode = llm.defaultRoutingMode() != null ? llm.defaultRoutingMode() : "COST_FIRST";
        double threshold = llm.progressiveThreshold() > 0 ? llm.progressiveThreshold() : 0.6;
        return new LlmConfig(providers, minutes, mode, threshold);
    }
}
