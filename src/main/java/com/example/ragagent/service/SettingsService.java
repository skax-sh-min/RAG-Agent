package com.example.ragagent.service;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.config.SettingsKeys;
import com.example.ragagent.llm.CircuitBreaker;
import com.example.ragagent.llm.ProviderToggle;
import com.example.ragagent.model.SettingsView;
import com.example.ragagent.model.SettingsView.ProviderRow;
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

    private enum Kind { DOUBLE, INT, BOOL }

    /** One editable setting's validation + input metadata. {@code labelKey} is an i18n message key. */
    private record Spec(String key, Kind kind, double min, double max, double step, String labelKey) {}

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
            new Spec(SettingsKeys.SEARCH_CURATED_QA_WEIGHT,        Kind.DOUBLE, 0.0, 10.0, 0.1,  "settings.item.curated-qa-weight"),
            new Spec(SettingsKeys.SEARCH_SUBMISSION_WEIGHT,        Kind.DOUBLE, 0.0, 10.0, 0.1,  "settings.item.submission-weight")
    );

    // Insertion order = render order in the "인덱싱 튜닝" group. Apply on the next indexing / ↺ re-index
    // (they don't retro-actively re-chunk already-indexed documents).
    private static final List<Spec> INDEXING_HOT_SPECS = List.of(
            new Spec(SettingsKeys.CHUNK_SIZE,                      Kind.INT,    100, 8000, 50,   "settings.item.chunk-size"),
            new Spec(SettingsKeys.CHUNK_OVERLAP,                   Kind.INT,    0,   2000, 10,   "settings.item.chunk-overlap"),
            new Spec(SettingsKeys.MIN_CHUNK_SIZE,                  Kind.INT,    0,   4000, 10,   "settings.item.min-chunk-size"),
            new Spec(SettingsKeys.CHUNK_SPLIT_GRANULAR,            Kind.BOOL,   0,   0,    0,    "settings.item.chunk-split-granular"),
            new Spec(SettingsKeys.INDEXING_MAX_CONCURRENT_FILES,   Kind.INT,    1,   32,   1,    "settings.item.max-concurrent-files"),
            new Spec(SettingsKeys.INDEXING_MAX_CONCURRENT_LLM,     Kind.INT,    1,   32,   1,    "settings.item.max-concurrent-llm-calls")
    );

    // Insertion order = render order in the "LLM 튜닝" group. Apply on the next LLM call (§6.18).
    private static final List<Spec> LLM_HOT_SPECS = List.of(
            new Spec(SettingsKeys.LLM_TEMPERATURE,                Kind.DOUBLE, 0.0, 0.3,  0.01, "settings.item.temperature"),
            new Spec(SettingsKeys.LLM_DIRECT_TEMPERATURE,         Kind.DOUBLE, 0.0, 1.0,  0.05, "settings.item.direct-temperature"),
            new Spec(SettingsKeys.LLM_INDEXING_TEMPERATURE,       Kind.DOUBLE, 0.0, 1.0,  0.05, "settings.item.indexing-temperature")
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
        SPECS = Map.copyOf(m);
    }

    private final SettingsOverrideRepository repo;
    private final AppProperties props;
    private final AuditLogger audit;
    private final CircuitBreaker circuitBreaker;
    private final ProviderToggle providerToggle;

    /** Persisted overrides, cached so the {@link #get} hot path never hits SQLite. */
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public SettingsService(SettingsOverrideRepository repo, AppProperties props,
                           AuditLogger audit, CircuitBreaker circuitBreaker,
                           ProviderToggle providerToggle) {
        this.repo = repo;
        this.props = props;
        this.audit = audit;
        this.circuitBreaker = circuitBreaker;
        this.providerToggle = providerToggle;
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

    /** Full {@code /settings} model with overrides already applied. */
    public SettingsView buildView() {
        List<ProviderRow> providers = providerRows();

        // Editable groups first, read-only (조회 전용) groups last.
        List<SettingGroup> groups = List.of(
                new SettingGroup("search_hot", "settings.group.search_hot", searchHotItems()),
                new SettingGroup("indexing", "settings.group.indexing", indexingItems()),
                new SettingGroup("llm_hot", "settings.group.llm_hot", llmHotItems()),
            new SettingGroup("ui_hot", "settings.group.ui_hot", uiHotItems()),
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
     */
    public List<ProviderRow> providerRows() {
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
                            providerToggle.isEnabled(cfg.name()));
                })
                .toList();
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
        return new SettingItem(
                spec.key(),
                spec.labelKey(),
                effectiveValue(spec.key()),
                bool ? "bool" : "number",
                true,
                cache.containsKey(spec.key()),
                null,
                bool ? null : spec.min(),
                bool ? null : spec.max(),
                bool ? null : spec.step());
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
                readOnly("settings.item.rerank-enabled", Boolean.toString(props.searchRerankEnabled()), "settings.note.restart")
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
     *  for deterministic extraction independently of the other two). max-tokens sits in the LLM
     *  providers card footer as read-only (baked into the provider beans at startup — restart to
     *  change). */
    private List<SettingItem> llmHotItems() {
        List<SettingItem> items = new ArrayList<>(LLM_HOT_SPECS.size());
        for (Spec s : LLM_HOT_SPECS) items.add(editableItem(s.key()));
        return items;
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

    private static Boolean parseBool(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.equalsIgnoreCase("true")) return Boolean.TRUE;
        if (value.equalsIgnoreCase("false")) return Boolean.FALSE;
        return null;
    }

    private List<SettingItem> cacheItems() {
        return List.of(
                readOnly("settings.item.query-embed-cache-enabled",
                        Boolean.toString(props.searchQueryEmbedCacheEnabledSafe()), "settings.note.restart"),
                readOnly("settings.item.query-embed-cache-max-size",
                        Integer.toString(props.searchQueryEmbedCacheMaxSizeSafe()), "settings.note.restart"),
                readOnly("settings.item.query-embed-cache-ttl",
                        Integer.toString(props.searchQueryEmbedCacheTtlSecondsSafe()), "settings.note.restart")
        );
    }

    private static SettingItem readOnly(String labelKey, String value, String note) {
        return new SettingItem(null, labelKey, value, "text", false, false, note, null, null, null);
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
            case SettingsKeys.SEARCH_SUBMISSION_WEIGHT        -> trimNum(props.searchSubmissionWeightSafe());
            case SettingsKeys.CHUNK_SIZE                      -> Integer.toString(props.chunkSizeSafe());
            case SettingsKeys.CHUNK_OVERLAP                   -> Integer.toString(props.chunkOverlapSafe());
            case SettingsKeys.MIN_CHUNK_SIZE                  -> Integer.toString(props.minChunkSizeSafe());
            case SettingsKeys.CHUNK_SPLIT_GRANULAR            -> Boolean.toString(props.chunkSplitGranularSafe());
            case SettingsKeys.INDEXING_MAX_CONCURRENT_FILES   -> Integer.toString(props.indexingSafe().maxConcurrentFiles());
            case SettingsKeys.INDEXING_MAX_CONCURRENT_LLM     -> Integer.toString(props.indexingSafe().maxConcurrentLlmCalls());
            case SettingsKeys.LLM_TEMPERATURE                 -> trimNum(props.llmSafe().temperature());
            case SettingsKeys.LLM_DIRECT_TEMPERATURE          -> trimNum(props.llmSafe().directTemperature());
            case SettingsKeys.LLM_INDEXING_TEMPERATURE        -> trimNum(props.llmSafe().indexingTemperature());
            case SettingsKeys.UI_SOURCE_PREVIEW_ENABLED       -> Boolean.toString(sourcePreviewEnabled());
            case SettingsKeys.UI_RETRIEVAL_METRICS_ENABLED    -> Boolean.toString(retrievalMetricsEnabled());
            default -> "";
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
