package com.example.ragagent.ingestion;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.exception.IndexingCancelledException;
import com.example.ragagent.model.DocumentInfo;
import com.example.ragagent.service.DocumentLoaderService;
import com.example.ragagent.service.ImageExtractorService;
import com.example.ragagent.service.MarkdownCorrectionService;
import com.example.ragagent.service.TextToMarkdownService;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    private KeywordSearchRepository keywordRepo;
    private AppProperties props;
    private DocumentLoaderService loaderService;

    @BeforeEach
    void setUp() throws IOException {
        // Stub AppProperties
        props = mock(AppProperties.class);
        when(props.dataDir()).thenReturn(tmpDir.toString());
        when(props.chunkSize()).thenReturn(2000);
        when(props.chunkOverlap()).thenReturn(200);
        when(props.embeddingSafe()).thenReturn(
                new AppProperties.EmbeddingConfig(null, null, null, null, 10, 120, true, 0));

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
        loaderService = mock(DocumentLoaderService.class);
        List<Document> stubDocs = List.of(new Document("테스트 문서 내용입니다. 청킹과 메타 태깅을 검증합니다."));
        when(loaderService.load(any())).thenReturn(stubDocs);
        when(loaderService.load(any(), any())).thenReturn(stubDocs);
        // TXT path (structured MD) feeds loadFromMarkdown
        when(loaderService.loadFromMarkdown(any())).thenReturn(stubDocs);

        // Stub MarkdownCorrectionService / TextToMarkdownService — pass content through unchanged
        MarkdownCorrectionService correctionService = mock(MarkdownCorrectionService.class);
        when(correctionService.correct(any(), any(), any(), anyBoolean(), anyBoolean(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        TextToMarkdownService textToMarkdownService = mock(TextToMarkdownService.class);
        when(textToMarkdownService.convert(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        ImageExtractorService imageExtractorService = mock(ImageExtractorService.class);
        when(imageExtractorService.extract(any(), anyString(), any())).thenReturn(java.util.Map.of());

        // Stub LlmRouter — throw to force TF-IDF fallback (avoids real LLM calls)
        com.example.ragagent.llm.LlmRouter llmRouter = mock(com.example.ragagent.llm.LlmRouter.class);
        when(llmRouter.executeWithTracking(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("no LLM in test"));

        keywordRepo = new KeywordSearchRepository(new JdbcTemplate(ds));
        keywordRepo.init();

        ChunkSplitter chunkSplitter = new ChunkSplitter();
        KeywordExtractor keywordExtractor = new KeywordExtractor(llmRouter, props);

        indexer = new DocumentIndexer(loaderService, correctionService, textToMarkdownService,
                imageExtractorService, vectorStore, docRegistry, keywordRepo, chunkSplitter, keywordExtractor, props);
        indexer.init();
    }

    @Test
    @DisplayName("single index — DocumentInfo 반환 + 메타 태그 검증")
    void index_single_returnsDocumentInfo_withCorrectMeta() throws IOException {
        Path txtFile = tmpDir.resolve("guide.txt");
        Files.writeString(txtFile, "테스트 문서 내용입니다. 청킹과 메타 태깅을 검증합니다.");

        DocumentInfo info = indexer.index(IndexRequest.single(txtFile, "guide.txt", "v1", "anonymous", e -> {}));

        assertThat(info.filename()).isEqualTo("guide.txt");
        assertThat(info.version()).isEqualTo("v1");
        assertThat(info.chunks()).isGreaterThan(0);
        assertThat(info.sha256()).isNotBlank();

        // Registry updated
        assertThat(docRegistry.findByDocId(info.docId(), DocRegistry.SHARED)).isPresent();

        // Vector store received the enriched chunks
        verify(vectorStore, atLeastOnce()).add(eq(DocRegistry.SHARED), eq("v1"), any());
    }

    @Test
    @DisplayName("TXT 업로드 — DOCX처럼 구조화 MD가 converted/ 에 저장되고 MD 경로로 인덱싱")
    void index_txt_convertsToMarkdown() throws IOException {
        Path txt = tmpDir.resolve("plain.txt");
        Files.writeString(txt, "제목 없는 평문입니다. 구조화 대상 내용.");

        DocumentInfo info = indexer.index(IndexRequest.single(txt, "plain.txt", "v1", "anonymous", e -> {}));

        // TXT는 이제 DOCX처럼 MD로 변환되어 저장된다 (plain load 경로가 아님을 입증)
        Path md = tmpDir.resolve("converted").resolve(info.docId() + ".md");
        assertThat(md).exists();
        assertThat(Files.readString(md)).contains("구조화 대상 내용");
        verify(vectorStore, atLeastOnce()).add(eq(DocRegistry.SHARED), eq("v1"), any());
    }

    @Test
    @DisplayName("single index — docType 'guide' 추론 (파일명에 'guide' 포함)")
    void index_single_infersDocTypeGuide() throws IOException {
        Path txtFile = tmpDir.resolve("user_guide.txt");
        Files.writeString(txtFile, "가이드 내용");

        DocumentInfo info = indexer.index(IndexRequest.single(txtFile, "user_guide.txt", "v1", "anonymous", e -> {}));

        // docType lives in spring-AI Document metadata, not in registry — verify via chunks > 0
        assertThat(docRegistry.findByDocId(info.docId(), DocRegistry.SHARED)).isPresent();
        assertThat(info.chunks()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("parallel index — staleDocId 있으면 구버전 삭제됨")
    void index_parallel_deletesStaleDocId() throws IOException {
        // Register a stale entry first
        String staleDocId = "old_guide.txt_stale1234";
        docRegistry.put(staleDocId, DocRegistry.SHARED,
                new DocRegistry.DocRegistryEntry("sha-old", "v1", "2025-01-01T00:00:00Z",
                        2, List.of("spring-id-1"), List.of()));

        Path txtFile = tmpDir.resolve("old_guide.txt");
        Files.writeString(txtFile, "새 버전 내용");
        Semaphore gate = new Semaphore(2);

        indexer.index(IndexRequest.parallel(txtFile, "v1", DocRegistry.SHARED, gate, staleDocId));

        // Stale entry removed from registry
        assertThat(docRegistry.findByDocId(staleDocId, DocRegistry.SHARED)).isEmpty();
        // Old spring-doc-ids deleted from vector store
        verify(vectorStore).deleteByDocIds(DocRegistry.SHARED, "v1", List.of("spring-id-1"));
    }

    @Test
    @DisplayName("parallel index(sync 갱신) — staleDocId의 태그가 신규 docId로 복원됨")
    void index_parallel_restoresTagsFromStaleDoc() throws IOException {
        // 1) 최초 업로드(대화형 single) — 태그 [alpha, beta] 명시
        Path file = tmpDir.resolve("notes.txt");
        Files.writeString(file, "최초 내용 버전 A 입니다.");
        DocumentInfo first = indexer.index(IndexRequest.single(
                file, "notes.txt", "v1", "anonymous", List.of("alpha", "beta"), e -> {}));
        // FTS(검색/복원 소스)에는 정규화된 형태로 저장됨
        assertThat(keywordRepo.tagsByDocIds(List.of(first.docId())).get(first.docId()))
                .containsExactlyInAnyOrder("alpha", "beta");

        // 2) 내용 변경 후 동기화 갱신 — 태그 입력 없이 staleDocId만 전달
        Files.writeString(file, "완전히 다른 내용 버전 B 입니다. 문장이 바뀌었습니다.");
        Semaphore gate = new Semaphore(2);
        DocumentInfo updated = indexer.index(IndexRequest.parallel(
                file, "v1", DocRegistry.SHARED, gate, first.docId()));

        // 내용 sha 변경으로 docId가 달라져도 태그가 이전 문서에서 복원됨
        assertThat(updated.docId()).isNotEqualTo(first.docId());
        assertThat(updated.tags()).containsExactlyInAnyOrder("alpha", "beta");
        assertThat(keywordRepo.tagsByDocIds(List.of(updated.docId())).get(updated.docId()))
                .containsExactlyInAnyOrder("alpha", "beta");
    }

    @Test
    @DisplayName("single 재업로드(태그 미입력) — 자동복원 안 함(명시적 clear 존중)")
    void index_single_doesNotAutoRestoreTags() throws IOException {
        Path file = tmpDir.resolve("memo.txt");
        Files.writeString(file, "태그 clear 검증용 내용.");
        DocumentInfo first = indexer.index(IndexRequest.single(
                file, "memo.txt", "v1", "anonymous", List.of("keep"), e -> {}));
        assertThat(first.tags()).containsExactly("keep");

        // 동일 내용/동일 docId 재업로드, 태그 비움 → 대화형 경로는 복원하지 않음
        DocumentInfo second = indexer.index(IndexRequest.single(
                file, "memo.txt", "v1", "anonymous", List.of(), e -> {}));
        assertThat(second.docId()).isEqualTo(first.docId());
        assertThat(second.tags()).isEmpty();
    }

    @Test
    @DisplayName("deleteArtifacts — registry 엔트리 제거 + vectorStore 삭제 호출")
    void deleteArtifacts_removesRegistryEntryAndCallsVectorStore() throws IOException {
        String docId = "sample.txt_abcd1234";
        docRegistry.put(docId, "anonymous",
                new DocRegistry.DocRegistryEntry("sha-del", "v1", "2026-01-01T00:00:00Z",
                        1, List.of("vec-id-x"), List.of()));

        indexer.deleteArtifacts("anonymous", docId, "v1");

        assertThat(docRegistry.findByDocId(docId, "anonymous")).isEmpty();
        verify(vectorStore).deleteByDocIds("anonymous", "v1", List.of("vec-id-x"));
    }

    @Test
    @DisplayName("syncDirectory — 취소(인터럽트) 시 IndexingCancelledException 던지고 그때까지 완료된 파일은 registry에 보존(§6.16.1)")
    void syncDirectory_cancelled_throwsAndSavesPartialRegistry() throws Exception {
        Path docsDir = tmpDir.resolve("documents");
        Files.createDirectories(docsDir);
        Files.writeString(docsDir.resolve("fast.txt"), "빠르게 끝나는 문서 내용입니다.");
        Files.writeString(docsDir.resolve("slow.txt"), "SLOW_MARKER 오래 걸리는 문서 내용입니다.");

        CountDownLatch slowFileStarted = new CountDownLatch(1);
        CountDownLatch fastFileDone = new CountDownLatch(1);
        // correctionService/textToMarkdownService echo their input unchanged (stubbed in setUp),
        // so the markdown reaching loadFromMarkdown() is exactly the original file content —
        // enough to tell the two files apart and block only the "slow" one.
        when(loaderService.loadFromMarkdown(any())).thenAnswer(inv -> {
            String md = inv.getArgument(0);
            if (md.contains("SLOW_MARKER")) {
                slowFileStarted.countDown();
                Thread.sleep(30_000); // interrupted well before this elapses
            }
            return List.of(new Document(md));
        });

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread syncThread = new Thread(() -> {
            try {
                indexer.syncDirectory("anonymous", "v1", docsDir, evt -> {
                    if ("sync_file_done".equals(evt.stage()) && "fast.txt".equals(evt.filename())) {
                        fastFileDone.countDown();
                    }
                });
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        syncThread.start();

        assertThat(slowFileStarted.await(5, TimeUnit.SECONDS))
                .as("slow.txt should have started processing before cancellation")
                .isTrue();
        // Wait for fast.txt to fully finish (including docRegistry.put()) before cancelling,
        // so the assertion below observes genuinely completed-before-cancel work rather than
        // racing shutdownNow() against fast.txt's own in-flight keyword extraction.
        assertThat(fastFileDone.await(5, TimeUnit.SECONDS))
                .as("fast.txt should have completed before cancellation")
                .isTrue();
        syncThread.interrupt();
        syncThread.join(10_000);

        assertThat(syncThread.isAlive()).as("worker must terminate, no zombie thread").isFalse();
        assertThat(thrown.get()).isInstanceOf(IndexingCancelledException.class);
        // fast.txt had already completed (and been registered in-memory) by the time the
        // interrupt landed on slow.txt; the cancel path must still persist that partial work.
        assertThat(docRegistry.docIds(DocRegistry.SHARED)).hasSize(1);
    }
}
