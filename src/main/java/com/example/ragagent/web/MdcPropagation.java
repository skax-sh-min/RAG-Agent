package com.example.ragagent.web;

import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 요청 스레드의 MDC({@code traceId})를 다른 스레드에서 도는 작업에 넘긴다.
 *
 * <p>{@link TraceIdFilter} 가 넣는 {@code traceId} 는 스레드 로컬이라 요청 스레드에만 있다. 채팅은
 * 요청 스레드에서 거의 아무것도 하지 않는다 — SSE 워커(가상 스레드), 그 안의 이력·독립화·분류
 * future, 검색의 축별 future, 지연 Vision 호출이 전부 다른 스레드다. 그래서 2026-09-21 의 GPU 소실
 * 로그는 <b>모든 줄이 {@code [-]}</b> 였고, 한 사고의 줄들을 trace id 로 묶을 수 없었다(Logback 의
 * MDC 어댑터는 상속되지 않는 {@code ThreadLocal} 이고, 가상 스레드도 마찬가지다).
 *
 * <p>잡는 시점은 <b>넘기는 쪽</b>이다: {@link #wrap} 은 호출된 스레드의 MDC 를 그 자리에서 복사해
 * 두었다가 작업이 도는 스레드에 씌우고, 끝나면 그 스레드의 원래 MDC 로 되돌린다(가상 스레드는
 * 일회용이라 되돌림이 무의미하지만, 풀 스레드에 쓰여도 새는 값이 없게). {@link #propagating} 은
 * {@code CompletableFuture.*Async(…, executor)} 에 그대로 넘길 수 있는 실행기다 — future 는
 * {@code executor.execute(Runnable)} 만 부르므로 거기서 감싸면 모든 단계를 빠짐없이 덮는다.
 *
 * <p>순수 클래스. 넘겨줄 MDC 가 없으면(요청 밖, 테스트) 작업 스레드의 MDC 를 비운 채로 돈다 —
 * 남의 요청 id 가 묻어 있던 풀 스레드가 그것을 이어 쓰는 것보다 낫다.
 */
public final class MdcPropagation {

    private MdcPropagation() {}

    /** 지금 스레드의 MDC 를 잡아 두었다가 {@code task} 가 도는 스레드에 씌운다. */
    public static Runnable wrap(Runnable task) {
        Map<String, String> captured = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            apply(captured);
            try {
                task.run();
            } finally {
                apply(previous);
            }
        };
    }

    /**
     * 넘긴 스레드의 MDC 를 가지고 작업을 돌리는 실행기. {@code delegate} 의 생명주기(종료)는
     * 그대로 호출자 몫이다 — 이 실행기는 감싸기만 한다.
     */
    public static Executor propagating(Executor delegate) {
        return task -> delegate.execute(wrap(task));
    }

    private static void apply(Map<String, String> context) {
        if (context == null || context.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }
}
