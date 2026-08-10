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
        when(memoryService.getHistory(anyString(), eq("t1"))).thenReturn("이전 대화");
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

        verify(memoryService, times(1)).getHistory(anyString(), eq("t1"));
        verify(classifierService, times(1)).classifyOnly(anyString(), any());
    }

    @Test
    @DisplayName("ChatResponse 매핑 — AgentState 모든 필드가 응답에 정확히 전이")
    void chat_mapsAgentStateToChatResponse() {
        when(memoryService.getHistory(any(), any())).thenReturn("");
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

        when(memoryService.getHistory(any(), any())).thenReturn("");
        when(classifierService.classifyOnly(any(), any())).thenReturn("manual");
        when(agentGraph.run(any())).thenReturn(upgradedResult);

        ChatResponse resp = service.chat(CTX, new ChatRequest("질문", "v1", "t1", RoutingMode.PROGRESSIVE));

        assertThat(resp.premiumUpgraded()).isEqualTo("gemini-pro");
    }

    @Test
    @DisplayName("memory 와 classifier 가 진짜 병렬 실행 (각각 200ms sleep 도 총 시간 ~200ms)")
    void chat_runsHistoryAndClassifyInParallel() {
        when(memoryService.getHistory(any(), any())).thenAnswer(inv -> {
            Thread.sleep(300);
            return "";
        });
        when(classifierService.classifyOnly(any(), any())).thenAnswer(inv -> {
            Thread.sleep(300);
            return "manual";
        });
        when(agentGraph.run(any())).thenReturn(fullResult());

        long start = System.nanoTime();
        service.chat(CTX, new ChatRequest("질문", "v1", "t1", RoutingMode.COST_FIRST));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // 직렬이면 ~600ms+, 병렬이면 ~300-450ms. 520ms 미만이면 병렬 실행으로 본다.
        assertThat(elapsedMs)
                .as("history + classify 병렬 실행 (직렬이었다면 600ms 초과)")
                .isLessThan(520);
    }

    @Test
    @DisplayName("classifyOnly 가 questionType=null 반환해도 OK (AgentGraph 의 CLASSIFIER 가 처리)")
    void chat_classifyReturnsNull_propagatesToGraph() {
        when(memoryService.getHistory(any(), any())).thenReturn("");
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
        when(memoryService.getHistory(any(), any())).thenReturn("");
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
        when(summarizerService.buildContext(anyString(), eq("t1"))).thenReturn("[Conversation Summary]\n요약본");
        when(classifierService.classifyOnly(any(), any())).thenReturn("manual");
        when(agentGraph.run(any())).thenReturn(fullResult());

        service.chat(CTX, new ChatRequest("질문", "v1", "t1", RoutingMode.COST_FIRST));

        ArgumentCaptor<AgentState> captor = ArgumentCaptor.forClass(AgentState.class);
        verify(agentGraph).run(captor.capture());
        assertThat(captor.getValue().conversationHistory()).isEqualTo("[Conversation Summary]\n요약본");
        verify(memoryService, never()).getHistory(anyString(), anyString());
    }

    @Test
    @DisplayName("§6.10 — summarizerService.buildContext() 가 null 이면 memoryService.getHistory() 로 폴백")
    void chat_fallsBackToRawHistory_whenNoSummaryCached() {
        when(summarizerService.buildContext(anyString(), eq("t1"))).thenReturn(null);
        when(memoryService.getHistory(anyString(), eq("t1"))).thenReturn("원본 히스토리");
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
        when(memoryService.getHistory(any(), any())).thenReturn("");
        when(classifierService.classifyOnly(any(), any())).thenReturn("manual");
        when(agentGraph.run(any())).thenReturn(fullResult());
        when(memoryService.addTurn(any(), any(), any(), any(), any(),
            anyInt(), anyInt(), anyInt(), any(), anyInt(), any(), any(), anyBoolean())).thenReturn(42L);

        service.chat(CTX, new ChatRequest("질문", "v1", "t1", RoutingMode.COST_FIRST));

        verify(summarizerService, times(1)).precomputeAfterTurn("anonymous", "t1", 42L, Locale.KOREAN);
    }
}
