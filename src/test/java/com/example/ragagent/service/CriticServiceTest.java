package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.llm.RoutingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA — CriticService grounded/needsRetry branching (EDIT.md #1)
 */
class CriticServiceTest {

    private final CriticService service = new CriticService();

    private AgentState newState() {
        return AgentState.of("질문", "v1", "t1", "", RoutingMode.COST_FIRST);
    }

    @Test
    @DisplayName("retrievedDocs 비어있으면 grounded 값과 무관하게 needsRetry=false")
    void emptyRetrievedDocs_neverRetries() {
        AgentState state = newState().toBuilder().grounded(false).build();

        AgentState result = service.execute(state);

        assertThat(result.needsRetry()).isFalse();
    }

    @Test
    @DisplayName("grounded=null (평가 스킵/실패) → fail-safe 로 grounded=true, needsRetry=false")
    void nullGrounded_failsSafeToGrounded() {
        AgentState state = newState().toBuilder()
                .retrievedDocs(List.of(new Document("chunk")))
                .grounded(null)
                .build();

        AgentState result = service.execute(state);

        assertThat(result.grounded()).isTrue();
        assertThat(result.needsRetry()).isFalse();
    }

    @Test
    @DisplayName("grounded=false + 문서 있음 → needsRetry=true")
    void groundedFalse_triggersRetry() {
        AgentState state = newState().toBuilder()
                .retrievedDocs(List.of(new Document("chunk")))
                .grounded(false)
                .build();

        AgentState result = service.execute(state);

        assertThat(result.grounded()).isFalse();
        assertThat(result.needsRetry()).isTrue();
    }

    @Test
    @DisplayName("grounded=true + 문서 있음 → needsRetry=false")
    void groundedTrue_noRetry() {
        AgentState state = newState().toBuilder()
                .retrievedDocs(List.of(new Document("chunk")))
                .grounded(true)
                .build();

        AgentState result = service.execute(state);

        assertThat(result.grounded()).isTrue();
        assertThat(result.needsRetry()).isFalse();
    }
}
