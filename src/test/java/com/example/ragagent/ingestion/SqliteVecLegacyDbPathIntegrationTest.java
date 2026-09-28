package com.example.ragagent.ingestion;

import org.junit.jupiter.api.parallel.ResourceLock;

import com.example.ragagent.config.DataSourceConfig;
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

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 옛 분리 스위치({@code app.vectorstore.sqlite-vec.db-path})가 설정된 배포를 실 vec0 위에서 E2E 로 확인한다.
 *
 * <p>예전(Step 5.10)에는 이 설정이 벡터/FTS 테이블을 별도 파일로 "분리"했다 — 실제로는 운영 테이블까지
 * 전부 그 파일로 갔다({@code DataSourceJdbcTemplateWiringTest}). 그래서 지금은 이 경로를 <b>유일한 DB
 * 파일</b>로 연다: 운영 테이블·Flyway 이력·벡터·FTS 가 모두 한 파일에 있고, {@code memory.db} 는 생기지
 * 않으며, 한정자 있는/없는 템플릿이 같은 DataSource 다.
 *
 * <p>{@code -Dsqlitevec.path=/path/to/vec0} 지정 시에만 실행({@code SqliteVecIntegrationTest}와 동일 게이트).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "app.vectorstore.type=sqlite-vec",
                "app.vectorstore.sqlite-vec.extension-path=${sqlitevec.path}",
                "app.vectorstore.sqlite-vec.db-path=" + SqliteVecLegacyDbPathIntegrationTest.LEGACY_DB,
                "app.embedding.dimensions=4",
                "app.auth.enabled=false",
                "app.data-dir=" + SqliteVecLegacyDbPathIntegrationTest.DATA_DIR,
                // SqliteVecIntegrationTest 와 같은 더미 LOCAL 프로바이더 — 이유는 그쪽 주석.
                "app.llm.providers[0].name=it-local",
                "app.llm.providers[0].base-url=http://127.0.0.1:9/v1",
                "app.llm.providers[0].api-key=test-key",
                "app.llm.providers[0].model=it-model",
                "app.llm.providers[0].type=BOTH",
                "app.llm.providers[0].role=LOCAL",
                "app.llm.verify-local-models-on-startup=false",
                // add→search 가 KNN 순서를 본다 — 기본 유사도 컷(0.3, §10.7.4)이면 직교 벡터("banana")가
                // 걸러져 두 번째 결과가 사라진다. 컷은 이 테스트의 관심사가 아니다.
                "app.search-similarity-threshold=0.0"
        })
@EnabledIfSystemProperty(named = "sqlitevec.path", matches = ".+")
@ResourceLock("global-state")
class SqliteVecLegacyDbPathIntegrationTest {

    static final String DATA_DIR = "target/sqlitevec-it-legacy";
    static final String LEGACY_DB = DATA_DIR + "/vector.db";

    private static final String V = "legacyv1";

    @MockitoBean EmbeddingModel embeddingModel;
    @MockitoBean ChatModel chatModel;

    @Autowired ApplicationContext ctx;
    @Autowired VectorStoreFacade facade;
    @Autowired JdbcTemplate jdbc;                                         // 운영 저장소가 받는 것
    @Autowired @Qualifier("vectorJdbcTemplate") JdbcTemplate vectorJdbc;  // 벡터/FTS 컴포넌트가 받는 것

    private static float[] vec(String t) {
        return switch (t) {
            case "apple"  -> new float[]{1, 0, 0, 0};
            case "banana" -> new float[]{0, 1, 0, 0};
            default       -> new float[]{0, 0, 0, 1};
        };
    }

    private static Document doc(String id, String text) {
        return Document.builder().id(id).text(text)
                .metadata(Map.of(MetaKey.DOC_ID, "legacydoc", MetaKey.VERSION, V)).build();
    }

    private long tableCount(String name) {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", Long.class, name);
        return c == null ? 0 : c;
    }

    @BeforeEach
    void setup() {
        when(embeddingModel.embed(anyList())).thenAnswer(inv ->
                ((List<String>) inv.getArgument(0)).stream().map(SqliteVecLegacyDbPathIntegrationTest::vec).toList());
        when(embeddingModel.embed(anyString())).thenAnswer(inv -> vec(inv.getArgument(0)));
        facade.deleteByDocIds("shared", V, List.of("leg_d1", "leg_d2"));
    }

    @Test
    @DisplayName("DataSource 하나 — 한정자 있는/없는 템플릿이 같은 DataSource, 그 파일은 옛 db-path")
    void oneDataSourceOnTheLegacyFile() {
        assertThat(ctx.getBeanNamesForType(DataSource.class)).containsExactly("dataSource");
        assertThat(vectorJdbc.getDataSource()).isSameAs(jdbc.getDataSource());
        assertThat(DataSourceConfig.sqliteFilePath(jdbc.getDataSource()))
                .isEqualTo(Path.of(LEGACY_DB).toAbsolutePath().normalize().toString());
        assertThat(Path.of(DATA_DIR, "memory.db")).doesNotExist();
    }

    @Test
    @DisplayName("운영 테이블·Flyway 이력·벡터·FTS 가 모두 그 한 파일에 있다")
    void everyTableLivesInTheOneFile() {
        assertThat(tableCount("conversation_turns")).isEqualTo(1);
        assertThat(tableCount("users")).isEqualTo(1);
        assertThat(tableCount("doc_registry")).isEqualTo(1);
        // Flyway 가 이제 실데이터가 있는 파일에 적용된다(예전엔 빈 memory.db 에만 닿았다).
        assertThat(tableCount("flyway_schema_history")).isEqualTo(1);
        assertThat(tableCount("vec_document_chunks")).isEqualTo(1);
        assertThat(tableCount("chunk_fts")).isEqualTo(1);
    }

    @Test
    @DisplayName("add → search → delete E2E (한 파일 위 vec0)")
    void addSearchDelete() {
        facade.add("shared", V, List.of(doc("leg_d1", "apple"), doc("leg_d2", "banana")));

        List<Document> hits = facade.search("shared", "apple", V, 5);
        assertThat(hits).extracting(Document::getId).containsExactly("leg_d1", "leg_d2");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM vec_document_chunks WHERE version=?", Long.class, V)).isEqualTo(2L);

        facade.deleteByDocIds("shared", V, List.of("leg_d1", "leg_d2"));
        assertThat(facade.search("shared", "apple", V, 5)).isEmpty();
    }
}
