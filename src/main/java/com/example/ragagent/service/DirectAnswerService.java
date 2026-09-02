package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmCurlLogger;
import com.example.ragagent.llm.LlmProvider;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.TokenEstimator;
import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.model.ResponseMode;
import com.example.ragagent.security.PromptInjectionGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.MessageSource;

import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Handles meta questions (greetings, service inquiries) without RAG retrieval.
 * Also handles directMode queries — general-purpose LLM calls bypassing RAG.
 * Equivalent to direct_answer_node in agents.py.
 */
@Service
public class DirectAnswerService {

    private static final Logger log = LoggerFactory.getLogger(DirectAnswerService.class);

    private final LlmRouter llmRouter;
    private final MessageSource messageSource;
    private final AppProperties props;
    private final ProviderContextWindows contextWindows;

    @org.springframework.beans.factory.annotation.Autowired
    public DirectAnswerService(LlmRouter llmRouter, MessageSource messageSource, AppProperties props,
                               ProviderContextWindows contextWindows) {
        this.llmRouter = llmRouter;
        this.messageSource = messageSource;
        this.props = props;
        this.contextWindows = contextWindows;
    }

    /** 창을 모르는 것과 같게 동작하는 축약 — 이력 절단이 no-op 이 된다. */
    public DirectAnswerService(LlmRouter llmRouter, MessageSource messageSource, AppProperties props) {
        this(llmRouter, messageSource, props, null);
    }

    public AgentState execute(AgentState state) {
        state = withFittedHistory(state);
        String systemPrompt = resolveSystemPrompt(state);
        log.debug("[DirectAnswer] directMode={} routingMode={} historyLen={}", state.directMode(),
                state.routingMode(), state.conversationHistory().length());
        String userPrompt = buildUserPrompt(state);

        // §6.18 — Direct(meta) answers use their own temperature (hot-editable via /settings), read
        // fresh per call, distinct from the general/RAG temperature baked into the provider default.
        double directTemp = props.llmSafe().directTemperature();
        int maxTokens = state.responseMode().maxTokens(props.llmSafe().maxTokens());
        LlmRouter.LlmResult result = llmRouter.executeGatedWithUsage(TaskType.TEXT, state.routingMode(),
                model -> model.call(buildPrompt(systemPrompt, userPrompt, directTemp, maxTokens)));
        String rawAnswer = result.text();
        String normalized = rawAnswer == null ? null : enforceSummaryOnly(rawAnswer, state.responseMode());
        String answer = (normalized == null || normalized.isEmpty()) ? null : normalized;
        log.debug("[DirectAnswer] answer length={}", answer == null ? -1 : answer.length());
        return state.toBuilder().answer(answer)
                .accumulateTokens(result.inputTokens(), result.outputTokens()).build();
    }

    /** Streaming variant — pushes tokens via listener.onToken() instead of blocking. */
    public AgentState executeStreaming(AgentState state, GraphListener listener) {
        state = withFittedHistory(state);
        String systemPrompt = resolveSystemPrompt(state);
        log.debug("[DirectAnswer] streaming directMode={} routingMode={} historyLen={}", state.directMode(),
                state.routingMode(), state.conversationHistory().length());

        double directTemp = props.llmSafe().directTemperature();
        LlmProvider provider = llmRouter.routeProvider(TaskType.TEXT, state.routingMode());

        StringBuilder full = new StringBuilder();
        try (var permit = llmRouter.acquirePermit(provider)) {
            callOrStream(provider, state, systemPrompt, directTemp,
                    t -> { listener.onToken(t); full.append(t); });
        }

        String answer = full.toString();
        answer = enforceSummaryOnly(answer, state.responseMode());
        log.debug("[DirectAnswer] streaming answer length={}", answer.length());
        // Streaming mode has no ChatResponse to read real usage from — record an approximate
        // (chars/4) usage entry so /llm-usage isn't blind to the entire direct-answer stream path,
        // and reflect the same estimate in the per-turn total so the chat UI isn't stuck at 0/0.
        String promptText = systemPrompt + buildUserPrompt(state);
        llmRouter.recordApproxUsage(provider.name(), promptText, answer);
        int approxIn = (int) LlmRouter.approxTokens(promptText);
        int approxOut = (int) LlmRouter.approxTokens(answer);
        return state.toBuilder().answer(answer).accumulateTokens(approxIn, approxOut).build();
    }

    /**
     * meta(인사/잡담)는 모드와 무관하게 자체 프롬프트를 쓰고, directMode 답변은 <b>모드별 전용</b>
     * 시스템 프롬프트를 고른다 (PLAN §6.24 Step 1-b) — RAG 경로와 같은 구조다.
     */
    private String resolveSystemPrompt(AgentState state) {
        String key = state.directMode()
                ? state.responseMode().directSystemPromptKey()
                : "prompt.direct.meta.system";
        return messageSource.getMessage(key, null, state.locale());
    }

    private static Prompt buildPrompt(String systemPrompt, String userPrompt,
                                      double temperature, int maxTokens) {
        // Attach temperature + the response mode's token budget as runtime options —
        // OpenAiChatModel merges them over the provider's defaultOptions field-by-field, so only
        // these two are overridden (model etc. stay). maxTokens<=0 leaves the provider default.
        OpenAiChatOptions.Builder opts = OpenAiChatOptions.builder().temperature(temperature);
        if (maxTokens > 0) opts.maxTokens(maxTokens);
        return new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt)),
                opts.build());
    }


    /**
     * 사용자 프롬프트는 이제 대화 이력과 질문만 나른다 — 답변의 성격은 전적으로
     * {@link #resolveSystemPrompt} 가 고른 모드별 시스템 프롬프트가 정한다(§6.24 Step 0-c에서
     * 스타일 지시문 층을 걷어냈다).
     */
    private String buildUserPrompt(AgentState state) {
        String history = state.conversationHistory();
        String question = PromptInjectionGuard.wrap(state.question());
        if (!state.directMode()) {
            return history.isBlank()
                    ? question
                    : "[이전 대화]\n%s\n\n[현재 질문]\n%s".formatted(history, question);
        }
        StringBuilder sb = new StringBuilder();
        if (!history.isBlank()) {
            sb.append("[이전 대화]\n").append(history).append("\n\n");
        }
        sb.append("[현재 질문]\n").append(question);
        return sb.toString();
    }

    /**
     * §10.13 — 이 경로의 <b>안전망</b>. 예전에는 {@code fitToBudget()} 을 한 번도 부르지 않았고,
     * 이력이 모드와 무관하게 5,000자로 고정이라 문제가 드러나지 않았을 뿐이다. 그 상한이 창에서
     * 파생되기 시작하면(= 넓어지면) 프롬프트가 창을 넘길 수 있으므로, 실제로 보내기 직전에 한 번 더 잰다.
     *
     * <p><b>이력만 자른다.</b> 질문과 시스템 프롬프트는 자를 수 없는 것이고, Direct 에는 버릴 검색
     * 문서가 없다 — 이 프롬프트에서 줄일 수 있는 것은 이력뿐이다.
     *
     * <p><b>창을 모르면 아무것도 하지 않는다</b> — 추측한 숫자로 대화 맥락을 버리지 않는다
     * ({@code ProviderContextWindows} 가 "모름"을 값으로 표현하는 이유).
     *
     * <p>프로바이더는 {@code findProviderName()} 으로 <b>먼저 묻는다</b> — 실제 호출 사이에 답이
     * 달라질 수 있지만 {@code AnswerService.buildAnswerPrompt()} 가 같은 근사를 쓰고 같은 이유로
     * 받아들인다(대체되는 것은 대개 창이 더 큰 다른 역할이라 "덜 잘랐어야 했는데 더 잘랐다" 쪽이다).
     */
    private AgentState withFittedHistory(AgentState state) {
        String history = state.conversationHistory();
        if (contextWindows == null || history == null || history.isBlank()) return state;
        int window = contextWindows.tokensOrZero(
                llmRouter.findProviderName(TaskType.TEXT, state.routingMode()));
        if (window <= 0) return state;
        int budget = HistoryPolicy.budgetChars(window,
                AnswerService.outputReservation(state.responseMode(), true, props.llmSafe().maxTokens()),
                0, TokenEstimator.estimate(state.question()), Integer.MAX_VALUE);
        String fitted = HistoryPolicy.trimToBudget(history, budget);
        if (fitted.length() >= history.length()) return state;

        log.warn("[DirectAnswer] 컨텍스트 창 {}토큰에 맞춰 이력 축소 {}→{}자",
                window, history.length(), fitted.length());
        // 줄였으면 말한다 — 이 앱이 미사용 출처·envNote·RAG 축소에서 반복해 온 규칙이다.
        // 문구는 RAG 경로의 축소 안내와 같은 자리(budgetNote)에 같은 말로 실린다: 세 렌더러가
        // 이미 그 필드를 그리고 있으므로 새 표시 경로를 만들지 않는다.
        return state.toBuilder()
                .conversationHistory(fitted)
                .budgetNote("컨텍스트 한도로 이전 대화 일부를 제외했습니다.")
                .build();
    }

    /**
     * Unified streaming handler for both provider.stream()=true/false.
     * When stream=true, calls OpenAiApi.chatCompletionStream() directly to bypass
     * OpenAiChatModel.internalStream()'s buffer(int,int) which holds all tokens until LLM finishes.
     */
    private void callOrStream(LlmProvider provider, AgentState state,
                              String systemPrompt, double temperature,
                              java.util.function.Consumer<String> tokenSink) {
        if (provider.stream()) {
            // Bypass OpenAiChatModel.internalStream() which buffers ALL chunks via buffer(int,int)
            // before emitting, defeating real-time token delivery to the browser.
            String userPrompt = buildUserPrompt(state);
            List<OpenAiApi.ChatCompletionMessage> messages = List.of(
                    new OpenAiApi.ChatCompletionMessage(systemPrompt, OpenAiApi.ChatCompletionMessage.Role.SYSTEM),
                    new OpenAiApi.ChatCompletionMessage(userPrompt, OpenAiApi.ChatCompletionMessage.Role.USER)
            );
            OpenAiApi.ChatCompletionRequest request =
                    new OpenAiApi.ChatCompletionRequest(messages, provider.model(), temperature, true);
            logDirectRequest(provider, request);
            // 중지/끊김 시 LLM 쪽 연결까지 실제로 끊으려면 구독을 취소해야 한다 — toIterable() 을
            // 그냥 벗어나는 것으로는 취소되지 않는다(CancellableTokenStream 참조).
            CancellableTokenStream.consume(
                    provider.openAiApi().chatCompletionStream(request)
                            .mapNotNull(chunk -> {
                                if (chunk.choices() == null || chunk.choices().isEmpty()) return null;
                                return chunk.choices().get(0).delta().content();
                            })
                            .filter(t -> !t.isEmpty())
                            .doOnCancel(() -> log.warn("[DirectAnswer] Stream cancelled provider={} thread={} route={}",
                                    provider.name(), state.threadId(), state.routingMode()))
                            .doOnError(e -> log.error("[DirectAnswer] Stream error provider={}", provider.name(), e))
                            .doFinally(signal -> log.debug("[DirectAnswer] Stream finished signal={} provider={} thread={}",
                                    signal, provider.name(), state.threadId())),
                    tokenSink);
        } else {
            // Provider does not support streaming: buffer and deliver as single chunk
            StringBuilder buf = new StringBuilder();
            ChatClient.builder(provider.chatModel()).build()
                    .prompt()
                    .options(OpenAiChatOptions.builder().temperature(temperature).build())
                    .system(systemPrompt)
                    .user(buildUserPrompt(state))
                    .stream()
                    .content()
                    .doOnCancel(() -> log.warn("[DirectAnswer] Buffered stream cancelled provider={} thread={} route={}",
                            provider.name(), state.threadId(), state.routingMode()))
                    .doOnError(e -> log.error("[DirectAnswer] Stream error", e))
                    .doFinally(signal -> log.debug("[DirectAnswer] Buffered stream finished signal={} provider={} thread={}",
                            signal, provider.name(), state.threadId()))
                    .doOnNext(buf::append)
                    .blockLast();
            if (!buf.isEmpty()) tokenSink.accept(buf.toString());
        }
    }

    /**
     * The provider.stream()=true branch above calls {@link OpenAiApi} directly, bypassing
     * {@code ChatModel} (and therefore {@code LoggingChatModel}) entirely to avoid
     * {@code OpenAiChatModel.internalStream()}'s buffering — so it never showed up in logs at any
     * level. Mirrors LoggingChatModel's TRACE(full curl)/DEBUG(endpoint+body) split via the shared
     * {@link LlmCurlLogger}.
     */
    private void logDirectRequest(LlmProvider provider, OpenAiApi.ChatCompletionRequest request) {
        if (!log.isDebugEnabled()) return;
        try {
            String endpoint = provider.baseUrl().replaceAll("/+$", "") + "/chat/completions";
            String json = LlmCurlLogger.toCurlBodyJson(request);
            LlmCurlLogger.log(log, "LLM", provider.name(), endpoint, provider.apiKey(), json);
        } catch (Exception e) {
            log.debug("[LLM curl] serialization error: {}", e.getMessage());
        }
    }

    /** 요약 전용 모드의 안전망 — RAG 경로와 같은 {@link SummaryOnlyGuard}를 쓴다. */
    private static String enforceSummaryOnly(String answer, ResponseMode mode) {
        return SummaryOnlyGuard.apply(answer, mode);
    }
}
