package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 긴 질문의 검색 질의(LongQuestionQuery) — LLM 단계({@code distill})와 두 단계를 잇는 {@code planLong}. */
class QuestionCondenserLongQuestionTest {

    private static AppProperties props(boolean multiquery) {
        AppProperties props = mock(AppProperties.class);
        when(props.searchMultiqueryEnabledSafe()).thenReturn(multiquery);
        when(props.searchMultiqueryMinLengthSafe()).thenReturn(15);
        when(props.llmSafe()).thenReturn(mock(AppProperties.LlmConfig.class));
        return props;
    }

    private static MessageSource bundle() {
        MessageSource ms = mock(MessageSource.class);
        when(ms.getMessage(eq("prompt.retrieval.distill"), any(), any(Locale.class))).thenReturn("검색어를 뽑아라");
        return ms;
    }

    private static QuestionCondenser condenser(LlmRouter router, boolean multiquery) {
        return new QuestionCondenser(router, mock(MemoryService.class), bundle(), props(multiquery));
    }

    private static String longQuestion(int chars) {
        return "SessionTimeoutHandler 설정이 왜 적용되지 않는지 알려 주세요. " + "로그 줄 ".repeat(chars / 4) + " 끝.";
    }

    // ── 응답 읽기 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("두 줄 응답을 읽는다 — 검색문 + 쉼표로 나눈 키워드(따옴표·글머리 제거, 짧은 것 제외)")
    void parsesBothLines() {
        QuestionCondenser.Distilled d = QuestionCondenser.parseDistilled(
                "검색문: SessionTimeoutHandler 가 적용되지 않는 원인\n키워드: \"SessionTimeoutHandler\", - web.xml, db, `KeepAlive`", 10, 5);

        assertThat(d.vectorQuery()).isEqualTo("SessionTimeoutHandler 가 적용되지 않는 원인");
        assertThat(d.keywords()).containsExactly("SessionTimeoutHandler", "web.xml", "KeepAlive");
        assertThat(d.inputTokens()).isEqualTo(10);
    }

    @Test
    @DisplayName("검색문이 상한을 넘으면 잘라 쓰지 않고 버리며, 키워드만 있어도 결과는 있다")
    void overlongQueryIsDroppedNotTruncated() {
        String tooLong = "가".repeat(QuestionCondenser.MAX_DISTILLED_QUERY_CHARS + 1);

        QuestionCondenser.Distilled d = QuestionCondenser.parseDistilled("검색문: " + tooLong + "\n키워드: FooService, BarHandler", 0, 0);

        assertThat(d.vectorQuery()).isNull();
        assertThat(d.keywords()).containsExactly("FooService", "BarHandler");
    }

    @Test
    @DisplayName("둘 다 못 읽으면 null, 키워드는 12개까지")
    void nothingUsableIsNull() {
        assertThat(QuestionCondenser.parseDistilled("모르겠습니다", 0, 0)).isNull();
        assertThat(QuestionCondenser.parseDistilled("", 0, 0)).isNull();

        StringBuilder sb = new StringBuilder("키워드: ");
        for (int i = 0; i < 20; i++) sb.append("Term").append(i).append(i < 19 ? ", " : "");
        assertThat(QuestionCondenser.parseDistilled(sb.toString(), 0, 0).keywords()).hasSize(QuestionCondenser.MAX_DISTILLED_TERMS);
    }

    @Test
    @DisplayName("LLM 에 보내는 질문은 8,000자까지 — 넘으면 앞 5,000 + 뒤 3,000 만, 가운데는 표시를 남긴다")
    void clipsTheInputToTheLlm() {
        String q = "A".repeat(6000) + "B".repeat(6000);

        String clipped = QuestionCondenser.clipForDistill(q);

        assertThat(clipped).startsWith("A".repeat(5000)).endsWith("B".repeat(3000)).contains("[… 중략 …]");
        assertThat(QuestionCondenser.clipForDistill("짧은 질문")).isEqualTo("짧은 질문");
    }

    // ── 게이트 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("준비가 필요한 질문: 500자 초과(긴 질문) 또는 짧은 후속 질문 — 그 사이는 해당 없음")
    void needsPreparation() {
        QuestionCondenser c = condenser(mock(LlmRouter.class), true);

        assertThat(c.needsPreparation("a".repeat(501))).isTrue();
        assertThat(c.needsPreparation("그 설정은 어디야?")).isTrue();
        assertThat(c.needsPreparation("충분히 길지만 긴 질문은 아닌 보통 질문입니다")).isFalse();
    }

    @Test
    @DisplayName("2,000자 이하의 긴 질문은 LLM 을 부르지 않고 1단계 결과만 만든다")
    void stageOneDoesNotCallTheLlm() {
        LlmRouter router = mock(LlmRouter.class);
        QuestionCondenser c = condenser(router, true);

        QuestionCondenser.Condensed plan = c.prepare("u1", "t1", longQuestion(1200), Locale.KOREAN).orElseThrow();

        verify(router, never()).executeGatedWithUsage(any(TaskType.class), any(RoutingMode.class), any());
        assertThat(plan.searchQuestion()).contains(" … ").startsWith("SessionTimeoutHandler");
        assertThat(plan.searchKeywords()).contains("SessionTimeoutHandler");
        assertThat(plan.inputTokens()).isZero();
    }

    @Test
    @DisplayName("2,000자 초과 + LLM 성공: 검색문은 LLM 것, 키워드는 LLM 먼저 + 결정적 추출 합집합, 토큰이 실린다")
    @SuppressWarnings("unchecked")
    void stageTwoMergesLlmResult() {
        LlmRouter router = mock(LlmRouter.class);
        when(router.executeGatedWithUsage(any(TaskType.class), any(RoutingMode.class), any()))
                .thenReturn(new LlmRouter.LlmResult("검색문: 세션 타임아웃 설정이 적용되지 않는 이유\n키워드: web.xml, KeepAliveInterval", 900, 40));
        QuestionCondenser c = condenser(router, true);

        QuestionCondenser.Condensed plan = c.prepare("u1", "t1", longQuestion(2400), Locale.KOREAN).orElseThrow();

        assertThat(plan.searchQuestion()).isEqualTo("세션 타임아웃 설정이 적용되지 않는 이유");
        assertThat(plan.searchKeywords().split(" ")).startsWith("web.xml", "KeepAliveInterval").contains("SessionTimeoutHandler");
        assertThat(plan.inputTokens()).isEqualTo(900);
        assertThat(plan.outputTokens()).isEqualTo(40);
    }

    @Test
    @DisplayName("2,000자 초과 + LLM 실패: 예외 없이 1단계 결과로 검색한다")
    void stageTwoFallsBackOnFailure() {
        LlmRouter router = mock(LlmRouter.class);
        when(router.executeGatedWithUsage(any(TaskType.class), any(RoutingMode.class), any()))
                .thenThrow(new RuntimeException("model down"));
        QuestionCondenser c = condenser(router, true);

        QuestionCondenser.Condensed plan = c.prepare("u1", "t1", longQuestion(2400), Locale.KOREAN).orElseThrow();

        assertThat(plan.searchQuestion()).contains(" … ");
        assertThat(plan.searchKeywords()).contains("SessionTimeoutHandler");
        assertThat(plan.inputTokens()).isZero();
    }

    @Test
    @DisplayName("질의 전처리 스위치(search-multiquery-enabled)를 끄면 2단계 LLM 은 꺼지고 1단계는 그대로다")
    void switchOffDisablesOnlyTheLlmStage() {
        LlmRouter router = mock(LlmRouter.class);
        QuestionCondenser c = condenser(router, false);

        Optional<QuestionCondenser.Condensed> plan = c.prepare("u1", "t1", longQuestion(2400), Locale.KOREAN);

        verify(router, never()).executeGatedWithUsage(any(TaskType.class), any(RoutingMode.class), any());
        assertThat(plan).isPresent();
        assertThat(plan.get().searchQuestion()).contains(" … ");
    }

    @Test
    @DisplayName("추릴 낱말이 없어도 빈 문자열로 표시한다(null 이 아니다) — 긴 질문이라 따로 만들었다는 표지")
    void emptyKeywordsStillMarkTheLongPath() {
        QuestionCondenser c = condenser(mock(LlmRouter.class), true);

        QuestionCondenser.Condensed plan = c.prepare("u1", "t1", "ㅋ ".repeat(300), Locale.KOREAN).orElseThrow();

        assertThat(plan.searchKeywords()).isNotNull().isEmpty();
        assertThat(List.of(plan.searchQuestion())).isNotEmpty();
    }
}
