"""검색 품질 하네스(SearchQualityEvaluationTest) 결과 요약.

하네스가 실행마다 남긴 `<out>/<label>/gold.jsonl`(골든셋)·`followup.jsonl`(후속 질문)을 읽어 실행끼리 나란히 보여 준다 —
recall@10 · nDCG@10 · 놓친 케이스, 케이스별 정답 순위, 후속 질문의 갈래(raw · pipeline · condensed · full)별 recall.

사용:
    python scripts/search_eval_summary.py [<결과 디렉터리>]          # 기본 target/search-eval, 그 아래 실행(label)마다 폴더

하네스를 돌리는 방법은 `SearchQualityEvaluationTest` 의 클래스 주석에 있다.
"""
import json
import os
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else 'target/search-eval'

# Windows 콘솔(cp949)에서도 한글 표가 깨지지 않게 한다.
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')


def load(label, section):
    p = os.path.join(ROOT, label, section + '.jsonl')
    if not os.path.exists(p):
        return []
    with open(p, encoding='utf-8') as f:
        return [json.loads(line) for line in f if line.strip()]


def mean(xs):
    xs = [x for x in xs if x is not None]
    return sum(xs) / len(xs) if xs else float('nan')


labels = sorted(d for d in os.listdir(ROOT) if os.path.isdir(os.path.join(ROOT, d))) if os.path.isdir(ROOT) else []
if not labels:
    print(f'결과가 없다: {ROOT}')
    sys.exit(0)

print(f'== 골든셋 (실행 {len(labels)}개)')
gold = {label: load(label, 'gold') for label in labels}
for label in labels:
    rows = gold[label]
    if not rows:
        continue
    misses = [r['case'] for r in rows if r['recall'] == 0]
    ceiling = sum(1 for r in rows if r.get('inCorpus', True))
    print(f"  {label:<12} n={len(rows):>2}  recall@10 {mean([r['recall'] for r in rows]):.3f}"
          f"  nDCG@10 {mean([r['ndcg'] for r in rows]):.3f}  상한 {ceiling}/{len(rows)}"
          f"  검색 ms 중앙 {sorted(r['ms'] for r in rows)[len(rows) // 2]:>5}  놓침 {', '.join(misses) or '-'}")

cases = []
for label in labels:
    for r in gold[label]:
        if r['case'] not in cases:
            cases.append(r['case'])
if cases:
    print('\n  케이스별 정답 순위(- = top-10 밖)')
    print('  ' + ' ' * 12 + ''.join(f'{label[:11]:>12}' for label in labels))
    for case in cases:
        cells = []
        for label in labels:
            hit = next((r for r in gold[label] if r['case'] == case), None)
            cells.append('' if hit is None else (str(hit['rank']) if hit['rank'] > 0 else '-'))
        if len(set(cells)) > 1 or '-' in cells:
            print(f'  {case:<12}' + ''.join(f'{c:>12}' for c in cells))
    print('  (모든 실행에서 같은 순위인 케이스는 생략)')

follow = {label: load(label, 'followup') for label in labels}
follow = {k: v for k, v in follow.items() if v}
if follow:
    print('\n== 후속 질문 (raw = 그대로·확장 없음 · pipeline = 지금 동작 · condensed = 길이 무관 독립화·확장 없음 · full = 자립 질문)')
    variants = ['raw', 'pipeline', 'condensed', 'concat', 'full']
    for label, rows in follow.items():
        line = '  '.join(f"{v} {mean([r['recall'] for r in rows if r['variant'] == v]):.3f}" for v in variants)
        print(f'  {label:<12} recall@10  {line}')
        for gate in (True, False):
            sub = [r for r in rows if r['gateOpen'] == gate]
            if sub:
                line = '  '.join(f"{v} {mean([r['recall'] for r in sub if r['variant'] == v]):.3f}" for v in variants)
                n = len({r['case'] for r in sub})
                print(f"  {'':<12}  게이트 {'열림' if gate else '닫힘'}({n}건)  {line}")
    for label, rows in follow.items():
        print(f'\n  [{label}] 케이스별 순위와 독립화 결과')
        for case in sorted({r['case'] for r in rows}):
            by = {r['variant']: r for r in rows if r['case'] == case}
            ranks = '  '.join(f"{v}={(by[v]['rank'] if by[v]['rank'] > 0 else '-') if v in by else '?'}" for v in variants)
            first = next(iter(by.values()))
            rewritten = (by.get('condensed') or {}).get('rewritten')
            print(f"   {case} len={first['length']:>2} gate={'O' if first['gateOpen'] else 'X'}  {ranks}  "
                  f"| {by['raw']['question'] if 'raw' in by else ''} → {rewritten or '(재작성 없음)'}")

# ── 게이트 모의 ─────────────────────────────────────────────────────────────────────────────
# 지금의 파이프라인은 "길이 < min-length 면 독립화(확장 없음), 아니면 확장"이다. 실행마다 케이스별로 condensed(독립화)와
# pipeline(그 실행의 게이트 — 닫힌 케이스는 확장) 결과가 있으므로, 그 실행의 게이트 이상인 L 에 대해 "게이트가 L 이었다면"을
# 그대로 다시 짤 수 있다(L 보다 짧으면 condensed, 아니면 확장 결과). concat@L 은 독립화 자리에 이어 붙이기를 쓴 것.
if follow:
    print('\n== 게이트 모의 (recall@10) — L 보다 짧은 후속 질문만 독립화/이어 붙이기, 나머지는 확장')
    for label, rows in follow.items():
        by_case = {}
        for r in rows:
            by_case.setdefault(r['case'], {})[r['variant']] = r
        run_gate = min((r['length'] for r in rows if not r['gateOpen']), default=0)  # 닫힌 케이스 중 가장 짧은 길이 ≥ 실행의 게이트
        cells = []
        for gate in (10, 12, 15, 10**6):
            if gate < run_gate and any(10 <= v['raw']['length'] < run_gate for v in by_case.values() if 'raw' in v):
                cells.append(f"L={'∞' if gate == 10**6 else gate}: (불가)")
                continue
            for alt in ('condensed', 'concat'):
                hits, n = 0, 0
                for case, v in by_case.items():
                    if alt not in v:
                        break
                    length = v['raw']['length']
                    chosen = v[alt] if length < gate else v['pipeline']
                    hits += chosen['recall']
                    n += 1
                else:
                    cells.append(f"L={'∞' if gate == 10**6 else gate} {alt[:4]} {hits / n:.3f}")
        print(f'  {label:<12} ' + '  '.join(cells))
