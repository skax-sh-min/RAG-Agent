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
 * QA — AgentState immutability and withXxx semantics
 *
 * Verifies the contract documented in CLAUDE.md:
 *   - records are immutable
 *   - each withXxx returns a NEW instance
 *   - withTokensAccumulated increments llmCallCount
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
    @DisplayName("withRetrievedDocs 는 방어 복사하므로 원본 변경 영향 없음")
    void defensiveCopyOnWith() {
        List<Document> mutable = new ArrayList<>();
        mutable.add(new Document("a"));
        AgentState state = newState().withRetrievedDocs(mutable);
        mutable.add(new Document("b"));
        assertThat(state.retrievedDocs()).hasSize(1);
    }

    @Test
    @DisplayName("withTokensAccumulated 호출 시 llmCallCount + 1")
    void incrementsCallCount() {
        AgentState state = newState();
        AgentState next = state.withTokensAccumulated(100, 50);
        assertThat(next.llmCallCount()).isEqualTo(1);
        assertThat(next.totalInputTokens()).isEqualTo(100);
        assertThat(next.totalOutputTokens()).isEqualTo(50);
    }

    @Test
    @DisplayName("withTokensAccumulated 가 두 번 호출되면 누적")
    void accumulatesTokens() {
        AgentState state = newState()
                .withTokensAccumulated(100, 50)
                .withTokensAccumulated(30, 20);
        assertThat(state.llmCallCount()).isEqualTo(2);
        assertThat(state.totalInputTokens()).isEqualTo(130);
        assertThat(state.totalOutputTokens()).isEqualTo(70);
    }

    @Test
    @DisplayName("withRetryCountIncremented 증분")
    void incrementsRetryCount() {
        AgentState state = newState().withRetryCountIncremented();
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
        assertThat(newState().withPremiumUpgraded("gpt-4o").wasUpgraded()).isTrue();
    }

    @Test
    @DisplayName("routingMode=null 이면 COST_FIRST 로 기본 설정")
    void defaultRoutingMode() {
        AgentState state = AgentState.of("q", "v", "t", "", null);
        assertThat(state.routingMode()).isEqualTo(RoutingMode.COST_FIRST);
    }

    @Test
    @DisplayName("withSources 가 sources 만 갱신하고 다른 필드는 보존")
    void withSourcesPreservesOtherFields() {
        AgentState state = newState()
                .withTokensAccumulated(10, 20)
                .withAnswer("answer");
        AgentState updated = state.withSources(List.of(new SourceRef("f", "p", "id", 1)));
        assertThat(updated.answer()).isEqualTo("answer");
        assertThat(updated.totalInputTokens()).isEqualTo(10);
        assertThat(updated.sources()).hasSize(1);
    }
}
