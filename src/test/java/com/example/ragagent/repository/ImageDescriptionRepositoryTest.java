package com.example.ragagent.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA — ImageDescriptionRepository (EDIT.md #1)
 *
 * Vision description cache: findAll() batch lookup + save() upsert. No test previously
 * existed for this class at all.
 */
class ImageDescriptionRepositoryTest {

    private Path dbFile;
    private ImageDescriptionRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("rag-image-desc-", ".db");
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + dbFile);
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE image_descriptions (
                    image_path  TEXT PRIMARY KEY,
                    description TEXT,
                    image_type  TEXT,
                    provider    TEXT
                )
                """);
        repo = new ImageDescriptionRepository(jdbc);
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(dbFile);
    }

    @Test
    @DisplayName("findAll — 빈 목록이면 쿼리 없이 빈 Map 반환")
    void findAll_emptyInput_returnsEmptyMapWithoutQuerying() {
        assertThat(repo.findAll(List.of())).isEmpty();
    }

    @Test
    @DisplayName("findAll — 저장된 경로만 반환, 없는 경로는 결과에서 제외")
    void findAll_returnsOnlyStoredPaths() {
        repo.save("/img/a.png", "설명 A", "diagram", "local");
        repo.save("/img/b.png", "설명 B", "photo", "local");

        Map<String, String> result = repo.findAll(List.of("/img/a.png", "/img/b.png", "/img/missing.png"));

        assertThat(result).containsExactlyInAnyOrderEntriesOf(
                Map.of("/img/a.png", "설명 A", "/img/b.png", "설명 B"));
    }

    @Test
    @DisplayName("save — 같은 경로로 다시 저장하면 UPSERT(덮어쓰기), 행 1개 유지")
    void save_sameKeyTwice_upsertsInsteadOfDuplicating() {
        repo.save("/img/a.png", "첫 설명", "diagram", "local");
        repo.save("/img/a.png", "갱신된 설명", "chart", "gemini");

        Map<String, String> result = repo.findAll(List.of("/img/a.png"));

        assertThat(result).containsExactly(Map.entry("/img/a.png", "갱신된 설명"));
    }
}
