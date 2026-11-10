package com.example.ragagent.workflow;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.ingestion.DocumentWriteLocks;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.repository.ChangeJobRepository;
import com.example.ragagent.repository.ChangeProposalRepository;
import com.example.ragagent.service.AdminService;
import com.example.ragagent.service.DatabaseBackupService;
import com.example.ragagent.web.MdcPropagation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 승인된 수정안을 <b>문서 단위로</b> 코퍼스에 적용한다(§6.33 4b-2) — 이 앱에서 유지보수 워크플로가 청크·벡터·FTS·변환 MD 를 쓰는 <b>유일한</b> 자리다.
 *
 * <p><b>문서 단위인 이유.</b> 일부 청크만 고치면 이웃 청크와 겹침 구간이 달라져 {@code ChunkReassembler} 의 재조립에 중복이 생기고, 변환 MD 되쓰기도
 * "이 문서의 어느 청크에도 바꿀 말이 남지 않았다"를 전제로 한다. 그래서 승인·거절·적용 모두 한 문서의 수정안 전부를 한 번에 다룬다. 코퍼스 전체
 * 일괄 승인은 만들지 않는다.
 *
 * <p><b>순서(각 단계가 앞 단계의 실패에 안전하다).</b> ① 문서 잠금(같은 문서의 적용이 겹치지 않는다) → ② <b>DB 백업</b>(실패하면 아무것도 적용하지
 * 않고 승인 상태로 남긴다) → ③ 청크마다 {@link AdminService#applyChunkEdit}(해시 대조 → 임베딩 먼저 → 저장; 결과마다 {@code applied}/{@code stale}/
 * {@code failed}) → ④ 적용된 것이 있을 때만 {@link MarkdownWriteBack}(문서의 어느 청크에도 바꿀 말이 안 남았을 때만). 승인 없이는 어느 단계도 오지
 * 않는다 — {@link #approveAndApply}/{@link #applyApproved} 는 {@code approved} 상태의 수정안만 본다.
 *
 * <p>알려진 한계: 이 서비스의 잠금은 같은 서비스끼리만 겹침을 막는다 — 같은 문서의 {@code ↺ 재인덱싱}과 동시에 돌면 막지 못한다(재인덱싱은 청크 id 를
 * 바꾸므로 대기 중인 수정안은 {@code stale} 이 된다). 원본 파일(DOCX/PDF)은 고치지 않는다 — 다시 올리면 수정은 사라진다.
 */
@Service
public class ChangeApplyService {

    private static final Logger log = LoggerFactory.getLogger(ChangeApplyService.class);
    /** 저장된 리포트 blob 을 읽는다 — 리포트에 필드가 늘어도 이미 만들어진 작업의 옛 리포트가 읽혀야 한다(Jackson 3 는 빠진 기본형 필드를 오류로 본다). */
    private static final tools.jackson.databind.ObjectMapper MAPPER = com.example.ragagent.llm.LenientJson.MAPPER;

    /** 글자 치환은 변환 MD 에 반영됐지만 이 문서의 의미 변경 수정안은 청크에만 있다(MD 에 되쓸 수 없다). */
    public static final String MD_PARTIAL = "MD_PARTIAL";

    /** 한 문서의 적용 결과. {@code error} 가 있으면 아무것도 적용하지 않았다(백업 실패·잠금 충돌 등). */
    public record DocResult(String docId, int applied, int stale, int failed, String mdStatus, String error) {
        static DocResult error(String docId, String message) {
            return new DocResult(docId, 0, 0, 0, null, message);
        }
    }

    private final ChangeProposalRepository proposals;
    private final ChangeJobRepository jobs;
    private final AdminService adminService;
    private final DatabaseBackupService backups;
    private final AppProperties props;
    /** 문서 단위 쓰기 잠금 — 같은 문서의 {@code ↺ 재인덱싱}({@code DocumentIndexer}) 과 공유한다. */
    private final DocumentWriteLocks writeLocks;

    @org.springframework.beans.factory.annotation.Autowired
    public ChangeApplyService(ChangeProposalRepository proposals, ChangeJobRepository jobs, AdminService adminService,
                              DatabaseBackupService backups, AppProperties props, DocumentWriteLocks writeLocks) {
        this.proposals = proposals;
        this.jobs = jobs;
        this.adminService = adminService;
        this.backups = backups;
        this.props = props;
        this.writeLocks = writeLocks;
    }

    /** 재인덱싱과 잠금을 공유하지 않는 인스턴스 — 테스트용. */
    public ChangeApplyService(ChangeProposalRepository proposals, ChangeJobRepository jobs, AdminService adminService,
                              DatabaseBackupService backups, AppProperties props) {
        this(proposals, jobs, adminService, backups, props, new DocumentWriteLocks());
    }

    /**
     * 한 문서의 {@code pending} 수정안을 모두 승인하고 가상 스레드에서 적용한다.
     *
     * @return 승인된 수정안 수 — 0 이면 승인할 것이 없다(이미 처리됐거나 없는 문서)
     */
    public int approveAndApply(long jobId, String docId, String reviewer) {
        int approved = proposals.transitionDocument(jobId, docId, ChangeProposalRepository.PENDING, ChangeProposalRepository.APPROVED, reviewer);
        boolean leftover = !proposals.listByJobDocStatus(jobId, docId, ChangeProposalRepository.APPROVED).isEmpty();
        if (approved == 0 && !leftover) return 0;
        Thread.ofVirtual().name("change-apply-" + jobId).start(MdcPropagation.wrap(() -> applyApproved(jobId, docId, reviewer)));
        return approved;
    }

    /** 한 문서의 {@code pending} 수정안을 모두 거절한다(코퍼스는 그대로). */
    public int reject(long jobId, String docId, String reviewer) {
        return proposals.transitionDocument(jobId, docId, ChangeProposalRepository.PENDING, ChangeProposalRepository.REJECTED, reviewer);
    }

    /** 승인된 수정안을 적용한다(동기) — {@link #approveAndApply} 의 몸체이자 테스트의 입구. 어떤 예외도 던지지 않고 결과에 담는다. */
    DocResult applyApproved(long jobId, String docId, String reviewer) {
        // 같은 문서의 다른 쓰기 작업(수정안 적용·재인덱싱)과 겹치지 않는다 — 기다리지 않고 거절하며 수정안은 승인 상태로 남는다(다시 시도할 수 있다).
        DocumentWriteLocks.Lease lease = writeLocks.tryAcquire(docId);
        if (lease == null) return DocResult.error(docId, "이 문서에 다른 쓰기 작업(수정안 적용 또는 재인덱싱)이 진행 중입니다");
        try {
            List<ChangeProposalRepository.Proposal> todo = proposals.listByJobDocStatus(jobId, docId, ChangeProposalRepository.APPROVED);
            if (todo.isEmpty()) return DocResult.error(docId, "적용할 승인된 수정안이 없습니다");
            MaintenanceWorkflow.Report report = report(jobId);
            if (report == null) return DocResult.error(docId, "작업 리포트를 읽지 못했습니다");
            // 수정안을 만든 때와 같은 규칙(대소문자 변형 포함 여부까지) — 제목·남은 말 검사·MD 되쓰기가 모두 이것을 쓴다.
            TermReplacement rule = new TermReplacement(report.from(), report.to(), report.caseVariants());

            DatabaseBackupService.RunResult backup = backups.run("pre-change-" + jobId);
            if (!backup.ok()) {
                // 복구 지점이 없는 채로 쓰지 않는다 — 수정안은 승인 상태로 남아 다시 시도할 수 있다.
                log.warn("[APPLY] DB 백업 실패 — 적용하지 않는다 job={} doc={}: {}", jobId, docId, backup.error());
                return DocResult.error(docId, "DB 백업에 실패해 적용하지 않았습니다: " + backup.error());
            }

            String collection = adminService.collectionFor(MaintenanceWorkflow.VERSION);
            int applied = 0, stale = 0, failed = 0, appliedOccurrences = 0;
            for (ChangeProposalRepository.Proposal p : todo) {
                Map<String, String> metaChanges = new HashMap<>();
                if (p.afterContext() != null) metaChanges.put(MetaKey.CHUNK_CONTEXT, p.afterContext());
                if (p.afterKeywords() != null) metaChanges.put(MetaKey.EXCERPT_KEYWORDS, p.afterKeywords());
                // 섹션 제목에도 같은 치환 — 본문에서 바꾼 말이 제목에 있으면 제목만 옛 이름으로 남는다(종단 확인에서 드러난 것).
                String heading = currentHeading(collection, p.chunkId());
                if (heading != null && rule.matches(heading)) {
                    metaChanges.put(MetaKey.HEADING, rule.apply(heading));
                }
                AdminService.ApplyOutcome outcome;
                try {
                    outcome = adminService.applyChunkEdit(collection, p.chunkId(), p.afterText(), metaChanges, p.beforeHash());
                } catch (Exception e) {
                    log.warn("[APPLY] 청크 적용 중 예외 chunk={}: {}", p.chunkId(), e.getMessage());
                    outcome = AdminService.ApplyOutcome.EMBED_FAILED;
                }
                switch (outcome) {
                    case APPLIED -> {
                        proposals.finishApply(p.id(), ChangeProposalRepository.APPLIED, null);
                        applied++;
                        appliedOccurrences += rule.count(p.beforeText());
                    }
                    case STALE, NOT_FOUND, CURATED -> {
                        proposals.finishApply(p.id(), ChangeProposalRepository.STALE, null);
                        stale++;
                    }
                    case EMBED_FAILED -> {
                        proposals.finishApply(p.id(), ChangeProposalRepository.FAILED, null);
                        failed++;
                    }
                }
            }

            String md = null;
            if (applied > 0) {
                boolean clean = adminService.getAllChunks(collection, docId).stream()
                        .noneMatch(c -> c.fullText() != null && rule.matches(c.fullText()));
                MarkdownWriteBack.Result r = MarkdownWriteBack.apply(Path.of(props.dataDir()).resolve("converted"),
                        docId, rule, appliedOccurrences, clean);
                md = r.status();
                // 의미 변경은 변환 MD 에 되쓸 수 없다 — 글자 치환만 MD 에 들어갔으니, 그 문서의 의미 변경은 재인덱싱하면 되돌아간다.
                boolean anySemanticApplied = todo.stream().anyMatch(p -> ChangeProposalRepository.KIND_SEMANTIC.equals(p.kind())
                        && proposals.find(p.id()).map(x -> ChangeProposalRepository.APPLIED.equals(x.status())).orElse(false));
                if (anySemanticApplied && MarkdownWriteBack.APPLIED.equals(md)) md = MD_PARTIAL;
                proposals.setMdStatus(jobId, docId, md);
                log.info("[APPLY] 완료 job={} doc={} 적용={} stale={} 실패={} md={} ({})", jobId, docId, applied, stale, failed, md, r.detail());
            } else {
                log.info("[APPLY] 적용된 청크 없음 job={} doc={} stale={} 실패={}", jobId, docId, stale, failed);
            }
            return new DocResult(docId, applied, stale, failed, md, null);
        } catch (Exception e) {
            log.warn("[APPLY] 실패 job={} doc={}: {}", jobId, docId, e.getMessage());
            return DocResult.error(docId, String.valueOf(e.getMessage()));
        } finally {
            lease.close();
        }
    }

    /** 청크의 현재 섹션 제목 — 읽지 못하면 {@code null}(그 메타데이터는 건드리지 않는다). */
    private String currentHeading(String collection, String chunkId) {
        try {
            AdminService.ChunkRow row = adminService.getChunk(collection, chunkId);
            return row == null ? null : row.metadata().get(MetaKey.HEADING);
        } catch (Exception e) {
            return null;
        }
    }

    private MaintenanceWorkflow.Report report(long jobId) {
        return jobs.find(jobId).map(j -> {
            try {
                return j.summaryJson() == null ? null : MAPPER.readValue(j.summaryJson(), MaintenanceWorkflow.Report.class);
            } catch (RuntimeException e) {
                return null;
            }
        }).orElse(null);
    }
}
