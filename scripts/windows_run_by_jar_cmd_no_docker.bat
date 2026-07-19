@echo off
setlocal
echo =======================================================
echo 현재 경로: %CD%

REM CMD 코드 페이지를 UTF-8로 변경
chcp 65001

REM .env 파일이 없다면 종료해야하나?
set "ENV_FILE=.env"

if exist "%ENV_FILE%" (
    echo Loading...
    for /f "usebackq tokens=1,* delims==" %%A in (`findstr /v "^#" .env`) do SET %%A=%%B

    echo =======================================================
    echo Starting...
    echo =======================================================
    java -jar target/rag-agent-0.2.3-SNAPSHOT.jar
    REM java -jar target/extracted/rag-agent-0.2.3-SNAPSHOT.jar
    REM run.log 파일에 로그를 남기고 싶다면 아래 주석을 제거하고 사용하세요.
    REM java -jar target/rag-agent-0.2.3-SNAPSHOT.jar >> run.log 2>&1
)

echo =======================================================
echo Done.
echo =======================================================

endlocal
pause
