package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.model.IndexingProgressEvent;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Manages per-task SSE emitters for real-time indexing progress.
 *
 * Events are buffered so that late-subscribing clients (race between async task
 * start and SSE connection setup) still receive the full event history.
 */
@Component
public class IndexingProgressService {

    private static final Logger log = LoggerFactory.getLogger(IndexingProgressService.class);

    // Indexing can legitimately run for hours (large files, a slow/overloaded LLM backend), and
    // the browser's SSE connection commonly drops and reconnects over that span (sleep, network
    // change, VPN blip). A fixed short-lived buffer means a reconnect that lands after the task
    // already finished gets no history at all — subscribe() would hand it a live emitter that
    // nothing will ever publish to again, a silent "zombie" connection the client can't tell
    // apart from a task that's still genuinely running. Retaining history for hours instead of
    // seconds costs nothing (a handful of small event records per task) and lets a late reconnect
    // still learn the real outcome. expireAfterWrite keeps sliding forward on every publish(), so
    // only a task that's actually gone quiet (finished, or abandoned) ages out.
    private static final Duration BUFFER_RETENTION = Duration.ofHours(4);

    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final Cache<String, List<IndexingProgressEvent>> buffers = Caffeine.newBuilder()
            .expireAfterWrite(BUFFER_RETENTION)
            .build();
    // §6.16.1 — the async virtual thread actually doing the indexing work, keyed by taskId,
    // so a user-initiated cancel can interrupt it (distinct from the SSE emitter above, which
    // only carries progress events to the browser). Also doubles as the "is this task genuinely
    // still running" signal in subscribe()/status() for a taskId whose buffer is still empty
    // (the moment-of-start race, before the worker's first event lands) — registerWorker() always
    // runs before the client can possibly know the taskId (see DocumentController), so that race
    // never reaches here with workers missing an active entry.
    private final ConcurrentHashMap<String, Thread> workers = new ConcurrentHashMap<>();
    private final AppProperties props;

    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "idx-progress-cleanup");
        t.setDaemon(true);
        return t;
    });

    public IndexingProgressService(AppProperties props) {
        this.props = props;
    }

    public String newTaskId() {
        return UUID.randomUUID().toString();
    }

    /** §6.16.1 — records the worker thread for a task so {@link #cancel(String)} can interrupt it. */
    public void registerWorker(String taskId, Thread worker) {
        workers.put(taskId, worker);
    }

    /**
     * §6.16.1 — user-initiated cancel. Interrupts the registered worker thread (if any) and
     * immediately publishes a terminal {@code cancelled} event so the client's SSE subscription
     * completes right away, without waiting for the interrupted worker to unwind and report back.
     */
    public void cancel(String taskId) {
        Thread worker = workers.remove(taskId);
        if (worker != null) {
            log.info("[IndexingProgress] cancel requested taskId={}", taskId);
            worker.interrupt();
        }
        publish(taskId, IndexingProgressEvent.cancelled());
    }

    /**
     * Subscribe to progress events for the given task.
     * Replays any events already buffered (handles race with async task start).
     */
    public SseEmitter subscribe(String taskId) {
        // Large uploads/keyword extraction can run well past 10 minutes; reuse the same
        // generous absolute ceiling as chat SSE (default 1h) instead of a fixed 10-minute
        // cap that a long-running-but-healthy indexing job would always exceed.
        SseEmitter emitter = new SseEmitter(props.sseTimeoutMs());

        List<IndexingProgressEvent> buffered = buffers.getIfPresent(taskId);

        if ((buffered == null || buffered.isEmpty()) && !workers.containsKey(taskId)) {
            // No history and no active worker: this taskId never existed, or its outcome aged
            // out of BUFFER_RETENTION. Tell the client now instead of handing it a live emitter
            // that will only ever receive heartbeat pings — EventSource treats that as a healthy
            // connection, so without this the client would wait forever with no way to notice.
            log.debug("[IndexingProgress] unknown/expired taskId={}", taskId);
            try {
                emitter.send(SseEmitter.event().name("progress")
                        .data(IndexingProgressEvent.unknown(), MediaType.APPLICATION_JSON));
            } catch (IOException ignored) {
                // client already gone; falling through to complete() below is enough
            }
            try { emitter.complete(); } catch (Exception ignored) {}
            return emitter;
        }

        if (buffered != null && !buffered.isEmpty()) {
            for (IndexingProgressEvent event : buffered) {
                try {
                    emitter.send(SseEmitter.event().name("progress")
                            .data(event, MediaType.APPLICATION_JSON));
                } catch (IOException e) {
                    log.debug("[IndexingProgress] buffered replay send failed taskId={}: {}", taskId, e.getMessage());
                    try { emitter.complete(); } catch (Exception ignored) {}
                    return emitter;
                }
            }
            if (isTerminal(buffered.get(buffered.size() - 1).stage())) {
                emitter.complete();
                return emitter;
            }
        }

        emitters.put(taskId, emitter);

        java.util.concurrent.ScheduledFuture<?> hb = cleaner.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().name("ping").data(""));
            } catch (Exception e) {
                log.debug("[IndexingProgress] heartbeat failed taskId={}: {}", taskId, e.getMessage());
                emitters.remove(taskId);
                try { emitter.complete(); } catch (Exception ignored) {}
            }
        }, 25, 25, TimeUnit.SECONDS);

        emitter.onCompletion(() -> { hb.cancel(false); emitters.remove(taskId); });
        emitter.onTimeout(   () -> { hb.cancel(false); emitters.remove(taskId); });
        emitter.onError(e   -> { hb.cancel(false); emitters.remove(taskId); });
        return emitter;
    }

    /** Publish an event to the subscriber (if connected) and append to buffer. */
    public void publish(String taskId, IndexingProgressEvent event) {
        buffers.asMap().computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(event);

        SseEmitter emitter = emitters.get(taskId);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name("progress")
                        .data(event, MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                log.debug("[IndexingProgress] send failed taskId={}: {}", taskId, e.getMessage());
                emitters.remove(taskId);
                try { emitter.complete(); } catch (Exception ignored) {}
            }
        }

        if (isTerminal(event.stage())) {
            if (emitter != null) {
                try { emitter.complete(); } catch (Exception ignored) {}
            }
            emitters.remove(taskId);
            workers.remove(taskId);
            // buffers retains the terminal event for BUFFER_RETENTION (Caffeine expiry) so a
            // client that reconnects later — even much later — still learns the real outcome
            // instead of hanging on a connection nothing will ever arrive on.
        }
    }

    /**
     * One-shot status check, independent of the SSE stream: the last known event for
     * {@code taskId} if any history exists, {@link IndexingProgressEvent#running()} if the task
     * is registered as actively running but hasn't published anything yet, or
     * {@link IndexingProgressEvent#unknown()} otherwise. Lets a client decide whether to (re)open
     * an SSE subscription without paying for one just to find out a task already finished.
     */
    public IndexingProgressEvent status(String taskId) {
        List<IndexingProgressEvent> buffered = buffers.getIfPresent(taskId);
        if (buffered != null && !buffered.isEmpty()) {
            return buffered.get(buffered.size() - 1);
        }
        if (workers.containsKey(taskId)) {
            return IndexingProgressEvent.running();
        }
        return IndexingProgressEvent.unknown();
    }

    private static boolean isTerminal(String stage) {
        return "done".equals(stage) || "error".equals(stage) || "sync_done".equals(stage)
                || "cancelled".equals(stage);
    }
}
