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
        @JsonProperty("env_note") String envNote,
        /**
         * 컨텍스트 예산 때문에 검색 문서나 이전 대화 일부가 프롬프트에서 빠졌을 때의 안내.
         * <b>출처 목록은 줄지 않는다</b> — 축소는 프롬프트에만 걸리므로, 이 안내가 없으면 사용자는
         * 화면의 출처를 모델이 전부 읽고 답했다고 믿는다. 축소가 없었으면 null.
         */
        @JsonProperty("budget_note") String budgetNote,
        /**
         * 이 답변이 <b>문서를 재료로 생성된 것</b>인가 ({@code ResponseMode.generative()}).
         * 검증 배지가 이 값으로 갈린다 — {@code 검증됨}(초록) 대신 {@code 생성}(파랑). 통과한 검증의
         * 질문 자체가 다르기 때문이다(문서 근거 여부 vs 발명된 이름 여부). 클라이언트가 모드 문자열을
         * 보고 판단하지 않도록 <b>서버가 성질로 계산해 내려준다</b>.
         */
        @JsonProperty("generative") boolean generative,
        /**
         * 창의 검증이 "발췌에 없는데 문서에 있는 것처럼 쓰였다"고 지목한 이름들. 재시도를 걸지 않는
         * <b>경고 전용</b> 값이고(§6.24 Step 2-d), 창의 모드가 아닌 턴에서는 항상 비어 있다.
         */
        @JsonProperty("invented_symbols") List<String> inventedSymbols
) {
    public ChatResponse {
        inventedSymbols = inventedSymbols == null ? List.of() : List.copyOf(inventedSymbols);
    }
}
