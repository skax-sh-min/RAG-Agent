package com.example.ragagent.controller;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.context.ThreadContextResolver;
import com.example.ragagent.repository.ChangeJobRepository;
import com.example.ragagent.repository.ChangeProposalRepository;
import com.example.ragagent.security.AppUserDetails;
import com.example.ragagent.security.CurrentUser;
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
        return new ChangeProposalRepository.Proposal(1, 5, "d1", "guide.pdf", "c1", "literal",
                "PostgreSQL 에 접속한다.", "Oracle 에 접속한다.", "h", null, null, null, null, "리터럴", "pending",
                null, null, null, null, "t");
    }

    @Test
    @DisplayName("일반 사용자는 유지보수 화면·요청에 접근할 수 없다 — 요청은 아예 실행되지 않는다")
    void nonAdminIsRefused() throws Exception {
        mvc.perform(get("/admin/change-jobs").with(user(USER))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/change-jobs/5").with(user(USER))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/change-requests").with(user(USER)).with(csrf()).param("instruction", "x"))
                .andExpect(status().isForbidden());
        verify(workflow, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("요청은 CSRF 토큰이 있어야 하고, 성공하면 작업 화면으로 넘어간다")
    void requestRequiresCsrfAndRedirects() throws Exception {
        when(workflow.start(eq("root"), eq("PostgreSQL 을 Oracle 로"), eq("PostgreSQL"), eq("Oracle"), any()))
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
        when(workflow.start(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("busy"));
        mvc.perform(post("/admin/change-requests").with(user(ADMIN)).with(csrf()).param("instruction", "x"))
                .andExpect(header().string("Location", "/admin/change-jobs?error=busy"));

        org.mockito.Mockito.doThrow(new IllegalArgumentException("bad")).when(workflow).start(any(), any(), any(), any(), any());
        mvc.perform(post("/admin/change-requests").with(user(ADMIN)).with(csrf()).param("instruction", "x"))
                .andExpect(header().string("Location", "/admin/change-jobs?error=invalid"));
    }

    @Test
    @DisplayName("작업 화면 — Diff·상태 배지·리포트(수동 처리·버림)가 그려지고 승인/적용 버튼은 없다")
    void rendersTheProposalsWithoutApplyButtons() throws Exception {
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
                .andExpect(content().string(not(containsString("승인"))))
                .andExpect(content().string(not(containsString("every 3s"))));
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
}
