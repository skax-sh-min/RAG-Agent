#!/bin/sh

# scripts 폴더에서 실행된 경우 프로젝트 루트로 이동
if [ "$(basename "$(pwd)")" = "scripts" ]; then
  cd ..
fi

# chroma-server 컨테이너가 실행 중이면 중지
CHROMA_STATUS=$(docker inspect --format '{{.State.Status}}' chroma-server 2>/dev/null)

case "$CHROMA_STATUS" in
  running)
    echo "chroma-server를 중지합니다..."
    docker stop chroma-server
    echo "chroma-server가 중지되었습니다."
    ;;
  "")
    echo "chroma-server 컨테이너가 존재하지 않습니다. 건너뜁니다."
    ;;
  exited|created|paused)
    echo "chroma-server가 이미 중지 상태입니다. 건너뜁니다."
    ;;
  *)
    echo "chroma-server 상태를 확인할 수 없습니다 (상태: ${CHROMA_STATUS:-알 수 없음}). 건너뜁니다."
    ;;
esac

# Docker 데몬이 실행 중이면 중지
if docker info > /dev/null 2>&1; then
  echo "Docker 데몬을 중지합니다..."
  sudo systemctl stop docker
  echo "Docker 데몬이 중지되었습니다."
else
  echo "Docker 데몬이 이미 중지 상태입니다."
fi
