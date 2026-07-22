package com.example.ragagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Convention guard for the parallel test setup (see {@code src/test/resources/junit-platform.properties}).
 *
 * <p>Test classes run concurrently at the class level. Two kinds of test are NOT concurrency-safe
 * in one JVM and must be serialized with {@code @ResourceLock("global-state")}:
 * <ul>
 *   <li><b>Spring-context tests</b> ({@code @WebMvcTest}/{@code @SpringBootTest}) — Spring Boot
 *       re-initializes Logback when a context starts (wiping programmatic appenders), and
 *       {@code @MockitoBean} reset is not safe across concurrent contexts.</li>
 *   <li><b>Log-capture tests</b> (a {@code ListAppender} attached to a shared logger) — a
 *       concurrent Spring-context start re-inits logging and drops the appender, so the captured
 *       list comes back empty.</li>
 * </ul>
 * This guard fails the build if such a class is missing the lock, so a newly added test can't
 * silently reintroduce the flaky failures the lock was added to fix.
 */
class ParallelIsolationConventionTest {

    private static final Path TEST_ROOT = Path.of("src/test/java");
    private static final String LOCK = "@ResourceLock(\"global-state\")";

    @Test
    @DisplayName("Spring-context / 로그 캡처 테스트는 @ResourceLock(\"global-state\")를 가져야 한다 (병렬 격리)")
    void statefulTestsCarryGlobalStateLock() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(TEST_ROOT)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    // this guard itself contains the trigger strings only as literals
                    .filter(p -> !p.getFileName().toString().equals("ParallelIsolationConventionTest.java"))
                    .forEach(p -> {
                        String src;
                        try {
                            src = Files.readString(p);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                        boolean needsLock = src.contains("@WebMvcTest")
                                || src.contains("@SpringBootTest")
                                || src.contains("ListAppender")
                                || src.contains(".addAppender(");
                        if (needsLock && !src.contains(LOCK)) {
                            violations.add(p.toString().replace("src/test/java/", ""));
                        }
                    });
        }
        assertThat(violations)
                .as("병렬 실행에서 플래키를 유발하는 테스트입니다 — 클래스에 %s 를 추가하세요", LOCK)
                .isEmpty();
    }
}
