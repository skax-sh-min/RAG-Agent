package com.example.ragagent.llm;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * 지나가는 모든 프롬프트에 사이트를 표시한다 — 옵션을 직접 넘길 수 없는 프레임워크 호출부를 위한 것이다(PLAN §6.29).
 *
 * <p>쓰는 곳은 {@code RetrievalService} 의 {@code MultiQueryExpander} 하나다. 그 확장기는 받은 모델로 자기
 * {@code ChatClient} 를 만들어 프롬프트를 직접 조립하므로, 호출부가 {@link ThinkingControl#mark} 로 옵션에 표시할
 * 자리가 없다. 그래서 그 모델({@link RoutedChatModel}) 앞에 이것을 끼운다. 표시는 라우터가 고른 프로바이더 체인의
 * {@link ThinkingControlChatModel} 이 읽고 걷어낸다.
 */
public class ThinkingSiteChatModel implements ChatModel {

    private final ChatModel delegate;
    private final ThinkingSite site;

    public ThinkingSiteChatModel(ChatModel delegate, ThinkingSite site) {
        this.delegate = delegate;
        this.site = site;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return delegate.call(ThinkingControl.mark(prompt, site));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(ThinkingControl.mark(prompt, site));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }
}
