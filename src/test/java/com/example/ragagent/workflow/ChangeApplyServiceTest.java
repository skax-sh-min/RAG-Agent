package com.example.ragagent.workflow;

import com.example.ragagent.SqliteTestDatabase;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.model.TextHash;
import com.example.ragagent.repository.ChangeJobRepository;
import com.example.ragagent.repository.ChangeProposalRepository;
import com.example.ragagent.service.AdminService;
import com.example.ragagent.service.DatabaseBackupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 승인된 수정안의 적용(§6.33 4b-2) — 실제 {@code change_proposal}/{@code change_job} 스키마(Flyway)와 실제 MD 파일 위에서, 청크 쓰기와 백업만 목으로 둔다.
 * 승인 없이는 아무것도 쓰지 않고, 백업이 실패하면 쓰지 않고, 청크가 바뀌었으면 stale 이고, 임베딩이 실패하면 failed 이며, MD 는 조건이 맞을 때만 바뀐다.
 */
class ChangeApplyServiceTest {

    @TempDir Path tmp;

    private ChangeProposalRepository proposals;
    private ChangeJobRepository jobs;
    private AdminService admin;
    private DatabaseBackupService backups;
    private ChangeApplyService service;
    private Path converted;
    private long jobId;

    @BeforeEach
    void setUp() throws Exception {
        JdbcTemplate jdbc = SqliteTestDatabase.open(tmp.resolve("t.db"));
        proposals = new ChangeProposalRepository(jdbc);
        jobs = new ChangeJobRepository(jdbc);
        admin = mock(AdminService.class);
        backups = mock(DatabaseBackupService.class);
        AppProperties props = mock(AppProperties.class);
        when(props.dataDir()).thenReturn(tmp.toString());
        converted = Files.createDirectories(tmp.resolve("converted"));
        when(admin.collectionFor(anyString())).thenReturn("latest");
        when(backups.run(anyString())).thenReturn(new DatabaseBackupService.RunResult("pre", Instant.now(), "b.db", 1, 1, null));
        service = new ChangeApplyService(proposals, jobs, admin, backups, props);

        jobId = jobs.create(ChangeJobRepository.KIND_MAINTENANCE, "PostgreSQL → Oracle", "root");
        jobs.finish(jobId, ChangeJobRepository.DONE,
                "{\"instruction\":\"x\",\"from\":\"PostgreSQL\",\"to\":\"Oracle\",\"mentions\":2,\"truncated\":false,\"tooShort\":false,"
                        + "\"proposals\":2,\"unchanged\":0,\"manualCurated\":0,\"discarded\":[]}", null);
    }

    private ChangeProposalRepository.NewProposal proposal(String doc, String chunk, String before) {
        return new ChangeProposalRepository.NewProposal(jobId, doc, doc + ".pdf", chunk, ChangeProposalRepository.KIND_LITERAL,
                before, before.replace("PostgreSQL", "Oracle"), TextHash.sha256(before),
                "ctx PostgreSQL", "ctx Oracle", null, null, "리터럴");
    }

    private void approve(String doc) {
        proposals.transitionDocument(jobId, doc, ChangeProposalRepository.PENDING, ChangeProposalRepository.APPROVED, "root");
    }

    private void chunksOfDoc(String... texts) {
        List<AdminService.ChunkRow> rows = new java.util.ArrayList<>();
        for (int i = 0; i < texts.length; i++) rows.add(new AdminService.ChunkRow("c" + i, texts[i], texts[i], Map.of(MetaKey.DOC_ID, "d1")));
        when(admin.getAllChunks(eq("latest"), eq("d1"))).thenReturn(rows);
    }

    private void applied(String chunk, AdminService.ApplyOutcome outcome) {
        when(admin.applyChunkEdit(eq("latest"), eq(chunk), anyString(), any(), anyString())).thenReturn(outcome);
    }

    private List<String> statuses() {
        return proposals.listByJob(jobId).stream().map(ChangeProposalRepository.Proposal::status).toList();
    }

    @Test
    @DisplayName("승인되지 않은 수정안은 백업도 청크 쓰기도 일어나지 않는다 — pending 은 그대로")
    void nothingHappensWithoutApproval() {
        proposals.insertAll(List.of(proposal("d1", "c0", "PostgreSQL 사용")));

        ChangeApplyService.DocResult r = service.applyApproved(jobId, "d1", "root");

        assertThat(r.error()).isNotNull();
        verify(backups, never()).run(anyString());
        verify(admin, never()).applyChunkEdit(anyString(), anyString(), anyString(), any(), anyString());
        assertThat(statuses()).containsExactly(ChangeProposalRepository.PENDING);
    }

    @Test
    @DisplayName("승인 뒤 적용 — 백업 → 청크(해시·맥락 전달) → applied, 청크에 바꿀 말이 안 남으면 MD 도 반영")
    void appliesApprovedProposalsAndWritesTheMd() throws IOException {
        proposals.insertAll(List.of(proposal("d1", "c0", "PostgreSQL 사용")));
        approve("d1");
        applied("c0", AdminService.ApplyOutcome.APPLIED);
        chunksOfDoc("Oracle 사용");
        Path md = Files.writeString(converted.resolve("d1.md"), "# 문서\nPostgreSQL 사용\n");

        ChangeApplyService.DocResult r = service.applyApproved(jobId, "d1", "root");

        assertThat(r.error()).isNull();
        assertThat(r.applied()).isEqualTo(1);
        assertThat(r.mdStatus()).isEqualTo(MarkdownWriteBack.APPLIED);
        verify(backups).run("pre-change-" + jobId);
        verify(admin).applyChunkEdit("latest", "c0", "Oracle 사용",
                Map.of(MetaKey.CHUNK_CONTEXT, "ctx Oracle"), TextHash.sha256("PostgreSQL 사용"));
        assertThat(Files.readString(md)).isEqualTo("# 문서\nOracle 사용\n");
        ChangeProposalRepository.Proposal p = proposals.listByJob(jobId).get(0);
        assertThat(p.status()).isEqualTo(ChangeProposalRepository.APPLIED);
        assertThat(p.appliedAt()).isNotBlank();
        assertThat(p.mdStatus()).isEqualTo(MarkdownWriteBack.APPLIED);
    }

    @Test
    @DisplayName("백업이 실패하면 아무것도 쓰지 않고 승인 상태로 남는다 — 복구 지점 없이 쓰지 않는다")
    void backupFailureAbortsBeforeAnyWrite() {
        proposals.insertAll(List.of(proposal("d1", "c0", "PostgreSQL 사용")));
        approve("d1");
        when(backups.run(anyString())).thenReturn(new DatabaseBackupService.RunResult("pre", Instant.now(), null, 0, 1, "disk full"));

        ChangeApplyService.DocResult r = service.applyApproved(jobId, "d1", "root");

        assertThat(r.error()).contains("백업").contains("disk full");
        verify(admin, never()).applyChunkEdit(anyString(), anyString(), anyString(), any(), anyString());
        assertThat(statuses()).containsExactly(ChangeProposalRepository.APPROVED);
    }

    @Test
    @DisplayName("섹션 제목에 바꿀 말이 있으면 제목도 함께 바꿔 넘긴다(없으면 넘기지 않는다)")
    void theSectionHeadingFollowsTheReplacement() {
        proposals.insertAll(List.of(proposal("d1", "c0", "PostgreSQL 사용"), proposal("d1", "c1", "PostgreSQL 둘")));
        approve("d1");
        applied("c0", AdminService.ApplyOutcome.APPLIED);
        applied("c1", AdminService.ApplyOutcome.APPLIED);
        when(admin.getChunk("latest", "c0")).thenReturn(new AdminService.ChunkRow("c0", "t", "t",
                Map.of(MetaKey.HEADING, "10. PostgreSQL — 접속")));
        when(admin.getChunk("latest", "c1")).thenReturn(new AdminService.ChunkRow("c1", "t", "t", Map.of(MetaKey.HEADING, "다른 제목")));
        chunksOfDoc("Oracle 사용", "Oracle 둘");

        service.applyApproved(jobId, "d1", "root");

        verify(admin).applyChunkEdit("latest", "c0", "Oracle 사용",
                Map.of(MetaKey.CHUNK_CONTEXT, "ctx Oracle", MetaKey.HEADING, "10. Oracle — 접속"), TextHash.sha256("PostgreSQL 사용"));
        verify(admin).applyChunkEdit("latest", "c1", "Oracle 둘",
                Map.of(MetaKey.CHUNK_CONTEXT, "ctx Oracle"), TextHash.sha256("PostgreSQL 둘"));
    }

    @Test
    @DisplayName("청크가 그사이 바뀌었으면(stale) · 임베딩이 실패하면(failed) 각각 그 상태로 남고, 적용된 것이 없으면 MD 는 건드리지 않는다")
    void staleAndFailedAreRecordedAndTheMdIsLeftAlone() throws IOException {
        proposals.insertAll(List.of(proposal("d1", "c0", "PostgreSQL 하나"), proposal("d1", "c1", "PostgreSQL 둘")));
        approve("d1");
        applied("c0", AdminService.ApplyOutcome.STALE);
        applied("c1", AdminService.ApplyOutcome.EMBED_FAILED);
        Path md = Files.writeString(converted.resolve("d1.md"), "PostgreSQL 하나 PostgreSQL 둘");

        ChangeApplyService.DocResult r = service.applyApproved(jobId, "d1", "root");

        assertThat(r.applied()).isZero();
        assertThat(r.stale()).isEqualTo(1);
        assertThat(r.failed()).isEqualTo(1);
        assertThat(r.mdStatus()).isNull();
        assertThat(statuses()).containsExactly(ChangeProposalRepository.STALE, ChangeProposalRepository.FAILED);
        assertThat(Files.readString(md)).isEqualTo("PostgreSQL 하나 PostgreSQL 둘");
    }

    @Test
    @DisplayName("일부만 적용되어 다른 청크에 바꿀 말이 남으면 MD_CONFLICT — MD 는 그대로(재인덱싱하면 되돌아간다고 리포트)")
    void partialApplyLeavesTheMdUntouched() throws IOException {
        proposals.insertAll(List.of(proposal("d1", "c0", "PostgreSQL 하나"), proposal("d1", "c1", "PostgreSQL 둘")));
        approve("d1");
        applied("c0", AdminService.ApplyOutcome.APPLIED);
        applied("c1", AdminService.ApplyOutcome.STALE);
        chunksOfDoc("Oracle 하나", "PostgreSQL 둘");   // c1 에 아직 남아 있다
        Path md = Files.writeString(converted.resolve("d1.md"), "PostgreSQL 하나 PostgreSQL 둘");

        ChangeApplyService.DocResult r = service.applyApproved(jobId, "d1", "root");

        assertThat(r.applied()).isEqualTo(1);
        assertThat(r.mdStatus()).isEqualTo(MarkdownWriteBack.CONFLICT);
        assertThat(Files.readString(md)).isEqualTo("PostgreSQL 하나 PostgreSQL 둘");
        assertThat(proposals.listByJob(jobId).get(0).mdStatus()).isEqualTo(MarkdownWriteBack.CONFLICT);
    }

    @Test
    @DisplayName("변환 MD 가 없으면 MD_MISSING — 청크는 적용된다")
    void missingMdIsReported() {
        proposals.insertAll(List.of(proposal("d1", "c0", "PostgreSQL 사용")));
        approve("d1");
        applied("c0", AdminService.ApplyOutcome.APPLIED);
        chunksOfDoc("Oracle 사용");

        ChangeApplyService.DocResult r = service.applyApproved(jobId, "d1", "root");

        assertThat(r.applied()).isEqualTo(1);
        assertThat(r.mdStatus()).isEqualTo(MarkdownWriteBack.MISSING);
    }

    @Test
    @DisplayName("거절은 코퍼스를 건드리지 않는다 — 한 문서의 pending 만 rejected 로, 다른 문서는 그대로")
    void rejectIsPerDocumentAndWritesNothing() {
        proposals.insertAll(List.of(proposal("d1", "c0", "PostgreSQL 사용"), proposal("d2", "c9", "PostgreSQL 사용")));

        assertThat(service.reject(jobId, "d1", "root")).isEqualTo(1);

        assertThat(statuses()).containsExactly(ChangeProposalRepository.REJECTED, ChangeProposalRepository.PENDING);
        verify(admin, never()).applyChunkEdit(anyString(), anyString(), anyString(), any(), anyString());
        verify(backups, never()).run(anyString());
    }

    @Test
    @DisplayName("승인은 문서 단위·한 번만 — 두 번째 승인은 바뀐 것이 없다")
    void approvalIsPerDocumentAndOnce() {
        proposals.insertAll(List.of(proposal("d1", "c0", "PostgreSQL 사용"), proposal("d1", "c1", "PostgreSQL 둘"), proposal("d2", "c9", "PostgreSQL 셋")));

        assertThat(proposals.transitionDocument(jobId, "d1", ChangeProposalRepository.PENDING, ChangeProposalRepository.APPROVED, "a")).isEqualTo(2);
        assertThat(proposals.transitionDocument(jobId, "d1", ChangeProposalRepository.PENDING, ChangeProposalRepository.APPROVED, "b")).isZero();
        assertThat(statuses()).containsExactly(ChangeProposalRepository.APPROVED, ChangeProposalRepository.APPROVED, ChangeProposalRepository.PENDING);
    }
}
