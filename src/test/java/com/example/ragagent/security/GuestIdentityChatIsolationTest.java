package com.example.ragagent.security;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.config.AppProperties.GuestIdentity;
import com.example.ragagent.controller.OperationsController;
import com.example.ragagent.llm.CircuitBreaker;
import com.example.ragagent.llm.BackgroundLlmConcurrencyTracker;
import com.example.ragagent.llm.EmbeddingConcurrencyTracker;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.repository.AppSecretRepository;
import com.example.ragagent.repository.LlmUsageRepository;
import com.example.ragagent.service.CuratedQaService;
import com.example.ragagent.service.MemoryService;
import com.example.ragagent.service.ThreadMetaService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * § 접속자별 채팅 개인화 — end-to-end wiring proof for {@code app.auth.guest-identity}.
 *
 * <p>The per-visitor id is derived in a servlet filter but consumed several layers away, so the unit
 * tests around {@link GuestIdentityResolver} alone can't show that the chat sidebar actually splits.
 * This walks the whole chain the browser walks — {@link NoAuthAutoLoginFilter} →
 * {@code SecurityContextHolder} → {@code SessionCurrentUser} → {@code ThreadContextResolver} →
 * {@code ThreadContext.userId()} → {@code ThreadMetaService.getAll(userId)} — and asserts on the
 * userId the service is finally asked for, which is exactly what scopes the thread list.
 *
 * <p>Deliberately does not go through the chat endpoint: creating a real thread needs a live LLM, and
 * what's under test here is identity routing, not answer generation.
 */
@WebMvcTest(value = OperationsController.class,
        properties = {"app.auth.enabled=false", "app.auth.guest-identity=hybrid"})
@Import({com.example.ragagent.context.WebMvcConfig.class, SecurityConfig.class, NoAuthAutoLoginFilter.class,
        SessionCurrentUser.class, com.example.ragagent.context.ThreadContextResolver.class,
        GuestIdentityChatIsolationTest.TestConfig.class})
@ResourceLock("global-state")
class GuestIdentityChatIsolationTest {

    private static final String COOKIE = GuestIdentityResolver.COOKIE_NAME;

    @Autowired MockMvc mvc;

    @MockitoBean ThreadMetaService threadMetaService;
    @MockitoBean MemoryService memoryService;
    @MockitoBean LlmUsageRepository usageRepo;
    @MockitoBean CircuitBreaker circuitBreaker;
    @MockitoBean ChatModel chatModel;
    @MockitoBean AuditLogger auditLogger;
    @MockitoBean CuratedQaService curatedQaService;
    @MockitoBean LlmRouter llmRouter;
    @MockitoBean EmbeddingConcurrencyTracker embeddingConcurrencyTracker;
    @MockitoBean BackgroundLlmConcurrencyTracker backgroundConcurrencyTracker;

    @TestConfiguration
    static class TestConfig {
        /** Pre-stubbed via @Bean, not @MockitoBean: SecurityConfig reads authSafe() during context
         *  refresh, before @MockitoBean fields are stubbed (same reason as ManagementOnlyAuthorizationTest). */
        @Bean
        AppProperties props() {
            AppProperties props = mock(AppProperties.class);
            when(props.authSafe())
                    .thenReturn(new AppProperties.AuthConfig(false, false, GuestIdentity.HYBRID));
            return props;
        }

        @Bean
        SqliteUserDetailsService userDetailsService() {
            SqliteUserDetailsService uds = mock(SqliteUserDetailsService.class);
            // An admin must exist or the filter redirects everything to /setup before any of this runs.
            when(uds.findFirstAdmin()).thenReturn(Optional.of(
                    new AppUserDetails("admin-id", "admin@local", "hash", "Admin", "ADMIN", true, false)));
            return uds;
        }

        @Bean
        AppSecretRepository appSecretRepository() {
            AppSecretRepository repo = mock(AppSecretRepository.class);
            when(repo.getOrCreate(anyString())).thenReturn("fixed-test-hmac-key".getBytes());
            return repo;
        }

        @Bean
        ClientIpResolver clientIpResolver() {
            return new ClientIpResolver(false);
        }

        @Bean
        GuestIdentityResolver guestIdentityResolver(AppProperties props,
                                                    ClientIpResolver ipResolver,
                                                    AppSecretRepository secretRepo) {
            return new GuestIdentityResolver(props, ipResolver, secretRepo);
        }
    }

    /** All userIds the thread list was scoped to, in call order. */
    private List<String> requestedUserIds() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(threadMetaService, atLeastOnce()).getAll(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("서로 다른 방문자 쿠키는 서로 다른 userId로 스레드 목록을 조회한다")
    void differentVisitorsScopeToDifferentUserIds() throws Exception {
        mvc.perform(get("/ui/threads").cookie(new Cookie(COOKIE, "guest-aaaaaaaaaaaa")))
                .andExpect(status().isOk());
        mvc.perform(get("/ui/threads").cookie(new Cookie(COOKIE, "guest-bbbbbbbbbbbb")))
                .andExpect(status().isOk());

        assertThat(requestedUserIds())
                .containsExactly("guest-aaaaaaaaaaaa", "guest-bbbbbbbbbbbb");
    }

    @Test
    @DisplayName("같은 방문자 쿠키는 매 요청 같은 userId로 조회한다 (이력 유지)")
    void sameVisitorIsStableAcrossRequests() throws Exception {
        Cookie cookie = new Cookie(COOKIE, "guest-abcdef123456");

        mvc.perform(get("/ui/threads").cookie(cookie)).andExpect(status().isOk());
        mvc.perform(get("/ui/threads").cookie(cookie)).andExpect(status().isOk());

        assertThat(requestedUserIds()).containsExactly("guest-abcdef123456", "guest-abcdef123456");
    }

    @Test
    @DisplayName("쿠키 없는 첫 방문은 IP에서 유도된 guest- id로 조회하고 그 쿠키를 발급한다")
    void firstVisitDerivesFromIpAndIssuesCookie() throws Exception {
        var result = mvc.perform(get("/ui/threads"))
                .andExpect(status().isOk())
                .andReturn();

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).isNotNull().contains(COOKIE + "=" + GuestIdentityResolver.ID_PREFIX);

        assertThat(requestedUserIds())
                .allSatisfy(id -> assertThat(id).startsWith(GuestIdentityResolver.ID_PREFIX));
    }

    @Test
    @DisplayName("위조된 쿠키 값은 그대로 userId가 되지 않는다")
    void malformedCookieIsNotUsedAsUserId() throws Exception {
        mvc.perform(get("/ui/threads").cookie(new Cookie(COOKIE, "'; DROP TABLE thread_meta;--")))
                .andExpect(status().isOk());

        assertThat(requestedUserIds())
                .singleElement()
                .satisfies(id -> assertThat(id)
                        .isNotEqualTo("'; DROP TABLE thread_meta;--")
                        .startsWith(GuestIdentityResolver.ID_PREFIX));
    }
}
