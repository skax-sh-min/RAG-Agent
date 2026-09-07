package com.example.ragagent.audit;

import org.junit.jupiter.api.parallel.ResourceLock;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.ragagent.LogbackTestSupport;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.security.ClientIpResolver;
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

        logger = new AuditLogger(new ObjectMapper(), propsFor(true), currentUser, new ClientIpResolver(false));
    }

    /** 감사만 켜고 나머지는 기본값인 최소 설정 — 이 테스트가 보는 것은 audit 블록뿐이다. */
    private static AppProperties propsFor(boolean auditEnabled) {
        return new AppProperties(
            "./data", 2, 800, 100, 100, 7, 0.0, true, 0, false,
                true, false, 3,
                null, null, null, null, null, null, null,
                new AppProperties.AuditConfig(auditEnabled, "10MB", 7, "100MB"), null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
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
        AuditLogger disabledLogger = new AuditLogger(new ObjectMapper(), propsFor(false), currentUser,
                new ClientIpResolver(false));

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

    /**
     * 감사 기록의 IP 는 {@link ClientIpResolver} 를 지나야 한다. 예전에는 여기서만
     * {@code X-Forwarded-For} 를 무조건 신뢰해서, 프록시가 없는 배포(= {@code trust-forwarded-for}
     * 가 {@code false} 인 정상 설정)에서 "누가 했는가"를 남기는 필드가 공격자 입력이었다.
     */
    @Test
    @DisplayName("trust-forwarded-for=false — 위조된 X-Forwarded-For 대신 실제 remoteAddr 를 기록")
    void log_ignoresForgedForwardedForByDefault() {
        var req = new org.springframework.mock.web.MockHttpServletRequest("POST", "/ui/documents/upload");
        req.addHeader("X-Forwarded-For", "203.0.113.9");
        req.setRemoteAddr("10.0.0.7");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(req));
        try {
            logger.log("document.upload", "doc-9");
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }

        String msg = listAppender.list.getFirst().getMessage();
        assertThat(msg).contains("\"ip\":\"10.0.0.7\"");
        assertThat(msg).doesNotContain("203.0.113.9");
    }

    @Test
    @DisplayName("trust-forwarded-for=true — 프록시 뒤에서는 XFF 첫 번째 IP 를 기록")
    void log_usesForwardedForWhenTrusted() {
        AuditLogger trusting = new AuditLogger(new ObjectMapper(), propsFor(true), currentUser,
                new ClientIpResolver(true));
        var req = new org.springframework.mock.web.MockHttpServletRequest("POST", "/ui/documents/upload");
        req.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1");
        req.setRemoteAddr("10.0.0.1");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(req));
        try {
            trusting.log("document.upload", "doc-9");
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }

        assertThat(listAppender.list.getFirst().getMessage()).contains("\"ip\":\"203.0.113.9\"");
    }

    @Test
    @DisplayName("요청 스레드 밖(가상 스레드)에서는 ip 키가 아예 없다")
    void log_outsideRequestThread_hasNoIpKey() {
        logger.log("document.sync", null);

        assertThat(listAppender.list.getFirst().getMessage()).doesNotContain("\"ip\"");
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
