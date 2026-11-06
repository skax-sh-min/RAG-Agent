package com.example.ragagent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 프로바이더의 모델 id 를 체인 가장 안쪽에서 요청에 싣는 데코레이터. 이것이 없으면 호출부가 모델을 안 실은 블로킹 호출이
 * 라이브러리 기본값({@code gpt-5-mini})으로 나간다 — 실제 본문은 {@code ThinkingControlWireTest} 가 본다.
 */
class ModelPinningChatModelTest {

    private static ChatModel delegateCapturing(AtomicReference<Prompt> seen) {
        ChatModel delegate = mock(ChatModel.class);
        ChatResponse ok = new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        when(delegate.call(any(Prompt.class))).thenAnswer(inv -> {
            seen.set(inv.getArgument(0));
            return ok;
        });
        when(delegate.stream(any(Prompt.class))).thenAnswer(inv -> {
            seen.set(inv.getArgument(0));
            return Flux.just(ok);
        });
        return delegate;
    }

    private static Prompt prompt(ChatOptions options) {
        return new Prompt(List.of(new UserMessage("q")), options);
    }

    @Test
    @DisplayName("모델을 싣지 않은 옵션(라이브러리 기본값이 들어 있다)에는 프로바이더 모델이 실린다")
    void callerWithoutModelGetsTheProviderModel() {
        var seen = new AtomicReference<Prompt>();
        var pinning = new ModelPinningChatModel(delegateCapturing(seen), "p1", "gemma-4-E2B");

        pinning.call(prompt(OpenAiChatOptions.builder().temperature(0.0).maxTokens(256).build()));

        assertThat(seen.get().getOptions().getModel()).isEqualTo("gemma-4-E2B");
    }

    @Test
    @DisplayName("호출자가 다른 모델을 실었어도 프로바이더 모델이 이긴다 — 모델은 호출이 아니라 프로바이더의 성질이다")
    void providerModelWinsOverTheCaller() {
        var seen = new AtomicReference<Prompt>();
        var pinning = new ModelPinningChatModel(delegateCapturing(seen), "p1", "gemma-4-E2B");

        pinning.call(prompt(OpenAiChatOptions.builder().model("other").build()));

        assertThat(seen.get().getOptions().getModel()).isEqualTo("gemma-4-E2B");
    }

    @Test
    @DisplayName("모델 말고는 아무것도 바꾸지 않는다 — 온도·출력 상한·extraBody 가 그대로이고 원본 프롬프트는 건드리지 않는다")
    void everythingElseIsKeptAndTheOriginalIsUntouched() {
        var seen = new AtomicReference<Prompt>();
        var pinning = new ModelPinningChatModel(delegateCapturing(seen), "p1", "m");
        OpenAiChatOptions original = OpenAiChatOptions.builder()
                .temperature(0.3).maxTokens(512).reasoningEffort("low")
                .extraBody(Map.of("chat_template_kwargs", Map.of("enable_thinking", false))).build();
        Prompt sent = prompt(original);

        pinning.call(sent);

        OpenAiChatOptions got = (OpenAiChatOptions) seen.get().getOptions();
        assertThat(got.getTemperature()).isEqualTo(0.3);
        assertThat(got.getMaxTokens()).isEqualTo(512);
        assertThat(got.getReasoningEffort()).isEqualTo("low");
        assertThat(got.getExtraBody()).containsKey("chat_template_kwargs");
        assertThat(sent.getOptions()).as("원본 옵션 객체는 그대로").isSameAs(original);
        assertThat(original.getModel()).as("원본의 모델 값도 그대로").isNotEqualTo("m");
        assertThat(seen.get().getInstructions()).hasSize(1);
    }

    @Test
    @DisplayName("이미 같은 모델이면 프롬프트를 그대로 통과시키고, 옵션이 없으면 원본 모델의 기본 옵션에 맡긴다")
    void passesThroughWhenThereIsNothingToDo() {
        var seen = new AtomicReference<Prompt>();
        var pinning = new ModelPinningChatModel(delegateCapturing(seen), "p1", "m");

        Prompt same = prompt(OpenAiChatOptions.builder().model("m").build());
        pinning.call(same);
        assertThat(seen.get()).isSameAs(same);

        Prompt noOptions = prompt(null);
        pinning.call(noOptions);
        assertThat(seen.get()).isSameAs(noOptions);
    }

    @Test
    @DisplayName("모델 id 가 비어 있으면 아무것도 하지 않는다")
    void blankModelIsANoOp() {
        var seen = new AtomicReference<Prompt>();
        var pinning = new ModelPinningChatModel(delegateCapturing(seen), "p1", " ");
        Prompt sent = prompt(OpenAiChatOptions.builder().temperature(0.0).build());

        pinning.call(sent);

        assertThat(seen.get()).isSameAs(sent);
    }

    @Test
    @DisplayName("일반 ChatOptions 도 OpenAiChatOptions 로 옮겨 모델을 싣고, 공통 필드는 잃지 않는다")
    void genericOptionsAreConvertedAndKeepTheirCommonFields() {
        var seen = new AtomicReference<Prompt>();
        var pinning = new ModelPinningChatModel(delegateCapturing(seen), "p1", "m");

        pinning.call(prompt(ChatOptions.builder().temperature(0.2).maxTokens(128).build()));

        assertThat(seen.get().getOptions()).isInstanceOf(OpenAiChatOptions.class);
        assertThat(seen.get().getOptions().getModel()).isEqualTo("m");
        assertThat(seen.get().getOptions().getTemperature()).isEqualTo(0.2);
        assertThat(seen.get().getOptions().getMaxTokens()).isEqualTo(128);
    }

    @Test
    @DisplayName("스트림 호출에도 같은 모델이 실린다(stream=false 프로바이더의 체인 경유 답변)")
    void streamingCallsArePinnedToo() {
        var seen = new AtomicReference<Prompt>();
        var pinning = new ModelPinningChatModel(delegateCapturing(seen), "p1", "m");

        pinning.stream(prompt(OpenAiChatOptions.builder().maxTokens(64).build())).blockLast();

        assertThat(seen.get().getOptions().getModel()).isEqualTo("m");
    }

    @Test
    @DisplayName("getOptions() 는 안쪽 모델의 기본 옵션을 그대로 돌려준다 — ChatClient 가 빈 옵션을 이어받지 않게")
    void getOptionsDelegates() {
        ChatModel delegate = mock(ChatModel.class);
        ChatOptions defaults = OpenAiChatOptions.builder().model("m").build();
        when(delegate.getOptions()).thenReturn(defaults);

        assertThat(new ModelPinningChatModel(delegate, "p1", "m").getOptions()).isSameAs(defaults);
    }
}
