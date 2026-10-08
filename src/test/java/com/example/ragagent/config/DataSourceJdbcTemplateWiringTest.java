package com.example.ragagent.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>어떤 {@code JdbcTemplate} 이 한정자 없는 주입에 들어가는가</b> — 즉 운영 저장소들이 실제로 어느
 * SQLite 파일에 쓰는가를 고정하는 진단 테스트.
 *
 * <p>배경: {@link DataSourceConfig} 가 {@code vectorJdbcTemplate} 빈을 직접 정의하는 순간 Spring Boot 의
 * {@code JdbcTemplateAutoConfiguration}({@code @ConditionalOnMissingBean(JdbcOperations.class)})이
 * <b>통째로 물러난다</b>. 그래서 컨텍스트에 남는 {@code JdbcTemplate} 은 그 빈 하나뿐이고,
 * {@code @Qualifier} 없이 {@code JdbcTemplate} 을 받는 모든 저장소(SqliteMemoryRepository ·
 * SqliteUserDetailsService · CuratedQaRepository · CuratedSubmissionRepository ·
 * ChunkReportRepository · LlmUsageRepository · SettingsOverrideRepository · AppSecretRepository ·
 * DocRegistry …)가 그것을 받는다.
 *
 * <p>결과가 설정에 따라 갈린다:
 * <ul>
 *   <li>분리 <b>off</b>: {@code vectorJdbcTemplateShared} 가 운영 DataSource 를 감싸므로 전부
 *       {@code memory.db} — 겉으로는 아무 문제가 없다.</li>
 *   <li>분리 <b>on</b>({@code app.vectorstore.sqlite-vec.db-path} 설정): 그 빈이 전용 벡터
 *       DataSource 를 가리키므로 <b>운영 테이블까지 벡터 DB 파일에 만들어진다</b>. 실제 배포의
 *       {@code vector.db} 안에서 {@code conversation_turns}·{@code users}·{@code curated_qa}·
 *       {@code chunk_report} 가 관측되고 {@code memory.db} 는 옛 상태로 남아 있는 이유가 이것이다.</li>
 * </ul>
 *
 * <p>이 테스트는 <b>현재 동작을 기록</b>할 뿐 옳다고 주장하지 않는다. 배선을 고치기로 하면(운영 전용
 * {@code @Primary JdbcTemplate} 빈 추가) 이 테스트의 기대값이 바뀌어야 하고, 그것이 곧 "기존 배포의
 * 데이터가 다른 파일로 이사한다"는 경보다.
 *
 * <p>{@code -Dsqlitevec.path=...} 로 게이트한다({@code SqliteVecIntegrationTest} 와 같은 이유 —
 * 분리 스위치는 {@code sqlite-vec} 백엔드에서만 의미가 있고, 그 백엔드는 커넥션마다 vec0 확장을
 * 로드하므로 실제 바이너리가 필요하다).
 */
@EnabledIfSystemProperty(named = "sqlitevec.path", matches = ".+")
@ResourceLock("global-state")
class DataSourceJdbcTemplateWiringTest {

    private static final String VEC_PATH = System.getProperty("sqlitevec.path", "");
    private static final String DATA_DIR = "target/ds-wiring-it";
    private static final String VECTOR_DB = DATA_DIR + "/vector.db";

    private ApplicationContextRunner runner(String... extraProps) {
        String[] base = {
                "app.data-dir=" + DATA_DIR,
                "app.vectorstore.type=sqlite-vec",
                "app.vectorstore.sqlite-vec.extension-path=" + VEC_PATH
        };
        String[] props = new String[base.length + extraProps.length];
        System.arraycopy(base, 0, props, 0, base.length);
        System.arraycopy(extraProps, 0, props, base.length, extraProps.length);
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JdbcTemplateAutoConfiguration.class))
                .withUserConfiguration(DataSourceConfig.class)
                .withPropertyValues(props);
    }

    private static String urlOf(JdbcTemplate template) {
        DataSource ds = template.getDataSource();
        assertThat(ds).isInstanceOf(HikariDataSource.class);
        return ((HikariDataSource) ds).getJdbcUrl().replace('\\', '/');
    }

    @Test
    @DisplayName("분리 on: 컨텍스트의 유일한 JdbcTemplate 은 vectorJdbcTemplate 이고 벡터 DB 파일을 가리킨다")
    void separateSwitchOn_soleTemplateIsVectorOne() {
        runner("app.vectorstore.sqlite-vec.db-path=" + VECTOR_DB).run(context -> {
            assertThat(context).hasNotFailed();

            // ① Spring Boot 의 자동설정 JdbcTemplate("jdbcTemplate")이 아예 없다 —
            //    앱이 JdbcOperations 빈을 하나라도 정의하면 자동설정이 통째로 물러나기 때문.
            assertThat(context.getBeanNamesForType(JdbcTemplate.class))
                    .containsExactly("vectorJdbcTemplate");

            // ② DataSource 는 둘 다 있다(운영 @Primary + 전용 벡터).
            assertThat(context.getBeanNamesForType(DataSource.class))
                    .containsExactlyInAnyOrder("dataSource", "vectorDataSource");

            // ③ 그래서 한정자 없이 JdbcTemplate 을 받는 저장소는 **벡터 DB 파일**에 쓴다.
            JdbcTemplate unqualified = context.getBean(JdbcTemplate.class);
            assertThat(urlOf(unqualified)).endsWith(VECTOR_DB.substring(VECTOR_DB.lastIndexOf('/')));
            assertThat(urlOf(unqualified)).doesNotContain("memory.db");
            assertThat(unqualified.getDataSource())
                    .isSameAs(context.getBean("vectorDataSource", DataSource.class));
        });
    }

    @Test
    @DisplayName("분리 off: 같은 빈이 운영 DataSource 를 감싸므로 전부 memory.db (문제가 보이지 않는다)")
    void separateSwitchOff_soleTemplatePointsAtMemoryDb() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanNamesForType(JdbcTemplate.class))
                    .containsExactly("vectorJdbcTemplate");
            assertThat(context.getBeanNamesForType(DataSource.class)).containsExactly("dataSource");

            JdbcTemplate unqualified = context.getBean(JdbcTemplate.class);
            assertThat(urlOf(unqualified)).endsWith("memory.db");
        });
    }
}
