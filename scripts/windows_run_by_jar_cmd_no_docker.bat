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
    java -jar "target\!JAR_NAME!"
    REM java -jar target/extracted/!JAR_NAME!
    REM run.log 파일에 로그를 남기고 싶다면 아래 주석을 제거하고 사용하세요.
    REM java -jar "target\!JAR_NAME!" >> run.log 2>&1
)

echo =======================================================
echo Done.
echo =======================================================

endlocal
pause
