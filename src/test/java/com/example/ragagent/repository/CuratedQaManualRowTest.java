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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA — 청크 추가: {@code curated_qa} 의 수동(사용자 제안) 행과 레거시 스키마 마이그레이션.
 *
 * <p>Covers:
 *  - 레거시 스키마(source_turn_id NOT NULL, origin 없음) → 재생성 마이그레이션이 데이터를 보존
 *  - 마이그레이션 후 turn 기반 UNIQUE 제약이 그대로 유지된다(부분 인덱스)
 *  - insertManual: source_turn_id NULL 로 여러 건 공존(부분 UNIQUE 인덱스가 막지 않음)
 *  - deactivateById 는 수동 행에도 동작하고, deactivate(turnId) 는 (NULL 이라) 동작하지 않는다
 */
class CuratedQaManualRowTest {

    private Path dbFile;
    private JdbcTemplate jdbc;
    private CuratedQaRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("rag-test-curated-manual-", ".db");
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:sqlite:" + dbFile);
        jdbc = new JdbcTemplate(ds);
        repo = new CuratedQaRepository(jdbc);
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(dbFile);
    }

    @Test
    @DisplayName("insertManual — source_turn_id NULL 행을 여러 건 넣어도 UNIQUE 충돌이 없다")
    void insertManual_multipleNullTurnRows_coexist() {
        repo.init();

        long a = repo.insertManual(1L, "u1", "제목 A", "본문 A", null);
        long b = repo.insertManual(2L, "u2", "제목 B", "본문 B", null);

        assertThat(a).isPositive();
        assertThat(b).isNotEqualTo(a);

        Optional<CuratedQaRepository.CuratedQa> rowA = repo.findById(a);
        assertThat(rowA).isPresent();
        assertThat(rowA.get().sourceTurnId()).isNull();
        assertThat(rowA.get().sourceSubmissionId()).isEqualTo(1L);
        assertThat(rowA.get().origin()).isEqualTo(CuratedQaRepository.ORIGIN_MANUAL);
        assertThat(rowA.get().isManual()).isTrue();
        assertThat(rowA.get().question()).isEqualTo("제목 A");
        assertThat(rowA.get().answer()).isEqualTo("본문 A");
        assertThat(rowA.get().status()).isEqualTo("active");
        assertThat(rowA.get().embedStatus()).isEqualTo("ok");
    }

    @Test
    @DisplayName("deactivateById — 수동 행을 비활성화한다 (deactivate(turnId)로는 불가능)")
    void deactivateById_worksForManualRow() {
        repo.init();
        long id = repo.insertManual(7L, "u1", "제목", "본문", null);

        // source_turn_id 가 NULL 이므로 turn 기준 UPDATE 는 어떤 값으로도 매칭되지 않는다.
        repo.deactivate(0L);
        assertThat(repo.findById(id).orElseThrow().status()).isEqualTo("active");

        repo.deactivateById(id);
        assertThat(repo.findById(id).orElseThrow().status()).isEqualTo("inactive");
    }

    @Test
    @DisplayName("마이그레이션 — 레거시 스키마의 기존 행을 보존하고 origin='like'로 채운다")
    void migration_preservesLegacyRows() {
        createLegacySchema();
        jdbc.update("INSERT INTO curated_qa (source_turn_id, source_user_id, source_thread_id, " +
                    "question, answer, status, source_doc_version, created_at, updated_at) " +
                    "VALUES (42, 'u1', 't1', '옛 질문', '옛 답변', 'active', 'v1', '2026-01-01', '2026-01-01')");

        repo.init();

        List<Map<String, Object>> cols = jdbc.queryForList("PRAGMA table_info(curated_qa)");
        assertThat(cols).extracting(c -> c.get("name"))
                .contains("origin", "source_submission_id", "embed_status");

        Optional<CuratedQaRepository.CuratedQa> row = repo.findBySourceTurnId(42L);
        assertThat(row).isPresent();
        assertThat(row.get().question()).isEqualTo("옛 질문");
        assertThat(row.get().answer()).isEqualTo("옛 답변");
        assertThat(row.get().sourceDocVersion()).isEqualTo("v1");
        assertThat(row.get().origin()).isEqualTo(CuratedQaRepository.ORIGIN_LIKE);
        assertThat(row.get().sourceSubmissionId()).isNull();
        // 마이그레이션 전 행에는 embed_status 컬럼이 없었으므로 기본값이 채워져야 한다.
        assertThat(row.get().embedStatus()).isEqualTo("ok");
    }

    @Test
    @DisplayName("마이그레이션 — 재실행해도 멱등이고, turn UNIQUE 제약은 유지된다")
    void migration_isIdempotentAndKeepsTurnUniqueness() {
        createLegacySchema();
        repo.init();
        repo.init();   // 두 번째 호출은 origin 컬럼이 이미 있으므로 재생성하지 않는다

        long first  = repo.upsertActive(9L, "u1", "t1", "질문", "답변", "v1", null);
        long second = repo.upsertActive(9L, "u1", "t1", "질문 수정", "답변 수정", "v1", null);

        // 같은 turn → 새 행이 아니라 기존 행 갱신 (부분 UNIQUE 인덱스가 살아 있다는 증거)
        assertThat(second).isEqualTo(first);
        assertThat(repo.findBySourceTurnId(9L).orElseThrow().answer()).isEqualTo("답변 수정");
        assertThat(repo.findAllActive(50)).hasSize(1);
    }

    /** {@code origin}/{@code embed_status} 이전, {@code source_turn_id NOT NULL} 이던 원래 스키마. */
    private void createLegacySchema() {
        jdbc.execute("""
                CREATE TABLE curated_qa (
                    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
                    source_turn_id      INTEGER NOT NULL,
                    source_user_id      TEXT NOT NULL,
                    source_thread_id    TEXT NOT NULL,
                    question            TEXT NOT NULL,
                    answer              TEXT NOT NULL,
                    status              TEXT NOT NULL DEFAULT 'active',
                    source_doc_version  TEXT,
                    created_at          TEXT NOT NULL,
                    updated_at          TEXT NOT NULL
                )
                """);
        jdbc.execute("CREATE UNIQUE INDEX idx_curated_qa_turn ON curated_qa(source_turn_id)");
        jdbc.execute("CREATE INDEX idx_curated_qa_status ON curated_qa(status)");
    }
}
