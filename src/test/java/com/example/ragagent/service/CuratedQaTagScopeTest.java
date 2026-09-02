package com.example.ragagent.service;

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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 큐레이션 Q&A × 태그 스코프.
 *
 * <p>배경: {@code RetrievalService.filterByTags}는 벡터·키워드·큐레이션이 합쳐진 후보 풀 전체에
 * 걸리는데 큐레이션 항목은 태그 메타데이터가 아예 없었다 → 사용자가 태그 칩을 하나라도 켜면
 * 좋아요한 답변이 전부 탈락했다. 이제 (1) 등록 시 제안의 태그를 그대로 싣고, (2) 태그를 모르는
 * 항목은 모든 스코프를 통과한다.
 *
 * <p>§10.11 이후 태그의 출처는 <b>제안</b>이다 — 프리필이 질문 당시 스코프를 폼에 채워 넣으므로
 * 기본값은 예전과 같지만, 저자가 등록 전에 고칠 수 있고 관리자가 승인 시 다시 고칠 수 있다.
 */
class CuratedQaTagScopeTest {

    private static final String UID = "u1";
    private static final String TID = "t1";
    private static final long TURN_ID = 42L;

    private CuratedQaRepository repository;
    private MemoryService memoryService;
    private ThreadMetaService threadMetaService;
    private VectorStoreFacade vectorStore;
    private CuratedQaService service;

    @BeforeEach
    void setUp() {
        repository = mock(CuratedQaRepository.class);
        memoryService = mock(MemoryService.class);
        threadMetaService = mock(ThreadMetaService.class);
        vectorStore = mock(VectorStoreFacade.class);
        service = new CuratedQaService(repository, threadMetaService, vectorStore, new com.example.ragagent.ingestion.ChunkSplitter(), splitProps());

        when(threadMetaService.findById(UID, TID)).thenReturn(Optional.of(
                new ThreadMeta(TID, UID, "제목", "v1", "2026-01-01", "2026-01-01", "COST_FIRST", "")));
    }

    private static CuratedQaRepository.CuratedQa curated(String tags) {
        return new CuratedQaRepository.CuratedQa(1L, TURN_ID, UID, TID, "질문", "답변", "active", "v1",
                "2026-01-01", "2026-01-01", "ok", CuratedQaRepository.ORIGIN_LIKE, null, tags, 1);
    }

    @Test
    @DisplayName("승인 — 제안의 태그 스코프를 curated_qa 에 그대로 싣는다 (출처 turn/thread 와 함께)")
    void approve_storesSubmissionTags() {
        when(repository.upsertActive(anyLong(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1L);

        service.createFromLikedTurn(7L, TURN_ID, UID, TID, "질문", "답변", "설계,api");

        verify(repository).upsertActive(TURN_ID, UID, TID, "질문", "답변", "v1", "설계,api", 7L);
    }

    @Test
    @DisplayName("승인 — 태그 없이 낸 제안이면 태그도 비어 저장된다 (모든 스코프에서 검색된다)")
    void approve_noTagsStaysEmpty() {
        when(repository.upsertActive(anyLong(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1L);

        service.createFromLikedTurn(7L, TURN_ID, UID, TID, "질문", "답변", "");

        verify(repository).upsertActive(TURN_ID, UID, TID, "질문", "답변", "v1", "", 7L);
    }

    @Test
    @DisplayName("임베딩 문서 — 태그가 있으면 문서 청크와 같은 키(MetaKey.TAGS)로 실린다")
    void embeddedDocument_carriesTagsMetadata() {
        when(repository.upsertActive(anyLong(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(curated("설계,api")));

        service.createFromLikedTurn(7L, TURN_ID, UID, TID, "질문", "답변", "설계,api");

        assertThat(capturedDocument().getMetadata().get(MetaKey.TAGS)).isEqualTo("설계,api");
    }

    @Test
    @DisplayName("임베딩 문서 — 태그가 없으면 키 자체를 넣지 않는다 (스코프 미상 = 전체 통과)")
    void embeddedDocument_omitsTagsKeyWhenEmpty() {
        when(repository.upsertActive(anyLong(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(curated(null)));

        service.createFromLikedTurn(7L, TURN_ID, UID, TID, "질문", "답변", null);

        assertThat(capturedDocument().getMetadata()).doesNotContainKey(MetaKey.TAGS);
    }

    private Document capturedDocument() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, timeout(2000))
                .add(eq("shared"), eq(CuratedQaService.CURATED_VERSION), captor.capture());
        return captor.getValue().get(0);
    }

    /** 분할 파이프라인이 실제로 도는 최소 설정 — 기본 배포와 같은 1500/500 비율. */
    private static com.example.ragagent.config.AppProperties splitProps() {
        var p = mock(com.example.ragagent.config.AppProperties.class);
        when(p.chunkSizeSafe()).thenReturn(1500);
        when(p.chunkOverlapSafe()).thenReturn(0);
        when(p.minChunkSizeSafe()).thenReturn(500);
        when(p.chunkSplitGranularSafe()).thenReturn(false);
        when(p.embeddingSafe()).thenReturn(new com.example.ragagent.config.AppProperties.EmbeddingConfig(
                null, null, null, null, null, null, false, 0, null, 1));
        return p;
    }
}
