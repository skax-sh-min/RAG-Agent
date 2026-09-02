package com.example.ragagent.llm;

import com.example.ragagent.exception.LlmBackpressureException;
import com.example.ragagent.exception.LlmContextOverflowException;
import com.example.ragagent.exception.LlmProviderExhaustedException;
import com.example.ragagent.repository.LlmUsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.web.client.HttpStatusCodeException;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.example.ragagent.llm.ProviderRole.*;

public class LlmRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);

    /**
     * Short fallback block for transient/overload-type failures — see {@link #blockForOverload}.
     *
     * <p><b>{@code String} 인 것은 실수가 아니다.</b> 이 값은 {@code CircuitBreaker.block()} 의 두 번째
     * 인자로 가는데, 그 자리는 HTTP {@code Retry-After} <b>헤더 값</b>이다 — 프로바이더가 헤더를 줬으면
     * 그 문자열이 그대로 오고, 안 줬을 때 이 상수가 대신 들어간다. {@code parseRetryAfter()} 가 초 단위
     * 숫자와 RFC 1123 날짜를 모두 받으므로 타입은 문자열이어야 한다. {@code int} 로 바꾸려면
     * {@code block()} 의 시그니처부터 갈라야 하고, 그러면 "헤더가 있으면 그것, 없으면 이 값"이라는
     * 한 줄짜리 규칙이 두 갈래로 늘어난다.
     *
     * <p>프로퍼티로 외부화되어 있지 않다 — 바꾸려면 코드를 고쳐야 한다. 세 갈래(폴백 없는 과부하 차단·
     * 기타 4xx/5xx·일반 예외)가 이 하나를 공유하므로, 그중 하나만 다르게 하려면 상수를 분리해야 한다.
     */
    private static final String SHORT_BLOCK_SECONDS = "30";

    /**
     * 폴백이 없는 프로바이더가 <b>연결 계열</b>로 실패했을 때의 차단 — 30초가 아니라 이만큼이다.
     *
     * <p><b>왜 그래도 차단은 하는가.</b> 서버가 정말 죽어 있으면 차단이 없을 때 모든 요청이 연결
     * 타임아웃을 각자 물게 된다. 빠르게 실패시키는 것이 차단의 값이고 그건 프로바이더가 하나뿐일
     * 때도 유효하다.
     *
     * <p><b>왜 30초는 긴가.</b> 프로바이더가 하나면 차단은 그 시간만큼 <b>전면 중단</b>이다. 로컬 LLM
     * 재시작은 보통 몇 초로 끝나므로, 30초는 서버가 이미 올라온 뒤까지 남아 운영자에게
     * "재시작했는데도 계속 안 된다"로 보인다 — 실측 사고가 정확히 그 모양이었다(차단 1회, 그 창 안의
     * 재시도 3번이 전부 {@code "All providers exhausted"}). 5초면 스탬피드는 막으면서 회복은
     * 사실상 즉시다.
     *
     * <p>폴백이 있으면 이 값을 쓰지 않는다 — 그때는 차단이 "다른 곳으로 보낸다"는 뜻이라 길어도
     * 손해가 없고, 아픈 프로바이더를 성급히 다시 부르지 않는 편이 낫다.
     */
    private static final String NO_FALLBACK_BLOCK_SECONDS = "5";

    private final List<LlmProvider> providers; // priority 오름차순
    private final LlmUsageRepository usageRepo;
    private final CircuitBreaker circuitBreaker;
    private final RoutingMode defaultMode;
    private final int readTimeoutSeconds;

    /**
     * Per-provider concurrency gate for the interactive query/chat path (CLASSIFIER,
     * ANSWER, CRITIC-feeding evaluation, DirectAnswer, reranking, multi-query expansion).
     * Sized from {@code AppProperties.ProviderConfig.concurrency()} (falls back to
     * {@code defaultProviderConcurrency}) so the app never sends more concurrent requests to a
     * single physical LLM server than it can actually serve (e.g. llama-server --parallel).
     * Deliberately NOT applied to indexing/background LLM calls ({@code executeWithTracking}),
     * which already have their own semaphore ({@code app.indexing.max-concurrent-llm-calls}) and
     * no synchronous HTTP caller waiting on a deadline — see {@link #executeGated}.
     */
    private final Map<String, Semaphore> providerGates = new ConcurrentHashMap<>();
    /** Each provider's total configured permits — {@code Semaphore} itself only exposes the free
     *  count ({@link Semaphore#availablePermits()}), so in-use has to be derived as capacity minus
     *  that (see {@link #localTier1Concurrency()}). Fixed at construction time (concurrency is a
     *  restart-required, non-hot-editable setting), so a plain map is enough — no need to keep it
     *  in sync with anything at runtime. */
    private final Map<String, Integer> providerCapacity = new ConcurrentHashMap<>();
    private final int defaultProviderConcurrency;
    private final int permitWaitTimeoutSeconds;

    /**
     * Runtime operator enable/disable state (§A). Consulted as an extra eligibility filter in
     * {@link #findFirst} and {@link #hasLocalProvider} — a disabled provider is invisible to routing
     * until re-enabled or the app restarts (the toggle is in-memory, see {@link ProviderToggle}).
     */
    private final ProviderToggle providerToggle;

    /** A held concurrency slot from {@link #acquirePermit}; release via try-with-resources. */
    public interface Permit extends AutoCloseable {
        @Override void close();
    }

    /**
     * Providers known (for the life of this process) to lack image-input support
     * (missing mmproj, text-only model, ...). Populated on first such failure so
     * later VISION/LIGHT_BOTH calls skip the doomed HTTP round-trip instead of
     * repeatedly failing — this is deliberately NOT routed through CircuitBreaker,
     * since a provider name is shared across task types and blocking it there would
     * also stall unrelated TEXT/LIGHT_TEXT calls (e.g. ANSWER/CLASSIFIER) that the
     * same provider can still serve fine.
     */
    private final Set<String> visionUnsupportedProviders = ConcurrentHashMap.newKeySet();

    /** In-flight counter for ungated (indexing/background) calls — see {@link
     *  BackgroundLlmConcurrencyTracker} for why the header concurrency indicator needs this. */
    private final BackgroundLlmConcurrencyTracker backgroundConcurrencyTracker;

    public LlmRouter(List<LlmProvider> providers, LlmUsageRepository usageRepo,
                     CircuitBreaker circuitBreaker, RoutingMode defaultMode) {
        this(providers, usageRepo, circuitBreaker, defaultMode, 180);
    }

    public LlmRouter(List<LlmProvider> providers, LlmUsageRepository usageRepo,
                     CircuitBreaker circuitBreaker, RoutingMode defaultMode,
                     int readTimeoutSeconds) {
        this(providers, usageRepo, circuitBreaker, defaultMode, readTimeoutSeconds,
                Map.of(), 3, 20);
    }

    public LlmRouter(List<LlmProvider> providers, LlmUsageRepository usageRepo,
                     CircuitBreaker circuitBreaker, RoutingMode defaultMode,
                     int readTimeoutSeconds,
                     Map<String, Integer> providerConcurrency,
                     int defaultProviderConcurrency, int permitWaitTimeoutSeconds) {
        // Existing callers (incl. tests) get a fresh empty toggle → nothing disabled, zero behavior
        // change. Only LlmConfig injects the shared @Component so /settings toggles affect this router.
        this(providers, usageRepo, circuitBreaker, defaultMode, readTimeoutSeconds,
                providerConcurrency, defaultProviderConcurrency, permitWaitTimeoutSeconds, new ProviderToggle());
    }

    public LlmRouter(List<LlmProvider> providers, LlmUsageRepository usageRepo,
                     CircuitBreaker circuitBreaker, RoutingMode defaultMode,
                     int readTimeoutSeconds,
                     Map<String, Integer> providerConcurrency,
                     int defaultProviderConcurrency, int permitWaitTimeoutSeconds,
                     ProviderToggle providerToggle) {
        // Existing callers (incl. tests) get a fresh, un-shared tracker — harmless, since nothing
        // else reads it unless the same instance is also wired into OperationsController (only
        // LlmConfig does that, via the overload below).
        this(providers, usageRepo, circuitBreaker, defaultMode, readTimeoutSeconds,
                providerConcurrency, defaultProviderConcurrency, permitWaitTimeoutSeconds, providerToggle,
                new BackgroundLlmConcurrencyTracker());
    }

    public LlmRouter(List<LlmProvider> providers, LlmUsageRepository usageRepo,
                     CircuitBreaker circuitBreaker, RoutingMode defaultMode,
                     int readTimeoutSeconds,
                     Map<String, Integer> providerConcurrency,
                     int defaultProviderConcurrency, int permitWaitTimeoutSeconds,
                     ProviderToggle providerToggle, BackgroundLlmConcurrencyTracker backgroundConcurrencyTracker) {
        this.providers = providers;
        this.usageRepo = usageRepo;
        this.circuitBreaker = circuitBreaker;
        this.defaultMode = defaultMode;
        this.readTimeoutSeconds = readTimeoutSeconds;
        this.defaultProviderConcurrency = defaultProviderConcurrency > 0 ? defaultProviderConcurrency : 3;
        this.permitWaitTimeoutSeconds = permitWaitTimeoutSeconds > 0 ? permitWaitTimeoutSeconds : 20;
        this.providerToggle = providerToggle;
        this.backgroundConcurrencyTracker = backgroundConcurrencyTracker;
        for (LlmProvider p : providers) {
            int concurrency = (providerConcurrency != null && providerConcurrency.containsKey(p.name()))
                    ? providerConcurrency.get(p.name()) : this.defaultProviderConcurrency;
            int capacity = Math.max(1, concurrency);
            providerGates.put(p.name(), new Semaphore(capacity));
            providerCapacity.put(p.name(), capacity);
        }
    }

    /**
     * Blocks (up to {@code app.llm.permit-wait-timeout-seconds}) for a concurrency slot on
     * {@code provider}'s per-server gate. Throws {@link LlmBackpressureException} (HTTP 429,
     * NOT a provider failure — no circuit-breaker block) when the wait times out.
     */
    public Permit acquirePermit(LlmProvider provider) {
        Semaphore gate = providerGates.computeIfAbsent(provider.name(),
                n -> new Semaphore(defaultProviderConcurrency));
        boolean acquired;
        try {
            acquired = gate.tryAcquire(permitWaitTimeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmBackpressureException(
                    "Interrupted while waiting for provider [" + provider.name() + "] capacity", 1);
        }
        if (!acquired) {
            log.warn("[BACKPRESSURE] provider={} concurrency slot wait exceeded {}s, rejecting with 429",
                    provider.name(), permitWaitTimeoutSeconds);
            throw new LlmBackpressureException(
                    "Provider [" + provider.name() + "] is at capacity. Please retry shortly.",
                    permitWaitTimeoutSeconds);
        }
        return gate::release;
    }

    /** 라우팅 모드에 맞는 첫 번째 사용 가능 LlmProvider 반환 (stream 플래그 포함). */
    public LlmProvider routeProvider(TaskType taskType, RoutingMode mode) {
        LlmProvider p = findFirst(taskType, roleOrder(mode), Set.of())
                .orElseThrow(() -> new LlmProviderExhaustedException(
                        "No available provider for task=" + taskType + " mode=" + mode));
        log.debug("[LLM route] provider={} task={} mode={} endpoint={}/chat/completions model={} stream={}",
                p.name(), taskType, mode, p.baseUrl(), p.model(), p.stream());
        return p;
    }

    /** 라우팅 모드에 맞는 첫 번째 사용 가능 ChatModel 반환. */
    public ChatModel route(TaskType taskType, RoutingMode mode) {
        return routeProvider(taskType, mode).chatModel();
    }

    /**
     * Tries each {@link TaskType} in order and returns the first provider found — for singleton
     * default-model resolution (e.g. {@code LlmConfig.primaryChatModel()}, the LLM behind
     * {@code MultiQueryExpander}) where a plain {@link #routeProvider} would fail on a
     * LIGHT_BOTH-only (local, no cloud key) setup that doesn't register a TEXT/BOTH provider.
     */
    public LlmProvider routeProviderWithFallback(List<TaskType> taskTypeOrder, RoutingMode mode) {
        for (TaskType t : taskTypeOrder) {
            try {
                return routeProvider(t, mode);
            } catch (LlmProviderExhaustedException ignored) {
                // try next
            }
        }
        throw new LlmProviderExhaustedException("No provider available for any of: " + taskTypeOrder);
    }

    /** 실행 + 토큰 기록 + Circuit Breaker 자동 전환. 인덱싱/백그라운드 경로용 — 동시성 게이트 미적용. */
    public String executeWithTracking(TaskType taskType, RoutingMode mode,
                                      Function<ChatModel, ChatResponse> call) {
        return executeWithTracking(taskType, mode, null, call);
    }

    /**
     * Same as {@link #executeWithTracking(TaskType, RoutingMode, Function)}, but records usage
     * under {@code usageLabelPrefix + provider.name()} instead of the bare provider name — see
     * {@link BackgroundUsage} for the reserved prefixes that separate background/non-chat LLM
     * calls (summarization, keyword extraction, etc.) from regular chat usage on /llm-usage.
     * Pass {@code null} for ordinary chat-serving calls (same behavior as the 3-arg overload).
     *
     * <p>Indexing/background callers (KeywordExtractor, MarkdownCorrectionService,
     * VisionDescriptionService, ...) use this — no per-provider wait-cap, since they already
     * throttle via their own semaphore and have no synchronous HTTP caller waiting on a deadline.
     */
    public String executeWithTracking(TaskType taskType, RoutingMode mode, String usageLabelPrefix,
                                      Function<ChatModel, ChatResponse> call) {
        return executeWithTracking(taskType, roleOrder(mode), usageLabelPrefix, call, new HashSet<>(), false).text();
    }

    /**
     * Same as {@link #executeWithTracking(TaskType, RoutingMode, Function)}, but bounded
     * by the target provider's per-server concurrency gate ({@link #acquirePermit}): waits up to
     * {@code app.llm.permit-wait-timeout-seconds} for a slot, then fails fast with
     * {@link LlmBackpressureException} (HTTP 429 + Retry-After) instead of piling up behind the
     * server. Use this for the interactive chat/query path (CLASSIFIER, ANSWER, evaluation,
     * DirectAnswer, reranking) — never for indexing/background calls.
     */
    public String executeGated(TaskType taskType, RoutingMode mode,
                               Function<ChatModel, ChatResponse> call) {
        return executeGated(taskType, mode, null, call);
    }

    public String executeGated(TaskType taskType, RoutingMode mode, String usageLabelPrefix,
                               Function<ChatModel, ChatResponse> call) {
        return executeWithTracking(taskType, roleOrder(mode), usageLabelPrefix, call, new HashSet<>(), true).text();
    }

    /** Answer text plus the real input/output token usage read from the LLM response's metadata. */
    public record LlmResult(String text, int inputTokens, int outputTokens) {}

    /**
     * Same as {@link #executeGated(TaskType, RoutingMode, Function)}, but also returns the real
     * input/output token usage from the response — for callers that need to surface per-turn
     * totals to the user (as opposed to only the aggregate {@code /llm-usage} dashboard, which
     * every {@code executeGated}/{@code executeWithTracking} call already records regardless).
     */
    public LlmResult executeGatedWithUsage(TaskType taskType, RoutingMode mode,
                                           Function<ChatModel, ChatResponse> call) {
        return executeWithTracking(taskType, roleOrder(mode), null, call, new HashSet<>(), true);
    }

    /**
     * Records approximate usage (chars/4, mirrors {@code TrackingEmbeddingModel}'s embedding
     * fallback) for calls whose real token count isn't available — real-time SSE token streaming
     * reads only content deltas, never a {@link ChatResponse} with usage metadata, and reading
     * one would mean buffering the full response first and breaking the token-by-token UX.
     * No-op when {@code answerText} is blank (failed/empty call — nothing was actually served).
     */
    public void recordApproxUsage(String providerName, String promptText, String answerText) {
        if (answerText == null || answerText.isBlank()) return;
        try {
            usageRepo.record(providerName, approxTokens(promptText), approxTokens(answerText));
        } catch (Exception e) {
            // Best-effort analytics — the answer was already served to the user, so a usage-table
            // write failure (e.g. SQLITE_FULL) must never surface as if the call itself failed.
            log.warn("[USAGE] Failed to record approx usage for provider={}: {}", providerName, e.getMessage());
        }
    }

    /**
     * Rough token estimate for text whose real usage isn't available (streaming answers, whose
     * caller reads only content deltas, never a {@link ChatResponse}) — the same heuristic
     * {@link #recordApproxUsage} records to the aggregate usage table, exposed so streaming callers
     * can also reflect it in their own per-turn {@code AgentState} totals.
     *
     * <p>Delegates to {@link TokenEstimator}, this app's single estimation assumption. It used to be
     * a bare {@code chars/4} here, which is the English rule of thumb and undercounted Korean
     * streaming usage by roughly 4x in {@code /llm-usage} — while {@code ResponseMode}'s budgets a
     * few classes away assumed 1 token per Korean character. English-only text estimates exactly as
     * before.
     */
    public static long approxTokens(String text) {
        return TokenEstimator.estimate(text);
    }

    public boolean hasLocalProvider() {
        return providers.stream()
                .anyMatch(p -> p.role() == LOCAL
                        && !circuitBreaker.isBlocked(p.name())
                        && !providerToggle.isDisabled(p.name()));
    }

    /**
     * Whether at least one runtime-enabled provider can serve {@code task}.
     *
     * <p>Capability is asked of {@link LlmProvider#supports(TaskType)} rather than compared against
     * a hand-maintained list of provider types. The previous form took the types as varargs and its
     * one caller passed {@code (BOTH, VISION)} for "can we describe images?", silently omitting
     * {@code LIGHT_BOTH} — a type that does serve {@code VISION}. The document upload page then
     * disabled its "이미지 설명 추가" checkbox on a deployment where image description worked. Such a
     * list has to be revisited every time {@code supports()} changes; deriving it removes that
     * class of drift rather than fixing one instance of it.
     *
     * <p>Deliberately ignores the circuit breaker: a temporary block does not mean the capability
     * is absent, and a UI control should not flicker with provider health. Operator enable/disable
     * via {@code /settings} does count — that is a standing decision, not a transient state.
     */
    public boolean hasEnabledProviderFor(TaskType task) {
        if (task == null) return false;
        return providers.stream()
                .anyMatch(p -> p.supports(task) && !providerToggle.isDisabled(p.name()));
    }

    /**
     * Whether the dedicated MICRO_TEXT offload model ({@code role=LOCAL, priority=0}, i.e. the
     * {@code local-fast} provider behind {@code LOCAL_FAST_LLM_URL} — LLM_ROUTING.md §9) is
     * currently available: registered (a blank base-url disables it outright at startup, LlmConfig
     * G2), not circuit-broken, not runtime-disabled via {@code /settings}.
     *
     * <p>Callers use this to skip an optional background chore entirely rather than let it fall
     * through to the answer-serving {@code priority=1} tier — {@code MICRO_TEXT} routing does fall
     * back to that tier by design ({@code BOTH} absorbs {@code MICRO_TEXT}), which is right for
     * chores the app can't do any other way, but wrong for ones with a free non-LLM alternative
     * (see {@code ConversationSummarizerService}, which reuses the answer's own "## 요약" section).
     *
     * <p><b>{@code supports(MICRO_TEXT)} is part of the test, not just role+priority.</b>
     * {@code role=LOCAL, priority=0} is not by itself proof that a MICRO_TEXT chore has somewhere
     * to go: the commented-out {@code local-vision} example in {@code application.properties} —
     * which LLM_ROUTING.md §8 actively recommends registering for image-heavy corpora — is
     * {@code type=VISION} at exactly that role+priority, and {@code VISION} serves only
     * {@code VISION}. Without this check, enabling a Vision model made this method report an
     * offload tier that {@code findFirst} would then skip, sending the chore straight to the
     * {@code priority=1} answer model — the precise outcome the gate exists to prevent, and one
     * that shows up as an unexplained {@code summary:} LLM call on a deployment with no small
     * model. ({@code type=LIGHT_TEXT} — LLM_ROUTING.md §9's more aggressive "A안" — still passes,
     * correctly: it does serve MICRO_TEXT and is still a dedicated priority-0 tier.)
     */
    public boolean hasMicroTextOffloadProvider() {
        return providers.stream()
                .anyMatch(p -> p.role() == LOCAL
                        && p.priority() == 0
                        && p.supports(TaskType.MICRO_TEXT)
                        && !circuitBreaker.isBlocked(p.name())
                        && !providerToggle.isDisabled(p.name()));
    }

    /** {@code inUse}/{@code capacity} snapshot for {@link #localTier1Concurrency()}. */
    public record ConcurrencySnapshot(int inUse, int capacity) {}

    /**
     * In-flight vs. capacity for the "main" LOCAL tier — {@code role=LOCAL, priority=1}, the
     * answer-serving local model(s) (LLM_ROUTING.md §9's {@code priority=0} MICRO_TEXT offload
     * model, e.g. {@code local-fast}, is deliberately excluded — it's not the tier users' chat
     * requests actually wait on). Summed across every provider at that role+priority that isn't
     * runtime-disabled via {@code /settings} — a horizontally load-balanced pair (e.g. {@code
     * local} + {@code local-2}) reports combined capacity. Empty only when no such provider is
     * registered/enabled at all, so the header's LLM indicator can hide itself entirely instead of
     * showing a meaningless {@code 0/0}.
     *
     * <p>A circuit-broken provider still contributes its full {@code concurrency} to {@code
     * capacity} — but that whole amount counts as {@code inUse} rather than being excluded: it
     * isn't accepting any request right now, so from the indicator's point of view its slots are
     * just as "unavailable" as ones genuinely occupied by an in-flight call. (Excluding it
     * entirely was the old behavior — for a lone LOCAL provider that's blocked, this used to make
     * the whole indicator vanish instead of showing e.g. a fully-saturated {@code 3/3}.)
     */
    public Optional<ConcurrencySnapshot> localTier1Concurrency() {
        List<LlmProvider> matches = providers.stream()
                .filter(p -> p.role() == LOCAL && p.priority() == 1)
                .filter(p -> !providerToggle.isDisabled(p.name()))
                .toList();
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        int capacity = 0;
        int inUse = 0;
        for (LlmProvider p : matches) {
            int cap = providerCapacity.getOrDefault(p.name(), defaultProviderConcurrency);
            capacity += cap;
            if (circuitBreaker.isBlocked(p.name())) {
                inUse += cap;
            } else {
                Semaphore gate = providerGates.get(p.name());
                int free = gate != null ? gate.availablePermits() : cap;
                inUse += Math.max(0, cap - free);
            }
        }
        return Optional.of(new ConcurrencySnapshot(inUse, capacity));
    }

    /** Returns the name of the first available provider for the given routing, or "unknown". */
    public String findProviderName(TaskType taskType, RoutingMode mode) {
        return findFirst(taskType, roleOrder(mode), Set.of())
                .map(LlmProvider::name)
                .orElse("unknown");
    }

    public RoutingMode getDefaultMode() { return defaultMode; }

    // ── Private ────────────────────────────────────────────────────────────

    private static List<ProviderRole> roleOrder(RoutingMode mode) {
        return switch (mode) {
            case COST_FIRST, PROGRESSIVE -> List.of(LOCAL, NORMAL, PREMIUM);
            case QUALITY_FIRST           -> List.of(PREMIUM, NORMAL, LOCAL);
            case LOCAL_ONLY              -> List.of(LOCAL);
        };
    }

    Optional<LlmProvider> findFirst(TaskType taskType,
                                    List<ProviderRole> roleOrder,
                                    Set<String> excluded) {
        boolean imageTask = isImageTask(taskType);
        for (ProviderRole role : roleOrder) {
            List<LlmProvider> eligible = providers.stream()
                    .filter(x -> x.role() == role
                            && x.supports(taskType)
                            && x.hasValidApiKey()
                            && !circuitBreaker.isBlocked(x.name())
                            && !providerToggle.isDisabled(x.name())
                            && !excluded.contains(x.name())
                            && !(imageTask && visionUnsupportedProviders.contains(x.name())))
                    .toList();
            if (!eligible.isEmpty()) {
                return Optional.of(selectWithinTopPriority(eligible));
            }
        }
        return Optional.empty();
    }

    /**
     * {@code providers} (and therefore {@code eligible}, a filtered view of it)
     * is priority-ascending, so the lowest priority present is always the preferred tier —
     * unchanged tie-break/failover semantics for the common case of one provider per priority.
     * When more than one provider shares that lowest priority (e.g. two LOCAL providers
     * registered for horizontal throughput), distribute across them by least-in-flight — the one
     * with the most free permits on its concurrency gate — instead of always
     * picking the first-registered one. This reuses the existing per-provider {@link Semaphore},
     * so it's a "least-connections" load balancer with no new bookkeeping. Ties (e.g. both fully
     * idle) deterministically keep the first-registered provider at that priority.
     */
    private LlmProvider selectWithinTopPriority(List<LlmProvider> eligible) {
        int topPriority = eligible.get(0).priority();
        LlmProvider best = eligible.get(0);
        int bestFreePermits = availablePermits(best);
        for (int i = 1; i < eligible.size(); i++) {
            LlmProvider candidate = eligible.get(i);
            if (candidate.priority() != topPriority) break; // priority-ascending: no more ties possible
            int freePermits = availablePermits(candidate);
            if (freePermits > bestFreePermits) {
                best = candidate;
                bestFreePermits = freePermits;
            }
        }
        return best;
    }

    private int availablePermits(LlmProvider provider) {
        Semaphore gate = providerGates.get(provider.name());
        return gate != null ? gate.availablePermits() : defaultProviderConcurrency;
    }

    private static boolean isImageTask(TaskType taskType) {
        return taskType == TaskType.VISION || taskType == TaskType.LIGHT_BOTH;
    }

    private LlmResult executeWithTracking(TaskType taskType, List<ProviderRole> roleOrder, String usageLabelPrefix,
                                       Function<ChatModel, ChatResponse> call,
                                       Set<String> tried, boolean gated) {
        LlmProvider provider = findFirst(taskType, roleOrder, tried)
                .orElseThrow(() -> new LlmProviderExhaustedException(
                        "All providers exhausted for task=" + taskType));
        tried.add(provider.name());
        try {
            return executeSingleTracked(provider, taskType, usageLabelPrefix, call, gated);
        } catch (LlmBackpressureException e) {
            // Capacity pressure, not a provider failure — do not block the circuit breaker or
            // silently retry against another provider; let the 429 propagate to the caller.
            throw e;
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            if (status == 429 || status == 402 || status == 503) {
                String retryAfter = e.getResponseHeaders() != null
                        ? e.getResponseHeaders().getFirst("Retry-After") : null;
                blockForOverload(provider, taskType, roleOrder, tried, retryAfter);
            } else {
                // 5xx·4xx 도 같은 판단을 받는다 — 프로바이더가 하나뿐이면 차단은 우회가 아니라
                // 전면 중단이고, 재시작 중 서버가 잠깐 5xx 를 뱉는 경우가 흔하다.
                blockForFailure(provider, taskType, roleOrder, tried);
            }
            log.warn("Provider [{}] returned HTTP {}, trying next", provider.name(), status);
            return executeWithTracking(taskType, roleOrder, usageLabelPrefix, call, tried, gated);
        } catch (Exception e) {
            if (isTimeoutLike(e)) {
                // Client-side interrupt — provider is healthy; block would cascade into "All providers exhausted"
                log.warn("[TIMEOUT:LLM_HTTP] provider={} client-timeout (app.llm.read-timeout-seconds={}s), "
                        + "NOT blocking circuit breaker", provider.name(), readTimeoutSeconds);
                throw e;
            }
            boolean contextOverflow = isContextOverflow(e);
            if (contextOverflow) {
                // 이 요청의 프롬프트가 서버 컨텍스트를 넘은 것이지 프로바이더가 아픈 게 아니다.
                // 30초를 기다려도 같은 요청은 똑같이 실패하고(결정적 오류), 그동안 들어온 -- 작아서
                // 통과했을 -- 요청까지 "All providers exhausted" 로 함께 죽는다. mmproj 와 달리
                // 기억해 두지도 않는다: 저건 모델의 영구적 성질이지만 이건 요청 하나의 크기 문제라,
                // 다음 짧은 질문은 같은 프로바이더에서 멀쩡히 처리된다.
                log.warn("Provider [{}] rejected the prompt: context window exceeded — NOT blocking "
                        + "circuit breaker (waiting changes nothing; a smaller prompt fits). Lower "
                        + "app.search-top-k (hot, via /settings) first, then app.llm.max-tokens "
                        + "(restart required), or raise the LLM server's context size. "
                        + "See OPERATOR_MANUAL §8.", provider.name());
            } else if (isRequestTerminatedByServer(e)) {
                // 서버가 내려가면서 이 요청을 끊었다. 차단하면 서버가 올라온 뒤까지 그 차단이 남아
                // "재시작했는데도 계속 안 된다"가 된다 — isTimeoutLike 와 같은 이유로 통과시킨다.
                log.warn("Provider [{}] terminated the in-flight request (restart/model reload?) — "
                        + "NOT blocking circuit breaker; the server is usually back within seconds.",
                        provider.name());
            } else if (isVisionUnsupported(e)) {
                // Model lacks mmproj / image-input support — a request-shape limitation, not a
                // provider outage. Blocking here (like any other failure) would also stall
                // unrelated TEXT/LIGHT_TEXT tasks sharing this provider name until the block
                // expires. Remember it instead so future VISION/LIGHT_BOTH calls skip this
                // provider without even attempting the doomed call.
                visionUnsupportedProviders.add(provider.name());
                log.warn("Provider [{}] does not support image input (mmproj missing) — "
                        + "skipping image tasks for this provider, NOT blocking circuit breaker", provider.name());
            } else {
                blockForFailure(provider, taskType, roleOrder, tried);
            }
            log.warn("Provider [{}] threw {}: {}, trying next",
                    provider.name(), e.getClass().getSimpleName(), e.getMessage());
            try {
                return executeWithTracking(taskType, roleOrder, usageLabelPrefix, call, tried, gated);
            } catch (LlmProviderExhaustedException exhausted) {
                // 여기까지 왔다는 건 남은 프로바이더가 없다는 뜻이다. 그런데 이 체인에서 컨텍스트
                // 초과가 한 번이라도 있었다면 진짜 이유는 "프로바이더가 없다"가 아니라 "프롬프트가
                // 컨텍스트를 넘었다"이고, 그것이 사용자·운영자가 실제로 고칠 수 있는 유일한 것이다.
                // 안쪽 프레임이 이미 바꿔 던졌으면 그대로 흘려보낸다(가장 처음 넘친 프로바이더
                // 이름을 잃지 않기 위해).
                if (contextOverflow && !(exhausted instanceof LlmContextOverflowException)) {
                    throw new LlmContextOverflowException(provider.name(), e);
                }
                throw exhausted;
            }
        }
    }

    /**
     * For overload-type errors (429/402/503), a full circuit-breaker block is
     * only useful if there's a fallback provider to degrade to. When {@code provider} is the
     * only one currently viable for {@code taskType} (e.g. a lone LOCAL provider with no
     * NORMAL/PREMIUM configured — the common air-gapped/no-auth deployment), blocking it for
     * the full {@code circuit-breaker-minutes} just turns a transient capacity blip into a
     * multi-minute total outage for every subsequent request — worse than leaving it open, since
     * the concurrency gate already throttles how hard the app hammers it. An explicit
     * {@code Retry-After} header is still honored even with no fallback (authoritative operator
     * guidance from the provider itself, not a default we're second-guessing).
     */
    /**
     * 오버로드가 아닌 일반 실패(연결 거부·리셋·5xx 등)의 차단.
     *
     * <p>{@link #blockForOverload} 와 <b>같은 판단</b>을 한다 — 넘겨줄 상대가 있으면 차단이 곧
     * 우회이고, 없으면 차단은 전면 중단이다. 다른 점은 여기엔 존중해야 할 {@code Retry-After} 가
     * 없다는 것뿐이라 분기가 폴백 유무 하나로 단순해진다.
     *
     * <p>이 분기가 없던 동안 로컬 LLM 하나짜리 배포는 재시작 한 번에 30초를 통째로 잃었다.
     */
    private void blockForFailure(LlmProvider provider, TaskType taskType,
                                 List<ProviderRole> roleOrder, Set<String> tried) {
        if (findFirst(taskType, roleOrder, tried).isPresent()) {
            circuitBreaker.block(provider.name(), SHORT_BLOCK_SECONDS);
            return;
        }
        log.warn("[NO-FALLBACK] provider={} is the only viable provider for task={} — "
                + "blocking briefly ({}s) so a restart recovers immediately instead of after {}s",
                provider.name(), taskType, NO_FALLBACK_BLOCK_SECONDS, SHORT_BLOCK_SECONDS);
        circuitBreaker.block(provider.name(), NO_FALLBACK_BLOCK_SECONDS);
    }

    private void blockForOverload(LlmProvider provider, TaskType taskType, List<ProviderRole> roleOrder,
                                  Set<String> tried, String retryAfterHeader) {
        boolean hasFallback = findFirst(taskType, roleOrder, tried).isPresent();
        if (hasFallback || (retryAfterHeader != null && !retryAfterHeader.isBlank())) {
            circuitBreaker.block(provider.name(), retryAfterHeader);
        } else {
            log.warn("[NO-FALLBACK] provider={} is the only viable provider for task={} — "
                    + "blocking briefly ({}s) instead of the full circuit-breaker duration to avoid a total outage",
                    provider.name(), taskType, SHORT_BLOCK_SECONDS);
            circuitBreaker.block(provider.name(), SHORT_BLOCK_SECONDS);
        }
    }

    private static boolean isTimeoutLike(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof InterruptedException
                    || cur instanceof java.io.InterruptedIOException
                    || cur instanceof java.net.SocketTimeoutException
                    || cur instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /**
     * 프롬프트가 서버의 컨텍스트 윈도우를 넘어 거절된 것인가.
     *
     * <p>{@link #isTimeoutLike}(클라이언트 측 중단) · {@link #isVisionUnsupported}(모델 성질)와 같은
     * 갈래의 세 번째 판정이다 — <b>프로바이더 장애가 아니어서 차단하면 안 되는</b> 실패. 다만 앞의
     * 둘과 성격이 하나 다르다: mmproj 부재는 그 프로바이더의 영구적 한계라 기억해 두고 이후 이미지
     * 작업을 건너뛰지만, 컨텍스트 초과는 <b>이 요청 하나의 크기</b> 문제라 기억할 것이 없다. 다음
     * 짧은 질문은 같은 프로바이더에서 그대로 처리돼야 한다.
     *
     * <p><b>공개인 이유</b>: 채팅 스트리밍 경로({@code AnswerService.streamDirect()})는 이 라우터를
     * 거치지 않고 {@link org.springframework.ai.openai.api.OpenAiApi} 를 직접 호출하므로, 거기서
     * 올라오는 예외는 {@code LlmContextOverflowException} 으로 바뀌지 않은 <b>날것</b>이다. 축소
     * 재시도(§6.26-9)가 그 경로에서도 초과를 알아보려면 같은 판정이 필요한데, 마커 목록을 복사하면
     * 서버 문구가 추가될 때 한쪽만 고쳐진다.
     *
     * <p>서버마다 문구가 달라 메시지로 판정한다. 실제로 관측된 것은 LM Studio 의
     * {@code {"code":500,"message":"Context size has been exceeded."}} 인데, 이것이 HTTP 400 본문에
     * 실려 오고 Spring AI 가 {@code NonTransientAiException} 으로 감싸므로 위 {@code catch
     * (HttpStatusCodeException)} 가 아니라 일반 {@code catch} 로 떨어진다 — 그래서 여기서 걸러야 한다.
     *
     * <p><b>{@code "too many tokens"} 류는 일부러 넣지 않았다</b> — 레이트리밋 응답
     * ("Too many tokens per minute")과 문구가 겹쳐, 진짜 429 를 컨텍스트 초과로 잘못 읽으면
     * {@code blockForOverload()} 의 Retry-After 처리를 건너뛰게 된다.
     */
    public static boolean isContextOverflow(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null) {
                String lowered = msg.toLowerCase();
                for (String marker : CONTEXT_OVERFLOW_MARKERS) {
                    if (lowered.contains(marker)) return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }

    /** 소문자 부분 일치. 서버별 문구 — LM Studio · OpenAI · llama.cpp · Anthropic 순. */
    private static final List<String> CONTEXT_OVERFLOW_MARKERS = List.of(
            "context size has been exceeded",
            "context_length_exceeded",
            "maximum context length",
            "exceeds the available context",
            "exceed context size",
            "prompt is too long"
    );

    /**
     * 서버가 <b>처리 중이던 요청을 끊었다</b> — 재시작·모델 리로드·수동 취소. 차단하면 안 되는
     * 네 번째 실패 갈래다.
     *
     * <p><b>{@link #isTimeoutLike} 의 거울상</b>이라고 보면 된다. 그쪽은 클라이언트가 끊은 경우이고
     * 이쪽은 서버가 끊은 경우인데, "프로바이더가 아픈 것이 아니다"라는 결론은 같다. 실제로 관측된
     * 것은 로컬 LLM 을 재시작했을 때 진행 중이던 응답에 돌아온
     * {@code 400 - {"error":"terminated"}} 이고, 서버는 그 몇 초 뒤 멀쩡히 살아난다.
     *
     * <p><b>차단이 왜 해로운가.</b> 이 실패는 서버가 <b>내려가는 순간</b>에만 나오는데, 30초 차단은
     * 서버가 <b>이미 올라온 뒤</b>까지 이어진다. 그동안 들어온 요청은 프로바이더에 닿지도 못하고
     * {@code "All providers exhausted"} 로 죽어, 운영자에게는 "재시작했는데도 계속 안 된다"로 보인다
     * (실측: 차단 1회에 그 창 안의 재시도 3번이 전부 그렇게 실패했다). 게다가 그 메시지는 원인을
     * 가린다 — 프로바이더가 고갈된 것이 아니라 하나가 한 번 끊긴 것이다.
     *
     * <p>{@link #isContextOverflow} 와 마찬가지로 <b>기억하지 않는다</b>: 서버 수명주기의 한순간이지
     * 그 프로바이더의 성질이 아니다.
     *
     * <p>판정을 메시지로 하는 이유도 같다 — 이 응답 역시 HTTP 400 본문에 실려
     * {@code NonTransientAiException} 으로 감싸여 오므로 {@code catch (HttpStatusCodeException)} 에
     * 걸리지 않는다. 마커를 {@code "terminated"} 한 단어가 아니라 JSON 조각으로 둔 것은 그 단어가
     * 다른 오류 문구에도 흔히 들어가기 때문이다(예: "connection terminated by peer" — 그건 진짜
     * 네트워크 장애라 차단하는 편이 맞다).
     */
    static boolean isRequestTerminatedByServer(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null) {
                String lowered = msg.toLowerCase();
                for (String marker : REQUEST_TERMINATED_MARKERS) {
                    if (lowered.contains(marker)) return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }

    /** 소문자 부분 일치. 서버가 진행 중인 요청을 끊었을 때의 응답 본문 조각. */
    private static final List<String> REQUEST_TERMINATED_MARKERS = List.of(
            "\"error\":\"terminated\"",
            "\"error\": \"terminated\""
    );

    private static boolean isVisionUnsupported(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null) {
                String lowered = msg.toLowerCase();
                if (lowered.contains("image input is not supported") || lowered.contains("mmproj")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }

    private LlmResult executeSingleTracked(LlmProvider provider, TaskType taskType, String usageLabelPrefix,
                                        Function<ChatModel, ChatResponse> call, boolean gated) {
        log.debug("[LLM →] provider={} task={} endpoint={}/chat/completions model={}", provider.name(), taskType, provider.baseUrl(), provider.model());
        long t0 = System.currentTimeMillis();
        ChatResponse response;
        if (gated) {
            try (Permit permit = acquirePermit(provider)) {
                response = call.apply(provider.chatModel());
            }
        } else {
            // Ungated (indexing/background) calls never touch the per-provider Semaphore above, so
            // they'd otherwise be invisible to the header's LLM concurrency indicator — see
            // BackgroundLlmConcurrencyTracker.
            backgroundConcurrencyTracker.increment();
            try {
                response = call.apply(provider.chatModel());
            } finally {
                backgroundConcurrencyTracker.decrement();
            }
        }
        long elapsed = System.currentTimeMillis() - t0;
        var usage = response.getMetadata().getUsage();
        int in  = (usage != null && usage.getPromptTokens()     != null) ? usage.getPromptTokens()     : 0;
        int out = (usage != null && usage.getCompletionTokens() != null) ? usage.getCompletionTokens() : 0;
        String usageKey = usageLabelPrefix != null ? usageLabelPrefix + provider.name() : provider.name();
        try {
            usageRepo.record(usageKey, in, out);
        } catch (Exception e) {
            // The LLM call above already succeeded — a usage-table write failure (e.g. SQLITE_FULL)
            // must not be mistaken by the caller's catch(Exception) for a provider failure, which
            // would discard this response, trip the circuit breaker, and cascade into
            // "all providers exhausted" for unrelated concurrent calls sharing this provider.
            log.warn("[USAGE] Failed to record usage for provider={}: {}", provider.name(), e.getMessage());
        }
        String text = response.getResult().getOutput().getText();
        if (log.isDebugEnabled()) {
            String preview = (text != null && text.length() > 80) ? text.substring(0, 80) + "…" : text;
            log.debug("[LLM ←] provider={} task={} in={} out={} {}ms | {}",
                    provider.name(), taskType, in, out, elapsed, preview);
        }
        return new LlmResult(text, in, out);
    }
}
