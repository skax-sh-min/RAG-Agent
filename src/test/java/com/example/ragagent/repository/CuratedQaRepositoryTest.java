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
 *  - embed_status: 신규 행 기본값 'ok', markEmbedFailed/markEmbedOk 전환, findFailedTurnIds 필터링
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

    @Test
    @DisplayName("§10.10 step④ — updateAnswer는 answer만 갱신하고 question/status는 보존한다")
    void updateAnswer_updatesAnswerOnlyKeepsRest() {
        long id = repo.upsertActive(1L, "u1", "t1", "질문", "원래 답변", "latest");

        repo.updateAnswer(id, "수정된 답변");

        CuratedQaRepository.CuratedQa row = repo.findById(id).orElseThrow();
        assertThat(row.answer()).isEqualTo("수정된 답변");
        assertThat(row.question()).isEqualTo("질문");
        assertThat(row.status()).isEqualTo("active");
    }

    @Test
    @DisplayName("§10.10 step④ — findAllActive는 active 항목만, 최신(id 역순)으로 반환한다")
    void findAllActive_returnsOnlyActiveNewestFirst() {
        long older = repo.upsertActive(1L, "u1", "t1", "질문1", "답변1", "latest");
        long newer = repo.upsertActive(2L, "u1", "t1", "질문2", "답변2", "latest");
        long deactivated = repo.upsertActive(3L, "u1", "t1", "질문3", "답변3", "latest");
        repo.deactivate(3L);

        List<CuratedQaRepository.CuratedQa> active = repo.findAllActive(50);

        List<Long> ids = active.stream().map(CuratedQaRepository.CuratedQa::id).toList();
        assertThat(ids).containsExactly(newer, older); // id DESC 타이브레이커로 결정적 순서
        assertThat(ids).doesNotContain(deactivated);
    }

    @Test
    @DisplayName("§10.10 step④ — findAllActive(offset, limit)로 페이지네이션한다")
    void findAllActive_paginated() {
        long first  = repo.upsertActive(1L, "u1", "t1", "질문1", "답변1", "latest");
        long second = repo.upsertActive(2L, "u1", "t1", "질문2", "답변2", "latest");
        long third  = repo.upsertActive(3L, "u1", "t1", "질문3", "답변3", "latest");

        List<Long> page1 = repo.findAllActive(0, 2).stream().map(CuratedQaRepository.CuratedQa::id).toList();
        List<Long> page2 = repo.findAllActive(2, 2).stream().map(CuratedQaRepository.CuratedQa::id).toList();

        assertThat(page1).containsExactly(third, second); // newest first
        assertThat(page2).containsExactly(first);
    }

    @Test
    @DisplayName("§10.10 embedding-fallback — 신규 행은 embed_status='ok'로 시작한다")
    void newRow_defaultsToEmbedStatusOk() {
        long id = repo.upsertActive(1L, "u1", "t1", "질문", "답변", "latest");

        assertThat(repo.findById(id).orElseThrow().embedStatus()).isEqualTo("ok");
    }

    @Test
    @DisplayName("§10.10 embedding-fallback — markEmbedFailed/markEmbedOk가 embed_status를 전환한다")
    void markEmbedFailedAndOk_flipEmbedStatus() {
        long id = repo.upsertActive(1L, "u1", "t1", "질문", "답변", "latest");

        repo.markEmbedFailed(id);
        assertThat(repo.findById(id).orElseThrow().embedStatus()).isEqualTo("failed");

        repo.markEmbedOk(id);
        assertThat(repo.findById(id).orElseThrow().embedStatus()).isEqualTo("ok");
    }

    @Test
    @DisplayName("§10.10 embedding-fallback — findFailedTurnIds는 active+failed인 turn만 반환한다")
    void findFailedTurnIds_returnsOnlyActiveAndFailed() {
        repo.upsertActive(1L, "u1", "t1", "질문1", "답변1", "latest"); // active, ok
        long id2 = repo.upsertActive(2L, "u1", "t1", "질문2", "답변2", "latest");
        repo.markEmbedFailed(id2); // active, failed
        long id3 = repo.upsertActive(3L, "u1", "t1", "질문3", "답변3", "latest");
        repo.markEmbedFailed(id3);
        repo.deactivate(3L); // inactive, failed — should NOT be reported

        assertThat(repo.findFailedTurnIds(List.of(1L, 2L, 3L, 999L))).containsExactly(2L);
    }

    @Test
    @DisplayName("§10.10 embedding-fallback — findFailedTurnIds는 빈 입력에 빈 Set을 반환한다")
    void findFailedTurnIds_emptyInput_returnsEmptySet() {
        assertThat(repo.findFailedTurnIds(List.of())).isEmpty();
        assertThat(repo.findFailedTurnIds(null)).isEmpty();
    }
}
