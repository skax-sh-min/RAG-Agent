package com.example.ragagent.controller;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.service.EmbeddingRebuildService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Map;

/**
 * {@code /admin} 의 "임베딩 재생성" 카드(sqlite-vec 전용) — 상태 조회·시작·중단({@link EmbeddingRebuildService}).
 * 경로가 {@code /admin/**} 아래라 모든 인증 모드에서 관리자 전용이다(SecurityConfig). {@code AdminController} 와 따로 둔
 * 이유는 {@link DatabaseBackupController} 와 같다 — 그쪽 생성자에 협력자를 얹으면 슬라이스 테스트 전부가 흔들린다.
 */
@RestController
@ConditionalOnProperty(name = "app.vectorstore.type", havingValue = "sqlite-vec")
public class EmbeddingRebuildController {

    private final EmbeddingRebuildService service;
    private final AuditLogger auditLogger;

    public EmbeddingRebuildController(EmbeddingRebuildService service, AuditLogger auditLogger) {
        this.service = service;
        this.auditLogger = auditLogger;
    }

    @GetMapping("/admin/embedding-rebuild")
    public EmbeddingRebuildService.Status status() {
        return service.status();
    }

    /** {@code mode=rebuild}(벡터 테이블을 다시 만들고 전체) 또는 {@code resume}(벡터 없는 청크만). */
    @PostMapping("/admin/embedding-rebuild/start")
    public EmbeddingRebuildService.Status start(@RequestParam String mode) {
        EmbeddingRebuildService.Mode m;
        try {
            m = EmbeddingRebuildService.Mode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("mode 는 rebuild 또는 resume 이어야 합니다.");
        }
        EmbeddingRebuildService.Status status;
        try {
            status = service.start(m);
        } catch (IllegalStateException busy) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, busy.getMessage());
        }
        auditLogger.log("admin.embedding-rebuild.start", "vec_embeddings",
                Map.of("mode", m.name(), "tableDimension", String.valueOf(status.tableDimension()),
                        "configuredDimension", String.valueOf(status.configuredDimension()),
                        "totalChunks", status.totalChunks()));
        return status;
    }

    @PostMapping("/admin/embedding-rebuild/cancel")
    public EmbeddingRebuildService.Status cancel() {
        boolean requested = service.cancel();
        auditLogger.log("admin.embedding-rebuild.cancel", "vec_embeddings", Map.of("requested", requested));
        return service.status();
    }
}
