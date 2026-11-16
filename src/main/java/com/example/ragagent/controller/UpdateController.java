package com.example.ragagent.controller;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.service.UpdateCheckService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code /admin} 의 「새 버전」 카드 — 새 릴리즈가 있는지 보이고 지금 한 번 확인하게 한다({@link UpdateCheckService}).
 * 경로가 {@code /admin/**} 아래라 모든 인증 모드에서 관리자 전용이다(SecurityConfig). {@code DatabaseBackupController} 처럼
 * {@code AdminController} 와 따로 둔다 — 그쪽 생성자에 협력자를 얹으면 슬라이스 테스트 전부가 흔들린다.
 */
@RestController
public class UpdateController {

    private final UpdateCheckService updates;
    private final AuditLogger auditLogger;

    public UpdateController(UpdateCheckService updates, AuditLogger auditLogger) {
        this.updates = updates;
        this.auditLogger = auditLogger;
    }

    @GetMapping("/admin/update")
    public UpdateCheckService.Status status() {
        return updates.status();
    }

    /** 지금 확인 — 끝날 때까지 기다렸다가 갱신된 상태를 돌려준다. 꺼져 있거나 설정이 틀리면 409(요청은 나가지 않는다). */
    @PostMapping("/admin/update/check")
    public UpdateCheckService.Status check() {
        UpdateCheckService.Status status;
        try {
            status = updates.check("manual");
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        Map<String, Object> details = new HashMap<>();
        details.put("ok", status.last() != null && status.last().ok());
        details.put("updateAvailable", status.updateAvailable());
        if (status.latest() != null) details.put("latest", status.latest().version());
        if (status.last() != null && status.last().error() != null) details.put("error", status.last().error());
        auditLogger.log("admin.update.check", status.repo(), details);
        return status;
    }

    /** 헤더 배지가 60초마다 묻는 값 — 최소한만 돌려준다(릴리즈 노트는 카드에서만). */
    @GetMapping("/admin/update/available")
    public Map<String, Object> available() {
        UpdateCheckService.Status status = updates.status();
        Map<String, Object> body = new HashMap<>();
        body.put("available", status.updateAvailable());
        body.put("version", status.updateAvailable() && status.latest() != null ? status.latest().version() : null);
        return body;
    }
}
