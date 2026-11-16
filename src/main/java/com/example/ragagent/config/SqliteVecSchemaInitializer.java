package com.example.ragagent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * sqlite-vec 백엔드용 스키마를 기동 시점에 생성한다.
 *
 * <p>{@code app.vectorstore.type=sqlite-vec}일 때만 활성화된다. {@code vec0} 가상 테이블의
 * 벡터 차원은 임베딩 모델에 종속된 DDL 리터럴이므로 Flyway 정적 마이그레이션에 적합하지 않다.
 * 대신 {@link ApplicationReadyEvent} 시점에 멱등성({@code IF NOT EXISTS}) DDL을 실행한다
 * (이 시점에는 {@link DataSourceConfig#configureSqliteVec}가 vec0 확장을 이미 로드한 상태).
 *
 * <p>테이블 두 개가 {@code spring_doc_id}로 JOIN된다: {@code vec_embeddings}(vec0 가상 테이블, 벡터 저장),
 * {@code vec_document_chunks}(텍스트 + JSON 메타데이터). {@code user_scope}는 문서 스토리지가
 * {@code DocRegistry.SHARED}로 단일화돼 있으므로 기본값 {@code 'shared'}.
 */
@Component
@ConditionalOnProperty(name = "app.vectorstore.type", havingValue = "sqlite-vec")
public class SqliteVecSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SqliteVecSchemaInitializer.class);

    static final String CHUNK_TABLE_DDL = """
            CREATE TABLE IF NOT EXISTS vec_document_chunks (
                spring_doc_id TEXT PRIMARY KEY,
                content       TEXT NOT NULL,
                metadata      TEXT NOT NULL,
                version       TEXT NOT NULL,
                doc_id        TEXT NOT NULL,
                user_scope    TEXT NOT NULL DEFAULT 'shared',
                created_at    TEXT NOT NULL
            )
            """;

    static final String IDX_VERSION_DDL =
            "CREATE INDEX IF NOT EXISTS idx_vec_chunks_version ON vec_document_chunks(version)";
    static final String IDX_DOCID_DDL =
            "CREATE INDEX IF NOT EXISTS idx_vec_chunks_docid ON vec_document_chunks(doc_id)";

    private final JdbcTemplate jdbc;
    private final AppProperties props;

    public SqliteVecSchemaInitializer(@Qualifier("vectorJdbcTemplate") JdbcTemplate jdbc, AppProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    void init() {
        // Fail fast BEFORE touching the DB so a misconfigured dimension executes no statements.
        int dim = resolveDimension(props.embeddingSafe().dimensions());
        // 이미 있는 vec_embeddings 의 차원이 설정과 다르면(임베딩 모델을 바꿨는데 테이블은 그대로) 크게 알린다 — 아래
        // CREATE ... IF NOT EXISTS 는 기존 테이블이 있으면 아무것도 하지 않아서, 알리지 않으면 앱이 조용히 뜨고 색인·검색의
        // 첫 호출에서야 "Dimension mismatch" 로 실패한다. 기동을 막지는 않는다: 이 상태를 고치는 도구가 앱 안에 있다
        // (/admin 의 「임베딩 재생성」 카드 — EmbeddingRebuildService) — 기동을 막으면 그 화면에 닿을 수 없다.
        String existingDdl = readExistingEmbeddingDdl();
        String mismatch = dimensionMismatchMessage(existingDdl, dim);
        if (mismatch != null) {
            log.error("[SQLITE-VEC] {}", mismatch);
        }
        // Belt-and-braces: the DataSource already carries these on its JDBC URL
        // (DataSourceConfig.SESSION_PRAGMAS), which is the only place they can be set reliably —
        // see that constant's javadoc. Re-asserting them here is a no-op in normal wiring and costs
        // two statements once at startup, so it is kept as a guard for a hand-built template.
        jdbc.execute("PRAGMA journal_mode=WAL");
        jdbc.execute("PRAGMA busy_timeout=5000");
        jdbc.execute(embeddingTableDdl(dim));
        jdbc.execute(CHUNK_TABLE_DDL);
        jdbc.execute(IDX_VERSION_DDL);
        jdbc.execute(IDX_DOCID_DDL);
        // 불일치면 CREATE ... IF NOT EXISTS 가 아무것도 하지 않았으니, 설정값이 아니라 테이블의 실제 차원을 적는다.
        Integer existingDim = parseExistingDimension(existingDdl);
        log.info("[SQLITE-VEC] schema ready — vec_embeddings(FLOAT[{}]) + vec_document_chunks", existingDim != null ? existingDim : dim);
    }

    private static final String SELECT_EMBEDDING_DDL =
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'vec_embeddings'";

    /** sqlite_master 에 그대로 남아 있는 vec0 DDL 의 {@code embedding FLOAT[N]} — {@link #embeddingTableDdl} 이 쓴 모양. */
    private static final Pattern EMBEDDING_COLUMN_DIM =
            Pattern.compile("embedding\\s+float\\s*\\[\\s*(\\d+)\\s*\\]", Pattern.CASE_INSENSITIVE);

    /** 이미 만들어진 {@code vec_embeddings} 의 CREATE 문. 테이블이 없으면(첫 기동) {@code null}. */
    private String readExistingEmbeddingDdl() {
        List<String> rows = jdbc.queryForList(SELECT_EMBEDDING_DDL, String.class);
        return (rows == null || rows.isEmpty()) ? null : rows.get(0);
    }

    /** CREATE 문에서 {@code embedding} 열의 차원을 읽는다. 문장이 없거나 모양을 모르면 {@code null}(= 판정하지 않는다). */
    public static Integer parseExistingDimension(String createSql) {
        if (createSql == null) return null;
        Matcher m = EMBEDDING_COLUMN_DIM.matcher(createSql);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    /**
     * 기존 {@code vec_embeddings} 의 차원이 설정과 다르면 운영자에게 보일 문장을, 아니면 {@code null} 을 돌려준다. 테이블이 없거나
     * 차원을 읽을 수 없으면 {@code null} — 그때는 아래 {@code CREATE ... IF NOT EXISTS} 가 설정한 차원으로 만들거나, 읽지 못한
     * 쪽이 이전과 같이 동작한다. 던지지 않는다 — 기동을 막으면 고치는 화면에 닿을 수 없다.
     */
    static String dimensionMismatchMessage(String existingCreateSql, int configured) {
        Integer existing = parseExistingDimension(existingCreateSql);
        if (existing == null || existing == configured) return null;
        return "벡터 테이블(vec_embeddings)은 " + existing + "차원으로 만들어져 있는데 "
                + "app.embedding.dimensions(EMBED_DIMENSIONS)는 " + configured + " 입니다. 이 상태로는 벡터 색인·검색이 "
                + "모두 실패합니다(앱은 계속 뜹니다). 임베딩 모델을 바꾼 것이라면 /admin 의 「임베딩 재생성」 카드에서 벡터 테이블을 "
                + "다시 만들고 전체를 재임베딩하세요(OPERATOR_MANUAL §7.3). 모델은 그대로이고 설정만 틀렸다면 "
                + "EMBED_DIMENSIONS 를 " + existing + " 로 되돌리고 재기동하세요.";
    }

    /**
     * sqlite-vec requires a fixed vector dimension baked into the {@code vec0} DDL, so it cannot
     * be auto-detected. Fail fast with operator guidance when {@code app.embedding.dimensions}
     * is missing or non-positive.
     */
    static int resolveDimension(Integer dim) {
        if (dim == null || dim <= 0) {
            throw new IllegalStateException(
                    "sqlite-vec 모드는 app.embedding.dimensions 설정이 필수입니다 (현재: " + dim + "). "
                    + "임베딩 모델의 벡터 차원수(예: 1536)를 지정하세요.");
        }
        return dim;
    }

    /**
     * {@code vec0} virtual table DDL with the embedding dimension as a literal.
     *
     * <p>{@code version} is a vec0 <em>partition key</em> so KNN can filter by version inside the
     * search ({@code WHERE embedding MATCH ? AND k = ? AND version = ?}) without over-fetch + JOIN.
     * {@code distance_metric=cosine} matches the Chroma path's {@code similarity = 1 - distance}
     * semantics (verified: identical vectors → distance 0, orthogonal → 1).
     */
    public static String embeddingTableDdl(int dim) {
        return """
                CREATE VIRTUAL TABLE IF NOT EXISTS vec_embeddings
                USING vec0(
                    spring_doc_id TEXT PRIMARY KEY,
                    version TEXT partition key,
                    embedding FLOAT[%d] distance_metric=cosine
                )
                """.formatted(dim);
    }
}
