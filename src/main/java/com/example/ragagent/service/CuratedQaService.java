package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.ingestion.ChunkSplitter;
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

    /**
     * Chunk-size multipliers tried, in order, when embedding a curated entry.
     *
     * <p>Curated text is answer-shaped, not document-shaped: a liked answer or a knowledge
     * proposal reads as one argument, so cutting it at the document {@code chunk-size} splits
     * reasoning that belongs together. Starting at <b>2× chunk-size</b> keeps such an entry whole
     * far more often; the smaller sizes exist only because the embedding server may reject the
     * larger input (batch/token limit), and shrinking is the one thing that reliably fixes that.
     * Landing on 1× means the entry is chunked exactly like a document.
     */
    static final double[] EMBED_CHUNK_SIZE_MULTIPLIERS = {2.0, 1.5, 1.0};

    private final CuratedQaRepository repository;
    private final MemoryService memoryService;
    private final ThreadMetaService threadMetaService;
    private final VectorStoreFacade vectorStore;
    private final ChunkSplitter chunkSplitter;
    private final AppProperties props;
    private final long embedDebounceMillis;

    @Autowired
    public CuratedQaService(CuratedQaRepository repository, MemoryService memoryService,
                            ThreadMetaService threadMetaService, VectorStoreFacade vectorStore,
                            ChunkSplitter chunkSplitter, AppProperties props) {
        this(repository, memoryService, threadMetaService, vectorStore, chunkSplitter, props,
                DEFAULT_EMBED_DEBOUNCE_MILLIS);
    }

    /** Package-private — lets tests shrink the debounce instead of waiting out the real 3s. */
    CuratedQaService(CuratedQaRepository repository, MemoryService memoryService,
                     ThreadMetaService threadMetaService, VectorStoreFacade vectorStore,
                     ChunkSplitter chunkSplitter, AppProperties props,
                     long embedDebounceMillis) {
        this.repository = repository;
        this.memoryService = memoryService;
        this.threadMetaService = threadMetaService;
        this.vectorStore = vectorStore;
        this.chunkSplitter = chunkSplitter;
        this.props = props;
        this.embedDebounceMillis = embedDebounceMillis;
    }

    /**
     * Splits arbitrary curated text into embeddable pieces using the very same {@link ChunkSplitter}
     * the document pipeline uses — one {@code .md} "document" in, N chunks out, so
     * {@code app.chunk-split-granular}, {@code embedding.max-chunk-chars} and the table/code-block
     * boundary protection all apply. Shared by both curated paths: the 게시판 splits a submission
     * body before storing it, and {@link #embedActiveRow} splits a long liked answer at embed time.
     * Returns a single-element list for anything already within {@code chunk-size}.
     */
    public List<String> splitForEmbedding(String text) {
        return splitForEmbedding(text, EMBED_CHUNK_SIZE_MULTIPLIERS[0]);
    }

    /**
     * @param chunkSizeMultiplier scales {@code app.chunk-size} for this attempt — see
     *                            {@link #EMBED_CHUNK_SIZE_MULTIPLIERS}. {@code minChunkSize} is
     *                            scaled with it so the two keep their configured ratio; leaving it
     *                            at the unscaled value would let the tiny-chunk merge undo the split.
     */
    public List<String> splitForEmbedding(String text, double chunkSizeMultiplier) {
        if (text == null || text.isBlank()) return List.of();
        int chunkSize = Math.max(1, (int) Math.round(props.chunkSizeSafe() * chunkSizeMultiplier));
        int minChunkSize = Math.max(1, (int) Math.round(props.minChunkSizeSafe() * chunkSizeMultiplier));
        Document doc = new Document(text.strip(), new HashMap<>(Map.of(MetaKey.CHAPTER_NO, "0")));
        List<String> pieces = chunkSplitter.splitDocuments(
                        List.of(doc), "curated.md",
                        chunkSize, props.chunkOverlapSafe(), minChunkSize,
                        props.embeddingSafe().maxChunkChars(), props.chunkSplitGranularSafe())
                .stream()
                .map(Document::getText)
                .filter(t -> t != null && !t.isBlank())
                .toList();
        return pieces.isEmpty() ? List.of(text.strip()) : pieces;
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
     * <p>PLAN §6.24 Step 0-a — the old "skip embedding for L-mode answers" branch is gone with L
     * itself. Its premise ("an L answer mirrors the indexed source almost verbatim, so re-embedding
     * duplicates an existing vector") did not hold: L answers measured the same length as M ones,
     * i.e. they were ordinary answers, and the branch was quietly discarding legitimate curated
     * knowledge. A mode-driven skip returns in Step 3-a via {@code ResponseMode.allowsSubmission()},
     * but for a real reason — keeping model-invented C-mode content out of the search corpus.
     */
    public void onLike(String userId, String threadId, long turnId) {
        Optional<MemoryRepository.Turn> turnOpt = memoryService.getTurn(userId, threadId, turnId);
        if (turnOpt.isEmpty()) {
            log.warn("[CURATED] onLike: turn not found userId={} threadId={} turnId={}", userId, threadId, turnId);
            return;
        }
        MemoryRepository.Turn turn = turnOpt.get();

        // 큐레이션 대상이 아닌 모드는 여기서 끝 — curated_qa 행조차 만들지 않는다. LIKE 피드백의
        // 유일한 소비자가 큐레이션이므로 그 모드에서는 좋아요가 무동작이 된다(싫어요는 그대로
        // 다음 컨텍스트 제외로 동작). 사유는 ResponseMode 의 해당 모드 주석 참조.
        ResponseMode mode = ResponseMode.parse(turn.responseMode());
        if (!mode.allowsSubmission()) {
            log.debug("[CURATED] onLike: {} 모드는 큐레이션 대상이 아니라 무시한다 turnId={}", mode, turnId);
            return;
        }

        String version = threadMetaService.findById(userId, threadId)
                .map(t -> t.version())
                .orElse(null);

        // 질문 당시의 검색 스코프(태그)를 그대로 승계한다 — 그 태그로 좁혀 얻은 답변이므로 이후
        // 같은 스코프에서 다시 검색될 때 살아남아야 한다. 태그 없이(전체 검색) 물은 질문이면 빈 값이
        // 되고, 그 경우 buildDocument()가 태그 메타데이터를 아예 붙이지 않아 어떤 태그 스코프에서도
        // 걸러지지 않는다(RetrievalService.filterByTags의 큐레이션 면제).
        long curatedId = repository.upsertActive(turnId, userId, threadId,
                turn.question(), turn.answer(), version, turn.selectedTags(), null);

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
        int chunks = existing.get().chunkCount();
        Thread.ofVirtual().name("curated-deindex-" + curatedId).start(() ->
                deleteVectors(curatedId, chunks));
    }

    /**
     * §6.25 — how many curated entries {@link #onThreadDeleted} would retract for this
     * conversation. Read by the admin delete confirmation so the operator sees the knowledge cost
     * of the delete before approving it, not after.
     */
    public int countActiveByThread(String userId, String threadId) {
        return repository.findActiveByThread(userId, threadId).size();
    }

    /**
     * §6.25 — retracts every 👍-promoted entry of a conversation that is being deleted whole; the
     * thread-level counterpart of {@link #onUnlike}.
     *
     * <p><b>Why this has to exist at all.</b> A {@code curated_qa} row is linked to its turn by a
     * <em>copy</em> of the thread/turn id, not a foreign key, so deleting the conversation removes
     * the turns while the row and its vectors survive and keep feeding search from a conversation
     * that no longer exists. That is exactly the orphan {@link #onUnlike} is called for on the
     * single-turn delete path — one level up. Both delete paths (the user's own
     * {@code DELETE /ui/threads/{threadId}} and the admin one) must call this, or the outcome
     * depends on which button was pressed.
     *
     * <p>Rows are deactivated <b>by their own id</b>, never by turn: {@code deactivate(turnId)}
     * silently no-ops on any row whose {@code source_turn_id} is NULL (see
     * {@link CuratedQaRepository#deactivateById}). Manual (청크 추가) rows are excluded by the
     * query itself — a submission is a 전부/전무 unit and deleting a chat thread must not take
     * part of one down.
     *
     * <p>Vector removal is <b>one batched call on one background thread</b>, unlike
     * {@link #forceRemoveBySubmission}'s per-row fan-out: a long conversation can hold many liked
     * turns, and that pattern would spend a thread and a network round-trip on each. As everywhere
     * else here, the DB write is synchronous (cheap, local) and only the remote delete is deferred
     * (§6.12).
     *
     * @return how many curated rows were retracted — surfaced in the deletion's audit entry and in
     *         the admin confirm dialog, so the cost of deleting a conversation is visible rather
     *         than silent.
     */
    public int onThreadDeleted(String userId, String threadId) {
        List<CuratedQa> rows = repository.findActiveByThread(userId, threadId);
        if (rows.isEmpty()) return 0;

        List<String> vectorIds = new java.util.ArrayList<>();
        for (CuratedQa row : rows) {
            repository.deactivateById(row.id());
            vectorIds.addAll(vectorIdsFor(row.id(), row.chunkCount()));
        }
        Thread.ofVirtual().name("curated-deindex-thread-" + threadId).start(() -> {
            try {
                vectorStore.deleteByDocIds(DocRegistry.SHARED, CURATED_VERSION, vectorIds);
            } catch (Exception e) {
                log.warn("[CURATED] 대화 삭제 후 벡터 삭제 실패 threadId={}: {}", threadId, e.getMessage());
            }
        });
        log.info("[CURATED] 대화 {} 삭제 — 큐레이션 {}건 회수(벡터 {}개)",
                threadId, rows.size(), vectorIds.size());
        return rows.size();
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
     * §10.11 — admin approval of a <b>좋아요 출신</b> proposal. Same review, different storage shape
     * from {@link #createFromSubmission}: <b>one row whose vectors are split at embed time</b>,
     * not N pre-split rows.
     *
     * <p>That difference is not cosmetic (함정 ①). Three things about a promoted chat answer are
     * keyed by the turn — {@code UNIQUE(source_turn_id)}, the conversation/turn delete retraction
     * ({@link #onThreadDeleted}), and the row's identity across a re-approval — and pushing this
     * through {@code insertManual} would write {@code source_turn_id = NULL}, killing all three at
     * once and silently: nothing here fails, the retraction simply never finds the row again.
     *
     * <p>The text stored is the <b>reviewed</b> title/body, not the raw turn: the whole point of
     * §10.11 is that a person edited it and an admin approved that edit. {@code source_doc_version}
     * still comes from the thread so the entry knows which document version it was answered against.
     *
     * @return the curated row's id
     */
    public long createFromLikedTurn(long submissionId, long turnId, String userId, String threadId,
                                    String title, String body, String tags) {
        String version = threadMetaService.findById(userId, threadId)
                .map(t -> t.version())
                .orElse(null);
        long curatedId = repository.upsertActive(turnId, userId, threadId, title, body, version,
                tags, submissionId);
        Thread.ofVirtual().name("curated-embed-" + curatedId).start(() ->
                embedActiveRow(curatedId, "submission-like"));
        return curatedId;
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
            int chunks = row.chunkCount();
            Thread.ofVirtual().name("curated-deindex-" + curatedId).start(() -> deleteVectors(curatedId, chunks));
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
        int chunks = rowOpt.get().chunkCount();
        Thread.ofVirtual().name("curated-deindex-" + curatedId).start(() -> deleteVectors(curatedId, chunks));
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
        int chunks = tryEmbedWithFallback(rowOpt.get());
        if (chunks > 0) {
            repository.markEmbedOk(curatedId);
            log.info("[CURATED] embedded curatedId={} chunks={} reason={}", curatedId, chunks, reason);
        } else {
            repository.markEmbedFailed(curatedId);
        }
    }

    private void embed(long curatedId, String userId, String threadId, long turnId) {
        Optional<CuratedQa> rowOpt = repository.findById(curatedId);
        if (rowOpt.isEmpty() || !"active".equals(rowOpt.get().status())) return;
        CuratedQa row = rowOpt.get();

        int chunks = tryEmbedWithFallback(row);
        if (chunks == 0) {
            repository.markEmbedFailed(curatedId);
            return;
        }
        repository.markEmbedOk(curatedId);

        // Compensating re-check: an unlike that raced during the (network) embed call itself gets
        // undone immediately instead of left as a dangling active-in-vectorstore/inactive-in-DB
        // entry — narrows the race window to just this call's own duration.
        if (!isStillLiked(userId, threadId, turnId)) {
            log.debug("[CURATED] embed committed then reverted (unliked during embed) turnId={}", turnId);
            deleteVectors(curatedId, chunks);
            return;
        }
        log.info("[CURATED] embedded curatedId={} chunks={} turnId={}", curatedId, chunks, turnId);
    }

    /**
     * Embeds the row as <b>one or more</b> vectors and returns how many were written (0 = failure).
     *
     * <p>A liked answer can be far longer than the embedding server's input limit, so the text is
     * split with {@link #splitForEmbedding} first — the same splitter documents go through, which
     * is what makes "too long to embed" structurally impossible rather than a failure to recover
     * from. Each piece becomes its own vector, all sharing the row's question so a question-shaped
     * query still matches the 2nd piece onward (the same reason document splitting reinjects
     * headings, and the 게시판 repeats the title on every chunk).
     *
     * <p>Chunk size walks {@link #EMBED_CHUNK_SIZE_MULTIPLIERS} — 2× the document chunk size first,
     * then 1.5×, then 1× — retrying the embedding call at each step. Curated text is one continuous
     * argument, so the biggest chunks that the server will accept are the ones that keep it
     * readable as evidence; shrinking is purely a response to rejection, never the default.
     *
     * <p>The core-sections fallback is kept for the case shrinking can't help with: the embedding
     * call failing for a reason other than size. It retries the whole row as a single vector built
     * from just the answer's core RAG sections ({@link CuratedTextUtils#extractCoreSections}).
     *
     * <p>Stale vectors from a previous, longer version of the same row (an edit that shortened the
     * answer) are removed <em>after</em> the new ones are written, never before — a failed re-embed
     * then leaves the old vectors searchable instead of silently dropping the entry from the index.
     */
    private int tryEmbedWithFallback(CuratedQa row) {
        // 크기 사다리: 2× → 1.5× → 1×. 실패의 압도적 다수는 "입력이 너무 큼"이고, 그건 더 잘게
        // 자르는 것으로만 풀린다 — 그래서 재시도할 때마다 청크를 줄인다.
        List<Document> docs = List.of();
        for (double multiplier : EMBED_CHUNK_SIZE_MULTIPLIERS) {
            docs = buildChunkedDocuments(row, multiplier);
            // 내용이 없어서 비었다면 더 잘게 잘라도 마찬가지다 — 사다리를 계속 내려갈 이유가 없다.
            if (docs.isEmpty()) break;
            try {
                vectorStore.add(DocRegistry.SHARED, CURATED_VERSION, docs);
                pruneStaleVectors(row, docs.size());
                if (multiplier != EMBED_CHUNK_SIZE_MULTIPLIERS[0]) {
                    log.info("[CURATED] embedded at {}× chunk-size ({} chunks) curatedId={}",
                            multiplier, docs.size(), row.id());
                }
                return docs.size();
            } catch (Exception e) {
                log.warn("[CURATED] embed failed at {}× chunk-size ({} chunks) curatedId={}: {}",
                        multiplier, docs.size(), row.id(), e.getMessage());
            }
        }

        // 청크가 하나도 남지 않았다면(예: 답변이 사실상 인용 목록뿐) 분할 이전과 똑같이 답변 전체를
        // 한 벡터로 넣어 본다 — 여기서 실패로 처리하면 임베딩할 값이 없었을 뿐인 항목에 실패 배지가 붙는다.
        if (docs.isEmpty()) {
            try {
                vectorStore.add(DocRegistry.SHARED, CURATED_VERSION,
                        List.of(buildDocument(row, 0, row.answer(), defaultSearchText(row))));
                pruneStaleVectors(row, 1);
                return 1;
            } catch (Exception e) {
                log.warn("[CURATED] whole-row embed failed curatedId={}: {}", row.id(), e.getMessage());
            }
        }

        String core = CuratedTextUtils.extractCoreSections(row.answer());
        if (core.isBlank()) {
            log.warn("[CURATED] no core-section fallback available curatedId={} (answer isn't in the RAG format)",
                    row.id());
            return 0;
        }
        String fallbackSearchText = row.question() + "\n\n" + MarkdownNoiseNormalizer.normalize(core);
        try {
            vectorStore.add(DocRegistry.SHARED, CURATED_VERSION,
                    List.of(buildDocument(row, 0, row.answer(), fallbackSearchText)));
            pruneStaleVectors(row, 1);
            log.info("[CURATED] embedded with core-sections fallback curatedId={}", row.id());
            return 1;
        } catch (Exception e) {
            log.warn("[CURATED] core-sections fallback embed also failed curatedId={}: {}", row.id(), e.getMessage());
            return 0;
        }
    }

    /**
     * One {@link Document} per chunk of the answer. Each chunk keeps its own slice as the stored
     * text and gets its own search-text override — {@code question + normalize(strip(chunk))} —
     * so the "요약"/"참고" stripping that used to apply to the whole answer now applies wherever
     * those sections happen to land ({@link CuratedTextUtils#stripStructuralSections} is a no-op on
     * a chunk that contains neither heading).
     *
     * <p><b>Strips before splitting, not after.</b> The "요약"/"참고" sections have never been part
     * of the search vector, and stripping each chunk afterwards fails as soon as one of those
     * sections spills across a chunk boundary — the trailing piece no longer contains the
     * {@code ## 참고} heading, so {@link CuratedTextUtils#stripStructuralSections} finds nothing to
     * remove and a chunk of pure citation list gets embedded. Removing them first makes such a chunk
     * impossible to construct.
     *
     * <p>A consequence worth naming: each vector's <em>stored</em> text is its slice of the stripped
     * answer, not of the full one, so the citation list no longer travels into the answer prompt as
     * grounding evidence. The complete answer is still kept verbatim in {@code curated_qa.answer},
     * which is what the chat bubble and the admin editor show.
     *
     * <p>Returns empty when the answer is entirely structural, which sends the caller to the
     * whole-row fallback.
     */
    private List<Document> buildChunkedDocuments(CuratedQa row, double chunkSizeMultiplier) {
        String searchableAnswer = CuratedTextUtils.stripStructuralSections(row.answer());
        if (!hasSubstantiveContent(searchableAnswer)) return List.of();

        List<Document> docs = new java.util.ArrayList<>();
        for (String piece : splitForEmbedding(searchableAnswer, chunkSizeMultiplier)) {
            // 조각이 재주입된 헤딩 한 줄뿐인 경우를 거른다 — 질문만 담긴 벡터가 되어 모든 질의에서
            // 진짜 내용 청크와 경쟁하게 된다.
            if (!hasSubstantiveContent(piece)) continue;
            String searchable = MarkdownNoiseNormalizer.normalize(piece);
            if (searchable.isBlank()) continue;
            docs.add(buildDocument(row, docs.size(), piece, row.question() + "\n\n" + searchable));
        }
        return docs;
    }

    /** True when {@code text} has at least one non-blank line that isn't an ATX heading — i.e. the
     *  chunk carries something a query could actually match, not just a reinjected section title. */
    private static boolean hasSubstantiveContent(String text) {
        if (text == null || text.isBlank()) return false;
        for (String line : text.split("\n", -1)) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            return true;
        }
        return false;
    }

    /** Removes vectors left over from a previous embed that produced more chunks than this one. */
    private void pruneStaleVectors(CuratedQa row, int newCount) {
        int oldCount = Math.max(1, row.chunkCount());
        if (oldCount > newCount) {
            List<String> stale = new java.util.ArrayList<>(oldCount - newCount);
            for (int i = newCount; i < oldCount; i++) stale.add(springDocId(row.id(), i));
            try {
                vectorStore.deleteByDocIds(DocRegistry.SHARED, CURATED_VERSION, stale);
            } catch (Exception e) {
                log.warn("[CURATED] stale vector cleanup failed curatedId={}: {}", row.id(), e.getMessage());
            }
        }
        repository.updateChunkCount(row.id(), newCount);
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
    private Document buildDocument(CuratedQa row, int chunkIndex, String storedText, String searchText) {
        Map<String, Object> meta = new HashMap<>();
        meta.put(MetaKey.DOC_ID, "curated:" + row.id());
        meta.put(MetaKey.FILENAME, "curated_qa");
        meta.put(MetaKey.VERSION, CURATED_VERSION);
        meta.put(MetaKey.DOC_TYPE, "curated_qa");
        // 검색 시 좋아요 큐레이션과 지식 제안을 서로 다른 가중치의 RRF 축으로 나누기 위한 표식.
        // DOC_TYPE 을 갈라 쓰지 않는 이유: 출처 라벨("💬 큐레이션 Q&A")과 태그 면제 판정이 모두
        // DOC_TYPE="curated_qa" 를 보고 있어, 그쪽을 바꾸면 둘 다 조용히 깨진다.
        meta.put(MetaKey.CURATED_ORIGIN,
                row.isManual() ? CuratedQaRepository.ORIGIN_MANUAL : CuratedQaRepository.ORIGIN_LIKE);
        meta.put(MetaKey.SOURCE_TYPE, "curated_qa");
        meta.put(MetaKey.CHUNK_INDEX, chunkIndex);
        meta.put(MetaKey.PAGE_OR_SLIDE, 1);
        // 태그가 있으면 문서 청크와 동일한 키로 실어 RetrievalService.filterByTags가 그대로 판정한다.
        // 비어 있으면 키 자체를 넣지 않는다 — 그래야 "스코프를 알 수 없는 큐레이션 항목"으로 취급되어
        // 어떤 태그 선택에서도 탈락하지 않는다(같은 메서드의 큐레이션 면제 분기).
        String tagsCsv = row.tags();
        if (tagsCsv != null && !tagsCsv.isBlank()) {
            meta.put(MetaKey.TAGS, tagsCsv);
        }
        // 지식 제안 본문 이미지 — 이 청크에 실제로 남아 있는 마커만 싣는다. 분할 뒤에 계산하므로
        // 이미지가 3장인 제안이 2청크로 나뉘면 각 청크는 자기 몫만 갖는다(문서 인덱싱의
        // DocumentLoaderService.loadFromMarkdown 과 같은 규칙). 이 키가 있어야 답변 말풍선의
        // 썸네일(RetrievalService → imageRefs)이 큐레이션 청크에도 붙는다.
        List<String> imagePaths = CuratedImageStore.markerPaths(storedText);
        if (!imagePaths.isEmpty()) {
            meta.put(MetaKey.IMAGE_PATHS, String.join(",", new java.util.LinkedHashSet<>(imagePaths)));
        }
        meta.put(MetaKey.SEARCH_TEXT, searchText); // transient override — stripped before persistence

        return new Document(springDocId(row.id(), chunkIndex), storedText, meta);
    }

    /** Removes every vector this row owns — {@code chunkCount} ids, not just the first. */
    private void deleteVectors(long curatedId, int chunkCount) {
        try {
            vectorStore.deleteByDocIds(DocRegistry.SHARED, CURATED_VERSION,
                    vectorIdsFor(curatedId, chunkCount));
        } catch (Exception e) {
            log.warn("[CURATED] vector delete failed curatedId={}: {}", curatedId, e.getMessage());
        }
    }

    /**
     * Vector ids for every chunk of one curated row. {@code chunkCount} is floored at 1 — a row
     * written before splitting existed records 0 but still owns the index-0 vector.
     */
    private static List<String> vectorIdsFor(long curatedId, int chunkCount) {
        List<String> ids = new java.util.ArrayList<>(Math.max(1, chunkCount));
        for (int i = 0; i < Math.max(1, chunkCount); i++) ids.add(springDocId(curatedId, i));
        return ids;
    }

    private boolean isStillLiked(String userId, String threadId, long turnId) {
        return memoryService.getFeedback(userId, threadId, turnId)
                .map(f -> "LIKE".equals(f.feedback()))
                .orElse(false);
    }

    /**
     * Vector id for one chunk. Index 0 keeps the historical {@code curated-<id>} form so an entry
     * embedded before splitting existed is overwritten in place rather than duplicated; later
     * chunks get a {@code -<i>} suffix.
     */
    private static String springDocId(long curatedId, int chunkIndex) {
        return chunkIndex == 0 ? "curated-" + curatedId : "curated-" + curatedId + "-" + chunkIndex;
    }
}
