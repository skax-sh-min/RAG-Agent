package com.example.ragagent.controller;

import org.junit.jupiter.api.parallel.ResourceLock;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.context.ThreadContextResolver;
import com.example.ragagent.security.AppUserDetails;
import com.example.ragagent.service.UpdateApplyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 「새 버전」 카드의 적용 쪽 엔드포인트 — 관리자 전용·CSRF, 409(조건 불충족), 감사 기록, 결과 JSON 모양. */
@WebMvcTest(value = UpdateApplyController.class, properties = {"app.auth.enabled=true"})
@Import({com.example.ragagent.context.WebMvcConfig.class, com.example.ragagent.security.SecurityConfig.class})
@ResourceLock("global-state")
class UpdateApplyControllerWebMvcTest {

    private static final AppUserDetails ADMIN =
            new AppUserDetails("u1", "admin@example.com", null, "Admin", "ADMIN", true, false);
    private static final AppUserDetails USER =
            new AppUserDetails("u2", "user@example.com", null, "User", "USER", true, false);

    @Autowired MockMvc mvc;

    @MockitoBean UpdateApplyService service;
    @MockitoBean AuditLogger auditLogger;
    @MockitoBean AppProperties props;                              // SecurityConfig 의존
    @MockitoBean ThreadContextResolver threadContextResolver;
    @MockitoBean org.springframework.ai.chat.model.ChatModel chatModel;   // WebConfig.chatClient 의존

    private static UpdateApplyService.View view(boolean canApply, UpdateApplyService.Job job, UpdateApplyService.SupervisorResult last) {
        return new UpdateApplyService.View(true, canApply, canApply ? null : "사유", job, last);
    }

    private static UpdateApplyService.Job downloading() {
        return new UpdateApplyService.Job(UpdateApplyService.Phase.DOWNLOADING, "0.5.7", 100, 1000, "jar 를 받는 중",
                Instant.parse("2026-11-16T01:00:00Z"), null);
    }

    @Test
    @DisplayName("GET /admin/update/apply — 적용 가능 여부·진행 작업·마지막 결과를 JSON 으로")
    void viewForAdmin() throws Exception {
        UpdateApplyService.SupervisorResult last = new UpdateApplyService.SupervisorResult(
                "rolled-back", "0.5.7", "exited-early", true, Instant.parse("2026-11-15T00:00:00Z"), "되돌렸습니다");
        when(service.view()).thenReturn(view(true, downloading(), last));

        mvc.perform(get("/admin/update/apply").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supervised").value(true))
                .andExpect(jsonPath("$.canApply").value(true))
                .andExpect(jsonPath("$.job.phase").value("DOWNLOADING"))
                .andExpect(jsonPath("$.job.bytesDone").value(100))
                .andExpect(jsonPath("$.lastResult.status").value("rolled-back"))
                .andExpect(jsonPath("$.lastResult.dbRestored").value(true))
                .andExpect(jsonPath("$.lastResult.message").value("되돌렸습니다"));
    }

    @Test
    @DisplayName("관리자가 아니면 조회·시작 모두 거절하고 서비스를 건드리지 않는다")
    void nonAdminIsRejected() throws Exception {
        mvc.perform(get("/admin/update/apply").with(user(USER))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/update/apply").param("version", "0.5.7").with(user(USER)).with(csrf()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("POST /admin/update/apply — 지정한 버전으로 시작하고 감사 로그를 남긴다")
    void startsAndAudits() throws Exception {
        when(service.view()).thenReturn(view(true, null, null));
        when(service.start("0.5.7")).thenReturn(downloading());

        mvc.perform(post("/admin/update/apply").param("version", "0.5.7").with(user(ADMIN)).with(csrf()))
                .andExpect(status().isOk());

        verify(service).start("0.5.7");
        verify(auditLogger).log(eq("admin.update.apply"), eq("0.5.7"), anyMap());
    }

    @Test
    @DisplayName("조건이 안 맞으면 409 와 사유 — 감사 로그는 남기지 않는다(시작되지 않았다)")
    void conflictWhenNotAllowed() throws Exception {
        when(service.view()).thenReturn(view(false, null, null));
        when(service.start("0.5.7")).thenThrow(new IllegalStateException("감독 스크립트로 실행 중이 아닙니다"));

        mvc.perform(post("/admin/update/apply").param("version", "0.5.7").with(user(ADMIN)).with(csrf()))
                .andExpect(status().isConflict());

        verify(auditLogger, never()).log(eq("admin.update.apply"), anyString(), anyMap());
    }

    @Test
    @DisplayName("version 이 없으면 400 — 확인하지 않은 버전을 올리지 않는다")
    void versionIsRequired() throws Exception {
        mvc.perform(post("/admin/update/apply").with(user(ADMIN)).with(csrf()))
                .andExpect(status().isBadRequest());
        verify(service, never()).start(anyString());
    }

    @Test
    @DisplayName("CSRF 토큰 없이는 거절")
    void requiresCsrf() throws Exception {
        mvc.perform(post("/admin/update/apply").param("version", "0.5.7").with(user(ADMIN)))
                .andExpect(status().isForbidden());
        verify(service, never()).start(anyString());
    }
}
