package com.example.ragagent.repository;

import com.example.ragagent.SqliteTestDatabase;
import com.example.ragagent.model.SourceRef;
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
 * § 삭제된 출처 중 <b>답변에 기여하지 않은 것</b>만 미리보기에서 뺀다.
 *
 * <p>출처를 status 만으로 걸러내지 않는 것이 원래 계약이다 — 답변을 떠받친 청크가 배지가 아니라
 * 출처 자체로 사라지면 "원래 없었던 것"처럼 보인다. 그 우려가 성립하지 않는 경우가 정확히 하나
 * 있다: <b>삭제됐고 참여도가 0으로 측정된</b> 청크. 그건 근거였던 적이 없고 top-k 에 우연히 들어온
 * 검색 잡음이다.
 *
 * <p>가장 깨지기 쉬운 지점은 {@code answer_share} 의 <b>NULL</b> 이다. 참여도 계산이 실패하면
 * "참여도 없음"으로 degrade 되고 컬럼 추가 이전 턴도 NULL 인데, SQL 에서 {@code NULL = 0} 은
 * FALSE 가 아니라 NULL 이라 조건을 순진하게 쓰면 {@code NOT(...)} 이 NULL 이 되어 그 행까지 함께
 * 빠진다 — 옛 대화의 출처가 통째로 사라지는 모양이다. "측정해서 0" 과 "측정 못 함" 을 가르는 것이
 * 이 테스트의 핵심이다.
 */
class QuestionReuseDeletedSourceFilterTest {

    private Path dbFile;
    private QuestionReuseRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("rag-test-deleted-source-", ".db");
        JdbcTemplate jdbc = SqliteTestDatabase.open(dbFile);
        SqliteTestDatabase.createSearchIndexTables(jdbc);
        jdbc.update("INSERT INTO conversation_turns (id, user_id, thread_id, question, answer) VALUES (7, 'u1', 't1', 'q', 'a')");
        repo = new QuestionReuseRepository(jdbc, jdbc);
        repo.saveTurnSourceRefs(7L, "u1", "t1", List.of(
                new QuestionReuseRepository.SourceSnapshot("contributed", "d1", "h1", 0.62, "active"),
                new QuestionReuseRepository.SourceSnapshot("measuredZero", "d1", "h2", 0.0, "active"),
                new QuestionReuseRepository.SourceSnapshot("unmeasured", "d1", "h3", null, "active"),
                new QuestionReuseRepository.SourceSnapshot("zeroButAlive", "d1", "h4", 0.0, "active")));
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(dbFile);
    }

    private List<String> previewChunkIds() {
        return repo.findSourcePreviewRows(7L).stream()
                .map(QuestionReuseRepository.SourcePreviewRow::chunkId)
                .sorted()
                .toList();
    }

    @Test
    @DisplayName("삭제 + 참여도 0(측정됨)인 출처만 미리보기에서 빠진다 — 기여했거나 미측정이면 남는다")
    void onlyDeletedAndMeasuredZeroIsDropped() {
        assertThat(previewChunkIds())
                .as("삭제 전에는 넷 다 보인다")
                .containsExactly("contributed", "measuredZero", "unmeasured", "zeroButAlive");

        repo.markSourceRefsStaleByChunkIds(
                List.of("contributed", "measuredZero", "unmeasured"), SourceRef.STALE_DELETED);

        assertThat(previewChunkIds()).containsExactly(
                // 삭제됐지만 답변을 떠받쳤다 → 배지와 함께 남아야 한다
                "contributed",
                // 삭제됐지만 참여도를 '모른다'(NULL) → 0으로 취급하면 안 된다
                "unmeasured",
                // 참여도 0이지만 청크는 살아 있다 → 이 규칙의 대상이 아니다
                "zeroButAlive");
    }

    @Test
    @DisplayName("미리보기에서 뺀 출처도 검증용 목록에는 그대로 남는다 — 표시 규칙일 뿐 삭제가 아니다")
    void filteredOutOfPreviewButStillPresentForValidation() {
        repo.markSourceRefsStaleByChunkIds(List.of("measuredZero"), SourceRef.STALE_DELETED);

        assertThat(previewChunkIds()).doesNotContain("measuredZero");
        assertThat(repo.findAllSourceRefs(7L))
                .as("validateTurn() 이 읽는 목록 — 행을 지우지 않았으므로 판정 재료는 그대로다")
                .extracting(QuestionReuseRepository.SourceSnapshot::chunkId)
                .contains("measuredZero");
    }

    @Test
    @DisplayName("'수정됨'은 걸러내지 않는다 — 청크가 아직 있어 위치·본문을 그대로 보여줄 수 있다")
    void modifiedIsNeverFiltered() {
        repo.markSourceRefsStaleByChunkIds(
                List.of("measuredZero"), SourceRef.STALE_MODIFIED);

        assertThat(previewChunkIds()).contains("measuredZero");
    }
}
