package com.example.ragagent.config;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.example.ragagent.ingestion.KeywordSearchRepository;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DataSourceConfig#configureSqliteVec} 단위 테스트.
 * 실제 커넥션을 열지 않고 HikariConfig 결과만 검증한다 (네이티브 바이너리 불필요).
 */
class DataSourceConfigTest {

    private HikariConfig base() {
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl("jdbc:sqlite:/tmp/test.db");
        c.setDriverClassName("org.sqlite.JDBC");
        c.setMaximumPoolSize(1);
        return c;
    }

    @Test
    @DisplayName("chroma(기본): 커넥션 변경 없음 — enable_load_extension/initSql 미설정")
    void chroma_noChange() {
        HikariConfig c = base();
        DataSourceConfig.configureSqliteVec(c, "chroma", "", "");
        assertThat(c.getDataSourceProperties().getProperty("enable_load_extension")).isNull();
        assertThat(c.getConnectionInitSql()).isNull();
    }

    @Test
    @DisplayName("null/blank type → chroma 취급, 변경 없음")
    void nullType_treatedAsChroma() {
        HikariConfig c = base();
        DataSourceConfig.configureSqliteVec(c, null, "/opt/vec0", "");
        assertThat(c.getDataSourceProperties().getProperty("enable_load_extension")).isNull();
        assertThat(c.getConnectionInitSql()).isNull();
    }

    @Test
    @DisplayName("sqlite-vec + 경로: enable_load_extension=true + load_extension initSql")
    void sqliteVec_setsLoader() {
        HikariConfig c = base();
        DataSourceConfig.configureSqliteVec(c, "sqlite-vec", "/opt/sqlite-vec/vec0", "");
        assertThat(c.getDataSourceProperties().getProperty("enable_load_extension")).isEqualTo("true");
        assertThat(c.getConnectionInitSql())
                .startsWith("SELECT load_extension('")
                .contains("/opt/sqlite-vec/vec0")
                .endsWith("')");
    }

    @Test
    @DisplayName("sqlite-vec + 대소문자 무시 + 공백 trim")
    void sqliteVec_caseInsensitiveAndTrimmed() {
        HikariConfig c = base();
        DataSourceConfig.configureSqliteVec(c, "  SQLite-Vec ", "  /opt/vec0  ", "");
        assertThat(c.getConnectionInitSql())
                .startsWith("SELECT load_extension('")
                .contains("/opt/vec0")
                .endsWith("')");
    }

    @Test
    @DisplayName("sqlite-vec + entrypoint 지정 → load_extension(path, entrypoint)")
    void sqliteVec_withEntrypoint() {
        HikariConfig c = base();
        DataSourceConfig.configureSqliteVec(c, "sqlite-vec", "/opt/vec0", "sqlite3_vec_init");
        assertThat(c.getConnectionInitSql())
                .startsWith("SELECT load_extension('")
                .contains("/opt/vec0")
                .endsWith("', 'sqlite3_vec_init')");
    }

    @Test
    @DisplayName("sqlite-vec + 경로 누락 → 명확한 오류로 기동 실패")
    void sqliteVec_blankPath_throws() {
        HikariConfig c = base();
        assertThatThrownBy(() -> DataSourceConfig.configureSqliteVec(c, "sqlite-vec", "  ", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("extension-path");
    }

    @Test
    @DisplayName("경로/엔트리포인트의 작은따옴표 차단 (SQL 주입/깨짐 방지)")
    void sqliteVec_rejectsSingleQuote() {
        assertThatThrownBy(() -> DataSourceConfig.configureSqliteVec(base(), "sqlite-vec", "/x/v'0", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("작은따옴표");
        assertThatThrownBy(() -> DataSourceConfig.configureSqliteVec(base(), "sqlite-vec", "/x/vec0", "ev'il"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("작은따옴표");
    }

    @Test
    @DisplayName("디렉터리 경로 입력 시 vec0 바이너리를 자동 해석한다")
    void sqliteVec_resolvesDirectoryPath(@TempDir Path dir) throws IOException {
        Path dll = dir.resolve("vec0.dll");
        Files.writeString(dll, "stub");

        HikariConfig c = base();
        DataSourceConfig.configureSqliteVec(c, "sqlite-vec", dir.toString(), "");

        String expected = "SELECT load_extension('" + dll.toAbsolutePath().normalize().toString().replace('\\', '/') + "')";
        assertThat(c.getConnectionInitSql()).isEqualTo(expected);
    }

    @Test
    @DisplayName("디렉터리 경로인데 vec0 바이너리가 없으면 명확한 오류")
    void sqliteVec_directoryWithoutBinary_throws(@TempDir Path dir) {
        HikariConfig c = base();

        assertThatThrownBy(() -> DataSourceConfig.configureSqliteVec(c, "sqlite-vec", dir.toString(), ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vec0 바이너리");
    }

    // ── 세션 PRAGMA (URL 파라미터) ──────────────────────────────────

    /**
     * <b>이 테스트가 존재하는 이유</b>: 예전에는
     * {@code spring.datasource.hikari.connection-init-sql=PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000;}
     * 로 "설정돼 있었다". 두 가지가 동시에 틀렸다 — DataSourceConfig 가 HikariConfig 를 직접 만들어
     * 그 프로퍼티가 바인딩되지 않았고, 설령 바인딩됐어도 드라이버는 세미콜론으로 이어 붙인 문장 중
     * <b>첫 것만</b> 실행한다. 그래서 "설정했다"와 "실제로 걸렸다"가 달랐고, 그 간극은 설정 문자열을
     * 읽는 것만으로는 절대 드러나지 않는다. 그러니 여기서는 <b>진짜 커넥션을 열어 되물어본다</b>.
     */
    @Test
    @DisplayName("sqliteUrl: 실제 커넥션에 PRAGMA 가 걸린다 — 설정한 값과 되물은 값이 같아야 한다")
    void sqliteUrl_pragmasActuallyApplyOnARealConnection(@TempDir Path dir) throws Exception {
        String url = DataSourceConfig.sqliteUrl(dir.resolve("pragma.db"));

        try (java.sql.Connection c = java.sql.DriverManager.getConnection(url);
             java.sql.Statement st = c.createStatement()) {
            assertThat(pragma(st, "journal_mode")).isEqualToIgnoringCase("wal");
            assertThat(pragma(st, "busy_timeout"))
                    .as("드라이버 기본값 3000 이 아니라 우리가 지정한 값이어야 한다")
                    .isEqualTo("5000");
            assertThat(pragma(st, "synchronous"))
                    .as("1 = NORMAL (WAL 권장). 기본값 FULL(2) 이면 커밋마다 fsync 한다")
                    .isEqualTo("1");
        }
    }

    @Test
    @DisplayName("sqliteUrl: 풀이 커넥션을 다시 열어도 PRAGMA 가 유지된다")
    void sqliteUrl_pragmasSurviveAReconnect(@TempDir Path dir) throws Exception {
        String url = DataSourceConfig.sqliteUrl(dir.resolve("pragma.db"));
        try (java.sql.Connection first = java.sql.DriverManager.getConnection(url)) { /* 열었다 닫는다 */ }

        try (java.sql.Connection c = java.sql.DriverManager.getConnection(url);
             java.sql.Statement st = c.createStatement()) {
            assertThat(pragma(st, "busy_timeout")).isEqualTo("5000");
            assertThat(pragma(st, "synchronous")).isEqualTo("1");
        }
    }

    @Test
    @DisplayName("sqliteUrl: 경로에 ?/& 가 있으면 거부 — 파라미터 경계가 깨져 엉뚱한 파일이 열린다")
    void sqliteUrl_rejectsPathsThatWouldBreakTheQueryString() {
        assertThatThrownBy(() -> DataSourceConfig.sqliteUrl(Path.of("/data/we?rd/memory.db")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("?");
        assertThatThrownBy(() -> DataSourceConfig.sqliteUrl(Path.of("/data/a&b/memory.db")))
                .isInstanceOf(IllegalStateException.class);
    }

    private static String pragma(java.sql.Statement st, String name) throws Exception {
        try (java.sql.ResultSet rs = st.executeQuery("PRAGMA " + name)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    // ── separate vector DB ─────────────────────────────────────────

    @Test
    @DisplayName("buildVectorHikariConfig: vector.db URL + pool=1 + vec0 load_extension")
    void vectorHikari_pointsAtVectorDbWithPoolAndExtension(@TempDir Path dir) {
        Path vectorDb = dir.resolve("vector.db");
        HikariConfig c = DataSourceConfig.buildVectorHikariConfig(
                vectorDb, 1, "sqlite-vec", "/opt/sqlite-vec/vec0", "");

        assertThat(c.getJdbcUrl()).isEqualTo(DataSourceConfig.sqliteUrl(vectorDb));
        assertThat(c.getMaximumPoolSize()).isEqualTo(1);
        assertThat(c.getPoolName()).isEqualTo("vector-db");
        assertThat(c.getDataSourceProperties().getProperty("enable_load_extension")).isEqualTo("true");
        assertThat(c.getConnectionInitSql())
                .startsWith("SELECT load_extension('")
                .contains("/opt/sqlite-vec/vec0");
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DataSourceConfig.class);

    @Test
    @DisplayName("기본(chroma): vectorJdbcTemplate 는 운영 DataSource 별칭 — 별도 vectorDataSource 없음(회귀 가드)")
    void defaultMode_vectorTemplateAliasesPrimary(@TempDir Path dir) {
        runner.withPropertyValues("app.data-dir=" + dir)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean("vectorDataSource");
                    assertThat(ctx).hasBean("vectorJdbcTemplate");
                    // exactly one DataSource, and the vector template is backed by it (chunk_fts stays in memory.db)
                    assertThat(ctx.getBeanNamesForType(DataSource.class)).hasSize(1);
                    JdbcTemplate vectorTpl = (JdbcTemplate) ctx.getBean("vectorJdbcTemplate");
                    assertThat(vectorTpl.getDataSource()).isSameAs(ctx.getBean("dataSource", DataSource.class));
                });
    }

    @Test
    @DisplayName("기본 모드: 다운스트림 소비자(KeywordSearchRepository)가 @Qualifier(vectorJdbcTemplate)로 memory.db에 실제 배선")
    void defaultMode_downstreamConsumerWiresThroughQualifier(@TempDir Path dir) {
        new ApplicationContextRunner()
                .withUserConfiguration(DataSourceConfig.class, KeywordSearchRepository.class)
                .withPropertyValues("app.data-dir=" + dir)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    // @Qualifier("vectorJdbcTemplate") resolved + @PostConstruct created chunk_fts on the real DB
                    KeywordSearchRepository repo = ctx.getBean(KeywordSearchRepository.class);
                    assertThat(repo.isAvailable()).isTrue();
                    // and that template is the memory.db (operational) DataSource in the non-separated path
                    JdbcTemplate vectorTpl = (JdbcTemplate) ctx.getBean("vectorJdbcTemplate");
                    assertThat(vectorTpl.getDataSource()).isSameAs(ctx.getBean("dataSource", DataSource.class));
                });
    }
}
