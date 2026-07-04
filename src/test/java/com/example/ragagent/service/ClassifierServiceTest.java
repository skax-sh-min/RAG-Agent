package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.llm.RoutingMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.MessageSource;
import reactor.core.publisher.Flux;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA — ClassifierService question-type parsing (EDIT.md #1)
 *
 * Covers: valid type parse, out-of-enum fallback to "concept", malformed-JSON fallback,
 * case-insensitivity, and that execute() accumulates a (0,0) token call while setting
 * questionType (classifyOnly() intentionally does not, per CLAUDE.md's documented
 * llmCallCount under-reporting trade-off — it has no AgentState to update).
 */
class ClassifierServiceTest {

    private ChatClient chatClient;
    private ClassifierService service;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        MessageSource messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("prompt");
        service = new ClassifierService(chatClient, messageSource);
    }

    private void stubResponse(String json) {
        when(chatClient.prompt().system(anyString()).user(anyString()).stream().content())
                .thenReturn(json == null ? Flux.empty() : Flux.just(json));
    }

    private AgentState newState() {
        return AgentState.of("질문", "v1", "t1", "", RoutingMode.COST_FIRST);
    }

    @Test
    @DisplayName("execute — 유효한 타입 파싱 시 questionType 설정")
    void execute_parsesValidType() {
        stubResponse("{\"question_type\": \"usage\"}");

        AgentState result = service.execute(newState());

        assertThat(result.questionType()).isEqualTo("usage");
    }

    @Test
    @DisplayName("execute — VALID_TYPES 에 없는 타입은 concept 로 폴백")
    void execute_unknownTypeFallsBackToConcept() {
        stubResponse("{\"question_type\": \"unknown_type\"}");

        AgentState result = service.execute(newState());

        assertThat(result.questionType()).isEqualTo("concept");
    }

    @Test
    @DisplayName("execute — 응답이 대문자여도 소문자로 정규화되어 매칭")
    void execute_caseInsensitiveMatch() {
        stubResponse("{\"question_type\": \"USAGE\"}");

        AgentState result = service.execute(newState());

        assertThat(result.questionType()).isEqualTo("usage");
    }

    @Test
    @DisplayName("execute — JSON 파싱 실패(잘못된 형식) 시 concept 로 폴백")
    void execute_malformedJsonFallsBackToConcept() {
        stubResponse("이것은 JSON 이 아닙니다");

        AgentState result = service.execute(newState());

        assertThat(result.questionType()).isEqualTo("concept");
    }

    @Test
    @DisplayName("execute — 빈 응답(스트림 종료 시 buf 비어있음) 시 concept 로 폴백")
    void execute_emptyResponseFallsBackToConcept() {
        stubResponse(null);

        AgentState result = service.execute(newState());

        assertThat(result.questionType()).isEqualTo("concept");
    }

    @Test
    @DisplayName("execute — llmCallCount 는 (0,0) 토큰으로 1회 누적")
    void execute_accumulatesZeroTokenCall() {
        stubResponse("{\"question_type\": \"meta\"}");

        AgentState result = service.execute(newState());

        assertThat(result.llmCallCount()).isEqualTo(1);
        assertThat(result.totalInputTokens()).isZero();
        assertThat(result.totalOutputTokens()).isZero();
    }

    @Test
    @DisplayName("classifyOnly — AgentState 없이 파싱된 타입 문자열만 반환")
    void classifyOnly_returnsRawType() {
        stubResponse("{\"question_type\": \"error\"}");

        String type = service.classifyOnly("질문", Locale.KOREAN);

        assertThat(type).isEqualTo("error");
    }
}
