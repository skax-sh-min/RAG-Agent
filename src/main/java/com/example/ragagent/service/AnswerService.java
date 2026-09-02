package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.ingestion.MarkdownNoiseNormalizer;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.model.ResponseMode;
import com.example.ragagent.model.SourceRef;
import com.example.ragagent.exception.LlmContextOverflowException;
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
import java.util.Set;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntFunction;
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
    /** 검증 호출이 스스로 거는 출력 예약. {@code RetrievalService} 의 컨텍스트 여유 판단이
     *  같은 값을 써야 해서 package-private 이다 — 두 곳이 다른 예약을 믿으면 여유 계산이 틀린다. */
    static final int MAX_EVAL_OUTPUT_TOKENS = 2_048;

    /**
     * 답변 프롬프트의 <b>섹션 머리말과 구분선</b>({@code [이전 대화]}·{@code [검색된 문서]}·
     * {@code [질문]}·문서 사이 {@code ---}·{@code [파일명 | p.N]} 라벨)이 차지하는 몫.
     *
     * <p>시스템 프롬프트는 여기 포함되지 않는다 — 그쪽은 {@code fitToBudget()} 이 실제로 세기
     * 때문이다. 예전에는 둘을 합쳐 2,000 으로 뭉뚱그렸는데, 모드·로케일마다 시스템 프롬프트 길이가
     * 크게 다르고(S 는 N 보다 훨씬 짧다) 그 차이만큼 좁은 창에서 문서를 괜히 버렸다.
     *
     * <p>남은 이 값은 라벨·구분선처럼 문서 개수에 비례하는 잔돈이라 상수로 둔다. 실제로는 topK 10 ·
     * 라벨 30자 기준 100 토큰 안쪽이므로, 넉넉한 쪽(200)으로 잡아도 예산에 거의 영향이 없다.
     */
    private static final int ANSWER_PROMPT_SECTION_OVERHEAD_TOKENS = 200;

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
    private PromptBudget budgetFor(AgentState state, String providerName, boolean streaming) {
        int window = contextWindows.tokensOrZero(providerName);
        if (window <= 0) return null;   // 창을 모른다 — 추측으로 근거를 버리지 않는다
        return new PromptBudget(window,
                outputReservation(state.responseMode(), streaming, props.llmSafe().maxTokens()));
    }

    /**
     * 이 호출이 출력에 잡아 둬야 할 자리.
     *
     * <p><b>블로킹은 우리가 보내는 값 그대로다.</b> {@code answerOptions()} 가
     * {@code maxTokens = ResponseMode.maxTokens(설정값)} 을 실어 보내고 서버는 그만큼을 실제로
     * 예약하므로, 예산에서도 같은 값을 빼야 계산이 맞는다.
     *
     * <p><b>스트리밍은 아무것도 보내지 않는다</b>(토큰 단위 UX 때문에 캡을 붙이지 않는다). 그래서
     * 서버가 예약하는 것이 아니라 <b>답변이 자랄 자리</b>가 필요할 뿐인데, 예전에는 여기에도 블로킹과
     * 같은 값을 뺐다 — N 기준 7,000 이다. 그건 <b>폭주 방지선</b>이지 예상 분량이 아니다: §6.24 의
     * 실측이 목표를 5,000자로 줘도 3,047자, 10,000자로 줘도 3,187자가 나온다고 기록하고 있다.
     * 쓰지도 않을 자리를 비워 두느라 근거 문서가 밀려났다.
     *
     * <p>그래서 스트리밍은 {@link ResponseMode#minChars()} 로 낮춘다 — 그 모드가 "이만큼은 쓰겠다"고
     * 정한 값이라 예상 분량의 근사로 맞고(N 5,000 은 실측 3,047 대비 60% 여유), 새 상수를 만들지
     * 않으며, 여전히 블로킹 예산을 넘지 않는다({@code min}). 설정 상한이 작으면 그쪽이 이긴다.
     */
    static int outputReservation(ResponseMode mode, boolean streaming, int configuredMaxTokens) {
        int blocking = mode.maxTokens(configuredMaxTokens);
        return streaming ? Math.min(blocking, mode.minChars()) : blocking;
    }

    /** 아직 프로바이더가 정해지지 않은 자리에서 쓰는 추정 — 라우터에게 "지금이라면 누구" 를 묻는다. */
    private String likelyProvider(AgentState state) {
        return llmRouter.findProviderName(TaskType.TEXT, state.routingMode());
    }

    public AgentState execute(AgentState state) {
        return executeBlocking(withBudgetNote(state));
    }

    public AgentState executeStreaming(AgentState state, GraphListener listener) {
        return executeStreamingNormal(withBudgetNote(state), listener);
    }

    /**
     * 축소가 일어났으면 그 사실을 state 에 실어 둔다 — 사용자에게 보여주기 위해서다.
     *
     * <p><b>안내가 필요한 이유는 출처 목록이 줄지 않기 때문이다.</b> 축소는 프롬프트에만 걸리고
     * {@code state.sources()} 는 검색된 전부를 그대로 들고 있어서, 안내가 없으면 사용자는 화면의
     * 출처 10개를 모델이 다 읽고 답한 것으로 믿는다. 실제로는 그중 몇 개만 프롬프트에 들어갔다.
     *
     * <p><b>두 진입점에서만 부른다.</b> 여기서 세워 둔 값은 아래 모든 경로의 {@code toBuilder()}
     * 복사를 타고 그대로 흘러가므로(PROGRESSIVE 업그레이드가 만드는 새 state 포함) 경로마다
     * 심을 필요가 없다. {@link #fitToBudget} 자체는 {@link #buildAnswerPrompt} 가 다시 부르지만
     * 같은 입력에 같은 함수라 결과가 갈리지 않는다 — 로직을 복제하는 대신 한 함수를 두 번 부른다.
     */
    private AgentState withBudgetNote(AgentState state) {
        return withBudgetNote(state, 0);
    }

    /**
     * @param shrinkLevel 실제로 성공한 시도의 축소 단계. 초과 재시도가 있었다면 <b>호출이 끝난 뒤</b>
     *                    그 단계로 다시 불러야 한다 — 안 그러면 화면은 0단계 기준으로 안내하고 모델은
     *                    그보다 적은 문서를 봤다는 상태가 남는다. 이미 표시된 출처를 다시 표시하는 것은
     *                    무해하므로(chunkId 기준, 멱등) 덧칠하면 된다.
     */
    private AgentState withBudgetNote(AgentState state, int shrinkLevel) {
        // 안내는 사용자가 실제로 겪는 경로 기준이어야 한다 — 채팅의 유일한 전송 경로는 스트리밍이다.
        Fitted fitted = fitToBudget(state, likelyProvider(state), true, shrinkLevel);
        if (fitted.note() == null) return state;
        return state.toBuilder()
                .budgetNote(fitted.note())
                .sources(markExcludedSources(state.sources(), fitted.docs()))
                .build();
    }

    /**
     * 프롬프트에 실리지 못한 출처에 표시를 남긴다 — 턴 단위 안내가 <b>몇 개</b>인지 말한다면 이쪽은
     * <b>어느 것</b>인지를 말한다.
     *
     * <p>위치가 아니라 {@code chunkId} 로 맞춘다. 출처 목록은 나중에 응답 참여도 기준으로 다시
     * 정렬되므로({@code SourceRef.DISPLAY_ORDER}) "뒤에서 N개"라는 위치 규칙은 화면에 닿기 전에
     * 무너진다. chunk id 를 모르는 출처(구 기록·폴백 경로)는 건드리지 않는다 — 확인할 수 없는 것을
     * 빠졌다고 표시하면 그 표시 자체를 믿을 수 없게 된다.
     */
    private static List<SourceRef> markExcludedSources(List<SourceRef> sources, List<Document> keptDocs) {
        if (sources.isEmpty()) return sources;
        Set<String> keptIds = keptDocs.stream()
                .map(Document::getId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        return sources.stream()
                .map(s -> (s.chunkId() != null && !keptIds.contains(s.chunkId()))
                        ? s.markPromptExcluded() : s)
                .toList();
    }

    // ── Blocking paths ──────────────────────────────────────────────────────

    private AgentState executeBlocking(AgentState state) {
        final AgentState requested = state;   // 아래에서 state 를 다시 대입하므로 람다는 이쪽을 본다
        String systemPrompt = answerSystemPrompt(state.locale(), state.responseMode());
        ChatOptions options = answerOptions(state);
        Shrunk<LlmRouter.LlmResult> attempt = withShrinkRetry(requested, "ANSWER", level -> {
            // 블로킹 — answerOptions() 가 maxTokens 를 실어 보내므로 그만큼 실제로 예약된다.
            String userPrompt = buildAnswerPrompt(requested, likelyProvider(requested), false, level);
            return llmRouter.executeGatedWithUsage(TaskType.TEXT, requested.routingMode(),
                    model -> model.call(buildPrompt(systemPrompt, userPrompt, options)));
        });
        LlmRouter.LlmResult result = attempt.value();
        String answer = truncate(enforceSummaryOnly(result.text() == null ? "" : result.text(), state.responseMode()));
        state = withBudgetNote(state, attempt.level()).toBuilder()
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
        Shrunk<String> attempt;
        try (var permit = llmRouter.acquirePermit(provider)) {
            attempt = streamAnswer(provider, state, systemPrompt, listener::onToken);
        }
        state = withBudgetNote(state, attempt.level());
        String answer = truncate(enforceSummaryOnly(attempt.value(), state.responseMode()));
        // streaming has no ChatResponse to read real usage from — record an approximate
        // (chars/4) usage entry so /llm-usage isn't blind to the entire streaming chat path, and
        // reflect the same estimate in the per-turn total so the chat UI isn't stuck at 0/0.
        String promptText = systemPrompt + buildAnswerPrompt(state, provider.name(), true, attempt.level());
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
        int shrinkLevel;
        if (listener != null) {
            Shrunk<String> attempt;
            try (var permit = llmRouter.acquirePermit(premiumProvider)) {
                attempt = streamAnswer(premiumProvider, state, systemPrompt, listener::onToken);
            }
            premiumAnswer = attempt.value();
            shrinkLevel = attempt.level();
            String promptText = systemPrompt
                    + buildAnswerPrompt(state, premiumProvider.name(), true, shrinkLevel);
            llmRouter.recordApproxUsage(premiumProvider.name(), promptText, premiumAnswer);
            inputTokens = (int) LlmRouter.approxTokens(promptText);
            outputTokens = (int) LlmRouter.approxTokens(premiumAnswer);
        } else {
            Shrunk<LlmRouter.LlmResult> attempt = withShrinkRetry(state, "ANSWER-PREMIUM", level -> {
                String userPrompt = buildAnswerPrompt(state, premiumProvider.name(), false, level);
                return llmRouter.executeGatedWithUsage(
                        TaskType.TEXT, RoutingMode.QUALITY_FIRST,
                        model -> model.call(buildPrompt(systemPrompt, userPrompt, answerOptions(state))));
            });
            premiumAnswer = attempt.value().text();
            shrinkLevel = attempt.level();
            inputTokens = attempt.value().inputTokens();
            outputTokens = attempt.value().outputTokens();
        }
        // 업그레이드 답변이 축소된 프롬프트로 만들어졌다면 그 사실도 사용자에게 가야 한다 —
        // 화면에 남는 것은 이 마지막 답변이다.
        if (shrinkLevel > 0) {
            resultState = withBudgetNote(resultState, shrinkLevel);
        }
        return resultState.toBuilder()
                          .answer(truncate(premiumAnswer))
                          .usedProvider(premiumProvider.name())
                          .premiumUpgraded(premiumProvider.name())
                          .accumulateTokens(inputTokens, outputTokens)
                          .needsRetry(false)
                          .build();
    }

    // ── 컨텍스트 초과 후 축소 재시도 (§6.26-9) ───────────────────────────────

    /**
     * 초과 한 번에 프롬프트를 <b>절반</b>으로 줄인다 — 하나씩이 아니라 절반씩이라 왕복이 log(n) 이다.
     * 이 상수가 그 단계 수의 상한이라 최악의 왕복은 4회(0~3단계)이고, topK=10 이면 10→5→3→2 다.
     *
     * <p><b>왜 이만큼이면 충분한가.</b> 사전 예산({@code fitToBudget})이 이미 창에 맞춰 줄여 놓았고,
     * 여기까지 오는 것은 그 계산이 <b>빗나갔다</b>는 뜻이다. 빗나가는 폭은 {@code TokenEstimator} 의
     * 추정 오차이고, {@code TokenEstimateCalibration} 이 정상으로 보는 범위가 0.75~1.35 다 — 5배를
     * 줄이고도 안 들어간다면 그건 추정 오차가 아니라 설정 문제이고, 그때는 {@code RAG-LLM-003} 이
     * 무엇을 고쳐야 하는지 이름을 대 주는 편이 낫다. 더 줄여 봐야 근거가 한 조각 남은 답변이 된다.
     */
    private static final int MAX_SHRINK_LEVELS = 3;

    /** 성공한 시도의 결과와 <b>그때의 축소 단계</b>. 단계가 있어야 사용자 안내를 다시 세울 수 있다. */
    private record Shrunk<T>(T value, int level) {}

    private <T> Shrunk<T> withShrinkRetry(AgentState state, String tag, IntFunction<T> attempt) {
        return withShrinkRetry(state, tag, () -> true, attempt);
    }

    /**
     * 컨텍스트 초과로 실패하면 프롬프트를 절반씩 줄여 다시 시도한다.
     *
     * <p><b>사전 예산이 있는데도 이것이 필요한 이유.</b> {@code fitToBudget} 은 {@code TokenEstimator}
     * 의 <b>추정</b> 위에 서 있고(창을 아예 모르면 아무것도 자르지 않는다), 추정이 빗나가면 그대로
     * 초과가 난다. 그때 사용자가 받는 것은 답변이 아니라 오류다 — 문서 몇 개만 덜어내면 답할 수
     * 있었는데도.
     *
     * <p><b>줄이는 것은 프롬프트지 요청이 아니다.</b> 라우터는 호출을 불투명한 클로저로 받아 프롬프트
     * 안을 못 보므로 이 재시도는 라우터가 아니라 <b>프롬프트를 조립하는 여기</b>에만 있을 수 있다.
     * 라우터의 프로바이더 순회(다른 프로바이더로 넘어가며 재시도)와는 층이 다르고 서로 방해하지
     * 않는다 — 저쪽이 "다른 곳에 물어본다"면 이쪽은 "더 작게 물어본다"다.
     *
     * <p><b>축소는 사용자에게 보여야 한다.</b> 성공한 단계를 함께 돌려주는 이유가 그것이다 —
     * 호출부가 {@link #withBudgetNote} 를 그 단계로 다시 세워 안내 문구와 출처의 {@code 미사용}
     * 표시를 실제로 보낸 프롬프트와 맞춘다. 안 그러면 화면은 0단계 기준으로 말하고 모델은 3단계를
     * 봤다는 상태가 된다.
     *
     * @param canRetry 재시도해도 되는 상황인지 — 스트리밍은 <b>토큰이 하나라도 나갔으면</b> 안 된다
     *                 (화면에 앞부분이 두 번 찍힌다). 초과는 생성 전에 거절되므로 실제로는 늘 참이지만,
     *                 그 가정이 깨졌을 때 사용자가 보는 것이 중복 텍스트라 조건으로 못 박아 둔다.
     */
    private <T> Shrunk<T> withShrinkRetry(AgentState state, String tag,
                                          BooleanSupplier canRetry, IntFunction<T> attempt) {
        int maxLevel = maxShrinkLevel(state);
        for (int level = 0; ; level++) {
            try {
                return new Shrunk<>(attempt.apply(level), level);
            } catch (RuntimeException e) {
                if (level >= maxLevel || !isContextOverflow(e) || !canRetry.getAsBoolean()) throw e;
                log.warn("[SHRINK] {} 컨텍스트 초과 — 프롬프트를 줄여 다시 시도한다. thread={} "
                         + "단계 {}→{}, 문서 {}→{}개. 사전 예산은 토큰 추정 위에 서 있어 여기까지 오는 "
                         + "일 자체는 정상이지만, 잦다면 app.search-top-k 를 낮추거나(핫, /settings) "
                         + "프로바이더의 컨텍스트를 키우십시오.",
                        tag, state.threadId(), level, level + 1,
                        shrinkDocs(state.retrievedDocs(), level).size(),
                        shrinkDocs(state.retrievedDocs(), level + 1).size());
            }
        }
    }

    /**
     * 블로킹 경로는 라우터가 {@link LlmContextOverflowException} 으로 바꿔 던지고, 스트리밍 경로는
     * {@code OpenAiApi} 를 직접 호출해 <b>날것</b>이 올라온다 — 둘 다 알아봐야 한다.
     */
    private static boolean isContextOverflow(Throwable t) {
        return t instanceof LlmContextOverflowException || LlmRouter.isContextOverflow(t);
    }

    /**
     * 이 턴에서 의미 있게 줄일 수 있는 단계 수.
     *
     * <p>문서가 1개뿐이면 반으로 나눠도 그대로라 <b>같은 요청을 반복</b>하게 된다 — 그때는 이력이
     * 남아 있을 때만 한 단계를 준다. 상한을 두지 않으면 결정적으로 실패하는 요청에 왕복만 쓴다.
     */
    private static int maxShrinkLevel(AgentState state) {
        int byDocs = ceilLog2(state.retrievedDocs().size());
        String history = state.conversationHistory();
        boolean hasHistory = history != null && !history.isBlank();
        return Math.min(MAX_SHRINK_LEVELS, hasHistory ? byDocs + 1 : byDocs);
    }

    /** 1 이 될 때까지 필요한 반감 횟수. {@code n <= 1} 이면 0 — 더 줄일 것이 없다. */
    private static int ceilLog2(int n) {
        return n <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(n - 1);
    }

    /**
     * 단계마다 문서 수를 반으로 — 뒤(최저 관련도)부터 버린다. {@code fitToBudget} 의 순서 규칙과 같다.
     * <b>최상위 문서는 남는다</b>: 다 버리면 프롬프트가 "문서를 찾을 수 없습니다"가 되어, 초과를
     * 피하려다 검색이 성공한 질문에 모른다고 답하게 된다.
     */
    private static List<Document> shrinkDocs(List<Document> docs, int level) {
        if (level <= 0 || docs.size() <= 1) return docs;
        int keep = Math.max(1, (docs.size() + (1 << level) - 1) >> level);
        return docs.subList(0, keep);
    }

    /**
     * 이력은 <b>문서를 다 줄인 뒤에야</b> 손댄다 — 이 앱이 축소에서 지키는 순서(문서 → 이력)를 재시도
     * 에서도 그대로 따른다. 문서가 이미 1개면 1단계부터 이력이 반씩 줄고, topK 가 크면 상한(3단계)
     * 안에서는 이력에 닿지 않는다. 그래도 되는 이유는 이력이 설정으로 이미 묶여 있고
     * ({@code MemoryService} 가 {@code max-tokens}의 절반) 창을 밀어붙이는 쪽은 문서라서다.
     */
    private static String shrinkHistory(String history, int level, int docCount) {
        int shift = level - ceilLog2(docCount);
        if (shift <= 0 || history == null || history.isBlank()) return history;
        return trimHistory(history, TokenEstimator.estimate(history) >> shift);
    }

    /** Shared raw Prompt construction for the non-fluent LlmRouter call sites (evaluation, PROGRESSIVE). */
    private static Prompt buildPrompt(String systemPrompt, String userPrompt, ChatOptions options) {
        List<org.springframework.ai.chat.messages.Message> messages =
                List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt));
        return options == null ? new Prompt(messages) : new Prompt(messages, options);
    }

    // ── Stream helpers ──────────────────────────────────────────────────────

    /**
     * <b>토큰이 하나라도 나갔으면 재시도하지 않는다</b> — 화면에 앞부분이 두 번 찍히기 때문이다.
     * 컨텍스트 초과는 생성이 시작되기 전에 거절되므로 실제로 그런 일은 없지만, 그 가정이 깨졌을 때
     * 사용자가 보는 것이 중복 텍스트라 조건으로 못 박는다. 나간 것이 없으면 {@code full} 도 비어
     * 있어 되감을 것이 없다.
     */
    private Shrunk<String> streamAnswer(LlmProvider provider, AgentState state,
                                        String systemPrompt, Consumer<String> tokenSink) {
        StringBuilder full = new StringBuilder();
        boolean[] emitted = {false};
        Shrunk<Void> attempt = withShrinkRetry(state, "ANSWER-STREAM", () -> !emitted[0], level -> {
            callOrStream(provider, state, systemPrompt, level,
                    t -> { emitted[0] = true; tokenSink.accept(t); full.append(t); });
            return null;
        });
        return new Shrunk<>(full.toString(), attempt.level());
    }

    private void callOrStream(LlmProvider provider, AgentState state, String systemPrompt,
                              int shrinkLevel, Consumer<String> tokenSink) {
        if (provider.stream()) {
            // Bypass OpenAiChatModel.internalStream() which buffers ALL chunks via buffer(int,int)
            // before emitting, defeating real-time token delivery to the browser.
            streamDirect(provider, systemPrompt,
                    buildAnswerPrompt(state, provider.name(), true, shrinkLevel), tokenSink,
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
                    // stream=false 경로는 answerOptions() 를 붙여 보내므로 예약이 실제로 걸린다.
                    .user(buildAnswerPrompt(state, provider.name(), false, shrinkLevel))
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
            EvalExcerpts[] used = new EvalExcerpts[1];
            LlmRouter.LlmResult result = withShrinkRetry(state, "EVAL", level -> {
                EvalExcerpts excerpts = evalExcerptsFor(state, answer, systemPrompt,
                        evalConverter.getFormat(), level);
                used[0] = excerpts;
                String evalPrompt = buildEvalPrompt(state, answer, excerpts.text(), evalConverter.getFormat());
                return llmRouter.executeGatedWithUsage(TaskType.TEXT, state.routingMode(),
                        model -> model.call(buildPrompt(systemPrompt, evalPrompt, evalOptions())));
            }).value();
            EvalExcerpts excerpts = used[0];
            if (isEmptyVerdict(result)) {
                logEmptyVerdict("EVAL", state, result);
                return withoutVerdict(state.toBuilder()
                        .accumulateTokens(result.inputTokens(), result.outputTokens()).build());
            }
            EvalOutput out = evalConverter.convert(result.text());
            boolean grounded = !docsPresent || out.grounded();
            // 근거 판정만 비운다 — sufficient("요청에 답했는가")는 발췌 완전성에 거의 의존하지 않고,
            // 그것까지 버리면 정당한 재시도·PROGRESSIVE 업그레이드 신호가 함께 사라진다.
            boolean groundedUnreliable = unreliableNegative(grounded, excerpts, state);
            Boolean groundedVerdict = groundedUnreliable ? null : grounded;
            boolean passed = out.sufficient() && (groundedUnreliable || grounded);
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
                    .grounded(groundedVerdict)
                    .evalReason(reason)
                    .envNote(envNote)
                    .usedDocIndices(out.usedDocs())
                    .excludedDocIds(evictionsFor(state, out.sufficient(), out.usedDocs(), excerpts))
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
            EvalExcerpts[] used = new EvalExcerpts[1];
            LlmRouter.LlmResult result = withShrinkRetry(state, "EVAL-C", level -> {
                EvalExcerpts excerpts = evalExcerptsFor(state, answer, systemPrompt,
                        creativeEvalConverter.getFormat(), level);
                used[0] = excerpts;
                String evalPrompt = buildEvalPrompt(state, answer, excerpts.text(),
                        creativeEvalConverter.getFormat());
                return llmRouter.executeGatedWithUsage(TaskType.TEXT, state.routingMode(),
                        model -> model.call(buildPrompt(systemPrompt, evalPrompt, evalOptions())));
            }).value();
            EvalExcerpts excerpts = used[0];
            if (isEmptyVerdict(result)) {
                logEmptyVerdict("EVAL-C", state, result);
                return withoutVerdict(state.toBuilder()
                        .accumulateTokens(result.inputTokens(), result.outputTokens()).build());
            }
            CreativeEvalOutput out = creativeEvalConverter.convert(result.text());
            boolean apiGrounded = !docsPresent || out.apiGrounded();
            // 표준 검증과 같은 가드 — C 의 apiGrounded 도 "발췌에서 못 찾았다"는 부정 판정이라,
            // 발췌가 빠진 상태에서 나온 것이면 믿을 수 없다. 여기서도 sufficient 는 살린다.
            boolean groundedUnreliable = unreliableNegative(apiGrounded, excerpts, state);
            Boolean groundedVerdict = groundedUnreliable ? null : apiGrounded;
            boolean passed = out.sufficient() && (groundedUnreliable || apiGrounded);
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
                    .grounded(groundedVerdict)
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
    /**
     * 축소된 근거로 내려진 <b>부정 판정</b>을 판정으로 인정할지 — 인정하면 안 된다.
     *
     * <p><b>비대칭이 핵심이다.</b> 발췌 일부가 빠진 채 나온 결과라도
     * <ul>
     *   <li>{@code grounded=true} 는 <b>믿을 수 있다</b> — 본 것만으로 근거를 찾았다는 뜻이고,
     *       문서를 더 보여준다고 그 근거가 사라지지 않는다.</li>
     *   <li>{@code grounded=false} 는 <b>믿을 수 없다</b> — "못 찾았다"인데, 빠진 그 문서에 있었을
     *       수 있다. 이것이 {@code .limit(5)} 사고의 정확한 모양이었다(#6~8 문서에만 있는 포트·경로를
     *       올바로 인용한 답변이 근거 없음 판정을 받음).</li>
     * </ul>
     *
     * <p><b>검증 호출이 이 앱에서 가장 큰 요청</b>이라 이 상황이 실제로 생긴다. 답변 호출과 달리
     * 검색 문서 위에 <b>답변 전문</b>이 통째로 얹히므로, 답변이 길어질수록 발췌에 남는 자리가
     * 줄어든다 — 즉 <b>답변이 길수록 검증이 보는 근거가 좁아진다</b>. 답변 호출은 통과했는데 검증만
     * 좁아지는 구간이 존재한다.
     *
     * <p>그래서 부정 판정이면 판정 자체를 버린다({@code grounded=null} = 판정 없음). 이 앱이 이미
     * 가진 상태라 새 UI가 필요 없고, 그 의미도 정확하다 — 배지도 재시도도 없다. <b>재시도를 걸지
     * 않는 것이 중요하다</b>: 재시도해도 답변 길이와 창은 그대로라 같은 축소가 반복되고, 재시도
     * 예산만 태운 뒤 미검증 배지가 붙는다.
     *
     * <p>거짓 통과를 만들지도 않는다 — {@code grounded=true} 를 위조하는 것이 아니라 판정을
     * 비우는 것이다('판정 없음' 규약 참고).
     *
     * <p><b>비우는 것은 {@code grounded} 뿐이다.</b> 같은 호출이 낸 {@code sufficient}("요청한 것에
     * 답했는가")는 발췌 완전성에 거의 의존하지 않는다 — 답변과 질문을 견주는 판단이다. 그것까지
     * 버리면 정당한 재시도와 PROGRESSIVE 업그레이드 신호가 함께 사라지는데, 업그레이드는 오히려
     * <b>창이 더 큰 프로바이더</b>로 가므로 이 상황에서 도움이 되는 쪽이다.
     */
    /**
     * 다음 재검색에서 밀어낼 청크를 고른다 — {@code sufficient=false} 재시도 전용.
     *
     * <p><b>여기서 계산하는 이유는 세 입력이 전부 이 자리에만 모여 있기 때문이다</b>: 직전 문서
     * 목록({@code state.retrievedDocs()}), 평가가 보고한 사용 문서({@code usedDocs}), 그리고 발췌가
     * 잘렸는지({@code excerpts.trimmed()}). 마지막 것은 {@code EvalExcerpts} 안에만 있어서
     * {@code RetrievalService} 로 옮기려면 그 사실을 상태에 실어 날라야 한다 — 판단을 데이터가 있는
     * 곳으로 가져오는 편이 데이터를 판단이 있는 곳으로 옮기는 것보다 낫다.
     *
     * <p>{@code grounded=false} 에는 적용하지 않는다: 그건 답변이 문서 <b>밖으로</b> 나간 경우라
     * 근거를 빼는 것이 방향상 반대다. 이 메서드가 {@code sufficient} 만 보는 이유다.
     *
     * <p>누적한다 — 직전에 밀어낸 청크는 다음 검색에서 다시 올라오면 안 된다. 그러지 않으면 교체가
     * 앞으로 나아가지 못하고 같은 자리를 오간다.
     */
    private static List<String> evictionsFor(AgentState state, boolean sufficient,
                                             List<Integer> usedDocs, EvalExcerpts excerpts) {
        if (sufficient) return state.excludedDocIds();
        Set<String> fresh = RetrievalEviction.select(state.retrievedDocs(), usedDocs, excerpts.trimmed());
        if (fresh.isEmpty()) return state.excludedDocIds();
        List<String> merged = new ArrayList<>(state.excludedDocIds());
        fresh.stream().filter(id -> !merged.contains(id)).forEach(merged::add);
        log.info("[EVAL] 재시도에서 밀어낼 청크 {}개 (근거 미사용 + 하위 순위) thread={}",
                fresh.size(), state.threadId());
        return merged;
    }

    private static boolean unreliableNegative(boolean grounded, EvalExcerpts excerpts, AgentState state) {
        if (grounded || !excerpts.trimmed()) return false;
        log.warn("[EVAL] 근거 없음 판정을 버린다(판정 없음으로 기록) — 검증이 답변보다 적은 문서를 봤다: "
                 + "{}개 중 {}개. thread={} 답변 {}자. 답변이 길수록 발췌 자리가 줄어드는 구조라, "
                 + "이 판정은 문서가 빠져서 나온 것일 수 있다.",
                excerpts.total(), excerpts.included(), state.threadId(),
                state.answer() == null ? 0 : state.answer().length());
        return true;
    }

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

    /**
     * 두 검증 경로가 공유하는 발췌 조립 — 축소 단계까지 포함해서.
     *
     * <p><b>{@code total} 은 언제나 원본 문서 수다.</b> 재시도가 목록을 미리 줄여도
     * {@link EvalExcerpts#trimmed()} 는 여전히 참이 되고, 그래서 그 판정을
     * {@link #unreliableNegative} 가 "줄어든 근거로 내려진 부정 판정"으로 걸러 낸다 — 재시도로 답을
     * 받아 내는 것과 그 답을 곧이곧대로 믿는 것은 다른 문제다.
     */
    private EvalExcerpts evalExcerptsFor(AgentState state, String answer, String systemPrompt,
                                         String schema, int shrinkLevel) {
        return buildEvalExcerpts(shrinkDocs(state.retrievedDocs(), shrinkLevel),
                evalExcerptTokenBudget(state, systemPrompt, answer, schema),
                state.retrievedDocs().size());
    }

    /** 두 검증 경로가 공유하는 사용자 메시지 — 차이는 시스템 프롬프트와 {@code format}(응답 스키마)뿐이다. */
    private static String buildEvalPrompt(AgentState state, String answer, String excerpts, String format) {
        return "[질문]\n%s\n\n[답변]\n%s\n\n[문서 발췌]\n%s\n\n%s"
                .formatted(PromptInjectionGuard.wrap(state.question()), answer, excerpts, format);
    }

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
        int window = contextWindows.tokensOrZero(likelyProvider(state));
        if (window <= 0) return 0;   // 창 모름 → 글자 상한만 적용(예전 동작 그대로)
        long fixed = TokenEstimator.estimate(systemPrompt)
                + TokenEstimator.estimate(answer)
                + TokenEstimator.estimate(state.question())
                + TokenEstimator.estimate(schema);
        return Math.max(0, new PromptBudget(window, MAX_EVAL_OUTPUT_TOKENS).inputBudget() - fixed);
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
     * <p><b>같은 이유로 "답변에 실제로 쓰인 문서만 검증에 넣기" 는 하면 안 된다.</b> 어느 문서가
     * 쓰였는지는 이 호출이 <i>끝나야</i> 알 수 있고({@code usedDocs} 는 이 호출의 출력이며
     * {@code AnswerAttribution} 은 FINALIZE 에서 돈다), 설령 미리 안다 해도 <b>답변을 닮은 문서만
     * 골라 보여주고 채점하는 것</b>이 된다. 무엇보다 근거는 답변이 인용한 문서에만 있는 것이 아니다 —
     * 위 사고가 정확히 그 모양이었다.
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
     *
     * @param tokenBudget 창에서 파생된 발췌 토큰 예산. {@code 0} 이하면 적용하지 않는다(창 모름).
     *                    {@link #MAX_EVAL_EXCERPT_CHARS} 글자 상한은 <b>그와 무관하게 늘 걸린다</b> —
     *                    그쪽은 과대 설정을 막는 절대 안전판이고, 이쪽은 이 프로바이더에 실제로 들어가는
     *                    양이라 성격이 다르다. 둘 중 먼저 걸리는 쪽에서 멈춘다.
     */
    /**
     * @param text     프롬프트에 실린 발췌 블록
     * @param included 실제로 실린 문서 수
     * @param total    답변이 봤던 문서 수. {@code included < total} 이면 <b>검증이 답변보다 적은
     *                 근거를 보고 판정했다</b>는 뜻이고, 그 사실이 판정의 신뢰도를 가른다
     *                 ({@link #evaluate} 의 축소 가드 참고)
     */
    private record EvalExcerpts(String text, int included, int total) {
        boolean trimmed() { return included < total; }
    }

    private static EvalExcerpts buildEvalExcerpts(List<Document> docs, long tokenBudget) {
        return buildEvalExcerpts(docs, tokenBudget, docs.size());
    }

    /**
     * @param totalAvailable 답변이 봤던 <b>원본</b> 문서 수. 축소 재시도(§6.26-9)가 {@code docs} 를
     *                       미리 줄여 놓았다면 {@code docs.size()} 로는 그 사실이 보이지 않는다 —
     *                       {@link EvalExcerpts#trimmed()} 가 {@code false} 가 되어
     *                       {@link #unreliableNegative} 가 <b>줄어든 근거로 내려진 부정 판정을 그대로
     *                       믿게 된다</b>. 이 앱이 계속 되풀이해 온 실수({@code .limit(5)})의 정확한
     *                       모양이라 재시도가 그것을 되살리게 둘 수 없다.
     */
    private static EvalExcerpts buildEvalExcerpts(List<Document> docs, long tokenBudget,
                                                  int totalAvailable) {
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
        if (included < totalAvailable) {
            log.warn("[EVAL] 발췌 한도(글자 {} / 토큰 {})로 {}개 중 {}개만 검증에 사용 — 하위 순위 문서 제외. "
                     + "검증 창이 답변 창보다 좁아지면 6~8번째 문서에만 있는 값을 정확히 인용한 답변이 "
                     + "근거 없음으로 판정될 수 있으므로, app.search-top-k / app.chunk-size 를 낮추거나 "
                     + "프로바이더의 컨텍스트를 키우는 편이 낫다.",
                    MAX_EVAL_EXCERPT_CHARS, tokenBudget > 0 ? tokenBudget : "미적용",
                    totalAvailable, included);
        }
        return new EvalExcerpts(sb.toString(), included, totalAvailable);
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

    /**
     * @param providerName 이 프롬프트를 <b>실제로 받을</b> 프로바이더. 예산이 그 프로바이더의 창에서
     *                     나오므로 호출부마다 다르다 — 특히 PROGRESSIVE 업그레이드는 창이 더 큰
     *                     PREMIUM 으로 가는데, 예전에는 이 자리에서도 원래(로컬) 프로바이더 기준으로
     *                     예산을 잡아 <b>업그레이드 답변이 필요 없이 적은 문서로 만들어졌다</b>.
     *                     그래서 이 메서드는 호출마다 예산을 다시 계산한다 — 중복 계산이 아니라
     *                     호출부마다 답이 달라야 하는 값이다.
     */
    private String buildAnswerPrompt(AgentState state, String providerName, boolean streaming) {
        return buildAnswerPrompt(state, providerName, streaming, 0);
    }

    /** @param shrinkLevel 컨텍스트 초과 후 재시도 단계 — {@link #fitToBudget} 참조. */
    private String buildAnswerPrompt(AgentState state, String providerName, boolean streaming,
                                     int shrinkLevel) {
        StringBuilder sb = new StringBuilder();

        // 예산에 맞춰 미리 덜어낸다. 호출 후에 초과 오류를 받고 재시도하는 것보다 왕복이 0회 싸고,
        // 무엇을 버릴지도 여기서만 알 수 있다(라우터는 프롬프트 안을 못 본다). 창을 모르면
        // fitToBudget 이 원본을 그대로 돌려주므로 예전과 동작이 같다.
        Fitted fitted = fitToBudget(state, providerName, streaming, shrinkLevel);

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

        appendRetryFeedback(sb, state);

        sb.append("[질문]\n").append(PromptInjectionGuard.wrap(state.question()));
        return sb.toString();
    }

    /**
     * 직전 시도가 왜 반려됐는지를 재시도 프롬프트에 실어 준다.
     *
     * <p><b>이게 없으면 재시도가 거의 무의미하다.</b> 검색 escalation 은 최종 컷을 재시도당 1개
     * 늘릴 뿐이고, 질문·시스템 프롬프트·대화 이력은 그대로이며, 일반/RAG 온도는 {@code [0, 0.3]}
     * 로 조여 있고 기본값이 {@code 0.0} 이다 — 같은 입력을 결정적으로 다시 넣고 같은 평가자에게
     * 같은 질문을 하는 셈이라 같은 답과 같은 판정이 나오기 쉽다. 프롬프트가 실제로 달라지는 것이
     * 재시도가 다른 결과를 낼 수 있는 유일한 경로다.
     *
     * <p>사유는 이미 계산돼 있다 — {@code evalReason} 은 평가 LLM 이 낸 한 문장이고 로그·SSE·DB 로
     * 이미 나가며 재시도 state 까지 살아남는다. 정작 그걸 볼 필요가 있는 모델에게만 안 갔다.
     *
     * <p><b>지시가 아니라 관찰로 넣는다.</b> "이 지적을 만족시켜라"로 쓰면 모델이 지적을 채우려고
     * 문서에 없는 내용을 지어내고, 그러면 고치려던 지표(근거)가 더 나빠진다. 그래서 문구는 "다시
     * 확인하라 / 없으면 없다고 말하라"이고, 이 블록을 답변에 언급하지 말라는 제약이 같은 블록 안에
     * 붙는다(시스템 프롬프트 6개를 건드리는 대신 — 블록이 있을 때만 그 제약도 존재하면 된다).
     */
    private static void appendRetryFeedback(StringBuilder sb, AgentState state) {
        if (state.retryCount() <= 0) return;
        String reason = state.evalReason();
        if (reason == null || reason.isBlank()) return;
        sb.append("[직전 시도 메모]\n")
          .append("직전 답변은 다음 이유로 반려됐다: ").append(reason).append('\n')
          .append("이번에는 그 부분을 문서에서 다시 확인하고 답하라. ")
          .append("문서에 없으면 지어내지 말고 없다고 밝혀라. ")
          .append("이 메모 자체는 내부 기록이므로 답변에 언급하지 마라.\n\n");
    }

    /** 예산에 맞춘 결과 — 무엇이 얼마나 남았는지. */
    /** @param note 축소가 있었을 때만 채워지는 사용자 안내 문구. 없으면 {@code null}. */
    private record Fitted(List<Document> docs, String history, String note) {}

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
    private Fitted fitToBudget(AgentState state, String providerName, boolean streaming) {
        return fitToBudget(state, providerName, streaming, 0);
    }

    /**
     * @param shrinkLevel 컨텍스트 초과 후 재시도 단계(§6.26-9). {@code 0} 이면 예전 동작 그대로이고,
     *                    단계마다 문서 수가 절반이 된다. <b>예산 계산보다 먼저</b> 적용된다 —
     *                    창을 모르면 예산 단계가 통째로 no-op 이라, 축소를 그쪽에 얹으면 정작
     *                    자동 복구가 가장 필요한 "창 모름" 배포에서 아무 일도 일어나지 않는다.
     */
    private Fitted fitToBudget(AgentState state, String providerName, boolean streaming, int shrinkLevel) {
        List<Document> allDocs = state.retrievedDocs();
        String fullHistory = state.conversationHistory();
        List<Document> docs = shrinkDocs(allDocs, shrinkLevel);
        String history = shrinkHistory(fullHistory, shrinkLevel, allDocs.size());
        PromptBudget budget = budgetFor(state, providerName, streaming);
        if (budget == null) {
            // 창 모름 → 예산 축소는 하지 않는다. 다만 재시도 축소는 창과 무관하게 적용된다.
            return new Fitted(docs, history, noteFor(allDocs.size(), docs.size(), fullHistory, history));
        }

        // 시스템 프롬프트는 실제로 센다 — 모드·로케일마다 길이가 다르고(S 는 N 보다 훨씬 짧다),
        // 넉넉히 잡은 상수로 대신하면 좁은 창에서 그 차이만큼 불필요하게 문서를 버린다.
        long fixedCost = TokenEstimator.estimate(answerSystemPrompt(state.locale(), state.responseMode()))
                + TokenEstimator.estimate(state.question())
                + TokenEstimator.estimate(String.join("\n", state.retrievalWarnings()))
                + ANSWER_PROMPT_SECTION_OVERHEAD_TOKENS;
        long limit = budget.inputBudget();

        List<Document> keptDocs = PromptBudget.fitByPrefix(docs,
                d -> TokenEstimator.estimate(MarkdownNoiseNormalizer.normalize(d.getText())),
                fixedCost + TokenEstimator.estimate(history), limit);

        // 문서를 줄여도 안 들어가면 이력을 오래된 턴부터 덜어낸다.
        long docCost = keptDocs.stream()
                .mapToLong(d -> TokenEstimator.estimate(MarkdownNoiseNormalizer.normalize(d.getText())))
                .sum();
        String keptHistory = trimHistory(history, limit - fixedCost - docCost);

        String note = noteFor(allDocs.size(), keptDocs.size(), fullHistory, keptHistory);
        if (note != null) {
            log.warn("[BUDGET] 컨텍스트 창 {}토큰(출력 예약 {}) → 입력 예산 {}토큰에 맞춰 축소: "
                     + "문서 {}→{}개, 이력 {}→{}자 (재시도 축소 단계 {}). app.search-top-k / "
                     + "app.chunk-size 를 낮추거나 프로바이더의 컨텍스트를 키우면 이 축소가 사라집니다.",
                    budget.contextWindow(), budget.outputReservation(), limit,
                    allDocs.size(), keptDocs.size(), lengthOf(fullHistory), lengthOf(keptHistory),
                    shrinkLevel);
        }
        return new Fitted(keptDocs, keptHistory, note);
    }

    /**
     * 안내 문구 — <b>원본 대비</b> 무엇이 줄었는지. 예산 축소와 재시도 축소가 같은 문장을 쓰는 이유는
     * 사용자에게 그 둘이 같은 사실이기 때문이다: 화면의 출처 목록만큼을 모델이 다 보지는 못했다.
     */
    private static String noteFor(int totalDocs, int keptDocs, String fullHistory, String keptHistory) {
        boolean historyTrimmed = lengthOf(keptHistory) < lengthOf(fullHistory);
        if (keptDocs >= totalDocs && !historyTrimmed) return null;
        return budgetNoteText(totalDocs, keptDocs, historyTrimmed);
    }

    private static int lengthOf(String s) {
        return s == null ? 0 : s.length();
    }

    /**
     * 사용자용 문구 — <b>무엇이 줄었는지</b>를 숫자로 말한다.
     *
     * <p>메시지 번들을 쓰지 않는다: 이 문자열은 {@code conversation_turns} 에 저장돼 나중에 다시
     * 렌더되는데, 그때의 로케일은 답변한 시점의 로케일과 다를 수 있다({@code evalReason}·
     * {@code envNote} 가 모델이 쓴 한국어 문장을 그대로 저장하는 것과 같은 자리다).
     */
    private static String budgetNoteText(int totalDocs, int keptDocs, boolean historyTrimmed) {
        StringBuilder sb = new StringBuilder();
        if (keptDocs < totalDocs) {
            sb.append("컨텍스트 한도로 검색된 문서 %d개 중 %d개만 사용했습니다".formatted(totalDocs, keptDocs));
        }
        if (historyTrimmed) {
            sb.append(sb.isEmpty() ? "컨텍스트 한도로 " : ", ").append("이전 대화 일부를 제외했습니다");
        }
        return sb.append(".").toString();
    }

    /**
     * 오래된 쪽부터 버려 예산에 맞춘다 — 가능하면 <b>턴 경계</b>(빈 줄 + {@code "Q: "})에서.
     *
     * <p>이력은 {@code MemoryService} 의 폴백 경로에서 {@code "Q: …\nA: …"} 를 빈 줄로 이어 붙여
     * 만들어지므로 그 경계가 존재한다. 다만 <b>항상 그 모양인 것은 아니다</b>: §6.10 요약 경로
     * ({@code ConversationSummarizerService.buildContext()})는 요약문 + 최근 턴을 섞어 주고, 답변
     * 본문 안에 빈 줄 다음 {@code "Q: "} 로 시작하는 줄이 있으면(FAQ 형식 답변) 경계가 더 잘게 잡힌다.
     *
     * <p>그래서 경계를 <b>찾지 못했을 때 전부 버리지 않는다</b>. 예전 구현은 분할 결과가 한 덩어리면
     * 그 하나를 지우고 빈 문자열을 반환했다 — 요약 경로의 이력이 통째로 사라지는 것이 정확히 그
     * 경우였고, 예산이 조금 모자랄 뿐인데 대화 맥락 전체를 잃었다. 이제는 줄 경계에서 앞을 잘라
     * <b>가능한 만큼 남긴다</b>. 경계가 잘게 잡히는 쪽(FAQ 답변)은 필요보다 조금 더 버리는 것뿐이라
     * 안전한 방향이다.
     */
    private static String trimHistory(String history, long budget) {
        if (history == null || history.isBlank()) return "";
        if (budget <= 0) return "";
        if (TokenEstimator.estimate(history) <= budget) return history;

        List<String> turns = new ArrayList<>(List.of(history.split("\n\n(?=Q: )")));
        if (turns.size() > 1) {
            while (turns.size() > 1) {
                turns.removeFirst();   // 가장 오래된 턴
                String candidate = String.join("\n\n", turns);
                if (TokenEstimator.estimate(candidate) <= budget) return candidate;
            }
            // 마지막 한 턴만 남았는데도 넘친다 — 아래 줄 단위 절단으로 넘긴다.
            history = turns.getFirst();
        }
        return trimLeadingLines(history, budget);
    }

    /**
     * 턴 경계를 쓸 수 없을 때의 폴백 — 앞에서부터 <b>줄 단위</b>로 덜어낸다.
     *
     * <p>문자 인덱스로 자르지 않는 이유는 문장·코드가 반 토막 나면 남은 이력이 오히려 모델을
     * 헷갈리게 하기 때문이다. 한 줄도 못 남기면 빈 문자열이다(그 한 줄조차 예산을 넘는 경우).
     */
    private static String trimLeadingLines(String text, long budget) {
        List<String> lines = new ArrayList<>(List.of(text.split("\n")));
        while (!lines.isEmpty()) {
            lines.removeFirst();
            String candidate = String.join("\n", lines);
            if (TokenEstimator.estimate(candidate) <= budget) return candidate.strip();
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
