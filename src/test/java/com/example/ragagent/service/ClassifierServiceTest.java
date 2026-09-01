package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA — ClassifierService question-type parsing (EDIT.md #1).
 *
 * classifyOnly()/execute() now route through LlmRouter.executeGated() (TaskType.TEXT,
 * RoutingMode.COST_FIRST) — previously a directly-injected ChatClient bound to a single
 * boot-time-fixed model, which bypassed both llm_usage tracking and per-request routing.
 * executeGated applies the per-provider concurrency gate since CLASSIFIER is on the
 * interactive chat/query path.
 *
 * Covers: valid type parse, out-of-enum fallback to "concept", malformed-JSON fallback,
 * case-insensitivity, and that execute() accumulates the real input/output token usage
 * returned by executeGatedWithUsage() while setting questionType (classifyOnly()
 * intentionally does not accumulate at all, per CLAUDE.md's documented llmCallCount
 * under-reporting trade-off — it has no AgentState to update).
 */
class ClassifierServiceTest {

    private LlmRouter llmRouter;
    private ClassifierService service;

    @BeforeEach
    void setUp() {
        llmRouter = mock(LlmRouter.class);
        MessageSource messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("prompt");
        AppProperties props = mock(AppProperties.class);
        when(props.llmSafe()).thenReturn(new AppProperties.LlmConfig(
                List.of(), 2, 10, 180, "COST_FIRST", 3, 20, 0.0, 0.1, 0.0, 0.7, true, 6000, true));
        service = new ClassifierService(llmRouter, messageSource, props);
    }

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private void stubResponse(String text) {
        when(llmRouter.executeGated(any(), any(), any())).thenReturn(text);
        when(llmRouter.executeGatedWithUsage(any(), any(), any()))
                .thenReturn(new LlmRouter.LlmResult(text, 0, 0));
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
    @DisplayName("execute — 빈 응답(null) 시 concept 로 폴백")
    void execute_emptyResponseFallsBackToConcept() {
        stubResponse(null);

        AgentState result = service.execute(newState());

        assertThat(result.questionType()).isEqualTo("concept");
    }

    @Test
    @DisplayName("execute — LlmResult의 input/output 토큰이 그대로 누적된다")
    void execute_accumulatesRealTokenUsage() {
        when(llmRouter.executeGatedWithUsage(any(), any(), any()))
                .thenReturn(new LlmRouter.LlmResult("{\"question_type\": \"meta\"}", 120, 30));

        AgentState result = service.execute(newState());

        assertThat(result.llmCallCount()).isEqualTo(1);
        assertThat(result.totalInputTokens()).isEqualTo(120);
        assertThat(result.totalOutputTokens()).isEqualTo(30);
    }

    @Test
    @DisplayName("classifyOnly — AgentState 없이 파싱된 타입 문자열만 반환")
    void classifyOnly_returnsRawType() {
        stubResponse("{\"question_type\": \"error\"}");

        String type = service.classifyOnly("질문", Locale.KOREAN);

        assertThat(type).isEqualTo("error");
    }

    @Test
    @DisplayName("execute/classifyOnly — LlmRouter를 TaskType.TEXT/COST_FIRST로 호출")
    void routesViaTextTaskTypeAndCostFirst() {
        stubResponse("{\"question_type\": \"concept\"}");

        service.execute(newState());
        service.classifyOnly("질문", Locale.KOREAN);

        verify(llmRouter, times(1))
                .executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any());
        verify(llmRouter, times(1))
                .executeGated(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any());
    }

    @Test
    @DisplayName("execute/classifyOnly — 질문이 PromptInjectionGuard.wrap()으로 감싸져 전달됨 (EDIT.md #5)")
    @SuppressWarnings("unchecked")
    void wrapsQuestionInUserQuestionDelimiters() {
        ArgumentCaptor<Function<ChatModel, ChatResponse>> gatedCaptor = ArgumentCaptor.forClass(Function.class);
        ArgumentCaptor<Function<ChatModel, ChatResponse>> gatedWithUsageCaptor = ArgumentCaptor.forClass(Function.class);
        when(llmRouter.executeGated(any(), any(), gatedCaptor.capture()))
                .thenReturn("{\"question_type\": \"concept\"}");
        when(llmRouter.executeGatedWithUsage(any(), any(), gatedWithUsageCaptor.capture()))
                .thenReturn(new LlmRouter.LlmResult("{\"question_type\": \"concept\"}", 0, 0));

        service.execute(newState());
        service.classifyOnly("질문", Locale.KOREAN);

        ChatModel chatModel = mock(ChatModel.class);
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        when(chatModel.call(promptCaptor.capture())).thenReturn(chatResponse("{\"question_type\": \"concept\"}"));
        gatedCaptor.getAllValues().forEach(fn -> fn.apply(chatModel));
        gatedWithUsageCaptor.getAllValues().forEach(fn -> fn.apply(chatModel));

        assertThat(promptCaptor.getAllValues()).hasSize(2).allSatisfy(prompt ->
                assertThat(prompt.getContents()).contains("[USER_QUESTION]").contains("[/USER_QUESTION]"));
    }
}
