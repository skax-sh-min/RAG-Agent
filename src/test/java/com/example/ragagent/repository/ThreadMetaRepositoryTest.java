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
 *  - save() UPSERT 가 routing_mode/tags 갱신 누락하는지 회귀 검사
 *  - updateTags() 단일 갱신
 */
class ThreadMetaRepositoryTest {

    private static final String UID = "test-user";

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
    @DisplayName("save → save 두 번째 호출에서 routing_mode 가 갱신되어야 함")
    void upsertShouldUpdateRoutingMode() {
        String now = ThreadMetaRepository.now();
        repo.save(new ThreadMeta("t1", UID, "first",  "v1", now, now, "COST_FIRST", ""));
        repo.save(new ThreadMeta("t1", UID, "second", "v1", now, now, "QUALITY_FIRST", ""));

        var meta = repo.findById(UID, "t1").orElseThrow();
        assertThat(meta.title()).isEqualTo("second");
        assertThat(meta.routingMode())
                .as("두 번째 save 호출의 routing_mode 가 반영되어야 함")
                .isEqualTo("QUALITY_FIRST");
    }

    @Test
    @DisplayName("findAllRecent 가 updated_at 내림차순")
    void findAllRecentOrder() {
        repo.save(new ThreadMeta("old", UID, "t", "v", "2025-01-01 00:00:00", "2025-01-01 00:00:00", "COST_FIRST", ""));
        repo.save(new ThreadMeta("new", UID, "t", "v", "2026-01-01 00:00:00", "2026-01-01 00:00:00", "COST_FIRST", ""));
        var all = repo.findAllRecent(UID, 10);
        assertThat(all.get(0).threadId()).isEqualTo("new");
    }

    @Test
    @DisplayName("updateRoutingMode 단일 갱신")
    void updateRoutingMode() {
        String now = ThreadMetaRepository.now();
        repo.save(new ThreadMeta("t1", UID, "t", "v", now, now, "COST_FIRST", ""));
        repo.updateRoutingMode(UID, "t1", "DUAL");
        assertThat(repo.findById(UID, "t1").orElseThrow().routingMode()).isEqualTo("DUAL");
    }

    @Test
    @DisplayName("updateTags — 마지막 전송 시점의 태그 선택으로 덮어씀")
    void updateTags_overwritesWithLatestSelection() {
        String now = ThreadMetaRepository.now();
        repo.save(new ThreadMeta("t1", UID, "t", "v", now, now, "COST_FIRST", "billing"));
        repo.updateTags(UID, "t1", "policy,onboarding");
        assertThat(repo.findById(UID, "t1").orElseThrow().tags()).isEqualTo("policy,onboarding");
    }

    @Test
    @DisplayName("save() UPSERT가 두 번째 호출에서 tags도 갱신해야 함")
    void upsertShouldUpdateTags() {
        String now = ThreadMetaRepository.now();
        repo.save(new ThreadMeta("t1", UID, "first", "v1", now, now, "COST_FIRST", "billing"));
        repo.save(new ThreadMeta("t1", UID, "second", "v1", now, now, "COST_FIRST", "policy"));

        assertThat(repo.findById(UID, "t1").orElseThrow().tags()).isEqualTo("policy");
    }
}
