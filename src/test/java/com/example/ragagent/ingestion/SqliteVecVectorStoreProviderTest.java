package com.example.ragagent.ingestion;

import com.example.ragagent.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Step 5.4 — 순수 헬퍼 + empty/delete 경로 단위 테스트.
 * 실제 KNN/add/search/version 필터/멱등은 네이티브 vec0가 필요해 PoC로 검증한다.
 */
class SqliteVecVectorStoreProviderTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);

    private SqliteVecVectorStoreProvider provider() {
        AppProperties props = mock(AppProperties.class);
        when(props.searchSimilarityThresholdSafe()).thenReturn(0.0);
        return new SqliteVecVectorStoreProvider(jdbc, embeddingModel, new ObjectMapper(), props);
    }

    @Test
    @DisplayName("toVectorLiteral: float[] → sqlite-vec JSON 텍스트")
    void vectorLiteral() {
        assertThat(SqliteVecVectorStoreProvider.toVectorLiteral(new float[]{1.0f, -2.5f, 0.0f}))
                .isEqualTo("[1.0,-2.5,0.0]");
        assertThat(SqliteVecVectorStoreProvider.toVectorLiteral(new float[]{})).isEqualTo("[]");
        assertThat(SqliteVecVectorStoreProvider.toVectorLiteral(new float[]{42.0f})).isEqualTo("[42.0]");
    }

    @Test
    @DisplayName("searchBatch(빈 쿼리): 임베딩 호출 없이 빈 리스트")
    void searchBatchEmpty() {
        assertThat(provider().searchBatch("u", List.of(), "v1", 7)).isEmpty();
        assertThat(provider().searchBatch("u", null, "v1", 7)).isEmpty();
        verifyNoInteractions(embeddingModel);
        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("add(빈 docs): 임베딩·DB 호출 없음")
    void addEmpty() {
        provider().add("u", "v1", List.of());
        verifyNoInteractions(embeddingModel);
        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("deleteByDocIds(빈): DB 호출 없음")
    void deleteEmpty() {
        provider().deleteByDocIds("u", "v1", List.of());
        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("deleteByDocIds: 두 테이블에서 IN 절로 삭제")
    void deleteBothTables() {
        provider().deleteByDocIds("u", "v1", List.of("a", "b"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).update(sql.capture(), eq("a"), eq("b"));
        List<String> stmts = sql.getAllValues();
        assertThat(stmts.get(0)).contains("DELETE FROM vec_embeddings").contains("IN (?,?)");
        assertThat(stmts.get(1)).contains("DELETE FROM vec_document_chunks").contains("IN (?,?)");
    }
}
