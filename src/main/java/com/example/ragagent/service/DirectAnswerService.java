package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

/**
 * Handles meta questions (greetings, service inquiries) without RAG retrieval.
 * Equivalent to direct_answer_node in agents.py.
 */
@Service
public class DirectAnswerService {

    private static final String SYSTEM_PROMPT = """
            당신은 문서 기반 지식 Q&A 도우미입니다.
            사용자의 인사나 서비스 관련 문의에 짧고 친절하게 답변하세요.
            문서 검색 없이 직접 답변합니다.
            답변은 2-3문장 이내로 간결하게 작성하세요.
            """;

    private final ChatClient chatClient;

    public DirectAnswerService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public AgentState execute(AgentState state) {
        String history = state.conversationHistory();
        String userPrompt = history.isBlank()
                ? state.question()
                : "[이전 대화]\n%s\n\n[현재 질문]\n%s".formatted(history, state.question());

        ChatResponse chatResponse = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userPrompt)
                .call()
                .chatResponse();

        state = accumulateTokens(state, chatResponse);
        return state.withAnswer(chatResponse.getResult().getOutput().getText());
    }

    private static AgentState accumulateTokens(AgentState state, ChatResponse resp) {
        var usage = resp.getMetadata().getUsage();
        int in  = (usage != null && usage.getPromptTokens()     != null) ? usage.getPromptTokens()     : 0;
        int out = (usage != null && usage.getCompletionTokens() != null) ? usage.getCompletionTokens() : 0;
        return state.withTokensAccumulated(in, out);
    }
}
