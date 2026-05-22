package com.example.ragagent.service;

import com.example.ragagent.model.IndexingProgressEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
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

    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<IndexingProgressEvent>> buffers = new ConcurrentHashMap<>();

    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "idx-progress-cleanup");
        t.setDaemon(true);
        return t;
    });

    public String newTaskId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Subscribe to progress events for the given task.
     * Replays any events already buffered (handles race with async task start).
     */
    public SseEmitter subscribe(String taskId) {
        SseEmitter emitter = new SseEmitter(600_000L);

        List<IndexingProgressEvent> buffered = buffers.get(taskId);
        if (buffered != null && !buffered.isEmpty()) {
            for (IndexingProgressEvent event : buffered) {
                try {
                    emitter.send(SseEmitter.event().name("progress")
                            .data(event, MediaType.APPLICATION_JSON));
                } catch (IOException e) {
                    emitter.completeWithError(e);
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
            try { emitter.send(SseEmitter.event().name("ping").data("")); }
            catch (Exception ignored) {}
        }, 25, 25, TimeUnit.SECONDS);

        emitter.onCompletion(() -> { hb.cancel(false); emitters.remove(taskId); });
        emitter.onTimeout(   () -> { hb.cancel(false); emitters.remove(taskId); });
        emitter.onError(e   -> { hb.cancel(false); emitters.remove(taskId); });
        return emitter;
    }

    /** Publish an event to the subscriber (if connected) and append to buffer. */
    public void publish(String taskId, IndexingProgressEvent event) {
        buffers.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(event);

        SseEmitter emitter = emitters.get(taskId);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name("progress")
                        .data(event, MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                log.debug("[IndexingProgress] send failed taskId={}: {}", taskId, e.getMessage());
                emitters.remove(taskId);
            }
        }

        if (isTerminal(event.stage())) {
            if (emitter != null) {
                try { emitter.complete(); } catch (Exception ignored) {}
            }
            emitters.remove(taskId);
            cleaner.schedule(() -> buffers.remove(taskId), 60, TimeUnit.SECONDS);
        }
    }

    private static boolean isTerminal(String stage) {
        return "done".equals(stage) || "error".equals(stage) || "sync_done".equals(stage);
    }
}
