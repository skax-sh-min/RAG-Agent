package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.model.DocumentInfo;
import com.example.ragagent.model.SyncResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.transformer.KeywordMetadataEnricher;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * RAG pipeline: document loading, chunking, metadata tagging,
 * incremental indexing (SHA-256 based), and semantic search.
 *
 * Equivalent to rag.py in the Python version.
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    private static final String REGISTRY_FILE = "doc_registry.json";
    private static final Pattern SAFE_VERSION = Pattern.compile("^[a-zA-Z0-9._\\-]{1,50}$");

    private final AppProperties props;
    private final DocumentLoaderService loaderService;
    private final ImageExtractorService imageExtractorService;
    private final VectorStoreRegistry vectorStoreRegistry;
    private final ObjectMapper mapper;
    private final KeywordMetadataEnricher keywordEnricher;

    // doc_id -> DocRegistryEntry (persisted to JSON)
    private final ConcurrentHashMap<String, DocRegistryEntry> registry = new ConcurrentHashMap<>();

    private Path dataDir;
    private Path documentsDir;
    private Path registryPath;

    public RagService(AppProperties props, DocumentLoaderService loaderService,
                      ImageExtractorService imageExtractorService,
                      VectorStoreRegistry vectorStoreRegistry, ChatModel chatModel) {
        this.props = props;
        this.loaderService = loaderService;
        this.imageExtractorService = imageExtractorService;
        this.vectorStoreRegistry = vectorStoreRegistry;
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.keywordEnricher = new KeywordMetadataEnricher(chatModel, 5);
    }

    @PostConstruct
    void init() throws IOException {
        dataDir = Path.of(props.dataDir());
        documentsDir = dataDir.resolve("documents");
        registryPath = dataDir.resolve(REGISTRY_FILE);
        Files.createDirectories(documentsDir);
        loadRegistry();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────

    public DocumentInfo indexDocument(Path filePath, String version) throws IOException {
        return indexDocument(filePath, filePath.getFileName().toString(), version);
    }

    /**
     * Indexes a file using an explicit logical {@code filename} for metadata/registry,
     * decoupled from the on-disk path. Used by upload endpoints where the file is staged
     * to a temporary path but must be tracked under the user's original filename.
     */
    public DocumentInfo indexDocument(Path filePath, String filename, String version) throws IOException {
        String sha256 = computeSha256(filePath);
        String docId = filename + "_" + sha256.substring(0, 8);
        String docType = inferDocType(filename);
        Path imagesDir = dataDir.resolve("images").resolve(docId);

        // Load & split (DOCX: converter-based with image extraction; PPTX/PDF: extract separately)
        List<Document> rawDocs;
        String lower = filename.toLowerCase();
        if (lower.endsWith(".docx")) {
            rawDocs = loaderService.loadDocx(filePath, docId, imagesDir);
        } else {
            rawDocs = loaderService.load(filePath);
            if (lower.endsWith(".pptx") || lower.endsWith(".pdf")) {
                rawDocs = injectImagePaths(rawDocs, imageExtractorService.extract(filePath, docId, imagesDir));
            }
        }
        List<Document> chunks = splitDocuments(rawDocs, filename, props.chunkSize(), props.chunkOverlap());

        // Tag metadata
        List<Document> tagged = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            Map<String, Object> meta = new HashMap<>(chunk.getMetadata());
            meta.put("doc_id", docId);
            meta.put("filename", filename);
            meta.put("version", version);
            meta.put("doc_type", docType);
            meta.put("sha256", sha256);
            meta.put("collected_at", Instant.now().toString());
            meta.putIfAbsent("source_type", "file");
            meta.putIfAbsent("page_or_slide", i + 1);
            tagged.add(new Document(chunk.getText(), meta));
        }

        // Delete old chunks for same doc_id if already indexed
        deleteByDocId(docId, version);

        // Enrich chunks with LLM-extracted keywords (adds excerpt_keywords metadata)
        List<Document> enriched = keywordEnricher.apply(tagged);

        // Add to vector store
        VectorStore store = vectorStoreRegistry.getStore(version);
        store.add(enriched);

        List<String> docIds = enriched.stream().map(Document::getId).toList();
        DocRegistryEntry entry = new DocRegistryEntry(sha256, version,
                Instant.now().toString(), tagged.size(), docIds, List.of());
        registry.put(docId, entry);
        saveRegistry();

        return new DocumentInfo(docId, filename, version, tagged.size(),
                entry.indexedAt(), sha256, List.of());
    }

    public SyncResult syncDirectory(String version) throws IOException {
        // Phase 1 (single thread): collect files on disk and detect what needs indexing
        Map<String, Path> filesOnDisk = new HashMap<>();
        if (Files.exists(documentsDir)) {
            try (Stream<Path> stream = Files.list(documentsDir)) {
                stream.filter(p -> RagService.isSupportedExtension(p.getFileName().toString()))
                      .forEach(p -> filesOnDisk.put(p.getFileName().toString(), p));
            }
        }

        record FileEntry(Path path, String staleDocId) {}
        Map<String, FileEntry> filesToIndex = new HashMap<>();

        for (Map.Entry<String, Path> e : filesOnDisk.entrySet()) {
            String filename = e.getKey();
            Path filePath = e.getValue();
            String sha256 = computeSha256(filePath);
            String docId = filename + "_" + sha256.substring(0, 8);

            boolean alreadyIndexed = registry.values().stream()
                    .anyMatch(r -> r.sha256().equals(sha256) && r.version().equals(version));
            if (alreadyIndexed) continue;

            String staleDocId = registry.keySet().stream()
                    .filter(k -> k.startsWith(filename + "_") && !k.equals(docId)
                              && version.equals(registry.get(k).version()))
                    .findFirst().orElse(null);
            // staleDocId deletion deferred to Phase 2 — only removed after successful re-indexing
            filesToIndex.put(filename, new FileEntry(filePath, staleDocId));
        }

        // Phase 2 (parallel): index each file
        int fileConcurrency = props.indexingSafe().maxConcurrentFiles();
        Semaphore llmGate = new Semaphore(props.indexingSafe().maxConcurrentLlmCalls());
        List<String> indexed = new CopyOnWriteArrayList<>();
        List<String> updated = new CopyOnWriteArrayList<>();

        try (ExecutorService filePool = Executors.newFixedThreadPool(
                fileConcurrency, Thread.ofVirtual().factory())) {
            List<CompletableFuture<Void>> futures = filesToIndex.entrySet().stream()
                .map(e -> CompletableFuture.runAsync(() -> {
                    try {
                        indexDocumentParallel(e.getValue().path(), version, llmGate, e.getValue().staleDocId());
                        (e.getValue().staleDocId() != null ? updated : indexed).add(e.getKey());
                    } catch (Exception ex) {
                        log.error("Parallel index failed: {}", e.getKey(), ex);
                    }
                }, filePool))
                .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        // Phase 3 (single thread): detect deleted files
        List<String> deleted = new ArrayList<>();
        for (String docId : new HashSet<>(registry.keySet())) {
            DocRegistryEntry entry = registry.get(docId);
            if (!version.equals(entry.version())) continue;
            String filename = docId.substring(0, docId.lastIndexOf('_'));
            if (!filesOnDisk.containsKey(filename)) {
                deleteByDocId(docId, version);
                registry.remove(docId);
                deleted.add(filename);
            }
        }

        saveRegistry();
        return new SyncResult(List.copyOf(indexed), List.copyOf(updated), deleted);
    }

    public void deleteDocument(String docId, String version) throws IOException {
        deleteByDocId(docId, version);
        registry.remove(docId);
        saveRegistry();
    }

    public List<DocumentInfo> listDocuments() {
        return registry.entrySet().stream()
                .map(e -> {
                    DocRegistryEntry r = e.getValue();
                    String filename = e.getKey().substring(0, e.getKey().lastIndexOf('_'));
                    return new DocumentInfo(e.getKey(), filename, r.version(),
                            r.chunks(), r.indexedAt(), r.sha256(), r.errors());
                })
                .sorted(Comparator.comparing(DocumentInfo::indexedAt).reversed())
                .toList();
    }

    public List<Document> search(String query, String version, int topK) {
        String safeVersion = (version != null && SAFE_VERSION.matcher(version).matches()) ? version : "latest";
        VectorStore store = vectorStoreRegistry.getStore(safeVersion);
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression(b.eq("version", safeVersion).build())
                .build();
        return store.similaritySearch(request);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────────────────────────────

    private void indexDocumentParallel(Path filePath, String version, Semaphore llmGate, String staleDocId) throws IOException {
        String filename = filePath.getFileName().toString();
        String sha256 = computeSha256(filePath);
        String docId = filename + "_" + sha256.substring(0, 8);
        String docType = inferDocType(filename);
        Path imagesDir = dataDir.resolve("images").resolve(docId);

        List<Document> rawDocs;
        String lower = filename.toLowerCase();
        if (lower.endsWith(".docx")) {
            rawDocs = loaderService.loadDocx(filePath, docId, imagesDir);
        } else {
            rawDocs = loaderService.load(filePath);
            if (lower.endsWith(".pptx") || lower.endsWith(".pdf")) {
                rawDocs = injectImagePaths(rawDocs, imageExtractorService.extract(filePath, docId, imagesDir));
            }
        }
        List<Document> chunks = splitDocuments(rawDocs, filename, props.chunkSize(), props.chunkOverlap());

        List<Document> tagged = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            Map<String, Object> meta = new HashMap<>(chunk.getMetadata());
            meta.put("doc_id", docId);
            meta.put("filename", filename);
            meta.put("version", version);
            meta.put("doc_type", docType);
            meta.put("sha256", sha256);
            meta.put("collected_at", Instant.now().toString());
            meta.putIfAbsent("source_type", "file");
            meta.putIfAbsent("page_or_slide", i + 1);
            tagged.add(new Document(chunk.getText(), meta));
        }

        deleteByDocId(docId, version);

        List<Document> enriched = enrichParallel(tagged, llmGate);

        VectorStore store = vectorStoreRegistry.getStore(version);
        store.add(enriched);

        List<String> docIds = enriched.stream().map(Document::getId).toList();
        registry.put(docId, new DocRegistryEntry(sha256, version,
                Instant.now().toString(), tagged.size(), docIds, List.of()));

        // Delete stale version only after new indexing succeeds — prevents data loss on failure
        if (staleDocId != null) {
            deleteByDocId(staleDocId, version);
            registry.remove(staleDocId);
        }
        // saveRegistry() intentionally omitted — called once after all parallel work completes
    }

    private List<Document> enrichParallel(List<Document> chunks, Semaphore llmGate) {
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            return chunks.stream()
                .map(chunk -> CompletableFuture.supplyAsync(() -> {
                    llmGate.acquireUninterruptibly();
                    try {
                        return keywordEnricher.apply(List.of(chunk)).get(0);
                    } finally {
                        llmGate.release();
                    }
                }, exec))
                .toList()
                .stream()
                .map(CompletableFuture::join)
                .toList();
        }
    }

    private void deleteByDocId(String docId, String version) {
        DocRegistryEntry existing = registry.get(docId);
        if (existing == null || existing.springDocIds().isEmpty()) return;
        VectorStore store = vectorStoreRegistry.getStore(version);
        store.delete(existing.springDocIds());
        deleteImagesQuietly(dataDir.resolve("images").resolve(docId));
    }

    private void deleteImagesQuietly(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) {}
            });
        } catch (IOException e) {
            log.warn("Image directory cleanup failed {}: {}", dir, e.getMessage());
        }
    }

    private List<Document> injectImagePaths(List<Document> docs, Map<Integer, List<String>> imageMap) {
        if (imageMap.isEmpty()) return docs;
        List<Document> result = new ArrayList<>(docs.size());
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            List<String> imgs = imageMap.getOrDefault(i + 1, List.of());
            if (imgs.isEmpty()) {
                result.add(doc);
            } else {
                Map<String, Object> meta = new HashMap<>(doc.getMetadata());
                meta.put("image_paths", String.join(",", imgs));
                result.add(new Document(doc.getText(), meta));
            }
        }
        return result;
    }

    private List<Document> splitDocuments(List<Document> docs, String filename, int chunkSize, int overlap) {
        String lower = filename.toLowerCase();

        if (lower.endsWith(".pptx")) {
            // Already one Document per slide — no further splitting
            return new ArrayList<>(docs);
        }

        if (lower.endsWith(".md") || lower.endsWith(".docx")) {
            // Loader already produced section-level Documents.
            // Apply sliding window only when a section exceeds chunkSize.
            List<Document> result = new ArrayList<>();
            for (Document doc : docs) {
                if (doc.getText() == null || doc.getText().isBlank()) continue;
                if (doc.getText().length() <= chunkSize) {
                    result.add(doc);
                } else {
                    result.addAll(slidingWindow(doc, chunkSize, overlap));
                }
            }
            return result;
        }

        // PDF, TXT: sliding window on every document
        List<Document> result = new ArrayList<>();
        for (Document doc : docs) {
            result.addAll(slidingWindow(doc, chunkSize, overlap));
        }
        return result;
    }

    private List<Document> slidingWindow(Document doc, int chunkSize, int overlap) {
        List<Document> result = new ArrayList<>();
        String text = doc.getText();
        if (text == null || text.isBlank()) return result;
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            if (end < text.length()) {
                int lastNl = text.lastIndexOf('\n', end);
                if (lastNl > start + overlap) end = lastNl + 1;
            }
            String chunk = text.substring(start, end).strip();
            if (!chunk.isBlank()) {
                result.add(new Document(chunk, new HashMap<>(doc.getMetadata())));
            }
            start = Math.max(start + 1, end - overlap);
        }
        return result;
    }

    private String computeSha256(Path filePath) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(filePath));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new RuntimeException("SHA-256 computation failed", e);
        }
    }

    private String inferDocType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.contains("guide")) return "guide";
        if (lower.contains("edu") || lower.contains("lesson")) return "education";
        return "manual";
    }

    public static final List<String> SUPPORTED_EXTENSIONS = List.of(".pdf", ".pptx", ".docx", ".txt", ".md");

    public static boolean isSupportedExtension(String filename) {
        if (filename == null) return false;
        String lower = filename.toLowerCase();
        return SUPPORTED_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private void loadRegistry() throws IOException {
        if (Files.exists(registryPath)) {
            Map<String, DocRegistryEntry> loaded = mapper.readValue(registryPath.toFile(),
                    new TypeReference<>() {});
            registry.putAll(loaded);
        }
    }

    private void saveRegistry() throws IOException {
        mapper.writeValue(registryPath.toFile(), registry);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Registry entry (persisted as JSON)
    // ──────────────────────────────────────────────────────────────────────

    public record DocRegistryEntry(
            String sha256,
            String version,
            String indexedAt,
            int chunks,
            List<String> springDocIds,  // Spring AI document IDs for efficient deletion
            List<String> errors
    ) {}
}
