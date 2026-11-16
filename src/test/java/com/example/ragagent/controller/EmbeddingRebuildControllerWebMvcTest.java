package com.example.ragagent.controller;

import org.junit.jupiter.api.parallel.ResourceLock;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.context.ThreadContextResolver;
import com.example.ragagent.security.AppUserDetails;
import com.example.ragagent.service.EmbeddingRebuildService;
import com.example.ragagent.service.EmbeddingRebuildService.Mode;
import com.example.ragagent.service.EmbeddingRebuildService.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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

/** 「임베딩 재생성」 카드의 엔드포인트 — 관리자 전용 게이트, mode 해석, 오류 상태 코드, 감사 기록. */
@WebMvcTest(value = EmbeddingRebuildController.class,
        properties = {"app.auth.enabled=true", "app.vectorstore.type=sqlite-vec"})
@Import({com.example.ragagent.context.WebMvcConfig.class, com.example.ragagent.security.SecurityConfig.class})
@ResourceLock("global-state")
class EmbeddingRebuildControllerWebMvcTest {

    private static final AppUserDetails ADMIN =
            new AppUserDetails("u1", "admin@example.com", null, "Admin", "ADMIN", true, false);
    private static final AppUserDetails USER =
            new AppUserDetails("u2", "user@example.com", null, "User", "USER", true, false);

    @Autowired MockMvc mvc;

    @MockitoBean EmbeddingRebuildService service;
    @MockitoBean AuditLogger auditLogger;
    @MockitoBean AppProperties props;                              // SecurityConfig 의존
    @MockitoBean ThreadContextResolver threadContextResolver;
    @MockitoBean org.springframework.ai.chat.model.ChatModel chatModel;   // WebConfig.chatClient 의존

    private static Status idle() {
        return new Status(768, 1024, true, 10, 10, 10, false, null);
    }

    @Test
    @DisplayName("GET /admin/embedding-rebuild — 관리자는 상태 JSON 을 받는다")
    void statusForAdmin() throws Exception {
        when(service.status()).thenReturn(idle());

        mvc.perform(get("/admin/embedding-rebuild").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tableDimension").value(768))
                .andExpect(jsonPath("$.configuredDimension").value(1024))
                .andExpect(jsonPath("$.dimensionMismatch").value(true))
                .andExpect(jsonPath("$.missingVectors").value(10))
                .andExpect(jsonPath("$.running").value(false));
    }

    @Test
    @DisplayName("관리자가 아니면 상태·시작·중단 모두 거절하고 서비스를 건드리지 않는다")
    void nonAdminIsRejected() throws Exception {
        mvc.perform(get("/admin/embedding-rebuild").with(user(USER))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/embedding-rebuild/start").param("mode", "rebuild").with(user(USER)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/embedding-rebuild/cancel").with(user(USER)).with(csrf()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("POST start?mode=rebuild — 서비스를 REBUILD 로 시작하고 감사 로그를 남긴다")
    void startRebuild() throws Exception {
        when(service.start(Mode.REBUILD)).thenReturn(idle());

        mvc.perform(post("/admin/embedding-rebuild/start").param("mode", "rebuild").with(user(ADMIN)).with(csrf()))
                .andExpect(status().isOk());

        verify(service).start(Mode.REBUILD);
        verify(auditLogger).log(eq("admin.embedding-rebuild.start"), eq("vec_embeddings"), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    @DisplayName("POST start?mode=RESUME — 대소문자 무관")
    void startResumeCaseInsensitive() throws Exception {
        when(service.start(Mode.RESUME)).thenReturn(idle());

        mvc.perform(post("/admin/embedding-rebuild/start").param("mode", "RESUME").with(user(ADMIN)).with(csrf()))
                .andExpect(status().isOk());

        verify(service).start(Mode.RESUME);
    }

    @Test
    @DisplayName("알 수 없는 mode → 400, 서비스는 시작하지 않는다")
    void unknownModeIs400() throws Exception {
        mvc.perform(post("/admin/embedding-rebuild/start").param("mode", "nuke").with(user(ADMIN)).with(csrf()))
                .andExpect(status().isBadRequest());
        verify(service, never()).start(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("시작할 수 없는 조건(IllegalArgumentException) → 400")
    void invalidConditionIs400() throws Exception {
        when(service.start(Mode.RESUME)).thenThrow(new IllegalArgumentException("차원이 달라 이어서 할 수 없습니다"));

        mvc.perform(post("/admin/embedding-rebuild/start").param("mode", "resume").with(user(ADMIN)).with(csrf()))
                .andExpect(status().isBadRequest());
        verify(auditLogger, never()).log(eq("admin.embedding-rebuild.start"), anyString(), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    @DisplayName("이미 진행 중이면 409 — 감사 로그는 남기지 않는다(시작되지 않았다)")
    void alreadyRunningIs409() throws Exception {
        when(service.start(Mode.REBUILD)).thenThrow(new IllegalStateException("이미 진행 중인 임베딩 재생성이 있습니다."));

        mvc.perform(post("/admin/embedding-rebuild/start").param("mode", "rebuild").with(user(ADMIN)).with(csrf()))
                .andExpect(status().isConflict());
        verify(auditLogger, never()).log(eq("admin.embedding-rebuild.start"), anyString(), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    @DisplayName("POST start — CSRF 토큰 없이는 거절")
    void startRequiresCsrf() throws Exception {
        mvc.perform(post("/admin/embedding-rebuild/start").param("mode", "rebuild").with(user(ADMIN)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("POST cancel — 중단을 요청하고 갱신된 상태를 돌려준다")
    void cancel() throws Exception {
        when(service.cancel()).thenReturn(true);
        when(service.status()).thenReturn(idle());

        mvc.perform(post("/admin/embedding-rebuild/cancel").with(user(ADMIN)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configuredDimension").value(1024));

        verify(service).cancel();
        verify(auditLogger).log(eq("admin.embedding-rebuild.cancel"), eq("vec_embeddings"), org.mockito.ArgumentMatchers.anyMap());
    }
}
