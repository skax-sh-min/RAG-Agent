package com.example.ragagent.repository;

import com.example.ragagent.model.ResponseMode;
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
 * 재사용 후보에서 어떤 응답 모드가 빠지는가 (PLAN §6.24 Step 3-b).
 *
 * <p>이 규칙은 <b>SQL 술어 안에</b> 있다. {@code QuestionReuseServiceTest} 는 리포지터리를
 * 목킹하므로 그 술어를 한 글자도 실행하지 않고, {@code ResponseModeTest} 는 플래그만 본다 —
 * 둘 다 통과하면서 술어가 잘못돼 있을 수 있다. 그래서 진짜 SQLite 에 행을 넣고 쿼리를 돌린다.
 *
 * <p>특히 고정해야 할 것은 <b>{@code ResponseMode.parse()} 와의 일치</b>다. 술어는 허용 목록에
 * 대한 IN 이 아니라 제외 목록에 대한 NOT IN 이어야 하며, 그래야 NULL·공백·옛 {@code 'M'}/
 * {@code 'L'}·알 수 없는 값이 {@code parse()} 와 같은 방향(N = 재사용 가능)으로 떨어진다.
 */
class QuestionReuseModeFilterTest {

    private Path dbFile;
    private JdbcTemplate jdbc;
    private QuestionReuseRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("rag-test-reuse-mode-", ".db");
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:sqlite:" + dbFile);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE conversation_turns (
                    id INTEGER PRIMARY KEY, user_id TEXT, thread_id TEXT,
                    question TEXT, answer TEXT, created_at TEXT,
                    feedback TEXT, response_mode TEXT, direct_mode INTEGER,
                    reused_from_turn_id INTEGER)
                """);
        jdbc.execute("CREATE TABLE chunk_fts (spring_doc_id TEXT, content TEXT, filename TEXT, page TEXT, chapter TEXT)");
        jdbc.execute("CREATE TABLE vec_document_chunks (spring_doc_id TEXT, content TEXT, metadata TEXT)");
        repo = new QuestionReuseRepository(jdbc, jdbc);
        repo.init();
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(dbFile);
    }

    /** 같은 질문 텍스트로 턴 하나를 넣는다 — 차이는 response_mode 뿐이다. */
    private void insertTurn(long id, String responseMode) {
        jdbc.update("INSERT INTO conversation_turns "
                    + "(id, user_id, thread_id, question, answer, created_at, feedback, response_mode, direct_mode) "
                    + "VALUES (?, 'u1', 't1', 'sqlite 연결 설정 방법', '답변 본문', '2026-08-24', NULL, ?, 0)",
                id, responseMode);
    }

    private List<Long> suggestionIds() {
        return repo.findSuggestionCandidates("sqlite", false, "u1", 50).stream()
                .map(QuestionReuseRepository.CandidateTurn::turnId)
                .sorted()
                .toList();
    }

    @Test
    @DisplayName("C 턴은 추천에도 재사용 조회에도 오르지 않는다")
    void creativeTurn_isNotAReuseCandidate() {
        insertTurn(1L, ResponseMode.N.name());
        insertTurn(2L, ResponseMode.C.name());

        // "다시 만들어줘"에 저장된 코드를 그대로 돌려주면 요청한 바로 그것을 하지 않는 셈이 된다.
        assertThat(suggestionIds()).containsExactly(1L);
        assertThat(repo.findTurnForReuse(2L, false, "u1")).isNull();
        assertThat(repo.findTurnForReuse(1L, false, "u1")).isNotNull();
    }

    @Test
    @DisplayName("S 턴 제외는 기존 동작 그대로다 (SQL 리터럴에서 플래그로 옮겼을 뿐)")
    void summaryTurn_staysExcluded() {
        insertTurn(1L, ResponseMode.S.name());
        assertThat(suggestionIds()).isEmpty();
        assertThat(repo.findTurnForReuse(1L, false, "u1")).isNull();
    }

    @Test
    @DisplayName("NULL·공백·옛 M/L·알 수 없는 값은 ResponseMode.parse()와 같이 N으로 취급돼 후보에 남는다")
    void unknownAndLegacyModes_followParseLeniency() {
        insertTurn(1L, null);
        insertTurn(2L, "");
        insertTurn(3L, "M");     // 구 표준 — parse() 는 N 으로 흡수한다
        insertTurn(4L, "L");     // 제거된 모드 — 마찬가지
        insertTurn(5L, "XYZ");   // 모르는 값
        insertTurn(6L, ResponseMode.N.name());

        // 허용 목록(IN)으로 걸렀다면 이 여섯 행이 전부 조용히 빠졌을 자리다.
        assertThat(suggestionIds()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        for (long id = 1; id <= 6; id++) {
            assertThat(ResponseMode.parse(id == 6 ? "N" : null).allowsReuse()).isTrue();
        }
    }

    @Test
    @DisplayName("대소문자·앞뒤 공백이 달라도 제외 판정은 같다 (parse()의 trim/upper와 동일)")
    void modeMatchingIsTrimmedAndCaseInsensitive() {
        insertTurn(1L, " c ");
        insertTurn(2L, "s");
        insertTurn(3L, " n ");

        assertThat(suggestionIds()).containsExactly(3L);
        assertThat(ResponseMode.parse(" c ")).isEqualTo(ResponseMode.C);
        assertThat(ResponseMode.parse("s")).isEqualTo(ResponseMode.S);
    }

    @Test
    @DisplayName("제외 목록은 enum에서 파생된다 — allowsReuse()=false 인 모드가 모두 걸린다")
    void everyNonReusableModeIsExcluded() {
        long id = 1;
        List<ResponseMode> blocked = java.util.Arrays.stream(ResponseMode.values())
                .filter(m -> !m.allowsReuse()).toList();
        for (ResponseMode m : blocked) insertTurn(id++, m.name());
        long allowedId = id;
        insertTurn(allowedId, ResponseMode.N.name());

        // 모드를 하나 더 추가하면서 allowsReuse()=false 로 두면 이 단언이 자동으로 그것까지 덮는다.
        assertThat(suggestionIds()).containsExactly(allowedId);
    }
}
