package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.exception.DocumentIndexingException;
import com.example.ragagent.ingestion.DocRegistry;
import com.example.ragagent.ingestion.DocumentIndexer;
import com.example.ragagent.ingestion.KeywordSearchRepository;
import com.example.ragagent.ingestion.IndexRequest;
import com.example.ragagent.ingestion.VectorStoreFacade;
import com.example.ragagent.model.DocumentInfo;
import com.example.ragagent.model.IndexingProgressEvent;
import com.example.ragagent.model.SyncResult;
import com.example.ragagent.model.TagUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/**
 * RAG pipeline orchestrator.
 * Delegates indexing logic to {@link DocumentIndexer}, registry to {@link DocRegistry},
 * and vector-store operations to {@link VectorStoreFacade}.
 * Documents are stored in shared paths (data/documents, data/images, data/converted)
 * and indexed into a single shared Chroma collection — no per-user isolation.
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final DocumentIndexer indexer;
    private final DocRegistry docRegistry;
    private final VectorStoreFacade vectorStore;
    private final KeywordSearchRepository keywordRepo;
    private final AppProperties props;

    public RagService(DocumentIndexer indexer, DocRegistry docRegistry,
                      VectorStoreFacade vectorStore, KeywordSearchRepository keywordRepo,
                      AppProperties props) {
        this.indexer     = indexer;
        this.docRegistry = docRegistry;
        this.vectorStore = vectorStore;
        this.keywordRepo = keywordRepo;
        this.props       = props;
    }

    // ── Public API ─────────────────────────────────────────────────────────

    public DocumentInfo indexDocument(String userId, Path filePath, String version) throws IOException {
        return indexDocument(userId, filePath, filePath.getFileName().toString(), version);
    }

    public DocumentInfo indexDocument(String userId, Path filePath, String filename,
                                      String version) throws IOException {
        return indexDocument(userId, filePath, filename, version, event -> {});
    }

    public DocumentInfo indexDocument(String userId, Path filePath, String filename, String version,
                                      Consumer<IndexingProgressEvent> onProgress) throws IOException {
        return indexDocument(userId, filePath, filename, version, List.of(), onProgress);
    }

    /** index with search-scope tags stored in chunk metadata. */
    public DocumentInfo indexDocument(String userId, Path filePath, String filename, String version,
                                      List<String> tags,
                                      Consumer<IndexingProgressEvent> onProgress) throws IOException {
        return indexDocument(userId, filePath, filename, version, tags, false, onProgress);
    }

    /** index with search-scope tags and optional local image-description insertion during MD correction. */
    public DocumentInfo indexDocument(String userId, Path filePath, String filename, String version,
                                      List<String> tags,
                                      boolean addImageDescriptions,
                                      Consumer<IndexingProgressEvent> onProgress) throws IOException {
        return indexDocument(userId, filePath, filename, version, tags,
                addImageDescriptions, false, onProgress);
    }

    /** index with optional second-pass heading numbering + code-block polishing. */
    public DocumentInfo indexDocument(String userId, Path filePath, String filename, String version,
                                      List<String> tags,
                                      boolean addImageDescriptions,
                                      boolean addHeadingNumbers,
                                      Consumer<IndexingProgressEvent> onProgress) throws IOException {
        DocumentInfo info = indexer.index(IndexRequest.single(
                filePath, filename, version, userId, tags,
                addImageDescriptions, addHeadingNumbers, onProgress));
        docRegistry.save();
        return info;
    }

    public SyncResult syncDirectory(String userId, String version) throws IOException {
        return syncDirectory(userId, version, event -> {});
    }

    public SyncResult syncDirectory(String userId, String version,
                                    Consumer<IndexingProgressEvent> onProgress) throws IOException {
        Path documentsDir = userDocumentsDir(userId);
        Files.createDirectories(documentsDir);
        return indexer.syncDirectory(userId, version, documentsDir, onProgress);
    }

    public void deleteDocument(String userId, String docId, String version) throws IOException {
        indexer.deleteArtifacts(DocRegistry.SHARED, docId, version);
        docRegistry.save();
    }

    public List<DocumentInfo> listDocuments(String userId) {
        List<Map.Entry<String, DocRegistry.DocRegistryEntry>> entries = docRegistry.entries(DocRegistry.SHARED).stream()
            .toList();
        List<String> docIds = entries.stream().map(Map.Entry::getKey).toList();
        Map<String, List<String>> tagsByDocId = keywordRepo.tagsByDocIds(docIds);

        return entries.stream()
                .map(e -> {
                    DocRegistry.DocRegistryEntry r = e.getValue();
                    String filename = DocRegistry.filenameFromDocId(e.getKey());
                    return new DocumentInfo(e.getKey(), filename, r.version(),
                    r.chunks(), r.indexedAt(), r.sha256(),
                    tagsByDocId.getOrDefault(e.getKey(), List.of()),
                    r.errors());
                })
                .sorted(Comparator.comparing(DocumentInfo::indexedAt).reversed())
                .toList();
    }

    /** Single-document lookup (current tags included) — powers the tag-edit UI. Empty if not found. */
    public Optional<DocumentInfo> findDocument(String userId, String docId) {
        return docRegistry.findByDocId(docId, DocRegistry.SHARED).map(r -> {
            List<String> tags = keywordRepo.tagsByDocIds(List.of(docId)).getOrDefault(docId, List.of());
            return new DocumentInfo(docId, DocRegistry.filenameFromDocId(docId), r.version(),
                    r.chunks(), r.indexedAt(), r.sha256(), tags, r.errors());
        });
    }

    /**
     * Replaces a document's search-scope tags — metadata-only, no re-embedding (tags never
     * influence the vector). Updates both the vector store (search filter source) and
     * {@code chunk_fts.doc_tags} (suggestion UI + reindex-tag-restore source) so the two stay
     * consistent. Throws {@link IllegalArgumentException} on tag policy violation ({@link
     * TagUtils#normalize}) or when {@code docId} does not exist in {@code doc_registry}.
     *
     * <p>Verifies the {@code chunk_fts} write actually touched a row — a {@code doc_registry}
     * entry can outlive its real chunk data (e.g. a prior indexing/reindex failure that never
     * reached {@code saveRegistry()} for the new state), in which case the update would silently
     * affect 0 rows and this method would otherwise return a falsely successful result. Throws
     * {@link DocumentIndexingException} in that case, telling the caller to re-sync/re-upload.
     */
    public DocumentInfo updateDocumentTags(String userId, String docId, List<String> rawTags) {
        List<String> tags = TagUtils.normalize(rawTags);
        DocRegistry.DocRegistryEntry entry = docRegistry.findByDocId(docId, DocRegistry.SHARED)
                .orElseThrow(() -> new IllegalArgumentException("문서를 찾을 수 없습니다: " + docId));

        String tagsCsv = TagUtils.toMetaValue(tags);
        vectorStore.updateTags(DocRegistry.SHARED, entry.version(), entry.springDocIds(), tagsCsv);
        int updatedRows = keywordRepo.updateDocTags(docId, tagsCsv);
        if (updatedRows == 0) {
            throw new DocumentIndexingException(
                    "문서의 색인 데이터를 찾을 수 없어 태그를 저장하지 못했습니다 (재동기화 또는 재업로드가 필요합니다): " + docId);
        }

        return new DocumentInfo(docId, DocRegistry.filenameFromDocId(docId), entry.version(),
                entry.chunks(), entry.indexedAt(), entry.sha256(), tags, entry.errors());
    }

    public List<Document> search(String userId, String query, String version, int topK) {
        return vectorStore.search(DocRegistry.SHARED, query, version, topK);
    }

    /** batched multi-query search — one embedding call + one Chroma query for all variants. */
    public List<List<Document>> searchBatch(String userId, List<String> queries, String version, int topK) {
        return vectorStore.searchBatch(DocRegistry.SHARED, queries, version, topK);
    }

    /** BM25 keyword (FTS5) search axis for hybrid retrieval. */
    /** Distinct tags in use (optionally scoped to a version) for tag-suggestion UI. */
    public List<String> listTags(String version) {
        return keywordRepo.distinctTags(version);
    }

    /** Distinct versions in use for version-selector UI. */
    public List<String> listVersions() {
        Set<String> versions = new HashSet<>();
        for (Map.Entry<String, DocRegistry.DocRegistryEntry> e : docRegistry.entries(DocRegistry.SHARED)) {
            String version = e.getValue().version();
            if (version != null && !version.isBlank()) versions.add(version);
        }
        versions.add("latest");
        return versions.stream().sorted().toList();
    }

    public List<Document> keywordSearch(String version, String question, int topK) {
        return keywordRepo.search(version, question, topK);
    }

    public void reindexFromMd(String docId) throws IOException {
        indexer.reindexFromMd(docId);
    }

    /** Same as {@link #reindexFromMd(String)}, reporting per-stage progress via {@code onProgress}. */
    public void reindexFromMd(String docId, Consumer<IndexingProgressEvent> onProgress) throws IOException {
        indexer.reindexFromMd(docId, onProgress);
    }

    // ── Path helpers ───────────────────────────────────────────────────────

    /** Shared documents directory: {dataDir}/documents */
    public Path userDocumentsDir(String userId) {
        return Path.of(props.dataDir()).resolve("documents");
    }

    // ── Static utilities (kept here for backward compatibility) ────────────

    public static final List<String> SUPPORTED_EXTENSIONS = List.of(".pdf", ".pptx", ".docx", ".txt", ".md");

    public static boolean isSupportedExtension(String filename) {
        if (filename == null) return false;
        String lower = filename.toLowerCase();
        return SUPPORTED_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }
}
