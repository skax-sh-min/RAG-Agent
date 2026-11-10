package com.example.ragagent.controller;

import com.example.ragagent.repository.ChangeJobRepository;
import com.example.ragagent.repository.ChangeProposalRepository;
import com.example.ragagent.security.CurrentUser;
import com.example.ragagent.service.ChunkDiff;
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
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 문서 유지보수 요청과 수정안 검토 화면(§6.33 4b-1). <b>전부 {@code /admin/**} 아래라 관리자만</b> 열린다(보안 체인이 {@code hasRole("ADMIN")} —
 * 이 컨트롤러가 따로 검사하지 않는다). 이 조각의 화면은 <b>읽기·요청뿐</b>이다: 수정안을 만들고 Diff 로 보여 줄 뿐 승인·적용 버튼은 없다 —
 * 코퍼스를 바꾸는 단계는 다음 조각이다.
 *
 * <p>요청은 {@code POST}(CSRF 필요)이고 성공하면 작업 화면으로 넘어간다. 진행 중이면 본문 조각을 3초마다 다시 받는다.
 */
@Controller
public class AdminChangeController {

    private static final Logger log = LoggerFactory.getLogger(AdminChangeController.class);
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    /** 수정안 하나와 그 Diff(축약된 unified 텍스트). */
    public record ProposalView(ChangeProposalRepository.Proposal proposal, String diff, boolean identical, boolean tooLarge) {}

    private final MaintenanceWorkflow workflow;
    private final ChangeJobRepository jobs;
    private final ChangeProposalRepository proposals;
    private final CurrentUser currentUser;

    public AdminChangeController(MaintenanceWorkflow workflow, ChangeJobRepository jobs,
                                 ChangeProposalRepository proposals, CurrentUser currentUser) {
        this.workflow = workflow;
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
                          @RequestParam(required = false) String to) {
        try {
            MaintenanceWorkflow.Started s = workflow.start(currentUser.userId(), instruction, from, to, LocaleContextHolder.getLocale());
            return "redirect:/admin/change-jobs/" + s.jobId();
        } catch (IllegalStateException busy) {
            return "redirect:/admin/change-jobs?error=busy";
        } catch (IllegalArgumentException bad) {
            return "redirect:/admin/change-jobs?error=invalid";
        }
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

    private void fill(long id, Model model) {
        ChangeJobRepository.Job job = jobs.find(id)
                .filter(j -> ChangeJobRepository.KIND_MAINTENANCE.equals(j.kind()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<ProposalView> views = new ArrayList<>();
        for (ChangeProposalRepository.Proposal p : proposals.listByJob(id)) {
            UnifiedDiffFormatter.Formatted f = UnifiedDiffFormatter.format(ChunkDiff.compare(p.beforeText(), p.afterText()));
            views.add(new ProposalView(p, f.text(), f.identical(), f.tooLarge()));
        }
        model.addAttribute("job", job);
        model.addAttribute("running", ChangeJobRepository.RUNNING.equals(job.status()));
        model.addAttribute("report", parse(job));
        model.addAttribute("views", views);
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
