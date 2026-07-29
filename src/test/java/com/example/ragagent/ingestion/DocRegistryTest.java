package com.example.ragagent.ingestion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for DocRegistry — CRUD + SQLite persistence.
 */
class DocRegistryTest {

    @TempDir
    Path tmpDir;

    private DocRegistry buildRegistry() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite:" + tmpDir.resolve("test.db"));
        DocRegistry reg = new DocRegistry(new JdbcTemplate(ds));
        reg.init();
        return reg;
    }

    private DocRegistry.DocRegistryEntry entry(String sha, String version) {
        return new DocRegistry.DocRegistryEntry(sha, version, "2026-01-01T00:00:00Z", 3,
                List.of("id1", "id2"), List.of());
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("chunk_overlap 기록 (문서 내보내기 재조립용)")
    class ChunkOverlapColumn {

        @Test
        @DisplayName("인덱싱 시점 overlap이 저장되고 그대로 조회된다")
        void persistsRecordedOverlap() {
            DocRegistry reg = buildRegistry();
            reg.put("doc_a", "anonymous", new DocRegistry.DocRegistryEntry(
                    "sha", "v1", "2026-01-01T00:00:00Z", 3, List.of("id1"), List.of(), 250));

            assertThat(reg.findByDocId("doc_a", "anonymous").orElseThrow().chunkOverlap())
                    .isEqualTo(250);
        }

        @Test
        @DisplayName("overlap=0도 '미기록'이 아니라 유효한 값으로 구분된다")
        void zeroIsDistinctFromUnknown() {
            DocRegistry reg = buildRegistry();
            reg.put("doc_zero", "anonymous", new DocRegistry.DocRegistryEntry(
                    "sha", "v1", "2026-01-01T00:00:00Z", 3, List.of("id1"), List.of(), 0));
            reg.put("doc_null", "anonymous", entry("sha", "v1"));   // 6-arg 레거시 형태 → null

            assertThat(reg.findByDocId("doc_zero", "anonymous").orElseThrow().chunkOverlap()).isZero();
            assertThat(reg.findByDocId("doc_null", "anonymous").orElseThrow().chunkOverlap()).isNull();
        }

        @Test
        @DisplayName("백필은 미기록 행만 채우고 이미 기록된 값은 건드리지 않는다")
        void backfillOnlyFillsNulls() {
            DocRegistry reg = buildRegistry();
            reg.put("doc_known", "anonymous", new DocRegistry.DocRegistryEntry(
                    "sha", "v1", "2026-01-01T00:00:00Z", 3, List.of("id1"), List.of(), 250));
            reg.put("doc_legacy", "anonymous", entry("sha", "v1"));

            assertThat(reg.backfillMissingChunkOverlap(100)).isEqualTo(1);
            assertThat(reg.findByDocId("doc_known", "anonymous").orElseThrow().chunkOverlap())
                    .as("이미 기록된 값은 덮어쓰지 않아야 한다").isEqualTo(250);
            assertThat(reg.findByDocId("doc_legacy", "anonymous").orElseThrow().chunkOverlap())
                    .isEqualTo(100);
        }

        @Test
        @DisplayName("백필은 멱등 — 재기동해도 두 번 적용되지 않는다")
        void backfillIsIdempotent() {
            DocRegistry reg = buildRegistry();
            reg.put("doc_legacy", "anonymous", entry("sha", "v1"));

            assertThat(reg.backfillMissingChunkOverlap(100)).isEqualTo(1);
            assertThat(reg.backfillMissingChunkOverlap(999))
                    .as("두 번째 실행은 채울 행이 없어야 한다").isZero();
            assertThat(reg.findByDocId("doc_legacy", "anonymous").orElseThrow().chunkOverlap())
                    .isEqualTo(100);
        }

        @Test
        @DisplayName("init()을 다시 실행해도 컬럼 추가가 실패하지 않는다 (재기동 안전)")
        void initIsRerunnable() {
            DocRegistry reg = buildRegistry();
            reg.put("doc_a", "anonymous", new DocRegistry.DocRegistryEntry(
                    "sha", "v1", "2026-01-01T00:00:00Z", 3, List.of("id1"), List.of(), 42));

            reg.init();   // 두 번째 기동 — ALTER TABLE은 이미 컬럼이 있어 무시되어야 한다

            assertThat(reg.findByDocId("doc_a", "anonymous").orElseThrow().chunkOverlap())
                    .isEqualTo(42);
        }
    }

    @Test
    @DisplayName("put → findByDocId → 조회 성공")
    void put_and_find() {
        DocRegistry reg = buildRegistry();
        reg.put("doc_abc12345", "anonymous", entry("sha1", "v1"));

        Optional<DocRegistry.DocRegistryEntry> result = reg.findByDocId("doc_abc12345", "anonymous");

        assertThat(result).isPresent();
        assertThat(result.get().sha256()).isEqualTo("sha1");
        assertThat(result.get().version()).isEqualTo("v1");
        assertThat(result.get().chunks()).isEqualTo(3);
    }

    @Test
    @DisplayName("remove → findByDocId → empty")
    void remove_then_find_empty() {
        DocRegistry reg = buildRegistry();
        reg.put("doc_abc12345", "anonymous", entry("sha1", "v1"));
        reg.remove("doc_abc12345", "anonymous");

        assertThat(reg.findByDocId("doc_abc12345", "anonymous")).isEmpty();
    }

    @Test
    @DisplayName("put → 같은 파일 DB에서 재조회 → 즉시 지속 확인")
    void put_persists_immediately() {
        DocRegistry reg1 = buildRegistry();
        reg1.put("doc_abc12345", "anonymous", entry("sha256abc", "latest"));

        DocRegistry reg2 = buildRegistry();
        assertThat(reg2.findByDocId("doc_abc12345", "anonymous")).isPresent();
        assertThat(reg2.findByDocId("doc_abc12345", "anonymous").get().sha256()).isEqualTo("sha256abc");
    }

    @Test
    @DisplayName("existsBySha256AndVersion — 존재 / 미존재")
    void existsBySha256AndVersion() {
        DocRegistry reg = buildRegistry();
        reg.put("doc_abc12345", "anonymous", entry("sha-x", "v1"));

        assertThat(reg.existsBySha256AndVersion("sha-x", "v1", "anonymous")).isTrue();
        assertThat(reg.existsBySha256AndVersion("sha-x", "v2", "anonymous")).isFalse();
        assertThat(reg.existsBySha256AndVersion("other", "v1", "anonymous")).isFalse();
    }

    @Test
    @DisplayName("existsBySha256AndVersion — chunks=0(청킹 실패로 남은 부분 저장 row)는 미존재로 취급된다")
    void existsBySha256AndVersion_partialEntry_treatedAsNotIndexed() {
        DocRegistry reg = buildRegistry();
        DocRegistry.DocRegistryEntry partial = new DocRegistry.DocRegistryEntry(
                "sha-partial", "v1", "2026-01-01T00:00:00Z", 0, List.of(), List.of());
        reg.put("doc_partial", "anonymous", partial);

        assertThat(reg.existsBySha256AndVersion("sha-partial", "v1", "anonymous")).isFalse();
    }

    @Test
    @DisplayName("findStaleDocId — 구버전 docId 탐지")
    void findStaleDocId_detects_old_entry() {
        DocRegistry reg = buildRegistry();
        reg.put("report.pdf_aabbccdd", "anonymous", entry("sha-old", "v1"));

        Optional<String> stale = reg.findStaleDocId("report.pdf", "report.pdf_11223344", "v1", "anonymous");

        assertThat(stale).hasValue("report.pdf_aabbccdd");
    }

    @Test
    @DisplayName("findStaleDocId — 동일 docId는 stale 아님")
    void findStaleDocId_ignores_same_docId() {
        DocRegistry reg = buildRegistry();
        reg.put("report.pdf_aabbccdd", "anonymous", entry("sha-same", "v1"));

        Optional<String> stale = reg.findStaleDocId("report.pdf", "report.pdf_aabbccdd", "v1", "anonymous");

        assertThat(stale).isEmpty();
    }

    @Test
    @DisplayName("filenameFromDocId — _hash 접미사 제거")
    void filenameFromDocId_strips_hash_suffix() {
        assertThat(DocRegistry.filenameFromDocId("report.pdf_ab12cd34")).isEqualTo("report.pdf");
        assertThat(DocRegistry.filenameFromDocId("nounderscore")).isEqualTo("nounderscore");
    }

    @Test
    @DisplayName("save/saveQuiet — no-op (예외 없음)")
    void save_and_saveQuiet_are_noops() {
        DocRegistry reg = buildRegistry();
        reg.put("doc_abc", "anonymous", entry("sha", "v1"));
        reg.save();
        reg.saveQuiet();
    }

    @Test
    @DisplayName("userId 격리 — 다른 userId로 조회 시 empty")
    void userId_isolation() {
        DocRegistry reg = buildRegistry();
        reg.put("doc_abc12345", "alice", entry("sha1", "v1"));

        assertThat(reg.findByDocId("doc_abc12345", "alice")).isPresent();
        assertThat(reg.findByDocId("doc_abc12345", "bob")).isEmpty();
        assertThat(reg.findByDocId("doc_abc12345", "anonymous")).isEmpty();
    }
}
