package com.example.ragagent.ratelimit;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.security.ClientIpResolver;
import com.example.ragagent.security.CurrentUser;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RateLimitFilterTest {

    private AppProperties appProperties;
    private CurrentUser currentUser;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        appProperties = mock(AppProperties.class);
        currentUser = mock(CurrentUser.class);
        when(currentUser.isAuthenticated()).thenReturn(false);
        filter = new RateLimitFilter(appProperties, currentUser, new ClientIpResolver(false));
    }

    private AppProperties.RateLimitConfig cfg(boolean enabled, int chat, int upload, int sync, int image, int def) {
        return new AppProperties.RateLimitConfig(enabled, chat, upload, sync, image, def);
    }

    @Test
    @DisplayName("disabled 시 모든 요청 통과")
    void disabled_passes_all_requests() throws Exception {
        when(appProperties.rateLimitSafe()).thenReturn(cfg(false, 60, 10, 2, 300, 120));
        MockHttpServletRequest req = chatRequest("127.0.0.1");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        verify(chain).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("policyFor — 경로별 정책 분류")
    void policyFor_routes_correctly() {
        assertThat(filter.policyFor(reqFor("/ui/chat"))).isEqualTo("chat");
        assertThat(filter.policyFor(reqFor("/api/v1/chat"))).isEqualTo("chat");
        assertThat(filter.policyFor(reqFor("/ui/chat/stream"))).isEqualTo("chat");
        assertThat(filter.policyFor(reqFor("POST", "/ui/documents/sync"))).isEqualTo("sync");
        assertThat(filter.policyFor(reqFor("POST", "/api/v1/documents/sync"))).isEqualTo("sync");
        assertThat(filter.policyFor(reqFor("POST", "/ui/documents/upload"))).isEqualTo("upload");
        assertThat(filter.policyFor(reqFor("POST", "/api/v1/documents"))).isEqualTo("upload");
        // 읽기는 같은 경로라도 업로드 버킷이 아니다
        assertThat(filter.policyFor(reqFor("GET", "/api/v1/documents"))).isEqualTo("default");
        assertThat(filter.policyFor(reqFor("/api/v1/images/foo.png"))).isEqualTo("image");
        assertThat(filter.policyFor(reqFor("/actuator/health"))).isEqualTo("default");

        // 업로드 버킷은 분당 10 이라, 업로드가 아닌 문서 화면 요청까지 여기서 토큰을 먹으면
        // 파일 10개를 한 번에 올리는 정상 사용이 마지막 파일에서 429 로 죽는다.
        assertThat(filter.policyFor(reqFor("GET", "/documents"))).isEqualTo("default");
        assertThat(filter.policyFor(reqFor("GET", "/ui/documents/list"))).isEqualTo("default");
        assertThat(filter.policyFor(reqFor("GET", "/ui/documents/doc_a/export"))).isEqualTo("default");
        assertThat(filter.policyFor(reqFor("GET", "/ui/documents/doc_a/tags/edit"))).isEqualTo("default");
        assertThat(filter.policyFor(reqFor("DELETE", "/ui/documents/doc_a"))).isEqualTo("default");
    }

    @Test
    @DisplayName("한도 초과 시 429 + Retry-After 헤더")
    void blocks_after_limit_with_429_and_retry_after() throws Exception {
        int limit = 3;
        when(appProperties.rateLimitSafe()).thenReturn(cfg(true, limit, 10, 2, 300, 120));

        for (int i = 0; i < limit; i++) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilterInternal(chatRequest("10.0.0.1"), res, mock(FilterChain.class));
            assertThat(res.getStatus()).isEqualTo(200);
            assertThat(res.getHeader("X-RateLimit-Remaining")).isNotNull();
        }

        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilterInternal(chatRequest("10.0.0.1"), res, chain);

        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getHeader("Retry-After")).isNotNull();
        assertThat(res.getHeader("X-RateLimit-Remaining")).isNull();
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("다른 IP는 독립적인 버킷 사용")
    void different_ips_have_separate_buckets() throws Exception {
        int limit = 2;
        when(appProperties.rateLimitSafe()).thenReturn(cfg(true, limit, 10, 2, 300, 120));

        for (String ip : new String[]{"1.2.3.4", "5.6.7.8"}) {
            for (int i = 0; i < limit; i++) {
                MockHttpServletResponse res = new MockHttpServletResponse();
                filter.doFilterInternal(chatRequest(ip), res, mock(FilterChain.class));
                assertThat(res.getStatus()).withFailMessage("ip=%s i=%d expected 200", ip, i).isEqualTo(200);
            }
        }
    }

    @Test
    @DisplayName("인증 사용자는 userId 기준으로 버킷 공유")
    void authenticated_user_keyed_by_userId() throws Exception {
        when(currentUser.isAuthenticated()).thenReturn(true);
        when(currentUser.userId()).thenReturn("user-abc");
        when(appProperties.rateLimitSafe()).thenReturn(cfg(true, 1, 10, 2, 300, 120));

        // 첫 요청: 다른 IP에서도 같은 사용자 → 통과
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        filter.doFilterInternal(chatRequest("192.168.0.1"), res1, mock(FilterChain.class));
        assertThat(res1.getStatus()).isEqualTo(200);

        // 두 번째 요청: IP 달라도 동일 userId → 차단
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        FilterChain chain2 = mock(FilterChain.class);
        filter.doFilterInternal(chatRequest("10.0.0.99"), res2, chain2);
        assertThat(res2.getStatus()).isEqualTo(429);
        verify(chain2, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("PLAN §6.19.3 — 기본값(trust-forwarded-for=false)에서는 XFF를 무시하고 remoteAddr 사용")
    void x_forwarded_for_ignored_by_default() {
        MockHttpServletRequest req = reqFor("/api/v1/chat");
        req.addHeader("X-Forwarded-For", "203.0.113.1, 10.0.0.1");
        req.setRemoteAddr("10.0.0.1");
        when(currentUser.isAuthenticated()).thenReturn(false);

        // 프록시 없는 배포에서 XFF는 클라이언트가 마음대로 바꿀 수 있으므로, 이를 신뢰하면
        // 매 요청 헤더만 바꿔 per-IP 한도를 무한히 리필할 수 있다.
        assertThat(filter.clientKey(req)).isEqualTo("ip:10.0.0.1");
    }

    @Test
    @DisplayName("trust-forwarded-for=true(프록시 뒤)로 옵트인하면 XFF 첫 번째 IP를 사용")
    void x_forwarded_for_used_when_trusted() {
        RateLimitFilter trusting = new RateLimitFilter(appProperties, currentUser, new ClientIpResolver(true));
        MockHttpServletRequest req = reqFor("/api/v1/chat");
        req.addHeader("X-Forwarded-For", "203.0.113.1, 10.0.0.1");
        req.setRemoteAddr("10.0.0.1");
        when(currentUser.isAuthenticated()).thenReturn(false);

        assertThat(trusting.clientKey(req)).isEqualTo("ip:203.0.113.1");
    }

    @Test
    @DisplayName("sync와 upload는 별도 한도 적용")
    void sync_and_upload_use_separate_limits() {
        AppProperties.RateLimitConfig cfg = cfg(true, 60, 10, 2, 300, 120);
        assertThat(filter.limitFor(cfg, "chat")).isEqualTo(60);
        assertThat(filter.limitFor(cfg, "upload")).isEqualTo(10);
        assertThat(filter.limitFor(cfg, "sync")).isEqualTo(2);
        assertThat(filter.limitFor(cfg, "image")).isEqualTo(300);
        assertThat(filter.limitFor(cfg, "default")).isEqualTo(120);
    }

    private MockHttpServletRequest chatRequest(String remoteAddr) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/chat");
        req.setRemoteAddr(remoteAddr);
        return req;
    }

    private MockHttpServletRequest reqFor(String path) {
        return new MockHttpServletRequest("GET", path);
    }

    private MockHttpServletRequest reqFor(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }
}
