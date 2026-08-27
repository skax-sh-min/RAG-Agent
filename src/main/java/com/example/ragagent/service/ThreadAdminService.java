package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.model.ThreadMeta;
import com.example.ragagent.repository.ThreadAdminRepository;
import com.example.ragagent.repository.ThreadAdminRepository.Sort;
import com.example.ragagent.repository.ThreadAdminRepository.Summary;
import com.example.ragagent.repository.ThreadAdminRepository.ThreadRow;
import org.springframework.stereotype.Service;

import java.util.List;

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

    /** Matches the page-size choices the other {@code /admin} panels offer (20/50/100). */
    static final int MAX_LIMIT = 100;
    static final int DEFAULT_LIMIT = 20;

    private final ThreadAdminRepository repository;
    private final AppProperties props;

    public ThreadAdminService(ThreadAdminRepository repository, AppProperties props) {
        this.repository = repository;
        this.props = props;
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
     * Only true in no-auth mode: with real logins every account is its own owner regardless of
     * what {@code app.auth.guest-identity} says (its resolver isn't even in the context then).
     */
    private boolean visitorSeparationOff() {
        var auth = props.authSafe();
        return !auth.enabled()
                && AppProperties.GuestIdentity.SHARED.equals(auth.guestIdentity());
    }
}
