package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.ingestion.DocRegistry;
import com.example.ragagent.ingestion.DocumentIndexer;
import com.example.ragagent.ingestion.IndexRequest;
import com.example.ragagent.ingestion.VectorStoreFacade;
import com.example.ragagent.model.DocumentInfo;
import com.example.ragagent.model.IndexingProgressEvent;
import com.example.ragagent.model.SyncResult;
import jakarta.annotation.PostConstruct;
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
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final DocumentIndexer indexer;
    private final DocRegistry docRegistry;
    private final VectorStoreFacade vectorStore;
    private final AppProperties props;

    private Path documentsDir;

    public RagService(DocumentIndexer indexer, DocRegistry docRegistry,
                      VectorStoreFacade vectorStore, AppProperties props) {
        this.indexer     = indexer;
        this.docRegistry = docRegistry;
        this.vectorStore = vectorStore;
        this.props       = props;
    }

    @PostConstruct
    void init() throws IOException {
        documentsDir = Path.of(props.dataDir()).resolve("documents");
        Files.createDirectories(documentsDir);
    }

    // ── Public API ─────────────────────────────────────────────────────────

    public DocumentInfo indexDocument(Path filePath, String version) throws IOException {
        return indexDocument(filePath, filePath.getFileName().toString(), version);
    }

    /**
     * Indexes a file using an explicit logical {@code filename} for metadata/registry,
     * decoupled from the on-disk path. Used by upload endpoints where the file is staged
     * to a temporary path but must be tracked under the user's original filename.
     */
    public DocumentInfo indexDocument(Path filePath, String filename, String version) throws IOException {
        return indexDocument(filePath, filename, version, event -> {});
    }

    /**
     * Indexes a document and reports granular progress via {@code onProgress}.
     * Events: loading → chunking (with total) → enriching (K/N) → storing → [caller sends done/error]
     */
    public DocumentInfo indexDocument(Path filePath, String filename, String version,
                                      Consumer<IndexingProgressEvent> onProgress) throws IOException {
        DocumentInfo info = indexer.index(IndexRequest.single(filePath, filename, version, onProgress));
        docRegistry.save();
        return info;
    }

    public SyncResult syncDirectory(String version) throws IOException {
        return syncDirectory(version, event -> {});
    }

    public SyncResult syncDirectory(String version,
                                    Consumer<IndexingProgressEvent> onProgress) throws IOException {
        return indexer.syncDirectory(version, documentsDir, onProgress);
    }

    public void deleteDocument(String docId, String version) throws IOException {
        indexer.deleteArtifacts(docId, version);
        docRegistry.save();
    }

    public List<DocumentInfo> listDocuments() {
        return docRegistry.entries().stream()
                .map(e -> {
                    DocRegistry.DocRegistryEntry r = e.getValue();
                    String filename = DocRegistry.filenameFromDocId(e.getKey());
                    return new DocumentInfo(e.getKey(), filename, r.version(),
                            r.chunks(), r.indexedAt(), r.sha256(), r.errors());
                })
                .sorted(Comparator.comparing(DocumentInfo::indexedAt).reversed())
                .toList();
    }

    public List<Document> search(String query, String version, int topK) {
        return vectorStore.search(query, version, topK);
    }

    public void reindexFromMd(String docId) throws IOException {
        indexer.reindexFromMd(docId);
    }

    // ── Static utilities (kept here for backward compatibility) ────────────

    public static final List<String> SUPPORTED_EXTENSIONS = List.of(".pdf", ".pptx", ".docx", ".txt", ".md");

    public static boolean isSupportedExtension(String filename) {
        if (filename == null) return false;
        String lower = filename.toLowerCase();
        return SUPPORTED_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

}
