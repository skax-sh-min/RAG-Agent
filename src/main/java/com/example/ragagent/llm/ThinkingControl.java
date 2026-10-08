package com.example.ragagent.llm;

import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

import java.util.HashMap;
import java.util.Map;

/**
 * 호출부가 쓰는 쪽 — "이 호출은 이 사이트다"라고 옵션에 표시한다. 실제로 무엇을 실을지는 받는 프로바이더가 정해진 뒤
 * {@link ThinkingControlChatModel} 이 정한다.
 *
 * <p><b>표시는 {@code extraBody} 의 내부 키다.</b> {@code OpenAiChatOptions} 에서 호출부가 값을 실어 데코레이터까지
 * 나를 수 있는 자리가 거기뿐이다. 그 대신 이 키는 <b>서버로 절대 나가면 안 된다</b> — 체인 맨 바깥의
 * {@link ThinkingControlChatModel} 이 표시가 있는 요청이면 언제나 걷어낸다({@code ThinkingControlWireTest} 가
 * 실제 본문으로 고정). 그래서 체인을 거치지 않는 경로(채팅 답변 스트리밍의 {@code OpenAiApi} 직행)에는 이 표시를 쓰지
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
        OpenAiChatOptions options = source instanceof OpenAiChatOptions openAi ? openAi.copy()
                : source == null ? OpenAiChatOptions.builder().build()
                : ModelOptionsUtils.copyToTarget(source, ChatOptions.class, OpenAiChatOptions.class);
        Map<String, Object> body = options.getExtraBody() == null ? new HashMap<>() : new HashMap<>(options.getExtraBody());
        body.put(SITE_MARKER, site.name());
        options.setExtraBody(body);
        return new Prompt(prompt.getInstructions(), options);
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
     * 체인을 지나지 않는 스트리밍 요청에 {@code wire} 를 실은 사본 — 블로킹의 {@link ThinkingControlChatModel#apply} 와
     * 같은 규칙이다: {@code extraBody} 에 더하고(다른 항목은 둔다), 표준 필드는 {@code reasoningEffort} 로. 실을 것이
     * 없으면 원본을 그대로 돌려준다.
     *
     * <p><b>32개 컴포넌트를 손으로 복사하는 곳은 여기 하나다.</b> 이 레코드에는 wither 가 없다({@code streamOptions} 하나뿐).
     * Spring AI 업그레이드로 컴포넌트가 늘면 정식 생성자 시그니처가 바뀌어 여기가 컴파일되지 않고, 같은 타입끼리 자리만
     * 바뀌면 {@code ThinkingControlTest} 가 컴포넌트 수와 값 보존으로 잡는다 — 조용히 한 필드를 떨어뜨리는 복사가 되지 않게.
     */
    public static OpenAiApi.ChatCompletionRequest applyTo(OpenAiApi.ChatCompletionRequest request, ThinkingWire wire) {
        if (request == null || wire == null || wire.fields().isEmpty()) return request;
        // 정식 생성자는 null 을 빈 HashMap 으로 바꿔 두므로 extraBody() 는 null 이 아니다 — 그래도 원본 맵은 건드리지 않는다.
        Map<String, Object> body = request.extraBody() == null ? new HashMap<>() : new HashMap<>(request.extraBody());
        body.remove(SITE_MARKER);
        body.putAll(wire.extraBody());
        String effort = wire.reasoningEffort() != null ? wire.reasoningEffort() : request.reasoningEffort();
        return new OpenAiApi.ChatCompletionRequest(
                request.messages(), request.model(), request.store(), request.metadata(),
                request.frequencyPenalty(), request.logitBias(), request.logprobs(), request.topLogprobs(),
                request.maxTokens(), request.maxCompletionTokens(), request.n(), request.outputModalities(),
                request.audioParameters(), request.presencePenalty(), request.responseFormat(), request.seed(),
                request.serviceTier(), request.stop(), request.stream(), request.streamOptions(),
                request.temperature(), request.topP(), request.tools(), request.toolChoice(),
                request.parallelToolCalls(), request.user(), effort, request.webSearchOptions(),
                request.verbosity(), request.promptCacheKey(), request.safetyIdentifier(), body);
    }

    /**
     * 서버가 {@code wire} 로 실은 필드를 거부한 실패인가 — 그렇다면 그 필드의 본문 이름, 아니면 {@code null}.
     *
     * <p>판정은 서버가 한 말({@link LlmErrorText} — 메시지와 응답 본문)에 그 이름이 나오는가다. 블로킹·스트리밍 두 경로가
     * 이 함수 하나를 쓴다 — 스트리밍의 WebClient 오류는 메시지에 본문이 없어서, 메시지만 보면 그 경로의 거부는 영영
     * 알아보지 못한다. 부분 문자열이라, 다른 오류가 우연히 요청 본문을 되읊으며 그 이름을 담으면 그 프로바이더에서 생각
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
