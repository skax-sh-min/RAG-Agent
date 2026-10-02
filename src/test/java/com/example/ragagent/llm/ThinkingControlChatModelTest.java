package com.example.ragagent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.NonTransientAiException;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 표시된 사이트의 생각 수준을 받는 프로바이더의 필드로 바꿔 싣는 데코레이터({@link ThinkingControlChatModel}). 실제 요청
 * 본문에 어떻게 실리는지는 Spring AI 의 직렬화까지 거치는 {@code ThinkingControlWireTest}(config 패키지)가 본다 —
 * 여기서는 판정만.
 */
class ThinkingControlChatModelTest {

    private static final String KWARGS = ThinkingDialect.TEMPLATE_KWARGS_FIELD;

    private static ChatResponse ok() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("한 줄"))));
    }

    private static Prompt marked(ThinkingSite site) {
        return new Prompt(List.of(new UserMessage("질문")),
                ThinkingControl.mark(OpenAiChatOptions.builder().maxTokens(256), site).build());
    }

    private static OpenAiChatOptions optionsOf(Prompt prompt) {
        return (OpenAiChatOptions) prompt.getOptions();
    }

    private static ProviderThinkingDialects dialects(String provider, ThinkingDialect configured, boolean local) {
        ProviderThinkingDialects d = new ProviderThinkingDialects();
        d.record(provider, configured, local);
        return d;
    }

    private static ThinkingControlChatModel model(ChatModel delegate, String provider, ProviderThinkingDialects dialects,
                                                  Function<ThinkingSite, ThinkingLevel> levels) {
        return new ThinkingControlChatModel(delegate, provider, dialects, levels, new ThinkingObservations());
    }

    private static Prompt sent(ChatModel delegate) {
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(delegate).call(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("LOCAL(auto) + 끔 — enable_thinking=false 를 싣고 표시 키는 걷어낸다. 원본 Prompt 는 그대로")
    void localOffSendsTheSwitchAndStripsTheMarker() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.call(any(Prompt.class))).thenReturn(ok());
        Prompt prompt = marked(ThinkingSite.CONDENSE);

        model(delegate, "local", dialects("local", ThinkingDialect.AUTO, true), s -> ThinkingLevel.OFF).call(prompt);

        OpenAiChatOptions out = optionsOf(sent(delegate));
        assertThat(out.getExtraBody())
                .containsEntry(KWARGS, Map.of("enable_thinking", false))
                .doesNotContainKey(ThinkingControl.SITE_MARKER);
        assertThat(out.getMaxTokens()).isEqualTo(256);
        assertThat(optionsOf(prompt).getExtraBody()).as("원본은 건드리지 않는다").containsKey(ThinkingControl.SITE_MARKER);
    }

    @Test
    @DisplayName("LOCAL(auto) + 낮게·중간·높게 — 모두 enable_thinking=true 로 접힌다")
    void localOnLevelsAllCollapseToTrue() {
        for (ThinkingLevel level : List.of(ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH)) {
            ChatModel delegate = mock(ChatModel.class);
            when(delegate.call(any(Prompt.class))).thenReturn(ok());

            model(delegate, "local", dialects("local", ThinkingDialect.AUTO, true), s -> level)
                    .call(marked(ThinkingSite.EVAL));

            assertThat(optionsOf(sent(delegate)).getExtraBody()).as(level.name())
                    .containsEntry(KWARGS, Map.of("enable_thinking", true));
        }
    }

    @Test
    @DisplayName("수준은 호출마다 다시 읽는다 — 핫 편집이 다음 호출에 바로 닿는다")
    void levelIsReadPerCall() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.call(any(Prompt.class))).thenReturn(ok());
        AtomicReference<ThinkingLevel> level = new AtomicReference<>(ThinkingLevel.OFF);
        ThinkingControlChatModel model = model(delegate, "local", dialects("local", ThinkingDialect.AUTO, true),
                s -> level.get());

        model.call(marked(ThinkingSite.CONDENSE));
        level.set(ThinkingLevel.HIGH);
        model.call(marked(ThinkingSite.CONDENSE));

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(delegate, times(2)).call(captor.capture());
        assertThat(captor.getAllValues()).extracting(p -> optionsOf(p).getExtraBody().get(KWARGS))
                .containsExactly(Map.of("enable_thinking", false), Map.of("enable_thinking", true));
    }

    @Test
    @DisplayName("원격(auto → 생각 제어 안 함) — 아무것도 싣지 않고 표시만 걷어낸다. 다른 extraBody 항목은 남는다")
    void remoteAutoSendsNothingButKeepsOtherFields() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.call(any(Prompt.class))).thenReturn(ok());
        Map<String, Object> body = new HashMap<>();
        body.put(ThinkingControl.SITE_MARKER, ThinkingSite.POST_ANSWER.name());
        body.put("top_k", 40);
        Prompt prompt = new Prompt(List.of(new UserMessage("질문")),
                OpenAiChatOptions.builder().maxTokens(256).extraBody(body).build());

        model(delegate, "openai-mini", dialects("openai-mini", ThinkingDialect.AUTO, false), s -> ThinkingLevel.OFF)
                .call(prompt);

        assertThat(optionsOf(sent(delegate)).getExtraBody())
                .containsOnlyKeys("top_k");
    }

    @Test
    @DisplayName("표시 하나뿐이었고 실을 것도 없으면 extraBody 자체를 비운다")
    void nothingLeftMeansNoExtraBody() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.call(any(Prompt.class))).thenReturn(ok());

        model(delegate, "gemini", dialects("gemini", ThinkingDialect.AUTO, false), s -> ThinkingLevel.LOW)
                .call(marked(ThinkingSite.CURATED_SUGGEST));

        assertThat(optionsOf(sent(delegate)).getExtraBody()).isNull();
    }

    @Test
    @DisplayName("모르는 프로바이더(등록되지 않은 이름)에는 아무것도 싣지 않는다 — 표준 밖 필드로 차단당하지 않게")
    void unknownProviderGetsNothing() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.call(any(Prompt.class))).thenReturn(ok());

        model(delegate, "who", new ProviderThinkingDialects(), s -> ThinkingLevel.OFF).call(marked(ThinkingSite.CONDENSE));

        assertThat(optionsOf(sent(delegate)).getExtraBody()).isNull();
    }

    @Test
    @DisplayName("openai-effort — 표준 필드 reasoning_effort 로 싣는다(끔은 none)")
    void openAiEffortUsesTheStandardField() {
        for (var c : List.of(Map.entry(ThinkingLevel.OFF, "none"), Map.entry(ThinkingLevel.HIGH, "high"))) {
            ChatModel delegate = mock(ChatModel.class);
            when(delegate.call(any(Prompt.class))).thenReturn(ok());

            model(delegate, "openai", dialects("openai", ThinkingDialect.OPENAI_EFFORT, false), s -> c.getKey())
                    .call(marked(ThinkingSite.EVAL));

            OpenAiChatOptions out = optionsOf(sent(delegate));
            assertThat(out.getReasoningEffort()).isEqualTo(c.getValue());
            assertThat(out.getExtraBody()).isNull();
        }
    }

    @Test
    @DisplayName("표시가 없는 요청은 손대지 않는다 — 아직 배선하지 않은 호출부는 지금처럼 나간다")
    void unmarkedPromptsPassThrough() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.call(any(Prompt.class))).thenReturn(ok());
        Prompt prompt = new Prompt(List.of(new UserMessage("질문")), OpenAiChatOptions.builder().maxTokens(10).build());
        ThinkingObservations observations = new ThinkingObservations();

        new ThinkingControlChatModel(delegate, "local", dialects("local", ThinkingDialect.AUTO, true),
                s -> ThinkingLevel.OFF, observations).call(prompt);

        verify(delegate).call(prompt);
        assertThat(observations.snapshot()).as("사이트를 모르는 호출은 관측하지 않는다").isEmpty();
    }

    @Test
    @DisplayName("서버가 필드를 거부하면 빼고 한 번 다시 보내고, 그 뒤로는 처음부터 싣지 않는다 — 실패가 라우터로 가지 않는다")
    void aServerThatRejectsTheFieldIsRememberedAndRetriedWithout() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.call(any(Prompt.class))).thenAnswer(inv -> {
            Prompt p = inv.getArgument(0);
            if (optionsOf(p).getExtraBody() != null && optionsOf(p).getExtraBody().containsKey(KWARGS)) {
                throw new NonTransientAiException(
                        "HTTP 400 - {\"error\":\"Unrecognized key(s) in object: 'chat_template_kwargs'\"}");
            }
            return ok();
        });
        ProviderThinkingDialects dialects = dialects("local", ThinkingDialect.AUTO, true);
        ThinkingControlChatModel model = model(delegate, "local", dialects, s -> ThinkingLevel.OFF);

        assertThat(model.call(marked(ThinkingSite.CONDENSE))).isNotNull();
        assertThat(model.call(marked(ThinkingSite.CONDENSE))).isNotNull();

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(delegate, times(3)).call(captor.capture());   // 거부 1 + 재시도 1 + 두 번째 호출은 처음부터 없이 1
        assertThat(captor.getAllValues())
                .extracting(p -> optionsOf(p).getExtraBody() != null && optionsOf(p).getExtraBody().containsKey(KWARGS))
                .containsExactly(true, false, false);
        assertThat(captor.getAllValues()).allSatisfy(p -> assertThat(optionsOf(p).getExtraBody() == null
                || !optionsOf(p).getExtraBody().containsKey(ThinkingControl.SITE_MARKER)).isTrue());
        assertThat(dialects.rejectedFields("local")).containsExactly(KWARGS);
    }

    @Test
    @DisplayName("거부 기억은 프로바이더 단위로 공유된다 — 다른 데코레이터 인스턴스(스트리밍 경로 등)도 다시 싣지 않는다")
    void rejectionMemoryIsSharedByProvider() {
        ProviderThinkingDialects dialects = dialects("local", ThinkingDialect.AUTO, true);
        dialects.markRejected("local", KWARGS);
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.call(any(Prompt.class))).thenReturn(ok());

        model(delegate, "local", dialects, s -> ThinkingLevel.OFF).call(marked(ThinkingSite.CONDENSE));

        assertThat(optionsOf(sent(delegate)).getExtraBody()).isNull();
    }

    @Test
    @DisplayName("필드와 무관한 실패는 그대로 던진다 — 재시도도, 기억도 하지 않는다")
    void unrelatedFailuresPropagate() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.call(any(Prompt.class)))
                .thenThrow(new NonTransientAiException("HTTP 400 - the request exceeds the available context size"))
                .thenReturn(ok());
        ProviderThinkingDialects dialects = dialects("local", ThinkingDialect.AUTO, true);
        ThinkingControlChatModel model = model(delegate, "local", dialects, s -> ThinkingLevel.OFF);

        assertThatThrownBy(() -> model.call(marked(ThinkingSite.CONDENSE))).hasMessageContaining("context size");
        model.call(marked(ThinkingSite.CONDENSE));

        verify(delegate, times(2)).call(any(Prompt.class));
        assertThat(dialects.rejectedFields("local")).as("거부로 기억되지 않았다").isEmpty();
    }

    @Test
    @DisplayName("성공한 호출은 (사이트, 프로바이더, 수준)으로 관측된다 — 생각 본문이 따로 오면 생각한 것으로 센다")
    void successfulCallsAreObserved() {
        ChatModel delegate = mock(ChatModel.class);
        AssistantMessage message = AssistantMessage.builder().content("391")
                .properties(Map.of(ThinkingObservations.REASONING_CONTENT_KEY, "Thinking Process: 17*23 ...")).build();
        ChatResponse response = new ChatResponse(
                List.of(new Generation(message, ChatGenerationMetadata.builder().finishReason("stop").build())),
                ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(31, 227, 258, new OpenAiApi.Usage(227, 31, 258))).build());
        when(delegate.call(any(Prompt.class))).thenReturn(response);
        ThinkingObservations observations = new ThinkingObservations();

        new ThinkingControlChatModel(delegate, "local", dialects("local", ThinkingDialect.AUTO, true),
                s -> ThinkingLevel.LOW, observations).call(marked(ThinkingSite.EVAL));

        List<ThinkingObservations.Sample> samples = observations.samples(ThinkingSite.EVAL, "local", ThinkingLevel.LOW);
        assertThat(samples).singleElement().satisfies(s -> {
            assertThat(s.sent()).isEqualTo(ThinkingWire.Sent.ON);
            assertThat(s.outputTokens()).isEqualTo(227);
            assertThat(s.thinkingObserved()).isTrue();
            assertThat(s.thinkingEstimated()).isTrue();
            assertThat(s.thinkingTokens()).as("출력 227 − 답변 '391' 추정 0").isEqualTo(227);
            assertThat(s.truncated()).isFalse();
        });
    }

    @Test
    @DisplayName("스트리밍도 같은 규칙으로 싣는다 — 원격에는 아무것도, LOCAL 에는 필드를")
    void streamingFollowsTheSameRule() {
        ChatModel remote = mock(ChatModel.class);
        when(remote.stream(any(Prompt.class))).thenReturn(Flux.just(ok()));
        model(remote, "openai-mini", dialects("openai-mini", ThinkingDialect.AUTO, false), s -> ThinkingLevel.OFF)
                .stream(marked(ThinkingSite.CONDENSE)).blockLast();
        ArgumentCaptor<Prompt> toRemote = ArgumentCaptor.forClass(Prompt.class);
        verify(remote).stream(toRemote.capture());
        assertThat(optionsOf(toRemote.getValue()).getExtraBody()).isNull();

        ChatModel local = mock(ChatModel.class);
        when(local.stream(any(Prompt.class))).thenReturn(Flux.just(ok()));
        model(local, "local", dialects("local", ThinkingDialect.AUTO, true), s -> ThinkingLevel.OFF)
                .stream(marked(ThinkingSite.CONDENSE)).blockLast();
        ArgumentCaptor<Prompt> toLocal = ArgumentCaptor.forClass(Prompt.class);
        verify(local).stream(toLocal.capture());
        assertThat(optionsOf(toLocal.getValue()).getExtraBody())
                .containsEntry(KWARGS, Map.of("enable_thinking", false))
                .doesNotContainKey(ThinkingControl.SITE_MARKER);
    }

    // ── 스트림(stream=false 프로바이더의 채팅 답변) — 거부 재시도와 관측 ───────────────────

    private static boolean carriesSwitch(Prompt p) {
        return optionsOf(p).getExtraBody() != null && optionsOf(p).getExtraBody().containsKey(KWARGS);
    }

    @Test
    @DisplayName("스트림 — 응답이 오기 전의 거부는 빼고 한 번 다시 보내고 기억한다(블로킹과 같은 규칙)")
    void streamRetriesARejectionBeforeAnyResponse() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.stream(any(Prompt.class))).thenAnswer(inv -> carriesSwitch(inv.getArgument(0))
                ? Flux.error(new NonTransientAiException("HTTP 400 - Unrecognized request argument: chat_template_kwargs"))
                : Flux.just(ok()));
        ProviderThinkingDialects dialects = dialects("local", ThinkingDialect.AUTO, true);

        List<ChatResponse> received = model(delegate, "local", dialects, s -> ThinkingLevel.LOW)
                .stream(marked(ThinkingSite.ANSWER_RAG_N)).collectList().block();

        assertThat(received).hasSize(1);
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(delegate, times(2)).stream(captor.capture());
        assertThat(captor.getAllValues()).extracting(ThinkingControlChatModelTest::carriesSwitch)
                .containsExactly(true, false);
        assertThat(dialects.rejectedFields("local")).containsExactly(KWARGS);
    }

    @Test
    @DisplayName("스트림 — 응답이 하나라도 흘러 나간 뒤의 실패는 다시 보내지 않는다(앞부분이 두 번 간다)")
    void streamDoesNotRetryAfterAResponseWentOut() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.stream(any(Prompt.class))).thenReturn(Flux.concat(Flux.just(ok()),
                Flux.error(new NonTransientAiException("HTTP 400 - chat_template_kwargs"))));
        ProviderThinkingDialects dialects = dialects("local", ThinkingDialect.AUTO, true);

        assertThatThrownBy(() -> model(delegate, "local", dialects, s -> ThinkingLevel.LOW)
                .stream(marked(ThinkingSite.ANSWER_RAG_N)).blockLast())
                .hasMessageContaining("chat_template_kwargs");

        verify(delegate, times(1)).stream(any(Prompt.class));
        assertThat(dialects.rejectedFields("local")).isEmpty();
    }

    @Test
    @DisplayName("스트림 — 정상 완료는 응답을 모아 관측한다. 오류로 끝난 스트림은 남기지 않는다")
    void streamCompletionIsObserved() {
        ChatModel delegate = mock(ChatModel.class);
        ChatResponse first = new ChatResponse(List.of(new Generation(new AssistantMessage("39"))));
        ChatResponse last = new ChatResponse(
                List.of(new Generation(new AssistantMessage("1"), ChatGenerationMetadata.builder().finishReason("LENGTH").build())),
                ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(31, 227, 258, new OpenAiApi.Usage(227, 31, 258))).build());
        when(delegate.stream(any(Prompt.class)))
                .thenReturn(Flux.just(first, last))
                .thenReturn(Flux.error(new IllegalStateException("connection reset")));
        ThinkingObservations observations = new ThinkingObservations();
        ThinkingControlChatModel model = new ThinkingControlChatModel(delegate, "local",
                dialects("local", ThinkingDialect.AUTO, true), s -> ThinkingLevel.HIGH, observations);

        model.stream(marked(ThinkingSite.ANSWER_DIRECT_N)).blockLast();
        assertThatThrownBy(() -> model.stream(marked(ThinkingSite.ANSWER_DIRECT_N)).blockLast())
                .hasMessageContaining("connection reset");

        assertThat(observations.samples(ThinkingSite.ANSWER_DIRECT_N, "local", ThinkingLevel.HIGH)).singleElement()
                .satisfies(s -> {
                    assertThat(s.sent()).isEqualTo(ThinkingWire.Sent.ON);
                    assertThat(s.outputTokens()).isEqualTo(227);
                    assertThat(s.thinkingObserved()).as("답 '391' 에 출력 227 — 블로킹과 같은 초과 규칙").isTrue();
                    assertThat(s.truncated()).isTrue();
                });
    }
}
