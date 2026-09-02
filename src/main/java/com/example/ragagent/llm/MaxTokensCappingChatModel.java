package com.example.ragagent.llm;

import java.util.function.IntSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

/**
 * Holds a per-provider {@code max-tokens} ceiling that a caller's per-call options cannot exceed.
 *
 * <p><b>이 데코레이터가 없으면 프로바이더별 설정이 채팅 경로에서 아무 일도 하지 않는다.</b>
 * {@code LlmConfig} 가 프로바이더 빈의 {@code defaultOptions} 에 값을 구워 넣지만, 그 기본값은
 * <b>호출자가 옵션을 안 줬을 때만</b> 쓰인다 — 그런데 {@code AnswerService.answerOptions()}/
 * {@code evalOptions()} 와 {@code DirectAnswerService} 는 <b>매 블로킹 호출마다</b>
 * {@code maxTokens} 를 실어 보내고(응답 모드별 예산, §6.24), 그 값은 전역
 * {@code app.llm.max-tokens} 에서 파생된다. 그래서 구워 넣은 프로바이더별 값은 곧바로 덮어써진다.
 *
 * <p>호출부를 고쳐 프로바이더를 알게 하는 방법은 쓰지 않았다 — 어느 프로바이더가 이 요청을 받을지는
 * {@code LlmRouter} 가 <b>나중에</b> 정하고(역할·우선순위·차단 상태·최소 부하 분산), 호출부가 미리
 * 물어보면 그 사이에 답이 달라질 수 있는 경쟁 상태가 된다. 반대로 이 데코레이터는 프로바이더가
 * 이미 정해진 뒤에 돌기 때문에 항상 맞는 값을 본다. {@code TrackingChatModel} ·
 * {@code ConcurrencyLimitingChatModel} 과 같은 자리, 같은 관례다.
 *
 * <p><b>내리기만 하고 올리지 않는다.</b> 검증 호출이 스스로 2,048 로 조인 것처럼 호출자가 더 작은
 * 값을 골랐다면 그 의도가 이긴다. 그리고 <b>없던 상한을 새로 만들지도 않는다</b> — 옵션에
 * {@code maxTokens} 가 없으면 그대로 통과시킨다. 그래야 프로바이더 빈의 {@code defaultOptions} 가
 * 예전처럼 최종 폴백으로 남고, "상한 없음"을 의도한 호출이 조용히 잘리지 않는다.
 *
 * <p>스트리밍 답변은 애초에 여기를 지나가지 않는다 — 그 경로는 {@code OpenAiChatModel} 의 버퍼링을
 * 피하려고 {@code OpenAiApi.chatCompletionStream()} 를 직접 쓴다. 상한이 블로킹 호출에만 걸린다는
 * 기존 설계와 그대로 맞아떨어진다.
 */
public class MaxTokensCappingChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(MaxTokensCappingChatModel.class);

    private final ChatModel delegate;
    private final String providerName;
    private final IntSupplier ceiling;

    /**
     * @param ceiling 이 프로바이더의 현재 상한을 <b>호출할 때마다</b> 계산하는 공급자. 값이 아니라
     *                공급자인 이유는 {@code app.llm.max-tokens} 가 핫 편집 대상이고(§6.26 A6), 창
     *                재탐지(§6.26 A5)로 상한의 근거인 컨텍스트 창 자체도 런타임에 바뀌기 때문이다 —
     *                생성자에서 한 번 받으면 그 둘 다 재기동 전까지 반영되지 않는다.
     *                {@code 0} 이하를 돌려주면 상한 없음(통과)이다.
     */
    public MaxTokensCappingChatModel(ChatModel delegate, String providerName, IntSupplier ceiling) {
        this.delegate = delegate;
        this.providerName = providerName;
        this.ceiling = ceiling;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return delegate.call(capped(prompt));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(capped(prompt));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    private Prompt capped(Prompt prompt) {
        if (prompt.getOptions() == null) return prompt;
        int ceilingNow = ceiling.getAsInt();   // 매 호출 재조회 — 핫 편집과 창 재탐지가 여기로 들어온다
        if (ceilingNow <= 0) return prompt;
        Integer requested = prompt.getOptions().getMaxTokens();
        if (requested == null || requested <= ceilingNow) return prompt;

        // ChatOptions 는 읽기 전용 인터페이스라 값을 실어 바꿀 수 있는 것은 구현체뿐이다. 이 앱의
        // 호출부는 전부 OpenAiChatOptions 를 만들지만, 아닌 것이 오면 상한을 포기하고 통과시킨다 —
        // 상한을 못 걸었다는 이유로 멀쩡한 요청을 막을 일은 아니다(로그로만 남긴다).
        if (!(prompt.getOptions() instanceof OpenAiChatOptions openAi)) {
            log.debug("[MAX_TOKENS] provider=[{}] {} 옵션이라 상한 {}을 적용하지 못했다",
                    providerName, prompt.getOptions().getClass().getSimpleName(), ceilingNow);
            return prompt;
        }
        // copy() 로 옵션만 갈아 끼운다 — 원본 Prompt 를 건드리면 호출부가 재사용할 때 값이 새어 나간다.
        OpenAiChatOptions lowered = openAi.copy();
        lowered.setMaxTokens(ceilingNow);
        log.debug("[MAX_TOKENS] provider=[{}] {} → {} (프로바이더별 상한)", providerName, requested, ceilingNow);
        return new Prompt(prompt.getInstructions(), lowered);
    }
}
