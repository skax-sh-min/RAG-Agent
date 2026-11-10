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

    public static final String RUNNING = "running";
    public static final String DONE = "done";
    public static final String FAILED = "failed";
    public static final String INTERRUPTED = "interrupted";

    public record Job(long id, String kind, String requestText, String requestedBy, String status,
                      String summaryJson, String error, String createdAt, String updatedAt) {}

    private static final RowMapper<Job> MAPPER = (rs, n) -> new Job(
            rs.getLong("id"), rs.getString("kind"), rs.getString("request_text"), rs.getString("requested_by"),
            rs.getString("status"), rs.getString("summary_json"), rs.getString("error"),
            rs.getString("created_at"), rs.getString("updated_at"));

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

    /** 이 사용자가 진행 중인 작업 수 — 사용자별 동시 1건 제한이 읽는다. */
    public int countRunning(String userId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM change_job WHERE requested_by = ? AND status = ?", Integer.class, userId, RUNNING);
        return n == null ? 0 : n;
    }

    /** 기동 때 한 번 — 앱이 죽어 {@code running} 으로 남은 작업을 {@code interrupted} 로 표시한다. */
    public int markRunningInterrupted() {
        return jdbc.update("UPDATE change_job SET status = ?, updated_at = ? WHERE status = ?",
                INTERRUPTED, DbTimestamp.now(), RUNNING);
    }
}
