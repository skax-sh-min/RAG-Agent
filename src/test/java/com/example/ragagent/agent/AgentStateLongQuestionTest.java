package com.example.ragagent.agent;

import com.example.ragagent.llm.RoutingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 긴 질문의 검색 질의(LongQuestionQuery)가 상태에 실리는 모양. */
class AgentStateLongQuestionTest {

    private static AgentState base() {
        return AgentState.of("원문 질문", "latest", "t1", "", RoutingMode.COST_FIRST);
    }

    @Test
    @DisplayName("키워드 질의가 없으면 키워드 축도 검색 질문을 그대로 쓴다(오늘과 같다)")
    void keywordQuestionFallsBackToSearchQuestion() {
        assertThat(base().effectiveKeywordQuestion()).isEqualTo("원문 질문");
        assertThat(base().toBuilder().searchQuestion("독립화된 질문").build().effectiveKeywordQuestion())
                .isEqualTo("독립화된 질문");
    }

    @Test
    @DisplayName("키워드 질의가 있으면 키워드 축은 그것을, 벡터·분류기는 짧은 검색문을 본다")
    void keywordQuestionIsUsedWhenPresent() {
        AgentState s = base().toBuilder().searchQuestion("짧은 검색문").searchKeywords("FooService BarHandler").build();

        assertThat(s.effectiveKeywordQuestion()).isEqualTo("FooService BarHandler");
        assertThat(s.effectiveSearchQuestion()).isEqualTo("짧은 검색문");
        assertThat(s.question()).isEqualTo("원문 질문");
    }

    @Test
    @DisplayName("긴 질문의 검색문은 '독립화됐다'가 아니다 — 이력 대신 들어가는 '다시 쓰면' 한 줄과 진단 표시가 거짓을 말하지 않는다")
    void longQuestionQueryIsNotACondensation() {
        AgentState followUp = base().toBuilder().searchQuestion("독립화된 질문").build();
        AgentState longQ = base().toBuilder().searchQuestion("짧은 검색문").searchKeywords("FooService").build();
        AgentState longNoTerms = base().toBuilder().searchQuestion("짧은 검색문").searchKeywords("").build();

        assertThat(followUp.wasCondensed()).isTrue();
        assertThat(longQ.wasCondensed()).isFalse();
        assertThat(longQ.usedLongQuestionQuery()).isTrue();
        assertThat(longNoTerms.wasCondensed()).as("추릴 낱말이 없어도 빈 문자열이 긴 질문 표지다").isFalse();
        assertThat(longNoTerms.effectiveKeywordQuestion()).as("빈 키워드는 없는 것으로 보고 검색문으로 돌아간다").isEqualTo("짧은 검색문");
        assertThat(base().usedLongQuestionQuery()).isFalse();
    }
}
