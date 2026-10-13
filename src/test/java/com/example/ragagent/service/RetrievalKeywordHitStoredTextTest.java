package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmProvider;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.llm.ProviderRole;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.model.SourceRef;
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
 * 키워드(BM25) 축으로만 찾힌 청크는 <b>저장된 원문</b>으로 답변 쪽에 간다.
 *
 * <p>{@code chunk_fts.content} 는 검색용 파생 텍스트(파일명·맥락 요약 + 장식을 걷은 본문)라, 융합이 벡터 쪽 문서로
 * 바꿔 주지 못하는 청크 — 키워드 축에만 있던 것 — 는 그 텍스트가 그대로 답변 프롬프트·검증 발췌·출처 미리보기로
 * 갔다. 그 경로를 {@code execute()} 전체로 고정한다.
 */
class RetrievalKeywordHitStoredTextTest {

    private static final String FTS_TEXT = "운영가이드.md | 이 청크는 배포 절차를 요약한다. | 배포 절차 1 빌드 2 승인";
    private static final String ORIGINAL = "## 배포 절차\n\n1. **빌드**\n2. 승인";

    private RagService ragService;

    @BeforeEach
    void setUp() {
        ragService = mock(RagService.class);
        when(ragService.search(anyString(), anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(ragService.searchBatch(anyString(), any(), anyString(), anyInt()))
                .thenReturn(List.of(List.of(vectorHit("v1", "doc-a", 0))));
        when(ragService.keywordSearch(eq("latest"), anyString(), anyInt()))
                .thenReturn(List.of(ftsHit("k1", "doc-b", "3"), ftsHit("v1", "doc-a", "0")));
        when(ragService.keywordSearch(eq(CuratedQaService.CURATED_VERSION), anyString(), anyInt())).thenReturn(List.of());
    }

    private RetrievalService service() {
        AppProperties props = mock(AppProperties.class);
        when(props.searchTopKSafe()).thenReturn(5);
        when(props.searchMultiqueryEnabledSafe()).thenReturn(false);
        when(props.searchMultiqueryMinLengthSafe()).thenReturn(0);
        when(props.searchHybridEnabledSafe()).thenReturn(true);
        when(props.searchRetryEscalateSafe()).thenReturn(false);
        when(props.searchRerankEnabled()).thenReturn(false);
        when(props.searchCandidateMultiplierSafe()).thenReturn(3);
        when(props.searchCuratedQaEnabledSafe()).thenReturn(false);
        when(props.searchRrfKeywordWeightSafe()).thenReturn(1.0);
        when(props.searchRrfKSafe()).thenReturn(60);
        LlmRouter llmRouter = mock(LlmRouter.class);
        RouterTestSupport.executeOn(llmRouter, new LlmProvider(
                "local", TaskType.TEXT, ProviderRole.LOCAL, 0, "key", null, "model", true,
                mock(org.springframework.ai.chat.model.ChatModel.class), null));
        MessageSource messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(anyString(), any(), any(java.util.Locale.class))).thenReturn("{query} {number}");
        return new RetrievalService(llmRouter, ragService, props, Optional.empty(), Optional.empty(), messageSource,
                new ChatImageAnalysisSkipRegistry(), new ProviderContextWindows(),
                com.example.ragagent.llm.ThinkingBudget.none());
    }

    /** 벡터 축의 히트 — 저장된 원문과 유사도를 나른다. */
    private static Document vectorHit(String id, String docId, int chunkIndex) {
        Map<String, Object> meta = new HashMap<>();
        meta.put(MetaKey.DOC_ID, docId);
        meta.put(MetaKey.CHUNK_INDEX, chunkIndex);
        meta.put(MetaKey.VERSION, "latest");
        return Document.builder().id(id).text("벡터 원문 " + id).metadata(meta).score(0.8).build();
    }

    /** 키워드 축의 히트 — FTS 행에서 만들어 본문이 파생 텍스트이고 점수가 없다(chunk_index 는 문자열). */
    private static Document ftsHit(String id, String docId, String chunkIndex) {
        Map<String, Object> meta = new HashMap<>();
        meta.put(MetaKey.DOC_ID, docId);
        meta.put(MetaKey.CHUNK_INDEX, chunkIndex);
        meta.put(MetaKey.VERSION, "latest");
        meta.put(MetaKey.FILENAME, "운영가이드.md");
        return Document.builder().id(id).text(FTS_TEXT).metadata(meta).build();
    }

    private static Document stored(String id) {
        Map<String, Object> meta = new HashMap<>();
        meta.put(MetaKey.DOC_ID, "doc-b");
        meta.put(MetaKey.CHUNK_INDEX, 3);                     // 저장본은 정수 — 식별 키는 받은 쪽 값을 지킨다
        meta.put(MetaKey.CHUNK_CONTEXT, "배포 절차 요약");
        meta.put(MetaKey.EXCERPT_KEYWORDS, "배포,빌드");
        return Document.builder().id(id).text(ORIGINAL).metadata(meta).build();
    }

    private static AgentState state() {
        return AgentState.of("배포 절차", "latest", "t1", "u1", RoutingMode.COST_FIRST);
    }

    @Test
    @DisplayName("키워드 축에만 있던 청크는 저장된 원문으로 바뀌고, 벡터 축에도 있던 청크는 다시 읽지 않는다")
    void keywordOnlyHit_carriesTheStoredOriginal() {
        when(ragService.findChunksByIds("latest", List.of("k1"))).thenReturn(List.of(stored("k1")));

        AgentState out = service().execute(state());

        Document k1 = out.retrievedDocs().stream().filter(d -> "k1".equals(d.getId())).findFirst().orElseThrow();
        assertThat(k1.getText()).isEqualTo(ORIGINAL);
        assertThat(k1.getMetadata())
                .containsEntry(MetaKey.EXCERPT_KEYWORDS, "배포,빌드")      // 저장본의 나머지 메타데이터로 채운다
                .containsEntry(MetaKey.CHUNK_INDEX, "3")                  // 식별 키는 받은 쪽 값 그대로
                .containsEntry(MetaKey.FILENAME, "운영가이드.md");
        Document v1 = out.retrievedDocs().stream().filter(d -> "v1".equals(d.getId())).findFirst().orElseThrow();
        assertThat(v1.getText()).isEqualTo("벡터 원문 v1");
        verify(ragService).findChunksByIds("latest", List.of("k1"));      // 키워드 축에만 있던 것 하나만, 한 번에

        // 출처 미리보기도 원문이고, 융합 진단(축 순위)은 식별 키가 그대로라 그대로 붙는다.
        SourceRef src = out.sources().stream().filter(s -> "k1".equals(s.chunkId())).findFirst().orElseThrow();
        assertThat(src.preview()).contains("배포 절차").doesNotContain("이 청크는");
        assertThat(src.axisRanks()).isEqualTo("bm25:1");
    }

    @Test
    @DisplayName("원문을 찾지 못하면(지워졌거나 백엔드가 읽기를 지원하지 않는다) 받은 그대로 둔다 — 검색은 실패하지 않는다")
    void missingStoredChunk_keepsTheHitAsIs() {
        when(ragService.findChunksByIds(anyString(), any())).thenReturn(List.of());

        AgentState out = service().execute(state());

        assertThat(out.retrievedDocs()).extracting(Document::getId).contains("k1", "v1");
        assertThat(out.retrievedDocs().stream().filter(d -> "k1".equals(d.getId())).findFirst().orElseThrow().getText())
                .isEqualTo(FTS_TEXT);
    }

    @Test
    @DisplayName("원문 조회가 실패해도 검색은 끝난다")
    void storedLookupFailure_doesNotFailTheSearch() {
        when(ragService.findChunksByIds(anyString(), any())).thenThrow(new IllegalStateException("db down"));

        AgentState out = service().execute(state());

        assertThat(out.retrievedDocs()).extracting(Document::getId).contains("k1", "v1");
    }

    @Test
    @DisplayName("키워드 축에만 있는 청크가 없으면 원문 조회를 하지 않는다")
    void noKeywordOnlyHit_noLookup() {
        when(ragService.keywordSearch(eq("latest"), anyString(), anyInt())).thenReturn(List.of(ftsHit("v1", "doc-a", "0")));

        service().execute(state());

        verify(ragService, never()).findChunksByIds(anyString(), any());
    }
}
