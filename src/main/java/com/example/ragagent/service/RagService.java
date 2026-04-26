package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.model.DocumentInfo;
import com.example.ragagent.model.SyncResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
import java.util.concurrent.ConcurrentHashMap;
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

    private static final String REGISTRY_FILE = "doc_registry.json";
    private static final Pattern SAFE_VERSION = Pattern.compile("^[a-zA-Z0-9._\\-]{1,50}$");

    private final AppProperties props;
    private final DocumentLoaderService loaderService;
    private final VectorStoreRegistry vectorStoreRegistry;
    private final ObjectMapper mapper;
    private final KeywordMetadataEnricher keywordEnricher;

    // doc_id -> DocRegistryEntry (persisted to JSON)
    private final ConcurrentHashMap<String, DocRegistryEntry> registry = new ConcurrentHashMap<>();

    private Path dataDir;
    private Path documentsDir;
    private Path registryPath;

    public RagService(AppProperties props, DocumentLoaderService loaderService,
                      VectorStoreRegistry vectorStoreRegistry, ChatModel chatModel) {
        this.props = props;
        this.loaderService = loaderService;
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
        String filename = filePath.getFileName().toString();
        String sha256 = computeSha256(filePath);
        String docId = filename + "_" + sha256.substring(0, 8);
        String docType = inferDocType(filename);

        // Load & split
        List<Document> rawDocs = loaderService.load(filePath);
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
        List<String> indexed = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<String> deleted = new ArrayList<>();

        // Collect current files on disk
        Map<String, Path> filesOnDisk = new HashMap<>();
        if (Files.exists(documentsDir)) {
            try (Stream<Path> stream = Files.list(documentsDir)) {
                stream.filter(p -> isSupportedExtension(p.getFileName().toString()))
                      .forEach(p -> filesOnDisk.put(p.getFileName().toString(), p));
            }
        }

        // Detect new / changed files
        for (Map.Entry<String, Path> entry : filesOnDisk.entrySet()) {
            String filename = entry.getKey();
            Path filePath = entry.getValue();
            String sha256 = computeSha256(filePath);
            String docId = filename + "_" + sha256.substring(0, 8);

            boolean alreadyIndexed = registry.values().stream()
                    .anyMatch(r -> r.sha256().equals(sha256) && r.version().equals(version));

            if (!alreadyIndexed) {
                // Remove stale entry for same filename+version (content changed → new sha256)
                String staleDocId = registry.keySet().stream()
                        .filter(k -> k.startsWith(filename + "_") && !k.equals(docId)
                                  && version.equals(registry.get(k).version()))
                        .findFirst().orElse(null);
                boolean isUpdate = staleDocId != null;
                if (isUpdate) {
                    deleteByDocId(staleDocId, version);
                    registry.remove(staleDocId);
                }

                indexDocument(filePath, version);
                if (isUpdate) updated.add(filename); else indexed.add(filename);
            }
        }

        // Detect deleted files
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

        if (!deleted.isEmpty()) saveRegistry();
        return new SyncResult(indexed, updated, deleted);
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

    private void deleteByDocId(String docId, String version) {
        DocRegistryEntry existing = registry.get(docId);
        if (existing == null || existing.springDocIds().isEmpty()) return;
        VectorStore store = vectorStoreRegistry.getStore(version);
        store.delete(existing.springDocIds());
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

    private boolean isSupportedExtension(String filename) {
        String lower = filename.toLowerCase();
        return lower.endsWith(".pdf") || lower.endsWith(".pptx")
                || lower.endsWith(".docx") || lower.endsWith(".txt") || lower.endsWith(".md");
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
