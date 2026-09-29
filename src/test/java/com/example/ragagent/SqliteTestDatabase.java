package com.example.ragagent;

import com.example.ragagent.config.SqliteVecSchemaInitializer;
import com.example.ragagent.ingestion.KeywordSearchRepository;
import org.flywaydb.core.Flyway;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 테스트용 SQLite 파일 — 앱과 <b>같은 스키마</b>를 앱과 같은 방법(Flyway, {@code application.properties} 의
 * {@code spring.flyway.*})으로 만든다.
 *
 * <p>운영 테이블의 스키마는 Flyway 마이그레이션에만 있다(V4 이전에는 저장소 클래스들의 런타임 DDL 에 흩어져 있었다).
 * 테스트가 테이블을 손으로 만들면 실제와 다른 축소 스키마 위에서 SQL 이 통과할 수 있으므로 — 목으로 대신한 조회가
 * 없는 컬럼을 읽고 있었던 적도 있다 — 저장소 테스트는 여기서 DB 를 받는다. 마이그레이션은 JVM 당 한 번 템플릿
 * 파일에 적용하고, 테스트마다 그 파일을 복사한다.
 *
 * <p>다시 만들 수 있는 검색 색인({@code chunk_fts}·{@code chunk_fts_key}·{@code vec_*})은 Flyway 가 만들지 않는다 —
 * 앱에서처럼 그 색인의 컴포넌트({@code KeywordSearchRepository}, {@code SqliteVecSchemaInitializer})가 만든다.
 */
public final class SqliteTestDatabase {

    /** V4 이전 저장소들이 기동 시 실행하던 DDL 의 기록 — 옛 배포의 DB 를 재현할 때 쓴다. */
    public static final String PRE_V4_RUNTIME_DDL = "/db/pre-v4-runtime-ddl.sql";

    private static volatile Path template;

    private SqliteTestDatabase() {}

    /**
     * {@code file} 을 연다 — 없거나 비어 있으면({@code Files.createTempFile} 이 만든 0바이트 파일 = 빈 SQLite DB)
     * 현재 스키마를 가진 새 DB 로 만들고, 내용이 있으면 그대로 연다(같은 파일을 다시 여는 테스트가 "재기동"을 흉내 낼
     * 수 있게).
     */
    public static JdbcTemplate open(Path file) {
        try {
            if (Files.notExists(file) || Files.size(file) == 0) {
                Files.copy(template(), file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return jdbc(file);
    }

    /**
     * 검색 색인 테이블을 앱과 같은 DDL 로 만든다 — {@code chunk_fts}(FTS5)·{@code chunk_fts_key} 는
     * {@code KeywordSearchRepository} 가, {@code vec_document_chunks} 는 {@code SqliteVecSchemaInitializer} 가 쓰는
     * 그 문장으로(vec0 확장 없이 만들 수 있는 일반 테이블이다). 출처 조회처럼 운영 테이블과 색인 테이블을 함께 조인하는
     * 코드를 시험할 때 쓴다.
     */
    public static void createSearchIndexTables(JdbcTemplate jdbc) {
        ReflectionTestUtils.invokeMethod(new KeywordSearchRepository(jdbc), "init");
        for (String field : List.of("CHUNK_TABLE_DDL", "IDX_VERSION_DDL", "IDX_DOCID_DDL")) {
            jdbc.execute((String) ReflectionTestUtils.getField(SqliteVecSchemaInitializer.class, field));
        }
    }

    /** 이미 있는 파일(또는 새로 생길 빈 파일)에 대한 {@link JdbcTemplate} — 스키마는 건드리지 않는다. */
    public static JdbcTemplate jdbc(Path file) {
        return new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + file));
    }

    /**
     * 앱의 Flyway 설정 그대로 — {@code application.properties} 의 값을 읽는다(여기에 복사하면 조용히 어긋난다).
     * {@code target} 이 {@code null} 이 아니면 그 버전까지만 적용한다.
     */
    public static Flyway appFlyway(Path file, String target) {
        Properties app = new Properties();
        try (InputStream in = SqliteTestDatabase.class.getResourceAsStream("/application.properties")) {
            if (in == null) throw new IllegalStateException("main application.properties is not on the test classpath");
            app.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var config = Flyway.configure()
                .dataSource("jdbc:sqlite:" + file, null, null)
                .locations(app.getProperty("spring.flyway.locations"))
                .baselineOnMigrate(Boolean.parseBoolean(app.getProperty("spring.flyway.baseline-on-migrate")))
                .baselineVersion(app.getProperty("spring.flyway.baseline-version"));
        if (target != null) config.target(target);
        return config.load();
    }

    /**
     * {@link #PRE_V4_RUNTIME_DDL} 을 실행한다 — 옛 코드처럼 "duplicate column name" 만 삼킨다.
     * {@code skip} 이 참인 문장은 건너뛴다(최근 컬럼이 없던 옛 버전을 흉내 낼 때).
     */
    public static void replayPreV4RuntimeDdl(JdbcTemplate jdbc, Predicate<String> skip) {
        String text;
        try (InputStream in = SqliteTestDatabase.class.getResourceAsStream(PRE_V4_RUNTIME_DDL)) {
            if (in == null) throw new IllegalStateException(PRE_V4_RUNTIME_DDL + " is not on the test classpath");
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String withoutComments = text.lines()
                .filter(line -> !line.strip().startsWith("--"))
                .collect(Collectors.joining("\n"));
        for (String statement : withoutComments.split(";")) {
            String sql = statement.strip();
            if (sql.isEmpty() || skip.test(sql)) continue;
            try {
                jdbc.execute(sql);
            } catch (DataAccessException e) {
                String message = e.getMostSpecificCause().getMessage();
                if (message == null || !message.contains("duplicate column name")) throw e;
            }
        }
    }

    /**
     * 비교할 수 있게 정규화한 스키마 — 테이블마다 컬럼(이름순: 이름·타입·NOT NULL·기본값·PK 순번)과 인덱스
     * (이름순: 이름·UNIQUE·출처·키 컬럼과 정렬 방향·부분 인덱스 조건). 컬럼의 물리적 순서는 보지 않는다 — 같은
     * 테이블이라도 옛 파일마다 {@code ALTER} 가 붙은 순서가 달라서, 이름으로 읽는 코드에는 의미가 없다.
     * Flyway 이력 테이블과 SQLite 자동 테이블은 뺀다.
     */
    public static Map<String, List<String>> schemaOf(JdbcTemplate jdbc) {
        Map<String, List<String>> out = new TreeMap<>();
        List<String> tables = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type = 'table' "
                        + "AND name NOT LIKE 'sqlite_%' AND name <> 'flyway_schema_history'", String.class);
        for (String table : tables) {
            List<String> parts = new ArrayList<>();
            jdbc.query("PRAGMA table_info(\"" + table + "\")", rs -> {
                parts.add("col " + rs.getString("name") + " | " + rs.getString("type").toUpperCase()
                        + " | notnull=" + rs.getInt("notnull") + " | default=" + rs.getString("dflt_value")
                        + " | pk=" + rs.getInt("pk"));
            });
            jdbc.query("PRAGMA index_list(\"" + table + "\")", rs -> {
                String index = rs.getString("name");
                List<String> keys = jdbc.query("PRAGMA index_xinfo(\"" + index + "\")",
                        (x, n) -> x.getInt("key") == 1
                                ? x.getString("name") + (x.getInt("desc") == 1 ? " DESC" : "")
                                : null)
                        .stream().filter(k -> k != null).toList();
                String where = "";
                if (rs.getInt("partial") == 1) {
                    String sql = jdbc.queryForObject(
                            "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = ?", String.class, index);
                    String normalized = sql.replaceAll("\\s+", " ").strip();
                    where = " | where=" + normalized.substring(normalized.toUpperCase().lastIndexOf(" WHERE ") + 7);
                }
                parts.add("idx " + index + " | unique=" + rs.getInt("unique") + " | origin=" + rs.getString("origin")
                        + " | keys=" + keys + where);
            });
            parts.sort(String::compareTo);
            out.put(table, parts);
        }
        return out;
    }

    private static Path template() {
        Path t = template;
        if (t != null) return t;
        synchronized (SqliteTestDatabase.class) {
            if (template == null) {
                try {
                    Path dir = Files.createTempDirectory("rag-test-schema-");
                    Path file = dir.resolve("template.db");
                    dir.toFile().deleteOnExit();
                    file.toFile().deleteOnExit();
                    appFlyway(file, null).migrate();
                    template = file;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return template;
        }
    }

    /** 표시용 — 스키마 차이를 실패 메시지에 한 줄씩. */
    public static String describe(Map<String, List<String>> schema) {
        return schema.entrySet().stream()
                .map(e -> e.getKey() + "\n  " + String.join("\n  ", e.getValue()))
                .collect(Collectors.joining("\n"));
    }
}
