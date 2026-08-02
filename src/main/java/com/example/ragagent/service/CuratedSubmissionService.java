package com.example.ragagent.service;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.ingestion.MarkdownNoiseNormalizer;
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
     * The body length ceiling, in normalized characters. One submission is exactly one chunk (no
     * auto-splitting — a split would spread approve/reject/withdraw across several vectors), so the
     * indexing chunk size is the natural bound. Hot-editable via /settings, hence read per call.
     *
     * <p>This check is load-bearing rather than cosmetic: {@code CuratedQaService}'s "input too
     * large" retry narrows the text down to the answer's {@code ## 상세 설명}/{@code ## 예시·코드}
     * sections, a structure a hand-written submission doesn't have — so for these rows there is no
     * fallback and an over-long body simply fails to embed.
     */
    public int maxBodyLength() {
        return props.chunkSizeSafe();
    }

    /**
     * Validates and stores a new submission. Throws {@link IllegalArgumentException} (→ 400) with a
     * user-facing Korean message on any rule violation. Returns the new submission id.
     */
    public long submit(String authorUserId, String title, String body) {
        String cleanTitle = PromptInjectionGuard.validate(title == null ? null : title.trim());
        if (cleanTitle.length() > MAX_TITLE_LEN) {
            throw new IllegalArgumentException(
                    "제목이 너무 깁니다 (최대 " + MAX_TITLE_LEN + "자, 입력: " + cleanTitle.length() + "자)");
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("본문을 입력해 주세요.");
        }
        String cleanBody = body.strip();
        int normalized = MarkdownNoiseNormalizer.normalize(cleanBody).length();
        int limit = maxBodyLength();
        if (normalized > limit) {
            throw new IllegalArgumentException(
                    "본문이 너무 깁니다 (최대 " + limit + "자, 입력: " + normalized + "자). " +
                    "한 건은 청크 하나로 등록되므로 내용을 나눠서 등록해 주세요.");
        }
        if (repository.countPendingByAuthor(authorUserId) >= MAX_PENDING_PER_USER) {
            throw new IllegalArgumentException(
                    "검토 대기 중인 제안이 이미 " + MAX_PENDING_PER_USER + "건입니다. 처리된 뒤 다시 등록해 주세요.");
        }

        long id = repository.insert(authorUserId, cleanTitle, cleanBody);
        auditLogger.log("curated.submission.create", "submission:" + id,
                Map.of("title", cleanTitle, "chars", normalized));
        log.info("[SUBMISSION] 신규 청크 제안 등록 id={} author={}", id, authorUserId);
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
                                  String editedTitle, String editedBody) {
        Optional<Submission> rowOpt = repository.findById(submissionId);
        if (rowOpt.isEmpty() || !rowOpt.get().isPending()) return Optional.empty();
        Submission row = rowOpt.get();

        String title = (editedTitle == null || editedTitle.isBlank()) ? row.title() : editedTitle.trim();
        String body  = (editedBody  == null || editedBody.isBlank())  ? row.body()  : editedBody.strip();

        // tags=null: 제안 게시판의 태그 입력은 아직 없다(다음 단계). 태그 없는 큐레이션 항목은
        // 어떤 태그 스코프에서도 걸러지지 않으므로(RetrievalService의 큐레이션 면제) 그때까지도
        // 승인된 제안은 정상적으로 검색된다.
        long curatedId = curatedQaService.createFromSubmission(submissionId, row.authorUserId(), title, body, null);
        if (!repository.markApproved(submissionId, reviewerUserId, title, body, curatedId)) {
            // Lost the CAS — another request approved it first. Undo the row we just created so the
            // submission keeps exactly one curated entry (forceRemove also de-indexes it).
            curatedQaService.forceRemove(curatedId);
            log.info("[SUBMISSION] 승인 경합으로 취소 id={} curatedId={}", submissionId, curatedId);
            return Optional.empty();
        }

        auditLogger.log("curated.submission.approve", "submission:" + submissionId,
                Map.of("curatedId", curatedId, "author", row.authorUserId()));
        log.info("[SUBMISSION] 승인·임베딩 실행 id={} curatedId={}", submissionId, curatedId);
        return Optional.of(curatedId);
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
