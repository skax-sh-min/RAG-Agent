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
                    // Document-management write surface — gated. Method-scoped: a bare "/ui/documents/*"
                    // pattern would also match "/ui/documents/list" (a single path segment), so every
                    // entry here pins the HTTP method too, not just the path.
                    .requestMatchers(HttpMethod.POST, "/ui/documents/upload").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.POST, "/ui/documents/progress/*/cancel").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.DELETE, "/ui/documents/*").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.PATCH, "/ui/documents/*/tags").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.GET, "/ui/documents/*/tags/edit").hasRole("ADMIN")
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
                    // §6.8 — destructive orphan-usage cleanup, admin only. Scoped narrowly (not
                    // all of /admin/**) so existing /admin/** endpoints keep their current
                    // "any authenticated user" behavior unchanged.
                    .requestMatchers(HttpMethod.DELETE, "/admin/llm-usage/**").hasRole("ADMIN")
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
