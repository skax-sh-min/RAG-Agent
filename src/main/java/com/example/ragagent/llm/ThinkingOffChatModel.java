package com.example.ragagent.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 호출부가 "생각(reasoning)하지 말고 바로 답하라"고 표시한 요청({@link #requestOff})을, 받는 프로바이더가 그 표시를
 * 받을 수 있을 때만 실어 보낸다.
 *
 * <p><b>왜 필요한가.</b> 추론 모델은 한 줄짜리 답을 내기 전에도 수백 토큰을 먼저 생각한다. 실측(2026-10-01,
 * llama.cpp + gemma-4-E2B, 서버 기본값 thinking 켬): 짧은 후속 질문의 독립화는 출력 상한 256 을 생각에 다 쓰고
 * 매번 6~8초 뒤 빈 응답으로 끝났고 — 대화형 경로에서 아무것도 얻지 못한 채 검색만 그만큼 늦췄다 — 답변 뒤 질문
 * 다듬기는 400~711 토큰·10~17초였다. 같은 요청에 생각을 끄자 0.7~1초·11~17토큰에 제대로 된 한 줄이 나왔다.
 *
 * <p><b>표시는 요청 본문의 {@code chat_template_kwargs: {"enable_thinking": false}} 다.</b> llama.cpp
 * {@code llama-server}·vLLM·SGLang 이 채팅 템플릿에 그대로 넘기는 확장이고, 템플릿이 그 변수를 보는 모델
 * (gemma-4·Qwen3 계열)에서 생각을 끈다. OpenAI 표준 필드가 아니어서 <b>아무에게나 보낼 수는 없다</b> — OpenAI·
 * Gemini 의 호환 엔드포인트는 모르는 필드를 400 으로 거부하고, 라우터는 4xx 를 프로바이더 실패로 보고 차단한다
 * (폴백이 있으면 30초, 없으면 5초). 백그라운드 호출 하나가 그동안 채팅까지 막는다. 그래서:
 * <ul>
 *   <li><b>LOCAL 역할 프로바이더에만 싣는다</b>(로컬 LLM 서버). 나머지에는 표시를 걷어내고 보낸다 — 그 모델은
 *       자기 기본값대로 생각하고, 호출부의 출력 상한이 그 경우의 안전판이다.</li>
 *   <li>LOCAL 서버가 그래도 이 필드를 거부하면(오류 문구에 필드 이름이 나온다) 그 프로바이더는 받지 않는다고 기억하고
 *       <b>표시 없이 한 번 다시 보낸다</b>. 실패가 라우터까지 올라가지 않으므로 차단도 없다. LM Studio 의 OpenAI 호환
 *       경로는 이런 스위치를 문서화하지 않는다 — 거부하지 않고 넘기면 효과 없이 모델이 계속 생각할 수 있다.</li>
 * </ul>
 *
 * <p>{@code MaxTokensCappingChatModel} 과 같은 자리·같은 이유의 데코레이터다: 어느 프로바이더가 요청을 받을지는
 * 라우터가 <b>나중에</b> 정하므로, 호출부는 의도만 표시하고 실을지는 프로바이더가 정해진 뒤 여기서 정한다. 체인의
 * <b>가장 바깥</b>에 두어 curl 로그({@code LoggingChatModel})가 실제로 나간 본문을 찍게 한다.
 */
public class ThinkingOffChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(ThinkingOffChatModel.class);

    /** 요청 본문 필드 이름 — 서버가 거부할 때 오류 문구에서도 이 이름을 찾는다. */
    public static final String TEMPLATE_KWARGS = "chat_template_kwargs";

    private final ChatModel delegate;
    private final String providerName;
    private final boolean localServer;
    /** 이 프로바이더가 필드를 거부한 적이 있는가 — 프로세스가 사는 동안 기억한다(서버를 바꾸면 앱도 다시 뜬다). */
    private final AtomicBoolean rejected = new AtomicBoolean(false);

    /**
     * @param localServer LOCAL 역할인가 — 로컬 LLM 서버만 이 표시를 받는다(클래스 주석)
     */
    public ThinkingOffChatModel(ChatModel delegate, String providerName, boolean localServer) {
        this.delegate = delegate;
        this.providerName = providerName;
        this.localServer = localServer;
    }

    /**
     * 호출부가 쓰는 표시 — 이 옵션으로 나가는 호출에서는 모델이 생각하지 않고 바로 답하게 한다. 실제로 실릴지는 받는
     * 프로바이더가 정한다(클래스 주석). 빌더의 {@code extraBody} 를 이것으로 정한다.
     */
    public static OpenAiChatOptions.Builder requestOff(OpenAiChatOptions.Builder builder) {
        Map<String, Object> body = new HashMap<>();
        body.put(TEMPLATE_KWARGS, Map.of("enable_thinking", false));
        return builder.extraBody(body);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        if (!asksThinkingOff(prompt)) return delegate.call(prompt);
        if (!localServer || rejected.get()) return delegate.call(withoutSwitch(prompt));
        try {
            return delegate.call(prompt);
        } catch (RuntimeException e) {
            if (!mentionsSwitch(e)) throw e;
            if (rejected.compareAndSet(false, true)) {
                log.warn("[THINKING] provider=[{}] 가 {} 를 거부했다 — 이 프로세스 동안은 싣지 않는다"
                        + "(이 서버에서는 생각을 끄지 못한다): {}", providerName, TEMPLATE_KWARGS, e.getMessage());
            }
            return delegate.call(withoutSwitch(prompt));
        }
    }

    /** 스트리밍 호출은 이 표시를 쓰지 않지만, 오면 같은 규칙으로 거른다(거부 뒤 재시도는 없다 — 오류가 구독 시점에 난다). */
    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        if (asksThinkingOff(prompt) && (!localServer || rejected.get())) {
            return delegate.stream(withoutSwitch(prompt));
        }
        return delegate.stream(prompt);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    static boolean asksThinkingOff(Prompt prompt) {
        return prompt.getOptions() instanceof OpenAiChatOptions options
                && options.getExtraBody() != null
                && options.getExtraBody().containsKey(TEMPLATE_KWARGS);
    }

    /** 표시만 걷어낸 사본 — 원본 {@code Prompt} 는 건드리지 않는다(호출부가 재사용할 수 있다). */
    private static Prompt withoutSwitch(Prompt prompt) {
        OpenAiChatOptions copy = ((OpenAiChatOptions) prompt.getOptions()).copy();
        Map<String, Object> rest = new HashMap<>(copy.getExtraBody());
        rest.remove(TEMPLATE_KWARGS);
        copy.setExtraBody(rest.isEmpty() ? null : rest);
        return new Prompt(prompt.getInstructions(), copy);
    }

    private static boolean mentionsSwitch(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(TEMPLATE_KWARGS)) return true;
        }
        return false;
    }
}
