package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.ingestion.MarkdownNoiseNormalizer;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.llm.DualResult;
import com.example.ragagent.llm.LlmProvider;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.security.PromptInjectionGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
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

    /** single evaluation call returns both gates. */
    private record EvalOutput(boolean sufficient, boolean grounded) {}

    private final LlmRouter llmRouter;
    private final MessageSource messageSource;
    private final int maxRetryCount;
    private final BeanOutputConverter<EvalOutput> evalConverter =
            new BeanOutputConverter<>(EvalOutput.class);

    public AnswerService(LlmRouter llmRouter, AppProperties appProperties, MessageSource messageSource) {
        this.llmRouter = llmRouter;
        this.messageSource = messageSource;
        this.maxRetryCount = appProperties.maxRetryCount();
    }

    public AgentState execute(AgentState state) {
        if (state.isDualMode()) return executeDualBlocking(state);
        return executeBlocking(state);
    }

    public AgentState executeStreaming(AgentState state, GraphListener listener) {
        if (state.isDualMode()) return executeDualStreaming(state, listener);
        return executeStreamingNormal(state, listener);
    }

    // ── Blocking paths ──────────────────────────────────────────────────────

    private AgentState executeBlocking(AgentState state) {
        String systemPrompt = answerSystemPrompt(state.locale());
        String userPrompt = buildAnswerPrompt(state);
        String rawAnswer = llmRouter.executeGated(TaskType.TEXT, state.routingMode(),
                model -> model.call(buildPrompt(systemPrompt, userPrompt)));
        String answer = truncate(rawAnswer == null ? "" : rawAnswer);
        state = state.toBuilder()
                     .accumulateTokens(0, 0)
                     .usedProvider(llmRouter.findProviderName(TaskType.TEXT, state.routingMode()))
                     .answer(answer)
                     .build();
        return checkSufficiencyAndMaybeUpgrade(state, answer, null);
    }

    private AgentState executeDualBlocking(AgentState state) {
        String systemPrompt = answerSystemPrompt(state.locale());
        String userPrompt = buildAnswerPrompt(state);
        DualResult dual = llmRouter.executeDual(
                TaskType.TEXT,
                model -> model.call(buildPrompt(systemPrompt, userPrompt))
        );
        return buildDualResultState(state,
                truncate(dual.externalAnswer()), dual.externalProvider(),
                truncate(dual.localAnswer()), dual.localProvider());
    }

    // ── Streaming paths ─────────────────────────────────────────────────────

    private AgentState executeStreamingNormal(AgentState state, GraphListener listener) {
        String systemPrompt = answerSystemPrompt(state.locale());
        LlmProvider provider = llmRouter.routeProvider(TaskType.TEXT, state.routingMode());
        String streamed;
        try (var permit = llmRouter.acquirePermit(provider)) {
            streamed = streamAnswer(provider, state, systemPrompt, listener::onToken);
        }
        String answer = truncate(streamed);
        // streaming has no ChatResponse to read real usage from — record an approximate
        // (chars/4) usage entry so /llm-usage isn't blind to the entire streaming chat path.
        llmRouter.recordApproxUsage(provider.name(), systemPrompt + buildAnswerPrompt(state), answer);
        state = state.toBuilder().accumulateTokens(0, 0).usedProvider(provider.name()).answer(answer).build();
        return checkSufficiencyAndMaybeUpgrade(state, answer, listener);
    }

    private AgentState executeDualStreaming(AgentState state, GraphListener listener) {
        String systemPrompt = answerSystemPrompt(state.locale());
        String userPrompt = buildAnswerPrompt(state);
        AgentState capturedState = state;
        StringBuilder localBuf = new StringBuilder(), extBuf = new StringBuilder();
        BiConsumer<LlmProvider, Consumer<String>> callFn =
                (prov, sink) -> callOrStream(prov, capturedState, systemPrompt, sink);
        LlmRouter.DualProviders dp = llmRouter.executeDualStream(
                TaskType.TEXT,
                callFn,
                t -> { localBuf.append(t); listener.onToken("local", t); },
                t -> { extBuf.append(t);   listener.onToken("external", t); }
        );
        llmRouter.recordApproxUsage(dp.localProvider(), systemPrompt + userPrompt, localBuf.toString());
        llmRouter.recordApproxUsage(dp.externalProvider(), systemPrompt + userPrompt, extBuf.toString());
        return buildDualResultState(state,
                truncate(extBuf.toString()), dp.externalProvider(),
                truncate(localBuf.toString()), dp.localProvider());
    }

    /** Shared DUAL result assembly for both blocking and streaming — CRITIC is bypassed (needsRetry=false). */
    private AgentState buildDualResultState(AgentState state, String extAnswer, String extProvider,
                                             String localAnswer, String localProvider) {
        return state.toBuilder()
                    .answer(extAnswer)
                    .usedProvider(extProvider)
                    .dualResult(localAnswer, localAnswer.isBlank() ? null : localProvider)
                    .needsRetry(false)
                    .build();
    }

    // ── Evaluation (sufficiency + grounding) + PROGRESSIVE ───────────────────

    private AgentState checkSufficiencyAndMaybeUpgrade(AgentState state, String answer, GraphListener listener) {
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
        if (listener != null) {
            try (var permit = llmRouter.acquirePermit(premiumProvider)) {
                premiumAnswer = streamAnswer(premiumProvider, state, systemPrompt, listener::onToken);
            }
            llmRouter.recordApproxUsage(premiumProvider.name(),
                    systemPrompt + buildAnswerPrompt(state), premiumAnswer);
        } else {
            String userPrompt = buildAnswerPrompt(state);
            premiumAnswer = llmRouter.executeGated(
                    TaskType.TEXT, RoutingMode.QUALITY_FIRST,
                    model -> model.call(buildPrompt(systemPrompt, userPrompt))
            );
        }
        return resultState.toBuilder()
                          .answer(truncate(premiumAnswer))
                          .usedProvider(premiumProvider.name())
                          .premiumUpgraded(premiumProvider.name())
                          .needsRetry(false)
                          .build();
    }

    /** Shared raw Prompt construction for the non-fluent LlmRouter call sites (DUAL, PROGRESSIVE). */
    private static Prompt buildPrompt(String systemPrompt, String userPrompt) {
        return new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt)));
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
            ChatClient.builder(provider.chatModel()).build()
                    .prompt()
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
        OpenAiApi.ChatCompletionRequest request =
                new OpenAiApi.ChatCompletionRequest(messages, provider.model(), 0.0, true);
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
     * single LLM call evaluating both gates at once.
     * needsRetry is driven by sufficiency (the ANSWER-node gate); grounded is stored for the
     * CRITIC node to consume without a second LLM round-trip. When no docs were retrieved,
     * grounding is trivially true (CRITIC short-circuits on empty docs anyway).
     */
    private AgentState evaluate(AgentState state, String answer, Locale locale) {
        boolean docsPresent = !state.retrievedDocs().isEmpty();
        try {
            String systemPrompt = messageSource.getMessage("prompt.answer.eval", null, locale);
            String excerpts = state.retrievedDocs().stream()
                    .limit(5)
                    .map(d -> d.getText() == null ? "" : d.getText())
                    .collect(Collectors.joining("\n---\n"));
            String evalPrompt = "[질문]\n%s\n\n[답변]\n%s\n\n[문서 발췌]\n%s\n\n%s"
                    .formatted(PromptInjectionGuard.wrap(state.question()), answer, excerpts, evalConverter.getFormat());

            String response = llmRouter.executeGated(TaskType.TEXT, state.routingMode(),
                    model -> model.call(buildPrompt(systemPrompt, evalPrompt)));
            EvalOutput out = evalConverter.convert(response == null ? "" : response);
            boolean grounded = !docsPresent || out.grounded();
            return state.toBuilder()
                    .accumulateTokens(0, 0)
                    .needsRetry(!out.sufficient())
                    .grounded(grounded)
                    .build();
        } catch (Exception e) {
            log.warn("Evaluation parse failed, treating as sufficient + grounded: {}", e.getMessage());
            return state.toBuilder().needsRetry(false).grounded(true).build();
        }
    }

    private String answerSystemPrompt(Locale locale) {
        return messageSource.getMessage("prompt.answer.system", null, locale);
    }

    private String buildAnswerPrompt(AgentState state) {
        StringBuilder sb = new StringBuilder();
        if (!state.conversationHistory().isBlank()) {
            sb.append("[이전 대화]\n").append(state.conversationHistory()).append("\n\n");
        }

        String docsContext = state.retrievedDocs().stream()
                .map(doc -> {
                    String filename = String.valueOf(doc.getMetadata().getOrDefault(MetaKey.FILENAME, "unknown"));
                    String page     = String.valueOf(doc.getMetadata().getOrDefault(MetaKey.PAGE_OR_SLIDE, "?"));
                    // Normalized (no context header, §10.1) — decorative markdown is stripped so it
                    // doesn't consume prompt tokens; the stored/displayed text elsewhere stays raw.
                    return "[%s | p.%s]\n%s".formatted(filename, page,
                            MarkdownNoiseNormalizer.normalize(doc.getText()));
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

    private static String truncate(String answer) {
        if (answer == null || answer.length() <= MAX_ANSWER_LEN) return answer;
        return answer.substring(0, MAX_ANSWER_LEN) + "\n\n…(응답이 너무 길어 잘렸습니다)";
    }

}
