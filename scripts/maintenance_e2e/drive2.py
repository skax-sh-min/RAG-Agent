"""Scenarios B (stale injection) and C (reject) of the maintenance end-to-end check — run after drive.py on the same scratch app."""
import os as _os
ROOT = _os.path.abspath(_os.path.join(_os.path.dirname(__file__), '..', '..')).replace('\\', '/')
HERE = ROOT + '/scripts/maintenance_e2e'
import io, json, re, sys, time, os, hashlib
sys.argv = [sys.argv[0]]
src = open(HERE + '/drive.py', encoding='utf-8').read()
# reuse helpers only (everything above the first scenario banner)
exec(src.split("print('== term'")[0])

results.clear()


def run_request(old, new):
    st, hdr, _ = http('POST', '/admin/change-requests', data={'instruction': old + ' → ' + new, 'from': old, 'to': new})
    jid = int(re.search(r'/admin/change-jobs/(\d+)', hdr['Location']).group(1))
    assert wait_job(jid) is not None
    return jid


def chunk_text(cid):
    c = db()
    r = c.execute("select content from vec_document_chunks where spring_doc_id=?", (cid,)).fetchone()
    c.close()
    return r[0] if r else None


# ── B: a chunk is edited by someone else between proposal and approval ──
OLD, NEW = 'BEDU2001', 'ZNEWBATCH2001'
import builtins
builtins.OLD = OLD
before = snapshot(OLD)
jid = run_request(OLD, NEW)
props = proposals(jid)
print('B: proposals', len(props), 'md before', before['md'])
victim = props[0][2]
edited = chunk_text(victim) + '\n(다른 관리자가 직접 고침)'
st, _, _ = http('POST', '/admin/chunks/%s?collection=latest' % victim, json_body={'text': edited})
check('B: 다른 사람이 청크를 직접 고쳤다', st == 200 and chunk_text(victim) == edited, st)
for d in sorted({p[1] for p in props}):
    http('POST', '/admin/change-jobs/%d/docs/approve' % jid, data={'docId': d})
t0 = time.time()
while time.time() - t0 < 300 and any(p[3] == 'approved' for p in proposals(jid)):
    time.sleep(2)
props = proposals(jid)
by = {p[2]: p for p in props}
print('B statuses:', [(p[2][:6], p[3], p[4]) for p in props])
check('B: 직접 고친 청크의 수정안은 stale', by[victim][3] == 'stale')
check('B: 직접 고친 청크는 그대로(덮어쓰지 않았다)', chunk_text(victim) == edited)
others = [p for p in props if p[2] != victim]
check('B: 나머지 청크는 적용됨', all(p[3] == 'applied' for p in others) and len(others) == len(props) - 1)
check('B: 나머지 청크에서 옛 말이 사라졌다', all(OLD not in chunk_text(p[2]) and NEW in chunk_text(p[2]) for p in others))
after = snapshot(OLD)
corrected_after = {f: v for f, v in after['md'].items() if f.endswith('_corrected.md')}
corrected_before = {f: v for f, v in before['md'].items() if f.endswith('_corrected.md')}
check('B: 일부만 적용돼 청크에 옛 말이 남았으니 MD 는 그대로(MD_CONFLICT)',
      all(corrected_after[f]['sha'] == corrected_before[f]['sha'] for f in corrected_before)
      and all(p[4] == 'MD_CONFLICT' for p in others), [p[4] for p in others])
st, _, page = http('GET', '/admin/change-jobs/%d' % jid)
check('B: 화면이 stale·MD_CONFLICT 경고를 보인다', st == 200 and '낡음' in page and '재인덱싱하면 이 수정이 되돌아갑니다' in page)

# ── C: reject leaves the corpus untouched ──
OLD2, NEW2 = 'TOT_CNT', 'ZNEWTOTAL_CNT'
builtins.OLD, builtins.NEW = OLD2, NEW2
OLD, NEW = OLD2, NEW2
snap0 = snapshot(OLD2)
backups0 = sorted(os.listdir(DATA + '/db-backups'))
jid2 = run_request(OLD2, NEW2)
p2 = proposals(jid2)
for d in sorted({p[1] for p in p2}):
    st, hdr, _ = http('POST', '/admin/change-jobs/%d/docs/reject' % jid2, data={'docId': d})
check('C: 거절하면 전부 rejected', all(p[3] == 'rejected' for p in proposals(jid2)), [p[3] for p in proposals(jid2)])
snap1 = snapshot(OLD2)
check('C: 거절 뒤 청크·벡터·FTS·MD 불변',
      all(snap1['chunks'][k]['content'] == snap0['chunks'][k]['content'] for k in snap0['chunks'])
      and snap1['vec'] == snap0['vec'] and snap1['fts'] == snap0['fts'] and snap1['md'] == snap0['md'])
check('C: 거절은 백업도 만들지 않는다', sorted(os.listdir(DATA + '/db-backups')) == backups0)
# 거절된 것을 다시 승인해도 아무 일도 없다(CAS)
for d in sorted({p[1] for p in p2}):
    http('POST', '/admin/change-jobs/%d/docs/approve' % jid2, data={'docId': d})
time.sleep(3)
check('C: 거절된 수정안은 다시 승인되지 않는다', all(p[3] == 'rejected' for p in proposals(jid2)))

# ── D: 같은 요청 동시 1건 · 3글자 미만 ──
st, hdr, _ = http('POST', '/admin/change-requests', data={'instruction': 'x', 'from': 'DB', 'to': 'Oracle'})
jid3 = int(re.search(r'/admin/change-jobs/(\d+)', hdr['Location']).group(1))
wait_job(jid3)
c = db()
rep = json.loads(c.execute("select summary_json from change_job where id=?", (jid3,)).fetchone()[0])
c.close()
check('D: 3글자 미만은 tooShort 로 밝히고 수정안 0', rep['tooShort'] is True and rep['proposals'] == 0 and len(proposals(jid3)) == 0)

bad = [n for n, ok in results if not ok]
print()
print('SUMMARY(B/C/D): %d passed, %d failed' % (len(results) - len(bad), len(bad)))
for n in bad:
    print('  FAILED:', n)
