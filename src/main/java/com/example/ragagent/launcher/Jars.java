package com.example.ragagent.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** jar 파일에 대한 두 가지 질문 — SHA-256 은 무엇이고, 안에 적힌 빌드 버전은 무엇인가. */
final class Jars {

    private Jars() {}

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code META-INF/build-info.properties} 의 {@code build.version}. 없거나 jar 가 아니면 null. */
    static String buildVersion(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry e = zip.getEntry("META-INF/build-info.properties");
            if (e == null) return null;
            try (InputStream in = zip.getInputStream(e)) {
                String text = new String(in.readNBytes(64 * 1024), StandardCharsets.UTF_8);
                for (String line : text.split("\\R")) {
                    if (line.startsWith("build.version=")) return line.substring("build.version=".length()).strip();
                }
                return null;
            }
        } catch (IOException e) {
            return null;
        }
    }
}
