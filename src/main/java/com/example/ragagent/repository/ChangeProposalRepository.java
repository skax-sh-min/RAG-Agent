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
    /** LLM 이 치환 결과를 한 번 더 고친 수정안 — 항상 "검토 필요". 변환 MD 에는 되쓰지 못한다. */
    public static final String KIND_SEMANTIC = "semantic";
    /** 원문 인용에 고정된 편집(자연어 지식 수정, AGENT_MCP_DESIGN §10.4) — 사용 중단 안내·주의 사항·구간 지우기·설명 수정. */
    public static final String KIND_EDIT = "edit";
    /** 청크를 통째로 지우는 수정안 — {@code after_text} 는 빈 문자열이다(§10.8). */
    public static final String KIND_DELETE_CHUNK = "delete_chunk";

    public static final String PENDING = "pending";
    public static final String APPROVED = "approved";
    public static final String REJECTED = "rejected";
    public static final String APPLIED = "applied";
    public static final String STALE = "stale";
    public static final String FAILED = "failed";
    /** 관리자가 승인 전에 뺀 수정안 — 코퍼스는 그대로다. 겹침 짝({@code group_key})은 함께 빠진다. */
    public static final String WITHDRAWN = "withdrawn";

    /**
     * 새로 만드는 수정안 — 상태는 항상 {@code pending}.
     *
     * @param opsJson       앵커 편집 목록(표시·감사용, 없으면 {@code null}) — 적용과 MD 되쓰기는 before/after 로 한다
     * @param targetRole    판정 역할({@code describes}·{@code mentions}) 또는 {@code null}
     * @param evidenceQuote 판정 근거 인용 또는 {@code null}
     * @param groupKey      겹침 짝 — 같은 값의 수정안은 함께 빠지고 함께 {@code stale} 이 된다. 짝이 없으면 {@code null}
     */
    public record NewProposal(long jobId, String docId, String filename, String chunkId, String kind,
                              String beforeText, String afterText, String beforeHash,
                              String beforeContext, String afterContext, String beforeKeywords, String afterKeywords,
                              String rationale, String opsJson, String targetRole, String evidenceQuote, String groupKey) {
        /** 리터럴·의미 변경 수정안(V16 이전의 모양) — 새 칸은 모두 비어 있다. */
        public NewProposal(long jobId, String docId, String filename, String chunkId, String kind,
                           String beforeText, String afterText, String beforeHash,
                           String beforeContext, String afterContext, String beforeKeywords, String afterKeywords,
                           String rationale) {
            this(jobId, docId, filename, chunkId, kind, beforeText, afterText, beforeHash, beforeContext, afterContext,
                    beforeKeywords, afterKeywords, rationale, null, null, null, null);
        }
    }

    /**
     * @param machineAfterText 관리자가 고치기 전의 기계 수정안 — {@code null} 이면 고친 적이 없다({@link #edited()})
     */
    public record Proposal(long id, long jobId, String docId, String filename, String chunkId, String kind,
                           String beforeText, String afterText, String beforeHash,
                           String beforeContext, String afterContext, String beforeKeywords, String afterKeywords,
                           String rationale, String status, String mdStatus, String reviewedBy, String reviewedAt,
                           String appliedAt, String createdAt,
                           String opsJson, String targetRole, String evidenceQuote, String groupKey,
                           String machineAfterText, String editedBy, String editedAt) {
        /** V16 이전의 모양 — 새 칸은 모두 비어 있다(테스트·옛 호출부용). */
        public Proposal(long id, long jobId, String docId, String filename, String chunkId, String kind,
                        String beforeText, String afterText, String beforeHash,
                        String beforeContext, String afterContext, String beforeKeywords, String afterKeywords,
                        String rationale, String status, String mdStatus, String reviewedBy, String reviewedAt,
                        String appliedAt, String createdAt) {
            this(id, jobId, docId, filename, chunkId, kind, beforeText, afterText, beforeHash, beforeContext, afterContext,
                    beforeKeywords, afterKeywords, rationale, status, mdStatus, reviewedBy, reviewedAt, appliedAt, createdAt,
                    null, null, null, null, null, null, null);
        }

        /** 관리자가 본문을 고쳤는가. */
        public boolean edited() {
            return machineAfterText != null;
        }
    }

    private static final RowMapper<Proposal> MAPPER = (rs, n) -> new Proposal(
            rs.getLong("id"), rs.getLong("job_id"), rs.getString("doc_id"), rs.getString("filename"), rs.getString("chunk_id"),
            rs.getString("kind"), rs.getString("before_text"), rs.getString("after_text"), rs.getString("before_hash"),
            rs.getString("before_context"), rs.getString("after_context"), rs.getString("before_keywords"), rs.getString("after_keywords"),
            rs.getString("rationale"), rs.getString("status"), rs.getString("md_status"), rs.getString("reviewed_by"),
            rs.getString("reviewed_at"), rs.getString("applied_at"), rs.getString("created_at"),
            rs.getString("ops_json"), rs.getString("target_role"), rs.getString("evidence_quote"), rs.getString("group_key"),
            rs.getString("machine_after_text"), rs.getString("edited_by"), rs.getString("edited_at"));

    private final JdbcTemplate jdbc;

    public ChangeProposalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertAll(List<NewProposal> proposals) {
        String now = DbTimestamp.now();
        for (NewProposal p : proposals) {
            jdbc.update("""
                    INSERT INTO change_proposal (job_id, doc_id, filename, chunk_id, kind, before_text, after_text, before_hash,
                        before_context, after_context, before_keywords, after_keywords, rationale, status, created_at,
                        ops_json, target_role, evidence_quote, group_key)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, p.jobId(), p.docId(), p.filename(), p.chunkId(), p.kind(), p.beforeText(), p.afterText(), p.beforeHash(),
                    p.beforeContext(), p.afterContext(), p.beforeKeywords(), p.afterKeywords(), p.rationale(), PENDING, now,
                    p.opsJson(), p.targetRole(), p.evidenceQuote(), p.groupKey());
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

    /**
     * 백업 이름이 없는 작업의 {@code approved} 수정안을 {@code pending} 으로 되돌린다 — 기동 때만 부른다. 작업당 사본은 첫 [확인] 뒤·적용 전에 뜨고
     * 이름은 그 뒤에 쓰므로, 그 사본으로 복원한 DB 에서는 "승인됨 + 백업 이름 없음"이 된다. 그대로 두면 [다시 시도] 한 번에 되돌린 작업이 다시
     * 적용된다(2026-11-23 리허설). 백업이 실패해 남은 승인도 같은 모양이라 함께 풀린다(다시 확인하면 된다).
     *
     * @return 되돌린 수정안의 (작업, 수정안) id
     */
    public List<long[]> releaseApprovedWithoutBackup() {
        List<long[]> ids = jdbc.query("""
                SELECT p.job_id, p.id FROM change_proposal p JOIN change_job j ON j.id = p.job_id
                WHERE p.status = 'approved' AND j.backup_name IS NULL ORDER BY p.job_id, p.id
                """, (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)});
        for (long[] id : ids) {
            jdbc.update("UPDATE change_proposal SET status = 'pending', reviewed_by = NULL, reviewed_at = NULL WHERE id = ? AND status = 'approved'", id[1]);
        }
        return ids;
    }

    /** 한 문서의 수정안을 상태별로 — 승인·적용이 문서 단위이므로 이 단위로 읽는다. */
    public List<Proposal> listByJobDocStatus(long jobId, String docId, String status) {
        return jdbc.query("SELECT * FROM change_proposal WHERE job_id = ? AND doc_id = ? AND status = ? ORDER BY id",
                MAPPER, jobId, docId, status);
    }

    /**
     * 한 문서의 수정안을 <b>모두</b> {@code from} → {@code to} 로 옮긴다(승인·거절의 문서 단위). 현재 상태가 {@code from} 인 것만 바뀌고(CAS),
     * 바뀐 수를 돌려준다.
     */
    public int transitionDocument(long jobId, String docId, String from, String to, String reviewer) {
        return jdbc.update("""
                UPDATE change_proposal SET status = ?, reviewed_by = ?, reviewed_at = ?
                WHERE job_id = ? AND doc_id = ? AND status = ?
                """, to, reviewer, DbTimestamp.now(), jobId, docId, from);
    }

    /**
     * 적용 결과를 남긴다 — 지금 상태가 {@code approved} 일 때만(compare-and-set). {@code applied} 이면 적용 시각도 찍는다.
     */
    public boolean finishApply(long id, String status, String mdStatus) {
        String appliedAt = APPLIED.equals(status) ? DbTimestamp.now() : null;
        return jdbc.update("UPDATE change_proposal SET status = ?, md_status = ?, applied_at = ? WHERE id = ? AND status = ?",
                status, mdStatus, appliedAt, id, APPROVED) > 0;
    }

    /** 이미 적용된 수정안의 MD 되쓰기 결과 — 문서의 MD 되쓰기는 청크를 다 적용한 뒤 한 번 일어난다. */
    public int setMdStatus(long jobId, String docId, String mdStatus) {
        return jdbc.update("UPDATE change_proposal SET md_status = ? WHERE job_id = ? AND doc_id = ? AND status = ?",
                mdStatus, jobId, docId, APPLIED);
    }

    /**
     * 관리자가 고친 본문으로 바꾼다 — <b>지금 상태가 {@code pending} 일 때만</b>(compare-and-set). 처음 고칠 때의 기계 수정안을
     * {@code machine_after_text} 에 한 번만 남긴다(두 번째부터는 그대로 — 기계가 무엇을 냈는지가 감사 기록이다).
     *
     * @return 바뀌었으면 {@code true} — 이미 승인·거절·적용됐거나 없는 수정안이면 {@code false}
     */
    public boolean editAfterText(long id, String newAfterText, String editor) {
        return jdbc.update("""
                UPDATE change_proposal
                SET machine_after_text = COALESCE(machine_after_text, after_text), after_text = ?, edited_by = ?, edited_at = ?
                WHERE id = ? AND status = ?
                """, newAfterText, editor, DbTimestamp.now(), id, PENDING) > 0;
    }

    /**
     * 맥락 요약·키워드의 수정 전·후를 바꾼다 — 관리자가 고친 본문에 맞춰 다시 만들 때(AGENT_MCP_DESIGN §10.7). {@code pending} 일 때만(compare-and-set).
     */
    public boolean updateContext(long id, String beforeContext, String afterContext, String beforeKeywords, String afterKeywords) {
        return jdbc.update("""
                UPDATE change_proposal SET before_context = ?, after_context = ?, before_keywords = ?, after_keywords = ?
                WHERE id = ? AND status = ?
                """, beforeContext, afterContext, beforeKeywords, afterKeywords, id, PENDING) > 0;
    }

    /**
     * 수정안을 뺀다({@code pending} → {@code withdrawn}). 겹침 짝({@code group_key})이 있으면 같은 작업의 짝도 함께 뺀다 — 한쪽만 적용되면
     * 겹침 구간이 어긋난다.
     *
     * @return 뺀 수정안 수(짝 포함). 0 이면 이미 처리됐거나 없는 수정안
     */
    public int withdraw(long id, String reviewer) {
        java.util.Optional<Proposal> p = find(id);
        if (p.isEmpty() || !PENDING.equals(p.get().status())) return 0;
        String now = DbTimestamp.now();
        if (p.get().groupKey() == null) {
            return jdbc.update("UPDATE change_proposal SET status = ?, reviewed_by = ?, reviewed_at = ? WHERE id = ? AND status = ?",
                    WITHDRAWN, reviewer, now, id, PENDING);
        }
        return jdbc.update("""
                UPDATE change_proposal SET status = ?, reviewed_by = ?, reviewed_at = ?
                WHERE job_id = ? AND group_key = ? AND status = ?
                """, WITHDRAWN, reviewer, now, p.get().jobId(), p.get().groupKey(), PENDING);
    }

    // ── 검토 화면(6e, AGENT_MCP_DESIGN §10.15) ─────────────────────────────

    /** 수정안 하나의 MD 되쓰기 결과 — 검토 화면은 청크 하나씩 적용하므로 (작업, 문서)의 행 전부가 아니라 그 행에만 쓴다. */
    public boolean setMdStatusFor(long id, String mdStatus) {
        return jdbc.update("UPDATE change_proposal SET md_status = ? WHERE id = ? AND status = ?", mdStatus, id, APPLIED) > 0;
    }

    /**
     * 관리자가 고친 본문으로 바꾼다 — {@link #editAfterText} 와 같고, <b>지금 본문이 {@code expectedAfterText} 일 때만</b>(동시 편집의
     * compare-and-set). 다른 관리자가 그사이 고쳤으면 {@code false}.
     */
    public boolean editAfterTextIfUnchanged(long id, String expectedAfterText, String newAfterText, String editor) {
        return jdbc.update("""
                UPDATE change_proposal
                SET machine_after_text = COALESCE(machine_after_text, after_text), after_text = ?, edited_by = ?, edited_at = ?
                WHERE id = ? AND status = ? AND after_text = ?
                """, newAfterText, editor, DbTimestamp.now(), id, PENDING, expectedAfterText) > 0;
    }

    /** 기계 수정안으로 되돌린다 — {@code pending} 이고 고친 적이 있을 때만. 배지("관리자가 고침")도 사라진다. */
    public boolean restoreMachineText(long id) {
        return jdbc.update("""
                UPDATE change_proposal SET after_text = machine_after_text, machine_after_text = NULL, edited_by = NULL, edited_at = NULL
                WHERE id = ? AND status = ? AND machine_after_text IS NOT NULL
                """, id, PENDING) > 0;
    }

    /** 겹침 짝 전체를 {@code from} → {@code to}(CAS). @return 바뀐 수 */
    public int transitionGroup(long jobId, String groupKey, String from, String to, String reviewer) {
        return jdbc.update("""
                UPDATE change_proposal SET status = ?, reviewed_by = ?, reviewed_at = ?
                WHERE job_id = ? AND group_key = ? AND status = ?
                """, to, reviewer, DbTimestamp.now(), jobId, groupKey, from);
    }

    /** 한 작업의 수정안을 상태별로 — 승인한 순서(검토 화면의 순차 실행기가 확인한 순서대로 적용한다). */
    public List<Proposal> listByJobStatus(long jobId, String status) {
        return jdbc.query("SELECT * FROM change_proposal WHERE job_id = ? AND status = ? ORDER BY reviewed_at, id",
                MAPPER, jobId, status);
    }

    /** 적용 때 실제로 실은 맥락·키워드 — 승인된 수정안에만(검토 화면에서 확인한 뒤 확정 본문으로 다시 만든 값, R3). */
    public boolean setAppliedContext(long id, String afterContext, String afterKeywords) {
        return jdbc.update("UPDATE change_proposal SET after_context = ?, after_keywords = ? WHERE id = ? AND status = ?",
                afterContext, afterKeywords, id, APPROVED) > 0;
    }

    public int countByStatus(long jobId, String status) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM change_proposal WHERE job_id = ? AND status = ?",
                Integer.class, jobId, status);
        return n == null ? 0 : n;
    }
}
