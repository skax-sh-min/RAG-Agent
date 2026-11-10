"""Scenario E: re-indexing the document from its (written-back) Markdown keeps the maintenance edit."""
import os as _os
ROOT = _os.path.abspath(_os.path.join(_os.path.dirname(__file__), '..', '..')).replace('\\', '/')
HERE = ROOT + '/scripts/maintenance_e2e'
import io, json, sys, time
sys.argv = [sys.argv[0]]
src = open(HERE + '/drive.py', encoding='utf-8').read()
exec(src.split("print('== term'")[0])
results.clear()
OLD, NEW = 'BEDU2002', 'ZNEWBATCH2002'
doc = json.load(open(ROOT + '/target/e2e/last.json', encoding='utf-8'))['docs'][0]


def doc_chunks():
    c = db()
    rows = c.execute("select spring_doc_id, content from vec_document_chunks where doc_id=? and version='latest'", (doc,)).fetchall()
    c.close()
    return rows


before = doc_chunks()
ids_before = {r[0] for r in before}
print('chunks of the document:', len(before), 'with NEW:', sum(NEW in r[1] for r in before), 'with OLD:', sum(OLD in r[1] for r in before))

st, _, body = http('POST', '/admin/documents/%s/reindex' % urllib.parse.quote(doc, safe=''))
print('preflight status', st, body[:200])
check('사전 경고(편집된 청크 N개) — 설계서가 밝힌 알려진 한계', st == 409 and '"editedChunks"' in body)

st, _, body = http('POST', '/admin/documents/%s/reindex?force=true' % urllib.parse.quote(doc, safe=''))
check('재인덱싱 시작(202)', st in (200, 202), st)
t0 = time.time()
while time.time() - t0 < 280:
    rows = doc_chunks()
    if rows and not ({r[0] for r in rows} & ids_before):
        time.sleep(3)
        rows = doc_chunks()
        break
    time.sleep(3)
rows = doc_chunks()
print('after reindex: chunks', len(rows), 'with NEW:', sum(NEW in r[1] for r in rows), 'with OLD:', sum(OLD in r[1] for r in rows))
check('재인덱싱으로 청크 id 가 새로 만들어졌다', rows and not ({r[0] for r in rows} & ids_before))
check('재인덱싱 뒤에도 새 말이 남는다(MD 되쓰기 덕분)', sum(NEW in r[1] for r in rows) >= 1)
check('재인덱싱 뒤 옛 말은 되살아나지 않는다', sum(OLD in r[1] for r in rows) == 0)
c = db()
fts_old = c.execute("select count(*) from chunk_fts where chunk_fts match ?", ('"' + OLD + '"',)).fetchone()[0]
fts_new = c.execute("select count(*) from chunk_fts where chunk_fts match ?", ('"' + NEW + '"',)).fetchone()[0]
c.close()
check('FTS 도 같다(옛 말 0, 새 말 있음)', fts_old == 0 and fts_new >= 1, (fts_old, fts_new))
# 내보내기에 중복이 없다
st, _, md = http('GET', '/ui/documents/%s/export?format=md' % urllib.parse.quote(doc, safe=''))
print('export status', st, 'len', len(md))
if st == 200:
    check('내보내기에 새 말이 있고 옛 말은 없다', NEW in md and OLD not in md)
bad = [n for n, ok in results if not ok]
print('SUMMARY(E): %d passed, %d failed' % (len(results) - len(bad), len(bad)))
for n in bad:
    print('  FAILED:', n)
