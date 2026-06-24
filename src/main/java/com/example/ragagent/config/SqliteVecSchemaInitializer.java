package com.example.ragagent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Phase 5 Step 5.3 — creates the sqlite-vec schema at startup.
 *
 * <p>Only active when {@code app.vectorstore.type=sqlite-vec}; the default {@code chroma}
 * backend never instantiates this bean. The {@code vec0} virtual table dimension is bound to
 * the embedding model, so it must be a DDL literal — that makes it a poor fit for Flyway's
 * static migrations. Instead we run idempotent {@code IF NOT EXISTS} DDL on
 * {@link ApplicationReadyEvent} (by which point {@link DataSourceConfig#configureSqliteVec}
 * has loaded the vec0 extension on the pooled connection).
 *
 * <p>Two tables, joined on {@code spring_doc_id}: {@code vec_embeddings} holds the vectors
 * (vec0 virtual table), {@code vec_document_chunks} holds text + JSON metadata. {@code user_scope}
 * defaults to {@code 'shared'} because document storage currently converges on
 * {@code DocRegistry.SHARED}.
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

    public SqliteVecSchemaInitializer(JdbcTemplate jdbc, AppProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    void init() {
        int dim = resolveDimension(props.embeddingSafe().dimensions());
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
