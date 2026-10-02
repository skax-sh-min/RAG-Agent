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
import java.util.function.Function;

/**
 * 호출부가 표시한 사이트({@link ThinkingControl#mark})의 생각 수준을, 받는 프로바이더가 알아듣는 필드로 바꿔 싣는다
 * (PLAN §6.29). 체인의 <b>가장 바깥</b>에 둔다 — curl 로그({@code LoggingChatModel})가 실제로 나간 본문을 찍게 하고,
 * 표시 키가 어떤 경우에도 서버로 나가지 않게 하기 위해서다.
 *
 * <p>순서: 표시된 사이트 → 설정의 수준({@code props.llmSafe().thinkingLevel(site)}, <b>매 호출</b> 다시 읽는다) →
 * 이 프로바이더의 dialect 로 변환({@link ProviderThinkingDialects#wireFor}) → 표시를 걷어내고 실어 보낸다.
 * 어느 프로바이더가 받을지는 라우터가 나중에 정하므로 호출부는 의도만 표시하고, 실을 것은 프로바이더가 정해진 뒤
 * 여기서 정한다({@code MaxTokensCappingChatModel} 과 같은 자리, 같은 이유).
 *
 * <p><b>왜 필요한가.</b> 추론 모델은 한 줄짜리 답을 내기 전에도 수백 토큰을 먼저 생각한다. 실측(2026-10-01,
 * llama.cpp + gemma-4-E2B, 서버 기본값 thinking 켬): 짧은 후속 질문의 독립화는 출력 상한 256 을 생각에 다 쓰고
 * 매번 6~8초 뒤 빈 응답으로 끝났고, 답변 뒤 질문 다듬기는 400~711 토큰·10~17초였다. 같은 요청에 생각을 끄자
 * 0.7~1초·11~17토큰에 제대로 된 한 줄이 나왔다.
 *
 * <p><b>표시가 없는 요청은 손대지 않는다</b> — 아직 사이트를 표시하지 않은 호출부는 지금처럼 아무것도 싣지 않고
 * 나간다(서버 기본값). 표시가 있으면 수준이 무엇이든 표시 키는 걷어낸다.
 *
 * <p><b>서버가 필드를 거부하면</b>(오류 문구에 필드 이름이 나온다) 그 프로바이더는 그 필드를 받지 않는다고 기억하고
 * <b>그 필드 없이 한 번 다시 보낸다</b>. 실패가 라우터까지 올라가지 않으므로 차단도 연속 실패 계수도 없다. 판정이
 * 문구의 부분 문자열이라, 다른 오류가 우연히 요청 본문을 되읊으며 그 이름을 담으면 그 프로바이더에서 생각 제어가
 * 꺼질 뿐이다(그 경우에도 요청은 다시 나간다). LM Studio 의 OpenAI 호환 경로처럼 모르는 필드를 오류 없이 넘기는
 * 서버에서는 효과 없이 모델이 계속 생각할 수 있다 — 그래서 관측({@link ThinkingObservations})이 "끔으로 보냈는데
 * 생각했다"를 남긴다.
 */
public class ThinkingControlChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(ThinkingControlChatModel.class);

    private final ChatModel delegate;
    private final String providerName;
    private final ProviderThinkingDialects dialects;
    private final Function<ThinkingSite, ThinkingLevel> levels;
    private final ThinkingObservations observations;

    /**
     * @param levels 사이트의 지금 수준 — 호출마다 부른다. 값이 아니라 함수인 이유는 수준이 핫 편집 대상이기 때문이다
     *               (5단계 {@code /settings}). 생성자에서 한 번 읽어 두면 재기동 전까지 반영되지 않는다.
     */
    public ThinkingControlChatModel(ChatModel delegate, String providerName, ProviderThinkingDialects dialects,
                                    Function<ThinkingSite, ThinkingLevel> levels, ThinkingObservations observations) {
        this.delegate = delegate;
        this.providerName = providerName;
        this.dialects = dialects;
        this.levels = levels;
        this.observations = observations;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ThinkingSite site = ThinkingControl.siteOf(prompt);
        if (site == null) return delegate.call(prompt);
        ThinkingLevel level = levels.apply(site);
        ThinkingWire wire = dialects.wireFor(providerName, level);
        try {
            return callAndObserve(prompt, site, level, wire);
        } catch (RuntimeException e) {
            String field = rejectedField(e, wire);
            if (field == null) throw e;
            if (dialects.markRejected(providerName, field)) {
                log.warn("[THINKING] provider=[{}] 가 {} 를 거부했다 — 이 프로세스 동안은 싣지 않는다"
                        + "(이 서버에서는 생각 수준을 정하지 못한다): {}", providerName, field, e.getMessage());
            }
            return callAndObserve(prompt, site, level, dialects.wireFor(providerName, level));
        }
    }

    private ChatResponse callAndObserve(Prompt prompt, ThinkingSite site, ThinkingLevel level, ThinkingWire wire) {
        log.debug("[THINKING] site={} level={} provider={} sent={}",
                site.id(), level.value(), providerName, wire.describe());
        long started = System.nanoTime();
        ChatResponse response = delegate.call(apply(prompt, wire));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        observations.record(site, providerName, level, ThinkingObservations.sampleOf(wire.sent(), response, elapsedMs));
        return response;
    }

    /**
     * 스트리밍은 이 체인을 거의 지나지 않는다(채팅 답변은 {@code OpenAiApi} 직행 — 3단계에서 따로 싣는다). 와도 같은
     * 규칙으로 싣되 거부 뒤 재시도는 없다 — 오류가 구독 시점에 나기 때문이다. 관측도 3단계다.
     */
    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        ThinkingSite site = ThinkingControl.siteOf(prompt);
        if (site == null) return delegate.stream(prompt);
        return delegate.stream(apply(prompt, dialects.wireFor(providerName, levels.apply(site))));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    /**
     * 표시를 걷어내고 {@code wire} 를 실은 사본 — 원본 {@code Prompt} 는 건드리지 않는다(호출부가 재사용할 수 있다).
     * 다른 {@code extraBody} 항목은 그대로 둔다.
     */
    static Prompt apply(Prompt prompt, ThinkingWire wire) {
        OpenAiChatOptions copy = ((OpenAiChatOptions) prompt.getOptions()).copy();
        Map<String, Object> body = copy.getExtraBody() == null ? new HashMap<>() : new HashMap<>(copy.getExtraBody());
        body.remove(ThinkingControl.SITE_MARKER);
        body.putAll(wire.extraBody());
        copy.setExtraBody(body.isEmpty() ? null : body);
        if (wire.reasoningEffort() != null) copy.setReasoningEffort(wire.reasoningEffort());
        return new Prompt(prompt.getInstructions(), copy);
    }

    /** 실은 필드 중 오류 문구에 이름이 나온 것 — 원인 사슬 전체를 본다. 없으면 {@code null}(다른 실패다). */
    private static String rejectedField(Throwable e, ThinkingWire wire) {
        for (String field : wire.fields()) {
            for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
                if (t.getMessage() != null && t.getMessage().contains(field)) return field;
            }
        }
        return null;
    }
}
