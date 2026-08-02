package com.example.ragagent.service;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.model.TagUtils;
import com.example.ragagent.repository.CuratedSubmissionRepository;
import com.example.ragagent.repository.CuratedSubmissionRepository.Submission;
import com.example.ragagent.security.PromptInjectionGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 청크 추가 — the 게시판 half of the user-submitted-chunk flow: users post, an admin later runs
 * 임베딩 실행 (→ {@link CuratedQaService#createFromSubmission}) or 거부.
 *
 * <p>Approval is the only gate between a user's text and the RAG context that gets injected into
 * answer prompts, so nothing here auto-approves and the admin UI always shows the full body — see
 * {@link #approve}.
 */
@Service
public class CuratedSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(CuratedSubmissionService.class);

    /** Titles are a heading, not a paragraph — much tighter than PromptInjectionGuard's 2,000. */
    public static final int MAX_TITLE_LEN = 200;

    /** Anti-flood: a user can queue this many unreviewed submissions at once. */
    public static final int MAX_PENDING_PER_USER = 20;

    private final CuratedSubmissionRepository repository;
    private final CuratedQaService curatedQaService;
    private final AppProperties props;
    private final AuditLogger auditLogger;

    public CuratedSubmissionService(CuratedSubmissionRepository repository,
                                    CuratedQaService curatedQaService,
                                    AppProperties props,
                                    AuditLogger auditLogger) {
        this.repository = repository;
        this.curatedQaService = curatedQaService;
        this.props = props;
        this.auditLogger = auditLogger;
    }

    /**
     * The chunk size a body is measured against — <b>not a limit</b>. There is deliberately no
     * ceiling on submission length any more: a body longer than this is split into several curated
     * chunks at approval time ({@link #splitBody}), exactly the way a document is. The form shows
     * this number only so the author can see how their text will be divided.
     *
     * <p>Splitting is what removes the "임베딩 불가능한 본문" failure mode entirely. Before, an
     * over-long body reached the embedding API whole and failed there, and {@code CuratedQaService}'s
     * "input too large" retry could not help — it narrows to the {@code ## 상세 설명} sections a
     * hand-written post doesn't have. Now every stored chunk is bounded by the same
     * {@code chunk-size} / {@code embedding.max-chunk-chars} the document pipeline respects.
     */
    public int chunkSizeForBody() {
        return props.chunkSizeSafe();
    }

    /**
     * Splits a submission body the same way a document is chunked, so an arbitrarily long proposal
     * becomes N embeddable chunks. Delegates to {@link CuratedQaService#splitForEmbedding} — the
     * liked-answer path splits with exactly the same rules, and having one implementation is what
     * keeps the two curated origins producing comparably sized vectors.
     */
    public List<String> splitBody(String body) {
        return curatedQaService.splitForEmbedding(body);
    }

    /** How many chunks this body would become — the form's "약 N개 청크" hint. */
    public int previewChunkCount(String body) {
        return splitBody(body).size();
    }

    /**
     * Validates and stores a new submission. Throws {@link IllegalArgumentException} (→ 400) with a
     * user-facing Korean message on any rule violation. Returns the new submission id.
     *
     * <p>Body length is <b>not</b> validated — see {@link #chunkSizeForBody}. Title length and the
     * per-user pending cap still are.
     */
    public long submit(String authorUserId, String title, String body, List<String> tags) {
        String cleanTitle = PromptInjectionGuard.validate(title == null ? null : title.trim());
        if (cleanTitle.length() > MAX_TITLE_LEN) {
            throw new IllegalArgumentException(
                    "제목이 너무 깁니다 (최대 " + MAX_TITLE_LEN + "자, 입력: " + cleanTitle.length() + "자)");
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("본문을 입력해 주세요.");
        }
        String cleanBody = body.strip();
        // TagUtils.normalize throws on policy violation (최대 10개 / 32자) — the message is user-facing.
        String tagsCsv = TagUtils.toMetaValue(TagUtils.normalize(tags));
        if (repository.countPendingByAuthor(authorUserId) >= MAX_PENDING_PER_USER) {
            throw new IllegalArgumentException(
                    "검토 대기 중인 제안이 이미 " + MAX_PENDING_PER_USER + "건입니다. 처리된 뒤 다시 등록해 주세요.");
        }

        long id = repository.insert(authorUserId, cleanTitle, cleanBody, tagsCsv);
        auditLogger.log("curated.submission.create", "submission:" + id,
                Map.of("title", cleanTitle, "chars", cleanBody.length(), "tags", tagsCsv));
        log.info("[SUBMISSION] 신규 청크 제안 등록 id={} author={} chars={}", id, authorUserId, cleanBody.length());
        return id;
    }

    /**
     * Admin 임베딩 실행: copies the (possibly edited) text into a real curated row and flips the
     * submission to approved. The admin's edits are saved back onto the submission too, so the
     * author sees what was actually indexed rather than their original draft.
     *
     * <p>Returns empty when the submission doesn't exist or is no longer pending (already handled,
     * withdrawn, or won by a concurrent approval — {@code markApproved} is a compare-and-set).
     */
    public Optional<Long> approve(long submissionId, String reviewerUserId,
                                  String editedTitle, String editedBody, List<String> editedTags) {
        Optional<Submission> rowOpt = repository.findById(submissionId);
        if (rowOpt.isEmpty() || !rowOpt.get().isPending()) return Optional.empty();
        Submission row = rowOpt.get();

        String title = (editedTitle == null || editedTitle.isBlank()) ? row.title() : editedTitle.trim();
        String body  = (editedBody  == null || editedBody.isBlank())  ? row.body()  : editedBody.strip();
        String tagsCsv = (editedTags == null) ? row.tags() : TagUtils.toMetaValue(TagUtils.normalize(editedTags));

        // 본문을 문서와 같은 방식으로 분할해 N개 청크로 등록한다 — 길이 제한이 없어진 대신 각 청크가
        // 임베딩 가능한 크기로 보장된다. 태그는 모든 청크에 동일하게 부여한다(제안 하나 = 한 스코프).
        List<String> bodyChunks = splitBody(body);
        List<Long> curatedIds = curatedQaService.createFromSubmission(
                submissionId, row.authorUserId(), title, bodyChunks, tagsCsv);
        long firstCuratedId = curatedIds.get(0);

        if (!repository.markApproved(submissionId, reviewerUserId, title, body, tagsCsv, firstCuratedId)) {
            // Lost the CAS — another request approved it first. Undo every chunk we just created
            // (전부/전무: a half-rolled-back submission would show as partially registered).
            curatedQaService.forceRemoveBySubmission(submissionId);
            log.info("[SUBMISSION] 승인 경합으로 취소 id={} 청크 {}건", submissionId, curatedIds.size());
            return Optional.empty();
        }

        auditLogger.log("curated.submission.approve", "submission:" + submissionId,
                Map.of("curatedIds", curatedIds, "chunks", curatedIds.size(),
                        "author", row.authorUserId(), "tags", tagsCsv == null ? "" : tagsCsv));
        log.info("[SUBMISSION] 승인·임베딩 실행 id={} → 청크 {}건 {}", submissionId, curatedIds.size(), curatedIds);
        return Optional.of(firstCuratedId);
    }

    /** Admin 거부. The note is mandatory — it is the only feedback the author gets. */
    public boolean reject(long submissionId, String reviewerUserId, String reviewNote) {
        if (reviewNote == null || reviewNote.isBlank()) {
            throw new IllegalArgumentException("거부 사유를 입력해 주세요.");
        }
        boolean ok = repository.markRejected(submissionId, reviewerUserId, reviewNote.trim());
        if (ok) {
            auditLogger.log("curated.submission.reject", "submission:" + submissionId,
                    Map.of("note", reviewNote.trim()));
            log.info("[SUBMISSION] 거부 id={}", submissionId);
        }
        return ok;
    }

    /** Author-initiated withdrawal of a still-pending submission. */
    public boolean withdraw(long submissionId, String authorUserId) {
        return repository.markWithdrawn(submissionId, authorUserId);
    }

    public List<Submission> listForAdmin(String status, int offset, int limit) {
        return repository.findByStatus(status, offset, limit);
    }

    public List<Submission> listMine(String authorUserId, int offset, int limit) {
        return repository.findByAuthor(authorUserId, offset, limit);
    }

    public Optional<Submission> findById(long id) {
        return repository.findById(id);
    }

    public int countPending() {
        return repository.countPending();
    }

    public int countUnreadForAuthor(String authorUserId) {
        return repository.countUnreviewedNotificationsForAuthor(authorUserId);
    }

    public void markAllReadForAuthor(String authorUserId) {
        repository.markAllReadForAuthor(authorUserId);
    }
}
