package com.example.ragagent.model;

import java.util.Locale;
import java.util.Optional;

/**
 * §10.14 청크 오류 신고의 사유. <b>내용이 잘못됐다</b>는 축만 담는다.
 *
 * <p>"질문과 무관"은 일부러 없다 — 그건 청크가 틀린 것이 아니라 이 답변에 안 맞는 것이고, 채팅에는
 * 이미 그 동작이 따로 있다("현재 대화에서 이 청크 제거", 표시 전용). 여기에 넣으면 표시 전용
 * 동작이 관리자 대기열로 흘러든다.
 *
 * <p>화면 문구는 이 enum 이 들고 있지 않다 — 사용자 화면과 관리자 화면이 같은 메시지 키
 * ({@code chunk.report.reason.<소문자 이름>})를 읽으므로 문구의 출처는 messages 프로퍼티 하나다.
 */
public enum ChunkReportReason {
    /** 사실이 틀림 */
    WRONG,
    /** 오래됨 · 업데이트 필요 */
    OUTDATED,
    /** 깨진 텍스트/표/이미지 (변환 오류) */
    BROKEN,
    /** 기타 */
    OTHER;

    /**
     * 관대하게 해석하지 <b>않는다</b>: 라디오 버튼 하나에서 오는 값이라 알 수 없는 값은 폼이
     * 깨졌다는 뜻이고, 조용히 {@code OTHER} 로 흡수하면 그 사실이 대기열에 묻힌다.
     */
    public static Optional<ChunkReportReason> parse(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        try {
            return Optional.of(valueOf(raw.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** 메시지 키 조각 — 템플릿에서 {@code #{'chunk.report.reason.' + ...}} 로 이어 붙인다. */
    public String messageSuffix() {
        return name().toLowerCase(Locale.ROOT);
    }
}
