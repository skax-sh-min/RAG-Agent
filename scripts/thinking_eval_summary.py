"""생각 수준 실측(ThinkingLevelEvaluationTest)의 결과를 호출 지점별 "끔 ↔ 낮게" 짝으로 요약한다 — PLAN §6.29 6단계.

하네스가 절마다 남긴 JSONL(classify · condense · retrieval · rerank · answer · answerS · answerC · evalacc · post · title ·
curated · direct · directS · meta · keyword · md · txt)을 읽어 지연·출력 토큰·생각 토큰(추정)·잘림·판정 비율의 분포를 한 번에 보여 준다.
출하 기본값(`ThinkingSite.shippedDefault`)·응답 필요분(`expectedOutputTokens`)·생각 여유(`ThinkingBudget.headroom`)를 다시
정할 때 읽는 표다.

사용:
    python scripts/thinking_eval_summary.py [<결과 디렉터리> ...]   # 기본 target/thinking-eval, 여러 개면 합쳐 읽는다

하네스를 돌리는 방법은 `ThinkingLevelEvaluationTest` 의 클래스 주석에 있다.
"""
import collections
import json
import os
import sys

DIRS = sys.argv[1:] or ['target/thinking-eval']

# Windows 콘솔(cp949)에서도 한글 표가 깨지지 않게 한다.
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')


def load(section):
    rows = []
    for d in DIRS:
        p = os.path.join(d, section + '.jsonl')
        if not os.path.exists(p):
            continue
        with open(p, encoding='utf-8') as f:
            for line in f:
                line = line.strip()
                if line:
                    rows.append(json.loads(line))
    return rows


def pct(xs, p):
    xs = sorted(x for x in xs if x is not None)
    if not xs:
        return None
    k = max(0, min(len(xs) - 1, int(round(p / 100 * len(xs) + 0.5)) - 1))
    return xs[k]


def mean(xs):
    xs = [x for x in xs if x is not None]
    return sum(xs) / len(xs) if xs else None


def f(x, d=1):
    if x is None:
        return '-'
    return f'{x:.{d}f}' if isinstance(x, float) else str(x)


def dist(xs, scale=1.0):
    xs = [x / scale for x in xs if x is not None]
    if not xs:
        return 'n/a'
    return f'p50 {f(pct(xs, 50))} p95 {f(pct(xs, 95))} max {f(max(xs))}'


def by(rows, key):
    g = collections.OrderedDict()
    for r in rows:
        g.setdefault(key(r), []).append(r)
    return g


def head(title):
    print()
    print('=' * 8, title)


def short_call_table(section, ms_key='ms'):
    rows = load(section)
    if not rows:
        return
    head(section)
    for lv, rs in by(rows, lambda r: r['level']).items():
        print(f"  {lv:>4} n={len(rs):>3} | 지연 s {dist([r.get(ms_key) for r in rs], 1000)} | 출력 tok {dist([r.get('outTok') for r in rs])}"
              f" | 생각(추정) tok {dist([r.get('thinkTokEst') for r in rs])} | 잘림 {sum(r.get('trunc') or 0 for r in rs)}"
              f" | 생각관측 {sum(r.get('thinkSeen') or 0 for r in rs)}/{sum(r.get('calls') or 0 for r in rs)}")


rows = load('classify')
if rows:
    short_call_table('classify')
    cases = by(rows, lambda r: r['case'])
    agree = sum(1 for rs in cases.values() if len({r['type'] for r in rs}) == 1)
    print(f'  유형 일치(끔=낮게): {agree}/{len(cases)}')
    for cid, rs in cases.items():
        t = {r['level']: r['type'] for r in rs}
        if len(set(t.values())) > 1:
            print(f"   불일치 {cid}: {rs[0]['question']!r} {t}")

rows = load('condense')
if rows:
    short_call_table('condense')
    for lv, rs in by(rows, lambda r: r['level']).items():
        print(f"  {lv:>4} 재작성 {sum(1 for r in rs if r['rewrote'])}/{len(rs)} · 핵심어 포함 {sum(1 for r in rs if r['mentionsKey'])}/{len(rs)}")

rows = load('retrieval')
if rows:
    head('retrieval — 쿼리 확장')
    for cfg, rs in by(rows, lambda r: r['config']).items():
        print(f"  {cfg:>12} n={len(rs):>3} recall@10 {f(mean([r['recall10'] for r in rs]), 3)} ndcg@10 {f(mean([r['ndcg10'] for r in rs]), 3)}"
              f" | 확장 실패 {sum(1 for r in rs if r.get('expansionFailed'))}/{len(rs)} | 검색 지연 s {dist([r['ms'] for r in rs], 1000)}"
              f" | 확장 출력 tok {dist([r.get('outTok') for r in rs])} | 잘림 {sum(r.get('trunc') or 0 for r in rs)}")

rows = load('rerank')
if rows:
    head('rerank')
    for lv, rs in by(rows, lambda r: r['level']).items():
        print(f"  {lv:>4} n={len(rs):>3} recall@10 {f(mean([r['recall10'] for r in rs]), 3)} ndcg@10 {f(mean([r['ndcg10'] for r in rs]), 3)}"
              f" | 지연 s {dist([r['ms'] for r in rs], 1000)} | 출력 tok {dist([r.get('outTok') for r in rs])} | 잘림 {sum(r.get('trunc') or 0 for r in rs)}")

for sec in ('answer', 'answerS', 'answerC'):
    rows = load(sec)
    if not rows:
        continue
    head(sec)
    for combo, rs in by(rows, lambda r: r['combo']).items():
        print(f"  {combo:>9} n={len(rs):>3} | 전체 s {dist([r['totalMs'] for r in rs], 1000)} | 생각 델타 {dist([r.get('thinkDeltas') for r in rs])}"
              f" | 답변 자 {f(mean([r['answerChars'] for r in rs]), 0)} | keyHit {sum(1 for r in rs if r['keyHit'])}/{len(rs)}"
              f" | grounded T/F/None {sum(1 for r in rs if r['grounded'] is True)}/{sum(1 for r in rs if r['grounded'] is False)}/{sum(1 for r in rs if r['grounded'] is None)}"
              f" | 검증 s {dist([r.get('eval_llmMs') for r in rs], 1000)} | 검증 출력 {dist([r.get('eval_outTok') for r in rs])} | 검증 잘림 {sum(r.get('eval_trunc') or 0 for r in rs)}"
              f" | 답변 잘림 {sum(r.get('ans_trunc') or 0 for r in rs)}")

rows = load('evalacc')
if rows:
    head('evalacc — 검증 정확도(positive=자기 문서, negative=다른 주제 문서, mutated=식별자를 뒤집은 답변)')
    for (pair, lv), rs in by(rows, lambda r: (r['pair'], r['level'])).items():
        tr = sum(1 for r in rs if r['grounded'] is True)
        fa = sum(1 for r in rs if r['grounded'] is False)
        no = sum(1 for r in rs if r['grounded'] is None)
        print(f"  {pair:>8}/{lv:>4} n={len(rs):>3} grounded T/F/None {tr}/{fa}/{no} | 지연 s {dist([r['ms'] for r in rs], 1000)} | 출력 tok {dist([r.get('outTok') for r in rs])} | 잘림 {sum(r.get('trunc') or 0 for r in rs)}")

for sec in ('post', 'title', 'curated'):
    short_call_table(sec)
    rows = load(sec)
    for lv, rs in by(rows, lambda r: r['level']).items():
        extra = ''
        if sec == 'post':
            extra = f" · 화면 기다림(20초) 안에 도착 {sum(1 for r in rs if r.get('withinUiWait'))}/{len(rs)}"
        print(f"  {lv:>4} 성공 {sum(1 for r in rs if r.get('ok'))}/{len(rs)}{extra}")

for sec in ('direct', 'directS', 'meta'):
    rows = load(sec)
    if rows:
        head(sec)
        for lv, rs in by(rows, lambda r: r['level']).items():
            print(f"  {lv:>4} n={len(rs):>3} | 전체 s {dist([r['totalMs'] for r in rs], 1000)} | 첫 토큰 s {dist([r['firstTokenMs'] for r in rs], 1000)}"
                  f" | 생각 델타 {dist([r.get('thinkDeltas') for r in rs])} | 답변 자 {f(mean([r['answerChars'] for r in rs]), 0)}")

rows = load('keyword')
if rows:
    head('keyword (키워드 + 맥락)')
    for lv, rs in by([r for r in rows if r['case'] != 'TOTAL'], lambda r: r['level']).items():
        print(f"  {lv:>4} 청크 {len(rs)} | 맥락 생성 {sum(1 for r in rs if r['llmContext'])}/{len(rs)} | 키워드 수 {dist([r['nKeywords'] for r in rs])}"
              f" | 본문에 실린 키워드 비율 {f(mean([r['keywordsInText'] / max(1, r['nKeywords']) for r in rs]), 2)}")
    for r in rows:
        if r['case'] == 'TOTAL':
            print(f"  TOTAL {r['level']}: 청크 {r['chunks']} · {r['ms']} ms · 호출 {r['calls']} · 출력 {r['outTok']} tok · 잘림 {r['trunc']} · 생각관측 {r['thinkSeen']}")

for sec in ('md', 'txt'):
    rows = load(sec)
    if rows:
        head(sec)
        for r in rows:
            print('  ', {k: v for k, v in r.items() if k not in ('section', 'case', 'site')})
