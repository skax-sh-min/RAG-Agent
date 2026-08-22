package com.example.ragagent.repository;

import com.example.ragagent.config.AppProperties;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA — SqliteMemoryRepository
 *
 * Covers:
 *  - 신규 thread 빈 history
 *  - 50 turn 초과 시 FETCH_LIMIT 적용
 *  - 단일 turn 이 maxChars 초과할 때의 동작
 */
class SqliteMemoryRepositoryTest {

    private static final String UID = "test-user";

    private Path dbFile;
    private SqliteMemoryRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("rag-test-", ".db");
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:sqlite:" + dbFile);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        AppProperties props = mock(AppProperties.class);
        when(props.memorySafe()).thenReturn(new AppProperties.MemoryConfig(50));
        repo = new SqliteMemoryRepository(jdbc, props);
        repo.init();
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(dbFile);
    }

    @Test
    @DisplayName("신규 thread → getHistory 빈 문자열")
    void emptyHistoryForNewThread() {
        assertThat(repo.getHistory(UID, "brand-new", 4000)).isEmpty();
    }

    @Test
    @DisplayName("addTurn 후 getTurns 가 시간순으로 반환")
    void addAndRetrieveTurns() {
        repo.addTurn(UID, "t1", "Q1", "A1", null, 0, 0, 0, null, 0, "M", null);
        repo.addTurn(UID, "t1", "Q2", "A2", null, 0, 0, 0, null, 0, "M", null);
        var turns = repo.getTurns(UID, "t1");
        assertThat(turns).hasSize(2);
        assertThat(turns.get(0).question()).isEqualTo("Q1");
        assertThat(turns.get(1).question()).isEqualTo("Q2");
    }

    @Test
    @DisplayName("addTurn 의 response_mode 가 getTurn(s)/getRecentTurns 모두에서 그대로 되돌아온다")
    void responseModeRoundTrips() {
        long id = repo.addTurn(UID, "t1", "Q", "A", null, 0, 0, 0, null, 0, "L", null);

        assertThat(repo.getTurns(UID, "t1").get(0).responseMode()).isEqualTo("L");
        assertThat(repo.getRecentTurns(UID, "t1").get(0).responseMode()).isEqualTo("L");
        assertThat(repo.getTurn(UID, "t1", id)).isPresent()
                .get().extracting(MemoryRepository.Turn::responseMode).isEqualTo("L");
    }

    @Test
    @DisplayName("50 turn 초과 시 최근 50개만 반영 (FETCH_LIMIT)")
    void respectsFetchLimit() {
        for (int i = 0; i < 60; i++) {
            repo.addTurn(UID, "t1", "Q" + i, "A" + i, null, 0, 0, 0, null, 0, "M", null);
        }
        String history = repo.getHistory(UID, "t1", 1_000_000);
        // 가장 오래된 Q0~Q9 (10개) 는 잘려나가야 함
        assertThat(history).doesNotContain("Q: Q0\n");
        assertThat(history).contains("Q: Q59");
    }

    @Test
    @DisplayName("getRecentTurns — 50 turn 초과 시 최근 50개만 시간순으로 반환 (FETCH_LIMIT)")
    void getRecentTurns_respectsFetchLimit() {
        for (int i = 0; i < 60; i++) {
            repo.addTurn(UID, "t1", "Q" + i, "A" + i, null, 0, 0, 0, null, 0, "M", null);
        }

        var turns = repo.getRecentTurns(UID, "t1");

        assertThat(turns).hasSize(50);
        assertThat(turns.get(0).question()).isEqualTo("Q10"); // 가장 오래된 Q0~Q9는 잘려나감
        assertThat(turns.get(turns.size() - 1).question()).isEqualTo("Q59"); // 최신 turn이 마지막(시간순)
    }

    @Test
    @DisplayName("getRecentTurns — FETCH_LIMIT 이하면 getTurns와 동일하게 전부 반환")
    void getRecentTurns_returnsAllWhenUnderLimit() {
        repo.addTurn(UID, "t1", "Q1", "A1", null, 0, 0, 0, null, 0, "M", null);
        repo.addTurn(UID, "t1", "Q2", "A2", null, 0, 0, 0, null, 0, "M", null);

        assertThat(repo.getRecentTurns(UID, "t1")).isEqualTo(repo.getTurns(UID, "t1"));
    }

    @Test
    @DisplayName("clearHistory 후 getTurns 빈 리스트")
    void clearHistory() {
        repo.addTurn(UID, "t1", "Q", "A", null, 0, 0, 0, null, 0, "M", null);
        repo.clearHistory(UID, "t1");
        assertThat(repo.getTurns(UID, "t1")).isEmpty();
    }

    @Test
    @DisplayName("단일 turn 이 maxChars 초과해도 잘라서라도 컨텍스트 제공")
    void singleTurnLargerThanBudget() {
        String huge = "x".repeat(10_000);
        repo.addTurn(UID, "t1", huge, huge, null, 0, 0, 0, null, 0, "M", null);
        // 현재 구현: 첫 entry 가 budget 초과 → 즉시 break → 빈 문자열
        String result = repo.getHistory(UID, "t1", 1_000);
        assertThat(result)
                .as("단일 거대 turn 이라도 잘라서라도 일부 컨텍스트 제공이 바람직")
                .isNotEmpty();
    }

    @Test
    @DisplayName("addTurn 은 생성된 turn id 를 반환한다")
    void addTurnReturnsGeneratedId() {
        long id1 = repo.addTurn(UID, "t1", "Q1", "A1", null, 0, 0, 0, null, 0, "M", null);
        long id2 = repo.addTurn(UID, "t1", "Q2", "A2", null, 0, 0, 0, null, 0, "M", null);
        assertThat(id1).isPositive();
        assertThat(id2).isGreaterThan(id1);
    }

    @Test
    @DisplayName("db-reuse turn 은 참조만 저장해도 조회 시 원본 answer 로 복원된다")
    void dbReuseTurnResolvesAnswerFromSourceTurn() {
        long sourceId = repo.addTurn(UID, "t1", "원본 질문", "원본 답변", null, 0, 0, 0, "local-a", 1, "M", null);
        long reusedId = repo.addTurn(UID, "t1", "재사용 질문", "", null, 0, 0, 0, "db-reuse", 0, "M", null, sourceId);

        var turns = repo.getTurns(UID, "t1");
        assertThat(turns).hasSize(2);
        assertThat(turns.get(1).id()).isEqualTo(reusedId);
        assertThat(turns.get(1).answer()).isEqualTo("원본 답변");

        assertThat(repo.getHistory(UID, "t1", 10_000)).contains("A: 원본 답변");
    }

    @Test
    @DisplayName("DISLIKE 로 표시된 turn 은 getHistory 컨텍스트에서 제외된다")
    void dislikedTurnExcludedFromHistory() {
        long keep = repo.addTurn(UID, "t1", "keep-question", "keep-answer", null, 0, 0, 0, null, 0, "M", null);
        long drop = repo.addTurn(UID, "t1", "drop-question", "drop-answer", null, 0, 0, 0, null, 0, "M", null);
        repo.updateFeedback(UID, "t1", drop, "DISLIKE");

        String history = repo.getHistory(UID, "t1", 1_000_000);

        assertThat(history).contains("keep-question");
        assertThat(history).doesNotContain("drop-question");
        assertThat(keep).isNotEqualTo(drop); // sanity
    }

    @Test
    @DisplayName("LIKE 로 표시된 turn 은 getHistory 컨텍스트에 그대로 남는다")
    void likedTurnStaysInHistory() {
        long id = repo.addTurn(UID, "t1", "liked-question", "liked-answer", null, 0, 0, 0, null, 0, "M", null);
        repo.updateFeedback(UID, "t1", id, "LIKE");

        assertThat(repo.getHistory(UID, "t1", 1_000_000)).contains("liked-question");
    }

    @Test
    @DisplayName("getFeedback — 존재하지 않는 turn/타 유저는 Optional.empty()")
    void getFeedbackOwnershipCheck() {
        long id = repo.addTurn(UID, "t1", "Q", "A", null, 0, 0, 0, null, 0, "M", null);

        assertThat(repo.getFeedback(UID, "t1", id)).isPresent();
        assertThat(repo.getFeedback(UID, "t1", id).get().feedback()).isNull();
        assertThat(repo.getFeedback("other-user", "t1", id)).isEmpty();
        assertThat(repo.getFeedback(UID, "t1", id + 999)).isEmpty();
    }

    @Test
    @DisplayName("updateFeedback 후 getFeedback 이 새 값을 반영한다")
    void updateFeedbackReflectedInGetFeedback() {
        long id = repo.addTurn(UID, "t1", "Q", "A", null, 0, 0, 0, null, 0, "M", null);
        repo.updateFeedback(UID, "t1", id, "DISLIKE");
        assertThat(repo.getFeedback(UID, "t1", id).get().feedback()).isEqualTo("DISLIKE");

        repo.updateFeedback(UID, "t1", id, null); // clear
        assertThat(repo.getFeedback(UID, "t1", id).get().feedback()).isNull();
    }

    // ── 검색 진단 수치 영속 (3단계) ────────────────────────────────────────────

    @Test
    @DisplayName("저장한 진단 수치가 최신순으로 조회되고, 수치 없는 턴은 목록에 끼지 않는다")
    void retrievalMetricsStoredAndListedNewestFirst() {
        long withMetrics1 = repo.addTurn(UID, "t1", "Q1", "A1", "2026-08-16 10:00:00", 0, 0, 0, "local", 0, "M", null);
        long noMetrics    = repo.addTurn(UID, "t1", "Q2", "A2", "2026-08-16 10:01:00", 0, 0, 0, "local", 0, "M", null);
        long withMetrics2 = repo.addTurn(UID, "t1", "Q3", "A3", "2026-08-16 10:02:00", 0, 0, 0, "local", 0, "S", null);

        repo.saveRetrievalMetrics(withMetrics1, "[{\"label\":\"a\"}]");
        repo.saveRetrievalMetrics(withMetrics2, "[{\"label\":\"b\"}]");

        assertThat(repo.countRetrievalMetrics()).isEqualTo(2);

        var rows = repo.findRecentRetrievalMetrics(0, 10);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).turnId()).isEqualTo(withMetrics2);   // 최신순
        assertThat(rows.get(0).responseMode()).isEqualTo("S");
        assertThat(rows.get(0).metricsJson()).isEqualTo("[{\"label\":\"b\"}]");
        assertThat(rows).noneMatch(r -> r.turnId() == noMetrics);
    }

    @Test
    @DisplayName("null/빈 JSON 저장은 no-op — 빈 행이 관리자 패널에 생기지 않는다")
    void blankMetricsAreNoOp() {
        long id = repo.addTurn(UID, "t1", "Q", "A", null, 0, 0, 0, null, 0, "M", null);

        repo.saveRetrievalMetrics(id, null);
        repo.saveRetrievalMetrics(id, "   ");

        assertThat(repo.countRetrievalMetrics()).isZero();
        assertThat(repo.findRecentRetrievalMetrics(0, 10)).isEmpty();
    }

    @Test
    @DisplayName("진단 수치 조회는 offset/limit 페이지네이션을 지킨다")
    void retrievalMetricsPaginate() {
        for (int i = 0; i < 5; i++) {
            long id = repo.addTurn(UID, "t1", "Q" + i, "A" + i, null, 0, 0, 0, null, 0, "M", null);
            repo.saveRetrievalMetrics(id, "[{\"label\":\"s" + i + "\"}]");
        }

        assertThat(repo.findRecentRetrievalMetrics(0, 2)).hasSize(2);
        assertThat(repo.findRecentRetrievalMetrics(4, 10)).hasSize(1);
        assertThat(repo.findRecentRetrievalMetrics(99, 10)).isEmpty();
    }

    @Test
    @DisplayName("deleteTurn 은 그 턴만 지우고 같은 대화의 나머지 턴은 남긴다")
    void deleteTurnRemovesOnlyThatTurn() {
        long first = repo.addTurn(UID, "t1", "Q1", "A1", null, 0, 0, 0, null, 0, "M", null);
        long second = repo.addTurn(UID, "t1", "Q2", "A2", null, 0, 0, 0, null, 0, "M", null);
        repo.saveTurnImageRefs(first, UID, "t1", List.of("images/a/x.png"));

        assertThat(repo.deleteTurn(UID, "t1", first)).isTrue();

        assertThat(repo.getTurns(UID, "t1")).extracting(MemoryRepository.Turn::id).containsExactly(second);
        assertThat(repo.getTurn(UID, "t1", first)).isEmpty();
        // 이미지 참조도 함께 사라진다(고아 행이 남으면 다음 대화 복원에서 유령 썸네일이 된다).
        assertThat(repo.getTurnImageRefs(UID, "t1")).doesNotContainKey(first);
    }

    @Test
    @DisplayName("deleteTurn 은 다른 사용자·다른 대화의 턴에는 닿지 않는다")
    void deleteTurnIsScopedByUserAndThread() {
        long mine = repo.addTurn(UID, "t1", "Q", "A", null, 0, 0, 0, null, 0, "M", null);

        assertThat(repo.deleteTurn("other-user", "t1", mine)).isFalse();
        assertThat(repo.deleteTurn(UID, "other-thread", mine)).isFalse();
        assertThat(repo.getTurn(UID, "t1", mine)).isPresent();
    }

    @Test
    @DisplayName("deleteTurn 은 이미 없는 턴에 대해 false 를 돌려준다")
    void deleteTurnIsIdempotent() {
        long id = repo.addTurn(UID, "t1", "Q", "A", null, 0, 0, 0, null, 0, "M", null);

        assertThat(repo.deleteTurn(UID, "t1", id)).isTrue();
        assertThat(repo.deleteTurn(UID, "t1", id)).isFalse();
        assertThat(repo.deleteTurn(UID, "t1", 999_999L)).isFalse();
    }

    @Test
    @DisplayName("삭제된 턴은 이후 프롬프트 히스토리에서도 빠진다")
    void deletedTurnDropsOutOfHistory() {
        long first = repo.addTurn(UID, "t1", "지울 질문", "지울 답변", null, 0, 0, 0, null, 0, "M", null);
        repo.addTurn(UID, "t1", "남길 질문", "남길 답변", null, 0, 0, 0, null, 0, "M", null);

        repo.deleteTurn(UID, "t1", first);

        String history = repo.getHistory(UID, "t1", 4000);
        assertThat(history).contains("남길 질문").doesNotContain("지울 질문");
    }
}
