package com.example.ragagent.controller;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.model.*;
import com.example.ragagent.exception.DocumentIndexingException;
import com.example.ragagent.exception.UnsupportedFileTypeException;
import com.example.ragagent.security.UploadValidator;
import com.example.ragagent.service.IndexingProgressService;
import com.example.ragagent.service.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
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
import java.util.Map;

/**
 * Document management: UI pages, HTMX upload/sync/list/delete fragments,
 * and REST /api/v1/documents + /api/v1/images.
 */
@Controller
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final RagService ragService;
    private final IndexingProgressService progressService;
    private final AppProperties props;
    private final AuditLogger auditLogger;

    public DocumentController(RagService ragService,
                               IndexingProgressService progressService,
                               AppProperties props,
                               AuditLogger auditLogger) {
        this.ragService = ragService;
        this.progressService = progressService;
        this.props = props;
        this.auditLogger = auditLogger;
    }

    private Path documentsDir() {
        return Path.of(props.dataDir()).resolve("documents");
    }

    // ── Page ──────────────────────────────────────────────────────────

    @GetMapping("/documents")
    public String documents(Model model) {
        model.addAttribute("documents", ragService.listDocuments());
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
            @RequestParam MultipartFile file,
            @RequestParam(defaultValue = "latest") String version) throws IOException {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        String filename;
        Path tmp;
        try {
            filename = UploadValidator.sanitizeFilename(file.getOriginalFilename());
            UploadValidator.checkExtension(filename);
            tmp = UploadValidator.stageToTemp(file, filename);
        } catch (UnsupportedFileTypeException e) {
            log.warn("Rejected upload: {}", e.getMessage());
            return ResponseEntity.unprocessableEntity().build();
        } catch (IllegalArgumentException e) {
            log.warn("Rejected upload: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
        String taskId = progressService.newTaskId();
        final Path tmpPath = tmp;
        final String fname = filename;
        final String ver = version;

        Thread.ofVirtual().name("idx-upload-" + taskId).start(() -> {
            try {
                DocumentInfo info = ragService.indexDocument(tmpPath, fname, ver,
                        event -> progressService.publish(taskId, event));
                progressService.publish(taskId, IndexingProgressEvent.done(info));
                auditLogger.log("document.upload", info.docId(),
                        Map.of("filename", fname, "version", ver, "chunks", info.chunks()));
            } catch (Exception e) {
                String msg = isChromaDown(e) ? "ChromaDB 연결 실패" : e.getMessage();
                log.error("Async index error for {}", fname, e);
                progressService.publish(taskId, IndexingProgressEvent.error(fname, msg));
            } finally {
                try { Files.deleteIfExists(tmpPath); } catch (Exception ignored) {}
            }
        });

        return ResponseEntity.accepted().body(Map.of("taskId", taskId));
    }

    /** SSE stream for indexing progress. */
    @GetMapping(value = "/ui/documents/progress/{taskId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter indexingProgress(@PathVariable String taskId) {
        return progressService.subscribe(taskId);
    }

    /** Starts directory sync asynchronously and returns {taskId} (HTTP 202). */
    @PostMapping("/ui/documents/sync")
    @ResponseBody
    public ResponseEntity<Map<String, String>> syncDocumentsUi(
            @RequestParam(defaultValue = "latest") String version) {
        String taskId = progressService.newTaskId();

        Thread.ofVirtual().name("idx-sync-" + taskId).start(() -> {
            try {
                SyncResult result = ragService.syncDirectory(version,
                        event -> progressService.publish(taskId, event));
                progressService.publish(taskId, IndexingProgressEvent.syncDone(result));
                auditLogger.log("document.sync", null,
                        Map.of("indexed", result.indexed().size(),
                               "updated", result.updated().size(),
                               "deleted", result.deleted().size()));
            } catch (Exception e) {
                String msg = isChromaDown(e) ? "ChromaDB 연결 실패" : e.getMessage();
                log.error("Sync error", e);
                progressService.publish(taskId, IndexingProgressEvent.error("sync", msg));
            }
        });

        return ResponseEntity.accepted().body(Map.of("taskId", taskId));
    }

    @DeleteMapping("/ui/documents/{docId}")
    @ResponseBody
    public ResponseEntity<Void> deleteDocumentUi(
            @PathVariable String docId,
            @RequestParam(defaultValue = "latest") String version) throws IOException {
        ragService.deleteDocument(docId, version);
        auditLogger.log("document.delete", docId, Map.of("version", version));
        return ResponseEntity.ok().build();
    }

    @GetMapping("/ui/documents/list")
    public String documentList(Model model) {
        model.addAttribute("documents", ragService.listDocuments());
        return "fragments/doc-table-body :: body";
    }

    // ── REST API ──────────────────────────────────────────────────────

    @PostMapping("/api/v1/documents")
    @ResponseBody
    public ResponseEntity<DocumentInfo> uploadDocumentApi(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "version", defaultValue = "latest") String version) throws IOException {

        if (file.isEmpty()) return ResponseEntity.badRequest().build();

        String filename;
        Path staged;
        try {
            filename = UploadValidator.sanitizeFilename(file.getOriginalFilename());
            UploadValidator.checkExtension(filename);
            staged = UploadValidator.stageToTemp(file, filename);
        } catch (UnsupportedFileTypeException e) {
            log.warn("Rejected upload: {}", e.getMessage());
            return ResponseEntity.unprocessableEntity().build();
        } catch (IllegalArgumentException e) {
            log.warn("Rejected upload: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }

        try {
            Path documentsDir = documentsDir();
            Files.createDirectories(documentsDir);
            Path base = documentsDir.toAbsolutePath().normalize();
            Path dest = base.resolve(filename).normalize();
            if (!dest.startsWith(base)) {
                log.warn("Rejected upload: path escapes documentsDir ({})", filename);
                Files.deleteIfExists(staged);
                return ResponseEntity.badRequest().build();
            }
            if (Files.exists(dest) && computeSha256(staged).equals(computeSha256(dest))) {
                log.debug("Upload no-op: identical content for {}", filename);
                Files.deleteIfExists(staged);
                DocumentInfo existing = ragService.indexDocument(dest, version);
                auditLogger.log("document.upload", existing.docId(),
                        Map.of("filename", filename, "version", version, "chunks", existing.chunks()));
                return ResponseEntity.ok(existing);
            }
            if (Files.exists(dest)) {
                dest = versionedPath(dest);
            }
            Files.copy(staged, dest, StandardCopyOption.REPLACE_EXISTING);
            DocumentInfo info = ragService.indexDocument(dest, version);
            auditLogger.log("document.upload", info.docId(),
                    Map.of("filename", filename, "version", version, "chunks", info.chunks()));
            return ResponseEntity.ok(info);
        } finally {
            try { Files.deleteIfExists(staged); } catch (IOException ignored) {}
        }
    }

    @PostMapping("/api/v1/documents/sync")
    @ResponseBody
    public ResponseEntity<SyncResult> syncDocumentsApi(
            @RequestParam(value = "version", defaultValue = "latest") String version) throws IOException {
        SyncResult result = ragService.syncDirectory(version);
        auditLogger.log("document.sync", null,
                Map.of("indexed", result.indexed().size(),
                       "updated", result.updated().size(),
                       "deleted", result.deleted().size()));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/api/v1/documents")
    @ResponseBody
    public ResponseEntity<List<DocumentInfo>> listDocumentsApi() {
        return ResponseEntity.ok(ragService.listDocuments());
    }

    @DeleteMapping("/api/v1/documents/{docId}")
    @ResponseBody
    public ResponseEntity<Void> deleteDocumentApi(
            @PathVariable String docId,
            @RequestParam(value = "version", defaultValue = "latest") String version) throws IOException {
        ragService.deleteDocument(docId, version);
        auditLogger.log("document.delete", docId, Map.of("version", version));
        return ResponseEntity.noContent().build();
    }

    /**
     * Serves extracted images. Rejects path traversal in docId / filename.
     */
    @GetMapping("/api/v1/images/{docId}/{filename}")
    @ResponseBody
    public ResponseEntity<Resource> getImage(
            @PathVariable String docId,
            @PathVariable String filename) throws IOException {
        if (docId.contains("..") || docId.contains("/") || docId.contains("\\")
                || filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return ResponseEntity.badRequest().build();
        }
        Path imgPath = Path.of(props.dataDir()).resolve("images").resolve(docId).resolve(filename);
        if (!Files.exists(imgPath) || !Files.isRegularFile(imgPath)) {
            return ResponseEntity.notFound().build();
        }
        String contentType = Files.probeContentType(imgPath);
        if (contentType == null) contentType = "application/octet-stream";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate())
                .header("X-Robots-Tag", "noindex, nofollow")
                .body(new FileSystemResource(imgPath));
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private static boolean isChromaDown(Throwable t) {
        while (t != null) {
            if (t instanceof ResourceAccessException) return true;
            t = t.getCause();
        }
        return false;
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
