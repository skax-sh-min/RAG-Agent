package com.example.ragagent.service;

import com.example.ragagent.agent.AgentGraph;
import com.example.ragagent.agent.AgentState;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.model.ChatRequest;
import com.example.ragagent.model.ChatResponse;
import com.example.ragagent.model.SourceRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
    private AgentService service;

    @BeforeEach
    void setUp() {
        agentGraph = mock(AgentGraph.class);
        memoryService = mock(MemoryService.class);
        classifierService = mock(ClassifierService.class);
        service = new AgentService(agentGraph, memoryService, classifierService);
    }

    private AgentState fullResult() {
        return AgentState.of("질문", "v1", "t1", "", RoutingMode.COST_FIRST)
                .withQuestionType("manual")
                .withAnswer("최종 답변")
                .withSources(List.of(new SourceRef("doc.pdf | v1 | p.3", "snippet", "doc_123", 3)))
                .withImageRefs(List.of("data/images/doc_123/img1.png"))
                .withUsedProvider("gemini-flash")
                .withTokensAccumulated(120, 80)
                .withTokensAccumulated(20, 10);
    }

    @Test
    @DisplayName("chat() 은 history+classifier 를 병렬 호출하고 결과를 AgentState 에 주입")
    void chat_parallelJoin_setsHistoryAndQuestionType() {
        when(memoryService.getHistory("t1")).thenReturn("이전 대화");
        when(classifierService.classifyOnly("질문")).thenReturn("manual");
        when(agentGraph.run(any())).thenReturn(fullResult());

        service.chat(new ChatRequest("질문", "v1", "t1", RoutingMode.COST_FIRST));

        ArgumentCaptor<AgentState> captor = ArgumentCaptor.forClass(AgentState.class);
        verify(agentGraph, times(1)).run(captor.capture());
        AgentState initial = captor.getValue();

        assertThat(initial.question()).isEqualTo("질문");
        assertThat(initial.version()).isEqualTo("v1");
        assertThat(initial.threadId()).isEqualTo("t1");
        assertThat(initial.conversationHistory()).isEqualTo("이전 대화");
        assertThat(initial.questionType()).isEqualTo("manual");
        assertThat(initial.routingMode()).isEqualTo(RoutingMode.COST_FIRST);

        verify(memoryService, times(1)).getHistory("t1");
        verify(classifierService, times(1)).classifyOnly("질문");
    }

    @Test
    @DisplayName("ChatResponse 매핑 — AgentState 모든 필드가 응답에 정확히 전이")
    void chat_mapsAgentStateToChatResponse() {
        when(memoryService.getHistory(any())).thenReturn("");
        when(classifierService.classifyOnly(any())).thenReturn("manual");
        when(agentGraph.run(any())).thenReturn(fullResult());

        ChatResponse resp = service.chat(new ChatRequest("질문", "v1", "t1", RoutingMode.COST_FIRST));

        assertThat(resp.answer()).isEqualTo("최종 답변");
        assertThat(resp.questionType()).isEqualTo("manual");
        assertThat(resp.sources()).hasSize(1);
        assertThat(resp.imageRefs()).containsExactly("data/images/doc_123/img1.png");
        assertThat(resp.totalInputTokens()).isEqualTo(140);
        assertThat(resp.totalOutputTokens()).isEqualTo(90);
        assertThat(resp.llmCallCount()).isEqualTo(2);
        assertThat(resp.usedProvider()).isEqualTo("gemini-flash");
        assertThat(resp.elapsedSeconds()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("DUAL 모드 응답 — dualLocalAnswer/Provider 가 ChatResponse 에 노출")
    void chat_dualMode_exposesLocalAnswer() {
        AgentState dualResult = AgentState.of("q", "v1", "t1", "", RoutingMode.DUAL)
                .withQuestionType("manual")
                .withAnswer("외부 답변")
                .withUsedProvider("gemini-flash")
                .withDualResult("로컬 답변", "local");

        when(memoryService.getHistory(any())).thenReturn("");
        when(classifierService.classifyOnly(any())).thenReturn("manual");
        when(agentGraph.run(any())).thenReturn(dualResult);

        ChatResponse resp = service.chat(new ChatRequest("질문", "v1", "t1", RoutingMode.DUAL));

        assertThat(resp.dualLocalAnswer()).isEqualTo("로컬 답변");
        assertThat(resp.dualLocalProvider()).isEqualTo("local");
        assertThat(resp.usedProvider()).isEqualTo("gemini-flash");
    }

    @Test
    @DisplayName("PROGRESSIVE 모드 응답 — premiumUpgraded 가 ChatResponse 에 노출")
    void chat_progressiveUpgrade_exposesPremiumProvider() {
        AgentState upgradedResult = fullResult().withPremiumUpgraded("gemini-pro");

        when(memoryService.getHistory(any())).thenReturn("");
        when(classifierService.classifyOnly(any())).thenReturn("manual");
        when(agentGraph.run(any())).thenReturn(upgradedResult);

        ChatResponse resp = service.chat(new ChatRequest("질문", "v1", "t1", RoutingMode.PROGRESSIVE));

        assertThat(resp.premiumUpgraded()).isEqualTo("gemini-pro");
    }

    @Test
    @DisplayName("memory 와 classifier 가 진짜 병렬 실행 (각각 200ms sleep 도 총 시간 ~200ms)")
    void chat_runsHistoryAndClassifyInParallel() {
        when(memoryService.getHistory(any())).thenAnswer(inv -> {
            Thread.sleep(200);
            return "";
        });
        when(classifierService.classifyOnly(any())).thenAnswer(inv -> {
            Thread.sleep(200);
            return "manual";
        });
        when(agentGraph.run(any())).thenReturn(fullResult());

        long start = System.nanoTime();
        service.chat(new ChatRequest("질문", "v1", "t1", RoutingMode.COST_FIRST));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // 직렬이면 ~400ms, 병렬이면 ~200-300ms. 350ms 미만이면 병렬 보장.
        assertThat(elapsedMs)
                .as("history + classify 병렬 실행 (직렬이었다면 400ms 초과)")
                .isLessThan(350);
    }

    @Test
    @DisplayName("classifyOnly 가 questionType=null 반환해도 OK (AgentGraph 의 CLASSIFIER 가 처리)")
    void chat_classifyReturnsNull_propagatesToGraph() {
        when(memoryService.getHistory(any())).thenReturn("");
        when(classifierService.classifyOnly(any())).thenReturn(null);
        when(agentGraph.run(any())).thenReturn(fullResult());

        service.chat(new ChatRequest("질문", "v1", "t1", RoutingMode.COST_FIRST));

        ArgumentCaptor<AgentState> captor = ArgumentCaptor.forClass(AgentState.class);
        verify(agentGraph).run(captor.capture());
        assertThat(captor.getValue().questionType()).isNull();
    }

    @Test
    @DisplayName("chat() 호출마다 AgentGraph.run 정확히 1회")
    void chat_invokesGraphOnce() {
        when(memoryService.getHistory(any())).thenReturn("");
        when(classifierService.classifyOnly(any())).thenReturn("manual");
        when(agentGraph.run(any())).thenReturn(fullResult());

        AtomicInteger callCount = new AtomicInteger();
        when(agentGraph.run(any())).thenAnswer(inv -> {
            callCount.incrementAndGet();
            return fullResult();
        });

        service.chat(new ChatRequest("q", "v1", "t1", RoutingMode.COST_FIRST));
        service.chat(new ChatRequest("q", "v1", "t2", RoutingMode.COST_FIRST));

        assertThat(callCount.get()).isEqualTo(2);
    }
}
