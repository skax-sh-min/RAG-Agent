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
import java.util.ArrayDeque;
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
    // still learn the real outcome. The retention window is measured from the task's LAST event,
    // not its first — publish() re-puts the buffer for exactly that reason (see there), so only a
    // task that has actually gone quiet (finished, or abandoned) ages out.
    private static final Duration BUFFER_RETENTION = Duration.ofHours(4);

    /**
     * 기록을 남겨 두는 태스크 수 상한. 파일 하나 업로드가 태스크 하나라 대량 업로드(화면의 순차
     * 루프)는 수백 개를 만들 수 있어 넉넉히 잡되, 4시간 보존이 무한정 쌓이지는 않게 한다.
     * 초과 시 Caffeine 이 가장 오래 안 쓰인 태스크부터 버린다 — 그 taskId 로 재접속하면
     * {@code unknown} 으로 응답되며, 그건 원래 보존 기간이 지났을 때와 같은 경로다.
     */
    private static final int MAX_TRACKED_TASKS = 500;

    /**
     * 태스크 하나가 보관하는 이벤트 수 상한. 동기화는 파일마다 이벤트를 하나 내므로 이 값이
     * 사실상 "재접속했을 때 되돌려 받을 수 있는 파일 수"다. 넘으면 <b>가장 오래된 것부터</b>
     * 버린다 — 종결 이벤트는 언제나 마지막이라 꼬리를 남기는 쪽이 그것을 지킨다.
     */
    private static final int MAX_EVENTS_PER_TASK = 1_000;

    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final Cache<String, EventBuffer> buffers = Caffeine.newBuilder()
            .expireAfterWrite(BUFFER_RETENTION)
            .maximumSize(MAX_TRACKED_TASKS)
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
     *
     * <p>Reads {@code workers} rather than removing from it directly — {@link #publish} already
     * removes the entry as part of handling the terminal event below, atomically with the buffer
     * write. Removing it here first would open a window (for a taskId with no buffered events yet)
     * where a concurrent {@link #subscribe}/{@link #status} sees neither a buffered event nor an
     * active worker and misreports {@code unknown} instead of {@code cancelled}.
     */
    public void cancel(String taskId) {
        Thread worker = workers.get(taskId);
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
        // generous absolute ceiling as chat SSE (app.sse-timeout-seconds, shipped at 2h) instead
        // of a fixed 10-minute cap that a long-running-but-healthy indexing job would always
        // exceed.
        SseEmitter emitter = new SseEmitter(props.sseTimeoutMs());

        EventBuffer buffer = buffers.getIfPresent(taskId);
        List<IndexingProgressEvent> buffered = buffer == null ? List.of() : buffer.snapshot();

        if (buffered.isEmpty() && !workers.containsKey(taskId)) {
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

        if (!buffered.isEmpty()) {
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

        // 실제 쓰기는 SseHeartbeat 가 가상 스레드로 넘긴다 — 인덱싱 진행률을 보고 있는 탭 하나가
        // 느리면 이 스케줄러 스레드가 붙잡히고, 그 뒤로 모든 taskId 의 ping 이 함께 밀린다.
        // 실패 시 정리(emitters 에서 제거 + complete)는 여기 람다 안에 그대로 남는다.
        java.util.concurrent.ScheduledFuture<?> hb = cleaner.scheduleAtFixedRate(new SseHeartbeat(() -> {
            try {
                emitter.send(SseEmitter.event().name("ping").data(""));
            } catch (Exception e) {
                log.debug("[IndexingProgress] heartbeat failed taskId={}: {}", taskId, e.getMessage());
                emitters.remove(taskId);
                try { emitter.complete(); } catch (Exception ignored) {}
            }
        }), 25, 25, TimeUnit.SECONDS);

        emitter.onCompletion(() -> { hb.cancel(false); emitters.remove(taskId); });
        emitter.onTimeout(   () -> { hb.cancel(false); emitters.remove(taskId); });
        emitter.onError(e   -> { hb.cancel(false); emitters.remove(taskId); });
        return emitter;
    }

    /** Publish an event to the subscriber (if connected) and append to buffer. */
    public void publish(String taskId, IndexingProgressEvent event) {
        EventBuffer buffer = buffers.asMap().computeIfAbsent(taskId, k -> new EventBuffer());
        buffer.add(event);
        // 같은 인스턴스를 다시 넣어 expireAfterWrite 타이머를 되민다. Caffeine 의 그 정책은
        // "생성 또는 값 교체" 기준이라, computeIfAbsent 로 기존 항목을 찾아 그 안을 고치는 것은
        // 캐시가 보기에 쓰기가 아니다 — 이 put 이 없으면 보존 시간이 태스크 '시작'부터 흐르고,
        // 4시간을 넘겨 도는 인덱싱(이 상수의 주석이 전제하는 바로 그 경우)은 아직 돌고 있는 동안
        // 자기 기록을 잃는다.
        buffers.put(taskId, buffer);

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
        EventBuffer buffer = buffers.getIfPresent(taskId);
        IndexingProgressEvent last = buffer == null ? null : buffer.last();
        if (last != null) {
            return last;
        }
        if (workers.containsKey(taskId)) {
            return IndexingProgressEvent.running();
        }
        return IndexingProgressEvent.unknown();
    }

    /**
     * 태스크 하나의 진행 이벤트 기록.
     *
     * <p>쓰기(모든 {@link #publish})가 압도적으로 많고 읽기는 드물다 — 재접속 때의 재생과
     * {@link #status} 뿐이다. 예전에는 {@link java.util.concurrent.CopyOnWriteArrayList} 였는데,
     * 그건 정확히 반대 모양(읽기 위주)을 위한 자료구조다: {@code add} 마다 배열 전체를 복사하므로
     * 이벤트 N 개를 쌓는 데 O(N²)가 든다. 파일마다 이벤트 하나를 내는 디렉터리 동기화에서 그 N 이
     * <b>파일 수</b>라, 1,000개짜리 동기화 한 번이 약 200만 번의 원소 복사와 2,000번의 배열 할당이
     * 됐다. 락 없는 읽기가 사 준 것은 그 대가에 비해 아무것도 아니었다.
     *
     * <p>지금은 평범한 덱을 {@code synchronized} 로 감싸고, 읽는 쪽에는 {@link #snapshot()} 으로
     * 불변 사본을 준다 — 재생 루프가 도는 동안 워커가 이벤트를 더해도 안전한 것은 예전과 같다.
     */
    private static final class EventBuffer {

        private final ArrayDeque<IndexingProgressEvent> events = new ArrayDeque<>();

        synchronized void add(IndexingProgressEvent event) {
            if (events.size() >= MAX_EVENTS_PER_TASK) {
                events.removeFirst();   // 가장 오래된 것부터 — 종결 이벤트는 꼬리에 있다
            }
            events.addLast(event);
        }

        /** 재생용 불변 사본. 호출자가 도는 동안 워커가 계속 써도 영향을 받지 않는다. */
        synchronized List<IndexingProgressEvent> snapshot() {
            return List.copyOf(events);
        }

        /** 마지막 이벤트, 없으면 {@code null}. */
        synchronized IndexingProgressEvent last() {
            return events.peekLast();
        }
    }

    private static boolean isTerminal(String stage) {
        return "done".equals(stage) || "error".equals(stage) || "sync_done".equals(stage)
                || "cancelled".equals(stage);
    }
}
