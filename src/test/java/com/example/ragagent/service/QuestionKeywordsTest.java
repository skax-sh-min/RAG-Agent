package com.example.ragagent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 추천 후보 검색의 키워드 추출 규칙을 고정한다.
 *
 * <p>여기서 지키는 성질은 하나다 — 어떤 절단도 결과가 원래 토큰의 <b>접두사</b>여야 한다. 부분 일치에서
 * 접두사는 원래 토큰이 맞히던 행을 전부 맞히므로, 규칙이 틀려도 재현율은 깎이지 않는다. 그래서
 * 아래 단언은 "무엇을 남기는가"와 "너무 짧게 자르지 않는가(2음절 어간)"를 본다.
 */
class QuestionKeywordsTest {

    @Test
    @DisplayName("의문·기능어와 조사·어미를 걷어내고 내용어만 남긴다")
    void stripsFunctionWordsParticlesAndEndings() {
        assertThat(QuestionKeywords.extract("sqlite 연결은 어떻게 설정하나요?"))
                .containsExactly("sqlite", "연결", "설정");
        assertThat(QuestionKeywords.extract("pdf 업로드가 안 되는데 왜 그런가요"))
                .containsExactly("pdf", "업로드");
        assertThat(QuestionKeywords.extract("LM Studio 포트 8080 바꾸는 방법 알려줘"))
                .containsExactly("lm", "studio", "포트", "8080", "바꾸", "방법");   // '바꾸는' → '바꾸' — 바꾸기·바꾸려면도 맞힌다
        assertThat(QuestionKeywords.extract("서버에서는 토큰이라는 값을 어디에 두나요"))
                .containsExactly("서버", "토큰", "값을");   // '값을' — 1음절 어간 + 한 글자 조사는 벗기지 않는다
    }

    @Test
    @DisplayName("아무것도 남지 않으면 빈 목록 — 호출자가 통째 부분 일치로 폴백한다")
    void returnsEmptyWhenNothingSurvives() {
        assertThat(QuestionKeywords.extract("이거 왜 안 돼요")).isEmpty();
        assertThat(QuestionKeywords.extract("어떻게 하나요?")).isEmpty();
        assertThat(QuestionKeywords.extract("가능한가요")).isEmpty();      // 어간 '가능' 도 기능어
        assertThat(QuestionKeywords.extract("이것은 무엇인가요")).isEmpty(); // '이것은' → '이것' → 기능어
        assertThat(QuestionKeywords.extract("   ")).isEmpty();
        assertThat(QuestionKeywords.extract(null)).isEmpty();
    }

    /**
     * 한 글자 조사(의·이·가·도·로·과)는 어간이 2음절 이상 남을 때만 벗긴다 — 정의·차이·추가·속도·경로·결과는
     * 통째로 조사처럼 보이는 내용어다. 두 글자 이상 조사(에서·으로·까지)는 그런 충돌이 없어 1음절 어간까지
     * 허용한다.
     */
    @Test
    @DisplayName("2음절 어간 규칙 — 경로·결과·추가·정의는 그대로, 값으로·탭에서는 한 글자 어간을 남긴다")
    void twoSyllableStemRule() {
        assertThat(QuestionKeywords.extract("경로 결과 추가 정의 사이 속도"))
                .containsExactly("경로", "결과", "추가", "정의", "사이", "속도");
        assertThat(QuestionKeywords.extract("경로로 결과가 추가도 정의의"))
                .containsExactly("경로", "결과", "추가", "정의");
        assertThat(QuestionKeywords.extract("값으로 탭에서 키까지"))
                .containsExactly("값", "탭", "키");
        assertThat(QuestionKeywords.extract("값은 키가"))
                .containsExactly("값은", "키가");
    }

    @Test
    @DisplayName("어미를 조사보다 먼저 벗긴다 — 방법은요 → 방법, 설정이에요 → 설정")
    void endingsBeforeParticles() {
        assertThat(QuestionKeywords.extract("방법은요 설정이에요 서버예요 업로드해줘 처리하기"))
                .containsExactly("방법", "설정", "서버", "업로드", "처리");
        // 각각 한 번씩만 벗긴다 — 결과는 언제나 원래 토큰의 접두사다.
        for (String kw : QuestionKeywords.extract("설정하는지는 데이터베이스에서는")) {
            assertThat("설정하는지는 데이터베이스에서는").contains(kw);
        }
    }

    @Test
    @DisplayName("기호가 든 식별자는 토큰 안에 남고, 문장 끝 문장부호만 벗긴다")
    void keepsIdentifierSymbols() {
        assertThat(QuestionKeywords.extract("application.yml 에서 max_tokens 값과 sqlite-vec 설정."))
                .containsExactly("application.yml", "max_tokens", "값과", "sqlite-vec", "설정");
        assertThat(QuestionKeywords.extract("/api/v1/documents/ 호출: C# 클라이언트로"))
                .containsExactly("/api/v1/documents", "호출", "c#", "클라이언트");
        assertThat(QuestionKeywords.extract(".env 파일은 어디에")).containsExactly(".env", "파일");
        assertThat(QuestionKeywords.extract("sqlite로 8080으로")).containsExactly("sqlite", "8080");
    }

    @Test
    @DisplayName("영문은 소문자로, 영문 기능어는 빠진다")
    void english() {
        assertThat(QuestionKeywords.extract("How do I configure the SQLite connection?"))
                .containsExactly("configure", "sqlite", "connection");
    }

    @Test
    @DisplayName("중복은 한 번만, 한 글자 토큰은 버리고, 최대 6개")
    void dedupeMinLengthAndCap() {
        assertThat(QuestionKeywords.extract("포트 포트 a 안 포트")).containsExactly("포트");
        assertThat(QuestionKeywords.extract("알파 베타 감마 델타 엡실론 제타 에타 세타"))
                .hasSize(QuestionKeywords.MAX_KEYWORDS)
                .containsExactly("알파", "베타", "감마", "델타", "엡실론", "제타");
    }

    @Test
    @DisplayName("절단은 언제나 접두사다 — 어떤 입력에서도 키워드는 원문의 부분 문자열")
    void everyKeywordIsASubstringOfTheInput() {
        List<String> inputs = List.of(
                "sqlite 연결은 어떻게 설정하나요?", "불필요한 설정을 지우려면", "데이터베이스에서는 인덱스로",
                "그렇네요 좋은데요 맞나요", "회의록 문의사항 주의점", "서버하고 클라이언트 사이에");
        for (String input : inputs) {
            for (String kw : QuestionKeywords.extract(input)) {
                assertThat(input.toLowerCase(java.util.Locale.ROOT)).as(input).contains(kw);
            }
        }
    }
}
