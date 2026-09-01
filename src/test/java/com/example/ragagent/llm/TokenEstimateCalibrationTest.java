package com.example.ragagent.llm;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import com.example.ragagent.LogbackTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 토큰 추정 계수 관측.
 *
 * <p>이 값이 존재하는 이유는 컨텍스트 입력 예산과 인덱싱 출력 상한이 모두 "한글 1글자 ≈ 1토큰"이라는
 * <b>가정</b> 위에 서 있기 때문이다. 계수가 1 에서 벗어나면 그 예산이 그만큼 틀리는데, 재보기 전에는
 * 알 수 없다.
 */
@ResourceLock("global-state")
class TokenEstimateCalibrationTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        // 캐스팅 대신 이 헬퍼를 써야 한다 — 테스트 클래스가 병렬로 돌아 SLF4J 바인딩 경합에
        // 걸리면 SubstituteLogger 가 와서 ClassCastException 이 난다(ParallelIsolationConventionTest).
        logger = LogbackTestSupport.logger(TokenEstimateCalibration.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("계수 = 실제 / 추정 — 1.0 이면 추정이 맞고, 0.5 면 추정이 실제의 두 배다")
    void ratioIsActualOverEstimate() {
        var cal = new TokenEstimateCalibration();
        String korean = "가".repeat(1_000);   // 추정 1,000 토큰

        cal.record(korean, 500);

        assertThat(cal.ratio()).isCloseTo(0.5, within(0.01));
        assertThat(cal.sampleCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("여러 표본은 합계로 누적된다 — 표본별 평균이 아니라 전체 비율")
    void samplesAccumulateAsTotals() {
        var cal = new TokenEstimateCalibration();

        cal.record("가".repeat(1_000), 700);
        cal.record("가".repeat(3_000), 1_700);

        // (700 + 1,700) / (1,000 + 3,000) = 0.6 — 긴 표본이 더 크게 반영되는 것이 맞다.
        assertThat(cal.ratio()).isCloseTo(0.6, within(0.01));
    }

    @Test
    @DisplayName("CJK 비율을 함께 관측한다 — 계수는 텍스트 구성에 따라 달라지므로 맥락이 필요하다")
    void tracksScriptMix() {
        var cal = new TokenEstimateCalibration();

        cal.record("가".repeat(500) + "a".repeat(500), 400);

        assertThat(cal.cjkFraction()).isCloseTo(0.5, within(0.01));
    }

    @Test
    @DisplayName("usage 를 못 준 호출은 표본이 아니다 — 0 을 실제값으로 세면 계수가 무너진다")
    void ignoresMissingUsage() {
        var cal = new TokenEstimateCalibration();

        cal.record("가".repeat(1_000), 0);
        cal.record("가".repeat(1_000), -1);
        cal.record(null, 500);
        cal.record("", 500);

        assertThat(cal.sampleCount()).isZero();
        assertThat(cal.ratio()).isNull();
        assertThat(cal.cjkFraction()).isNull();
    }

    @Test
    @DisplayName("계수가 정상 범위를 벗어나면 INFO 가 아니라 WARN 으로 알린다")
    void warnsWhenTheEstimateIsBadlyOff() {
        var cal = new TokenEstimateCalibration();

        // 50건마다 누적 보고 — 계수 0.5 는 예산이 두 배로 빡빡하다는 뜻이다.
        for (int i = 0; i < 50; i++) cal.record("가".repeat(100), 50);

        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("[TOKEN_CAL]", "표본 50건");
        });
    }

    @Test
    @DisplayName("계수가 정상 범위면 INFO 로만 알린다 — 멀쩡한 배포에 경고를 띄우지 않는다")
    void staysQuietWhenTheEstimateHolds() {
        var cal = new TokenEstimateCalibration();

        for (int i = 0; i < 50; i++) cal.record("가".repeat(100), 100);   // 계수 1.0

        assertThat(appender.list)
                .filteredOn(e -> e.getLevel() == Level.WARN)
                .as("계수가 맞는데 경고가 나가면 진짜 경고가 묻힌다")
                .isEmpty();
        assertThat(appender.list).anySatisfy(e ->
                assertThat(e.getFormattedMessage()).contains("[TOKEN_CAL]", "표본 50건"));
    }
}
