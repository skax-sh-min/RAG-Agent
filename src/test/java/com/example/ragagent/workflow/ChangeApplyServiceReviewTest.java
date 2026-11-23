package com.example.ragagent.workflow;

import com.example.ragagent.SqliteTestDatabase;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.ingestion.DocumentWriteLocks;
import com.example.ragagent.ingestion.KeywordExtractor;
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
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 수정안 검토 화면의 확인·일괄 적용·순차 적용(6e, AGENT_MCP_DESIGN §10.15) — 실제 {@code change_proposal}/{@code change_job} 스키마와 실제 MD 파일 위에서,
 * 청크 쓰기·백업·맥락 추출만 목으로 둔다. 순차 실행기는 호출한 스레드에서 돌린다({@code runDrainInline}).
 */
class ChangeApplyServiceReviewTest {

    @TempDir Path tmp;

    private ChangeProposalRepository proposals;
    private ChangeJobRepository jobs;
    private AdminService admin;
    private DatabaseBackupService backups;
    private KeywordExtractor extractor;
    private DocumentWriteLocks locks;
    private ChangeApplyService service;
    private Path converted;

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
        when(admin.applyChunkEdit(eq("latest"), anyString(), anyString(), any(), anyString())).thenReturn(AdminService.ApplyOutcome.APPLIED);
        when(backups.run(anyString())).thenAnswer(inv -> {
            Files.writeString(tmp.resolve("b.db"), "x");
            return new DatabaseBackupService.RunResult("pre", Instant.now(), "b.db", 1, 1, null);
        });
        when(backups.backupFile("b.db")).thenReturn(tmp.resolve("b.db"));
        locks = new DocumentWriteLocks();
        service = new ChangeApplyService(proposals, jobs, admin, backups, props, locks);
        extractor = mock(KeywordExtractor.class);
        service.setKeywordExtractor(extractor);
        service.runDrainInline();
    }

    /** 자연어 수정 작업(유형 {@code type}). */
    private long nlJob(String type) {
        long id = jobs.create(ChangeJobRepository.KIND_MAINTENANCE, "callBatchNow 사용 중단", "root");
        jobs.setIntent(id, "{\"mode\":\"nl\",\"type\":\"" + type + "\",\"subject\":\"callBatchNow\"}");
        jobs.finish(id, ChangeJobRepository.DONE, "{}", null);
        return id;
    }

    /** 글자 치환 작업. */
    private long literalJob() {
        long id = jobs.create(ChangeJobRepository.KIND_MAINTENANCE, "PostgreSQL → Oracle", "root");
        jobs.finish(id, ChangeJobRepository.DONE,
                "{\"instruction\":\"x\",\"from\":\"PostgreSQL\",\"to\":\"Oracle\",\"mentions\":1,\"truncated\":false,\"tooShort\":false,"
                        + "\"proposals\":1,\"unchanged\":0,\"manualCurated\":0,\"discarded\":[]}", null);
        return id;
    }

    /** "넣기만" 편집 수정안 — 원문 줄 앞에 안내 한 줄. 맥락은 수정안을 만들 때 만든 값이 있다. */
    private long insertOnly(long job, String doc, String chunk, String before) {
        proposals.insertAll(List.of(new ChangeProposalRepository.NewProposal(job, doc, doc + ".pdf", chunk, ChangeProposalRepository.KIND_EDIT,
                before, "> **사용 중단** — callBatchNow\n" + before, TextHash.sha256(before),
                "옛 맥락", "새 맥락", "kw", "kw2", "안내", null, "describes", "callBatchNow(", null)));
        return last(job);
    }

    /** 원문 줄을 바꾼 편집 수정안 — 일괄 적용 대상이 아니다. */
    private long rewriting(long job, String doc, String chunk, String before) {
        proposals.insertAll(List.of(new ChangeProposalRepository.NewProposal(job, doc, doc + ".pdf", chunk, ChangeProposalRepository.KIND_EDIT,
                before, before.replace("호출", "(사용 중단) 호출"), TextHash.sha256(before),
                "옛 맥락", "새 맥락", null, null, "고침", null, "describes", null, null)));
        return last(job);
    }

    private long last(long job) {
        List<ChangeProposalRepository.Proposal> all = proposals.listByJob(job);
        return all.stream().mapToLong(ChangeProposalRepository.Proposal::id).max().orElseThrow();
    }

    private String status(long id) {
        return proposals.find(id).orElseThrow().status();
    }

    private String hash(long id) {
        return TextHash.sha256(proposals.find(id).orElseThrow().afterText());
    }

    @Test
    @DisplayName("확인 = 그 수정안 하나만 승인·적용 — 같은 문서의 다른 수정안은 대기, MD 는 그 청크만, md_status 는 그 행에만")
    void confirmAppliesOnlyThatProposal() throws Exception {
        long job = nlJob(EditIntent.DEPRECATE);
        Path md = Files.writeString(converted.resolve("d1.md"), "# 7.10\n\ncallBatchNow 즉시 호출\n\n다른 줄 호출\n");
        long a = insertOnly(job, "d1", "c1", "callBatchNow 즉시 호출");
        long b = insertOnly(job, "d1", "c2", "다른 줄 호출");

        assertThat(service.confirmOne(job, a, proposals.find(a).orElseThrow().afterText(), hash(a), "root")).isNull();

        assertThat(status(a)).isEqualTo(ChangeProposalRepository.APPLIED);
        assertThat(status(b)).isEqualTo(ChangeProposalRepository.PENDING);
        assertThat(proposals.find(a).orElseThrow().mdStatus()).isEqualTo(MarkdownWriteBack.APPLIED);
        assertThat(proposals.find(b).orElseThrow().mdStatus()).isNull();
        assertThat(Files.readString(md)).contains("> **사용 중단** — callBatchNow\ncallBatchNow 즉시 호출").contains("\n다른 줄 호출\n")
                .doesNotContain("callBatchNow\n다른 줄");
        verify(admin, times(1)).applyChunkEdit(eq("latest"), eq("c1"), anyString(), any(), anyString());
        verify(admin, never()).applyChunkEdit(eq("latest"), eq("c2"), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("R3 — 고치지 않았고 만들 때의 맥락이 있으면 LLM 을 부르지 않는다, 고쳤으면 확정 본문으로 한 번 다시 만든다")
    void contextIsRegeneratedOnlyWhenEdited() {
        long job = nlJob(EditIntent.DEPRECATE);
        long a = insertOnly(job, "d1", "c1", "callBatchNow 즉시 호출");
        long b = insertOnly(job, "d1", "c2", "다른 줄 호출");
        when(admin.getChunk("latest", "c2")).thenReturn(new AdminService.ChunkRow("c2", "다른 줄 호출", "다른 줄 호출", Map.of()));
        when(extractor.enrichSingle(any())).thenAnswer(inv -> {
            org.springframework.ai.document.Document d = inv.getArgument(0);
            assertThat(d.getText()).isEqualTo("> 사람이 고친 안내\n다른 줄 호출");
            Map<String, Object> m = new java.util.HashMap<>(d.getMetadata());
            m.put(MetaKey.CHUNK_CONTEXT, "다시 만든 맥락");
            m.put(MetaKey.EXCERPT_KEYWORDS, "다시");
            return new org.springframework.ai.document.Document(d.getText(), m);
        });

        service.confirmOne(job, a, proposals.find(a).orElseThrow().afterText(), hash(a), "root");
        verify(extractor, never()).enrichSingle(any());

        service.confirmOne(job, b, "> 사람이 고친 안내\n다른 줄 호출", hash(b), "root");
        verify(extractor, times(1)).enrichSingle(any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> meta = ArgumentCaptor.forClass(Map.class);
        verify(admin).applyChunkEdit(eq("latest"), eq("c2"), eq("> 사람이 고친 안내\n다른 줄 호출"), meta.capture(), anyString());
        assertThat(meta.getValue()).containsEntry(MetaKey.CHUNK_CONTEXT, "다시 만든 맥락");
        assertThat(proposals.find(b).orElseThrow().machineAfterText()).startsWith("> **사용 중단**");   // 기계 수정안이 남는다
        assertThat(proposals.find(b).orElseThrow().afterContext()).isEqualTo("다시 만든 맥락");
    }

    @Test
    @DisplayName("R4 — 맥락 재생성이 실패해도 본문은 적용되고, 맥락은 건드리지 않으며 경고가 남는다")
    void contextFailureStillApplies() {
        long job = nlJob(EditIntent.ADD_NOTE);
        long a = insertOnly(job, "d1", "c1", "callBatchNow 즉시 호출");
        when(extractor.enrichSingle(any())).thenThrow(new RuntimeException("LLM down"));

        assertThat(service.confirmOne(job, a, "> 고친 안내\ncallBatchNow 즉시 호출", hash(a), "root")).isNull();

        assertThat(status(a)).isEqualTo(ChangeProposalRepository.APPLIED);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> meta = ArgumentCaptor.forClass(Map.class);
        verify(admin).applyChunkEdit(eq("latest"), eq("c1"), anyString(), meta.capture(), anyString());
        assertThat(meta.getValue()).doesNotContainKey(MetaKey.CHUNK_CONTEXT);
        assertThat(service.noteFor(a)).contains("옛 값");
    }

    @Test
    @DisplayName("R2 — 같은 작업에서 여러 번 확인해도 백업은 한 번, 그 파일이 지워졌으면 다시 만든다")
    void oneBackupPerJob() throws Exception {
        long job = nlJob(EditIntent.DEPRECATE);
        long a = insertOnly(job, "d1", "c1", "가 호출");
        long b = insertOnly(job, "d2", "c2", "나 호출");
        long c = insertOnly(job, "d3", "c3", "다 호출");

        service.confirmOne(job, a, proposals.find(a).orElseThrow().afterText(), hash(a), "root");
        service.confirmOne(job, b, proposals.find(b).orElseThrow().afterText(), hash(b), "root");
        verify(backups, times(1)).run("pre-change-" + job);
        assertThat(jobs.backupName(job)).isEqualTo("b.db");

        Files.delete(tmp.resolve("b.db"));
        service.confirmOne(job, c, proposals.find(c).orElseThrow().afterText(), hash(c), "root");
        verify(backups, times(2)).run("pre-change-" + job);
    }

    @Test
    @DisplayName("백업이 실패하면 아무것도 쓰지 않고 승인 상태로 남는다 — 다시 시도하면 적용된다")
    void backupFailureLeavesApproved() {
        long job = nlJob(EditIntent.DEPRECATE);
        long a = insertOnly(job, "d1", "c1", "가 호출");
        when(backups.run(anyString())).thenReturn(new DatabaseBackupService.RunResult("pre", Instant.now(), null, 0, 1, "disk full"));

        service.confirmOne(job, a, proposals.find(a).orElseThrow().afterText(), hash(a), "root");

        assertThat(status(a)).isEqualTo(ChangeProposalRepository.APPROVED);
        assertThat(service.noteFor(a)).contains("disk full");
        verify(admin, never()).applyChunkEdit(anyString(), anyString(), anyString(), any(), anyString());
        assertThat(service.draining(job)).isFalse();
    }

    @Test
    @DisplayName("R8 — 재인덱싱이 문서를 잡고 있으면 승인 상태로 남고, 풀린 뒤 다시 시도로 적용된다")
    void lockedDocumentIsDeferredThenRetried() {
        long job = nlJob(EditIntent.DEPRECATE);
        long a = insertOnly(job, "d1", "c1", "가 호출");
        DocumentWriteLocks.Lease reindex = locks.tryAcquire("d1");

        service.confirmOne(job, a, proposals.find(a).orElseThrow().afterText(), hash(a), "root");
        assertThat(status(a)).isEqualTo(ChangeProposalRepository.APPROVED);
        assertThat(service.noteFor(a)).contains("다른 쓰기 작업");

        reindex.close();
        service.retry(job);
        assertThat(status(a)).isEqualTo(ChangeProposalRepository.APPLIED);
    }

    @Test
    @DisplayName("같은 문서의 두 청크를 연달아 확인해도 둘 다 적용된다")
    void twoChunksOfOneDocument() {
        long job = nlJob(EditIntent.DEPRECATE);
        long a = insertOnly(job, "d1", "c1", "가 호출");
        long b = insertOnly(job, "d1", "c2", "나 호출");
        service.confirmOne(job, a, proposals.find(a).orElseThrow().afterText(), hash(a), "root");
        service.confirmOne(job, b, proposals.find(b).orElseThrow().afterText(), hash(b), "root");
        assertThat(status(a)).isEqualTo(ChangeProposalRepository.APPLIED);
        assertThat(status(b)).isEqualTo(ChangeProposalRepository.APPLIED);
    }

    @Test
    @DisplayName("동시 편집은 충돌, 마커를 깨는 편집은 저장도 확인도 안 된다 — 상태는 대기 그대로")
    void conflictsAndInvariants() {
        long job = nlJob(EditIntent.DEPRECATE);
        proposals.insertAll(List.of(new ChangeProposalRepository.NewProposal(job, "d1", "d1.pdf", "c1", ChangeProposalRepository.KIND_EDIT,
                "[이미지: a.png]\n가 호출", "> 안내\n[이미지: a.png]\n가 호출", TextHash.sha256("[이미지: a.png]\n가 호출"),
                null, null, null, null, "x", null, null, null, null)));
        long a = last(job);

        assertThatThrownBy(() -> service.confirmOne(job, a, "> 안내\n[이미지: a.png]\n가 호출", "다른해시", "root"))
                .isInstanceOf(ChangeApplyService.ProposalConflictException.class);
        assertThat(service.confirmOne(job, a, "> 안내\n가 호출", hash(a), "root")).contains("마커");
        assertThat(service.saveDraft(job, a, "> 안내\n가 호출", hash(a), "root")).contains("마커");
        assertThat(status(a)).isEqualTo(ChangeProposalRepository.PENDING);
        assertThat(proposals.find(a).orElseThrow().edited()).isFalse();
        verify(admin, never()).applyChunkEdit(anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("임시 저장 → 기계 수정안으로 되돌리기 — 배지가 사라지고 본문이 기계 수정안으로")
    void draftThenRestore() {
        long job = nlJob(EditIntent.DEPRECATE);
        long a = insertOnly(job, "d1", "c1", "가 호출");
        String machine = proposals.find(a).orElseThrow().afterText();

        assertThat(service.saveDraft(job, a, "> 고침\n가 호출", hash(a), "root")).isNull();
        assertThat(proposals.find(a).orElseThrow().edited()).isTrue();
        assertThat(status(a)).isEqualTo(ChangeProposalRepository.PENDING);

        assertThat(service.restoreMachine(job, a)).isTrue();
        assertThat(proposals.find(a).orElseThrow().afterText()).isEqualTo(machine);
        assertThat(proposals.find(a).orElseThrow().edited()).isFalse();
    }

    @Test
    @DisplayName("R10 — 일괄 적용은 deprecate·add_note 자연어 작업의 '넣기만' 수정안만, 원문을 바꾼 수정안은 건너뛴다")
    void batchOnlyForInsertOnlyProposals() {
        long job = nlJob(EditIntent.DEPRECATE);
        long a = insertOnly(job, "d1", "c1", "가 호출");
        long b = rewriting(job, "d1", "c2", "나 호출");
        long c = insertOnly(job, "d2", "c3", "다 호출");

        assertThat(service.approveBatch(job, "d1", "root")).isEqualTo(1);   // 문서 필터
        assertThat(status(a)).isEqualTo(ChangeProposalRepository.APPLIED);
        assertThat(status(b)).isEqualTo(ChangeProposalRepository.PENDING);
        assertThat(status(c)).isEqualTo(ChangeProposalRepository.PENDING);

        assertThat(service.approveBatch(job, null, "root")).isEqualTo(1);
        assertThat(status(c)).isEqualTo(ChangeProposalRepository.APPLIED);
        assertThat(status(b)).isEqualTo(ChangeProposalRepository.PENDING);
    }

    @Test
    @DisplayName("R10 — remove·revise·글자 바꾸기 작업은 검토 화면의 일괄 적용과 작업 화면의 문서 단위 승인이 둘 다 거절된다")
    void batchRefusedForTextChangingJobs() {
        for (long job : new long[] {nlJob(EditIntent.REMOVE), nlJob(EditIntent.REVISE), literalJob()}) {
            insertOnly(job, "d1", "c1", "가 호출");
            assertThatThrownBy(() -> service.approveBatch(job, null, "root"))
                    .isInstanceOf(ChangeApplyService.BatchNotAllowedException.class);
            assertThatThrownBy(() -> service.approveAndApply(job, "d1", "root"))
                    .isInstanceOf(ChangeApplyService.BatchNotAllowedException.class);
            assertThat(proposals.listByJob(job)).allMatch(p -> ChangeProposalRepository.PENDING.equals(p.status()));
        }
        verify(backups, never()).run(anyString());
    }

    @Test
    @DisplayName("승인 없이는 검토 경로에서도 아무것도 바뀌지 않는다 — 다시 시도·실행기는 대기 수정안을 건드리지 않는다")
    void nothingHappensWithoutApprovalOnTheReviewPath() {
        long job = nlJob(EditIntent.DEPRECATE);
        long a = insertOnly(job, "d1", "c1", "가 호출");

        service.retry(job);
        service.drain(job);

        assertThat(status(a)).isEqualTo(ChangeProposalRepository.PENDING);
        verify(backups, never()).run(anyString());
        verify(admin, never()).applyChunkEdit(anyString(), anyString(), anyString(), any(), anyString());
    }
}
