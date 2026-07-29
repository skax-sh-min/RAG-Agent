# LLM_ROUTING — 멀티 LLM 라우팅 운영 참조 문서

> 구현 완료. 신규 프로바이더 추가, 운영 설정, 장애 대응을 위한 참조 가이드.

---

## 1. 아키텍처

```
┌──────────────────────────────────────────────────────────────────────┐
│  LlmRouter                                                           │
│                                                                      │
│  route(TaskType, RoutingMode) → 역할 순서 결정 → 프로바이더 선택      │
│                                                                      │
│  RoutingMode (UI 제어, 대화별 설정):                                  │
│    COST_FIRST    — LOCAL → NORMAL → PREMIUM (기본)                   │
│    QUALITY_FIRST — PREMIUM → NORMAL → LOCAL                         │
│    PROGRESSIVE   — COST_FIRST 시작 → 품질 임계값 미달 시 PREMIUM 재실행│
│    LOCAL_ONLY    — LOCAL 전용, 외부 API 호출 없음                    │
│                                                                      │
│  필터 체인 (각 역할 내):                                             │
│    1. TaskType 지원 여부                                             │
│    2. Circuit Breaker 미차단                                         │
│    3. API 키 유효성                                                  │
│    4. priority 순서 (낮을수록 우선, 동일 priority 후보는            │
│       잔여 permit 최다(least-in-flight)로 로드밸런싱)                │
│                                                                      │
│  동시성 게이트 (질의 경로 전용 — 프로바이더 선택과는 별개):           │
│    executeGated()/acquirePermit() → 프로바이더별 Semaphore로 서버    │
│    실제 --parallel 값을 초과해 보내지 않음. 대기 상한 초과 시 즉시   │
│    429(LlmBackpressureException) — 상세는 §6                        │
└──────────────────────────────────────────────────────────────────────┘
         ↕ ChatModel
┌──────────────────────────────────────────────────────────────────────┐
│ 프로바이더 역할(Role) × 유형(TaskType) 매트릭스                       │
│                                                                      │
│  LOCAL   (priority 0): local-fast       MICRO_TEXT — 잡무 전담 소형 모델(§6.21) │
│  LOCAL   (priority 1): local            BOTH       — 로컬 LLM 1 (범용, 무료)   │
│  LOCAL   (priority 1): local-2          BOTH       — 로컬 LLM 2 (로컬 1과 동일 priority → 로드밸런싱) │
│  LOCAL   (priority 0): local-vision     VISION     — Vision 전용 (선택, 기본 비활성) │
│  NORMAL  (priority 2): gemini-flash-lite TEXT       — 저비용 1순위(GEMINI_API_KEY1)     │
│  NORMAL  (priority 3): gemini-flash     TEXT       — 저비용 2순위(GEMINI_API_KEY2)      │
│  NORMAL  (priority 4): openai-mini      TEXT       — 저비용 fallback │
│  PREMIUM (priority 5): gemma-4-31b-1    TEXT       — 고추론(GEMINI_API_KEY1) — 아래와 동일 priority → 로드밸런싱 │
│  PREMIUM (priority 5): gemma-4-31b-2    TEXT       — 동일 모델, 다른 키로 처리량/쿼터 2배 (name은 반드시 달라야 함) │
│  PREMIUM (priority 6): openai           TEXT       — 고추론 fallback │
│                                                                      │
│  AgentGraph 노드 → TaskType 기준:                                    │
│    ClassifierService        → LIGHT_TEXT                             │
│    RetrievalService (쿼리)  → MICRO_TEXT                             │
│    AnswerService            → TEXT                                   │
│    CriticService            → TEXT                                   │
│    DirectAnswerService      → LIGHT_TEXT                             │
│    VisionDescriptionService → VISION                                 │
│    ImageTypeClassifier      → LIGHT_BOTH  (분류는 범용 멀티모달로)   │
│    KeywordExtractor (키워드+맥락) → MICRO_TEXT                                  │
│    RerankerService (opt-in) → TEXT        (SEARCH_RERANK_ENABLED=true일 때만) │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 2. 타입 정의

### TaskType

```java
public enum TaskType {
    MICRO_TEXT,   // 키워드+맥락·요약·제목·쿼리 확장 — 추론 불필요, 소형 모델로 오프로딩(§6.21 B안)
    LIGHT_TEXT,   // 분류, meta 직답 — 가볍지만 품질 민감(큰 모델 유지)
    TEXT,         // 답변 생성, Critic — 고추론 모델 선택 가능
    VISION,       // 이미지 설명 — 멀티모달 지원 필수
    LIGHT_BOTH,   // LIGHT_TEXT + VISION (로컬 범용 모델)
    BOTH          // TEXT + VISION 모두 처리 (외부 고성능 모델)
}
```

`supports()` 매핑: `MICRO_TEXT`→MICRO_TEXT만, `LIGHT_TEXT`→LIGHT_TEXT+MICRO_TEXT, `LIGHT_BOTH`→LIGHT_TEXT·MICRO_TEXT·VISION, `BOTH`→전체. MICRO_TEXT는 LIGHT_TEXT의 부분집합이라 소형(`type=MICRO_TEXT`) 미등록 시 상위 모델이 흡수(회귀 0).  
`type=VISION` 프로바이더는 VISION task에서만 선택됨 — 범용 `LIGHT_BOTH` 모델과 공존 가능.

### RoutingMode

```java
public enum RoutingMode {
    COST_FIRST,    // LOCAL → NORMAL → PREMIUM
    QUALITY_FIRST, // PREMIUM → NORMAL → LOCAL
    PROGRESSIVE,   // COST_FIRST 먼저 → qualityScore < threshold 시 PREMIUM 재실행
    LOCAL_ONLY     // LOCAL 전용 (미연결 시 LlmProviderExhaustedException)
}
```

### ProviderRole

`LOCAL` — 로컬 LLM (무료, 빠름), `NORMAL` — 저비용 외부 API, `PREMIUM` — 고추론 외부 API

---

## 3. 프로바이더 설정 (application.properties)

```properties
app.llm.default-routing-mode=COST_FIRST
app.llm.circuit-breaker-minutes=4
app.llm.progressive-threshold=0.6
# §6.18 — sampling temperature + response cap (were dead/hardcoded before). temperature/max-tokens
# are baked into each provider bean at startup (view-only, restart to change); direct-temperature
# is read per-call by DirectAnswerService (hot-editable via /settings). max-tokens applies to
# blocking calls only — streaming chat answers are uncapped (bounded by app.sse-*-timeout-seconds).
app.llm.temperature=${LLM_TEMPERATURE:0.0}
app.llm.direct-temperature=${DIRECT_LLM_TEMPERATURE:0.1}
app.llm.max-tokens=${LLM_MAX_TOKENS:6000}
# 질의 경로 동시성 게이트 기본값(서버의 실제 --parallel 값에 맞춘다) + 대기 상한
app.llm.default-provider-concurrency=${LLM_DEFAULT_PROVIDER_CONCURRENCY:3}
app.llm.permit-wait-timeout-seconds=${LLM_PERMIT_WAIT_TIMEOUT_SECONDS:20}

# 등장 순서 = 인덱스 순서(사람이 읽기 편하도록 맞춤; Spring 바인딩 자체는 파일 내 줄 순서와 무관하고
# "활성(비주석) 프로바이더의 번호가 0부터 연속"이기만 하면 된다): 소형 로컬(MICRO_TEXT) → 로컬 LLM 1
# → 로컬 LLM 2(로컬 1과 로드밸런싱) → 외부 NORMAL 3종 + PREMIUM 3종(gemma-4-31b-1/-2는 서로 다른
# Gemini 키로 로드밸런싱되는 2대) → Vision 전용(선택, 기본 비활성).

# ── [LOCAL] 소형 로컬 LLM 1 — MICRO_TEXT 잡무 전담(§6.21) ─────────
# 키워드+맥락·요약·제목·MultiQuery 쿼리확장을 500MB급 소형 모델로 분리. priority=0 → 아래
# 로컬 LLM 1/2(priority=1)보다 먼저 선택된다. 없어도 BOTH가 MICRO_TEXT를 흡수하므로 회귀 0 —
# 비활성화하려면 이 블록을 통째로 주석 처리.
app.llm.providers[0].name=local-fast
app.llm.providers[0].base-url=${LOCAL_FAST_LLM_URL:http://localhost:8090/v1}
app.llm.providers[0].api-key=${LOCAL_FAST_LLM_KEY:}
app.llm.providers[0].model=${LOCAL_FAST_LLM_MODEL:Qwen3.5-0.8B-Q4_K_M.gguf}
app.llm.providers[0].type=MICRO_TEXT
app.llm.providers[0].role=LOCAL
app.llm.providers[0].priority=0
app.llm.providers[0].stream=true
#app.llm.providers[0].concurrency=4

# ── [LOCAL] 로컬 LLM 1 — 범용 (TEXT/분류/직답/Vision) ──────────────
# type=BOTH → 소형이 처리하지 않는 모든 태스크 처리. 없으면 COST_FIRST 시 NORMAL부터 시작.
app.llm.providers[1].name=local
app.llm.providers[1].base-url=${LOCAL_LLM_URL:http://localhost:1234/v1}
app.llm.providers[1].api-key=${LOCAL_LLM_KEY:}
app.llm.providers[1].model=${LOCAL_LLM_MODEL:google/gemma-4-e4b}
app.llm.providers[1].type=BOTH
app.llm.providers[1].role=LOCAL
app.llm.providers[1].priority=1
app.llm.providers[1].stream=true
# app.llm.providers[1].concurrency=3

# ── [LOCAL] 로컬 LLM 2 — 로컬 LLM 1과 로드밸런싱 (처리량 확장) ────
# local과 동일 role(LOCAL)·동일 priority(1)·다른 base-url로 등록 — LlmRouter가 잔여 permit이
# 더 많은(least-in-flight) 쪽으로 자동 분산한다(§5.4 예제 5/7). 총 동시 처리량 = 등록 대수 × concurrency.
# 서버가 없으면 한 번 실패 후 같은 요청 안에서 로컬 LLM 1로 자동 폴백(사용자에게는 보이지 않음).
app.llm.providers[2].name=local-2
app.llm.providers[2].base-url=${LOCAL_LLM_URL_2:http://localhost:1235/v1}
app.llm.providers[2].api-key=${LOCAL_LLM_KEY_2:${LOCAL_LLM_KEY:}}
app.llm.providers[2].model=${LOCAL_LLM_MODEL_2:${LOCAL_LLM_MODEL:google/gemma-4-e4b}}
app.llm.providers[2].type=BOTH
app.llm.providers[2].role=LOCAL
app.llm.providers[2].priority=1
app.llm.providers[2].stream=true
# app.llm.providers[2].concurrency=3

# ── [NORMAL] Gemini Flash Lite — 저비용 1순위 ────────────────────
# GEMINI_API_KEY1 미설정 시 시작 시 warn 로그 후 자동 비활성화
app.llm.providers[3].name=gemini-flash-lite
app.llm.providers[3].base-url=${GEMINI_BASE_URL:https://generativelanguage.googleapis.com/v1beta/openai/}
app.llm.providers[3].api-key=${GEMINI_API_KEY1:}
app.llm.providers[3].model=gemini-3.1-flash-lite
app.llm.providers[3].type=TEXT
app.llm.providers[3].role=NORMAL
app.llm.providers[3].priority=2

# ── [NORMAL] Gemini Flash — 저비용 2순위 ────────────────────────
# GEMINI_API_KEY2 미설정 시 시작 시 warn 로그 후 자동 비활성화
app.llm.providers[4].name=gemini-flash
app.llm.providers[4].base-url=${GEMINI_BASE_URL:https://generativelanguage.googleapis.com/v1beta/openai/}
app.llm.providers[4].api-key=${GEMINI_API_KEY2:}
app.llm.providers[4].model=gemini-2.5-flash
app.llm.providers[4].type=TEXT
app.llm.providers[4].role=NORMAL
app.llm.providers[4].priority=3

# ── [NORMAL] OpenAI Mini (fallback) ──────────────────────────────
# OPENAI_API_KEY 미설정 시 시작 시 warn 로그 후 자동 비활성화
app.llm.providers[5].name=openai-mini
app.llm.providers[5].base-url=${OPENAI_BASE_URL:https://api.openai.com}
app.llm.providers[5].api-key=${OPENAI_API_KEY:}
app.llm.providers[5].model=gpt-4o-mini
app.llm.providers[5].type=TEXT
app.llm.providers[5].role=NORMAL
app.llm.providers[5].priority=4

# ── [PREMIUM] Gemma 4 31B — 고추론, GEMINI_API_KEY1 인스턴스 ──────
# GEMINI_API_KEY1 미설정 시 시작 시 warn 로그 후 자동 비활성화
app.llm.providers[6].name=gemma-4-31b-1
app.llm.providers[6].base-url=${GEMINI_BASE_URL:https://generativelanguage.googleapis.com/v1beta/openai/}
app.llm.providers[6].api-key=${GEMINI_API_KEY1:}
app.llm.providers[6].model=gemma-4-31b-it
app.llm.providers[6].type=TEXT
app.llm.providers[6].role=PREMIUM
app.llm.providers[6].priority=5

# ── [PREMIUM] Gemma 4 31B — 고추론, GEMINI_API_KEY2 인스턴스 ──────
# [6]과 model/role/priority(5)가 동일 — API 키와 name만 다르므로 findFirst()가 동일 priority
# 그룹으로 묶어 잔여 permit이 더 많은(least-in-flight) 쪽으로 자동 분산한다(§6).
# 물리적으로 같은 Gemini gemma-4-31b 모델을 두 키로 나눠 호출해 PREMIUM 티어의 실질
# 처리량/쿼터를 두 배로 늘리는 구성 — 로컬 LLM 2(§3 "로컬 LLM 2" 참고)와 동일한 패턴을
# PREMIUM 클라우드 티어에 적용한 것.
# ⚠️ name은 반드시 서로 달라야 한다. 동시성 게이트 세마포어·서킷브레이커·/settings 토글·
# 호출 내 "이미 시도함" 집합·llm_usage 라벨이 모두 name을 키로 쓰기 때문에, 이름이 같으면
# 세마포어 하나를 나눠 쓰고(처리량 2배가 안 됨) 한쪽이 429로 차단될 때 다른 쪽도 함께 차단돼
# 두 키를 둔 목적 자체가 사라진다. 기동 시 중복 검사는 없다.
# GEMINI_API_KEY2 미설정 시 시작 시 warn 로그 후 자동 비활성화
app.llm.providers[7].name=gemma-4-31b-2
app.llm.providers[7].base-url=${GEMINI_BASE_URL:https://generativelanguage.googleapis.com/v1beta/openai/}
app.llm.providers[7].api-key=${GEMINI_API_KEY2:}
app.llm.providers[7].model=gemma-4-31b-it
app.llm.providers[7].type=TEXT
app.llm.providers[7].role=PREMIUM
app.llm.providers[7].priority=5

# ── [PREMIUM] OpenAI GPT-5o (fallback) ───────────────────────────
# OPENAI_API_KEY 미설정 시 시작 시 warn 로그 후 자동 비활성화
app.llm.providers[8].name=openai
app.llm.providers[8].base-url=${OPENAI_BASE_URL:https://api.openai.com}
app.llm.providers[8].api-key=${OPENAI_API_KEY:}
app.llm.providers[8].model=gpt-5o
app.llm.providers[8].type=TEXT
app.llm.providers[8].role=PREMIUM
app.llm.providers[8].priority=6

# ── [LOCAL] Vision 전용 로컬 모델 (선택, 기본 비활성) ─────────────
# type=VISION → VISION task에서 BOTH(로컬 LLM 1/2)보다 우선 선택됨. LLaVA, Qwen2-VL 등 권장.
# 다음 빈 인덱스 [9] 사용 — 로컬 LLM을 3대 이상으로 늘렸다면 그만큼 밀어서 조정.
# app.llm.providers[9].name=local-vision
# app.llm.providers[9].base-url=${LOCAL_LLM_URL:http://localhost:1235/v1}
# app.llm.providers[9].api-key=${LOCAL_LLM_KEY:}
# app.llm.providers[9].model=llava-1.6-34b
# app.llm.providers[9].type=VISION
# app.llm.providers[9].role=LOCAL
# app.llm.providers[9].priority=0

# ── 병렬 인덱싱 제어 ──────────────────────────────────────────────
# 인덱싱 LLM 동시 호출 피크 ≈ FILES × LLM (파일끼리 단계가 겹칠 수 있고, 교정/구조화 세마포어는
# 파일마다 별개로 생성됨). FILES=1이면 피크가 정확히 LLM 값으로 고정된다.
app.indexing.max-concurrent-files=${INDEXING_MAX_FILES:1}
app.indexing.max-concurrent-llm-calls=${INDEXING_MAX_LLM:3}
# 키워드+맥락 추출 배치 크기(§10.8.2) — 청크 N개를 한 LLM 호출로 묶어 왕복을 ceil(청크수/N)로 절감.
# 1=배치 없음(청크당 1콜, 이전 동작). 배치가 클수록 응답도 길어지므로 로컬 모델에서 타임아웃이
# 잦으면 keyword-timeout-seconds도 함께 올린다.
app.indexing.keyword-batch-size=${INDEXING_KEYWORD_BATCH_SIZE:2}
```

---

## 4. 라우팅 시나리오

| 상황 | COST_FIRST | QUALITY_FIRST | PROGRESSIVE | LOCAL_ONLY |
|------|-----------|--------------|------------|-----------|
| 로컬 LLM 정상 | local→normal→premium | premium→normal→local | local/normal 먼저 | **local 단독** |
| 로컬 LLM 없음 | normal→premium | premium→normal | normal 먼저 | **exhausted** |
| gemini-flash 429 | openai-mini→premium | premium→openai-mini | openai-mini 먼저 | local 단독 |
| 모든 NORMAL 차단 | PREMIUM fallback | PREMIUM 정상 | PREMIUM 재실행 | local 단독 |
| 전체 소진 | `LlmProviderExhaustedException` | 동일 | 동일 | 동일 |

**PROGRESSIVE 흐름**:
1. COST_FIRST로 Answer 실행
2. `qualityScore` < `progressiveThreshold`(기본 0.6) AND 재검색 소진 시
3. QUALITY_FIRST로 Answer 재실행 (동일 검색 결과 재사용)
4. 응답 메타에 `🔝 고추론 재분석 → {providerName}` 배지 표시

> 현재 `qualityScore`는 sufficient=true→1.0, false→0.0 이진값. 추후 스칼라 점수로 확장 가능.

---

## 5. Circuit Breaker

- HTTP 429/402/503(과부하성 오류): `Retry-After` 헤더 파싱 → 해당 시간 차단. 헤더 없으면 **폴백 가능 여부**에 따라 분기:
  - 폴백 프로바이더가 있으면 `circuit-breaker-minutes`(기본값) 적용
  - 폴백이 전혀 없는 유일 프로바이더면 30초로 단축 차단(다중 분 단위 전면 다운 방지)
- 그 외 4xx/5xx 및 기타 예외: 30초 차단 후 다음 프로바이더 시도.
- 차단 만료는 다음 라우팅 시 자동 해제.
- `/llm-usage` 페이지에서 차단 상태 + 남은 시간 카운트다운 확인 가능 (30초마다 자동 갱신).
- **동시성 백프레셔(§6, 아래)는 Circuit Breaker와 별개**다 — 용량 초과는 프로바이더 장애가 아니므로 차단하지 않는다.
- **"30초"의 근거**: `LlmRouter.SHORT_BLOCK_SECONDS`("30") 하드코딩 상수 하나를 **세 갈래**(폴백 없는 과부하 차단·기타 4xx/5xx·일반 예외)가 공유한다. 이 값은 폴백 없는 프로바이더 완화 로직을 구현하며 새로 정한 게 아니라, 그 이전부터 "기타 4xx/5xx·일반 예외" 차단에 쓰이던 기존 값을 그대로 재사용한 것 — `permit-wait-timeout-seconds`(기본 20초)와 비슷한 수준이라 재사용에 무리가 없었다. `app.llm.default-provider-concurrency`/`app.llm.permit-wait-timeout-seconds`와 달리 **프로퍼티로 외부화되어 있지 않다** — 값을 바꾸려면 코드 수정이 필요하다.
  - 더 짧게(예: 10~20초) 바꾸면 일시적 장애에서 더 빨리 회복되지만, 실제 서버 복구가 그보다 오래 걸리는 상황이면 재시도가 더 잦아져(연결·요청·로그 비용만 반복) 실질적인 다운타임 단축 효과 없이 노이즈만 늘 수 있다.
  - 세 갈래가 상수 하나를 공유하므로, 폴백 없는 과부하 차단만 다르게(예: 10초) 가져가고 싶다면 상수를 분리해야 한다.

---

## 6. 동시성 게이트 + 백프레셔

여러 사용자의 질문이 동시에 도착하면, 앱은 프로바이더별로 실제 서버가 처리 가능한 동시 요청 수(`llama-server --parallel` 등)를 절대 초과해 보내지 않는다.

```
┌────────────────────────────────────────────────────────────────────┐
│  LlmRouter.executeGated() / acquirePermit()                        │
│                                                                     │
│  각 LlmProvider마다 Semaphore(concurrency) 보유 (provider명 키)     │
│    크기 = providers[N].concurrency, 미설정 시 default-provider-    │
│           concurrency(기본 3)                                      │
│                                                                     │
│  요청 도착 → tryAcquire(permit-wait-timeout-seconds, 기본 20초)     │
│    ├─ 획득 성공 → LLM 호출 → 응답 후 permit 반환                    │
│    └─ 대기 상한 초과 → LlmBackpressureException                    │
│          → HTTP 429 + Retry-After (RAG-LLM-002)                    │
│          → Circuit Breaker 차단 없음, 다른 프로바이더 재시도도 없음  │
│             (용량 압박이지 프로바이더 장애가 아니므로 즉시 전파)     │
└────────────────────────────────────────────────────────────────────┘
```

**적용 범위 — 질의(채팅) 경로만, 인덱싱/백그라운드는 미적용**:

| 게이트 적용 (질의 경로) | 게이트 미적용 (인덱싱/백그라운드) |
|---|---|
| `ClassifierService` (분류) | `KeywordExtractor` (키워드+맥락 추출 — §10.8.2로 청크를 배치 묶음당 1콜로 호출, `app.indexing.keyword-batch-size`) |
| `AnswerService` (블로킹+스트리밍+PROGRESSIVE+평가) | `MarkdownCorrectionService` (MD 포맷 교정) |
| `DirectAnswerService` | `VisionDescriptionService` |
| `RerankerService` (opt-in) | `ImageTypeClassifier` |
| `RetrievalService`의 MultiQuery 확장 모델(`ConcurrencyLimitingChatModel` 데코레이터 경유) | `TextToMarkdownService` (TXT 구조화) |
| | `ConversationSummarizerService.precompute()`(fire-and-forget) |
| | `ThreadMetaService.generateTitleAsync()`(fire-and-forget) |

인덱싱 경로는 이미 자체 세마포어(`app.indexing.max-concurrent-llm-calls`)로 동시성을 제어하고 있고, 마감시한 있는 동기 HTTP 호출자가 없으므로 이중 게이팅을 피하기 위해 의도적으로 제외했다 — `LlmRouter.executeWithTracking()`(게이트 미적용, 기존 동작 그대로)을 그대로 사용한다.

**설정**:

| 프로퍼티 | 환경변수 | 기본값 | 설명 |
|---|---|---|---|
| `app.llm.default-provider-concurrency` | `LLM_DEFAULT_PROVIDER_CONCURRENCY` | `3` | 프로바이더별 동시 처리 상한 기본값(개별 프로바이더가 `concurrency`를 지정하지 않을 때) |
| `app.llm.providers[N].concurrency` | — (인덱스 프로퍼티) | 위 기본값 | 프로바이더별 개별 오버라이드 — 서버의 실제 `--parallel` 값에 맞춘다 |
| `app.llm.permit-wait-timeout-seconds` | `LLM_PERMIT_WAIT_TIMEOUT_SECONDS` | `20` | 슬롯 대기 상한(초). `LLM_READ_TIMEOUT_SECONDS`(기본 180)보다 훨씬 짧게 유지해 사용자가 오래 기다리지 않고 빠른 429를 받도록 함 |

SSE 스트리밍에서는 `error.llm.backpressure` 메시지("현재 요청이 몰려 있습니다. 잠시 후 다시 시도해 주세요.")로 우아하게 종료된다(`StreamingAgentService`). REST/HTMX 블로킹 경로는 `GlobalExceptionHandler`가 `RagException.retryAfterSeconds()`를 읽어 `Retry-After` 헤더를 자동 부착한다.

**인플라이트 single-flight (임베딩 전용)**: 위 세마포어 게이트와는 별개로, `CachingEmbeddingModel`(질의 임베딩 캐시, Phase 7-A)이 동시 요청 중복 계산까지 제거한다. 4명이 완전히 동일한 질문을 거의 동시에 물으면, 첫 호출(owner)만 실제로 임베딩 API를 호출하고 나머지(joiner)는 그 결과를 `CompletableFuture.join()`으로 공유한다(`ConcurrentHashMap<key, CompletableFuture<float[]>>` 기반) — thundering herd 방지.  
owner가 실패하면 joiner에도 동일 예외가 전파되고 in-flight 항목은 정리되어 다음 호출이 새로 재시도한다.  
완전 동일한(정규화 후) 텍스트만 병합되며, 근사 질문은 여전히 캐시 미스(§10.5 시맨틱 캐시 영역, 보류). CLASSIFIER 등 다른 텍스트 응답에는 적용되지 않는다 — 오늘 기준 그런 캐시 자체가 없다.

**동일 우선순위 프로바이더 로드밸런싱 (처리량 확장)**: 같은 `role`·같은 `priority`로 프로바이더를 여러 대 등록하면(§3 "LOCAL 로드밸런싱 예시" 참고) `LlmRouter.findFirst()`가 이제 그중 **잔여 permit이 가장 많은(least-in-flight) 프로바이더**를 선택한다 — 각 프로바이더가 위 동시성 게이트의 `Semaphore`를 하나씩 갖고 있으므로 잔여 permit 수를 즉시 조회할 수 있어 별도 상태 없이 "least-connections" 로드밸런싱이 된다.

```
findFirst(role, priority 오름차순 순회)
  → 그 role에서 가장 낮은 priority를 가진 후보 그룹 선택(우선순위는 그대로 tie-break/장애조치 순서)
    ├─ 후보가 1개 → 기존과 동일하게 그대로 선택
    └─ 후보가 여러 개(동일 priority) → 잔여 permit(availablePermits())이 가장 많은 쪽 선택
         · 둘 다 동일하면(예: 둘 다 유휴) 먼저 등록된 프로바이더로 결정적 tie-break
```

- **priority가 다르면 부하와 무관하게 낮은 priority가 항상 우선** — 로드밸런싱은 동일 priority 그룹 내부에서만 일어난다. 부하가 높다고 다음 priority(예: 외부 유료 API)로 자동 전환되지는 않는다 — 그건 프로바이더가 실제로 응답 실패(429/402/503/기타)할 때만 §5 Circuit Breaker가 처리하는 영역이다.
- **총 동시 처리량 = 등록 대수 × per-provider concurrency** (예: LOCAL 2대 × concurrency 3 = 6).
- 임베딩 프로바이더도 이제 로드밸런싱된다(§6.21 E1) — `EmbeddingModel` 체인이라 라우팅 지점은 LLM 경로와 다르지만, `LoadBalancingEmbeddingModel`이 다중 임베딩 엔드포인트(`app.embedding.additional-base-urls`)를 least-in-flight로 분산한다. 인덱싱 시 병렬 서브배치(§6.21 E2, `app.embedding.max-concurrent-batches`)와 결합하면 단일 대용량 문서도 여러 엔드포인트를 동시에 채운다. 설정은 OPERATOR_MANUAL §3.2 "임베딩 병렬화" 참고.
- **좋아요 기반 큐레이션 Q&A 임베딩(§10.10)**은 이 표의 LLM 채팅 게이트(위 표)와 무관하다 — `VectorStoreFacade.add()`를 통해 인덱싱과 동일한 임베딩 파이프라인(uncached, §10.9.4)을 타므로 여기 §6의 임베딩 로드밸런싱·병렬 서브배치 대상에 자연히 포함된다. 좋아요 즉시가 아니라 3초 디바운스 후 배경 가상 스레드에서 실행되므로 채팅 응답 지연에는 영향이 없다. 별도의 라우팅/동시성 설정은 필요 없다.
- `/llm-usage`에서 프로바이더별 사용량 집계로 실제 분산 여부를 확인할 수 있다.

---

## 7. 사용량 추적 (SQLite — memory.db 공유)

```sql
CREATE TABLE IF NOT EXISTS llm_usage (
    provider_name  TEXT    NOT NULL,
    usage_date     TEXT    NOT NULL,   -- 'YYYY-MM-DD'
    input_tokens   INTEGER NOT NULL DEFAULT 0,
    output_tokens  INTEGER NOT NULL DEFAULT 0,
    call_count     INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (provider_name, usage_date)
);
```

모니터링: `GET /api/llm/usage` (일간·주간·월간), `GET /api/llm/usage/history?days=N` (Chart.js용)

---

## 8. 제약 및 주의사항

- **프로바이더 자동 비활성화**: `api-key`가 비어있으면 (`${GEMINI_API_KEY:}` 등 빈 기본값) 시작 시 warn 로그 출력 후 해당 프로바이더를 제외. 키 미설정만으로 providers 블록을 남겨둔 채 비활성화 가능
- **LOCAL_ONLY**: LOCAL 미연결·차단 시 외부 API fallback 없이 즉시 exhausted — UI에서 오류 안내 필요
- **라우팅 전략 셀렉터 자체 숨김**: 위 항목은 LOCAL_ONLY "개별 옵션"을 `disabled` 처리하는 것과 달리, `app.llm.default-routing-mode`(=`LLM_ROUTING_MODE`)가 `LOCAL_ONLY`면 채팅 사이드바의 라우팅 전략 드롭다운 **전체**가 렌더링되지 않는다 — 이 배포에서는 프로바이더가 LOCAL 하나뿐이라 어떤 모드를 골라도 결과가 동일하므로, 선택지 자체를 없애는 편이 더 정확하다.
  - 판정 경로: `LlmRouter.getDefaultMode()` → `ChatController.populateChatModel()`의 `localOnlyDeployment` 모델 속성 → `chat.html`의 `th:if="${!localOnlyDeployment}"`.
  - 대화별 `routingMode`(스레드 메타에 저장된 현재 선택값)가 아니라 **배포 전체의 기본값**을 기준으로 판단한다 — 그렇지 않으면 사용자가 LOCAL_ONLY를 고르는 순간 셀렉터가 사라져 다시 못 바꾸는 UX 함정이 생긴다.
- **같은 Gemini API 키 공유**: `GEMINI_API_KEY1`은 gemini-flash-lite(NORMAL)·gemma-4-31b-1(PREMIUM, `providers[6]`)가, `GEMINI_API_KEY2`는 gemini-flash(NORMAL)·gemma-4-31b-2(PREMIUM, `providers[7]`)가 각각 공유한다 — 한 키에 Rate Limit이 걸리면 NORMAL과 PREMIUM 양쪽이 동시에 차단될 수 있음. OpenAI를 PREMIUM fallback(`providers[8]`)으로 유지 권장
- **classifyOnly() 토큰 미누적**: `AgentService`가 선행 분류 시 `AgentState` 토큰 집계에서 1회 누락 (허용된 MVP 트레이드오프)
- **tried 집합 순환 방지**: `executeWithTracking()` 내 tried 집합이 모든 프로바이더를 포함하면 exhausted — 최대 재귀 = 프로바이더 수
- **Vision 라우팅**: `type=VISION` 모델 미등록 시 `LIGHT_BOTH` → `BOTH` 순으로 fallback. Vision 문서 많으면 `local-vision` 등록 권장
- **동시성 게이트(§6) 크기 설정 실수**: `providers[N].concurrency`를 서버의 실제 `--parallel`보다 크게 잡으면 앱이 스스로 429/타임아웃을 유발할 수 있다(서버가 처리 못 할 요청까지 통과시킴). 반대로 너무 작게 잡으면 여유 용량을 못 씀 — 서버 설정값과 일치시키는 것이 원칙
- **동일 우선순위 프로바이더 다중 등록 시 자동 로드밸런싱**: `findFirst()`가 같은 role·같은 priority 후보 중 동시성 게이트의 잔여 permit이 가장 많은(least-in-flight) 프로바이더를 선택 — 여러 대 등록하면 실제로 부하가 분산된다. priority가 다르면 부하와 무관하게 낮은 priority가 항상 우선(동일 priority 그룹 내부에서만 분산). 설정 방법은 §3 "로컬 LLM 2 — 로컬 LLM 1과 로드밸런싱" 참고

---

## 9. 태스크별 모델 분리 — 소형(경량) LLM 오프로딩 (PLAN §6.21)

기본 배포는 단일 LOCAL(`type=BOTH`, priority 0)이 답변부터 잡무까지 전부 처리한다. 추론이 필요 없는 고빈도 잡무를 별도 소형 모델로 내리면 (1) 큰 모델이 답변 생성에 전념하고 (2) 두 모델이 **독립 Semaphore**(§6)를 써 슬롯 경합이 사라진다 → 대화 응답 지연 감소.

**`TaskType.MICRO_TEXT`(§6.21 B안)**: 추론 불필요 잡무 전용 태스크 타입. `KeywordExtractor`·`ConversationSummarizerService`·`ThreadMetaService`·`RetrievalService`(MultiQuery 쿼리 확장, §6.21 작업2) 4개 백그라운드 호출부가 이 타입으로 라우팅된다.  
**분류(`ClassifierService`)·meta 직답(`DirectAnswerService`)은 품질 민감이라 `LIGHT_TEXT`로 남겨 큰 모델이 처리**한다.  
문서 변환 백그라운드(`MarkdownCorrectionService` MD 서식 교정·`TextToMarkdownService` TXT 구조화)도 구조 충실도가 중요해 `LIGHT_TEXT` 유지(공격적 A안에서만 소형으로 내려감).

**메커니즘 — `findFirst()` priority + 프로바이더별 Semaphore 재사용(§6)**. 소형을 `type=MICRO_TEXT`·`role=LOCAL`·`priority=0`, 큰 모델을 `type=BOTH`·`priority=1`로 등록하면:

| 태스크 (TaskType) | 담당 | 이유 |
|---|---|---|
| 키워드+맥락·요약·제목·MultiQuery 쿼리확장 (`MICRO_TEXT`) | **소형** | MICRO_TEXT eligible=[소형(p0), 큰(p1)] → 최저 priority=소형 |
| 분류·meta 직답 (`LIGHT_TEXT`) | **큰 모델** | 소형(MICRO_TEXT)은 `supports(LIGHT_TEXT)=false` → 큰 BOTH만 eligible |
| 답변·Critic·Rerank (`TEXT`) | **큰 모델** | 소형은 `supports(TEXT)=false` |
| Vision·이미지 분류 (`VISION`/`LIGHT_BOTH`) | **큰 모델** | 소형은 이미지 미지원 |

- **폴백/회귀 0**: `MICRO_TEXT`는 `LIGHT_TEXT`/`LIGHT_BOTH`/`BOTH`가 모두 지원(부분집합)하므로, 소형 다운·미등록 시 큰 모델이 그대로 흡수한다. **예외: 대화 요약**(`ConversationSummarizerService`)만은 이 폴백을 타지 않는다 — 소형(`role=LOCAL, priority=0`)이 없으면(`LlmRouter.hasMicroTextOffloadProvider()=false`) LLM 요약 자체를 생략하고 원본 history로 폴백한다(부가 기능이 답변용 모델의 동시성 슬롯을 잠식하지 않게 하려는 의도적 게이팅. 답변이 이미 `## 요약` 섹션을 갖고 있으면 소형 유무와 무관하게 그 내용을 그대로 재사용하므로 LLM 호출 0회). `RetrievalService`는 `MICRO_TEXT→LIGHT_TEXT→TEXT` 순 폴백이라 cloud-only(LOCAL 없음)에서도 구성 실패가 없다.
- **priority 필수**: 소형(0) < 큰(1). 동률이면 §6 로드밸런서가 둘 사이에 분산해 **절반만** 오프로딩된다.
- **인덱스 연속성**: `providers[N]`은 0부터 연속이어야 바인딩(파일 내 줄 순서 자체는 무관). 기본 파일은 `[0]`=소형·`[1]`=로컬 LLM 1·`[2]`=로컬 LLM 2·`[3]~[8]`=외부(PREMIUM gemma-4-31b-1/-2가 `[6]`·`[7]` 두 키로 로드밸런싱)·`[9]`=Vision(선택, §3 예시).

**더 공격적 오프로딩(A안)**: 소형을 `type=LIGHT_TEXT`로 등록하면 분류·직답까지 소형이 처리한다(`LIGHT_TEXT`가 MICRO_TEXT도 지원하므로 둘 다 흡수). 분류 오분류는 라우팅 정확도에, 직답은 사용자 노출에 직결되므로 채택 전 검색 품질 평가 하네스(OPERATOR_MANUAL §6.6)로 회귀를 측정할 것. 설정 예제는 OPERATOR_MANUAL §5.4 "예제 6 — 소형(경량) LLM 분리".
