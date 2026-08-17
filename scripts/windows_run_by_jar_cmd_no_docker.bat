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
    for /f %%f in ('dir /b "%CD%\target\rag-agent-*.jar"') do (
        set "JAR_NAME=%%~nxf"
        echo JAR_NAME=!JAR_NAME!
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
    if not exist "%CD%\data" mkdir "%CD%\data"
    copy /Y "%CD%\target\!JAR_NAME!" "%CD%\data\rag-agent.jar" >nul
    if errorlevel 1 (
        echo 오류: JAR 복사에 실패했습니다. 실행 중인 앱이 data\rag-agent.jar 를 잠그고 있는지 확인하세요.
        goto :end
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
