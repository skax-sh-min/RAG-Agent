package com.example.ragagent.controller;

import com.example.ragagent.context.ThreadContext;
import com.example.ragagent.service.ChunkReportService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

/**
 * §10.14 — 채팅 출처의 "내용 오류 신고" 접수(사용자 쪽 문 하나). 관리자 쪽 조회·처리는
 * {@link AdminController} 에 있다(다른 인가 경계이므로 컨트롤러도 나눈다).
 *
 * <p><b>{@code /api/v1/**} 아래 두지 않는다</b>: 그 접두사는 CSRF 예외이고 management-only 모드에서
 * 게스트에게 열려 있어, 쓰기 엔드포인트를 거기 두면 크로스사이트로 대기열을 채울 수 있다. 채팅의
 * 다른 사용자 쓰기(피드백·출처 숨김)와 같은 {@code /ui/**} 를 쓴다 — 게스트도 부를 수 있고 CSRF
 * 토큰은 요구된다.
 *
 * <p>경로에 {@code /chat}·{@code /documents} 문자열을 넣지 않는 것도 의도다 —
 * {@code RateLimitFilter.policyFor()} 는 경로 부분 문자열로 버킷을 고르므로, 신고가 채팅이나 업로드
 * 버킷의 토큰을 먹으면 정작 질문이 429 로 막힌다. 신고는 {@code default} 버킷을 쓴다.
 */
@Controller
public class ChunkReportController {

    private final ChunkReportService chunkReportService;

    public ChunkReportController(ChunkReportService chunkReportService) {
        this.chunkReportService = chunkReportService;
    }

    /**
     * 신고 접수. 검증 실패는 {@link IllegalArgumentException} → 400(ProblemDetail)이고, 이미 이
     * 대화에서 신고한 청크는 <b>409</b> 다 — 오류가 아니라 "이미 접수됨"이라 화면 문구가 다르다.
     */
    @PostMapping("/ui/chunk-reports")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> report(ThreadContext ctx,
                                                      @RequestParam String chunkId,
                                                      @RequestParam(required = false) String threadId,
                                                      @RequestParam(required = false) Long turnId,
                                                      @RequestParam String reason,
                                                      @RequestParam(required = false) String comment) {
        ChunkReportService.ReportResult result =
                chunkReportService.report(ctx.userId(), chunkId, threadId, turnId, reason, comment);
        if (!result.created()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("status", "duplicate"));
        }
        return ResponseEntity.ok(Map.of("status", "created", "id", result.id()));
    }
}
