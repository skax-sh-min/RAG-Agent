package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.ingestion.DocRegistry;
import com.example.ragagent.ingestion.DocumentIndexer;
import com.example.ragagent.ingestion.IndexRequest;
import com.example.ragagent.ingestion.KeywordSearchRepository;
import com.example.ragagent.ingestion.VectorStoreFacade;
import com.example.ragagent.model.DocumentInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * QA — RagService orchestration logic (EDIT.md #1)
 *
 * Most methods are thin one-line delegation to DocumentIndexer/VectorStoreFacade/
 * KeywordSearchRepository (not worth testing beyond compile-time wiring). This focuses on
 * the methods with real logic: listDocuments()'s sort+tag-merge, listVersions()'s
 * distinct+"latest"+sort, the indexDocument() overload chain's defaults, and
 * deleteDocument()'s SHARED-scope + save() ordering.
 */
class RagServiceTest {

    private DocumentIndexer indexer;
    private DocRegistry docRegistry;
    private VectorStoreFacade vectorStore;
    private KeywordSearchRepository keywordRepo;
    private RagService service;

    @BeforeEach
    void setUp() {
        indexer = mock(DocumentIndexer.class);
        docRegistry = mock(DocRegistry.class);
        vectorStore = mock(VectorStoreFacade.class);
        keywordRepo = mock(KeywordSearchRepository.class);
        AppProperties props = mock(AppProperties.class);
        service = new RagService(indexer, docRegistry, vectorStore, keywordRepo, props);
    }

    private static DocRegistry.DocRegistryEntry entry(String sha, String version, String indexedAt, int chunks) {
        return new DocRegistry.DocRegistryEntry(sha, version, indexedAt, chunks, List.of("doc1"), List.of());
    }

    @Test
    @DisplayName("listDocuments — indexedAt 최신순 정렬 + 태그 병합(없으면 빈 리스트)")
    void listDocuments_sortsByIndexedAtDescendingAndMergesTags() {
        Set<Map.Entry<String, DocRegistry.DocRegistryEntry>> entries = new LinkedHashSet<>(List.of(
                new AbstractMap.SimpleEntry<>("old.pdf_aaa", entry("aaa", "latest", "2026-01-01T00:00:00Z", 3)),
                new AbstractMap.SimpleEntry<>("new.pdf_bbb", entry("bbb", "latest", "2026-06-01T00:00:00Z", 5))
        ));
        when(docRegistry.entries(DocRegistry.SHARED)).thenReturn(entries);
        when(keywordRepo.tagsByDocIds(anyList()))
                .thenReturn(Map.of("new.pdf_bbb", List.of("faq")));

        List<DocumentInfo> result = service.listDocuments("u1");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).docId()).isEqualTo("new.pdf_bbb"); // 최신이 먼저
        assertThat(result.get(0).tags()).containsExactly("faq");
        assertThat(result.get(1).docId()).isEqualTo("old.pdf_aaa");
        assertThat(result.get(1).tags()).isEmpty(); // tagsByDocIds에 없으면 빈 리스트
    }

    @Test
    @DisplayName("listVersions — 중복 제거 + 공백 버전 무시 + latest 항상 포함 + 정렬")
    void listVersions_distinctSortedAndAlwaysIncludesLatest() {
        Set<Map.Entry<String, DocRegistry.DocRegistryEntry>> entries = new LinkedHashSet<>(List.of(
                new AbstractMap.SimpleEntry<>("a", entry("a", "2.0", "t", 1)),
                new AbstractMap.SimpleEntry<>("b", entry("b", "1.0", "t", 1)),
                new AbstractMap.SimpleEntry<>("c", entry("c", "1.0", "t", 1)), // 중복
                new AbstractMap.SimpleEntry<>("d", entry("d", "", "t", 1))      // 공백 버전 무시
        ));
        when(docRegistry.entries(DocRegistry.SHARED)).thenReturn(entries);

        List<String> versions = service.listVersions();

        assertThat(versions).containsExactly("1.0", "2.0", "latest");
    }

    @Test
    @DisplayName("indexDocument (최소 오버로드) — tags 빈 리스트, addImageDescriptions/addHeadingNumbers=false 기본값, save() 호출")
    void indexDocument_simplestOverload_appliesDefaultsAndSaves() throws Exception {
        DocumentInfo canned = new DocumentInfo("doc1", "manual.pdf", "latest", 1, "t", "sha", List.of(), List.of());
        when(indexer.index(any())).thenReturn(canned);
        Path path = Path.of("/tmp/manual.pdf");

        DocumentInfo result = service.indexDocument("u1", path, "latest");

        assertThat(result).isEqualTo(canned);
        ArgumentCaptor<IndexRequest> captor = ArgumentCaptor.forClass(IndexRequest.class);
        verify(indexer).index(captor.capture());
        IndexRequest req = captor.getValue();
        assertThat(req.filename()).isEqualTo("manual.pdf");
        assertThat(req.ownerId()).isEqualTo("u1");
        assertThat(req.version()).isEqualTo("latest");
        assertThat(req.tags()).isEmpty();
        assertThat(req.addImageDescriptions()).isFalse();
        assertThat(req.addHeadingNumbers()).isFalse();
        verify(docRegistry).save();
    }

    @Test
    @DisplayName("deleteDocument — DocRegistry.SHARED 스코프로 삭제 후 save() 호출")
    void deleteDocument_deletesUnderSharedScopeThenSaves() throws Exception {
        service.deleteDocument("u1", "doc1", "latest");

        verify(indexer).deleteArtifacts(DocRegistry.SHARED, "doc1", "latest");
        verify(docRegistry).save();
    }

    @Test
    @DisplayName("deleteDocument — data/documents 의 원본 파일을 backup/ 폴더로 이동하고 파일명에 삭제 시각을 붙인다")
    void deleteDocument_archivesSourceFileToBackupFolder(@TempDir Path tmpDir) throws Exception {
        AppProperties props = mock(AppProperties.class);
        when(props.dataDir()).thenReturn(tmpDir.toString());
        RagService svc = new RagService(indexer, docRegistry, vectorStore, keywordRepo, props);

        Path documentsDir = tmpDir.resolve("documents");
        Files.createDirectories(documentsDir);
        Path source = documentsDir.resolve("manual.pdf");
        Files.writeString(source, "content");

        svc.deleteDocument("u1", "manual.pdf_a1b2c3d4", "latest");

        assertThat(source).doesNotExist();
        Path backupDir = documentsDir.resolve("backup");
        assertThat(backupDir).isDirectory();
        try (var files = Files.list(backupDir)) {
            List<Path> backedUp = files.toList();
            assertThat(backedUp).hasSize(1);
            assertThat(backedUp.get(0).getFileName().toString()).matches("manual_\\d{8}_\\d{6}\\.pdf");
            assertThat(Files.readString(backedUp.get(0))).isEqualTo("content");
        }
    }

    @Test
    @DisplayName("deleteDocument — 원본 파일이 이미 없으면 backup/ 폴더를 만들지 않고 조용히 넘어간다")
    void deleteDocument_sourceFileAlreadyMissing_noBackupFolderCreated(@TempDir Path tmpDir) throws Exception {
        AppProperties props = mock(AppProperties.class);
        when(props.dataDir()).thenReturn(tmpDir.toString());
        RagService svc = new RagService(indexer, docRegistry, vectorStore, keywordRepo, props);

        svc.deleteDocument("u1", "missing.pdf_a1b2c3d4", "latest");

        assertThat(tmpDir.resolve("documents").resolve("backup")).doesNotExist();
    }

    @Test
    @DisplayName("findDocument — 등록된 문서: docId/filename/tags 포함")
    void findDocument_present_includesTags() {
        when(docRegistry.findByDocId("doc1", DocRegistry.SHARED))
                .thenReturn(java.util.Optional.of(entry("sha", "latest", "2026-01-01T00:00:00Z", 3)));
        when(keywordRepo.tagsByDocIds(List.of("doc1"))).thenReturn(Map.of("doc1", List.of("faq", "guide")));

        Optional<DocumentInfo> result = service.findDocument("u1", "doc1");

        assertThat(result).isPresent();
        assertThat(result.get().docId()).isEqualTo("doc1");
        assertThat(result.get().tags()).containsExactly("faq", "guide");
    }

    @Test
    @DisplayName("findDocument — 미등록 문서: Optional.empty()")
    void findDocument_absent_returnsEmpty() {
        when(docRegistry.findByDocId("missing", DocRegistry.SHARED)).thenReturn(java.util.Optional.empty());

        assertThat(service.findDocument("u1", "missing")).isEmpty();
    }

    @Test
    @DisplayName("updateDocumentTags — 정규화된 태그로 벡터스토어+FTS 모두 갱신하고 갱신된 DocumentInfo 반환")
    void updateDocumentTags_updatesVectorStoreAndFts() {
        DocRegistry.DocRegistryEntry existing = entry("sha", "v2", "2026-01-01T00:00:00Z", 3);
        when(docRegistry.findByDocId("doc1", DocRegistry.SHARED)).thenReturn(java.util.Optional.of(existing));
        when(keywordRepo.updateDocTags("doc1", "faq,guide")).thenReturn(3);

        DocumentInfo result = service.updateDocumentTags("u1", "doc1", List.of(" FAQ ", "Guide", "faq"));

        assertThat(result.docId()).isEqualTo("doc1");
        assertThat(result.tags()).containsExactly("faq", "guide"); // normalized: lowercase + trim + dedupe
        verify(vectorStore).updateTags(DocRegistry.SHARED, "v2", existing.springDocIds(), "faq,guide");
        verify(keywordRepo).updateDocTags("doc1", "faq,guide");
    }

    @Test
    @DisplayName("updateDocumentTags — chunk_fts에 매칭되는 행이 0개(고아 registry entry) → DocumentIndexingException")
    void updateDocumentTags_noFtsRowsUpdated_throwsIndexingException() {
        DocRegistry.DocRegistryEntry existing = entry("sha", "v2", "2026-01-01T00:00:00Z", 3);
        when(docRegistry.findByDocId("doc1", DocRegistry.SHARED)).thenReturn(java.util.Optional.of(existing));
        when(keywordRepo.updateDocTags("doc1", "faq")).thenReturn(0);

        assertThatThrownBy(() -> service.updateDocumentTags("u1", "doc1", List.of("faq")))
                .isInstanceOf(com.example.ragagent.exception.DocumentIndexingException.class);
    }

    @Test
    @DisplayName("updateDocumentTags — 존재하지 않는 docId → IllegalArgumentException, 부수효과 없음")
    void updateDocumentTags_missingDoc_throwsAndNoSideEffects() {
        when(docRegistry.findByDocId("missing", DocRegistry.SHARED)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.updateDocumentTags("u1", "missing", List.of("x")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(vectorStore);
    }

    @Test
    @DisplayName("updateDocumentTags — 태그 정책 위반(최대 개수 초과) → IllegalArgumentException, 부수효과 없음")
    void updateDocumentTags_policyViolation_throwsBeforeLookup() {
        List<String> tooMany = java.util.stream.IntStream.range(0, 11)
                .mapToObj(i -> "tag" + i).toList();

        assertThatThrownBy(() -> service.updateDocumentTags("u1", "doc1", tooMany))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(vectorStore, keywordRepo);
    }

    @Test
    @DisplayName("updateDisplayName — UPDATE가 1행에 영향 → 갱신 후 재조회한 DocumentInfo(실제 DB 상태)를 반환한다")
    void updateDisplayName_updatesAndReturnsRefetchedEntry() {
        when(docRegistry.updateDisplayName("doc1", DocRegistry.SHARED, "별칭")).thenReturn(1);
        DocRegistry.DocRegistryEntry updated = new DocRegistry.DocRegistryEntry(
                "sha", "v2", "2026-01-01T00:00:00Z", 3, List.of("doc1"), List.of(), null, "별칭");
        when(docRegistry.findByDocId("doc1", DocRegistry.SHARED)).thenReturn(java.util.Optional.of(updated));

        DocumentInfo result = service.updateDisplayName("u1", "doc1", "  별칭  ");

        assertThat(result.displayName()).isEqualTo("별칭");
        verify(docRegistry).updateDisplayName("doc1", DocRegistry.SHARED, "별칭");
    }

    @Test
    @DisplayName("updateDisplayName — UPDATE가 0행에 영향(존재하지 않는 문서) → IllegalArgumentException, 재조회하지 않는다")
    void updateDisplayName_zeroRowsAffected_throwsWithoutRefetch() {
        // Regression: the pre-fix version pre-checked existence with a SELECT, then returned the
        // requested name unconditionally without ever checking whether the UPDATE itself affected
        // any rows — a write that silently no-oped still reported success with the new name, which
        // reverted on the next page load. The UPDATE's own row count is now the sole existence check.
        when(docRegistry.updateDisplayName("missing", DocRegistry.SHARED, "별칭")).thenReturn(0);

        assertThatThrownBy(() -> service.updateDisplayName("u1", "missing", "별칭"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(docRegistry, never()).findByDocId(anyString(), anyString());
    }

    @Test
    @DisplayName("updateDisplayName — 공백만 있는 이름은 null(해제)로 저장되고 반환된다")
    void updateDisplayName_blankClearsOverride() {
        when(docRegistry.updateDisplayName("doc1", DocRegistry.SHARED, null)).thenReturn(1);
        DocRegistry.DocRegistryEntry cleared = new DocRegistry.DocRegistryEntry(
                "sha", "v2", "2026-01-01T00:00:00Z", 3, List.of("doc1"), List.of(), null, null);
        when(docRegistry.findByDocId("doc1", DocRegistry.SHARED)).thenReturn(java.util.Optional.of(cleared));

        DocumentInfo result = service.updateDisplayName("u1", "doc1", "   ");

        assertThat(result.displayName()).isNull();
        verify(docRegistry).updateDisplayName("doc1", DocRegistry.SHARED, null);
    }

    @Test
    @DisplayName("updateDisplayName — 200자 초과 → IllegalArgumentException, DB 접근 없음")
    void updateDisplayName_tooLong_throwsBeforeAnyDbCall() {
        String tooLong = "a".repeat(201);

        assertThatThrownBy(() -> service.updateDisplayName("u1", "doc1", tooLong))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(docRegistry);
    }

    @Test
    @DisplayName("isSupportedExtension — 대소문자 무관 지원 확장자 판별")
    void isSupportedExtension_caseInsensitive() {
        assertThat(RagService.isSupportedExtension("manual.PDF")).isTrue();
        assertThat(RagService.isSupportedExtension("manual.exe")).isFalse();
        assertThat(RagService.isSupportedExtension(null)).isFalse();
    }
}
