package com.example.ragagent.exception;

public sealed abstract class RagException extends RuntimeException
        permits DocumentIndexingException, VectorStoreException,
                InvalidQuestionException, UnsupportedFileTypeException,
                LlmProviderExhaustedException {

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
}
