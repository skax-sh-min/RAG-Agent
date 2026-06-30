package com.example.ragagent.ingestion;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.exception.VectorStoreException;
import com.example.ragagent.model.MetaKey;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * sqlite-vec 기반 {@link VectorStoreProvider}.
 *
 * <p>벡터는 {@code vec_embeddings}(vec0 가상 테이블), 텍스트·JSON 메타데이터는
 * {@code vec_document_chunks}({@code SqliteVecSchemaInitializer} 참고)에 저장하며
 * {@code spring_doc_id}로 JOIN한다. KNN은 vec0 파티션 키로 버전을 필터링하므로
 * 단일 쿼리({@code WHERE embedding MATCH ? AND k = ? AND version = ?})로 동작한다.
 * 유사도 지표는 cosine({@code similarity = 1 - distance})으로 Chroma 경로와 동일하다.
 *
 * <p>{@code VectorStoreProviderConfig}가 {@code app.vectorstore.type=sqlite-vec}일 때
 * 정확히 하나의 {@link VectorStoreProvider} 빈으로 등록한다. 이 클래스 자체는 POJO이므로
 * 기본 프로파일의 Chroma 빈과 충돌하지 않는다.
 *
 * <p>전달되는 version 값은 {@link VectorStoreFacade}에서 이미 검증된 것으로 간주한다.
 */
public class SqliteVecVectorStoreProvider implements VectorStoreProvider {

    private static final Logger log = LoggerFactory.getLogger(SqliteVecVectorStoreProvider.class);

    private static final String INSERT_EMBEDDING =
            "INSERT INTO vec_embeddings(spring_doc_id, version, embedding) VALUES (?, ?, ?)";
    private static final String INSERT_CHUNK =
            "INSERT INTO vec_document_chunks(spring_doc_id, content, metadata, version, doc_id, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?)";
    // vec0 KNN with version partition filter; JOIN only enriches with text + metadata.
    private static final String SEARCH = """
            SELECT c.spring_doc_id AS spring_doc_id, c.content AS content, c.metadata AS metadata, knn.distance AS distance
            FROM (
                SELECT spring_doc_id, distance FROM vec_embeddings
                WHERE embedding MATCH ? AND k = ? AND version = ?
            ) knn
            JOIN vec_document_chunks c ON c.spring_doc_id = knn.spring_doc_id
            ORDER BY knn.distance
            """;
    private static final int MIN_EMBED_TEXT_LENGTH = 128;
    private static final int MAX_EMBED_RETRY = 8;
    private static final double EMBED_SHRINK_RATIO = 0.8;

    private final JdbcTemplate jdbc;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;
    private final double similarityThreshold;

    public SqliteVecVectorStoreProvider(JdbcTemplate jdbc, EmbeddingModel embeddingModel,
                                        ObjectMapper objectMapper, AppProperties props) {
        this.jdbc = jdbc;
        this.embeddingModel = embeddingModel;
        this.objectMapper = objectMapper;
        this.similarityThreshold = props.searchSimilarityThresholdSafe();
    }

    @Override
    public List<Document> search(String userId, String query, String version, int topK) {
        return searchByEmbedding(embedSingleWithFallback(query), version, topK);
    }

    @Override
    public List<List<Document>> searchBatch(String userId, List<String> queries, String version, int topK) {
        if (queries == null || queries.isEmpty()) return List.of();
        List<float[]> embeddings = embedBatchWithFallback(queries);
        List<List<Document>> out = new ArrayList<>(queries.size());
        for (float[] embedding : embeddings) {
            out.add(searchByEmbedding(embedding, version, topK));
        }
        return out;
    }

    @Override
    public void add(String userId, String version, List<Document> docs) {
        if (docs == null || docs.isEmpty()) return;

        // vec0 does not support INSERT OR REPLACE → delete first so re-indexing is idempotent.
        deleteBySpringDocIds(docs.stream().map(Document::getId).toList());

        List<String> texts = docs.stream().map(d -> d.getText() == null ? "" : d.getText()).toList();
        List<float[]> embeddings = embedBatchWithFallback(texts);

        jdbc.batchUpdate(INSERT_EMBEDDING, new BatchPreparedStatementSetter() {
            @Override public void setValues(PreparedStatement ps, int i) throws SQLException {
                ps.setString(1, docs.get(i).getId());
                ps.setString(2, version);
                ps.setString(3, toVectorLiteral(embeddings.get(i)));
            }
            @Override public int getBatchSize() { return docs.size(); }
        });

        String now = Instant.now().toString();
        List<Object[]> chunkRows = new ArrayList<>(docs.size());
        for (Document d : docs) {
            Map<String, Object> meta = d.getMetadata() == null ? Map.of() : d.getMetadata();
            chunkRows.add(new Object[]{
                    d.getId(),
                    d.getText() == null ? "" : d.getText(),
                    toJson(meta),
                    version,
                    String.valueOf(meta.getOrDefault(MetaKey.DOC_ID, "")),
                    now
            });
        }
        jdbc.batchUpdate(INSERT_CHUNK, chunkRows);
    }

    @Override
    public void deleteByDocIds(String userId, String version, List<String> springDocIds) {
        deleteBySpringDocIds(springDocIds);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private List<Document> searchByEmbedding(float[] embedding, String version, int topK) {
        String vector = toVectorLiteral(embedding);
        List<Document> rows = jdbc.query(SEARCH, (rs, i) -> {
            double similarity = 1.0 - rs.getDouble("distance");
            if (similarity < similarityThreshold) return null;          // R-1 (0.0 = accept all but negatives)
            return Document.builder()
                    .id(rs.getString("spring_doc_id"))
                    .text(rs.getString("content"))
                    .metadata(parseMetadata(rs.getString("metadata")))
                    .score(similarity)
                    .build();
        }, vector, topK, version);
        return rows.stream().filter(Objects::nonNull).toList();
    }

    /** spring_doc_id is the global primary key, so a single delete per table covers every version. */
    private void deleteBySpringDocIds(List<String> springDocIds) {
        if (springDocIds == null || springDocIds.isEmpty()) return;
        String placeholders = springDocIds.stream().map(id -> "?").collect(Collectors.joining(","));
        Object[] args = springDocIds.toArray();
        jdbc.update("DELETE FROM vec_embeddings WHERE spring_doc_id IN (" + placeholders + ")", args);
        jdbc.update("DELETE FROM vec_document_chunks WHERE spring_doc_id IN (" + placeholders + ")", args);
    }

    /** float[] → sqlite-vec JSON text literal {@code [v0,v1,...]} (accepted by vec0 directly). */
    static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 10 + 2).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            throw new VectorStoreException("메타데이터 직렬화 실패", e);
        }
    }

    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) return new HashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("[sqlite-vec] metadata 파싱 실패, 빈 맵으로 대체: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private List<float[]> embedBatchWithFallback(List<String> texts) {
        try {
            return embeddingModel.embed(texts); // fast path: single batched call
        } catch (RuntimeException e) {
            if (!isInputTooLargeError(e)) throw e;
            log.warn("[sqlite-vec] batched embedding rejected by model token limit, retrying per item with shrinking");
            List<float[]> out = new ArrayList<>(texts.size());
            for (String text : texts) {
                out.add(embedSingleWithFallback(text));
            }
            return out;
        }
    }

    private float[] embedSingleWithFallback(String text) {
        String candidate = text == null ? "" : text;
        final int originalLength = candidate.length();

        for (int i = 0; i < MAX_EMBED_RETRY; i++) {
            try {
                return embeddingModel.embed(candidate);
            } catch (RuntimeException e) {
                if (!isInputTooLargeError(e)) throw e;
                if (candidate.length() <= MIN_EMBED_TEXT_LENGTH) {
                    throw new VectorStoreException("임베딩 입력이 모델 제한을 초과하여 축소 재시도 후에도 실패했습니다.", e);
                }
                int nextLength = Math.max(MIN_EMBED_TEXT_LENGTH, (int) (candidate.length() * EMBED_SHRINK_RATIO));
                if (nextLength >= candidate.length()) nextLength = candidate.length() - 1;
                candidate = candidate.substring(0, nextLength);
            }
        }

        log.warn("[sqlite-vec] embedding text truncated due to model token limit: {} -> {} chars",
                originalLength, candidate.length());
        return embeddingModel.embed(candidate);
    }

    private boolean isInputTooLargeError(Throwable error) {
        Throwable cur = error;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null) {
                String lowered = msg.toLowerCase(Locale.ROOT);
                if (lowered.contains("too large to process")
                        || lowered.contains("maximum context length")
                        || (lowered.contains("tokens") && lowered.contains("batch size"))) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }
}
