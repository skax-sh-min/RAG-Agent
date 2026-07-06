package com.example.ragagent.llm;

import com.example.ragagent.exception.LlmProviderExhaustedException;
import com.example.ragagent.repository.LlmUsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.web.client.HttpClientErrorException;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.example.ragagent.llm.ProviderRole.*;

public class LlmRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);

    private final List<LlmProvider> providers; // priority 오름차순
    private final LlmUsageRepository usageRepo;
    private final CircuitBreaker circuitBreaker;
    private final RoutingMode defaultMode;
    private final double progressiveThreshold;

    public LlmRouter(List<LlmProvider> providers, LlmUsageRepository usageRepo,
                     CircuitBreaker circuitBreaker, RoutingMode defaultMode,
                     double progressiveThreshold) {
        this.providers = providers;
        this.usageRepo = usageRepo;
        this.circuitBreaker = circuitBreaker;
        this.defaultMode = defaultMode;
        this.progressiveThreshold = progressiveThreshold;
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

    /** 실행 + 토큰 기록 + Circuit Breaker 자동 전환. */
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
     */
    public String executeWithTracking(TaskType taskType, RoutingMode mode, String usageLabelPrefix,
                                      Function<ChatModel, ChatResponse> call) {
        return executeWithTracking(taskType, roleOrder(mode), usageLabelPrefix, call, new HashSet<>());
    }

    /**
     * DUAL 모드 병렬 실행.
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
                    .supplyAsync(() -> executeSingleTracked(local, taskType, call), exec)
                    .exceptionally(t -> {
                        log.warn("[DUAL] LOCAL call failed ({}): {}", local.name(), t.getMessage());
                        return "";
                    });
            CompletableFuture<String> externalF = CompletableFuture
                    .supplyAsync(() -> executeSingleTracked(external, taskType, call), exec)
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
                    .runAsync(() -> callFn.accept(local, localTokenSink), exec)
                    .exceptionally(t -> {
                        log.warn("[DUAL] LOCAL call failed ({}): {}", local.name(), t.getMessage());
                        return null;
                    });
            CompletableFuture<Void> externalF = CompletableFuture
                    .runAsync(() -> callFn.accept(external, externalTokenSink), exec)
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
        usageRepo.record(providerName, approxTokens(promptText), approxTokens(answerText));
    }

    private static long approxTokens(String text) {
        return text == null ? 0 : text.length() / 4;
    }

    public boolean hasLocalProvider() {
        return providers.stream()
                .anyMatch(p -> p.role() == LOCAL && !circuitBreaker.isBlocked(p.name()));
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
        for (ProviderRole role : roleOrder) {
            Optional<LlmProvider> p = providers.stream()
                    .filter(x -> x.role() == role
                            && x.supports(taskType)
                            && x.hasValidApiKey()
                            && !circuitBreaker.isBlocked(x.name())
                            && !excluded.contains(x.name()))
                    .findFirst();
            if (p.isPresent()) return p;
        }
        return Optional.empty();
    }

    private String executeWithTracking(TaskType taskType, List<ProviderRole> roleOrder, String usageLabelPrefix,
                                       Function<ChatModel, ChatResponse> call,
                                       Set<String> tried) {
        LlmProvider provider = findFirst(taskType, roleOrder, tried)
                .orElseThrow(() -> new LlmProviderExhaustedException(
                        "All providers exhausted for task=" + taskType));
        tried.add(provider.name());
        try {
            return executeSingleTracked(provider, taskType, usageLabelPrefix, call);
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 429 || status == 402) {
                String retryAfter = e.getResponseHeaders() != null
                        ? e.getResponseHeaders().getFirst("Retry-After") : null;
                circuitBreaker.block(provider.name(), retryAfter);
            } else {
                circuitBreaker.block(provider.name(), "30");
            }
            log.warn("Provider [{}] returned HTTP {}, trying next", provider.name(), status);
            return executeWithTracking(taskType, roleOrder, usageLabelPrefix, call, tried);
        } catch (Exception e) {
            if (isTimeoutLike(e)) {
                // Client-side interrupt — provider is healthy; block would cascade into "All providers exhausted"
                log.warn("[TIMEOUT:LLM_HTTP] provider={} client-timeout, NOT blocking circuit breaker",
                        provider.name());
                throw e;
            }
            circuitBreaker.block(provider.name(), "30");
            log.warn("Provider [{}] threw {}: {}, trying next",
                    provider.name(), e.getClass().getSimpleName(), e.getMessage());
            return executeWithTracking(taskType, roleOrder, usageLabelPrefix, call, tried);
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

    private String executeSingleTracked(LlmProvider provider, TaskType taskType,
                                        Function<ChatModel, ChatResponse> call) {
        return executeSingleTracked(provider, taskType, null, call);
    }

    private String executeSingleTracked(LlmProvider provider, TaskType taskType, String usageLabelPrefix,
                                        Function<ChatModel, ChatResponse> call) {
        log.debug("[LLM →] provider={} task={} endpoint={}/chat/completions model={}", provider.name(), taskType, provider.baseUrl(), provider.model());
        long t0 = System.currentTimeMillis();
        ChatResponse response = call.apply(provider.chatModel());
        long elapsed = System.currentTimeMillis() - t0;
        var usage = response.getMetadata().getUsage();
        int in  = (usage != null && usage.getPromptTokens()     != null) ? usage.getPromptTokens()     : 0;
        int out = (usage != null && usage.getCompletionTokens() != null) ? usage.getCompletionTokens() : 0;
        String usageKey = usageLabelPrefix != null ? usageLabelPrefix + provider.name() : provider.name();
        usageRepo.record(usageKey, in, out);
        String text = response.getResult().getOutput().getText();
        if (log.isDebugEnabled()) {
            String preview = (text != null && text.length() > 80) ? text.substring(0, 80) + "…" : text;
            log.debug("[LLM ←] provider={} task={} in={} out={} {}ms | {}",
                    provider.name(), taskType, in, out, elapsed, preview);
        }
        return text;
    }
}
