package com.example.ragagent.exception;

public final class DocumentIndexingException extends RagException {
    public DocumentIndexingException(String message) { super("RAG-INDEX-001", message); }
    public DocumentIndexingException(String message, Throwable cause) { super("RAG-INDEX-001", message, cause); }
    @Override public int httpStatus() { return 500; }
}
