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
        @JsonProperty("turn_id") Long turnId,
        /** CRITIC 결과. null = 검증 미실행(meta/Direct 답변, 검색 결과 없음). */
        @JsonProperty("grounded") Boolean grounded,
        /**
         * 검증을 통과하지 못한 이유 — 평가 LLM이 준 한 문장. 통과했거나 검증을 돌리지 않았으면 null.
         * 스트리밍 경로가 SSE {@code done} 이벤트로 같은 값을 내보내므로, 두 경로 어디로 물어봐도
         * "왜 미검증인지"를 같은 방식으로 알 수 있다.
         */
        @JsonProperty("eval_reason") String evalReason
) {}
