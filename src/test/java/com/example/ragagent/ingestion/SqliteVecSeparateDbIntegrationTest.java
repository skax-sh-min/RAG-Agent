package com.example.ragagent.ingestion;

import com.example.ragagent.model.MetaKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Step 5.10 — 벡터/운영 DB 분리(separate {@code vector.db})를 실 vec0 위에서 E2E 검증한다.
 *
 * <p>{@code -Dsqlitevec.path=/path/to/vec0} 지정 시에만 실행({@code SqliteVecIntegrationTest}와 동일 게이트).
 * 검증 핵심: {@code app.vectorstore.sqlite-vec.db-path} 설정 시 벡터/FTS 테이블이 <b>별도 파일</b>에
 * 생성되고 운영 {@code memory.db}에는 존재하지 않는 것(물리적 분리) + add→search→delete E2E 동작.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "app.vectorstore.type=sqlite-vec",
                "app.vectorstore.sqlite-vec.extension-path=${sqlitevec.path}",
                "app.vectorstore.sqlite-vec.db-path=target/sqlitevec-it-sep/vector.db",
                "app.embedding.dimensions=4",
                "app.auth.enabled=false",
                "app.data-dir=target/sqlitevec-it-sep",
                "app.llm.providers[0].api-key=test-key"
        })
@EnabledIfSystemProperty(named = "sqlitevec.path", matches = ".+")
class SqliteVecSeparateDbIntegrationTest {

    private static final String V = "sepv1";

    @MockitoBean EmbeddingModel embeddingModel;
    @MockitoBean ChatModel chatModel;

    @Autowired ApplicationContext ctx;
    @Autowired VectorStoreFacade facade;
    @Autowired JdbcTemplate jdbc;                                    // @Primary → memory.db
    @Autowired @Qualifier("vectorJdbcTemplate") JdbcTemplate vectorJdbc;  // vector.db

    private static float[] vec(String t) {
        return switch (t) {
            case "apple"  -> new float[]{1, 0, 0, 0};
            case "banana" -> new float[]{0, 1, 0, 0};
            default       -> new float[]{0, 0, 0, 1};
        };
    }

    private static Document doc(String id, String text) {
        return Document.builder().id(id).text(text)
                .metadata(Map.of(MetaKey.DOC_ID, "sepdoc", MetaKey.VERSION, V)).build();
    }

    private long tableCount(JdbcTemplate t, String name) {
        Long c = t.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", Long.class, name);
        return c == null ? 0 : c;
    }

    @BeforeEach
    void setup() {
        when(embeddingModel.embed(anyList())).thenAnswer(inv ->
                ((List<String>) inv.getArgument(0)).stream().map(SqliteVecSeparateDbIntegrationTest::vec).toList());
        when(embeddingModel.embed(anyString())).thenAnswer(inv -> vec(inv.getArgument(0)));
        facade.deleteByDocIds("shared", V, List.of("sep_d1", "sep_d2"));
    }

    @Test
    @DisplayName("분리 스위치 on: 전용 vectorDataSource + 두 JdbcTemplate가 서로 다른 DataSource")
    void separateDataSourceWired() {
        assertThat(ctx.containsBean("vectorDataSource")).isTrue();
        assertThat(vectorJdbc.getDataSource()).isNotSameAs(jdbc.getDataSource());
    }

    @Test
    @DisplayName("벡터/FTS 테이블은 vector.db 에만, memory.db 에는 없음(물리적 분리)")
    void tablesLiveInVectorDbOnly() {
        // vec_document_chunks/chunk_fts 는 vector.db 에 생성됨
        assertThat(tableCount(vectorJdbc, "vec_document_chunks")).isEqualTo(1);
        assertThat(tableCount(vectorJdbc, "chunk_fts")).isEqualTo(1);
        // 운영 memory.db 에는 존재하지 않음
        assertThat(tableCount(jdbc, "vec_document_chunks")).isZero();
        assertThat(tableCount(jdbc, "chunk_fts")).isZero();
    }

    @Test
    @DisplayName("add → search → delete E2E (분리된 vector.db 위 vec0)")
    void addSearchDelete() {
        facade.add("shared", V, List.of(doc("sep_d1", "apple"), doc("sep_d2", "banana")));

        List<Document> hits = facade.search("shared", "apple", V, 5);
        assertThat(hits).extracting(Document::getId).containsExactly("sep_d1", "sep_d2");
        // 저장은 vector.db 로 감 (운영 DB 오염 없음)
        assertThat(vectorJdbc.queryForObject(
                "SELECT COUNT(*) FROM vec_document_chunks WHERE version=?", Long.class, V)).isEqualTo(2L);

        facade.deleteByDocIds("shared", V, List.of("sep_d1", "sep_d2"));
        assertThat(facade.search("shared", "apple", V, 5)).isEmpty();
    }
}
