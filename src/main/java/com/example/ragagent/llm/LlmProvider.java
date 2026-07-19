package com.example.ragagent.llm;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.api.OpenAiApi;

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
        ChatModel chatModel,
        OpenAiApi openAiApi
) {
    public boolean supports(TaskType req) {
        return switch (this.type) {
            // §6.21 — a dedicated small model serves only MICRO_TEXT chores. LIGHT_TEXT/LIGHT_BOTH/BOTH
            // also serve MICRO_TEXT so deployments without a small model fall back to the capable model
            // (MICRO_TEXT is strictly a subset of what a LIGHT_TEXT-capable provider already handles).
            case MICRO_TEXT -> req == MICRO_TEXT;
            case LIGHT_TEXT -> req == LIGHT_TEXT || req == MICRO_TEXT;
            case TEXT       -> req == TEXT;
            case VISION     -> req == VISION;
            case LIGHT_BOTH -> req == LIGHT_TEXT || req == MICRO_TEXT || req == VISION;
            case BOTH       -> true;
        };
    }

    public boolean hasValidApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
