package com.example.ragagent.repository;

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
 * QA — SqliteMemoryRepository
 *
 * Covers:
 *  - 신규 thread 빈 history
 *  - 50 turn 초과 시 FETCH_LIMIT 적용
 *  - B-11 단일 turn 이 maxChars 초과할 때의 동작
 */
class SqliteMemoryRepositoryTest {

    private Path dbFile;
    private SqliteMemoryRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("rag-test-", ".db");
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:sqlite:" + dbFile);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        repo = new SqliteMemoryRepository(jdbc);
        repo.init();
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(dbFile);
    }

    @Test
    @DisplayName("신규 thread → getHistory 빈 문자열")
    void emptyHistoryForNewThread() {
        assertThat(repo.getHistory("brand-new", 4000)).isEmpty();
    }

    @Test
    @DisplayName("addTurn 후 getTurns 가 시간순으로 반환")
    void addAndRetrieveTurns() {
        repo.addTurn("t1", "Q1", "A1");
        repo.addTurn("t1", "Q2", "A2");
        var turns = repo.getTurns("t1");
        assertThat(turns).hasSize(2);
        assertThat(turns.get(0).question()).isEqualTo("Q1");
        assertThat(turns.get(1).question()).isEqualTo("Q2");
    }

    @Test
    @DisplayName("50 turn 초과 시 최근 50개만 반영 (FETCH_LIMIT)")
    void respectsFetchLimit() {
        for (int i = 0; i < 60; i++) {
            repo.addTurn("t1", "Q" + i, "A" + i);
        }
        String history = repo.getHistory("t1", 1_000_000);
        // 가장 오래된 Q0~Q9 (10개) 는 잘려나가야 함
        assertThat(history).doesNotContain("Q: Q0\n");
        assertThat(history).contains("Q: Q59");
    }

    @Test
    @DisplayName("clearHistory 후 getTurns 빈 리스트")
    void clearHistory() {
        repo.addTurn("t1", "Q", "A");
        repo.clearHistory("t1");
        assertThat(repo.getTurns("t1")).isEmpty();
    }

    @Test
    @DisplayName("단일 turn 이 maxChars 초과해도 잘라서라도 컨텍스트 제공 (B-11)")
    void singleTurnLargerThanBudget() {
        String huge = "x".repeat(10_000);
        repo.addTurn("t1", huge, huge);
        // 현재 구현: 첫 entry 가 budget 초과 → 즉시 break → 빈 문자열
        String result = repo.getHistory("t1", 1_000);
        assertThat(result)
                .as("단일 거대 turn 이라도 잘라서라도 일부 컨텍스트 제공이 바람직 (B-11)")
                .isNotEmpty();
    }
}
