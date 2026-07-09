package com.example.ragagent.ingestion;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.exception.VectorStoreException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.SocketTimeoutException;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

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
            @DisplayName("add: token-limit 축소 재시도 소진 시 VectorStoreException으로 변환")
            void addWrapsTooLargeErrorAfterRetriesExhausted() {
            SqliteVecVectorStoreProvider p = provider();
            Document doc = Document.builder()
                .id("d1")
                .text("x".repeat(3000))
                .metadata(java.util.Map.of())
                .build();

            when(embeddingModel.embed(anyList()))
                .thenThrow(new RuntimeException("input (1123 tokens) is too large to process. increase the physical batch size (current batch size: 512)"));
            when(embeddingModel.embed(anyString()))
                .thenThrow(new RuntimeException("input (1123 tokens) is too large to process. increase the physical batch size (current batch size: 512)"));

            assertThatThrownBy(() -> p.add("u", "v1", List.of(doc)))
                .isInstanceOf(VectorStoreException.class)
                .hasMessageContaining("모델 제한을 초과");
            }

    @Test
    @DisplayName("add: 대용량 문서는 여러 배치로 나눠 임베딩되고, 결과가 올바른 doc id에 매핑된다")
    void addMapsEmbeddingsToCorrectDocIdAcrossBatches() {
        SqliteVecVectorStoreProvider p = provider();
        // ~20,000 chars each stays well under TokenCountBatchingStrategy's default per-document
        // cap (8191 tokens, ~4.1-4.75 chars/token observed for repeated ASCII text), but the two
        // combined exceed the 90%-reserved batch cap (~7371 tokens), forcing separate batches.
        Document doc1 = Document.builder().id("d1").text("a".repeat(20_000)).metadata(Map.of()).build();
        Document doc2 = Document.builder().id("d2").text("b".repeat(20_000)).metadata(Map.of()).build();

        when(embeddingModel.embed(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return texts.stream()
                    .map(t -> t.startsWith("a") ? new float[]{1f} : new float[]{2f})
                    .toList();
        });

        p.add("u", "v1", List.of(doc1, doc2));

        verify(embeddingModel, atLeast(2)).embed(anyList());

        ArgumentCaptor<BatchPreparedStatementSetter> captor = ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbc).batchUpdate(
                eq("INSERT INTO vec_embeddings(spring_doc_id, version, embedding) VALUES (?, ?, ?)"),
                captor.capture());
        BatchPreparedStatementSetter setter = captor.getValue();

        try {
            PreparedStatement ps0 = mock(PreparedStatement.class);
            setter.setValues(ps0, 0);
            verify(ps0).setString(1, "d1");
            verify(ps0).setString(3, "[1.0]");

            PreparedStatement ps1 = mock(PreparedStatement.class);
            setter.setValues(ps1, 1);
            verify(ps1).setString(1, "d2");
            verify(ps1).setString(3, "[2.0]");
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("add: 임베딩 배치가 read timeout에 걸리면 절반으로 나눠 재시도한다")
    void addSplitsBatchInHalfOnTimeout() {
        SqliteVecVectorStoreProvider p = provider();
        Document doc1 = Document.builder().id("d1").text("hello1").metadata(Map.of()).build();
        Document doc2 = Document.builder().id("d2").text("hello2").metadata(Map.of()).build();

        when(embeddingModel.embed(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            if (texts.size() > 1) {
                throw new RuntimeException("I/O error", new SocketTimeoutException("Read timed out"));
            }
            return texts.stream().map(t -> new float[]{0.5f}).toList();
        });

        p.add("u", "v1", List.of(doc1, doc2));

        // 1 initial 2-item call that times out, then 2 single-item retries (halved) = 3 total.
        verify(embeddingModel, times(3)).embed(anyList());
        verify(jdbc, times(1)).batchUpdate(
                eq("INSERT INTO vec_embeddings(spring_doc_id, version, embedding) VALUES (?, ?, ?)"),
                any(BatchPreparedStatementSetter.class));
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
