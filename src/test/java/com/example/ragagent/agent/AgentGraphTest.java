package com.example.ragagent.agent;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.service.AnswerService;
import com.example.ragagent.service.ClassifierService;
import com.example.ragagent.service.CriticService;
import com.example.ragagent.service.DirectAnswerService;
import com.example.ragagent.service.FinalizeService;
import com.example.ragagent.service.GraphListener;
import com.example.ragagent.service.RetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA — AgentGraph (state machine transitions)
 *
 * Covers (per refactoring/01-test-safety-net.md):
 *  - classifier_meta_skipsRetrieval
 *  - classifier_nonMeta_goesToRetrieval
 *  - answer_needsRetry_loopsBackToRetrieval
 *  - critic_needsRetry_loopsBackToRetrieval
 *  - retry_capped_at_maxRetryCount
 *  - existingQuestionType_skipsClassifier (AgentService 사전 분류 경로)
 */
class AgentGraphTest {

    private static final int MAX_RETRY = 2;

    private ClassifierService classifierService;
    private DirectAnswerService directAnswerService;
    private RetrievalService retrievalService;
    private AnswerService answerService;
    private CriticService criticService;
    private FinalizeService finalizeService;
    private AgentGraph graph;

    @BeforeEach
    void setUp() {
        classifierService = mock(ClassifierService.class);
        directAnswerService = mock(DirectAnswerService.class);
        retrievalService = mock(RetrievalService.class);
        answerService = mock(AnswerService.class);
        criticService = mock(CriticService.class);
        finalizeService = mock(FinalizeService.class);

        // 모든 서비스 기본 stub: 입력 state 그대로 반환 (각 테스트가 필요 시 override)
        when(classifierService.execute(any())).thenAnswer(inv -> inv.getArgument(0));
        when(directAnswerService.execute(any())).thenAnswer(inv -> inv.getArgument(0));
        when(retrievalService.execute(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(answerService.execute(any())).thenAnswer(inv -> inv.getArgument(0));
        when(criticService.execute(any())).thenAnswer(inv -> inv.getArgument(0));
        when(finalizeService.execute(any())).thenAnswer(inv -> inv.getArgument(0));

        AppProperties props = new AppProperties(
            "./data", MAX_RETRY, 800, 100, 100, 7, 0.0, true, 0, false,
                true, false, 3,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        graph = new AgentGraph(classifierService, directAnswerService, retrievalService,
                               answerService, criticService, finalizeService, props);
    }

    private AgentState newState(RoutingMode mode) {
        return AgentState.of("질문", "v1", "t1", "", mode);
    }

    @Test
    @DisplayName("classifier 가 meta 분류 → RETRIEVAL 건너뛰고 DIRECT_ANSWER 호출")
    void classifier_meta_skipsRetrieval() {
        when(classifierService.execute(any()))
                .thenAnswer(inv -> ((AgentState) inv.getArgument(0)).toBuilder().questionType("meta").build());

        AgentState result = graph.run(newState(RoutingMode.COST_FIRST));

        verify(classifierService, times(1)).execute(any());
        verify(directAnswerService, times(1)).execute(any());
        verify(retrievalService, never()).execute(any(), any());
        verify(answerService, never()).execute(any());
        verify(criticService, never()).execute(any());
        verify(finalizeService, times(1)).execute(any());
        assertThat(result.questionType()).isEqualTo("meta");
    }

    @Test
    @DisplayName("classifier 가 non-meta 분류 → RETRIEVAL → ANSWER 진행")
    void classifier_nonMeta_goesToRetrieval() {
        when(classifierService.execute(any()))
                .thenAnswer(inv -> ((AgentState) inv.getArgument(0)).toBuilder().questionType("manual").build());

        graph.run(newState(RoutingMode.COST_FIRST));

        verify(retrievalService, times(1)).execute(any(), any());
        verify(answerService, times(1)).execute(any());
        verify(directAnswerService, never()).execute(any());
        verify(criticService, times(1)).execute(any());
        verify(finalizeService, times(1)).execute(any());
    }

    @Test
    @DisplayName("AgentService 사전 분류 — questionType 이미 설정되면 classifier 호출 skip")
    void existingQuestionType_skipsClassifier() {
        AgentState pre = newState(RoutingMode.COST_FIRST).toBuilder().questionType("manual").build();

        graph.run(pre);

        verify(classifierService, never()).execute(any());
        verify(retrievalService, times(1)).execute(any(), any());
        verify(answerService, times(1)).execute(any());
    }

    @Test
    @DisplayName("ANSWER 가 needsRetry=true → RETRIEVAL 로 루프백 (1회 retry)")
    void answer_needsRetry_loopsBackToRetrieval() {
        when(classifierService.execute(any()))
                .thenAnswer(inv -> ((AgentState) inv.getArgument(0)).toBuilder().questionType("manual").build());

        int[] answerCalls = {0};
        when(answerService.execute(any())).thenAnswer(inv -> {
            AgentState s = inv.getArgument(0);
            answerCalls[0]++;
            // 첫 호출: retry 요청, 두번째: ok
            return answerCalls[0] == 1 ? s.toBuilder().needsRetry(true).build() : s.toBuilder().needsRetry(false).build();
        });

        graph.run(newState(RoutingMode.COST_FIRST));

        verify(retrievalService, times(2)).execute(any(), any());
        verify(answerService, times(2)).execute(any());
        verify(criticService, times(1)).execute(any());
        verify(finalizeService, times(1)).execute(any());
    }

    @Test
    @DisplayName("CRITIC 가 needsRetry=true → RETRIEVAL 로 루프백")
    void critic_needsRetry_loopsBackToRetrieval() {
        when(classifierService.execute(any()))
                .thenAnswer(inv -> ((AgentState) inv.getArgument(0)).toBuilder().questionType("manual").build());
        when(answerService.execute(any()))
                .thenAnswer(inv -> ((AgentState) inv.getArgument(0)).toBuilder().needsRetry(false).build());

        int[] criticCalls = {0};
        when(criticService.execute(any())).thenAnswer(inv -> {
            AgentState s = inv.getArgument(0);
            criticCalls[0]++;
            return criticCalls[0] == 1 ? s.toBuilder().needsRetry(true).build() : s.toBuilder().needsRetry(false).build();
        });

        graph.run(newState(RoutingMode.COST_FIRST));

        verify(retrievalService, times(2)).execute(any(), any());
        verify(answerService, times(2)).execute(any());
        verify(criticService, times(2)).execute(any());
        verify(finalizeService, times(1)).execute(any());
    }

    @Test
    @DisplayName("maxRetryCount(2) 도달 후 retry 종료 — answer 3회 호출 후 CRITIC → FINALIZE")
    void retry_capped_at_maxRetryCount() {
        when(classifierService.execute(any()))
                .thenAnswer(inv -> ((AgentState) inv.getArgument(0)).toBuilder().questionType("manual").build());
        when(answerService.execute(any()))
                .thenAnswer(inv -> ((AgentState) inv.getArgument(0)).toBuilder().needsRetry(true).build());

        graph.run(newState(RoutingMode.COST_FIRST));

        // 흐름: ANSWER(retry=0→1) → ANSWER(1→2) → ANSWER(2, no more retry) → CRITIC → FINALIZE
        verify(retrievalService, times(3)).execute(any(), any());
        verify(answerService, times(3)).execute(any());
        verify(criticService, times(1)).execute(any());
        verify(finalizeService, times(1)).execute(any());
    }

    @Test
    @DisplayName("runStreaming — RETRIEVAL 직후 listener.onImagesReady()가 state.imageRefs()로 호출됨")
    void runStreaming_firesOnImagesReadyAfterRetrieval() {
        List<String> refs = List.of("images/doc1/p1_img1.png");
        when(classifierService.execute(any()))
                .thenAnswer(inv -> ((AgentState) inv.getArgument(0)).toBuilder().questionType("manual").build());
        when(retrievalService.execute(any(), any()))
                .thenAnswer(inv -> ((AgentState) inv.getArgument(0)).toBuilder().imageRefs(refs).build());
        // non-NOOP listener → AgentGraph calls answerService.executeStreaming(), not execute().
        when(answerService.executeStreaming(any(), any())).thenAnswer(inv -> inv.getArgument(0));

        List<List<String>> captured = new ArrayList<>();
        GraphListener listener = new GraphListener() {
            @Override public void onImagesReady(List<String> imageRefs) { captured.add(imageRefs); }
        };

        graph.runStreaming(newState(RoutingMode.COST_FIRST), listener);

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0)).isEqualTo(refs);
    }

    @Test
    @DisplayName("runStreaming — ANSWER 불충분 재시도 시 listener.onRetry(\"answer\", 1) 발생")
    void runStreaming_firesOnRetryWhenAnswerInsufficient() {
        when(classifierService.execute(any()))
                .thenAnswer(inv -> ((AgentState) inv.getArgument(0)).toBuilder().questionType("manual").build());
        int[] answerCalls = {0};
        when(answerService.executeStreaming(any(), any())).thenAnswer(inv -> {
            AgentState s = inv.getArgument(0);
            answerCalls[0]++;
            return answerCalls[0] == 1 ? s.toBuilder().needsRetry(true).build() : s.toBuilder().needsRetry(false).build();
        });

        List<String> retries = new ArrayList<>();
        GraphListener listener = new GraphListener() {
            @Override public void onRetry(String reason, int retryCount) { retries.add(reason + ":" + retryCount); }
        };

        graph.runStreaming(newState(RoutingMode.COST_FIRST), listener);

        assertThat(retries).containsExactly("answer:1");
    }

    @Test
    @DisplayName("runStreaming — CRITIC 미검증 재시도 시 listener.onRetry(\"critic\", 1) 발생")
    void runStreaming_firesOnRetryWhenCriticUngrounded() {
        when(classifierService.execute(any()))
                .thenAnswer(inv -> ((AgentState) inv.getArgument(0)).toBuilder().questionType("manual").build());
        when(answerService.executeStreaming(any(), any()))
                .thenAnswer(inv -> ((AgentState) inv.getArgument(0)).toBuilder().needsRetry(false).build());
        int[] criticCalls = {0};
        when(criticService.execute(any())).thenAnswer(inv -> {
            AgentState s = inv.getArgument(0);
            criticCalls[0]++;
            return criticCalls[0] == 1 ? s.toBuilder().needsRetry(true).build() : s.toBuilder().needsRetry(false).build();
        });

        List<String> retries = new ArrayList<>();
        GraphListener listener = new GraphListener() {
            @Override public void onRetry(String reason, int retryCount) { retries.add(reason + ":" + retryCount); }
        };

        graph.runStreaming(newState(RoutingMode.COST_FIRST), listener);

        assertThat(retries).containsExactly("critic:1");
    }

    @Test
    @DisplayName("runStreaming — 재시도 없으면 onRetry 미호출")
    void runStreaming_noRetry_doesNotFireOnRetry() {
        when(classifierService.execute(any()))
                .thenAnswer(inv -> ((AgentState) inv.getArgument(0)).toBuilder().questionType("manual").build());
        when(answerService.executeStreaming(any(), any()))
                .thenAnswer(inv -> ((AgentState) inv.getArgument(0)).toBuilder().needsRetry(false).build());

        boolean[] fired = {false};
        GraphListener listener = new GraphListener() {
            @Override public void onRetry(String reason, int retryCount) { fired[0] = true; }
        };

        graph.runStreaming(newState(RoutingMode.COST_FIRST), listener);

        assertThat(fired[0]).isFalse();
    }
}
