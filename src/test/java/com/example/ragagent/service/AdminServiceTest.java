package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.config.AppProperties.EmbeddingConfig;
import com.example.ragagent.config.AppProperties.VectorStoreConfig;
import com.example.ragagent.model.VectorStoreAdminView;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AdminService}가 백엔드 독립적으로 동작하는지 검증한다: sqlite-vec 모드에서
 * {@code ChromaApi} 빈이 없어도({@code Optional.empty}) 청크 조회/삭제가 NPE 없이 graceful하게 동작하고,
 * {@link AdminService#vectorStoreView()}가 백엔드별로 올바른 상태를 집계해야 한다 (Step 5.8).
 */
class AdminServiceTest {

    private AdminService chromaless() {
        return new AdminService(Optional.empty(), mock(JdbcTemplate.class), mock(AppProperties.class));
    }

    @Test
    @DisplayName("ChromaApi 없음(sqlite-vec): listCollections → available=false, 조회는 빈 결과, 변경은 no-op")
    void noChromaApi_degradesGracefully() {
        AdminService svc = chromaless();

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
        AdminService svc = new AdminService(Optional.of(api), mock(JdbcTemplate.class), mock(AppProperties.class));

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
        when(props.embeddingSafe()).thenReturn(new EmbeddingConfig(null, null, null, 768, 10, 120));

        AdminService svc = new AdminService(Optional.empty(), jdbc, props);
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

        AdminService svc = new AdminService(Optional.of(api), mock(JdbcTemplate.class), props);
        VectorStoreAdminView v = svc.vectorStoreView();

        assertThat(v.isChroma()).isTrue();
        assertThat(v.healthy()).isTrue();          // listCollections returned non-null
        assertThat(v.collectionCount()).isZero();
        assertThat(v.totalChunks()).isZero();
        assertThat(v.hasDocCount()).isFalse();      // totalDocs == -1
        assertThat(v.vecVersion()).isNull();
    }
}
