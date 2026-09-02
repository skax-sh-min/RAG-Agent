package com.example.ragagent.agent;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Agent execution graph — equivalent to LangGraph StateGraph in the Python version.
 *
 * Flow:
 *   START → CLASSIFIER
 *     ├─ (meta)  → DIRECT_ANSWER → FINALIZE → END
 *     └─ (other) → RETRIEVAL → ANSWER
 *                                ├─ (sufficient=false) → RETRIEVAL (재검색: escalation + 청크 교체)
 *                                └─ (ok)               → CRITIC
 *                                                        ├─ (grounded=false) → ANSWER (재검색 없이 재생성)
 *                                                        └─ (ok)             → FINALIZE → END
 *
 * 두 재시도 게이트는 <b>다른 실패</b>라 대응이 다르다. {@code sufficient=false} 는 "질문에 답하지
 * 못했다"이므로 재료를 바꿔야 하고, {@code grounded=false} 는 "문서 밖으로 나갔다"이므로 재료는
 * 그대로 두고 답변만 다시 써야 한다 — 후자에서 재검색은 임베딩·확장 호출을 쓰고 사실상 같은
 * 집합을 받아오는 낭비다. 예전에는 둘 다 RETRIEVAL 로 갔다.
 */
@Component
public class AgentGraph {

    private static final Logger log = LoggerFactory.getLogger(AgentGraph.class);

    private enum Node { CLASSIFIER, DIRECT_ANSWER, RETRIEVAL, ANSWER, CRITIC, FINALIZE, END }

    private final ClassifierService classifierService;
    private final DirectAnswerService directAnswerService;
    private final RetrievalService retrievalService;
    private final AnswerService answerService;
    private final CriticService criticService;
    private final FinalizeService finalizeService;
    private final int maxRetryCount;

    public AgentGraph(
            ClassifierService classifierService,
            DirectAnswerService directAnswerService,
            RetrievalService retrievalService,
            AnswerService answerService,
            CriticService criticService,
            FinalizeService finalizeService,
            AppProperties appProperties) {
        this.classifierService = classifierService;
        this.directAnswerService = directAnswerService;
        this.retrievalService = retrievalService;
        this.answerService = answerService;
        this.criticService = criticService;
        this.finalizeService = finalizeService;
        this.maxRetryCount = appProperties.maxRetryCount();
    }

    /** Existing blocking path — no listener overhead. */
    public AgentState run(AgentState initialState) {
        return runInternal(initialState, GraphListener.NOOP);
    }

    /** Streaming path — GraphListener receives node/token/sources events. */
    public AgentState runStreaming(AgentState initialState, GraphListener listener) {
        return runInternal(initialState, listener);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * CLASSIFIER 이후의 갈림길. meta(인사/잡담)는 검색을 건너뛰지만, <b>검색 결과가 전제인
     * 모드는 예외다</b> (§6.24 Step 2-c) — "문서에 있는 날짜 함수로 예제 하나 만들어줘" 같은
     * 요청이 meta 로 분류되는 순간 검색을 통째로 건너뛰어 그 모드가 무력화되기 때문이다.
     * 분류기를 고치는 대신 여기서 막는 이유는, 분류기는 "이 질문이 잡담인가"만 알고 "이 턴이
     * 문서를 필요로 하는가"는 모드가 알기 때문이다.
     */
    private static Node afterClassify(AgentState state) {
        boolean meta = "meta".equals(state.questionType());
        return (meta && state.responseMode().allowsDirect()) ? Node.DIRECT_ANSWER : Node.RETRIEVAL;
    }

    private AgentState runInternal(AgentState initialState, GraphListener listener) {
        // directMode: RAG 없이 LLM 직접 호출 → CLASSIFIER/RETRIEVAL/CRITIC 생략.
        // 단, 검색 결과가 전제인 모드(C)는 Direct 전용 시스템 프롬프트 자체가 없다 — 값이 아니라
        // 성질로 묻고(§6.24 Step 0-b), 해당하지 않으면 일반 RAG 경로로 되돌린다. 손으로 만든
        // responseMode=C&directMode=true 요청이 여기로 들어오면 프롬프트 키가 null 이라 그대로
        // 터진다(구 L은 서버 가드가 없어 그 요청이 통과했다). 사용자에게 보이는 강등은 별도로
        // ChatController 가 맡고, 이 가드는 그래프 자신의 정합성 보장이다.
        Node current = (initialState.directMode() && initialState.responseMode().allowsDirect())
                ? Node.DIRECT_ANSWER : Node.CLASSIFIER;
        log.debug("[AgentGraph] start node={} directMode={} routingMode={}",
                current, initialState.directMode(), initialState.routingMode());
        AgentState state = initialState;

        while (current != Node.END) {
            current = switch (current) {
                case CLASSIFIER -> {
                    listener.onNodeEnter("classifier");
                    if (state.questionType() != null) {
                        yield afterClassify(state);
                    }
                    state = classifierService.execute(state);
                    yield afterClassify(state);
                }
                case DIRECT_ANSWER -> {
                    state = (listener == GraphListener.NOOP)
                            ? directAnswerService.execute(state)
                            : directAnswerService.executeStreaming(state, listener);
                    yield Node.FINALIZE;
                }
                case RETRIEVAL -> {
                    listener.onNodeEnter("retrieval");
                    state = retrievalService.execute(state, listener);
                    listener.onSourcesReady(state.sources());
                    listener.onImagesReady(state.imageRefs());
                    yield Node.ANSWER;
                }
                case ANSWER -> {
                    listener.onNodeEnter("answer");
                    state = (listener == GraphListener.NOOP)
                            ? answerService.execute(state)
                            : answerService.executeStreaming(state, listener);
                    // sufficient=false — "질문에 답하지 못했다". 근거가 모자라거나 잘못 검색된
                    // 것이므로 검색을 다시 한다. retrievalRetries 가 함께 오르고, 그 값이
                    // RetrievalService 의 escalation·교체 입력이 된다.
                    if (state.needsRetry() && state.retryCount() < maxRetryCount) {
                        state = state.toBuilder().incrementRetry().incrementRetrievalRetry().build();
                        log.info("[AgentGraph] retry #{} reason=ANSWER_INSUFFICIENT thread={} detail={}",
                                state.retryCount(), state.threadId(), state.evalReason());
                        listener.onRetry("answer", state.retryCount(), state.evalReason());
                        yield Node.RETRIEVAL;
                    }
                    // CRITIC은 ANSWER의 eval이 남긴 grounded 플래그만 소비하므로, 검증을 건너뛴
                    // 모드에서는 판단할 재료가 없다 — 값이 아니라 성질로 묻는다(§6.24 Step 0-b).
                    if (state.responseMode().skipsVerification()) {
                        yield Node.FINALIZE;
                    }
                    yield Node.CRITIC;
                }
                case CRITIC -> {
                    listener.onNodeEnter("critic");
                    state = criticService.execute(state);
                    // grounded=false — "문서에 없는 것을 말했다". 근거는 이미 손에 있고 답변이
                    // 그 밖으로 나간 것이므로 RETRIEVAL 을 다시 도는 것은 임베딩 + MultiQuery 확장
                    // 호출을 쓰고 사실상 같은 집합(+1)을 받아오는 낭비다. 답변만 다시 쓴다.
                    //
                    // 이 분기가 성립하려면 AnswerService 의 [직전 시도 메모] 가 반드시 있어야
                    // 한다 — 프롬프트가 그대로면 온도 0에서 같은 답변이 그대로 다시 나온다.
                    // retrievalRetries 는 올리지 않는다: 검색을 다시 하지 않았으므로 escalation 이
                    // 한 칸 앞서 나가면 안 된다.
                    if (state.needsRetry() && state.retryCount() < maxRetryCount) {
                        state = state.toBuilder().incrementRetry().build();
                        log.info("[AgentGraph] retry #{} reason=CRITIC_UNGROUNDED thread={} detail={} (재검색 없이 답변만 재생성)",
                                state.retryCount(), state.threadId(), state.evalReason());
                        listener.onRetry("critic", state.retryCount(), state.evalReason());
                        yield Node.ANSWER;
                    }
                    yield Node.FINALIZE;
                }
                case FINALIZE -> {
                    state = finalizeService.execute(state);
                    yield Node.END;
                }
                case END -> Node.END;
            };
        }

        return state;
    }
}
