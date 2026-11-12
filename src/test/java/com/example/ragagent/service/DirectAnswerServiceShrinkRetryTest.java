package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.exception.LlmContextOverflowException;
import com.example.ragagent.llm.LlmProvider;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ProviderRole;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.MessageSource;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Direct 경로의 축소 후 재시도 — 사전 예산({@code withFittedHistory})이 빗나가 서버가 컨텍스트 초과로 거절하면
 * 이력을 사다리({@code PromptLadder} 의 이력 칸과 같은 순서)의 다음 걸음으로 줄여 다시 보낸다.
 */
@ResourceLock("global-state")
class DirectAnswerServiceShrinkRetryTest {

    private static final String LONG_Q = "가".repeat(1200);
    /** 오래된 턴의 긴 질문 + 중간 턴 + 직전 턴. */
    private static final String HISTORY =
            "Q: " + LONG_Q + "\nA: 답1\n\nQ: 질문2\nA: 답2\n\nQ: 질문3\nA: 답3";

    private LlmRouter llmRouter;
    private DirectAnswerService service;

    @BeforeEach
    void setUp() {
        llmRouter = mock(LlmRouter.class);
        MessageSource messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("prompt");
        AppProperties props = mock(AppProperties.class);
        when(props.llmSafe()).thenReturn(new AppProperties.LlmConfig(
                List.of(), 2, 10, 180, "COST_FIRST", 3, 20, 0.0, 0.1, 0.0, 0.7, true, 6000, 1, true));
        service = new DirectAnswerService(llmRouter, messageSource, props);
    }

    private static AgentState state(String history) {
        return AgentState.of("질문", "v1", "t1", "", RoutingMode.COST_FIRST, true)
                .toBuilder().conversationHistory(history).build();
    }

    private static LlmContextOverflowException overflow() {
        return new LlmContextOverflowException("local", new RuntimeException("exceeds the available context size"));
    }

    // ── 사다리 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("사다리 — 과거 질문 자르기 → 직전 1턴만 → 이력 전부, 걸음마다 짧아진다")
    void ladder_order() {
        List<String> steps = DirectAnswerService.historyShrinkSteps(HISTORY);

        assertThat(steps).hasSize(3);
        assertThat(steps.get(0)).contains("…(생략)").contains("Q: 질문2").contains("Q: 질문3")
                .hasSizeLessThan(HISTORY.length());
        assertThat(steps.get(1)).isEqualTo("Q: 질문3\nA: 답3");
        assertThat(steps.get(2)).isEmpty();
    }

    @Test
    @DisplayName("사다리 — 앞 걸음과 같아지는 칸은 건너뛴다(짧은 턴 하나면 '이력 전부 빼기'뿐)")
    void ladder_skipsNoOpSteps() {
        assertThat(DirectAnswerService.historyShrinkSteps("Q: 짧다\nA: 답")).containsExactly("");
        assertThat(DirectAnswerService.historyShrinkSteps("")).isEmpty();
        assertThat(DirectAnswerService.historyShrinkSteps(null)).isEmpty();
    }

    // ── 블로킹 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("블로킹 — 컨텍스트 초과면 이력을 줄여 다시 보내고, 줄였다는 안내가 남는다")
    @SuppressWarnings("unchecked")
    void blocking_retriesWithShorterHistory() {
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                .thenThrow(overflow())
                .thenReturn(new LlmRouter.LlmResult("답변", 10, 5));

        AgentState result = service.execute(state(HISTORY));

        assertThat(result.answer()).isEqualTo("답변");
        verify(llmRouter, times(2)).executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any());
        assertThat(result.conversationHistory()).contains("…(생략)").hasSizeLessThan(HISTORY.length());
        assertThat(result.budgetNote()).isNotBlank();
    }

    @Test
    @DisplayName("블로킹 — 매번 초과면 사다리를 다 내려간 뒤 원래 예외를 던진다(걸음 수 + 1 번)")
    void blocking_exhaustsLadderThenThrows() {
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                .thenThrow(overflow());

        assertThatThrownBy(() -> service.execute(state(HISTORY))).isInstanceOf(LlmContextOverflowException.class);

        verify(llmRouter, times(DirectAnswerService.historyShrinkSteps(HISTORY).size() + 1))
                .executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any());
    }

    @Test
    @DisplayName("블로킹 — 초과가 아닌 오류는 다시 시도하지 않는다")
    void blocking_otherErrorsAreNotRetried() {
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                .thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> service.execute(state(HISTORY))).isInstanceOf(IllegalStateException.class);

        verify(llmRouter, times(1)).executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any());
    }

    @Test
    @DisplayName("블로킹 — 이력이 비었으면 줄일 것이 없어 같은 요청을 되풀이하지 않는다")
    void blocking_noHistoryNoRetry() {
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                .thenThrow(overflow());

        assertThatThrownBy(() -> service.execute(state(""))).isInstanceOf(LlmContextOverflowException.class);

        verify(llmRouter, times(1)).executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any());
    }

    // ── 스트리밍 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("스트리밍 — 첫 토큰 전 초과면 이력을 줄여 다시 열고, 창 재탐지를 시도마다 건다")
    void streaming_retriesBeforeFirstToken() {
        ChatModel chatModel = com.example.ragagent.LlmTestSupport.chatModelMock();
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.error(overflow()))
                .thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("답"))))));
        LlmProvider provider = new LlmProvider(
                "local", TaskType.TEXT, ProviderRole.LOCAL, 0, "key", null, "model", false, chatModel, null);
        when(llmRouter.routeProvider(eq(TaskType.TEXT), any())).thenReturn(provider);

        List<String> tokens = new ArrayList<>();
        GraphListener listener = new GraphListener() {
            @Override public void onToken(String text) { tokens.add(text); }
        };

        AgentState result = service.executeStreaming(state(HISTORY), listener);

        assertThat(result.answer()).isEqualTo("답");
        assertThat(String.join("", tokens)).isEqualTo("답");
        assertThat(result.budgetNote()).isNotBlank();
        assertThat(result.conversationHistory()).hasSizeLessThan(HISTORY.length());
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).stream(prompts.capture());
        String first = prompts.getAllValues().get(0).getUserMessage().getText();
        String second = prompts.getAllValues().get(1).getUserMessage().getText();
        assertThat(second.length()).isLessThan(first.length());
        verify(llmRouter).contextOverflowObserved(provider);
    }

    @Test
    @DisplayName("스트리밍 — 토큰이 하나라도 나간 뒤의 오류는 다시 보내지 않는다(앞부분이 두 번 찍힌다)")
    void streaming_noRetryAfterTokenEmitted() {
        OpenAiChatModel raw = mock(OpenAiChatModel.class);
        when(raw.stream(any(Prompt.class))).thenReturn(Flux.concat(
                Flux.just(com.example.ragagent.LlmTestSupport.answer("안")),
                // 첫 청크가 소비자에게 닿은 뒤에 오류가 오도록 늦춘다 — 업스트림 오류가 큐에 쌓인 청크를 앞지르는 경주를 피한다
                Flux.<ChatResponse>error(overflow()).delaySubscription(java.time.Duration.ofMillis(300))));
        LlmProvider provider = new LlmProvider(
                "openai", TaskType.TEXT, ProviderRole.NORMAL, 1, "key", null, "gpt-4o", true, null, raw);
        when(llmRouter.routeProvider(eq(TaskType.TEXT), any())).thenReturn(provider);

        assertThatThrownBy(() -> service.executeStreaming(state(HISTORY), new GraphListener() { }))
                .isInstanceOf(RuntimeException.class);

        verify(raw, times(1)).stream(any(Prompt.class));
    }
}
