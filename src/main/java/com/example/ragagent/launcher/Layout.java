package com.example.ragagent.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 설치 폴더의 배치 — <b>버전마다 자기 폴더</b>에 jar 를 두고, 지금 쓰는 버전은 {@code current.txt} 한 줄이 가리킨다.
 * <pre>
 * versions/0.5.6/rag-agent-0.5.6.jar
 * versions/0.5.7/rag-agent-0.5.7.jar
 * current.txt                          ← 0.5.7
 * </pre>
 * 예전처럼 {@code rag-agent.jar} 를 덮어쓰지 않는다. 그래서 (1) 실행 중이던 파일을 바꾸다 Windows 의 파일 잠금에 걸릴 일이 없고
 * (2) 교체와 롤백이 모두 "포인터 파일 하나를 원자적으로 바꾸는 것"이며 (3) 이전 버전이 파일째 남아 있어 되돌릴 것이 있다.
 */
final class Layout {

    /** {@code 0.5.7} · {@code 0.5.6-SNAPSHOT} 같은 모양만 — 값이 폴더 이름이 되므로 {@code ../} 같은 것은 막는다. */
    private static final Pattern VERSION = Pattern.compile("[0-9]+(\\.[0-9]+){1,3}(-[A-Za-z0-9.]+)?");

    private final Path home;

    Layout(Path home) {
        this.home = home;
    }

    static boolean validVersion(String v) {
        return v != null && v.length() <= 64 && VERSION.matcher(v).matches();
    }

    Path versionsDir() {
        return home.resolve("versions");
    }

    Path pointer() {
        return home.resolve("current.txt");
    }

    Path jarOf(String version) {
        return versionsDir().resolve(version).resolve("rag-agent-" + version + ".jar");
    }

    /** 지금 쓰는 버전. 포인터가 없거나 이상한 값이면 비어 있다. */
    Optional<String> current() throws IOException {
        if (!Files.isRegularFile(pointer())) return Optional.empty();
        String v = new String(Files.readAllBytes(pointer()), StandardCharsets.UTF_8).strip();
        return validVersion(v) ? Optional.of(v) : Optional.empty();
    }

    /** 포인터를 바꾼다 — 임시 파일에 쓰고 원자적으로 이름을 바꾼다(교체의 마지막 한 걸음). */
    void setCurrent(String version) throws IOException {
        if (!validVersion(version)) throw new IOException("invalid version: " + version);
        Path tmp = home.resolve("current.txt.tmp");
        Files.write(tmp, (version + "\n").getBytes(StandardCharsets.UTF_8));
        Props.moveReplacing(tmp, pointer());
    }

    /**
     * {@code staged} 를 {@code versions/<version>/} 으로 옮긴다. 이미 같은 내용이 있으면 그대로 두고(멱등 — 교체 도중 런처가 죽었다 다시
     * 뜬 경우), <b>내용이 다르면 덮어쓰지 않고 실패</b>한다(같은 버전 이름에 다른 jar 가 들어오는 것은 이상 상황이다).
     *
     * @param keepSource true 면 복사한다(첫 설치 — 원본을 지우지 않는다), false 면 옮긴다(업데이트로 받은 파일)
     */
    void install(String version, Path staged, boolean keepSource) throws IOException {
        if (!validVersion(version)) throw new IOException("invalid version: " + version);
        Path target = jarOf(version);
        Files.createDirectories(target.getParent());
        if (Files.exists(target)) {
            if (!Jars.sha256(target).equals(Jars.sha256(staged))) {
                throw new IOException("versions/" + version + " already holds a different jar");
            }
            if (!keepSource) Files.deleteIfExists(staged);
            return;
        }
        Path part = target.resolveSibling(target.getFileName() + ".part");
        Files.copy(staged, part, StandardCopyOption.REPLACE_EXISTING);
        Props.moveReplacing(part, target);          // 완성본만 이 이름을 갖는다
        if (!keepSource) Files.deleteIfExists(staged);
    }

    /** 설치된 버전 폴더들 — 새로 설치한(폴더를 만든) 것부터. */
    List<String> installed() throws IOException {
        if (!Files.isDirectory(versionsDir())) return List.of();
        try (Stream<Path> dirs = Files.list(versionsDir())) {
            List<Path> list = new ArrayList<>(dirs.filter(Files::isDirectory)
                    .filter(d -> validVersion(d.getFileName().toString())).toList());
            list.sort(Comparator.comparingLong((Path d) -> d.toFile().lastModified()).reversed());
            return list.stream().map(d -> d.getFileName().toString()).toList();
        }
    }

    /**
     * 오래된 버전을 지운다 — 최근 {@code keep} 개와 {@code protect}(지금 버전·이전 버전)는 남긴다. 지우다 실패해도(Windows 에서 다른
     * 프로세스가 쥐고 있는 경우 등) 업데이트를 막지 않는다. 지운 버전 목록을 돌려준다.
     */
    List<String> prune(int keep, Set<String> protect) throws IOException {
        List<String> all = installed();
        List<String> removed = new ArrayList<>();
        for (int i = Math.max(1, keep); i < all.size(); i++) {
            String v = all.get(i);
            if (protect.contains(v)) continue;
            if (deleteTree(versionsDir().resolve(v))) removed.add(v);
        }
        return removed;
    }

    private static boolean deleteTree(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
