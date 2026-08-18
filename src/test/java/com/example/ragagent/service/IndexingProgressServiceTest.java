package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.model.IndexingProgressEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * QA — IndexingProgressService worker tracking + cancel() (§6.16.1)
 */
class IndexingProgressServiceTest {

    private final IndexingProgressService service = new IndexingProgressService(mock(AppProperties.class));

    @Test
    @DisplayName("cancel() — 등록된 워커 스레드를 interrupt한다")
    void cancel_interruptsRegisteredWorker() throws InterruptedException {
        String taskId = service.newTaskId();
        Thread worker = new Thread(() -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException ignored) {
                // expected — the point of this test
            }
        });
        worker.start();
        service.registerWorker(taskId, worker);

        service.cancel(taskId);
        worker.join(5_000);

        assertThat(worker.isAlive()).as("interrupted sleep should unwind promptly").isFalse();
    }

    @Test
    @DisplayName("cancel() — 워커가 등록되지 않은 taskId 호출 시 예외 없이 무시된다")
    void cancel_unknownTaskId_isNoop() {
        assertThatCode(() -> service.cancel("no-such-task")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("cancel() — 정상 종료(publish 'done')된 taskId에 대해서는 워커를 재차 interrupt하지 않는다")
    void cancel_afterNormalCompletion_workerAlreadyUnregistered() {
        String taskId = service.newTaskId();
        Thread worker = new Thread(() -> {});
        service.registerWorker(taskId, worker);

        // simulate the worker's own terminal publish (e.g. IndexingProgressEvent.done(...))
        service.publish(taskId, com.example.ragagent.model.IndexingProgressEvent.of(
                "done", 1, 1, "f.txt", "완료"));

        // worker map entry is cleaned up by the terminal publish, so a late cancel() is a no-op
        assertThatCode(() -> service.cancel(taskId)).doesNotThrowAnyException();
        assertThat(worker.isInterrupted()).isFalse();
    }

    @Test
    @DisplayName("status() — 존재한 적 없는 taskId는 unknown을 반환한다")
    void status_unknownTaskId_returnsUnknown() {
        assertThat(service.status("no-such-task").stage()).isEqualTo("unknown");
    }

    @Test
    @DisplayName("status() — 워커는 등록됐지만 첫 이벤트가 아직 없으면 running을 반환한다")
    void status_activeWorkerNoEventsYet_returnsRunning() {
        String taskId = service.newTaskId();
        service.registerWorker(taskId, new Thread(() -> {}));

        assertThat(service.status(taskId).stage()).isEqualTo("running");
    }

    @Test
    @DisplayName("status() — publish된 마지막 이벤트를 반환한다")
    void status_returnsLastPublishedEvent() {
        String taskId = service.newTaskId();
        service.publish(taskId, IndexingProgressEvent.of("chunking", 1, 10, "f.md", "청크 분할 중"));

        assertThat(service.status(taskId).stage()).isEqualTo("chunking");
    }

    @Test
    @DisplayName("status() — 종료 후에도 워커 map에서는 지워지지만 마지막 상태는 계속 조회된다 (버퍼 보존)")
    void status_afterTerminalEvent_stillReturnsIt() {
        String taskId = service.newTaskId();
        service.registerWorker(taskId, new Thread(() -> {}));
        service.publish(taskId, IndexingProgressEvent.of("done", 5, 5, "f.md", "완료"));

        assertThat(service.status(taskId).stage()).isEqualTo("done");
    }

    @Test
    @DisplayName("subscribe() — 존재한 적 없는 taskId는 즉시 완료된다 (좀비 연결로 남지 않음)")
    void subscribe_unknownTaskId_completesImmediately() {
        SseEmitter emitter = service.subscribe("never-existed");

        // ResponseBodyEmitter#send throws IllegalStateException once complete() has been called —
        // the most direct way to observe, from outside, that subscribe() completed an unknown
        // taskId's emitter right away instead of leaving it open to receive nothing but heartbeat
        // pings forever.
        assertThatCode(() -> emitter.send(SseEmitter.event().data("x")))
                .as("an unknown/expired taskId's emitter must already be complete")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("subscribe() — 워커가 등록된 실행 중 taskId는 unknown 처리되지 않고 연결을 유지한다")
    void subscribe_activeWorkerNoEventsYet_staysOpen() {
        String taskId = service.newTaskId();
        service.registerWorker(taskId, new Thread(() -> {}));

        SseEmitter emitter = service.subscribe(taskId);

        assertThatCode(() -> emitter.send(SseEmitter.event().data("x")))
                .as("a task that's genuinely still running must not look unknown/expired")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("subscribe() — 종료된 taskId에 재구독하면 마지막 이벤트를 재생하고 즉시 완료한다")
    void subscribe_afterTerminalEvent_replaysAndCompletes() {
        String taskId = service.newTaskId();
        service.registerWorker(taskId, new Thread(() -> {}));
        service.publish(taskId, IndexingProgressEvent.of("done", 5, 5, "f.md", "완료"));

        SseEmitter emitter = service.subscribe(taskId);

        assertThatCode(() -> emitter.send(SseEmitter.event().data("x")))
                .as("a late reconnect must still learn the real outcome, not hang")
                .isInstanceOf(IllegalStateException.class);
    }
}
