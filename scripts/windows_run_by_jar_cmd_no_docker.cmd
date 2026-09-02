@echo off
REM ASCII ONLY - do not add Korean text to this file. cmd.exe tracks a byte
REM offset while decoding it, and DBCS characters make that offset drift so
REM comment fragments get executed as commands. This happens even when the
REM file encoding matches the console code page.
chcp 65001 >nul
setlocal enabledelayedexpansion

echo =======================================================
echo working directory: %CD%

set "ENV_FILE=.env"
if not exist "%ENV_FILE%" (
    echo ERROR: .env not found at %CD%\%ENV_FILE%
    goto :end
)

echo Loading...
for /f "usebackq tokens=1,* delims==" %%A in (`findstr /v "^#" .env`) do SET %%A=%%B

set "SERVER_PORT=8081"

set "JAR_NAME="
for /f "delims=" %%f in ('dir /b "%CD%\target\rag-agent-*.jar" 2^>nul') do (
    set "JAR_NAME=%%~nxf"
    echo JAR_NAME=!JAR_NAME!
)
REM No build output, but a previously copied data\rag-agent.jar exists: run that
REM one instead of failing. This is the "mvn clean, then start again without
REM rebuilding" case - the copy would have nothing to copy from.
set "SKIP_COPY="
if not defined JAR_NAME (
    if exist "%CD%\data\rag-agent.jar" (
        echo.
        echo NOTICE: no rag-agent-*.jar under %CD%\target
        echo         running the existing data\rag-agent.jar instead ^(no copy^).
        echo         To run the latest build, stop and run: mvn clean package -DskipTests
        echo.
        REM timeout fails when stdin is redirected; fall back to ping.
        timeout /t 3 /nobreak >nul 2>&1 || ping -n 4 127.0.0.1 >nul 2>&1
        set "SKIP_COPY=1"
        set "JAR_NAME=rag-agent.jar (existing)"
    ) else (
        echo ERROR: no rag-agent-*.jar under %CD%\target and no data\rag-agent.jar - build it first.
        goto :end
    )
)

echo =======================================================
echo Starting... !JAR_NAME!  port: !SERVER_PORT!
echo =======================================================

REM "cp" is not a Windows command; "copy" does not create the target folder.
if not defined SKIP_COPY (
    if not exist "%CD%\data" mkdir "%CD%\data"
    copy /Y "%CD%\target\!JAR_NAME!" "%CD%\data\rag-agent.jar" >nul
    if errorlevel 1 (
        echo ERROR: failed to copy the jar. A running app may be locking data\rag-agent.jar.
        goto :end
    )
)

REM -D must precede -jar, otherwise it is passed to the app and ignored.
REM stdout/stderr.encoding are what actually control console+redirect output
REM on JDK 19+; file.encoding alone does not.
set "JAVA_ENC=-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"

java %JAVA_ENC% -jar "data\rag-agent.jar"
REM java %JAVA_ENC% -jar "data\rag-agent.jar" >> run.log 2>&1

echo =======================================================
echo Done.
echo =======================================================

:end
endlocal
pause
