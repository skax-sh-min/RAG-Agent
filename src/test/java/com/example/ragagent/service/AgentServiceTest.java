package com.example.ragagent.service;

import com.example.ragagent.agent.AgentGraph;
import com.example.ragagent.agent.AgentState;
import com.example.ragagent.context.ThreadContext;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.model.ChatRequest;
import com.example.ragagent.model.ChatResponse;
import com.example.ragagent.model.SourceRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA — AgentService (entry point, parallel history + classify join)
 *
 * Covers (per refactoring/01-test-safety-net.md):
 *  - chat() 가 memoryService.getHistory + classifierService.classifyOnly 를 병렬 호출 후 합류
 *  - 합류 결과가 AgentState 에 정확히 주입됨
 *  - AgentGraph.run() 의 최종 state 를 ChatResponse 로 매핑
 *  - elapsedSeconds 가 양수
 */
class AgentServiceTest {

    private AgentGraph agentGraph;
    private MemoryService memoryService;
    private ClassifierService classifierService;
    private ConversationSummarizerService summarizerService;
    private AgentService service;

    private static final ThreadContext CTX = ThreadContext.anonymous("t1");

    @BeforeEach
    void setUp() {
        agentGraph = mock(AgentGraph.class);
        memoryService = mock(MemoryService.class);
        classifierService = mock(ClassifierService.class);
        summarizerService = mock(ConversationSummarizerService.class);
        service = new AgentService(agentGraph, memoryService, classifierService, summarizerService);
    }

    private AgentState fullResult() {
        return AgentState.of("질문", "v1", "t1", "", RoutingMode.COST_FIRST)
                .toBuilder()
                .questionType("manual")
                .answer("최종 답변")
                .sources(List.of(new SourceRef("doc.pdf | v1 | p.3", "snippet", "chunk_1", "doc_123", 3)))
                .imageRefs(List.of("data/images/doc_123/img1.png"))
                .usedProvider("gemini-flash")
                .accumulateTokens(120, 80)
                .accumulateTokens(20, 10)
                .build();
    }

    @Test
    @DisplayName("chat() 은 history+classifier 를 병렬 호출하고 결과를 AgentState 에 주입")
    void chat_parallelJoin_setsHistoryAndQuestionType() {
        when(memoryService.getHistory(anyString(), eq("t1"), anyInt(), anyBoolean())).thenReturn("이전 대화");
        when(classifierService.classifyOnly(anyString(), any())).thenReturn("manual");
        when(agentGraph.run(any())).thenReturn(fullResult());

        service.chat(CTX, new ChatRequest("질문", "v1", "t1", RoutingMode.COST_FIRST));

        ArgumentCaptor<AgentState> captor = ArgumentCaptor.forClass(AgentState.class);
        verify(agentGraph, times(1)).run(captor.capture());
        AgentState initial = captor.getValue();

        assertThat(initial.question()).isEqualTo("질문");
        assertThat(initial.version()).isEqualTo("v1");
        assertThat(initial.threadId()).isEqualTo("t1");
        assertThat(initial.conversationHistory()).isEqualTo("이전 대화");
        assertThat(initial.questionType()).isEqualTo("manual");
        assertThat(initial.routingMode()).isEqualTo(RoutingMode.COST_FIRST);

        verify(memoryService, times(1)).getHistory(anyString(), eq("t1"), anyInt(), eq(false));
        verify(classifierService, times(1)).classifyOnly(anyString(), any());
    }

    @Test
    @DisplayName("ChatResponse 매핑 — AgentState 모든 필드가 응답에 정확히 전이")
    void chat_mapsAgentStateToChatResponse() {
        when(memoryService.getHistory(any(), any(), anyInt(), anyBoolean())).thenReturn("");
        when(classifierService.classifyOnly(any(), any())).thenReturn("manual");
        when(agentGraph.run(any())).thenReturn(fullResult());

        ChatResponse resp = service.chat(CTX, new ChatRequest("질문", "v1", "t1", RoutingMode.COST_FIRST));

        assertThat(resp.answer()).isEqualTo("최종 답변");
        assertThat(resp.questionType()).isEqualTo("manual");
        assertThat(resp.sources()).hasSize(1);
        assertThat(resp.imageRefs()).containsExactly("data/images/doc_123/img1.png");
        assertThat(resp.totalInputTokens()).isEqualTo(140);
        assertThat(resp.totalOutputTokens()).isEqualTo(90);
        assertThat(resp.llmCallCount()).isEqualTo(2);
        assertThat(resp.usedProvider()).isEqualTo("gemini-flash");
        assertThat(resp.elapsedSeconds()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    @DisplayName("PROGRESSIVE 모드 응답 — premiumUpgraded 가 ChatResponse 에 노출")
    void chat_progressiveUpgrade_exposesPremiumProvider() {
        AgentState upgradedResult = fullResult().toBuilder().premiumUpgraded("gemini-pro").build();

        when(memoryService.getHistory(any(), any(), anyInt(), anyBoolean())).thenReturn("");
        when(classifierService.classifyOnly(any(), any())).thenReturn("manual");
        when(agentGraph.run(any())).thenReturn(upgradedResult);

        ChatResponse resp = service.chat(CTX, new ChatRequest("질문", "v1", "t1", RoutingMode.PROGRESSIVE));

        assertThat(resp.premiumUpgraded()).isEqualTo("gemini-pro");
    }

    @Test
    @DisplayName("memory 와 classifier 가 진짜 병렬 실행 — 두 호출이 동시에 떠 있어야만 서로를 만난다")
    void chat_runsHistoryAndClassifyInParallel() {
        // 각 호출이 상대를 기다린다: 병렬이면 곧바로 만나고, 직렬이면 먼저 불린 쪽이 상대 없이
        // 타임아웃으로 끝난다. 예전에는 둘을 300ms 씩 재우고 벽시계(520ms 미만)로 판정했는데,
        // 테스트 클래스가 병렬로 도는 전체 실행에서 CPU 가 붐비면 병렬인데도 오버헤드만으로 경계를
        // 넘었다(정확히 520ms 로 실패한 적이 있다).
        CountDownLatch bothInFlight = new CountDownLatch(2);
        AtomicInteger metTheOther = new AtomicInteger();
        when(memoryService.getHistory(any(), any(), anyInt(), anyBoolean())).thenAnswer(inv -> {
            bothInFlight.countDown();
            if (bothInFlight.await(5, TimeUnit.SECONDS)) metTheOther.incrementAndGet();
            return "";
        });
        when(classifierService.classifyOnly(any(), any())).thenAnswer(inv -> {
            bothInFlight.countDown();
            if (bothInFlight.await(5, TimeUnit.SECONDS)) metTheOther.incrementAndGet();
            return "manual";
        });
        when(agentGraph.run(any())).thenReturn(fullResult());

        service.chat(CTX, new ChatRequest("질문", "v1", "t1", RoutingMode.COST_FIRST));

        assertThat(metTheOther.get())
                .as("history 와 classify 가 동시에 떠 있었다 (직렬이면 먼저 불린 쪽이 5초 뒤 상대 없이 끝난다)")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("classifyOnly 가 questionType=null 반환해도 OK (AgentGraph 의 CLASSIFIER 가 처리)")
    void chat_classifyReturnsNull_propagatesToGraph() {
        when(memoryService.getHistory(any(), any(), anyInt(), anyBoolean())).thenReturn("");
        when(classifierService.classifyOnly(any(), any())).thenReturn(null);
        when(agentGraph.run(any())).thenReturn(fullResult());

        service.chat(CTX, new ChatRequest("질문", "v1", "t1", RoutingMode.COST_FIRST));

        ArgumentCaptor<AgentState> captor = ArgumentCaptor.forClass(AgentState.class);
        verify(agentGraph).run(captor.capture());
        assertThat(captor.getValue().questionType()).isNull();
    }

    @Test
    @DisplayName("chat() 호출마다 AgentGraph.run 정확히 1회")
    void chat_invokesGraphOnce() {
        when(memoryService.getHistory(any(), any(), anyInt(), anyBoolean())).thenReturn("");
        when(classifierService.classifyOnly(any(), any())).thenReturn("manual");
        when(agentGraph.run(any())).thenReturn(fullResult());

        AtomicInteger callCount = new AtomicInteger();
        when(agentGraph.run(any())).thenAnswer(inv -> {
            callCount.incrementAndGet();
            return fullResult();
        });

        service.chat(CTX, new ChatRequest("q", "v1", "t1", RoutingMode.COST_FIRST));
        service.chat(CTX, new ChatRequest("q", "v1", "t2", RoutingMode.COST_FIRST));

        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("§6.10 — summarizerService.buildContext() 가 값을 반환하면 그것을 history 로 사용 (getHistory 폴백 안 함)")
    void chat_usesPrecomputedSummaryContext_whenAvailable() {
        when(summarizerService.buildContext(anyString(), eq("t1"), anyInt(), anyBoolean())).thenReturn("[Conversation Summary]\n요약본");
        when(classifierService.classifyOnly(any(), any())).thenReturn("manual");
        when(agentGraph.run(any())).thenReturn(fullResult());

        service.chat(CTX, new ChatRequest("질문", "v1", "t1", RoutingMode.COST_FIRST));

        ArgumentCaptor<AgentState> captor = ArgumentCaptor.forClass(AgentState.class);
        verify(agentGraph).run(captor.capture());
        assertThat(captor.getValue().conversationHistory()).isEqualTo("[Conversation Summary]\n요약본");
        verify(memoryService, never()).getHistory(anyString(), anyString(), anyInt(), anyBoolean());
    }

    @Test
    @DisplayName("§6.10 — summarizerService.buildContext() 가 null 이면 memoryService.getHistory() 로 폴백")
    void chat_fallsBackToRawHistory_whenNoSummaryCached() {
        when(summarizerService.buildContext(anyString(), eq("t1"), anyInt(), anyBoolean())).thenReturn(null);
        when(memoryService.getHistory(anyString(), eq("t1"), anyInt(), anyBoolean())).thenReturn("원본 히스토리");
        when(classifierService.classifyOnly(any(), any())).thenReturn("manual");
        when(agentGraph.run(any())).thenReturn(fullResult());

        service.chat(CTX, new ChatRequest("질문", "v1", "t1", RoutingMode.COST_FIRST));

        ArgumentCaptor<AgentState> captor = ArgumentCaptor.forClass(AgentState.class);
        verify(agentGraph).run(captor.capture());
        assertThat(captor.getValue().conversationHistory()).isEqualTo("원본 히스토리");
    }

    @Test
    @DisplayName("새 turn 저장 후 summarizerService.precomputeAfterTurn() 호출 (답변 완료 직후 요약 재생성 트리거)")
    void chat_precomputesSummary_afterNewTurnPersisted() {
        when(memoryService.getHistory(any(), any(), anyInt(), anyBoolean())).thenReturn("");
        when(classifierService.classifyOnly(any(), any())).thenReturn("manual");
        when(agentGraph.run(any())).thenReturn(fullResult());
        when(memoryService.addTurn(any(), any(), any(), any(), any(),
            anyInt(), anyInt(), anyInt(), any(), anyInt(), any(), any(), anyBoolean())).thenReturn(42L);

        service.chat(CTX, new ChatRequest("질문", "v1", "t1", RoutingMode.COST_FIRST));

        verify(summarizerService, times(1)).precomputeAfterTurn("anonymous", "t1", 42L, Locale.KOREAN);
    }

    /** StreamingAgentService 와 같은 이유 — join() 의 CompletionException 을 풀어 REST 핸들러가 503 을 내게. */
    @Test
    @DisplayName("사전 분류 future 안의 소진은 CompletionException 이 아니라 LlmProviderExhaustedException 으로 나온다")
    void chat_llmExhaustedInsidePreRunFuture_isUnwrapped() {
        when(classifierService.classifyOnly(any(), any()))
                .thenThrow(new com.example.ragagent.exception.LlmProviderExhaustedException("no providers", 4));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.chat(CTX, new ChatRequest("질문", "v1", "t1", RoutingMode.COST_FIRST)))
                .isInstanceOf(com.example.ragagent.exception.LlmProviderExhaustedException.class)
                .satisfies(e -> assertThat(((com.example.ragagent.exception.LlmProviderExhaustedException) e)
                        .retryAfterSeconds()).isEqualTo(4));
        verify(agentGraph, never()).run(any());
    }
}
