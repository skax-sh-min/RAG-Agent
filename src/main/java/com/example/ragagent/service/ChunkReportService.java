package com.example.ragagent.service;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.model.ChunkReportReason;
import com.example.ragagent.repository.ChunkReportRepository;
import com.example.ragagent.repository.ChunkReportRepository.ChunkLocation;
import com.example.ragagent.repository.ChunkReportRepository.Group;
import com.example.ragagent.repository.ChunkReportRepository.Report;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * §10.14 청크 오류 신고 — 사용자가 낸 "이 청크 내용이 틀렸다/오래됐다"를 접수하고, 관리자가
 * <b>청크 단위로</b> 확인·처리할 수 있게 읽어 주는 계층.
 *
 * <p><b>이 클래스는 검색 코퍼스를 건드리지 않는다.</b> 신고는 대기열에 쌓일 뿐이고 청크·벡터·FTS·
 * 재사용 판정 어디에도 영향이 없다 — 반영은 관리자가 기존 청크 편집 경로로 청크를 실제로 고칠 때
 * 일어난다. "N건 이상이면 자동 비활성화" 같은 자동 조치를 여기에 넣으면 신고가 곧 삭제 버튼이
 * 되어, §10.11 이 좁혀 놓은 "코퍼스는 사람이 지킨다"와 방향이 어긋난다.
 *
 * <p>신고 시점의 청크 원문·해시를 <b>행마다</b> 스냅샷한다. 관리자가 볼 때쯤 청크는 재인덱싱으로
 * 사라졌을 수 있고, 그러면 무엇이 틀렸다는 것인지 자체가 없어진다. 3주 전 신고와 어제 신고가 서로
 * 다른 원문을 가리키는 것도 정보다(그 사이에 한 번 고쳐졌다는 뜻).
 */
@Service
public class ChunkReportService {

    private static final Logger log = LoggerFactory.getLogger(ChunkReportService.class);

    /** 코멘트 상한. 관리자가 읽는 한 문단이지 문서가 아니다. */
    public static final int MAX_COMMENT_LEN = 500;

    /** 스냅샷 상한 — 청크는 보통 chunk-size(기본 1,500자) 안쪽이고, 이 값은 이상값 방어다. */
    private static final int MAX_SNAPSHOT_LEN = 8_000;

    private final ChunkReportRepository repository;
    private final QuestionReuseService questionReuseService;
    private final MemoryService memoryService;
    private final AuditLogger auditLogger;

    public ChunkReportService(ChunkReportRepository repository,
                              QuestionReuseService questionReuseService,
                              MemoryService memoryService,
                              AuditLogger auditLogger) {
        this.repository = repository;
        this.questionReuseService = questionReuseService;
        this.memoryService = memoryService;
        this.auditLogger = auditLogger;
    }

    /**
     * 접수 결과. {@code created=false} 는 오류가 아니라 <b>이 대화에서 이미 신고한 청크</b>라는
     * 뜻이라 화면 문구가 달라야 한다 — 그래서 예외가 아니라 값으로 돌려준다.
     */
    public record ReportResult(boolean created, long id) {}

    /** 관리자 상세 — 한 청크에 달린 신고 전부 + 그 청크의 현재 상태. */
    public record ChunkReportDetail(String chunkId, String docId, String version, String filename,
                                    boolean curated, List<ReportView> reports,
                                    String currentContent, String currentContentSource,
                                    String changeStatus, List<Report> history) {}

    /**
     * 신고 1건 + <b>그 신고 이후</b> 청크가 바뀌었는지. 신고마다 따로 계산하는 이유는 오래된 신고와
     * 최근 신고가 서로 다른 원문을 가리킬 수 있어서다(그 사이에 고쳐졌다면 오래된 쪽만 낡는다).
     */
    public record ReportView(Report report, boolean snapshotStale) {}

    /** 신고 이후 청크가 그대로다. */
    public static final String CHANGE_UNCHANGED = "unchanged";
    /** 신고 이후 청크가 수정됐다(해시 불일치). */
    public static final String CHANGE_MODIFIED = "modified";
    /** 청크가 더 이상 없다(삭제 또는 재인덱싱으로 id 가 사라짐). */
    public static final String CHANGE_DELETED = "deleted";
    /** 해시를 구할 수 없어 판단하지 않는다(FTS 미가용 등) — "그대로"로 위조하지 않는다. */
    public static final String CHANGE_UNKNOWN = "unknown";

    // ── 접수 ────────────────────────────────────────────────────────────

    /**
     * 신고 접수. 클라이언트에서 오는 값은 {@code chunkId}·{@code threadId}·{@code turnId}·사유·
     * 코멘트뿐이고, <b>문서·버전·원문 스냅샷은 서버가 직접 읽는다</b> — 신고 대상이 무엇인지를
     * 클라이언트가 정하게 두면 대기열이 사실과 어긋난다.
     *
     * @throws IllegalArgumentException 검증 실패(→ 400). 메시지는 사용자에게 그대로 보인다.
     */
    public ReportResult report(String reporterUserId, String chunkId, String threadId, Long turnId,
                               String reasonRaw, String comment) {
        if (chunkId == null || chunkId.isBlank()) {
            throw new IllegalArgumentException("신고할 청크를 찾을 수 없습니다.");
        }
        ChunkReportReason reason = ChunkReportReason.parse(reasonRaw)
                .orElseThrow(() -> new IllegalArgumentException("신고 사유를 선택해 주세요."));
        String trimmed = comment == null ? "" : comment.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("어떤 내용이 잘못됐는지 간단히 적어 주세요.");
        }
        if (trimmed.length() > MAX_COMMENT_LEN) {
            throw new IllegalArgumentException("설명은 " + MAX_COMMENT_LEN + "자를 넘을 수 없습니다.");
        }

        if (repository.hasOpenReport(chunkId, reporterUserId, threadId)) {
            return new ReportResult(false, -1L);
        }

        Optional<ChunkLocation> location = repository.findChunkLocation(chunkId);
        String snapshot = location.map(ChunkLocation::content).map(ChunkReportService::truncate).orElse(null);
        String hash = questionReuseService == null ? "" : questionReuseService.currentChunkHash(chunkId);
        String question = questionSnapshot(reporterUserId, threadId, turnId);

        try {
            long id = repository.insert(
                    chunkId,
                    location.map(ChunkLocation::docId).orElse(null),
                    location.map(ChunkLocation::version).orElse(null),
                    location.map(ChunkLocation::filename).orElse(null),
                    reporterUserId, threadId, turnId, question,
                    reason.name(), trimmed, hash, snapshot);
            auditLogger.log("chunk.report", chunkId, Map.of(
                    "reportId", id,
                    "reason", reason.name(),
                    "threadId", threadId == null ? "" : threadId,
                    "turnId", turnId == null ? -1L : turnId));
            return new ReportResult(true, id);
        } catch (DuplicateKeyException e) {
            // 위 hasOpenReport 와 부분 UNIQUE 인덱스 사이의 경쟁(더블 클릭) — 중복은 오류가 아니다.
            return new ReportResult(false, -1L);
        }
    }

    /**
     * 신고 당시 사용자가 무엇을 묻고 있었는지. 턴을 <b>다시 읽어</b> 소유까지 확인한다 — 클라이언트가
     * 보낸 질문 문자열을 믿지 않기 위해서이고, 남의 턴 id 를 넣어 대기열에 남의 질문을 심는 것도
     * 막는다. 찾지 못하면 오류가 아니라 질문 없는 신고다(스트리밍 중이거나 이미 지운 대화).
     */
    private String questionSnapshot(String userId, String threadId, Long turnId) {
        if (turnId == null || threadId == null || threadId.isBlank() || memoryService == null) return null;
        try {
            return memoryService.getTurn(userId, threadId, turnId)
                    .map(t -> truncate(t.question()))
                    .orElse(null);
        } catch (Exception e) {
            log.debug("[REPORT] 질문 스냅샷 실패 turnId={}: {}", turnId, e.getMessage());
            return null;
        }
    }

    // ── 관리자 조회 ─────────────────────────────────────────────────────

    /** 대기열 — 열린 신고를 청크로 묶은 목록(최근 신고순). */
    public List<Group> openGroups(int offset, int limit) {
        return repository.openGroups(Math.max(0, offset), Math.max(1, limit));
    }

    /** 헤더 배지 — 열린 신고를 가진 <b>청크 수</b>(신고 건수가 아니다). */
    public int openChunkCount() {
        return repository.countOpenChunks();
    }

    /**
     * 한 청크의 열린 신고 전부와 그 청크의 현재 상태. 관리자는 여기서 코멘트 N개를 한 화면에 읽고,
     * 고칠지 반려할지 판단한다.
     *
     * @return 열린 신고가 하나도 없으면 {@code empty}
     */
    public Optional<ChunkReportDetail> chunkDetail(String chunkId) {
        List<Report> open = repository.findOpenByChunk(chunkId);
        if (open.isEmpty()) return Optional.empty();

        String currentHash = questionReuseService == null ? "" : questionReuseService.currentChunkHash(chunkId);
        Optional<ChunkLocation> location = repository.findChunkLocation(chunkId);

        List<ReportView> views = open.stream()
                .map(r -> new ReportView(r, snapshotStale(r.chunkHash(), currentHash)))
                .toList();

        Report newest = open.get(open.size() - 1);
        String changeStatus = changeStatus(newest.chunkHash(), currentHash, location.isPresent());

        // 신고 행에 복사해 둔 값이 먼저다 — 문서가 지워진 뒤에도 "무엇에 대한 신고였는지"가 남아야
        // 한다. 살아 있는 청크에서는 두 값이 같다.
        String version = firstNonBlank(newest.version(), location.map(ChunkLocation::version).orElse(null));

        return Optional.of(new ChunkReportDetail(
                chunkId,
                firstNonBlank(newest.docId(), location.map(ChunkLocation::docId).orElse(null)),
                version,
                firstNonBlank(newest.filename(), location.map(ChunkLocation::filename).orElse(null)),
                CuratedQaService.CURATED_VERSION.equals(version),
                views,
                location.map(ChunkLocation::content).orElse(null),
                // 지금 보여주는 텍스트가 원문인지 FTS 파생 검색 텍스트인지(§10.1). 스냅샷과 나란히
                // 놓는 화면이라, 둘이 다른 이유가 "고쳐져서"인지 "원래 다른 텍스트라서"인지 밝혀야 한다.
                location.map(ChunkLocation::source).orElse(null),
                changeStatus,
                repository.findClosedByChunk(chunkId, 5)));
    }

    /** 이 신고가 붙잡은 원문이 지금 것과 다른가. 둘 중 하나라도 모르면 "낡았다"고 말하지 않는다. */
    private static boolean snapshotStale(String reportedHash, String currentHash) {
        if (reportedHash == null || reportedHash.isBlank()) return false;
        if (currentHash == null || currentHash.isBlank()) return true;   // 청크가 사라졌다
        return !reportedHash.equals(currentHash);
    }

    private static String changeStatus(String reportedHash, String currentHash, boolean stillStored) {
        boolean noCurrent = currentHash == null || currentHash.isBlank();
        if (noCurrent && !stillStored) return CHANGE_DELETED;
        if (reportedHash == null || reportedHash.isBlank() || noCurrent) return CHANGE_UNKNOWN;
        return reportedHash.equals(currentHash) ? CHANGE_UNCHANGED : CHANGE_MODIFIED;
    }

    // ── 관리자 조치 ─────────────────────────────────────────────────────

    /**
     * 「처리 완료」 — 이 청크의 열린 신고를 전부 닫는다. 관리자가 고치는 대상은 청크 하나이지 신고
     * N건이 아니므로 조치의 단위도 청크다. 판정만 남기고 청크 자체는 건드리지 않는다(수정은 기존
     * 청크 편집 경로의 일이고, 그래야 편집 시각 스탬프·재인덱싱 경고·재사용 무효화가 한 곳에 붙는다).
     *
     * @return 닫힌 신고 수 (0 = 그 사이 다른 관리자가 처리함)
     */
    public int resolveChunk(String chunkId, String reviewerUserId, String note) {
        return close(chunkId, ChunkReportRepository.STATUS_RESOLVED, reviewerUserId, note,
                "chunk.report.resolve");
    }

    /** 「반려」 — 사유가 필수다(신고자에게 남는 유일한 기록이자, 나중에 같은 신고가 또 왔을 때의 근거). */
    public int rejectChunk(String chunkId, String reviewerUserId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("반려 사유를 입력해 주세요.");
        }
        return close(chunkId, ChunkReportRepository.STATUS_REJECTED, reviewerUserId, reason.strip(),
                "chunk.report.reject");
    }

    private int close(String chunkId, String status, String reviewerUserId, String note, String action) {
        if (chunkId == null || chunkId.isBlank()) {
            throw new IllegalArgumentException("청크 ID가 필요합니다.");
        }
        int closed = repository.closeChunk(chunkId, status, reviewerUserId,
                note == null ? null : note.strip());
        if (closed > 0) {
            auditLogger.log(action, chunkId, Map.of("closed", closed));
        }
        return closed;
    }

    private static String truncate(String text) {
        if (text == null) return null;
        return text.length() <= MAX_SNAPSHOT_LEN ? text : text.substring(0, MAX_SNAPSHOT_LEN);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return (b != null && !b.isBlank()) ? b : null;
    }
}
