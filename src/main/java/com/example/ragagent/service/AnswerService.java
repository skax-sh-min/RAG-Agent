package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.ingestion.MarkdownNoiseNormalizer;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.model.ResponseMode;
import com.example.ragagent.llm.LlmCurlLogger;
import com.example.ragagent.llm.LlmProvider;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.PromptBudget;
import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.llm.TokenEstimator;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.security.PromptInjectionGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Generates a structured answer from retrieved documents (Call 1),
 * then evaluates evidence sufficiency via a separate LLM call (Call 2).
 *
 * execute()          — existing blocking path (used by non-streaming flow)
 * executeStreaming()  — streams Call 1 tokens via GraphListener, Call 2 stays blocking
 */
@Service
public class AnswerService {

    private static final Logger log = LoggerFactory.getLogger(AnswerService.class);

    private static final int MAX_ANSWER_LEN = 20_000;

    /** Cap for the evaluator's explanation — see {@link #normalizeOneLine}. */
    private static final int MAX_EVAL_REASON_LEN = 200;

    /** Cap for the environment-dependent-value note — shown to the user, so a little more room. */
    private static final int MAX_ENV_NOTE_LEN = 300;

    /**
     * Ceiling on the evidence block sent to the evaluator — see {@link #buildEvalExcerpts}.
     *
     * <p><b>This is a safety valve for a pathological configuration, not a working limit.</b> The
     * real bound on the evidence block is {@code topK × chunk-size} (default 10 × 1500 = 15,000),
     * which sits well under it; a normal turn never meets this cap, and a turn that does has
     * already lost documents from the verification window.
     *
     * <p>Sizing follows the <b>evaluation call</b>, not the answer call, because the eval call is
     * the largest single request this app makes — it carries the question, the full answer, the
     * same excerpts, and the response schema at once. Budgeting Korean at ~1 token/char, the
     * non-excerpt part is roughly 4,900 tokens (eval system prompt ~1,570 + schema ~250 + question
     * + a ~3,000-char answer), and the reply reserves {@link #MAX_EVAL_OUTPUT_TOKENS}. At 32,000
     * the whole call lands near ~39,000 tokens, which needs a 64k-class context window; on a
     * 32k model the effective ceiling is closer to 20,000 chars and must come from {@code topK} /
     * {@code chunk-size} rather than from this constant. See OPERATOR_MANUAL §8.
     */
    private static final int MAX_EVAL_EXCERPT_CHARS = 32_000;

    /**
     * Output cap for the evaluation call only.
     *
     * <p>Without it the call inherits the provider's baked-in default ({@code app.llm.max-tokens})
     * and reserves the operator's entire completion budget for a reply that is a handful of JSON
     * fields — on a narrow context window that reservation, not the evidence, is what pushes the
     * request over {@code n_ctx}. Strict servers reject it outright; llama-server clamps generation
     * and can return nothing, which surfaces as {@code [EVAL] 검증기가 빈 응답} with
     * {@code outputTokens=0}.
     *
     * <p>Deliberately generous for what the schema needs (~400 tokens: two short Korean sentences
     * plus a small array): a model that emits a brief reasoning preamble into content must still
     * reach the JSON, since a truncated reply parses as a failure and degrades to
     * {@link #withoutVerdict} — trading a verdict for tokens is the wrong side of that bargain.
     * Clamped by the configured ceiling so it can never exceed what the operator allows.
     */
    private static final int MAX_EVAL_OUTPUT_TOKENS = 2_048;

    /**
     * 답변 프롬프트에서 문서·이력·질문 말고 <b>고정으로 들어가는 것</b>의 대략적 토큰 수 —
     * 시스템 프롬프트와 섹션 머리말({@code [이전 대화]}·{@code [검색된 문서]}·{@code [질문]})이다.
     *
     * <p>정확히 세지 않는 이유는 시스템 프롬프트가 응답 모드·로케일마다 다른 메시지 번들에서
     * 오고, 그걸 예산 계산 때문에 매번 불러오면 계산이 프롬프트 조립보다 무거워지기 때문이다.
     * 한/영 번들의 모드별 시스템 프롬프트가 1,200~1,800자 범위이므로 넉넉한 쪽으로 잡는다 —
     * 이 값이 실제보다 크면 조금 덜 담고, 작으면 초과한다. 어느 쪽이 안전한지는 분명하다.
     */
    private static final int ANSWER_PROMPT_OVERHEAD_TOKENS = 2_000;

    /**
     * single evaluation call returns both gates, plus one sentence explaining a failure.
     * {@code reason} is advisory only — it never affects routing or retry decisions, so a model
     * that returns it empty (or omits it) degrades to today's behavior rather than breaking.
     *
     * <p>{@code envNote} carries the other half of that contract: paths, hosts/ports, URLs and
     * environment-variable values legitimately differ between the machine a document was written
     * on and the one the reader is using, so the eval prompt forbids failing {@code grounded} on
     * those alone and asks for this note instead. Unlike {@code reason} it is kept on a
     * <em>passing</em> turn too — it is an advisory for the reader ("substitute your own path"),
     * not a verdict about the answer.
     */
    private record EvalOutput(boolean sufficient, boolean grounded, String reason, String envNote,
                              List<Integer> usedDocs) {}

    /**
     * C(응용) 전용 검증 결과 (§6.24 Step 2-d) — {@link EvalOutput}과 <b>필드가 다르다</b>.
     *
     * <p>기존 {@code grounded}("답변의 핵심 주장이 발췌에 근거하는가")는 창의 답변에서 정의상 항상
     * false다. 그 판정을 그대로 태우면 CRITIC이 재시도를 걸어 ANSWER·EVAL·RETRIEVAL을 각각 3회
     * (기본 {@code max-retry-count}=2) 태우고 끝에 미검증 경고까지 붙인다 — 표준 턴 164초 기준
     * 8분짜리 턴이다. 그렇다고 S처럼 검증을 통째로 끄면 C 고유의 위험(문서에 없는 API 발명)이
     * 무방비가 된다. 그래서 <b>끄지 않고 기준만 바꿔 끼운다</b>: "문서를 조합해 새로 만들었다"는
     * 통과, "문서에 없는 함수를 발명했다"는 실패.
     *
     * <p>{@code apiGrounded}는 그대로 {@code AgentState.grounded}에 실린다 — CRITIC은 그 불린
     * 하나만 소비하므로 <b>{@code CriticService} 코드 변경이 0</b>이다.
     *
     * <p>{@code inventedSymbols}는 재시도를 걸지 <b>않는다</b>(UI 경고 전용). 별도 왕복이 아니라
     * 같은 호출이 이미 답변과 전 발췌를 나란히 들고 있어 따라오는 값이다.
     *
     * <p>{@code usedDocs}는 일부러 없다 — C 답변은 발췌를 인용하는 게 아니라 재료로 삼아 새로
     * 쓰는 것이라 "몇 번 조각을 근거로 썼는가"가 성립하지 않는다. 빈 리스트로 두면
     * {@code AnswerAttribution}이 신호 없이 순수 어휘 매칭으로 degrade한다(설계된 폴백).
     */
    private record CreativeEvalOutput(boolean sufficient, boolean apiGrounded,
                                      List<String> inventedSymbols, String envNote) {}

    private final LlmRouter llmRouter;
    private final MessageSource messageSource;
    private final AppProperties props;
    private final ProviderContextWindows contextWindows;
    private final int maxRetryCount;
    private final BeanOutputConverter<EvalOutput> evalConverter =
            new BeanOutputConverter<>(EvalOutput.class);
    private final BeanOutputConverter<CreativeEvalOutput> creativeEvalConverter =
            new BeanOutputConverter<>(CreativeEvalOutput.class);

    public AnswerService(LlmRouter llmRouter, AppProperties appProperties, MessageSource messageSource,
                         ProviderContextWindows contextWindows) {
        this.llmRouter = llmRouter;
        this.messageSource = messageSource;
        this.props = appProperties;
        this.maxRetryCount = appProperties.maxRetryCount();
        this.contextWindows = contextWindows;
    }

    /**
     * 이 턴을 받을 프로바이더의 입력 예산 — 모르면 {@code null}(= 자르지 않는다).
     *
     * <p>어느 프로바이더가 받을지는 라우터가 정하므로 먼저 물어본다. 물어본 뒤 실제 호출 사이에
     * 답이 달라질 수 있지만(서킷 브레이커가 그 사이 열리거나 닫히는 경우), 그 경우 대체되는 것은
     * 보통 <b>다른 역할</b>의 프로바이더 — 즉 창이 더 큰 클라우드 — 라 결과는 "덜 잘랐어야 했는데
     * 더 잘랐다" 쪽이다. 같은 우선순위의 형제는 같은 모델을 돌리는 부하분산 쌍이라(§6.21) 창이
     * 같다. 어느 쪽이든 초과를 부르는 방향은 아니다.
     *
     * <p>출력 예약은 이 모드가 실제로 붙일 {@code maxTokens} 다. 스트리밍은 캡을 붙이지 않지만
     * 답변이 자라날 자리는 여전히 필요하므로 같은 값을 <b>예상 분량</b>으로 잡아 둔다 — 입력이 창을
     * 꽉 채우면 생성할 자리가 없어 결국 같은 초과가 난다.
     */
    private PromptBudget budgetFor(AgentState state) {
        int window = contextWindows.tokensOrZero(
                llmRouter.findProviderName(TaskType.TEXT, state.routingMode()));
        if (window <= 0) return null;   // 창을 모른다 — 추측으로 근거를 버리지 않는다
        return new PromptBudget(window, state.responseMode().maxTokens(props.llmSafe().maxTokens()));
    }

    public AgentState execute(AgentState state) {
        return executeBlocking(state);
    }

    public AgentState executeStreaming(AgentState state, GraphListener listener) {
        return executeStreamingNormal(state, listener);
    }

    // ── Blocking paths ──────────────────────────────────────────────────────

    private AgentState executeBlocking(AgentState state) {
        String systemPrompt = answerSystemPrompt(state.locale(), state.responseMode());
        String userPrompt = buildAnswerPrompt(state);
        ChatOptions options = answerOptions(state);
        LlmRouter.LlmResult result = llmRouter.executeGatedWithUsage(TaskType.TEXT, state.routingMode(),
                model -> model.call(buildPrompt(systemPrompt, userPrompt, options)));
        String answer = truncate(enforceSummaryOnly(result.text() == null ? "" : result.text(), state.responseMode()));
        state = state.toBuilder()
                     .accumulateTokens(result.inputTokens(), result.outputTokens())
                     .usedProvider(llmRouter.findProviderName(TaskType.TEXT, state.routingMode()))
                     .answer(answer)
                     .build();
        return checkSufficiencyAndMaybeUpgrade(state, answer, null);
    }

    // ── Streaming paths ─────────────────────────────────────────────────────

    private AgentState executeStreamingNormal(AgentState state, GraphListener listener) {
        String systemPrompt = answerSystemPrompt(state.locale(), state.responseMode());
        LlmProvider provider = llmRouter.routeProvider(TaskType.TEXT, state.routingMode());
        String streamed;
        try (var permit = llmRouter.acquirePermit(provider)) {
            streamed = streamAnswer(provider, state, systemPrompt, listener::onToken);
        }
        String answer = truncate(enforceSummaryOnly(streamed, state.responseMode()));
        // streaming has no ChatResponse to read real usage from — record an approximate
        // (chars/4) usage entry so /llm-usage isn't blind to the entire streaming chat path, and
        // reflect the same estimate in the per-turn total so the chat UI isn't stuck at 0/0.
        String promptText = systemPrompt + buildAnswerPrompt(state);
        llmRouter.recordApproxUsage(provider.name(), promptText, answer);
        int approxIn = (int) LlmRouter.approxTokens(promptText);
        int approxOut = (int) LlmRouter.approxTokens(answer);
        state = state.toBuilder().accumulateTokens(approxIn, approxOut).usedProvider(provider.name()).answer(answer).build();
        return checkSufficiencyAndMaybeUpgrade(state, answer, listener);
    }

    // ── Evaluation (sufficiency + grounding) + PROGRESSIVE ───────────────────

    private AgentState checkSufficiencyAndMaybeUpgrade(AgentState state, String answer, GraphListener listener) {
        // 검증을 건너뛰는 모드(현재 S): eval LLM 호출도, "응답 검증 중..." 인디케이터도, 재시도
        // 루프도 없다. AgentGraph가 같은 성질로 CRITIC을 건너뛰므로 두 게이트가 함께 꺼진다.
        // grounded는 null로 남는다(검증 미실행) — directMode와 같은 규약.
        if (state.responseMode().skipsVerification()) {
            return state;
        }
        // The blocking evaluate() call below can take several seconds to tens of seconds with no
        // token/stage event of its own — tell the streaming client to show a "verifying" indicator
        // instead of going silent between the last answer token and the next event.
        if (listener != null) listener.onVerifying();
        AgentState resultState = evaluate(state, answer, state.locale());
        if (state.routingMode() == RoutingMode.PROGRESSIVE
                && resultState.needsRetry()
                && state.retryCount() >= maxRetryCount) {
            return progressiveUpgrade(state, resultState, listener);
        }
        return resultState;
    }

    private AgentState progressiveUpgrade(AgentState state, AgentState resultState, GraphListener listener) {
        String systemPrompt = answerSystemPrompt(state.locale(), state.responseMode());
        LlmProvider premiumProvider = llmRouter.routeProvider(TaskType.TEXT, RoutingMode.QUALITY_FIRST);
        if (listener != null) listener.onUpgrade(premiumProvider.name());
        String premiumAnswer;
        int inputTokens, outputTokens;
        if (listener != null) {
            try (var permit = llmRouter.acquirePermit(premiumProvider)) {
                premiumAnswer = streamAnswer(premiumProvider, state, systemPrompt, listener::onToken);
            }
            String promptText = systemPrompt + buildAnswerPrompt(state);
            llmRouter.recordApproxUsage(premiumProvider.name(), promptText, premiumAnswer);
            inputTokens = (int) LlmRouter.approxTokens(promptText);
            outputTokens = (int) LlmRouter.approxTokens(premiumAnswer);
        } else {
            String userPrompt = buildAnswerPrompt(state);
            LlmRouter.LlmResult result = llmRouter.executeGatedWithUsage(
                    TaskType.TEXT, RoutingMode.QUALITY_FIRST,
                    model -> model.call(buildPrompt(systemPrompt, userPrompt, answerOptions(state)))
            );
            premiumAnswer = result.text();
            inputTokens = result.inputTokens();
            outputTokens = result.outputTokens();
        }
        return resultState.toBuilder()
                          .answer(truncate(premiumAnswer))
                          .usedProvider(premiumProvider.name())
                          .premiumUpgraded(premiumProvider.name())
                          .accumulateTokens(inputTokens, outputTokens)
                          .needsRetry(false)
                          .build();
    }

    /** Shared raw Prompt construction for the non-fluent LlmRouter call sites (evaluation, PROGRESSIVE). */
    private static Prompt buildPrompt(String systemPrompt, String userPrompt, ChatOptions options) {
        List<org.springframework.ai.chat.messages.Message> messages =
                List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt));
        return options == null ? new Prompt(messages) : new Prompt(messages, options);
    }

    // ── Stream helpers ──────────────────────────────────────────────────────

    private String streamAnswer(LlmProvider provider, AgentState state,
                                String systemPrompt, Consumer<String> tokenSink) {
        StringBuilder full = new StringBuilder();
        callOrStream(provider, state, systemPrompt, t -> { tokenSink.accept(t); full.append(t); });
        return full.toString();
    }

    private void callOrStream(LlmProvider provider, AgentState state,
                              String systemPrompt, Consumer<String> tokenSink) {
        if (provider.stream()) {
            // Bypass OpenAiChatModel.internalStream() which buffers ALL chunks via buffer(int,int)
            // before emitting, defeating real-time token delivery to the browser.
            streamDirect(provider, systemPrompt, buildAnswerPrompt(state), tokenSink,
                    state.threadId(), state.routingMode(), answerTemperature(state.responseMode()));
        } else {
            // stream=false: still use streaming HTTP to stay compatible with local LLM servers
            // that do not support stream:false. Buffer all tokens and deliver as one chunk.
            StringBuilder buf = new StringBuilder();
            ChatClient.ChatClientRequestSpec spec = ChatClient.builder(provider.chatModel()).build().prompt();
            ChatOptions options = answerOptions(state);
            if (options != null) spec = spec.options(options);
            spec
                    .system(systemPrompt)
                    .user(buildAnswerPrompt(state))
                    .stream()
                    .content()
                    .doOnNext(buf::append)
                    .blockLast();
            if (!buf.isEmpty()) tokenSink.accept(buf.toString());
        }
    }

    private void streamDirect(LlmProvider provider, String systemPrompt, String userPrompt,
                               Consumer<String> tokenSink, String threadId, RoutingMode routingMode,
                               double temperature) {
        List<OpenAiApi.ChatCompletionMessage> messages = List.of(
                new OpenAiApi.ChatCompletionMessage(systemPrompt, OpenAiApi.ChatCompletionMessage.Role.SYSTEM),
                new OpenAiApi.ChatCompletionMessage(userPrompt, OpenAiApi.ChatCompletionMessage.Role.USER)
        );
        // §6.18 — general/RAG temperature (app.llm.temperature / LLM_TEMPERATURE), was hardcoded 0.0.
        // §6.24 — the caller now picks between that and creative-temperature by response mode; this
        // path is the one the chat UI actually uses, so a mode-aware temperature that skipped it
        // would be invisible everywhere it matters.
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
                        .doOnCancel(() -> log.warn("[Answer] Stream cancelled provider={} thread={} route={}",
                                provider.name(), threadId, routingMode))
                        .doOnError(e -> log.error("[Answer] Stream error provider={}", provider.name(), e))
                        .doFinally(signal -> log.debug("[Answer] Stream finished signal={} provider={} thread={}",
                                signal, provider.name(), threadId)),
                tokenSink);
    }

    /**
     * streamDirect() calls {@link OpenAiApi} directly, bypassing {@code ChatModel} (and therefore
     * {@link com.example.ragagent.llm.LoggingChatModel}) entirely — see the class javadoc for why.
     * Without this, the actual RAG answer request (the one carrying the retrieved-document
     * context) never showed up in logs at any level. Mirrors LoggingChatModel's TRACE(full
     * curl)/DEBUG(endpoint+body) split via the shared {@link LlmCurlLogger}.
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

    /**
     * single LLM call evaluating both gates at once.
     * needsRetry is driven by sufficiency (the ANSWER-node gate); grounded is stored for the
     * CRITIC node to consume without a second LLM round-trip. When no docs were retrieved,
     * grounding is trivially true (CRITIC short-circuits on empty docs anyway).
     *
     * <p>The same call also returns {@code reason} — one sentence naming what was missing. Without
     * it a failed verification is only ever observable as a boolean, so neither the user (who sees
     * a retry, or a 미검증 badge on the final answer) nor an operator reading the log can tell
     * whether the corpus lacks the content, the question was ambiguous, or the model over-claimed.
     * Asking for it in the evaluation call that is already being made costs no extra round-trip.
     * Only stored when a gate actually failed — a passing turn keeps {@code evalReason} null so
     * downstream code can treat non-null as "this turn has something to explain".
     *
     * <p>{@code envNote} follows the opposite rule and is kept regardless of the verdict: it exists
     * because environment-dependent values (paths, hosts, ports, env-var values) are no longer a
     * legitimate reason to fail {@code grounded} — the prompt routes them here instead — and the
     * reader still needs to be told which values to substitute for their own machine.
     *
     * <p>{@code usedDocs} (2단계 응답 참여도) rides along for the same reason {@code reason} does:
     * this call already holds the answer and every excerpt side by side, so asking which excerpt
     * numbers the answer actually drew on costs no extra round-trip. It is <b>advisory only</b> —
     * it never gates anything, a model that omits it degrades to pure lexical attribution, and it
     * can only narrow the candidate set, never manufacture a share (see {@code AnswerAttribution}).
     */
    private AgentState evaluate(AgentState state, String answer, Locale locale) {
        if (state.responseMode().usesCreativeEval()) {
            return evaluateCreative(state, answer, locale);
        }
        boolean docsPresent = !state.retrievedDocs().isEmpty();
        try {
            String systemPrompt = messageSource.getMessage(state.responseMode().evalPromptKey(), null, locale);
            String excerpts = buildEvalExcerpts(state.retrievedDocs(),
                    evalExcerptTokenBudget(state, systemPrompt, answer, evalConverter.getFormat()));
            String evalPrompt = buildEvalPrompt(state, answer, excerpts, evalConverter.getFormat());

            LlmRouter.LlmResult result = llmRouter.executeGatedWithUsage(TaskType.TEXT, state.routingMode(),
                    model -> model.call(buildPrompt(systemPrompt, evalPrompt, evalOptions())));
            if (isEmptyVerdict(result)) {
                logEmptyVerdict("EVAL", state, result);
                return withoutVerdict(state.toBuilder()
                        .accumulateTokens(result.inputTokens(), result.outputTokens()).build());
            }
            EvalOutput out = evalConverter.convert(result.text());
            boolean grounded = !docsPresent || out.grounded();
            boolean passed = out.sufficient() && grounded;
            String reason = passed ? null : normalizeOneLine(out.reason(), MAX_EVAL_REASON_LEN);
            String envNote = normalizeOneLine(out.envNote(), MAX_ENV_NOTE_LEN);
            if (!passed) {
                log.info("[EVAL] 검증 미통과 thread={} sufficient={} grounded={} reason={}",
                        state.threadId(), out.sufficient(), grounded, reason);
            }
            if (envNote != null) {
                log.debug("[EVAL] 환경 의존 값 안내 thread={} envNote={}", state.threadId(), envNote);
            }
            return state.toBuilder()
                    .accumulateTokens(result.inputTokens(), result.outputTokens())
                    .needsRetry(!out.sufficient())
                    .grounded(grounded)
                    .evalReason(reason)
                    .envNote(envNote)
                    .usedDocIndices(out.usedDocs())
                    .build();
        } catch (Exception e) {
            log.warn("[EVAL] 검증 응답을 읽지 못했다 — 판정 없음으로 기록한다(재시도 없음, 배지 없음): {}",
                    e.getMessage());
            return withoutVerdict(state);
        }
    }

    /**
     * C(응용) 전용 검증 (§6.24 Step 2-d) — {@link #evaluate}와 <b>같은 자리, 같은 왕복 수</b>다.
     * 검증을 끄는 것이 아니라 판정 기준만 창의 모드에 맞게 바꿔 끼운다
     * ({@link CreativeEvalOutput} 참조).
     *
     * <p>{@code apiGrounded}는 그대로 {@code grounded}에 실린다 — CRITIC은 그 불린 하나만 보므로
     * {@code CriticService}는 한 줄도 바뀌지 않는다. 재시도를 거는 것은 {@code sufficient}(요청한
     * 산출물을 실제로 만들었는가)뿐이고, {@code inventedSymbols}는 <b>재시도를 걸지 않는다</b> —
     * 창의 모드에서 이름을 지어내는 것 자체는 실패가 아니고 그것을 문서 근거인 양 제시하는 것이
     * 문제라서, 다시 생성시키는 대신 독자에게 경고로 보여주는 편이 맞다.
     *
     * <p>파싱 실패 시의 폴백은 {@link #evaluate}와 같다 — {@link #withoutVerdict}(판정 없음).
     * 검증기 자체의 고장이 완성된 답변의 전달을 막아서는 안 되지만, 그렇다고 검증한 적 없는
     * 답변에 통과 배지를 달아 줘서도 안 된다.
     */
    private AgentState evaluateCreative(AgentState state, String answer, Locale locale) {
        boolean docsPresent = !state.retrievedDocs().isEmpty();
        try {
            String systemPrompt = messageSource.getMessage(state.responseMode().evalPromptKey(), null, locale);
            String excerpts = buildEvalExcerpts(state.retrievedDocs(),
                    evalExcerptTokenBudget(state, systemPrompt, answer, creativeEvalConverter.getFormat()));
            String evalPrompt = buildEvalPrompt(state, answer, excerpts, creativeEvalConverter.getFormat());

            LlmRouter.LlmResult result = llmRouter.executeGatedWithUsage(TaskType.TEXT, state.routingMode(),
                    model -> model.call(buildPrompt(systemPrompt, evalPrompt, evalOptions())));
            if (isEmptyVerdict(result)) {
                logEmptyVerdict("EVAL-C", state, result);
                return withoutVerdict(state.toBuilder()
                        .accumulateTokens(result.inputTokens(), result.outputTokens()).build());
            }
            CreativeEvalOutput out = creativeEvalConverter.convert(result.text());
            boolean apiGrounded = !docsPresent || out.apiGrounded();
            boolean passed = out.sufficient() && apiGrounded;
            List<String> invented = out.inventedSymbols() == null ? List.of() : out.inventedSymbols();
            // 실패 사유는 발명된 이름 그 자체다 — 별도 필드를 만들면 SSE 페이로드·로그·툴팁 세 곳을
            // 모두 늘려야 하는데, evalReason 이 이미 그 셋을 지나가는 "한 문장 설명" 자리다.
            String reason = passed ? null
                    : normalizeOneLine(invented.isEmpty()
                            ? "요청한 산출물을 만들지 못했거나 문서에 없는 이름을 문서 근거로 제시함"
                            : "문서 발췌에 없는 이름을 문서에 있는 것처럼 사용: " + String.join(", ", invented),
                            MAX_EVAL_REASON_LEN);
            String envNote = normalizeOneLine(out.envNote(), MAX_ENV_NOTE_LEN);
            if (!passed) {
                log.info("[EVAL-C] 검증 미통과 thread={} sufficient={} apiGrounded={} invented={}",
                        state.threadId(), out.sufficient(), apiGrounded, invented);
            } else if (!invented.isEmpty()) {
                log.info("[EVAL-C] 통과했으나 미확인 심볼 보고 thread={} invented={}", state.threadId(), invented);
            }
            return state.toBuilder()
                    .accumulateTokens(result.inputTokens(), result.outputTokens())
                    .needsRetry(!out.sufficient())
                    .grounded(apiGrounded)
                    .evalReason(reason)
                    .envNote(envNote)
                    .usedDocIndices(List.of())
                    .inventedSymbols(invented)
                    .build();
        } catch (Exception e) {
            log.warn("[EVAL-C] 창의 검증 응답을 읽지 못했다 — 판정 없음으로 기록한다"
                     + "(재시도 없음, 배지 없음): {}", e.getMessage());
            return withoutVerdict(state);
        }
    }

    /**
     * 검증 결과를 <b>읽지 못했을 때</b>의 상태 — 판정을 위조하지 않는다.
     *
     * <p>예전에는 이 자리에서 {@code grounded=true} 를 써넣었다. 재시도를 막는 것까지는 옳다
     * (검증기 고장이 이미 완성된 답변의 전달을 막아서는 안 된다). 틀린 것은 그 다음이었다 —
     * 그 {@code true} 가 그대로 {@code VerificationSnapshot} 으로 흘러가 <b>검증한 적 없는 답변에
     * '검증됨'(N) / '생성'(C) 배지</b>가 붙었다. 실제로 관찰된 사고가 그것이다: 창의 검증이 빈
     * 응답을 반환 → 파싱 실패 → 통과 처리 → 재시도({@code sufficient=false} 로 걸렸어야 할)도
     * 돌지 않고 파란 '생성' 배지까지 붙은 답변이 나갔다.
     *
     * <p>{@code grounded=null} 은 이 앱에서 이미 <b>"검증 미실행"</b>을 뜻하고, 그 상태는 끝까지
     * 그렇게 흐른다: {@code CriticService} 가 덮어쓰지 않고, {@code VerificationSnapshot.isEmpty()}
     * 가 참이 되어 {@code MemoryService.saveVerification()} 이 저장을 건너뛰며(컬럼 NULL),
     * {@code verdictLabel()} 과 {@code chat-stream.js} 의 {@code === true}/{@code === false} 비교가
     * 둘 다 배지를 그리지 않는다. 즉 새 UI 상태를 만드는 것이 아니라 <b>이미 있는 상태로 정직하게
     * 되돌리는 것</b>이다 — S 모드와 meta/Direct 턴이 늘 그렇게 표시돼 왔다.
     */
    private static AgentState withoutVerdict(AgentState state) {
        return state.toBuilder()
                .needsRetry(false)
                .grounded(null)
                .evalReason(null)
                .envNote(null)
                .usedDocIndices(List.of())
                .inventedSymbols(List.of())
                .build();
    }

    /**
     * 검증 호출이 빈 응답을 돌려줬는지. 빈 응답은 <b>깨진 JSON과 다른 사고</b>다 — 모델이 판정을
     * 내렸는데 형식이 틀린 것이 아니라, 아무것도 내지 못한 것이다. 두 경우의 처리는 같지만
     * (판정 없음) 원인이 달라 로그를 나눈다: 이 경로가 잦다면 프롬프트가 아니라 <b>요청 크기나
     * 모델 쪽</b>을 봐야 한다. 검증 호출은 이 앱에서 가장 큰 단일 요청이다 — 질문 + 답변 전문 +
     * {@link #buildEvalExcerpts} 발췌(topK × chunk-size, 상한 {@link #MAX_EVAL_EXCERPT_CHARS}) +
     * 응답 스키마가 한 번에 들어간다.
     */
    private static boolean isEmptyVerdict(LlmRouter.LlmResult result) {
        return result.text() == null || result.text().isBlank();
    }

    /** 빈 응답 진단 로그 — {@code outputTokens} 가 이 사고의 원인을 가르는 유일한 단서다. */
    private void logEmptyVerdict(String tag, AgentState state, LlmRouter.LlmResult result) {
        log.warn("[{}] 검증기가 빈 응답을 반환했다 — 판정 없음으로 기록한다(재시도 없음, 배지 없음). "
                 + "thread={} inputTokens={} outputTokens={} | outputTokens=0 이면 모델이 아무것도 "
                 + "내지 못한 것(컨텍스트 초과 의심), 0보다 크면 content 가 아닌 곳으로 나온 것"
                 + "(reasoning 모델).",
                tag, state.threadId(), result.inputTokens(), result.outputTokens());
    }

    /** 두 검증 경로가 공유하는 사용자 메시지 — 차이는 시스템 프롬프트와 {@code format}(응답 스키마)뿐이다. */
    private static String buildEvalPrompt(AgentState state, String answer, String excerpts, String format) {
        return "[질문]\n%s\n\n[답변]\n%s\n\n[문서 발췌]\n%s\n\n%s"
                .formatted(PromptInjectionGuard.wrap(state.question()), answer, excerpts, format);
    }

    /**
     * The evidence block the evaluator judges the answer against.
     *
     * <p><b>It must be the same evidence the answer was written from.</b> This used to send only
     * the first 5 documents while {@link #buildAnswerPrompt} passes all of {@code retrievedDocs}
     * ({@code app.search-top-k}, default 10) — so an answer correctly citing a value found only in
     * document #6-8 was judged against excerpts that could not contain it. Paths, ports, addresses
     * and other constants are exactly the facts this hits hardest: they live in a single chunk,
     * unlike a prose claim that gets restated across several.
     *
     * <p>A retry does not repair that. {@code RetrievalService} escalates {@code candidateK} — the
     * pool it searches — but the final cut is always {@code defaultTopK}, so the answer node keeps
     * seeing exactly topK documents and the evaluator keeps seeing the same first five of them. A
     * grounded=false caused by the window, rather than by the answer, therefore tends to reproduce
     * on every attempt and burn the whole retry budget before delivering a 미검증 answer.
     *
     * <p>Text is normalized with the same {@link MarkdownNoiseNormalizer} the answer prompt uses,
     * so both calls also see the same <em>form</em> of a value — the evaluator comparing a raw
     * {@code **8080**} against an answer written from the stripped {@code 8080} was its own small
     * source of false mismatches.
     *
     * <p>{@link #MAX_EVAL_EXCERPT_CHARS} bounds the prompt for pathological configurations (a very
     * large topK or chunk size). Documents are dropped <em>whole</em>, lowest-ranked first, and
     * never truncated mid-document — half a chunk is precisely how the path or constant under
     * verification disappears. The top-ranked document is always kept.
     */
    /**
     * 검증 호출의 발췌에 쓸 수 있는 토큰 수 — 창을 모르면 {@code 0}(= 토큰 예산 없음, 글자 상한만).
     *
     * <p><b>답변 호출과 따로 계산해야 한다.</b> 이 호출은 프롬프트 모양이 다르다: 검색 문서는 같지만
     * 거기에 <b>답변 전문</b>과 응답 스키마가 더 얹히고, 대화 이력은 빠진다. 답변 프롬프트가 예산에
     * 들어갔다는 사실이 검증 프롬프트도 들어간다는 보장이 되지 못하는 이유이고, 실제로 이 앱에서
     * 가장 큰 단일 요청은 답변이 아니라 이쪽이다.
     *
     * <p>출력 예약은 {@link #MAX_EVAL_OUTPUT_TOKENS} 다 — 이 호출은 스스로 그 값으로 조이므로
     * 프로바이더의 일반 예약이 아니라 실제로 예약되는 값을 빼야 맞다.
     */
    private long evalExcerptTokenBudget(AgentState state, String systemPrompt, String answer, String schema) {
        int window = contextWindows.tokensOrZero(
                llmRouter.findProviderName(TaskType.TEXT, state.routingMode()));
        if (window <= 0) return 0;   // 창 모름 → 글자 상한만 적용(예전 동작 그대로)
        long fixed = TokenEstimator.estimate(systemPrompt)
                + TokenEstimator.estimate(answer)
                + TokenEstimator.estimate(state.question())
                + TokenEstimator.estimate(schema);
        return Math.max(0, new PromptBudget(window, MAX_EVAL_OUTPUT_TOKENS).inputBudget() - fixed);
    }

    /**
     * @param tokenBudget 창에서 파생된 발췌 토큰 예산. {@code 0} 이하면 적용하지 않는다(창 모름).
     *                    {@link #MAX_EVAL_EXCERPT_CHARS} 글자 상한은 <b>그와 무관하게 늘 걸린다</b> —
     *                    그쪽은 과대 설정을 막는 절대 안전판이고, 이쪽은 이 프로바이더에 실제로 들어가는
     *                    양이라 성격이 다르다. 둘 중 먼저 걸리는 쪽에서 멈춘다.
     */
    private static String buildEvalExcerpts(List<Document> docs, long tokenBudget) {
        StringBuilder sb = new StringBuilder();
        int used = 0;
        long usedTokens = 0;
        int included = 0;
        for (Document d : docs) {
            String text = MarkdownNoiseNormalizer.normalize(d.getText());
            long tokens = TokenEstimator.estimate(text);
            boolean overChars = used + text.length() > MAX_EVAL_EXCERPT_CHARS;
            boolean overTokens = tokenBudget > 0 && usedTokens + tokens > tokenBudget;
            if (included > 0 && (overChars || overTokens)) break;
            if (included > 0) sb.append("\n---\n");
            // [D1], [D2], … — the numbering the eval prompt's usedDocs field refers to. 1-based and
            // in prompt order, so an index maps straight back to retrievedDocs.get(n-1). A document
            // dropped by the size cap simply never gets a number, which is why the cap truncates
            // from the tail: indices of the documents that ARE included never shift.
            sb.append("[D").append(included + 1).append("]\n").append(text);
            used += text.length();
            usedTokens += tokens;
            included++;
        }
        if (included < docs.size()) {
            log.warn("[EVAL] 발췌 한도(글자 {} / 토큰 {})로 {}개 중 {}개만 검증에 사용 — 하위 순위 문서 제외. "
                     + "검증 창이 답변 창보다 좁아지면 6~8번째 문서에만 있는 값을 정확히 인용한 답변이 "
                     + "근거 없음으로 판정될 수 있으므로, app.search-top-k / app.chunk-size 를 낮추거나 "
                     + "프로바이더의 컨텍스트를 키우는 편이 낫다.",
                    MAX_EVAL_EXCERPT_CHARS, tokenBudget > 0 ? tokenBudget : "미적용",
                    docs.size(), included);
        }
        return sb.toString();
    }

    /**
     * Keeps a model-written explanation to one short line. It lands in an SSE payload, a log line
     * and a tooltip, none of which want a paragraph — and a model that ignores the "one sentence"
     * ask would otherwise leak the whole chain of thought into all three. Blank → null, so callers
     * can test one thing ("is there a note?") instead of two.
     */
    private static String normalizeOneLine(String raw, int maxLen) {
        if (raw == null) return null;
        String oneLine = raw.replaceAll("\\s+", " ").strip();
        if (oneLine.isEmpty()) return null;
        return oneLine.length() > maxLen
                ? oneLine.substring(0, maxLen).strip() + "…"
                : oneLine;
    }

    /**
     * 이 턴의 모드 전용 시스템 프롬프트 (PLAN §6.24 Step 1-a).
     *
     * <p>예전에는 공용 프롬프트 하나를 쓰고 사용자 메시지 끝에 "[응답 스타일]" 지시문을 덧붙여
     * 형식을 뒤집으려 했다. 그 방식은 S에서 실패했다 — 시스템 프롬프트가 나열한 5섹션 헤더
     * 목록이 사용자 메시지의 한 줄 부정 지시보다 강하게 작용해 모델이 전부 생성했고, 서버가
     * 사후에 잘라내면서 화면과 DB가 달라졌다. 이제 모드마다 프롬프트를 통째로 바꾼다.
     */
    private String answerSystemPrompt(Locale locale, ResponseMode mode) {
        return messageSource.getMessage(mode.answerSystemPromptKey(), null, locale);
    }

    /**
     * Per-call options for a blocking answer call: general/RAG temperature (§6.18, hot — read fresh
     * per call) plus {@code maxTokens} derived from the response mode's share of the configured
     * {@code app.llm.max-tokens} (never above it — see {@link ResponseMode#maxTokens(int)}).
     *
     * <p>maxTokens is only attached on the <b>blocking</b> call paths; streaming has no per-call cap
     * (token-by-token UX). That asymmetry no longer matters for length control: the budget is a
     * safety valve, and what actually shapes the answer is the mode's own system prompt — S names a
     * 1,000-character ceiling there, N deliberately names no number at all (§6.24).
     */
    private ChatOptions answerOptions(AgentState state) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .temperature(answerTemperature(state.responseMode()));
        int configured = props.llmSafe().maxTokens();
        int max = state.responseMode().maxTokens(configured);
        if (max > 0) builder.maxTokens(max);
        return builder.build();
    }

    /**
     * 이 턴의 답변 생성 온도 (§6.24 Step 2-b).
     *
     * <p>문서 충실 모드(S/N)는 일반/RAG 온도를 쓴다 — clamp가 [0.0, 0.3]이라 표본추출로 사실이
     * 흔들리지 않는다. C(응용)만 창의 온도({@code app.llm.creative-temperature}, clamp [0.0, 1.0])를
     * 쓰는데, 바로 그 0.3 상한 때문에 일반 온도로는 창의 생성이 원천 봉쇄되기 때문이다.
     *
     * <p>둘 다 hot이라 매 호출 새로 읽는다. 그리고 이 메서드는 <b>블로킹과 스트리밍 양쪽</b>에서
     * 불려야 한다 — 채팅 UI의 유일한 전송 경로가 스트리밍이므로, {@code streamDirect()}를 빠뜨리면
     * 화면에서만 온도가 안 오르고 그 사실이 아무 로그에도 남지 않는다.
     */
    private double answerTemperature(ResponseMode mode) {
        AppProperties.LlmConfig llm = props.llmSafe();
        return mode.usesCreativeTemperature() ? llm.creativeTemperature() : llm.temperature();
    }

    /** Sufficiency-evaluation call options: general/RAG temperature only (no maxTokens cap — the
     *  structured JSON output is already short). Deliberately NOT the creative temperature even for
     *  C: judging whether an identifier appears in an excerpt is a lookup, not a creative task.
     *  Hot — read fresh per call. */
    private ChatOptions evalOptions() {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .temperature(props.llmSafe().temperature());
        int configured = props.llmSafe().maxTokens();
        // 0 이하 = "프로바이더 기본값 유지" (answerOptions 와 같은 규약).
        if (configured > 0) builder.maxTokens(Math.min(configured, MAX_EVAL_OUTPUT_TOKENS));
        return builder.build();
    }

    private String buildAnswerPrompt(AgentState state) {
        StringBuilder sb = new StringBuilder();

        // 예산에 맞춰 미리 덜어낸다. 호출 후에 초과 오류를 받고 재시도하는 것보다 왕복이 0회 싸고,
        // 무엇을 버릴지도 여기서만 알 수 있다(라우터는 프롬프트 안을 못 본다). 창을 모르면
        // fitToBudget 이 원본을 그대로 돌려주므로 예전과 동작이 같다.
        Fitted fitted = fitToBudget(state);

        if (!fitted.history().isBlank()) {
            sb.append("[이전 대화]\n").append(fitted.history()).append("\n\n");
        }

        String docsContext = fitted.docs().stream()
                .map(doc -> {
                    // §10.10 — a curated Q&A hit has no real filename/page; label it distinctly
                    // instead of leaking the "curated_qa | p.1" placeholder metadata into the prompt.
                    String label = "curated_qa".equals(doc.getMetadata().get(MetaKey.DOC_TYPE))
                            ? "[큐레이션 Q&A]"
                            : "[%s | p.%s]".formatted(
                                    String.valueOf(doc.getMetadata().getOrDefault(MetaKey.FILENAME, "unknown")),
                                    String.valueOf(doc.getMetadata().getOrDefault(MetaKey.PAGE_OR_SLIDE, "?")));
                    // Normalized (no context header, §10.1) — decorative markdown is stripped so it
                    // doesn't consume prompt tokens; the stored/displayed text elsewhere stays raw.
                    return label + "\n" + MarkdownNoiseNormalizer.normalize(doc.getText());
                })
                .collect(Collectors.joining("\n\n---\n\n"));

        if (!docsContext.isBlank()) {
            sb.append("[검색된 문서]\n").append(docsContext).append("\n\n");
        } else {
            sb.append("[검색된 문서]\n문서를 찾을 수 없습니다.\n\n");
        }

        if (!state.retrievalWarnings().isEmpty()) {
            sb.append("[경고]\n").append(String.join("\n", state.retrievalWarnings())).append("\n\n");
        }

        sb.append("[질문]\n").append(PromptInjectionGuard.wrap(state.question()));
        return sb.toString();
    }

    /** 예산에 맞춘 결과 — 무엇이 얼마나 남았는지. */
    private record Fitted(List<Document> docs, String history) {}

    /**
     * 프롬프트가 프로바이더의 창에 들어가도록 <b>문서를 먼저</b>, 그래도 넘치면 <b>대화 이력을</b>
     * 오래된 것부터 덜어낸다.
     *
     * <p><b>문서를 먼저 버리는 이유.</b> 검색 결과는 RRF 점수 내림차순이라 뒤쪽이 최저 관련도이고,
     * 그 문서들이 답변에 기여하지 않는 일은 흔하다(§ 응답 참여도 — 검색 1위조차 한 글자도 기여하지
     * 않는 경우가 있어 출처 표시 순서를 참여도 우선으로 바꿨다). 반면 대화 이력이 사라지면 사용자는
     * "방금 말한 걸 잊었다"로 즉시 체감한다. 덜 아픈 쪽을 먼저 버린다.
     *
     * <p>이력은 {@code "Q: …\nA: …"} 를 빈 줄로 이어 붙인 형식이라(오래된 것이 앞) 턴 경계에서
     * 자를 수 있다. 답변 본문에도 빈 줄이 있을 수 있으므로 <b>빈 줄 다음에 {@code "Q: "} 가 오는
     * 자리</b>만 경계로 본다 — 문자 인덱스로 자르면 반쪽짜리 턴이 남아 모델을 더 헷갈리게 한다.
     */
    private Fitted fitToBudget(AgentState state) {
        List<Document> docs = state.retrievedDocs();
        String history = state.conversationHistory();
        PromptBudget budget = budgetFor(state);
        if (budget == null) return new Fitted(docs, history);   // 창 모름 → 예전 그대로

        long fixedCost = TokenEstimator.estimate(state.question())
                + TokenEstimator.estimate(String.join("\n", state.retrievalWarnings()))
                + ANSWER_PROMPT_OVERHEAD_TOKENS;
        long limit = budget.inputBudget();

        List<Document> keptDocs = PromptBudget.fitByPrefix(docs,
                d -> TokenEstimator.estimate(MarkdownNoiseNormalizer.normalize(d.getText())),
                fixedCost + TokenEstimator.estimate(history), limit);

        // 문서를 줄여도 안 들어가면 이력을 오래된 턴부터 덜어낸다.
        long docCost = keptDocs.stream()
                .mapToLong(d -> TokenEstimator.estimate(MarkdownNoiseNormalizer.normalize(d.getText())))
                .sum();
        String keptHistory = trimHistory(history, limit - fixedCost - docCost);

        if (keptDocs.size() < docs.size() || keptHistory.length() < history.length()) {
            log.warn("[BUDGET] 컨텍스트 창 {}토큰(출력 예약 {}) → 입력 예산 {}토큰에 맞춰 축소: "
                     + "문서 {}→{}개, 이력 {}→{}자. app.search-top-k / app.chunk-size 를 낮추거나 "
                     + "프로바이더의 컨텍스트를 키우면 이 축소가 사라집니다.",
                    budget.contextWindow(), budget.outputReservation(), limit,
                    docs.size(), keptDocs.size(), history.length(), keptHistory.length());
        }
        return new Fitted(keptDocs, keptHistory);
    }

    /** 턴 경계(빈 줄 + {@code "Q: "})에서 오래된 쪽부터 버린다. 예산이 음수면 전부 버린다. */
    private static String trimHistory(String history, long budget) {
        if (history == null || history.isBlank()) return "";
        if (budget <= 0) return "";
        if (TokenEstimator.estimate(history) <= budget) return history;

        List<String> turns = new ArrayList<>(List.of(history.split("\n\n(?=Q: )")));
        while (!turns.isEmpty()) {
            turns.removeFirst();   // 가장 오래된 턴
            String candidate = String.join("\n\n", turns);
            if (TokenEstimator.estimate(candidate) <= budget) return candidate;
        }
        return "";
    }

    /** 절단 안내 — 코드 펜스 <b>바깥</b>의 평문이어야 한다({@link #truncate} 참조). */
    private static final String TRUNCATION_NOTICE = "…(응답이 너무 길어 잘렸습니다)";

    /**
     * Absolute ceiling protecting storage/rendering — independent of the response mode, whose
     * budget is expressed as the call's maxTokens instead of a character cap.
     *
     * <p><b>줄 경계에서 자르고 코드 펜스 짝을 맞춘다</b> (§6.24 Step 3-c). 예전에는 문자
     * 인덱스로 그냥 잘랐는데, 그 자리가 펜스 안이면 펜스 줄 수가 홀수인 답변이 만들어진다.
     * 그 답변이 문서 내보내기나 재색인을 타는 순간 {@code MarkdownCorrectionService} 의
     * <b>코드펜스 짝 맞춤 불변식</b>이 깨지고, {@code normalizeCodeBlocks()} 가 짝을 확정할 수
     * 없다며 <b>그 문서 전체</b>의 언어 태그·코드 정리를 건너뛴다 — 잘린 답변 하나가 문서
     * 단위 품질 저하로 번진다.
     *
     * <p>두 가지를 한다:
     * <ol>
     *   <li><b>펜스가 열린 채 끝나면 닫는다.</b> 이것이 불변식을 지키는 부분이다. 닫는 펜스는
     *       안내 문구 <b>앞</b>에 온다 — 안내는 코드가 아니므로 펜스 밖 평문이어야 하고, 이는
     *       {@code ChunkSplitter} 의 {@code CODE_CONTINUATION_*} 마커가 지키는 규칙과 같다.</li>
     *   <li><b>마지막 줄바꿈까지만 남긴다.</b> 이쪽은 불변식이 아니라 결과물의 문제다 —
     *       {@code "```java"} 한가운데서 자르면 {@code "```ja"} 라는 잘린 여는 펜스가 남고,
     *       위 1번이 거기에 닫는 펜스를 붙여 <b>언어 태그가 깨진 빈 코드 블록</b>을 만든다.
     *       표 행이 반쪽만 남는 것도 같은 종류의 문제다. 잃는 것은 이미 잘리는 중인 답변의
     *       마지막 한 줄뿐이다.</li>
     * </ol>
     *
     * <p><b>줄 중간 펜스는 여기서 만들어지지 않는다.</b> {@code fenceLineCount} 와
     * {@code fenceMarkCount} 가 어긋나는 그 조건은 한 줄 안에 앞선 내용과 {@code ```} 이 함께
     * 있어야 성립하는데, 절단은 뒤에서 문자를 <b>덜어낼</b> 뿐이라 없던 것을 만들 수 없다 —
     * 원문에 이미 있었다면 그것은 이 메서드가 고칠 대상이 아니다. 절단이 실제로 일으킬 수 있는
     * 결함은 <b>홀수 펜스</b> 하나뿐이고, 그것이 1번이 막는 것이다.
     *
     * <p>펜스 세기는 {@link MarkdownCorrectionService#fenceLineCount} 를 그대로 쓴다. 같은 것을
     * 여기서 다시 구현하면 두 관점이 갈라질 수 있고, 그러면 이 수리가 저쪽 가드를 통과시키지
     * 못한다.
     *
     * <p>줄바꿈이 하나도 없는 거대한 한 줄이면 자를 줄 경계가 없다. 그때는 잘린 끝에 남은 백틱
     * 연속만 걷어낸다 — 문장 중간에 매달린 {@code "``"} 는 원문에도 없던 것이라 남길 이유가 없고,
     * 그 자리에서 잘린 백틱이 {@code ```} 를 이루면 개수만 늘리기 때문이다.
     *
     * <p>결과 길이는 {@link #MAX_ANSWER_LEN} 을 안내 문구만큼 넘는다 — 예전부터 그랬고,
     * 이 상한은 저장·렌더링 보호용이지 정확한 바이트 예산이 아니다.
     */
    private static String truncate(String answer) {
        if (answer == null || answer.length() <= MAX_ANSWER_LEN) return answer;
        String cut = answer.substring(0, MAX_ANSWER_LEN);
        int lastLineBreak = cut.lastIndexOf('\n');
        if (lastLineBreak >= 0) {
            cut = cut.substring(0, lastLineBreak);
        } else {
            while (cut.endsWith("`")) cut = cut.substring(0, cut.length() - 1);
        }
        StringBuilder out = new StringBuilder(cut);
        if (MarkdownCorrectionService.fenceLineCount(cut) % 2 != 0) out.append("\n```");
        return out.append("\n\n").append(TRUNCATION_NOTICE).toString();
    }

    /** 요약 전용 모드의 안전망 — 구현·판단 근거는 {@link SummaryOnlyGuard}에 있다. */
    private static String enforceSummaryOnly(String answer, ResponseMode mode) {
        return SummaryOnlyGuard.apply(answer, mode);
    }
}
