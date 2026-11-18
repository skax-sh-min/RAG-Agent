package com.example.ragagent.config;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code SQLITE_VEC_EXTENSION_PATH} 를 <b>비운</b> sqlite-vec 모드의 끝에서 끝까지 — jar(여기서는 target/classes)에 빌드가 넣은
 * 확장을 {@code {data-dir}/native/} 로 꺼내고, 컨텍스트의 실제 커넥션이 그 파일을 로드해 vec0 가상 테이블까지 쓰는지 본다.
 * {@code -Dsqlitevec.path} 로 게이트되는 {@code DataSourceJdbcTemplateWiringTest} 는 직접 지정한 경로를 보고, 이 테스트는 번들을 본다 —
 * 이 빌드가 확장을 넣지 않았거나({@code -Dsqlite-vec.skip=true}) 번들하는 플랫폼이 아니면 건너뛴다.
 */
@ResourceLock("global-state")
class DataSourceBundledSqliteVecTest {

    @Test
    @DisplayName("경로 비움: 번들을 {data-dir}/native/sqlite-vec-<버전>/ 에 꺼내 컨텍스트의 커넥션이 로드한다 — vec_version() 과 vec0 테이블이 동작")
    void blankPath_loadsTheBundledExtensionInTheRealContext(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(VecExtension.isBundled(), "이 빌드는 sqlite-vec 를 jar 에 넣지 않았다(-Dsqlite-vec.skip=true)");
        try {
            VecExtension.platform();
        } catch (IllegalStateException e) {
            Assumptions.abort("번들하는 플랫폼이 아니다: " + e.getMessage());
        }
        String version = VecExtension.version();

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JdbcTemplateAutoConfiguration.class))
                .withUserConfiguration(DataSourceConfig.class)
                .withPropertyValues(
                        "app.data-dir=" + dir,
                        "app.vectorstore.type=sqlite-vec",
                        "app.vectorstore.sqlite-vec.extension-path=")
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    assertThat(dir.resolve("native").resolve("sqlite-vec-" + version).resolve(VecExtension.fileName(VecExtension.platform())))
                            .as("번들은 {data-dir}/native/ 아래로 꺼내진다").exists();
                    JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
                    assertThat(jdbc.queryForObject("SELECT vec_version()", String.class)).isEqualTo("v" + version);

                    // 확장이 로드됐을 뿐 아니라 이 앱이 쓰는 vec0 가상 테이블이 이 버전에서 동작한다(KNN 까지)
                    jdbc.execute("CREATE VIRTUAL TABLE bundled_check USING vec0(embedding float[3])");
                    jdbc.update("INSERT INTO bundled_check(rowid, embedding) VALUES (1, ?)", (Object) floats(1f, 0f, 0f));
                    jdbc.update("INSERT INTO bundled_check(rowid, embedding) VALUES (2, ?)", (Object) floats(0f, 1f, 0f));
                    Long nearest = jdbc.queryForObject(
                            "SELECT rowid FROM bundled_check WHERE embedding MATCH ? AND k = 1 ORDER BY distance",
                            Long.class, (Object) floats(0.9f, 0.1f, 0f));
                    assertThat(nearest).isEqualTo(1L);
                });
    }

    /** raw float32 BLOB — 앱이 벡터를 vec0 에 바인딩하는 형식(OPERATOR_MANUAL §10.9.2)과 같다. */
    private static byte[] floats(float... v) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(v.length * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (float f : v) b.putFloat(f);
        return b.array();
    }
}
