package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

/**
 * Generates a structured answer from retrieved documents.
 * Uses ReAct-style self-evaluation: if evidence is insufficient, sets needsRetry=true.
 *
 * Equivalent to answer_node in agents.py.
 */
@Service
public class AnswerService {

    private static final String SYSTEM_PROMPT = """
            당신은 프레임워크 매뉴얼 전문 Q&A 어시스턴트입니다.
            아래 검색된 문서를 바탕으로 질문에 답변하세요.

            답변 형식 (마크다운):
            ## 요약
            (1-2문장 핵심 요약)

            ## 상세 설명
            (근거 문서 기반 상세 답변)

            ## 예시/코드
            (해당되는 경우만)

            ## 설정/주의사항
            (해당되는 경우만)

            ## 참고
            (관련 섹션/페이지)

            오류(error) 질문에는 원인→확인→해결 순으로 작성하세요.
            문서에 없는 내용은 추측하지 말고 "문서에서 확인되지 않음"으로 명시하세요.

            답변 마지막에 반드시 다음 JSON을 한 줄로 추가하세요:
            {"sufficient": true} 또는 {"sufficient": false}
            (sufficient=false: 핵심 질문에 답하기에 문서 증거가 불충분할 때)
            """;

    private final ChatClient chatClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public AnswerService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public void execute(AgentState state) {
        String docsContext = buildDocsContext(state);
        String history = state.getConversationHistory();

        String userPrompt = buildUserPrompt(history, docsContext, state);

        String response = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userPrompt)
                .call()
                .content();

        boolean sufficient = extractSufficient(response);
        state.setNeedsRetry(!sufficient);
        state.setAnswer(removeJsonSuffix(response));
    }

    private String buildDocsContext(AgentState state) {
        return state.getRetrievedDocs().stream()
                .map(doc -> {
                    String filename = String.valueOf(doc.getMetadata().getOrDefault("filename", "unknown"));
                    String page = String.valueOf(doc.getMetadata().getOrDefault("page_or_slide", "?"));
                    return "[%s | p.%s]\n%s".formatted(filename, page, doc.getText());
                })
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    private String buildUserPrompt(String history, String docsContext, AgentState state) {
        StringBuilder sb = new StringBuilder();
        if (!history.isBlank()) {
            sb.append("[이전 대화]\n").append(history).append("\n\n");
        }
        if (!docsContext.isBlank()) {
            sb.append("[검색된 문서]\n").append(docsContext).append("\n\n");
        } else {
            sb.append("[검색된 문서]\n문서를 찾을 수 없습니다.\n\n");
        }
        if (!state.getRetrievalWarnings().isEmpty()) {
            sb.append("[경고]\n").append(String.join("\n", state.getRetrievalWarnings())).append("\n\n");
        }
        sb.append("[질문]\n").append(state.getQuestion());
        return sb.toString();
    }

    private boolean extractSufficient(String response) {
        try {
            int lastBrace = response.lastIndexOf('{');
            if (lastBrace >= 0) {
                String jsonPart = response.substring(lastBrace);
                int end = jsonPart.indexOf('}');
                if (end >= 0) {
                    JsonNode node = mapper.readTree(jsonPart.substring(0, end + 1));
                    return node.path("sufficient").asBoolean(true);
                }
            }
        } catch (Exception ignored) {}
        return true; // if parse fails, treat as sufficient
    }

    private String removeJsonSuffix(String response) {
        int lastBrace = response.lastIndexOf('{');
        if (lastBrace > 0 && response.substring(lastBrace).contains("sufficient")) {
            return response.substring(0, lastBrace).stripTrailing();
        }
        return response;
    }
}
