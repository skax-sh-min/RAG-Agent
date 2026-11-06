package com.example.ragagent.mcp.server;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.ratelimit.RateLimitFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * MCP 엔드포인트 전용 보안 체인 (AGENT_MCP_DESIGN §4.2). {@code spring.ai.mcp.server.enabled=true} 일 때만 존재한다 — 꺼져 있으면 체인도
 * 필터도 엔드포인트도 없고 앱은 이 기능이 없을 때와 같다.
 *
 * <p><b>기존 세 체인보다 앞선 전용 체인이어야 한다</b>({@code @Order(1)} + {@code securityMatcher}). 그렇지 않으면 이 경로가 모드별 체인의
 * 규칙을 그대로 받는다: 평문 no-auth 는 {@code anyRequest().permitAll()} 이라 <b>무인증으로 열리고</b>, 세션 체인은 CSRF 토큰을 요구하며
 * 로그인 페이지로 리다이렉트한다. 전용 체인은 어느 보안 모드에서도 같다: STATELESS, CSRF 끔, API 키 없으면 401.
 *
 * <p>필터 순서는 <b>레이트리밋 → API 키 → 인가</b>다. 레이트리밋이 키 확인보다 앞이어야 키 추측(401 반복)이 IP 별 {@code mcp} 버킷에 걸린다 —
 * 뒤에 두면 401 은 이미 응답된 뒤라 세지 않는다. {@link McpApiKeyFilter} 는 일부러 <b>빈이 아니다</b>: {@code Filter} 빈은 스프링 부트가
 * 모든 URL 에 서블릿 필터로 등록해 앱 전체가 401 이 된다.
 */
@Configuration
@ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true")
@EnableConfigurationProperties(McpProperties.class)
public class McpSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(McpSecurityConfig.class);

    @Bean
    @Order(1)
    SecurityFilterChain mcpFilterChain(HttpSecurity http, McpProperties props, AuditLogger audit,
                                       @Autowired(required = false) @Nullable RateLimitFilter rateLimitFilter) throws Exception {
        props.validate();
        if (!props.readEnabled() && !props.adminEnabled()) {
            log.warn("[MCP] 서버는 켜져 있지만 API 키가 없다(MCP_READ_API_KEY / MCP_ADMIN_API_KEY) — 모든 요청이 401 이다");
        } else {
            log.info("[MCP] 서버가 켜져 있다 — /api/v1/mcp (읽기 키 {}, 관리자 키 {})",
                    props.readEnabled() ? "있음" : "없음", props.adminEnabled() ? "있음" : "없음");
        }
        McpApiKeyFilter apiKeyFilter = new McpApiKeyFilter(props, audit);
        http.securityMatcher("/api/v1/mcp", "/api/v1/mcp/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("MCP_READ"))
                .addFilterBefore(apiKeyFilter, AuthorizationFilter.class);
        if (rateLimitFilter != null) {
            http.addFilterBefore(rateLimitFilter, McpApiKeyFilter.class);
        }
        return http.build();
    }
}
