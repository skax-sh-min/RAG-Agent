package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.config.DataSourceConfig;
import com.example.ragagent.ingestion.DocRegistry;
import com.example.ragagent.ingestion.KeywordExtractor;
import com.example.ragagent.ingestion.KeywordSearchRepository;
import com.example.ragagent.ingestion.SearchTextBuilder;
import com.example.ragagent.ingestion.VectorStoreFacade;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.model.VectorStoreAdminView;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Admin-level access to the active vector store: collection/chunk browsing and editing on both
 * backends (Chroma via {@link ChromaApi}, sqlite-vec via the vector {@code JdbcTemplate}), plus a
 * backend-agnostic status view ({@link #vectorStoreView()}).
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

        /**
         * 청크의 마지막 변경 시각(ISO-8601 UTC) — 관리자가 손으로 고친 시각({@link MetaKey#EDITED_AT})과
         * 청크가 만들어진 시각({@link MetaKey#COLLECTED_AT}, 초기·재인덱싱 때 찍힌다) 중 <b>더 최신</b> 값.
         * 하나만 있으면 그 값, 둘 다 없거나 읽을 수 없으면 빈 문자열.
         *
         * <p>{@code edited_at} 자체를 인덱싱 때 찍지 않는 이유: 재인덱싱 사전 경고의 "편집된 청크 N개"가 그 키의
         * 존재로 세기 때문이다({@code countEditedChunks}). 화면에는 {@code fragments/time :: utc} 로
         * 그려 보는 사람의 시간대로 바뀐다.
         */
        public String lastModifiedAt() {
            java.time.Instant edited = parseInstant(metadata.get(MetaKey.EDITED_AT));
            java.time.Instant collected = parseInstant(metadata.get(MetaKey.COLLECTED_AT));
            if (edited == null && collected == null) return "";
            if (edited == null) return collected.toString();
            if (collected == null) return edited.toString();
            return (edited.isAfter(collected) ? edited : collected).toString();
        }

        private static java.time.Instant parseInstant(String iso) {
            if (iso == null || iso.isBlank()) return null;
            try { return java.time.Instant.parse(iso.strip()); }
            catch (java.time.format.DateTimeParseException e) { return null; }
        }

        /**
         * 목록의 풍선(title) 미리보기용 본문 — 연속된 줄바꿈(사이에 공백뿐인 빈 줄 포함)은 한 번으로 줄인다.
         * 청크 본문은 문단 사이 빈 줄이 흔한데, 풍선에서는 그것이 세로 공간만 먹는다. 목록 셀 자체는
         * 한 줄 말줄임이라 영향이 없고, 저장된 본문({@link #fullText})은 건드리지 않는다.
         */
        public String tooltipPreview() {
            return textPreview == null ? "" : textPreview.replaceAll("\\r?\\n(?:[ \\t]*\\r?\\n)+", "\n");
        }

        /**
         * 큐레이션 축(§10.10)의 청크인가. 이 축은 검색 텍스트를 만드는 규칙이 다르므로
         * ({@code 질문 + 본문}, 질문은 벡터 메타데이터에 없다) 문서 청크용 재인덱싱을
         * 그대로 태우면 안 된다 — {@link AdminService#reindexChunk} 참고.
         */
        public boolean isCurated() {
            return "curated_qa".equals(metadata.get(MetaKey.DOC_TYPE));
        }

        /**
         * 이 청크의 {@code chunk_context} 가 <b>파일 안의 위치</b>로 시작하는가.
         *
         * <p>문서 청크의 값은 {@code KeywordExtractor.combineContext()} 가 만든
         * {@code "{파일명} > {헤딩}\n{LLM 1~2문장}"} 이고, 첫 줄은 파일명·헤딩에서 결정적으로
         * 파생되는 값이라 손으로 고칠 것이 아니다. 큐레이션 청크에는 그 개념 자체가 없다 —
         * 값 전체가 작성자가 쓴 요약이다.
         */
        public boolean hasBreadcrumb() { return !isCurated(); }

        /**
         * 읽기 전용 위치 표시. 큐레이션 청크와 위치를 모르는 청크에서는 빈 문자열이다.
         *
         * <p>이 규칙이 <b>서버에 있는 이유</b>: 화면에 두면 소비하는 자리가 둘이고(편집 패널을
         * 열 때, 키워드·요약 재생성 뒤 다시 읽을 때) 한쪽만 고치면 재생성 한 번이 요약을 읽기
         * 전용 칸으로 옮겨 놓는다 — 그리고 그 상태로 저장하면 요약이 사라진다. 실제로 그랬다.
         * 게다가 이 프로젝트에는 JS 테스트 하네스가 없어 그 규칙에 테스트를 붙일 수가 없었다.
         */
        public String contextBreadcrumb() { return splitContext()[0]; }

        /** 편집 가능한 요약. 위치 표시를 뗀 나머지이며, 큐레이션 청크에서는 값 전체다. */
        public String contextSummary() { return splitContext()[1]; }

        private String[] splitContext() {
            String combined = metadata.getOrDefault(MetaKey.CHUNK_CONTEXT, "");
            if (!hasBreadcrumb()) return new String[]{"", combined};
            int nl = combined.indexOf('\n');
            return nl < 0 ? new String[]{combined, ""}
                          : new String[]{combined.substring(0, nl), combined.substring(nl + 1)};
        }

        /**
         * 이 청크가 속한 {@code curated_qa} 행의 id. {@code doc_id} 가 {@code curated:<id>} 라
         * 거기서 되읽는다 — 큐레이션 청크가 아니거나 형식이 다르면 비어 있다.
         */
        public java.util.OptionalLong curatedRowId() {
            // 형식은 CuratedQaService 가 소유한다 — 쓰는 쪽과 읽는 쪽이 접두사를 따로 들고 있으면
            // 그것을 바꾸는 날 한쪽만 고쳐진다.
            return isCurated() ? CuratedQaService.rowIdOf(docId()) : java.util.OptionalLong.empty();
        }
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
        return new VectorStoreAdminView("chroma", r.available(), -1, totalChunks,
                r.items().size(), null, null, dbPath());
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
                null, vecVersion, dim, dbPath());
    }

    /**
     * The SQLite file behind this service's template — read from the DataSource the app actually opened,
     * not re-derived from settings (a re-derived path inherits whatever the wiring got wrong: this card
     * once reported an empty {@code memory.db} as the operational DB). Null when unknown (unit tests).
     */
    private String dbPath() {
        return jdbc == null ? null : DataSourceConfig.sqliteFilePath(jdbc.getDataSource());
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
     * 청크 하나의 본문/메타데이터 편집. 저장된 벡터는 그대로 둔다(재임베딩은 {@link #reindexChunk}).
     *
     * <p>{@link MetaKey#EDITED_AT} 를 찍는다 — 나중에 문서 단위 {@code ↺ 재인덱싱} 은 저장된 MD 에서
     * 모든 청크를 다시 만들어 이 편집을 버리므로, 그 전에 경고할 근거가 필요하다
     * ({@link #countEditedChunks}). 스탬프는 <b>편집이 일어나면 항상</b> 찍힌다. 예전에는
     * 클라이언트가 메타데이터 맵을 보냈을 때만 찍혀서, 본문만 고친 편집은 재인덱싱 경고에
     * 잡히지 않았다.
     *
     * <p><b>{@code clientMeta} 는 그대로 저장되지 않는다.</b> 화면에서 실제로 편집할 수 있는 것은
     * {@link #EDITABLE_CHUNK_META_KEYS} 둘뿐이고 메타데이터 JSON 은 읽기 전용이므로, 그 둘만 골라
     * <b>저장된 메타데이터 위에</b> 얹는다. 예전에는 클라이언트가 보낸 맵이 통째로 저장본을
     * 대체했다 — 그러면 (a) 요청 본문에 아무 키나 넣어 청크 메타데이터를 만들어 낼 수 있고,
     * (b) 화면이 보내지 않은 키(예: 로더가 붙이는 {@code section} 처럼 {@code MetaKey} 에 없는 것)가
     * 조용히 사라진다. 저장본에서 시작하면 두 문제가 함께 없어지고, 허용 목록을 늘릴 때
     * "화면에서 편집 가능한가"라는 한 가지 기준만 보면 된다.
     *
     * <p><b>Chroma 경로의 메타데이터 유실도 함께 고친다.</b> 예전에는 {@code newMeta == null} 이면
     * {@code Map.of()} 로 upsert 해 그 청크의 메타데이터를 전부 날렸다 — 본문만 보내는 호출
     * 하나로 {@code doc_id}·{@code filename}·태그가 사라지는 잠재 버그였다.
     */
    public void updateChunk(String collectionName, String chunkId,
                            String newText, Map<String, String> clientMeta) {
        if (isSqliteVec()) {
            // Metadata/text only — the stored vector (vec_embeddings) is intentionally preserved
            // (same caveat as the Chroma path: editing text does not re-embed).
            String oldText = newText == null ? null : storedTextSqliteVec(chunkId);   // image_paths 를 마커 변화에 맞추려면 옛 본문이 필요하다
            if (newText != null) {
                jdbc.update("UPDATE vec_document_chunks SET content = ? WHERE spring_doc_id = ?",
                        newText, chunkId);
            }
            jdbc.update("UPDATE vec_document_chunks SET metadata = ? WHERE spring_doc_id = ?",
                    toJson(mergeEditableMeta(storedMetaSqliteVec(chunkId), clientMeta, oldText, newText)), chunkId);
            if (newText != null && questionReuseService != null) {
                questionReuseService.invalidateChunk(chunkId);
            }
            return;
        }
        if (chromaApi == null) { log.warn("updateChunk ignored — no ChromaApi"); return; }
        // Fetch existing embedding to avoid re-embedding. METADATAS is fetched too: the stored
        // metadata is what the edit is merged onto (see this method's javadoc) — without it the
        // upsert below would replace it with whatever the client sent, or with nothing at all.
        GetEmbeddingsRequest req = new GetEmbeddingsRequest(
                List.of(chunkId), null, 1, 0,
                List.of(Include.EMBEDDINGS, Include.DOCUMENTS, Include.METADATAS));
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

        List<Map<String, String>> storedMetas = existing == null ? null : existing.metadata();
        Map<String, String> storedMeta = (storedMetas != null && !storedMetas.isEmpty()
                && storedMetas.get(0) != null) ? storedMetas.get(0) : Map.of();
        String oldText = (existing != null && existing.documents() != null && !existing.documents().isEmpty())
                ? existing.documents().get(0) : null;
        Map<String, Object> metaObj = new HashMap<>(mergeEditableMeta(storedMeta, clientMeta, oldText, newText));

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
        // 큐레이션 청크는 여기로 들어오면 안 된다. 아래 SearchTextBuilder.precompute() 는 문서
        // 청크의 규칙(chunk_context + 본문)으로 검색 텍스트를 다시 만드는데, 그 축의 검색
        // 텍스트는 질문 + 본문이고 질문은 벡터 메타데이터에 실려 있지 않다 — 그대로 태우면 그
        // 청크만 조용히 질문을 잃고, 질문형 질의와의 매칭이 급격히 나빠진다. 라우팅은
        // AdminController 가 하고(→ CuratedQaService.reembedRow), 여기서는 마지막 방어선이다.
        if (row.isCurated()) {
            log.warn("reindexChunk — 큐레이션 청크는 이 경로로 재인덱싱하지 않는다 id={} (CuratedQaService.reembedRow)", chunkId);
            return false;
        }
        String previousHash = questionReuseService == null ? "" : questionReuseService.currentChunkHash(chunkId);

        Map<String, Object> meta = new HashMap<>(row.metadata());
        // 이 청크가 마지막으로 색인된 시각. 목록의 수정일시는 이 값과 edited_at 중 최신을 보여준다.
        // edited_at 은 건드리지 않는다 — "손으로 고친 청크"의 표식이고 재인덱싱 사전 경고가 그 존재로 센다.
        // (저장은 위의 embed 가 성공해야 일어나므로 실패한 재인덱싱은 시각을 바꾸지 않는다.)
        meta.put(MetaKey.COLLECTED_AT, Instant.now().toString());
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

    /** {@link #applyChunkEdit} 의 결과 — {@code APPLIED} 외에는 청크가 바뀌지 않았다. */
    public enum ApplyOutcome {
        APPLIED,
        /** 청크가 없다(재인덱싱으로 id 가 바뀌었거나 삭제됨). */
        NOT_FOUND,
        /** 큐레이션 청크 — 단일 출처가 {@code curated_qa} 라 이 경로로 고치지 않는다. */
        CURATED,
        /** 현재 본문의 해시가 기대와 다르다 — 수정안을 만든 뒤 청크가 바뀌었다. */
        STALE,
        /** 임베딩(저장) 실패 — 아무것도 바뀌지 않았다. */
        EMBED_FAILED
    }

    /**
     * 청크 본문을 <b>임베딩을 먼저 하고</b> 제자리에서 바꾼다(§6.33 4b-2) — 청크 id 가 유지되고 벡터·FTS 가 새 본문으로 다시 만들어지며 재사용 표시는
     * "수정됨"이 된다. {@link #updateChunk} 는 임베딩을 그대로 두고({@code void}, 실패를 삼킨다) 본문만 바꿔 벡터와 본문이 어긋나므로 쓰지 않는다.
     *
     * <p>순서가 안전의 전부다: ① 현재 본문 해시가 {@code expectedHash} 와 같은지 확인(다르면 {@code STALE} — 수정안을 만든 뒤 누가 고쳤다) → ② 새
     * 본문 + 바뀐 맥락/키워드로 검색 텍스트를 만들어 {@code vectorStore.add} (임베딩이 먼저고 삭제·삽입이 그 뒤 한 트랜잭션이라 실패하면 옛 행이 그대로다)
     * → ③ FTS 색인 갈이. ②가 실패하면 {@code EMBED_FAILED} 이고 청크는 바뀌지 않았다.
     *
     * @param metaChanges {@link #EDITABLE_CHUNK_META_KEYS}({@code chunk_context}·{@code excerpt_keywords}) 중 바꿀 값 — 그 밖의 키는 무시한다.
     *                    키가 없거나 값이 {@code null} 이면 그 메타데이터는 그대로다
     */
    public ApplyOutcome applyChunkEdit(String collectionName, String chunkId, String newText,
                                       Map<String, String> metaChanges, String expectedHash) {
        ChunkRow row = getChunk(collectionName, chunkId);
        if (row == null) return ApplyOutcome.NOT_FOUND;
        if (row.isCurated()) return ApplyOutcome.CURATED;
        if (!com.example.ragagent.model.TextHash.sha256(row.fullText()).equals(expectedHash)) return ApplyOutcome.STALE;

        String previousHash = questionReuseService == null ? "" : questionReuseService.currentChunkHash(chunkId);
        Map<String, Object> meta = new HashMap<>(row.metadata());
        if (metaChanges != null) {
            for (String key : EDITABLE_CHUNK_META_KEYS) {
                String v = metaChanges.get(key);
                if (v != null) meta.put(key, v);
            }
        }
        // 손으로 고친 것과 같은 표식 — 문서 단위 재인덱싱은 저장된 MD 에서 청크를 다시 만들어 이 편집을 버릴 수 있다(MD 되쓰기가 성공하면 그렇지 않지만
        // 그 판단은 이 메서드가 아니라 호출자의 몫이므로 경고 쪽으로 기운다).
        meta.put(MetaKey.EDITED_AT, Instant.now().toString());

        Document doc = SearchTextBuilder.precompute(new Document(chunkId, newText == null ? "" : newText, meta));
        try {
            vectorStore.add(DocRegistry.SHARED, row.metadata().get(MetaKey.VERSION), List.of(doc));
        } catch (Exception e) {
            log.error("applyChunkEdit embed/store failed id={}: {}", chunkId, e.getMessage());
            return ApplyOutcome.EMBED_FAILED;
        }
        keywordRepo.deleteBySpringDocIds(List.of(chunkId));
        keywordRepo.indexChunks(List.of(doc));
        if (questionReuseService != null) {
            questionReuseService.invalidateChunkIfHashChanged(chunkId, previousHash);
        }
        log.info("[ADMIN] chunk edit applied id={}", chunkId);
        return ApplyOutcome.APPLIED;
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

    /**
     * {@code /admin} 청크 편집 화면이 실제로 바꿀 수 있는 메타데이터 키.
     *
     * <p>나머지는 화면에서 읽기 전용이고({@code admin.html} 의 메타데이터 JSON), 그래서 서버도
     * 그렇게 다룬다 — 목록에 없는 키는 요청에 실려 와도 무시된다. 새 키를 여기 넣기 전에 물을
     * 것은 하나다: <b>화면에서 편집할 수 있는가.</b>
     */
    static final Set<String> EDITABLE_CHUNK_META_KEYS =
            Set.of(MetaKey.EXCERPT_KEYWORDS, MetaKey.CHUNK_CONTEXT);

    /**
     * 저장된 메타데이터에 편집 가능한 키만 얹고 편집 시각을 찍는다.
     *
     * <p>호출자가 넘긴 맵은 건드리지 않는다(불변 맵일 수 있고, 남의 맵을 고쳐 놓아서도 안 된다).
     * {@code clientMeta} 가 {@code null} 이면 저장본 + 스탬프만 남는다 — 본문만 고친 편집이다.
     *
     * <p><b>큐레이션 청크에서는 그 둘도 편집 대상이 아니다.</b> 이 축에서 요약·키워드의 단일
     * 출처는 {@code curated_qa.summary}/{@code .keywords} 컬럼이고, 벡터 메타데이터의 값은
     * 재임베딩마다 거기서 다시 쓰이는 <b>사본</b>이다({@code CuratedQaService.buildDocument}).
     * 받아 주면 화면은 "저장되었습니다"라고 말하는데 다음 재임베딩이 조용히 옛 값으로 되돌린다 —
     * 오류도 로그도 없이. 고치는 자리는 {@code /admin} 큐레이션 패널 하나이며
     * ({@code CuratedQaService.updateEntry}), 거기 저장은 재임베딩까지 함께 돈다.
     *
     * <p>화면도 그 두 칸을 잠그지만 규칙의 자리는 여기다 — 이 클래스가 이미 그렇게 하고 있다
     * (허용 목록 자체가 화면 규칙이 아니라 서버 규칙이다).
     */
    static Map<String, String> mergeEditableMeta(Map<String, String> storedMeta,
                                                 Map<String, String> clientMeta) {
        return mergeEditableMeta(storedMeta, clientMeta, null, null);
    }

    /**
     * 위와 같되, 본문이 바뀌었으면({@code newText != null}) {@link MetaKey#IMAGE_PATHS} 도 마커의 변화에 맞춘다.
     *
     * <p><b>왜 필요한가.</b> {@code image_paths} 는 인덱싱 때 정해져 저장되고, 검색이 그것을 읽어 지연 Vision 대상과 답변 썸네일을
     * 정한다({@code RetrievalService.imagePathsMeta}). 편집 화면에서 그림을 넣고 빼도 이 값이 그대로면 지운 그림이 답변에 계속 붙고
     * 새 그림은 붙지 않는다.
     *
     * <p><b>본문 전체에서 다시 만들지 않는다.</b> 옛 값 − (옛 본문에는 있었는데 새 본문에서 사라진 마커) + (새 본문의 마커)다.
     * 스캔 PDF처럼 본문에 마커가 없이 {@code image_paths} 만 가진 청크가 있어서, 본문의 마커만으로 다시 만들면 본문 한 글자를
     * 고쳤을 뿐인데 그 그림들이 조용히 떨어져 나간다. {@code oldText} 를 모르면({@code null}) 사라진 마커는 없는 것으로 본다.
     */
    static Map<String, String> mergeEditableMeta(Map<String, String> storedMeta, Map<String, String> clientMeta,
                                                 String oldText, String newText) {
        Map<String, String> merged = mergeEditableMetaBase(storedMeta, clientMeta);
        if (newText != null) {
            java.util.LinkedHashSet<String> paths = new java.util.LinkedHashSet<>();
            String stored = merged.get(MetaKey.IMAGE_PATHS);
            if (stored != null && !stored.isBlank()) {
                for (String p : stored.split(",")) if (!p.isBlank()) paths.add(p.strip());
            }
            if (oldText != null) {
                List<String> now = ChunkImageEditor.paths(newText);
                for (String p : ChunkImageEditor.paths(oldText)) if (!now.contains(p)) paths.remove(p);
            }
            paths.addAll(ChunkImageEditor.paths(newText));
            if (paths.isEmpty()) merged.remove(MetaKey.IMAGE_PATHS);
            else merged.put(MetaKey.IMAGE_PATHS, String.join(",", paths));
        }
        return merged;
    }

    private static Map<String, String> mergeEditableMetaBase(Map<String, String> storedMeta,
                                                             Map<String, String> clientMeta) {
        Map<String, String> merged = new LinkedHashMap<>(storedMeta == null ? Map.of() : storedMeta);
        boolean curated = "curated_qa".equals(merged.get(MetaKey.DOC_TYPE));
        if (clientMeta != null && !curated) {
            for (String key : EDITABLE_CHUNK_META_KEYS) {
                String value = clientMeta.get(key);
                if (value != null) merged.put(key, value);
            }
        }
        merged.put(MetaKey.EDITED_AT, Instant.now().toString());
        return merged;
    }

    /** sqlite-vec 백엔드에 저장된 이 청크의 본문. 없거나 읽지 못하면 {@code null}. */
    private String storedTextSqliteVec(String chunkId) {
        try {
            List<String> rows = jdbc.query("SELECT content FROM vec_document_chunks WHERE spring_doc_id = ?",
                    (rs, n) -> rs.getString(1), chunkId);
            return (rows == null || rows.isEmpty()) ? null : rows.get(0);
        } catch (Exception e) {
            log.warn("updateChunk: 기존 본문 조회 실패 id={} — image_paths 는 새 본문의 마커만 더한다: {}", chunkId, e.getMessage());
            return null;
        }
    }

    /** sqlite-vec 백엔드에 저장된 이 청크의 메타데이터. 없거나 읽지 못하면 빈 맵. */
    private Map<String, String> storedMetaSqliteVec(String chunkId) {
        try {
            List<String> rows = jdbc.query(
                    "SELECT metadata FROM vec_document_chunks WHERE spring_doc_id = ?",
                    (rs, n) -> rs.getString(1), chunkId);
            return (rows == null || rows.isEmpty()) ? Map.of() : parseMeta(rows.get(0));
        } catch (Exception e) {
            log.warn("updateChunk: 기존 메타데이터 조회 실패 id={} — 편집분만 저장한다: {}",
                    chunkId, e.getMessage());
            return Map.of();
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
