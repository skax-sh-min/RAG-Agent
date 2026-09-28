package com.example.ragagent.repository;

import com.example.ragagent.model.SourceRef;
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
 * § 출처 위치 스냅샷 — {@code turn_source_ref.filename}/{@code page_or_slide}/{@code chapter_no}.
 *
 * <p>이 테이블은 청크 id 와 해시만 스냅샷하고 <b>위치는 라이브 조인</b>으로 가져왔다. 청크가
 * 지워지면 그 조인이 비어, 다시 연 대화의 출처가 파일명 자리에 16진 {@code doc_id} 를, 페이지
 * 자리에 아무것도 갖지 못했다 — 답변이 실제로 근거로 삼은 청크인데도 "어느 문서 몇 쪽이었나"를
 * 답할 수 없었다.
 *
 * <p>핵심 계약은 <b>우선순위</b>다: 라이브 값이 언제나 이긴다. 청크가 살아 있는데 재인덱싱으로
 * 위치가 바뀌었다면 지금 위치가 사실이고, 스냅샷은 그것이 사라졌을 때만 쓰이는 폴백이다.
 */
class QuestionReusePositionSnapshotTest {

    private Path dbFile;
    private JdbcTemplate jdbc;
    private QuestionReuseRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("rag-test-position-snapshot-", ".db");
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:sqlite:" + dbFile);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE conversation_turns (id INTEGER PRIMARY KEY, user_id TEXT)");
        jdbc.execute("CREATE TABLE chunk_fts (spring_doc_id TEXT, content TEXT, filename TEXT, page TEXT, chapter TEXT)");
        jdbc.execute("CREATE TABLE chunk_fts_key (spring_doc_id TEXT PRIMARY KEY, fts_rowid INTEGER, doc_id TEXT, "
                + "version TEXT, filename TEXT, page TEXT, chapter TEXT, content_hash TEXT)");
        jdbc.execute("CREATE TABLE vec_document_chunks (spring_doc_id TEXT, content TEXT, metadata TEXT)");
        jdbc.update("INSERT INTO conversation_turns (id, user_id) VALUES (7, 'u1')");
        jdbc.update("INSERT INTO conversation_turns (id, user_id) VALUES (8, 'u1')");
        repo = new QuestionReuseRepository(jdbc, jdbc);
        repo.init();
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(dbFile);
    }

    /** 턴 저장 시점의 위치를 함께 떠 둔다 — 참여도는 0이 아니어야 삭제 후에도 목록에 남는다. */
    private void saveWithSnapshot(long turnId, String chunkId) {
        repo.saveTurnSourceRefs(turnId, "u1", "t1", List.of(
                new QuestionReuseRepository.SourceSnapshot(
                        chunkId, "d1", "h1", 0.5, "active", "설계문서.pdf", "12", "0")));
    }

    /** 청크가 살아 있는 상태 — 라이브 조인이 채워진다. */
    private void chunkIsAlive(String chunkId, String filename, String page) {
        jdbc.update("INSERT INTO chunk_fts_key (spring_doc_id, fts_rowid, doc_id, version, filename, page, chapter, content_hash) "
                + "VALUES (?, 1, 'd1', 'latest', ?, ?, '0', 'h1')", chunkId, filename, page);
    }

    private QuestionReuseRepository.SourcePreviewRow previewOf(long turnId) {
        List<QuestionReuseRepository.SourcePreviewRow> rows = repo.findSourcePreviewRows(turnId);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    @Test
    @DisplayName("청크가 지워져도 스냅샷이 파일명·페이지를 채운다 — 예전에는 둘 다 비었다")
    void deletedChunk_positionComesFromTheSnapshot() {
        saveWithSnapshot(7L, "c1");
        // chunk_fts_key/vec_document_chunks 에 행을 넣지 않는다 = 청크가 지워진 상태.
        repo.markSourceRefsStaleByChunkIds(List.of("c1"), SourceRef.STALE_DELETED);

        QuestionReuseRepository.SourcePreviewRow row = previewOf(7L);

        assertThat(row.filename()).isEqualTo("설계문서.pdf");
        assertThat(row.pageOrSlide()).isEqualTo("12");
        assertThat(row.status()).isEqualTo("deleted");
    }

    @Test
    @DisplayName("청크가 살아 있으면 라이브 값이 이긴다 — 재인덱싱으로 위치가 바뀌면 지금 위치가 사실이다")
    void liveChunk_beatsTheSnapshot() {
        saveWithSnapshot(7L, "c1");
        chunkIsAlive("c1", "설계문서-v2.pdf", "34");

        QuestionReuseRepository.SourcePreviewRow row = previewOf(7L);

        assertThat(row.filename()).isEqualTo("설계문서-v2.pdf");
        assertThat(row.pageOrSlide()).isEqualTo("34");
    }

    @Test
    @DisplayName("스냅샷 이전에 저장된 구 행은 예전 그대로 — 위치 없이 온다(backfill 불가)")
    void legacyRowWithoutSnapshot_behavesAsBefore() {
        // 위치 셋을 모르는 5-인자 생성자 = 이 컬럼들이 생기기 전에 저장된 행과 같은 모양.
        repo.saveTurnSourceRefs(7L, "u1", "t1", List.of(
                new QuestionReuseRepository.SourceSnapshot("c1", "d1", "h1", 0.5, "active")));
        repo.markSourceRefsStaleByChunkIds(List.of("c1"), SourceRef.STALE_DELETED);

        QuestionReuseRepository.SourcePreviewRow row = previewOf(7L);

        assertThat(row.filename()).isNull();
        assertThat(row.pageOrSlide()).isNull();
    }

    @Test
    @DisplayName("재사용 턴으로 복사할 때 위치도 함께 간다 — 빠뜨리면 재사용 답변만 위치를 잃는다")
    void cloneCarriesTheSnapshot() {
        saveWithSnapshot(7L, "c1");
        repo.markSourceRefsStaleByChunkIds(List.of("c1"), SourceRef.STALE_DELETED);

        repo.cloneTurnSourceRefs(7L, 8L, "u1", "t1");

        QuestionReuseRepository.SourcePreviewRow row = previewOf(8L);
        assertThat(row.filename()).isEqualTo("설계문서.pdf");
        assertThat(row.pageOrSlide()).isEqualTo("12");
    }
}
