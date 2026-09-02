package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.exception.LlmContextOverflowException;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.ai.document.Document;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * §6.26-9 축소 후 재시도 — 사전 예산이 빗나갔을 때의 자동 복구.
 *
 * <p>사전 축소({@code fitToBudget})는 {@code TokenEstimator} 의 <b>추정</b> 위에 서 있고, 창을 아예
 * 모르는 배포에서는 아무것도 자르지 않는다. 그 두 경우에 프롬프트가 서버 컨텍스트를 넘으면 예전에는
 * 사용자가 답변 대신 {@code RAG-LLM-003} 을 받았다 — 문서 몇 개만 덜어냈으면 답할 수 있었는데도.
 *
 * <p>여기서 고정하는 것은 세 가지다: <b>절반씩</b> 줄여 왕복을 log(n) 으로 묶는다는 것, 줄인 사실이
 * <b>사용자에게 그대로 간다</b>는 것(안내 문구가 실제로 보낸 프롬프트와 맞아야 한다), 그리고 재시도가
 * 만들어 낸 축소가 <b>검증 판정의 신뢰도를 낮춘다</b>는 것 — 답을 받아 내는 것과 그 답을 곧이곧대로
 * 믿는 것은 다른 문제다.
 */
@ResourceLock("global-state")
class AnswerServiceShrinkRetryTest {

    private LlmRouter llmRouter;
    private ProviderContextWindows contextWindows;
    private AnswerService service;

    @BeforeEach
    void setUp() {
        llmRouter = mock(LlmRouter.class);
        AppProperties props = new AppProperties(
                "./data", 2, 800, 100, 100, 7, 0.0, true, 0, false,
                true, false, 3,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        contextWindows = new ProviderContextWindows();   // 창 모름 — 사전 축소가 아예 돌지 않는 배포
        MessageSource messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("prompt");
        service = new AnswerService(llmRouter, props, messageSource, contextWindows);
        when(llmRouter.findProviderName(any(), any())).thenReturn("lm");
    }

    private static Document koreanDoc(String marker, int chars) {
        return new Document(marker + "가".repeat(chars));
    }

    private static AgentState stateWith(int docCount) {
        List<Document> docs = new java.util.ArrayList<>();
        for (int i = 1; i <= docCount; i++) docs.add(koreanDoc("문서" + i + "-", 500));
        return AgentState.of("질문", "v1", "t1", "", RoutingMode.COST_FIRST)
                .toBuilder().retrievedDocs(docs).build();
    }

    private static RuntimeException overflow() {
        return new LlmContextOverflowException("lm",
                new IllegalStateException("Context size has been exceeded."));
    }

    /** 서버가 날것으로 던지는 형태 — 스트리밍 경로는 라우터를 거치지 않아 변환되지 않는다. */
    private static RuntimeException rawOverflow() {
        return new IllegalStateException("prompt is too long: 9000 tokens > 8192 maximum");
    }

    /**
     * 답변 호출은 {@code answerCalls} 번째까지 초과로 실패하고, 그 뒤로는 성공한다.
     * 검증 호출(2번째 이후)은 항상 통과시켜 답변 경로만 관찰한다.
     */
    private AtomicReference<String> stubAnswerFailingFirst(int failures) {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> lastAnswerPrompt = new AtomicReference<>();
        boolean[] isAnswer = {false};
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), any(), any())).thenAnswer(inv -> {
            java.util.function.Function<org.springframework.ai.chat.model.ChatModel,
                    org.springframework.ai.chat.model.ChatResponse> fn = inv.getArgument(2);
            var probe = mock(org.springframework.ai.chat.model.ChatModel.class);
            when(probe.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenAnswer(call -> {
                org.springframework.ai.chat.prompt.Prompt prompt = call.getArgument(0);
                String text = prompt.getInstructions().stream()
                        .map(org.springframework.ai.chat.messages.Message::getText)
                        .reduce("", (a, b) -> a + "\n" + b);
                if (text.contains("[검색된 문서]")) {           // 답변 호출
                    if (calls.incrementAndGet() <= failures) throw overflow();
                    lastAnswerPrompt.set(text);
                    isAnswer[0] = true;
                } else {
                    isAnswer[0] = false;                       // 검증 호출
                }
                return new org.springframework.ai.chat.model.ChatResponse(List.of(
                        new org.springframework.ai.chat.model.Generation(
                                new org.springframework.ai.chat.messages.AssistantMessage("답변"))));
            });
            fn.apply(probe);
            return new LlmRouter.LlmResult(
                    isAnswer[0] ? "답변" : "{\"sufficient\":true,\"grounded\":true}", 0, 0);
        });
        return lastAnswerPrompt;
    }

    @Test
    @DisplayName("컨텍스트 초과 한 번 — 문서를 절반으로 줄여 다시 시도하고 답변을 낸다")
    void oneOverflowHalvesTheDocumentsAndSucceeds() {
        AtomicReference<String> prompt = stubAnswerFailingFirst(1);

        AgentState result = service.execute(stateWith(8));

        assertThat(result.answer()).isEqualTo("답변");
        // 8 → 4. 앞쪽(관련도 높은 쪽)이 남는다.
        assertThat(prompt.get()).contains("문서1-", "문서4-");
        assertThat(prompt.get()).doesNotContain("문서5-", "문서8-");
    }

    @Test
    @DisplayName("절반씩 줄인다 — 세 번 초과하면 8→4→2→1 로 내려간다(하나씩이 아니라)")
    void shrinkIsBinaryNotOneByOne() {
        AtomicReference<String> prompt = stubAnswerFailingFirst(3);

        service.execute(stateWith(8));

        assertThat(prompt.get()).contains("문서1-");
        assertThat(prompt.get()).doesNotContain("문서2-");
    }

    @Test
    @DisplayName("줄인 사실은 사용자에게 그대로 간다 — 안내 문구가 실제로 보낸 문서 수를 말한다")
    void theUserIsToldWhatTheRetryActuallySent() {
        stubAnswerFailingFirst(1);

        AgentState result = service.execute(stateWith(8));

        assertThat(result.budgetNote())
                .as("0단계 기준으로 안내하면 화면과 모델이 본 것이 어긋난다")
                .isEqualTo("컨텍스트 한도로 검색된 문서 8개 중 4개만 사용했습니다.");
    }

    @Test
    @DisplayName("상한을 넘으면 포기하고 RAG-LLM-003 을 그대로 올린다 — 결정적 실패에 왕복만 쓰지 않는다")
    void givesUpAfterTheShrinkLimit() {
        stubAnswerFailingFirst(99);

        assertThatThrownBy(() -> service.execute(stateWith(8)))
                .isInstanceOf(LlmContextOverflowException.class);
    }

    @Test
    @DisplayName("문서가 하나뿐이고 이력도 없으면 재시도하지 않는다 — 반으로 나눠도 같은 요청이다")
    void doesNotRetryWhenThereIsNothingLeftToHalve() {
        AtomicInteger answerCalls = new AtomicInteger();
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), any(), any())).thenAnswer(inv -> {
            answerCalls.incrementAndGet();
            throw overflow();
        });

        assertThatThrownBy(() -> service.execute(stateWith(1)))
                .isInstanceOf(LlmContextOverflowException.class);
        assertThat(answerCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("초과가 아닌 실패는 재시도하지 않는다 — 진짜 오류를 축소로 덮지 않는다")
    void onlyContextOverflowIsRetried() {
        AtomicInteger calls = new AtomicInteger();
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), any(), any())).thenAnswer(inv -> {
            calls.incrementAndGet();
            throw new IllegalStateException("connection reset");
        });

        assertThatThrownBy(() -> service.execute(stateWith(8)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("connection reset");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("라우터를 거치지 않는 날것의 초과 메시지도 알아본다 — 스트리밍 경로가 그 모양이다")
    void recognizesRawOverflowNotWrappedByTheRouter() {
        AtomicInteger calls = new AtomicInteger();
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), any(), any())).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) throw rawOverflow();
            return new LlmRouter.LlmResult("{\"sufficient\":true,\"grounded\":true}", 0, 0);
        });

        AgentState result = service.execute(stateWith(8));

        assertThat(result.answer()).isNotBlank();
        assertThat(calls.get()).as("1회 실패 + 답변 재시도 + 검증").isGreaterThanOrEqualTo(2);
    }

    // ── 검증 호출 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("검증도 축소해 다시 시도한다 — 이 앱 최대의 단일 요청이라 여기서 초과가 가장 잦다")
    void evaluationIsRetriedWithFewerExcerpts() {
        AtomicInteger evalCalls = new AtomicInteger();
        stubAnswerThenEval(text -> {
            if (evalCalls.incrementAndGet() == 1) throw overflow();
            return "{\"sufficient\":true,\"grounded\":true}";
        });

        AgentState result = service.execute(stateWith(8));

        assertThat(evalCalls.get()).as("초과 1회 + 축소 후 성공 1회").isEqualTo(2);
        assertThat(result.answer()).isEqualTo("답변");
    }

    @Test
    @DisplayName("축소해서 받아 낸 '근거 없음'은 판정으로 삼지 않는다 — 빠진 문서에 근거가 있었을 수 있다")
    void aNegativeVerdictFromShrunkExcerptsIsDiscarded() {
        AtomicInteger evalCalls = new AtomicInteger();
        stubAnswerThenEval(text -> {
            if (evalCalls.incrementAndGet() == 1) throw overflow();
            return "{\"sufficient\":true,\"grounded\":false}";
        });

        AgentState result = service.execute(stateWith(8));

        assertThat(result.grounded())
                .as("재시도가 답을 받아 냈다고 그 답을 곧이곧대로 믿으면 .limit(5) 사고가 되살아난다")
                .isNull();
        assertThat(result.needsRetry()).as("sufficient 는 살린다").isFalse();
    }

    /** 답변은 늘 성공, 검증 응답만 호출부가 정한다. */
    private void stubAnswerThenEval(java.util.function.Function<String, String> evalResponse) {
        boolean[] isAnswer = {false};
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), any(), any())).thenAnswer(inv -> {
            java.util.function.Function<org.springframework.ai.chat.model.ChatModel,
                    org.springframework.ai.chat.model.ChatResponse> fn = inv.getArgument(2);
            var probe = mock(org.springframework.ai.chat.model.ChatModel.class);
            String[] evalText = {null};
            when(probe.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenAnswer(call -> {
                org.springframework.ai.chat.prompt.Prompt prompt = call.getArgument(0);
                String text = prompt.getInstructions().stream()
                        .map(org.springframework.ai.chat.messages.Message::getText)
                        .reduce("", (a, b) -> a + "\n" + b);
                isAnswer[0] = text.contains("[검색된 문서]");
                if (!isAnswer[0]) evalText[0] = evalResponse.apply(text);
                return new org.springframework.ai.chat.model.ChatResponse(List.of(
                        new org.springframework.ai.chat.model.Generation(
                                new org.springframework.ai.chat.messages.AssistantMessage("답변"))));
            });
            fn.apply(probe);
            return new LlmRouter.LlmResult(isAnswer[0] ? "답변" : evalText[0], 0, 0);
        });
    }
}
