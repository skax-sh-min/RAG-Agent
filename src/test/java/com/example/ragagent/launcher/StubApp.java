package com.example.ragagent.launcher;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 런처 테스트용 가짜 앱 — {@link StubJars} 가 이 클래스를 jar 에 담아 진짜 {@code java -jar} 로 띄운다(스크립트 없이 어느 OS 에서나 같다).
 * 어떻게 행동할지는 jar 안의 {@code stub-behavior.txt} 가 정한다:
 * <ul>
 *   <li>{@code healthy-exit0} — {@code SERVER_PORT} 에서 {@code /api/v1/health} 200 을 내다가 잠시 뒤 정상 종료</li>
 *   <li>{@code healthy-then-crash} — 헬스는 통과하고 잠시 뒤 비정상 종료(1) — 시험을 통과한 뒤의 크래시</li>
 *   <li>{@code crash} — DB 파일({@code STUB_DB})을 "마이그레이션"해 놓고 곧바로 비정상 종료(1) — 기동 중에 죽는 새 버전</li>
 *   <li>{@code hang} — 아무 응답 없이 매달림</li>
 *   <li>{@code exit42-once} — 처음에는 종료 코드 42, 두 번째부터는 정상</li>
 *   <li>{@code quick-exit0} — 곧바로 정상 종료</li>
 * </ul>
 * 시작할 때마다 {@code STUB_LOG} 에 한 줄({@code start <버전> <행동>})과 받은 환경(RAG_SUPERVISED·UPDATE_DIR·JAVA_OPTS 의 -D·.env 값)을 남긴다.
 * <b>내부 클래스·익명 클래스를 만들지 않는다</b> — jar 에 이 클래스 파일 하나만 담기 때문이다.
 */
public final class StubApp {

    private StubApp() {}

    public static void main(String[] args) throws Exception {
        String behavior = resource("/stub-behavior.txt");
        String version = resource("/stub-version.txt");
        append("start " + version + " " + behavior);
        append("env supervised=" + System.getenv("RAG_SUPERVISED") + " updateDir=" + System.getenv("UPDATE_DIR")
                + " flag=" + System.getProperty("stub.flag") + " x=" + System.getenv("STUB_X")
                + " launcherPid=" + System.getenv("RAG_LAUNCHER_PID") + " launcherStarted=" + System.getenv("RAG_LAUNCHER_STARTED"));
        switch (behavior) {
            case "healthy-exit0" -> {
                serve();
                Thread.sleep(2000);
                System.exit(0);
            }
            case "healthy-then-crash" -> {
                serve();
                Thread.sleep(1500);
                System.exit(1);
            }
            case "crash" -> {
                Files.writeString(Path.of(System.getenv("STUB_DB")), "migrated");
                System.exit(1);
            }
            case "hang" -> Thread.sleep(600_000);
            case "exit42-once" -> {
                Path once = Path.of(System.getenv("STUB_STATE"), "once");
                if (!Files.exists(once)) {
                    Files.writeString(once, "x");
                    System.exit(42);
                }
                serve();
                Thread.sleep(2000);
                System.exit(0);
            }
            case "quick-exit0" -> System.exit(0);
            default -> System.exit(99);
        }
    }

    private static void serve() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", Integer.parseInt(System.getenv("SERVER_PORT"))), 0);
        server.createContext("/api/v1/health", exchange -> {
            byte[] body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = StubApp.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
    }

    private static void append(String line) throws IOException {
        Files.writeString(Path.of(System.getenv("STUB_LOG")), line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
