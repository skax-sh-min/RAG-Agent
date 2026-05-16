package com.example.ragagent.exception;

import java.time.Instant;

public record ErrorResponse(
        String errorCode,
        String message,
        String traceId,
        Instant timestamp
) {
    public static ErrorResponse of(RagException e, String traceId) {
        return new ErrorResponse(e.errorCode(), e.getMessage(), traceId, Instant.now());
    }

    public static ErrorResponse generic(String traceId) {
        return new ErrorResponse("RAG-INT-001", "Internal error", traceId, Instant.now());
    }
}
