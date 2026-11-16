package com.example.ragagent.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@code scripts/rag-agent-supervisor.sh} 를 실제 bash 로 돌린다. Windows 에서는 Git Bash 가 있어야 하고(WSL 의 bash.exe 는 쓰지 않는다 —
 * 경로 체계가 달라 Windows 경로의 마커를 못 읽는다), {@code curl}(헬스 확인)이 있어야 한다. 없으면 스킵한다.
 * 시나리오는 {@link AbstractSupervisorScenarios}.
 */
@DisplayName("감독 스크립트(bash)")
class SupervisorBashScriptTest extends AbstractSupervisorScenarios {

    private static final Path SCRIPT = Path.of("scripts", "rag-agent-supervisor.sh").toAbsolutePath();

    private static String findBash() {
        String fromEnv = System.getenv("RAG_TEST_BASH");
        for (String c : new String[]{fromEnv, "C:/Program Files/Git/bin/bash.exe", "/bin/bash", "/usr/bin/bash"}) {
            if (c != null && Files.isExecutable(Path.of(c))) return c;
        }
        return null;
    }

    private static boolean curlAvailable(String bash) {
        try {
            Process p = new ProcessBuilder(bash, "-c", "command -v curl >/dev/null 2>&1").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    @Override
    void assumeRunnable() {
        String bash = findBash();
        Assumptions.assumeTrue(bash != null, "bash 가 없어 스킵한다");
        Assumptions.assumeTrue(Files.exists(SCRIPT), "스크립트가 없다: " + SCRIPT);
        Assumptions.assumeTrue(curlAvailable(bash), "curl 이 없어 스킵한다");
    }

    @Override
    String installStub(Path binDir) throws IOException {
        Path stub = binDir.resolve("java");
        Files.writeString(stub, String.join("\n",
                "#!/bin/sh",
                "jar=\"\"",
                "while [ $# -gt 0 ]; do",
                "  if [ \"$1\" = \"-jar\" ]; then jar=\"$2\"; shift; fi",
                "  shift",
                "done",
                "behavior=$(sed -n 's/^BEHAVIOR=//p' \"$jar\" | head -n 1 | tr -d '\\r')",
                "version=$(sed -n 's/^VERSION=//p' \"$jar\" | head -n 1 | tr -d '\\r')",
                "echo \"start $version $behavior\" >> \"$STUB_LOG\"",
                "rm -f \"$STUB_FLAG\"",
                "case \"$behavior\" in",
                "  healthy-exit0) touch \"$STUB_FLAG\"; sleep 1; exit 0 ;;",
                "  crash) echo migrated > \"$STUB_DB\"; exit 1 ;;",
                "  hang) exec sleep 600 ;;",
                "  exit42-once)",
                "    if [ ! -f \"$STUB_STATE/once\" ]; then touch \"$STUB_STATE/once\"; exit 42; fi",
                "    touch \"$STUB_FLAG\"; sleep 1; exit 0 ;;",
                "  quick-exit0) exit 0 ;;",
                "esac",
                "exit 99",
                "") );
        stub.toFile().setExecutable(true);
        return stub.toAbsolutePath().toString().replace('\\', '/');
    }

    @Override
    List<String> command() {
        return List.of(findBash(), SCRIPT.toString().replace('\\', '/'));
    }

    @Override
    String healthUrl(Path flag) {
        String p = flag.toAbsolutePath().toString().replace('\\', '/');
        return p.startsWith("/") ? "file://" + p : "file:///" + p;
    }
}
