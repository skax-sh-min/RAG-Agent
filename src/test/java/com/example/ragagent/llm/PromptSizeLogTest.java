package com.example.ragagent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프롬프트 크기 로그의 표시 계약 — 이 클래스가 하는 일은 "무엇이 프롬프트를 부풀렸는가"를 한 줄로
 * 읽히게 만드는 것뿐이라, 검증할 것도 그 읽힘이다.
 *
 * <p>Covers:
 *  - 조각마다 토큰·바이트를 함께 찍고 합계가 조각들의 합과 맞는다
 *  - 빈 블록은 0으로 찍지 않고 아예 빠진다(없는 것과 0인 것은 다르다)
 *  - 창을 모르면 "창 모름" — 0을 창으로 찍어 사용률 100%로 오해하게 두지 않는다
 */
class PromptSizeLogTest {

    @Test
    @DisplayName("조각마다 토큰·바이트를 찍고, 합계는 조각들의 합이다")
    void rendersEachPartWithTokensAndBytes() {
        String question = "포트 설정은 어디서 하나요?";

        String line = PromptSizeLog.of("답변").add("질문", question).render(null);

        assertThat(line).startsWith("답변 — 질문(")
                .contains("%,d tok".formatted(TokenEstimator.estimate(question)))
                .contains("%,d byte".formatted(question.getBytes(StandardCharsets.UTF_8).length))
                .contains("| 합계 ");
    }

    @Test
    @DisplayName("수량 표기는 라벨 뒤에 붙는다 — '이력 3턴', '문서 10청크'")
    void quantityGoesAfterTheLabel() {
        String line = PromptSizeLog.of("답변")
                .add("이력", "3턴", "Q: 질문\nA: 답변")
                .add("문서", "10청크", "문서 본문")
                .render(null);

        assertThat(line).contains("이력 3턴(").contains("문서 10청크(");
    }

    @Test
    @DisplayName("빈 블록은 0으로 찍지 않고 빠진다 — 없는 것과 0인 것은 다르다")
    void emptyPartsAreOmitted() {
        String line = PromptSizeLog.of("답변")
                .add("질문", "질문")
                .add("경고", "")
                .add("이력", null)
                .render(null);

        assertThat(line).contains("질문(").doesNotContain("경고").doesNotContain("이력");
    }

    @Test
    @DisplayName("합계는 조각들의 토큰 합과 같다")
    void totalIsTheSumOfParts() {
        String a = "가".repeat(100);
        String b = "나".repeat(250);

        PromptSizeLog size = PromptSizeLog.of("검증").add("답변", a).add("발췌", b);

        assertThat(size.totalTokens())
                .isEqualTo(TokenEstimator.estimate(a) + TokenEstimator.estimate(b));
        assertThat(size.render(null)).contains("합계 %,d tok".formatted(size.totalTokens()));
    }

    @Test
    @DisplayName("조각이 하나도 없으면 '(빈 프롬프트)' — 빈 줄을 남기지 않는다")
    void noPartsIsStated() {
        assertThat(PromptSizeLog.of("답변").render(null)).contains("(빈 프롬프트)");
    }

    @Test
    @DisplayName("창을 알면 사용률까지 — 예산 대비 몇 %인지가 이 로그를 보는 이유다")
    void budgetTailShowsUsage() {
        PromptSizeLog size = PromptSizeLog.of("답변").add("문서", "가".repeat(1_000));

        String tail = size.budgetTail("local", 32_768, 10_000);

        assertThat(tail).contains("프로바이더 local", "창 32,768 tok", "입력 예산 10,000 tok", "10% 사용");
    }

    @Test
    @DisplayName("창을 모르면 '창 모름' — 0을 창으로 찍어 100% 사용으로 오해하게 두지 않는다")
    void budgetTailSaysUnknownWindow() {
        String tail = PromptSizeLog.of("답변").add("질문", "질문").budgetTail("local", 0, 0);

        assertThat(tail).isEqualTo("프로바이더 local, 창 모름");
    }

    @Test
    @DisplayName("꼬리말은 합계 뒤에 파이프로 이어 붙는다")
    void tailIsAppendedAfterTheTotal() {
        String line = PromptSizeLog.of("검증").add("질문", "질문").render("thread=t1");

        assertThat(line).endsWith("| thread=t1");
    }
}
