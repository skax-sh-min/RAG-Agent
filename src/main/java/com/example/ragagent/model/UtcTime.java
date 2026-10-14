package com.example.ragagent.model;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * 화면으로 나가는 시각 — 서버는 바꾸지 않고 <b>UTC 순간</b>으로 내보내며, 보는 사람의 시간대로 바꾸는 것은 브라우저다.
 *
 * <p>템플릿은 {@code fragments/time :: utc(값, 정밀도)} 로 {@code <time class="utc-time" datetime="…Z">} 를 그리고,
 * {@code layout/base.html} 의 {@code localizeTimes()} 가 그 요소를 전부 바꾼다(HTMX 나 {@code innerHTML} 로 늦게 그려지는
 * 패널은 MutationObserver 가 잡는다). JS 가 돌지 않으면 {@link #fallback} 이 보인다 — 'UTC' 를 붙여, 틀린 시각처럼 보이지
 * 않게.
 *
 * <p>예전에는 관리자 화면이 서버에서 KST 로 고정 변환하고({@code KstDateFormat}) 채팅은 브라우저가 바꿔, 같은 값이 화면마다
 * 다른 규칙을 탔다. 문서 목록은 한 표 안에서도 서버가 그린 행(KST)과 업로드 직후 JS 가 붙인 행(UTC 문자열을 자른 것)이
 * 9시간 어긋났다.
 *
 * <p>입력은 저장된 두 모양을 다 받는다 — 존 표기 없는 DB 스탬프({@code yyyy-MM-dd HH:mm:ss}, UTC — {@code DbTimestamp},
 * SQLite {@code datetime('now')})와 ISO 순간({@code …Z}, {@code Instant.toString()}).
 */
public final class UtcTime {

    private static final DateTimeFormatter DB_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter SECOND = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);

    private UtcTime() {}

    /** 저장된 시각 → ISO-8601 UTC 순간(초 단위, {@code Z}). 비었거나 읽을 수 없으면 {@code null}. */
    public static String iso(String stored) {
        Instant instant = parse(stored);
        return instant == null ? null : iso(instant);
    }

    public static String iso(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /**
     * JS 가 돌기 전·돌지 않을 때 보이는 글자 — {@code precision} 은 {@code second}(기본) · {@code minute} · {@code time}.
     * 읽을 수 없는 값은 그대로 돌려준다(표시용이라 값이 이상해도 화면이 깨지지 않게), {@code null} 은 빈 문자열.
     */
    public static String fallback(String stored, String precision) {
        if (stored == null) return "";
        Instant instant = parse(stored);
        return instant == null ? stored : formatter(precision).format(instant) + " UTC";
    }

    /**
     * 문구 안에 끼워 넣을 {@code <time>} 요소 — 번들 문구의 자리표시자에 시각이 들어가 템플릿 조각을 쓸 수 없는 자리용
     * ({@code th:utext}). 값은 {@link Instant} 에서만 만들어 HTML 로 해석될 글자가 들어갈 수 없다.
     */
    public static String timeElement(Instant instant, String precision) {
        if (instant == null) return "";
        return "<time class=\"utc-time\" datetime=\"" + iso(instant) + "\" data-precision=\"" + normalize(precision) + "\">"
                + formatter(precision).format(instant) + " UTC</time>";
    }

    private static Instant parse(String stored) {
        if (stored == null || stored.isBlank()) return null;
        String s = stored.strip();
        try {
            return s.indexOf('T') >= 0 ? Instant.parse(s) : LocalDateTime.parse(s, DB_STAMP).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String normalize(String precision) {
        return "minute".equals(precision) || "time".equals(precision) ? precision : "second";
    }

    private static DateTimeFormatter formatter(String precision) {
        return switch (normalize(precision)) {
            case "minute" -> MINUTE;
            case "time" -> TIME;
            default -> SECOND;
        };
    }
}
