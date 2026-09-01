package com.example.ragagent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 프로바이더별 {@code max-tokens} 상한의 실제 적용 지점.
 *
 * <p>여기서 지키는 계약은 하나다: <b>내리기만 하고, 올리지도 새로 만들지도 않는다.</b> 이 데코레이터가
 * 없으면 프로바이더별 설정은 채팅 경로에서 무의미하다 — 프로바이더 빈의 {@code defaultOptions} 에
 * 구워 넣은 값을 블로킹 호출부의 per-call 옵션이 매번 덮어쓰기 때문이다.
 */
class MaxTokensCappingChatModelTest {

    /** delegate 가 실제로 받은 Prompt 를 붙잡아 두고, 그 옵션의 maxTokens 를 돌려준다. */
    private static Integer maxTokensSeenBy(ChatModel capping, AtomicReference<Prompt> seen, Prompt sent) {
        capping.call(sent);
        Prompt received = seen.get();
        return received.getOptions() == null ? null : received.getOptions().getMaxTokens();
    }

    private static ChatModel delegateCapturing(AtomicReference<Prompt> seen) {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.call(any(Prompt.class))).thenAnswer(inv -> {
            seen.set(inv.getArgument(0));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        });
        return delegate;
    }

    private static Prompt promptWith(Integer maxTokens) {
        OpenAiChatOptions.Builder b = OpenAiChatOptions.builder().model("m");
        if (maxTokens != null) b.maxTokens(maxTokens);
        return new Prompt(List.of(new org.springframework.ai.chat.messages.UserMessage("q")), b.build());
    }

    @Test
    @DisplayName("호출자가 상한보다 큰 값을 실으면 프로바이더 상한으로 내려간다")
    void loweredWhenCallerAsksForMore() {
        var seen = new AtomicReference<Prompt>();
        var capping = new MaxTokensCappingChatModel(delegateCapturing(seen), "small", 4_000);

        assertThat(maxTokensSeenBy(capping, seen, promptWith(10_000))).isEqualTo(4_000);
    }

    @Test
    @DisplayName("호출자가 더 작은 값을 골랐으면 그 의도가 이긴다 — 상한은 올리지 않는다")
    void smallerCallerValueWins() {
        var seen = new AtomicReference<Prompt>();
        var capping = new MaxTokensCappingChatModel(delegateCapturing(seen), "small", 4_000);

        // 검증 호출이 스스로 2,048 로 조이는 경우가 정확히 이것이다(AnswerService.evalOptions).
        assertThat(maxTokensSeenBy(capping, seen, promptWith(2_048))).isEqualTo(2_048);
    }

    @Test
    @DisplayName("옵션에 maxTokens 가 없으면 새로 만들지 않는다 — 프로바이더 빈의 기본값이 폴백으로 남아야 한다")
    void absentMaxTokensIsNotInvented() {
        var seen = new AtomicReference<Prompt>();
        var capping = new MaxTokensCappingChatModel(delegateCapturing(seen), "small", 4_000);

        assertThat(maxTokensSeenBy(capping, seen, promptWith(null))).isNull();
    }

    @Test
    @DisplayName("상한이 0 이하면(=미설정 프로바이더) 아무것도 하지 않는다")
    void noCeilingIsANoop() {
        var seen = new AtomicReference<Prompt>();
        var capping = new MaxTokensCappingChatModel(delegateCapturing(seen), "any", 0);

        assertThat(maxTokensSeenBy(capping, seen, promptWith(10_000))).isEqualTo(10_000);
    }

    @Test
    @DisplayName("원본 Prompt 는 변형되지 않는다 — 호출부가 같은 옵션을 재사용해도 값이 새지 않는다")
    void originalPromptIsNotMutated() {
        var seen = new AtomicReference<Prompt>();
        var capping = new MaxTokensCappingChatModel(delegateCapturing(seen), "small", 4_000);
        Prompt original = promptWith(10_000);

        capping.call(original);

        assertThat(original.getOptions().getMaxTokens())
                .as("데코레이터가 호출자의 옵션 객체를 제자리에서 고치면 다음 호출까지 오염된다")
                .isEqualTo(10_000);
        assertThat(seen.get().getOptions().getMaxTokens()).isEqualTo(4_000);
    }
}
