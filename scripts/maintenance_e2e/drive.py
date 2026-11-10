"""End-to-end check of the maintenance flow (PLAN 6.33 4b) against a scratch copy of the real corpus.

Talks to the real running app (:8098, DATA_DIR=target/e2e-data) over HTTP and inspects the scratch DB / MD files directly.
Never touches the real data/ directory.
"""
import os as _os
ROOT = _os.path.abspath(_os.path.join(_os.path.dirname(__file__), '..', '..')).replace('\\', '/')
HERE = ROOT + '/scripts/maintenance_e2e'
import hashlib, io, json, os, re, sqlite3, struct, sys, time, urllib.parse, urllib.request

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
DATA = ROOT + '/target/e2e-data'
APP = 'http://127.0.0.1:8098'
EMBED = 'http://127.0.0.1:28091/v1/embeddings'
OLD = sys.argv[1] if len(sys.argv) > 1 else 'BEDU2002'
NEW = sys.argv[2] if len(sys.argv) > 2 else 'ZNEWBATCH2002'
results = []


def check(name, ok, detail=''):
    results.append((name, ok))
    print(('PASS ' if ok else 'FAIL ') + name + ('  — ' + str(detail) if detail != '' else ''))


def db():
    c = sqlite3.connect(DATA + '/memory.db', timeout=30)
    c.enable_load_extension(True)
    c.load_extension(ROOT + '/lib/win64/vec0')
    return c


def http(method, path, data=None, json_body=None, follow=False):
    req = urllib.request.Request(APP + path, method=method)
    body = None
    if data is not None:
        body = urllib.parse.urlencode(data).encode()
        req.add_header('Content-Type', 'application/x-www-form-urlencoded')
    if json_body is not None:
        body = json.dumps(json_body).encode()
        req.add_header('Content-Type', 'application/json')

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *a, **k):
            return None
    opener = urllib.request.build_opener() if follow else urllib.request.build_opener(NoRedirect)
    try:
        r = opener.open(req, body, timeout=120)
        return r.status, dict(r.headers), r.read().decode('utf-8', 'replace')
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode('utf-8', 'replace')


def embed(text):
    req = urllib.request.Request(EMBED, data=json.dumps({'model': 'bge-m3-Q4_K_M.gguf', 'input': text}).encode(),
                                 headers={'Content-Type': 'application/json'})
    return json.load(urllib.request.urlopen(req, timeout=60))['data'][0]['embedding']


def snapshot(term):
    """Everything the maintenance flow could change, for chunks mentioning `term`."""
    c = db()
    rows = c.execute("select spring_doc_id, doc_id, content, metadata from vec_document_chunks where version='latest' and content like ?",
                     ('%' + term + '%',)).fetchall()
    ids = [r[0] for r in rows]
    snap = {'chunks': {r[0]: {'doc': r[1], 'content': r[2], 'meta': json.loads(r[3])} for r in rows}}
    snap['n_total'] = c.execute("select count(*) from vec_document_chunks").fetchone()[0]
    snap['n_vec'] = c.execute("select count(*) from vec_embeddings").fetchone()[0]
    # embedding blob hash per chunk
    snap['vec'] = {}
    for sid in ids:
        b = c.execute("select embedding from vec_embeddings where spring_doc_id=?", (sid,)).fetchone()
        snap['vec'][sid] = hashlib.sha256(b[0] if b else b'').hexdigest()[:12]
    snap['fts'] = {t: c.execute("select count(*) from chunk_fts where chunk_fts match ?", ('"' + t + '"',)).fetchone()[0] for t in (OLD, NEW)}
    docs = {r[1] for r in rows}
    snap['md'] = {}
    for f in os.listdir(DATA + '/converted'):
        if any(f.startswith(d) for d in docs):
            txt = open(DATA + '/converted/' + f, encoding='utf-8').read()
            snap['md'][f] = {'old': txt.count(OLD), 'new': txt.count(NEW), 'sha': hashlib.sha256(txt.encode()).hexdigest()[:12]}
    c.close()
    return snap


def vector_top(query, k=10):
    c = db()
    emb = embed(query)
    blob = struct.pack('<%df' % len(emb), *emb)
    rows = c.execute("select spring_doc_id, distance from vec_embeddings where embedding match ? and k = ? and version='latest' order by distance",
                     (blob, k)).fetchall()
    c.close()
    return rows


def wait_job(job_id, timeout=240):
    t0 = time.time()
    while time.time() - t0 < timeout:
        st, _, body = http('GET', '/admin/change-jobs/%d/body' % job_id)
        if 'spinner-border' not in body:
            return body
        time.sleep(2)
    return None


def proposals(job_id):
    c = db()
    rows = c.execute("select id, doc_id, chunk_id, status, md_status, before_text, after_text, after_context from change_proposal where job_id=? order by id", (job_id,)).fetchall()
    c.close()
    return rows


print('== term', OLD, '->', NEW)
before = snapshot(OLD)
print('chunks with old term:', len(before['chunks']), 'docs:', {v['doc'][:30] for v in before['chunks'].values()},
      'fts old/new:', before['fts'], 'md:', before['md'])
assert before['chunks'], 'term not in corpus'
top_before_old = vector_top(OLD)

# ── 1. request ──
st, hdr, _ = http('POST', '/admin/change-requests', data={'instruction': OLD + ' 을 ' + NEW + ' 로 바꿔', 'from': OLD, 'to': NEW})
m = re.search(r'/admin/change-jobs/(\d+)', hdr.get('Location', ''))
check('요청 → 작업 화면으로 리다이렉트', st == 302 and m is not None, hdr.get('Location'))
job_id = int(m.group(1))
body = wait_job(job_id)
check('수정안 만들기가 끝난다', body is not None)
props = proposals(job_id)
check('수정안이 청크마다 생겼다(pending)', len(props) == len(before['chunks']) and all(p[3] == 'pending' for p in props),
      '%d proposals vs %d chunks' % (len(props), len(before['chunks'])))
check('수정안의 after 에 새 말만 있다', all(NEW in p[6] and OLD not in p[6] for p in props))
check('화면에 Diff·승인 버튼이 그려진다', body is not None and '승인하고 적용' in body and ('+' in body))

# ── 2. nothing changes without approval ──
mid = snapshot(OLD)
same = (mid['chunks'].keys() == before['chunks'].keys()
        and all(mid['chunks'][k]['content'] == before['chunks'][k]['content'] for k in before['chunks'])
        and mid['vec'] == before['vec'] and mid['fts'] == before['fts'] and mid['md'] == before['md']
        and mid['n_total'] == before['n_total'] and mid['n_vec'] == before['n_vec'])
check('승인 전: 청크·벡터·FTS·MD 모두 그대로', same)
check('승인 전: db-backups 비어 있음', not os.path.isdir(DATA + '/db-backups') or not os.listdir(DATA + '/db-backups'))

# ── 3. approve + apply ──
doc_ids = sorted({p[1] for p in props})
for d in doc_ids:
    st, hdr, _ = http('POST', '/admin/change-jobs/%d/docs/approve' % job_id, data={'docId': d})
    check('승인 요청(' + d[:28] + ')', st == 302, hdr.get('Location'))
t0 = time.time()
while time.time() - t0 < 300:
    if not any(p[3] == 'approved' for p in proposals(job_id)):
        break
    time.sleep(2)
props = proposals(job_id)
print('statuses:', [(p[3], p[4]) for p in props])
check('전부 applied', all(p[3] == 'applied' for p in props))
backups = os.listdir(DATA + '/db-backups') if os.path.isdir(DATA + '/db-backups') else []
check('적용 전에 DB 백업이 만들어졌다', len(backups) >= 1, backups)

after = snapshot(NEW)
check('청크 id 가 유지됐다', set(after['chunks'].keys()) == set(before['chunks'].keys()))
check('청크 수 불변(벡터·본문 행 모두)', after['n_total'] == before['n_total'] and after['n_vec'] == before['n_vec'],
      '%s/%s' % (after['n_total'], after['n_vec']))
check('본문: 옛 말 0, 새 말 있음', all(OLD not in v['content'] and NEW in v['content'] for v in after['chunks'].values()))
vec_changed = all(after['vec'][k] != before['vec'][k] for k in before['chunks'])
check('벡터가 새 본문으로 다시 만들어졌다(임베딩 갱신)', vec_changed)
check('FTS: 새 말 검색됨, 옛 말 0', after['fts'][NEW] >= len(before['chunks']) and after['fts'][OLD] == 0, after['fts'])
meta_ok = all(OLD not in json.dumps(v['meta'], ensure_ascii=False) for v in after['chunks'].values())
check('맥락·키워드 메타데이터에 옛 말이 남지 않음', meta_ok,
      [k for k, v in after['chunks'].items() if OLD in json.dumps(v['meta'], ensure_ascii=False)][:3])
keep_keys = all({'doc_id', 'filename', 'version'} <= set(v['meta']) and v['meta'].get('doc_id') == before['chunks'][k]['meta'].get('doc_id')
                for k, v in after['chunks'].items())
check('doc_id·filename·version 등 다른 메타데이터는 그대로', keep_keys)
md_applied = [f for f, v in after['md'].items() if v['new'] > 0]
print('md after:', after['md'])
check('변환 MD: 교정본(_corrected)이 새 말로 바뀜', any(f.endswith('_corrected.md') and v['old'] == 0 and v['new'] > 0 for f, v in after['md'].items()))
raw = [v for f, v in after['md'].items() if not f.endswith('_corrected.md')]
check('변환 MD: 원본 변환본(.md)은 그대로', all(v['sha'] == before['md'][f]['sha'] for f, v in after['md'].items() if not f.endswith('_corrected.md')))
check('md_status = applied', all(p[4] == 'applied' for p in props), [p[4] for p in props])

# ── 4. search: new term finds them, old term does not ──
top_new = [r[0] for r in vector_top(NEW)]
check('벡터 검색: 새 말로 찾으면 수정된 청크가 상위에 있다', len(set(top_new[:10]) & set(before['chunks'])) >= 1,
      '%d/%d in top10' % (len(set(top_new[:10]) & set(before['chunks'])), len(before['chunks'])))
top_old = vector_top(OLD)
print('old-term vector hits that are among the edited chunks:', len({r[0] for r in top_old} & set(before['chunks'])), '(vector search is fuzzy; FTS is the exact check)')

# ── 5. admin chunk list still works; page renders final state ──
st, _, page = http('GET', '/admin/change-jobs/%d' % job_id)
check('적용 뒤 화면: 버튼 없음·MD 결과·원본 경고', st == 200 and '승인하고 적용' not in page and '변환 MD 반영됨' in page and '원본 파일' in page)

print()
bad = [n for n, ok in results if not ok]
print('SUMMARY: %d passed, %d failed' % (len(results) - len(bad), len(bad)))
for n in bad:
    print('  FAILED:', n)
json.dump({'job_id': job_id, 'chunk_ids': list(before['chunks'].keys()), 'docs': doc_ids}, open(ROOT + '/target/e2e/last.json', 'w'))
