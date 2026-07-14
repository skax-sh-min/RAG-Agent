package com.example.ragagent.ingestion;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.exception.VectorStoreException;
import com.example.ragagent.model.MetaKey;
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
        return provider(0.0);
    }

    private SqliteVecVectorStoreProvider provider(double threshold) {
        AppProperties props = mock(AppProperties.class);
        when(props.searchSimilarityThresholdSafe()).thenReturn(threshold);
        return new SqliteVecVectorStoreProvider(jdbc, embeddingModel, new ObjectMapper(), props);
    }

    // ── §10.7.4 — threshold-active overfetch ─────────────────────────────

    @Test
    @DisplayName("search — threshold>0이면 k를 topK의 2배로 과조회한다")
    void search_overfetchesKWhenThresholdActive() {
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(), any(), any()))
                .thenReturn(List.of());

        provider(0.5).search("u", "질문", "latest", 7);

        ArgumentCaptor<Integer> kCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(jdbc).query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                any(), kCaptor.capture(), any());
        assertThat(kCaptor.getValue()).isEqualTo(14); // ceil(7 * 2.0)
    }

    @Test
    @DisplayName("search — threshold=0.0(기본)이면 과조회하지 않는다 (무해)")
    void search_noOverfetchWhenThresholdZero() {
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(), any(), any()))
                .thenReturn(List.of());

        provider().search("u", "질문", "latest", 7);

        ArgumentCaptor<Integer> kCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(jdbc).query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                any(), kCaptor.capture(), any());
        assertThat(kCaptor.getValue()).isEqualTo(7);
    }

    @Test
    @DisplayName("search — 결과가 topK보다 많아도 topK로 잘라낸다")
    void search_capsResultsAtTopKEvenWhenMoreSurvive() {
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
        List<Document> tenDocs = java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> Document.builder().id("d" + i).text("t").metadata(Map.of()).build())
                .toList();
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(), any(), any()))
                .thenReturn(tenDocs);

        List<Document> result = provider().search("u", "질문", "latest", 5);

        assertThat(result).hasSize(5);
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
    @DisplayName("add: 임베딩 입력은 맥락+정규화 텍스트이고, vec_document_chunks.content는 원문 그대로이며 CHUNK_CONTEXT는 제외된다(§10.1)")
    void add_embedsDerivedTextButPersistsOriginalContent() {
        SqliteVecVectorStoreProvider p = provider();
        String original = "**중요**한 내용\n------";
        Document doc = Document.builder().id("d1").text(original)
                .metadata(Map.of(MetaKey.CHUNK_CONTEXT, "문서.pdf > 설정"))
                .build();

        when(embeddingModel.embed(anyList())).thenReturn(List.of(new float[]{0.1f}));

        p.add("u", "v1", List.of(doc));

        ArgumentCaptor<List> embedCaptor = ArgumentCaptor.forClass(List.class);
        verify(embeddingModel).embed(embedCaptor.capture());
        assertThat(embedCaptor.getValue()).containsExactly("문서.pdf > 설정\n\n중요한 내용");

        ArgumentCaptor<List> rowsCaptor = ArgumentCaptor.forClass(List.class);
        verify(jdbc).batchUpdate(
                eq("INSERT INTO vec_document_chunks(spring_doc_id, content, metadata, version, doc_id, created_at) VALUES (?, ?, ?, ?, ?, ?)"),
                rowsCaptor.capture());
        Object[] row = (Object[]) rowsCaptor.getValue().get(0);
        assertThat(row[1]).isEqualTo(original);                          // content column = raw original
        assertThat((String) row[2]).doesNotContain("chunk_context");     // metadata JSON excludes CHUNK_CONTEXT
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
    @DisplayName("add(onProgress): 배치가 나뉠 때마다 실제 진행률을 보고한다")
    void addReportsIncrementalProgressAcrossBatches() {
        SqliteVecVectorStoreProvider p = provider();
        // Same sizing as addMapsEmbeddingsToCorrectDocIdAcrossBatches — forces a 2-batch split.
        Document doc1 = Document.builder().id("d1").text("a".repeat(20_000)).metadata(Map.of()).build();
        Document doc2 = Document.builder().id("d2").text("b".repeat(20_000)).metadata(Map.of()).build();

        when(embeddingModel.embed(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return texts.stream().map(t -> new float[]{1f}).toList();
        });

        List<int[]> calls = new java.util.ArrayList<>();
        p.add("u", "v1", List.of(doc1, doc2), (done, total) -> calls.add(new int[]{done, total}));

        assertThat(calls.get(0)).containsExactly(0, 2);           // reported before any batch starts
        assertThat(calls.get(calls.size() - 1)).containsExactly(2, 2); // reaches 100% at the end
        assertThat(calls.size()).isGreaterThanOrEqualTo(3);        // 0/2 + at least 2 incremental steps
        // never reports done > total, and done is non-decreasing
        int prevDone = -1;
        for (int[] call : calls) {
            assertThat(call[0]).isLessThanOrEqualTo(call[1]).isGreaterThanOrEqualTo(prevDone);
            prevDone = call[0];
        }
    }

    @Test
    @DisplayName("updateTags: 기존 메타데이터를 보존하며 tags 필드만 갱신한다")
    void updateTagsMergesIntoExistingMetadata() {
        SqliteVecVectorStoreProvider p = provider();
        String existingJson = "{\"doc_id\":\"doc-1\",\"tags\":\"old\"}";
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
                .thenReturn(java.util.Collections.singletonList(new Object[]{"d1", existingJson}));

        p.updateTags("u", "v1", List.of("d1"), "new,tags");

        ArgumentCaptor<List<Object[]>> captor = ArgumentCaptor.forClass(List.class);
        verify(jdbc).batchUpdate(eq("UPDATE vec_document_chunks SET metadata = ? WHERE spring_doc_id = ?"), captor.capture());
        List<Object[]> updates = captor.getValue();
        assertThat(updates).hasSize(1);
        assertThat((String) updates.get(0)[0]).contains("\"tags\":\"new,tags\"").contains("\"doc_id\":\"doc-1\"");
        assertThat(updates.get(0)[1]).isEqualTo("d1");
    }

    @Test
    @DisplayName("updateTags: 빈 tagsCsv → tags 키 제거")
    void updateTagsRemovesKeyWhenBlank() {
        SqliteVecVectorStoreProvider p = provider();
        String existingJson = "{\"doc_id\":\"doc-1\",\"tags\":\"old\"}";
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
                .thenReturn(java.util.Collections.singletonList(new Object[]{"d1", existingJson}));

        p.updateTags("u", "v1", List.of("d1"), "");

        ArgumentCaptor<List<Object[]>> captor = ArgumentCaptor.forClass(List.class);
        verify(jdbc).batchUpdate(eq("UPDATE vec_document_chunks SET metadata = ? WHERE spring_doc_id = ?"), captor.capture());
        assertThat((String) captor.getValue().get(0)[0]).doesNotContain("tags");
    }

    @Test
    @DisplayName("updateTags(빈 springDocIds): DB 호출 없음")
    void updateTagsEmptyIds() {
        provider().updateTags("u", "v1", List.of(), "x");
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
