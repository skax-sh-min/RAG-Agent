package com.example.ragagent.service;

import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.repository.QuestionReuseRepository;
import com.example.ragagent.repository.QuestionReuseRepository.BackfillTarget;
import com.example.ragagent.web.MdcPropagation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 과거 대화의 질문을 다듬는 일괄 처리 — {@code /admin} 의 "과거 질문 다듬기".
 *
 * <p>다듬은 질문({@code conversation_turns.clarified_question})은 이 기능이 생긴 뒤의 턴에만 있어서, 그 전에 쌓인
 * 재사용 후보 턴은 추천에서 여전히 원문으로만 맞고 지시어뿐인 질문은 추천에서 빠진다. 그 턴들을 한 건씩 다듬는다 —
 * 턴마다 LLM 한 번이고, 호출·판정·저장은 라이브 경로의 한 줄 다듬기({@link PostAnswerService#backfill})와 같다.
 * 대상은 재사용 후보가 될 수 있는데 아직 다듬지 않은 턴뿐이다({@code QuestionReuseRepository.findClarifyBackfillTargets}).
 *
 * <ul>
 *   <li><b>최신 턴부터</b> — 최근 대화일수록 다시 추천에 걸릴 가능성이 높다.</li>
 *   <li><b>채팅에 양보한다</b> — 매 호출 전에 로컬 LLM 의 대화형 게이트가 비었는지, 마지막으로 바빴던 때에서
 *       {@link #QUIET} 가 지났는지 본다. 이 호출은 동시성 게이트 밖(배경 호출)이라, 확인 없이 돌면 사용자의 답변
 *       생성과 같은 서버를 나눠 쓴다. 차단된 프로바이더는 게이트가 "꽉 참"으로 보고하므로 차단이 풀릴 때까지도
 *       기다린다.</li>
 *   <li><b>언제든 멈추고, 다시 시작하면 남은 것부터</b> — 진행 상태를 따로 저장하지 않는다. 다듬은 턴은 값이 채워져
 *       대상에서 빠지므로 다시 시작하면 자연히 남은 것만 집는다. 호출이 실패한 턴은 NULL 로 남아 다음 실행이 다시
 *       시도한다(한 실행 안에서는 id 커서가 지나가므로 같은 턴을 다시 집지 않는다).</li>
 *   <li><b>질문 다듬기 설정({@code llm.clarified-question-enabled})이 꺼지면 멈춘다</b> — 매 턴 다시 읽는다.</li>
 *   <li><b>연달아 {@value #MAX_CONSECUTIVE_FAILURES}번 실패하면 멈춘다</b> — LLM 이 죽어 있으면 남은 턴을 전부
 *       실패로 훑고 지나갈 뿐이다.</li>
 * </ul>
 *
 * <p>상태는 메모리에만 있다 — 앱이 다시 뜨면 멈춘 상태로 시작하고, 다시 누르면 남은 것부터 이어 간다. 한 번에 하나만
 * 돈다(이미 돌고 있으면 시작은 지금 상태를 돌려줄 뿐이다).
 */
@Service
public class ClarifiedQuestionBackfill {

    private static final Logger log = LoggerFactory.getLogger(ClarifiedQuestionBackfill.class);

    /** 한 번에 읽어 오는 대상 수 — 다 처리하면 커서 아래로 다음 묶음을 읽는다. */
    static final int BATCH = 20;

    /** 연달아 이만큼 실패하면 멈춘다 — LLM 이 죽어 있는 경우다. */
    static final int MAX_CONSECUTIVE_FAILURES = 5;

    /** 채팅이 마지막으로 바빴던 때에서 이만큼 지나야 다음 호출을 보낸다 — 한 턴 안의 호출 사이 빈틈에 끼어들지 않게. */
    static final Duration QUIET = Duration.ofSeconds(2);

    /** 채팅을 기다리는 동안 게이트를 다시 보는 간격. */
    static final Duration POLL = Duration.ofMillis(250);

    public enum State { IDLE, RUNNING, WAITING_FOR_CHAT, STOPPING, STOPPED, DONE, DISABLED, FAILED }

    /**
     * {@code /admin} 이 그리는 상태 — {@code remaining} 은 지금 남은 대상 수(조회 시점), 나머지는 마지막(또는 진행 중인)
     * 실행의 수치다. {@code running} 이 참이면 화면이 계속 다시 묻는다.
     */
    public record Status(State state, boolean running, int remaining, int processed, int clarified,
                         int keptOriginal, int failed, String startedAt, String finishedAt, String message) {}

    private final QuestionReuseRepository repository;
    private final PostAnswerService postAnswerService;
    private final SettingsService settingsService;
    private final LlmRouter llmRouter;
    private final Duration quiet;
    private final Duration poll;
    private final Object lock = new Object();
    private volatile Run current;

    /** 스프링이 쓰는 생성자 — 생성자가 둘이라 {@code @Autowired} 가 없으면 스프링이 고르지 못해 앱이 뜨지 않는다. */
    @Autowired
    public ClarifiedQuestionBackfill(QuestionReuseRepository repository, PostAnswerService postAnswerService,
                                     SettingsService settingsService, LlmRouter llmRouter) {
        this(repository, postAnswerService, settingsService, llmRouter, QUIET, POLL);
    }

    /** 테스트용 — 양보 시간을 줄인다. */
    ClarifiedQuestionBackfill(QuestionReuseRepository repository, PostAnswerService postAnswerService,
                              SettingsService settingsService, LlmRouter llmRouter, Duration quiet, Duration poll) {
        this.repository = repository;
        this.postAnswerService = postAnswerService;
        this.settingsService = settingsService;
        this.llmRouter = llmRouter;
        this.quiet = quiet;
        this.poll = poll;
    }

    public Status status() {
        int remaining = remaining();
        Run run = current;
        return run == null
                ? new Status(State.IDLE, false, remaining, 0, 0, 0, 0, null, null, null)
                : run.snapshot(remaining);
    }

    /** 시작한다 — 이미 돌고 있으면 그대로 두고, 질문 다듬기 설정이 꺼져 있으면 돌지 않는다. */
    public Status start() {
        synchronized (lock) {
            Run run = current;
            if (run == null || !run.isActive()) {
                Run fresh = new Run(quiet);
                current = fresh;
                if (!settingsService.clarifiedQuestionEnabled()) {
                    fresh.finish(State.DISABLED, null);
                } else {
                    log.info("[CLARIFY-BACKFILL] 시작 — 남은 턴 {}", remaining());
                    Thread.ofVirtual().name("clarify-backfill").start(MdcPropagation.wrap(() -> runLoop(fresh)));
                }
            }
        }
        return status();
    }

    /** 멈추라고 알린다 — 지금 진행 중인 한 건(호출 하나)은 끝까지 간다. */
    public Status stop() {
        Run run = current;
        if (run != null) run.requestStop();
        return status();
    }

    /** 테스트용 — 진행 중인 실행이 끝날 때까지 기다린다. */
    boolean awaitFinish(Duration timeout) throws Exception {
        Run run = current;
        if (run == null) return true;
        try {
            run.done.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (java.util.concurrent.TimeoutException e) {
            return false;
        }
    }

    private int remaining() {
        try {
            return repository.countClarifyBackfillTargets();
        } catch (Exception e) {
            log.debug("[CLARIFY-BACKFILL] 남은 수 조회 실패: {}", e.getMessage());
            return -1;
        }
    }

    private void runLoop(Run run) {
        long cursor = Long.MAX_VALUE;
        int consecutiveFailures = 0;
        try {
            while (true) {
                List<BackfillTarget> batch = repository.findClarifyBackfillTargets(cursor, BATCH);
                if (batch.isEmpty()) {
                    run.finish(State.DONE, null);
                    return;
                }
                for (BackfillTarget target : batch) {
                    cursor = target.turnId();
                    if (run.stopRequested) {
                        run.finish(State.STOPPED, null);
                        return;
                    }
                    if (!settingsService.clarifiedQuestionEnabled()) {
                        run.finish(State.DISABLED, null);
                        return;
                    }
                    if (!waitForQuietChat(run)) {
                        run.finish(State.STOPPED, null);
                        return;
                    }
                    run.setState(State.RUNNING);
                    PostAnswerService.Outcome outcome = postAnswerService.backfill(
                            target.turnId(), target.userId(), target.threadId(), target.question(), target.answer());
                    run.record(outcome);
                    consecutiveFailures = outcome == PostAnswerService.Outcome.FAILED ? consecutiveFailures + 1 : 0;
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        run.finish(State.FAILED, "LLM 호출이 연달아 " + MAX_CONSECUTIVE_FAILURES
                                + "번 실패해 멈췄습니다 — LLM 서버 상태를 확인한 뒤 다시 시작하세요");
                        return;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            run.finish(State.STOPPED, null);
        } catch (RuntimeException e) {
            log.warn("[CLARIFY-BACKFILL] 중단: {}", e.toString());
            run.finish(State.FAILED, e.getMessage());
        } finally {
            // 위에서 잡지 못한 것(Error)으로 빠져나가도 "진행 중"에 묶여 다시 시작할 수 없게 되지 않도록.
            if (!run.done.isDone()) run.finish(State.FAILED, "예상하지 못한 오류로 멈췄습니다");
            log.info("[CLARIFY-BACKFILL] 끝 — {} (처리 {} · 다듬음 {} · 원문 유지 {} · 실패 {})", run.state,
                    run.processed.get(), run.clarified.get(), run.kept.get(), run.failed.get());
        }
    }

    /** 채팅이 조용해질 때까지 기다린다 — 멈추라는 요청이 오면 {@code false}. */
    private boolean waitForQuietChat(Run run) throws InterruptedException {
        while (!run.stopRequested) {
            boolean busy = llmRouter.localTier1Concurrency().map(s -> s.inUse() > 0).orElse(false);
            long now = System.nanoTime();
            if (busy) run.lastBusyNanos = now;
            if (!busy && now - run.lastBusyNanos >= quiet.toNanos()) return true;
            run.setState(State.WAITING_FOR_CHAT);
            Thread.sleep(Math.max(1, poll.toMillis()));
        }
        return false;
    }

    /** 실행 한 번의 상태 — 백그라운드 스레드가 쓰고 요청 스레드가 읽는다. */
    private static final class Run {
        private volatile State state = State.RUNNING;
        private volatile boolean stopRequested;
        private volatile String finishedAt;
        private volatile String message;
        private volatile long lastBusyNanos;
        private final String startedAt = Instant.now().toString();
        private final AtomicInteger processed = new AtomicInteger();
        private final AtomicInteger clarified = new AtomicInteger();
        private final AtomicInteger kept = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();
        private final CompletableFuture<Void> done = new CompletableFuture<>();

        Run(Duration quiet) {
            // "마지막으로 바빴던 때"를 조용한 시간만큼 과거로 둔다 — 시작할 때 채팅이 비어 있으면 바로 시작한다.
            lastBusyNanos = System.nanoTime() - quiet.toNanos();
        }

        boolean isActive() {
            return state == State.RUNNING || state == State.WAITING_FOR_CHAT || state == State.STOPPING;
        }

        void requestStop() {
            stopRequested = true;
            if (isActive()) state = State.STOPPING;
        }

        /** 진행 중 상태 표시 — 멈추라는 요청이 온 뒤에는 "멈추는 중"을 덮지 않는다. */
        void setState(State next) {
            if (!stopRequested) state = next;
        }

        void record(PostAnswerService.Outcome outcome) {
            processed.incrementAndGet();
            switch (outcome) {
                case CLARIFIED -> clarified.incrementAndGet();
                case KEPT_ORIGINAL -> kept.incrementAndGet();
                case FAILED -> failed.incrementAndGet();
            }
        }

        void finish(State end, String reason) {
            message = reason;
            finishedAt = Instant.now().toString();
            state = end;
            done.complete(null);
        }

        Status snapshot(int remaining) {
            State now = state;
            boolean running = now == State.RUNNING || now == State.WAITING_FOR_CHAT || now == State.STOPPING;
            return new Status(now, running, remaining, processed.get(), clarified.get(), kept.get(), failed.get(),
                    startedAt, finishedAt, message);
        }
    }
}
