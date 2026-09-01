package com.example.ragagent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 입력 예산 계산과 자르기 규칙.
 *
 * <p>이 계산이 필요한 근거는 {@code max_tokens} 가 상한이 아니라 <b>예약</b>이라는 것이다 —
 * 창 크기만으로는 부족하고 출력에 잡아 둔 자리를 뺀 나머지가 진짜 입력 한도다.
 */
class PromptBudgetTest {

    @Test
    @DisplayName("입력 예산 = 창 − 출력 예약 − 여유(창의 10%)")
    void inputBudgetSubtractsReservationAndMargin() {
        // 32,000 창에 8,000 예약 → 여유 3,200 → 20,800
        assertThat(new PromptBudget(32_000, 8_000).inputBudget()).isEqualTo(20_800);
    }

    @Test
    @DisplayName("작은 창에서는 여유가 최소 256 으로 받쳐진다")
    void marginHasAFloor() {
        assertThat(PromptBudget.marginFor(1_000)).isEqualTo(PromptBudget.MIN_MARGIN);
        assertThat(PromptBudget.marginFor(10_000)).isEqualTo(1_000);
    }

    @Test
    @DisplayName("예약과 여유가 창을 다 먹으면 예산은 음수가 아니라 0")
    void budgetNeverGoesNegative() {
        assertThat(new PromptBudget(4_000, 4_000).inputBudget()).isZero();
        assertThat(new PromptBudget(1_000, 10_000).inputBudget()).isZero();
    }

    @Test
    @DisplayName("예산을 넘는 지점에서 뒤를 잘라낸다 — 앞(관련도 높은 쪽)이 남는다")
    void keepsThePrefixAndDropsTheTail() {
        List<String> items = List.of("aaaa", "bbbb", "cccc", "dddd");   // 각 1토큰(4자/4)

        assertThat(PromptBudget.fitByPrefix(items, TokenEstimator::estimate, 0, 2))
                .containsExactly("aaaa", "bbbb");
    }

    @Test
    @DisplayName("고정 비용(질문·시스템 프롬프트 등)이 예산을 먼저 먹는다")
    void fixedCostCountsAgainstTheBudget() {
        List<String> items = List.of("aaaa", "bbbb", "cccc");

        assertThat(PromptBudget.fitByPrefix(items, TokenEstimator::estimate, 2, 3))
                .containsExactly("aaaa");
    }

    @Test
    @DisplayName("첫 항목은 예산을 넘어도 남긴다 — 문서를 전부 버리면 '문서를 찾을 수 없습니다'가 된다")
    void alwaysKeepsAtLeastTheFirstItem() {
        List<String> items = List.of("가나다라마바사아자차", "bbbb");   // 첫 항목 10토큰

        assertThat(PromptBudget.fitByPrefix(items, TokenEstimator::estimate, 0, 1))
                .containsExactly("가나다라마바사아자차");
    }

    @Test
    @DisplayName("전부 들어가면 그대로 둔다 / 빈 목록은 빈 목록")
    void noopCases() {
        List<String> items = List.of("aaaa", "bbbb");
        assertThat(PromptBudget.fitByPrefix(items, TokenEstimator::estimate, 0, 1_000))
                .isEqualTo(items);
        assertThat(PromptBudget.fitByPrefix(List.<String>of(), TokenEstimator::estimate, 0, 10)).isEmpty();
    }

    // ── 재작성 입력 상한 (MD 교정 / txt→md 구조화) ────────────────────────────

    @Test
    @DisplayName("재작성 입력 = (창 − 지시 프롬프트 − 여유) / 2.5 — 본문과 그 예약이 함께 들어가야 하므로")
    void rewriteInputSolvesTheCircularReservation() {
        // 사고가 났던 배포와 같은 창: 20,480 − 지시 1,300 − 여유 2,048 = 17,132 → /2.5 = 6,852
        assertThat(PromptBudget.rewriteInputChars(20_480, 1_300)).isEqualTo(6_852);
    }

    @Test
    @DisplayName("그 상한만큼 넣으면 본문 + 자기 출력 예약 + 지시 + 여유가 창 안에 들어간다")
    void rewriteInputAndItsOwnReservationFitTheWindow() {
        int window = 20_480;
        int overhead = 1_300;
        int chars = PromptBudget.rewriteInputChars(window, overhead);
        // 한글 1글자 = 1토큰(TokenEstimator 가정)이라 글자 수가 곧 토큰 수인 최악의 경우로 잰다.
        String body = "가".repeat(chars);

        // 상한이 넉넉해 예약이 잘리지 않는 경우 — 두 식이 같은 여유 비율을 쓰는지 확인하는 자리
        long total = overhead + TokenEstimator.estimate(body)
                + IndexingOutputCap.forRewrite(body, 100_000)
                + PromptBudget.marginFor(window);

        assertThat(total).isLessThanOrEqualTo(window);
    }

    @Test
    @DisplayName("창이 지시 프롬프트와 여유도 못 담으면 0 — 호출부가 '판단 불가'로 읽는다")
    void rewriteInputIsZeroWhenTheWindowCannotEvenHoldThePrompt() {
        assertThat(PromptBudget.rewriteInputChars(1_500, 1_300)).isZero();
        assertThat(PromptBudget.rewriteInputChars(0, 1_300)).isZero();
    }
}
