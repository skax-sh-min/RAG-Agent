package com.example.ragagent.tool;

import com.example.ragagent.audit.AuditLogger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Tool 권한 검사(AGENT_MCP_DESIGN §2.2) — 허용 표, 거부 시 예외와 감사, 호출자 복원. */
class ToolGuardTest {

    private static final ToolCaller GUEST = new ToolCaller("anonymous", false, "chat");
    private static final ToolCaller ADMIN = new ToolCaller("root", true, "mcp");

    @Test
    @DisplayName("PUBLIC 은 게스트도 되고, ADMIN 은 관리자만 된다 — null 호출자는 게스트로 본다")
    void accessMatrix() {
        assertThat(ToolGuard.allows(GUEST, ToolAccess.PUBLIC)).isTrue();
        assertThat(ToolGuard.allows(ADMIN, ToolAccess.PUBLIC)).isTrue();
        assertThat(ToolGuard.allows(GUEST, ToolAccess.ADMIN)).isFalse();
        assertThat(ToolGuard.allows(ADMIN, ToolAccess.ADMIN)).isTrue();
        assertThat(ToolGuard.allows(null, ToolAccess.ADMIN)).isFalse();
        assertThat(ToolGuard.allows(null, ToolAccess.PUBLIC)).isTrue();
    }

    @Test
    @DisplayName("거부하면 ToolDeniedException 과 tool.denied 감사 기록, 허용하면 tool.call 감사 기록")
    void deniesAndAudits() {
        AuditLogger audit = mock(AuditLogger.class);
        ToolGuard guard = new ToolGuard(audit);

        assertThatThrownBy(() -> guard.require(GUEST, ToolAccess.ADMIN, "getDocument"))
                .isInstanceOf(ToolDeniedException.class)
                .hasMessageContaining("getDocument");
        verify(audit).log(eq("tool.denied"), eq("getDocument"), anyMap());

        guard.require(ADMIN, ToolAccess.ADMIN, "getDocument");
        verify(audit).log(eq("tool.call"), eq("getDocument"), anyMap());
    }

    @Test
    @DisplayName("호출자가 null 이면 게스트로 검사한다 — 관리자 Tool 은 거부")
    void nullCallerIsGuest() {
        ToolGuard guard = new ToolGuard(mock(AuditLogger.class));
        assertThatThrownBy(() -> guard.require(null, ToolAccess.ADMIN, "getDocument")).isInstanceOf(ToolDeniedException.class);
        assertThatCode(() -> guard.require(null, ToolAccess.PUBLIC, "searchKnowledge")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("감사 기록이 실패해도 판단은 바뀌지 않는다 — 거부는 거부, 허용은 허용")
    void auditFailureDoesNotChangeTheDecision() {
        AuditLogger audit = mock(AuditLogger.class);
        doThrow(new IllegalStateException("로그 파일 쓰기 실패")).when(audit).log(any(), any(), anyMap());
        ToolGuard guard = new ToolGuard(audit);

        assertThatThrownBy(() -> guard.require(GUEST, ToolAccess.ADMIN, "getDocument")).isInstanceOf(ToolDeniedException.class);
        assertThatCode(() -> guard.require(GUEST, ToolAccess.PUBLIC, "searchKnowledge")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("거부 예외 메시지는 도구 이름만 담는다 — 내부 사정(역할·경로)을 싣지 않는다")
    void deniedMessageIsPlain() {
        assertThat(new ToolDeniedException("getDocument").getMessage()).isEqualTo("이 도구를 사용할 권한이 없습니다: getDocument");
    }

    @Test
    @DisplayName("호출자는 ToolContext 로 오간다 — 없거나 타입이 틀리면 게스트(권한이 모자란 쪽으로 실패)")
    void callerRoundTripsThroughTheContext() {
        assertThat(ToolCaller.from(ADMIN.toContext())).isEqualTo(ADMIN);
        assertThat(ToolCaller.from(null)).isEqualTo(ToolCaller.guest());
        assertThat(ToolCaller.from(new ToolContext(Map.of()))).isEqualTo(ToolCaller.guest());
        assertThat(ToolCaller.from(new ToolContext(Map.of(ToolCaller.CONTEXT_KEY, "admin")))).isEqualTo(ToolCaller.guest());
        assertThat(ToolCaller.guest().admin()).isFalse();
        assertThat(new ToolCaller(" ", true, null)).extracting(ToolCaller::userId, ToolCaller::channel)
                .containsExactly("anonymous", "chat");
    }
}
