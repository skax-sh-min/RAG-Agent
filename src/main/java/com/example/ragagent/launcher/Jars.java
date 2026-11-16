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

    /** 앱 jar 매니페스트에서 "이 버전을 올리려면 런처가 이 프로토콜 이상이어야 한다"를 적는 속성. */
    static final String MIN_LAUNCHER_PROTOCOL = "Min-Launcher-Protocol";

    /**
     * 앱 jar 가 요구하는 최소 런처 프로토콜. 속성이 없거나 숫자가 아니거나 jar 를 못 읽으면 1(첫 런처부터 지키는 기준선)이다 —
     * 이 값이 1 보다 클 때만 의미가 있고, 릴리즈를 만들 때 이 값이 숫자임을 {@code release.sh} 가 확인한다.
     */
    static int minLauncherProtocol(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry e = zip.getEntry("META-INF/MANIFEST.MF");
            if (e == null) return 1;
            try (InputStream in = zip.getInputStream(e)) {
                String text = new String(in.readNBytes(256 * 1024), StandardCharsets.UTF_8);
                for (String line : text.split("\\R")) {
                    if (line.regionMatches(true, 0, MIN_LAUNCHER_PROTOCOL + ":", 0, MIN_LAUNCHER_PROTOCOL.length() + 1)) {
                        return Math.max(1, Integer.parseInt(line.substring(MIN_LAUNCHER_PROTOCOL.length() + 1).strip()));
                    }
                }
                return 1;
            }
        } catch (IOException | NumberFormatException e) {
            return 1;
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
