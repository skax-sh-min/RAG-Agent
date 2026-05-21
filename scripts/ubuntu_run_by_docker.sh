#!/bin/sh

# scripts 폴더에서 실행된 경우 프로젝트 루트로 이동
if [ "$(basename "$(pwd)")" = "scripts" ]; then
  cd ..
fi

# Docker 설치 여부 확인
if ! docker --version > /dev/null 2>&1; then
  echo "오류: Docker가 설치되어 있지 않습니다. 설치 후 다시 실행하세요."
  exit 1
fi

# Docker 데몬이 중지 상태면 시작
if ! docker info > /dev/null 2>&1; then
  echo "Docker 데몬을 시작합니다..."
  sudo systemctl start docker
  echo "Docker 데몬이 시작되었습니다."
fi

# chroma-server 컨테이너 상태에 따라 생성/시작/스킵
CHROMA_STATUS=$(docker inspect --format '{{.State.Status}}' chroma-server 2>/dev/null)

case "$CHROMA_STATUS" in
  running)
    echo "chroma-server가 이미 실행 중입니다."
    ;;
  "")
    echo "chroma-server 컨테이너를 새로 생성하고 시작합니다..."
    docker run -d --name chroma-server -p 8001:8000 \
      -v "$(pwd)/data/chroma:/chroma/chroma" \
      chromadb/chroma:latest
    echo "chroma-server가 시작되었습니다."
    ;;
  exited|created|paused)
    echo "chroma-server 컨테이너를 시작합니다..."
    docker start chroma-server
    echo "chroma-server가 시작되었습니다."
    ;;
  *)
    echo "chroma-server 상태를 확인할 수 없습니다 (상태: ${CHROMA_STATUS:-알 수 없음}). 건너뜁니다."
    ;;
esac

echo ".env 환경변수를 로딩합니다..."
export $(grep -v '^#' .env | xargs)

echo "Spring Boot 서버를 시작합니다..."
mvn spring-boot:run
