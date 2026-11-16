package com.example.ragagent.service;

import com.example.ragagent.SqliteTestDatabase;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.config.SqliteVecSchemaInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 진짜 vec0 위에서 {@link EmbeddingRebuildService} 의 DB 쪽(차원 읽기 · 차집합 조회 · DROP/CREATE · 작업 한 바퀴)을 확인한다.
 * {@code -Dsqlitevec.path=/path/to/vec0} 가 있을 때만 돈다({@code SqliteVecIntegrationTest} 와 같은 규약 — 없으면 skip).
 * 임베딩 호출 자리({@code AdminService.reindexChunk})만 가짜이고, 가짜는 진짜처럼 <b>새 차원의 벡터를 vec_embeddings 에 쓴다</b> —
 * 그래서 끝난 뒤 "벡터 없는 청크 0" 이 실제 테이블에서 성립하는지를 본다.
 */
@EnabledIfSystemProperty(named = "sqlitevec.path", matches = ".+")
class EmbeddingRebuildIntegrationTest {

    @TempDir Path dir;

    private Connection conn;
    private JdbcTemplate jdbc;

    @BeforeEach
    void open() throws Exception {
        Properties p = new Properties();
        p.setProperty("enable_load_extension", "true");
        conn = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("it.db").toString().replace('\\', '/'), p);
        try (Statement st = conn.createStatement()) {
            st.execute("SELECT load_extension('" + System.getProperty("sqlitevec.path").replace('\\', '/') + "')");
        }
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(conn, true));
        SqliteTestDatabase.createSearchIndexTables(jdbc);            // vec_document_chunks (앱과 같은 DDL)
        jdbc.execute(SqliteVecSchemaInitializer.embeddingTableDdl(4));
        for (int i = 0; i < 5; i++) {
            jdbc.update("INSERT INTO vec_document_chunks(spring_doc_id, content, metadata, version, doc_id, created_at) "
                    + "VALUES (?, 't', '{}', ?, ?, '2026-01-01T00:00:00Z')", "c" + i, i < 4 ? "v1" : "v2", "d" + (i / 2));
        }
        // 옛 모델(4차원)로 임베딩된 청크 둘
        insertVector("c0", "v1", 4);
        insertVector("c1", "v1", 4);
    }

    @AfterEach
    void close() throws Exception {
        conn.close();
    }

    private void insertVector(String id, String version, int dim) {
        ByteBuffer b = ByteBuffer.allocate(dim * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < dim; i++) b.putFloat(0.1f);
        jdbc.update("INSERT INTO vec_embeddings(spring_doc_id, version, embedding) VALUES (?, ?, ?)", id, version, b.array());
    }

    private static AppProperties propsWithDim(int dim) {
        AppProperties props = mock(AppProperties.class);
        when(props.embeddingSafe())
                .thenReturn(new AppProperties.EmbeddingConfig(null, null, null, dim, 10, 120, true, 0, List.of(), 1));
        return props;
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    @Test
    @DisplayName("차원 읽기 · 차집합 · 상태 — 같은 차원이면 벡터 없는 청크 3개(5 − 2)")
    void statusAndPendingOnRealVec0() {
        EmbeddingRebuildService svc = new EmbeddingRebuildService(jdbc, propsWithDim(4), mock(AdminService.class), mock(CuratedQaService.class));

        assertThat(svc.tableDimension()).isEqualTo(4);
        var s = svc.status();
        assertThat(s.dimensionMismatch()).isFalse();
        assertThat(s.totalChunks()).isEqualTo(5);
        assertThat(s.vectorCount()).isEqualTo(2);
        assertThat(s.missingVectors()).isEqualTo(3);
        assertThat(svc.pendingChunks()).extracting(EmbeddingRebuildService.ChunkRef::id).containsExactly("c2", "c3", "c4");
    }

    @Test
    @DisplayName("DROP/CREATE — 새 차원으로 다시 만들고 청크 본문은 그대로, 옛 벡터는 사라진다")
    void dropAndRecreateOnRealVec0() {
        EmbeddingRebuildService svc = new EmbeddingRebuildService(jdbc, propsWithDim(8), mock(AdminService.class), mock(CuratedQaService.class));

        assertThat(svc.status().dimensionMismatch()).isTrue();      // 테이블 4 ↔ 설정 8
        svc.dropAndRecreateVectorTable(8);

        assertThat(svc.tableDimension()).isEqualTo(8);
        assertThat(svc.status().dimensionMismatch()).isFalse();
        assertThat(count("vec_embeddings")).isZero();
        assertThat(count("vec_document_chunks")).isEqualTo(5);
        assertThat(svc.pendingChunks()).hasSize(5);
    }

    @Test
    @DisplayName("REBUILD 한 바퀴 — 테이블을 8차원으로 다시 만들고, 모든 청크에 새 벡터가 생겨 '없는 청크'가 0 이 된다")
    void fullRebuildOnRealVec0() throws Exception {
        AdminService admin = mock(AdminService.class);
        when(admin.getChunk(anyString(), anyString())).thenAnswer(inv ->
                new AdminService.ChunkRow(inv.getArgument(1), "p", "t", Map.of()));
        // 진짜 reindexChunk 처럼: 같은 id 로 새 차원 벡터를 쓴다(옛 행이 있으면 지우고)
        when(admin.reindexChunk(anyString(), anyString(), anyBoolean())).thenAnswer(inv -> {
            String id = inv.getArgument(1);
            jdbc.update("DELETE FROM vec_embeddings WHERE spring_doc_id = ?", id);
            insertVector(id, inv.getArgument(0), 8);
            return true;
        });
        EmbeddingRebuildService svc = new EmbeddingRebuildService(jdbc, propsWithDim(8), admin, mock(CuratedQaService.class));

        svc.start(EmbeddingRebuildService.Mode.REBUILD);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (svc.status().running() && System.nanoTime() < deadline) Thread.sleep(20);

        var s = svc.status();
        assertThat(s.job().state()).isEqualTo(EmbeddingRebuildService.JobState.DONE);
        assertThat(s.job().total()).isEqualTo(5);
        assertThat(s.job().done()).isEqualTo(5);
        assertThat(s.tableDimension()).isEqualTo(8);
        assertThat(s.vectorCount()).isEqualTo(5);
        assertThat(s.missingVectors()).isZero();
        assertThat(svc.pendingChunks()).isEmpty();
    }
}
