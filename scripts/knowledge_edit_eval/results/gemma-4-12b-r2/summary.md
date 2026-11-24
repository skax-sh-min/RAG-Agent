# 6c 사전 평가 — gemma-4-12b-r2

모델 `.\gemma-4-12B-it-Q4_K_M.gguf` · 서버 http://127.0.0.1:1234/v1 · 리랭커 http://127.0.0.1:28095 · 생각 끔 · 온도 0.2

**해석** 29건: 유형 29 (100.0%) · 대상까지 28 (96.6%) · 거절 2 · 호출당 10962 ms

## ① 지시 해석

| 사례 | 기대 | 모델 | 유형 | 대상 | 비고 |
|---|---|---|---|---|---|
| dep-callBatchNow | deprecate callBatchNow | deprecate callBatchNow / 대안 callBatchAfterCommit | O | O |  |
| dep-reserveCallTimerService | deprecate reserveCallTimerService | deprecate reserveCallTimerService | O | O |  |
| dep-OldFileDailyCleaner | deprecate OldFileDailyCleaner | deprecate OldFileDailyCleaner | O | O |  |
| dep-CaseIgnoreHashMap | deprecate CaseIgnoreHashMap | deprecate CaseIgnoreHashMap / 대안 HashMap | O | O |  |
| dep-PaddableDataOutputStream | deprecate PaddableDataOutputStream | deprecate PaddableDataOutputStream | O | O |  |
| dep-FUAttcFileMng | deprecate FUAttcFileMng | deprecate FUAttcFileMng / 대안 FUFileMng | O | O |  |
| rm-isSingleJvmBatch | remove isSingleJvmBatch | remove isSingleJvmBatch | O | O |  |
| rm-waitForAllReceiveEnd | remove waitForAllReceiveEnd | remove waitForAllReceiveEnd | O | O |  |
| rm-readLines | remove readLines | remove readLines | O | O |  |
| rm-callAsyncServiceAfterCommit | remove callAsyncServiceAfterCommit | remove callAsyncServiceAfterCommit | O | O |  |
| rm-addErrorCountAmount | remove addErrorCountAmount | remove addErrorCountAmount | O | O |  |
| rm-closeQuietly | remove closeQuietly | remove closeQuietly | O | O |  |
| note-getJobExecutionId | add_note getJobExecutionId | add_note getJobExecutionId / 같은 Job 을 재실행하면 실행 ID 가 새로 발급된다 | O | O |  |
| note-CSVFileOption | add_note CSVFileOption | add_note CSVFileOption / 인코딩을 지정하지 않으면 서버 기본 인코딩이 쓰인다 | O | O |  |
| note-dbSelectMulti | add_note dbSelectMulti | add_note dbSelectMulti / 조회 건수가 많으면 메모리가 부족해질 수 있다 | O | O |  |
| note-copyFile | add_note copyFile | add_note copyFile / 대상 파일이 이미 있으면 덮어쓴다 | O | O |  |
| note-cancelCallTimerService | add_note cancelCallTimerService | add_note cancelCallTimerService / 취소에 실패하면 false 를 돌려주므로 반드시 결과를 확인해야 한다 | O | O |  |
| note-sendDummyReturnNow | add_note sendDummyReturnNow | add_note sendDummyReturnNow / 비동기 요청 거래에서만 호출해야 한다 | O | O |  |
| rev-dbSelectSingleLockRetry | revise dbSelectSingleLockRetry | revise dbSelectSingleLockRetry / 1초 | O | O |  |
| rev-sendErrorResponseOnFail | revise sendErrorResponseOnFail | revise sendDummyReturnNow 의 sendErrorResponseOnFail / false | O | X |  |
| rev-getSharedQueue | revise getSharedQueue | revise getSharedQueue / 30초 | O | O |  |
| rev-NEXCORE_RUNTIME_MODE | revise NEXCORE_RUNTIME_MODE | revise NEXCORE_RUNTIME_MODE / 'D' | O | O |  |
| rev-callAsyncServiceNow | revise callAsyncServiceNow | revise callAsyncServiceNow / 기동 거래가 에러로 끝나면 수동 거래를 실행하지 않는다 | O | O |  |
| rev-BaseRuntimeException | revise BaseRuntimeException | revise BaseRuntimeException / 프레임워크 내부 예외가 아니라 업무 예외의 부모 클래스 | O | O |  |
| int-replace-1 | replace Job Mind | replace Job Mind → 배치 스케줄러 | O | O |  |
| int-replace-2 | replace MCI | replace MCI → 채널통합 | O | O |  |
| int-replace-3 | replace NEXCORE 9.0 | replace NEXCORE 9.0 → NEXCORE 10 | O | O |  |
| int-unclear-1 | unclear  | unclear  | O | O | 수정 유형을 정하지 못했습니다 — 화면에서 유형과 대상을 직접 고르세요 |
| int-unclear-2 | unclear  | unclear  | O | O | 수정 유형을 정하지 못했습니다 — 화면에서 유형과 대상을 직접 고르세요 |

## ②③ 대상 판정과 가드

| 사례 | 정답 D | 판정 D | 맞음 | recall | precision | 대상 | 수정안 | 바꿀 곳 없음 | 버림(가드 등) |
|---|---|---|---|---|---|---|---|---|---|
| dep-callBatchNow | 2 | 3 | 2 | 1.00 | 0.67 | 6 | 6 | 0 | 0 |
| dep-reserveCallTimerService | 4 | 5 | 4 | 1.00 | 0.80 | 6 | 6 | 0 | 0 |
| dep-OldFileDailyCleaner | 1 | 1 | 1 | 1.00 | 1.00 | 1 | 1 | 0 | 0 |
| dep-CaseIgnoreHashMap | 1 | 1 | 1 | 1.00 | 1.00 | 3 | 3 | 0 | 0 |
| dep-PaddableDataOutputStream | 1 | 0 | 0 | 0.00 | - | 0 | 0 | 0 | 0 |
| dep-FUAttcFileMng | 4 | 4 | 4 | 1.00 | 1.00 | 5 | 5 | 0 | 0 |
| rm-isSingleJvmBatch | 1 | 4 | 1 | 1.00 | 0.25 | 4 | 3 | 0 | 1 |
| rm-waitForAllReceiveEnd | 3 | 6 | 2 | 0.67 | 0.33 | 6 | 4 | 0 | 2 |
| rm-readLines | 5 | 5 | 5 | 1.00 | 1.00 | 7 | 4 | 0 | 3 |
| rm-callAsyncServiceAfterCommit | 2 | 2 | 2 | 1.00 | 1.00 | 4 | 3 | 0 | 1 |
| rm-addErrorCountAmount | 3 | 7 | 3 | 1.00 | 0.43 | 8 | 4 | 0 | 4 |
| rm-closeQuietly | 6 | 6 | 6 | 1.00 | 1.00 | 7 | 5 | 0 | 2 |
| note-getJobExecutionId | 1 | 0 | 0 | 0.00 | - | 1 | 1 | 0 | 0 |
| note-CSVFileOption | 1 | 1 | 1 | 1.00 | 1.00 | 1 | 1 | 0 | 0 |
| note-dbSelectMulti | 2 | 4 | 2 | 1.00 | 0.50 | 1 | 1 | 0 | 0 |
| note-copyFile | 4 | 4 | 4 | 1.00 | 1.00 | 1 | 1 | 0 | 0 |
| note-cancelCallTimerService | 3 | 8 | 3 | 1.00 | 0.38 | 1 | 1 | 0 | 0 |
| note-sendDummyReturnNow | 4 | 5 | 4 | 1.00 | 0.80 | 1 | 1 | 0 | 0 |
| rev-dbSelectSingleLockRetry | 6 | 7 | 6 | 1.00 | 0.86 | 7 | 5 | 0 | 2 |
| rev-sendErrorResponseOnFail | 2 | 0 | 0 | 0.00 | - | 0 | 0 | 0 | 0 |
| rev-getSharedQueue | 2 | 8 | 2 | 1.00 | 0.25 | 9 | 3 | 0 | 6 |
| rev-NEXCORE_RUNTIME_MODE | 1 | 3 | 1 | 1.00 | 0.33 | 3 | 0 | 0 | 3 |
| rev-callAsyncServiceNow | 4 | 5 | 4 | 1.00 | 0.80 | 6 | 2 | 0 | 4 |
| rev-BaseRuntimeException | 1 | 0 | 0 | 0.00 | - | 0 | 0 | 0 | 0 |

**판정**: 정답 설명함 64 · 판정 설명함 89 · 맞음 58 → recall 0.91 · precision 0.65 (애매 라벨 제외)
**가드**: 대상 88 → 수정안 60 · 바꿀 곳 없음 0 · 버림 28 → 통과율(바꿀 곳 없음 제외) 0.68 · 다듬은 문장 대체 3
**지연**: 판정 210회 평균 16504 ms · 편집/다듬기 88회 평균 31576 ms

## 유형별

| 유형 | 해석 유형 | 해석 대상 | recall | precision | 가드 통과 | 자동 점검 통과 |
|---|---|---|---|---|---|---|
| deprecate | 6/6 | 6/6 | 0.92 | 0.86 | 1.00 | 1.00 |
| remove | 6/6 | 6/6 | 0.95 | 0.63 | 0.64 | 0.74 |
| add_note | 6/6 | 6/6 | 0.93 | 0.64 | 1.00 | 1.00 |
| revise | 6/6 | 5/6 | 0.81 | 0.57 | 0.40 | 0.50 |
| replace | 3/3 | 3/3 | - | - | - | - |
| unclear | 2/2 | 2/2 | - | - | - | - |

## 판정이 정답과 다른 청크

- dep-callBatchNow 7bb201a3 정답 M · 판정 describes (54. PUFwkTest — 온디맨드·대량입출력)
- dep-reserveCallTimerService 5a7adfa5 정답 M · 판정 describes (3.4 어플리케이션 호출)
- dep-PaddableDataOutputStream 4d0693ce 정답 D · 판정 mentions (40. 클래스: nexcore.framework.core.util.PaddableDataOutputStream)
- rm-isSingleJvmBatch 4fe326c5 정답 M · 판정 describes (유형1 – 반복 데이터 메모리 캐시 사용)
- rm-isSingleJvmBatch 51689137 정답 M · 판정 describes (유형1 – 반복 데이터 메모리 캐시 사용)
- rm-isSingleJvmBatch 040aa752 정답 M · 판정 describes ([BCA] 업무공통 휴일 체크)
- rm-waitForAllReceiveEnd fdeb839d 정답 M · 판정 describes (7.11.3.2 단일큐 예제 - Reader)
- rm-waitForAllReceiveEnd 301a3841 정답 M · 판정 describes (7.11.3.5 멀티큐 예제 - Reader)
- rm-waitForAllReceiveEnd 9668db79 정답 D · 판정 unrelated (7.11.3.7 큐 API 상세)
- rm-waitForAllReceiveEnd 827d5aff 정답 M · 판정 describes (20. BEDU5001 — 병렬 SharedQueue Reader (단일 큐))
- rm-waitForAllReceiveEnd 7b0a4747 정답 M · 판정 describes (22. BEDU5003 — 병렬 SharedQueue Reader (멀티 큐))
- rm-addErrorCountAmount 35951a81 정답 M · 판정 describes (3. 배치 라이프사이클 상세)
- rm-addErrorCountAmount 931b66f3 정답 M · 판정 describes (10. BEDU2002 — File → DB (고정길이 + 에러재처리))
- rm-addErrorCountAmount 3ef435b7 정답 M · 판정 describes (13. BEDU3002 — DB → DB (에러롤백·재처리))
- rm-addErrorCountAmount c8331bc6 정답 U · 판정 describes (15. BEDU3004 — DB → DB (1234건 강제 에러))
- note-getJobExecutionId 0bdaa7e5 정답 D · 판정 mentions (6.1.5 IBatchContext)
- note-dbSelectMulti 39e1aa81 정답 M · 판정 describes (16. BEDU4001 — 공유 FM + 배치 SELECT)
- note-dbSelectMulti e2f802db 정답 M · 판정 describes (18.5 핵심 코드)
- note-cancelCallTimerService ee90de9e 정답 M · 판정 describes ()
- note-cancelCallTimerService 6f9db640 정답 M · 판정 describes (온라인 서비스 개발)
- note-cancelCallTimerService 69980cfa 정답 M · 판정 describes (3.4.9.6 지연 비동기 연동거래)
- note-cancelCallTimerService b21d0272 정답 M · 판정 describes (3.9.8 비동기 응답 예제)
- note-cancelCallTimerService 46df9dd6 정답 U · 판정 describes (온라인 서비스 개발)
- note-sendDummyReturnNow b21d0272 정답 M · 판정 describes (3.9.8 비동기 응답 예제)
- rev-dbSelectSingleLockRetry debf28d7 정답 M · 판정 describes (9. DUNJFC_EVENT_LOG_00 — 이벤트 로그 DU)
- rev-sendErrorResponseOnFail 615b7ce8 정답 D · 판정 unrelated (3.9.4.6 sendDummyReturn)
- rev-sendErrorResponseOnFail d006ef6b 정답 D · 판정 mentions (3.6 메서드: `sendDummyReturnNow(nexcore.framework.core.data.IOnlineContext)`)
- rev-getSharedQueue de40151d 정답 M · 판정 describes (7.11.3.3 단일큐 예제 – Processor)
- rev-getSharedQueue ae4fd27e 정답 M · 판정 describes (7.11.3.6 멀티큐 예제 – Processor)
- rev-getSharedQueue 49dfe3c8 정답 M · 판정 describes (7.11.4.2 SharedMap 사용)
- rev-getSharedQueue 92833882 정답 M · 판정 describes (21. BEDU5002 — 병렬 SharedQueue Processor (단일 큐))
- rev-getSharedQueue 26e13ad2 정답 M · 판정 describes (23. BEDU5004 — 병렬 SharedQueue Processor (멀티 큐))
- rev-getSharedQueue ec8feba6 정답 U · 판정 describes (7.11.3.5 멀티큐 예제 - Reader)
- rev-NEXCORE_RUNTIME_MODE 7f36d38d 정답 M · 판정 describes (1.56 메서드: `getRecordSetMaxRecordLimit()`)
- rev-NEXCORE_RUNTIME_MODE 162804d2 정답 U · 판정 describes (1.42 메서드: `isLocalRuntime()`)
- rev-callAsyncServiceNow d60a5055 정답 M · 판정 describes (온라인 서비스 개발)
- rev-BaseRuntimeException b8d63f5b 정답 D · 판정 mentions (3.5 예외 및 메시지 처리)

## 대상이었지만 수정안이 되지 않은 청크

- rm-isSingleJvmBatch 04c3b3d8 [정답 D] 가드 위반 — 다른 작업과 구간이 겹친다 · 다른 작업과 구간이 겹친다
- rm-waitForAllReceiveEnd 2fae6c21 [정답 D] 가드 위반 — 대상(waitForAllReceiveEnd)과 관련된 구간이 아니다 — 인용에도 그 섹션 제목에도 대상이 없다 · 섹션 지우기의 인용이 제목 줄이 아니다
- rm-waitForAllReceiveEnd fdeb839d [정답 M] 가드 위반 — 대상(waitForAllReceiveEnd)과 관련된 구간이 아니다 — 인용에도 그 섹션 제목에도 대상이 없다 · 대상(waitForAllReceiveEnd)과 관련된 구간이 아니다 — 인용에도 그 섹션 제목에도 대상이 없다
- rm-readLines 1115e67f [정답 A] 가드 위반 — 인용을 원문에서 찾지 못했다: ### 26.44 메서드: `readLines(java.io.File,j…
- rm-readLines c950b511 [정답 A] 가드 위반 — 인용이 여러 곳에 있다: ### 32.49 메서드: `readLines(java.io.InputS… · 인용이 여러 곳에 있다: ### 32.49 메서드: `readLines(java.io.InputS…
- rm-readLines c42d7394 [정답 D] 가드 위반 — 다른 작업과 구간이 겹친다 · 다른 작업과 구간이 겹친다
- rm-callAsyncServiceAfterCommit f98c4db6 [정답 A] 가드 위반 — 인용이 여러 곳에 있다: ### 6.9 메서드: `callAsyncServiceAfterCommi… · 인용이 여러 곳에 있다: ### 6.9 메서드: `callAsyncServiceAfterCommi…
- rm-addErrorCountAmount 3ef435b7 [정답 M] 가드 위반 — 대상(addErrorCountAmount)과 관련된 구간이 아니다 — 인용에도 그 섹션 제목에도 대상이 없다 · 섹션 지우기의 인용이 제목 줄이 아니다
- rm-addErrorCountAmount 35951a81 [정답 M] 가드 위반 — 대상(addErrorCountAmount)과 관련된 구간이 아니다 — 인용에도 그 섹션 제목에도 대상이 없다
- rm-addErrorCountAmount a5f2e166 [정답 A] 가드 위반 — 끝 인용을 원문에서 찾지 못했다: ``` ``` · 섹션 지우기의 인용이 제목 줄이 아니다
- rm-addErrorCountAmount c8331bc6 [정답 U] 가드 위반 — 대상(addErrorCountAmount)과 관련된 구간이 아니다 — 인용에도 그 섹션 제목에도 대상이 없다
- rm-closeQuietly 49cbb655 [정답 D] 가드 위반 — 다른 작업과 구간이 겹친다 · 다른 작업과 구간이 겹친다
- rm-closeQuietly a84d0ab4 [정답 D] 가드 위반 — 다른 작업과 구간이 겹친다 · 다른 작업과 구간이 겹친다 · 인용이 여러 곳에 있다: ### 32.9 메서드: `closeQuietly(java.nio.cha… · 인용이 여러 곳에 있다: ### 32.9 메서드: `closeQuietly(java.nio.cha…
- rev-dbSelectSingleLockRetry debf28d7 [정답 M] 가드 위반 — 대상(dbSelectSingleLockRetry)과 관련된 구간이 아니다 — 인용에도 그 섹션 제목에도 대상이 없다
- rev-dbSelectSingleLockRetry aadffed5 [정답 D] 가드 위반 — 지시문에 없는 숫자를 넣었다: 1000
- rev-getSharedQueue 9668db79 [정답 D] 가드 위반 — 대상(getSharedQueue)과 관련된 구간이 아니다 — 인용에도 그 섹션 제목에도 대상이 없다
- rev-getSharedQueue de40151d [정답 M] 가드 위반 — 대상(getSharedQueue)과 관련된 구간이 아니다 — 인용에도 그 섹션 제목에도 대상이 없다
- rev-getSharedQueue ae4fd27e [정답 M] 가드 위반 — 대상(getSharedQueue)과 관련된 구간이 아니다 — 인용에도 그 섹션 제목에도 대상이 없다
- rev-getSharedQueue 26e13ad2 [정답 M] 가드 위반 — 대상(getSharedQueue)과 관련된 구간이 아니다 — 인용에도 그 섹션 제목에도 대상이 없다
- rev-getSharedQueue ec8feba6 [정답 U] 가드 위반 — 대상(getSharedQueue)과 관련된 구간이 아니다 — 인용에도 그 섹션 제목에도 대상이 없다
- rev-getSharedQueue 13f0dd3d [정답 A] 가드 위반 — 대상(getSharedQueue)과 관련된 구간이 아니다 — 인용에도 그 섹션 제목에도 대상이 없다
- rev-NEXCORE_RUNTIME_MODE 7f36d38d [정답 M] 가드 위반 — 대상(NEXCORE_RUNTIME_MODE)과 관련된 구간이 아니다 — 인용에도 그 섹션 제목에도 대상이 없다
- rev-NEXCORE_RUNTIME_MODE 04c3b3d8 [정답 D] 가드 위반 — 대상(NEXCORE_RUNTIME_MODE)과 관련된 구간이 아니다 — 인용에도 그 섹션 제목에도 대상이 없다
- rev-NEXCORE_RUNTIME_MODE 162804d2 [정답 U] 가드 위반 — 대상(NEXCORE_RUNTIME_MODE)과 관련된 구간이 아니다 — 인용에도 그 섹션 제목에도 대상이 없다
- rev-callAsyncServiceNow 9549695b [정답 D] 가드 위반 — 대상(callAsyncServiceNow)과 관련된 구간이 아니다 — 인용에도 그 섹션 제목에도 대상이 없다
- rev-callAsyncServiceNow 86fe0e0c [정답 D] 가드 위반 — 대상(callAsyncServiceNow)과 관련된 구간이 아니다 — 인용에도 그 섹션 제목에도 대상이 없다
- rev-callAsyncServiceNow 3f0d9518 [정답 D] 가드 위반 — 대상(callAsyncServiceNow)과 관련된 구간이 아니다 — 인용에도 그 섹션 제목에도 대상이 없다
- rev-callAsyncServiceNow d60a5055 [정답 M] 가드 위반 — 대상(callAsyncServiceNow)과 관련된 구간이 아니다 — 인용에도 그 섹션 제목에도 대상이 없다
