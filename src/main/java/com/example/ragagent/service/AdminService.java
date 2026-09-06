package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.ingestion.DocRegistry;
import com.example.ragagent.ingestion.KeywordExtractor;
import com.example.ragagent.ingestion.KeywordSearchRepository;
import com.example.ragagent.ingestion.SearchTextBuilder;
import com.example.ragagent.ingestion.VectorStoreFacade;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.model.VectorStoreAdminView;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.ChromaApi.AddEmbeddingsRequest;
import org.springframework.ai.chroma.vectorstore.ChromaApi.Collection;
import org.springframework.ai.chroma.vectorstore.ChromaApi.DeleteEmbeddingsRequest;
import org.springframework.ai.chroma.vectorstore.ChromaApi.GetEmbeddingResponse;
import org.springframework.ai.chroma.vectorstore.ChromaApi.GetEmbeddingsRequest;
import org.springframework.ai.chroma.vectorstore.ChromaApi.QueryRequest.Include;
import org.springframework.ai.chroma.vectorstore.common.ChromaApiConstants;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Admin-level access to the active vector store: ChromaDB collection/chunk browsing,
 * plus a backend-agnostic status view ({@link #vectorStoreView()}) covering both
 * chroma and sqlite-vec.
 */
@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);
    private static final String TENANT   = ChromaApiConstants.DEFAULT_TENANT_NAME;
    private static final String DATABASE = ChromaApiConstants.DEFAULT_DATABASE_NAME;
    /** Chroma's get() has no server-side ORDER BY — fetch up to this many matches, then sort/paginate in Java. */
    private static final int CHUNK_FETCH_CAP = 10_000;
    /** Document content order: group by document, then by each chunk's stable position within it. */
    private static final Comparator<ChunkRow> CONTENT_ORDER =
            Comparator.comparing(ChunkRow::docId).thenComparingInt(AdminService::chunkIndexOf);

    /** Nullable — sqlite-vec 백엔드에서는 ChromaApi 빈이 없으므로 Optional로 주입된다. */
    private final ChromaApi chromaApi;
    private final JdbcTemplate jdbc;
    private final AppProperties props;
    private final ObjectMapper objectMapper;
    private final VectorStoreFacade vectorStore;
    private final KeywordSearchRepository keywordRepo;
    private final KeywordExtractor keywordExtractor;
    private final QuestionReuseService questionReuseService;
    private final DocRegistry docRegistry;

    // shown on /admin to disambiguate operational vs vector DB files. Field-injected
    // (not constructor) so unit tests that build AdminService directly stay unaffected (null → hidden).
    @Value("${app.data-dir:./data}")
    private String dataDir;
    @Value("${app.vectorstore.sqlite-vec.db-path:}")
    private String sqliteVecDbPath;

    // vec_document_chunks/vec_embeddings live with the vector tables → same template as the provider.
    @Autowired
    public AdminService(Optional<ChromaApi> chromaApi, @Qualifier("vectorJdbcTemplate") JdbcTemplate jdbc,
                        AppProperties props, ObjectMapper objectMapper,
                        VectorStoreFacade vectorStore, KeywordSearchRepository keywordRepo,
                        KeywordExtractor keywordExtractor,
                        QuestionReuseService questionReuseService,
                        DocRegistry docRegistry) {
        this.chromaApi = chromaApi.orElse(null);
        this.jdbc = jdbc;
        this.props = props;
        this.objectMapper = objectMapper;
        this.vectorStore = vectorStore;
        this.keywordRepo = keywordRepo;
        this.keywordExtractor = keywordExtractor;
        this.questionReuseService = questionReuseService;
        this.docRegistry = docRegistry;
    }

    // Backward-compatible constructor for unit tests.
    public AdminService(Optional<ChromaApi> chromaApi, @Qualifier("vectorJdbcTemplate") JdbcTemplate jdbc,
                        AppProperties props, ObjectMapper objectMapper,
                        VectorStoreFacade vectorStore, KeywordSearchRepository keywordRepo,
                        KeywordExtractor keywordExtractor) {
        this(chromaApi, jdbc, props, objectMapper, vectorStore, keywordRepo, keywordExtractor, null, null);
    }

    /** Active backend is sqlite-vec? Null-safe so unit tests with mocked props don't NPE. */
    private boolean isSqliteVec() {
        return props != null && props.vectorStoreSafe() != null
                && "sqlite-vec".equals(props.vectorStoreSafe().type());
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    public record CollectionSummary(String id, String name, String version, long chunkCount) {}

    public record ChunkRow(String id, String textPreview, String fullText,
                           Map<String, String> metadata) {
        public String docId()     { return metadata.getOrDefault(MetaKey.DOC_ID, ""); }
        public String filename()  { return metadata.getOrDefault(MetaKey.FILENAME, ""); }
        public String pageSlide() { return metadata.getOrDefault(MetaKey.PAGE_OR_SLIDE, ""); }
        /** "0" means no real chapter (pre-heading text, PPTX, scanned PDF) — shown blank, mirroring RetrievalService's citation logic. */
        public String chapterNo() {
            String v = metadata.getOrDefault(MetaKey.CHAPTER_NO, "");
            return "0".equals(v) ? "" : v;
        }
        public String keywords()  { return metadata.getOrDefault(MetaKey.EXCERPT_KEYWORDS, ""); }
        public int chunkSize()    { return fullText == null ? 0 : fullText.length(); }
    }

    /** Unparseable/missing chunk_index (legacy pre-§ chunks) sorts last within its document. */
    private static int chunkIndexOf(ChunkRow row) {
        try { return Integer.parseInt(row.metadata().getOrDefault(MetaKey.CHUNK_INDEX, "")); }
        catch (NumberFormatException e) { return Integer.MAX_VALUE; }
    }

    private static List<ChunkRow> paginate(List<ChunkRow> rows, int offset, int limit) {
        if (offset >= rows.size()) return List.of();
        return new ArrayList<>(rows.subList(offset, Math.min(rows.size(), offset + limit)));
    }

    // ── DTOs (public) ─────────────────────────────────────────────────────────

    /** Wraps listCollections result with a ChromaDB availability flag. */
    public record CollectionsResult(List<CollectionSummary> items, boolean available) {}

    // ── Vector store status (backend-agnostic) ───────────────────────

    /** Active-backend status for the {@code /admin} "Vector Store 상태" card. */
    public VectorStoreAdminView vectorStoreView() {
        return "sqlite-vec".equals(props.vectorStoreSafe().type()) ? sqliteVecView() : chromaView();
    }

    private VectorStoreAdminView chromaView() {
        CollectionsResult r = listCollections();
        long totalChunks = r.items().stream().mapToLong(CollectionSummary::chunkCount).sum();
        // Chroma collections don't track distinct document counts → unknown (-1).
        // Chroma stores vectors on its own server → no local vector DB file to report.
        return new VectorStoreAdminView("chroma", r.available(), -1, totalChunks,
                r.items().size(), null, null, operationalDbPath(), null);
    }

    private VectorStoreAdminView sqliteVecView() {
        String vecVersion = null;
        boolean healthy = false;
        try {
            vecVersion = jdbc.queryForObject("SELECT vec_version()", String.class);
            healthy = vecVersion != null;
        } catch (Exception e) {
            log.warn("vec_version() 조회 실패 (sqlite-vec 확장 미로드?): {}", e.getMessage());
        }
        long totalChunks = safeCount("SELECT COUNT(*) FROM vec_document_chunks");
        long totalDocs   = safeCount("SELECT COUNT(DISTINCT doc_id) FROM vec_document_chunks");
        Integer dim = props.embeddingSafe().dimensions();
        return new VectorStoreAdminView("sqlite-vec", healthy, totalDocs, totalChunks,
                null, vecVersion, dim, operationalDbPath(), vectorDbPath());
    }

    /** memory.db absolute path (operational DB), or null when data-dir is unavailable (e.g. unit tests). */
    private String operationalDbPath() {
        return dataDir == null ? null : Path.of(dataDir, "memory.db").toAbsolutePath().normalize().toString();
    }

    /**
     * Vector DB path for display: the dedicated {@code vector.db} when the Step 5.10 switch is on,
     * else the same file as the operational DB (vectors live in memory.db).
     */
    private String vectorDbPath() {
        if (sqliteVecDbPath != null && !sqliteVecDbPath.isBlank()) {
            return Path.of(sqliteVecDbPath.trim()).toAbsolutePath().normalize().toString();
        }
        return operationalDbPath();
    }

    private long safeCount(String sql) {
        try {
            Long c = jdbc.queryForObject(sql, Long.class);
            return c != null ? c : 0L;
        } catch (Exception e) {
            log.warn("count 쿼리 실패 [{}]: {}", sql, e.getMessage());
            return 0L;
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public CollectionsResult listCollections() {
        if (isSqliteVec()) return sqliteVecCollections();
        if (chromaApi == null) return new CollectionsResult(List.of(), false);
        try {
            List<Collection> cols = chromaApi.listCollections(TENANT, DATABASE);
            if (cols == null) return new CollectionsResult(List.of(), true);
            List<CollectionSummary> items = cols.stream().map(col -> {
                long count = 0;
                try { Long c = chromaApi.countEmbeddings(TENANT, DATABASE, col.id()); count = c != null ? c : 0; }
                catch (Exception e) { log.warn("Count failed for {}: {}", col.name(), e.getMessage()); }
                String version = col.name().startsWith("manual_")
                        ? col.name().substring("manual_".length()) : col.name();
                return new CollectionSummary(col.id(), col.name(), version, count);
            }).toList();
            return new CollectionsResult(items, true);
        } catch (Exception e) {
            log.error("listCollections failed: {}", e.getMessage());
            return new CollectionsResult(List.of(), false);
        }
    }

    /**
     * One page of chunks, in document content order.
     *
     * <p><b>Chroma 경로는 두 번 읽는다.</b> Chroma 의 {@code get()} 에는 서버 측 ORDER BY 가 없어
     * offset/limit 을 그대로 내려보낼 수 없다 — 순서를 이쪽에서 정해야 하고, 그러려면 매치 집합
     * 전체를 봐야 한다. 그런데 <b>정렬 기준({@code doc_id}, {@code chunk_index})은 전부
     * 메타데이터에 있다.</b> 그래서 1단계는 메타데이터만 받아 순서를 정하고, 본문은 2단계에서
     * <b>이 페이지에 실제로 보이는 것(기본 20개)만</b> 읽는다.
     *
     * <p>예전에는 한 번에 본문까지 다 받아 자바에서 잘랐다. 청크 하나가 기본 1,500자이므로
     * {@link #CHUNK_FETCH_CAP} 에 가까운 컬렉션에서는 <b>페이지를 넘길 때마다</b> 수십 MB 를
     * 전송·파싱해 그중 20개만 쓰고 버렸다. {@code liveChunkIds()} 가 같은 판단(본문이 페이로드의
     * 대부분이다)을 이미 하고 있었는데 이 경로만 빠져 있었다.
     *
     * <p>2단계 사이에 청크가 지워졌으면 그 행은 조용히 빠진다 — 어차피 목록은 스냅샷이고,
     * 없는 것을 빈 행으로 보여줄 이유가 없다.
     *
     * <p>전부가 필요한 호출자는 {@link #getAllChunks} 를 쓴다(내보내기·재인덱싱 사전 확인).
     * 그쪽은 어차피 모든 본문이 필요하므로 두 단계로 나눌 이유가 없다.
     */
    public List<ChunkRow> getChunks(String collectionName, String docId,
                                    int offset, int limit) {
        if (isSqliteVec()) return sqliteVecChunks(collectionName, docId, offset, limit);
        if (chromaApi == null) return List.of();
        try {
            // 컬렉션 id 는 한 번만 구해 두 요청이 함께 쓴다 — resolveId() 는 그 자체가 Chroma 왕복이다.
            String collectionId = resolveId(collectionName);

            List<ChunkRow> ordered = new ArrayList<>(
                    fetchRows(collectionId, docId, List.of(Include.METADATAS)));
            ordered.sort(CONTENT_ORDER);

            List<ChunkRow> page = paginate(ordered, offset, limit);
            if (page.isEmpty()) return List.of();
            return fetchByIds(collectionId, page.stream().map(ChunkRow::id).toList());
        } catch (Exception e) {
            log.error("getChunks failed collection={} docId={}: {}", collectionName, docId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 한 문서(또는 컬렉션)의 청크 <b>전부</b>를 본문까지. {@link #getChunks} 와 달리 두 단계로
     * 나누지 않는다 — 호출자가 모든 본문을 쓰므로 나눠 봐야 왕복만 하나 는다.
     *
     * <p>사용자의 클릭 한 번에 대응하는 경로(내보내기, 재인덱싱 사전 확인) 전용이다.
     * <b>페이지 렌더에서 부르면 안 된다</b> — 그러라고 {@link #getChunks} 가 있다.
     * {@link #CHUNK_FETCH_CAP} 에서 잘린다.
     */
    public List<ChunkRow> getAllChunks(String collectionName, String docId) {
        if (isSqliteVec()) return sqliteVecChunks(collectionName, docId, 0, CHUNK_FETCH_CAP);
        if (chromaApi == null) return List.of();
        try {
            List<ChunkRow> rows = new ArrayList<>(fetchRows(resolveId(collectionName), docId,
                    List.of(Include.DOCUMENTS, Include.METADATAS)));
            rows.sort(CONTENT_ORDER);
            return rows;
        } catch (Exception e) {
            log.error("getAllChunks failed collection={} docId={}: {}", collectionName, docId, e.getMessage());
            return List.of();
        }
    }

    public long countChunks(String collectionName, String docId) {
        if (isSqliteVec()) return sqliteVecCount(collectionName, docId);
        if (chromaApi == null) return 0;
        if (docId == null || docId.isBlank()) {
            try { Long c = chromaApi.countEmbeddings(TENANT, DATABASE, resolveId(collectionName)); return c != null ? c : 0; }
            catch (Exception e) { return 0; }
        }
        // where 필터가 걸린 개수는 countEmbeddings() 로 알 수 없어 get() 으로 세는 수밖에 없다.
        // 세는 데 본문은 필요 없다 — 예전에는 getChunks() 를 거치며 문서 본문까지 전부 끌어왔고,
        // 그것이 청크 목록을 띄우는 화면에서 매번 함께 일어났다.
        try {
            return fetchRows(resolveId(collectionName), docId, List.of(Include.METADATAS)).size();
        } catch (Exception e) {
            log.error("countChunks failed collection={} docId={}: {}", collectionName, docId, e.getMessage());
            return 0;
        }
    }

    /** {@code docId} 로 걸러 {@link #CHUNK_FETCH_CAP} 까지. {@code include} 가 본문 포함 여부를 정한다. */
    private List<ChunkRow> fetchRows(String collectionId, String docId, List<Include> include) {
        Map<String, Object> where = (docId == null || docId.isBlank()) ? null
                : Map.of(MetaKey.DOC_ID, Map.of("$eq", docId));
        return toRows(chromaApi.getEmbeddings(TENANT, DATABASE, collectionId,
                new GetEmbeddingsRequest(null, where, CHUNK_FETCH_CAP, 0, include)));
    }

    /** 지정한 청크만 본문까지 읽어 <b>요청한 순서 그대로</b> 돌려준다 — Chroma 는 응답 순서를 보장하지 않는다. */
    private List<ChunkRow> fetchByIds(String collectionId, List<String> ids) {
        List<ChunkRow> fetched = toRows(chromaApi.getEmbeddings(TENANT, DATABASE, collectionId,
                new GetEmbeddingsRequest(ids, null, ids.size(), 0,
                        List.of(Include.DOCUMENTS, Include.METADATAS))));
        Map<String, ChunkRow> byId = new HashMap<>(fetched.size() * 2);
        for (ChunkRow row : fetched) byId.putIfAbsent(row.id(), row);

        List<ChunkRow> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            ChunkRow row = byId.get(id);
            if (row != null) out.add(row);
        }
        return out;
    }

    /** Chroma 응답 → {@link ChunkRow} 목록. 본문 없이 조회했으면 {@code fullText} 는 빈 문자열이다. */
    private static List<ChunkRow> toRows(GetEmbeddingResponse resp) {
        if (resp == null || resp.ids() == null) return List.of();
        List<String> ids  = resp.ids();
        List<String> docs = resp.documents();
        List<Map<String, String>> metas = resp.metadata();
        List<ChunkRow> rows = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            String text = (docs != null && i < docs.size()) ? docs.get(i) : "";
            Map<String, String> meta = (metas != null && i < metas.size()) ? metas.get(i) : Map.of();
            String preview = text != null && text.length() > 250
                    ? text.substring(0, 250) + "…" : Objects.requireNonNullElse(text, "");
            rows.add(new ChunkRow(ids.get(i), preview, text, meta));
        }
        return rows;
    }

    /**
     * How many of a document's chunks carry a {@link MetaKey#EDITED_AT} stamp, i.e. were hand-edited
     * in {@code /admin} and would be discarded by a document-level re-index (which rebuilds chunks
     * from the saved MD file — the edit never went back into that file).
     *
     * <p>Deliberately goes through {@link #getAllChunks}: one code path for both backends, and it
     * runs only on the re-index pre-flight (a single click), never on a page render. Metadata edits
     * (keywords/context) count too — they are lost by the same re-index for the same reason.
     */
    public long countEditedChunks(String collectionName, String docId) {
        return getAllChunks(collectionName, docId).stream()
                .filter(r -> !r.metadata().getOrDefault(MetaKey.EDITED_AT, "").isBlank())
                .count();
    }

    /**
     * Vector-store identifier for a document version: the version itself on sqlite-vec (the vec0
     * partition key), {@code manual_<version>} on Chroma (the collection name). The UI passes this
     * value around as "collection" on both backends.
     */
    public String collectionFor(String version) {
        String v = (version == null || version.isBlank()) ? "latest" : version.strip();
        return isSqliteVec() ? v : "manual_" + v;
    }

    public ChunkRow getChunk(String collectionName, String chunkId) {
        if (isSqliteVec()) return sqliteVecChunk(chunkId);
        if (chromaApi == null) return null;
        GetEmbeddingsRequest req = new GetEmbeddingsRequest(
                List.of(chunkId), null, 1, 0,
                List.of(Include.DOCUMENTS, Include.METADATAS));
        try {
            GetEmbeddingResponse resp = chromaApi.getEmbeddings(TENANT, DATABASE, resolveId(collectionName), req);
            if (resp == null || resp.ids() == null || resp.ids().isEmpty()) return null;
            String text = resp.documents() != null && !resp.documents().isEmpty()
                    ? resp.documents().get(0) : "";
            Map<String, String> meta = resp.metadata() != null && !resp.metadata().isEmpty()
                    ? resp.metadata().get(0) : Map.of();
            return new ChunkRow(resp.ids().get(0), text, text, meta);
        } catch (Exception e) {
            log.error("getChunk failed id={}: {}", chunkId, e.getMessage());
            return null;
        }
    }

    /**
     * Deletes one chunk from the vector store, the FTS index, and — via
     * {@link #forgetChunkInRegistry} — the owning document's {@code doc_registry} row.
     *
     * <p>The registry part matters because the document list's chunk count is read from that row,
     * not counted live: without it a deleted chunk kept being reported for the life of the document
     * (until the next full re-index rewrote the row).
     */
    public DeleteResult deleteChunk(String collectionName, String chunkId) {
        // Read the owning document BEFORE deleting — afterwards the metadata carrying it is gone.
        ChunkRow row = getChunk(collectionName, chunkId);
        String docId = row == null ? null : row.docId();

        if (isSqliteVec()) {
            // Two-table consistency: drop the chunk from both the metadata table and the vec0 store.
            jdbc.update("DELETE FROM vec_document_chunks WHERE spring_doc_id = ?", chunkId);
            jdbc.update("DELETE FROM vec_embeddings WHERE spring_doc_id = ?", chunkId);
            keywordRepo.deleteBySpringDocIds(List.of(chunkId));
            if (questionReuseService != null) questionReuseService.markChunkDeleted(chunkId);
            return new DeleteResult(docId, forgetChunkInRegistry(docId, chunkId));
        }
        if (chromaApi == null) {
            log.warn("deleteChunk ignored — no ChromaApi");
            return new DeleteResult(docId, null);
        }
        chromaApi.deleteEmbeddings(TENANT, DATABASE, collectionName,
                new DeleteEmbeddingsRequest(List.of(chunkId)));
        keywordRepo.deleteBySpringDocIds(List.of(chunkId));
        if (questionReuseService != null) questionReuseService.markChunkDeleted(chunkId);
        return new DeleteResult(docId, forgetChunkInRegistry(docId, chunkId));
    }

    // ── 레지스트리 청크 수 재계산 ─────────────────────────────────────────────

    /** @param checked 대조한 문서 수, @param fixed 실제로 고쳐 쓴 문서 수 */
    public record ReconcileResult(int checked, int fixed) {}

    /**
     * Rewrites every document's stored chunk count / {@code spring_doc_ids} from what the vector
     * store actually holds right now, repairing rows that drifted before {@link #deleteChunk}
     * started maintaining them.
     *
     * <p>Deliberately operator-triggered rather than a startup backfill: on the Chroma backend the
     * only way to enumerate a document's chunks is to fetch them, which is far too much work to
     * repeat on every boot for a one-off repair — and with delete now keeping the row in sync,
     * fresh drift no longer accumulates.
     *
     * <p>A document whose live chunk list comes back empty while the row claims chunks is skipped,
     * not zeroed: "every chunk was deleted" and "the store did not answer" look identical here, and
     * only one of them is safe to write.
     */
    public ReconcileResult reconcileChunkCounts() {
        if (docRegistry == null) return new ReconcileResult(0, 0);
        int checked = 0, fixed = 0;
        for (Map.Entry<String, DocRegistry.DocRegistryEntry> e : docRegistry.entries(DocRegistry.SHARED)) {
            String docId = e.getKey();
            DocRegistry.DocRegistryEntry entry = e.getValue();
            checked++;
            List<String> live;
            try {
                live = liveChunkIds(collectionFor(entry.version()), docId);
            } catch (Exception ex) {
                log.warn("[REGISTRY] 청크 수 재계산 건너뜀 docId={}: {}", docId, ex.getMessage());
                continue;
            }
            if (live.isEmpty() && entry.chunks() > 0) {
                log.warn("[REGISTRY] 청크 수 재계산 건너뜀 docId={}: 벡터 스토어가 청크를 하나도 돌려주지 않음 "
                        + "(전부 삭제된 문서인지 스토어 응답 문제인지 구분할 수 없음)", docId);
                continue;
            }
            List<String> stored = entry.springDocIds() == null ? List.of() : entry.springDocIds();
            if (live.size() == entry.chunks() && new HashSet<>(live).equals(new HashSet<>(stored))) continue;

            docRegistry.put(docId, DocRegistry.SHARED, new DocRegistry.DocRegistryEntry(
                    entry.sha256(), entry.version(), entry.indexedAt(), live.size(), live,
                    entry.errors(), entry.chunkOverlap(), entry.displayName()));
            log.info("[REGISTRY] 청크 수 재계산: docId={}, {} -> {}", docId, entry.chunks(), live.size());
            fixed++;
        }
        return new ReconcileResult(checked, fixed);
    }

    /** Chunk ids the store currently holds for one document. Metadata-only on Chroma (the chunk
     *  text is irrelevant here and is the bulk of the payload). */
    private List<String> liveChunkIds(String collectionName, String docId) {
        if (isSqliteVec()) {
            return jdbc.queryForList(
                    "SELECT spring_doc_id FROM vec_document_chunks WHERE version = ? AND doc_id = ?",
                    String.class, collectionName, docId);
        }
        if (chromaApi == null) throw new IllegalStateException("ChromaApi 없음");
        GetEmbeddingsRequest req = new GetEmbeddingsRequest(
                null, Map.of(MetaKey.DOC_ID, Map.of("$eq", docId)), CHUNK_FETCH_CAP, 0,
                List.of(Include.METADATAS));
        GetEmbeddingResponse resp = chromaApi.getEmbeddings(TENANT, DATABASE, resolveId(collectionName), req);
        return (resp == null || resp.ids() == null) ? List.of() : resp.ids();
    }

    /** Outcome of {@link #deleteChunk}: which document lost a chunk and its new registry count
     *  ({@code null} when the registry was not updated — unknown document, or a legacy row with no
     *  recorded chunk ids), so the UI can refresh that number instead of reloading the page. */
    public record DeleteResult(String docId, Integer remainingChunks) {}

    /**
     * Drops {@code chunkId} from its document's registry row so the stored chunk count and
     * {@code spring_doc_ids} list keep matching what the vector store actually holds.
     *
     * <p>No-op unless the id is really in the list: that makes a repeated delete harmless and
     * leaves legacy rows (indexed before ids were recorded) alone rather than zeroing their count
     * from a list that was never populated. The new count is the surviving list's size rather than
     * {@code chunks - 1}, so the two columns can only converge, never drift further apart.
     */
    private Integer forgetChunkInRegistry(String docId, String chunkId) {
        if (docRegistry == null || docId == null || docId.isBlank()) return null;
        var entry = docRegistry.findByDocId(docId, DocRegistry.SHARED);
        if (entry.isEmpty()) return null;
        var e = entry.get();
        List<String> ids = e.springDocIds();
        if (ids == null || !ids.contains(chunkId)) return null;
        List<String> remaining = ids.stream().filter(id -> !chunkId.equals(id)).toList();
        docRegistry.put(docId, DocRegistry.SHARED, new DocRegistry.DocRegistryEntry(
                e.sha256(), e.version(), e.indexedAt(), remaining.size(), remaining,
                e.errors(), e.chunkOverlap(), e.displayName()));
        log.info("[ADMIN] 청크 삭제로 레지스트리 갱신: docId={}, 청크 {} -> {}",
                docId, ids.size(), remaining.size());
        return remaining.size();
    }

    private String resolveId(String collectionName) {
        try {
            Collection col = chromaApi.getCollection(TENANT, DATABASE, collectionName);
            return col != null ? col.id() : collectionName;
        } catch (Exception e) {
            return collectionName;
        }
    }

    /**
     * Metadata-only update: fetches the existing embedding and re-upserts
     * with new text/metadata while preserving the original vector.
     *
     * <p>Stamps {@link MetaKey#EDITED_AT} so a later document-level {@code ↺ 재인덱싱} — which
     * rebuilds every chunk from the saved MD file and therefore discards this edit — can warn
     * first ({@link #countEditedChunks}). The stamp is written only when {@code newMeta} is
     * non-null, i.e. from the {@code /admin} edit panel, which always sends both fields; a
     * text-only call would have no metadata map to put it in.
     */
    public void updateChunk(String collectionName, String chunkId,
                            String newText, Map<String, String> newMeta) {
        if (newMeta != null) {
            newMeta = new HashMap<>(newMeta);
            newMeta.put(MetaKey.EDITED_AT, Instant.now().toString());
        }
        if (isSqliteVec()) {
            // Metadata/text only — the stored vector (vec_embeddings) is intentionally preserved
            // (same caveat as the Chroma path: editing text does not re-embed).
            if (newText != null) {
                jdbc.update("UPDATE vec_document_chunks SET content = ? WHERE spring_doc_id = ?",
                        newText, chunkId);
            }
            if (newMeta != null) {
                jdbc.update("UPDATE vec_document_chunks SET metadata = ? WHERE spring_doc_id = ?",
                        toJson(newMeta), chunkId);
            }
            if (newText != null && questionReuseService != null) {
                questionReuseService.invalidateChunk(chunkId);
            }
            return;
        }
        if (chromaApi == null) { log.warn("updateChunk ignored — no ChromaApi"); return; }
        // Fetch existing embedding to avoid re-embedding
        GetEmbeddingsRequest req = new GetEmbeddingsRequest(
                List.of(chunkId), null, 1, 0,
                List.of(Include.EMBEDDINGS, Include.DOCUMENTS));
        GetEmbeddingResponse existing;
        String collectionId = resolveId(collectionName);
        try {
            existing = chromaApi.getEmbeddings(TENANT, DATABASE, collectionId, req);
        } catch (Exception e) {
            log.error("updateChunk fetch failed id={}: {}", chunkId, e.getMessage());
            return;
        }

        float[] embedding = (existing != null && existing.embeddings() != null
                && !existing.embeddings().isEmpty())
                ? existing.embeddings().get(0) : new float[0];

        String text = newText != null ? newText
                : (existing != null && existing.documents() != null
                        && !existing.documents().isEmpty()
                        ? existing.documents().get(0) : "");

        Map<String, Object> metaObj = newMeta == null ? Map.of() : new HashMap<>(newMeta);

        try {
            chromaApi.upsertEmbeddings(TENANT, DATABASE, collectionName,
                    new AddEmbeddingsRequest(chunkId, embedding, metaObj, text));
        } catch (Exception e) {
            log.error("updateChunk upsert failed id={}: {}", chunkId, e.getMessage());
            return;
        }
        if (newText != null && questionReuseService != null) {
            questionReuseService.invalidateChunk(chunkId);
        }
    }

    /**
     * Re-embeds and re-indexes (FTS) a single chunk IN PLACE — the chunk's own id is preserved,
     * so this overwrites the existing vector/row rather than creating a duplicate (Chroma:
     * {@code upsertEmbeddings} by id; sqlite-vec: {@code add()} deletes-then-inserts by id, see
     * {@code SqliteVecVectorStoreProvider}). Unlike {@link #updateChunk} (metadata/text-only, the
     * original embedding is kept verbatim), this calls the real embedding API against the chunk's
     * CURRENT stored text — so a prior {@link #updateChunk} text edit now actually participates in
     * vector search, and the keyword (BM25) axis is refreshed too (which {@link #updateChunk} never
     * touches at all).
     *
     * @param regenerateKeywords false — keep the chunk's current {@code excerpt_keywords} and
     *        {@link MetaKey#CHUNK_CONTEXT} as-is (e.g. values the operator just hand-edited via
     *        {@link #updateChunk} — both are persisted metadata, so an edit genuinely survives
     *        into this re-embed) and only re-embed + re-index FTS against the current text/
     *        metadata. No LLM call, immediate.
     *        true — re-run {@link KeywordExtractor} for just this chunk first (one LLM call,
     *        same TF-timeout-fallback as indexing) to regenerate {@code excerpt_keywords}/
     *        {@code chunk_context} from the current text, THEN re-embed + re-index with that result
     *        — mirrors document-level {@code ↺ 재인덱싱} quality for a single chunk.
     * @return false if the chunk doesn't exist or the vector-store/FTS write failed.
     */
    public boolean reindexChunk(String collectionName, String chunkId, boolean regenerateKeywords) {
        ChunkRow row = getChunk(collectionName, chunkId);
        if (row == null) {
            log.warn("reindexChunk — chunk not found id={}", chunkId);
            return false;
        }
        String previousHash = questionReuseService == null ? "" : questionReuseService.currentChunkHash(chunkId);

        Map<String, Object> meta = new HashMap<>(row.metadata());
        if (regenerateKeywords) {
            Document reEnriched = keywordExtractor.enrichSingle(new Document(row.fullText(), meta));
            meta.put(MetaKey.EXCERPT_KEYWORDS, reEnriched.getMetadata().get(MetaKey.EXCERPT_KEYWORDS));
            meta.put(MetaKey.CHUNK_CONTEXT, reEnriched.getMetadata().get(MetaKey.CHUNK_CONTEXT));
        }

        // §10.8.5 — precompute once so vectorStore.add()/keywordRepo.indexChunks() below both reuse
        // the same derived (context+normalized) text instead of each recomputing it independently.
        Document doc = SearchTextBuilder.precompute(new Document(chunkId, row.fullText(), meta));
        try {
            vectorStore.add(DocRegistry.SHARED, row.metadata().get(MetaKey.VERSION), List.of(doc));
        } catch (Exception e) {
            log.error("reindexChunk embed failed id={}: {}", chunkId, e.getMessage());
            return false;
        }
        keywordRepo.deleteBySpringDocIds(List.of(chunkId));
        keywordRepo.indexChunks(List.of(doc));
        if (questionReuseService != null) {
            questionReuseService.invalidateChunkIfHashChanged(chunkId, previousHash);
        }
        log.info("[ADMIN] chunk reindexed id={} regenerateKeywords={}", chunkId, regenerateKeywords);
        return true;
    }

    // ── sqlite-vec chunk browsing ────────────────────────────
    // For sqlite-vec the "collection" identifier passed from the UI is the version
    // string (vec0 partition key). Chunks live in vec_document_chunks(content/metadata).

    private CollectionsResult sqliteVecCollections() {
        try {
            List<CollectionSummary> items = jdbc.query(
                    "SELECT version, COUNT(*) AS c FROM vec_document_chunks GROUP BY version ORDER BY version",
                    (rs, n) -> {
                        String v = rs.getString("version");
                        return new CollectionSummary(v, v, v, rs.getLong("c"));
                    });
            return new CollectionsResult(items, true);
        } catch (Exception e) {
            log.error("sqlite-vec listCollections failed: {}", e.getMessage());
            return new CollectionsResult(List.of(), false);
        }
    }

    private List<ChunkRow> sqliteVecChunks(String version, String docId, int offset, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT spring_doc_id, content, metadata FROM vec_document_chunks WHERE version = ?");
        List<Object> args = new ArrayList<>();
        args.add(version);
        if (docId != null && !docId.isBlank()) { sql.append(" AND doc_id = ?"); args.add(docId); }
        // Document content order: group by document, then by each chunk's stable position within
        // it (metadata.chunk_index, set at index time — see MetaKey.CHUNK_INDEX) instead of the
        // meaningless spring_doc_id (random per-chunk id) the old ORDER BY effectively sorted by.
        sql.append(" ORDER BY doc_id, CAST(json_extract(metadata, '$.chunk_index') AS INTEGER), spring_doc_id LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        try {
            return jdbc.query(sql.toString(), (rs, n) -> {
                String text = rs.getString("content");
                String preview = text != null && text.length() > 250
                        ? text.substring(0, 250) + "…" : Objects.requireNonNullElse(text, "");
                return new ChunkRow(rs.getString("spring_doc_id"), preview, text,
                        parseMeta(rs.getString("metadata")));
            }, args.toArray());
        } catch (Exception e) {
            log.error("sqlite-vec getChunks failed version={} docId={}: {}", version, docId, e.getMessage());
            return List.of();
        }
    }

    private long sqliteVecCount(String version, String docId) {
        String sql = "SELECT COUNT(*) FROM vec_document_chunks WHERE version = ?";
        Object[] args = (docId != null && !docId.isBlank())
                ? new Object[]{version, docId} : new Object[]{version};
        if (docId != null && !docId.isBlank()) sql += " AND doc_id = ?";
        try { Long c = jdbc.queryForObject(sql, Long.class, args); return c != null ? c : 0; }
        catch (Exception e) { return 0; }
    }

    private ChunkRow sqliteVecChunk(String chunkId) {
        try {
            return jdbc.query(
                    "SELECT spring_doc_id, content, metadata FROM vec_document_chunks WHERE spring_doc_id = ?",
                    (rs, n) -> {
                        String text = rs.getString("content");
                        return new ChunkRow(rs.getString("spring_doc_id"), text, text,
                                parseMeta(rs.getString("metadata")));
                    }, chunkId).stream().findFirst().orElse(null);
        } catch (Exception e) {
            log.error("sqlite-vec getChunk failed id={}: {}", chunkId, e.getMessage());
            return null;
        }
    }

    private Map<String, String> parseMeta(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, Object> raw = objectMapper.readValue(json, new TypeReference<>() {});
            Map<String, String> out = new LinkedHashMap<>();
            raw.forEach((k, v) -> out.put(k, v == null ? "" : String.valueOf(v)));
            return out;
        } catch (Exception e) {
            log.warn("metadata JSON 파싱 실패: {}", e.getMessage());
            return Map.of();
        }
    }

    private String toJson(Map<String, String> meta) {
        try { return objectMapper.writeValueAsString(meta); }
        catch (Exception e) { log.warn("metadata 직렬화 실패: {}", e.getMessage()); return "{}"; }
    }
}
