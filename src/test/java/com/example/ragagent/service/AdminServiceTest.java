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

    // ── 편집 스탬프(MetaKey.EDITED_AT) — 재인덱싱 사전 경고의 근거 ─────────────

    /** 편집 스탬프가 없으면 재인덱싱 경고가 셀 것이 없어져 A안 전체가 조용히 무력화된다. */
    @Test
    @DisplayName("updateChunk(sqlite-vec): 저장한 메타데이터에 edited_at 스탬프가 찍힌다")
    void updateChunk_stampsEditedAt() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AppProperties props = mock(AppProperties.class);
        when(props.vectorStoreSafe()).thenReturn(new VectorStoreConfig("sqlite-vec"));

        AdminService svc = new AdminService(Optional.empty(), jdbc, props, OM, mock(VectorStoreFacade.class), mock(KeywordSearchRepository.class), mock(KeywordExtractor.class));
        svc.updateChunk("latest", "c1", "new text", Map.of(MetaKey.DOC_ID, "doc1"));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(eq("UPDATE vec_document_chunks SET metadata = ? WHERE spring_doc_id = ?"),
                json.capture(), eq("c1"));
        assertThat(json.getValue()).contains(MetaKey.EDITED_AT);
    }

    /** 호출자가 넘긴 맵은 불변(Map.of)일 수 있고, 남의 맵을 고쳐 놓아서도 안 된다. */
    @Test
    @DisplayName("updateChunk: 호출자가 넘긴 메타데이터 맵 자체는 변경하지 않는다")
    void updateChunk_doesNotMutateCallerMap() {
        AppProperties props = mock(AppProperties.class);
        when(props.vectorStoreSafe()).thenReturn(new VectorStoreConfig("sqlite-vec"));
        AdminService svc = new AdminService(Optional.empty(), mock(JdbcTemplate.class), props, OM, mock(VectorStoreFacade.class), mock(KeywordSearchRepository.class), mock(KeywordExtractor.class));

        Map<String, String> caller = Map.of(MetaKey.DOC_ID, "doc1");
        assertThatCode(() -> svc.updateChunk("latest", "c1", "t", caller)).doesNotThrowAnyException();
        assertThat(caller).doesNotContainKey(MetaKey.EDITED_AT);
    }

    @Test
    @DisplayName("collectionFor: sqlite-vec은 버전 그대로, chroma는 manual_ 접두어 (빈 값은 latest)")
    void collectionFor_perBackend() {
        AppProperties vec = mock(AppProperties.class);
        when(vec.vectorStoreSafe()).thenReturn(new VectorStoreConfig("sqlite-vec"));
        AdminService vecSvc = new AdminService(Optional.empty(), mock(JdbcTemplate.class), vec, OM, mock(VectorStoreFacade.class), mock(KeywordSearchRepository.class), mock(KeywordExtractor.class));

        AppProperties chroma = mock(AppProperties.class);
        when(chroma.vectorStoreSafe()).thenReturn(new VectorStoreConfig("chroma"));
        AdminService chromaSvc = new AdminService(Optional.empty(), mock(JdbcTemplate.class), chroma, OM, mock(VectorStoreFacade.class), mock(KeywordSearchRepository.class), mock(KeywordExtractor.class));

        assertThat(vecSvc.collectionFor("v2")).isEqualTo("v2");
        assertThat(chromaSvc.collectionFor("v2")).isEqualTo("manual_v2");
        assertThat(vecSvc.collectionFor(" ")).isEqualTo("latest");
        assertThat(chromaSvc.collectionFor(null)).isEqualTo("manual_latest");
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
    @DisplayName("getChunks(chroma) — 응답이 뒤섞여 와도 문서별 content order(doc_id, chunk_index)로 정렬 후 페이지네이션")
    @SuppressWarnings("unchecked")
    void getChunks_chroma_sortsByContentOrderAndPaginates() {
        ChromaApi api = mock(ChromaApi.class);
        // Chroma가 문서/청크 순서와 무관한 임의 순서로 반환한다고 가정 (예: id 기준).
        List<String> ids  = List.of("zid", "aid", "mid", "bid");
        List<String> docs = List.of("d1c1", "d2c0", "d1c0", "d2c1");
        List<Map<String, String>> metas = List.of(
                Map.of(MetaKey.DOC_ID, "doc1", MetaKey.CHUNK_INDEX, "1"),
                Map.of(MetaKey.DOC_ID, "doc2", MetaKey.CHUNK_INDEX, "0"),
                Map.of(MetaKey.DOC_ID, "doc1", MetaKey.CHUNK_INDEX, "0"),
                Map.of(MetaKey.DOC_ID, "doc2", MetaKey.CHUNK_INDEX, "1"));
        when(api.getEmbeddings(anyString(), anyString(), anyString(), any()))
                .thenReturn(new ChromaApi.GetEmbeddingResponse(ids, List.of(), docs, metas));
        AdminService svc = new AdminService(Optional.of(api), mock(JdbcTemplate.class), mock(AppProperties.class),
                OM, mock(VectorStoreFacade.class), mock(KeywordSearchRepository.class), mock(KeywordExtractor.class));

        List<AdminService.ChunkRow> page1 = svc.getChunks("col", null, 0, 2);
        List<AdminService.ChunkRow> page2 = svc.getChunks("col", null, 2, 2);

        assertThat(page1).extracting(AdminService.ChunkRow::fullText).containsExactly("d1c0", "d1c1");
        assertThat(page2).extracting(AdminService.ChunkRow::fullText).containsExactly("d2c0", "d2c1");
    }

    @Test
    @DisplayName("getChunks(sqlite-vec) — doc_id + chunk_index(json_extract) 기준으로 정렬하는 SQL 사용 (더 이상 spring_doc_id 우선 정렬 아님)")
    @SuppressWarnings("unchecked")
    void getChunks_sqliteVec_ordersByDocIdAndChunkIndex() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        AppProperties props = mock(AppProperties.class);
        when(props.vectorStoreSafe()).thenReturn(new VectorStoreConfig("sqlite-vec"));

        AdminService svc = new AdminService(Optional.empty(), jdbc, props, OM, mock(VectorStoreFacade.class), mock(KeywordSearchRepository.class), mock(KeywordExtractor.class));
        svc.getChunks("latest", null, 0, 20);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sqlCaptor.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sqlCaptor.getValue())
                .contains("ORDER BY doc_id, CAST(json_extract(metadata, '$.chunk_index') AS INTEGER), spring_doc_id");
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

    // ── deleteChunk() — doc_registry 청크 수 동기화 ────────────────────────────

    /** 문서 목록의 청크 수는 doc_registry에 저장된 값이라 라이브 집계가 아니다 — 청크를 지워도
     *  이 행을 안 고치면 삭제된 청크가 다음 전체 재인덱싱까지 계속 세어진다(실제로 보고된 증상). */
    @Test
    @DisplayName("deleteChunk: 문서 레지스트리의 청크 수와 spring_doc_ids에서도 삭제된 청크가 빠진다")
    void deleteChunk_syncsDocRegistry() {
        ChromaApi api = mock(ChromaApi.class);
        stubExistingChunk(api, "c2", "text", Map.of(MetaKey.DOC_ID, "doc1"));

        DocRegistry registry = mock(DocRegistry.class);
        when(registry.findByDocId("doc1", DocRegistry.SHARED)).thenReturn(Optional.of(
                new DocRegistry.DocRegistryEntry("sha", "latest", "2026-01-01T00:00:00Z", 3,
                        List.of("c1", "c2", "c3"), List.of(), 0, null)));

        AdminService svc = new AdminService(Optional.of(api), mock(JdbcTemplate.class), mock(AppProperties.class), OM,
                mock(VectorStoreFacade.class), mock(KeywordSearchRepository.class), mock(KeywordExtractor.class),
                null, registry);

        AdminService.DeleteResult result = svc.deleteChunk("manual_latest", "c2");

        ArgumentCaptor<DocRegistry.DocRegistryEntry> saved =
                ArgumentCaptor.forClass(DocRegistry.DocRegistryEntry.class);
        verify(registry).put(eq("doc1"), eq(DocRegistry.SHARED), saved.capture());
        assertThat(saved.getValue().chunks()).isEqualTo(2);
        assertThat(saved.getValue().springDocIds()).containsExactly("c1", "c3");
        assertThat(result.remainingChunks()).isEqualTo(2);
        assertThat(result.docId()).isEqualTo("doc1");
    }

    /** 청크 id 목록이 기록되지 않은 예전 문서 행을, 목록에 없는 id 하나로 0으로 만들어 버리면 안 된다. */
    @Test
    @DisplayName("deleteChunk: 레지스트리에 그 청크 id가 없으면 청크 수를 건드리지 않는다")
    void deleteChunk_unknownChunkId_leavesRegistryAlone() {
        ChromaApi api = mock(ChromaApi.class);
        stubExistingChunk(api, "ghost", "text", Map.of(MetaKey.DOC_ID, "doc1"));

        DocRegistry registry = mock(DocRegistry.class);
        when(registry.findByDocId("doc1", DocRegistry.SHARED)).thenReturn(Optional.of(
                new DocRegistry.DocRegistryEntry("sha", "latest", "2026-01-01T00:00:00Z", 3,
                        List.of(), List.of(), 0, null)));

        AdminService svc = new AdminService(Optional.of(api), mock(JdbcTemplate.class), mock(AppProperties.class), OM,
                mock(VectorStoreFacade.class), mock(KeywordSearchRepository.class), mock(KeywordExtractor.class),
                null, registry);

        AdminService.DeleteResult result = svc.deleteChunk("manual_latest", "ghost");

        verify(registry, never()).put(anyString(), anyString(), any());
        assertThat(result.remainingChunks()).isNull();
    }

    // ── reconcileChunkCounts() — 과거 드리프트 일회성 복구 ────────────────────

    private AdminService sqliteVecSvc(JdbcTemplate jdbc, DocRegistry registry) {
        AppProperties props = mock(AppProperties.class);
        when(props.vectorStoreSafe()).thenReturn(new VectorStoreConfig("sqlite-vec"));
        return new AdminService(Optional.empty(), jdbc, props, OM, mock(VectorStoreFacade.class),
                mock(KeywordSearchRepository.class), mock(KeywordExtractor.class), null, registry);
    }

    private void stubRegistryEntry(DocRegistry registry, String docId, int chunks, List<String> ids) {
        when(registry.entries(DocRegistry.SHARED)).thenReturn(Map.of(docId,
                new DocRegistry.DocRegistryEntry("sha", "latest", "2026-01-01T00:00:00Z", chunks,
                        ids, List.of(), 0, null)).entrySet());
    }

    @Test
    @DisplayName("reconcileChunkCounts: 저장된 청크 수가 실제와 다르면 실제 값으로 고쳐 쓴다")
    void reconcileChunkCounts_fixesDrift() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), any(), any()))
                .thenReturn(List.of("c1", "c3"));
        DocRegistry registry = mock(DocRegistry.class);
        stubRegistryEntry(registry, "doc1", 3, List.of("c1", "c2", "c3"));

        AdminService.ReconcileResult r = sqliteVecSvc(jdbc, registry).reconcileChunkCounts();

        ArgumentCaptor<DocRegistry.DocRegistryEntry> saved =
                ArgumentCaptor.forClass(DocRegistry.DocRegistryEntry.class);
        verify(registry).put(eq("doc1"), eq(DocRegistry.SHARED), saved.capture());
        assertThat(saved.getValue().chunks()).isEqualTo(2);
        assertThat(saved.getValue().springDocIds()).containsExactly("c1", "c3");
        assertThat(r.fixed()).isEqualTo(1);
        assertThat(r.checked()).isEqualTo(1);
    }

    @Test
    @DisplayName("reconcileChunkCounts: 이미 정확한 행은 다시 쓰지 않는다")
    void reconcileChunkCounts_noDrift_noWrite() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), any(), any()))
                .thenReturn(List.of("c1", "c2"));
        DocRegistry registry = mock(DocRegistry.class);
        stubRegistryEntry(registry, "doc1", 2, List.of("c1", "c2"));

        AdminService.ReconcileResult r = sqliteVecSvc(jdbc, registry).reconcileChunkCounts();

        verify(registry, never()).put(anyString(), anyString(), any());
        assertThat(r.fixed()).isZero();
    }

    /** "청크가 전부 삭제된 문서"와 "스토어가 답을 안 준 상황"은 여기서 똑같이 보인다 —
     *  둘 중 하나만 기록해도 안전하지 않으므로 건드리지 않는다. */
    @Test
    @DisplayName("reconcileChunkCounts: 실제 청크가 0으로 오면 행을 0으로 만들지 않고 건너뛴다")
    void reconcileChunkCounts_emptyLiveResult_skips() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), any(), any())).thenReturn(List.of());
        DocRegistry registry = mock(DocRegistry.class);
        stubRegistryEntry(registry, "doc1", 5, List.of("c1", "c2", "c3", "c4", "c5"));

        AdminService.ReconcileResult r = sqliteVecSvc(jdbc, registry).reconcileChunkCounts();

        verify(registry, never()).put(anyString(), anyString(), any());
        assertThat(r.fixed()).isZero();
        assertThat(r.checked()).isEqualTo(1);
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
