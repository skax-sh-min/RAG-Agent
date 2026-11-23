package com.example.ragagent.workflow;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.model.TextHash;
import com.example.ragagent.repository.ChangeJobRepository;
import com.example.ragagent.repository.ChangeProposalRepository;
import com.example.ragagent.service.AdminService;
import com.example.ragagent.service.ChunkDiff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 밖에서 들어오는 수정안(AGENT_MCP_DESIGN §10.9, 6b) — 외부 에이전트가 MCP 관리자 키로 청크 하나의 편집을 제안한다. <b>코퍼스에는 아무것도 쓰지
 * 않는다</b>: 가드를 지난 제안은 {@code change_proposal} 의 {@code pending} 행이 될 뿐이고, 쓰는 곳은 관리자가 {@code /admin} 에서 승인한 뒤의
 * {@link ChangeApplyService} 하나다(승인은 Tool 이 아니다 — 에이전트가 자기 수정안을 승인할 수 없다).
 *
 * <p>가드 위반은 예외가 아니라 {@link ProposeResult#violations()} 로 돌려준다 — 에이전트가 읽고 고쳐 다시 낼 수 있게. 권한 검사는 호출하는 Tool 의
 * {@code ToolGuard} 가 한다(이 서비스는 관리자 호출자를 전제한다).
 */
@Service
public class ChangeProposalService {

    private static final Logger log = LoggerFactory.getLogger(ChangeProposalService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 외부 작업 하나에 쌓을 수 있는 수정안 상한. */
    static final int MAX_PROPOSALS_PER_JOB = 200;
    /** 본문 전체({@code newText})로 낸 제안의 글자 상한 — 관리자 고치기와 같다. */
    static final int MAX_NEW_TEXT_CHARS = ChangeApplyService.MAX_EDITED_CHARS;
    static final String MODE_EXTERNAL = "external";
    /** 검토 화면 주소(상대 경로) — 에이전트가 사람에게 건넨다. */
    public static final String REVIEW_PATH = "/admin/change-jobs/";

    /** 외부 에이전트에게 돌려주는 검토 주소 — 수정안 검토 화면(6e, AGENT_MCP_DESIGN §10.15.5). */
    static String reviewUrl(long jobId) {
        return REVIEW_PATH + jobId + "/review";
    }

    /**
     * @param accepted   대기열에 들어갔는가
     * @param jobId      수정안이 붙은 작업(거절돼도 새로 만든 작업이 있으면 그 번호, 없으면 요청의 번호 또는 0)
     * @param proposalId 만든 수정안 번호(거절이면 0)
     * @param violations 거절 사유 — 작업 전체의 사유와 버린 편집 작업마다의 사유
     * @param reviewUrl  사람이 검토·승인할 화면
     */
    public record ProposeResult(boolean accepted, long jobId, long proposalId, List<String> violations, String reviewUrl) {}

    /** 작업 조회의 수정안 한 줄 — Diff 는 축약본. */
    public record ProposalSummary(long proposalId, String docId, String file, String chunkId, String kind, String status,
                                  String mdStatus, boolean edited, String diff) {}

    public record JobSummary(long jobId, String requestText, String status, String error, int total,
                             Map<String, Integer> byStatus, List<ProposalSummary> proposals, boolean truncated, String reviewUrl) {}

    private final AdminService adminService;
    private final ChangeJobRepository jobs;
    private final ChangeProposalRepository proposals;
    private AuditLogger auditLogger;

    @Autowired
    public ChangeProposalService(AdminService adminService, ChangeJobRepository jobs, ChangeProposalRepository proposals) {
        this.adminService = adminService;
        this.jobs = jobs;
        this.proposals = proposals;
    }

    @Autowired(required = false)
    public void setAuditLogger(AuditLogger auditLogger) {
        this.auditLogger = auditLogger;
    }

    /**
     * 청크 하나의 편집을 제안한다.
     *
     * @param ops              원문 인용 편집 작업({@link AnchoredEdit}) — {@code newText}·{@code deleteWholeChunk} 와 함께 쓰지 않는다
     * @param newText          바뀐 뒤 본문 전체(사람 편집과 같은 검사만 — 마커·펜스 불변식과 크기)
     * @param deleteWholeChunk 청크를 통째로 지우는 수정안
     * @param jobId            이어 붙일 외부 작업(요청자 본인의 것만), 없으면 새로 만든다
     */
    public ProposeResult proposeExternal(String userId, String chunkId, String baseHash, List<AnchoredEdit.Operation> ops,
                                         String newText, boolean deleteWholeChunk, String rationale, Long jobId) {
        List<String> violations = new ArrayList<>();
        long jid = jobId == null ? 0 : jobId;
        int forms = (ops != null && !ops.isEmpty() ? 1 : 0) + (newText != null && !newText.isBlank() ? 1 : 0) + (deleteWholeChunk ? 1 : 0);
        if (forms != 1) return reject(jid, "ops · newText · deleteWholeChunk 중 정확히 하나를 주세요");
        if (chunkId == null || chunkId.isBlank()) return reject(jid, "chunkId 가 필요합니다");
        if (baseHash == null || baseHash.isBlank()) return reject(jid, "baseHash 가 필요합니다(getChunkContext 의 contentHash)");
        String why = rationale == null ? "" : rationale.strip();
        if (why.isEmpty()) return reject(jid, "rationale(무엇을 왜 바꾸는지)이 필요합니다");
        if (why.length() > 500) why = why.substring(0, 500);

        if (jobId != null) {
            Optional<ChangeJobRepository.Job> job = jobs.find(jobId).filter(j -> ownExternalJob(j, userId));
            if (job.isEmpty()) return reject(jid, "이어 붙일 수 있는 외부 작업이 아닙니다: " + jobId);
            if (proposals.listByJob(jobId).size() >= MAX_PROPOSALS_PER_JOB) {
                return reject(jid, "작업당 수정안 상한(" + MAX_PROPOSALS_PER_JOB + "개)에 닿았습니다 — 새 작업으로 내세요");
            }
            boolean dup = proposals.listByJob(jobId).stream()
                    .anyMatch(p -> chunkId.equals(p.chunkId()) && ChangeProposalRepository.PENDING.equals(p.status()));
            if (dup) return reject(jid, "이 작업에 이 청크의 검토 대기 수정안이 이미 있습니다 — 관리자 화면에서 빼거나 고치세요");
        }

        String collection = adminService.collectionFor(MaintenanceWorkflow.VERSION);
        AdminService.ChunkRow row = adminService.getChunk(collection, chunkId);
        String version = row == null ? null : row.metadata().get(MetaKey.VERSION);
        boolean otherVersion = version != null && !version.isBlank() && !MaintenanceWorkflow.VERSION.equals(version);
        if (row == null || row.isCurated() || otherVersion) {
            // 없는 청크와 고칠 수 없는 청크(큐레이션·다른 버전)를 같은 말로 — 존재 여부를 흘리지 않는다(getChunkContext 와 같다).
            return reject(jid, "청크를 찾을 수 없습니다: " + chunkId);
        }
        String before = row.fullText() == null ? "" : row.fullText();
        if (!TextHash.sha256(before).equals(baseHash.strip())) {
            return reject(jid, "청크가 그사이 바뀌었습니다 — getChunkContext 로 다시 읽고 새 contentHash 로 내세요");
        }

        String kind;
        String after;
        String opsJson = null;
        if (deleteWholeChunk) {
            kind = ChangeProposalRepository.KIND_DELETE_CHUNK;
            after = "";
        } else if (newText != null && !newText.isBlank()) {
            kind = ChangeProposalRepository.KIND_EDIT;
            after = newText.replace("\r\n", "\n");
            if (after.length() > MAX_NEW_TEXT_CHARS) return reject(jid, "newText 가 너무 깁니다(" + MAX_NEW_TEXT_CHARS + "자 이하)");
            if (after.equals(before)) return reject(jid, "newText 가 지금 본문과 같습니다");
            String invariant = MaintenanceWorkflow.invariantViolation(before, after);
            if (invariant != null) return reject(jid, invariant + " — 그림·페이지 표지와 코드 펜스는 그대로 두세요");
        } else {
            kind = ChangeProposalRepository.KIND_EDIT;
            AnchoredEdit.Result r = AnchoredEdit.apply(before, ops);
            for (AnchoredEdit.Rejected x : r.rejected()) violations.add("작업 " + (x.index() + 1) + ": " + x.reason());
            if (r.problem() != null) violations.add(r.problem());
            if (!r.usable() || !r.rejected().isEmpty()) {
                // 일부만 통과한 제안은 받지 않는다 — 에이전트가 의도한 편집의 일부만 적용되면 뜻이 달라질 수 있다.
                if (violations.isEmpty()) violations.add("적용할 편집이 없습니다");
                return new ProposeResult(false, jid, 0, List.copyOf(violations), jid > 0 ? reviewUrl(jid) : null);
            }
            after = r.text();
            opsJson = MAPPER.writeValueAsString(r.applied());
        }

        if (jobId == null) {
            jid = jobs.createDone(ChangeJobRepository.KIND_MAINTENANCE, "MCP: " + firstLine(why), userId,
                    MAPPER.writeValueAsString(Map.of("mode", MODE_EXTERNAL)));
        }
        proposals.insertAll(List.of(new ChangeProposalRepository.NewProposal(jid, row.docId(), row.filename(), chunkId, kind,
                before, after, TextHash.sha256(before), null, null, null, null, why, opsJson, null, null, null)));
        long pid = proposals.listByJob(jid).stream().filter(p -> chunkId.equals(p.chunkId()))
                .mapToLong(ChangeProposalRepository.Proposal::id).max().orElse(0);
        audit(jid, pid, chunkId, row.docId(), kind, userId);
        log.info("[PROPOSE] 외부 수정안 job={} proposal={} chunk={} kind={}", jid, pid, chunkId, kind);
        return new ProposeResult(true, jid, pid, List.of(), reviewUrl(jid));
    }

    /** 유지보수 작업 하나의 상태와 수정안(축약 Diff) — 관리자용. 없거나 유지보수 작업이 아니면 비어 있다. */
    public Optional<JobSummary> jobSummary(long jobId, int maxProposals, int maxDiffChars) {
        Optional<ChangeJobRepository.Job> job = jobs.find(jobId).filter(j -> ChangeJobRepository.KIND_MAINTENANCE.equals(j.kind()));
        if (job.isEmpty()) return Optional.empty();
        List<ChangeProposalRepository.Proposal> all = proposals.listByJob(jobId);
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (ChangeProposalRepository.Proposal p : all) byStatus.merge(p.status(), 1, Integer::sum);
        List<ProposalSummary> rows = new ArrayList<>();
        for (ChangeProposalRepository.Proposal p : all.subList(0, Math.min(all.size(), maxProposals))) {
            String diff = UnifiedDiffFormatter.format(ChunkDiff.compare(p.beforeText(), p.afterText())).text();
            if (diff != null && diff.length() > maxDiffChars) diff = diff.substring(0, maxDiffChars) + "\n…";
            rows.add(new ProposalSummary(p.id(), p.docId(), p.filename(), p.chunkId(), p.kind(), p.status(), p.mdStatus(), p.edited(), diff));
        }
        ChangeJobRepository.Job j = job.get();
        return Optional.of(new JobSummary(j.id(), j.requestText(), j.status(), j.error(), all.size(), byStatus, List.copyOf(rows),
                all.size() > maxProposals, reviewUrl(j.id())));
    }

    private boolean ownExternalJob(ChangeJobRepository.Job j, String userId) {
        return ChangeJobRepository.KIND_MAINTENANCE.equals(j.kind()) && userId != null && userId.equals(j.requestedBy())
                && j.intentJson() != null && j.intentJson().contains("\"" + MODE_EXTERNAL + "\"");
    }

    private static ProposeResult reject(long jobId, String reason) {
        return new ProposeResult(false, jobId, 0, List.of(reason), jobId > 0 ? reviewUrl(jobId) : null);
    }

    private static String firstLine(String s) {
        String line = s.lines().findFirst().orElse("");
        return line.length() > 200 ? line.substring(0, 200) : line;
    }

    private void audit(long jobId, long proposalId, String chunkId, String docId, String kind, String userId) {
        if (auditLogger == null) return;
        try {
            auditLogger.log("change.propose", chunkId, Map.of("job", jobId, "proposal", proposalId, "doc", String.valueOf(docId),
                    "kind", kind, "by", String.valueOf(userId)));
        } catch (Exception e) {
            log.debug("[PROPOSE] 감사 기록 실패: {}", e.getMessage());
        }
    }
}
