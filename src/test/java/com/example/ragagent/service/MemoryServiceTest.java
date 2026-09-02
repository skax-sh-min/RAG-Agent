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
}
