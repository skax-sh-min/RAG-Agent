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
