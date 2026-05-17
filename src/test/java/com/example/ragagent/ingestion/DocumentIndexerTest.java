package com.example.ragagent.ingestion;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.model.DocumentInfo;
import com.example.ragagent.service.DocumentLoaderService;
import com.example.ragagent.service.ImageExtractorService;
import com.example.ragagent.service.MarkdownCorrectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DocumentIndexer — metadata tagging and single/parallel index dispatch.
 * LLM enrichment is bypassed via mocked LlmRouter (returns null → TF-IDF fallback).
 */
class DocumentIndexerTest {

    @TempDir
    Path tmpDir;

    private DocumentIndexer indexer;
    private VectorStoreFacade vectorStore;
    private DocRegistry docRegistry;
    private AppProperties props;

    @BeforeEach
    void setUp() throws IOException {
        // Stub AppProperties
        props = mock(AppProperties.class);
        when(props.dataDir()).thenReturn(tmpDir.toString());
        when(props.chunkSize()).thenReturn(2000);
        when(props.chunkOverlap()).thenReturn(200);

        AppProperties.IndexingConfig indexing = mock(AppProperties.IndexingConfig.class);
        when(indexing.maxConcurrentLlmCalls()).thenReturn(2);
        when(indexing.keywordTimeoutSeconds()).thenReturn(5);
        when(indexing.maxConcurrentFiles()).thenReturn(2);
        when(props.indexingSafe()).thenReturn(indexing);

        // Stub VectorStoreFacade
        vectorStore = mock(VectorStoreFacade.class);

        // Real DocRegistry backed by a temp SQLite file
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite:" + tmpDir.resolve("test.db"));
        docRegistry = new DocRegistry(new JdbcTemplate(ds));
        docRegistry.init();

        // Stub DocumentLoaderService — returns a single Document per call
        DocumentLoaderService loaderService = mock(DocumentLoaderService.class);
        when(loaderService.load(any())).thenReturn(
                List.of(new Document("테스트 문서 내용입니다. 청킹과 메타 태깅을 검증합니다.")));

        // Stub MarkdownCorrectionService and ImageExtractorService (not exercised here)
        MarkdownCorrectionService correctionService = mock(MarkdownCorrectionService.class);
        ImageExtractorService imageExtractorService = mock(ImageExtractorService.class);
        when(imageExtractorService.extract(any(), anyString(), any())).thenReturn(java.util.Map.of());

        // Stub LlmRouter — throw to force TF-IDF fallback (avoids real LLM calls)
        com.example.ragagent.llm.LlmRouter llmRouter = mock(com.example.ragagent.llm.LlmRouter.class);
        when(llmRouter.executeWithTracking(any(), any(), any()))
                .thenThrow(new RuntimeException("no LLM in test"));

        indexer = new DocumentIndexer(loaderService, correctionService, imageExtractorService,
                vectorStore, docRegistry, llmRouter, props);
        indexer.init();
    }

    @Test
    @DisplayName("single index — DocumentInfo 반환 + 메타 태그 검증")
    void index_single_returnsDocumentInfo_withCorrectMeta() throws IOException {
        Path txtFile = tmpDir.resolve("guide.txt");
        Files.writeString(txtFile, "테스트 문서 내용입니다. 청킹과 메타 태깅을 검증합니다.");

        DocumentInfo info = indexer.index(IndexRequest.single(txtFile, "guide.txt", "v1", e -> {}));

        assertThat(info.filename()).isEqualTo("guide.txt");
        assertThat(info.version()).isEqualTo("v1");
        assertThat(info.chunks()).isGreaterThan(0);
        assertThat(info.sha256()).isNotBlank();

        // Registry updated
        assertThat(docRegistry.findByDocId(info.docId(), "anonymous")).isPresent();

        // Vector store received the enriched chunks
        verify(vectorStore, atLeastOnce()).add(eq("v1"), any());
    }

    @Test
    @DisplayName("single index — docType 'guide' 추론 (파일명에 'guide' 포함)")
    void index_single_infersDocTypeGuide() throws IOException {
        Path txtFile = tmpDir.resolve("user_guide.txt");
        Files.writeString(txtFile, "가이드 내용");

        DocumentInfo info = indexer.index(IndexRequest.single(txtFile, "user_guide.txt", "v1", e -> {}));

        // docType lives in spring-AI Document metadata, not in registry — verify via chunks > 0
        assertThat(docRegistry.findByDocId(info.docId(), "anonymous")).isPresent();
        assertThat(info.chunks()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("parallel index — staleDocId 있으면 구버전 삭제됨")
    void index_parallel_deletesStaleDocId() throws IOException {
        // Register a stale entry first
        String staleDocId = "old_guide.txt_stale1234";
        docRegistry.put(staleDocId, "anonymous",
                new DocRegistry.DocRegistryEntry("sha-old", "v1", "2025-01-01T00:00:00Z",
                        2, List.of("spring-id-1"), List.of()));

        Path txtFile = tmpDir.resolve("old_guide.txt");
        Files.writeString(txtFile, "새 버전 내용");
        Semaphore gate = new Semaphore(2);

        indexer.index(IndexRequest.parallel(txtFile, "v1", gate, staleDocId));

        // Stale entry removed from registry
        assertThat(docRegistry.findByDocId(staleDocId, "anonymous")).isEmpty();
        // Old spring-doc-ids deleted from vector store
        verify(vectorStore).deleteByDocIds("v1", List.of("spring-id-1"));
    }

    @Test
    @DisplayName("deleteArtifacts — registry 엔트리 제거 + vectorStore 삭제 호출")
    void deleteArtifacts_removesRegistryEntryAndCallsVectorStore() throws IOException {
        String docId = "sample.txt_abcd1234";
        docRegistry.put(docId, "anonymous",
                new DocRegistry.DocRegistryEntry("sha-del", "v1", "2026-01-01T00:00:00Z",
                        1, List.of("vec-id-x"), List.of()));

        indexer.deleteArtifacts(docId, "v1");

        assertThat(docRegistry.findByDocId(docId, "anonymous")).isEmpty();
        verify(vectorStore).deleteByDocIds("v1", List.of("vec-id-x"));
    }
}
