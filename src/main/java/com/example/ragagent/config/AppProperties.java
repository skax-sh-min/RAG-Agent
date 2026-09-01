package com.example.ragagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.util.unit.DataSize;

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
        Double searchRrfKeywordWeight,          // 가중 RRF — 키워드(BM25) 축 가중치 (기본 0.5; 벡터축은 그룹 정규화되어 1.0이 동등 비중이므로 BM25를 절반으로 낮춘 값)
        Integer searchRrfK,                     // 가중 RRF — RRF 상수 k (기본 60, 원논문 표준값)
        Boolean searchQueryEmbedCacheEnabled,   // 쿼리 임베딩 캐시 on/off (기본 true)
        Integer searchQueryEmbedCacheMaxSize,   // 쿼리 임베딩 캐시 최대 엔트리 수 (기본 500)
        Integer searchQueryEmbedCacheTtlSeconds, // 쿼리 임베딩 캐시 TTL 초 (기본 600 = 10분)
        PptxShapeExtractionConfig pptxImage,    // PPTX 그리기 도구 도형 래스터라이즈/클러스터링 튜닝
        String mdCorrectionDefaultCodeLanguage, // MD 교정 시 LLM이 미펜스 코드를 감쌀 때 언어 판단이 어려우면 붙일 기본 언어 (java/bash/sql, 기본 java)
        DocxShapeExtractionConfig docxImage,    // DOCX 레거시 VML 도형 + 사진 합성 튜닝
        Boolean pptxRemoveDuplicateSlides,      // PPTX 변환 시 완전 동일 슬라이드 + 목차형 슬라이드 제거 (기본 true) — PptxToMarkdownConverter
        Boolean pptxDropDividerSlides,          // PPTX 변환 시 본문·이미지 없이 '구분용 제목'만 있는 섹션 구분 슬라이드 제거 (기본 true, 문장형/키 메시지 제목은 유지) — PptxToMarkdownConverter
        Boolean searchCuratedQaEnabled,          // §10.10 — 좋아요 기반 큐레이션 Q&A를 RRF 축으로 반영할지 여부 (기본 true). 핫에디터블 — RetrievalService가 매 검색마다 재조회
        Double searchCuratedQaWeight,            // §10.10 — 좋아요 큐레이션 축 RRF 가중치 (기본 1.0 = 그룹정규화된 벡터축과 동등. 예전 1.2 에서 내렸다 — 이 축은 후보가 적어 웬만하면 자기 축 상위를 받는데 거기에 가산점까지 주면 관련 없는 큐레이션 항목이 끌려 올라온다). 지식 제안은 searchSubmissionWeight 로 별도. 핫에디터블
        Boolean pptxDropRedundantTitleSlides,    // PPTX 변환 시 이미지·도형 없이 짧은 제목 한 줄만 있고 그 내용이 바로 다음 슬라이드에 그대로 포함되는 "예고 제목" 슬라이드 제거 (기본 true) — PptxToMarkdownConverter
        Boolean pptxDropEndingSlide,             // PPTX 변환 시 마지막 슬라이드가 이미지 없이 '끝'/'END'/'The End' 같은 종료 표시만 담고 있으면 제거 (기본 true) — PptxToMarkdownConverter
        Boolean chunkSplitGranular,              // 청크 분할 전략: true=소제목 기준 최대 분할(min-chunk-size 무시), false=크기 기준 병합(기본, 기존 동작). 핫에디터블 — 다음 인덱싱/↺ 재인덱싱부터 적용
        Double searchSubmissionWeight,           // 지식 제안(승인된 사용자 제출) 축 RRF 가중치 (기본 1.0). 좋아요 큐레이션(searchCuratedQaWeight)과 별개 — 핫에디터블
        UploadConfig upload                      // §6.15 — 전역 저장 상한(문서 업로드가 늘리는 디스크 사용량의 총량 캡). 미설정/0 = 무제한(기본)
) {
    public record LlmConfig(
            List<ProviderConfig> providers,
            int circuitBreakerMinutes,
            int connectTimeoutSeconds,
            int readTimeoutSeconds,
            String defaultRoutingMode,
            int defaultProviderConcurrency,  // per-provider concurrency gate default (matches the server's --parallel), fallback when a provider omits its own `concurrency`
            int permitWaitTimeoutSeconds,    // max wait for a concurrency slot on the query path before failing fast with 429 (default 60s, well under the 600s read-timeout)
            Double temperature,              // general/RAG temperature (app.llm.temperature / LLM_TEMPERATURE), default 0.0, clamp [0,0.3] — HOT-editable, attached per call by every interactive gated caller (ClassifierService, AnswerService, RerankerService); still baked into each provider's defaultOptions at bean creation too, as the fallback for framework-internal callers that build their own ChatClient around the injected model (e.g. RetrievalService's MultiQueryExpander) and so can't take a per-call override
            Double directTemperature,        // Direct(meta) answer temperature (app.llm.direct-temperature / DIRECT_LLM_TEMPERATURE), default 0.1, clamp [0,1.0] — HOT-editable (DirectAnswerService reads it per call, §6.18)
            Double indexingTemperature,      // indexing/background temperature (app.llm.indexing-temperature / LLM_INDEXING_TEMPERATURE), default 0.0, clamp [0,0.1] — HOT-editable, attached per call by every ungated executeWithTracking() caller (KeywordExtractor, MarkdownCorrectionService, TextToMarkdownService, VisionDescriptionService, ImageTypeClassifier, ThreadMetaService, ConversationSummarizerService) so a higher general/RAG temperature can never leak into extraction-style calls that need to stay deterministic
            Double creativeTemperature,      // C(응용) 모드 answer temperature (app.llm.creative-temperature / CREATIVE_LLM_TEMPERATURE), default 0.7, clamp [0,1.0] — HOT-editable (§6.24). Separate from `temperature` because that one is clamped to [0,0.3]: a document-faithful answer must not wobble under sampling, which also makes creative generation impossible on it. Read fresh per call by AnswerService on BOTH the blocking and the streaming path — miss streamDirect() and only the chat UI stays cold
            Boolean creativeModeEnabled,     // C(응용) 모드를 채팅에서 고를 수 있는가 (app.llm.creative-mode-enabled / CREATIVE_MODE_ENABLED), default true — HOT-editable. 온도(creativeTemperature)가 "C를 어떻게 답하게 할까"라면 이쪽은 "C를 열어 둘까"다: 문서 밖 내용을 생성하는 유일한 모드라 배포처에 따라 아예 닫아 두는 것이 운영 정책일 수 있다. 끄면 채팅 입력창에서 C 버튼이 사라지고, 그래도 도착한 요청(REST·손으로 만든 폼)은 SettingsService.effectiveResponseMode() 가 N 으로 강등한다 — 과거 C 턴의 기록/배지는 그대로 남는다
            Integer maxTokens,               // LLM response cap (app.llm.max-tokens / LLM_MAX_TOKENS), default 6000, clamp >0 — VIEW-ONLY (baked at bean creation; streaming chat answers are uncapped by design, bounded by SSE timeouts)
            Boolean verifyLocalModelsOnStartup // GET {base-url}/v1/models for every registered LOCAL-role provider at boot — fails startup (throws, Spring exits) if unreachable or the configured model isn't in the response. Default true (app.llm.verify-local-models-on-startup / LLM_VERIFY_LOCAL_MODELS_ON_STARTUP)
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
            Integer concurrency, // this provider's own concurrency slots; null/<=0 falls back to LlmConfig.defaultProviderConcurrency
            Integer contextSize, // this provider's total context window in tokens (input + output). Unset = probed from the server at startup (ContextWindowProbe), and left unknown if that fails — never guessed. Operator-declared always wins, because a probe reads the server as it is *right now* and a model reloaded at a different size makes it stale
            Integer maxTokens    // this provider's own blocking-call output cap; null/<=0 falls back to LlmConfig.maxTokens. Exists because context windows differ per model — a 8k local model and a 128k cloud model cannot share one ceiling. Enforced by MaxTokensCappingChatModel (baking it into the provider bean's defaultOptions is not enough: every blocking call site attaches its own maxTokens, which would override it)
    ) {
        /**
         * True when this provider will actually be registered as a live {@code LlmProvider} by
         * {@code LlmConfig.llmRouter()} (its G1+G2 gates) — a LOCAL-role provider is exempt from
         * needing an api-key (it defaults to the "no-key" placeholder), but every role, LOCAL
         * included, still needs a non-blank base-url. The single source of truth for "is this
         * provider usable", shared by the bean-registration filter and the /llm-usage status
         * badge — before this existed, /llm-usage only checked api-key and showed a LOCAL slot
         * (e.g. local-fast/local-2) as "정상" even with no base-url configured, since its api-key
         * always defaults to a non-blank "no-key" placeholder regardless of base-url.
         */
        public boolean isEnabled() {
            boolean hasKey     = apiKey != null && !apiKey.isBlank();
            boolean isLocal    = role != null && "LOCAL".equalsIgnoreCase(role.trim());
            boolean hasBaseUrl = baseUrl != null && !baseUrl.isBlank();
            return (hasKey || isLocal) && hasBaseUrl;
        }
    }

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
            Integer maxChunkChars,          // hard ceiling per chunk to fit the embedding server batch; 0/null = disabled
            List<String> additionalBaseUrls, // §6.21 E1 — extra endpoints (same model), load-balanced with base-url
            Integer maxConcurrentBatches    // §6.21 E2 — parallel sub-batch embeds during indexing; 1/null = serial
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

    /**
     * §6.15 — 전역 저장 상한. 저장소가 사용자별로 갈라져 있지 않으므로({@code DocRegistry.SHARED})
     * 쿼터 축도 "사용자별 누적"이 아니라 배포 전체의 디스크 총량이다.
     *
     * <p>{@code DataSize} 로 받는 이유는 상한이 GB 단위여서다 — {@code 20GB} 로 쓸 수 있고, 단위
     * 없는 숫자는 바이트로 읽힌다({@code 0} = 무제한, 기본값). {@code int} 바이트 수로는 2GB 를
     * 넘길 수도 없다.
     */
    public record UploadConfig(
            DataSize maxTotalSize,       // 문서 저장 사용량 총 상한 (app.upload.max-total-size / UPLOAD_MAX_TOTAL_SIZE). 0/미설정 = 무제한
            Integer backupRetentionDays, // documents/backup/ 보관 일수 (app.upload.backup-retention-days / BACKUP_RETENTION_DAYS). 0/음수 = 기간 제한 없음
            DataSize backupMaxSize       // documents/backup/ 총 용량 상한 (app.upload.backup-max-size / BACKUP_MAX_SIZE). 초과 시 오래된 것부터 삭제. 0 = 무제한
    ) {
        /** 미설정 시 보관 일수. 삭제 취소를 뒤늦게 알아차리는 데 걸릴 만한 시간을 넉넉히 잡은 값. */
        public static final int DEFAULT_BACKUP_RETENTION_DAYS = 30;

        /** 미설정 시 백업 총 용량 상한. */
        public static final long DEFAULT_BACKUP_MAX_BYTES = 2L * 1024 * 1024 * 1024;

        /** 상한(바이트). {@code <= 0} 이면 무제한이라는 뜻이고, 호출부는 그때 아무것도 검사하지 않는다. */
        public long maxTotalBytes() {
            return maxTotalSize == null ? 0L : maxTotalSize.toBytes();
        }

        /** 상한이 실제로 걸려 있는지 — {@code maxTotalBytes() > 0}. 기본 배포는 false 라 회귀가 0이다. */
        public boolean hasLimit() {
            return maxTotalBytes() > 0L;
        }

        /** 백업 보관 일수. {@code <= 0} = 기간으로는 지우지 않음(다른 두 규칙은 그대로 적용된다). */
        public int backupRetentionDaysOrZero() {
            return backupRetentionDays == null ? 0 : Math.max(0, backupRetentionDays);
        }

        /** 백업 총 용량 상한(바이트). {@code <= 0} = 용량으로는 지우지 않음. */
        public long backupMaxBytes() {
            return backupMaxSize == null ? 0L : Math.max(0L, backupMaxSize.toBytes());
        }
    }

    public record AuthConfig(
            boolean enabled,         // false → no-auth mode (guest/admin auto-login)
            boolean managementOnly,  // §6.17 B안 — only meaningful when enabled=false; authSafe() normalizes
                                     // this to false whenever enabled=true, so it's the only place that rule
                                     // needs to be known. true → /admin/** + document-write UI require a real
                                     // login (NoAuthAutoLoginFilter/SecurityConfig), everything else stays
                                     // guest-auto-authenticated exactly like plain no-auth mode.
            String guestIdentity     // How no-auth mode separates visitors from each other — see GuestIdentity.
                                     // Only meaningful when enabled=false (GuestIdentityResolver, which reads
                                     // it, is @ConditionalOnProperty on the same flag). authSafe() normalizes
                                     // null/blank/unknown to GuestIdentity.SHARED (= the pre-existing single
                                     // shared guest), so a config typo degrades to old behavior, never to a
                                     // half-applied split.
    ) {
        /**
         * MANDATORY once this record has more than one constructor: without it Spring Boot cannot tell
         * which one to bind with and silently picks the 2-arg convenience form below, leaving
         * {@code guestIdentity} at its default no matter what {@code app.auth.guest-identity} says —
         * a failure that looks exactly like a valid {@code shared} configuration.
         */
        @ConstructorBinding
        public AuthConfig {
        }

        /** Back-compat 2-arg form — defaults to the single shared guest identity. Test convenience;
         *  never used for property binding (see {@code @ConstructorBinding} above). */
        public AuthConfig(boolean enabled, boolean managementOnly) {
            this(enabled, managementOnly, GuestIdentity.SHARED);
        }
    }

    /**
     * Valid {@code app.auth.guest-identity} values — how no-auth mode decides which visitor a request
     * belongs to. The resulting id lands in {@code ThreadContext.userId()}, which every repository is
     * already keyed by ({@code thread_meta}, {@code conversation_turns}, {@code curated_qa}), so chat
     * personalization needs no storage change. Document storage stays shared ({@code DocRegistry.SHARED}).
     */
    public static final class GuestIdentity {
        /** One fixed guest for everyone — the pre-existing behavior, and the default. */
        public static final String SHARED = "shared";
        /** Derive from the client IP alone. No cookie needed, but NAT collapses visitors and a DHCP
         *  lease change orphans history. Requires {@code app.trust-forwarded-for} to be set correctly. */
        public static final String IP = "ip";
        /** Derive from a long-lived signed cookie alone. Accurate regardless of IP, but a visitor who
         *  blocks or clears cookies gets a brand-new identity every time. */
        public static final String COOKIE = "cookie";
        /** Cookie when present, otherwise derive from IP and persist that as the cookie. Survives both
         *  an IP change (cookie wins) and a cookie wipe (same IP re-derives the same id). Recommended. */
        public static final String HYBRID = "hybrid";

        static final java.util.Set<String> ALL = java.util.Set.of(SHARED, IP, COOKIE, HYBRID);

        private GuestIdentity() {}
    }

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
            Double clusterProximityPaddingPt, // 클러스터링 근접 판정 시 바운딩박스에 적용할 바깥쪽 패딩 (기본 5)
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
            Boolean mergeAnnotatedShapes // [실험적] true: 같은 문단의 VML 도형을 사진과 합성(합성 위치가 실제 문서와 어긋날 수 있음) / false(기본): 항상 원본 사진만 추출
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
     * Similarity threshold for vector search, clamped to [0,1]. Defaults to <b>0.3</b> — a floor
     * low enough that a genuinely relevant chunk never trips it, high enough to drop the tail the
     * vector store returns simply because {@code topK} asked for that many rows.
     *
     * <p>It applies inside the {@code VectorStoreProvider}, so it prunes the <b>vector axis only</b>
     * — the BM25 axis is unfiltered. Raising this therefore also raises the keyword axis's relative
     * share of the fused ranking, which is why it and {@code search-rrf-keyword-weight} should not
     * be raised in the same step. The curated axes are vector searches too, so they are pruned by
     * the same floor.
     *
     * <p>{@code 0.0} = accept all (the previous default, and Spring AI's).
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

    /**
     * Chunk-splitting strategy. {@code false} (default, and the value used when unset) keeps the
     * size-driven merge that bundles short chapters together; {@code true} selects 최대 분할 —
     * split at every heading, ignore {@code min-chunk-size}, fold only heading+≤2-sentence lead-ins
     * into the chapters below them (see {@code ChunkSplitter#splitChapterGranular}).
     *
     * <p>Hot-editable: read per index call, so flipping it in {@code /settings} and running ↺
     * re-index re-chunks that document under the other strategy without a restart. Documents indexed
     * before the flip keep their existing chunks until they are re-indexed — the two strategies can
     * coexist in one collection.
     */
    public boolean chunkSplitGranularSafe() {
        Boolean o = overrideBool(SettingsKeys.CHUNK_SPLIT_GRANULAR);
        if (o != null) return o;
        return chunkSplitGranular != null && chunkSplitGranular;
    }

    /**
     * RRF weight of the 지식 제안 axis — approved user submissions ({@code origin='manual'}), split
     * out from the 👍-promoted axis ({@link #searchCuratedQaWeightSafe()}) so the two can be tuned
     * against each other. Both live in the same {@code "curated"} vector namespace; what separates
     * them at search time is {@code MetaKey.CURATED_ORIGIN}. Hot-editable, clamped to {@code >= 0}.
     */
    public double searchSubmissionWeightSafe() {
        Double o = overrideDouble(SettingsKeys.SEARCH_SUBMISSION_WEIGHT);
        double v = (o != null) ? o : (searchSubmissionWeight != null ? searchSubmissionWeight : 1.0);
        return v >= 0 ? v : 1.0;
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
        // 프로퍼티가 비었을 때의 폴백 — application.properties 의 기본값과 같은 수여야 한다.
        return v > 0 ? v : 10;
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

    /** §10.10 — curated-Q&A RRF axis on/off. Hot-editable. Defaults to enabled. */
    public boolean searchCuratedQaEnabledSafe() {
        Boolean o = overrideBool(SettingsKeys.SEARCH_CURATED_QA_ENABLED);
        Boolean effective = (o != null) ? o : searchCuratedQaEnabled;
        return effective == null || effective;
    }

    /** §10.10 — curated-Q&A RRF axis weight. Defaults to <b>1.0</b>: parity with the
     *  group-normalized vector axes, so this axis competes on rank rather than on a bonus.
     *  It used to sit above 1.0, which surfaced loosely-related curated entries — the axis holds
     *  few candidates, so almost anything in it ranks high on its own axis, and a bonus on top of
     *  that reads as "boost whatever exists here". Hot-editable. */
    public double searchCuratedQaWeightSafe() {
        Double o = overrideDouble(SettingsKeys.SEARCH_CURATED_QA_WEIGHT);
        Double effective = (o != null) ? o : searchCuratedQaWeight;
        return (effective != null && effective > 0) ? effective : 1.0;
    }

    /** Query embedding cache on/off. Defaults to enabled. */
    public boolean searchQueryEmbedCacheEnabledSafe() {
        return searchQueryEmbedCacheEnabled == null || searchQueryEmbedCacheEnabled;
    }

    /** PPTX duplicate/table-of-contents slide removal on/off. Defaults to enabled. */
    public boolean pptxRemoveDuplicateSlidesSafe() {
        return pptxRemoveDuplicateSlides == null || pptxRemoveDuplicateSlides;
    }

    /** PPTX section-divider (title-only, no body/image) slide removal on/off. Defaults to enabled. */
    public boolean pptxDropDividerSlidesSafe() {
        return pptxDropDividerSlides == null || pptxDropDividerSlides;
    }

    /** PPTX redundant "preview title" slide removal (title-only slide whose text also appears on
     *  the immediately following slide) on/off. Defaults to enabled. */
    public boolean pptxDropRedundantTitleSlidesSafe() {
        return pptxDropRedundantTitleSlides == null || pptxDropRedundantTitleSlides;
    }

    /** PPTX last-slide "ending marker" (끝/END/The End) removal on/off. Defaults to enabled. */
    public boolean pptxDropEndingSlideSafe() {
        return pptxDropEndingSlide == null || pptxDropEndingSlide;
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
            return new IndexingConfig(f, l, 30, 2);
        }
        int files   = (filesOverride != null && filesOverride > 0) ? filesOverride
                    : (indexing.maxConcurrentFiles() > 0 ? indexing.maxConcurrentFiles() : 1);
        int llm     = (llmOverride != null && llmOverride > 0) ? llmOverride
                    : (indexing.maxConcurrentLlmCalls() > 0 ? indexing.maxConcurrentLlmCalls() : 3);
        int timeout = indexing.keywordTimeoutSeconds() > 0 ? indexing.keywordTimeoutSeconds() : 30;
        int batch   = indexing.keywordBatchSize() > 0      ? indexing.keywordBatchSize()      : 2;
        return new IndexingConfig(files, llm, timeout, batch);
    }

    public ChromaHttpConfig chromaSafe() {
        if (chroma == null) return new ChromaHttpConfig(5, 60);
        int connect = chroma.connectTimeoutSeconds() > 0 ? chroma.connectTimeoutSeconds() : 5;
        int read = chroma.readTimeoutSeconds() > 0 ? chroma.readTimeoutSeconds() : 60;
        return new ChromaHttpConfig(connect, read);
    }

    public EmbeddingConfig embeddingSafe() {
        if (embedding == null) return new EmbeddingConfig(null, null, null, null, 10, 120, true, 0, List.of(), 1);
        int connect = (embedding.connectTimeoutSeconds() != null && embedding.connectTimeoutSeconds() > 0)
                ? embedding.connectTimeoutSeconds() : 10;
        int read = (embedding.readTimeoutSeconds() != null && embedding.readTimeoutSeconds() > 0)
                ? embedding.readTimeoutSeconds() : 120;
        boolean usageFallback = embedding.usageFallbackEnabled() == null || embedding.usageFallbackEnabled();
        // 0 = disabled (no hard cap); negative values are clamped to 0.
        int maxChunkChars = (embedding.maxChunkChars() != null && embedding.maxChunkChars() > 0)
                ? embedding.maxChunkChars() : 0;
        // §6.21 E1/E2 — null-safe defaults: no extra endpoints, serial sub-batch embedding.
        List<String> additionalBaseUrls = embedding.additionalBaseUrls() != null
                ? embedding.additionalBaseUrls() : List.of();
        int maxConcurrentBatches = (embedding.maxConcurrentBatches() != null && embedding.maxConcurrentBatches() > 1)
                ? embedding.maxConcurrentBatches() : 1;
        return new EmbeddingConfig(
                embedding.baseUrl(),
                embedding.apiKey(),
                embedding.model(),
                embedding.dimensions(),
                connect,
                read,
                usageFallback,
                maxChunkChars,
                additionalBaseUrls,
                maxConcurrentBatches
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

    /**
     * §6.15 저장 상한 + 백업 보존 정책.
     *
     * <p><b>상한은</b> 미설정·음수가 전부 <b>0(무제한)</b> 으로 정규화된다 — 상한이 없는 상태가
     * 기본이고, 설정 실수가 "예상보다 빡빡한 상한"으로 굳어져 업로드를 막는 쪽보다 낫다.
     *
     * <p><b>백업 보존은 반대로</b> 미설정이 <b>기본값</b>(30일 / 2GB)으로 채워진다. 이쪽의 "무제한"은
     * 안전한 기본이 아니라 디스크가 조용히 차는 상태이고, 백업은 사용자가 화면에서 볼 수도 지울 수도
     * 없는 파일이라 아무도 알아차리지 못한다. 명시적으로 {@code 0} 을 적은 운영자만 무제한이 된다.
     */
    public UploadConfig uploadSafe() {
        if (upload == null) {
            return new UploadConfig(DataSize.ofBytes(0),
                    UploadConfig.DEFAULT_BACKUP_RETENTION_DAYS,
                    DataSize.ofBytes(UploadConfig.DEFAULT_BACKUP_MAX_BYTES));
        }
        return new UploadConfig(
                DataSize.ofBytes(Math.max(0L, upload.maxTotalBytes())),
                upload.backupRetentionDays() == null
                        ? UploadConfig.DEFAULT_BACKUP_RETENTION_DAYS
                        : Math.max(0, upload.backupRetentionDays()),
                DataSize.ofBytes(upload.backupMaxSize() == null
                        ? UploadConfig.DEFAULT_BACKUP_MAX_BYTES
                        : upload.backupMaxBytes()));
    }

    public AuthConfig authSafe() {
        if (auth == null) return new AuthConfig(true, false);
        // managementOnly is only meaningful when auth is disabled — normalize here so every
        // downstream consumer (SecurityConfig, NoAuthAutoLoginFilter, GlobalModelAdvice, ...)
        // can trust authSafe().managementOnly() directly without re-deriving this rule.
        return new AuthConfig(auth.enabled(),
                !auth.enabled() && auth.managementOnly(),
                normalizeGuestIdentity(auth.guestIdentity()));
    }

    /** Unknown/blank → SHARED: a typo must fall back to the pre-existing single-guest behavior rather
     *  than to some partially-applied split. GuestIdentityResolver logs the raw value when it differs. */
    private static String normalizeGuestIdentity(String raw) {
        if (raw == null || raw.isBlank()) return GuestIdentity.SHARED;
        String v = raw.strip().toLowerCase(Locale.ROOT);
        return GuestIdentity.ALL.contains(v) ? v : GuestIdentity.SHARED;
    }

    /** Vector store backend, defaulting to {@code chroma}. (Bean wiring uses raw @ConditionalOnProperty.) */
    public VectorStoreConfig vectorStoreSafe() {
        if (vectorstore == null || vectorstore.type() == null || vectorstore.type().isBlank())
            return new VectorStoreConfig("chroma");
        return new VectorStoreConfig(vectorstore.type().trim());
    }

    /** Conversation-history fetch limit (fallback path), defaulting to 10 turns. Clamped to >= 1. */
    public MemoryConfig memorySafe() {
        int limit = (memory != null && memory.fetchLimitTurns() != null && memory.fetchLimitTurns() > 0)
                ? memory.fetchLimitTurns() : 10;
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
                ? pptxImage.clusterProximityPaddingPt() : 5.0;
        boolean mergeAnnotatedPictures = (pptxImage != null && pptxImage.mergeAnnotatedPictures() != null)
                ? pptxImage.mergeAnnotatedPictures() : true;
        // rasterizeShapes defaults to false — loose overlapping shapes no longer auto-merge into
        // one blob; only groups/SmartArt/table+shape/picture+annotation composites survive.
        boolean rasterizeShapes = (pptxImage != null && pptxImage.rasterizeShapes() != null)
                && pptxImage.rasterizeShapes();
        return new PptxShapeExtractionConfig(minDim, padding, mergeAnnotatedPictures, rasterizeShapes);
    }

    /**
     * DOCX VML-shape + picture merge tuning. [Experimental] Defaults to false (no merge) — the
     * merged shape's position can drift from the actual document layout, so verbatim extraction is
     * currently the safer default. Only falls back on an unset (null) field — an explicit true is
     * honored.
     */
    public DocxShapeExtractionConfig docxImageSafe() {
        boolean mergeAnnotatedShapes = (docxImage != null && docxImage.mergeAnnotatedShapes() != null)
                && docxImage.mergeAnnotatedShapes();
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

    /**
     * {@code app.llm.max-tokens} 의 코드 쪽 폴백 — {@code application.properties} 의 기본값과
     * <b>같은 수여야 한다</b>. 두 곳에 흩어진 리터럴이라 한쪽만 바꾸면 프로퍼티가 비었을 때만
     * 다른 값이 나오는, 재현이 까다로운 불일치가 된다.
     */
    private static final int DEFAULT_MAX_TOKENS = 10_000;

    /** Null-safe accessor — returns an empty LlmConfig when app.llm is not configured. */
    public LlmConfig llmSafe() {
        // temperature / direct-temperature / indexing-temperature are all hot-editable — fold their
        // /settings overrides in here. Every interactive gated caller (ClassifierService, AnswerService,
        // RerankerService) reads temperature() per call; DirectAnswerService reads directTemperature()
        // per call; every ungated executeWithTracking() background caller reads indexingTemperature()
        // per call; AnswerService reads creativeTemperature() per call for the C (creative) mode, on
        // the blocking AND the streaming path; creative-mode-enabled gates whether the C mode can be
        // picked at all (read per chat request by SettingsService.effectiveResponseMode()).
        // maxTokens stays view-only: it's baked into the provider
        // defaultOptions at bean creation, so an override couldn't take effect until a restart.
        Double tempOverride = overrideDouble(SettingsKeys.LLM_TEMPERATURE);
        Double directOverride = overrideDouble(SettingsKeys.LLM_DIRECT_TEMPERATURE);
        Double indexingOverride = overrideDouble(SettingsKeys.LLM_INDEXING_TEMPERATURE);
        Double creativeOverride = overrideDouble(SettingsKeys.LLM_CREATIVE_TEMPERATURE);
        Boolean creativeModeOverride = overrideBool(SettingsKeys.LLM_CREATIVE_MODE_ENABLED);
        if (llm == null) {
            double t = clamp(tempOverride != null ? tempOverride : 0.0, 0.0, 0.3);
            double dt = clamp(directOverride != null ? directOverride : 0.1, 0.0, 1.0);
            double it = clamp(indexingOverride != null ? indexingOverride : 0.0, 0.0, 0.1);
            double ct = clamp(creativeOverride != null ? creativeOverride : 0.7, 0.0, 1.0);
            boolean cm = creativeModeOverride == null || creativeModeOverride;
            return new LlmConfig(List.of(), 2, 10, 180, "COST_FIRST", 3, 20, t, dt, it, ct, cm,
                    DEFAULT_MAX_TOKENS, true);
        }
        List<ProviderConfig> providers = llm.providers() != null ? llm.providers() : List.of();
        int minutes = llm.circuitBreakerMinutes() > 0 ? llm.circuitBreakerMinutes() : 2;
                int connectTimeout = llm.connectTimeoutSeconds() > 0 ? llm.connectTimeoutSeconds() : 10;
                int readTimeout = llm.readTimeoutSeconds() > 0 ? llm.readTimeoutSeconds() : 180;
        String mode = llm.defaultRoutingMode() != null ? llm.defaultRoutingMode() : "COST_FIRST";
        int defaultProviderConcurrency = llm.defaultProviderConcurrency() > 0 ? llm.defaultProviderConcurrency() : 3;
        int permitWaitTimeoutSeconds = llm.permitWaitTimeoutSeconds() > 0 ? llm.permitWaitTimeoutSeconds() : 20;
        double temperatureBase = tempOverride != null ? tempOverride
                : (llm.temperature() != null ? llm.temperature() : 0.0);
        double temperature = clamp(temperatureBase, 0.0, 0.3);
        double directBase = directOverride != null ? directOverride
                : (llm.directTemperature() != null ? llm.directTemperature() : 0.1);
        double directTemperature = clamp(directBase, 0.0, 1.0);
        double indexingBase = indexingOverride != null ? indexingOverride
                : (llm.indexingTemperature() != null ? llm.indexingTemperature() : 0.0);
        double indexingTemperature = clamp(indexingBase, 0.0, 0.1);
        double creativeBase = creativeOverride != null ? creativeOverride
                : (llm.creativeTemperature() != null ? llm.creativeTemperature() : 0.7);
        double creativeTemperature = clamp(creativeBase, 0.0, 1.0);
        // 기본 ON — 이 스위치가 생기기 전에는 C 가 늘 열려 있었으므로, 미설정 시 동작이 바뀌면 안 된다.
        boolean creativeModeEnabled = creativeModeOverride != null ? creativeModeOverride
                : (llm.creativeModeEnabled() == null || llm.creativeModeEnabled());
        int maxTokens = (llm.maxTokens() != null && llm.maxTokens() > 0) ? llm.maxTokens() : DEFAULT_MAX_TOKENS;
        boolean verifyLocalModels = llm.verifyLocalModelsOnStartup() == null || llm.verifyLocalModelsOnStartup();
                return new LlmConfig(providers, minutes, connectTimeout, readTimeout, mode,
                        defaultProviderConcurrency, permitWaitTimeoutSeconds, temperature, directTemperature,
                        indexingTemperature, creativeTemperature, creativeModeEnabled, maxTokens,
                        verifyLocalModels);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
