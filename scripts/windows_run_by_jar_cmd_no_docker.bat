@echo off
setlocal enabledelayedexpansion
echo =======================================================
echo 현재 경로: %CD%

REM CMD 코드 페이지를 UTF-8로 변경
chcp 65001

REM .env 파일이 없다면 종료해야하나?
set "ENV_FILE=.env"

if exist "%ENV_FILE%" (
    echo Loading...
    for /f "usebackq tokens=1,* delims==" %%A in (`findstr /v "^#" .env`) do SET %%A=%%B

    set "SERVER_PORT=8081"

    rem 현재 실행 위치 기준 target 폴더에서 rag-agent-*.jar 파일 검색
    rem 2^>nul 이 없으면 빌드 전 실행 시 dir 의 "파일을 찾을 수 없습니다" 가 그대로 찍힌다.
    set "JAR_NAME="
    set "SKIP_COPY="
    for /f "delims=" %%f in ('dir /b "%CD%\target\rag-agent-*.jar" 2^>nul') do (
        set "JAR_NAME=%%~nxf"
        echo JAR_NAME=!JAR_NAME!
    )

    rem 빌드 산출물이 없어도 예전에 복사해 둔 data\rag-agent.jar 가 있으면 그것으로 진행한다.
    rem (mvn clean 후 재빌드 없이 다시 띄우는 경우 — 없는 파일을 복사하려다 죽는 것보다 낫다)
    if not defined JAR_NAME (
        if exist "%CD%\data\rag-agent.jar" (
            echo.
            echo 알림: %CD%\target\rag-agent-*.jar 파일이 없습니다.
            echo       이미 있는 data\rag-agent.jar 로 진행합니다 ^(복사하지 않음^).
            echo       최신 빌드로 실행하려면 중단 후 mvn clean package -DskipTests 를 먼저 실행하세요.
            echo.
            rem timeout 은 입력이 리다이렉트되면 실패하므로 ping 으로 폴백한다.
            timeout /t 3 /nobreak >nul 2>&1 || ping -n 4 127.0.0.1 >nul 2>&1
            set "SKIP_COPY=1"
            set "JAR_NAME=rag-agent.jar (기존)"
        ) else (
            echo 오류: target\rag-agent-*.jar 도 data\rag-agent.jar 도 없습니다. 먼저 빌드하세요:
            echo       mvn clean package -DskipTests
            goto :end
        )
    )

    echo =======================================================
    echo Starting... !JAR_NAME!  port: !SERVER_PORT!
    echo =======================================================

    REM 로그를 남기지 않고 바로 실행하고 싶다면 아래 주석을 제거하고 사용하세요.
    REM java -jar target/!JAR_NAME!
    REM run.log 파일에 로그를 남기고 싶다면 아래 주석을 제거하고 사용하세요.
    REM java -jar "target\!JAR_NAME!" >> run.log 2>&1

    REM rag-agent.jar 파일을 data 폴더로 복사 (overwrite)
    REM cp "%CD%\target\!JAR_NAME!" "%CD%\data\rag-agent.jar"
    REM cp 는 Windows 기본 명령이 아니다(Git Bash 등이 PATH 에 있을 때만 동작) — copy /Y 사용.
    REM copy 는 대상 폴더를 만들어 주지 않으므로 data 폴더가 없으면 먼저 생성한다.
    if not defined SKIP_COPY (
        if not exist "%CD%\data" mkdir "%CD%\data"
        copy /Y "%CD%\target\!JAR_NAME!" "%CD%\data\rag-agent.jar" >nul
        if errorlevel 1 (
            echo 오류: JAR 복사에 실패했습니다. 실행 중인 앱이 data\rag-agent.jar 를 잠그고 있는지 확인하세요.
            goto :end
        )
    )
    REM 로그를 남기지 않고 바로 실행하고 싶다면 아래 주석을 제거하고 사용하세요.
    java -jar "data\rag-agent.jar"
    REM run.log 파일에 로그를 남기고 싶다면 아래 주석을 제거하고 사용하세요.
    REM java -jar "data\rag-agent.jar" >> run.log 2>&1
)

echo =======================================================
echo Done.
echo =======================================================

REM 위 복사 실패 시 여기로 건너뛴다(= "Done." 배너를 찍지 않는다).
:end

endlocal
pause
