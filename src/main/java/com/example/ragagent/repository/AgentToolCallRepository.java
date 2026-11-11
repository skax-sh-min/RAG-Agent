package com.example.ragagent.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Tool 선택·실행 관측({@code agent_tool_call}, Flyway V14) — {@code /admin} 의 Tool 호출 추적 패널이 읽는다(§6.33 5단계).
 * <b>기록은 절대 턴을 실패시키지 않는다</b>: 어떤 예외도 삼키고 로그만 남긴다. 질문 본문·인자는 저장하지 않는다(관측은 분포와 지연이면 된다).
 */
@Repository
public class AgentToolCallRepository {

    private static final Logger log = LoggerFactory.getLogger(AgentToolCallRepository.class);

    public static final String PLAN = "plan";
    public static final String EXEC = "exec";
    public static final String NONE = "NONE";
    // 계획 상태
    public static final String PLANNED = "planned";
    public static final String NO_TOOL = "none";
    public static final String INVALID = "invalid";
    public static final String REJECTED = "rejected";
    public static final String ERROR = "error";
    // 실행 상태
    public static final String OK = "ok";
    public static final String EMPTY = "empty";
    /** 보존 기간(일) — 기동 때 이보다 오래된 행을 지운다. */
    static final int RETENTION_DAYS = 90;

    /** 선택 한 줄 요약: 이 Tool 이 계획으로 몇 번 고르졌고 실행은 몇 번 어떻게 끝났는가. */
    public record ToolRow(String tool, int planned, int execs, int execOk, int execEmpty, int execError,
                          Long avgExecMs, Long maxExecMs) {}

    /** 기간 전체의 계획 호출 요약. */
    public record Summary(int plans, int planned, int none, int invalid, int rejected, int error,
                          Long avgPlanMs, Long maxPlanMs, List<ToolRow> tools) {
        /** 폴백(검색으로 내려간) 비율 — 계획 호출 중 Tool 이 정해지지 않은 것. 0 건이면 NaN 이 아니라 0. */
        public double fallbackRatio() {
            return plans == 0 ? 0.0 : (plans - planned) / (double) plans;
        }
    }

    private final JdbcTemplate jdbc;

    public AgentToolCallRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 기동이 끝나면 한 번 — 보존 기간이 지난 행을 지운다. */
    @EventListener(ApplicationReadyEvent.class)
    void purgeOld() {
        try {
            String cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(RETENTION_DAYS).format(DbTimestamp.FORMAT);
            int n = jdbc.update("DELETE FROM agent_tool_call WHERE created_at < ?", cutoff);
            if (n > 0) log.info("[AGENT-TOOL] 보존 기간({}일)이 지난 관측 {}건을 지웠다", RETENTION_DAYS, n);
        } catch (Exception e) {
            log.warn("[AGENT-TOOL] 오래된 관측 정리 실패: {}", e.getMessage());
        }
    }

    /** 한 줄을 남긴다 — 실패해도 호출자에게 영향이 없다. */
    public void record(String threadId, String userId, String phase, String tool, String status, Integer resultSize, Long durationMs) {
        try {
            jdbc.update("""
                    INSERT INTO agent_tool_call (thread_id, user_id, phase, tool_name, status, result_size, duration_ms, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, threadId, userId, phase, tool == null || tool.isBlank() ? NONE : tool, status, resultSize, durationMs,
                    DbTimestamp.now());
        } catch (Exception e) {
            log.debug("[AGENT-TOOL] 관측 기록 실패: {}", e.getMessage());
        }
    }

    /** 최근 {@code days} 일의 요약. */
    public Summary summary(int days) {
        String since = LocalDateTime.now(ZoneOffset.UTC).minusDays(Math.max(1, days)).format(DbTimestamp.FORMAT);
        int[] c = new int[6];   // plans, planned, none, invalid, rejected, error
        Long[] planMs = new Long[2];
        jdbc.query("""
                SELECT COUNT(*), COALESCE(SUM(status='planned'),0), COALESCE(SUM(status='none'),0),
                       COALESCE(SUM(status='invalid'),0), COALESCE(SUM(status='rejected'),0), COALESCE(SUM(status='error'),0),
                       CAST(AVG(duration_ms) AS INTEGER), MAX(duration_ms)
                FROM agent_tool_call WHERE phase = 'plan' AND created_at >= ?
                """, rs -> {
            for (int i = 0; i < 6; i++) c[i] = rs.getInt(i + 1);
            long avg = rs.getLong(7);
            planMs[0] = rs.wasNull() ? null : avg;
            long max = rs.getLong(8);
            planMs[1] = rs.wasNull() ? null : max;
        }, since);

        List<ToolRow> tools = new ArrayList<>(jdbc.query("""
                SELECT tool_name,
                       SUM(phase='plan' AND status='planned') AS planned,
                       SUM(phase='exec') AS execs,
                       SUM(phase='exec' AND status='ok') AS ok,
                       SUM(phase='exec' AND status='empty') AS empty,
                       SUM(phase='exec' AND status='error') AS err,
                       CAST(AVG(CASE WHEN phase='exec' THEN duration_ms END) AS INTEGER) AS avg_ms,
                       MAX(CASE WHEN phase='exec' THEN duration_ms END) AS max_ms
                FROM agent_tool_call
                WHERE created_at >= ? AND tool_name <> 'NONE'
                GROUP BY tool_name
                ORDER BY planned DESC, tool_name
                """, (rs, n) -> {
            long avg = rs.getLong("avg_ms");
            Long avgMs = rs.wasNull() ? null : avg;
            long max = rs.getLong("max_ms");
            Long maxMs = rs.wasNull() ? null : max;
            return new ToolRow(rs.getString("tool_name"), rs.getInt("planned"), rs.getInt("execs"), rs.getInt("ok"),
                    rs.getInt("empty"), rs.getInt("err"), avgMs, maxMs);
        }, since));
        return new Summary(c[0], c[1], c[2], c[3], c[4], c[5], planMs[0], planMs[1], List.copyOf(tools));
    }
}
