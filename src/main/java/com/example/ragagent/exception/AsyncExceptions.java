package com.example.ragagent.exception;

import java.util.concurrent.CompletionException;

/**
 * {@code CompletableFuture.join()} 이 던지는 {@link CompletionException} 을 원래 예외로 되돌린다.
 *
 * <p>채팅 진입점 둘({@code AgentService.chat}, {@code StreamingAgentService.run})은 이력·독립화·분류를
 * future 로 병렬 실행한 뒤 {@code join()} 한다. 거기서 분류기의 LLM 호출이 실패하면 나오는 것은
 * {@code LlmProviderExhaustedException} 이 아니라 그것을 감싼 {@code CompletionException} 이고, 뒤에
 * 있는 {@code catch (LlmProviderExhaustedException)} 은 그것을 잡지 못한다. 2026-09-21 의 GPU 소실에서
 * 정확히 그렇게 됐다 — 소진은 예상된 장애인데 일반 {@code catch (Exception)} 으로 떨어져 ERROR 로
 * 기록되고({@code SSE streaming error} + {@code RAG-INT-001}), 사용자에게는 현지화된 문구 대신
 * {@code e.getMessage()} 즉 <b>클래스 이름이 앞에 붙은</b>
 * {@code "com.example….LlmProviderExhaustedException: AI 서버가 …"} 가 토스트로 나갔다.
 *
 * <p>원인이 {@link RuntimeException} 이면 그것을, 아니면 {@code CompletionException} 자체를 돌려준다 —
 * 호출자는 {@code throw AsyncExceptions.unwrap(e);} 한 줄로 쓴다.
 */
public final class AsyncExceptions {

    private AsyncExceptions() {}

    public static RuntimeException unwrap(CompletionException e) {
        Throwable cause = e.getCause();
        return cause instanceof RuntimeException re ? re : e;
    }
}
