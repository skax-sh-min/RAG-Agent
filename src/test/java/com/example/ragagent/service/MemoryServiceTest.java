package com.example.ragagent.service;

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
 * piece of logic: the conversation-history char budget derived from LLM_MAX_TOKENS, which
 * README/CLAUDE.md document as "LLM_MAX_TOKENS × 0.75" with a 1,000-char floor.
 */
class MemoryServiceTest {

    private final MemoryRepository repository = mock(MemoryRepository.class);

    @Test
    @DisplayName("budget = llmMaxTokens * 0.75, getHistory 에 그대로 전달")
    void budget_derivedAsThreeQuartersOfMaxTokens() {
        MemoryService service = new MemoryService(repository, 8000);

        service.getHistory("u1", "t1");

        verify(repository).getHistory("u1", "t1", 6000);
    }

    @Test
    @DisplayName("budget 은 최소 1,000자 하한 (매우 작은 llmMaxTokens 라도)")
    void budget_hasFloorOf1000Chars() {
        MemoryService service = new MemoryService(repository, 100);

        service.getHistory("u1", "t1");

        verify(repository).getHistory(eq("u1"), eq("t1"), eq(1000));
    }
}
