package com.example.ragagent.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA — CuratedQaRepository (§10.10 in PLAN.md)
 *
 * Covers:
 *  - upsertActive: 신규 insert
 *  - upsertActive: 같은 source_turn_id 재호출 시 중복 insert 없이 재활성화(내용 갱신)
 *  - deactivate: status=inactive 전환, id/스냅샷은 보존
 *  - deactivate: 존재하지 않는 turn → no-op
 *  - findBySourceTurnId / findById: 없는 경우 empty
 */
class CuratedQaRepositoryTest {

    private Path dbFile;
    private CuratedQaRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("rag-test-curated-", ".db");
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:sqlite:" + dbFile);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        repo = new CuratedQaRepository(jdbc);
        repo.init();
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(dbFile);
    }

    @Test
    @DisplayName("upsertActive — 신규 turn → insert 후 active 상태로 조회된다")
    void upsertActive_insertsNewRow() {
        long id = repo.upsertActive(1L, "u1", "t1", "질문", "답변", "latest");

        Optional<CuratedQaRepository.CuratedQa> row = repo.findById(id);
        assertThat(row).isPresent();
        assertThat(row.get().sourceTurnId()).isEqualTo(1L);
        assertThat(row.get().sourceUserId()).isEqualTo("u1");
        assertThat(row.get().sourceThreadId()).isEqualTo("t1");
        assertThat(row.get().question()).isEqualTo("질문");
        assertThat(row.get().answer()).isEqualTo("답변");
        assertThat(row.get().status()).isEqualTo("active");
        assertThat(row.get().sourceDocVersion()).isEqualTo("latest");
    }

    @Test
    @DisplayName("upsertActive — 같은 turn 재호출 시 중복 insert 없이 재활성화·내용 갱신된다")
    void upsertActive_reactivatesExistingRowInsteadOfDuplicating() {
        long firstId = repo.upsertActive(1L, "u1", "t1", "원래 질문", "원래 답변", "v1");
        repo.deactivate(1L);
        assertThat(repo.findById(firstId).orElseThrow().status()).isEqualTo("inactive");

        long secondId = repo.upsertActive(1L, "u1", "t1", "수정된 질문", "수정된 답변", "v2");

        assertThat(secondId).isEqualTo(firstId); // same row reused, not a new one
        CuratedQaRepository.CuratedQa row = repo.findById(firstId).orElseThrow();
        assertThat(row.status()).isEqualTo("active");
        assertThat(row.question()).isEqualTo("수정된 질문");
        assertThat(row.answer()).isEqualTo("수정된 답변");
        assertThat(row.sourceDocVersion()).isEqualTo("v2");
    }

    @Test
    @DisplayName("deactivate — status=inactive로 전환하고 스냅샷은 그대로 보존한다")
    void deactivate_flipsStatusButKeepsSnapshot() {
        repo.upsertActive(1L, "u1", "t1", "질문", "답변", "latest");

        repo.deactivate(1L);

        CuratedQaRepository.CuratedQa row = repo.findBySourceTurnId(1L).orElseThrow();
        assertThat(row.status()).isEqualTo("inactive");
        assertThat(row.question()).isEqualTo("질문");
        assertThat(row.answer()).isEqualTo("답변");
    }

    @Test
    @DisplayName("deactivate — 존재하지 않는 turn → no-op (예외 없음)")
    void deactivate_nonExistentTurn_isNoOp() {
        repo.deactivate(999L);
        assertThat(repo.findBySourceTurnId(999L)).isEmpty();
    }

    @Test
    @DisplayName("findBySourceTurnId / findById — 없는 경우 empty")
    void find_returnsEmptyWhenMissing() {
        assertThat(repo.findBySourceTurnId(42L)).isEmpty();
        assertThat(repo.findById(42L)).isEmpty();
    }
}
