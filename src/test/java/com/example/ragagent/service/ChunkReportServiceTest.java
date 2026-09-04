package com.example.ragagent.service;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.repository.ChunkReportRepository;
import com.example.ragagent.repository.ChunkReportRepository.ChunkLocation;
import com.example.ragagent.repository.ChunkReportRepository.Report;
import com.example.ragagent.repository.MemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * §10.14 청크 오류 신고 서비스.
 *
 * <p>Covers:
 *  - 사유·코멘트 검증(빈 값, 알 수 없는 사유, 길이 상한)
 *  - 신고 대상 메타(문서·버전·파일명·원문 스냅샷·해시)를 <b>서버가 직접 읽어</b> 저장한다
 *  - 같은 대화의 중복 신고는 예외가 아니라 {@code created=false}
 *  - 상세: 신고 이후 청크가 바뀌었는지(그대로/수정됨/삭제됨/알 수 없음)를 해시로 판정
 *  - 조치는 청크 단위이고 반려는 사유가 필수
 */
class ChunkReportServiceTest {

    private static final String USER   = "u1";
    private static final String ADMIN  = "admin";
    private static final String CHUNK  = "chunk-1";
    private static final String THREAD = "t1";

    private ChunkReportRepository repository;
    private QuestionReuseService questionReuseService;
    private MemoryService memoryService;
    private AuditLogger auditLogger;
    private ChunkReportService service;

    @BeforeEach
    void setUp() {
        repository = mock(ChunkReportRepository.class);
        questionReuseService = mock(QuestionReuseService.class);
        memoryService = mock(MemoryService.class);
        auditLogger = mock(AuditLogger.class);
        service = new ChunkReportService(repository, questionReuseService, memoryService, auditLogger);
    }

    // ── 접수 검증 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("코멘트가 비면 거부한다 — 사유 코드만으로는 어디를 고칠지 알 수 없다")
    void rejectsBlankComment() {
        assertThatThrownBy(() -> service.report(USER, CHUNK, THREAD, 1L, "WRONG", "   "))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("알 수 없는 사유는 OTHER 로 흡수하지 않고 거부한다")
    void rejectsUnknownReason() {
        assertThatThrownBy(() -> service.report(USER, CHUNK, THREAD, 1L, "IRRELEVANT", "틀렸습니다"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("코멘트 길이 상한")
    void rejectsTooLongComment() {
        String tooLong = "가".repeat(ChunkReportService.MAX_COMMENT_LEN + 1);
        assertThatThrownBy(() -> service.report(USER, CHUNK, THREAD, 1L, "WRONG", tooLong))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── 접수 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("문서·버전·원문 스냅샷·해시·질문은 클라이언트가 아니라 서버가 채운다")
    void snapshotsServerSideFacts() {
        when(repository.hasOpenReport(CHUNK, USER, THREAD)).thenReturn(false);
        when(repository.findChunkLocation(CHUNK)).thenReturn(Optional.of(
                new ChunkLocation("doc-1", "latest", "manual.pdf", "원문 텍스트",
                        ChunkLocation.SOURCE_ORIGINAL)));
        when(questionReuseService.currentChunkHash(CHUNK)).thenReturn("hash-1");
        when(memoryService.getTurn(USER, THREAD, 7L)).thenReturn(Optional.of(turn("포트가 뭐야?")));
        when(repository.insert(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any())).thenReturn(42L);

        ChunkReportService.ReportResult result =
                service.report(USER, CHUNK, THREAD, 7L, "outdated", "  포트가 바뀌었습니다  ");

        assertThat(result.created()).isTrue();
        assertThat(result.id()).isEqualTo(42L);
        verify(repository).insert(CHUNK, "doc-1", "latest", "manual.pdf", USER, THREAD, 7L,
                "포트가 뭐야?", "OUTDATED", "포트가 바뀌었습니다", "hash-1", "원문 텍스트");
    }

    @Test
    @DisplayName("청크를 찾지 못해도 신고는 접수된다 — 스냅샷만 비운다")
    void reportsWithoutSnapshotWhenChunkUnknown() {
        when(repository.hasOpenReport(anyString(), anyString(), any())).thenReturn(false);
        when(repository.findChunkLocation(CHUNK)).thenReturn(Optional.empty());
        when(questionReuseService.currentChunkHash(CHUNK)).thenReturn("");
        when(memoryService.getTurn(anyString(), anyString(), anyLong())).thenReturn(Optional.empty());
        when(repository.insert(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any())).thenReturn(1L);

        assertThat(service.report(USER, CHUNK, THREAD, 7L, "BROKEN", "표가 깨져 있습니다").created()).isTrue();

        verify(repository).insert(CHUNK, null, null, null, USER, THREAD, 7L, null,
                "BROKEN", "표가 깨져 있습니다", "", null);
    }

    @Test
    @DisplayName("같은 대화의 중복 신고는 오류가 아니라 created=false")
    void duplicateIsNotAnError() {
        when(repository.hasOpenReport(CHUNK, USER, THREAD)).thenReturn(true);

        ChunkReportService.ReportResult result =
                service.report(USER, CHUNK, THREAD, 7L, "WRONG", "틀렸습니다");

        assertThat(result.created()).isFalse();
        verify(repository, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    // ── 상세(변경 판정) ─────────────────────────────────────────────────

    @Test
    @DisplayName("해시가 그대로면 '변경 없음', 달라지면 '수정됨'")
    void derivesChangeStatusFromHash() {
        when(repository.findOpenByChunk(CHUNK)).thenReturn(List.of(report(1L, "hash-old")));
        when(repository.findClosedByChunk(eq(CHUNK), anyInt())).thenReturn(List.of());
        when(repository.findChunkLocation(CHUNK)).thenReturn(Optional.of(
                new ChunkLocation("doc-1", "latest", "manual.pdf", "지금 내용",
                        ChunkLocation.SOURCE_ORIGINAL)));

        when(questionReuseService.currentChunkHash(CHUNK)).thenReturn("hash-old");
        assertThat(service.chunkDetail(CHUNK).orElseThrow().changeStatus())
                .isEqualTo(ChunkReportService.CHANGE_UNCHANGED);

        when(questionReuseService.currentChunkHash(CHUNK)).thenReturn("hash-new");
        var detail = service.chunkDetail(CHUNK).orElseThrow();
        assertThat(detail.changeStatus()).isEqualTo(ChunkReportService.CHANGE_MODIFIED);
        assertThat(detail.reports().get(0).snapshotStale()).isTrue();
    }

    @Test
    @DisplayName("청크가 사라졌으면 '삭제됨' — 해시를 못 구한 것과 구별한다")
    void deletedVsUnknown() {
        when(repository.findOpenByChunk(CHUNK)).thenReturn(List.of(report(1L, "hash-old")));
        when(repository.findClosedByChunk(eq(CHUNK), anyInt())).thenReturn(List.of());
        when(questionReuseService.currentChunkHash(CHUNK)).thenReturn("");

        when(repository.findChunkLocation(CHUNK)).thenReturn(Optional.empty());
        assertThat(service.chunkDetail(CHUNK).orElseThrow().changeStatus())
                .isEqualTo(ChunkReportService.CHANGE_DELETED);

        // 저장돼 있기는 한데 해시를 못 구한 경우(FTS 미가용)는 '그대로'로 위조하지 않는다.
        when(repository.findChunkLocation(CHUNK)).thenReturn(Optional.of(
                new ChunkLocation("doc-1", "latest", null, "내용", ChunkLocation.SOURCE_SEARCH_TEXT)));
        assertThat(service.chunkDetail(CHUNK).orElseThrow().changeStatus())
                .isEqualTo(ChunkReportService.CHANGE_UNKNOWN);
    }

    @Test
    @DisplayName("열린 신고가 없으면 상세도 없다")
    void noDetailWithoutOpenReports() {
        when(repository.findOpenByChunk(CHUNK)).thenReturn(List.of());
        assertThat(service.chunkDetail(CHUNK)).isEmpty();
    }

    // ── 조치 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("처리 완료는 그 청크의 열린 신고를 한 번에 닫는다")
    void resolveClosesWholeChunk() {
        when(repository.closeChunk(CHUNK, ChunkReportRepository.STATUS_RESOLVED, ADMIN, "수정함"))
                .thenReturn(3);

        assertThat(service.resolveChunk(CHUNK, ADMIN, "수정함")).isEqualTo(3);
        verify(auditLogger).log(eq("chunk.report.resolve"), eq(CHUNK), any());
    }

    @Test
    @DisplayName("반려는 사유가 필수다")
    void rejectNeedsReason() {
        assertThatThrownBy(() -> service.rejectChunk(CHUNK, ADMIN, "  "))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).closeChunk(any(), any(), any(), any());
    }

    @Test
    @DisplayName("이미 처리된 청크는 0건을 닫는다(감사 로그도 남기지 않는다)")
    void closingTwiceIsNoop() {
        when(repository.closeChunk(any(), any(), any(), any())).thenReturn(0);

        assertThat(service.resolveChunk(CHUNK, ADMIN, null)).isZero();
        verify(auditLogger, never()).log(anyString(), anyString(), any());
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static Report report(long id, String hash) {
        return new Report(id, CHUNK, "doc-1", "latest", "manual.pdf", USER, THREAD, 7L,
                "포트가 뭐야?", "WRONG", "8080이 아니라 9090입니다", hash, "신고 당시 원문",
                ChunkReportRepository.STATUS_OPEN, null, null, "2026-09-04 10:00:00", null);
    }

    private static MemoryRepository.Turn turn(String question) {
        return new MemoryRepository.Turn(7L, question, "답변", "2026-09-04 10:00:00",
                "2026-09-04 10:00:01", 10, 20, 300, "local", 1, null, "N", null, false);
    }
}
