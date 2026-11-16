package com.example.ragagent.service;

import com.example.ragagent.web.MdcPropagation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * 앱을 띄운 런처가 사라지면 앱도 스스로 끝낸다 — <b>고아가 되어 포트를 쥐고 남지 않게</b>.
 *
 * <p>런처는 앱을 자식 프로세스로 띄운다. 런처가 정상 종료(Ctrl+C·SIGTERM·콘솔 닫기)하면 종료 훅이 자식을 먼저 멈추지만, <b>강제 종료</b>
 * (작업 관리자의 "작업 끝내기"·{@code kill -9}·OOM kill)에는 훅이 돌지 않아 앱만 남는다(Windows 에서 실측: 런처를 강제로 끝낸 뒤에도 앱이
 * 계속 응답했다). 그 앱은 포트를 쥐고 있어 새 런처가 띄운 앱과 충돌하고, 업데이트를 눌러도 종료 코드 42 를 받아 줄 부모가 없다.
 * 그래서 런처가 넘겨 준 {@code RAG_LAUNCHER_PID} 를 몇 초마다 살펴, 사라졌으면 컨텍스트를 정상 종료하고 끝낸다.
 *
 * <p><b>PID 재사용</b>도 막는다 — 런처가 {@code RAG_LAUNCHER_STARTED}(시작 시각)도 넘기고, 같은 PID 라도 시작 시각이 다르면 다른 프로세스로 본다.
 * 런처 밖에서 실행하면(두 값이 없다) 아무것도 하지 않는다.
 */
@Component
public class LauncherWatchdog {

    private static final Logger log = LoggerFactory.getLogger(LauncherWatchdog.class);

    private final ApplicationContext context;
    private final String pidText;
    private final String startedText;
    private final long intervalMillis;

    public LauncherWatchdog(ApplicationContext context,
                            @Value("${app.update.launcher-pid:}") String pidText,
                            @Value("${app.update.launcher-started:}") String startedText,
                            @Value("${app.update.launcher-watch-seconds:5}") long intervalSeconds) {
        this.context = context;
        this.pidText = pidText == null ? "" : pidText.strip();
        this.startedText = startedText == null ? "" : startedText.strip();
        this.intervalMillis = Math.max(1, intervalSeconds) * 1000L;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        Optional<Long> pid = watchedPid();
        if (pid.isEmpty()) return;
        log.info("[LAUNCHER] 런처(pid {})가 사라지면 이 앱도 끝낸다", pid.get());
        Thread t = Thread.ofVirtual().name("launcher-watchdog").unstarted(MdcPropagation.wrap(this::watch));
        t.start();
    }

    private void watch() {
        try {
            while (launcherAlive()) Thread.sleep(intervalMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        log.warn("[LAUNCHER] 런처(pid {})가 사라졌다 — 고아로 남지 않도록 앱을 끝낸다", pidText);
        int code = SpringApplication.exit(context, () -> 0);
        System.exit(code);
    }

    /** 감시할 런처의 PID — 값이 없거나 숫자가 아니면 비어 있다(런처 밖에서 실행한 경우). */
    Optional<Long> watchedPid() {
        if (pidText.isEmpty()) return Optional.empty();
        try {
            long pid = Long.parseLong(pidText);
            return pid > 0 ? Optional.of(pid) : Optional.empty();
        } catch (NumberFormatException e) {
            log.warn("[LAUNCHER] app.update.launcher-pid 를 읽을 수 없어 런처 감시를 끈다: {}", pidText);
            return Optional.empty();
        }
    }

    /** 런처가 아직 살아 있는가. 감시 대상이 없으면 (끝낼 이유가 없으므로) 살아 있다고 본다. */
    boolean launcherAlive() {
        Optional<Long> pid = watchedPid();
        if (pid.isEmpty()) return true;
        Optional<ProcessHandle> handle = ProcessHandle.of(pid.get());
        if (handle.isEmpty() || !handle.get().isAlive()) return false;
        if (startedText.isEmpty()) return true;
        try {
            Instant expected = Instant.parse(startedText);
            Optional<Instant> actual = handle.get().info().startInstant();
            // 시작 시각을 알 수 없는 환경이면 PID 만으로 판단한다. 알 수 있는데 다르면 PID 를 다른 프로세스가 물려받은 것이다.
            return actual.isEmpty() || actual.get().equals(expected);
        } catch (RuntimeException e) {
            return true;
        }
    }
}
