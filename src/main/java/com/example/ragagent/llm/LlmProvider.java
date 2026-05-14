package com.example.ragagent.llm;

import org.springframework.ai.chat.model.ChatModel;

import static com.example.ragagent.llm.TaskType.*;

public record LlmProvider(
        String name,
        TaskType type,
        ProviderRole role,
        int priority,
        String apiKey,
        String baseUrl,
        String model,
        boolean stream,
        ChatModel chatModel
) {
    public boolean supports(TaskType req) {
        return switch (this.type) {
            case LIGHT_TEXT -> req == LIGHT_TEXT;
            case TEXT       -> req == TEXT;
            case VISION     -> req == VISION;
            case LIGHT_BOTH -> req == LIGHT_TEXT || req == VISION;
            case BOTH       -> true;
        };
    }

    public boolean hasValidApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
