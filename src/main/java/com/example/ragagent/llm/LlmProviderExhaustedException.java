package com.example.ragagent.llm;

public class LlmProviderExhaustedException extends RuntimeException {
    public LlmProviderExhaustedException(String message) {
        super(message);
    }
}
