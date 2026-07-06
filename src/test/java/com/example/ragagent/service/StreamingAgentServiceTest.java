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

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
        service = new StreamingAgentService(agentGraph, memoryService, classifierService,
                threadMetaService, new ObjectMapper(), messageSource, summarizerService, props);

        when(memoryService.getHistory(any(), any())).thenReturn("");
        when(classifierService.classifyOnly(any(), any())).thenReturn("usage");
        when(memoryService.addTurn(any(), any(), any(), any(), any(), anyInt(), anyInt(), anyInt(), any(), anyInt()))
                .thenReturn(42L);
    }

    private ChatForm form(boolean directMode, String tags) {
        return new ChatForm("질문", "t1", "v1", "COST_FIRST", directMode, tags);
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
                anyString(), eq(0), eq(0), anyInt(), eq("local"), anyInt());
        verify(summarizerService).invalidate("t1");
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
    @DisplayName("답변이 비어있으면 addTurn 호출 안 함 (요약 캐시 무효화도 스킵)")
    void run_blankAnswer_doesNotPersistTurn() {
        when(agentGraph.runStreaming(any(), any())).thenReturn(resultState(null));

        service.run("u1", form(false, null), emitter);

        verify(memoryService, never())
                .addTurn(any(), any(), any(), any(), any(), anyInt(), anyInt(), anyInt(), any(), anyInt());
        verify(summarizerService, never()).invalidate(any());
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
                anyString(), eq(0), eq(0), eq(0), isNull(), eq(0));
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
                .addTurn(any(), any(), any(), any(), any(), anyInt(), anyInt(), anyInt(), any(), anyInt());
        verify(emitter).completeWithError(boom);
    }

    // ── Idle watchdog ───────────────────────────────────────────────────────
    // props.sseIdleTimeoutMs() is deliberately small here so the watchdog's own background
    // scheduled thread genuinely fires (and interrupts the calling thread, since run() is
    // invoked synchronously in these tests) within the test's timeout — no mocking of the
    // interrupt mechanism itself.

    @Test
    @DisplayName("idle watchdog — 진행 신호 없이 idle timeout 경과 시 실행 스레드를 인터럽트")
    void run_idleTimeout_interruptsWorkerWhenNoProgress() {
        when(props.sseIdleTimeoutMs()).thenReturn(100L);
        when(agentGraph.runStreaming(any(), any())).thenAnswer(inv -> {
            Thread.sleep(1000); // no listener activity during this — watchdog should fire well before 1s
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
        when(props.sseIdleTimeoutMs()).thenReturn(300L);
        when(agentGraph.runStreaming(any(), any())).thenAnswer(inv -> {
            GraphListener listener = inv.getArgument(1);
            for (int i = 0; i < 10; i++) {
                Thread.sleep(60); // well under the 300ms idle timeout — resets the clock each time
                listener.onToken("chunk" + i);
            }
            return resultState("최종 답변");
        });

        service.run("u1", form(false, null), emitter);

        assertThat(Thread.currentThread().isInterrupted())
                .as("active token stream (total > idle timeout) must not be cut off").isFalse();
        verify(emitter).complete();
        verify(emitter, never()).completeWithError(any());
    }
}
