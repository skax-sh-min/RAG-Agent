package com.example.ragagent.exception;

public final class InvalidQuestionException extends RagException {
    public InvalidQuestionException(String message) { super("RAG-VAL-001", message); }
    @Override public int httpStatus() { return 400; }
}
