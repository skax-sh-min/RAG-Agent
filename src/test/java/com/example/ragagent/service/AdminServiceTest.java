package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.config.AppProperties.EmbeddingConfig;
import com.example.ragagent.config.AppProperties.VectorStoreConfig;
import com.example.ragagent.ingestion.DocRegistry;
import com.example.ragagent.ingestion.KeywordExtractor;
import com.example.ragagent.ingestion.KeywordSearchRepository;
import com.example.ragagent.ingestion.VectorStoreFacade;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.model.VectorStoreAdminView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AdminService}가 백엔드 독립적으로 동작하는지 검증한다: chroma 모드에서 {@code ChromaApi}
 * 부재 시 graceful 강등, sqlite-vec 모드에서 {@code JdbcTemplate} 기반 상태 집계·청크 브라우징,
 * 그리고 {@link AdminService#vectorStoreView()}의 백엔드별 집계.
 */
class AdminServiceTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private AdminService chromaless() {
        return new AdminService(Optional.empty(), mock(JdbcTemplate.class), mock(AppProperties.class), OM, mock(VectorStoreFacade.class), mock(KeywordSearchRepository.class), mock(KeywordExtractor.class));
    }

    @Test
    @DisplayName("ChromaApi 없음(chroma 모드): listCollections → available=false, 조회는 빈 결과, 변경은 no-op")
    void noChromaApi_degradesGracefully() {
        AdminService svc = chromaless();  // props mock → vectorStoreSafe() null → chroma 경로

        assertThat(svc.listCollections().available()).isFalse();
        assertThat(svc.listCollections().items()).isEmpty();
        assertThat(svc.getChunks("c", null, 0, 10)).isEmpty();
        assertThat(svc.getChunk("c", "id")).isNull();
        assertThat(svc.countChunks("c", null)).isZero();
        assertThatCode(() -> svc.deleteChunk("c", "id")).doesNotThrowAnyException();
        assertThatCode(() -> svc.updateChunk("c", "id", "t", Map.of())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ChromaApi 있음(chroma): listCollections가 ChromaApi에 위임")
    void withChromaApi_delegates() {
        ChromaApi api = mock(ChromaApi.class);
        when(api.listCollections(anyString(), anyString())).thenReturn(List.of());
        AdminService svc = new AdminService(Optional.of(api), mock(JdbcTemplate.class), mock(AppProperties.class), OM, mock(VectorStoreFacade.class), mock(KeywordSearchRepository.class), mock(KeywordExtractor.class));

        AdminService.CollectionsResult r = svc.listCollections();

        assertThat(r.available()).isTrue();
        verify(api).listCollections(anyString(), anyString());
    }

    @Test
    @DisplayName("ChromaApi 없음: deleteChunk가 ChromaApi 접근 시도조차 안 함")
    void noChromaApi_deleteDoesNotTouchApi() {
        AdminService svc = chromaless();
        assertThatCode(() -> svc.deleteChunk("c", "id")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("vectorStoreView(sqlite-vec): JdbcTemplate 집계로 vec_version·문서/청크 수 노출")
    void vectorStoreView_sqliteVec() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT vec_version()", String.class)).thenReturn("v0.1.9");
        when(jdbc.queryForObject("SELECT COUNT(*) FROM vec_document_chunks", Long.class)).thenReturn(42L);
        when(jdbc.queryForObject("SELECT COUNT(DISTINCT doc_id) FROM vec_document_chunks", Long.class)).thenReturn(5L);

        AppProperties props = mock(AppProperties.class);
        when(props.vectorStoreSafe()).thenReturn(new VectorStoreConfig("sqlite-vec"));
        when(props.embeddingSafe()).thenReturn(new EmbeddingConfig(null, null, null, 768, 10, 120, true, 0, List.of(), 1));

        AdminService svc = new AdminService(Optional.empty(), jdbc, props, OM, mock(VectorStoreFacade.class), mock(KeywordSearchRepository.class), mock(KeywordExtractor.class));
        VectorStoreAdminView v = svc.vectorStoreView();

        assertThat(v.isSqliteVec()).isTrue();
        assertThat(v.healthy()).isTrue();
        assertThat(v.vecVersion()).isEqualTo("v0.1.9");
        assertThat(v.dimension()).isEqualTo(768);
        assertThat(v.totalChunks()).isEqualTo(42L);
        assertThat(v.totalDocs()).isEqualTo(5L);
        assertThat(v.hasDocCount()).isTrue();
        assertThat(v.collectionCount()).isNull();
    }

    @Test
    @DisplayName("vectorStoreView(chroma): 컬렉션 집계 재사용, 문서 수는 unknown(-1)")
    void vectorStoreView_chroma() {
        ChromaApi api = mock(ChromaApi.class);
        when(api.listCollections(anyString(), anyString())).thenReturn(List.of());

        AppProperties props = mock(AppProperties.class);
        when(props.vectorStoreSafe()).thenReturn(new VectorStoreConfig("chroma"));

        AdminService svc = new AdminService(Optional.of(api), mock(JdbcTemplate.class), props, OM, mock(VectorStoreFacade.class), mock(KeywordSearchRepository.class), mock(KeywordExtractor.class));
        VectorStoreAdminView v = svc.vectorStoreView();

        assertThat(v.isChroma()).isTrue();
        assertThat(v.healthy()).isTrue();          // listCollections returned non-null
        assertThat(v.collectionCount()).isZero();
        assertThat(v.totalChunks()).isZero();
        assertThat(v.hasDocCount()).isFalse();      // totalDocs == -1
        assertThat(v.vecVersion()).isNull();
    }

    @Test
    @DisplayName("sqlite-vec: listCollections가 version 그룹을 pseudo-collection으로 반환(ChromaApi 미사용)")
    @SuppressWarnings("unchecked")
    void sqliteVec_listCollections_groupsByVersion() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class)))
                .thenReturn(List.of(new AdminService.CollectionSummary("latest", "latest", "latest", 7L)));

        AppProperties props = mock(AppProperties.class);
        when(props.vectorStoreSafe()).thenReturn(new VectorStoreConfig("sqlite-vec"));

        AdminService svc = new AdminService(Optional.empty(), jdbc, props, OM, mock(VectorStoreFacade.class), mock(KeywordSearchRepository.class), mock(KeywordExtractor.class));
        AdminService.CollectionsResult r = svc.listCollections();

        assertThat(r.available()).isTrue();
        assertThat(r.items()).singleElement().satisfies(c -> {
            assertThat(c.version()).isEqualTo("latest");
            assertThat(c.chunkCount()).isEqualTo(7L);
        });
    }

    @Test
    @DisplayName("sqlite-vec: deleteChunk가 vec_document_chunks와 vec_embeddings 두 테이블 모두 삭제")
    void sqliteVec_deleteChunk_deletesBothTables() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AppProperties props = mock(AppProperties.class);
        when(props.vectorStoreSafe()).thenReturn(new VectorStoreConfig("sqlite-vec"));

        AdminService svc = new AdminService(Optional.empty(), jdbc, props, OM, mock(VectorStoreFacade.class), mock(KeywordSearchRepository.class), mock(KeywordExtractor.class));
        svc.deleteChunk("latest", "doc1::0");

        verify(jdbc).update(eq("DELETE FROM vec_document_chunks WHERE spring_doc_id = ?"), eq("doc1::0"));
        verify(jdbc).update(eq("DELETE FROM vec_embeddings WHERE spring_doc_id = ?"), eq("doc1::0"));
    }

    // ── reindexChunk() — chroma 백엔드 기준(순수 JdbcTemplate 목킹 없이 ChromaApi로 검증) ──────

    /** {@code getChunk()}가 이 하나짜리 응답을 chroma에서 그대로 읽어오도록 stub. */
    private void stubExistingChunk(ChromaApi api, String chunkId, String text, Map<String, String> meta) {
        when(api.getEmbeddings(anyString(), anyString(), anyString(), any()))
                .thenReturn(new org.springframework.ai.chroma.vectorstore.ChromaApi.GetEmbeddingResponse(
                        List.of(chunkId), List.of(new float[]{0.1f}), List.of(text), List.of(meta)));
    }

    @Test
    @DisplayName("reindexChunk — 존재하지 않는 청크면 false, vectorStore/keywordRepo에 손대지 않음")
    void reindexChunk_notFound_returnsFalseAndSkipsWrites() {
        ChromaApi api = mock(ChromaApi.class);
        when(api.getEmbeddings(anyString(), anyString(), anyString(), any()))
                .thenReturn(new org.springframework.ai.chroma.vectorstore.ChromaApi.GetEmbeddingResponse(
                        List.of(), List.of(), List.of(), List.of()));
        VectorStoreFacade vectorStore = mock(VectorStoreFacade.class);
        KeywordSearchRepository keywordRepo = mock(KeywordSearchRepository.class);
        KeywordExtractor keywordExtractor = mock(KeywordExtractor.class);
        AdminService svc = new AdminService(Optional.of(api), mock(JdbcTemplate.class), mock(AppProperties.class),
                OM, vectorStore, keywordRepo, keywordExtractor);

        boolean result = svc.reindexChunk("col", "missing-id", false);

        assertThat(result).isFalse();
        verify(vectorStore, never()).add(any(), any(), any());
        verify(keywordRepo, never()).indexChunks(any());
        verify(keywordExtractor, never()).enrichSingle(any());
    }

    @Test
    @DisplayName("reindexChunk — regenerateKeywords=false: 현재 텍스트로 재임베딩·FTS 재색인, 키워드는 그대로 재사용(LLM 미호출)")
    @SuppressWarnings("unchecked")
    void reindexChunk_keepKeywords_reembedsWithoutLlmCall() {
        ChromaApi api = mock(ChromaApi.class);
        Map<String, String> meta = new HashMap<>();
        meta.put(MetaKey.VERSION, "v1");
        meta.put(MetaKey.EXCERPT_KEYWORDS, "기존키워드");
        stubExistingChunk(api, "c1", "본문 텍스트", meta);
        VectorStoreFacade vectorStore = mock(VectorStoreFacade.class);
        KeywordSearchRepository keywordRepo = mock(KeywordSearchRepository.class);
        KeywordExtractor keywordExtractor = mock(KeywordExtractor.class);
        AdminService svc = new AdminService(Optional.of(api), mock(JdbcTemplate.class), mock(AppProperties.class),
                OM, vectorStore, keywordRepo, keywordExtractor);

        boolean result = svc.reindexChunk("col", "c1", false);

        assertThat(result).isTrue();
        verify(keywordExtractor, never()).enrichSingle(any());
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(eq(DocRegistry.SHARED), eq("v1"), captor.capture());
        Document sent = captor.getValue().get(0);
        assertThat(sent.getId()).isEqualTo("c1"); // 같은 id로 upsert — 새 청크가 아니라 원본을 덮어씀
        assertThat(sent.getText()).isEqualTo("본문 텍스트");
        assertThat(sent.getMetadata().get(MetaKey.EXCERPT_KEYWORDS)).isEqualTo("기존키워드");
        verify(keywordRepo).deleteBySpringDocIds(List.of("c1"));
        verify(keywordRepo).indexChunks(any());
    }

    @Test
    @DisplayName("reindexChunk — regenerateKeywords=true: KeywordExtractor를 호출해 그 결과(키워드·맥락)로 재색인")
    @SuppressWarnings("unchecked")
    void reindexChunk_regenerateKeywords_usesExtractorResult() {
        ChromaApi api = mock(ChromaApi.class);
        Map<String, String> meta = new HashMap<>();
        meta.put(MetaKey.VERSION, "v1");
        meta.put(MetaKey.EXCERPT_KEYWORDS, "기존키워드");
        stubExistingChunk(api, "c1", "본문 텍스트", meta);
        VectorStoreFacade vectorStore = mock(VectorStoreFacade.class);
        KeywordSearchRepository keywordRepo = mock(KeywordSearchRepository.class);
        KeywordExtractor keywordExtractor = mock(KeywordExtractor.class);
        // enrichSingle()은 §10.1 관례상 원본과 무관한 새 id의 Document를 반환한다 — 메타데이터만 의미 있음.
        Map<String, Object> reEnriched = new HashMap<>();
        reEnriched.put(MetaKey.EXCERPT_KEYWORDS, "새키워드");
        reEnriched.put(MetaKey.CHUNK_CONTEXT, "새맥락");
        when(keywordExtractor.enrichSingle(any())).thenReturn(new Document("본문 텍스트", reEnriched));
        AdminService svc = new AdminService(Optional.of(api), mock(JdbcTemplate.class), mock(AppProperties.class),
                OM, vectorStore, keywordRepo, keywordExtractor);

        boolean result = svc.reindexChunk("col", "c1", true);

        assertThat(result).isTrue();
        verify(keywordExtractor).enrichSingle(any());
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(eq(DocRegistry.SHARED), eq("v1"), captor.capture());
        Document sent = captor.getValue().get(0);
        assertThat(sent.getId()).isEqualTo("c1"); // enrichSingle()의 새 id가 아니라 원래 청크 id 유지
        assertThat(sent.getMetadata().get(MetaKey.EXCERPT_KEYWORDS)).isEqualTo("새키워드");
        assertThat(sent.getMetadata().get(MetaKey.CHUNK_CONTEXT)).isEqualTo("새맥락");
    }

    @Test
    @DisplayName("reindexChunk — 재임베딩 실패 시 false 반환, FTS 재색인은 시도하지 않음")
    void reindexChunk_embedFailure_returnsFalseAndSkipsFts() {
        ChromaApi api = mock(ChromaApi.class);
        stubExistingChunk(api, "c1", "본문 텍스트", Map.of(MetaKey.VERSION, "v1"));
        VectorStoreFacade vectorStore = mock(VectorStoreFacade.class);
        doThrow(new RuntimeException("embed down")).when(vectorStore).add(any(), any(), any());
        KeywordSearchRepository keywordRepo = mock(KeywordSearchRepository.class);
        AdminService svc = new AdminService(Optional.of(api), mock(JdbcTemplate.class), mock(AppProperties.class),
                OM, vectorStore, keywordRepo, mock(KeywordExtractor.class));

        boolean result = svc.reindexChunk("col", "c1", false);

        assertThat(result).isFalse();
        verify(keywordRepo, never()).deleteBySpringDocIds(any());
        verify(keywordRepo, never()).indexChunks(any());
    }
}
