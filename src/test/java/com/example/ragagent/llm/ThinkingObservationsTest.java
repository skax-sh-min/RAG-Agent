package com.example.ragagent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.api.OpenAiApi;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ThinkingObservationsTest {

    private static ChatResponse response(String content, String reasoning, String finish, OpenAiApi.Usage usage) {
        AssistantMessage message = AssistantMessage.builder().content(content)
                .properties(Map.of(ThinkingObservations.REASONING_CONTENT_KEY, reasoning == null ? "" : reasoning))
                .build();
        Generation generation = new Generation(message, ChatGenerationMetadata.builder().finishReason(finish).build());
        ChatResponseMetadata.Builder meta = ChatResponseMetadata.builder();
        if (usage != null) {
            meta.usage(new DefaultUsage(usage.promptTokens(), usage.completionTokens(), usage.totalTokens(), usage));
        }
        return new ChatResponse(List.of(generation), meta.build());
    }

    @Test
    @DisplayName("서버가 reasoning_tokens 를 보고하면 그 값을 쓴다(추정 아님)")
    void reportedReasoningTokensWin() {
        var usage = new OpenAiApi.Usage(500, 40, 540, null,
                new OpenAiApi.Usage.CompletionTokenDetails(420, null, null, null));

        var s = ThinkingObservations.sampleOf(ThinkingWire.Sent.ON, response("답", "", "stop", usage), 1200);

        assertThat(s.thinkingTokens()).isEqualTo(420);
        assertThat(s.thinkingEstimated()).isFalse();
        assertThat(s.thinkingObserved()).isTrue();
        assertThat(s.outputTokens()).isEqualTo(500);
        assertThat(s.latencyMs()).isEqualTo(1200);
    }

    @Test
    @DisplayName("보고가 없고 생각 본문이 따로 오면 — 출력 토큰 − 답변 추정으로 센다(llama.cpp 의 실제 모양)")
    void estimatesFromOutputTokensWhenReasoningContentArrives() {
        var usage = new OpenAiApi.Usage(227, 31, 258);

        var s = ThinkingObservations.sampleOf(ThinkingWire.Sent.ON,
                response("답변 열 글자입니다", "Thinking Process: ...", "stop", usage), 7500);

        assertThat(s.thinkingObserved()).isTrue();
        assertThat(s.thinkingEstimated()).isTrue();
        assertThat(s.thinkingTokens()).as("227 − 한글 8자").isEqualTo(219);
    }

    @Test
    @DisplayName("usage 도 없으면 생각 본문 자체를 추정한다")
    void estimatesFromReasoningTextWithoutUsage() {
        var s = ThinkingObservations.sampleOf(ThinkingWire.Sent.ON, response("답", "생각생각", "stop", null), 10);

        assertThat(s.outputTokens()).isNull();
        assertThat(s.thinkingTokens()).isEqualTo(4);
    }

    @Test
    @DisplayName("생각 본문도 보고도 없을 때(이 앱의 블로킹 경로) — 출력이 답변 추정을 크게 넘으면 그 초과분이 생각이다")
    void excessOutputMeansThinking() {
        // 2026-10-02 실측 모양: 답 '391' 에 출력 227 토큰 — Spring AI 가 reasoning_content 를 버린 뒤의 응답
        var s = ThinkingObservations.sampleOf(ThinkingWire.Sent.OFF,
                response("391", null, "STOP", new OpenAiApi.Usage(227, 31, 258)), 7500);

        assertThat(s.thinkingObserved()).as("끔으로 보냈는데 생각했다 — 스위치를 무시하는 서버의 모양").isTrue();
        assertThat(s.thinkingEstimated()).isTrue();
        assertThat(s.thinkingTokens()).isEqualTo(227);
    }

    @Test
    @DisplayName("초과가 작거나(64 미만) 답변 추정의 두 배를 못 넘으면 생각으로 세지 않는다 — 추정 오차를 생각으로 오인하지 않게")
    void smallExcessIsNotThinking() {
        String hundred = "가".repeat(100);
        var small = ThinkingObservations.sampleOf(ThinkingWire.Sent.ON,
                response(hundred, null, "stop", new OpenAiApi.Usage(150, 10, 160)), 10);
        var underDouble = ThinkingObservations.sampleOf(ThinkingWire.Sent.ON,
                response("가".repeat(300), null, "stop", new OpenAiApi.Usage(550, 10, 560)), 10);

        assertThat(small.thinkingObserved()).as("초과 50").isFalse();
        assertThat(underDouble.thinkingObserved()).as("초과 250 이지만 답변 300 보다 작다").isFalse();
        assertThat(underDouble.thinkingTokens()).isNull();
    }

    @Test
    @DisplayName("잘림은 finish_reason=length 로 — 대소문자는 가리지 않는다(Spring AI 는 LENGTH 로 준다)")
    void truncation() {
        var s = ThinkingObservations.sampleOf(ThinkingWire.Sent.OFF,
                response("가".repeat(256), null, "LENGTH", new OpenAiApi.Usage(256, 10, 266)), 900);

        assertThat(s.truncated()).isTrue();
        assertThat(s.thinkingObserved()).as("출력이 전부 답변으로 설명된다").isFalse();
        assertThat(s.sent()).isEqualTo(ThinkingWire.Sent.OFF);
    }

    @Test
    @DisplayName("키마다 최근 50회만 남긴다 — 오래된 것부터 버린다")
    void keepsOnlyTheMostRecent() {
        ThinkingObservations o = new ThinkingObservations();
        for (int i = 0; i < ThinkingObservations.CAPACITY + 7; i++) {
            o.record(ThinkingSite.EVAL, "local", ThinkingLevel.LOW,
                    new ThinkingObservations.Sample(ThinkingWire.Sent.ON, i, null, false, false, false, i));
        }
        o.record(ThinkingSite.EVAL, "local", ThinkingLevel.HIGH,
                new ThinkingObservations.Sample(ThinkingWire.Sent.ON, 1, null, false, false, false, 1));

        List<ThinkingObservations.Sample> low = o.samples(ThinkingSite.EVAL, "local", ThinkingLevel.LOW);
        assertThat(low).hasSize(ThinkingObservations.CAPACITY);
        assertThat(low.get(0).latencyMs()).isEqualTo(7);
        assertThat(o.samples(ThinkingSite.EVAL, "local", ThinkingLevel.HIGH)).hasSize(1);
        assertThat(o.samples(ThinkingSite.EVAL, "local", ThinkingLevel.OFF)).isEmpty();
        assertThat(o.snapshot()).hasSize(2);
    }
}
