package com.example.ragagent.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmProvider;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.llm.ProviderRole;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.model.ResponseMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.MessageSource;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA — AnswerService 경로
 *
 * Covers (per refactoring/01-test-safety-net.md):
 *  - BLOCKING (COST_FIRST)
 *  - STREAMING (GraphListener token 전달)
 *  - PROGRESSIVE 업그레이드 (sufficient=false 후 PREMIUM 호출)
 *
 * executeBlocking()/evaluate() now route through LlmRouter.executeGatedWithUsage() (TaskType.TEXT,
 * state.routingMode()) instead of a directly-injected ChatClient bound to a single boot-time-fixed
 * model — this was previously invisible to /llm-usage and ignored the request's routing mode
 * entirely (§6.14 in PLAN.md), and its real per-call token usage now accumulates into AgentState's
 * per-turn totals too (previously discarded — every call site fed accumulateTokens(0, 0)).
 * Streaming paths still can't read real ChatResponse usage, so they record an approximate
 * (chars/4) usage entry via LlmRouter.recordApproxUsage() and fold the same estimate into
 * AgentState's totals.
 */
@ResourceLock("global-state")
class AnswerServiceTest {

    private static final int MAX_RETRY = 2;

    private LlmRouter llmRouter;
    private MessageSource messageSource;
    private ProviderContextWindows contextWindows;
    private AnswerService service;

    @BeforeEach
    void setUp() {
        llmRouter = mock(LlmRouter.class);
        AppProperties props = new AppProperties(
                "./data", MAX_RETRY, 800, 100, 100, 7, 0.0, true, 0, false,
                true, false, 3,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        contextWindows = new ProviderContextWindows();   // 비어 있음 = 창 모름 → 예산 축소 없음
        messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("prompt");
        service = new AnswerService(llmRouter, props, messageSource, contextWindows);
    }

    // ── 입력 예산 (컨텍스트 창 기반 사전 축소) ────────────────────────────────

    /** 한글 n자 = TokenEstimator 기준 n토큰 — 예산 계산이 눈에 보이게 하려고 한국어로 만든다. */
    private static org.springframework.ai.document.Document koreanDoc(int chars) {
        return new org.springframework.ai.document.Document("가".repeat(chars));
    }

    /**
     * <b>첫</b> 프롬프트만 붙잡는 라우터 스텁 — 한 턴은 답변 호출에 이어 검증 호출을 내고, 마지막을
     * 잡으면 검증 프롬프트({@code [답변]}/{@code [문서 발췌]})를 보게 된다. 입력 예산이 걸리는 곳은
     * 답변 프롬프트이므로 처음 것을 남긴다.
     */
    private java.util.concurrent.atomic.AtomicReference<String> capturePrompt() {
        var seen = new java.util.concurrent.atomic.AtomicReference<String>();
        when(llmRouter.executeGatedWithUsage(any(), any(), any())).thenAnswer(inv -> {
            java.util.function.Function<org.springframework.ai.chat.model.ChatModel,
                    org.springframework.ai.chat.model.ChatResponse> fn = inv.getArgument(2);
            org.springframework.ai.chat.model.ChatModel probe =
                    mock(org.springframework.ai.chat.model.ChatModel.class);
            when(probe.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenAnswer(call -> {
                org.springframework.ai.chat.prompt.Prompt prompt = call.getArgument(0);
                seen.compareAndSet(null, prompt.getInstructions().stream()
                        .map(org.springframework.ai.chat.messages.Message::getText)
                        .reduce("", (a, b) -> a + "\n" + b));
                return chatResponse("답변");
            });
            fn.apply(probe);
            return new LlmRouter.LlmResult("답변", 0, 0);
        });
        return seen;
    }

    @Test
    @DisplayName("컨텍스트 창을 모르면 아무것도 자르지 않는다 — 추측으로 근거를 버리지 않는다")
    void unknownContextWindowTrimsNothing() {
        // contextWindows 는 setUp 에서 비어 있다.
        when(llmRouter.findProviderName(any(), any())).thenReturn("lm");
        var seen = capturePrompt();

        AgentState state = newState(RoutingMode.COST_FIRST).toBuilder()
                .retrievedDocs(List.of(koreanDoc(4_000), koreanDoc(4_000), koreanDoc(4_000)))
                .build();
        service.execute(state);

        // 세 문서가 모두 실려야 한다(각 4,000자).
        assertThat(seen.get()).isNotNull();
        assertThat(countOccurrences(seen.get(), "가".repeat(4_000))).isEqualTo(3);
    }

    @Test
    @DisplayName("창이 좁으면 관련도 낮은 뒤쪽 문서부터 덜어낸다")
    void narrowContextDropsLowestRankedDocumentsFirst() {
        // 창 13,000 → 예산 = 13,000 − 예약 7,000 − 여유 1,300 = 4,700 토큰.
        // 고정비 ≈ 203(목 시스템 프롬프트 "prompt" + 질문 + 섹션 머리말 200)이므로
        // 문서 셋(각 ~2,003) 중 둘까지만 들어간다: 203+2,003+2,003=4,209 ≤ 4,700 < 6,212.
        contextWindows.record("lm", 13_000, ProviderContextWindows.Source.CONFIGURED);
        when(llmRouter.findProviderName(any(), any())).thenReturn("lm");
        var seen = capturePrompt();

        // 문서를 서로 구분되게 만든다 — 무엇이 남았는지 확인해야 하므로.
        var first  = new org.springframework.ai.document.Document("첫번째" + "가".repeat(2_000));
        var second = new org.springframework.ai.document.Document("두번째" + "나".repeat(2_000));
        var third  = new org.springframework.ai.document.Document("세번째" + "다".repeat(2_000));
        AgentState state = newState(RoutingMode.COST_FIRST).toBuilder()
                .retrievedDocs(List.of(first, second, third))
                .build();
        service.execute(state);

        String prompt = seen.get();
        assertThat(prompt).as("가장 관련도 높은 문서는 남아야 한다").contains("첫번째");
        assertThat(prompt).as("예산이 허락하는 만큼은 담는다").contains("두번째");
        assertThat(prompt).as("최저 관련도 문서가 먼저 버려진다").doesNotContain("세번째");
    }

    @Test
    @DisplayName("문서를 다 덜어내도 모자라면 대화 이력을 오래된 턴부터 버린다")
    void historyIsTrimmedOldestFirstWhenDocumentsAreNotEnough() {
        // 창 10,000 → 예산 2,000. 고정비 ~203 + 문서 1,000 을 빼면 이력에 ~797 만 남아,
        // 1,500자짜리 오래된 턴은 버려지고 200자짜리 최근 턴만 살아남는다.
        contextWindows.record("lm", 10_000, ProviderContextWindows.Source.CONFIGURED);
        when(llmRouter.findProviderName(any(), any())).thenReturn("lm");
        var seen = capturePrompt();

        String history = "Q: 가장오래된질문\nA: " + "옛".repeat(1_500)
                + "\n\nQ: 최근질문\nA: " + "새".repeat(200);
        AgentState state = newState(RoutingMode.COST_FIRST).toBuilder()
                .conversationHistory(history)
                .retrievedDocs(List.of(koreanDoc(1_000)))
                .build();
        service.execute(state);

        String prompt = seen.get();
        assertThat(prompt).as("최근 턴은 남아야 한다").contains("최근질문");
        assertThat(prompt).as("가장 오래된 턴부터 버린다").doesNotContain("가장오래된질문");
    }

    @Test
    @DisplayName("이력은 턴 경계에서 잘린다 — 반쪽짜리 턴을 남기지 않는다")
    void historyIsCutAtTurnBoundaries() {
        // 창 10,000 → 예산 2,000, 고정비 ~203 → 이력에 ~1,797. 2,000자 턴은 못 들어간다.
        contextWindows.record("lm", 10_000, ProviderContextWindows.Source.CONFIGURED);
        when(llmRouter.findProviderName(any(), any())).thenReturn("lm");
        var seen = capturePrompt();

        String history = "Q: 첫질문\nA: " + "옛".repeat(2_000) + "\n\nQ: 둘째질문\nA: 짧은답";
        AgentState state = newState(RoutingMode.COST_FIRST).toBuilder()
                .conversationHistory(history)
                .build();
        service.execute(state);

        String prompt = seen.get();
        // 남은 이력은 온전한 턴이어야 한다 — Q 와 A 가 짝으로 들어 있다.
        assertThat(prompt).contains("Q: 둘째질문");
        assertThat(prompt).contains("A: 짧은답");
        assertThat(prompt).doesNotContain("첫질문");
    }

    @Test
    @DisplayName("검증 발췌도 창에서 파생된다 — 답변이 길수록 발췌에 남는 자리가 줄어든다")
    void evalExcerptsDeriveFromTheContextWindow() {
        contextWindows.record("lm", 12_000, ProviderContextWindows.Source.CONFIGURED);
        when(llmRouter.findProviderName(any(), any())).thenReturn("lm");

        // 검증 프롬프트(두 번째 호출)를 잡는다 — 첫 번째는 답변 프롬프트다.
        var prompts = new java.util.ArrayList<String>();
        when(llmRouter.executeGatedWithUsage(any(), any(), any())).thenAnswer(inv -> {
            java.util.function.Function<org.springframework.ai.chat.model.ChatModel,
                    org.springframework.ai.chat.model.ChatResponse> fn = inv.getArgument(2);
            org.springframework.ai.chat.model.ChatModel probe =
                    mock(org.springframework.ai.chat.model.ChatModel.class);
            when(probe.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenAnswer(call -> {
                org.springframework.ai.chat.prompt.Prompt prompt = call.getArgument(0);
                prompts.add(prompt.getInstructions().stream()
                        .map(org.springframework.ai.chat.messages.Message::getText)
                        .reduce("", (a, b) -> a + "\n" + b));
                return chatResponse("답변");
            });
            fn.apply(probe);
            return new LlmRouter.LlmResult("답변", 0, 0);
        });

        var first  = new org.springframework.ai.document.Document("첫번째" + "가".repeat(1_000));
        var second = new org.springframework.ai.document.Document("두번째" + "나".repeat(1_000));
        var third  = new org.springframework.ai.document.Document("세번째" + "다".repeat(1_000));
        AgentState state = newState(RoutingMode.COST_FIRST).toBuilder()
                .retrievedDocs(List.of(first, second, third))
                .build();
        service.execute(state);

        assertThat(prompts).as("답변 + 검증 두 호출이 나가야 한다").hasSizeGreaterThanOrEqualTo(2);
        String evalPrompt = prompts.get(1);
        // 창 12,000 → PromptBudget(12,000, 2,048).inputBudget() = 12,000 − 2,048 − 1,200 = 8,752.
        // 시스템 프롬프트("prompt" 목)·답변·질문·스키마를 빼도 문서 셋(각 ~1,003)은 다 들어간다.
        assertThat(evalPrompt).contains("첫번째", "두번째", "세번째");
    }

    @Test
    @DisplayName("검증 창이 좁으면 발췌도 하위 순위부터 줄어든다")
    void evalExcerptsShrinkOnANarrowWindow() {
        contextWindows.record("lm", 4_096, ProviderContextWindows.Source.CONFIGURED);
        when(llmRouter.findProviderName(any(), any())).thenReturn("lm");

        var prompts = new java.util.ArrayList<String>();
        when(llmRouter.executeGatedWithUsage(any(), any(), any())).thenAnswer(inv -> {
            java.util.function.Function<org.springframework.ai.chat.model.ChatModel,
                    org.springframework.ai.chat.model.ChatResponse> fn = inv.getArgument(2);
            org.springframework.ai.chat.model.ChatModel probe =
                    mock(org.springframework.ai.chat.model.ChatModel.class);
            when(probe.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenAnswer(call -> {
                org.springframework.ai.chat.prompt.Prompt prompt = call.getArgument(0);
                prompts.add(prompt.getInstructions().stream()
                        .map(org.springframework.ai.chat.messages.Message::getText)
                        .reduce("", (a, b) -> a + "\n" + b));
                return chatResponse("답변");
            });
            fn.apply(probe);
            return new LlmRouter.LlmResult("답변", 0, 0);
        });

        var first  = new org.springframework.ai.document.Document("첫번째" + "가".repeat(1_500));
        var second = new org.springframework.ai.document.Document("두번째" + "나".repeat(1_500));
        AgentState state = newState(RoutingMode.COST_FIRST).toBuilder()
                .retrievedDocs(List.of(first, second))
                .build();
        service.execute(state);

        String evalPrompt = prompts.get(1);
        // 창 4,096 → 예산 4,096 − 2,048 − 409 = 1,639. 첫 문서(1,503)만 들어간다.
        assertThat(evalPrompt).as("첫 발췌는 예산을 넘어도 남는다").contains("첫번째");
        assertThat(evalPrompt).as("좁은 창에서는 하위 순위 발췌가 빠진다").doesNotContain("두번째");
    }

    @Test
    @DisplayName("턴 경계가 없는 이력(요약 경로)도 통째로 버리지 않고 줄 단위로 줄인다")
    void historyWithoutTurnBoundariesIsTrimmedByLine() {
        // §6.10 요약 경로는 "Q:/A:" 쌍이 아니라 요약문을 준다 — 예전 구현은 경계를 못 찾아
        // 이력 전체를 버렸고, 예산이 조금 모자랄 뿐인데 대화 맥락을 통째로 잃었다.
        contextWindows.record("lm", 10_000, ProviderContextWindows.Source.CONFIGURED);
        when(llmRouter.findProviderName(any(), any())).thenReturn("lm");
        var seen = capturePrompt();

        String summary = "오래된요약" + "옛".repeat(2_000) + "\n최근요약줄";
        AgentState state = newState(RoutingMode.COST_FIRST).toBuilder()
                .conversationHistory(summary)
                .build();
        service.execute(state);

        String prompt = seen.get();
        assertThat(prompt).as("이력이 통째로 사라지면 안 된다").contains("최근요약줄");
        assertThat(prompt).as("앞쪽(오래된 줄)부터 버린다").doesNotContain("오래된요약");
    }

    @Test
    @DisplayName("PROGRESSIVE 업그레이드는 PREMIUM 의 창으로 예산을 다시 잡는다 — 로컬 기준으로 깎지 않는다")
    void premiumUpgradeBudgetsAgainstThePremiumProvider() {
        // 로컬은 좁고(8,192) PREMIUM 은 넓다(64,000). 예전에는 두 호출 모두 state.routingMode() 로
        // 프로바이더를 찾아, 넓은 창으로 가는 업그레이드 답변까지 좁은 창 기준으로 문서를 버렸다.
        contextWindows.record("local", 8_192, ProviderContextWindows.Source.CONFIGURED);
        contextWindows.record("premium", 64_000, ProviderContextWindows.Source.CONFIGURED);
        when(llmRouter.findProviderName(any(), any())).thenReturn("local");

        var premium = new com.example.ragagent.llm.LlmProvider(
                "premium", com.example.ragagent.llm.TaskType.TEXT,
                com.example.ragagent.llm.ProviderRole.PREMIUM, 1, "k", null, null, false,
                mock(org.springframework.ai.chat.model.ChatModel.class), null);
        when(llmRouter.routeProvider(any(), eq(RoutingMode.QUALITY_FIRST))).thenReturn(premium);

        var prompts = new java.util.ArrayList<String>();
        when(llmRouter.executeGatedWithUsage(any(), any(), any())).thenAnswer(inv -> {
            java.util.function.Function<org.springframework.ai.chat.model.ChatModel,
                    org.springframework.ai.chat.model.ChatResponse> fn = inv.getArgument(2);
            org.springframework.ai.chat.model.ChatModel probe =
                    mock(org.springframework.ai.chat.model.ChatModel.class);
            when(probe.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenAnswer(call -> {
                org.springframework.ai.chat.prompt.Prompt prompt = call.getArgument(0);
                prompts.add(prompt.getInstructions().stream()
                        .map(org.springframework.ai.chat.messages.Message::getText)
                        .reduce("", (a, b) -> a + "\n" + b));
                return chatResponse("{\"sufficient\":false,\"grounded\":false}");
            });
            fn.apply(probe);
            // 첫 호출(답변)은 본문, 이후 검증 호출은 불충분 판정 → PROGRESSIVE 업그레이드 유발
            return new LlmRouter.LlmResult(
                    prompts.size() == 1 ? "답변" : "{\"sufficient\":false,\"grounded\":false}", 0, 0);
        });

        var docs = List.of(
                new org.springframework.ai.document.Document("첫번째" + "가".repeat(2_000)),
                new org.springframework.ai.document.Document("두번째" + "나".repeat(2_000)),
                new org.springframework.ai.document.Document("세번째" + "다".repeat(2_000)));
        // 업그레이드는 재시도를 다 쓴 뒤에만 일어난다(checkSufficiencyAndMaybeUpgrade).
        service.execute(newState(RoutingMode.PROGRESSIVE).toBuilder()
                .retrievedDocs(docs).retryCount(MAX_RETRY).build());

        // 한 턴에는 답변 호출과 검증 호출이 섞여 나간다 — 예산이 걸리는 것은 답변 프롬프트이고,
        // 그쪽만 "[검색된 문서]" 섹션을 갖는다(검증은 "[문서 발췌]").
        List<String> answerPrompts = prompts.stream().filter(p -> p.contains("[검색된 문서]")).toList();
        assertThat(answerPrompts).as("답변 호출이 두 번(원본 + 업그레이드) 나가야 한다").hasSizeGreaterThanOrEqualTo(2);

        assertThat(answerPrompts.getFirst())
                .as("원래 로컬 창(8,192)에서는 하위 문서가 잘린다").doesNotContain("세번째");
        assertThat(answerPrompts.getLast())
                .as("PREMIUM 창(64,000)이면 세 문서가 다 들어간다").contains("세번째");
    }

    @Test
    @DisplayName("발췌가 잘린 채 나온 '근거 없음'은 판정으로 삼지 않는다 — 빠진 문서에 근거가 있었을 수 있다")
    void groundedFalseOnTrimmedEvidenceBecomesNoVerdict() {
        // 창을 좁혀 검증 발췌가 반드시 잘리게 만든다. 답변 전문이 발췌 자리를 먹는 구조라
        // 답변이 길수록 이 상황이 쉽게 생긴다.
        contextWindows.record("lm", 8_000, ProviderContextWindows.Source.CONFIGURED);
        when(llmRouter.findProviderName(any(), any())).thenReturn("lm");

        var calls = new java.util.ArrayList<String>();
        when(llmRouter.executeGatedWithUsage(any(), any(), any())).thenAnswer(inv -> {
            calls.add("x");
            // 1번째 = 답변, 2번째 = 검증(근거 없음 판정)
            return new LlmRouter.LlmResult(
                    calls.size() == 1 ? "긴답변".repeat(500)
                                      : "{\"sufficient\":true,\"grounded\":false,\"reason\":\"근거 없음\"}",
                    0, 0);
        });

        var docs = List.of(
                new org.springframework.ai.document.Document("첫번째" + "가".repeat(1_500)),
                new org.springframework.ai.document.Document("두번째" + "나".repeat(1_500)),
                new org.springframework.ai.document.Document("세번째" + "다".repeat(1_500)));
        AgentState out = service.execute(
                newState(RoutingMode.COST_FIRST).toBuilder().retrievedDocs(docs).build());

        assertThat(out.grounded()).as("판정 없음이어야 한다 — false 로 굳히면 잘못된 미검증 배지가 붙는다").isNull();
        assertThat(out.needsRetry()).as("재시도해도 답변 길이와 창은 그대로라 같은 축소가 반복된다").isFalse();
        assertThat(out.evalReason()).isNull();
    }

    @Test
    @DisplayName("반대로 '근거 있음'은 발췌가 잘렸어도 그대로 신뢰한다 — 문서를 더 봤다고 근거가 사라지지 않는다")
    void groundedTrueOnTrimmedEvidenceIsStillTrusted() {
        contextWindows.record("lm", 8_000, ProviderContextWindows.Source.CONFIGURED);
        when(llmRouter.findProviderName(any(), any())).thenReturn("lm");

        var calls = new java.util.ArrayList<String>();
        when(llmRouter.executeGatedWithUsage(any(), any(), any())).thenAnswer(inv -> {
            calls.add("x");
            return new LlmRouter.LlmResult(
                    calls.size() == 1 ? "긴답변".repeat(500)
                                      : "{\"sufficient\":true,\"grounded\":true}",
                    0, 0);
        });

        var docs = List.of(
                new org.springframework.ai.document.Document("첫번째" + "가".repeat(1_500)),
                new org.springframework.ai.document.Document("두번째" + "나".repeat(1_500)),
                new org.springframework.ai.document.Document("세번째" + "다".repeat(1_500)));
        AgentState out = service.execute(
                newState(RoutingMode.COST_FIRST).toBuilder().retrievedDocs(docs).build());

        assertThat(out.grounded()).isTrue();
    }

    @Test
    @DisplayName("스트리밍은 출력 예약이 더 작다 — 캡을 보내지 않으므로 폭주 방지선을 뺄 이유가 없다")
    void streamingReservesLessThanBlocking() {
        // N: 블로킹은 실제로 보내는 값(10,000의 70%)을 그대로 빼야 계산이 맞고, 스트리밍은
        // 아무것도 보내지 않으므로 "답변이 자랄 자리"(minChars 5,000)면 충분하다.
        assertThat(AnswerService.outputReservation(ResponseMode.N, false, 10_000)).isEqualTo(7_000);
        assertThat(AnswerService.outputReservation(ResponseMode.N, true, 10_000)).isEqualTo(5_000);
    }

    @Test
    @DisplayName("S는 두 경로가 같다 — 이미 minChars가 블로킹 예산과 같은 자리다")
    void shortModeIsUnchanged() {
        assertThat(AnswerService.outputReservation(ResponseMode.S, false, 10_000)).isEqualTo(2_000);
        assertThat(AnswerService.outputReservation(ResponseMode.S, true, 10_000)).isEqualTo(2_000);
    }

    @Test
    @DisplayName("설정 상한이 작으면 그쪽이 이긴다 — 스트리밍 예약이 상한을 넘어설 수 없다")
    void neverExceedsTheConfiguredCeiling() {
        // max-tokens 2,000 배포: N의 minChars(5,000)가 아니라 2,000이 적용돼야 한다.
        assertThat(AnswerService.outputReservation(ResponseMode.N, true, 2_000)).isEqualTo(2_000);
        assertThat(AnswerService.outputReservation(ResponseMode.N, false, 2_000)).isEqualTo(2_000);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) { count++; idx += needle.length(); }
        return count;
    }

    private AgentState newState(RoutingMode mode) {
        return AgentState.of("질문", "v1", "t1", "", mode);
    }

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    // ── BLOCKING 경로 ────────────────────────────────────────────────────
    // 답변(executeBlocking)과 sufficiency(evaluate) 둘 다 llmRouter.executeGatedWithUsage()을
    // 같은 (TaskType.TEXT, routingMode)로 순서대로 호출하므로 thenReturn(a, b)로 스텁한다.

    @Test
    @DisplayName("BLOCKING COST_FIRST — 답변+sufficiency 2회 호출, llmCallCount=2, 실제 토큰 사용량 누적")
    void blocking_costFirst_basicFlow() {
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn(new LlmRouter.LlmResult("## 요약\n핵심 답변", 100, 40),
                            new LlmRouter.LlmResult("{\"sufficient\":true}", 30, 10));
        when(llmRouter.findProviderName(any(), any())).thenReturn("gemini-flash");

        AgentState result = service.execute(newState(RoutingMode.COST_FIRST));

        assertThat(result.answer()).isEqualTo("## 요약\n핵심 답변");
        assertThat(result.usedProvider()).isEqualTo("gemini-flash");
        assertThat(result.needsRetry()).isFalse();
        assertThat(result.totalInputTokens()).isEqualTo(130);
        assertThat(result.totalOutputTokens()).isEqualTo(50);
        assertThat(result.llmCallCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("BLOCKING — sufficiency=false 면 needsRetry=true")
    void blocking_sufficiency_false_setsNeedsRetry() {
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn(new LlmRouter.LlmResult("불완전 답변", 0, 0),
                            new LlmRouter.LlmResult("{\"sufficient\":false}", 0, 0));
        when(llmRouter.findProviderName(any(), any())).thenReturn("gemini-flash");

        AgentState result = service.execute(newState(RoutingMode.COST_FIRST));

        assertThat(result.needsRetry()).isTrue();
    }

    @Test
    @DisplayName("BLOCKING — 검증 미통과 시 평가 LLM 의 reason 이 evalReason 으로 담긴다")
    void blocking_evalReason_capturedOnFailure() {
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn(new LlmRouter.LlmResult("불완전 답변", 0, 0),
                            new LlmRouter.LlmResult(
                                    "{\"sufficient\":false,\"grounded\":true,"
                                    + "\"reason\":\"설치 절차는 있으나 질문한 포트 설정 값이 문서에 없음\"}", 0, 0));
        when(llmRouter.findProviderName(any(), any())).thenReturn("gemini-flash");

        AgentState result = service.execute(newState(RoutingMode.COST_FIRST));

        assertThat(result.needsRetry()).isTrue();
        assertThat(result.evalReason()).isEqualTo("설치 절차는 있으나 질문한 포트 설정 값이 문서에 없음");
    }

    @Test
    @DisplayName("BLOCKING — 검증 통과면 reason 을 줬더라도 evalReason 은 null (설명할 게 없음)")
    void blocking_evalReason_nullWhenPassed() {
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn(new LlmRouter.LlmResult("답변", 0, 0),
                            new LlmRouter.LlmResult(
                                    "{\"sufficient\":true,\"grounded\":true,\"reason\":\"문제 없음\"}", 0, 0));
        when(llmRouter.findProviderName(any(), any())).thenReturn("gemini-flash");

        AgentState result = service.execute(newState(RoutingMode.COST_FIRST));

        assertThat(result.needsRetry()).isFalse();
        assertThat(result.evalReason()).isNull();
    }

    @Test
    @DisplayName("BLOCKING — reason 이 비었거나 없으면 evalReason 은 null (모델이 안 줘도 깨지지 않음)")
    void blocking_evalReason_nullWhenModelOmitsIt() {
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn(new LlmRouter.LlmResult("불완전 답변", 0, 0),
                            new LlmRouter.LlmResult("{\"sufficient\":false,\"reason\":\"   \"}", 0, 0));
        when(llmRouter.findProviderName(any(), any())).thenReturn("gemini-flash");

        AgentState result = service.execute(newState(RoutingMode.COST_FIRST));

        assertThat(result.needsRetry()).isTrue();
        assertThat(result.evalReason()).isNull();
    }

    // ── 환경 의존 값(경로/주소/포트/환경변수) 안내 ──────────────────────────
    // 경로·호스트·포트·환경변수 값은 문서를 쓴 기계와 읽는 기계가 다르면 당연히 달라진다. 평가
    // 프롬프트가 그것만으로는 grounded=false 를 내지 못하게 막는 대신 envNote 로 받아, 검증 결과와
    // 무관하게 사용자에게 "이 값은 본인 환경 기준으로 바꿔야 한다"고 알린다.

    @Test
    @DisplayName("BLOCKING — 검증을 통과해도 envNote 는 남는다 (사용자에게 줄 안내라서)")
    void blocking_envNote_keptEvenWhenPassed() {
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn(new LlmRouter.LlmResult("답변", 0, 0),
                            new LlmRouter.LlmResult(
                                    "{\"sufficient\":true,\"grounded\":true,\"reason\":\"\","
                                    + "\"envNote\":\"설치 경로 /opt/app 과 포트 8080 은 환경에 따라 다를 수 있습니다\"}", 0, 0));
        when(llmRouter.findProviderName(any(), any())).thenReturn("gemini-flash");

        AgentState result = service.execute(newState(RoutingMode.COST_FIRST));

        assertThat(result.needsRetry()).isFalse();
        assertThat(result.grounded()).isTrue();
        assertThat(result.evalReason()).isNull();          // 통과했으니 실패 사유는 없고
        assertThat(result.envNote())                        // 안내만 남는다
                .isEqualTo("설치 경로 /opt/app 과 포트 8080 은 환경에 따라 다를 수 있습니다");
    }

    @Test
    @DisplayName("BLOCKING — 미통과 답변에서는 evalReason 과 envNote 가 함께 담긴다")
    void blocking_envNote_coexistsWithEvalReason() {
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn(new LlmRouter.LlmResult("불완전 답변", 0, 0),
                            new LlmRouter.LlmResult(
                                    "{\"sufficient\":false,\"grounded\":true,"
                                    + "\"reason\":\"재시도 횟수는 문서에 근거가 없음\","
                                    + "\"envNote\":\"로그 경로는 배포마다 다릅니다\"}", 0, 0));
        when(llmRouter.findProviderName(any(), any())).thenReturn("gemini-flash");

        AgentState result = service.execute(newState(RoutingMode.COST_FIRST));

        assertThat(result.needsRetry()).isTrue();
        assertThat(result.evalReason()).isEqualTo("재시도 횟수는 문서에 근거가 없음");
        assertThat(result.envNote()).isEqualTo("로그 경로는 배포마다 다릅니다");
    }

    @Test
    @DisplayName("BLOCKING — envNote 가 비었거나 없으면 null (모델이 안 줘도 깨지지 않음)")
    void blocking_envNote_nullWhenModelOmitsIt() {
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn(new LlmRouter.LlmResult("답변", 0, 0),
                            new LlmRouter.LlmResult(
                                    "{\"sufficient\":true,\"grounded\":true,\"envNote\":\"  \"}", 0, 0));
        when(llmRouter.findProviderName(any(), any())).thenReturn("gemini-flash");

        AgentState result = service.execute(newState(RoutingMode.COST_FIRST));

        assertThat(result.envNote()).isNull();
    }

    @Test
    @DisplayName("BLOCKING — 장문 envNote 는 한 줄로 정규화되고 잘린다")
    void blocking_envNote_normalizedToOneLineAndTruncated() {
        String longNote = "경로 안내\n".repeat(80);   // 개행 포함 400자 초과
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn(new LlmRouter.LlmResult("답변", 0, 0),
                            new LlmRouter.LlmResult(
                                    "{\"sufficient\":true,\"grounded\":true,\"envNote\":\""
                                    + longNote.replace("\n", "\\n") + "\"}", 0, 0));
        when(llmRouter.findProviderName(any(), any())).thenReturn("gemini-flash");

        AgentState result = service.execute(newState(RoutingMode.COST_FIRST));

        assertThat(result.envNote()).doesNotContain("\n").endsWith("…");
        assertThat(result.envNote().length()).isLessThanOrEqualTo(301);   // 300 + 말줄임표
    }

    @Test
    @DisplayName("BLOCKING — sufficiency 파싱 실패는 재시도를 걸지 않되 통과로 위조하지도 않는다")
    void blocking_sufficiency_parse_error_leavesNoVerdict() {
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn(new LlmRouter.LlmResult("답변", 0, 0),
                            new LlmRouter.LlmResult("not-a-json", 0, 0));
        when(llmRouter.findProviderName(any(), any())).thenReturn("gemini-flash");

        AgentState result = service.execute(newState(RoutingMode.COST_FIRST));

        assertThat(result.needsRetry()).isFalse();
        assertThat(result.grounded()).isNull();
    }

    @Test
    @DisplayName("BLOCKING — 질문이 답변/평가 프롬프트 모두에서 PromptInjectionGuard.wrap()으로 감싸짐 (EDIT.md #5)")
    @SuppressWarnings("unchecked")
    void blocking_wrapsQuestionInUserQuestionDelimiters() {
        ArgumentCaptor<Function<ChatModel, ChatResponse>> callCaptor = ArgumentCaptor.forClass(Function.class);
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), callCaptor.capture()))
                .thenReturn(new LlmRouter.LlmResult("답변", 0, 0),
                            new LlmRouter.LlmResult("{\"sufficient\":true}", 0, 0));
        when(llmRouter.findProviderName(any(), any())).thenReturn("gemini-flash");

        service.execute(newState(RoutingMode.COST_FIRST));

        ChatModel chatModel = mock(ChatModel.class);
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        when(chatModel.call(promptCaptor.capture())).thenReturn(chatResponse("dummy"));
        callCaptor.getAllValues().forEach(fn -> fn.apply(chatModel));

        assertThat(promptCaptor.getAllValues()).hasSize(2).allSatisfy(prompt ->
                assertThat(prompt.getContents()).contains("[USER_QUESTION]").contains("[/USER_QUESTION]"));
    }

    @Test
    @DisplayName("BLOCKING — 답변 프롬프트의 [검색된 문서]는 정규화된 텍스트를 쓰고 맥락 헤더는 넣지 않는다(§10.1)")
    @SuppressWarnings("unchecked")
    void blocking_answerPrompt_usesNormalizedRetrievedDocTextWithoutContextHeader() {
        ArgumentCaptor<Function<ChatModel, ChatResponse>> callCaptor = ArgumentCaptor.forClass(Function.class);
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), callCaptor.capture()))
                .thenReturn(new LlmRouter.LlmResult("답변", 0, 0),
                            new LlmRouter.LlmResult("{\"sufficient\":true}", 0, 0));
        when(llmRouter.findProviderName(any(), any())).thenReturn("gemini-flash");

        Document doc = new Document("**중요**한 내용\n------", Map.of(
                MetaKey.FILENAME, "가이드.pdf", MetaKey.PAGE_OR_SLIDE, "3",
                MetaKey.CHUNK_CONTEXT, "가이드.pdf > 설정"));
        AgentState state = newState(RoutingMode.COST_FIRST).toBuilder().retrievedDocs(List.of(doc)).build();

        service.execute(state);

        ChatModel chatModel = mock(ChatModel.class);
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        when(chatModel.call(promptCaptor.capture())).thenReturn(chatResponse("dummy"));
        callCaptor.getAllValues().forEach(fn -> fn.apply(chatModel));

        String answerPrompt = promptCaptor.getAllValues().get(0).getContents();
        assertThat(answerPrompt).contains("중요한 내용");
        assertThat(answerPrompt).doesNotContain("**중요**", "------", "가이드.pdf > 설정");
    }

    // ── 검증(evaluate)이 보는 문서 창 ──────────────────────────────────────
    // 답변은 retrievedDocs 전체(app.search-top-k, 기본 8)로 쓰는데 검증은 앞 5개만 보던 시절이
    // 있었다. 그러면 6~8번째 문서에만 있는 값(경로·포트·상수처럼 한 청크에만 나오는 사실)을 정확히
    // 인용한 답변이 근거 없음으로 판정된다. 재시도해도 RetrievalService 는 후보 풀만 키우고 최종
    // 컷은 계속 topK 라, 검증 창은 절대 넓어지지 않아 같은 실패가 반복된다.

    @Test
    @DisplayName("BLOCKING — 검증 프롬프트는 retrievedDocs 를 앞 5개로 자르지 않고 전부 싣는다")
    @SuppressWarnings("unchecked")
    void blocking_evalPrompt_includesEveryRetrievedDoc() {
        ArgumentCaptor<Function<ChatModel, ChatResponse>> callCaptor = ArgumentCaptor.forClass(Function.class);
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), callCaptor.capture()))
                .thenReturn(new LlmRouter.LlmResult("답변", 0, 0),
                            new LlmRouter.LlmResult("{\"sufficient\":true,\"grounded\":true}", 0, 0));
        when(llmRouter.findProviderName(any(), any())).thenReturn("gemini-flash");

        // topK 기본값과 같은 8개. 8번째에만 답변이 인용한 포트 값이 들어 있다.
        List<Document> docs = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            docs.add(new Document("문서본문" + i + (i == 8 ? " 기본 포트는 18080 입니다" : ""),
                    Map.of(MetaKey.FILENAME, "가이드.pdf", MetaKey.PAGE_OR_SLIDE, String.valueOf(i))));
        }
        AgentState state = newState(RoutingMode.COST_FIRST).toBuilder().retrievedDocs(docs).build();

        service.execute(state);

        ChatModel chatModel = mock(ChatModel.class);
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        when(chatModel.call(promptCaptor.capture())).thenReturn(chatResponse("dummy"));
        callCaptor.getAllValues().forEach(fn -> fn.apply(chatModel));

        String evalPrompt = promptCaptor.getAllValues().get(1).getContents();
        assertThat(evalPrompt).contains("문서본문1");
        assertThat(evalPrompt).contains("문서본문6", "문서본문7", "문서본문8");   // 예전엔 잘려 나가던 구간
        assertThat(evalPrompt).contains("기본 포트는 18080 입니다");
    }

    @Test
    @DisplayName("BLOCKING — 검증 프롬프트의 [문서 발췌]도 답변 프롬프트와 같은 정규화 텍스트를 쓴다")
    @SuppressWarnings("unchecked")
    void blocking_evalPrompt_usesSameNormalizedTextAsAnswerPrompt() {
        ArgumentCaptor<Function<ChatModel, ChatResponse>> callCaptor = ArgumentCaptor.forClass(Function.class);
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), callCaptor.capture()))
                .thenReturn(new LlmRouter.LlmResult("답변", 0, 0),
                            new LlmRouter.LlmResult("{\"sufficient\":true,\"grounded\":true}", 0, 0));
        when(llmRouter.findProviderName(any(), any())).thenReturn("gemini-flash");

        Document doc = new Document("포트는 **8080** 입니다\n------",
                Map.of(MetaKey.FILENAME, "가이드.pdf", MetaKey.PAGE_OR_SLIDE, "3"));
        AgentState state = newState(RoutingMode.COST_FIRST).toBuilder().retrievedDocs(List.of(doc)).build();

        service.execute(state);

        ChatModel chatModel = mock(ChatModel.class);
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        when(chatModel.call(promptCaptor.capture())).thenReturn(chatResponse("dummy"));
        callCaptor.getAllValues().forEach(fn -> fn.apply(chatModel));

        String evalPrompt = promptCaptor.getAllValues().get(1).getContents();
        // 답변 모델은 강조가 벗겨진 "8080"을 보고 썼는데 평가 모델만 "**8080**"을 보면
        // 같은 값이 서로 다른 문자열로 보인다 — 두 프롬프트가 같은 형태를 봐야 한다.
        assertThat(evalPrompt).contains("포트는 8080 입니다");
        assertThat(evalPrompt).doesNotContain("**8080**", "------");
    }

    @Test
    @DisplayName("BLOCKING — 발췌 상한 초과 시 하위 순위 문서를 통째로 빼고, 최상위 문서는 항상 남긴다")
    @SuppressWarnings("unchecked")
    void blocking_evalPrompt_dropsLowestRankedDocsWholeWhenOverBudget() {
        ArgumentCaptor<Function<ChatModel, ChatResponse>> callCaptor = ArgumentCaptor.forClass(Function.class);
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), callCaptor.capture()))
                .thenReturn(new LlmRouter.LlmResult("답변", 0, 0),
                            new LlmRouter.LlmResult("{\"sufficient\":true,\"grounded\":true}", 0, 0));
        when(llmRouter.findProviderName(any(), any())).thenReturn("gemini-flash");

        // 상한(32,000자)을 넘기도록 20,000자짜리 문서 3개 — 1번은 통째로 남고 3번은 통째로 빠진다.
        List<Document> docs = List.of(
                new Document("최상위표식 " + "가".repeat(20_000), Map.of(MetaKey.FILENAME, "a.pdf")),
                new Document("두번째표식 " + "나".repeat(20_000), Map.of(MetaKey.FILENAME, "b.pdf")),
                new Document("세번째표식 " + "다".repeat(20_000), Map.of(MetaKey.FILENAME, "c.pdf")));
        AgentState state = newState(RoutingMode.COST_FIRST).toBuilder().retrievedDocs(docs).build();

        service.execute(state);

        ChatModel chatModel = mock(ChatModel.class);
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        when(chatModel.call(promptCaptor.capture())).thenReturn(chatResponse("dummy"));
        callCaptor.getAllValues().forEach(fn -> fn.apply(chatModel));

        String evalPrompt = promptCaptor.getAllValues().get(1).getContents();
        assertThat(evalPrompt).contains("최상위표식");                       // 1위는 무조건 유지
        assertThat(evalPrompt).doesNotContain("세번째표식");                 // 상한 초과분은 제외
        // 잘린 조각이 아니라 문서 단위로 빠진다 — 반쪽 청크는 검증 대상 값이 사라지는 경로다.
        assertThat(evalPrompt).contains("가".repeat(20_000));
    }

    @Test
    @DisplayName("BLOCKING — 검증 호출은 답변 예산이 아니라 자체 출력 상한(2,048)을 쓴다")
    @SuppressWarnings("unchecked")
    void blocking_evalCall_capsItsOwnOutputBudget() {
        ArgumentCaptor<Function<ChatModel, ChatResponse>> callCaptor = ArgumentCaptor.forClass(Function.class);
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), callCaptor.capture()))
                .thenReturn(new LlmRouter.LlmResult("답변", 0, 0),
                            new LlmRouter.LlmResult("{\"sufficient\":true,\"grounded\":true}", 0, 0));
        when(llmRouter.findProviderName(any(), any())).thenReturn("gemini-flash");

        service.execute(newState(RoutingMode.COST_FIRST));

        ChatModel chatModel = mock(ChatModel.class);
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        when(chatModel.call(promptCaptor.capture())).thenReturn(chatResponse("dummy"));
        callCaptor.getAllValues().forEach(fn -> fn.apply(chatModel));

        // 검증 응답은 JSON 몇 필드뿐인데, 상한을 안 걸면 프로바이더에 구워진 app.llm.max-tokens
        // 전체가 예약된다 — 좁은 컨텍스트에서 n_ctx 를 넘기는 것은 근거가 아니라 이 예약이다.
        Integer evalMax = promptCaptor.getAllValues().get(1).getOptions().getMaxTokens();
        assertThat(evalMax).as("검증 호출 출력 상한").isEqualTo(2_048);
        // 답변 호출은 모드 예산(N = max-tokens의 70% 또는 5,000자 바닥) 그대로여야 한다.
        Integer answerMax = promptCaptor.getAllValues().get(0).getOptions().getMaxTokens();
        assertThat(answerMax).as("답변 호출은 영향 없음")
                .isEqualTo(ResponseMode.N.maxTokens(new AppProperties(
                        "./data", MAX_RETRY, 800, 100, 100, 7, 0.0, true, 0, false, true, false, 3,
                        null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                        null, null, null).llmSafe().maxTokens()));
    }

    @Test
    @DisplayName("BLOCKING — §10.10 큐레이션 Q&A 문서는 '[curated_qa | p.1]' 대신 고정 라벨을 쓴다")
    @SuppressWarnings("unchecked")
    void blocking_answerPrompt_labelsCuratedQaDocDistinctly() {
        ArgumentCaptor<Function<ChatModel, ChatResponse>> callCaptor = ArgumentCaptor.forClass(Function.class);
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), callCaptor.capture()))
                .thenReturn(new LlmRouter.LlmResult("답변", 0, 0),
                            new LlmRouter.LlmResult("{\"sufficient\":true}", 0, 0));
        when(llmRouter.findProviderName(any(), any())).thenReturn("gemini-flash");

        Document curated = new Document("과거에 좋아요 받은 답변", Map.of(
                MetaKey.DOC_TYPE, "curated_qa", MetaKey.FILENAME, "curated_qa", MetaKey.PAGE_OR_SLIDE, 1));
        AgentState state = newState(RoutingMode.COST_FIRST).toBuilder().retrievedDocs(List.of(curated)).build();

        service.execute(state);

        ChatModel chatModel = mock(ChatModel.class);
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        when(chatModel.call(promptCaptor.capture())).thenReturn(chatResponse("dummy"));
        callCaptor.getAllValues().forEach(fn -> fn.apply(chatModel));

        String answerPrompt = promptCaptor.getAllValues().get(0).getContents();
        assertThat(answerPrompt).contains("[큐레이션 Q&A]", "과거에 좋아요 받은 답변");
        assertThat(answerPrompt).doesNotContain("curated_qa | p.1");
    }

    @Test
    @DisplayName("BLOCKING — S는 전용 시스템 프롬프트를 쓰고 스타일 지시문 층은 없다 (§6.24 Step 0-c/1-a)")
    void blocking_answerPrompt_usesModeSystemPrompt() {
        // S 모드에서는 평가(evaluate) LLM 호출 자체가 스킵되므로 answer 호출 1회만 발생한다.
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn(new LlmRouter.LlmResult("답변", 0, 0));
        when(llmRouter.findProviderName(any(), any())).thenReturn("gemini-flash");

        service.execute(newState(RoutingMode.COST_FIRST).toBuilder().responseMode(ResponseMode.S).build());

        // 평가 LLM은 한 번도 호출되지 않는다 (answer LLM 1회만)
        verify(llmRouter, org.mockito.Mockito.times(1)).executeGatedWithUsage(any(), any(), any());
        verify(messageSource).getMessage(eq("prompt.answer.system.s"), any(), any(Locale.class));
        verify(messageSource, org.mockito.Mockito.never())
                .getMessage(org.mockito.ArgumentMatchers.startsWith("prompt.answer.style"), any(), any(Locale.class));
    }

    @Test
    @DisplayName("BLOCKING C — 창의 시스템 프롬프트 + 창의 검증 프롬프트를 쓰고, apiGrounded 가 grounded 로 실린다 (§6.24 Step 2-a/2-d)")
    void blocking_creativeMode_usesCreativePromptsAndMapsApiGrounded() {
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn(new LlmRouter.LlmResult("## 요약\n만들었습니다", 100, 40),
                            new LlmRouter.LlmResult("{\"sufficient\":true,\"apiGrounded\":true,\"inventedSymbols\":[],\"envNote\":\"\"}", 30, 10));
        when(llmRouter.findProviderName(any(), any())).thenReturn("local");

        AgentState result = service.execute(
                newState(RoutingMode.COST_FIRST).toBuilder().responseMode(ResponseMode.C).build());

        verify(messageSource).getMessage(eq("prompt.answer.system.c"), any(), any(Locale.class));
        verify(messageSource).getMessage(eq("prompt.answer.eval.creative"), any(), any(Locale.class));
        // 검증을 '끄지' 않는다 — 답변 1회 + 평가 1회. S(평가 스킵)와 갈리는 지점이다.
        verify(llmRouter, times(2)).executeGatedWithUsage(any(), any(), any());
        assertThat(result.grounded()).isTrue();   // apiGrounded → grounded (CriticService 코드 변경 0)
        assertThat(result.needsRetry()).isFalse();
        assertThat(result.inventedSymbols()).isEmpty();
    }

    @Test
    @DisplayName("BLOCKING C — 창의 답변이 '문서에 그대로 없다'는 이유로 재시도 루프를 돌지 않는다")
    void blocking_creativeMode_doesNotSpinTheRetryLoop() {
        // 기존 grounded 기준이었다면 창의 답변은 정의상 false 라 CRITIC 재시도를 물고
        // ANSWER·EVAL·RETRIEVAL 을 각각 3배 태웠을 상황이다. 창의 검증에서는 통과해야 한다.
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn(new LlmRouter.LlmResult("## 구현\n새로 작성한 코드", 100, 40),
                            new LlmRouter.LlmResult("{\"sufficient\":true,\"apiGrounded\":true,\"inventedSymbols\":[],\"envNote\":\"\"}", 30, 10));
        when(llmRouter.findProviderName(any(), any())).thenReturn("local");

        AgentState state = newState(RoutingMode.COST_FIRST).toBuilder()
                .responseMode(ResponseMode.C)
                .retrievedDocs(List.of(new Document("문서 본문", Map.of(MetaKey.FILENAME, "a.md"))))
                .build();
        AgentState result = service.execute(state);

        assertThat(result.needsRetry()).isFalse();
        assertThat(result.grounded()).isTrue();
        assertThat(result.evalReason()).isNull();
    }

    @Test
    @DisplayName("BLOCKING C — inventedSymbols 는 상태에 담기되 재시도는 걸지 않는다(경고 전용)")
    void blocking_creativeMode_inventedSymbolsWarnWithoutRetry() {
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn(new LlmRouter.LlmResult("## 구현\ncode", 100, 40),
                            new LlmRouter.LlmResult("{\"sufficient\":true,\"apiGrounded\":true,"
                                    + "\"inventedSymbols\":[\"parseDateEx\"],\"envNote\":\"\"}", 30, 10));
        when(llmRouter.findProviderName(any(), any())).thenReturn("local");

        AgentState result = service.execute(
                newState(RoutingMode.COST_FIRST).toBuilder().responseMode(ResponseMode.C).build());

        // 창의 모드에서 이름을 지어내는 것 자체는 실패가 아니다 — 다시 생성시키는 대신 경고한다.
        assertThat(result.inventedSymbols()).containsExactly("parseDateEx");
        assertThat(result.needsRetry()).isFalse();
    }

    @Test
    @DisplayName("BLOCKING C — apiGrounded=false 는 grounded=false 로 실려 CRITIC 이 재시도를 걸 수 있다")
    void blocking_creativeMode_apiGroundedFalsePropagates() {
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn(new LlmRouter.LlmResult("## 구현\ncode", 100, 40),
                            new LlmRouter.LlmResult("{\"sufficient\":true,\"apiGrounded\":false,"
                                    + "\"inventedSymbols\":[\"fakeApi\"],\"envNote\":\"\"}", 30, 10));
        when(llmRouter.findProviderName(any(), any())).thenReturn("local");

        AgentState state = newState(RoutingMode.COST_FIRST).toBuilder()
                .responseMode(ResponseMode.C)
                .retrievedDocs(List.of(new Document("문서 본문", Map.of(MetaKey.FILENAME, "a.md"))))
                .build();
        AgentState result = service.execute(state);

        assertThat(result.grounded()).isFalse();
        // 실패 사유는 발명된 이름 그 자체다 — evalReason 이 SSE·로그·툴팁을 지나가는 자리라 거기 싣는다.
        assertThat(result.evalReason()).contains("fakeApi");
    }

    @Test
    @DisplayName("BLOCKING C — 창의 검증 파싱 실패는 '판정 없음'이다 (통과로 위조하지 않고, 전달도 막지 않는다)")
    void blocking_creativeMode_parseFailureLeavesNoVerdict() {
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn(new LlmRouter.LlmResult("## 구현\ncode", 100, 40),
                            new LlmRouter.LlmResult("not json at all", 30, 10));
        when(llmRouter.findProviderName(any(), any())).thenReturn("local");

        AgentState result = service.execute(
                newState(RoutingMode.COST_FIRST).toBuilder().responseMode(ResponseMode.C).build());

        assertThat(result.needsRetry()).isFalse();   // 검증기 고장이 답변 전달을 막지는 않는다
        assertThat(result.grounded()).isNull();      // 그러나 통과 배지를 붙여서도 안 된다
        assertThat(result.inventedSymbols()).isEmpty();
    }

    // 실제로 관찰된 사고(2026-08-24 23:58): 창의 검증이 빈 문자열을 반환 → BeanOutputConverter 가
    // "No content to map due to end-of-input" 으로 실패 → 예전 폴백이 통과 처리 → sufficient=false
    // 로 걸렸어야 할 재시도가 돌지 않은 채 파란 '생성' 배지까지 붙은 답변이 나갔다. 빈 응답은
    // 검증 호출에서 가장 흔한 실패 모드다(이 앱에서 가장 큰 단일 요청이라 컨텍스트를 넘기기 쉽다).
    @Test
    @DisplayName("BLOCKING C — 창의 검증이 빈 응답이면 판정 없음으로 기록한다 (§ 관찰된 사고)")
    void blocking_creativeMode_emptyVerdictIsNotAPass() {
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn(new LlmRouter.LlmResult("## 구현\ncode", 100, 40),
                            new LlmRouter.LlmResult("", 900, 0));
        when(llmRouter.findProviderName(any(), any())).thenReturn("local");

        AgentState result = service.execute(
                newState(RoutingMode.COST_FIRST).toBuilder().responseMode(ResponseMode.C).build());

        assertThat(result.grounded()).isNull();
        assertThat(result.needsRetry()).isFalse();
        assertThat(result.evalReason()).isNull();
        assertThat(result.inventedSymbols()).isEmpty();
        // 빈 응답이어도 그 호출의 토큰은 실제로 썼다 — 사용량 집계에서 사라지면 안 된다.
        assertThat(result.totalInputTokens()).isEqualTo(1000);
    }

    @Test
    @DisplayName("BLOCKING N — 검증이 빈 응답이면 역시 판정 없음이다 (창의 경로와 같은 규칙)")
    void blocking_standardMode_emptyVerdictIsNotAPass() {
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn(new LlmRouter.LlmResult("답변", 100, 40),
                            new LlmRouter.LlmResult("   ", 900, 0));
        when(llmRouter.findProviderName(any(), any())).thenReturn("local");

        AgentState result = service.execute(newState(RoutingMode.COST_FIRST));

        assertThat(result.grounded()).isNull();
        assertThat(result.needsRetry()).isFalse();
    }

    // ── STREAMING 경로 ───────────────────────────────────────────────────
    // ChatModel.stream()에는 call()로 위임하는 default 구현이 없어(UnsupportedOperationException)
    // ChatModel을 직접 mock 해야 한다 (DirectAnswerServiceTest와 동일 패턴).
    // sufficiency(evaluate)는 블로킹 호출이므로 executeGatedWithUsage으로 스텁한다.

    @Test
    @DisplayName("STREAMING COST_FIRST — 답변+sufficiency 2회 호출, llmCallCount=2 (executeBlocking과 동일하게 집계돼야 함), 스트리밍 답변은 근사 토큰이 누적됨")
    void streaming_costFirst_accumulatesLlmCallCount() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chatResponse("스트리밍 답변")));
        LlmProvider provider = new LlmProvider(
                "local", TaskType.TEXT, ProviderRole.LOCAL, 0, "key", null, "model", false, chatModel, null);
        when(llmRouter.routeProvider(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST))).thenReturn(provider);
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn(new LlmRouter.LlmResult("{\"sufficient\":true}", 0, 0));

        List<String> tokens = new ArrayList<>();
        GraphListener listener = new GraphListener() {
            @Override public void onToken(String text) { tokens.add(text); }
        };

        AgentState result = service.executeStreaming(newState(RoutingMode.COST_FIRST), listener);

        assertThat(result.answer()).isEqualTo("스트리밍 답변");
        assertThat(result.usedProvider()).isEqualTo("local");
        assertThat(result.needsRetry()).isFalse();
        assertThat(result.llmCallCount()).isEqualTo(2);
        // 스트리밍 답변 자체는 real ChatResponse usage가 없어 chars/4 근사치가 누적된다 (evaluate() 몫은 0).
        assertThat(result.totalOutputTokens()).isEqualTo((int) LlmRouter.approxTokens("스트리밍 답변"));
        verify(llmRouter).recordApproxUsage(eq("local"), anyString(), eq("스트리밍 답변"));
    }

    @Test
    @DisplayName("STREAMING — 답변 스트리밍이 끝난 직후, sufficiency evaluate() 호출 전에 onVerifying()이 정확히 1회 호출된다")
    void streaming_callsOnVerifyingBeforeSufficiencyEvaluation() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chatResponse("스트리밍 답변")));
        LlmProvider provider = new LlmProvider(
                "local", TaskType.TEXT, ProviderRole.LOCAL, 0, "key", null, "model", false, chatModel, null);
        when(llmRouter.routeProvider(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST))).thenReturn(provider);

        List<String> callOrder = new ArrayList<>();
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                .thenAnswer(inv -> {
                    callOrder.add("evaluate");
                    return new LlmRouter.LlmResult("{\"sufficient\":true}", 0, 0);
                });

        AtomicInteger verifyingCalls = new AtomicInteger();
        GraphListener listener = new GraphListener() {
            @Override public void onToken(String text) { callOrder.add("token"); }
            @Override public void onVerifying() { verifyingCalls.incrementAndGet(); callOrder.add("verifying"); }
        };

        service.executeStreaming(newState(RoutingMode.COST_FIRST), listener);

        assertThat(verifyingCalls.get()).isEqualTo(1);
        assertThat(callOrder).containsSubsequence("token", "verifying", "evaluate");
    }

    @Test
    @DisplayName("STREAMING S 모드 — onVerifying() 미발생 + 평가 LLM 호출도 스킵된다")
    void streaming_sMode_doesNotEmitOnVerifyingAndSkipsEvaluateLlmCall() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chatResponse("스트리밍 답변")));
        LlmProvider provider = new LlmProvider(
                "local", TaskType.TEXT, ProviderRole.LOCAL, 0, "key", null, "model", false, chatModel, null);
        when(llmRouter.routeProvider(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST))).thenReturn(provider);
        // 평가 LLM은 S 모드에서 호출되지 않아야 한다. 스텁을 제거해 실수로 호출되면 즉시 실패하게 한다.

        AtomicInteger verifyingCalls = new AtomicInteger();
        GraphListener listener = new GraphListener() {
            @Override public void onVerifying() { verifyingCalls.incrementAndGet(); }
        };

        service.executeStreaming(newState(RoutingMode.COST_FIRST).toBuilder().responseMode(ResponseMode.S).build(), listener);

        assertThat(verifyingCalls.get()).isZero();
        // 평가 LLM(executeGatedWithUsage)은 한 번도 호출되지 않는다
        verify(llmRouter, org.mockito.Mockito.never()).executeGatedWithUsage(any(), any(), any());
    }

    @Test
    @DisplayName("STREAMING — provider.stream()=true 인 네이티브 OpenAiApi 우회 경로(streamDirect)도 DEBUG 로그(엔드포인트+body)를 남긴다")
    void streaming_streamDirectPath_logsRequestAtDebugLevel() {
        Logger logbackLogger = com.example.ragagent.LogbackTestSupport.logger(AnswerService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
        Level previousLevel = logbackLogger.getLevel();
        logbackLogger.setLevel(Level.DEBUG);
        try {
            OpenAiApi openAiApi = mock(OpenAiApi.class);
            var delta = new OpenAiApi.ChatCompletionMessage("스트리밍 답변", OpenAiApi.ChatCompletionMessage.Role.ASSISTANT);
            var choice = new OpenAiApi.ChatCompletionChunk.ChunkChoice(null, 0, delta, null);
            var chunk = new OpenAiApi.ChatCompletionChunk("id", List.of(choice), null, "model", null, null, null, null);
            when(openAiApi.chatCompletionStream(any())).thenReturn(Flux.just(chunk));

            LlmProvider provider = new LlmProvider("local", TaskType.TEXT, ProviderRole.LOCAL, 0,
                    "sk-test-key-123456", "http://localhost:1234/v1", "model", true, mock(ChatModel.class), openAiApi);
            when(llmRouter.routeProvider(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST))).thenReturn(provider);
            when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                    .thenReturn(new LlmRouter.LlmResult("{\"sufficient\":true}", 0, 0));

            GraphListener listener = new GraphListener() {
                @Override public void onToken(String text) { }
            };
            service.executeStreaming(newState(RoutingMode.COST_FIRST), listener);

            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                String msg = event.getFormattedMessage();
                assertThat(msg).contains("local")
                        .contains("http://localhost:1234/v1/chat/completions")
                        .doesNotContain("curl -s -X POST")
                        .doesNotContain("Authorization")
                        .doesNotContain("sk-test-key-123456");
            });
        } finally {
            logbackLogger.detachAppender(appender);
            logbackLogger.setLevel(previousLevel);
        }
    }

    // ── PROGRESSIVE 업그레이드 경로 ───────────────────────────────────────

    @Test
    @DisplayName("PROGRESSIVE — sufficiency=false + retryCount>=maxRetry → QUALITY_FIRST 업그레이드")
    @SuppressWarnings("unchecked")
    void progressive_upgrade_triggersQualityFirst() {
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.PROGRESSIVE), any()))
                .thenReturn(new LlmRouter.LlmResult("초안 답변", 0, 0),
                            new LlmRouter.LlmResult("{\"sufficient\":false}", 0, 0));
        when(llmRouter.findProviderName(any(), eq(RoutingMode.PROGRESSIVE)))
                .thenReturn("gemini-flash");
        LlmProvider premiumProvider = new LlmProvider(
                "gemini-pro", TaskType.TEXT, ProviderRole.PREMIUM, 4, "key", null, null, true, null, null);
        when(llmRouter.routeProvider(any(), eq(RoutingMode.QUALITY_FIRST)))
                .thenReturn(premiumProvider);
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.QUALITY_FIRST), any(Function.class)))
                .thenReturn(new LlmRouter.LlmResult("프리미엄 답변", 200, 90));

        // retryCount = maxRetry 도달 상태에서 진입 (그래프가 retry 한도 초과 후 PROGRESSIVE 진입 시 시나리오)
        AgentState initial = newState(RoutingMode.PROGRESSIVE)
                .toBuilder().incrementRetry().incrementRetry().build();

        AgentState result = service.execute(initial);

        assertThat(result.answer()).isEqualTo("프리미엄 답변");
        assertThat(result.usedProvider()).isEqualTo("gemini-pro");
        assertThat(result.premiumUpgraded()).isEqualTo("gemini-pro");
        assertThat(result.needsRetry()).isFalse();
        assertThat(result.totalInputTokens()).isEqualTo(200);
        assertThat(result.totalOutputTokens()).isEqualTo(90);
        verify(llmRouter, times(1))
                .executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.QUALITY_FIRST), any(Function.class));
    }

    @Test
    @DisplayName("PROGRESSIVE — sufficient=true 면 업그레이드 안 함")
    void progressive_sufficient_noUpgrade() {
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.PROGRESSIVE), any()))
                .thenReturn(new LlmRouter.LlmResult("충분한 답변", 0, 0),
                            new LlmRouter.LlmResult("{\"sufficient\":true}", 0, 0));
        when(llmRouter.findProviderName(any(), any())).thenReturn("gemini-flash");

        AgentState initial = newState(RoutingMode.PROGRESSIVE)
                .toBuilder().incrementRetry().incrementRetry().build();

        AgentState result = service.execute(initial);

        assertThat(result.premiumUpgraded()).isNull();
        assertThat(result.answer()).isEqualTo("충분한 답변");
    }

    @Test
    @DisplayName("PROGRESSIVE — needsRetry 더라도 retryCount < maxRetry 면 업그레이드 안 함")
    void progressive_underRetryLimit_noUpgrade() {
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.PROGRESSIVE), any()))
                .thenReturn(new LlmRouter.LlmResult("답변", 0, 0),
                            new LlmRouter.LlmResult("{\"sufficient\":false}", 0, 0));
        when(llmRouter.findProviderName(any(), any())).thenReturn("gemini-flash");

        // retryCount = 0 (< maxRetry=2) → 업그레이드 조건 미충족, needsRetry=true 만 전파
        AgentState result = service.execute(newState(RoutingMode.PROGRESSIVE));

        assertThat(result.premiumUpgraded()).isNull();
        assertThat(result.needsRetry()).isTrue();
    }
}
