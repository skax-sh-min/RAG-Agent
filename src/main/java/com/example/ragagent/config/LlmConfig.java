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
import org.springframework.web.client.RestClient;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Configuration
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    public LlmRouter llmRouter(AppProperties props, LlmUsageRepository usageRepo,
                                CircuitBreaker circuitBreaker, ProviderToggle providerToggle,
                                BackgroundLlmConcurrencyTracker backgroundConcurrencyTracker) {
        AppProperties.LlmConfig llmCfg = props.llmSafe();
                int connectTimeoutSeconds = llmCfg.connectTimeoutSeconds();
                int readTimeoutSeconds = llmCfg.readTimeoutSeconds();

        // G1: LOCAL providers target a local endpoint (e.g. llama-server) that needs no api-key.
        // Only cloud providers (NORMAL/PREMIUM) with a blank key are disabled.
        llmCfg.providers().stream()
                .filter(cfg -> (cfg.apiKey() == null || cfg.apiKey().isBlank()) && !isLocalRole(cfg))
                .forEach(cfg -> log.warn(
                        "Provider [{}] disabled — api-key is empty (set the corresponding env var)", cfg.name()));
        // G2: ANY provider (LOCAL included) with no base-url configured is disabled. LOCAL providers
        // are exempt from the api-key check above (G1) but not from this one — with multiple optional
        // LOCAL slots (small/local 1/local 2, §6.21), each needs its own explicit env var
        // (LOCAL_FAST_LLM_URL / LOCAL_LLM_URL / LOCAL_LLM_URL_2) or it silently registers a provider
        // pointing at nothing. Cloud providers already have a real default base-url (GEMINI_BASE_URL /
        // OPENAI_BASE_URL), so this is a no-op for them in practice.
        llmCfg.providers().stream()
                .filter(cfg -> cfg.baseUrl() == null || cfg.baseUrl().isBlank())
                .forEach(cfg -> log.warn(
                        "Provider [{}] disabled — base-url is empty (set the corresponding env var)", cfg.name()));

        // G3: verify every registered LOCAL-role provider actually answers GET {base}/v1/models and
        // that the configured model name is among the results — a wrong port or a typo'd model name
        // (LOCAL_LLM_MODEL / LOCAL_FAST_LLM_MODEL / LOCAL_LLM_MODEL_2) would otherwise surface only as
        // a confusing runtime chat failure much later. Fails startup entirely on mismatch/unreachable
        // (Spring Boot exits non-zero) — see AppProperties.LlmConfig.verifyLocalModelsOnStartup(),
        // default true. Cloud (NORMAL/PREMIUM) providers are not checked here.
        boolean verifyLocalModels = llmCfg.verifyLocalModelsOnStartup() == null || llmCfg.verifyLocalModelsOnStartup();

        List<LlmProvider> providers = llmCfg.providers().stream()
                .filter(AppProperties.ProviderConfig::isEnabled) // G1+G2 combined — see its javadoc
                .map(cfg -> {
                    String roleStr = cfg.role() != null ? cfg.role().toUpperCase() : "NORMAL";
                    String typeStr = cfg.type() != null ? cfg.type().toUpperCase() : "BOTH";
                    // LOCAL endpoints accept any token; substitute a non-blank placeholder so
                    // LlmProvider.hasValidApiKey() passes in LlmRouter (mirrors EmbeddingBeanConfig's "no-key").
                    String effectiveApiKey = (cfg.apiKey() != null && !cfg.apiKey().isBlank())
                            ? cfg.apiKey() : "no-key";
                    String resolvedUrl = cfg.baseUrl(); // non-blank, guaranteed by isEnabled() above (G2)
                    boolean providerStream = !Boolean.FALSE.equals(cfg.stream()); // default: true
                    // OpenAiApi.builder() appends /v1 internally, so strip it to avoid /v1/v1.
                    // resolvedUrl (with /v1) is kept for LlmProvider.baseUrl() and LoggingChatModel curl logs.
                    String apiBase = resolvedUrl.endsWith("/v1/") ? resolvedUrl.substring(0, resolvedUrl.length() - 4)
                                   : resolvedUrl.endsWith("/v1")  ? resolvedUrl.substring(0, resolvedUrl.length() - 3)
                                   : resolvedUrl;
                    if (isLocalRole(cfg) && verifyLocalModels) {
                        verifyLocalModel(cfg.name(), apiBase, cfg.model(), connectTimeoutSeconds, readTimeoutSeconds);
                    }
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
                            // §6.18 — was hardcoded temperature(0.0)/maxTokens(6000); now the effective
                            // app.llm.temperature / app.llm.max-tokens (LLM_TEMPERATURE / LLM_MAX_TOKENS).
                            // temperature here is only the startup-time fallback for framework-internal
                            // callers that build their own ChatClient around this bean and never pass a
                            // per-call ChatOptions (e.g. RetrievalService's MultiQueryExpander) — every
                            // call site this app owns (Classifier/Answer/Reranker/Direct) attaches the
                            // live effective temperature per call instead, so /settings changes apply
                            // without a restart for those. maxTokens stays view-only (restart to change).
                            .defaultOptions(OpenAiChatOptions.builder()
                                    .model(cfg.model())
                                    .temperature(llmCfg.temperature())
                                    .maxTokens(llmCfg.maxTokens())
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

        // Per-provider concurrency gate: falls back to defaultProviderConcurrency
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
                providerConcurrency, llmCfg.defaultProviderConcurrency(), llmCfg.permitWaitTimeoutSeconds(),
                providerToggle, backgroundConcurrencyTracker);
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

    /**
     * G3: calls the OpenAI-compatible {@code GET {apiBase}/v1/models} endpoint and throws if the
     * server is unreachable or the configured model isn't in the returned list. Called at bean
     * creation time — any exception here fails {@code llmRouter()} construction, which fails Spring
     * Boot startup (see caller for rationale). Reuses the same connect/read timeouts as the chat
     * calls; a slow-to-boot local server should raise {@code LLM_CONNECT_TIMEOUT_SECONDS} rather than
     * disable this check.
     */
    private void verifyLocalModel(String providerName, String apiBase, String model,
                                  int connectTimeoutSeconds, int readTimeoutSeconds) {
        List<String> availableModels;
        try {
            ModelsResponse response = HttpClientTimeouts.restClientBuilder(connectTimeoutSeconds, readTimeoutSeconds)
                    .baseUrl(apiBase)
                    .build()
                    .get()
                    .uri("/v1/models")
                    .retrieve()
                    .body(ModelsResponse.class);
            availableModels = (response != null && response.data() != null)
                    ? response.data().stream().map(ModelEntry::id).filter(Objects::nonNull).toList()
                    : List.of();
        } catch (Exception e) {
            log.error("""
                    [LLM STARTUP CHECK FAILED] provider=[{}] could not reach {}/v1/models — {}: {}
                      -> Is the server (e.g. LM Studio / llama-server) actually running at that address?
                      -> If the address/port is wrong, fix {} and restart.
                      -> If the server just starts later than this app, set LLM_VERIFY_LOCAL_MODELS_ON_STARTUP=false to skip this check.""",
                    providerName, apiBase, e.getClass().getSimpleName(), e.getMessage(), baseUrlEnvVarHint(providerName));
            throw new IllegalStateException(
                    "Local LLM provider [%s] is unreachable at %s/v1/models — is the server running and is the URL correct? (%s: %s)"
                            .formatted(providerName, apiBase, e.getClass().getSimpleName(), e.getMessage()), e);
        }
        if (!availableModels.contains(model)) {
            log.error("""
                    [LLM STARTUP CHECK FAILED] provider=[{}] configured model '{}' was not found at {}/v1/models
                      -> Models actually available there: {}
                      -> Fix {} to one of the models above, or load the intended model on that server.""",
                    providerName, model, apiBase, availableModels, modelEnvVarHint(providerName));
            throw new IllegalStateException(
                    "Local LLM provider [%s]: configured model '%s' was not found at %s/v1/models. Available models: %s"
                            .formatted(providerName, model, apiBase, availableModels));
        }
        log.info("Local LLM provider [{}] verified — model '{}' confirmed available at {}/v1/models",
                providerName, model, apiBase);
    }

    /**
     * Best-effort env var name for a G3 failure log — matches this file's default provider wiring
     * (providers[0]=local-fast/[1]=local/[2]=local-2 below) so an operator sees exactly which env
     * var to fix instead of having to cross-reference application.properties by hand. Any other
     * provider name (e.g. a custom local-vision slot) falls back to a generic pointer.
     */
    private static String baseUrlEnvVarHint(String providerName) {
        return switch (providerName) {
            case "local-fast" -> "LOCAL_FAST_LLM_URL";
            case "local" -> "LOCAL_LLM_URL";
            case "local-2" -> "LOCAL_LLM_URL_2";
            default -> "this provider's base-url env var (see application.properties app.llm.providers[].base-url)";
        };
    }

    /** Same convention as {@link #baseUrlEnvVarHint}, for the model-name env var instead. */
    private static String modelEnvVarHint(String providerName) {
        return switch (providerName) {
            case "local-fast" -> "LOCAL_FAST_LLM_MODEL";
            case "local" -> "LOCAL_LLM_MODEL";
            case "local-2" -> "LOCAL_LLM_MODEL_2";
            default -> "this provider's model env var (see application.properties app.llm.providers[].model)";
        };
    }

    /** OpenAI-compatible {@code GET /v1/models} response shape — only the fields G3 needs. */
    private record ModelsResponse(List<ModelEntry> data) {}
    private record ModelEntry(String id) {}
}
