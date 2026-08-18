package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.ingestion.CuratedTextUtils;
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
import java.util.Arrays;
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

    private final LlmRouter llmRouter;
    private final MessageSource messageSource;
    private final AppProperties props;
    private final int maxRetryCount;
    private final BeanOutputConverter<EvalOutput> evalConverter =
            new BeanOutputConverter<>(EvalOutput.class);

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
        String systemPrompt = answerSystemPrompt(state.locale());
        String userPrompt = buildAnswerPrompt(state);
        ChatOptions options = answerOptions(state);
        LlmRouter.LlmResult result = llmRouter.executeGatedWithUsage(TaskType.TEXT, state.routingMode(),
                model -> model.call(buildPrompt(systemPrompt, userPrompt, options)));
        String answer = truncate(enforceSummaryOnlyForS(result.text() == null ? "" : result.text(), state.responseMode()));
        state = state.toBuilder()
                     .accumulateTokens(result.inputTokens(), result.outputTokens())
                     .usedProvider(llmRouter.findProviderName(TaskType.TEXT, state.routingMode()))
                     .answer(answer)
                     .build();
        return checkSufficiencyAndMaybeUpgrade(state, answer, null);
    }

    // ── Streaming paths ─────────────────────────────────────────────────────

    private AgentState executeStreamingNormal(AgentState state, GraphListener listener) {
        String systemPrompt = answerSystemPrompt(state.locale());
        LlmProvider provider = llmRouter.routeProvider(TaskType.TEXT, state.routingMode());
        String streamed;
        try (var permit = llmRouter.acquirePermit(provider)) {
            streamed = streamAnswer(provider, state, systemPrompt, listener::onToken);
        }
        String answer = truncate(enforceSummaryOnlyForS(streamed, state.responseMode()));
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
        // S mode: skip evaluation entirely — no verifying indicator, no LLM eval call,
        // no retry loop. CRITIC is already skipped in AgentGraph for S mode; skipping the
        // ANSWER-level eval here ensures neither the blocking LLM call nor the "응답 검증 중..."
        // UI indicator appears. grounded stays null (검증 미실행), same convention as directMode.
        if (state.responseMode() == ResponseMode.S) {
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
        String systemPrompt = answerSystemPrompt(state.locale());
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
                    state.threadId(), state.routingMode());
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
                               Consumer<String> tokenSink, String threadId, RoutingMode routingMode) {
        List<OpenAiApi.ChatCompletionMessage> messages = List.of(
                new OpenAiApi.ChatCompletionMessage(systemPrompt, OpenAiApi.ChatCompletionMessage.Role.SYSTEM),
                new OpenAiApi.ChatCompletionMessage(userPrompt, OpenAiApi.ChatCompletionMessage.Role.USER)
        );
        // §6.18 — general/RAG temperature (app.llm.temperature / LLM_TEMPERATURE), was hardcoded 0.0.
        OpenAiApi.ChatCompletionRequest request =
                new OpenAiApi.ChatCompletionRequest(messages, provider.model(), props.llmSafe().temperature(), true);
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
        boolean docsPresent = !state.retrievedDocs().isEmpty();
        try {
            String systemPrompt = messageSource.getMessage("prompt.answer.eval", null, locale);
            String excerpts = buildEvalExcerpts(state.retrievedDocs());
            String evalPrompt = "[질문]\n%s\n\n[답변]\n%s\n\n[문서 발췌]\n%s\n\n%s"
                    .formatted(PromptInjectionGuard.wrap(state.question()), answer, excerpts, evalConverter.getFormat());

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

    private String answerSystemPrompt(Locale locale) {
        return messageSource.getMessage("prompt.answer.system", null, locale);
    }

    /**
     * The S/M/L answer-style instruction for this turn (summary / detailed / source-preserving),
     * naming a concrete character target (see {@link ResponseMode} javadoc). Appended to the user
     * prompt just before the question so the question stays last (the system prompt's injection
     * warning assumes that ordering).
     */
    private String responseStyleInstruction(AgentState state) {
        ResponseMode mode = state.responseMode();
        int tokens = mode.maxTokens(props.llmSafe().maxTokens());
        int targetChars = tokens > 0 ? tokens : mode.minChars();
        return messageSource.getMessage(mode.promptKey(), new Object[]{targetChars}, state.locale());
    }

    /**
     * Per-call options for a blocking answer call: general/RAG temperature (§6.18, hot — read fresh
     * per call) plus {@code maxTokens} derived from the response mode's share of the configured
     * {@code app.llm.max-tokens}. maxTokens is only attached on the <b>blocking</b> call paths —
     * streaming calls have no hard per-call cap (token-by-token UX), so there the same character
     * target is instead named in {@link #responseStyleInstruction} as the model's only length control.
     */
    private ChatOptions answerOptions(AgentState state) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .temperature(props.llmSafe().temperature());
        int configured = props.llmSafe().maxTokens();
        int max = state.responseMode().maxTokens(configured);
        if (max > 0) builder.maxTokens(max);
        return builder.build();
    }

    /** Sufficiency-evaluation call options: general/RAG temperature only (no maxTokens cap — the
     *  structured JSON output is already short). Hot — read fresh per call. */
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

        sb.append(responseStyleInstruction(state)).append("\n\n");

        sb.append("[질문]\n").append(PromptInjectionGuard.wrap(state.question()));
        return sb.toString();
    }

    /** Absolute ceiling protecting storage/rendering — independent of the response mode, whose
     *  budget is expressed as the call's maxTokens instead of a character cap. */
    private static String truncate(String answer) {
        if (answer == null || answer.length() <= MAX_ANSWER_LEN) return answer;
        return answer.substring(0, MAX_ANSWER_LEN) + "\n\n…(응답이 너무 길어 잘렸습니다)";
    }

    /**
     * S mode must return summary-only text. Keep only the summary section body (if present),
     * otherwise take the first non-empty lines, and cap to 7 lines.
     */
    private static String enforceSummaryOnlyForS(String answer, ResponseMode mode) {
        if (mode != ResponseMode.S) return answer;
        if (answer == null || answer.isBlank()) return answer;
        String summary = CuratedTextUtils.extractSummarySection(answer);
        String base = summary.isBlank() ? answer : summary;
        String lines = Arrays.stream(base.split("\\R"))
                .map(String::strip)
                .filter(s -> !s.isBlank())
                .limit(7)
                .collect(Collectors.joining("\n"));
        if (lines.isBlank()) return "## 요약\n요약할 내용이 없습니다.";
        return "## 요약\n" + lines;
    }

}
