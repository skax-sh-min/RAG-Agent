package com.example.ragagent.service;

import com.example.ragagent.ingestion.CuratedTextUtils;
import com.example.ragagent.ingestion.DocRegistry;
import com.example.ragagent.ingestion.MarkdownNoiseNormalizer;
import com.example.ragagent.ingestion.VectorStoreFacade;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.model.ResponseMode;
import com.example.ragagent.repository.CuratedQaRepository;
import com.example.ragagent.repository.CuratedQaRepository.CuratedQa;
import com.example.ragagent.repository.MemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * §10.10 — promotes 👍'd chat turns into a separately embedded, shared knowledge axis (reserved
 * vector-store version namespace {@value #CURATED_VERSION} — auto-isolated per backend: a
 * distinct Chroma collection / a sqlite-vec partition key, version-agnostic so it survives
 * document re-indexing). See documents/PLAN.md §10.10 for the full design.
 *
 * <p>The DB snapshot ({@code curated_qa}) is written synchronously (cheap local write, same
 * request as the feedback flip); the embedding call is background + debounced so an accidental
 * like→unlike never pays for an LLM/embedding round-trip on the interactive path (§6.12).
 */
@Service
public class CuratedQaService {

    private static final Logger log = LoggerFactory.getLogger(CuratedQaService.class);

    /** Reserved vectorstore version namespace for curated Q&A — never a real document version. */
    public static final String CURATED_VERSION = "curated";

    private static final long DEFAULT_EMBED_DEBOUNCE_MILLIS = 3_000L;

    private final CuratedQaRepository repository;
    private final MemoryService memoryService;
    private final ThreadMetaService threadMetaService;
    private final VectorStoreFacade vectorStore;
    private final long embedDebounceMillis;

    @Autowired
    public CuratedQaService(CuratedQaRepository repository, MemoryService memoryService,
                            ThreadMetaService threadMetaService, VectorStoreFacade vectorStore) {
        this(repository, memoryService, threadMetaService, vectorStore, DEFAULT_EMBED_DEBOUNCE_MILLIS);
    }

    /** Package-private — lets tests shrink the debounce instead of waiting out the real 3s. */
    CuratedQaService(CuratedQaRepository repository, MemoryService memoryService,
                     ThreadMetaService threadMetaService, VectorStoreFacade vectorStore,
                     long embedDebounceMillis) {
        this.repository = repository;
        this.memoryService = memoryService;
        this.threadMetaService = threadMetaService;
        this.vectorStore = vectorStore;
        this.embedDebounceMillis = embedDebounceMillis;
    }

    /**
     * Call after a turn's feedback transitions INTO {@code LIKE}. Upserts the curated_qa snapshot
     * synchronously, then embeds on a background virtual thread after a short debounce:
     * <ol>
     *   <li>sleep {@link #embedDebounceMillis} — lets a fat-fingered like→unlike cancel before any
     *       embedding API call is made (cost-saving; correctness does not depend on this step)</li>
     *   <li>re-check feedback right before the embed call — skip entirely if no longer LIKE</li>
     *   <li>after the embed call succeeds, re-check once more and compensate (delete) if the turn
     *       was unliked while the call was in flight</li>
     * </ol>
     * Mirrors the DISLIKE-discard guard in {@link ConversationSummarizerService#precompute}.
     *
     * <p>L-mode answers (§ ResponseMode) are skipped entirely — an L answer already preserves the
     * source document's own wording almost verbatim, so embedding it again would just duplicate a
     * vector that's already in the index under the real document. The curated_qa row is still
     * created (unlike/edit/admin-listing keep working), only the embed call is skipped.
     */
    public void onLike(String userId, String threadId, long turnId) {
        Optional<MemoryRepository.Turn> turnOpt = memoryService.getTurn(userId, threadId, turnId);
        if (turnOpt.isEmpty()) {
            log.warn("[CURATED] onLike: turn not found userId={} threadId={} turnId={}", userId, threadId, turnId);
            return;
        }
        MemoryRepository.Turn turn = turnOpt.get();
        String version = threadMetaService.findById(userId, threadId)
                .map(t -> t.version())
                .orElse(null);

        // 질문 당시의 검색 스코프(태그)를 그대로 승계한다 — 그 태그로 좁혀 얻은 답변이므로 이후
        // 같은 스코프에서 다시 검색될 때 살아남아야 한다. 태그 없이(전체 검색) 물은 질문이면 빈 값이
        // 되고, 그 경우 buildDocument()가 태그 메타데이터를 아예 붙이지 않아 어떤 태그 스코프에서도
        // 걸러지지 않는다(RetrievalService.filterByTags의 큐레이션 면제).
        long curatedId = repository.upsertActive(turnId, userId, threadId,
                turn.question(), turn.answer(), version, turn.selectedTags());

        if (ResponseMode.parse(turn.responseMode()) == ResponseMode.L) {
            log.debug("[CURATED] embed skipped (L-mode answer already mirrors source content) turnId={}", turnId);
            return;
        }

        Thread.ofVirtual().name("curated-embed-" + curatedId).start(() -> {
            try {
                Thread.sleep(embedDebounceMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!isStillLiked(userId, threadId, turnId)) {
                log.debug("[CURATED] embed skipped (unliked during debounce) turnId={}", turnId);
                return;
            }
            embed(curatedId, userId, threadId, turnId);
        });
    }

    /**
     * Call after a turn's feedback transitions OUT OF {@code LIKE} (to NONE or DISLIKE).
     * Deactivates the row synchronously (fast, local); vector removal runs on a background thread
     * since a Chroma delete is a network round-trip (§6.12 — never block the interactive path on
     * remote I/O). Safe no-op if nothing was ever embedded (delete-by-id on a missing id is a
     * no-op on both backends) or if the turn was never promoted at all.
     */
    public void onUnlike(String userId, String threadId, long turnId) {
        Optional<CuratedQa> existing = repository.findBySourceTurnId(turnId);
        if (existing.isEmpty() || !"active".equals(existing.get().status())) return;

        repository.deactivate(turnId);
        long curatedId = existing.get().id();
        Thread.ofVirtual().name("curated-deindex-" + curatedId).start(() ->
                deleteVector(curatedId));
    }

    /**
     * §10.10 step ④ — looked up by the originating turn (all the chat UI knows — threadId/turnId,
     * not the curated row's own id). The caller (controller) is responsible for the ownership
     * check — {@code memoryService.getFeedback} already scopes by (userId, threadId), same as the
     * existing feedback-toggle endpoint. Used by both the GET (populate the edit box) and PATCH
     * (save) chat-inline-edit endpoints.
     */
    public Optional<CuratedQa> findActiveByTurn(long turnId) {
        return repository.findBySourceTurnId(turnId).filter(r -> "active".equals(r.status()));
    }

    /** §10.10 step ④ — owner edit path (chat inline "편집"), see {@link #findActiveByTurn}. */
    public boolean updateAnswerForTurn(String userId, String threadId, long turnId, String newAnswer) {
        Optional<CuratedQa> rowOpt = findActiveByTurn(turnId);
        if (rowOpt.isEmpty()) return false;
        return updateAnswer(rowOpt.get().id(), newAnswer);
    }

    /**
     * §10.10 step ④ — edit path shared by both the owner (via {@link #updateAnswerForTurn}) and
     * the {@code /admin} curated tab (looked up directly by id there). Re-embeds on a background
     * thread — no debounce and no like-state re-check here: unlike {@link #onLike}, an edit is an
     * explicit save action, not a promotion that can race with an accidental unlike.
     */
    public boolean updateAnswer(long curatedId, String newAnswer) {
        if (newAnswer == null || newAnswer.isBlank()) return false;
        if (repository.findById(curatedId).isEmpty()) return false;
        repository.updateAnswer(curatedId, newAnswer);
        Thread.ofVirtual().name("curated-reembed-" + curatedId).start(() ->
                embedActiveRow(curatedId, "edit"));
        return true;
    }

    /**
     * 청크 추가 — admin approval of a user-submitted chunk. Creates the {@code curated_qa} row
     * synchronously (so the approving request can report success and link the submission to it),
     * then embeds on a background virtual thread exactly like the edit path: no debounce and no
     * feedback re-check, since an approval is an explicit one-way action with no unlike to race.
     *
     * <p>{@code title} lands in the {@code question} column on purpose — {@code defaultSearchText()}
     * embeds {@code question + answer}, so a descriptive title is what makes a manually written
     * chunk retrievable by a question-shaped query at all. Returns the new curated row id.
     */
    public List<Long> createFromSubmission(long submissionId, String authorUserId, String title,
                                           List<String> bodyChunks, String tags) {
        List<Long> curatedIds = new java.util.ArrayList<>(bodyChunks.size());
        for (String chunk : bodyChunks) {
            // 제목은 모든 청크에 반복 부여한다 — defaultSearchText()가 question+answer를 임베딩하므로
            // 2번째 청크부터 제목이 없으면 질문형 질의와의 매칭이 급격히 나빠진다(문서 인덱싱의
            // reinjectHeadingForSplitPieces와 같은 이유).
            curatedIds.add(repository.insertManual(submissionId, authorUserId, title, chunk, tags));
        }
        for (long curatedId : curatedIds) {
            Thread.ofVirtual().name("curated-embed-" + curatedId).start(() ->
                    embedActiveRow(curatedId, "submission"));
        }
        return List.copyOf(curatedIds);
    }

    /**
     * 청크 추가 — takes down every curated row belonging to one submission, together. Approval can
     * create N rows, and a submission is 등록 완료/회수됨 as a whole (전부/전무), so a partial removal
     * would leave the author looking at a half-registered proposal. Used by the admin curated panel
     * (via {@link #forceRemove}) and by the approval-race rollback in {@code CuratedSubmissionService}.
     * Returns how many rows were deactivated.
     */
    public int forceRemoveBySubmission(long submissionId) {
        List<CuratedQa> rows = repository.findActiveBySubmissionId(submissionId);
        for (CuratedQa row : rows) {
            repository.deactivateById(row.id());
            long curatedId = row.id();
            Thread.ofVirtual().name("curated-deindex-" + curatedId).start(() -> deleteVector(curatedId));
        }
        if (!rows.isEmpty()) {
            log.info("[CURATED] 제안 {}의 청크 {}건 회수", submissionId, rows.size());
        }
        return rows.size();
    }

    /**
     * §10.10 step ④ — admin moderation path: deactivates + de-indexes regardless of the original
     * asker's own feedback state (separate authorization from {@link #onUnlike}'s ownership check
     * — the admin curated tab looks entries up by curated id, not by thread/turn).
     */
    public boolean forceRemove(long curatedId) {
        Optional<CuratedQa> rowOpt = repository.findById(curatedId);
        if (rowOpt.isEmpty() || !"active".equals(rowOpt.get().status())) return false;

        // 사용자 제안에서 온 행이면 같은 제안의 나머지 청크도 함께 내린다 — 제안은 전부/전무이므로
        // 한 청크만 지워 반쪽 등록 상태를 만들지 않는다(Submission.displayStatus 참고).
        Long submissionId = rowOpt.get().sourceSubmissionId();
        if (submissionId != null) {
            return forceRemoveBySubmission(submissionId) > 0;
        }

        // By id, not by turn — a manual (user-submitted) row has source_turn_id = NULL, which no
        // WHERE source_turn_id = ? can ever match.
        repository.deactivateById(curatedId);
        Thread.ofVirtual().name("curated-deindex-" + curatedId).start(() -> deleteVector(curatedId));
        return true;
    }

    /** Same as {@link #listActive(int, int)} with {@code offset=0}. */
    public List<CuratedQa> listActive(int limit) {
        return repository.findAllActive(limit);
    }

    /** §10.10 step ④ — admin curated-Q&A browser listing, paginated. */
    public List<CuratedQa> listActive(int offset, int limit) {
        return repository.findAllActive(offset, limit);
    }

    /** §10.10 step ④ — direct id lookup for the admin edit panel (chat's owner-edit path looks
     *  up by turn instead, see {@link #updateAnswerForTurn}). */
    public Optional<CuratedQa> findById(long id) {
        return repository.findById(id);
    }

    /**
     * §10.10 embedding-fallback — turn ids (among the given set) whose active curated row is
     * currently stuck in {@code embed_status='failed'}. chat.html's turn-history render uses this
     * to show a "임베딩 실패" badge next to the curated-edit pencil icon.
     */
    public Set<Long> findFailedTurnIds(List<Long> turnIds) {
        return repository.findFailedTurnIds(turnIds);
    }

    /**
     * Embeds an already-active row and records the outcome in {@code embed_status} — used by both
     * the owner/admin edit path and the submission-approval path. No like-state re-check (unlike
     * {@link #embed}): both callers are explicit save/approve actions that can't race an unlike.
     */
    private void embedActiveRow(long curatedId, String reason) {
        Optional<CuratedQa> rowOpt = repository.findById(curatedId);
        if (rowOpt.isEmpty() || !"active".equals(rowOpt.get().status())) return;
        if (tryEmbedWithFallback(rowOpt.get())) {
            repository.markEmbedOk(curatedId);
            log.info("[CURATED] embedded curatedId={} reason={}", curatedId, reason);
        } else {
            repository.markEmbedFailed(curatedId);
        }
    }

    private void embed(long curatedId, String userId, String threadId, long turnId) {
        Optional<CuratedQa> rowOpt = repository.findById(curatedId);
        if (rowOpt.isEmpty() || !"active".equals(rowOpt.get().status())) return;
        CuratedQa row = rowOpt.get();

        if (!tryEmbedWithFallback(row)) {
            repository.markEmbedFailed(curatedId);
            return;
        }
        repository.markEmbedOk(curatedId);

        // Compensating re-check: an unlike that raced during the (network) embed call itself gets
        // undone immediately instead of left as a dangling active-in-vectorstore/inactive-in-DB
        // entry — narrows the race window to just this call's own duration.
        if (!isStillLiked(userId, threadId, turnId)) {
            log.debug("[CURATED] embed committed then reverted (unliked during embed) turnId={}", turnId);
            deleteVector(curatedId);
            return;
        }
        log.info("[CURATED] embedded curatedId={} turnId={}", curatedId, turnId);
    }

    /**
     * §10.10 embedding-fallback — attempts {@link #defaultSearchText} first (question + answer,
     * minus the "요약"/"참고" structural sections — see that method); on failure (typically: the
     * combined text still exceeds the embedding server's input limit) retries with a narrower
     * slice: just the answer's core RAG sections (상세 설명/예시·코드/설정·주의사항, emphasis markers
     * additionally stripped — {@link CuratedTextUtils#extractCoreSections}). Returns {@code false}
     * only when both attempts fail, or when the answer has no core-section structure to fall back
     * to at all (e.g. a Direct-mode/meta answer) — callers mark the row {@code embed_status='failed'}
     * in that case.
     */
    private boolean tryEmbedWithFallback(CuratedQa row) {
        try {
            vectorStore.add(DocRegistry.SHARED, CURATED_VERSION, List.of(buildDocument(row, defaultSearchText(row))));
            return true;
        } catch (Exception e) {
            log.warn("[CURATED] embed failed, retrying with core sections only curatedId={}: {}",
                    row.id(), e.getMessage());
        }

        String core = CuratedTextUtils.extractCoreSections(row.answer());
        if (core.isBlank()) {
            log.warn("[CURATED] no core-section fallback available curatedId={} (answer isn't in the RAG format)",
                    row.id());
            return false;
        }
        String fallbackSearchText = row.question() + "\n\n" + MarkdownNoiseNormalizer.normalize(core);
        try {
            vectorStore.add(DocRegistry.SHARED, CURATED_VERSION, List.of(buildDocument(row, fallbackSearchText)));
            log.info("[CURATED] embedded with core-sections fallback curatedId={}", row.id());
            return true;
        } catch (Exception e) {
            log.warn("[CURATED] core-sections fallback embed also failed curatedId={}: {}", row.id(), e.getMessage());
            return false;
        }
    }

    /**
     * Question + answer minus the "## 참고" (citation noise) and "## 요약" (redundant once "##
     * 상세 설명" carries the same content) structural sections — both are a net negative for
     * question-driven semantic matching, so neither should reach the embedding call. Falls back
     * to the narrower {@link CuratedTextUtils#extractCoreSections} slice only if this text is
     * still too large for the embedding server (see {@link #tryEmbedWithFallback}); a Direct-mode/
     * meta answer has neither heading to strip, so this is a no-op for it and the full text is
     * used as-is.
     */
    private static String defaultSearchText(CuratedQa row) {
        String core = CuratedTextUtils.stripStructuralSections(row.answer());
        return row.question() + "\n\n" + MarkdownNoiseNormalizer.normalize(core);
    }

    /**
     * Builds the curated Document: {@code getText()} = full answer (participates/§10.1 stored
     * text, "## 참고" section intact — useful for a human reading the curated entry). The search
     * vector is a separately precomputed override under {@link MetaKey#SEARCH_TEXT} passed in by
     * the caller ({@link #defaultSearchText} normally, a shrunk fallback on retry — see
     * {@link #tryEmbedWithFallback}) — which {@code SearchTextBuilder.build()} already prefers
     * over recomputing from {@code getText()} (§10.8.5), so no changes are needed to either
     * {@code VectorStoreProvider}.
     */
    private Document buildDocument(CuratedQa row, String searchText) {
        Map<String, Object> meta = new HashMap<>();
        meta.put(MetaKey.DOC_ID, "curated:" + row.id());
        meta.put(MetaKey.FILENAME, "curated_qa");
        meta.put(MetaKey.VERSION, CURATED_VERSION);
        meta.put(MetaKey.DOC_TYPE, "curated_qa");
        meta.put(MetaKey.SOURCE_TYPE, "curated_qa");
        meta.put(MetaKey.CHUNK_INDEX, 0);
        meta.put(MetaKey.PAGE_OR_SLIDE, 1);
        // 태그가 있으면 문서 청크와 동일한 키로 실어 RetrievalService.filterByTags가 그대로 판정한다.
        // 비어 있으면 키 자체를 넣지 않는다 — 그래야 "스코프를 알 수 없는 큐레이션 항목"으로 취급되어
        // 어떤 태그 선택에서도 탈락하지 않는다(같은 메서드의 큐레이션 면제 분기).
        String tagsCsv = row.tags();
        if (tagsCsv != null && !tagsCsv.isBlank()) {
            meta.put(MetaKey.TAGS, tagsCsv);
        }
        meta.put(MetaKey.SEARCH_TEXT, searchText); // transient override — stripped before persistence

        return new Document(springDocId(row.id()), row.answer(), meta);
    }

    private void deleteVector(long curatedId) {
        try {
            vectorStore.deleteByDocIds(DocRegistry.SHARED, CURATED_VERSION, List.of(springDocId(curatedId)));
        } catch (Exception e) {
            log.warn("[CURATED] vector delete failed curatedId={}: {}", curatedId, e.getMessage());
        }
    }

    private boolean isStillLiked(String userId, String threadId, long turnId) {
        return memoryService.getFeedback(userId, threadId, turnId)
                .map(f -> "LIKE".equals(f.feedback()))
                .orElse(false);
    }

    private static String springDocId(long curatedId) {
        return "curated-" + curatedId;
    }
}
