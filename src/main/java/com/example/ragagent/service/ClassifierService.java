package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
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

    private static final Set<String> VALID_TYPES = Set.of("concept", "usage", "error", "version", "meta");

    private static final String SYSTEM_PROMPT = """
            당신은 프레임워크 매뉴얼 Q&A 시스템의 질문 분류기입니다.
            사용자의 질문을 다음 중 하나로 분류하세요:

            - concept : 개념/이론/원리 설명 요청 (예: "~이 무엇인가요?", "~의 차이는?")
            - usage   : 사용법/코드 예시/설정 방법 요청 (예: "어떻게 사용하나요?", "설정 방법은?")
            - error   : 오류/버그/트러블슈팅 요청 (예: "에러가 납니다", "왜 안 되나요?")
            - version : 버전/변경사항/업데이트 관련 (예: "버전별 차이는?", "최신 변경사항은?")
            - meta    : 인사/서비스 소개/잡담 등 (예: "안녕", "뭘 도와줘?", "감사합니다")

            반드시 JSON만 반환하세요: {"question_type": "..."}

            예시:
            - "Spring Security를 어떻게 설정하나요?" → {"question_type": "usage"}
            - "NullPointerException이 계속 납니다" → {"question_type": "error"}
            - "안녕하세요, 무엇을 도와줄 수 있나요?" → {"question_type": "meta"}
            - "JPA와 MyBatis의 차이점은?" → {"question_type": "concept"}
            - "3.0에서 바뀐 점이 있나요?" → {"question_type": "version"}
            """;

    private final ChatClient chatClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public ClassifierService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public void execute(AgentState state) {
        String response = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(state.getQuestion())
                .call()
                .content();

        state.setQuestionType(extractQuestionType(response));
    }

    private String extractQuestionType(String response) {
        try {
            String json = extractJson(response);
            JsonNode node = mapper.readTree(json);
            String type = node.path("question_type").asText("concept").toLowerCase();
            if (VALID_TYPES.contains(type)) return type;
        } catch (Exception ignored) {}
        return "concept";
    }

    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) return text.substring(start, end + 1);
        return "{}";
    }
}
