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

    /** 추천 술어는 활성 출처가 있는 턴만 통과시키므로, 후보로 기대하는 턴마다 하나씩 넣는다. */
    private static final String ACTIVE_SOURCE_SQL =
            "INSERT INTO turn_source_ref (turn_id, user_id, thread_id, chunk_id, chunk_hash, status) "
            + "VALUES (?, ?, ?, ?, 'h1', ?)";

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
                    reused_from_turn_id INTEGER, selected_tags TEXT)
                """);
        jdbc.execute("CREATE TABLE chunk_fts (spring_doc_id TEXT, content TEXT, filename TEXT, page TEXT, chapter TEXT)");
        jdbc.execute("CREATE TABLE chunk_fts_key (spring_doc_id TEXT PRIMARY KEY, fts_rowid INTEGER, doc_id TEXT, "
                + "version TEXT, filename TEXT, page TEXT, chapter TEXT, content_hash TEXT)");
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
        activeSource(id, "u1", "t1");
    }

    private void activeSource(long turnId, String userId, String threadId) {
        source(turnId, userId, threadId, "active");
    }

    private void source(long turnId, String userId, String threadId, String status) {
        jdbc.update(ACTIVE_SOURCE_SQL, turnId, userId, threadId, "chunk-" + turnId, status);
    }

    private List<Long> suggestionIds() {
        return repo.findSuggestionCandidates("sqlite", false, "u1", null, 50).stream()
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

    /** 같은 질문 텍스트로 사용자·대화·모드·피드백을 지정해 넣는다. */
    private void insertTurn(long id, String userId, String threadId, String responseMode, String feedback) {
        jdbc.update("INSERT INTO conversation_turns "
                    + "(id, user_id, thread_id, question, answer, created_at, feedback, response_mode, direct_mode) "
                    + "VALUES (?, ?, ?, 'sqlite 연결 설정 방법', '답변 본문', '2026-08-24', ?, ?, 0)",
                id, userId, threadId, feedback, responseMode);
        activeSource(id, userId, threadId);
    }

    private List<Long> suggestionIdsInOrder(String userId, String threadId) {
        return repo.findSuggestionCandidates("sqlite", false, userId, threadId, 50).stream()
                .map(QuestionReuseRepository.CandidateTurn::turnId)
                .toList();
    }

    @Test
    @DisplayName("현재 대화의 턴은 재사용 술어(S/C·싫어요)를 타지 않는다 — 이동 대상이지 재사용 후보가 아니다")
    void currentThreadTurns_bypassReusePredicates() {
        insertTurn(1L, "u1", "t-here",  ResponseMode.S.name(), null);       // 현재 대화, S
        insertTurn(2L, "u1", "t-here",  ResponseMode.N.name(), "DISLIKE");  // 현재 대화, 싫어요
        insertTurn(3L, "u1", "t-other", ResponseMode.S.name(), null);       // 다른 대화, S → 제외
        insertTurn(4L, "u1", "t-other", ResponseMode.N.name(), "DISLIKE");  // 다른 대화, 싫어요 → 제외
        insertTurn(5L, "u1", "t-other", ResponseMode.N.name(), null);       // 다른 대화, 정상

        assertThat(suggestionIdsInOrder("u1", "t-here")).containsExactly(2L, 1L, 5L);
        // 실제 재사용 조회는 여전히 엄격하다 — 현재 대화의 S 턴 id 로 불러도 나오지 않는다.
        assertThat(repo.findTurnForReuse(1L, false, "u1")).isNull();
        // 대화 id 없이(REST) 부르면 그 면제는 없다.
        assertThat(suggestionIdsInOrder("u1", null)).containsExactly(5L);
    }

    @Test
    @DisplayName("정렬 — 현재 대화 → 내 다른 대화 → 그 외, 그 안에서 최신순")
    void ordering_currentThreadThenMineThenOthers() {
        insertTurn(10L, "u2", "t-x",     ResponseMode.N.name(), null);  // 남
        insertTurn(11L, "u1", "t-here",  ResponseMode.N.name(), null);  // 현재 대화 (오래됨)
        insertTurn(12L, "u1", "t-other", ResponseMode.N.name(), null);  // 내 다른 대화
        insertTurn(13L, "u2", "t-y",     ResponseMode.N.name(), null);  // 남 (최신)
        insertTurn(14L, "u1", "t-here",  ResponseMode.N.name(), null);  // 현재 대화 (최신)

        assertThat(suggestionIdsInOrder("u1", "t-here")).containsExactly(14L, 11L, 12L, 13L, 10L);
        // 대화 id 가 다른 사용자의 것과 겹쳐도 사용자가 다르면 현재 대화가 아니다.
        assertThat(suggestionIdsInOrder("u2", "t-here")).containsExactly(13L, 10L, 14L, 12L, 11L);
    }

    @Test
    @DisplayName("원본이 다른 사용자의 턴인 재사용 턴도 추천·재사용 조회에서 원본 답변을 그대로 낸다")
    void reuseTurnWhoseSourceBelongsToAnotherUser_resolvesTheSourceAnswer() {
        jdbc.update("INSERT INTO conversation_turns "
                    + "(id, user_id, thread_id, question, answer, created_at, feedback, response_mode, direct_mode) "
                    + "VALUES (1, 'u2', 't-src', 'sqlite 연결 설정 방법', '원본 답변', '2026-08-24', NULL, 'N', 0)");
        // u1 이 u2 의 턴을 재사용했다 — 답변은 비워 두고 참조만 남는 저장 모양.
        jdbc.update("INSERT INTO conversation_turns "
                    + "(id, user_id, thread_id, question, answer, created_at, feedback, response_mode, direct_mode, reused_from_turn_id) "
                    + "VALUES (2, 'u1', 't-reuse', 'sqlite 연결 설정 방법', '', '2026-08-25', NULL, 'N', 0, 1)");
        activeSource(1L, "u2", "t-src");
        activeSource(2L, "u1", "t-reuse");   // 재사용 턴은 원본의 출처를 복제해 갖는다(cloneTurnSourceRefs)

        // 예전 조인(`src.user_id = t.user_id`)이었다면 두 조회 모두 "참조 원문 삭제됨" 을 답변으로 냈다 —
        // 재사용의 재사용에서는 그 문구가 그대로 사용자에게 답변으로 나갔다.
        QuestionReuseRepository.CandidateTurn forReuse = repo.findTurnForReuse(2L, false, "u1");
        assertThat(forReuse).isNotNull();
        assertThat(forReuse.answer()).isEqualTo("원본 답변");

        assertThat(repo.findSuggestionCandidates("sqlite", false, "u1", null, 50))
                .extracting(QuestionReuseRepository.CandidateTurn::answer)
                .containsOnly("원본 답변");

        // 원본이 실제로 지워지면 폴백은 그대로다.
        jdbc.update("DELETE FROM conversation_turns WHERE id = 1");
        assertThat(repo.findTurnForReuse(2L, false, "u1").answer()).isEqualTo("참조 원문 삭제됨");
    }

    /**
     * 후보가 스스로 재사용 턴이면 답변 텍스트와 같은 행(원본)에서 모드·태그도 가져온다. 원본 값을
     * 복사해 저장하기 전에 만들어진 재사용 행은 자리표시자('M', 0, '')를 들고 있는데, 그 행을 다시
     * 재사용하면 이 COALESCE 가 원본의 값을 되돌려 준다 — 백필 없이.
     */
    @Test
    @DisplayName("재사용 턴을 다시 재사용할 때 답변의 모양(모드·태그)은 원본 행에서 온다")
    void reuseOfAReuseTurn_takesTheAnswerShapeFromTheSource() {
        jdbc.update("INSERT INTO conversation_turns "
                    + "(id, user_id, thread_id, question, answer, created_at, feedback, response_mode, direct_mode, selected_tags) "
                    + "VALUES (1, 'u2', 't-src', 'sqlite 연결 설정 방법', '원본 답변', '2026-08-24', NULL, 'N', 0, 'policy,billing')");
        // 자리표시자를 저장하던 시절의 재사용 행.
        jdbc.update("INSERT INTO conversation_turns "
                    + "(id, user_id, thread_id, question, answer, created_at, feedback, response_mode, direct_mode, reused_from_turn_id, selected_tags) "
                    + "VALUES (2, 'u1', 't-reuse', 'sqlite 연결 설정 방법', '', '2026-08-25', NULL, 'M', 0, 1, '')");
        activeSource(1L, "u2", "t-src");
        activeSource(2L, "u1", "t-reuse");

        QuestionReuseRepository.CandidateTurn viaReuse = repo.findTurnForReuse(2L, false, "u1");
        assertThat(viaReuse).isNotNull();
        assertThat(viaReuse.responseMode()).isEqualTo("N");
        assertThat(viaReuse.directMode()).isFalse();
        assertThat(viaReuse.selectedTags()).isEqualTo("policy,billing");

        // 원본이 아닌 보통 턴은 자기 행 그대로다.
        QuestionReuseRepository.CandidateTurn plain = repo.findTurnForReuse(1L, false, "u2");
        assertThat(plain.responseMode()).isEqualTo("N");
        assertThat(plain.selectedTags()).isEqualTo("policy,billing");

        // 추천 목록도 같은 열을 싣는다.
        assertThat(repo.findSuggestionCandidates("sqlite", false, "u1", null, 50))
                .extracting(QuestionReuseRepository.CandidateTurn::selectedTags)
                .containsOnly("policy,billing");
    }

    /**
     * Direct 답변은 근거 청크가 없어 validateTurn() 이 언제나 거부한다 — 후보에 올려 봐야 헛클릭이고,
     * §10.11 이후 좋아요는 지식 제안을 여는 신호이지 재사용 자격이 아니다. 판정은 답변과 같은 행
     * (재사용 턴이면 원본)에서 하므로, 자리표시자(direct 0)를 든 옛 재사용 행도 원본이 Direct 면 빠진다.
     * 현재 대화의 턴은 이동 대상이라 예외 그대로다.
     */
    @Test
    @DisplayName("Direct 턴은 좋아요가 있어도 추천·재사용 후보가 아니다 — 재사용 턴은 원본의 Direct 여부로 판정")
    void directTurns_areNeverReuseCandidates_evenWhenLiked() {
        jdbc.update("INSERT INTO conversation_turns "
                    + "(id, user_id, thread_id, question, answer, created_at, feedback, response_mode, direct_mode) "
                    + "VALUES (1, 'u1', 't-a', 'sqlite 연결 설정 방법', 'Direct 답변', '2026-08-24', 'LIKE', 'N', 1)");
        jdbc.update("INSERT INTO conversation_turns "
                    + "(id, user_id, thread_id, question, answer, created_at, feedback, response_mode, direct_mode) "
                    + "VALUES (2, 'u1', 't-b', 'sqlite 연결 설정 방법', 'Direct 답변', '2026-08-24', NULL, 'N', 1)");
        // 옛 재사용 행: 자기 행은 direct 0 이지만 원본(1)이 Direct 다.
        jdbc.update("INSERT INTO conversation_turns "
                    + "(id, user_id, thread_id, question, answer, created_at, feedback, response_mode, direct_mode, reused_from_turn_id) "
                    + "VALUES (3, 'u1', 't-c', 'sqlite 연결 설정 방법', '', '2026-08-25', NULL, 'M', 0, 1)");
        // 대조군: 보통 RAG 턴.
        jdbc.update("INSERT INTO conversation_turns "
                    + "(id, user_id, thread_id, question, answer, created_at, feedback, response_mode, direct_mode) "
                    + "VALUES (4, 'u1', 't-d', 'sqlite 연결 설정 방법', 'RAG 답변', '2026-08-24', NULL, 'N', 0)");
        // 넷 다 활성 출처를 준다 — 여기서 보려는 제외 사유는 Direct 이지 출처 없음이 아니다.
        activeSource(1L, "u1", "t-a");
        activeSource(2L, "u1", "t-b");
        activeSource(3L, "u1", "t-c");
        activeSource(4L, "u1", "t-d");

        assertThat(repo.findSuggestionCandidates("sqlite", false, "u1", null, 50))
                .extracting(QuestionReuseRepository.CandidateTurn::turnId)
                .containsExactly(4L);
        assertThat(repo.findTurnForReuse(1L, false, "u1")).isNull();
        assertThat(repo.findTurnForReuse(2L, false, "u1")).isNull();
        assertThat(repo.findTurnForReuse(3L, false, "u1")).isNull();
        assertThat(repo.findTurnForReuse(4L, false, "u1")).isNotNull();

        // 현재 대화의 Direct 턴은 여전히 목록에 오른다 — 재사용이 아니라 그 자리로의 이동이므로.
        assertThat(repo.findSuggestionCandidates("sqlite", false, "u1", "t-a", 50))
                .extracting(QuestionReuseRepository.CandidateTurn::turnId)
                .containsExactly(1L, 4L);
    }

    /**
     * validateTurn() 이 보는 사실 가운데 이 테이블에 이미 있는 둘 — 출처 행이 없다, 통지가
     * status 를 찍어 두었다 — 은 추천 SQL 이 먼저 거른다. 예전에는 그 행이 LIMIT 창을 차지한 채
     * 서비스에서 키 입력마다 다시 확인되고 버려졌다. 재사용 조회(findTurnForReuse)는 일부러 그대로
     * 둔다: 그 뒤의 validateTurn() 이 내는 사유가 폴백 토스트 문구이기 때문이다.
     */
    @Test
    @DisplayName("활성 출처가 하나도 없는 턴은 추천에 오르지 않는다 — 재사용 조회와 현재 대화 이동은 그대로")
    void turnsWithoutAnActiveSource_areFilteredBySuggestionSql() {
        jdbc.update("INSERT INTO conversation_turns "
                    + "(id, user_id, thread_id, question, answer, created_at, feedback, response_mode, direct_mode) "
                    + "VALUES (1, 'u1', 't-a', 'sqlite 연결 설정 방법', '출처 없는 답변', '2026-08-24', NULL, 'N', 0)");
        jdbc.update("INSERT INTO conversation_turns "
                    + "(id, user_id, thread_id, question, answer, created_at, feedback, response_mode, direct_mode) "
                    + "VALUES (2, 'u1', 't-b', 'sqlite 연결 설정 방법', '문서가 지워진 답변', '2026-08-24', NULL, 'N', 0)");
        jdbc.update("INSERT INTO conversation_turns "
                    + "(id, user_id, thread_id, question, answer, created_at, feedback, response_mode, direct_mode) "
                    + "VALUES (3, 'u1', 't-c', 'sqlite 연결 설정 방법', '출처 하나는 살아 있는 답변', '2026-08-24', NULL, 'N', 0)");
        jdbc.update("INSERT INTO conversation_turns "
                    + "(id, user_id, thread_id, question, answer, created_at, feedback, response_mode, direct_mode) "
                    + "VALUES (4, 'u1', 't-d', 'sqlite 연결 설정 방법', '옛 inactive 답변', '2026-08-24', NULL, 'N', 0)");
        // 1: 출처 행 없음(검색을 돌리지 않은 턴·기능 이전 턴)
        // 2: 출처 전부가 통지로 무효화됨(문서 삭제·재인덱싱)
        source(2L, "u1", "t-b", "deleted");
        source(2L, "u1", "t-b", "modified");
        // 3: 낡은 행 옆에 활성 행 하나 — SQL 은 통과시키고(엄격하지 않다) 판정은 서비스 몫
        source(3L, "u1", "t-c", "modified");
        source(3L, "u1", "t-c", "active");
        // 4: 레거시 'inactive' — 'active' 가 아니므로 없음과 같다
        source(4L, "u1", "t-d", "inactive");

        assertThat(suggestionIdsInOrder("u1", null)).containsExactly(3L);

        // 재사용 조회는 이 술어를 타지 않는다 — 사유("출처 청크가 없어…")는 validateTurn() 이 낸다.
        assertThat(repo.findTurnForReuse(1L, false, "u1")).isNotNull();
        assertThat(repo.findTurnForReuse(2L, false, "u1")).isNotNull();

        // 현재 대화의 턴은 이동 대상이라 출처가 없어도 오른다.
        assertThat(suggestionIdsInOrder("u1", "t-a")).containsExactly(1L, 3L);
    }
}
