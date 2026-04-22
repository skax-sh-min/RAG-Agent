package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * Handles meta questions (greetings, service inquiries) without RAG retrieval.
 * Equivalent to direct_answer_node in agents.py.
 */
@Service
public class DirectAnswerService {

    private static final String SYSTEM_PROMPT = """
            당신은 프레임워크 매뉴얼 Q&A 도우미입니다.
            사용자의 인사나 서비스 관련 문의에 짧고 친절하게 답변하세요.
            문서 검색 없이 직접 답변합니다.
            답변은 2-3문장 이내로 간결하게 작성하세요.
            """;

    private final ChatClient chatClient;

    public DirectAnswerService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public void execute(AgentState state) {
        String history = state.getConversationHistory();
        String userPrompt = history.isBlank()
                ? state.getQuestion()
                : "[이전 대화]\n%s\n\n[현재 질문]\n%s".formatted(history, state.getQuestion());

        String answer = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userPrompt)
                .call()
                .content();

        state.setAnswer(answer);
    }
}
