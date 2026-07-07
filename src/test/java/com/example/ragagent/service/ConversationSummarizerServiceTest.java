package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.exception.LlmProviderExhaustedException;
import com.example.ragagent.llm.BackgroundUsage;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.repository.MemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA — ConversationSummarizerService (§6.10 in PLAN.md)
 *
 * Covers:
 *  - dedupe(): DISLIKE 하드 제외, 정규화 후 중복 질문은 최신 답변만 유지
 *  - precompute() → buildContext(): 정상 캐싱 및 "요약 + 최근 N턴" 조합
 *  - precompute(): turns 비어있으면(또는 dedupe 결과 blank) LLM 호출 없이 종료
 *  - precompute(): LOCAL provider 예외/일반 예외 시 캐시에 아무것도 남지 않음(안전 폴백)
 *  - precompute(): TTL 이내 재호출은 LLM 을 다시 부르지 않음
 *  - invalidate(): 캐시 제거 후 buildContext() 는 다시 null
 */
class ConversationSummarizerServiceTest {

    private MemoryService memoryService;
    private LlmRouter llmRouter;
    private MessageSource messageSource;
    private ConversationSummarizerService service;

    private static final String UID = "u1";
    private static final String TID = "t1";

    @BeforeEach
    void setUp() {
        memoryService = mock(MemoryService.class);
        llmRouter = mock(LlmRouter.class);
        messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(eq("prompt.summary.system"), any(), any(Locale.class)))
                .thenReturn("system prompt");
        // Generous budget so buildContext() keeps the summary + recent turns intact by default;
        // the budget-trim behavior is exercised explicitly in its own test.
        when(memoryService.maxConversationChars()).thenReturn(6_000);
        AppProperties props = mock(AppProperties.class);
        when(props.summarySafe()).thenReturn(new AppProperties.SummaryConfig(3, 2_000, 2, 15));
        service = new ConversationSummarizerService(memoryService, llmRouter, messageSource, props);
    }

    private static MemoryRepository.Turn turn(long id, String q, String a, String feedback) {
        return new MemoryRepository.Turn(id, q, a, null, null, 0, 0, 0, "local", 1, feedback);
    }

    @Test
    @DisplayName("dedupe — DISLIKE turn 은 제외된다")
    void dedupe_excludesDislikedTurns() {
        List<MemoryRepository.Turn> turns = List.of(
                turn(1, "질문A", "답변A", null),
                turn(2, "질문B", "답변B", "DISLIKE"));

        String result = service.dedupe(turns);

        assertThat(result).contains("질문A").doesNotContain("질문B");
    }

    @Test
    @DisplayName("dedupe — 정규화 후 동일 질문은 최신 답변만 유지된다")
    void dedupe_keepsLatestAnswerForRepeatedQuestion() {
        List<MemoryRepository.Turn> turns = List.of(
                turn(1, "  질문A  ", "옛날 답변", null),
                turn(2, "질문a", "최신 답변", null));

        String result = service.dedupe(turns);

        assertThat(result).contains("최신 답변").doesNotContain("옛날 답변");
        assertThat(result.lines().filter(l -> l.startsWith("Q:")).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("precompute — turns 가 비어있으면 LLM 호출 없이 종료")
    void precompute_emptyHistory_skipsLlmCall() {
        when(memoryService.getTurns(UID, TID)).thenReturn(List.of());

        service.precompute(UID, TID, Locale.KOREAN);

        verify(llmRouter, never()).executeWithTracking(any(), any(), any(), any());
        assertThat(service.buildContext(UID, TID)).isNull();
    }

    @Test
    @DisplayName("precompute → buildContext — 요약이 캐시되고 최근 turn 과 결합되어 반환된다")
    void precompute_thenBuildContext_combinesSummaryWithRecentTurns() {
        List<MemoryRepository.Turn> turns = List.of(
                turn(1, "질문1", "답변1", null),
                turn(2, "질문2", "답변2", null));
        when(memoryService.getTurns(UID, TID)).thenReturn(turns);
        when(llmRouter.executeWithTracking(eq(TaskType.LIGHT_TEXT), eq(RoutingMode.LOCAL_ONLY),
                eq(BackgroundUsage.SUMMARY_PREFIX), any()))
                .thenReturn("요약된 내용");

        service.precompute(UID, TID, Locale.KOREAN);
        String context = service.buildContext(UID, TID);

        assertThat(context).contains("요약된 내용");
        assertThat(context).contains("질문2").contains("답변2"); // last-N raw turn preserved verbatim
    }

    @Test
    @DisplayName("precompute — LOCAL provider 없음(LlmProviderExhaustedException) 시 캐시에 남지 않음")
    void precompute_noLocalProvider_leavesCacheEmpty() {
        when(memoryService.getTurns(UID, TID)).thenReturn(List.of(turn(1, "질문", "답변", null)));
        when(llmRouter.executeWithTracking(any(), any(), any(), any()))
                .thenThrow(new LlmProviderExhaustedException("no local provider"));

        service.precompute(UID, TID, Locale.KOREAN);

        assertThat(service.buildContext(UID, TID)).isNull();
    }

    @Test
    @DisplayName("precompute — 알 수 없는 예외도 전파하지 않고 조용히 실패한다")
    void precompute_unexpectedException_doesNotPropagate() {
        when(memoryService.getTurns(UID, TID)).thenReturn(List.of(turn(1, "질문", "답변", null)));
        when(llmRouter.executeWithTracking(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("boom"));

        service.precompute(UID, TID, Locale.KOREAN); // must not throw

        assertThat(service.buildContext(UID, TID)).isNull();
    }

    @Test
    @DisplayName("precompute — TTL 이내 재호출은 LLM 을 다시 부르지 않는다")
    void precompute_withinTtl_skipsSecondLlmCall() {
        when(memoryService.getTurns(anyString(), anyString()))
                .thenReturn(List.of(turn(1, "질문", "답변", null)));
        when(llmRouter.executeWithTracking(any(), any(), any(), any())).thenReturn("요약");

        service.precompute(UID, TID, Locale.KOREAN);
        service.precompute(UID, TID, Locale.KOREAN);

        verify(llmRouter, times(1)).executeWithTracking(any(), any(), any(), any());
    }

    @Test
    @DisplayName("invalidate — 캐시 제거 후 buildContext() 는 다시 null")
    void invalidate_clearsCache() {
        when(memoryService.getTurns(UID, TID)).thenReturn(List.of(turn(1, "질문", "답변", null)));
        when(llmRouter.executeWithTracking(any(), any(), any(), any())).thenReturn("요약");
        service.precompute(UID, TID, Locale.KOREAN);
        assertThat(service.buildContext(UID, TID)).isNotNull();

        service.invalidate(TID);

        assertThat(service.buildContext(UID, TID)).isNull();
    }

    @Test
    @DisplayName("invalidate — TTL 도 함께 초기화되어 재호출 즉시 LLM 을 다시 부른다")
    void invalidate_resetsTtlGuardToo() {
        when(memoryService.getTurns(anyString(), anyString()))
                .thenReturn(List.of(turn(1, "질문", "답변", null)));
        when(llmRouter.executeWithTracking(any(), any(), any(), any())).thenReturn("요약");

        service.precompute(UID, TID, Locale.KOREAN);
        service.invalidate(TID);
        service.precompute(UID, TID, Locale.KOREAN);

        verify(llmRouter, times(2)).executeWithTracking(any(), any(), any(), any());
    }

    @Test
    @DisplayName("buildContext — 요약+최근턴 조합도 폴백 경로와 동일한 문자 예산을 넘지 않는다 (§6.11)")
    void buildContext_respectsSameCharBudgetAsFallback() {
        when(memoryService.maxConversationChars()).thenReturn(200);
        String longAnswer = "가".repeat(300);
        when(memoryService.getTurns(UID, TID)).thenReturn(List.of(
                turn(1, "질문1", longAnswer, null),
                turn(2, "질문2", longAnswer, null)));
        when(llmRouter.executeWithTracking(eq(TaskType.LIGHT_TEXT), eq(RoutingMode.LOCAL_ONLY),
                eq(BackgroundUsage.SUMMARY_PREFIX), any()))
                .thenReturn("짧은 요약");

        service.precompute(UID, TID, Locale.KOREAN);
        String context = service.buildContext(UID, TID);

        assertThat(context).isNotNull();
        assertThat(context.length()).isLessThanOrEqualTo(200);
        assertThat(context).contains("짧은 요약");           // summary is always preserved
        assertThat(context).doesNotContain(longAnswer);      // oversized recent turns dropped, not overflowed
    }

    @Test
    @DisplayName("buildContext — 요약만으로 예산 초과 시에도 예산 이내로 하드 캡 (아주 작은 LLM_MAX_TOKENS)")
    void buildContext_summaryAloneExceedsBudget_isHardCapped() {
        when(memoryService.maxConversationChars()).thenReturn(50);
        when(memoryService.getTurns(UID, TID)).thenReturn(List.of(turn(1, "질문", "답변", null)));
        when(llmRouter.executeWithTracking(any(), any(), any(), any())).thenReturn("요".repeat(500));

        service.precompute(UID, TID, Locale.KOREAN);
        String context = service.buildContext(UID, TID);

        assertThat(context).isNotNull();
        assertThat(context.length()).isLessThanOrEqualTo(50);
    }

    @Test
    @DisplayName("buildContext — 아무것도 캐싱되지 않았으면 null (호출자가 getHistory 로 폴백)")
    void buildContext_noCacheEntry_returnsNull() {
        assertThat(service.buildContext(UID, "unknown-thread")).isNull();
    }
}
