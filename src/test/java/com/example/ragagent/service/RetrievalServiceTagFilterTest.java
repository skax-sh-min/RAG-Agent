package com.example.ragagent.service;

import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmProvider;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ProviderRole;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.repository.LlmUsageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.context.MessageSource;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** RetrievalService 태그 엄격(AND) post-filter 동작. */
class RetrievalServiceTagFilterTest {

    private RagService rag;
    private RetrievalService svc;

    @BeforeEach
    void setup() {
        AppProperties props = mock(AppProperties.class);
        when(props.searchTopKSafe()).thenReturn(7);
        when(props.searchMultiqueryEnabledSafe()).thenReturn(false);  // shouldExpand=false → LLM 미호출
        when(props.searchMultiqueryMinLengthSafe()).thenReturn(0);
        when(props.searchHybridEnabledSafe()).thenReturn(false);
        when(props.searchRetryEscalateSafe()).thenReturn(false);
        when(props.searchRerankEnabled()).thenReturn(false);
        when(props.searchCandidateMultiplierSafe()).thenReturn(3);
        when(props.searchTagCandidateMultiplierSafe()).thenReturn(2);
        rag = mock(RagService.class);
        LlmRouter llmRouter = mock(LlmRouter.class);
        LlmProvider expansionProvider = new LlmProvider(
                "local", TaskType.TEXT, ProviderRole.LOCAL, 0, "key", null, "model", true, mock(ChatModel.class), null);
        when(llmRouter.routeProviderWithFallback(any(), any())).thenReturn(expansionProvider);
        MessageSource messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("{query} {number}");
        svc = new RetrievalService(llmRouter, mock(LlmUsageRepository.class), rag, props, Optional.empty(), Optional.empty(), messageSource, new ChatImageAnalysisSkipRegistry(), new ProviderContextWindows());
    }

    private static Document doc(String id, String tagsCsv) {
        Map<String, Object> m = new HashMap<>();
        m.put(MetaKey.DOC_ID, id);
        m.put(MetaKey.CHUNK_INDEX, 0);
        if (tagsCsv != null) m.put(MetaKey.TAGS, tagsCsv);
        return new Document(id + " content", m);
    }

    private static AgentState state(List<String> tags) {
        return AgentState.of("질문", "latest", "t1", "anonymous", "", RoutingMode.COST_FIRST, false, Locale.KOREAN)
                .toBuilder().selectedTags(tags).build();
    }

    private static List<Object> docIds(AgentState s) {
        return s.retrievedDocs().stream().map(d -> d.getMetadata().get(MetaKey.DOC_ID)).toList();
    }

    @Test
    @DisplayName("AND 필터: 선택 태그를 모두 가진 청크만 통과")
    void andFilter_onlyMatching() {
        Document d1 = doc("d1", "a,b,c");
        Document d2 = doc("d2", "a");
        Document d3 = doc("d3", null);
        when(rag.searchBatch(any(), any(), any(), anyInt())).thenReturn(List.of(List.of(d1, d2, d3)));

        assertThat(docIds(svc.execute(state(List.of("a", "b"))))).containsExactly("d1");
    }

    @Test
    @DisplayName("태그 미선택 → 필터 없이 기존 동작(전부 반환)")
    void noTags_passthrough() {
        Document d1 = doc("d1", "a");
        Document d2 = doc("d2", null);
        when(rag.searchBatch(any(), any(), any(), anyInt())).thenReturn(List.of(List.of(d1, d2)));

        assertThat(docIds(svc.execute(state(List.of())))).containsExactlyInAnyOrder("d1", "d2");
    }

    @Test
    @DisplayName("fallback 경로(searchBatch 예외)에서도 태그 필터가 적용된다")
    void fallbackPath_appliesFilter() {
        Document d1 = doc("d1", "a,b");
        Document d2 = doc("d2", "a");
        when(rag.searchBatch(any(), any(), any(), anyInt())).thenThrow(new RuntimeException("boom"));
        when(rag.search(any(), any(), any(), anyInt())).thenReturn(List.of(d1, d2));

        assertThat(docIds(svc.execute(state(List.of("a", "b"))))).containsExactly("d1");
    }

    @Test
    @DisplayName("filterByTags: 직접 호출 — AND 매칭 / 빈 선택 pass-through")
    void filterByTags_direct() {
        Document d1 = doc("d1", "a,b,c");
        Document d2 = doc("d2", "a");
        assertThat(svc.filterByTags(List.of(d1, d2), List.of("a", "b"), 10)).containsExactly(d1);
        assertThat(svc.filterByTags(List.of(d1, d2), List.of(), 10)).hasSize(2);
    }

    /** DOC_TYPE=curated_qa 힌트를 단 큐레이션 히트 (태그가 null 이면 키 자체를 넣지 않는다). */
    private static Document curatedHit(String tagsCsv) {
        Map<String, Object> m = new HashMap<>();
        m.put(MetaKey.DOC_TYPE, "curated_qa");
        m.put(MetaKey.DOC_ID, "curated:1");
        if (tagsCsv != null) m.put(MetaKey.TAGS, tagsCsv);
        return new Document("큐레이션 답변", m);
    }

    @Test
    @DisplayName("filterByTags — 태그 없는 큐레이션 항목은 통과시키되, 태그 없는 문서 청크는 그대로 탈락시킨다")
    void filterByTags_exemptsScopelessCuratedEntriesOnly() {
        // 이전에는 큐레이션 항목에 태그 메타데이터가 아예 없어, 태그를 하나라도 고르면
        // 좋아요한 답변이 전부 사라졌다.
        List<Document> candidates = List.of(
                curatedHit(null),        // 스코프 미상 → 통과
                curatedHit("설계"),       // 일치 → 통과
                curatedHit("운영"),       // 불일치 → 탈락
                doc("d1", null),         // 태그 없는 문서 → 탈락(엄격 AND 유지)
                doc("d2", "설계"));       // 일치 문서 → 통과

        List<Document> out = svc.filterByTags(candidates, List.of("설계"), 10);

        assertThat(out).extracting(d -> d.getMetadata().get(MetaKey.DOC_ID))
                .containsExactly("curated:1", "curated:1", "d2");
    }

    @Test
    @DisplayName("filterByTags — 선택 태그가 없으면 큐레이션·문서 모두 통과(기존 동작)")
    void filterByTags_emptySelectionPassesEverything() {
        List<Document> candidates = List.of(curatedHit(null), doc("d1", null));

        assertThat(svc.filterByTags(candidates, List.of(), 10)).hasSize(2);
    }
}
