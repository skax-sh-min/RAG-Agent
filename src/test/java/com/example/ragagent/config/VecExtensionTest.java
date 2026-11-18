package com.example.ragagent.config;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link VecExtension} — jar(여기서는 target/classes)에 빌드가 넣은 sqlite-vec 를 꺼내 실제로 SQLite 에 싣는다. 플랫폼 판정은 순수 함수라
 * 어느 OS 에서나 같게 돌고, 실제 로드는 이 빌드가 확장을 넣었고({@code -Dsqlite-vec.skip=true} 가 아니고) 이 OS 가 지원 플랫폼일 때만 한다.
 */
class VecExtensionTest {

    // ── 플랫폼 판정(순수) ─────────────────────────────────────────────────────

    @Test
    @DisplayName("지원 플랫폼 다섯: os.name/os.arch 표기가 달라도 같은 이름으로 모인다")
    void supportedPlatforms() {
        assertThat(VecExtension.platform("windows 11", "amd64", false)).isEqualTo("windows-x86_64");
        assertThat(VecExtension.platform("windows server 2022", "x86_64", false)).isEqualTo("windows-x86_64");
        assertThat(VecExtension.platform("mac os x", "aarch64", false)).isEqualTo("macos-aarch64");
        assertThat(VecExtension.platform("mac os x", "arm64", false)).isEqualTo("macos-aarch64");
        assertThat(VecExtension.platform("mac os x", "x86_64", false)).isEqualTo("macos-x86_64");
        assertThat(VecExtension.platform("linux", "amd64", false)).isEqualTo("linux-x86_64");
        assertThat(VecExtension.platform("linux", "aarch64", false)).isEqualTo("linux-aarch64");
    }

    @Test
    @DisplayName("darwin 은 windows 가 아니다 — 'win' 부분 문자열 판정이 맥을 윈도우로 읽던 함정")
    void darwinIsNotWindows() {
        assertThat(VecExtension.platform("darwin", "aarch64", false)).isEqualTo("macos-aarch64");
        assertThat(VecExtension.platform("darwin", "amd64", false)).isEqualTo("macos-x86_64");
    }

    @Test
    @DisplayName("지원하지 않는 조합(32비트 ARM·32비트 x86·Windows ARM·그 밖의 OS)은 이유와 대안을 담은 예외")
    void unsupportedPlatformsExplainWhat() {
        for (String[] p : new String[][]{{"linux", "arm"}, {"linux", "x86"}, {"windows 11", "aarch64"}, {"freebsd", "amd64"}, {"windows 10", "x86"}}) {
            assertThatThrownBy(() -> VecExtension.platform(p[0], p[1], false))
                    .as(p[0] + "/" + p[1])
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("미지원 플랫폼").hasMessageContaining("SQLITE_VEC_EXTENSION_PATH");
        }
    }

    @Test
    @DisplayName("musl(Alpine) Linux 는 glibc 용 파일이 로드되지 않으니 미리 이유를 말하고 직접 지정을 안내한다")
    void muslIsRefusedUpFront() {
        assertThatThrownBy(() -> VecExtension.platform("linux", "amd64", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("musl").hasMessageContaining("SQLITE_VEC_EXTENSION_PATH");
    }

    @Test
    @DisplayName("파일 이름은 플랫폼의 로더블 규칙을 따른다")
    void fileNames() {
        assertThat(VecExtension.fileName("windows-x86_64")).isEqualTo("vec0.dll");
        assertThat(VecExtension.fileName("macos-aarch64")).isEqualTo("vec0.dylib");
        assertThat(VecExtension.fileName("macos-x86_64")).isEqualTo("vec0.dylib");
        assertThat(VecExtension.fileName("linux-x86_64")).isEqualTo("vec0.so");
        assertThat(VecExtension.fileName("linux-aarch64")).isEqualTo("vec0.so");
    }

    // ── jar 에 든 확장(빌드가 넣었을 때만) ────────────────────────────────────

    private static void assumeBundledAndSupported() {
        Assumptions.assumeTrue(VecExtension.isBundled(), "이 빌드는 sqlite-vec 를 jar 에 넣지 않았다(-Dsqlite-vec.skip=true)");
        try {
            VecExtension.platform();
        } catch (IllegalStateException e) {
            Assumptions.abort("이 환경은 sqlite-vec 가 번들하는 플랫폼이 아니다: " + e.getMessage());
        }
    }

    private static String pomVersion() throws Exception {
        Matcher m = Pattern.compile("<sqlite-vec\\.version>\\s*([^<\\s]+)\\s*</sqlite-vec\\.version>").matcher(Files.readString(Path.of("pom.xml")));
        assertThat(m.find()).as("pom.xml 에 <sqlite-vec.version> 이 있어야 한다").isTrue();
        return m.group(1);
    }

    @Test
    @DisplayName("버전의 유일한 출처는 pom 이다 — jar 의 VERSION 파일이 pom 의 sqlite-vec.version 과 같다")
    void versionComesFromThePom() throws Exception {
        assumeBundledAndSupported();

        assertThat(VecExtension.version()).isEqualTo(pomVersion());
    }

    @Test
    @DisplayName("꺼낸 파일은 <폴더>/sqlite-vec-<버전>/vec0.* 이고, 같은 내용이면 다시 쓰지 않고, 깨져 있으면 새로 쓴다")
    void extractsToAVersionedFolderAndIsIdempotent(@TempDir Path dir) throws Exception {
        assumeBundledAndSupported();

        Path first = VecExtension.extractTo(dir);

        assertThat(first).isAbsolute().exists();
        assertThat(first.getParent().getFileName().toString()).isEqualTo("sqlite-vec-" + VecExtension.version());
        assertThat(first.getFileName().toString()).isEqualTo(VecExtension.fileName(VecExtension.platform()));
        assertThat(Files.size(first)).isGreaterThan(10_000);          // 자리표시자가 아니라 진짜 바이너리
        long modified = Files.getLastModifiedTime(first).toMillis();
        Thread.sleep(20);

        assertThat(VecExtension.extractTo(dir)).isEqualTo(first);
        assertThat(Files.getLastModifiedTime(first).toMillis()).as("같은 내용이면 다시 쓰지 않는다").isEqualTo(modified);

        byte[] good = Files.readAllBytes(first);
        Files.write(first, new byte[good.length]);                   // 크기는 같고 내용만 깨졌다
        assertThat(VecExtension.extractTo(dir)).isEqualTo(first);
        assertThat(Files.readAllBytes(first)).as("크기가 같아도 내용이 다르면 새로 쓴다").isEqualTo(good);
        try (var tmp = Files.list(first.getParent())) {
            assertThat(tmp.map(p -> p.getFileName().toString())).as("임시 파일을 남기지 않는다").noneMatch(n -> n.endsWith(".tmp"));
        }
    }

    @Test
    @DisplayName("꺼낸 확장이 실제로 로드된다 — vec_version() 이 번들한 버전이다")
    void theExtractedExtensionLoadsIntoSqlite(@TempDir Path dir) throws Exception {
        assumeBundledAndSupported();
        Path vec = VecExtension.extractTo(dir);

        SQLiteConfig config = new SQLiteConfig();
        config.enableLoadExtension(true);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:", config.toProperties());
             Statement st = c.createStatement()) {
            st.execute("SELECT load_extension('" + vec.toString().replace('\\', '/') + "')");
            try (ResultSet rs = st.executeQuery("SELECT vec_version()")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("v" + VecExtension.version());
            }
        }
    }
}
