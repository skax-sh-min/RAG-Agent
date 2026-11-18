package com.example.ragagent.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Locale;

/**
 * jar 에 실려 있는 sqlite-vec 로더블 확장({@code vec0})을 파일로 꺼내 SQLite 가 읽을 수 있게 한다.
 *
 * <p><b>버전은 코드에 없다.</b> {@code pom.xml} 의 {@code sqlite-vec.version} 이 유일한 출처이고, 빌드(maven-antrun-plugin)가
 * 그 버전의 릴리즈 파일을 받아 {@code native/sqlite-vec/<플랫폼>/vec0.*} 로, 같은 버전을 {@code native/sqlite-vec/VERSION} 으로 jar 에
 * 넣는다. 이 클래스는 그 파일들을 읽을 뿐이다.
 *
 * <p>실행 중인 앱이 jar 안의 파일을 직접 로드할 수는 없다(SQLite 는 파일 경로를 받는다) — 그래서 {@code <targetDir>/sqlite-vec-<버전>/}
 * 아래로 꺼낸다. 폴더 이름에 버전이 들어가므로 롤백한 옛 앱과 새 앱이 서로의 파일을 덮지 않고(Windows 는 로드 중인 DLL 을 못 바꾼다),
 * 이미 같은 내용이 있으면 다시 쓰지 않는다.
 *
 * <p>{@code SQLITE_VEC_EXTENSION_PATH}(운영자가 직접 준 바이너리)가 있으면 이 클래스는 쓰이지 않는다 — {@link DataSourceConfig} 가 정한다.
 */
public final class VecExtension {

    static final String RESOURCE_ROOT = "native/sqlite-vec/";
    static final String VERSION_RESOURCE = RESOURCE_ROOT + "VERSION";

    private VecExtension() {
    }

    /** 이 빌드가 확장을 jar 에 넣었는가({@code -Dsqlite-vec.skip=true} 로 빌드했으면 없다). */
    public static boolean isBundled() {
        return VecExtension.class.getClassLoader().getResource(VERSION_RESOURCE) != null;
    }

    /** 빌드가 jar 에 넣은 {@code native/sqlite-vec/VERSION} 의 값(예: {@code 0.1.9}). */
    public static String version() throws IOException {
        try (InputStream in = open(VERSION_RESOURCE)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    /**
     * 이 JVM 의 플랫폼 이름 — {@code windows-x86_64} · {@code macos-aarch64} · {@code macos-x86_64} · {@code linux-x86_64} ·
     * {@code linux-aarch64} 중 하나. 그 밖이면 {@link IllegalStateException}.
     */
    public static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return platform(os, System.getProperty("os.arch", "").toLowerCase(Locale.ROOT), os.contains("linux") && isMusl());
    }

    /**
     * 순수 판정 — 시스템 속성 없이 시험한다. {@code musl} 은 Alpine 같은 musl libc 배포인지: 릴리즈의 Linux 파일은 glibc 용이라
     * musl 에서는 로드 때 "Error loading shared library ld-linux-…" 로 불투명하게 실패하므로 여기서 미리 이유를 말한다.
     */
    static String platform(String os, String arch, boolean musl) {
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");

        if (os.contains("win") && !os.contains("darwin") && x64) return "windows-x86_64";
        if (os.contains("mac") || os.contains("darwin")) {
            if (arm64) return "macos-aarch64";
            if (x64) return "macos-x86_64";
        }
        if (os.contains("linux") && (x64 || arm64)) {
            if (musl) {
                throw new IllegalStateException("jar 에 든 sqlite-vec 의 Linux 파일은 glibc 용이라 musl(Alpine) 에서는 로드되지 않습니다. "
                        + "musl 용으로 빌드한 vec0 를 SQLITE_VEC_EXTENSION_PATH 로 지정하거나 glibc 기반 이미지(Debian/Ubuntu 계열)를 쓰세요.");
            }
            return x64 ? "linux-x86_64" : "linux-aarch64";
        }
        throw new IllegalStateException("sqlite-vec 미지원 플랫폼: os=" + os + ", arch=" + arch
                + " (jar 에 든 것: windows-x86_64, macos-aarch64, macos-x86_64, linux-x86_64, linux-aarch64 — "
                + "그 밖에서는 vec0 를 직접 받아 SQLITE_VEC_EXTENSION_PATH 로 지정하세요. 32비트 JVM/OS 도 해당합니다)");
    }

    /** {@code /lib/ld-musl-*.so.1} 이 있으면 musl libc 다. 읽지 못하면 아니라고 본다. */
    static boolean isMusl() {
        try (DirectoryStream<Path> s = Files.newDirectoryStream(Path.of("/lib"), "ld-musl-*.so.1")) {
            return s.iterator().hasNext();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    static String fileName(String platform) {
        if (platform.startsWith("windows")) return "vec0.dll";
        return platform.startsWith("macos") ? "vec0.dylib" : "vec0.so";
    }

    /** 현재 플랫폼의 확장을 {@code targetDir/sqlite-vec-<버전>/} 아래로 꺼내고 그 파일의 절대경로를 돌려준다. */
    public static Path extractTo(Path targetDir) throws IOException {
        String platform = platform();
        String name = fileName(platform);
        Path target = targetDir.resolve("sqlite-vec-" + version()).resolve(name).toAbsolutePath().normalize();

        byte[] bytes;
        try (InputStream in = open(RESOURCE_ROOT + platform + "/" + name)) {
            bytes = in.readAllBytes();
        }
        // 크기만이 아니라 내용까지 같을 때만 건너뛴다 — 같은 버전 폴더에 깨진 파일이 남은 경우를 놓치지 않는다
        if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), bytes)) {
            return target;
        }
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), name, ".tmp");
        try {
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return target;
    }

    private static InputStream open(String resource) {
        InputStream in = VecExtension.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException("이 빌드에는 sqlite-vec 확장이 들어 있지 않습니다(classpath 에 " + resource + " 없음) — "
                    + "mvn 으로 다시 빌드하거나(-Dsqlite-vec.skip=true 로 빌드했다면 빼고) SQLITE_VEC_EXTENSION_PATH 로 직접 지정하세요.");
        }
        return in;
    }
}
