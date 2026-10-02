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
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

    private OpenAiApi api;
    private LlmProvider provider;
    private ProviderThinkingDialects dialects;
    private ThinkingObservations observations;

    @BeforeEach
    void setUp() {
        api = mock(OpenAiApi.class);
        provider = new LlmProvider("local", TaskType.TEXT, ProviderRole.LOCAL, 1, "no-key",
                "http://127.0.0.1:1234/v1", "gemma", true, null, api);
        dialects = new ProviderThinkingDialects();
        dialects.record("local", ThinkingDialect.AUTO, true);   // LOCAL + auto → chat_template_kwargs
        observations = new ThinkingObservations();
    }

    private AnswerStreamer streamer(ThinkingLevel level) {
        return new AnswerStreamer(dialects, observations, site -> level);
    }

    private static OpenAiApi.ChatCompletionChunk chunk(String content, String reasoning,
                                                       OpenAiApi.ChatCompletionFinishReason finish) {
        var delta = new OpenAiApi.ChatCompletionMessage(content, OpenAiApi.ChatCompletionMessage.Role.ASSISTANT,
                null, null, null, null, null, null, reasoning);
        var choice = new OpenAiApi.ChatCompletionChunk.ChunkChoice(finish, 0, delta, null);
        return new OpenAiApi.ChatCompletionChunk("c1", List.of(choice), null, "gemma", null, null, null, null);
    }

    private static OpenAiApi.ChatCompletionChunk thought(String text) {
        return chunk(null, text, null);
    }

    private static OpenAiApi.ChatCompletionChunk answer(String text) {
        return chunk(text, null, null);
    }

    private static OpenAiApi.ChatCompletionChunk stop() {
        return chunk(null, null, OpenAiApi.ChatCompletionFinishReason.STOP);
    }

    private static WebClientResponseException badRequest(String body) {
        return WebClientResponseException.create(400, "Bad Request", new HttpHeaders(),
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private List<OpenAiApi.ChatCompletionRequest> sentRequests(int times) {
        ArgumentCaptor<OpenAiApi.ChatCompletionRequest> captor = ArgumentCaptor.forClass(OpenAiApi.ChatCompletionRequest.class);
        verify(api, times(times)).chatCompletionStream(captor.capture());
        return captor.getAllValues();
    }

    private static boolean carriesSwitch(OpenAiApi.ChatCompletionRequest r) {
        return r.extraBody() != null && r.extraBody().containsKey(KWARGS);
    }

    @Test
    @DisplayName("LOCAL + 낮게 — 요청에 enable_thinking=true 가 실리고, 메시지·모델·온도·stream 은 예전 그대로다")
    void sendsTheSitesLevelOnTheRequest() {
        when(api.chatCompletionStream(any())).thenReturn(Flux.just(answer("답"), stop()));

        streamer(ThinkingLevel.LOW).stream(provider, ThinkingSite.ANSWER_RAG_N, "시스템", "질문", 0.7,
                t -> { }, () -> { }, TRACE);

        OpenAiApi.ChatCompletionRequest sent = sentRequests(1).get(0);
        assertThat(sent.extraBody()).containsEntry(KWARGS, Map.of("enable_thinking", true));
        assertThat(sent.model()).isEqualTo("gemma");
        assertThat(sent.temperature()).isEqualTo(0.7);
        assertThat(sent.stream()).isTrue();
        assertThat(sent.maxTokens()).as("스트리밍 답변은 출력 상한을 보내지 않는다(예전 그대로)").isNull();
        assertThat(sent.messages()).extracting(OpenAiApi.ChatCompletionMessage::role)
                .containsExactly(OpenAiApi.ChatCompletionMessage.Role.SYSTEM, OpenAiApi.ChatCompletionMessage.Role.USER);
        assertThat(sent.messages()).extracting(OpenAiApi.ChatCompletionMessage::content).containsExactly("시스템", "질문");
    }

    @Test
    @DisplayName("수준은 호출마다 다시 읽는다 — 끔이면 enable_thinking=false")
    void levelIsReadPerCall() {
        when(api.chatCompletionStream(any())).thenAnswer(inv -> Flux.just(answer("답"), stop()));
        AtomicReference<ThinkingLevel> level = new AtomicReference<>(ThinkingLevel.OFF);
        AnswerStreamer streamer = new AnswerStreamer(dialects, observations, site -> level.get());

        streamer.stream(provider, ThinkingSite.ANSWER_DIRECT_S, "s", "u", 0.1, t -> { }, () -> { }, TRACE);
        level.set(ThinkingLevel.HIGH);
        streamer.stream(provider, ThinkingSite.ANSWER_DIRECT_S, "s", "u", 0.1, t -> { }, () -> { }, TRACE);

        assertThat(sentRequests(2)).extracting(r -> r.extraBody().get(KWARGS))
                .containsExactly(Map.of("enable_thinking", false), Map.of("enable_thinking", true));
    }

    @Test
    @DisplayName("생각 델타는 활동으로만 알리고 화면에 내보내지 않는다 — 답 델타만 토큰이다")
    void reasoningDeltasAreActivityNotTokens() {
        when(api.chatCompletionStream(any())).thenReturn(Flux.just(
                chunk(null, null, null),                // llama.cpp 의 첫 청크(role 만)
                thought("Thinking"), thought(" Process"), thought(": 2+3"),
                answer("다섯"), answer("입니다"), stop()));
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
        when(api.chatCompletionStream(any())).thenReturn(Flux.just(
                thought("a"), thought("b"), answer("답"), chunk(null, null, OpenAiApi.ChatCompletionFinishReason.LENGTH)));

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
        when(api.chatCompletionStream(any())).thenAnswer(inv -> carriesSwitch(inv.getArgument(0))
                ? Flux.error(badRequest("{\"error\":{\"message\":\"Unrecognized request argument supplied: chat_template_kwargs\"}}"))
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
        when(api.chatCompletionStream(any())).thenReturn(Flux.concat(Flux.just(answer("앞부분")),
                Flux.<OpenAiApi.ChatCompletionChunk>error(badRequest("chat_template_kwargs"))
                        .delaySubscription(java.time.Duration.ofMillis(50))));
        List<String> tokens = new ArrayList<>();

        assertThatThrownBy(() -> streamer(ThinkingLevel.LOW).stream(provider, ThinkingSite.ANSWER_RAG_N,
                "s", "u", 0.0, tokens::add, () -> { }, TRACE))
                .isInstanceOf(WebClientResponseException.class);

        assertThat(tokens).containsExactly("앞부분");
        sentRequests(1);
        assertThat(dialects.rejectedFields("local")).isEmpty();
        assertThat(observations.snapshot()).as("끊긴 스트림은 관측하지 않는다").isEmpty();
    }

    @Test
    @DisplayName("필드와 무관한 실패(컨텍스트 초과 등)는 그대로 올린다 — 축소 재시도는 호출부의 몫이다")
    void unrelatedFailuresPropagateUntouched() {
        WebClientResponseException overflow = badRequest(
                "{\"error\":{\"message\":\"request (40016 tokens) exceeds the available context size (32768 tokens)\"}}");
        when(api.chatCompletionStream(any())).thenReturn(Flux.error(overflow));

        assertThatThrownBy(() -> streamer(ThinkingLevel.LOW).stream(provider, ThinkingSite.ANSWER_RAG_N,
                "s", "u", 0.0, t -> { }, () -> { }, TRACE))
                .isSameAs(overflow);

        sentRequests(1);
        assertThat(dialects.rejectedFields("local")).isEmpty();
    }

    @Test
    @DisplayName("소비자가 실패하면(연결 끊김) 다시 보내지 않고 그대로 올린다")
    void aConsumerFailureIsNotRetried() {
        when(api.chatCompletionStream(any())).thenReturn(Flux.just(thought("생각"), answer("답"), stop()));

        assertThatThrownBy(() -> streamer(ThinkingLevel.LOW).stream(provider, ThinkingSite.ANSWER_RAG_N,
                "s", "u", 0.0, t -> { }, () -> { throw new UncheckedIOException(new java.io.IOException("broken pipe")); },
                TRACE))
                .isInstanceOf(UncheckedIOException.class);

        sentRequests(1);
    }

    @Test
    @DisplayName("원격(auto → 생각 제어 안 함)과 생각 제어 없는 축약 — 아무 필드도 싣지 않는다(예전 그대로)")
    void nothingIsSentWhereThinkingIsNotControlled() {
        when(api.chatCompletionStream(any())).thenAnswer(inv -> Flux.just(answer("답"), stop()));
        dialects.record("remote", ThinkingDialect.AUTO, false);
        LlmProvider remote = new LlmProvider("remote", TaskType.TEXT, ProviderRole.NORMAL, 2, "sk",
                "https://api.example.com/v1", "gpt", true, null, api);

        streamer(ThinkingLevel.HIGH).stream(remote, ThinkingSite.ANSWER_RAG_N, "s", "u", 0.0, t -> { }, () -> { }, TRACE);
        AnswerStreamer.withoutThinkingControl().stream(provider, ThinkingSite.ANSWER_RAG_N, "s", "u", 0.0,
                t -> { }, () -> { }, TRACE);

        assertThat(sentRequests(2)).allSatisfy(r -> {
            assertThat(r.extraBody()).isEmpty();
            assertThat(r.reasoningEffort()).isNull();
        });
    }
}
