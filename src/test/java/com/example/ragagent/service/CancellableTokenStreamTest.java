package com.example.ragagent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 중지 버튼이 <b>LLM 쪽 생성까지</b> 실제로 멈추는지 고정한다.
 *
 * <p>관찰된 버그: 화면은 "사용자가 중단함"인데 로컬 LLM 은 계속 생성했다. 원인은
 * {@code toIterable().forEach()} 를 예외로 벗어나도 <b>업스트림 구독이 취소되지 않는</b> 것이었고,
 * 그 사실은 코드만 봐서는 드러나지 않는다(취소는 Reactor 내부 동작이다). 그래서 여기서 실제
 * {@code doOnCancel} 발화를 확인한다 — 이 테스트가 없으면 같은 형태로 되돌려놔도 빌드는 통과한다.
 */
class CancellableTokenStreamTest {

    /** LLM 스트림 흉내 — 계속 토큰을 내보내며, 취소되면 래치를 연다. */
    private static Flux<String> endlessTokens(CountDownLatch cancelled, AtomicInteger produced) {
        return Flux.interval(Duration.ofMillis(5))
                .map(i -> "tok" + i)
                .doOnNext(t -> produced.incrementAndGet())
                .doOnCancel(cancelled::countDown);
    }

    @Test
    @DisplayName("소비자가 실패하면(=SSE send 실패) 업스트림 구독을 취소하고 예외를 그대로 올린다")
    void consumerFailureCancelsUpstream() throws Exception {
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicInteger produced = new AtomicInteger();
        AtomicInteger consumed = new AtomicInteger();

        // emitter.send() 가 클라이언트 abort 후 던지는 것과 같은 예외 타입.
        assertThatThrownBy(() -> CancellableTokenStream.consume(
                endlessTokens(cancelled, produced),
                token -> {
                    if (consumed.incrementAndGet() == 3) {
                        throw new UncheckedIOException(new java.io.IOException("broken pipe"));
                    }
                }))
                .isInstanceOf(UncheckedIOException.class);

        assertThat(cancelled.await(2, TimeUnit.SECONDS))
                .as("구독이 취소돼야 LLM 쪽 HTTP 연결이 닫히고 생성이 멈춘다")
                .isTrue();

        // 취소가 실제로 생산을 멈췄는지 — 취소 시점 이후로 더 늘지 않아야 한다.
        int atCancel = produced.get();
        Thread.sleep(200);
        assertThat(produced.get())
                .as("취소 뒤에도 생산이 계속되면 그것이 바로 이 버그다")
                .isEqualTo(atCancel);
    }

    @Test
    @DisplayName("소비 도중 인터럽트되면(중지/유휴 타임아웃) 다음 토큰에서 멈추고 취소한다")
    void interruptDuringConsumptionStopsAndCancels() throws Exception {
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicInteger consumed = new AtomicInteger();
        // 토큰이 이미 큐에 쌓여 있어 반복자가 대기하지 않는 상황 — Reactor 자체의 인터럽트 처리가
        // 발동하지 않으므로, 토큰 사이의 플래그 검사만이 중단을 만들어 낸다.
        Flux<String> buffered = Flux.range(1, 500).map(i -> "tok" + i).hide()
                .doOnCancel(cancelled::countDown);
        try {
            assertThatThrownBy(() -> CancellableTokenStream.consume(buffered, token -> {
                if (consumed.incrementAndGet() == 1) Thread.currentThread().interrupt();
            }))
                    .isInstanceOf(UncheckedIOException.class)
                    .hasCauseInstanceOf(InterruptedIOException.class);
        } finally {
            // 인터럽트 플래그는 이 스레드를 뒤이어 쓰는 테스트로 새어나가면 안 된다.
            Thread.interrupted();
        }
        assertThat(consumed.get()).as("플래그를 세운 그 다음 토큰에서 멈춰야 한다").isEqualTo(1);
        assertThat(cancelled.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("반복자가 대기 중일 때 인터럽트되면 Reactor 자체 경로로 멈추고 취소된다")
    void interruptWhileWaitingCancels() throws Exception {
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicInteger produced = new AtomicInteger();
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> CancellableTokenStream.consume(
                    endlessTokens(cancelled, produced), token -> { }))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            Thread.interrupted();
        }
        assertThat(cancelled.await(2, TimeUnit.SECONDS))
                .as("이 경로의 취소는 Reactor 가 해 주지만, 중지 신호가 늘 여기로 오지는 않는다"
                    + " — consumerFailureCancelsUpstream 이 실제 중지 경로다")
                .isTrue();
    }

    @Test
    @DisplayName("정상 종료는 그대로 통과시킨다 — 토큰 순서·개수가 보존된다")
    void normalCompletionDeliversEveryToken() {
        List<String> received = new ArrayList<>();
        CancellableTokenStream.consume(Flux.just("a", "b", "c"), received::add);
        assertThat(received).containsExactly("a", "b", "c");
    }

    // ── 규약 가드 ────────────────────────────────────────────────────────────

    /**
     * 이 클래스를 쓰지 않고 {@code toIterable()} 로 되돌리면 버그가 그대로 돌아오는데,
     * 위 테스트들은 이 클래스만 보므로 <b>빌드가 조용히 통과한다</b>. 그래서 호출부까지 고정한다
     * ({@code ResponseModeBranchConventionTest} 와 같은 이유의 가드다).
     */
    @Test
    @DisplayName("운영 코드에서 토큰 스트림을 toIterable() 로 직접 소비하지 않는다")
    void productionCodeConsumesTokenStreamsThroughThisClass() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            files.filter(p -> p.toString().endsWith(".java"))
                    // 취소를 실제로 구현하는 곳 — 여기서만 쓸 수 있다.
                    .filter(p -> !p.getFileName().toString().equals("CancellableTokenStream.java"))
                    .forEach(p -> {
                        try {
                            if (Files.readString(p).contains(".toIterable()")) {
                                violations.add(p.toString());
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
        assertThat(violations)
                .as("toIterable().forEach() 를 예외로 벗어나도 업스트림은 취소되지 않는다 — "
                    + "중지를 눌러도 LLM 이 계속 생성한다. CancellableTokenStream.consume() 을 쓸 것")
                .isEmpty();
    }
}
