package com.example.ragagent.config;

import com.example.ragagent.exception.LlmProviderExhaustedException;
import com.example.ragagent.llm.ContextWindowProbe;
import com.example.ragagent.llm.MaxTokensCappingChatModel;
import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.llm.TokenEstimateCalibration;
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
import org.springframework.web.client.RestClientResponseException;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Configuration
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    public LlmRouter llmRouter(AppProperties props, LlmUsageRepository usageRepo,
                                CircuitBreaker circuitBreaker, ProviderToggle providerToggle,
                                BackgroundLlmConcurrencyTracker backgroundConcurrencyTracker,
                                ProviderContextWindows contextWindows,
                                TokenEstimateCalibration tokenCalibration,
                                ProviderThinkingDialects thinkingDialects,
                                ThinkingObservations thinkingObservations) {
        AppProperties.LlmConfig llmCfg = props.llmSafe();
                int connectTimeoutSeconds = llmCfg.connectTimeoutSeconds();
                int readTimeoutSeconds = llmCfg.readTimeoutSeconds();

        // G1: LOCAL providers target a local endpoint (e.g. llama-server) that needs no api-key.
        // Only cloud providers (NORMAL/PREMIUM) with a blank key are disabled.
        llmCfg.providers().stream()
                .filter(cfg -> (cfg.apiKey() == null || cfg.apiKey().isBlank()) && !cfg.isLocal())
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
        // default true — except an unreachable server whose replica answered: that one starts blocked
        // (verifyLocalModels()). Cloud (NORMAL/PREMIUM) providers are not checked here.
        // The check is also a *name resolution*: a configured name that is only part of one server id
        // (`gemma-4-e4b` vs LM Studio's `google/gemma-4-e4b`) passes, and the server's id is what the
        // provider is built with from here on (ModelNameResolver has the rule).
        boolean verifyLocalModels = llmCfg.verifyLocalModelsOnStartup() == null || llmCfg.verifyLocalModelsOnStartup();
        // 검증은 프로바이더를 만들기 전에 전부 끝낸다 — 한 서버가 닿지 않을 때 기동을 멈출지는 같은 묶음의 다른 서버가
        // 닿았는가에 달려 있어서다(verifyLocalModels() 주석).
        Map<String, String> verifiedModels = new HashMap<>();
        Set<String> startedUnreachable = new HashSet<>();
        if (verifyLocalModels) {
            verifyLocalModels(llmCfg.providers(), connectTimeoutSeconds, readTimeoutSeconds,
                    verifiedModels, startedUnreachable);
        }

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
                    String apiBase = cfg.apiBase();
                    // 여기서부터 cfg.model() 대신 이 값을 쓴다 — 컨텍스트 탐지·defaultOptions·curl 로그·
                    // LlmProvider 네 자리 전부. 스트리밍 경로(AnswerService/DirectAnswerService)는
                    // provider.model() 을 요청 본문에 그대로 싣고 LM Studio 프로브는 id 를 정확히
                    // 비교하므로, 한 자리라도 설정값이 남으면 그 경로만 "없는 모델"을 부른다.
                    // 검증을 껐거나 서버가 기동 때 닿지 않았으면 물어본 적이 없으니 설정값 그대로다.
                    String effectiveModel = verifiedModels.getOrDefault(cfg.name(), cfg.model());
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
                    Integer declaredCtx = cfg.declaredContextSize();
                    Integer effectiveCtx = declaredCtx;
                    if (effectiveCtx != null) {
                        contextWindows.record(cfg.name(), effectiveCtx,
                                ProviderContextWindows.Source.CONFIGURED);
                    } else if (cfg.isLocal() && !startedUnreachable.contains(cfg.name())) {
                        // 방금 닿지 않은 서버에 다시 물으면 연결 타임아웃을 한 번 더 물 뿐이다 — 창은 "모름"으로 두고,
                        // 서버가 돌아온 뒤 컨텍스트 초과(자동 재탐지)나 /settings 의 재탐지 버튼이 채운다.
                        effectiveCtx = ContextWindowProbe.probe(apiBase, effectiveModel,
                                connectTimeoutSeconds, readTimeoutSeconds).orElse(null);
                        if (effectiveCtx != null) {
                            contextWindows.record(cfg.name(), effectiveCtx,
                                    ProviderContextWindows.Source.PROBED);
                        }
                    }
                    warnIfBelowSupportedWindow(cfg.name(), effectiveCtx, declaredCtx != null
                            ? ProviderContextWindows.Source.CONFIGURED : ProviderContextWindows.Source.PROBED);

                    // 프로바이더별 max-tokens (미설정/0 이하면 전역값) — concurrency 와 같은 폴백 규약.
                    // 기동 시점의 값은 defaultOptions 에 굽고 경고를 내는 데만 쓰고, 실제 상한은 아래
                    // 공급자가 호출마다 다시 계산한다 (§6.26 A6: app.llm.max-tokens 가 핫이고,
                    // §6.26 A5: 창도 /settings 에서 다시 탐지될 수 있다).
                    int effectiveMaxTokens = capMaxTokensToContext(
                            cfg.name(), requestedMaxTokens(cfg, llmCfg.maxTokens()), effectiveCtx);
                    ChatModel rawModel = OpenAiChatModel.builder()
                            .openAiApi(api)
                            // §6.18 — was hardcoded temperature(0.0)/maxTokens(6000); now the effective
                            // app.llm.temperature / app.llm.max-tokens (LLM_TEMPERATURE / LLM_MAX_TOKENS).
                            // Both values here are only the startup-time fallback for framework-internal
                            // callers that build their own ChatClient around this bean and never pass a
                            // per-call ChatOptions (e.g. RetrievalService's MultiQueryExpander) — every
                            // call site this app owns (Classifier/Answer/Reranker/Direct) attaches the
                            // live effective temperature and maxTokens per call instead, and
                            // MaxTokensCappingChatModel recomputes the per-provider ceiling per call
                            // (§6.26 A6), so /settings changes apply without a restart for those.
                            .defaultOptions(OpenAiChatOptions.builder()
                                    .model(effectiveModel)
                                    .temperature(llmCfg.temperature())
                                    .maxTokens(effectiveMaxTokens)
                                    .build())
                            .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                            .build();
                    // defaultOptions 만으로는 부족하다 — 블로킹 호출부가 매번 자기 maxTokens 를
                    // 실어 보내 그 기본값을 덮어쓴다. 이 데코레이터가 프로바이더 선택 '이후'에
                    // 상한으로 눌러 준다(MaxTokensCappingChatModel 클래스 주석 참고).
                    // 계측 데코레이터는 프롬프트와 usage 를 동시에 보는 유일한 자리다 — 라우터는
                    // 호출을 불투명한 클로저로 받고, 호출부는 서버가 센 토큰 수를 못 본다.
                    // 가장 바깥은 호출부가 표시한 사이트의 생각 수준을 이 프로바이더가 알아듣는 필드로 바꿔 싣는
                    // 자리다(§6.29 — AUTO 면 LOCAL 만 chat_template_kwargs, 원격은 모르는 필드를 400 으로 거부하고
                    // 라우터가 그걸 차단으로 받는다). 바깥에 둬야 curl 로그가 실제로 나간 본문을 찍고, 표시 키가
                    // 서버로 새지 않는다(ThinkingControlChatModel 클래스 주석 참고). 수준은 함수로 넘긴다 — 핫이다.
                    thinkingDialects.record(cfg.name(), configuredThinkingDialect(cfg), cfg.isLocal());
                    ChatModel model = new ThinkingControlChatModel(
                            new LoggingChatModel(
                                    tokenCalibration.wrap(
                                            new MaxTokensCappingChatModel(rawModel, cfg.name(),
                                                    () -> liveMaxTokens(cfg, props, contextWindows))),
                                    cfg.name(), resolvedUrl, effectiveApiKey, effectiveModel),
                            cfg.name(), thinkingDialects,
                            site -> props.llmSafe().thinkingLevel(site),
                            thinkingObservations,
                            // 생각 여유의 상한(§6.29 ④) — 안쪽 MaxTokensCappingChatModel 이 거는 것과 같은 값, 같은 창.
                            () -> liveMaxTokens(cfg, props, contextWindows),
                            () -> contextWindows.tokensOrZero(cfg.name()));
                    return new LlmProvider(
                            cfg.name(),
                            TaskType.valueOf(typeStr),
                            ProviderRole.valueOf(roleStr),
                            cfg.priority(),
                            effectiveApiKey,
                            resolvedUrl,
                            effectiveModel,
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


        // Per-provider concurrency gate: falls back to defaultProviderConcurrency
        // when a provider config omits its own `concurrency`.
        Map<String, Integer> providerConcurrency = new HashMap<>();
        for (AppProperties.ProviderConfig cfg : llmCfg.providers()) {
            int concurrency = (cfg.concurrency() != null && cfg.concurrency() > 0)
                    ? cfg.concurrency() : llmCfg.defaultProviderConcurrency();
            providerConcurrency.put(cfg.name(), concurrency);
        }

        log.info("LLM providers registered: {}", providers.stream()
                .map(p -> "%s(%s/%s/p%d/stream=%b/concurrency=%d/ctx=%s/thinking=%s) → %s [%s]".formatted(p.name(), p.role(), p.type(),
                        p.priority(), p.stream(), providerConcurrency.getOrDefault(p.name(), llmCfg.defaultProviderConcurrency()),
                        contextWindows.find(p.name())
                                .map(w -> w.tokens() + "/" + w.source().name().toLowerCase())
                                .orElse("?"),   // "?" = 선언도 탐지도 없음 → 입력 예산을 짤 근거가 없다
                        thinkingDialects.dialectOf(p.name()).value(),
                        p.baseUrl(), p.model()))
                .toList());
        log.info("LLM HTTP timeouts: connect={}s read={}s, permit-wait={}s", connectTimeoutSeconds, readTimeoutSeconds,
                llmCfg.permitWaitTimeoutSeconds());

        // 기동 때 닿지 않은 복제본은 차단된 채로 시작한다 — 첫 사용자가 그 서버의 연결 타임아웃을 무는 대신 처음부터
        // 살아 있는 쪽으로 간다. 차단이 풀리면 라우터가 평소처럼 다시 시도하고, 여전히 죽어 있으면 다시 차단한다.
        for (String name : startedUnreachable) {
            circuitBreaker.block(name, STARTUP_UNREACHABLE_BLOCK_SECONDS);
        }

        return new LlmRouter(providers, usageRepo, circuitBreaker, defaultMode, readTimeoutSeconds,
                providerConcurrency, llmCfg.defaultProviderConcurrency(), llmCfg.permitWaitTimeoutSeconds(),
                providerToggle, backgroundConcurrencyTracker,
                // 위에서 기동 시 한 번 채운 창은 낡는다(서버를 다른 -c 로 재시작, LM Studio 의 JIT
                // 로딩). 컨텍스트 초과가 그 사실을 알려 주는 유일한 신호라, 그 순간 같은 탐지를
                // 한 번 더 돌려 스스로 고치게 한다.
                new ProbingContextWindowRefresher(contextWindows, connectTimeoutSeconds, readTimeoutSeconds));
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

    /**
     * 설정된 생각 제어 방식 — 모르는 값이면 경고하고 {@code AUTO}(현행 규칙)로 동작한다. 오타가 원격에 표준 밖
     * 필드를 싣는 쪽으로 번지면 그 프로바이더가 400 → 차단되므로, 모를 때는 가장 보수적인 기존 규칙으로 떨어뜨린다.
     */
    private static ThinkingDialect configuredThinkingDialect(AppProperties.ProviderConfig cfg) {
        String raw = cfg.thinkingDialect();
        if (raw != null && !raw.isBlank() && ThinkingDialect.parse(raw).isEmpty()) {
            log.warn("[THINKING] provider=[{}] thinking-dialect={} 를 모른다 — auto 로 동작한다 "
                    + "(auto|none|template-kwargs|template-kwargs-effort|openai-effort)", cfg.name(), raw);
        }
        return cfg.thinkingDialectOrAuto();
    }

    /**
     * 이 프로바이더가 요청할 출력 상한 — 자기 값이 있으면 그것, 없으면 전역값
     * ({@code concurrency} 와 같은 폴백 규약).
     */
    private static int requestedMaxTokens(AppProperties.ProviderConfig cfg, int globalMaxTokens) {
        return cfg.requestedMaxTokens(globalMaxTokens);
    }

    /**
     * 이 프로바이더의 <b>지금</b> 유효한 출력 상한 — {@code MaxTokensCappingChatModel} 이 호출마다
     * 부른다.
     *
     * <p>세 입력이 모두 런타임에 바뀔 수 있다: 전역 {@code app.llm.max-tokens} 는 핫 편집 대상이고
     * (§6.26 A6), 컨텍스트 창은 {@code /settings} 의 재탐지로 갱신되며(§6.26 A5), 둘의 조합인
     * {@link ProviderContextWindows#cappedMaxTokens} 결과도 따라 움직인다. 그래서 값이 아니라
     * 계산을 넘긴다 — 프로바이더 자신의 {@code max-tokens} 선언만이 구조적 설정이라 재기동 대상이다.
     */
    private static int liveMaxTokens(AppProperties.ProviderConfig cfg, AppProperties props,
                                     ProviderContextWindows contextWindows) {
        int requested = requestedMaxTokens(cfg, props.llmSafe().maxTokens());
        int window = contextWindows.tokensOrZero(cfg.name());
        return ProviderContextWindows.cappedMaxTokens(requested, window > 0 ? window : null);
    }

    /**
     * 출력 예약이 컨텍스트 창을 통째로 먹어버리는 설정을 기동 시점에 잡아낸다.
     *
     * <p>OpenAI 호환 서버에서 {@code max_tokens} 는 <b>예약</b>이다 — 프롬프트가 아무리 짧아도
     * {@code 프롬프트 + max_tokens} 가 창을 넘으면 거절당한다. 그래서 {@code max-tokens} 가 창보다
     * 크거나 같으면 <b>어떤 요청도 성공할 수 없다</b>: 입력에 남는 자리가 0 이하다. 설정만 보고는
     * 아무도 눈치채지 못하고, 증상은 매 질문마다 "Context size has been exceeded" 로만 나타난다.
     *
     * <p>그래서 창을 아는 경우에 한해 <b>창의 절반</b>으로 눌러 준다({@link ProviderContextWindows#cappedMaxTokens}).
     * 절반인 이유는 그 이상을 출력에 예약하면 남는 입력 자리가 출력보다 작아지는데, 이 앱은 RAG 라
     * 입력(검색 문서 + 대화 이력)이 출력보다 큰 것이 정상이기 때문이다. 임의의 안전 마진이 아니라
     * "입력이 출력보다 작아지면 안 된다"는 경계값이다.
     *
     * <p>조용히 고치지 않고 WARN 을 남긴다 — 운영자가 적어 둔 숫자와 실제로 쓰이는 숫자가 다른
     * 상태이므로, {@code SettingsService.warnOnDivergingOverrides()} 와 같은 이유로 알려야 한다.
     * 창을 모르면({@code null}) 아무것도 하지 않는다: 모르는 값으로 남의 설정을 깎을 수는 없다.
     *
     * <p>package-private static: 순수 계산이라 빈을 띄우지 않고 검사할 수 있어야 한다.
     */
    static int capMaxTokensToContext(String providerName, int requested, Integer contextTokens) {
        int capped = ProviderContextWindows.cappedMaxTokens(requested, contextTokens);
        if (capped == requested) return requested;
        log.warn("""
                [LLM CONFIG] provider=[{}] max-tokens({}) >= context-size({}) — 입력에 남는 자리가 없어 \
                모든 요청이 컨텍스트 초과로 실패합니다. {} 로 낮춰 실행합니다.
                  -> app.llm.providers[N].max-tokens 를 창보다 충분히 작게 두거나, 서버의 컨텍스트를 키우세요.""",
                providerName, requested, contextTokens, capped);
        return capped;
    }

    /**
     * 이 앱이 지원하는 가장 작은 컨텍스트 창 — {@code app.llm.max-tokens} 하한의 두 배(16,000, "16K").
     *
     * <p>두 배인 이유는 {@link ProviderContextWindows#cappedMaxTokens} 와 같은 경계다 — 출력 예약이 창의 절반을 넘으면
     * 입력 자리가 출력보다 작아진다. 하한이 그 경계 안에 드는 가장 작은 창이 이 값이다. 그보다 작은 창에서는 그 클램프가
     * {@code max-tokens ≥ 창}일 때만 걸리므로 하한 전체가 그대로 예약된다 — 8k~10k 창이면 옵션을 싣지 않는 질의 확장이
     * 넘치고 답변의 입력 예산도 절반 아래로 준다(PITFALLS 의 2026-10-23 범위 변경 항목). 16,384 가 아니라 하한에 묶은 값인
     * 이유는 하한이 바뀌면 이 경계도 함께 움직여야 해서다 — 16K 로 띄운 서버(16,384)는 통과한다.
     */
    static final int MIN_SUPPORTED_CONTEXT_TOKENS = 2 * AppProperties.MIN_MAX_TOKENS;

    /**
     * 창을 알고 그 창이 {@link #MIN_SUPPORTED_CONTEXT_TOKENS} 보다 작으면 WARN 을 남긴다 — <b>기동은 막지 않는다</b>.
     * 막지 않는 이유: 작게 띄운 쪽이 복제본 하나이거나 실험용 모델일 수 있고, 탐지값이 틀렸으면 운영자가
     * {@code context-size} 선언으로 고칠 수 있다(선언이 탐지를 이긴다). 창을 모르면 아무것도 하지 않는다.
     *
     * <p>기동 때만 본다 — {@code /settings} 의 재탐지·컨텍스트 초과 뒤의 자동 재탐지로 바뀐 창은 여기를 지나지 않는다.
     *
     * @return 경고했는가. package-private static: 순수 판정이라 빈을 띄우지 않고 검사할 수 있어야 한다
     */
    static boolean warnIfBelowSupportedWindow(String providerName, Integer contextTokens,
                                              ProviderContextWindows.Source source) {
        if (contextTokens == null || contextTokens <= 0 || contextTokens >= MIN_SUPPORTED_CONTEXT_TOKENS) return false;
        log.warn("""
                [LLM CONFIG] provider=[{}] context-size({}, {}) < {} — 지원하는 최소 컨텍스트(16K)보다 작습니다. \
                max-tokens 하한({})이 창의 절반을 넘어 블로킹 호출의 입력 자리가 출력 예약보다 작아지고, \
                8k~10k 창에서는 옵션을 싣지 않는 질의 확장이 넘칩니다.
                  -> 서버의 컨텍스트를 16K 이상으로 띄우세요(llama.cpp -c 16384 · LM Studio 컨텍스트 길이).""",
                providerName, contextTokens, source == ProviderContextWindows.Source.CONFIGURED ? "선언" : "탐지",
                MIN_SUPPORTED_CONTEXT_TOKENS, AppProperties.MIN_MAX_TOKENS);
        return true;
    }

    /**
     * 기동 때 닿지 않은 복제본의 첫 차단 — 라우터가 폴백이 있는 실패에 거는 차단({@code LlmRouter.SHORT_BLOCK_SECONDS})과
     * 같은 30초. 이 프로바이더는 정의상 대신 받을 복제본이 있다.
     */
    private static final String STARTUP_UNREACHABLE_BLOCK_SECONDS = "30";

    /**
     * G3 를 등록될 LOCAL 프로바이더 전부에 대해 돌린다 — 해석된 모델 id 는 {@code verifiedModels} 에, 닿지 않았지만
     * 기동을 멈추지 않을 프로바이더는 {@code startedUnreachable} 에 담는다.
     *
     * <p><b>장애 모양의 실패만 너그럽게 본다, 그것도 복제본이 살아 있을 때만.</b> 같은 묶음({@link LocalReplicas})의 다른
     * 서버가 검증을 통과했으면 이 서버 하나 때문에 앱 전체를 세우지 않는다 — 두 대를 두는 이유가 한 대가 없어도 돌아가게
     * 하려는 것인데, 예전에는 하필 기동 시점에 한 대가 꺼져 있으면 아무것도 뜨지 않았다(PITFALLS {@code StreamFailover}
     * 남은 한계 ①). 장애 모양 = 연결이 안 되거나(거부·타임아웃·DNS) 응답이 없거나(읽기 타임아웃) 5xx({@link #isOutage}).
     * 복제본이 없는 서버(단일 서버 배포 · 소형 모델처럼 priority 가 다른 서버)는 예전처럼 기동을 멈춘다.
     * <b>서버가 답을 했는데 그것이 오류(4xx)·모델 목록이 아닌 응답이거나, 모델이 없거나 모호하면</b> 복제본이 있어도
     * 멈춘다 — 그건 장애가 아니라 설정 오류(경로·인증·모델명)이고 이 검증이 잡으려는 바로 그것이다. 게다가 그런 서버를
     * 차단한 채 띄우면 런타임의 채팅 스트리밍은 4xx 를 다른 서버로 넘기지 않으므로({@code LlmRouter.failOver}) 차단이 풀릴
     * 때마다 사용자 오류가 된다.
     *
     * <p>닿지 않은 서버는 모델명을 해석하지 못했으므로 <b>설정값 그대로</b> 등록한다. 서버가 그 모델을 다른 id 로 부르면
     * (LM Studio 의 {@code google/…} 처럼) 돌아온 뒤의 요청이 "없는 모델"이 될 수 있어 경고에 그 사실을 적는다 —
     * 살아 있는 복제본의 해석 결과를 빌려 쓰지 않는 이유는 두 서버의 종류가 같다는 보장이 없어서다(추측은 검증이 아니다).
     */
    private void verifyLocalModels(List<AppProperties.ProviderConfig> all, int connectTimeoutSeconds,
                                   int readTimeoutSeconds, Map<String, String> verifiedModels,
                                   Set<String> startedUnreachable) {
        List<AppProperties.ProviderConfig> enabled = all.stream()
                .filter(AppProperties.ProviderConfig::isEnabled)
                .toList();
        Map<AppProperties.ProviderConfig, LocalServerUnreachableException> unreachable = new LinkedHashMap<>();
        for (AppProperties.ProviderConfig cfg : enabled) {
            if (!cfg.isLocal()) continue;
            try {
                verifiedModels.put(cfg.name(),
                        verifyLocalModel(cfg.name(), cfg.apiBase(), cfg.model(), connectTimeoutSeconds, readTimeoutSeconds));
            } catch (LocalServerUnreachableException e) {
                if (!e.outage()) {          // 서버가 답은 했다 — 설정 오류라 복제본과 무관하게 멈춘다
                    e.logStartupFailure();
                    throw e;
                }
                unreachable.put(cfg, e);
            }
        }
        for (var entry : unreachable.entrySet()) {
            AppProperties.ProviderConfig cfg = entry.getKey();
            List<String> livePeers = LocalReplicas.peersOf(cfg, enabled).stream()
                    .map(AppProperties.ProviderConfig::name)
                    .filter(verifiedModels::containsKey)
                    .toList();
            if (livePeers.isEmpty()) {
                entry.getValue().logStartupFailure();
                throw entry.getValue();
            }
            startedUnreachable.add(cfg.name());
            log.warn("""
                    [LLM STARTUP CHECK] provider=[{}] is unreachable at {}/v1/models, but its replica(s) {} answered — \
                    starting anyway with [{}] blocked; requests go to the replica until it comes back.
                      -> Its model name was not verified and is sent as configured ('{}'). If that server reports the model \
                    under a different id, set {} to the full id.
                      -> Its context window is unknown until a context overflow re-probes it or /settings 「창 재탐지」 is pressed.""",
                    cfg.name(), cfg.apiBase(), livePeers, cfg.name(), cfg.model(), modelEnvVarHint(cfg.name()));
        }
    }

    /**
     * 장애 모양의 실패인가 — 서버가 없거나(연결 계열, {@link LlmRouter#isConnectionFailure}) 응답하지 못했다(읽기 타임아웃·5xx).
     * 복제본이 살아 있으면 기다릴 만한 것들이다. 4xx·모델 목록이 아닌 응답(JSON 이 아닌 본문 등)은 서버가 <b>답을 한</b>
     * 것이라 설정 오류다. 이 클라이언트({@link HttpClientTimeouts}, {@code HttpURLConnection})에서 연결·읽기 타임아웃은
     * 둘 다 {@code SocketTimeoutException} 이다.
     */
    static boolean isOutage(Throwable e) {
        if (LlmRouter.isConnectionFailure(e)) return true;
        for (Throwable cur = e; cur != null; cur = cur.getCause()) {
            if (cur instanceof java.net.SocketTimeoutException) return true;
            if (cur instanceof RestClientResponseException r) return r.getStatusCode().is5xxServerError();
        }
        return false;
    }

    /**
     * G3 가 {@code /v1/models} 에서 모델 목록을 받지 못했다. 설정 오류(모델 없음·모호함 — 이건 그냥 {@code IllegalStateException})
     * 와 갈라야 복제본이 있을 때 기동을 이어 갈 수 있고, 그중에서도 {@link #outage()} 인 것만 이어 간다.
     */
    private static final class LocalServerUnreachableException extends IllegalStateException {
        private final String providerName;
        private final String apiBase;
        private final boolean outage;

        LocalServerUnreachableException(String providerName, String apiBase, Exception cause) {
            super(message(providerName, apiBase, cause), cause);
            this.providerName = providerName;
            this.apiBase = apiBase;
            this.outage = isOutage(cause);
        }

        private static String message(String providerName, String apiBase, Exception cause) {
            return (isOutage(cause)
                    ? "Local LLM provider [%s] is unreachable at %s/v1/models — is the server running and is the URL correct? (%s: %s)"
                    : "Local LLM provider [%s] answered %s/v1/models without a model list — is the URL the OpenAI-compatible base (…/v1) and the api-key right? (%s: %s)")
                    .formatted(providerName, apiBase, cause.getClass().getSimpleName(), cause.getMessage());
        }

        /** 서버가 없거나 응답하지 못했다({@link #isOutage}) — {@code false} 면 서버가 답을 했고 그 답이 문제다. */
        boolean outage() {
            return outage;
        }

        /** 기동을 멈추기 직전에만 — 무엇을 고치면 되는지까지. */
        void logStartupFailure() {
            Throwable cause = getCause();
            if (!outage) {
                log.error("""
                        [LLM STARTUP CHECK FAILED] provider=[{}] got no model list from {}/v1/models — {}: {}
                          -> The server answered, but not with an OpenAI-compatible model list: check that {} is the \
                        OpenAI-compatible base (e.g. http://host:1234/v1) and that its api-key is right.""",
                        providerName, apiBase, cause.getClass().getSimpleName(), cause.getMessage(), baseUrlEnvVarHint(providerName));
                return;
            }
            log.error("""
                    [LLM STARTUP CHECK FAILED] provider=[{}] could not reach {}/v1/models — {}: {}
                      -> Is the server (e.g. LM Studio / llama-server) actually running at that address?
                      -> If the address/port is wrong, fix {} and restart.
                      -> If the server just starts later than this app, set LLM_VERIFY_LOCAL_MODELS_ON_STARTUP=false to skip this check.""",
                    providerName, apiBase, cause.getClass().getSimpleName(), cause.getMessage(), baseUrlEnvVarHint(providerName));
        }
    }

    /**
     * G3: calls the OpenAI-compatible {@code GET {apiBase}/v1/models} endpoint and throws if the
     * server is unreachable or the configured model isn't in the returned list. Called at bean
     * creation time — any exception here fails {@code llmRouter()} construction, which fails Spring
     * Boot startup (see caller for rationale) — except an unreachable server whose replica answered
     * ({@link #verifyLocalModels}). Reuses the same connect/read timeouts as the chat
     * calls; a slow-to-boot local server should raise {@code LLM_CONNECT_TIMEOUT_SECONDS} rather than
     * disable this check.
     *
     * @return the model id to build the provider with — the configured name when it matches exactly,
     *         otherwise the one server id that contains it ({@link ModelNameResolver}). A name that
     *         is part of several ids is a startup failure like a missing one: picking one silently
     *         would defeat the check's purpose.
     */
    private String verifyLocalModel(String providerName, String apiBase, String model,
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
            // 여기서는 로그를 남기지 않는다 — 복제본이 살아 있으면 기동을 멈추지 않으므로 ERROR 인지 WARN 인지는 호출자가 안다.
            throw new LocalServerUnreachableException(providerName, apiBase, e);
        }
        ModelNameResolver.Resolution match = ModelNameResolver.resolve(model, availableModels);
        if (match.ambiguous()) {
            log.error("""
                    [LLM STARTUP CHECK FAILED] provider=[{}] configured model '{}' is part of more than one model at {}/v1/models
                      -> Models it matches: {}
                      -> Fix {} to the full id of the intended model (an exact id always wins over a partial one).""",
                    providerName, model, apiBase, match.candidates(), modelEnvVarHint(providerName));
            throw new IllegalStateException(
                    "Local LLM provider [%s]: configured model '%s' is ambiguous at %s/v1/models — it is part of %s. Use the full model id."
                            .formatted(providerName, model, apiBase, match.candidates()));
        }
        if (!match.found()) {
            log.error("""
                    [LLM STARTUP CHECK FAILED] provider=[{}] configured model '{}' was not found at {}/v1/models
                      -> Models actually available there: {}
                      -> Fix {} to one of the models above (or a part of one that no other model shares), or load the intended model on that server.""",
                    providerName, model, apiBase, availableModels, modelEnvVarHint(providerName));
            throw new IllegalStateException(
                    "Local LLM provider [%s]: configured model '%s' was not found at %s/v1/models. Available models: %s"
                            .formatted(providerName, model, apiBase, availableModels));
        }
        if (match.renamed()) {
            // Not a WARN: a partial name is a supported shorthand, not a misconfiguration. But the
            // operator must be able to see from the log which id the requests actually carry.
            log.info("Local LLM provider [{}] verified — configured model '{}' is part of server model '{}' at {}/v1/models; using the server's id",
                    providerName, model, match.resolved(), apiBase);
        } else {
            log.info("Local LLM provider [{}] verified — model '{}' confirmed available at {}/v1/models",
                    providerName, model, apiBase);
        }
        return match.resolved();
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
