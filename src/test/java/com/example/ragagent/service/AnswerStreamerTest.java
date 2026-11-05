package com.example.ragagent.service;

import com.example.ragagent.llm.LlmProvider;
import com.example.ragagent.llm.ProviderRole;
import com.example.ragagent.llm.ProviderThinkingDialects;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.llm.ThinkingDialect;
import com.example.ragagent.llm.ThinkingLevel;
import com.example.ragagent.llm.ThinkingObservations;
import com.example.ragagent.llm.ThinkingSite;
import com.example.ragagent.llm.ThinkingWire;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.example.ragagent.LlmTestSupport.answer;
import static com.example.ragagent.LlmTestSupport.stop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 채팅 답변 스트리밍의 단일 경로({@link AnswerStreamer}) — PLAN §6.29 3단계. 실제 HTTP·직렬화까지 거치는 모양은
 * {@code AnswerStreamerWireTest} 가 보고, 여기서는 판정만 본다: 무엇을 싣는가, 생각 델타를 어떻게 가르는가, 언제 다시
 * 보내는가, 무엇을 관측하는가.
 */
class AnswerStreamerTest {

    private static final String KWARGS = ThinkingDialect.TEMPLATE_KWARGS_FIELD;
    private static final AnswerStreamer.Trace TRACE =
            new AnswerStreamer.Trace(LoggerFactory.getLogger(AnswerStreamerTest.class), "[Test]", "t1", RoutingMode.COST_FIRST);

    private OpenAiChatModel api;
    private LlmProvider provider;
    private ProviderThinkingDialects dialects;
    private ThinkingObservations observations;

    @BeforeEach
    void setUp() {
        api = mock(OpenAiChatModel.class);
        provider = new LlmProvider("local", TaskType.TEXT, ProviderRole.LOCAL, 1, "no-key",
                "http://127.0.0.1:1234/v1", "gemma", true, null, api);
        dialects = new ProviderThinkingDialects();
        dialects.record("local", ThinkingDialect.AUTO, true);   // LOCAL + auto → chat_template_kwargs
        observations = new ThinkingObservations();
    }

    private AnswerStreamer streamer(ThinkingLevel level) {
        return new AnswerStreamer(dialects, observations, site -> level);
    }

    private static ChatResponse chunk(String content, String reasoningSoFar, String finish) {
        return com.example.ragagent.LlmTestSupport.chunk(content, reasoningSoFar, finish);
    }

    /** 생각 델타들 — Spring AI 2.0 은 지금까지 쌓인 전체를 청크마다 붙인다(LlmTestSupport). */
    private static List<ChatResponse> thoughts(String... deltas) {
        return com.example.ragagent.LlmTestSupport.thoughts(deltas);
    }

    private static RuntimeException badRequest(String body) {
        return com.example.ragagent.LlmTestSupport.badRequest(body);
    }

    /** 스트림 하나 — 청크는 {@code ChatResponse}, 또는 청크 목록이 섞여 있어도 펼친다. */
    private static Flux<ChatResponse> flux(Object... items) {
        List<ChatResponse> out = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof ChatResponse r) out.add(r);
            else if (item instanceof List<?> l) l.forEach(x -> out.add((ChatResponse) x));
            else throw new IllegalArgumentException(String.valueOf(item));
        }
        return Flux.fromIterable(out);
    }

    private List<Prompt> sentRequests(int times) {
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(api, times(times)).stream(captor.capture());
        return captor.getAllValues();
    }

    private static OpenAiChatOptions optionsOf(Prompt p) {
        return (OpenAiChatOptions) p.getOptions();
    }

    private static boolean carriesSwitch(Prompt p) {
        return optionsOf(p).getExtraBody() != null && optionsOf(p).getExtraBody().containsKey(KWARGS);
    }

    @Test
    @DisplayName("LOCAL + 낮게 — 요청에 enable_thinking=true 가 실리고, 메시지·모델·온도·stream 은 예전 그대로다")
    void sendsTheSitesLevelOnTheRequest() {
        when(api.stream(any(Prompt.class))).thenReturn(Flux.just(answer("답"), stop()));

        streamer(ThinkingLevel.LOW).stream(provider, ThinkingSite.ANSWER_RAG_N, "시스템", "질문", 0.7,
                t -> { }, () -> { }, TRACE);

        Prompt sent = sentRequests(1).get(0);
        OpenAiChatOptions options = optionsOf(sent);
        assertThat(options.getExtraBody()).containsEntry(KWARGS, Map.of("enable_thinking", true));
        assertThat(options.getModel()).isEqualTo("gemma");
        assertThat(options.getTemperature()).isEqualTo(0.7);
        assertThat(options.getMaxTokens()).as("스트리밍 답변은 출력 상한을 보내지 않는다(예전 그대로)").isNull();
        assertThat(sent.getInstructions()).extracting(m -> m.getMessageType())
                .containsExactly(MessageType.SYSTEM, MessageType.USER);
        assertThat(sent.getInstructions()).extracting(m -> m.getText()).containsExactly("시스템", "질문");
    }

    @Test
    @DisplayName("수준은 호출마다 다시 읽는다 — 끔이면 enable_thinking=false")
    void levelIsReadPerCall() {
        when(api.stream(any(Prompt.class))).thenAnswer(inv -> Flux.just(answer("답"), stop()));
        AtomicReference<ThinkingLevel> level = new AtomicReference<>(ThinkingLevel.OFF);
        AnswerStreamer streamer = new AnswerStreamer(dialects, observations, site -> level.get());

        streamer.stream(provider, ThinkingSite.ANSWER_DIRECT_S, "s", "u", 0.1, t -> { }, () -> { }, TRACE);
        level.set(ThinkingLevel.HIGH);
        streamer.stream(provider, ThinkingSite.ANSWER_DIRECT_S, "s", "u", 0.1, t -> { }, () -> { }, TRACE);

        assertThat(sentRequests(2)).extracting(r -> optionsOf(r).getExtraBody().get(KWARGS))
                .containsExactly(Map.of("enable_thinking", false), Map.of("enable_thinking", true));
    }

    @Test
    @DisplayName("생각 델타는 활동으로만 알리고 화면에 내보내지 않는다 — 답 델타만 토큰이다")
    void reasoningDeltasAreActivityNotTokens() {
        // 생각 본문은 청크마다 지금까지 쌓인 전체로 온다 — 답 청크에도 그 값이 계속 붙는다(Spring AI 2.0 실측).
        // 그 값이 늘지 않는 청크는 생각 델타가 아니다.
        when(api.stream(any(Prompt.class))).thenReturn(flux(
                chunk(null, null, null),                // llama.cpp 의 첫 청크(role 만)
                thoughts("Thinking", " Process", ": 2+3"),
                chunk("다섯", "Thinking Process: 2+3", null), chunk("입니다", "Thinking Process: 2+3", null), stop()));
        List<String> tokens = new ArrayList<>();
        AtomicInteger thinking = new AtomicInteger();

        streamer(ThinkingLevel.LOW).stream(provider, ThinkingSite.ANSWER_RAG_N, "s", "u", 0.0,
                tokens::add, thinking::incrementAndGet, TRACE);

        assertThat(tokens).containsExactly("다섯", "입니다");
        assertThat(thinking.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("정상 완료는 (사이트, 프로바이더, 수준)으로 관측된다 — 델타 수와 마지막 finish_reason")
    void completionIsObserved() {
        when(api.stream(any(Prompt.class))).thenReturn(flux(
                thoughts("a", "b"), chunk("답", "ab", null), chunk(null, "ab", "LENGTH")));

        streamer(ThinkingLevel.MEDIUM).stream(provider, ThinkingSite.ANSWER_RAG_C, "s", "u", 0.7,
                t -> { }, () -> { }, TRACE);

        assertThat(observations.samples(ThinkingSite.ANSWER_RAG_C, "local", ThinkingLevel.MEDIUM)).singleElement()
                .satisfies(s -> {
                    assertThat(s.sent()).isEqualTo(ThinkingWire.Sent.ON);
                    assertThat(s.thinkingObserved()).isTrue();
                    assertThat(s.thinkingTokens()).isEqualTo(2);
                    assertThat(s.outputTokens()).isEqualTo(3);
                    assertThat(s.outputEstimated()).isTrue();
                    assertThat(s.truncated()).isTrue();
                });
    }

    @Test
    @DisplayName("서버가 필드를 거부하면(아무 청크도 오기 전) 빼고 한 번 다시 보내고, 다음부터는 처음부터 싣지 않는다")
    void aRejectedFieldIsRememberedAndTheStreamResent() {
        when(api.stream(any(Prompt.class))).thenAnswer(inv -> carriesSwitch(inv.getArgument(0))
                ? Flux.error(badRequest("Unrecognized request argument supplied: chat_template_kwargs"))
                : Flux.just(answer("답"), stop()));
        List<String> tokens = new ArrayList<>();
        AnswerStreamer streamer = streamer(ThinkingLevel.OFF);

        streamer.stream(provider, ThinkingSite.ANSWER_RAG_S, "s", "u", 0.0, tokens::add, () -> { }, TRACE);
        streamer.stream(provider, ThinkingSite.ANSWER_RAG_S, "s", "u", 0.0, tokens::add, () -> { }, TRACE);

        assertThat(tokens).containsExactly("답", "답");
        assertThat(sentRequests(3)).extracting(AnswerStreamerTest::carriesSwitch)
                .as("거부 1 + 재시도 1 + 두 번째 호출은 처음부터 없이 1").containsExactly(true, false, false);
        assertThat(dialects.rejectedFields("local")).containsExactly(KWARGS);
        assertThat(observations.samples(ThinkingSite.ANSWER_RAG_S, "local", ThinkingLevel.OFF))
                .as("재시도한 호출은 아무것도 싣지 않았다 — '서버가 정한다'로 남는다")
                .hasSize(2).allSatisfy(s -> assertThat(s.sent()).isEqualTo(ThinkingWire.Sent.NOTHING));
    }

    @Test
    @DisplayName("청크가 하나라도 온 뒤의 실패는 다시 보내지 않는다 — 화면에 앞부분이 두 번 찍힌다")
    void aFailureAfterTheFirstChunkIsNotRetried() {
        // 오류를 늦게 보낸다 — 실제 스트림처럼 청크 사이에 틈이 있어야 소비자가 앞 청크를 받는다. 동기 Flux 로 붙여
        // 보내면 Reactor 의 toIterable() 은 큐에 남은 항목보다 종료 오류를 먼저 던진다.
        when(api.stream(any(Prompt.class))).thenReturn(Flux.concat(Flux.just(answer("앞부분")),
                Flux.<ChatResponse>error(badRequest("chat_template_kwargs"))
                        .delaySubscription(java.time.Duration.ofMillis(50))));
        List<String> tokens = new ArrayList<>();

        assertThatThrownBy(() -> streamer(ThinkingLevel.LOW).stream(provider, ThinkingSite.ANSWER_RAG_N,
                "s", "u", 0.0, tokens::add, () -> { }, TRACE))
                .isInstanceOf(com.openai.errors.BadRequestException.class);

        assertThat(tokens).containsExactly("앞부분");
        sentRequests(1);
        assertThat(dialects.rejectedFields("local")).isEmpty();
        assertThat(observations.snapshot()).as("끊긴 스트림은 관측하지 않는다").isEmpty();
    }

    @Test
    @DisplayName("필드와 무관한 실패(컨텍스트 초과 등)는 그대로 올린다 — 축소 재시도는 호출부의 몫이다")
    void unrelatedFailuresPropagateUntouched() {
        RuntimeException overflow = badRequest("request (40016 tokens) exceeds the available context size (32768 tokens)");
        when(api.stream(any(Prompt.class))).thenReturn(Flux.error(overflow));

        assertThatThrownBy(() -> streamer(ThinkingLevel.LOW).stream(provider, ThinkingSite.ANSWER_RAG_N,
                "s", "u", 0.0, t -> { }, () -> { }, TRACE))
                .isSameAs(overflow);

        sentRequests(1);
        assertThat(dialects.rejectedFields("local")).isEmpty();
    }

    @Test
    @DisplayName("소비자가 실패하면(연결 끊김) 다시 보내지 않고 그대로 올린다")
    void aConsumerFailureIsNotRetried() {
        when(api.stream(any(Prompt.class))).thenReturn(flux(thoughts("생각"), answer("답"), stop()));

        assertThatThrownBy(() -> streamer(ThinkingLevel.LOW).stream(provider, ThinkingSite.ANSWER_RAG_N,
                "s", "u", 0.0, t -> { }, () -> { throw new UncheckedIOException(new java.io.IOException("broken pipe")); },
                TRACE))
                .isInstanceOf(UncheckedIOException.class);

        sentRequests(1);
    }

    @Test
    @DisplayName("원격(auto → 생각 제어 안 함)과 생각 제어 없는 축약 — 아무 필드도 싣지 않는다(예전 그대로)")
    void nothingIsSentWhereThinkingIsNotControlled() {
        when(api.stream(any(Prompt.class))).thenAnswer(inv -> Flux.just(answer("답"), stop()));
        dialects.record("remote", ThinkingDialect.AUTO, false);
        LlmProvider remote = new LlmProvider("remote", TaskType.TEXT, ProviderRole.NORMAL, 2, "sk",
                "https://api.example.com/v1", "gpt", true, null, api);

        streamer(ThinkingLevel.HIGH).stream(remote, ThinkingSite.ANSWER_RAG_N, "s", "u", 0.0, t -> { }, () -> { }, TRACE);
        AnswerStreamer.withoutThinkingControl().stream(provider, ThinkingSite.ANSWER_RAG_N, "s", "u", 0.0,
                t -> { }, () -> { }, TRACE);

        assertThat(sentRequests(2)).allSatisfy(r -> {
            assertThat(optionsOf(r).getExtraBody()).isNullOrEmpty();
            assertThat(optionsOf(r).getReasoningEffort()).isNull();
        });
    }
}
