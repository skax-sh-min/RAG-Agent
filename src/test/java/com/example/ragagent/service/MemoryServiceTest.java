package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.repository.MemoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA — MemoryService (EDIT.md #1)
 *
 * Every public method besides the constructor is one-line delegation to MemoryRepository
 * (not worth testing individually beyond compile-time wiring). This focuses on the one real
 * piece of logic: the conversation-history char budget derived from app.llm.max-tokens
 * (LLM_MAX_TOKENS, §6.18), which README/CLAUDE.md document as "LLM_MAX_TOKENS × 0.5" with a
 * 1,000-char floor.
 */
class MemoryServiceTest {

    private final MemoryRepository repository = mock(MemoryRepository.class);

    private static AppProperties propsWithMaxTokens(int maxTokens) {
        AppProperties props = mock(AppProperties.class);
        when(props.llmSafe()).thenReturn(new AppProperties.LlmConfig(
                java.util.List.of(), 2, 10, 180, "COST_FIRST", 3, 20, 0.0, 0.1, 0.0, 0.7, true, maxTokens, 1, true));
        return props;
    }

    @Test
    @DisplayName("budget = llmMaxTokens * 0.5, getHistory 에 그대로 전달")
    void budget_derivedAsHalfOfMaxTokens() {
        MemoryService service = new MemoryService(repository, propsWithMaxTokens(8000));

        service.getHistory("u1", "t1");

        verify(repository).getHistory("u1", "t1", 4000);
    }

    @Test
    @DisplayName("budget 은 최소 1,000자 하한 (매우 작은 llmMaxTokens 라도)")
    void budget_hasFloorOf1000Chars() {
        MemoryService service = new MemoryService(repository, propsWithMaxTokens(100));

        service.getHistory("u1", "t1");

        verify(repository).getHistory(eq("u1"), eq("t1"), eq(1000));
    }

    // ── §10.13 이 턴의 예산 ──────────────────────────────────────────────────

    private static MemoryService withWindow(MemoryRepository repository, int maxTokens,
                                            String provider, int windowTokens) {
        com.example.ragagent.llm.LlmRouter router = mock(com.example.ragagent.llm.LlmRouter.class);
        when(router.findProviderName(eq(com.example.ragagent.llm.TaskType.TEXT),
                org.mockito.ArgumentMatchers.any())).thenReturn(provider);
        var windows = new com.example.ragagent.llm.ProviderContextWindows();
        if (windowTokens > 0) {
            windows.record(provider, windowTokens,
                    com.example.ragagent.llm.ProviderContextWindows.Source.PROBED);
        }
        return new MemoryService(repository, propsWithMaxTokens(maxTokens), router, windows);
    }

    @Test
    @DisplayName("§10.13 — Direct 턴은 문서가 비운 자리를 이력으로 받는다")
    void directTurn_getsTheEmptyDocumentSlot() {
        MemoryService service = withWindow(repository, 10_000, "local", 40_960);

        int direct = service.maxConversationChars(true, com.example.ragagent.model.ResponseMode.N,
                com.example.ragagent.llm.RoutingMode.COST_FIRST, true, "질문");

        // 40,960 − 5,000(N 스트리밍 출력 예약) − 4,096(여유) − 질문 − 1,000(고정비)
        assertThat(direct).isGreaterThan(30_000);
        assertThat(direct).isGreaterThan(service.maxConversationChars() * 5);
    }

    @Test
    @DisplayName("§10.13 — RAG 턴은 지금의 고정값 그대로다 (검색이 몇 개를 가져올지 아직 모른다)")
    void ragTurn_keepsTheFixedBudget() {
        MemoryService service = withWindow(repository, 10_000, "local", 40_960);

        assertThat(service.maxConversationChars(false, com.example.ragagent.model.ResponseMode.N,
                com.example.ragagent.llm.RoutingMode.COST_FIRST, true, "질문"))
                .isEqualTo(service.maxConversationChars());
    }

    @Test
    @DisplayName("§10.13 — 창을 모르면 Direct 턴도 고정값 그대로 (추측으로 맥락을 늘리지 않는다)")
    void unknownWindow_keepsTheFixedBudget() {
        MemoryService service = withWindow(repository, 10_000, "local", 0);

        assertThat(service.maxConversationChars(true, com.example.ragagent.model.ResponseMode.N,
                com.example.ragagent.llm.RoutingMode.COST_FIRST, true, "질문"))
                .isEqualTo(service.maxConversationChars());
    }

    @Test
    @DisplayName("§10.13 — 스트리밍은 출력 예약이 작아 이력에 줄 자리가 더 넓다")
    void streamingReservesLessForOutput() {
        MemoryService service = withWindow(repository, 10_000, "local", 40_960);
        var n = com.example.ragagent.model.ResponseMode.N;
        var mode = com.example.ragagent.llm.RoutingMode.COST_FIRST;

        assertThat(service.maxConversationChars(true, n, mode, true, "질문"))
                .isGreaterThan(service.maxConversationChars(true, n, mode, false, "질문"));
    }
}
