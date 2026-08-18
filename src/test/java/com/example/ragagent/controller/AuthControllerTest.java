package com.example.ragagent.controller;

import org.junit.jupiter.api.parallel.ResourceLock;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.context.ThreadContextResolver;
import com.example.ragagent.security.AppUserDetails;
import com.example.ragagent.security.SecurityConfig;
import com.example.ragagent.security.SqliteUserDetailsService;
import org.springframework.ai.chat.model.ChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import org.springframework.test.web.servlet.ResultMatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AuthController 계약 보호 테스트.
 *
 * Covers:
 *  - GET /login 기본 응답
 *  - GET /setup 접근 제어 (auth 모드 / admin 유무)
 *  - POST /signup 입력 검증 (이메일·비밀번호·중복)
 *  - 회귀: 가입 후 기존 세션 무효화
 *  - 회귀: 73자 비밀번호 거부
 */
@WebMvcTest(value = AuthController.class, properties = "app.auth.enabled=true")
@Import({SecurityConfig.class, com.example.ragagent.context.WebMvcConfig.class})
@ResourceLock("global-state")
class AuthControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean SqliteUserDetailsService userDetailsService;
    @MockitoBean PasswordEncoder passwordEncoder;
    @MockitoBean AppProperties props;
    @MockitoBean ThreadContextResolver threadContextResolver;
    @MockitoBean ChatModel chatModel;

    private static final String VALID_PW = "Valid1@pass!";

    private AppUserDetails testUser() {
        return new AppUserDetails("uid", "user@example.com", "hash", "Test", "USER", true, false);
    }

    /** redirect URL의 쿼리 파라미터를 무시하고 경로가 "/" 인지 확인 */
    private static ResultMatcher redirectedToRoot() {
        return result -> {
            String loc = result.getResponse().getRedirectedUrl();
            assertThat(loc).as("redirect location must not be null").isNotNull();
            String path = loc.contains("?") ? loc.substring(0, loc.indexOf('?')) : loc;
            assertThat(path).as("redirect path (query params ignored)").isEqualTo("/");
        };
    }

    @BeforeEach
    void setUp() {
        when(props.authSafe()).thenReturn(new AppProperties.AuthConfig(true, false));
    }

    // ── GET /login ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /login — 200 OK")
    void loginPage_returns200() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/login"));
    }

    @Test
    @DisplayName("GET /login?error — 모델에 error=true 포함")
    void loginPage_withError_addsErrorModel() throws Exception {
        mvc.perform(get("/login").param("error", ""))
                .andExpect(status().isOk())
                .andExpect(model().attribute("error", true));
    }

    @Test
    @DisplayName("GET /login?logout — 모델에 logout=true 포함")
    void loginPage_withLogout_addsLogoutModel() throws Exception {
        mvc.perform(get("/login").param("logout", ""))
                .andExpect(status().isOk())
                .andExpect(model().attribute("logout", true));
    }

    @Test
    @DisplayName("GET /login — no-auth 모드에서는 redirect:/ (CSRF 비활성화로 템플릿이 깨짐 방지)")
    void loginPage_noAuthMode_redirectsToRoot() throws Exception {
        when(props.authSafe()).thenReturn(new AppProperties.AuthConfig(false, false));

        mvc.perform(get("/login"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedToRoot());
    }

    @Test
    @DisplayName("GET /login — §6.17 B안 management-only 모드에서는 200 OK (CSRF 활성이라 템플릿이 안전함)")
    void loginPage_managementOnlyMode_returns200() throws Exception {
        when(props.authSafe()).thenReturn(new AppProperties.AuthConfig(false, true));

        mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/login"));
    }

    /** auth.enabled=false 에서는 가입할 계정이라는 개념 자체가 없다(관리자 계정은 /setup 에서
     *  만든다). 회원가입 링크를 그대로 두면 존재하지 않는 흐름으로 보내게 된다. 문구가 아니라
     *  링크 대상으로 검증하는 이유는 테스트 로케일(ko/en)에 좌우되지 않게 하기 위함. */
    @Test
    @DisplayName("GET /login — auth 비활성(management-only) 시 회원가입 링크 대신 홈 링크가 나온다")
    void loginPage_authDisabled_showsHomeLinkInsteadOfSignup() throws Exception {
        when(props.authSafe()).thenReturn(new AppProperties.AuthConfig(false, true));

        String html = mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("/signup");
        assertThat(html).contains("href=\"/\"");
    }

    @Test
    @DisplayName("GET /login — auth 활성 시에는 회원가입 링크가 그대로 있다")
    void loginPage_authEnabled_keepsSignupLink() throws Exception {
        String html = mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("/signup");
    }

    @Test
    @DisplayName("GET /signup — no-auth 모드에서는 redirect:/ (CSRF 비활성화로 템플릿이 깨짐 방지)")
    void signupPage_noAuthMode_redirectsToRoot() throws Exception {
        when(props.authSafe()).thenReturn(new AppProperties.AuthConfig(false, false));

        mvc.perform(get("/signup"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedToRoot());
    }

    @Test
    @DisplayName("GET /signup — management-only 모드에서도 자체 가입은 계속 차단(redirect:/) — 관리자 1명만 로그인 가능")
    void signupPage_managementOnlyMode_stillRedirectsToRoot() throws Exception {
        when(props.authSafe()).thenReturn(new AppProperties.AuthConfig(false, true));

        mvc.perform(get("/signup"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedToRoot());
    }

    // ── GET /setup ──────────────────────────────────────────────────────────

    @Test
    @WithMockUser
    @DisplayName("GET /setup — auth.enabled=true 시 redirect:/")
    void setupPage_authEnabled_redirectsToRoot() throws Exception {
        mvc.perform(get("/setup"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedToRoot());
    }

    @Test
    @WithMockUser
    @DisplayName("GET /setup — auth.enabled=false + admin 존재 시 redirect:/")
    void setupPage_adminExists_redirectsToRoot() throws Exception {
        when(props.authSafe()).thenReturn(new AppProperties.AuthConfig(false, false));
        when(userDetailsService.findFirstAdmin()).thenReturn(Optional.of(testUser()));

        mvc.perform(get("/setup"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedToRoot());
    }

    @Test
    @WithMockUser
    @DisplayName("GET /setup — auth.enabled=false + admin 없을 때 setup 페이지 반환")
    void setupPage_noAdmin_returnsSetupView() throws Exception {
        when(props.authSafe()).thenReturn(new AppProperties.AuthConfig(false, false));
        when(userDetailsService.findFirstAdmin()).thenReturn(Optional.empty());

        mvc.perform(get("/setup"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/setup"));
    }

    // ── POST /signup 입력 검증 ───────────────────────────────────────────────

    @Test
    @DisplayName("POST /signup — 이메일 형식 불일치 → redirect:/signup + 에러")
    void signup_invalidEmail_rejectsWithError() throws Exception {
        mvc.perform(post("/signup")
                        .param("email", "not-an-email")
                        .param("password", VALID_PW)
                        .param("passwordConfirm", VALID_PW)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/signup*"))
                .andExpect(flash().attribute("error", "auth.signup.error.email.invalid"));
    }

    @Test
    @DisplayName("POST /signup — 이메일 중복 → redirect:/signup + 에러")
    void signup_duplicateEmail_rejectsWithError() throws Exception {
        when(userDetailsService.emailExists(anyString())).thenReturn(true);

        mvc.perform(post("/signup")
                        .param("email", "taken@example.com")
                        .param("password", VALID_PW)
                        .param("passwordConfirm", VALID_PW)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/signup*"))
                .andExpect(flash().attribute("error", "auth.signup.error.email.taken"));
    }

    @Test
    @DisplayName("POST /signup — 73자 비밀번호 → 검증 실패")
    void signup_passwordOver72Chars_rejectsWithError() throws Exception {
        String pw73 = "Aa1!" + "x".repeat(69); // 4 + 69 = 73 chars
        when(userDetailsService.emailExists(anyString())).thenReturn(false);

        mvc.perform(post("/signup")
                        .param("email", "user@example.com")
                        .param("password", pw73)
                        .param("passwordConfirm", pw73)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/signup*"))
                .andExpect(flash().attribute("error", "auth.signup.error.password.weak"));
    }

    @Test
    @DisplayName("POST /signup — 72자 비밀번호 → 허용됨")
    void signup_password72Chars_allowed() throws Exception {
        String pw72 = "Aa1!" + "x".repeat(68); // 4 + 68 = 72 chars
        when(userDetailsService.emailExists(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hash");
        when(userDetailsService.loadUserByUsername(anyString())).thenReturn(testUser());

        mvc.perform(post("/signup")
                        .param("email", "user@example.com")
                        .param("password", pw72)
                        .param("passwordConfirm", pw72)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedToRoot());
    }

    @Test
    @DisplayName("POST /signup — 비밀번호 불일치 → redirect:/signup + 에러")
    void signup_passwordMismatch_rejectsWithError() throws Exception {
        when(userDetailsService.emailExists(anyString())).thenReturn(false);

        mvc.perform(post("/signup")
                        .param("email", "user@example.com")
                        .param("password", VALID_PW)
                        .param("passwordConfirm", "Different1@!")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/signup*"))
                .andExpect(flash().attribute("error", "auth.signup.error.password.mismatch"));
    }

    // ── 회귀: 세션 고정 방지 ────────────────────────────────────────────

    @Test
    @DisplayName("POST /signup — 성공 후 기존 세션 무효화")
    void signup_success_invalidatesOldSession() throws Exception {
        when(userDetailsService.emailExists(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hash");
        when(userDetailsService.loadUserByUsername(anyString())).thenReturn(testUser());

        MockHttpSession preSession = new MockHttpSession();

        mvc.perform(post("/signup")
                        .session(preSession)
                        .param("email", "new@example.com")
                        .param("password", VALID_PW)
                        .param("passwordConfirm", VALID_PW)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedToRoot());

        assertThat(preSession.isInvalid())
                .as("기존 세션은 가입 완료 후 무효화돼야 한다")
                .isTrue();
    }
}
