package com.example.ragagent.controller;

import com.example.ragagent.repository.ChangeJobRepository;
import com.example.ragagent.repository.ChangeProposalRepository;
import com.example.ragagent.security.CurrentUser;
import com.example.ragagent.service.ChunkDiff;
import com.example.ragagent.workflow.ChangeApplyService;
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

    @GetMapping("/admin/change-jobs")
    public String list(@RequestParam(required = false) String error, Model model) {
        model.addAttribute("jobs", jobs.recentByKind(ChangeJobRepository.KIND_MAINTENANCE, 30));
        model.addAttribute("error", error);
        return "admin-change-jobs";
    }

    @PostMapping("/admin/change-requests")
    public String request(@RequestParam String instruction,
                          @RequestParam(required = false) String from,
                          @RequestParam(required = false) String to,
                          @RequestParam(required = false, defaultValue = "false") boolean semantic) {
        try {
            MaintenanceWorkflow.Started s = workflow.start(currentUser.userId(), instruction, from, to, semantic, LocaleContextHolder.getLocale());
            return "redirect:/admin/change-jobs/" + s.jobId();
        } catch (IllegalStateException busy) {
            return "redirect:/admin/change-jobs?error=busy";
        } catch (IllegalArgumentException bad) {
            return "redirect:/admin/change-jobs?error=invalid";
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
            UnifiedDiffFormatter.Formatted f = UnifiedDiffFormatter.format(ChunkDiff.compare(p.beforeText(), p.afterText()));
            byDoc.computeIfAbsent(p.docId(), k -> new ArrayList<>()).add(new ProposalView(p, f.text(), f.identical(), f.tooLarge()));
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
        model.addAttribute("report", parse(job));
        model.addAttribute("docs", docs);
        model.addAttribute("anyApplied", anyApplied);
    }

    private static long count(List<ProposalView> views, String status) {
        return views.stream().filter(v -> status.equals(v.proposal().status())).count();
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
