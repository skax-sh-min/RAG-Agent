package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * V9 — 서버 로컬 시각으로 저장돼 있던 시각 컬럼을 UTC 로 옮긴다. 스키마는 바꾸지 않는다.
 *
 * <p>{@code thread_meta}·{@code curated_qa}·{@code curated_submission}·{@code chunk_report} 의 저장소는 이 버전 전까지
 * {@code LocalDateTime.now()} 로 서버 로컬 시각을 썼고, 나머지 운영 테이블은 UTC 였다. 모양({@code yyyy-MM-dd HH:mm:ss})이
 * {@code conversation_turns.created_at} 같은 UTC 컬럼과 같아서 값만 보고는 가를 수 없다 — 그래서 그 컬럼들만 이름으로 골라
 * 옮긴다. 이제 쓰는 쪽은 {@code DbTimestamp.now()}(UTC)다.
 *
 * <p><b>SQL 이 아니라 Java 인 이유.</b> 원래 값은 JVM 의 기본 시간대({@link ZoneId#systemDefault()} —
 * {@code -Duser.timezone}, 없으면 {@code TZ}·OS)로 찍혔다. SQLite 의 {@code datetime(x, 'utc')} 는 C 런타임의 로컬
 * 시간대를 쓰므로 {@code -Duser.timezone} 을 준 배포에서는 다른 답을 낸다. 같은 JVM 의 같은 규칙으로 되돌려야 한다.
 *
 * <p><b>가정</b>: 그 값들이 지금 이 JVM 과 같은 시간대에서 쓰였다. 시간대가 UTC 인 배포(흔한 컨테이너)에서는 아무 값도
 * 바뀌지 않는다. 시간대가 다른 서버로 DB 를 옮긴 뒤 처음 기동하면 옛 값이 새 서버의 시간대로 해석된다 — 값에 시간대가
 * 없어 사후에 알아낼 방법이 없는 경우다. 모양이 다른 값은 건드리지 않고 개수만 경고로 남긴다.
 */
public class V9__Local_timestamps_to_utc extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V9__Local_timestamps_to_utc.class);

    /** 저장소들이 쓰던 모양. 앱 코드의 상수를 참조하지 않는다 — 그 값이 나중에 바뀌어도 이미 쓰인 값의 모양은 이것이다. */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private record Target(String table, List<String> columns) {}

    /** 서버 로컬 시각을 쓰던 컬럼 전부 — 이 밖의 시각 컬럼은 처음부터 UTC 였다. */
    private static final List<Target> TARGETS = List.of(
            new Target("thread_meta", List.of("created_at", "updated_at")),
            new Target("curated_qa", List.of("created_at", "updated_at")),
            new Target("curated_submission", List.of("created_at", "updated_at", "reviewed_at", "author_read_at")),
            new Target("chunk_report", List.of("created_at", "reviewed_at")));

    /** @param converted 실제로 값이 바뀐 칸 수(UTC 서버에서는 0) · @param skipped 모양이 달라 그대로 둔 칸 수 */
    public record Result(int converted, int skipped) {}

    private record Cell(long rowid, String value) {}

    @Override
    public void migrate(Context context) throws Exception {
        ZoneId zone = ZoneId.systemDefault();
        Result result = toUtc(context.getConnection(), zone);
        log.info("[V9] server-local timestamps -> UTC (zone={}): {} converted, {} left as-is",
                zone, result.converted(), result.skipped());
    }

    /** {@code zone} 으로 쓰였다고 보고 {@link #TARGETS} 의 값을 UTC 로 바꾼다. 시간대를 받는 것은 테스트를 위해서다. */
    public static Result toUtc(Connection con, ZoneId zone) throws SQLException {
        int converted = 0;
        int skipped = 0;
        for (Target target : TARGETS) {
            for (String column : target.columns()) {
                // 다 읽고 나서 쓴다 — 같은 연결에서 SELECT 커서가 열린 채 그 테이블을 고치면 SQLite 는 순회 결과를 보장하지 않는다.
                List<Cell> cells = new ArrayList<>();
                try (PreparedStatement select = con.prepareStatement(
                        "SELECT rowid, " + column + " FROM " + target.table() + " WHERE " + column + " IS NOT NULL");
                     ResultSet rs = select.executeQuery()) {
                    while (rs.next()) cells.add(new Cell(rs.getLong(1), rs.getString(2)));
                }
                int unparseable = 0;
                try (PreparedStatement update = con.prepareStatement(
                        "UPDATE " + target.table() + " SET " + column + " = ? WHERE rowid = ?")) {
                    for (Cell cell : cells) {
                        String utc = toUtc(cell.value(), zone);
                        if (utc == null) {
                            unparseable++;
                        } else if (!utc.equals(cell.value())) {
                            update.setString(1, utc);
                            update.setLong(2, cell.rowid());
                            update.addBatch();
                            converted++;
                        }
                    }
                    update.executeBatch();
                }
                if (unparseable > 0) {
                    log.warn("[V9] {}.{}: {} value(s) not in yyyy-MM-dd HH:mm:ss form, left as-is",
                            target.table(), column, unparseable);
                }
                skipped += unparseable;
            }
        }
        return new Result(converted, skipped);
    }

    /** 한 값 — 모양이 다르면 {@code null}. 서머타임 틈(없는 시각)은 틈만큼 뒤로, 겹침은 이른 쪽 오프셋으로 읽는다. */
    static String toUtc(String local, ZoneId zone) {
        try {
            return LocalDateTime.parse(local, STAMP).atZone(zone)
                    .withZoneSameInstant(ZoneOffset.UTC).format(STAMP);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
