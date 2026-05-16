package com.example.ragagent.ingestion;

import com.example.ragagent.config.AppProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of indexed documents — persisted as JSON.
 * Thread-safe reads; callers must serialise writes at the application level
 * (RagService guarantees single-writer via its own coordination).
 */
@Component
public class DocRegistry {

    private static final Logger log = LoggerFactory.getLogger(DocRegistry.class);
    private static final String REGISTRY_FILE = "doc_registry.json";

    private final ConcurrentHashMap<String, DocRegistryEntry> data = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final Path registryPath;

    public DocRegistry(AppProperties props) {
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.registryPath = Path.of(props.dataDir()).resolve(REGISTRY_FILE);
    }

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(registryPath.getParent());
        load();
    }

    // ── CRUD ──────────────────────────────────────────────────────────────

    public void put(String docId, DocRegistryEntry entry) {
        data.put(docId, entry);
    }

    public Optional<DocRegistryEntry> findByDocId(String docId) {
        return Optional.ofNullable(data.get(docId));
    }

    public void remove(String docId) {
        data.remove(docId);
    }

    public Set<String> docIds() {
        return data.keySet();
    }

    public Collection<DocRegistryEntry> values() {
        return data.values();
    }

    public Set<Map.Entry<String, DocRegistryEntry>> entries() {
        return data.entrySet();
    }

    // ── Query helpers ──────────────────────────────────────────────────────

    public boolean existsBySha256AndVersion(String sha256, String version) {
        return data.values().stream()
                .anyMatch(e -> e.sha256().equals(sha256) && e.version().equals(version));
    }

    public Optional<String> findStaleDocId(String filename, String newDocId, String version) {
        return data.entrySet().stream()
                .filter(e -> e.getKey().startsWith(filename + "_")
                          && !e.getKey().equals(newDocId)
                          && version.equals(e.getValue().version()))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    // ── Persistence ────────────────────────────────────────────────────────

    public void save() throws IOException {
        mapper.writeValue(registryPath.toFile(), data);
    }

    public void saveQuiet() {
        try {
            save();
        } catch (IOException e) {
            log.warn("[REGISTRY] 저장 실패: {}", e.getMessage());
        }
    }

    // ── Static utility ─────────────────────────────────────────────────────

    public static String filenameFromDocId(String docId) {
        int idx = docId.lastIndexOf('_');
        return idx > 0 ? docId.substring(0, idx) : docId;
    }

    // ── Private ────────────────────────────────────────────────────────────

    private void load() throws IOException {
        if (Files.exists(registryPath)) {
            Map<String, DocRegistryEntry> loaded = mapper.readValue(registryPath.toFile(),
                    new TypeReference<>() {});
            data.putAll(loaded);
            log.debug("[REGISTRY] 로드 완료: {}개", loaded.size());
        }
    }

    // ── Registry entry (persisted as JSON) ─────────────────────────────────

    public record DocRegistryEntry(
            String sha256,
            String version,
            String indexedAt,
            int chunks,
            List<String> springDocIds,
            List<String> errors
    ) {}
}
