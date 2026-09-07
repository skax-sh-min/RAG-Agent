package com.example.ragagent.context;

import com.example.ragagent.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.UUID;

@Component
public class ThreadContextResolver implements HandlerMethodArgumentResolver {

    private final CurrentUser currentUser;

    public ThreadContextResolver(CurrentUser currentUser) {
        this.currentUser = currentUser;  // 향후 JwtCurrentUser 등 구현체로 교체
    }

    @Override
    public boolean supportsParameter(MethodParameter p) {
        return p.getParameterType().equals(ThreadContext.class);
    }

    /**
     * 세션을 <b>만들지 않는다</b> — 있으면 읽기만 한다.
     *
     * <p>예전에는 {@code getSession()} 이 세션이 없으면 새로 만들었다. 이 리졸버는 REST 엔드포인트
     * ({@code /api/v1/documents}, {@code /api/v1/chat} 등)에도 걸리므로, 쿠키를 들고 오지 않는
     * 스크립트 클라이언트의 호출 하나하나가 8시간짜리({@code server.servlet.session.timeout})
     * 세션 객체를 남겼다 — no-auth 모드는 STATELESS 로 선언돼 있는데도 앱이 그 뒤에서 세션을
     * 쌓았다. 여기서 만든 {@code threadId} 는 아무 컨트롤러도 읽지 않는다(전부 폼/본문의 threadId 를
     * 쓴다). 화면 경로의 세션은 {@code ChatController} 가 {@code HttpSession} 파라미터로 따로 만든다.
     */
    @Override
    public Object resolveArgument(MethodParameter p, ModelAndViewContainer mav,
                                   NativeWebRequest req, WebDataBinderFactory bf) {
        HttpServletRequest httpReq = req.getNativeRequest(HttpServletRequest.class);
        HttpSession session = httpReq.getSession(false);
        String threadId = session != null ? (String) session.getAttribute("threadId") : null;
        if (threadId == null) {
            threadId = UUID.randomUUID().toString();
            if (session != null) session.setAttribute("threadId", threadId);
        }
        return new ThreadContext(threadId, currentUser.userId(), currentUser.locale());
    }
}
