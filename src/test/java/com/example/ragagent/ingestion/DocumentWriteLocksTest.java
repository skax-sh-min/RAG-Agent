package com.example.ragagent.ingestion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** 문서 단위 쓰기 잠금 — 같은 문서끼리만 배타적이고, 기다리지 않고 거절하며, close 로 풀린다. */
class DocumentWriteLocksTest {

    private final DocumentWriteLocks locks = new DocumentWriteLocks();

    @Test
    @DisplayName("같은 문서는 한 번에 하나 — 두 번째는 기다리지 않고 null, 풀리면 다시 잡힌다")
    void exclusivePerDocumentAndNeverBlocks() {
        DocumentWriteLocks.Lease first = locks.tryAcquire("d1");
        assertThat(first).isNotNull();
        assertThat(locks.isLocked("d1")).isTrue();

        // 재진입하지 않는다 — 같은 스레드가 다시 잡으려 해도 거절이다(겹치는 두 작업을 중첩해 부르는 실수를 막는다)
        assertThat(locks.tryAcquire("d1")).as("같은 스레드의 재진입도 거절").isNull();
        AtomicBoolean otherThreadGot = new AtomicBoolean(true);
        Thread t = new Thread(() -> otherThreadGot.set(locks.tryAcquire("d1") != null));
        t.start();
        join(t);
        assertThat(otherThreadGot).as("다른 스레드는 기다리지 않고 거절된다").isFalse();

        first.close();
        assertThat(locks.isLocked("d1")).isFalse();
        try (DocumentWriteLocks.Lease again = locks.tryAcquire("d1")) {
            assertThat(again).isNotNull();
        }
    }

    @Test
    @DisplayName("같은 증표를 두 번 닫아도 한 번만 푼다 — 그 사이 다른 작업이 잡은 잠금을 풀지 않는다")
    void closingTwiceDoesNotFreeSomeoneElsesLease() {
        DocumentWriteLocks.Lease first = locks.tryAcquire("d1");
        first.close();
        DocumentWriteLocks.Lease second = locks.tryAcquire("d1");
        assertThat(second).isNotNull();

        first.close();   // 이미 닫은 증표 — 두 번째 작업의 잠금을 건드리면 안 된다

        assertThat(locks.isLocked("d1")).isTrue();
        second.close();
        assertThat(locks.isLocked("d1")).isFalse();
    }

    @Test
    @DisplayName("다른 문서는 서로 막지 않는다")
    void differentDocumentsAreIndependent() {
        try (DocumentWriteLocks.Lease a = locks.tryAcquire("d1"); DocumentWriteLocks.Lease b = locks.tryAcquire("d2")) {
            assertThat(a).isNotNull();
            assertThat(b).isNotNull();
        }
    }

    @Test
    @DisplayName("try-with-resources 가 예외에도 푼다 — 실패한 작업이 문서를 영원히 잠그지 않는다")
    void releasedEvenWhenTheWorkThrows() {
        try (DocumentWriteLocks.Lease lease = locks.tryAcquire("d1")) {
            assertThat(lease).isNotNull();
            throw new IllegalStateException("boom");
        } catch (IllegalStateException expected) {
            // 아래에서 풀렸는지 본다
        }
        assertThat(locks.isLocked("d1")).isFalse();
    }

    @Test
    @DisplayName("동시에 여러 스레드가 잡으려 하면 정확히 하나만 얻는다")
    void exactlyOneWinnerUnderContention() throws Exception {
        int n = 16;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        java.util.concurrent.atomic.AtomicInteger winners = new java.util.concurrent.atomic.AtomicInteger();
        CountDownLatch holdUntilAllTried = new CountDownLatch(1);
        for (int i = 0; i < n; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    DocumentWriteLocks.Lease lease = locks.tryAcquire("d1");
                    if (lease != null) {
                        winners.incrementAndGet();
                        holdUntilAllTried.await();   // 다른 스레드가 모두 시도할 때까지 쥔다
                        lease.close();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        Thread.sleep(300);
        holdUntilAllTried.countDown();
        assertThat(done.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(winners.get()).isEqualTo(1);
    }

    private static void join(Thread t) {
        try {
            t.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
