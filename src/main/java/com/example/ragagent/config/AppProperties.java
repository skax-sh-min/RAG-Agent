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
        MemoryConfig memory,
        SummaryConfig summary,
        Integer searchTagCandidateMultiplier,  // 태그 선택 시 후보확대 배수 (기본 2)
        Integer sseIdleTimeoutSeconds,          // SSE 무활동(토큰/노드 이벤트 없음) 감시 타임아웃 (기본 120초)
        Double searchRrfKeywordWeight,          // 가중 RRF — 키워드(BM25) 축 가중치 (기본 1.0, 벡터축은 그룹 정규화되어 자동으로 1.0과 동등 비중)
        Integer searchRrfK,                     // 가중 RRF — RRF 상수 k (기본 60, 원논문 표준값)
        Boolean searchQueryEmbedCacheEnabled,   // 쿼리 임베딩 캐시 on/off (기본 true)
        Integer searchQueryEmbedCacheMaxSize,   // 쿼리 임베딩 캐시 최대 엔트리 수 (기본 500)
        Integer searchQueryEmbedCacheTtlSeconds, // 쿼리 임베딩 캐시 TTL 초 (기본 600 = 10분)
        PptxShapeExtractionConfig pptxImage     // PPTX 그리기 도구 도형 래스터라이즈/클러스터링 튜닝
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
            Integer readTimeoutSeconds,
            Boolean usageFallbackEnabled,
            Integer maxChunkChars   // hard ceiling per chunk to fit the embedding server batch; 0/null = disabled
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
            boolean enabled,         // false → no-auth mode (guest/admin auto-login)
            boolean managementOnly   // §6.17 B안 — only meaningful when enabled=false; authSafe() normalizes
                                     // this to false whenever enabled=true, so it's the only place that rule
                                     // needs to be known. true → /admin/** + document-write UI require a real
                                     // login (NoAuthAutoLoginFilter/SecurityConfig), everything else stays
                                     // guest-auto-authenticated exactly like plain no-auth mode.
    ) {}

    /** 벡터 스토어 백엔드 선택. {@code type}: chroma (기본값) | sqlite-vec. */
    public record VectorStoreConfig(String type) {}

    /** 대화 기록 조회(폴백 경로) 튜닝. {@code fetchLimitTurns}: 문자 예산 적용 전 조회할 최근 turn 상한. */
    public record MemoryConfig(Integer fetchLimitTurns) {}

    /**
     * 대화 요약 캐시(§6.9/§6.10 {@code ConversationSummarizerService}) 튜닝.
     * 모두 미설정 시 아래 {@link #summarySafe()} 기본값(기존 하드코딩과 동일)으로 동작한다.
     */
    public record SummaryConfig(
            Integer maxCachedThreads,     // LRU 캐시가 유지하는 최대 thread 수
            Integer maxSummaryChars,      // 요약 문자열 상한 (초과 시 잘림)
            Integer recentRawTurns,       // 요약 뒤에 원문 그대로 덧붙일 최근 turn 수
            Integer precomputeTtlSeconds  // 동일 thread 재-precompute 억제 창(초)
    ) {}

    /**
     * PPTX 그리기 도구 도형(그룹/커넥터/텍스트 없는 자유형 도형) 래스터라이즈 튜닝 —
     * {@link com.example.ragagent.service.PptxImageExtractor} 참고.
     */
    public record PptxShapeExtractionConfig(
            Double minShapeDimensionPt,       // 가로/세로 중 큰 쪽이 이 값 미만이면 아이콘/구분선으로 보고 제외 (기본 30)
            Double clusterProximityPaddingPt, // 클러스터링 근접 판정 시 바운딩박스에 적용할 바깥쪽 패딩 (기본 15)
            Boolean mergeAnnotatedPictures    // true(기본): 사진도 근접 클러스터링에 참여해 겹친 주석 도형과 합성 / false: PPTX에서 실제 그룹(XSLFGroupShape)으로 묶인 경우만 합성, 그 외 사진은 항상 원본 그대로 추출
    ) {}

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

    /** Weighted RRF — keyword (BM25) axis weight. Defaults to 1.0 (parity with the group-normalized vector axes). */
    public double searchRrfKeywordWeightSafe() {
        return (searchRrfKeywordWeight != null && searchRrfKeywordWeight > 0) ? searchRrfKeywordWeight : 1.0;
    }

    /** Weighted RRF — rank-fusion constant k. Defaults to 60 (the original RRF paper's value). */
    public int searchRrfKSafe() {
        return (searchRrfK != null && searchRrfK > 0) ? searchRrfK : 60;
    }

    /** Query embedding cache on/off. Defaults to enabled. */
    public boolean searchQueryEmbedCacheEnabledSafe() {
        return searchQueryEmbedCacheEnabled == null || searchQueryEmbedCacheEnabled;
    }

    /** Query embedding cache max entries. Defaults to 500. */
    public int searchQueryEmbedCacheMaxSizeSafe() {
        return (searchQueryEmbedCacheMaxSize != null && searchQueryEmbedCacheMaxSize > 0)
                ? searchQueryEmbedCacheMaxSize : 500;
    }

    /** Query embedding cache TTL (seconds, write-based expiry). Defaults to 600 (10 min). */
    public int searchQueryEmbedCacheTtlSecondsSafe() {
        return (searchQueryEmbedCacheTtlSeconds != null && searchQueryEmbedCacheTtlSeconds > 0)
                ? searchQueryEmbedCacheTtlSeconds : 600;
    }

    /**
     * Absolute hard ceiling for an SSE connection, regardless of activity — a backstop against
     * a pathological case (e.g. a generation that never stops producing tokens). The idle
     * timeout ({@link #sseIdleTimeoutMs()}) is what actually bounds a stuck-but-otherwise-healthy
     * long response, so this can stay generous.
     */
    public long sseTimeoutMs() {
        return (sseTimeoutSeconds != null && sseTimeoutSeconds > 0)
                ? sseTimeoutSeconds * 1000L : 3_600_000L;
    }

    /**
     * Max time with no forward-progress signal (node transition, token, or sources-ready event)
     * from the agent graph before the SSE stream is considered stuck and aborted. Resets on
     * every signal, so a slow-but-actively-generating local LLM response is never cut off.
     */
    public long sseIdleTimeoutMs() {
        return (sseIdleTimeoutSeconds != null && sseIdleTimeoutSeconds > 0)
                ? sseIdleTimeoutSeconds * 1000L : 120_000L;
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
        if (embedding == null) return new EmbeddingConfig(null, null, null, null, 10, 120, true, 0);
        int connect = (embedding.connectTimeoutSeconds() != null && embedding.connectTimeoutSeconds() > 0)
                ? embedding.connectTimeoutSeconds() : 10;
        int read = (embedding.readTimeoutSeconds() != null && embedding.readTimeoutSeconds() > 0)
                ? embedding.readTimeoutSeconds() : 120;
        boolean usageFallback = embedding.usageFallbackEnabled() == null || embedding.usageFallbackEnabled();
        // 0 = disabled (no hard cap); negative values are clamped to 0.
        int maxChunkChars = (embedding.maxChunkChars() != null && embedding.maxChunkChars() > 0)
                ? embedding.maxChunkChars() : 0;
        return new EmbeddingConfig(
                embedding.baseUrl(),
                embedding.apiKey(),
                embedding.model(),
                embedding.dimensions(),
                connect,
                read,
                usageFallback,
                maxChunkChars
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
        if (auth == null) return new AuthConfig(true, false);
        // managementOnly is only meaningful when auth is disabled — normalize here so every
        // downstream consumer (SecurityConfig, NoAuthAutoLoginFilter, GlobalModelAdvice, ...)
        // can trust authSafe().managementOnly() directly without re-deriving this rule.
        return new AuthConfig(auth.enabled(), !auth.enabled() && auth.managementOnly());
    }

    /** Vector store backend, defaulting to {@code chroma}. (Bean wiring uses raw @ConditionalOnProperty.) */
    public VectorStoreConfig vectorStoreSafe() {
        if (vectorstore == null || vectorstore.type() == null || vectorstore.type().isBlank())
            return new VectorStoreConfig("chroma");
        return new VectorStoreConfig(vectorstore.type().trim());
    }

    /** Conversation-history fetch limit (fallback path), defaulting to 50 turns. Clamped to >= 1. */
    public MemoryConfig memorySafe() {
        int limit = (memory != null && memory.fetchLimitTurns() != null && memory.fetchLimitTurns() > 0)
                ? memory.fetchLimitTurns() : 50;
        return new MemoryConfig(limit);
    }

    /**
     * Summary-cache tuning with the same defaults previously hardcoded in
     * {@code ConversationSummarizerService} (3 / 2000 / 2 / 15). Each field is clamped to >= 1.
     */
    public SummaryConfig summarySafe() {
        int cached  = pos(summary == null ? null : summary.maxCachedThreads(), 3);
        int chars   = pos(summary == null ? null : summary.maxSummaryChars(), 2_000);
        int recent  = pos(summary == null ? null : summary.recentRawTurns(), 2);
        int ttlSecs = pos(summary == null ? null : summary.precomputeTtlSeconds(), 15);
        return new SummaryConfig(cached, chars, recent, ttlSecs);
    }

    private static int pos(Integer value, int fallback) {
        return (value != null && value > 0) ? value : fallback;
    }

    /**
     * PPTX shape-rasterization tuning, defaulting to 30pt (min shape dimension) / 15pt (cluster
     * proximity padding) / true (merge annotated pictures). Only falls back on an unset (null)
     * field — an explicit 0 (padding) or false (mergeAnnotatedPictures) is honored (e.g. padding=0
     * to only bundle shapes that literally touch/overlap).
     */
    public PptxShapeExtractionConfig pptxImageSafe() {
        double minDim = (pptxImage != null && pptxImage.minShapeDimensionPt() != null && pptxImage.minShapeDimensionPt() >= 0)
                ? pptxImage.minShapeDimensionPt() : 30.0;
        double padding = (pptxImage != null && pptxImage.clusterProximityPaddingPt() != null && pptxImage.clusterProximityPaddingPt() >= 0)
                ? pptxImage.clusterProximityPaddingPt() : 15.0;
        boolean mergeAnnotatedPictures = (pptxImage != null && pptxImage.mergeAnnotatedPictures() != null)
                ? pptxImage.mergeAnnotatedPictures() : true;
        return new PptxShapeExtractionConfig(minDim, padding, mergeAnnotatedPictures);
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
