package com.example.ragagent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 체인을 지나지 않는 스트리밍 요청과 체인 안의 블로킹 요청이 <b>같이 쓰는</b> 생각 필드 변환({@link ThinkingControl#applyTo}),
 * 사이트 표시({@link ThinkingControl#mark}), 그리고 두 경로가 공유하는 거부 판정({@link ThinkingControl#rejectedField}).
 *
 * <p>Spring AI 1.x 에서는 {@code applyTo} 가 32개 컴포넌트 레코드를 손으로 복사해서 "한 필드도 떨어뜨리지 않는다"를 반사로
 * 고정했다. 2.0 의 옵션은 {@code mutate()} 가 모든 값을 이어받는 빌더를 주므로 그 복사가 없다 — 대신 아래 테스트가 "다른 값이
 * 보존된다"를 대표 필드들로 고정한다. 와이어에 실제로 어떻게 나가는지는 {@code ThinkingControlWireTest} 가 본다.
 */
class ThinkingControlTest {

    private static final String KWARGS = ThinkingDialect.TEMPLATE_KWARGS_FIELD;

    private static ThinkingWire templateOn() {
        return ThinkingDialect.TEMPLATE_KWARGS.wire(ThinkingLevel.LOW);
    }

    private static OpenAiChatOptions simpleOptions() {
        return OpenAiChatOptions.builder().model("gemma").temperature(0.7).maxTokens(321).build();
    }

    // ── applyTo ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("생각 필드를 얹는 자리(extraBody·reasoningEffort)만 바뀌고 나머지 값은 그대로 옮긴다")
    void everyOtherValueIsCarriedOver() {
        Map<String, Object> existing = new HashMap<>(Map.of("top_k", 40, ThinkingControl.SITE_MARKER, "ANSWER_RAG_N"));
        OpenAiChatOptions original = OpenAiChatOptions.builder()
                .model("gemma").temperature(0.7).maxTokens(321).topP(0.9)
                .frequencyPenalty(0.1).presencePenalty(0.2).stop(List.of("END"))
                .extraBody(existing).build();

        OpenAiChatOptions applied = ThinkingControl.applyTo(original, templateOn());

        assertThat(applied.getModel()).isEqualTo("gemma");
        assertThat(applied.getTemperature()).isEqualTo(0.7);
        assertThat(applied.getMaxTokens()).isEqualTo(321);
        assertThat(applied.getTopP()).isEqualTo(0.9);
        assertThat(applied.getFrequencyPenalty()).isEqualTo(0.1);
        assertThat(applied.getPresencePenalty()).isEqualTo(0.2);
        assertThat(applied.getStopSequences()).containsExactly("END");
        assertThat(applied.getExtraBody())
                .containsEntry("top_k", 40)
                .containsEntry(KWARGS, Map.of("enable_thinking", true))
                .as("표시는 서버로 나가면 안 된다").doesNotContainKey(ThinkingControl.SITE_MARKER);
        assertThat(original.getExtraBody()).as("원본은 건드리지 않는다")
                .doesNotContainKey(KWARGS).containsKey(ThinkingControl.SITE_MARKER);
    }

    @Test
    @DisplayName("표준 필드 dialect — reasoningEffort 를 싣고 extraBody 에는 아무것도 더하지 않는다")
    void openAiEffortSetsTheStandardField() {
        OpenAiChatOptions applied = ThinkingControl.applyTo(simpleOptions(),
                ThinkingDialect.OPENAI_EFFORT.wire(ThinkingLevel.HIGH));

        assertThat(applied.getReasoningEffort()).isEqualTo("high");
        assertThat(applied.getExtraBody()).isNullOrEmpty();
        assertThat(applied.getTemperature()).isEqualTo(0.7);
    }

    @Test
    @DisplayName("실을 것이 없어도(생각 제어 안 함·거부됨) 표시는 걷어낸다")
    void nothingToSendStillStripsTheMarker() {
        OpenAiChatOptions marked = ThinkingControl.mark(OpenAiChatOptions.builder().temperature(0.3),
                ThinkingSite.ANSWER_RAG_N).build();

        OpenAiChatOptions plain = ThinkingControl.applyTo(marked, ThinkingWire.NOTHING);
        OpenAiChatOptions rejected = ThinkingControl.applyTo(marked, templateOn().without(java.util.Set.of(KWARGS)));

        assertThat(plain.getExtraBody()).isNullOrEmpty();
        assertThat(rejected.getExtraBody()).isNullOrEmpty();
        assertThat(plain.getTemperature()).isEqualTo(0.3);
    }

    // ── mark / siteOf ───────────────────────────────────────────────────────

    @Test
    @DisplayName("표시한 사이트를 프롬프트에서 되읽는다")
    void markedSiteIsReadBack() {
        Prompt prompt = new Prompt(List.of(new SystemMessage("s"), new UserMessage("q")),
                ThinkingControl.mark(OpenAiChatOptions.builder(), ThinkingSite.CONDENSE).build());

        assertThat(ThinkingControl.siteOf(prompt)).isEqualTo(ThinkingSite.CONDENSE);
        assertThat(ThinkingControl.siteOf(new Prompt("q"))).as("표시 없음").isNull();
    }

    @Test
    @DisplayName("일반 ChatOptions 프롬프트에도 표시하고, 공통 필드는 잃지 않는다")
    void markingAGenericOptionsPromptKeepsTheCommonFields() {
        ChatOptions generic = ChatOptions.builder().model("m").temperature(0.4).maxTokens(99).build();

        Prompt marked = ThinkingControl.mark(new Prompt("q", generic), ThinkingSite.CONDENSE);

        assertThat(ThinkingControl.siteOf(marked)).isEqualTo(ThinkingSite.CONDENSE);
        OpenAiChatOptions options = (OpenAiChatOptions) marked.getOptions();
        assertThat(options.getModel()).isEqualTo("m");
        assertThat(options.getTemperature()).isEqualTo(0.4);
        assertThat(options.getMaxTokens()).isEqualTo(99);
    }

    @Test
    @DisplayName("이미 표시된 프롬프트는 그대로 둔다(호출부가 정한 사이트가 이긴다)")
    void anAlreadyMarkedPromptIsLeftAlone() {
        Prompt marked = new Prompt("q", ThinkingControl.mark(OpenAiChatOptions.builder(), ThinkingSite.CONDENSE).build());

        assertThat(ThinkingControl.mark(marked, ThinkingSite.ANSWER_RAG_N)).isSameAs(marked);
    }

    // ── rejectedField ───────────────────────────────────────────────────────

    @Test
    @DisplayName("블로킹(RestClient) — 메시지에 실린 본문에서 필드 이름을 찾는다")
    void blockingRejectionIsReadFromTheMessage() {
        var e = new RuntimeException("HTTP 400 - {\"error\":\"Unrecognized key(s) in object: 'chat_template_kwargs'\"}");

        assertThat(ThinkingControl.rejectedField(e, templateOn())).isEqualTo(KWARGS);
    }

    @Test
    @DisplayName("스트리밍(WebClient) — 메시지에 없는 필드 이름을 응답 본문에서 찾는다")
    void streamingRejectionIsReadFromTheResponseBody() {
        var e = WebClientResponseException.create(400, "Bad Request", new HttpHeaders(),
                "{\"error\":{\"message\":\"Unrecognized request argument supplied: chat_template_kwargs\"}}"
                        .getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);

        assertThat(e.getMessage()).doesNotContain(KWARGS);
        assertThat(ThinkingControl.rejectedField(e, templateOn())).isEqualTo(KWARGS);
        assertThat(ThinkingControl.rejectedField(new RuntimeException("감싼 예외", e), templateOn())).isEqualTo(KWARGS);
    }

    @Test
    @DisplayName("실은 필드가 아닌 이름·무관한 오류·실은 것이 없는 요청은 거부가 아니다")
    void otherFailuresAreNotRejections() {
        var overflow = WebClientResponseException.create(400, "Bad Request", new HttpHeaders(),
                "{\"error\":{\"message\":\"request exceeds the available context size\"}}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        var effortRejected = new RuntimeException("HTTP 400 - unknown field reasoning_effort");

        assertThat(ThinkingControl.rejectedField(overflow, templateOn())).isNull();
        assertThat(ThinkingControl.rejectedField(effortRejected, templateOn()))
                .as("이 요청은 reasoning_effort 를 싣지 않았다").isNull();
        assertThat(ThinkingControl.rejectedField(new RuntimeException("HTTP 400 - chat_template_kwargs"),
                ThinkingWire.NOTHING)).isNull();
        assertThat(ThinkingControl.rejectedField(effortRejected, ThinkingDialect.OPENAI_EFFORT.wire(ThinkingLevel.LOW)))
                .isEqualTo(ThinkingWire.REASONING_EFFORT_FIELD);
    }
}
