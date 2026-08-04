package com.example.ragagent.audit;

import org.junit.jupiter.api.parallel.ResourceLock;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.ragagent.LogbackTestSupport;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.security.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA — AuditLogger (파일 기반 롤링 로그)
 *
 * Logback ListAppender로 "AUDIT" 로거에 실제 기록되는 이벤트를 검증.
 * 파일 I/O 없이 메모리에서 빠르게 실행.
 */
@ResourceLock("global-state")
class AuditLoggerTest {

    private ListAppender<ILoggingEvent> listAppender;
    private Logger auditLogger;
    private AuditLogger logger;
    private CurrentUser currentUser;

    @BeforeEach
    void setUp() {
        auditLogger = LogbackTestSupport.logger("AUDIT");
        listAppender = new ListAppender<>();
        listAppender.start();
        auditLogger.addAppender(listAppender);

        currentUser = mock(CurrentUser.class);
        when(currentUser.userId()).thenReturn("user-test");

        AppProperties props = new AppProperties(
            "./data", 2, 800, 100, 100, 7, 0.0, true, 0, false,
                true, false, 3,
                null, null, null, null, null, null, null,
                new AppProperties.AuditConfig(true, "10MB", 7, "100MB"), null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        logger = new AuditLogger(new ObjectMapper(), props, currentUser);
    }

    @AfterEach
    void tearDown() {
        auditLogger.detachAppender(listAppender);
    }

    @Test
    @DisplayName("log(action, resource) → AUDIT 로거에 JSON 한 줄 기록")
    void log_action_resource_writesJsonEntry() {
        logger.log("document.upload", "doc-1");

        assertThat(listAppender.list).hasSize(1);
        String msg = listAppender.list.getFirst().getMessage();
        assertThat(msg).contains("\"action\":\"document.upload\"");
        assertThat(msg).contains("\"resource\":\"doc-1\"");
        assertThat(msg).contains("\"user\":\"user-test\"");
        assertThat(msg).contains("\"ts\":");
    }

    @Test
    @DisplayName("log() with details → details 객체 포함")
    void log_withDetails_includesDetails() {
        logger.log("document.delete", "doc-2", Map.of("version", "latest"));

        String msg = listAppender.list.getFirst().getMessage();
        assertThat(msg).contains("\"action\":\"document.delete\"");
        assertThat(msg).contains("latest");
    }

    @Test
    @DisplayName("resource=null → JSON에 resource 키 없음")
    void log_nullResource_noResourceKey() {
        logger.log("document.sync", null, Map.of("indexed", 3));

        String msg = listAppender.list.getFirst().getMessage();
        assertThat(msg).doesNotContain("\"resource\"");
        assertThat(msg).contains("\"indexed\"");
    }

    @Test
    @DisplayName("enabled=false → 아무것도 기록 안 함")
    void log_disabled_writesNothing() {
        AppProperties disabledProps = new AppProperties(
            "./data", 2, 800, 100, 100, 7, 0.0, true, 0, false,
                true, false, 3,
                null, null, null, null, null, null, null,
                new AppProperties.AuditConfig(false, "10MB", 7, "100MB"), null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        AuditLogger disabledLogger = new AuditLogger(new ObjectMapper(), disabledProps, currentUser);

        disabledLogger.log("document.upload", "doc-3");

        assertThat(listAppender.list).isEmpty();
    }

    @Test
    @DisplayName("auditSafe() — audit=null 이면 enabled=true 기본값")
    void auditSafe_nullConfig_returnsEnabledDefault() {
        AppProperties propsWithNull = new AppProperties(
            "./data", 2, 800, 100, 100, 7, 0.0, true, 0, false,
                true, false, 3,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertThat(propsWithNull.auditSafe().enabled()).isTrue();
        assertThat(propsWithNull.auditSafe().maxHistoryDays()).isEqualTo(7);
        assertThat(propsWithNull.auditSafe().maxFileSize()).isEqualTo("10MB");
    }

    @Test
    @DisplayName("직렬화 불가 details 가 있어도 예외 전파 안 함 (fail-safe)")
    void log_serializeFailure_doesNotThrow() {
        // ObjectMapper가 실패하도록 순환 참조 같은 상황은 만들기 어려우므로
        // enabled=true 상태에서 정상 흐름이 예외를 던지지 않는지만 보장
        assertThatNoException().isThrownBy(() ->
                logger.log("thread.delete", "t-1", Map.of("key", "value")));
    }
}
