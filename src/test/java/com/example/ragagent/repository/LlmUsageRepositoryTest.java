package com.example.ragagent.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA — LlmUsageRepository
 *
 * Verifies the daily UPSERT (call_count + tokens accumulate) and the period
 * aggregation queries used by the LLM usage dashboard.
 */
class LlmUsageRepositoryTest {

    private Path dbFile;
    private LlmUsageRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("rag-usage-", ".db");
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + dbFile);
        var jdbc = new JdbcTemplate(ds);
        repo = new LlmUsageRepository(jdbc);
        repo.init();
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(dbFile);
    }

    @Test
    @DisplayName("같은 (provider, date) 에 두 번 record → call_count=2, 토큰 누적")
    void recordAccumulates() {
        repo.record("openai", 100, 50);
        repo.record("openai", 30, 20);

        var daily = repo.getDaily("openai");
        assertThat(daily.callCount()).isEqualTo(2);
        assertThat(daily.inputTokens()).isEqualTo(130);
        assertThat(daily.outputTokens()).isEqualTo(70);
        assertThat(daily.totalTokens()).isEqualTo(200);
    }

    @Test
    @DisplayName("기록 없는 provider 의 getDaily 는 0 / 0 / 0")
    void emptyProviderReturnsZeros() {
        var d = repo.getDaily("nonexistent");
        assertThat(d.callCount()).isZero();
        assertThat(d.inputTokens()).isZero();
        assertThat(d.outputTokens()).isZero();
    }

    @Test
    @DisplayName("getWeekly / getMonthly 가 오늘 기록을 포함")
    void weeklyAndMonthlyIncludeToday() {
        repo.record("openai", 1, 1);
        assertThat(repo.getWeekly("openai").callCount()).isEqualTo(1);
        assertThat(repo.getMonthly("openai").callCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("getDailyHistory(30) 가 빈 데이터에서 빈 리스트 반환")
    void emptyDailyHistory() {
        assertThat(repo.getDailyHistory("nonexistent", 30)).isEmpty();
    }

    @Test
    @DisplayName("두 provider 가 독립적으로 집계")
    void multipleProvidersIsolated() {
        repo.record("openai", 100, 100);
        repo.record("claude", 200, 200);
        assertThat(repo.getDaily("openai").totalTokens()).isEqualTo(200);
        assertThat(repo.getDaily("claude").totalTokens()).isEqualTo(400);
    }
}
