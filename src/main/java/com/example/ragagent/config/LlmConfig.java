package com.example.ragagent.config;

import com.example.ragagent.exception.LlmProviderExhaustedException;
import com.example.ragagent.llm.ContextWindowProbe;
import com.example.ragagent.llm.MaxTokensCappingChatModel;
import com.example.ragagent.llm.ProviderContextWindows;
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
                                BackgroundLlmConcurrencyTracker backgroundConcurrencyTracker,
                                ProviderContextWindows contextWindows) {
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
                    // 컨텍스트 창: 운영자 선언이 최우선, 없으면 서버에 물어본다(LOCAL 만 — 클라우드
                    // 프로바이더는 이 엔드포인트가 없고, 컨텍스트가 넉넉해 문제가 되지도 않는다).
                    // 못 구하면 기록하지 않는다 = "모른다". 0 이나 추측값을 넣지 않는 것이 중요하다.
                    Integer declaredCtx = (cfg.contextSize() != null && cfg.contextSize() > 0)
                            ? cfg.contextSize() : null;
                    Integer effectiveCtx = declaredCtx;
                    if (effectiveCtx != null) {
                        contextWindows.record(cfg.name(), effectiveCtx,
                                ProviderContextWindows.Source.CONFIGURED);
                    } else if (isLocalRole(cfg)) {
                        effectiveCtx = ContextWindowProbe.probe(apiBase, cfg.model(),
                                connectTimeoutSeconds, readTimeoutSeconds).orElse(null);
                        if (effectiveCtx != null) {
                            contextWindows.record(cfg.name(), effectiveCtx,
                                    ProviderContextWindows.Source.PROBED);
                        }
                    }

                    // 프로바이더별 max-tokens (미설정/0 이하면 전역값) — concurrency 와 같은 폴백 규약.
                    int requestedMaxTokens = (cfg.maxTokens() != null && cfg.maxTokens() > 0)
                            ? cfg.maxTokens() : llmCfg.maxTokens();
                    int effectiveMaxTokens = capMaxTokensToContext(
                            cfg.name(), requestedMaxTokens, effectiveCtx);
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
                                    .maxTokens(effectiveMaxTokens)
                                    .build())
                            .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                            .build();
                    // defaultOptions 만으로는 부족하다 — 블로킹 호출부가 매번 자기 maxTokens 를
                    // 실어 보내 그 기본값을 덮어쓴다. 이 데코레이터가 프로바이더 선택 '이후'에
                    // 상한으로 눌러 준다(MaxTokensCappingChatModel 클래스 주석 참고).
                    ChatModel model = new LoggingChatModel(
                            new MaxTokensCappingChatModel(rawModel, cfg.name(), effectiveMaxTokens),
                            cfg.name(), resolvedUrl, effectiveApiKey, cfg.model());
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
                .map(p -> "%s(%s/%s/p%d/stream=%b/concurrency=%d/ctx=%s) → %s [%s]".formatted(p.name(), p.role(), p.type(),
                        p.priority(), p.stream(), providerConcurrency.getOrDefault(p.name(), llmCfg.defaultProviderConcurrency()),
                        contextWindows.find(p.name())
                                .map(w -> w.tokens() + "/" + w.source().name().toLowerCase())
                                .orElse("?"),   // "?" = 선언도 탐지도 없음 → 입력 예산을 짤 근거가 없다
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
     * 출력 예약이 컨텍스트 창을 통째로 먹어버리는 설정을 기동 시점에 잡아낸다.
     *
     * <p>OpenAI 호환 서버에서 {@code max_tokens} 는 <b>예약</b>이다 — 프롬프트가 아무리 짧아도
     * {@code 프롬프트 + max_tokens} 가 창을 넘으면 거절당한다. 그래서 {@code max-tokens} 가 창보다
     * 크거나 같으면 <b>어떤 요청도 성공할 수 없다</b>: 입력에 남는 자리가 0 이하다. 설정만 보고는
     * 아무도 눈치채지 못하고, 증상은 매 질문마다 "Context size has been exceeded" 로만 나타난다.
     *
     * <p>그래서 창을 아는 경우에 한해 <b>창의 절반</b>으로 눌러 준다. 절반인 이유는 그 이상을 출력에
     * 예약하면 남는 입력 자리가 출력보다 작아지는데, 이 앱은 RAG 라 입력(검색 문서 + 대화 이력)이
     * 출력보다 큰 것이 정상이기 때문이다. 임의의 안전 마진이 아니라 "입력이 출력보다 작아지면 안
     * 된다"는 경계값이다.
     *
     * <p>조용히 고치지 않고 WARN 을 남긴다 — 운영자가 적어 둔 숫자와 실제로 쓰이는 숫자가 다른
     * 상태이므로, {@code SettingsService.warnOnDivergingOverrides()} 와 같은 이유로 알려야 한다.
     * 창을 모르면({@code null}) 아무것도 하지 않는다: 모르는 값으로 남의 설정을 깎을 수는 없다.
     */
    // package-private static: 순수 계산이라 빈을 띄우지 않고 검사할 수 있어야 한다
    // (SettingsService.formatModeBudgetForTest 와 같은 선례).
    static int capMaxTokensToContext(String providerName, int requested, Integer contextTokens) {
        if (contextTokens == null || requested < contextTokens) return requested;
        int capped = Math.max(256, contextTokens / 2);
        log.warn("""
                [LLM CONFIG] provider=[{}] max-tokens({}) >= context-size({}) — 입력에 남는 자리가 없어 \
                모든 요청이 컨텍스트 초과로 실패합니다. {} 로 낮춰 실행합니다.
                  -> app.llm.providers[N].max-tokens 를 창보다 충분히 작게 두거나, 서버의 컨텍스트를 키우세요.""",
                providerName, requested, contextTokens, capped);
        return capped;
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
