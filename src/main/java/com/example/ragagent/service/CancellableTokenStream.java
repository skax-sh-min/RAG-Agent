package com.example.ragagent.service;

import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * LLM 토큰 스트림을 <b>취소 가능하게</b> 소비한다 — 중지 버튼, SSE 연결 끊김, 유휴 타임아웃.
 *
 * <p><b>왜 있는가.</b> 두 스트리밍 경로({@code AnswerService.streamDirect()},
 * {@code DirectAnswerService.callOrStream()})는 {@code flux.toIterable().forEach(sink)} 로 토큰을
 * 소비했다. 사용자가 중지를 누르면 브라우저가 fetch 를 abort 하고, 서버의 다음
 * {@code emitter.send()} 가 {@code IOException} → {@code UncheckedIOException} 으로 터진다. 그
 * 예외는 {@code forEach} 밖으로 전파되는데, <b>그때 반복자를 그냥 버릴 뿐 업스트림을 취소하지
 * 않는다</b>. 구독이 살아 있으니 LLM 쪽 HTTP 응답 본문은 계속 소비되고, 로컬 LLM 은 화면이
 * "사용자가 중단함"을 띄운 뒤에도 계속 생성한다(관찰된 증상).
 *
 * <p>실측으로 확인한 동작이다. 5ms 마다 토큰을 내는 {@code Flux} 를 3번째 토큰에서 예외를 던지는
 * 소비자로 {@code toIterable().forEach()} 하면 {@code doOnCancel} 이 <b>끝내 불리지 않고</b>
 * 업스트림은 계속 생산한다(소비 3개 / 생산 63개, 계속 증가). 같은 스트림을 이 클래스처럼
 * 구독을 붙잡아 두고 실패 시 {@code cancel()} 하면 생산이 즉시 멈춘다(소비 3개 / 생산 4개, 300ms
 * 뒤에도 4개).
 *
 * <p><b>중단 신호가 두 갈래인 것에 주의.</b> 클라이언트 abort 는 (1) {@code emitter.send()} 실패와
 * (2) {@code SseEmitter.onError}/{@code onCompletion} → {@code worker.interrupt()} 를 <b>동시에</b>
 * 일으킨다. 인터럽트만으로는 부족하다 — 반복자가 <b>대기 중</b>일 때만 Reactor 가 그것을 보고
 * 취소하는데, 토큰이 끊임없이 도착하는 동안에는 대기하지 않으며, 애초에 send 실패 쪽이 먼저
 * 터져 스레드는 이미 반복자 밖으로 나와 있다. 그래서 여기서는 <b>예외 경로에서 직접 취소</b>하고,
 * 토큰 사이사이 인터럽트 플래그도 함께 본다(둘 중 무엇이 먼저 오든 결과가 같도록).
 */
final class CancellableTokenStream {

    private static final Logger log = LoggerFactory.getLogger(CancellableTokenStream.class);

    private CancellableTokenStream() {}

    /**
     * {@code tokens} 를 호출 스레드에서 소비한다. 소비자가 던지거나 스레드가 인터럽트되면
     * 업스트림 구독을 취소한 뒤 예외를 그대로 올려보낸다 — 즉 <b>호출부의 예외 처리는 예전과
     * 똑같고</b>, 달라지는 것은 LLM 쪽 연결이 실제로 끊긴다는 점뿐이다.
     */
    static void consume(Flux<String> tokens, Consumer<String> tokenSink) {
        AtomicReference<Subscription> subscription = new AtomicReference<>();
        try {
            tokens.doOnSubscribe(subscription::set)
                    .toIterable()
                    .forEach(token -> {
                        // 인터럽트는 지워지지 않는다(isInterrupted). StreamingAgentService 의
                        // catch 가 그 플래그를 보고 "정상 취소"로 분류하므로, 여기서 무엇을
                        // 던지든 오류로 기록되지 않는다. send 실패 경로와 같은 예외 타입을 써서
                        // 호출부가 두 경우를 구분할 필요가 없게 한다.
                        if (Thread.currentThread().isInterrupted()) {
                            throw new UncheckedIOException(
                                    new InterruptedIOException("토큰 스트림이 중단됐다 (중지/타임아웃)"));
                        }
                        tokenSink.accept(token);
                    });
        } catch (RuntimeException | Error e) {
            cancelQuietly(subscription.get());
            throw e;
        }
    }

    /** 취소 실패가 원래 예외를 가려서는 안 된다 — 이미 실패한 턴을 정리하는 중이다. */
    private static void cancelQuietly(Subscription subscription) {
        if (subscription == null) return;
        try {
            subscription.cancel();
        } catch (RuntimeException e) {
            log.warn("[STREAM] 토큰 스트림 취소 실패 — 무시하고 진행: {}", e.toString());
        }
    }
}
