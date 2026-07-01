package com.example.ragagent.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Creates the data directory before HikariCP opens the SQLite database.
 *
 * Without this, LlmUsageRepository.init() (and the other @PostConstruct
 * repository inits) fail at startup because ./data/memory.db cannot be
 * created when the parent directory does not yet exist.  RagService would
 * normally create the directory, but its @PostConstruct runs later in the
 * dependency chain.
 */
@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    @Value("${app.data-dir:./data}")
    private String dataDir;

    @Value("${spring.datasource.hikari.maximum-pool-size:1}")
    private int maxPoolSize;

    // sqlite-vec 백엔드일 때만 네이티브 확장을 로드한다.
    @Value("${app.vectorstore.type:chroma}")
    private String vectorStoreType;

    @Value("${app.vectorstore.sqlite-vec.extension-path:}")
    private String sqliteVecExtensionPath;

    @Value("${app.vectorstore.sqlite-vec.entrypoint:}")
    private String sqliteVecEntrypoint;

    // when set (sqlite-vec only), vector + FTS tables live in a SEPARATE SQLite file
    // for operational isolation from memory.db. Empty (default) → unchanged (tables in memory.db).
    // Kept as a feature switch: activation is opt-in and instantly reversible by clearing the path.
    @Value("${app.vectorstore.sqlite-vec.db-path:}")
    private String sqliteVecDbPath;

    /** SpEL guard for the separate-vector-DB feature switch (sqlite-vec backend + non-blank db-path). */
    static final String SEPARATE_VECTOR_DB =
            "'${app.vectorstore.type:chroma}' == 'sqlite-vec' and '${app.vectorstore.sqlite-vec.db-path:}'.trim().length() > 0";

    private boolean separateVectorDb() {
        return "sqlite-vec".equalsIgnoreCase(vectorStoreType == null ? "" : vectorStoreType.trim())
                && sqliteVecDbPath != null && !sqliteVecDbPath.isBlank();
    }

    /**
     * Operational (memory.db) DataSource — conversations, auth, usage, registry, and (unless the
     * separate-vector-DB switch is on) the vector/FTS tables too. Marked {@code @Primary}
     * so the auto-configured {@code JdbcTemplate}/Flyway bind here even when a second (vector)
     * DataSource is present.
     */
    @Bean
    @Primary
    public DataSource dataSource() throws IOException {
        Path dir = Path.of(dataDir);
        Files.createDirectories(dir);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + dir.toAbsolutePath() + "/memory.db");
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(maxPoolSize);
        config.setPoolName("memory-db");
        // vec0 stays on memory.db ONLY when the vector tables also live there. When the separate
        // vector DB is active, memory.db carries no vectors → no extension needed here.
        if (!separateVectorDb()) {
            configureSqliteVec(config, vectorStoreType, sqliteVecExtensionPath, sqliteVecEntrypoint);
        }
        return new HikariDataSource(config);
    }

    /**
     * Dedicated vector DataSource — created only when the separate-vector-DB switch is on.
     * Holds {@code vec_embeddings}/{@code vec_document_chunks}/{@code chunk_fts}. Replicates the
     * operational pool constraints (pool=1) and loads the vec0 extension here instead of on memory.db.
     * WAL/busy_timeout PRAGMAs are applied by {@code SqliteVecSchemaInitializer} on first use.
     */
    @Bean(name = "vectorDataSource")
    @ConditionalOnExpression(SEPARATE_VECTOR_DB)
    public DataSource vectorDataSource() throws IOException {
        Path path = Path.of(sqliteVecDbPath.trim()).toAbsolutePath().normalize();
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        HikariConfig config = buildVectorHikariConfig(
                path, maxPoolSize, vectorStoreType, sqliteVecExtensionPath, sqliteVecEntrypoint);
        log.info("[SQLITE-VEC] separate vector DB active → {}", path);
        return new HikariDataSource(config);
    }

    /**
     * Builds the dedicated vector DB Hikari config (no connection opened — unit-testable).
     * Replicates pool=1 and loads the vec0 extension on this DataSource only.
     */
    static HikariConfig buildVectorHikariConfig(Path dbPath, int poolSize, String type,
                                                String extensionPath, String entrypoint) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + dbPath);
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(poolSize);   // pool=1 replicated (SQLite serializes writes)
        config.setPoolName("vector-db");
        configureSqliteVec(config, type, extensionPath, entrypoint);
        return config;
    }

    /**
     * Template used by the sqlite-vec components ({@code SqliteVecSchemaInitializer}/{@code Verifier}/
     * provider), {@code KeywordSearchRepository} (chunk_fts), and {@code AdminService}. Resolves to the
     * dedicated vector DataSource when separation is on; otherwise aliases the operational DataSource —
     * so chroma mode and non-separated sqlite-vec keep chunk_fts in memory.db (zero regression).
     */
    @Bean(name = "vectorJdbcTemplate")
    @ConditionalOnExpression(SEPARATE_VECTOR_DB)
    public JdbcTemplate vectorJdbcTemplateSeparate(@Qualifier("vectorDataSource") DataSource vectorDataSource) {
        return new JdbcTemplate(vectorDataSource);
    }

    @Bean(name = "vectorJdbcTemplate")
    @ConditionalOnExpression("!(" + SEPARATE_VECTOR_DB + ")")
    public JdbcTemplate vectorJdbcTemplateShared(@Qualifier("dataSource") DataSource operationalDataSource) {
        return new JdbcTemplate(operationalDataSource);
    }

    /**
     * {@code app.vectorstore.type=sqlite-vec}일 때 런타임 확장 로딩을 활성화하고,
     * {@code connectionInitSql}로 풀링된 모든 커넥션에 sqlite-vec {@code vec0} 확장을 로드한다.
     * pool=1이므로 단일 커넥션이 재생성되어도 확장이 다시 로드된다.
     *
     * <p>No official Maven artifact bundles the native binary, so the operator provides the
     * {@code vec0} loadable extension out-of-band and points {@code extension-path} at it
     * (see OPERATOR_MANUAL §sqlite-vec). For the default {@code chroma} backend this is a no-op
     * — the connection is left exactly as before.
     *
     * <p>Package-private + static so it can be unit-tested without opening a real connection.
     */
    static void configureSqliteVec(HikariConfig config, String type, String extensionPath, String entrypoint) {
        if (!"sqlite-vec".equalsIgnoreCase(type == null ? "" : type.trim())) {
            return; // chroma (default) — unchanged
        }
        String path = extensionPath == null ? "" : extensionPath.trim();
        if (path.isEmpty()) {
            throw new IllegalStateException(
                    "app.vectorstore.type=sqlite-vec 인데 app.vectorstore.sqlite-vec.extension-path 가 비어 있습니다. "
                    + "vec0 로더블 확장 바이너리 경로를 지정하세요 (예: /opt/sqlite-vec/vec0).");
        }
        String ep = entrypoint == null ? "" : entrypoint.trim();
        // load_extension SQL 리터럴 안전성: 작은따옴표는 SQL을 깨뜨리고 주입 위험 → 차단.
        if (path.indexOf('\'') >= 0) {
            throw new IllegalStateException("extension-path 에 작은따옴표(')를 포함할 수 없습니다: " + path);
        }
        if (ep.indexOf('\'') >= 0) {
            throw new IllegalStateException("entrypoint 에 작은따옴표(')를 포함할 수 없습니다: " + ep);
        }
        path = resolveExtensionPath(path);
        log.info("[SQLITE-VEC] load_extension path resolved to: {}", path);
        // 1) 드라이버 레벨에서 load_extension() 허용 (xerial 기본 off — 보안)
        config.addDataSourceProperty("enable_load_extension", "true");
        // 2) 커넥션마다 vec0 로드 — connectionInitSql 은 단일 statement 만 실행됨
        String initSql = ep.isEmpty()
                ? "SELECT load_extension('" + path + "')"
                : "SELECT load_extension('" + path + "', '" + ep + "')";
        config.setConnectionInitSql(initSql);
    }

    /**
     * Accept either a file path or a directory path for sqlite-vec extension binaries.
     *
     * <p>If a directory is given, resolve common vec0 filenames for the current platform.
     * This keeps older operator configs like {@code ./data/vec-win64} working on Windows.
     */
    static String resolveExtensionPath(String rawPath) {
        Path p = Path.of(rawPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(p)) {
            return p.toString().replace('\\', '/');
        }

        List<String> candidates = List.of(
                "vec0.dll",
                "vec0.dylib",
                "vec0.so",
                "vec0"
        );
        for (String name : candidates) {
            Path c = p.resolve(name);
            if (Files.isRegularFile(c)) {
                return c.toAbsolutePath().normalize().toString().replace('\\', '/');
            }
        }

        throw new IllegalStateException(
                "sqlite-vec extension-path 가 디렉터리를 가리키지만 vec0 바이너리를 찾지 못했습니다: "
                        + p + " (expected one of " + String.join(", ", candidates) + ")");
    }
}
