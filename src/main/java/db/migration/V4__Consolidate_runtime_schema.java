package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * V4 — 운영 테이블의 스키마를 Flyway 로 모은다.
 *
 * <p>이 마이그레이션 전에는 V1–V3 가 네 테이블의 <b>첫 모양</b>만 만들고, 나머지 테이블과 그 뒤에 생긴 컬럼은
 * 저장소 클래스들이 기동할 때마다 런타임 DDL({@code CREATE TABLE IF NOT EXISTS} + 방어적
 * {@code ALTER TABLE ... ADD COLUMN})로 만들었다. 스키마가 두 곳에 있었고, 같은 테이블을 두 곳이 다른 모양으로
 * 선언했으며, 컬럼을 추가하는 방식도 두 가지(예외를 통째로 삼키기 / {@code PRAGMA} 로 먼저 확인하기)였다.
 *
 * <p><b>SQL 이 아니라 Java 인 이유.</b> 이 시점의 DB 는 여러 모양일 수 있다 — 빈 DB(V1–V3 만 막 적용됨), 옛
 * 새 설치(V1–V3 이력 + 런타임 DDL 이 붙인 컬럼), 이력 없이 런타임 DDL 로만 만들어져 버전 3 으로 baseline 된
 * 옛 벡터 DB 파일, 옛 버전 앱이 만들어 최근 컬럼이 빠진 DB. SQLite 에는 {@code ADD COLUMN IF NOT EXISTS} 가
 * 없어서 SQL 파일로는 이들을 같은 결과로 보낼 수 없다(이미 컬럼이 있는 DB 에서 실패한다). 그래서 여기서 테이블과
 * 컬럼이 있는지 확인하며 빠진 것만 만든다 — 어느 상태에서 출발해도 결과는 {@link #TABLES} 한 벌이고,
 * {@code FlywaySchemaConvergenceTest} 가 그 출발 상태들로 이를 고정한다. V4 뒤로는 상태가 하나로 확정되므로
 * 이후의 변경은 평범한 SQL 마이그레이션(V5 이후)으로 한다.
 *
 * <p><b>여기 없는 테이블.</b> 원본이 아니라 다시 만들 수 있는 검색 색인 — {@code chunk_fts}·{@code chunk_fts_key}
 * ({@code KeywordSearchRepository}), {@code vec_embeddings}·{@code vec_document_chunks}
 * ({@code SqliteVecSchemaInitializer}) — 은 지금처럼 그 색인을 쓰는 컴포넌트가 만든다. vec0 테이블은 차원이
 * 설정값이고 확장이 로드된 sqlite-vec 백엔드에서만 만들 수 있으며, FTS5 는 없는 환경에서 조용히 꺼지는
 * 설계다 — 설정·환경과 무관하게 한 번 적용되고 끝나는 마이그레이션과 맞지 않는다.
 *
 * <p>적용된 마이그레이션이므로 <b>고치지 않는다</b> — 스키마를 바꾸려면 새 마이그레이션을 더한다. SQL 파일과 달리 Java
 * 마이그레이션에는 체크섬이 없어({@code flyway_schema_history.checksum} = {@code NULL}) 고쳐도 Flyway 의 validate 가
 * 알아채지 못하고, 이미 V4 를 적용한 DB 에는 조용히 반영되지 않는다. 대신 {@code FlywaySchemaConvergenceTest} 가 V1–V4
 * 의 결과를 옛 DDL 기록과 정확히 대조해 스키마를 바꾸는 수정을 잡는다.
 */
public class V4__Consolidate_runtime_schema extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V4__Consolidate_runtime_schema.class);

    /** 컬럼 하나. 테이블을 새로 만들 때와 빠진 컬럼을 추가할 때 <b>같은 정의</b>를 쓴다 — 두 경로의 결과가 같은 이유다. */
    private record Column(String name, String definition) {
        String ddl() {
            return name + " " + definition;
        }
    }

    private record Table(String name, List<Column> columns, List<String> constraints, List<String> indexes) {
        String createSql(String tableName) {
            String body = Stream.concat(columns.stream().map(Column::ddl), constraints.stream())
                    .collect(Collectors.joining(",\n    "));
            return "CREATE TABLE IF NOT EXISTS " + tableName + " (\n    " + body + "\n)";
        }
    }

    private static Column col(String name, String definition) {
        return new Column(name, definition);
    }

    private static final Table CURATED_QA = new Table("curated_qa", List.of(
            col("id", "INTEGER PRIMARY KEY AUTOINCREMENT"),
            col("source_turn_id", "INTEGER"),
            col("source_user_id", "TEXT NOT NULL"),
            col("source_thread_id", "TEXT NOT NULL"),
            col("question", "TEXT NOT NULL"),
            col("answer", "TEXT NOT NULL"),
            col("status", "TEXT NOT NULL DEFAULT 'active'"),
            col("source_doc_version", "TEXT"),
            col("created_at", "TEXT NOT NULL"),
            col("updated_at", "TEXT NOT NULL"),
            col("embed_status", "TEXT NOT NULL DEFAULT 'ok'"),
            col("origin", "TEXT NOT NULL DEFAULT 'like'"),
            col("source_submission_id", "INTEGER"),
            col("tags", "TEXT"),
            col("chunk_count", "INTEGER NOT NULL DEFAULT 1"),
            col("summary", "TEXT"),
            col("keywords", "TEXT")),
            List.of(),
            List.of("CREATE UNIQUE INDEX IF NOT EXISTS idx_curated_qa_turn ON curated_qa(source_turn_id) "
                            + "WHERE source_turn_id IS NOT NULL",
                    "CREATE INDEX IF NOT EXISTS idx_curated_qa_status ON curated_qa(status)",
                    "CREATE INDEX IF NOT EXISTS idx_curated_qa_submission ON curated_qa(source_submission_id) "
                            + "WHERE source_submission_id IS NOT NULL"));

    /**
     * 운영 테이블 전부의 현재 모양. 컬럼 순서는 옛 런타임 DDL 이 만들던 순서(처음 {@code CREATE} 의 컬럼 → 나중에
     * {@code ALTER} 로 붙은 컬럼)를 따른다 — V4 가 새로 만드는 테이블이 옛 파일의 같은 테이블과 순서까지 같도록.
     */
    private static final List<Table> TABLES = List.of(
            new Table("conversation_turns", List.of(
                    col("id", "INTEGER PRIMARY KEY AUTOINCREMENT"),
                    col("thread_id", "TEXT NOT NULL"),
                    col("question", "TEXT NOT NULL"),
                    col("answer", "TEXT NOT NULL"),
                    col("created_at", "TEXT NOT NULL DEFAULT (datetime('now'))"),
                    col("asked_at", "TEXT"),
                    col("input_tokens", "INTEGER DEFAULT 0"),
                    col("output_tokens", "INTEGER DEFAULT 0"),
                    col("elapsed_ms", "INTEGER DEFAULT 0"),
                    col("provider", "TEXT"),
                    col("llm_calls", "INTEGER DEFAULT 0"),
                    col("user_id", "TEXT NOT NULL DEFAULT 'anonymous'"),
                    col("feedback", "TEXT"),
                    col("response_mode", "TEXT"),
                    col("selected_tags", "TEXT"),
                    col("reused_from_turn_id", "INTEGER"),
                    col("direct_mode", "INTEGER NOT NULL DEFAULT 0"),
                    col("retrieval_metrics", "TEXT"),
                    col("verification", "TEXT")),
                    List.of(),
                    List.of("CREATE INDEX IF NOT EXISTS idx_thread_id ON conversation_turns(thread_id)",
                            "CREATE INDEX IF NOT EXISTS idx_turns_user_thread ON conversation_turns(user_id, thread_id)",
                            "CREATE INDEX IF NOT EXISTS idx_turns_reused_from ON conversation_turns(reused_from_turn_id)")),
            new Table("turn_image_ref", List.of(
                    col("id", "INTEGER PRIMARY KEY AUTOINCREMENT"),
                    col("turn_id", "INTEGER NOT NULL"),
                    col("user_id", "TEXT NOT NULL"),
                    col("thread_id", "TEXT NOT NULL"),
                    col("image_ref", "TEXT NOT NULL"),
                    col("status", "TEXT NOT NULL DEFAULT 'active'"),
                    col("created_at", "TEXT NOT NULL DEFAULT (datetime('now'))")),
                    List.of(),
                    List.of("CREATE INDEX IF NOT EXISTS idx_turn_image_turn ON turn_image_ref(turn_id)",
                            "CREATE INDEX IF NOT EXISTS idx_turn_image_user_thread ON turn_image_ref(user_id, thread_id)")),
            new Table("image_descriptions", List.of(
                    col("image_path", "TEXT PRIMARY KEY"),
                    col("description", "TEXT NOT NULL"),
                    col("image_type", "TEXT"),
                    col("provider", "TEXT"),
                    col("created_at", "TEXT NOT NULL DEFAULT (datetime('now'))"),
                    col("user_id", "TEXT NOT NULL DEFAULT 'anonymous'")),
                    List.of(),
                    List.of("CREATE INDEX IF NOT EXISTS idx_img_user ON image_descriptions(user_id)")),
            new Table("thread_meta", List.of(
                    col("thread_id", "TEXT PRIMARY KEY"),
                    col("title", "TEXT NOT NULL DEFAULT '새 대화'"),
                    col("version", "TEXT NOT NULL DEFAULT 'latest'"),
                    col("created_at", "TEXT NOT NULL"),
                    col("updated_at", "TEXT NOT NULL"),
                    col("routing_mode", "TEXT NOT NULL DEFAULT 'COST_FIRST'"),
                    col("tags", "TEXT NOT NULL DEFAULT ''"),
                    col("user_id", "TEXT NOT NULL DEFAULT 'anonymous'")),
                    List.of(),
                    List.of("CREATE INDEX IF NOT EXISTS idx_thread_meta_user ON thread_meta(user_id)")),
            new Table("turn_source_ref", List.of(
                    col("id", "INTEGER PRIMARY KEY AUTOINCREMENT"),
                    col("turn_id", "INTEGER NOT NULL"),
                    col("user_id", "TEXT NOT NULL"),
                    col("thread_id", "TEXT NOT NULL"),
                    col("chunk_id", "TEXT NOT NULL"),
                    col("doc_id", "TEXT"),
                    col("chunk_hash", "TEXT NOT NULL"),
                    col("status", "TEXT NOT NULL DEFAULT 'active'"),
                    col("created_at", "TEXT NOT NULL DEFAULT (datetime('now'))"),
                    col("answer_share", "REAL"),
                    col("invalidated_at", "TEXT"),
                    col("hidden_at", "TEXT"),
                    col("filename", "TEXT"),
                    col("page_or_slide", "TEXT"),
                    col("chapter_no", "TEXT")),
                    List.of(),
                    List.of("CREATE INDEX IF NOT EXISTS idx_turn_source_turn ON turn_source_ref(turn_id)",
                            "CREATE INDEX IF NOT EXISTS idx_turn_source_chunk ON turn_source_ref(chunk_id)")),
            new Table("llm_usage", List.of(
                    col("provider_name", "TEXT NOT NULL"),
                    col("usage_date", "TEXT NOT NULL"),
                    col("input_tokens", "INTEGER NOT NULL DEFAULT 0"),
                    col("output_tokens", "INTEGER NOT NULL DEFAULT 0"),
                    col("call_count", "INTEGER NOT NULL DEFAULT 0"),
                    col("user_id", "TEXT NOT NULL DEFAULT 'anonymous'")),
                    List.of("PRIMARY KEY (provider_name, usage_date)"),
                    List.of("CREATE INDEX IF NOT EXISTS idx_llm_usage_date ON llm_usage(usage_date)")),
            CURATED_QA,
            new Table("curated_submission", List.of(
                    col("id", "INTEGER PRIMARY KEY AUTOINCREMENT"),
                    col("author_user_id", "TEXT NOT NULL"),
                    col("title", "TEXT NOT NULL"),
                    col("body", "TEXT NOT NULL"),
                    col("status", "TEXT NOT NULL DEFAULT 'pending'"),
                    col("reviewer_user_id", "TEXT"),
                    col("review_note", "TEXT"),
                    col("curated_qa_id", "INTEGER"),
                    col("created_at", "TEXT NOT NULL"),
                    col("updated_at", "TEXT NOT NULL"),
                    col("reviewed_at", "TEXT"),
                    col("author_read_at", "TEXT"),
                    col("tags", "TEXT"),
                    col("source_turn_id", "INTEGER"),
                    col("source_thread_id", "TEXT"),
                    col("summary", "TEXT"),
                    col("keywords", "TEXT")),
                    List.of(),
                    List.of("CREATE INDEX IF NOT EXISTS idx_curated_sub_turn ON curated_submission(source_turn_id) "
                                    + "WHERE source_turn_id IS NOT NULL",
                            "CREATE INDEX IF NOT EXISTS idx_curated_sub_status ON curated_submission(status, id DESC)",
                            "CREATE INDEX IF NOT EXISTS idx_curated_sub_author ON curated_submission(author_user_id, id DESC)")),
            new Table("settings_override", List.of(
                    col("key", "TEXT NOT NULL PRIMARY KEY"),
                    col("value", "TEXT NOT NULL"),
                    col("updated_at", "TEXT NOT NULL")),
                    List.of(),
                    List.of()),
            new Table("app_secret", List.of(
                    col("name", "TEXT NOT NULL PRIMARY KEY"),
                    col("value", "TEXT NOT NULL"),
                    col("created_at", "TEXT NOT NULL")),
                    List.of(),
                    List.of()),
            new Table("doc_registry", List.of(
                    col("doc_id", "TEXT NOT NULL"),
                    col("user_id", "TEXT NOT NULL DEFAULT 'anonymous'"),
                    col("sha256", "TEXT NOT NULL"),
                    col("version", "TEXT NOT NULL"),
                    col("indexed_at", "TEXT NOT NULL"),
                    col("chunks", "INTEGER NOT NULL"),
                    col("spring_doc_ids", "TEXT NOT NULL"),
                    col("errors", "TEXT NOT NULL"),
                    col("chunk_overlap", "INTEGER"),
                    col("display_name", "TEXT"),
                    col("tags", "TEXT")),
                    List.of("PRIMARY KEY (doc_id, user_id)"),
                    List.of("CREATE INDEX IF NOT EXISTS idx_doc_registry_user_version ON doc_registry(user_id, version)",
                            "CREATE INDEX IF NOT EXISTS idx_doc_registry_sha_version ON doc_registry(sha256, version, user_id)")),
            new Table("chunk_report", List.of(
                    col("id", "INTEGER PRIMARY KEY AUTOINCREMENT"),
                    col("chunk_id", "TEXT NOT NULL"),
                    col("doc_id", "TEXT"),
                    col("version", "TEXT"),
                    col("filename", "TEXT"),
                    col("reporter_user_id", "TEXT NOT NULL"),
                    col("thread_id", "TEXT"),
                    col("turn_id", "INTEGER"),
                    col("question", "TEXT"),
                    col("reason_code", "TEXT NOT NULL"),
                    col("comment", "TEXT NOT NULL"),
                    col("chunk_hash", "TEXT"),
                    col("chunk_snapshot", "TEXT"),
                    col("status", "TEXT NOT NULL DEFAULT 'open'"),
                    col("reviewer_user_id", "TEXT"),
                    col("review_note", "TEXT"),
                    col("created_at", "TEXT NOT NULL"),
                    col("reviewed_at", "TEXT")),
                    List.of(),
                    List.of("CREATE INDEX IF NOT EXISTS idx_chunk_report_open ON chunk_report(status, chunk_id)",
                            "CREATE INDEX IF NOT EXISTS idx_chunk_report_chunk ON chunk_report(chunk_id, id DESC)",
                            "CREATE UNIQUE INDEX IF NOT EXISTS idx_chunk_report_dup "
                                    + "ON chunk_report(chunk_id, reporter_user_id, thread_id) WHERE status = 'open'")),
            new Table("users", List.of(
                    col("id", "TEXT PRIMARY KEY"),
                    col("email", "TEXT UNIQUE NOT NULL"),
                    col("password_hash", "TEXT NOT NULL"),
                    col("display_name", "TEXT"),
                    col("role", "TEXT NOT NULL DEFAULT 'USER'"),
                    col("enabled", "INTEGER NOT NULL DEFAULT 1"),
                    col("failed_count", "INTEGER NOT NULL DEFAULT 0"),
                    col("locked_until", "TEXT"),
                    col("created_at", "TEXT NOT NULL"),
                    col("updated_at", "TEXT NOT NULL")),
                    List.of(),
                    List.of("CREATE INDEX IF NOT EXISTS idx_users_email ON users(email)")),
            new Table("persistent_logins", List.of(
                    col("username", "TEXT NOT NULL"),
                    col("series", "TEXT PRIMARY KEY"),
                    col("token", "TEXT NOT NULL"),
                    col("last_used", "TEXT NOT NULL")),
                    List.of(),
                    List.of()));

    @Override
    public void migrate(Context context) throws Exception {
        Connection con = context.getConnection();
        boolean rebuilt = rebuildPreOriginCuratedQa(con);
        List<String> created = new ArrayList<>();
        List<String> added = new ArrayList<>();
        for (Table table : TABLES) {
            if (!tableExists(con, table.name())) {
                exec(con, table.createSql(table.name()));
                created.add(table.name());
            } else {
                Set<String> existing = columnNames(con, table.name());
                for (Column column : table.columns()) {
                    if (!existing.contains(column.name())) {
                        exec(con, "ALTER TABLE " + table.name() + " ADD COLUMN " + column.ddl());
                        added.add(table.name() + "." + column.name());
                    }
                }
            }
            for (String index : table.indexes()) {
                exec(con, index);
            }
        }
        log.info("[FLYWAY] V4 — 테이블 생성 {}, 컬럼 추가 {}{}", created, added,
                rebuilt ? ", curated_qa 재생성(source_turn_id nullable)" : "");
    }

    /**
     * 좋아요만 있던 시절의 {@code curated_qa} 는 {@code source_turn_id} 가 {@code NOT NULL} 이었다. 직접 작성한
     * 제안(턴이 없다)을 담으려면 nullable 이어야 하는데 SQLite 는 {@code ALTER} 로 제약을 바꿀 수 없어 테이블을 한 번
     * 다시 짓는다 — 옛 {@code CuratedQaRepository.migrateLegacySchema()} 그대로다. 그 모양의 표식은
     * {@code origin} 컬럼이 없다는 것이고, 그 시절의 행은 정의상 전부 좋아요 출신이다. {@code DROP TABLE} 이 옛
     * 인덱스(부분 인덱스가 아니던 {@code idx_curated_qa_turn})를 함께 가져가므로 인덱스는 이어지는 공통 단계에서
     * 다시 만든다.
     *
     * @return 재생성했으면 {@code true}
     */
    private static boolean rebuildPreOriginCuratedQa(Connection con) throws SQLException {
        if (!tableExists(con, CURATED_QA.name())) return false;
        Set<String> columns = columnNames(con, CURATED_QA.name());
        if (columns.contains("origin")) return false;
        if (!columns.contains("embed_status")) {
            exec(con, "ALTER TABLE curated_qa ADD COLUMN embed_status TEXT NOT NULL DEFAULT 'ok'");
        }
        exec(con, CURATED_QA.createSql("curated_qa_new"));
        exec(con, """
                INSERT INTO curated_qa_new
                    (id, source_turn_id, source_user_id, source_thread_id, question, answer,
                     status, source_doc_version, created_at, updated_at, embed_status,
                     origin, source_submission_id, tags, chunk_count)
                SELECT id, source_turn_id, source_user_id, source_thread_id, question, answer,
                       status, source_doc_version, created_at, updated_at, embed_status,
                       'like', NULL, NULL, 1
                  FROM curated_qa
                """);
        exec(con, "DROP TABLE curated_qa");
        exec(con, "ALTER TABLE curated_qa_new RENAME TO curated_qa");
        return true;
    }

    private static boolean tableExists(Connection con, String table) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static Set<String> columnNames(Connection con, String table) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(\"" + table + "\")")) {
            while (rs.next()) {
                names.add(rs.getString("name"));
            }
        }
        return names;
    }

    private static void exec(Connection con, String sql) throws SQLException {
        try (Statement st = con.createStatement()) {
            st.execute(sql);
        }
    }
}
