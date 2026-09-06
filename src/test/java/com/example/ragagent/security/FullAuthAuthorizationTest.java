package com.example.ragagent.security;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.context.ThreadContextResolver;
import com.example.ragagent.controller.AdminController;
import com.example.ragagent.controller.SettingsController;
import com.example.ragagent.service.AdminService;
import com.example.ragagent.service.CuratedQaService;
import com.example.ragagent.service.CuratedSubmissionService;
import com.example.ragagent.service.IndexingProgressService;
import com.example.ragagent.service.RagService;
import com.example.ragagent.service.RetrievalMetricsService;
import com.example.ragagent.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §6.19.2 — 전체 인증 모드(`app.auth.enabled=true`)의 인가 경계.
 *
 * <p>이 모드에서 ROLE_ADMIN 을 요구하는 경로는 오랫동안 {@code DELETE /admin/llm-usage/**}
 * 하나뿐이었고 나머지 {@code /admin/**} 은 "로그인한 사용자 누구나" 였다. {@code /signup} 이
 * permitAll 이므로 그 상태에서는 <b>가입만 하면 관리자</b>였다 — 전 사용자의 대화 전문
 * ({@code /admin/threads}), 런타임 설정({@code /admin/settings/update}), 검색 코퍼스 주입
 * ({@code /admin/submissions/{id}/approve})이 모두 그 한 줄 뒤에 있었다.
 *
 * <p>여기서 검증하는 것은 <b>필터 단계의 인가 판정</b>이라 컨트롤러 매핑이나 템플릿 렌더와
 * 무관하다 — 그래서 {@code /actuator/**} 처럼 이 슬라이스에 핸들러가 없는 경로도 규칙만
 * 정확히 검사할 수 있다.
 */
@WebMvcTest(value = {AdminController.class, SettingsController.class},
        properties = {"app.auth.enabled=true"})
@Import({com.example.ragagent.context.WebMvcConfig.class, SecurityConfig.class,
        SessionCurrentUser.class, FullAuthAuthorizationTest.TestConfig.class})
@ResourceLock("global-state")
class FullAuthAuthorizationTest {

    /**
     * {@code SecurityConfig.filterChain()} 은 컨텍스트 refresh 중 한 번 호출되면서
     * {@code props.authSafe()} 로 분기를 고른다. {@code @MockitoBean} 은 그 시점에 아직
     * 스텁되지 않으므로(@BeforeEach 는 필터 체인이 이미 만들어진 뒤에 돈다) 전체 인증 분기를
     * 확실히 타려면 여기서 미리 스텁된 mock 을 넘겨야 한다 — ManagementOnlyAuthorizationTest 와
     * 같은 이유다.
     */
    @TestConfiguration
    static class TestConfig {
        @Bean
        AppProperties props() {
            AppProperties props = mock(AppProperties.class);
            when(props.authSafe()).thenReturn(new AppProperties.AuthConfig(true, false));
            return props;
        }
    }

    @Autowired MockMvc mvc;

    // WebConfig 의 chatClient 빈이 요구한다 — 이 슬라이스는 LlmConfig 를 로드하지 않는다.
    @MockitoBean ChatModel chatModel;
    @MockitoBean SqliteUserDetailsService userDetailsService;
    @MockitoBean PasswordEncoder passwordEncoder;
    @MockitoBean ThreadContextResolver threadContextResolver;
    @MockitoBean AdminService adminService;
    @MockitoBean RagService ragService;
    @MockitoBean IndexingProgressService progressService;
    @MockitoBean CuratedQaService curatedQaService;
    @MockitoBean CuratedSubmissionService submissionService;
    @MockitoBean RetrievalMetricsService retrievalMetricsService;
    @MockitoBean com.example.ragagent.service.ThreadAdminService threadAdminService;
    @MockitoBean com.example.ragagent.service.CuratedQuestionSuggester questionSuggester;
    @MockitoBean com.example.ragagent.service.ChunkReportService chunkReportService;
    @MockitoBean AuditLogger auditLogger;
    @MockitoBean SettingsService settingsService;

    @BeforeEach
    void setUp() {
        when(submissionService.countPending()).thenReturn(3);
    }

    // ── 익명: 로그인으로 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("익명 GET /admin/submissions/pending-count — 로그인 리다이렉트")
    void anonymous_adminEndpoint_redirectsToLogin() throws Exception {
        mvc.perform(get("/admin/submissions/pending-count"))
                .andExpect(status().is3xxRedirection());
    }

    // ── ROLE_USER: 403 (이 커밋 전에는 전부 통과했다) ─────────────────────────

    @Test
    @DisplayName("ROLE_USER GET /admin/submissions/pending-count — 403")
    void user_adminReadEndpoint_forbidden() throws Exception {
        mvc.perform(get("/admin/submissions/pending-count").with(user("u").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ROLE_USER GET /admin/threads — 403 (전 사용자 대화 목록)")
    void user_threadPanel_forbidden() throws Exception {
        mvc.perform(get("/admin/threads").with(user("u").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ROLE_USER POST /admin/settings/update + CSRF — 403")
    void user_settingsUpdate_forbidden() throws Exception {
        mvc.perform(post("/admin/settings/update")
                        .param("key", "app.search-top-k").param("value", "10")
                        .with(csrf()).with(user("u").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ROLE_USER POST /admin/submissions/1/approve + CSRF — 403 (검색 코퍼스 주입)")
    void user_submissionApprove_forbidden() throws Exception {
        mvc.perform(post("/admin/submissions/1/approve").with(csrf()).with(user("u").roles("USER")))
                .andExpect(status().isForbidden());
    }

    // ── ROLE_ADMIN: 통과 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("ROLE_ADMIN GET /admin/submissions/pending-count — 200")
    void admin_adminReadEndpoint_ok() throws Exception {
        mvc.perform(get("/admin/submissions/pending-count").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    // ── actuator: 로그 레벨 변경은 관리 행위 ─────────────────────────────────

    /**
     * TRACE 로 올리면 {@code LlmCurlLogger} 가 검색된 문서 본문이 실린 프롬프트 전문을 로그
     * 파일에 남긴다 — 그래서 {@code /actuator/health} 를 제외한 actuator 는 ROLE_ADMIN 이다.
     */
    @Test
    @DisplayName("ROLE_USER POST /actuator/loggers/... + CSRF — 403")
    void user_loggersEndpoint_forbidden() throws Exception {
        mvc.perform(post("/actuator/loggers/com.example.ragagent")
                        .contentType("application/json").content("{\"configuredLevel\":\"TRACE\"}")
                        .with(csrf()).with(user("u").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("익명 POST /actuator/loggers/... — 로그인 리다이렉트")
    void anonymous_loggersEndpoint_redirectsToLogin() throws Exception {
        mvc.perform(post("/actuator/loggers/com.example.ragagent")
                        .contentType("application/json").content("{\"configuredLevel\":\"TRACE\"}")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    /** 헬스는 그대로 열려 있어야 한다(모니터링) — 이 슬라이스엔 핸들러가 없으므로 404. */
    @Test
    @DisplayName("익명 GET /actuator/health — 인가 통과(핸들러 없음 → 404)")
    void anonymous_health_notGated() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isNotFound());
    }
}
