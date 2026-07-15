package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmProvider;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ProviderRole;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.repository.LlmUsageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * §10.8.1 — the original-query vector search must overlap the MultiQuery expansion LLM
 * round-trip instead of waiting behind it. Confirms via timing that
 * {@code ragService.search()} (original query) starts well before an artificially slow
 * expansion call returns, and that {@code ragService.searchBatch()} only receives the
 * variant queries (original excluded, since it was already searched separately).
 */
class RetrievalServiceMultiQueryParallelTest {

    private static final long EXPANSION_DELAY_MS = 300;

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static Document doc(String id) {
        return new Document("content-" + id, Map.of("filename", id, "page_or_slide", "1"));
    }

    @Test
    @DisplayName("원본 질의 검색이 확장 LLM 호출 완료 전에 시작됨 (병렬 실행)")
    void originalQuerySearchOverlapsExpansion() {
        AtomicLong testStartNanos = new AtomicLong(System.nanoTime());
        AtomicLong searchInvokedAtNanos = new AtomicLong(-1);

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            Thread.sleep(EXPANSION_DELAY_MS);
            return chatResponse("변형질문1\n변형질문2");
        });

        RagService ragService = mock(RagService.class);
        when(ragService.search(anyString(), anyString(), anyString(), anyInt())).thenAnswer(inv -> {
            searchInvokedAtNanos.set(System.nanoTime());
            return List.of(doc("original"));
        });
        when(ragService.searchBatch(anyString(), any(), anyString(), anyInt()))
                .thenReturn(List.of(List.of(doc("variant"))));

        AppProperties props = mock(AppProperties.class);
        when(props.searchTopK()).thenReturn(5);
        when(props.searchMultiqueryEnabled()).thenReturn(true);
        when(props.searchMultiqueryMinLengthSafe()).thenReturn(0);
        when(props.searchHybridEnabled()).thenReturn(false);
        when(props.searchRetryEscalateSafe()).thenReturn(false);
        when(props.searchRerankEnabled()).thenReturn(false);
        when(props.searchCandidateMultiplierSafe()).thenReturn(3);
        when(props.searchTagCandidateMultiplierSafe()).thenReturn(2);

        LlmRouter llmRouter = mock(LlmRouter.class);
        LlmProvider expansionProvider = new LlmProvider(
                "local", TaskType.TEXT, ProviderRole.LOCAL, 0, "key", null, "model", true, chatModel, null);
        when(llmRouter.routeProviderWithFallback(any(), any())).thenReturn(expansionProvider);

        RetrievalService svc = new RetrievalService(llmRouter, mock(LlmUsageRepository.class), ragService, props,
                Optional.empty(), Optional.empty());

        testStartNanos.set(System.nanoTime());
        AgentState result = svc.execute(
                AgentState.of("이것은 확장 대상이 되는 충분히 긴 질문입니다", "latest", "t1", "", RoutingMode.COST_FIRST));

        long elapsedToSearchMs = (searchInvokedAtNanos.get() - testStartNanos.get()) / 1_000_000;
        assertThat(elapsedToSearchMs)
                .as("original-query search should start immediately, not after the expansion delay")
                .isLessThan(EXPANSION_DELAY_MS);

        verify(ragService).searchBatch(eq("anonymous"), eq(List.of("변형질문1", "변형질문2")), eq("latest"), anyInt());
        assertThat(result.retrievedDocs()).isNotEmpty();
    }
}
