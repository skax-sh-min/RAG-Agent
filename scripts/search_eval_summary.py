"""검색 품질 하네스(SearchQualityEvaluationTest) 결과 요약.

하네스가 실행마다 남긴 `<out>/<label>/gold.jsonl`(골든셋)·`followup.jsonl`(후속 질문)을 읽어 실행끼리 나란히 보여 준다 —
recall@10 · nDCG@10 · 놓친 케이스, 케이스별 정답 순위, 후속 질문의 갈래(raw · pipeline · condensed · concat · full ·
fused)별 recall 을 질문 종류(same-topic · topic-switch)로 나눠서, 그리고 독립화 게이트를 바꿨을 때의 모의.

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
VARIANTS = ['raw', 'pipeline', 'condensed', 'concat', 'full', 'fused']


def kind_of(r):
    return r.get('kind') or 'same-topic'   # 2026-10-12 의 실행에는 kind 가 없다 — 전부 같은 주제 질문이었다


def variant_line(rows):
    present = [v for v in VARIANTS if any(r['variant'] == v for r in rows)]
    return '  '.join(f"{v} {mean([r['recall'] for r in rows if r['variant'] == v]):.3f}" for v in present)


if follow:
    print('\n== 후속 질문 (raw = 그대로·확장 없음 · pipeline = 지금 동작 · condensed = 길이 무관 독립화·확장 없음 · '
          'concat = 앞 질문 + 후속 질문 · full = 자립 질문 · fused = raw·concat 결과의 RRF)')
    for label, rows in follow.items():
        kinds = sorted({kind_of(r) for r in rows}, key=lambda k: (k != 'same-topic', k))
        for kind in kinds:
            sub = [r for r in rows if kind_of(r) == kind]
            n = len({r['case'] for r in sub})
            print(f'  {label:<12} {kind:<12}({n:>2}건) recall@10  {variant_line(sub)}')
            for gate in (True, False):
                gsub = [r for r in sub if r['gateOpen'] == gate]
                if gsub:
                    gn = len({r['case'] for r in gsub})
                    print(f"  {'':<12} {'':<12}  게이트 {'열림' if gate else '닫힘'}({gn}건)  {variant_line(gsub)}")
        if len(kinds) > 1:
            print(f"  {label:<12} {'전체':<12}({len({r['case'] for r in rows}):>2}건) recall@10  {variant_line(rows)}")
    for label, rows in follow.items():
        print(f'\n  [{label}] 케이스별 순위와 독립화 결과')
        for case in sorted({r['case'] for r in rows}):
            by = {r['variant']: r for r in rows if r['case'] == case}
            ranks = '  '.join(f"{v}={(by[v]['rank'] if by[v]['rank'] > 0 else '-') if v in by else '?'}" for v in VARIANTS)
            first = next(iter(by.values()))
            rewritten = (by.get('condensed') or {}).get('rewritten')
            print(f"   {case} {kind_of(first)[:6]:<6} len={first['length']:>2} gate={'O' if first['gateOpen'] else 'X'}  {ranks}  "
                  f"| {by['raw']['question'] if 'raw' in by else ''} → {rewritten or '(재작성 없음)'}")

# ── 게이트 모의 ─────────────────────────────────────────────────────────────────────────────
# 지금의 파이프라인은 "길이 < min-length 면 독립화(확장 없음), 아니면 확장"이다. 실행마다 케이스별로 condensed(독립화)와
# pipeline(그 실행의 게이트 — 닫힌 케이스는 확장) 결과가 있으므로, 그 실행의 게이트 이상인 L 에 대해 "게이트가 L 이었다면"을
# 그대로 다시 짤 수 있다(L 보다 짧으면 대안, 아니면 확장 결과). 대안은 condensed(독립화) · concat(이어 붙이기) · fused(원문과
# 이어 붙이기를 함께) 셋이다.
if follow:
    print('\n== 게이트 모의 (recall@10) — L 보다 짧은 후속 질문만 대안(독립화 · 이어 붙이기 · 함께)으로, 나머지는 확장')
    for label, rows in follow.items():
        kinds = sorted({kind_of(r) for r in rows}, key=lambda k: (k != 'same-topic', k))
        run_gate = min((r['length'] for r in rows if not r['gateOpen']), default=0)  # 닫힌 케이스 중 가장 짧은 길이 ≥ 실행의 게이트
        for kind in kinds + (['전체'] if len(kinds) > 1 else []):
            sub = rows if kind == '전체' else [r for r in rows if kind_of(r) == kind]
            by_case = {}
            for r in sub:
                by_case.setdefault(r['case'], {})[r['variant']] = r
            cells = []
            for gate in (10, 12, 15, 10**6):
                name = '∞' if gate == 10**6 else str(gate)
                if gate < run_gate and any(gate <= v['raw']['length'] < run_gate for v in by_case.values() if 'raw' in v):
                    cells.append(f'L={name}: (불가)')
                    continue
                for alt in ('condensed', 'concat', 'fused'):
                    if any(alt not in v for v in by_case.values()):
                        continue
                    hits = sum((v[alt] if v['raw']['length'] < gate else v['pipeline'])['recall'] for v in by_case.values())
                    cells.append(f'L={name} {alt[:4]} {hits / len(by_case):.3f}')
            print(f'  {label:<12} {kind:<12} ' + '  '.join(cells))
