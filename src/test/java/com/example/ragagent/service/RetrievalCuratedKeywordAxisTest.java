package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmProvider;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ProviderRole;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.repository.LlmUsageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.context.MessageSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 큐레이션 축이 <b>BM25 도</b> 본다는 것.
 *
 * <p>이 테스트가 있는 이유: {@code CuratedQaService} 가 큐레이션 청크를 {@code chunk_fts} 에
 * 쓰기 시작한 것만으로는 아무 일도 일어나지 않는다. {@code KeywordSearchRepository.search()} 는
 * {@code WHERE ... AND version = ?} 로 거르는데 일반 키워드 축은 <b>문서 version</b> 으로
 * 부르고, 큐레이션은 예약 네임스페이스 {@code "curated"} 에 있기 때문이다. 두 조각이 붙어
 * 있지 않으면 {@code excerpt_keywords} 는 저장은 되지만 아무 데서도 읽히지 않는 —
 * 정확히 예전 상태의 — 거짓 손잡이로 남는다. 그 연결을 여기서 고정한다.
 */
class RetrievalCuratedKeywordAxisTest {

    private static final String CURATED = CuratedQaService.CURATED_VERSION;

    private RagService ragService;

    @BeforeEach
    void setUp() {
        ragService = mock(RagService.class);
        when(ragService.searchBatch(anyString(), any(), anyString(), anyInt()))
                .thenReturn(List.of(List.of()));
        when(ragService.search(anyString(), anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(ragService.keywordSearch(anyString(), anyString(), anyInt())).thenReturn(List.of());
    }

    private RetrievalService service(boolean hybrid, boolean curatedEnabled) {
        AppProperties props = mock(AppProperties.class);
        when(props.searchTopKSafe()).thenReturn(5);
        when(props.searchMultiqueryEnabledSafe()).thenReturn(false);
        when(props.searchMultiqueryMinLengthSafe()).thenReturn(0);
        when(props.searchHybridEnabledSafe()).thenReturn(hybrid);
        when(props.searchRetryEscalateSafe()).thenReturn(false);
        when(props.searchRerankEnabled()).thenReturn(false);
        when(props.searchCandidateMultiplierSafe()).thenReturn(3);
        when(props.searchCuratedQaEnabledSafe()).thenReturn(curatedEnabled);
        when(props.searchCuratedQaWeightSafe()).thenReturn(1.0);
        when(props.searchRrfKeywordWeightSafe()).thenReturn(1.0);

        return new RetrievalService(stubLlmRouter(), mock(LlmUsageRepository.class), ragService,
                props, Optional.empty(), Optional.empty(), stubMessageSource(),
                new ChatImageAnalysisSkipRegistry(), new ProviderContextWindows());
    }

    /** MultiQueryExpander 의 모델을 빈 생성 시점에 꺼내므로 라우터는 실제 프로바이더를 줘야 한다. */
    private static LlmRouter stubLlmRouter() {
        LlmRouter llmRouter = mock(LlmRouter.class);
        LlmProvider expansionProvider = new LlmProvider(
                "local", TaskType.TEXT, ProviderRole.LOCAL, 0, "key", null, "model", true,
                mock(org.springframework.ai.chat.model.ChatModel.class), null);
        when(llmRouter.routeProviderWithFallback(any(), any())).thenReturn(expansionProvider);
        return llmRouter;
    }

    /** 생성자가 이 메시지로 PromptTemplate 을 즉시 만든다 — 스텁이 없으면 기동에서 죽는다. */
    private static MessageSource stubMessageSource() {
        MessageSource messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(anyString(), any(), any(java.util.Locale.class)))
                .thenReturn("{query} {number}");
        return messageSource;
    }

    private static AgentState state() {
        return AgentState.of("배포 절차", "latest", "t1", "u1", RoutingMode.COST_FIRST);
    }

    /** FTS 행에서 만든 히트 — {@code chunk_fts} 에 doc_type 컬럼이 없으니 그 키가 없다. */
    private static Document ftsHit(String id, String tags) {
        Map<String, Object> meta = new HashMap<>();
        meta.put(MetaKey.DOC_ID, "curated:7");
        meta.put(MetaKey.VERSION, CURATED);
        meta.put(MetaKey.FILENAME, "curated_qa");
        meta.put(MetaKey.TAGS, tags);
        return Document.builder().id(id).text("배포는 ArgoCD 로 한다").metadata(meta).build();
    }

    @Test
    @DisplayName("큐레이션 축은 예약 네임스페이스로 BM25 도 물어본다 (문서 version 으로는 절대 안 나온다)")
    void curatedAxis_alsoQueriesTheKeywordIndexUnderTheCuratedNamespace() {
        service(true, true).execute(state());

        verify(ragService).keywordSearch(eq(CURATED), anyString(), anyInt());
        verify(ragService).keywordSearch(eq("latest"), anyString(), anyInt());
    }

    @Test
    @DisplayName("하이브리드를 끄면 큐레이션 쪽 BM25 도 돌지 않는다 (운영자가 끈 축이 몰래 살아나지 않는다)")
    void hybridDisabled_skipsTheCuratedKeywordQueryToo() {
        service(false, true).execute(state());

        verify(ragService, never()).keywordSearch(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("큐레이션 축을 끄면 그쪽 BM25 도 돌지 않는다")
    void curatedDisabled_skipsBothCuratedQueries() {
        service(true, false).execute(state());

        verify(ragService, never()).keywordSearch(eq(CURATED), anyString(), anyInt());
        verify(ragService, never()).search(anyString(), anyString(), eq(CURATED), anyInt());
    }

    /**
     * {@code chunk_fts} 에는 {@code doc_type} 컬럼이 없다. 그 표식을 찍지 않으면
     * {@code filterByTags} 의 큐레이션 면제가 걸리지 않아, 태그 칩을 하나라도 켜는 순간
     * 태그 없는 큐레이션 항목이 통째로 탈락한다 — 그 면제가 생긴 원인이 바로 그 증상이다.
     */
    @Test
    @DisplayName("키워드로만 걸린 큐레이션 히트에 doc_type 을 찍어 태그 면제가 계속 걸리게 한다")
    void keywordOnlyCuratedHit_isMarkedSoTheTagExemptionStillApplies() {
        RetrievalService svc = service(true, true);
        // indexChunks() 가 태그 없는 청크를 빈 문자열로 쓰므로(str(null) = "") FTS 히트의
        // doc_tags 는 NULL 이 아니라 "" 다 — 스코프를 모르는 큐레이션 항목의 실제 모양.
        Document unmarked = ftsHit("curated-7", "");     // FTS 행에서 온 그대로 — doc_type 없음
        when(ragService.keywordSearch(eq(CURATED), anyString(), anyInt())).thenReturn(List.of(unmarked));

        // 표식이 없으면 태그 스코프에서 탈락한다 — 이것이 표식을 찍는 이유다.
        assertThat(svc.filterByTags(List.of(unmarked), List.of("인프라"), 10)).isEmpty();

        // 같은 히트가 축을 지나오면 살아남는다: 축이 doc_type 을 찍었기 때문이다.
        AgentState out = svc.execute(state().toBuilder().selectedTags(List.of("인프라")).build());

        assertThat(out.retrievedDocs())
                .as("태그를 고른 검색에서도 태그 없는 큐레이션 항목은 통과해야 한다")
                .extracting(Document::getId)
                .contains("curated-7");
        assertThat(out.retrievedDocs().stream()
                .filter(d -> "curated-7".equals(d.getId())).findFirst().orElseThrow()
                .getMetadata())
                .containsEntry(MetaKey.DOC_TYPE, "curated_qa");
    }
}
