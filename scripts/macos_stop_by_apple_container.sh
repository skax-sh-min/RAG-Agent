#!/bin/sh

# scripts 폴더에서 실행된 경우 프로젝트 루트로 이동
if [ "$(basename "$(pwd)")" = "scripts" ]; then
  cd ..
fi

# chroma-server 컨테이너가 실행 중이면 중지
CHROMA_STATUS=$(container inspect chroma-server 2>/dev/null)

case "$CHROMA_STATUS" in
  *'"status":"running"'*)
    echo "chroma-server를 중지합니다..."
    container stop chroma-server
    echo "chroma-server가 중지되었습니다."
    ;;
  "")
    echo "chroma-server 컨테이너가 존재하지 않습니다. 건너뜁니다."
    ;;
  *'"status":"stopped"'*)
    echo "chroma-server가 이미 중지 상태입니다. 건너뜁니다."
    ;;
  *)
    echo "chroma-server 상태를 확인할 수 없습니다 (상태: ${CHROMA_STATUS:-알 수 없음}). 건너뜁니다."
    ;;
esac

# Apple Container 서비스가 실행 중이면 중지
if container system status > /dev/null 2>&1; then
  echo "Apple Container 서비스를 중지합니다..."
  container system stop
  echo "Apple Container 서비스가 중지되었습니다."
else
  echo "Apple Container 서비스가 이미 중지 상태입니다."
fi
