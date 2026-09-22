package com.example.ragagent.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 요청 스레드의 {@code traceId} 가 워커·future 스레드에서도 보이는가 — 2026-09-21 의 GPU 소실 로그가
 * 전부 {@code [-]} 였던 것이 이 클래스가 없던 이유다.
 */
class MdcPropagationTest {

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    @DisplayName("wrap — 넘긴 스레드의 MDC 를 작업 스레드에 씌우고, 끝나면 원래대로 되돌린다")
    void wrapCarriesMdcAndRestores() throws Exception {
        MDC.put("traceId", "req-1");
        AtomicReference<String> seenInside = new AtomicReference<>();
        Runnable task = MdcPropagation.wrap(() -> seenInside.set(MDC.get("traceId")));

        Thread worker = new Thread(() -> {
            MDC.put("traceId", "other-thread");
            task.run();
            // 작업이 끝난 뒤 그 스레드의 원래 값이 살아 있어야 한다(풀 스레드에 남의 id 가 새지 않게).
            seenInside.set(seenInside.get() + "|after=" + MDC.get("traceId"));
        });
        worker.start();
        worker.join();

        assertThat(seenInside.get()).isEqualTo("req-1|after=other-thread");
    }

    @Test
    @DisplayName("propagating — CompletableFuture.*Async 에 넘기면 모든 단계가 넘긴 스레드의 MDC 를 잇는다")
    void propagatingExecutorCoversEveryStage() {
        MDC.put("traceId", "req-2");
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Executor exec = MdcPropagation.propagating(pool);
            String seen = CompletableFuture.supplyAsync(() -> MDC.get("traceId"), exec)
                    .thenApplyAsync(first -> first + "→" + MDC.get("traceId"), exec)
                    .join();
            assertThat(seen).isEqualTo("req-2→req-2");
        }
    }

    @Test
    @DisplayName("넘겨줄 MDC 가 없으면 작업 스레드의 MDC 를 비운다 — 남의 요청 id 를 이어 쓰지 않는다")
    void noContextClearsInsteadOfInheriting() throws Exception {
        MDC.clear();
        AtomicReference<String> inside = new AtomicReference<>("unset");
        Runnable task = MdcPropagation.wrap(() -> inside.set(MDC.get("traceId")));
        AtomicReference<Map<String, String>> after = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            MDC.put("traceId", "stale");
            task.run();
            after.set(MDC.getCopyOfContextMap());
        });
        worker.start();
        worker.join();
        assertThat(inside.get()).isNull();
        assertThat(after.get()).containsEntry("traceId", "stale");   // 되돌림은 여전히 동작
    }
}
