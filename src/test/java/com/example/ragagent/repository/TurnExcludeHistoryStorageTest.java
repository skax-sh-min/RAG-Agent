package com.example.ragagent.repository;

import com.example.ragagent.SqliteTestDatabase;
import com.example.ragagent.config.AppProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * §6.31 — 턴의 이력 제외 표시(V11 {@code exclude_history})가 저장되고 다시 읽히는가, 재사용이 원본의 값을 따르는가. 진짜 SQLite 에
 * 앱과 같은 마이그레이션을 적용한 DB 로 본다(서비스 테스트는 리포지토리를 목킹해 SQL 을 실행하지 않는다).
 */
class TurnExcludeHistoryStorageTest {

    private Path dbFile;
    private JdbcTemplate jdbc;
    private SqliteMemoryRepository memory;
    private QuestionReuseRepository reuse;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("rag-test-exclude-history-", ".db");
        jdbc = SqliteTestDatabase.open(dbFile);
        SqliteTestDatabase.createSearchIndexTables(jdbc);
        AppProperties props = mock(AppProperties.class);
        when(props.memorySafe()).thenReturn(new AppProperties.MemoryConfig(50));
        memory = new SqliteMemoryRepository(jdbc, props);
        reuse = new QuestionReuseRepository(jdbc, jdbc);
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(dbFile);
    }

    private long turn(Long reusedFrom) {
        long id = memory.addTurn("u1", "t1", "질문", reusedFrom == null ? "답변 본문" : "", "2026-10-26 00:00:00",
                0, 0, 0, "lm", 1, "N", "", false, reusedFrom);
        jdbc.update("INSERT INTO turn_source_ref (turn_id, user_id, thread_id, chunk_id, chunk_hash, status) "
                    + "VALUES (?, 'u1', 't1', ?, 'h1', 'active')", id, "chunk-" + id);
        return id;
    }

    @Test
    @DisplayName("기본값은 0 이고, 표시한 턴만 대화 기록에서 true 로 읽힌다")
    void markedTurnReadsBackAsExcluded() {
        long plain = turn(null);
        long excluded = turn(null);
        memory.markHistoryExcluded(excluded);

        assertThat(memory.getTurns("u1", "t1"))
                .extracting(MemoryRepository.Turn::id, MemoryRepository.Turn::excludeHistory)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(plain, false),
                        org.assertj.core.groups.Tuple.tuple(excluded, true));
        assertThat(memory.getTurn("u1", "t1", excluded)).get()
                .extracting(MemoryRepository.Turn::excludeHistory).isEqualTo(true);
    }

    @Test
    @DisplayName("재사용 후보는 답변을 만든 행(원본)의 값을 싣는다 — 모드·검색 축·태그와 같은 규칙")
    void reuseCandidateCarriesTheSourceTurnsValue() {
        long source = turn(null);
        memory.markHistoryExcluded(source);
        long reused = turn(source);   // 이 기능 이전처럼 값을 복사하지 않은 재사용 턴

        assertThat(reuse.findTurnForReuse(source).excludeHistory()).isTrue();
        assertThat(reuse.findTurnForReuse(reused).excludeHistory()).as("원본의 값으로 떨어진다").isTrue();
    }
}
