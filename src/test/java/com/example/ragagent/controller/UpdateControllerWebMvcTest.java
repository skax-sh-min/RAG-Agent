package com.example.ragagent.controller;

import org.junit.jupiter.api.parallel.ResourceLock;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.context.ThreadContextResolver;
import com.example.ragagent.security.AppUserDetails;
import com.example.ragagent.service.UpdateCheckService;
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

/** 「새 버전」 카드의 엔드포인트 — 관리자 전용 게이트, 꺼짐/설정 오류의 409, 감사 기록, 배지용 최소 응답. */
@WebMvcTest(value = UpdateController.class, properties = {"app.auth.enabled=true"})
@Import({com.example.ragagent.context.WebMvcConfig.class, com.example.ragagent.security.SecurityConfig.class})
@ResourceLock("global-state")
class UpdateControllerWebMvcTest {

    private static final AppUserDetails ADMIN =
            new AppUserDetails("u1", "admin@example.com", null, "Admin", "ADMIN", true, false);
    private static final AppUserDetails USER =
            new AppUserDetails("u2", "user@example.com", null, "User", "USER", true, false);

    @Autowired MockMvc mvc;

    @MockitoBean UpdateCheckService service;
    @MockitoBean AuditLogger auditLogger;
    @MockitoBean AppProperties props;                              // SecurityConfig 의존
    @MockitoBean ThreadContextResolver threadContextResolver;
    @MockitoBean org.springframework.ai.chat.model.ChatModel chatModel;   // WebConfig.chatClient 의존

    private static UpdateCheckService.Status sample(boolean available) {
        UpdateCheckService.Release latest = new UpdateCheckService.Release(
                "v0.5.7", "0.5.7", "Release 0.5.7", "notes", "https://github.com/o/n/releases/tag/v0.5.7", Instant.parse("2026-11-16T00:00:00Z"),
                new UpdateCheckService.Asset("rag-agent-0.5.7.jar", 1000L,
                        "https://github.com/o/n/releases/download/v0.5.7/rag-agent-0.5.7.jar"),
                new UpdateCheckService.Asset("rag-agent-0.5.7.jar.sha256", 100L,
                        "https://github.com/o/n/releases/download/v0.5.7/rag-agent-0.5.7.jar.sha256"));
        return new UpdateCheckService.Status(true, "0 0 9 * * *", "Asia/Seoul", "o/n", "0.5.6", false, false,
                new UpdateCheckService.CheckResult("manual", Instant.parse("2026-11-16T01:00:00Z"), true, null),
                latest, available, false, null);
    }

    @Test
    @DisplayName("GET /admin/update — 관리자는 상태 JSON 을 받는다")
    void statusForAdmin() throws Exception {
        when(service.status()).thenReturn(sample(true));

        mvc.perform(get("/admin/update").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.currentVersion").value("0.5.6"))
                .andExpect(jsonPath("$.latest.version").value("0.5.7"))
                .andExpect(jsonPath("$.updateAvailable").value(true));
    }

    @Test
    @DisplayName("관리자가 아니면 상태·확인·배지 모두 거절하고 서비스를 건드리지 않는다")
    void nonAdminIsRejected() throws Exception {
        mvc.perform(get("/admin/update").with(user(USER))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/update/available").with(user(USER))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/update/check").with(user(USER)).with(csrf())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("POST /admin/update/check — 수동으로 확인하고 감사 로그를 남긴다")
    void manualCheck() throws Exception {
        when(service.check("manual")).thenReturn(sample(true));

        mvc.perform(post("/admin/update/check").with(user(ADMIN)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updateAvailable").value(true));

        verify(service).check("manual");
        verify(auditLogger).log(eq("admin.update.check"), eq("o/n"), anyMap());
    }

    @Test
    @DisplayName("꺼져 있거나 설정이 틀리면 409 — 감사 로그는 남기지 않는다(확인이 일어나지 않았다)")
    void disabledIs409() throws Exception {
        when(service.check("manual")).thenThrow(new IllegalStateException("새 버전 확인이 꺼져 있습니다"));

        mvc.perform(post("/admin/update/check").with(user(ADMIN)).with(csrf()))
                .andExpect(status().isConflict());
        verify(auditLogger, never()).log(eq("admin.update.check"), anyString(), anyMap());
    }

    @Test
    @DisplayName("POST check — CSRF 토큰 없이는 거절")
    void checkRequiresCsrf() throws Exception {
        mvc.perform(post("/admin/update/check").with(user(ADMIN))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("GET /admin/update/available — 배지용 최소 응답(새 버전 있음 + 버전만, 노트는 없다)")
    void availableBadge() throws Exception {
        when(service.status()).thenReturn(sample(true));

        mvc.perform(get("/admin/update/available").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.version").value("0.5.7"))
                .andExpect(jsonPath("$.notes").doesNotExist());
    }

    @Test
    @DisplayName("새 버전이 없으면 available=false, version=null")
    void availableBadgeWhenCurrent() throws Exception {
        when(service.status()).thenReturn(sample(false));

        mvc.perform(get("/admin/update/available").with(user(ADMIN)))
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.version").doesNotExist());
    }
}
