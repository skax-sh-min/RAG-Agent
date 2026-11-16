package com.example.ragagent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 런처가 사라졌는지 알아보는 판정 — 실제로 프로세스를 띄워 강제로 죽여 본다. (종료 자체는 {@code System.exit} 라 단위 테스트로 볼 수 없고,
 * 실제 앱 + 런처로 확인했다 — PITFALLS.)
 */
class LauncherWatchdogTest {

    private static LauncherWatchdog watchdog(String pid, String started) {
        return new LauncherWatchdog(mock(ApplicationContext.class), pid, started, 5);
    }

    private static Process sleepingProcess() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("windows") ? "java.exe" : "java").toString();
        return new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), SleepingProcess.class.getName()).start();
    }

    @Test
    @DisplayName("런처 밖에서 실행했거나(값 없음) 값을 읽을 수 없으면 감시하지 않는다 — 끝낼 이유가 없으므로 '살아 있다'")
    void noWatchWithoutAValidPid() {
        for (String bad : new String[]{"", "   ", "abc", "-5", "0", "12x"}) {
            LauncherWatchdog w = watchdog(bad, "");
            assertThat(w.watchedPid()).as("'" + bad + "'").isEmpty();
            assertThat(w.launcherAlive()).as("'" + bad + "'").isTrue();
        }
        assertThat(new LauncherWatchdog(mock(ApplicationContext.class), null, null, 5).watchedPid()).isEmpty();
    }

    @Test
    @DisplayName("살아 있는 프로세스는 살아 있다고 본다 — 시작 시각이 맞으면 통과")
    void aLiveProcessIsAlive() {
        ProcessHandle self = ProcessHandle.current();
        String started = self.info().startInstant().map(Instant::toString).orElse("");

        assertThat(watchdog(String.valueOf(self.pid()), "").launcherAlive()).isTrue();
        assertThat(watchdog(String.valueOf(self.pid()), started).launcherAlive()).isTrue();
        assertThat(watchdog(String.valueOf(self.pid()), "not-an-instant").launcherAlive()).as("읽을 수 없는 시각은 PID 만으로 판단").isTrue();
    }

    @Test
    @DisplayName("PID 는 같지만 시작 시각이 다르면(PID 를 다른 프로세스가 물려받음) 런처가 사라진 것이다")
    void aReusedPidIsNotTheLauncher() {
        ProcessHandle self = ProcessHandle.current();
        Assumptions_assumeStartInstant(self);
        String other = Instant.parse("2001-01-01T00:00:00Z").toString();

        assertThat(watchdog(String.valueOf(self.pid()), other).launcherAlive()).isFalse();
    }

    private static void Assumptions_assumeStartInstant(ProcessHandle h) {
        org.junit.jupiter.api.Assumptions.assumeTrue(h.info().startInstant().isPresent(), "이 환경은 프로세스 시작 시각을 알려 주지 않는다");
    }

    @Test
    @DisplayName("이미 끝난 프로세스는 사라진 것이다")
    void anExitedProcessIsGone() throws Exception {
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("windows") ? "java.exe" : "java").toString(), "-version")
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();

        assertThat(watchdog(String.valueOf(p.pid()), "").launcherAlive()).isFalse();
    }

    @Test
    @DisplayName("살아 있다가 강제로 죽은 프로세스를 알아챈다 — 강제 종료된 런처 뒤에 앱이 고아로 남는 상황")
    void aForciblyKilledLauncherIsNoticed() throws Exception {
        Process launcher = sleepingProcess();
        try {
            ProcessHandle handle = launcher.toHandle();
            String started = handle.info().startInstant().map(Instant::toString).orElse("");
            LauncherWatchdog w = watchdog(String.valueOf(launcher.pid()), started);
            assertThat(w.launcherAlive()).isTrue();

            launcher.destroyForcibly();                     // 종료 훅이 돌지 못하는 강제 종료
            assertThat(launcher.waitFor(30, TimeUnit.SECONDS)).isTrue();

            assertThat(w.launcherAlive()).isFalse();
        } finally {
            launcher.destroyForcibly();
        }
    }
}
