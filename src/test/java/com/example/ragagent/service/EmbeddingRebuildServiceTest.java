package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.service.EmbeddingRebuildService.ChunkRef;
import com.example.ragagent.service.EmbeddingRebuildService.JobState;
import com.example.ragagent.service.EmbeddingRebuildService.Mode;
import com.example.ragagent.service.EmbeddingRebuildService.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link EmbeddingRebuildService} — 작업 흐름(성공·실패·연속 실패 중단·중단 요청·큐레이션 조각·동시 시작 거절)과 시작 조건을 가른다.
 * 실제 vec0 테이블이 필요한 부분(차집합 조회·DROP/CREATE)은 spy 로 갈아 끼우고, 그쪽은 vec0 가 있는 환경의
 * {@code EmbeddingRebuildIntegrationTest}(-Dsqlitevec.path)가 맡는다.
 */
class EmbeddingRebuildServiceTest {

    private JdbcTemplate jdbc;
    private AdminService admin;
    private CuratedQaService curated;
    private EmbeddingRebuildService svc;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        admin = mock(AdminService.class);
        curated = mock(CuratedQaService.class);
        svc = spy(new EmbeddingRebuildService(jdbc, propsWithDim(1024), admin, curated));
        doNothing().when(svc).dropAndRecreateVectorTable(anyInt());
    }

    private static AppProperties propsWithDim(Integer dim) {
        AppProperties props = mock(AppProperties.class);
        when(props.embeddingSafe())
                .thenReturn(new AppProperties.EmbeddingConfig(null, null, null, dim, 10, 120, true, 0, List.of(), 1));
        return props;
    }

    private static AdminService.ChunkRow docRow(String id) {
        return new AdminService.ChunkRow(id, "p", "text", Map.of(MetaKey.DOC_ID, "doc.md_1"));
    }

    private static AdminService.ChunkRow curatedRow(String id, long rowId) {
        return new AdminService.ChunkRow(id, "p", "text", Map.of(
                MetaKey.DOC_TYPE, "curated_qa", MetaKey.DOC_ID, CuratedQaService.curatedDocId(rowId)));
    }

    private void pending(ChunkRef... chunks) {
        doReturn(List.of(chunks)).when(svc).pendingChunks();
        for (ChunkRef c : chunks) {
            when(admin.getChunk(c.version(), c.id())).thenReturn(docRow(c.id()));
            when(admin.reindexChunk(c.version(), c.id(), false)).thenReturn(true);
        }
    }

    private Status awaitFinished() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Status s = svc.status();
            if (!s.running() && s.job() != null) return s;
            Thread.sleep(10);
        }
        throw new AssertionError("작업이 10초 안에 끝나지 않았다: " + svc.status());
    }

    // ── 상태 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("status — 같은 차원이면 벡터 없는 청크 = 청크 수 − 벡터 수, 불일치는 아님")
    void statusCounts() {
        doReturn(1024).when(svc).tableDimension();
        when(jdbc.queryForObject("SELECT COUNT(*) FROM vec_document_chunks", Long.class)).thenReturn(10L);
        when(jdbc.queryForObject("SELECT COUNT(*) FROM vec_embeddings", Long.class)).thenReturn(4L);

        Status s = svc.status();

        assertThat(s.dimensionMismatch()).isFalse();
        assertThat(s.totalChunks()).isEqualTo(10);
        assertThat(s.vectorCount()).isEqualTo(4);
        assertThat(s.missingVectors()).isEqualTo(6);
        assertThat(s.running()).isFalse();
        assertThat(s.job()).isNull();
    }

    @Test
    @DisplayName("status — 테이블 차원이 설정과 다르면 불일치, 옛 벡터는 못 쓰므로 청크 전부가 '없는' 것")
    void statusMismatch() {
        doReturn(768).when(svc).tableDimension();
        when(jdbc.queryForObject("SELECT COUNT(*) FROM vec_document_chunks", Long.class)).thenReturn(10L);
        when(jdbc.queryForObject("SELECT COUNT(*) FROM vec_embeddings", Long.class)).thenReturn(10L);

        Status s = svc.status();

        assertThat(s.dimensionMismatch()).isTrue();
        assertThat(s.tableDimension()).isEqualTo(768);
        assertThat(s.configuredDimension()).isEqualTo(1024);
        assertThat(s.missingVectors()).isEqualTo(10);
    }

    // ── 시작 조건 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("RESUME — 테이블 차원이 설정과 다르면 받지 않고 '처음부터'를 안내한다")
    void resumeRejectedOnMismatch() {
        doReturn(768).when(svc).tableDimension();
        assertThatThrownBy(() -> svc.start(Mode.RESUME))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("768").hasMessageContaining("1024").hasMessageContaining("처음부터");
        verify(svc, never()).dropAndRecreateVectorTable(anyInt());
    }

    @Test
    @DisplayName("RESUME — 테이블이 없어도(차원을 읽을 수 없음) 받지 않는다")
    void resumeRejectedWithoutTable() {
        doReturn(null).when(svc).tableDimension();
        assertThatThrownBy(() -> svc.start(Mode.RESUME)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("설정 차원이 없으면 REBUILD 도 시작하지 않는다 — 어떤 차원으로 만들지 모른다")
    void noConfiguredDimension() {
        EmbeddingRebuildService noDim = spy(new EmbeddingRebuildService(jdbc, propsWithDim(null), admin, curated));
        assertThatThrownBy(() -> noDim.start(Mode.REBUILD))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("EMBED_DIMENSIONS");
    }

    // ── 작업 흐름 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("REBUILD — 설정 차원으로 테이블을 다시 만든 뒤 모든 청크를 같은 id 로 재임베딩(키워드 재생성 없음)")
    void rebuildProcessesEverything() throws Exception {
        doReturn(768).when(svc).tableDimension();
        pending(new ChunkRef("a", "v1"), new ChunkRef("b", "v1"), new ChunkRef("c", "v2"));

        svc.start(Mode.REBUILD);
        Status s = awaitFinished();

        verify(svc).dropAndRecreateVectorTable(1024);
        verify(admin).reindexChunk("v1", "a", false);
        verify(admin).reindexChunk("v1", "b", false);
        verify(admin).reindexChunk("v2", "c", false);
        assertThat(s.job().state()).isEqualTo(JobState.DONE);
        assertThat(s.job().total()).isEqualTo(3);
        assertThat(s.job().done()).isEqualTo(3);
        assertThat(s.job().failed()).isZero();
        assertThat(s.job().message()).contains("모든 청크");
        assertThat(s.job().finishedAt()).isNotNull();
    }

    @Test
    @DisplayName("RESUME — 테이블은 건드리지 않고 벡터 없는 청크만 처리한다")
    void resumeDoesNotDropTable() throws Exception {
        doReturn(1024).when(svc).tableDimension();
        pending(new ChunkRef("a", "v1"));

        svc.start(Mode.RESUME);
        Status s = awaitFinished();

        verify(svc, never()).dropAndRecreateVectorTable(anyInt());
        assertThat(s.job().state()).isEqualTo(JobState.DONE);
        assertThat(s.job().done()).isEqualTo(1);
    }

    @Test
    @DisplayName("청크가 없으면 바로 끝난다")
    void nothingToDo() throws Exception {
        doReturn(1024).when(svc).tableDimension();
        pending();

        svc.start(Mode.RESUME);
        Status s = awaitFinished();

        assertThat(s.job().state()).isEqualTo(JobState.DONE);
        assertThat(s.job().total()).isZero();
        assertThat(s.job().message()).contains("없습니다");
    }

    @Test
    @DisplayName("실패한 청크는 세고 예시로 남기며 나머지는 계속한다 — 끝 메시지가 '이어서'를 안내")
    void failuresAreCountedAndWorkContinues() throws Exception {
        doReturn(1024).when(svc).tableDimension();
        pending(new ChunkRef("a", "v1"), new ChunkRef("bad", "v1"), new ChunkRef("c", "v1"));
        when(admin.reindexChunk("v1", "bad", false)).thenReturn(false);

        svc.start(Mode.RESUME);
        Status s = awaitFinished();

        assertThat(s.job().state()).isEqualTo(JobState.DONE);
        assertThat(s.job().done()).isEqualTo(2);
        assertThat(s.job().failed()).isEqualTo(1);
        assertThat(s.job().failures()).singleElement().satisfies(f -> {
            assertThat(f.id()).isEqualTo("bad");
            assertThat(f.version()).isEqualTo("v1");
            assertThat(f.reason()).isNotBlank();
        });
        assertThat(s.job().message()).contains("1개가 실패").contains("이어서");
    }

    @Test
    @DisplayName("예외도 그 청크의 실패일 뿐 작업을 죽이지 않는다 — 사유에 예외 메시지")
    void exceptionIsAFailureNotACrash() throws Exception {
        doReturn(1024).when(svc).tableDimension();
        pending(new ChunkRef("boom", "v1"), new ChunkRef("ok", "v1"));
        when(admin.reindexChunk("v1", "boom", false)).thenThrow(new IllegalStateException("임베딩 서버 응답 없음"));

        svc.start(Mode.RESUME);
        Status s = awaitFinished();

        assertThat(s.job().state()).isEqualTo(JobState.DONE);
        assertThat(s.job().failures().get(0).reason()).contains("임베딩 서버 응답 없음");
        verify(admin).reindexChunk("v1", "ok", false);
    }

    @Test
    @DisplayName("연속 20개가 실패하면 서버 문제로 보고 멈춘다 — 나머지 청크는 건드리지 않는다")
    void abortsAfterConsecutiveFailures() throws Exception {
        doReturn(1024).when(svc).tableDimension();
        List<ChunkRef> all = new ArrayList<>();
        for (int i = 0; i < 30; i++) all.add(new ChunkRef("c" + i, "v1"));
        pending(all.toArray(new ChunkRef[0]));
        for (ChunkRef c : all) when(admin.reindexChunk(c.version(), c.id(), false)).thenReturn(false);

        svc.start(Mode.RESUME);
        Status s = awaitFinished();

        assertThat(s.job().state()).isEqualTo(JobState.FAILED);
        assertThat(s.job().failed()).isEqualTo(EmbeddingRebuildService.MAX_CONSECUTIVE_FAILURES);
        assertThat(s.job().failures()).hasSize(EmbeddingRebuildService.MAX_FAILURE_SAMPLES);
        assertThat(s.job().message()).contains("연속").contains("이어서");
        verify(admin, times(EmbeddingRebuildService.MAX_CONSECUTIVE_FAILURES))
                .reindexChunk(anyString(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("성공이 사이에 끼면 연속 실패 횟수는 0 으로 돌아간다")
    void successResetsTheConsecutiveCounter() throws Exception {
        doReturn(1024).when(svc).tableDimension();
        List<ChunkRef> all = new ArrayList<>();
        for (int i = 0; i < 45; i++) all.add(new ChunkRef("c" + i, "v1"));
        pending(all.toArray(new ChunkRef[0]));
        for (int i = 0; i < all.size(); i++) {
            // 19개 실패 → 1개 성공 → 19개 실패 → 1개 성공 → 5개 실패: 연속 20 에 한 번도 닿지 않는다
            boolean ok = (i % 20) == 19;
            when(admin.reindexChunk("v1", "c" + i, false)).thenReturn(ok);
        }

        svc.start(Mode.RESUME);
        Status s = awaitFinished();

        assertThat(s.job().state()).isEqualTo(JobState.DONE);
        assertThat(s.job().failed()).isEqualTo(43);
    }

    @Test
    @DisplayName("큐레이션 — 한 행의 여러 조각은 첫 조각에서 reembedRow 한 번, 나머지는 건너뛴다")
    void curatedRowIsReembeddedOnce() throws Exception {
        doReturn(1024).when(svc).tableDimension();
        doReturn(List.of(new ChunkRef("curated-7", "curated"), new ChunkRef("curated-7-1", "curated"),
                new ChunkRef("curated-9", "curated"))).when(svc).pendingChunks();
        when(admin.getChunk("curated", "curated-7")).thenReturn(curatedRow("curated-7", 7));
        when(admin.getChunk("curated", "curated-7-1")).thenReturn(curatedRow("curated-7-1", 7));
        when(admin.getChunk("curated", "curated-9")).thenReturn(curatedRow("curated-9", 9));
        when(curated.reembedRow(anyLong(), anyString())).thenReturn(true);

        svc.start(Mode.RESUME);
        Status s = awaitFinished();

        verify(curated).reembedRow(eq(7L), anyString());
        verify(curated).reembedRow(eq(9L), anyString());
        verify(curated, times(2)).reembedRow(anyLong(), anyString());
        verify(admin, never()).reindexChunk(anyString(), anyString(), anyBoolean());   // 문서 청크 경로를 태우면 질문이 빠진다
        assertThat(s.job().done()).isEqualTo(2);
        assertThat(s.job().skipped()).isEqualTo(1);
        assertThat(s.job().failed()).isZero();
    }

    @Test
    @DisplayName("그사이 사라진 청크는 실패가 아니라 건너뜀")
    void vanishedChunkIsSkipped() throws Exception {
        doReturn(1024).when(svc).tableDimension();
        doReturn(List.of(new ChunkRef("gone", "v1"))).when(svc).pendingChunks();
        when(admin.getChunk("v1", "gone")).thenReturn(null);

        svc.start(Mode.RESUME);
        Status s = awaitFinished();

        assertThat(s.job().skipped()).isEqualTo(1);
        assertThat(s.job().failed()).isZero();
        assertThat(s.job().state()).isEqualTo(JobState.DONE);
    }

    // ── 동시성 · 중단 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("돌고 있는 작업이 있으면 새로 시작할 수 없다 — 앱 전체에서 하나")
    void secondStartIsRejectedWhileRunning() throws Exception {
        doReturn(1024).when(svc).tableDimension();
        pending(new ChunkRef("a", "v1"));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(admin.reindexChunk("v1", "a", false)).thenAnswer(inv -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return true;
        });

        svc.start(Mode.RESUME);
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(svc.status().running()).isTrue();

        assertThatThrownBy(() -> svc.start(Mode.RESUME)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> svc.start(Mode.REBUILD)).isInstanceOf(IllegalStateException.class);

        release.countDown();
        assertThat(awaitFinished().job().state()).isEqualTo(JobState.DONE);
        // 끝난 뒤에는 다시 시작할 수 있다
        svc.start(Mode.RESUME);
        awaitFinished();
    }

    @Test
    @DisplayName("중단 요청 — 지금 청크까지 끝내고 멈추며 나머지는 건드리지 않는다(스레드를 끊지 않는다)")
    void cancelStopsAfterTheCurrentChunk() throws Exception {
        doReturn(1024).when(svc).tableDimension();
        pending(new ChunkRef("a", "v1"), new ChunkRef("b", "v1"), new ChunkRef("c", "v1"));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(admin.reindexChunk("v1", "a", false)).thenAnswer(inv -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return true;
        });

        svc.start(Mode.RESUME);
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(svc.cancel()).isTrue();
        assertThat(svc.status().job().message()).contains("중단을 요청");
        release.countDown();
        Status s = awaitFinished();

        assertThat(s.job().state()).isEqualTo(JobState.CANCELLED);
        assertThat(s.job().done()).isEqualTo(1);        // 하던 청크는 끝냈다
        verify(admin, never()).reindexChunk("v1", "b", false);
        verify(admin, never()).reindexChunk("v1", "c", false);
        assertThat(s.job().message()).contains("이어서");
    }

    @Test
    @DisplayName("돌고 있는 작업이 없으면 cancel 은 false")
    void cancelWithoutJob() {
        assertThat(svc.cancel()).isFalse();
    }

    @Test
    @DisplayName("테이블 재생성이 실패하면 작업이 FAILED 로 끝나고 청크는 건드리지 않는다")
    void dropFailureFailsTheJob() throws Exception {
        doReturn(768).when(svc).tableDimension();
        org.mockito.Mockito.doThrow(new IllegalStateException("no such module: vec0"))
                .when(svc).dropAndRecreateVectorTable(anyInt());
        pending(new ChunkRef("a", "v1"));

        svc.start(Mode.REBUILD);
        Status s = awaitFinished();

        assertThat(s.job().state()).isEqualTo(JobState.FAILED);
        assertThat(s.job().message()).contains("no such module");
        verify(admin, never()).reindexChunk(anyString(), anyString(), anyBoolean());
    }
}
