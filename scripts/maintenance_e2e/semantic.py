"""Scenario F: semantic proposals with the REAL local model on the scratch copy of the corpus.

Request "Oracle -> PostgreSQL" with semantic=true, then inspect what the model proposed and what the guards let through.
"""
import os as _os
ROOT = _os.path.abspath(_os.path.join(_os.path.dirname(__file__), '..', '..')).replace('\\', '/')
HERE = ROOT + '/scripts/maintenance_e2e'
import io, json, re, sys, time
sys.argv = [sys.argv[0]]
src = open(HERE + '/drive.py', encoding='utf-8').read()
exec(src.split("print('== term'")[0])
results.clear()

OLD, NEW = 'Oracle', 'PostgreSQL'
import builtins
builtins.OLD, builtins.NEW = OLD, NEW

before = snapshot(OLD)
print('chunks with', OLD, ':', len(before['chunks']), 'docs:', len({v['doc'] for v in before['chunks'].values()}))

st, hdr, _ = http('POST', '/admin/change-requests',
                  data={'instruction': OLD + ' 를 ' + NEW + ' 로 바꿔', 'from': OLD, 'to': NEW, 'semantic': 'true'})
jid = int(re.search(r'/admin/change-jobs/(\d+)', hdr['Location']).group(1))
t0 = time.time()
body = wait_job(jid, timeout=900)
print('job finished in %.0fs' % (time.time() - t0))
check('의미 변경 작업이 끝난다', body is not None)

c = db()
rep = json.loads(c.execute("select summary_json from change_job where id=?", (jid,)).fetchone()[0])
rows = c.execute("select id, doc_id, chunk_id, kind, before_text, after_text, rationale from change_proposal where job_id=? order by id", (jid,)).fetchall()
c.close()
print('report:', {k: rep[k] for k in ('mentions', 'proposals', 'unchanged', 'semantic', 'semanticTried', 'semanticProposed')})
print('discarded:', rep['discarded'])
print('semanticSkipped:', json.dumps(rep.get('semanticSkipped'), ensure_ascii=False))
kinds = [r[3] for r in rows]
print('kinds:', kinds)

check('수정안이 언급된 청크마다 있다(리터럴 이상)', len(rows) + len(rep['discarded']) == len(before['chunks']) - rep['unchanged'],
      '%d proposals + %d discarded vs %d chunks' % (len(rows), len(rep['discarded']), len(before['chunks'])))
check('LLM 이 청크를 검토했다(호출이 실제로 일어남)', rep['semanticTried'] >= 1, rep['semanticTried'])

# invariants over EVERYTHING that was proposed, literal or semantic
img = lambda t: re.findall(r'\[이미지(?:\([^)]*\))?:[ \t]*[^\]]+?[ \t]*\]', t)
page = lambda t: re.findall(r'\[페이지:\s*\d+\]', t)
fence = lambda t: len(re.findall(r'^```', t, flags=re.M))
inv_ok = all(img(r[4]) == img(r[5]) and page(r[4]) == page(r[5]) for r in rows)
check('모든 수정안이 이미지·페이지 마커를 그대로 둔다', inv_ok)
check('코드 펜스 줄 수가 짝을 유지한다(홀수로 바뀐 수정안 없음)', all(fence(r[5]) % 2 == fence(r[4]) % 2 for r in rows))
check('모든 수정안에서 바꿀 말이 사라졌다', all(OLD not in r[5] for r in rows))
check('새 말 개수가 줄어든 수정안이 없다', all(r[5].count(NEW) >= r[4].replace(OLD, NEW).count(NEW) for r in rows))
sem = [r for r in rows if r[3] == 'semantic']
for r in sem[:3]:
    print('--- semantic proposal', r[2][:8], '|', r[6][:100])
    import difflib
    lit = r[4].replace(OLD, NEW)
    for l in list(difflib.unified_diff(lit.splitlines(), r[5].splitlines(), lineterm='', n=0))[2:12]:
        print('   ', l[:140])
ratios = [len(r[5]) / max(1, len(r[4].replace(OLD, NEW))) for r in sem]
check('의미 변경 수정안은 길이가 0.6~1.4 안(가드)', all(0.6 <= x <= 1.4 for x in ratios), [round(x, 2) for x in ratios])
st, _, page_html = http('GET', '/admin/change-jobs/%d' % jid)
check('화면: 의미 변경 배지·시도/제안 수가 그려진다', st == 200 and ('의미 변경 · 검토 필요' in page_html or not sem) and '의미 변경:' in page_html)

# ── nothing written before approval
mid = snapshot(OLD)
check('승인 전 불변', all(mid['chunks'][k]['content'] == before['chunks'][k]['content'] for k in before['chunks']) and mid['vec'] == before['vec'] and mid['md'] == before['md'])

# ── approve one document that has a semantic proposal (or the first doc) and verify the apply
target_docs = [r[1] for r in sem] or [rows[0][1]]
doc = target_docs[0]
http('POST', '/admin/change-jobs/%d/docs/approve' % jid, data={'docId': doc})
t0 = time.time()
while time.time() - t0 < 300 and any(p[3] == 'approved' for p in proposals(jid)):
    time.sleep(2)
mine = [p for p in proposals(jid) if p[1] == doc]
print('doc statuses:', [(p[3], p[4]) for p in mine])
check('승인한 문서의 수정안이 모두 applied', all(p[3] == 'applied' for p in mine))
c = db()
live = {r[0]: r[1] for r in c.execute("select spring_doc_id, content from vec_document_chunks where doc_id=? and version='latest'", (doc,)).fetchall()}
c.close()
exp = {r[2]: r[5] for r in rows if r[1] == doc}
check('청크 본문이 수정안의 after 와 정확히 같다(의미 변경 포함)', all(live.get(k) == v for k, v in exp.items()))
if any(r[1] == doc and r[3] == 'semantic' for r in rows):
    check('의미 변경이 있는 문서의 MD 상태는 MD_PARTIAL', all(p[4] == 'MD_PARTIAL' for p in mine), [p[4] for p in mine])
else:
    check('의미 변경이 없는 문서는 MD 가 applied/MD_CONFLICT 중 하나', all(p[4] in ('applied', 'MD_CONFLICT') for p in mine), [p[4] for p in mine])
# other documents untouched (not approved)
other = [r for r in rows if r[1] != doc]
c = db()
untouched = all((c.execute("select content from vec_document_chunks where spring_doc_id=?", (r[2],)).fetchone() or [None])[0] == r[4] for r in other)
c.close()
check('승인하지 않은 다른 문서는 그대로', untouched, '%d chunks in other docs' % len(other))

bad = [n for n, ok in results if not ok]
print()
print('SUMMARY(F): %d passed, %d failed' % (len(results) - len(bad), len(bad)))
for n in bad:
    print('  FAILED:', n)
