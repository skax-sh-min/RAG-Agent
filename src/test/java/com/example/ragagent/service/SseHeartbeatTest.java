package com.example.ragagent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SseHeartbeat} 의 계약 — <b>스케줄러 스레드를 절대 붙잡지 않는다</b>와
 * <b>앞 하트비트가 나가지 않았으면 건너뛴다</b>.
 *
 * <p>실행 위치를 생성자로 받는 설계 덕분에 전송이 막혀 있는 상황을 시간(sleep)에 기대지 않고
 * 확정적으로 만들 수 있다 — 아래 대부분의 테스트는 오프로드를 <b>수동 실행</b>으로 바꿔 끼워
 * "보냈다/아직 안 보냈다"를 테스트가 직접 정한다.
 */
class SseHeartbeatTest {

    /** 오프로드된 작업을 모아 두고 테스트가 원하는 시점에 돌리는 실행자. */
    private static final class ManualExecutor {
        private final List<Runnable> queued = new ArrayList<>();
        void accept(Runnable task) { queued.add(task); }
        int pending() { return queued.size(); }
        void runAll() {
            List<Runnable> batch = new ArrayList<>(queued);
            queued.clear();
            batch.forEach(Runnable::run);
        }
    }

    @Test
    @DisplayName("tick 하나 — 전송을 오프로드한다(스케줄러 스레드에서 보내지 않는다)")
    void tick_offloadsTheSend() {
        ManualExecutor exec = new ManualExecutor();
        AtomicInteger sent = new AtomicInteger();
        SseHeartbeat hb = new SseHeartbeat(sent::incrementAndGet, exec::accept);

        hb.run();

        assertThat(sent.get()).as("run() 자체는 전송하지 않는다").isZero();
        assertThat(exec.pending()).isEqualTo(1);

        exec.runAll();
        assertThat(sent.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("앞 하트비트가 아직 안 나갔으면 다음 tick 은 건너뛴다 — 대기 스레드가 쌓이지 않는다")
    void tickWhileInFlight_isSkipped() {
        ManualExecutor exec = new ManualExecutor();
        AtomicInteger sent = new AtomicInteger();
        SseHeartbeat hb = new SseHeartbeat(sent::incrementAndGet, exec::accept);

        hb.run();          // 1번 tick — 오프로드됨, 아직 실행 전
        hb.run();          // 2번
        hb.run();          // 3번

        assertThat(exec.pending())
                .as("in-flight 중의 tick 은 새 작업을 만들지 않아야 한다")
                .isEqualTo(1);

        exec.runAll();
        assertThat(sent.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("전송이 끝나면 다음 tick 은 다시 보낸다")
    void afterSendCompletes_nextTickSendsAgain() {
        ManualExecutor exec = new ManualExecutor();
        AtomicInteger sent = new AtomicInteger();
        SseHeartbeat hb = new SseHeartbeat(sent::incrementAndGet, exec::accept);

        hb.run();
        exec.runAll();
        hb.run();
        exec.runAll();

        assertThat(sent.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("전송이 예외를 던져도 in-flight 가 풀린다 — 끊긴 연결 한 번에 하트비트가 영구 정지하면 안 된다")
    void sendFailure_releasesInFlight() {
        ManualExecutor exec = new ManualExecutor();
        AtomicInteger attempts = new AtomicInteger();
        SseHeartbeat hb = new SseHeartbeat(() -> {
            attempts.incrementAndGet();
            throw new java.io.IOException("broken pipe");
        }, exec::accept);

        hb.run();
        exec.runAll();     // 던진다 — SseHeartbeat 가 삼켜야 한다
        hb.run();
        exec.runAll();

        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("오프로드 자체가 실패해도 in-flight 가 풀린다(스레드 생성 거부·종료 중인 실행자)")
    void offloadFailure_releasesInFlight() {
        AtomicInteger offloadCalls = new AtomicInteger();
        SseHeartbeat hb = new SseHeartbeat(() -> { }, task -> {
            offloadCalls.incrementAndGet();
            throw new java.util.concurrent.RejectedExecutionException("shutting down");
        });

        assertThatThrownBy(hb::run).isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
        assertThatThrownBy(hb::run).isInstanceOf(java.util.concurrent.RejectedExecutionException.class);

        assertThat(offloadCalls.get())
                .as("첫 실패가 in-flight 를 잠근 채 남으면 두 번째 tick 은 오프로드조차 시도하지 않는다")
                .isEqualTo(2);
    }

    /**
     * 기본 생성자(가상 스레드)로 <b>실제로</b> 블로킹 전송을 걸어 두고 {@code run()} 이 즉시
     * 돌아오는지 본다. 전송이 latch 에 막혀 있으므로, run() 이 전송을 기다렸다면 이 테스트는
     * 그 자리에서 멈춘다 — 타이밍이 아니라 구조가 검증된다.
     */
    @Test
    @DisplayName("실제 가상 스레드 — 전송이 막혀 있어도 run() 은 즉시 반환한다")
    void run_doesNotBlockOnASlowSend() throws Exception {
        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        SseHeartbeat hb = new SseHeartbeat(() -> {
            sendStarted.countDown();
            releaseSend.await();          // 테스트가 풀어 줄 때까지 소켓에 매달려 있는 셈
        });

        hb.run();                         // 여기서 막히면 테스트가 끝나지 않는다

        assertThat(sendStarted.await(5, TimeUnit.SECONDS))
                .as("전송은 다른 스레드에서 시작돼야 한다").isTrue();
        releaseSend.countDown();
    }
}
