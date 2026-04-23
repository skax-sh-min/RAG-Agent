package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Classifies the user question into one of:
 * concept | usage | error | version | meta
 *
 * Equivalent to classifier_node in agents.py.
 */
@Service
public class ClassifierService {

    private static final Logger log = LoggerFactory.getLogger(ClassifierService.class);
    private static final Set<String> VALID_TYPES = Set.of("concept", "usage", "error", "version", "meta");

    private static final String SYSTEM_PROMPT = """
            당신은 프레임워크 매뉴얼 Q&A 시스템의 질문 분류기입니다.
            사용자의 질문을 다음 중 하나로 분류하세요:

            - concept : 개념/이론/원리 설명 요청 (예: "~이 무엇인가요?", "~의 차이는?")
            - usage   : 사용법/코드 예시/설정 방법 요청 (예: "어떻게 사용하나요?", "설정 방법은?")
            - error   : 오류/버그/트러블슈팅 요청 (예: "에러가 납니다", "왜 안 되나요?")
            - version : 버전/변경사항/업데이트 관련 (예: "버전별 차이는?", "최신 변경사항은?")
            - meta    : 인사/서비스 소개/잡담 등 (예: "안녕", "뭘 도와줘?", "감사합니다")
            """;

    private record ClassifierOutput(@JsonProperty("question_type") String questionType) {}

    private final ChatClient chatClient;
    private final BeanOutputConverter<ClassifierOutput> converter =
            new BeanOutputConverter<>(ClassifierOutput.class);

    public ClassifierService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public void execute(AgentState state) {
        String response = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(state.getQuestion() + "\n\n" + converter.getFormat())
                .call()
                .content();

        state.setQuestionType(parseType(response));
    }

    private String parseType(String response) {
        try {
            String type = converter.convert(response).questionType().toLowerCase();
            return VALID_TYPES.contains(type) ? type : "concept";
        } catch (Exception e) {
            log.warn("Classifier output parse failed, defaulting to 'concept': {}", e.getMessage());
            return "concept";
        }
    }
}
