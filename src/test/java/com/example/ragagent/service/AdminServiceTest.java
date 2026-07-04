package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.config.AppProperties.EmbeddingConfig;
import com.example.ragagent.config.AppProperties.VectorStoreConfig;
import com.example.ragagent.model.VectorStoreAdminView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AdminService}가 백엔드 독립적으로 동작하는지 검증한다: chroma 모드에서 {@code ChromaApi}
 * 부재 시 graceful 강등, sqlite-vec 모드에서 {@code JdbcTemplate} 기반 상태 집계·청크 브라우징,
 * 그리고 {@link AdminService#vectorStoreView()}의 백엔드별 집계.
 */
class AdminServiceTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private AdminService chromaless() {
        return new AdminService(Optional.empty(), mock(JdbcTemplate.class), mock(AppProperties.class), OM);
    }

    @Test
    @DisplayName("ChromaApi 없음(chroma 모드): listCollections → available=false, 조회는 빈 결과, 변경은 no-op")
    void noChromaApi_degradesGracefully() {
        AdminService svc = chromaless();  // props mock → vectorStoreSafe() null → chroma 경로

        assertThat(svc.listCollections().available()).isFalse();
        assertThat(svc.listCollections().items()).isEmpty();
        assertThat(svc.getChunks("c", null, 0, 10)).isEmpty();
        assertThat(svc.getChunk("c", "id")).isNull();
        assertThat(svc.countChunks("c", null)).isZero();
        assertThatCode(() -> svc.deleteChunk("c", "id")).doesNotThrowAnyException();
        assertThatCode(() -> svc.updateChunk("c", "id", "t", Map.of())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ChromaApi 있음(chroma): listCollections가 ChromaApi에 위임")
    void withChromaApi_delegates() {
        ChromaApi api = mock(ChromaApi.class);
        when(api.listCollections(anyString(), anyString())).thenReturn(List.of());
        AdminService svc = new AdminService(Optional.of(api), mock(JdbcTemplate.class), mock(AppProperties.class), OM);

        AdminService.CollectionsResult r = svc.listCollections();

        assertThat(r.available()).isTrue();
        verify(api).listCollections(anyString(), anyString());
    }

    @Test
    @DisplayName("ChromaApi 없음: deleteChunk가 ChromaApi 접근 시도조차 안 함")
    void noChromaApi_deleteDoesNotTouchApi() {
        AdminService svc = chromaless();
        assertThatCode(() -> svc.deleteChunk("c", "id")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("vectorStoreView(sqlite-vec): JdbcTemplate 집계로 vec_version·문서/청크 수·버전별 집계 노출")
    @SuppressWarnings("unchecked")
    void vectorStoreView_sqliteVec() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT vec_version()", String.class)).thenReturn("v0.1.9");
        when(jdbc.queryForObject("SELECT COUNT(*) FROM vec_document_chunks", Long.class)).thenReturn(42L);
        when(jdbc.queryForObject("SELECT COUNT(DISTINCT doc_id) FROM vec_document_chunks", Long.class)).thenReturn(5L);
        when(jdbc.query(anyString(), any(RowMapper.class)))
                .thenReturn(List.of(new VectorStoreAdminView.VersionCount("latest", 42L)));

        AppProperties props = mock(AppProperties.class);
        when(props.vectorStoreSafe()).thenReturn(new VectorStoreConfig("sqlite-vec"));
        when(props.embeddingSafe()).thenReturn(new EmbeddingConfig(null, null, null, 768, 10, 120, true));

        AdminService svc = new AdminService(Optional.empty(), jdbc, props, OM);
        VectorStoreAdminView v = svc.vectorStoreView();

        assertThat(v.isSqliteVec()).isTrue();
        assertThat(v.healthy()).isTrue();
        assertThat(v.vecVersion()).isEqualTo("v0.1.9");
        assertThat(v.dimension()).isEqualTo(768);
        assertThat(v.totalChunks()).isEqualTo(42L);
        assertThat(v.totalDocs()).isEqualTo(5L);
        assertThat(v.hasDocCount()).isTrue();
        assertThat(v.perVersion()).singleElement().satisfies(pv -> {
            assertThat(pv.version()).isEqualTo("latest");
            assertThat(pv.chunkCount()).isEqualTo(42L);
        });
        assertThat(v.collectionCount()).isNull();
    }

    @Test
    @DisplayName("vectorStoreView(chroma): 컬렉션 집계 재사용, 문서 수는 unknown(-1)")
    void vectorStoreView_chroma() {
        ChromaApi api = mock(ChromaApi.class);
        when(api.listCollections(anyString(), anyString())).thenReturn(List.of());

        AppProperties props = mock(AppProperties.class);
        when(props.vectorStoreSafe()).thenReturn(new VectorStoreConfig("chroma"));

        AdminService svc = new AdminService(Optional.of(api), mock(JdbcTemplate.class), props, OM);
        VectorStoreAdminView v = svc.vectorStoreView();

        assertThat(v.isChroma()).isTrue();
        assertThat(v.healthy()).isTrue();          // listCollections returned non-null
        assertThat(v.collectionCount()).isZero();
        assertThat(v.totalChunks()).isZero();
        assertThat(v.hasDocCount()).isFalse();      // totalDocs == -1
        assertThat(v.vecVersion()).isNull();
    }

    @Test
    @DisplayName("sqlite-vec: listCollections가 version 그룹을 pseudo-collection으로 반환(ChromaApi 미사용)")
    @SuppressWarnings("unchecked")
    void sqliteVec_listCollections_groupsByVersion() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class)))
                .thenReturn(List.of(new AdminService.CollectionSummary("latest", "latest", "latest", 7L)));

        AppProperties props = mock(AppProperties.class);
        when(props.vectorStoreSafe()).thenReturn(new VectorStoreConfig("sqlite-vec"));

        AdminService svc = new AdminService(Optional.empty(), jdbc, props, OM);
        AdminService.CollectionsResult r = svc.listCollections();

        assertThat(r.available()).isTrue();
        assertThat(r.items()).singleElement().satisfies(c -> {
            assertThat(c.version()).isEqualTo("latest");
            assertThat(c.chunkCount()).isEqualTo(7L);
        });
    }

    @Test
    @DisplayName("sqlite-vec: deleteChunk가 vec_document_chunks와 vec_embeddings 두 테이블 모두 삭제")
    void sqliteVec_deleteChunk_deletesBothTables() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AppProperties props = mock(AppProperties.class);
        when(props.vectorStoreSafe()).thenReturn(new VectorStoreConfig("sqlite-vec"));

        AdminService svc = new AdminService(Optional.empty(), jdbc, props, OM);
        svc.deleteChunk("latest", "doc1::0");

        verify(jdbc).update(eq("DELETE FROM vec_document_chunks WHERE spring_doc_id = ?"), eq("doc1::0"));
        verify(jdbc).update(eq("DELETE FROM vec_embeddings WHERE spring_doc_id = ?"), eq("doc1::0"));
    }
}
