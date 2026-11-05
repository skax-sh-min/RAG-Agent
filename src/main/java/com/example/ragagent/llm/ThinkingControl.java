package com.example.ragagent.llm;

import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.HashMap;
import java.util.Map;

/**
 * 호출부가 쓰는 쪽 — "이 호출은 이 사이트다"라고 옵션에 표시한다. 실제로 무엇을 실을지는 받는 프로바이더가 정해진 뒤
 * {@link ThinkingControlChatModel} 이 정한다.
 *
 * <p><b>표시는 {@code extraBody} 의 내부 키다.</b> {@code OpenAiChatOptions} 에서 호출부가 값을 실어 데코레이터까지
 * 나를 수 있는 자리가 거기뿐이다. 그 대신 이 키는 <b>서버로 절대 나가면 안 된다</b> — 체인 맨 바깥의
 * {@link ThinkingControlChatModel} 이 표시가 있는 요청이면 언제나 걷어낸다({@code ThinkingControlWireTest} 가
 * 실제 본문으로 고정). 그래서 체인을 거치지 않는 경로(채팅 답변 스트리밍의 {@code rawModel} 직행)에는 이 표시를 쓰지
 * 않는다 — 그쪽은 표시 없이 프로바이더가 이미 정해진 채로 오므로, 변환 결과를 요청 객체에 바로 싣는다({@link #applyTo}).
 *
 * <p>호출부가 {@code chat_template_kwargs} 같은 필드를 {@code extraBody} 에 직접 넣으면 안 되는 이유도 여기서
 * 끝난다: 원격 프로바이더가 모르는 필드를 400 으로 거부하면 라우터가 그 프로바이더를 차단한다. 표시만 하고,
 * 실을지는 프로바이더가 정한다.
 */
public final class ThinkingControl {

    /** 표시가 사는 {@code extraBody} 키. 서버로 나가면 안 되는 이름이라 일부러 표준과 겹칠 수 없는 모양이다. */
    public static final String SITE_MARKER = "__rag_thinking_site";

    private ThinkingControl() {}

    /**
     * 이 옵션으로 나가는 호출이 어느 사이트인지 표시한다. 빌더의 {@code extraBody} 를 이것으로 정한다 — 이 앱의
     * 호출부는 {@code extraBody} 를 다른 데 쓰지 않는다.
     */
    public static OpenAiChatOptions.Builder mark(OpenAiChatOptions.Builder builder, ThinkingSite site) {
        Map<String, Object> body = new HashMap<>();
        body.put(SITE_MARKER, site.name());
        return builder.extraBody(body);
    }

    /**
     * 이미 만들어진 프롬프트에 표시한 사본 — 옵션을 직접 넘길 수 없는 프레임워크 호출부({@link ThinkingSiteChatModel})를
     * 위한 것이다. 이미 표시가 있으면 그대로 둔다(호출부가 정한 사이트가 이긴다). 원본은 건드리지 않는다.
     *
     * <p>옵션이 {@code OpenAiChatOptions} 가 아니면(Spring AI {@code ChatClient} 는 모델의 기본 옵션을 복사하는데, 이
     * 앱의 체인은 일반 {@code ChatOptions} 를 돌려준다) {@code OpenAiChatModel} 이 내부에서 하는 것과 같은 변환으로
     * 옮긴다 — 그 뒤 과정에서 일어날 일을 앞당길 뿐이라 잃는 값이 없다.
     */
    public static Prompt mark(Prompt prompt, ThinkingSite site) {
        if (siteOf(prompt) != null) return prompt;
        ChatOptions source = prompt.getOptions();
        OpenAiChatOptions.Builder builder = source instanceof OpenAiChatOptions openAi ? openAi.mutate()
                : source == null ? OpenAiChatOptions.builder()
                : fromGeneric(source);
        Map<String, Object> existing = source instanceof OpenAiChatOptions openAi ? openAi.getExtraBody() : null;
        Map<String, Object> body = existing == null ? new HashMap<>() : new HashMap<>(existing);
        body.put(SITE_MARKER, site.name());
        OpenAiChatOptions options = builder.extraBody(body).build();
        return new Prompt(prompt.getInstructions(), options);
    }

    /**
     * 일반 {@code ChatOptions} 의 공통 필드만 {@code OpenAiChatOptions} 로 옮긴다. 2.0 의 옵션은 불변이라(세터 없음)
     * {@code ModelOptionsUtils.copyToTarget} 의 JSON 왕복 변환을 쓸 수 없다.
     * 이 앱이 일반 옵션으로 만나는 필드는 아래뿐이다(모델·온도·출력 상한·샘플링·정지열).
     */
    private static OpenAiChatOptions.Builder fromGeneric(ChatOptions source) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder();
        if (source.getModel() != null) builder.model(source.getModel());
        if (source.getTemperature() != null) builder.temperature(source.getTemperature());
        if (source.getMaxTokens() != null) builder.maxTokens(source.getMaxTokens());
        if (source.getTopP() != null) builder.topP(source.getTopP());
        if (source.getFrequencyPenalty() != null) builder.frequencyPenalty(source.getFrequencyPenalty());
        if (source.getPresencePenalty() != null) builder.presencePenalty(source.getPresencePenalty());
        if (source.getStopSequences() != null) builder.stop(source.getStopSequences());
        return builder;
    }

    /** 표시된 사이트. 표시가 없거나 알아볼 수 없으면 {@code null} — 그 요청은 아무것도 바꾸지 않고 통과한다. */
    public static ThinkingSite siteOf(Prompt prompt) {
        if (prompt == null || !(prompt.getOptions() instanceof OpenAiChatOptions options)) return null;
        Map<String, Object> body = options.getExtraBody();
        if (body == null || !(body.get(SITE_MARKER) instanceof String name)) return null;
        try {
            return ThinkingSite.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * {@code wire} 를 실은 옵션 사본 — 표시({@link #SITE_MARKER})는 걷어내고, 다른 {@code extraBody} 항목은 둔다. 표준 필드는
     * {@code reasoningEffort} 로 싣는다. 블로킹 체인({@link ThinkingControlChatModel})과 체인을 지나지 않는 채팅 답변
     * 스트리밍({@code AnswerStreamer})이 <b>같은 이 함수</b>를 쓴다 — 두 경로의 변환이 갈라지지 않게. 원본은 건드리지 않는다
     * (옵션이 불변이다).
     *
     * <p>2.0 의 옵션은 {@code mutate()} 로 모든 값을 이어받는 빌더를 주므로 필드를 손으로 복사하지 않는다 — 필드가 늘어도
     * 떨어뜨릴 일이 없다.
     */
    public static OpenAiChatOptions applyTo(OpenAiChatOptions options, ThinkingWire wire) {
        Map<String, Object> body = options.getExtraBody() == null ? new HashMap<>() : new HashMap<>(options.getExtraBody());
        body.remove(SITE_MARKER);
        if (wire != null) body.putAll(wire.extraBody());
        OpenAiChatOptions.Builder builder = options.mutate().extraBody(body.isEmpty() ? null : body);
        if (wire != null && wire.reasoningEffort() != null) builder.reasoningEffort(wire.reasoningEffort());
        return builder.build();
    }

    /**
     * 서버가 {@code wire} 로 실은 필드를 거부한 실패인가 — 그렇다면 그 필드의 본문 이름, 아니면 {@code null}.
     *
     * <p>판정은 서버가 한 말({@link LlmErrorText} — 메시지와 응답 본문)에 그 이름이 나오는가다. 블로킹·스트리밍 두 경로가
     * 이 함수 하나를 쓴다 — 메시지에는 서버가 쓴 문장이 다 실리지 않을 수 있어, 메시지만 보면 거부를 놓칠 수 있다.
     * 부분 문자열이라, 다른 오류가 우연히 요청 본문을 되읊으며 그 이름을 담으면 그 프로바이더에서 생각
     * 제어가 꺼질 뿐이다(그 경우에도 요청은 다시 나간다).
     */
    public static String rejectedField(Throwable e, ThinkingWire wire) {
        if (e == null || wire == null || wire.fields().isEmpty()) return null;
        String said = LlmErrorText.of(e);
        for (String field : wire.fields()) {
            if (said.contains(field)) return field;
        }
        return null;
    }
}
