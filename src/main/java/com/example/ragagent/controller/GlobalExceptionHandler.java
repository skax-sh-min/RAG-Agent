package com.example.ragagent.controller;

import com.example.ragagent.exception.RagException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Locale;

/**
 * RFC 9457 ProblemDetail responses for all domain + infrastructure exceptions.
 * All RagException subclasses are routed here; HTMX requests get an HX-Reswap:none header
 * so the client can handle the error body without swapping content.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RagException.class)
    public ResponseEntity<ProblemDetail> handleRag(RagException ex, HttpServletRequest req) {
        String traceId = MDC.get("traceId");
        if (ex.httpStatus() >= 500) log.error("[{}][{}] {}", ex.errorCode(), traceId, ex.getMessage(), ex);
        else log.warn("[{}][{}] {}", ex.errorCode(), traceId, ex.getMessage());

        ProblemDetail pd = ProblemDetail.forStatus(ex.httpStatus());
        pd.setTitle(ex.errorCode());
        pd.setDetail(ex.getMessage());
        pd.setProperty("errorCode", ex.errorCode());
        pd.setProperty("traceId", traceId);

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ex.httpStatus());
        if (req.getHeader("HX-Request") != null) {
            builder = builder.header("HX-Reswap", "none");
        }
        return builder.body(pd);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleUploadSize(MaxUploadSizeExceededException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.PAYLOAD_TOO_LARGE);
        pd.setTitle("File Too Large");
        pd.setDetail("Upload exceeds the maximum allowed size.");
        pd.setProperty("errorCode", "RAG-UP-003");
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Bad Request");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public void handleAsyncTimeout(HttpServletRequest req) {
        log.info("[TIMEOUT:ASYNC_MVC] {} {}", req.getMethod(), req.getRequestURI());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResource(NoResourceFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setTitle("Not Found");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleUnexpected(Exception ex, HttpServletRequest req) {
        String traceId = MDC.get("traceId");

        if (isClientAbort(ex) || (isSseRequest(req) && hasIoExceptionCause(ex))) {
            log.debug("[RAG-INT-001][{}] Client disconnected on {} {}", traceId, req.getMethod(), req.getRequestURI());
            return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
        }

        if (isSseRequest(req)) {
            log.warn("[RAG-INT-001][{}] SSE exception on {} {}", traceId, req.getMethod(), req.getRequestURI(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        log.error("[RAG-INT-001][{}] Unhandled exception on {} {}", traceId, req.getMethod(), req.getRequestURI(), ex);

        if (isHtmlPageRequest(req)) {
            // Full browser page navigation (not HTMX/API) — ProblemDetail has no HTML
            // message converter, so writing it here would throw a second exception
            // (HttpMessageNotWritableException) on top of the original one.
            String html = "<!DOCTYPE html><html><body><h1>500 - Internal Server Error</h1>"
                    + "<p>An unexpected error occurred. Please try again.</p></body></html>";
            return ResponseEntity.internalServerError().contentType(MediaType.TEXT_HTML).body(html);
        }

        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        pd.setTitle("Internal Server Error");
        pd.setDetail("An unexpected error occurred. Please try again.");
        pd.setProperty("errorCode", "RAG-INT-001");
        pd.setProperty("traceId", traceId);
        return ResponseEntity.internalServerError().body(pd);
    }

    /** Full browser page load (link click, address-bar navigation) — not HTMX (fragment swap) or API/JSON. */
    private boolean isHtmlPageRequest(HttpServletRequest req) {
        if (req.getHeader("HX-Request") != null) return false;
        String accept = req.getHeader("Accept");
        return accept != null && accept.contains(MediaType.TEXT_HTML_VALUE);
    }

    private boolean isSseRequest(HttpServletRequest req) {
        String accept = req.getHeader("Accept");
        return (accept != null && accept.contains("text/event-stream"))
                || req.getRequestURI().contains("/ui/documents/progress/")
                || req.getRequestURI().contains("/ui/chat/stream");
    }

    private boolean isClientAbort(Throwable ex) {
        Throwable cur = ex;
        while (cur != null) {
            String className = cur.getClass().getName();
            if (className.contains("ClientAbortException") || className.contains("AsyncRequestNotUsableException")) {
                return true;
            }
            String msg = cur.getMessage();
            if (msg != null) {
                String lowered = msg.toLowerCase(Locale.ROOT);
                if (lowered.contains("broken pipe")
                        || lowered.contains("connection reset")
                        || lowered.contains("forcibly closed")
                        || lowered.contains("software caused connection abort")
                        || lowered.contains("connection abort")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }

    /**
     * OS-localized socket abort messages (e.g. Korean Windows renders WSAECONNABORTED as
     * "현재 연결은 사용자의 호스트 시스템의 소프트웨어에 의해 중단되었습니다") don't match the English
     * substrings in {@link #isClientAbort}, so a normal client disconnect on an SSE endpoint
     * gets misreported as a server error. SSE/streaming handlers never do file I/O, so any
     * IOException surfacing from them is the socket write failing, not an application bug.
     */
    private boolean hasIoExceptionCause(Throwable ex) {
        Throwable cur = ex;
        while (cur != null) {
            if (cur instanceof java.io.IOException) return true;
            cur = cur.getCause();
        }
        return false;
    }
}
