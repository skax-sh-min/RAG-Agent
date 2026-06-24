package com.example.ragagent.ingestion;

import com.example.ragagent.model.MetaKey;
import com.example.ragagent.service.VectorStoreRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Phase 5 Step 5.7 — full sqlite-vec backend integration over the real Spring context.
 *
 * <p>Runs only when {@code -Dsqlitevec.path=/path/to/vec0} points at a vec0 loadable for this
 * platform (the DataSource loads it on connection init); otherwise the whole class is skipped, so
 * CI and the default build stay green without the native binary. Embeddings are mocked to keep the
 * test offline and deterministic — the vector store path itself is exercised end-to-end against
 * real sqlite-vec.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "app.vectorstore.type=sqlite-vec",
                "app.vectorstore.sqlite-vec.extension-path=${sqlitevec.path}",
                "app.embedding.dimensions=4",
                "app.auth.enabled=false",
                "app.data-dir=target/sqlitevec-it",
                // LLM provider 키(더미) — primaryChatModel 빌드용. 임베딩은 @MockitoBean, 실제 호출 없음.
                "app.llm.providers[0].api-key=test-key"
        })
@EnabledIfSystemProperty(named = "sqlitevec.path", matches = ".+")
class SqliteVecIntegrationTest {

    private static final String V = "itv1";   // test-only version (vec0 partition key) → isolates data

    @MockitoBean
    EmbeddingModel embeddingModel;

    // LLM은 sqlite-vec 통합과 무관 — ChatModel을 mock해 LLM provider 빌드 우회
    @MockitoBean
    ChatModel chatModel;

    @Autowired ApplicationContext ctx;
    @Autowired VectorStoreFacade facade;
    @Autowired VectorStoreProvider provider;

    private static float[] vec(String t) {
        return switch (t) {
            case "apple"  -> new float[]{1, 0, 0, 0};
            case "banana" -> new float[]{0, 1, 0, 0};
            default       -> new float[]{0, 0, 0, 1};
        };
    }

    private static Document doc(String id, String text) {
        return Document.builder().id(id).text(text)
                .metadata(Map.of(MetaKey.DOC_ID, "itdoc", MetaKey.VERSION, V)).build();
    }

    @BeforeEach
    void setup() {
        when(embeddingModel.embed(anyList())).thenAnswer(inv ->
                ((List<String>) inv.getArgument(0)).stream().map(SqliteVecIntegrationTest::vec).toList());
        when(embeddingModel.embed(anyString())).thenAnswer(inv -> vec(inv.getArgument(0)));
        // 잔여 데이터 격리 (테스트 전용 version)
        facade.deleteByDocIds("shared", V, List.of("it_d1", "it_d2"));
    }

    @Test
    @DisplayName("sqlite-vec 프로파일 컨텍스트가 ChromaDB 없이 로드된다")
    void contextLoadsWithoutChroma() {
        assertThat(ctx.getBeanNamesForType(ChromaApi.class)).isEmpty();
        assertThat(ctx.getBeanNamesForType(VectorStoreRegistry.class)).isEmpty();
        assertThat(ctx.containsBean("vectorStoreWarmup")).isFalse();
        assertThat(ctx.containsBean("chromaHealthChecker")).isFalse();
        assertThat(provider).isInstanceOf(SqliteVecVectorStoreProvider.class);
        assertThat(ctx.containsBean("sqliteVecSchemaInitializer")).isTrue();
        assertThat(ctx.containsBean("sqliteVecVerifier")).isTrue();
    }

    @Test
    @DisplayName("업로드 → 검색 → 삭제 E2E (facade → SqliteVecVectorStoreProvider → vec0)")
    void addSearchDelete() {
        facade.add("shared", V, List.of(doc("it_d1", "apple"), doc("it_d2", "banana")));

        // search: apple 쿼리 → d1(가장 가까움) 먼저, 메타데이터 복원
        List<Document> hits = facade.search("shared", "apple", V, 5);
        assertThat(hits).extracting(Document::getId).containsExactly("it_d1", "it_d2");
        assertThat(hits.get(0).getMetadata()).containsEntry(MetaKey.DOC_ID, "itdoc");

        // batch
        List<List<Document>> batch = facade.searchBatch("shared", List.of("apple", "banana"), V, 5);
        assertThat(batch).hasSize(2);
        assertThat(batch.get(0).get(0).getId()).isEqualTo("it_d1");
        assertThat(batch.get(1).get(0).getId()).isEqualTo("it_d2");

        // delete → 빈 결과
        facade.deleteByDocIds("shared", V, List.of("it_d1", "it_d2"));
        assertThat(facade.search("shared", "apple", V, 5)).isEmpty();
    }
}
