package com.example.ragagent.llm;

import com.example.ragagent.exception.LlmBackpressureException;
import com.example.ragagent.exception.LlmProviderExhaustedException;
import com.example.ragagent.repository.LlmUsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.web.client.HttpStatusCodeException;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.example.ragagent.llm.ProviderRole.*;

public class LlmRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);

    /** Short fallback block (seconds) for transient/overload-type failures — see {@link #blockForOverload}. */
    private static final String SHORT_BLOCK_SECONDS = "30";

    private final List<LlmProvider> providers; // priority 오름차순
    private final LlmUsageRepository usageRepo;
    private final CircuitBreaker circuitBreaker;
    private final RoutingMode defaultMode;
    private final double progressiveThreshold;
    private final int readTimeoutSeconds;

    /**
     * Per-provider concurrency gate for the interactive query/chat path (CLASSIFIER,
     * ANSWER, CRITIC-feeding evaluation, DUAL, DirectAnswer, reranking, multi-query expansion).
     * Sized from {@code AppProperties.ProviderConfig.concurrency()} (falls back to
     * {@code defaultProviderConcurrency}) so the app never sends more concurrent requests to a
     * single physical LLM server than it can actually serve (e.g. llama-server --parallel).
     * Deliberately NOT applied to indexing/background LLM calls ({@code executeWithTracking}),
     * which already have their own semaphore ({@code app.indexing.max-concurrent-llm-calls}) and
     * no synchronous HTTP caller waiting on a deadline — see {@link #executeGated}.
     */
    private final Map<String, Semaphore> providerGates = new ConcurrentHashMap<>();
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

    public LlmRouter(List<LlmProvider> providers, LlmUsageRepository usageRepo,
                     CircuitBreaker circuitBreaker, RoutingMode defaultMode,
                     double progressiveThreshold) {
        this(providers, usageRepo, circuitBreaker, defaultMode, progressiveThreshold, 180);
    }

    public LlmRouter(List<LlmProvider> providers, LlmUsageRepository usageRepo,
                     CircuitBreaker circuitBreaker, RoutingMode defaultMode,
                     double progressiveThreshold, int readTimeoutSeconds) {
        this(providers, usageRepo, circuitBreaker, defaultMode, progressiveThreshold, readTimeoutSeconds,
                Map.of(), 3, 20);
    }

    public LlmRouter(List<LlmProvider> providers, LlmUsageRepository usageRepo,
                     CircuitBreaker circuitBreaker, RoutingMode defaultMode,
                     double progressiveThreshold, int readTimeoutSeconds,
                     Map<String, Integer> providerConcurrency,
                     int defaultProviderConcurrency, int permitWaitTimeoutSeconds) {
        // Existing callers (incl. tests) get a fresh empty toggle → nothing disabled, zero behavior
        // change. Only LlmConfig injects the shared @Component so /settings toggles affect this router.
        this(providers, usageRepo, circuitBreaker, defaultMode, progressiveThreshold, readTimeoutSeconds,
                providerConcurrency, defaultProviderConcurrency, permitWaitTimeoutSeconds, new ProviderToggle());
    }

    public LlmRouter(List<LlmProvider> providers, LlmUsageRepository usageRepo,
                     CircuitBreaker circuitBreaker, RoutingMode defaultMode,
                     double progressiveThreshold, int readTimeoutSeconds,
                     Map<String, Integer> providerConcurrency,
                     int defaultProviderConcurrency, int permitWaitTimeoutSeconds,
                     ProviderToggle providerToggle) {
        this.providers = providers;
        this.usageRepo = usageRepo;
        this.circuitBreaker = circuitBreaker;
        this.defaultMode = defaultMode;
        this.progressiveThreshold = progressiveThreshold;
        this.readTimeoutSeconds = readTimeoutSeconds;
        this.defaultProviderConcurrency = defaultProviderConcurrency > 0 ? defaultProviderConcurrency : 3;
        this.permitWaitTimeoutSeconds = permitWaitTimeoutSeconds > 0 ? permitWaitTimeoutSeconds : 20;
        this.providerToggle = providerToggle;
        for (LlmProvider p : providers) {
            int concurrency = (providerConcurrency != null && providerConcurrency.containsKey(p.name()))
                    ? providerConcurrency.get(p.name()) : this.defaultProviderConcurrency;
            providerGates.put(p.name(), new Semaphore(Math.max(1, concurrency)));
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
        return executeWithTracking(taskType, roleOrder(mode), usageLabelPrefix, call, new HashSet<>(), false);
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
        return executeWithTracking(taskType, roleOrder(mode), usageLabelPrefix, call, new HashSet<>(), true);
    }

    /**
     * DUAL 모드 병렬 실행 (두 프로바이더 모두 동시성 게이트 적용).
     * LOCAL 프로바이더 미등록 시 즉시 LlmProviderExhaustedException.
     */
    public DualResult executeDual(TaskType taskType,
                                  Function<ChatModel, ChatResponse> call) {
        LlmProvider local = findFirst(taskType, List.of(LOCAL), Set.of())
                .orElseThrow(() -> new LlmProviderExhaustedException(
                        "DUAL requires a LOCAL provider. Register a LOCAL provider or switch mode."));
        LlmProvider external = findFirst(taskType, List.of(NORMAL, PREMIUM), Set.of())
                .orElseThrow(() -> new LlmProviderExhaustedException(
                        "DUAL requires at least one external provider (NORMAL or PREMIUM)."));

        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            // exceptionally() ensures one side's failure never cancels the other via exec.close()
            CompletableFuture<String> localF = CompletableFuture
                    .supplyAsync(() -> executeSingleTracked(local, taskType, null, call, true), exec)
                    .exceptionally(t -> {
                        log.warn("[DUAL] LOCAL call failed ({}): {}", local.name(), t.getMessage());
                        return "";
                    });
            CompletableFuture<String> externalF = CompletableFuture
                    .supplyAsync(() -> executeSingleTracked(external, taskType, null, call, true), exec)
                    .exceptionally(t -> {
                        log.warn("[DUAL] external call failed ({}): {}", external.name(), t.getMessage());
                        return "";
                    });
            return new DualResult(localF.join(), local.name(), externalF.join(), external.name());
        }
    }

    /** Provider names returned by executeDualStream. */
    public record DualProviders(String localProvider, String externalProvider) {}

    /**
     * DUAL 스트리밍: LOCAL과 외부 프로바이더를 Virtual Thread로 병렬 실행.
     * callFn은 (provider, tokenSink) → void 형태. provider.stream() 에 따라 호출자가 스트림/블로킹 분기.
     */
    public DualProviders executeDualStream(TaskType taskType,
                                            BiConsumer<LlmProvider, Consumer<String>> callFn,
                                            Consumer<String> localTokenSink,
                                            Consumer<String> externalTokenSink) {
        LlmProvider local = findFirst(taskType, List.of(LOCAL), Set.of())
                .orElseThrow(() -> new LlmProviderExhaustedException(
                        "DUAL requires a LOCAL provider. Register a LOCAL provider or switch mode."));
        LlmProvider external = findFirst(taskType, List.of(NORMAL, PREMIUM), Set.of())
                .orElseThrow(() -> new LlmProviderExhaustedException(
                        "DUAL requires at least one external provider (NORMAL or PREMIUM)."));

        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            // exceptionally() ensures one side's failure never cancels the other via exec.close()
            CompletableFuture<Void> localF = CompletableFuture
                    .runAsync(() -> {
                        try (Permit permit = acquirePermit(local)) {
                            callFn.accept(local, localTokenSink);
                        }
                    }, exec)
                    .exceptionally(t -> {
                        log.warn("[DUAL] LOCAL call failed ({}): {}", local.name(), t.getMessage());
                        return null;
                    });
            CompletableFuture<Void> externalF = CompletableFuture
                    .runAsync(() -> {
                        try (Permit permit = acquirePermit(external)) {
                            callFn.accept(external, externalTokenSink);
                        }
                    }, exec)
                    .exceptionally(t -> {
                        log.warn("[DUAL] external call failed ({}): {}", external.name(), t.getMessage());
                        return null;
                    });
            CompletableFuture.allOf(localF, externalF).join();
        }
        return new DualProviders(local.name(), external.name());
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

    private static long approxTokens(String text) {
        return text == null ? 0 : text.length() / 4;
    }

    public boolean hasLocalProvider() {
        return providers.stream()
                .anyMatch(p -> p.role() == LOCAL
                        && !circuitBreaker.isBlocked(p.name())
                        && !providerToggle.isDisabled(p.name()));
    }

    /** Returns the name of the first available provider for the given routing, or "unknown". */
    public String findProviderName(TaskType taskType, RoutingMode mode) {
        return findFirst(taskType, roleOrder(mode), Set.of())
                .map(LlmProvider::name)
                .orElse("unknown");
    }

    public RoutingMode getDefaultMode() { return defaultMode; }
    public double getProgressiveThreshold() { return progressiveThreshold; }

    // ── Private ────────────────────────────────────────────────────────────

    private static List<ProviderRole> roleOrder(RoutingMode mode) {
        return switch (mode) {
            case COST_FIRST, PROGRESSIVE -> List.of(LOCAL, NORMAL, PREMIUM);
            case QUALITY_FIRST           -> List.of(PREMIUM, NORMAL, LOCAL);
            case DUAL                    -> List.of(LOCAL, NORMAL, PREMIUM);
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

    private String executeWithTracking(TaskType taskType, List<ProviderRole> roleOrder, String usageLabelPrefix,
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
                circuitBreaker.block(provider.name(), SHORT_BLOCK_SECONDS);
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
            if (isVisionUnsupported(e)) {
                // Model lacks mmproj / image-input support — a request-shape limitation, not a
                // provider outage. Blocking here (like any other failure) would also stall
                // unrelated TEXT/LIGHT_TEXT tasks sharing this provider name until the block
                // expires. Remember it instead so future VISION/LIGHT_BOTH calls skip this
                // provider without even attempting the doomed call.
                visionUnsupportedProviders.add(provider.name());
                log.warn("Provider [{}] does not support image input (mmproj missing) — "
                        + "skipping image tasks for this provider, NOT blocking circuit breaker", provider.name());
            } else {
                circuitBreaker.block(provider.name(), SHORT_BLOCK_SECONDS);
            }
            log.warn("Provider [{}] threw {}: {}, trying next",
                    provider.name(), e.getClass().getSimpleName(), e.getMessage());
            return executeWithTracking(taskType, roleOrder, usageLabelPrefix, call, tried, gated);
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

    private String executeSingleTracked(LlmProvider provider, TaskType taskType, String usageLabelPrefix,
                                        Function<ChatModel, ChatResponse> call, boolean gated) {
        log.debug("[LLM →] provider={} task={} endpoint={}/chat/completions model={}", provider.name(), taskType, provider.baseUrl(), provider.model());
        long t0 = System.currentTimeMillis();
        ChatResponse response;
        if (gated) {
            try (Permit permit = acquirePermit(provider)) {
                response = call.apply(provider.chatModel());
            }
        } else {
            response = call.apply(provider.chatModel());
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
        return text;
    }
}
