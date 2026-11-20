# 6c 사전 평가 — gemma-4-e2b

모델 `.\gemma-4-E2B-it-Q4_K_M.gguf` · 서버 http://127.0.0.1:1234/v1 · 리랭커 http://127.0.0.1:28095 · 생각 끔 · 온도 0.2

**해석** 29건: 유형 22 (75.9%) · 대상까지 19 (65.5%) · 거절 9 · 호출당 888 ms

## ① 지시 해석

| 사례 | 기대 | 모델 | 유형 | 대상 | 비고 |
|---|---|---|---|---|---|
| dep-callBatchNow | deprecate callBatchNow | deprecate callBatchNow / 대안 callBatchAfterCommit → callBatchAfterCommit | O | O |  |
| dep-reserveCallTimerService | deprecate reserveCallTimerService | deprecate reserveCallTimerService | O | O |  |
| dep-OldFileDailyCleaner | deprecate OldFileDailyCleaner | deprecate OldFileDailyCleaner 클래스 | O | X |  |
| dep-CaseIgnoreHashMap | deprecate CaseIgnoreHashMap | replace CaseIgnoreHashMap / 대안 HashMap | X | X | 바꿀 말과 새 말이 필요합니다 — 화면에서 유형과 대상을 직접 고르세요 |
| dep-PaddableDataOutputStream | deprecate PaddableDataOutputStream | deprecate PaddableDataOutputStream | O | O |  |
| dep-FUAttcFileMng | deprecate FUAttcFileMng | deprecate FUAttcFileMng / 대안 FUFileMng | O | O |  |
| rm-isSingleJvmBatch | remove isSingleJvmBatch | remove isSingleJvmBatch | O | O |  |
| rm-waitForAllReceiveEnd | remove waitForAllReceiveEnd | remove waitForAllReceiveEnd | O | O |  |
| rm-readLines | remove readLines | remove readLines 메서드 | O | X |  |
| rm-callAsyncServiceAfterCommit | remove callAsyncServiceAfterCommit | remove callAsyncServiceAfterCommit | O | O |  |
| rm-addErrorCountAmount | remove addErrorCountAmount | remove addErrorCountAmount | O | O |  |
| rm-closeQuietly | remove closeQuietly | remove closeQuietly | O | O |  |
| note-getJobExecutionId | add_note getJobExecutionId | add_note getJobExecutionId / 같은 Job 을 재실행하면 실행 ID 가 새로 발급된다 | O | O |  |
| note-CSVFileOption | add_note CSVFileOption | add_note CSVFileOption / 인코딩을 지정하지 않으면 서버 기본 인코딩이 쓰인다 | O | O |  |
| note-dbSelectMulti | add_note dbSelectMulti | add_note dbSelectMulti / 조회 건수가 많으면 메모리가 부족해질 수 있다 | O | O |  |
| note-copyFile | add_note copyFile | add_note copyFile / 대상 파일이 이미 있으면 덮어쓴다 | O | O |  |
| note-cancelCallTimerService | add_note cancelCallTimerService | add_note cancelCallTimerService / 취소에 실패하면 false 를 돌려주므로 반드시 결과를 확인해야 한다 | O | O |  |
| note-sendDummyReturnNow | add_note sendDummyReturnNow | add_note sendDummyReturnNow / 비동기 요청 거래에서만 호출해야 한다 | O | O |  |
| rev-dbSelectSingleLockRetry | revise dbSelectSingleLockRetry | revise dbSelectSingleLockRetry의 재시도 간격 / 1초 / 대안 1초 | X | X | 해석한 값이 지시문에 없습니다: dbSelectSingleLockRetry의 재시도 간격 — 화면에서 유형과 대상을 직접 고르세요 |
| rev-sendErrorResponseOnFail | revise sendErrorResponseOnFail | revise sendDummyReturnNow / false / 대안 false | O | O |  |
| rev-getSharedQueue | revise getSharedQueue | revise getSharedQueue 의 Timeout 기본값 / 30초 / 대안 30초 | O | X |  |
| rev-NEXCORE_RUNTIME_MODE | revise NEXCORE_RUNTIME_MODE | revise NEXCORE_RUNTIME_MODE / 'D' / 대안 D | O | O |  |
| rev-callAsyncServiceNow | revise callAsyncServiceNow | unclear callAsyncServiceNow | X | X | 수정 유형을 정하지 못했습니다 — 화면에서 유형과 대상을 직접 고르세요 |
| rev-BaseRuntimeException | revise BaseRuntimeException | unclear BaseRuntimeException | X | X | 수정 유형을 정하지 못했습니다 — 화면에서 유형과 대상을 직접 고르세요 |
| int-replace-1 | replace Job Mind | replace Job Mind / 대안 배치 스케줄러 | X | X | 바꿀 말과 새 말이 필요합니다 — 화면에서 유형과 대상을 직접 고르세요 |
| int-replace-2 | replace MCI | replace MCI / 대안 채널통합 | X | X | 바꿀 말과 새 말이 필요합니다 — 화면에서 유형과 대상을 직접 고르세요 |
| int-replace-3 | replace NEXCORE 9.0 | replace NEXCORE 9.0 / 대안 NEXCORE 10 | X | X | 바꿀 말과 새 말이 필요합니다 — 화면에서 유형과 대상을 직접 고르세요 |
| int-unclear-1 | unclear  | unclear . | O | O | 수정 유형을 정하지 못했습니다 — 화면에서 유형과 대상을 직접 고르세요 |
| int-unclear-2 | unclear  | unclear . | O | O | 수정 유형을 정하지 못했습니다 — 화면에서 유형과 대상을 직접 고르세요 |

## ②③ 대상 판정과 가드

| 사례 | 정답 D | 판정 D | 맞음 | recall | precision | 대상 | 수정안 | 바꿀 곳 없음 | 버림(가드 등) |
|---|---|---|---|---|---|---|---|---|---|
| dep-callBatchNow | 2 | 1 | 1 | 0.50 | 1.00 | 1 | 1 | 0 | 0 |
| dep-reserveCallTimerService | 4 | 0 | 0 | 0.00 | - | 0 | 0 | 0 | 0 |
| dep-OldFileDailyCleaner | 1 | 0 | 0 | 0.00 | - | 3 | 3 | 0 | 0 |
| dep-CaseIgnoreHashMap | 1 | 1 | 1 | 1.00 | 1.00 | 1 | 1 | 0 | 0 |
| dep-PaddableDataOutputStream | 1 | 1 | 1 | 1.00 | 1.00 | 2 | 2 | 0 | 0 |
| dep-FUAttcFileMng | 4 | 0 | 0 | 0.00 | - | 0 | 0 | 0 | 0 |
| rm-isSingleJvmBatch | 1 | 0 | 0 | 0.00 | - | 0 | 0 | 0 | 0 |
| rm-waitForAllReceiveEnd | 3 | 1 | 1 | 0.33 | 1.00 | 1 | 0 | 0 | 1 |
| rm-readLines | 5 | 7 | 5 | 1.00 | 0.71 | 7 | 0 | 0 | 7 |
| rm-callAsyncServiceAfterCommit | 2 | 1 | 1 | 0.50 | 1.00 | 2 | 0 | 0 | 2 |
| rm-addErrorCountAmount | 3 | 2 | 2 | 0.67 | 1.00 | 2 | 0 | 0 | 2 |
| rm-closeQuietly | 6 | 5 | 5 | 0.83 | 1.00 | 5 | 0 | 0 | 5 |
| note-getJobExecutionId | 1 | 1 | 0 | 0.00 | 0.00 | 1 | 1 | 0 | 0 |
| note-CSVFileOption | 1 | 1 | 0 | 0.00 | 0.00 | 1 | 1 | 0 | 0 |
| note-dbSelectMulti | 2 | 2 | 2 | 1.00 | 1.00 | 1 | 1 | 0 | 0 |
| note-copyFile | 4 | 5 | 3 | 0.75 | 0.60 | 1 | 1 | 0 | 0 |
| note-cancelCallTimerService | 3 | 1 | 1 | 0.33 | 1.00 | 1 | 1 | 0 | 0 |
| note-sendDummyReturnNow | 4 | 2 | 2 | 0.50 | 1.00 | 1 | 1 | 0 | 0 |
| rev-dbSelectSingleLockRetry | 6 | 4 | 4 | 0.67 | 1.00 | 4 | 0 | 0 | 4 |
| rev-sendErrorResponseOnFail | 2 | 2 | 1 | 0.50 | 0.50 | 2 | 0 | 0 | 2 |
| rev-getSharedQueue | 2 | 0 | 0 | 0.00 | - | 0 | 0 | 0 | 0 |
| rev-NEXCORE_RUNTIME_MODE | 1 | 1 | 0 | 0.00 | 0.00 | 1 | 0 | 0 | 1 |
| rev-callAsyncServiceNow | 4 | 2 | 2 | 0.50 | 1.00 | 2 | 0 | 0 | 2 |
| rev-BaseRuntimeException | 1 | 0 | 0 | 0.00 | - | 0 | 0 | 0 | 0 |

**판정**: 정답 설명함 64 · 판정 설명함 40 · 맞음 32 → recall 0.50 · precision 0.80 (애매 라벨 제외)
**가드**: 대상 39 → 수정안 13 · 바꿀 곳 없음 0 · 버림 26 → 통과율(바꿀 곳 없음 제외) 0.33 · 다듬은 문장 대체 2
**지연**: 판정 210회 평균 1466 ms · 편집/다듬기 39회 평균 2016 ms

## 유형별

| 유형 | 해석 유형 | 해석 대상 | recall | precision | 가드 통과 | 자동 점검 통과 |
|---|---|---|---|---|---|---|
| deprecate | 5/6 | 4/6 | 0.23 | 1.00 | 1.00 | 1.00 |
| remove | 6/6 | 5/6 | 0.70 | 0.88 | 0.00 | - |
| add_note | 6/6 | 6/6 | 0.53 | 0.67 | 1.00 | 1.00 |
| revise | 3/6 | 2/6 | 0.44 | 0.78 | 0.00 | - |
| replace | 0/3 | 0/3 | - | - | - | - |
| unclear | 2/2 | 2/2 | - | - | - | - |

## 판정이 정답과 다른 청크

- dep-callBatchNow 0f8458a3 정답 D · 판정 mentions (배치 어플리케이션 개발)
- dep-reserveCallTimerService 46df9dd6 정답 D · 판정 mentions (온라인 서비스 개발)
- dep-reserveCallTimerService 86fe0e0c 정답 D · 판정 mentions (3.4.9 연동거래)
- dep-reserveCallTimerService 69980cfa 정답 D · 판정 mentions (3.4.9.6 지연 비동기 연동거래)
- dep-reserveCallTimerService 0f819358 정답 D · 판정 mentions (3.9.4.5 수동 타이머 처리 API)
- dep-OldFileDailyCleaner 5bed4e19 정답 D · 판정 mentions (39. 클래스: nexcore.framework.core.util.OldFileDailyCleaner)
- dep-FUAttcFileMng 7e812ad8 정답 D · 판정 mentions (3.15.8 첨부파일 관리 (대량 입력 거래))
- dep-FUAttcFileMng 52c06f51 정답 D · 판정 mentions (3.4 처리 흐름)
- dep-FUAttcFileMng 8063d6fd 정답 D · 판정 mentions (4. FUAttcFileMng.fmFileInqSingle — 파일조회-단건)
- dep-FUAttcFileMng f10e3358 정답 D · 판정 mentions (6. FUAttcFileMng.fmFileDel — 파일삭제)
- rm-isSingleJvmBatch 04c3b3d8 정답 D · 판정 mentions (1.4 메서드: `getRuntimeMode()`)
- rm-waitForAllReceiveEnd 9668db79 정답 D · 판정 mentions (7.11.3.7 큐 API 상세)
- rm-waitForAllReceiveEnd 2fae6c21 정답 D · 판정 mentions (4. AutoCommitRecordHandler / AbsRecordHandler / SharedQueue 옵션)
- rm-readLines dab85ba0 정답 U · 판정 describes (26.65 메서드: `writeLines(java.io.File,java.util.Collection)`)
- rm-readLines 8854b797 정답 U · 판정 describes (26.64 메서드: `writeLines(java.io.File,java.lang.String,java.util.Collection,boolean)`)
- rm-callAsyncServiceAfterCommit 86fe0e0c 정답 D · 판정 mentions (3.4.9 연동거래)
- rm-addErrorCountAmount 0641f105 정답 D · 판정 mentions (6.86 메서드: `addErrorCountAmount(long,java.math.BigDecimal)`)
- rm-closeQuietly 49cbb655 정답 D · 판정 mentions (32.3 메서드: `closeQuietly(java.io.Writer)`)
- note-getJobExecutionId 0bdaa7e5 정답 D · 판정 mentions (6.1.5 IBatchContext)
- note-getJobExecutionId 914138ad 정답 U · 판정 describes (6.28 메서드: `getJobGroupId()`)
- note-CSVFileOption a1bfa375 정답 D · 판정 mentions (7. 클래스: nexcore.framework.biz.online.SQLIO.CSVFileOption)
- note-CSVFileOption 76b46f49 정답 U · 판정 describes (8.3 메서드: `setHeaderSkip(boolean)`)
- note-copyFile 5872cff9 정답 D · 판정 mentions (26.24.1 매개변수)
- note-copyFile 41560c5b 정답 U · 판정 describes (26.34 메서드: `copyToFile(java.io.InputStream,java.io.File)`)
- note-copyFile 48fdeab4 정답 U · 판정 describes (26.31 메서드: `copyURLToFile(java.net.URL,java.io.File)`)
- note-cancelCallTimerService 86fe0e0c 정답 D · 판정 mentions (3.4.9 연동거래)
- note-cancelCallTimerService 0a8d1471 정답 D · 판정 mentions (6.10 메서드: `callAsyncServiceAfterRollback(java.lang.String,nexcore.framework.core.data.IDataSet,nexcore.framework.core.data.IOnlineContext)`)
- note-sendDummyReturnNow ee90de9e 정답 D · 판정 mentions ()
- note-sendDummyReturnNow 615b7ce8 정답 D · 판정 mentions (3.9.4.6 sendDummyReturn)
- rev-dbSelectSingleLockRetry 02734e5d 정답 D · 판정 mentions (온라인 서비스 개발)
- rev-dbSelectSingleLockRetry 38628c35 정답 D · 판정 mentions (3.5.3.4 DBMS의 오류 메시지 조회할 경우 잘못 사용한 예)
- rev-sendErrorResponseOnFail d006ef6b 정답 D · 판정 mentions (3.6 메서드: `sendDummyReturnNow(nexcore.framework.core.data.IOnlineContext)`)
- rev-sendErrorResponseOnFail cddc3d6d 정답 U · 판정 describes (3.9.7.1 송신 채널 더미 응답 (sendDummyReturnNow))
- rev-getSharedQueue 9668db79 정답 D · 판정 mentions (7.11.3.7 큐 API 상세)
- rev-getSharedQueue 2fae6c21 정답 D · 판정 mentions (4. AutoCommitRecordHandler / AbsRecordHandler / SharedQueue 옵션)
- rev-NEXCORE_RUNTIME_MODE 04c3b3d8 정답 D · 판정 mentions (1.4 메서드: `getRuntimeMode()`)
- rev-NEXCORE_RUNTIME_MODE 8fd3122a 정답 U · 판정 describes (3.11 메서드: `run(java.lang.Number...)`)
- rev-callAsyncServiceNow 86fe0e0c 정답 D · 판정 mentions (3.4.9 연동거래)
- rev-callAsyncServiceNow 3f0d9518 정답 D · 판정 mentions (3.4.9.4 비동기 연동거래)
- rev-BaseRuntimeException b8d63f5b 정답 D · 판정 mentions (3.5 예외 및 메시지 처리)

## 대상이었지만 수정안이 되지 않은 청크

- rm-waitForAllReceiveEnd ee166be2 [정답 D] 편집 응답을 읽지 못했다
- rm-readLines 071bcadd [정답 D] 편집 응답을 읽지 못했다
- rm-readLines f1af649c [정답 D] 편집 응답을 읽지 못했다
- rm-readLines c42d7394 [정답 D] 편집 응답을 읽지 못했다
- rm-readLines fec468a2 [정답 D] 편집 응답을 읽지 못했다
- rm-readLines 432ffd5e [정답 D] 편집 응답을 읽지 못했다
- rm-readLines dab85ba0 [정답 U] 편집 응답을 읽지 못했다
- rm-readLines 8854b797 [정답 U] 편집 응답을 읽지 못했다
- rm-callAsyncServiceAfterCommit f98c4db6 [정답 A] 편집 응답을 읽지 못했다
- rm-callAsyncServiceAfterCommit 9549695b [정답 D] 편집 응답을 읽지 못했다
- rm-addErrorCountAmount 6e858da9 [정답 D] 편집 응답을 읽지 못했다
- rm-addErrorCountAmount 5b1ab2f4 [정답 D] 편집 응답을 읽지 못했다
- rm-closeQuietly 7e13de0e [정답 D] 편집 응답을 읽지 못했다
- rm-closeQuietly a84d0ab4 [정답 D] 편집 응답을 읽지 못했다
- rm-closeQuietly 4b840f14 [정답 D] 편집 응답을 읽지 못했다
- rm-closeQuietly cc2aa6a1 [정답 D] 편집 응답을 읽지 못했다
- rm-closeQuietly 92ed84cd [정답 D] 편집 응답을 읽지 못했다
- rev-dbSelectSingleLockRetry bd5a9e46 [정답 D] 편집 응답을 읽지 못했다
- rev-dbSelectSingleLockRetry 503fa0e6 [정답 D] 편집 응답을 읽지 못했다
- rev-dbSelectSingleLockRetry 500b1092 [정답 D] 편집 응답을 읽지 못했다
- rev-dbSelectSingleLockRetry aadffed5 [정답 D] 편집 응답을 읽지 못했다
- rev-sendErrorResponseOnFail 615b7ce8 [정답 D] 편집 응답을 읽지 못했다
- rev-sendErrorResponseOnFail cddc3d6d [정답 U] 편집 응답을 읽지 못했다
- rev-NEXCORE_RUNTIME_MODE 8fd3122a [정답 U] 편집 응답을 읽지 못했다
- rev-callAsyncServiceNow 9549695b [정답 D] 편집 응답을 읽지 못했다
- rev-callAsyncServiceNow f98c4db6 [정답 D] 편집 응답을 읽지 못했다
