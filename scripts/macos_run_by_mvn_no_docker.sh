#!/bin/sh
set -e

# windows_run_by_mvn_cmd_no_docker.bat 의 macOS 판.
# Docker 없이 .env 를 로드해 소스에서 바로 Spring Boot 를 띄운다.

# scripts 폴더에서 실행된 경우 프로젝트 루트로 이동
if [ "$(basename "$(pwd)")" = "scripts" ]; then
  cd ..
fi

echo "======================================================="
echo "현재 경로: $(pwd)"

ENV_FILE=".env"

if [ ! -f "$ENV_FILE" ]; then
  # .bat 은 .env 가 없으면 아무것도 하지 않고 조용히 끝났다 — 원인을 알기 어려우므로 여기서는 명시적으로 실패시킨다.
  echo "오류: $ENV_FILE 파일이 없습니다. 'cp .env.example .env' 후 값을 채우고 다시 실행하세요."
  exit 1
fi

echo ".env 환경변수를 로딩합니다..."
set -a; . "./$ENV_FILE"; set +a

# .bat 과 동일하게 .env 값을 덮어써서 8081 로 띄운다(다른 포트를 쓰려면 이 줄을 수정).
SERVER_PORT=8081
export SERVER_PORT

# Docker 를 쓰지 않는 스크립트이므로 chroma 백엔드면 Chroma 서버가 이미 떠 있어야 한다.
case "${VECTORSTORE_TYPE:-chroma}" in
  sqlite-vec) ;;
  *)
    echo "경고: VECTORSTORE_TYPE=${VECTORSTORE_TYPE:-chroma} 입니다 — 이 스크립트는 Chroma 를 띄우지 않습니다."
    echo "      ${CHROMA_HOST:-http://localhost}:${CHROMA_PORT:-8001} 에 Chroma 가 이미 실행 중이어야 합니다."
    echo "      외부 의존 없이 돌리려면 .env 에 VECTORSTORE_TYPE=sqlite-vec 를 설정하세요."
    ;;
esac

echo "======================================================="
echo "Starting... port: $SERVER_PORT"
echo "======================================================="

mvn spring-boot:run
# run.log 파일에 로그를 남기고 싶다면 위 줄 대신 아래를 사용하세요.
# mvn spring-boot:run >> run.log 2>&1

echo "======================================================="
echo "Done."
echo "======================================================="
