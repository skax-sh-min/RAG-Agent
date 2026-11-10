package com.example.ragagent.controller;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.context.ThreadContextResolver;
import com.example.ragagent.repository.ChangeJobRepository;
import com.example.ragagent.repository.ChangeProposalRepository;
import com.example.ragagent.security.AppUserDetails;
import com.example.ragagent.security.CurrentUser;
import com.example.ragagent.workflow.ChangeApplyService;
import com.example.ragagent.workflow.MaintenanceWorkflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 유지보수 요청·검토 화면 — 관리자만, 요청은 POST+CSRF, 화면에 적용 버튼이 없고 Diff 가 그려진다. */
@WebMvcTest(value = AdminChangeController.class, properties = "app.auth.enabled=true")
@Import({com.example.ragagent.context.WebMvcConfig.class, com.example.ragagent.security.SecurityConfig.class})
@ResourceLock("global-state")
class AdminChangeControllerTest {

    private static final AppUserDetails ADMIN = new AppUserDetails("root", "root@example.com", null, "관리자", "ADMIN", true, false);
    private static final AppUserDetails USER = new AppUserDetails("u1", "u1@example.com", null, "사용자", "USER", true, false);

    @Autowired MockMvc mvc;

    @MockitoBean MaintenanceWorkflow workflow;
    @MockitoBean ChangeApplyService applyService;
    @MockitoBean ChangeJobRepository jobs;
    @MockitoBean ChangeProposalRepository proposals;
    @MockitoBean CurrentUser currentUser;
    @MockitoBean AppProperties props;
    @MockitoBean ThreadContextResolver threadContextResolver;
    @MockitoBean org.springframework.ai.chat.model.ChatModel chatModel;

    @BeforeEach
    void setUp() {
        when(currentUser.userId()).thenReturn("root");
    }

    private static ChangeJobRepository.Job job(long id, String kind, String status, String summary) {
        return new ChangeJobRepository.Job(id, kind, "PostgreSQL 을 Oracle 로", "root", status, summary, null, "2026-11-10 01:00:00", "2026-11-10 01:00:00");
    }

    private static ChangeProposalRepository.Proposal proposal() {
        return proposal("pending", null);
    }

    private static ChangeProposalRepository.Proposal proposal(String status, String mdStatus) {
        return new ChangeProposalRepository.Proposal(1, 5, "d1", "guide.pdf", "c1", "literal",
                "PostgreSQL 에 접속한다.", "Oracle 에 접속한다.", "h", null, null, null, null, "리터럴", status,
                mdStatus, null, null, null, "t");
    }

    @Test
    @DisplayName("일반 사용자는 유지보수 화면·요청에 접근할 수 없다 — 요청은 아예 실행되지 않는다")
    void nonAdminIsRefused() throws Exception {
        mvc.perform(get("/admin/change-jobs").with(user(USER))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/change-jobs/5").with(user(USER))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/change-requests").with(user(USER)).with(csrf()).param("instruction", "x"))
                .andExpect(status().isForbidden());
        verify(workflow, never()).start(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    @Test
    @DisplayName("요청은 CSRF 토큰이 있어야 하고, 성공하면 작업 화면으로 넘어간다")
    void requestRequiresCsrfAndRedirects() throws Exception {
        when(workflow.start(eq("root"), eq("PostgreSQL 을 Oracle 로"), eq("PostgreSQL"), eq("Oracle"), eq(false), any()))
                .thenReturn(new MaintenanceWorkflow.Started(5, "running"));

        mvc.perform(post("/admin/change-requests").with(user(ADMIN))
                        .param("instruction", "PostgreSQL 을 Oracle 로").param("from", "PostgreSQL").param("to", "Oracle"))
                .andExpect(status().isForbidden());   // CSRF 없음
        mvc.perform(post("/admin/change-requests").with(user(ADMIN)).with(csrf())
                        .param("instruction", "PostgreSQL 을 Oracle 로").param("from", "PostgreSQL").param("to", "Oracle"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/admin/change-jobs/5"));
    }

    @Test
    @DisplayName("진행 중 요청·잘못된 요청은 오류 코드로 목록에 돌아간다")
    void busyAndInvalidRedirectBack() throws Exception {
        when(workflow.start(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any())).thenThrow(new IllegalStateException("busy"));
        mvc.perform(post("/admin/change-requests").with(user(ADMIN)).with(csrf()).param("instruction", "x"))
                .andExpect(header().string("Location", "/admin/change-jobs?error=busy"));

        org.mockito.Mockito.doThrow(new IllegalArgumentException("bad")).when(workflow).start(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
        mvc.perform(post("/admin/change-requests").with(user(ADMIN)).with(csrf()).param("instruction", "x"))
                .andExpect(header().string("Location", "/admin/change-jobs?error=invalid"));
    }

    @Test
    @DisplayName("작업 화면 — Diff·상태 배지·리포트(수동 처리·버림)가 그려지고, 검토 대기 문서에는 승인·거절 버튼이 있다")
    void rendersTheProposalsWithReviewButtons() throws Exception {
        String report = """
                {"instruction":"PostgreSQL 을 Oracle 로","from":"PostgreSQL","to":"Oracle","mentions":3,"truncated":false,"tooShort":false,
                 "proposals":1,"unchanged":1,"manualCurated":2,
                 "discarded":[{"file":"img.pdf","chunkId":"c9","reason":"이미지 마커가 바뀜"}]}""";
        when(jobs.find(5L)).thenReturn(Optional.of(job(5, "maintenance", "done", report)));
        when(proposals.listByJob(5L)).thenReturn(List.of(proposal()));

        mvc.perform(get("/admin/change-jobs/5").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("guide.pdf")))
                .andExpect(content().string(containsString("-PostgreSQL 에 접속한다.")))
                .andExpect(content().string(containsString("+Oracle 에 접속한다.")))
                .andExpect(content().string(containsString("검토 대기")))
                .andExpect(content().string(containsString("수동 처리가 필요합니다")))
                .andExpect(content().string(containsString("이미지 마커가 바뀜")))
                .andExpect(content().string(containsString("아직 아무것도 바뀌지 않았습니다")))
                .andExpect(content().string(containsString("승인하고 적용")))
                .andExpect(content().string(containsString("/admin/change-jobs/5/docs/approve")))
                .andExpect(content().string(containsString("/admin/change-jobs/5/docs/reject")))
                .andExpect(content().string(not(containsString("every 3s"))));
    }

    @Test
    @DisplayName("이미 적용된 문서에는 버튼이 없고 MD 결과·원본 파일 경고가 보인다 · 승인돼 적용 중이면 폴링한다")
    void appliedAndInProgressDocuments() throws Exception {
        when(jobs.find(5L)).thenReturn(Optional.of(job(5, "maintenance", "done", null)));
        when(proposals.listByJob(5L)).thenReturn(List.of(proposal("applied", "MD_CONFLICT")));

        mvc.perform(get("/admin/change-jobs/5").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("승인하고 적용"))))
                .andExpect(content().string(containsString("재인덱싱하면 이 수정이 되돌아갑니다")))
                .andExpect(content().string(containsString("원본 파일(DOCX/PDF 등)은 바뀌지 않습니다")))
                .andExpect(content().string(not(containsString("아직 아무것도 바뀌지 않았습니다"))));

        when(proposals.listByJob(5L)).thenReturn(List.of(proposal("approved", null)));
        mvc.perform(get("/admin/change-jobs/5/body").with(user(ADMIN)))
                .andExpect(content().string(containsString("every 3s")));
    }

    @Test
    @DisplayName("승인·거절은 관리자만, CSRF 와 함께 POST — 일반 사용자는 서비스에 닿지 못한다")
    void approveAndRejectAreAdminOnlyAndCsrfProtected() throws Exception {
        when(jobs.find(5L)).thenReturn(Optional.of(job(5, "maintenance", "done", null)));
        when(applyService.approveAndApply(5L, "d1", "root")).thenReturn(2);
        when(applyService.reject(5L, "d1", "root")).thenReturn(2);

        mvc.perform(post("/admin/change-jobs/5/docs/approve").with(user(USER)).with(csrf()).param("docId", "d1"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/change-jobs/5/docs/approve").with(user(ADMIN)).param("docId", "d1"))
                .andExpect(status().isForbidden());   // CSRF 없음
        verify(applyService, never()).approveAndApply(org.mockito.ArgumentMatchers.anyLong(), any(), any());

        mvc.perform(post("/admin/change-jobs/5/docs/approve").with(user(ADMIN)).with(csrf()).param("docId", "d1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/admin/change-jobs/5"));
        mvc.perform(post("/admin/change-jobs/5/docs/reject").with(user(ADMIN)).with(csrf()).param("docId", "d1"))
                .andExpect(status().is3xxRedirection());
        verify(applyService).approveAndApply(5L, "d1", "root");
        verify(applyService).reject(5L, "d1", "root");
    }

    @Test
    @DisplayName("승인할 것이 없으면(이미 처리됨) 안내와 함께 돌아가고, 없는 작업·영향도 작업의 승인은 404 — 서비스는 불리지 않는다")
    void nothingToApproveAndWrongJobs() throws Exception {
        when(jobs.find(5L)).thenReturn(Optional.of(job(5, "maintenance", "done", null)));
        when(applyService.approveAndApply(5L, "d1", "root")).thenReturn(0);
        when(jobs.find(7L)).thenReturn(Optional.of(job(7, "impact", "done", null)));
        when(jobs.find(8L)).thenReturn(Optional.empty());

        mvc.perform(post("/admin/change-jobs/5/docs/approve").with(user(ADMIN)).with(csrf()).param("docId", "d1"))
                .andExpect(header().string("Location", "/admin/change-jobs/5?notice=nothing"));
        mvc.perform(post("/admin/change-jobs/7/docs/approve").with(user(ADMIN)).with(csrf()).param("docId", "d1")).andExpect(status().isNotFound());
        mvc.perform(post("/admin/change-jobs/8/docs/reject").with(user(ADMIN)).with(csrf()).param("docId", "d1")).andExpect(status().isNotFound());
        verify(applyService, never()).approveAndApply(org.mockito.ArgumentMatchers.eq(7L), any(), any());
    }

    @Test
    @DisplayName("진행 중이면 본문 조각이 폴링하고, 영향도 작업이나 없는 작업은 404")
    void pollingAndNotFound() throws Exception {
        when(jobs.find(6L)).thenReturn(Optional.of(job(6, "maintenance", "running", null)));
        when(proposals.listByJob(6L)).thenReturn(List.of());
        when(jobs.find(7L)).thenReturn(Optional.of(job(7, "impact", "done", null)));
        when(jobs.find(8L)).thenReturn(Optional.empty());

        mvc.perform(get("/admin/change-jobs/6/body").with(user(ADMIN)))
                .andExpect(status().isOk()).andExpect(content().string(containsString("every 3s")));
        mvc.perform(get("/admin/change-jobs/7").with(user(ADMIN))).andExpect(status().isNotFound());
        mvc.perform(get("/admin/change-jobs/8").with(user(ADMIN))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("의미 변경 체크박스가 켜지면 semantic=true 로 시작한다 — 요청 폼에 체크박스와 안내가 있다")
    void semanticCheckboxIsPassedThrough() throws Exception {
        when(workflow.start(eq("root"), eq("x"), eq("ABC"), eq("XYZ"), eq(true), any()))
                .thenReturn(new MaintenanceWorkflow.Started(9, "running"));
        when(jobs.recentByKind(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());

        mvc.perform(post("/admin/change-requests").with(user(ADMIN)).with(csrf())
                        .param("instruction", "x").param("from", "ABC").param("to", "XYZ").param("semantic", "true"))
                .andExpect(header().string("Location", "/admin/change-jobs/9"));
        mvc.perform(get("/admin/change-jobs").with(user(ADMIN)))
                .andExpect(content().string(containsString("name=\"semantic\"")))
                .andExpect(content().string(containsString("검토해야 합니다")));
    }

    @Test
    @DisplayName("의미 변경 수정안은 배지로 구분되고 리포트에 시도·제안 수와 건너뛴 사유가 보인다")
    void semanticProposalsAreMarkedAndReported() throws Exception {
        String report = """
                {"instruction":"x","from":"PostgreSQL","to":"Oracle","mentions":2,"truncated":false,"tooShort":false,
                 "proposals":2,"unchanged":0,"manualCurated":0,"discarded":[],
                 "semantic":true,"semanticTried":2,"semanticProposed":1,
                 "semanticSkipped":[{"file":"guide.pdf","chunkId":"c2","reason":"가드 위반 — 새 말이 줄었다"}]}""";
        when(jobs.find(5L)).thenReturn(Optional.of(job(5, "maintenance", "done", report)));
        ChangeProposalRepository.Proposal literal = proposal();
        ChangeProposalRepository.Proposal semantic = new ChangeProposalRepository.Proposal(2, 5, "d1", "guide.pdf", "c3", "semantic",
                "PostgreSQL 드라이버", "Oracle 드라이버(ojdbc)", "h", null, null, null, null, "검토 필요", "pending",
                null, null, null, null, "t");
        when(proposals.listByJob(5L)).thenReturn(List.of(literal, semantic));

        mvc.perform(get("/admin/change-jobs/5").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("의미 변경 · 검토 필요")))
                .andExpect(content().string(containsString("글자 치환")))
                .andExpect(content().string(containsString("2곳을 AI 가 검토해 1곳을 제안했습니다")))
                .andExpect(content().string(containsString("가드 위반 — 새 말이 줄었다")));
    }

    @Test
    @DisplayName("옛 리포트(의미 변경 필드가 없는)도 그대로 그려진다")
    void reportsWithoutSemanticFieldsStillRender() throws Exception {
        String old = "{\"instruction\":\"x\",\"from\":\"A\",\"to\":\"B\",\"mentions\":1,\"truncated\":false,\"tooShort\":false,"
                + "\"proposals\":1,\"unchanged\":0,\"manualCurated\":0,\"discarded\":[]}";
        when(jobs.find(5L)).thenReturn(Optional.of(job(5, "maintenance", "done", old)));
        when(proposals.listByJob(5L)).thenReturn(List.of(proposal()));

        mvc.perform(get("/admin/change-jobs/5").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("1곳의 수정안을 만들었습니다")))   // 리포트가 실제로 읽혔다(null 이면 이 줄이 없다)
                .andExpect(content().string(not(containsString("AI 가 검토해"))));
    }
}
