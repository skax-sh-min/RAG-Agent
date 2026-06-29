package com.example.ragagent.config;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DataSourceConfig#configureSqliteVec} 단위 테스트.
 * 실제 커넥션을 열지 않고 HikariConfig 결과만 검증한다 (네이티브 바이너리 불필요).
 */
class DataSourceConfigTest {

    private HikariConfig base() {
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl("jdbc:sqlite:/tmp/test.db");
        c.setDriverClassName("org.sqlite.JDBC");
        c.setMaximumPoolSize(1);
        return c;
    }

    @Test
    @DisplayName("chroma(기본): 커넥션 변경 없음 — enable_load_extension/initSql 미설정")
    void chroma_noChange() {
        HikariConfig c = base();
        DataSourceConfig.configureSqliteVec(c, "chroma", "", "");
        assertThat(c.getDataSourceProperties().getProperty("enable_load_extension")).isNull();
        assertThat(c.getConnectionInitSql()).isNull();
    }

    @Test
    @DisplayName("null/blank type → chroma 취급, 변경 없음")
    void nullType_treatedAsChroma() {
        HikariConfig c = base();
        DataSourceConfig.configureSqliteVec(c, null, "/opt/vec0", "");
        assertThat(c.getDataSourceProperties().getProperty("enable_load_extension")).isNull();
        assertThat(c.getConnectionInitSql()).isNull();
    }

    @Test
    @DisplayName("sqlite-vec + 경로: enable_load_extension=true + load_extension initSql")
    void sqliteVec_setsLoader() {
        HikariConfig c = base();
        DataSourceConfig.configureSqliteVec(c, "sqlite-vec", "/opt/sqlite-vec/vec0", "");
        assertThat(c.getDataSourceProperties().getProperty("enable_load_extension")).isEqualTo("true");
        assertThat(c.getConnectionInitSql())
                .startsWith("SELECT load_extension('")
                .contains("/opt/sqlite-vec/vec0")
                .endsWith("')");
    }

    @Test
    @DisplayName("sqlite-vec + 대소문자 무시 + 공백 trim")
    void sqliteVec_caseInsensitiveAndTrimmed() {
        HikariConfig c = base();
        DataSourceConfig.configureSqliteVec(c, "  SQLite-Vec ", "  /opt/vec0  ", "");
        assertThat(c.getConnectionInitSql())
                .startsWith("SELECT load_extension('")
                .contains("/opt/vec0")
                .endsWith("')");
    }

    @Test
    @DisplayName("sqlite-vec + entrypoint 지정 → load_extension(path, entrypoint)")
    void sqliteVec_withEntrypoint() {
        HikariConfig c = base();
        DataSourceConfig.configureSqliteVec(c, "sqlite-vec", "/opt/vec0", "sqlite3_vec_init");
        assertThat(c.getConnectionInitSql())
                .startsWith("SELECT load_extension('")
                .contains("/opt/vec0")
                .endsWith("', 'sqlite3_vec_init')");
    }

    @Test
    @DisplayName("sqlite-vec + 경로 누락 → 명확한 오류로 기동 실패")
    void sqliteVec_blankPath_throws() {
        HikariConfig c = base();
        assertThatThrownBy(() -> DataSourceConfig.configureSqliteVec(c, "sqlite-vec", "  ", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("extension-path");
    }

    @Test
    @DisplayName("경로/엔트리포인트의 작은따옴표 차단 (SQL 주입/깨짐 방지)")
    void sqliteVec_rejectsSingleQuote() {
        assertThatThrownBy(() -> DataSourceConfig.configureSqliteVec(base(), "sqlite-vec", "/x/v'0", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("작은따옴표");
        assertThatThrownBy(() -> DataSourceConfig.configureSqliteVec(base(), "sqlite-vec", "/x/vec0", "ev'il"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("작은따옴표");
    }

    @Test
    @DisplayName("디렉터리 경로 입력 시 vec0 바이너리를 자동 해석한다")
    void sqliteVec_resolvesDirectoryPath(@TempDir Path dir) throws IOException {
        Path dll = dir.resolve("vec0.dll");
        Files.writeString(dll, "stub");

        HikariConfig c = base();
        DataSourceConfig.configureSqliteVec(c, "sqlite-vec", dir.toString(), "");

        String expected = "SELECT load_extension('" + dll.toAbsolutePath().normalize().toString().replace('\\', '/') + "')";
        assertThat(c.getConnectionInitSql()).isEqualTo(expected);
    }

    @Test
    @DisplayName("디렉터리 경로인데 vec0 바이너리가 없으면 명확한 오류")
    void sqliteVec_directoryWithoutBinary_throws(@TempDir Path dir) {
        HikariConfig c = base();

        assertThatThrownBy(() -> DataSourceConfig.configureSqliteVec(c, "sqlite-vec", dir.toString(), ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vec0 바이너리");
    }
}
