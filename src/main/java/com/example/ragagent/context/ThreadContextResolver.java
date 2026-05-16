package com.example.ragagent.context;

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
        // 향후: SecurityContextHolder.getContext().getAuthentication() 기반 userId 설정
        return ThreadContext.anonymous(threadId);
    }
}
