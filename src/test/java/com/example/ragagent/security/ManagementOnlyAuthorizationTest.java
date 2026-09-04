package com.example.ragagent.security;

import org.junit.jupiter.api.parallel.ResourceLock;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.context.ThreadContext;
import com.example.ragagent.context.ThreadContextResolver;
import com.example.ragagent.controller.AdminController;
import com.example.ragagent.controller.CuratedSubmissionController;
import com.example.ragagent.controller.DocumentController;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.model.SyncResult;
import com.example.ragagent.model.VectorStoreAdminView;
import com.example.ragagent.service.AdminService;
import com.example.ragagent.service.CuratedQaService;
import com.example.ragagent.service.CuratedSubmissionService;
import com.example.ragagent.service.RetrievalMetricsService;
import com.example.ragagent.service.DocumentExportService;
import com.example.ragagent.service.IndexingProgressService;
import com.example.ragagent.service.RagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §6.17 B안 — management-only mode authorization boundary.
 *
 * Covers: anonymous access to /admin/** and the document-write UI is denied (redirected to
 * /login), ROLE_USER (i.e. NoAuthAutoLoginFilter's GUEST_PRINCIPAL, or any non-admin real login)
 * is denied with 403 even carrying a valid CSRF token, ROLE_ADMIN passes through, and the
 * open surface (read-only /documents, /ui/documents/list, /api/v1/documents) stays fully
 * reachable anonymously — including /api/v1/** mutating endpoints, which are deliberately
 * exempted from both the login gate and CSRF (preserves OPERATOR_MANUAL.md's documented curl
 * automation). NoAuthAutoLoginFilter is imported explicitly (@WebMvcTest doesn't auto-include
 * custom @Component filters) so guest auto-injection on non-gated paths is actually exercised —
 * without it, the "open surface" assertions below would trivially pass for the wrong reason
 * (no filter running at all) rather than proving the real guest path works.
 */
@WebMvcTest(value = {DocumentController.class, AdminController.class, CuratedSubmissionController.class},
        properties = {"app.auth.enabled=false", "app.auth.management-only=true"})
@Import({com.example.ragagent.context.WebMvcConfig.class, SecurityConfig.class, NoAuthAutoLoginFilter.class,
        SessionCurrentUser.class, ManagementOnlyAuthorizationTest.TestConfig.class})
@ResourceLock("global-state")
class ManagementOnlyAuthorizationTest {

    /**
     * SecurityConfig.filterChain() is a @Bean factory method invoked once during context
     * refresh — it reads props.authSafe() at that moment to pick its branch. A @MockitoBean
     * field is unstubbed until @BeforeEach runs, which is too late (the filter chain is already
     * built with authSafe()==null by then, silently falling through to the full-auth branch).
     * Providing a pre-stubbed mock via @Bean guarantees the right value is in place before
     * SecurityConfig's constructor ever runs.
     */
    @TestConfiguration
    static class TestConfig {
        @Bean
        AppProperties props() {
            AppProperties props = mock(AppProperties.class);
            when(props.authSafe()).thenReturn(new AppProperties.AuthConfig(false, true));
            return props;
        }

        /**
         * NoAuthAutoLoginFilter's collaborator. Needed explicitly because @WebMvcTest doesn't scan
         * @Component beans (same reason the filter itself is @Import-ed above). The strategy here is
         * the default 'shared' — see props() — so the resolver returns the fixed guest id without ever
         * reading the secret store, which is why a mock repository suffices.
         */
        @Bean
        GuestIdentityResolver guestIdentityResolver(AppProperties props) {
            return new GuestIdentityResolver(props, new ClientIpResolver(false),
                    mock(com.example.ragagent.repository.AppSecretRepository.class));
        }

        /**
         * Spring Boot's UserDetailsServiceAutoConfiguration still registers its
         * inMemoryUserDetailsManager fallback alongside the mocked SqliteUserDetailsService in
         * this slice (two UserDetailsService beans → "Global Authentication Manager will not use
         * a UserDetailsService for username/password login", per the WARN at context startup).
         * An explicit AuthenticationManager bean removes the ambiguity so POST /login actually
         * authenticates against the mock instead of silently failing every credential.
         */
        @Bean
        AuthenticationManager authenticationManager(SqliteUserDetailsService uds, PasswordEncoder pe) {
            DaoAuthenticationProvider provider = new DaoAuthenticationProvider(pe);
            provider.setUserDetailsService(uds);
            return new ProviderManager(provider);
        }
    }

    @Autowired MockMvc mvc;

    @MockitoBean SqliteUserDetailsService userDetailsService;
    @MockitoBean PasswordEncoder passwordEncoder;
    @MockitoBean ChatModel chatModel;
    @MockitoBean ThreadContextResolver threadContextResolver;
    @MockitoBean RagService ragService;
    @MockitoBean com.example.ragagent.repository.CuratedQaRepository curatedQaRepository;
    @MockitoBean IndexingProgressService progressService;
    @MockitoBean AuditLogger auditLogger;
    @MockitoBean LlmRouter llmRouter;
    @MockitoBean AdminService adminService;
    @MockitoBean CuratedQaService curatedQaService;
    @MockitoBean com.example.ragagent.service.CuratedQuestionSuggester questionSuggester;
    @MockitoBean com.example.ragagent.service.ChunkReportService chunkReportService;
    @MockitoBean CuratedSubmissionService submissionService;
    @MockitoBean RetrievalMetricsService retrievalMetricsService;
    @MockitoBean com.example.ragagent.service.ThreadAdminService threadAdminService;
    // 본문 이미지 업로드 엔드포인트의 협력자 — @WebMvcTest 는 @Service 를 스캔하지 않으므로
    // 명시하지 않으면 CuratedSubmissionController 생성이 실패해 컨텍스트 로드가 깨진다.
    @MockitoBean com.example.ragagent.service.CuratedImageStore curatedImageStore;
    @MockitoBean DocumentExportService documentExportService;
    @MockitoBean com.example.ragagent.service.StorageQuotaService storageQuotaService;

    private AppUserDetails adminUser() {
        return new AppUserDetails("admin-id", "admin@local", "hash", "Admin", "ADMIN", true, false);
    }

    @BeforeEach
    void setUp() throws Exception {
        when(userDetailsService.findFirstAdmin()).thenReturn(Optional.of(adminUser()));
        when(threadContextResolver.resolveArgument(any(), any(), any(), any()))
                .thenReturn(new ThreadContext("t1", "guest", Locale.KOREAN));
        when(ragService.listDocuments(any())).thenReturn(List.of());
        // GET /admin dereferences both unconditionally (AdminController#adminPage) — needed so
        // the session round-trip test below actually renders the page instead of throwing an NPE
        // once it authenticates past the login gate.
        when(adminService.listCollections()).thenReturn(new AdminService.CollectionsResult(List.of(), true));
        when(adminService.vectorStoreView()).thenReturn(new VectorStoreAdminView(
                "chroma", true, -1, 0, 0, null, null, "memory.db", "memory.db"));
        when(curatedQaService.listActive(anyInt(), anyInt())).thenReturn(List.of());
    }

    // ── 게이트 경로: 익명 접근 → 로그인으로 리다이렉트 ──────────────────────────

    @Test
    @DisplayName("익명 GET /admin — 로그인 페이지로 리다이렉트")
    void anonymousAdminAccess_redirectsToLogin() throws Exception {
        mvc.perform(get("/admin"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("익명 POST /ui/documents/upload — 로그인 페이지로 리다이렉트")
    void anonymousUpload_redirectsToLogin() throws Exception {
        mvc.perform(post("/ui/documents/upload").with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    /**
     * Export is a GET, but it returns the document's full reconstructed content in one response —
     * bulk extraction that guest chat/browsing doesn't offer — so it is gated with the management
     * surface rather than left open like /ui/documents/list.
     */
    @Test
    void anonymousExport_redirectsToLogin() throws Exception {
        mvc.perform(get("/ui/documents/x/export").param("format", "md"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void adminRole_export_succeeds() throws Exception {
        when(documentExportService.export(any(), any(), any(), any()))
                .thenReturn(new DocumentExportService.Result(
                        "# 문서".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "doc.md", "text/markdown; charset=UTF-8"));

        mvc.perform(get("/ui/documents/x/export").param("format", "md")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    // ── 게이트 경로: 잘못된 역할은 403, ADMIN은 통과 ──────────────────────────

    @Test
    @DisplayName("ROLE_USER + CSRF — DELETE /ui/documents/x → 403")
    void wrongRole_deleteDocument_forbidden() throws Exception {
        mvc.perform(delete("/ui/documents/x").with(csrf()).with(user("someone").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ROLE_ADMIN + CSRF — DELETE /ui/documents/x → 200 통과")
    void adminRole_deleteDocument_succeeds() throws Exception {
        mvc.perform(delete("/ui/documents/x").with(csrf()).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    /**
     * §표시 이름 — regression coverage for the display-name routes originally shipped without an
     * entry in either this matcher list or NoAuthAutoLoginFilter.GATED_UI_DOCUMENT_ROUTES (the two
     * are supposed to mirror each other exactly, per that field's own comment), which left them
     * open to any guest in management-only mode instead of requiring the real admin login every
     * other document-write route here does.
     */
    @Test
    @DisplayName("익명 PATCH /ui/documents/x/display-name — 로그인 페이지로 리다이렉트")
    void anonymousUpdateDisplayName_redirectsToLogin() throws Exception {
        mvc.perform(patch("/ui/documents/x/display-name").with(csrf()).param("displayName", "별칭"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("ROLE_USER + CSRF — PATCH /ui/documents/x/display-name → 403")
    void wrongRole_updateDisplayName_forbidden() throws Exception {
        mvc.perform(patch("/ui/documents/x/display-name").with(csrf()).with(user("someone").roles("USER"))
                        .param("displayName", "별칭"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ROLE_ADMIN + CSRF — PATCH /ui/documents/x/display-name → 200 통과")
    void adminRole_updateDisplayName_succeeds() throws Exception {
        when(ragService.updateDisplayName(any(), any(), any())).thenReturn(
                new com.example.ragagent.model.DocumentInfo("x", "file.md", "별칭", "latest", 1,
                        "2026-08-12T00:00:00Z", "sha", List.of(), List.of()));

        mvc.perform(patch("/ui/documents/x/display-name").with(csrf()).with(user("admin").roles("ADMIN"))
                        .param("displayName", "별칭"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("ROLE_USER GET /ui/documents/x/display-name/edit — 403")
    void wrongRole_editDisplayNameForm_forbidden() throws Exception {
        mvc.perform(get("/ui/documents/x/display-name/edit").with(user("someone").roles("USER")))
                .andExpect(status().isForbidden());
    }

    // ── 열린 표면: 익명이어도 조회는 그대로 동작 ────────────────────────────────

    @Test
    @DisplayName("익명 GET /documents — 200 (조회는 게스트에게 열려 있음)")
    void anonymousDocumentsPage_staysOpen() throws Exception {
        mvc.perform(get("/documents")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("익명 GET /ui/documents/list — 200")
    void anonymousDocumentList_staysOpen() throws Exception {
        mvc.perform(get("/ui/documents/list")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("익명 GET /api/v1/documents — 200")
    void anonymousDocumentsApi_staysOpen() throws Exception {
        mvc.perform(get("/api/v1/documents")).andExpect(status().isOk());
    }

    // ── 청크 추가 게시판 ────────────────────────────────────────────────────

    @Test
    @DisplayName("익명 GET /curated/submissions — 200 (제안 등록/조회는 게스트에게 열려 있음)")
    void anonymousSubmissionPage_staysOpen() throws Exception {
        when(submissionService.listMine(anyString(), anyInt(), anyInt())).thenReturn(List.of());
        when(submissionService.chunkSizeForBody()).thenReturn(800);

        mvc.perform(get("/curated/submissions")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("익명 POST /curated/submissions — CSRF 토큰이 있으면 200대 (게스트도 제안 가능)")
    void anonymousSubmissionPost_allowedWithCsrf() throws Exception {
        mvc.perform(post("/curated/submissions").with(csrf())
                        .param("title", "제목").param("body", "본문"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("익명 GET /admin/submissions/pending-count — 로그인으로 리다이렉트 (대기 건수 유출 방지)")
    void anonymousPendingCount_isGated() throws Exception {
        mvc.perform(get("/admin/submissions/pending-count"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("ROLE_USER GET /admin/submissions/pending-count — 403 (게스트 principal은 통과 불가)")
    void guestPendingCount_isForbidden() throws Exception {
        mvc.perform(get("/admin/submissions/pending-count").with(user("guest").roles("USER")))
                .andExpect(status().isForbidden());
    }

    /**
     * §6.25 — 대화 목록은 전 사용자의 대화 제목이라, 이 엔드포인트가 게스트에게 열리면
     * 배포의 모든 대화 제목이 그대로 유출된다. {@code /api/v1/**}(CSRF 면제 + 게스트 개방)이
     * 아니라 {@code /admin/**} 아래 둔 이유가 정확히 이것이므로 그 사실을 고정한다.
     */
    @Test
    @DisplayName("익명 GET /admin/threads — 로그인으로 리다이렉트 (전 사용자 대화 제목 유출 방지)")
    void anonymousThreadPanel_isGated() throws Exception {
        mvc.perform(get("/admin/threads"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("ROLE_USER GET /admin/threads — 403 (게스트 principal은 통과 불가)")
    void guestThreadPanel_isForbidden() throws Exception {
        mvc.perform(get("/admin/threads").with(user("guest").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ROLE_USER GET /admin/threads/{id}/turns — 403 (드릴다운도 같은 게이트)")
    void guestThreadTurns_isForbidden() throws Exception {
        mvc.perform(get("/admin/threads/t1/turns").with(user("guest").roles("USER")))
                .andExpect(status().isForbidden());
    }

    /**
     * 남의 대화를 되돌릴 수 없게 지우는 엔드포인트다 — 게이트가 이 테스트 하나에 걸려 있다.
     * {@code .hasRole("ADMIN")}이지 {@code .authenticated()}가 아니어야 하는 이유이기도 하다:
     * {@code NoAuthAutoLoginFilter}의 게스트 principal 자체가 인증된 ROLE_USER 라서
     * {@code .authenticated()} 였다면 여기를 그냥 통과한다.
     */
    @Test
    @DisplayName("ROLE_USER DELETE /admin/threads/{id} — CSRF가 있어도 403")
    void guestDeleteThread_isForbidden() throws Exception {
        mvc.perform(delete("/admin/threads/t1").with(csrf())
                        .with(user("guest").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("익명 DELETE /admin/threads/{id} — 로그인으로 리다이렉트")
    void anonymousDeleteThread_isGated() throws Exception {
        mvc.perform(delete("/admin/threads/t1").with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("ROLE_ADMIN DELETE /admin/threads/{id} — CSRF 없으면 403 (파괴적 작업에 CSRF 필수)")
    void adminDeleteThreadWithoutCsrf_isForbidden() throws Exception {
        mvc.perform(delete("/admin/threads/t1").with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    /**
     * §6.25 결정 3 — 이 엔드포인트가 게스트에게 열리면 "관리자만, 기록을 남기고" 라는 열람 조건이
     * 통째로 무의미해진다. 남의 답변 전문이 나가는 유일한 경로라 게이트를 따로 고정한다.
     */
    @Test
    @DisplayName("ROLE_USER GET /admin/threads/turns/{id}/content — 403 (원문 열람은 관리자 전용)")
    void guestTurnContent_isForbidden() throws Exception {
        mvc.perform(get("/admin/threads/turns/7/content").with(user("guest").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("익명 GET /admin/threads/turns/{id}/content — 로그인으로 리다이렉트")
    void anonymousTurnContent_isGated() throws Exception {
        mvc.perform(get("/admin/threads/turns/7/content"))
                .andExpect(status().is3xxRedirection());
    }

    /**
     * 양성 대조 — 위 403들이 "게이트가 막았다"인지 "그런 엔드포인트가 없다"인지 구분한다.
     * 서비스 목이 빈 값을 주므로 컨트롤러까지 도달했다면 404 다: 403 이 아니라는 것이 요지.
     */
    @Test
    @DisplayName("ROLE_ADMIN DELETE /admin/threads/{id} — CSRF 포함 시 컨트롤러까지 도달한다")
    void adminDeleteThread_reachesController() throws Exception {
        when(threadAdminService.delete("t1")).thenReturn(java.util.Optional.empty());

        mvc.perform(delete("/admin/threads/t1").with(csrf())
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("ROLE_ADMIN POST /admin/submissions/{id}/approve — CSRF 포함 시 통과")
    void adminApproveSubmission_succeeds() throws Exception {
        when(submissionService.approve(anyLong(), anyString(), any(), any(), any()))
                .thenReturn(Optional.of(55L));

        mvc.perform(post("/admin/submissions/1/approve").with(csrf())
                        .with(user("admin").roles("ADMIN"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"제목\",\"body\":\"본문\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("ROLE_USER POST /admin/submissions/{id}/approve — CSRF가 있어도 403")
    void guestApproveSubmission_isForbidden() throws Exception {
        mvc.perform(post("/admin/submissions/1/approve").with(csrf())
                        .with(user("guest").roles("USER"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("익명 POST /api/v1/documents/sync — CSRF 없이도 200 (curl 자동화 보존, 확인된 범위 제외)")
    void anonymousDocumentsSyncApi_worksWithoutCsrf() throws Exception {
        when(ragService.syncDirectory(any(), any())).thenReturn(new SyncResult(List.of(), List.of(), List.of()));

        mvc.perform(post("/api/v1/documents/sync").param("version", "latest"))
                .andExpect(status().isOk());
    }

    // ── 세션 왕복: IF_REQUIRED가 실제로 로그인 상태를 유지하는지 ─────────────────

    @Test
    @DisplayName("POST /login 성공 후 같은 세션으로 GET /admin 재요청 — 200 (세션 지속 증명)")
    void adminLogin_thenReuseSession_reachesAdminPage() throws Exception {
        when(userDetailsService.loadUserByUsername("admin@local")).thenReturn(adminUser());
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        var loginResult = mvc.perform(post("/login")
                        .param("username", "admin@local")
                        .param("password", "whatever")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> {
                    String loc = result.getResponse().getRedirectedUrl();
                    org.assertj.core.api.Assertions.assertThat(loc).isEqualTo("/");
                })
                .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        org.assertj.core.api.Assertions.assertThat(session)
                .as("formLogin success must persist the Authentication into a session under IF_REQUIRED")
                .isNotNull();

        mvc.perform(get("/admin").session(session))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("로그인 후 POST /logout — 세션 무효화 + /login?logout 리다이렉트, 같은 세션으로 GET /admin 재요청은 다시 거부")
    void adminLogout_invalidatesSession_thenAdminAccessDeniedAgain() throws Exception {
        when(userDetailsService.loadUserByUsername("admin@local")).thenReturn(adminUser());
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        var loginResult = mvc.perform(post("/login")
                        .param("username", "admin@local")
                        .param("password", "whatever")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        org.assertj.core.api.Assertions.assertThat(session).isNotNull();

        // sanity check — still admin right before logout
        mvc.perform(get("/admin").session(session)).andExpect(status().isOk());

        mvc.perform(post("/logout").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> {
                    String loc = result.getResponse().getRedirectedUrl();
                    org.assertj.core.api.Assertions.assertThat(loc).isEqualTo("/login?logout");
                });

        org.assertj.core.api.Assertions.assertThat(session.isInvalid())
                .as("POST /logout must invalidate the session (invalidateHttpSession(true))")
                .isTrue();

        mvc.perform(get("/admin").session(session))
                .andExpect(status().is3xxRedirection());
    }
}
