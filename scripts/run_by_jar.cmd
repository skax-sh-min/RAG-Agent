@echo off
REM ASCII ONLY - do not add Korean text to this file. cmd.exe tracks a byte
REM offset while decoding it, and DBCS characters make that offset drift so
REM comment fragments get executed as commands. This happens even when the
REM file encoding matches the console code page.
REM
REM Runs an already built jar (no Docker): loads .env, copies the newest
REM target\rag-agent-X.Y.Z.jar to data\rag-agent.jar and runs that. Pass -b
REM to build first, or -cb to clean and build first. Works from any folder, including a
REM double-click in Explorer: it moves to the project root first.
REM The sqlite-vec extension is bundled in the jar, so .env needs no
REM SQLITE_VEC_EXTENSION_PATH on Windows x64.
chcp 65001 >nul
setlocal enabledelayedexpansion

set "BUILD_MODE="
if "%~1"=="" goto :args_done
if /i "%~1"=="-h" goto :help
if /i "%~1"=="--help" goto :help
if /i "%~1"=="-b" set "BUILD_MODE=build"
if /i "%~1"=="--build" set "BUILD_MODE=build"
if /i "%~1"=="-cb" set "BUILD_MODE=clean"
if /i "%~1"=="--clean-build" set "BUILD_MODE=clean"
if not defined BUILD_MODE goto :bad_args
if not "%~2"=="" goto :bad_args

:args_done
REM project root = the folder above this script
cd /d "%~dp0.."

echo =======================================================
echo working directory: %CD%

set "ENV_FILE=.env"
if not exist "%ENV_FILE%" (
    echo ERROR: .env not found at %CD%\%ENV_FILE%
    echo        copy .env.example .env, fill in the values and run again.
    set "EXIT_CODE=1"
    goto :end
)

echo Loading...
for /f "usebackq tokens=1,* delims==" %%A in (`findstr /v "^#" .env`) do SET %%A=%%B

set "SERVER_PORT=8081"

if defined BUILD_MODE (
    where mvn >nul 2>&1
    if errorlevel 1 (
        echo ERROR: mvn not found on PATH - install Maven 3.9+ with Java 21.
        set "EXIT_CODE=1"
        goto :end
    )
    if "!BUILD_MODE!"=="clean" (
        call mvn clean package
    ) else (
        call mvn package
    )
    if errorlevel 1 (
        echo ERROR: build failed; the application will not start.
        set "EXIT_CODE=1"
        goto :end
    )
)

REM The build also writes rag-agent-X.Y.Z-launcher.jar next to the app jar (the
REM small launcher for in-app updates) - skip it, it is not the application.
REM /o:d lists oldest first, so the last match is the newest build.
set "JAR_NAME="
if defined BUILD_MODE (
    REM Maven records the version of the jar just built, even if an older
    REM version in target has a newer timestamp.
    if exist "%CD%\target\maven-archiver\pom.properties" (
        for /f "tokens=1,* delims==" %%A in ('findstr /b /c:"version=" "%CD%\target\maven-archiver\pom.properties"') do set "JAR_NAME=rag-agent-%%B.jar"
    )
    if not defined JAR_NAME (
        echo ERROR: cannot determine the built jar version.
        set "EXIT_CODE=1"
        goto :end
    )
    if not exist "%CD%\target\!JAR_NAME!" (
        echo ERROR: built jar not found: target\!JAR_NAME!
        set "EXIT_CODE=1"
        goto :end
    )
) else (
for /f "delims=" %%f in ('dir /b /o:d "%CD%\target\rag-agent-*.jar" 2^>nul') do (
    set "STEM=%%~nf"
    if /i not "!STEM:~-9!"=="-launcher" set "JAR_NAME=%%~nxf"
)
)
if defined JAR_NAME echo JAR_NAME=!JAR_NAME!
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
        set "EXIT_CODE=1"
        goto :end
    )
)

REM this script starts no Chroma server: with the chroma backend it must already run
if /i not "%VECTORSTORE_TYPE%"=="sqlite-vec" (
    echo WARNING: VECTORSTORE_TYPE=%VECTORSTORE_TYPE% - this script does not start Chroma.
    echo          Chroma must already be running, or set VECTORSTORE_TYPE=sqlite-vec in .env.
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
        set "EXIT_CODE=1"
        goto :end
    )
)

REM -D must precede -jar, otherwise it is passed to the app and ignored.
REM stdout/stderr.encoding are what actually control console+redirect output
REM on JDK 19+; file.encoding alone does not.
set "JAVA_ENC=-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"

java %JAVA_ENC% -jar "data\rag-agent.jar"
set "EXIT_CODE=!ERRORLEVEL!"
REM java %JAVA_ENC% -jar "data\rag-agent.jar" >> run.log 2>&1

echo =======================================================
echo Done.
echo =======================================================

:end
pause
if not defined EXIT_CODE set "EXIT_CODE=0"
endlocal & exit /b %EXIT_CODE%

:help
echo Usage: %~nx0 [OPTION]
echo   -b,  --build        Build with mvn package, then run the jar.
echo   -cb, --clean-build  Build with mvn clean package, then run the jar.
echo   -h,  --help         Show this help.
echo   No option: run the existing jar.
endlocal & exit /b 0

:bad_args
echo ERROR: unknown or conflicting options. Use --help for usage. 1>&2
endlocal & exit /b 1
