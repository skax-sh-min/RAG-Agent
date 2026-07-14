package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
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

    public DirectAnswerService(LlmRouter llmRouter, MessageSource messageSource) {
        this.llmRouter = llmRouter;
        this.messageSource = messageSource;
    }

    public AgentState execute(AgentState state) {
        String systemPrompt = resolveSystemPrompt(state);
        log.debug("[DirectAnswer] directMode={} routingMode={} historyLen={}", state.directMode(),
                state.routingMode(), state.conversationHistory().length());
        String userPrompt = buildUserPrompt(state);

        RoutingMode effective = effectiveRoutingMode(state.routingMode());
        String rawAnswer = llmRouter.executeGated(TaskType.TEXT, effective,
                model -> model.call(buildPrompt(systemPrompt, userPrompt)));
        String answer = (rawAnswer == null || rawAnswer.isEmpty()) ? null : rawAnswer;
        log.debug("[DirectAnswer] answer length={}", answer == null ? -1 : answer.length());
        return state.toBuilder().answer(answer).accumulateTokens(0, 0).build();
    }

    /** Streaming variant — pushes tokens via listener.onToken() instead of blocking. */
    public AgentState executeStreaming(AgentState state, GraphListener listener) {
        String systemPrompt = resolveSystemPrompt(state);
        log.debug("[DirectAnswer] streaming directMode={} routingMode={} historyLen={}", state.directMode(),
                state.routingMode(), state.conversationHistory().length());

        RoutingMode effective = effectiveRoutingMode(state.routingMode());
        LlmProvider provider = llmRouter.routeProvider(TaskType.TEXT, effective);

        StringBuilder full = new StringBuilder();
        try (var permit = llmRouter.acquirePermit(provider)) {
            callOrStream(provider, state, systemPrompt,
                    t -> { listener.onToken(t); full.append(t); });
        }

        String answer = full.toString();
        log.debug("[DirectAnswer] streaming answer length={}", answer.length());
        // Streaming mode has no ChatResponse to read real usage from — record an approximate
        // (chars/4) usage entry so /llm-usage isn't blind to the entire direct-answer stream path.
        llmRouter.recordApproxUsage(provider.name(), systemPrompt + buildUserPrompt(state), answer);
        return state.toBuilder().answer(answer).accumulateTokens(0, 0).build();
    }

    private String resolveSystemPrompt(AgentState state) {
        String key = state.directMode() ? "prompt.direct.system" : "prompt.direct.meta.system";
        return messageSource.getMessage(key, null, state.locale());
    }

    /** DUAL is not implemented in DirectAnswer; fall back to COST_FIRST (LOCAL preferred). */
    private static RoutingMode effectiveRoutingMode(RoutingMode mode) {
        return (mode == RoutingMode.DUAL) ? RoutingMode.COST_FIRST : mode;
    }

    private static Prompt buildPrompt(String systemPrompt, String userPrompt) {
        return new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt)));
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
                              String systemPrompt, java.util.function.Consumer<String> tokenSink) {
        if (provider.stream()) {
            // Bypass OpenAiChatModel.internalStream() which buffers ALL chunks via buffer(int,int)
            // before emitting, defeating real-time token delivery to the browser.
            String userPrompt = buildUserPrompt(state);
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
