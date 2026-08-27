package com.example.ragagent.controller;

import com.example.ragagent.context.ThreadContext;
import com.example.ragagent.model.IndexingProgressEvent;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.security.CurrentUser;
import com.example.ragagent.service.AdminService;
import com.example.ragagent.service.CuratedQaService;
import com.example.ragagent.service.CuratedSubmissionService;
import com.example.ragagent.service.IndexingProgressService;
import com.example.ragagent.service.RagService;
import com.example.ragagent.service.RetrievalMetricsService;
import com.example.ragagent.service.ThreadAdminService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin UI: vector-store collection/chunk viewer and editor (Chroma and sqlite-vec backends),
 * the §10.10 curated-Q&A moderation tab, and the 청크 추가 submission-review tab.
 */
@Controller
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final AdminService adminService;
    private final RagService   ragService;
    private final IndexingProgressService progressService;
    private final CuratedQaService curatedQaService;
    private final CuratedSubmissionService submissionService;
    private final RetrievalMetricsService retrievalMetricsService;
    private final ThreadAdminService threadAdminService;
    private final CurrentUser currentUser;

    public AdminController(AdminService adminService, RagService ragService,
                            IndexingProgressService progressService, CuratedQaService curatedQaService,
                            CuratedSubmissionService submissionService,
                            RetrievalMetricsService retrievalMetricsService,
                            ThreadAdminService threadAdminService, CurrentUser currentUser) {
        this.adminService = adminService;
        this.ragService   = ragService;
        this.progressService = progressService;
        this.curatedQaService = curatedQaService;
        this.submissionService = submissionService;
        this.retrievalMetricsService = retrievalMetricsService;
        this.threadAdminService = threadAdminService;
        this.currentUser = currentUser;
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
                         @RequestParam(defaultValue = "20") int limit,
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
    public ResponseEntity<Map<String, Object>> deleteChunk(@PathVariable String chunkId,
                                             @RequestParam String collection) {
        AdminService.DeleteResult result = adminService.deleteChunk(collection, chunkId);
        // Reports the owning document's new chunk count so the registry table can update that one
        // number in place; null (unknown document / legacy row) simply leaves it as it was.
        Map<String, Object> body = new HashMap<>();
        body.put("docId", result.docId());
        body.put("remainingChunks", result.remainingChunks());
        return ResponseEntity.ok(body);
    }

    /**
     * Recomputes every document's stored chunk count from the vector store — a one-off repair for
     * rows that drifted while chunk deletion did not maintain them. Kept under {@code /admin/**}
     * (ROLE_ADMIN gated, §6.17) like every other registry-mutating action.
     */
    @PostMapping("/admin/registry/reconcile-chunks")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> reconcileChunkCounts() {
        AdminService.ReconcileResult r = adminService.reconcileChunkCounts();
        log.info("[REGISTRY] 청크 수 재계산 요청 처리: 대조 {}건, 수정 {}건", r.checked(), r.fixed());
        return ResponseEntity.ok(Map.of("checked", r.checked(), "fixed", r.fixed()));
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
     * on every {@code /admin} page load). Paginated (20/50/100 per page, default 20) so the
     * panel stays usable as the curated set grows past the old 50-row cap.
     */
    @GetMapping("/admin/curated")
    public String curatedPanel(@RequestParam(defaultValue = "0")  int offset,
                                @RequestParam(defaultValue = "20") int limit,
                                Model model) {
        model.addAttribute("curatedEntries", curatedQaService.listActive(offset, limit));
        model.addAttribute("offset", offset);
        model.addAttribute("limit",  limit);
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

    // ── 검색 진단 수치 (3단계) ──────────────────────────────────────────────────

    /**
     * Retrieval-diagnostics panel fragment — same lazy-load-on-expand pattern as
     * {@link #curatedPanel}, so {@code /admin} itself never pays for this query.
     *
     * <p>Read-only by design: it exists to answer "is retrieval behaving" across many turns, and
     * every knob it informs already lives on {@code /settings}. Deliberately not user-scoped —
     * it is a deployment-wide operator view, gated by {@code /admin/**}'s ROLE_ADMIN.
     */
    @GetMapping("/admin/retrieval-metrics")
    public String retrievalMetricsPanel(@RequestParam(defaultValue = "0")  int offset,
                                        @RequestParam(defaultValue = "20") int limit,
                                        Model model) {
        model.addAttribute("metricTurns",  retrievalMetricsService.recent(offset, limit));
        model.addAttribute("metricsTotal", retrievalMetricsService.count());
        model.addAttribute("offset", offset);
        model.addAttribute("limit",  limit);
        return "fragments/admin-retrieval-metrics :: panel";
    }

    // ── §6.25 대화 목록 (전 사용자) ─────────────────────────────────────────────

    /**
     * 대화 목록 패널 — same lazy-load-on-expand pattern as {@link #curatedPanel}.
     *
     * <p>Deliberately <b>not</b> under {@code /api/v1/**}: that prefix is CSRF-exempt and
     * guest-open in management-only mode (§6.17), which would hand every user's conversation
     * titles to anyone. Here it inherits the {@code ROLE_ADMIN} gate, same reasoning as
     * {@link #pendingSubmissionCount}. It is also a different endpoint from {@code /ui/threads},
     * which is the signed-in user's own sidebar and stays user-scoped.
     *
     * @param userId optional owner filter; {@code sort} is parsed into a closed set
     *               ({@code ThreadAdminRepository.Sort}) since {@code ORDER BY} can't be a bind
     *               parameter
     */
    @GetMapping("/admin/threads")
    public String threadPanel(@RequestParam(required = false) String userId,
                              @RequestParam(required = false) String sort,
                              @RequestParam(defaultValue = "0")  int offset,
                              @RequestParam(defaultValue = "20") int limit,
                              Model model) {
        model.addAttribute("panel", threadAdminService.panel(userId, sort, offset, limit));
        return "fragments/admin-threads :: panel";
    }

    /**
     * One conversation's turns — the drill-down opened by 상세, fetched on demand rather than
     * rendered with the list (a deployment can hold thousands of turns across the page's rows).
     *
     * <p>The owner is resolved from the thread id server-side, and the rows carry no answer text
     * at all ({@code ThreadAdminRepository.TurnRow}) — reading an answer is a separate audited
     * call, not something this listing can be edited into exposing.
     */
    @GetMapping("/admin/threads/{threadId}/turns")
    public String threadTurns(@PathVariable String threadId, Model model) {
        model.addAttribute("turns", threadAdminService.turns(threadId));
        model.addAttribute("threadId", threadId);
        return "fragments/admin-threads :: turns";
    }

    // ── 청크 추가 게시판 — 제안 검토 ────────────────────────────────────────────

    /**
     * Submission panel fragment — same lazy-load-on-expand pattern as {@link #curatedPanel}.
     * Defaults to {@code pending} (the actionable set: "아직 등록되지 않은 청크").
     */
    @GetMapping("/admin/submissions")
    public String submissionPanel(@RequestParam(defaultValue = "pending") String status,
                                  @RequestParam(defaultValue = "0")  int offset,
                                  @RequestParam(defaultValue = "20") int limit,
                                  Model model) {
        // "all" is the UI's word for "no filter"; the repository's is null/blank.
        String filter = "all".equals(status) ? null : status;
        model.addAttribute("submissions", submissionService.listForAdmin(filter, offset, limit));
        model.addAttribute("submissionStatus", status);
        model.addAttribute("offset", offset);
        model.addAttribute("limit",  limit);
        return "fragments/admin-submissions :: panel";
    }

    /**
     * Drives the header badge (polled ~60 s). Deliberately under {@code /admin/**} rather than
     * {@code /api/v1/**}: the latter is CSRF-exempt and guest-open in management-only mode, which
     * would hand the pending count to anyone; here it inherits the {@code ROLE_ADMIN} gate.
     */
    @GetMapping("/admin/submissions/pending-count")
    @ResponseBody
    public Map<String, Integer> pendingSubmissionCount() {
        return Map.of("count", submissionService.countPending());
    }

    /** Full submission text for the review panel — never truncated (see below). */
    @GetMapping("/admin/submissions/{id}/detail")
    @ResponseBody
    public ResponseEntity<?> submissionDetail(@PathVariable long id) {
        return submissionService.findById(id)
                .<ResponseEntity<?>>map(s -> ResponseEntity.ok(Map.of(
                        "id",     s.id(),
                        "title",  s.title(),
                        "body",   s.body(),
                        "tags",   s.tags() == null ? "" : s.tags(),
                        "author", s.authorUserId(),
                        "status", s.displayStatus(),
                        // 승인 시 몇 개 청크로 나뉘는지 미리 보여준다(승인 후에는 실제 생성된 개수).
                        "chunkPreview", s.chunkCount() > 0
                                ? s.chunkCount() : submissionService.previewChunkCount(s.body()))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 임베딩 실행 — approves the submission and indexes it as a curated chunk. The optional
     * {@code title}/{@code body} in the request body are the admin's edits; they replace the
     * author's text on both the curated row and the submission itself.
     *
     * <p>This is the only checkpoint between user-authored text and the {@code [검색된 문서]} block
     * of an answer prompt — the review UI shows the body in full for exactly that reason, and there
     * is deliberately no bulk-approve or auto-approve path.
     */
    @PostMapping("/admin/submissions/{id}/approve")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> approveSubmission(
            @PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {

        String title = (body != null && body.get("title") instanceof String s) ? s : null;
        String text  = (body != null && body.get("body")  instanceof String s) ? s : null;
        // null tags = "관리자가 태그를 건드리지 않음" → 제안에 저장된 값 유지. 빈 문자열은 "모두 해제".
        List<String> tags = (body != null && body.get("tags") instanceof String s)
                ? com.example.ragagent.model.TagUtils.parseTagList(s) : null;

        return submissionService.approve(id, currentUser.userId(), title, text, tags)
                .map(curatedId -> ResponseEntity.ok(Map.<String, Object>of("curatedId", curatedId)))
                .orElseGet(() -> ResponseEntity.status(409).body(Map.<String, Object>of(
                        "status", "not_pending",
                        "message", "이미 처리되었거나 철회된 제안입니다.")));
    }

    /** 거부 — {@code reason} is required (it is the author's only feedback). */
    @PostMapping("/admin/submissions/{id}/reject")
    @ResponseBody
    public ResponseEntity<Void> rejectSubmission(@PathVariable long id,
                                                 @RequestBody Map<String, Object> body) {
        String reason = body.get("reason") instanceof String s ? s : null;
        boolean ok = submissionService.reject(id, currentUser.userId(), reason);
        return ok ? ResponseEntity.ok().build() : ResponseEntity.status(409).build();
    }

    /**
     * Re-index a document from its saved Markdown file (corrected or raw). Async — starts the
     * work on a virtual thread and returns {@code {taskId}} immediately (202); progress and the
     * terminal outcome are reported via the shared SSE endpoint (same one uploads/sync use):
     * {@code GET /ui/documents/progress/{taskId}}.
     *
     * <p>Pre-flight: unless {@code force=true}, two read-only checks run first and a {@code 409}
     * describing what they found is returned <em>without starting any work</em>, so the operator can
     * proceed anyway ({@code force=true}) or stop:
     *
     * <ol>
     *   <li>code-fence defects in the saved MD (with line numbers). This path never repairs fences
     *       itself — it does not re-run the rewriting correction passes (see
     *       {@code DocumentIndexer.postProcessIfNeeded}) — so a defect found here would otherwise be
     *       baked into the new chunks silently;</li>
     *   <li>chunks hand-edited in {@code /admin} ({@link MetaKey#EDITED_AT}). Re-indexing rebuilds
     *       every chunk from the MD file, which never received those edits, so they are about to be
     *       discarded — this is the warning that makes that consequence visible before the fact
     *       rather than after.</li>
     * </ol>
     *
     * <p>Both are reported in one response: they are independent findings about the same click, and
     * asking the operator twice in a row would train them to click through both.
     */
    /** 0 when the document isn't in the registry — a missing document is the re-index call's own
     *  error to report, not something the pre-flight should turn into a confusing warning. */
    private long countEditedChunks(String userId, String docId) {
        return ragService.findDocument(userId, docId)
                .map(d -> adminService.countEditedChunks(adminService.collectionFor(d.version()), docId))
                .orElse(0L);
    }

    @PostMapping("/admin/documents/{docId}/reindex")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> reindexFromMd(
            ThreadContext ctx,
            @PathVariable String docId,
            @RequestParam(defaultValue = "false") boolean force) {

        if (!force) {
            var problems = ragService.checkReindexFenceHealth(docId);
            long editedChunks = countEditedChunks(ctx.userId(), docId);
            if (!problems.isEmpty() || editedChunks > 0) {
                log.info("[REINDEX] 사전 확인 요청: docId={}, 펜스 문제={}건, 편집된 청크={}개",
                        docId, problems.size(), editedChunks);
                return ResponseEntity.status(409).body(Map.of(
                        "status", "preflight_warnings",
                        "docId", docId,
                        "editedChunks", editedChunks,
                        "problems", problems.stream().map(p -> Map.of(
                                "line", p.line(), "kind", p.kind(), "message", p.message())).toList()));
            }
        }

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
