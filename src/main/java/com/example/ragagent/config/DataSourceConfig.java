package com.example.ragagent.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

    @Bean
    public DataSource dataSource() throws IOException {
        Path dir = Path.of(dataDir);
        Files.createDirectories(dir);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + dir.toAbsolutePath() + "/memory.db");
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(maxPoolSize);
        configureSqliteVec(config, vectorStoreType, sqliteVecExtensionPath, sqliteVecEntrypoint);
        return new HikariDataSource(config);
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
