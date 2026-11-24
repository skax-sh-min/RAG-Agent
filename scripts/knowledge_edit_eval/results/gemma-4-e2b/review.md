# 6c 수정안 검토 — gemma-4-e2b (.\gemma-4-E2B-it-Q4_K_M.gguf)

각 수정안을 읽고 `수용` 칸에 O(고치지 않고 승인할 만함) / X 를 적는다. 자동 점검은 참고용이다.

## P1 · dep-callBatchNow · deprecate

- 지시: callBatchNow 는 더 이상 사용되지 않습니다. 대신 callBatchAfterCommit 을 사용하세요.
- 청크 `c2ff5cf8-fad6-47c0-a585-03fcfcef170f` (7.10.6.1 호출 API) · 정답 라벨 **D** · 종류 `edit`
- 근거: 사용 중단 안내: callBatchNow
- 자동 점검: OK 안내 블록 있음
- 수용: ☐

```diff
@@ -1 +1 @@
 #### 7.10.6 온라인에서 배치 호출(온디맨드 배치)
+
+> **사용 중단** — callBatchNow은 더 이상 사용되지 않으며, 대신 callBatchAfterCommit을 사용하세요.
+
 ##### 7.10.6.1 호출 API
```

## P2 · dep-OldFileDailyCleaner · deprecate

- 지시: OldFileDailyCleaner 클래스는 폐기되었습니다.
- 청크 `a8b09635-bb5e-43da-92bd-cb33130791c6` (39.18 메서드: `setBeanName(java.lang.String)`) · 정답 라벨 **A** · 종류 `edit`
- 근거: 사용 중단 안내: OldFileDailyCleaner
- 자동 점검: OK 안내 블록 있음
- 수용: ☐

```diff
@@ -1 +1 @@
 ## 39. 클래스: nexcore.framework.core.util.OldFileDailyCleaner
+
+> **사용 중단** — OldFileDailyCleaner은 더 이상 사용되지 않습니다.
+
 ### 39.18 메서드: `setBeanName(java.lang.String)`
```

## P3 · dep-OldFileDailyCleaner · deprecate

- 지시: OldFileDailyCleaner 클래스는 폐기되었습니다.
- 청크 `75d06572-f85f-42b7-a055-8f1b8531e52e` (39.15 메서드: `getRetainDays()`) · 정답 라벨 **A** · 종류 `edit`
- 근거: 사용 중단 안내: OldFileDailyCleaner
- 자동 점검: OK 안내 블록 있음
- 수용: ☐

```diff
@@ -1 +1 @@
 ## 39. 클래스: nexcore.framework.core.util.OldFileDailyCleaner
+
+> **사용 중단** — OldFileDailyCleaner은 더 이상 사용되지 않습니다.
+
 ### 39.15 메서드: `getRetainDays()`
```

## P4 · dep-OldFileDailyCleaner · deprecate

- 지시: OldFileDailyCleaner 클래스는 폐기되었습니다.
- 청크 `8ab7b1f8-e797-4bb2-902a-68efe8d45d85` (39.3 메서드: `getDirectories()`) · 정답 라벨 **A** · 종류 `edit`
- 근거: 사용 중단 안내: OldFileDailyCleaner
- 자동 점검: OK 안내 블록 있음
- 수용: ☐

```diff
@@ -1 +1 @@
 ## 39. 클래스: nexcore.framework.core.util.OldFileDailyCleaner
+
+> **사용 중단** — OldFileDailyCleaner은 더 이상 사용되지 않습니다.
+
 ### 39.3 메서드: `getDirectories()`
```

## P5 · dep-CaseIgnoreHashMap · deprecate

- 지시: CaseIgnoreHashMap 은 사용 중단되었으니 대신 HashMap 을 쓰세요.
- 청크 `1f12e83a-0f02-4ccc-b84b-a3745b32cf76` (9. 클래스: nexcore.framework.core.util.CaseIgnoreHashMap<K, V>) · 정답 라벨 **D** · 종류 `edit`
- 근거: 사용 중단 안내: CaseIgnoreHashMap
- 자동 점검: OK 안내 블록 있음
- 수용: ☐

```diff
@@ -1 +1 @@
 ## 9. 클래스: nexcore.framework.core.util.CaseIgnoreHashMap<K, V>
 
+> **사용 중단** — CaseIgnoreHashMap은 더 이상 사용되지 않습니다. 대신 HashMap을 사용하세요.
+
 **패키지:** `nexcore.framework.core.util`  
 **정규화 클래스명:** `nexcore.framework.core.util.CaseIgnoreHashMap<K, V>`
```

## P6 · dep-PaddableDataOutputStream · deprecate

- 지시: PaddableDataOutputStream 은 더 이상 지원하지 않습니다.
- 청크 `4d0693ce-d6d1-4a28-b6ff-6a989142dc5c` (40. 클래스: nexcore.framework.core.util.PaddableDataOutputStream) · 정답 라벨 **D** · 종류 `edit`
- 근거: 사용 중단 안내: PaddableDataOutputStream
- 자동 점검: OK 안내 블록 있음
- 수용: ☐

```diff
@@ -1 +1 @@
 ## 40. 클래스: nexcore.framework.core.util.PaddableDataOutputStream
 
+> **사용 중단** — PaddableDataOutputStream은 더 이상 사용되지 않습니다.
+
 **패키지:** `nexcore.framework.core.util`  
 **정규화 클래스명:** `nexcore.framework.core.util.PaddableDataOutputStream`
```

## P7 · dep-PaddableDataOutputStream · deprecate

- 지시: PaddableDataOutputStream 은 더 이상 지원하지 않습니다.
- 청크 `eb47060b-9e97-476d-9ccf-a83ad52e0eb5` (40.3 생성자: `<init>(java.io.OutputStream,java.lang.String)`) · 정답 라벨 **A** · 종류 `edit`
- 근거: 사용 중단 안내: PaddableDataOutputStream
- 자동 점검: OK 안내 블록 있음
- 수용: ☐

```diff
@@ -1 +1 @@
 ## 40. 클래스: nexcore.framework.core.util.PaddableDataOutputStream
+
+> **사용 중단** — PaddableDataOutputStream은 더 이상 사용되지 않습니다.
+
 ### 40.3 생성자: `<init>(java.io.OutputStream,java.lang.String)`
```

## P8 · note-getJobExecutionId · add_note

- 지시: getJobExecutionId 에 '같은 Job 을 재실행하면 실행 ID 가 새로 발급된다' 는 주의 사항을 추가해
- 청크 `eaf95617-d4b1-4c94-b4ba-6bc558d0f993` (12.1 메서드: `getBatchObject()`) · 정답 라벨 **A** · 종류 `edit`
- 근거: 주의 사항 추가: getJobExecutionId
- 자동 점검: OK 주의 블록 있음
- 수용: ☐

```diff
@@ -60 +60 @@
 배치 JOB EXECUTION ID를 반환한다.
 
+> **주의** — 같은 Job 을 재실행하면 실행 ID 가 새로 발급된다.
+
 ### 12.4 메서드: `getJobExecutionId()`
 #### 12.4.1 반환값
```

## P9 · note-CSVFileOption · add_note

- 지시: CSVFileOption 에 '인코딩을 지정하지 않으면 서버 기본 인코딩이 쓰인다' 는 주의 사항을 추가해
- 청크 `c4e6b330-01c6-4aec-9bd4-ec6df52de085` (7.15 메서드: `setRecordResolver(nexcore.framework.integration.db.IRecordResolver)`) · 정답 라벨 **A** · 종류 `edit`
- 근거: 주의 사항 추가: CSVFileOption
- 자동 점검: OK 주의 블록 있음
- 수용: ☐

```diff
@@ -44 +44 @@
 저장파일객체 지정
 
+> **주의** — 인코딩을 지정하지 않으면 서버 기본 인코딩이 쓰인다
+
 ### 7.17 메서드: `setFile(java.io.File)`
 #### 7.17.1 매개변수
```

## P10 · note-dbSelectMulti · add_note

- 지시: dbSelectMulti 와 관련해 '조회 건수가 많으면 메모리가 부족해질 수 있다' 는 주의 사항을 추가해
- 청크 `c70d5551-ab1c-4cc5-8cb4-20f794a2df1f` (7.6.6 소량 Select 처리) · 정답 라벨 **D** · 종류 `edit`
- 근거: 주의 사항 추가: dbSelectMulti · 다듬은 문장이 검사를 통과하지 못해 지시문 문장을 그대로 넣음 · 길이가 기준 문장과 크게 다르다(324%)
- 자동 점검: OK 주의 블록 있음
- 수용: ☐

```diff
@@ -19 +19 @@
 IRecordSet rs = dbSelectMulti("select", sqlparam);
 ```
+
+> **주의** — 조회 건수가 많으면 메모리가 부족해질 수 있다
```

## P11 · note-copyFile · add_note

- 지시: copyFile 에 '대상 파일이 이미 있으면 덮어쓴다' 는 주의 사항을 넣어줘
- 청크 `2489489b-d7dd-4b4b-8d7c-d5930915eb2e` (26.23 메서드: `copyFile(java.io.File,java.io.File)`) · 정답 라벨 **D** · 종류 `edit`
- 근거: 주의 사항 추가: copyFile
- 자동 점검: OK 주의 블록 있음
- 수용: ☐

```diff
@@ -12 +12 @@
 Copies a file to a new location preserving the file date.
 
+> **주의** — 대상 파일이 이미 있으면 덮어쓴다
+
 This method copies the contents of the specified source file to the
 specified destination file. The directory holding the destination file is
```

## P12 · note-cancelCallTimerService · add_note

- 지시: cancelCallTimerService 에 '취소에 실패하면 false 를 돌려주므로 반드시 결과를 확인해야 한다' 는 주의 사항을 추가해
- 청크 `12f300a1-bf2b-4e3f-b844-1b6565524a18` (3.9.4.7 타이머 해제) · 정답 라벨 **D** · 종류 `edit`
- 근거: 주의 사항 추가: cancelCallTimerService
- 자동 점검: OK 주의 블록 있음
- 수용: ☐

```diff
@@ -16 +16 @@
 ```
 
+> **주의** — 취소에 실패하면 false를 반환하므로 반드시 결과를 확인해야 한다.
+
 `cancelCallTimerService()`의 리턴 값이 `true`이면 정상적으로 타이머가 꺼진 상태이고, `false`이면 이미 타이머가 실행된 경우이므로 이때는 업무 로직에 따라 별도의 에러처리를 해야 합니다.
```

## P13 · note-sendDummyReturnNow · add_note

- 지시: sendDummyReturnNow 에 '비동기 요청 거래에서만 호출해야 한다' 는 주의 사항을 추가해
- 청크 `cddc3d6d-bbdc-4ba2-97f2-fbb719685613` (3.9.7.1 송신 채널 더미 응답 (sendDummyReturnNow)) · 정답 라벨 **D** · 종류 `edit`
- 근거: 주의 사항 추가: sendDummyReturnNow · 다듬은 문장이 검사를 통과하지 못해 지시문 문장을 그대로 넣음 · 길이가 기준 문장과 크게 다르다(210%)
- 자동 점검: OK 주의 블록 있음
- 수용: ☐

```diff
@@ -14 +14 @@
 | --- | --- |
 | sendDummyReturnNow(onlineCtx, **false**); | **전문을 송신 받은 채널로 더미 리턴 응답**.이후 에러가 발생해도 에러 전문을 송신 채널로 전송하지 않음 |
+
+> **주의** — 비동기 요청 거래에서만 호출해야 한다
```

