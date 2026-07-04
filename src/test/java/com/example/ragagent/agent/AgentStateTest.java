package com.example.ragagent.agent;

import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.model.SourceRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA — AgentState immutability and toBuilder() semantics
 *
 * Verifies the contract documented in CLAUDE.md:
 *   - records are immutable
 *   - each toBuilder().xxx().build() returns a NEW instance
 *   - accumulateTokens increments llmCallCount
 *   - constructor defaults routingMode to COST_FIRST when null
 */
class AgentStateTest {

    private AgentState newState() {
        return AgentState.of("질문", "v1", "thread-1", "", RoutingMode.COST_FIRST);
    }

    @Test
    @DisplayName("of() 후 retrievedDocs 컬렉션은 불변")
    void retrievedDocsImmutable() {
        AgentState state = newState();
        assertThatThrownBy(() -> state.retrievedDocs().add(new Document("x")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("retrievedDocs(mutable) 는 방어 복사하므로 원본 변경 영향 없음")
    void defensiveCopyOnToBuilder() {
        List<Document> mutable = new ArrayList<>();
        mutable.add(new Document("a"));
        AgentState state = newState().toBuilder().retrievedDocs(mutable).build();
        mutable.add(new Document("b"));
        assertThat(state.retrievedDocs()).hasSize(1);
    }

    @Test
    @DisplayName("accumulateTokens 호출 시 llmCallCount + 1")
    void incrementsCallCount() {
        AgentState state = newState();
        AgentState next = state.toBuilder().accumulateTokens(100, 50).build();
        assertThat(next.llmCallCount()).isEqualTo(1);
        assertThat(next.totalInputTokens()).isEqualTo(100);
        assertThat(next.totalOutputTokens()).isEqualTo(50);
    }

    @Test
    @DisplayName("accumulateTokens 가 두 번 호출되면 누적")
    void accumulatesTokens() {
        AgentState state = newState()
                .toBuilder().accumulateTokens(100, 50).build()
                .toBuilder().accumulateTokens(30, 20).build();
        assertThat(state.llmCallCount()).isEqualTo(2);
        assertThat(state.totalInputTokens()).isEqualTo(130);
        assertThat(state.totalOutputTokens()).isEqualTo(70);
    }

    @Test
    @DisplayName("incrementRetry 증분")
    void incrementsRetryCount() {
        AgentState state = newState().toBuilder().incrementRetry().build();
        assertThat(state.retryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("isDualMode 는 routingMode 가 DUAL 일 때만 true")
    void isDualMode() {
        assertThat(newState().isDualMode()).isFalse();
        AgentState dual = AgentState.of("q", "v", "t", "", RoutingMode.DUAL);
        assertThat(dual.isDualMode()).isTrue();
    }

    @Test
    @DisplayName("wasUpgraded 는 premiumUpgraded 가 null 이 아닐 때 true")
    void wasUpgraded() {
        assertThat(newState().wasUpgraded()).isFalse();
        assertThat(newState().toBuilder().premiumUpgraded("gpt-4o").build().wasUpgraded()).isTrue();
    }

    @Test
    @DisplayName("routingMode=null 이면 COST_FIRST 로 기본 설정")
    void defaultRoutingMode() {
        AgentState state = AgentState.of("q", "v", "t", "", null);
        assertThat(state.routingMode()).isEqualTo(RoutingMode.COST_FIRST);
    }

    @Test
    @DisplayName("sources(...) 가 sources 만 갱신하고 다른 필드는 보존")
    void sourcesPreservesOtherFields() {
        AgentState state = newState()
                .toBuilder().accumulateTokens(10, 20).build()
                .toBuilder().answer("answer").build();
        AgentState updated = state.toBuilder().sources(List.of(new SourceRef("f", "p", "id", 1))).build();
        assertThat(updated.answer()).isEqualTo("answer");
        assertThat(updated.totalInputTokens()).isEqualTo(10);
        assertThat(updated.sources()).hasSize(1);
    }
}
