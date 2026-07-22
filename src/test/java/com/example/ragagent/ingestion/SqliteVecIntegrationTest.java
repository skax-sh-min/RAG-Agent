package com.example.ragagent.ingestion;

import org.junit.jupiter.api.parallel.ResourceLock;

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
 * 실제 Spring 컨텍스트 위에서 sqlite-vec 백엔드를 E2E로 검증하는 통합 테스트.
 *
 * <p>{@code -Dsqlitevec.path=/path/to/vec0}로 해당 플랫폼용 vec0 로더블을 지정해야만 실행된다
 * ({@code DataSource}가 커넥션 초기화 시 로드); 미지정 시 클래스 전체가 skip되어 CI와
 * 기본 빌드는 네이티브 바이너리 없이도 통과한다. 임베딩은 오프라인·결정적 실행을 위해 mock하고,
 * 벡터 스토어 경로는 실제 sqlite-vec에 대해 E2E로 검증한다.
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
@ResourceLock("global-state")
class SqliteVecIntegrationTest {

    private static final String V = "itv1";   // 테스트 전용 버전(vec0 파티션 키) → 다른 데이터와 격리

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
