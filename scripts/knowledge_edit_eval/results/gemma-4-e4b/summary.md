# 6c 사전 평가 — gemma-4-e4b

모델 `.\gemma-4-E4B-it-Q4_K_M.gguf` · 서버 http://127.0.0.1:1234/v1 · 리랭커 http://127.0.0.1:28095 · 생각 끔 · 온도 0.2

**해석** 29건: 유형 28 (96.6%) · 대상까지 25 (86.2%) · 거절 3 · 호출당 1457 ms

## ① 지시 해석

| 사례 | 기대 | 모델 | 유형 | 대상 | 비고 |
|---|---|---|---|---|---|
| dep-callBatchNow | deprecate callBatchNow | deprecate callBatchNow / 대안 callBatchAfterCommit | O | O |  |
| dep-reserveCallTimerService | deprecate reserveCallTimerService | deprecate reserveCallTimerService | O | O |  |
| dep-OldFileDailyCleaner | deprecate OldFileDailyCleaner | deprecate OldFileDailyCleaner 클래스 | O | X |  |
| dep-CaseIgnoreHashMap | deprecate CaseIgnoreHashMap | deprecate CaseIgnoreHashMap / 대안 HashMap | O | O |  |
| dep-PaddableDataOutputStream | deprecate PaddableDataOutputStream | deprecate PaddableDataOutputStream | O | O |  |
| dep-FUAttcFileMng | deprecate FUAttcFileMng | deprecate FUAttcFileMng / 대안 FUFileMng | O | O |  |
| rm-isSingleJvmBatch | remove isSingleJvmBatch | remove isSingleJvmBatch | O | O |  |
| rm-waitForAllReceiveEnd | remove waitForAllReceiveEnd | remove waitForAllReceiveEnd | O | O |  |
| rm-readLines | remove readLines | remove readLines 메서드 | O | X |  |
| rm-callAsyncServiceAfterCommit | remove callAsyncServiceAfterCommit | remove callAsyncServiceAfterCommit | O | O |  |
| rm-addErrorCountAmount | remove addErrorCountAmount | remove addErrorCountAmount | O | O |  |
| rm-closeQuietly | remove closeQuietly | remove closeQuietly | O | O |  |
| note-getJobExecutionId | add_note getJobExecutionId | add_note getJobExecutionId / 같은 Job 을 재실행하면 실행 ID 가 새로 발급된다 | O | O |  |
| note-CSVFileOption | add_note CSVFileOption | add_note CSVFileOption / '인코딩을 지정하지 않으면 서버 기본 인코딩이 쓰인다 | O | O |  |
| note-dbSelectMulti | add_note dbSelectMulti | add_note dbSelectMulti / 조회 건수가 많으면 메모리가 부족해질 수 있다 | O | O |  |
| note-copyFile | add_note copyFile | add_note copyFile / 대상 파일이 이미 있으면 덮어쓴다 | O | O |  |
| note-cancelCallTimerService | add_note cancelCallTimerService | add_note cancelCallTimerService / 취소에 실패하면 false 를 돌려주므로 반드시 결과를 확인해야 한다 | O | O |  |
| note-sendDummyReturnNow | add_note sendDummyReturnNow | add_note sendDummyReturnNow / 비동기 요청 거래에서만 호출해야 한다 | O | O |  |
| rev-dbSelectSingleLockRetry | revise dbSelectSingleLockRetry | revise dbSelectSingleLockRetry / 1초 | O | O |  |
| rev-sendErrorResponseOnFail | revise sendErrorResponseOnFail | revise sendDummyReturnNow 의 sendErrorResponseOnFail / false | O | X |  |
| rev-getSharedQueue | revise getSharedQueue | revise getSharedQueue / 30초 | O | O |  |
| rev-NEXCORE_RUNTIME_MODE | revise NEXCORE_RUNTIME_MODE | revise NEXCORE_RUNTIME_MODE / 'D' | O | O |  |
| rev-callAsyncServiceNow | revise callAsyncServiceNow | revise callAsyncServiceNow / 기동 거래가 에러로 끝나면 수동 거래를 실행하지 않는다 | O | O |  |
| rev-BaseRuntimeException | revise BaseRuntimeException | revise BaseRuntimeException / 업무 예외의 부모 클래스다 | O | O |  |
| int-replace-1 | replace Job Mind | replace Job Mind / 대안 배치 스케줄러 | X | X | 바꿀 말과 새 말이 필요합니다 — 화면에서 유형과 대상을 직접 고르세요 |
| int-replace-2 | replace MCI | replace MCI → 채널통합 | O | O |  |
| int-replace-3 | replace NEXCORE 9.0 | replace NEXCORE 9.0 → NEXCORE 10 | O | O |  |
| int-unclear-1 | unclear  | unclear  | O | O | 수정 유형을 정하지 못했습니다 — 화면에서 유형과 대상을 직접 고르세요 |
| int-unclear-2 | unclear  | unclear  | O | O | 수정 유형을 정하지 못했습니다 — 화면에서 유형과 대상을 직접 고르세요 |

## ②③ 대상 판정과 가드

| 사례 | 정답 D | 판정 D | 맞음 | recall | precision | 대상 | 수정안 | 바꿀 곳 없음 | 버림(가드 등) |
|---|---|---|---|---|---|---|---|---|---|
| dep-callBatchNow | 2 | 3 | 2 | 1.00 | 0.67 | 5 | 5 | 0 | 0 |
| dep-reserveCallTimerService | 4 | 3 | 3 | 0.75 | 1.00 | 4 | 4 | 0 | 0 |
| dep-OldFileDailyCleaner | 1 | 1 | 1 | 1.00 | 1.00 | 7 | 7 | 0 | 0 |
| dep-CaseIgnoreHashMap | 1 | 1 | 1 | 1.00 | 1.00 | 6 | 6 | 0 | 0 |
| dep-PaddableDataOutputStream | 1 | 2 | 1 | 1.00 | 0.50 | 7 | 7 | 0 | 0 |
| dep-FUAttcFileMng | 4 | 4 | 4 | 1.00 | 1.00 | 4 | 4 | 0 | 0 |
| rm-isSingleJvmBatch | 1 | 7 | 1 | 1.00 | 0.14 | 7 | 5 | 0 | 2 |
| rm-waitForAllReceiveEnd | 3 | 3 | 1 | 0.33 | 0.33 | 3 | 2 | 0 | 1 |
| rm-readLines | 5 | 6 | 5 | 1.00 | 0.83 | 8 | 3 | 0 | 5 |
| rm-callAsyncServiceAfterCommit | 2 | 2 | 2 | 1.00 | 1.00 | 2 | 2 | 0 | 0 |
| rm-addErrorCountAmount | 3 | 3 | 1 | 0.33 | 0.33 | 4 | 3 | 0 | 1 |
| rm-closeQuietly | 6 | 6 | 6 | 1.00 | 1.00 | 6 | 4 | 0 | 2 |
| note-getJobExecutionId | 1 | 2 | 0 | 0.00 | 0.00 | 1 | 1 | 0 | 0 |
| note-CSVFileOption | 1 | 2 | 1 | 1.00 | 0.50 | 1 | 1 | 0 | 0 |
| note-dbSelectMulti | 2 | 3 | 1 | 0.50 | 0.33 | 1 | 1 | 0 | 0 |
| note-copyFile | 4 | 6 | 4 | 1.00 | 0.67 | 1 | 1 | 0 | 0 |
| note-cancelCallTimerService | 3 | 6 | 3 | 1.00 | 0.50 | 1 | 1 | 0 | 0 |
| note-sendDummyReturnNow | 4 | 3 | 3 | 0.75 | 1.00 | 1 | 1 | 0 | 0 |
| rev-dbSelectSingleLockRetry | 6 | 6 | 6 | 1.00 | 1.00 | 6 | 5 | 0 | 1 |
| rev-sendErrorResponseOnFail | 2 | 3 | 2 | 1.00 | 0.67 | 3 | 2 | 0 | 1 |
| rev-getSharedQueue | 2 | 4 | 1 | 0.50 | 0.25 | 5 | 4 | 0 | 1 |
| rev-NEXCORE_RUNTIME_MODE | 1 | 0 | 0 | 0.00 | - | 0 | 0 | 0 | 0 |
| rev-callAsyncServiceNow | 4 | 4 | 4 | 1.00 | 1.00 | 4 | 0 | 0 | 4 |
| rev-BaseRuntimeException | 1 | 1 | 0 | 0.00 | 0.00 | 1 | 0 | 0 | 1 |

**판정**: 정답 설명함 64 · 판정 설명함 81 · 맞음 53 → recall 0.83 · precision 0.65 (애매 라벨 제외)
**가드**: 대상 88 → 수정안 69 · 바꿀 곳 없음 0 · 버림 19 → 통과율(바꿀 곳 없음 제외) 0.78 · 다듬은 문장 대체 6
**지연**: 판정 210회 평균 2950 ms · 편집/다듬기 88회 평균 6002 ms

## 유형별

| 유형 | 해석 유형 | 해석 대상 | recall | precision | 가드 통과 | 자동 점검 통과 |
|---|---|---|---|---|---|---|
| deprecate | 6/6 | 5/6 | 0.92 | 0.86 | 1.00 | 1.00 |
| remove | 6/6 | 5/6 | 0.80 | 0.59 | 0.63 | 0.53 |
| add_note | 6/6 | 6/6 | 0.80 | 0.55 | 1.00 | 1.00 |
| revise | 6/6 | 5/6 | 0.81 | 0.72 | 0.58 | 0.73 |
| replace | 2/3 | 2/3 | - | - | - | - |
| unclear | 2/2 | 2/2 | - | - | - | - |

## 판정이 정답과 다른 청크

- dep-callBatchNow 7bb201a3 정답 M · 판정 describes (54. PUFwkTest — 온디맨드·대량입출력)
- dep-reserveCallTimerService 46df9dd6 정답 D · 판정 mentions (온라인 서비스 개발)
- dep-PaddableDataOutputStream 3d09d635 정답 M · 판정 describes (7.6 메서드: `writeMapToOut(nexcore.framework.batch.util.RecordMapGetter)`)
- rm-isSingleJvmBatch 97959659 정답 M · 판정 describes (유형1 – 반복 데이터 메모리 캐시 이용)
- rm-isSingleJvmBatch 1332a6bf 정답 M · 판정 describes (유형1 – 반복 데이터 메모리 캐시 사용)
- rm-isSingleJvmBatch 4fe326c5 정답 M · 판정 describes (유형1 – 반복 데이터 메모리 캐시 사용)
- rm-isSingleJvmBatch 51689137 정답 M · 판정 describes (유형1 – 반복 데이터 메모리 캐시 사용)
- rm-isSingleJvmBatch ec2578fb 정답 M · 판정 describes (유형2 – 건건이 select 하지 않고 전체를 bulk 로 조회)
- rm-isSingleJvmBatch 040aa752 정답 M · 판정 describes ([BCA] 업무공통 휴일 체크)
- rm-waitForAllReceiveEnd fdeb839d 정답 M · 판정 describes (7.11.3.2 단일큐 예제 - Reader)
- rm-waitForAllReceiveEnd 301a3841 정답 M · 판정 describes (7.11.3.5 멀티큐 예제 - Reader)
- rm-waitForAllReceiveEnd 9668db79 정답 D · 판정 mentions (7.11.3.7 큐 API 상세)
- rm-waitForAllReceiveEnd 2fae6c21 정답 D · 판정 mentions (4. AutoCommitRecordHandler / AbsRecordHandler / SharedQueue 옵션)
- rm-readLines 8854b797 정답 U · 판정 describes (26.64 메서드: `writeLines(java.io.File,java.lang.String,java.util.Collection,boolean)`)
- rm-addErrorCountAmount 6e858da9 정답 D · 판정 mentions (7.9.3.1.2 IBatchContext 의 메소드)
- rm-addErrorCountAmount 931b66f3 정답 M · 판정 describes (10. BEDU2002 — File → DB (고정길이 + 에러재처리))
- rm-addErrorCountAmount 5b1ab2f4 정답 D · 판정 mentions (12.46 메서드: `addErrorCountAmount(long, double)`)
- rm-addErrorCountAmount c8331bc6 정답 U · 판정 describes (15. BEDU3004 — DB → DB (1234건 강제 에러))
- note-getJobExecutionId 0bdaa7e5 정답 D · 판정 mentions (6.1.5 IBatchContext)
- note-getJobExecutionId 8c92b24f 정답 M · 판정 describes (7.11.3.2 단일큐 예제 - Reader)
- note-getJobExecutionId ae4fd27e 정답 M · 판정 describes (7.11.3.6 멀티큐 예제 – Processor)
- note-CSVFileOption 490526e7 정답 M · 판정 describes (9.26 메서드: `dbSelectToFile(java.lang.String,java.lang.Object,nexcore.framework.biz.online.SQLIO.CSVFileOption,nexcore.framework.core.data.IOnlineContext)`)
- note-dbSelectMulti c70d5551 정답 D · 판정 mentions (7.6.6 소량 Select 처리)
- note-dbSelectMulti 90fdf768 정답 M · 판정 describes (6.4 처리 흐름)
- note-dbSelectMulti 39e1aa81 정답 M · 판정 describes (16. BEDU4001 — 공유 FM + 배치 SELECT)
- note-copyFile 41560c5b 정답 U · 판정 describes (26.34 메서드: `copyToFile(java.io.InputStream,java.io.File)`)
- note-copyFile 48fdeab4 정답 U · 판정 describes (26.31 메서드: `copyURLToFile(java.net.URL,java.io.File)`)
- note-cancelCallTimerService 6f9db640 정답 M · 판정 describes (온라인 서비스 개발)
- note-cancelCallTimerService 69980cfa 정답 M · 판정 describes (3.4.9.6 지연 비동기 연동거래)
- note-cancelCallTimerService b21d0272 정답 M · 판정 describes (3.9.8 비동기 응답 예제)
- note-sendDummyReturnNow d006ef6b 정답 D · 판정 mentions (3.6 메서드: `sendDummyReturnNow(nexcore.framework.core.data.IOnlineContext)`)
- rev-sendErrorResponseOnFail cddc3d6d 정답 U · 판정 describes (3.9.7.1 송신 채널 더미 응답 (sendDummyReturnNow))
- rev-getSharedQueue de40151d 정답 M · 판정 describes (7.11.3.3 단일큐 예제 – Processor)
- rev-getSharedQueue 2fae6c21 정답 D · 판정 mentions (4. AutoCommitRecordHandler / AbsRecordHandler / SharedQueue 옵션)
- rev-getSharedQueue 92833882 정답 M · 판정 describes (21. BEDU5002 — 병렬 SharedQueue Processor (단일 큐))
- rev-getSharedQueue 26e13ad2 정답 M · 판정 describes (23. BEDU5004 — 병렬 SharedQueue Processor (멀티 큐))
- rev-NEXCORE_RUNTIME_MODE 04c3b3d8 정답 D · 판정 mentions (1.4 메서드: `getRuntimeMode()`)
- rev-BaseRuntimeException b8d63f5b 정답 D · 판정 mentions (3.5 예외 및 메시지 처리)
- rev-BaseRuntimeException a4d8981d 정답 M · 판정 describes (7. 클래스: nexcore.framework.batch.BatchFwkException)

## 대상이었지만 수정안이 되지 않은 청크

- rm-isSingleJvmBatch ec2578fb [정답 M] 가드 위반 — 끝 인용이 여러 곳에 있다: } · 인용을 원문에서 찾지 못했다: if (BaseUtils.isSingleJvmBatch()) { (필요한…
- rm-isSingleJvmBatch 97959659 [정답 M] 가드 위반 — 끝 인용이 여러 곳에 있다: } · 인용을 원문에서 찾지 못했다: if (BaseUtils.isSingleJvmBatch()) { (필요한…
- rm-waitForAllReceiveEnd ee166be2 [정답 D] 가드 위반 — 끝 인용이 여러 곳에 있다: receiver들이 queue에 것을 모두 빼갈 때까지 wait 함. 무… · 인용을 원문에서 찾지 못했다: ### 5.6 메서드: `waitForAllReceiveEnd()` (필… · 끝 인용이 여러 곳에 있다: receiver들이 queue에 것을 모두 빼갈 때까지 wait 함. 무… · 인용을 원문에서 찾지 못했다: ### 5.7 메서드: `waitForAllReceiveEnd(long)…
- rm-readLines 071bcadd [정답 D] 가드 위반 — 끝 인용을 원문에서 찾지 못했다: #### 26.46.5 예외 ### 26.46 메서드: `readLine…
- rm-readLines 1115e67f [정답 A] 가드 위반 — 인용이 여러 곳에 있다: ### 26.44 메서드: `readLines(java.io.File,j…
- rm-readLines f1af649c [정답 D] 가드 위반 — 인용이 여러 곳에 있다: `readLines(java.io.File,java.lang.String… · 인용이 여러 곳에 있다: ### 26.45 메서드: `readLines(java.io.File,j…
- rm-readLines c950b511 [정답 A] 가드 위반 — 인용이 여러 곳에 있다: ### 32.49 메서드: `readLines(java.io.InputS… · 인용을 원문에서 찾지 못했다: ### 32.49 메서드: `readLines(java.io.InputS…
- rm-readLines 8854b797 [정답 U] 가드 위반 — 대상(readLines)과 관련된 구간이 아니다 — 인용에도 그 섹션 제목에도 대상이 없다 · 대상(readLines)과 관련된 구간이 아니다 — 인용에도 그 섹션 제목에도 대상이 없다
- rm-addErrorCountAmount c8331bc6 [정답 U] 가드 위반 — 대상(addErrorCountAmount)과 관련된 구간이 아니다 — 인용에도 그 섹션 제목에도 대상이 없다 · 대상(addErrorCountAmount)과 관련된 구간이 아니다 — 인용에도 그 섹션 제목에도 대상이 없다
- rm-closeQuietly a84d0ab4 [정답 D] 가드 위반 — 인용을 원문에서 찾지 못했다: `public static void closeQuietly ( Socke… · 인용을 원문에서 찾지 못했다: 32.8 메서드: `closeQuietly(java.net.Socket)…
- rm-closeQuietly cc2aa6a1 [정답 D] 가드 위반 — 인용을 원문에서 찾지 못했다: `public static void closeQuietly ( Outpu… · 인용을 원문에서 찾지 못했다: 32.5 메서드: `closeQuietly(java.io.OutputSt…
- rev-dbSelectSingleLockRetry 38628c35 [정답 D] 가드 위반 — 변경 없음 — 결과가 원문과 같다
- rev-sendErrorResponseOnFail cddc3d6d [정답 U] 가드 위반 — 대상(sendErrorResponseOnFail)과 관련된 구간이 아니다 — 인용에도 그 섹션 제목에도 대상이 없다 · 대상(sendErrorResponseOnFail)과 관련된 구간이 아니다 — 인용에도 그 섹션 제목에도 대상이 없다
- rev-getSharedQueue 9668db79 [정답 D] 가드 위반 — 대상(getSharedQueue)과 관련된 구간이 아니다 — 인용에도 그 섹션 제목에도 대상이 없다
- rev-callAsyncServiceNow 9549695b [정답 D] 가드 위반 — 인용을 원문에서 찾지 못했다: callAsyncServiceNow 는 기동 거래가 에러로 끝나면 수동 …
- rev-callAsyncServiceNow 86fe0e0c [정답 D] 가드 위반 — 대상(callAsyncServiceNow)과 관련된 구간이 아니다 — 인용에도 그 섹션 제목에도 대상이 없다
- rev-callAsyncServiceNow 3f0d9518 [정답 D] 가드 위반 — 인용을 원문에서 찾지 못했다: callAsyncServiceNow 는 기동 거래가 에러로 끝나면 수동 …
- rev-callAsyncServiceNow f98c4db6 [정답 D] 가드 위반 — 인용을 원문에서 찾지 못했다: "callAsyncServiceNow 는 기동 거래가 에러로 끝나면 수동…
- rev-BaseRuntimeException a4d8981d [정답 M] 가드 위반 — 대상(BaseRuntimeException)과 관련된 구간이 아니다 — 인용에도 그 섹션 제목에도 대상이 없다
