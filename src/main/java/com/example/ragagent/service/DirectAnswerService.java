package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.context.MessageSource;
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
        ChatResponse chatResponse = client.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .chatResponse();

        String answer = ChatResponses.safeText(chatResponse);
        log.debug("[DirectAnswer] answer length={}", answer == null ? -1 : answer.length());
        state = accumulateTokens(state, chatResponse);
        return state.withAnswer(answer);
    }

    /** Streaming variant — pushes tokens via listener.onToken() instead of blocking. */
    public AgentState executeStreaming(AgentState state, GraphListener listener) {
        String systemPrompt = resolveSystemPrompt(state);
        log.debug("[DirectAnswer] streaming directMode={} routingMode={} historyLen={}", state.directMode(),
                state.routingMode(), state.conversationHistory().length());
        String userPrompt = buildUserPrompt(state);

        StringBuilder full = new StringBuilder();
        buildClient(state.routingMode()).prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .stream()
                .content()
                .doOnNext(token -> { listener.onToken(token); full.append(token); })
                .blockLast();

        String answer = full.toString();
        log.debug("[DirectAnswer] streaming answer length={}", answer.length());
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

    private static AgentState accumulateTokens(AgentState state, ChatResponse resp) {
        var usage = resp.getMetadata().getUsage();
        int in  = (usage != null && usage.getPromptTokens()     != null) ? usage.getPromptTokens()     : 0;
        int out = (usage != null && usage.getCompletionTokens() != null) ? usage.getCompletionTokens() : 0;
        return state.withTokensAccumulated(in, out);
    }
}
