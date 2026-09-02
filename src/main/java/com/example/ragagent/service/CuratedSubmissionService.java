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
    private final CuratedImageStore imageStore;
    private final MemoryService memoryService;
    private final AppProperties props;
    private final AuditLogger auditLogger;

    public CuratedSubmissionService(CuratedSubmissionRepository repository,
                                    CuratedQaService curatedQaService,
                                    CuratedImageStore imageStore,
                                    MemoryService memoryService,
                                    AppProperties props,
                                    AuditLogger auditLogger) {
        this.repository = repository;
        this.curatedQaService = curatedQaService;
        this.imageStore = imageStore;
        this.memoryService = memoryService;
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
        return submit(authorUserId, title, body, tags, null, null);
    }

    /**
     * §10.11 — same as {@link #submit(String, String, String, List)}, plus the chat turn this
     * proposal came from. The turn is <b>re-read and ownership-checked here</b>: the client may
     * edit the prefilled text (that is the point of routing 좋아요 through review), but it may not
     * decide on its own that some text "came from a chat answer". An unresolvable or foreign
     * turn is not an error — the proposal is simply stored as a hand-written one.
     *
     * <p>The per-user pending cap is <b>skipped</b> for these (trap ④): the cap exists to stop a
     * user from flooding the review queue by typing, and a 좋아요 is one button press with nowhere
     * to render a "20건이 밀려 있습니다" form error. Flooding by 좋아요 is bounded by
     * {@link #findLiveProposalForTurn} instead — one live proposal per turn.
     */
    public long submit(String authorUserId, String title, String body, List<String> tags,
                       String sourceThreadId, Long sourceTurnId) {
        boolean fromTurn = sourceTurnId != null && sourceThreadId != null
                && memoryService.getTurn(authorUserId, sourceThreadId, sourceTurnId).isPresent();
        return submitInternal(authorUserId, title, body, tags,
                fromTurn ? sourceTurnId : null, fromTurn ? sourceThreadId : null);
    }

    private long submitInternal(String authorUserId, String title, String body, List<String> tags,
                                Long sourceTurnId, String sourceThreadId) {
        String cleanTitle = PromptInjectionGuard.validate(title == null ? null : title.trim());
        if (cleanTitle.length() > MAX_TITLE_LEN) {
            throw new IllegalArgumentException(
                    "제목이 너무 깁니다 (최대 " + MAX_TITLE_LEN + "자, 입력: " + cleanTitle.length() + "자)");
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("본문을 입력해 주세요.");
        }
        String cleanBody = body.strip();
        // 본문 길이는 제한하지 않지만 이미지 개수는 제한한다 — 승인 한 번이 이미지 수만큼 Vision 호출을
        // 부채질하기 때문(describeImages). 길이와 달리 분할로 흡수되지 않는 비용이다.
        imageStore.validateImageCount(cleanBody);
        // TagUtils.normalize throws on policy violation (최대 10개 / 32자) — the message is user-facing.
        String tagsCsv = TagUtils.toMetaValue(TagUtils.normalize(tags));
        if (sourceTurnId == null && repository.countPendingByAuthor(authorUserId) >= MAX_PENDING_PER_USER) {
            throw new IllegalArgumentException(
                    "검토 대기 중인 제안이 이미 " + MAX_PENDING_PER_USER + "건입니다. 처리된 뒤 다시 등록해 주세요.");
        }

        long id = repository.insert(authorUserId, cleanTitle, cleanBody, tagsCsv,
                sourceTurnId, sourceThreadId);
        auditLogger.log("curated.submission.create", "submission:" + id,
                Map.of("title", cleanTitle, "chars", cleanBody.length(), "tags", tagsCsv,
                        "sourceTurnId", sourceTurnId == null ? "" : String.valueOf(sourceTurnId)));
        log.info("[SUBMISSION] 신규 청크 제안 등록 id={} author={} chars={} turn={}",
                id, authorUserId, cleanBody.length(), sourceTurnId);
        return id;
    }

    /**
     * §10.11 프리필 — the 좋아요된 답변, read <b>server-side from the turn</b> so the form starts
     * from what was actually said (trap ③: a 3,000자 답변 does not fit in a URL, and text carried
     * by the client could claim an origin it doesn't have).
     *
     * <p>Empty when the turn doesn't exist or isn't this user's — the page then just renders the
     * blank write form rather than an error, since a stale link is not worth a failure page.
     */
    public Optional<TurnPrefill> prefillFromTurn(String userId, String threadId, long turnId) {
        return memoryService.getTurn(userId, threadId, turnId).map(turn -> new TurnPrefill(
                turnId,
                threadId,
                // 질문은 2,000자까지 가능하고 제목은 200자다 — 자르지 않으면 폼이 예외로 죽는다.
                truncateTitle(turn.question()),
                turn.answer(),
                turn.selectedTags(),
                CuratedImageStore.markerPaths(turn.answer()).size(),
                turn.responseModeLabel()));
    }

    /**
     * §10.11 중복 제안 방지 — the live (pending/approved) proposal already made for this turn.
     * The chat's 좋아요 flow sends the user to that entry instead of opening a second draft.
     */
    public Optional<Submission> findLiveProposalForTurn(long turnId) {
        return repository.findLiveByTurn(turnId);
    }

    /** Cuts a chat question down to a title. Public so the chat-side prefill and the form agree. */
    public static String truncateTitle(String question) {
        String q = question == null ? "" : question.strip().replaceAll("\\s+", " ");
        return q.length() <= MAX_TITLE_LEN ? q : q.substring(0, MAX_TITLE_LEN);
    }

    /**
     * What the 제안 폼 is pre-populated with when it is opened from a 좋아요.
     *
     * @param imageCount how many image markers the answer carries — shown next to
     *                   {@link CuratedImageStore#MAX_IMAGES_PER_SUBMISSION} so the author can
     *                   delete some <em>before</em> submitting rather than being rejected after
     *                   (trap ⑥; document images are counted too)
     * @param modeLabel  the turn's two-letter 표기 ({@code RN}/{@code DN}/…) — the same label the
     *                   admin will review it under, shown here so the author knows a Direct answer
     *                   is being proposed as shared knowledge
     */
    public record TurnPrefill(long turnId, String threadId, String title, String body,
                              String tags, int imageCount, String modeLabel) {}

    /**
     * Admin 임베딩 실행: copies the (possibly edited) text into a real curated row and flips the
     * submission to approved. The admin's edits are saved back onto the submission too — as are the
     * Vision descriptions injected below — so the author sees what was actually indexed rather than
     * their original draft.
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
        imageStore.validateImageCount(body);

        // 본문 이미지에 Vision 설명을 주입한 뒤 자른다. 순서가 중요하다 — 설명은 임베딩되는 텍스트의
        // 일부여야 이미지 내용이 검색에 걸리고, 임베딩은 지금 이 승인 시점에 딱 한 번 일어난다.
        // 나중에(분할 후) 주입하면 마커와 설명이 서로 다른 청크로 갈라질 수 있다.
        body = imageStore.describeImages(body);

        // 등록 경로가 출처에 따라 갈린다 (§10.11 함정 ①). 좋아요 출신은 turn 단위로 식별되는
        // 행 하나이고(대화·턴 삭제 회수와 재승인이 전부 그 키를 탄다), 손으로 쓴 제안은 승인 시점에
        // 미리 나뉜 N개 행이다. 태그는 어느 쪽이든 모든 청크에 동일하게 부여한다(제안 하나 = 한 스코프).
        List<Long> curatedIds;
        if (row.fromChatTurn()) {
            // 재승인이면 같은 행이 제자리에서 갱신되고 다시 임베딩된다 — 이것이 정책 3의 "교체"다.
            // 여기서 먼저 회수하면 백그라운드 벡터 삭제가 방금 새로 쓴 벡터를 지울 수 있다(같은 id).
            curatedIds = List.of(curatedQaService.createFromLikedTurn(submissionId, row.sourceTurnId(),
                    row.authorUserId(), row.sourceThreadId(), title, body, tagsCsv));
        } else {
            // 손으로 쓴 제안은 승인마다 새 id 의 행이 생기므로, 남아 있던 이전 등록본을 먼저 내린다.
            // 수정 중에도 검색에 남아 있던(정책 3) 그 행들이다 — 안 내리면 같은 제안이 두 벌로 검색된다.
            if (row.curatedActive() > 0) {
                int replaced = curatedQaService.forceRemoveBySubmission(submissionId);
                log.info("[SUBMISSION] 재승인 — 이전 등록본 {}건을 교체한다 id={}", replaced, submissionId);
            }
            curatedIds = curatedQaService.createFromSubmission(
                    submissionId, row.authorUserId(), title, splitBody(body), tagsCsv);
        }
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
        Optional<Submission> rowOpt = repository.findById(submissionId);
        boolean ok = repository.markRejected(submissionId, reviewerUserId, reviewNote.trim());
        if (ok) {
            // 상태를 먼저 바꾸고 정리한다 — releaseImages()가 "아직 살아 있는 제안"을 훑어 참조 여부를
            // 판단하므로, 이 행이 rejected 로 바뀐 뒤에야 자기 자신을 참조로 세지 않는다.
            rowOpt.ifPresent(row -> imageStore.releaseImages(row.body()));
            auditLogger.log("curated.submission.reject", "submission:" + submissionId,
                    Map.of("note", reviewNote.trim()));
            log.info("[SUBMISSION] 거부 id={}", submissionId);
        }
        return ok;
    }

    /**
     * Author-initiated withdrawal. §10.11 — this now covers an <b>approved</b> submission too, and
     * that is the whole point: before, a user could take back a proposal only while nobody had
     * looked at it, so contributed knowledge was irretrievable except through an admin. With 좋아요
     * routed through this board that gap would cover every promoted answer.
     *
     * <p>The board row flips first, then the curated rows come down, then the images are released —
     * both cleanups scan for live references and must not count this row as one.
     */
    public boolean withdraw(long submissionId, String authorUserId) {
        Optional<Submission> rowOpt = repository.findById(submissionId);
        boolean wasApproved = rowOpt.map(r -> CuratedSubmissionRepository.STATUS_APPROVED.equals(r.status()))
                .orElse(false);
        boolean ok = repository.markWithdrawn(submissionId, authorUserId);
        if (ok) {
            if (wasApproved) {
                int removed = curatedQaService.forceRemoveBySubmission(submissionId);
                auditLogger.log("curated.submission.withdraw", "submission:" + submissionId,
                        Map.of("author", authorUserId, "retracted", removed));
                log.info("[SUBMISSION] 저자 철회 id={} — 등록본 {}건 회수", submissionId, removed);
            }
            rowOpt.ifPresent(row -> imageStore.releaseImages(row.body()));
        }
        return ok;
    }

    /**
     * §10.11 저자 수정 — the author rewrites their own proposal; it goes back to 검토 대기 either
     * way. An approved entry keeps serving search until the new text is approved (정책 3), so an
     * edit costs nothing while it waits.
     *
     * <p>Same validation as {@link #submit} minus the pending cap — the row already exists, so
     * editing it cannot add to the queue.
     */
    public boolean updateByAuthor(long submissionId, String authorUserId,
                                  String title, String body, List<String> tags) {
        String cleanTitle = PromptInjectionGuard.validate(title == null ? null : title.trim());
        if (cleanTitle.length() > MAX_TITLE_LEN) {
            throw new IllegalArgumentException(
                    "제목이 너무 깁니다 (최대 " + MAX_TITLE_LEN + "자, 입력: " + cleanTitle.length() + "자)");
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("본문을 입력해 주세요.");
        }
        String cleanBody = body.strip();
        imageStore.validateImageCount(cleanBody);
        String tagsCsv = TagUtils.toMetaValue(TagUtils.normalize(tags));

        Optional<Submission> before = repository.findById(submissionId);
        if (!repository.updateByAuthor(submissionId, authorUserId, cleanTitle, cleanBody, tagsCsv)) {
            return false;
        }
        // 수정으로 본문에서 빠진 이미지를 정리한다 — releaseImages 는 살아 있는 다른 본문이
        // 참조하는 파일은 남기므로, 새 본문에 그대로 남은 이미지는 지워지지 않는다.
        before.ifPresent(row -> imageStore.releaseImages(row.body()));
        auditLogger.log("curated.submission.update", "submission:" + submissionId,
                Map.of("author", authorUserId, "chars", cleanBody.length(), "tags", tagsCsv));
        log.info("[SUBMISSION] 저자 수정 id={} author={} — 검토 대기로 되돌림", submissionId, authorUserId);
        return true;
    }

    public List<Submission> listForAdmin(String status, int offset, int limit) {
        return repository.findByStatus(status, offset, limit);
    }

    public List<Submission> listMine(String authorUserId, int offset, int limit) {
        return repository.findByAuthor(authorUserId, offset, limit);
    }

    public List<Submission> listMine(String authorUserId, String status, int offset, int limit) {
        return repository.findByAuthor(authorUserId, status, offset, limit);
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
