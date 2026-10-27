package com.example.ragagent.agent;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.model.ResponseMode;
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
import org.springframework.ai.document.Document;

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
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

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
    @DisplayName("§6.31 — RAG 로 물었는데 meta 로 판정되면 이력 제외를 끈 채 DIRECT_ANSWER 로 간다(이력이 곧 답, 저장값도 끈 것)")
    void metaTurnDropsTheHistoryExclusion() {
        when(classifierService.execute(any()))
                .thenAnswer(inv -> ((AgentState) inv.getArgument(0)).toBuilder().questionType("meta").build());
        org.mockito.ArgumentCaptor<AgentState> seen = org.mockito.ArgumentCaptor.forClass(AgentState.class);
        when(directAnswerService.execute(seen.capture())).thenAnswer(inv -> inv.getArgument(0));

        AgentState result = graph.run(newState(RoutingMode.COST_FIRST).toBuilder()
                .excludeHistory(true).historyBoost(4).build());

        assertThat(seen.getValue().excludeHistory()).isFalse();
        assertThat(result.excludeHistory()).isFalse();
        assertThat(result.historyBoost()).isZero();
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
    @DisplayName("responseMode=S 면 ANSWER 후 CRITIC 스킵하고 FINALIZE 진행")
    void responseModeS_skipsCritic() {
        when(classifierService.execute(any()))
                .thenAnswer(inv -> ((AgentState) inv.getArgument(0)).toBuilder().questionType("manual").build());
        when(answerService.execute(any()))
                .thenAnswer(inv -> ((AgentState) inv.getArgument(0)).toBuilder().needsRetry(false).build());

        AgentState state = newState(RoutingMode.COST_FIRST).toBuilder().responseMode(ResponseMode.S).build();
        graph.run(state);

        verify(retrievalService, times(1)).execute(any(), any());
        verify(answerService, times(1)).execute(any());
        verify(criticService, never()).execute(any());
        verify(finalizeService, times(1)).execute(any());
    }

    @Test
    @DisplayName("responseMode=C 면 meta 로 분류돼도 DIRECT_ANSWER 가 아니라 RETRIEVAL 을 탄다 (§6.24 Step 2-c)")
    void creativeMode_metaStillGoesToRetrieval() {
        // "문서에 있는 날짜 함수로 예제 하나 만들어줘" 가 meta 로 분류되는 순간 검색을 통째로
        // 건너뛰어 C 가 무력화된다 — 분류기는 "잡담인가"만 알고 "문서가 필요한가"는 모드가 안다.
        when(classifierService.execute(any()))
                .thenAnswer(inv -> ((AgentState) inv.getArgument(0)).toBuilder().questionType("meta").build());

        AgentState state = newState(RoutingMode.COST_FIRST).toBuilder()
                .responseMode(ResponseMode.C).build();
        AgentState result = graph.run(state);

        verify(classifierService, times(1)).execute(any());
        verify(directAnswerService, never()).execute(any());
        verify(retrievalService, times(1)).execute(any(), any());
        verify(answerService, times(1)).execute(any());
        verify(finalizeService, times(1)).execute(any());
        assertThat(result.questionType()).isEqualTo("meta");
    }

    @Test
    @DisplayName("responseMode=C + directMode=true 요청도 DIRECT_ANSWER 로 새지 않는다")
    void creativeMode_directModeRequestFallsBackToTheRagPath() {
        // C 는 Direct 전용 시스템 프롬프트가 없다(키가 null). 손으로 만든
        // responseMode=C&directMode=true 요청이 DIRECT_ANSWER 에 닿으면 그 null 로 터진다 —
        // 구 L 은 서버 가드가 없어 정확히 그런 요청이 통과했다.
        when(classifierService.execute(any()))
                .thenAnswer(inv -> ((AgentState) inv.getArgument(0)).toBuilder().questionType("manual").build());

        AgentState state = AgentState.of("질문", "v1", "t1", "", RoutingMode.COST_FIRST, true)
                .toBuilder().responseMode(ResponseMode.C).build();
        graph.run(state);

        verify(directAnswerService, never()).execute(any());
        verify(retrievalService, times(1)).execute(any(), any());
        verify(answerService, times(1)).execute(any());
        verify(finalizeService, times(1)).execute(any());
    }

    @Test
    @DisplayName("responseMode=C 는 검증을 건너뛰지 않는다 — CRITIC 을 그대로 탄다")
    void creativeMode_stillRunsCritic() {
        // S 와 갈리는 지점. C 는 eval 을 '끄는' 게 아니라 창의 기준으로 바꿔 끼우므로,
        // 그 결과(apiGrounded)를 소비할 CRITIC 이 여전히 필요하다.
        when(classifierService.execute(any()))
                .thenAnswer(inv -> ((AgentState) inv.getArgument(0)).toBuilder().questionType("manual").build());
        when(answerService.execute(any()))
                .thenAnswer(inv -> ((AgentState) inv.getArgument(0)).toBuilder().needsRetry(false).build());

        AgentState state = newState(RoutingMode.COST_FIRST).toBuilder()
                .responseMode(ResponseMode.C).build();
        graph.run(state);

        verify(criticService, times(1)).execute(any());
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
    @DisplayName("CRITIC 가 needsRetry=true → 재검색 없이 ANSWER 만 다시 (근거는 이미 손에 있다)")
    void critic_needsRetry_regeneratesWithoutRetrieval() {
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

        // grounded=false 는 "문서 밖으로 나갔다"이지 "근거가 모자라다"가 아니다. 재검색은 임베딩 +
        // MultiQuery 확장 호출을 쓰고 사실상 같은 집합을 받아오므로 한 번만 돈다.
        verify(retrievalService, times(1)).execute(any(), any());
        verify(answerService, times(2)).execute(any());
        verify(criticService, times(2)).execute(any());
        verify(finalizeService, times(1)).execute(any());
    }

    @Test
    @DisplayName("CRITIC 재시도는 retrievalRetries 를 올리지 않는다 — 검색 escalation 이 앞서 나가면 안 된다")
    void critic_retry_doesNotAdvanceRetrievalEscalation() {
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

        AgentState result = graph.run(newState(RoutingMode.COST_FIRST));

        assertThat(result.retryCount()).isEqualTo(1);
        assertThat(result.retrievalRetries()).isZero();
    }

    /** 검색이 부를 때마다 다른 청크를 돌려준다(재검색이 재료를 바꾸는 정상적인 경우). */
    private void retrievalReturnsFreshChunksEachTime() {
        int[] calls = {0};
        when(retrievalService.execute(any(), any())).thenAnswer(inv -> {
            calls[0]++;
            return ((AgentState) inv.getArgument(0)).toBuilder()
                    .retrievedDocs(List.of(new Document("c" + calls[0], "본문" + calls[0], java.util.Map.of())))
                    .build();
        });
    }

    /** 검색이 매번 같은 청크를 돌려준다(교체·증가가 아무것도 바꾸지 못한 경우). */
    private void retrievalReturnsTheSameChunks() {
        when(retrievalService.execute(any(), any())).thenAnswer(inv -> ((AgentState) inv.getArgument(0)).toBuilder()
                .retrievedDocs(List.of(new Document("same-1", "본문", java.util.Map.of()),
                        new Document("same-2", "본문", java.util.Map.of())))
                .build());
    }

    @Test
    @DisplayName("maxRetryCount(2) 도달 후 retry 종료 — answer 3회 호출 후 CRITIC → FINALIZE")
    void retry_capped_at_maxRetryCount() {
        when(classifierService.execute(any()))
                .thenAnswer(inv -> ((AgentState) inv.getArgument(0)).toBuilder().questionType("manual").build());
        when(answerService.execute(any()))
                .thenAnswer(inv -> ((AgentState) inv.getArgument(0)).toBuilder().needsRetry(true).build());
        retrievalReturnsFreshChunksEachTime();

        graph.run(newState(RoutingMode.COST_FIRST));

        // 흐름: ANSWER(retry=0→1) → ANSWER(1→2) → ANSWER(2, no more retry) → CRITIC → FINALIZE
        verify(retrievalService, times(3)).execute(any(), any());
        verify(answerService, times(3)).execute(any());
        verify(criticService, times(1)).execute(any());
        verify(finalizeService, times(1)).execute(any());
    }

    @Test
    @DisplayName("조기 종료 — 재검색이 같은 청크를 돌려주고 또 불충분이면 상한이 남아도 멈춘다(answer 2회, 검색 2회)")
    void insufficientAgainOnTheSameChunks_stopsEarly() {
        when(classifierService.execute(any()))
                .thenAnswer(inv -> ((AgentState) inv.getArgument(0)).toBuilder().questionType("manual").build());
        when(answerService.execute(any()))
                .thenAnswer(inv -> ((AgentState) inv.getArgument(0)).toBuilder().needsRetry(true).build());
        when(criticService.execute(any()))   // 근거는 맞다 — 불충분만 반복된다
                .thenAnswer(inv -> ((AgentState) inv.getArgument(0)).toBuilder().needsRetry(false).build());
        retrievalReturnsTheSameChunks();

        AgentState result = graph.run(newState(RoutingMode.COST_FIRST));

        verify(retrievalService, times(2)).execute(any(), any());
        verify(answerService, times(2)).execute(any());
        verify(criticService, times(1)).execute(any());   // 소진과 같이 — CRITIC 을 거쳐 끝난다
        verify(finalizeService, times(1)).execute(any());
        assertThat(result.retryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("조기 종료 — 같은 근거로 다시 썼는데 또 근거 이탈이면 두 번째 반려에서 멈춘다(answer 2회)")
    void ungroundedAgainOnTheSameChunks_stopsEarly() {
        when(classifierService.execute(any()))
                .thenAnswer(inv -> ((AgentState) inv.getArgument(0)).toBuilder().questionType("manual").build());
        when(answerService.execute(any()))
                .thenAnswer(inv -> ((AgentState) inv.getArgument(0)).toBuilder().needsRetry(false).build());
        when(criticService.execute(any()))
                .thenAnswer(inv -> ((AgentState) inv.getArgument(0)).toBuilder().needsRetry(true).build());
        retrievalReturnsTheSameChunks();

        AgentState result = graph.run(newState(RoutingMode.COST_FIRST));

        verify(retrievalService, times(1)).execute(any(), any());
        verify(answerService, times(2)).execute(any());
        verify(criticService, times(2)).execute(any());
        verify(finalizeService, times(1)).execute(any());
        assertThat(result.retryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("게이트가 다르면 같은 청크여도 '같은 반려'가 아니다 — 불충분 뒤의 근거 이탈은 재시도한다")
    void differentGateOnTheSameChunks_stillRetries() {
        when(classifierService.execute(any()))
                .thenAnswer(inv -> ((AgentState) inv.getArgument(0)).toBuilder().questionType("manual").build());
        int[] answerCalls = {0};
        when(answerService.execute(any())).thenAnswer(inv -> {
            answerCalls[0]++;
            return ((AgentState) inv.getArgument(0)).toBuilder().needsRetry(answerCalls[0] == 1).build();
        });
        int[] criticCalls = {0};
        when(criticService.execute(any())).thenAnswer(inv -> {
            criticCalls[0]++;
            return ((AgentState) inv.getArgument(0)).toBuilder().needsRetry(criticCalls[0] == 1).build();
        });
        retrievalReturnsTheSameChunks();

        graph.run(newState(RoutingMode.COST_FIRST));

        // ANSWER 불충분(→ 재검색, 같은 청크) → ANSWER 통과 → CRITIC 이탈(다른 게이트 — 재시도) → ANSWER → CRITIC 통과
        verify(answerService, times(3)).execute(any());
        verify(criticService, times(2)).execute(any());
    }

    @Test
    @DisplayName("반려 서명은 순서를 보지 않는다 — 순위만 바뀐 같은 집합은 같은 재료다")
    void rejectionKeyIgnoresOrder() {
        Document a = new Document("a", "x", java.util.Map.of());
        Document b = new Document("b", "y", java.util.Map.of());
        assertThat(AgentState.rejectionKey(AgentState.RetryGate.INSUFFICIENT, List.of(a, b)))
                .isEqualTo(AgentState.rejectionKey(AgentState.RetryGate.INSUFFICIENT, List.of(b, a)))
                .isNotEqualTo(AgentState.rejectionKey(AgentState.RetryGate.UNGROUNDED, List.of(a, b)));
    }

    @Test
    @DisplayName("§6.30 B — 조기 종료는 검색 결과가 아니라 답변 프롬프트에 실린 문서로 비교한다")
    void rejectionComparesTheDocumentsTheModelSaw() {
        Document a = new Document("a", "x", java.util.Map.of());
        Document b = new Document("b", "y", java.util.Map.of());
        Document c = new Document("c", "z", java.util.Map.of());
        com.example.ragagent.model.SentPrompt sawAB = new com.example.ragagent.model.SentPrompt(
                List.of("a", "b"), null, List.of());
        AgentState first = newState(RoutingMode.COST_FIRST).toBuilder()
                .retrievedDocs(List.of(a, b)).lastSent(sawAB)
                .rememberRejection(AgentState.RetryGate.INSUFFICIENT).build();

        // 재검색이 c 를 더 가져왔지만 사다리가 잘라 모델은 같은 a, b 를 봤다 — 같은 재료다
        AgentState sameSeen = first.toBuilder().retrievedDocs(List.of(a, b, c)).lastSent(sawAB).build();
        assertThat(sameSeen.repeatsLastRejection(AgentState.RetryGate.INSUFFICIENT)).isTrue();
        // 조기 종료 로그의 청크 수도 비교한 집합의 크기다(검색 결과 3개가 아니라)
        assertThat(sameSeen.seenDocCount()).isEqualTo(2);

        // c 가 실렸으면 재료가 바뀌었다
        AgentState newSeen = first.toBuilder().retrievedDocs(List.of(a, b, c))
                .lastSent(new com.example.ragagent.model.SentPrompt(List.of("a", "b", "c"), null, List.of())).build();
        assertThat(newSeen.repeatsLastRejection(AgentState.RetryGate.INSUFFICIENT)).isFalse();
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
    @DisplayName("runStreaming — ANSWER 불충분 재시도 시 listener.onRetry(\"answer\", 1) + 평가 사유 전달")
    void runStreaming_firesOnRetryWhenAnswerInsufficient() {
        when(classifierService.execute(any()))
                .thenAnswer(inv -> ((AgentState) inv.getArgument(0)).toBuilder().questionType("manual").build());
        int[] answerCalls = {0};
        when(answerService.executeStreaming(any(), any())).thenAnswer(inv -> {
            AgentState s = inv.getArgument(0);
            answerCalls[0]++;
            return answerCalls[0] == 1
                    ? s.toBuilder().needsRetry(true).evalReason("질문한 포트 설정 값이 문서에 없음").build()
                    : s.toBuilder().needsRetry(false).build();
        });

        List<String> retries = new ArrayList<>();
        GraphListener listener = new GraphListener() {
            @Override public void onRetry(String reason, int retryCount, String detail) {
                retries.add(reason + ":" + retryCount + ":" + detail);
            }
        };

        graph.runStreaming(newState(RoutingMode.COST_FIRST), listener);

        // 평가 LLM 이 준 사유가 그래프를 거쳐 리스너까지 그대로 전달돼야 한다 —
        // 이게 끊기면 UI 는 "재시도 중"까지만 알고 이유는 영영 보이지 않는다.
        assertThat(retries).containsExactly("answer:1:질문한 포트 설정 값이 문서에 없음");
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
            @Override public void onRetry(String reason, int retryCount, String detail) {
                retries.add(reason + ":" + retryCount);
            }
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
            @Override public void onRetry(String reason, int retryCount, String detail) { fired[0] = true; }
        };

        graph.runStreaming(newState(RoutingMode.COST_FIRST), listener);

        assertThat(fired[0]).isFalse();
    }
}
