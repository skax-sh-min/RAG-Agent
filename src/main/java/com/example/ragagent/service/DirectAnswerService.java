package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmProvider;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.TokenEstimator;
import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.llm.ThinkingBudget;
import com.example.ragagent.llm.ThinkingControl;
import com.example.ragagent.llm.ThinkingSite;
import com.example.ragagent.model.ResponseMode;
import com.example.ragagent.security.PromptInjectionGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.MessageSource;

import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Handles meta questions (greetings, service inquiries) without RAG retrieval.
 * Also handles directMode queries — general-purpose LLM calls bypassing RAG.
 */
@Service
public class DirectAnswerService {

    private static final Logger log = LoggerFactory.getLogger(DirectAnswerService.class);

    private final LlmRouter llmRouter;
    private final MessageSource messageSource;
    private final AppProperties props;
    private final ProviderContextWindows contextWindows;
    /** 채팅 답변 스트리밍({@code stream=true})의 단일 경로 — {@code AnswerService} 와 공유한다(§6.29 3단계). */
    private final AnswerStreamer answerStreamer;
    /** 이력 안전망의 출력 예약에 생각 여유를 더한다(§6.29 ④). */
    private final ThinkingBudget thinkingBudget;

    @org.springframework.beans.factory.annotation.Autowired
    public DirectAnswerService(LlmRouter llmRouter, MessageSource messageSource, AppProperties props,
                               ProviderContextWindows contextWindows, AnswerStreamer answerStreamer,
                               ThinkingBudget thinkingBudget) {
        this.llmRouter = llmRouter;
        this.messageSource = messageSource;
        this.props = props;
        this.contextWindows = contextWindows;
        this.answerStreamer = answerStreamer;
        this.thinkingBudget = thinkingBudget;
    }

    /**
     * 창을 모르는 것과 같게 동작하는 축약 — 이력 절단이 no-op 이 되고, 스트리밍은 생각 제어 없이 나간다(아무 필드도
     * 싣지 않는다). 테스트용.
     */
    public DirectAnswerService(LlmRouter llmRouter, MessageSource messageSource, AppProperties props) {
        this(llmRouter, messageSource, props, null, AnswerStreamer.withoutThinkingControl(), ThinkingBudget.none());
    }

    public AgentState execute(AgentState state) {
        // 블로킹 — 모드의 maxTokens 를 실어 보내므로 그만큼이 실제로 예약된다.
        state = withFittedHistory(state, false);
        String systemPrompt = resolveSystemPrompt(state);
        log.debug("[DirectAnswer] directMode={} routingMode={} historyLen={}", state.directMode(),
                state.routingMode(), state.conversationHistory().length());
        String userPrompt = buildUserPrompt(state);

        // §6.18 — Direct(meta) answers use their own temperature (hot-editable via /settings), read
        // fresh per call, distinct from the general/RAG temperature baked into the provider default.
        double directTemp = props.llmSafe().directTemperature();
        int maxTokens = state.responseMode().maxTokens(props.llmSafe().maxTokens());
        ThinkingSite site = site(state);
        LlmRouter.LlmResult result = llmRouter.executeGatedWithUsage(site.taskType(), site.routingMode(state.routingMode()),
                model -> model.call(buildPrompt(systemPrompt, userPrompt, directTemp, maxTokens, site)));
        String rawAnswer = result.text();
        String normalized = rawAnswer == null ? null : enforceSummaryOnly(rawAnswer, state.responseMode());
        String answer = (normalized == null || normalized.isEmpty()) ? null : normalized;
        log.debug("[DirectAnswer] answer length={}", answer == null ? -1 : answer.length());
        return state.toBuilder().answer(answer)
                .accumulateTokens(result.inputTokens(), result.outputTokens()).build();
    }

    /** Streaming variant — pushes tokens via listener.onToken() instead of blocking. */
    public AgentState executeStreaming(AgentState state, GraphListener listener) {
        // 스트리밍 — 두 갈래(직행·stream=false 의 ChatClient) 모두 maxTokens 를 싣지 않는다.
        state = withFittedHistory(state, true);
        String systemPrompt = resolveSystemPrompt(state);
        log.debug("[DirectAnswer] streaming directMode={} routingMode={} historyLen={}", state.directMode(),
                state.routingMode(), state.conversationHistory().length());

        double directTemp = props.llmSafe().directTemperature();
        LlmProvider provider = llmRouter.routeProvider(site(state).taskType(), site(state).routingMode(state.routingMode()));

        StringBuilder full = new StringBuilder();
        try (var permit = llmRouter.acquirePermit(provider)) {
            callOrStream(provider, state, systemPrompt, directTemp,
                    t -> { listener.onToken(t); full.append(t); }, listener::onThinking);
        }

        String answer = full.toString();
        answer = enforceSummaryOnly(answer, state.responseMode());
        log.debug("[DirectAnswer] streaming answer length={}", answer.length());
        // Streaming mode has no ChatResponse to read real usage from — record an approximate
        // (TokenEstimator) usage entry so /llm-usage isn't blind to the entire direct-answer stream path,
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

    /**
     * 이 턴의 호출 지점(§6.29) — Direct 답변은 응답 모드의 Direct 사이트, 분류가 meta(인사/잡담)로 판정해 여기로 온
     * 답변은 {@code answer-meta}. 프롬프트를 고르는 {@link #resolveSystemPrompt} 와 같은 갈림이다. Direct 를 쓸 수
     * 없는 모드(C)는 요청 단계에서 N 으로 정규화되지만({@code ChatRequest}), 여기까지 왔다면 N 의 사이트를 쓴다.
     */
    private static ThinkingSite site(AgentState state) {
        if (!state.directMode()) return ThinkingSite.ANSWER_META;
        ThinkingSite site = state.responseMode().directThinkingSite();
        return site != null ? site : ThinkingSite.ANSWER_DIRECT_N;
    }

    private static Prompt buildPrompt(String systemPrompt, String userPrompt,
                                      double temperature, int maxTokens, ThinkingSite site) {
        // Attach temperature + the response mode's token budget as runtime options —
        // OpenAiChatModel merges them over the provider's defaultOptions field-by-field, so only
        // these two are overridden (model etc. stay). maxTokens<=0 leaves the provider default.
        OpenAiChatOptions.Builder opts = ThinkingControl.mark(OpenAiChatOptions.builder().temperature(temperature), site);
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
     *
     * <p>출력 예약은 {@code AnswerService.answerReservation} — 모드의 기본 예약 + 생각 여유(§6.29 ④). 예전에는 블로킹
     * 경로({@link #execute})에서도 스트리밍 예약(N 5,000)을 빼서, 실제로 실어 보내는 7,000 과 2,000 토큰이 어긋났다.
     *
     * @param streaming 이 호출이 {@code maxTokens} 를 싣지 않는가 — 출력 예약이 달라진다
     */
    private AgentState withFittedHistory(AgentState state, boolean streaming) {
        String history = state.conversationHistory();
        if (contextWindows == null || history == null || history.isBlank()) return state;
        ThinkingSite site = site(state);
        String provider = llmRouter.findProviderName(site.taskType(), site.routingMode(state.routingMode()));
        int window = contextWindows.tokensOrZero(provider);
        if (window <= 0) return state;
        int budget = HistoryPolicy.budgetChars(window,
                AnswerService.answerReservation(thinkingBudget, site, provider, state.responseMode(), streaming,
                        props.llmSafe().maxTokens()).tokens(),
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
     * When stream=true, streams through {@link AnswerStreamer} — {@code OpenAiApi.chatCompletionStream()}
     * directly, to bypass OpenAiChatModel.internalStream()'s buffer(int,int) which holds all tokens
     * until LLM finishes. That also bypasses the ChatModel decorator chain, so the streamer applies the
     * site's thinking level itself (§6.29 3단계).
     *
     * @param onThinking 생각 델타마다 — {@code stream=true} 갈래만 부른다
     */
    private void callOrStream(LlmProvider provider, AgentState state,
                              String systemPrompt, double temperature,
                              java.util.function.Consumer<String> tokenSink, Runnable onThinking) {
        if (provider.stream()) {
            // Bypass OpenAiChatModel.internalStream() which buffers ALL chunks via buffer(int,int)
            // before emitting, defeating real-time token delivery to the browser.
            answerStreamer.stream(provider, site(state), systemPrompt, buildUserPrompt(state), temperature,
                    tokenSink, onThinking,
                    new AnswerStreamer.Trace(log, "[DirectAnswer]", state.threadId(), state.routingMode()));
        } else {
            // Provider does not support streaming: buffer and deliver as single chunk
            StringBuilder buf = new StringBuilder();
            ChatClient.builder(provider.chatModel()).build()
                    .prompt()
                    // stream=false 프로바이더 — 체인(ThinkingControlChatModel)을 지나므로 사이트 표시가 그대로 먹는다.
                    .options(ThinkingControl.mark(OpenAiChatOptions.builder().temperature(temperature), site(state)).build())
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

    /** 요약 전용 모드의 안전망 — RAG 경로와 같은 {@link SummaryOnlyGuard}를 쓴다. */
    private static String enforceSummaryOnly(String answer, ResponseMode mode) {
        return SummaryOnlyGuard.apply(answer, mode);
    }
}
