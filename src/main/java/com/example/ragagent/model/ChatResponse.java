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
        @JsonProperty("eval_reason") String evalReason,
        /**
         * 경로·호스트·포트·환경변수 값처럼 실행 환경에 따라 달라지는 값이 답변에 포함됐을 때의 안내
         * 한 문장. 이런 값은 문서와 달라도 검증 실패 사유가 아니므로({@code prompt.answer.eval}의
         * 환경 의존 값 예외) grounded 는 true 로 두고 이 필드로만 알린다. 해당 없으면 null.
         */
        @JsonProperty("env_note") String envNote
) {}
