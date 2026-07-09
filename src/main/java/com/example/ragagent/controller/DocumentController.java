package com.example.ragagent.controller;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.context.ThreadContext;
import com.example.ragagent.model.*;
import com.example.ragagent.exception.DocumentIndexingException;
import com.example.ragagent.exception.IndexingCancelledException;
import com.example.ragagent.exception.UnsupportedFileTypeException;
import com.example.ragagent.security.UploadValidator;
import com.example.ragagent.service.IndexingProgressService;
import com.example.ragagent.service.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Document management: UI pages, HTMX upload/sync/list/delete fragments,
 * and REST /api/v1/documents + /api/v1/images.
 */
@Controller
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final RagService ragService;
    private final IndexingProgressService progressService;
    private final AuditLogger auditLogger;

    public DocumentController(RagService ragService,
                               IndexingProgressService progressService,
                               AuditLogger auditLogger) {
        this.ragService = ragService;
        this.progressService = progressService;
        this.auditLogger = auditLogger;
    }

    // ── Page ──────────────────────────────────────────────────────────

    @GetMapping("/documents")
    public String documents(ThreadContext ctx, Model model) {
        model.addAttribute("documents", ragService.listDocuments(ctx.userId()));
        return "documents";
    }

    // ── HTMX actions ─────────────────────────────────────────────────

    /**
     * Accepts the file synchronously (transfer + magic-byte check), then starts indexing
     * asynchronously on a virtual thread. Returns {taskId} immediately (HTTP 202).
     */
    @PostMapping("/ui/documents/upload")
    @ResponseBody
    public ResponseEntity<Map<String, String>> uploadDocument(
            ThreadContext ctx,
            @RequestParam MultipartFile file,
            @RequestParam(defaultValue = "latest") String version,
            @RequestParam(required = false) String tags,
            @RequestParam(name = "addImageDescriptions", defaultValue = "false") boolean addImageDescriptions,
            @RequestParam(name = "addHeadingNumbers", defaultValue = "false") boolean addHeadingNumbers) throws IOException {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        String filename;
        Path tmp;
        final List<String> tagList;
        try {
            filename = UploadValidator.sanitizeFilename(file.getOriginalFilename());
            UploadValidator.checkExtension(filename);
            tmp = UploadValidator.stageToTemp(file, filename);
            tagList = TagUtils.parseCsv(tags);   // 검증 실패 시 IllegalArgumentException → 400
        } catch (UnsupportedFileTypeException e) {
            log.warn("Rejected upload: {}", e.getMessage());
            return ResponseEntity.unprocessableEntity().build();
        } catch (IllegalArgumentException e) {
            log.warn("Rejected upload: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
        final String userId = ctx.userId();
        Path dest;
        try {
            // Persist into documentsDir (not just the temp stage) — directory sync treats any
            // registered document missing from disk as user-deleted and wipes its embeddings,
            // so an upload that never lands on disk gets destroyed on the next sync.
            dest = persistToDocumentsDir(tmp, filename, ragService.userDocumentsDir(userId));
        } catch (IllegalArgumentException e) {
            log.warn("Rejected upload: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } finally {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }

        String taskId = progressService.newTaskId();
        final Path docPath = dest;
        final String fname = dest.getFileName().toString();
        final String ver = version;

        Thread worker = Thread.ofVirtual().name("idx-upload-" + taskId).start(() -> {
            try {
                DocumentInfo info = ragService.indexDocument(userId, docPath, fname, ver, tagList,
                    addImageDescriptions, addHeadingNumbers,
                        event -> progressService.publish(taskId, event));
                progressService.publish(taskId, IndexingProgressEvent.done(info));
                auditLogger.log("document.upload", info.docId(),
                    Map.of("filename", fname, "version", ver, "chunks", info.chunks(),
                        "addImageDescriptions", addImageDescriptions,
                        "addHeadingNumbers", addHeadingNumbers));
            } catch (IndexingCancelledException e) {
                // progressService.cancel() already published the terminal 'cancelled' event.
                log.info("[UPLOAD] cancelled by user: taskId={} file={}", taskId, fname);
            } catch (Exception e) {
                String msg = isChromaDown(e) ? "ChromaDB 연결 실패" : e.getMessage();
                log.error("Async index error for {}", fname, e);
                progressService.publish(taskId, IndexingProgressEvent.error(fname, msg));
            }
        });
        progressService.registerWorker(taskId, worker);

        return ResponseEntity.accepted().body(Map.of("taskId", taskId));
    }

    /** SSE stream for indexing progress. */
    @GetMapping(value = "/ui/documents/progress/{taskId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter indexingProgress(@PathVariable String taskId) {
        return progressService.subscribe(taskId);
    }

    /** §6.16.1 — cancels an in-progress upload/sync task: interrupts the worker thread and
     *  completes the SSE progress stream immediately. */
    @PostMapping("/ui/documents/progress/{taskId}/cancel")
    @ResponseBody
    public ResponseEntity<Void> cancelIndexing(@PathVariable String taskId) {
        progressService.cancel(taskId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/ui/documents/{docId}")
    @ResponseBody
    public ResponseEntity<Void> deleteDocumentUi(
            ThreadContext ctx,
            @PathVariable String docId,
            @RequestParam(defaultValue = "latest") String version) throws IOException {
        ragService.deleteDocument(ctx.userId(), docId, version);
        auditLogger.log("document.delete", docId, Map.of("version", version));
        return ResponseEntity.ok().build();
    }

    /** Tags-cell edit form (HTMX fragment) — pre-filled with the document's current tags. */
    @GetMapping("/ui/documents/{docId}/tags/edit")
    public String editTagsForm(ThreadContext ctx, @PathVariable String docId, Model model) {
        DocumentInfo doc = ragService.findDocument(ctx.userId(), docId)
                .orElseThrow(() -> new IllegalArgumentException("문서를 찾을 수 없습니다: " + docId));
        model.addAttribute("doc", doc);
        model.addAttribute("tagsCsv", String.join(", ", doc.tags()));
        return "fragments/doc-table-body :: tagsEdit";
    }

    /** Tags-cell view fragment — also used as the Cancel target for the edit form. */
    @GetMapping("/ui/documents/{docId}/tags/view")
    public String viewTagsCell(ThreadContext ctx, @PathVariable String docId, Model model) {
        DocumentInfo doc = ragService.findDocument(ctx.userId(), docId)
                .orElseThrow(() -> new IllegalArgumentException("문서를 찾을 수 없습니다: " + docId));
        model.addAttribute("doc", doc);
        return "fragments/doc-table-body :: tagsView";
    }

    /** Replaces a document's search-scope tags (metadata-only — no re-embedding). */
    @PatchMapping("/ui/documents/{docId}/tags")
    public String updateTagsUi(ThreadContext ctx, @PathVariable String docId,
                                @RequestParam(defaultValue = "") String tags, Model model) {
        List<String> tagList = TagUtils.parseCsv(tags);   // policy violation → 400 (IllegalArgumentException)
        DocumentInfo doc = ragService.updateDocumentTags(ctx.userId(), docId, tagList);
        auditLogger.log("document.tags_update", docId, Map.of("tags", doc.tags()));
        model.addAttribute("doc", doc);
        return "fragments/doc-table-body :: tagsView";
    }

    @GetMapping("/ui/documents/list")
    public String documentList(ThreadContext ctx, Model model) {
        model.addAttribute("documents", ragService.listDocuments(ctx.userId()));
        return "fragments/doc-table-body :: body";
    }

    /** Distinct tags in use (optional version scope) — powers tag-suggestion chips on upload/chat. */
    @GetMapping("/api/v1/tags")
    @ResponseBody
    public List<String> listTags(@RequestParam(required = false) String version) {
        return ragService.listTags(version);
    }

    /** Distinct versions in use for version-selector UI. */
    @GetMapping("/api/v1/versions")
    @ResponseBody
    public List<String> listVersions() {
        return ragService.listVersions();
    }

    // ── REST API ──────────────────────────────────────────────────────

    @PostMapping("/api/v1/documents")
    @ResponseBody
    public ResponseEntity<DocumentInfo> uploadDocumentApi(
            ThreadContext ctx,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "version", defaultValue = "latest") String version,
            @RequestParam(value = "tags", required = false) String tags,
            @RequestParam(name = "addImageDescriptions", defaultValue = "false") boolean addImageDescriptions,
            @RequestParam(name = "addHeadingNumbers", defaultValue = "false") boolean addHeadingNumbers) throws IOException {

        if (file.isEmpty()) return ResponseEntity.badRequest().build();

        String filename;
        Path staged;
        final List<String> tagList;
        try {
            filename = UploadValidator.sanitizeFilename(file.getOriginalFilename());
            UploadValidator.checkExtension(filename);
            staged = UploadValidator.stageToTemp(file, filename);
            tagList = TagUtils.parseCsv(tags);   // 검증 실패 시 → 400
        } catch (UnsupportedFileTypeException e) {
            log.warn("Rejected upload: {}", e.getMessage());
            return ResponseEntity.unprocessableEntity().build();
        } catch (IllegalArgumentException e) {
            log.warn("Rejected upload: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }

        String userId = ctx.userId();
        Path dest;
        try {
            dest = persistToDocumentsDir(staged, filename, ragService.userDocumentsDir(userId));
        } catch (IllegalArgumentException e) {
            log.warn("Rejected upload: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } finally {
            try { Files.deleteIfExists(staged); } catch (IOException ignored) {}
        }
        DocumentInfo info = ragService.indexDocument(userId, dest,
                dest.getFileName().toString(), version, tagList,
                addImageDescriptions, addHeadingNumbers, e -> {});
        auditLogger.log("document.upload", info.docId(),
                Map.of("filename", filename, "version", version, "chunks", info.chunks(),
                "addImageDescriptions", addImageDescriptions,
                "addHeadingNumbers", addHeadingNumbers));
        return ResponseEntity.ok(info);
    }

    @PostMapping("/api/v1/documents/sync")
    @ResponseBody
    public ResponseEntity<SyncResult> syncDocumentsApi(
            ThreadContext ctx,
            @RequestParam(value = "version", defaultValue = "latest") String version) throws IOException {
        SyncResult result = ragService.syncDirectory(ctx.userId(), version);
        auditLogger.log("document.sync", null,
                Map.of("indexed", result.indexed().size(),
                       "updated", result.updated().size(),
                       "deleted", result.deleted().size()));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/api/v1/documents")
    @ResponseBody
    public ResponseEntity<List<DocumentInfo>> listDocumentsApi(ThreadContext ctx) {
        return ResponseEntity.ok(ragService.listDocuments(ctx.userId()));
    }

    @DeleteMapping("/api/v1/documents/{docId}")
    @ResponseBody
    public ResponseEntity<Void> deleteDocumentApi(
            ThreadContext ctx,
            @PathVariable String docId,
            @RequestParam(value = "version", defaultValue = "latest") String version) throws IOException {
        ragService.deleteDocument(ctx.userId(), docId, version);
        auditLogger.log("document.delete", docId, Map.of("version", version));
        return ResponseEntity.noContent().build();
    }

    // Only these are ever served inline as image/*. Anything else — notably SVG, which can
    // carry an executable <script>/onload and would run same-origin if rendered inline — is
    // forced to download instead.
    private static final Set<String> SAFE_IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp");

    /**
     * Serves extracted images. Rejects path traversal in docId / filename.
     */
    @GetMapping("/api/v1/images/{docId}/{filename}")
    @ResponseBody
    public ResponseEntity<Resource> getImage(
            ThreadContext ctx,
            @PathVariable String docId,
            @PathVariable String filename) throws IOException {
        if (docId.contains("..") || docId.contains("/") || docId.contains("\\")
                || filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return ResponseEntity.badRequest().build();
        }
        Path imagesBase = ragService.userDocumentsDir(ctx.userId()).getParent().resolve("images").normalize();
        Path imgPath = imagesBase.resolve(docId).resolve(filename).normalize();
        // Defense in depth alongside the reject-list above: the resolved path must still
        // land inside imagesBase.
        if (!imgPath.startsWith(imagesBase)) {
            return ResponseEntity.badRequest().build();
        }
        if (!Files.exists(imgPath) || !Files.isRegularFile(imgPath)) {
            return ResponseEntity.notFound().build();
        }

        if (!SAFE_IMAGE_EXTENSIONS.contains(fileExtension(filename))) {
            // Non-raster (SVG, EMF/WMF, etc.) — never render inline. Force download under a
            // locked-down per-response CSP so an embedded script can't execute same-origin
            // even if a browser is coaxed into displaying it directly.
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename(filename).build().toString())
                    .header("Content-Security-Policy", "default-src 'none'; sandbox")
                    .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate())
                    .header("X-Robots-Tag", "noindex, nofollow")
                    .body(new FileSystemResource(imgPath));
        }

        String contentType = Files.probeContentType(imgPath);
        if (contentType == null) contentType = "application/octet-stream";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate())
                .header("X-Robots-Tag", "noindex, nofollow")
                .body(new FileSystemResource(imgPath));
    }

    private static String fileExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return (dot < 0 || dot == filename.length() - 1)
                ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private static boolean isChromaDown(Throwable t) {
        while (t != null) {
            if (t instanceof ResourceAccessException) return true;
            t = t.getCause();
        }
        return false;
    }

    /**
     * Persists a staged upload into {@code documentsDir} so it survives for future directory
     * syncs — {@link com.example.ragagent.ingestion.DocumentIndexer#syncDirectory} treats any
     * registered document missing from disk as user-deleted. No-ops (returns the existing path)
     * if identical content is already there; falls back to a versioned name on a same-name
     * collision with different content.
     *
     * @throws IllegalArgumentException if the resolved destination escapes documentsDir
     */
    private static Path persistToDocumentsDir(Path staged, String filename, Path documentsDir) throws IOException {
        Files.createDirectories(documentsDir);
        Path base = documentsDir.toAbsolutePath().normalize();
        Path dest = base.resolve(filename).normalize();
        if (!dest.startsWith(base)) {
            throw new IllegalArgumentException("path escapes documentsDir: " + filename);
        }
        if (Files.exists(dest) && computeSha256(staged).equals(computeSha256(dest))) {
            log.debug("Upload no-op: identical content for {}", filename);
            return dest;
        }
        if (Files.exists(dest)) {
            dest = versionedPath(dest);
        }
        Files.copy(staged, dest, StandardCopyOption.REPLACE_EXISTING);
        return dest;
    }

    private static String computeSha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var in = new DigestInputStream(Files.newInputStream(path), digest)) {
                byte[] buf = new byte[8192];
                while (in.read(buf) != -1) { /* drain */ }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new DocumentIndexingException("SHA-256 not available", e);
        }
    }

    private static Path versionedPath(Path base) {
        String name = base.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext  = dot > 0 ? name.substring(dot)    : "";
        Path dir = base.getParent();
        for (int v = 2; v <= 99; v++) {
            Path candidate = dir.resolve(stem + "_v" + v + ext);
            if (!Files.exists(candidate)) return candidate;
        }
        return dir.resolve(stem + "_v" + Instant.now().toEpochMilli() + ext);
    }
}
