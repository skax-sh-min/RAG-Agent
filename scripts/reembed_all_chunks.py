#!/usr/bin/env python3
"""임베딩 모델을 바꾼 뒤(sqlite-vec) 모든 청크의 벡터를 다시 만든다 — OPERATOR_MANUAL §7.3 「화면을 쓸 수 없을 때」.

앱 안에서는 `/admin` 의 「임베딩 재생성」 카드가 같은 일을 한다(Python 불필요) — 이 스크립트는 관리자 화면에 못 들어갈 때·자동화할 때의 대안이다.

청크마다 앱의 `POST /admin/chunks/{id}/reindex?collection={version}` 을 한 번씩 부른다. 서버가 하는 일은
관리자 화면의 청크 「재인덱싱」 버튼과 같다: 저장된 본문 + chunk_context 로 검색 텍스트를 다시 만들어
(새 모델로) 임베딩하고, 같은 청크 id 로 벡터를 쓰고, FTS 도 갱신한다. LLM 은 부르지 않고(키워드 재생성 없음),
손으로 고친 청크·태그·문서 레지스트리는 그대로이며, 큐레이션 청크(version=curated)는 서버가 알아서
CuratedQaService.reembedRow 로 보낸다.

표준 라이브러리만 쓴다. 로그인이 필요한 배포(전체 인증 · management-only)는 환경변수로 관리자 계정을 준다:
    ADMIN_USERNAME / ADMIN_PASSWORD     (인자로 받지 않는다 — 셸 기록에 남기지 않으려고)
평문 no-auth 배포(AUTH_ENABLED=false, AUTH_MANAGEMENT_ONLY=false)는 계정이 필요 없다.

예:
    python scripts/reembed_all_chunks.py --db data/memory.db --vec0 lib/win64/vec0.dll
    python scripts/reembed_all_chunks.py --db data/memory.db --dry-run

--vec0 (vec0 확장 경로)를 주면 **벡터가 아직 없는 청크만** 처리한다 — 중간에 멈췄다 다시 돌려도 이어서 한다.
주지 않으면 모든 청크를 다시 임베딩한다(이미 새 벡터가 있는 청크도 한 번 더).
"""
import argparse
import json
import os
import re
import sqlite3
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from http.cookiejar import CookieJar

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except AttributeError:
    pass


def chunks_to_embed(db_path, vec0_path):
    """(spring_doc_id, version) 목록. vec0 를 주면 벡터가 없는 것만."""
    con = sqlite3.connect(db_path)
    try:
        if vec0_path:
            if not hasattr(con, "enable_load_extension"):
                sys.exit("이 파이썬의 sqlite3 는 확장 로드를 지원하지 않습니다 — --vec0 없이 돌리거나 다른 파이썬을 쓰세요.")
            con.enable_load_extension(True)
            con.load_extension(vec0_path)
            # LEFT JOIN vec_embeddings 로 "벡터 없는 청크"를 구하면 안 된다 — vec0 의 기본키 점조회가 행마다 훑어서
            # 청크 2만 개에 13초(제곱으로 늘어난다). 두 테이블의 id 를 각각 한 번씩 읽어 차집합을 낸다(2만 개에 10ms).
            have = {r[0] for r in con.execute("SELECT spring_doc_id FROM vec_embeddings")}
            rows = con.execute("SELECT spring_doc_id, version FROM vec_document_chunks ORDER BY version, doc_id").fetchall()
            return [r for r in rows if r[0] not in have]
        return con.execute("SELECT spring_doc_id, version FROM vec_document_chunks ORDER BY version, doc_id").fetchall()
    finally:
        con.close()


def meta_content(html, name):
    m = re.search(r'<meta\s+name="%s"\s+content="([^"]*)"' % re.escape(name), html)
    return m.group(1) if m else ""


class Client:
    def __init__(self, base_url, timeout):
        self.base = base_url.rstrip("/")
        self.timeout = timeout
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CookieJar()))
        self.csrf_header = "X-CSRF-TOKEN"
        self.csrf_token = ""

    def get(self, path):
        with self.opener.open(self.base + path, timeout=self.timeout) as r:
            return r.geturl(), r.read().decode("utf-8", errors="replace")

    def login_if_needed(self):
        url, html = self.get("/admin")
        if "/login" not in urllib.parse.urlparse(url).path:
            self.refresh_csrf(html)
            return False                       # 로그인 없이 /admin 이 열린다(평문 no-auth)
        user = os.environ.get("ADMIN_USERNAME")
        password = os.environ.get("ADMIN_PASSWORD")
        if not user or not password:
            sys.exit("이 배포는 로그인이 필요합니다 — 환경변수 ADMIN_USERNAME / ADMIN_PASSWORD 를 설정하세요.")
        token = meta_content(html, "_csrf")
        if not token:
            m = re.search(r'name="_csrf"\s+value="([^"]*)"', html)
            token = m.group(1) if m else ""
        form = {"username": user, "password": password}
        if token:
            form["_csrf"] = token
        req = urllib.request.Request(self.base + "/login", data=urllib.parse.urlencode(form).encode(), method="POST")
        with self.opener.open(req, timeout=self.timeout) as r:
            if "error" in urllib.parse.urlparse(r.geturl()).query:
                sys.exit("로그인 실패 — 계정을 확인하세요.")
        url, html = self.get("/admin")
        if "/login" in urllib.parse.urlparse(url).path:
            sys.exit("로그인했지만 /admin 에 들어가지 못했습니다 — 관리자(ROLE_ADMIN) 계정이어야 합니다.")
        self.refresh_csrf(html)
        return True

    def refresh_csrf(self, html):
        self.csrf_token = meta_content(html, "_csrf")
        self.csrf_header = meta_content(html, "_csrf_header") or "X-CSRF-TOKEN"

    def reindex(self, chunk_id, version):
        """HTTP 상태를 돌려준다(연결 실패 0, 세션 만료 -1). 429 는 Retry-After 만큼 쉬고 같은 청크를 다시 보낸다."""
        url = "%s/admin/chunks/%s/reindex?collection=%s" % (
            self.base, urllib.parse.quote(chunk_id, safe=""), urllib.parse.quote(version, safe=""))
        headers = {"Content-Type": "application/json"}
        if self.csrf_token:
            headers[self.csrf_header] = self.csrf_token
        while True:
            req = urllib.request.Request(url, data=b'{"regenerateKeywords": false}', headers=headers, method="POST")
            try:
                with self.opener.open(req, timeout=self.timeout) as r:
                    if "/login" in urllib.parse.urlparse(r.geturl()).path:
                        return -1
                    return r.status
            except urllib.error.HTTPError as e:
                if e.code == 429:
                    wait = int(e.headers.get("Retry-After") or 5)
                    print("  (429 — %d초 쉬고 다시)" % wait, flush=True)
                    time.sleep(max(wait, 1))
                    continue
                return e.code
            except (urllib.error.URLError, OSError):
                return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", default="data/memory.db", help="SQLite 파일 (옛 SQLITE_VEC_DB_PATH 를 썼다면 그 경로)")
    ap.add_argument("--vec0", default=None, help="vec0 확장 경로 — 주면 벡터가 없는 청크만 처리(재개 가능)")
    ap.add_argument("--base-url", default="http://localhost:8080")
    ap.add_argument("--timeout", type=int, default=180, help="요청 하나의 읽기 타임아웃(초)")
    ap.add_argument("--dry-run", action="store_true", help="대상 수만 세고 끝낸다")
    args = ap.parse_args()

    if not os.path.exists(args.db):
        sys.exit("DB 파일이 없습니다: " + args.db)
    todo = chunks_to_embed(args.db, args.vec0)
    by_version = {}
    for _, version in todo:
        by_version[version] = by_version.get(version, 0) + 1
    print("대상 청크 %d개 — 버전별: %s" % (len(todo), json.dumps(by_version, ensure_ascii=False)))
    if args.dry_run or not todo:
        return

    client = Client(args.base_url, args.timeout)
    logged_in = client.login_if_needed()
    print("접속 확인 완료 (%s)" % ("관리자 로그인" if logged_in else "로그인 불필요"))

    ok, failed = 0, []
    started = time.time()
    for i, (chunk_id, version) in enumerate(todo, 1):
        status = client.reindex(chunk_id, version)
        if status == 200:
            ok += 1
        elif status == -1:
            sys.exit("세션이 만료됐습니다. 다시 실행하면 --vec0 로 남은 것만 이어서 합니다. (진행 %d/%d)" % (i - 1, len(todo)))
        else:
            failed.append((chunk_id, version, status))
            print("  실패 status=%s version=%s id=%s" % (status, version, chunk_id), flush=True)
        if i % 50 == 0 or i == len(todo):
            print("%d/%d 처리 (성공 %d, 실패 %d, %.0f초)" % (i, len(todo), ok, len(failed), time.time() - started), flush=True)

    print("끝: 성공 %d, 실패 %d" % (ok, len(failed)))
    if failed:
        print("실패 status 해석: 404 = 청크 없음·비활성 큐레이션 행 / 0 = 연결 실패 / 그 밖 = 서버 로그 확인 (임베딩 서버·모델·차원)")
        print("원인을 고친 뒤 --vec0 로 다시 돌리면 실패한 것만 다시 합니다.")
        sys.exit(1)


if __name__ == "__main__":
    main()
