package com.example.ragagent.exception;

public final class VectorStoreException extends RagException {
    public VectorStoreException(String message, Throwable cause) { super("RAG-VEC-001", message, cause); }
    @Override public int httpStatus() { return 503; }
}
