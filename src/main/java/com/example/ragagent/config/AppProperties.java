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
        Integer sseTimeoutSeconds,
        LlmConfig llm,
        IndexingConfig indexing,
        ImageDescriptionProperties imageDescription,
        EmbeddingConfig embedding
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
            int priority,
            Boolean stream
    ) {}

    public record IndexingConfig(
            int maxConcurrentFiles,
            int maxConcurrentLlmCalls,
            int keywordTimeoutSeconds
    ) {}

    public record EmbeddingConfig(
            String baseUrl,
            String apiKey,
            String model,
            Integer dimensions
    ) {}

    public record ImageDescriptionProperties(
            String mode,
            boolean enabled,
            boolean ocrEnabled,
            String tessdataPath,
            int minImageBytes,
            boolean lazy,
            boolean classifyType,
            boolean docxEmfConvert,
            boolean docxWmfConvert
    ) {}

    public ImageDescriptionProperties imageDescriptionSafe() {
        if (imageDescription == null)
            return new ImageDescriptionProperties("strip", false, false, null, 1_000, true, false, false, false);
        return imageDescription;
    }

    public long sseTimeoutMs() {
        return (sseTimeoutSeconds != null && sseTimeoutSeconds > 0)
                ? sseTimeoutSeconds * 1000L : 300_000L;
    }

    public IndexingConfig indexingSafe() {
        if (indexing == null) return new IndexingConfig(4, 8, 30);
        int files   = indexing.maxConcurrentFiles() > 0    ? indexing.maxConcurrentFiles()    : 4;
        int llm     = indexing.maxConcurrentLlmCalls() > 0 ? indexing.maxConcurrentLlmCalls() : 8;
        int timeout = indexing.keywordTimeoutSeconds() > 0 ? indexing.keywordTimeoutSeconds() : 30;
        return new IndexingConfig(files, llm, timeout);
    }

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
