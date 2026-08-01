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
        long id = repo.insert("u1", "제목", "본문");

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
        long id = repo.insert("u1", "원래 제목", "원래 본문");
        long curatedId = curatedRepo.insertManual(id, "u1", "수정 제목", "수정 본문");

        assertThat(repo.markApproved(id, "admin", "수정 제목", "수정 본문", curatedId)).isTrue();
        // 두 번째 호출은 이미 approved 라 CAS 실패 — 중복 curated 행이 생기지 않는 근거
        assertThat(repo.markApproved(id, "admin", "또 수정", "또 수정", curatedId)).isFalse();

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
        long id = repo.insert("u1", "제목", "본문");
        long curatedId = curatedRepo.insertManual(id, "u1", "제목", "본문");
        repo.markApproved(id, "admin", "제목", "본문", curatedId);

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
        long id = repo.insert("u1", "제목", "본문");
        long curatedId = curatedRepo.insertManual(id, "u1", "제목", "본문");
        repo.markApproved(id, "admin", "제목", "본문", curatedId);

        assertThat(repo.findById(id).orElseThrow().embedFailed()).isFalse();

        curatedRepo.markEmbedFailed(curatedId);
        assertThat(repo.findById(id).orElseThrow().embedFailed()).isTrue();

        curatedRepo.markEmbedOk(curatedId);
        assertThat(repo.findById(id).orElseThrow().embedFailed()).isFalse();
    }

    @Test
    @DisplayName("markRejected — 사유가 저장되고 pending 이 아니면 실패")
    void markRejected_storesNote() {
        long id = repo.insert("u1", "제목", "본문");

        assertThat(repo.markRejected(id, "admin", "출처가 불분명합니다")).isTrue();
        assertThat(repo.markRejected(id, "admin", "두 번째")).isFalse();

        Submission row = repo.findById(id).orElseThrow();
        assertThat(row.displayStatus()).isEqualTo("rejected");
        assertThat(row.reviewNote()).isEqualTo("출처가 불분명합니다");
    }

    @Test
    @DisplayName("markWithdrawn — 작성자 본인의 pending 제안만 철회된다")
    void markWithdrawn_scopedToAuthorAndPending() {
        long id = repo.insert("u1", "제목", "본문");

        assertThat(repo.markWithdrawn(id, "someone-else")).isFalse();
        assertThat(repo.markWithdrawn(id, "u1")).isTrue();
        assertThat(repo.markWithdrawn(id, "u1")).isFalse();   // 이미 withdrawn

        assertThat(repo.findById(id).orElseThrow().displayStatus()).isEqualTo("withdrawn");
    }

    @Test
    @DisplayName("알림 카운트 — 검토된 건만 세고, 목록을 열면(읽음 처리) 0이 된다")
    void unreadCount_countsReviewedOnlyAndClears() {
        long pending  = repo.insert("u1", "대기", "본문");
        long approved = repo.insert("u1", "승인", "본문");
        long rejected = repo.insert("u1", "반려", "본문");
        long other    = repo.insert("u2", "남의 글", "본문");
        repo.markRejected(other, "admin", "사유");

        // 검토 전에는 알림이 없다
        assertThat(repo.countUnreviewedNotificationsForAuthor("u1")).isZero();

        long curatedId = curatedRepo.insertManual(approved, "u1", "승인", "본문");
        repo.markApproved(approved, "admin", "승인", "본문", curatedId);
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
        long a = repo.insert("u1", "A", "본문");
        long b = repo.insert("u1", "B", "본문");
        repo.markRejected(b, "admin", "사유");

        assertThat(repo.findByStatus("pending", 0, 10)).extracting(Submission::id).containsExactly(a);
        assertThat(repo.findByStatus("rejected", 0, 10)).extracting(Submission::id).containsExactly(b);

        List<Submission> all = repo.findByStatus(null, 0, 10);
        assertThat(all).extracting(Submission::id).containsExactly(b, a);   // 최신순
    }

    @Test
    @DisplayName("countPendingByAuthor — 사용자별 대기 건수만 센다")
    void countPendingByAuthor_scoped() {
        repo.insert("u1", "A", "본문");
        repo.insert("u1", "B", "본문");
        long c = repo.insert("u1", "C", "본문");
        repo.insert("u2", "D", "본문");
        repo.markRejected(c, "admin", "사유");

        assertThat(repo.countPendingByAuthor("u1")).isEqualTo(2);
        assertThat(repo.countPendingByAuthor("u2")).isEqualTo(1);
    }

    @Test
    @DisplayName("bodyPreview — 한 줄로 접고 120자에서 자른다")
    void bodyPreview_flattensAndTruncates() {
        long id = repo.insert("u1", "제목", "첫 줄\n\n둘째 줄");
        assertThat(repo.findById(id).orElseThrow().bodyPreview()).isEqualTo("첫 줄 둘째 줄");

        long longId = repo.insert("u1", "제목", "가".repeat(200));
        String preview = repo.findById(longId).orElseThrow().bodyPreview();
        assertThat(preview).hasSize(121).endsWith("…");
    }
}
