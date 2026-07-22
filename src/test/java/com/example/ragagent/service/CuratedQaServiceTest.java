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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA — CuratedQaService (§10.10 in PLAN.md)
 *
 * Covers:
 *  - onLike: turn 없음 → 임베딩/저장 없이 종료
 *  - onLike: 스냅샷을 동기적으로 upsert(호출 즉시, 백그라운드 대기 없이 검증 가능)
 *  - onLike → 디바운스 이후에도 LIKE 유지 → 임베딩 호출(version="curated", 저장 텍스트엔 참고 섹션 유지·
 *    SEARCH_TEXT 오버라이드엔 참고 섹션 제외)
 *  - onLike → 디바운스 중 좋아요 취소 → 임베딩 API 호출 자체를 생략(비용 절감 체크포인트)
 *  - onLike → 임베딩 호출 자체는 성공했지만 그 사이 좋아요가 취소됨 → 보정 삭제(커밋 후 체크포인트)
 *  - onUnlike: 활성 엔트리 존재 → 비활성화 + 벡터 삭제
 *  - onUnlike: 엔트리 없음 / 이미 비활성 → no-op
 */
class CuratedQaServiceTest {

    private static final String UID = "u1";
    private static final String TID = "t1";
    private static final long TURN_ID = 42L;
    private static final long SHORT_DEBOUNCE_MS = 20L;

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
        service = new CuratedQaService(repository, memoryService, threadMetaService, vectorStore, SHORT_DEBOUNCE_MS);

        when(threadMetaService.findById(UID, TID)).thenReturn(Optional.of(
                new ThreadMeta(TID, UID, "제목", "v1", "2026-01-01", "2026-01-01", "COST_FIRST")));
    }

    private static MemoryRepository.Turn turn(String question, String answer) {
        return new MemoryRepository.Turn(TURN_ID, question, answer, null, null, 0, 0, 0, "local", 1, "LIKE");
    }

    private static CuratedQaRepository.CuratedQa curatedQa(long id, String status, String question, String answer) {
        return new CuratedQaRepository.CuratedQa(id, TURN_ID, UID, TID, question, answer, status, "v1",
                "2026-01-01", "2026-01-01");
    }

    @Test
    @DisplayName("onLike — turn을 찾을 수 없으면 아무것도 하지 않는다")
    void onLike_turnNotFound_doesNothing() {
        when(memoryService.getTurn(UID, TID, TURN_ID)).thenReturn(Optional.empty());

        service.onLike(UID, TID, TURN_ID);

        verify(repository, never()).upsertActive(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("onLike — 스냅샷을 즉시(동기) upsert한다")
    void onLike_upsertsSnapshotSynchronously() {
        when(memoryService.getTurn(UID, TID, TURN_ID)).thenReturn(Optional.of(turn("질문", "답변")));
        when(repository.upsertActive(TURN_ID, UID, TID, "질문", "답변", "v1")).thenReturn(1L);

        service.onLike(UID, TID, TURN_ID);

        // 백그라운드 스레드(임베딩) 완료를 기다릴 필요 없이, 호출 직후 검증 가능해야 한다.
        verify(repository, times(1)).upsertActive(TURN_ID, UID, TID, "질문", "답변", "v1");
    }

    @Test
    @DisplayName("onLike — 디바운스 이후에도 LIKE면 curated 네임스페이스로 임베딩한다")
    void onLike_stillLikedAfterDebounce_embeds() {
        when(memoryService.getTurn(UID, TID, TURN_ID)).thenReturn(Optional.of(turn("질문", "답변\n\n## 참고\n- [파일.docx | p.1] (섹션)")));
        when(repository.upsertActive(anyLong(), any(), any(), any(), any(), any())).thenReturn(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(
                curatedQa(1L, "active", "질문", "답변\n\n## 참고\n- [파일.docx | p.1] (섹션)")));
        when(memoryService.getFeedback(UID, TID, TURN_ID))
                .thenReturn(Optional.of(new MemoryRepository.FeedbackRow("LIKE")));

        service.onLike(UID, TID, TURN_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> docsCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, timeout(2000)).add(eq("shared"), eq(CuratedQaService.CURATED_VERSION), docsCaptor.capture());

        Document doc = docsCaptor.getValue().get(0);
        // 저장/표시용 텍스트는 참고 섹션이 그대로 유지된다.
        assertThat(doc.getText()).contains("## 참고", "파일.docx");
        // 임베딩용 SEARCH_TEXT 오버라이드는 참고 섹션이 제외된다(질문은 포함).
        String searchText = String.valueOf(doc.getMetadata().get(MetaKey.SEARCH_TEXT));
        assertThat(searchText).contains("질문").doesNotContain("참고", "파일.docx");
    }

    @Test
    @DisplayName("onLike — 디바운스 중 좋아요를 취소하면 임베딩 API 호출 자체를 생략한다")
    void onLike_unlikedDuringDebounce_skipsEmbedCall() throws Exception {
        when(memoryService.getTurn(UID, TID, TURN_ID)).thenReturn(Optional.of(turn("질문", "답변")));
        when(repository.upsertActive(anyLong(), any(), any(), any(), any(), any())).thenReturn(1L);
        // 디바운스가 끝난 시점엔 이미 좋아요가 취소된 상태.
        when(memoryService.getFeedback(UID, TID, TURN_ID)).thenReturn(Optional.empty());

        service.onLike(UID, TID, TURN_ID);

        Thread.sleep(SHORT_DEBOUNCE_MS + 150); // 디바운스+판단이 끝날 때까지 대기
        verify(vectorStore, never()).add(any(), any(), any());
        verify(repository, never()).findById(anyLong()); // embed()의 재조회 자체가 시작되지 않음
    }

    @Test
    @DisplayName("onLike — 임베딩 호출은 성공했지만 그 사이 좋아요가 취소되면 보정 삭제한다")
    void onLike_unlikedDuringEmbedCall_compensatesWithDelete() {
        when(memoryService.getTurn(UID, TID, TURN_ID)).thenReturn(Optional.of(turn("질문", "답변")));
        when(repository.upsertActive(anyLong(), any(), any(), any(), any(), any())).thenReturn(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(curatedQa(1L, "active", "질문", "답변")));
        // 1st call(디바운스 직후 체크) = LIKE, 2nd call(임베딩 완료 후 보정 체크) = 취소됨.
        when(memoryService.getFeedback(UID, TID, TURN_ID))
                .thenReturn(Optional.of(new MemoryRepository.FeedbackRow("LIKE")))
                .thenReturn(Optional.empty());

        service.onLike(UID, TID, TURN_ID);

        verify(vectorStore, timeout(2000)).add(eq("shared"), eq(CuratedQaService.CURATED_VERSION), any());
        verify(vectorStore, timeout(2000)).deleteByDocIds("shared", CuratedQaService.CURATED_VERSION, List.of("curated-1"));
    }

    @Test
    @DisplayName("onUnlike — 활성 엔트리가 있으면 비활성화하고 벡터를 삭제한다")
    void onUnlike_activeEntry_deactivatesAndDeletesVector() {
        when(repository.findBySourceTurnId(TURN_ID)).thenReturn(Optional.of(curatedQa(1L, "active", "질문", "답변")));

        service.onUnlike(UID, TID, TURN_ID);

        verify(repository, times(1)).deactivate(TURN_ID);
        verify(vectorStore, timeout(2000)).deleteByDocIds("shared", CuratedQaService.CURATED_VERSION, List.of("curated-1"));
    }

    @Test
    @DisplayName("onUnlike — 엔트리가 없으면 아무것도 하지 않는다")
    void onUnlike_noEntry_isNoOp() {
        when(repository.findBySourceTurnId(TURN_ID)).thenReturn(Optional.empty());

        service.onUnlike(UID, TID, TURN_ID);

        verify(repository, never()).deactivate(anyLong());
        verify(vectorStore, never()).deleteByDocIds(any(), any(), any());
    }

    @Test
    @DisplayName("onUnlike — 이미 비활성 상태면 아무것도 하지 않는다(중복 삭제 방지)")
    void onUnlike_alreadyInactive_isNoOp() {
        when(repository.findBySourceTurnId(TURN_ID)).thenReturn(Optional.of(curatedQa(1L, "inactive", "질문", "답변")));

        service.onUnlike(UID, TID, TURN_ID);

        verify(repository, never()).deactivate(anyLong());
        verify(vectorStore, never()).deleteByDocIds(any(), any(), any());
    }

    // ── §10.10 step ④ — 편집/관리 ────────────────────────────────────────────

    @Test
    @DisplayName("updateAnswerForTurn — 활성 엔트리가 있으면 answer를 갱신하고 재임베딩한다")
    void updateAnswerForTurn_activeEntry_updatesAndReembeds() {
        when(repository.findBySourceTurnId(TURN_ID)).thenReturn(Optional.of(curatedQa(1L, "active", "질문", "답변")));
        when(repository.findById(1L)).thenReturn(Optional.of(curatedQa(1L, "active", "질문", "수정된 답변")));

        boolean result = service.updateAnswerForTurn(UID, TID, TURN_ID, "수정된 답변");

        assertThat(result).isTrue();
        verify(repository, times(1)).updateAnswer(1L, "수정된 답변");
        verify(vectorStore, timeout(2000)).add(eq("shared"), eq(CuratedQaService.CURATED_VERSION), any());
    }

    @Test
    @DisplayName("updateAnswerForTurn — 엔트리가 없거나 비활성이면 false, 갱신하지 않는다")
    void updateAnswerForTurn_noActiveEntry_returnsFalse() {
        when(repository.findBySourceTurnId(TURN_ID)).thenReturn(Optional.empty());

        boolean result = service.updateAnswerForTurn(UID, TID, TURN_ID, "수정된 답변");

        assertThat(result).isFalse();
        verify(repository, never()).updateAnswer(anyLong(), any());
    }

    @Test
    @DisplayName("updateAnswer — 빈 답변은 거부한다")
    void updateAnswer_blankAnswer_returnsFalse() {
        boolean result = service.updateAnswer(1L, "   ");

        assertThat(result).isFalse();
        verify(repository, never()).updateAnswer(anyLong(), any());
    }

    @Test
    @DisplayName("forceRemove — 활성 엔트리를 소유자 상태와 무관하게 비활성화·de-index한다")
    void forceRemove_activeEntry_deactivatesAndDeletesVectorRegardlessOfLikeState() {
        when(repository.findById(1L)).thenReturn(Optional.of(curatedQa(1L, "active", "질문", "답변")));

        boolean result = service.forceRemove(1L);

        assertThat(result).isTrue();
        verify(repository, times(1)).deactivate(TURN_ID); // curatedQa()의 sourceTurnId=TURN_ID
        verify(vectorStore, timeout(2000)).deleteByDocIds("shared", CuratedQaService.CURATED_VERSION, List.of("curated-1"));
        // onUnlike의 소유권 체크(getFeedback)는 전혀 거치지 않는다 — 별도 인가 경로.
        verify(memoryService, never()).getFeedback(any(), any(), anyLong());
    }

    @Test
    @DisplayName("forceRemove — 이미 비활성이거나 존재하지 않으면 false")
    void forceRemove_inactiveOrMissing_returnsFalse() {
        when(repository.findById(1L)).thenReturn(Optional.of(curatedQa(1L, "inactive", "질문", "답변")));
        when(repository.findById(2L)).thenReturn(Optional.empty());

        assertThat(service.forceRemove(1L)).isFalse();
        assertThat(service.forceRemove(2L)).isFalse();
        verify(repository, never()).deactivate(anyLong());
    }

    @Test
    @DisplayName("listActive / findById / findActiveByTurn — repository로 위임한다")
    void readMethods_delegateToRepository() {
        when(repository.findAllActive(50)).thenReturn(List.of(curatedQa(1L, "active", "질문", "답변")));
        when(repository.findById(1L)).thenReturn(Optional.of(curatedQa(1L, "active", "질문", "답변")));
        when(repository.findBySourceTurnId(TURN_ID)).thenReturn(Optional.of(curatedQa(1L, "active", "질문", "답변")));

        assertThat(service.listActive(50)).hasSize(1);
        assertThat(service.findById(1L)).isPresent();
        assertThat(service.findActiveByTurn(TURN_ID)).isPresent();
    }

    @Test
    @DisplayName("findActiveByTurn — 비활성 엔트리는 empty를 반환한다")
    void findActiveByTurn_inactiveEntry_returnsEmpty() {
        when(repository.findBySourceTurnId(TURN_ID)).thenReturn(Optional.of(curatedQa(1L, "inactive", "질문", "답변")));

        assertThat(service.findActiveByTurn(TURN_ID)).isEmpty();
    }
}
