package com.example.ragagent.service;

import com.example.ragagent.llm.LlmProvider;
import com.example.ragagent.llm.LlmRouter;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.function.Function;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 목 {@link LlmRouter} 가 블로킹 호출({@code executeGatedWithUsage})을 <b>주어진 프로바이더의 모델 위에서</b> 실제로 실행하게 한다.
 *
 * <p>라우터를 목으로 두는 서비스 테스트는 호출이 어느 모델에 닿는지가 아니라 서비스가 그 모델에 무엇을 보내는지를 본다.
 * 진짜 라우터가 하는 일(프로바이더 선택·게이트·차단·전환)은 {@code LlmRouterTest} 가 따로 고정하므로, 여기서는 "이 프로바이더가
 * 받았다"로 단순화한다. 질의 확장({@code RoutedChatModel})이 호출마다 라우터를 지나게 된 뒤로 여러 테스트가 같은 스텁을 필요로 해
 * 한 곳에 모았다.
 */
final class RouterTestSupport {

    private RouterTestSupport() {}

    /** 라우터를 지나는 블로킹 호출이 전부 {@code provider} 의 모델에서 실행된다. 사용량 숫자는 0 이다. */
    static void executeOn(LlmRouter router, LlmProvider provider) {
        when(router.executeGatedWithUsage(any(), any(), any())).thenAnswer(inv -> {
            Function<ChatModel, ChatResponse> call = inv.getArgument(2);
            ChatResponse response = call.apply(provider.chatModel());
            return new LlmRouter.LlmResult(response.getResult().getOutput().getText(), 0, 0);
        });
    }
}
