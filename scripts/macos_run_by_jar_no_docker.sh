#!/bin/sh
set -e

# windows_run_by_jar_cmd_no_docker.bat 의 macOS 판.
# Docker 없이 .env 를 로드해 이미 빌드된 target/rag-agent-*.jar 를 실행한다.
# 빌드가 먼저 필요하다: mvn clean package -DskipTests

# scripts 폴더에서 실행된 경우 프로젝트 루트로 이동
if [ "$(basename "$(pwd)")" = "scripts" ]; then
  cd ..
fi

echo "======================================================="
echo "현재 경로: $(pwd)"

# .bat 의 `chcp 65001` 대응. Java 18+ 는 file.encoding 이 UTF-8 이지만 콘솔 출력 인코딩은
# 로케일을 따르므로, 로케일이 비어 있으면(cron/CI 등 비대화형 셸) 한글 로그가 깨진다.
if [ -z "$LANG" ] && [ -z "$LC_ALL" ]; then
  LANG=ko_KR.UTF-8
  export LANG
fi

ENV_FILE=".env"

if [ ! -f "$ENV_FILE" ]; then
  # .bat 은 .env 가 없으면 아무것도 하지 않고 조용히 끝났다 — 원인을 알기 어려우므로 여기서는 명시적으로 실패시킨다.
  echo "오류: $ENV_FILE 파일이 없습니다. 'cp .env.example .env' 후 값을 채우고 다시 실행하세요."
  exit 1
fi

echo ".env 환경변수를 로딩합니다..."
# 셸이 직접 읽으므로 값에 공백이 있으면 따옴표로 감싸야 한다(예: FOO="a b").
# 인용 없는 `FOO=a b` 는 b 를 명령으로 실행하려 해 실패한다.
set -a; . "./$ENV_FILE"; set +a

# .bat 과 동일하게 .env 값을 덮어써서 8081 로 띄운다(다른 포트를 쓰려면 이 줄을 수정).
SERVER_PORT=8081
export SERVER_PORT

# target 폴더에서 실행 대상 JAR 검색.
# *.jar.original(Boot 재패키징 전 원본)은 glob 에 걸리지 않고, 여러 개면 가장 최근 것을 고른다.
JAR_PATH=$(ls -t target/rag-agent-*.jar 2>/dev/null | head -1 || true)

# .bat/.cmd 와 같은 자리(data/rag-agent.jar)를 쓴다 — 아래 복사 대상이자 실행 대상.
RUN_DIR="data"
RUN_JAR="$RUN_DIR/rag-agent.jar"
SKIP_COPY=""

if [ -z "$JAR_PATH" ]; then
  if [ -f "$RUN_JAR" ]; then
    # 빌드 산출물은 없지만 예전에 복사해 둔 것이 있다 — mvn clean 후 재빌드 없이 다시 띄우는 경우다.
    # 없는 파일을 복사하려다 죽는 것보다 있는 것으로 도는 편이 낫고, 대신 최신이 아님을 알린다.
    echo ""
    echo "알림: target/rag-agent-*.jar 파일이 없습니다."
    echo "      이미 있는 $RUN_JAR 로 진행합니다 (복사하지 않음)."
    echo "      최신 빌드로 실행하려면 중단 후 mvn clean package -DskipTests 를 먼저 실행하세요."
    echo ""
    sleep 3
    SKIP_COPY=1
  else
    echo "오류: target/rag-agent-*.jar 도 $RUN_JAR 도 없습니다. 먼저 빌드하세요:"
    echo "      mvn clean package -DskipTests"
    exit 1
  fi
fi

if [ -n "$JAR_PATH" ]; then
  echo "JAR_NAME=$(basename "$JAR_PATH")"
else
  echo "JAR_NAME=$(basename "$RUN_JAR") (기존)"
fi

# Docker 를 쓰지 않는 스크립트이므로 chroma 백엔드면 Chroma 서버가 이미 떠 있어야 한다.
case "${VECTORSTORE_TYPE:-chroma}" in
  sqlite-vec) ;;
  *)
    echo "경고: VECTORSTORE_TYPE=${VECTORSTORE_TYPE:-chroma} 입니다 — 이 스크립트는 Chroma 를 띄우지 않습니다."
    echo "      ${CHROMA_HOST:-http://localhost}:${CHROMA_PORT:-8001} 에 Chroma 가 이미 실행 중이어야 합니다."
    echo "      외부 의존 없이 돌리려면 .env 에 VECTORSTORE_TYPE=sqlite-vec 를 설정하세요."
    ;;
esac

# .bat 과 동일하게 버전 없는 고정 경로(data/rag-agent.jar)로 복사한 뒤 그것을 실행한다.
# 이유 두 가지: (1) 실행 중에도 target/ 을 지우고 다시 빌드할 수 있다 — 실행 파일과 빌드
# 산출물이 분리되므로 mvn clean 이 돌아가는 앱을 건드리지 않는다. (2) 버전이 올라가도
# 실행 명령·서비스 등록·로그 경로가 그대로다.
# 참고: data/ 는 .gitignore 대상이고 DATA_DIR 과는 무관하다 — 여기서는 단순히 JAR 을 두는 자리다.
# RUN_DIR/RUN_JAR 는 위에서(빌드 산출물 탐색 직후) 이미 정해 뒀다 — 폴백이 그 값을 봐야 하기 때문.
if [ -z "$SKIP_COPY" ]; then
  mkdir -p "$RUN_DIR"
  cp "$JAR_PATH" "$RUN_JAR"
fi

echo "======================================================="
echo "Starting... $(basename "$RUN_JAR")  port: $SERVER_PORT"
echo "======================================================="

java -jar "$RUN_JAR"
# target/ 의 JAR 을 복사 없이 그대로 실행하려면 위 줄 대신 아래를 사용하세요.
# java -jar "$JAR_PATH"
# Exploded(계층 추출) 실행을 쓰려면 아래를 사용하세요.
# java -jar "target/extracted/$(basename "$JAR_PATH")"
# run.log 파일에 로그를 남기고 싶다면 아래를 사용하세요.
# java -jar "$RUN_JAR" >> run.log 2>&1

echo "======================================================="
echo "Done."
echo "======================================================="
