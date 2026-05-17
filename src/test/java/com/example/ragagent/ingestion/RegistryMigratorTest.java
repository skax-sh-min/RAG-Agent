package com.example.ragagent.ingestion;

import com.example.ragagent.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for RegistryMigrator — JSON → SQLite 1회 임포트 검증.
 */
class RegistryMigratorTest {

    @TempDir
    Path tmpDir;

    private DocRegistry docRegistry;
    private AppProperties props;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite:" + tmpDir.resolve("test.db"));
        docRegistry = new DocRegistry(new JdbcTemplate(ds));
        docRegistry.init();

        props = mock(AppProperties.class);
        when(props.dataDir()).thenReturn(tmpDir.toString());
    }

    private DocRegistry.DocRegistryEntry entry(String sha, String version) {
        return new DocRegistry.DocRegistryEntry(sha, version, "2026-01-01T00:00:00Z", 3,
                List.of("id1", "id2"), List.of());
    }

    @Test
    @DisplayName("JSON 파일 존재 시 → SQLite 임포트 + .migrated 백업")
    void migrate_imports_json_and_renames_file() throws Exception {
        // Arrange: doc_registry.json 작성
        Map<String, DocRegistry.DocRegistryEntry> entries = Map.of(
                "report.pdf_abc12345", entry("sha1", "v1"),
                "guide.txt_def67890", entry("sha2", "latest")
        );
        Path json = tmpDir.resolve("doc_registry.json");
        new ObjectMapper().writeValue(json.toFile(), entries);

        // Act
        RegistryMigrator migrator = new RegistryMigrator(docRegistry, props);
        migrator.migrateIfNeeded();

        // Assert: 두 엔트리 임포트됨
        assertThat(docRegistry.findByDocId("report.pdf_abc12345", "anonymous")).isPresent();
        assertThat(docRegistry.findByDocId("guide.txt_def67890", "anonymous")).isPresent();

        // JSON 파일 → .migrated로 변경됨
        assertThat(json).doesNotExist();
        assertThat(tmpDir.resolve("doc_registry.json.migrated")).exists();
    }

    @Test
    @DisplayName(".migrated 파일 존재 시 → skip (idempotent)")
    void migrate_skips_if_migrated_exists() throws Exception {
        // Arrange: JSON + .migrated 모두 존재
        Path json     = tmpDir.resolve("doc_registry.json");
        Path migrated = tmpDir.resolve("doc_registry.json.migrated");
        new ObjectMapper().writeValue(json.toFile(),
                Map.of("old.pdf_aaaaaaaa", entry("sha-old", "v1")));
        Files.createFile(migrated);

        // Act
        RegistryMigrator migrator = new RegistryMigrator(docRegistry, props);
        migrator.migrateIfNeeded();

        // Assert: JSON 파일 내용은 SQLite에 없음 (skip됨)
        assertThat(docRegistry.findByDocId("old.pdf_aaaaaaaa", "anonymous")).isEmpty();
        // 원본 JSON 파일은 그대로
        assertThat(json).exists();
    }

    @Test
    @DisplayName("JSON 파일 없으면 → 아무것도 하지 않음")
    void migrate_does_nothing_if_no_json() throws Exception {
        // Act: JSON 없음
        RegistryMigrator migrator = new RegistryMigrator(docRegistry, props);
        migrator.migrateIfNeeded();

        // Assert: .migrated 없고 SQLite 비어 있음
        assertThat(tmpDir.resolve("doc_registry.json.migrated")).doesNotExist();
        assertThat(docRegistry.docIds("anonymous")).isEmpty();
    }
}
