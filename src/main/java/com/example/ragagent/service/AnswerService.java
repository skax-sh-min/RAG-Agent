package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

/**
 * Generates a structured answer from retrieved documents (Call 1),
 * then evaluates evidence sufficiency via a separate LLM call (Call 2).
 *
 * Splitting the two concerns eliminates fragile "JSON embedded in prose" parsing.
 *
 * Equivalent to answer_node in agents.py.
 */
@Service
public class AnswerService {

    private static final Logger log = LoggerFactory.getLogger(AnswerService.class);

    private static final String ANSWER_SYSTEM_PROMPT = """
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
            """;

    private static final String SUFFICIENCY_SYSTEM_PROMPT = """
            아래 [질문]과 [답변]을 검토하여 답변이 질문에 충분한지 판단하세요.
            sufficient=false: 검색된 문서 증거가 핵심 질문에 답하기에 불충분할 때만 사용하세요.
            """;

    private record SufficiencyOutput(boolean sufficient) {}

    private final ChatClient chatClient;
    private final BeanOutputConverter<SufficiencyOutput> sufficiencyConverter =
            new BeanOutputConverter<>(SufficiencyOutput.class);

    public AnswerService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public void execute(AgentState state) {
        // Call 1: generate pure prose answer — no JSON instructions mixed in
        String answer = chatClient.prompt()
                .system(ANSWER_SYSTEM_PROMPT)
                .user(buildAnswerPrompt(state))
                .call()
                .content();

        state.setAnswer(answer);

        // Call 2: dedicated sufficiency check — simple JSON only
        state.setNeedsRetry(!checkSufficiency(state.getQuestion(), answer));
    }

    private String buildAnswerPrompt(AgentState state) {
        StringBuilder sb = new StringBuilder();
        if (!state.getConversationHistory().isBlank()) {
            sb.append("[이전 대화]\n").append(state.getConversationHistory()).append("\n\n");
        }

        String docsContext = state.getRetrievedDocs().stream()
                .map(doc -> {
                    String filename = String.valueOf(doc.getMetadata().getOrDefault("filename", "unknown"));
                    String page     = String.valueOf(doc.getMetadata().getOrDefault("page_or_slide", "?"));
                    return "[%s | p.%s]\n%s".formatted(filename, page, doc.getText());
                })
                .collect(Collectors.joining("\n\n---\n\n"));

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

    private boolean checkSufficiency(String question, String answer) {
        try {
            String evalPrompt = "[질문]\n%s\n\n[답변]\n%s\n\n%s"
                    .formatted(question, answer, sufficiencyConverter.getFormat());

            String response = chatClient.prompt()
                    .system(SUFFICIENCY_SYSTEM_PROMPT)
                    .user(evalPrompt)
                    .call()
                    .content();

            return sufficiencyConverter.convert(response).sufficient();
        } catch (Exception e) {
            log.warn("Sufficiency check parse failed, treating as sufficient: {}", e.getMessage());
            return true;
        }
    }
}
