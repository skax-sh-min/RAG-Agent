#!/usr/bin/env python3
"""임베딩 모델 교체 때 sqlite-vec 의 벡터 테이블(vec_embeddings)만 지운다 — OPERATOR_MANUAL §7.3 「화면을 쓸 수 없을 때」.

앱 안에서는 `/admin` 의 「임베딩 재생성」 카드가 지우고 다시 만드는 것까지 한다(Python·확장 경로 불필요) — 이 스크립트는 화면에 못 들어갈 때의 대안이다.

반드시 앱을 멈춘 뒤 돌린다. 지우는 것은 `vec_embeddings`(vec0 가상 테이블, 그림자 테이블 포함) 하나다 —
청크 본문(`vec_document_chunks`)·키워드 색인(`chunk_fts`)·문서 레지스트리(`doc_registry`)는 건드리지 않는다.
vec0 가상 테이블은 확장이 로드돼 있어야 지울 수 있다(없으면 SQLite 가 `no such module: vec0`).

--yes 가 없으면 지금 상태만 보여 주고 아무것도 지우지 않는다.

예:
    python scripts/drop_vec_embeddings.py --db data/memory.db --vec0 lib/win64/vec0.dll
    python scripts/drop_vec_embeddings.py --db data/memory.db --vec0 lib/win64/vec0.dll --yes
"""
import argparse
import os
import re
import sqlite3
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except AttributeError:
    pass


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", default="data/memory.db", help="SQLite 파일 (옛 SQLITE_VEC_DB_PATH 를 썼다면 그 경로)")
    ap.add_argument("--vec0", required=True, help="vec0 확장 경로 (SQLITE_VEC_EXTENSION_PATH 와 같은 파일, 확장자 생략 가능)")
    ap.add_argument("--yes", action="store_true", help="실제로 지운다(없으면 상태만 출력)")
    args = ap.parse_args()

    if not os.path.exists(args.db):
        sys.exit("DB 파일이 없습니다: " + args.db)
    con = sqlite3.connect(args.db)
    if not hasattr(con, "enable_load_extension"):
        sys.exit("이 파이썬의 sqlite3 는 확장 로드를 지원하지 않습니다 — sqlite3 CLI 의 `.load` 를 쓰세요(매뉴얼 §7.3).")
    con.enable_load_extension(True)
    con.load_extension(args.vec0)

    row = con.execute("SELECT sql FROM sqlite_master WHERE type='table' AND name='vec_embeddings'").fetchone()
    if row is None:
        print("vec_embeddings 가 없습니다 — 지울 것이 없습니다(이미 지웠거나 첫 기동 전).")
        return
    m = re.search(r"embedding\s+float\s*\[\s*(\d+)\s*\]", row[0], re.IGNORECASE)
    vectors = con.execute("SELECT COUNT(*) FROM vec_embeddings").fetchone()[0]
    chunks = con.execute("SELECT COUNT(*) FROM vec_document_chunks").fetchone()[0]
    print("현재 vec_embeddings: %s차원, 벡터 %d개 / 청크 본문(vec_document_chunks) %d개"
          % (m.group(1) if m else "?", vectors, chunks))

    if not args.yes:
        print("지우지 않았습니다. 앱이 멈춰 있는지 확인한 뒤 --yes 를 붙여 다시 실행하세요.")
        return
    con.execute("DROP TABLE vec_embeddings")
    con.commit()
    left = [r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE name LIKE 'vec%' AND type='table' ORDER BY name")]
    print("vec_embeddings 를 지웠습니다. 남은 vec 테이블: %s" % left)
    print("청크 본문 %d개는 그대로입니다. 이제 EMBED_MODEL/EMBED_DIMENSIONS 를 확인하고 앱을 띄우세요." %
          con.execute("SELECT COUNT(*) FROM vec_document_chunks").fetchone()[0])
    con.close()


if __name__ == "__main__":
    main()
