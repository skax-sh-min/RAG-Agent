package com.example.ragagent.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/**
 * 영향도 분석 작업({@code change_job}, Flyway V12)의 저장소. 코퍼스에는 아무것도 쓰지 않는다 — 작업 기록과 리포트만 저장한다.
 * 시각은 UTC({@link DbTimestamp}).
 */
@Repository
public class ChangeJobRepository {

    public static final String KIND_IMPACT = "impact";
    public static final String KIND_MAINTENANCE = "maintenance";

    public static final String RUNNING = "running";
    public static final String DONE = "done";
    public static final String FAILED = "failed";
    public static final String INTERRUPTED = "interrupted";

    /**
     * @param intentJson    자연어 지식 수정의 해석된 의도(V16) — 글자 치환 작업·옛 작업은 {@code null}
     * @param reportChunkId 청크 오류 신고에서 시작한 작업의 그 청크(V17, AGENT_MCP_DESIGN §10.12 6d) — 대상이 이 청크 하나로 고정되고, 이 청크의 수정안이
     *                      적용되면 열린 신고가 해결로 닫힌다. 그 밖의 작업은 {@code null}
     */
    public record Job(long id, String kind, String requestText, String requestedBy, String status,
                      String summaryJson, String error, String createdAt, String updatedAt, String intentJson,
                      String reportChunkId) {
        /** V17 이전의 모양 — 테스트·옛 호출부용. */
        public Job(long id, String kind, String requestText, String requestedBy, String status,
                   String summaryJson, String error, String createdAt, String updatedAt, String intentJson) {
            this(id, kind, requestText, requestedBy, status, summaryJson, error, createdAt, updatedAt, intentJson, null);
        }

        /** V16 이전의 모양 — 테스트·옛 호출부용. */
        public Job(long id, String kind, String requestText, String requestedBy, String status,
                   String summaryJson, String error, String createdAt, String updatedAt) {
            this(id, kind, requestText, requestedBy, status, summaryJson, error, createdAt, updatedAt, null, null);
        }

        /** 신고에서 시작한 작업인가. */
        public boolean fromReport() {
            return reportChunkId != null && !reportChunkId.isBlank();
        }
    }

    private static final RowMapper<Job> MAPPER = (rs, n) -> new Job(
            rs.getLong("id"), rs.getString("kind"), rs.getString("request_text"), rs.getString("requested_by"),
            rs.getString("status"), rs.getString("summary_json"), rs.getString("error"),
            rs.getString("created_at"), rs.getString("updated_at"), rs.getString("intent_json"),
            rs.getString("report_chunk_id"));

    private final JdbcTemplate jdbc;

    public ChangeJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 새 작업을 {@code running} 으로 만든다. */
    public long create(String kind, String requestText, String requestedBy) {
        String now = DbTimestamp.now();
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO change_job (kind, request_text, requested_by, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, kind);
            ps.setString(2, requestText);
            ps.setString(3, requestedBy);
            ps.setString(4, RUNNING);
            ps.setString(5, now);
            ps.setString(6, now);
            return ps;
        }, keys);
        Number id = keys.getKey();
        if (id == null) throw new IllegalStateException("change_job id 를 받지 못했습니다");
        return id.longValue();
    }

    /**
     * 끝난 상태({@code done})로 바로 만든다 — 수정안을 밖(MCP 외부 에이전트)에서 하나씩 받는 작업처럼 백그라운드 단계가 없는 작업용. 의도를 함께 남긴다.
     */
    public long createDone(String kind, String requestText, String requestedBy, String intentJson) {
        long id = create(kind, requestText, requestedBy);
        jdbc.update("UPDATE change_job SET status = ?, intent_json = ?, updated_at = ? WHERE id = ?",
                DONE, intentJson, DbTimestamp.now(), id);
        return id;
    }

    /**
     * 신고에서 시작한 작업을 만든다(V17, 6d) — {@link #create} 와 같고 그 청크를 함께 남긴다.
     */
    public long createForReport(String kind, String requestText, String requestedBy, String reportChunkId) {
        long id = create(kind, requestText, requestedBy);
        jdbc.update("UPDATE change_job SET report_chunk_id = ? WHERE id = ?", reportChunkId, id);
        return id;
    }

    /** 이 청크의 신고에서 시작한 최근 작업(최신순) — 신고 상세가 "이미 만든 작업"을 보인다. */
    public List<Job> recentForReportChunk(String chunkId, int limit) {
        return jdbc.query("SELECT * FROM change_job WHERE report_chunk_id = ? ORDER BY id DESC LIMIT ?", MAPPER, chunkId, limit);
    }

    /** 해석된 의도를 남긴다(V16) — 자연어 지식 수정 작업이 해석을 마친 뒤 한 번. */
    public void setIntent(long id, String intentJson) {
        jdbc.update("UPDATE change_job SET intent_json = ?, updated_at = ? WHERE id = ?", intentJson, DbTimestamp.now(), id);
    }

    /** 끝난 작업의 리포트를 저장한다 — {@code running} 인 작업만 바꾼다(재기동이 이미 {@code interrupted} 로 돌린 것을 되살리지 않는다). */
    public boolean finish(long id, String status, String summaryJson, String error) {
        return jdbc.update("""
                UPDATE change_job SET status = ?, summary_json = ?, error = ?, updated_at = ?
                WHERE id = ? AND status = ?
                """, status, summaryJson, error, DbTimestamp.now(), id, RUNNING) > 0;
    }

    public Optional<Job> find(long id) {
        List<Job> rows = jdbc.query("SELECT * FROM change_job WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 이 사용자의 최근 작업(최신순). */
    public List<Job> recentFor(String userId, int limit) {
        return jdbc.query("SELECT * FROM change_job WHERE requested_by = ? ORDER BY id DESC LIMIT ?", MAPPER, userId, limit);
    }

    /** 이 종류의 최근 작업(최신순) — 관리자 화면용. */
    public List<Job> recentByKind(String kind, int limit) {
        return jdbc.query("SELECT * FROM change_job WHERE kind = ? ORDER BY id DESC LIMIT ?", MAPPER, kind, limit);
    }

    /** 이 사용자가 진행 중인 작업 수 — 사용자별 동시 1건 제한이 읽는다. */
    public int countRunning(String userId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM change_job WHERE requested_by = ? AND status = ?", Integer.class, userId, RUNNING);
        return n == null ? 0 : n;
    }

    /**
     * 이 작업의 첫 코퍼스 쓰기 직전에 만든 DB 백업 파일 이름(V18, AGENT_MCP_DESIGN §10.15 R2) — 아직 쓰지 않았으면 {@code null}.
     * 레코드 컴포넌트로 두지 않은 이유: {@link Job} 은 테스트·호출부 여럿이 위치 기반으로 만들고, 이 값을 읽는 곳은 적용 서비스 하나뿐이다.
     */
    public String backupName(long id) {
        List<String> rows = jdbc.query("SELECT backup_name FROM change_job WHERE id = ?", (rs, n) -> rs.getString(1), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void setBackupName(long id, String name) {
        jdbc.update("UPDATE change_job SET backup_name = ? WHERE id = ?", name, id);
    }

    /** 기동 때 한 번 — 앱이 죽어 {@code running} 으로 남은 작업을 {@code interrupted} 로 표시한다. */
    public int markRunningInterrupted() {
        return jdbc.update("UPDATE change_job SET status = ?, updated_at = ? WHERE status = ?",
                INTERRUPTED, DbTimestamp.now(), RUNNING);
    }
}
