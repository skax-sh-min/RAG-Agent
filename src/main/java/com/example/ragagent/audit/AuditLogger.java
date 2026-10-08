package com.example.ragagent.audit;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.security.ClientIpResolver;
import com.example.ragagent.security.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 감사 이벤트를 전용 롤링 파일(AUDIT logger → logback-spring.xml AUDIT_FILE appender)에 기록.
 * Logback이 스레드 안전성·파일 로테이션·자동 삭제를 모두 처리한다.
 */
@Component
public class AuditLogger {

    // logback-spring.xml의 <logger name="AUDIT"> 와 연결
    private static final Logger auditLog = LoggerFactory.getLogger("AUDIT");

    private final ObjectMapper mapper;
    private final AppProperties props;
    private final CurrentUser currentUser;
    private final ClientIpResolver clientIpResolver;

    public AuditLogger(ObjectMapper mapper, AppProperties props, CurrentUser currentUser,
                       ClientIpResolver clientIpResolver) {
        this.mapper = mapper;
        this.props = props;
        this.currentUser = currentUser;
        this.clientIpResolver = clientIpResolver;
    }

    public void log(String action, String resource) {
        log(action, resource, null);
    }

    public void log(String action, String resource, Map<String, Object> details) {
        if (!props.auditSafe().enabled()) return;

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("ts", Instant.now().toString());
        event.put("user", currentUser.userId());
        event.put("action", action);
        if (resource != null) event.put("resource", resource);
        String ip = extractIp();
        if (ip != null) event.put("ip", ip);
        if (details != null && !details.isEmpty()) event.put("details", details);

        try {
            auditLog.info(mapper.writeValueAsString(event));
        } catch (Exception ignored) {
            // 감사 로그 실패는 본 요청에 영향을 주지 않음
        }
    }

    /**
     * IP는 HTTP 요청 스레드에서만 추출 가능 — virtual thread 내부에서 호출 시 null 반환.
     *
     * <p><b>{@link ClientIpResolver} 를 쓴다.</b> 예전에는 여기서 {@code X-Forwarded-For} 를
     * <b>무조건</b> 신뢰했다 — {@code app.trust-forwarded-for} 가 있는데도 보지 않았으므로,
     * 프록시 없는 배포(그 값이 {@code false} 인 정상 설정)에서 감사 기록의 IP 가 곧 공격자가 적어
     * 보낸 문자열이었다. 같은 요청에서 레이트리밋과 게스트 식별은 실제 remote address 를 쓰고
     * 있었으니, 셋 중 감사 로그만 다른 값을 믿고 있던 셈이다 — "누가 무엇을 했는가"를 남기는 것이
     * 목적인 로그에서 그 필드가 가장 위조하기 쉬웠다.
     */
    private String extractIp() {
        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes sra) {
                HttpServletRequest req = sra.getRequest();
                return clientIpResolver.resolve(req);
            }
        } catch (Exception ignored) {}
        return null;
    }
}
