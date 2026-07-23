package com.example.ragagent.controller;

import com.example.ragagent.context.ThreadContext;
import com.example.ragagent.model.IndexingProgressEvent;
import com.example.ragagent.service.AdminService;
import com.example.ragagent.service.CuratedQaService;
import com.example.ragagent.service.IndexingProgressService;
import com.example.ragagent.service.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Admin UI: vector-store collection/chunk viewer and editor (Chroma and sqlite-vec backends),
 * plus the §10.10 curated-Q&A moderation tab.
 */
@Controller
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    private static final int CURATED_LIST_LIMIT = 50; // §10.10 — small expected volume, no paging yet

    private final AdminService adminService;
    private final RagService   ragService;
    private final IndexingProgressService progressService;
    private final CuratedQaService curatedQaService;

    public AdminController(AdminService adminService, RagService ragService,
                            IndexingProgressService progressService, CuratedQaService curatedQaService) {
        this.adminService = adminService;
        this.ragService   = ragService;
        this.progressService = progressService;
        this.curatedQaService = curatedQaService;
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

    /**
     * Re-embed + re-index (FTS) a single chunk against its current stored text — unlike
     * {@link #updateChunk}, this actually calls the embedding API (and optionally the keyword-
     * extraction LLM) so search results reflect a prior text/keyword edit. Synchronous: single-chunk
     * cost is low enough that the admin button can just wait for the result (contrast with the
     * document-level {@code /admin/documents/{docId}/reindex}, which is async + SSE-tracked).
     */
    @PostMapping("/admin/chunks/{chunkId}/reindex")
    @ResponseBody
    public ResponseEntity<Void> reindexChunk(@PathVariable String chunkId,
                                              @RequestParam String collection,
                                              @RequestBody(required = false) Map<String, Object> body) {
        boolean regenerateKeywords = body != null && Boolean.TRUE.equals(body.get("regenerateKeywords"));
        boolean ok = adminService.reindexChunk(collection, chunkId, regenerateKeywords);
        return ok ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    // ── §10.10 Curated Q&A moderation ───────────────────────────────────────────

    /**
     * Curated Q&A panel fragment — lazy-loaded only when the admin expands the section
     * (the section is collapsed by default so {@code listActive()} doesn't run a DB query
     * on every {@code /admin} page load).
     */
    @GetMapping("/admin/curated")
    public String curatedPanel(Model model) {
        model.addAttribute("curatedEntries", curatedQaService.listActive(CURATED_LIST_LIMIT));
        return "fragments/admin-curated :: panel";
    }

    /** Get full curated entry data for the edit panel. */
    @GetMapping("/admin/curated/{id}/detail")
    @ResponseBody
    public ResponseEntity<?> curatedDetail(@PathVariable long id) {
        return curatedQaService.findById(id)
                .<ResponseEntity<?>>map(row -> ResponseEntity.ok(Map.of(
                        "id",       row.id(),
                        "question", row.question(),
                        "answer",   row.answer())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Update a curated entry's answer text (re-embeds) — admin can edit any user's entry. */
    @PostMapping("/admin/curated/{id}")
    @ResponseBody
    public ResponseEntity<Void> updateCurated(@PathVariable long id, @RequestBody Map<String, Object> body) {
        String newAnswer = body.get("answer") instanceof String s ? s : null;
        boolean updated = curatedQaService.updateAnswer(id, newAnswer);
        return updated ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    /** Force-remove a curated entry regardless of the original asker's own feedback state (moderation). */
    @DeleteMapping("/admin/curated/{id}")
    @ResponseBody
    public ResponseEntity<Void> deleteCurated(@PathVariable long id) {
        boolean removed = curatedQaService.forceRemove(id);
        return removed ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    /**
     * Re-index a document from its saved Markdown file (corrected or raw). Async — starts the
     * work on a virtual thread and returns {@code {taskId}} immediately (202); progress and the
     * terminal outcome are reported via the shared SSE endpoint (same one uploads/sync use):
     * {@code GET /ui/documents/progress/{taskId}}.
     */
    @PostMapping("/admin/documents/{docId}/reindex")
    @ResponseBody
    public ResponseEntity<Map<String, String>> reindexFromMd(@PathVariable String docId) {
        String taskId = progressService.newTaskId();

        Thread worker = Thread.ofVirtual().name("idx-reindex-" + taskId).start(() -> {
            try {
                ragService.reindexFromMd(docId, event -> progressService.publish(taskId, event));
                progressService.publish(taskId, IndexingProgressEvent.of("done", 0, 0, docId, "재인덱싱 완료"));
            } catch (Exception e) {
                log.warn("[REINDEX] 재인덱싱 실패: docId={}, {}", docId, e.getMessage());
                progressService.publish(taskId, IndexingProgressEvent.error(docId, e.getMessage()));
            }
        });
        progressService.registerWorker(taskId, worker);

        return ResponseEntity.accepted().body(Map.of("taskId", taskId));
    }
}
