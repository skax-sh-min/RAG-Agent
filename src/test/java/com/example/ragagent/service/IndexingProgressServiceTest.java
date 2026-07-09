package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
