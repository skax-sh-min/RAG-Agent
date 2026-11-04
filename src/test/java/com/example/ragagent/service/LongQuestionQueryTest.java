package com.example.ragagent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 긴 질문의 검색 질의 — 1단계(LLM 없이) 규칙. */
class LongQuestionQueryTest {

    @Test
    @DisplayName("1단계는 공백을 뗀 길이가 500자를 넘을 때, 2단계(LLM)는 2,000자를 넘을 때")
    void thresholds() {
        assertThat(LongQuestionQuery.isLong("a".repeat(500))).isFalse();
        assertThat(LongQuestionQuery.isLong("a".repeat(501))).isTrue();
        assertThat(LongQuestionQuery.isLong("  " + "a".repeat(500) + "  ")).as("앞뒤 공백은 세지 않는다").isFalse();
        assertThat(LongQuestionQuery.needsLlm("a".repeat(2000))).isFalse();
        assertThat(LongQuestionQuery.needsLlm("a".repeat(2001))).isTrue();
        assertThat(LongQuestionQuery.isLong(null)).isFalse();
    }

    @Test
    @DisplayName("벡터 검색문은 앞 300자 + 뒤 200자 안팎이고, 가운데는 … 로 표시한다")
    void headTailKeepsInstructionAtBothEnds() {
        String head = "다음 오류를 분석해 주세요 ";
        String tail = " 원인과 해결 방법을 알려 주세요";
        String body = "line ".repeat(400);
        String q = head + body + tail;

        String v = LongQuestionQuery.headTail(q);

        assertThat(v).startsWith("다음 오류를 분석해 주세요").endsWith("원인과 해결 방법을 알려 주세요").contains(" … ");
        assertThat(v.length()).isLessThanOrEqualTo(LongQuestionQuery.HEAD_CHARS + LongQuestionQuery.TAIL_CHARS + 10);
    }

    @Test
    @DisplayName("500자 이하는 공백만 접고 그대로 돌려준다")
    void headTailLeavesShortQuestionAlone() {
        assertThat(LongQuestionQuery.headTail("  안녕   하세요\n\n반갑습니다 ")).isEqualTo("안녕 하세요 반갑습니다");
    }

    @Test
    @DisplayName("키워드: 식별자는 질문에 나온 그대로 남고, 3글자 미만·40자 초과·기능어는 빠진다")
    void keywordTermsKeepExactIdentifiers() {
        String q = "NullPointerException 이 어디서 나는지 알려줘 "
                + "at com.example.FooService.handle(FooService.java:42) "
                + "x " + "a".repeat(41) + " 설정 ERR-4021";

        List<String> terms = LongQuestionQuery.keywordTerms(q);

        assertThat(terms).contains("NullPointerException", "FooService", "ERR");
        assertThat(terms).doesNotContain("x", "a".repeat(41), "알려줘");
        assertThat(terms).allSatisfy(t -> assertThat(t.length()).isBetween(3, 40));
    }

    @Test
    @DisplayName("키워드: 앞·뒤(지시) 구간의 낱말이 본문 한복판의 흔한 낱말보다 앞선다")
    void keywordTermsFavourInstructionRegion() {
        String body = ("Connection pool exhausted retry ").repeat(60);
        String q = "SessionTimeoutHandler 설정을 알려주세요 " + body + " 마지막으로 KeepAliveInterval 도 확인해 주세요";

        List<String> terms = LongQuestionQuery.keywordTerms(q, 5);

        assertThat(terms).contains("SessionTimeoutHandler", "KeepAliveInterval");
    }

    @Test
    @DisplayName("키워드는 상한을 넘지 않고, 같은 낱말은 대소문자를 무시하고 한 번만 센다")
    void keywordTermsAreCappedAndDeduplicated() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) sb.append("Token").append(i).append("Name Token").append(i).append("name ");
        List<String> terms = LongQuestionQuery.keywordTerms(sb.toString(), 20);

        assertThat(terms).hasSize(20).doesNotHaveDuplicates();
        assertThat(LongQuestionQuery.keywordTerms("   ", 20)).isEmpty();
    }

    @Test
    @DisplayName("합집합: LLM 낱말이 먼저, 대소문자 중복·짧은 약어는 빠지고, 상한에서 멈춘다")
    void mergeTermsPutsLlmFirstAndDedupes() {
        List<String> merged = LongQuestionQuery.mergeTerms(
                List.of("FooService", "db", "timeout"), List.of("fooservice", "BarHandler", "Baz"), 4);

        assertThat(merged).containsExactly("FooService", "timeout", "BarHandler", "Baz");
        assertThat(LongQuestionQuery.mergeTerms(List.of("aaa", "bbb", "ccc"), List.of("ddd"), 2)).hasSize(2);
        assertThat(LongQuestionQuery.joinTerms(List.of("aaa", "bbb"))).isEqualTo("aaa bbb");
        assertThat(LongQuestionQuery.joinTerms(List.of())).isNull();
    }
}
