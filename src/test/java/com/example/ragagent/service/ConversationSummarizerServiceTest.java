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
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

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
 *  - dedupeTurns(): DISLIKE 하드 제외, 정규화 후 중복 질문은 최신 답변만 유지
 *  - precompute() → buildContext(): 정상 캐싱 및 "요약 + 최근 N턴" 조합
 *  - precompute(): 모든 답변에 "## 요약" 섹션이 있으면 LLM 없이 그 내용을 그대로 사용
 *  - precompute(): 일부 답변에만 "## 요약" 이 있으면 LLM 입력이 그 요약으로 축약된 채 1회 호출
 *  - precompute(): LOCAL_FAST(MICRO_TEXT offload) provider 가 없으면 LLM 요약은 아예 생략
 *  - precompute(): turns 비어있으면(또는 dedupe 결과 blank) LLM 호출 없이 종료
 *  - precompute(): LOCAL provider 예외/일반 예외 시 캐시에 아무것도 남지 않음(안전 폴백)
 *  - precompute(): TTL 이내 재호출은 LLM 을 다시 부르지 않음
 *  - invalidate(): 캐시 제거 후 buildContext() 는 다시 null
 *  - precompute(turnId): LLM 응답 도착 시점에 해당 turn 이 DISLIKE면 결과를 캐싱하지 않음
 *  - precomputeAfterTurn(): 답변 완료 직후 캐시를 무효화하고 백그라운드로 재생성함
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
        // Default for the existing cases: the MICRO_TEXT offload model (LOCAL_FAST_LLM_URL) is
        // configured, so the LLM summarization path is allowed to run.
        when(llmRouter.hasMicroTextOffloadProvider()).thenReturn(true);
        AppProperties props = mock(AppProperties.class);
        when(props.summarySafe()).thenReturn(new AppProperties.SummaryConfig(3, 2_000, 2, 15));
        when(props.llmSafe()).thenReturn(new AppProperties.LlmConfig(
                List.of(), 2, 10, 180, "COST_FIRST", 3, 20, 0.0, 0.1, 0.0, 0.7, true, 6000, 1, true));
        service = new ConversationSummarizerService(memoryService, llmRouter, messageSource, props);
    }

    private static MemoryRepository.Turn turn(long id, String q, String a, String feedback) {
        return new MemoryRepository.Turn(id, q, a, null, null, 0, 0, 0, "local", 1, feedback, "M", null, false);
    }

    /**
     * 요약 경로를 실제로 태우려면 최소 3턴이 필요하다 — 뒤 recentRawTurns(=2)개는 [Recent] 가
     * 직접 담당하므로 요약 대상에서 빠지고, 2턴 이하 스레드는 요약 없이 [Recent] 만으로 문맥이
     * 구성된다(LLM 호출 0회). 요약 자체를 검증하는 케이스는 이 헬퍼를 쓴다.
     */
    private static List<MemoryRepository.Turn> threeTurns() {
        return List.of(turn(1, "질문", "답변", null),
                       turn(2, "질문2", "답변2", null),
                       turn(3, "질문3", "답변3", null));
    }

    @Test
    @DisplayName("dedupeTurns — DISLIKE turn 은 제외된다")
    void dedupe_excludesDislikedTurns() {
        List<MemoryRepository.Turn> turns = List.of(
                turn(1, "질문A", "답변A", null),
                turn(2, "질문B", "답변B", "DISLIKE"));

        List<MemoryRepository.Turn> result = service.dedupeTurns(turns);

        assertThat(result).extracting(MemoryRepository.Turn::question).containsExactly("질문A");
    }

    @Test
    @DisplayName("dedupeTurns — 정규화 후 동일 질문은 최신 답변만 유지된다")
    void dedupe_keepsLatestAnswerForRepeatedQuestion() {
        List<MemoryRepository.Turn> turns = List.of(
                turn(1, "  질문A  ", "옛날 답변", null),
                turn(2, "질문a", "최신 답변", null));

        List<MemoryRepository.Turn> result = service.dedupeTurns(turns);

        assertThat(result).extracting(MemoryRepository.Turn::answer).containsExactly("최신 답변");
    }

    @Test
    @DisplayName("precompute — turns 가 비어있으면 LLM 호출 없이 종료")
    void precompute_emptyHistory_skipsLlmCall() {
        when(memoryService.getRecentTurns(UID, TID)).thenReturn(List.of());

        service.precompute(UID, TID, Locale.KOREAN);

        verify(llmRouter, never()).executeWithTracking(any(), any(), any(), any());
        assertThat(service.buildContext(UID, TID)).isNull();
    }

    @Test
    @DisplayName("precompute → buildContext — 요약(이전 턴) + [Recent](최근 turn) 이 겹치지 않게 결합된다")
    void precompute_thenBuildContext_combinesSummaryWithRecentTurns() {
        // recentRawTurns=2 이므로 요약 대상은 turn1 뿐이고 turn2·3 은 [Recent] 몫이다.
        // 3턴 이상이어야 요약할 "이전 턴"이 생긴다는 점이 이 분할의 핵심.
        when(memoryService.getRecentTurns(UID, TID)).thenReturn(List.of(
                turn(1, "질문1", "답변1", null),
                turn(2, "질문2", "답변2", null),
                turn(3, "질문3", "답변3", null)));
        when(llmRouter.executeWithTracking(eq(TaskType.MICRO_TEXT), eq(RoutingMode.LOCAL_ONLY),
                eq(BackgroundUsage.SUMMARY_PREFIX), any()))
                .thenReturn("요약된 내용");

        service.precompute(UID, TID, Locale.KOREAN);
        String context = service.buildContext(UID, TID);

        assertThat(context).contains("요약된 내용");
        assertThat(context).contains("질문3").contains("답변3"); // last-N raw turn preserved verbatim
    }

    @Test
    @DisplayName("precompute → buildContext — 최근 창을 요약 대상에서 빼 같은 턴이 두 번 들어가지 않는다")
    void precompute_excludesRecentWindowFromSummary() {
        when(memoryService.getRecentTurns(UID, TID)).thenReturn(List.of(
                turn(1, "오래된 질문", ragAnswer("오래된 요약", "오래된 본문"), null),
                turn(2, "질문2", ragAnswer("둘째 요약", "둘째 본문"), null),
                turn(3, "질문3", ragAnswer("셋째 요약", "셋째 본문"), null)));

        service.precompute(UID, TID, Locale.KOREAN);
        String context = service.buildContext(UID, TID);

        // turn2·3 은 [Recent] 에만, turn1 은 요약에만 — 각 요약 문구가 정확히 한 번씩만 등장한다.
        assertThat(context.split("셋째 요약", -1)).hasSize(2);   // 등장 1회
        assertThat(context.split("오래된 요약", -1)).hasSize(2); // 등장 1회
    }

    @Test
    @DisplayName("buildContext — 턴이 최근 창 이하면 요약 없이 [Recent] 만으로 문맥을 만든다 (원본 폴백으로 떨어지지 않음)")
    void buildContext_shortThread_usesRecentOnlyInsteadOfFallingBack() {
        when(memoryService.getRecentTurns(UID, TID)).thenReturn(List.of(
                turn(1, "질문1", ragAnswer("첫 답변 한 줄 요약", "첫 답변 장황한 본문"), null)));

        service.precompute(UID, TID, Locale.KOREAN);
        String context = service.buildContext(UID, TID);

        // 요약할 이전 턴이 없어도 null 이 아니어야 한다 — null 이면 호출자가 getHistory() 로
        // 폴백해 방금 줄인 답변 전문이 그대로 되돌아온다.
        assertThat(context).isNotNull()
                .doesNotContain("[Conversation Summary]")   // 빈 섹션 헤더는 내보내지 않는다
                .contains("첫 답변 한 줄 요약")
                .doesNotContain("첫 답변 장황한 본문");
        verify(llmRouter, never()).executeWithTracking(any(), any(), any(), any());
    }

    @Test
    @DisplayName("buildContext — [Recent] 의 RAG 답변은 '## 요약' 만 담는다 (본문은 다음 턴이 재검색하므로 불필요)")
    void buildContext_recentRagAnswerKeepsOnlySummarySection() {
        when(memoryService.getRecentTurns(UID, TID)).thenReturn(List.of(
                turn(1, "질문1", ragAnswer("첫 답변 한 줄 요약", "첫 답변 장황한 본문"), null)));

        service.precompute(UID, TID, Locale.KOREAN);
        String context = service.buildContext(UID, TID);

        // 예산(6,000자)은 넉넉하므로 본문이 빠진 이유는 오직 렌더링 규칙 때문이다.
        assertThat(context)
                .contains("첫 답변 한 줄 요약")
                .doesNotContain("첫 답변 장황한 본문")
                .doesNotContain("## 상세 설명");
    }

    @Test
    @DisplayName("buildContext — [Recent] 의 Direct 답변은 남기되 상한까지만 (근거 문서가 없어 이 답변이 유일한 기록)")
    void buildContext_recentDirectAnswerIsKeptButCapped() {
        String longDirect = "가".repeat(1_500) + "꼬리표";
        when(memoryService.getRecentTurns(UID, TID)).thenReturn(List.of(
                turn(1, "직답 질문", longDirect, null)));
        when(llmRouter.executeWithTracking(eq(TaskType.MICRO_TEXT), eq(RoutingMode.LOCAL_ONLY),
                eq(BackgroundUsage.SUMMARY_PREFIX), any()))
                .thenReturn("짧은 요약");

        service.precompute(UID, TID, Locale.KOREAN);
        String context = service.buildContext(UID, TID);

        assertThat(context)
                .contains("직답 질문")
                .contains("가".repeat(100))   // 지워지지 않고 남는다
                .doesNotContain("꼬리표");     // 다만 1,200자에서 잘린다
    }

    @Test
    @DisplayName("buildContext — 싫어요 turn 은 [Recent] 원문 구간에서도 제외된다 "
            + "(요약에서만 빼고 원문으로 다시 넣던 버그)")
    void buildContext_excludesDislikedTurnFromRecentVerbatimBlock() {
        // getRecentTurns() 의 SQL 에는 feedback 조건이 없다 — dedupeTurns() 를 거치지 않으면
        // 싫어요 답변이 요약에서는 빠지면서 [Recent] 에는 전문 그대로 들어간다. UI 의
        // "다음 대화 컨텍스트에서 제외" 약속과 정반대이고, 원문이라 문자 예산까지 독식한다.
        when(memoryService.getRecentTurns(UID, TID)).thenReturn(List.of(
                turn(1, "질문1", ragAnswer("첫 답변 한 줄 요약", "첫 답변 본문"), null),
                turn(2, "싫어요 질문", "싫어요가 눌린 장황한 답변 전문", "DISLIKE")));

        service.precompute(UID, TID, Locale.KOREAN);
        String context = service.buildContext(UID, TID);

        assertThat(context)
                .doesNotContain("싫어요 질문")
                .doesNotContain("싫어요가 눌린 장황한 답변 전문")
                .contains("첫 답변 한 줄 요약");
    }

    @Test
    @DisplayName("precompute — 모든 답변에 '## 요약' 섹션이 있으면 LLM 없이 그 내용을 요약으로 쓴다")
    void precompute_allAnswersCarrySummarySection_skipsLlmEntirely() {
        // 요약 대상(= 최근 2턴을 뺀 나머지)이 생기도록 4턴. turn1·2 가 요약, turn3·4 가 [Recent].
        when(memoryService.getRecentTurns(UID, TID)).thenReturn(List.of(
                turn(1, "질문1", ragAnswer("첫 답변 한 줄 요약", "첫 답변 장황한 본문"), null),
                turn(2, "질문2", ragAnswer("둘째 답변 한 줄 요약", "둘째 답변 장황한 본문"), null),
                turn(3, "질문3", ragAnswer("셋째 답변 한 줄 요약", "셋째 답변 장황한 본문"), null),
                turn(4, "질문4", ragAnswer("넷째 답변 한 줄 요약", "넷째 답변 장황한 본문"), null)));

        service.precompute(UID, TID, Locale.KOREAN);

        verify(llmRouter, never()).executeWithTracking(any(), any(), any(), any());
        String context = service.buildContext(UID, TID);
        assertThat(context).isNotNull();
        String summaryBlock = context.substring(0, context.indexOf("[Recent]"));
        assertThat(summaryBlock)
                .contains("첫 답변 한 줄 요약").contains("둘째 답변 한 줄 요약")
                .doesNotContain("장황한 본문")            // 본문은 요약에 들어가지 않는다
                .doesNotContain("넷째 답변 한 줄 요약");  // 최근 창은 요약 대상에서 빠진다
    }

    @Test
    @DisplayName("precompute — 일부 답변만 '## 요약' 이면 그 요약으로 축약된 입력으로 LLM 1회 호출")
    @SuppressWarnings("unchecked")
    void precompute_partialSummarySections_callsLlmWithShrunkInput() {
        ArgumentCaptor<Function<ChatModel, ChatResponse>> callCaptor = ArgumentCaptor.forClass(Function.class);
        // 요약 대상은 앞 2턴(turn1·2), 뒤 2턴은 [Recent] 몫이다.
        when(memoryService.getRecentTurns(UID, TID)).thenReturn(List.of(
                turn(1, "질문1", ragAnswer("첫 답변 한 줄 요약", "첫 답변 장황한 본문"), null),
                turn(2, "질문2", "요약 섹션이 없는 Direct 답변", null),
                turn(3, "질문3", ragAnswer("셋째 요약", "셋째 본문"), null),
                turn(4, "질문4", ragAnswer("넷째 요약", "넷째 본문"), null)));
        when(llmRouter.executeWithTracking(eq(TaskType.MICRO_TEXT), eq(RoutingMode.LOCAL_ONLY),
                eq(BackgroundUsage.SUMMARY_PREFIX), callCaptor.capture()))
                .thenReturn("LLM 요약");

        service.precompute(UID, TID, Locale.KOREAN);

        ChatModel chatModel = mock(ChatModel.class);
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        when(chatModel.call(promptCaptor.capture())).thenReturn(mock(ChatResponse.class));
        callCaptor.getValue().apply(chatModel);

        String llmInput = promptCaptor.getValue().getUserMessage().getText();
        assertThat(llmInput)
                .contains("첫 답변 한 줄 요약")            // 이미 요약된 답변은 요약본으로 대체
                .doesNotContain("첫 답변 장황한 본문")
                .contains("요약 섹션이 없는 Direct 답변");  // 요약 없는 답변만 원문 그대로 LLM 몫
        assertThat(service.buildContext(UID, TID)).contains("LLM 요약");
    }

    @Test
    @DisplayName("precompute — LOCAL_FAST(MICRO_TEXT 오프로드) provider 가 없으면 LLM 을 부르지 않는다")
    void precompute_withoutMicroTextOffloadProvider_skipsLlmSummary() {
        when(llmRouter.hasMicroTextOffloadProvider()).thenReturn(false);
        when(memoryService.getRecentTurns(UID, TID))
                .thenReturn(List.of(turn(1, "질문", "요약 섹션이 없는 답변", null)));

        service.precompute(UID, TID, Locale.KOREAN);

        verify(llmRouter, never()).executeWithTracking(any(), any(), any(), any());
    }

    // 아래 두 테스트는 buildContext() 결과로 요약 내용을 확인한다 — 캐시에 직접 접근할 수단이
    // 없기 때문. buildContext()는 요약 뒤에 최근 recentRawTurns(=2)개 턴의 *원문*을 덧붙이므로,
    // "요약에는 없어야 한다"를 검증하려는 턴은 반드시 그 창 밖(=3번째 이전)에 두어야 한다.
    // 그러지 않으면 원문 구간에 걸려 doesNotContain 이 항상 실패한다.

    @Test
    @DisplayName("precompute — LOCAL_FAST 가 없어도 이미 뽑아둔 '## 요약' 은 버리지 않는다 "
            + "(요약 없는 답변 하나 때문에 전체를 포기하지 않음)")
    void precompute_withoutMicroTextOffloadProvider_keepsExistingSummarySections() {
        when(llmRouter.hasMicroTextOffloadProvider()).thenReturn(false);
        when(memoryService.getRecentTurns(UID, TID)).thenReturn(List.of(
                turn(1, "질문1", ragAnswer("첫 답변 한 줄 요약", "첫 답변 장황한 본문"), null),
                turn(2, "질문2", ragAnswer("둘째 답변 요약", "둘째 답변 본문"), null),
                turn(3, "질문3", "요약 섹션이 없는 Direct 답변", null)));

        service.precompute(UID, TID, Locale.KOREAN);

        verify(llmRouter, never()).executeWithTracking(any(), any(), any(), any());
        assertThat(service.buildContext(UID, TID))
                .contains("첫 답변 한 줄 요약")             // RAG 턴은 자기 요약으로 들어가고
                .doesNotContain("첫 답변 장황한 본문")      // 본문은 빠진다(원문 창 밖이므로)
                .contains("요약 섹션이 없는 Direct 답변");  // 요약 없는 턴도 (상한 내에서) 담긴다
    }

    @Test
    @DisplayName("precompute — LLM 없이 조립할 때 요약 없는 답변은 상한만큼만 담긴다 "
            + "(긴 Direct 답변 하나가 요약 예산을 독식하지 않도록)")
    void precompute_withoutMicroTextOffloadProvider_capsUnsummarizedAnswer() {
        when(llmRouter.hasMicroTextOffloadProvider()).thenReturn(false);
        String longDirect = "가".repeat(1200) + "꼬리표";
        when(memoryService.getRecentTurns(UID, TID)).thenReturn(List.of(
                turn(1, "질문1", longDirect, null),
                turn(2, "질문2", ragAnswer("둘째 답변 요약", "둘째 답변 본문"), null),
                turn(3, "질문3", ragAnswer("셋째 답변 요약", "셋째 답변 본문"), null)));

        service.precompute(UID, TID, Locale.KOREAN);

        assertThat(service.buildContext(UID, TID))
                .doesNotContain("꼬리표")        // 앞쪽 긴 답변은 300자에서 잘리고
                .contains("셋째 답변 요약");      // 최신 턴의 요약은 살아남는다
    }

    @Test
    @DisplayName("precompute — LOCAL_FAST 가 없어도 '## 요약' 기반 요약은 그대로 동작한다")
    void precompute_withoutMicroTextOffloadProvider_stillUsesSummarySections() {
        when(llmRouter.hasMicroTextOffloadProvider()).thenReturn(false);
        when(memoryService.getRecentTurns(UID, TID)).thenReturn(List.of(
                turn(1, "질문1", ragAnswer("첫 답변 한 줄 요약", "첫 답변 장황한 본문"), null)));

        service.precompute(UID, TID, Locale.KOREAN);

        verify(llmRouter, never()).executeWithTracking(any(), any(), any(), any());
        assertThat(service.buildContext(UID, TID)).contains("첫 답변 한 줄 요약");
    }

    /** prompt.answer.system 이 지시하는 고정 5-섹션 RAG 답변 형식(요약 → 상세 설명 → … → 참고). */
    private static String ragAnswer(String summary, String detail) {
        return """
                ## 요약
                %s

                ## 상세 설명
                %s

                ## 참고
                - [doc.md | p.1] (제목)
                """.formatted(summary, detail);
    }

    @Test
    @DisplayName("precompute — LOCAL provider 없음(LlmProviderExhaustedException) 시 캐시에 남지 않음")
    void precompute_noLocalProvider_leavesCacheEmpty() {
        when(memoryService.getRecentTurns(UID, TID)).thenReturn(threeTurns());
        when(llmRouter.executeWithTracking(any(), any(), any(), any()))
                .thenThrow(new LlmProviderExhaustedException("no local provider"));

        service.precompute(UID, TID, Locale.KOREAN);

        assertThat(service.buildContext(UID, TID)).isNull();
    }

    @Test
    @DisplayName("precompute — 알 수 없는 예외도 전파하지 않고 조용히 실패한다")
    void precompute_unexpectedException_doesNotPropagate() {
        when(memoryService.getRecentTurns(UID, TID)).thenReturn(threeTurns());
        when(llmRouter.executeWithTracking(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("boom"));

        service.precompute(UID, TID, Locale.KOREAN); // must not throw

        assertThat(service.buildContext(UID, TID)).isNull();
    }

    @Test
    @DisplayName("precompute — TTL 이내 재호출은 LLM 을 다시 부르지 않는다")
    void precompute_withinTtl_skipsSecondLlmCall() {
        // 3턴 이상이어야 요약 대상(최근 2턴을 뺀 나머지)이 생겨 LLM 경로가 실행된다.
        when(memoryService.getRecentTurns(anyString(), anyString()))
                .thenReturn(List.of(turn(1, "질문", "답변", null),
                                    turn(2, "질문2", "답변2", null),
                                    turn(3, "질문3", "답변3", null)));
        when(llmRouter.executeWithTracking(any(), any(), any(), any())).thenReturn("요약");

        service.precompute(UID, TID, Locale.KOREAN);
        service.precompute(UID, TID, Locale.KOREAN);

        verify(llmRouter, times(1)).executeWithTracking(any(), any(), any(), any());
    }

    @Test
    @DisplayName("invalidate — 캐시 제거 후 buildContext() 는 다시 null")
    void invalidate_clearsCache() {
        when(memoryService.getRecentTurns(UID, TID)).thenReturn(threeTurns());
        when(llmRouter.executeWithTracking(any(), any(), any(), any())).thenReturn("요약");
        service.precompute(UID, TID, Locale.KOREAN);
        assertThat(service.buildContext(UID, TID)).isNotNull();

        service.invalidate(TID);

        assertThat(service.buildContext(UID, TID)).isNull();
    }

    @Test
    @DisplayName("invalidate — TTL 도 함께 초기화되어 재호출 즉시 LLM 을 다시 부른다")
    void invalidate_resetsTtlGuardToo() {
        // 3턴 이상이어야 요약 대상(최근 2턴을 뺀 나머지)이 생겨 LLM 경로가 실행된다.
        when(memoryService.getRecentTurns(anyString(), anyString()))
                .thenReturn(List.of(turn(1, "질문", "답변", null),
                                    turn(2, "질문2", "답변2", null),
                                    turn(3, "질문3", "답변3", null)));
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
        when(memoryService.getRecentTurns(UID, TID)).thenReturn(List.of(
                turn(1, "질문1", longAnswer, null),
                turn(2, "질문2", longAnswer, null),
                turn(3, "질문3", longAnswer, null)));
        when(llmRouter.executeWithTracking(eq(TaskType.MICRO_TEXT), eq(RoutingMode.LOCAL_ONLY),
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
        when(memoryService.getRecentTurns(UID, TID)).thenReturn(List.of(
                turn(1, "질문1", "답변1", null),
                turn(2, "질문2", "답변2", null),
                turn(3, "질문3", "답변3", null)));
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

    @Test
    @DisplayName("precompute(turnId) — 캐싱 시점에 해당 turn 이 DISLIKE면 결과를 버린다")
    void precompute_withTurnId_discardsResultIfDisliked() {
        when(memoryService.getRecentTurns(UID, TID)).thenReturn(List.of(
                turn(1, "질문", "답변", null),
                turn(2, "질문2", "답변2", null),
                turn(3, "질문3", "답변3", null)));
        when(llmRouter.executeWithTracking(any(), any(), any(), any())).thenReturn("요약");
        when(memoryService.getFeedback(UID, TID, 1L))
                .thenReturn(Optional.of(new MemoryRepository.FeedbackRow("DISLIKE")));

        service.precompute(UID, TID, 1L, Locale.KOREAN);

        assertThat(service.buildContext(UID, TID)).isNull();
    }

    @Test
    @DisplayName("precompute(turnId) — DISLIKE 가 아니면 평소처럼 캐싱된다")
    void precompute_withTurnId_cachesWhenNotDisliked() {
        when(memoryService.getRecentTurns(UID, TID)).thenReturn(List.of(
                turn(1, "질문", "답변", null),
                turn(2, "질문2", "답변2", null),
                turn(3, "질문3", "답변3", null)));
        when(llmRouter.executeWithTracking(any(), any(), any(), any())).thenReturn("요약");
        when(memoryService.getFeedback(UID, TID, 1L)).thenReturn(Optional.empty());

        service.precompute(UID, TID, 1L, Locale.KOREAN);

        assertThat(service.buildContext(UID, TID)).contains("요약");
    }

    @Test
    @DisplayName("precomputeAfterTurn — 답변 완료 직후 캐시를 무효화하고 백그라운드로 재생성한다")
    void precomputeAfterTurn_regeneratesCacheInBackground() {
        // 요약 대상(최근 2턴을 뺀 나머지)이 있어야 LLM 요약이 만들어지므로 3턴에서 시작한다.
        when(memoryService.getRecentTurns(UID, TID)).thenReturn(List.of(
                turn(1, "질문1", "답변1", null),
                turn(2, "질문2", "답변2", null),
                turn(3, "질문3", "답변3", null)));
        when(llmRouter.executeWithTracking(any(), any(), any(), any())).thenReturn("옛 요약");
        service.precompute(UID, TID, Locale.KOREAN);
        assertThat(service.buildContext(UID, TID)).contains("옛 요약");

        // A new turn (id=4) is persisted; precomputeAfterTurn should invalidate + regenerate.
        when(memoryService.getRecentTurns(UID, TID)).thenReturn(List.of(
                turn(1, "질문1", "답변1", null), turn(2, "질문2", "답변2", null),
                turn(3, "질문3", "답변3", null), turn(4, "질문4", "답변4", null)));
        when(llmRouter.executeWithTracking(any(), any(), any(), any())).thenReturn("새 요약");
        when(memoryService.getFeedback(UID, TID, 4L)).thenReturn(Optional.empty());

        service.precomputeAfterTurn(UID, TID, 4L, Locale.KOREAN);

        awaitCondition(() -> {
            String ctx = service.buildContext(UID, TID);
            return ctx != null && ctx.contains("새 요약");
        });
        assertThat(service.buildContext(UID, TID)).contains("새 요약").doesNotContain("옛 요약");
    }

    @Test
    @DisplayName("precomputeAfterTurn — 백그라운드 재생성 도중 해당 turn 이 DISLIKE되면 캐시를 비운 채로 둔다")
    void precomputeAfterTurn_leavesCacheEmptyWhenTurnDisliked() {
        when(memoryService.getRecentTurns(UID, TID)).thenReturn(List.of(
                turn(1, "질문", "답변", null),
                turn(2, "질문2", "답변2", null),
                turn(3, "질문3", "답변3", null)));
        when(llmRouter.executeWithTracking(any(), any(), any(), any())).thenReturn("요약");
        when(memoryService.getFeedback(UID, TID, 1L))
                .thenReturn(Optional.of(new MemoryRepository.FeedbackRow("DISLIKE")));

        service.precomputeAfterTurn(UID, TID, 1L, Locale.KOREAN);

        awaitCondition(() -> {
            // never() isn't usable here (background thread), so poll for the LLM call to have
            // happened, then assert the cache stayed empty despite it.
            try {
                verify(llmRouter, times(1)).executeWithTracking(any(), any(), any(), any());
                return true;
            } catch (AssertionError notYet) {
                return false;
            }
        });
        assertThat(service.buildContext(UID, TID)).isNull();
    }

    /** Polls a fast-completing background virtual thread instead of a fixed sleep. */
    private static void awaitCondition(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError("condition not met within timeout");
    }
}
