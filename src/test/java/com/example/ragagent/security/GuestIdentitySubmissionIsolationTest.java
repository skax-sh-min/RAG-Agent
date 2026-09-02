package com.example.ragagent.security;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.config.AppProperties.GuestIdentity;
import com.example.ragagent.context.ThreadContextResolver;
import com.example.ragagent.controller.CuratedSubmissionController;
import com.example.ragagent.repository.AppSecretRepository;
import com.example.ragagent.service.CuratedSubmissionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 청크 추가 게시판 × 방문자 식별 — end-to-end wiring proof for the deployed configuration
 * ({@code app.auth.enabled=false}, {@code management-only=true}, {@code guest-identity=hybrid}).
 *
 * <p>"내 제안" is only meaningful if each visitor gets their own {@code userId}; that id is derived
 * in a servlet filter but consumed in the controller, so the {@link GuestIdentityResolver} unit
 * tests can't show the board actually separates. Walks the same chain
 * {@link GuestIdentityChatIsolationTest} does — {@link NoAuthAutoLoginFilter} →
 * {@code SecurityContextHolder} → {@link SessionCurrentUser} → {@code CurrentUser.userId()} —
 * and asserts on the userId the service is finally scoped to.
 *
 * <p>Also pins the single-admin case: once that one admin really logs in,
 * {@code NoAuthAutoLoginFilter.hasRealLogin()} must keep the filter from stomping their identity
 * back to a guest id on this (non-gated) page — otherwise the admin's own proposals and read-state
 * would silently merge into whichever guest bucket their browser resolves to.
 */
@WebMvcTest(value = CuratedSubmissionController.class,
        properties = {"app.auth.enabled=false", "app.auth.management-only=true",
                      "app.auth.guest-identity=hybrid"})
@Import({com.example.ragagent.context.WebMvcConfig.class, SecurityConfig.class, NoAuthAutoLoginFilter.class,
        SessionCurrentUser.class, ThreadContextResolver.class,
        GuestIdentitySubmissionIsolationTest.TestConfig.class})
@ResourceLock("global-state")
class GuestIdentitySubmissionIsolationTest {

    private static final String COOKIE = GuestIdentityResolver.COOKIE_NAME;

    private static final AppUserDetails ADMIN =
            new AppUserDetails("admin-id", "admin@local", "hash", "Admin", "ADMIN", true, false);

    @Autowired MockMvc mvc;

    @MockitoBean CuratedSubmissionService service;
    // 본문 이미지 업로드 엔드포인트의 협력자 — @WebMvcTest 는 @Service 를 스캔하지 않으므로
    // 명시하지 않으면 컨트롤러 생성 자체가 실패해 컨텍스트 로드가 깨진다.
    @MockitoBean com.example.ragagent.service.CuratedImageStore imageStore;
    @MockitoBean ChatModel chatModel;

    @TestConfiguration
    static class TestConfig {
        /** Pre-stubbed via @Bean: SecurityConfig reads authSafe() during context refresh, before
         *  @MockitoBean fields are stubbed (same reason as ManagementOnlyAuthorizationTest). */
        @Bean
        AppProperties props() {
            AppProperties props = mock(AppProperties.class);
            when(props.authSafe())
                    .thenReturn(new AppProperties.AuthConfig(false, true, GuestIdentity.HYBRID));
            return props;
        }

        /** Exactly one admin — the deployed situation. */
        @Bean
        SqliteUserDetailsService userDetailsService() {
            SqliteUserDetailsService uds = mock(SqliteUserDetailsService.class);
            when(uds.findFirstAdmin()).thenReturn(Optional.of(ADMIN));
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
        GuestIdentityResolver guestIdentityResolver(AppProperties props, ClientIpResolver ipResolver,
                                                    AppSecretRepository secretRepo) {
            return new GuestIdentityResolver(props, ipResolver, secretRepo);
        }
    }

    @BeforeEach
    void setUp() {
        when(service.chunkSizeForBody()).thenReturn(800);
        when(service.listMine(anyString(), any(), anyInt(), anyInt())).thenReturn(List.of());
    }

    /** All userIds "내 제안" was scoped to, in call order. */
    private List<String> listedUserIds() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(service, atLeastOnce()).listMine(captor.capture(), any(), anyInt(), anyInt());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("서로 다른 방문자는 서로 다른 userId로 '내 제안'을 조회한다")
    void differentVisitorsSeeDifferentSubmissionLists() throws Exception {
        mvc.perform(get("/curated/submissions").cookie(new Cookie(COOKIE, "guest-aaaaaaaaaaaa")))
                .andExpect(status().isOk());
        mvc.perform(get("/curated/submissions").cookie(new Cookie(COOKIE, "guest-bbbbbbbbbbbb")))
                .andExpect(status().isOk());

        assertThat(listedUserIds()).containsExactly("guest-aaaaaaaaaaaa", "guest-bbbbbbbbbbbb");
    }

    @Test
    @DisplayName("같은 방문자는 매 요청 같은 userId — 등록한 제안의 처리 결과를 계속 볼 수 있다")
    void sameVisitorKeepsSeeingOwnSubmissions() throws Exception {
        Cookie cookie = new Cookie(COOKIE, "guest-abcdef123456");

        mvc.perform(get("/curated/submissions").cookie(cookie)).andExpect(status().isOk());
        mvc.perform(post("/curated/submissions").cookie(cookie).with(csrf())
                        .param("title", "제목").param("body", "본문"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/curated/submissions/unread-count").cookie(cookie))
                .andExpect(status().isOk());

        assertThat(listedUserIds()).containsExactly("guest-abcdef123456");
        verify(service).submit("guest-abcdef123456", "제목", "본문", java.util.List.of(), null, null);
        verify(service).countUnreadForAuthor("guest-abcdef123456");
    }

    @Test
    @DisplayName("쿠키 없는 첫 방문도 guest- id로 스코프되고 그 쿠키를 발급받는다")
    void firstVisitGetsDerivedIdAndCookie() throws Exception {
        var result = mvc.perform(get("/curated/submissions"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getHeader("Set-Cookie"))
                .isNotNull().contains(COOKIE + "=" + GuestIdentityResolver.ID_PREFIX);
        assertThat(listedUserIds())
                .allSatisfy(id -> assertThat(id).startsWith(GuestIdentityResolver.ID_PREFIX));
    }

    @Test
    @DisplayName("위조된 쿠키 값은 그대로 userId(작성자 키)가 되지 않는다")
    void malformedCookieIsNotUsedAsAuthorKey() throws Exception {
        mvc.perform(get("/curated/submissions")
                        .cookie(new Cookie(COOKIE, "'; DROP TABLE curated_submission;--")))
                .andExpect(status().isOk());

        assertThat(listedUserIds()).singleElement().satisfies(id -> assertThat(id)
                .isNotEqualTo("'; DROP TABLE curated_submission;--")
                .startsWith(GuestIdentityResolver.ID_PREFIX));
    }

    @Test
    @DisplayName("실제 로그인한 관리자는 게스트 id로 덮어써지지 않는다 (본인 제안이 게스트 함에 섞이지 않음)")
    void realAdminLoginIsNotDowngradedToGuest() throws Exception {
        // 관리자 브라우저에도 방문자 쿠키가 남아 있는 상황 — 그래도 로그인 신원이 이겨야 한다.
        mvc.perform(get("/curated/submissions")
                        .cookie(new Cookie(COOKIE, "guest-abcdef123456"))
                        .with(user(ADMIN)))
                .andExpect(status().isOk());

        assertThat(listedUserIds()).containsExactly("admin-id");
    }
}
