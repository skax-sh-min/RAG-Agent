# 6c 수정안 검토 — gemma-4-e4b (.\gemma-4-E4B-it-Q4_K_M.gguf)

각 수정안을 읽고 `수용` 칸에 O(고치지 않고 승인할 만함) / X 를 적는다. 자동 점검은 참고용이다.

발견된 문제는 삭제 요청에서 설명과 코드를 구별하지 못하고 있다.
삭제 대상이 코드에서 사용되는 경우, 코드를 삭제하면 프로그램이 정상적으로 동작하지 않을 수 있으므로 deprecated된 API를 사용하는 예제라는 설명(주석)이 반드시 필요하다.

## P1 · dep-callBatchNow · deprecate

- 지시: callBatchNow 는 더 이상 사용되지 않습니다. 대신 callBatchAfterCommit 을 사용하세요.
- 청크 `b757982f-3d92-4083-96ca-8f5e91294d96` (47. PUSample.pmEDU99R0001 — 온디맨드 배치 호출) · 정답 라벨 **A** · 종류 `edit`
- 근거: 사용 중단 안내: callBatchNow
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -1 +1 @@
 ## 47. PUSample.pmEDU99R0001 — 온디맨드 배치 호출
 
+> **사용 중단** — callBatchNow은 더 이상 사용되지 않으므로 대신 callBatchAfterCommit을 사용해야 합니다.
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
 
+> **사용 중단** — callBatchNow은 더 이상 사용되지 않으므로 대신 callBatchAfterCommit을 사용해야 합니다.
+
 ### 54.1 개요 (키워드: PU, 대량입력, 대량출력, 온디맨드)
```

## P3 · dep-callBatchNow · deprecate

- 지시: callBatchNow 는 더 이상 사용되지 않습니다. 대신 callBatchAfterCommit 을 사용하세요.
- 청크 `0f8458a3-6750-4c6b-8e33-f0e71a3e7511` (배치 어플리케이션 개발) · 정답 라벨 **D** · 종류 `edit`
- 근거: 사용 중단 안내: callBatchNow
- 자동 점검: OK 안내 블록 있음
- 수용: x

```diff
@@ -1 +1 @@
 ## 배치 어플리케이션 개발
 
+> **사용 중단** — callBatchNow은 더 이상 사용되지 않으며, 대신 callBatchAfterCommit을 사용해야 합니다.
+
 ### 온디맨드 배치
```

## P4 · dep-callBatchNow · deprecate

- 지시: callBatchNow 는 더 이상 사용되지 않습니다. 대신 callBatchAfterCommit 을 사용하세요.
- 청크 `c2ff5cf8-fad6-47c0-a585-03fcfcef170f` (7.10.6.1 호출 API) · 정답 라벨 **D** · 종류 `edit`
- 근거: 사용 중단 안내: callBatchNow
- 자동 점검: OK 안내 블록 있음
- 수용: x

```diff
@@ -1 +1 @@
 #### 7.10.6 온라인에서 배치 호출(온디맨드 배치)
+
+> **사용 중단** — callBatchNow은 더 이상 사용되지 않으며, 대신 callBatchAfterCommit을 사용해야 합니다.
+
 ##### 7.10.6.1 호출 API
```

## P5 · dep-callBatchNow · deprecate

- 지시: callBatchNow 는 더 이상 사용되지 않습니다. 대신 callBatchAfterCommit 을 사용하세요.
- 청크 `fb33e957-6e94-485f-8991-4a603c9c5123` (7.10.6 온라인에서 배치 호출(온디맨드 배치)) · 정답 라벨 **A** · 종류 `edit`
- 근거: 사용 중단 안내: callBatchNow
- 자동 점검: OK 안내 블록 있음
- 수용: x

```diff
@@ -1 +1 @@
 ### 7.10 온라인 프레임워크 연계
+
+> **사용 중단** — callBatchNow은 더 이상 사용되지 않으므로 대신 callBatchAfterCommit을 사용하세요.
+
 #### 7.10.6 온라인에서 배치 호출(온디맨드 배치)
```

## P6 · dep-reserveCallTimerService · deprecate

- 지시: reserveCallTimerService 는 이제 사용하지 않습니다.
- 청크 `0a8d1471-9c55-4b65-ab94-7753656ea44e` (6.10 메서드: `callAsyncServiceAfterRollback(java.lang.String,nexcore.framework.core.data.IDataSet,nexcore.framework.core.data.IOnlineContext)`) · 정답 라벨 **A** · 종류 `edit`
- 근거: 사용 중단 안내: reserveCallTimerService
- 자동 점검: OK 안내 블록 있음
- 수용: x

```diff
@@ -1 +1 @@
 ## 6. 클래스: nexcore.framework.biz.online.ProcessUnit
+
+> **사용 중단** — reserveCallTimerService는 더 이상 사용되지 않습니다.
+
 ### 6.10 메서드: `callAsyncServiceAfterRollback(java.lang.String,nexcore.framework.core.data.IDataSet,nexcore.framework.core.data.IOnlineContext)`
```

## P7 · dep-reserveCallTimerService · deprecate

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

## P8 · dep-reserveCallTimerService · deprecate

- 지시: reserveCallTimerService 는 이제 사용하지 않습니다.
- 청크 `69980cfa-57e9-4a6d-a440-56a81d338463` (3.4.9.6 지연 비동기 연동거래) · 정답 라벨 **D** · 종류 `edit`
- 근거: 사용 중단 안내: reserveCallTimerService · 다듬은 문장이 검사를 통과하지 못해 지시문 문장을 그대로 넣음 · 길이가 기준 문장과 크게 다르다(293%)
- 자동 점검: OK 안내 블록 있음
- 수용: x

```diff
@@ -1 +1 @@
 #### 3.4.9 연동거래
+
+> **사용 중단** — reserveCallTimerService은(는) 더 이상 사용되지 않습니다.
+
 ##### 3.4.9.6 지연 비동기 연동거래
```

## P9 · dep-reserveCallTimerService · deprecate

- 지시: reserveCallTimerService 는 이제 사용하지 않습니다.
- 청크 `0f819358-d6d1-45a9-a1b2-b28b8f81937d` (3.9.4.5 수동 타이머 처리 API) · 정답 라벨 **D** · 종류 `edit`
- 근거: 사용 중단 안내: reserveCallTimerService · 다듬은 문장이 검사를 통과하지 못해 지시문 문장을 그대로 넣음 · 길이가 기준 문장과 크게 다르다(240%)
- 자동 점검: OK 안내 블록 있음
- 수용: x

```diff
@@ -1 +1 @@
 #### 3.9.4 API
+
+> **사용 중단** — reserveCallTimerService은(는) 더 이상 사용되지 않습니다.
+
 ##### 3.9.4.5 수동 타이머 처리 API
```

## P10 · dep-OldFileDailyCleaner · deprecate

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

## P11 · dep-OldFileDailyCleaner · deprecate

- 지시: OldFileDailyCleaner 클래스는 폐기되었습니다.
- 청크 `a8b09635-bb5e-43da-92bd-cb33130791c6` (39.18 메서드: `setBeanName(java.lang.String)`) · 정답 라벨 **A** · 종류 `edit`
- 근거: 사용 중단 안내: OldFileDailyCleaner
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -1 +1 @@
 ## 39. 클래스: nexcore.framework.core.util.OldFileDailyCleaner
+
+> **사용 중단** — OldFileDailyCleaner는 더 이상 사용되지 않습니다.
+
 ### 39.18 메서드: `setBeanName(java.lang.String)`
```

## P12 · dep-OldFileDailyCleaner · deprecate

- 지시: OldFileDailyCleaner 클래스는 폐기되었습니다.
- 청크 `75d06572-f85f-42b7-a055-8f1b8531e52e` (39.15 메서드: `getRetainDays()`) · 정답 라벨 **A** · 종류 `edit`
- 근거: 사용 중단 안내: OldFileDailyCleaner
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -1 +1 @@
 ## 39. 클래스: nexcore.framework.core.util.OldFileDailyCleaner
+
+> **사용 중단** — OldFileDailyCleaner는 더 이상 사용되지 않습니다.
+
 ### 39.15 메서드: `getRetainDays()`
```

## P13 · dep-OldFileDailyCleaner · deprecate

- 지시: OldFileDailyCleaner 클래스는 폐기되었습니다.
- 청크 `f4f76bdf-4447-4316-926f-77bdf1f3592e` (39.12 메서드: `setScheduleTime(java.lang.String)`) · 정답 라벨 **A** · 종류 `edit`
- 근거: 사용 중단 안내: OldFileDailyCleaner
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -1 +1 @@
 ## 39. 클래스: nexcore.framework.core.util.OldFileDailyCleaner
+
+> **사용 중단** — OldFileDailyCleaner는 더 이상 사용되지 않습니다.
+
 ### 39.12 메서드: `setScheduleTime(java.lang.String)`
```

## P14 · dep-OldFileDailyCleaner · deprecate

- 지시: OldFileDailyCleaner 클래스는 폐기되었습니다.
- 청크 `7fba6574-240d-4ac8-b192-ec8471be7927` (39.6 메서드: `getFileNameSuffix()`) · 정답 라벨 **A** · 종류 `edit`
- 근거: 사용 중단 안내: OldFileDailyCleaner
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -1 +1 @@
 ## 39. 클래스: nexcore.framework.core.util.OldFileDailyCleaner
+
+> **사용 중단** — OldFileDailyCleaner는 더 이상 사용되지 않습니다.
+
 ### 39.6 메서드: `getFileNameSuffix()`
```

## P15 · dep-OldFileDailyCleaner · deprecate

- 지시: OldFileDailyCleaner 클래스는 폐기되었습니다.
- 청크 `8ab7b1f8-e797-4bb2-902a-68efe8d45d85` (39.3 메서드: `getDirectories()`) · 정답 라벨 **A** · 종류 `edit`
- 근거: 사용 중단 안내: OldFileDailyCleaner
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -1 +1 @@
 ## 39. 클래스: nexcore.framework.core.util.OldFileDailyCleaner
+
+> **사용 중단** — OldFileDailyCleaner는 더 이상 사용되지 않습니다.
+
 ### 39.3 메서드: `getDirectories()`
```

## P16 · dep-OldFileDailyCleaner · deprecate

- 지시: OldFileDailyCleaner 클래스는 폐기되었습니다.
- 청크 `c9ca8579-3b9d-4275-baad-d6fea8eae17d` (39.9 메서드: `setIgnoreFileNames(java.util.List)`) · 정답 라벨 **A** · 종류 `edit`
- 근거: 사용 중단 안내: OldFileDailyCleaner
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -1 +1 @@
 ## 39. 클래스: nexcore.framework.core.util.OldFileDailyCleaner
+
+> **사용 중단** — OldFileDailyCleaner는 더 이상 사용되지 않습니다.
+
 ### 39.9 메서드: `setIgnoreFileNames(java.util.List)`
```

## P17 · dep-CaseIgnoreHashMap · deprecate

- 지시: CaseIgnoreHashMap 은 사용 중단되었으니 대신 HashMap 을 쓰세요.
- 청크 `1f12e83a-0f02-4ccc-b84b-a3745b32cf76` (9. 클래스: nexcore.framework.core.util.CaseIgnoreHashMap<K, V>) · 정답 라벨 **D** · 종류 `edit`
- 근거: 사용 중단 안내: CaseIgnoreHashMap
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -1 +1 @@
 ## 9. 클래스: nexcore.framework.core.util.CaseIgnoreHashMap<K, V>
 
+> **사용 중단** — CaseIgnoreHashMap은 더 이상 사용되지 않으므로 대신 HashMap을 사용해야 합니다.
+
 **패키지:** `nexcore.framework.core.util`  
 **정규화 클래스명:** `nexcore.framework.core.util.CaseIgnoreHashMap<K, V>`
```

## P18 · dep-CaseIgnoreHashMap · deprecate

- 지시: CaseIgnoreHashMap 은 사용 중단되었으니 대신 HashMap 을 쓰세요.
- 청크 `a062e03a-8493-4fa2-ac61-227a76e07f70` (9.15 메서드: `values()`) · 정답 라벨 **A** · 종류 `edit`
- 근거: 사용 중단 안내: CaseIgnoreHashMap
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -1 +1 @@
 ## 9. 클래스: nexcore.framework.core.util.CaseIgnoreHashMap<K, V>
+
+> **사용 중단** — CaseIgnoreHashMap은 더 이상 사용되지 않으므로 대신 HashMap을 사용해야 합니다.
+
 ### 9.15 메서드: `values()`
```

## P19 · dep-CaseIgnoreHashMap · deprecate

- 지시: CaseIgnoreHashMap 은 사용 중단되었으니 대신 HashMap 을 쓰세요.
- 청크 `55d49e9a-9f5d-4b0e-bc53-81e80a6c447a` (9.9 메서드: `isEmpty()`) · 정답 라벨 **A** · 종류 `edit`
- 근거: 사용 중단 안내: CaseIgnoreHashMap
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -1 +1 @@
 ## 9. 클래스: nexcore.framework.core.util.CaseIgnoreHashMap<K, V>
+
+> **사용 중단** — CaseIgnoreHashMap은 더 이상 사용되지 않으므로 대신 HashMap을 사용해야 합니다.
+
 ### 9.9 메서드: `isEmpty()`
```

## P20 · dep-CaseIgnoreHashMap · deprecate

- 지시: CaseIgnoreHashMap 은 사용 중단되었으니 대신 HashMap 을 쓰세요.
- 청크 `9e685a0d-3af2-41c1-8aef-5b0cbdaa5f89` (9.6 메서드: `containsValue(java.lang.Object)`) · 정답 라벨 **A** · 종류 `edit`
- 근거: 사용 중단 안내: CaseIgnoreHashMap
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -1 +1 @@
 ## 9. 클래스: nexcore.framework.core.util.CaseIgnoreHashMap<K, V>
+
+> **사용 중단** — CaseIgnoreHashMap은 더 이상 사용되지 않으므로 대신 HashMap을 사용해야 합니다.
+
 ### 9.6 메서드: `containsValue(java.lang.Object)`
```

## P21 · dep-CaseIgnoreHashMap · deprecate

- 지시: CaseIgnoreHashMap 은 사용 중단되었으니 대신 HashMap 을 쓰세요.
- 청크 `18d2382e-1d15-4447-956b-09c82cde061b` (9.12 메서드: `putAll(java.util.Map)`) · 정답 라벨 **A** · 종류 `edit`
- 근거: 사용 중단 안내: CaseIgnoreHashMap
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -1 +1 @@
 ## 9. 클래스: nexcore.framework.core.util.CaseIgnoreHashMap<K, V>
+
+> **사용 중단** — CaseIgnoreHashMap은 더 이상 사용되지 않으므로 대신 HashMap을 사용해야 합니다.
+
 ### 9.12 메서드: `putAll(java.util.Map)`
```

## P22 · dep-CaseIgnoreHashMap · deprecate

- 지시: CaseIgnoreHashMap 은 사용 중단되었으니 대신 HashMap 을 쓰세요.
- 청크 `986d266d-8ccd-4b72-94b1-ea1e6d198ae2` (9.3 생성자: `<init>(int,float)`) · 정답 라벨 **A** · 종류 `edit`
- 근거: 사용 중단 안내: CaseIgnoreHashMap
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -1 +1 @@
 ## 9. 클래스: nexcore.framework.core.util.CaseIgnoreHashMap<K, V>
+
+> **사용 중단** — CaseIgnoreHashMap은 더 이상 사용되지 않으므로 대신 HashMap을 사용해야 합니다.
+
 ### 9.3 생성자: `<init>(int,float)`
```

## P23 · dep-PaddableDataOutputStream · deprecate

- 지시: PaddableDataOutputStream 은 더 이상 지원하지 않습니다.
- 청크 `3d09d635-2da2-4eea-9ad1-e7843f3ea4ae` (7.6 메서드: `writeMapToOut(nexcore.framework.batch.util.RecordMapGetter)`) · 정답 라벨 **M** · 종류 `edit`
- 근거: 사용 중단 안내: PaddableDataOutputStream
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -1 +1 @@
 ## 7. 클래스: nexcore.framework.batch.appbase.fio.IRecordClassifier
+
+> **사용 중단** — PaddableDataOutputStream은 더 이상 사용되지 않습니다.
+
 ### 7.6 메서드: `writeMapToOut(nexcore.framework.batch.util.RecordMapGetter)`
```

## P24 · dep-PaddableDataOutputStream · deprecate

- 지시: PaddableDataOutputStream 은 더 이상 지원하지 않습니다.
- 청크 `4d0693ce-d6d1-4a28-b6ff-6a989142dc5c` (40. 클래스: nexcore.framework.core.util.PaddableDataOutputStream) · 정답 라벨 **D** · 종류 `edit`
- 근거: 사용 중단 안내: PaddableDataOutputStream
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -1 +1 @@
 ## 40. 클래스: nexcore.framework.core.util.PaddableDataOutputStream
 
+> **사용 중단** — PaddableDataOutputStream은 더 이상 사용되지 않습니다.
+
 **패키지:** `nexcore.framework.core.util`  
 **정규화 클래스명:** `nexcore.framework.core.util.PaddableDataOutputStream`
```

## P25 · dep-PaddableDataOutputStream · deprecate

- 지시: PaddableDataOutputStream 은 더 이상 지원하지 않습니다.
- 청크 `eb47060b-9e97-476d-9ccf-a83ad52e0eb5` (40.3 생성자: `<init>(java.io.OutputStream,java.lang.String)`) · 정답 라벨 **A** · 종류 `edit`
- 근거: 사용 중단 안내: PaddableDataOutputStream
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -1 +1 @@
 ## 40. 클래스: nexcore.framework.core.util.PaddableDataOutputStream
+
+> **사용 중단** — PaddableDataOutputStream은 더 이상 사용되지 않습니다.
+
 ### 40.3 생성자: `<init>(java.io.OutputStream,java.lang.String)`
```

## P26 · dep-PaddableDataOutputStream · deprecate

- 지시: PaddableDataOutputStream 은 더 이상 지원하지 않습니다.
- 청크 `0bbd8b4b-3702-4bfe-8faa-bb412a8d9153` (40.13 메서드: `writeStringWithRPadding(java.lang.String,int,byte)`) · 정답 라벨 **A** · 종류 `edit`
- 근거: 사용 중단 안내: PaddableDataOutputStream
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -1 +1 @@
 ## 40. 클래스: nexcore.framework.core.util.PaddableDataOutputStream
+
+> **사용 중단** — PaddableDataOutputStream은 더 이상 사용되지 않습니다.
+
 ### 40.13 메서드: `writeStringWithRPadding(java.lang.String,int,byte)`
```

## P27 · dep-PaddableDataOutputStream · deprecate

- 지시: PaddableDataOutputStream 은 더 이상 지원하지 않습니다.
- 청크 `2fc3ae22-205f-4b68-adc6-8f461f74b704` (40.9 메서드: `writeStringWithIntLength(java.lang.String)`) · 정답 라벨 **A** · 종류 `edit`
- 근거: 사용 중단 안내: PaddableDataOutputStream
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -1 +1 @@
 ## 40. 클래스: nexcore.framework.core.util.PaddableDataOutputStream
+
+> **사용 중단** — PaddableDataOutputStream은 더 이상 사용되지 않습니다.
+
 ### 40.9 메서드: `writeStringWithIntLength(java.lang.String)`
```

## P28 · dep-PaddableDataOutputStream · deprecate

- 지시: PaddableDataOutputStream 은 더 이상 지원하지 않습니다.
- 청크 `3f694d9d-46f9-4618-93fd-9a6b226f2284` (40.6 메서드: `isLengthOverError()`) · 정답 라벨 **A** · 종류 `edit`
- 근거: 사용 중단 안내: PaddableDataOutputStream
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -1 +1 @@
 ## 40. 클래스: nexcore.framework.core.util.PaddableDataOutputStream
+
+> **사용 중단** — PaddableDataOutputStream은 더 이상 사용되지 않습니다.
+
 ### 40.6 메서드: `isLengthOverError()`
```

## P29 · dep-PaddableDataOutputStream · deprecate

- 지시: PaddableDataOutputStream 은 더 이상 지원하지 않습니다.
- 청크 `f2e58dae-25fc-47c3-9d9c-a724b0a4a664` (40.11 메서드: `writeWithIntLength(nexcore.framework.core.util.ByteArrayWrap)`) · 정답 라벨 **A** · 종류 `edit`
- 근거: 사용 중단 안내: PaddableDataOutputStream
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -1 +1 @@
 ## 40. 클래스: nexcore.framework.core.util.PaddableDataOutputStream
+
+> **사용 중단** — PaddableDataOutputStream은 더 이상 사용되지 않습니다.
+
 ### 40.11 메서드: `writeWithIntLength(nexcore.framework.core.util.ByteArrayWrap)`
```

## P30 · dep-FUAttcFileMng · deprecate

- 지시: FUAttcFileMng 는 더 이상 쓰이지 않습니다. 대신 FUFileMng 를 사용하세요.
- 청크 `f10e3358-fa52-4dad-a0d9-cb2135681134` (6. FUAttcFileMng.fmFileDel — 파일삭제) · 정답 라벨 **D** · 종류 `edit`
- 근거: 사용 중단 안내: FUAttcFileMng
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -1 +1 @@
 ## 6. FUAttcFileMng.fmFileDel — 파일삭제
 
+> **사용 중단** — FUAttcFileMng은 더 이상 사용되지 않으므로 대신 FUFileMng을 사용해야 합니다.
+
 ### 6.1 개요
```

## P31 · dep-FUAttcFileMng · deprecate

- 지시: FUAttcFileMng 는 더 이상 쓰이지 않습니다. 대신 FUFileMng 를 사용하세요.
- 청크 `52c06f51-fd05-4a26-b428-8134fc80c984` (3.4 처리 흐름) · 정답 라벨 **D** · 종류 `edit`
- 근거: 사용 중단 안내: FUAttcFileMng
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -1 +1 @@
 ## 3. FUAttcFileMng.fmFileReg — 파일등록
+
+> **사용 중단** — FUAttcFileMng은 더 이상 사용되지 않으므로 대신 FUFileMng을 사용해야 합니다.
+
 ### 3.4 처리 흐름
```

## P32 · dep-FUAttcFileMng · deprecate

- 지시: FUAttcFileMng 는 더 이상 쓰이지 않습니다. 대신 FUFileMng 를 사용하세요.
- 청크 `8063d6fd-3e29-42c8-aa4a-39692e29fcb0` (4. FUAttcFileMng.fmFileInqSingle — 파일조회-단건) · 정답 라벨 **D** · 종류 `edit`
- 근거: 사용 중단 안내: FUAttcFileMng
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -1 +1 @@
 ## 4. FUAttcFileMng.fmFileInqSingle — 파일조회-단건
 
+> **사용 중단** — FUAttcFileMng은 더 이상 사용되지 않으므로 대신 FUFileMng을 사용하십시오.
+
 ### 4.1 개요
```

## P33 · dep-FUAttcFileMng · deprecate

- 지시: FUAttcFileMng 는 더 이상 쓰이지 않습니다. 대신 FUFileMng 를 사용하세요.
- 청크 `7e812ad8-54e4-42ee-8dac-b1eeea0cb880` (3.15.8 첨부파일 관리 (대량 입력 거래)) · 정답 라벨 **D** · 종류 `edit`
- 근거: 사용 중단 안내: FUAttcFileMng · 다듬은 문장이 검사를 통과하지 못해 지시문 문장을 그대로 넣음 · 부정 표현의 수가 기준 문장과 다르다(뜻이 뒤집혔을 수 있다)
- 자동 점검: OK 안내 블록 있음
- 수용: x

```diff
@@ -1 +1 @@
 ### 3.15 기타 코딩 가이드
+
+> **사용 중단** — FUAttcFileMng은(는) 더 이상 사용되지 않습니다. 대신 FUFileMng을(를) 사용하세요.
+
 #### 3.15.8 첨부파일 관리 (대량 입력 거래)
```

## P34 · rm-isSingleJvmBatch · remove

- 지시: isSingleJvmBatch 에 대한 설명을 삭제해
- 청크 `04c3b3d8-0511-4398-9a03-2c9a381a6c0c` (1.4 메서드: `getRuntimeMode()`) · 정답 라벨 **D** · 종류 `edit`
- 근거: 내용 삭제: isSingleJvmBatch · 일부 작업을 버림 —  · 1개 인용 실패
- 자동 점검: OK 대상이 사라짐 · 지운 글자 269 (원문의 19%)
- 수용: o (소제목과 메서드명과 관련된 내용 삭제됨 표시는 필요할 듯)

```diff
@@ -25 +25 @@
 Runtime mode.
 
-### 1.5 메서드: `isSingleJvmBatch()`
 
-**패키지:** `nexcore.framework.core.util`  
-**소속 클래스:** `nexcore.framework.core.util.BaseUtils`  
-**멤버 종류:** `메서드`
-
-```java
-public static boolean isSingleJvmBatch ()
-```
-
-SingleJVM 배치를 위한 런타임 여부
-
-#### 1.5.1 반환값
-
-- SingleJVM 배치를 위한 런타임 여부
 
 ### 1.6 메서드: `getRootCause(java.lang.Throwable)`
```

## P35 · rm-isSingleJvmBatch · remove

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

## P36 · rm-isSingleJvmBatch · remove

- 지시: isSingleJvmBatch 에 대한 설명을 삭제해
- 청크 `1332a6bf-15b8-41bb-ad2a-37c1d5a34a5b` (유형1 – 반복 데이터 메모리 캐시 사용) · 정답 라벨 **M** · 종류 `edit`
- 근거: 내용 삭제: isSingleJvmBatch · 일부 작업을 버림 —  · 1개 인용 실패
- 자동 점검: OK 대상이 사라짐 · 지운 글자 210 (원문의 11%)
- 수용: x

```diff
@@ -8 +8 @@
 ```java
 IRecord rs = null;
-if (BaseUtils.isSingleJvmBatch()) {
-    ICache cache = BaseUtils.getCacheManager().getCacheOrAdd("BCD_SET_ATTRB_ITEM");
-    record = (IRecord)cache.get(requestData.getString("SET_CODE"));
 
-    if (rs == null) {
         rs = dbSelect("selectListSetAttrbItem", requestData, onlineCtx);
         cache.put(requestData.getString("SET_CODE"), record);
```

## P37 · rm-isSingleJvmBatch · remove

- 지시: isSingleJvmBatch 에 대한 설명을 삭제해
- 청크 `51689137-c905-43c7-9621-496116dc4dfb` (유형1 – 반복 데이터 메모리 캐시 사용) · 정답 라벨 **M** · 종류 `edit`
- 근거: 내용 삭제: isSingleJvmBatch · 일부 작업을 버림 — 대상(isSingleJvmBatch)과 관련된 구간이 아니다 — 인용에도 그 섹션 제목에도 대상이 없다
- 자동 점검: OK 대상이 사라짐 · 지운 글자 35 (원문의 2%)
- 수용: x

```diff
@@ -37 +37 @@
 
 IRecord record = null;
-if (BaseUtils.isSingleJvmBatch()) {
+
```

## P38 · rm-isSingleJvmBatch · remove

- 지시: isSingleJvmBatch 에 대한 설명을 삭제해
- 청크 `040aa752-1288-400e-aa9b-1e6cbd62bad1` ([BCA] 업무공통 휴일 체크) · 정답 라벨 **M** · 종류 `edit`
- 근거: 내용 삭제: isSingleJvmBatch · 일부 작업을 버림 —  · 1개 인용 실패
- 자동 점검: 확인 필요 — 대상이 아직 남음 · 지운 글자 617 (원문의 46%)
- 수용: x

```diff
@@ -12 +12 @@
 
 ```java
-if (BaseUtils.isSingleJvmBatch()) {
-    // 휴일여부 캐시 객체
-    ICache cache = BaseUtils.getCacheManager().getCacheOrAdd("BCA_HLDD_DT");
-    String useYn = (String)cache.get(baseDt);
-    if (useYn == null) { //처음 조회일 경우 DB 에서 읽음
-        // 산출일자 공휴일여부 확인
-        IDataSet selectReq = new DataSet();
-        selectReq.put("HLDD_DT", baseDt);
 
-        // @@ 메소드 호출 (업무공통_휴일기본 단건조회 DUBCA_HLDDM_00.select)
-        IDataSet selectRes = dUBCA_HLDDM_00.select(selectReq, onlineCtx);
-
-        useYn = "Y".equals(selectRes.getString("USE_YN")) ? "Y" : "N";
-        // 휴일여부 등록
-        cache.put(baseDt, useYn);
-    }
-    return useYn;
 }

```

## P39 · rm-waitForAllReceiveEnd · remove

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

## P40 · rm-waitForAllReceiveEnd · remove

- 지시: waitForAllReceiveEnd 관련 내용은 지워줘
- 청크 `fdeb839d-03a6-4f01-80db-d1a42651d26d` (7.11.3.2 단일큐 예제 - Reader) · 정답 라벨 **M** · 종류 `edit`
- 근거: 내용 삭제: waitForAllReceiveEnd · 일부 작업을 버림 —  · 1개 인용 실패
- 자동 점검: OK 대상이 사라짐 · 지운 글자 40 (원문의 8%)
- 수용: x

```diff
@@ -21 +21 @@
     if (super.exceptionInExecute == null) {
         log.info("큐 {0} 수신완료 대기중", this.sharedQueueId);
-        this.sharedQueue.waitForAllReceiveEnd();
+        
     }
 }
```

## P41 · rm-readLines · remove

- 지시: readLines 메서드 설명은 문서에서 빼 줘
- 청크 `c42d7394-d0c2-46cf-b0a1-00b3b3d439e3` (32.52 메서드: `readLines(java.io.Reader)`) · 정답 라벨 **D** · 종류 `edit`
- 근거: 내용 삭제: readLines · 일부 작업을 버림 —  · 1개 인용 실패
- 자동 점검: 확인 필요 — 대상이 아직 남음 · 지운 글자 617 (원문의 45%)
- 수용: o (삭제 대상이 소제목인 경우, 제목은 남기고 내용은 삭제. 내용에 삭제됨을 표시해야 함)

```diff
@@ -2 +2 @@
 ### 32.52 메서드: `readLines(java.io.Reader)`
 
-**패키지:** `nexcore.framework.core.util`  
-**소속 클래스:** `nexcore.framework.core.util.IOUtils`  
-**멤버 종류:** `메서드`
 
-```java
-public static List < String > readLines ( Reader input) throws IOException
-```
-
-Gets the contents of a `Reader` as a list of Strings,
-one entry per line.
-
-This method buffers the input internally, so there is no need to use a
-`BufferedReader`.
-
-#### 32.52.1 매개변수
-
-- `input` - the `Reader` to read from, not null
-
-#### 32.52.2 반환값
-
-- the list of Strings, never null
-
-#### 32.52.3 예외
-
-- `NullPointerException` - if the input is null
-- `IOException` - if an I/O error occurs
-
-#### 32.52.4 도입 버전
-
-- 1.1
 
 ### 32.53 메서드: `toInputStream(java.lang.CharSequence)`
```

## P42 · rm-readLines · remove

- 지시: readLines 메서드 설명은 문서에서 빼 줘
- 청크 `fec468a2-f267-4a17-95ad-db91eff1844e` (32.51 메서드: `readLines(java.io.InputStream,java.lang.String)`) · 정답 라벨 **D** · 종류 `edit`
- 근거: 내용 삭제: readLines · 일부 작업을 버림 —  · 1개 인용 실패
- 자동 점검: 확인 필요 — 대상이 아직 남음 · 지운 글자 784 (원문의 70%)
- 수용: o

```diff
@@ -10 +10 @@
 
-Gets the contents of an `InputStream` as a list of Strings,
-one entry per line, using the specified character encoding.
 
-Character encoding names can be found at
-IANA.
-
-This method buffers the input internally, so there is no need to use a
-`BufferedInputStream`.
-
-#### 32.51.1 매개변수
-
-- `input` - the `InputStream` to read from, not null
-- `encoding` - the encoding to use, null means platform default
-
-#### 32.51.2 반환값
-
-- the list of Strings, never null
-
-### 32.51 메서드: `readLines(java.io.InputStream,java.lang.String)`
-#### 32.51.3 예외
-
-- `NullPointerException` - if the input is null
-- `IOException` - if an I/O error occurs
-- `UnsupportedCharsetException` - thrown instead of `.UnsupportedEncodingException` in version 2.2 if the
-encoding is not supported.
-
-#### 32.51.4 도입 버전
-
-- 1.1
```

## P43 · rm-readLines · remove

- 지시: readLines 메서드 설명은 문서에서 빼 줘
- 청크 `432ffd5e-81e8-4547-a8b5-431282190e5e` (32.50 메서드: `readLines(java.io.InputStream,java.nio.charset.Charset)`) · 정답 라벨 **D** · 종류 `delete_chunk`
- 근거: 청크 삭제: readLines · 제목·표지 말고 남은 본문이 없어 청크 삭제로 바꿨다
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

## P44 · rm-callAsyncServiceAfterCommit · remove

- 지시: callAsyncServiceAfterCommit 에 대한 내용을 삭제해
- 청크 `9549695b-78c0-4194-b67c-3879dd0508c6` (온라인 서비스 개발) · 정답 라벨 **D** · 종류 `edit`
- 근거: 내용 삭제: callAsyncServiceAfterCommit · 일부 작업을 버림 — 대상(callAsyncServiceAfterCommit)과 관련된 구간이 아니다 — 인용에도 그 섹션 제목에도 대상이 없다
- 자동 점검: OK 대상이 사라짐 · 지운 글자 125 (원문의 25%)
- 수용: o

```diff
@@ -11 +11 @@
 | --- | --- | --- |
 | 동기 | callServiceByRequired | 수동 거래 동일 트랜잭션으로 호출함. 수동 거래 정상 리턴되더라도 기동거래에서 에러 발생하면 수동 거래도 롤백 |
-| 비동기 | callAsyncServiceAfterCommit | 기동 거래와 독립적으로 별도 쓰레드에서 수동 거래 호출 기동 거래가 commit 완료되면 수동 거래 실행됨 기동 거래 롤백 되면 수동 거래 실행하지 않음 |
+
 | 비동기 | callAsyncServiceNow | 이 메소드 호출 즉시 별도 쓰레드에서 수동 거래 실행됨 기동 거래 정상/에러 여부에 상관없이 수동 거래 실행됨 |
```

## P45 · rm-callAsyncServiceAfterCommit · remove

- 지시: callAsyncServiceAfterCommit 에 대한 내용을 삭제해
- 청크 `86fe0e0c-19c5-473b-b87b-55f41d7530a7` (3.4.9 연동거래) · 정답 라벨 **D** · 종류 `edit`
- 근거: 내용 삭제: callAsyncServiceAfterCommit
- 자동 점검: OK 대상이 사라짐 · 지운 글자 581 (원문의 32%)
- 수용: x (다음 행을 잘못 지웠고, 줄바꿈도 잘못됨)

```diff
@@ -16 +16 @@
 | - | IDataSet callServiceByRequiresNew( String tranCd, IDataSet requestData, IOnlineContext onlineCtx) | 동기식 연동 거래 호출 (트랜잭션 분리 됨) tranCd : 거래코드 requestData : IDataSet타입의 요청데이터 수동거래에서 리턴하는 DataSet 이 callServiceByRequiresNew 의 리턴값으로 리턴됨 |
 | 비동기 | String callAsyncServiceNow( String tranCd, IDataSet requestData, IOnlineContext onlineCtx ) | 비동기식 연동거래 호출. 기동 거래와 분리된 별도의 쓰레드 또는 인스턴스 또는 서버에서 실행됨. 호출 즉시 큐에 요청이 들어가 실행을 준비함. 리턴값 : 수동 거래 GUID |
-| - | String callAsyncServiceAfterCommit( String tranCd, IDataSet requestData, IOnlineContext onlineCtx ) | 비동기식 연동거래 호출. 기동 거래와 분리된 별도의 쓰레드 또는 인스턴스 또는 서버에서 실행됨. 기동 거래가 정상 commit 된 후에 수동 거래 실행됨. 기동 거래 에러 발생시 수동 거래도 실행 취소됨. 리턴값 : 수동 거래 GUID |
-| - | String reserveCallTimerService( String tranCd, int delaySeconds, IDataSet requestData, IOnlineContext onlineCtx ) | 본 서비스와 독립적으로 수동 서비스를 지연시간 대기 후 실행하도록 호출 - 비동기 연동거래 키값이 리턴됩니다. - 대외 async 거래후 응답전문 미수신시 Timeout 처리를 위해 사용됩니다. tranCd : 거래코드 delaySeconds : 지연시간(초) requestData : IDataSet타입의 요청데이터 onlineCtx : 요청 컨텍스트 정보 리턴값 : 비동기 호출 키 |
+
 | - | boolean cancelCallTimerService ( String delayAsyncId, IOnlineContext onlineCtx ) | 지연시간 대기중인 지연비동기 연동거래 실행을 취소 - 리턴값이 boolean이며, true는 취소성공이고, false는 취소실패(수행중, 수행완료)입니다. [파라미터] delayAsyncId : 비동기 호출키 onlineCtx : 요청 컨텍스트 정보 |
```

## P46 · rm-addErrorCountAmount · remove

- 지시: addErrorCountAmount 설명을 지워
- 청크 `931b66f3-1e2f-493c-8c61-c33e19d35856` (10. BEDU2002 — File → DB (고정길이 + 에러재처리)) · 정답 라벨 **M** · 종류 `edit`
- 근거: 내용 삭제: addErrorCountAmount · 일부 작업을 버림 —  · 1개 인용 실패
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

## P47 · rm-addErrorCountAmount · remove

- 지시: addErrorCountAmount 설명을 지워
- 청크 `0641f105-d475-4179-a0dd-8471a61437bc` (6.86 메서드: `addErrorCountAmount(long,java.math.BigDecimal)`) · 정답 라벨 **D** · 종류 `edit`
- 근거: 내용 삭제: addErrorCountAmount
- 자동 점검: 확인 필요 — 대상이 아직 남음 · 지운 글자 81 (원문의 8%)
- 수용: x

```diff
@@ -10 +10 @@
 
-에러건수, 금액 증가
 
-#### 6.86.1 구현
-
-- `addErrorCountAmount` in interface `IBatchContext`
 
 #### 6.86.2 매개변수
```

## P48 · rm-addErrorCountAmount · remove

- 지시: addErrorCountAmount 설명을 지워
- 청크 `a5f2e166-dfb5-4b9c-928e-a754b7400006` (6.84 메서드: `addSuccessCountAmount(long,java.math.BigDecimal)`) · 정답 라벨 **A** · 종류 `edit`
- 근거: 내용 삭제: addErrorCountAmount · 일부 작업을 버림 —  · 1개 인용 실패
- 자동 점검: 확인 필요 — 대상이 아직 남음 · 지운 글자 238 (원문의 30%)
- 수용: x

```diff
@@ -21 +21 @@
 - `amount` - 정상금액
 
-### 6.85 메서드: `addErrorCountAmount(long,double)`
 
-**패키지:** `nexcore.framework.batch`
-**소속 클래스:** `nexcore.framework.batch.BatchContext`
-**멤버 종류:** `메서드`
-
-```java
-public void addErrorCountAmount (long count, double amount)
-```
-
-에러건수, 금액 증가
 
 #### 6.85.1 구현
```

## P49 · rm-closeQuietly · remove

- 지시: closeQuietly 에 관한 설명을 삭제해 주세요
- 청크 `7e13de0e-d646-484f-bd4d-09aa924866b7` (32.7 메서드: `closeQuietly(java.io.Closeable...)`) · 정답 라벨 **D** · 종류 `edit`
- 근거: 내용 삭제: closeQuietly · 일부 작업을 버림 —  · 1개 인용 실패
- 자동 점검: 확인 필요 — 대상이 아직 남음 · 지운 글자 1257 (원문의 82%)
- 수용: x

```diff
@@ -10 +10 @@
 
-Closes a `Closeable` unconditionally.
 
-Equivalent to `Closeable.close()`, except any exceptions will be ignored.
-
-This is typically used in finally blocks to ensure that the closeable is closed
-even if an Exception was thrown before the normal close statement was reached.
-
-It should not be used to replace the close statement(s)
-which should be present for the non-exceptional case.
-
-It is only intended to simplify tidying up where normal processing has already failed
-and reporting close failure as well is not necessary or useful.
-
-Example code:
-
-```java
-Closeable closeable = null;
-try {
-closeable = new FileReader("foo.txt");
-// processing using the closeable; may throw an Exception
-closeable.close(); // Normal close - exceptions not ignored
-} catch (Exception e) {
-// error handling
-} finally {
-IOUtils.closeQuietly(closeable); // In case normal close was skipped due to Exception
-}
-```
-
-Closing all streams:
-
-```java
-try {
-return IOUtils.copy(inputStream, outputStream);
-} finally {
-IOUtils.closeQuietly(inputStream, outputStream);
-}
-```
-
-### 32.7 메서드: `closeQuietly(java.io.Closeable...)`
-#### 32.7.1 매개변수
-
-- `closeables` - the objects to close, may be null or already closed
-
-#### 32.7.2 도입 버전
-
-- 2.5
-
-#### 32.7.3 참고
-
-- `closeQuietly(Closeable)`
```

## P50 · rm-closeQuietly · remove

- 지시: closeQuietly 에 관한 설명을 삭제해 주세요
- 청크 `49cbb655-424f-440d-aab7-43fa6f4e94f1` (32.3 메서드: `closeQuietly(java.io.Writer)`) · 정답 라벨 **D** · 종류 `edit`
- 근거: 내용 삭제: closeQuietly · 일부 작업을 버림 —  · 1개 인용 실패
- 자동 점검: 확인 필요 — 대상이 아직 남음 · 지운 글자 460 (원문의 30%)
- 수용: x

```diff
@@ -10 +10 @@
 
-Closes an `Writer` unconditionally.
 
-Equivalent to `Writer.close()`, except any exceptions will be ignored.
-This is typically used in finally blocks.
-
-Example code:
-
-```java
-Writer out = null;
-try {
-out = new StringWriter();
-out.write("Hello World");
-out.close(); //close errors are handled
-} catch (Exception e) {
-// error handling
-} finally {
-IOUtils.closeQuietly(out);
-}
-```
-
-#### 32.3.1 매개변수
-
-- `output` - the Writer to close, may be null or already closed
 
 ### 32.4 메서드: `closeQuietly(java.io.InputStream)`
```

## P51 · rm-closeQuietly · remove

- 지시: closeQuietly 에 관한 설명을 삭제해 주세요
- 청크 `4b840f14-7b40-4159-b5d2-8aabcd811d7d` (32.6 메서드: `closeQuietly(java.io.Closeable)`) · 정답 라벨 **D** · 종류 `edit`
- 근거: 내용 삭제: closeQuietly · 일부 작업을 버림 —  · 1개 인용 실패
- 자동 점검: 확인 필요 — 대상이 아직 남음 · 지운 글자 719 (원문의 72%)
- 수용: x

```diff
@@ -10 +10 @@
 
-Closes a `Closeable` unconditionally.
 
-Equivalent to `Closeable.close()`, except any exceptions will be ignored. This is typically used in
-finally blocks.
-
-Example code:
-
-```java
-Closeable closeable = null;
-try {
-closeable = new FileReader("foo.txt");
-// process closeable
-closeable.close();
-} catch (Exception e) {
-// error handling
-} finally {
-IOUtils.closeQuietly(closeable);
-}
-```
-
-Closing all streams:
-
-```java
-try {
-return IOUtils.copy(inputStream, outputStream);
-} finally {
-IOUtils.closeQuietly(inputStream);
-IOUtils.closeQuietly(outputStream);
-}
-```
-
-### 32.6 메서드: `closeQuietly(java.io.Closeable)`
-#### 32.6.1 매개변수
-
-- `closeable` - the objects to close, may be null or already closed
-
-#### 32.6.2 도입 버전
-
-- 2.0
```

## P52 · rm-closeQuietly · remove

- 지시: closeQuietly 에 관한 설명을 삭제해 주세요
- 청크 `92ed84cd-0d44-4793-a6ef-15ce000a5813` (32.10 메서드: `closeQuietly(java.net.ServerSocket)`) · 정답 라벨 **D** · 종류 `edit`
- 근거: 내용 삭제: closeQuietly · 일부 작업을 버림 —  · 1개 인용 실패
- 자동 점검: 확인 필요 — 대상이 아직 남음 · 지운 글자 486 (원문의 64%)
- 수용: x

```diff
@@ -10 +10 @@
 
-Closes a `ServerSocket` unconditionally.
 
-Equivalent to `ServerSocket.close()`, except any exceptions will be ignored.
-This is typically used in finally blocks.
-
-Example code:
-
-```java
-ServerSocket socket = null;
-try {
-socket = new ServerSocket();
-// process socket
-socket.close();
-} catch (Exception e) {
-// error handling
-} finally {
-IOUtils.closeQuietly(socket);
-}
-```
-
-#### 32.10.1 매개변수
-
-- `sock` - the ServerSocket to close, may be null or already closed
-
-#### 32.10.2 도입 버전
-
-- 2.2
```

## P53 · note-getJobExecutionId · add_note

- 지시: getJobExecutionId 에 '같은 Job 을 재실행하면 실행 ID 가 새로 발급된다' 는 주의 사항을 추가해
- 청크 `ae4fd27e-cc67-4b5b-afb4-d36575f3bc4f` (7.11.3.6 멀티큐 예제 – Processor) · 정답 라벨 **M** · 종류 `edit`
- 근거: 주의 사항 추가: getJobExecutionId · 다듬은 문장이 검사를 통과하지 못해 지시문 문장을 그대로 넣음 · 길이가 기준 문장과 크게 다르다(207%)
- 자동 점검: OK 주의 블록 있음
- 수용: o

```diff
@@ -40 +40 @@
  
+> **주의** — 같은 Job 을 재실행하면 실행 ID 가 새로 발급된다
+
 - 데이터 수신 처리
   - 큐에서 수신한 IRecord 객체를 처리하기 위한 RecordHandler 등록
```

## P54 · note-CSVFileOption · add_note

- 지시: CSVFileOption 에 '인코딩을 지정하지 않으면 서버 기본 인코딩이 쓰인다' 는 주의 사항을 추가해
- 청크 `a1bfa375-18c7-4123-bb94-70c416e7c40f` (7. 클래스: nexcore.framework.biz.online.SQLIO.CSVFileOption) · 정답 라벨 **D** · 종류 `edit`
- 근거: 주의 사항 추가: CSVFileOption
- 자동 점검: OK 주의 블록 있음
- 수용: o

```diff
@@ -34 +34 @@
 인코딩 (미지정시 시스템 기본 인코딩 사용)
 
+> **주의** — 인코딩을 지정하지 않으면 서버 기본 인코딩이 사용됩니다.
+
 ### 7.2 메서드: `getEncoding()`
 #### 7.2.1 반환값
```

## P55 · note-dbSelectMulti · add_note

- 지시: dbSelectMulti 와 관련해 '조회 건수가 많으면 메모리가 부족해질 수 있다' 는 주의 사항을 추가해
- 청크 `39e1aa81-f22d-4632-b749-f2a17d2ba332` (16. BEDU4001 — 공유 FM + 배치 SELECT) · 정답 라벨 **M** · 종류 `edit`
- 근거: 주의 사항 추가: dbSelectMulti · 다듬은 문장이 검사를 통과하지 못해 지시문 문장을 그대로 넣음 · 기준 문장·주변 글에 없는 말이 많다: 많을, 경우, 발생할, 있으니, 합니다
- 자동 점검: OK 주의 블록 있음
- 수용: o

```diff
@@ -39 +39 @@
 4. afterExecute 정상/오류 로그
 
+> **주의** — 조회 건수가 많으면 메모리가 부족해질 수 있다
+
 ### 16.5 핵심 코드
```

## P56 · note-copyFile · add_note

- 지시: copyFile 에 '대상 파일이 이미 있으면 덮어쓴다' 는 주의 사항을 넣어줘
- 청크 `2489489b-d7dd-4b4b-8d7c-d5930915eb2e` (26.23 메서드: `copyFile(java.io.File,java.io.File)`) · 정답 라벨 **D** · 종류 `edit`
- 근거: 주의 사항 추가: copyFile
- 자동 점검: OK 주의 블록 있음
- 수용: o

```diff
@@ -17 +17 @@
 method will overwrite it.
 
+> **주의** — 대상 파일이 이미 존재하면 덮어씁니다.
+
 Note: This method tries to preserve the file's last
 modified date/times using `File.setLastModified(long)`, however
```

## P57 · note-cancelCallTimerService · add_note

- 지시: cancelCallTimerService 에 '취소에 실패하면 false 를 돌려주므로 반드시 결과를 확인해야 한다' 는 주의 사항을 추가해
- 청크 `86fe0e0c-19c5-473b-b87b-55f41d7530a7` (3.4.9 연동거래) · 정답 라벨 **D** · 종류 `edit`
- 근거: 주의 사항 추가: cancelCallTimerService · 다듬은 문장이 검사를 통과하지 못해 지시문 문장을 그대로 넣음 · 기준 문장·주변 글에 없는 말이 많다: 경우, 반환하므로, 반환값을
- 자동 점검: OK 주의 블록 있음
- 수용: o

```diff
@@ -20 +20 @@
 | - | boolean cancelCallTimerService ( String delayAsyncId, IOnlineContext onlineCtx ) | 지연시간 대기중인 지연비동기 연동거래 실행을 취소 - 리턴값이 boolean이며, true는 취소성공이고, false는 취소실패(수행중, 수행완료)입니다. [파라미터] delayAsyncId : 비동기 호출키 onlineCtx : 요청 컨텍스트 정보 |
 
+> **주의** — 취소에 실패하면 false 를 돌려주므로 반드시 결과를 확인해야 한다
+
 각 호출의 예는 다음과 같습니다.
```

## P58 · note-sendDummyReturnNow · add_note

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

## P59 · rev-dbSelectSingleLockRetry · revise

- 지시: dbSelectSingleLockRetry 의 재시도 간격은 0.5초가 아니라 1초다
- 청크 `bd5a9e46-8365-4f19-bd6d-2ccce96598e6` (1.3 메서드: `dbSelectSingleLockRetry(java.lang.String,int,java.lang.Object,java.lang.String,nexcore.framework.core.data.IOnlineContext)`) · 정답 라벨 **D** · 종류 `edit`
- 근거: 설명 수정: dbSelectSingleLockRetry
- 자동 점검: OK 새 값이 들어감
- 수용: ☐

```diff
@@ -10 +10 @@
 
-SELECT FOR UPDATE NOWAIT 조회에서 SQL 오류 코드 54 또는 30006으로 잠금 획득에 실패한 경우 재시도한다. 이 경우 여기에 지정된 `tryCnt` 만큼 재시도하여 lock 획득을 시도하며, `tryCnt` 시도 후에도 lock 획득에 실패할 경우 에러가 발생한다. 재시도 시 0.5 초 sleep 후 재시도한다.
+SELECT FOR UPDATE NOWAIT 조회에서 SQL 오류 코드 54 또는 30006으로 잠금 획득에 실패한 경우 재시도한다. 이 경우 여기에 지정된 `tryCnt` 만큼 재시도하여 lock 획득을 시도하며, `tryCnt` 시도 후에도 lock 획득에 실패할 경우 에러가 발생한다. 재시도 시 1초 sleep 후 재시도한다.
 
 #### 1.3.1 매개변수
```

## P60 · rev-dbSelectSingleLockRetry · revise

- 지시: dbSelectSingleLockRetry 의 재시도 간격은 0.5초가 아니라 1초다
- 청크 `503fa0e6-fc3f-4d3b-a278-bb4f051846c0` (1.2 메서드: `dbSelectSingleLockRetry(java.lang.String,int,java.lang.Object,nexcore.framework.core.data.IOnlineContext)`) · 정답 라벨 **D** · 종류 `edit`
- 근거: 설명 수정: dbSelectSingleLockRetry
- 자동 점검: OK 새 값이 들어감
- 수용: o

```diff
@@ -10 +10 @@
 
-SELECT FOR UPDATE NOWAIT 조회에서 SQL 오류 코드 54 또는 30006으로 잠금 획득에 실패한 경우 재시도한다. 이 경우 여기에 지정된 `tryCnt` 만큼 재시도하여 lock 획득을 시도하며, `tryCnt` 시도 후에도 lock 획득에 실패할 경우 에러가 발생한다. 재시도 시 0.5 초 sleep 후 재시도한다.
+SELECT FOR UPDATE NOWAIT 조회에서 SQL 오류 코드 54 또는 30006으로 잠금 획득에 실패한 경우 재시도한다. 이 경우 여기에 지정된 `tryCnt` 만큼 재시도하여 lock 획득을 시도하며, `tryCnt` 시도 후에도 lock 획득에 실패할 경우 에러가 발생한다. 재시도 시 1초 sleep 후 재시도한다.
 
 #### 1.2.1 매개변수
```

## P61 · rev-dbSelectSingleLockRetry · revise

- 지시: dbSelectSingleLockRetry 의 재시도 간격은 0.5초가 아니라 1초다
- 청크 `500b1092-ace6-42ed-9a9f-a13785016913` (1.5 메서드: `dbSelectSingleLockRetry(java.lang.String,int,int,java.lang.Object,java.lang.String,nexcore.framework.core.data.IOnlineContext)`) · 정답 라벨 **D** · 종류 `edit`
- 근거: 설명 수정: dbSelectSingleLockRetry
- 자동 점검: OK 새 값이 들어감
- 수용: x

```diff
@@ -10 +10 @@
 
-SELECT FOR UPDATE NOWAIT 조회에서 SQL 오류 코드 54 또는 30006으로 잠금 획득에 실패한 경우 재시도한다. 이 경우 여기에 지정된 `tryCnt` 만큼 재시도하여 lock 획득을 시도하며, `tryCnt` 시도 후에도 lock 획득에 실패할 경우 에러가 발생한다. 재시도 시 `sleepMillSec` 밀리초 sleep 후 재시도한다.
+SELECT FOR UPDATE NOWAIT 조회에서 SQL 오류 코드 54 또는 30006으로 잠금 획득에 실패한 경우 재시도한다. 이 경우 여기에 지정된 `tryCnt` 만큼 재시도하여 lock 획득을 시도하며, `tryCnt` 시도 후에도 lock 획득에 실패할 경우 에러가 발생한다. 재시도 시 1초 sleep 후 재시도한다.
 
 #### 1.5.1 매개변수
```

## P62 · rev-dbSelectSingleLockRetry · revise

- 지시: dbSelectSingleLockRetry 의 재시도 간격은 0.5초가 아니라 1초다
- 청크 `aadffed5-b7ca-4430-ab24-029852dad5e2` (1.4 메서드: `dbSelectSingleLockRetry(java.lang.String,int,int,java.lang.Object,nexcore.framework.core.data.IOnlineContext)`) · 정답 라벨 **D** · 종류 `edit`
- 근거: 설명 수정: dbSelectSingleLockRetry
- 자동 점검: OK 새 값이 들어감
- 수용: x

```diff
@@ -10 +10 @@
 
-SELECT FOR UPDATE NOWAIT 조회에서 SQL 오류 코드 54 또는 30006으로 잠금 획득에 실패한 경우 재시도한다. 이 경우 여기에 지정된 `tryCnt` 만큼 재시도하여 lock 획득을 시도하며, `tryCnt` 시도 후에도 lock 획득에 실패할 경우 에러가 발생한다. 재시도 시 `sleepMillSec` 밀리초 sleep 후 재시도한다.
+SELECT FOR UPDATE NOWAIT 조회에서 SQL 오류 코드 54 또는 30006으로 잠금 획득에 실패한 경우 재시도한다. 이 경우 여기에 지정된 `tryCnt` 만큼 재시도하여 lock 획득을 시도하며, `tryCnt` 시도 후에도 lock 획득에 실패할 경우 에러가 발생한다. 재시도 시 1초 sleep 후 재시도한다.
 
 #### 1.4.1 매개변수
```

## P63 · rev-dbSelectSingleLockRetry · revise

- 지시: dbSelectSingleLockRetry 의 재시도 간격은 0.5초가 아니라 1초다
- 청크 `02734e5d-aa71-44e7-b748-ae49b55844cd` (온라인 서비스 개발) · 정답 라벨 **D** · 종류 `edit`
- 근거: 설명 수정: dbSelectSingleLockRetry
- 자동 점검: OK 새 값이 들어감
- 수용: o

```diff
@@ -5 +5 @@
 - **For Update Lock Retry**
 - SELECT FOR UPDATE NOWAIT 쿼리시 해당 ROW를 이미 다른 세션이 Lock 잡고 있으면 Lock 에러 발생
-- dbSelectSingleLockRetry 안에서는 sleep 0.5 초 후 지정된 횟수만큼 재시도 함
+- dbSelectSingleLockRetry 안에서는 sleep 1초 후 지정된 횟수만큼 재시도 함
 
 [도형 그룹]
```

## P64 · rev-sendErrorResponseOnFail · revise

- 지시: sendDummyReturnNow 의 sendErrorResponseOnFail 기본값은 true 가 아니라 false 다
- 청크 `d006ef6b-a108-46b2-acab-5d224d57e72a` (3.6 메서드: `sendDummyReturnNow(nexcore.framework.core.data.IOnlineContext)`) · 정답 라벨 **D** · 종류 `edit`
- 근거: 설명 수정: sendErrorResponseOnFail
- 자동 점검: OK 새 값이 들어감
- 수용: o

```diff
@@ -40 +40 @@
 
 - `onlineCtx` - 온라인컨텍스트
-- `sendErrorResponseOnFail` - (더미리턴 후 오류난 경우 오류 전문 전송여부)
+- `sendErrorResponseOnFail` - (더미리턴 후 오류난 경우 오류 전문 전송여부) (기본값: false)
 
 #### 3.7.2 반환값
```

## P65 · rev-sendErrorResponseOnFail · revise

- 지시: sendDummyReturnNow 의 sendErrorResponseOnFail 기본값은 true 가 아니라 false 다
- 청크 `615b7ce8-98b6-42f4-b545-9d84007bc63a` (3.9.4.6 sendDummyReturn) · 정답 라벨 **D** · 종류 `edit`
- 근거: 설명 수정: sendErrorResponseOnFail
- 자동 점검: OK 새 값이 들어감
- 수용: o

```diff
@@ -8 +8 @@
 | void **sendDummyReturnNow**(IOnlineContext onlineCtx);<br>void **sendDummyReturnNow**(IOnlineContext onlineCtx, boolean sendErrorResponseOnFail ); |
 
-`sendErrorResponseOnFail`은 기본값 `true`이며, 이 값이 `true`일 경우 `sendDummyReturnNow` 호출하여 Http 세션을 닫은 후에 뒤에서 에러가 발생할 경우, 프레임워크에서 에러 전문을 생성하여 MCI/FEP/EAI로 에러 전문을 PUSH 전송하는 기능을 합니다. 그러나 에러와 상관없이 계속 dummy 상태를 유지하기 위해서는 `false`를 주어야 합니다.
+`sendErrorResponseOnFail`은 기본값 `false`이며, 이 값이 `true`일 경우 `sendDummyReturnNow` 호출하여 Http 세션을 닫은 후에 뒤에서 에러가 발생할 경우, 프레임워크에서 에러 전문을 생성하여 MCI/FEP/EAI로 에러 전문을 PUSH 전송하는 기능을 합니다. 그러나 에러와 상관없이 계속 dummy 상태를 유지하기 위해서는 `false`를 주어야 합니다.
```

## P66 · rev-getSharedQueue · revise

- 지시: getSharedQueue 의 Timeout 기본값은 10초가 아니라 30초다
- 청크 `92833882-c369-44c9-85b4-7fe9248b8e2d` (21. BEDU5002 — 병렬 SharedQueue Processor (단일 큐)) · 정답 라벨 **M** · 종류 `edit`
- 근거: 설명 수정: getSharedQueue
- 자동 점검: 확인 필요 — 새 값 "30초" 이 없음
- 수용: x

```diff
@@ -36 +36 @@
 ### 21.4 처리 흐름
 
-1. getSharedQueue(id, 10) → Receiver
+1. getSharedQueue(id, 30) → Receiver
 2. receive(makeHandler) 루프
 3. 행마다 INSERT + 성공 정산
```

## P67 · rev-getSharedQueue · revise

- 지시: getSharedQueue 의 Timeout 기본값은 10초가 아니라 30초다
- 청크 `26e13ad2-5a01-4edb-bbc0-24ba9aaf782b` (23. BEDU5004 — 병렬 SharedQueue Processor (멀티 큐)) · 정답 라벨 **M** · 종류 `edit`
- 근거: 설명 수정: getSharedQueue
- 자동 점검: 확인 필요 — 새 값 "30초" 이 없음
- 수용: x

```diff
@@ -47 +47 @@
 this.sharedQueueId = StringUtils.nvl(context.getInParameter("QUEUE_ID"),
         StringUtils.nvl(context.getInParameter("PARENT.JOB_EXE_ID"), "DEFAULT"));
-this.sharedQueue = getSharedQueue(this.sharedQueueId + "_" + (threadNo - 1), 10);
+this.sharedQueue = getSharedQueue(this.sharedQueueId + "_" + (threadNo - 1), 30);
 this.sharedQueueReceiver = sharedQueue.createReceiver(context);
 // execute
```

## P68 · rev-getSharedQueue · revise

- 지시: getSharedQueue 의 Timeout 기본값은 10초가 아니라 30초다
- 청크 `13f0dd3d-a62d-4080-87ea-b8f64de2eda3` (1.4 메서드: `createSharedQueue(java.lang.String,java.lang.String,int,nexcore.framework.batch.IBatchContext)`) · 정답 라벨 **A** · 종류 `edit`
- 근거: 설명 수정: getSharedQueue
- 자동 점검: OK 새 값이 들어감
- 수용: x

```diff
@@ -33 +33 @@
 ```
 
-id 에 해당하는 큐를 반환함. 없으면 정해진 시간만큼 wait 하고 그래도 없으면 에러낸다.
+id 에 해당하는 큐를 반환함. 없으면 30초 동안 wait 하고 그래도 없으면 에러낸다.
 
 ### 1.5 메서드: `getSharedQueue(java.lang.String,int)`
```

## P69 · rev-getSharedQueue · revise

- 지시: getSharedQueue 의 Timeout 기본값은 10초가 아니라 30초다
- 청크 `de40151d-7110-409f-ba50-ae089cd71d47` (7.11.3.3 단일큐 예제 – Processor) · 정답 라벨 **M** · 종류 `edit`
- 근거: 설명 수정: getSharedQueue
- 자동 점검: 확인 필요 — 새 값 "30초" 이 없음
- 수용: x

```diff
@@ -28 +28 @@
     this.sharedQueueId = StringUtils.nvl(context.getInParameter("QUEUE_ID"), context.getJobExecutionId());
     // 10 초 타임아웃 적용
-    this.sharedQueue = getSharedQueue(this.sharedQueueId, 10);
+    this.sharedQueue = getSharedQueue(this.sharedQueueId, 30);
     this.sharedQueueReceiver = sharedQueue.createReceiver(context);
 }
```

