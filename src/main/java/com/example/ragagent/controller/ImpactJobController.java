package com.example.ragagent.controller;

import com.example.ragagent.context.ThreadContext;
import com.example.ragagent.repository.ChangeJobRepository;
import com.example.ragagent.workflow.ImpactAnalysisWorkflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

/**
 * 영향도 분석 리포트 화면(§6.33 4a) — 채팅이 남긴 링크({@code /ui/impact-jobs/{id}})의 도착지. <b>읽기 전용</b>이다.
 *
 * <p>리포트는 <b>요청자와 관리자만</b> 본다. 남의 작업은 "없음"(404)과 같은 응답이다 — 작업 번호가 순번이라 존재 여부를 구별해 알려 주면 번호를
 * 훑어 남의 요청 대상(변경하려는 식별자)을 엿볼 수 있다. {@code /ui/**} 아래라 게스트도 열 수 있고(공유 게스트 id 는 같은 방문자 집합의 것이다)
 * 쓰기 엔드포인트가 아니라 CSRF 와 무관하다. 경로에 {@code /chat}·{@code /documents} 문자열을 넣지 않아 레이트리밋 버킷을 먹지 않는다.
 *
 * <p>진행 중이면 본문 조각({@code /body})을 HTMX 가 3초마다 다시 받는다 — 끝나면 조각이 폴링 속성을 달지 않아 스스로 멈춘다.
 */
@Controller
public class ImpactJobController {

    private static final Logger log = LoggerFactory.getLogger(ImpactJobController.class);
    /** 저장된 리포트 blob 을 읽는다 — 리포트에 필드가 늘어도 이미 만들어진 작업의 옛 리포트가 읽혀야 한다(Jackson 3 는 빠진 기본형 필드를 오류로 본다). */
    private static final tools.jackson.databind.ObjectMapper MAPPER = com.example.ragagent.llm.LenientJson.MAPPER;

    private final ChangeJobRepository jobs;

    public ImpactJobController(ChangeJobRepository jobs) {
        this.jobs = jobs;
    }

    @GetMapping("/ui/impact-jobs/{id}")
    public String page(ThreadContext ctx, @PathVariable long id, Model model) {
        fill(ctx, id, model);
        return "impact-job";
    }

    /** 폴링이 받는 본문 조각 — 같은 권한 검사를 지난다. */
    @GetMapping("/ui/impact-jobs/{id}/body")
    public String body(ThreadContext ctx, @PathVariable long id, Model model) {
        fill(ctx, id, model);
        return "impact-job :: body";
    }

    private void fill(ThreadContext ctx, long id, Model model) {
        ChangeJobRepository.Job job = jobs.find(id)
                .filter(j -> ChangeJobRepository.KIND_IMPACT.equals(j.kind()))
                .filter(j -> j.requestedBy().equals(ctx.userId()) || isAdminRequest())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("job", job);
        model.addAttribute("running", ChangeJobRepository.RUNNING.equals(job.status()));
        model.addAttribute("report", parse(job));
    }

    /** 저장된 리포트 JSON 을 읽는다 — 아직 없거나(진행 중·실패) 깨졌으면 {@code null}. */
    static ImpactAnalysisWorkflow.Report parse(ChangeJobRepository.Job job) {
        if (job.summaryJson() == null || job.summaryJson().isBlank()) return null;
        try {
            return MAPPER.readValue(job.summaryJson(), ImpactAnalysisWorkflow.Report.class);
        } catch (RuntimeException e) {
            log.warn("[IMPACT] 리포트를 읽지 못했다 job={}: {}", job.id(), e.getMessage());
            return null;
        }
    }

    private static boolean isAdminRequest() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated()
                && auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
