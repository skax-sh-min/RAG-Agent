"""PLAN §6.30 축소 사다리 측정(PromptLadderEvaluationTest) 요약.

사용: python scripts/ladder_eval_summary.py target/ladder-eval [old-label new-label] [--logs DIR]

<out>/<label>/<section>.jsonl 을 읽어 절마다 표를 찍는다 — keyHit(골든 마커가 답변에 있는가) · 검증 통과/근거 없음/판정 없음 ·
재시도 · LLM 호출 · 축소 안내가 붙은 턴 · [SHRINK] · 조기 종료. 라벨을 둘 주면 같은 사례끼리 짝지어 무엇이든 바뀐 사례를 나열한다.

--logs DIR 을 주면 DIR/<label>.log(그 실행의 Maven 출력)에서 **시도별** 순서를 읽는다 — JSONL 은 턴의 최종 상태만 담기 때문이다.
시도마다 [BUDGET](문서 a→b · 이력 · 칸) · [EVAL] 검증 미통과 · 교체(밀어낼 청크) · retry #n 을 이어 붙여:
  - E 의 근거: 문서를 줄인 시도에서 나온 반려 뒤 재시도가 결국 통과한 비율 vs 줄이지 않은 시도의 반려
  - 교체가 일어난 반려 수(가드 ① — 검증이 근거 문서를 보고하지 않으면 교체가 없다)
  - 조기 종료(B)
로그는 하네스 JVM 의 표준 출력이라 Windows 에서는 cp949 로 남는다 — UTF-8 로 먼저 읽고 안 되면 cp949 로 읽는다.
"""
import json
import re
import sys
from collections import OrderedDict
from pathlib import Path

SECTIONS = OrderedDict([
    ("long", "긴 독립 질문 + 다른 주제 이력 4턴"),
    ("fu1", "① 지시어를 문서로 풀 수 있는 후속 질문"),
    ("fu2", "② 직전 답변의 내용을 묻는 질문"),
    ("fu3", "③ 직전 턴이 Direct"),
])


def load(root: Path, label: str):
    rows = {}
    for section in SECTIONS:
        f = root / label / f"{section}.jsonl"
        if not f.exists():
            continue
        for line in f.read_text(encoding="utf-8").splitlines():
            if line.strip():
                r = json.loads(line)
                rows[(section, r["id"])] = r   # 같은 사례를 다시 돌렸으면 마지막 것
    return rows


def docs_used(r):
    """[BUDGET] 줄의 '문서 a→b개' 에서 마지막 b — 없으면 축소가 없었다(None)."""
    lines = r.get("budgetLines") or []
    for line in reversed(lines):
        m = re.search(r"문서 (\d+)→(\d+)개", line)
        if m:
            return int(m.group(2))
    return None


def table(rows, label):
    print(f"\n## {label}")
    print("| 절 | n | keyHit | 검증 통과 | 근거 없음 | 판정 없음 | 재시도(합) | LLM 호출 | 소요(초) | 축소 안내 | [SHRINK] | 조기 종료 | 오류 |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for section, title in SECTIONS.items():
        rs = [r for (s, _), r in rows.items() if s == section]
        if not rs:
            continue
        n = len(rs)
        hit = sum(1 for r in rs if r["keyHit"])
        ok = sum(1 for r in rs if r.get("grounded") is True)
        bad = sum(1 for r in rs if r.get("grounded") is False)
        none = n - ok - bad
        retries = sum(r.get("retries") or 0 for r in rs)
        calls = sum(r.get("llmCalls") or 0 for r in rs)
        elapsed = sum(r.get("elapsedMs") or 0 for r in rs) / 1000
        notes = sum(1 for r in rs if r.get("budgetNote"))
        shrink = sum(r.get("shrinkLogs") or 0 for r in rs)
        early = sum(r.get("earlyStops") or 0 for r in rs)
        err = sum(1 for r in rs if r.get("error"))
        print(f"| {title} | {n} | {hit} | {ok} | {bad} | {none} | {retries} | {calls} | {elapsed:.0f} | {notes} | "
              f"{shrink} | {early} | {err} |")


def paired(old, new, old_label, new_label):
    print(f"\n## 짝 비교 — {old_label} → {new_label}")
    same = sum(1 for k in set(old) & set(new) if old[k].get("answer") == new[k].get("answer"))
    print(f"답변이 글자 하나까지 같은 사례: {same}/{len(set(old) & set(new))}")
    print("\n| 사례 | keyHit | 검증 | 재시도 | 문서(마지막 축소) | 안내(새) |")
    print("|---|---|---|---|---|---|")
    for key in sorted(set(old) & set(new)):
        a, b = old[key], new[key]
        changed = (a["keyHit"] != b["keyHit"] or a.get("grounded") != b.get("grounded")
                   or (a.get("retries") or 0) != (b.get("retries") or 0) or docs_used(a) != docs_used(b))
        if not changed:
            continue
        print(f"| {key[0]} {key[1]} | {a['keyHit']}→{b['keyHit']} | {a.get('grounded')}→{b.get('grounded')} | "
              f"{a.get('retries')}→{b.get('retries')} | {docs_used(a)}→{docs_used(b)} | {b.get('budgetNote') or ''} |")


# ── 시도별(Maven 로그) ────────────────────────────────────────────────────────────────────────────────

CASE_RE = re.compile(r"^\[(?P<label>[^\]]+)\] (?P<sec>\w+) (?P<id>\S+) keyHit=(?P<hit>\w+) grounded=(?P<g>\w+) "
                     r"retries=(?P<r>\d+)")
BUDGET_RE = re.compile(r"\[BUDGET\].*?(?:칸 (?P<rung>\d+)까지: )?문서 (?P<a>\d+)→(?P<b>\d+)개.*?이력 (?P<h1>\d+)→(?P<h2>\d+)자")
FAIL_RE = re.compile(r"\[EVAL\] 검증 미통과 .*sufficient=(?P<s>\w+) grounded=(?P<g>\w+)")
EVICT_RE = re.compile(r"\[EVAL\] 재시도에서 밀어낼 청크 (?P<n>\d+)개")
RETRY_RE = re.compile(r"\[AgentGraph\] retry #\d+ reason=(?P<why>\w+)")


def read_log(path: Path) -> str:
    raw = path.read_bytes()
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        return raw.decode("cp949", errors="replace")


def attempts(path: Path):
    """사례마다 시도 목록 — 시도 = {docs:(a,b), hist:(h1,h2), rung, fail:(s,g), evicted:n}. [BUDGET] 은 시도의 첫 줄만 쓴다
    (사다리 전 코드는 한 시도에 같은 줄을 여러 번 남긴다)."""
    cases, cur = [], {"attempts": [{}]}
    for line in read_log(path).splitlines():
        att = cur["attempts"][-1]
        if m := BUDGET_RE.search(line):
            if "docs" not in att:
                att["docs"] = (int(m["a"]), int(m["b"]))
                att["hist"] = (int(m["h1"]), int(m["h2"]))
                att["rung"] = int(m["rung"]) if m["rung"] else None
        elif m := FAIL_RE.search(line):
            att["fail"] = (m["s"], m["g"])
        elif m := EVICT_RE.search(line):
            att["evicted"] = int(m["n"])
        elif m := RETRY_RE.search(line):
            att["why"] = m["why"]
            cur["attempts"].append({})
        elif "retry stopped early" in line:
            cur["early"] = cur.get("early", 0) + 1
        elif m := CASE_RE.match(line):
            cur.update(sec=m["sec"], id=m["id"], hit=m["hit"] == "true", passed=m["g"] == "true")
            cases.append(cur)
            cur = {"attempts": [{}]}
    return cases


def trimmed(att):
    d = att.get("docs")
    return d is not None and d[1] < d[0]


def attempt_summary(cases, label):
    print(f"\n## 시도별 — {label}")
    print("| 절 | 시도 | 문서를 줄인 시도 | 반려 | 그중 문서를 줄인 시도 | 교체가 일어난 반려 | 조기 종료 |")
    print("|---|---|---|---|---|---|---|")
    for section, title in SECTIONS.items():
        cs = [c for c in cases if c.get("sec") == section]
        if not cs:
            continue
        atts = [a for c in cs for a in c["attempts"]]
        rejected = [a for c in cs for a in c["attempts"][:-1]]
        print(f"| {title} | {len(atts)} | {sum(map(trimmed, atts))} | {len(rejected)} | "
              f"{sum(map(trimmed, rejected))} | {sum(1 for a in rejected if a.get('evicted'))} | "
              f"{sum(c.get('early', 0) for c in cs)} |")
    # E — 반려를 사례 단위로 센다(한 사례의 반려 둘을 둘로 세면 실패한 사례가 두 번 무게를 얻는다)
    t_cases = [c for c in cases if any(trimmed(a) for a in c["attempts"][:-1])]
    u_cases = [c for c in cases if c["attempts"][:-1] and not any(trimmed(a) for a in c["attempts"][:-1])]
    print(f"\n반려가 있었던 사례 중 — 문서를 줄인 시도에서 반려: {len(t_cases)}건, 재시도로 결국 통과 "
          f"{sum(c['passed'] for c in t_cases)} · 줄이지 않은 시도에서만 반려: {len(u_cases)}건, 결국 통과 "
          f"{sum(c['passed'] for c in u_cases)}")


def main():
    args = sys.argv[1:]
    logs = None
    if "--logs" in args:
        i = args.index("--logs")
        logs = Path(args[i + 1])
        args = args[:i] + args[i + 2:]
    root = Path(args[0] if args else "target/ladder-eval")
    labels = args[1:] or sorted(p.name for p in root.iterdir() if p.is_dir())
    data = {label: load(root, label) for label in labels}
    for label in labels:
        table(data[label], label)
    if len(labels) == 2:
        paired(data[labels[0]], data[labels[1]], labels[0], labels[1])
    if logs:
        for label in labels:
            f = logs / f"{label}.log"
            if f.exists():
                attempt_summary(attempts(f), label)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
