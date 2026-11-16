package com.example.ragagent.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 런처 설정 — 환경변수와 설치 폴더의 {@code .env}(같은 이름이면 {@code .env} 가 이긴다. 기존 실행 스크립트와 같은 규칙).
 * {@code .env} 의 값은 자식(앱) 프로세스에도 환경변수로 그대로 넘어간다.
 *
 * <p>환경변수 이름: {@code JAVA_BIN} · {@code JAVA_OPTS} · {@code DATA_DIR} · {@code UPDATE_DIR} ·
 * {@code SERVER_PORT} · {@code RAG_HEALTH_URL} · {@code RAG_HEALTH_TIMEOUT} · {@code RAG_HEALTH_INTERVAL} ·
 * {@code RAG_RESTART_DELAY} · {@code RAG_CRASH_LIMIT} · {@code RAG_CRASH_WINDOW}, 그리고 {@code RAG_KEEP_VERSIONS}(기본 3).
 */
final class LauncherConfig {

    private static final Pattern ENV_LINE = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$");

    final Path home;
    final Path dataDir;
    final Path updateDir;
    final String javaBin;
    final List<String> jvmOpts;
    final String healthUrl;
    final long healthTimeoutMs;
    final long healthIntervalMs;
    final long restartDelayMs;
    final int crashLimit;
    final long crashWindowMs;
    final int keepVersions;
    /** 첫 설치에 쓸 jar({@code --jar}) — 없으면 설치 폴더에서 찾는다. */
    final Path bootstrapJar;
    /** {@code .env} 의 값 — 자식 프로세스의 환경변수에 덮어쓴다. */
    final Map<String, String> overrides;

    private LauncherConfig(Path home, Map<String, String> overrides, Map<String, String> base, Path bootstrapJar) {
        this.home = home;
        this.overrides = overrides;
        this.bootstrapJar = bootstrapJar;
        java.util.function.BiFunction<String, String, String> get = (name, def) -> {
            String v = overrides.get(name);
            if (v == null || v.isEmpty()) v = base.get(name);
            return v == null || v.isEmpty() ? def : v;
        };
        this.dataDir = home.resolve(get.apply("DATA_DIR", "./data")).normalize();
        this.updateDir = home.resolve(get.apply("UPDATE_DIR", dataDir.resolve("updates").toString())).normalize();
        String windows = System.getProperty("os.name", "").toLowerCase().contains("windows") ? "java.exe" : "java";
        this.javaBin = get.apply("JAVA_BIN", Path.of(System.getProperty("java.home"), "bin", windows).toString());
        String opts = get.apply("JAVA_OPTS", "").strip();
        this.jvmOpts = opts.isEmpty() ? List.of() : Arrays.asList(opts.split("\\s+"));
        String port = get.apply("SERVER_PORT", "8080");
        this.healthUrl = get.apply("RAG_HEALTH_URL", "http://127.0.0.1:" + port + "/api/v1/health");
        this.healthTimeoutMs = seconds(get.apply("RAG_HEALTH_TIMEOUT", "180"), 180) * 1000L;
        // RAG_HEALTH_INTERVAL_MS 는 테스트가 폴링을 촘촘히 하려는 숨은 스위치다
        this.healthIntervalMs = overrides.containsKey("RAG_HEALTH_INTERVAL_MS")
                ? Math.max(20, seconds(overrides.get("RAG_HEALTH_INTERVAL_MS"), 2000))
                : seconds(get.apply("RAG_HEALTH_INTERVAL", "2"), 2) * 1000L;
        this.restartDelayMs = seconds(get.apply("RAG_RESTART_DELAY", "5"), 5) * 1000L;
        this.crashLimit = (int) Math.max(1, seconds(get.apply("RAG_CRASH_LIMIT", "5"), 5));
        this.crashWindowMs = seconds(get.apply("RAG_CRASH_WINDOW", "300"), 300) * 1000L;
        this.keepVersions = (int) Math.max(1, seconds(get.apply("RAG_KEEP_VERSIONS", "3"), 3));
    }

    /** 읽을 수 없는 숫자는 기본값으로 — 설정 오타로 런처가 못 뜨는 것보다 낫다. */
    private static long seconds(String text, long def) {
        try {
            long v = Long.parseLong(text.strip());
            return v < 0 ? def : v;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static LauncherConfig load(Path home, Map<String, String> baseEnv, Path bootstrapJar) throws IOException {
        Path envFile = home.resolve(".env");
        Map<String, String> overrides = Files.isRegularFile(envFile)
                ? parseEnv(new String(Files.readAllBytes(envFile), StandardCharsets.UTF_8))
                : new LinkedHashMap<>();
        return new LauncherConfig(home, overrides, baseEnv, bootstrapJar);
    }

    /**
     * {@code KEY=VALUE} 줄을 읽는다 — {@code #} 로 시작하는 줄과 빈 줄은 건너뛰고, 값 바깥의 따옴표 한 쌍은 벗긴다(값 안의 {@code =} 와
     * 공백은 그대로). 셸이 {@code .env} 를 읽는 방식과 같은 결과를 내려는 것이다.
     */
    static Map<String, String> parseEnv(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        if (text.startsWith("﻿")) text = text.substring(1);
        for (String line : text.split("\\R")) {
            Matcher m = ENV_LINE.matcher(line);
            if (!m.matches()) continue;
            String v = m.group(2).strip();
            if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
                v = v.substring(1, v.length() - 1);
            }
            values.put(m.group(1), v);
        }
        return values;
    }

    /** 자식 프로세스 명령: {@code java <JAVA_OPTS> -jar <jar>}. */
    List<String> command(Path jar) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.addAll(jvmOpts);
        cmd.add("-jar");
        cmd.add(jar.toString());
        return cmd;
    }
}
