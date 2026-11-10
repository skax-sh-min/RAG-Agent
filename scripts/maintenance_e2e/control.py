"""Control: re-index an UNTOUCHED document and compare chunk counts — is the 30 -> 52 jump caused by the maintenance edit?"""
import os as _os
ROOT = _os.path.abspath(_os.path.join(_os.path.dirname(__file__), '..', '..')).replace('\\', '/')
HERE = ROOT + '/scripts/maintenance_e2e'
import io, sys, time
sys.argv = [sys.argv[0]]
src = open(HERE + '/drive.py', encoding='utf-8').read()
exec(src.split("print('== term'")[0])
doc = [r for r in db().execute("select doc_id from doc_registry where doc_id like 'EDUBASE_ONLINE%'").fetchall()][0][0]


def ids():
    c = db()
    r = {x[0] for x in c.execute("select spring_doc_id from vec_document_chunks where doc_id=? and version='latest'", (doc,)).fetchall()}
    c.close()
    return r


b = ids()
print('untouched doc', doc[:40], 'chunks before', len(b))
st, _, _ = http('POST', '/admin/documents/%s/reindex?force=true' % urllib.parse.quote(doc, safe=''))
print('status', st)
t0 = time.time()
while time.time() - t0 < 240:
    a = ids()
    if a and not (a & b):
        time.sleep(3)
        a = ids()
        break
    time.sleep(3)
print('chunks after', len(ids()))
