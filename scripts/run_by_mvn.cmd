@echo off
REM ASCII ONLY - do not add Korean text to this file. cmd.exe tracks a byte
REM offset while decoding it, and DBCS characters make that offset drift so
REM comment fragments get executed as commands. This happens even when the
REM file encoding matches the console code page.
REM
REM Runs the app from source with Maven (no Docker): loads .env, then
REM "mvn spring-boot:run" on port 8081. Works from any folder, including a
REM double-click in Explorer: it moves to the project root first.
REM The sqlite-vec extension is bundled in the build, so .env needs no
REM SQLITE_VEC_EXTENSION_PATH on Windows x64.
chcp 65001 >nul
setlocal enabledelayedexpansion

REM project root = the folder above this script
cd /d "%~dp0.."

echo =======================================================
echo working directory: %CD%

set "ENV_FILE=.env"
if not exist "%ENV_FILE%" (
    echo ERROR: .env not found at %CD%\%ENV_FILE%
    echo        copy .env.example .env, fill in the values and run again.
    goto :end
)

where mvn >nul 2>&1
if errorlevel 1 (
    echo ERROR: mvn not found on PATH - install Maven 3.9+ with Java 21.
    goto :end
)

echo Loading...
for /f "usebackq tokens=1,* delims==" %%A in (`findstr /v "^#" .env`) do SET %%A=%%B

REM overrides the .env value - change this line to use another port
set "SERVER_PORT=8081"

REM this script starts no Chroma server: with the chroma backend it must already run
if /i not "%VECTORSTORE_TYPE%"=="sqlite-vec" (
    echo WARNING: VECTORSTORE_TYPE=%VECTORSTORE_TYPE% - this script does not start Chroma.
    echo          Chroma must already be running, or set VECTORSTORE_TYPE=sqlite-vec in .env.
)

echo =======================================================
echo Starting... port: !SERVER_PORT!
echo =======================================================

REM "call" is required: mvn is mvn.cmd, and without call this script would end
REM when Maven exits (the lines below would never run).
call mvn spring-boot:run
REM call mvn spring-boot:run >> run.log 2>&1

echo =======================================================
echo Done.
echo =======================================================

:end
endlocal
pause
