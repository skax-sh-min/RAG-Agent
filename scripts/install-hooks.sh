#!/bin/sh
# 저장소의 Git 훅을 .git/hooks에 연결한다.
# 설치되는 pre-commit 훅은 .env 파일의 실수로 인한 커밋을 막는다.
# 저장소를 복제한 뒤 한 번 실행: sh scripts/install-hooks.sh
set -e

REPO_ROOT="$(git rev-parse --show-toplevel)"
HOOKS_SRC="$REPO_ROOT/scripts/hooks"
HOOKS_DST="$REPO_ROOT/.git/hooks"

for src in "$HOOKS_SRC"/*; do
    name="$(basename "$src")"
    dst="$HOOKS_DST/$name"
    ln -sf "$src" "$dst"
    echo "installed: .git/hooks/$name -> $src"
done

echo "done."
