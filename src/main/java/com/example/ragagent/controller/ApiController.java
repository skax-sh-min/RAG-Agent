package com.example.ragagent.controller;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.CircuitBreaker;
import com.example.ragagent.model.*;
import com.example.ragagent.repository.LlmUsageRepository;
import com.example.ragagent.security.UnsupportedFileTypeException;
import com.example.ragagent.security.UploadValidator;
import com.example.ragagent.service.AgentService;
import com.example.ragagent.service.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;

import java.io.IOException;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST API — equivalent to api.py (FastAPI) in the Python version.
 *
 * Endpoints:
 *   GET  /api/health
 *   POST /api/chat
 *   POST /api/documents
 *   POST /api/documents/sync
 *   GET  /api/documents
 *   DELETE /api/documents/{docId}
 *   GET  /api/llm/usage
 *   GET  /api/llm/usage/history
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private static final Logger log = LoggerFactory.getLogger(ApiController.class);

    private final AgentService agentService;
    private final RagService ragService;
    private final AppProperties props;
    private final LlmUsageRepository usageRepo;
    private final CircuitBreaker circuitBreaker;
    private final Path documentsDir;

    public ApiController(AgentService agentService, RagService ragService,
                         AppProperties props, LlmUsageRepository usageRepo,
                         CircuitBreaker circuitBreaker) {
        this.agentService = agentService;
        this.ragService = ragService;
        this.props = props;
        this.usageRepo = usageRepo;
        this.circuitBreaker = circuitBreaker;
        this.documentsDir = Path.of(props.dataDir()).resolve("documents");
    }

    // ── Health ──────────────────────────────────────────────────────────────

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "service", "rag-agent",
                "timestamp", Instant.now().toString()
        );
    }

    // ── Chat ────────────────────────────────────────────────────────────────

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        if (request.question() == null || request.question().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            ChatResponse response = agentService.chat(request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Chat error", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ── Document Management ─────────────────────────────────────────────────

    @PostMapping("/documents")
    public ResponseEntity<DocumentInfo> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "version", defaultValue = "latest") String version) {

        if (file.isEmpty()) return ResponseEntity.badRequest().build();

        // Sanitize, extension-check, stage to temp, magic-byte check — unified via UploadValidator
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
        } catch (IOException e) {
            log.error("Document upload error (stage)", e);
            return ResponseEntity.internalServerError().build();
        }

        // SHA-256 dedup + copy to final destination
        try {
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
                return ResponseEntity.ok(ragService.indexDocument(dest, version));
            }
            if (Files.exists(dest)) {
                dest = versionedPath(dest);
            }
            Files.copy(staged, dest, StandardCopyOption.REPLACE_EXISTING);
            return ResponseEntity.ok(ragService.indexDocument(dest, version));
        } catch (IOException e) {
            log.error("Document upload error", e);
            return ResponseEntity.internalServerError().build();
        } finally {
            try { Files.deleteIfExists(staged); } catch (IOException ignored) {}
        }
    }

    @PostMapping("/documents/sync")
    public ResponseEntity<SyncResult> syncDocuments(
            @RequestParam(value = "version", defaultValue = "latest") String version) {
        try {
            SyncResult result = ragService.syncDirectory(version);
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            log.error("Sync error", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/documents")
    public ResponseEntity<List<DocumentInfo>> listDocuments() {
        return ResponseEntity.ok(ragService.listDocuments());
    }

    @DeleteMapping("/documents/{docId}")
    public ResponseEntity<Void> deleteDocument(
            @PathVariable String docId,
            @RequestParam(value = "version", defaultValue = "latest") String version) {
        try {
            ragService.deleteDocument(docId, version);
            return ResponseEntity.noContent().build();
        } catch (IOException e) {
            log.error("Delete error", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ── Image Serving ────────────────────────────────────────────────────────

    /**
     * Serves extracted images stored under data/images/{docId}/{filename}.
     * Rejects path traversal attempts (.. or / in either segment).
     */
    @GetMapping("/images/{docId}/{filename}")
    public ResponseEntity<Resource> getImage(
            @PathVariable String docId,
            @PathVariable String filename) {
        if (docId.contains("..") || docId.contains("/") || docId.contains("\\")
                || filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return ResponseEntity.badRequest().build();
        }
        Path imgPath = Path.of(props.dataDir()).resolve("images").resolve(docId).resolve(filename);
        if (!Files.exists(imgPath) || !Files.isRegularFile(imgPath)) {
            return ResponseEntity.notFound().build();
        }
        try {
            String contentType = Files.probeContentType(imgPath);
            if (contentType == null) contentType = "application/octet-stream";
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate())
                    .header("X-Robots-Tag", "noindex, nofollow")
                    .body(new FileSystemResource(imgPath));
        } catch (IOException e) {
            log.error("Image serve error", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ── LLM Usage ───────────────────────────────────────────────────────────

    /** Provider-level daily / weekly / monthly summary + Circuit Breaker state. */
    @GetMapping("/llm/usage")
    public List<UsageReport> getLlmUsage() {
        Map<String, Instant> blocked = circuitBreaker.getBlockedProviders();
        return props.llmSafe().providers().stream()
                .map(cfg -> {
                    String name = cfg.name();
                    Instant until = blocked.get(name);
                    return new UsageReport(
                            name,
                            cfg.type(),
                            cfg.model(),
                            usageRepo.getDaily(name),
                            usageRepo.getWeekly(name),
                            usageRepo.getMonthly(name),
                            until != null ? until.toString() : null
                    );
                })
                .toList();
    }

    /** Daily token history per provider for Chart.js stacked bar chart. */
    @GetMapping("/llm/usage/history")
    public Map<String, List<LlmUsageRepository.DailyRow>> getLlmUsageHistory(
            @RequestParam(defaultValue = "30") int days) {
        int safeDays = Math.min(Math.max(days, 1), 365);
        return props.llmSafe().providers().stream().collect(
                Collectors.toMap(
                        AppProperties.ProviderConfig::name,
                        cfg -> usageRepo.getDailyHistory(cfg.name(), safeDays),
                        (a, b) -> a
                )
        );
    }

    // ── Response records ────────────────────────────────────────────────────

    public record UsageReport(
            String provider,
            String type,
            String model,
            LlmUsageRepository.PeriodSummary daily,
            LlmUsageRepository.PeriodSummary weekly,
            LlmUsageRepository.PeriodSummary monthly,
            String blockedUntil   // ISO-8601 or null
    ) {}

    // ── Internal helpers ────────────────────────────────────────────────────

    /**
     * Strips any directory components and replaces disallowed characters with '_'.
     * Rejects names that are blank, dot-only ('.', '..', '...'), or start with a dot
     * (hidden files / leading-dot traversal-like names) by throwing IllegalArgumentException.
     */
    private static String computeSha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var in = new DigestInputStream(Files.newInputStream(path), digest)) {
                byte[] buf = new byte[8192];
                while (in.read(buf) != -1) { /* drain */ }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
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
