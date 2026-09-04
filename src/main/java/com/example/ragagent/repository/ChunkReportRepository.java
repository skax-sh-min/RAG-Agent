package com.example.ragagent.repository;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * §10.14 청크 오류 신고 — 사용자가 "이 청크 내용이 틀렸다/오래됐다"고 남기는 대기열.
 *
 * <p>순수 대기열이다: 여기 쓰인 값은 검색·재사용·벡터 스토어 어디에도 영향을 주지 않는다. 실제
 * 반영은 관리자가 기존 청크 편집 경로로 청크를 고칠 때 비로소 일어난다.
 *
 * <p><b>두 JdbcTemplate 을 든다</b>({@link QuestionReuseRepository} 선례): 신고 행 자체는 운영
 * DB(memory.db)에 살고, 신고 시점의 청크 위치·원문 스냅샷은 벡터/FTS DB 에서 읽어야 한다.
 */
@Repository
public class ChunkReportRepository {

    private static final Logger log = LoggerFactory.getLogger(ChunkReportRepository.class);

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static final String STATUS_OPEN     = "open";
    public static final String STATUS_RESOLVED = "resolved";
    public static final String STATUS_REJECTED = "rejected";

    private final JdbcTemplate jdbc;
    private final JdbcTemplate vectorJdbc;

    public ChunkReportRepository(JdbcTemplate jdbc,
                                 @Qualifier("vectorJdbcTemplate") JdbcTemplate vectorJdbc) {
        this.jdbc = jdbc;
        this.vectorJdbc = vectorJdbc;
    }

    /** 신고 1건. {@code chunkSnapshot}/{@code chunkHash} 는 <b>신고 시점</b>의 청크다(현재가 아니다). */
    public record Report(long id, String chunkId, String docId, String version, String filename,
                         String reporterUserId, String threadId, Long turnId, String question,
                         String reasonCode, String comment, String chunkHash, String chunkSnapshot,
                         String status, String reviewerUserId, String reviewNote,
                         String createdAt, String reviewedAt) {}

    /**
     * 관리자 목록의 한 행 — <b>신고 1건이 아니라 청크 1개</b>다. 한 청크에 여러 사람이 신고하는 것이
     * 정상이고, 관리자가 할 일은 그 청크를 고치는 하나이므로 대기열의 단위도 청크여야 한다.
     */
    public record Group(String chunkId, String docId, String version, String filename,
                        int reportCount, String firstReportedAt, String lastReportedAt) {}

    /**
     * 청크의 위치 + 지금 저장돼 있는 텍스트.
     *
     * <p>{@code source} 가 필요한 이유: sqlite-vec 배포에서는 {@code vec_document_chunks.content}
     * 가 <b>원문</b>이지만, Chroma 배포에서는 이 앱의 SQLite 쪽에 원문이 없어 {@code chunk_fts}
     * 의 <b>파생 검색 텍스트</b>(§10.1 — 맥락 헤더 + 정규화된 본문)밖에 읽을 수 없다. 신고 당시
     * 스냅샷과 나란히 놓고 비교하는 화면이므로, 둘이 다르게 보이는 이유가 "고쳐져서"인지 "원래
     * 다른 텍스트라서"인지 화면이 말할 수 있어야 한다.
     */
    public record ChunkLocation(String docId, String version, String filename,
                                String content, String source) {
        /** 원문 그대로(sqlite-vec 백엔드). */
        public static final String SOURCE_ORIGINAL = "original";
        /** FTS 파생 검색 텍스트(원문을 읽을 수 없는 백엔드). */
        public static final String SOURCE_SEARCH_TEXT = "search-text";
    }

    private static final RowMapper<Report> ROW_MAPPER = (rs, n) -> {
        long turnId = rs.getLong("turn_id");
        Long turn = rs.wasNull() ? null : turnId;
        return new Report(
                rs.getLong("id"),
                rs.getString("chunk_id"),
                rs.getString("doc_id"),
                rs.getString("version"),
                rs.getString("filename"),
                rs.getString("reporter_user_id"),
                rs.getString("thread_id"),
                turn,
                rs.getString("question"),
                rs.getString("reason_code"),
                rs.getString("comment"),
                rs.getString("chunk_hash"),
                rs.getString("chunk_snapshot"),
                rs.getString("status"),
                rs.getString("reviewer_user_id"),
                rs.getString("review_note"),
                rs.getString("created_at"),
                rs.getString("reviewed_at"));
    };

    @PostConstruct
    void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS chunk_report (
                    id               INTEGER PRIMARY KEY AUTOINCREMENT,
                    chunk_id         TEXT NOT NULL,
                    doc_id           TEXT,
                    version          TEXT,
                    filename         TEXT,
                    reporter_user_id TEXT NOT NULL,
                    thread_id        TEXT,
                    turn_id          INTEGER,
                    question         TEXT,
                    reason_code      TEXT NOT NULL,
                    comment          TEXT NOT NULL,
                    chunk_hash       TEXT,
                    chunk_snapshot   TEXT,
                    status           TEXT NOT NULL DEFAULT 'open',
                    reviewer_user_id TEXT,
                    review_note      TEXT,
                    created_at       TEXT NOT NULL,
                    reviewed_at      TEXT
                )
                """);
        // 대기열 조회(열린 신고를 청크로 묶기)와 청크별 상세가 각각 타는 인덱스.
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chunk_report_open "
                + "ON chunk_report(status, chunk_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chunk_report_chunk "
                + "ON chunk_report(chunk_id, id DESC)");
        // 중복 방지 — 키가 (청크, 신고자, 대화)인 이유는 no-auth 기본값에서 게스트의 userId 가
        // 상수일 수 있기 때문이다(app.auth.guest-identity=shared → 전 방문자가 GUEST_ID 하나).
        // (청크, 신고자)로 잠그면 그 배포에서는 청크당 단 한 명만 신고할 수 있게 되어, 이 기능의
        // 전제("여러 명이 같은 청크를 신고한다")가 조용히 무너진다. thread_id 는 방문자·대화
        // 단위로 갈라지므로 남의 신고를 막지 않으면서 같은 대화의 중복 클릭만 막는다.
        // 부분 인덱스라 처리 완료된 신고는 재신고를 막지 않는다(고쳤는데 또 틀렸을 수 있다).
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_chunk_report_dup "
                + "ON chunk_report(chunk_id, reporter_user_id, thread_id) WHERE status = 'open'");
    }

    // ── 쓰기 ────────────────────────────────────────────────────────────

    /** 새 신고. 항상 {@code open} 으로 시작한다. 반환값은 새 행의 id. */
    public long insert(String chunkId, String docId, String version, String filename,
                       String reporterUserId, String threadId, Long turnId, String question,
                       String reasonCode, String comment, String chunkHash, String chunkSnapshot) {
        String now = now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO chunk_report (chunk_id, doc_id, version, filename, reporter_user_id, "
                    + "thread_id, turn_id, question, reason_code, comment, chunk_hash, chunk_snapshot, "
                    + "status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '"
                    + STATUS_OPEN + "', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, chunkId);
            ps.setString(2, docId);
            ps.setString(3, version);
            ps.setString(4, filename);
            ps.setString(5, reporterUserId);
            ps.setString(6, threadId);
            if (turnId == null) {
                ps.setNull(7, java.sql.Types.INTEGER);
            } else {
                ps.setLong(7, turnId);
            }
            ps.setString(8, question);
            ps.setString(9, reasonCode);
            ps.setString(10, comment);
            ps.setString(11, chunkHash);
            ps.setString(12, chunkSnapshot);
            ps.setString(13, now);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key != null ? key.longValue() : -1L;
    }

    /**
     * 청크 하나에 열려 있는 신고를 <b>전부</b> 같은 판정으로 닫는다. 관리자가 고치는 대상은 청크
     * 하나이지 신고 N건이 아니므로 조치의 단위도 그렇다 — 보던 사이에 도착한 신고가 함께 닫히는
     * 것도 같은 이유로 옳다(같은 청크에 대한 같은 조치다).
     *
     * <p>{@code WHERE status='open'} 이 곧 compare-and-set 이라 두 관리자가 동시에 눌러도 두 번째는
     * 0행을 고친다.
     *
     * @return 닫힌 신고 수 (0 = 이미 처리됨)
     */
    public int closeChunk(String chunkId, String status, String reviewerUserId, String reviewNote) {
        return jdbc.update(
                "UPDATE chunk_report SET status = ?, reviewer_user_id = ?, review_note = ?, reviewed_at = ? "
                + "WHERE chunk_id = ? AND status = '" + STATUS_OPEN + "'",
                status, reviewerUserId, reviewNote, now(), chunkId);
    }

    // ── 읽기 ────────────────────────────────────────────────────────────

    /** 이 대화에서 이 사용자가 이 청크를 이미 신고했는가(열린 신고 기준). */
    public boolean hasOpenReport(String chunkId, String reporterUserId, String threadId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chunk_report WHERE chunk_id = ? AND reporter_user_id = ? "
                + "AND COALESCE(thread_id, '') = COALESCE(?, '') AND status = '" + STATUS_OPEN + "'",
                Integer.class, chunkId, reporterUserId, threadId);
        return n != null && n > 0;
    }

    /**
     * 대기열 — 열린 신고를 청크로 묶어 최근 신고순으로. 문서명·버전은 신고 행에 복사돼 있으므로
     * 문서가 지워진 뒤에도 남는다({@code MAX()} 는 그룹에서 아무 값이나 고르기 위한 것으로, 같은
     * 청크의 행들은 이 세 값이 같다).
     */
    public List<Group> openGroups(int offset, int limit) {
        return jdbc.query("""
                SELECT chunk_id,
                       MAX(doc_id)     AS doc_id,
                       MAX(version)    AS version,
                       MAX(filename)   AS filename,
                       COUNT(*)        AS report_count,
                       MIN(created_at) AS first_reported_at,
                       MAX(created_at) AS last_reported_at
                  FROM chunk_report
                 WHERE status = ?
                 GROUP BY chunk_id
                 ORDER BY MAX(created_at) DESC
                 LIMIT ? OFFSET ?
                """,
                (rs, n) -> new Group(
                        rs.getString("chunk_id"),
                        rs.getString("doc_id"),
                        rs.getString("version"),
                        rs.getString("filename"),
                        rs.getInt("report_count"),
                        rs.getString("first_reported_at"),
                        rs.getString("last_reported_at")),
                STATUS_OPEN, limit, offset);
    }

    /**
     * 헤더 배지 값 — <b>열린 신고를 가진 청크 수</b>이지 신고 건수가 아니다. 한 청크에 열 명이
     * 신고해도 관리자가 할 일은 하나이므로, 건수로 세면 밀린 일이 실제보다 많아 보인다.
     */
    public int countOpenChunks() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT 1 FROM chunk_report WHERE status = ? GROUP BY chunk_id)",
                Integer.class, STATUS_OPEN);
        return n == null ? 0 : n;
    }

    /** 한 청크의 열린 신고 전부 — 오래된 순(코멘트를 읽는 순서가 곧 신고가 쌓인 순서다). */
    public List<Report> findOpenByChunk(String chunkId) {
        return jdbc.query(
                "SELECT * FROM chunk_report WHERE chunk_id = ? AND status = ? ORDER BY id ASC",
                ROW_MAPPER, chunkId, STATUS_OPEN);
    }

    /** 처리 이력 — 같은 청크가 전에도 신고·처리된 적이 있는지(상세 화면의 꼬리말). */
    public List<Report> findClosedByChunk(String chunkId, int limit) {
        return jdbc.query(
                "SELECT * FROM chunk_report WHERE chunk_id = ? AND status <> ? ORDER BY id DESC LIMIT ?",
                ROW_MAPPER, chunkId, STATUS_OPEN, limit);
    }

    // ── 청크 조회(벡터/FTS DB) ───────────────────────────────────────────

    /**
     * 신고 대상 청크의 위치와 현재 원문. <b>클라이언트가 보낸 값을 믿지 않기 위해</b> 서버가 직접
     * 읽는다 — 신고 폼은 청크 id 하나만 보낸다.
     *
     * <p>{@code chunk_fts} 를 먼저 보는 이유는 두 백엔드에 모두 있기 때문이고,
     * {@code vec_document_chunks} 는 sqlite-vec 배포에만 존재하므로 <b>테이블이 있을 때만</b>
     * 조회한다(Chroma 배포에서 없는 테이블을 참조하면 쿼리 자체가 예외가 된다).
     *
     * <p>주의: {@code chunk_fts.content} 는 원문이 아니라 <b>파생 검색 텍스트</b>(§10.1)다. 그래서
     * 원문을 가진 vec 테이블이 있으면 그쪽 텍스트를 우선 쓰고, 화면은 어느 쪽을 보여주는지 밝힌다.
     */
    public Optional<ChunkLocation> findChunkLocation(String chunkId) {
        if (chunkId == null || chunkId.isBlank()) return Optional.empty();

        if (hasVecChunkTable()) {
            List<ChunkLocation> vecRows = query(
                    "SELECT doc_id, version, content FROM vec_document_chunks WHERE spring_doc_id = ? LIMIT 1",
                    (rs, n) -> new ChunkLocation(rs.getString("doc_id"), rs.getString("version"),
                            null, rs.getString("content"), ChunkLocation.SOURCE_ORIGINAL),
                    chunkId);
            if (!vecRows.isEmpty()) {
                ChunkLocation vec = vecRows.get(0);
                // filename 은 vec 테이블에 없다 — FTS 행이 있으면 거기서 채운다(없으면 null).
                List<ChunkLocation> ftsRows = ftsRows(chunkId);
                String filename = ftsRows.isEmpty() ? null : ftsRows.get(0).filename();
                return Optional.of(new ChunkLocation(vec.docId(), vec.version(), filename,
                        vec.content(), ChunkLocation.SOURCE_ORIGINAL));
            }
        }
        List<ChunkLocation> ftsRows = ftsRows(chunkId);
        return ftsRows.isEmpty() ? Optional.empty() : Optional.of(ftsRows.get(0));
    }

    private List<ChunkLocation> ftsRows(String chunkId) {
        return query("SELECT doc_id, version, filename, content FROM chunk_fts WHERE spring_doc_id = ? LIMIT 1",
                (rs, n) -> new ChunkLocation(rs.getString("doc_id"), rs.getString("version"),
                        rs.getString("filename"), rs.getString("content"),
                        ChunkLocation.SOURCE_SEARCH_TEXT),
                chunkId);
    }

    /**
     * FTS5 가 없는 SQLite 빌드({@code KeywordSearchRepository.isAvailable()} 가 false)나 아직
     * 인덱싱 전이면 테이블 자체가 없다 — 그 경우 신고는 스냅샷 없이 접수된다(신고를 막는 것보다
     * 낫다).
     */
    private <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
        try {
            return vectorJdbc.query(sql, mapper, args);
        } catch (Exception e) {
            log.debug("[REPORT] 청크 조회 실패 — 스냅샷 없이 진행: {}", e.getMessage());
            return List.of();
        }
    }

    private boolean hasVecChunkTable() {
        try {
            Integer n = vectorJdbc.queryForObject(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'vec_document_chunks'",
                    Integer.class);
            return n != null && n > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String now() {
        return LocalDateTime.now().format(DT_FMT);
    }
}
