package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.repository.MemoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * §10.12 — 짧은 후속 질문의 독립화(condense).
 *
 * <p>여기서 고정하는 것 중 가장 중요한 것은 <b>재료에 답변이 들어가지 않는다</b>는 것이다.
 * 그 하나가 §10.12 열린 항목 (b)(Direct 턴의 오염)를 구조적으로 닫는다 — 모델이 지어낸 문장이
 * 검색어의 재료가 될 수 있는 경로 자체가 없어야 한다.
 */
class QuestionCondenserTest {

    private static final String PROMPT_TEMPLATE =
            "이전 질문들:\n{history}\n---\n{query}";

    private static MemoryRepository.Turn turn(long id, String q, String a, boolean direct) {
        return new MemoryRepository.Turn(id, q, a, "2026-09-03 00:00:00", "2026-09-03 00:00:01",
                10, 10, 100, "local", 1, null, "N", null, direct);
    }

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /** 게이트가 열린 기본 설정: multiquery on, 최소 길이 15자. */
    private static AppProperties props() {
        AppProperties props = mock(AppProperties.class);
        when(props.searchMultiqueryEnabledSafe()).thenReturn(true);
        when(props.searchMultiqueryMinLengthSafe()).thenReturn(15);
        when(props.llmSafe()).thenReturn(mock(AppProperties.LlmConfig.class));
        return props;
    }

    private static MessageSource bundle() {
        MessageSource messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(eq("prompt.retrieval.condense"), any(), any(Locale.class)))
                .thenReturn(PROMPT_TEMPLATE);
        return messageSource;
    }

    /** {@code executeGatedWithUsage} 를 주어진 응답으로 답하게 만든다. */
    @SuppressWarnings("unchecked")
    private static LlmRouter routerReturning(String response) {
        LlmRouter router = mock(LlmRouter.class);
        when(router.executeGatedWithUsage(any(TaskType.class), any(RoutingMode.class), any()))
                .thenAnswer(inv -> {
                    Function<ChatModel, ChatResponse> fn = inv.getArgument(2);
                    ChatModel model = mock(ChatModel.class);
                    when(model.call(any(Prompt.class))).thenReturn(chatResponse(response));
                    fn.apply(model);
                    return new LlmRouter.LlmResult(response, 40, 12);
                });
        return router;
    }

    // ── 게이트 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("게이트는 원문 길이만 본다 — 임계값 미만이면 열리고, 이상이면 닫힌다(shouldExpand 의 여집합)")
    void gateIsTheComplementOfExpansion() {
        QuestionCondenser condenser = new QuestionCondenser(
                mock(LlmRouter.class), mock(MemoryService.class), bundle(), props());

        assertThat(condenser.gateOpen("그 설정은 어디야?")).isTrue();       // 10자
        assertThat(condenser.gateOpen("012345678901234")).isFalse();       // 정확히 15자
        assertThat(condenser.gateOpen("이것은 확장 대상이 되는 충분히 긴 질문입니다")).isFalse();
        assertThat(condenser.gateOpen("   ")).isFalse();
        assertThat(condenser.gateOpen(null)).isFalse();
    }

    @Test
    @DisplayName("multiquery 스위치를 내리면 독립화도 함께 꺼진다 — 둘은 같은 성질의 질의 전처리 호출이다")
    void sharesTheMultiqueryOffSwitch() {
        AppProperties props = props();
        when(props.searchMultiqueryEnabledSafe()).thenReturn(false);

        QuestionCondenser condenser = new QuestionCondenser(
                mock(LlmRouter.class), mock(MemoryService.class), bundle(), props);

        assertThat(condenser.gateOpen("그 설정은 어디야?")).isFalse();
    }

    // ── 재료 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("재료는 이전 '질문'뿐 — 답변은 프롬프트에 들어가지 않는다(Direct 턴 오염 차단)")
    void materialCarriesQuestionsOnly() {
        String inventedTerm = "SseSuperTurboHandler";
        MemoryService memory = mock(MemoryService.class);
        when(memory.getRecentTurns(anyString(), anyString())).thenReturn(List.of(
                turn(1, "SSE 타임아웃 설정 어떻게 바꿔?", "답변에는 " + inventedTerm + " 를 쓰면 됩니다", true)));

        LlmRouter router = routerReturning("SSE 타임아웃 설정은 어디에 있어?");
        QuestionCondenser condenser = new QuestionCondenser(router, memory, bundle(), props());

        condenser.condense("u1", "t1", "그거 어디야?", Locale.KOREAN);

        ArgumentCaptor<Function<ChatModel, ChatResponse>> fn = ArgumentCaptor.forClass(Function.class);
        verify(router).executeGatedWithUsage(eq(TaskType.MICRO_TEXT), eq(RoutingMode.COST_FIRST), fn.capture());

        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(chatResponse("x"));
        fn.getValue().apply(model);
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        String sent = prompt.getValue().getContents();

        assertThat(sent)
                .as("이전 질문은 재료다")
                .contains("SSE 타임아웃 설정 어떻게 바꿔?");
        assertThat(sent)
                .as("Direct 답변이 지어낸 이름이 검색어의 재료가 되는 경로 자체가 없어야 한다")
                .doesNotContain(inventedTerm);
    }

    @Test
    @DisplayName("첫 질문이면 LLM 을 부르지 않는다 — 기댈 맥락이 없으므로 짧은 것이 후속 질문이라는 뜻이 아니다")
    void firstQuestionSkipsTheCallEntirely() {
        MemoryService memory = mock(MemoryService.class);
        when(memory.getRecentTurns(anyString(), anyString())).thenReturn(List.of());
        LlmRouter router = mock(LlmRouter.class);

        QuestionCondenser condenser = new QuestionCondenser(router, memory, bundle(), props());

        assertThat(condenser.condense("u1", "t1", "그거 어디야?", Locale.KOREAN)).isEmpty();
        verify(router, never()).executeGatedWithUsage(any(), any(), any());
    }

    // ── 결과 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("재작성되면 그 질문과 호출 토큰을 함께 돌려준다 — 그래프 밖 호출이라 호출부가 실어 줘야 한다")
    void returnsRewriteWithUsage() {
        MemoryService memory = mock(MemoryService.class);
        when(memory.getRecentTurns(anyString(), anyString()))
                .thenReturn(List.of(turn(1, "SSE 타임아웃 설정 어떻게 바꿔?", "답변", false)));

        QuestionCondenser condenser = new QuestionCondenser(
                routerReturning("SSE 타임아웃 설정은 어디에 있어?"), memory, bundle(), props());

        Optional<QuestionCondenser.Condensed> out =
                condenser.condense("u1", "t1", "그거 어디야?", Locale.KOREAN);

        assertThat(out).isPresent();
        assertThat(out.get().searchQuestion()).isEqualTo("SSE 타임아웃 설정은 어디에 있어?");
        assertThat(out.get().inputTokens()).isEqualTo(40);
        assertThat(out.get().outputTokens()).isEqualTo(12);
    }

    @Test
    @DisplayName("이미 자립적이라 모델이 원문을 그대로 돌려주면 '재작성 없음'이다 — 짧지만 자립적인 질문의 오염 방지")
    void unchangedRewriteCountsAsNoRewrite() {
        MemoryService memory = mock(MemoryService.class);
        when(memory.getRecentTurns(anyString(), anyString()))
                .thenReturn(List.of(turn(1, "인덱싱 청크 크기 얼마야?", "답변", false)));

        QuestionCondenser condenser = new QuestionCondenser(
                routerReturning("SSE 타임아웃?"), memory, bundle(), props());

        assertThat(condenser.condense("u1", "t1", "SSE 타임아웃?", Locale.KOREAN)).isEmpty();
    }

    @Test
    @DisplayName("호출이 실패하면 원문으로 검색한다 — 재작성이 없다고 검색을 멈추지 않는다")
    void llmFailureFallsBackToTheOriginal() {
        MemoryService memory = mock(MemoryService.class);
        when(memory.getRecentTurns(anyString(), anyString()))
                .thenReturn(List.of(turn(1, "SSE 타임아웃 설정 어떻게 바꿔?", "답변", false)));

        LlmRouter router = mock(LlmRouter.class);
        when(router.executeGatedWithUsage(any(), any(), any()))
                .thenThrow(new IllegalStateException("All providers exhausted"));

        QuestionCondenser condenser = new QuestionCondenser(router, memory, bundle(), props());

        assertThat(condenser.condense("u1", "t1", "그거 어디야?", Locale.KOREAN)).isEmpty();
    }

    // ── 파싱 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("첫 줄만 취하고 감싼 따옴표는 벗긴다")
    void parseKeepsOneLineAndStripsWrappingQuotes() {
        assertThat(QuestionCondenser.parse("\"SSE 타임아웃 설정은 어디야?\"\n부연 설명"))
                .isEqualTo("SSE 타임아웃 설정은 어디야?");
        assertThat(QuestionCondenser.parse("\n\n  SSE 타임아웃 설정  \n"))
                .isEqualTo("SSE 타임아웃 설정");
    }

    @Test
    @DisplayName("너무 긴 응답은 잘라 쓰지 않고 버린다 — 앞부분만 자르면 원문보다 나쁜 질의가 된다")
    void parseDiscardsOverlongOutput() {
        assertThat(QuestionCondenser.parse("가".repeat(QuestionCondenser.MAX_CONDENSED_CHARS + 1))).isNull();
        assertThat(QuestionCondenser.parse("")).isNull();
        assertThat(QuestionCondenser.parse(null)).isNull();
    }
}
