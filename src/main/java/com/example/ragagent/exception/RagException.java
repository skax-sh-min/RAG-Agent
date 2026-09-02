package com.example.ragagent.exception;

public sealed abstract class RagException extends RuntimeException
        permits DocumentIndexingException, VectorStoreException,
                InvalidQuestionException, UnsupportedFileTypeException,
                LlmProviderExhaustedException, LlmBackpressureException,
                StorageQuotaExceededException {

    private final String errorCode;

    protected RagException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected RagException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() { return errorCode; }

    public abstract int httpStatus();

    /** Seconds the client should wait before retrying, or -1 when not applicable. */
    public int retryAfterSeconds() { return -1; }
}
