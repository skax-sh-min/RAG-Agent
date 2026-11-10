"""Scenario G: case variants (ORACLE / oracle) on the scratch copy of the real corpus.

Job 1 (option OFF): the report must say that variants stay; reject it (corpus untouched).
Job 2 (option ON):  variants are replaced shaped to the matched case in chunks AND in the converted Markdown.
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
ci = lambda t: len(re.findall(re.escape(OLD), t, flags=re.I))


def chunks_ci():
    c = db()
    rows = c.execute("select spring_doc_id, doc_id, content, metadata from vec_document_chunks where version='latest' and content like ? collate nocase",
                     ('%' + OLD + '%',)).fetchall()
    c.close()
    return rows


def report(jid):
    c = db()
    r = json.loads(c.execute("select summary_json from change_job where id=?", (jid,)).fetchone()[0])
    c.close()
    return r


def request(case_variants):
    data = {'instruction': OLD + ' → ' + NEW, 'from': OLD, 'to': NEW}
    if case_variants:
        data['caseVariants'] = 'true'
    st, hdr, _ = http('POST', '/admin/change-requests', data=data)
    jid = int(re.search(r'/admin/change-jobs/(\d+)', hdr['Location']).group(1))
    assert wait_job(jid) is not None
    return jid


base = chunks_ci()
exact_chunks = [r for r in base if OLD in r[2]]
variant_chunks = [r for r in base if len(re.findall(re.escape(OLD), r[2], flags=re.I)) > len(re.findall(re.escape(OLD), r[2]))]
variant_only = [r for r in base if OLD not in r[2]]
print('case-insensitive chunks: %d | with exact: %d | with a case variant: %d | variant only: %d' % (len(base), len(exact_chunks), len(variant_chunks), len(variant_only)))
check('코퍼스에 대소문자 변형이 실제로 있다(이 시나리오의 전제)', len(variant_chunks) > 0)
md_before = {f: open(DATA + '/converted/' + f, encoding='utf-8').read() for f in os.listdir(DATA + '/converted')}
md_ci_before = sum(ci(t) for t in md_before.values())
md_exact_before = sum(t.count(OLD) for t in md_before.values())
print('converted MD: case-insensitive %d, exact %d' % (md_ci_before, md_exact_before))

# ── Job 1: option OFF ──
j1 = request(False)
r1 = report(j1)
print('OFF report:', {k: r1[k] for k in ('mentions', 'proposals', 'unchanged', 'caseVariants', 'caseVariantChunks')})
check('끔: 리포트가 변형이 남은 청크 수를 밝힌다', r1['caseVariants'] is False and r1['caseVariantChunks'] == len(variant_chunks), r1['caseVariantChunks'])
check('끔: 변형뿐인 청크는 건너뛴다(unchanged)', r1['unchanged'] == len(variant_only), r1['unchanged'])
c = db()
rows1 = c.execute("select chunk_id, before_text, after_text from change_proposal where job_id=?", (j1,)).fetchall()
c.close()
check('끔: 수정안은 글자 그대로만 바꾼다 — 대문자 변형은 after 에 남는다',
      all(OLD not in r[2] for r in rows1) and any(ci(r[2]) > 0 for r in rows1), 'variants left in %d proposals' % sum(ci(r[2]) > 0 for r in rows1))
st, _, page = http('GET', '/admin/change-jobs/%d' % j1)
check('끔: 화면 경고("바뀌지 않고 남습니다")', st == 200 and '바뀌지 않고 남습니다' in page)
for d in sorted({p[1] for p in proposals(j1)}):
    http('POST', '/admin/change-jobs/%d/docs/reject' % j1, data={'docId': d})
after_reject = chunks_ci()
check('끔 작업 거절 뒤 코퍼스 불변', {r[0]: r[2] for r in after_reject} == {r[0]: r[2] for r in base})

# ── Job 2: option ON ──
j2 = request(True)
r2 = report(j2)
print('ON report:', {k: r2[k] for k in ('mentions', 'proposals', 'unchanged', 'caseVariants', 'caseVariantChunks', 'discarded')})
check('켬: 변형뿐인 청크도 수정안이 생겼다(unchanged 0)', r2['caseVariants'] is True and r2['unchanged'] == 0, r2['unchanged'])
c = db()
rows2 = c.execute("select chunk_id, doc_id, before_text, after_text, after_keywords from change_proposal where job_id=?", (j2,)).fetchall()
c.close()
check('켬: 모든 수정안의 after 에 옛 말이 (대소문자 무관) 없다', all(ci(r[3]) == 0 for r in rows2))
shaped = True
for r in rows2:
    for m in re.finditer(re.escape(OLD), r[2], flags=re.I):
        tok = m.group()
        want = NEW if tok == OLD else NEW.upper() if tok == OLD.upper() else NEW.lower() if tok == OLD.lower() else NEW
        if want not in r[3]:
            shaped = False
check('켬: 변형이 그 대소문자 모양에 맞춰 바뀌었다(ORACLE→POSTGRESQL · oracle→postgresql)', shaped)
check('켬: 대문자 변형이 실제로 POSTGRESQL 로 바뀐 수정안이 있다', any('POSTGRESQL' in r[3] and 'ORACLE' in r[2] for r in rows2))
for d in sorted({r[1] for r in rows2}):
    http('POST', '/admin/change-jobs/%d/docs/approve' % j2, data={'docId': d})
t0 = time.time()
while time.time() - t0 < 600 and any(p[3] == 'approved' for p in proposals(j2)):
    time.sleep(3)
props2 = proposals(j2)
print('statuses:', sorted({(p[3], p[4]) for p in props2}))
check('켬: 전부 applied', all(p[3] == 'applied' for p in props2))
applied_ids = {p[2] for p in props2 if p[3] == 'applied'}
now = chunks_ci()
left_in_applied = [r for r in now if r[0] in applied_ids and ci(r[2]) > 0]
check('켬: 적용된 청크에 옛 말(대소문자 무관)이 남지 않는다', not left_in_applied, len(left_in_applied))
others = [r for r in now if r[0] not in applied_ids]
print('chunks still mentioning the old term (not applied):', len(others), '| discarded by invariants:', len(r2['discarded']))
check('켬: 남은 청크는 불변식으로 버려진 것뿐이다', len(others) == len(r2['discarded']), '%d vs %d' % (len(others), len(r2['discarded'])))
# FTS (trigram, case-insensitive) agrees
c = db()
fts_old = c.execute("select count(*) from chunk_fts where chunk_fts match ?", ('"' + OLD + '"',)).fetchone()[0]
c.close()
check('켬: FTS(대소문자 무시)도 같다 — 옛 말이 걸리는 청크는 남은 청크 수와 같다', fts_old == len(others), '%d vs %d' % (fts_old, len(others)))

# converted MD — the same rule
md_after = {f: open(DATA + '/converted/' + f, encoding='utf-8').read() for f in os.listdir(DATA + '/converted')}
md_status = {p[1]: p[4] for p in props2}
print('md statuses per doc:', md_status)
for doc, st_ in md_status.items():
    corrected = [f for f in md_after if f.startswith(doc) and f.endswith('_corrected.md')]
    raw = [f for f in md_after if f.startswith(doc) and not f.endswith('_corrected.md')]
    if st_ == 'applied' and corrected:
        check('켬: MD(%s) 교정본에 옛 말이 (대소문자 무관) 없다' % doc[:22], all(ci(md_after[f]) == 0 for f in corrected))
    if raw:
        check('켬: MD(%s) 원본 변환본은 그대로' % doc[:22], all(md_after[f] == md_before[f] for f in raw))
conflicts = [d for d, s in md_status.items() if s == 'MD_CONFLICT']
print('MD_CONFLICT docs:', len(conflicts))

bad = [n for n, ok in results if not ok]
print()
print('SUMMARY(G): %d passed, %d failed' % (len(results) - len(bad), len(bad)))
for n in bad:
    print('  FAILED:', n)
