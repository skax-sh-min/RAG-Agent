package com.example.ragagent.agent;

import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.model.SourceRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA — AgentState.Builder: 22-field copy-constructor + partial-modify invariant
 *
 * Covers (per refactoring/04-agent-state-builder.md):
 *  - builder() 팩토리로 신규 상태 구성
 *  - toBuilder() copy-constructor 가 모든 필드를 보존
 *  - 단일 필드 수정 시 나머지 21개 필드 불변 유지
 *  - accumulateTokens 누적 동작
 *  - incrementRetry 편의 메서드
 */
class AgentStateBuilderTest {

    private AgentState fullState() {
        return AgentState.builder()
                .question("질문")
                .version("v1")
                .threadId("t1")
                .questionType("manual")
                .retrievedDocs(List.of(new Document("텍스트", Map.of())))
                .sources(List.of(new SourceRef("doc.pdf | v1 | p.3", "snippet", "chunk_1", "doc_abc", 3)))
                .retrievalWarnings(List.of("warning1"))
                .imageRefs(List.of("data/images/img1.png"))
                .answer("최종 답변")
                .retryCount(1)
                .needsRetry(false)
                .conversationHistory("이전 대화")
                .accumulateTokens(100, 200)
                .accumulateTokens(50, 60)
                .routingMode(RoutingMode.QUALITY_FIRST)
                .usedProvider("gemini-flash")
                .premiumUpgraded("gemini-pro")
                .grounded(true)
                .directMode(false)
                .locale(Locale.KOREAN)
                .build();
    }

    @Test
    @DisplayName("builder() 팩토리 — 22개 필드 직접 설정 후 build() 정확히 반영")
    void builder_setsAllFields() {
        AgentState s = fullState();

        assertThat(s.question()).isEqualTo("질문");
        assertThat(s.version()).isEqualTo("v1");
        assertThat(s.threadId()).isEqualTo("t1");
        assertThat(s.questionType()).isEqualTo("manual");
        assertThat(s.retrievedDocs()).hasSize(1);
        assertThat(s.sources()).hasSize(1);
        assertThat(s.retrievalWarnings()).containsExactly("warning1");
        assertThat(s.imageRefs()).containsExactly("data/images/img1.png");
        assertThat(s.answer()).isEqualTo("최종 답변");
        assertThat(s.retryCount()).isEqualTo(1);
        assertThat(s.needsRetry()).isFalse();
        assertThat(s.conversationHistory()).isEqualTo("이전 대화");
        assertThat(s.totalInputTokens()).isEqualTo(150);
        assertThat(s.totalOutputTokens()).isEqualTo(260);
        assertThat(s.llmCallCount()).isEqualTo(2);
        assertThat(s.routingMode()).isEqualTo(RoutingMode.QUALITY_FIRST);
        assertThat(s.usedProvider()).isEqualTo("gemini-flash");
        assertThat(s.premiumUpgraded()).isEqualTo("gemini-pro");
        assertThat(s.grounded()).isTrue();
        assertThat(s.directMode()).isFalse();
        assertThat(s.locale()).isEqualTo(Locale.KOREAN);
    }

    @Test
    @DisplayName("toBuilder() copy-constructor — 22개 필드 모두 보존")
    void toBuilder_copyConstructor_preservesAllFields() {
        AgentState original = fullState();
        AgentState copy = original.toBuilder().build();

        assertThat(copy.question()).isEqualTo(original.question());
        assertThat(copy.version()).isEqualTo(original.version());
        assertThat(copy.threadId()).isEqualTo(original.threadId());
        assertThat(copy.questionType()).isEqualTo(original.questionType());
        assertThat(copy.retrievedDocs()).isEqualTo(original.retrievedDocs());
        assertThat(copy.sources()).isEqualTo(original.sources());
        assertThat(copy.retrievalWarnings()).isEqualTo(original.retrievalWarnings());
        assertThat(copy.imageRefs()).isEqualTo(original.imageRefs());
        assertThat(copy.answer()).isEqualTo(original.answer());
        assertThat(copy.retryCount()).isEqualTo(original.retryCount());
        assertThat(copy.needsRetry()).isEqualTo(original.needsRetry());
        assertThat(copy.conversationHistory()).isEqualTo(original.conversationHistory());
        assertThat(copy.totalInputTokens()).isEqualTo(original.totalInputTokens());
        assertThat(copy.totalOutputTokens()).isEqualTo(original.totalOutputTokens());
        assertThat(copy.llmCallCount()).isEqualTo(original.llmCallCount());
        assertThat(copy.routingMode()).isEqualTo(original.routingMode());
        assertThat(copy.usedProvider()).isEqualTo(original.usedProvider());
        assertThat(copy.premiumUpgraded()).isEqualTo(original.premiumUpgraded());
        assertThat(copy.grounded()).isEqualTo(original.grounded());
        assertThat(copy.directMode()).isEqualTo(original.directMode());
        assertThat(copy.locale()).isEqualTo(original.locale());
    }

    @Test
    @DisplayName("단일 필드 수정 — 나머지 21개 필드 불변")
    void toBuilder_modifySingleField_otherFieldsPreserved() {
        AgentState original = fullState();
        AgentState modified = original.toBuilder().answer("새 답변").build();

        assertThat(modified.answer()).isEqualTo("새 답변");
        assertThat(modified.question()).isEqualTo(original.question());
        assertThat(modified.totalInputTokens()).isEqualTo(original.totalInputTokens());
        assertThat(modified.llmCallCount()).isEqualTo(original.llmCallCount());
        assertThat(modified.routingMode()).isEqualTo(original.routingMode());
        assertThat(modified.locale()).isEqualTo(original.locale());
    }

    @Test
    @DisplayName("accumulateTokens — 누적 합산 + llmCallCount 증가")
    void accumulateTokens_accumulates() {
        AgentState s = AgentState.builder()
                .question("q").version("v").threadId("t")
                .accumulateTokens(100, 50)
                .accumulateTokens(200, 80)
                .accumulateTokens(50, 20)
                .build();

        assertThat(s.totalInputTokens()).isEqualTo(350);
        assertThat(s.totalOutputTokens()).isEqualTo(150);
        assertThat(s.llmCallCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("incrementRetry — retryCount 1 증가, 나머지 보존")
    void incrementRetry_incrementsOnlyRetryCount() {
        AgentState s = AgentState.builder()
                .question("q").version("v").threadId("t")
                .retryCount(2)
                .answer("기존 답변")
                .build();

        AgentState incremented = s.toBuilder().incrementRetry().build();

        assertThat(incremented.retryCount()).isEqualTo(3);
        assertThat(incremented.answer()).isEqualTo("기존 답변");
        assertThat(incremented.totalInputTokens()).isEqualTo(0);
    }
}
