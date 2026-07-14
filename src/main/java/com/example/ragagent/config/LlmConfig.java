package com.example.ragagent.config;

import com.example.ragagent.exception.LlmProviderExhaustedException;
import com.example.ragagent.llm.*;
import com.example.ragagent.repository.LlmUsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.retry.support.RetryTemplate;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    public LlmRouter llmRouter(AppProperties props, LlmUsageRepository usageRepo,
                                CircuitBreaker circuitBreaker) {
        AppProperties.LlmConfig llmCfg = props.llmSafe();
                int connectTimeoutSeconds = llmCfg.connectTimeoutSeconds();
                int readTimeoutSeconds = llmCfg.readTimeoutSeconds();

        // G1: LOCAL providers target a local endpoint (e.g. llama-server) that needs no api-key.
        // Only cloud providers (NORMAL/PREMIUM) with a blank key are disabled.
        llmCfg.providers().stream()
                .filter(cfg -> (cfg.apiKey() == null || cfg.apiKey().isBlank()) && !isLocalRole(cfg))
                .forEach(cfg -> log.warn(
                        "Provider [{}] disabled — api-key is empty (set the corresponding env var)", cfg.name()));

        List<LlmProvider> providers = llmCfg.providers().stream()
                .filter(cfg -> (cfg.apiKey() != null && !cfg.apiKey().isBlank()) || isLocalRole(cfg))
                .map(cfg -> {
                    String roleStr = cfg.role() != null ? cfg.role().toUpperCase() : "NORMAL";
                    String typeStr = cfg.type() != null ? cfg.type().toUpperCase() : "BOTH";
                    // LOCAL endpoints accept any token; substitute a non-blank placeholder so
                    // LlmProvider.hasValidApiKey() passes in LlmRouter (mirrors EmbeddingBeanConfig's "no-key").
                    String effectiveApiKey = (cfg.apiKey() != null && !cfg.apiKey().isBlank())
                            ? cfg.apiKey() : "no-key";
                    String resolvedUrl = cfg.baseUrl() != null ? cfg.baseUrl() : "https://api.openai.com";
                    boolean providerStream = !Boolean.FALSE.equals(cfg.stream()); // default: true
                    // OpenAiApi.builder() appends /v1 internally, so strip it to avoid /v1/v1.
                    // resolvedUrl (with /v1) is kept for LlmProvider.baseUrl() and LoggingChatModel curl logs.
                    String apiBase = resolvedUrl.endsWith("/v1/") ? resolvedUrl.substring(0, resolvedUrl.length() - 4)
                                   : resolvedUrl.endsWith("/v1")  ? resolvedUrl.substring(0, resolvedUrl.length() - 3)
                                   : resolvedUrl;
                    OpenAiApi api = OpenAiApi.builder()
                            .baseUrl(apiBase)
                            .apiKey(effectiveApiKey)
                            .restClientBuilder(HttpClientTimeouts.restClientBuilder(
                                    connectTimeoutSeconds,
                                    readTimeoutSeconds))
                            .build();
                    // Disable Spring AI's DEFAULT_RETRY_TEMPLATE (maxAttempts=10, retries on
                    // ResourceAccessException which includes SocketTimeoutException). Without this,
                    // a single slow LLM call sends the same request up to 10 times.
                    // LlmRouter.executeWithTracking() handles retries at the router level instead.
                    ChatModel rawModel = OpenAiChatModel.builder()
                            .openAiApi(api)
                            .defaultOptions(OpenAiChatOptions.builder()
                                    .model(cfg.model())
                                    .temperature(0.0)
                                    .maxTokens(6000)
                                    .build())
                            .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                            .build();
                    ChatModel model = new LoggingChatModel(rawModel, cfg.name(),
                            resolvedUrl, effectiveApiKey, cfg.model());
                    return new LlmProvider(
                            cfg.name(),
                            TaskType.valueOf(typeStr),
                            ProviderRole.valueOf(roleStr),
                            cfg.priority(),
                            effectiveApiKey,
                            resolvedUrl,
                            cfg.model(),
                            providerStream,
                            model,
                            api);
                })
                .sorted(Comparator.comparingInt(LlmProvider::priority))
                .toList();

        RoutingMode defaultMode;
        try {
            defaultMode = llmCfg.defaultRoutingMode() != null
                    ? RoutingMode.valueOf(llmCfg.defaultRoutingMode().toUpperCase())
                    : RoutingMode.COST_FIRST;
        } catch (IllegalArgumentException e) {
            defaultMode = RoutingMode.COST_FIRST;
        }

        double threshold = llmCfg.progressiveThreshold() > 0
                ? llmCfg.progressiveThreshold() : 0.6;

        // §6.12 — per-provider concurrency gate: falls back to defaultProviderConcurrency
        // when a provider config omits its own `concurrency`.
        Map<String, Integer> providerConcurrency = new HashMap<>();
        for (AppProperties.ProviderConfig cfg : llmCfg.providers()) {
            int concurrency = (cfg.concurrency() != null && cfg.concurrency() > 0)
                    ? cfg.concurrency() : llmCfg.defaultProviderConcurrency();
            providerConcurrency.put(cfg.name(), concurrency);
        }

        log.info("LLM providers registered: {}", providers.stream()
                .map(p -> "%s(%s/%s/p%d/stream=%b/concurrency=%d) → %s [%s]".formatted(p.name(), p.role(), p.type(),
                        p.priority(), p.stream(), providerConcurrency.getOrDefault(p.name(), llmCfg.defaultProviderConcurrency()),
                        p.baseUrl(), p.model()))
                .toList());
        log.info("LLM HTTP timeouts: connect={}s read={}s, permit-wait={}s", connectTimeoutSeconds, readTimeoutSeconds,
                llmCfg.permitWaitTimeoutSeconds());

        return new LlmRouter(providers, usageRepo, circuitBreaker, defaultMode, threshold, readTimeoutSeconds,
                providerConcurrency, llmCfg.defaultProviderConcurrency(), llmCfg.permitWaitTimeoutSeconds());
    }

    @Bean
    @Primary
    public ChatModel primaryChatModel(LlmRouter router) {
        // TEXT for NORMAL/PREMIUM providers.
        // Fall back to LIGHT_TEXT so LIGHT_BOTH local-only setups (no cloud key) also work.
        for (TaskType taskType : List.of(TaskType.TEXT, TaskType.LIGHT_TEXT)) {
            try {
                ChatModel m = router.route(taskType, RoutingMode.COST_FIRST);
                log.info("primaryChatModel resolved via TaskType.{}", taskType);
                return m;
            } catch (LlmProviderExhaustedException ignored) {
                // try next
            }
        }
        throw new IllegalStateException(
                "No LLM provider available. Configure a LOCAL provider (LOCAL_LLM_URL) or set OPENAI_API_KEY / GEMINI_API_KEY.");
    }

    /** G1: a provider whose role is LOCAL targets a local endpoint (e.g. llama-server) that needs no api-key. */
    private static boolean isLocalRole(AppProperties.ProviderConfig cfg) {
        return cfg.role() != null && "LOCAL".equalsIgnoreCase(cfg.role().trim());
    }
}
