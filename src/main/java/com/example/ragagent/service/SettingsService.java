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
public class SettingsService implements AppProperties.OverrideSource {

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
    private static final List<Spec> SEARCH_HOT_SPECS = List.of(
            new Spec(SettingsKeys.SEARCH_SIMILARITY_THRESHOLD,     Kind.DOUBLE, 0.0, 1.0,  0.01, "settings.item.similarity-threshold"),
            new Spec(SettingsKeys.SEARCH_RRF_KEYWORD_WEIGHT,       Kind.DOUBLE, 0.0, 10.0, 0.1,  "settings.item.rrf-keyword-weight"),
            new Spec(SettingsKeys.SEARCH_RRF_K,                    Kind.INT,    1,   1000, 1,    "settings.item.rrf-k"),
            new Spec(SettingsKeys.SEARCH_CANDIDATE_MULTIPLIER,     Kind.INT,    1,   20,   1,    "settings.item.candidate-multiplier"),
            new Spec(SettingsKeys.SEARCH_TAG_CANDIDATE_MULTIPLIER, Kind.INT,    1,   20,   1,    "settings.item.tag-candidate-multiplier"),
            new Spec(SettingsKeys.SEARCH_MULTIQUERY_MIN_LENGTH,    Kind.INT,    0,   1000, 1,    "settings.item.multiquery-min-length"),
            new Spec(SettingsKeys.SEARCH_RETRY_ESCALATE,           Kind.BOOL,   0,   0,    0,    "settings.item.retry-escalate"),
            new Spec(SettingsKeys.SEARCH_TOP_K,                    Kind.INT,    1,   50,   1,    "settings.item.top-k"),
            new Spec(SettingsKeys.SEARCH_MULTIQUERY_ENABLED,       Kind.BOOL,   0,   0,    0,    "settings.item.multiquery-enabled"),
            new Spec(SettingsKeys.SEARCH_HYBRID_ENABLED,           Kind.BOOL,   0,   0,    0,    "settings.item.hybrid-enabled"),
            new Spec(SettingsKeys.SEARCH_CURATED_QA_ENABLED,       Kind.BOOL,   0,   0,    0,    "settings.item.curated-qa-enabled"),
            new Spec(SettingsKeys.SEARCH_CURATED_QA_WEIGHT,        Kind.DOUBLE, 0.0, 10.0, 0.1,  "settings.item.curated-qa-weight")
    );

    // Insertion order = render order in the "인덱싱 튜닝" group. Apply on the next indexing / ↺ re-index
    // (they don't retro-actively re-chunk already-indexed documents).
    private static final List<Spec> INDEXING_HOT_SPECS = List.of(
            new Spec(SettingsKeys.CHUNK_SIZE,                      Kind.INT,    100, 8000, 50,   "settings.item.chunk-size"),
            new Spec(SettingsKeys.CHUNK_OVERLAP,                   Kind.INT,    0,   2000, 10,   "settings.item.chunk-overlap"),
            new Spec(SettingsKeys.MIN_CHUNK_SIZE,                  Kind.INT,    0,   4000, 10,   "settings.item.min-chunk-size"),
            new Spec(SettingsKeys.CHUNK_SPLIT_GRANULAR,            Kind.BOOL,   0,   0,    0,    "settings.item.chunk-split-granular"),
            new Spec(SettingsKeys.INDEXING_MAX_CONCURRENT_FILES,   Kind.INT,    1,   4,    1,    "settings.item.max-concurrent-files"),
            new Spec(SettingsKeys.INDEXING_MAX_CONCURRENT_LLM,     Kind.INT,    1,   8,    1,    "settings.item.max-concurrent-llm-calls")
    );

    // Insertion order = render order in the "LLM 튜닝" group. Apply on the next LLM call (§6.18).
    private static final List<Spec> LLM_HOT_SPECS = List.of(
            new Spec(SettingsKeys.LLM_TEMPERATURE,                Kind.DOUBLE, 0.0, 0.3,  0.05, "settings.item.temperature"),
            new Spec(SettingsKeys.LLM_DIRECT_TEMPERATURE,         Kind.DOUBLE, 0.0, 1.0,  0.05, "settings.item.direct-temperature"),
            new Spec(SettingsKeys.LLM_INDEXING_TEMPERATURE,       Kind.DOUBLE, 0.0, 0.1,  0.05, "settings.item.indexing-temperature"),
            new Spec(SettingsKeys.LLM_CREATIVE_TEMPERATURE,       Kind.DOUBLE, 0.0, 1.0,  0.05, "settings.item.creative-temperature"),
            new Spec(SettingsKeys.LLM_CREATIVE_MODE_ENABLED,      Kind.BOOL,   0,   0,    0,    "settings.item.creative-mode-enabled"),
            new Spec(SettingsKeys.LLM_SHRINK_STEP,                Kind.INT,    1,   10,   1,    "settings.item.shrink-step"),
            new Spec(SettingsKeys.LLM_CLARIFIED_QUESTION_ENABLED, Kind.BOOL,   0,   0,    0,    "settings.item.clarified-question-enabled"),
            new Spec(SettingsKeys.LLM_FOLLOW_UP_QUESTIONS_ENABLED, Kind.BOOL,  0,   0,    0,    "settings.item.follow-up-questions-enabled"),
            new Spec(SettingsKeys.LLM_MAX_TOKENS,                 Kind.INT,
                    AppProperties.MIN_MAX_TOKENS, AppProperties.MAX_MAX_TOKENS, 500, "settings.item.max-tokens")
    );

        // Insertion order = render order in the "UI" group. Apply on next page render.
        private static final List<Spec> UI_HOT_SPECS = List.of(
            new Spec(SettingsKeys.UI_SOURCE_PREVIEW_ENABLED,      Kind.BOOL,   0,   0,    0,    "settings.item.source-preview-enabled"),
            new Spec(SettingsKeys.UI_RETRIEVAL_METRICS_ENABLED,   Kind.BOOL,   0,   0,    0,    "settings.item.retrieval-metrics-enabled")
        );

    private static final Map<String, Spec> SPECS;
    static {
        Map<String, Spec> m = new LinkedHashMap<>();
        for (Spec s : SEARCH_HOT_SPECS) m.put(s.key(), s);
        for (Spec s : INDEXING_HOT_SPECS) m.put(s.key(), s);
        for (Spec s : LLM_HOT_SPECS) m.put(s.key(), s);
        for (Spec s : UI_HOT_SPECS) m.put(s.key(), s);
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

    @org.springframework.beans.factory.annotation.Autowired
    public SettingsService(SettingsOverrideRepository repo, AppProperties props,
                           AuditLogger audit, CircuitBreaker circuitBreaker,
                           ProviderToggle providerToggle, ProviderContextWindows contextWindows,
                           StorageQuotaService storageQuotaService, ProviderThinkingDialects thinkingDialects,
                           ProviderConnectivity connectivity) {
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
                String choice = v.toLowerCase(java.util.Locale.ROOT);
                if (!spec.choices().contains(choice)) {
                    throw new IllegalArgumentException(
                            "허용 값은 %s 입니다: %s".formatted(String.join(" / ", spec.choices()), raw));
                }
                yield choice;
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
     * 값이 기동 때 굳어 바꾸려면 재기동이 필요한 그룹의 제목 옆에 붙이는 표시. 예전에는 항목마다 붙였다 — 같은 말이 줄마다
     * 반복돼 오히려 어느 값이 그런지 읽히지 않았고, 그룹이 통째로 조회 전용이라 줄 단위로 가를 이유도 없었다.
     */
    private static final String RESTART_NOTE = "settings.note.restart";

    /**
     * Full {@code /settings} model with overrides already applied.
     *
     * <p>프로바이더의 상태 열은 <b>늘 "확인 중"으로 시작한다</b> — 이 화면을 열 때마다 서버에 새로 물어야 하는데(마지막 확인이
     * 수 분 전의 것일 수 있다) 물어보는 동안 페이지가 막히면 안 된다. 결과는 {@link #refreshProviderConnections()} 가 칸만
     * 바꿔 끼운다.
     */
    public SettingsView buildView() {
        List<ProviderRow> providers = providerRows(true);

        // Editable groups first, read-only (조회 전용) groups last.
        List<SettingGroup> groups = List.of(
                new SettingGroup("search_hot", "settings.group.search_hot", searchHotItems()),
                new SettingGroup("indexing", "settings.group.indexing", indexingItems()),
                new SettingGroup("llm_hot", "settings.group.llm_hot", llmHotItems()),
            new SettingGroup("ui_hot", "settings.group.ui_hot", uiHotItems()),
                new SettingGroup("search_fixed", "settings.group.search_fixed", fixedSearchItems(), RESTART_NOTE),
                new SettingGroup("storage", "settings.group.storage", storageItems(), RESTART_NOTE),
                new SettingGroup("cache", "settings.group.cache", cacheItems(), RESTART_NOTE)
        );

        AppProperties.LlmConfig llm = props.llmSafe();
        Integer dim = props.embeddingSafe().dimensions();
        return new SettingsView(
                providers,
                llm.defaultRoutingMode(),
                trimNum(llm.temperature()),   // general/RAG temperature — now the real effective value
                Integer.toString(llm.maxTokens()),
                nullToDash(props.embeddingSafe().model()),
                nullToDash(props.embeddingSafe().baseUrl()),
                dim != null ? dim.toString() : "auto",
                props.vectorStoreSafe().type(),
                groups);
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
        return visibleProviders().stream()
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
                            connectionOf(cfg, recheck));
                })
                .toList();
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
                        ? ProviderConnection.reachable(r.latencyMs(), r.modelListed())
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
        return providerRows();
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
     */
    public record ReprobeRow(String provider, String before, String after, ReprobeOutcome outcome) {}

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
            if (cfg.declaredContextSize() != null) {
                rows.add(new ReprobeRow(cfg.name(), before, before, ReprobeOutcome.SKIPPED_DECLARED));
                continue;
            }
            int old = contextWindows.tokensOrZero(cfg.name());
            Integer found = ContextWindowProbe.probe(cfg.apiBase(), cfg.model(),
                    llm.connectTimeoutSeconds(), llm.readTimeoutSeconds()).orElse(null);
            if (found == null) {
                rows.add(new ReprobeRow(cfg.name(), before, before, ReprobeOutcome.FAILED));
                continue;
            }
            contextWindows.record(cfg.name(), found, ProviderContextWindows.Source.PROBED);
            String after = contextWindowLabel(cfg.name());
            boolean changed = found != old;
            rows.add(new ReprobeRow(cfg.name(), before, after,
                    changed ? ReprobeOutcome.UPDATED : ReprobeOutcome.UNCHANGED));

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

    /**
     * {@code "8,192 (탐지됨)"} 처럼 값과 <b>출처</b>를 함께 적는다 — 운영자가 직접 적은 숫자인지 앱이
     * 서버에게 물어본 숫자인지가 신뢰도를 가른다. 탐지값은 기동 시점의 관측이라 모델을 다른 크기로
     * 다시 로드하면 낡고, 그때 운영자가 {@code context-size} 로 못 박으면 이 칸이 (설정됨)으로 바뀐다.
     *
     * <p>모르면 {@code "-"} 다. 이것도 정보다 — 컨텍스트 초과가 나는데 이 칸이 {@code "-"} 라면
     * 앱이 창 크기를 모르는 상태이므로 어떤 보정도 돌지 않았다는 뜻이다.
     */
    private String contextWindowLabel(String providerName) {
        return contextWindows.find(providerName)
                .map(w -> "%,d (%s)".formatted(w.tokens(),
                        w.source() == ProviderContextWindows.Source.CONFIGURED ? "설정됨" : "탐지됨"))
                .orElse("-");
    }

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
                null);   // 편집 가능한 행은 라벨·범위로 충분해 툴팁을 쓰지 않는다
    }

    private List<SettingItem> searchHotItems() {
        List<SettingItem> items = new ArrayList<>(SEARCH_HOT_SPECS.size());
        for (Spec s : SEARCH_HOT_SPECS) items.add(editableItem(s.key()));
        return items;
    }

    /** Only rerank-enabled remains read-only here — it's an {@code @ConditionalOnProperty} bean that
     *  can't be hot-swapped (topK / multiquery / hybrid moved to the hot group). */
    private List<SettingItem> fixedSearchItems() {
        return List.of(
                readOnly("settings.item.rerank-enabled", Boolean.toString(props.searchRerankEnabled()), null)
        );
    }

    /** Chunking + indexing concurrency: hot-editable but they apply on the NEXT indexing / ↺ re-index,
     *  not the next search (existing chunks are not re-split), hence a distinct group + note. */
    private List<SettingItem> indexingItems() {
        List<SettingItem> items = new ArrayList<>(INDEXING_HOT_SPECS.size());
        for (Spec s : INDEXING_HOT_SPECS) items.add(editableItem(s.key()));
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
        List<SettingItem> items = new ArrayList<>(LLM_HOT_SPECS.size());
        for (Spec s : LLM_HOT_SPECS) items.add(editableItem(s.key()));
        return items;
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
        return List.of(
                readOnly("settings.item.storage-used", usage, null, "settings.tooltip.storage-used"),
                readOnly("settings.item.storage-limit",
                        limit > 0 ? StorageQuotaService.formatBytes(limit) : "무제한",
                        null)
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
            case SettingsKeys.CHUNK_SIZE                      -> Integer.toString(props.chunkSizeSafe());
            case SettingsKeys.CHUNK_OVERLAP                   -> Integer.toString(props.chunkOverlapSafe());
            case SettingsKeys.MIN_CHUNK_SIZE                  -> Integer.toString(props.minChunkSizeSafe());
            case SettingsKeys.CHUNK_SPLIT_GRANULAR            -> Boolean.toString(props.chunkSplitGranularSafe());
            case SettingsKeys.INDEXING_MAX_CONCURRENT_FILES   -> Integer.toString(props.indexingSafe().maxConcurrentFiles());
            case SettingsKeys.INDEXING_MAX_CONCURRENT_LLM     -> Integer.toString(props.indexingSafe().maxConcurrentLlmCalls());
            case SettingsKeys.LLM_TEMPERATURE                 -> trimNum(props.llmSafe().temperature());
            case SettingsKeys.LLM_DIRECT_TEMPERATURE          -> trimNum(props.llmSafe().directTemperature());
            case SettingsKeys.LLM_INDEXING_TEMPERATURE        -> trimNum(props.llmSafe().indexingTemperature());
            case SettingsKeys.LLM_CREATIVE_MODE_ENABLED       -> Boolean.toString(creativeModeEnabled());
            case SettingsKeys.LLM_SHRINK_STEP                 -> Integer.toString(props.llmSafe().shrinkStep());
            case SettingsKeys.LLM_MAX_TOKENS                  -> Integer.toString(props.llmSafe().maxTokens());
            case SettingsKeys.LLM_CREATIVE_TEMPERATURE        -> trimNum(props.llmSafe().creativeTemperature());
            case SettingsKeys.LLM_CLARIFIED_QUESTION_ENABLED  -> Boolean.toString(clarifiedQuestionEnabled());
            case SettingsKeys.LLM_FOLLOW_UP_QUESTIONS_ENABLED -> Boolean.toString(followUpQuestionsEnabled());
            case SettingsKeys.UI_SOURCE_PREVIEW_ENABLED       -> Boolean.toString(sourcePreviewEnabled());
            case SettingsKeys.UI_RETRIEVAL_METRICS_ENABLED    -> Boolean.toString(retrievalMetricsEnabled());
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
