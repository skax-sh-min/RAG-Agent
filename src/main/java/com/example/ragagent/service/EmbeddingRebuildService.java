package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.config.SqliteVecSchemaInitializer;
import com.example.ragagent.web.MdcPropagation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 임베딩 모델을 바꾼 뒤 sqlite-vec 의 벡터를 <b>앱 안에서</b> 다시 만든다 — {@code /admin} 의 「임베딩 재생성」 카드가 부른다
 * (OPERATOR_MANUAL §7.3). 이전에는 SQLite 를 직접 열어 {@code vec_embeddings} 를 지우고 청크마다 재인덱싱 API 를 부르는
 * 외부 스크립트가 필요했다.
 *
 * <p>두 가지로 시작한다.
 * <ul>
 *   <li>{@link Mode#REBUILD} — 벡터 테이블({@code vec_embeddings})을 <b>지우고 설정한 차원으로 다시 만든 뒤</b> 모든 청크를
 *       새로 임베딩한다. 임베딩 모델을 바꿨을 때의 길이다. 옛 벡터를 먼저 지우므로 끝날 때까지 벡터 축 검색은 비어 있고
 *       BM25 만 동작한다 — 옛 모델 벡터와 새 모델 벡터가 섞인 순위는 어느 쪽도 의미가 없어서 비워 두는 쪽을 택했다.</li>
 *   <li>{@link Mode#RESUME} — 벡터가 아직 없는 청크만 임베딩한다. 중단·실패 뒤 이어 하는 길이다. 테이블 차원이 설정과 다르면
 *       받지 않는다(옛 차원 벡터 옆에 새 차원을 넣을 수 없다).</li>
 * </ul>
 *
 * <p><b>청크 하나의 재임베딩은 관리자 화면의 청크 「재인덱싱」 버튼과 같은 코드다</b>({@code AdminController.reindexChunk} 의 갈림을
 * 그대로 따른다): 문서 청크는 {@link AdminService#reindexChunk}(저장된 본문 + {@code chunk_context} 로 검색 텍스트를 다시 만들어
 * <b>같은 청크 id 로</b> 벡터를 쓰고 FTS 를 갱신 — LLM 호출 없음, 손으로 고친 청크·태그·레지스트리·질문 재사용 해시 그대로),
 * 큐레이션 청크는 {@link CuratedQaService#reembedRow}(질문 + 본문 규칙). 한 행이 여러 조각으로 나뉘는 큐레이션은 첫 조각에서
 * 행 전체를 다시 쓰므로 나머지 조각은 건너뛴다.
 *
 * <p><b>"벡터 없는 청크"를 {@code LEFT JOIN vec_embeddings} 로 구하지 않는다</b> — vec0 의 기본키 점조회가 행마다 훑어서 청크 2만 개에
 * 13초(제곱으로 늘어난다)가 걸렸다. 두 테이블의 id 를 각각 한 번씩 읽어 차집합을 낸다(같은 2만 개에 10ms).
 *
 * <p>작업은 앱 전체에서 하나만, 가상 스레드에서 청크를 <b>하나씩</b> 돈다(SQLite 풀 1개 — 병렬로 얻을 것이 없다). 연속
 * {@value #MAX_CONSECUTIVE_FAILURES}개가 실패하면 임베딩 서버가 죽은 것으로 보고 멈춘다(청크 수만 개를 실패로 채우지 않도록).
 * 진행 상태는 메모리에만 있다 — 재기동하면 사라지고, 이어 하기는 DB 의 "벡터 없는 청크"로 다시 계산한다.
 */
@Service
@ConditionalOnProperty(name = "app.vectorstore.type", havingValue = "sqlite-vec")
public class EmbeddingRebuildService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingRebuildService.class);

    /** 연속 이만큼 실패하면 서버 쪽 문제로 보고 작업을 멈춘다. */
    static final int MAX_CONSECUTIVE_FAILURES = 20;
    /** 화면에 보일 실패 예시의 상한. */
    static final int MAX_FAILURE_SAMPLES = 20;

    private static final String SELECT_EMBEDDING_DDL =
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'vec_embeddings'";

    public enum Mode { REBUILD, RESUME }

    public enum JobState { RUNNING, DONE, CANCELLED, FAILED }

    /** 실패한 청크 하나. */
    public record Failure(String id, String version, String reason) {}

    /** 작업의 한 시점 모습(JSON). {@code total} 은 {@code phase} 가 임베딩 단계가 되기 전에는 0 이다. */
    public record Job(JobState state, Mode mode, String phase, long total, long done, long skipped, long failed,
                      String startedAt, String finishedAt, String message, List<Failure> failures) {}

    /**
     * 카드가 그리는 전부. {@code missingVectors} 는 청크 수 − 벡터 수(차원이 다르면 옛 벡터는 못 쓰므로 청크 수 전체).
     * 정확한 차집합은 작업을 시작할 때 구한다 — 이 값은 페이지를 열 때마다 불리므로 싸게 센다.
     */
    public record Status(Integer tableDimension, Integer configuredDimension, boolean dimensionMismatch,
                         long totalChunks, long vectorCount, long missingVectors, boolean running, Job job) {}

    record ChunkRef(String id, String version) {}

    /** 가변 작업 상태 — 워커가 쓰고 상태 조회가 읽는다. */
    private static final class JobRun {
        final Mode mode;
        final Instant startedAt = Instant.now();
        volatile JobState state = JobState.RUNNING;
        volatile String phase = "시작하는 중";
        volatile String message;
        volatile Instant finishedAt;
        volatile boolean cancelRequested;
        final AtomicLong total = new AtomicLong();
        final AtomicLong done = new AtomicLong();
        final AtomicLong skipped = new AtomicLong();
        final AtomicLong failed = new AtomicLong();
        final List<Failure> failures = Collections.synchronizedList(new ArrayList<>());

        JobRun(Mode mode) { this.mode = mode; }

        Job snapshot() {
            List<Failure> copy;
            synchronized (failures) { copy = List.copyOf(failures); }
            return new Job(state, mode, phase, total.get(), done.get(), skipped.get(), failed.get(),
                    startedAt.toString(), finishedAt == null ? null : finishedAt.toString(), message, copy);
        }
    }

    private final JdbcTemplate jdbc;
    private final AppProperties props;
    private final AdminService adminService;
    private final CuratedQaService curatedQaService;

    private final Object startLock = new Object();
    private final AtomicReference<JobRun> current = new AtomicReference<>();

    public EmbeddingRebuildService(@Qualifier("vectorJdbcTemplate") JdbcTemplate jdbc, AppProperties props,
                                   AdminService adminService, CuratedQaService curatedQaService) {
        this.jdbc = jdbc;
        this.props = props;
        this.adminService = adminService;
        this.curatedQaService = curatedQaService;
    }

    // ── 상태 ──────────────────────────────────────────────────────────────────

    public Status status() {
        Integer table = tableDimension();
        Integer configured = configuredDimension();
        boolean mismatch = table != null && configured != null && !table.equals(configured);
        long chunks = count("SELECT COUNT(*) FROM vec_document_chunks");
        long vectors = table == null ? 0 : count("SELECT COUNT(*) FROM vec_embeddings");
        long missing = mismatch ? chunks : Math.max(0, chunks - vectors);
        JobRun job = current.get();
        return new Status(table, configured, mismatch, chunks, vectors, missing,
                job != null && job.state == JobState.RUNNING, job == null ? null : job.snapshot());
    }

    // ── 시작 · 중단 ───────────────────────────────────────────────────────────

    /**
     * 작업을 시작한다(백그라운드). 이미 돌고 있으면 {@link IllegalStateException}, 시작할 수 없는 조건이면
     * {@link IllegalArgumentException}(메시지는 화면에 그대로 보인다).
     */
    public Status start(Mode mode) {
        synchronized (startLock) {
            JobRun running = current.get();
            if (running != null && running.state == JobState.RUNNING) {
                throw new IllegalStateException("이미 진행 중인 임베딩 재생성이 있습니다.");
            }
            Integer configured = configuredDimension();
            if (configured == null || configured <= 0) {
                throw new IllegalArgumentException(
                        "app.embedding.dimensions(EMBED_DIMENSIONS)가 설정돼 있지 않아 벡터 테이블을 만들 수 없습니다.");
            }
            Integer table = tableDimension();
            if (mode == Mode.RESUME && (table == null || !table.equals(configured))) {
                throw new IllegalArgumentException(
                        "벡터 테이블의 차원(" + (table == null ? "없음" : table) + ")이 설정(" + configured
                        + ")과 달라 이어서 할 수 없습니다. '처음부터 전체 재임베딩'을 선택하세요.");
            }
            JobRun job = new JobRun(mode);
            current.set(job);
            int dim = configured;
            Thread.ofVirtual().name("embed-rebuild").start(MdcPropagation.wrap(() -> run(job, dim)));
            log.info("[EMBED-REBUILD] 시작 mode={} 설정 차원={} 테이블 차원={}", mode, dim, table);
        }
        return status();
    }

    /**
     * 중단을 요청한다 — 지금 임베딩 중인 청크 하나까지 끝내고 멈춘다(스레드를 끊지 않는다: 임베딩 호출·SQLite 쓰기 도중에
     * 끊어 반쯤 쓴 상태를 남기지 않으려고). 돌고 있는 작업이 없으면 {@code false}.
     */
    public boolean cancel() {
        JobRun job = current.get();
        if (job == null || job.state != JobState.RUNNING) return false;
        job.cancelRequested = true;
        job.message = "중단을 요청했습니다 — 지금 청크까지 끝내고 멈춥니다.";
        return true;
    }

    // ── 워커 ──────────────────────────────────────────────────────────────────

    private void run(JobRun job, int dim) {
        try {
            if (job.mode == Mode.REBUILD) {
                job.phase = "벡터 테이블을 다시 만드는 중";
                dropAndRecreateVectorTable(dim);
            }
            job.phase = "다시 임베딩할 청크를 찾는 중";
            List<ChunkRef> todo = pendingChunks();
            job.total.set(todo.size());
            job.phase = "임베딩 중";

            Set<Long> curatedHandled = new HashSet<>();
            int consecutiveFailures = 0;
            for (ChunkRef chunk : todo) {
                if (job.cancelRequested) {
                    finish(job, JobState.CANCELLED, "사용자가 중단했습니다. '이어서'로 남은 청크를 계속할 수 있습니다.");
                    return;
                }
                Outcome outcome = reembedOne(chunk, curatedHandled);
                switch (outcome.kind()) {
                    case DONE -> { job.done.incrementAndGet(); consecutiveFailures = 0; }
                    case SKIPPED -> job.skipped.incrementAndGet();
                    case FAILED -> {
                        job.failed.incrementAndGet();
                        consecutiveFailures++;
                        if (job.failures.size() < MAX_FAILURE_SAMPLES) {
                            job.failures.add(new Failure(chunk.id(), chunk.version(), outcome.reason()));
                        }
                        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                            finish(job, JobState.FAILED, "연속 " + MAX_CONSECUTIVE_FAILURES + "개가 실패해 멈췄습니다 — 임베딩 서버·모델 이름·"
                                    + "EMBED_DIMENSIONS 를 확인한 뒤 '이어서'를 누르세요.");
                            return;
                        }
                    }
                }
            }
            String message = todo.isEmpty() ? "다시 임베딩할 청크가 없습니다."
                    : job.failed.get() > 0
                        ? "끝났지만 " + job.failed.get() + "개가 실패했습니다 — 원인을 고친 뒤 '이어서'로 실패한 청크만 다시 할 수 있습니다."
                        : "모든 청크를 다시 임베딩했습니다.";
            finish(job, JobState.DONE, message);
        } catch (Throwable t) {
            log.error("[EMBED-REBUILD] 작업이 예외로 끝났습니다", t);
            finish(job, JobState.FAILED, "오류: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
        }
    }

    private void finish(JobRun job, JobState state, String message) {
        job.state = state;
        job.message = message;
        job.phase = switch (state) {
            case DONE -> "완료";
            case CANCELLED -> "중단됨";
            default -> "실패";
        };
        job.finishedAt = Instant.now();
        log.info("[EMBED-REBUILD] {} — 전체 {} · 성공 {} · 건너뜀 {} · 실패 {} ({})", state, job.total.get(), job.done.get(),
                job.skipped.get(), job.failed.get(), message);
    }

    private enum Kind { DONE, SKIPPED, FAILED }

    private record Outcome(Kind kind, String reason) {
        static Outcome done() { return new Outcome(Kind.DONE, null); }
        static Outcome skipped() { return new Outcome(Kind.SKIPPED, null); }
        static Outcome failed(String reason) { return new Outcome(Kind.FAILED, reason); }
    }

    private Outcome reembedOne(ChunkRef chunk, Set<Long> curatedHandled) {
        try {
            AdminService.ChunkRow row = adminService.getChunk(chunk.version(), chunk.id());
            if (row == null) return Outcome.skipped();             // 그사이 지워졌거나 큐레이션 조각이 정리됐다
            var curatedRow = row.curatedRowId();
            if (curatedRow.isPresent()) {
                // 큐레이션 한 행은 여러 조각(여러 청크 id)이고 reembedRow 가 행 전체를 다시 쓴다 — 첫 조각에서만 부른다.
                if (!curatedHandled.add(curatedRow.getAsLong())) return Outcome.skipped();
                return reembed(chunk, true)
                        ? Outcome.done()
                        : Outcome.failed("큐레이션 행 재임베딩 실패(비활성 행이거나 임베딩 오류) — 서버 로그 확인");
            }
            return reembed(chunk, false)
                    ? Outcome.done()
                    : Outcome.failed("재임베딩 실패 — 서버 로그에서 임베딩 서버·모델·차원을 확인하세요");
        } catch (RuntimeException e) {
            return Outcome.failed(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    // ── 테스트에서 갈아 끼우는 자리 (spy) ─────────────────────────────────────

    /** 청크 하나의 벡터를 쓴다. 큐레이션이면 {@link CuratedQaService#reembedRow}, 아니면 {@link AdminService#reindexChunk}. */
    boolean reembed(ChunkRef chunk, boolean curated) {
        if (curated) {
            var row = adminService.getChunk(chunk.version(), chunk.id());
            return row != null && row.curatedRowId().isPresent()
                    && curatedQaService.reembedRow(row.curatedRowId().getAsLong(), "embedding-rebuild");
        }
        return adminService.reindexChunk(chunk.version(), chunk.id(), false);
    }

    /** 벡터가 없는 청크 — 두 id 집합의 차집합(LEFT JOIN 은 vec0 에서 제곱으로 느리다, 클래스 설명 참고). */
    List<ChunkRef> pendingChunks() {
        Set<String> have = new HashSet<>(jdbc.queryForList("SELECT spring_doc_id FROM vec_embeddings", String.class));
        List<ChunkRef> all = jdbc.query(
                "SELECT spring_doc_id, version FROM vec_document_chunks ORDER BY version, doc_id",
                (rs, i) -> new ChunkRef(rs.getString(1), rs.getString(2)));
        List<ChunkRef> out = new ArrayList<>();
        for (ChunkRef c : all) {
            if (!have.contains(c.id())) out.add(c);
        }
        return out;
    }

    /** 벡터 테이블만 지우고 설정한 차원으로 다시 만든다(청크 본문·FTS·레지스트리는 건드리지 않는다). */
    void dropAndRecreateVectorTable(int dim) {
        jdbc.execute("DROP TABLE IF EXISTS vec_embeddings");
        jdbc.execute(SqliteVecSchemaInitializer.embeddingTableDdl(dim));
    }

    // ── 읽기 보조 ─────────────────────────────────────────────────────────────

    Integer tableDimension() {
        try {
            List<String> rows = jdbc.queryForList(SELECT_EMBEDDING_DDL, String.class);
            return rows == null || rows.isEmpty() ? null : SqliteVecSchemaInitializer.parseExistingDimension(rows.get(0));
        } catch (RuntimeException e) {
            log.warn("[EMBED-REBUILD] vec_embeddings 의 차원을 읽지 못했습니다: {}", e.getMessage());
            return null;
        }
    }

    private Integer configuredDimension() {
        AppProperties.EmbeddingConfig cfg = props.embeddingSafe();
        return cfg == null ? null : cfg.dimensions();
    }

    private long count(String sql) {
        try {
            Long c = jdbc.queryForObject(sql, Long.class);
            return c == null ? 0 : c;
        } catch (RuntimeException e) {
            log.warn("[EMBED-REBUILD] count 실패 [{}]: {}", sql, e.getMessage());
            return 0;
        }
    }
}
