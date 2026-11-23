package com.example.ragagent.controller;

import com.example.ragagent.model.MetaKey;
import com.example.ragagent.model.TextHash;
import com.example.ragagent.repository.ChangeJobRepository;
import com.example.ragagent.repository.ChangeProposalRepository;
import com.example.ragagent.security.CurrentUser;
import com.example.ragagent.service.AdminService;
import com.example.ragagent.workflow.ChangeApplyService;
import com.example.ragagent.workflow.ReviewDiff;
import com.example.ragagent.workflow.ReviewRules;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
 * 수정안 검토 화면(6e, AGENT_MCP_DESIGN §10.15) — 목록 1 : 수정 전 3 : 수정 후 3. 수정안을 하나씩 고르고, 두 칸의 바뀐 곳을 배경색으로 보고, 수정 후
 * 칸을 그 자리에서 고치고, [확인] 으로 <b>그 청크 하나</b>를 적용한다. 안내만 넣는 자연어 수정 작업({@code deprecate}·{@code add_note})은 "넣기만"
 * 수정안을 [일괄 적용] 할 수 있다(R10 — 서버가 다시 판정해 409).
 *
 * <p>전부 {@code /admin/**} 아래라 관리자만 열린다(보안 체인 — 이 컨트롤러가 따로 검사하지 않는다). {@link AdminChangeController} 와 따로 둔 이유는
 * 생성자에 협력자를 얹으면 슬라이스 테스트가 흔들리기 때문이다({@code EmbeddingRebuildController} 선례).
 */
@Controller
public class AdminChangeReviewController {

    /** 사유 평문 — 문자셋을 밝히지 않으면 ISO-8859-1 로 나가 한국어가 깨진다. */
    private static final MediaType TEXT_UTF8 = new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8);

    /** 목록 필터 — 기본은 대기(편집함 포함). */
    static final String FILTER_OPEN = "open";

    /** 목록의 한 줄. {@code state} 는 화면 상태 키({@link #state}), {@code typeKey} 는 유형 배지의 메시지 키. */
    public record Row(ChangeProposalRepository.Proposal proposal, String state, String typeKey, boolean needsDirect) {}

    /**
     * 열린 항목.
     *
     * @param stale     청크가 수정안을 만든 뒤 바뀌었다 — 확인할 수 없다
     * @param afterHash 화면이 받은 수정 후 본문의 해시 — 확인·임시 저장에 함께 보내 동시 편집을 가린다
     * @param heading   청크의 섹션 제목(읽지 못하면 {@code null})
     * @param note      적용이 미뤄졌거나 경고가 붙은 사유(메모리)
     */
    public record Item(Row row, ReviewDiff.Panes panes, boolean stale, String afterHash, String heading, String note,
                       int groupSize) {}

    private final ChangeJobRepository jobs;
    private final ChangeProposalRepository proposals;
    private final ChangeApplyService applyService;
    private final CurrentUser currentUser;

    public AdminChangeReviewController(ChangeJobRepository jobs, ChangeProposalRepository proposals, ChangeApplyService applyService,
                                       CurrentUser currentUser) {
        this.jobs = jobs;
        this.proposals = proposals;
        this.applyService = applyService;
        this.currentUser = currentUser;
    }

    @ExceptionHandler(ChangeApplyService.ProposalConflictException.class)
    public ResponseEntity<String> conflict(ChangeApplyService.ProposalConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).contentType(TEXT_UTF8).body(e.getMessage());
    }

    @ExceptionHandler(ChangeApplyService.BatchNotAllowedException.class)
    public ResponseEntity<String> batchNotAllowed(ChangeApplyService.BatchNotAllowedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).contentType(TEXT_UTF8).body(e.getMessage());
    }

    // ── 화면 ────────────────────────────────────────────────────────────────

    @GetMapping("/admin/change-jobs/{id}/review")
    public String page(@PathVariable long id, @RequestParam(required = false) Long p,
                       @RequestParam(required = false, defaultValue = FILTER_OPEN) String status,
                       @RequestParam(required = false) String doc, Model model) {
        ChangeJobRepository.Job job = requireJob(id);
        fillList(job, status, doc, model);
        Long open = p != null ? p : firstPending(id, doc);
        fillItem(job, open, model);
        model.addAttribute("currentPid", open);
        return "admin-change-review";
    }

    @GetMapping("/admin/change-jobs/{id}/review/list")
    public String list(@PathVariable long id, @RequestParam(required = false, defaultValue = FILTER_OPEN) String status,
                       @RequestParam(required = false) String doc, @RequestParam(required = false) Long p, Model model) {
        ChangeJobRepository.Job job = requireJob(id);
        fillList(job, status, doc, model);
        model.addAttribute("currentPid", p);
        return "admin-change-review :: list";
    }

    @GetMapping("/admin/change-jobs/{id}/review/{pid}")
    public String item(@PathVariable long id, @PathVariable long pid, Model model, HttpServletResponse response) {
        ChangeJobRepository.Job job = requireJob(id);
        fillItem(job, pid, model);
        response.setHeader("HX-Push-Url", "/admin/change-jobs/" + id + "/review?p=" + pid);
        return "admin-change-review :: item";
    }

    /** 수정 후 칸을 고치는 동안의 강조 갱신 — 아무것도 저장하지 않는다. 수정 전 칸과 강조 층(oob)을 돌려준다. */
    @PostMapping("/admin/change-jobs/{id}/review/{pid}/diff")
    public String diff(@PathVariable long id, @PathVariable long pid, @RequestParam(defaultValue = "") String text, Model model) {
        requireJob(id);
        ChangeProposalRepository.Proposal p = requireProposal(id, pid);
        String t = text.length() > 40_000 ? text.substring(0, 40_000) : text;
        model.addAttribute("panes", ReviewDiff.of(p.beforeText(), t));
        return "admin-change-review :: diff";
    }

    /** 임시 저장 — 항목을 옮길 때 고친 내용을 남긴다. 성공은 204, 거절은 422 + 사유(평문), 동시 편집은 409. */
    @PostMapping("/admin/change-jobs/{id}/review/{pid}/draft")
    public ResponseEntity<String> draft(@PathVariable long id, @PathVariable long pid, @RequestParam(defaultValue = "") String text,
                                        @RequestParam(required = false) String expectedHash) {
        requireJob(id);
        String error = applyService.saveDraft(id, pid, text, expectedHash, currentUser.userId());
        if (error != null) {
            return ResponseEntity.unprocessableEntity().contentType(TEXT_UTF8).body(error);
        }
        return ResponseEntity.noContent().header("HX-Trigger", "review-list-changed").build();
    }

    /**
     * 확인 — 그 수정안 하나를 승인하고 적용 줄에 넣는다. 응답은 다음 대기 항목(없으면 요약). 검사에 걸리면 같은 항목을 사유와 입력값을 담아 돌려준다
     * (400 은 토스트만 남고 입력이 사라진다 — {@code ProviderCatalog} 선례).
     */
    @PostMapping("/admin/change-jobs/{id}/review/{pid}/confirm")
    public String confirm(@PathVariable long id, @PathVariable long pid, @RequestParam(defaultValue = "") String text,
                          @RequestParam(required = false) String expectedHash, @RequestParam(required = false) String doc,
                          Model model, HttpServletResponse response) {
        ChangeJobRepository.Job job = requireJob(id);
        String error = applyService.confirmOne(id, pid, text, expectedHash, currentUser.userId());
        if (error != null) {
            fillItem(job, pid, model);
            model.addAttribute("itemError", error);
            model.addAttribute("itemText", text);
            return "admin-change-review :: item";
        }
        return next(job, doc, model, response);
    }

    /** 빼기 — 이 화면에서 부른 것. 응답은 다음 대기 항목. */
    @PostMapping("/admin/change-jobs/{id}/review/{pid}/withdraw")
    public String withdraw(@PathVariable long id, @PathVariable long pid, @RequestParam(required = false) String doc,
                           Model model, HttpServletResponse response) {
        ChangeJobRepository.Job job = requireJob(id);
        applyService.withdrawProposal(id, pid, currentUser.userId());
        return next(job, doc, model, response);
    }

    /** 기계 수정안으로 되돌린다 — 응답은 같은 항목. */
    @PostMapping("/admin/change-jobs/{id}/review/{pid}/restore")
    public String restore(@PathVariable long id, @PathVariable long pid, Model model, HttpServletResponse response) {
        ChangeJobRepository.Job job = requireJob(id);
        applyService.restoreMachine(id, pid);
        fillItem(job, pid, model);
        response.setHeader("HX-Trigger", "review-list-changed");
        return "admin-change-review :: item";
    }

    /** 임베딩 실패로 끝난 수정안을 다시 적용 줄에 넣는다 — 응답은 같은 항목(적용 중). */
    @PostMapping("/admin/change-jobs/{id}/review/{pid}/reapply")
    public String reapply(@PathVariable long id, @PathVariable long pid, Model model, HttpServletResponse response) {
        ChangeJobRepository.Job job = requireJob(id);
        applyService.reapplyFailed(id, pid, currentUser.userId());
        fillItem(job, pid, model);
        response.setHeader("HX-Trigger", "review-list-changed");
        return "admin-change-review :: item";
    }

    /** 일괄 적용(R10) — 응답은 목록. 작업이 하나씩 확인해야 하면 409. */
    @PostMapping("/admin/change-jobs/{id}/review/batch")
    public String batch(@PathVariable long id, @RequestParam(required = false) String doc, Model model, HttpServletResponse response) {
        ChangeJobRepository.Job job = requireJob(id);
        applyService.approveBatch(id, blankToNull(doc), currentUser.userId());
        fillList(job, FILTER_OPEN, doc, model);
        response.setHeader("HX-Trigger", "review-item-changed");
        return "admin-change-review :: list";
    }

    /** 미뤄진(잠금·백업) 승인 수정안을 다시 적용한다 — 응답은 목록. */
    @PostMapping("/admin/change-jobs/{id}/review/retry")
    public String retry(@PathVariable long id, @RequestParam(required = false, defaultValue = FILTER_OPEN) String status,
                        @RequestParam(required = false) String doc, Model model) {
        ChangeJobRepository.Job job = requireJob(id);
        applyService.retry(id);
        fillList(job, status, doc, model);
        return "admin-change-review :: list";
    }

    // ── 모델 ────────────────────────────────────────────────────────────────

    private String next(ChangeJobRepository.Job job, String doc, Model model, HttpServletResponse response) {
        Long next = firstPending(job.id(), blankToNull(doc));
        fillItem(job, next, model);
        response.setHeader("HX-Trigger", "review-list-changed");
        response.setHeader("HX-Push-Url", "/admin/change-jobs/" + job.id() + "/review" + (next == null ? "" : "?p=" + next));
        return "admin-change-review :: item";
    }

    private Long firstPending(long jobId, String doc) {
        return proposals.listByJob(jobId).stream()
                .filter(p -> ChangeProposalRepository.PENDING.equals(p.status()))
                .filter(p -> doc == null || doc.isBlank() || doc.equals(p.docId()))
                .map(ChangeProposalRepository.Proposal::id).findFirst().orElse(null);
    }

    private void fillList(ChangeJobRepository.Job job, String status, String doc, Model model) {
        List<ChangeProposalRepository.Proposal> all = proposals.listByJob(job.id());
        boolean batch = ReviewRules.batchAllowed(job);
        String nlType = ReviewRules.nlType(job);
        Map<String, String> docs = new LinkedHashMap<>();
        List<Row> rows = new ArrayList<>();
        java.util.Set<String> seenGroups = new java.util.HashSet<>();
        int pending = 0, applying = 0, applied = 0, withdrawn = 0, problems = 0;
        for (ChangeProposalRepository.Proposal p : all) {
            docs.putIfAbsent(p.docId(), p.filename());
            Row r = row(p, batch, nlType);
            switch (r.state()) {
                case "pending", "edited" -> pending++;
                case "applying" -> applying++;
                case "applied" -> applied++;
                case "withdrawn", "rejected" -> withdrawn++;
                default -> problems++;
            }
            if (doc != null && !doc.isBlank() && !doc.equals(p.docId())) continue;
            if (!matches(status, r.state())) continue;
            // 겹침 짝은 한 줄로 — 짝의 첫 청크만 보인다.
            if (p.groupKey() != null && !seenGroups.add(p.groupKey())) continue;
            rows.add(r);
        }
        model.addAttribute("job", job);
        model.addAttribute("rows", rows);
        model.addAttribute("docs", docs);
        model.addAttribute("filterStatus", status == null ? FILTER_OPEN : status);
        model.addAttribute("filterDoc", blankToNull(doc));
        model.addAttribute("countPending", pending);
        model.addAttribute("countApplying", applying);
        model.addAttribute("countApplied", applied);
        model.addAttribute("countWithdrawn", withdrawn);
        model.addAttribute("countProblems", problems);
        model.addAttribute("batchAllowed", batch);
        List<ChangeProposalRepository.Proposal> targets = batch ? applyService.batchTargets(job.id(), blankToNull(doc)) : List.of();
        model.addAttribute("batchCount", targets.size());
        model.addAttribute("batchDocs", targets.stream().map(ChangeProposalRepository.Proposal::docId).distinct().count());
        model.addAttribute("batchLlmCalls", targets.stream().filter(p -> p.edited() || (p.afterContext() == null && p.afterKeywords() == null)).count());
        boolean draining = applyService.draining(job.id());
        model.addAttribute("polling", applying > 0 && draining);
        model.addAttribute("canRetry", applying > 0 && !draining);
        model.addAttribute("nlType", nlType);
    }

    private static boolean matches(String filter, String state) {
        if (filter == null || filter.isBlank() || FILTER_OPEN.equals(filter)) return "pending".equals(state) || "edited".equals(state);
        return switch (filter) {
            case "all" -> true;
            case "applying" -> "applying".equals(state);
            case "applied" -> "applied".equals(state);
            case "withdrawn" -> "withdrawn".equals(state) || "rejected".equals(state);
            case "problems" -> "stale".equals(state) || "failed".equals(state) || "md".equals(state);
            default -> true;
        };
    }

    /** 화면 상태 — 대기 · 편집함 · 적용 중 · 적용됨 · MD 미반영 · 뺌 · 거절 · 낡음 · 실패. */
    static String state(ChangeProposalRepository.Proposal p) {
        return switch (p.status()) {
            case ChangeProposalRepository.PENDING -> p.edited() ? "edited" : "pending";
            case ChangeProposalRepository.APPROVED -> "applying";
            case ChangeProposalRepository.APPLIED ->
                    p.mdStatus() != null && !com.example.ragagent.workflow.MarkdownWriteBack.APPLIED.equals(p.mdStatus()) ? "md" : "applied";
            default -> p.status();
        };
    }

    /** 유형 배지의 메시지 키 — 자연어 수정 편집이면 그 유형, 아니면 수정안 종류. */
    static String typeKey(ChangeProposalRepository.Proposal p, String nlType) {
        if (ChangeProposalRepository.KIND_EDIT.equals(p.kind()) && nlType != null) return "edit.type." + nlType;
        return "maint.kind." + p.kind();
    }

    private static Row row(ChangeProposalRepository.Proposal p, boolean batch, String nlType) {
        boolean needsDirect = batch && ChangeProposalRepository.PENDING.equals(p.status()) && (p.groupKey() != null || !ReviewRules.insertOnly(p));
        return new Row(p, state(p), typeKey(p, nlType), needsDirect);
    }

    private void fillItem(ChangeJobRepository.Job job, Long pid, Model model) {
        model.addAttribute("job", job);
        if (pid == null) {
            model.addAttribute("item", null);
            List<ChangeProposalRepository.Proposal> all = proposals.listByJob(job.id());
            model.addAttribute("summaryApplied", all.stream().filter(p -> ChangeProposalRepository.APPLIED.equals(p.status())).count());
            model.addAttribute("summaryApplying", all.stream().filter(p -> ChangeProposalRepository.APPROVED.equals(p.status())).count());
            model.addAttribute("summaryStale", all.stream().filter(p -> ChangeProposalRepository.STALE.equals(p.status())).count());
            model.addAttribute("summaryFailed", all.stream().filter(p -> ChangeProposalRepository.FAILED.equals(p.status())).count());
            model.addAttribute("summaryMd", all.stream().filter(p -> "md".equals(state(p))).count());
            return;
        }
        ChangeProposalRepository.Proposal p = requireProposal(job.id(), pid);
        // 읽지 못하면 위치만 빠지고, 낡음 판정은 "없음"으로 본다.
        AdminService.ChunkRow chunk = applyService.currentChunk(p.chunkId());
        boolean open = ChangeProposalRepository.PENDING.equals(p.status());
        boolean stale = open && (chunk == null || !TextHash.sha256(chunk.fullText()).equals(p.beforeHash()));
        String heading = chunk == null ? null : chunk.metadata().get(MetaKey.HEADING);
        int groupSize = p.groupKey() == null ? 1
                : (int) proposals.listByJob(job.id()).stream().filter(x -> p.groupKey().equals(x.groupKey())).count();
        String afterForDiff = ChangeProposalRepository.KIND_DELETE_CHUNK.equals(p.kind()) ? "" : p.afterText();
        model.addAttribute("item", new Item(row(p, ReviewRules.batchAllowed(job), ReviewRules.nlType(job)),
                ReviewDiff.of(p.beforeText(), afterForDiff), stale, TextHash.sha256(p.afterText()), heading,
                applyService.noteFor(p.id()), groupSize));
    }

    private ChangeJobRepository.Job requireJob(long id) {
        return jobs.find(id)
                .filter(j -> ChangeJobRepository.KIND_MAINTENANCE.equals(j.kind()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private ChangeProposalRepository.Proposal requireProposal(long jobId, long pid) {
        return proposals.find(pid).filter(x -> x.jobId() == jobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
