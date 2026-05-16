package com.example.ragagent.controller;

import com.example.ragagent.exception.RagException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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
    public void handleAsyncTimeout() {
        log.warn("[TIMEOUT:ASYNC_MVC] Async request timed out");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResource(NoResourceFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setTitle("Not Found");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest req) {
        String traceId = MDC.get("traceId");
        log.error("[RAG-INT-001][{}] Unhandled exception on {} {}", traceId, req.getMethod(), req.getRequestURI(), ex);
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        pd.setTitle("Internal Server Error");
        pd.setDetail("An unexpected error occurred. Please try again.");
        pd.setProperty("errorCode", "RAG-INT-001");
        pd.setProperty("traceId", traceId);
        return ResponseEntity.internalServerError().body(pd);
    }
}
