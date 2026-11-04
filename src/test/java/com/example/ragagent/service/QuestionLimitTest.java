package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.llm.ThinkingBudget;
import com.example.ragagent.model.ResponseMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 채팅 질문 길이 한도 — 설정 상한과 이 모델의 창 기준 상한 중 작은 쪽. */
class QuestionLimitTest {

    private static final int WINDOW = 16_384;

    /** 창 {@code window}(0 = 모름), max-tokens 10,000, 청크 1,500자인 배포. */
    private static QuestionLimit limit(int configured, int window, LlmRouter router) {
        AppProperties props = mock(AppProperties.class);
        AppProperties.LlmConfig llm = mock(AppProperties.LlmConfig.class);
        when(llm.maxTokens()).thenReturn(10_000);
        when(props.llmSafe()).thenReturn(llm);
        when(props.chunkSizeSafe()).thenReturn(1_500);
        when(router.findProviderName(any(TaskType.class), any(RoutingMode.class))).thenReturn("local");
        ProviderContextWindows windows = mock(ProviderContextWindows.class);
        when(windows.tokensOrZero("local")).thenReturn(window);
        return new QuestionLimit(configured, router, windows, ThinkingBudget.none(), props);
    }

    private static String ask(int chars) {
        return "가".repeat(chars);
    }

    private static void validate(QuestionLimit limit, String q, boolean direct, boolean streaming) {
        limit.validate(q, direct, RoutingMode.COST_FIRST, ResponseMode.DEFAULT, streaming);
    }

    @Test
    @DisplayName("설정 상한은 500~100,000 으로 눌린다 — 그 아래로 내리면 평범한 질문이 막힌다")
    void configuredIsClamped() {
        assertThat(limit(100, 0, mock(LlmRouter.class)).configuredMax()).isEqualTo(500);
        assertThat(limit(9_999_999, 0, mock(LlmRouter.class)).configuredMax()).isEqualTo(100_000);
        assertThat(limit(10_000, 0, mock(LlmRouter.class)).configuredMax()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("설정 상한을 넘으면 창과 무관하게 거절한다")
    void rejectsAboveConfiguredMax() {
        QuestionLimit limit = limit(10_000, 0, mock(LlmRouter.class));

        assertThatThrownBy(() -> validate(limit, ask(10_001), false, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("최대 10000자");
        assertThatCode(() -> validate(limit, ask(10_000), false, true)).doesNotThrowAnyException();
        assertThatThrownBy(() -> validate(limit, "  ", false, true)).hasMessageContaining("비어");
    }

    @Test
    @DisplayName("500자 이하는 창을 따지지 않는다 — 라우터에 묻지도 않는다")
    void shortQuestionSkipsTheWindowCheck() {
        LlmRouter router = mock(LlmRouter.class);
        QuestionLimit limit = limit(10_000, 1_000, router);   // 아주 작은 창

        assertThatCode(() -> validate(limit, ask(500), false, false)).doesNotThrowAnyException();
        verify(router, never()).findProviderName(any(TaskType.class), any(RoutingMode.class));
    }

    @Test
    @DisplayName("창을 모르면 설정 상한만 적용한다")
    void unknownWindowOnlyConfiguredMax() {
        QuestionLimit limit = limit(10_000, 0, mock(LlmRouter.class));

        assertThatCode(() -> validate(limit, ask(9_000), false, false)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("16K 창: 입력 예산 − 고정 몫 − 문서 한 개보다 긴 질문은 이 모델에서 받을 글자 수를 알려 주며 거절한다")
    void rejectsWhatTheWindowCannotTake() {
        QuestionLimit limit = limit(10_000, WINDOW, mock(LlmRouter.class));
        // 블로킹 N: 출력 예약 7,000 → 입력 예산 16,384−7,000−1,638 = 7,746 → 질문 몫 7,746−2,000−1,500 = 4,246 토큰(= 한글 4,246자)

        assertThat(limit.allowedQuestionTokens(false, RoutingMode.COST_FIRST, ResponseMode.DEFAULT, false)).isEqualTo(4_246);
        assertThatCode(() -> validate(limit, ask(4_000), false, false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> validate(limit, ask(5_000), false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("약 4246자까지").hasMessageContaining("입력: 5000자");
    }

    @Test
    @DisplayName("Direct 는 문서 자리를 비워 두지 않아 그만큼 더 받는다 · 스트리밍은 출력 예약이 작아 더 받는다")
    void directAndStreamingTakeMore() {
        QuestionLimit limit = limit(10_000, WINDOW, mock(LlmRouter.class));

        int rag = limit.allowedQuestionTokens(false, RoutingMode.COST_FIRST, ResponseMode.DEFAULT, false);
        int direct = limit.allowedQuestionTokens(true, RoutingMode.COST_FIRST, ResponseMode.DEFAULT, false);
        int streamingRag = limit.allowedQuestionTokens(false, RoutingMode.COST_FIRST, ResponseMode.DEFAULT, true);

        assertThat(direct - rag).isEqualTo(1_500);
        assertThat(streamingRag).isGreaterThan(rag);
    }

    @Test
    @DisplayName("창이 고정 몫도 못 담으면 허용 토큰은 0 이다 — 음수가 아니다")
    void tinyWindowAllowsZeroNotNegative() {
        QuestionLimit limit = limit(10_000, 4_000, mock(LlmRouter.class));

        assertThat(limit.allowedQuestionTokens(false, RoutingMode.COST_FIRST, ResponseMode.DEFAULT, false)).isZero();
    }

    @Test
    @DisplayName("한도를 재다가 예외가 나도 질문은 막지 않는다 — 설정 상한은 이미 통과했다")
    void computeFailureDoesNotBlock() {
        LlmRouter router = mock(LlmRouter.class);
        QuestionLimit limit = limit(10_000, WINDOW, router);
        when(router.findProviderName(any(TaskType.class), any(RoutingMode.class))).thenThrow(new IllegalStateException("boom"));

        assertThatCode(() -> validate(limit, ask(9_000), false, false)).doesNotThrowAnyException();
    }
}
