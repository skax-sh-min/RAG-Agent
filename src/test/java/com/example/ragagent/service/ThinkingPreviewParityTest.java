package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.llm.LlmProvider;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ProviderRole;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.llm.ThinkingControlChatModel;
import com.example.ragagent.llm.ThinkingLevel;
import com.example.ragagent.llm.ThinkingSite;
import com.example.ragagent.model.ResponseMode;
import com.example.ragagent.model.ThinkingPreview;
import com.example.ragagent.repository.MemoryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>미리보기 = 런타임</b> — 카드가 보여 주는 숫자가 실제 호출부가 만든 요청과 같은가(PLAN §6.29 ⑦-바, 테스트 항목 "미리보기 =
 * 런타임"). 미리보기가 런타임 함수를 부르도록 만들었어도, 그 함수를 <b>호출부가 실제로 쓰는지</b>는 호출부를 실제로 돌려야만
 * 알 수 있다 — 호출부가 옛 상수로 되돌아가면 미리보기는 그대로이고 요청만 달라진다.
 *
 * <p>비교 대상은 두 가지다: 요청에 실린 {@code max_tokens}(실제 전송 체인 {@code ThinkingControlChatModel} 을 지난 뒤의 값),
 * 그리고 프롬프트에 실제로 들어간 문서·발췌 수.
 */
@ResourceLock("global-state")
class ThinkingPreviewParityTest {

    private static final int CHUNK = 1_500;
    private static final Pattern EXCERPT_MARK = Pattern.compile("\\[D\\d+]");

    private Locale previousLocale;

    @BeforeEach
    void korean() {
        previousLocale = LocaleContextHolder.getLocale();
        LocaleContextHolder.setLocale(Locale.KOREAN);
    }

    @AfterEach
    void restore() {
        LocaleContextHolder.setLocale(previousLocale);
    }

    private static ResourceBundleMessageSource bundle() {
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        return messages;
    }

    private static ChatResponse reply(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /** 받은 프롬프트를 모으고 {@code answer} 로 답하는 서버 흉내. */
    private static ChatModel server(List<Prompt> seen, String answer) {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenAnswer(inv -> {
            seen.add(inv.getArgument(0));
            return reply(answer);
        });
        return model;
    }

    /** 실제 전송 체인의 바깥 데코레이터 — 사이트 표시를 읽어 수준·여유를 요청에 싣는다(블로킹 호출이 지나는 그 클래스). */
    private static ChatModel chain(ThinkingPreviewHarness h, ChatModel server) {
        String provider = ThinkingPreviewHarness.PROVIDER;
        return new ThinkingControlChatModel(server, provider, h.dialects, site -> h.props.llmSafe().thinkingLevel(site),
                h.observations, () -> h.budget.providerMaxTokens(provider), () -> h.budget.contextWindow(provider));
    }

    /** 라우터의 {@code executeGatedWithUsage} 가 호출부의 람다를 실제 체인에 적용하게 한다. */
    private static void routeBlockingCallsThrough(ThinkingPreviewHarness h, ChatModel chain, String text) {
        when(h.router.executeGatedWithUsage(any(TaskType.class), any(RoutingMode.class), any())).thenAnswer(inv -> {
            Function<ChatModel, ChatResponse> call = inv.getArgument(2);
            call.apply(chain);
            return new LlmRouter.LlmResult(text, 10, 10);
        });
    }

    private static Integer maxTokensOf(Prompt prompt) {
        return ((OpenAiChatOptions) prompt.getOptions()).getMaxTokens();
    }

    private static String textOf(Prompt prompt) {
        return prompt.getInstructions().stream().map(Message::getText).collect(Collectors.joining("\n"));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) count++;
        return count;
    }

    // ── 독립화(CONDENSE) — 호출부가 실제로 만든 요청 ─────────────────────────────────────────────

    @Test
    @DisplayName("독립화 — 낮게로 켜면 요청의 max_tokens 가 미리보기의 예약(기본 256 + 여유 512 = 768)과 같고, 서버에는 enable_thinking=true 가 간다")
    void condenseRequestMatchesThePreview() {
        ThinkingPreviewHarness h = ThinkingPreviewHarness.builder()
                .file(ThinkingPreviewHarness.levels(ThinkingSite.CONDENSE, ThinkingLevel.LOW)).build();
        List<Prompt> sent = new ArrayList<>();
        routeBlockingCallsThrough(h, chain(h, server(sent, "SSE 타임아웃 설정은 어디에 있어?")), "SSE 타임아웃 설정은 어디에 있어?");
        MemoryService memory = mock(MemoryService.class);
        when(memory.getRecentTurns(anyString(), anyString())).thenReturn(List.of(new MemoryRepository.Turn(
                1, "SSE 타임아웃 설정 어떻게 바꿔?", "답변", "2026-09-03 00:00:00", "2026-09-03 00:00:01",
                10, 10, 100, "local", 1, null, "N", null, false)));

        new QuestionCondenser(h.router, memory, bundle(), h.props).condense("u", "t", "그거 어디야?", Locale.KOREAN);

        assertThat(sent).hasSize(1);
        ThinkingPreview.Cell previewed = h.row(ThinkingSite.CONDENSE).cell(ThinkingLevel.LOW);
        assertThat(maxTokensOf(sent.get(0))).isEqualTo(previewed.reservedTokens()).isEqualTo(768);
        assertThat(((OpenAiChatOptions) sent.get(0).getOptions()).getExtraBody())
                .containsKey("chat_template_kwargs")
                .doesNotContainKey("__rag_thinking_site");
    }

    @Test
    @DisplayName("독립화 — 출하값(끔)에서는 여유가 없고 기본 예약 256 그대로다 — 미리보기도 같다")
    void condenseOffKeepsTheBaseReservation() {
        ThinkingPreviewHarness h = ThinkingPreviewHarness.builder().build();
        List<Prompt> sent = new ArrayList<>();
        routeBlockingCallsThrough(h, chain(h, server(sent, "x")), "x");
        MemoryService memory = mock(MemoryService.class);
        when(memory.getRecentTurns(anyString(), anyString())).thenReturn(List.of(new MemoryRepository.Turn(
                1, "SSE 타임아웃 설정 어떻게 바꿔?", "답변", "2026-09-03 00:00:00", "2026-09-03 00:00:01",
                10, 10, 100, "local", 1, null, "N", null, false)));

        new QuestionCondenser(h.router, memory, bundle(), h.props).condense("u", "t", "그거 어디야?", Locale.KOREAN);

        assertThat(maxTokensOf(sent.get(0))).isEqualTo(h.row(ThinkingSite.CONDENSE).savedCell().reservedTokens()).isEqualTo(256);
    }

    // ── 채팅 답변 + 검증 — 실제 AnswerService 를 돌려서 ────────────────────────────────────────────

    private AnswerService answerService(ThinkingPreviewHarness h) {
        return new AnswerService(h.router, h.props, bundle(), h.windows, AnswerStreamer.withoutThinkingControl(), h.budget);
    }

    /** 스트리밍 답변이 지나는 {@code stream=false} 프로바이더 — 그 경로는 체인을 지나고 프롬프트를 그대로 볼 수 있다. */
    private ChatModel streamingProvider(ThinkingPreviewHarness h, List<Prompt> streamed, String answer) {
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenAnswer(inv -> {
            streamed.add(inv.getArgument(0));
            return Flux.just(reply(answer));
        });
        LlmProvider provider = new LlmProvider("local", TaskType.TEXT, ProviderRole.LOCAL, 0, "key", null, "model",
                false, model, null);
        when(h.router.routeProvider(eq(TaskType.TEXT), any(RoutingMode.class))).thenReturn(provider);
        return model;
    }

    private static AgentState turn(ResponseMode mode) {
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < 10; i++) docs.add(new Document("가".repeat(CHUNK)));
        return AgentState.of("가".repeat(ThinkingPreviewService.QUESTION_CHARS), "v1", "t1", "", RoutingMode.COST_FIRST)
                .toBuilder().responseMode(mode).retrievedDocs(docs).build();
    }

    @Test
    @DisplayName("답변 프롬프트에 들어간 문서 수 = 미리보기의 '문서 k/top-K' — 요약(S) 모드에서 낮게·높게 모두(여유가 입력 예산을 깎는 것이 보이는 모드)")
    void answerPromptHoldsAsManyDocumentsAsThePreviewSays() {
        for (ThinkingLevel level : List.of(ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.HIGH)) {
            ThinkingPreviewHarness h = ThinkingPreviewHarness.builder()
                    .file(ThinkingPreviewHarness.levels(ThinkingSite.ANSWER_RAG_S, level)).build();
            List<Prompt> streamed = new ArrayList<>();
            streamingProvider(h, streamed, "답변");

            answerService(h).executeStreaming(turn(ResponseMode.S), new GraphListener() {});

            assertThat(streamed).as(level.name()).hasSize(1);
            int held = occurrences(textOf(streamed.get(0)), "가".repeat(CHUNK));
            ThinkingPreview.Meaning meaning = h.row(ThinkingSite.ANSWER_RAG_S).cell(level).meaning();
            assertThat(meaning.known()).isTrue();
            assertThat(held).as("%s — 프롬프트에 실제로 실린 청크 수".formatted(level)).isEqualTo((int) meaning.metric());
        }
        // 이 숫자가 수준에 따라 실제로 달라진다는 전제 — 달라지지 않으면 위 비교는 아무것도 증명하지 않는다
        ThinkingPreviewHarness h = ThinkingPreviewHarness.builder().build();
        assertThat(h.row(ThinkingSite.ANSWER_RAG_S).cell(ThinkingLevel.HIGH).meaning().metric())
                .isLessThan(h.row(ThinkingSite.ANSWER_RAG_S).cell(ThinkingLevel.OFF).meaning().metric());
    }

    @Test
    @DisplayName("검증 — 요청의 max_tokens 와 프롬프트의 발췌 수가 미리보기와 같다(중간: 예약 3,072 · 발췌 k/10)")
    void evalRequestAndExcerptsMatchThePreview() {
        ThinkingPreviewHarness h = ThinkingPreviewHarness.builder()
                .file(ThinkingPreviewHarness.levels(ThinkingSite.EVAL, ThinkingLevel.MEDIUM)).build();
        List<Prompt> streamed = new ArrayList<>();
        String answer = "가".repeat(ThinkingPreviewService.SHORT_ANSWER_CHARS);
        streamingProvider(h, streamed, answer);
        List<Prompt> evalSent = new ArrayList<>();
        routeBlockingCallsThrough(h, chain(h, server(evalSent, "{\"sufficient\":true,\"grounded\":true}")),
                "{\"sufficient\":true,\"grounded\":true}");

        answerService(h).executeStreaming(turn(ResponseMode.N), new GraphListener() {});

        assertThat(evalSent).as("검증 호출").hasSize(1);
        ThinkingPreview.Cell previewed = h.row(ThinkingSite.EVAL).cell(ThinkingLevel.MEDIUM);
        assertThat(maxTokensOf(evalSent.get(0))).as("요청의 max_tokens").isEqualTo(previewed.reservedTokens()).isEqualTo(3_072);
        // 시스템 프롬프트도 [D1] 같은 표기를 설명하므로, 실제 발췌 블록([문서 발췌] 이후)만 센다
        String evalText = textOf(evalSent.get(0));
        Matcher marks = EXCERPT_MARK.matcher(evalText.substring(evalText.lastIndexOf("[문서 발췌]")));
        int excerpts = 0;
        while (marks.find()) excerpts++;
        assertThat(excerpts).as("프롬프트에 실린 발췌 수").isEqualTo((int) previewed.meaning().metric());
        assertThat(previewed.meaning().text()).contains("발췌 " + excerpts + "/10개");
    }
}
