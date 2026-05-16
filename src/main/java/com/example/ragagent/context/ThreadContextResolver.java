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

    @Override
    public Object resolveArgument(MethodParameter p, ModelAndViewContainer mav,
                                   NativeWebRequest req, WebDataBinderFactory bf) {
        HttpServletRequest httpReq = req.getNativeRequest(HttpServletRequest.class);
        HttpSession session = httpReq.getSession();
        String threadId = (String) session.getAttribute("threadId");
        if (threadId == null) {
            threadId = UUID.randomUUID().toString();
            session.setAttribute("threadId", threadId);
        }
        return new ThreadContext(threadId, currentUser.userId(), currentUser.locale());
    }
}
