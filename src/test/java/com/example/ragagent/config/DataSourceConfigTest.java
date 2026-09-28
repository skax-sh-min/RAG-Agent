package com.example.ragagent.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.example.ragagent.ingestion.KeywordSearchRepository;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DataSourceConfig} 단위 테스트 — vec0 로딩 설정({@link DataSourceConfig#configureSqliteVec}), 세션 PRAGMA,
 * 그리고 <b>DB 파일은 하나</b>라는 규칙(경로 결정·배선). 네이티브 바이너리는 필요 없다 — sqlite-vec 모드에서
 * 실제로 vec0 를 싣고 여는 배선은 {@code -Dsqlitevec.path} 로 게이트된 {@code DataSourceJdbcTemplateWiringTest} 가 본다.
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
            assertThat(pragma(st, "cache_size"))
                    .as("음수는 KiB. 드라이버 기본값 -2000(2MB)이면 코퍼스가 커질 때 페이지 캐시를 포기한다")
                    .isEqualTo("-32768");
            assertThat(pragma(st, "mmap_size"))
                    .as("기본값 0 = mmap 꺼짐. 읽기마다 OS 캐시 → SQLite 버퍼 복사가 일어나는데, "
                        + "ANN 인덱스 없는 vec0 KNN 이 이 앱에서 그 복사가 가장 비싼 자리다")
                    .isEqualTo("268435456");
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
            assertThat(pragma(st, "cache_size")).isEqualTo("-32768");
            assertThat(pragma(st, "mmap_size")).isEqualTo("268435456");
        }
    }

    /**
     * <b>왜 {@code Path} 가 아니라 문자열로 거는가</b>: Windows 는 파일명에 {@code ?} 를 허용하지
     * 않아 {@code Path.of("/data/we?rd/memory.db")} 가 {@link java.nio.file.InvalidPathException}
     * 으로 먼저 죽는다 — 가드에 닿지도 못한다. 판정 대상은 URL 에 이어 붙일 문자열이므로
     * 문자열로 걸어야 {@code ?}/{@code &} 양쪽이 OS 와 무관하게 검증된다.
     */
    @Test
    @DisplayName("sqliteUrl: 경로에 ?/& 가 있으면 거부 — 파라미터 경계가 깨져 엉뚱한 파일이 열린다")
    void sqliteUrl_rejectsPathsThatWouldBreakTheQueryString() {
        assertThatThrownBy(() -> DataSourceConfig.sqliteUrl("/data/we?rd/memory.db"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/data/we?rd/memory.db");
        assertThatThrownBy(() -> DataSourceConfig.sqliteUrl("/data/a&b/memory.db"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/data/a&b/memory.db");
    }

    @Test
    @DisplayName("sqliteUrl(Path): Path 입구도 같은 가드를 지난다")
    void sqliteUrlPath_goesThroughTheSameGuard() {
        // '&' 는 Windows/POSIX 양쪽에서 합법적인 파일명 문자라 Path 로 만들 수 있다 ('?' 는 Windows 에서 불가)
        assertThatThrownBy(() -> DataSourceConfig.sqliteUrl(Path.of("/data/a&b/memory.db")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("a&b");
    }

    private static String pragma(java.sql.Statement st, String name) throws Exception {
        try (java.sql.ResultSet rs = st.executeQuery("PRAGMA " + name)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    // ── 파일은 하나 — 경로 규칙 ─────────────────────────────────────

    @Test
    @DisplayName("resolveDbPath: 기본은 {data-dir}/memory.db (옛 db-path 가 비었거나 공백이면)")
    void resolveDbPath_defaultsToMemoryDbInDataDir(@TempDir Path dir) {
        Path expected = dir.toAbsolutePath().normalize().resolve("memory.db");
        assertThat(DataSourceConfig.resolveDbPath(dir.toString(), "sqlite-vec", "")).isEqualTo(expected);
        assertThat(DataSourceConfig.resolveDbPath(dir.toString(), "sqlite-vec", "   ")).isEqualTo(expected);
        assertThat(DataSourceConfig.resolveDbPath(dir.toString(), "sqlite-vec", null)).isEqualTo(expected);
        assertThat(DataSourceConfig.resolveDbPath(dir.toString(), "chroma", null)).isEqualTo(expected);
    }

    /**
     * <b>이 규칙이 지키는 것</b>: 옛 분리 스위치를 켰던 배포는 운영 테이블까지 전부 그 파일에 있다
     * ({@code DataSourceJdbcTemplateWiringTest} 가 기록한 배선 사고). 이 값을 무시하고 memory.db 를 열면
     * 재기동 한 번에 계정·대화·설정·문서 목록이 사라진 것처럼 보인다 — 데이터는 그대로인데 다른 파일을 본다.
     */
    @Test
    @DisplayName("resolveDbPath: sqlite-vec + 옛 db-path → 그 파일이 유일한 DB (그 배포의 데이터가 전부 거기 있다)")
    void resolveDbPath_sqliteVecOpensTheLegacyFile(@TempDir Path dir) {
        Path legacy = dir.resolve("elsewhere").resolve("vector.db");
        Path expected = legacy.toAbsolutePath().normalize();
        assertThat(DataSourceConfig.resolveDbPath(dir.toString(), "sqlite-vec", "  " + legacy + "  "))
                .isEqualTo(expected);
        assertThat(DataSourceConfig.resolveDbPath(dir.toString(), " SQLite-Vec ", legacy.toString()))
                .isEqualTo(expected);
    }

    /** chroma 에서는 그 스위치가 원래 무시됐다 — 따르면 설정에 남은 한 줄이 빈 파일을 열게 만든다. */
    @Test
    @DisplayName("resolveDbPath: chroma 는 옛 db-path 를 무시한다 (예전에도 무시했다)")
    void resolveDbPath_chromaIgnoresTheLegacyPath(@TempDir Path dir) {
        assertThat(DataSourceConfig.resolveDbPath(dir.toString(), "chroma", dir.resolve("vector.db").toString()))
                .isEqualTo(dir.toAbsolutePath().normalize().resolve("memory.db"));
    }

    @Test
    @DisplayName("resolveDbPath: 옛 db-path 의 상대 경로는 예전처럼 작업 디렉터리 기준 (같은 파일이 열려야 한다)")
    void resolveDbPath_relativeLegacyPathStaysWorkingDirRelative() {
        assertThat(DataSourceConfig.resolveDbPath("./somewhere-else", "sqlite-vec", "./data/vector.db"))
                .isEqualTo(Path.of("./data/vector.db").toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("buildHikariConfig: 세션 PRAGMA URL + pool=1 + (sqlite-vec) 커넥션마다 vec0 load_extension")
    void hikariConfig_urlPoolAndExtension(@TempDir Path dir) {
        Path db = dir.resolve("memory.db");
        HikariConfig c = DataSourceConfig.buildHikariConfig(db, 1, "sqlite-vec", "/opt/sqlite-vec/vec0", "");

        assertThat(c.getJdbcUrl()).isEqualTo(DataSourceConfig.sqliteUrl(db));
        assertThat(c.getMaximumPoolSize()).isEqualTo(1);
        assertThat(c.getDataSourceProperties().getProperty("enable_load_extension")).isEqualTo("true");
        assertThat(c.getConnectionInitSql())
                .startsWith("SELECT load_extension('")
                .contains("/opt/sqlite-vec/vec0");
    }

    /** /admin 이 표시하는 경로의 출처 — 설정에서 다시 유도하지 않고 DataSource 가 실제로 연 파일을 읽는다. */
    @Test
    @DisplayName("sqliteFilePath: sqliteUrl 의 역 — Hikari 가 아니면 null")
    void sqliteFilePath_invertsSqliteUrl(@TempDir Path dir) {
        Path db = dir.resolve("memory.db");
        try (HikariDataSource hikari = new HikariDataSource()) {   // 인자 없는 생성자: 풀을 시작하지 않는다
            hikari.setJdbcUrl(DataSourceConfig.sqliteUrl(db));
            assertThat(DataSourceConfig.sqliteFilePath(hikari)).isEqualTo(db.toString());
        }
        assertThat(DataSourceConfig.sqliteFilePath(null)).isNull();
        assertThat(DataSourceConfig.sqliteFilePath(new DriverManagerDataSource("jdbc:sqlite:" + db))).isNull();
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DataSourceConfig.class);

    @Test
    @DisplayName("기본(chroma): DataSource 하나 · JdbcTemplate 하나 — vectorJdbcTemplate 가 그 DataSource 를 감싼다")
    void defaultMode_oneDataSourceOneTemplate(@TempDir Path dir) {
        runner.withPropertyValues("app.data-dir=" + dir)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBeanNamesForType(DataSource.class)).containsExactly("dataSource");
                    assertThat(ctx.getBeanNamesForType(JdbcTemplate.class)).containsExactly("vectorJdbcTemplate");
                    JdbcTemplate vectorTpl = (JdbcTemplate) ctx.getBean("vectorJdbcTemplate");
                    assertThat(vectorTpl.getDataSource()).isSameAs(ctx.getBean("dataSource", DataSource.class));
                    assertThat(DataSourceConfig.sqliteFilePath(vectorTpl.getDataSource()))
                            .isEqualTo(dir.toAbsolutePath().normalize().resolve("memory.db").toString());
                });
    }

    @Test
    @DisplayName("chroma + 옛 db-path: 여전히 memory.db 하나 — 그 경로에는 파일을 만들지 않는다")
    void chromaMode_legacyPathLeavesNoSecondFile(@TempDir Path dir) {
        Path legacy = dir.resolve("vector.db");
        runner.withPropertyValues("app.data-dir=" + dir, "app.vectorstore.sqlite-vec.db-path=" + legacy)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBeanNamesForType(DataSource.class)).containsExactly("dataSource");
                    assertThat(DataSourceConfig.sqliteFilePath(ctx.getBean(DataSource.class)))
                            .isEqualTo(dir.toAbsolutePath().normalize().resolve("memory.db").toString());
                    assertThat(legacy).doesNotExist();
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
