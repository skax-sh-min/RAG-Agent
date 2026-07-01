package com.example.ragagent.ingestion;

import com.example.ragagent.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link SqliteVecVectorStoreProvider} 순수 헬퍼 + empty/delete 경로 단위 테스트.
 * 실제 KNN/add/search/version 필터/멱등은 네이티브 vec0가 필요해 통합 테스트로 검증한다.
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
    @DisplayName("add: 배치 임베딩 token limit 초과 시 개별 축소 재시도로 복구")
    void addFallsBackWhenEmbeddingInputTooLarge() {
        SqliteVecVectorStoreProvider p = provider();
        Document doc = Document.builder()
                .id("d1")
                .text("x".repeat(220))
                .metadata(java.util.Map.of("doc_id", "doc-1"))
                .build();

        when(embeddingModel.embed(anyList()))
                .thenThrow(new RuntimeException("input (515 tokens) is too large to process. increase the physical batch size (current batch size: 512)"));
        when(embeddingModel.embed(anyString())).thenAnswer(invocation -> {
            String s = invocation.getArgument(0, String.class);
            if (s.length() > 140) {
                throw new RuntimeException("too large to process");
            }
            return new float[]{0.1f, 0.2f};
        });

        p.add("u", "v1", List.of(doc));

        verify(embeddingModel, times(1)).embed(anyList());
        verify(embeddingModel, atLeast(2)).embed(anyString());
        verify(jdbc, times(1)).batchUpdate(eq("INSERT INTO vec_embeddings(spring_doc_id, version, embedding) VALUES (?, ?, ?)"), any(org.springframework.jdbc.core.BatchPreparedStatementSetter.class));
        verify(jdbc, times(1)).batchUpdate(eq("INSERT INTO vec_document_chunks(spring_doc_id, content, metadata, version, doc_id, created_at) VALUES (?, ?, ?, ?, ?, ?)"), anyList());
    }

    @Test
    @DisplayName("add: token-limit 오류가 아니면 즉시 예외 전파")
    void addPropagatesNonTokenLimitError() {
        SqliteVecVectorStoreProvider p = provider();
        Document doc = Document.builder()
                .id("d1")
                .text("hello")
                .metadata(java.util.Map.of())
                .build();

        when(embeddingModel.embed(anyList()))
                .thenThrow(new RuntimeException("connection reset"));

        assertThatThrownBy(() -> p.add("u", "v1", List.of(doc)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("connection reset");
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
