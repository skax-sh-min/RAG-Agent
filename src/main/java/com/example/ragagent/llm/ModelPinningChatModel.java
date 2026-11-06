package com.example.ragagent.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

/**
 * 이 프로바이더의 모델 id 를 데코레이터 체인을 지나는 <b>모든 호출</b>의 요청 본문에 싣는다.
 *
 * <p><b>이 데코레이터가 없으면 블로킹 호출의 {@code model} 이 설정값이 아니라 {@code gpt-5-mini} 로 나간다.</b>
 * Spring AI 2.0 의 {@code OpenAiChatModel} 은 호출자가 옵션을 주면 기본 옵션을 <b>병합하지 않고</b> 그 옵션만 쓰는데
 * (바이트코드와 와이어 테스트로 확인), 모델을 지정하지 않고 빌더로 만든 {@code OpenAiChatOptions} 의 {@code model} 은
 * 비어 있지 않고 라이브러리 기본값 {@code gpt-5-mini} 다. 이 앱의 블로킹 호출부(분류·독립화·검증·키워드 추출 …)는 온도와 {@code maxTokens} 만 싣고
 * 모델은 싣지 않으므로 그 기본값이 그대로 요청에 실렸다. 프로바이더 빈의 기본 옵션에 {@code model} 을 넣어 둔 것은
 * 호출자가 옵션을 아예 안 줬을 때만 쓰인다. 모델 이름을 무시하는 서버(llama-server)에서는 증상이 안 보이고, 이름으로
 * 모델을 고르는 서버나 원격 프로바이더에서는 다른 모델이 불리거나 404 가 나고 라우터가 그것을 프로바이더 실패로 보고
 * 차단한다. curl 로그({@code LoggingChatModel})는 프로바이더의 모델 id 를 따로 받아 찍으므로 로그에는 맞는 값이 보였다 —
 * 그래서 눈으로는 발견되지 않았고 {@code ThinkingControlWireTest} 가 실제 본문을 본다.
 *
 * <p>호출부를 고쳐 모델을 싣게 하는 방법은 쓰지 않았다 — 어느 프로바이더가 이 요청을 받을지는 {@code LlmRouter} 가
 * <b>나중에</b> 정하므로 호출부는 모델 이름을 알 수 없다({@code MaxTokensCappingChatModel} 과 같은 이유, 같은 관례).
 * 이 데코레이터는 프로바이더가 정해진 뒤, 체인의 <b>가장 안쪽</b>(원본 모델 바로 위)에서 돌아 위쪽 데코레이터들이 옵션을
 * 어떻게 복사하든 마지막에 한 번 맞춘다. 모델은 호출의 성질이 아니라 프로바이더의 성질이라 호출자가 다른 값을 실었어도
 * 덮어쓴다 — 이 앱에서 블로킹 호출이 모델을 정하는 자리는 없다(채팅 답변 스트리밍은 체인을 지나지 않고
 * {@code AnswerStreamer} 가 제 옵션에 {@code provider.model()} 을 싣는다).
 *
 * <p>옵션이 아예 없는 호출은 건드리지 않는다 — 원본 모델이 기본 옵션(모델 포함)을 쓴다.
 */
public class ModelPinningChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(ModelPinningChatModel.class);

    private final ChatModel delegate;
    private final String providerName;
    private final String model;

    /**
     * @param model 이 프로바이더의 요청에 실릴 모델 id({@code LlmProvider.model()} — G3 가 서버 목록에서 해석한 값).
     *              비어 있으면 아무것도 하지 않는다.
     */
    public ModelPinningChatModel(ChatModel delegate, String providerName, String model) {
        this.delegate = delegate;
        this.providerName = providerName;
        this.model = model;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return delegate.call(pinned(prompt));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(pinned(prompt));
    }

    @Override
    public ChatOptions getOptions() {
        return delegate.getOptions();
    }

    private Prompt pinned(Prompt prompt) {
        if (model == null || model.isBlank()) return prompt;
        ChatOptions options = prompt.getOptions();
        if (options == null || model.equals(options.getModel())) return prompt;

        // 일반 ChatOptions 도 OpenAiChatOptions 로 옮겨 모델을 싣는다 — 그대로 두면 OpenAiChatModel 이 자기 기본값을 쓸 수 있다.
        // 옵션 사본만 갈아 끼운다: 원본 Prompt 를 건드리면 호출부가 재사용할 때 값이 새어 나간다.
        OpenAiChatOptions.Builder builder = options instanceof OpenAiChatOptions openAi
                ? openAi.mutate()
                : ThinkingControl.fromGeneric(options);
        log.debug("[MODEL] provider=[{}] {} → {} (프로바이더 모델)", providerName, options.getModel(), model);
        return new Prompt(prompt.getInstructions(), builder.model(model).build());
    }
}
