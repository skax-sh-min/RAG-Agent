# 6c 수정안 검토 — gemma-4-12b-r2 (.\gemma-4-12B-it-Q4_K_M.gguf)

각 수정안을 읽고 `수용` 칸에 O(고치지 않고 승인할 만함) / X 를 적는다. 자동 점검은 참고용이다.

## P1 · dep-callBatchNow · deprecate

- 지시: callBatchNow 는 더 이상 사용되지 않습니다. 대신 callBatchAfterCommit 을 사용하세요.
- 청크 `b757982f-3d92-4083-96ca-8f5e91294d96` (47. PUSample.pmEDU99R0001 — 온디맨드 배치 호출) · 정답 라벨 **A** · 종류 `edit`
- 근거: 사용 중단 안내: callBatchNow
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -1 +1 @@
 ## 47. PUSample.pmEDU99R0001 — 온디맨드 배치 호출
 
+> **사용 중단** — `callBatchNow`는 더 이상 사용되지 않으므로 `callBatchAfterCommit`을 사용하십시오.
+
 ### 47.1 개요 (키워드: callBatchNow, 온디맨드)
```

## P2 · dep-callBatchNow · deprecate

- 지시: callBatchNow 는 더 이상 사용되지 않습니다. 대신 callBatchAfterCommit 을 사용하세요.
- 청크 `7bb201a3-4d52-424e-b99a-6f4ecf4d011a` (54. PUFwkTest — 온디맨드·대량입출력) · 정답 라벨 **M** · 종류 `edit`
- 근거: 사용 중단 안내: callBatchNow
- 자동 점검: OK 안내 블록 있음
- 수용: x

```diff
@@ -1 +1 @@
 ## 54. PUFwkTest — 온디맨드·대량입출력
 
+> **사용 중단** — `callBatchNow`는 더 이상 사용되지 않으므로 `callBatchAfterCommit`을 사용하십시오.
+
 ### 54.1 개요 (키워드: PU, 대량입력, 대량출력, 온디맨드)
```

## P3 · dep-callBatchNow · deprecate

- 지시: callBatchNow 는 더 이상 사용되지 않습니다. 대신 callBatchAfterCommit 을 사용하세요.
- 청크 `624108d4-66bb-4ef4-b5a9-eaaeeafe98e0` (4.7 메서드: `callMethodByNotSupported(java.lang.String,nexcore.framework.core.data.IDataSet,nexcore.framework.core.data.IOnlineContext)`) · 정답 라벨 **A** · 종류 `edit`
- 근거: 사용 중단 안내: callBatchNow
- 자동 점검: OK 안내 블록 있음
- 수용: x

```diff
@@ -1 +1 @@
 ## 4. 클래스: nexcore.framework.biz.online.FunctionUnit
+
+> **사용 중단** — `callBatchNow`는 더 이상 사용되지 않으므로 `callBatchAfterCommit`을 사용하십시오.
+
 ### 4.7 메서드: `callMethodByNotSupported(java.lang.String,nexcore.framework.core.data.IDataSet,nexcore.framework.core.data.IOnlineContext)`
```

## P4 · dep-callBatchNow · deprecate

- 지시: callBatchNow 는 더 이상 사용되지 않습니다. 대신 callBatchAfterCommit 을 사용하세요.
- 청크 `0f8458a3-6750-4c6b-8e33-f0e71a3e7511` (배치 어플리케이션 개발) · 정답 라벨 **D** · 종류 `edit`
- 근거: 사용 중단 안내: callBatchNow
- 자동 점검: OK 안내 블록 있음
- 수용: x

```diff
@@ -1 +1 @@
 ## 배치 어플리케이션 개발
 
+> **사용 중단** — callBatchNow는 더 이상 사용되지 않으므로 대신 callBatchAfterCommit을 사용하십시오.
+
 ### 온디맨드 배치
```

## P5 · dep-callBatchNow · deprecate

- 지시: callBatchNow 는 더 이상 사용되지 않습니다. 대신 callBatchAfterCommit 을 사용하세요.
- 청크 `c2ff5cf8-fad6-47c0-a585-03fcfcef170f` (7.10.6.1 호출 API) · 정답 라벨 **D** · 종류 `edit`
- 근거: 사용 중단 안내: callBatchNow
- 자동 점검: OK 안내 블록 있음
- 수용: x

```diff
@@ -1 +1 @@
 #### 7.10.6 온라인에서 배치 호출(온디맨드 배치)
+
+> **사용 중단** — callBatchNow는 더 이상 사용되지 않으므로 대신 callBatchAfterCommit을 사용하십시오.
+
 ##### 7.10.6.1 호출 API
```

## P6 · dep-callBatchNow · deprecate

- 지시: callBatchNow 는 더 이상 사용되지 않습니다. 대신 callBatchAfterCommit 을 사용하세요.
- 청크 `fb33e957-6e94-485f-8991-4a603c9c5123` (7.10.6 온라인에서 배치 호출(온디맨드 배치)) · 정답 라벨 **A** · 종류 `edit`
- 근거: 사용 중단 안내: callBatchNow
- 자동 점검: OK 안내 블록 있음
- 수용: x

```diff
@@ -1 +1 @@
 ### 7.10 온라인 프레임워크 연계
+
+> **사용 중단** — callBatchNow는 더 이상 사용되지 않으므로 대신 callBatchAfterCommit을 사용하십시오.
+
 #### 7.10.6 온라인에서 배치 호출(온디맨드 배치)
```

## P7 · dep-reserveCallTimerService · deprecate

- 지시: reserveCallTimerService 는 이제 사용하지 않습니다.
- 청크 `0a8d1471-9c55-4b65-ab94-7753656ea44e` (6.10 메서드: `callAsyncServiceAfterRollback(java.lang.String,nexcore.framework.core.data.IDataSet,nexcore.framework.core.data.IOnlineContext)`) · 정답 라벨 **A** · 종류 `edit`
- 근거: 사용 중단 안내: reserveCallTimerService
- 자동 점검: OK 안내 블록 있음
- 수용: x

```diff
@@ -1 +1 @@
 ## 6. 클래스: nexcore.framework.biz.online.ProcessUnit
+
+> **사용 중단** — `reserveCallTimerService`는 더 이상 사용되지 않습니다.
+
 ### 6.10 메서드: `callAsyncServiceAfterRollback(java.lang.String,nexcore.framework.core.data.IDataSet,nexcore.framework.core.data.IOnlineContext)`
```

## P8 · dep-reserveCallTimerService · deprecate

- 지시: reserveCallTimerService 는 이제 사용하지 않습니다.
- 청크 `46df9dd6-8436-4e82-b1e1-de37766299d6` (온라인 서비스 개발) · 정답 라벨 **D** · 종류 `edit`
- 근거: 사용 중단 안내: reserveCallTimerService
- 자동 점검: OK 안내 블록 있음
- 수용: x

```diff
@@ -1 +1 @@
 ## 온라인 서비스 개발
 
+> **사용 중단** — reserveCallTimerService는 더 이상 사용되지 않습니다.
+
 ### 타이머 처리
```

## P9 · dep-reserveCallTimerService · deprecate

- 지시: reserveCallTimerService 는 이제 사용하지 않습니다.
- 청크 `86fe0e0c-19c5-473b-b87b-55f41d7530a7` (3.4.9 연동거래) · 정답 라벨 **D** · 종류 `edit`
- 근거: 사용 중단 안내: reserveCallTimerService
- 자동 점검: OK 안내 블록 있음
- 수용: x

```diff
@@ -1 +1 @@
 ### 3.4 어플리케이션 호출
+
+> **사용 중단** — reserveCallTimerService는 더 이상 사용되지 않습니다.
+
 #### 3.4.9 연동거래
```

## P10 · dep-reserveCallTimerService · deprecate

- 지시: reserveCallTimerService 는 이제 사용하지 않습니다.
- 청크 `69980cfa-57e9-4a6d-a440-56a81d338463` (3.4.9.6 지연 비동기 연동거래) · 정답 라벨 **D** · 종류 `edit`
- 근거: 사용 중단 안내: reserveCallTimerService
- 자동 점검: OK 안내 블록 있음
- 수용: x

```diff
@@ -1 +1 @@
 #### 3.4.9 연동거래
+
+> **사용 중단** — reserveCallTimerService는 더 이상 사용되지 않습니다.
+
 ##### 3.4.9.6 지연 비동기 연동거래
```

## P11 · dep-reserveCallTimerService · deprecate

- 지시: reserveCallTimerService 는 이제 사용하지 않습니다.
- 청크 `0f819358-d6d1-45a9-a1b2-b28b8f81937d` (3.9.4.5 수동 타이머 처리 API) · 정답 라벨 **D** · 종류 `edit`
- 근거: 사용 중단 안내: reserveCallTimerService
- 자동 점검: OK 안내 블록 있음
- 수용: x

```diff
@@ -1 +1 @@
 #### 3.9.4 API
+
+> **사용 중단** — reserveCallTimerService 메소드는 더 이상 사용되지 않습니다.
+
 ##### 3.9.4.5 수동 타이머 처리 API
```

## P12 · dep-reserveCallTimerService · deprecate

- 지시: reserveCallTimerService 는 이제 사용하지 않습니다.
- 청크 `5a7adfa5-1209-4be9-bf68-f8686ac07e4c` (3.4 어플리케이션 호출) · 정답 라벨 **M** · 종류 `edit`
- 근거: 사용 중단 안내: reserveCallTimerService
- 자동 점검: OK 안내 블록 있음
- 수용: x

```diff
@@ -1 +1 @@
 ## 3. 개발 - 온라인 어플리케이션
+
+> **사용 중단** — reserveCallTimerService는 더 이상 사용되지 않습니다.
+
 ### 3.4 어플리케이션 호출
```

## P13 · dep-OldFileDailyCleaner · deprecate

- 지시: OldFileDailyCleaner 클래스는 폐기되었습니다.
- 청크 `5bed4e19-bb7d-4ec3-8daa-1630f1635e01` (39. 클래스: nexcore.framework.core.util.OldFileDailyCleaner) · 정답 라벨 **D** · 종류 `edit`
- 근거: 사용 중단 안내: OldFileDailyCleaner
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -1 +1 @@
 ## 39. 클래스: nexcore.framework.core.util.OldFileDailyCleaner
 
+> **사용 중단** — OldFileDailyCleaner는 더 이상 사용되지 않습니다.
+
 **패키지:** `nexcore.framework.core.util`  
 **정규화 클래스명:** `nexcore.framework.core.util.OldFileDailyCleaner`
```

## P14 · dep-CaseIgnoreHashMap · deprecate

- 지시: CaseIgnoreHashMap 은 사용 중단되었으니 대신 HashMap 을 쓰세요.
- 청크 `1f12e83a-0f02-4ccc-b84b-a3745b32cf76` (9. 클래스: nexcore.framework.core.util.CaseIgnoreHashMap<K, V>) · 정답 라벨 **D** · 종류 `edit`
- 근거: 사용 중단 안내: CaseIgnoreHashMap
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -1 +1 @@
 ## 9. 클래스: nexcore.framework.core.util.CaseIgnoreHashMap<K, V>
 
+> **사용 중단** — CaseIgnoreHashMap은 더 이상 사용되지 않으므로 대신 HashMap을 사용하십시오.
+
 **패키지:** `nexcore.framework.core.util`  
 **정규화 클래스명:** `nexcore.framework.core.util.CaseIgnoreHashMap<K, V>`
```

## P15 · dep-CaseIgnoreHashMap · deprecate

- 지시: CaseIgnoreHashMap 은 사용 중단되었으니 대신 HashMap 을 쓰세요.
- 청크 `a062e03a-8493-4fa2-ac61-227a76e07f70` (9.15 메서드: `values()`) · 정답 라벨 **A** · 종류 `edit`
- 근거: 사용 중단 안내: CaseIgnoreHashMap
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -1 +1 @@
 ## 9. 클래스: nexcore.framework.core.util.CaseIgnoreHashMap<K, V>
+
+> **사용 중단** — CaseIgnoreHashMap은 더 이상 사용되지 않으므로 대신 HashMap을 사용하십시오.
+
 ### 9.15 메서드: `values()`
```

## P16 · dep-CaseIgnoreHashMap · deprecate

- 지시: CaseIgnoreHashMap 은 사용 중단되었으니 대신 HashMap 을 쓰세요.
- 청크 `18d2382e-1d15-4447-956b-09c82cde061b` (9.12 메서드: `putAll(java.util.Map)`) · 정답 라벨 **A** · 종류 `edit`
- 근거: 사용 중단 안내: CaseIgnoreHashMap
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -1 +1 @@
 ## 9. 클래스: nexcore.framework.core.util.CaseIgnoreHashMap<K, V>
+
+> **사용 중단** — CaseIgnoreHashMap은 더 이상 사용되지 않으므로 대신 HashMap을 사용하십시오.
+
 ### 9.12 메서드: `putAll(java.util.Map)`
```

## P17 · dep-FUAttcFileMng · deprecate

- 지시: FUAttcFileMng 는 더 이상 쓰이지 않습니다. 대신 FUFileMng 를 사용하세요.
- 청크 `f10e3358-fa52-4dad-a0d9-cb2135681134` (6. FUAttcFileMng.fmFileDel — 파일삭제) · 정답 라벨 **D** · 종류 `edit`
- 근거: 사용 중단 안내: FUAttcFileMng
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -1 +1 @@
 ## 6. FUAttcFileMng.fmFileDel — 파일삭제
 
+> **사용 중단** — FUAttcFileMng은 더 이상 사용되지 않으므로 FUFileMng을 사용하십시오.
+
 ### 6.1 개요
```

## P18 · dep-FUAttcFileMng · deprecate

- 지시: FUAttcFileMng 는 더 이상 쓰이지 않습니다. 대신 FUFileMng 를 사용하세요.
- 청크 `1b50fde8-09d4-4c39-8d3c-f3f96adefa6f` (2. MyBizComponent — 공통기능 컴포넌트) · 정답 라벨 **A** · 종류 `edit`
- 근거: 사용 중단 안내: FUAttcFileMng
- 자동 점검: OK 안내 블록 있음
- 수용: x

```diff
@@ -1 +1 @@
 ## 2. MyBizComponent — 공통기능 컴포넌트
 
+> **사용 중단** — FUAttcFileMng은 더 이상 사용되지 않으므로 FUFileMng을 사용하십시오.
+
 ### 2.1 개요
```

## P19 · dep-FUAttcFileMng · deprecate

- 지시: FUAttcFileMng 는 더 이상 쓰이지 않습니다. 대신 FUFileMng 를 사용하세요.
- 청크 `52c06f51-fd05-4a26-b428-8134fc80c984` (3.4 처리 흐름) · 정답 라벨 **D** · 종류 `edit`
- 근거: 사용 중단 안내: FUAttcFileMng
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -1 +1 @@
 ## 3. FUAttcFileMng.fmFileReg — 파일등록
+
+> **사용 중단** — FUAttcFileMng은 더 이상 사용되지 않으므로 FUFileMng을 사용하십시오.
+
 ### 3.4 처리 흐름
```

## P20 · dep-FUAttcFileMng · deprecate

- 지시: FUAttcFileMng 는 더 이상 쓰이지 않습니다. 대신 FUFileMng 를 사용하세요.
- 청크 `8063d6fd-3e29-42c8-aa4a-39692e29fcb0` (4. FUAttcFileMng.fmFileInqSingle — 파일조회-단건) · 정답 라벨 **D** · 종류 `edit`
- 근거: 사용 중단 안내: FUAttcFileMng
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -1 +1 @@
 ## 4. FUAttcFileMng.fmFileInqSingle — 파일조회-단건
 
+> **사용 중단** — FUAttcFileMng은 더 이상 사용되지 않으므로 FUFileMng을 사용하십시오.
+
 ### 4.1 개요
```

## P21 · dep-FUAttcFileMng · deprecate

- 지시: FUAttcFileMng 는 더 이상 쓰이지 않습니다. 대신 FUFileMng 를 사용하세요.
- 청크 `7e812ad8-54e4-42ee-8dac-b1eeea0cb880` (3.15.8 첨부파일 관리 (대량 입력 거래)) · 정답 라벨 **D** · 종류 `edit`
- 근거: 사용 중단 안내: FUAttcFileMng
- 자동 점검: OK 안내 블록 있음
- 수용: x

```diff
@@ -1 +1 @@
 ### 3.15 기타 코딩 가이드
+
+> **사용 중단** — FUAttcFileMng은 더 이상 사용되지 않으므로, 대신 FUFileMng을 사용하십시오.
+
 #### 3.15.8 첨부파일 관리 (대량 입력 거래)
```

## P22 · rm-isSingleJvmBatch · remove

- 지시: isSingleJvmBatch 에 대한 설명을 삭제해
- 청크 `4fe326c5-b31e-43a4-92a1-e0ac6d7b0ff0` (유형1 – 반복 데이터 메모리 캐시 사용) · 정답 라벨 **M** · 종류 `edit`
- 근거: 내용 삭제: isSingleJvmBatch · 일부 작업을 버림 —  · 1개 인용 실패
- 자동 점검: OK 대상이 사라짐 · 지운 글자 427 (원문의 20%)
- 수용: x

```diff
@@ -40 +40 @@
 ```java
 IRecord record = null;
-if (BaseUtils.isSingleJvmBatch()) {
-    ICache cache = BaseUtils.getCacheManager().getCacheOrAdd("BCC_BCD_GVCRPRODINFO");
-    record = (IRecord)cache.get(requestData.getString("PROD_CD"));
 
-    if (record == null) {
-        record = dbSelectSingle("select01", requestData, onlineCtx);
-        cache.put(requestData.getString("PROD_CD"), record);
-    }
-} else {
-    record = dbSelectSingle("select01", requestData, onlineCtx);
-}
 // 이후 로직 그대로

```

## P23 · rm-isSingleJvmBatch · remove

- 지시: isSingleJvmBatch 에 대한 설명을 삭제해
- 청크 `51689137-c905-43c7-9621-496116dc4dfb` (유형1 – 반복 데이터 메모리 캐시 사용) · 정답 라벨 **M** · 종류 `edit`
- 근거: 내용 삭제: isSingleJvmBatch
- 자동 점검: OK 대상이 사라짐 · 지운 글자 35 (원문의 2%)
- 수용: x

```diff
@@ -37 +37 @@

 IRecord record = null;
-if (BaseUtils.isSingleJvmBatch()) {
+
```

## P24 · rm-isSingleJvmBatch · remove

- 지시: isSingleJvmBatch 에 대한 설명을 삭제해
- 청크 `040aa752-1288-400e-aa9b-1e6cbd62bad1` ([BCA] 업무공통 휴일 체크) · 정답 라벨 **M** · 종류 `edit`
- 근거: 내용 삭제: isSingleJvmBatch · 일부 작업을 버림 —  · 1개 인용 실패
- 자동 점검: 확인 필요 — 대상이 아직 남음 · 지운 글자 54 (원문의 4%)
- 수용: x

```diff
@@ -31 +31 @@
 }
 
-1. **배치 여부 로직 삭제** : if (BaseUtils.isSingleJvmBatch())
+
 
 2. 먼저 캐시에 있는지 확인함
```

## P25 · rm-waitForAllReceiveEnd · remove

- 지시: waitForAllReceiveEnd 관련 내용은 지워줘
- 청크 `7b0a4747-1c1b-47bd-b6da-142ac3c66b13` (22. BEDU5003 — 병렬 SharedQueue Reader (멀티 큐)) · 정답 라벨 **M** · 종류 `edit`
- 근거: 내용 삭제: waitForAllReceiveEnd
- 자동 점검: OK 대상이 사라짐 · 지운 글자 33 (원문의 2%)
- 수용: x

```diff
@@ -38 +38 @@
 3. CSNO%3 인덱스로 send
 4. onFinally 모든 Sender finish
-5. 정상 시 모든 큐 waitForAllReceiveEnd
+
 
 ### 22.5 핵심 코드
```

## P26 · rm-waitForAllReceiveEnd · remove

- 지시: waitForAllReceiveEnd 관련 내용은 지워줘
- 청크 `827d5aff-c679-4551-a26b-9371b77aa5ca` (20. BEDU5001 — 병렬 SharedQueue Reader (단일 큐)) · 정답 라벨 **M** · 종류 `edit`
- 근거: 내용 삭제: waitForAllReceiveEnd
- 자동 점검: OK 대상이 사라짐 · 지운 글자 179 (원문의 8%)
- 수용: x

```diff
@@ -3 +3 @@
 ### 20.1 개요
 
-SharedQueue, Reader, 단일큐, createSharedQueue, send, finish, waitForAllReceiveEnd, DB-to-Queue
+SharedQueue, Reader, 단일큐, createSharedQueue, send
 
 ### 20.2 언제 쓰는가
@@ -40 +40 @@
 3. 행마다 `sharedQueueSender.send`
 4. `onFinally`에서 `finish()`
-5. 정상 시 `afterExecute`에서 `waitForAllReceiveEnd()`
 
+
 ### 20.5 핵심 코드
 
@@ -81 +81 @@
 
 - **Q. send 타임아웃이면?** A. 10초 내 receive 못하면 예외 → `KISBException`으로 래핑.
-- **Q. execute 실패 시 wait?** A. `exceptionInExecute == null`일 때만 `waitForAllReceiveEnd`.
+
```

## P27 · rm-waitForAllReceiveEnd · remove

- 지시: waitForAllReceiveEnd 관련 내용은 지워줘
- 청크 `ee166be2-54a0-4b44-a80b-389bcec2768c` (5.6 메서드: `waitForAllReceiveEnd()`) · 정답 라벨 **D** · 종류 `edit`
- 근거: 내용 삭제: waitForAllReceiveEnd · 일부 작업을 버림 —  · 3개 인용 실패
- 자동 점검: 확인 필요 — 대상이 아직 남음 · 지운 글자 267 (원문의 30%)
- 수용: o 

```diff
@@ -1 +1 @@
 ## 5. 클래스: nexcore.framework.batch.appbase.mem.SharedQueue
-### 5.6 메서드: `waitForAllReceiveEnd()`
-
-**패키지:** `nexcore.framework.batch.appbase.mem`
-**소속 클래스:** `nexcore.framework.batch.appbase.mem.SharedQueue`
-**멤버 종류:** `메서드`
-
-```java
-public void waitForAllReceiveEnd ()
-```
-
-receiver들이 queue에 것을 모두 빼갈 때까지 wait 함. 무한히 wait 함.
-
 ### 5.7 메서드: `waitForAllReceiveEnd(long)`
```

## P28 · rm-waitForAllReceiveEnd · remove

- 지시: waitForAllReceiveEnd 관련 내용은 지워줘
- 청크 `301a3841-454b-4d2c-8799-a49e7c526e0e` (7.11.3.5 멀티큐 예제 - Reader) · 정답 라벨 **M** · 종류 `edit`
- 근거: 내용 삭제: waitForAllReceiveEnd · 일부 작업을 버림 — 대상(waitForAllReceiveEnd)과 관련된 구간이 아니다 — 인용에도 그 섹션 제목에도 대상이 없다
- 자동 점검: OK 대상이 사라짐 · 지운 글자 43 (원문의 5%)
- 수용: x

```diff
@@ -32 +32 @@
         for (int i=0; i<this.numberOfQueue; i++) {
             log.info("큐 {0}_{1} 수신완료 대기중", this.sharedQueueId, i);
-            this.sharedQueue[i].waitForAllReceiveEnd();
+            
         }
     }
```

## P29 · rm-readLines · remove

- 지시: readLines 메서드 설명은 문서에서 빼 줘
- 청크 `071bcadd-a0b3-45a9-abba-0921ff3410d2` (26.46 메서드: `readLines(java.io.File)`) · 정답 라벨 **D** · 종류 `delete_chunk`
- 근거: 청크 삭제: readLines
- 자동 점검: OK 청크 삭제(제목에 대상)
- 수용: x

```diff
(청크 전체 삭제)
## 26. 클래스: nexcore.framework.core.util.FileUtils
### 26.46 메서드: `readLines(java.io.File)`

**패키지:** `nexcore.framework.core.util`  
**소속 클래스:** `nexcore.framework.core.util.FileUtils`  
**멤버 종류:** `메서드`

```java
@Deprecated public static List < String > readLines ( File file) throws IOException

**상태:** `Deprecated`

#### 26.46.1 Deprecated

- Deprecated. 이 API는 더 이상 사용을 권장하지 않습니다.

#### 26.46.2 대체 사용

- `readLines(File, Charset)` 를 사용하세요.

Reads the contents of a file line by line to a List of Strings using the default encoding for the VM.
The file is always closed.

#### 26.46.3 매개변수

- `file` - the file to read, must not be `null`

#### 26.46.4 반환값

- the list of Strings representing each line in the file, never `null`

#### 26.46.5 예외

- `IOException` - in case of an I/O error

### 26.46 메서드: `readLines(java.io.File)`
#### 26.46.6 도입 버전

- 1.3
```

## P30 · rm-readLines · remove

- 지시: readLines 메서드 설명은 문서에서 빼 줘
- 청크 `f1af649c-30d1-44fd-a372-f31aa67ee291` (26.45 메서드: `readLines(java.io.File,java.lang.String)`) · 정답 라벨 **D** · 종류 `delete_chunk`
- 근거: 청크 삭제: readLines
- 자동 점검: OK 청크 삭제(제목에 대상)
- 수용: o

```diff
(청크 전체 삭제)
## 26. 클래스: nexcore.framework.core.util.FileUtils
### 26.45 메서드: `readLines(java.io.File,java.lang.String)`

**패키지:** `nexcore.framework.core.util`  
**소속 클래스:** `nexcore.framework.core.util.FileUtils`  
**멤버 종류:** `메서드`

```java
public static List < String > readLines ( File file, String encoding) throws IOException


Reads the contents of a file line by line to a List of Strings. The file is always closed.

#### 26.45.1 매개변수

- `file` - the file to read, must not be `null`
- `encoding` - the encoding to use, `null` means platform default

#### 26.45.2 반환값

- the list of Strings representing each line in the file, never `null`

#### 26.45.3 예외

- `IOException` - in case of an I/O error
- `UnsupportedCharsetException` - thrown instead of `.UnsupportedEncodingException` in version 2.2 if the encoding is not supported.

### 26.45 메서드: `readLines(java.io.File,java.lang.String)`
#### 26.45.4 도입 버전

- 1.1
```

## P31 · rm-readLines · remove

- 지시: readLines 메서드 설명은 문서에서 빼 줘
- 청크 `fec468a2-f267-4a17-95ad-db91eff1844e` (32.51 메서드: `readLines(java.io.InputStream,java.lang.String)`) · 정답 라벨 **D** · 종류 `delete_chunk`
- 근거: 청크 삭제: readLines
- 자동 점검: OK 청크 삭제(제목에 대상)
- 수용: x

```diff
(청크 전체 삭제)
## 32. 클래스: nexcore.framework.core.util.IOUtils
### 32.51 메서드: `readLines(java.io.InputStream,java.lang.String)`

**패키지:** `nexcore.framework.core.util`  
**소속 클래스:** `nexcore.framework.core.util.IOUtils`  
**멤버 종류:** `메서드`

```java
public static List < String > readLines ( InputStream input, String encoding) throws IOException

Gets the contents of an `InputStream` as a list of Strings,
one entry per line, using the specified character encoding.

Character encoding names can be found at
IANA.

This method buffers the input internally, so there is no need to use a
`BufferedInputStream`.

#### 32.51.1 매개변수

- `input` - the `InputStream` to read from, not null
- `encoding` - the encoding to use, null means platform default

#### 32.51.2 반환값

- the list of Strings, never null

### 32.51 메서드: `readLines(java.io.InputStream,java.lang.String)`
#### 32.51.3 예외

- `NullPointerException` - if the input is null
- `IOException` - if an I/O error occurs
- `UnsupportedCharsetException` - thrown instead of `.UnsupportedEncodingException` in version 2.2 if the
encoding is not supported.

#### 32.51.4 도입 버전

- 1.1
```

## P32 · rm-readLines · remove

- 지시: readLines 메서드 설명은 문서에서 빼 줘
- 청크 `432ffd5e-81e8-4547-a8b5-431282190e5e` (32.50 메서드: `readLines(java.io.InputStream,java.nio.charset.Charset)`) · 정답 라벨 **D** · 종류 `delete_chunk`
- 근거: 청크 삭제: readLines
- 자동 점검: OK 청크 삭제(제목에 대상)
- 수용: x

```diff
(청크 전체 삭제)
## 32. 클래스: nexcore.framework.core.util.IOUtils
### 32.50 메서드: `readLines(java.io.InputStream,java.nio.charset.Charset)`

**패키지:** `nexcore.framework.core.util`  
**소속 클래스:** `nexcore.framework.core.util.IOUtils`  
**멤버 종류:** `메서드`

```java
public static List < String > readLines ( InputStream input, Charset encoding) throws IOException

Gets the contents of an `InputStream` as a list of Strings,
one entry per line, using the specified character encoding.

This method buffers the input internally, so there is no need to use a
`BufferedInputStream`.

#### 32.50.1 매개변수

- `input` - the `InputStream` to read from, not null
- `encoding` - the encoding to use, null means platform default

#### 32.50.2 반환값

- the list of Strings, never null

#### 32.50.3 예외

- `NullPointerException` - if the input is null
- `IOException` - if an I/O error occurs

### 32.50 메서드: `readLines(java.io.InputStream,java.nio.charset.Charset)`
#### 32.50.4 도입 버전

- 2.3
```

## P33 · rm-callAsyncServiceAfterCommit · remove

- 지시: callAsyncServiceAfterCommit 에 대한 내용을 삭제해
- 청크 `42015e7b-63cd-49fc-a988-85dda66dc3e1` (35. PUFepCallSample2.pmEDU04R0051 — FEP 비동기 AfterCommit + 후속 비동기사비스) · 정답 라벨 **A** · 종류 `edit`
- 근거: 내용 삭제: callAsyncServiceAfterCommit · 일부 작업을 버림 —  · 1개 인용 실패
- 자동 점검: 확인 필요 — 대상이 아직 남음 · 지운 글자 110 (원문의 8%)
- 수용: o

```diff
@@ -21 +21 @@
 1. 하드코딩 JSON → `setITFC_ID` + `setOTSD_CHNL_TMER_KEY_VAL`
 2. `sendOutboundAfterCommit`
-3. `callAsyncServiceAfterCommit("EDU04C0050", requestData, onlineCtx)`
+3. 
 4. `IS_ERROR=정상`
 
@@ -45 +45 @@
 ### 35.7 연관 호출
 
-- `sendOutboundAfterCommit`, `callAsyncServiceAfterCommit("EDU04C0050")`
+- `sendOutboundAfterCommit`, 
 - (주석) `callBatchJobAfterCommit`
```

## P34 · rm-callAsyncServiceAfterCommit · remove

- 지시: callAsyncServiceAfterCommit 에 대한 내용을 삭제해
- 청크 `9549695b-78c0-4194-b67c-3879dd0508c6` (온라인 서비스 개발) · 정답 라벨 **D** · 종류 `edit`
- 근거: 내용 삭제: callAsyncServiceAfterCommit · 일부 작업을 버림 —  · 1개 인용 실패
- 자동 점검: OK 대상이 사라짐 · 지운 글자 125 (원문의 25%)
- 수용: o (줄바꿈이 추가되어 표가깨짐)

```diff
@@ -11 +11 @@
 | --- | --- | --- |
 | 동기 | callServiceByRequired | 수동 거래 동일 트랜잭션으로 호출함. 수동 거래 정상 리턴되더라도 기동거래에서 에러 발생하면 수동 거래도 롤백 |
-| 비동기 | callAsyncServiceAfterCommit | 기동 거래와 독립적으로 별도 쓰레드에서 수동 거래 호출 기동 거래가 commit 완료되면 수동 거래 실행됨 기동 거래 롤백 되면 수동 거래 실행하지 않음 |
+
 | 비동기 | callAsyncServiceNow | 이 메소드 호출 즉시 별도 쓰레드에서 수동 거래 실행됨 기동 거래 정상/에러 여부에 상관없이 수동 거래 실행됨 |
```

## P35 · rm-callAsyncServiceAfterCommit · remove

- 지시: callAsyncServiceAfterCommit 에 대한 내용을 삭제해
- 청크 `86fe0e0c-19c5-473b-b87b-55f41d7530a7` (3.4.9 연동거래) · 정답 라벨 **D** · 종류 `edit`
- 근거: 내용 삭제: callAsyncServiceAfterCommit · 일부 작업을 버림 —  · 1개 인용 실패
- 자동 점검: OK 대상이 사라짐 · 지운 글자 241 (원문의 13%)
- 수용: o (줄바꿈이 추가되어 표가깨짐)

```diff
@@ -16 +16 @@
 | - | IDataSet callServiceByRequiresNew( String tranCd, IDataSet requestData, IOnlineContext onlineCtx) | 동기식 연동 거래 호출 (트랜잭션 분리 됨) tranCd : 거래코드 requestData : IDataSet타입의 요청데이터 수동거래에서 리턴하는 DataSet 이 callServiceByRequiresNew 의 리턴값으로 리턴됨 |
 | 비동기 | String callAsyncServiceNow( String tranCd, IDataSet requestData, IOnlineContext onlineCtx ) | 비동기식 연동거래 호출. 기동 거래와 분리된 별도의 쓰레드 또는 인스턴스 또는 서버에서 실행됨. 호출 즉시 큐에 요청이 들어가 실행을 준비함. 리턴값 : 수동 거래 GUID |
-| - | String callAsyncServiceAfterCommit( String tranCd, IDataSet requestData, IOnlineContext onlineCtx ) | 비동기식 연동거래 호출. 기동 거래와 분리된 별도의 쓰레드 또는 인스턴스 또는 서버에서 실행됨. 기동 거래가 정상 commit 된 후에 수동 거래 실행됨. 기동 거래 에러 발생시 수동 거래도 실행 취소됨. 리턴값 : 수동 거래 GUID |
+
 | - | String reserveCallTimerService( String tranCd, int delaySeconds, IDataSet requestData, IOnlineContext onlineCtx ) | 본 서비스와 독립적으로 수동 서비스를 지연시간 대기 후 실행하도록 호출 - 비동기 연동거래 키값이 리턴됩니다. - 대외 async 거래후 응답전문 미수신시 Timeout 처리를 위해 사용됩니다. tranCd : 거래코드 delaySeconds : 지연시간(초) requestData : IDataSet타입의 요청데이터 onlineCtx : 요청 컨텍스트 정보 리턴값 : 비동기 호출 키 |
 | - | boolean cancelCallTimerService ( String delayAsyncId, IOnlineContext onlineCtx ) | 지연시간 대기중인 지연비동기 연동거래 실행을 취소 - 리턴값이 boolean이며, true는 취소성공이고, false는 취소실패(수행중, 수행완료)입니다. [파라미터] delayAsyncId : 비동기 호출키 onlineCtx : 요청 컨텍스트 정보 |
```

## P36 · rm-addErrorCountAmount · remove

- 지시: addErrorCountAmount 설명을 지워
- 청크 `931b66f3-1e2f-493c-8c61-c33e19d35856` (10. BEDU2002 — File → DB (고정길이 + 에러재처리)) · 정답 라벨 **M** · 종류 `edit`
- 근거: 내용 삭제: addErrorCountAmount
- 자동 점검: OK 대상이 사라짐 · 지운 글자 61 (원문의 2%)
- 수용: x

```diff
@@ -65 +65 @@
 public void handleErrorRecord(IRecord record, long position, Throwable e) {
     log.error("[에러발생] (" + position + ") (" + e.getMessage() + ") " + record.toString().trim());
-    context.addErrorCountAmount(1, record.getDouble("ACC_BLNC"));
+    
 }
```

## P37 · rm-addErrorCountAmount · remove

- 지시: addErrorCountAmount 설명을 지워
- 청크 `0641f105-d475-4179-a0dd-8471a61437bc` (6.86 메서드: `addErrorCountAmount(long,java.math.BigDecimal)`) · 정답 라벨 **D** · 종류 `edit`
- 근거: 내용 삭제: addErrorCountAmount
- 자동 점검: 확인 필요 — 대상이 아직 남음 · 지운 글자 75 (원문의 7%)
- 수용: ☐

```diff
@@ -6 +6 @@
 **멤버 종류:** `메서드`
 
-```java
-public void addErrorCountAmount (long count, BigDecimal amount)
-```
+
 
 에러건수, 금액 증가
```

## P38 · rm-addErrorCountAmount · remove

- 지시: addErrorCountAmount 설명을 지워
- 청크 `5b1ab2f4-ed4b-4c62-a29a-ee4cd71bdef9` (12.46 메서드: `addErrorCountAmount(long, double)`) · 정답 라벨 **D** · 종류 `edit`
- 근거: 내용 삭제: addErrorCountAmount · 일부 작업을 버림 —  · 2개 인용 실패
- 자동 점검: 확인 필요 — 대상이 아직 남음 · 지운 글자 130 (원문의 14%)
- 수용: x

```diff
@@ -6 +6 @@
 **멤버 종류:** `메서드`
 
-```java
-void addErrorCountAmount(long count, double amount)
-```
 
+
 에러 건수와 금액을 증가시킨다.
 
@@ -23 +21 @@
 **멤버 종류:** `메서드`
 
-```java
-void addErrorCountAmount(long count, BigDecimal amount)
-```
+
 
 에러 건수와 금액을 증가시킨다.
```

## P39 · rm-addErrorCountAmount · remove

- 지시: addErrorCountAmount 설명을 지워
- 청크 `6e858da9-a36f-4f3b-a1c9-c124302228b7` (7.9.3.1.2 IBatchContext 의 메소드) · 정답 라벨 **D** · 종류 `edit`
- 근거: 내용 삭제: addErrorCountAmount
- 자동 점검: OK 대상이 사라짐 · 지운 글자 172 (원문의 26%)
- 수용: o

```diff
@@ -7 +7 @@
 | --- | --- |
 | void **addSuccessCountAmount**( long count, double amount) | 정상 건수, 금액 증가 본처리 RecordHandler 또는 loop 수행 중에 정상 처리된 건수와 금액 값을 BatchContext 메모리에 증가시킵니다. 배치 주트랜잭션 커밋 시에 이 값이 배치실행로그 테이블에 update 됩니다. (txCommit()을 수동으로 하거나 AutoCommitRecordHandler에 의해 커밋하는 시점) 만일 에러 발생으로 롤백되는 경우 배치실행로그 테이블에 기록되어 있는 직전 정상 커밋된 값을 다시 BatchContext의 메모리에 load 합니다. |
-| void **addErrorCountAmount**( long count, double amount) | 에러 건수, 금액 증가 에러 발생하여 별도 처리한 건수, 금액 정보를 이 API를 사용하여 BatchContext 메모리에 저장한 후 txCommit 시에 배치실행로그에 update되도록 합니다. |
+
```

## P40 · rm-closeQuietly · remove

- 지시: closeQuietly 에 관한 설명을 삭제해 주세요
- 청크 `a1390050-e605-4e41-b026-9e02537ddfbb` (32. 클래스: nexcore.framework.core.util.IOUtils) · 정답 라벨 **A** · 종류 `edit`
- 근거: 내용 삭제: closeQuietly · 일부 작업을 버림 —  · 2개 인용 실패
- 자동 점검: 확인 필요 — 대상이 아직 남음 · 지운 글자 125 (원문의 9%)
- 수용: o

```diff
@@ -61 +61 @@
 
-### 32.2 메서드: `closeQuietly(java.io.Reader)`
-#### 32.2.1 매개변수
 
-- `input` - the Reader to close, may be null or already closed
```

## P41 · rm-closeQuietly · remove

- 지시: closeQuietly 에 관한 설명을 삭제해 주세요
- 청크 `7e13de0e-d646-484f-bd4d-09aa924866b7` (32.7 메서드: `closeQuietly(java.io.Closeable...)`) · 정답 라벨 **D** · 종류 `delete_chunk`
- 근거: 청크 삭제: closeQuietly
- 자동 점검: OK 청크 삭제(제목에 대상)
- 수용: x

```diff
(청크 전체 삭제)
## 32. 클래스: nexcore.framework.core.util.IOUtils
### 32.7 메서드: `closeQuietly(java.io.Closeable...)`

**패키지:** `nexcore.framework.core.util`  
**소속 클래스:** `nexcore.framework.core.util.IOUtils`  
**멤버 종류:** `메서드`

```java
public static void closeQuietly ( Closeable ... closeables)-```

Closes a `Closeable` unconditionally.

Equivalent to `Closeable.close()`, except any exceptions will be ignored.

This is typically used in finally blocks to ensure that the closeable is closed
even if an Exception was thrown before the normal close statement was reached.

It should not be used to replace the close statement(s)
which should be present for the non-exceptional case.

It is only intended to simplify tidying up where normal processing has already failed
and reporting close failure as well is not necessary or useful.

Example code:

```java
Closeable closeable = null;
try {
closeable = new FileReader("foo.txt");
// processing using the closeable; may throw an Exception
closeable.close(); // Normal close - exceptions not ignored
} catch (Exception e) {
// error handling
} finally {
IOUtils.closeQuietly(closeable); // In case normal close was skipped due to Exception
}
```

Closing all streams:

```java
try {
return IOUtils.copy(inputStream, outputStream);
} finally {
IOUtils.closeQuietly(inputStream, outputStream);
}


### 32.7 메서드: `closeQuietly(java.io.Closeable...)`
#### 32.7.1 매개변수

- `closeables` - the objects to close, may be null or already closed

#### 32.7.2 도입 버전

- 2.5

#### 32.7.3 참고

- `closeQuietly(Closeable)`
```

## P42 · rm-closeQuietly · remove

- 지시: closeQuietly 에 관한 설명을 삭제해 주세요
- 청크 `4b840f14-7b40-4159-b5d2-8aabcd811d7d` (32.6 메서드: `closeQuietly(java.io.Closeable)`) · 정답 라벨 **D** · 종류 `delete_chunk`
- 근거: 청크 삭제: closeQuietly
- 자동 점검: OK 청크 삭제(제목에 대상)
- 수용: o

```diff
(청크 전체 삭제)
## 32. 클래스: nexcore.framework.core.util.IOUtils
### 32.6 메서드: `closeQuietly(java.io.Closeable)`

**패키지:** `nexcore.framework.core.util`  
**소속 클래스:** `nexcore.framework.core.util.IOUtils`  
**멤버 종류:** `메서드`

```java
public static void closeQuietly ( Closeable closeable)
```

Closes a `Closeable` unconditionally.

Equivalent to `Closeable.close()`, except any exceptions will be ignored. This is typically used in
finally blocks.

Example code:

```java
Closeable closeable = null;
try {
closeable = new FileReader("foo.txt");
// process closeable
closeable.close();
} catch (Exception e) {
// error handling
} finally {
IOUtils.closeQuietly(closeable);
}
```

Closing all streams:

```java
try {
return IOUtils.copy(inputStream, outputStream);
} finally {
IOUtils.closeQuietly(inputStream);
IOUtils.closeQuietly(outputStream);
}
```

### 32.6 메서드: `closeQuietly(java.io.Closeable)`
#### 32.6.1 매개변수

- `closeable` - the objects to close, may be null or already closed

#### 32.6.2 도입 버전

- 2.0
```

## P43 · rm-closeQuietly · remove

- 지시: closeQuietly 에 관한 설명을 삭제해 주세요
- 청크 `cc2aa6a1-8a17-4bdc-bd41-0c5b8e46791c` (32.5 메서드: `closeQuietly(java.io.OutputStream)`) · 정답 라벨 **D** · 종류 `delete_chunk`
- 근거: 청크 삭제: closeQuietly
- 자동 점검: OK 청크 삭제(제목에 대상)
- 수용: o

```diff
(청크 전체 삭제)
## 32. 클래스: nexcore.framework.core.util.IOUtils
### 32.5 메서드: `closeQuietly(java.io.OutputStream)`

**패키지:** `nexcore.framework.core.util`  
**소속 클래스:** `nexcore.framework.core.util.IOUtils`  
**멤버 종류:** `메서드`

```java
public static void closeQuietly ( OutputStream output)
```

Closes an `OutputStream` unconditionally.

Equivalent to `OutputStream.close()`, except any exceptions will be ignored.
This is typically used in finally blocks.

Example code:

```java
byte[] data = "Hello, World".getBytes();
OutputStream out = null;
try {
out = new FileOutputStream("foo.txt");
out.write(data);
out.close(); //close errors are handled
} catch (IOException e) {
// error handling
} finally {
IOUtils.closeQuietly(out);
}
```

#### 32.5.1 매개변수

- `output` - the OutputStream to close, may be null or already closed
```

## P44 · rm-closeQuietly · remove

- 지시: closeQuietly 에 관한 설명을 삭제해 주세요
- 청크 `92ed84cd-0d44-4793-a6ef-15ce000a5813` (32.10 메서드: `closeQuietly(java.net.ServerSocket)`) · 정답 라벨 **D** · 종류 `delete_chunk`
- 근거: 청크 삭제: closeQuietly
- 자동 점검: OK 청크 삭제(제목에 대상)
- 수용: o

```diff
(청크 전체 삭제)
## 32. 클래스: nexcore.framework.core.util.IOUtils
### 32.10 메서드: `closeQuietly(java.net.ServerSocket)`

**패키지:** `nexcore.framework.core.util`  
**소속 클래스:** `nexcore.framework.core.util.IOUtils`  
**멤버 종류:** `메서드`

```java
public static void closeQuietly ( ServerSocket sock)
```

Closes a `ServerSocket` unconditionally.

Equivalent to `ServerSocket.close()`, except any exceptions will be ignored.
This is typically used in finally blocks.

Example code:

```java
ServerSocket socket = null;
try {
socket = new ServerSocket();
// process socket
socket.close();
} catch (Exception e) {
// error handling
} finally {
IOUtils.closeQuietly(socket);
}
```

#### 32.10.1 매개변수

- `sock` - the ServerSocket to close, may be null or already closed

#### 32.10.2 도입 버전

- 2.2
```

## P45 · note-getJobExecutionId · add_note

- 지시: getJobExecutionId 에 '같은 Job 을 재실행하면 실행 ID 가 새로 발급된다' 는 주의 사항을 추가해
- 청크 `eaf95617-d4b1-4c94-b4ba-6bc558d0f993` (12.1 메서드: `getBatchObject()`) · 정답 라벨 **A** · 종류 `edit`
- 근거: 주의 사항 추가: getJobExecutionId · 다듬은 문장이 검사를 통과하지 못해 지시문 문장을 그대로 넣음 · 기준 문장·주변 글에 없는 말이 많다: 동일한, 경우, 발급됩니다
- 자동 점검: OK 주의 블록 있음
- 수용: o

```diff
@@ -60 +60 @@
 배치 JOB EXECUTION ID를 반환한다.
 
+> **주의** — 같은 Job 을 재실행하면 실행 ID 가 새로 발급된다
+
 ### 12.4 메서드: `getJobExecutionId()`
 #### 12.4.1 반환값
```

## P46 · note-CSVFileOption · add_note

- 지시: CSVFileOption 에 '인코딩을 지정하지 않으면 서버 기본 인코딩이 쓰인다' 는 주의 사항을 추가해
- 청크 `a1bfa375-18c7-4123-bb94-70c416e7c40f` (7. 클래스: nexcore.framework.biz.online.SQLIO.CSVFileOption) · 정답 라벨 **D** · 종류 `edit`
- 근거: 주의 사항 추가: CSVFileOption · 다듬은 문장이 검사를 통과하지 못해 지시문 문장을 그대로 넣음 · 기준 문장·주변 글에 없는 말이 많다: 않을, 경우, 사용됩니다
- 자동 점검: OK 주의 블록 있음
- 수용: o

```diff
@@ -34 +34 @@
 인코딩 (미지정시 시스템 기본 인코딩 사용)
 
+> **주의** — 인코딩을 지정하지 않으면 서버 기본 인코딩이 쓰인다
+
 ### 7.2 메서드: `getEncoding()`
 #### 7.2.1 반환값
```

## P47 · note-dbSelectMulti · add_note

- 지시: dbSelectMulti 와 관련해 '조회 건수가 많으면 메모리가 부족해질 수 있다' 는 주의 사항을 추가해
- 청크 `39e1aa81-f22d-4632-b749-f2a17d2ba332` (16. BEDU4001 — 공유 FM + 배치 SELECT) · 정답 라벨 **M** · 종류 `edit`
- 근거: 주의 사항 추가: dbSelectMulti
- 자동 점검: OK 주의 블록 있음
- 수용: o

```diff
@@ -74 +74 @@
 
 ---
+
+> **주의** — `dbSelectMulti` 호출 시 조회 건수가 많으면 메모리가 부족해질 수 있습니다.
```

## P48 · note-copyFile · add_note

- 지시: copyFile 에 '대상 파일이 이미 있으면 덮어쓴다' 는 주의 사항을 넣어줘
- 청크 `2489489b-d7dd-4b4b-8d7c-d5930915eb2e` (26.23 메서드: `copyFile(java.io.File,java.io.File)`) · 정답 라벨 **D** · 종류 `edit`
- 근거: 주의 사항 추가: copyFile · 다듬은 문장이 검사를 통과하지 못해 지시문 문장을 그대로 넣음 · 기준 문장·주변 글에 없는 말이 많다: 존재할, 경우, 덮어씁니다
- 자동 점검: OK 주의 블록 있음
- 수용: o

```diff
@@ -17 +17 @@
 method will overwrite it.
 
+> **주의** — 대상 파일이 이미 있으면 덮어쓴다
+
 Note: This method tries to preserve the file's last
 modified date/times using `File.setLastModified(long)`, however
```

## P49 · note-cancelCallTimerService · add_note

- 지시: cancelCallTimerService 에 '취소에 실패하면 false 를 돌려주므로 반드시 결과를 확인해야 한다' 는 주의 사항을 추가해
- 청크 `86fe0e0c-19c5-473b-b87b-55f41d7530a7` (3.4.9 연동거래) · 정답 라벨 **D** · 종류 `edit`
- 근거: 주의 사항 추가: cancelCallTimerService
- 자동 점검: OK 주의 블록 있음
- 수용: x

```diff
@@ -20 +20 @@
 | - | boolean cancelCallTimerService ( String delayAsyncId, IOnlineContext onlineCtx ) | 지연시간 대기중인 지연비동기 연동거래 실행을 취소 - 리턴값이 boolean이며, true는 취소성공이고, false는 취소실패(수행중, 수행완료)입니다. [파라미터] delayAsyncId : 비동기 호출키 onlineCtx : 요청 컨텍스트 정보 |
 
+> **주의** — 리턴값이 boolean이며, 취소 실패 시 false를 반환하므로 반드시 결과를 확인해야 합니다.
+
 각 호출의 예는 다음과 같습니다.
```

## P50 · note-sendDummyReturnNow · add_note

- 지시: sendDummyReturnNow 에 '비동기 요청 거래에서만 호출해야 한다' 는 주의 사항을 추가해
- 청크 `615b7ce8-98b6-42f4-b545-9d84007bc63a` (3.9.4.6 sendDummyReturn) · 정답 라벨 **D** · 종류 `edit`
- 근거: 주의 사항 추가: sendDummyReturnNow
- 자동 점검: OK 주의 블록 있음
- 수용: o

```diff
@@ -4 +4 @@
 비동기 요청 전문 전송 후 요청거래는 최초 채널로 아무 응답을 주지 않고 마칩니다. 비동기 요청 거래에서 `sendDummyReturn`을 호출하면 즉시 Http 세션에 더미 응답 데이터를 전송하여 MCI/FEP/EAI로부터 받은 Http 세션을 풀어줍니다.
 
+> **주의** — 이 함수는 비동기 요청 거래에서만 호출해야 합니다.
+
 | **API** |
 | --- |
```

## P51 · rev-dbSelectSingleLockRetry · revise

- 지시: dbSelectSingleLockRetry 의 재시도 간격은 0.5초가 아니라 1초다
- 청크 `bd5a9e46-8365-4f19-bd6d-2ccce96598e6` (1.3 메서드: `dbSelectSingleLockRetry(java.lang.String,int,java.lang.Object,java.lang.String,nexcore.framework.core.data.IOnlineContext)`) · 정답 라벨 **D** · 종류 `edit`
- 근거: 설명 수정: dbSelectSingleLockRetry
- 자동 점검: OK 새 값이 들어감
- 수용: o

```diff
@@ -10 +10 @@
 ```
 
-SELECT FOR UPDATE NOWAIT 조회에서 SQL 오류 코드 54 또는 30006으로 잠금 획득에 실패한 경우 재시도한다. 이 경우 여기에 지정된 `tryCnt` 만큼 재시도하여 lock 획득을 시도하며, `tryCnt` 시도 후에도 lock 획득에 실패할 경우 에러가 발생한다. 재시도 시 0.5 초 sleep 후 재시도한다.
+SELECT FOR UPDATE NOWAIT 조회에서 SQL 오류 코드 54 또는 30006으로 잠금 획득에 실패한 경우 재시도한다. 이 경우 여기에 지정된 `tryCnt` 만큼 재시도하여 lock 획득을 시도하며, `tryCnt` 시도 후에도 lock 획득에 실패할 경우 에러가 발생한다. 재시도 시 1초 sleep 후 재시도한다.
 
 #### 1.3.1 매개변수
```

## P52 · rev-dbSelectSingleLockRetry · revise

- 지시: dbSelectSingleLockRetry 의 재시도 간격은 0.5초가 아니라 1초다
- 청크 `503fa0e6-fc3f-4d3b-a278-bb4f051846c0` (1.2 메서드: `dbSelectSingleLockRetry(java.lang.String,int,java.lang.Object,nexcore.framework.core.data.IOnlineContext)`) · 정답 라벨 **D** · 종류 `edit`
- 근거: 설명 수정: dbSelectSingleLockRetry
- 자동 점검: 확인 필요 — 새 값 "1초" 이 없음
- 수용: o

```diff
@@ -10 +10 @@
 ```
 
-SELECT FOR UPDATE NOWAIT 조회에서 SQL 오류 코드 54 또는 30006으로 잠금 획득에 실패한 경우 재시도한다. 이 경우 여기에 지정된 `tryCnt` 만큼 재시도하여 lock 획득을 시도하며, `tryCnt` 시도 후에도 lock 획득에 실패할 경우 에러가 발생한다. 재시도 시 0.5 초 sleep 후 재시도한다.
+SELECT FOR UPDATE NOWAIT 조회에서 SQL 오류 코드 54 또는 30006으로 잠금 획득에 실패한 경우 재시도한다. 이 경우 여기에 지정된 `tryCnt` 만큼 재시도하여 lock 획득을 시도하며, `tryCnt` 시도 후에도 lock 획득에 실패할 경우 에러가 발생한다. 재시도 시 1 초 sleep 후 재시도한다.
 
 #### 1.2.1 매개변수
```

## P53 · rev-dbSelectSingleLockRetry · revise

- 지시: dbSelectSingleLockRetry 의 재시도 간격은 0.5초가 아니라 1초다
- 청크 `500b1092-ace6-42ed-9a9f-a13785016913` (1.5 메서드: `dbSelectSingleLockRetry(java.lang.String,int,int,java.lang.Object,java.lang.String,nexcore.framework.core.data.IOnlineContext)`) · 정답 라벨 **D** · 종류 `edit`
- 근거: 설명 수정: dbSelectSingleLockRetry
- 자동 점검: OK 새 값이 들어감
- 수용: x

```diff
@@ -10 +10 @@
 ```
 
-SELECT FOR UPDATE NOWAIT 조회에서 SQL 오류 코드 54 또는 30006으로 잠금 획득에 실패한 경우 재시도한다. 이 경우 여기에 지정된 `tryCnt` 만큼 재시도하여 lock 획득을 시도하며, `tryCnt` 시도 후에도 lock 획득에 실패할 경우 에러가 발생한다. 재시도 시 `sleepMillSec` 밀리초 sleep 후 재시도한다.
+SELECT FOR UPDATE NOWAIT 조회에서 SQL 오류 코드 54 또는 30006으로 잠금 획득에 실패한 경우 재시도한다. 이 경우 여기에 지정된 `tryCnt` 만큼 재시도하여 lock 획득을 시도하며, `tryCnt` 시도 후에도 lock 획득에 실패할 경우 에러가 발생한다. 재시도 시 1초 sleep 후 재시도한다.
 
 #### 1.5.1 매개변수
```

## P54 · rev-dbSelectSingleLockRetry · revise

- 지시: dbSelectSingleLockRetry 의 재시도 간격은 0.5초가 아니라 1초다
- 청크 `02734e5d-aa71-44e7-b748-ae49b55844cd` (온라인 서비스 개발) · 정답 라벨 **D** · 종류 `edit`
- 근거: 설명 수정: dbSelectSingleLockRetry
- 자동 점검: 확인 필요 — 새 값 "1초" 이 없음
- 수용: o

```diff
@@ -5 +5 @@
 - **For Update Lock Retry**
 - SELECT FOR UPDATE NOWAIT 쿼리시 해당 ROW를 이미 다른 세션이 Lock 잡고 있으면 Lock 에러 발생
-- dbSelectSingleLockRetry 안에서는 sleep 0.5 초 후 지정된 횟수만큼 재시도 함
+- dbSelectSingleLockRetry 안에서는 sleep 1 초 후 지정된 횟수만큼 재시도 함
 
 [도형 그룹]
```

## P55 · rev-dbSelectSingleLockRetry · revise

- 지시: dbSelectSingleLockRetry 의 재시도 간격은 0.5초가 아니라 1초다
- 청크 `38628c35-66d2-4f80-a6fb-193a3cc27110` (3.5.3.4 DBMS의 오류 메시지 조회할 경우 잘못 사용한 예) · 정답 라벨 **D** · 종류 `edit`
- 근거: 설명 수정: dbSelectSingleLockRetry
- 자동 점검: OK 새 값이 들어감
- 수용: x

```diff
@@ -6 +6 @@
 ##### 3.5.3.5 SELECT FOR UPDATE NOWAIT로 조회 시 LOCK 획득에 실패
 
-`SELECT FOR UPDATE NOWAIT`로 조회 시 LOCK 획득에 실패하는 경우 `DataException` 에러가 발생합니다. 이 경우 **dbSelectSingleLockRetry** (`String stmtName`, `int tryCnt`, `Object param`, `IOnlineContext onlineCtx`) 메소드를 사용하여 지정한 횟수(`tryCnt`)만큼 재시도해 LOCK 획득 시도를 합니다. 지정한 횟수(`tryCnt`)만큼 시도 후에도 LOCK 획득 실패한 경우 에러가 발생합니다.
+`SELECT FOR UPDATE NOWAIT`로 조회 시 LOCK 획득에 실패하는 경우 `DataException` 에러가 발생합니다. 이 경우 **dbSelectSingleLockRetry** (`String stmtName`, `int tryCnt`, `Object param`, `IOnlineContext onlineCtx`) 메소드를 사용하여 1초 간격으로 지정한 횟수(`tryCnt`)만큼 재시도해 LOCK 획득 시도를 합니다. 지정한 횟수(`tryCnt`)만큼 시도 후에도 LOCK 획득 실패한 경우 에러가 발생합니다.
 
```

## P56 · rev-getSharedQueue · revise

- 지시: getSharedQueue 의 Timeout 기본값은 10초가 아니라 30초다
- 청크 `92833882-c369-44c9-85b4-7fe9248b8e2d` (21. BEDU5002 — 병렬 SharedQueue Processor (단일 큐)) · 정답 라벨 **M** · 종류 `edit`
- 근거: 설명 수정: getSharedQueue · 일부 작업을 버림 — 대상(getSharedQueue)과 관련된 구간이 아니다 — 인용에도 그 섹션 제목에도 대상이 없다
- 자동 점검: 확인 필요 — 새 값 "30초" 이 없음
- 수용: x

```diff
@@ -36 +36 @@
 ### 21.4 처리 흐름
 
-1. getSharedQueue(id, 10) → Receiver
+1. getSharedQueue(id, 30) → Receiver
 2. receive(makeHandler) 루프
 3. 행마다 INSERT + 성공 정산
@@ -48 +48 @@
     this.sharedQueueId = StringUtils.nvl(context.getInParameter("QUEUE_ID"),
             StringUtils.nvl(context.getInParameter("PARENT.JOB_EXE_ID"), "DEFAULT"));
-    this.sharedQueue = getSharedQueue(this.sharedQueueId, 10);
+    this.sharedQueue = getSharedQueue(this.sharedQueueId, 30);
     this.sharedQueueReceiver = sharedQueue.createReceiver(context);
     this.commitCount = profile == null || profile.getCommitCount() == 0 ? 100 : profile.getCommitCount();
```

## P57 · rev-getSharedQueue · revise

- 지시: getSharedQueue 의 Timeout 기본값은 10초가 아니라 30초다
- 청크 `2fae6c21-087b-4138-8fb7-498938647087` (4. AutoCommitRecordHandler / AbsRecordHandler / SharedQueue 옵션) · 정답 라벨 **D** · 종류 `edit`
- 근거: 설명 수정: getSharedQueue
- 자동 점검: OK 새 값이 들어감
- 수용: o

```diff
@@ -25 +25 @@
 | `existsSharedQueue` / `removeSharedQueue` | 동일 ID 존재 시 삭제 후 재생성 |
 | `createSender(context)` / `send(record, timeoutMs)` / `finish()` | 송신 |
-| `getSharedQueue(id, waitSec)` | Processor가 대기 후 획득 |
+| `getSharedQueue(id, waitSec)` | Processor가 대기 후 획득 (기본 30초) |
 | `createReceiver` / `receive(handler)` | 수신 루프 |
 | `waitForAllReceiveEnd()` | Reader `afterExecute`에서 Processor 소비 대기 |
```

## P58 · rev-getSharedQueue · revise

- 지시: getSharedQueue 의 Timeout 기본값은 10초가 아니라 30초다
- 청크 `49dfe3c8-2d9c-43c7-8c39-b26d43537bcd` (7.11.4.2 SharedMap 사용) · 정답 라벨 **M** · 종류 `edit`
- 근거: 설명 수정: getSharedQueue
- 자동 점검: 확인 필요 — 새 값 "30초" 이 없음
- 수용: x

```diff
@@ -16 +16 @@
         // 공유 Map Receiver
         sharedQueueId = context.getInParameter("SHARED_QUEUE_ID");
-        sharedQueue = getSharedQueue(sharedQueueId, 10);
+        sharedQueue = getSharedQueue(sharedQueueId, 30);
         sharedQueueReceiver = sharedQueue.createReceiver(context);
     }
```

## P59 · rev-callAsyncServiceNow · revise

- 지시: callAsyncServiceNow 는 기동 거래가 에러로 끝나면 수동 거래를 실행하지 않는다
- 청크 `f98c4db6-0d02-4c7f-bacc-fbd63a00ec1e` (6.8 메서드: `callAsyncServiceNow(java.lang.String,nexcore.framework.core.data.IDataSet,nexcore.framework.core.data.IOnlineContext)`) · 정답 라벨 **D** · 종류 `edit`
- 근거: 설명 수정: callAsyncServiceNow
- 자동 점검: OK 새 값이 들어감
- 수용: x

```diff
@@ -10 +10 @@
 
-연동서비스(거래)를 위해 피연동 서비스 비동기 호출 (즉시)
+기동 거래가 에러로 끝나면 수동 거래를 실행하지 않는다
 
 Calls asynchronously, the specified service as associated transaction.
```

## P60 · rev-callAsyncServiceNow · revise

- 지시: callAsyncServiceNow 는 기동 거래가 에러로 끝나면 수동 거래를 실행하지 않는다
- 청크 `040e6152-45aa-4d6f-a075-46d3fae18d5b` (3.4.9.5 비동기 연동거래 수동거래의 응답 Merge) · 정답 라벨 **A** · 종류 `edit`
- 근거: 설명 수정: callAsyncServiceNow
- 자동 점검: 확인 필요 — 새 값 "기동 거래가 에러로 끝나면 수동 거래를 실행하지 않는다" 이 없음
- 수용: o

```diff
@@ -20 +20 @@
 
-callAsyncServiceNow 호출시 각 수동거래를 구분할 수 있는 키 값 (일련번호) 을 CAS_SEQ_NO 로 부여하고, 수동거래들에서는 거래 실행 완료 후 결과 값 DataSet 을 자신에게 할당된 CAS_SEQ_NO 를 키로 하여 resultHolderMap 에 put 합니다.
+callAsyncServiceNow 호출시 기동 거래가 에러로 끝나면 수동 거래를 실행하지 않으며, 각 수동거래를 구분할 수 있는 키 값 (일련번호) 을 CAS_SEQ_NO 로 부여하고, 수동거래들에서는 거래 실행 완료 후 결과 값 DataSet 을 자신에게 할당된 CAS_SEQ_NO 를 키로 하여 resultHolderMap 에 put 합니다.
 
 - 수동 프로그램. 업무 로직 수행 후 결과 저장 부분
```

