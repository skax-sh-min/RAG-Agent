package com.example.ragagent.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code scripts/rag-agent-supervisor.ps1} 를 Windows PowerShell 로 돌린다 — Windows 에서만(다른 OS 는 스킵). 가짜 java 는
 * {@code java.cmd}(인자 {@code -jar <경로>} 의 경로를 {@code stub-java.ps1} 에 넘긴다)이고, 시나리오는 bash 판과 같은
 * {@link AbstractSupervisorScenarios} 다.
 */
@DisplayName("감독 스크립트(PowerShell)")
class SupervisorPowerShellScriptTest extends AbstractSupervisorScenarios {

    private static final Path SCRIPT = Path.of("scripts", "rag-agent-supervisor.ps1").toAbsolutePath();

    @Override
    void assumeRunnable() {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("windows"), "Windows 에서만 돈다");
        Assumptions.assumeTrue(Files.exists(SCRIPT), "스크립트가 없다: " + SCRIPT);
    }

    @Override
    String installStub(Path binDir) throws IOException {
        Files.writeString(binDir.resolve("stub-java.ps1"), String.join("\r\n",
                "param([string]$Jar)",
                "$lines = Get-Content -LiteralPath $Jar",
                "$behavior = ($lines | Where-Object { $_ -like 'BEHAVIOR=*' } | Select-Object -First 1).Substring(9).Trim()",
                "$version = ($lines | Where-Object { $_ -like 'VERSION=*' } | Select-Object -First 1).Substring(8).Trim()",
                "Add-Content -LiteralPath $env:STUB_LOG -Value \"start $version $behavior\"",
                "Remove-Item -LiteralPath $env:STUB_FLAG -Force -ErrorAction SilentlyContinue",
                "switch ($behavior) {",
                "  'healthy-exit0' { New-Item -ItemType File -Force -Path $env:STUB_FLAG | Out-Null; Start-Sleep -Seconds 1; exit 0 }",
                "  'crash' { Set-Content -LiteralPath $env:STUB_DB -Value 'migrated'; exit 1 }",
                "  'hang' { Start-Sleep -Seconds 20; exit 0 }",
                "  'exit42-once' {",
                "    $once = Join-Path $env:STUB_STATE 'once'",
                "    if (-not (Test-Path -LiteralPath $once)) { New-Item -ItemType File -Force -Path $once | Out-Null; exit 42 }",
                "    New-Item -ItemType File -Force -Path $env:STUB_FLAG | Out-Null; Start-Sleep -Seconds 1; exit 0",
                "  }",
                "  'quick-exit0' { exit 0 }",
                "}",
                "exit 99",
                ""));
        Path cmd = binDir.resolve("java.cmd");
        Files.writeString(cmd, String.join("\r\n",
                "@echo off",
                "powershell.exe -NoProfile -ExecutionPolicy Bypass -File \"%~dp0stub-java.ps1\" \"%~2\"",
                "exit /b %ERRORLEVEL%",
                ""));
        return cmd.toAbsolutePath().toString();
    }

    @Override
    List<String> command() {
        return List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", SCRIPT.toString());
    }

    @Override
    String healthUrl(Path flag) {
        return "file:///" + flag.toAbsolutePath().toString().replace('\\', '/');
    }
}
