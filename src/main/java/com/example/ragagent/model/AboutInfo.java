package com.example.ragagent.model;

import java.time.Instant;

/**
 * {@code /settings} 맨 아래 「정보」 카드에 보이는 값 — 앱 이름·버전·빌드 시각·라이선스, 그리고 관리자에게만 보이는 실행 환경.
 *
 * @param version      빌드 정보가 있으면 그 버전, 없으면(IDE·{@code mvn} 없이 띄운 개발 실행) {@code null}
 * @param buildTime    빌드 시각(UTC 순간) — 빌드 정보가 없으면 {@code null}
 * @param javaVersion  실행 중인 JVM 버전 — <b>관리자에게만</b> 보인다(소프트웨어 버전은 공격자에게 쓸모 있는 정보다)
 * @param bootVersion  Spring Boot 버전 — 위와 같다
 */
public record AboutInfo(String name, String version, Instant buildTime,
                        String licenseName, String licenseUrl, String copyright,
                        String javaVersion, String bootVersion) {

    /**
     * 빌드 시각의 {@code <time>} 요소 — 보는 사람의 시간대로 바꾸는 것은 {@code layout/base.html} 의 {@code localizeTimes()} 다.
     * {@code th:utext} 로 싣는다: {@link UtcTime#timeElement} 가 {@link Instant} 에서만 만들어 HTML 로 해석될 글자가 들어갈 수 없다.
     * (템플릿에서 {@code T(...)} 로 부르면 Thymeleaf 의 제한된 표현식 문맥이 막는다.)
     */
    public String buildTimeElement() {
        return UtcTime.timeElement(buildTime, "minute");
    }

    /** 빌드 정보 없이 떠 있는 개발 실행인가 — 화면이 버전 자리에 "개발 빌드"를 쓴다. */
    public boolean devBuild() {
        return version == null || version.isBlank();
    }
}
