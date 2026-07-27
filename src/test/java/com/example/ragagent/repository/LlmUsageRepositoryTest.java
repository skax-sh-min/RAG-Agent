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

    @Test
    @DisplayName("usedProviders — 기록 없으면 빈 Set")
    void usedProvidersEmptyWhenNoRecords() {
        assertThat(repo.usedProviders()).isEmpty();
    }

    @Test
    @DisplayName("usedProviders — record() 된 provider 이름만 포함")
    void usedProvidersReflectsRecordedProviders() {
        repo.record("openai", 10, 5);
        repo.record("claude", 1, 1);

        assertThat(repo.usedProviders()).containsExactlyInAnyOrder("openai", "claude");
        assertThat(repo.usedProviders()).doesNotContain("never-used");
    }

    @Test
    @DisplayName("deleteByProvider — 해당 provider 행만 제거하고 삭제 행수 반환")
    void deleteByProviderRemovesOnlyThatProvider() {
        repo.record("openai", 10, 5);
        repo.record("openai", 1, 1); // second day-independent call would collide on same day; still 1 row (UPSERT)
        repo.record("claude", 1, 1);

        int deleted = repo.deleteByProvider("openai");

        assertThat(deleted).isEqualTo(1); // UPSERT keeps one row per (provider, date)
        assertThat(repo.usedProviders()).containsExactly("claude");
        assertThat(repo.getDaily("openai").callCount()).isZero();
        assertThat(repo.getDaily("claude").callCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("deleteByProvider — 존재하지 않는 provider 는 0 반환, 예외 없음")
    void deleteByProviderNonexistentReturnsZero() {
        assertThat(repo.deleteByProvider("never-existed")).isZero();
    }

    // ── Prefix aggregation (BACKGROUND category merging) ────────────────────────

    @Test
    @DisplayName("getDailyByPrefix — 같은 prefix의 여러 provider(local/local-fast) 행을 합산한다")
    void getDailyByPrefixSumsAcrossMatchingProviders() {
        repo.record("title:local", 100, 50);
        repo.record("title:local-fast", 30, 20);
        repo.record("summary:local", 999, 999); // different prefix — must not leak in

        var daily = repo.getDailyByPrefix("title:");

        assertThat(daily.callCount()).isEqualTo(2);
        assertThat(daily.inputTokens()).isEqualTo(130);
        assertThat(daily.outputTokens()).isEqualTo(70);
    }

    @Test
    @DisplayName("getWeeklyByPrefix/getMonthlyByPrefix 도 오늘 기록을 포함해 합산한다")
    void weeklyAndMonthlyByPrefixIncludeToday() {
        repo.record("keyword:local", 1, 1);
        repo.record("keyword:local-fast", 1, 1);

        assertThat(repo.getWeeklyByPrefix("keyword:").callCount()).isEqualTo(2);
        assertThat(repo.getMonthlyByPrefix("keyword:").callCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("getByPeriodPrefix — 일치하는 provider가 없으면 0/0/0")
    void getByPeriodPrefixEmptyWhenNoMatch() {
        var d = repo.getDailyByPrefix("nonexistent:");
        assertThat(d.callCount()).isZero();
        assertThat(d.inputTokens()).isZero();
        assertThat(d.outputTokens()).isZero();
    }

    @Test
    @DisplayName("getDailyHistoryByPrefix — 같은 날짜의 여러 provider 기록을 날짜별로 합산한다")
    void getDailyHistoryByPrefixSumsPerDay() {
        repo.record("title:local", 100, 50);
        repo.record("title:local-fast", 30, 20);

        var history = repo.getDailyHistoryByPrefix("title:", 7);

        assertThat(history).hasSize(1); // both recorded today — one merged row
        assertThat(history.get(0).inputTokens()).isEqualTo(130);
        assertThat(history.get(0).outputTokens()).isEqualTo(70);
        assertThat(history.get(0).callCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("usedProviderNamesWithPrefix — prefix로 시작하는 이름만, 정확 일치 다른 provider는 제외")
    void usedProviderNamesWithPrefixFiltersCorrectly() {
        repo.record("title:local", 1, 1);
        repo.record("title:local-fast", 1, 1);
        repo.record("titleX:local", 1, 1); // shares no ':' boundary — should not match "title:" prefix... actually LIKE 'title:%' excludes this since it lacks the literal ':'
        repo.record("summary:local", 1, 1);

        assertThat(repo.usedProviderNamesWithPrefix("title:"))
                .containsExactlyInAnyOrder("title:local", "title:local-fast");
    }
}
