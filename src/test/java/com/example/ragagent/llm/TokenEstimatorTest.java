package com.example.ragagent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 토큰 추정의 <b>단일</b> 가정 (PLAN §6.24 남은 이슈 (a) 해소).
 *
 * <p>여기서 고정하는 계약의 핵심은 "두 옛 가정이 각각의 특수 케이스로 남는다"는 것이다 — 영어는
 * 예전 {@code chars/4} 와 <b>정확히</b> 같아 회귀가 없고, 한국어는 {@code ResponseMode} 예산이
 * 쓰던 1토큰/글자와 일치한다. 이 성질이 깨지면 둘 중 한쪽 소비처가 조용히 어긋난다.
 */
class TokenEstimatorTest {

    @Test
    @DisplayName("영어는 예전 chars/4 와 정확히 같다 — 영어권 배포에 회귀가 없어야 한다")
    void asciiMatchesTheOldCharsOverFour() {
        for (String s : List.of("hello world", "a", "", "The quick brown fox jumps over the lazy dog")) {
            assertThat(TokenEstimator.estimate(s))
                    .as("'%s'", s)
                    .isEqualTo(s.length() / 4);
        }
    }

    @Test
    @DisplayName("한국어는 글자당 1토큰 — ResponseMode 예산·MAX_EVAL_EXCERPT_CHARS 산정과 같은 가정")
    void koreanCountsOneTokenPerSyllable() {
        assertThat(TokenEstimator.estimate("안녕하세요")).isEqualTo(5);
        // 예전 chars/4 였다면 1 이었다 — /llm-usage 한국어 과소 보고가 정확히 이 차이다.
        assertThat(TokenEstimator.estimate("안녕하세요")).isNotEqualTo("안녕하세요".length() / 4);
    }

    @Test
    @DisplayName("섞인 글은 두 규칙의 합으로 보간된다")
    void mixedTextInterpolates() {
        // 한글 3자 + 나머지 8자(": hello!" 는 8자) → 3 + 8/4 = 5
        String mixed = "한국어: hello!";
        long expected = 3 + (mixed.length() - 3) / 4;
        assertThat(TokenEstimator.estimate(mixed)).isEqualTo(expected);
    }

    @Test
    @DisplayName("공백·구두점은 CJK 로 세지 않는다 — 세면 한국어 추정이 부풀어 예산을 과하게 깎는다")
    void punctuationAndSpacesAreNotCjk() {
        // 한글 4자 + 공백/마침표 등 나머지는 4자당 1토큰으로만 기여한다.
        assertThat(TokenEstimator.estimate("가나다라")).isEqualTo(4);
        assertThat(TokenEstimator.estimate("가 나 다 라"))
                .as("사이의 공백 3개는 3/4 = 0 토큰")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("한자·가나도 글자당 1토큰")
    void cjkIdeographsAndKanaCountAsOne() {
        assertThat(TokenEstimator.estimate("漢字")).isEqualTo(2);
        assertThat(TokenEstimator.estimate("ひらがな")).isEqualTo(4);
        assertThat(TokenEstimator.estimate("カタカナ")).isEqualTo(4);
    }

    @Test
    @DisplayName("보조 평면 문자(이모지)는 char 두 개가 아니라 한 글자로 센다")
    void surrogatePairsCountAsOneCharacter() {
        // "🙂" 는 char 2개다. codePoint 로 세지 않으면 나머지 글자 수가 두 배로 잡힌다.
        String emoji = "🙂🙂🙂🙂";
        assertThat(emoji.length()).isEqualTo(8);
        assertThat(TokenEstimator.estimate(emoji)).isEqualTo(1);   // 4 codePoints / 4
    }

    @Test
    @DisplayName("null·빈 값은 0, 목록은 조각들의 합")
    void nullAndCollections() {
        assertThat(TokenEstimator.estimate((String) null)).isZero();
        assertThat(TokenEstimator.estimate((Iterable<String>) null)).isZero();
        assertThat(TokenEstimator.estimate(Arrays.asList("안녕", null, "hello wor")))
                .isEqualTo(2 + 0 + 2);
    }

    @Test
    @DisplayName("LlmRouter.approxTokens 는 이 추정기에 위임한다 — 소비처가 갈라지면 안 된다")
    void routerDelegatesToTheSameEstimator() {
        for (String s : List.of("문서를 검색해 주세요", "plain english text", "혼합 mixed 텍스트")) {
            assertThat(LlmRouter.approxTokens(s))
                    .as("'%s'", s)
                    .isEqualTo(TokenEstimator.estimate(s));
        }
    }
}
