package com.example.ragagent.repository;

import com.example.ragagent.SqliteTestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code change_job}(Flyway V12) — 앱과 같은 마이그레이션을 적용한 DB 로 상태 전이를 고정한다. */
class ChangeJobRepositoryTest {

    @TempDir Path tmp;
    private ChangeJobRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        JdbcTemplate jdbc = SqliteTestDatabase.open(tmp.resolve("t.db"));
        repo = new ChangeJobRepository(jdbc);
    }

    @Test
    @DisplayName("만들면 running 이고, 끝내면 리포트와 함께 done 으로 바뀐다")
    void createThenFinish() {
        long id = repo.create(ChangeJobRepository.KIND_IMPACT, "ABC API", "u1");
        assertThat(repo.find(id).orElseThrow().status()).isEqualTo(ChangeJobRepository.RUNNING);
        assertThat(repo.countRunning("u1")).isEqualTo(1);

        assertThat(repo.finish(id, ChangeJobRepository.DONE, "{\"mentions\":3}", null)).isTrue();

        ChangeJobRepository.Job job = repo.find(id).orElseThrow();
        assertThat(job.status()).isEqualTo(ChangeJobRepository.DONE);
        assertThat(job.summaryJson()).isEqualTo("{\"mentions\":3}");
        assertThat(repo.countRunning("u1")).isZero();
    }

    @Test
    @DisplayName("이미 끝난(또는 interrupted 된) 작업은 다시 바꾸지 않는다")
    void finishOnlyTouchesRunningJobs() {
        long id = repo.create(ChangeJobRepository.KIND_IMPACT, "x", "u1");
        assertThat(repo.markRunningInterrupted()).isEqualTo(1);

        assertThat(repo.finish(id, ChangeJobRepository.DONE, "{}", null)).isFalse();
        assertThat(repo.find(id).orElseThrow().status()).isEqualTo(ChangeJobRepository.INTERRUPTED);
    }

    @Test
    @DisplayName("진행 중 수는 사용자별이고, 최근 작업은 본인 것만 최신순이다")
    void perUserViews() {
        long a1 = repo.create(ChangeJobRepository.KIND_IMPACT, "a1", "alice");
        long a2 = repo.create(ChangeJobRepository.KIND_IMPACT, "a2", "alice");
        repo.create(ChangeJobRepository.KIND_IMPACT, "b1", "bob");

        assertThat(repo.countRunning("alice")).isEqualTo(2);
        assertThat(repo.countRunning("bob")).isEqualTo(1);
        assertThat(repo.recentFor("alice", 10)).extracting(ChangeJobRepository.Job::id).containsExactly(a2, a1);
    }
}
