package com.example.ragagent.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ChatResponse(
        String answer,
        @JsonProperty("question_type") String questionType,
        List<SourceRef> sources,
        @JsonProperty("image_refs") List<String> imageRefs,
        @JsonProperty("total_input_tokens") int totalInputTokens,
        @JsonProperty("total_output_tokens") int totalOutputTokens,
        @JsonProperty("llm_call_count") int llmCallCount,
        @JsonProperty("elapsed_seconds") double elapsedSeconds,
        @JsonProperty("premium_upgraded") String premiumUpgraded,
        @JsonProperty("used_provider") String usedProvider,
        @JsonProperty("dual_local_answer") String dualLocalAnswer,
        @JsonProperty("dual_local_provider") String dualLocalProvider,
        @JsonProperty("turn_id") Long turnId
) {}
