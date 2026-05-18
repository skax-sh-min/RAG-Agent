package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.ingestion.DocRegistry;
import com.example.ragagent.ingestion.DocumentIndexer;
import com.example.ragagent.ingestion.IndexRequest;
import com.example.ragagent.ingestion.VectorStoreFacade;
import com.example.ragagent.model.DocumentInfo;
import com.example.ragagent.model.IndexingProgressEvent;
import com.example.ragagent.model.SyncResult;
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
 * All operations are scoped to a userId — file paths and Chroma collections are isolated per user.
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final DocumentIndexer indexer;
    private final DocRegistry docRegistry;
    private final VectorStoreFacade vectorStore;
    private final AppProperties props;

    public RagService(DocumentIndexer indexer, DocRegistry docRegistry,
                      VectorStoreFacade vectorStore, AppProperties props) {
        this.indexer     = indexer;
        this.docRegistry = docRegistry;
        this.vectorStore = vectorStore;
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
        DocumentInfo info = indexer.index(IndexRequest.single(filePath, filename, version, userId, onProgress));
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
        indexer.deleteArtifacts(userId, docId, version);
        docRegistry.save();
    }

    public List<DocumentInfo> listDocuments(String userId) {
        return docRegistry.entries(userId).stream()
                .map(e -> {
                    DocRegistry.DocRegistryEntry r = e.getValue();
                    String filename = DocRegistry.filenameFromDocId(e.getKey());
                    return new DocumentInfo(e.getKey(), filename, r.version(),
                            r.chunks(), r.indexedAt(), r.sha256(), r.errors());
                })
                .sorted(Comparator.comparing(DocumentInfo::indexedAt).reversed())
                .toList();
    }

    public List<Document> search(String userId, String query, String version, int topK) {
        return vectorStore.search(userId, query, version, topK);
    }

    public void reindexFromMd(String docId) throws IOException {
        indexer.reindexFromMd(docId);
    }

    // ── Path helpers ───────────────────────────────────────────────────────

    /** Per-user documents directory: {dataDir}/users/{userId}/documents */
    public Path userDocumentsDir(String userId) {
        return Path.of(props.dataDir()).resolve("users").resolve(userId).resolve("documents");
    }

    // ── Static utilities (kept here for backward compatibility) ────────────

    public static final List<String> SUPPORTED_EXTENSIONS = List.of(".pdf", ".pptx", ".docx", ".txt", ".md");

    public static boolean isSupportedExtension(String filename) {
        if (filename == null) return false;
        String lower = filename.toLowerCase();
        return SUPPORTED_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }
}
