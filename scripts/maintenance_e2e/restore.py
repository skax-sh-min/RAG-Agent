"""Rehearsal: roll a maintenance job back from its pre-change DB backup (OPERATOR_MANUAL 6.2.2), on the scratch copy.

  restore.py before <jobId>   app STOPPED — record the current state, then restore change_job.backup_name of that job
                              exactly as the manual says (move memory.db aside, delete -wal/-shm, copy the backup in)
  restore.py after <jobId>    app RUNNING again — compare against the recorded state and print what came back and what did not
Run `review.py literal` (job with BEDU2002 -> ZNEWBATCH2002) and `review.py nl OldFileDailyCleaner ...` first.
"""
import os as _os
ROOT = _os.path.abspath(_os.path.join(_os.path.dirname(__file__), '..', '..')).replace('\\', '/')
HERE = ROOT + '/scripts/maintenance_e2e'
import io, json, os, shutil, sqlite3, sys
argv = sys.argv[1:]
sys.argv = [sys.argv[0]]
src = open(HERE + '/drive.py', encoding='utf-8').read()
exec(src.split("print('== term'")[0])
results.clear()
OLD, NEW = 'BEDU2002', 'ZNEWBATCH2002'
NOTICE = '**사용 중단** — OldFileDailyCleaner'
STATE = ROOT + '/target/e2e/restore-state.json'


def state():
    c = db()
    s = {
        'chunks_old': c.execute("select count(*) from vec_document_chunks where version='latest' and content like ?", ('%' + OLD + '%',)).fetchone()[0],
        'chunks_new': c.execute("select count(*) from vec_document_chunks where version='latest' and content like ?", ('%' + NEW + '%',)).fetchone()[0],
        'chunks_notice': c.execute("select count(*) from vec_document_chunks where version='latest' and content like ?", ('%' + NOTICE + '%',)).fetchone()[0],
        'fts_old': c.execute("select count(*) from chunk_fts where chunk_fts match ?", ('"' + OLD + '"',)).fetchone()[0],
        'fts_new': c.execute("select count(*) from chunk_fts where chunk_fts match ?", ('"' + NEW + '"',)).fetchone()[0],
        'n_chunks': c.execute("select count(*) from vec_document_chunks").fetchone()[0],
        'n_vec': c.execute("select count(*) from vec_embeddings").fetchone()[0],
        'jobs': c.execute("select id, status, backup_name from change_job order by id").fetchall(),
        'proposals': c.execute("select job_id, status, count(*) from change_proposal group by job_id, status order by job_id, status").fetchall(),
    }
    c.close()
    md = {}
    for f in sorted(os.listdir(DATA + '/converted')):
        if f.startswith('EDUBASE_BATCH') or f.startswith('nexcore-framework-core-util') or f.startswith('nexcore-framework-core-data'):
            t = open(DATA + '/converted/' + f, encoding='utf-8').read()
            md[f] = {'old': t.count(OLD), 'new': t.count(NEW), 'notice': t.count(NOTICE)}
    s['md'] = md
    return s


def show(tag, s):
    print('--', tag)
    for k, v in s.items():
        print('  ', k, v)


def restore_md(name):
    # 복원하는 사본 시점 이후에 만든 작업의 MD 수정도 함께 되돌린다 — 그 사본과 그 뒤 모든 사본의 .converted 를 최신 것부터 덮어써서
    # 파일마다 가장 오래된(= 복원 시점에 가장 가까운) 사본이 남게 한다. 이름이 시각 순이다.
    bdir = DATA + '/db-backups'
    copies = sorted((d for d in os.listdir(bdir) if d.endswith('.db.converted') and d[:-len('.converted')] >= name), reverse=True)
    for d in copies:
        for f in os.listdir(bdir + '/' + d):
            shutil.copy(bdir + '/' + d + '/' + f, DATA + '/converted/' + f)
        print('restored converted MD copies from', d, sorted(os.listdir(bdir + '/' + d)))
    if not copies:
        print('no converted MD copy next to the backup (backup made before 2026-11-23)')


if argv[0] == 'before':
    job = int(argv[1])
    c = sqlite3.connect(DATA + '/memory.db')
    name = c.execute("select backup_name from change_job where id=?", (job,)).fetchone()[0]
    c.close()
    s = state()
    show('now (before restore)', s)
    json.dump({'job': job, 'backup': name, 'state': s}, open(STATE, 'w', encoding='utf-8'), ensure_ascii=False, default=list)
    # OPERATOR_MANUAL 6.2.2 — exactly as written
    shutil.move(DATA + '/memory.db', DATA + '/memory.db.before-restore')
    for ext in ('-wal', '-shm'):
        if os.path.exists(DATA + '/memory.db' + ext):
            os.remove(DATA + '/memory.db' + ext)
    shutil.copy(DATA + '/db-backups/' + name, DATA + '/memory.db')
    print('restored', name, '-> memory.db')
    restore_md(name)


elif argv[0] == 'md':
    restore_md(json.load(open(STATE, encoding='utf-8'))['backup'])
elif argv[0] == 'after':
    saved = json.load(open(STATE, encoding='utf-8'))
    job = saved['job']
    s = state()
    show('after restore', s)
    b = saved['state']
    check('글자 치환이 되돌아갔다: 청크에 옛 말 %d · 새 말 0' % s['chunks_old'], s['chunks_old'] >= 1 and s['chunks_new'] == 0, (s['chunks_old'], s['chunks_new']))
    check('FTS 도 되돌아갔다', s['fts_old'] >= 1 and s['fts_new'] == 0, (s['fts_old'], s['fts_new']))
    check('벡터 수 = 청크 수', s['n_vec'] == s['n_chunks'], (s['n_vec'], s['n_chunks']))
    top = [r[0] for r in vector_top(OLD, 10)]
    c = db()
    olds = [r[0] for r in c.execute("select spring_doc_id from vec_document_chunks where version='latest' and content like ?", ('%' + OLD + '%',))]
    c.close()
    check('벡터 검색이 옛 말 청크를 찾는다(벡터도 옛 것)', any(x in top for x in olds))
    check('뒤 작업의 사용 중단 안내도 청크에서 사라졌다(사본 시점 이후 = 전부 되돌아감)', s['chunks_notice'] == 0, s['chunks_notice'])
    later = [j for j in b['jobs'] if j[0] > job]
    check('사본 뒤에 만든 작업은 목록에서 사라졌다', all(j[0] not in [x[0] for x in s['jobs']] for j in later), [j[0] for j in s['jobs']])
    this = [p for p in s['proposals'] if p[0] == job]
    print('  this job proposals after restore:', this)
    # 파일은 DB 와 함께 돌아가지 않는다
    md_new = sum(v['new'] for f, v in s['md'].items() if f.startswith('EDUBASE_BATCH'))
    md_notice = sum(v['notice'] for f, v in s['md'].items() if not f.startswith('EDUBASE_BATCH'))
    print('  MD after restore: batch doc new-term', md_new, '· core docs notices', md_notice)
    check('변환 MD 도 사본으로 되돌아갔다(새 말·안내 없음)', md_new == 0 and md_notice == 0, (md_new, md_notice))
    check('그 작업의 수정안은 대기로 돌아왔다(승인됨 없음)', all(p[1] != 'approved' for p in this), this)
    st, _, body = http('GET', '/admin/change-jobs/%d/review?status=all' % job)
    check('검토 화면에 "미뤄진 적용 다시 시도" 버튼이 없다', st == 200 and '/review/retry' not in body, st)
    # 재인덱싱해도 되돌린 수정이 되살아나지 않는다
    doc = 'EDUBASE_BATCH_샘플_예제_분석.md_fcd57f2c'
    import time as _t

    def doc_rows():
        c = db()
        rows = c.execute("select spring_doc_id, content from vec_document_chunks where doc_id=? and version='latest'", (doc,)).fetchall()
        c.close()
        return rows
    before_ids = {r[0] for r in doc_rows()}
    st, _, _ = http('POST', '/admin/documents/%s/reindex?force=true' % urllib.parse.quote(doc, safe=''))
    for _ in range(100):
        _t.sleep(3)
        rows = doc_rows()
        if rows and not ({r[0] for r in rows} & before_ids):
            _t.sleep(3)
            break
    rows = doc_rows()
    n_old = sum(OLD in r[1] for r in rows)
    n_new = sum(NEW in r[1] for r in rows)
    print('  after reindex: old', n_old, 'new', n_new)
    check('재인덱싱 뒤에도 옛 말 · 새 말 0 (수정이 되살아나지 않는다)', n_old >= 1 and n_new == 0, (n_old, n_new))
    bad = [n for n, ok in results if not ok]
    print('SUMMARY(restore): %d passed, %d failed' % (len(results) - len(bad), len(bad)))
    for n in bad:
        print('  FAILED:', n)
