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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA — §6.25: {@code findActiveByThread}, the row set a whole-conversation delete retracts.
 *
 * <p>Every assertion here is about something the query must <b>not</b> return. The happy path is
 * trivial; the failure modes are not, and each one is a way for a conversation delete to reach
 * something it doesn't own:
 *
 * <ul>
 *   <li>manual (청크 추가) rows — a submission is a 전부/전무 unit owned by
 *       {@code forceRemoveBySubmission}; deleting a chat thread must never take part of one down</li>
 *   <li>another user's rows, and other threads' rows — the scoping every delete path in the app has</li>
 *   <li>already-inactive rows — re-deleting them would re-issue vector deletes for nothing</li>
 * </ul>
 */
class CuratedQaThreadScopeTest {

    private Path dbFile;
    private JdbcTemplate jdbc;
    private CuratedQaRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("rag-test-curated-thread-", ".db");
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:sqlite:" + dbFile);
        jdbc = new JdbcTemplate(ds);
        repo = new CuratedQaRepository(jdbc);
        repo.init();
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(dbFile);
    }

    @Test
    @DisplayName("findActiveByThread — 그 대화의 활성 좋아요 행만, 생성 순서로 돌려준다")
    void returnsOnlyActiveLikeRowsOfThatThread() {
        long a = repo.upsertActive(1L, "u1", "t1", "질문 A", "답변 A", "v1", null);
        long b = repo.upsertActive(2L, "u1", "t1", "질문 B", "답변 B", "v1", null);

        List<CuratedQaRepository.CuratedQa> rows = repo.findActiveByThread("u1", "t1");

        assertThat(rows).extracting(CuratedQaRepository.CuratedQa::id).containsExactly(a, b);
    }

    @Test
    @DisplayName("findActiveByThread — 다른 대화·다른 사용자의 행은 제외한다")
    void excludesOtherThreadsAndOtherUsers() {
        long mine = repo.upsertActive(1L, "u1", "t1", "내 질문", "내 답변", "v1", null);
        repo.upsertActive(2L, "u1", "t2", "다른 대화", "답변", "v1", null);
        repo.upsertActive(3L, "u2", "t1", "다른 사용자", "답변", "v1", null);

        assertThat(repo.findActiveByThread("u1", "t1"))
                .extracting(CuratedQaRepository.CuratedQa::id)
                .containsExactly(mine);
    }

    @Test
    @DisplayName("findActiveByThread — 이미 비활성인 행은 제외한다(중복 회수 방지)")
    void excludesInactiveRows() {
        long active = repo.upsertActive(1L, "u1", "t1", "살아있음", "답변", "v1", null);
        long gone   = repo.upsertActive(2L, "u1", "t1", "이미 내려감", "답변", "v1", null);
        repo.deactivateById(gone);

        assertThat(repo.findActiveByThread("u1", "t1"))
                .extracting(CuratedQaRepository.CuratedQa::id)
                .containsExactly(active);
    }

    @Test
    @DisplayName("findActiveByThread — 제안(manual) 행은 source_thread_id가 비어 있어도 절대 포함되지 않는다")
    void neverIncludesManualSubmissionRows() {
        repo.insertManual(1L, "u1", "제안 제목", "제안 본문", null);
        long liked = repo.upsertActive(1L, "u1", "t1", "질문", "답변", "v1", null);

        // 대화 id 로도, 수동 행이 실제로 갖는 빈 문자열로도 잡히지 않아야 한다.
        assertThat(repo.findActiveByThread("u1", "t1"))
                .extracting(CuratedQaRepository.CuratedQa::id)
                .containsExactly(liked);
        assertThat(repo.findActiveByThread("u1", "")).isEmpty();
    }

    @Test
    @DisplayName("findActiveByThread — manual 행이 나중에 진짜 thread id를 갖게 되더라도 제외된다")
    void manualRowStaysExcludedEvenWithARealThreadId() {
        long manual = repo.insertManual(1L, "u1", "제안 제목", "제안 본문", null);
        // 이 UPDATE 는 현재 코드에 없는 가상의 미래 변경을 흉내 낸다 — 쿼리가 source_thread_id 가
        // 아니라 source_turn_id IS NOT NULL 로 걸러낸다는 것이 이 테스트의 요지다.
        jdbc.update("UPDATE curated_qa SET source_thread_id = 't1' WHERE id = ?", manual);

        assertThat(repo.findActiveByThread("u1", "t1")).isEmpty();
    }
}
