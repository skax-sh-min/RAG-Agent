package com.example.ragagent.repository;

import com.example.ragagent.config.AppProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private JdbcTemplate jdbc;
    private SqliteMemoryRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("rag-test-", ".db");
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:sqlite:" + dbFile);
        jdbc = new JdbcTemplate(ds);
        AppProperties props = mock(AppProperties.class);
        when(props.memorySafe()).thenReturn(new AppProperties.MemoryConfig(50));
        repo = new SqliteMemoryRepository(jdbc, props);
        repo.init();
        // deleteTurn()/clearHistory()는 turn_source_ref 까지 지우는데 그 테이블의 소유자는
        // QuestionReuseRepository 다(§6.23 런타임 DDL). 여기서 실제 init()을 돌려 스키마를
        // 운영과 맞춘다 — DDL을 테스트에 복사하면 그쪽이 바뀔 때 조용히 어긋난다.
        // (memory.db 단독 모드에서는 vectorJdbcTemplate 가 primary 의 별칭이라 같은 걸 넘긴다.)
        new QuestionReuseRepository(jdbc, jdbc).init();
        // 같은 이유로 thread_meta 도 실제 소유자에게 만들게 한다: findRecentRetrievalMetrics 가
        // 대화 제목을 붙이려고 LEFT JOIN 한다(§6.25). 운영에서는 두 리포지토리의 @PostConstruct 가
        // 모두 돌아 항상 함께 존재하고, 이 테스트만이 그렇지 않은 유일한 맥락이다.
        new ThreadMetaRepository(jdbc).init();
    }

    /** 검색 출처 스냅샷 1건 — deleteTurn 이 자식 행까지 지우는지 보기 위한 최소 픽스처. */
    private void insertSourceRef(long turnId, String threadId, String chunkId) {
        jdbc.update("INSERT INTO turn_source_ref (turn_id, user_id, thread_id, chunk_id, chunk_hash) "
                + "VALUES (?, ?, ?, ?, ?)", turnId, UID, threadId, chunkId, "hash-" + chunkId);
    }

    private int sourceRefCount(long turnId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM turn_source_ref WHERE turn_id = ?", Integer.class, turnId);
        return n == null ? 0 : n;
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

    // ── §6.25 진단 수치 필터 (사용자 / 대화) ────────────────────────────────────

    /** 필터가 걸린 목록과 개수가 <b>같은 집합</b>을 봐야 한다. 페이지네이션 버튼은 크기 기반이라
     *  개수만 어긋나도 아무것도 깨지지 않는다 — 그래서 눈치채기 어렵고, 그래서 고정한다. */
    @Test
    @DisplayName("사용자·대화 필터가 목록과 개수에 똑같이 걸린다")
    void metricsFiltersApplyToBothListAndCount() {
        long a = repo.addTurn("u1", "t1", "Q", "A", null, 0, 0, 0, null, 0, "N", null);
        long b = repo.addTurn("u1", "t2", "Q", "A", null, 0, 0, 0, null, 0, "N", null);
        long c = repo.addTurn("u2", "t3", "Q", "A", null, 0, 0, 0, null, 0, "N", null);
        for (long id : new long[]{a, b, c}) repo.saveRetrievalMetrics(id, "[{\"label\":\"x\"}]");

        assertThat(repo.findRecentRetrievalMetrics("u1", null, 0, 10)).hasSize(2);
        assertThat(repo.countRetrievalMetrics("u1", null)).isEqualTo(2);

        assertThat(repo.findRecentRetrievalMetrics(null, "t2", 0, 10)).hasSize(1);
        assertThat(repo.countRetrievalMetrics(null, "t2")).isEqualTo(1);

        // 공백은 "필터 없음"과 같게 취급된다 — SQL 에 도달하는 형태는 하나뿐이어야 한다.
        assertThat(repo.countRetrievalMetrics("  ", "")).isEqualTo(3);
    }

    /** 회귀 가드 — 필터 없이 부른 결과가 이 기능 도입 전과 완전히 같아야 한다. */
    @Test
    @DisplayName("필터를 주지 않으면 예전 시그니처와 완전히 같은 목록·개수를 낸다")
    void unfilteredCallsAreUnchanged() {
        for (int i = 0; i < 3; i++) {
            long id = repo.addTurn(UID, "t" + i, "Q" + i, "A", null, 0, 0, 0, null, 0, "N", null);
            repo.saveRetrievalMetrics(id, "[{\"label\":\"s" + i + "\"}]");
        }

        assertThat(repo.findRecentRetrievalMetrics(0, 10))
                .usingRecursiveComparison()
                .isEqualTo(repo.findRecentRetrievalMetrics(null, null, 0, 10));
        assertThat(repo.countRetrievalMetrics()).isEqualTo(repo.countRetrievalMetrics(null, null));
    }

    /** LEFT JOIN 이어야 하는 이유 — thread_meta 행이 없는 턴의 진단도 목록에 남아야 한다.
     *  빠지면 목록과 "전체 N턴" 배지가 조용히 어긋난다. */
    @Test
    @DisplayName("대화(thread_meta) 행이 없는 턴도 진단 목록에 남고, 제목만 null 이다")
    void orphanTurnStillAppearsWithNullTitle() {
        long orphan = repo.addTurn(UID, "사라진-대화", "Q", "A", null, 0, 0, 0, null, 0, "N", null);
        repo.saveRetrievalMetrics(orphan, "[{\"label\":\"x\"}]");

        var rows = repo.findRecentRetrievalMetrics(null, null, 0, 10);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).threadId()).isEqualTo("사라진-대화");
        assertThat(rows.get(0).threadTitle()).isNull();
        assertThat(repo.countRetrievalMetrics(null, null)).isEqualTo(1);
    }

    @Test
    @DisplayName("대화 제목·소유자가 진단 행에 함께 실린다")
    void metricsRowCarriesOwnerAndThreadTitle() {
        new ThreadMetaRepository(jdbc).save(new com.example.ragagent.model.ThreadMeta(
                "t1", UID, "[latest] 인덱싱 질문", "latest",
                "2026-01-01 00:00:00", "2026-01-02 00:00:00", "COST_FIRST", ""));
        long id = repo.addTurn(UID, "t1", "Q", "A", null, 0, 0, 0, null, 0, "N", null);
        repo.saveRetrievalMetrics(id, "[{\"label\":\"x\"}]");

        var row = repo.findRecentRetrievalMetrics(null, null, 0, 10).get(0);

        assertThat(row.userId()).isEqualTo(UID);
        assertThat(row.threadId()).isEqualTo("t1");
        assertThat(row.threadTitle()).isEqualTo("[latest] 인덱싱 질문");   // 접두 제거는 표시 계층의 몫
    }

    @Test
    @DisplayName("distinctRetrievalMetricsUserIds — 진단이 있는 사용자만 (전체 대화 소유자와 다른 집합)")
    void distinctUserIdsCoversOnlyOwnersWithMetrics() {
        long withMetrics = repo.addTurn("u1", "t1", "Q", "A", null, 0, 0, 0, null, 0, "N", null);
        repo.addTurn("u2", "t2", "Q", "A", null, 0, 0, 0, null, 0, "N", null);   // 진단 없음
        repo.saveRetrievalMetrics(withMetrics, "[{\"label\":\"x\"}]");

        assertThat(repo.distinctRetrievalMetricsUserIds()).containsExactly("u1");
    }

    @Test
    @DisplayName("deleteTurn 은 그 턴만 지우고 같은 대화의 나머지 턴은 남긴다")
    void deleteTurnRemovesOnlyThatTurn() {
        long first = repo.addTurn(UID, "t1", "Q1", "A1", null, 0, 0, 0, null, 0, "M", null);
        long second = repo.addTurn(UID, "t1", "Q2", "A2", null, 0, 0, 0, null, 0, "M", null);
        repo.saveTurnImageRefs(first, UID, "t1", List.of("images/a/x.png"));
        insertSourceRef(first, "t1", "chunk-1");
        insertSourceRef(second, "t1", "chunk-2");

        assertThat(repo.deleteTurn(UID, "t1", first)).isTrue();

        assertThat(repo.getTurns(UID, "t1")).extracting(MemoryRepository.Turn::id).containsExactly(second);
        assertThat(repo.getTurn(UID, "t1", first)).isEmpty();
        // 이미지 참조도 함께 사라진다(고아 행이 남으면 다음 대화 복원에서 유령 썸네일이 된다).
        assertThat(repo.getTurnImageRefs(UID, "t1")).doesNotContainKey(first);
        // 출처 스냅샷도 그 턴 것만 사라진다 — clearHistory()와 같은 테이블 집합을 turn_id 하나로
        // 좁힌 것이므로, 세 테이블 중 하나라도 빠지면 고아 행이 남는다(CLAUDE.md).
        assertThat(sourceRefCount(first)).isZero();
        assertThat(sourceRefCount(second)).isEqualTo(1);
    }

    @Test
    @DisplayName("deleteTurn 은 turn_source_ref 테이블이 아예 없어도 나머지를 지운다(그 테이블 소유자는 QuestionReuseRepository)")
    void deleteTurnToleratesMissingTurnSourceRefTable() {
        long id = repo.addTurn(UID, "t1", "Q", "A", null, 0, 0, 0, null, 0, "M", null);
        // QuestionReuseRepository.init() 을 돌리지 않은 컨텍스트를 재현한다. 그 경우 지울 출처
        // 자체가 없으므로 관용이 맞지만, 관용의 catch 대상이 틀리면(과거: BadSqlGrammarException)
        // SQLite 의 no-such-table 이 UncategorizedSQLException 으로 와서 그대로 터진다.
        jdbc.execute("DROP TABLE turn_source_ref");

        assertThat(repo.deleteTurn(UID, "t1", id)).isTrue();
        assertThat(repo.getTurn(UID, "t1", id)).isEmpty();
    }

    @Test
    @DisplayName("deleteTurn 은 turn_source_ref 외의 SQL 오류는 삼키지 않는다")
    void deleteTurnDoesNotSwallowUnrelatedSqlErrors() {
        long id = repo.addTurn(UID, "t1", "Q", "A", null, 0, 0, 0, null, 0, "M", null);
        // 관용은 "turn_source_ref 가 없다"에만 걸려야 한다. 다른 테이블이 사라진 상황까지
        // 조용히 넘기면 삭제가 절반만 된 것을 아무도 모른다.
        jdbc.execute("DROP TABLE turn_image_ref");

        assertThatThrownBy(() -> repo.deleteTurn(UID, "t1", id))
                .isInstanceOf(DataAccessException.class);
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
