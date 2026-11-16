package com.example.ragagent.controller;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.service.UpdateApplyService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code /admin} 「새 버전」 카드의 <b>적용</b> 쪽 — 업데이트를 시작하고 진행·마지막 결과를 보인다({@link UpdateApplyService}).
 * 확인(알림) 쪽 {@link UpdateController} 와 따로 둔다: 알림은 읽기뿐이지만 이쪽은 앱을 재시작시키므로 경로·테스트·감사 기록이 갈린다.
 * 모든 경로가 {@code /admin/**} 라 모든 인증 모드에서 관리자 전용이고, POST 는 CSRF 토큰이 필요하다.
 */
@RestController
public class UpdateApplyController {

    private final UpdateApplyService apply;
    private final AuditLogger auditLogger;

    public UpdateApplyController(UpdateApplyService apply, AuditLogger auditLogger) {
        this.apply = apply;
        this.auditLogger = auditLogger;
    }

    /** 적용 가능 여부·사유, 진행 중인 작업, 감독 스크립트가 남긴 마지막 결과. */
    @GetMapping("/admin/update/apply")
    public UpdateApplyService.View view() {
        return apply.view();
    }

    /**
     * 업데이트 시작 — {@code version} 은 관리자가 화면에서 본 최신 버전이어야 한다(그사이 바뀌었으면 409). 받기·검증·백업은
     * 백그라운드로 이어지므로 진행 상태를 바로 돌려주고, 화면이 {@code GET /admin/update/apply} 를 다시 물어 따라간다.
     * 조건이 안 맞으면(감독 스크립트 아님·새 버전 없음·다른 작업 진행 중 …) 409 와 사유.
     */
    @PostMapping("/admin/update/apply")
    public UpdateApplyService.View start(@RequestParam(value = "version", required = false) String version) {
        // required=false + 직접 400: 빠진 파라미터의 기본 예외는 이 앱의 공통 처리기에서 500 이 된다
        if (version == null || version.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "version 이 필요합니다 — 화면에서 확인한 최신 버전을 보내세요.");
        }
        UpdateApplyService.View before = apply.view();
        try {
            apply.start(version);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        Map<String, Object> details = new HashMap<>();
        details.put("to", version);
        UpdateApplyService.SupervisorResult last = before.lastResult();
        if (last != null) details.put("previousAttempt", last.status() + " " + last.version());
        auditLogger.log("admin.update.apply", version, details);
        return apply.view();
    }
}
