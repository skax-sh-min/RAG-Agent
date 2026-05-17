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
        ChromaHttpConfig chroma,
        ImageDescriptionProperties imageDescription,
        EmbeddingConfig embedding,
        RateLimitConfig rateLimit
) {
    public record LlmConfig(
            List<ProviderConfig> providers,
            int circuitBreakerMinutes,
            int connectTimeoutSeconds,
            int readTimeoutSeconds,
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
            Integer dimensions,
            Integer connectTimeoutSeconds,
            Integer readTimeoutSeconds
    ) {}

    public record ChromaHttpConfig(
            int connectTimeoutSeconds,
            int readTimeoutSeconds
    ) {}

    public record RateLimitConfig(
            boolean enabled,
            int chatPerMinute,
            int uploadPerMinute,
            int syncPerMinute,
            int imagePerMinute,
            int defaultPerMinute
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

    public ChromaHttpConfig chromaSafe() {
        if (chroma == null) return new ChromaHttpConfig(5, 60);
        int connect = chroma.connectTimeoutSeconds() > 0 ? chroma.connectTimeoutSeconds() : 5;
        int read = chroma.readTimeoutSeconds() > 0 ? chroma.readTimeoutSeconds() : 60;
        return new ChromaHttpConfig(connect, read);
    }

    public EmbeddingConfig embeddingSafe() {
        if (embedding == null) return new EmbeddingConfig(null, null, null, null, 10, 120);
        int connect = (embedding.connectTimeoutSeconds() != null && embedding.connectTimeoutSeconds() > 0)
                ? embedding.connectTimeoutSeconds() : 10;
        int read = (embedding.readTimeoutSeconds() != null && embedding.readTimeoutSeconds() > 0)
                ? embedding.readTimeoutSeconds() : 120;
        return new EmbeddingConfig(
                embedding.baseUrl(),
                embedding.apiKey(),
                embedding.model(),
                embedding.dimensions(),
                connect,
                read
        );
    }

    public RateLimitConfig rateLimitSafe() {
        if (rateLimit == null) return new RateLimitConfig(false, 60, 10, 2, 300, 120);
        return rateLimit;
    }

    /** Null-safe accessor — returns an empty LlmConfig when app.llm is not configured. */
    public LlmConfig llmSafe() {
        if (llm == null) return new LlmConfig(List.of(), 2, 10, 180, "COST_FIRST", 0.6);
        List<ProviderConfig> providers = llm.providers() != null ? llm.providers() : List.of();
        int minutes = llm.circuitBreakerMinutes() > 0 ? llm.circuitBreakerMinutes() : 2;
                int connectTimeout = llm.connectTimeoutSeconds() > 0 ? llm.connectTimeoutSeconds() : 10;
                int readTimeout = llm.readTimeoutSeconds() > 0 ? llm.readTimeoutSeconds() : 180;
        String mode = llm.defaultRoutingMode() != null ? llm.defaultRoutingMode() : "COST_FIRST";
        double threshold = llm.progressiveThreshold() > 0 ? llm.progressiveThreshold() : 0.6;
                return new LlmConfig(providers, minutes, connectTimeout, readTimeout, mode, threshold);
    }
}
