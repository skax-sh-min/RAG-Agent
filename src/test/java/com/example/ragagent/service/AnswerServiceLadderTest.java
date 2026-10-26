package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.exception.LlmContextOverflowException;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.PromptBudget;
import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.model.ResponseMode;
import com.example.ragagent.model.SentPrompt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.context.MessageSource;

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
import static org.mockito.Mockito.when;

/**
 * §6.30 — 축소 사다리가 답변 경로에 제대로 물렸는가: 사전 예산과 축소 재시도가 같은 칸을 지나고, 보낸 것이 state 에 남고
 * ({@code lastSent}), 검증 실패 재시도가 그것으로 판단한다(교체 A · 이력 유지 C · 뺄 순서 D).
 *
 * <p>순수 사다리의 칸·되돌려 채우기는 {@link PromptLadderTest} 가 본다. 여기서는 숫자를 창(20,000)에서 잡은 입력 예산에 대해
 * 넉넉한 여유를 두고 고른다 — 시스템 프롬프트는 목이 돌려주는 "prompt" 라 고정비가 200 남짓이다.
 */
@ResourceLock("global-state")
class AnswerServiceLadderTest {

    private static final int WINDOW = 20_000;

    private LlmRouter llmRouter;
    private ProviderContextWindows windows;
    private AnswerService service;
    /** 답변 호출마다 보낸 사용자 메시지(+시스템). */
    private final List<String> answerPrompts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        llmRouter = mock(LlmRouter.class);
        windows = new ProviderContextWindows();
        AppProperties props = mock(AppProperties.class);
        when(props.maxRetryCount()).thenReturn(2);
        when(props.searchTopKSafe()).thenReturn(10);
        when(props.llmSafe()).thenReturn(new AppProperties.LlmConfig(
                List.of(), 2, 10, 180, "COST_FIRST", 3, 20, 0.0, 0.1, 0.0, 0.7, true, 8000, 1, true));
        MessageSource messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("prompt");
        when(llmRouter.findProviderName(any(), any())).thenReturn("lm");
        service = new AnswerService(llmRouter, props, messageSource, windows);
    }

    /** 블로킹 N 답변의 입력 예산 — 테스트가 크기를 이 값에 대해 고른다(여유가 커서 정확한 값에 기대지 않는다). */
    private static long answerBudget() {
        return new PromptBudget(WINDOW, AnswerService.outputReservation(ResponseMode.N, false, 8000)).inputBudget();
    }

    private static Document doc(int n, int chars) {
        String marker = "문서" + n + "-";
        return new Document("d" + n, marker + "가".repeat(Math.max(0, chars - marker.length())), Map.of());
    }

    private static List<Document> docs(int count, int chars) {
        List<Document> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) list.add(doc(i, chars));
        return list;
    }

    private static String turn(String questionTag, int answerChars) {
        return "Q: " + questionTag + "\nA: " + "나".repeat(answerChars);
    }

    private static AgentState state(List<Document> docs, String history) {
        return AgentState.of("질문", "v1", "t1", history, RoutingMode.COST_FIRST)
                .toBuilder().retrievedDocs(docs).build();
    }

    /**
     * 답변 호출은 처음 {@code failures} 번 초과로 실패하고, 검증 호출은 {@code evalJson} 을 돌려준다.
     */
    private void stub(int failures, String evalJson) {
        AtomicInteger answers = new AtomicInteger();
        boolean[] isAnswer = {false};
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), any(), any())).thenAnswer(inv -> {
            Function<ChatModel, ChatResponse> fn = inv.getArgument(2);
            ChatModel probe = mock(ChatModel.class);
            when(probe.call(any(Prompt.class))).thenAnswer(call -> {
                Prompt prompt = call.getArgument(0);
                String text = prompt.getInstructions().stream()
                        .map(org.springframework.ai.chat.messages.Message::getText)
                        .reduce("", (a, b) -> a + "\n" + b);
                isAnswer[0] = text.contains("[검색된 문서]");
                if (isAnswer[0]) {
                    if (answers.incrementAndGet() <= failures) {
                        throw new LlmContextOverflowException("lm", new IllegalStateException("Context size has been exceeded."));
                    }
                    answerPrompts.add(text);
                }
                return new ChatResponse(List.of(new Generation(new AssistantMessage("답변"))));
            });
            fn.apply(probe);
            return new LlmRouter.LlmResult(isAnswer[0] ? "답변" : evalJson, 0, 0);
        });
    }

    private static final String PASS = "{\"sufficient\":true,\"grounded\":true}";

    private String lastPrompt() {
        return answerPrompts.getLast();
    }

    @Test
    @DisplayName("줄일 것이 없으면 이력은 원문 그대로 실린다 — 사다리는 넘칠 때만 돈다")
    void untouchedWhenItFits() {
        windows.record("lm", WINDOW, ProviderContextWindows.Source.CONFIGURED);
        stub(0, PASS);
        String history = "[Conversation Summary]\n요약\n\n[Recent]\n" + turn("하나", 10) + "\n\n" + turn("둘", 10);

        AgentState result = service.execute(state(docs(3, 100), history));

        assertThat(lastPrompt()).contains("[이전 대화]\n" + history + "\n\n[검색된 문서]");
        assertThat(result.budgetNote()).isNull();
        assertThat(result.lastSent().docKeys()).containsExactly("d1", "d2", "d3");
        assertThat(result.lastSent().historyCut()).isEqualTo(SentPrompt.HistoryCut.NONE);
    }

    @Test
    @DisplayName("창을 몰라도 초과 뒤 재시도는 사다리를 따른다 — 먼저 먼 턴을 빼고 문서는 그대로")
    void shrinkRetryWalksTheSameLadder() {
        stub(1, PASS);
        String history = turn("첫째질문", 10) + "\n\n" + turn("둘째질문", 10) + "\n\n" + turn("셋째질문", 10);

        AgentState result = service.execute(state(docs(8, 300), history));

        assertThat(lastPrompt()).contains("셋째질문", "문서8-").doesNotContain("첫째질문", "둘째질문");
        assertThat(result.budgetNote()).isEqualTo("컨텍스트 한도로 이전 대화 일부를 제외했습니다.");
        assertThat(result.lastSent().historyCut().olderTurnsDropped()).isEqualTo(2);
    }

    @Test
    @DisplayName("이력을 전부 뺀 자리에는 후속 질문의 다시 쓴 질문 한 줄이 들어간다(칸 5)")
    void condensedQuestionReplacesTheHistory() {
        windows.record("lm", WINDOW, ProviderContextWindows.Source.CONFIGURED);
        stub(0, PASS);
        AgentState s = state(docs(6, 1_000), turn("그건", (int) answerBudget())).toBuilder()
                .question("그 포트는 어디서 바꿔?").searchQuestion("서버 포트 설정은 어디서 바꾸나").build();

        AgentState result = service.execute(s);

        assertThat(lastPrompt()).contains("[이전 대화]\n(이전 대화는 길이 한도로 생략했다. 이 질문을 이전 대화에 비추어 다시 쓰면: "
                + "서버 포트 설정은 어디서 바꾸나)").contains("문서6-");
        assertThat(result.budgetNote()).isEqualTo("컨텍스트 한도로 이전 대화 대신 다시 쓴 질문으로 답했습니다.");
    }

    @Test
    @DisplayName("§6.30 A — 교체는 답변 프롬프트에 실린 것 안에서만 고른다(보이지 않은 문서는 밀지 않는다)")
    void evictionStaysWithinTheSentDocuments() {
        windows.record("lm", WINDOW, ProviderContextWindows.Source.CONFIGURED);
        stub(0, "{\"sufficient\":false,\"grounded\":true,\"reason\":\"부족\",\"usedDocs\":[1,2]}");
        int perDoc = 1_000;
        int fit = (int) ((answerBudget() - 400) / perDoc);   // 답변에 실리는 수 — 검증(예약이 작다)은 전부 본다
        List<Document> all = docs(fit + 2, perDoc);

        AgentState result = service.execute(state(all, ""));

        assertThat(result.lastSent().docKeys()).hasSize(fit);
        assertThat(result.excludedDocIds()).as("실리지 않은 두 문서는 '안 쓰임'이 아니라 '모름'이다")
                .doesNotContain("d" + (fit + 1), "d" + (fit + 2))
                .contains("d" + fit);
        assertThat(result.lastSent().usedDocKeys()).as("검증이 쓴다고 한 문서를 키로 남긴다(D 의 재료)")
                .containsExactly("d1", "d2");
    }

    /** 검증 실패 재시도의 상태 — 큰 그 앞 턴 + 작은 직전 턴, 문서 11개(escalation +1). */
    private AgentState retryState(List<String> previousSentKeys) {
        return retryState(previousSentKeys, List.of());
    }

    private AgentState retryState(List<String> previousSentKeys, List<String> reportedUsedKeys) {
        String history = turn("그앞질문", 1_490) + "\n\n" + turn("직전질문", 90);
        return state(docs(11, 1_000), history).toBuilder()
                .retryCount(1).evalReason("근거 부족")
                .lastSent(new SentPrompt(previousSentKeys, SentPrompt.HistoryCut.NONE, reportedUsedKeys))
                .build();
    }

    @Test
    @DisplayName("§6.30 C — 재시도는 이력을 직전 시도보다 더 줄이지 않는다: 넘치는 몫은 문서가 흡수한다")
    void retryAbsorbsTheOverflowWithDocuments() {
        windows.record("lm", WINDOW, ProviderContextWindows.Source.CONFIGURED);
        stub(0, PASS);
        List<String> all = docs(11, 1_000).stream().map(Document::getId).toList();

        service.execute(retryState(all).toBuilder().retryCount(0).lastSent(null).evalReason(null).build());
        String first = lastPrompt();
        service.execute(retryState(all));
        String retry = lastPrompt();

        assertThat(first).as("전제 — 첫 시도라면 그 앞 턴을 먼저 뺀다").doesNotContain("그앞질문").contains("문서11-");
        assertThat(retry).contains("그앞질문", "직전질문", "[직전 시도 메모]").doesNotContain("문서11-");
    }

    @Test
    @DisplayName("§6.30 D — 재시도가 가져온 새 문서는 순위가 낮아도 나중에 뺀다")
    void retryKeepsTheNewDocument() {
        windows.record("lm", WINDOW, ProviderContextWindows.Source.CONFIGURED);
        stub(0, PASS);
        List<String> previous = docs(10, 1_000).stream().map(Document::getId).toList();   // d11 은 이번에 새로 들어왔다

        service.execute(retryState(previous, List.of("d1", "d2")));

        assertThat(lastPrompt()).as("직전에 봤지만 쓰였다는 보고가 없는 하위 문서(d10)가 먼저 빠진다")
                .contains("문서11-").doesNotContain("문서10-");
    }

    @Test
    @DisplayName("§6.30 D — 검증이 쓴 문서를 보고하지 않았으면(모름) 직전에 실린 문서를 지킨다")
    void retryWithUnknownUseKeepsThePreviouslySentDocuments() {
        windows.record("lm", WINDOW, ProviderContextWindows.Source.CONFIGURED);
        stub(0, PASS);
        List<String> previous = docs(10, 1_000).stream().map(Document::getId).toList();

        service.execute(retryState(previous));

        assertThat(lastPrompt()).as("빈 보고는 '안 썼다'가 아니라 '모른다' — 새로 들어온 d11 이 먼저 빠진다")
                .contains("문서10-").doesNotContain("문서11-");
    }
}
