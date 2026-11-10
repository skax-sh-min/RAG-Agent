import os as _os
ROOT = _os.path.abspath(_os.path.join(_os.path.dirname(__file__), '..', '..')).replace('\\', '/')
HERE = ROOT + '/scripts/maintenance_e2e'
import os, shutil, sqlite3, sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', line_buffering=True)
DST = ROOT + '/target/e2e-data'
if os.path.exists(DST):
    shutil.rmtree(DST)
os.makedirs(DST)

# 1) SQLite backup API (not a file copy — WAL)
src = sqlite3.connect('file:' + ROOT + '/data/memory.db?mode=ro', uri=True)
dst = sqlite3.connect(DST + '/memory.db')
src.backup(dst)
src.close()
dst.close()
# 2) converted MD files
shutil.copytree(ROOT + '/data/converted', DST + '/converted')
for d in ('documents', 'images'):
    os.makedirs(DST + '/' + d, exist_ok=True)

if '--no-users' in sys.argv:
    # 채팅 관리자 시나리오(chatadmin.py) — 복사본의 계정을 비워 /setup 으로 일회용 관리자를 만든다(원본 data/ 는 건드리지 않는다)
    u = sqlite3.connect(DST + '/memory.db')
    u.execute('DELETE FROM users')
    u.commit()
    u.close()
    print('users emptied in the scratch copy')
c = sqlite3.connect(DST + '/memory.db')
c.enable_load_extension(True)
c.load_extension(ROOT + '/lib/win64/vec0')
print('chunks', c.execute("select count(*) from vec_document_chunks").fetchone())
print('versions', c.execute("select version, count(*) from vec_document_chunks group by version").fetchall())
# candidate terms: identifiers (>=5 chars) mentioned in 2..6 chunks of 'latest', in a doc that has a converted MD
import re, collections
docs = {r[0]: r[1] for r in c.execute("select doc_id, display_name from doc_registry")}
conv = set(os.listdir(DST + '/converted'))
cnt = collections.defaultdict(set)
for sid, doc, content in c.execute("select spring_doc_id, doc_id, content from vec_document_chunks where version='latest'"):
    for t in set(re.findall(r'[A-Za-z][A-Za-z0-9_]{6,}', content)):
        cnt[t].add((doc, sid))
cands = []
for t, hits in cnt.items():
    ds = {d for d, _ in hits}
    if 2 <= len(hits) <= 5 and len(ds) == 1:
        d = next(iter(ds))
        if any(f.startswith(d) or d in f for f in conv):
            cands.append((t, len(hits), d))
print('candidates', len(cands))
for t, n, d in sorted(cands, key=lambda x: -x[1])[:15]:
    print(t, n, d[:40])
print('docs', list(docs.items())[:3])
