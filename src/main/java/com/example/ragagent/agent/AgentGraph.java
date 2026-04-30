package com.example.ragagent.agent;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.service.*;
import org.springframework.stereotype.Component;

/**
 * Agent execution graph — equivalent to LangGraph StateGraph in the Python version.
 *
 * Flow:
 *   START → CLASSIFIER
 *     ├─ (meta)  → DIRECT_ANSWER → FINALIZE → END
 *     └─ (other) → RETRIEVAL → ANSWER
 *                                ├─ (needs_retry) → RETRIEVAL (loop)
 *                                └─ (ok)          → CRITIC
 *                                                    ├─ (needs_retry) → RETRIEVAL
 *                                                    └─ (ok)          → FINALIZE → END
 */
@Component
public class AgentGraph {

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

    private AgentState runInternal(AgentState initialState, GraphListener listener) {
        Node current = Node.CLASSIFIER;
        AgentState state = initialState;

        while (current != Node.END) {
            current = switch (current) {
                case CLASSIFIER -> {
                    listener.onNodeEnter("classifier");
                    if (state.questionType() != null) {
                        yield "meta".equals(state.questionType()) ? Node.DIRECT_ANSWER : Node.RETRIEVAL;
                    }
                    state = classifierService.execute(state);
                    yield "meta".equals(state.questionType()) ? Node.DIRECT_ANSWER : Node.RETRIEVAL;
                }
                case DIRECT_ANSWER -> {
                    state = directAnswerService.execute(state);
                    yield Node.FINALIZE;
                }
                case RETRIEVAL -> {
                    listener.onNodeEnter("retrieval");
                    state = state.withRetryCountIncremented();
                    state = retrievalService.execute(state);
                    listener.onSourcesReady(state.sources());
                    yield Node.ANSWER;
                }
                case ANSWER -> {
                    listener.onNodeEnter("answer");
                    state = (listener == GraphListener.NOOP)
                            ? answerService.execute(state)
                            : answerService.executeStreaming(state, listener);
                    if (state.isDualMode()) yield Node.FINALIZE;
                    if (state.needsRetry() && state.retryCount() < maxRetryCount) {
                        yield Node.RETRIEVAL;
                    }
                    yield Node.CRITIC;
                }
                case CRITIC -> {
                    listener.onNodeEnter("critic");
                    state = criticService.execute(state);
                    if (state.needsRetry() && state.retryCount() < maxRetryCount) {
                        yield Node.RETRIEVAL;
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
