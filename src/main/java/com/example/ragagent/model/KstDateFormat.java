package com.example.ragagent.model;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Renders a stored UTC instant string ({@code Instant.now().toString()}, e.g.
 * {@code DocRegistry.DocRegistryEntry.indexedAt()}) as Korea Standard Time for display —
 * {@code documents.html} and {@code admin.html}'s document registry table both show "인덱싱 시간"
 * in KST rather than the raw UTC value. Referenced from Thymeleaf via
 * {@code T(com.example.ragagent.model.KstDateFormat).toKst(...)}; the underlying stored/returned
 * value (also the REST API's {@code indexed_at} field) is left untouched — this is display-only.
 */
public final class KstDateFormat {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private KstDateFormat() {}

    /** {@code isoInstant} formatted as {@code "yyyy-MM-dd HH:mm"} in Asia/Seoul. Returns the input
     *  unchanged when null/blank/unparseable, so a malformed value degrades to the raw string
     *  instead of breaking the page. */
    public static String toKst(String isoInstant) {
        if (isoInstant == null || isoInstant.isBlank()) return isoInstant;
        try {
            return FORMAT.withZone(KST).format(Instant.parse(isoInstant));
        } catch (Exception e) {
            return isoInstant;
        }
    }

    /** {@code conversation_turns.asked_at} 의 저장 형식이자, 이 클래스가 UTC 로 해석하는 형식. */
    private static final DateTimeFormatter DB_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 초까지 보여준다 — 진단 패널은 몇 초 간격의 턴을 나란히 놓고 보는 화면이라
     *  분 단위로 자르면 서로 다른 턴이 같은 시각으로 보인다. */
    private static final DateTimeFormatter STAMP_OUT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * {@code conversation_turns.asked_at}({@code "yyyy-MM-dd HH:mm:ss"}, <b>UTC</b>)을 KST 로 —
     * §6.25 결정 2.
     *
     * <p>이 앱은 두 가지 시각 표기를 저장한다: {@code asked_at} 은 {@code Instant.now()} 를 UTC 로
     * 찍고, {@code thread_meta.updated_at} 은 {@code LocalDateTime.now()} 라 <b>시스템 로컬</b>이다.
     * 같은 대화에서 두 값을 나란히 놓으면 실측 9시간이 어긋나 보이고(관리자 패널 실데이터로 확인),
     * {@code /admin} 검색 진단 수치 패널은 이 변환이 생기기 전까지 UTC 를 그대로 찍고 있었다.
     *
     * <p>{@link #toKst} 와 갈라 두는 이유는 <b>입력 형식이 다르기 때문</b>이다 — 저쪽은 ISO instant
     * ({@code DocRegistry.indexedAt}), 이쪽은 존 표기가 없는 DB 스탬프라 "UTC 로 읽는다"는 규칙이
     * 코드 안에만 있다. 한 메서드로 합치면 그 규칙이 형식 추측에 묻힌다.
     *
     * <p>null/blank/파싱 불가는 입력을 그대로 돌려준다 — 표시용이라 값이 이상하다고 화면이 깨지는
     * 것보다 원본이 보이는 편이 낫다({@link #toKst} 와 같은 degradation).
     */
    public static String utcStampToKst(String stamp) {
        if (stamp == null || stamp.isBlank()) return stamp;
        try {
            return STAMP_OUT.format(java.time.LocalDateTime.parse(stamp.strip(), DB_STAMP)
                    .atZone(java.time.ZoneOffset.UTC)
                    .withZoneSameInstant(KST));
        } catch (Exception e) {
            return stamp;
        }
    }
}
