package com.example.ragagent.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 유지보수의 치환 규칙 — 글자 그대로가 기본이고, 대소문자 변형은 켰을 때만 그 모양에 맞춰 바꾼다. */
class TermReplacementTest {

    private static final TermReplacement EXACT = TermReplacement.exact("Oracle", "PostgreSQL");
    private static final TermReplacement VARIANTS = new TermReplacement("Oracle", "PostgreSQL", true);

    @Test
    @DisplayName("글자 그대로(기본) — String.replace 와 같다: 대소문자 변형은 건드리지 않는다")
    void exactLeavesCaseVariantsAlone() {
        String text = "Oracle 과 ORACLE 과 oracle 과 oRacle";

        assertThat(EXACT.apply(text)).isEqualTo("PostgreSQL 과 ORACLE 과 oracle 과 oRacle");
        assertThat(EXACT.count(text)).isEqualTo(1);
        assertThat(EXACT.matches("ORACLE only")).isFalse();
    }

    @Test
    @DisplayName("변형 포함 — 찾은 표기의 대소문자 모양에 맞춘다: 대문자→대문자, 소문자→소문자, 정확히 같으면 그대로, 섞인 표기는 그대로")
    void variantsAreShapedToTheMatchedCase() {
        assertThat(VARIANTS.apply("Oracle 과 ORACLE 과 oracle 과 oRacle"))
                .isEqualTo("PostgreSQL 과 POSTGRESQL 과 postgresql 과 PostgreSQL");
        assertThat(VARIANTS.count("Oracle ORACLE oracle oRacle")).isEqualTo(4);
        assertThat(VARIANTS.matches("only ORACLE here")).isTrue();
    }

    @Test
    @DisplayName("변형 수는 옵션과 무관하게 센다(글자 그대로인 것은 세지 않는다) — 리포트의 투명성")
    void variantCountIgnoresTheOption() {
        String text = "Oracle ORACLE ORACLE oracle";
        assertThat(EXACT.variantCount(text)).isEqualTo(3);
        assertThat(VARIANTS.variantCount(text)).isEqualTo(3);
        assertThat(EXACT.variantCount("Oracle only")).isZero();
        assertThat(EXACT.variantCount(null)).isZero();
    }

    @Test
    @DisplayName("식별자의 특수문자는 글자로 취급한다 — 정규식 문자(. * ( $)가 들어 있어도 · 새 말의 $ 와 \\ 도 그대로")
    void specialCharactersAreLiteral() {
        TermReplacement dotted = new TermReplacement("a.b(c)", "x$1\\y", true);
        assertThat(dotted.apply("A.B(C) 와 axb(c) 와 a.b(c)")).isEqualTo("X$1\\Y 와 axb(c) 와 x$1\\y");
        assertThat(dotted.count("A.B(C) axb(c)")).isEqualTo(1);
    }

    @Test
    @DisplayName("새 말이 한글이면 모양이 달라지지 않고, 바꿀 것이 없으면 입력을 그대로 돌려준다")
    void nonLatinTermsAndNoMatch() {
        TermReplacement kr = new TermReplacement("Oracle", "오라클", true);
        assertThat(kr.apply("ORACLE oracle Oracle")).isEqualTo("오라클 오라클 오라클");
        String untouched = "아무 관련 없는 본문";
        assertThat(VARIANTS.apply(untouched)).isEqualTo(untouched);
        assertThat(VARIANTS.apply(null)).isNull();
    }

    @Test
    @DisplayName("맥락·키워드 같은 짧은 문자열에도 같은 규칙 — 소문자 키워드가 바뀐다(이전엔 글자 그대로라 못 바꿨다)")
    void lowercaseKeywordsAreReplacedWhenVariantsAreOn() {
        assertThat(VARIANTS.apply("oracle, 접속, jdbc")).isEqualTo("postgresql, 접속, jdbc");
        assertThat(EXACT.apply("oracle, 접속, jdbc")).isEqualTo("oracle, 접속, jdbc");
    }
}
