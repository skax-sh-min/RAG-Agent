package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.llm.LlmProvider;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
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

        ChatClient client = buildClient(state.routingMode());
        StringBuilder buf = new StringBuilder();
        client.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .stream().content().doOnNext(buf::append).blockLast();
        String answer = buf.isEmpty() ? null : buf.toString();
        log.debug("[DirectAnswer] answer length={}", answer == null ? -1 : answer.length());
        return state.withAnswer(answer).withTokensAccumulated(0, 0);
    }

    /** Streaming variant — pushes tokens via listener.onToken() instead of blocking. */
    public AgentState executeStreaming(AgentState state, GraphListener listener) {
        String systemPrompt = resolveSystemPrompt(state);
        log.debug("[DirectAnswer] streaming directMode={} routingMode={} historyLen={}", state.directMode(),
                state.routingMode(), state.conversationHistory().length());

        RoutingMode effective = (state.routingMode() == RoutingMode.DUAL) ? RoutingMode.COST_FIRST : state.routingMode();
        LlmProvider provider = llmRouter.routeProvider(TaskType.TEXT, effective);

        StringBuilder full = new StringBuilder();
        callOrStream(provider, state, systemPrompt,
                t -> { listener.onToken(t); full.append(t); });

        String answer = full.toString();
        log.debug("[DirectAnswer] streaming answer length={}", answer.length());
        // Note: streaming mode cannot capture usage metadata for token tracking (no ChatResponse)
        return state.withAnswer(answer).withTokensAccumulated(0, 0);
    }

    private String resolveSystemPrompt(AgentState state) {
        String key = state.directMode() ? "prompt.direct.system" : "prompt.direct.meta.system";
        return messageSource.getMessage(key, null, state.locale());
    }

    private ChatClient buildClient(RoutingMode mode) {
        // DUAL is not implemented in DirectAnswer; fall back to COST_FIRST (LOCAL preferred)
        RoutingMode effective = (mode == RoutingMode.DUAL) ? RoutingMode.COST_FIRST : mode;
        return ChatClient.builder(llmRouter.route(TaskType.TEXT, effective)).build();
    }

    private static String buildUserPrompt(AgentState state) {
        String history = state.conversationHistory();
        return history.isBlank()
                ? state.question()
                : "[이전 대화]\n%s\n\n[현재 질문]\n%s".formatted(history, state.question());
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
