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
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
    private KeywordSearchRepository keywordRepo;
    private RagService service;

    @BeforeEach
    void setUp() {
        indexer = mock(DocumentIndexer.class);
        docRegistry = mock(DocRegistry.class);
        VectorStoreFacade vectorStore = mock(VectorStoreFacade.class);
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
    @DisplayName("isSupportedExtension — 대소문자 무관 지원 확장자 판별")
    void isSupportedExtension_caseInsensitive() {
        assertThat(RagService.isSupportedExtension("manual.PDF")).isTrue();
        assertThat(RagService.isSupportedExtension("manual.exe")).isFalse();
        assertThat(RagService.isSupportedExtension(null)).isFalse();
    }
}
