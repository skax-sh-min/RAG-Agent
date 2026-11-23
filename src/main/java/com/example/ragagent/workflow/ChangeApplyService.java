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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * 글자 치환은 변환 MD 에 반영됐지만 이 문서의 의미 변경 수정안은 청크에만 있다 — <b>옛 작업의 값으로만 남는다.</b> 6a(AGENT_MCP_DESIGN §10.8)
     * 부터 의미 변경은 줄 구간 대조로 MD 에 되쓰므로 새로 이 값을 쓰지 않는다(화면은 옛 행을 그리려고 문구를 유지한다).
     */
    public static final String MD_PARTIAL = "MD_PARTIAL";

    /** 관리자가 고친 본문의 길이 상한 — 청크 하나의 크기를 크게 넘는 붙여넣기를 막는다. */
    static final int MAX_EDITED_CHARS = 20_000;

    /**
     * 한 문서의 적용 결과. {@code error} 가 있으면 아무것도 적용하지 않았다(백업 실패·잠금 충돌 등).
     *
     * @param reportsClosed 신고에서 시작한 작업(6d)이 그 청크의 수정안을 적용해 해결로 닫은 신고 수 — 그 밖에는 0
     */
    public record DocResult(String docId, int applied, int stale, int failed, String mdStatus, String error, int reportsClosed) {
        static DocResult error(String docId, String message) {
            return new DocResult(docId, 0, 0, 0, null, message, 0);
        }
    }

    private final ChangeProposalRepository proposals;
    private final ChangeJobRepository jobs;
    private final AdminService adminService;
    private final DatabaseBackupService backups;
    private final AppProperties props;
    /** 문서 단위 쓰기 잠금 — 같은 문서의 {@code ↺ 재인덱싱}({@code DocumentIndexer}) 과 공유한다. */
    private final DocumentWriteLocks writeLocks;
    /** 감사 로그 — 세터 주입(생성자가 둘이고 테스트가 직접 부른다). 없으면 기록하지 않는다. */
    private com.example.ragagent.audit.AuditLogger auditLogger;

    /**
     * 청크 오류 신고 — 신고에서 시작한 작업(6d)의 그 청크 수정안이 적용되면 열린 신고를 해결로 닫는다. 세터 주입(생성자가 둘이고 테스트가 직접 부른다).
     * 없으면 닫지 않는다(관리자가 신고 화면에서 직접 닫는다).
     */
    private com.example.ragagent.service.ChunkReportService chunkReports;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setChunkReports(com.example.ragagent.service.ChunkReportService chunkReports) {
        this.chunkReports = chunkReports;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAuditLogger(com.example.ragagent.audit.AuditLogger auditLogger) {
        this.auditLogger = auditLogger;
    }

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
        // R10(AGENT_MCP_DESIGN §10.15) — 문서 단위 승인은 원문을 건드리지 않는 요청의 "넣기만" 수정안에만 열린다. 그 밖의 작업과 수정안은 검토
        // 화면에서 하나씩 확인한다. 이미 승인된(앞선 적용이 잠금·백업으로 미룬) 수정안의 다시 시도는 그대로 된다.
        ChangeJobRepository.Job job = jobs.find(jobId).orElse(null);
        boolean leftoverBefore = !proposals.listByJobDocStatus(jobId, docId, ChangeProposalRepository.APPROVED).isEmpty();
        boolean hasPending = !proposals.listByJobDocStatus(jobId, docId, ChangeProposalRepository.PENDING).isEmpty();
        if (hasPending && !ReviewRules.batchAllowed(job) && !leftoverBefore) throw new BatchNotAllowedException();
        int approved = 0;
        if (ReviewRules.batchAllowed(job)) {
            for (ChangeProposalRepository.Proposal p : proposals.listByJobDocStatus(jobId, docId, ChangeProposalRepository.PENDING)) {
                if (ReviewRules.insertOnly(p) && p.groupKey() == null
                        && proposals.transition(p.id(), ChangeProposalRepository.PENDING, ChangeProposalRepository.APPROVED, reviewer)) {
                    approved++;
                }
            }
        }
        boolean leftover = !proposals.listByJobDocStatus(jobId, docId, ChangeProposalRepository.APPROVED).isEmpty();
        if (approved == 0 && !leftover) return 0;
        auditDocument("change.approve", jobId, docId, reviewer, approved);
        Thread.ofVirtual().name("change-apply-" + jobId).start(MdcPropagation.wrap(() -> applyApproved(jobId, docId, reviewer)));
        return approved;
    }

    /** 한 문서의 {@code pending} 수정안을 모두 거절한다(코퍼스는 그대로). */
    public int reject(long jobId, String docId, String reviewer) {
        int n = proposals.transitionDocument(jobId, docId, ChangeProposalRepository.PENDING, ChangeProposalRepository.REJECTED, reviewer);
        if (n > 0) auditDocument("change.reject", jobId, docId, reviewer, n);
        return n;
    }

    private void auditDocument(String action, long jobId, String docId, String reviewer, int count) {
        if (auditLogger == null) return;
        try {
            auditLogger.log(action, docId, Map.of("job", jobId, "reviewer", String.valueOf(reviewer), "count", count));
        } catch (Exception e) {
            log.debug("[APPLY] 감사 기록 실패: {}", e.getMessage());
        }
    }

    /** 승인된 수정안을 적용한다(동기) — {@link #approveAndApply} 의 몸체이자 테스트의 입구. 어떤 예외도 던지지 않고 결과에 담는다. */
    DocResult applyApproved(long jobId, String docId, String reviewer) {
        // 같은 문서의 다른 쓰기 작업(수정안 적용·재인덱싱)과 겹치지 않는다 — 기다리지 않고 거절하며 수정안은 승인 상태로 남는다(다시 시도할 수 있다).
        DocumentWriteLocks.Lease lease = writeLocks.tryAcquire(docId);
        if (lease == null) return DocResult.error(docId, "이 문서에 다른 쓰기 작업(수정안 적용 또는 재인덱싱)이 진행 중입니다");
        try {
            List<ChangeProposalRepository.Proposal> todo = proposals.listByJobDocStatus(jobId, docId, ChangeProposalRepository.APPROVED);
            if (todo.isEmpty()) return DocResult.error(docId, "적용할 승인된 수정안이 없습니다");
            ChangeJobRepository.Job job = jobs.find(jobId).orElse(null);
            // 신고에서 시작한 작업(6d) — 그 청크의 수정안이 적용되면 신고를 닫는다.
            String reportChunk = job != null && job.fromReport() ? job.reportChunkId() : null;
            MaintenanceWorkflow.Report report = report(job);
            // 수정안을 만든 때와 같은 치환 규칙(대소문자 변형 포함 여부까지) — 제목·남은 말 검사·발생 수 대조 MD 되쓰기가 이것을 쓴다.
            // 치환 규칙이 없는 작업(자연어 수정·외부 제안)도 있으므로 없을 수 있다.
            TermReplacement rule = report == null || report.from() == null || report.to() == null ? null
                    : new TermReplacement(report.from(), report.to(), report.caseVariants());
            // 발생 수 대조는 "기계가 낸 글자 치환"에만 맞다. 의미 변경·앵커 편집·관리자가 고친 것·청크 삭제가 하나라도 있으면 줄 구간 대조로 되쓴다.
            // 신고 작업의 글자 치환은 청크 하나만 바꾸므로 "문서의 어느 청크에도 바꿀 말이 안 남음"을 전제하는 발생 수 대조가 맞지 않는다 — 줄 구간 대조로.
            // 그 문서에 이미 검토 화면에서 하나씩 적용된 수정안이 있으면 발생 수 대조의 전제("문서의 모든 청크가 이번에 끝난다")가 깨진다 — 줄 구간 대조로(R9).
            boolean literalOnly = reportChunk == null
                    && proposals.listByJobDocStatus(jobId, docId, ChangeProposalRepository.APPLIED).isEmpty()
                    && todo.stream().allMatch(p -> ChangeProposalRepository.KIND_LITERAL.equals(p.kind()) && !p.edited());
            if (literalOnly && rule == null) return DocResult.error(docId, "작업 리포트를 읽지 못했습니다");

            String backupError = ensureJobBackup(jobId);
            if (backupError != null) {
                // 복구 지점이 없는 채로 쓰지 않는다 — 수정안은 승인 상태로 남아 다시 시도할 수 있다.
                log.warn("[APPLY] DB 백업 실패 — 적용하지 않는다 job={} doc={}: {}", jobId, docId, backupError);
                return DocResult.error(docId, "DB 백업에 실패해 적용하지 않았습니다: " + backupError);
            }

            String collection = adminService.collectionFor(MaintenanceWorkflow.VERSION);
            // 겹침 짝은 쓰기 전에 해시를 먼저 대조한다 — 한쪽만 적용되면 겹침 구간이 어긋나므로 하나라도 낡았으면 짝 전체를 쓰지 않는다.
            Set<String> staleGroups = staleGroups(collection, todo);
            int applied = 0, stale = 0, failed = 0, appliedOccurrences = 0;
            boolean reportedChunkApplied = false;
            List<MarkdownWriteBack.Edit> appliedEdits = new ArrayList<>();
            List<Long> appliedIds = new ArrayList<>();
            for (ChangeProposalRepository.Proposal p : todo) {
                AdminService.ApplyOutcome outcome;
                if (p.groupKey() != null && staleGroups.contains(p.groupKey())) {
                    outcome = AdminService.ApplyOutcome.STALE;
                } else {
                    outcome = applyOne(collection, p, rule, p.afterContext(), p.afterKeywords());
                }
                switch (outcome) {
                    case APPLIED -> {
                        proposals.finishApply(p.id(), ChangeProposalRepository.APPLIED, null);
                        appliedIds.add(p.id());
                        applied++;
                        if (p.chunkId().equals(reportChunk)) reportedChunkApplied = true;
                        if (rule != null) appliedOccurrences += rule.count(p.beforeText());
                        appliedEdits.add(new MarkdownWriteBack.Edit(p.beforeText(), p.afterText()));
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
                audit("change.apply", p, Map.of("result", outcome.name(), "reviewer", String.valueOf(reviewer)));
            }

            String md = null;
            if (applied > 0) {
                Path convertedDir = Path.of(props.dataDir()).resolve("converted");
                MarkdownWriteBack.Result r;
                if (literalOnly) {
                    boolean clean = adminService.getAllChunks(collection, docId).stream()
                            .noneMatch(c -> c.fullText() != null && rule.matches(c.fullText()));
                    r = MarkdownWriteBack.apply(convertedDir, docId, rule, appliedOccurrences, clean);
                } else {
                    r = MarkdownWriteBack.applyEdits(convertedDir, docId, appliedEdits);
                }
                md = r.status();
                // 이번에 적용한 수정안에만 — 앞서 검토 화면에서 하나씩 적용된 수정안의 MD 결과를 덮지 않는다.
                for (Long id : appliedIds) proposals.setMdStatusFor(id, md);
                log.info("[APPLY] 완료 job={} doc={} 적용={} stale={} 실패={} md={} ({})", jobId, docId, applied, stale, failed, md, r.detail());
            } else {
                log.info("[APPLY] 적용된 청크 없음 job={} doc={} stale={} 실패={}", jobId, docId, stale, failed);
            }
            int closed = reportedChunkApplied ? closeReports(jobId, reportChunk, reviewer) : 0;
            return new DocResult(docId, applied, stale, failed, md, null, closed);
        } catch (Exception e) {
            log.warn("[APPLY] 실패 job={} doc={}: {}", jobId, docId, e.getMessage());
            return DocResult.error(docId, String.valueOf(e.getMessage()));
        } finally {
            lease.close();
        }
    }

    /**
     * 신고에서 시작한 작업의 그 청크가 적용됐다 — 열린 신고를 해결로 닫는다(6d). 신고 처리 실패는 적용 결과를 바꾸지 않는다(청크는 이미 고쳐졌고, 신고는
     * 관리자가 화면에서 직접 닫을 수 있다).
     */
    private int closeReports(long jobId, String chunkId, String reviewer) {
        if (chunkReports == null) return 0;
        try {
            int n = chunkReports.resolveChunk(chunkId, reviewer, "수정 작업 #" + jobId + " 적용으로 해결");
            if (n > 0) log.info("[APPLY] 신고 해결 job={} chunk={} 닫음={}", jobId, chunkId, n);
            return n;
        } catch (Exception e) {
            log.warn("[APPLY] 신고를 닫지 못했다 job={} chunk={}: {}", jobId, chunkId, e.getMessage());
            return 0;
        }
    }

    /** 수정안 하나를 쓴다 — 청크 삭제는 {@link AdminService#applyChunkDelete}, 나머지는 {@link AdminService#applyChunkEdit}. 예외는 실패로 바꾼다. */
    private AdminService.ApplyOutcome applyOne(String collection, ChangeProposalRepository.Proposal p, TermReplacement rule,
                                               String context, String keywords) {
        try {
            if (ChangeProposalRepository.KIND_DELETE_CHUNK.equals(p.kind())) {
                return adminService.applyChunkDelete(collection, p.chunkId(), p.beforeHash());
            }
            Map<String, String> metaChanges = new HashMap<>();
            if (context != null) metaChanges.put(MetaKey.CHUNK_CONTEXT, context);
            if (keywords != null) metaChanges.put(MetaKey.EXCERPT_KEYWORDS, keywords);
            // 섹션 제목에도 같은 치환 — 본문에서 바꾼 말이 제목에 있으면 제목만 옛 이름으로 남는다(종단 확인에서 드러난 것).
            if (rule != null) {
                String heading = currentHeading(collection, p.chunkId());
                if (heading != null && rule.matches(heading)) {
                    metaChanges.put(MetaKey.HEADING, rule.apply(heading));
                }
            }
            return adminService.applyChunkEdit(collection, p.chunkId(), p.afterText(), metaChanges, p.beforeHash());
        } catch (Exception e) {
            log.warn("[APPLY] 청크 적용 중 예외 chunk={}: {}", p.chunkId(), e.getMessage());
            return AdminService.ApplyOutcome.EMBED_FAILED;
        }
    }

    /** 겹침 짝 중 하나라도 현재 본문이 수정안의 수정 전과 다른(또는 없는) 짝 — 그 짝은 전부 쓰지 않는다. */
    private Set<String> staleGroups(String collection, List<ChangeProposalRepository.Proposal> todo) {
        Set<String> out = new HashSet<>();
        for (ChangeProposalRepository.Proposal p : todo) {
            if (p.groupKey() == null || out.contains(p.groupKey())) continue;
            AdminService.ChunkRow row;
            try {
                row = adminService.getChunk(collection, p.chunkId());
            } catch (Exception e) {
                row = null;
            }
            if (row == null || !com.example.ragagent.model.TextHash.sha256(row.fullText()).equals(p.beforeHash())) out.add(p.groupKey());
        }
        return out;
    }

    /**
     * 관리자가 수정안 본문을 고친다(AGENT_MCP_DESIGN §10.7, G9) — {@code pending} 인 동안만. 사람의 판단을 모델용 가드로 막지 않고, 그림·페이지 마커와
     * 코드 펜스 불변식(G2·G6)만 다시 본다. 청크 삭제 수정안은 고칠 본문이 없다(빼거나 승인한다).
     *
     * @return 거절 사유, 통과해 저장했으면 {@code null}
     */
    public String editProposal(long jobId, long proposalId, String newAfterText, String editor) {
        ChangeProposalRepository.Proposal p = proposals.find(proposalId).filter(x -> x.jobId() == jobId).orElse(null);
        if (p == null) return "수정안을 찾지 못했습니다";
        if (!ChangeProposalRepository.PENDING.equals(p.status())) return "검토 대기 중인 수정안만 고칠 수 있습니다";
        if (ChangeProposalRepository.KIND_DELETE_CHUNK.equals(p.kind())) return "청크 삭제 수정안은 고칠 본문이 없습니다 — 빼거나 승인하세요";
        String text = newAfterText == null ? "" : newAfterText.replace("\r\n", "\n");
        if (text.isBlank()) return "본문이 비었습니다 — 청크를 지우려면 내용 삭제를 요청하세요";
        if (text.length() > MAX_EDITED_CHARS) return "본문이 너무 깁니다(" + MAX_EDITED_CHARS + "자 이하)";
        if (text.equals(p.beforeText())) return "수정 전과 같습니다 — 이 청크를 바꾸지 않으려면 '이 청크 빼기'를 누르세요";
        String invariant = MaintenanceWorkflow.invariantViolation(p.beforeText(), text);
        if (invariant != null) return invariant + " — 그림·페이지 마커와 코드 펜스는 그대로 두어야 합니다";
        if (!proposals.editAfterText(proposalId, text, editor)) return "검토 대기 중인 수정안만 고칠 수 있습니다";
        audit("change.edit", p, Map.of("editor", String.valueOf(editor)));
        return null;
    }

    /**
     * 수정안의 맥락 요약·키워드를 지금의 바뀐 뒤 본문으로 다시 만든다(관리자가 고른 때만 — AGENT_MCP_DESIGN §10.7). 인덱싱과 같은
     * {@code KeywordExtractor} 호출 한 번이고, 결과는 수정안에만 저장된다(적용 때 청크에 실린다). {@code pending} 인 동안만, 청크 삭제 수정안은 대상이 아니다.
     *
     * @return 거절 사유, 저장했으면 {@code null}
     */
    public String regenerateContext(long jobId, long proposalId, String editor) {
        ChangeProposalRepository.Proposal p = proposals.find(proposalId).filter(x -> x.jobId() == jobId).orElse(null);
        if (p == null) return "수정안을 찾지 못했습니다";
        if (!ChangeProposalRepository.PENDING.equals(p.status())) return "검토 대기 중인 수정안만 다시 만들 수 있습니다";
        if (ChangeProposalRepository.KIND_DELETE_CHUNK.equals(p.kind())) return "청크 삭제 수정안에는 맥락이 없습니다";
        if (keywordExtractor == null) return "맥락을 다시 만들 수 없습니다(키워드 추출기가 없음)";
        AdminService.ChunkRow row = adminService.getChunk(adminService.collectionFor(MaintenanceWorkflow.VERSION), p.chunkId());
        if (row == null) return "청크를 찾지 못했습니다 — 수정안이 낡았을 수 있습니다";
        Map<String, Object> meta = new HashMap<>(row.metadata());
        org.springframework.ai.document.Document enriched;
        try {
            enriched = keywordExtractor.enrichSingle(new org.springframework.ai.document.Document(p.afterText(), meta));
        } catch (Exception e) {
            log.warn("[APPLY] 맥락 재생성 실패 proposal={}: {}", proposalId, e.getMessage());
            return "맥락을 다시 만들지 못했습니다";
        }
        Object ctx = enriched.getMetadata().get(MetaKey.CHUNK_CONTEXT);
        Object kw = enriched.getMetadata().get(MetaKey.EXCERPT_KEYWORDS);
        String beforeCtx = p.beforeContext() != null ? p.beforeContext() : row.metadata().get(MetaKey.CHUNK_CONTEXT);
        String beforeKw = p.beforeKeywords() != null ? p.beforeKeywords() : row.metadata().get(MetaKey.EXCERPT_KEYWORDS);
        if (!proposals.updateContext(proposalId, beforeCtx, ctx == null ? null : ctx.toString(), beforeKw, kw == null ? null : kw.toString())) {
            return "검토 대기 중인 수정안만 다시 만들 수 있습니다";
        }
        audit("change.regenerate-context", p, Map.of("editor", String.valueOf(editor)));
        return null;
    }

    /** 맥락·키워드 재생성 — 세터 주입(생성자가 둘이고 테스트가 직접 부른다). */
    private com.example.ragagent.ingestion.KeywordExtractor keywordExtractor;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setKeywordExtractor(com.example.ragagent.ingestion.KeywordExtractor keywordExtractor) {
        this.keywordExtractor = keywordExtractor;
    }

    /** 관리자가 수정안을 뺀다(겹침 짝 포함). @return 뺀 수 — 0 이면 이미 처리됐거나 없는 수정안 */
    public int withdrawProposal(long jobId, long proposalId, String reviewer) {
        ChangeProposalRepository.Proposal p = proposals.find(proposalId).filter(x -> x.jobId() == jobId).orElse(null);
        if (p == null) return 0;
        int n = proposals.withdraw(proposalId, reviewer);
        if (n > 0) audit("change.withdraw", p, Map.of("reviewer", String.valueOf(reviewer), "count", n));
        return n;
    }

    // ── 검토 화면(6e, AGENT_MCP_DESIGN §10.15) ─────────────────────────────

    /** R10 — 이 작업(또는 수정안)은 하나씩 확인해야 한다. 컨트롤러가 409 로 바꾼다. */
    public static final class BatchNotAllowedException extends RuntimeException {
        public BatchNotAllowedException() {
            super("이 요청은 원문을 바꾸는 요소가 있어 하나씩 확인해야 합니다");
        }
    }

    /** 동시 편집 — 화면이 연 뒤 다른 관리자가 수정안을 고쳤거나 처리했다. 컨트롤러가 409 로 바꾼다. */
    public static final class ProposalConflictException extends RuntimeException {
        public ProposalConflictException() {
            super("다른 관리자가 이 수정안을 고쳤거나 처리했습니다 — 다시 엽니다");
        }
    }

    /** 테스트용 — 순차 실행기를 가상 스레드가 아니라 호출한 스레드에서 돌린다(확인이 돌아올 때 적용이 끝나 있다). */
    private boolean inlineDrain;

    void runDrainInline() {
        this.inlineDrain = true;
    }

    /** 순차 실행기가 돌고 있는 작업 — 작업마다 하나(R8). */
    private final Set<Long> draining = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 적용이 미뤄졌거나 경고가 붙은 수정안의 사유(잠금 충돌·백업 실패·맥락 옛 값) — 화면 표시용이라 메모리에만 둔다(재기동하면 사라진다;
     * 상태 자체는 DB 의 {@code approved}/{@code applied} 가 말한다).
     */
    private final Map<Long, String> notes = new java.util.concurrent.ConcurrentHashMap<>();

    /** 검토 화면이 항목에 보일 사유 — 없으면 {@code null}. */
    public String noteFor(long proposalId) {
        return notes.get(proposalId);
    }

    /** 수정안이 가리키는 청크의 지금 모습 — 검토 화면의 낡음 판정·섹션 제목. 읽지 못하면 {@code null}. */
    public AdminService.ChunkRow currentChunk(String chunkId) {
        try {
            return adminService.getChunk(adminService.collectionFor(MaintenanceWorkflow.VERSION), chunkId);
        } catch (Exception e) {
            return null;
        }
    }

    /** 이 작업의 순차 실행기가 지금 돌고 있는가 — 목록 폴링과 [다시 시도] 버튼이 읽는다. */
    public boolean draining(long jobId) {
        return draining.contains(jobId);
    }

    /**
     * R2 — 작업당 백업 하나. 이 작업의 백업이 남아 있으면 다시 만들지 않고, 없거나(첫 쓰기) 보존 정리로 지워졌으면 새로 만들어 이름을 남긴다.
     *
     * @return 실패 사유, 쓸 백업이 있으면 {@code null}
     */
    String ensureJobBackup(long jobId) {
        String name = jobs.backupName(jobId);
        if (name != null) {
            Path f = backups.backupFile(name);
            if (f != null && java.nio.file.Files.isRegularFile(f)) return null;
        }
        DatabaseBackupService.RunResult r = backups.run("pre-change-" + jobId);
        if (!r.ok()) return r.error();
        if (r.file() != null) jobs.setBackupName(jobId, r.file());
        return null;
    }

    /** 확인·임시 저장의 공통 검사 — 거절 사유 또는 {@code null}. 사람 편집은 G2·G6 만 강제한다(R7). */
    private static String reviewTextError(ChangeProposalRepository.Proposal p, String text) {
        if (text.isBlank()) return "본문이 비었습니다 — 청크를 지우려면 내용 삭제를 요청하세요";
        if (text.length() > MAX_EDITED_CHARS) return "본문이 너무 깁니다(" + MAX_EDITED_CHARS + "자 이하)";
        if (text.equals(p.beforeText())) return "수정 전과 같습니다 — 이 청크를 바꾸지 않으려면 '빼기'를 누르세요";
        String invariant = MaintenanceWorkflow.invariantViolation(p.beforeText(), text);
        if (invariant != null) return invariant + " — 그림·페이지 마커와 코드 펜스는 그대로 두어야 합니다";
        return null;
    }

    /** 검토 화면이 연 수정안 — {@code pending} 이고 화면이 받은 본문의 해시가 지금과 같아야 한다. */
    private ChangeProposalRepository.Proposal openForReview(long jobId, long proposalId, String expectedHash) {
        ChangeProposalRepository.Proposal p = proposals.find(proposalId).filter(x -> x.jobId() == jobId).orElse(null);
        if (p == null || !ChangeProposalRepository.PENDING.equals(p.status())) throw new ProposalConflictException();
        if (expectedHash != null && !expectedHash.equals(com.example.ragagent.model.TextHash.sha256(p.afterText()))) {
            throw new ProposalConflictException();
        }
        return p;
    }

    /** 고친 본문을 저장한다(고쳤을 때만) — 거절 사유 또는 {@code null}. */
    private String saveEdit(ChangeProposalRepository.Proposal p, String text, String editor) {
        if (text.equals(p.afterText())) return null;
        if (ChangeProposalRepository.KIND_DELETE_CHUNK.equals(p.kind())) return "청크 삭제 수정안은 고칠 본문이 없습니다 — 빼거나 확인하세요";
        if (p.groupKey() != null) return "겹침 짝은 고칠 수 없습니다 — 확인하거나 빼세요";
        String error = reviewTextError(p, text);
        if (error != null) return error;
        if (!proposals.editAfterTextIfUnchanged(p.id(), p.afterText(), text, editor)) throw new ProposalConflictException();
        audit("change.edit", p, Map.of("editor", String.valueOf(editor)));
        return null;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replace("\r\n", "\n");
    }

    /**
     * 검토 화면의 임시 저장 — 항목을 옮길 때 고친 내용을 남긴다(상태는 {@code pending}, 배지 "편집함").
     *
     * @return 거절 사유, 저장했거나 바뀐 것이 없으면 {@code null}
     * @throws ProposalConflictException 다른 관리자가 그사이 고쳤거나 처리했다
     */
    public String saveDraft(long jobId, long proposalId, String text, String expectedHash, String editor) {
        ChangeProposalRepository.Proposal p = openForReview(jobId, proposalId, expectedHash);
        return saveEdit(p, normalize(text), editor);
    }

    /** 기계 수정안으로 되돌린다. @return 되돌렸으면 {@code true} */
    public boolean restoreMachine(long jobId, long proposalId) {
        ChangeProposalRepository.Proposal p = proposals.find(proposalId).filter(x -> x.jobId() == jobId).orElse(null);
        return p != null && proposals.restoreMachineText(proposalId);
    }

    /**
     * 확인 = 그 수정안 하나를 승인하고 곧바로 적용 줄에 넣는다(R1). 고쳤으면 먼저 저장하고(처음 기계 수정안은 {@code machine_after_text} 에 남는다),
     * 겹침 짝이면 짝 전체를 승인한다. 적용은 작업별 순차 실행기가 확인한 순서대로 한다(R8) — 이 메서드는 기다리지 않는다.
     *
     * @return 거절 사유(상태는 그대로), 승인했으면 {@code null}
     * @throws ProposalConflictException 다른 관리자가 그사이 고쳤거나 처리했다
     */
    public String confirmOne(long jobId, long proposalId, String text, String expectedHash, String reviewer) {
        ChangeProposalRepository.Proposal p = openForReview(jobId, proposalId, expectedHash);
        if (!ChangeProposalRepository.KIND_DELETE_CHUNK.equals(p.kind())) {
            String error = saveEdit(p, normalize(text), reviewer);
            if (error != null) return error;
        }
        int n = p.groupKey() == null
                ? (proposals.transition(proposalId, ChangeProposalRepository.PENDING, ChangeProposalRepository.APPROVED, reviewer) ? 1 : 0)
                : proposals.transitionGroup(jobId, p.groupKey(), ChangeProposalRepository.PENDING, ChangeProposalRepository.APPROVED, reviewer);
        if (n == 0) throw new ProposalConflictException();
        notes.remove(proposalId);
        audit("change.approve", p, Map.of("reviewer", String.valueOf(reviewer), "count", n, "via", "review"));
        enqueue(jobId);
        return null;
    }

    /**
     * 일괄 적용(R10) — 작업이 일괄 적용을 받을 때만, 대기 중인 "넣기만" 수정안을(문서 필터가 있으면 그 문서만) 한 번에 승인해 순차 실행기에 넣는다.
     * 그사이 누가 고쳤거나 뺀 것은 CAS 가 건너뛴다.
     *
     * @return 승인한 수
     * @throws BatchNotAllowedException 이 작업은 하나씩 확인해야 한다
     */
    public int approveBatch(long jobId, String docFilter, String reviewer) {
        ChangeJobRepository.Job job = jobs.find(jobId).orElse(null);
        if (!ReviewRules.batchAllowed(job)) throw new BatchNotAllowedException();
        int n = 0;
        Set<String> docs = new HashSet<>();
        for (ChangeProposalRepository.Proposal p : batchTargets(jobId, docFilter)) {
            if (proposals.transition(p.id(), ChangeProposalRepository.PENDING, ChangeProposalRepository.APPROVED, reviewer)) {
                n++;
                docs.add(p.docId());
            }
        }
        if (n > 0 && auditLogger != null) {
            try {
                auditLogger.log("change.approve", "job-" + jobId,
                        Map.of("job", jobId, "reviewer", String.valueOf(reviewer), "count", n, "docs", docs.size(), "via", "batch"));
            } catch (Exception e) {
                log.debug("[APPLY] 감사 기록 실패: {}", e.getMessage());
            }
        }
        if (n > 0) enqueue(jobId);
        return n;
    }

    /** 일괄 적용 대상 — 대기 중인 "넣기만" 수정안(겹침 짝 제외 — 짝은 하나씩 확인한다). 작업이 일괄 적용을 받는지는 보지 않는다. */
    public List<ChangeProposalRepository.Proposal> batchTargets(long jobId, String docFilter) {
        List<ChangeProposalRepository.Proposal> out = new ArrayList<>();
        for (ChangeProposalRepository.Proposal p : proposals.listByJobStatus(jobId, ChangeProposalRepository.PENDING)) {
            if (docFilter != null && !docFilter.isBlank() && !docFilter.equals(p.docId())) continue;
            if (p.groupKey() == null && ReviewRules.insertOnly(p)) out.add(p);
        }
        return out;
    }

    /** 남은 승인 수정안을 다시 적용한다(잠금 충돌·백업 실패로 미뤄진 것) — 실행기가 이미 돌고 있으면 아무것도 하지 않는다. */
    public void retry(long jobId) {
        enqueue(jobId);
    }

    /** 작업의 순차 실행기를 띄운다 — 돌고 있지 않을 때만(R8). */
    void enqueue(long jobId) {
        if (!draining.add(jobId)) return;
        if (inlineDrain) {
            drain(jobId);
            return;
        }
        Thread.ofVirtual().name("change-review-" + jobId).start(MdcPropagation.wrap(() -> drain(jobId)));
    }

    /**
     * 작업의 {@code approved} 수정안을 확인 시각 순으로 하나씩 적용한다(테스트는 이것을 동기로 부른다). 잠금·백업 때문에 이번에 적용하지 못한 것은
     * 승인 상태로 두고 건너뛴다(다시 시도). 끝내기 직전에 들어온 확인을 놓치지 않으려고 실행기 표시를 지운 뒤 한 번 더 본다.
     */
    void drain(long jobId) {
        Set<Long> skipped = new HashSet<>();
        try {
            while (true) {
                while (true) {
                    ChangeProposalRepository.Proposal next = proposals.listByJobStatus(jobId, ChangeProposalRepository.APPROVED).stream()
                            .filter(p -> !skipped.contains(p.id())).findFirst().orElse(null);
                    if (next == null) break;
                    if (!applyReviewed(jobId, next)) skipped.add(next.id());
                }
                draining.remove(jobId);
                boolean more = proposals.listByJobStatus(jobId, ChangeProposalRepository.APPROVED).stream()
                        .anyMatch(p -> !skipped.contains(p.id()));
                if (!more || !draining.add(jobId)) return;
            }
        } catch (Exception e) {
            log.warn("[APPLY] 검토 적용 실행기 실패 job={}: {}", jobId, e.getMessage());
            draining.remove(jobId);
        }
    }

    /**
     * 검토 화면에서 확인한 수정안 하나(겹침 짝이면 짝 전체)를 적용한다. 맥락·키워드는 잠금 밖에서 먼저 만들고(R3·R4), 문서 잠금 → 작업 백업(R2) →
     * 청크 쓰기 → 그 청크의 줄 구간 MD 되쓰기(R9).
     *
     * @return 처리했으면({@code applied}/{@code stale}/{@code failed} 로 끝났으면) {@code true}, 잠금·백업 때문에 승인 상태로 남겼으면 {@code false}
     */
    boolean applyReviewed(long jobId, ChangeProposalRepository.Proposal first) {
        List<ChangeProposalRepository.Proposal> todo = first.groupKey() == null ? List.of(first)
                : proposals.listByJobStatus(jobId, ChangeProposalRepository.APPROVED).stream()
                        .filter(p -> first.groupKey().equals(p.groupKey())).toList();
        String collection = adminService.collectionFor(MaintenanceWorkflow.VERSION);

        // 맥락·키워드 — 잠금 밖(LLM 은 느릴 수 있다).
        Map<Long, String[]> contexts = new HashMap<>();
        for (ChangeProposalRepository.Proposal p : todo) contexts.put(p.id(), contextFor(collection, p));

        DocumentWriteLocks.Lease lease = writeLocks.tryAcquire(first.docId());
        if (lease == null) {
            notes.put(first.id(), "이 문서에 다른 쓰기 작업(재인덱싱 등)이 진행 중이라 적용을 미뤘습니다 — 다시 시도하세요");
            return false;
        }
        try {
            String backupError = ensureJobBackup(jobId);
            if (backupError != null) {
                notes.put(first.id(), "DB 백업에 실패해 적용하지 않았습니다: " + backupError);
                return false;
            }
            ChangeJobRepository.Job job = jobs.find(jobId).orElse(null);
            String reportChunk = job != null && job.fromReport() ? job.reportChunkId() : null;
            MaintenanceWorkflow.Report report = report(job);
            TermReplacement rule = report == null || report.from() == null || report.to() == null ? null
                    : new TermReplacement(report.from(), report.to(), report.caseVariants());
            Set<String> staleGroups = staleGroups(collection, todo);
            List<MarkdownWriteBack.Edit> edits = new ArrayList<>();
            List<Long> appliedIds = new ArrayList<>();
            boolean reportedChunkApplied = false;
            for (ChangeProposalRepository.Proposal p : todo) {
                String[] ctx = contexts.get(p.id());
                AdminService.ApplyOutcome outcome = p.groupKey() != null && staleGroups.contains(p.groupKey())
                        ? AdminService.ApplyOutcome.STALE
                        : applyOne(collection, p, rule, ctx[0], ctx[1]);
                switch (outcome) {
                    case APPLIED -> {
                        if (ctx[0] != null || ctx[1] != null) proposals.setAppliedContext(p.id(), ctx[0], ctx[1]);
                        proposals.finishApply(p.id(), ChangeProposalRepository.APPLIED, null);
                        appliedIds.add(p.id());
                        edits.add(new MarkdownWriteBack.Edit(p.beforeText(), p.afterText()));
                        if (p.chunkId().equals(reportChunk)) reportedChunkApplied = true;
                    }
                    case STALE, NOT_FOUND, CURATED -> proposals.finishApply(p.id(), ChangeProposalRepository.STALE, null);
                    case EMBED_FAILED -> proposals.finishApply(p.id(), ChangeProposalRepository.FAILED, null);
                }
                audit("change.apply", p, Map.of("result", outcome.name(), "via", "review"));
            }
            if (!edits.isEmpty()) {
                MarkdownWriteBack.Result r = MarkdownWriteBack.applyEdits(
                        Path.of(props.dataDir()).resolve("converted"), first.docId(), edits);
                for (Long id : appliedIds) proposals.setMdStatusFor(id, r.status());
                log.info("[APPLY] 검토 적용 job={} proposal={} 적용={} md={} ({})", jobId, first.id(), appliedIds.size(), r.status(), r.detail());
            }
            if (reportedChunkApplied) closeReports(jobId, reportChunk, first.reviewedBy());
            return true;
        } catch (Exception e) {
            log.warn("[APPLY] 검토 적용 실패 job={} proposal={}: {}", jobId, first.id(), e.getMessage());
            notes.put(first.id(), "적용 중 오류: " + e.getMessage());
            return false;
        } finally {
            lease.close();
        }
    }

    /**
     * 적용할 맥락·키워드 {@code [context, keywords]} — R3: 사람이 본문을 고치지 않았고 수정안을 만들 때 만든 값이 있으면 그것을 쓴다(LLM 호출 없음).
     * 아니면 확정 본문으로 한 번 다시 만든다. R4: 다시 만들지 못하면 {@code [null, null]} — 청크의 옛 맥락이 그대로 남고 항목에 경고가 붙는다.
     * 청크 삭제 수정안은 맥락이 없다.
     */
    private String[] contextFor(String collection, ChangeProposalRepository.Proposal p) {
        if (ChangeProposalRepository.KIND_DELETE_CHUNK.equals(p.kind())) return new String[] {null, null};
        if (!p.edited() && (p.afterContext() != null || p.afterKeywords() != null)) {
            return new String[] {p.afterContext(), p.afterKeywords()};
        }
        if (keywordExtractor == null) {
            notes.put(p.id(), "맥락·키워드를 다시 만들지 못해 옛 값으로 적용했습니다 — /admin 청크 화면에서 재인덱싱(키워드 재생성)으로 고치세요");
            return new String[] {null, null};
        }
        try {
            AdminService.ChunkRow row = adminService.getChunk(collection, p.chunkId());
            Map<String, Object> meta = row == null ? new HashMap<>() : new HashMap<>(row.metadata());
            org.springframework.ai.document.Document enriched =
                    keywordExtractor.enrichSingle(new org.springframework.ai.document.Document(p.afterText(), meta));
            Object ctx = enriched.getMetadata().get(MetaKey.CHUNK_CONTEXT);
            Object kw = enriched.getMetadata().get(MetaKey.EXCERPT_KEYWORDS);
            audit("change.regenerate-context", p, Map.of("via", "review"));
            return new String[] {ctx == null ? null : ctx.toString(), kw == null ? null : kw.toString()};
        } catch (Exception e) {
            log.warn("[APPLY] 맥락 재생성 실패 — 옛 맥락으로 적용 proposal={}: {}", p.id(), e.getMessage());
            notes.put(p.id(), "맥락·키워드를 다시 만들지 못해 옛 값으로 적용했습니다 — /admin 청크 화면에서 재인덱싱(키워드 재생성)으로 고치세요");
            return new String[] {null, null};
        }
    }

    /** 감사 기록 — 본문은 남기지 않는다(before/after 는 {@code change_proposal} 에 있다). 기록 실패는 적용을 막지 않는다. */
    private void audit(String action, ChangeProposalRepository.Proposal p, Map<String, Object> extra) {
        if (auditLogger == null) return;
        try {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("job", p.jobId());
            d.put("proposal", p.id());
            d.put("doc", p.docId());
            d.put("kind", p.kind());
            d.put("edited", p.edited() || "change.edit".equals(action));
            d.putAll(extra);
            auditLogger.log(action, p.chunkId(), d);
        } catch (Exception e) {
            log.debug("[APPLY] 감사 기록 실패: {}", e.getMessage());
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

    private static MaintenanceWorkflow.Report report(ChangeJobRepository.Job j) {
        if (j == null || j.summaryJson() == null) return null;
        try {
            return MAPPER.readValue(j.summaryJson(), MaintenanceWorkflow.Report.class);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
