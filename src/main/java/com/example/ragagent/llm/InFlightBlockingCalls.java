package com.example.ragagent.llm;

import org.slf4j.MDC;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 한 턴(요청의 {@code traceId}) 안에서 <b>진행 중인 블로킹 LLM 호출</b>을 센다 — SSE 유휴 워치독이 읽는다(§6.29 열린 항목 e).
 *
 * <p>워치독은 그래프가 낸 이벤트(노드 진입·토큰·생각 델타·출처)가 {@code app.sse-idle-timeout-seconds} 동안 없으면 워커를
 * 끊는다. 그런데 분류·질의 확장·Tool 선택·검증은 <b>응답이 통째로 올 때까지 이벤트가 없는</b> 블로킹 호출이라, 생각을 켜고
 * 유휴 타임아웃을 크게 낮춘 배포에서는 멀쩡히 도는 호출이 끊겼다. 호출이 진행 중이면 활동으로 본다.
 *
 * <p><b>왜 끊을 능력을 잃지 않는가</b>: 진짜로 멈춘 블로킹 호출은 HTTP 읽기 타임아웃({@code OpenAiClients})이 끊어 예외가
 * 되고 호출이 끝난다 — 그러면 카운터가 내려가 유휴 시계가 다시 흐른다. 이 클래스는 "지금 서버의 응답을 기다리는 중"만 알린다.
 *
 * <p><b>왜 {@code traceId} 로 묶는가</b>: 한 턴의 블로킹 호출은 워커·사전 future·검색 축 등 여러 스레드에서 도는데,
 * {@code MdcPropagation} 이 이미 그 모든 스레드에 {@code traceId} 를 잇는다. 워치독(스케줄러 스레드)은 워커 시작 때 잡아 둔
 * 같은 id 로 묻는다. MDC 에 id 가 없으면(요청 밖·테스트) 아무것도 세지 않는다 — 어느 턴의 것인지 알 수 없는 호출이
 * 다른 턴의 워치독을 늦추는 것보다 낫다.
 *
 * <p>정적 상태지만 키가 요청마다 다른 id 라 서로 간섭하지 않는다. 항목은 카운터가 0 이 되는 순간 지워 새지 않는다.
 */
public final class InFlightBlockingCalls {

    /** MDC 키 — {@code web/TraceIdFilter} 가 넣는 것과 같다. */
    public static final String TRACE_ID_KEY = "traceId";

    private static final ConcurrentHashMap<String, AtomicInteger> IN_FLIGHT = new ConcurrentHashMap<>();

    private InFlightBlockingCalls() {}

    /** 호출을 시작한다 — 반환값을 try-with-resources 로 닫는다. 지금 스레드의 MDC 에 id 가 없으면 아무것도 하지 않는 토큰. */
    public static Token enter() {
        String traceId = MDC.get(TRACE_ID_KEY);
        if (traceId == null || traceId.isBlank()) return Token.NONE;
        IN_FLIGHT.computeIfAbsent(traceId, k -> new AtomicInteger()).incrementAndGet();
        return new Token(traceId);
    }

    /** 이 턴에서 지금 응답을 기다리는 블로킹 호출이 있는가. {@code traceId} 가 없으면 거짓. */
    public static boolean active(String traceId) {
        if (traceId == null) return false;
        AtomicInteger n = IN_FLIGHT.get(traceId);
        return n != null && n.get() > 0;
    }

    private static void leave(String traceId) {
        // compute 안에서 내리고 0 이면 지운다 — 내린 뒤 지우는 사이에 같은 id 의 enter() 가 끼어도 카운터를 잃지 않는다.
        IN_FLIGHT.computeIfPresent(traceId, (k, n) -> n.decrementAndGet() <= 0 ? null : n);
    }

    /** 두 번 닫아도 한 번만 내린다. */
    public static final class Token implements AutoCloseable {
        static final Token NONE = new Token(null);

        private final String traceId;
        private final AtomicInteger open = new AtomicInteger(1);

        private Token(String traceId) {
            this.traceId = traceId;
        }

        @Override
        public void close() {
            if (traceId != null && open.compareAndSet(1, 0)) leave(traceId);
        }
    }
}
