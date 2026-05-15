package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.llm.DualResult;
import com.example.ragagent.llm.LlmProvider;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
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
import reactor.core.scheduler.Schedulers;

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

    private record SufficiencyOutput(boolean sufficient) {}

    private final ChatClient chatClient;
    private final LlmRouter llmRouter;
    private final MessageSource messageSource;
    private final int maxRetryCount;
    private final BeanOutputConverter<SufficiencyOutput> sufficiencyConverter =
            new BeanOutputConverter<>(SufficiencyOutput.class);

    public AnswerService(ChatClient chatClient, LlmRouter llmRouter,
                         AppProperties appProperties, MessageSource messageSource) {
        this.chatClient = chatClient;
        this.llmRouter = llmRouter;
        this.messageSource = messageSource;
        this.maxRetryCount = appProperties.maxRetryCount();
    }

    /** Existing blocking path — unchanged. */
    public AgentState execute(AgentState state) {
        return executeInternal(state, null);
    }

    /**
     * Streaming path: Call 1 tokens are pushed via listener.onToken().
     * Call 2 (sufficiency) and PROGRESSIVE upgrade remain blocking.
     * DUAL falls back to the blocking path (Phase 5 will add DUAL streaming).
     */
    public AgentState executeStreaming(AgentState state, GraphListener listener) {
        return executeInternal(state, listener);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private AgentState executeInternal(AgentState state, GraphListener listener) {
        Locale locale = state.locale();
        String answerSystemPrompt = messageSource.getMessage("prompt.answer.system", null, locale);

        // DUAL: streaming when listener present, blocking otherwise
        if (state.isDualMode()) {
            if (listener != null) {
                AgentState capturedState = state;
                StringBuilder localBuf = new StringBuilder(), extBuf = new StringBuilder();
                BiConsumer<LlmProvider, Consumer<String>> callFn =
                        (prov, sink) -> callOrStream(prov, capturedState, answerSystemPrompt, sink);
                LlmRouter.DualProviders dp = llmRouter.executeDualStream(
                        TaskType.TEXT,
                        callFn,
                        t -> { localBuf.append(t); listener.onToken("local", t); },
                        t -> { extBuf.append(t);   listener.onToken("external", t); }
                );
                String localAnswer = truncate(localBuf.toString());
                String extAnswer   = truncate(extBuf.toString());
                return state.withAnswer(extAnswer)
                            .withUsedProvider(dp.externalProvider())
                            .withDualResult(localAnswer,
                                    localAnswer.isBlank() ? null : dp.localProvider())
                            .withNeedsRetry(false);
            }
            String answerPrompt = buildAnswerPrompt(state);
            DualResult dual = llmRouter.executeDual(
                    TaskType.TEXT,
                    model -> model.call(new Prompt(List.of(
                            new SystemMessage(answerSystemPrompt),
                            new UserMessage(answerPrompt)
                    )))
            );
            String extAnswer   = truncate(dual.externalAnswer());
            String localAnswer = truncate(dual.localAnswer());
            return state
                    .withAnswer(extAnswer)
                    .withUsedProvider(dual.externalProvider())
                    .withDualResult(localAnswer,
                            localAnswer.isBlank() ? null : dual.localProvider())
                    .withNeedsRetry(false);
        }

        // Call 1: answer generation
        String answer;
        if (listener != null) {
            LlmProvider provider = llmRouter.routeProvider(TaskType.TEXT, state.routingMode());
            answer = streamAnswer(provider, state, answerSystemPrompt, listener::onToken);
            state = state.withUsedProvider(provider.name());
        } else {
            StringBuilder buf = new StringBuilder();
            chatClient.prompt()
                    .system(answerSystemPrompt)
                    .user(buildAnswerPrompt(state))
                    .stream().content().doOnNext(buf::append).blockLast();
            state = state.withTokensAccumulated(0, 0);
            answer = buf.isEmpty() ? "" : buf.toString();
            state = state.withUsedProvider(llmRouter.findProviderName(TaskType.TEXT, state.routingMode()));
        }

        answer = truncate(answer);
        // Call 2: sufficiency check (always blocking)
        AgentState resultState = checkSufficiency(state.withAnswer(answer), answer, locale);

        // PROGRESSIVE upgrade: re-call when listener present, otherwise blocking.
        if (state.routingMode() == RoutingMode.PROGRESSIVE
                && resultState.needsRetry()
                && state.retryCount() >= maxRetryCount) {

            LlmProvider premiumProvider = llmRouter.routeProvider(TaskType.TEXT, RoutingMode.QUALITY_FIRST);
            if (listener != null) listener.onUpgrade(premiumProvider.name());
            String premiumAnswer;
            if (listener != null) {
                premiumAnswer = streamAnswer(premiumProvider, state, answerSystemPrompt, listener::onToken);
            } else {
                String answerPrompt = buildAnswerPrompt(state);
                premiumAnswer = llmRouter.executeWithTracking(
                        TaskType.TEXT, RoutingMode.QUALITY_FIRST,
                        model -> model.call(new Prompt(List.of(
                                new SystemMessage(answerSystemPrompt),
                                new UserMessage(answerPrompt)
                        )))
                );
            }
            return resultState
                    .withAnswer(truncate(premiumAnswer))
                    .withUsedProvider(premiumProvider.name())
                    .withPremiumUpgraded(premiumProvider.name())
                    .withNeedsRetry(false);
        }

        return resultState;
    }

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
                .publishOn(Schedulers.boundedElastic(), 1)
                .doOnNext(tokenSink)
                .blockLast();
    }

    private AgentState checkSufficiency(AgentState state, String answer, Locale locale) {
        try {
            String systemPrompt = messageSource.getMessage("prompt.answer.sufficiency", null, locale);
            String evalPrompt = "[질문]\n%s\n\n[답변]\n%s\n\n%s"
                    .formatted(state.question(), answer, sufficiencyConverter.getFormat());

            StringBuilder buf = new StringBuilder();
            chatClient.prompt()
                    .system(systemPrompt)
                    .user(evalPrompt)
                    .stream().content().doOnNext(buf::append).blockLast();
            state = state.withTokensAccumulated(0, 0);
            boolean sufficient = sufficiencyConverter
                    .convert(buf.isEmpty() ? "" : buf.toString())
                    .sufficient();
            return state.withNeedsRetry(!sufficient);
        } catch (Exception e) {
            log.warn("Sufficiency check parse failed, treating as sufficient: {}", e.getMessage());
            return state.withNeedsRetry(false);
        }
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
                    return "[%s | p.%s]\n%s".formatted(filename, page, doc.getText());
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

        sb.append("[질문]\n").append(state.question());
        return sb.toString();
    }

    private static String truncate(String answer) {
        if (answer == null || answer.length() <= MAX_ANSWER_LEN) return answer;
        return answer.substring(0, MAX_ANSWER_LEN) + "\n\n…(응답이 너무 길어 잘렸습니다)";
    }

}
