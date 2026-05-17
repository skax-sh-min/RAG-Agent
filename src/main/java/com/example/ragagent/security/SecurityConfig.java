package com.example.ragagent.security;

import com.example.ragagent.ratelimit.RateLimitFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
                                    @Autowired(required = false) @Nullable RateLimitFilter rateLimitFilter) throws Exception {
        http
            // CSRF: REST API는 stateless 토큰 기반(추후). HTMX UI는 토큰 필수.
            .csrf(csrf -> csrf
                .ignoringRequestMatchers("/api/v1/**")
            )
            // 보안 헤더
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp
                    .policyDirectives(
                        "default-src 'self'; " +
                        "img-src 'self' data:; " +
                        "script-src 'self' 'unsafe-inline'; " +
                        "style-src 'self' 'unsafe-inline'; " +
                        "connect-src 'self'"
                    )
                    .reportOnly()   // Phase 1: Report-Only. 1주 운영 후 enforce로 전환.
                )
                .frameOptions(f -> f.sameOrigin())
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31_536_000))
            )
            // 인증 없음 — 모든 요청 허용 (향후 18-extension-roadmap.md LOGIN 도입 시 수정)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/webjars/**", "/css/**", "/js/**", "/images/**", "/favicon.ico").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().permitAll()
            )
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable());

        if (rateLimitFilter != null) {
            http.addFilterBefore(rateLimitFilter, AuthorizationFilter.class);
        }

        return http.build();
    }
}
