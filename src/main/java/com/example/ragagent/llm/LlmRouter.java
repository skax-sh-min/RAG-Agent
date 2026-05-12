package com.example.ragagent.llm;

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

    /** 라우팅 모드에 맞는 첫 번째 사용 가능 ChatModel 반환. */
    public ChatModel route(TaskType taskType, RoutingMode mode) {
        return findFirst(taskType, roleOrder(mode), Set.of())
                .map(LlmProvider::chatModel)
                .orElseThrow(() -> new LlmProviderExhaustedException(
                        "No available provider for task=" + taskType + " mode=" + mode));
    }

    /** 실행 + 토큰 기록 + Circuit Breaker 자동 전환. */
    public String executeWithTracking(TaskType taskType, RoutingMode mode,
                                      Function<ChatModel, ChatResponse> call) {
        return executeWithTracking(taskType, roleOrder(mode), call, new HashSet<>());
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
            CompletableFuture<String> localF =
                    CompletableFuture.supplyAsync(() -> executeSingleTracked(local, taskType, call), exec);
            CompletableFuture<String> externalF =
                    CompletableFuture.supplyAsync(() -> executeSingleTracked(external, taskType, call), exec);
            return new DualResult(localF.join(), local.name(), externalF.join(), external.name());
        }
    }

    /** Provider names returned by executeDualStream. */
    public record DualProviders(String localProvider, String externalProvider) {}

    /**
     * DUAL 스트리밍: LOCAL과 외부 프로바이더를 Virtual Thread로 병렬 실행.
     * streamFn은 (model, tokenSink) → void 형태로, 호출자가 프롬프트를 포함한 스트리밍 로직을 제공.
     */
    public DualProviders executeDualStream(TaskType taskType,
                                            BiConsumer<ChatModel, Consumer<String>> streamFn,
                                            Consumer<String> localTokenSink,
                                            Consumer<String> externalTokenSink) {
        LlmProvider local = findFirst(taskType, List.of(LOCAL), Set.of())
                .orElseThrow(() -> new LlmProviderExhaustedException(
                        "DUAL requires a LOCAL provider. Register a LOCAL provider or switch mode."));
        LlmProvider external = findFirst(taskType, List.of(NORMAL, PREMIUM), Set.of())
                .orElseThrow(() -> new LlmProviderExhaustedException(
                        "DUAL requires at least one external provider (NORMAL or PREMIUM)."));

        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Void> localF = CompletableFuture.runAsync(
                    () -> streamFn.accept(local.chatModel(), localTokenSink), exec);
            CompletableFuture<Void> externalF = CompletableFuture.runAsync(
                    () -> streamFn.accept(external.chatModel(), externalTokenSink), exec);
            CompletableFuture.allOf(localF, externalF).join();
        }
        return new DualProviders(local.name(), external.name());
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

    private String executeWithTracking(TaskType taskType, List<ProviderRole> roleOrder,
                                       Function<ChatModel, ChatResponse> call,
                                       Set<String> tried) {
        LlmProvider provider = findFirst(taskType, roleOrder, tried)
                .orElseThrow(() -> new LlmProviderExhaustedException(
                        "All providers exhausted for task=" + taskType));
        tried.add(provider.name());
        try {
            return executeSingleTracked(provider, taskType, call);
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
            return executeWithTracking(taskType, roleOrder, call, tried);
        } catch (Exception e) {
            circuitBreaker.block(provider.name(), "30");
            log.warn("Provider [{}] threw {}: {}, trying next",
                    provider.name(), e.getClass().getSimpleName(), e.getMessage());
            return executeWithTracking(taskType, roleOrder, call, tried);
        }
    }

    private String executeSingleTracked(LlmProvider provider, TaskType taskType,
                                        Function<ChatModel, ChatResponse> call) {
        log.debug("[LLM →] provider={} task={}", provider.name(), taskType);
        long t0 = System.currentTimeMillis();
        ChatResponse response = call.apply(provider.chatModel());
        long elapsed = System.currentTimeMillis() - t0;
        var usage = response.getMetadata().getUsage();
        int in  = (usage != null && usage.getPromptTokens()     != null) ? usage.getPromptTokens()     : 0;
        int out = (usage != null && usage.getCompletionTokens() != null) ? usage.getCompletionTokens() : 0;
        usageRepo.record(provider.name(), in, out);
        String text = response.getResult().getOutput().getText();
        if (log.isDebugEnabled()) {
            String preview = (text != null && text.length() > 80) ? text.substring(0, 80) + "…" : text;
            log.debug("[LLM ←] provider={} task={} in={} out={} {}ms | {}",
                    provider.name(), taskType, in, out, elapsed, preview);
        }
        return text;
    }
}
