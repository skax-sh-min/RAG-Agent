"""Scenario H: an administrator asks for a maintenance change IN CHAT (real app, management-only mode, AGENT_ENABLED=true).

Needs the app started with  -DAUTH_MANAGEMENT_ONLY=true -DAGENT_ENABLED=true  on a scratch copy whose `users` table was emptied
(`prep.py --no-users`): this script creates a throwaway administrator through /setup and logs in with it.
The throwaway password is written to target/e2e/admin-credentials.txt (git-ignored), never printed.
"""
import os as _os
ROOT = _os.path.abspath(_os.path.join(_os.path.dirname(__file__), '..', '..')).replace('\\', '/')
HERE = ROOT + '/scripts/maintenance_e2e'
import html, io, json, re, secrets, sys, time, urllib.parse, urllib.request
from http.cookiejar import CookieJar   # drive.py 가 http() 함수를 정의하므로 모듈 이름 http 를 쓰지 않는다
sys.argv = [sys.argv[0]]
src = open(HERE + '/drive.py', encoding='utf-8').read()
exec(src.split("print('== term'")[0])
results.clear()


class Client:
    """Cookie-keeping HTTP client. Spring Security (cookie CSRF repository + BREACH masking) wants the MASKED token that pages render
    into hidden `_csrf` fields — NOT the raw XSRF-TOKEN cookie value — so the token is read from a page (and re-read after login,
    which rotates it)."""

    def __init__(self):
        self.jar = CookieJar()
        self.token = ''

        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *a, **k):
                return None
        self.op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar), NoRedirect)

    def refresh_csrf(self, path='/login'):
        # 관리자가 아직 없으면 /login 이 /setup 으로 302 한다 — 토큰이 없으면 /setup 에서 읽는다.
        for candidate in (path, '/setup', '/login'):
            st, _, body = self.get(candidate)
            m = re.search(r'name="_csrf"[^>]*value="([^"]+)"', body) or re.search(r'<meta name="_csrf" content="([^"]+)"', body)
            if m:
                self.token = m.group(1)
                return True
        return False

    def get(self, path):
        try:
            r = self.op.open(urllib.request.Request(APP + path), timeout=120)
            return r.status, dict(r.headers), r.read().decode('utf-8', 'replace')
        except urllib.error.HTTPError as e:
            return e.code, dict(e.headers), e.read().decode('utf-8', 'replace')

    def post(self, path, data):
        if not self.token:
            self.refresh_csrf()
        data = dict(data)
        data['_csrf'] = self.token
        body = urllib.parse.urlencode(data).encode()
        req = urllib.request.Request(APP + path, data=body, method='POST',
                                     headers={'Content-Type': 'application/x-www-form-urlencoded', 'X-CSRF-TOKEN': self.token})
        try:
            r = self.op.open(req, timeout=300)
            return r.status, dict(r.headers), r.read().decode('utf-8', 'replace')
        except urllib.error.HTTPError as e:
            return e.code, dict(e.headers), e.read().decode('utf-8', 'replace')


def text_of(fragment):
    """The answer lives in the data-md attribute of the assistant bubble (markdown rendered client-side) — a tag stripper would drop it."""
    m = re.search(r'data-md="([^"]*)"', fragment)
    if m:
        return html.unescape(m.group(1)).strip()
    t = re.sub(r'<script.*?</script>|<style.*?</style>', '', fragment, flags=re.S)
    return html.unescape(re.sub(r'\s+', ' ', re.sub(r'<[^>]+>', ' ', t))).strip()


def chat(client, question, thread):
    """Blocking HTMX path (/ui/chat) — returns the visible answer text."""
    st, _, body = client.post('/ui/chat', {'question': question, 'threadId': thread, 'version': 'latest', 'responseMode': 'N', 'directMode': 'false'})
    if st != 200:   # 403·500 이면 "되묻지 않았다" 같은 부정 검사가 공허하게 통과한다 — 요청 자체가 실패한 것으로 크게 알린다
        check('채팅 요청이 200 이다(%s)' % question[:20], False, st)
    return st, text_of(body)


def chat_stream(client, question, thread):
    """SSE path (/ui/chat/stream) — runs on a worker thread without a SecurityContext; returns the streamed answer text."""
    st, _, body = client.post('/ui/chat/stream', {'question': question, 'threadId': thread, 'version': 'latest', 'responseMode': 'N', 'directMode': 'false'})
    out = []
    for m in re.finditer(r'event:token\s*\ndata:(\{.*?\})\s*\n', body):
        try:
            out.append(json.loads(m.group(1))['text'])
        except Exception:
            pass
    if st != 200:
        check('스트리밍 요청이 200 이다(%s)' % question[:20], False, st)
    return st, ''.join(out), body


def jobs():
    c = db()
    r = c.execute("select id, status, summary_json from change_job where kind='maintenance' order by id").fetchall()
    c.close()
    return r


def wait_jobs_idle(timeout=120):
    t0 = time.time()
    while time.time() - t0 < timeout:
        if all(j[1] != 'running' for j in jobs()):
            return True
        time.sleep(2)
    return False


# ── set up the throwaway administrator (log in with the saved credentials, else create it through /setup) ──
admin = Client()
admin.refresh_csrf()
cred_path = ROOT + '/target/e2e/admin-credentials.txt'
email, password = 'e2e-admin@example.test', None
if _os.path.exists(cred_path):
    lines = open(cred_path, encoding='utf-8').read().split()
    if len(lines) >= 2:
        email, password = lines[0], lines[1]
st, hdr, _ = admin.post('/login', {'username': email, 'password': password or 'x'}) if password else (0, {}, '')
logged_in = st == 302 and 'error' not in hdr.get('Location', '') and '/setup' not in hdr.get('Location', '')
if not logged_in:
    password = 'Aa1!' + secrets.token_urlsafe(12)
    open(cred_path, 'w', encoding='utf-8').write(email + chr(10) + password + chr(10))
    admin = Client()
    admin.refresh_csrf()
    st, hdr, _ = admin.post('/setup', {'email': email, 'displayName': 'E2E Admin', 'password': password, 'passwordConfirm': password})
    check('관리자 계정을 만들 수 있다(/setup)', st == 302, st)
    admin.refresh_csrf()
    st, hdr, _ = admin.post('/login', {'username': email, 'password': password})
    logged_in = st == 302 and 'error' not in hdr.get('Location', '')
check('로그인한다', logged_in, hdr.get('Location'))
admin.refresh_csrf('/')   # 로그인이 CSRF 토큰을 돌린다 — 새 세션의 토큰을 다시 읽는다
st, _, _ = admin.get('/admin/change-jobs')
check('관리자 화면에 들어간다(=세션이 ROLE_ADMIN)', st == 200, st)

guest = Client()
guest.refresh_csrf('/')
st, _, _ = guest.get('/admin/change-jobs')
check('게스트는 관리자 화면에 못 들어간다', st in (302, 401, 403), st)

jobs_before = len(jobs())
ASK = lambda a, b: '「%s」' % a in b and '「%s」' % b in b

# ── A: admin asks -> the chat asks back, nothing starts ──
st, a1 = chat(admin, 'PostgreSQL 을 Oracle 로 바꿔 줘', 'e2e-a')
print('ASK  :', a1[:260])
check('A1: 관리자의 요청에 채팅이 되묻는다(「PostgreSQL」·「Oracle」·수정안을 만들까요)',
      '「PostgreSQL」' in a1 and '「Oracle」' in a1 and '수정안을 만들까요' in a1)
check('A1: 옵션은 화면에서만 — 미리 채운 링크를 준다', '/admin/change-jobs?from=PostgreSQL&to=Oracle' in a1)
check('A1: 되묻기 단계에서는 작업이 만들어지지 않는다', len(jobs()) == jobs_before)

# ── A2: "네" -> the job starts (no options) ──
st, a2 = chat(admin, '네', 'e2e-a')
print('START:', a2[:200])
m = re.search(r'/admin/change-jobs/(\d+)', a2)
check('A2: "네" 라고 하면 작업이 시작되고 링크가 답이다', m is not None and '시작했습니다' in a2)
check('A2: 작업이 하나 만들어졌다', len(jobs()) == jobs_before + 1)
wait_jobs_idle()
rep = json.loads(jobs()[-1][2]) if jobs() and jobs()[-1][2] else {}
check('A2: 옵션은 끈 채로 시작했다(semantic·caseVariants false)', rep.get('semantic') is False and rep.get('caseVariants') is False, {k: rep.get(k) for k in ('semantic', 'caseVariants', 'from', 'to')})
check('A2: 바꿀 말·새 말이 요청 그대로다', rep.get('from') == 'PostgreSQL' and rep.get('to') == 'Oracle')
n_after_a = len(jobs())

# ── A3: a second "네" does nothing (the confirmation is one-shot) ──
st, a3 = chat(admin, '네', 'e2e-a')
# 답 텍스트에 "시작했습니다" 가 있는지로 판정하면 안 된다 — 같은 대화에서는 모델이 이력을 되읊을 수 있다. 새 작업이 없고 새 작업 링크가 없다.
check('A3: 이미 쓴 확정은 다시 쓰이지 않는다(새 작업 없음)', len(jobs()) == n_after_a and not re.search(r'/admin/change-jobs/\d+', a3))

# ── C: cancel ──
st, c1 = chat(admin, 'Tibero 를 MariaDB 로 바꿔 줘', 'e2e-c')
check('C1: 되묻는다', '수정안을 만들까요' in c1, c1[:120])
st, c2 = chat(admin, '아니요', 'e2e-c')
st, c3 = chat(admin, '네', 'e2e-c')
check('C2: "아니요" 뒤의 "네" 는 시작하지 못한다(대기가 취소됐다)', len(jobs()) == n_after_a and not re.search(r'/admin/change-jobs/\d+', c3))

# ── C4: mixed reply is a cancellation, not a confirmation ──
st, d1 = chat(admin, 'Tibero 를 MariaDB 로 바꿔 줘', 'e2e-d')
st, d2 = chat(admin, '네 근데 잠깐만요', 'e2e-d')
st, d3 = chat(admin, '네', 'e2e-d')
check('C4: "네 근데 …" 는 확정이 아니다 — 그 뒤의 "네" 도 시작하지 못한다', len(jobs()) == n_after_a and not re.search(r'/admin/change-jobs/\d+', d3))

# ── D: a guest saying the same thing gets nothing ──
st, g1 = chat(guest, 'PostgreSQL 을 Oracle 로 바꿔 줘', 'e2e-g')
st, g2 = chat(guest, '네', 'e2e-g')
check('D: 게스트의 같은 요청은 되묻지도 시작하지도 않는다', '수정안을 만들까요' not in g1 and not re.search(r'/admin/change-jobs/\d+', g2) and len(jobs()) == n_after_a, g1[:100])

# ── E: ordinary document questions are not hijacked for the admin ──
st, e1 = chat(admin, 'AbsBatchComponent 클래스는 어떤 역할을 하나?', 'e2e-e')
check('E: 관리자의 문서 질문은 되묻기로 가지 않는다', '수정안을 만들까요' not in e1 and len(e1) > 20, e1[:120])

# ── B: the SSE path (worker thread, no SecurityContext) ──
wait_jobs_idle()
st, s1, _ = chat_stream(admin, 'Oracle 을 PostgreSQL 로 바꿔 줘', 'e2e-s')
print('SSE ASK:', s1[:200])
check('B1: 스트리밍 경로에서도 관리자에게 되묻는다', '수정안을 만들까요' in s1 and '「Oracle」' in s1)
n_before_s = len(jobs())
st, s2, _ = chat_stream(admin, '네', 'e2e-s')
check('B2: 스트리밍 경로의 "네" 로 작업이 시작된다', '시작했습니다' in s2 and len(jobs()) == n_before_s + 1, s2[:120])
wait_jobs_idle()
st, sg, _ = chat_stream(guest, 'Oracle 을 PostgreSQL 로 바꿔 줘', 'e2e-s2')
check('B3: 스트리밍 경로의 게스트는 되묻지 않는다', '수정안을 만들까요' not in sg)

# ── F: planner accuracy on varied phrasings (admin) ──
phrases = [
    ('Tibero 를 MariaDB 로 변경해 줘', 'Tibero', 'MariaDB'),
    ('문서에서 JEUS 라는 말을 전부 Tomcat 으로 고쳐 주세요', 'JEUS', 'Tomcat'),
    ('ibatis 를 MyBatis 로 교체해', 'ibatis', 'MyBatis'),
    ('WebLogic 대신 JBoss 라고 쓰도록 문서를 수정해 줘', 'WebLogic', 'JBoss'),
    ('Log4j 를 Logback 으로 바꾸는 수정안 만들어 줘', 'Log4j', 'Logback'),
]
hit = 0
for i, (q, a, b) in enumerate(phrases):
    wait_jobs_idle()
    st, ans = chat(admin, q, 'e2e-f%d' % i)
    ok = '수정안을 만들까요' in ans and '「%s」' % a in ans and '「%s」' % b in ans
    hit += ok
    print(('  OK  ' if ok else '  MISS'), q, '=>', ans[:110])
    chat(admin, '아니요', 'e2e-f%d' % i)   # clear the pending
print('planner picked the change tool with the right terms: %d/%d' % (hit, len(phrases)))
check('F: 다양한 표현을 대부분 올바른 바꿀 말·새 말로 되묻는다(소형 모델 — 3/5 이상)', hit >= 3, '%d/%d' % (hit, len(phrases)))

bad = [n for n, ok in results if not ok]
print()
print('SUMMARY(H): %d passed, %d failed' % (len(results) - len(bad), len(bad)))
for n in bad:
    print('  FAILED:', n)
