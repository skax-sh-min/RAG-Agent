package com.example.ragagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 체인을 지나지 않는 스트리밍 요청에 생각 필드를 싣는 변환({@link ThinkingControl#applyTo})과, 두 경로가 공유하는 거부
 * 판정({@link ThinkingControl#rejectedField}).
 *
 * <p>{@code applyTo} 는 wither 가 없는 32개 컴포넌트 레코드를 손으로 복사한다. 그 복사가 Spring AI 업그레이드에서 조용히
 * 한 필드를 떨어뜨리지 않게 컴포넌트 수와 <b>모든 컴포넌트의 값 보존</b>을 반사로 고정한다.
 */
class ThinkingControlTest {

    private static final String KWARGS = ThinkingDialect.TEMPLATE_KWARGS_FIELD;

    private static ThinkingWire templateOn() {
        return ThinkingDialect.TEMPLATE_KWARGS.wire(ThinkingLevel.LOW);
    }

    private static OpenAiApi.ChatCompletionRequest simpleRequest() {
        return new OpenAiApi.ChatCompletionRequest(List.of(
                new OpenAiApi.ChatCompletionMessage("시스템", OpenAiApi.ChatCompletionMessage.Role.SYSTEM),
                new OpenAiApi.ChatCompletionMessage("질문", OpenAiApi.ChatCompletionMessage.Role.USER)),
                "gemma", 0.7, true);
    }

    // ── applyTo ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("컴포넌트는 32개다 — 늘면 applyTo 의 손 복사를 다시 볼 것(§11.1 Spring AI 업그레이드)")
    void theRequestHasThirtyTwoComponents() {
        assertThat(OpenAiApi.ChatCompletionRequest.class.getRecordComponents())
                .as("applyTo 가 정식 생성자로 전부 복사한다 — 늘어난 컴포넌트를 거기에 더할 것")
                .hasSize(32);
    }

    @Test
    @DisplayName("모든 컴포넌트를 그대로 옮긴다 — 생각 필드를 얹는 자리(extraBody·reasoningEffort)만 바뀐다")
    void everyOtherComponentIsCarriedOver() throws Exception {
        OpenAiApi.ChatCompletionRequest original = everyComponentSet();

        OpenAiApi.ChatCompletionRequest applied = ThinkingControl.applyTo(original, templateOn());

        for (RecordComponent c : OpenAiApi.ChatCompletionRequest.class.getRecordComponents()) {
            if (c.getName().equals("extraBody")) continue;
            assertThat(c.getAccessor().invoke(applied)).as(c.getName())
                    .isSameAs(c.getAccessor().invoke(original));
        }
        assertThat(applied.extraBody())
                .containsEntry("top_k", 40)
                .containsEntry(KWARGS, Map.of("enable_thinking", true));
        assertThat(original.extraBody()).as("원본은 건드리지 않는다").doesNotContainKey(KWARGS);
    }

    @Test
    @DisplayName("표준 필드 dialect — reasoningEffort 를 덮고 extraBody 는 그대로 둔다")
    void openAiEffortSetsTheStandardField() {
        OpenAiApi.ChatCompletionRequest applied = ThinkingControl.applyTo(simpleRequest(),
                ThinkingDialect.OPENAI_EFFORT.wire(ThinkingLevel.HIGH));

        assertThat(applied.reasoningEffort()).isEqualTo("high");
        assertThat(applied.extraBody()).isEmpty();
        assertThat(applied.temperature()).isEqualTo(0.7);
        assertThat(applied.stream()).isTrue();
    }

    @Test
    @DisplayName("실을 것이 없으면(생각 제어 안 함·거부됨) 원본을 그대로 돌려준다")
    void nothingToSendReturnsTheSameRequest() {
        OpenAiApi.ChatCompletionRequest request = simpleRequest();

        assertThat(ThinkingControl.applyTo(request, ThinkingWire.NOTHING)).isSameAs(request);
        assertThat(ThinkingControl.applyTo(request, templateOn().without(java.util.Set.of(KWARGS)))).isSameAs(request);
    }

    @Test
    @DisplayName("직렬화 — 생각 필드는 본문 최상위로 나가고, extra_body 로 중첩되지 않는다")
    void serializesAtTheTopLevel() throws Exception {
        OpenAiApi.ChatCompletionRequest applied = ThinkingControl.applyTo(simpleRequest(),
                ThinkingDialect.TEMPLATE_KWARGS.wire(ThinkingLevel.OFF));

        JsonNode body = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(applied));

        assertThat(body.path(KWARGS).path("enable_thinking").isBoolean()).isTrue();
        assertThat(body.path(KWARGS).path("enable_thinking").asBoolean()).isFalse();
        assertThat(body.has("extra_body")).isFalse();
        assertThat(body.has("extraBody")).isFalse();
        assertThat(body.toString()).doesNotContain(ThinkingControl.SITE_MARKER);
        assertThat(body.path("stream").asBoolean()).isTrue();
    }

    /**
     * 컴포넌트마다 서로 다른 인스턴스를 넣은 요청 — 복사가 자리를 바꾸거나 떨어뜨리면 {@code isSameAs} 가 잡는다.
     * 레코드 타입 컴포넌트도 실제 인스턴스로 채운다(null 로 두면 떨어뜨려도 같아 보인다).
     */
    private static OpenAiApi.ChatCompletionRequest everyComponentSet() throws Exception {
        RecordComponent[] components = OpenAiApi.ChatCompletionRequest.class.getRecordComponents();
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            args[i] = components[i].getName().equals("extraBody")
                    ? new HashMap<>(Map.of("top_k", 40))
                    : sample(components[i].getType(), i, 0);
        }
        Constructor<OpenAiApi.ChatCompletionRequest> canonical = OpenAiApi.ChatCompletionRequest.class
                .getDeclaredConstructor(Arrays.stream(components).map(RecordComponent::getType).toArray(Class[]::new));
        OpenAiApi.ChatCompletionRequest request = canonical.newInstance(args);
        for (RecordComponent c : components) {
            assertThat(c.getAccessor().invoke(request)).as("전제 — %s 가 채워졌다", c.getName()).isNotNull();
        }
        return request;
    }

    private static Object sample(Class<?> type, int i, int depth) throws Exception {
        if (type == String.class) return "v" + i;
        if (type == Boolean.class || type == boolean.class) return Boolean.TRUE;
        if (type == Double.class || type == double.class) return Double.valueOf(i + 0.5);
        if (type == Integer.class || type == int.class) return Integer.valueOf(1000 + i);
        if (type == List.class) return new ArrayList<>();
        if (type == Map.class) return new HashMap<>();
        if (type.isEnum()) return type.getEnumConstants()[0];
        if (type.isRecord() && depth < 3) {
            RecordComponent[] cs = type.getRecordComponents();
            Object[] args = new Object[cs.length];
            for (int j = 0; j < cs.length; j++) args[j] = sample(cs[j].getType(), j, depth + 1);
            Constructor<?> ctor = type.getDeclaredConstructor(
                    Arrays.stream(cs).map(RecordComponent::getType).toArray(Class[]::new));
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        }
        if (type != Object.class && !type.isInterface()) {
            return type.getDeclaredConstructor().newInstance();   // ResponseFormat 처럼 빈 생성자가 있는 클래스
        }
        return new Object();
    }

    // ── rejectedField ───────────────────────────────────────────────────────

    @Test
    @DisplayName("블로킹(RestClient) — 메시지에 실린 본문에서 필드 이름을 찾는다")
    void blockingRejectionIsReadFromTheMessage() {
        var e = new NonTransientAiException("HTTP 400 - {\"error\":\"Unrecognized key(s) in object: 'chat_template_kwargs'\"}");

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
        var effortRejected = new NonTransientAiException("HTTP 400 - unknown field reasoning_effort");

        assertThat(ThinkingControl.rejectedField(overflow, templateOn())).isNull();
        assertThat(ThinkingControl.rejectedField(effortRejected, templateOn()))
                .as("이 요청은 reasoning_effort 를 싣지 않았다").isNull();
        assertThat(ThinkingControl.rejectedField(new NonTransientAiException("HTTP 400 - chat_template_kwargs"),
                ThinkingWire.NOTHING)).isNull();
        assertThat(ThinkingControl.rejectedField(effortRejected, ThinkingDialect.OPENAI_EFFORT.wire(ThinkingLevel.LOW)))
                .isEqualTo(ThinkingWire.REASONING_EFFORT_FIELD);
    }
}
