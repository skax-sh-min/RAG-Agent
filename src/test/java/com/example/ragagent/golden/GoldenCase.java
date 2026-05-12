package com.example.ragagent.golden;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 골든셋 JSON 파일 한 케이스.
 *
 * given : 시뮬레이션할 AgentGraph 출력 (mock 입력 데이터)
 * expected: 기대되는 AnswerShape (비교 대상)
 */
public record GoldenCase(
        String name,
        String question,
        @JsonProperty("routing_mode") String routingMode,
        String version,
        Given given,
        AnswerShape expected
) {
    public record Given(
            String answer,
            @JsonProperty("question_type") String questionType,
            @JsonProperty("sources_count") int sourcesCount,
            @JsonProperty("image_refs_count") int imageRefsCount,
            @JsonProperty("input_tokens") int inputTokens,
            @JsonProperty("output_tokens") int outputTokens,
            @JsonProperty("llm_call_count") int llmCallCount,
            @JsonProperty("premium_upgraded") String premiumUpgraded,
            @JsonProperty("dual_local_answer") String dualLocalAnswer,
            @JsonProperty("dual_local_provider") String dualLocalProvider,
            @JsonProperty("used_provider") String usedProvider
    ) {}
}
