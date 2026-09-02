package com.example.ragagent.repository;

import com.example.ragagent.repository.CuratedSubmissionRepository.Submission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA — 청크 추가 게시판 저장소.
 *
 * <p>Covers:
 *  - insert → pending, 작성자/상태별 조회
 *  - markApproved/markRejected 의 compare-and-set (pending 이 아니면 실패)
 *  - markWithdrawn 의 작성자 스코프
 *  - 알림 카운트(countPending / countUnreviewedNotificationsForAuthor)와 읽음 처리
 *  - displayStatus: 승인된 제안의 curated_qa 가 비활성이면 revoked 로 파생된다
 */
class CuratedSubmissionRepositoryTest {

    private Path dbFile;
    private JdbcTemplate jdbc;
    private CuratedSubmissionRepository repo;
    private CuratedQaRepository curatedRepo;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("rag-test-submission-", ".db");
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:sqlite:" + dbFile);
        jdbc = new JdbcTemplate(ds);
        repo = new CuratedSubmissionRepository(jdbc);
        repo.init();
        curatedRepo = new CuratedQaRepository(jdbc);   // LEFT JOIN 대상 테이블
        curatedRepo.init();
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(dbFile);
    }

    @Test
    @DisplayName("insert — pending 상태로 저장되고 작성자 목록에 나온다")
    void insert_createsPendingRow() {
        long id = repo.insert("u1", "제목", "본문", null);

        Submission row = repo.findById(id).orElseThrow();
        assertThat(row.status()).isEqualTo(CuratedSubmissionRepository.STATUS_PENDING);
        assertThat(row.isPending()).isTrue();
        assertThat(row.displayStatus()).isEqualTo("pending");
        assertThat(row.title()).isEqualTo("제목");
        assertThat(row.body()).isEqualTo("본문");
        assertThat(row.authorReadAt()).isNull();
        assertThat(repo.findByAuthor("u1", 0, 10)).extracting(Submission::id).containsExactly(id);
        assertThat(repo.countPending()).isEqualTo(1);
    }

    @Test
    @DisplayName("markApproved — pending 일 때만 성공하고, 관리자가 고친 본문이 저장된다")
    void markApproved_onlyFromPending() {
        long id = repo.insert("u1", "원래 제목", "원래 본문", null);
        long curatedId = curatedRepo.insertManual(id, "u1", "수정 제목", "수정 본문", null);

        assertThat(repo.markApproved(id, "admin", "수정 제목", "수정 본문", null, curatedId)).isTrue();
        // 두 번째 호출은 이미 approved 라 CAS 실패 — 중복 curated 행이 생기지 않는 근거
        assertThat(repo.markApproved(id, "admin", "또 수정", "또 수정", null, curatedId)).isFalse();

        Submission row = repo.findById(id).orElseThrow();
        assertThat(row.status()).isEqualTo(CuratedSubmissionRepository.STATUS_APPROVED);
        assertThat(row.title()).isEqualTo("수정 제목");
        assertThat(row.body()).isEqualTo("수정 본문");
        assertThat(row.curatedQaId()).isEqualTo(curatedId);
        assertThat(row.reviewerUserId()).isEqualTo("admin");
        assertThat(row.reviewedAt()).isNotNull();
        assertThat(row.displayStatus()).isEqualTo("approved");
        assertThat(repo.countPending()).isZero();
    }

    @Test
    @DisplayName("displayStatus — 승인 후 curated_qa 가 비활성화되면 revoked 로 보인다")
    void displayStatus_derivesRevokedFromCuratedRow() {
        long id = repo.insert("u1", "제목", "본문", null);
        long curatedId = curatedRepo.insertManual(id, "u1", "제목", "본문", null);
        repo.markApproved(id, "admin", "제목", "본문", null, curatedId);

        assertThat(repo.findById(id).orElseThrow().displayStatus()).isEqualTo("approved");

        curatedRepo.deactivateById(curatedId);   // 관리자가 큐레이션 탭에서 회수

        Submission row = repo.findById(id).orElseThrow();
        assertThat(row.status()).isEqualTo("approved");           // 저장된 값은 그대로
        assertThat(row.displayStatus()).isEqualTo("revoked");     // 화면에는 회수됨
        assertThat(row.embedFailed()).isFalse();
    }

    @Test
    @DisplayName("embedFailed — 승인된 제안의 curated_qa 임베딩이 실패하면 true")
    void embedFailed_reflectsCuratedEmbedStatus() {
        long id = repo.insert("u1", "제목", "본문", null);
        long curatedId = curatedRepo.insertManual(id, "u1", "제목", "본문", null);
        repo.markApproved(id, "admin", "제목", "본문", null, curatedId);

        assertThat(repo.findById(id).orElseThrow().embedFailed()).isFalse();

        curatedRepo.markEmbedFailed(curatedId);
        assertThat(repo.findById(id).orElseThrow().embedFailed()).isTrue();

        curatedRepo.markEmbedOk(curatedId);
        assertThat(repo.findById(id).orElseThrow().embedFailed()).isFalse();
    }

    @Test
    @DisplayName("markRejected — 사유가 저장되고 pending 이 아니면 실패")
    void markRejected_storesNote() {
        long id = repo.insert("u1", "제목", "본문", null);

        assertThat(repo.markRejected(id, "admin", "출처가 불분명합니다")).isTrue();
        assertThat(repo.markRejected(id, "admin", "두 번째")).isFalse();

        Submission row = repo.findById(id).orElseThrow();
        assertThat(row.displayStatus()).isEqualTo("rejected");
        assertThat(row.reviewNote()).isEqualTo("출처가 불분명합니다");
    }

    @Test
    @DisplayName("markWithdrawn — 작성자 본인의 pending 제안만 철회된다")
    void markWithdrawn_scopedToAuthorAndPending() {
        long id = repo.insert("u1", "제목", "본문", null);

        assertThat(repo.markWithdrawn(id, "someone-else")).isFalse();
        assertThat(repo.markWithdrawn(id, "u1")).isTrue();
        assertThat(repo.markWithdrawn(id, "u1")).isFalse();   // 이미 withdrawn

        assertThat(repo.findById(id).orElseThrow().displayStatus()).isEqualTo("withdrawn");
    }

    @Test
    @DisplayName("알림 카운트 — 검토된 건만 세고, 목록을 열면(읽음 처리) 0이 된다")
    void unreadCount_countsReviewedOnlyAndClears() {
        long pending  = repo.insert("u1", "대기", "본문", null);
        long approved = repo.insert("u1", "승인", "본문", null);
        long rejected = repo.insert("u1", "반려", "본문", null);
        long other    = repo.insert("u2", "남의 글", "본문", null);
        repo.markRejected(other, "admin", "사유");

        // 검토 전에는 알림이 없다
        assertThat(repo.countUnreviewedNotificationsForAuthor("u1")).isZero();

        long curatedId = curatedRepo.insertManual(approved, "u1", "승인", "본문", null);
        repo.markApproved(approved, "admin", "승인", "본문", null, curatedId);
        repo.markRejected(rejected, "admin", "사유");

        assertThat(repo.countUnreviewedNotificationsForAuthor("u1")).isEqualTo(2);
        assertThat(repo.countUnreviewedNotificationsForAuthor("u2")).isEqualTo(1);  // 남의 알림과 분리

        repo.markAllReadForAuthor("u1");
        assertThat(repo.countUnreviewedNotificationsForAuthor("u1")).isZero();
        assertThat(repo.countUnreviewedNotificationsForAuthor("u2")).isEqualTo(1);
        // 아직 검토되지 않은 건은 읽음 스탬프가 찍히지 않는다 — 나중에 처리되면 다시 알림이 뜬다
        assertThat(repo.findById(pending).orElseThrow().authorReadAt()).isNull();
    }

    @Test
    @DisplayName("findByStatus — status 필터, null 이면 전체")
    void findByStatus_filtersOrReturnsAll() {
        long a = repo.insert("u1", "A", "본문", null);
        long b = repo.insert("u1", "B", "본문", null);
        repo.markRejected(b, "admin", "사유");

        assertThat(repo.findByStatus("pending", 0, 10)).extracting(Submission::id).containsExactly(a);
        assertThat(repo.findByStatus("rejected", 0, 10)).extracting(Submission::id).containsExactly(b);

        List<Submission> all = repo.findByStatus(null, 0, 10);
        assertThat(all).extracting(Submission::id).containsExactly(b, a);   // 최신순
    }

    @Test
    @DisplayName("countPendingByAuthor — 사용자별 대기 건수만 센다")
    void countPendingByAuthor_scoped() {
        repo.insert("u1", "A", "본문", null);
        repo.insert("u1", "B", "본문", null);
        long c = repo.insert("u1", "C", "본문", null);
        repo.insert("u2", "D", "본문", null);
        repo.markRejected(c, "admin", "사유");

        assertThat(repo.countPendingByAuthor("u1")).isEqualTo(2);
        assertThat(repo.countPendingByAuthor("u2")).isEqualTo(1);
    }

    @Test
    @DisplayName("bodyPreview — 한 줄로 접고 120자에서 자른다")
    void bodyPreview_flattensAndTruncates() {
        long id = repo.insert("u1", "제목", "첫 줄\n\n둘째 줄", null);
        assertThat(repo.findById(id).orElseThrow().bodyPreview()).isEqualTo("첫 줄 둘째 줄");

        long longId = repo.insert("u1", "제목", "가".repeat(200), null);
        String preview = repo.findById(longId).orElseThrow().bodyPreview();
        assertThat(preview).hasSize(121).endsWith("…");
    }

    // ── 1:N 분할 · 전부/전무 ─────────────────────────────────────────────────

    @Test
    @DisplayName("displayStatus — 청크가 여러 개여도 하나라도 살아 있으면 '등록 완료'")
    void displayStatus_anyActiveChunkKeepsApproved() {
        long id = repo.insert("u1", "제목", "본문", "인프라");
        long c1 = curatedRepo.insertManual(id, "u1", "제목", "본문 1", "인프라");
        long c2 = curatedRepo.insertManual(id, "u1", "제목", "본문 2", "인프라");
        long c3 = curatedRepo.insertManual(id, "u1", "제목", "본문 3", "인프라");
        repo.markApproved(id, "admin", "제목", "본문", "인프라", c1);

        assertThat(repo.findById(id).orElseThrow().chunkCount()).isEqualTo(3);
        assertThat(repo.findById(id).orElseThrow().displayStatus()).isEqualTo("approved");

        curatedRepo.deactivateById(c1);
        curatedRepo.deactivateById(c2);
        // 아직 c3 가 살아 있다 → 여전히 등록 완료
        assertThat(repo.findById(id).orElseThrow().displayStatus()).isEqualTo("approved");

        curatedRepo.deactivateById(c3);
        // 전부 내려가야 회수됨
        assertThat(repo.findById(id).orElseThrow().displayStatus()).isEqualTo("revoked");
    }

    @Test
    @DisplayName("embedFailed — 여러 청크 중 하나만 실패해도 true")
    void embedFailed_anyFailedChunk() {
        long id = repo.insert("u1", "제목", "본문", null);
        long c1 = curatedRepo.insertManual(id, "u1", "제목", "본문 1", null);
        long c2 = curatedRepo.insertManual(id, "u1", "제목", "본문 2", null);
        repo.markApproved(id, "admin", "제목", "본문", null, c1);

        assertThat(repo.findById(id).orElseThrow().embedFailed()).isFalse();

        curatedRepo.markEmbedFailed(c2);
        assertThat(repo.findById(id).orElseThrow().embedFailed()).isTrue();

        curatedRepo.markEmbedOk(c2);
        assertThat(repo.findById(id).orElseThrow().embedFailed()).isFalse();
    }

    @Test
    @DisplayName("tags — 등록 시 저장되고 승인 시 관리자 수정본으로 덮어써진다")
    void tags_storedAndOverwrittenOnApproval() {
        long id = repo.insert("u1", "제목", "본문", "인프라,vpn");
        assertThat(repo.findById(id).orElseThrow().tags()).isEqualTo("인프라,vpn");

        long c1 = curatedRepo.insertManual(id, "u1", "제목", "본문", "인프라");
        repo.markApproved(id, "admin", "제목", "본문", "인프라", c1);

        assertThat(repo.findById(id).orElseThrow().tags()).isEqualTo("인프라");
    }

    @Test
    @DisplayName("chunkCount — 승인 전에는 0")
    void chunkCount_zeroBeforeApproval() {
        long id = repo.insert("u1", "제목", "본문", null);
        assertThat(repo.findById(id).orElseThrow().chunkCount()).isZero();
    }

    // ── §10.11 저자 수정·철회 ────────────────────────────────────────────────

    @Test
    @DisplayName("markWithdrawn — 등록 완료된 제안도 철회할 수 있다 (반려·철회된 것은 여전히 불가)")
    void markWithdrawn_nowCoversApproved() {
        long approved = repo.insert("u1", "제목", "본문", null);
        long c1 = curatedRepo.insertManual(approved, "u1", "제목", "본문", null);
        repo.markApproved(approved, "admin", "제목", "본문", null, c1);
        assertThat(repo.markWithdrawn(approved, "u1")).isTrue();
        assertThat(repo.findById(approved).orElseThrow().status()).isEqualTo("withdrawn");

        long rejected = repo.insert("u1", "제목", "본문", null);
        repo.markRejected(rejected, "admin", "사유");
        assertThat(repo.markWithdrawn(rejected, "u1")).isFalse();

        long other = repo.insert("u1", "제목", "본문", null);
        assertThat(repo.markWithdrawn(other, "u2")).isFalse();   // 남의 제안은 못 내린다
    }

    @Test
    @DisplayName("updateByAuthor — 승인된 제안을 고치면 검토 대기로 돌아가되 등록본 연결은 남는다")
    void updateByAuthor_approvedGoesBackToPendingKeepingCuratedLink() {
        long id = repo.insert("u1", "원래 제목", "원래 본문", "인프라");
        long c1 = curatedRepo.insertManual(id, "u1", "원래 제목", "원래 본문", "인프라");
        repo.markApproved(id, "admin", "원래 제목", "원래 본문", "인프라", c1);

        assertThat(repo.updateByAuthor(id, "u1", "새 제목", "새 본문", "보안")).isTrue();

        Submission row = repo.findById(id).orElseThrow();
        assertThat(row.status()).isEqualTo("pending");
        assertThat(row.title()).isEqualTo("새 제목");
        assertThat(row.body()).isEqualTo("새 본문");
        assertThat(row.tags()).isEqualTo("보안");
        assertThat(row.reviewerUserId()).isNull();      // 사라진 텍스트에 대한 판정은 남기지 않는다
        assertThat(row.reviewedAt()).isNull();
        assertThat(row.curatedQaId()).isEqualTo(c1);    // 지금 검색에 쓰이는 등록본은 그대로다
        assertThat(row.curatedActive()).isEqualTo(1);
        // 검토 대기인데 등록본이 살아 있는 상태 — 목록이 "현재 등록본은 계속 사용 중"으로 읽는다.
        assertThat(row.displayStatus()).isEqualTo("pending");
    }

    @Test
    @DisplayName("updateByAuthor — 반려·철회된 제안이나 남의 제안은 고칠 수 없다")
    void updateByAuthor_refusesTerminalStatesAndOtherAuthors() {
        long rejected = repo.insert("u1", "제목", "본문", null);
        repo.markRejected(rejected, "admin", "사유");
        assertThat(repo.updateByAuthor(rejected, "u1", "새 제목", "새 본문", null)).isFalse();

        long mine = repo.insert("u1", "제목", "본문", null);
        assertThat(repo.updateByAuthor(mine, "u2", "새 제목", "새 본문", null)).isFalse();
        assertThat(repo.findById(mine).orElseThrow().title()).isEqualTo("제목");
    }

    @Test
    @DisplayName("findByAuthor — 저장된 상태로 거른다 (회수됨은 파생이라 등록 완료에 함께 나온다)")
    void findByAuthor_filtersByStoredStatus() {
        long pending  = repo.insert("u1", "대기", "본문", null);
        long approved = repo.insert("u1", "승인", "본문", null);
        long c1 = curatedRepo.insertManual(approved, "u1", "승인", "본문", null);
        repo.markApproved(approved, "admin", "승인", "본문", null, c1);
        curatedRepo.deactivateById(c1);                       // → displayStatus 는 revoked

        assertThat(repo.findByAuthor("u1", "pending", 0, 20)).extracting(Submission::id)
                .containsExactly(pending);
        assertThat(repo.findByAuthor("u1", "approved", 0, 20)).extracting(Submission::displayStatus)
                .containsExactly("revoked");
        assertThat(repo.findByAuthor("u1", null, 0, 20)).hasSize(2);
    }

    // ── §10.11 좋아요 출신 제안 ──────────────────────────────────────────────

    @Test
    @DisplayName("출처 턴 — 저장되고, 살아 있는(pending/approved) 제안만 중복으로 잡힌다")
    void sourceTurn_storedAndFoundWhileLive() {
        long id = repo.insert("u1", "제목", "본문", null, 42L, "t1");

        Submission row = repo.findById(id).orElseThrow();
        assertThat(row.sourceTurnId()).isEqualTo(42L);
        assertThat(row.sourceThreadId()).isEqualTo("t1");
        assertThat(row.fromChatTurn()).isTrue();
        assertThat(repo.findLiveByTurn(42L)).map(Submission::id).contains(id);

        // 반려·철회된 제안은 "이미 냈다"로 치지 않는다 — 다시 낼 수 있어야 하는 바로 그 상황이다.
        repo.markRejected(id, "admin", "출처 불명");
        assertThat(repo.findLiveByTurn(42L)).isEmpty();
    }

    /**
     * §10.11 함정 ② — 제안의 상태는 {@code curated_qa.source_submission_id} 로만 세어진다.
     * 좋아요 출신 승인이 그 연결을 빠뜨리면 아무것도 실패하지 않은 채 제안의 상태가 현실과
     * 끊긴다: 청크 0개인 '등록 완료'로 뜨고, 관리자가 그 지식을 실제로 내려도 계속 그렇게 뜬다.
     */
    @Test
    @DisplayName("함정 ② — source_submission_id 를 실어야 제안 상태가 실제 등록본을 따라간다")
    void likeOriginApproval_needsSubmissionLinkToTrackReality() {
        long linkedSub   = repo.insert("u1", "제목", "본문", null, 42L, "t1");
        long unlinkedSub = repo.insert("u1", "제목", "본문", null, 43L, "t1");

        long linked   = curatedRepo.upsertActive(42L, "u1", "t1", "제목", "본문", "v1", null, linkedSub);
        long unlinked = curatedRepo.upsertActive(43L, "u1", "t1", "제목", "본문", "v1", null, null);
        repo.markApproved(linkedSub,   "admin", "제목", "본문", null, linked);
        repo.markApproved(unlinkedSub, "admin", "제목", "본문", null, unlinked);

        // 연결이 있으면 등록본이 보인다. 없으면 승인 직후부터 "청크 0개"다.
        assertThat(repo.findById(linkedSub).orElseThrow().chunkCount()).isEqualTo(1);
        assertThat(repo.findById(unlinkedSub).orElseThrow().chunkCount()).isZero();

        // 그 지식을 실제로 내렸을 때 — 연결이 있으면 회수됨으로 따라오고, 없으면 등록 완료로 남는다.
        curatedRepo.deactivateById(linked);
        curatedRepo.deactivateById(unlinked);
        assertThat(repo.findById(linkedSub).orElseThrow().displayStatus()).isEqualTo("revoked");
        assertThat(repo.findById(unlinkedSub).orElseThrow().displayStatus()).isEqualTo("approved");
    }

    @Test
    @DisplayName("chunkCount — 좋아요 출신은 행 하나가 벡터 N개다 (행 수가 아니라 벡터 수를 센다)")
    void chunkCount_countsVectorsNotRows() {
        long id = repo.insert("u1", "제목", "본문", null, 42L, "t1");
        long curatedId = curatedRepo.upsertActive(42L, "u1", "t1", "제목", "본문", "v1", null, id);
        repo.markApproved(id, "admin", "제목", "본문", null, curatedId);
        curatedRepo.updateChunkCount(curatedId, 3);

        assertThat(repo.findById(id).orElseThrow().chunkCount()).isEqualTo(3);
    }
}
