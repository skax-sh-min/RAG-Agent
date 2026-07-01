package com.example.ragagent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

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
        // Replicate the operational DB's pragmas on the vector template's connection. Harmless when
        // vectorJdbcTemplate aliases memory.db (non-separated); required for a dedicated vector.db
        // since its connection is created separately from SqliteMemoryRepository's.
        jdbc.execute("PRAGMA journal_mode=WAL");
        jdbc.execute("PRAGMA busy_timeout=5000");
        jdbc.execute(embeddingTableDdl(dim));
        jdbc.execute(CHUNK_TABLE_DDL);
        jdbc.execute(IDX_VERSION_DDL);
        jdbc.execute(IDX_DOCID_DDL);
        log.info("[SQLITE-VEC] schema ready — vec_embeddings(FLOAT[{}]) + vec_document_chunks", dim);
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
    static String embeddingTableDdl(int dim) {
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
