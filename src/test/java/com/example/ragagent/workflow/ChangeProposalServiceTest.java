package com.example.ragagent.workflow;

import com.example.ragagent.SqliteTestDatabase;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.model.TextHash;
import com.example.ragagent.repository.ChangeJobRepository;
import com.example.ragagent.repository.ChangeProposalRepository;
import com.example.ragagent.service.AdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 밖에서 들어오는 수정안(AGENT_MCP_DESIGN §10.9) — 실제 스키마(Flyway) 위에서. 코퍼스에는 아무것도 쓰지 않고, 가드 위반은 예외가 아니라 사유로 돌려주며,
 * 낡은 해시·큐레이션·남의 작업은 거절한다.
 */
class ChangeProposalServiceTest {

    @TempDir Path tmp;

    private static final String BODY = "# A 기능\n\nA 기능은 30초 동안 기다린다.\n\n[이미지: images/x/1.png]";

    private AdminService admin;
    private ChangeJobRepository jobs;
    private ChangeProposalRepository proposals;
    private ChangeProposalService service;

    @BeforeEach
    void setUp() throws Exception {
        var jdbc = SqliteTestDatabase.open(tmp.resolve("t.db"));
        jobs = new ChangeJobRepository(jdbc);
        proposals = new ChangeProposalRepository(jdbc);
        admin = mock(AdminService.class);
        when(admin.collectionFor(anyString())).thenReturn("latest");
        chunk("c1", BODY, null);
        service = new ChangeProposalService(admin, jobs, proposals);
    }

    private void chunk(String id, String text, String docType) {
        Map<String, String> meta = new HashMap<>();
        meta.put(MetaKey.DOC_ID, "d1");
        meta.put(MetaKey.FILENAME, "guide.pdf");
        meta.put(MetaKey.VERSION, "latest");
        if (docType != null) meta.put(MetaKey.DOC_TYPE, docType);
        when(admin.getChunk("latest", id)).thenReturn(new AdminService.ChunkRow(id, text, text, meta));
    }

    private static List<AnchoredEdit.Operation> note() {
        return List.of(AnchoredEdit.Operation.insertAfter("30초 동안 기다린다.", "> **주의** — A 는 더 이상 쓰지 않는다."));
    }

    @Test
    @DisplayName("통과한 제안은 새 외부 작업의 pending 수정안이 될 뿐이다 — 청크 쓰기는 일어나지 않는다")
    void acceptedProposalOnlyQueues() {
        ChangeProposalService.ProposeResult r = service.proposeExternal("mcp-admin", "c1", TextHash.sha256(BODY), note(), null, false,
                "A 기능 사용 중단 안내", null);

        assertThat(r.accepted()).isTrue();
        assertThat(r.violations()).isEmpty();
        assertThat(r.reviewUrl()).isEqualTo("/admin/change-jobs/" + r.jobId() + "/review");
        ChangeProposalRepository.Proposal p = proposals.find(r.proposalId()).orElseThrow();
        assertThat(p.status()).isEqualTo(ChangeProposalRepository.PENDING);
        assertThat(p.kind()).isEqualTo(ChangeProposalRepository.KIND_EDIT);
        assertThat(p.afterText()).contains("> **주의** — A 는 더 이상 쓰지 않는다.").contains("[이미지: images/x/1.png]");
        assertThat(p.beforeHash()).isEqualTo(TextHash.sha256(BODY));
        assertThat(p.opsJson()).contains("INSERT_AFTER");
        ChangeJobRepository.Job job = jobs.find(r.jobId()).orElseThrow();
        assertThat(job.status()).isEqualTo(ChangeJobRepository.DONE);
        assertThat(job.requestText()).isEqualTo("MCP: A 기능 사용 중단 안내");
        assertThat(job.intentJson()).contains("external");
        verify(admin, never()).applyChunkEdit(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.any(), anyString());
        verify(admin, never()).applyChunkDelete(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("같은 jobId 로 이어 붙인다 — 남의 작업·글자 치환 작업에는 못 붙이고, 같은 청크의 대기 수정안이 있으면 거절")
    void appendsOnlyToOwnExternalJob() {
        chunk("c2", "C 는 따로 쓴다.", null);
        long job = service.proposeExternal("mcp-admin", "c1", TextHash.sha256(BODY), note(), null, false, "안내", null).jobId();

        assertThat(service.proposeExternal("mcp-admin", "c2", TextHash.sha256("C 는 따로 쓴다."), null, "C 는 쓰지 않는다.", false, "C", job)
                .accepted()).isTrue();
        assertThat(proposals.listByJob(job)).hasSize(2);

        assertThat(service.proposeExternal("someone-else", "c2", TextHash.sha256("C 는 따로 쓴다."), null, "x", false, "C", job)
                .violations().get(0)).contains("외부 작업이 아닙니다");
        long literal = jobs.create(ChangeJobRepository.KIND_MAINTENANCE, "A → B", "mcp-admin");
        assertThat(service.proposeExternal("mcp-admin", "c2", TextHash.sha256("C 는 따로 쓴다."), null, "x", false, "C", literal)
                .accepted()).isFalse();
        assertThat(service.proposeExternal("mcp-admin", "c1", TextHash.sha256(BODY), note(), null, false, "다시", job)
                .violations().get(0)).contains("이미 있습니다");
    }

    @Test
    @DisplayName("낡은 해시·없는 청크·큐레이션 청크는 거절하고 작업을 만들지 않는다")
    void staleHashMissingOrCuratedAreRejected() {
        chunk("cq", "Q&A", "curated_qa");

        assertThat(service.proposeExternal("a", "c1", "old", note(), null, false, "x", null).violations().get(0)).contains("바뀌었습니다");
        assertThat(service.proposeExternal("a", "nope", "h", note(), null, false, "x", null).violations().get(0)).contains("찾을 수 없습니다");
        assertThat(service.proposeExternal("a", "cq", TextHash.sha256("Q&A"), null, "Q&A!", false, "x", null).violations().get(0))
                .contains("찾을 수 없습니다");
        assertThat(jobs.recentByKind(ChangeJobRepository.KIND_MAINTENANCE, 10)).isEmpty();
    }

    @Test
    @DisplayName("편집 하나라도 가드에 걸리면 전부 받지 않고 작업별 사유를 돌려준다(일부만 적용되면 뜻이 달라질 수 있다)")
    void partialFailureRejectsTheWholeProposal() {
        ChangeProposalService.ProposeResult r = service.proposeExternal("a", "c1", TextHash.sha256(BODY), List.of(
                AnchoredEdit.Operation.replace("30초", "60초"),
                AnchoredEdit.Operation.replace("images/x/1.png", "images/y/2.png")), null, false, "x", null);

        assertThat(r.accepted()).isFalse();
        assertThat(r.violations()).anyMatch(v -> v.startsWith("작업 2:") && v.contains("일부만"));
        assertThat(jobs.recentByKind(ChangeJobRepository.KIND_MAINTENANCE, 10)).isEmpty();
    }

    @Test
    @DisplayName("newText 는 마커·펜스 불변식만, deleteWholeChunk 는 빈 본문의 delete_chunk 수정안 — 형태는 정확히 하나")
    void newTextAndDeleteForms() {
        assertThat(service.proposeExternal("a", "c1", TextHash.sha256(BODY), null, "# A 기능\n\n바뀐 설명", false, "x", null)
                .violations().get(0)).contains("이미지 마커");

        ChangeProposalService.ProposeResult del = service.proposeExternal("a", "c1", TextHash.sha256(BODY), null, null, true, "A 삭제", null);
        assertThat(del.accepted()).isTrue();
        ChangeProposalRepository.Proposal p = proposals.find(del.proposalId()).orElseThrow();
        assertThat(p.kind()).isEqualTo(ChangeProposalRepository.KIND_DELETE_CHUNK);
        assertThat(p.afterText()).isEmpty();

        assertThat(service.proposeExternal("a", "c1", TextHash.sha256(BODY), note(), null, true, "x", null).violations().get(0))
                .contains("정확히 하나");
        assertThat(service.proposeExternal("a", "c1", TextHash.sha256(BODY), note(), null, false, " ", null).violations().get(0))
                .contains("rationale");
    }

    @Test
    @DisplayName("작업 조회 — 상태별 수, 축약 Diff, 검토 주소. 유지보수 작업이 아니면 비어 있다")
    void jobSummaryShowsStatusesAndDiffs() {
        long job = service.proposeExternal("a", "c1", TextHash.sha256(BODY), note(), null, false, "안내", null).jobId();
        long impact = jobs.create(ChangeJobRepository.KIND_IMPACT, "X", "a");

        ChangeProposalService.JobSummary s = service.jobSummary(job, 50, 1_500).orElseThrow();

        assertThat(s.total()).isEqualTo(1);
        assertThat(s.byStatus()).containsEntry(ChangeProposalRepository.PENDING, 1);
        assertThat(s.proposals().get(0).diff()).contains("+> **주의**");
        assertThat(s.reviewUrl()).isEqualTo("/admin/change-jobs/" + job + "/review");
        assertThat(service.jobSummary(impact, 50, 1_500)).isEmpty();
    }
}
