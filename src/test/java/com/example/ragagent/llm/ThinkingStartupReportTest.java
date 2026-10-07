package com.example.ragagent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 기동 시 한 번 남기는 생각 수준 요약과 설정 경고 — 로그를 붙잡지 않고 순수 계산만 본다. */
class ThinkingStartupReportTest {

    @Test
    @DisplayName("사이트를 수준별로 묶어 한 줄로 — 파일에서 온 것과 출하값으로 떨어진 것을 센다")
    void summarizesBySiteLevel() {
        var report = ThinkingStartupReport.build(Map.of("condense", "off", "eval", "high"),
                site -> switch (site) {
                    case CONDENSE, POST_ANSWER, CURATED_SUGGEST -> ThinkingLevel.OFF;
                    case EVAL -> ThinkingLevel.HIGH;
                    default -> ThinkingLevel.LOW;
                });

        assertThat(report.warnings()).isEmpty();
        assertThat(report.summary())
                .contains("off=[condense, post-answer, curated-suggest]")
                .contains("high=[eval]")
                .contains("medium=[]")
                .contains("설정 파일 2곳 · 출하값 " + (ThinkingSite.values().length - 2) + "곳");
    }

    @Test
    @DisplayName("값이 틀리면 고칠 키와 대신 쓰는 출하값을 경고한다")
    void warnsOnInvalidValue() {
        var report = ThinkingStartupReport.build(Map.of("eval", "default"), ThinkingSite::shippedDefault);

        assertThat(report.warnings()).singleElement().asString()
                .contains("app.llm.thinking.eval=default")
                .contains("출하값 off");
        assertThat(report.summary()).contains("설정 파일 0곳");
    }

    @Test
    @DisplayName("모르는 호출 지점 키는 무시한다고 경고한다 — 오타로 사이트 이름이 틀린 경우")
    void warnsOnUnknownSite() {
        var report = ThinkingStartupReport.build(Map.of("evall", "off"), ThinkingSite::shippedDefault);

        assertThat(report.warnings()).singleElement().asString()
                .contains("app.llm.thinking.evall")
                .contains("모르는 호출 지점");
    }
}
