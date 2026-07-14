package com.example.ragagent.ingestion;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.exception.VectorStoreException;
import com.example.ragagent.model.MetaKey;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    // §10.7.4 — post-filtering by similarityThreshold can shrink the pool below topK when the
    // KNN query only ever asks for exactly topK candidates; over-fetch when a threshold is
    // actually active. No-op at the default (0.0, accept-all) — nothing to filter out there.
    private static final double THRESHOLD_OVERFETCH_MULTIPLIER = 2.0;
        private static final Pattern TOKEN_LIMIT_PATTERN = Pattern.compile(
            "input \\((\\d+) tokens\\).+?current batch size: (\\d+)",
            Pattern.CASE_INSENSITIVE);

    private final JdbcTemplate jdbc;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;
    private final double similarityThreshold;
    // Same default Spring AI applies internally to ChromaVectorStore.add() — splits by token
    // count (8191 default, 10% reserve) so add() never sends an entire large document's chunks
    // (e.g. 500+) as one unbounded embed() call. Without this, only the Chroma backend got this
    // safety net "for free"; sqlite-vec bypassed it by calling embeddingModel.embed() directly.
    private final BatchingStrategy batchingStrategy = new TokenCountBatchingStrategy();

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
        add(userId, version, docs, (done, total) -> { });
    }

    @Override
    public void add(String userId, String version, List<Document> docs,
                     BiConsumer<Integer, Integer> onProgress) {
        if (docs == null || docs.isEmpty()) return;

        // vec0 does not support INSERT OR REPLACE → delete first so re-indexing is idempotent.
        deleteBySpringDocIds(docs.stream().map(Document::getId).toList());

        // Embed per token-bounded sub-batch (not all of docs in one call) so a large document's
        // chunk count can't turn into a single oversized HTTP request that times out against a
        // slow local embedding server. Keyed by doc id since batchingStrategy.batch() does not
        // guarantee it preserves docs' original order. Each completed sub-batch reports real
        // incremental progress instead of the caller only seeing a single 0%→100% jump.
        // Batches over the derived (context+normalized) text, not raw text — that's what actually
        // gets embedded, so that's what the token-count estimate must be sized against (§10.1).
        int total = docs.size();
        int done = 0;
        onProgress.accept(0, total);
        Map<String, float[]> embeddingByDocId = new HashMap<>(docs.size() * 2);
        List<Document> embedInputDocs = docs.stream()
                .map(d -> new Document(d.getId(), SearchTextBuilder.build(d), Map.of()))
                .toList();
        for (List<Document> batch : batchingStrategy.batch(embedInputDocs)) {
            List<String> texts = batch.stream().map(Document::getText).toList(); // already derived
            List<float[]> batchEmbeddings = embedBatchWithFallback(texts);
            for (int i = 0; i < batch.size(); i++) {
                embeddingByDocId.put(batch.get(i).getId(), batchEmbeddings.get(i));
            }
            done += batch.size();
            onProgress.accept(done, total);
        }

        jdbc.batchUpdate(INSERT_EMBEDDING, new BatchPreparedStatementSetter() {
            @Override public void setValues(PreparedStatement ps, int i) throws SQLException {
                ps.setString(1, docs.get(i).getId());
                ps.setString(2, version);
                ps.setString(3, toVectorLiteral(embeddingByDocId.get(docs.get(i).getId())));
            }
            @Override public int getBatchSize() { return docs.size(); }
        });

        String now = Instant.now().toString();
        List<Object[]> chunkRows = new ArrayList<>(docs.size());
        for (Document d : docs) {
            Map<String, Object> meta = d.getMetadata() == null ? new HashMap<>() : new HashMap<>(d.getMetadata());
            meta.remove(MetaKey.CHUNK_CONTEXT); // transient — never persisted
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

    @Override
    public void updateTags(String userId, String version, List<String> springDocIds, String tagsCsv) {
        if (springDocIds == null || springDocIds.isEmpty()) return;
        String placeholders = springDocIds.stream().map(id -> "?").collect(Collectors.joining(","));
        List<Object[]> rows = jdbc.query(
                "SELECT spring_doc_id, metadata FROM vec_document_chunks WHERE spring_doc_id IN (" + placeholders + ")",
                (rs, i) -> new Object[]{rs.getString("spring_doc_id"), rs.getString("metadata")},
                springDocIds.toArray());
        if (rows.isEmpty()) return;

        List<Object[]> updates = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            String springDocId = (String) row[0];
            Map<String, Object> meta = parseMetadata((String) row[1]);
            if (tagsCsv == null || tagsCsv.isEmpty()) meta.remove(MetaKey.TAGS);
            else meta.put(MetaKey.TAGS, tagsCsv);
            updates.add(new Object[]{toJson(meta), springDocId});
        }
        jdbc.batchUpdate("UPDATE vec_document_chunks SET metadata = ? WHERE spring_doc_id = ?", updates);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * §10.7.4 — over-fetches {@code k} when a similarity threshold is active (post-filtering can
     * otherwise shrink the pool below {@code topK}) and caps the filtered result back to
     * {@code topK} — rows arrive pre-sorted by ascending distance, so taking the first
     * {@code topK} that pass the threshold is exactly "closest topK above threshold".
     */
    private List<Document> searchByEmbedding(float[] embedding, String version, int topK) {
        String vector = toVectorLiteral(embedding);
        int fetchK = similarityThreshold > 0.0
                ? (int) Math.ceil(topK * THRESHOLD_OVERFETCH_MULTIPLIER) : topK;
        List<Document> rows = jdbc.query(SEARCH, (rs, i) -> {
            double similarity = 1.0 - rs.getDouble("distance");
            if (similarity < similarityThreshold) return null;          // 0.0 = accept all but negatives
            return Document.builder()
                    .id(rs.getString("spring_doc_id"))
                    .text(rs.getString("content"))
                    .metadata(parseMetadata(rs.getString("metadata")))
                    .score(similarity)
                    .build();
        }, vector, fetchK, version);
        return rows.stream().filter(Objects::nonNull).limit(topK).toList();
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
            if (isInputTooLargeError(e)) {
                logInputTooLarge("embed-batch", e, texts == null ? 0 : texts.size(),
                        texts == null || texts.isEmpty() ? 0 : texts.get(0) == null ? 0 : texts.get(0).length());
                log.warn("[sqlite-vec] batched embedding rejected by model token limit, retrying per item with shrinking");
                List<float[]> out = new ArrayList<>(texts.size());
                for (String text : texts) {
                    out.add(embedSingleWithFallback(text));
                }
                return out;
            }
            // A read timeout is a wall-clock problem, not a token-limit one — retrying the exact
            // same batch (RetryTemplate in EmbeddingBeanConfig) just times out again. Halving the
            // batch shrinks the request until it fits within the read-timeout window, without
            // needing to know the embedding server's actual throughput up front.
            if (isTimeoutLike(e) && texts.size() > 1) {
                log.warn("[sqlite-vec] embedding batch timed out (n={}), splitting in half and retrying",
                        texts.size());
                int mid = texts.size() / 2;
                List<float[]> left = embedBatchWithFallback(texts.subList(0, mid));
                List<float[]> right = embedBatchWithFallback(texts.subList(mid, texts.size()));
                List<float[]> combined = new ArrayList<>(texts.size());
                combined.addAll(left);
                combined.addAll(right);
                return combined;
            }
            throw e;
        }
    }

    private static boolean isTimeoutLike(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof SocketTimeoutException || cur instanceof InterruptedIOException) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private float[] embedSingleWithFallback(String text) {
        String candidate = text == null ? "" : text;
        final int originalLength = candidate.length();
        RuntimeException lastTooLarge = null;

        for (int i = 0; i < MAX_EMBED_RETRY; i++) {
            try {
                return embeddingModel.embed(candidate);
            } catch (RuntimeException e) {
                if (!isInputTooLargeError(e)) throw e;
                logInputTooLarge("embed-single", e, i + 1, candidate.length());
                lastTooLarge = e;
                if (candidate.length() <= MIN_EMBED_TEXT_LENGTH) {
                    throw new VectorStoreException("임베딩 입력이 모델 제한을 초과하여 축소 재시도 후에도 실패했습니다.", e);
                }
                int nextLength = computeNextLength(candidate.length(), e);
                if (nextLength >= candidate.length()) nextLength = candidate.length() - 1;
                candidate = candidate.substring(0, nextLength);
            }
        }

        log.warn("[sqlite-vec] embedding text truncated due to model token limit but still rejected: {} -> {} chars",
                originalLength, candidate.length());
        throw new VectorStoreException("임베딩 입력이 모델 제한을 초과하여 축소 재시도 후에도 실패했습니다.", lastTooLarge);
    }

    private int computeNextLength(int currentLength, RuntimeException error) {
        Optional<TokenLimitHint> hint = parseTokenLimitHint(error);
        if (hint.isPresent() && hint.get().inputTokens() > 0 && hint.get().batchLimit() > 0) {
            // token 비율로 다음 길이를 크게 줄여 재시도 횟수를 절약한다.
            double ratio = ((double) hint.get().batchLimit() / hint.get().inputTokens()) * 0.9;
            int byHint = (int) Math.floor(currentLength * Math.max(0.1, Math.min(ratio, 0.95)));
            return Math.max(MIN_EMBED_TEXT_LENGTH, byHint);
        }
        return Math.max(MIN_EMBED_TEXT_LENGTH, (int) (currentLength * EMBED_SHRINK_RATIO));
    }

    private Optional<TokenLimitHint> parseTokenLimitHint(Throwable error) {
        Throwable cur = error;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null) {
                Matcher m = TOKEN_LIMIT_PATTERN.matcher(msg);
                if (m.find()) {
                    try {
                        int inputTokens = Integer.parseInt(m.group(1));
                        int batchLimit = Integer.parseInt(m.group(2));
                        return Optional.of(new TokenLimitHint(inputTokens, batchLimit));
                    } catch (NumberFormatException ignored) {
                        // continue parsing outer causes
                    }
                }
            }
            cur = cur.getCause();
        }
        return Optional.empty();
    }

    private void logInputTooLarge(String stage, Throwable error, int countOrAttempt, int textLength) {
        Optional<TokenLimitHint> hint = parseTokenLimitHint(error);
        if (hint.isPresent()) {
            TokenLimitHint h = hint.get();
            log.warn("[sqlite-vec] token-limit stage={} inputTokens={} limit={} n={} len={}",
                    stage, h.inputTokens(), h.batchLimit(), countOrAttempt, textLength);
            return;
        }

        log.warn("[sqlite-vec] token-limit stage={} n={} len={}", stage, countOrAttempt, textLength);
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

    private record TokenLimitHint(int inputTokens, int batchLimit) {
    }
}
