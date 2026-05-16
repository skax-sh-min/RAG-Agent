package com.example.ragagent.exception;

public final class UnsupportedFileTypeException extends RagException {
    public UnsupportedFileTypeException(String message) { super("RAG-UP-001", message); }
    @Override public int httpStatus() { return 422; }
}
