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
    @DisplayName("grounded=null (검증 미실행/판정 불가) → 재시도는 없지만 통과로 위조하지도 않는다")
    void nullGrounded_staysUnverified() {
        AgentState state = newState().toBuilder()
                .retrievedDocs(List.of(new Document("chunk")))
                .grounded(null)
                .build();

        AgentState result = service.execute(state);

        // 예전에는 여기서 true 를 써넣었고, 그 값이 VerificationSnapshot 으로 흘러가 검증한 적
        // 없는 답변에 '검증됨'/'생성' 배지를 붙였다. null 은 "배지 없음"으로 이미 렌더된다.
        assertThat(result.grounded()).isNull();
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
