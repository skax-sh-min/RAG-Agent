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

    @Test
    @DisplayName("관리자 고치기 — pending 일 때만, 처음 기계 수정안은 한 번만 남는다(V16)")
    void editKeepsTheMachineProposalOnce() {
        repo.insertAll(List.of(proposal(1, "d1", "c1")));
        long id = repo.listByJob(1).get(0).id();

        assertThat(repo.editAfterText(id, "Oracle 을 사용", "admin")).isTrue();
        assertThat(repo.editAfterText(id, "Oracle 을 사용한다", "admin2")).isTrue();

        ChangeProposalRepository.Proposal p = repo.find(id).orElseThrow();
        assertThat(p.afterText()).isEqualTo("Oracle 을 사용한다");
        assertThat(p.machineAfterText()).isEqualTo("Oracle 사용");
        assertThat(p.edited()).isTrue();
        assertThat(p.editedBy()).isEqualTo("admin2");
        assertThat(p.editedAt()).isNotBlank();

        repo.transition(id, ChangeProposalRepository.PENDING, ChangeProposalRepository.APPROVED, "admin");
        assertThat(repo.editAfterText(id, "늦은 편집", "admin")).isFalse();
        assertThat(repo.find(id).orElseThrow().afterText()).isEqualTo("Oracle 을 사용한다");
    }

    @Test
    @DisplayName("빼기 — 겹침 짝(group_key)은 함께, 다른 작업의 같은 키와 이미 처리된 것은 그대로")
    void withdrawTakesTheOverlapPairTogether() {
        repo.insertAll(List.of(
                new ChangeProposalRepository.NewProposal(1, "d1", "d1.pdf", "c1", ChangeProposalRepository.KIND_EDIT,
                        "a", "b", "h", null, null, null, null, "편집", "[]", "describes", "인용", "g1"),
                new ChangeProposalRepository.NewProposal(1, "d1", "d1.pdf", "c2", ChangeProposalRepository.KIND_EDIT,
                        "a", "b", "h", null, null, null, null, "편집", "[]", null, null, "g1"),
                new ChangeProposalRepository.NewProposal(2, "d1", "d1.pdf", "c3", ChangeProposalRepository.KIND_EDIT,
                        "a", "b", "h", null, null, null, null, "편집", "[]", null, null, "g1"),
                proposal(1, "d1", "c4")));
        List<ChangeProposalRepository.Proposal> job1 = repo.listByJob(1);
        assertThat(job1.get(0).targetRole()).isEqualTo("describes");
        assertThat(job1.get(0).evidenceQuote()).isEqualTo("인용");
        assertThat(job1.get(0).opsJson()).isEqualTo("[]");

        assertThat(repo.withdraw(job1.get(0).id(), "admin")).isEqualTo(2);
        assertThat(repo.listByJob(1)).extracting(ChangeProposalRepository.Proposal::status)
                .containsExactly(ChangeProposalRepository.WITHDRAWN, ChangeProposalRepository.WITHDRAWN, ChangeProposalRepository.PENDING);
        assertThat(repo.listByJob(2).get(0).status()).isEqualTo(ChangeProposalRepository.PENDING);
        assertThat(repo.withdraw(job1.get(0).id(), "admin")).isZero();   // 이미 뺐다
    }
}
