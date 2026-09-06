package com.example.ragagent.service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * SSE 하트비트 한 번을 <b>스케줄러 스레드 밖에서</b> 보내는 장치.
 *
 * <p><b>왜 필요한가.</b> {@code emitter.send()} 는 소켓 쓰기, 즉 블로킹 I/O 다. 하트비트를
 * 스케줄러 태스크 안에서 직접 보내면 <b>느린 클라이언트 한 명이 스케줄러 스레드를 붙잡는다</b>
 * — 그 스레드는 이 배포의 모든 대화가 공유하므로, 멈춰 있는 탭 하나 때문에 다른 사람들의
 * 하트비트가 밀리고(리버스 프록시가 멀쩡한 연결을 유휴로 보고 끊는다) 같은 스케줄러에 얹힌
 * {@code StreamingAgentService} 의 <b>유휴 워치독까지 늦게 돌아</b>
 * {@code app.sse-idle-timeout-seconds} 가 설계대로 동작하지 않는다.
 *
 * <p>그래서 스케줄러 스레드는 <b>깨우기만</b> 하고 쓰기는 다른 스레드(운영에서는 가상 스레드)로
 * 넘긴다. 이렇게 하면 스케줄러 태스크가 순수 계산만 남아 단일 스레드로도 세션 수에 관계없이
 * 제때 돈다 — 워치독을 별도 스케줄러로 옮길 이유가 없어지는 것도 이 때문이다.
 *
 * <p><b>in-flight 가드가 필수인 이유.</b> Spring 의 {@code ResponseBodyEmitter} 는 내부
 * {@code writeLock} 으로 한 emitter 의 전송을 직렬화한다(그래서 하트비트와 답변 토큰이 섞이지
 * 않는다). 즉 답변 토큰이 락을 쥐고 있으면 하트비트 전송도 그만큼 기다린다. 가드가 없으면
 * 그동안 매 tick 마다 새 스레드가 같은 락을 기다리며 쌓인다 — 앞의 하트비트가 아직 나가지
 * 않았다면 이번 tick 은 그냥 건너뛰는 것이 맞다(하트비트는 누적이 의미 없는 신호다).
 *
 * <p>순수 클래스다({@code HistoryPolicy}/{@code RetrievalEviction} 선례) — 로거도 emitter 도
 * 들지 않고, 전송 방법과 실행 위치를 모두 생성자로 받는다. 그래서 전송이 막혀 있는 상황을
 * 시간에 기대지 않고 확정적으로 시험할 수 있다.
 */
final class SseHeartbeat implements Runnable {

    /** {@code emitter.send()} 가 {@code IOException} 을 던지므로 {@link Runnable} 로는 받을 수 없다. */
    @FunctionalInterface
    interface Send {
        void send() throws Exception;
    }

    private final Send send;
    private final Consumer<Runnable> offload;
    private final AtomicBoolean inFlight = new AtomicBoolean(false);

    /** 운영용 — 전송을 가상 스레드에서 한다. */
    SseHeartbeat(Send send) {
        this(send, task -> Thread.ofVirtual().name("sse-heartbeat-send").start(task));
    }

    /** 테스트가 실행 위치를 바꿔 끼우기 위한 생성자(동기 실행, 지연 실행 등). */
    SseHeartbeat(Send send, Consumer<Runnable> offload) {
        this.send = send;
        this.offload = offload;
    }

    /** 스케줄러가 호출한다 — 절대 블로킹하지 않는다. */
    @Override
    public void run() {
        if (!inFlight.compareAndSet(false, true)) return;   // 앞 하트비트가 아직 나가지 않았다
        try {
            offload.accept(() -> {
                try {
                    send.send();
                } catch (Exception ignored) {
                    // 끊긴 연결에 쓰는 것이 대부분이다. 하트비트 실패 자체는 아무것도 바꾸지
                    // 않으며, 정리가 필요한 호출자는 자기 람다 안에서 직접 한다
                    // (IndexingProgressService 가 emitters 에서 지우고 complete() 한다).
                } finally {
                    inFlight.set(false);
                }
            });
        } catch (RuntimeException e) {
            // 오프로드 자체가 실패했다(스레드 생성 거부, 종료 중인 실행자 등) — 위의 finally 가
            // 영영 돌지 않으므로 여기서 풀지 않으면 이 하트비트는 두 번 다시 나가지 못한다.
            inFlight.set(false);
            throw e;
        }
    }
}
