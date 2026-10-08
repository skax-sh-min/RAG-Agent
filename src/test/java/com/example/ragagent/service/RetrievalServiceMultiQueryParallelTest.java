package com.example.ragagent.service;

import com.example.ragagent.llm.ProviderContextWindows;
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
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
 * round-trip instead of waiting behind it. The expansion call waits for the original-query
 * {@code ragService.search()} to start: when the two overlap, the wait ends at once; when the
 * search is serialized behind the expansion, it can only start after the expansion returns, so
 * the wait times out. Also confirms that {@code ragService.searchBatch()} only receives the
 * variant queries (original excluded, since it was already searched separately).
 *
 * <p>This used to be a stopwatch check: the expansion slept 300ms and the search had to start
 * within 300ms. Under the full parallel test run, overhead alone can reach a bound like that —
 * {@code AgentServiceTest.chat_runsHistoryAndClassifyInParallel} had the same shape and failed at
 * exactly its bound.
 */
class RetrievalServiceMultiQueryParallelTest {

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static Document doc(String id) {
        return new Document("content-" + id, Map.of("filename", id, "page_or_slide", "1"));
    }

    @Test
    @DisplayName("원본 질의 검색이 확장 LLM 호출 완료 전에 시작됨 (병렬 실행)")
    void originalQuerySearchOverlapsExpansion() {
        CountDownLatch originalSearchStarted = new CountDownLatch(1);
        AtomicBoolean startedDuringExpansion = new AtomicBoolean();

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            startedDuringExpansion.set(originalSearchStarted.await(5, TimeUnit.SECONDS));
            return chatResponse("변형질문1\n변형질문2");
        });

        RagService ragService = mock(RagService.class);
        // 버전으로 원본 질의 검색만 집는다 — 큐레이션 축도 ragService.search() 를 부른다(예약 버전 "curated").
        when(ragService.search(anyString(), anyString(), eq("latest"), anyInt())).thenAnswer(inv -> {
            originalSearchStarted.countDown();
            return List.of(doc("original"));
        });
        when(ragService.searchBatch(anyString(), any(), anyString(), anyInt()))
                .thenReturn(List.of(List.of(doc("variant"))));

        AppProperties props = mock(AppProperties.class);
        when(props.searchTopKSafe()).thenReturn(5);
        when(props.searchMultiqueryEnabledSafe()).thenReturn(true);
        when(props.searchMultiqueryMinLengthSafe()).thenReturn(0);
        when(props.searchHybridEnabledSafe()).thenReturn(false);
        when(props.searchRetryEscalateSafe()).thenReturn(false);
        when(props.searchRerankEnabled()).thenReturn(false);
        when(props.searchCandidateMultiplierSafe()).thenReturn(3);
        when(props.searchTagCandidateMultiplierSafe()).thenReturn(2);

        LlmRouter llmRouter = mock(LlmRouter.class);
        LlmProvider expansionProvider = new LlmProvider(
                "local", TaskType.TEXT, ProviderRole.LOCAL, 0, "key", null, "model", true, chatModel, null);
        when(llmRouter.routeProviderWithFallback(any(), any())).thenReturn(expansionProvider);
        MessageSource messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("{query} {number}");

        RetrievalService svc = new RetrievalService(llmRouter, mock(LlmUsageRepository.class), ragService, props,
                Optional.empty(), Optional.empty(), messageSource, new ChatImageAnalysisSkipRegistry(), new ProviderContextWindows());

        AgentState result = svc.execute(
                AgentState.of("이것은 확장 대상이 되는 충분히 긴 질문입니다", "latest", "t1", "", RoutingMode.COST_FIRST));

        assertThat(startedDuringExpansion.get())
                .as("original-query search should start while the expansion call is still in flight "
                        + "(serialized behind it, it starts only after the expansion's 5s wait)")
                .isTrue();

        verify(ragService).searchBatch(eq("anonymous"), eq(List.of("변형질문1", "변형질문2")), eq("latest"), anyInt());
        assertThat(result.retrievedDocs()).isNotEmpty();
    }
}
