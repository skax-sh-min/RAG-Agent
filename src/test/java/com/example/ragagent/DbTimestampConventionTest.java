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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB 에 쓰는 시각은 UTC 다 — 서버 로컬 시각을 만드는 호출이 앱 코드에 새로 들어오지 않게 막는다.
 *
 * <p>네 저장소가 {@code LocalDateTime.now()} 로 서버 로컬 시각을 쓰던 동안, 같은 모양의 문자열이 테이블에 따라 다른
 * 시간대였다(KST 서버에서 9시간 차). 값에 시간대 표시가 없어 읽는 쪽은 가를 수 없고 컴파일러도 모른다. 쓰는 쪽은
 * {@link DbTimestamp#now()} 를 지날 것.
 */
class DbTimestampConventionTest {

    private static final Path MAIN = Path.of("src/main/java");

    /** 존을 주지 않은 "지금"과 SQLite 의 로컬 시각 변환. */
    private static final Pattern LOCAL_NOW =
            Pattern.compile("\\b(LocalDateTime|LocalDate|LocalTime)\\.now\\(\\s*\\)|'localtime'");

    /** DB 에 쓰지 않는 로컬 시각 — 파일 이름처럼 운영자가 그 서버의 시계로 읽는 값. 늘릴 때는 이유를 함께 적을 것. */
    private static final Set<String> ALLOWED = Set.of(
            // 백업 파일 이름(memory-yyyyMMdd-HHmmss.db) — 정기 실행 시각(app.backup.cron)과 같은 서버 시계로 읽힌다.
            "com/example/ragagent/service/DatabaseBackupService.java");

    @Test
    @DisplayName("앱 코드에 존 없는 now()·'localtime' 이 없다 — DB 시각은 DbTimestamp.now()(UTC)로 쓴다")
    void noServerLocalNowInMainCode() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                if (ALLOWED.contains(rel)) continue;
                String code = ChatFontSizeConventionTest.withoutComments(Files.readString(file));
                Matcher m = LOCAL_NOW.matcher(code);
                while (m.find()) violations.add(rel + " — " + m.group());
            }
        }
        assertThat(violations)
                .as("서버 로컬 시각을 만드는 호출이 있다. DB 에 쓰는 값이면 DbTimestamp.now() 를, 다른 용도면 "
                        + "ZoneOffset.UTC 처럼 존을 명시하거나 이유와 함께 ALLOWED 에 더할 것")
                .isEmpty();
    }

    @Test
    @DisplayName("DbTimestamp.now() 는 UTC 의 지금이다")
    void dbTimestampIsUtc() {
        LocalDateTime written = LocalDateTime.parse(DbTimestamp.now(), DbTimestamp.FORMAT);
        assertThat(Duration.between(written, LocalDateTime.now(ZoneOffset.UTC)).abs())
                .isLessThan(Duration.ofSeconds(5));
    }
}
