package com.example.ragagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String dataDir,
        int maxRetryCount,
        int chunkSize,
        int chunkOverlap,
    int minChunkSize,
        int searchTopK,
        double searchSimilarityThreshold,
        boolean searchMultiqueryEnabled,
        int searchMultiqueryMinLength,
        boolean searchHybridEnabled,
        boolean searchRetryEscalate,
        boolean searchRerankEnabled,
        int searchCandidateMultiplier,
        Integer sseTimeoutSeconds,
        LlmConfig llm,
        IndexingConfig indexing,
        ChromaHttpConfig chroma,
        ImageDescriptionProperties imageDescription,
        EmbeddingConfig embedding,
        RateLimitConfig rateLimit,
        AuditConfig audit,
        AuthConfig auth,
        VectorStoreConfig vectorstore,
        Integer searchTagCandidateMultiplier   // 태그 선택 시 후보확대 배수 (기본 2)
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

    public record AuditConfig(
            boolean enabled,
            String maxFileSize,      // e.g. "10MB"  — Logback SizeAndTimeBasedRollingPolicy
            int maxHistoryDays,      // 보관 일수, 이 일수가 지난 파일 자동 삭제
            String totalSizeCap      // audit 디렉터리 전체 상한, e.g. "100MB"
    ) {}

    public record AuthConfig(
            boolean enabled          // false → no-auth mode (guest/admin auto-login)
    ) {}

    /** 벡터 스토어 백엔드 선택. {@code type}: chroma (기본값) | sqlite-vec. */
    public record VectorStoreConfig(String type) {}

    public record ImageDescriptionProperties(
            String mode,
            boolean enabled,
            boolean ocrEnabled,
            String tessdataPath,
            int minImageBytes,
            boolean classifyType,
            boolean docxEmfConvert,
            boolean docxWmfConvert
    ) {}

    public ImageDescriptionProperties imageDescriptionSafe() {
        if (imageDescription == null)
            return new ImageDescriptionProperties("strip", false, false, null, 1_000, false, false, false);
        return imageDescription;
    }

    /**
     * Similarity threshold for vector search, clamped to [0,1].
     * 0.0 = accept all (Spring AI default).
     */
    public double searchSimilarityThresholdSafe() {
        if (searchSimilarityThreshold <= 0.0) return 0.0;
        return Math.min(searchSimilarityThreshold, 1.0);
    }

    /** Query length (chars) at/above which multi-query expansion runs. Clamped to >= 0 (0 = no length gate). */
    public int searchMultiqueryMinLengthSafe() {
        return Math.max(0, searchMultiqueryMinLength);
    }

    /** Minimum chunk length used by post-merge compaction. Falls back to chunkOverlap for backward compatibility. */
    public int minChunkSizeSafe() {
        if (minChunkSize > 0) return minChunkSize;
        return Math.max(1, chunkOverlap);
    }

    /** Candidate pool multiplier for reranking. Clamped to >= 1 to avoid empty pools. */
    public int searchCandidateMultiplierSafe() {
        return Math.max(1, searchCandidateMultiplier);
    }

    /** Candidate-expansion multiplier applied when tags are selected. Defaults to 2. */
    public int searchTagCandidateMultiplierSafe() {
        return (searchTagCandidateMultiplier == null || searchTagCandidateMultiplier < 1)
                ? 2 : searchTagCandidateMultiplier;
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

    public AuditConfig auditSafe() {
        if (audit == null) return new AuditConfig(true, "10MB", 7, "100MB");
        String size = (audit.maxFileSize() != null && !audit.maxFileSize().isBlank()) ? audit.maxFileSize() : "10MB";
        int days = audit.maxHistoryDays() > 0 ? audit.maxHistoryDays() : 7;
        String cap = (audit.totalSizeCap() != null && !audit.totalSizeCap().isBlank()) ? audit.totalSizeCap() : "100MB";
        return new AuditConfig(audit.enabled(), size, days, cap);
    }

    public AuthConfig authSafe() {
        if (auth == null) return new AuthConfig(true);
        return auth;
    }

    /** Vector store backend, defaulting to {@code chroma}. (Bean wiring uses raw @ConditionalOnProperty.) */
    public VectorStoreConfig vectorStoreSafe() {
        if (vectorstore == null || vectorstore.type() == null || vectorstore.type().isBlank())
            return new VectorStoreConfig("chroma");
        return new VectorStoreConfig(vectorstore.type().trim());
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
