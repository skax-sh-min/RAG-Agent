package com.example.ragagent.security;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.ratelimit.RateLimitFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.lang.Nullable;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.session.HttpSessionEventPublisher;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final AppProperties props;

    public SecurityConfig(AppProperties props) {
        this.props = props;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
                                    @Autowired(required = false) @Nullable RateLimitFilter rateLimitFilter,
                                    @Autowired(required = false) @Nullable NoAuthAutoLoginFilter noAuthFilter) throws Exception {

        // Common security headers
        http.headers(headers -> headers
            .contentSecurityPolicy(csp -> csp.policyDirectives(
                "default-src 'self'; " +
                "img-src 'self' data:; " +
                "script-src 'self' 'unsafe-inline'; " +
                "style-src 'self' 'unsafe-inline'; " +
                "connect-src 'self'"
            ))
            .frameOptions(f -> f.sameOrigin())
            .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
        );

        var authCfg = (props != null) ? props.authSafe() : null;
        if (authCfg != null && !authCfg.enabled() && authCfg.managementOnly()) {
            // §6.17 B안 — management-only mode: chat/browsing stay guest-open (no login), but
            // /admin/** and the document-management write UI require a real login against the
            // /setup admin account. formLogin() needs a real session to persist the authenticated
            // context across requests, so unlike plain no-auth this branch cannot be STATELESS —
            // IF_REQUIRED creates one only when something actually needs it (a successful login),
            // not for anonymous/guest traffic. CookieCsrfTokenRepository keeps CSRF correctness
            // decoupled from that session lifecycle. /api/v1/** stays CSRF-exempt, same as the
            // full-auth branch below, so a scripted client can call it without a token — but the
            // document-write endpoints among them are ROLE_ADMIN all the same
            // (gateDocumentManagement), so such a script authenticates via /login first
            // (OPERATOR_MANUAL.md). Only REST reads and chat stay guest-open.
            http
                .csrf(csrf -> csrf
                    .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .ignoringRequestMatchers("/api/v1/**"))
                .sessionManagement(s -> s
                    .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                    .sessionFixation().migrateSession()
                    .maximumSessions(3)
                )
                .authorizeHttpRequests(auth -> { auth
                    .requestMatchers("/login", "/error").permitAll()
                    .requestMatchers("/webjars/**", "/css/**", "/js/**", "/images/**", "/favicon.ico").permitAll()
                    .requestMatchers("/manifest.webmanifest", "/sw.js", "/offline.html", "/icons/**").permitAll()
                    .requestMatchers("/actuator/health", "/api/v1/health").permitAll()
                    // 게스트에게 열린 배포다 — /actuator/loggers 를 그대로 두면 방문자 누구나
                    // 로그 레벨을 TRACE 로 올려 프롬프트 전문(검색된 문서 본문 포함)을 로그 파일에
                    // 쌓게 할 수 있다. 평문 no-auth 분기에는 이 규칙을 두지 않았다: 그쪽은
                    // /admin 까지 관리자가 자동 주입되는 폐쇄망 단일 운영자 전제라,
                    // 여기만 막는 것이 두 모드의 경계와 일치한다.
                    .requestMatchers("/actuator/**").hasRole("ADMIN")
                    // Deliberately .hasRole("ADMIN"), not .authenticated() — NoAuthAutoLoginFilter's
                    // GUEST_PRINCIPAL is a real (non-anonymous) authenticated principal with ROLE_USER,
                    // so .authenticated() would silently accept it if this matcher list and the
                    // filter's own gated-path list (isGatedManagementPath()) ever drift apart. hasRole
                    // fails safe in that scenario instead of silently granting access.
                    .requestMatchers("/admin/**").hasRole("ADMIN");
                    gateDocumentManagement(auth);
                    auth.anyRequest().permitAll();     // /setup, /signup, /documents, /ui/documents/list,
                                                       // tags/view, 나머지 /api/v1/**, chat — all guest-open
                })
                .formLogin(form -> form
                    .loginPage("/login")
                    .loginProcessingUrl("/login")
                    .defaultSuccessUrl("/", true)
                    .failureUrl("/login?error")
                )
                .logout(logout -> logout
                    .logoutUrl("/logout")
                    .logoutSuccessUrl("/login?logout")
                    .invalidateHttpSession(true)
                    .deleteCookies("JSESSIONID")
                );
            if (noAuthFilter != null) {
                http.addFilterBefore(noAuthFilter, AuthorizationFilter.class);
            }
        } else if (authCfg != null && !authCfg.enabled()) {
            // No-auth mode: stateless, all requests permitted, CSRF disabled
            http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            if (noAuthFilter != null) {
                http.addFilterBefore(noAuthFilter, AuthorizationFilter.class);
            }
        } else {
            // Normal auth mode
            http
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/v1/**"))
                .authorizeHttpRequests(auth -> { auth
                    // /setup 은 이 모드에서도 열려 있어야 한다 — 관리자를 만드는 유일한 경로이고
                    // (AuthController.createAdminUser 를 부르는 곳이 거기뿐이다), /signup 은
                    // ROLE_USER 만 만든다. 이게 없으면 아래 ROLE_ADMIN 게이트들이 <b>아무도</b>
                    // 통과할 수 없는 문이 된다. 페이지 자체가 "관리자가 이미 있으면 리다이렉트"로
                    // 스스로를 닫으므로 최초 1회만 열린다(평문 no-auth 모드와 같은 부트스트랩).
                    .requestMatchers("/login", "/signup", "/setup", "/error").permitAll()
                    .requestMatchers("/webjars/**", "/css/**", "/js/**", "/images/**", "/favicon.ico").permitAll()
                    .requestMatchers("/manifest.webmanifest", "/sw.js", "/offline.html", "/icons/**").permitAll()
                    .requestMatchers("/actuator/health", "/api/v1/health").permitAll()
                    // §6.19.2 — /admin/** 전체가 ROLE_ADMIN 이다. 예전엔 §6.8 의
                    // DELETE /admin/llm-usage/** 하나만 게이트하고 나머지는 "로그인한 사용자 누구나"
                    // 였는데, 그 사이 /admin 아래로 들어온 것들이 그 가정을 무너뜨렸다 —
                    // /admin/threads 는 전 사용자의 대화 전문을 읽고 지우며,
                    // /admin/settings/update 는 런타임 설정을, /admin/submissions/{id}/approve 는
                    // 검색 코퍼스를 바꾼다(§10.11 의 "유일한 문"). /signup 이 permitAll 이므로
                    // 게이트가 없으면 가입만 하면 관리자가 된다. 두 no-auth 계열 모드는 이
                    // 분기를 타지 않는다 — 위의 두 분기가 각자 처리한다.
                    .requestMatchers("/admin/**").hasRole("ADMIN")
                    // 런타임 로그 레벨 변경(POST /actuator/loggers/{name})은 관리 행위다 —
                    // TRACE 로 올리면 LlmCurlLogger 가 검색된 문서 본문이 실린 프롬프트 전문을
                    // 로그 파일에 남긴다. /actuator/health 는 위에서 이미 permitAll 이라
                    // 이 규칙에 걸리지 않는다.
                    .requestMatchers("/actuator/**").hasRole("ADMIN");
                    // 문서 관리는 management-only 모드와 <b>같은</b> 게이트를 받는다. 예전에는 이
                    // 분기에만 그 규칙이 없어서, /signup 이 permitAll 인 배포에서 <b>가입만 하면</b>
                    // 문서를 올리고 지우고 통째로 내보낼 수 있었다 — 바로 위 주석이 /admin/** 에
                    // 대해 지적한 것과 같은 구멍이 문서 쪽에 남아 있었던 셈이다.
                    gateDocumentManagement(auth);
                    auth.anyRequest().authenticated();
                })
                .formLogin(form -> form
                    .loginPage("/login")
                    .loginProcessingUrl("/login")
                    .defaultSuccessUrl("/", true)
                    .failureUrl("/login?error")
                )
                .logout(logout -> logout
                    .logoutUrl("/logout")
                    .logoutSuccessUrl("/login?logout")
                    .invalidateHttpSession(true)
                    .deleteCookies("JSESSIONID")
                )
                .sessionManagement(session -> session
                    .sessionFixation().migrateSession()
                    .maximumSessions(3)
                );
        }

        if (rateLimitFilter != null) {
            http.addFilterBefore(rateLimitFilter, AuthorizationFilter.class);
        }

        return http.build();
    }

    /**
     * 문서 관리 표면 — <b>인증이 있는 두 모드가 같은 목록을 쓴다</b>(management-only, full-auth).
     *
     * <p>한 곳에 있는 이유는 예전에 갈라져 있었기 때문이다: management-only 에만 이 규칙이 있고
     * full-auth 는 {@code .authenticated()} 로 끝나서, {@code /signup} 이 permitAll 인 그 모드에서는
     * <b>가입만 하면</b> 문서를 올리고 지우고 내보낼 수 있었다.
     *
     * <p><b>메서드까지 못 박는다.</b> 경로만 보는 {@code "/ui/documents/*"} 는 한 세그먼트짜리
     * {@code "/ui/documents/list"}(게스트·일반 사용자에게 열려 있어야 하는 목록 갱신)까지 삼킨다.
     *
     * <p>{@code export} 는 읽기지만 여기 있다 — 문서 전문을 요청 하나로 복원해 돌려주는 벌크 반출이라,
     * 열람(한 번에 청크 몇 개)과 성격이 다르다.
     *
     * <p><b>{@code /api/v1/documents} 쓰기도 같은 게이트를 받는다.</b> 예전에는 curl 자동화를 위해
     * 열어 뒀는데, 그러면 바로 위 UI 게이트가 장식이 된다 —
     * {@code curl -X DELETE .../api/v1/documents/{id}} 한 줄이 그대로 우회했다. 자동화는 {@code /login}
     * 으로 세션 쿠키를 받아 쓰면 그대로 동작하므로(OPERATOR_MANUAL), 없어지는 것은 기능이 아니라
     * '인증 없이도 된다'는 지름길뿐이다. 읽기({@code GET /api/v1/documents}, {@code /api/v1/chat},
     * 태그·이미지 조회)는 건드리지 않는다.
     *
     * <p>이 REST 경로들은 {@code NoAuthAutoLoginFilter.isGatedManagementPath()} 에 <b>일부러</b>
     * 넣지 않았다. 거기 넣으면 익명으로 남아 {@code /login} 으로 302 가 되는데, API 호출자에게는
     * 로그인 페이지 HTML 보다 403 이 맞는 응답이다. 게스트 principal 이 주입돼도 그것은
     * {@code ROLE_USER} 라 {@code hasRole("ADMIN")} 이 막는다 — 두 목록이 어긋나도 안전하게
     * 실패한다는 것이 바로 이 경우다.
     */
    private static void gateDocumentManagement(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry auth) {
        auth
            .requestMatchers(HttpMethod.POST, "/ui/documents/upload").hasRole("ADMIN")
            .requestMatchers(HttpMethod.POST, "/ui/documents/progress/*/cancel").hasRole("ADMIN")
            .requestMatchers(HttpMethod.DELETE, "/ui/documents/*").hasRole("ADMIN")
            .requestMatchers(HttpMethod.PATCH, "/ui/documents/*/tags").hasRole("ADMIN")
            .requestMatchers(HttpMethod.GET, "/ui/documents/*/tags/edit").hasRole("ADMIN")
            .requestMatchers(HttpMethod.PATCH, "/ui/documents/*/display-name").hasRole("ADMIN")
            .requestMatchers(HttpMethod.GET, "/ui/documents/*/display-name/edit").hasRole("ADMIN")
            .requestMatchers(HttpMethod.GET, "/ui/documents/*/export").hasRole("ADMIN")
            .requestMatchers(HttpMethod.POST, "/api/v1/documents").hasRole("ADMIN")
            .requestMatchers(HttpMethod.POST, "/api/v1/documents/sync").hasRole("ADMIN")
            .requestMatchers(HttpMethod.DELETE, "/api/v1/documents/*").hasRole("ADMIN");
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /** Required for Spring Security maximumSessions() to track session lifecycle. */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }
}
