package com.example.ragagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;

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
        PptxShapeExtractionConfig pptxImage,    // PPTX 그리기 도구 도형 래스터라이즈/클러스터링 튜닝
        String mdCorrectionDefaultCodeLanguage, // MD 교정 시 LLM이 미펜스 코드를 감쌀 때 언어 판단이 어려우면 붙일 기본 언어 (java/bash/sql, 기본 java)
        DocxShapeExtractionConfig docxImage     // DOCX 레거시 VML 도형 + 사진 합성 튜닝
) {
    public record LlmConfig(
            List<ProviderConfig> providers,
            int circuitBreakerMinutes,
            int connectTimeoutSeconds,
            int readTimeoutSeconds,
            String defaultRoutingMode,
            double progressiveThreshold,
            int defaultProviderConcurrency,  // per-provider concurrency gate default (matches the server's --parallel), fallback when a provider omits its own `concurrency`
            int permitWaitTimeoutSeconds,    // max wait for a concurrency slot on the query path before failing fast with 429 (default 20s, well under the 180s read-timeout)
            Double temperature,              // general/RAG temperature (app.llm.temperature / LLM_TEMPERATURE), default 0.0, clamp [0,2] — VIEW-ONLY (baked into provider defaultOptions at bean creation, restart to change)
            Double directTemperature,        // Direct(meta) answer temperature (app.llm.direct-temperature / DIRECT_LLM_TEMPERATURE), default 0.1, clamp [0,0.2] — HOT-editable (DirectAnswerService reads it per call, §6.18)
            Integer maxTokens                // LLM response cap (app.llm.max-tokens / LLM_MAX_TOKENS), default 6000, clamp >0 — VIEW-ONLY (baked at bean creation; streaming chat answers are uncapped by design, bounded by SSE timeouts)
    ) {}

    public record ProviderConfig(
            String name,
            String baseUrl,
            String apiKey,
            String model,
            String type,
            String role,
            int priority,
            Boolean stream,
            Integer concurrency  // this provider's own concurrency slots; null/<=0 falls back to LlmConfig.defaultProviderConcurrency
    ) {}

    public record IndexingConfig(
            int maxConcurrentFiles,
            int maxConcurrentLlmCalls,
            int keywordTimeoutSeconds,
            int keywordBatchSize   // §10.8.2 — chunks bundled into one keyword-extraction LLM call (1 = no batching)
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
            Boolean mergeAnnotatedPictures,   // true(기본): 사진 위/근처에 겹친 시드 도형을 사진과 하나로 합성(앵커 기반, rasterizeShapes와 독립) / false: 사진은 항상 원본 그대로 추출
            Boolean rasterizeShapes           // 느슨한(아무 앵커에도 안 겹친) 도형끼리의 근접 클러스터링 — true: 겹친 도형을 다이어그램 한 장으로 병합(구 기본 동작) / false(기본): 클러스터링 안 함(느슨한 단일 도형은 이미지로 안 뽑음). 그룹/SmartArt/표+도형 합성은 이 값과 무관하게 항상 유지
    ) {}

    /**
     * DOCX 레거시 VML 도형(v:rect/v:oval/v:roundrect/v:line) + 사진 합성 튜닝 —
     * {@link com.example.ragagent.service.DocxAnnotationShapeMerger} 참고. PPTX와 달리 POI의
     * WordprocessingML 모델에는 도형 좌표·렌더러가 없어 진짜 기하학적 겹침 판정이 불가능하므로,
     * "같은 문단에 사진과 도형이 함께 있으면 합성" 근사 방식만 지원한다.
     */
    public record DocxShapeExtractionConfig(
            Boolean mergeAnnotatedShapes // true(기본): 같은 문단의 VML 도형을 사진과 합성 / false: 항상 원본 사진만 추출
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

    // ── Runtime settings-override layer ───────────────────────────────────────
    //
    // A hot-editable value's xxxSafe() accessor consults this source FIRST (override → else the
    // bound property default), so a change saved on the /settings page reaches the next search
    // without a restart. AppProperties is an immutable @ConfigurationProperties record — it cannot
    // take the store as a constructor arg (Spring binds it) nor hold a non-component instance field,
    // so the source is a process-wide static bound by SettingsService at startup. When unbound
    // (null — unit tests, or before SettingsService initializes) every accessor behaves exactly as
    // before: the override lookup is a no-op. Only the keys in SettingsKeys are ever looked up here.

    /** Supplies a raw override string for a settings key (see {@link SettingsKeys}), or null when unset. */
    public interface OverrideSource {
        String get(String key);
    }

    private static volatile OverrideSource overrideSource;

    /** Bound once by {@code SettingsService} at startup; replaces any previously bound source. */
    public static void bindOverrides(OverrideSource source) {
        overrideSource = source;
    }

    /** Clears the bound source (used on shutdown and to isolate tests). */
    public static void unbindOverrides() {
        overrideSource = null;
    }

    private static String rawOverride(String key) {
        OverrideSource s = overrideSource;
        if (s == null) return null;
        String v = s.get(key);
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    private static Double overrideDouble(String key) {
        String v = rawOverride(key);
        if (v == null) return null;
        try { return Double.parseDouble(v); } catch (NumberFormatException e) { return null; }
    }

    private static Integer overrideInt(String key) {
        String v = rawOverride(key);
        if (v == null) return null;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return null; }
    }

    private static Boolean overrideBool(String key) {
        String v = rawOverride(key);
        if (v == null) return null;
        if (v.equalsIgnoreCase("true"))  return Boolean.TRUE;
        if (v.equalsIgnoreCase("false")) return Boolean.FALSE;
        return null;
    }

    /**
     * Similarity threshold for vector search, clamped to [0,1].
     * 0.0 = accept all (Spring AI default).
     */
    public double searchSimilarityThresholdSafe() {
        Double o = overrideDouble(SettingsKeys.SEARCH_SIMILARITY_THRESHOLD);
        double base = (o != null) ? o : searchSimilarityThreshold;
        if (base <= 0.0) return 0.0;
        return Math.min(base, 1.0);
    }

    /** Query length (chars) at/above which multi-query expansion runs. Clamped to >= 0 (0 = no length gate). */
    public int searchMultiqueryMinLengthSafe() {
        Integer o = overrideInt(SettingsKeys.SEARCH_MULTIQUERY_MIN_LENGTH);
        return Math.max(0, o != null ? o : searchMultiqueryMinLength);
    }

    /**
     * Retry-escalation toggle for candidate expansion on retry (hot-editable via /settings). Plain boolean
     * with an override hook — no clamping, so the accessor exists purely to route through the
     * override layer. {@code searchRetryEscalate()} raw getter must not be called elsewhere
     * (AppPropertiesSafeAccessorTest enforces this).
     */
    public boolean searchRetryEscalateSafe() {
        Boolean o = overrideBool(SettingsKeys.SEARCH_RETRY_ESCALATE);
        return o != null ? o : searchRetryEscalate;
    }

    /** Minimum chunk length used by post-merge compaction. Falls back to chunkOverlap for backward compatibility. */
    /** Chunk size (chars) used at indexing time. Hot-editable — {@code DocumentIndexer} re-reads it
     *  per index, so an override applies on the next indexing / ↺ re-index. Clamped to a sane floor. */
    public int chunkSizeSafe() {
        Integer o = overrideInt(SettingsKeys.CHUNK_SIZE);
        int v = (o != null) ? o : chunkSize;
        return v > 0 ? v : 800;
    }

    /** Chunk overlap (chars). Hot-editable at indexing time, same lifecycle as {@link #chunkSizeSafe()}. */
    public int chunkOverlapSafe() {
        Integer o = overrideInt(SettingsKeys.CHUNK_OVERLAP);
        int v = (o != null) ? o : chunkOverlap;
        return Math.max(0, v);
    }

    /** Minimum chunk size (chars); {@code <= 0} falls back to the (override-aware) overlap. Hot-editable. */
    public int minChunkSizeSafe() {
        Integer o = overrideInt(SettingsKeys.MIN_CHUNK_SIZE);
        int v = (o != null) ? o : minChunkSize;
        if (v > 0) return v;
        return Math.max(1, chunkOverlapSafe());
    }

    /** Search top-K (final result count / candidate base). Hot-editable — {@code RetrievalService}
     *  re-reads it per search. Clamped to {@code >= 1}. */
    public int searchTopKSafe() {
        Integer o = overrideInt(SettingsKeys.SEARCH_TOP_K);
        int v = (o != null) ? o : searchTopK;
        return v > 0 ? v : 7;
    }

    /** Multi-query expansion on/off. Hot-editable — {@code RetrievalService.shouldExpand()} re-reads it per search. */
    public boolean searchMultiqueryEnabledSafe() {
        Boolean o = overrideBool(SettingsKeys.SEARCH_MULTIQUERY_ENABLED);
        return o != null ? o : searchMultiqueryEnabled;
    }

    /** Hybrid (BM25 keyword axis) search on/off. Hot-editable — {@code RetrievalService} re-reads it per search. */
    public boolean searchHybridEnabledSafe() {
        Boolean o = overrideBool(SettingsKeys.SEARCH_HYBRID_ENABLED);
        return o != null ? o : searchHybridEnabled;
    }

    /** Candidate pool multiplier for reranking. Clamped to >= 1 to avoid empty pools. */
    public int searchCandidateMultiplierSafe() {
        Integer o = overrideInt(SettingsKeys.SEARCH_CANDIDATE_MULTIPLIER);
        return Math.max(1, o != null ? o : searchCandidateMultiplier);
    }

    /** Candidate-expansion multiplier applied when tags are selected. Defaults to 2. */
    public int searchTagCandidateMultiplierSafe() {
        Integer o = overrideInt(SettingsKeys.SEARCH_TAG_CANDIDATE_MULTIPLIER);
        Integer effective = (o != null) ? o : searchTagCandidateMultiplier;
        return (effective == null || effective < 1) ? 2 : effective;
    }

    /** Weighted RRF — keyword (BM25) axis weight. Defaults to 1.0 (parity with the group-normalized vector axes). */
    public double searchRrfKeywordWeightSafe() {
        Double o = overrideDouble(SettingsKeys.SEARCH_RRF_KEYWORD_WEIGHT);
        Double effective = (o != null) ? o : searchRrfKeywordWeight;
        return (effective != null && effective > 0) ? effective : 1.0;
    }

    /** Weighted RRF — rank-fusion constant k. Defaults to 60 (the original RRF paper's value). */
    public int searchRrfKSafe() {
        Integer o = overrideInt(SettingsKeys.SEARCH_RRF_K);
        Integer effective = (o != null) ? o : searchRrfK;
        return (effective != null && effective > 0) ? effective : 60;
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
        // max-concurrent-files and max-concurrent-llm-calls are hot-editable (fold the overrides in
        // here — every consumer reads them fresh per operation: DocumentIndexer's keyword gate,
        // MarkdownCorrectionService.correct(), TextToMarkdownService.convert() and
        // LazyVisionService — so the next indexing sees a /settings change without a restart). None
        // of them may cache these in a field.
        // timeout/batch are ALSO read fresh per call (KeywordExtractor reads props.indexingSafe()
        // directly, not cached), but they stay outside SettingsKeys/HOT_EDITABLE — no override hook
        // exists for them here, so a /settings entry for them would have nothing to write to. Not a
        // caching limitation like similarity-threshold used to be (see the two VectorStoreProvider
        // impls) — just not wired up as hot-editable yet.
        // Fallbacks mirror the application.properties defaults (FILES=1, LLM=3) so a missing/zero
        // config lands on the same conservative peak (FILES × LLM) the shipped config does — they
        // used to drift (4/8), silently tripling the peak whenever the config block was absent.
        Integer filesOverride = overrideInt(SettingsKeys.INDEXING_MAX_CONCURRENT_FILES);
        Integer llmOverride   = overrideInt(SettingsKeys.INDEXING_MAX_CONCURRENT_LLM);
        if (indexing == null) {
            int f = (filesOverride != null && filesOverride > 0) ? filesOverride : 1;
            int l = (llmOverride != null && llmOverride > 0) ? llmOverride : 3;
            return new IndexingConfig(f, l, 30, 4);
        }
        int files   = (filesOverride != null && filesOverride > 0) ? filesOverride
                    : (indexing.maxConcurrentFiles() > 0 ? indexing.maxConcurrentFiles() : 1);
        int llm     = (llmOverride != null && llmOverride > 0) ? llmOverride
                    : (indexing.maxConcurrentLlmCalls() > 0 ? indexing.maxConcurrentLlmCalls() : 3);
        int timeout = indexing.keywordTimeoutSeconds() > 0 ? indexing.keywordTimeoutSeconds() : 30;
        int batch   = indexing.keywordBatchSize() > 0      ? indexing.keywordBatchSize()      : 4;
        return new IndexingConfig(files, llm, timeout, batch);
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
        // rasterizeShapes defaults to false — loose overlapping shapes no longer auto-merge into
        // one blob; only groups/SmartArt/table+shape/picture+annotation composites survive.
        boolean rasterizeShapes = (pptxImage != null && pptxImage.rasterizeShapes() != null)
                && pptxImage.rasterizeShapes();
        return new PptxShapeExtractionConfig(minDim, padding, mergeAnnotatedPictures, rasterizeShapes);
    }

    /**
     * DOCX VML-shape + picture merge tuning, defaulting to true (merge). Only falls back on an
     * unset (null) field — an explicit false is honored.
     */
    public DocxShapeExtractionConfig docxImageSafe() {
        boolean mergeAnnotatedShapes = (docxImage != null && docxImage.mergeAnnotatedShapes() != null)
                ? docxImage.mergeAnnotatedShapes() : true;
        return new DocxShapeExtractionConfig(mergeAnnotatedShapes);
    }

    /**
     * Default language tag the correction LLM is told to use when it wraps unfenced code/logs into a
     * code block and can't determine the language ({@link com.example.ragagent.service.MarkdownCorrectionService}).
     * Restricted to java/bash/sql (this project's own stack) — any other configured value (including
     * unset/blank) falls back to {@code "java"}.
     */
    public String mdCorrectionDefaultCodeLanguageSafe() {
        String v = mdCorrectionDefaultCodeLanguage == null ? "" : mdCorrectionDefaultCodeLanguage.strip().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "java", "bash", "sql" -> v;
            default -> "java";
        };
    }

    /** Null-safe accessor — returns an empty LlmConfig when app.llm is not configured. */
    public LlmConfig llmSafe() {
        // direct-temperature is hot-editable — fold the /settings override in here (DirectAnswerService
        // reads props.llmSafe().directTemperature() per call). temperature/maxTokens stay view-only:
        // they're baked into the provider defaultOptions at bean creation, so an override couldn't take
        // effect until a restart — no hook for them.
        Double directOverride = overrideDouble(SettingsKeys.LLM_DIRECT_TEMPERATURE);
        if (llm == null) {
            double dt = clamp(directOverride != null ? directOverride : 0.1, 0.0, 0.2);
            return new LlmConfig(List.of(), 2, 10, 180, "COST_FIRST", 0.6, 3, 20, 0.0, dt, 6000);
        }
        List<ProviderConfig> providers = llm.providers() != null ? llm.providers() : List.of();
        int minutes = llm.circuitBreakerMinutes() > 0 ? llm.circuitBreakerMinutes() : 2;
                int connectTimeout = llm.connectTimeoutSeconds() > 0 ? llm.connectTimeoutSeconds() : 10;
                int readTimeout = llm.readTimeoutSeconds() > 0 ? llm.readTimeoutSeconds() : 180;
        String mode = llm.defaultRoutingMode() != null ? llm.defaultRoutingMode() : "COST_FIRST";
        double threshold = llm.progressiveThreshold() > 0 ? llm.progressiveThreshold() : 0.6;
        int defaultProviderConcurrency = llm.defaultProviderConcurrency() > 0 ? llm.defaultProviderConcurrency() : 3;
        int permitWaitTimeoutSeconds = llm.permitWaitTimeoutSeconds() > 0 ? llm.permitWaitTimeoutSeconds() : 20;
        double temperature = clamp(llm.temperature() != null ? llm.temperature() : 0.0, 0.0, 2.0);
        double directBase = directOverride != null ? directOverride
                : (llm.directTemperature() != null ? llm.directTemperature() : 0.1);
        double directTemperature = clamp(directBase, 0.0, 0.2);
        int maxTokens = (llm.maxTokens() != null && llm.maxTokens() > 0) ? llm.maxTokens() : 6000;
                return new LlmConfig(providers, minutes, connectTimeout, readTimeout, mode, threshold,
                        defaultProviderConcurrency, permitWaitTimeoutSeconds, temperature, directTemperature, maxTokens);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
