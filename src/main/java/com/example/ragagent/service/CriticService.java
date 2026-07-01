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
        // null = evaluation skipped/failed → fail-safe to grounded (no retry).
        boolean grounded = state.grounded() == null || state.grounded();
        return state.toBuilder().grounded(grounded).needsRetry(!grounded).build();
    }
}
