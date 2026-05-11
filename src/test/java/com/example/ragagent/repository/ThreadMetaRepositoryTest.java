package com.example.ragagent.repository;

import com.example.ragagent.model.ThreadMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA — ThreadMetaRepository
 *
 * Covers:
 *  - B-04 save() UPSERT 가 routing_mode 갱신 누락하는지 회귀 검사
 */
class ThreadMetaRepositoryTest {

    private Path dbFile;
    private ThreadMetaRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("rag-meta-", ".db");
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + dbFile);
        var jdbc = new JdbcTemplate(ds);
        repo = new ThreadMetaRepository(jdbc);
        repo.init();
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(dbFile);
    }

    @Test
    @DisplayName("save → save 두 번째 호출에서 routing_mode 가 갱신되어야 함 (B-04)")
    void upsertShouldUpdateRoutingMode() {
        String now = ThreadMetaRepository.now();
        repo.save(new ThreadMeta("t1", "first", "v1", now, now, "COST_FIRST"));
        repo.save(new ThreadMeta("t1", "second", "v1", now, now, "QUALITY_FIRST"));

        var meta = repo.findById("t1").orElseThrow();
        assertThat(meta.title()).isEqualTo("second");
        assertThat(meta.routingMode())
                .as("두 번째 save 호출의 routing_mode 가 반영되어야 함 (B-04)")
                .isEqualTo("QUALITY_FIRST");
    }

    @Test
    @DisplayName("findAllRecent 가 updated_at 내림차순")
    void findAllRecentOrder() {
        repo.save(new ThreadMeta("old", "t", "v", "2025-01-01 00:00:00", "2025-01-01 00:00:00", "COST_FIRST"));
        repo.save(new ThreadMeta("new", "t", "v", "2026-01-01 00:00:00", "2026-01-01 00:00:00", "COST_FIRST"));
        var all = repo.findAllRecent(10);
        assertThat(all.get(0).threadId()).isEqualTo("new");
    }

    @Test
    @DisplayName("updateRoutingMode 단일 갱신")
    void updateRoutingMode() {
        String now = ThreadMetaRepository.now();
        repo.save(new ThreadMeta("t1", "t", "v", now, now, "COST_FIRST"));
        repo.updateRoutingMode("t1", "DUAL");
        assertThat(repo.findById("t1").orElseThrow().routingMode()).isEqualTo("DUAL");
    }
}
