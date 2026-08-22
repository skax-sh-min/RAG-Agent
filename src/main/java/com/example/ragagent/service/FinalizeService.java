package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.model.SourceRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Last node of the agent graph — turn persistence has been moved to
 * AgentService and StreamingAgentService so elapsed time is available.
 * Equivalent to finalize_node in agents.py.
 *
 * <p>It does own one computation: the per-source 응답 참여도 (2단계). This is the only point in the
 * graph where the answer and the retrieved chunks are both final — ANSWER can run several times
 * (sufficiency retry, CRITIC retry, PROGRESSIVE upgrade), and computing attribution on an answer
 * that is about to be discarded would just be thrown away. Cost is pure CPU (no LLM/embedding
 * call), so running it once here is cheap enough to be unconditional.
 */
@Service
public class FinalizeService {

    private static final Logger log = LoggerFactory.getLogger(FinalizeService.class);

    public AgentState execute(AgentState state) {
        List<SourceRef> sources = state.sources();
        if (sources.isEmpty() || state.answer() == null || state.answer().isBlank()) {
            return state;   // meta/direct turns and failed turns have nothing to attribute
        }
        try {
            AnswerAttribution.Result attribution = AnswerAttribution.compute(
                    state.answer(), state.retrievedDocs(), state.usedDocIndices());
            if (attribution.method() == AnswerAttribution.Method.NONE) {
                // 참여도를 못 구해도 유사도만으로 정렬은 성립한다 (SourceRef.DISPLAY_ORDER 2순위).
                return state.toBuilder().sources(SourceRef.sortedForDisplay(sources)).build();
            }
            log.debug("[ATTRIBUTION] thread={} method={} chunks={}",
                    state.threadId(), attribution.method().wireValue(), attribution.sharesByChunkId().size());
            // 참여도를 붙인 '뒤에' 정렬한다 — 1순위 키가 방금 생겼기 때문.
            return state.toBuilder()
                        .sources(SourceRef.sortedForDisplay(
                                AnswerAttribution.applyTo(sources, attribution)))
                        .build();
        } catch (Exception e) {
            // Attribution is a diagnostic. It must never be the reason a finished answer fails to
            // reach the user, so a bug here degrades to "no shares shown" rather than propagating.
            log.warn("[ATTRIBUTION] 계산 실패 thread={} — 참여도 없이 진행: {}", state.threadId(), e.toString());
            return state;
        }
    }
}
