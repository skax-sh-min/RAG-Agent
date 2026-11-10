package com.example.ragagent.repository;

import com.example.ragagent.SqliteTestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code change_proposal}(Flyway V13) — 대기열 저장과 compare-and-set 상태 전이. */
class ChangeProposalRepositoryTest {

    @TempDir Path tmp;
    private ChangeProposalRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        repo = new ChangeProposalRepository(SqliteTestDatabase.open(tmp.resolve("t.db")));
    }

    private static ChangeProposalRepository.NewProposal proposal(long job, String doc, String chunk) {
        return new ChangeProposalRepository.NewProposal(job, doc, doc + ".pdf", chunk, ChangeProposalRepository.KIND_LITERAL,
                "PostgreSQL 사용", "Oracle 사용", "hash", "ctx", "ctx2", "kw", "kw2", "리터럴 치환");
    }

    @Test
    @DisplayName("저장하면 pending 이고, 작업별로 문서·id 순으로 읽힌다")
    void insertAndList() {
        repo.insertAll(List.of(proposal(1, "d2", "c3"), proposal(1, "d1", "c1"), proposal(1, "d1", "c2"), proposal(2, "d9", "c9")));

        List<ChangeProposalRepository.Proposal> list = repo.listByJob(1);

        assertThat(list).extracting(ChangeProposalRepository.Proposal::chunkId).containsExactly("c1", "c2", "c3");
        assertThat(list).allSatisfy(p -> assertThat(p.status()).isEqualTo(ChangeProposalRepository.PENDING));
        assertThat(list.get(0).afterContext()).isEqualTo("ctx2");
        assertThat(repo.countByStatus(1, ChangeProposalRepository.PENDING)).isEqualTo(3);
    }

    @Test
    @DisplayName("상태 전이는 지금 상태가 맞을 때만 — 두 번째 승인·이미 거절된 것의 승인은 실패한다")
    void transitionsAreCompareAndSet() {
        repo.insertAll(List.of(proposal(1, "d1", "c1")));
        long id = repo.listByJob(1).get(0).id();

        assertThat(repo.transition(id, ChangeProposalRepository.PENDING, ChangeProposalRepository.APPROVED, "admin")).isTrue();
        assertThat(repo.transition(id, ChangeProposalRepository.PENDING, ChangeProposalRepository.APPROVED, "admin2")).isFalse();
        assertThat(repo.transition(id, ChangeProposalRepository.PENDING, ChangeProposalRepository.REJECTED, "admin2")).isFalse();

        ChangeProposalRepository.Proposal p = repo.find(id).orElseThrow();
        assertThat(p.status()).isEqualTo(ChangeProposalRepository.APPROVED);
        assertThat(p.reviewedBy()).isEqualTo("admin");
        assertThat(p.reviewedAt()).isNotBlank();
    }
}
