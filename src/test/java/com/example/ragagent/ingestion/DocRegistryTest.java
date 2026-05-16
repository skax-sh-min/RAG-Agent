package com.example.ragagent.ingestion;

import com.example.ragagent.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for DocRegistry — CRUD + JSON persistence.
 */
class DocRegistryTest {

    @TempDir
    Path tmpDir;

    private DocRegistry buildRegistry() throws IOException {
        AppProperties props = mock(AppProperties.class);
        when(props.dataDir()).thenReturn(tmpDir.toString());
        DocRegistry reg = new DocRegistry(props);
        reg.init();
        return reg;
    }

    private DocRegistry.DocRegistryEntry entry(String sha, String version) {
        return new DocRegistry.DocRegistryEntry(sha, version, "2026-01-01T00:00:00Z", 3,
                List.of("id1", "id2"), List.of());
    }

    @Test
    @DisplayName("put → findByDocId → 조회 성공")
    void put_and_find() throws IOException {
        DocRegistry reg = buildRegistry();
        reg.put("doc_abc12345", entry("sha1", "v1"));

        Optional<DocRegistry.DocRegistryEntry> result = reg.findByDocId("doc_abc12345");

        assertThat(result).isPresent();
        assertThat(result.get().sha256()).isEqualTo("sha1");
        assertThat(result.get().version()).isEqualTo("v1");
        assertThat(result.get().chunks()).isEqualTo(3);
    }

    @Test
    @DisplayName("remove → findByDocId → empty")
    void remove_then_find_empty() throws IOException {
        DocRegistry reg = buildRegistry();
        reg.put("doc_abc12345", entry("sha1", "v1"));
        reg.remove("doc_abc12345");

        assertThat(reg.findByDocId("doc_abc12345")).isEmpty();
    }

    @Test
    @DisplayName("save → 새 인스턴스 load → 데이터 복원")
    void save_and_reload_persists_data() throws IOException {
        DocRegistry reg = buildRegistry();
        reg.put("doc_abc12345", entry("sha256abc", "latest"));
        reg.save();

        DocRegistry reg2 = buildRegistry();
        assertThat(reg2.findByDocId("doc_abc12345")).isPresent();
        assertThat(reg2.findByDocId("doc_abc12345").get().sha256()).isEqualTo("sha256abc");
    }

    @Test
    @DisplayName("existsBySha256AndVersion — 존재 / 미존재")
    void existsBySha256AndVersion() throws IOException {
        DocRegistry reg = buildRegistry();
        reg.put("doc_abc12345", entry("sha-x", "v1"));

        assertThat(reg.existsBySha256AndVersion("sha-x", "v1")).isTrue();
        assertThat(reg.existsBySha256AndVersion("sha-x", "v2")).isFalse();
        assertThat(reg.existsBySha256AndVersion("other", "v1")).isFalse();
    }

    @Test
    @DisplayName("findStaleDocId — 구버전 docId 탐지")
    void findStaleDocId_detects_old_entry() throws IOException {
        DocRegistry reg = buildRegistry();
        reg.put("report.pdf_aabbccdd", entry("sha-old", "v1"));

        Optional<String> stale = reg.findStaleDocId("report.pdf", "report.pdf_11223344", "v1");

        assertThat(stale).hasValue("report.pdf_aabbccdd");
    }

    @Test
    @DisplayName("findStaleDocId — 동일 docId는 stale 아님")
    void findStaleDocId_ignores_same_docId() throws IOException {
        DocRegistry reg = buildRegistry();
        reg.put("report.pdf_aabbccdd", entry("sha-same", "v1"));

        Optional<String> stale = reg.findStaleDocId("report.pdf", "report.pdf_aabbccdd", "v1");

        assertThat(stale).isEmpty();
    }

    @Test
    @DisplayName("filenameFromDocId — _hash 접미사 제거")
    void filenameFromDocId_strips_hash_suffix() {
        assertThat(DocRegistry.filenameFromDocId("report.pdf_ab12cd34")).isEqualTo("report.pdf");
        assertThat(DocRegistry.filenameFromDocId("nounderscore")).isEqualTo("nounderscore");
    }

    @Test
    @DisplayName("saveQuiet — IOException 발생해도 예외 전파 없음")
    void saveQuiet_does_not_throw() throws IOException {
        AppProperties props = mock(AppProperties.class);
        // Non-writable path to trigger IOException
        Path readonlyDir = tmpDir.resolve("readonly");
        Files.createDirectories(readonlyDir);
        readonlyDir.toFile().setWritable(false);

        when(props.dataDir()).thenReturn(readonlyDir.toString());
        DocRegistry reg = new DocRegistry(props);
        reg.put("doc_abc", entry("sha", "v1"));

        // Must not throw even if save fails
        reg.saveQuiet();
    }
}
