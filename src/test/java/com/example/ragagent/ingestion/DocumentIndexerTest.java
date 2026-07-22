package com.example.ragagent.ingestion;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.exception.IndexingCancelledException;
import com.example.ragagent.model.DocumentInfo;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.service.DocumentLoaderService;
import com.example.ragagent.service.DocxAnnotationShapeMerger;
import com.example.ragagent.service.DocxToMarkdownConverter;
import com.example.ragagent.service.ImageExtractorService;
import com.example.ragagent.service.MarkdownCorrectionService;
import com.example.ragagent.service.PdfImageExtractor;
import com.example.ragagent.service.PdfToMarkdownConverter;
import com.example.ragagent.service.PptxImageExtractor;
import com.example.ragagent.service.PptxToMarkdownConverter;
import com.example.ragagent.service.TextToMarkdownService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private MarkdownCorrectionService correctionService;

    @BeforeEach
    void setUp() throws IOException {
        // Stub AppProperties
        props = mock(AppProperties.class);
        when(props.dataDir()).thenReturn(tmpDir.toString());
        when(props.chunkSizeSafe()).thenReturn(2000);
        when(props.chunkOverlapSafe()).thenReturn(200);
        when(props.embeddingSafe()).thenReturn(
                new AppProperties.EmbeddingConfig(null, null, null, null, 10, 120, true, 0, List.of(), 1));

        AppProperties.IndexingConfig indexing = mock(AppProperties.IndexingConfig.class);
        when(indexing.maxConcurrentLlmCalls()).thenReturn(2);
        when(indexing.keywordTimeoutSeconds()).thenReturn(5);
        when(indexing.maxConcurrentFiles()).thenReturn(2);
        when(props.indexingSafe()).thenReturn(indexing);
        when(props.pptxImageSafe()).thenReturn(new AppProperties.PptxShapeExtractionConfig(30.0, 15.0, true, false));
        when(props.docxImageSafe()).thenReturn(new AppProperties.DocxShapeExtractionConfig(false));

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
        // TXT path (structured MD) feeds loadFromMarkdown. reindexFromMd() calls the 2-arg
        // (isPptx) overload — stub both so the shared default covers live-indexing (1-arg) and
        // reindex (2-arg) call sites alike.
        when(loaderService.loadFromMarkdown(any())).thenReturn(stubDocs);
        when(loaderService.loadFromMarkdown(any(), anyBoolean())).thenReturn(stubDocs);

        // Stub MarkdownCorrectionService / TextToMarkdownService — pass content through unchanged
        correctionService = mock(MarkdownCorrectionService.class);
        when(correctionService.correct(any(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        // reapplyHeadingNumbers delegates to a real instance (no LLM call in that method, so a
        // never-invoked LlmRouter mock is fine) — it's a no-op unless the MD already has a
        // numbered heading, so this is safe as the shared default for every test while still
        // letting reindex-renumbering tests exercise genuine behavior.
        when(props.llmSafe()).thenReturn(new AppProperties.LlmConfig(
                java.util.List.of(), 2, 10, 180, "COST_FIRST", 0.6, 3, 20, 0.0, 0.1, 8000, true));
        MarkdownCorrectionService realCorrectionForRenumber =
                new MarkdownCorrectionService(mock(com.example.ragagent.llm.LlmRouter.class), props);
        when(correctionService.reapplyHeadingNumbers(any()))
                .thenAnswer(inv -> realCorrectionForRenumber.reapplyHeadingNumbers(inv.getArgument(0)));
        // postProcess (also no LLM call) — same real-instance delegation as reapplyHeadingNumbers above.
        when(correctionService.postProcess(any()))
                .thenAnswer(inv -> realCorrectionForRenumber.postProcess(inv.getArgument(0)));
        TextToMarkdownService textToMarkdownService = mock(TextToMarkdownService.class);
        when(textToMarkdownService.convert(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        ImageExtractorService imageExtractorService = mock(ImageExtractorService.class);
        // DocumentIndexer only ever calls the 4-arg (onProgress) overload (scanned-PDF branch) —
        // stubbing the 3-arg overload here was a no-op that happened to pass anyway because
        // Mockito's default answer for an unstubbed Map-returning method is already an empty map.
        when(imageExtractorService.extract(any(), anyString(), any(), any())).thenReturn(java.util.Map.of());

        // Stub LlmRouter — throw to force TF-IDF fallback (avoids real LLM calls)
        com.example.ragagent.llm.LlmRouter llmRouter = mock(com.example.ragagent.llm.LlmRouter.class);
        when(llmRouter.executeWithTracking(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("no LLM in test"));

        keywordRepo = new KeywordSearchRepository(new JdbcTemplate(ds));
        keywordRepo.init();

        ChunkSplitter chunkSplitter = new ChunkSplitter();
        KeywordExtractor keywordExtractor = new KeywordExtractor(llmRouter, props);

        // Real, dependency-free converters — pptx/pdf tests below re-stub loaderService's
        // loadFromMarkdown()/loadPdfPagesForConversion() to delegate to a real DocumentLoaderService
        // where they need genuine [페이지:N]/heading parsing instead of the generic stubDocs echo.
        indexer = new DocumentIndexer(loaderService, correctionService, textToMarkdownService,
                new PptxToMarkdownConverter(new PptxImageExtractor(props), props),
                new PdfToMarkdownConverter(new PdfImageExtractor()),
                imageExtractorService, vectorStore, docRegistry, keywordRepo, chunkSplitter, keywordExtractor, props);
        indexer.init();
    }

    private DocumentLoaderService realLoader() {
        return new DocumentLoaderService(
                new DocxToMarkdownConverter(Optional.empty(), Optional.empty(), props,
                        new DocxAnnotationShapeMerger(props)),
                Optional.empty());
    }

    private void writeMinimalPptx(Path path, String title) throws IOException {
        try (XMLSlideShow pptx = new XMLSlideShow()) {
            XSLFSlide slide = pptx.createSlide();
            XSLFTextBox box = slide.createTextBox();
            box.setPlaceholder(Placeholder.TITLE);
            box.setText(title);
            try (OutputStream out = Files.newOutputStream(path)) {
                pptx.write(out);
            }
        }
    }

    private void writeTextPdf(Path path, String text) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            doc.save(path.toFile());
        }
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
        verify(vectorStore, atLeastOnce()).add(eq(DocRegistry.SHARED), eq("v1"), any(), any());
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
        verify(vectorStore, atLeastOnce()).add(eq(DocRegistry.SHARED), eq("v1"), any(), any());
    }

    @Test
    @DisplayName("PPTX 업로드 — 슬라이드가 MD로 변환되어 converted/ 에 저장되고 슬라이드 제목이 헤딩으로 반영된다")
    void index_pptx_convertsToMarkdownWithSlideHeading() throws IOException {
        // Re-stub loadFromMarkdown to genuinely parse — the shared @BeforeEach stub just echoes
        // a canned generic Document, which can't prove [페이지:N]/## 마커가 실제로 파싱되는지 증명 못 함.
        DocumentLoaderService realLoader = realLoader();
        // PPTX indexing calls the 2-arg (isPptx) overload — delegate both args through so the
        // real loader genuinely parses [페이지:N]/## markers instead of echoing the generic stub.
        when(loaderService.loadFromMarkdown(anyString(), anyBoolean()))
                .thenAnswer(inv -> realLoader.loadFromMarkdown(inv.getArgument(0), inv.getArgument(1)));

        Path pptxFile = tmpDir.resolve("deck.pptx");
        writeMinimalPptx(pptxFile, "개요");

        DocumentInfo info = indexer.index(IndexRequest.single(pptxFile, "deck.pptx", "v1", "anonymous", e -> {}));

        Path md = tmpDir.resolve("converted").resolve(info.docId() + ".md");
        assertThat(Files.exists(md)).isTrue();
        String mdContent = Files.readString(md);
        assertThat(mdContent).contains("[페이지: 1]").contains("## 개요");
        assertThat(info.chunks()).isGreaterThan(0);
        verify(vectorStore, atLeastOnce()).add(eq(DocRegistry.SHARED), eq("v1"), any(), any());
    }

    @Test
    @DisplayName("PPTX 업로드 — 슬라이드 이미지가 converted/ 의 MD 본문에 [이미지: ...] 인라인 마커로 남는다 (DOCX와 동일)")
    void index_pptx_inlinesImageMarkerInMarkdown() throws IOException {
        DocumentLoaderService realLoader = realLoader();
        when(loaderService.loadFromMarkdown(anyString(), anyBoolean()))
                .thenAnswer(inv -> realLoader.loadFromMarkdown(inv.getArgument(0), inv.getArgument(1)));

        Path pptxFile = tmpDir.resolve("deck-with-image.pptx");
        try (XMLSlideShow pptx = new XMLSlideShow()) {
            byte[] fakePng = "fake-png-bytes".getBytes();
            XSLFPictureData pd = pptx.addPicture(fakePng, PictureData.PictureType.PNG);
            XSLFSlide slide = pptx.createSlide();
            XSLFTextBox title = slide.createTextBox();
            title.setPlaceholder(Placeholder.TITLE);
            title.setText("다이어그램");
            slide.createPicture(pd);
            try (OutputStream out = Files.newOutputStream(pptxFile)) {
                pptx.write(out);
            }
        }

        DocumentInfo info = indexer.index(IndexRequest.single(pptxFile, "deck-with-image.pptx", "v1", "anonymous", e -> {}));

        Path md = tmpDir.resolve("converted").resolve(info.docId() + ".md");
        String mdContent = Files.readString(md);
        assertThat(mdContent).containsPattern("\\[이미지: images/[^\\]]+\\.png]");
        assertThat(info.chunks()).isGreaterThan(0);
    }

    @Test
    @DisplayName("PPTX 업로드 — addHeadingNumbers가 체크되어 있어도 소제목 번호를 생성하지 않는다")
    void index_pptx_ignoresAddHeadingNumbersFlag() throws IOException {
        Path pptxFile = tmpDir.resolve("deck-numbered.pptx");
        writeMinimalPptx(pptxFile, "개요");

        indexer.index(IndexRequest.single(pptxFile, "deck-numbered.pptx", "v1", "anonymous",
                List.of(), false, true, e -> {}));

        // correctionService.correct()의 5번째 인자(addHeadingNumbers)는 요청값(true)과 무관하게
        // 항상 false로 전달되어야 한다 — PPTX 헤딩은 슬라이드 제목 라벨이지 문서 목차가 아니므로.
        // 6번째 인자(groupByPage)는 PPTX이므로 항상 true — [페이지: N] 마커 단위로만 교정 섹션을 묶는다.
        verify(correctionService).correct(any(), any(), any(), eq(false), eq(false), eq(true), any());
    }

    @Test
    @DisplayName("PDF 업로드(스캔 아님) — 페이지가 MD로 변환되어 converted/ 에 저장되고 [페이지: N] 마커가 반영된다(합성 헤딩 없음)")
    void index_nonScannedPdf_convertsToMarkdownWithPageMarker() throws IOException {
        DocumentLoaderService realLoader = realLoader();
        // Non-scanned PDF indexing calls the 2-arg (skipChapterNumbers) overload — delegate both
        // args through so the real loader genuinely parses the [페이지:N] markers.
        when(loaderService.loadFromMarkdown(anyString(), anyBoolean()))
                .thenAnswer(inv -> realLoader.loadFromMarkdown(inv.getArgument(0), inv.getArgument(1)));
        when(loaderService.loadPdfPagesForConversion(any()))
                .thenAnswer(inv -> realLoader.loadPdfPagesForConversion(inv.getArgument(0)));

        Path pdfFile = tmpDir.resolve("report.pdf");
        writeTextPdf(pdfFile, "This is real extractable text on a non-scanned PDF page, "
                + "long enough on its own to avoid the scanned-document heuristic.");

        DocumentInfo info = indexer.index(IndexRequest.single(pdfFile, "report.pdf", "v1", "anonymous", e -> {}));

        Path md = tmpDir.resolve("converted").resolve(info.docId() + ".md");
        assertThat(Files.exists(md)).isTrue();
        String mdContent = Files.readString(md);
        assertThat(mdContent).contains("[페이지: 1]");
        assertThat(mdContent).doesNotContain("## 1페이지"); // 합성 페이지 헤딩은 더 이상 생성하지 않는다
        assertThat(info.chunks()).isGreaterThan(0);
    }

    @Test
    @DisplayName("PDF 업로드(스캔 문서) — MD 변환 없이 기존 OCR/플랫 경로를 그대로 탄다 (회귀 방지)")
    void index_scannedPdf_skipsMarkdownConversion() throws IOException {
        Path pdfFile = tmpDir.resolve("scanned.pdf");
        Files.writeString(pdfFile, "%PDF-1.4 fake bytes — content never actually parsed in this test");

        List<Document> scannedPages = List.of(
                new Document("표지", Map.of(MetaKey.SOURCE_TYPE, "ocr")),
                new Document("목차 내용", Map.of(MetaKey.SOURCE_TYPE, "ocr")));
        when(loaderService.loadPdfPagesForConversion(any()))
                .thenReturn(new DocumentLoaderService.PdfPages(scannedPages, true));
        when(loaderService.load(any(), any())).thenReturn(scannedPages);

        DocumentInfo info = indexer.index(IndexRequest.single(pdfFile, "scanned.pdf", "v1", "anonymous", e -> {}));

        Path md = tmpDir.resolve("converted").resolve(info.docId() + ".md");
        assertThat(Files.exists(md)).isFalse(); // scanned PDFs never produce a converted MD file
        assertThat(info.chunks()).isGreaterThan(0);
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
    @DisplayName("reindexFromMd — 성공 시 태그가 FTS에서 복원되어 유지된다")
    void reindexFromMd_success_preservesTags() throws IOException {
        Path txtFile = tmpDir.resolve("guide.txt");
        Files.writeString(txtFile, "테스트 문서 내용입니다. 청킹과 메타 태깅을 검증합니다.");
        DocumentInfo info = indexer.index(IndexRequest.single(txtFile, "guide.txt", "v1", "anonymous", e -> {}));
        keywordRepo.updateDocTags(info.docId(), "faq,guide");

        indexer.reindexFromMd(info.docId());

        assertThat(keywordRepo.tagsByDocIds(List.of(info.docId())).get(info.docId()))
                .containsExactlyInAnyOrder("faq", "guide");
    }

    @Test
    @DisplayName("reindexFromMd — 벡터 저장 실패 시 기존 태그/청크가 그대로 남는다 (delete-before-write 회귀 방지)")
    void reindexFromMd_vectorStoreAddFails_leavesExistingDataIntact() throws IOException {
        Path txtFile = tmpDir.resolve("guide.txt");
        Files.writeString(txtFile, "테스트 문서 내용입니다. 청킹과 메타 태깅을 검증합니다.");
        DocumentInfo info = indexer.index(IndexRequest.single(txtFile, "guide.txt", "v1", "anonymous", e -> {}));
        keywordRepo.updateDocTags(info.docId(), "faq,guide");

        // Reset the mock so the earlier successful index() stubbing/interactions don't leak in,
        // then make the reindex's add() call fail. reindexFromMd() uses the progress-reporting
        // 4-arg overload (storing-stage progress), not the plain 3-arg one.
        reset(vectorStore);
        doThrow(new RuntimeException("embedding server down")).when(vectorStore).add(any(), any(), any(), any());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> indexer.reindexFromMd(info.docId()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("embedding server down");

        // Old FTS rows (chunks + tags) must survive — the old-row delete only runs after a
        // successful add()+indexChunks(), which never happened here.
        assertThat(keywordRepo.tagsByDocIds(List.of(info.docId())).get(info.docId()))
                .containsExactlyInAnyOrder("faq", "guide");
        verify(vectorStore, never()).deleteByDocIds(any(), any(), any());
    }

    @Test
    @DisplayName("reindexFromMd — corrected.md의 존재하지 않는 이미지 마커는 제거된 뒤 인덱싱되고, 파일에도 반영된다")
    void reindexFromMd_missingImageMarker_removedBeforeIndexingAndPersisted() throws IOException {
        Path txtFile = tmpDir.resolve("guide.txt");
        Files.writeString(txtFile, "테스트 문서 내용입니다.");
        DocumentInfo info = indexer.index(IndexRequest.single(txtFile, "guide.txt", "v1", "anonymous", e -> {}));

        // Simulate a corrected.md left pointing at an image that no longer exists on disk
        // (deleted/moved since the MD was written) — the referenced file is never created here.
        Path mdPath = tmpDir.resolve("converted").resolve(info.docId() + ".md"); // correctionService is mocked in this suite, so no real _corrected.md is written — reindexFromMd() falls back to the raw .md
        Files.writeString(mdPath,
                Files.readString(mdPath) + "\n[이미지: images/deadbeef/missing.png]\n");

        indexer.reindexFromMd(info.docId());

        assertThat(Files.readString(mdPath)).doesNotContain("images/deadbeef/missing.png");

        org.mockito.ArgumentCaptor<String> mdCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        // reindexFromMd() calls the 2-arg (isPptx) overload, not the 1-arg one this test used to
        // capture from — without the boolean matcher below, Mockito never records a match here and
        // the assertion below degenerates to vacuously true on an empty capture list.
        verify(loaderService, atLeastOnce()).loadFromMarkdown(mdCaptor.capture(), anyBoolean());
        assertThat(mdCaptor.getAllValues()).noneMatch(md -> md.contains("images/deadbeef/missing.png"));
    }

    @Test
    @DisplayName("reindexFromMd — 이미지 파일이 실제 존재하면 마커는 그대로 유지된다")
    void reindexFromMd_existingImageMarker_preserved() throws IOException {
        Path txtFile = tmpDir.resolve("guide.txt");
        Files.writeString(txtFile, "테스트 문서 내용입니다.");
        DocumentInfo info = indexer.index(IndexRequest.single(txtFile, "guide.txt", "v1", "anonymous", e -> {}));

        Path imageDir = tmpDir.resolve("images").resolve("cafef00d");
        Files.createDirectories(imageDir);
        Files.writeString(imageDir.resolve("real.png"), "not a real png, just needs to exist");

        Path mdPath = tmpDir.resolve("converted").resolve(info.docId() + ".md"); // correctionService is mocked in this suite, so no real _corrected.md is written — reindexFromMd() falls back to the raw .md
        Files.writeString(mdPath,
                Files.readString(mdPath) + "\n[이미지: images/cafef00d/real.png]\n");

        indexer.reindexFromMd(info.docId());

        assertThat(Files.readString(mdPath)).contains("images/cafef00d/real.png");
        org.mockito.ArgumentCaptor<String> mdCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(loaderService, atLeastOnce()).loadFromMarkdown(mdCaptor.capture(), anyBoolean());
        assertThat(mdCaptor.getAllValues()).anyMatch(md -> md.contains("images/cafef00d/real.png"));
    }

    @Test
    @DisplayName("reindexFromMd — 변환불가 마커·정상 마커 혼재 시 존재하지 않는 것만 선택적으로 제거된다")
    void reindexFromMd_mixedMarkers_removesOnlyMissingOnes() throws IOException {
        Path txtFile = tmpDir.resolve("guide.txt");
        Files.writeString(txtFile, "테스트 문서 내용입니다.");
        DocumentInfo info = indexer.index(IndexRequest.single(txtFile, "guide.txt", "v1", "anonymous", e -> {}));

        Path imageDir = tmpDir.resolve("images").resolve("cafef00d");
        Files.createDirectories(imageDir);
        Files.writeString(imageDir.resolve("real.emf"), "raw unconverted bytes");

        Path mdPath = tmpDir.resolve("converted").resolve(info.docId() + ".md"); // correctionService is mocked in this suite, so no real _corrected.md is written — reindexFromMd() falls back to the raw .md
        Files.writeString(mdPath, Files.readString(mdPath)
                + "\n[이미지(변환불가): images/cafef00d/real.emf]\n"
                + "[이미지: images/deadbeef/gone.png]\n");

        indexer.reindexFromMd(info.docId());

        String cleaned = Files.readString(mdPath);
        assertThat(cleaned).contains("images/cafef00d/real.emf").doesNotContain("images/deadbeef/gone.png");
    }

    @Test
    @DisplayName("reindexFromMd — 이미 번호가 있던 소제목이 편집으로 어긋나면 재인덱싱 시 다시 계산되어 파일에도 반영된다")
    void reindexFromMd_staleHeadingNumbers_recalculatedAndPersisted() throws IOException {
        Path txtFile = tmpDir.resolve("guide.txt");
        Files.writeString(txtFile, "## 1. 첫 번째 절\n본문A\n");
        DocumentInfo info = indexer.index(IndexRequest.single(txtFile, "guide.txt", "v1", "anonymous", e -> {}));

        // 코드 블록 편집 등으로 가운데 헤딩이 사라져 번호가 어긋난 상황을 재현(1., 3.만 남음)
        Path mdPath = tmpDir.resolve("converted").resolve(info.docId() + ".md");
        Files.writeString(mdPath, "## 1. 첫 번째 절\n본문A\n\n## 3. 세 번째 절\n본문B\n");

        indexer.reindexFromMd(info.docId());

        String result = Files.readString(mdPath);
        assertThat(result).contains("## 1. 첫 번째 절").contains("## 2. 세 번째 절");
        assertThat(result).doesNotContain("## 3.");
    }

    @Test
    @DisplayName("reindexFromMd — 소제목 번호가 원래 없던 문서는 재인덱싱해도 번호가 새로 생기지 않는다")
    void reindexFromMd_noExistingHeadingNumbers_staysUnnumbered() throws IOException {
        Path txtFile = tmpDir.resolve("guide.txt");
        Files.writeString(txtFile, "## 첫 번째 절\n본문A\n\n## 두 번째 절\n본문B\n");
        DocumentInfo info = indexer.index(IndexRequest.single(txtFile, "guide.txt", "v1", "anonymous", e -> {}));
        Path mdPath = tmpDir.resolve("converted").resolve(info.docId() + ".md");

        indexer.reindexFromMd(info.docId());

        // postProcessMarkdown (now also applied on reindex) may harmlessly trim trailing blank
        // lines, so this checks the actual invariant under test — no numbers appear — rather than
        // byte-for-byte file equality.
        String after = Files.readString(mdPath);
        assertThat(after).contains("## 첫 번째 절").contains("## 두 번째 절");
        assertThat(after).doesNotContain("## 1.").doesNotContain("## 2.");
    }

    @Test
    @DisplayName("reindexFromMd — PPTX 문서는 소제목에 번호가 있어도 재인덱싱 시 건드리지 않는다")
    void reindexFromMd_pptx_neverTouchesHeadingNumbers() throws IOException {
        Path pptxFile = tmpDir.resolve("deck.pptx");
        writeMinimalPptx(pptxFile, "개요");
        DocumentInfo info = indexer.index(IndexRequest.single(pptxFile, "deck.pptx", "v1", "anonymous", e -> {}));

        Path mdPath = tmpDir.resolve("converted").resolve(info.docId() + ".md");
        // PPTX는 애초에 번호가 붙지 않지만, 만약 번호처럼 보이는 헤딩이 있어도 손대면 안 된다는 것을
        // 확인하기 위해 강제로 번호 있는 헤딩을 주입한다.
        Files.writeString(mdPath, "[페이지: 1]\n## 1. 개요\n본문\n\n[페이지: 2]\n## 3. 결론\n본문\n");

        indexer.reindexFromMd(info.docId());

        assertThat(Files.readString(mdPath)).contains("## 1. 개요").contains("## 3. 결론");
    }

    @Test
    @DisplayName("parallel index — 사전계산된 sha256이 전달되면 파일을 재해싱하지 않고 그대로 사용한다(§10.8.4)")
    void index_parallel_usesPrecomputedSha256WithoutRehashing() throws IOException {
        Path txtFile = tmpDir.resolve("presha.txt");
        Files.writeString(txtFile, "실제 파일 내용");
        Semaphore gate = new Semaphore(2);
        // Deliberately wrong vs. the file's real content hash — if index() actually re-read and
        // re-hashed the file (bypassing the precomputed value), info.sha256() would NOT equal this.
        String fakeSha256 = "f".repeat(64);

        DocumentInfo info = indexer.index(
                IndexRequest.parallel(txtFile, "v1", DocRegistry.SHARED, gate, null, fakeSha256));

        assertThat(info.sha256()).isEqualTo(fakeSha256);
        assertThat(info.docId()).isEqualTo("presha.txt_" + fakeSha256.substring(0, 8));
    }

    @Test
    @DisplayName("parallel index — sha256 미전달(null) 시 기존과 동일하게 파일에서 직접 계산한다")
    void index_parallel_withoutPrecomputedSha256_computesFromFile() throws IOException {
        Path txtFile = tmpDir.resolve("nopresha.txt");
        Files.writeString(txtFile, "실제 파일 내용");
        Semaphore gate = new Semaphore(2);

        DocumentInfo info = indexer.index(IndexRequest.parallel(txtFile, "v1", DocRegistry.SHARED, gate, null));

        assertThat(info.sha256()).isNotEqualTo("f".repeat(64));
        assertThat(info.sha256()).isNotBlank();
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
