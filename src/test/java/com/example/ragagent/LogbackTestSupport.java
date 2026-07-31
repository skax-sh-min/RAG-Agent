package com.example.ragagent;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

/**
 * Obtains a Logback {@link Logger} for a log-capture test, waiting out SLF4J's binding race first.
 *
 * <p>Test CLASSES run in parallel (see {@code src/test/resources/junit-platform.properties}), so
 * whichever class touches logging first triggers SLF4J initialization while others are already
 * calling {@code LoggerFactory.getLogger(...)}. During that window SLF4J's state is
 * {@code ONGOING_INITIALIZATION} and <em>every</em> caller — including
 * {@link LoggerFactory#getILoggerFactory()} — gets a substitute (
 * {@code org.slf4j.helpers.SubstituteLogger} / {@code SubstituteLoggerFactory}) rather than the
 * Logback implementation. Casting that substitute to a Logback {@code Logger} throws
 * {@link ClassCastException}, which is exactly how {@code AuditLoggerTest} failed intermittently
 * (all 6 methods erroring in {@code @BeforeEach}, passing on the very next run).
 *
 * <p>{@code @ResourceLock("global-state")} does not help: it serializes the appender-attaching tests
 * against each other, but the initializing thread here is some other, unlocked class starting up.
 * So instead of ordering the tests, this waits for the binding itself to finish.
 *
 * <p>Use this instead of casting {@code LoggerFactory.getLogger(...)} directly in any test that
 * attaches a {@code ListAppender}.
 */
public final class LogbackTestSupport {

    /** Initialization takes milliseconds; this ceiling only exists so a genuinely broken logging
     *  setup fails with a clear message instead of hanging the build. */
    private static final long BINDING_TIMEOUT_MS = 10_000;
    private static final long POLL_INTERVAL_MS = 10;

    private LogbackTestSupport() {}

    /** Logback logger for {@code name}, e.g. the "AUDIT" appender-backed logger. */
    public static Logger logger(String name) {
        awaitLogbackBinding();
        return (Logger) LoggerFactory.getLogger(name);
    }

    /** Logback logger for {@code type}'s own logger — the usual "capture what this class logs" case. */
    public static Logger logger(Class<?> type) {
        awaitLogbackBinding();
        return (Logger) LoggerFactory.getLogger(type);
    }

    /**
     * Blocks until SLF4J has finished binding to Logback, so the caller's cast is safe. Returns
     * immediately once bound (the steady state for every call after the first), and never throws on
     * interruption — it restores the interrupt flag and lets the cast proceed, so a cancelled test
     * run fails on its own terms rather than here.
     */
    private static void awaitLogbackBinding() {
        long deadline = System.currentTimeMillis() + BINDING_TIMEOUT_MS;
        while (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext)) {
            if (System.currentTimeMillis() > deadline) {
                ILoggerFactory actual = LoggerFactory.getILoggerFactory();
                throw new IllegalStateException(
                        "SLF4J did not bind to Logback within " + BINDING_TIMEOUT_MS + "ms — factory is "
                                + actual.getClass().getName() + ". Log-capture tests need the Logback backend.");
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
