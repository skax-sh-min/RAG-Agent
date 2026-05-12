package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

/**
 * Handles meta questions (greetings, service inquiries) without RAG retrieval.
 * Also handles directMode queries — general-purpose LLM calls bypassing RAG.
 * Equivalent to direct_answer_node in agents.py.
 */
@Service
public class DirectAnswerService {

    private static final Logger log = LoggerFactory.getLogger(DirectAnswerService.class);

    private static final String SYSTEM_PROMPT = """
            당신은 문서 기반 지식 Q&A 도우미입니다.
            사용자의 인사나 서비스 관련 문의에 짧고 친절하게 답변하세요.
            문서 검색 없이 직접 답변합니다.
            답변은 2-3문장 이내로 간결하게 작성하세요.
            """;

    private static final String DIRECT_SYSTEM_PROMPT = """
            당신은 유능한 AI 어시스턴트입니다.
            사용자의 질문에 정확하고 도움이 되는 답변을 제공하세요.
            답변은 마크다운 형식으로 작성하세요.
            문서 검색 없이 학습된 지식을 바탕으로 직접 답변합니다.
            """;

    private final ChatClient chatClient;

    public DirectAnswerService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public AgentState execute(AgentState state) {
        String systemPrompt = state.directMode() ? DIRECT_SYSTEM_PROMPT : SYSTEM_PROMPT;
        log.debug("[DirectAnswer] directMode={} historyLen={}", state.directMode(),
                state.conversationHistory().length());
        String userPrompt = buildUserPrompt(state);

        ChatResponse chatResponse = chatClient.prompt()
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
        String systemPrompt = state.directMode() ? DIRECT_SYSTEM_PROMPT : SYSTEM_PROMPT;
        log.debug("[DirectAnswer] streaming directMode={} historyLen={}", state.directMode(),
                state.conversationHistory().length());
        String userPrompt = buildUserPrompt(state);

        StringBuilder full = new StringBuilder();
        chatClient.prompt()
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
