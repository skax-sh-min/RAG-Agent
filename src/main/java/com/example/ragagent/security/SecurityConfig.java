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
            // full-auth branch below, so scripted uploads/sync (documented in OPERATOR_MANUAL.md)
            // keep working unauthenticated — only the web UI surface is gated.
            http
                .csrf(csrf -> csrf
                    .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .ignoringRequestMatchers("/api/v1/**"))
                .sessionManagement(s -> s
                    .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                    .sessionFixation().migrateSession()
                    .maximumSessions(3)
                )
                .authorizeHttpRequests(auth -> auth
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
                    // Document-management write surface — gated. Method-scoped: a bare "/ui/documents/*"
                    // pattern would also match "/ui/documents/list" (a single path segment), so every
                    // entry here pins the HTTP method too, not just the path.
                    .requestMatchers(HttpMethod.POST, "/ui/documents/upload").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.POST, "/ui/documents/progress/*/cancel").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.DELETE, "/ui/documents/*").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.PATCH, "/ui/documents/*/tags").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.GET, "/ui/documents/*/tags/edit").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.PATCH, "/ui/documents/*/display-name").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.GET, "/ui/documents/*/display-name/edit").hasRole("ADMIN")
                    // Export is a read, but it hands back the document's full reconstructed content
                    // in one request — a bulk-extraction capability guest chat/browsing doesn't
                    // provide — so it is gated with the management surface rather than left open.
                    .requestMatchers(HttpMethod.GET, "/ui/documents/*/export").hasRole("ADMIN")
                    // Deliberately .hasRole("ADMIN"), not .authenticated() — NoAuthAutoLoginFilter's
                    // GUEST_PRINCIPAL is a real (non-anonymous) authenticated principal with ROLE_USER,
                    // so .authenticated() would silently accept it if this matcher list and the
                    // filter's own gated-path list (isGatedManagementPath()) ever drift apart. hasRole
                    // fails safe in that scenario instead of silently granting access.
                    .requestMatchers("/admin/**").hasRole("ADMIN")
                    .anyRequest().permitAll()          // /setup, /signup, /documents, /ui/documents/list,
                                                        // tags/view, /api/v1/**, chat — all guest-open
                )
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
                .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/login", "/signup", "/error").permitAll()
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
                    .requestMatchers("/actuator/**").hasRole("ADMIN")
                    .anyRequest().authenticated()
                )
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
