package com.example.ragagent.service;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.repository.CuratedSubmissionRepository;
import com.example.ragagent.repository.CuratedSubmissionRepository.Submission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA — 청크 추가 서비스(등록 검증 / 승인 / 거부).
 *
 * <p>Covers:
 *  - 제목·본문 검증 (공백, 길이, 대기 건수 상한) 및 통과 시 저장 + 감사 로그
 *  - 본문 길이는 정규화 후 길이(app.chunk-size)로 잰다
 *  - approve: pending 일 때만, 관리자 수정본으로 curated 행 생성 후 CAS
 *  - approve CAS 실패 시 방금 만든 curated 행을 되돌린다
 *  - reject: 사유 필수
 */
class CuratedSubmissionServiceTest {

    private static final String AUTHOR = "u1";
    private static final String ADMIN  = "admin";

    private CuratedSubmissionRepository repository;
    private CuratedQaService curatedQaService;
    private AppProperties props;
    private AuditLogger auditLogger;
    private CuratedSubmissionService service;

    @BeforeEach
    void setUp() {
        repository = mock(CuratedSubmissionRepository.class);
        curatedQaService = mock(CuratedQaService.class);
        props = mock(AppProperties.class);
        auditLogger = mock(AuditLogger.class);
        when(props.chunkSizeSafe()).thenReturn(800);
        service = new CuratedSubmissionService(repository, curatedQaService, props, auditLogger);
    }

    private static Submission submission(long id, String status) {
        return new Submission(id, AUTHOR, "원래 제목", "원래 본문", status, null, null,
                null, "2026-01-01", "2026-01-01", null, null, null, null);
    }

    // ── 등록 검증 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("submit — 정상 입력이면 저장하고 감사 로그를 남긴다")
    void submit_valid_insertsAndAudits() {
        when(repository.countPendingByAuthor(AUTHOR)).thenReturn(0);
        when(repository.insert(eq(AUTHOR), eq("제목"), eq("본문"))).thenReturn(7L);

        long id = service.submit(AUTHOR, "  제목  ", "  본문  ");

        assertThat(id).isEqualTo(7L);
        verify(repository).insert(AUTHOR, "제목", "본문");
        verify(auditLogger).log(eq("curated.submission.create"), eq("submission:7"), any());
    }

    @Test
    @DisplayName("submit — 제목이 비었거나 200자를 넘으면 거부")
    void submit_titleValidation() {
        assertThatThrownBy(() -> service.submit(AUTHOR, "   ", "본문"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.submit(AUTHOR, "가".repeat(201), "본문"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("제목이 너무 깁니다");
        verify(repository, never()).insert(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("submit — 본문이 비면 거부")
    void submit_blankBodyRejected() {
        assertThatThrownBy(() -> service.submit(AUTHOR, "제목", "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("본문");
        verify(repository, never()).insert(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("submit — 본문 상한은 정규화 후 길이 기준(app.chunk-size)")
    void submit_bodyLengthMeasuredAfterNormalization() {
        when(props.chunkSizeSafe()).thenReturn(30);
        when(repository.countPendingByAuthor(AUTHOR)).thenReturn(0);
        when(repository.insert(anyString(), anyString(), anyString())).thenReturn(1L);

        // 원문은 30자를 넘지만 장식 마크다운이라 정규화하면 줄어든다 → 통과해야 한다.
        String decorated = "**" + "가".repeat(20) + "**\n\n---\n\n" + "**나**";
        assertThat(decorated.length()).isGreaterThan(30);
        service.submit(AUTHOR, "제목", decorated);
        verify(repository, times(1)).insert(anyString(), anyString(), anyString());

        assertThatThrownBy(() -> service.submit(AUTHOR, "제목", "가".repeat(31)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("본문이 너무 깁니다");
    }

    @Test
    @DisplayName("submit — 대기 중 제안이 상한에 도달하면 거부")
    void submit_pendingCapEnforced() {
        when(repository.countPendingByAuthor(AUTHOR))
                .thenReturn(CuratedSubmissionService.MAX_PENDING_PER_USER);

        assertThatThrownBy(() -> service.submit(AUTHOR, "제목", "본문"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("검토 대기");
        verify(repository, never()).insert(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("maxBodyLength — chunkSizeSafe를 매번 새로 읽는다 (/settings 핫 편집 반영)")
    void maxBodyLength_readsFresh() {
        when(props.chunkSizeSafe()).thenReturn(800, 1200);
        assertThat(service.maxBodyLength()).isEqualTo(800);
        assertThat(service.maxBodyLength()).isEqualTo(1200);
    }

    // ── 승인 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("approve — 관리자 수정본으로 curated 행을 만들고 제안을 승인 처리한다")
    void approve_usesAdminEdits() {
        when(repository.findById(1L)).thenReturn(Optional.of(submission(1L, "pending")));
        when(curatedQaService.createFromSubmission(1L, AUTHOR, "고친 제목", "고친 본문", null)).thenReturn(55L);
        when(repository.markApproved(1L, ADMIN, "고친 제목", "고친 본문", 55L)).thenReturn(true);

        Optional<Long> result = service.approve(1L, ADMIN, "고친 제목", "고친 본문");

        assertThat(result).contains(55L);
        verify(curatedQaService).createFromSubmission(1L, AUTHOR, "고친 제목", "고친 본문", null);
        verify(auditLogger).log(eq("curated.submission.approve"), eq("submission:1"), any());
    }

    @Test
    @DisplayName("approve — 수정본이 비면 작성자 원문을 그대로 쓴다")
    void approve_fallsBackToOriginalText() {
        when(repository.findById(1L)).thenReturn(Optional.of(submission(1L, "pending")));
        when(curatedQaService.createFromSubmission(1L, AUTHOR, "원래 제목", "원래 본문", null)).thenReturn(55L);
        when(repository.markApproved(1L, ADMIN, "원래 제목", "원래 본문", 55L)).thenReturn(true);

        assertThat(service.approve(1L, ADMIN, null, "  ")).contains(55L);
        verify(curatedQaService).createFromSubmission(1L, AUTHOR, "원래 제목", "원래 본문", null);
    }

    @Test
    @DisplayName("approve — pending 이 아니거나 없으면 임베딩을 시도조차 하지 않는다")
    void approve_nonPending_returnsEmpty() {
        when(repository.findById(1L)).thenReturn(Optional.of(submission(1L, "approved")));
        when(repository.findById(2L)).thenReturn(Optional.empty());

        assertThat(service.approve(1L, ADMIN, null, null)).isEmpty();
        assertThat(service.approve(2L, ADMIN, null, null)).isEmpty();
        verify(curatedQaService, never()).createFromSubmission(anyLong(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("approve — 동시 승인으로 CAS에 지면 방금 만든 curated 행을 되돌린다")
    void approve_lostRace_rollsBackCuratedRow() {
        when(repository.findById(1L)).thenReturn(Optional.of(submission(1L, "pending")));
        when(curatedQaService.createFromSubmission(anyLong(), anyString(), anyString(), anyString(), any()))
                .thenReturn(55L);
        when(repository.markApproved(anyLong(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(false);

        assertThat(service.approve(1L, ADMIN, null, null)).isEmpty();

        verify(curatedQaService).forceRemove(55L);
        verify(auditLogger, never()).log(eq("curated.submission.approve"), anyString(), any());
    }

    // ── 거부 / 철회 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("reject — 사유는 필수이며, 성공 시 감사 로그를 남긴다")
    void reject_requiresReason() {
        assertThatThrownBy(() -> service.reject(1L, ADMIN, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("거부 사유");
        verify(repository, never()).markRejected(anyLong(), anyString(), anyString());

        when(repository.markRejected(1L, ADMIN, "출처 불명")).thenReturn(true);
        assertThat(service.reject(1L, ADMIN, "  출처 불명  ")).isTrue();
        verify(auditLogger).log(eq("curated.submission.reject"), eq("submission:1"), any());
    }

    @Test
    @DisplayName("reject — 이미 처리된 제안이면 false, 감사 로그도 남기지 않는다")
    void reject_nonPending_returnsFalse() {
        when(repository.markRejected(anyLong(), anyString(), anyString())).thenReturn(false);

        assertThat(service.reject(1L, ADMIN, "사유")).isFalse();
        verify(auditLogger, never()).log(eq("curated.submission.reject"), anyString(), any());
    }

    @Test
    @DisplayName("withdraw — 작성자 스코프로 저장소에 위임한다")
    void withdraw_delegatesWithAuthorScope() {
        when(repository.markWithdrawn(3L, AUTHOR)).thenReturn(true);

        assertThat(service.withdraw(3L, AUTHOR)).isTrue();
        verify(repository).markWithdrawn(3L, AUTHOR);
    }
}
