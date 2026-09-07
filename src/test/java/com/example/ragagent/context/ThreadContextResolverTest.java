package com.example.ragagent.context;

import com.example.ragagent.security.CurrentUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.context.request.ServletWebRequest;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ThreadContext} 를 만드는 것이 세션을 만드는 부작용을 갖지 않는지.
 *
 * <p>이 리졸버는 REST 엔드포인트에도 걸린다. 예전 {@code getSession()} 은 쿠키 없는 스크립트 호출
 * 하나마다 8시간짜리 세션 객체를 남겼다 — STATELESS 로 선언된 no-auth 모드에서도.
 */
class ThreadContextResolverTest {

    private static final CurrentUser GUEST = new CurrentUser() {
        @Override public String userId() { return "guest-1"; }
        @Override public boolean isAuthenticated() { return true; }
        @Override public Locale locale() { return Locale.KOREAN; }
    };

    private final ThreadContextResolver resolver = new ThreadContextResolver(GUEST);

    private ThreadContext resolve(MockHttpServletRequest request) {
        return (ThreadContext) resolver.resolveArgument(null, null, new ServletWebRequest(request), null);
    }

    @Test
    @DisplayName("세션이 없는 요청(API 호출)은 세션을 만들지 않고 컨텍스트만 준다")
    void noSession_createsNone() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/documents");

        ThreadContext ctx = resolve(request);

        assertThat(ctx.userId()).isEqualTo("guest-1");
        assertThat(ctx.threadId()).isNotBlank();
        assertThat(request.getSession(false)).as("리졸버가 세션을 만들면 안 된다").isNull();
    }

    @Test
    @DisplayName("세션이 이미 있으면 그 threadId 를 읽고, 없던 값은 그 세션에만 적는다")
    void existingSession_isReusedNotReplaced() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("threadId", "t-existing");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/chat/t-existing");
        request.setSession(session);

        assertThat(resolve(request).threadId()).isEqualTo("t-existing");

        MockHttpSession fresh = new MockHttpSession();
        MockHttpServletRequest second = new MockHttpServletRequest("GET", "/");
        second.setSession(fresh);
        ThreadContext ctx = resolve(second);
        assertThat(fresh.getAttribute("threadId")).isEqualTo(ctx.threadId());
    }
}
