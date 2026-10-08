package com.example.ragagent.repository;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.service.HistoryPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * §10.13 — 이력의 <b>폴백 경로</b>(요약 캐시가 없을 때)가 요약 경로와 같은 렌더 규칙을 읽는가.
 *
 * <p>이 경로는 지금까지 답변을 <b>전문 그대로</b> 넣고 전체 문자 예산만 걸었다. 그래서 같은
 * 스레드가 요약 캐시가 있느냐에 따라 다른 맥락을 봤고, 캐시 TTL 이 지나는 순간 이력이 갑자기
 * 달라졌다. §10.13 의 새 규칙을 한쪽에만 넣으면 그 재현성 문제가 그대로 남는다 — 그래서 두
 * 경로 모두 {@link HistoryPolicy#renderAnswer} 를 거친다.
 */
class DirectHistoryFallbackTest {

    private static final String UID = "u1";
    private static final String TID = "t1";

    /** 요약 섹션이 없는 DN 답변 — 예전 요약 경로에서 1,200자 캡에 걸리던 유일한 종류. */
    private static final String DN_ANSWER = "직접 답변 본문입니다.".repeat(120);

    private static final String RAG_ANSWER =
            "## 요약\n한 줄 요약.\n\n## 상세 설명\n자세한 본문입니다.\n\n## 참고\n- [파일.docx | p.1]";

    private Path dbFile;
    private JdbcTemplate jdbc;
    private SqliteMemoryRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("rag-test-direct-history-", ".db");
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + dbFile));
        AppProperties props = mock(AppProperties.class);
        when(props.memorySafe()).thenReturn(new AppProperties.MemoryConfig(50));
        repo = new SqliteMemoryRepository(jdbc, props);
        repo.init();
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(dbFile);
    }

    /** 이전 턴이 <b>RAG</b> 였던 경우. */
    private void addTurn(String question, String answer, String mode) {
        addTurn(question, answer, mode, false);
    }

    /** 이전 턴이 <b>Direct</b> 였던 경우 — 렌더 규칙이 갈리는 축이라 픽스처가 그것을 말해야 한다. */
    private void addDirectTurn(String question, String answer, String mode) {
        addTurn(question, answer, mode, true);
    }

    private void addTurn(String question, String answer, String mode, boolean directMode) {
        repo.addTurn(UID, TID, question, answer, "2026-09-03 00:00:00",
                0, 0, 0, "local", 1, mode, null, directMode, null);
    }

    @Test
    @DisplayName("Direct 로 물으면 DN 답변이 전문 그대로 들어간다 — 캡이 걸리지 않는다")
    void askingDirect_keepsADirectAnswerWhole() {
        addDirectTurn("첫 질문", DN_ANSWER, "N");

        assertThat(repo.getHistory(UID, TID, 100_000, true)).contains(DN_ANSWER);
    }

    @Test
    @DisplayName("Direct 로 물으면 이전 RAG 턴에서 요약·참고가 빠진 본문이 들어간다")
    void askingDirect_stripsSummaryAndReferences() {
        addTurn("첫 질문", RAG_ANSWER, "N");

        assertThat(repo.getHistory(UID, TID, 100_000, true))
                .contains("자세한 본문입니다.")
                .doesNotContain("## 요약", "한 줄 요약", "## 참고", "파일.docx");
    }

    @Test
    @DisplayName("Direct 로 물으면 이전 S 턴은 '## 요약' 만 — 그게 답변 전부다")
    void askingDirect_summaryOnlyTurn() {
        addTurn("첫 질문", "## 요약\n짧은 요약.", "S");

        assertThat(repo.getHistory(UID, TID, 100_000, true))
                .contains("짧은 요약.").doesNotContain("## 요약");
    }

    @Test
    @DisplayName("RAG 로 물으면 지금까지의 동작 그대로 — 이 변경은 Direct 로 스코프된다")
    void askingRag_isUnchanged() {
        addTurn("첫 질문", RAG_ANSWER, "N");

        // 예전과 같이 '## 요약' 만 들어간다(폴백 경로도 이제 그 규칙을 읽는다).
        String rendered = repo.getHistory(UID, TID, 100_000, false);
        assertThat(rendered).contains("한 줄 요약.").doesNotContain("자세한 본문입니다.");
    }

    /**
     * 이 경로가 규칙을 <b>복제하지 않고 위임한다</b>는 것 — 한쪽만 고쳤을 때 갈리는 자리가
     * 정확히 여기다.
     */
    @Test
    @DisplayName("렌더 결과가 HistoryPolicy 와 문자 그대로 같다 (규칙을 복제하지 않는다)")
    void delegatesToTheSharedPolicy() {
        for (boolean previousWasDirect : new boolean[]{false, true}) {
            jdbc.update("DELETE FROM conversation_turns");
            addTurn("첫 질문", RAG_ANSWER, "N", previousWasDirect);

            for (boolean askingDirect : new boolean[]{true, false}) {
                assertThat(repo.getHistory(UID, TID, 100_000, askingDirect))
                        .as("askingDirect=%s previousWasDirect=%s", askingDirect, previousWasDirect)
                        .isEqualTo("Q: 첫 질문\nA: " + HistoryPolicy.renderAnswer(
                                RAG_ANSWER, "N", askingDirect, previousWasDirect));
            }
        }
    }

    @Test
    @DisplayName("RAG 로 물어도 이전 턴이 Direct 였으면 전문이 들어간다 — SQL 이 direct_mode 를 싣는다")
    void askingRag_previousDirectTurn_keepsTheBody() {
        addDirectTurn("첫 질문", DN_ANSWER, "N");

        assertThat(repo.getHistory(UID, TID, 100_000, false)).contains(DN_ANSWER);
    }

    @Test
    @DisplayName("프롬프트에 싣는 턴은 가져오는 창의 절반까지 — 10턴 설정이면 최근 5턴")
    void historyIsCappedAtHalfTheFetchWindow() {
        AppProperties props = mock(AppProperties.class);
        when(props.memorySafe()).thenReturn(new AppProperties.MemoryConfig(10));
        SqliteMemoryRepository capped = new SqliteMemoryRepository(jdbc, props);
        for (int i = 1; i <= 8; i++) addTurn("질문" + i, "## 요약\n답변" + i, "N");

        String history = capped.getHistory(UID, TID, 100_000, false);

        assertThat(history).contains("질문4", "질문5", "질문6", "질문7", "질문8")
                .doesNotContain("질문1", "질문2", "질문3");
    }

    @Test
    @DisplayName("인자 없는 오버로드는 RAG 기준 — §10.13 이전과 동작이 같다")
    void defaultOverloadStaysOnTheRagRule() {
        addTurn("첫 질문", RAG_ANSWER, "N");

        assertThat(repo.getHistory(UID, TID, 100_000))
                .isEqualTo(repo.getHistory(UID, TID, 100_000, false));
    }
}
