"""6c 사전 평가 골든셋(src/test/resources/agent-eval/edit-gold.json)을 만든다 — AGENT_MCP_DESIGN §10.12.

코퍼스 원문은 저장소에 넣지 않는다: 골든셋에는 청크 id 와 라벨만 남고, 하네스(KnowledgeEditEvaluationTest)가
스크래치 DB 복사본에서 본문을 읽는다. 이 스크립트는 라벨을 검사하고(지시 대상이 글자 그대로 나오는 청크가 하나도 빠지지
않았는가 · 없는 id 를 라벨링하지 않았는가) 대상이 나오지 않는 청크 중 리랭커 점수가 높은 2개를 방해 후보로 덧붙인다.

    python -I scripts/knowledge_edit_eval/build_gold.py <memory.db 복사본> [리랭커 주소, 기본 http://127.0.0.1:28095]

라벨: D 설명함(describes) · M 언급만(mentions) · U 무관 · A 애매(정밀도·재현율 계산에서 뺀다).
규칙: 청크에 대상의 동작·용도를 설명하는 문장이나 표의 설명 칸이 있으면 D, 코드·목록·키워드·다른 주제의 예시에 이름만 나오면 M,
청크 끝에 대상의 절이 막 시작하는 경우(본문은 다음 청크)와 대상을 쓰는 샘플 절은 A. 더 긴 식별자의 일부로만 나오면 U
(copyFile ⊂ copyFileToDirectory) — 앱이 모델에 묻지 않고 무관으로 두는 경우다.
"""
import json
import re
import sqlite3
import sys
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")

CASES = [
    # ── 사용 중단 ─────────────────────────────────────────────────────────
    dict(id="dep-callBatchNow", instruction="callBatchNow 는 더 이상 사용되지 않습니다. 대신 callBatchAfterCommit 을 사용하세요.",
         expect=dict(type="deprecate", subject="callBatchNow", replacement="callBatchAfterCommit"),
         labels={"0f8458a3": "D", "fb33e957": "A", "c2ff5cf8": "D", "b9501cfc": "M", "b757982f": "A", "7bb201a3": "M", "624108d4": "A"}),
    dict(id="dep-reserveCallTimerService", instruction="reserveCallTimerService 는 이제 사용하지 않습니다.",
         expect=dict(type="deprecate", subject="reserveCallTimerService"),
         labels={"d60a5055": "M", "46df9dd6": "D", "5a7adfa5": "M", "86fe0e0c": "D", "69980cfa": "D", "0f819358": "D", "0a8d1471": "A"}),
    dict(id="dep-OldFileDailyCleaner", instruction="OldFileDailyCleaner 클래스는 폐기되었습니다.",
         expect=dict(type="deprecate", subject="OldFileDailyCleaner"),
         labels={"5bed4e19": "D", "8ab7b1f8": "A", "7fba6574": "A", "c9ca8579": "A", "f4f76bdf": "A", "75d06572": "A", "a8b09635": "A"}),
    dict(id="dep-CaseIgnoreHashMap", instruction="CaseIgnoreHashMap 은 사용 중단되었으니 대신 HashMap 을 쓰세요.",
         expect=dict(type="deprecate", subject="CaseIgnoreHashMap", replacement="HashMap"),
         labels={"af1180f1": "M", "1f12e83a": "D", "986d266d": "A", "9e685a0d": "A", "55d49e9a": "A", "18d2382e": "A", "a062e03a": "A"}),
    dict(id="dep-PaddableDataOutputStream", instruction="PaddableDataOutputStream 은 더 이상 지원하지 않습니다.",
         expect=dict(type="deprecate", subject="PaddableDataOutputStream"),
         labels={"3d09d635": "M", "4d0693ce": "D", "eb47060b": "A", "3f694d9d": "A", "2fc3ae22": "A", "f2e58dae": "A", "0bbd8b4b": "A"}),
    dict(id="dep-FUAttcFileMng", instruction="FUAttcFileMng 는 더 이상 쓰이지 않습니다. 대신 FUFileMng 를 사용하세요.",
         expect=dict(type="deprecate", subject="FUAttcFileMng", replacement="FUFileMng"),
         labels={"7e812ad8": "D", "7925da25": "M", "1b50fde8": "A", "52c06f51": "D", "8063d6fd": "D", "f10e3358": "D", "7d4f48f9": "M"}),
    # ── 내용 삭제 ─────────────────────────────────────────────────────────
    dict(id="rm-isSingleJvmBatch", instruction="isSingleJvmBatch 에 대한 설명을 삭제해",
         expect=dict(type="remove", subject="isSingleJvmBatch"),
         labels={"97959659": "M", "1332a6bf": "M", "4fe326c5": "M", "51689137": "M", "ec2578fb": "M", "040aa752": "M", "04c3b3d8": "D"}),
    dict(id="rm-waitForAllReceiveEnd", instruction="waitForAllReceiveEnd 관련 내용은 지워줘",
         expect=dict(type="remove", subject="waitForAllReceiveEnd"),
         labels={"fdeb839d": "M", "301a3841": "M", "9668db79": "D", "2fae6c21": "D", "827d5aff": "M", "7b0a4747": "M", "ee166be2": "D"}),
    dict(id="rm-readLines", instruction="readLines 메서드 설명은 문서에서 빼 줘",
         expect=dict(type="remove", subject="readLines"),
         labels={"1115e67f": "A", "f1af649c": "D", "071bcadd": "D", "c950b511": "A", "432ffd5e": "D", "fec468a2": "D", "c42d7394": "D"}),
    dict(id="rm-callAsyncServiceAfterCommit", instruction="callAsyncServiceAfterCommit 에 대한 내용을 삭제해",
         expect=dict(type="remove", subject="callAsyncServiceAfterCommit"),
         labels={"d60a5055": "M", "9549695b": "D", "5a7adfa5": "M", "86fe0e0c": "D", "cbc54e0c": "M", "42015e7b": "A", "f98c4db6": "A"}),
    dict(id="rm-addErrorCountAmount", instruction="addErrorCountAmount 설명을 지워",
         expect=dict(type="remove", subject="addErrorCountAmount"),
         labels={"6e858da9": "D", "35951a81": "M", "931b66f3": "M", "3ef435b7": "M", "a5f2e166": "A", "0641f105": "D", "5b1ab2f4": "D"}),
    dict(id="rm-closeQuietly", instruction="closeQuietly 에 관한 설명을 삭제해 주세요",
         expect=dict(type="remove", subject="closeQuietly"),
         labels={"a1390050": "A", "49cbb655": "D", "cc2aa6a1": "D", "4b840f14": "D", "7e13de0e": "D", "a84d0ab4": "D", "92ed84cd": "D"}),
    # ── 주의 사항 추가 ─────────────────────────────────────────────────────
    dict(id="note-getJobExecutionId", instruction="getJobExecutionId 에 '같은 Job 을 재실행하면 실행 ID 가 새로 발급된다' 는 주의 사항을 추가해",
         expect=dict(type="add_note", subject="getJobExecutionId", content="같은 Job 을 재실행하면 실행 ID 가 새로 발급된다"),
         labels={"0bdaa7e5": "D", "8c92b24f": "M", "de40151d": "M", "ec8feba6": "M", "ae4fd27e": "M", "d852d2e8": "A",
                 "a4a127a8": "U", "02ff6fd6": "U", "eaf95617": "A"}),
    dict(id="note-CSVFileOption", instruction="CSVFileOption 에 '인코딩을 지정하지 않으면 서버 기본 인코딩이 쓰인다' 는 주의 사항을 추가해",
         expect=dict(type="add_note", subject="CSVFileOption", content="인코딩을 지정하지 않으면 서버 기본 인코딩이 쓰인다"),
         labels={"a1bfa375": "D", "dc966e3e": "A", "0592559f": "A", "7e957b92": "A", "3f64d95e": "A", "c4e6b330": "A", "490526e7": "M"}),
    dict(id="note-dbSelectMulti", instruction="dbSelectMulti 와 관련해 '조회 건수가 많으면 메모리가 부족해질 수 있다' 는 주의 사항을 추가해",
         expect=dict(type="add_note", subject="dbSelectMulti", content="조회 건수가 많으면 메모리가 부족해질 수 있다"),
         labels={"1b7bdf34": "D", "c70d5551": "D", "a0203d2c": "M", "90fdf768": "M", "39e1aa81": "M", "0c4f0e06": "M", "e2f802db": "M"}),
    dict(id="note-copyFile", instruction="copyFile 에 '대상 파일이 이미 있으면 덮어쓴다' 는 주의 사항을 넣어줘",
         expect=dict(type="add_note", subject="copyFile", content="대상 파일이 이미 있으면 덮어쓴다"),
         labels={"0b95e444": "M", "be0887f7": "M", "2489489b": "D", "06d47365": "D", "5872cff9": "D", "6f6b87c0": "D", "83f3ae85": "A"}),
    dict(id="note-cancelCallTimerService",
         instruction="cancelCallTimerService 에 '취소에 실패하면 false 를 돌려주므로 반드시 결과를 확인해야 한다' 는 주의 사항을 추가해",
         expect=dict(type="add_note", subject="cancelCallTimerService", content="취소에 실패하면 false 를 돌려주므로 반드시 결과를 확인해야 한다"),
         labels={"ee90de9e": "M", "6f9db640": "M", "86fe0e0c": "D", "69980cfa": "M", "12f300a1": "D", "b21d0272": "M",
                 "476f5c4a": "M", "5ea4ddc8": "A", "0a8d1471": "D"}),
    dict(id="note-sendDummyReturnNow", instruction="sendDummyReturnNow 에 '비동기 요청 거래에서만 호출해야 한다' 는 주의 사항을 추가해",
         expect=dict(type="add_note", subject="sendDummyReturnNow", content="비동기 요청 거래에서만 호출해야 한다"),
         labels={"ee90de9e": "D", "615b7ce8": "D", "cddc3d6d": "D", "b21d0272": "M", "626c3ef7": "M", "a7fcdabd": "A", "d006ef6b": "D"}),
    # ── 설명 수정 ─────────────────────────────────────────────────────────
    dict(id="rev-dbSelectSingleLockRetry", instruction="dbSelectSingleLockRetry 의 재시도 간격은 0.5초가 아니라 1초다",
         expect=dict(type="revise", subject="dbSelectSingleLockRetry", content="1초"),
         labels={"02734e5d": "D", "38628c35": "D", "debf28d7": "M", "503fa0e6": "D", "bd5a9e46": "D", "aadffed5": "D", "500b1092": "D"}),
    dict(id="rev-sendErrorResponseOnFail", instruction="sendDummyReturnNow 의 sendErrorResponseOnFail 기본값은 true 가 아니라 false 다",
         expect=dict(type="revise", subject="sendErrorResponseOnFail", content="false", subjectAlternates=["sendDummyReturnNow"]),
         labels={"615b7ce8": "D", "d006ef6b": "D"}),
    dict(id="rev-getSharedQueue", instruction="getSharedQueue 의 Timeout 기본값은 10초가 아니라 30초다",
         expect=dict(type="revise", subject="getSharedQueue", content="30초"),
         labels={"de40151d": "M", "ae4fd27e": "M", "9668db79": "D", "49dfe3c8": "M", "2fae6c21": "D", "92833882": "M", "26e13ad2": "M",
                 "13f0dd3d": "A", "5efb2f62": "U", "5067a9c5": "U"}),
    dict(id="rev-NEXCORE_RUNTIME_MODE", instruction="NEXCORE_RUNTIME_MODE 의 기본값은 'L' 이 아니라 'D' 다",
         expect=dict(type="revise", subject="NEXCORE_RUNTIME_MODE", content="'D'"),
         labels={"04c3b3d8": "D", "7f36d38d": "M"}),
    dict(id="rev-callAsyncServiceNow", instruction="callAsyncServiceNow 는 기동 거래가 에러로 끝나면 수동 거래를 실행하지 않는다",
         expect=dict(type="revise", subject="callAsyncServiceNow", content="기동 거래가 에러로 끝나면 수동 거래를 실행하지 않는다"),
         labels={"d60a5055": "M", "9549695b": "D", "5a7adfa5": "M", "86fe0e0c": "D", "3f0d9518": "D", "040e6152": "A", "cbc54e0c": "M", "f98c4db6": "D"}),
    dict(id="rev-BaseRuntimeException", instruction="BaseRuntimeException 은 프레임워크 내부 예외가 아니라 업무 예외의 부모 클래스다",
         expect=dict(type="revise", subject="BaseRuntimeException", content="업무 예외의 부모 클래스"),
         labels={"b8d63f5b": "D", "53e8ca5d": "M", "609819f0": "M", "a4d8981d": "M", "e68c7061": "M", "056182df": "M", "ad303632": "U", "a8c5160c": "M"}),
]

# 해석만 재는 지시 — 유형 넷 밖(글자 바꾸기 · 불명확).
INTERPRET_ONLY = [
    dict(id="int-replace-1", instruction="문서에 나오는 Job Mind 를 배치 스케줄러로 바꿔", expect=dict(type="replace", **{"from": "Job Mind", "to": "배치 스케줄러"})),
    dict(id="int-replace-2", instruction="MCI 를 채널통합으로 전부 바꿔 줘", expect=dict(type="replace", **{"from": "MCI", "to": "채널통합"})),
    dict(id="int-replace-3", instruction="NEXCORE 9.0 표기를 NEXCORE 10 으로 고쳐", expect=dict(type="replace", **{"from": "NEXCORE 9.0", "to": "NEXCORE 10"})),
    dict(id="int-unclear-1", instruction="문서 좀 정리해 줘", expect=dict(type="unclear")),
    dict(id="int-unclear-2", instruction="온라인 거래 쪽 내용이 좀 이상한 것 같아", expect=dict(type="unclear")),
]

SPECIAL = re.compile(r"[A-Za-z0-9_]")


def standalone(text, subject):
    """ImpactAnalysisWorkflow.hasStandaloneOccurrence 와 같은 규칙 — 끝 글자가 영숫자·밑줄이면 바깥 이웃도 그렇지 않아야 한다."""
    lo, s = text.lower(), subject.lower()
    i = lo.find(s)
    while i >= 0:
        before = text[i - 1] if i > 0 else ""
        after = text[i + len(s)] if i + len(s) < len(text) else ""
        ok_l = not (SPECIAL.match(subject[0]) and before and SPECIAL.match(before))
        ok_r = not (SPECIAL.match(subject[-1]) and after and SPECIAL.match(after))
        if ok_l and ok_r:
            return True
        i = lo.find(s, i + 1)
    return False


def rerank(base, query, docs):
    body = json.dumps({"model": "bge-reranker-v2-m3", "query": query, "documents": docs}).encode("utf-8")
    req = urllib.request.Request(base.rstrip("/") + "/v1/rerank", data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return {x["index"]: x["relevance_score"] for x in json.load(r)["results"]}


def main():
    db, base = sys.argv[1], (sys.argv[2] if len(sys.argv) > 2 else "http://127.0.0.1:28095")
    c = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    rows = c.execute("select spring_doc_id, content, metadata from vec_document_chunks where version='latest'").fetchall()
    by_id = {i: (t, json.loads(m)) for i, t, m in rows}
    out = []
    problems = []
    for case in CASES:
        subj = case["expect"]["subject"]
        hits = [i for i, (t, _) in by_id.items() if subj.lower() in t.lower()]
        labels = case["labels"]
        if labels == "AUTO":
            labels = {i[:8]: "D" for i in hits}
            print(f"[{case['id']}] AUTO 라벨 — 사람이 확인할 것:")
            for i in hits:
                t, m = by_id[i]
                p = t.lower().find(subj.lower())
                print(f"   {i[:8]} {m.get('filename')} h={m.get('heading')} :: {t[max(0, p - 80):p + 120]!r}")
        full = {}
        for prefix, lab in labels.items():
            ids = [i for i in hits if i.startswith(prefix)]
            if len(ids) != 1:
                problems.append(f"{case['id']}: 라벨 {prefix} 이(가) 대상이 나오는 청크 {len(ids)}개와 맞는다")
                continue
            full[ids[0]] = lab
        missing = [i for i in hits if i not in full]
        if missing:
            problems.append(f"{case['id']}: 라벨 없는 청크 {[m[:8] for m in missing]}")
        for i, lab in full.items():
            alone = standalone(by_id[i][0], subj)
            if lab == "U" and alone:
                problems.append(f"{case['id']}: {i[:8]} 은 U 인데 대상이 단독으로 나온다")
            if lab != "U" and not alone:
                problems.append(f"{case['id']}: {i[:8]} 은 {lab} 인데 대상이 더 긴 식별자 안에만 있다")
        # 방해 후보 — 같은 파일의 청크 중 대상이 나오지 않고 지시와 가장 닮은 2개(앱의 하이브리드 검색이 섞는 뜻 후보를 흉내 낸다)
        # 큰 파일(수백 청크)은 리랭커 한 번에 다 못 보내므로 대상 청크 앞뒤 15개 안에서만 고른다.
        near = {(by_id[i][1].get("filename"), int(by_id[i][1].get("chunk_index", 0)) + d) for i in hits for d in range(-15, 16)}
        others = [i for i, (t, m) in by_id.items()
                  if (m.get("filename"), int(m.get("chunk_index", 0))) in near and i not in full]
        if others:
            scores = rerank(base, case["instruction"], [by_id[i][0][:2000] for i in others])
            for k in sorted(scores, key=scores.get, reverse=True)[:2]:
                full[others[k]] = "U"
                t, m = by_id[others[k]]
                print(f"[{case['id']}] 방해 후보 {others[k][:8]} ({scores[k]:.2f}) h={m.get('heading')} :: {t[:90]!r}")
        out.append(dict(id=case["id"], instruction=case["instruction"], expect=case["expect"],
                        pool=[dict(chunkId=i, label=lab) for i, lab in full.items()]))
    if problems:
        print("\n라벨 문제:")
        for p in problems:
            print("  -", p)
        sys.exit(1)
    gold = dict(
        note="6c 사전 평가 골든셋 (AGENT_MCP_DESIGN §10.12). 본문은 담지 않는다 — 하네스가 -Dedit-eval.db 의 memory.db 복사본에서 청크 id 로 읽는다. "
             "pool 은 지시 대상이 글자 그대로 나오는 청크 전부(앱의 전수 조회) + 같은 파일에서 지시와 가장 닮은 방해 후보 2개. "
             "label: D 설명함 · M 언급만 · U 무관 · A 애매(정밀도·재현율에서 뺀다). 생성: scripts/knowledge_edit_eval/build_gold.py",
        cases=out, interpretOnly=INTERPRET_ONLY)
    path = "src/test/resources/agent-eval/edit-gold.json"
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(gold, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print(f"\n{path} — 사례 {len(out)} · 청크 {sum(len(x['pool']) for x in out)} · 해석 전용 {len(INTERPRET_ONLY)}")


if __name__ == "__main__":
    main()
