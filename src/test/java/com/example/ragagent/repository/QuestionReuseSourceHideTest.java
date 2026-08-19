package com.example.ragagent.repository;

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
 * § 현재 대화에서 이 청크 제거 — {@code turn_source_ref.hidden_at}.
 *
 * <p>핵심 계약은 "숨김은 표시 전용"이다: 미리보기 목록에서는 사라지되 재사용 검증이 읽는
 * {@code findAllSourceRefs}에는 그대로 남아야 한다. 둘이 갈라지면 화면에서 배지를 치운 것만으로
 * 그 답변의 유효성 판정이 달라진다.
 */
class QuestionReuseSourceHideTest {

    private Path dbFile;
    private JdbcTemplate jdbc;
    private QuestionReuseRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("rag-test-source-hide-", ".db");
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:sqlite:" + dbFile);
        jdbc = new JdbcTemplate(ds);
        // findSourcePreviewRows()가 조인하는 최소 스키마 — 청크 본문은 이 테스트의 관심사가 아니다.
        jdbc.execute("CREATE TABLE conversation_turns (id INTEGER PRIMARY KEY, user_id TEXT)");
        jdbc.execute("CREATE TABLE chunk_fts (spring_doc_id TEXT, content TEXT, filename TEXT, page TEXT, chapter TEXT)");
        jdbc.execute("CREATE TABLE vec_document_chunks (spring_doc_id TEXT, content TEXT, metadata TEXT)");
        jdbc.update("INSERT INTO conversation_turns (id, user_id) VALUES (7, 'u1')");
        repo = new QuestionReuseRepository(jdbc, jdbc);
        repo.init();
        repo.saveTurnSourceRefs(7L, "u1", "t1", List.of(
                new QuestionReuseRepository.SourceSnapshot("c1", "d1", "h1", 0.62, "active"),
                new QuestionReuseRepository.SourceSnapshot("c2", "d1", "h2", null, "active")));
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
    @DisplayName("숨긴 출처는 미리보기 목록에서 빠지지만 검증용 목록에는 남는다")
    void hidden_disappearsFromPreviewOnly() {
        assertThat(previewChunkIds()).containsExactly("c1", "c2");

        assertThat(repo.hideSourceRef(7L, "u1", "t1", "c2")).isEqualTo(1);

        assertThat(previewChunkIds()).containsExactly("c1");
        assertThat(repo.findAllSourceRefs(7L))
                .extracting(QuestionReuseRepository.SourceSnapshot::chunkId)
                .containsExactlyInAnyOrder("c1", "c2");
        // status는 건드리지 않는다 — 숨김은 "청크가 바뀌었다"는 관측이 아니다.
        assertThat(repo.findSourceRefs(7L))
                .extracting(QuestionReuseRepository.SourceSnapshot::chunkId)
                .containsExactlyInAnyOrder("c1", "c2");
    }

    @Test
    @DisplayName("이미 숨긴 출처를 다시 숨기면 0건 (멱등)")
    void hidingTwice_isNoop() {
        assertThat(repo.hideSourceRef(7L, "u1", "t1", "c2")).isEqualTo(1);
        assertThat(repo.hideSourceRef(7L, "u1", "t1", "c2")).isZero();
    }

    @Test
    @DisplayName("소유자/스레드/청크가 다르면 아무것도 숨기지 않는다")
    void wrongOwnerOrChunk_hidesNothing() {
        assertThat(repo.hideSourceRef(7L, "other", "t1", "c2")).isZero();
        assertThat(repo.hideSourceRef(7L, "u1", "other-thread", "c2")).isZero();
        assertThat(repo.hideSourceRef(7L, "u1", "t1", "unknown-chunk")).isZero();
        assertThat(repo.hideSourceRef(0L, "u1", "t1", "c2")).isZero();
        assertThat(repo.hideSourceRef(7L, "u1", "t1", "  ")).isZero();
        assertThat(previewChunkIds()).containsExactly("c1", "c2");
    }
}
