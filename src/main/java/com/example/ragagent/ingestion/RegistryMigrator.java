package com.example.ragagent.ingestion;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.ragagent.config.AppProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * One-time migration: imports doc_registry.json into the SQLite doc_registry table.
 * After a successful run the JSON file is renamed to doc_registry.json.migrated so the
 * migrator is idempotent across restarts.
 */
@Component
public class RegistryMigrator {

    private static final Logger log = LoggerFactory.getLogger(RegistryMigrator.class);
    private static final String JSON_FILE    = "doc_registry.json";
    private static final String MIGRATED_FILE = "doc_registry.json.migrated";

    private final DocRegistry docRegistry;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path dataDir;

    public RegistryMigrator(DocRegistry docRegistry, AppProperties props) {
        this.docRegistry = docRegistry;
        this.dataDir = Path.of(props.dataDir());
    }

    @PostConstruct
    public void migrateIfNeeded() throws IOException {
        Path json     = dataDir.resolve(JSON_FILE);
        Path migrated = dataDir.resolve(MIGRATED_FILE);

        if (!Files.exists(json)) return;
        if (Files.exists(migrated)) return;

        Map<String, DocRegistry.DocRegistryEntry> entries =
                mapper.readValue(json.toFile(), new TypeReference<>() {});

        int imported = 0;
        for (Map.Entry<String, DocRegistry.DocRegistryEntry> e : entries.entrySet()) {
            docRegistry.put(e.getKey(), "anonymous", e.getValue());
            imported++;
        }

        if (imported != entries.size()) {
            throw new IllegalStateException(
                    "마이그레이션 부분 실패: " + imported + "/" + entries.size());
        }

        Files.move(json, migrated);
        log.info("[REGISTRY-MIGRATE] doc_registry.json → SQLite 완료: {}개", imported);
    }
}
