package com.example.ragagent.security;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.config.AppProperties.GuestIdentity;
import com.example.ragagent.repository.AppSecretRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA — 접속자별 채팅 개인화(§ 게스트 식별). no-auth 모드에서 방문자를 구분해
 * {@code ThreadContext.userId()}로 흘려보내는 값이 전략별로 어떻게 정해지는지 고정한다.
 */
class GuestIdentityResolverTest {

    private static final byte[] SECRET = "test-secret-key-material-32bytes".getBytes();

    private final AppSecretRepository secretRepository = mock(AppSecretRepository.class);

    private GuestIdentityResolver resolverFor(String strategy, boolean trustForwardedFor) {
        AppProperties props = mock(AppProperties.class);
        when(props.authSafe()).thenReturn(new AppProperties.AuthConfig(false, false, strategy));
        when(secretRepository.getOrCreate(GuestIdentityResolver.SECRET_NAME)).thenReturn(SECRET);
        return new GuestIdentityResolver(props, new ClientIpResolver(trustForwardedFor), secretRepository);
    }

    private MockHttpServletRequest requestFrom(String remoteAddr) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/chat");
        req.setRemoteAddr(remoteAddr);
        return req;
    }

    private String cookieValue(MockHttpServletResponse res) {
        String header = res.getHeader("Set-Cookie");
        if (header == null) return null;
        return header.substring(header.indexOf('=') + 1, header.indexOf(';'));
    }

    @Nested
    @DisplayName("shared (기본값)")
    class Shared {

        @Test
        @DisplayName("전 방문자가 기존 고정 게스트 id를 그대로 공유한다 (회귀 0)")
        void everyVisitorGetsSameFixedId() {
            GuestIdentityResolver resolver = resolverFor(GuestIdentity.SHARED, false);

            String a = resolver.resolve(requestFrom("1.2.3.4"), new MockHttpServletResponse());
            String b = resolver.resolve(requestFrom("5.6.7.8"), new MockHttpServletResponse());

            assertThat(a).isEqualTo(GuestIdentityResolver.SHARED_ID).isEqualTo(b);
        }

        @Test
        @DisplayName("쿠키를 굽지 않고 비밀키도 읽지 않는다")
        void touchesNothing() {
            GuestIdentityResolver resolver = resolverFor(GuestIdentity.SHARED, false);
            MockHttpServletResponse res = new MockHttpServletResponse();

            resolver.resolve(requestFrom("1.2.3.4"), res);

            assertThat(res.getHeader("Set-Cookie")).isNull();
            verify(secretRepository, never()).getOrCreate(GuestIdentityResolver.SECRET_NAME);
        }
    }

    @Nested
    @DisplayName("ip")
    class Ip {

        @Test
        @DisplayName("같은 IP는 같은 id, 다른 IP는 다른 id")
        void stablePerIp() {
            GuestIdentityResolver resolver = resolverFor(GuestIdentity.IP, false);

            String a1 = resolver.resolve(requestFrom("1.2.3.4"), new MockHttpServletResponse());
            String a2 = resolver.resolve(requestFrom("1.2.3.4"), new MockHttpServletResponse());
            String b = resolver.resolve(requestFrom("5.6.7.8"), new MockHttpServletResponse());

            assertThat(a1).isEqualTo(a2).isNotEqualTo(b);
            assertThat(a1).startsWith(GuestIdentityResolver.ID_PREFIX);
        }

        @Test
        @DisplayName("id에 원문 IP가 들어가지 않는다 (HMAC 해시)")
        void doesNotLeakRawIp() {
            GuestIdentityResolver resolver = resolverFor(GuestIdentity.IP, false);

            String id = resolver.resolve(requestFrom("203.0.113.7"), new MockHttpServletResponse());

            assertThat(id).doesNotContain("203.0.113.7");
        }

        @Test
        @DisplayName("쿠키를 굽지 않는다 (IP 전략은 stateless)")
        void setsNoCookie() {
            GuestIdentityResolver resolver = resolverFor(GuestIdentity.IP, false);
            MockHttpServletResponse res = new MockHttpServletResponse();

            resolver.resolve(requestFrom("1.2.3.4"), res);

            assertThat(res.getHeader("Set-Cookie")).isNull();
        }

        @Test
        @DisplayName("trust-forwarded-for=false면 XFF를 위조해도 id가 바뀌지 않는다")
        void forgedForwardedForCannotImpersonate() {
            GuestIdentityResolver resolver = resolverFor(GuestIdentity.IP, false);

            MockHttpServletRequest honest = requestFrom("10.0.0.1");
            MockHttpServletRequest forged = requestFrom("10.0.0.1");
            forged.addHeader("X-Forwarded-For", "203.0.113.99");

            assertThat(resolver.resolve(forged, new MockHttpServletResponse()))
                    .isEqualTo(resolver.resolve(honest, new MockHttpServletResponse()));
        }

        @Test
        @DisplayName("trust-forwarded-for=true(프록시 뒤)면 XFF 기준으로 방문자가 갈린다")
        void behindProxySplitsByForwardedFor() {
            GuestIdentityResolver resolver = resolverFor(GuestIdentity.IP, true);

            MockHttpServletRequest one = requestFrom("10.0.0.1");   // 프록시 주소는 동일
            one.addHeader("X-Forwarded-For", "203.0.113.1");
            MockHttpServletRequest two = requestFrom("10.0.0.1");
            two.addHeader("X-Forwarded-For", "203.0.113.2");

            assertThat(resolver.resolve(one, new MockHttpServletResponse()))
                    .isNotEqualTo(resolver.resolve(two, new MockHttpServletResponse()));
        }
    }

    @Nested
    @DisplayName("cookie")
    class CookieOnly {

        @Test
        @DisplayName("첫 방문에 새 id를 굽고, 그 쿠키를 다시 보내면 같은 id가 유지된다")
        void mintsThenReuses() {
            GuestIdentityResolver resolver = resolverFor(GuestIdentity.COOKIE, false);

            MockHttpServletResponse first = new MockHttpServletResponse();
            String minted = resolver.resolve(requestFrom("1.2.3.4"), first);
            assertThat(cookieValue(first)).isEqualTo(minted);

            MockHttpServletRequest repeat = requestFrom("9.9.9.9"); // IP가 달라도 쿠키가 이긴다
            repeat.setCookies(new Cookie(GuestIdentityResolver.COOKIE_NAME, minted));
            MockHttpServletResponse second = new MockHttpServletResponse();

            assertThat(resolver.resolve(repeat, second)).isEqualTo(minted);
            assertThat(second.getHeader("Set-Cookie")).as("이미 있으면 다시 굽지 않음").isNull();
        }

        @Test
        @DisplayName("같은 IP라도 쿠키가 없으면 서로 다른 방문자로 분리된다")
        void ipDoesNotMergeVisitors() {
            GuestIdentityResolver resolver = resolverFor(GuestIdentity.COOKIE, false);

            String a = resolver.resolve(requestFrom("1.2.3.4"), new MockHttpServletResponse());
            String b = resolver.resolve(requestFrom("1.2.3.4"), new MockHttpServletResponse());

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("형식이 깨진 쿠키는 무시하고 새로 발급한다 (변조 방어)")
        void rejectsMalformedCookie() {
            GuestIdentityResolver resolver = resolverFor(GuestIdentity.COOKIE, false);

            MockHttpServletRequest req = requestFrom("1.2.3.4");
            req.setCookies(new Cookie(GuestIdentityResolver.COOKIE_NAME, "../../etc/passwd"));
            MockHttpServletResponse res = new MockHttpServletResponse();

            String id = resolver.resolve(req, res);

            assertThat(id).isNotEqualTo("../../etc/passwd").startsWith(GuestIdentityResolver.ID_PREFIX);
            assertThat(res.getHeader("Set-Cookie")).isNotNull();
        }

        @Test
        @DisplayName("쿠키는 HttpOnly + SameSite=Lax + Path=/ 로 발급된다")
        void cookieAttributes() {
            GuestIdentityResolver resolver = resolverFor(GuestIdentity.COOKIE, false);
            MockHttpServletResponse res = new MockHttpServletResponse();

            resolver.resolve(requestFrom("1.2.3.4"), res);

            assertThat(res.getHeader("Set-Cookie"))
                    .contains("HttpOnly")
                    .contains("SameSite=Lax")
                    .contains("Path=/");
        }
    }

    @Nested
    @DisplayName("hybrid (권장)")
    class Hybrid {

        @Test
        @DisplayName("쿠키가 없으면 IP에서 유도하고 그 값을 쿠키로 저장한다")
        void derivesFromIpAndPersists() {
            GuestIdentityResolver resolver = resolverFor(GuestIdentity.HYBRID, false);
            MockHttpServletResponse res = new MockHttpServletResponse();

            String id = resolver.resolve(requestFrom("1.2.3.4"), res);

            assertThat(cookieValue(res)).isEqualTo(id);
            // 순수 ip 전략과 동일한 값이어야 쿠키 삭제 후에도 복구된다
            assertThat(id).isEqualTo(resolverFor(GuestIdentity.IP, false)
                    .resolve(requestFrom("1.2.3.4"), new MockHttpServletResponse()));
        }

        @Test
        @DisplayName("IP가 바뀌어도 쿠키가 있으면 이력이 유지된다 (DHCP 갱신 대응)")
        void cookieSurvivesIpChange() {
            GuestIdentityResolver resolver = resolverFor(GuestIdentity.HYBRID, false);

            MockHttpServletResponse first = new MockHttpServletResponse();
            String original = resolver.resolve(requestFrom("1.2.3.4"), first);

            MockHttpServletRequest moved = requestFrom("192.168.50.77");
            moved.setCookies(new Cookie(GuestIdentityResolver.COOKIE_NAME, cookieValue(first)));

            assertThat(resolver.resolve(moved, new MockHttpServletResponse())).isEqualTo(original);
        }

        @Test
        @DisplayName("쿠키를 지워도 같은 IP면 원래 id로 복구된다 (쿠키 차단 대응)")
        void ipRecoversAfterCookieWipe() {
            GuestIdentityResolver resolver = resolverFor(GuestIdentity.HYBRID, false);

            String before = resolver.resolve(requestFrom("1.2.3.4"), new MockHttpServletResponse());
            String afterWipe = resolver.resolve(requestFrom("1.2.3.4"), new MockHttpServletResponse());

            assertThat(afterWipe).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("IP 정규화")
    class Normalization {

        @Test
        @DisplayName("IPv4는 그대로 사용")
        void ipv4Unchanged() {
            assertThat(GuestIdentityResolver.normalizeIp("203.0.113.7")).isEqualTo("203.0.113.7");
        }

        @Test
        @DisplayName("IPv6 zone id(%eth0)는 제거 — 호스트 로컬 잡음")
        void stripsZoneId() {
            assertThat(GuestIdentityResolver.normalizeIp("fe80::1%eth0")).doesNotContain("%");
        }

        @Test
        @DisplayName("완전표기 IPv6는 /64 프리픽스만 사용 — privacy extensions로 하위 64비트가 바뀌어도 동일인")
        void truncatesFullIpv6ToPrefix() {
            String a = GuestIdentityResolver.normalizeIp("2001:0db8:85a3:0000:1111:2222:3333:4444");
            String b = GuestIdentityResolver.normalizeIp("2001:0db8:85a3:0000:9999:8888:7777:6666");

            assertThat(a).isEqualTo("2001:0db8:85a3:0000").isEqualTo(b);
        }

        @Test
        @DisplayName("null/빈 값도 예외 없이 키로 쓸 수 있는 값을 돌려준다")
        void nullSafe() {
            assertThat(GuestIdentityResolver.normalizeIp(null)).isEqualTo("unknown");
            assertThat(GuestIdentityResolver.normalizeIp("  ")).isEqualTo("unknown");
        }
    }

    @Nested
    @DisplayName("전략 값 정규화 (AppProperties.authSafe)")
    class StrategyNormalization {

        private String normalized(String raw) {
            AppProperties props = new AppProperties(
                    "./data", 2, 800, 100, 100, 7, 0.0, true, 0, false,
                    true, false, 3, null,
                    null, null, null, null, null, null, null,
                    new AppProperties.AuthConfig(false, false, raw), null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
            return props.authSafe().guestIdentity();
        }

        @Test
        @DisplayName("대소문자/공백은 관대하게 처리")
        void lenientParsing() {
            assertThat(normalized("  HYBRID ")).isEqualTo(GuestIdentity.HYBRID);
        }

        @Test
        @DisplayName("null·빈값·오타는 shared로 폴백 — 설정 실수가 반쪽 분리로 이어지지 않게")
        void unknownFallsBackToShared() {
            assertThat(normalized(null)).isEqualTo(GuestIdentity.SHARED);
            assertThat(normalized("")).isEqualTo(GuestIdentity.SHARED);
            assertThat(normalized("hybrd")).isEqualTo(GuestIdentity.SHARED);
        }
    }
}
