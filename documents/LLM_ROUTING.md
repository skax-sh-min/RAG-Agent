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
│    DUAL          — LOCAL + 외부(NORMAL→PREMIUM) 병렬 → 두 결과 표시  │
│    LOCAL_ONLY    — LOCAL 전용, 외부 API 호출 없음                    │
│                                                                      │
│  필터 체인 (각 역할 내):                                             │
│    1. TaskType 지원 여부                                             │
│    2. Circuit Breaker 미차단                                         │
│    3. API 키 유효성                                                  │
│    4. priority 순서 (낮을수록 우선)                                  │
└──────────────────────────────────────────────────────────────────────┘
         ↕ ChatModel
┌──────────────────────────────────────────────────────────────────────┐
│ 프로바이더 역할(Role) × 유형(TaskType) 매트릭스                       │
│                                                                      │
│  LOCAL   (priority 0): 범용 로컬 LLM    LIGHT_BOTH — 무료            │
│  LOCAL   (priority 0): local-vision     VISION     — Vision 전용     │
│  NORMAL  (priority 1): gemini-flash     BOTH       — 저비용 범용     │
│  NORMAL  (priority 2): openai-mini      BOTH       — 저비용 fallback │
│  PREMIUM (priority 3): gemini-pro       BOTH       — 고추론 범용     │
│  PREMIUM (priority 4): openai           BOTH       — 고추론 fallback │
│                                                                      │
│  AgentGraph 노드 → TaskType 기준:                                    │
│    ClassifierService        → LIGHT_TEXT                             │
│    RetrievalService (쿼리)  → LIGHT_TEXT                             │
│    AnswerService            → TEXT                                   │
│    CriticService            → TEXT                                   │
│    DirectAnswerService      → LIGHT_TEXT                             │
│    VisionDescriptionService → VISION                                 │
│    ImageTypeClassifier      → LIGHT_BOTH  (분류는 범용 멀티모달로)   │
│    KeywordMetadataEnricher  → LIGHT_TEXT                             │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 2. 타입 정의

### TaskType

```java
public enum TaskType {
    LIGHT_TEXT,   // 분류, 키워드 추출, 쿼리 확장 — LOCAL으로 충분
    TEXT,         // 답변 생성, Critic — 고추론 모델 선택 가능
    VISION,       // 이미지 설명 — 멀티모달 지원 필수
    LIGHT_BOTH,   // LIGHT_TEXT + VISION (로컬 범용 모델)
    BOTH          // TEXT + VISION 모두 처리 (외부 고성능 모델)
}
```

`supports()` 매핑: `LIGHT_BOTH`→LIGHT_TEXT·VISION 처리, `BOTH`→전체 처리.  
`type=VISION` 프로바이더는 VISION task에서만 선택됨 — 범용 `LIGHT_BOTH` 모델과 공존 가능.

### RoutingMode

```java
public enum RoutingMode {
    COST_FIRST,    // LOCAL → NORMAL → PREMIUM
    QUALITY_FIRST, // PREMIUM → NORMAL → LOCAL
    PROGRESSIVE,   // COST_FIRST 먼저 → qualityScore < threshold 시 PREMIUM 재실행
    DUAL,          // LOCAL + 외부(NORMAL→PREMIUM) 병렬 (LOCAL 등록 필수)
    LOCAL_ONLY     // LOCAL 전용 (미연결 시 LlmProviderExhaustedException)
}
```

### ProviderRole

`LOCAL` — 로컬 LLM (무료, 빠름), `NORMAL` — 저비용 외부 API, `PREMIUM` — 고추론 외부 API

---

## 3. 프로바이더 설정 (application.properties)

```properties
app.llm.default-routing-mode=COST_FIRST
app.llm.circuit-breaker-minutes=2
app.llm.progressive-threshold=0.6

# ── [LOCAL] 범용 로컬 LLM ──────────────────────────────────────────
# type=LIGHT_BOTH → LIGHT_TEXT + VISION 태스크 처리
# 없으면 COST_FIRST 시 NORMAL부터 시작
app.llm.providers[0].name=local
app.llm.providers[0].base-url=http://localhost:1234/v1
app.llm.providers[0].api-key=lm-studio
app.llm.providers[0].model=gemma-4-27b-it
app.llm.providers[0].type=LIGHT_BOTH
app.llm.providers[0].role=LOCAL
app.llm.providers[0].priority=0

# ── [LOCAL] Vision 전용 로컬 모델 (선택) ──────────────────────────
# type=VISION → VISION task에서 LIGHT_BOTH보다 우선 선택됨
# 등록 시: LLaVA, Qwen2-VL 등 Vision 특화 모델 권장
# app.llm.providers[5].name=local-vision
# app.llm.providers[5].base-url=http://localhost:1235/v1
# app.llm.providers[5].api-key=lm-studio
# app.llm.providers[5].model=llava-1.6-34b
# app.llm.providers[5].type=VISION
# app.llm.providers[5].role=LOCAL
# app.llm.providers[5].priority=0

# ── [NORMAL] Gemini Flash ─────────────────────────────────────────
# GEMINI_API_KEY 미설정 시 시작 시 warn 로그 후 자동 비활성화
app.llm.providers[1].name=gemini-flash
app.llm.providers[1].base-url=${GEMINI_BASE_URL:https://generativelanguage.googleapis.com/v1beta/openai/}
app.llm.providers[1].api-key=${GEMINI_API_KEY:}
app.llm.providers[1].model=gemini-2.5-flash
app.llm.providers[1].type=BOTH
app.llm.providers[1].role=NORMAL
app.llm.providers[1].priority=1

# ── [NORMAL] OpenAI Mini (fallback) ──────────────────────────────
# OPENAI_API_KEY 미설정 시 시작 시 warn 로그 후 자동 비활성화
app.llm.providers[2].name=openai-mini
app.llm.providers[2].base-url=${OPENAI_BASE_URL:https://api.openai.com}
app.llm.providers[2].api-key=${OPENAI_API_KEY:}
app.llm.providers[2].model=gpt-4o-mini
app.llm.providers[2].type=BOTH
app.llm.providers[2].role=NORMAL
app.llm.providers[2].priority=2

# ── [PREMIUM] Gemini Pro ──────────────────────────────────────────
# GEMINI_API_KEY 미설정 시 시작 시 warn 로그 후 자동 비활성화
app.llm.providers[3].name=gemini-pro
app.llm.providers[3].base-url=${GEMINI_BASE_URL:https://generativelanguage.googleapis.com/v1beta/openai/}
app.llm.providers[3].api-key=${GEMINI_API_KEY:}
app.llm.providers[3].model=gemini-2.5-pro
app.llm.providers[3].type=BOTH
app.llm.providers[3].role=PREMIUM
app.llm.providers[3].priority=3

# ── [PREMIUM] OpenAI GPT-4o (fallback) ───────────────────────────
# OPENAI_API_KEY 미설정 시 시작 시 warn 로그 후 자동 비활성화
app.llm.providers[4].name=openai
app.llm.providers[4].base-url=${OPENAI_BASE_URL:https://api.openai.com}
app.llm.providers[4].api-key=${OPENAI_API_KEY:}
app.llm.providers[4].model=gpt-4o
app.llm.providers[4].type=BOTH
app.llm.providers[4].role=PREMIUM
app.llm.providers[4].priority=4

# ── 병렬 인덱싱 제어 ──────────────────────────────────────────────
app.indexing.max-concurrent-files=${INDEXING_MAX_FILES:3}
app.indexing.max-concurrent-llm-calls=${INDEXING_MAX_LLM:4}
```

---

## 4. 라우팅 시나리오

| 상황 | COST_FIRST | QUALITY_FIRST | PROGRESSIVE | DUAL | LOCAL_ONLY |
|------|-----------|--------------|------------|------|-----------|
| 로컬 LLM 정상 | local→normal→premium | premium→normal→local | local/normal 먼저 | **local∥normal** | **local 단독** |
| 로컬 LLM 없음 | normal→premium | premium→normal | normal 먼저 | **exhausted** | **exhausted** |
| gemini-flash 429 | openai-mini→premium | premium→openai-mini | openai-mini 먼저 | local∥openai-mini | local 단독 |
| 모든 NORMAL 차단 | PREMIUM fallback | PREMIUM 정상 | PREMIUM 재실행 | local∥premium | local 단독 |
| 전체 소진 | `LlmProviderExhaustedException` | 동일 | 동일 | 동일 | 동일 |

**PROGRESSIVE 흐름**:
1. COST_FIRST로 Answer 실행
2. `qualityScore` < `progressiveThreshold`(기본 0.6) AND 재검색 소진 시
3. QUALITY_FIRST로 Answer 재실행 (동일 검색 결과 재사용)
4. 응답 메타에 `🔝 고추론 재분석 → {providerName}` 배지 표시

> 현재 `qualityScore`는 sufficient=true→1.0, false→0.0 이진값. 추후 스칼라 점수로 확장 가능.

**DUAL 전제 조건**: LOCAL 등록 필수. 미등록 시 즉시 exhausted.  
CLASSIFIER·RETRIEVAL은 COST_FIRST(공유), ANSWER만 LOCAL∥외부 병렬 실행.

---

## 5. Circuit Breaker

- HTTP 429/402: `Retry-After` 헤더 파싱 → 해당 시간 차단. 헤더 없으면 `circuit-breaker-minutes` 적용.
- 기타 예외: 30초 차단 후 다음 프로바이더 시도.
- 차단 만료는 다음 라우팅 시 자동 해제.
- `/llm-usage` 페이지에서 차단 상태 + 남은 시간 카운트다운 확인 가능 (30초마다 자동 갱신).

---

## 6. 사용량 추적 (SQLite — memory.db 공유)

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

## 7. 제약 및 주의사항

- **프로바이더 자동 비활성화**: `api-key`가 비어있으면 (`${GEMINI_API_KEY:}` 등 빈 기본값) 시작 시 warn 로그 출력 후 해당 프로바이더를 제외. 키 미설정만으로 providers 블록을 남겨둔 채 비활성화 가능
- **DUAL 활성 조건**: LOCAL 미등록 → UI에서 드롭다운 `disabled` + "로컬 LLM이 필요합니다" 툴팁
- **LOCAL_ONLY**: LOCAL 미연결·차단 시 외부 API fallback 없이 즉시 exhausted — UI에서 오류 안내 필요
- **같은 Gemini API 키 공유**: Flash(NORMAL)와 Pro(PREMIUM) 429가 동시 발생 가능 → OpenAI를 PREMIUM fallback으로 유지 권장
- **classifyOnly() 토큰 미누적**: `AgentService`가 선행 분류 시 `AgentState` 토큰 집계에서 1회 누락 (허용된 MVP 트레이드오프)
- **tried 집합 순환 방지**: `executeWithTracking()` 내 tried 집합이 모든 프로바이더를 포함하면 exhausted — 최대 재귀 = 프로바이더 수
- **Vision 라우팅**: `type=VISION` 모델 미등록 시 `LIGHT_BOTH` → `BOTH` 순으로 fallback. Vision 문서 많으면 `local-vision` 등록 권장
