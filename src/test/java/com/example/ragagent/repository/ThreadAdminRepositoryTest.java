package com.example.ragagent.repository;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.model.ThreadMeta;
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
 * QA — §6.25 관리자 대화 목록 집계.
 *
 * <p>The aggregate is one statement with a LEFT JOIN, four conditional SUMs and a correlated
 * subquery, and almost every way it can be wrong is a wrong <em>number</em> rather than an error:
 *
 * <ul>
 *   <li>the two reuse counters point in opposite directions and are trivially swappable</li>
 *   <li>joining {@code conversation_turns} a second time (instead of the subquery) would multiply
 *       the grouped rows and inflate every other counter — so a thread with both inbound and
 *       outbound reuse is asserted on all counters at once</li>
 *   <li>an empty thread must report 0, not vanish (that's what makes the LEFT a LEFT)</li>
 *   <li>pagination must not drop or repeat rows when the leading sort key ties</li>
 * </ul>
 */
class ThreadAdminRepositoryTest {

    private Path dbFile;
    private JdbcTemplate jdbc;
    private ThreadAdminRepository repo;
    private ThreadMetaRepository threads;
    private SqliteMemoryRepository turns;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("rag-test-thread-admin-", ".db");
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:sqlite:" + dbFile);
        jdbc = new JdbcTemplate(ds);

        threads = new ThreadMetaRepository(jdbc);
        threads.init();
        AppProperties props = org.mockito.Mockito.mock(AppProperties.class);
        org.mockito.Mockito.when(props.memorySafe())
                .thenReturn(new AppProperties.MemoryConfig(50));
        turns = new SqliteMemoryRepository(jdbc, props);
        turns.init();

        repo = new ThreadAdminRepository(jdbc);
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(dbFile);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void thread(String threadId, String userId, String title, String updatedAt) {
        threads.save(new ThreadMeta(threadId, userId, title, "latest",
                "2026-01-01 00:00:00", updatedAt, "COST_FIRST", ""));
    }

    /** A plain turn. Returns its id so a later turn can declare it as the source it reused. */
    private long turn(String userId, String threadId, String askedAt) {
        return turns.addTurn(userId, threadId, "질문", "답변", askedAt,
                0, 0, 0, "local", 1, "N", null, false, null);
    }

    private long reusingTurn(String userId, String threadId, long sourceTurnId) {
        return turns.addTurn(userId, threadId, "질문", "답변", "2026-02-01 00:00:00",
                0, 0, 0, "local", 1, "N", null, false, sourceTurnId);
    }

    private ThreadAdminRepository.ThreadRow rowOf(String threadId) {
        return repo.findAll(null, ThreadAdminRepository.Sort.RECENT, 0, 50).stream()
                .filter(r -> r.threadId().equals(threadId))
                .findFirst().orElseThrow(() -> new AssertionError("no row for " + threadId));
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("두 재사용 카운터는 반대 방향이다 — 인바운드/아웃바운드가 바뀌지 않는다")
    void inboundAndOutboundReuseCountersPointOppositeWays() {
        thread("src-t", "u1", "원본 대화", "2026-02-01 10:00:00");
        thread("dst-t", "u1", "재사용 대화", "2026-02-02 10:00:00");

        long source = turn("u1", "src-t", "2026-01-05 09:00:00");
        // dst-t 가 src-t 의 답변을 두 번 재사용한다.
        reusingTurn("u1", "dst-t", source);
        reusingTurn("u1", "dst-t", source);

        var src = rowOf("src-t");
        var dst = rowOf("dst-t");

        // 원본 대화: 스스로 재사용한 적은 없고(0), 두 번 재사용당했다(2).
        assertThat(src.reusedIn()).isZero();
        assertThat(src.reusedOut()).isEqualTo(2);
        // 재사용 대화: 정확히 반대.
        assertThat(dst.reusedIn()).isEqualTo(2);
        assertThat(dst.reusedOut()).isZero();
    }

    @Test
    @DisplayName("아웃바운드 재사용이 있어도 다른 카운터가 부풀지 않는다 (조인 대신 서브쿼리)")
    void outboundReuseDoesNotInflateOtherCounters() {
        thread("t1", "u1", "대화", "2026-02-01 10:00:00");
        long a = turn("u1", "t1", "2026-01-05 09:00:00");
        turn("u1", "t1", "2026-01-05 10:00:00");

        thread("t2", "u1", "다른 대화", "2026-02-02 10:00:00");
        reusingTurn("u1", "t2", a);
        reusingTurn("u1", "t2", a);
        reusingTurn("u1", "t2", a);

        var row = rowOf("t1");

        // conversation_turns 를 한 번 더 조인했다면 turn_count 가 3배(또는 그 이상)가 됐을 것이다.
        assertThat(row.turnCount()).isEqualTo(2);
        assertThat(row.reusedOut()).isEqualTo(3);
        assertThat(row.reusedIn()).isZero();
    }

    @Test
    @DisplayName("턴이 하나도 없는 대화도 0으로 표시된다 (LEFT JOIN)")
    void emptyThreadStillAppearsWithZeroCounters() {
        thread("empty", "u1", "빈 대화", "2026-02-01 10:00:00");

        var row = rowOf("empty");

        assertThat(row.turnCount()).isZero();
        assertThat(row.reusedIn()).isZero();
        assertThat(row.reusedOut()).isZero();
        assertThat(row.diagCount()).isZero();
        assertThat(row.lastAskedAt()).isNull();   // "측정 안 됨"이지 0이 아니다
    }

    @Test
    @DisplayName("피드백·진단 카운터와 최종 질문 시각")
    void feedbackAndDiagnosticsCounters() {
        thread("t1", "u1", "대화", "2026-02-01 10:00:00");
        long liked = turn("u1", "t1", "2026-01-05 09:00:00");
        long disliked = turn("u1", "t1", "2026-01-06 09:00:00");
        long withDiag = turn("u1", "t1", "2026-01-07 09:00:00");

        turns.updateFeedback("u1", "t1", liked, "LIKE");
        turns.updateFeedback("u1", "t1", disliked, "DISLIKE");
        turns.saveRetrievalMetrics(withDiag, "[]");

        var row = rowOf("t1");

        assertThat(row.turnCount()).isEqualTo(3);
        assertThat(row.likeCount()).isEqualTo(1);
        assertThat(row.dislikeCount()).isEqualTo(1);
        assertThat(row.diagCount()).isEqualTo(1);
        assertThat(row.lastAskedAt()).isEqualTo("2026-01-07 09:00:00");
    }

    @Test
    @DisplayName("사용자 필터 — 목록과 count가 같은 집합을 본다")
    void userFilterAppliesToBothListAndCount() {
        thread("a", "u1", "A", "2026-02-01 10:00:00");
        thread("b", "u1", "B", "2026-02-02 10:00:00");
        thread("c", "u2", "C", "2026-02-03 10:00:00");

        assertThat(repo.findAll("u1", ThreadAdminRepository.Sort.RECENT, 0, 50))
                .extracting(ThreadAdminRepository.ThreadRow::threadId)
                .containsExactly("b", "a");   // updated_at DESC
        assertThat(repo.count("u1")).isEqualTo(2);

        assertThat(repo.count(null)).isEqualTo(3);
        assertThat(repo.count("  ")).isEqualTo(3);   // blank == 필터 없음
    }

    @Test
    @DisplayName("정렬 키가 동률이어도 페이지네이션이 행을 빠뜨리거나 중복하지 않는다")
    void paginationIsStableWhenTheSortKeyTies() {
        // 전부 같은 updated_at → RECENT 의 선두 키가 완전히 동률인 최악의 경우.
        for (int i = 0; i < 6; i++) {
            thread("t" + i, "u1", "대화 " + i, "2026-02-01 10:00:00");
        }

        var page1 = repo.findAll(null, ThreadAdminRepository.Sort.RECENT, 0, 3);
        var page2 = repo.findAll(null, ThreadAdminRepository.Sort.RECENT, 3, 3);

        List<String> seen = java.util.stream.Stream.concat(page1.stream(), page2.stream())
                .map(ThreadAdminRepository.ThreadRow::threadId).toList();

        assertThat(seen).containsExactlyInAnyOrder("t0", "t1", "t2", "t3", "t4", "t5");
        assertThat(seen).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("정렬 — 턴 수 / 재사용됨")
    void sortsByTurnCountAndOutboundReuse() {
        thread("few", "u1", "적음", "2026-02-03 10:00:00");
        thread("many", "u1", "많음", "2026-02-01 10:00:00");

        long source = turn("u1", "few", "2026-01-05 09:00:00");
        for (int i = 0; i < 4; i++) turn("u1", "many", "2026-01-0" + (i + 1) + " 09:00:00");
        reusingTurn("u1", "many", source);   // few 의 답변이 재사용된다

        assertThat(repo.findAll(null, ThreadAdminRepository.Sort.TURNS, 0, 50))
                .extracting(ThreadAdminRepository.ThreadRow::threadId)
                .containsExactly("many", "few");
        // 아웃바운드 기준이면 턴이 하나뿐인 few 가 위로 온다 — 이게 두 정렬의 차이다.
        assertThat(repo.findAll(null, ThreadAdminRepository.Sort.REUSED, 0, 50))
                .extracting(ThreadAdminRepository.ThreadRow::threadId)
                .containsExactly("few", "many");
    }

    @Test
    @DisplayName("Sort.parse — 알 수 없는 값/null 은 RECENT 로 떨어진다 (ORDER BY 주입 차단)")
    void sortParseFallsBackToRecent() {
        assertThat(ThreadAdminRepository.Sort.parse(null)).isEqualTo(ThreadAdminRepository.Sort.RECENT);
        assertThat(ThreadAdminRepository.Sort.parse("m.updated_at; DROP TABLE thread_meta"))
                .isEqualTo(ThreadAdminRepository.Sort.RECENT);
        assertThat(ThreadAdminRepository.Sort.parse(" turns ")).isEqualTo(ThreadAdminRepository.Sort.TURNS);
    }

    @Test
    @DisplayName("summary — 전체 집계와 고아 턴 카운트")
    void summaryCountsThreadsUsersTurnsAndOrphans() {
        thread("t1", "u1", "A", "2026-02-01 10:00:00");
        thread("t2", "u2", "B", "2026-02-02 10:00:00");
        long a = turn("u1", "t1", "2026-01-05 09:00:00");
        turn("u2", "t2", "2026-01-06 09:00:00");
        reusingTurn("u2", "t2", a);
        // thread_meta 행 없이 턴만 있는 대화 — findAll 은 볼 수 없는 상태.
        turn("u1", "ghost", "2026-01-07 09:00:00");

        var s = repo.summary();

        assertThat(s.threadCount()).isEqualTo(2);
        assertThat(s.userCount()).isEqualTo(2);
        assertThat(s.turnCount()).isEqualTo(4);
        assertThat(s.reusedTurnCount()).isEqualTo(1);
        assertThat(s.orphanTurnCount()).isEqualTo(1);
        assertThat(repo.findAll(null, ThreadAdminRepository.Sort.RECENT, 0, 50)).hasSize(2);
    }

    @Test
    @DisplayName("findOwner — thread id 만으로 소유자를 되찾는다 (삭제가 userId를 받지 않게 하는 근거)")
    void findOwnerResolvesOwnerFromThreadIdAlone() {
        thread("t1", "u1", "A", "2026-02-01 10:00:00");

        assertThat(repo.findOwner("t1")).contains("u1");
        assertThat(repo.findOwner("없는-대화")).isEmpty();
    }

    @Test
    @DisplayName("distinctUserIds — 대화를 가진 사용자만, 정렬되어")
    void distinctUserIdsListsOwnersOnly() {
        thread("t1", "u2", "A", "2026-02-01 10:00:00");
        thread("t2", "u1", "B", "2026-02-02 10:00:00");
        thread("t3", "u1", "C", "2026-02-03 10:00:00");

        assertThat(repo.distinctUserIds()).containsExactly("u1", "u2");
    }
}
