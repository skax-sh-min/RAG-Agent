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
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * §10.10 — the shared knowledge axis: a separately embedded corpus (reserved vector-store version
 * namespace {@value #CURATED_VERSION} — auto-isolated per backend: a distinct Chroma collection /
 * a sqlite-vec partition key, version-agnostic so it survives document re-indexing) fused into
 * retrieval as its own weighted RRF axis. See documents/PLAN.md §10.10 and §10.11.
 *
 * <p><b>Everything here is created by an admin approving a 지식 제안</b> (§10.11). Nothing writes
 * to this corpus on a user action alone. Until then a 👍 wrote a row directly and embedded it three
 * seconds later, which meant the app had two doors into the search corpus guarded oppositely — one
 * requiring review, the other requiring nothing — and a Direct answer, grounded in no document at
 * all, could become everyone's search knowledge on one click. The entry points that remain are
 * {@link #createFromSubmission} and {@link #createFromLikedTurn}, both reached only from
 * {@code CuratedSubmissionService.approve}.
 *
 * <p>The DB row is written synchronously (cheap local write, inside the approving request) and the
 * embedding call runs on a background thread — never block the interactive path on remote I/O
 * (§6.12).
 */
@Service
public class CuratedQaService {

    private static final Logger log = LoggerFactory.getLogger(CuratedQaService.class);

    /** Reserved vectorstore version namespace for curated Q&A — never a real document version. */
    public static final String CURATED_VERSION = "curated";

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
    private final ThreadMetaService threadMetaService;
    private final VectorStoreFacade vectorStore;
    private final ChunkSplitter chunkSplitter;
    private final AppProperties props;

    public CuratedQaService(CuratedQaRepository repository, ThreadMetaService threadMetaService,
                            VectorStoreFacade vectorStore, ChunkSplitter chunkSplitter,
                            AppProperties props) {
        this.repository = repository;
        this.threadMetaService = threadMetaService;
        this.vectorStore = vectorStore;
        this.chunkSplitter = chunkSplitter;
        this.props = props;
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
     * §10.11 — retracts the curated entry a deleted <b>turn</b> produced; the single-turn
     * counterpart of {@link #onThreadDeleted}. Deactivates synchronously (fast, local) and removes
     * the vectors on a background thread, since a Chroma delete is a network round-trip (§6.12 —
     * never block the interactive path on remote I/O). A no-op when the turn was never promoted.
     *
     * <p>This used to be {@code onUnlike}, called whenever feedback moved off {@code LIKE}. It no
     * longer is: a curated entry is now created by an <b>admin approving a proposal</b>, not by the
     * like, so taking it back is the author's or the admin's action on the 지식 제안 board — not a
     * side effect of changing one's mind about the chat message. What remains is the orphan
     * problem the method existed for: a {@code curated_qa} row is linked to its turn by a
     * <em>copy</em> of the id, not a foreign key, so deleting the turn would otherwise leave the
     * row and its vectors feeding search from an exchange that no longer exists.
     */
    public void onTurnDeleted(String userId, String threadId, long turnId) {
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
     * thread-level counterpart of {@link #onTurnDeleted}.
     *
     * <p><b>Why this has to exist at all.</b> A {@code curated_qa} row is linked to its turn by a
     * <em>copy</em> of the thread/turn id, not a foreign key, so deleting the conversation removes
     * the turns while the row and its vectors survive and keep feeding search from a conversation
     * that no longer exists. That is exactly the orphan {@link #onTurnDeleted} is called for on the
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
     * §10.10 step ④ — the {@code /admin} curated tab's edit path (looked up by curated id).
     * Re-embeds on a background thread — no debounce and no like-state re-check: an edit is an
     * explicit save action, not a promotion that can race with an accidental unlike.
     *
     * <p>§10.11 removed the chat-side twin of this ({@code updateAnswerForTurn}, reached by a
     * pencil next to 👍). Editing a curated entry now happens where it was proposed — the 지식 제안
     * page — so the chat window never shows or changes curation state.
     */
    public boolean updateAnswer(long curatedId, String newAnswer) {
        return updateEntry(curatedId, null, newAnswer);
    }

    /**
     * 질문·답변을 함께 고치는 경로 — {@code /admin} 편집 화면의 저장 하나가 둘 다 보낼 수 있다.
     *
     * <p><b>재임베딩은 한 번만 돈다.</b> 질문과 답변이 같은 검색 텍스트를 이룬다
     * ({@code defaultSearchText()} = 질문 + 본문, 질문은 모든 청크에 반복 부여) — 따로 저장하면
     * 같은 항목을 두 번 임베딩하게 되고, 그 사이에 벡터가 질문만 바뀐 중간 상태로 남는다.
     *
     * <p>{@code null} 인 쪽은 건드리지 않는다. 둘 다 비어 있으면 아무것도 하지 않고 {@code false} —
     * 빈 질문은 그 항목을 검색에서 사실상 지우는 것과 같고, 빈 답변은 근거가 사라지는 것이다.
     */
    public boolean updateEntry(long curatedId, String newQuestion, String newAnswer) {
        boolean hasQuestion = newQuestion != null && !newQuestion.isBlank();
        boolean hasAnswer   = newAnswer   != null && !newAnswer.isBlank();
        if (!hasQuestion && !hasAnswer) return false;
        if (repository.findById(curatedId).isEmpty()) return false;
        if (hasQuestion) repository.updateQuestion(curatedId, newQuestion.strip());
        if (hasAnswer)   repository.updateAnswer(curatedId, newAnswer);
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
     * asker's own feedback state (separate authorization from {@link #onTurnDeleted}'s ownership check
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

    /** §10.10 step ④ — direct id lookup for the admin edit panel. */
    public Optional<CuratedQa> findById(long id) {
        return repository.findById(id);
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

    /**
     * Vector id for one chunk. Index 0 keeps the historical {@code curated-<id>} form so an entry
     * embedded before splitting existed is overwritten in place rather than duplicated; later
     * chunks get a {@code -<i>} suffix.
     */
    private static String springDocId(long curatedId, int chunkIndex) {
        return chunkIndex == 0 ? "curated-" + curatedId : "curated-" + curatedId + "-" + chunkIndex;
    }
}
