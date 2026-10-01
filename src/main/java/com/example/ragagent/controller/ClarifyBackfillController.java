package com.example.ragagent.controller;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.service.ClarifiedQuestionBackfill;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * {@code /admin} 의 "과거 질문 다듬기" — 이 기능 이전에 쌓인 재사용 후보 턴의 질문을 한 건씩 다듬는 일괄 처리를
 * 시작·멈춤·조회한다({@link ClarifiedQuestionBackfill}). 경로가 {@code /admin/**} 아래라 모든 인증 모드에서 관리자
 * 전용이다(SecurityConfig). {@code AdminController} 와 따로 둔 이유는 그쪽 생성자에 협력자를 하나 더 얹으면 그
 * 컨트롤러의 슬라이스 테스트 전부가 이 기능과 무관하게 흔들리기 때문이다.
 */
@RestController
public class ClarifyBackfillController {

    private final ClarifiedQuestionBackfill backfill;
    private final AuditLogger auditLogger;

    public ClarifyBackfillController(ClarifiedQuestionBackfill backfill, AuditLogger auditLogger) {
        this.backfill = backfill;
        this.auditLogger = auditLogger;
    }

    @GetMapping("/admin/clarify-backfill")
    public ClarifiedQuestionBackfill.Status status() {
        return backfill.status();
    }

    @PostMapping("/admin/clarify-backfill/start")
    public ClarifiedQuestionBackfill.Status start() {
        ClarifiedQuestionBackfill.Status status = backfill.start();
        auditLogger.log("admin.clarify-backfill.start", "conversation_turns",
                Map.of("state", status.state().name(), "remaining", status.remaining()));
        return status;
    }

    @PostMapping("/admin/clarify-backfill/stop")
    public ClarifiedQuestionBackfill.Status stop() {
        ClarifiedQuestionBackfill.Status status = backfill.stop();
        auditLogger.log("admin.clarify-backfill.stop", "conversation_turns",
                Map.of("state", status.state().name(), "processed", status.processed()));
        return status;
    }
}
