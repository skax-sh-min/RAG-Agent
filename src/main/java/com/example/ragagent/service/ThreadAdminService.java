package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.model.KstDateFormat;
import com.example.ragagent.model.ResponseMode;
import com.example.ragagent.model.ThreadMeta;
import com.example.ragagent.repository.ThreadAdminRepository;
import com.example.ragagent.repository.ThreadAdminRepository.Sort;
import com.example.ragagent.repository.ThreadAdminRepository.Summary;
import com.example.ragagent.repository.ThreadAdminRepository.ThreadRow;
import com.example.ragagent.repository.ThreadAdminRepository.TurnRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * §6.25 — read side of the {@code /admin} 대화 목록 panel.
 *
 * <p>Thin on purpose: the aggregate is one SQL statement (see
 * {@link ThreadAdminRepository#findAll}), so this layer only clamps the paging arguments, turns
 * the request's sort string into the closed {@link Sort} set, and assembles the one thing the
 * template can't derive from the rows — whether visitor separation is even switched on.
 */
@Service
public class ThreadAdminService {

    private static final Logger log = LoggerFactory.getLogger(ThreadAdminService.class);

    /** Matches the page-size choices the other {@code /admin} panels offer (20/50/100). */
    static final int MAX_LIMIT = 100;
    static final int DEFAULT_LIMIT = 20;

    /** Drill-down cap — opening a very long conversation must not render thousands of rows. */
    static final int MAX_TURNS = 500;

    /** Question preview length, matching the diagnostics panel so the two lists read alike. */
    static final int MAX_QUESTION_PREVIEW = 120;

    private final ThreadAdminRepository repository;
    private final AppProperties props;
    private final CuratedQaService curatedQaService;
    private final MemoryService memoryService;
    private final ThreadMetaService threadMetaService;

    public ThreadAdminService(ThreadAdminRepository repository, AppProperties props,
                              CuratedQaService curatedQaService, MemoryService memoryService,
                              ThreadMetaService threadMetaService) {
        this.repository = repository;
        this.props = props;
        this.curatedQaService = curatedQaService;
        this.memoryService = memoryService;
        this.threadMetaService = threadMetaService;
    }

    /**
     * One row as the template sees it.
     *
     * @param displayTitle the stored title minus the legacy {@code "[version]"} prefix, so the
     *                     admin list reads the same as the chat sidebar
     *                     ({@link ThreadMeta#displayTitle()} is the one implementation of that rule)
     */
    public record ThreadView(ThreadRow row, String displayTitle) {
        public String threadId()  { return row.threadId(); }
        public String userId()    { return row.userId(); }
        public String updatedAt() { return row.updatedAt(); }
        public int turnCount()    { return row.turnCount(); }
        public int reusedIn()     { return row.reusedIn(); }
        public int reusedOut()    { return row.reusedOut(); }
        public int diagCount()    { return row.diagCount(); }
        public int likeCount()    { return row.likeCount(); }
        public int dislikeCount() { return row.dislikeCount(); }
        public String tags()      { return row.tags(); }

        /**
         * Shortened owner id for the table cell — a guest id is
         * {@code guest-<12 hex>} (an HMAC, see {@code GuestIdentityResolver}), which is
         * unreadable at full length and identical-looking across visitors up to the prefix. The
         * full value stays available as the cell's tooltip.
         */
        public String shortUserId() {
            String id = row.userId();
            if (id == null) return "";
            return id.length() <= 14 ? id : id.substring(0, 14) + "…";
        }

        /**
         * True when most of this conversation never ran retrieval — reuse/Direct/meta turns carry
         * no diagnostics. Drives the list's ⚠ marker, the same "retrieved a lot, used little"
         * spirit as the diagnostics panel's own warning, one level up.
         */
        public boolean mostlyWithoutRetrieval() {
            return row.turnCount() > 0 && row.diagCount() * 2 < row.turnCount();
        }
    }

    /**
     * The whole panel payload.
     *
     * @param visitorSeparationOff no-auth mode with {@code guest-identity=shared}: every visitor
     *                             shares one id, so this list collapses to one owner no matter how
     *                             many people used the deployment. The panel says so rather than
     *                             letting the operator read it as "one person used this".
     */
    public record PanelView(List<ThreadView> threads, Summary summary, List<String> userIds,
                            String userFilter, Sort sort, int offset, int limit, int total,
                            boolean visitorSeparationOff) {}

    public PanelView panel(String userId, String sortKey, int offset, int limit) {
        Sort sort = Sort.parse(sortKey);
        int safeLimit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        int safeOffset = Math.max(0, offset);
        String filter = (userId == null || userId.isBlank()) ? null : userId.strip();

        List<ThreadView> rows = repository.findAll(filter, sort, safeOffset, safeLimit).stream()
                .map(r -> new ThreadView(r, ThreadMeta.stripVersionPrefix(r.title())))
                .toList();

        return new PanelView(rows, repository.summary(), repository.distinctUserIds(),
                filter, sort, safeOffset, safeLimit, repository.count(filter),
                visitorSeparationOff());
    }

    /**
     * The turns of one conversation, for the drill-down. Resolves the owner from the thread id
     * itself ({@link ThreadAdminRepository#findOwner}) so no caller has to hand one in — the same
     * reason the delete endpoint takes only a thread id.
     *
     * <p>Empty for an unknown thread: a conversation that isn't there and a conversation with no
     * turns look the same to the operator, and neither is worth an error page in a panel.
     */
    public List<TurnView> turns(String threadId) {
        return repository.findOwner(threadId)
                .map(owner -> repository.findTurns(owner, threadId, MAX_TURNS).stream()
                        .map(TurnView::new)
                        .toList())
                .orElseGet(List::of);
    }

    /** One drill-down turn as the template sees it — see {@link TurnRow} on why there is no answer. */
    public record TurnView(TurnRow row) {
        public long turnId()            { return row.turnId(); }
        public String askedAt()         { return row.askedAt(); }
        public String provider()        { return row.provider(); }
        public boolean reused()         { return row.reused(); }
        public boolean directMode()     { return row.directMode(); }
        public boolean hasDiagnostics() { return row.hasDiagnostics(); }

        /** 진단 패널과 같은 규칙 — 저장은 UTC, 표시는 KST(§6.25 결정 2). */
        public String askedAtKst() {
            return KstDateFormat.utcStampToKst(row.askedAt());
        }

        /** Preview line — the panel is a list, not a transcript reader. */
        public String question() {
            String q = row.question();
            if (q == null) return "";
            String oneLine = q.replaceAll("\\s+", " ").strip();
            return oneLine.length() > MAX_QUESTION_PREVIEW
                    ? oneLine.substring(0, MAX_QUESTION_PREVIEW) + "…"
                    : oneLine;
        }

        /**
         * The mode this turn actually answered in. Goes through {@link ResponseMode#parse} rather
         * than showing the stored string, so legacy {@code "M"}/{@code "L"} and NULL read as the
         * {@code N} they behave as — the same rule the chat bubbles follow.
         */
        public String responseMode() {
            return ResponseMode.parse(row.responseMode()).name();
        }

        public boolean liked()    { return "LIKE".equals(row.feedback()); }
        public boolean disliked() { return "DISLIKE".equals(row.feedback()); }
    }

    // ── 답변 원문 열람 (§6.25 결정 3) ────────────────────────────────────────

    /**
     * One turn's question and answer, in full.
     *
     * @param askedAtKst 저장은 UTC, 표시는 KST — 목록·드릴다운과 같은 규칙(결정 2)
     */
    public record TurnContentView(long turnId, String userId, String threadId, String askedAtKst,
                                  String question, String answer, String responseMode) {}

    /**
     * 한 턴의 원문. <b>호출 자체가 기록에 남을 일</b>이므로 컨트롤러가 결과를 받은 뒤
     * {@code admin.thread.read} 감사 이벤트를 남긴다 — 여기서 남기지 않는 이유는 감사에 필요한
     * 행위자(관리자 id)를 서비스가 알지 못하고, "조회했다"는 사실은 응답이 실제로 나갔을 때만
     * 참이기 때문이다(없는 턴은 404 이고 열람이 아니다).
     */
    public Optional<TurnContentView> turnContent(long turnId) {
        return repository.findTurnContent(turnId).map(c -> new TurnContentView(
                c.turnId(), c.userId(), c.threadId(),
                KstDateFormat.utcStampToKst(c.askedAt()),
                c.question(), c.answer(),
                ResponseMode.parse(c.responseMode()).name()));
    }

    // ── 삭제 ─────────────────────────────────────────────────────────────────

    /**
     * What deleting this conversation would cost, read <b>now</b>.
     *
     * <p>The confirmation dialog is the whole safeguard on an irreversible cross-user delete, so
     * the numbers behind it are re-read at click time instead of scraped from the rendered row —
     * the panel may have been open for a while, and approving a delete against stale counts is the
     * failure this exists to prevent.
     *
     * @param curatedCount 좋아요로 승격된 큐레이션 항목 수 — 이 대화를 지우면 함께 회수된다.
     *                     목록 집계에 넣지 않은 이유는 `curated_qa`에 `source_thread_id` 인덱스가
     *                     없어 행마다 스캔이 되는데, 이 값이 필요한 순간은 삭제 클릭 한 번뿐이기 때문.
     * @param reusedOut    이 대화의 답변에 의존하는 다른 턴 수 — 삭제하면 전부 "참조 원문 삭제됨"이
     *                     된다. 되돌릴 수 없는 쪽의 대가라 확인 문구에서 가장 무겁게 다룬다.
     */
    public record DeletePreview(String threadId, String displayTitle, String userId,
                                int turnCount, int reusedOut, int diagCount, int curatedCount) {}

    /** Empty when the conversation isn't there — a delete of something already gone is a 404,
     *  not a dialog. */
    public Optional<DeletePreview> deletePreview(String threadId) {
        return repository.findOne(threadId).map(r -> new DeletePreview(
                r.threadId(),
                ThreadMeta.stripVersionPrefix(r.title()),
                r.userId(),
                r.turnCount(),
                r.reusedOut(),
                r.diagCount(),
                curatedQaService.countActiveByThread(r.userId(), threadId)));
    }

    /** What the delete actually removed — fed straight into the audit entry. */
    public record DeleteResult(String threadId, String userId, int turnCount, int curatedRetracted) {}

    /**
     * Deletes one conversation on an operator's behalf.
     *
     * <p><b>The owner is resolved here, from the thread id.</b> {@code thread_meta.thread_id} is
     * the primary key, so nothing needs to hand one in — and the endpoint therefore exposes no
     * parameter naming <em>whose</em> conversation to act on.
     *
     * <p>Same order and the same three steps as the user's own delete path
     * ({@code OperationsController.deleteThread}), curated retraction first: a curated row is
     * linked to its turn by a copy of the id, not a foreign key, so without that the row and its
     * vectors would outlive the conversation and keep feeding search (§6.25 결정 4). Any future
     * delete path must call the same three, or the outcome depends on which button was pressed.
     *
     * @return empty when the conversation doesn't exist (nothing was touched)
     */
    public Optional<DeleteResult> delete(String threadId) {
        Optional<String> owner = repository.findOwner(threadId);
        if (owner.isEmpty()) return Optional.empty();

        String userId = owner.get();
        int turnCount = repository.findOne(threadId).map(ThreadRow::turnCount).orElse(0);

        int curatedRetracted = curatedQaService.onThreadDeleted(userId, threadId);
        memoryService.clearHistory(userId, threadId);
        threadMetaService.delete(userId, threadId);

        log.info("[ADMIN] 대화 삭제 threadId={} owner={} 턴={} 큐레이션회수={}",
                threadId, userId, turnCount, curatedRetracted);
        return Optional.of(new DeleteResult(threadId, userId, turnCount, curatedRetracted));
    }

    /**
     * Only true in no-auth mode: with real logins every account is its own owner regardless of
     * what {@code app.auth.guest-identity} says (its resolver isn't even in the context then).
     */
    private boolean visitorSeparationOff() {
        var auth = props.authSafe();
        return !auth.enabled()
                && AppProperties.GuestIdentity.SHARED.equals(auth.guestIdentity());
    }
}
