package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.ingestion.MarkdownNoiseNormalizer;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.model.ResponseMode;
import com.example.ragagent.llm.LlmCurlLogger;
import com.example.ragagent.llm.LlmProvider;
import com.example.ragagent.llm.LlmRouter;
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
     * Ceiling on the evidence block sent to the evaluator — see {@link #buildEvalExcerpts}. Sized
     * to match {@link #MAX_ANSWER_LEN}: the evidence may be as long as the longest answer this app
     * will ever store, which the default config (topK 8 × chunk-size 1500 = 12,000) sits under
     * comfortably, so the cap is a safety valve rather than something a normal turn ever meets.
     */
    private static final int MAX_EVAL_EXCERPT_CHARS = 20_000;

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
    private final int maxRetryCount;
    private final BeanOutputConverter<EvalOutput> evalConverter =
            new BeanOutputConverter<>(EvalOutput.class);
    private final BeanOutputConverter<CreativeEvalOutput> creativeEvalConverter =
            new BeanOutputConverter<>(CreativeEvalOutput.class);

    public AnswerService(LlmRouter llmRouter, AppProperties appProperties, MessageSource messageSource) {
        this.llmRouter = llmRouter;
        this.messageSource = messageSource;
        this.props = appProperties;
        this.maxRetryCount = appProperties.maxRetryCount();
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
                        signal, provider.name(), threadId))
                .toIterable()
                .forEach(tokenSink);
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
            String excerpts = buildEvalExcerpts(state.retrievedDocs());
            String evalPrompt = buildEvalPrompt(state, answer, excerpts, evalConverter.getFormat());

            LlmRouter.LlmResult result = llmRouter.executeGatedWithUsage(TaskType.TEXT, state.routingMode(),
                    model -> model.call(buildPrompt(systemPrompt, evalPrompt, evalOptions())));
            EvalOutput out = evalConverter.convert(result.text() == null ? "" : result.text());
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
            log.warn("Evaluation parse failed, treating as sufficient + grounded: {}", e.getMessage());
            return state.toBuilder().needsRetry(false).grounded(true).evalReason(null).envNote(null)
                    .usedDocIndices(List.of()).build();
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
     * <p>파싱 실패 시의 폴백은 {@link #evaluate}와 같다 — 통과 처리. 검증기 자체의 고장이 완성된
     * 답변의 전달을 막아서는 안 된다.
     */
    private AgentState evaluateCreative(AgentState state, String answer, Locale locale) {
        boolean docsPresent = !state.retrievedDocs().isEmpty();
        try {
            String systemPrompt = messageSource.getMessage(state.responseMode().evalPromptKey(), null, locale);
            String excerpts = buildEvalExcerpts(state.retrievedDocs());
            String evalPrompt = buildEvalPrompt(state, answer, excerpts, creativeEvalConverter.getFormat());

            LlmRouter.LlmResult result = llmRouter.executeGatedWithUsage(TaskType.TEXT, state.routingMode(),
                    model -> model.call(buildPrompt(systemPrompt, evalPrompt, evalOptions())));
            CreativeEvalOutput out = creativeEvalConverter.convert(result.text() == null ? "" : result.text());
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
            log.warn("Creative evaluation parse failed, treating as sufficient + grounded: {}", e.getMessage());
            return state.toBuilder().needsRetry(false).grounded(true).evalReason(null).envNote(null)
                    .usedDocIndices(List.of()).inventedSymbols(List.of()).build();
        }
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
     * ({@code app.search-top-k}, default 8) — so an answer correctly citing a value found only in
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
    private static String buildEvalExcerpts(List<Document> docs) {
        StringBuilder sb = new StringBuilder();
        int used = 0;
        int included = 0;
        for (Document d : docs) {
            String text = MarkdownNoiseNormalizer.normalize(d.getText());
            if (included > 0 && used + text.length() > MAX_EVAL_EXCERPT_CHARS) break;
            if (included > 0) sb.append("\n---\n");
            // [D1], [D2], … — the numbering the eval prompt's usedDocs field refers to. 1-based and
            // in prompt order, so an index maps straight back to retrievedDocs.get(n-1). A document
            // dropped by the size cap simply never gets a number, which is why the cap truncates
            // from the tail: indices of the documents that ARE included never shift.
            sb.append("[D").append(included + 1).append("]\n").append(text);
            used += text.length();
            included++;
        }
        if (included < docs.size()) {
            log.warn("[EVAL] 문서 발췌 {}자 상한으로 {}개 중 {}개만 검증에 사용 — 하위 순위 문서 제외 "
                     + "(app.search-top-k / app.chunk-size 가 과도하게 큰지 확인)",
                    MAX_EVAL_EXCERPT_CHARS, docs.size(), included);
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
        return OpenAiChatOptions.builder()
                .temperature(props.llmSafe().temperature())
                .build();
    }

    private String buildAnswerPrompt(AgentState state) {
        StringBuilder sb = new StringBuilder();
        if (!state.conversationHistory().isBlank()) {
            sb.append("[이전 대화]\n").append(state.conversationHistory()).append("\n\n");
        }

        String docsContext = state.retrievedDocs().stream()
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

    /** Absolute ceiling protecting storage/rendering — independent of the response mode, whose
     *  budget is expressed as the call's maxTokens instead of a character cap. */
    private static String truncate(String answer) {
        if (answer == null || answer.length() <= MAX_ANSWER_LEN) return answer;
        return answer.substring(0, MAX_ANSWER_LEN) + "\n\n…(응답이 너무 길어 잘렸습니다)";
    }

    /** 요약 전용 모드의 안전망 — 구현·판단 근거는 {@link SummaryOnlyGuard}에 있다. */
    private static String enforceSummaryOnly(String answer, ResponseMode mode) {
        return SummaryOnlyGuard.apply(answer, mode);
    }
}
