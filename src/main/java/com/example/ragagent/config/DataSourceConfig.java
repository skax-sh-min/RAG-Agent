package com.example.ragagent.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * SQLite DataSource — <b>이 앱이 여는 SQLite 파일은 하나다.</b> 운영 테이블(대화·계정·설정·레지스트리…),
 * 두 백엔드 공통인 {@code chunk_fts}, sqlite-vec 백엔드의 벡터 테이블이 모두 같은 파일·같은 풀(pool=1)을
 * 쓴다. 경로는 {@link #resolveDbPath} 가 정한다.
 *
 * <p><b>예전에는 파일을 둘로 나누는 스위치가 있었다</b>(Step 5.10, {@code app.vectorstore.sqlite-vec.db-path}).
 * 인덱싱 쓰기와 운영 쓰기의 락을 나누려던 것인데 <b>한 번도 그렇게 동작하지 않았다</b> — 이 클래스가
 * {@code vectorJdbcTemplate} 빈을 정의하는 순간 Spring Boot 의 {@code JdbcTemplate} 자동설정
 * ({@code @ConditionalOnMissingBean(JdbcOperations.class)})이 물러나, 한정자 없이 {@code JdbcTemplate} 을
 * 받는 운영 저장소까지 전부 벡터 파일에 썼다. 남은 것은 비용뿐이었다: 아무것도 쌓이지 않는 {@code memory.db}
 * (백업·초기화 대상을 헷갈리게 했다), 그 파일에만 적용되고 "성공"으로 보고되는 Flyway, 그리고 두 파일이
 * 사실 한 파일이라는 것을 전제로 굳어진 쿼리 — {@code QuestionReuseRepository.findSourcePreviewRows()} 는
 * 운영 테이블과 FTS/벡터 테이블을 SQL 하나로 조인한다. 분리를 "제대로" 만들려면 그 조인을 풀고 기존 배포의
 * 데이터를 옮겨야 했고, 얻는 것은 측정된 적 없는 락 분리였다. 그래서 파일을 하나로 합쳤다 — 실행 중 I/O 는
 * 이미 한 파일·한 커넥션이었으므로 성능은 그대로다.
 *
 * <p>The data directory is created here, before HikariCP opens the file: Flyway migrates the file
 * during context startup, before anything else (RagService) would create the directory.
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

    // 예전 "벡터 DB 분리" 스위치. 이제는 sqlite-vec 백엔드에서 **유일한 DB 파일의 경로**로만 읽는다
    // (구 배포 호환 — 그 배포의 데이터는 전부 이 파일에 있다). 규칙은 resolveDbPath().
    @Value("${app.vectorstore.sqlite-vec.db-path:}")
    private String legacyVectorDbPath;

    /** {@code app.data-dir} 안의 기본 DB 파일 이름. */
    static final String DEFAULT_DB_FILE = "memory.db";

    /** {@link #sqliteUrl} 이 쓰고 {@link #sqliteFilePath} 가 읽는 접두사 — 쓰는 쪽과 읽는 쪽이 같은 값을 본다. */
    private static final String JDBC_SQLITE_PREFIX = "jdbc:sqlite:";

    /**
     * 커넥션마다 걸려야 하는 SQLite 세션 PRAGMA.
     *
     * <p><b>{@code busy_timeout}</b> — 쓰기 락이 잡혀 있을 때 즉시 {@code SQLITE_BUSY} 로 실패하는
     * 대신 이만큼 기다린다. pool=1 이라 앱 안에서는 경합이 드물지만, 같은 파일을 여는 다른
     * 프로세스(운영자의 {@code sqlite3} 셸, 백업 도구)와는 여전히 부딪친다.
     *
     * <p><b>{@code synchronous=NORMAL}</b> — WAL 모드의 표준 권장값이다. 기본값 FULL 은 커밋마다
     * fsync 를 하는데, 이 앱은 한 턴이 끝날 때 {@code addTurn} → 이미지 참조 → 검색 진단 →
     * 검증 → 출처 스냅샷으로 <b>연속 여러 번</b> 쓰고 pool=1 이라 그동안 다른 요청이 커넥션을
     * 잡지 못한다. NORMAL 은 전원이 끊기면 마지막 트랜잭션 몇 개를 잃을 수 있지만 <b>DB 가
     * 깨지지는 않는다</b>(WAL 의 보장) — 잃는 것이 대화 한 턴의 꼬리라 이 앱에는 맞는 거래다.
     *
     * <p><b>{@code mmap_size}</b> — 읽기를 메모리 맵으로 처리한다. SQLite 기본값은 <b>0(꺼짐)</b>
     * 이라 모든 페이지가 OS 파일 캐시에서 SQLite 버퍼로 <b>복사</b>된 뒤 쓰인다. 이 앱의 검색은
     * 그 복사가 가장 비싼 모양이다 — vec0 KNN 은 ANN 인덱스 없이 버전 파티션의 벡터를 전부
     * 훑고(1024차원 × 청크 수), FTS5 trigram 인덱스도 크다. 맵이 걸리면 그 복사가 사라진다.
     * 값은 <b>상한</b>이며 실제로는 파일 크기만큼만 매핑된다. 가상 주소 공간일 뿐이고 페이지는
     * OS 파일 캐시와 <b>공유</b>되므로, 오히려 이중 버퍼링이 줄어 상주 메모리가 늘지 않는다.
     * 쓰기는 영향받지 않는다 — SQLite 의 mmap 은 기본이 읽기 전용이고 쓰기는 평소 경로로 간다.
     * <b>대가</b>: 매핑된 페이지에서 I/O 오류가 나면 오류 코드 대신 프로세스가 죽는다(SIGBUS).
     * 로컬 디스크 전제라 받아들인 거래이며, DB 파일을 <b>네트워크 공유에 두는 배포라면 이 값을
     * 0 으로 되돌려야 한다</b>.
     *
     * <p><b>{@code cache_size}</b> — 음수는 KiB 단위다. 기본값 {@code -2000}(2MB)은 코퍼스가
     * 조금만 커져도 페이지 캐시 적중을 포기하는 크기다. mmap 이 켜져 있으면 대부분의 이득이
     * 그쪽으로 흡수되지만(아래 측정), 이 값을 함께 두는 이유는 mmap 을 <b>쓸 수 없는 경우</b>가
     * 있기 때문이다 — {@code SQLITE_MAX_MMAP_SIZE=0} 으로 빌드된 드라이버, 매핑이 실패하는 파일
     * 시스템, 위의 네트워크 공유 예외. 그때 FTS 축의 이득을 남겨 두는 폴백이다. 상한일 뿐이고
     * 지연 할당이라 작은 DB 는 아무것도 더 쓰지 않는다.
     *
     * <p><b>실측</b>(2,389청크 / 58MB / 1024차원, 질의 100회, 5라운드 중앙값):
     * <pre>
     *                          vec0 KNN   FTS MATCH   LIKE 폴백
     *   기본(2MB, mmap 꺼짐)      1,752ms      222ms      879ms
     *   mmap 만                    577ms      153ms      480ms
     *   cache 32MB + mmap          487ms      122ms      488ms
     * </pre>
     * KNN 은 mmap 이 전부이고(3.2배) cache_size 는 거기에 더 보태지 않는다. 반대로 mmap 없이
     * cache_size 만 키우면 FTS 쪽은 2.2배(MATCH 281→125ms)가 나온다 — 그래서 둘 다 둔다.
     *
     * <p><b>왜 URL 파라미터인가.</b> {@code connectionInitSql} 은 statement 하나만 실행하고,
     * sqlite-vec 백엔드에서는 그 자리를 {@code load_extension()} 이 이미 쓰고 있다. 세미콜론으로
     * 이어 붙이는 것도 방법이 아니다 — <b>드라이버가 첫 문장만 실행한다</b>(그래서 예전
     * {@code spring.datasource.hikari.connection-init-sql} 의 {@code busy_timeout=5000} 은 한 번도
     * 적용된 적이 없고 드라이버 기본값 3000 이 걸려 있었다). xerial 드라이버는 URL 쿼리
     * 파라미터로 PRAGMA 를 받으며, 그 값은 풀이 커넥션을 다시 열어도 유지된다.
     * {@code DataSourceConfigTest} 가 <b>실제 커넥션을 열어 되물어본다</b> — 이 다섯 값 중 하나가
     * 드라이버에 무시돼도 설정 문자열만 읽어서는 드러나지 않기 때문이다.
     */
    private static final String SESSION_PRAGMAS =
            "journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL"
            + "&cache_size=-32768&mmap_size=268435456";

    /**
     * 위 PRAGMA 를 얹은 SQLite JDBC URL. 경로에 {@code ?}/{@code &} 가 있으면 파라미터 경계가
     * 깨져 <b>엉뚱한 파일</b>이 열리므로(조용한 실패다) 미리 막는다.
     *
     * <p>Package-private + static — 실제 커넥션 없이 단위 테스트한다.
     */
    static String sqliteUrl(Path dbPath) {
        return sqliteUrl(dbPath.toString());
    }

    /**
     * 가드가 실제로 판정하는 자리. <b>{@link Path} 가 아니라 문자열을 받는 이유</b>:
     * Windows 의 {@code Path} 는 {@code ?} 를 애초에 담지 못해
     * {@code Path.of("/data/we?rd/memory.db")} 자체가 {@link java.nio.file.InvalidPathException}
     * 으로 죽는다 — 이 가드에 닿기도 전에. 그래서 {@code Path} 로만 시험하면
     * Windows 빌드에서는 {@code &} 쪽 절반만 검증되고, 정작 오류 메시지가 지목하는
     * {@code ?} 는 한 번도 지나가지 않는다. 판정 대상은 URL 로 이어 붙일 <b>문자열</b>이므로
     * 문자열을 받는 자리를 따로 두어 양쪽 문자를 OS 와 무관하게 시험한다.
     */
    static String sqliteUrl(String dbPath) {
        if (dbPath.indexOf('?') >= 0 || dbPath.indexOf('&') >= 0) {
            throw new IllegalStateException(
                    "SQLite 파일 경로에 '?' 또는 '&' 를 포함할 수 없습니다(JDBC URL 파라미터와 충돌): " + dbPath);
        }
        return JDBC_SQLITE_PREFIX + dbPath + "?" + SESSION_PRAGMAS;
    }

    /**
     * {@link #sqliteUrl} 의 역 — DataSource 가 <b>실제로 연</b> SQLite 파일의 경로.
     *
     * <p>{@code /admin} 이 파일 위치를 설정값에서 따로 계산하지 않고 여기서 읽는다. 따로 계산하던 동안
     * 분리 배포의 {@code /admin} 은 아무것도 쌓이지 않는 {@code memory.db} 를 "운영 DB"로 보여 줬다 —
     * 설정에서 다시 유도한 값은 배선이 틀렸을 때 그 틀림을 그대로 물려받는다.
     *
     * @return 파일 경로, 또는 HikariCP 가 아니거나(단위 테스트의 mock) 형식이 다르면 {@code null}
     */
    public static String sqliteFilePath(DataSource dataSource) {
        if (!(dataSource instanceof HikariDataSource hikari)) return null;
        String url = hikari.getJdbcUrl();
        if (url == null || !url.startsWith(JDBC_SQLITE_PREFIX)) return null;
        String rest = url.substring(JDBC_SQLITE_PREFIX.length());
        int query = rest.indexOf('?');
        return query >= 0 ? rest.substring(0, query) : rest;
    }

    /**
     * 이 앱이 여는 SQLite 파일 — {@code sqlite-vec} 백엔드이고 옛 분리 스위치
     * ({@code app.vectorstore.sqlite-vec.db-path})에 값이 있으면 <b>그 경로</b>, 아니면
     * {@code {data-dir}/memory.db}.
     *
     * <p><b>규칙을 이렇게 고른 이유: "재기동 후 여는 파일 = 지금 데이터가 있는 파일"이 모든 기존 설정에서
     * 성립해야 한다.</b> 스위치를 켰던 배포는 운영 테이블까지 전부 그 경로의 파일에 있다(클래스 주석). 그 값을
     * 무시하고 {@code memory.db} 를 열면 재기동 한 번에 계정·대화·설정·문서 목록이 사라진 것처럼 보인다 —
     * 데이터는 그대로인데 앱이 다른 파일을 보는 것이다. 반대로 chroma 백엔드에서는 이 스위치가 원래 무시됐으므로
     * 여기서도 무시한다. 따르면 chroma 배포의 설정에 남아 있던 한 줄이 빈 파일을 열게 만든다.
     *
     * <p>상대 경로는 예전과 같이 작업 디렉터리 기준이다({@code data-dir} 기준이 아니다) — 같은 파일이 열려야
     * 하므로. Package-private + static — 실제 커넥션 없이 단위 테스트한다.
     */
    static Path resolveDbPath(String dataDir, String vectorStoreType, String legacyVectorDbPath) {
        if (isSqliteVec(vectorStoreType) && legacyVectorDbPath != null && !legacyVectorDbPath.isBlank()) {
            return Path.of(legacyVectorDbPath.trim()).toAbsolutePath().normalize();
        }
        return defaultDbPath(dataDir);
    }

    static Path defaultDbPath(String dataDir) {
        return Path.of(dataDir).toAbsolutePath().normalize().resolve(DEFAULT_DB_FILE);
    }

    private static boolean isSqliteVec(String type) {
        return "sqlite-vec".equalsIgnoreCase(type == null ? "" : type.trim());
    }

    /**
     * The one SQLite DataSource. {@code @Primary} so Flyway and the auto-configured infrastructure bind
     * here; with a single DataSource this is also what every repository reads and writes.
     */
    @Bean
    @Primary
    public DataSource dataSource() throws IOException {
        Files.createDirectories(Path.of(dataDir));
        Path dbPath = resolveDbPath(dataDir, vectorStoreType, legacyVectorDbPath);
        if (dbPath.getParent() != null) Files.createDirectories(dbPath.getParent());
        if (!dbPath.equals(defaultDbPath(dataDir))) {
            warnLegacyPath(dbPath);
        }
        return new HikariDataSource(buildHikariConfig(
                dbPath, maxPoolSize, vectorStoreType, sqliteVecExtensionPath, sqliteVecEntrypoint));
    }

    /**
     * 옛 분리 스위치로 열린 배포에 두 가지를 알린다: 그 경로가 이제 유일한 DB 라는 것, 그리고 기본 위치에
     * {@code memory.db} 가 남아 있다면 더 이상 아무도 열지 않는다는 것. 후자를 "빈 파일"이라고 단정하지 않는
     * 이유는 스위치를 <b>도중에</b> 켠 배포가 있을 수 있어서다 — 그 경우 켜기 전의 데이터가 거기 그대로 있다.
     */
    private void warnLegacyPath(Path dbPath) {
        log.warn("[DB] app.vectorstore.sqlite-vec.db-path(SQLITE_VEC_DB_PATH) 는 더 이상 벡터 전용 파일이 아니다 — "
                + "이 경로가 앱의 유일한 DB 파일이다: {} (분리 스위치를 켠 배포는 모든 테이블이 원래 여기 있었다). "
                + "새 배포는 이 값을 비워 {DATA_DIR}/memory.db 하나를 쓴다 — 이름 정리 절차는 OPERATOR_MANUAL §6.3.1", dbPath);
        Path leftover = defaultDbPath(dataDir);
        if (Files.exists(leftover)) {
            log.warn("[DB] 쓰지 않는 파일: {} — 예전 분리 구성이 만든 것으로 이제 아무도 열지 않는다. 스위치를 처음부터 "
                    + "켰다면 빈 테이블과 Flyway 이력뿐이지만, 도중에 켰다면 켜기 전의 데이터가 들어 있을 수 있다. "
                    + "확인한 뒤 정리할 것(OPERATOR_MANUAL §6.3.1)", leftover);
        }
    }

    /**
     * Builds the Hikari config for {@code dbPath} without opening a connection (unit-testable):
     * session PRAGMAs on the URL ({@link #SESSION_PRAGMAS}), pool=1, vec0 on every connection in
     * sqlite-vec mode.
     */
    static HikariConfig buildHikariConfig(Path dbPath, int poolSize, String type,
                                          String extensionPath, String entrypoint) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(sqliteUrl(dbPath));
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(poolSize);   // pool=1 — SQLite serializes writes even in WAL mode
        config.setPoolName("sqlite");
        configureSqliteVec(config, type, extensionPath, entrypoint);
        return config;
    }

    /**
     * 컨텍스트의 <b>유일한</b> {@code JdbcTemplate}. 앱이 {@code JdbcOperations} 빈을 하나라도 정의하면
     * Boot 의 자동설정 템플릿이 물러나므로, {@code @Qualifier} 없는 주입(운영 저장소 전부)도 이 빈을 받는다 —
     * 파일이 하나이므로 그것이 맞다.
     *
     * <p>이름과 {@code @Qualifier("vectorJdbcTemplate")} 는 벡터/FTS 테이블을 만지는 컴포넌트를 표시하려고 남긴
     * 것이지 다른 파일을 뜻하지 않는다. 그 표시가 경계를 보장하지도 않는다 —
     * {@code QuestionReuseRepository.findSourcePreviewRows()} 가 이 템플릿으로 운영 테이블을 함께 조인한다.
     * 파일을 다시 나누려면 그 조인부터 풀어야 한다.
     */
    @Bean(name = "vectorJdbcTemplate")
    public JdbcTemplate vectorJdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
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
        if (!isSqliteVec(type)) {
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
