"""Scenario R: the 6e review screen (/admin/change-jobs/{id}/review) applied with the real embedding server.

Usage (same app as drive.py, but run against a fresh prep.py copy):
  review.py literal            R1: 4b literal job — batch refused, confirm one-by-one (one with a human edit), verify chunk/vec/FTS/MD/backup
  review.py nl <subject> <alt> R2: natural-language deprecate job — batch apply of insert-only proposals
  review.py fail <jobId>       R3: confirm one proposal while the embedding server is unreachable (start the app with a dead EMBED_BASE_URL)
  review.py retry <jobId>      R3b: after restarting with the real embedding server — [다시 적용] on the failed item applies it
Writes target/e2e/last.json (docs of the literal job) so reindex.py can run right after `literal`.
"""
import os as _os
ROOT = _os.path.abspath(_os.path.join(_os.path.dirname(__file__), '..', '..')).replace('\\', '/')
HERE = ROOT + '/scripts/maintenance_e2e'
import html, io, json, os, re, sys, time
argv = sys.argv[1:]
sys.argv = [sys.argv[0]]
src = open(HERE + '/drive.py', encoding='utf-8').read()
exec(src.split("print('== term'")[0])
results.clear()
APP = os.environ.get('E2E_APP', 'http://127.0.0.1:8102')
OLD, NEW = 'BEDU2002', 'ZNEWBATCH2002'
HUMAN_LINE = '검토자 메모: 배치 ID 변경을 확인했다.'


def job_row(job_id):
    c = db()
    r = c.execute("select status, backup_name, summary_json, intent_json from change_job where id=?", (job_id,)).fetchone()
    c.close()
    return r


def props(job_id):
    c = db()
    rows = c.execute("select id, doc_id, chunk_id, kind, status, md_status, before_text, after_text, before_context, after_context,"
                     " before_keywords, after_keywords, edited_by from change_proposal where job_id=? order by id", (job_id,)).fetchall()
    c.close()
    keys = ['id', 'doc', 'chunk', 'kind', 'status', 'md', 'before', 'after', 'bctx', 'actx', 'bkw', 'akw', 'edited_by']
    return [dict(zip(keys, r)) for r in rows]


def chunk(cid):
    c = db()
    r = c.execute("select content, metadata from vec_document_chunks where spring_doc_id=?", (cid,)).fetchone()
    v = c.execute("select embedding from vec_embeddings where spring_doc_id=?", (cid,)).fetchone()
    c.close()
    if not r:
        return None
    return {'content': r[0], 'meta': json.loads(r[1]), 'vec': hashlib.sha256(v[0] if v else b'').hexdigest()[:12]}


def fts_has(cid, term):
    c = db()
    n = c.execute("select count(*) from chunk_fts f join chunk_fts_key k on k.fts_rowid=f.rowid where k.spring_doc_id=? and chunk_fts match ?",
                  (cid, '"' + term + '"')).fetchone()[0]
    c.close()
    return n > 0


def md_text(doc):
    for name in (doc + '_corrected.md', doc + '.md'):
        p = DATA + '/converted/' + name
        if os.path.exists(p):
            return name, open(p, encoding='utf-8').read()
    return None, None


def backups():
    d = DATA + '/db-backups'
    return sorted(f for f in os.listdir(d) if f.endswith('.db')) if os.path.isdir(d) else []


def after_hash(job_id, pid):
    st, _, body = http('GET', '/admin/change-jobs/%d/review/%d' % (job_id, pid))
    m = re.search(r'name="expectedHash"\s+value="([^"]*)"', body)
    return st, (m.group(1) if m else None), body


def confirm(job_id, pid, text):
    st, h, _ = after_hash(job_id, pid)
    return http('POST', '/admin/change-jobs/%d/review/%d/confirm' % (job_id, pid), data={'text': text, 'expectedHash': h or ''})


def drain(job_id, timeout=600):
    t0 = time.time()
    while time.time() - t0 < timeout:
        ps = props(job_id)
        if not any(p['status'] == 'approved' for p in ps):
            time.sleep(2)   # the last item's MD write-back runs right after its status flips
            return props(job_id)
        time.sleep(2)
    return props(job_id)


def summary(tag):
    bad = [n for n, ok in results if not ok]
    print('SUMMARY(%s): %d passed, %d failed' % (tag, len(results) - len(bad), len(bad)))
    for n in bad:
        print('  FAILED:', n)


def literal():
    st, hdr, _ = http('POST', '/admin/change-requests', data={'instruction': OLD + ' 을 ' + NEW + ' 로 바꿔', 'from': OLD, 'to': NEW})
    m = re.search(r'/admin/change-jobs/(\d+)', hdr.get('Location', ''))
    check('요청 → 작업 화면', st == 302 and m is not None, hdr.get('Location'))
    job = int(m.group(1))
    check('수정안 만들기가 끝난다', wait_job(job) is not None)
    ps = props(job)
    print('job', job, 'proposals', len(ps))
    check('수정안이 생겼다(pending)', ps and all(p['status'] == 'pending' for p in ps), [p['status'] for p in ps])
    before = {p['chunk']: chunk(p['chunk']) for p in ps}
    docs = sorted({p['doc'] for p in ps})
    md_before = {d: md_text(d) for d in docs}
    os.makedirs(ROOT + '/target/e2e', exist_ok=True)
    json.dump({'job': job, 'docs': docs}, open(ROOT + '/target/e2e/last.json', 'w', encoding='utf-8'))
    bk0 = backups()

    st, _, body = http('GET', '/admin/change-jobs/%d/review' % job)
    check('검토 화면이 열린다', st == 200 and 'rv-backdrop' in body, st)
    st, _, _ = http('POST', '/admin/change-jobs/%d/review/batch' % job)
    check('글자 치환 작업의 일괄 적용은 409', st == 409, st)
    st, _, body = http('GET', '/admin/change-jobs/%d' % job)
    check('작업 화면에 문서 단위 승인 버튼이 없다', '/docs/approve' not in body)

    # 확인 전에는 아무것도 바뀌지 않는다
    check('확인 전: 청크·벡터 그대로', all(chunk(c) == before[c] for c in before))

    # 첫 수정안은 사람이 한 줄 덧붙여 확인, 나머지는 기계 수정안 그대로
    edited = ps[0]
    human = edited['after'].rstrip('\n') + '\n\n' + HUMAN_LINE + '\n'
    st, _, body = confirm(job, edited['id'], human)
    check('사람이 고친 수정안 확인 → 200', st == 200 and 'itemError' not in body, st)
    for p in ps[1:]:
        st, _, body = confirm(job, p['id'], p['after'])
        check('수정안 %d 확인' % p['id'], st == 200, st)
    t0 = time.time()
    ps = drain(job)
    print('drain %.1fs' % (time.time() - t0), [(p['id'], p['status'], p['md']) for p in ps])
    check('모든 수정안이 applied', all(p['status'] == 'applied' for p in ps), [p['status'] for p in ps])

    for p in ps:
        now = chunk(p['chunk'])
        want = human if p['id'] == edited['id'] else p['after']
        check('[%d] 청크 본문 = 확인한 글' % p['id'], now and now['content'].replace('\r\n', '\n') == want.replace('\r\n', '\n'))
        check('[%d] 청크 id 유지' % p['id'], now is not None)
        check('[%d] 벡터가 다시 만들어졌다' % p['id'], now and now['vec'] != before[p['chunk']]['vec'])
        check('[%d] FTS: 옛 말 없음, 새 말 있음' % p['id'], not fts_has(p['chunk'], OLD) and fts_has(p['chunk'], NEW))
        check('[%d] edited_at 이 찍혔다' % p['id'], now and now['meta'].get('edited_at'))
        ctx_before = (before[p['chunk']]['meta'].get('chunk_context') or '')
        ctx_now = (now['meta'].get('chunk_context') or '') if now else ''
        if p['id'] == edited['id']:
            print('   edited ctx before:', ctx_before[:120].replace('\n', ' '))
            print('   edited ctx now   :', ctx_now[:120].replace('\n', ' '))
            check('[%d] 사람이 고친 청크는 맥락을 다시 만들었다(맥락이 바뀜)' % p['id'], ctx_now != ctx_before.replace(OLD, NEW) or ctx_now != ctx_before)
        else:
            check('[%d] 안 고친 청크의 맥락 = 만들 때 값' % p['id'], ctx_now == (p['actx'] or ctx_before), (ctx_now[:60], (p['actx'] or '')[:60]))
    # MD 되쓰기
    for d in docs:
        name, txt = md_text(d)
        b = md_before[d][1]
        print('MD', name, 'old', b.count(OLD) if b else None, '->', txt.count(OLD) if txt else None, 'new', txt.count(NEW) if txt else None)
        if txt is None:
            check('MD 없음(MD_MISSING) — %s' % d, all(p['md'] == 'MD_MISSING' for p in ps if p['doc'] == d))
            continue
        check('MD: 옛 말 없음 · 새 말 있음 (%s)' % name, txt.count(OLD) == 0 and txt.count(NEW) >= 1)
        check('MD: 사람이 덧붙인 줄이 들어갔다', HUMAN_LINE in txt if edited['doc'] == d else True)
    check('md_status 가 모두 기록됐다', all(p['md'] for p in ps), [p['md'] for p in ps])
    # 백업
    jr = job_row(job)
    bk1 = backups()
    check('작업당 백업 하나(backup_name 기록)', jr[1] and len(bk1) == len(bk0) + 1 and jr[1] in bk1, (jr[1], bk0, bk1))
    conv = DATA + '/db-backups/' + (jr[1] or '') + '.converted'
    check('백업 옆에 적용 전 변환 MD 사본이 있다', os.path.isdir(conv) and all(
        open(conv + '/' + f, encoding='utf-8').read().count(NEW) == 0 for f in os.listdir(conv)) and os.listdir(conv),
        os.listdir(conv) if os.path.isdir(conv) else None)
    # 재사용 표시
    c = db()
    inv = c.execute("select count(*), sum(invalidated_at is not null) from turn_source_ref where chunk_id in (%s)" %
                    ','.join('?' * len(ps)), [p['chunk'] for p in ps]).fetchone()
    c.close()
    print('turn_source_ref on these chunks: total', inv[0], 'invalidated', inv[1])
    if inv[0]:
        check('재사용 출처가 "수정됨"으로 표시됐다', inv[1] == inv[0], inv)
    # 검색에서 새 말로 찾힌다
    top = [r[0] for r in vector_top(NEW, 10)]
    check('벡터 검색 상위 10 에 바뀐 청크가 있다', any(p['chunk'] in top for p in ps))
    st, _, body = http('GET', '/admin/change-jobs/%d/review?status=all' % job)
    check('검토 화면 "전체"에 적용됨이 보인다', st == 200)
    summary('R1 literal, job %d' % job)


def nl(subject, alt):
    st, hdr, _ = http('POST', '/admin/change-requests', data={'instruction': subject + ' 사용 중단, 대신 ' + alt,
                                                          'type': 'deprecate', 'subject': subject, 'replacement': alt})
    m = re.search(r'/admin/change-jobs/(\d+)', hdr.get('Location', ''))
    check('자연어(deprecate) 요청 → 작업 화면', st == 302 and m is not None, hdr.get('Location'))
    job = int(m.group(1))
    t0 = time.time()
    check('수정안 만들기가 끝난다', wait_job(job, timeout=1200) is not None)
    print('build %.0fs' % (time.time() - t0))
    jr = job_row(job)
    ps = props(job)
    print('job', job, jr[0], 'proposals', [(p['id'], p['kind'], p['status']) for p in ps])
    if not ps:
        print('summary_json', (jr[2] or '')[:600])
        summary('R2 nl, job %d (no proposals)' % job)
        return
    before = {p['chunk']: chunk(p['chunk']) for p in ps}
    bk0 = backups()
    st, _, body = http('GET', '/admin/change-jobs/%d' % job)
    check('작업 화면에 문서 단위 승인 버튼이 있다(일괄 허용 작업)', '/docs/approve' in body)
    st, hdr, body = http('POST', '/admin/change-jobs/%d/review/batch' % job)
    check('일괄 적용 → 200', st == 200, st)
    ps = drain(job)
    print([(p['id'], p['status'], p['md']) for p in ps])
    check('넣기만 수정안이 모두 applied', all(p['status'] == 'applied' for p in ps), [p['status'] for p in ps])
    for p in ps:
        now = chunk(p['chunk'])
        added = [l for l in p['after'].split('\n') if l not in p['before'].split('\n')]
        print('   [%d] added:' % p['id'], added[:2])
        check('[%d] 청크 본문 = 수정안' % p['id'], now and now['content'].replace('\r\n', '\n') == p['after'].replace('\r\n', '\n'))
        check('[%d] 벡터가 다시 만들어졌다' % p['id'], now and now['vec'] != before[p['chunk']]['vec'])
        check('[%d] md_status' % p['id'], bool(p['md']), p['md'])
        name, txt = md_text(p['doc'])
        if txt and p['md'] == 'applied':
            check('[%d] MD 에 넣은 문장이 있다' % p['id'], all(a.strip() in txt for a in added if a.strip()))
    jr = job_row(job)
    check('백업 하나', jr[1] and len(backups()) == len(bk0) + 1, (jr[1], len(bk0), len(backups())))
    summary('R2 nl, job %d' % job)


def fail(job):
    ps = [p for p in props(job) if p['status'] == 'pending']
    assert ps, 'no pending proposal to confirm'
    p = ps[0]
    before = chunk(p['chunk'])
    st, _, _ = confirm(job, p['id'], p['after'])
    check('확인 → 200(바로 돌아온다)', st == 200, st)
    time.sleep(5)
    ps2 = drain(job, timeout=180)
    q = next(x for x in ps2 if x['id'] == p['id'])
    print('status', q['status'], 'md', q['md'])
    check('임베딩 실패면 applied 가 아니다', q['status'] != 'applied', q['status'])
    now = chunk(p['chunk'])
    check('청크·벡터 불변', now == before)
    check('FTS 불변(옛 본문 그대로)', fts_has(p['chunk'], OLD) == (OLD in before['content']))
    st, _, body = http('GET', '/admin/change-jobs/%d/review?status=all&p=%d' % (job, p['id']))
    check('화면에 실패 사유와 [다시 적용] 이 보인다', '임베딩' in body and '/reapply' in body)
    json.dump({'job': job, 'pid': p['id'], 'status': q['status']}, open(ROOT + '/target/e2e/fail.json', 'w', encoding='utf-8'))
    summary('R3 embed failure, job %d' % job)


def retry(job):
    info = json.load(open(ROOT + '/target/e2e/fail.json', encoding='utf-8'))
    st, _, body = http('GET', '/admin/change-jobs/%d/review/%d' % (job, info['pid']))
    check('실패 항목에 사유와 [다시 적용] 이 보인다', '임베딩' in body and '/reapply' in body)
    st, _, _ = http('POST', '/admin/change-jobs/%d/review/%d/reapply' % (job, info['pid']))
    check('다시 적용 → 200', st == 200, st)
    ps = drain(job)
    q = next(x for x in ps if x['id'] == info['pid'])
    print('status', q['status'], 'md', q['md'])
    check('다시 시도 뒤 applied', q['status'] == 'applied', q['status'])
    now = chunk(q['chunk'])
    check('청크 본문 = 수정안', now and now['content'].replace('\r\n', '\n') == q['after'].replace('\r\n', '\n'))
    summary('R3b retry, job %d' % job)


if not argv or argv[0] == 'literal':
    literal()
elif argv[0] == 'nl':
    nl(argv[1], argv[2])
elif argv[0] == 'fail':
    fail(int(argv[1]))
elif argv[0] == 'retry':
    retry(int(argv[1]))
