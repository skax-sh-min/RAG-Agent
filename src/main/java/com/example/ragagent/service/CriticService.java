package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

/**
 * Validates that the generated answer is grounded in the retrieved documents.
 * If ungrounded, sets needsRetry=true (within shared retry budget).
 *
 * Equivalent to critic_node in agents.py.
 */
@Service
public class CriticService {

    private static final String SYSTEM_PROMPT = """
            당신은 RAG 답변의 근거 검증 전문가입니다.
            아래 [문서 발췌]를 바탕으로 [답변]이 문서에 근거하는지 판단하세요.

            판단 기준:
            - grounded=true : 답변의 핵심 주장이 문서에 명확히 근거함
            - grounded=false: 답변에 문서에 없는 주요 내용이 포함되거나 사실 관계가 다름

            반드시 JSON만 반환하세요: {"grounded": true} 또는 {"grounded": false}
            """;

    private final ChatClient chatClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public CriticService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public void execute(AgentState state) {
        if (state.getRetrievedDocs().isEmpty()) {
            state.setNeedsRetry(false);
            return;
        }

        String excerpts = state.getRetrievedDocs().stream()
                .limit(5)
                .map(Document::getText)
                .collect(Collectors.joining("\n---\n"));

        String userPrompt = """
                [문서 발췌]
                %s

                [답변]
                %s
                """.formatted(excerpts, state.getAnswer());

        String response = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userPrompt)
                .call()
                .content();

        boolean grounded = extractGrounded(response);
        state.setNeedsRetry(!grounded);
    }

    private boolean extractGrounded(String response) {
        try {
            String json = extractJson(response);
            JsonNode node = mapper.readTree(json);
            return node.path("grounded").asBoolean(true);
        } catch (Exception ignored) {}
        return true;
    }

    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) return text.substring(start, end + 1);
        return "{}";
    }
}
