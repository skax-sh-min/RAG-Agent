# 6c 수정안 검토 — gemma-4-e4b-dep2 (.\gemma-4-E4B-it-Q4_K_M.gguf)

각 수정안을 읽고 `수용` 칸에 O(고치지 않고 승인할 만함) / X 를 적는다. 자동 점검은 참고용이다.

## P1 · dep-callBatchNow · deprecate

- 지시: callBatchNow 는 더 이상 사용되지 않습니다. 대신 callBatchAfterCommit 을 사용하세요.
- 청크 `b757982f-3d92-4083-96ca-8f5e91294d96` (47. PUSample.pmEDU99R0001 — 온디맨드 배치 호출) · 정답 라벨 **A** · 종류 `edit`
- 근거: 사용 중단 안내: callBatchNow
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -3 +3 @@
 ### 47.1 개요 (키워드: callBatchNow, 온디맨드)
 
+> **사용 중단** — callBatchNow은 더 이상 사용되지 않으므로 대신 callBatchAfterCommit을 사용해야 합니다.
+
 `PARAM1~5`의 `key=value`를 Map으로 파싱 후 `callBatchNow(JOB_ID, …)`.
```

## P2 · dep-callBatchNow · deprecate

- 지시: callBatchNow 는 더 이상 사용되지 않습니다. 대신 callBatchAfterCommit 을 사용하세요.
- 청크 `7bb201a3-4d52-424e-b99a-6f4ecf4d011a` (54. PUFwkTest — 온디맨드·대량입출력) · 정답 라벨 **M** · 종류 `edit`
- 근거: 사용 중단 안내: callBatchNow
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -15 +15 @@
 ### 55.1 개요 (키워드: callBatchNow)
 
+> **사용 중단** — callBatchNow은 더 이상 사용되지 않으므로 대신 callBatchAfterCommit을 사용해야 합니다.
+
 단순 온디맨드. `inParam.put("PARM1","commandline argument1")`.
```

## P3 · dep-callBatchNow · deprecate

- 지시: callBatchNow 는 더 이상 사용되지 않습니다. 대신 callBatchAfterCommit 을 사용하세요.
- 청크 `0f8458a3-6750-4c6b-8e33-f0e71a3e7511` (배치 어플리케이션 개발) · 정답 라벨 **D** · 종류 `edit`
- 근거: 사용 중단 안내: callBatchNow
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -8 +8 @@
 - 온디맨드 배치 Job 도 배치 스케줄러 (Job Mind) 에 등록되어 있어야 함
 
+> **사용 중단** — callBatchNow은 더 이상 사용되지 않으며, 대신 callBatchAfterCommit을 사용해야 합니다.
+
 | 배치 Job 실행 시기 | 함수 |
 | --- | --- |
```

## P4 · dep-callBatchNow · deprecate

- 지시: callBatchNow 는 더 이상 사용되지 않습니다. 대신 callBatchAfterCommit 을 사용하세요.
- 청크 `c2ff5cf8-fad6-47c0-a585-03fcfcef170f` (7.10.6.1 호출 API) · 정답 라벨 **D** · 종류 `edit`
- 근거: 사용 중단 안내: callBatchNow
- 자동 점검: OK 안내 블록 있음
- 수용: x

```diff
@@ -6 +6 @@
 - ‘(2) 커밋 후 호출’은 본 거래 커밋이 성공한 이후 온디맨드 배치 호출됩니다.
 
+> **사용 중단** — callBatchNow은 더 이상 사용되지 않으며, 대신 callBatchAfterCommit을 사용해야 합니다.
+
 | **메소드** | **설명** |
 | --- | --- |
```

## P5 · dep-callBatchNow · deprecate

- 지시: callBatchNow 는 더 이상 사용되지 않습니다. 대신 callBatchAfterCommit 을 사용하세요.
- 청크 `fb33e957-6e94-485f-8991-4a603c9c5123` (7.10.6 온라인에서 배치 호출(온디맨드 배치)) · 정답 라벨 **A** · 종류 `edit`
- 근거: 사용 중단 안내: callBatchNow
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -4 +4 @@
 온라인 거래에서 배치를 기동시킬 수 있습니다. 이것을 온디맨드 배치라고 합니다. 배치 스케줄러(잡마인드)에 등록된 job 단위로 수행되며 아래 예제와 같이 사용합니다.
 
+> **사용 중단** — callBatchNow은 더 이상 사용되지 않으므로 대신 callBatchAfterCommit을 사용하세요.
+
 ```java
```

## P6 · dep-reserveCallTimerService · deprecate

- 지시: reserveCallTimerService 는 이제 사용하지 않습니다.
- 청크 `0a8d1471-9c55-4b65-ab94-7753656ea44e` (6.10 메서드: `callAsyncServiceAfterRollback(java.lang.String,nexcore.framework.core.data.IDataSet,nexcore.framework.core.data.IOnlineContext)`) · 정답 라벨 **A** · 종류 `edit`
- 근거: 사용 중단 안내: reserveCallTimerService
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -29 +29 @@
 ### 6.11 메서드: `reserveCallTimerService(java.lang.String,int,nexcore.framework.core.data.IDataSet,nexcore.framework.core.data.IOnlineContext)`
 
+> **사용 중단** — reserveCallTimerService는 더 이상 사용되지 않습니다.
+
 **패키지:** `nexcore.framework.biz.online`  
 **소속 클래스:** `nexcore.framework.biz.online.ProcessUnit`
```

## P7 · dep-reserveCallTimerService · deprecate

- 지시: reserveCallTimerService 는 이제 사용하지 않습니다.
- 청크 `86fe0e0c-19c5-473b-b87b-55f41d7530a7` (3.4.9 연동거래) · 정답 라벨 **D** · 종류 `edit`
- 근거: 사용 중단 안내: reserveCallTimerService
- 자동 점검: OK 안내 블록 있음
- 수용: x

```diff
@@ -11 +11 @@
 연동거래에 사용되는 API들은 유형별로 아래와 같이 분류됩니다.
 
+> **사용 중단** — reserveCallTimerService는 더 이상 사용되지 않습니다.
+
 | 유형 | API | 설명 |
 | --- | --- | --- |
```

## P8 · dep-reserveCallTimerService · deprecate

- 지시: reserveCallTimerService 는 이제 사용하지 않습니다.
- 청크 `69980cfa-57e9-4a6d-a440-56a81d338463` (3.4.9.6 지연 비동기 연동거래) · 정답 라벨 **D** · 종류 `edit`
- 근거: 사용 중단 안내: reserveCallTimerService · 다듬은 문장이 검사를 통과하지 못해 지시문 문장을 그대로 넣음 · 길이가 기준 문장과 크게 다르다(293%)
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -4 +4 @@
 일정 시간 후에 수동 거래가 실행되는 것을 지연비동기 라고 하며, 대외 연계에서 타이머 거래 호출 용도로 주로 사용됩니다.
 
+> **사용 중단** — reserveCallTimerService은(는) 더 이상 사용되지 않습니다.
+
 ```java
 IDataSet pXYZ01002IReq = new DataSet();
```

## P9 · dep-reserveCallTimerService · deprecate

- 지시: reserveCallTimerService 는 이제 사용하지 않습니다.
- 청크 `0f819358-d6d1-45a9-a1b2-b28b8f81937d` (3.9.4.5 수동 타이머 처리 API) · 정답 라벨 **D** · 종류 `edit`
- 근거: 사용 중단 안내: reserveCallTimerService · 다듬은 문장이 검사를 통과하지 못해 지시문 문장을 그대로 넣음 · 길이가 기준 문장과 크게 다르다(321%)
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -2 +2 @@
 ##### 3.9.4.5 수동 타이머 처리 API
 
+> **사용 중단** — reserveCallTimerService은(는) 더 이상 사용되지 않습니다.
+
 `OutboundTimeoutCallback` 객체를 생성하지 않고 `reserveCallTimerService` (지연비동기) 메소드를 이용하여 타이머 처리를 하는 방식도 가능하며, 파라미터의 값들은 `OutboundTimeoutCallback`의 생성자와 같습니다.
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
+> **사용 중단** — CaseIgnoreHashMap은 더 이상 사용되지 않으므로 대신 HashMap을 사용하십시오.
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

## P24 · dep-PaddableDataOutputStream · deprecate

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

## P25 · dep-PaddableDataOutputStream · deprecate

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

## P26 · dep-PaddableDataOutputStream · deprecate

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

## P27 · dep-PaddableDataOutputStream · deprecate

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

## P28 · dep-FUAttcFileMng · deprecate

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

## P29 · dep-FUAttcFileMng · deprecate

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

## P30 · dep-FUAttcFileMng · deprecate

- 지시: FUAttcFileMng 는 더 이상 쓰이지 않습니다. 대신 FUFileMng 를 사용하세요.
- 청크 `8063d6fd-3e29-42c8-aa4a-39692e29fcb0` (4. FUAttcFileMng.fmFileInqSingle — 파일조회-단건) · 정답 라벨 **D** · 종류 `edit`
- 근거: 사용 중단 안내: FUAttcFileMng
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -1 +1 @@
 ## 4. FUAttcFileMng.fmFileInqSingle — 파일조회-단건
 
+> **사용 중단** — FUAttcFileMng은 더 이상 사용되지 않으므로 대신 FUFileMng을 사용해야 합니다.
+
 ### 4.1 개요
```

## P31 · dep-FUAttcFileMng · deprecate

- 지시: FUAttcFileMng 는 더 이상 쓰이지 않습니다. 대신 FUFileMng 를 사용하세요.
- 청크 `7e812ad8-54e4-42ee-8dac-b1eeea0cb880` (3.15.8 첨부파일 관리 (대량 입력 거래)) · 정답 라벨 **D** · 종류 `edit`
- 근거: 사용 중단 안내: FUAttcFileMng
- 자동 점검: OK 안내 블록 있음
- 수용: o

```diff
@@ -7 +7 @@
 [이미지 설명: 이 다이어그램은 사용자 인터페이스(UI)가 MCI 서버를 통해 요청을 보내고, 이 요청이 업무 서버의 핵심 로직을 거쳐 처리되는 시스템 아키텍처를 보여줍니다. 데이터는 NAS 공유 디스크와 같은 공통 자원 및 DB 테이블에 저장되며, 각 구성 요소들이 유기적으로 연결되어 기능을 수행하는 구조입니다.]
 
+> **사용 중단** — 첨부파일 정보 관리를 위해 프레임워크 FM(FUAttcFileMng, 첨부파일관리)을 사용하며, FUAttcFileMng은 더 이상 사용되지 않으므로 대신 FUFileMng을 사용해야 합니다.
+
 서버에 업로드 된 파일의 정보 관리를 위해 프레임워크 FM(FUAttcFileMng, 첨부파일관리)을 사용합니다. FUAttcFileMng(fwk-comfnct-njava)가 관리하는 항목과 fm 사용방법은 아래와 같습니다.
```

