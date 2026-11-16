package com.example.ragagent.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 릴리즈 태그·빌드 버전 비교용 — {@code v0.5.7}, {@code 0.5.6-SNAPSHOT}, {@code 1.0.0-rc.1} 같은 모양만 읽는다.
 *
 * <p>규칙은 일부러 좁다: 숫자 2~4개를 점으로 이은 본체 + 선택적 {@code -한정자} 뿐이고, 그 밖의 모양(날짜 태그,
 * {@code nightly}, 숫자 하나뿐인 {@code 2026-11-16}·{@code v7}, 빈 문자열)은 {@link #parse} 가 비워 돌려준다 — 읽지 못한 태그를 "더 새롭다"로 추측하면 잘못된 알림이
 * 뜨기 때문이다. 같은 본체에서 <b>한정자가 없는 쪽이 더 새롭다</b>({@code 0.5.6-SNAPSHOT} &lt; {@code 0.5.6}) — 개발 중인
 * 스냅샷은 그 번호의 정식 릴리즈보다 앞선 상태다. 한정자끼리는 {@code SNAPSHOT} 이 가장 낮고 나머지는 대소문자 무시 사전순이다.
 */
public final class AppVersion implements Comparable<AppVersion> {

    private final List<Integer> numbers;
    private final String qualifier;   // null = 정식
    private final String text;        // 정규화된 표기(접두 v·빌드 메타데이터 제거)

    private AppVersion(List<Integer> numbers, String qualifier, String text) {
        this.numbers = numbers;
        this.qualifier = qualifier;
        this.text = text;
    }

    /** 읽을 수 없으면 빈 값. */
    public static Optional<AppVersion> parse(String raw) {
        if (raw == null) return Optional.empty();
        String s = raw.strip();
        if (s.length() > 64) return Optional.empty();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        int plus = s.indexOf('+');                       // 빌드 메타데이터(1.2.3+sha) 는 비교에 쓰지 않는다
        if (plus >= 0) s = s.substring(0, plus);
        String core = s;
        String qualifier = null;
        int dash = s.indexOf('-');
        if (dash >= 0) {
            core = s.substring(0, dash);
            qualifier = s.substring(dash + 1);
            if (qualifier.isEmpty()) return Optional.empty();
        }
        String[] parts = core.split("\\.", -1);
        if (parts.length < 2 || parts.length > 4) return Optional.empty();   // 숫자 하나(날짜 태그 2026-11-16 의 2026 등)는 버전이 아니다
        List<Integer> numbers = new ArrayList<>();
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 9 || !p.chars().allMatch(c -> c >= '0' && c <= '9')) return Optional.empty();
            numbers.add(Integer.parseInt(p));
        }
        return Optional.of(new AppVersion(List.copyOf(numbers), qualifier, s));
    }

    /** 화면에 보이는 표기 — 접두 {@code v} 가 없다. */
    public String text() {
        return text;
    }

    public boolean isNewerThan(AppVersion other) {
        return compareTo(other) > 0;
    }

    @Override
    public int compareTo(AppVersion o) {
        int n = Math.max(numbers.size(), o.numbers.size());
        for (int i = 0; i < n; i++) {
            int a = i < numbers.size() ? numbers.get(i) : 0;
            int b = i < o.numbers.size() ? o.numbers.get(i) : 0;
            if (a != b) return Integer.compare(a, b);
        }
        if (qualifier == null && o.qualifier == null) return 0;
        if (qualifier == null) return 1;
        if (o.qualifier == null) return -1;
        boolean snapA = qualifier.equalsIgnoreCase("SNAPSHOT");
        boolean snapB = o.qualifier.equalsIgnoreCase("SNAPSHOT");
        if (snapA != snapB) return snapA ? -1 : 1;
        return qualifier.toLowerCase(Locale.ROOT).compareTo(o.qualifier.toLowerCase(Locale.ROOT));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AppVersion v && compareTo(v) == 0;
    }

    @Override
    public int hashCode() {
        // equals 와 맞춘다: 0.5 == 0.5.0 이므로 뒤쪽 0 은 해시에서 뺀다
        int end = numbers.size();
        while (end > 1 && numbers.get(end - 1) == 0) end--;
        return 31 * numbers.subList(0, end).hashCode()
                + (qualifier == null ? 0 : qualifier.toLowerCase(Locale.ROOT).hashCode());
    }

    @Override
    public String toString() {
        return text;
    }
}
