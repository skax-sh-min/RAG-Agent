package com.example.ragagent.service;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.repository.CuratedSubmissionRepository;
import com.example.ragagent.repository.CuratedSubmissionRepository.Submission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * §10.11 — 저자가 자기 제안에 대해 할 수 있는 일: 수정과 철회.
 *
 * <p>이 둘이 없으면 §10.11 의 모델이 성립하지 않는다. 좋아요가 지식 제안을 경유하게 되면
 * 등록된 지식의 출처가 대부분 채팅이 되는데, 그때까지 게시판에는 <b>검토 대기 중인 제안을
 * 철회하는</b> 길만 있었고 수정하는 길은 아예 없었다 — 한 번 승인되면 저자는 자기가 넣은
 * 지식을 고치지도 내리지도 못했다.
 *
 * <p>수정이 등록본을 즉시 내리지 않는 것(정책 3)도 여기서 고정한다. 오타 하나 고치는 동안
 * 그 지식이 며칠 사라지는 것은 과하다.
 */
class CuratedSubmissionAuthorControlTest {

    private static final String AUTHOR = "u1";

    private CuratedSubmissionRepository repository;
    private CuratedQaService curatedQaService;
    private CuratedImageStore imageStore;
    private CuratedSubmissionService service;

    @BeforeEach
    void setUp() {
        repository = mock(CuratedSubmissionRepository.class);
        curatedQaService = mock(CuratedQaService.class);
        imageStore = mock(CuratedImageStore.class);
        when(imageStore.describeImages(anyString())).thenAnswer(inv -> inv.getArgument(0));
        AppProperties props = mock(AppProperties.class);
        when(props.chunkSizeSafe()).thenReturn(1_500);
        service = new CuratedSubmissionService(repository, curatedQaService, imageStore,
                mock(MemoryService.class), props, mock(AuditLogger.class));
    }

    private static Submission row(String status, int curatedActive) {
        return new Submission(7L, AUTHOR, "제목", "본문", status, null, null, null,
                "2026-01-01", "2026-01-01", null, null, "인프라", null, null,
                curatedActive, curatedActive, curatedActive, 0);
    }

    // ── 철회 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("철회 — 등록 완료된 제안도 내릴 수 있고, 등록본이 검색에서 함께 회수된다")
    void withdraw_approved_retractsIndexedKnowledge() {
        when(repository.findById(7L)).thenReturn(Optional.of(row("approved", 2)));
        when(repository.markWithdrawn(7L, AUTHOR)).thenReturn(true);

        assertThat(service.withdraw(7L, AUTHOR)).isTrue();

        verify(curatedQaService).forceRemoveBySubmission(7L);
        verify(imageStore).releaseImages("본문");
    }

    @Test
    @DisplayName("철회 — 검토 대기 중인 제안에는 회수할 등록본이 없다")
    void withdraw_pending_hasNothingToRetract() {
        when(repository.findById(7L)).thenReturn(Optional.of(row("pending", 0)));
        when(repository.markWithdrawn(7L, AUTHOR)).thenReturn(true);

        assertThat(service.withdraw(7L, AUTHOR)).isTrue();

        verify(curatedQaService, never()).forceRemoveBySubmission(anyLong());
    }

    @Test
    @DisplayName("철회 — CAS 가 막으면(이미 반려·철회됨) 아무것도 정리하지 않는다")
    void withdraw_lostCas_touchesNothing() {
        when(repository.findById(7L)).thenReturn(Optional.of(row("approved", 2)));
        when(repository.markWithdrawn(7L, AUTHOR)).thenReturn(false);

        assertThat(service.withdraw(7L, AUTHOR)).isFalse();

        verify(curatedQaService, never()).forceRemoveBySubmission(anyLong());
        verify(imageStore, never()).releaseImages(anyString());
    }

    // ── 수정 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("수정 — 저장하면 검토 대기로 돌아가되 등록본은 건드리지 않는다 (정책 3)")
    void update_doesNotRetractWhileAwaitingReview() {
        when(repository.findById(7L)).thenReturn(Optional.of(row("approved", 2)));
        when(repository.updateByAuthor(7L, AUTHOR, "새 제목", "새 본문", "인프라")).thenReturn(true);

        assertThat(service.updateByAuthor(7L, AUTHOR, "새 제목", "새 본문", List.of("인프라"))).isTrue();

        verify(repository).updateByAuthor(7L, AUTHOR, "새 제목", "새 본문", "인프라");
        verify(curatedQaService, never()).forceRemoveBySubmission(anyLong());
        // 본문에서 빠진 이미지는 정리하되, 아직 참조되는 파일은 releaseImages 가 알아서 남긴다.
        verify(imageStore).releaseImages("본문");
    }

    @Test
    @DisplayName("수정 — CAS 가 막으면(검토자가 먼저 처리) 이미지도 건드리지 않는다")
    void update_lostCas_touchesNothing() {
        when(repository.findById(7L)).thenReturn(Optional.of(row("pending", 0)));
        when(repository.updateByAuthor(anyLong(), anyString(), anyString(), anyString(), any()))
                .thenReturn(false);

        assertThat(service.updateByAuthor(7L, AUTHOR, "새 제목", "새 본문", List.of())).isFalse();

        verify(imageStore, never()).releaseImages(anyString());
    }

    @Test
    @DisplayName("수정 — 등록과 같은 검증을 받는다 (제목 길이·빈 본문·이미지 개수)")
    void update_appliesSameValidationAsSubmit() {
        assertThatThrownBy(() -> service.updateByAuthor(7L, AUTHOR, "가".repeat(300), "본문", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("제목이 너무 깁니다");

        assertThatThrownBy(() -> service.updateByAuthor(7L, AUTHOR, "제목", "   ", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("본문을 입력해 주세요");

        verify(repository, never()).updateByAuthor(anyLong(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("수정 — pending 상한은 적용하지 않는다 (이미 있는 행이라 큐를 늘리지 않는다)")
    void update_doesNotConsultPendingCap() {
        when(repository.findById(7L)).thenReturn(Optional.of(row("pending", 0)));
        when(repository.updateByAuthor(anyLong(), anyString(), anyString(), anyString(), any()))
                .thenReturn(true);

        service.updateByAuthor(7L, AUTHOR, "제목", "본문", List.of());

        verify(repository, never()).countPendingByAuthor(anyString());
    }

    // ── 재승인 = 교체 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("재승인 — 손으로 쓴 제안은 이전 등록본을 먼저 내리고 새로 만든다 (두 벌로 검색되지 않도록)")
    void reapprove_handWritten_replacesPreviousRows() {
        when(repository.findById(7L)).thenReturn(Optional.of(row("pending", 2)));
        when(curatedQaService.splitForEmbedding(anyString())).thenReturn(List.of("조각1"));
        when(curatedQaService.createFromSubmission(anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(List.of(20L));
        when(repository.markApproved(anyLong(), anyString(), anyString(), anyString(), any(), anyLong()))
                .thenReturn(true);

        service.approve(7L, "admin", null, null, null);

        verify(curatedQaService).forceRemoveBySubmission(7L);
        verify(curatedQaService).createFromSubmission(anyLong(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("재승인 — 좋아요 출신은 먼저 내리지 않는다 (같은 id 라 백그라운드 삭제가 새 벡터를 지운다)")
    void reapprove_likeOrigin_replacesInPlace() {
        Submission liked = new Submission(7L, AUTHOR, "제목", "본문", "pending", null, null, null,
                "2026-01-01", "2026-01-01", null, null, "인프라", 42L, "t1", 1, 1, 1, 0);
        when(repository.findById(7L)).thenReturn(Optional.of(liked));
        when(curatedQaService.createFromLikedTurn(anyLong(), anyLong(), anyString(), anyString(),
                anyString(), anyString(), any())).thenReturn(30L);
        when(repository.markApproved(anyLong(), anyString(), anyString(), anyString(), any(), anyLong()))
                .thenReturn(true);

        service.approve(7L, "admin", null, null, null);

        verify(curatedQaService, never()).forceRemoveBySubmission(anyLong());
        verify(curatedQaService).createFromLikedTurn(7L, 42L, AUTHOR, "t1", "제목", "본문", "인프라");
    }
}
