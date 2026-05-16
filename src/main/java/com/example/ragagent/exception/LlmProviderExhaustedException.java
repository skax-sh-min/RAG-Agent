package com.example.ragagent.exception;

public final class LlmProviderExhaustedException extends RagException {
    public LlmProviderExhaustedException(String message) { super("RAG-LLM-001", message); }
    @Override public int httpStatus() { return 503; }
}
