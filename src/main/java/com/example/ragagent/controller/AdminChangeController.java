package com.example.ragagent.controller;

import com.example.ragagent.repository.ChangeJobRepository;
import com.example.ragagent.repository.ChangeProposalRepository;
import com.example.ragagent.security.CurrentUser;
import com.example.ragagent.service.ChunkDiff;
import com.example.ragagent.workflow.ChangeApplyService;
import com.example.ragagent.workflow.EditIntent;
import com.example.ragagent.workflow.KnowledgeEditWorkflow;
import com.example.ragagent.workflow.MaintenanceWorkflow;
import com.example.ragagent.workflow.UnifiedDiffFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 문서 유지보수 요청과 수정안 검토·승인 화면(§6.33 4b). <b>전부 {@code /admin/**} 아래라 관리자만</b> 열린다(보안 체인이 {@code hasRole("ADMIN")} —
 * 이 컨트롤러가 따로 검사하지 않는다).
 *
 * <p>요청·승인·거절은 모두 {@code POST}(CSRF 필요)다. <b>승인은 문서 단위</b>이고 승인하면 곧바로 적용이 시작된다({@link ChangeApplyService} — 백업 →
 * 임베딩 먼저 청크 적용 → MD 되쓰기). 적용은 백그라운드라 화면은 승인된 수정안이 남아 있는 동안 본문 조각을 3초마다 다시 받는다.
 */
@Controller
public class AdminChangeController {

    private static final Logger log = LoggerFactory.getLogger(AdminChangeController.class);
    /** 저장된 리포트 blob 을 읽는다 — 리포트에 필드가 늘어도 이미 만들어진 작업의 옛 리포트가 읽혀야 한다(Jackson 3 는 빠진 기본형 필드를 오류로 본다). */
    private static final tools.jackson.databind.ObjectMapper MAPPER = com.example.ragagent.llm.LenientJson.MAPPER;

    /** 수정안 하나와 그 Diff(축약된 unified 텍스트). */
    public record ProposalView(ChangeProposalRepository.Proposal proposal, String diff, boolean identical, boolean tooLarge) {}

    /** 한 문서의 수정안 묶음 — 승인·거절의 단위. {@code pending} 이 하나라도 있어야 버튼이 보인다. */
    public record DocView(String docId, String filename, List<ProposalView> views, long pending, long approved, long applied,
                          long stale, long failed, String mdStatus) {}

    private final MaintenanceWorkflow workflow;
    private final ChangeApplyService applyService;
    private final ChangeJobRepository jobs;
    private final ChangeProposalRepository proposals;
    private final CurrentUser currentUser;

    public AdminChangeController(MaintenanceWorkflow workflow, ChangeApplyService applyService, ChangeJobRepository jobs,
                                 ChangeProposalRepository proposals, CurrentUser currentUser) {
        this.workflow = workflow;
        this.applyService = applyService;
        this.jobs = jobs;
        this.proposals = proposals;
        this.currentUser = currentUser;
    }

    /** 자연어 수정 워크플로(6c) — 세터 주입(슬라이스 테스트의 생성자를 흔들지 않게). 없으면 지시문만의 요청도 글자 치환으로 간다. */
    private KnowledgeEditWorkflow knowledgeEdit;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setKnowledgeEdit(KnowledgeEditWorkflow knowledgeEdit) {
        this.knowledgeEdit = knowledgeEdit;
    }

    @GetMapping("/admin/change-jobs")
    public String list(@RequestParam(required = false) String error,
                       @RequestParam(required = false) String from,
                       @RequestParam(required = false) String to, Model model) {
        model.addAttribute("jobs", jobs.recentByKind(ChangeJobRepository.KIND_MAINTENANCE, 30));
        model.addAttribute("error", error);
        // 채팅이 되물으며 건넨 링크 — 바꿀 말·새 말을 폼에 미리 채운다(옵션은 여기서 직접 켠다). 값은 템플릿이 이스케이프한다.
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        return "admin-change-jobs";
    }

    /**
     * 유지보수 요청. 갈래는 셋이다(AGENT_MCP_DESIGN §10.6):
     * <ul>
     *   <li>바꿀 말·새 말을 직접 주면(유형이 비었거나 {@code replace}) — 지금의 글자 치환 워크플로(옵션 둘 포함).</li>
     *   <li>유형을 직접 고르면({@code deprecate}·{@code remove}·{@code add_note}·{@code revise}) — 그 의도로 자연어 수정 워크플로(해석 단계 없음).</li>
     *   <li>지시문만 주면(유형 "자동") — 자연어 수정 워크플로가 LLM 으로 해석한다. 글자 바꾸기로 해석되면 글자 치환으로 넘어간다(옵션은 꺼진 채).</li>
     * </ul>
     */
    @PostMapping("/admin/change-requests")
    public String request(@RequestParam(required = false, defaultValue = "") String instruction,
                          @RequestParam(required = false) String from,
                          @RequestParam(required = false) String to,
                          @RequestParam(required = false, defaultValue = "false") boolean semantic,
                          @RequestParam(required = false, defaultValue = "false") boolean caseVariants,
                          @RequestParam(required = false, defaultValue = "") String type,
                          @RequestParam(required = false) String subject,
                          @RequestParam(required = false) String content,
                          @RequestParam(required = false) String replacement) {
        try {
            String t = type.strip();
            boolean literal = from != null && !from.isBlank() && to != null && !to.isBlank();
            long jobId;
            if (literal && (t.isEmpty() || EditIntent.REPLACE.equals(t))) {
                jobId = workflow.start(currentUser.userId(), instruction, from, to, semantic, caseVariants,
                        LocaleContextHolder.getLocale()).jobId();
            } else if (EditIntent.REPLACE.equals(t)) {
                return "redirect:/admin/change-jobs?error=invalid";   // 글자 바꾸기를 골랐는데 바꿀 말·새 말이 없다
            } else if (knowledgeEdit == null) {
                jobId = workflow.start(currentUser.userId(), instruction, from, to, semantic, caseVariants,
                        LocaleContextHolder.getLocale()).jobId();
            } else {
                EditIntent intent = t.isEmpty() ? null : new EditIntent(t, subject, content, replacement, null, null);
                jobId = knowledgeEdit.start(currentUser.userId(), instruction, intent, LocaleContextHolder.getLocale()).jobId();
            }
            return "redirect:/admin/change-jobs/" + jobId;
        } catch (IllegalStateException busy) {
            return "redirect:/admin/change-jobs?error=busy";
        } catch (IllegalArgumentException bad) {
            return "redirect:/admin/change-jobs?error=invalid";
        }
    }

    /**
     * 청크 오류 신고에서 수정안 만들기(6d, AGENT_MCP_DESIGN §10.12) — 신고 상세의 폼. 지시문은 신고 코멘트로 채워진 것을 관리자가 고친 것이고, 대상은 그
     * 청크 하나로 고정된다. 유형이 비면("자동") 지시문을 해석하고, {@code replace} 면 바꿀 말·새 말로 그 청크에만 글자 치환을 한다. 그 청크의 수정안이 적용되면
     * 신고가 해결로 닫힌다({@link ChangeApplyService}).
     */
    @PostMapping("/admin/chunk-reports/chunks/{chunkId}/propose")
    public String proposeFromReport(@PathVariable String chunkId,
                                    @RequestParam(required = false, defaultValue = "") String instruction,
                                    @RequestParam(required = false, defaultValue = "") String type,
                                    @RequestParam(required = false) String subject,
                                    @RequestParam(required = false) String content,
                                    @RequestParam(required = false) String replacement,
                                    @RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to) {
        if (knowledgeEdit == null) return "redirect:/admin/change-jobs?error=invalid";
        try {
            String t = type.strip();
            EditIntent intent = t.isEmpty() ? null
                    : EditIntent.REPLACE.equals(t) ? new EditIntent(t, null, null, null, from, to)
                    : new EditIntent(t, subject, content, replacement, null, null);
            long jobId = knowledgeEdit.startFromReport(currentUser.userId(), chunkId, instruction, intent,
                    LocaleContextHolder.getLocale()).jobId();
            return "redirect:/admin/change-jobs/" + jobId;
        } catch (IllegalStateException busy) {
            return "redirect:/admin/change-jobs?error=busy";
        } catch (IllegalArgumentException bad) {
            return "redirect:/admin/change-jobs?error=invalid";
        }
    }

    /** 자연어 수정 작업의 리포트에서 고른 청크(추가 적용 후보·언급만)로 수정안을 더한다. */
    @PostMapping("/admin/change-jobs/{id}/chunks/propose")
    public String proposeChunks(@PathVariable long id, @RequestParam(required = false) List<String> chunkIds) {
        requireMaintenanceJob(id);
        if (knowledgeEdit == null || chunkIds == null || chunkIds.isEmpty()) return "redirect:/admin/change-jobs/" + id;
        try {
            int n = knowledgeEdit.proposeForChunks(id, chunkIds, LocaleContextHolder.getLocale());
            return "redirect:/admin/change-jobs/" + id + (n == 0 ? "?notice=nothing" : "");
        } catch (IllegalArgumentException e) {
            return "redirect:/admin/change-jobs/" + id + "?notice=nothing";
        }
    }

    /** 한 문서의 수정안을 모두 승인하고 적용을 시작한다 — 승인 없이는 어떤 코퍼스 쓰기도 일어나지 않는다. */
    @PostMapping("/admin/change-jobs/{id}/docs/approve")
    public String approve(@PathVariable long id, @RequestParam String docId) {
        requireMaintenanceJob(id);
        int n = applyService.approveAndApply(id, docId, currentUser.userId());
        return "redirect:/admin/change-jobs/" + id + (n == 0 ? "?notice=nothing" : "");
    }

    /** 한 문서의 수정안을 모두 거절한다(코퍼스는 그대로). */
    @PostMapping("/admin/change-jobs/{id}/docs/reject")
    public String reject(@PathVariable long id, @RequestParam String docId) {
        requireMaintenanceJob(id);
        applyService.reject(id, docId, currentUser.userId());
        return "redirect:/admin/change-jobs/" + id;
    }

    /**
     * 수정안 본문을 관리자가 고친다(AGENT_MCP_DESIGN §10.7) — {@code pending} 인 동안만, 그림·페이지 마커와 코드 펜스 불변식만 다시 본다. 응답은 그 수정안의
     * 카드 조각이다. 거절되면 400 이 아니라 사유와 입력값을 담은 카드를 200 으로 돌려준다(400 은 화면에 토스트만 남고 입력이 사라진다).
     */
    @PostMapping("/admin/change-jobs/{id}/proposals/{pid}/edit")
    public String edit(@PathVariable long id, @PathVariable long pid, @RequestParam(defaultValue = "") String afterText, Model model) {
        ChangeJobRepository.Job job = requireMaintenanceJob(id);
        String error = applyService.editProposal(id, pid, afterText, currentUser.userId());
        ChangeProposalRepository.Proposal p = proposals.find(pid).filter(x -> x.jobId() == id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("job", job);
        model.addAttribute("v", view(p));
        if (error != null) {
            model.addAttribute("editError", error);
            model.addAttribute("editText", afterText);
        }
        return "fragments/change-proposal-card :: card";
    }

    /** 맥락 요약·키워드를 지금 본문으로 다시 만든다(LLM 1회) — 응답은 그 수정안의 카드. 실패하면 사유를 카드에 싣는다. */
    @PostMapping("/admin/change-jobs/{id}/proposals/{pid}/regenerate-context")
    public String regenerateContext(@PathVariable long id, @PathVariable long pid, Model model) {
        ChangeJobRepository.Job job = requireMaintenanceJob(id);
        String error = applyService.regenerateContext(id, pid, currentUser.userId());
        ChangeProposalRepository.Proposal p = proposals.find(pid).filter(x -> x.jobId() == id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("job", job);
        model.addAttribute("v", view(p));
        if (error != null) model.addAttribute("contextError", error);
        return "fragments/change-proposal-card :: card";
    }

    /** 수정안을 뺀다(겹침 짝 포함) — 문서의 대기 수가 바뀌므로 화면을 다시 읽게 한다. */
    @PostMapping("/admin/change-jobs/{id}/proposals/{pid}/withdraw")
    public org.springframework.http.ResponseEntity<Void> withdraw(@PathVariable long id, @PathVariable long pid) {
        requireMaintenanceJob(id);
        applyService.withdrawProposal(id, pid, currentUser.userId());
        return org.springframework.http.ResponseEntity.noContent().header("HX-Refresh", "true").build();
    }

    private static ProposalView view(ChangeProposalRepository.Proposal p) {
        UnifiedDiffFormatter.Formatted f = UnifiedDiffFormatter.format(ChunkDiff.compare(p.beforeText(), p.afterText()));
        return new ProposalView(p, f.text(), f.identical(), f.tooLarge());
    }

    @GetMapping("/admin/change-jobs/{id}")
    public String page(@PathVariable long id, Model model) {
        fill(id, model);
        return "admin-change-job";
    }

    @GetMapping("/admin/change-jobs/{id}/body")
    public String body(@PathVariable long id, Model model) {
        fill(id, model);
        return "admin-change-job :: body";
    }

    private ChangeJobRepository.Job requireMaintenanceJob(long id) {
        return jobs.find(id)
                .filter(j -> ChangeJobRepository.KIND_MAINTENANCE.equals(j.kind()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private void fill(long id, Model model) {
        ChangeJobRepository.Job job = requireMaintenanceJob(id);
        Map<String, List<ProposalView>> byDoc = new LinkedHashMap<>();
        for (ChangeProposalRepository.Proposal p : proposals.listByJob(id)) {
            byDoc.computeIfAbsent(p.docId(), k -> new ArrayList<>()).add(view(p));
        }
        List<DocView> docs = new ArrayList<>();
        boolean inProgress = false;
        boolean anyApplied = false;
        for (Map.Entry<String, List<ProposalView>> e : byDoc.entrySet()) {
            List<ProposalView> vs = e.getValue();
            long approved = count(vs, ChangeProposalRepository.APPROVED);
            long applied = count(vs, ChangeProposalRepository.APPLIED);
            inProgress |= approved > 0;
            anyApplied |= applied > 0;
            String md = vs.stream().map(v -> v.proposal().mdStatus()).filter(java.util.Objects::nonNull).findFirst().orElse(null);
            docs.add(new DocView(e.getKey(), vs.get(0).proposal().filename(), vs, count(vs, ChangeProposalRepository.PENDING),
                    approved, applied, count(vs, ChangeProposalRepository.STALE), count(vs, ChangeProposalRepository.FAILED), md));
        }
        model.addAttribute("job", job);
        model.addAttribute("running", ChangeJobRepository.RUNNING.equals(job.status()) || inProgress);
        KnowledgeEditWorkflow.Report editReport = parseEditReport(job);
        model.addAttribute("editReport", editReport);
        // 자연어 수정 작업의 리포트는 글자 치환 리포트 모양으로 읽으면 바꿀 말이 비어 "null → null" 로 그려진다 — 둘 중 하나만 싣는다.
        model.addAttribute("report", editReport == null ? parse(job) : null);
        java.util.Set<String> proposed = new java.util.HashSet<>();
        for (List<ProposalView> vs : byDoc.values()) for (ProposalView v : vs) proposed.add(v.proposal().chunkId());
        model.addAttribute("proposedChunks", proposed);
        model.addAttribute("docs", docs);
        model.addAttribute("anyApplied", anyApplied);
    }

    private static long count(List<ProposalView> views, String status) {
        return views.stream().filter(v -> status.equals(v.proposal().status())).count();
    }

    /** 자연어 수정 작업의 리포트 — 의도의 {@code mode} 가 {@code nl} 일 때만. */
    static KnowledgeEditWorkflow.Report parseEditReport(ChangeJobRepository.Job job) {
        if (job.intentJson() == null || !job.intentJson().contains("\"nl\"")) return null;
        if (job.summaryJson() == null || job.summaryJson().isBlank()) return null;
        try {
            return MAPPER.readValue(job.summaryJson(), KnowledgeEditWorkflow.Report.class);
        } catch (RuntimeException e) {
            log.warn("[KNOWLEDGE-EDIT] 리포트를 읽지 못했다 job={}: {}", job.id(), e.getMessage());
            return null;
        }
    }

    static MaintenanceWorkflow.Report parse(ChangeJobRepository.Job job) {
        if (job.summaryJson() == null || job.summaryJson().isBlank()) return null;
        try {
            return MAPPER.readValue(job.summaryJson(), MaintenanceWorkflow.Report.class);
        } catch (RuntimeException e) {
            log.warn("[MAINTENANCE] 리포트를 읽지 못했다 job={}: {}", job.id(), e.getMessage());
            return null;
        }
    }
}
