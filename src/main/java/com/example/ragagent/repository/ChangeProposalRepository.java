package com.example.ragagent.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 유지보수 수정안 대기열({@code change_proposal}, Flyway V13). <b>여기에 행을 만드는 것은 코퍼스를 바꾸지 않는다</b> — 검토를 기다리는 기록일
 * 뿐이다. 승인·적용은 별도 단계다(상태 전이는 {@code compare-and-set} 이 되도록 {@link #transition} 하나로만 한다).
 */
@Repository
public class ChangeProposalRepository {

    public static final String KIND_LITERAL = "literal";

    public static final String PENDING = "pending";
    public static final String APPROVED = "approved";
    public static final String REJECTED = "rejected";
    public static final String APPLIED = "applied";
    public static final String STALE = "stale";
    public static final String FAILED = "failed";

    /** 새로 만드는 수정안 — 상태는 항상 {@code pending}. */
    public record NewProposal(long jobId, String docId, String filename, String chunkId, String kind,
                              String beforeText, String afterText, String beforeHash,
                              String beforeContext, String afterContext, String beforeKeywords, String afterKeywords,
                              String rationale) {}

    public record Proposal(long id, long jobId, String docId, String filename, String chunkId, String kind,
                           String beforeText, String afterText, String beforeHash,
                           String beforeContext, String afterContext, String beforeKeywords, String afterKeywords,
                           String rationale, String status, String mdStatus, String reviewedBy, String reviewedAt,
                           String appliedAt, String createdAt) {}

    private static final RowMapper<Proposal> MAPPER = (rs, n) -> new Proposal(
            rs.getLong("id"), rs.getLong("job_id"), rs.getString("doc_id"), rs.getString("filename"), rs.getString("chunk_id"),
            rs.getString("kind"), rs.getString("before_text"), rs.getString("after_text"), rs.getString("before_hash"),
            rs.getString("before_context"), rs.getString("after_context"), rs.getString("before_keywords"), rs.getString("after_keywords"),
            rs.getString("rationale"), rs.getString("status"), rs.getString("md_status"), rs.getString("reviewed_by"),
            rs.getString("reviewed_at"), rs.getString("applied_at"), rs.getString("created_at"));

    private final JdbcTemplate jdbc;

    public ChangeProposalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertAll(List<NewProposal> proposals) {
        String now = DbTimestamp.now();
        for (NewProposal p : proposals) {
            jdbc.update("""
                    INSERT INTO change_proposal (job_id, doc_id, filename, chunk_id, kind, before_text, after_text, before_hash,
                        before_context, after_context, before_keywords, after_keywords, rationale, status, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, p.jobId(), p.docId(), p.filename(), p.chunkId(), p.kind(), p.beforeText(), p.afterText(), p.beforeHash(),
                    p.beforeContext(), p.afterContext(), p.beforeKeywords(), p.afterKeywords(), p.rationale(), PENDING, now);
        }
    }

    /** 한 작업의 수정안 — 문서·청크 순서(문서 단위로 보기 위해). */
    public List<Proposal> listByJob(long jobId) {
        return jdbc.query("SELECT * FROM change_proposal WHERE job_id = ? ORDER BY doc_id, id", MAPPER, jobId);
    }

    public java.util.Optional<Proposal> find(long id) {
        return jdbc.query("SELECT * FROM change_proposal WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    /**
     * 상태 전이 — <b>지금 상태가 {@code from} 일 때만</b> 바꾼다(compare-and-set). 두 관리자가 같은 수정안을 동시에 승인·거절하거나
     * 이미 적용된 것을 되돌리는 일을 막는다.
     *
     * @return 바뀌었으면 {@code true}
     */
    public boolean transition(long id, String from, String to, String reviewer) {
        return jdbc.update("""
                UPDATE change_proposal SET status = ?, reviewed_by = ?, reviewed_at = ?
                WHERE id = ? AND status = ?
                """, to, reviewer, DbTimestamp.now(), id, from) > 0;
    }

    public int countByStatus(long jobId, String status) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM change_proposal WHERE job_id = ? AND status = ?",
                Integer.class, jobId, status);
        return n == null ? 0 : n;
    }
}
