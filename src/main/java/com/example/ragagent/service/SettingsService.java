package com.example.ragagent.service;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.llm.ContextWindowProbe;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.config.SettingsKeys;
import com.example.ragagent.llm.CircuitBreaker;
import com.example.ragagent.llm.ProviderConnectivity;
import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.llm.ProviderThinkingDialects;
import com.example.ragagent.llm.ProviderToggle;
import com.example.ragagent.llm.ThinkingDialect;
import com.example.ragagent.llm.ThinkingLevel;
import com.example.ragagent.llm.ThinkingSite;
import com.example.ragagent.model.SettingsView;
import com.example.ragagent.model.SettingsView.ProviderConnection;
import com.example.ragagent.model.SettingsView.ProviderRow;
import com.example.ragagent.model.SettingsView.ProviderThinking;
import com.example.ragagent.model.ResponseMode;
import com.example.ragagent.model.SettingsView.SettingGroup;
import com.example.ragagent.model.SettingsView.SettingItem;
import com.example.ragagent.repository.SettingsOverrideRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The runtime settings-override layer's brain.
 *
 * <p>Implements {@link AppProperties.OverrideSource} and binds itself into {@code AppProperties} at
 * startup so every hot-editable {@code xxxSafe()} accessor sees overrides. Owns:
 * <ul>
 *   <li>an in-memory {@code cache} of persisted overrides (loaded once from
 *       {@link SettingsOverrideRepository}, kept in sync on every write) — the read path
 *       ({@link #get}) never touches SQLite,</li>
 *   <li>the editable-setting catalog ({@link #SPECS}) used for both validation and view metadata,</li>
 *   <li>{@link #update}/{@link #reset} with type + range validation and {@link AuditLogger} events,</li>
 *   <li>{@link #buildView()} for the {@code /settings} page.</li>
 * </ul>
 *
 * <p>Only {@link SettingsKeys#HOT_EDITABLE} keys are writable. Restart-required values (rerank/hybrid
 * enabled, vectorstore type, embedding dimensions, ...) are surfaced read-only — they are fixed at
 * bean-creation time, so accepting an override for them would silently do nothing until a restart.
 */
@Service
public class SettingsService implements AppProperties.OverrideSource, EnvironmentAware {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    /** {@code CHOICE} — 허용 값 목록 중 하나(§6.29 ⑦-아: 생각 수준 off/low/medium/high). 목록에 없는 값은 400 이다. */
    private enum Kind { DOUBLE, INT, BOOL, CHOICE }

    /**
     * One editable setting's validation + input metadata. {@code labelKey} is an i18n message key.
     * {@code choices} 는 {@link Kind#CHOICE} 일 때만 의미가 있다.
     */
    private record Spec(String key, Kind kind, double min, double max, double step, String labelKey,
                        List<String> choices) {
        Spec(String key, Kind kind, double min, double max, double step, String labelKey) {
            this(key, kind, min, max, step, labelKey, List.of());
        }
    }

    /** 생각 수준의 허용 값 — {@code ThinkingLevel} 이 단일 출처다. {@code default} 같은 값은 없다(PLAN §6.29 ①). */
    private static final List<String> THINKING_CHOICES =
            java.util.Arrays.stream(ThinkingLevel.values()).map(ThinkingLevel::value).toList();

    // Insertion order = render order in the "검색 튜닝 (핫 수정)" group. Apply on the next search.
    // The two RRF axis weights start at 0.1, not 0.0: their xxxSafe() accessors read a non-positive
    // value as "missing" and fall back to 1.0, so a 0 meant to switch an axis off would instead have
    // raised it (the keyword axis ships at 0.5). Switching an axis off is search-hybrid-enabled /
    // search-curated-qa-enabled.
    private static final List<Spec> SEARCH_HOT_SPECS = List.of(
            new Spec(SettingsKeys.SEARCH_SIMILARITY_THRESHOLD,     Kind.DOUBLE, 0.0, 1.0,  0.01, "settings.item.similarity-threshold"),
            new Spec(SettingsKeys.SEARCH_RRF_KEYWORD_WEIGHT,       Kind.DOUBLE, 0.1, 10.0, 0.1,  "settings.item.rrf-keyword-weight"),
            new Spec(SettingsKeys.SEARCH_RRF_K,                    Kind.INT,    1,   1000, 1,    "settings.item.rrf-k"),
            new Spec(SettingsKeys.SEARCH_CANDIDATE_MULTIPLIER,     Kind.INT,    1,   20,   1,    "settings.item.candidate-multiplier"),
            new Spec(SettingsKeys.SEARCH_TAG_CANDIDATE_MULTIPLIER, Kind.INT,    1,   20,   1,    "settings.item.tag-candidate-multiplier"),
            new Spec(SettingsKeys.SEARCH_MULTIQUERY_MIN_LENGTH,    Kind.INT,    0,   1000, 1,    "settings.item.multiquery-min-length"),
            new Spec(SettingsKeys.SEARCH_RETRY_ESCALATE,           Kind.BOOL,   0,   0,    0,    "settings.item.retry-escalate"),
            new Spec(SettingsKeys.SEARCH_TOP_K,                    Kind.INT,    1,   50,   1,    "settings.item.top-k"),
            new Spec(SettingsKeys.SEARCH_MULTIQUERY_ENABLED,       Kind.BOOL,   0,   0,    0,    "settings.item.multiquery-enabled"),
            new Spec(SettingsKeys.SEARCH_HYBRID_ENABLED,           Kind.BOOL,   0,   0,    0,    "settings.item.hybrid-enabled"),
            new Spec(SettingsKeys.SEARCH_CURATED_QA_ENABLED,       Kind.BOOL,   0,   0,    0,    "settings.item.curated-qa-enabled"),
            new Spec(SettingsKeys.SEARCH_CURATED_QA_WEIGHT,        Kind.DOUBLE, 0.1, 10.0, 0.1,  "settings.item.curated-qa-weight"),
            new Spec(SettingsKeys.SEARCH_CURATED_QA_MIN_SIMILARITY, Kind.DOUBLE, 0.0, 1.0, 0.01, "settings.item.curated-qa-min-similarity"),
            // §6.33 — 교차 인코더 리랭커가 있을 때만 의미가 있다. 문턱은 모델의 원점수라 음수도 유효하다(bge-reranker-v2-m3 는 대략 -10~+10).
            new Spec(SettingsKeys.SEARCH_RERANK_MIN_SCORE,          Kind.DOUBLE, -20.0, 20.0, 0.1, "settings.item.rerank-min-score"),
            new Spec(SettingsKeys.SEARCH_MAX_ATTEMPTS,              Kind.INT,    1, AppProperties.MAX_SEARCH_MAX_ATTEMPTS, 1, "settings.item.search-max-attempts")
    );

    // Insertion order = render order in the "인덱싱 튜닝" group. Apply on the next indexing / ↺ re-index
    // (they don't retro-actively re-chunk already-indexed documents).
    private static final List<Spec> INDEXING_HOT_SPECS = List.of(
            new Spec(SettingsKeys.CHUNK_SIZE,                      Kind.INT,    100, 8000, 50,   "settings.item.chunk-size"),
            new Spec(SettingsKeys.CHUNK_OVERLAP,                   Kind.INT,    0,   2000, 10,   "settings.item.chunk-overlap"),
            new Spec(SettingsKeys.MIN_CHUNK_SIZE,                  Kind.INT,    0,   4000, 10,   "settings.item.min-chunk-size"),
            new Spec(SettingsKeys.CHUNK_SPLIT_GRANULAR,            Kind.BOOL,   0,   0,    0,    "settings.item.chunk-split-granular"),
            new Spec(SettingsKeys.INDEXING_MAX_CONCURRENT_FILES,   Kind.INT,    1,   4,    1,    "settings.item.max-concurrent-files"),
            new Spec(SettingsKeys.INDEXING_MAX_CONCURRENT_LLM,     Kind.INT,    1,   8,    1,    "settings.item.max-concurrent-llm-calls"),
            new Spec(SettingsKeys.INDEXING_KEYWORD_BATCH_SIZE,     Kind.INT,    1,   20,   1,    "settings.item.keyword-batch-size"),
            new Spec(SettingsKeys.INDEXING_KEYWORD_TIMEOUT,        Kind.INT,    10,  3600, 10,   "settings.item.keyword-timeout-seconds"),
            // 0 = 끔(상한 없음). 켤 때는 chunk-size 보다 크게 — 작으면 정상 크기의 청크까지 잘린다.
            new Spec(SettingsKeys.EMBEDDING_MAX_CHUNK_CHARS,       Kind.INT,    0,   32000, 100, "settings.item.embed-max-chunk-chars"),
            new Spec(SettingsKeys.EMBEDDING_MAX_CONCURRENT_BATCHES, Kind.INT,   1,   8,    1,    "settings.item.embed-max-concurrent-batches")
    );

    // Insertion order = render order in the "문서 변환" group. Apply on the next upload — the converters
    // read them per conversion; documents already converted don't change.
    // DOCX EMF/WMF 변환은 여기에 없다: @ConditionalOnProperty 빈(EmfToPngConverter·LibreOfficeConverter)의
    // 게이트라 화면에서 켜도 빈이 없으면 아무 일도 하지 않는다(재기동 필요).
    private static final List<Spec> CONVERSION_HOT_SPECS = List.of(
            new Spec(SettingsKeys.PPTX_REMOVE_DUPLICATE_SLIDES,    Kind.BOOL,   0,   0,    0,    "settings.item.pptx-remove-duplicate-slides"),
            new Spec(SettingsKeys.PPTX_DROP_DIVIDER_SLIDES,        Kind.BOOL,   0,   0,    0,    "settings.item.pptx-drop-divider-slides"),
            new Spec(SettingsKeys.PPTX_DROP_REDUNDANT_TITLE_SLIDES, Kind.BOOL,  0,   0,    0,    "settings.item.pptx-drop-redundant-title-slides"),
            new Spec(SettingsKeys.PPTX_DROP_ENDING_SLIDE,          Kind.BOOL,   0,   0,    0,    "settings.item.pptx-drop-ending-slide"),
            new Spec(SettingsKeys.PPTX_IMAGE_MIN_SHAPE_DIMENSION,  Kind.DOUBLE, 0.0, 500.0, 1.0, "settings.item.pptx-image-min-shape-dimension"),
            new Spec(SettingsKeys.PPTX_IMAGE_CLUSTER_PADDING,      Kind.DOUBLE, 0.0, 100.0, 1.0, "settings.item.pptx-image-cluster-padding"),
            new Spec(SettingsKeys.PPTX_IMAGE_MERGE_ANNOTATED,      Kind.BOOL,   0,   0,    0,    "settings.item.pptx-image-merge-annotated"),
            new Spec(SettingsKeys.PPTX_IMAGE_RASTERIZE_SHAPES,     Kind.BOOL,   0,   0,    0,    "settings.item.pptx-image-rasterize-shapes"),
            new Spec(SettingsKeys.DOCX_IMAGE_MERGE_ANNOTATED,      Kind.BOOL,   0,   0,    0,    "settings.item.docx-image-merge-annotated")
    );

    /** 추가 질문 개수의 허용 범위와 기본값 — 화면 스펙과 followUpQuestionsCount()가 같은 값을 쓴다. */
    public static final int MIN_FOLLOW_UP_COUNT = 2;
    public static final int MAX_FOLLOW_UP_COUNT = 5;
    public static final int DEFAULT_FOLLOW_UP_COUNT = 3;

    // Insertion order = render order in the "LLM 튜닝" group. Apply on the next LLM call (§6.18).
    private static final List<Spec> LLM_HOT_SPECS = List.of(
            new Spec(SettingsKeys.LLM_TEMPERATURE,                Kind.DOUBLE, 0.0, 0.3,  0.05, "settings.item.temperature"),
            new Spec(SettingsKeys.LLM_DIRECT_TEMPERATURE,         Kind.DOUBLE, 0.0, 1.0,  0.05, "settings.item.direct-temperature"),
            new Spec(SettingsKeys.LLM_INDEXING_TEMPERATURE,       Kind.DOUBLE, 0.0, 0.1,  0.05, "settings.item.indexing-temperature"),
            new Spec(SettingsKeys.LLM_CREATIVE_TEMPERATURE,       Kind.DOUBLE, 0.0, 1.0,  0.05, "settings.item.creative-temperature"),
            new Spec(SettingsKeys.LLM_CREATIVE_MODE_ENABLED,      Kind.BOOL,   0,   0,    0,    "settings.item.creative-mode-enabled"),
            new Spec(SettingsKeys.LLM_CLARIFIED_QUESTION_ENABLED, Kind.BOOL,   0,   0,    0,    "settings.item.clarified-question-enabled"),
            new Spec(SettingsKeys.LLM_FOLLOW_UP_QUESTIONS_ENABLED, Kind.BOOL,  0,   0,    0,    "settings.item.follow-up-questions-enabled"),
            new Spec(SettingsKeys.LLM_FOLLOW_UP_QUESTIONS_COUNT,  Kind.INT,
                    MIN_FOLLOW_UP_COUNT, MAX_FOLLOW_UP_COUNT, 1, "settings.item.follow-up-questions-count"),
            new Spec(SettingsKeys.LLM_SHRINK_STEP,                Kind.INT,    1,   10,   1,    "settings.item.shrink-step"),
            new Spec(SettingsKeys.LLM_MAX_TOKENS,                 Kind.INT,
                    AppProperties.MIN_MAX_TOKENS, AppProperties.MAX_MAX_TOKENS, 500, "settings.item.max-tokens")
    );

        // Insertion order = render order in the "UI" group. Apply on next page render.
        private static final List<Spec> UI_HOT_SPECS = List.of(
            new Spec(SettingsKeys.UI_SOURCE_PREVIEW_ENABLED,      Kind.BOOL,   0,   0,    0,    "settings.item.source-preview-enabled"),
            new Spec(SettingsKeys.UI_RETRIEVAL_METRICS_ENABLED,   Kind.BOOL,   0,   0,    0,    "settings.item.retrieval-metrics-enabled")
        );

    /** 로그 수준의 허용 값 — 화면에 이 모양(대문자) 그대로 저장·표시된다. */
    static final List<String> LOG_LEVEL_CHOICES = List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");

    // 운영 — 로그 수준은 저장 즉시(LoggingSystem 에 직접 건다), DB 백업 둘은 다음 정기 실행·다음 정리부터(DatabaseBackupService 가
    // 쓸 때마다 다시 읽는다). cron·폴더는 스케줄러·경로가 기동 때 굳어 여기에 없다.
    private static final List<Spec> OPERATIONS_HOT_SPECS = List.of(
            new Spec(SettingsKeys.LOGGING_LEVEL,  Kind.CHOICE, 0, 0,   0, "settings.item.logging-level", LOG_LEVEL_CHOICES),
            new Spec(SettingsKeys.BACKUP_ENABLED, Kind.BOOL,   0, 0,   0, "settings.item.backup-enabled"),
            new Spec(SettingsKeys.BACKUP_KEEP,    Kind.INT,    1, 365, 1, "settings.item.backup-keep")
    );

    private static final Map<String, Spec> SPECS;
    static {
        Map<String, Spec> m = new LinkedHashMap<>();
        for (Spec s : SEARCH_HOT_SPECS) m.put(s.key(), s);
        for (Spec s : INDEXING_HOT_SPECS) m.put(s.key(), s);
        for (Spec s : CONVERSION_HOT_SPECS) m.put(s.key(), s);
        for (Spec s : LLM_HOT_SPECS) m.put(s.key(), s);
        for (Spec s : UI_HOT_SPECS) m.put(s.key(), s);
        for (Spec s : OPERATIONS_HOT_SPECS) m.put(s.key(), s);
        // §6.29 — 호출 지점별 생각 수준. 키는 ThinkingSite 가 만든다(사이트를 더하면 여기도 저절로 는다). 일반 항목 격자(*_HOT_SPECS)에는
        // 넣지 않는다 — 이 키들의 유일한 편집 자리는 생각 수준 카드다.
        for (ThinkingSite site : ThinkingSite.values()) {
            m.put(site.settingsKey(), new Spec(site.settingsKey(), Kind.CHOICE, 0, 0, 0,
                    "settings.thinking.site." + site.id(), THINKING_CHOICES));
        }
        SPECS = Map.copyOf(m);
    }

    private final SettingsOverrideRepository repo;
    private final AppProperties props;
    private final AuditLogger audit;
    private final CircuitBreaker circuitBreaker;
    private final ProviderToggle providerToggle;
    private final ProviderContextWindows contextWindows;
    /** §6.15 — only for the read-only 저장 사용량 row; nothing on this page edits the cap. */
    private final StorageQuotaService storageQuotaService;
    /** §6.29 — 프로바이더 표의 "생각 제어" 열이 읽는 dialect·거부 기억. */
    private final ProviderThinkingDialects thinkingDialects;
    /** 프로바이더 표의 "상태" 열이 읽는, 서버에 실제로 물어본 마지막 결과. */
    private final ProviderConnectivity connectivity;
    /** 화면의 프로바이더 편집 — 행마다 출처·재기동 대기·기동 검증 실패를 붙인다. {@code null} = 편집 없는 구성(테스트). */
    private final ProviderCatalog providerCatalog;
    /** 교차 인코더 리랭커(§6.33) — 상태 배지만 읽는다. 빈이 없으면 {@code null}. */
    private CrossEncoderReranker crossEncoder;

    /**
     * 기동 때의 설정값(로그 수준의 기본값 · 백업 기본값 · 조회 전용 행)을 읽는 곳. 스프링이 {@link #setEnvironment} 로 넣는다 —
     * 생성자가 테스트용으로 여럿이라 거기에 더하지 않았다. {@code null}(생성자를 직접 부르는 테스트)이면 각 값의 기본값을 쓴다.
     */
    private Environment environment;

    /** Persisted overrides, cached so the {@link #get} hot path never hits SQLite. */
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /** 생각 제어 열을 쓰지 않는 호출부(테스트)를 위한 축약 — 기록이 없는 프로바이더는 그 열에 "서버 설정 사용" 으로 나온다. */
    public SettingsService(SettingsOverrideRepository repo, AppProperties props,
                           AuditLogger audit, CircuitBreaker circuitBreaker,
                           ProviderToggle providerToggle, ProviderContextWindows contextWindows,
                           StorageQuotaService storageQuotaService) {
        this(repo, props, audit, circuitBreaker, providerToggle, contextWindows, storageQuotaService,
                new ProviderThinkingDialects());
    }

    /** 접속 확인을 쓰지 않는 호출부(테스트)를 위한 축약 — 한 번도 묻지 않았으니 설정이 갖춰진 프로바이더는 "확인 중" 으로 나온다. */
    public SettingsService(SettingsOverrideRepository repo, AppProperties props,
                           AuditLogger audit, CircuitBreaker circuitBreaker,
                           ProviderToggle providerToggle, ProviderContextWindows contextWindows,
                           StorageQuotaService storageQuotaService, ProviderThinkingDialects thinkingDialects) {
        this(repo, props, audit, circuitBreaker, providerToggle, contextWindows, storageQuotaService,
                thinkingDialects, new ProviderConnectivity());
    }

    /** 프로바이더 편집을 쓰지 않는 호출부(테스트)를 위한 축약 — 모든 행이 properties 그대로로 그려진다. */
    public SettingsService(SettingsOverrideRepository repo, AppProperties props,
                           AuditLogger audit, CircuitBreaker circuitBreaker,
                           ProviderToggle providerToggle, ProviderContextWindows contextWindows,
                           StorageQuotaService storageQuotaService, ProviderThinkingDialects thinkingDialects,
                           ProviderConnectivity connectivity) {
        this(repo, props, audit, circuitBreaker, providerToggle, contextWindows, storageQuotaService,
                thinkingDialects, connectivity, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SettingsService(SettingsOverrideRepository repo, AppProperties props,
                           AuditLogger audit, CircuitBreaker circuitBreaker,
                           ProviderToggle providerToggle, ProviderContextWindows contextWindows,
                           StorageQuotaService storageQuotaService, ProviderThinkingDialects thinkingDialects,
                           ProviderConnectivity connectivity, ProviderCatalog providerCatalog) {
        this.providerCatalog = providerCatalog;
        this.connectivity = connectivity;
        this.thinkingDialects = thinkingDialects;
        this.repo = repo;
        this.props = props;
        this.audit = audit;
        this.circuitBreaker = circuitBreaker;
        this.providerToggle = providerToggle;
        this.contextWindows = contextWindows;
        this.storageQuotaService = storageQuotaService;
    }

    @PostConstruct
    void init() {
        cache.putAll(repo.findAll());
        // Capture each override key's effective value BEFORE binding the override source: with nothing
        // bound yet, xxxSafe() returns the pure env-var/application.properties value. Comparing it to
        // the post-bind value (override applied) is how warnOnDivergingOverrides() surfaces exactly the
        // keys where a persisted /settings override silently wins over what the operator configured.
        Map<String, String> baseValues = new LinkedHashMap<>();
        for (String key : cache.keySet()) {
            if (SPECS.containsKey(key)) baseValues.put(key, effectiveValue(key));
        }
        AppProperties.bindOverrides(this);
        if (cache.containsKey(SettingsKeys.LOGGING_LEVEL)) applyLogLevel();   // 저장된 로그 수준은 재기동 뒤에도 이긴다
        log.info("[SETTINGS] runtime override layer bound — {} override(s) loaded: {}",
                cache.size(), cache.keySet());
        warnOnDivergingOverrides(baseValues);
    }

    /**
     * Logs a WARN for every persisted override whose effective value differs from what the
     * environment variable / {@code application.properties} would otherwise supply. A persisted
     * override always wins (see {@code AppProperties.xxxSafe()}), so without this an operator who set
     * e.g. {@code SEARCH_TOP_K=10} via an env var has no runtime signal that a stored {@code /settings}
     * override of 7 is what actually takes effect. Comparison is on the post-clamp effective value, so
     * an override that clamps to the same number as the env value is (correctly) not flagged.
     *
     * @param baseValues each override key's effective value captured while the override source was
     *                   not yet bound (i.e. the env-var/properties value)
     */
    private void warnOnDivergingOverrides(Map<String, String> baseValues) {
        baseValues.forEach((key, base) -> {
            String effective = effectiveValue(key);
            if (!effective.equals(base)) {
                log.warn("[SETTINGS] '{}' — a persisted /settings override ({}) is overriding the "
                        + "env-var/application.properties value ({}); the override wins. Reset it in "
                        + "/settings to fall back to the configured value.", key, effective, base);
            }
        });
    }

    @PreDestroy
    void shutdown() {
        AppProperties.unbindOverrides();
    }

    // ── AppProperties.OverrideSource ─────────────────────────────────────────

    @Override
    public String get(String key) {
        return cache.get(key);
    }

    // ── Mutations ────────────────────────────────────────────────────────────

    /**
     * Validates and persists an override for a hot-editable key, then returns the fresh effective
     * value. Rejects unknown/non-editable keys and out-of-range values with
     * {@link IllegalArgumentException} (→ {@code GlobalExceptionHandler} 400). Audited.
     */
    public String update(String key, String rawValue) {
        Spec spec = SPECS.get(key);
        if (spec == null) {
            throw new IllegalArgumentException("수정할 수 없는 설정 키입니다: " + key);
        }
        String canonical = validateAndCanonicalize(spec, rawValue);
        String before = effectiveValue(key);
        repo.upsert(key, canonical);
        cache.put(key, canonical);
        if (SettingsKeys.LOGGING_LEVEL.equals(key)) applyLogLevel();
        String after = effectiveValue(key);
        audit.log("settings.update", key, Map.of("from", before, "to", after));
        log.info("[SETTINGS] override set: {} = {} (was {})", key, after, before);
        return after;
    }

    /** 이 키에 {@code /settings} 오버라이드가 있는가 — 생각 수준 카드의 "오버라이드됨" 배지와 [기본값] 버튼이 읽는다. */
    public boolean isOverridden(String key) {
        return cache.containsKey(key);
    }

    /** Removes an override, reverting the key to its property default. No-op-safe. Audited. */
    public void reset(String key) {
        Spec spec = SPECS.get(key);
        if (spec == null) {
            throw new IllegalArgumentException("알 수 없는 설정 키입니다: " + key);
        }
        String before = effectiveValue(key);
        boolean had = cache.containsKey(key);
        repo.delete(key);
        cache.remove(key);
        if (SettingsKeys.LOGGING_LEVEL.equals(key)) applyLogLevel();
        String after = effectiveValue(key);
        if (had) {
            audit.log("settings.reset", key, Map.of("from", before, "to", after));
            log.info("[SETTINGS] override cleared: {} → {} (property default)", key, after);
        }
    }

    private String validateAndCanonicalize(Spec spec, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("값이 비어 있습니다.");
        }
        String v = raw.trim();
        return switch (spec.kind()) {
            case BOOL -> {
                if (v.equalsIgnoreCase("true"))  yield "true";
                if (v.equalsIgnoreCase("false")) yield "false";
                throw new IllegalArgumentException("true 또는 false 여야 합니다: " + raw);
            }
            case INT -> {
                int n;
                try { n = Integer.parseInt(v); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("정수여야 합니다: " + raw); }
                if (n < spec.min() || n > spec.max()) {
                    throw new IllegalArgumentException(
                            "허용 범위 [%d, %d] 를 벗어났습니다: %d".formatted((long) spec.min(), (long) spec.max(), n));
                }
                yield Integer.toString(n);
            }
            case CHOICE -> {
                // 대소문자는 가리지 않고, 저장은 목록에 적힌 모양 그대로(생각 수준은 소문자, 로그 수준은 대문자)
                yield spec.choices().stream().filter(c -> c.equalsIgnoreCase(v)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "허용 값은 %s 입니다: %s".formatted(String.join(" / ", spec.choices()), raw)));
            }
            case DOUBLE -> {
                double d;
                try { d = Double.parseDouble(v); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("숫자여야 합니다: " + raw); }
                if (d < spec.min() || d > spec.max()) {
                    throw new IllegalArgumentException(
                            "허용 범위 [%s, %s] 를 벗어났습니다: %s".formatted(trimNum(spec.min()), trimNum(spec.max()), trimNum(d)));
                }
                yield trimNum(d);
            }
        };
    }

    // ── View ─────────────────────────────────────────────────────────────────


    /**
     * Full {@code /settings} model with overrides already applied.
     *
     * <p>프로바이더의 상태 열은 <b>늘 "확인 중"으로 시작한다</b> — 이 화면을 열 때마다 서버에 새로 물어야 하는데(마지막 확인이
     * 수 분 전의 것일 수 있다) 물어보는 동안 페이지가 막히면 안 된다. 결과는 {@link #refreshProviderConnections()} 가 칸만
     * 바꿔 끼운다.
     */
    public SettingsView buildView() {
        List<ProviderRow> providers = providerRows(true);

        // 화면 순서 그대로다: 편집 가능한 그룹(LLM 튜닝 → UI → 검색 튜닝 → 인덱싱/청킹)이 먼저, 조회 전용(재기동 필요)
        // 그룹이 나중이다. 템플릿은 두 무리 사이에 생각 수준 카드를 끼운다(SettingsView.editableGroups()/readOnlyGroups()).
        List<SettingGroup> groups = List.of(
                new SettingGroup("llm_hot", "settings.group.llm_hot", llmHotItems()),
                new SettingGroup("ui_hot", "settings.group.ui_hot", uiHotItems()),
                new SettingGroup("search_hot", "settings.group.search_hot", searchHotItems()),
                new SettingGroup("indexing", "settings.group.indexing", indexingItems()),
                new SettingGroup("conversion", "settings.group.conversion", conversionItems()),
                new SettingGroup("operations", "settings.group.operations", operationsItems()),
                new SettingGroup("connection", "settings.group.connection", connectionItems()),
                new SettingGroup("security", "settings.group.security", securityItems()),
                new SettingGroup("search_fixed", "settings.group.search_fixed", fixedSearchItems()),
                new SettingGroup("cache", "settings.group.cache", cacheItems())
        );

        AppProperties.LlmConfig llm = props.llmSafe();
        Integer dim = props.embeddingSafe().dimensions();
        return new SettingsView(
                providers,
                llm.defaultRoutingMode(),
                trimNum(llm.temperature()),   // general/RAG temperature — now the real effective value
                Integer.toString(llm.maxTokens()),
                embeddingModelLabel(),
                embeddingUrls().isEmpty() ? "-" : String.join(" ; ", embeddingUrls()),
                dim != null ? dim.toString() : "auto",
                props.vectorStoreSafe().type(),
                groups,
                rerankerModelLabel(),
                props.rerankerEnabled() ? String.join(" ; ", props.rerankerBaseUrls()) : "-",
                // 프로바이더 표와 같이 화면을 열 때는 늘 "확인 중" 으로 시작하고 /settings/llm-status 가 채운다
                endpointRows(ProviderConnectivity.EMBEDDING_KEY, embeddingUrls(), true),
                endpointRows(ProviderConnectivity.RERANKER_KEY, props.rerankerBaseUrls(), true));
    }

    /**
     * Current provider rows (config + circuit-breaker block + operator enable/disable state). Shared by
     * {@link #buildView()} and the toggle endpoint's HTMX fragment so both render the same table.
     *
     * <p>In a {@code LOCAL_ONLY} deployment (see {@link #isLocalOnlyDeployment()}), NORMAL/PREMIUM rows
     * are filtered out — routing never selects them in that mode, so listing them on the settings page
     * would just be confusing config-vs-reality noise (a NORMAL/PREMIUM entry can still be present in
     * {@code application.properties} even when the deployment is pinned to LOCAL_ONLY, e.g. kept around
     * for a future mode switch).
     *
     * <p>상태 열은 <b>마지막으로 서버에 물어본 결과</b>를 나이와 무관하게 쓴다 — 토글·재탐지 뒤에 돌아오는 표 조각이 클릭 한 번에
     * 상태를 "확인 중" 으로 되돌리면 안 된다. 한 번도 안 물었으면 "확인 중" 이다.
     */
    public List<ProviderRow> providerRows() {
        return providerRows(false);
    }

    /**
     * @param recheck {@code true} 면 마지막 확인을 무시하고 설정이 갖춰진 프로바이더를 모두 "확인 중" 으로 둔다 — 화면을 새로
     *                열 때다(그 뒤 {@link #refreshProviderConnections()} 가 채운다)
     */
    private List<ProviderRow> providerRows(boolean recheck) {
        Map<String, Instant> blocked = circuitBreaker.getBlockedProviders();
        List<ProviderRow> rows = new ArrayList<>(visibleProviders().stream()
                .map(cfg -> {
                    Instant until = blocked.get(cfg.name());
                    return new ProviderRow(
                            cfg.name(),
                            cfg.role() != null ? cfg.role().toUpperCase() : "NORMAL",
                            cfg.priority(),
                            cfg.model(),
                            cfg.baseUrl(),
                            cfg.isEnabled(), // real registration gate — see ProviderRow#configured javadoc
                            until != null,
                            until != null ? until.toString() : null,
                            providerToggle.isEnabled(cfg.name()),
                            contextWindowLabel(cfg.name()),
                            providerThinking(cfg),
                            connectionOf(cfg, recheck),
                            providerCatalog == null ? null : providerCatalog.editOf(cfg.name()));
                })
                .toList());
        // 재기동하면 추가될, 아직 돌지 않는 프로바이더 — 맨 아래에 흐리게. 상태·토글·생각 제어 칸은 비운다(그릴 사실이 없다).
        if (providerCatalog != null) {
            boolean localOnly = isLocalOnlyDeployment();
            for (AppProperties.ProviderConfig cfg : providerCatalog.pendingAdditions()) {
                if (localOnly && !cfg.isLocal()) continue;
                rows.add(new ProviderRow(cfg.name(), cfg.role() != null ? cfg.role().toUpperCase() : "NORMAL",
                        cfg.priority(), cfg.model(), cfg.baseUrl(), false, false, null, false,
                        cfg.declaredContextSize() == null ? CONTEXT_UNKNOWN_LABEL
                                : "%,d (%s)".formatted(cfg.declaredContextSize(), "수동 입력"),
                        null, ProviderConnection.notConfigured(), providerCatalog.editOf(cfg.name())));
            }
        }
        return rows;
    }

    /**
     * 상태 열의 한 칸 — 미설정(서버를 만들지 못했다) / 확인 중 / 접속불가 / 정상. "미설정" 은 {@code cfg.isEnabled()} 가 정하고,
     * 나머지는 서버에 물어본 결과다. 서킷 브레이커의 차단은 이 칸에 섞지 않는다 — 별개의 사실(라우터가 지금 건너뛴다)이라 표가
     * {@code blocked} 로 따로 표시한다.
     */
    private ProviderConnection connectionOf(AppProperties.ProviderConfig cfg, boolean recheck) {
        if (!cfg.isEnabled()) return ProviderConnection.notConfigured();
        if (recheck) return ProviderConnection.pending();
        return connectivity.last(cfg.name())
                .map(r -> r.reachable()
                        ? ProviderConnection.reachable(r.latencyMs(), r.modelListed()).withServedModel(r.servedModel())
                        : ProviderConnection.unreachable(r.error()))
                .orElseGet(ProviderConnection::pending);
    }

    /**
     * 설정이 갖춰진 프로바이더에 <b>지금</b> 모델 목록 API 로 접속해 보고, 결과를 반영한 표의 행을 돌려준다
     * ({@code GET /settings/llm-status} 가 상태 칸만 바꿔 끼운다). 서버마다 병렬로 묻고 응답이 없는 서버는 연결 타임아웃
     * (3초)에서 포기한다. 몇 초 안에 끝난 확인은 다시 하지 않는다 — 게스트에게 열린 화면에서 불리므로 새로 고침이 서버를
     * 두드리는 수단이 되면 안 된다({@link ProviderConnectivity}).
     */
    public List<ProviderRow> refreshProviderConnections() {
        connectivity.refresh(visibleProviders().stream().filter(AppProperties.ProviderConfig::isEnabled).toList());
        connectivity.refreshEndpoints(auxEndpoints());
        return providerRows();
    }

    private List<ProviderConnectivity.Endpoint> auxEndpoints() {
        AppProperties.EmbeddingConfig e = props.embeddingSafe();
        AppProperties.RerankerConfig r = props.rerankerSafe();
        return List.of(
                new ProviderConnectivity.Endpoint(ProviderConnectivity.EMBEDDING_KEY, embeddingUrls(), e.apiKey(), e.model()),
                new ProviderConnectivity.Endpoint(ProviderConnectivity.RERANKER_KEY, props.rerankerBaseUrls(), r.apiKey(), r.model()));
    }

    /** 임베딩 서버 주소 전부(EMBED_BASE_URLS 의 {@code ;} 목록) — 주소가 없으면 빈 목록. */
    private List<String> embeddingUrls() {
        AppProperties.EmbeddingConfig e = props.embeddingSafe();
        List<String> urls = new ArrayList<>();
        if (e.baseUrl() != null && !e.baseUrl().isBlank()) urls.add(e.baseUrl());
        if (e.additionalBaseUrls() != null) urls.addAll(e.additionalBaseUrls());
        return urls;
    }

    /** 임베딩·리랭커 줄의 모델 칸에 쓰는 이름(설정값) — 화면과 상태 응답({@code /settings/llm-status})이 같은 값을 그린다. */
    public String embeddingModelLabel() {
        return nullToDash(props.embeddingSafe().model());
    }

    /** 리랭커가 꺼져 있으면 {@code -}. */
    public String rerankerModelLabel() {
        return props.rerankerEnabled() ? props.rerankerSafe().model() : "-";
    }

    /** 임베딩 서버 주소별 마지막 접속 확인 — 주소가 없으면 미설정 한 줄, 아직 안 물었으면 확인 중. */
    public List<SettingsView.EndpointRow> embeddingEndpoints() {
        return endpointRows(ProviderConnectivity.EMBEDDING_KEY, embeddingUrls(), false);
    }

    /** 리랭커 서버 주소별 마지막 접속 확인 — 주소가 없으면(꺼짐) 미설정 한 줄. */
    public List<SettingsView.EndpointRow> rerankerEndpoints() {
        return endpointRows(ProviderConnectivity.RERANKER_KEY, props.rerankerBaseUrls(), false);
    }

    /** @param pending {@code true} 면 마지막 확인을 무시하고 "확인 중" 으로 둔다(화면을 새로 열 때) */
    private List<SettingsView.EndpointRow> endpointRows(String key, List<String> urls, boolean pending) {
        if (urls == null || urls.isEmpty()) {
            return List.of(new SettingsView.EndpointRow("-", SettingsView.ProviderConnection.notConfigured()));
        }
        List<SettingsView.EndpointRow> rows = new ArrayList<>();
        for (String u : urls) {
            SettingsView.ProviderConnection c = pending ? SettingsView.ProviderConnection.pending()
                    : connectivity.lastEndpoint(key, u)
                            .map(r -> r.reachable()
                                    ? SettingsView.ProviderConnection.reachable(r.latencyMs(), r.modelListed()).withServedModel(r.servedModel())
                                    : SettingsView.ProviderConnection.unreachable(r.error()))
                            .orElseGet(SettingsView.ProviderConnection::pending);
            rows.add(new SettingsView.EndpointRow(u, c));
        }
        return rows;
    }

    /**
     * 프로바이더 표의 "생각 제어" 열 — 실제 전송과 같은 값을 읽는다: 설정값은 {@code ProviderConfig}, 실제로 쓰는 값은
     * {@link ProviderThinkingDialects}(기동 시 {@code LlmConfig} 가 {@code AUTO} 를 풀어 기록한 것), 거부는 그 기억이다.
     * 기록이 없는 프로바이더(등록되지 않은 것)는 "아무것도 싣지 않는다"로 나온다 — {@code dialectOf} 가 쓰는 규칙과 같다.
     */
    private ProviderThinking providerThinking(AppProperties.ProviderConfig cfg) {
        // 등록되지 않은 프로바이더(키·주소가 없어 꺼진 것)에는 기록 자체가 없다 — "아무것도 싣지 않는다"로 적으면 그 프로바이더가
        // 생각 제어를 못 받는 서버인 것처럼 읽힌다. 말할 것이 없으면 칸을 비운다.
        if (!cfg.isEnabled()) return null;
        ThinkingDialect resolved = thinkingDialects.dialectOf(cfg.name());
        return new ProviderThinking(cfg.thinkingDialectOrAuto(), resolved, resolved.support(), resolved.field(),
                thinkingDialects.rejectedFields(cfg.name()));
    }

    // ── 컨텍스트 창 재탐지 (§6.26 A5) ─────────────────────────────────────────

    /**
     * 한 프로바이더의 재탐지 결과 한 줄.
     *
     * @param before {@code contextWindowLabel()} 형식의 이전 값 — 무엇이 바뀌었는지 보이려면 새 값만으로는 부족하다
     * @param outcome 화면 배지와 문구를 가르는 값
     * @param belowSupported 이 줄이 끝난 뒤 기록된 창이 지원하는 최소(16K)보다 작은가 — 결과가 무엇이든(선언·실패 포함)
     *                       지금 기록된 값으로 판정한다. 버튼을 누른 운영자는 로그를 보지 않을 수 있어서 화면에도 띄운다
     */
    public record ReprobeRow(String provider, String before, String after, ReprobeOutcome outcome,
                             boolean belowSupported) {}

    /** 재탐지 한 번의 결과 전체. {@code restartNeeded} 는 아래 {@link #reprobeContextWindows()} 참고. */
    public record ReprobeResult(List<ReprobeRow> rows, boolean restartNeeded, String restartDetail) {}

    public enum ReprobeOutcome {
        /** 값이 실제로 달라졌다 — 다음 호출부터 새 예산이 적용된다. */
        UPDATED,
        /** 물어봤고 답을 받았는데 예전과 같다. */
        UNCHANGED,
        /** 서버가 답하지 않았거나 로드된 컨텍스트를 못 찾았다. <b>이전 값은 그대로 둔다.</b> */
        FAILED,
        /** {@code context-size} 로 선언된 프로바이더 — 선언이 탐지를 이기므로 묻지 않는다. */
        SKIPPED_DECLARED
    }

    /**
     * 등록된 LOCAL 프로바이더에게 컨텍스트 창을 <b>지금</b> 다시 물어본다 (§6.26 A5, 선택지 D).
     *
     * <p><b>왜 자동이 아니라 버튼인가.</b> 주기적으로 다시 물으면 같은 질문이 시각에 따라 다른 양의
     * 근거를 받는다 — {@code TokenEstimateCalibration} 이 "관측만 하고 예산을 자동 보정하지 않는다"고
     * 정한 것과 같은 이유로, 예산이 스스로 움직이면 재현이 안 된다. 창을 바꾼 사람은 자기가 바꿨다는
     * 것을 알고 있으므로, 값이 갱신되는 시점을 그 사람이 정하게 하는 편이 정직하다. 감사 로그에도
     * 누가 언제 눌렀는지가 남는다.
     *
     * <p><b>실패는 이전 값을 지우지 않는다.</b> 서버가 잠깐 응답하지 않는 것과 창이 사라진 것은 다르고,
     * 알던 값을 "모름"으로 되돌리면 그 순간부터 입력 예산이 통째로 꺼진다 — 고치러 누른 버튼이 상황을
     * 악화시키는 셈이다.
     *
     * <p><b>선언된 창은 건드리지 않는다.</b> {@code context-size} 는 의도이고 탐지는 관측이라, 관측이
     * 의도를 덮어쓰면 운영자가 못 박은 값이 버튼 한 번에 사라진다. 그런 행은 결과 표에
     * {@link ReprobeOutcome#SKIPPED_DECLARED} 로 남겨 "안 물어봤다"는 사실 자체를 보여 준다.
     *
     * <p><b>출력 상한은 이제 따라온다</b>(§6.26 A6) — {@code MaxTokensCappingChatModel} 이 호출마다
     * {@code LlmConfig.liveMaxTokens()} 를 불러 현재 창과 현재 {@code app.llm.max-tokens} 로 다시
     * 계산하므로, 옵션을 싣는 호출부(이 앱의 모든 블로킹 호출)는 재탐지 직후부터 새 값을 쓴다.
     *
     * <p><b>딱 한 곳이 남는다.</b> 프로바이더 빈의 {@code defaultOptions} 는 기동 시점 값 그대로이고,
     * 이건 <b>옵션을 실어 보내지 않는</b> 프레임워크 내부 호출자(예: MultiQuery 확장기)의 폴백이다.
     * 그 경로는 캡 데코레이터가 손댈 옵션 자체가 없어 지나가므로, 창이 <b>줄어들어</b> 그 기동값이
     * 새 창을 넘게 되면 그 호출만 컨텍스트 초과가 난다 — 그때만 {@code restartNeeded} 가 켜진다.
     * 창이 넓어진 경우는 기동값이 작을 뿐 안전하므로 알리지 않는다(알림이 잦으면 아무도 안 읽는다).
     */
    public ReprobeResult reprobeContextWindows() {
        AppProperties.LlmConfig llm = props.llmSafe();
        List<ReprobeRow> rows = new ArrayList<>();
        List<String> restartReasons = new ArrayList<>();

        for (AppProperties.ProviderConfig cfg : visibleProviders()) {
            if (!cfg.isEnabled() || !cfg.isLocal()) continue;   // 클라우드는 이 엔드포인트가 없다
            String before = contextWindowLabel(cfg.name());
            // 기록된 출처로 가른다 — 기동 때 굳은 설정(cfg)이 아니라. 건너뛰는 것은 context-size 선언뿐이다: 화면에서 넣은 수동
            // 컨텍스트(MANUAL)는 탐지가 안 될 때의 대체값이라 여기서 탐지가 성공하면 덮고, 실패하면 그대로 둔다(ProviderCatalog).
            // 기동 때 굳은 cfg 에는 수동 값이 contextSize 로 섞여 있으므로 cfg.declaredContextSize() 로 가르면 안 된다.
            boolean declaredNow = contextWindows.find(cfg.name()).map(w -> w.source().declared()).orElse(false);
            if (declaredNow) {
                rows.add(new ReprobeRow(cfg.name(), before, before, ReprobeOutcome.SKIPPED_DECLARED,
                        belowSupported(cfg.name())));
                continue;
            }
            int old = contextWindows.tokensOrZero(cfg.name());
            Integer found = ContextWindowProbe.probe(cfg.apiBase(), cfg.model(),
                    llm.connectTimeoutSeconds(), llm.readTimeoutSeconds()).orElse(null);
            if (found == null) {
                rows.add(new ReprobeRow(cfg.name(), before, before, ReprobeOutcome.FAILED,
                        belowSupported(cfg.name())));
                continue;
            }
            contextWindows.record(cfg.name(), found, ProviderContextWindows.Source.PROBED);
            ProviderContextWindows.warnIfBelowSupportedMinimum(cfg.name(), found,
                    ProviderContextWindows.Source.PROBED, "재탐지");
            String after = contextWindowLabel(cfg.name());
            boolean changed = found != old;
            rows.add(new ReprobeRow(cfg.name(), before, after,
                    changed ? ReprobeOutcome.UPDATED : ReprobeOutcome.UNCHANGED, belowSupported(cfg.name())));

            if (changed) {
                int requested = (cfg.maxTokens() != null && cfg.maxTokens() > 0)
                        ? cfg.maxTokens() : llm.maxTokens();
                int baked = ProviderContextWindows.cappedMaxTokens(requested, old > 0 ? old : null);
                if (ProviderContextWindows.cappedMaxTokens(baked, found) != baked) {
                    restartReasons.add("%s: defaultOptions %,d ≥ 창 %,d".formatted(cfg.name(), baked, found));
                }
            }
        }

        long updated = rows.stream().filter(r -> r.outcome() == ReprobeOutcome.UPDATED).count();
        audit.log("settings.context-window.reprobe", "all", Map.of(
                "probed", Integer.toString(rows.size()),
                "updated", Long.toString(updated)));
        return new ReprobeResult(rows, !restartReasons.isEmpty(), String.join(", ", restartReasons));
    }

    /** 지금 기록된 이 프로바이더의 창이 지원하는 최소(16K)보다 작은가 — 모르면 {@code false}. */
    private boolean belowSupported(String providerName) {
        return ProviderContextWindows.belowSupportedMinimum(contextWindows.tokensOrZero(providerName));
    }

    /**
     * {@code "8,192 (탐지됨)"} 처럼 값과 <b>출처</b>를 함께 적는다 — 운영자가 직접 적은 숫자인지 앱이
     * 서버에게 물어본 숫자인지가 신뢰도를 가른다. 탐지값은 기동 시점의 관측이라 모델을 다른 크기로
     * 다시 로드하면 낡고, 그때 운영자가 {@code context-size} 로 못 박으면 이 칸이 (설정됨)으로 바뀐다.
     *
     * <p>모르면 {@link #CONTEXT_UNKNOWN_LABEL} 이다. 이것도 정보다 — 컨텍스트 초과가 나는데 이 칸이 미탐지라면
     * 앱이 창 크기를 모르는 상태이므로 어떤 보정도 돌지 않았다는 뜻이다. <b>숫자를 지어 보이지 않는다</b>: 앱 내부에
     * "모를 때 쓰는 기본 창"은 없다(추측한 숫자로 입력 예산을 짜면 멀쩡한 프롬프트를 자르거나 초과를 못 막는다) —
     * 모르면 창 기준의 사전 축소·출력 예약 보정이 꺼진 채 설정된 {@code max-tokens} 그대로 나간다.
     */
    private String contextWindowLabel(String providerName) {
        return contextWindows.find(providerName)
                .map(w -> "%,d (%s)".formatted(w.tokens(), switch (w.source()) {
                    case CONFIGURED -> "설정됨";
                    case MANUAL -> "수동 입력";
                    case PROBED -> "탐지됨";
                }))
                .orElse(CONTEXT_UNKNOWN_LABEL);
    }

    /** 컨텍스트 창을 모를 때 프로바이더 표 칸에 적는 문구 — 값이 아니라 "창 기준 보정이 꺼져 있다"는 사실이다. */
    static final String CONTEXT_UNKNOWN_LABEL = "미탐지 (입력 제한 없음)";

    /** {@code app.llm.default-routing-mode} pinned to {@code LOCAL_ONLY} for this whole deployment. */
    private boolean isLocalOnlyDeployment() {
        return "LOCAL_ONLY".equals(props.llmSafe().defaultRoutingMode());
    }

    /**
     * All configured providers, or only the {@code LOCAL}-role ones when this deployment is
     * {@code LOCAL_ONLY} (see {@link #providerRows()}). Shared with {@link #setProviderEnabled(String,
     * boolean)} so a hidden NORMAL/PREMIUM provider can't be toggled through the endpoint either — if
     * it's not on the page, it's not "known" to it.
     */
    private List<AppProperties.ProviderConfig> visibleProviders() {
        List<AppProperties.ProviderConfig> all = props.llmSafe().providers();
        if (!isLocalOnlyDeployment()) {
            return all;
        }
        return all.stream()
                .filter(cfg -> "LOCAL".equalsIgnoreCase(cfg.role()))
                .toList();
    }

    /**
     * Enables/disables a registered provider at runtime (§A, in-memory — resets on restart). Rejects an
     * unknown provider name, and refuses to disable the last still-enabled provider (which would leave
     * routing with nothing to select). Audited. Returns the refreshed rows for the HTMX table swap.
     *
     * <p>Keyed by name: a load-balanced pair sharing a name toggles together (see {@link ProviderToggle}).
     */
    public List<ProviderRow> setProviderEnabled(String name, boolean enabled) {
        List<AppProperties.ProviderConfig> providers = visibleProviders();
        boolean known = providers.stream().anyMatch(c -> name.equals(c.name()));
        if (!known) {
            throw new IllegalArgumentException("알 수 없는 프로바이더입니다: " + name);
        }
        if (!enabled) {
            long remaining = providers.stream()
                    .map(AppProperties.ProviderConfig::name)
                    .distinct()
                    .filter(n -> !n.equals(name) && providerToggle.isEnabled(n))
                    .count();
            if (remaining == 0) {
                throw new IllegalArgumentException(
                        "마지막으로 활성화된 프로바이더는 비활성화할 수 없습니다: " + name);
            }
        }
        boolean was = providerToggle.isEnabled(name);
        providerToggle.setEnabled(name, enabled);
        if (was != enabled) {
            audit.log("settings.provider.toggle", name, Map.of("enabled", Boolean.toString(enabled)));
            log.info("[SETTINGS] provider [{}] {} at runtime (in-memory — resets to config on restart)",
                    name, enabled ? "ENABLED" : "DISABLED");
        }
        return providerRows();
    }

    /** One editable item for {@code key} — used by both the page and the post-update HTMX fragment. */
    public SettingItem editableItem(String key) {
        Spec spec = SPECS.get(key);
        if (spec == null) throw new IllegalArgumentException("알 수 없는 설정 키입니다: " + key);
        boolean bool = spec.kind() == Kind.BOOL;
        boolean choice = spec.kind() == Kind.CHOICE;
        return new SettingItem(
                spec.key(),
                spec.labelKey(),
                effectiveValue(spec.key()),
                bool ? "bool" : choice ? "choice" : "number",
                true,
                cache.containsKey(spec.key()),
                null,
                bool || choice ? null : spec.min(),
                bool || choice ? null : spec.max(),
                bool || choice ? null : spec.step(),
                descriptionKey(spec.labelKey()),
                spec.choices());
    }

    /**
     * 항목 설명의 메시지 키 — 라벨 키 {@code settings.item.X} 에 짝지은 {@code settings.desc.X}. 라벨은 짧은 이름만 두고 "무엇을
     * 하는 값인가"는 라벨에 마우스를 올렸을 때 풍선으로 보인다(settings-item.html). 편집 가능한 일반 항목은 전부 설명을 가진다 —
     * 빠지면 화면에 키가 그대로 보이므로 {@code SettingsServiceTest} 가 번들 두 벌에 다 있는지 확인한다. 생각 수준(CHOICE)은 자기
     * 카드에서 따로 그려 여기에 오지 않는다.
     */
    static String descriptionKey(String labelKey) {
        return labelKey.startsWith("settings.item.")
                ? "settings.desc." + labelKey.substring("settings.item.".length())
                : null;
    }

    /** 이 화면의 편집 가능한 일반 항목 전부(검색 · 인덱싱 · LLM · 화면 표시) — 설명 키 확인용. */
    static List<String> hotItemLabelKeys() {
        List<String> keys = new ArrayList<>();
        for (List<Spec> specs : List.of(SEARCH_HOT_SPECS, INDEXING_HOT_SPECS, CONVERSION_HOT_SPECS, LLM_HOT_SPECS, UI_HOT_SPECS,
                OPERATIONS_HOT_SPECS)) {
            for (Spec spec : specs) keys.add(spec.labelKey());
        }
        return keys;
    }

    private List<SettingItem> searchHotItems() {
        List<SettingItem> items = new ArrayList<>(SEARCH_HOT_SPECS.size() + 2);
        for (Spec s : SEARCH_HOT_SPECS) {
            items.add(editableItem(s.key()));
            // §6.33 — 후보 수·최종 컷은 설정이 아니라 search-top-k 의 파생값이다. 고치는 곳(top-k) 바로 아래에 조회 전용으로 보인다.
            if (SettingsKeys.SEARCH_TOP_K.equals(s.key())) items.addAll(derivedRerankItems());
        }
        return items;
    }

    /**
     * 교차 인코더 리랭커가 동작할 때의 후보 수·최종 컷 — 검색({@code RetrievalService})이 쓰는 두 메서드의 값 그대로다
     * ({@link AppProperties#effectiveRerankCandidates()} · {@link AppProperties#effectiveRerankTopK()}). 리랭커가 꺼져 있거나
     * 잠시 건너뛰는 중이면 값은 그대로 보이되 "적용되지 않음" 배지가 붙는다.
     */
    private List<SettingItem> derivedRerankItems() {
        String note = rerankerNote();
        return List.of(
                readOnly("settings.item.rerank-candidates", Integer.toString(props.effectiveRerankCandidates()), note,
                        "settings.desc.rerank-candidates"),
                readOnly("settings.item.rerank-top-k", Integer.toString(props.effectiveRerankTopK()), note,
                        "settings.desc.rerank-top-k"));
    }

    /** 리랭커가 지금 검색에 쓰이지 않는 이유의 배지 키 — 쓰이고 있으면 {@code null}. */
    private String rerankerNote() {
        if (!props.rerankerEnabled()) return "settings.note.reranker-off";
        if (crossEncoder != null && crossEncoder.isSkipping()) return "settings.note.reranker-down";
        return null;
    }

    /** 리랭커 빈이 있을 때만 주입된다(없으면 {@code null}) — 생성자가 테스트용으로 여럿이라 세터로 받는다. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCrossEncoderReranker(CrossEncoderReranker crossEncoder) {
        this.crossEncoder = crossEncoder;
    }

    /** Only rerank-enabled remains read-only here — it's an {@code @ConditionalOnProperty} bean that
     *  can't be hot-swapped (topK / multiquery / hybrid moved to the hot group). */
    private List<SettingItem> fixedSearchItems() {
        return List.of(
                readOnly("settings.item.rerank-enabled", Boolean.toString(props.searchRerankEnabled()), null,
                        "settings.desc.rerank-enabled")
        );
    }

    /** Chunking + indexing concurrency: hot-editable but they apply on the NEXT indexing / ↺ re-index,
     *  not the next search (existing chunks are not re-split), hence a distinct group + note. */
    private List<SettingItem> indexingItems() {
        List<SettingItem> items = new ArrayList<>(INDEXING_HOT_SPECS.size());
        for (Spec s : INDEXING_HOT_SPECS) items.add(editableItem(s.key()));
        return items;
    }

    /** PPTX/DOCX conversion knobs — apply on the next upload; already-converted documents are untouched. */
    private List<SettingItem> conversionItems() {
        List<SettingItem> items = new ArrayList<>(CONVERSION_HOT_SPECS.size() + 5);
        for (Spec s : CONVERSION_HOT_SPECS) items.add(editableItem(s.key()));
        // 조회 전용 — 이미지 설명·OCR·EMF/WMF 는 빈 자체를 켜고 끄는 값(@ConditionalOnProperty)이고, 코드 언어는 아직 .env 로만 정한다
        AppProperties.ImageDescriptionProperties image = props.imageDescriptionSafe();
        items.add(readOnly("settings.item.md-code-language", envString("app.md-correction.default-code-language", "java"), null,
                "settings.desc.md-code-language"));
        items.add(readOnly("settings.item.image-description-enabled", Boolean.toString(image.enabled()), null,
                "settings.desc.image-description-enabled"));
        items.add(readOnly("settings.item.image-ocr-enabled", Boolean.toString(image.ocrEnabled()), null,
                "settings.desc.image-ocr-enabled"));
        items.add(readOnly("settings.item.docx-emf-convert", Boolean.toString(image.docxEmfConvert()), null,
                "settings.desc.docx-emf-convert"));
        items.add(readOnly("settings.item.docx-wmf-convert", Boolean.toString(image.docxWmfConvert()), null,
                "settings.desc.docx-wmf-convert"));
        return items;
    }

    /** LLM tuning (§6.18): all three temperatures are hot — general/RAG temperature
     *  (ClassifierService/AnswerService/RerankerService read it per call), direct-temperature
     *  (DirectAnswerService reads it per call), and indexing-temperature (every ungated
     *  executeWithTracking() background caller — keyword extraction, MD correction, txt→md, vision
     *  description/classification, title, summary — reads it per call, so it can be pinned near 0
     *  for deterministic extraction independently of the other two) — and creative-temperature, which
     *  only the C (응용) response mode uses (§6.24): the general one is clamped to [0.0, 0.3], so
     *  creative generation is impossible on it. Paired with it is creative-mode-enabled, the on/off
     *  switch for that mode as a whole ({@link #effectiveResponseMode}) — the two sit together, the
     *  knob first and the switch that makes it moot right after it. max-tokens is hot here too
     *  (§6.26 A6); only each provider bean's defaultOptions still needs a restart, and that is just
     *  the fallback for callers that attach no options of their own. */
    private List<SettingItem> llmHotItems() {
        List<SettingItem> items = new ArrayList<>(LLM_HOT_SPECS.size() + 7);
        for (Spec s : LLM_HOT_SPECS) items.add(editableItem(s.key()));
        items.addAll(chatFixedItems());
        return items;
    }

    /**
     * LLM·임베딩·리랭커 서버와의 연결 — 전부 기동 때 클라이언트·게이트·검증에 굳는 값이라 조회 전용이다. 소비자와 같은 접근자에서 읽는다
     * ({@code llmSafe()} · {@code embeddingSafe()} · {@code rerankerSafe()}).
     */
    private List<SettingItem> connectionItems() {
        AppProperties.LlmConfig llm = props.llmSafe();
        AppProperties.RerankerConfig reranker = props.rerankerSafe();
        return List.of(
                readOnly("settings.item.llm-connect-timeout", llm.connectTimeoutSeconds() + "s", null,
                        "settings.desc.llm-connect-timeout"),
                readOnly("settings.item.llm-read-timeout", llm.readTimeoutSeconds() + "s", null,
                        "settings.desc.llm-read-timeout"),
                readOnly("settings.item.llm-default-provider-concurrency", Integer.toString(llm.defaultProviderConcurrency()),
                        null, "settings.desc.llm-default-provider-concurrency"),
                readOnly("settings.item.llm-permit-wait-timeout", llm.permitWaitTimeoutSeconds() + "s", null,
                        "settings.desc.llm-permit-wait-timeout"),
                readOnly("settings.item.llm-verify-local-models",
                        Boolean.toString(llm.verifyLocalModelsOnStartup() == null || llm.verifyLocalModelsOnStartup()), null,
                        "settings.desc.llm-verify-local-models"),
                readOnly("settings.item.embed-usage-fallback",
                        Boolean.toString(props.embeddingSafe().usageFallbackEnabled() == null
                                || props.embeddingSafe().usageFallbackEnabled()), null,
                        "settings.desc.embed-usage-fallback"),
                readOnly("settings.item.rerank-max-concurrent",
                        reranker == null || reranker.maxConcurrent() == null ? "2" : Integer.toString(reranker.maxConcurrent()),
                        null, "settings.desc.rerank-max-concurrent"),
                // 키 둘은 관리자에게만(SettingsView.ADMIN_ONLY_ITEMS) — 이 카드는 누구에게나 보인다
                readOnly("settings.item.embed-api-key", maskSecret(envString("app.embedding.api-key", null)), null,
                        "settings.desc.embed-api-key"),
                readOnly("settings.item.rerank-api-key", maskSecret(envString("app.reranker.api-key", null)), null,
                        "settings.desc.rerank-api-key"),
                readOnly("settings.item.sqlite-vec-entrypoint", envString("app.vectorstore.sqlite-vec.entrypoint", "(자동)"), null,
                        "settings.desc.sqlite-vec-entrypoint"),
                readOnly("settings.item.chroma-host", envString("spring.ai.vectorstore.chroma.client.host", "http://localhost"),
                        null, "settings.desc.chroma-host"),
                readOnly("settings.item.chroma-port", envString("spring.ai.vectorstore.chroma.client.port", "8001"), null,
                        "settings.desc.chroma-port"));
    }

    /**
     * 인증·접속 경로·MCP — 보안 체인이 기동 때 굳히는 값이라 조회 전용. <b>비밀은 보이지 않는다</b>: MCP API 키는 설정 여부만 보인다
     * (이 화면은 게스트에게도 열린다). 인증 셋은 {@code authSafe()}(모드 정규화 뒤의 값), 나머지는 보안 설정이 읽는 그 속성이다.
     */
    private List<SettingItem> securityItems() {
        AppProperties.AuthConfig auth = props.authSafe();
        AppProperties.RateLimitConfig rate = props.rateLimitSafe();
        return List.of(
                readOnly("settings.item.auth-enabled", Boolean.toString(auth.enabled()), null, "settings.desc.auth-enabled"),
                readOnly("settings.item.auth-management-only", Boolean.toString(auth.managementOnly()), null,
                        "settings.desc.auth-management-only"),
                readOnly("settings.item.auth-guest-identity", auth.guestIdentity(), null, "settings.desc.auth-guest-identity"),
                readOnly("settings.item.trust-forwarded-for", Boolean.toString(envBool("app.trust-forwarded-for", false)), null,
                        "settings.desc.trust-forwarded-for"),
                readOnly("settings.item.secure-cookie",
                        Boolean.toString(envBool("server.servlet.session.cookie.secure", true)), null,
                        "settings.desc.secure-cookie"),
                readOnly("settings.item.mcp-server-enabled", Boolean.toString(envBool("spring.ai.mcp.server.enabled", false)),
                        null, "settings.desc.mcp-server-enabled"),
                readOnly("settings.item.mcp-read-api-key", maskSecret(envString("app.mcp.read-api-key", null)), null,
                        "settings.desc.mcp-read-api-key"),
                readOnly("settings.item.mcp-admin-api-key", maskSecret(envString("app.mcp.admin-api-key", null)), null,
                        "settings.desc.mcp-admin-api-key"),
                readOnly("settings.item.rate-limit-enabled", Boolean.toString(rate.enabled()), null,
                        "settings.desc.rate-limit-enabled"),
                readOnly("settings.item.rate-limit-chat", rate.chatPerMinute() + "/분", null, "settings.desc.rate-limit-chat"),
                readOnly("settings.item.rate-limit-upload", rate.uploadPerMinute() + "/분", null, "settings.desc.rate-limit-upload"),
                readOnly("settings.item.rate-limit-sync", rate.syncPerMinute() + "/분", null, "settings.desc.rate-limit-sync"),
                readOnly("settings.item.rate-limit-image", rate.imagePerMinute() + "/분", null, "settings.desc.rate-limit-image"),
                readOnly("settings.item.rate-limit-default", rate.defaultPerMinute() + "/분", null,
                        "settings.desc.rate-limit-default"),
                readOnly("settings.item.rate-limit-mcp",
                        (rate.mcpPerMinute() == null || rate.mcpPerMinute() <= 0 ? 60 : rate.mcpPerMinute()) + "/분", null,
                        "settings.desc.rate-limit-mcp"));
    }

    /**
     * 비밀 값의 표시 — 앞뒤 3글자만 보이고 가운데는 {@code ***}(예: {@code abc***xyz}). 6글자 이하면 앞뒤만으로 전부 드러나므로
     * {@code ***} 만, 없으면 "미설정". 이 줄들은 관리자에게만 실린다({@code SettingsView.ADMIN_ONLY_ITEMS}·관리자 전용 그룹).
     */
    static String maskSecret(String value) {
        if (value == null || value.isBlank()) return "미설정";
        String v = value.strip();
        if (v.length() <= 6) return "***";
        return v.substring(0, 3) + "***" + v.substring(v.length() - 3);
    }

    /**
     * 대화 흐름을 정하지만 기동 때 굳는 값 넷 — 같은 카드에 조회 전용으로 붙는다(조회 전용 줄에는 "재기동 필요" 배지를 달지 않는다 —
     * 고칠 수 없는 줄에 붙이면 뜻이 없다. 그 배지는 고칠 수 있는데 재기동해야 반영되는 칸(프로바이더 편집)만의 것이다). 값은 소비자가 실제로
     * 쓰는 것과 같은 출처에서 읽는다: 재시도 상한은 {@code AgentGraph}·{@code AnswerService} 가 기동 때 받는 {@code maxRetryCount()},
     * 이력 조회 상한은 {@code memorySafe()}, 질문 길이는 {@code QuestionLimit} 과 같은 범위(500~100,000)로 누른 값, Tool 선택은
     * {@code ToolPlanService} 가 받는 {@code app.agent.enabled}.
     */
    private List<SettingItem> chatFixedItems() {
        AppProperties.SummaryConfig summary = props.summarySafe();
        int questionMax = Math.max(QuestionLimit.MIN_CONFIGURED, Math.min(QuestionLimit.MAX_CONFIGURED,
                envInt("app.chat.max-question-chars", com.example.ragagent.security.PromptInjectionGuard.MAX_CHAT_QUESTION_LEN)));
        return List.of(
                readOnly("settings.item.max-retry-count", Integer.toString(props.maxRetryCount()), null,
                        "settings.desc.max-retry-count"),
                readOnly("settings.item.memory-fetch-limit-turns", Integer.toString(props.memorySafe().fetchLimitTurns()),
                        null, "settings.desc.memory-fetch-limit-turns"),
                readOnly("settings.item.chat-max-question-chars", Integer.toString(questionMax), null,
                        "settings.desc.chat-max-question-chars"),
                readOnly("settings.item.agent-enabled", Boolean.toString(envBool("app.agent.enabled", false)), null,
                        "settings.desc.agent-enabled"),
                readOnly("settings.item.summary-max-cached-threads", Integer.toString(summary.maxCachedThreads()), null,
                        "settings.desc.summary-max-cached-threads"),
                readOnly("settings.item.summary-max-summary-chars", Integer.toString(summary.maxSummaryChars()), null,
                        "settings.desc.summary-max-summary-chars"),
                readOnly("settings.item.summary-recent-raw-turns", Integer.toString(summary.recentRawTurns()), null,
                        "settings.desc.summary-recent-raw-turns"));
    }

    /**
     * 운영 카드 — 고칠 수 있는 셋(로그 수준 · DB 백업 켜기 · 남길 개수)과 조회 전용 셋(문서 저장 사용량 · 저장 상한 · 문서 백업 상한)이
     * 한 카드에 섞인다.
     */
    private List<SettingItem> operationsItems() {
        List<SettingItem> items = new ArrayList<>(OPERATIONS_HOT_SPECS.size() + 10);
        for (Spec s : OPERATIONS_HOT_SPECS) items.add(editableItem(s.key()));
        String zone = envString("app.time-zone", "Asia/Seoul");
        items.add(readOnly("settings.item.db-backup-cron",
                envString("app.backup.cron", "0 30 3 * * *") + " (" + zone + ")", null, "settings.desc.db-backup-cron"));
        items.addAll(storageItems());
        items.add(readOnly("settings.item.backup-retention-days", Integer.toString(props.uploadSafe().backupRetentionDays()),
                null, "settings.desc.backup-retention-days"));
        items.add(readOnly("settings.item.update-check-enabled", Boolean.toString(envBool("app.update.enabled", false)), null,
                "settings.desc.update-check-enabled"));
        items.add(readOnly("settings.item.update-check-cron",
                envString("app.update.cron", "0 0 9 * * *") + " (" + zone + ")", null, "settings.desc.update-check-cron"));
        items.add(readOnly("settings.item.update-repo", envString("app.update.repo", "-"), null, "settings.desc.update-repo"));
        items.add(readOnly("settings.item.update-api-base", envString("app.update.api-base", "https://api.github.com"), null,
                "settings.desc.update-api-base"));
        // 감사 로그 — AuditLogger 가 매번 읽는 auditSafe() 그대로(파일 크기·보관 같은 나머지 감사 설정은 화면에 두지 않는다)
        items.add(readOnly("settings.item.audit-enabled", Boolean.toString(props.auditSafe().enabled()), null,
                "settings.desc.audit-enabled"));
        return items;
    }

    /**
     * 로그 수준을 실제 로거에 건다 — 오버라이드가 있으면 그 수준을 {@link SettingsKeys#LOGGED_PACKAGES} 셋에(환경변수
     * {@code LOGGING_LEVEL} 과 같은 범위), 없으면 각 로거를 기동 때의 설정값({@code logging.level.<이름>})으로 되돌린다. 실패는 로그만
     * 남긴다 — 로그 수준 때문에 설정 저장이 실패하면 안 된다. {@code /actuator/loggers} 로 따로 바꾼 로거도 이 셋이면 덮인다.
     */
    private void applyLogLevel() {
        try {
            LoggingSystem logging = LoggingSystem.get(getClass().getClassLoader());
            String override = cache.get(SettingsKeys.LOGGING_LEVEL);
            for (String name : SettingsKeys.LOGGED_PACKAGES) {
                String level = override != null ? override : envString("logging.level." + name, null);
                logging.setLogLevel(name, parseLogLevel(level));
            }
        } catch (RuntimeException e) {
            log.warn("[SETTINGS] could not apply the log level: {}", e.getMessage());
        }
    }

    private static LogLevel parseLogLevel(String level) {
        if (level == null || level.isBlank()) return null;   // null = 상위 로거를 따른다
        try {
            return LogLevel.valueOf(level.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 화면에 보이는 로그 수준 — 오버라이드, 없으면 앱 패키지의 설정값({@code LOGGING_LEVEL}), 그것도 없으면 INFO. */
    private String effectiveLogLevel() {
        String override = cache.get(SettingsKeys.LOGGING_LEVEL);
        if (override != null) return override;
        LogLevel configured = parseLogLevel(envString("logging.level." + SettingsKeys.LOGGED_PACKAGES.get(0), null));
        return configured == null ? "INFO" : configured.name();
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    private String envString(String key, String fallback) {
        if (environment == null) return fallback;
        String v = environment.getProperty(key);
        return v == null || v.isBlank() ? fallback : v.strip();
    }

    private int envInt(String key, int fallback) {
        String v = envString(key, null);
        if (v == null) return fallback;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return fallback; }
    }

    private boolean envBool(String key, boolean fallback) {
        String v = envString(key, null);
        return v == null ? fallback : Boolean.parseBoolean(v);
    }

    /**
     * C(응용) 응답 모드를 채팅에서 고를 수 있는가 ({@code app.llm.creative-mode-enabled}).
     * <b>기본 ON</b> — 이 스위치가 생기기 전에는 늘 열려 있었으므로 미설정 시 동작이 바뀌지 않는다.
     *
     * <p>이것만으로 판정이 끝나지는 않는다: "어떤 모드가 이 스위치에 종속되는가"는
     * {@link ResponseMode#operatorToggleable()} 가 안다. 둘을 합치는 곳이
     * {@link #effectiveResponseMode(ResponseMode)} 하나이므로, 모드를 하나 더 끄고 싶어지면
     * enum 의 플래그만 켜면 되고 이 클래스는 손대지 않는다.
     */
    public boolean creativeModeEnabled() {
        return props.llmSafe().creativeModeEnabled();
    }

    /**
     * 요청된 응답 모드를 <b>지금 이 배포에서 실제로 쓸 수 있는 모드</b>로 바꿔 준다 — 운영자가 꺼 둔
     * 모드는 {@link ResponseMode#DEFAULT} 로 강등한다.
     *
     * <p>모든 채팅 진입점이 이 메서드를 거쳐야 한다({@code ChatController} 의 HTMX·SSE·REST 세 경로).
     * 클라이언트가 버튼을 감추는 것만으로는 부족하다 — C/Direct 배타 가드와 같은 이유이고, 실제로 구
     * L 모드는 서버 가드가 없어 손으로 만든 요청이 그대로 통과했다. 강등을 <b>진입점</b>에서 하는 것도
     * 같은 이유다: 그래프 안쪽에서 모드만 바꾸면 화면·DB 의 {@code response_mode} 는 C 인데 실제로는
     * N 으로 답한 턴이 남는다.
     *
     * <p>강등 대상이 {@code DEFAULT} 이므로 {@code DEFAULT} 자신은 끌 수 있는 모드일 수 없다 —
     * {@code ResponseMode.operatorToggleable()} 의 계약이고 테스트가 고정한다.
     */
    public ResponseMode effectiveResponseMode(ResponseMode requested) {
        if (requested == null) return ResponseMode.DEFAULT;
        if (requested.operatorToggleable() && !creativeModeEnabled()) {
            return ResponseMode.DEFAULT;
        }
        return requested;
    }

    private List<SettingItem> uiHotItems() {
        List<SettingItem> items = new ArrayList<>(UI_HOT_SPECS.size());
        for (Spec s : UI_HOT_SPECS) items.add(editableItem(s.key()));
        return items;
    }

    /** Global source-preview toggle for chat source popovers. Default ON when unset. */
    public boolean sourcePreviewEnabled() {
        Boolean o = parseBool(cache.get(SettingsKeys.UI_SOURCE_PREVIEW_ENABLED));
        return o == null || o;
    }

    /**
     * Shows per-chunk retrieval diagnostics (유사도 · 검색기여도 · 축별 순위) in the chat source
     * list. Default <b>OFF</b> — the numbers are a tuning aid, not something a reader of an answer
     * needs, and they invite over-reading (a high similarity with no bearing on the answer is
     * normal). The REST/SSE payload carries them regardless; this only gates the rendering.
     */
    public boolean retrievalMetricsEnabled() {
        Boolean o = parseBool(cache.get(SettingsKeys.UI_RETRIEVAL_METRICS_ENABLED));
        return o != null && o;
    }

    /**
     * 답변 뒤에 질문을 다듬어 저장하는가(PostAnswerService) — 턴마다 LLM 을 한 번 더 부르는 기능이라
     * 운영자가 끌 수 있어야 한다. Default <b>ON</b> when unset. 다음 턴부터 적용된다(매 턴 다시 읽는다).
     * {@code AppProperties} 에 필드를 두지 않고 UI 토글들처럼 이 캐시만 읽는다 — 배포 설정으로 정할 값이
     * 아니라 화면에서 켜고 끄는 운영 스위치이고, {@code LlmConfig} 레코드에 필드를 더하면 그 레코드를 위치
     * 인자로 만드는 수십 곳이 함께 흔들린다.
     */
    public boolean clarifiedQuestionEnabled() {
        Boolean o = parseBool(cache.get(SettingsKeys.LLM_CLARIFIED_QUESTION_ENABLED));
        return o == null || o;
    }

    /**
     * 답변 아래에 이어서 물어볼 만한 질문을 제안하는가(PostAnswerService) — 위와 같은 운영 스위치이고 같은
     * 이유로 이 캐시만 읽는다. Default <b>ON</b> when unset. 둘 다 켜져 있으면 LLM 호출은 턴당 한 번이다
     * (다듬은 질문과 추가 질문을 한 번에 받는다).
     */
    public boolean followUpQuestionsEnabled() {
        Boolean o = parseBool(cache.get(SettingsKeys.LLM_FOLLOW_UP_QUESTIONS_ENABLED));
        return o == null || o;
    }

    /**
     * 답변 아래에 제안할 추가 질문 수 — 위 스위치와 같은 운영 값이라 같은 이유로 이 캐시만 읽는다. 다음 턴부터
     * 적용된다. 값이 없거나 읽을 수 없으면 {@value #DEFAULT_FOLLOW_UP_COUNT}, 범위 밖이면 범위 안으로 자른다
     * (오버라이드는 저장 때 범위 검사를 받지만, 이 읽기가 그 검사에 기대지 않게).
     */
    public int followUpQuestionsCount() {
        String raw = cache.get(SettingsKeys.LLM_FOLLOW_UP_QUESTIONS_COUNT);
        int n = DEFAULT_FOLLOW_UP_COUNT;
        if (raw != null && !raw.isBlank()) {
            try {
                n = Integer.parseInt(raw.strip());
            } catch (NumberFormatException ignored) {
                // 기본값
            }
        }
        return Math.max(MIN_FOLLOW_UP_COUNT, Math.min(MAX_FOLLOW_UP_COUNT, n));
    }

    private static Boolean parseBool(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.equalsIgnoreCase("true")) return Boolean.TRUE;
        if (value.equalsIgnoreCase("false")) return Boolean.FALSE;
        return null;
    }

    /**
     * §6.15 저장 상한 — 조회 전용 2행: 지금 쓰고 있는 양과 상한.
     *
     * <p>편집 가능하게 만들지 않은 이유는 두 가지다 — 다음 호출부터 적용되는 종류의 값이 아니라
     * 배포 정책이고, 무엇보다 {@code Spec} 의 수치 종류가 {@code int} 라 GB 단위(20GB = 2.1e10)를
     * 담지 못한다. 대신 <b>사용량</b>을 함께 보여준다: 상한만 적혀
     * 있으면 운영자가 어디쯤 와 있는지 알 방법이 업로드가 거부되는 순간뿐이다.
     */
    private List<SettingItem> storageItems() {
        long used = storageQuotaService.usedBytesCached();   // guest-open page — never an on-demand disk walk
        long limit = storageQuotaService.limitBytes();
        String usage = limit > 0
                ? "%s / %s (%d%%)".formatted(StorageQuotaService.formatBytes(used),
                        StorageQuotaService.formatBytes(limit), Math.round(used * 100.0 / limit))
                : StorageQuotaService.formatBytes(used);
        long backupMax = props.uploadSafe().backupMaxBytes();
        return List.of(
                readOnly("settings.item.storage-used", usage, null, "settings.tooltip.storage-used"),
                readOnly("settings.item.storage-limit",
                        limit > 0 ? StorageQuotaService.formatBytes(limit) : "무제한",
                        null, "settings.desc.storage-limit"),
                readOnly("settings.item.backup-max-size",
                        backupMax > 0 ? StorageQuotaService.formatBytes(backupMax) : "무제한",
                        null, "settings.desc.backup-max-size")
        );
    }

    private List<SettingItem> cacheItems() {
        return List.of(
                readOnly("settings.item.query-embed-cache-enabled",
                        Boolean.toString(props.searchQueryEmbedCacheEnabledSafe()), null),
                readOnly("settings.item.query-embed-cache-max-size",
                        Integer.toString(props.searchQueryEmbedCacheMaxSizeSafe()), null),
                readOnly("settings.item.query-embed-cache-ttl",
                        Integer.toString(props.searchQueryEmbedCacheTtlSecondsSafe()), null)
        );
    }

    private static SettingItem readOnly(String labelKey, String value, String note) {
        return readOnly(labelKey, value, note, null);
    }

    private static SettingItem readOnly(String labelKey, String value, String note, String tooltipKey) {
        return new SettingItem(null, labelKey, value, "text", false, false, note, null, null, null, tooltipKey);
    }

    /**
     * Effective value of a hot key (override applied + clamping), read through the same
     * {@code AppProperties} accessor the search pipeline uses — so what the page shows is exactly
     * what the next retrieval will use.
     */
    private String effectiveValue(String key) {
        return switch (key) {
            case SettingsKeys.SEARCH_SIMILARITY_THRESHOLD     -> trimNum(props.searchSimilarityThresholdSafe());
            case SettingsKeys.SEARCH_RRF_KEYWORD_WEIGHT       -> trimNum(props.searchRrfKeywordWeightSafe());
            case SettingsKeys.SEARCH_RRF_K                    -> Integer.toString(props.searchRrfKSafe());
            case SettingsKeys.SEARCH_CANDIDATE_MULTIPLIER     -> Integer.toString(props.searchCandidateMultiplierSafe());
            case SettingsKeys.SEARCH_TAG_CANDIDATE_MULTIPLIER -> Integer.toString(props.searchTagCandidateMultiplierSafe());
            case SettingsKeys.SEARCH_MULTIQUERY_MIN_LENGTH    -> Integer.toString(props.searchMultiqueryMinLengthSafe());
            case SettingsKeys.SEARCH_RETRY_ESCALATE           -> Boolean.toString(props.searchRetryEscalateSafe());
            case SettingsKeys.SEARCH_TOP_K                    -> Integer.toString(props.searchTopKSafe());
            case SettingsKeys.SEARCH_MULTIQUERY_ENABLED       -> Boolean.toString(props.searchMultiqueryEnabledSafe());
            case SettingsKeys.SEARCH_HYBRID_ENABLED           -> Boolean.toString(props.searchHybridEnabledSafe());
            case SettingsKeys.SEARCH_CURATED_QA_ENABLED       -> Boolean.toString(props.searchCuratedQaEnabledSafe());
            case SettingsKeys.SEARCH_CURATED_QA_WEIGHT        -> trimNum(props.searchCuratedQaWeightSafe());
            case SettingsKeys.SEARCH_CURATED_QA_MIN_SIMILARITY -> trimNum(props.searchCuratedQaMinSimilaritySafe());
            case SettingsKeys.SEARCH_RERANK_MIN_SCORE         -> trimNum(props.searchRerankMinScoreSafe());
            case SettingsKeys.SEARCH_MAX_ATTEMPTS             -> Integer.toString(props.searchMaxAttemptsSafe());
            case SettingsKeys.CHUNK_SIZE                      -> Integer.toString(props.chunkSizeSafe());
            case SettingsKeys.CHUNK_OVERLAP                   -> Integer.toString(props.chunkOverlapSafe());
            case SettingsKeys.MIN_CHUNK_SIZE                  -> Integer.toString(props.minChunkSizeSafe());
            case SettingsKeys.CHUNK_SPLIT_GRANULAR            -> Boolean.toString(props.chunkSplitGranularSafe());
            case SettingsKeys.INDEXING_MAX_CONCURRENT_FILES   -> Integer.toString(props.indexingSafe().maxConcurrentFiles());
            case SettingsKeys.INDEXING_MAX_CONCURRENT_LLM     -> Integer.toString(props.indexingSafe().maxConcurrentLlmCalls());
            case SettingsKeys.INDEXING_KEYWORD_BATCH_SIZE     -> Integer.toString(props.indexingSafe().keywordBatchSize());
            case SettingsKeys.INDEXING_KEYWORD_TIMEOUT        -> Integer.toString(props.indexingSafe().keywordTimeoutSeconds());
            case SettingsKeys.EMBEDDING_MAX_CHUNK_CHARS       -> Integer.toString(props.embeddingSafe().maxChunkChars());
            case SettingsKeys.EMBEDDING_MAX_CONCURRENT_BATCHES -> Integer.toString(props.embeddingSafe().maxConcurrentBatches());
            case SettingsKeys.PPTX_REMOVE_DUPLICATE_SLIDES    -> Boolean.toString(props.pptxRemoveDuplicateSlidesSafe());
            case SettingsKeys.PPTX_DROP_DIVIDER_SLIDES        -> Boolean.toString(props.pptxDropDividerSlidesSafe());
            case SettingsKeys.PPTX_DROP_REDUNDANT_TITLE_SLIDES -> Boolean.toString(props.pptxDropRedundantTitleSlidesSafe());
            case SettingsKeys.PPTX_DROP_ENDING_SLIDE          -> Boolean.toString(props.pptxDropEndingSlideSafe());
            case SettingsKeys.PPTX_IMAGE_MIN_SHAPE_DIMENSION  -> trimNum(props.pptxImageSafe().minShapeDimensionPt());
            case SettingsKeys.PPTX_IMAGE_CLUSTER_PADDING      -> trimNum(props.pptxImageSafe().clusterProximityPaddingPt());
            case SettingsKeys.PPTX_IMAGE_MERGE_ANNOTATED      -> Boolean.toString(props.pptxImageSafe().mergeAnnotatedPictures());
            case SettingsKeys.PPTX_IMAGE_RASTERIZE_SHAPES     -> Boolean.toString(props.pptxImageSafe().rasterizeShapes());
            case SettingsKeys.DOCX_IMAGE_MERGE_ANNOTATED      -> Boolean.toString(props.docxImageSafe().mergeAnnotatedShapes());
            case SettingsKeys.LLM_TEMPERATURE                 -> trimNum(props.llmSafe().temperature());
            case SettingsKeys.LLM_DIRECT_TEMPERATURE          -> trimNum(props.llmSafe().directTemperature());
            case SettingsKeys.LLM_INDEXING_TEMPERATURE        -> trimNum(props.llmSafe().indexingTemperature());
            case SettingsKeys.LLM_CREATIVE_MODE_ENABLED       -> Boolean.toString(creativeModeEnabled());
            case SettingsKeys.LLM_SHRINK_STEP                 -> Integer.toString(props.llmSafe().shrinkStep());
            case SettingsKeys.LLM_MAX_TOKENS                  -> Integer.toString(props.llmSafe().maxTokens());
            case SettingsKeys.LLM_CREATIVE_TEMPERATURE        -> trimNum(props.llmSafe().creativeTemperature());
            case SettingsKeys.LLM_CLARIFIED_QUESTION_ENABLED  -> Boolean.toString(clarifiedQuestionEnabled());
            case SettingsKeys.LLM_FOLLOW_UP_QUESTIONS_ENABLED -> Boolean.toString(followUpQuestionsEnabled());
            case SettingsKeys.LLM_FOLLOW_UP_QUESTIONS_COUNT   -> Integer.toString(followUpQuestionsCount());
            case SettingsKeys.UI_SOURCE_PREVIEW_ENABLED       -> Boolean.toString(sourcePreviewEnabled());
            case SettingsKeys.UI_RETRIEVAL_METRICS_ENABLED    -> Boolean.toString(retrievalMetricsEnabled());
            case SettingsKeys.LOGGING_LEVEL                   -> effectiveLogLevel();
            case SettingsKeys.BACKUP_ENABLED                  -> Boolean.toString(
                    AppProperties.hotBool(key, envBool("app.backup.enabled", true)));
            case SettingsKeys.BACKUP_KEEP                     -> Integer.toString(
                    Math.max(1, AppProperties.hotInt(key, envInt("app.backup.keep", 7))));
            default -> ThinkingSite.bySettingsKey(key)
                    .map(site -> props.llmSafe().thinkingLevel(site).value())
                    .orElse("");
        };
    }

    /** Formats a double without a trailing ".0" for whole numbers (e.g. 60.0 → "60", 0.35 → "0.35"). */
    private static String trimNum(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d)) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }

    private static String nullToDash(String s) {
        return (s == null || s.isBlank()) ? "-" : s;
    }
}
