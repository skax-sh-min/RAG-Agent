package com.example.ragagent.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ChatResponse(
        String answer,
        @JsonProperty("question_type") String questionType,
        List<String> sources,
        @JsonProperty("total_input_tokens") int totalInputTokens,
        @JsonProperty("total_output_tokens") int totalOutputTokens,
        @JsonProperty("llm_call_count") int llmCallCount,
        @JsonProperty("elapsed_seconds") double elapsedSeconds
) {}
