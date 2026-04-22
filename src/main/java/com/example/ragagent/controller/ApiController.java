package com.example.ragagent.controller;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.model.*;
import com.example.ragagent.service.AgentService;
import com.example.ragagent.service.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private static final Logger log = LoggerFactory.getLogger(ApiController.class);

    private final AgentService agentService;
    private final RagService ragService;
    private final Path documentsDir;

    public ApiController(AgentService agentService, RagService ragService, AppProperties props) {
        this.agentService = agentService;
        this.ragService = ragService;
        this.documentsDir = Path.of(props.dataDir()).resolve("documents");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Health
    // ──────────────────────────────────────────────────────────────────────

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "service", "rag-agent",
                "timestamp", Instant.now().toString()
        );
    }

    // ──────────────────────────────────────────────────────────────────────
    // Chat
    // ──────────────────────────────────────────────────────────────────────

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        if (request.question() == null || request.question().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            ChatResponse response = agentService.chat(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Chat error", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Document Management
    // ──────────────────────────────────────────────────────────────────────

    @PostMapping("/documents")
    public ResponseEntity<DocumentInfo> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "version", defaultValue = "latest") String version) {

        if (file.isEmpty()) return ResponseEntity.badRequest().build();

        try {
            Files.createDirectories(documentsDir);
            String filename = sanitizeFilename(file.getOriginalFilename());
            Path savedPath = documentsDir.resolve(filename);
            file.transferTo(savedPath);

            DocumentInfo info = ragService.indexDocument(savedPath, version);
            return ResponseEntity.ok(info);
        } catch (IOException e) {
            log.error("Document upload error", e);
            return ResponseEntity.internalServerError().build();
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

    // ──────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────────────────────────────

    private String sanitizeFilename(String original) {
        if (original == null) return "upload_" + Instant.now().toEpochMilli();
        return Path.of(original).getFileName().toString()
                .replaceAll("[^a-zA-Z0-9._\\-가-힣]", "_");
    }
}
