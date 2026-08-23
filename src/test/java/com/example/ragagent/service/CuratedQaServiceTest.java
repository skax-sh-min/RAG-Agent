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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
 *  - embed 임베딩 실패 fallback: 전체 텍스트 실패 시 상세 섹션만으로 재시도 → 성공하면 markEmbedOk,
 *    둘 다 실패하거나 애초에 상세 섹션이 없으면(Direct 모드 등) markEmbedFailed
 *  - updateAnswer 재임베딩도 동일한 fallback/마킹 로직 공유
 *  - findFailedTurnIds — repository 위임
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
        service = new CuratedQaService(repository, memoryService, threadMetaService, vectorStore, new com.example.ragagent.ingestion.ChunkSplitter(), splitProps(), SHORT_DEBOUNCE_MS);

        when(threadMetaService.findById(UID, TID)).thenReturn(Optional.of(
                new ThreadMeta(TID, UID, "제목", "v1", "2026-01-01", "2026-01-01", "COST_FIRST", "")));
    }

    private static MemoryRepository.Turn turn(String question, String answer) {
        return turn(question, answer, "M");
    }

    private static MemoryRepository.Turn turn(String question, String answer, String responseMode) {
        return new MemoryRepository.Turn(TURN_ID, question, answer, null, null, 0, 0, 0, "local", 1, "LIKE", responseMode, null);
    }

    private static CuratedQaRepository.CuratedQa curatedQa(long id, String status, String question, String answer) {
        return new CuratedQaRepository.CuratedQa(id, TURN_ID, UID, TID, question, answer, status, "v1",
                "2026-01-01", "2026-01-01", "ok", CuratedQaRepository.ORIGIN_LIKE, null, null, 1);
    }

    @Test
    @DisplayName("onLike — turn을 찾을 수 없으면 아무것도 하지 않는다")
    void onLike_turnNotFound_doesNothing() {
        when(memoryService.getTurn(UID, TID, TURN_ID)).thenReturn(Optional.empty());

        service.onLike(UID, TID, TURN_ID);

        verify(repository, never()).upsertActive(anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("onLike — 스냅샷을 즉시(동기) upsert한다")
    void onLike_upsertsSnapshotSynchronously() {
        when(memoryService.getTurn(UID, TID, TURN_ID)).thenReturn(Optional.of(turn("질문", "답변")));
        when(repository.upsertActive(TURN_ID, UID, TID, "질문", "답변", "v1", null)).thenReturn(1L);

        service.onLike(UID, TID, TURN_ID);

        // 백그라운드 스레드(임베딩) 완료를 기다릴 필요 없이, 호출 직후 검증 가능해야 한다.
        verify(repository, times(1)).upsertActive(TURN_ID, UID, TID, "질문", "답변", "v1", null);
    }

    @Test
    @DisplayName("onLike — 디바운스 이후에도 LIKE면 curated 네임스페이스로 임베딩한다")
    void onLike_stillLikedAfterDebounce_embeds() {
        when(memoryService.getTurn(UID, TID, TURN_ID)).thenReturn(Optional.of(turn("질문", "답변\n\n## 참고\n- [파일.docx | p.1] (섹션)")));
        when(repository.upsertActive(anyLong(), any(), any(), any(), any(), any(), any())).thenReturn(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(
                curatedQa(1L, "active", "질문", "답변\n\n## 참고\n- [파일.docx | p.1] (섹션)")));
        when(memoryService.getFeedback(UID, TID, TURN_ID))
                .thenReturn(Optional.of(new MemoryRepository.FeedbackRow("LIKE")));

        service.onLike(UID, TID, TURN_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> docsCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, timeout(2000)).add(eq("shared"), eq(CuratedQaService.CURATED_VERSION), docsCaptor.capture());

        Document doc = docsCaptor.getValue().get(0);
        // 벡터 텍스트는 '## 참고'를 제거한 뒤의 내용이다 — 분할이 도입되면서 스트립이 자르기 '전'으로
        // 옮겨졌기 때문(인용 목록이 청크 경계를 넘으면 뒤 조각에서 헤딩이 사라져 스트립을 못 한다).
        // 원문 전체는 curated_qa.answer 에 그대로 남아 채팅 버블·관리자 편집기가 보여준다.
        assertThat(doc.getText()).doesNotContain("## 참고", "파일.docx");
        // 임베딩용 SEARCH_TEXT 오버라이드도 동일하게 참고 섹션이 제외된다(질문은 포함).
        String searchText = String.valueOf(doc.getMetadata().get(MetaKey.SEARCH_TEXT));
        assertThat(searchText).contains("질문").doesNotContain("참고", "파일.docx");
    }

    @Test
    @DisplayName("onLike — 임베딩용 SEARCH_TEXT는 '## 요약'도 제외한다(저장 텍스트엔 그대로 유지)")
    void onLike_stillLikedAfterDebounce_embedTextExcludesSummaryToo() {
        String fullAnswer = "## 요약\n핵심 한 줄 요약.\n\n## 상세 설명\n자세한 설명입니다.\n\n## 참고\n- [파일.docx | p.1]";
        when(memoryService.getTurn(UID, TID, TURN_ID)).thenReturn(Optional.of(turn("질문", fullAnswer)));
        when(repository.upsertActive(anyLong(), any(), any(), any(), any(), any(), any())).thenReturn(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(curatedQa(1L, "active", "질문", fullAnswer)));
        when(memoryService.getFeedback(UID, TID, TURN_ID))
                .thenReturn(Optional.of(new MemoryRepository.FeedbackRow("LIKE")));

        service.onLike(UID, TID, TURN_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> docsCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, timeout(2000)).add(eq("shared"), eq(CuratedQaService.CURATED_VERSION), docsCaptor.capture());

        Document doc = docsCaptor.getValue().get(0);
        // 벡터 텍스트에서 요약·참고 섹션이 모두 빠진다(위 테스트와 같은 이유 — 스트립이 분할보다 앞선다).
        assertThat(doc.getText()).contains("상세 설명", "자세한 설명입니다.")
                .doesNotContain("## 요약", "핵심 한 줄 요약", "## 참고", "파일.docx");
        // 임베딩용 SEARCH_TEXT 오버라이드도 요약·참고 섹션이 제외된다(질문·상세 설명은 포함).
        String searchText = String.valueOf(doc.getMetadata().get(MetaKey.SEARCH_TEXT));
        assertThat(searchText).contains("질문", "상세 설명", "자세한 설명입니다.")
                .doesNotContain("요약", "핵심 한 줄", "참고", "파일.docx");
    }

    @Test
    @DisplayName("onLike — S 모드 턴은 curated_qa 행조차 만들지 않는다(좋아요 무동작)")
    void onLike_summaryMode_doesNothing() {
        // S 답변은 전체가 "## 요약" 한 섹션이라, 큐레이션 임베딩 입력에서 구조 섹션을 걷어내면
        // 본문이 통째로 사라져 질문만 담긴 벡터가 만들어졌다. 애초에 축약된 답변이라 공유 지식으로
        // 승격할 대상도 아니므로 좋아요 자체를 무동작으로 만든다(싫어요는 계속 동작한다).
        when(memoryService.getTurn(UID, TID, TURN_ID)).thenReturn(Optional.of(turn("질문", "## 요약\n짧은 답변", "S")));

        service.onLike(UID, TID, TURN_ID);

        verify(repository, never()).upsertActive(anyLong(), any(), any(), any(), any(), any(), any());
        verify(repository, never()).findById(anyLong());
        verify(vectorStore, never()).add(any(), any(), any());
    }

    @Test
    @DisplayName("onLike — 옛 L모드로 저장된 턴도 이제 임베딩된다(PLAN §6.24 Step 0-a: 근거 없던 스킵 제거)")
    void onLike_legacyLModeTurn_isNoLongerSkipped() {
        // 예전에는 response_mode='L' 이면 임베딩을 통째로 건너뛰었다 — "L 답변은 색인된 원문을
        // 거의 그대로 미러링한다"는 전제였는데, 실측에서 L 답변 길이가 M과 같아 전제가 깨졌다.
        // 그 분기는 멀쩡한 큐레이션 지식을 조용히 버리고 있었으므로 L과 함께 제거했다.
        // 이제 'L'은 존재하지 않는 값이라 ResponseMode.parse가 N으로 흡수하고, 일반 경로를 탄다.
        when(memoryService.getTurn(UID, TID, TURN_ID)).thenReturn(Optional.of(turn("질문", "답변", "L")));
        when(repository.upsertActive(anyLong(), any(), any(), any(), any(), any(), any())).thenReturn(1L);
        when(memoryService.getFeedback(UID, TID, TURN_ID))
                .thenReturn(Optional.of(new MemoryRepository.FeedbackRow("LIKE")));
        when(repository.findById(1L)).thenReturn(Optional.of(curatedQa(1L, "active", "질문", "답변")));

        service.onLike(UID, TID, TURN_ID);

        verify(repository, times(1)).upsertActive(TURN_ID, UID, TID, "질문", "답변", "v1", null);
        // 디바운스(20ms) 뒤 백그라운드 임베딩 스레드가 실제로 벡터를 쓴다.
        verify(vectorStore, timeout(2_000)).add(any(), any(), any());
    }

    @Test
    @DisplayName("onLike — 디바운스 중 좋아요를 취소하면 임베딩 API 호출 자체를 생략한다")
    void onLike_unlikedDuringDebounce_skipsEmbedCall() throws Exception {
        when(memoryService.getTurn(UID, TID, TURN_ID)).thenReturn(Optional.of(turn("질문", "답변")));
        when(repository.upsertActive(anyLong(), any(), any(), any(), any(), any(), any())).thenReturn(1L);
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
        when(repository.upsertActive(anyLong(), any(), any(), any(), any(), any(), any())).thenReturn(1L);
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
        // id 기준 — 사용자 제안(수동) 행은 source_turn_id 가 NULL 이라 turn 기준으로는 못 지운다.
        verify(repository, times(1)).deactivateById(1L);
        verify(repository, never()).deactivate(anyLong());
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
        verify(repository, never()).deactivateById(anyLong());
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
    @DisplayName("listActive(offset, limit) — repository의 페이지네이션 오버로드로 위임한다")
    void listActive_paginated_delegatesToRepository() {
        when(repository.findAllActive(20, 20)).thenReturn(List.of(curatedQa(2L, "active", "질문2", "답변2")));

        assertThat(service.listActive(20, 20)).hasSize(1);
    }

    @Test
    @DisplayName("findActiveByTurn — 비활성 엔트리는 empty를 반환한다")
    void findActiveByTurn_inactiveEntry_returnsEmpty() {
        when(repository.findBySourceTurnId(TURN_ID)).thenReturn(Optional.of(curatedQa(1L, "inactive", "질문", "답변")));

        assertThat(service.findActiveByTurn(TURN_ID)).isEmpty();
    }

    // ── §10.10 embedding-fallback (재시도 + embed_status 마킹) ─────────────────

    private static final String RAG_FORMAT_ANSWER = """
            ## 요약
            핵심 요약입니다.

            ## 상세 설명
            자세한 설명 내용입니다.

            ## 참고
            - [파일.docx | p.1] (섹션)""";

    @Test
    @DisplayName("embed — 전체 텍스트 임베딩이 바로 성공하면 재시도 없이 markEmbedOk")
    void embed_fullTextSucceeds_singleCallAndMarksOk() {
        when(memoryService.getTurn(UID, TID, TURN_ID)).thenReturn(Optional.of(turn("질문", RAG_FORMAT_ANSWER)));
        when(repository.upsertActive(anyLong(), any(), any(), any(), any(), any(), any())).thenReturn(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(curatedQa(1L, "active", "질문", RAG_FORMAT_ANSWER)));
        when(memoryService.getFeedback(UID, TID, TURN_ID))
                .thenReturn(Optional.of(new MemoryRepository.FeedbackRow("LIKE")));

        service.onLike(UID, TID, TURN_ID);

        verify(vectorStore, timeout(2000).times(1)).add(any(), any(), any());
        verify(repository, timeout(2000)).markEmbedOk(1L);
        verify(repository, never()).markEmbedFailed(anyLong());
    }

    @Test
    @DisplayName("embed — 전체 임베딩 실패 시 상세 섹션만으로 재시도해 성공하면 markEmbedOk")
    void embed_fullTextFails_retriesWithCoreSectionsAndSucceeds() {
        when(memoryService.getTurn(UID, TID, TURN_ID)).thenReturn(Optional.of(turn("질문", RAG_FORMAT_ANSWER)));
        when(repository.upsertActive(anyLong(), any(), any(), any(), any(), any(), any())).thenReturn(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(curatedQa(1L, "active", "질문", RAG_FORMAT_ANSWER)));
        when(memoryService.getFeedback(UID, TID, TURN_ID))
                .thenReturn(Optional.of(new MemoryRepository.FeedbackRow("LIKE")));
        doThrow(new RuntimeException("input too long")).doNothing()
                .when(vectorStore).add(any(), any(), any());

        service.onLike(UID, TID, TURN_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> docsCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, timeout(2000).times(2)).add(any(), any(), docsCaptor.capture());
        String fallbackSearchText = String.valueOf(
                docsCaptor.getAllValues().get(1).get(0).getMetadata().get(MetaKey.SEARCH_TEXT));
        assertThat(fallbackSearchText).contains("질문", "상세 설명", "자세한 설명 내용").doesNotContain("요약", "참고");
        verify(repository, timeout(2000)).markEmbedOk(1L);
        verify(repository, never()).markEmbedFailed(anyLong());
    }

    @Test
    @DisplayName("embed — 크기 사다리(2×/1.5×/1×)와 핵심 섹션 재시도까지 모두 실패하면 markEmbedFailed")
    void embed_bothAttemptsFail_marksFailed() {
        when(memoryService.getTurn(UID, TID, TURN_ID)).thenReturn(Optional.of(turn("질문", RAG_FORMAT_ANSWER)));
        when(repository.upsertActive(anyLong(), any(), any(), any(), any(), any(), any())).thenReturn(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(curatedQa(1L, "active", "질문", RAG_FORMAT_ANSWER)));
        when(memoryService.getFeedback(UID, TID, TURN_ID))
                .thenReturn(Optional.of(new MemoryRepository.FeedbackRow("LIKE")));
        doThrow(new RuntimeException("input too long")).when(vectorStore).add(any(), any(), any());

        service.onLike(UID, TID, TURN_ID);

        // 2× → 1.5× → 1× (3회) + 핵심 섹션 폴백 1회 = 4회
        verify(vectorStore, timeout(2000).times(4)).add(any(), any(), any());
        verify(repository, timeout(2000)).markEmbedFailed(1L);
        verify(repository, never()).markEmbedOk(anyLong());
    }

    @Test
    @DisplayName("embed — '## 상세 설명'이 없으면(Direct 모드 등) 크기 사다리만 돌고 핵심 섹션 재시도는 생략")
    void embed_noCoreSectionFallback_failsWithoutRetry() {
        String directAnswer = "안녕하세요! 무엇을 도와드릴까요?";
        when(memoryService.getTurn(UID, TID, TURN_ID)).thenReturn(Optional.of(turn("질문", directAnswer)));
        when(repository.upsertActive(anyLong(), any(), any(), any(), any(), any(), any())).thenReturn(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(curatedQa(1L, "active", "질문", directAnswer)));
        when(memoryService.getFeedback(UID, TID, TURN_ID))
                .thenReturn(Optional.of(new MemoryRepository.FeedbackRow("LIKE")));
        doThrow(new RuntimeException("input too long")).when(vectorStore).add(any(), any(), any());

        service.onLike(UID, TID, TURN_ID);

        // 짧은 답변이라 세 배수 모두 같은 1청크지만 크기 사다리는 그대로 3회 시도하고,
        // 핵심 섹션이 없으므로 그 폴백은 시도조차 하지 않는다.
        verify(vectorStore, timeout(2000).times(3)).add(any(), any(), any());
        verify(repository, timeout(2000)).markEmbedFailed(1L);
    }

    @Test
    @DisplayName("updateAnswer — 재임베딩이 전체+재시도 모두 실패하면 markEmbedFailed (DB 저장 자체는 성공)")
    void updateAnswer_reembedFails_marksFailed() {
        when(repository.findById(1L)).thenReturn(Optional.of(curatedQa(1L, "active", "질문", RAG_FORMAT_ANSWER)));
        doThrow(new RuntimeException("too long")).when(vectorStore).add(any(), any(), any());

        boolean result = service.updateAnswer(1L, "새 답변");

        assertThat(result).isTrue();
        verify(repository, timeout(2000)).markEmbedFailed(1L);
    }

    @Test
    @DisplayName("findFailedTurnIds — repository로 위임한다")
    void findFailedTurnIds_delegatesToRepository() {
        when(repository.findFailedTurnIds(List.of(1L, 2L))).thenReturn(Set.of(2L));

        assertThat(service.findFailedTurnIds(List.of(1L, 2L))).containsExactly(2L);
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
