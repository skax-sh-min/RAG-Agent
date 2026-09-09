package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.ingestion.ChunkSplitter;
import com.example.ragagent.ingestion.CuratedTextUtils;
import com.example.ragagent.ingestion.DocRegistry;
import com.example.ragagent.ingestion.KeywordSearchRepository;
import com.example.ragagent.ingestion.MarkdownNoiseNormalizer;
import com.example.ragagent.ingestion.SearchTextBuilder;
import com.example.ragagent.ingestion.VectorStoreFacade;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.model.ResponseMode;
import com.example.ragagent.repository.CuratedQaRepository;
import com.example.ragagent.repository.CuratedQaRepository.CuratedQa;
import com.example.ragagent.repository.MemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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
    /**
     * 큐레이션 청크의 BM25(키워드) 축. 예전에는 이 축에 아예 없었다 — {@code indexChunks()} 를
     * 부르는 곳이 {@code DocumentIndexer} 와 {@code AdminService.reindexChunk()} 뿐이라
     * 큐레이션 청크는 {@code chunk_fts} 행 자체가 없었고, 그래서 {@code excerpt_keywords} 를
     * 채워 봐야 읽는 코드가 없었다. 이제 벡터를 쓸 때마다 같은 문서로 FTS 도 함께 쓴다.
     *
     * <p>{@code Optional} 이 아니라 필수 의존이다 — {@code KeywordSearchRepository} 는 FTS5 를
     * 못 쓰는 환경에서도 빈으로 존재하고 {@code indexChunks()} 가 스스로 no-op 이 된다.
     */
    private final KeywordSearchRepository keywordRepo;

    public CuratedQaService(CuratedQaRepository repository, ThreadMetaService threadMetaService,
                            VectorStoreFacade vectorStore, ChunkSplitter chunkSplitter,
                            AppProperties props, KeywordSearchRepository keywordRepo) {
        this.repository = repository;
        this.threadMetaService = threadMetaService;
        this.vectorStore = vectorStore;
        this.chunkSplitter = chunkSplitter;
        this.props = props;
        this.keywordRepo = keywordRepo;
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
        // 한 스레드의 벡터를 한 번에 지우는 것은 그대로 두되, 지우는 문은 deindex() 하나다 —
        // 여기서 vectorStore 를 직접 부르던 동안 FTS 행이 남아, 대화를 지워도 그 항목이 BM25
        // 축에서 계속 근거로 붙었다.
        Thread.ofVirtual().name("curated-deindex-thread-" + threadId)
                .start(() -> deindex(vectorIds, "threadId=" + threadId));
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
        return updateEntry(curatedId, null, newAnswer, null, null);
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
        return updateEntry(curatedId, newQuestion, newAnswer, null, null);
    }

    /**
     * 위와 같되 <b>요약·키워드까지 한 번의 저장으로</b> 반영한다.
     *
     * <p>이 둘의 단일 출처는 {@code curated_qa} 컬럼이다 — 벡터 메타데이터의
     * {@code chunk_context}/{@code excerpt_keywords} 는 재임베딩 때마다 여기서 다시 쓰이는
     * <b>사본</b>이다. 그래서 {@code /admin} 청크 화면에서 그 두 키를 고쳐 봐야 다음 재임베딩에
     * 조용히 되돌아간다(그쪽이 큐레이션 청크에서 두 칸을 읽기 전용으로 막는 이유이자, 고칠
     * 자리를 여기 하나로 모은 이유다).
     *
     * <p>{@code null} 은 "안 보냄", 빈 문자열은 "비우기"다. 넷 중 무엇이 왔든 재임베딩은
     * <b>한 번만</b> 돈다 — 같은 항목을 두 번 임베딩하면 그 사이 벡터가 반만 갱신된 중간
     * 상태로 남는다.
     */
    public boolean updateEntry(long curatedId, String newQuestion, String newAnswer,
                               String newSummary, String newKeywords) {
        boolean hasQuestion   = newQuestion != null && !newQuestion.isBlank();
        boolean hasAnswer     = newAnswer   != null && !newAnswer.isBlank();
        boolean hasEnrichment = newSummary != null || newKeywords != null;
        if (!hasQuestion && !hasAnswer && !hasEnrichment) return false;
        if (repository.findById(curatedId).isEmpty()) return false;
        if (hasQuestion)   repository.updateQuestion(curatedId, newQuestion.strip());
        if (hasAnswer)     repository.updateAnswer(curatedId, newAnswer);
        if (hasEnrichment) repository.updateEnrichment(curatedId, newSummary, newKeywords);
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
                                           List<String> bodyChunks, String tags,
                                           String summary, String keywords) {
        List<Long> curatedIds = new java.util.ArrayList<>(bodyChunks.size());
        for (String chunk : bodyChunks) {
            // 제목은 모든 청크에 반복 부여한다 — defaultSearchText()가 question+answer를 임베딩하므로
            // 2번째 청크부터 제목이 없으면 질문형 질의와의 매칭이 급격히 나빠진다(문서 인덱싱의
            // reinjectHeadingForSplitPieces와 같은 이유).
            // 요약·키워드는 제안 하나가 통째로 갖는 값이라 모든 청크에 같은 값이 붙는다 —
            // 제목과 같은 이유다(청크마다 다시 만들면 승인 한 번에 LLM 을 N 번 부르게 된다).
            curatedIds.add(repository.insertManual(submissionId, authorUserId, title, chunk, tags,
                    summary, keywords));
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
                                    String title, String body, String tags,
                                    String summary, String keywords) {
        String version = threadMetaService.findById(userId, threadId)
                .map(t -> t.version())
                .orElse(null);
        long curatedId = repository.upsertActive(turnId, userId, threadId, title, body, version,
                tags, submissionId, summary, keywords);
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
    private boolean embedActiveRow(long curatedId, String reason) {
        Optional<CuratedQa> rowOpt = repository.findById(curatedId);
        if (rowOpt.isEmpty() || !"active".equals(rowOpt.get().status())) return false;
        CuratedQa row = rowOpt.get();
        List<Document> written = tryEmbedWithFallback(row);
        if (!written.isEmpty()) {
            // BM25 축은 벡터가 실제로 쓰인 뒤에만 따라간다 — 임베딩이 실패한 항목을 키워드로만
            // 검색되게 두면 출처는 붙는데 의미 매칭은 안 되는 절반짜리 항목이 생긴다.
            indexFts(row, written);
            repository.markEmbedOk(curatedId);
            log.info("[CURATED] embedded curatedId={} chunks={} reason={}", curatedId, written.size(), reason);
            return true;
        }
        repository.markEmbedFailed(curatedId);
        return false;
    }

    /**
     * 한 큐레이션 행을 규칙대로 다시 임베딩한다 — {@code /admin} 청크 화면의 재인덱싱이
     * 큐레이션 청크를 만났을 때 부르는 자리({@code AdminController}).
     *
     * <p>그쪽의 {@code AdminService.reindexChunk()} 를 그대로 태우면 안 된다: 그건 문서 청크의
     * 규칙({@code chunk_context} + 본문)으로 검색 텍스트를 다시 만드는데, 이 축의 검색 텍스트는
     * <b>질문 + 본문</b>이고 질문은 벡터 메타데이터에 실려 있지 않다. 즉 그 경로를 지나면 그
     * 청크만 조용히 질문을 잃는다.
     *
     * <p>단위가 '청크 하나'가 아니라 '행 하나'인 것은 의도된 것이다 — 이 축에서 질문·본문·요약·
     * 키워드는 행이 통째로 갖는 값이라, 한 청크만 다시 만들 수 있는 상태 자체가 없다.
     *
     * @return 실제로 벡터를 다시 쓴 경우에만 {@code true}. 행이 없거나 {@code active} 가 아니거나
     *         임베딩이 실패하면 {@code false} 다 — 예전에는 행만 존재하면 {@code true} 였고,
     *         그래서 비활성 행에 재인덱싱을 누르면 아무 일도 없이 성공 토스트가 떴다.
     *         문서 청크의 {@code AdminService.reindexChunk()} 와 같은 계약이다(호출자가 404 로 옮긴다).
     */
    public boolean reembedRow(long curatedId, String reason) {
        return embedActiveRow(curatedId, reason);
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
    private List<Document> tryEmbedWithFallback(CuratedQa row) {
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
                return docs;
            } catch (Exception e) {
                log.warn("[CURATED] embed failed at {}× chunk-size ({} chunks) curatedId={}: {}",
                        multiplier, docs.size(), row.id(), e.getMessage());
            }
        }

        // 청크가 하나도 남지 않았다면(예: 답변이 사실상 인용 목록뿐) 분할 이전과 똑같이 답변 전체를
        // 한 벡터로 넣어 본다 — 여기서 실패로 처리하면 임베딩할 값이 없었을 뿐인 항목에 실패 배지가 붙는다.
        if (docs.isEmpty()) {
            List<Document> whole = List.of(buildDocument(row, 0, row.answer(), defaultSearchText(row)));
            try {
                vectorStore.add(DocRegistry.SHARED, CURATED_VERSION, whole);
                pruneStaleVectors(row, 1);
                return whole;
            } catch (Exception e) {
                log.warn("[CURATED] whole-row embed failed curatedId={}: {}", row.id(), e.getMessage());
            }
        }

        String core = CuratedTextUtils.extractCoreSections(row.answer());
        if (core.isBlank()) {
            log.warn("[CURATED] no core-section fallback available curatedId={} (answer isn't in the RAG format)",
                    row.id());
            return List.of();
        }
        String fallbackSearchText = row.question() + "\n\n" + MarkdownNoiseNormalizer.normalize(core);
        List<Document> fallback = List.of(buildDocument(row, 0, row.answer(), fallbackSearchText));
        try {
            vectorStore.add(DocRegistry.SHARED, CURATED_VERSION, fallback);
            pruneStaleVectors(row, 1);
            log.info("[CURATED] embedded with core-sections fallback curatedId={}", row.id());
            return fallback;
        } catch (Exception e) {
            log.warn("[CURATED] core-sections fallback embed also failed curatedId={}: {}", row.id(), e.getMessage());
            return List.of();
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
            deindex(stale, "stale curatedId=" + row.id());
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
        meta.put(MetaKey.DOC_ID, curatedDocId(row.id()));
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
        // 요약·키워드를 문서 청크와 같은 키로 싣는다 — /admin 청크 화면이 이 두 키만 보고
        // '요약'·'키워드' 칸을 그리므로, 이 축만 다른 이름을 쓰면 그 화면에서 영원히 빈칸이다.
        // 비어 있으면 키 자체를 넣지 않는다: 빈 문자열을 넣으면 FTS keywords 컬럼에 빈 토큰이
        // 들어가고, /admin 에서 "값이 있는데 비어 있음"과 "값이 없음"을 구분할 수 없게 된다.
        putIfPresent(meta, MetaKey.CHUNK_CONTEXT, row.summary());
        putIfPresent(meta, MetaKey.EXCERPT_KEYWORDS, row.keywords());
        meta.put(MetaKey.SEARCH_TEXT, searchText); // transient override — stripped before persistence

        return new Document(springDocId(row.id(), chunkIndex), storedText, meta);
    }

    private static void putIfPresent(Map<String, Object> meta, String key, String value) {
        if (value != null && !value.isBlank()) meta.put(key, value.strip());
    }

    /**
     * FTS 행에 실을 문서 — 벡터용 문서와 <b>검색 텍스트만</b> 다르다.
     *
     * <p>벡터 입력은 {@link #defaultSearchText}(질문 + 본문) 그대로 두고, FTS 입력에만 요약을
     * 앞에 붙인다. 두 축이 같은 텍스트를 원하지 않기 때문이다 — 요약은 본문을 다시 말한 것이라
     * 의미 벡터에서는 희석이고(그래서 {@code stripSummarySection} 이 임베딩에서 걷어낸다),
     * 어휘 매칭인 BM25 에서는 그 항목이 무엇에 관한 것인지를 말하는 <b>토큰의 반복</b>이다 —
     * 문서 청크에서 {@code chunk_context} 가 하는 일이 정확히 그것이고, 이 축에서는 그 자리를
     * 요약이 맡는다.
     */
    private static Document ftsDocument(Document vectorDoc, String summary) {
        if (summary == null || summary.isBlank()) return vectorDoc;
        Map<String, Object> meta = new HashMap<>(vectorDoc.getMetadata());
        meta.put(MetaKey.SEARCH_TEXT, summary.strip() + "\n\n" + SearchTextBuilder.build(vectorDoc));
        return new Document(vectorDoc.getId(), vectorDoc.getText(), meta);
    }

    /**
     * 방금 쓴 벡터와 같은 청크들을 BM25 축에도 쓴다. 먼저 지우고 넣는 것은
     * {@code AdminService.reindexChunk()} 와 같은 이유다 — {@code indexChunks()} 는 INSERT 라
     * 지우지 않으면 같은 {@code spring_doc_id} 의 행이 쌓인다.
     *
     * <p>실패해도 예외를 올리지 않는다: 벡터는 이미 성공적으로 쓰였고, 키워드 축이 빠진 항목은
     * 검색 품질이 조금 낮을 뿐 여전히 검색된다. 여기서 던지면 그 반대가 된다(항목 전체가
     * 임베딩 실패로 기록된다).
     */
    private void indexFts(CuratedQa row, List<Document> docs) {
        try {
            keywordRepo.deleteBySpringDocIds(docs.stream().map(Document::getId).toList());
            keywordRepo.indexChunks(docs.stream().map(d -> ftsDocument(d, row.summary())).toList());
        } catch (Exception e) {
            log.warn("[CURATED] FTS index failed curatedId={}: {}", row.id(), e.getMessage());
        }
    }

    /**
     * 이 축의 {@code doc_id} 형식 — {@code curated:{행 번호}}. 쓰는 곳과 읽는 곳이 갈리면
     * 접두사 하나 바꾸는 것이 조용한 사고가 되므로 형식은 여기 한 쌍에만 둔다
     * ({@code CuratedImageStore.markerPaths()} 와 같은 이유로 static 이다 — 읽는 쪽이
     * 이 서비스에 의존할 필요가 없다).
     */
    public static String curatedDocId(long rowId) {
        return DOC_ID_PREFIX + rowId;
    }

    /** {@link #curatedDocId} 의 역방향. 형식이 아니면 빈 값 — 추측해서 숫자를 만들지 않는다. */
    public static java.util.OptionalLong rowIdOf(String docId) {
        if (docId == null || !docId.startsWith(DOC_ID_PREFIX)) return java.util.OptionalLong.empty();
        try {
            return java.util.OptionalLong.of(Long.parseLong(docId.substring(DOC_ID_PREFIX.length())));
        } catch (NumberFormatException e) {
            return java.util.OptionalLong.empty();
        }
    }

    private static final String DOC_ID_PREFIX = "curated:";

    /**
     * 기동 시 <b>유령 FTS 행</b>을 쓸어낸다 — 이 축에서 내려간 항목인데 {@code chunk_fts} 행만
     * 남아 있는 경우.
     *
     * <p><b>왜 필요한가.</b> 벡터와 FTS 삭제는 {@link #deindex} 안에서 <b>각자의 try/catch</b> 를
     * 갖는다(한쪽이 실패해도 나머지는 진행한다 — 그 편이 회수를 통째로 포기하는 것보다 낫다).
     * 그래서 "벡터는 지워졌는데 FTS 는 남은" 상태가 설계상 도달 가능하고, 실제로 그렇게 남은 행이
     * 관찰됐다: 철회된 지식 제안의 청크가 채팅 답변에 계속 인용됐다. {@code RetrievalService}
     * 의 큐레이션 BM25 절반이 그 행을 그대로 집어 오고 {@code markCurated()} 가 출처 라벨까지
     * 붙여 주기 때문에, <b>내려간 지식이 멀쩡한 항목처럼 보인다</b>.
     *
     * <p><b>판정은 행 단위다</b>({@code chunk_count} 를 보지 않는다). 활성 행이 하나라도 소유한
     * id 는 건드리지 않고, <b>활성 행이 아예 없는</b> 행 번호의 것만 지운다 — {@code chunk_count}
     * 가 낡아 있으면 id 집합 비교는 살아 있는 청크의 키워드 축을 지울 수 있는데, 그 위험을 지지
     * 않기 위해서다(개수 드리프트는 {@code pruneStaleVectors} 의 일이다).
     *
     * <p>FTS 를 못 쓰는 빌드에서는 목록이 비어 no-op 이고, 실패해도 기동을 막지 않는다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void sweepOrphanFtsRows() {
        try {
            List<String> inFts = keywordRepo.springDocIdsForVersion(CURATED_VERSION);
            if (inFts.isEmpty()) return;

            Set<Long> active = repository.activeIds();
            List<String> orphans = inFts.stream()
                    .filter(id -> {
                        java.util.OptionalLong row = rowIdOfSpringDocId(id);
                        return row.isEmpty() || !active.contains(row.getAsLong());
                    })
                    .toList();
            if (orphans.isEmpty()) return;

            keywordRepo.deleteBySpringDocIds(orphans);
            log.warn("[CURATED] 유령 FTS 행 {}건 제거 — 내려간 항목이 키워드 축에 남아 답변 근거로 "
                     + "붙고 있었다: {}", orphans.size(), orphans);
        } catch (Exception e) {
            log.warn("[CURATED] 유령 FTS 행 청소 실패 (무시하고 계속): {}", e.getMessage());
        }
    }

    /**
     * {@link #springDocId} 의 역방향 — {@code curated-2} · {@code curated-2-1} → {@code 2}.
     * 형식이 아니면 빈 값이고, 그런 행은 이 축의 것이 아니므로 유령으로 본다.
     */
    private static java.util.OptionalLong rowIdOfSpringDocId(String springDocId) {
        if (springDocId == null || !springDocId.startsWith("curated-")) return java.util.OptionalLong.empty();
        String rest = springDocId.substring("curated-".length());
        int dash = rest.indexOf('-');
        try {
            return java.util.OptionalLong.of(Long.parseLong(dash < 0 ? rest : rest.substring(0, dash)));
        } catch (NumberFormatException e) {
            return java.util.OptionalLong.empty();
        }
    }

    /** Removes every vector this row owns — {@code chunkCount} ids, not just the first. */
    private void deleteVectors(long curatedId, int chunkCount) {
        deindex(vectorIdsFor(curatedId, chunkCount), "curatedId=" + curatedId);
    }

    /**
     * 이 축에서 무언가를 내리는 <b>단 하나의</b> 자리 — 벡터와 FTS 행을 함께 지운다.
     *
     * <p><b>둘은 짝이다.</b> 한쪽만 지우면 검색 코퍼스에서 내린 항목이 다른 축에는 남아 계속
     * 답변 근거로 붙는다. 특히 FTS 쪽이 남으면 {@code RetrievalService.curatedAxis()} 의 BM25
     * 질의가 그대로 집어 오고, {@code markCurated()} 가 출처 라벨까지 붙여 준다 — 내린 지식이
     * 멀쩡한 큐레이션 항목처럼 계속 인용된다. 덤으로 {@code chunk_fts_key} 의 해시가 살아 있어
     * {@code QuestionReuseService.validateTurn()} 의 "청크가 그대로인가" 검사까지 통과해,
     * 그 항목에 근거한 답변이 재사용되기까지 한다.
     *
     * <p>그래서 <b>id 목록을 받는 이 메서드 하나</b>만 둔다. 대화 삭제({@link #onThreadDeleted})는
     * 한 스레드의 모든 행을 한 번에 지우느라 자기 목록을 따로 모으는데, 예전에는 그 자리에서
     * {@code vectorStore.deleteByDocIds()} 를 직접 불러 <b>FTS 를 빠뜨렸다</b>. 짝을 기억에
     * 맡기는 대신 지우는 문을 하나로 만든 이유다.
     *
     * @param what 로그용 식별 문구(어느 행인지 / 어느 대화인지)
     */
    private void deindex(List<String> ids, String what) {
        if (ids == null || ids.isEmpty()) return;
        try {
            vectorStore.deleteByDocIds(DocRegistry.SHARED, CURATED_VERSION, ids);
        } catch (Exception e) {
            log.warn("[CURATED] vector delete failed {}: {}", what, e.getMessage());
        }
        try {
            keywordRepo.deleteBySpringDocIds(ids);
        } catch (Exception e) {
            log.warn("[CURATED] FTS delete failed {}: {}", what, e.getMessage());
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
