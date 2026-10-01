package com.example.ragagent.controller;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.context.ThreadContextResolver;
import com.example.ragagent.security.AppUserDetails;
import com.example.ragagent.service.ClarifiedQuestionBackfill;
import com.example.ragagent.service.ClarifiedQuestionBackfill.State;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /admin/clarify-backfill} — 상태를 JSON 으로 돌려주고, 시작·멈춤은 감사 로그를 남기며, 관리자만 부를 수 있다
 * (full-auth 모드 — 가입만 한 사용자는 403, 쓰기 요청은 CSRF 필요).
 */
@WebMvcTest(value = ClarifyBackfillController.class, properties = "app.auth.enabled=true")
@Import({com.example.ragagent.context.WebMvcConfig.class, com.example.ragagent.security.SecurityConfig.class})
@ResourceLock("global-state")
class ClarifyBackfillControllerTest {

    private static final AppUserDetails ADMIN =
            new AppUserDetails("u1", "admin@example.com", null, "Admin", "ADMIN", true, false);
    private static final AppUserDetails MEMBER =
            new AppUserDetails("u2", "member@example.com", null, "Member", "USER", true, false);

    private static final ClarifiedQuestionBackfill.Status RUNNING = new ClarifiedQuestionBackfill.Status(
            State.RUNNING, true, 120, 3, 2, 1, 0, "2026-10-01T10:00:00Z", null, null);

    @Autowired MockMvc mvc;

    @MockitoBean ClarifiedQuestionBackfill backfill;
    @MockitoBean AuditLogger auditLogger;
    @MockitoBean AppProperties props;                         // SecurityConfig 의존
    @MockitoBean ThreadContextResolver threadContextResolver;
    @MockitoBean org.springframework.ai.chat.model.ChatModel chatModel;   // WebConfig 의존

    @Test
    @DisplayName("GET — 관리자에게 상태를 JSON 으로")
    void statusAsJson() throws Exception {
        when(backfill.status()).thenReturn(RUNNING);

        mvc.perform(get("/admin/clarify-backfill").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.running").value(true))
                .andExpect(jsonPath("$.remaining").value(120))
                .andExpect(jsonPath("$.clarified").value(2))
                .andExpect(jsonPath("$.keptOriginal").value(1));
    }

    @Test
    @DisplayName("POST start/stop — 상태를 돌려주고 감사 로그를 남긴다")
    void startAndStopAreAudited() throws Exception {
        when(backfill.start()).thenReturn(RUNNING);
        when(backfill.stop()).thenReturn(RUNNING);

        mvc.perform(post("/admin/clarify-backfill/start").with(user(ADMIN)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RUNNING"));
        mvc.perform(post("/admin/clarify-backfill/stop").with(user(ADMIN)).with(csrf()))
                .andExpect(status().isOk());

        verify(auditLogger).log(eq("admin.clarify-backfill.start"), eq("conversation_turns"), anyMap());
        verify(auditLogger).log(eq("admin.clarify-backfill.stop"), eq("conversation_turns"), anyMap());
    }

    @Test
    @DisplayName("관리자가 아니면 403 — 조회도, 시작도")
    void adminOnly() throws Exception {
        mvc.perform(get("/admin/clarify-backfill").with(user(MEMBER)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/clarify-backfill/start").with(user(MEMBER)).with(csrf()))
                .andExpect(status().isForbidden());

        verify(backfill, never()).start();
    }

    @Test
    @DisplayName("CSRF 토큰 없는 시작 요청은 거부된다")
    void startNeedsCsrf() throws Exception {
        mvc.perform(post("/admin/clarify-backfill/start").with(user(ADMIN)))
                .andExpect(status().isForbidden());

        verify(backfill, never()).start();
    }
}
