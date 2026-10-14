package com.example.ragagent;

import com.example.ragagent.repository.DbTimestamp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시간대 규약 — 어떤 시각이 어느 시계인지를 코드가 정하고, JVM 의 기본 시간대에는 맡기지 않는다.
 *
 * <ul>
 *   <li>DB·API 에 쓰는 시각은 UTC — {@link DbTimestamp#now()}.</li>
 *   <li>사람이 읽는 서버의 시계(로그 시각, 예약 작업, 백업 파일 이름)는 {@code app.time-zone}(기본 KST).</li>
 *   <li>JVM 기본 시간대({@code TZ}/{@code -Duser.timezone})는 어디서도 읽지 않는다.</li>
 * </ul>
 *
 * <p>그 셋이 섞여 있던 동안 같은 문자열 모양이 테이블마다 다른 시간대였고(KST 서버에서 9시간 차 — Flyway V9 가 옮겼다),
 * TZ 설정 없는 컨테이너(UTC)에서는 같은 앱이 로그·정기 백업("03:30" 이 KST 낮 12:30)·답변 버블 시각을 9시간 다르게 찍었다.
 * 값에 시간대 표시가 없어 읽는 쪽은 가를 수 없고 컴파일러도 모르므로, 쓰는 쪽의 모양을 여기서 막는다.
 */
class TimeZoneConventionTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Path LOGBACK = Path.of("src/main/resources/logback-spring.xml");

    /** 존을 주지 않은 "지금"(JVM 기본 시간대로 찍힌다)과 SQLite 의 로컬 시각 변환. */
    private static final Pattern ZONELESS_NOW = Pattern.compile(
            "\\b(LocalDateTime|LocalDate|LocalTime|ZonedDateTime|OffsetDateTime|OffsetTime|Year|YearMonth|MonthDay)"
                    + "\\.now\\(\\s*\\)|'localtime'");

    /** JVM 기본 시간대를 직접 읽는 호출. */
    private static final Pattern JVM_DEFAULT_ZONE =
            Pattern.compile("ZoneId\\.systemDefault\\(\\)|TimeZone\\.getDefault\\(\\)|Clock\\.systemDefaultZone\\(\\)");

    /** JVM 기본 시간대를 읽어도 되는 곳 — 이유와 함께. 늘릴 때도 이유를 적을 것. */
    private static final Map<String, String> JVM_DEFAULT_ZONE_ALLOWED = Map.of(
            "db/migration/V9__Local_timestamps_to_utc.java",
            "옛 저장소가 LocalDateTime.now() 로 쓴 값을 바로 그 시간대(JVM 기본값)로 되돌리는 일회성 변환");

    /** {@code @Scheduled(...)} 의 인자 — cron 이면 zone 이 있어야 한다(없으면 JVM 기본 시간대의 시각이다). */
    private static final Pattern SCHEDULED = Pattern.compile("@Scheduled\\s*\\(([^)]*)\\)");

    /** logback 의 날짜 변환어와 그 옵션 — 옵션의 둘째 값이 시간대다({@code %d{패턴, 시간대}}). */
    private static final Pattern LOG_DATE = Pattern.compile("%d(?:ate)?(\\{[^}]*})?");

    @Test
    @DisplayName("앱 코드에 존 없는 now()·'localtime' 이 없다 — DB 시각은 DbTimestamp.now()(UTC), 사람의 시계는 app.time-zone")
    void noZonelessNow() throws IOException {
        List<String> violations = scan(ZONELESS_NOW, Set.of());
        assertThat(violations)
                .as("서버 로컬 시각을 만드는 호출이 있다. DB 에 쓰는 값이면 DbTimestamp.now() 를, 사람이 읽는 서버 시계면 "
                        + "app.time-zone 을, 그 밖이면 ZoneOffset.UTC 처럼 존을 명시할 것")
                .isEmpty();
    }

    @Test
    @DisplayName("JVM 기본 시간대를 읽지 않는다 — 배포 위치(TZ 설정 없는 컨테이너는 UTC)에 따라 결과가 달라진다")
    void noJvmDefaultZone() throws IOException {
        JVM_DEFAULT_ZONE_ALLOWED.keySet().forEach(allowed ->
                assertThat(MAIN.resolve(allowed)).as("예외 목록의 파일이 옮겨졌거나 지워졌다 — 목록도 고칠 것").exists());
        List<String> violations = scan(JVM_DEFAULT_ZONE, JVM_DEFAULT_ZONE_ALLOWED.keySet());
        assertThat(violations)
                .as("JVM 기본 시간대를 읽는 곳이 있다. 화면이면 UTC 를 넘겨 브라우저가 바꾸게 하거나 KstDateFormat.KST 를, "
                        + "서버의 시계면 app.time-zone 을 쓸 것")
                .isEmpty();
    }

    @Test
    @DisplayName("cron 으로 도는 @Scheduled 는 zone 을 적는다 — 적지 않으면 JVM 기본 시간대의 시각에 돈다")
    void cronSchedulesNameTheirZone() throws IOException {
        List<String> violations = new ArrayList<>();
        int crons = 0;
        for (Path file : javaFiles()) {
            String code = ChatFontSizeConventionTest.withoutComments(Files.readString(file));
            Matcher m = SCHEDULED.matcher(code);
            while (m.find()) {
                String args = m.group(1);
                if (!args.contains("cron")) continue;
                crons++;
                if (!args.contains("zone")) violations.add(rel(file) + " — " + m.group());
            }
        }
        assertThat(crons).as("cron 으로 도는 @Scheduled 를 하나도 찾지 못했다 — 패턴이 낡았는지 볼 것").isPositive();
        assertThat(violations)
                .as("zone 없는 cron 이 있다. zone = \"${app.time-zone:Asia/Seoul}\" 를 더할 것")
                .isEmpty();
    }

    @Test
    @DisplayName("logback-spring.xml 의 %d 는 전부 시간대를 적는다 — 로그 시각과 파일 회전 경계가 배포마다 갈리지 않게")
    void logbackDatesNameTheirZone() throws IOException {
        String xml = ChatFontSizeConventionTest.withoutComments(Files.readString(LOGBACK));
        List<String> violations = new ArrayList<>();
        int dates = 0;
        Matcher m = LOG_DATE.matcher(xml);
        while (m.find()) {
            dates++;
            String option = m.group(1);
            if (option == null || !option.contains(",")) violations.add(m.group());
        }
        assertThat(dates).as("logback-spring.xml 에서 %d 를 하나도 찾지 못했다 — 패턴이 낡았는지 볼 것").isPositive();
        assertThat(violations)
                .as("시간대 없는 %d 가 있다. %d{패턴, ${timeZone}} 처럼 app.time-zone 을 적을 것")
                .isEmpty();
    }

    @Test
    @DisplayName("DbTimestamp.now() 는 UTC 의 지금이다")
    void dbTimestampIsUtc() {
        LocalDateTime written = LocalDateTime.parse(DbTimestamp.now(), DbTimestamp.FORMAT);
        assertThat(Duration.between(written, LocalDateTime.now(ZoneOffset.UTC)).abs())
                .isLessThan(Duration.ofSeconds(5));
    }

    private static List<String> scan(Pattern pattern, Set<String> allowed) throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            String rel = rel(file);
            if (allowed.contains(rel)) continue;
            String code = ChatFontSizeConventionTest.withoutComments(Files.readString(file));
            Matcher m = pattern.matcher(code);
            while (m.find()) violations.add(rel + " — " + m.group());
        }
        return violations;
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static String rel(Path file) {
        return MAIN.relativize(file).toString().replace('\\', '/');
    }
}
