package com.example.ragagent.llm;

import java.util.Locale;
import java.util.Optional;

/**
 * 호출 지점 하나가 모델에게 얼마나 생각하라고 할지 — <b>넷뿐이다</b>(PLAN §6.29 ①).
 *
 * <p><b>"서버 기본값"이라는 수준은 없다.</b> 그건 관리자가 고르는 값이 아니라 앱이 모르는 값이라, 그걸 고르면
 * 그 호출이 실제로 생각하는지 아무도 답할 수 없다. 서버가 정하는 상황은 사이트의 수준이 아니라 프로바이더의
 * 성질({@link ThinkingDialect#NONE})로 남는다.
 *
 * <p>이 값이 서버에서 무엇이 되는지는 받는 프로바이더가 정한다({@link ThinkingDialect#wire}) — llama.cpp +
 * gemma-4 처럼 켬/끔만 받는 서버에서는 낮게·중간·높게가 같은 값으로 나간다.
 */
public enum ThinkingLevel {
    OFF, LOW, MEDIUM, HIGH;

    /** 설정 파일과 {@code /settings} 에 적는 값 — {@code off}/{@code low}/{@code medium}/{@code high}. */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * 대소문자·앞뒤 공백을 무시한다. 넷 중 하나가 아니면 비어 있다 — 기본값으로 떨어뜨리는 것은 호출부의 몫이다
     * (어느 기본값인지는 호출 지점마다 다르다).
     */
    public static Optional<ThinkingLevel> parse(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String v = raw.strip().toUpperCase(Locale.ROOT);
        for (ThinkingLevel level : values()) {
            if (level.name().equals(v)) return Optional.of(level);
        }
        return Optional.empty();
    }
}
