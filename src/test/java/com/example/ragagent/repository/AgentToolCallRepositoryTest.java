package com.example.ragagent.repository;

import com.example.ragagent.SqliteTestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code agent_tool_call}(Flyway V14) — 앱과 같은 마이그레이션을 적용한 DB 로 집계를 고정한다. */
class AgentToolCallRepositoryTest {

    @TempDir Path tmp;
    private JdbcTemplate jdbc;
    private AgentToolCallRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = SqliteTestDatabase.open(tmp.resolve("t.db"));
        repo = new AgentToolCallRepository(jdbc);
    }

    @Test
    @DisplayName("계획 호출을 센다 — 폴백 비율은 Tool 이 정해지지 않은 호출의 비율이다")
    void summarizesPlans() {
        repo.record("t1", "u1", AgentToolCallRepository.PLAN, "queryCatalog", AgentToolCallRepository.PLANNED, null, 800L);
        repo.record("t1", "u1", AgentToolCallRepository.PLAN, "analyzeImpact", AgentToolCallRepository.PLANNED, null, 1000L);
        repo.record("t2", "u1", AgentToolCallRepository.PLAN, null, AgentToolCallRepository.NO_TOOL, null, 600L);
        repo.record("t3", "u1", AgentToolCallRepository.PLAN, null, AgentToolCallRepository.INVALID, null, 900L);

        AgentToolCallRepository.Summary s = repo.summary(14);

        assertThat(s.plans()).isEqualTo(4);
        assertThat(s.planned()).isEqualTo(2);
        assertThat(s.none()).isEqualTo(1);
        assertThat(s.invalid()).isEqualTo(1);
        assertThat(s.fallbackRatio()).isEqualTo(0.5);
        assertThat(s.avgPlanMs()).isEqualTo(825L);
        assertThat(s.maxPlanMs()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("Tool 별로 선택 수와 실행 결과(성공·빈 결과·실패)·지연을 합친다 — NONE 줄은 만들지 않는다")
    void summarizesPerTool() {
        repo.record("t1", "u1", AgentToolCallRepository.PLAN, "queryCatalog", AgentToolCallRepository.PLANNED, null, 800L);
        repo.record("t1", "u1", AgentToolCallRepository.EXEC, "queryCatalog", AgentToolCallRepository.OK, 120, 40L);
        repo.record("t2", "u1", AgentToolCallRepository.PLAN, "queryCatalog", AgentToolCallRepository.PLANNED, null, 700L);
        repo.record("t2", "u1", AgentToolCallRepository.EXEC, "queryCatalog", AgentToolCallRepository.EMPTY, 0, 20L);
        repo.record("t3", "u1", AgentToolCallRepository.PLAN, "queryCatalog", AgentToolCallRepository.PLANNED, null, 900L);
        repo.record("t3", "u1", AgentToolCallRepository.EXEC, "queryCatalog", AgentToolCallRepository.ERROR, null, 60L);
        repo.record("t4", "u1", AgentToolCallRepository.PLAN, null, AgentToolCallRepository.NO_TOOL, null, 500L);

        AgentToolCallRepository.Summary s = repo.summary(14);

        assertThat(s.tools()).hasSize(1);
        AgentToolCallRepository.ToolRow row = s.tools().get(0);
        assertThat(row.tool()).isEqualTo("queryCatalog");
        assertThat(row.planned()).isEqualTo(3);
        assertThat(row.execs()).isEqualTo(3);
        assertThat(row.execOk()).isEqualTo(1);
        assertThat(row.execEmpty()).isEqualTo(1);
        assertThat(row.execError()).isEqualTo(1);
        assertThat(row.avgExecMs()).isEqualTo(40L);
        assertThat(row.maxExecMs()).isEqualTo(60L);
    }

    @Test
    @DisplayName("기간 밖의 기록은 요약에서 빠진다 · 기록이 없으면 0 이고 폴백 비율도 0")
    void respectsThePeriod() {
        assertThat(repo.summary(14).plans()).isZero();
        assertThat(repo.summary(14).fallbackRatio()).isZero();

        jdbc.update("INSERT INTO agent_tool_call (phase, tool_name, status, created_at) VALUES ('plan','queryCatalog','planned','2000-01-01 00:00:00')");
        repo.record("t1", "u1", AgentToolCallRepository.PLAN, "queryCatalog", AgentToolCallRepository.PLANNED, null, 1L);

        assertThat(repo.summary(7).plans()).isEqualTo(1);
    }

    @Test
    @DisplayName("기동 때 보존 기간이 지난 행만 지운다")
    void purgesOnlyOldRows() {
        jdbc.update("INSERT INTO agent_tool_call (phase, tool_name, status, created_at) VALUES ('plan','queryCatalog','planned','2000-01-01 00:00:00')");
        repo.record("t1", "u1", AgentToolCallRepository.PLAN, "queryCatalog", AgentToolCallRepository.PLANNED, null, 1L);

        repo.purgeOld();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_tool_call", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("기록 실패는 호출자에게 던지지 않는다 — 관측이 턴을 실패시키면 안 된다")
    void recordNeverThrows() {
        jdbc.execute("DROP TABLE agent_tool_call");

        repo.record("t1", "u1", AgentToolCallRepository.PLAN, "x", AgentToolCallRepository.PLANNED, null, 1L);   // 예외 없음
    }
}
