package com.example.ragagent.ingestion;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.exception.DocumentIndexingException;
import com.example.ragagent.exception.IndexingCancelledException;
import com.example.ragagent.model.DocumentInfo;
import com.example.ragagent.model.IndexingProgressEvent;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.model.SyncResult;
import com.example.ragagent.service.DocumentLoaderService;
import com.example.ragagent.service.ImageExtractorService;
import com.example.ragagent.service.MarkdownCorrectionService;
import com.example.ragagent.service.PdfToMarkdownConverter;
import com.example.ragagent.service.PptxToMarkdownConverter;
import com.example.ragagent.service.TextToMarkdownService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Orchestrates single-document indexing: load → split → tag → enrich → store.
 * Does not call {@link DocRegistry#save()} — callers decide when to persist.
 */
@Component
public class DocumentIndexer {

    private static final Logger log = LoggerFactory.getLogger(DocumentIndexer.class);

    // Matches both [이미지: path] and [이미지(변환불가): path] — see DocumentLoaderService's
    // IMAGE_PATH_MARKER (only the plain form) and DocxToMarkdownConverter/PptxToMarkdownConverter/
    // PdfToMarkdownConverter (marker producers). Used by removeMissingImageMarkers() on re-index.
    private static final Pattern IMAGE_MARKER = Pattern.compile("\\[이미지(?:\\([^)]*\\))?: ([^\\]]+?)]");

    private final DocumentLoaderService loaderService;
    private final MarkdownCorrectionService correctionService;
    private final TextToMarkdownService textToMarkdownService;
    private final PptxToMarkdownConverter pptxConverter;
    private final PdfToMarkdownConverter pdfConverter;
    private final ImageExtractorService imageExtractorService;
    private final VectorStoreFacade vectorStore;
    private final DocRegistry docRegistry;
    private final KeywordSearchRepository keywordRepo;
    private final ChunkSplitter chunkSplitter;
    private final KeywordExtractor keywordExtractor;
    private final AppProperties props;

    private Path dataDir;

    public DocumentIndexer(DocumentLoaderService loaderService,
                           MarkdownCorrectionService correctionService,
                           TextToMarkdownService textToMarkdownService,
                           PptxToMarkdownConverter pptxConverter,
                           PdfToMarkdownConverter pdfConverter,
                           ImageExtractorService imageExtractorService,
                           VectorStoreFacade vectorStore,
                           DocRegistry docRegistry,
                           KeywordSearchRepository keywordRepo,
                           ChunkSplitter chunkSplitter,
                           KeywordExtractor keywordExtractor,
                           AppProperties props) {
        this.loaderService = loaderService;
        this.correctionService = correctionService;
        this.textToMarkdownService = textToMarkdownService;
        this.pptxConverter = pptxConverter;
        this.pdfConverter = pdfConverter;
        this.imageExtractorService = imageExtractorService;
        this.vectorStore = vectorStore;
        this.docRegistry = docRegistry;
        this.keywordRepo = keywordRepo;
        this.chunkSplitter = chunkSplitter;
        this.keywordExtractor = keywordExtractor;
        this.props = props;
    }

    @PostConstruct
    void init() {
        dataDir = Path.of(props.dataDir());
        log.info("[CONFIG] indexing.max-concurrent-files={}, indexing.max-concurrent-llm-calls={}",
                props.indexingSafe().maxConcurrentFiles(),
                props.indexingSafe().maxConcurrentLlmCalls());
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Indexes one document. Does NOT call {@link DocRegistry#save()} — caller's responsibility.
     */
    public DocumentInfo index(IndexRequest req) throws IOException {
        log.info("[INDEX] 시작: {} (version={})", req.filename(), req.version());
        long t0 = System.currentTimeMillis();

        // §10.8.4 — syncDirectory() already hashed this file during detection; reuse it instead
        // of re-reading the whole file. Interactive single-upload requests still compute fresh.
        String sha256 = req.precomputedSha256() != null ? req.precomputedSha256() : computeSha256(req.path());
        String docId = req.filename() + "_" + sha256.substring(0, 8);
        String imageId = imageId(sha256);
        String docType = inferDocType(req.filename());
        Path imagesDir = dataDir.resolve("images").resolve(imageId);
        Path rawMdPath = dataDir.resolve("converted").resolve(docId + ".md");
        Path correctedMdPath = dataDir.resolve("converted").resolve(docId + "_corrected.md");
        log.debug("[INDEX] docId={}, imageId={}, type={}, sha256={}", docId, imageId, docType, sha256);

        // Preserve tags on operator re-index / directory sync (those paths carry no tag input):
        // the request has no tags, but they live in the FTS index under the prior docId
        // (staleDocId when content changed, else this docId). Read BEFORE the delete below
        // wipes those rows. Interactive single upload keeps its explicit tags — an empty list
        // there means the user intentionally cleared them, so we do not auto-restore.
        List<String> effectiveTags = req.tags();
        if (effectiveTags.isEmpty() && req.parallelGate() != null) {
            String priorDocId = req.staleDocId() != null ? req.staleDocId() : docId;
            effectiveTags = restoreTags(priorDocId);
            if (!effectiveTags.isEmpty()) {
                log.debug("[INDEX] {} 태그 복원: {} (prior={})", req.filename(), effectiveTags, priorDocId);
            }
        }

        // Delete before conversion so newly created images/MD files are not immediately removed
        deleteExistingVectorsAndFiles(DocRegistry.SHARED, docId, req.version());

        String lower = req.filename().toLowerCase();
        log.debug("[INDEX] {} 문서 로드 중...", req.filename());
        req.onProgress().accept(IndexingProgressEvent.of("loading", 0, 0, req.filename(), "문서 로드 중..."));
        List<Document> rawDocs;
        if (lower.endsWith(".docx")) {
            req.onProgress().accept(IndexingProgressEvent.of("loading", 0, 0, req.filename(), "DOCX → Markdown 변환 중..."));
            String rawMd = loaderService.convertDocxToMd(req.path(), imageId, imagesDir);
            Files.createDirectories(rawMdPath.getParent());
            Files.writeString(rawMdPath, rawMd);
            String sourceMd = correctionService.correct(rawMd, docId, correctedMdPath,
                    req.addImageDescriptions(), req.addHeadingNumbers(), false,
                    (done, total) -> req.onProgress().accept(
                            IndexingProgressEvent.of("correcting", done, total, req.filename(),
                                    done + "/" + total + " 섹션 교정 중")));
            rawDocs = loaderService.loadFromMarkdown(sourceMd);
        } else if (lower.endsWith(".txt")) {
            // Plain text has no inherent structure → let the LLM impose headings/lists + fix grammar,
            // then run it through the same MD pipeline DOCX uses (format correction → section split).
            // Graceful: convert()/correct() keep the original text if the LLM is unavailable.
            req.onProgress().accept(IndexingProgressEvent.of("loading", 0, 0, req.filename(), "TXT → Markdown 구조화 중..."));
            String plainText = Files.readString(req.path());
            String structuredMd = textToMarkdownService.convert(plainText, docId,
                    (done, total) -> req.onProgress().accept(
                            IndexingProgressEvent.of("structuring", done, total, req.filename(),
                                    done + "/" + total + " 블록 구조화 중")));
            Files.createDirectories(rawMdPath.getParent());
            Files.writeString(rawMdPath, structuredMd);
            String sourceMd = correctionService.correct(structuredMd, docId, correctedMdPath,
                    req.addImageDescriptions(), req.addHeadingNumbers(), false,
                    (done, total) -> req.onProgress().accept(
                            IndexingProgressEvent.of("correcting", done, total, req.filename(),
                                    done + "/" + total + " 섹션 교정 중")));
            rawDocs = loaderService.loadFromMarkdown(sourceMd);
        } else if (lower.endsWith(".md")) {
            req.onProgress().accept(IndexingProgressEvent.of("loading", 0, 0, req.filename(), "Markdown 로드 중..."));
            String rawMd = Files.readString(req.path());
            Files.createDirectories(rawMdPath.getParent());
            Files.writeString(rawMdPath, rawMd);
            String sourceMd = correctionService.correct(rawMd, docId, correctedMdPath,
                    req.addImageDescriptions(), req.addHeadingNumbers(), false,
                    (done, total) -> req.onProgress().accept(
                            IndexingProgressEvent.of("correcting", done, total, req.filename(),
                                    done + "/" + total + " 섹션 교정 중")));
            rawDocs = loaderService.loadFromMarkdown(sourceMd);
        } else if (lower.endsWith(".pptx")) {
            // PPTX has unambiguous slide numbers → convert to MD (title-only heading per slide,
            // [페이지: N] marker, inline [이미지: ...] markers like DOCX) and run it through the
            // same correction+section pipeline DOCX uses. loadFromMarkdown() promotes the image
            // markers into image_paths metadata automatically — no separate attach step needed.
            req.onProgress().accept(IndexingProgressEvent.of("loading", 0, 0, req.filename(), "PPTX → Markdown 변환 중..."));
            String rawMd = pptxConverter.convert(req.path(), imageId, imagesDir);
            Files.createDirectories(rawMdPath.getParent());
            Files.writeString(rawMdPath, rawMd);
            // addHeadingNumbers는 체크되어 있어도 항상 무시한다 — PPTX의 ##/### 헤딩은 슬라이드
            // 제목/부제목 라벨(최대 2단계, calibrateHeadingOrder로 슬라이드별 결정)이지 문서
            // 목차 같은 계층 구조가 아니어서, 순차적으로 "1.1"/"1.2" 번호를 매겨도 실제 구조를
            // 반영하지 못하고 이미 있는 [페이지: N] 마커와도 겹쳐 혼란만 준다.
            // groupByPage=true — 슬라이드 하나(## + 있으면 ###)가 [페이지: N] 마커 단위로 하나의
            // 교정 섹션이 되도록 한다. 일반 헤딩 기준 분할(splitBySections)을 쓰면 소제목(###)이
            // 있는 슬라이드가 두 섹션으로 쪼개진다.
            String sourceMd = correctionService.correct(rawMd, docId, correctedMdPath,
                    req.addImageDescriptions(), false, true,
                    (done, total) -> req.onProgress().accept(
                            IndexingProgressEvent.of("correcting", done, total, req.filename(),
                                    done + "/" + total + " 섹션 교정 중")));
            rawDocs = loaderService.loadFromMarkdown(sourceMd);
        } else if (lower.endsWith(".pdf")) {
            DocumentLoaderService.PdfPages pdfPages = loaderService.loadPdfPagesForConversion(req.path());
            if (pdfPages.scanned()) {
                // Scanned PDF — unchanged legacy OCR/flat path (no MD conversion).
                rawDocs = loaderService.load(req.path(),
                        (done, total) -> req.onProgress().accept(IndexingProgressEvent.of(
                                "loading", done, total, req.filename(),
                                "OCR 처리 중 (" + done + "/" + total + " 페이지)")));
                req.onProgress().accept(IndexingProgressEvent.of(
                        "loading", 0, 0, req.filename(), "이미지 추출 중..."));
                rawDocs = injectImagePaths(rawDocs, imageExtractorService.extract(
                        req.path(), imageId, imagesDir,
                        (done, total) -> req.onProgress().accept(IndexingProgressEvent.of(
                                "loading", done, total, req.filename(),
                                "이미지 추출 중 (" + done + "/" + total + " 페이지)"))));
            } else {
                // Non-scanned PDF has unambiguous page numbers → convert to MD ([페이지: N] marker
                // + synthetic per-page heading, inline [이미지: ...] markers like DOCX) and run it
                // through the same pipeline DOCX uses. loadFromMarkdown() promotes the image
                // markers into image_paths metadata automatically — no separate attach step needed.
                req.onProgress().accept(IndexingProgressEvent.of("loading", 0, 0, req.filename(), "PDF → Markdown 변환 중..."));
                String rawMd = pdfConverter.convert(pdfPages.pages(), req.path(), imageId, imagesDir,
                        (done, total) -> req.onProgress().accept(IndexingProgressEvent.of(
                                "loading", done, total, req.filename(),
                                "이미지 추출 중 (" + done + "/" + total + " 페이지)")));
                Files.createDirectories(rawMdPath.getParent());
                Files.writeString(rawMdPath, rawMd);
                String sourceMd = correctionService.correct(rawMd, docId, correctedMdPath,
                        req.addImageDescriptions(), req.addHeadingNumbers(), false,
                        (done, total) -> req.onProgress().accept(
                                IndexingProgressEvent.of("correcting", done, total, req.filename(),
                                        done + "/" + total + " 섹션 교정 중")));
                rawDocs = loaderService.loadFromMarkdown(sourceMd);
            }
        } else {
            throw new IllegalArgumentException("Unsupported file type: " + req.filename());
        }
        log.debug("[INDEX] {} 로드 완료 → 원본 섹션 {}개", req.filename(), rawDocs.size());

        req.onProgress().accept(IndexingProgressEvent.of("chunking", 0, 0, req.filename(), "청크 분할 중..."));
        List<Document> chunks = chunkSplitter.splitDocuments(
            rawDocs, req.filename(), props.chunkSize(), props.chunkOverlap(), props.minChunkSizeSafe(),
            props.embeddingSafe().maxChunkChars());
        log.debug("[INDEX] {} 청크 분할 완료 → {}개 (chunkSize={}, overlap={}, minChunkSize={})",
            req.filename(), chunks.size(), props.chunkSize(), props.chunkOverlap(), props.minChunkSizeSafe());
        req.onProgress().accept(IndexingProgressEvent.of("chunking", 0, chunks.size(), req.filename(),
                chunks.size() + "개 청크"));

        List<Document> tagged = tagMetadata(chunks, docId, req.filename(), req.version(), docType, sha256, DocRegistry.SHARED, effectiveTags);

        log.debug("[INDEX] {} 키워드 추출 중 ({}개 청크, 병렬)...", req.filename(), tagged.size());
        Semaphore gate = req.parallelGate() != null
                ? req.parallelGate()
                : new Semaphore(props.indexingSafe().maxConcurrentLlmCalls());
        List<Document> enriched = keywordExtractor.enrichParallel(tagged, gate, req.filename(), req.onProgress());
        // §10.8.5 — compute the embedding/FTS derived text once here instead of once per consumer
        // (vectorStore.add() below + keywordRepo.indexChunks()); both read it back via
        // SearchTextBuilder.build()'s short-circuit and strip it before persisting metadata.
        enriched = enriched.stream().map(SearchTextBuilder::precompute).toList();

        log.debug("[INDEX] {} 벡터 스토어 저장 중 ({}개 청크)...", req.filename(), enriched.size());
        vectorStore.add(DocRegistry.SHARED, req.version(), enriched, (done, total) ->
                req.onProgress().accept(IndexingProgressEvent.of("storing", done, total,
                        req.filename(), "벡터 DB 저장 중...")));
        keywordRepo.indexChunks(enriched);   // populate FTS keyword index

        List<String> docIds = enriched.stream().map(Document::getId).toList();
        DocRegistry.DocRegistryEntry entry = new DocRegistry.DocRegistryEntry(
                sha256, req.version(), Instant.now().toString(), tagged.size(), docIds, List.of());
        docRegistry.put(docId, DocRegistry.SHARED, entry);

        if (req.staleDocId() != null) {
            log.debug("[INDEX] {} 구버전 삭제: {}", req.filename(), req.staleDocId());
            deleteArtifacts(DocRegistry.SHARED, req.staleDocId(), req.version());
        }

        log.info("[INDEX] 완료: {} → {}개 청크, {}ms", req.filename(), tagged.size(), System.currentTimeMillis() - t0);
        return new DocumentInfo(docId, req.filename(), req.version(), tagged.size(),
            entry.indexedAt(), sha256,
            List.copyOf(new LinkedHashSet<>(effectiveTags)),
            List.of());
    }

    /**
     * Re-indexes from a saved Markdown file (DOCX flow). Keeps MD files on disk.
     * Calls {@link DocRegistry#save()} at the end.
     */
    public void reindexFromMd(String docId) throws IOException {
        reindexFromMd(docId, event -> {});
    }

    /** Same as {@link #reindexFromMd(String)}, reporting per-stage progress via {@code onProgress}. */
    public void reindexFromMd(String docId, Consumer<IndexingProgressEvent> onProgress) throws IOException {
        DocRegistry.DocRegistryEntry existing = docRegistry.findByDocId(docId)
                .orElseThrow(() -> new IllegalArgumentException("문서를 찾을 수 없습니다: " + docId));

        String version  = existing.version();
        String filename = DocRegistry.filenameFromDocId(docId);
        String sha256   = existing.sha256();
        String docType  = inferDocType(filename);

        Path correctedPath = dataDir.resolve("converted").resolve(docId + "_corrected.md");
        Path rawPath       = dataDir.resolve("converted").resolve(docId + ".md");
        Path mdPath = Files.exists(correctedPath) ? correctedPath : rawPath;
        if (!Files.exists(mdPath)) {
            throw new IllegalStateException(
                    "MD 파일이 없습니다 (DOCX/TXT/PPTX/PDF 문서만 MD 재인덱싱 지원): " + docId);
        }

        log.info("[REINDEX] 시작: docId={}, src={}", docId, mdPath.getFileName());
        long t0 = System.currentTimeMillis();

        // Old chunks are captured but NOT deleted yet — write-then-delete (not delete-then-write)
        // so a failure below (embedding/LLM call) leaves the existing document intact instead of
        // wiping it. New chunk ids are always freshly generated, so old and new rows can briefly
        // coexist under the same doc_id without colliding.
        List<String> oldSpringDocIds = existing.springDocIds();

        onProgress.accept(IndexingProgressEvent.of("loading", 0, 0, filename, "MD 파일 로드 중..."));
        String md = Files.readString(mdPath);
        md = removeMissingImageMarkers(md, mdPath, filename);
        md = reapplyHeadingNumbersIfNeeded(md, mdPath, filename);
        List<Document> rawDocs = loaderService.loadFromMarkdown(md);
        List<Document> chunks  = chunkSplitter.splitDocuments(
            rawDocs, filename, props.chunkSize(), props.chunkOverlap(), props.minChunkSizeSafe(),
            props.embeddingSafe().maxChunkChars());
        log.debug("[REINDEX] 청크 분할: {}섹션 → {}청크", rawDocs.size(), chunks.size());
        onProgress.accept(IndexingProgressEvent.of("chunking", 0, chunks.size(), filename,
                chunks.size() + "개 청크로 분할 완료"));
        // Keep tags across re-index (same docId): read from FTS before the old rows are removed.
        List<String> preservedTags = restoreTags(docId);
        List<Document> tagged  = tagMetadata(chunks, docId, filename, version, docType, sha256, DocRegistry.SHARED, preservedTags);

        log.debug("[REINDEX] 키워드 추출 시작: {}개 청크", tagged.size());
        Semaphore gate = new Semaphore(props.indexingSafe().maxConcurrentLlmCalls());
        List<Document> enriched = keywordExtractor.enrichParallel(tagged, gate, filename, onProgress);
        enriched = enriched.stream().map(SearchTextBuilder::precompute).toList(); // §10.8.5

        log.debug("[REINDEX] 벡터 스토어 저장 중: {}개 청크", enriched.size());
        vectorStore.add(DocRegistry.SHARED, version, enriched, (done, total) ->
                onProgress.accept(IndexingProgressEvent.of("storing", done, total, filename,
                        done + "/" + total + " 벡터 저장 완료")));
        keywordRepo.indexChunks(enriched);   // populate FTS keyword index

        // Only now remove the old chunks — by their captured spring_doc_ids, not by doc_id (both
        // old and new rows share the same doc_id string; a doc_id-based delete would also wipe
        // the rows just inserted above).
        if (!oldSpringDocIds.isEmpty()) {
            vectorStore.deleteByDocIds(DocRegistry.SHARED, version, oldSpringDocIds);
            keywordRepo.deleteBySpringDocIds(oldSpringDocIds);
        }

        List<String> springIds = enriched.stream().map(Document::getId).toList();
        docRegistry.put(docId, DocRegistry.SHARED, new DocRegistry.DocRegistryEntry(
                sha256, version, Instant.now().toString(), tagged.size(), springIds, List.of()));
        docRegistry.save();

        log.info("[REINDEX] 완료: {} → {}개 청크, {}ms", filename, tagged.size(), System.currentTimeMillis() - t0);
    }

    /**
     * Synchronises the documents directory with the vector store.
     * Calls {@link DocRegistry#save()} once at the end.
     */
    public SyncResult syncDirectory(String userId, String version, Path documentsDir,
                                    Consumer<IndexingProgressEvent> onProgress) throws IOException {
        log.info("[SYNC] 디렉터리 동기화 시작 (version={})", version);
        long t0 = System.currentTimeMillis();

        // 1단계: 디스크 파일 수집 + 인덱싱 대상 탐지
        Map<String, Path> filesOnDisk = new HashMap<>();
        if (Files.exists(documentsDir)) {
            try (Stream<Path> stream = Files.list(documentsDir)) {
                // NFC normalization: macOS HFS+/APFS returns NFD filenames
                stream.filter(p -> isSupportedExtension(p.getFileName().toString()))
                      .forEach(p -> filesOnDisk.put(
                              Normalizer.normalize(p.getFileName().toString(), Normalizer.Form.NFC), p));
            }
        }

        // §10.8.4 — sha256 carried through to step 2 so index() doesn't re-hash the same file.
        record FileEntry(Path path, String staleDocId, String sha256) {}
        Map<String, FileEntry> filesToIndex = new HashMap<>();

        for (Map.Entry<String, Path> e : filesOnDisk.entrySet()) {
            String filename = e.getKey();
            Path   filePath = e.getValue();
            String sha256   = computeSha256(filePath);
            String docId    = filename + "_" + sha256.substring(0, 8);

            if (docRegistry.existsBySha256AndVersion(sha256, version, DocRegistry.SHARED)) continue;

            String stale = docRegistry.findStaleDocId(filename, docId, version, DocRegistry.SHARED).orElse(null);
            filesToIndex.put(filename, new FileEntry(filePath, stale, sha256));
        }
        log.info("[SYNC] 1단계 완료: 전체 {}개, 인덱싱 필요 {}개, 스킵 {}개",
                filesOnDisk.size(), filesToIndex.size(), filesOnDisk.size() - filesToIndex.size());
        filesToIndex.forEach((name, fe) ->
                log.debug("[SYNC]   대상: {} (stale={})", name, fe.staleDocId()));

        int totalFiles = filesToIndex.size();
        onProgress.accept(IndexingProgressEvent.of("sync_start", 0, totalFiles, "sync",
                "파일 " + totalFiles + "개 인덱싱 예정"));

        // 2단계: 파일별 병렬 인덱싱
        int fileConcurrency = props.indexingSafe().maxConcurrentFiles();
        Semaphore llmGate   = new Semaphore(props.indexingSafe().maxConcurrentLlmCalls());
        List<String> indexed = new CopyOnWriteArrayList<>();
        List<String> updated = new CopyOnWriteArrayList<>();
        AtomicInteger doneFiles = new AtomicInteger(0);

        try (ExecutorService filePool = Executors.newFixedThreadPool(
                fileConcurrency, Thread.ofVirtual().factory())) {
            List<CompletableFuture<Void>> futures = filesToIndex.entrySet().stream()
                .map(e -> CompletableFuture.runAsync(() -> {
                    boolean failed = false;
                    String errorMsg = null;
                    try {
                        index(IndexRequest.parallel(e.getValue().path(), version,
                                DocRegistry.SHARED, llmGate, e.getValue().staleDocId(), e.getValue().sha256()));
                        (e.getValue().staleDocId() != null ? updated : indexed).add(e.getKey());
                    } catch (Exception ex) {
                        log.error("[SYNC] 병렬 인덱싱 실패: {}", e.getKey(), ex);
                        failed = true;
                        errorMsg = isConnectionError(ex)
                                ? "임베딩/LLM 서버 연결 실패"
                                : (ex.getMessage() != null ? ex.getMessage() : "인덱싱 오류");
                    }
                    int k = doneFiles.incrementAndGet();
                    if (failed) {
                        onProgress.accept(IndexingProgressEvent.syncFileError(k, totalFiles, e.getKey(), errorMsg));
                    } else {
                        onProgress.accept(IndexingProgressEvent.of("sync_file_done", k, totalFiles,
                                e.getKey(), k + "/" + totalFiles + " 완료"));
                    }
                }, filePool))
                .toList();
            // .get() (not .join()) so a cancel-driven interrupt of this coordinating thread
            // actually unblocks the wait instead of parking through it (§6.16.1).
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
            } catch (InterruptedException e) {
                log.warn("[SYNC] cancelled by user — interrupting in-flight file(s), {}/{} completed",
                        doneFiles.get(), totalFiles);
                filePool.shutdownNow();
                try {
                    filePool.awaitTermination(10, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                // Persist whatever succeeded before cancellation so completed work isn't lost;
                // step 3 (deletion detection) is skipped — it can run on the next normal sync.
                docRegistry.save();
                throw new IndexingCancelledException(
                        "sync cancelled: " + doneFiles.get() + "/" + totalFiles + " files processed");
            } catch (ExecutionException e) {
                throw new RuntimeException(e.getCause());
            }
        }

        // 3단계: 삭제된 파일 감지
        List<String> deleted = new ArrayList<>();
        for (String docId : new HashSet<>(docRegistry.docIds(DocRegistry.SHARED))) {
            DocRegistry.DocRegistryEntry entry = docRegistry.findByDocId(docId, DocRegistry.SHARED).orElse(null);
            if (entry == null || !version.equals(entry.version())) continue;
            String filename = DocRegistry.filenameFromDocId(docId);
            if (!filesOnDisk.containsKey(filename)) {
                log.debug("[SYNC] 삭제 감지: {}", filename);
                deleteArtifacts(DocRegistry.SHARED, docId, version);
                deleted.add(filename);
            }
        }

        docRegistry.save();
        log.info("[SYNC] 동기화 완료: 신규={}, 갱신={}, 삭제={}, 총 {}ms",
                indexed.size(), updated.size(), deleted.size(), System.currentTimeMillis() - t0);
        return new SyncResult(List.copyOf(indexed), List.copyOf(updated), deleted);
    }

    /**
     * Deletes vector chunks, image/MD files, and removes the entry from {@link DocRegistry}.
     * Does NOT call {@link DocRegistry#save()} — caller decides when to persist.
     */
    public void deleteArtifacts(String userId, String docId, String version) {
        deleteExistingVectorsOnly(userId, docId, version);
        deleteDocFiles(userId, docId);
        docRegistry.remove(docId, userId);
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private static boolean isSupportedExtension(String filename) {
        if (filename == null) return false;
        String lower = filename.toLowerCase();
        return List.of(".pdf", ".pptx", ".docx", ".txt", ".md").stream().anyMatch(lower::endsWith);
    }

    private List<Document> tagMetadata(List<Document> chunks, String docId, String filename,
                                        String version, String docType, String sha256, String ownerId,
                                        List<String> tags) {
        // comma-joined storage form (matches the image_paths convention; backend-neutral).
        String tagsMeta = com.example.ragagent.model.TagUtils.toMetaValue(tags);
        List<Document> tagged = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            Map<String, Object> meta = new HashMap<>(chunk.getMetadata());
            meta.put(MetaKey.DOC_ID,       docId);
            meta.put(MetaKey.FILENAME,     filename);
            meta.put(MetaKey.VERSION,      version);
            meta.put(MetaKey.DOC_TYPE,     docType);
            meta.put(MetaKey.SHA256,       sha256);
            meta.put(MetaKey.COLLECTED_AT, Instant.now().toString());
            meta.putIfAbsent(MetaKey.SOURCE_TYPE,   "file");
            meta.putIfAbsent(MetaKey.PAGE_OR_SLIDE, i + 1);
            meta.put(MetaKey.CHUNK_INDEX,  i);   // stable per-chunk key (separate from page)
            meta.put(MetaKey.OWNER_ID,     ownerId);
            meta.putIfAbsent(MetaKey.VISIBILITY, "private");
            if (!tagsMeta.isEmpty()) meta.put(MetaKey.TAGS, tagsMeta);
            tagged.add(new Document(chunk.getText(), meta));
        }
        return tagged;
    }

    /**
     * Recovers a document's search-scope tags from the FTS index ({@code chunk_fts.doc_tags}) so
     * operator re-index / directory-sync paths — which carry no tag input — do not silently drop
     * tags set at original upload. Returns an empty list when FTS is unavailable or no prior rows
     * exist for {@code priorDocId}. Never throws.
     */
    private List<String> restoreTags(String priorDocId) {
        if (priorDocId == null) return List.of();
        return keywordRepo.tagsByDocIds(List.of(priorDocId)).getOrDefault(priorDocId, List.of());
    }

    /**
     * Strips {@code [이미지: path]}/{@code [이미지(변환불가): path]} markers whose referenced file
     * no longer exists under {@code data/images/} (manually cleaned up, moved, or lost since the MD
     * was written) before re-indexing from it — otherwise the stale reference is carried forward
     * into the new chunks' {@code image_paths} metadata, pointing at a file that 404s. Persists the
     * cleaned content back to {@code mdPath} so the fix survives (self-healing on next re-index
     * too); a failed write is logged and swallowed since the cleaned string is still used in-memory
     * for the current pass regardless. No-op (returns {@code md} unchanged) when every referenced
     * file exists.
     */
    private String removeMissingImageMarkers(String md, Path mdPath, String filename) {
        Matcher m = IMAGE_MARKER.matcher(md);
        List<String> missing = new ArrayList<>();
        StringBuilder cleaned = new StringBuilder();
        int last = 0;
        while (m.find()) {
            String path = m.group(1).strip();
            if (!Files.exists(dataDir.resolve(path))) {
                cleaned.append(md, last, m.start());
                last = m.end();
                missing.add(path);
            }
        }
        if (missing.isEmpty()) return md;
        cleaned.append(md, last, md.length());
        String result = cleaned.toString();
        log.warn("[REINDEX] {} — 존재하지 않는 이미지 참조 {}개 제거: {}", filename, missing.size(), missing);
        try {
            Files.writeString(mdPath, result);
        } catch (IOException e) {
            log.warn("[REINDEX] {} — 정리된 MD 저장 실패(이번 인덱싱은 계속 진행): {}", filename, e.getMessage());
        }
        return result;
    }

    /**
     * Re-checks and re-computes hierarchical heading numbers on re-index — the saved MD may have
     * been edited since the numbers were first assigned (e.g. a chunk edit split/merged a code
     * block, shifting which headings exist), leaving stale numbers behind.
     * {@link MarkdownCorrectionService#reapplyHeadingNumbers} is a no-op unless the document
     * already has a numbered heading, so a document that never had numbers stays that way. PPTX is
     * skipped outright — its headings never get numbered even at upload time (see the {@code
     * .pptx} branch in {@link #index}), so there's nothing to re-check.
     */
    private String reapplyHeadingNumbersIfNeeded(String md, Path mdPath, String filename) {
        if (filename.toLowerCase().endsWith(".pptx")) return md;

        String result = correctionService.reapplyHeadingNumbers(md);
        if (result.equals(md)) return md;

        log.debug("[REINDEX] {} — 소제목 번호 재계산", filename);
        try {
            Files.writeString(mdPath, result);
        } catch (IOException e) {
            log.warn("[REINDEX] {} — 소제목 번호 갱신 MD 저장 실패(이번 인덱싱은 계속 진행): {}", filename, e.getMessage());
        }
        return result;
    }

    private void deleteExistingVectorsAndFiles(String userId, String docId, String version) {
        deleteExistingVectorsOnly(userId, docId, version);
        deleteDocFiles(userId, docId);
    }

    private void deleteExistingVectorsOnly(String userId, String docId, String version) {
        docRegistry.findByDocId(docId, userId).ifPresent(e -> {
            if (!e.springDocIds().isEmpty()) {
                log.debug("[DELETE] docId={} → 벡터 청크 {}개 삭제", docId, e.springDocIds().size());
                vectorStore.deleteByDocIds(userId, version, e.springDocIds());
            }
        });
        keywordRepo.deleteByDocId(docId);   // keep FTS index in sync
    }

    /**
     * Deletes the images directory (keyed by content-hash {@code imageId} — resolved from the
     * registry entry still present at this point in every caller) plus converted MD files (keyed
     * by {@code docId}, unaffected by the imageId change). Also attempts the legacy
     * {@code images/{docId}/} path for documents indexed before imageId existed, since those
     * images never moved. Before deleting the imageId directory, checks that no other doc_id
     * still shares that sha256 — content-identical documents share one images directory by
     * design, so deleting one must not orphan the other's image links.
     */
    private void deleteDocFiles(String userId, String docId) {
        docRegistry.findByDocId(docId, userId).ifPresent(entry -> {
            String imageId = imageId(entry.sha256());
            if (!docRegistry.existsOtherBySha256(entry.sha256(), docId)) {
                deleteImagesQuietly(dataDir.resolve("images").resolve(imageId));
            } else {
                log.debug("[DELETE] imageId={} 는 다른 문서와 공유 중 — 이미지 보존", imageId);
            }
        });
        deleteImagesQuietly(dataDir.resolve("images").resolve(docId)); // legacy pre-imageId layout
        for (String suffix : List.of(".md", "_corrected.md")) {
            Path p = dataDir.resolve("converted").resolve(docId + suffix);
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                log.warn("MD cleanup failed {}: {}", p, e.getMessage());
            }
        }
    }

    private void deleteImagesQuietly(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) {}
            });
        } catch (IOException e) {
            log.warn("Image directory cleanup failed {}: {}", dir, e.getMessage());
        }
    }

    /**
     * Position-based image attachment: assumes exactly one raw {@code Document} per slide/page
     * ({@code docs.get(i)} ↔ slide/page {@code i+1}). Used only by the untouched scanned-PDF path,
     * where that invariant still holds (no MD conversion, no section merge/split in between).
     */
    private List<Document> injectImagePaths(List<Document> docs, Map<Integer, List<String>> imageMap) {
        if (imageMap.isEmpty()) return docs;
        List<Document> result = new ArrayList<>(docs.size());
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            List<String> imgs = imageMap.getOrDefault(i + 1, List.of());
            if (imgs.isEmpty()) {
                result.add(doc);
            } else {
                Map<String, Object> meta = new HashMap<>(doc.getMetadata());
                meta.put(MetaKey.IMAGE_PATHS, String.join(",", imgs));
                result.add(new Document(doc.getText(), meta));
            }
        }
        return result;
    }

    private String computeSha256(Path filePath) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var in = new java.security.DigestInputStream(Files.newInputStream(filePath), digest)) {
                byte[] buf = new byte[8192];
                while (in.read(buf) != -1) { /* drain */ }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new DocumentIndexingException("SHA-256 computation failed", e);
        }
    }

    /**
     * Short content-derived key for the images directory/path, kept separate from {@code docId}
     * (which stays filename-prefixed for display and stale-version detection — see
     * {@link DocRegistry#filenameFromDocId} / {@link DocRegistry#findStaleDocId}). Embedding the
     * full docId in every {@code [이미지: ...]} marker and {@code image_paths} metadata entry
     * scales badly for long (often Korean, 30+ char) filenames on documents with many images —
     * this fixed-length hash prefix is repeated instead. 16 hex chars (64 bits) makes an
     * unintended collision between unrelated documents astronomically unlikely; a genuine
     * collision only happens for byte-identical files, which is handled as intentional image
     * sharing (see {@link DocRegistry#existsOtherBySha256}).
     */
    private static final int IMAGE_ID_LENGTH = 16;

    private String imageId(String sha256) {
        return sha256.substring(0, Math.min(IMAGE_ID_LENGTH, sha256.length()));
    }

    private String inferDocType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.contains("guide")) return "guide";
        if (lower.contains("edu") || lower.contains("lesson")) return "education";
        return "manual";
    }

    private static boolean isConnectionError(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof org.springframework.web.client.ResourceAccessException
                    || cur instanceof java.net.ConnectException) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

}
