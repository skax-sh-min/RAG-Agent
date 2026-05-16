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

/**
 * Populates MDC with a per-request traceId (from X-Trace-Id header or generated),
 * and echoes it back in the response header so callers can correlate logs.
 */
@Component
public class TraceIdFilter extends OncePerRequestFilter {

    static final String MDC_KEY = "traceId";
    static final String HEADER  = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = req.getHeader(HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        MDC.put(MDC_KEY, traceId);
        res.setHeader(HEADER, traceId);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
