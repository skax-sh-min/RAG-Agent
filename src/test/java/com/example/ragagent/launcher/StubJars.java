package com.example.ragagent.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

/** {@link StubApp} 을 담은 실행 가능한 jar 를 만든다 — 런처가 진짜 {@code java -jar} 로 띄울 수 있고 {@code build.version} 도 들어 있다. */
final class StubJars {

    private StubJars() {}

    /** {@code dir/rag-agent-<version>.jar}. 같은 behavior 라도 jar 마다 내용이 다르도록 버전·행동이 안에 들어간다. */
    static Path create(Path dir, String version, String behavior) throws IOException {
        Files.createDirectories(dir);
        Path jar = dir.resolve("rag-agent-" + version + ".jar");
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS, StubApp.class.getName());
        try (OutputStream os = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(os, mf)) {
            out.putNextEntry(new ZipEntry(StubApp.class.getName().replace('.', '/') + ".class"));
            try (InputStream in = StubApp.class.getResourceAsStream("StubApp.class")) {
                out.write(in.readAllBytes());
            }
            out.closeEntry();
            put(out, "META-INF/build-info.properties", "build.name=rag-agent\nbuild.version=" + version + "\n");
            put(out, "stub-behavior.txt", behavior + "\n");
            put(out, "stub-version.txt", version + "\n");
        }
        return jar;
    }

    private static void put(JarOutputStream out, String name, String text) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }
}
