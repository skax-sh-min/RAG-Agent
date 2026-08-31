package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import org.springframework.stereotype.Service;

/**
 * Validates that the generated answer is grounded in the retrieved documents.
 *
 * <p>grounding is now computed in {@link AnswerService}'s single evaluation call
 * (alongside sufficiency). This node merely consumes the precomputed {@code grounded}
 * flag — no second LLM round-trip — and decides whether a retry is needed.
 */
@Service
public class CriticService {

    public AgentState execute(AgentState state) {
        if (state.retrievedDocs().isEmpty()) {
            return state.toBuilder().needsRetry(false).build();
        }
        // null = 검증이 돌지 않았거나 판정을 읽지 못했다. 재시도는 걸지 않되(검증기 고장이 완성된
        // 답변의 전달을 막아서는 안 된다) 통과로 위조하지도 않는다 — 예전에는 여기서 true 를
        // 써넣었고, 그 값이 그대로 VerificationSnapshot 에 실려 검증한 적 없는 답변에 '검증됨'/'생성'
        // 배지가 붙었다. grounded=null 은 이미 "배지 없음"으로 렌더된다(VerificationSnapshot).
        if (state.grounded() == null) {
            return state.toBuilder().needsRetry(false).build();
        }
        boolean grounded = state.grounded();
        return state.toBuilder().grounded(grounded).needsRetry(!grounded).build();
    }
}
