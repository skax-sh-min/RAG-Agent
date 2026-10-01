package com.example.ragagent.repository;

import com.example.ragagent.SqliteTestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 답변 뒤에 다듬은 질문({@code clarified_question}, V6)이 재사용 쿼리에 실리는가 — 진짜 SQLite 에
 * 앱과 같은 마이그레이션을 적용한 DB 로 본다({@code QuestionReuseServiceTest} 는 리포지토리를 목킹하므로
 * SQL 을 한 글자도 실행하지 않는다).
 */
class QuestionReuseClarifiedQuestionTest {

    private Path dbFile;
    private JdbcTemplate jdbc;
    private QuestionReuseRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("rag-test-reuse-clarified-", ".db");
        jdbc = SqliteTestDatabase.open(dbFile);
        SqliteTestDatabase.createSearchIndexTables(jdbc);
        repo = new QuestionReuseRepository(jdbc, jdbc);
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(dbFile);
    }

    private void turn(long id, String question, String clarified, Long reusedFrom) {
        jdbc.update("INSERT INTO conversation_turns "
                    + "(id, user_id, thread_id, question, answer, created_at, response_mode, direct_mode, "
                    + " clarified_question, reused_from_turn_id) "
                    + "VALUES (?, 'u1', 't1', ?, '답변 본문', '2026-10-01', 'N', 0, ?, ?)",
                id, question, clarified, reusedFrom);
        jdbc.update("INSERT INTO turn_source_ref (turn_id, user_id, thread_id, chunk_id, chunk_hash, status) "
                    + "VALUES (?, 'u1', 't1', ?, 'h1', 'active')", id, "chunk-" + id);
    }

    @Test
    @DisplayName("다듬은 질문에만 있는 낱말로도 찾고, 후보가 그 문장을 함께 싣는다")
    void matchesWordsThatOnlyTheClarifiedQuestionHas() {
        turn(1L, "그거 어떻게 설정해?", "MCI 연동 타임아웃은 어떻게 설정하나요?", null);

        List<QuestionReuseRepository.CandidateTurn> found =
                repo.findSuggestionCandidates(List.of("타임아웃"), false, "u1", null, 10);

        assertThat(found).singleElement().satisfies(c -> {
            assertThat(c.question()).isEqualTo("그거 어떻게 설정해?");
            assertThat(c.clarifiedQuestion()).isEqualTo("MCI 연동 타임아웃은 어떻게 설정하나요?");
            assertThat(c.displayQuestion()).isEqualTo("MCI 연동 타임아웃은 어떻게 설정하나요?");
        });
    }

    @Test
    @DisplayName("원문의 낱말로도 여전히 찾는다 — 매칭은 원문과 다듬은 질문을 함께 본다")
    void stillMatchesTheOriginalWording() {
        turn(1L, "디비 접속 설정 알려줘", "데이터베이스 접속 설정 방법은?", null);

        assertThat(repo.findSuggestionCandidates(List.of("디비"), false, "u1", null, 10)).hasSize(1);
        assertThat(repo.findSuggestionCandidates(List.of("데이터베이스"), false, "u1", null, 10)).hasSize(1);
    }

    @Test
    @DisplayName("다듬은 질문이 없는 옛 재사용 턴은 원본 턴의 다듬은 질문으로 떨어진다")
    void anOldReuseTurnFallsBackToItsSourceTurnsClarifiedQuestion() {
        turn(1L, "그거 어떻게 설정해?", "MCI 연동 타임아웃은 어떻게 설정하나요?", null);
        turn(2L, "그거 어떻게 설정해?", null, 1L);   // 이 기능 이전에 원문을 그대로 복사한 재사용 턴

        QuestionReuseRepository.CandidateTurn reused = repo.findTurnForReuse(2L, false, "u1");

        assertThat(reused).isNotNull();
        assertThat(reused.displayQuestion()).isEqualTo("MCI 연동 타임아웃은 어떻게 설정하나요?");
    }

    @Test
    @DisplayName("다듬은 질문이 없는 턴은 원문을 보여준다")
    void withoutAClarifiedQuestionTheOriginalIsShown() {
        turn(1L, "sqlite 연결 설정 방법", null, null);

        assertThat(repo.findTurnForReuse(1L, false, "u1").displayQuestion()).isEqualTo("sqlite 연결 설정 방법");
    }
}
