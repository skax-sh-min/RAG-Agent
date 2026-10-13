package com.example.ragagent.controller;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.service.DatabaseBackupService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code /admin} 의 "DB 백업" 카드 — 정기 백업의 상태를 보이고 지금 한 번 뜨게 한다({@link DatabaseBackupService}).
 * 경로가 {@code /admin/**} 아래라 모든 인증 모드에서 관리자 전용이다(SecurityConfig). {@code AdminController} 와 따로 둔
 * 이유는 {@code ClarifyBackfillController} 와 같다 — 그쪽 생성자에 협력자를 얹으면 슬라이스 테스트 전부가 흔들린다.
 */
@RestController
public class DatabaseBackupController {

    private final DatabaseBackupService backups;
    private final AuditLogger auditLogger;

    public DatabaseBackupController(DatabaseBackupService backups, AuditLogger auditLogger) {
        this.backups = backups;
        this.auditLogger = auditLogger;
    }

    @GetMapping("/admin/db-backup")
    public DatabaseBackupService.Status status() {
        return backups.status();
    }

    /** 지금 백업 — 끝날 때까지 기다렸다가 갱신된 상태를 돌려준다(수십 MB 면 1초 안쪽). */
    @PostMapping("/admin/db-backup/run")
    public DatabaseBackupService.Status run() {
        DatabaseBackupService.RunResult result = backups.run("manual");
        Map<String, Object> details = new HashMap<>();
        details.put("ok", result.ok());
        details.put("file", result.file());
        details.put("bytes", result.bytes());
        if (!result.ok()) details.put("error", result.error());
        auditLogger.log("admin.db-backup.run", "memory.db", details);
        return backups.status();
    }
}
