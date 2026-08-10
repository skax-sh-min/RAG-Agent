package com.example.ragagent.service;

import com.example.ragagent.agent.AgentGraph;
import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.exception.LlmProviderExhaustedException;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.model.ChatForm;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.MessageSource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA — StreamingAgentService.run() SSE pipeline orchestration (EDIT.md #1)
 *
 * Focuses on observable side effects (memoryService.addTurn, emitter completion,
 * AgentState construction) rather than decoding SSE wire-format bytes — SseEmitter is
 * mocked so no real HTTP response plumbing is needed.
 */
class StreamingAgentServiceTest {

    private AgentGraph agentGraph;
    private MemoryService memoryService;
    private ClassifierService classifierService;
    private ThreadMetaService threadMetaService;
    private MessageSource messageSource;
    private ConversationSummarizerService summarizerService;
    private AppProperties props;
    private SseEmitter emitter;
    private StreamingAgentService service;
    /** Simulated nanosecond clock driving the idle watchdog — advanced explicitly by each test. */
    private AtomicLong fakeNanos;

    /** Moves the watchdog's clock forward by {@code ms} without sleeping. */
    private void advanceMs(long ms) {
        fakeNanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(ms));
    }

    /**
     * Gives the watchdog (a real scheduled task, ~{@code idleTimeout/6} ms period) time to sample
     * the simulated clock at least once. Only affects how many times it runs, never its verdict —
     * that comes solely from {@link #fakeNanos}, so extra delay under load can't flip the result.
     * Used by the "must NOT abort" tests, where a watchdog that never got scheduled is also a pass.
     */
    private static void letWatchdogTick() throws InterruptedException {
        Thread.sleep(250);
    }

    /**
     * Waits for the watchdog to interrupt this (worker) thread. The generous ceiling costs nothing
     * in practice — the interrupt aborts the sleep at the first watchdog tick — but means a
     * scheduler starved by the parallel suite delays the test instead of failing it.
     */
    private static void awaitWatchdogInterrupt() throws InterruptedException {
        Thread.sleep(5_000);
    }

    @BeforeEach
    void setUp() {
        agentGraph = mock(AgentGraph.class);
        memoryService = mock(MemoryService.class);
        classifierService = mock(ClassifierService.class);
        threadMetaService = mock(ThreadMetaService.class);
        messageSource = mock(MessageSource.class);
        summarizerService = mock(ConversationSummarizerService.class);
        props = mock(AppProperties.class);
        when(props.sseIdleTimeoutMs()).thenReturn(120_000L);
        emitter = mock(SseEmitter.class);
        fakeNanos = new AtomicLong(0);
        // Idle-watchdog clock is hand-advanced (see advanceMs) so these tests assert on simulated
        // progress instead of wall-clock time — the suite runs test classes in parallel, where a
        // CPU stall would otherwise be indistinguishable from a genuinely idle pipeline.
        service = new StreamingAgentService(agentGraph, memoryService, classifierService,
                threadMetaService, new ObjectMapper(), messageSource, summarizerService, props,
                new ChatImageAnalysisSkipRegistry(), fakeNanos::get);

        when(memoryService.getHistory(any(), any())).thenReturn("");
        when(classifierService.classifyOnly(any(), any())).thenReturn("usage");
        when(memoryService.addTurn(any(), any(), any(), any(), any(), anyInt(), anyInt(), anyInt(), any(), anyInt(), any(), any(), anyBoolean()))
                .thenReturn(42L);
    }

    private ChatForm form(boolean directMode, String tags) {
        return form(directMode, tags, null); // responseMode 미지정 → ResponseMode.DEFAULT(M)
    }

    private ChatForm form(boolean directMode, String tags, String responseMode) {
        return new ChatForm("질문", "t1", "v1", "COST_FIRST", directMode, tags, responseMode);
    }

    private AgentState resultState(String answer) {
        return AgentState.of("질문", "v1", "t1", "u1", "", RoutingMode.COST_FIRST, false, Locale.KOREAN)
                .toBuilder().answer(answer).usedProvider("local").build();
    }

    @Test
    @DisplayName("정상 흐름 — 답변 있으면 addTurn 저장 + emitter.complete + 제목 생성 트리거")
    void run_happyPath_persistsTurnAndCompletes() {
        when(agentGraph.runStreaming(any(), any())).thenReturn(resultState("최종 답변"));

        service.run("u1", form(false, null), emitter);

        verify(memoryService).addTurn(eq("u1"), eq("t1"), eq("질문"), eq("최종 답변"),
            anyString(), eq(0), eq(0), anyInt(), eq("local"), anyInt(), eq("M"), any(), eq(false));
        verify(summarizerService).precomputeAfterTurn(eq("u1"), eq("t1"), eq(42L), any());
        verify(emitter).complete();
        verify(emitter, never()).completeWithError(any());
        verify(threadMetaService).generateTitleAsync(eq("u1"), eq("t1"), eq("v1"), eq("질문"));
    }

    @Test
    @DisplayName("directMode=true — classifier 스킵, AgentState.directMode=true 로 그래프 실행")
    void run_directMode_skipsClassifierAndSetsDirectMode() {
        ArgumentCaptor<AgentState> stateCaptor = ArgumentCaptor.forClass(AgentState.class);
        when(agentGraph.runStreaming(stateCaptor.capture(), any())).thenReturn(resultState("답변"));

        service.run("u1", form(true, null), emitter);

        verify(classifierService, never()).classifyOnly(any(), any());
        assertThat(stateCaptor.getValue().directMode()).isTrue();
    }

    @Test
    @DisplayName("일반 RAG 모드 — classifier 호출 결과가 초기 AgentState.questionType 에 반영")
    void run_normalMode_setsQuestionTypeFromClassifier() {
        ArgumentCaptor<AgentState> stateCaptor = ArgumentCaptor.forClass(AgentState.class);
        when(agentGraph.runStreaming(stateCaptor.capture(), any())).thenReturn(resultState("답변"));

        service.run("u1", form(false, null), emitter);

        verify(classifierService).classifyOnly(eq("질문"), any());
        assertThat(stateCaptor.getValue().questionType()).isEqualTo("usage");
        assertThat(stateCaptor.getValue().directMode()).isFalse();
    }

    @Test
    @DisplayName("선택된 태그가 AgentState.selectedTags 로 전달됨")
    void run_carriesSelectedTagsIntoState() {
        ArgumentCaptor<AgentState> stateCaptor = ArgumentCaptor.forClass(AgentState.class);
        when(agentGraph.runStreaming(stateCaptor.capture(), any())).thenReturn(resultState("답변"));

        service.run("u1", form(false, "faq,guide"), emitter);

        assertThat(stateCaptor.getValue().selectedTags()).containsExactly("faq", "guide");
    }

    @Test
    @DisplayName("응답 모드(S/M/L)가 그래프 상태로 전달된다")
    void run_carriesResponseModeIntoState() {
        ArgumentCaptor<AgentState> stateCaptor = ArgumentCaptor.forClass(AgentState.class);
        when(agentGraph.runStreaming(stateCaptor.capture(), any())).thenReturn(resultState("답변"));

        service.run("u1", form(false, null, "L"), emitter);

        assertThat(stateCaptor.getValue().responseMode())
                .isEqualTo(com.example.ragagent.model.ResponseMode.L);
    }

    @Test
    @DisplayName("응답 모드가 없거나 알 수 없는 값이면 기본값 M으로 전달된다")
    void run_responseModeDefaultsToM() {
        ArgumentCaptor<AgentState> stateCaptor = ArgumentCaptor.forClass(AgentState.class);
        when(agentGraph.runStreaming(stateCaptor.capture(), any())).thenReturn(resultState("답변"));

        service.run("u1", form(false, null, "XL"), emitter);

        assertThat(stateCaptor.getValue().responseMode())
                .isEqualTo(com.example.ragagent.model.ResponseMode.M);
    }

    @Test
    @DisplayName("답변이 비어있으면 addTurn 호출 안 함 (요약 재생성 트리거도 스킵)")
    void run_blankAnswer_doesNotPersistTurn() {
        when(agentGraph.runStreaming(any(), any())).thenReturn(resultState(null));

        service.run("u1", form(false, null), emitter);

        verify(memoryService, never())
            .addTurn(any(), any(), any(), any(), any(), anyInt(), anyInt(), anyInt(), any(), anyInt(), any(), any(), anyBoolean());
        verify(summarizerService, never()).precomputeAfterTurn(any(), any(), any(), any());
        verify(emitter).complete();
    }

    @Test
    @DisplayName("LlmProviderExhaustedException — complete() 로 마무리(에러 완료 아님), 안내 메시지 전송")
    void run_llmExhausted_completesGracefullyWithFriendlyMessage() {
        when(agentGraph.runStreaming(any(), any()))
                .thenThrow(new LlmProviderExhaustedException("no providers"));
        when(messageSource.getMessage(eq("error.llm.exhausted"), any(), anyString(), any(Locale.class)))
                .thenReturn("LLM 서버에 연결할 수 없습니다.");

        service.run("u1", form(false, null), emitter);

        verify(emitter).complete();
        verify(emitter, never()).completeWithError(any());
    }

    @Test
    @DisplayName("일반 예외 + 부분 답변 존재 — 부분 답변에 중단 문구 붙여 저장 후 completeWithError")
    void run_genericError_withPartialAnswer_persistsPartialAndCompletesWithError() {
        RuntimeException boom = new RuntimeException("boom");
        when(agentGraph.runStreaming(any(), any())).thenAnswer(inv -> {
            GraphListener listener = inv.getArgument(1);
            listener.onToken("부분 답변");
            throw boom;
        });

        service.run("u1", form(false, null), emitter);

        verify(memoryService).addTurn(eq("u1"), eq("t1"), eq("질문"), eq("부분 답변\n[오류로 중단됨]"),
            anyString(), eq(0), eq(0), eq(0), isNull(), eq(0), eq("M"), any(), eq(false));
        verify(emitter).completeWithError(boom);
        verify(emitter, never()).complete();
    }

    @Test
    @DisplayName("일반 예외 + 부분 답변 없음 — addTurn 호출 안 하고 completeWithError")
    void run_genericError_withoutPartialAnswer_doesNotPersist() {
        RuntimeException boom = new RuntimeException("boom");
        when(agentGraph.runStreaming(any(), any())).thenThrow(boom);

        service.run("u1", form(false, null), emitter);

        verify(memoryService, never())
            .addTurn(any(), any(), any(), any(), any(), anyInt(), anyInt(), anyInt(), any(), anyInt(), any(), any(), anyBoolean());
        verify(emitter).completeWithError(boom);
    }

    // ── images 이벤트 (RAG 관련 이미지 썸네일) ───────────────────────────────

    @Test
    @DisplayName("images 이벤트 — imageRefs가 비어있으면 emitter.send() 추가 호출 없음")
    void run_onImagesReady_emptyList_sendsNoExtraEvent() throws Exception {
        when(agentGraph.runStreaming(any(), any())).thenAnswer(inv -> {
            GraphListener listener = inv.getArgument(1);
            listener.onImagesReady(List.of());
            return resultState("답변");
        });

        service.run("u1", form(false, null), emitter);

        // "done" 이벤트 1건만 — images 이벤트는 추가되지 않아야 한다.
        verify(emitter, org.mockito.Mockito.times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("images 이벤트 — imageRefs가 있으면 emitter.send()가 1회 더 호출됨")
    void run_onImagesReady_nonEmptyList_sendsExtraEvent() throws Exception {
        when(agentGraph.runStreaming(any(), any())).thenAnswer(inv -> {
            GraphListener listener = inv.getArgument(1);
            listener.onImagesReady(List.of("images/doc1/p1_img1.png"));
            return resultState("답변");
        });

        service.run("u1", form(false, null), emitter);

        // "done" 이벤트 + "images" 이벤트 = 2건.
        verify(emitter, org.mockito.Mockito.times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    // ── verifying 이벤트 (스트리밍 종료 ~ sufficiency evaluate() 사이 무음 구간 표시) ──────

    @Test
    @DisplayName("onVerifying() — done 이벤트 외에 verifying 이벤트가 추가로 1회 전송된다")
    void run_onVerifying_sendsExtraEvent() throws Exception {
        when(agentGraph.runStreaming(any(), any())).thenAnswer(inv -> {
            GraphListener listener = inv.getArgument(1);
            listener.onVerifying();
            return resultState("답변");
        });

        service.run("u1", form(false, null), emitter);

        // "done" 이벤트 + "verifying" 이벤트 = 2건.
        verify(emitter, org.mockito.Mockito.times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    // ── Idle watchdog ───────────────────────────────────────────────────────
    // props.sseIdleTimeoutMs() is deliberately small here so the watchdog's own background
    // scheduled thread genuinely fires (and interrupts the calling thread, since run() is
    // invoked synchronously in these tests) within the test's timeout — no mocking of the
    // interrupt mechanism itself.

    @Test
    @DisplayName("idle watchdog — 진행 신호 없이 idle timeout 경과 시 실행 스레드를 인터럽트")
    void run_idleTimeout_interruptsWorkerWhenNoProgress() {
        when(props.sseIdleTimeoutMs()).thenReturn(600L);
        when(agentGraph.runStreaming(any(), any())).thenAnswer(inv -> {
            advanceMs(5_000);   // no listener activity — simulated clock alone puts us far past idle
            awaitWatchdogInterrupt();
            return resultState("답변");
        });

        service.run("u1", form(false, null), emitter);

        assertThat(Thread.currentThread().isInterrupted())
                .as("idle watchdog should have interrupted the worker thread").isTrue();
        Thread.interrupted(); // clear so the flag doesn't leak into later tests
        verify(emitter).completeWithError(any(InterruptedException.class));
        verify(emitter, never()).complete();
    }

    @Test
    @DisplayName("idle watchdog — 토큰이 계속 도착하면 누적 시간이 idle timeout을 넘겨도 인터럽트되지 않음")
    void run_activeProgress_doesNotTriggerIdleTimeout() throws Exception {
        when(props.sseIdleTimeoutMs()).thenReturn(600L);
        when(agentGraph.runStreaming(any(), any())).thenAnswer(inv -> {
            GraphListener listener = inv.getArgument(1);
            // 10 × 200ms = 2,000ms total, far past the 600ms idle window, but no single gap
            // reaches it — each token resets the clock, which is exactly what must not abort.
            for (int i = 0; i < 10; i++) {
                advanceMs(200);
                listener.onToken("chunk" + i);
            }
            letWatchdogTick();   // watchdog must sample and still find the stream healthy
            return resultState("최종 답변");
        });

        service.run("u1", form(false, null), emitter);

        assertThat(Thread.currentThread().isInterrupted())
                .as("active token stream (total > idle timeout) must not be cut off").isFalse();
        verify(emitter).complete();
        verify(emitter, never()).completeWithError(any());
    }

    @Test
    @DisplayName("idle watchdog — onVerifying() 호출도 진행 신호로 간주되어 idle timeout을 리셋한다")
    void run_onVerifying_resetsIdleTimeout() {
        when(props.sseIdleTimeoutMs()).thenReturn(600L);
        when(agentGraph.runStreaming(any(), any())).thenAnswer(inv -> {
            GraphListener listener = inv.getArgument(1);
            advanceMs(400);          // under the 600ms idle window
            listener.onVerifying();  // resets the clock — the slow evaluate() call this represents
            advanceMs(400);          // 400+400 > 600 would trip the watchdog without that reset
            letWatchdogTick();
            return resultState("답변");
        });

        service.run("u1", form(false, null), emitter);

        assertThat(Thread.currentThread().isInterrupted())
                .as("onVerifying() should count as progress, not idle").isFalse();
        verify(emitter).complete();
        verify(emitter, never()).completeWithError(any());
    }
}
