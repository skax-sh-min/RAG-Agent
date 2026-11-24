# 6c 사전 평가 — gemma-4-e4b-dep2

모델 `.\gemma-4-E4B-it-Q4_K_M.gguf` · 서버 http://127.0.0.1:1234/v1 · 리랭커 http://127.0.0.1:28095 · 생각 끔 · 온도 0.2

**해석** 6건: 유형 6 (100.0%) · 대상까지 5 (83.3%) · 거절 0 · 호출당 14661 ms

## ① 지시 해석

| 사례 | 기대 | 모델 | 유형 | 대상 | 비고 |
|---|---|---|---|---|---|
| dep-callBatchNow | deprecate callBatchNow | deprecate callBatchNow / 대안 callBatchAfterCommit | O | O |  |
| dep-reserveCallTimerService | deprecate reserveCallTimerService | deprecate reserveCallTimerService | O | O |  |
| dep-OldFileDailyCleaner | deprecate OldFileDailyCleaner | deprecate OldFileDailyCleaner 클래스 | O | X |  |
| dep-CaseIgnoreHashMap | deprecate CaseIgnoreHashMap | deprecate CaseIgnoreHashMap / 대안 HashMap | O | O |  |
| dep-PaddableDataOutputStream | deprecate PaddableDataOutputStream | deprecate PaddableDataOutputStream | O | O |  |
| dep-FUAttcFileMng | deprecate FUAttcFileMng | deprecate FUAttcFileMng / 대안 FUFileMng | O | O |  |

## ②③ 대상 판정과 가드

| 사례 | 정답 D | 판정 D | 맞음 | recall | precision | 대상 | 수정안 | 바꿀 곳 없음 | 버림(가드 등) |
|---|---|---|---|---|---|---|---|---|---|
| dep-callBatchNow | 2 | 3 | 2 | 1.00 | 0.67 | 5 | 5 | 0 | 0 |
| dep-reserveCallTimerService | 4 | 3 | 3 | 0.75 | 1.00 | 4 | 4 | 0 | 0 |
| dep-OldFileDailyCleaner | 1 | 1 | 1 | 1.00 | 1.00 | 7 | 7 | 0 | 0 |
| dep-CaseIgnoreHashMap | 1 | 1 | 1 | 1.00 | 1.00 | 6 | 6 | 0 | 0 |
| dep-PaddableDataOutputStream | 1 | 1 | 1 | 1.00 | 1.00 | 5 | 5 | 0 | 0 |
| dep-FUAttcFileMng | 4 | 4 | 4 | 1.00 | 1.00 | 4 | 4 | 0 | 0 |

**판정**: 정답 설명함 13 · 판정 설명함 13 · 맞음 12 → recall 0.92 · precision 0.92 (애매 라벨 제외)
**가드**: 대상 31 → 수정안 31 · 바꿀 곳 없음 0 · 버림 0 → 통과율(바꿀 곳 없음 제외) 1.00 · 다듬은 문장 대체 2
**지연**: 판정 54회 평균 25433 ms · 편집/다듬기 31회 평균 14267 ms

## 유형별

| 유형 | 해석 유형 | 해석 대상 | recall | precision | 가드 통과 | 자동 점검 통과 |
|---|---|---|---|---|---|---|
| deprecate | 6/6 | 5/6 | 0.92 | 0.92 | 1.00 | 1.00 |

## 판정이 정답과 다른 청크

- dep-callBatchNow 7bb201a3 정답 M · 판정 describes (54. PUFwkTest — 온디맨드·대량입출력)
- dep-reserveCallTimerService 46df9dd6 정답 D · 판정 mentions (온라인 서비스 개발)

## 대상이었지만 수정안이 되지 않은 청크

