package com.example.ragagent.controller;

import com.example.ragagent.context.ThreadContext;
import com.example.ragagent.service.AdminService;
import com.example.ragagent.service.RagService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Admin UI: vector-store collection/chunk viewer and editor (Chroma and sqlite-vec backends).
 */
@Controller
public class AdminController {

    private final AdminService adminService;
    private final RagService   ragService;

    public AdminController(AdminService adminService, RagService ragService) {
        this.adminService = adminService;
        this.ragService   = ragService;
    }

    // ── Page ─────────────────────────────────────────────────────────────────

    @GetMapping("/admin")
    public String adminPage(ThreadContext ctx, Model model) {
        var result = adminService.listCollections();
        model.addAttribute("collections",     result.items());
        model.addAttribute("chromaAvailable", result.available());
        model.addAttribute("vectorStore",     adminService.vectorStoreView());
        model.addAttribute("documents",       ragService.listDocuments(ctx.userId()));
        return "admin";
    }

    // ── HTMX fragments ───────────────────────────────────────────────────────

    /** Chunk table fragment — loaded when user selects a collection (+ optional docId filter). */
    @GetMapping("/admin/chunks")
    public String chunks(ThreadContext ctx,
                         @RequestParam String collection,
                         @RequestParam(required = false) String docId,
                         @RequestParam(defaultValue = "0")  int offset,
                         @RequestParam(defaultValue = "50") int limit,
                         Model model) {
        model.addAttribute("chunks",     adminService.getChunks(collection, docId, offset, limit));
        model.addAttribute("collection", collection);
        model.addAttribute("docId",      docId);
        model.addAttribute("offset",     offset);
        model.addAttribute("limit",      limit);
        model.addAttribute("documents",  ragService.listDocuments(ctx.userId()));
        return "fragments/admin-chunks :: table";
    }

    // ── REST actions ──────────────────────────────────────────────────────────

    /** Delete a single chunk by its ChromaDB ID. */
    @DeleteMapping("/admin/chunks/{chunkId}")
    @ResponseBody
    public ResponseEntity<Void> deleteChunk(@PathVariable String chunkId,
                                             @RequestParam String collection) {
        adminService.deleteChunk(collection, chunkId);
        return ResponseEntity.ok().build();
    }

    /** Get full chunk data (text + metadata) for the edit panel. */
    @GetMapping("/admin/chunks/{chunkId}/detail")
    @ResponseBody
    public ResponseEntity<?> chunkDetail(@PathVariable String chunkId,
                                          @RequestParam String collection) {
        AdminService.ChunkRow row = adminService.getChunk(collection, chunkId);
        if (row == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of(
                "id",       row.id(),
                "text",     row.fullText(),
                "metadata", row.metadata()
        ));
    }

    /** Update chunk text and/or metadata (re-upserts with original embedding). */
    @PostMapping("/admin/chunks/{chunkId}")
    @ResponseBody
    public ResponseEntity<Void> updateChunk(@PathVariable String chunkId,
                                             @RequestParam String collection,
                                             @RequestBody Map<String, Object> body) {
        String newText = body.get("text") instanceof String s ? s : null;
        Map<String, String> newMeta = null;
        if (body.get("metadata") instanceof Map<?, ?> m) {
            // Defensive: only string key/value pairs pass through — a nested object or
            // non-string value in the request silently drops that entry instead of risking
            // a ClassCastException wherever the map is later read as Map<String, String>.
            newMeta = new HashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() instanceof String k && e.getValue() instanceof String v) {
                    newMeta.put(k, v);
                }
            }
        }
        adminService.updateChunk(collection, chunkId, newText, newMeta);
        return ResponseEntity.ok().build();
    }

    /** Re-index a document from its saved Markdown file (corrected or raw). */
    @PostMapping("/admin/documents/{docId}/reindex")
    @ResponseBody
    public ResponseEntity<?> reindexFromMd(@PathVariable String docId) throws java.io.IOException {
        try {
            ragService.reindexFromMd(docId);
            return ResponseEntity.ok(Map.of("message", "재인덱싱 완료"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }
}
