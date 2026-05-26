#!/bin/sh
set -e

# scripts 폴더에서 실행된 경우 프로젝트 루트로 이동
if [ "$(basename "$(pwd)")" = "scripts" ]; then
  cd ..
fi

# chroma-server 실행 여부 확인
CHROMA_STATUS=$(docker inspect --format '{{.State.Status}}' chroma-server 2>/dev/null) || true
case "$CHROMA_STATUS" in
  running) CHROMA_RUNNING=true ;;
  *)       CHROMA_RUNNING=false ;;
esac

# Spring Boot 실행 여부 확인 (포트 8080)
if lsof -ti:8080 > /dev/null 2>&1; then
  SPRING_RUNNING=true
else
  SPRING_RUNNING=false
fi

# 실행 상태 출력
echo "=== 서비스 상태 ==="
if $CHROMA_RUNNING; then
  echo "  chroma-server : 실행 중"
else
  echo "  chroma-server : 중지 상태"
fi
if $SPRING_RUNNING; then
  echo "  Spring Boot   : 실행 중 (포트 8080)"
else
  echo "  Spring Boot   : 중지 상태"
fi
echo ""

# 두 서비스 모두 중지 상태일 때만 초기화 허용
if $CHROMA_RUNNING || $SPRING_RUNNING; then
  echo "오류: 데이터를 초기화하려면 두 서비스가 모두 중지 상태여야 합니다."
  echo "       ubuntu_stop_by_docker.sh 를 먼저 실행하세요."
  exit 1
fi

# 초기화 대상 안내 및 확인
echo "=== 초기화 대상 ==="
echo "  data/chroma/       — ChromaDB 벡터 데이터"
echo "  data/memory.db     — SQLite (채팅 히스토리, 문서 레지스트리, 사용자)"
echo "  data/documents/    — 업로드 원본 문서"
echo "  data/images/       — 추출 이미지"
echo "  data/converted/    — 변환된 Markdown 파일"
echo ""
echo "  data/audit/        — 감사 로그"
echo ""
printf "위 데이터를 모두 삭제하시겠습니까? [y/N] "
read CONFIRM
if [ "$CONFIRM" != "y" ] && [ "$CONFIRM" != "Y" ]; then
  echo "취소되었습니다."
  exit 0
fi

# 데이터 초기화
echo ""
echo "데이터를 초기화합니다..."

if [ -d "data/chroma" ]; then
  rm -rf data/chroma
  echo "  ✓ data/chroma 삭제 완료"
else
  echo "  - data/chroma 없음, 건너뜁니다."
fi

for f in data/memory.db data/memory.db-wal data/memory.db-shm; do
  if [ -f "$f" ]; then
    rm -f "$f"
    echo "  ✓ $f 삭제 완료"
  fi
done

for dir in data/documents data/images data/converted; do
  if [ -d "$dir" ]; then
    rm -rf "$dir"
    echo "  ✓ $dir 삭제 완료"
  else
    echo "  - $dir 없음, 건너뜁니다."
  fi
done

if [ -d "data/audit" ]; then
  rm -f data/audit/audit.log data/audit/audit.*.log.gz
  echo "  ✓ data/audit 로그 파일 삭제 완료"
else
  echo "  - data/audit 없음, 건너뜁니다."
fi

echo ""
echo "초기화가 완료되었습니다."
