package com.example.ragagent.service;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.repository.CuratedSubmissionRepository;
import com.example.ragagent.repository.CuratedSubmissionRepository.Submission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
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
        when(props.chunkOverlapSafe()).thenReturn(0);
        when(props.minChunkSizeSafe()).thenReturn(200);
        when(props.chunkSplitGranularSafe()).thenReturn(false);
        when(props.embeddingSafe()).thenReturn(new AppProperties.EmbeddingConfig(
                null, null, null, null, null, null, false, 0, null, 1));
        // 실제 ChunkSplitter — 승인 시 본문 분할이 문서 인덱싱과 같은 기계를 쓰는지까지 함께 검증한다.
        service = new CuratedSubmissionService(repository, curatedQaService,
                new com.example.ragagent.ingestion.ChunkSplitter(), props, auditLogger);
    }

    private static Submission submission(long id, String status) {
        return new Submission(id, AUTHOR, "원래 제목", "원래 본문", status, null, null,
                null, "2026-01-01", "2026-01-01", null, null, null, 0, 0, 0);
    }

    // ── 등록 검증 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("submit — 정상 입력이면 저장하고 감사 로그를 남긴다")
    void submit_valid_insertsAndAudits() {
        when(repository.countPendingByAuthor(AUTHOR)).thenReturn(0);
        when(repository.insert(eq(AUTHOR), eq("제목"), eq("본문"), any())).thenReturn(7L);

        long id = service.submit(AUTHOR, "  제목  ", "  본문  ", java.util.List.of());

        assertThat(id).isEqualTo(7L);
        // 태그 없이 등록하면 빈 CSV — 큐레이션 면제 대상이 되어 모든 태그 스코프에서 검색된다.
        verify(repository).insert(AUTHOR, "제목", "본문", "");
        verify(auditLogger).log(eq("curated.submission.create"), eq("submission:7"), any());
    }

    @Test
    @DisplayName("submit — 제목이 비었거나 200자를 넘으면 거부")
    void submit_titleValidation() {
        assertThatThrownBy(() -> service.submit(AUTHOR, "   ", "본문", java.util.List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.submit(AUTHOR, "가".repeat(201), "본문", java.util.List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("제목이 너무 깁니다");
        verify(repository, never()).insert(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("submit — 본문이 비면 거부")
    void submit_blankBodyRejected() {
        assertThatThrownBy(() -> service.submit(AUTHOR, "제목", "   ", java.util.List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("본문");
        verify(repository, never()).insert(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("submit — 본문 길이 제한은 없다 (긴 본문도 그대로 등록된다)")
    void submit_hasNoBodyLengthLimit() {
        when(props.chunkSizeSafe()).thenReturn(30);
        when(repository.countPendingByAuthor(AUTHOR)).thenReturn(0);
        when(repository.insert(anyString(), anyString(), anyString(), any())).thenReturn(1L);

        // chunkSize 의 수십 배여도 거부되지 않는다 — 승인 시 청크로 나뉘기 때문.
        service.submit(AUTHOR, "제목", "가".repeat(1000), List.of());

        verify(repository, times(1)).insert(eq(AUTHOR), eq("제목"), eq("가".repeat(1000)), any());
    }

    @Test
    @DisplayName("splitBody — chunkSize 이하면 1개, 넘으면 여러 개로 나뉜다")
    void splitBody_dividesLongBodies() {
        when(props.chunkSizeSafe()).thenReturn(400);
        when(props.minChunkSizeSafe()).thenReturn(120);

        assertThat(service.splitBody("짧은 본문.")).hasSize(1);

        List<String> many = service.splitBody(("문장입니다. ".repeat(20) + "\n").repeat(10));
        assertThat(many).hasSizeGreaterThan(1);
        assertThat(service.previewChunkCount("짧은 본문.")).isEqualTo(1);
    }

    @Test
    @DisplayName("splitBody — 빈 본문은 빈 목록, 공백만 있어도 빈 목록")
    void splitBody_blankBody() {
        assertThat(service.splitBody(null)).isEmpty();
        assertThat(service.splitBody("   ")).isEmpty();
    }

    @Test
    @DisplayName("submit — 대기 중 제안이 상한에 도달하면 거부")
    void submit_pendingCapEnforced() {
        when(repository.countPendingByAuthor(AUTHOR))
                .thenReturn(CuratedSubmissionService.MAX_PENDING_PER_USER);

        assertThatThrownBy(() -> service.submit(AUTHOR, "제목", "본문", java.util.List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("검토 대기");
        verify(repository, never()).insert(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("chunkSizeForBody — chunkSizeSafe를 매번 새로 읽는다 (/settings 핫 편집 반영)")
    void chunkSizeForBody_readsFresh() {
        when(props.chunkSizeSafe()).thenReturn(800, 1200);
        assertThat(service.chunkSizeForBody()).isEqualTo(800);
        assertThat(service.chunkSizeForBody()).isEqualTo(1200);
    }

    // ── 승인 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("approve — 관리자 수정본으로 curated 행을 만들고 제안을 승인 처리한다")
    void approve_usesAdminEdits() {
        when(repository.findById(1L)).thenReturn(Optional.of(submission(1L, "pending")));
        when(curatedQaService.createFromSubmission(eq(1L), eq(AUTHOR), eq("고친 제목"), any(), any()))
                .thenReturn(List.of(55L));
        when(repository.markApproved(eq(1L), eq(ADMIN), eq("고친 제목"), eq("고친 본문"), any(), eq(55L)))
                .thenReturn(true);

        Optional<Long> result = service.approve(1L, ADMIN, "고친 제목", "고친 본문", null);

        assertThat(result).contains(55L);
        verify(curatedQaService).createFromSubmission(eq(1L), eq(AUTHOR), eq("고친 제목"), any(), any());
        verify(auditLogger).log(eq("curated.submission.approve"), eq("submission:1"), any());
    }

    @Test
    @DisplayName("approve — 수정본이 비면 작성자 원문을 그대로 쓴다")
    void approve_fallsBackToOriginalText() {
        when(repository.findById(1L)).thenReturn(Optional.of(submission(1L, "pending")));
        when(curatedQaService.createFromSubmission(eq(1L), eq(AUTHOR), eq("원래 제목"), any(), any()))
                .thenReturn(List.of(55L));
        when(repository.markApproved(eq(1L), eq(ADMIN), eq("원래 제목"), eq("원래 본문"), any(), eq(55L)))
                .thenReturn(true);

        assertThat(service.approve(1L, ADMIN, null, "  ", null)).contains(55L);
        verify(curatedQaService).createFromSubmission(eq(1L), eq(AUTHOR), eq("원래 제목"), any(), any());
    }

    @Test
    @DisplayName("approve — pending 이 아니거나 없으면 임베딩을 시도조차 하지 않는다")
    void approve_nonPending_returnsEmpty() {
        when(repository.findById(1L)).thenReturn(Optional.of(submission(1L, "approved")));
        when(repository.findById(2L)).thenReturn(Optional.empty());

        assertThat(service.approve(1L, ADMIN, null, null, null)).isEmpty();
        assertThat(service.approve(2L, ADMIN, null, null, null)).isEmpty();
        verify(curatedQaService, never()).createFromSubmission(anyLong(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("approve — 동시 승인으로 CAS에 지면 방금 만든 curated 행을 되돌린다")
    void approve_lostRace_rollsBackCuratedRow() {
        when(repository.findById(1L)).thenReturn(Optional.of(submission(1L, "pending")));
        when(curatedQaService.createFromSubmission(anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(List.of(55L));
        when(repository.markApproved(anyLong(), anyString(), anyString(), anyString(), any(), anyLong()))
                .thenReturn(false);

        assertThat(service.approve(1L, ADMIN, null, null, null)).isEmpty();

        // 전부/전무: 제안 단위로 되돌린다(청크가 여러 개일 수 있으므로 개별 id 로 지우지 않는다).
        verify(curatedQaService).forceRemoveBySubmission(1L);
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

    @Test
    @DisplayName("approve — 긴 본문은 여러 청크로 나뉘어 등록되고, 첫 청크 id 가 반환된다")
    void approve_splitsLongBodyIntoSeveralChunks() {
        // chunkSize 와 minChunkSize 가 붙어 있으면(예: 둘 다 200) 잘린 조각이 다시 mergeTinyChunks 로
        // 합쳐져 분할이 사라진다 — 기본 설정(1500/500)과 같은 비율로 둔다.
        when(props.chunkSizeSafe()).thenReturn(400);
        when(props.minChunkSizeSafe()).thenReturn(120);
        String longBody = ("이것은 충분히 긴 문장입니다. ".repeat(10) + "\n\n").repeat(5);
        Submission row = new Submission(1L, AUTHOR, "제목", longBody, "pending", null, null,
                null, "2026-01-01", "2026-01-01", null, null, "인프라", 0, 0, 0);
        when(repository.findById(1L)).thenReturn(Optional.of(row));
        when(curatedQaService.createFromSubmission(anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(List.of(10L, 11L, 12L));
        when(repository.markApproved(anyLong(), anyString(), anyString(), anyString(), any(), anyLong()))
                .thenReturn(true);

        assertThat(service.approve(1L, ADMIN, null, null, null)).contains(10L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> chunks = ArgumentCaptor.forClass(List.class);
        verify(curatedQaService).createFromSubmission(eq(1L), eq(AUTHOR), eq("제목"),
                chunks.capture(), eq("인프라"));
        assertThat(chunks.getValue()).hasSizeGreaterThan(1);
        // 첫 청크 id 가 제안의 대표 포인터로 저장된다.
        verify(repository).markApproved(eq(1L), eq(ADMIN), eq("제목"), eq(longBody), eq("인프라"), eq(10L));
    }

    @Test
    @DisplayName("approve — 관리자가 태그를 넘기면 그 값이 모든 청크에 쓰이고 제안에도 저장된다")
    void approve_usesAdminTags() {
        when(repository.findById(1L)).thenReturn(Optional.of(submission(1L, "pending")));
        when(curatedQaService.createFromSubmission(anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(List.of(55L));
        when(repository.markApproved(anyLong(), anyString(), anyString(), anyString(), any(), anyLong()))
                .thenReturn(true);

        service.approve(1L, ADMIN, null, null, List.of("보안", "VPN"));

        // TagUtils 정규화(소문자·중복제거)를 거친 CSV 가 전달된다.
        verify(curatedQaService).createFromSubmission(eq(1L), eq(AUTHOR), anyString(), any(), eq("보안,vpn"));
        verify(repository).markApproved(eq(1L), eq(ADMIN), anyString(), anyString(), eq("보안,vpn"), eq(55L));
    }

    @Test
    @DisplayName("approve — 관리자가 태그를 건드리지 않으면(null) 제안에 저장된 값을 유지한다")
    void approve_keepsSubmissionTagsWhenAdminDidNotTouchThem() {
        Submission row = new Submission(1L, AUTHOR, "제목", "본문", "pending", null, null,
                null, "2026-01-01", "2026-01-01", null, null, "인프라", 0, 0, 0);
        when(repository.findById(1L)).thenReturn(Optional.of(row));
        when(curatedQaService.createFromSubmission(anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(List.of(55L));
        when(repository.markApproved(anyLong(), anyString(), anyString(), anyString(), any(), anyLong()))
                .thenReturn(true);

        service.approve(1L, ADMIN, null, null, null);

        verify(curatedQaService).createFromSubmission(eq(1L), eq(AUTHOR), anyString(), any(), eq("인프라"));
    }

    @Test
    @DisplayName("submit — 태그는 TagUtils 정책(최대 10개·32자)을 그대로 적용받는다")
    void submit_tagPolicyEnforced() {
        when(repository.countPendingByAuthor(AUTHOR)).thenReturn(0);

        assertThatThrownBy(() -> service.submit(AUTHOR, "제목", "본문",
                List.of("a".repeat(33))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("태그는 최대");
        verify(repository, never()).insert(anyString(), anyString(), anyString(), any());
    }
}
