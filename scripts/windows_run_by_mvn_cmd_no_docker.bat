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
    echo =======================================================
    echo Starting... port: !SERVER_PORT!
    echo =======================================================
    mvn spring-boot:run
    REM run.log 파일에 로그를 남기고 싶다면 아래 주석을 제거하고 사용하세요.
    REM mvn spring-boot:run >> run.log 2>&1
) 

echo =======================================================
echo Done.
echo =======================================================

endlocal
pause
