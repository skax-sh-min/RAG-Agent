package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmProvider;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.TaskType;
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

    public DirectAnswerService(LlmRouter llmRouter, MessageSource messageSource, AppProperties props) {
        this.llmRouter = llmRouter;
        this.messageSource = messageSource;
        this.props = props;
    }

    public AgentState execute(AgentState state) {
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
        String answer = (rawAnswer == null || rawAnswer.isEmpty()) ? null : rawAnswer;
        log.debug("[DirectAnswer] answer length={}", answer == null ? -1 : answer.length());
        return state.toBuilder().answer(answer)
                .accumulateTokens(result.inputTokens(), result.outputTokens()).build();
    }

    /** Streaming variant — pushes tokens via listener.onToken() instead of blocking. */
    public AgentState executeStreaming(AgentState state, GraphListener listener) {
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

    private String resolveSystemPrompt(AgentState state) {
        String key = state.directMode() ? "prompt.direct.system" : "prompt.direct.meta.system";
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


    private static String buildUserPrompt(AgentState state) {
        String history = state.conversationHistory();
        String question = PromptInjectionGuard.wrap(state.question());
        return history.isBlank()
                ? question
                : "[이전 대화]\n%s\n\n[현재 질문]\n%s".formatted(history, question);
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
                            signal, provider.name(), state.threadId()))
                    .toIterable()
                    .forEach(tokenSink);
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
}
