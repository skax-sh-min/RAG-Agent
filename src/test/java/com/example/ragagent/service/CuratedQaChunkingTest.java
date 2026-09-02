package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.ingestion.ChunkSplitter;
import com.example.ragagent.ingestion.VectorStoreFacade;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.model.ThreadMeta;
import com.example.ragagent.repository.CuratedQaRepository;
import com.example.ragagent.repository.MemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 좋아요 큐레이션 임베딩 분할 — 긴 답변 하나가 여러 벡터가 된다.
 *
 * <p>제안 게시판과 달리 <b>DB 행은 turn 당 하나로 유지</b>된다(UNIQUE(source_turn_id)·좋아요 취소·
 * 인라인 편집이 모두 turn 단위). 분할은 임베딩 시점에만 일어나고, 몇 개로 나뉘었는지는
 * {@code curated_qa.chunk_count}에 남아 de-index/재임베딩이 모든 벡터를 찾을 수 있게 한다.
 */
class CuratedQaChunkingTest {

    private static final String UID = "u1";
    private static final String TID = "t1";
    private static final long TURN_ID = 42L;
    private static final long CURATED_ID = 7L;

    private CuratedQaRepository repository;
    private MemoryService memoryService;
    private VectorStoreFacade vectorStore;
    private AppProperties props;
    private CuratedQaService service;

    @BeforeEach
    void setUp() {
        repository = mock(CuratedQaRepository.class);
        memoryService = mock(MemoryService.class);
        ThreadMetaService threadMetaService = mock(ThreadMetaService.class);
        vectorStore = mock(VectorStoreFacade.class);
        props = mock(AppProperties.class);

        when(props.chunkSizeSafe()).thenReturn(400);
        when(props.chunkOverlapSafe()).thenReturn(0);
        when(props.minChunkSizeSafe()).thenReturn(120);
        when(props.chunkSplitGranularSafe()).thenReturn(false);
        when(props.embeddingSafe()).thenReturn(new AppProperties.EmbeddingConfig(
                null, null, null, null, null, null, false, 0, null, 1));

        service = new CuratedQaService(repository, threadMetaService, vectorStore,
                new ChunkSplitter(), props);

        when(threadMetaService.findById(UID, TID)).thenReturn(Optional.of(
                new ThreadMeta(TID, UID, "제목", "v1", "2026-01-01", "2026-01-01", "COST_FIRST", "")));
        when(repository.upsertActive(anyLong(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(CURATED_ID);
    }

    /** chunkSize=400 이지만 임베딩은 2배(800자)부터 시도하므로, 나뉘려면 그보다 훨씬 길어야 한다. */
    private static String longAnswer() {
        return ("## 섹션\n\n" + "충분히 긴 설명 문장입니다. ".repeat(20) + "\n\n").repeat(8);
    }

    /** 좋아요 출신 제안의 승인 — 행 하나가 임베딩 시점에 벡터 N개로 나뉘는 경로(§10.11 함정 ①). */
    private void approveWith(String answer, int existingChunkCount) {
        when(repository.findById(CURATED_ID)).thenReturn(Optional.of(
                new CuratedQaRepository.CuratedQa(CURATED_ID, TURN_ID, UID, TID, "질문", answer,
                        "active", "v1", "2026-01-01", "2026-01-01", "ok",
                        CuratedQaRepository.ORIGIN_LIKE, 7L, null, existingChunkCount)));
        service.createFromLikedTurn(7L, TURN_ID, UID, TID, "질문", answer, null);
    }

    private List<Document> capturedDocs() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, timeout(2000))
                .add(eq("shared"), eq(CuratedQaService.CURATED_VERSION), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("긴 답변은 여러 벡터로 나뉘어 임베딩된다 (DB 행은 그대로 1개)")
    void longAnswer_becomesSeveralVectors() {
        approveWith(longAnswer(), 1);

        List<Document> docs = capturedDocs();
        assertThat(docs).hasSizeGreaterThan(1);
        // DB 스냅샷은 여전히 turn 당 한 행 — upsertActive 1회, insertManual 없음.
        verify(repository).upsertActive(eq(TURN_ID), any(), any(), any(), any(), any(), any(), any());
        verify(repository, never()).insertManual(anyLong(), any(), any(), any(), any());
        // 나뉜 개수가 기록되어야 de-index 가 모든 벡터를 찾을 수 있다.
        verify(repository, timeout(2000)).updateChunkCount(CURATED_ID, docs.size());
    }

    @Test
    @DisplayName("벡터 id — 첫 청크는 기존 형식 유지, 이후만 -N 접미사 (기존 항목 덮어쓰기)")
    void vectorIds_keepLegacyFormForFirstChunk() {
        approveWith(longAnswer(), 1);

        List<Document> docs = capturedDocs();
        assertThat(docs.get(0).getId()).isEqualTo("curated-" + CURATED_ID);
        assertThat(docs.get(1).getId()).isEqualTo("curated-" + CURATED_ID + "-1");
        // chunk_index 메타데이터도 순서대로 부여된다.
        assertThat(docs).extracting(d -> d.getMetadata().get(MetaKey.CHUNK_INDEX))
                .startsWith(0, 1);
    }

    @Test
    @DisplayName("모든 청크의 검색 텍스트에 질문이 반복 부여된다 (2번째 청크부터 매칭 유지)")
    void everyChunkRepeatsTheQuestion() {
        approveWith(longAnswer(), 1);

        assertThat(capturedDocs()).allSatisfy(d ->
                assertThat(String.valueOf(d.getMetadata().get(MetaKey.SEARCH_TEXT))).startsWith("질문"));
    }

    @Test
    @DisplayName("각 청크의 저장 텍스트는 자기 조각 — 전체 답변을 청크마다 복제하지 않는다")
    void eachChunkStoresItsOwnSlice() {
        String answer = longAnswer();
        approveWith(answer, 1);

        List<Document> docs = capturedDocs();
        assertThat(docs).allSatisfy(d -> assertThat(d.getText().length()).isLessThan(answer.length()));
        assertThat(docs.stream().map(Document::getText).distinct()).hasSize(docs.size());
    }

    @Test
    @DisplayName("짧은 답변은 예전처럼 벡터 1개 (회귀 없음)")
    void shortAnswer_staysASingleVector() {
        approveWith("짧은 답변입니다.", 1);

        List<Document> docs = capturedDocs();
        assertThat(docs).hasSize(1);
        assertThat(docs.get(0).getId()).isEqualTo("curated-" + CURATED_ID);
        verify(repository, timeout(2000)).updateChunkCount(CURATED_ID, 1);
    }

    @Test
    @DisplayName("답변이 짧아지면 남은 이전 벡터를 정리한다 (새 벡터를 먼저 쓴 뒤)")
    void shrinkingAnswer_prunesStaleVectors() {
        // 이전에 4개로 나뉘어 있던 항목이 이제 1개로 줄어드는 상황
        approveWith("짧아진 답변입니다.", 4);

        capturedDocs(); // add 가 먼저 일어났음을 확인
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> ids = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, timeout(2000))
                .deleteByDocIds(eq("shared"), eq(CuratedQaService.CURATED_VERSION), ids.capture());
        assertThat(ids.getValue()).containsExactly(
                "curated-" + CURATED_ID + "-1",
                "curated-" + CURATED_ID + "-2",
                "curated-" + CURATED_ID + "-3");
    }

    @Test
    @DisplayName("턴 삭제 — 나뉜 벡터를 전부 삭제한다")
    void turnDeleted_deletesEveryChunk() {
        when(repository.findBySourceTurnId(TURN_ID)).thenReturn(Optional.of(
                new CuratedQaRepository.CuratedQa(CURATED_ID, TURN_ID, UID, TID, "질문", "답변",
                        "active", "v1", "2026-01-01", "2026-01-01", "ok",
                        CuratedQaRepository.ORIGIN_LIKE, null, null, 3)));

        service.onTurnDeleted(UID, TID, TURN_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> ids = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, timeout(2000))
                .deleteByDocIds(eq("shared"), eq(CuratedQaService.CURATED_VERSION), ids.capture());
        assertThat(ids.getValue()).containsExactly(
                "curated-" + CURATED_ID,
                "curated-" + CURATED_ID + "-1",
                "curated-" + CURATED_ID + "-2");
    }

    @Test
    @DisplayName("splitForEmbedding — 빈 입력은 빈 목록, chunkSize 이하는 1개")
    void splitForEmbedding_basics() {
        assertThat(service.splitForEmbedding(null)).isEmpty();
        assertThat(service.splitForEmbedding("   ")).isEmpty();
        assertThat(service.splitForEmbedding("짧은 글.")).hasSize(1);
        assertThat(service.splitForEmbedding(longAnswer())).hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("인용만 남은 꼬리 청크는 임베딩하지 않는다 (검색 텍스트가 질문뿐인 벡터 방지)")
    void citationOnlyChunkIsDropped() {
        // '## 참고'는 검색 텍스트에서 제거되므로, 그 섹션만 담긴 조각은 벡터가 될 이유가 없다.
        String answer = "## 상세 설명\n\n" + "본문 문장입니다. ".repeat(40)
                + "\n\n## 참고\n" + "- [파일.docx | p.1] (섹션)\n".repeat(30);
        approveWith(answer, 1);

        assertThat(capturedDocs()).allSatisfy(d -> {
            String searchText = String.valueOf(d.getMetadata().get(MetaKey.SEARCH_TEXT));
            assertThat(searchText).doesNotContain("파일.docx");
            assertThat(searchText.replace("질문", "").strip()).isNotBlank();
        });
    }

    // ── 크기 사다리 (2× → 1.5× → 1×) ─────────────────────────────────────────

    @Test
    @DisplayName("사다리 — 기본은 chunk-size의 2배로 자른다 (문서보다 큰 청크)")
    void ladder_startsAtTwiceChunkSize() {
        String text = longAnswer();

        // 2배(800자) 기준 조각 수 < 1배(400자) 기준 조각 수 — 실제로 더 크게 자른다는 뜻.
        int atTwo = service.splitForEmbedding(text).size();
        int atOne = service.splitForEmbedding(text, 1.0).size();
        assertThat(atTwo).isLessThan(atOne);
        assertThat(service.splitForEmbedding(text, 2.0)).hasSize(atTwo);
    }

    @Test
    @DisplayName("사다리 — 2배가 실패하면 1.5배, 그것도 실패하면 1배로 재시도한다")
    void ladder_shrinksOnFailure() {
        // 앞 두 번은 실패, 세 번째(1×)에 성공.
        doThrow(new RuntimeException("input too large"))
                .doThrow(new RuntimeException("input too large"))
                .doNothing()
                .when(vectorStore).add(any(), any(), any());

        approveWith(longAnswer(), 1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, timeout(2000).times(3))
                .add(eq("shared"), eq(CuratedQaService.CURATED_VERSION), captor.capture());

        List<List<Document>> attempts = captor.getAllValues();
        // 재시도할수록 청크 크기가 줄므로 개수는 절대 줄지 않고, 마지막(1×)은 첫 시도(2×)보다 많다.
        // (섹션 구조가 크기보다 먼저 경계를 정하는 구간이 있어 인접 두 시도가 같은 개수일 수는 있다.)
        assertThat(attempts.get(1).size()).isGreaterThanOrEqualTo(attempts.get(0).size());
        assertThat(attempts.get(2).size()).isGreaterThan(attempts.get(0).size());
        // 성공한 마지막 시도의 개수가 기록된다.
        verify(repository, timeout(2000)).updateChunkCount(CURATED_ID, attempts.get(2).size());
        verify(repository, timeout(2000)).markEmbedOk(CURATED_ID);
    }

    @Test
    @DisplayName("사다리 — 배수 순서는 2.0 → 1.5 → 1.0")
    void ladder_multiplierOrder() {
        assertThat(CuratedQaService.EMBED_CHUNK_SIZE_MULTIPLIERS).containsExactly(2.0, 1.5, 1.0);
    }

    @Test
    @DisplayName("출처 표식 — 좋아요 벡터에는 curated_origin=like 가 실린다")
    void vectorsCarryOriginMarker() {
        approveWith("짧은 답변입니다.", 1);

        assertThat(capturedDocs()).allSatisfy(d ->
                assertThat(d.getMetadata().get(MetaKey.CURATED_ORIGIN))
                        .isEqualTo(CuratedQaRepository.ORIGIN_LIKE));
    }
}
