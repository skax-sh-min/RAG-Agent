package com.example.ragagent.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 앱과 런처가 주고받는 {@code key=value} 파일(pending · trial · result) 읽기·쓰기. <b>줄 단위, 이스케이프 없음, BOM 없음, LF</b> —
 * {@code java.util.Properties} 를 쓰지 않는 이유는 그것이 Windows 경로의 역슬래시와 콜론을 이스케이프해 값이 달라지기 때문이다
 * (앱 쪽 {@code UpdateApplyService.writePending} 과 같은 규칙).
 *
 * <p>이 패키지는 <b>JDK 만</b> 쓴다 — 런처 jar 는 Spring 도 로깅 라이브러리도 싣지 않는 작은 jar 이기 때문이다
 * ({@code LauncherJdkOnlyConventionTest} 가 막는다).
 */
final class Props {

    private Props() {}

    /** 파일이 없으면 빈 맵. 빈 줄·{@code #} 주석·{@code =} 없는 줄은 건너뛰고, 첫 줄의 BOM 은 무시한다. */
    static Map<String, String> read(Path file) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) return values;
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1);
        for (String line : text.split("\\R")) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            values.put(t.substring(0, eq).strip(), t.substring(eq + 1).strip());
        }
        return values;
    }

    /** 임시 파일에 쓰고 이름을 바꾼다 — 읽는 쪽이 반쯤 쓴 파일을 보지 않게. */
    static void write(Path file, Map<String, String> values) throws IOException {
        StringBuilder sb = new StringBuilder();
        values.forEach((k, v) -> sb.append(k).append('=').append(v == null ? "" : v).append('\n'));
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, sb.toString().getBytes(StandardCharsets.UTF_8));
        moveReplacing(tmp, file);
    }

    static void moveReplacing(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
