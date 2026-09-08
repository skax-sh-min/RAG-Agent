package com.example.ragagent.service;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.ingestion.KeywordExtractor;
import com.example.ragagent.ingestion.KeywordSearchRepository;
import com.example.ragagent.ingestion.VectorStoreFacade;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.model.ThreadMeta;
import com.example.ragagent.repository.CuratedQaRepository;
import com.example.ragagent.repository.CuratedSubmissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 큐레이션 축의 요약·키워드, 그리고 그 값이 <b>읽히게 만드는</b> BM25 색인.
 *
 * <p>배경: 이 두 값은 오랫동안 큐레이션 축에서 읽는 코드가 없었다. {@code chunk_context} 는
 * {@code SearchTextBuilder.build()} 가 {@code SEARCH_TEXT} 오버라이드를 먼저 돌려주는 바람에
 * 계산 자체에 닿지 못했고, {@code excerpt_keywords} 는 {@code chunk_fts} 의 전용 컬럼으로만
 * 쓰이는데 큐레이션 청크에는 FTS 행이 아예 없었다({@code indexChunks()} 를 부르는 곳이
 * {@code DocumentIndexer} 와 {@code AdminService} 뿐이었다). 그래서 승인된 지식 제안은
 * {@code /admin} 청크 화면에서 두 칸이 늘 비어 있었고, 채워 봐야 아무 일도 일어나지 않았다.
 *
 * <p>여기서 고정하는 것은 그 연결이다 — 값이 메타데이터로 실리고, FTS 행이 생기고, 내려갈 때
 * 함께 사라진다는 것.
 */
class CuratedEnrichmentTest {

    private static final String UID = "u1";
    private static final String TID = "t1";
    private static final long TURN_ID = 42L;

    private CuratedQaRepository repository;
    private VectorStoreFacade vectorStore;
    private KeywordSearchRepository keywordRepo;
    private CuratedQaService service;

    @BeforeEach
    void setUp() {
        repository = mock(CuratedQaRepository.class);
        ThreadMetaService threadMetaService = mock(ThreadMetaService.class);
        vectorStore = mock(VectorStoreFacade.class);
        keywordRepo = mock(KeywordSearchRepository.class);
        service = new CuratedQaService(repository, threadMetaService, vectorStore,
                new com.example.ragagent.ingestion.ChunkSplitter(), props(), keywordRepo);

        when(threadMetaService.findById(UID, TID)).thenReturn(Optional.of(
                new ThreadMeta(TID, UID, "제목", "v1", "2026-01-01", "2026-01-01", "COST_FIRST", "")));
    }

    /** 분할 규칙은 이 테스트의 관심사가 아니다 — 다른 큐레이션 테스트와 같은 최소 스텁. */
    private static AppProperties props() {
        AppProperties p = mock(AppProperties.class);
        when(p.chunkSizeSafe()).thenReturn(1500);
        when(p.chunkOverlapSafe()).thenReturn(0);
        when(p.minChunkSizeSafe()).thenReturn(500);
        when(p.chunkSplitGranularSafe()).thenReturn(false);
        when(p.embeddingSafe()).thenReturn(new AppProperties.EmbeddingConfig(
                null, null, null, null, null, null, false, 0, null, 1));
        return p;
    }

    private static CuratedQaRepository.CuratedQa curated(String summary, String keywords) {
        return new CuratedQaRepository.CuratedQa(1L, TURN_ID, UID, TID, "배포는 어떻게 하나요", "본문입니다.",
                "active", "v1", "2026-01-01", "2026-01-01", "ok",
                CuratedQaRepository.ORIGIN_LIKE, null, null, 1, summary, keywords);
    }

    /** 벡터 스토어에 실제로 넘어간 문서들. */
    private List<Document> embeddedDocs() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, timeout(2000)).add(any(), any(), captor.capture());
        return captor.getValue();
    }

    // ── 메타데이터 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("요약·키워드가 문서 청크와 같은 메타데이터 키로 실린다 (/admin 청크 화면이 읽는 키)")
    void enrichment_ridesOnTheStandardMetadataKeys() {
        when(repository.findById(1L)).thenReturn(Optional.of(curated("배포 절차 요약", "배포, ArgoCD")));

        service.reembedRow(1L, "test");

        Map<String, Object> meta = embeddedDocs().get(0).getMetadata();
        assertThat(meta.get(MetaKey.CHUNK_CONTEXT)).isEqualTo("배포 절차 요약");
        assertThat(meta.get(MetaKey.EXCERPT_KEYWORDS)).isEqualTo("배포, ArgoCD");
    }

    /**
     * 빈 값은 키 자체를 넣지 않는다 — 빈 문자열을 실으면 FTS keywords 컬럼에 빈 토큰이 들어가고,
     * {@code /admin} 에서 "값이 있는데 비었다"와 "값이 없다"를 구분할 수 없게 된다.
     */
    @Test
    @DisplayName("요약·키워드가 비어 있으면 키를 아예 싣지 않는다")
    void blankEnrichment_omitsTheKeys() {
        when(repository.findById(1L)).thenReturn(Optional.of(curated(null, "   ")));

        service.reembedRow(1L, "test");

        Map<String, Object> meta = embeddedDocs().get(0).getMetadata();
        assertThat(meta).doesNotContainKeys(MetaKey.CHUNK_CONTEXT, MetaKey.EXCERPT_KEYWORDS);
    }

    // ── BM25 축 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("임베딩이 성공하면 같은 청크가 BM25(chunk_fts) 축에도 색인된다")
    void embedding_alsoIndexesTheKeywordAxis() {
        when(repository.findById(1L)).thenReturn(Optional.of(curated("배포 절차 요약", "배포, ArgoCD")));

        service.reembedRow(1L, "test");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(keywordRepo, timeout(2000)).indexChunks(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getMetadata().get(MetaKey.EXCERPT_KEYWORDS))
                .isEqualTo("배포, ArgoCD");
    }

    /**
     * FTS 입력에만 요약을 앞에 붙인다. 벡터 입력은 {@code 질문 + 본문} 그대로다 — 요약은 본문을
     * 다시 말한 것이라 의미 벡터에서는 희석이지만, 어휘 매칭인 BM25 에서는 그 항목이 무엇에
     * 관한 것인지를 말하는 토큰의 반복이다(문서 청크에서 {@code chunk_context} 가 하는 일).
     */
    @Test
    @DisplayName("요약은 FTS 검색 텍스트 앞에만 붙고 벡터 입력은 건드리지 않는다")
    void summary_prefixesTheFtsTextOnly() {
        when(repository.findById(1L)).thenReturn(Optional.of(curated("배포 절차 요약", "배포")));

        service.reembedRow(1L, "test");

        String vectorText = (String) embeddedDocs().get(0).getMetadata().get(MetaKey.SEARCH_TEXT);
        assertThat(vectorText).doesNotContain("배포 절차 요약").contains("배포는 어떻게 하나요");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(keywordRepo, timeout(2000)).indexChunks(captor.capture());
        String ftsText = (String) captor.getValue().get(0).getMetadata().get(MetaKey.SEARCH_TEXT);
        assertThat(ftsText).startsWith("배포 절차 요약").contains("배포는 어떻게 하나요");
    }

    /**
     * {@code indexChunks()} 는 INSERT 다 — 먼저 지우지 않으면 같은 {@code spring_doc_id} 의 FTS
     * 행이 재임베딩마다 쌓인다({@code AdminService.reindexChunk()} 가 같은 순서를 지키는 이유).
     */
    @Test
    @DisplayName("재임베딩은 FTS 행을 먼저 지우고 다시 넣는다 (중복 누적 방지)")
    void reembedding_deletesFtsRowsBeforeInserting() {
        when(repository.findById(1L)).thenReturn(Optional.of(curated("요약", "키워드")));

        service.reembedRow(1L, "test");

        var order = org.mockito.Mockito.inOrder(keywordRepo);
        order.verify(keywordRepo, timeout(2000)).deleteBySpringDocIds(anyList());
        order.verify(keywordRepo).indexChunks(anyList());
    }

    /**
     * 벡터가 실패하면 FTS 도 쓰지 않는다 — 키워드로만 검색되는 항목이 생기면 출처는 붙는데
     * 의미 매칭은 안 되는 절반짜리 항목이 된다.
     */
    @Test
    @DisplayName("임베딩이 실패하면 BM25 축에도 넣지 않는다")
    void failedEmbedding_leavesTheKeywordAxisUntouched() {
        when(repository.findById(1L)).thenReturn(Optional.of(curated("요약", "키워드")));
        org.mockito.Mockito.doThrow(new RuntimeException("embed down"))
                .when(vectorStore).add(any(), any(), anyList());

        service.reembedRow(1L, "test");

        verify(repository, timeout(2000)).markEmbedFailed(1L);
        verify(keywordRepo, never()).indexChunks(anyList());
    }

    /**
     * FTS 행은 벡터의 짝이다. 여기서 안 지우면 검색 코퍼스에서 내린 항목이 키워드 축에 남아,
     * 지웠는데도 계속 답변 근거로 붙는다.
     */
    @Test
    @DisplayName("항목을 내리면 벡터와 함께 FTS 행도 사라진다")
    void removal_takesTheFtsRowsDownToo() {
        when(repository.findById(1L)).thenReturn(Optional.of(curated("요약", "키워드")));

        service.forceRemove(1L);

        // 벡터/FTS 삭제는 가상 스레드에서 돈다(forceRemove) — timeout 없이 검증하면 부하가 큰
        // 전체 실행에서만 지는 경주가 된다.
        verify(keywordRepo, timeout(2000)).deleteBySpringDocIds(List.of("curated-1"));
    }

    // ── 단일 출처: curated_qa 컬럼 ────────────────────────────────────────────

    /**
     * 이 둘을 고치는 자리는 큐레이션 패널 하나다. 청크 화면의 같은 이름 칸은 재임베딩마다
     * 여기서 다시 쓰이는 사본이라 그쪽에서는 읽기 전용이다
     * ({@code AdminService.mergeEditableMeta}).
     */
    @Test
    @DisplayName("updateEntry — 요약·키워드를 컬럼에 쓰고 재임베딩은 한 번만 돈다")
    void updateEntry_writesEnrichmentAndReembedsOnce() {
        when(repository.findById(1L)).thenReturn(Optional.of(curated("옛 요약", "옛 키워드")));

        assertThat(service.updateEntry(1L, "새 질문", "새 답변", "새 요약", "새 키워드")).isTrue();

        verify(repository).updateEnrichment(1L, "새 요약", "새 키워드");
        verify(repository).updateQuestion(1L, "새 질문");
        verify(repository).updateAnswer(1L, "새 답변");
        // 같은 항목을 두 번 임베딩하면 그 사이 벡터가 반만 갱신된 중간 상태로 남는다
        verify(vectorStore, timeout(2000)).add(any(), any(), anyList());
    }

    @Test
    @DisplayName("updateEntry — 요약만 보내도 저장되고 재임베딩된다 (질문·답변은 그대로)")
    void updateEntry_enrichmentOnly_isEnoughToSave() {
        when(repository.findById(1L)).thenReturn(Optional.of(curated("옛 요약", "옛 키워드")));

        assertThat(service.updateEntry(1L, null, null, "새 요약", null)).isTrue();

        verify(repository).updateEnrichment(1L, "새 요약", null);
        verify(repository, never()).updateQuestion(anyLong(), any());
        verify(repository, never()).updateAnswer(anyLong(), any());
    }

    /** 3-arg 오버로드는 예전 호출자(질문·답변만 보내는 화면)의 계약이다 — 두 칸을 건드리면 안 된다. */
    @Test
    @DisplayName("updateEntry(3-arg) — 요약·키워드는 손대지 않는다")
    void updateEntry_legacyOverload_leavesEnrichmentAlone() {
        when(repository.findById(1L)).thenReturn(Optional.of(curated("옛 요약", "옛 키워드")));

        service.updateEntry(1L, "새 질문", "새 답변");

        verify(repository, never()).updateEnrichment(anyLong(), any(), any());
    }

    // ── "빈 칸 자동 생성" ─────────────────────────────────────────────────────

    private CuratedSubmissionService submissionService(KeywordExtractor extractor) {
        return new CuratedSubmissionService(mock(CuratedSubmissionRepository.class),
                mock(CuratedQaService.class), mock(CuratedImageStore.class),
                mock(MemoryService.class), props(), mock(AuditLogger.class), extractor);
    }

    private static KeywordExtractor extractorReturning(String summary, String keywords) {
        KeywordExtractor extractor = mock(KeywordExtractor.class);
        Map<String, Object> meta = new HashMap<>();
        meta.put(MetaKey.CHUNK_CONTEXT, summary);
        meta.put(MetaKey.EXCERPT_KEYWORDS, keywords);
        when(extractor.enrichSingle(any())).thenReturn(new Document("본문", meta));
        return extractor;
    }

    @Test
    @DisplayName("자동 생성 — 비어 있는 두 칸을 본문에서 채운다")
    void enrich_fillsBothWhenEmpty() {
        var out = submissionService(extractorReturning("본문 요약", "키워드1, 키워드2"))
                .enrich("본문입니다.", "", "");

        assertThat(out.summary()).isEqualTo("본문 요약");
        assertThat(out.keywords()).isEqualTo("키워드1, 키워드2");
        assertThat(out.llmCalled()).isTrue();
    }

    /** 버튼 이름이 "빈 칸 자동 생성"인 것이 곧 약속이다 — 사람이 쓴 문장을 갈아엎지 않는다. */
    @Test
    @DisplayName("자동 생성 — 이미 채워진 칸은 덮지 않고 빈 칸만 채운다")
    void enrich_neverOverwritesWhatTheAuthorWrote() {
        var out = submissionService(extractorReturning("생성된 요약", "생성된 키워드"))
                .enrich("본문입니다.", "사람이 쓴 요약", "");

        assertThat(out.summary()).isEqualTo("사람이 쓴 요약");
        assertThat(out.keywords()).isEqualTo("생성된 키워드");
    }

    @Test
    @DisplayName("자동 생성 — 둘 다 채워져 있으면 LLM 을 아예 부르지 않는다")
    void enrich_bothFilled_skipsTheLlmEntirely() {
        KeywordExtractor extractor = extractorReturning("생성", "생성");

        var out = submissionService(extractor).enrich("본문입니다.", "요약", "키워드");

        assertThat(out.llmCalled()).isFalse();
        verify(extractor, never()).enrichSingle(any());
    }

    @Test
    @DisplayName("자동 생성 — 본문이 비어 있으면 만들 근거가 없으므로 거절한다")
    void enrich_blankBody_isRejected() {
        CuratedSubmissionService svc = submissionService(extractorReturning("a", "b"));

        assertThatThrownBy(() -> svc.enrich("   ", "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("본문");
    }

    /** 모델이 길게 답해도 제안 등록이 실패하지는 않는다 — 거절이 아니라 자른다. */
    @Test
    @DisplayName("자동 생성 — 상한을 넘긴 결과는 잘려서 돌아온다")
    void enrich_clampsOverlongOutput() {
        String tooLong = "가".repeat(CuratedSubmissionService.MAX_SUMMARY_LEN + 200);
        var out = submissionService(extractorReturning(tooLong, "키워드")).enrich("본문", "", "");

        assertThat(out.summary()).hasSize(CuratedSubmissionService.MAX_SUMMARY_LEN);
    }
}
