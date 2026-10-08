package com.example.ragagent.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Populates MDC with a per-request traceId (from X-Trace-Id header or generated),
 * and echoes it back in the response header so callers can correlate logs.
 */
@Component
public class TraceIdFilter extends OncePerRequestFilter {

    static final String MDC_KEY = "traceId";
    static final String HEADER  = "X-Trace-Id";

    /**
     * 클라이언트가 보낸 trace id 로 받아들일 모양 — 영숫자와 {@code _-}, 최대 64자.
     *
     * <p>이 값은 <b>모든 로그 줄</b>의 {@code [%X{traceId}]} 자리에 그대로 들어간다. 검증이 없으면
     * 줄바꿈을 넣어 가짜 로그 줄을 만들거나(로그 주입), 수 KB 짜리 헤더로 로그 파일을 부풀릴 수
     * 있다 — 게스트에게 열린 배포에서는 누구나 보낼 수 있는 헤더다. UUID(하이픈 포함)와 흔한
     * 상관관계 id 는 그대로 통과하고, 통과하지 못한 값은 <b>거부가 아니라 새로 발급</b>한다:
     * 추적 id 가 이상하다고 요청을 실패시킬 이유는 없다.
     */
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = sanitize(req.getHeader(HEADER));
        MDC.put(MDC_KEY, traceId);
        res.setHeader(HEADER, traceId);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** 헤더 값이 {@link #SAFE_TRACE_ID} 모양이면 그대로, 아니면 새로 발급한다. */
    static String sanitize(String headerValue) {
        if (headerValue != null && SAFE_TRACE_ID.matcher(headerValue).matches()) {
            return headerValue;
        }
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
