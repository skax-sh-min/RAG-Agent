@echo off
REM ASCII ONLY - do not add Korean text to this file. cmd.exe tracks a byte
REM offset while decoding it, and DBCS characters make that offset drift so
REM comment fragments get executed as commands.
REM
REM Starts rag-agent.jar under the supervisor, which applies updates staged by
REM the app (jar swap + health check + rollback). All the logic is in
REM rag-agent-supervisor.ps1 next to this file. Run it from the install
REM directory (rag-agent.jar, .env, data\) - or from the scripts folder, the
REM supervisor moves up one level by itself.
chcp 65001 >nul
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0rag-agent-supervisor.ps1" %*
exit /b %ERRORLEVEL%
