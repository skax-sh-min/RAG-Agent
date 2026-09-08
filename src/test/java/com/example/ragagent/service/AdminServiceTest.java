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
import org.mockito.ArgumentMatchers;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    /**
     * 화면의 메타데이터 JSON 은 읽기 전용이고 편집 가능한 것은 키워드·맥락 둘뿐이다. 예전에는
     * 클라이언트가 보낸 맵이 저장본을 통째로 대체해서, (a) 요청에 아무 키나 실어 청크 메타데이터를
     * 만들어 낼 수 있었고 (b) 화면이 보내지 않는 키(로더가 붙이는 {@code section} 등)가 편집 한 번에
     * 사라졌다.
     */
    // ── chunk_context 를 위치 표시 / 요약으로 나누는 규칙 ─────────────────────
    //
    // 이 규칙은 화면(admin.html)에 있었다. 소비하는 자리가 둘인데다(편집 패널을 열 때, 키워드·
    // 요약 재생성 뒤 다시 읽을 때) 이 프로젝트에는 JS 테스트 하네스가 없어 테스트를 붙일 수가
    // 없었다 — 실제로 한쪽만 고쳐서 재생성 한 번이 요약을 읽기 전용 칸으로 옮겨 놓는 일이 있었다.

    private static AdminService.ChunkRow chunk(Map<String, String> meta) {
        return new AdminService.ChunkRow("c1", "미리보기", "본문", meta);
    }

    @Test
    @DisplayName("문서 청크: 첫 줄이 위치 표시, 나머지가 편집 가능한 요약")
    void chunkContext_documentChunk_splitsAtTheFirstNewline() {
        AdminService.ChunkRow row = chunk(Map.of(
                MetaKey.DOC_ID, "doc1",
                MetaKey.CHUNK_CONTEXT, "manual.md > 3.2 배포\n배포 절차를 설명하는 청크다."));

        assertThat(row.hasBreadcrumb()).isTrue();
        assertThat(row.contextBreadcrumb()).isEqualTo("manual.md > 3.2 배포");
        assertThat(row.contextSummary()).isEqualTo("배포 절차를 설명하는 청크다.");
    }

    /**
     * 큐레이션 청크의 값은 작성자가 쓴 요약 그 자체다 — 파일 위치라는 개념이 없어 줄바꿈도 없다.
     * 문서 규칙을 그대로 적용하면 요약 <b>전체</b>가 읽기 전용 칸으로 들어가고 편집란이 빈다.
     */
    @Test
    @DisplayName("큐레이션 청크: 위치 표시가 없고 값 전체가 요약이다")
    void chunkContext_curatedChunk_isAllSummary() {
        AdminService.ChunkRow row = chunk(Map.of(
                MetaKey.DOC_ID, "curated:7",
                MetaKey.DOC_TYPE, "curated_qa",
                MetaKey.CHUNK_CONTEXT, "배포는 ArgoCD 가 자동 반영한다."));

        assertThat(row.hasBreadcrumb()).isFalse();
        assertThat(row.contextBreadcrumb()).isEmpty();
        assertThat(row.contextSummary()).isEqualTo("배포는 ArgoCD 가 자동 반영한다.");
    }

    /** LLM 문장 없이 구조적 맥락만 있는 청크(추출 실패 폴백) — 편집란이 비는 것이 맞다. */
    @Test
    @DisplayName("문서 청크에 줄바꿈이 없으면 전부 위치 표시다 (요약은 빈 문자열)")
    void chunkContext_documentChunkWithoutSentence_isAllBreadcrumb() {
        AdminService.ChunkRow row = chunk(Map.of(
                MetaKey.DOC_ID, "doc1",
                MetaKey.CHUNK_CONTEXT, "manual.md > 3.2 배포"));

        assertThat(row.contextBreadcrumb()).isEqualTo("manual.md > 3.2 배포");
        assertThat(row.contextSummary()).isEmpty();
    }

    @Test
    @DisplayName("chunk_context 가 아예 없으면 둘 다 빈 문자열")
    void chunkContext_absent_isEmptyOnBothSides() {
        AdminService.ChunkRow row = chunk(Map.of(MetaKey.DOC_ID, "doc1"));

        assertThat(row.contextBreadcrumb()).isEmpty();
        assertThat(row.contextSummary()).isEmpty();
    }

    @Test
    @DisplayName("mergeEditableMeta: 편집 가능한 두 키만 반영하고 나머지 저장본은 그대로 둔다")
    void mergeEditableMeta_takesOnlyEditableKeys() {
        Map<String, String> stored = new java.util.LinkedHashMap<>();
        stored.put(MetaKey.DOC_ID, "doc1");
        stored.put(MetaKey.FILENAME, "manual.pdf");
        stored.put("section", "3");                      // MetaKey 에 없는 레거시 키
        stored.put(MetaKey.EXCERPT_KEYWORDS, "이전 키워드");

        Map<String, String> merged = AdminService.mergeEditableMeta(stored, Map.of(
                MetaKey.EXCERPT_KEYWORDS, "새 키워드",
                MetaKey.CHUNK_CONTEXT, "새 맥락",
                MetaKey.DOC_ID, "위조된-doc",            // 편집 불가 — 무시돼야 한다
                "injected_key", "임의 값"));             // 클라이언트가 지어낸 키 — 무시돼야 한다

        assertThat(merged).containsEntry(MetaKey.EXCERPT_KEYWORDS, "새 키워드")
                .containsEntry(MetaKey.CHUNK_CONTEXT, "새 맥락")
                .containsEntry(MetaKey.DOC_ID, "doc1")
                .containsEntry(MetaKey.FILENAME, "manual.pdf")
                .containsEntry("section", "3")
                .containsKey(MetaKey.EDITED_AT)
                .doesNotContainKey("injected_key");
    }

    /**
     * 큐레이션 청크에서 이 둘의 단일 출처는 {@code curated_qa.summary}/{@code .keywords} 컬럼이고,
     * 벡터 메타데이터의 값은 재임베딩마다 거기서 다시 쓰이는 <b>사본</b>이다
     * ({@code CuratedQaService.buildDocument}). 편집을 받아 주면 화면은 "저장되었습니다"라고 하는데
     * 다음 재임베딩이 조용히 옛 값으로 되돌린다 — 오류도 로그도 없다. 고치는 자리는 큐레이션
     * 패널 하나이며, 그쪽 저장은 재임베딩까지 함께 돈다.
     */
    @Test
    @DisplayName("mergeEditableMeta: 큐레이션 청크에서는 요약·키워드 편집을 받지 않는다 (되돌아갈 값이라서)")
    void mergeEditableMeta_curatedChunkIgnoresEnrichmentEdits() {
        Map<String, String> stored = new java.util.LinkedHashMap<>();
        stored.put(MetaKey.DOC_ID, "curated:7");
        stored.put(MetaKey.DOC_TYPE, "curated_qa");
        stored.put(MetaKey.EXCERPT_KEYWORDS, "저장된 키워드");
        stored.put(MetaKey.CHUNK_CONTEXT, "저장된 요약");

        Map<String, String> merged = AdminService.mergeEditableMeta(stored, Map.of(
                MetaKey.EXCERPT_KEYWORDS, "화면에서 고친 키워드",
                MetaKey.CHUNK_CONTEXT, "화면에서 고친 요약"));

        assertThat(merged).containsEntry(MetaKey.EXCERPT_KEYWORDS, "저장된 키워드")
                .containsEntry(MetaKey.CHUNK_CONTEXT, "저장된 요약")
                .as("본문 편집 추적은 그대로 — 텍스트는 여전히 고칠 수 있다")
                .containsKey(MetaKey.EDITED_AT);
    }

    /** 문서 청크는 영향이 없어야 한다 — 가드는 doc_type 하나로만 걸린다. */
    @Test
    @DisplayName("mergeEditableMeta: doc_type 이 없는 평범한 문서 청크는 예전 그대로 편집된다")
    void mergeEditableMeta_documentChunkStillEditable() {
        Map<String, String> merged = AdminService.mergeEditableMeta(
                new java.util.LinkedHashMap<>(Map.of(MetaKey.DOC_ID, "doc1")),
                Map.of(MetaKey.CHUNK_CONTEXT, "새 맥락"));

        assertThat(merged).containsEntry(MetaKey.CHUNK_CONTEXT, "새 맥락");
    }

    @Test
    @DisplayName("mergeEditableMeta: 본문만 고친 편집(clientMeta=null)도 저장본을 지키고 스탬프를 찍는다")
    void mergeEditableMeta_nullClientMetaPreservesStored() {
        Map<String, String> merged = AdminService.mergeEditableMeta(
                Map.of(MetaKey.DOC_ID, "doc1", MetaKey.TAGS, "billing"), null);

        assertThat(merged).containsEntry(MetaKey.DOC_ID, "doc1")
                .containsEntry(MetaKey.TAGS, "billing")
                .containsKey(MetaKey.EDITED_AT);
    }

    @Test
    @DisplayName("updateChunk(sqlite-vec): 저장된 메타데이터를 읽어 그 위에 편집분을 얹는다")
    void updateChunk_mergesOntoStoredMetadata() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AppProperties props = mock(AppProperties.class);
        when(props.vectorStoreSafe()).thenReturn(new VectorStoreConfig("sqlite-vec"));
        when(jdbc.query(eq("SELECT metadata FROM vec_document_chunks WHERE spring_doc_id = ?"),
                ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<String>>any(), eq("c1")))
                .thenReturn(List.of("{\"doc_id\":\"doc1\",\"section\":\"3\"}"));

        AdminService svc = new AdminService(Optional.empty(), jdbc, props, OM, mock(VectorStoreFacade.class), mock(KeywordSearchRepository.class), mock(KeywordExtractor.class));
        svc.updateChunk("latest", "c1", "new text", Map.of(MetaKey.EXCERPT_KEYWORDS, "kw"));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(eq("UPDATE vec_document_chunks SET metadata = ? WHERE spring_doc_id = ?"),
                json.capture(), eq("c1"));
        assertThat(json.getValue()).contains("doc1").contains("section").contains("kw");
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
    @DisplayName("getChunks(chroma) — 1단계는 메타데이터만, 2단계는 이 페이지의 id 만 본문과 함께 읽는다")
    @SuppressWarnings("unchecked")
    void getChunks_chroma_readsBodiesOnlyForThePage() {
        ChromaApi api = mock(ChromaApi.class);
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

        svc.getChunks("col", null, 0, 2);

        ArgumentCaptor<ChromaApi.GetEmbeddingsRequest> reqs =
                ArgumentCaptor.forClass(ChromaApi.GetEmbeddingsRequest.class);
        verify(api, times(2)).getEmbeddings(anyString(), anyString(), anyString(), reqs.capture());

        ChromaApi.GetEmbeddingsRequest ordering = reqs.getAllValues().get(0);
        assertThat(ordering.include())
                .as("순서만 정하는 조회다 — 본문은 페이로드의 대부분이고 정렬 기준은 전부 메타데이터에 있다")
                .doesNotContain(ChromaApi.QueryRequest.Include.DOCUMENTS);
        assertThat(ordering.ids()).as("1단계는 매치 집합 전체를 봐야 한다").isNull();

        ChromaApi.GetEmbeddingsRequest bodies = reqs.getAllValues().get(1);
        assertThat(bodies.include()).contains(ChromaApi.QueryRequest.Include.DOCUMENTS);
        assertThat(bodies.ids())
                .as("본문은 이 페이지에 보이는 것만 — 정렬 결과 첫 2개(doc1의 chunk 0,1)")
                .containsExactly("mid", "zid");
    }

    @Test
    @DisplayName("getChunks(chroma) — 2단계 응답이 요청 순서와 다르게 와도 페이지 순서는 정렬 순서를 따른다")
    @SuppressWarnings("unchecked")
    void getChunks_chroma_keepsRequestedOrderWhenResponseIsShuffled() {
        ChromaApi api = mock(ChromaApi.class);
        List<Map<String, String>> metas = List.of(
                Map.of(MetaKey.DOC_ID, "doc1", MetaKey.CHUNK_INDEX, "1"),
                Map.of(MetaKey.DOC_ID, "doc1", MetaKey.CHUNK_INDEX, "0"));
        when(api.getEmbeddings(anyString(), anyString(), anyString(), any()))
                // 1단계(순서 결정) → 2단계(본문). 2단계 응답은 요청한 ids 순서와 반대로 온다.
                .thenReturn(new ChromaApi.GetEmbeddingResponse(
                        List.of("second", "first"), List.of(), List.of("c1", "c0"), metas))
                .thenReturn(new ChromaApi.GetEmbeddingResponse(
                        List.of("second", "first"), List.of(), List.of("c1", "c0"), metas));
        AdminService svc = new AdminService(Optional.of(api), mock(JdbcTemplate.class), mock(AppProperties.class),
                OM, mock(VectorStoreFacade.class), mock(KeywordSearchRepository.class), mock(KeywordExtractor.class));

        assertThat(svc.getChunks("col", null, 0, 2))
                .extracting(AdminService.ChunkRow::fullText)
                .containsExactly("c0", "c1");
    }

    @Test
    @DisplayName("getChunks(chroma) — 1·2단계 사이에 지워진 청크는 조용히 빠진다")
    @SuppressWarnings("unchecked")
    void getChunks_chroma_dropsChunksDeletedBetweenPhases() {
        ChromaApi api = mock(ChromaApi.class);
        when(api.getEmbeddings(anyString(), anyString(), anyString(), any()))
                .thenReturn(new ChromaApi.GetEmbeddingResponse(
                        List.of("a", "b"), List.of(), List.of("", ""),
                        List.of(Map.of(MetaKey.DOC_ID, "d", MetaKey.CHUNK_INDEX, "0"),
                                Map.of(MetaKey.DOC_ID, "d", MetaKey.CHUNK_INDEX, "1"))))
                // 2단계에서는 b 가 이미 없다
                .thenReturn(new ChromaApi.GetEmbeddingResponse(
                        List.of("a"), List.of(), List.of("body-a"),
                        List.of(Map.of(MetaKey.DOC_ID, "d", MetaKey.CHUNK_INDEX, "0"))));
        AdminService svc = new AdminService(Optional.of(api), mock(JdbcTemplate.class), mock(AppProperties.class),
                OM, mock(VectorStoreFacade.class), mock(KeywordSearchRepository.class), mock(KeywordExtractor.class));

        assertThat(svc.getChunks("col", null, 0, 2))
                .extracting(AdminService.ChunkRow::id)
                .containsExactly("a");
    }

    @Test
    @DisplayName("countChunks(chroma, docId) — 세는 데 본문을 끌어오지 않는다")
    @SuppressWarnings("unchecked")
    void countChunks_chroma_doesNotFetchBodies() {
        ChromaApi api = mock(ChromaApi.class);
        when(api.getEmbeddings(anyString(), anyString(), anyString(), any()))
                .thenReturn(new ChromaApi.GetEmbeddingResponse(
                        List.of("a", "b", "c"), List.of(), List.of("", "", ""),
                        List.of(Map.of(), Map.of(), Map.of())));
        AdminService svc = new AdminService(Optional.of(api), mock(JdbcTemplate.class), mock(AppProperties.class),
                OM, mock(VectorStoreFacade.class), mock(KeywordSearchRepository.class), mock(KeywordExtractor.class));

        assertThat(svc.countChunks("col", "doc1")).isEqualTo(3);

        ArgumentCaptor<ChromaApi.GetEmbeddingsRequest> req =
                ArgumentCaptor.forClass(ChromaApi.GetEmbeddingsRequest.class);
        verify(api).getEmbeddings(anyString(), anyString(), anyString(), req.capture());
        assertThat(req.getValue().include()).doesNotContain(ChromaApi.QueryRequest.Include.DOCUMENTS);
    }

    @Test
    @DisplayName("getAllChunks(chroma) — 전체가 필요한 경로는 한 번에 본문까지 읽는다(왕복을 늘리지 않는다)")
    @SuppressWarnings("unchecked")
    void getAllChunks_chroma_singleRoundTripWithBodies() {
        ChromaApi api = mock(ChromaApi.class);
        when(api.getEmbeddings(anyString(), anyString(), anyString(), any()))
                .thenReturn(new ChromaApi.GetEmbeddingResponse(
                        List.of("b", "a"), List.of(), List.of("c1", "c0"),
                        List.of(Map.of(MetaKey.DOC_ID, "d", MetaKey.CHUNK_INDEX, "1"),
                                Map.of(MetaKey.DOC_ID, "d", MetaKey.CHUNK_INDEX, "0"))));
        AdminService svc = new AdminService(Optional.of(api), mock(JdbcTemplate.class), mock(AppProperties.class),
                OM, mock(VectorStoreFacade.class), mock(KeywordSearchRepository.class), mock(KeywordExtractor.class));

        assertThat(svc.getAllChunks("col", "d"))
                .extracting(AdminService.ChunkRow::fullText)
                .containsExactly("c0", "c1");

        ArgumentCaptor<ChromaApi.GetEmbeddingsRequest> req =
                ArgumentCaptor.forClass(ChromaApi.GetEmbeddingsRequest.class);
        verify(api).getEmbeddings(anyString(), anyString(), anyString(), req.capture());
        assertThat(req.getValue().include()).contains(ChromaApi.QueryRequest.Include.DOCUMENTS);
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
