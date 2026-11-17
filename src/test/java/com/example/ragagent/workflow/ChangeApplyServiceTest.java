package com.example.ragagent.workflow;

import com.example.ragagent.SqliteTestDatabase;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.ingestion.DocumentWriteLocks;
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
    @DisplayName("관리자가 고친 수정안은 고친 본문으로 적용되고 MD 도 구간 대조로 그 본문을 따른다")
    void editedProposalAppliesTheEditedText() throws IOException {
        proposals.insertAll(List.of(proposal("d1", "c0", "PostgreSQL 사용")));
        long id = proposals.listByJob(jobId).get(0).id();
        assertThat(service.editProposal(jobId, id, "Oracle 19c 사용", "root")).isNull();
        approve("d1");
        applied("c0", AdminService.ApplyOutcome.APPLIED);
        Path md = Files.writeString(converted.resolve("d1.md"), "제목\nPostgreSQL 사용\n");

        ChangeApplyService.DocResult r = service.applyApproved(jobId, "d1", "root");

        assertThat(r.applied()).isEqualTo(1);
        verify(admin).applyChunkEdit(eq("latest"), eq("c0"), eq("Oracle 19c 사용"), any(), eq(TextHash.sha256("PostgreSQL 사용")));
        assertThat(Files.readString(md)).isEqualTo("제목\nOracle 19c 사용\n");
        assertThat(proposals.find(id).orElseThrow().machineAfterText()).isEqualTo("Oracle 사용");
    }

    @Test
    @DisplayName("고치기는 마커를 깨거나 수정 전과 같거나 pending 이 아니면 거절하고 저장하지 않는다")
    void editRejectsInvariantViolations() {
        String before = "PostgreSQL 사용\n[이미지: images/a/1.png]";
        proposals.insertAll(List.of(new ChangeProposalRepository.NewProposal(jobId, "d1", "d1.pdf", "c0", ChangeProposalRepository.KIND_LITERAL,
                before, before.replace("PostgreSQL", "Oracle"), TextHash.sha256(before), null, null, null, null, "리터럴")));
        long id = proposals.listByJob(jobId).get(0).id();

        assertThat(service.editProposal(jobId, id, "Oracle 사용", "root")).contains("이미지 마커");
        assertThat(service.editProposal(jobId, id, before, "root")).contains("수정 전과 같습니다");
        assertThat(service.editProposal(jobId + 99, id, "x", "root")).isNotNull();   // 다른 작업 번호로는 못 고친다
        assertThat(proposals.find(id).orElseThrow().edited()).isFalse();

        approve("d1");
        assertThat(service.editProposal(jobId, id, "Oracle 사용\n[이미지: images/a/1.png]\n추가", "root")).isNotNull();
    }

    @Test
    @DisplayName("청크 삭제 수정안은 applyChunkDelete 로 가고, MD 에서 그 구간이 사라진다")
    void deleteChunkProposalDeletesTheChunkAndItsMdLines() throws IOException {
        String body = "## B 기능\n\nB 를 쓰는 방법.";
        proposals.insertAll(List.of(new ChangeProposalRepository.NewProposal(jobId, "d1", "d1.pdf", "c0",
                ChangeProposalRepository.KIND_DELETE_CHUNK, body, "", TextHash.sha256(body), null, null, null, null, "B 삭제")));
        approve("d1");
        when(admin.applyChunkDelete("latest", "c0", TextHash.sha256(body))).thenReturn(AdminService.ApplyOutcome.APPLIED);
        Path md = Files.writeString(converted.resolve("d1.md"), "## A\n\nA 설명.\n\n## B 기능\n\nB 를 쓰는 방법.\n\n## C\n");

        ChangeApplyService.DocResult r = service.applyApproved(jobId, "d1", "root");

        assertThat(r.applied()).isEqualTo(1);
        verify(admin, never()).applyChunkEdit(anyString(), anyString(), anyString(), any(), anyString());
        assertThat(Files.readString(md)).doesNotContain("B 기능").doesNotContain("B 를 쓰는 방법").contains("## A").contains("## C");
        assertThat(r.mdStatus()).isEqualTo(MarkdownWriteBack.APPLIED);
    }

    @Test
    @DisplayName("겹침 짝 중 하나라도 낡았으면 짝 전체를 쓰지 않는다(stale)")
    void staleOverlapPairIsNotApplied() {
        proposals.insertAll(List.of(
                new ChangeProposalRepository.NewProposal(jobId, "d1", "d1.pdf", "c0", ChangeProposalRepository.KIND_EDIT,
                        "A 는 30초", "A 는 60초", TextHash.sha256("A 는 30초"), null, null, null, null, "편집", null, null, null, "g1"),
                new ChangeProposalRepository.NewProposal(jobId, "d1", "d1.pdf", "c1", ChangeProposalRepository.KIND_EDIT,
                        "30초 이후", "60초 이후", TextHash.sha256("30초 이후"), null, null, null, null, "편집", null, null, null, "g1")));
        approve("d1");
        when(admin.getChunk("latest", "c0")).thenReturn(new AdminService.ChunkRow("c0", "A 는 30초", "A 는 30초", Map.of()));
        when(admin.getChunk("latest", "c1")).thenReturn(new AdminService.ChunkRow("c1", "누가 고침", "누가 고침", Map.of()));

        ChangeApplyService.DocResult r = service.applyApproved(jobId, "d1", "root");

        assertThat(r.stale()).isEqualTo(2);
        assertThat(r.applied()).isZero();
        verify(admin, never()).applyChunkEdit(anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("빼기 — 뺀 수정안은 승인해도 적용되지 않는다")
    void withdrawnProposalIsNotApplied() {
        proposals.insertAll(List.of(proposal("d1", "c0", "PostgreSQL 사용"), proposal("d1", "c1", "PostgreSQL 둘")));
        long first = proposals.listByJob(jobId).get(0).id();

        assertThat(service.withdrawProposal(jobId, first, "root")).isEqualTo(1);
        approve("d1");

        assertThat(statuses()).containsExactly(ChangeProposalRepository.WITHDRAWN, ChangeProposalRepository.APPROVED);
    }

    @Test
    @DisplayName("승인은 문서 단위·한 번만 — 두 번째 승인은 바뀐 것이 없다")
    void approvalIsPerDocumentAndOnce() {
        proposals.insertAll(List.of(proposal("d1", "c0", "PostgreSQL 사용"), proposal("d1", "c1", "PostgreSQL 둘"), proposal("d2", "c9", "PostgreSQL 셋")));

        assertThat(proposals.transitionDocument(jobId, "d1", ChangeProposalRepository.PENDING, ChangeProposalRepository.APPROVED, "a")).isEqualTo(2);
        assertThat(proposals.transitionDocument(jobId, "d1", ChangeProposalRepository.PENDING, ChangeProposalRepository.APPROVED, "b")).isZero();
        assertThat(statuses()).containsExactly(ChangeProposalRepository.APPROVED, ChangeProposalRepository.APPROVED, ChangeProposalRepository.PENDING);
    }

    @Test
    @DisplayName("의미 변경 수정안은 줄 구간 대조로 MD 에 되쓴다 — 드라이버 줄까지 반영되고 MD_PARTIAL 이 생기지 않는다(6a)")
    void semanticProposalsAreWrittenBackByLineHunks() throws IOException {
        proposals.insertAll(List.of(
                new ChangeProposalRepository.NewProposal(jobId, "d1", "d1.pdf", "c0", ChangeProposalRepository.KIND_SEMANTIC,
                        "PostgreSQL 사용\ndriver: org.postgresql.Driver", "Oracle 사용\ndriver: oracle.jdbc.OracleDriver",
                        TextHash.sha256("PostgreSQL 사용\ndriver: org.postgresql.Driver"), "ctx PostgreSQL", "ctx Oracle", null, null, "검토 필요")));
        approve("d1");
        applied("c0", AdminService.ApplyOutcome.APPLIED);
        chunksOfDoc("Oracle 사용\ndriver: oracle.jdbc.OracleDriver");
        Path md = Files.writeString(converted.resolve("d1.md"), "PostgreSQL 사용\ndriver: org.postgresql.Driver\n");

        ChangeApplyService.DocResult r = service.applyApproved(jobId, "d1", "root");

        assertThat(r.applied()).isEqualTo(1);
        assertThat(r.mdStatus()).isEqualTo(MarkdownWriteBack.APPLIED);
        assertThat(Files.readString(md)).isEqualTo("Oracle 사용\ndriver: oracle.jdbc.OracleDriver\n");
        assertThat(proposals.listByJob(jobId).get(0).mdStatus()).isEqualTo(MarkdownWriteBack.APPLIED);
        verify(admin).applyChunkEdit("latest", "c0", "Oracle 사용\ndriver: oracle.jdbc.OracleDriver",
                Map.of(MetaKey.CHUNK_CONTEXT, "ctx Oracle"), TextHash.sha256("PostgreSQL 사용\ndriver: org.postgresql.Driver"));
    }

    @Test
    @DisplayName("변형 규칙으로 만든 작업은 제목·남은 말 검사·MD 되쓰기가 모두 같은 규칙을 쓴다(대문자 변형이 남아 있으면 MD 를 건드리지 않는다)")
    void applyUsesTheSameCaseVariantRuleAsTheProposals() throws IOException {
        long job2 = jobs.create(ChangeJobRepository.KIND_MAINTENANCE, "Oracle → PostgreSQL", "root");
        jobs.finish(job2, ChangeJobRepository.DONE,
                "{\"instruction\":\"x\",\"from\":\"Oracle\",\"to\":\"PostgreSQL\",\"mentions\":2,\"truncated\":false,\"tooShort\":false,"
                        + "\"proposals\":1,\"unchanged\":0,\"manualCurated\":0,\"discarded\":[],\"caseVariants\":true,\"caseVariantChunks\":1}", null);
        String before = "Oracle 와 ORACLE 사용";
        proposals.insertAll(List.of(new ChangeProposalRepository.NewProposal(job2, "d1", "d1.pdf", "c0", ChangeProposalRepository.KIND_LITERAL,
                before, "PostgreSQL 와 POSTGRESQL 사용", TextHash.sha256(before), null, null, null, null, "리터럴")));
        proposals.transitionDocument(job2, "d1", ChangeProposalRepository.PENDING, ChangeProposalRepository.APPROVED, "root");
        when(admin.applyChunkEdit(eq("latest"), eq("c0"), anyString(), any(), anyString())).thenReturn(AdminService.ApplyOutcome.APPLIED);
        when(admin.getChunk("latest", "c0")).thenReturn(new AdminService.ChunkRow("c0", "t", "t", Map.of(MetaKey.HEADING, "3. ORACLE 설정")));
        chunksOfDoc("PostgreSQL 와 POSTGRESQL 사용");
        Path md = Files.writeString(converted.resolve("d1.md"), "Oracle 와 ORACLE 사용\n");

        ChangeApplyService.DocResult r = service.applyApproved(job2, "d1", "root");

        assertThat(r.mdStatus()).isEqualTo(MarkdownWriteBack.APPLIED);
        assertThat(Files.readString(md)).isEqualTo("PostgreSQL 와 POSTGRESQL 사용\n");   // MD 의 대문자 변형도 같은 규칙으로
        verify(admin).applyChunkEdit("latest", "c0", "PostgreSQL 와 POSTGRESQL 사용",
                Map.of(MetaKey.HEADING, "3. POSTGRESQL 설정"), TextHash.sha256(before));      // 제목의 대문자 변형도
    }

    @Test
    @DisplayName("변형 규칙인데 다른 청크에 대문자 변형이 남아 있으면 MD_CONFLICT — 남은 말 검사도 같은 규칙")
    void leftoverCaseVariantsBlockTheMdWhenTheRuleIncludesThem() throws IOException {
        long job2 = jobs.create(ChangeJobRepository.KIND_MAINTENANCE, "Oracle → PostgreSQL", "root");
        jobs.finish(job2, ChangeJobRepository.DONE,
                "{\"instruction\":\"x\",\"from\":\"Oracle\",\"to\":\"PostgreSQL\",\"mentions\":2,\"truncated\":false,\"tooShort\":false,"
                        + "\"proposals\":1,\"unchanged\":0,\"manualCurated\":0,\"discarded\":[],\"caseVariants\":true,\"caseVariantChunks\":1}", null);
        proposals.insertAll(List.of(new ChangeProposalRepository.NewProposal(job2, "d1", "d1.pdf", "c0", ChangeProposalRepository.KIND_LITERAL,
                "Oracle 사용", "PostgreSQL 사용", TextHash.sha256("Oracle 사용"), null, null, null, null, "리터럴")));
        proposals.transitionDocument(job2, "d1", ChangeProposalRepository.PENDING, ChangeProposalRepository.APPROVED, "root");
        when(admin.applyChunkEdit(eq("latest"), eq("c0"), anyString(), any(), anyString())).thenReturn(AdminService.ApplyOutcome.APPLIED);
        chunksOfDoc("PostgreSQL 사용", "다른 청크에 ORACLE 이 남음");
        Path md = Files.writeString(converted.resolve("d1.md"), "Oracle 사용 ORACLE");

        ChangeApplyService.DocResult r = service.applyApproved(job2, "d1", "root");

        assertThat(r.mdStatus()).isEqualTo(MarkdownWriteBack.CONFLICT);
        assertThat(Files.readString(md)).isEqualTo("Oracle 사용 ORACLE");
    }

    // ── 재인덱싱과의 상호 배제(§6.33 4b) ────────────────────────────────────

    private ChangeApplyService serviceSharing(DocumentWriteLocks locks) {
        AppProperties props = mock(AppProperties.class);
        when(props.dataDir()).thenReturn(tmp.toString());
        return new ChangeApplyService(proposals, jobs, admin, backups, props, locks);
    }

    @Test
    @DisplayName("같은 문서에 재인덱싱 같은 쓰기 작업이 진행 중이면 적용하지 않는다 — 백업도 청크 쓰기도 없고 수정안은 승인 상태로 남는다")
    void refusesToApplyWhileTheDocumentIsBeingWritten() {
        DocumentWriteLocks locks = new DocumentWriteLocks();
        ChangeApplyService sharing = serviceSharing(locks);
        proposals.insertAll(List.of(proposal("d1", "c0", "PostgreSQL 사용")));
        approve("d1");

        try (DocumentWriteLocks.Lease reindexing = locks.tryAcquire("d1")) {
            ChangeApplyService.DocResult r = sharing.applyApproved(jobId, "d1", "root");

            assertThat(r.error()).contains("다른 쓰기 작업").contains("재인덱싱");
        }
        verify(backups, never()).run(anyString());
        verify(admin, never()).applyChunkEdit(anyString(), anyString(), anyString(), any(), anyString());
        assertThat(statuses()).containsExactly(ChangeProposalRepository.APPROVED);
    }

    @Test
    @DisplayName("다른 문서의 쓰기 작업은 적용을 막지 않고, 적용이 끝나면(실패해도) 잠금을 푼다 — 풀린 뒤 다시 승인 상태에서 적용된다")
    void otherDocumentsDoNotBlockAndTheLockIsReleased() {
        DocumentWriteLocks locks = new DocumentWriteLocks();
        ChangeApplyService sharing = serviceSharing(locks);
        proposals.insertAll(List.of(proposal("d1", "c0", "PostgreSQL 사용")));
        approve("d1");
        applied("c0", AdminService.ApplyOutcome.APPLIED);
        chunksOfDoc("Oracle 사용");

        try (DocumentWriteLocks.Lease other = locks.tryAcquire("d2")) {
            assertThat(sharing.applyApproved(jobId, "d1", "root").applied()).isEqualTo(1);
        }
        assertThat(locks.isLocked("d1")).as("적용이 끝났으니 잠금이 풀렸다").isFalse();

        // 백업 실패(예외 없는 조기 반환)·예상 밖 예외에서도 풀린다
        long job2 = jobs.create(ChangeJobRepository.KIND_MAINTENANCE, "x", "root");
        proposals.insertAll(List.of(new ChangeProposalRepository.NewProposal(job2, "d3", "d3.pdf", "c9", ChangeProposalRepository.KIND_LITERAL,
                "PostgreSQL", "Oracle", TextHash.sha256("PostgreSQL"), null, null, null, null, "r")));
        proposals.transitionDocument(job2, "d3", ChangeProposalRepository.PENDING, ChangeProposalRepository.APPROVED, "root");
        sharing.applyApproved(job2, "d3", "root");   // 리포트가 없어(작업이 끝나지 않음) 오류로 돌아온다
        assertThat(locks.isLocked("d3")).isFalse();
    }
}
