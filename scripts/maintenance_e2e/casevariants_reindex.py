"""Scenario G2: after the case-variant job, re-indexing a document from its Markdown must NOT bring ORACLE/oracle back."""
import os as _os
ROOT = _os.path.abspath(_os.path.join(_os.path.dirname(__file__), '..', '..')).replace('\\', '/')
HERE = ROOT + '/scripts/maintenance_e2e'
import io, re, sys, time
sys.argv = [sys.argv[0]]
src = open(HERE + '/drive.py', encoding='utf-8').read()
exec(src.split("print('== term'")[0])
results.clear()
OLD, NEW = 'Oracle', 'PostgreSQL'
ci = lambda t: len(re.findall(re.escape(OLD), t, flags=re.I))

c = db()
doc = c.execute("select doc_id from change_proposal where status='applied' and job_id=(select max(job_id) from change_proposal) "
                "group by doc_id order by count(*) limit 1").fetchone()[0]
c.close()


def chunks():
    c = db()
    r = c.execute("select spring_doc_id, content from vec_document_chunks where doc_id=? and version='latest'", (doc,)).fetchall()
    c.close()
    return r


b = chunks()
print('document:', doc, 'chunks', len(b), '| old (case-insensitive):', sum(ci(x[1]) for x in b), '| new:', sum(NEW.lower() in x[1].lower() for x in b))
ids = {x[0] for x in b}
st, _, _ = http('POST', '/admin/documents/%s/reindex?force=true' % urllib.parse.quote(doc, safe=''))
check('재인덱싱 시작', st in (200, 202), st)
t0 = time.time()
while time.time() - t0 < 280:
    a = chunks()
    if a and not ({x[0] for x in a} & ids):
        time.sleep(3)
        break
    time.sleep(3)
a = chunks()
print('after reindex: chunks', len(a), '| old (case-insensitive):', sum(ci(x[1]) for x in a), '| new (POSTGRESQL variants incl.):', sum(NEW.lower() in x[1].lower() for x in a))
check('재인덱싱으로 청크 id 가 새로 만들어졌다', a and not ({x[0] for x in a} & ids))
check('재인덱싱 뒤에도 옛 말(대문자 변형 포함)이 되살아나지 않는다', sum(ci(x[1]) for x in a) == 0)
check('새 말은 남는다', sum(NEW.lower() in x[1].lower() for x in a) >= 1)
bad = [n for n, ok in results if not ok]
print('SUMMARY(G2): %d passed, %d failed' % (len(results) - len(bad), len(bad)))
