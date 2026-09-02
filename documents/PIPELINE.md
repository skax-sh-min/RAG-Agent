# Query & Indexing Pipeline — 질의·인덱싱 처리 흐름

RAG Agent의 두 가지 핵심 흐름(질의응답, 문서 임포트)을 코드 레벨에서 기술.

---

## 목차

1. [질의응답 흐름](#1-질의응답-흐름)
2. [AgentGraph 노드](#2-agentgraph-노드)
3. [모드별 동작](#3-모드별-동작)
4. [LLM 호출 요약](#4-llm-호출-요약)
5. [재시도와 검증](#5-재시도와-검증)
6. [문서 임포트 흐름](#6-문서-임포트-흐름)
7. [관련 문서](#7-관련-문서)

---

## 1. 질의응답 흐름

```
HTTP 요청
  └─ ChatController → AgentService.chat()
        │
        ├─ [병렬] 대화 히스토리 로드
        │         질문 유형 분류 (LLM ①)
        │
        └─ AgentGraph.run()
              │
              ├─ meta 질문 ──→ DIRECT_ANSWER → FINALIZE
              │
                └─ 일반 질문 ──→ RETRIEVAL → ANSWER ─────→ FINALIZE
                                    ↑      │
                                    │      └─ (responseMode != S) → CRITIC → FINALIZE
                                    │                                 │
                                    └─────────────────────────────────┘
                                  부족/미근거 시 재시도 (최대 N회, S는 ANSWER 기준만)
```

`AgentState`(불변 레코드)를 각 노드가 받아 새 인스턴스를 반환하며 상태를 전파.

### 1.1 중복 질문 재사용 경로 (UI 추천 선택)

`/api/v1/questions/reuse`는 AgentGraph를 타지 않는 단축 경로입니다.

```
질문 추천 클릭
  └─ ChatController.reuseQuestionAnswer()
       ├─ turn 접근 권한 확인(scope=shared|me)
       ├─ 반환 직전 출처 청크 해시 재검증
       │    ├─ 통과: 기존 answer 재사용 저장(provider=db-reuse)
       │    └─ 실패: fallback=true + 사유 반환
       └─ (클라이언트) fallback=true면 동일 질문으로 일반 질의 전송
```

검증 실패는 CRITIC 단계 전용이 아니라, 일반 질의 전체(검색→답변→평가→재시도)로 전환하는 신호입니다.

---

## 2. AgentGraph 노드

| 노드 | 역할 | LLM 호출 |
|------|------|---------|
| **CLASSIFIER** | 질문 유형 판별 (concept / usage / error / version / meta) | ① — AgentService에서 선실행하므로 그래프 내에서는 스킵 |
| **DIRECT_ANSWER** | meta 질문 직접 응답 (벡터 검색 없음) | ② |
| **RETRIEVAL** | 쿼리 확장(조건부 — 15자 미만 질의는 생략, `app.search-multiquery-min-length`) → 확장 LLM 호출과 원본 질의 벡터 검색을 가상 스레드로 병렬 실행(§10.8.1, 원본 검색 지연이 확장 대기 뒤에 숨음) → 배치 임베딩(쿼리 임베딩 캐시 히트 시 스킵) → 벡터 스토어 배치 쿼리(chroma 단일 호출, 결과에 쓰지 않는 임베딩 필드는 요청 자체를 생략 §10.9.1 / sqlite-vec 쿼리별) + 큐레이션 Q&A 축 병렬 조회(§10.10, 예약 version `"curated"`, `search.curated-qa-enabled`로 게이팅) → 가중 RRF 병합(벡터축 그룹 정규화 + 키워드축 가중치 + 큐레이션축 가중치) → 선택적 LLM 리랭킹(opt-in). 재검색 시 후보 풀 ×1.5씩 **및 최종 컷 +1**(컨텍스트 여유가 있을 때만) 에스컬레이션 + **근거 미사용 하위 청크 교체**(§5) | ③ 쿼리 확장(조건부), [리랭킹 활성 시 1콜] |
| **ANSWER** | 문서 기반 답변 생성 + **충분도·근거 통합 평가**(1콜, §5.1) | ④ 답변, ⑤ 평가 |
| **CRITIC** | ⑤가 계산해 둔 `grounded` 플래그를 읽어 재시도 여부만 결정 | **없음** (별도 LLM 왕복 없음) |
| **FINALIZE** | 대화 히스토리 저장 (SQLite) | 없음 |

### 노드 전환 규칙

| 조건 | 전환 |
|------|------|
| questionType == "meta" | CLASSIFIER → DIRECT_ANSWER |
| 그 외 | CLASSIFIER → RETRIEVAL |
| ANSWER sufficient=false, retryCount < max | ANSWER → RETRIEVAL |
| ANSWER responseMode == S | ANSWER → FINALIZE (CRITIC 스킵) |
| ANSWER responseMode != S | ANSWER → CRITIC |
| CRITIC grounded=false, retryCount < max | CRITIC → **ANSWER** (재검색 없이 답변만 재생성) |
| 그 외 | CRITIC → FINALIZE |

---

## 3. 모드별 동작

### COST_FIRST (기본)
```
분류 → 검색 → 답변 → 충분도 검사
              └─ 부족 시 재검색 (retryCount < max)
         → CRITIC → 종료
```

> 예외: `responseMode=S`인 turn은 ANSWER 뒤 CRITIC을 건너뛰고 바로 FINALIZE로 종료한다.

### PROGRESSIVE
```
COST_FIRST와 동일하되,
재시도 소진 후에도 답변 불충분 → PREMIUM 모델로 자동 업그레이드 후 재답변
```

### meta 질문
```
분류에서 "meta" 감지 → 검색 없이 직접 답변 → 종료
```

> 라우팅 모드 상세: [LLM_ROUTING.md](LLM_ROUTING.md)

### 3.1 응답 모드 (S / N, + 서버 전용 C) — 라우팅 모드와 직교하는 축

사용자가 메시지마다 고르는 **답변의 성격**이다(입력창의 S/N 토글, 기본 N). 라우팅 모드(COST_FIRST/PROGRESSIVE)가 "어느 프로바이더로 보낼까"라면, 응답 모드는 "어떤 답변을 쓸까"다.

| 모드 | 성격 | 시스템 프롬프트 | 분량 지침 | 검증 | 큐레이션 |
|---|---|---|---|---|---|
| **S** 요약 | 요약 한 섹션 | `prompt.answer.system.s` | **1,000자 이내** + 4~7줄 | 생략(eval + CRITIC 둘 다) | **제외** |
| **N** 표준 | 문서 충실, 5섹션 | `prompt.answer.system.n` | 숫자 없음 — "구체적이고 자세하게" | `prompt.answer.eval` | 승격 |
| **C** 응용 | 문서를 **재료로** 생성, 4섹션 | `prompt.answer.system.c` | 숫자 없음 — "구체적이고 자세하게" | `prompt.answer.eval.creative` | **제외** |

Direct(검색 없음) 경로도 같은 규칙으로 `prompt.direct.system.{s,n}`을 고른다. meta(인사/잡담)만 모드와 무관하게 `prompt.direct.meta.system`을 쓴다. **C는 Direct 프롬프트가 없다** — 검색 결과가 이 모드의 전제라 RAG 없이 부를 수 있는 값이 아니다.

> **C는 Direct와 배타다.** 검색을 건너뛰는 Direct 에서는 문서를 재료로 삼는 C 가 성립하지 않으므로, 채팅 화면이 C 버튼을 비활성화하고(선택 중이었다면 저장된 선택까지 N 으로 되돌린다) **서버도 독립적으로 같은 규칙을 건다** — `ChatRequest`(REST)와 `ChatForm.responseModeOrDefault()`(HTMX 폼 + SSE 가 공유하는 값 객체). 구 L 모드는 클라이언트 비활성화만 있고 서버 가드가 없어 손으로 만든 요청이 그대로 통과했다.

> **C는 운영자가 통째로 닫을 수 있는 유일한 모드다** — `app.llm.creative-mode-enabled`(기본 ON, `/settings`에서 핫 수정). 문서 밖의 내용을 쓰는 유일한 모드라 제공 여부 자체가 운영 정책이기 때문이다. 위의 Direct 배타가 **요청 하나**를 보고 판정하는 것과 달리 이쪽은 **배포 설정**을 봐야 하므로 레코드가 스스로 답할 수 없다 — 판정은 `ResponseMode.operatorToggleable()`(끌 수 있는 모드인가)과 `SettingsService.creativeModeEnabled()`(지금 꺼져 있는가)로 나뉘고, 둘을 합치는 곳은 `SettingsService.effectiveResponseMode()` **하나**다. 모든 채팅 진입점이 그것을 지난다: `ChatController.normalizeResponseMode()`(HTMX·SSE 공유)와 `withAvailableResponseMode()`(REST). 강등을 **진입점에서** 하는 이유는 Direct 가드와 같다 — 그래프 안쪽에서만 바꾸면 저장된 `response_mode`는 C인데 실제로는 N으로 답한 턴이 남는다. 꺼져도 이미 C로 답한 과거 턴의 표기·배지는 그대로다.

**모드마다 시스템 프롬프트를 통째로 바꾼다.** 공용 프롬프트 하나를 두고 사용자 메시지에 "위 형식은 쓰지 마세요" 같은 부정 지시를 얹어 뒤집는 방식은 S에서 실패했다 — 시스템 프롬프트가 나열한 5섹션 헤더 목록이 그 한 줄보다 강하게 작용해 모델이 전부 생성했고, 서버가 사후 절단하면서 화면과 저장본이 갈라졌다. 그래서 **S 프롬프트는 5섹션 헤더 이름을 언급조차 하지 않는다**(금지하려고 나열하는 것만으로도 약한 로컬 모델은 그 목록을 따라간다).

**분량 지침이 S에만 숫자인 이유**: 짧은 출력에 건 상한은 모델이 스스로 멈추는 지점보다 *앞*에 있어 구속력이 있지만, 긴 출력에 건 목표는 그 *뒤*에 있어 아무 일도 하지 않는다(구 M "약 5,000자"에 실제 3,047자, 구 L "약 10,000자"에 3,187자 — 이 관측이 L을 제거한 근거다). 그래서 N은 숫자 대신 무엇을 더 쓸지를 지시한다(배경·이유·전제·예외).

#### 프롬프트 상한과 토큰 예산은 다른 것이다

`ResponseMode.maxTokens()`가 내는 값은 **폭주를 막는 안전판**이지 목표 분량이 아니다. 실제 분량은 위 프롬프트 지침이 정한다.

```
예산 = min( max-tokens, max(max-tokens × 비율, 최소 보장) )      // S 0.15/2,000   N·C 0.70/5,000
```

- **블로킹 호출에만 걸린다**(REST `/api/v1/chat`, JS 미사용 폴백). 채팅 화면의 스트리밍 답변에는 토큰 상한이 없다(§4.1).
- **예산은 프롬프트 상한보다 넉넉해야 한다.** S는 프롬프트가 1,000자인데 예산이 2,000~2,400이다 — 지시를 잘 따른 답변이 블로킹 경로에서 문장 중간에 잘리지 않게 하려는 의도된 여유다. **S의 프롬프트 상한을 올린다면 `minChars`도 함께 올려야 한다**(한글 1토큰≈1글자라 2,000자 지시 + 2,000 예산은 여유가 0이다).
- `/settings`의 "응답 예산" 행이 모드별 실효값과 **어느 항이 이겼는지**를 함께 보여준다(`2,000 (최소 보장)` / `11,200 (상한의 70%)` / `3,000 (설정 상한)`). 전환점이 모드마다 다르기 때문이다 — S는 `max-tokens` 13,334, N·C는 7,143.

#### S 모드에서 좋아요는 아무 일도 하지 않는다

S 답변은 전체가 `## 요약` 한 섹션이라, 큐레이션 임베딩 입력에서 구조 섹션을 걷어내면(`CuratedTextUtils.stripStructuralSections` — 요약·참고 제거) **본문이 통째로 사라져** 질문만 담긴 벡터가 된다. N 답변에서는 `## 상세 설명`이 남지만 S에는 남을 것이 없다. 애초에 축약된 답변이라 공유 지식으로 승격할 대상도 아니다.

그래서 `ResponseMode.S.allowsCuration() = false`이고 `CuratedQaService.onLike()`가 즉시 반환한다 — `curated_qa` 행조차 만들지 않는다. LIKE 피드백의 유일한 소비자가 큐레이션이므로 결과적으로 **S 턴의 좋아요는 무동작**이다. **싫어요는 모드와 무관하게 그대로 동작한다**(다음 대화 컨텍스트에서 제외).

#### C는 검증을 끄지 않고 바꿔 낀다

C는 문서 밖 내용을 만들어내는 것이 목적이라, 기존 `grounded`("답변의 핵심 주장이 발췌에 근거하는가")가 **정의상 항상 false**다. 그대로 두면 CRITIC이 재시도를 걸어 ANSWER·EVAL·RETRIEVAL을 각각 3회(기본 `max-retry-count`=2) 태우고 끝에 미검증 경고까지 붙인다 — 표준 턴 164초 기준 8분짜리 턴이다. 그렇다고 S처럼 통째로 끄면 C 고유의 위험(문서에 없는 API 발명)이 무방비가 된다.

그래서 **같은 자리에서 판정 기준만 갈아 끼운다**(추가 왕복 0회 — 그 호출은 이미 답변과 전 발췌를 나란히 들고 있다):

| 필드 | 판정 기준 | 실패 시 |
|---|---|---|
| `sufficient` | 요청한 산출물을 실제로 만들었는가 | 재시도 |
| `apiGrounded` | 답변이 **문서 유래라고 제시한** 심볼이 발췌에 실재하는가 | `grounded` 필드에 실려 CRITIC 재시도 (`CriticService` 코드 변경 0) |
| `inventedSymbols` | 발췌에 없는데 있는 것처럼 쓰인 이름 목록 | **재시도 아님** — 이름을 지어내는 것 자체는 실패가 아니고, 그걸 문서 근거인 양 제시하는 것이 문제라 경고로 보여준다 |
| `envNote` | 기존과 동일 | — |

"문서를 조합해 새로 만들었다"는 통과, "문서에 없는 함수를 발명했다"는 실패. 응답 형식이 다르므로 파서도 둘이다(`EvalOutput` / `CreativeEvalOutput`) — 한 레코드에 `inventedSymbols`를 얹으면 N의 응답 스키마에도 그 필드가 실려 표준 검증이 흔들린다.

**온도도 다른 것을 쓴다.** 일반/RAG 온도(`app.llm.temperature`)는 clamp 상한이 **0.3**이라 창의 생성이 원천 봉쇄돼 있어, C만 `app.llm.creative-temperature`(기본 0.7, clamp [0,1.0], `/settings`에서 핫 수정)를 쓴다. 이 분기는 **블로킹과 스트리밍 양쪽**에 걸려야 한다 — 채팅 화면의 유일한 전송 경로가 스트리밍이라, `streamDirect()`를 빠뜨리면 화면에서만 온도가 안 오르고 그 사실이 아무 로그에도 남지 않는다.

#### 검증 배지는 무엇을 검증했는지를 말한다

같은 "통과"라도 통과한 질문이 다르다 — 표준 모드의 `grounded` 는 "답변이 문서에 근거하는가", 창의 모드의 `apiGrounded` 는 "문서 유래라고 제시한 이름이 실재하는가"를 물었다. 그래서 C 의 통과 배지는 초록 `검증됨` 이 아니라 **파랑 `생성`** 이다. 같은 초록을 붙이면 사용자는 뒤엣것을 앞엣것으로 읽고, 그것이 이 모드에서 가장 비싼 오해다. `inventedSymbols` 가 비어 있지 않으면 노랑 경고 배지와 펼친 목록이 **통과한 답변에도** 함께 붙는다(재시도를 걸지 않는 값이므로).

| 검증 상태 | 배지 | 색 |
|---|---|---|
| 미실행 (`grounded=null` — S, meta/Direct, 검색 결과 없음) | 없음 | — |
| 통과 · 표준 | `검증됨` | 초록 |
| 통과 · 생성(C) | `생성` | 파랑 |
| 미통과 | `미검증` (+ 사유) | 노랑 |
| 발명된 이름 있음 | `문서 밖 이름 N` (+ 목록) | 노랑, 위와 별개로 추가 |

**규칙은 `VerificationSnapshot` 한 곳에 있다.** 렌더러가 셋이기 때문이다 — 방금 보낸 메시지를 그리는 no-JS HTMX 폴백 프래그먼트, 새로고침 후의 대화 기록(`chat.html` 의 자체 루프), 스트리밍(`chat-stream.js`). 서버 렌더러 둘은 그 레코드의 메서드를 읽고, JS 는 SSE 이벤트가 템플릿을 거치지 않아 한 번 더 구현한다 — **바꾸면 양쪽을 함께 고쳐야 한다**(`SourceRef.staleBadge()` 와 같은 구조).

같은 레코드가 `conversation_turns.verification` 에 JSON 으로 저장되어 **새로고침 후에도 배지가 남는다**. 예전에는 기록 루프가 검증 배지를 아예 그리지 않아 새로고침 한 번으로 배지가 사라졌는데, C 의 "문서 밖 이름" 은 안전 신호라 그렇게 두면 안 된다(§3.1 의 "서버가 답변을 바꿨을 때" 와 같은 종류의 화면/DB 불일치). 컬럼이 `NULL` 이면 검증 기록이 없는 턴이고, 그건 이 컬럼 이전의 모든 턴과 meta/Direct·S 턴이다 — 그 경우 배지를 띄우지 않는 예전 동작 그대로다.

#### 대화 히스토리 예산과의 상호작용

`ConversationSummarizerService`는 답변의 `## 요약` 섹션을 재요약 없이 그대로 재사용하는데, **S 답변은 전체가 요약**이라 압축 없이 통째로 실린다. 히스토리 예산은 `max(1000, max-tokens/2)`이므로 `max-tokens=12,000`이면 6,000자 — S 답변 1,000자 기준 약 6턴 분량이다. S의 분량을 늘리면 이 예산을 그만큼 빨리 소모해 오래된 턴이 먼저 밀려난다.

> 응답 모드 재설계의 배경·결정 이력: [PLAN.md §6.24](PLAN.md)

---

## 4. LLM 호출 요약

| # | 위치 | 목적 | 토큰 누적 |
|---|------|------|---------|
| ① | AgentService (사전 병렬) | 질문 유형 분류 | 없음 |
| ② | DIRECT_ANSWER | meta 직접 응답 | ✓ |
| ③ | RETRIEVAL | 쿼리 다양화 | 없음 |
| ④ | ANSWER | 답변 생성 | ✓ |
| ⑤ | ANSWER | 충분도(`sufficient`) + 근거(`grounded`) + 사유(`reason`) + 환경 의존 값 안내(`envNote`) **동시 평가** | ✓ |
| ⑥ | CRITIC | — **LLM 호출 없음**. ⑤가 낸 `grounded`를 소비할 뿐 | — |
| ⑦ | ANSWER (PROGRESSIVE) | PREMIUM 재답변 | ✓ |

> ①은 `AgentState`에 누적되지 않아 `llmCallCount`가 실제보다 1 낮게 표시됨 — 허용된 tradeoff.  
> ③ (MultiQueryExpander)도 토큰 미누적. 15자 미만 질의는 생략되며(기본값), 실행될 때도 원본 질의 검색과 병렬로 진행되어(§10.8.1) 검색 전체 지연에 그대로 더해지지 않는다.  
> ③의 프롬프트는 Spring AI 기본값(영어, 관점 다양화만 요청)이 아니라 `RetrievalService` 생성자가 `messages(_ko).properties`의 `prompt.retrieval.expansion`으로 교체한 커스텀 한국어 프롬프트 — 관점만 다양화하는 게 아니라 인사말·존댓말 어미·군더더기 제거, 지시어/대명사를 구체적 용어로 풀어쓰기 등 **임베딩 벡터 검색에 더 적합한 형태로 정규화**하도록 함께 요청한다. 여기에 **표기 변형**도 포함된다 — 문서가 한글로 쓰였는지 영문으로 쓰였는지 알 수 없으므로 변형 중 최소 1개는 핵심 용어를 반대 언어 표기로 바꾸게 하고("디비 접속 설정" → "database connection configuration"), 약어·구어체 음차(디비)는 정식 표기(데이터베이스/DB)로도 풀어 쓰게 한다. **이 변형은 벡터 축에만 투입된다** — BM25 축(`keywordF`)은 여전히 원본 질문만 검색하므로, 한/영 표기 차이의 나머지 절반은 키워드 축 가중치(`SEARCH_RRF_KEYWORD_WEIGHT`, 기본 0.5)로 다룬다(OPERATOR_MANUAL §7.8). LLM에 넘기는 질문 텍스트는 다른 프롬프트 구성 지점과 동일하게 `PromptInjectionGuard.wrap()`으로 감싸며, 벡터 검색 자체(원본 질의 축)는 감싸지 않은 원문을 그대로 임베딩한다.
>
> `responseMode=S`에서는 CRITIC 노드가 실행되지 않는다. 따라서 해당 turn의 재시도는 ANSWER의 `sufficient=false` 조건으로만 결정된다.

> **동시성 게이트**: ①~⑦ 모두 프로바이더별 동시성 게이트(`LlmRouter.executeGated()`, 서버의 실제 `--parallel` 값에 맞춘 `Semaphore`)를 거친다 — 여러 사용자의 질문이 겹쳐도 앱이 한 프로바이더에 동시 전송하는 요청 수는 이 한도를 넘지 않는다. 대기가 상한(`app.llm.permit-wait-timeout-seconds`, 기본 60초)을 넘으면 즉시 HTTP 429로 응답하고 재검색/재시도로 넘어가지 않는다. 문서 인덱싱의 LLM 호출(키워드 추출, MD 포맷 교정 등)은 이 게이트 대상이 아니며 기존 `INDEXING_MAX_LLM` 세마포어만 적용된다 — 상세는 [LLM_ROUTING.md §6](LLM_ROUTING.md#6-동시성-게이트--백프레셔) 참고.

> **태스크별 모델 분리(§6.21)**: ③ 쿼리 다양화와 인덱싱 잡무(키워드+맥락 추출·대화 요약·제목 생성)는 `TaskType.MICRO_TEXT`로 라우팅된다 — `type=MICRO_TEXT` 소형 프로바이더를 등록하면 이 추론 불필요 잡무만 500MB급 소형 모델로 오프로딩되고, 분류(①)·직답(②)·답변(④)·통합 평가(⑤) 등 품질 민감·고추론 호출은 전부 `TaskType.TEXT`로 묶여 큰 모델(`type=TEXT`/`BOTH`)이 전담한다. 소형 미등록 시 큰 모델이 흡수(회귀 0). 상세는 [LLM_ROUTING.md §9](LLM_ROUTING.md).

### 4.0 입력 예산 — 호출 전에 프롬프트를 창에 맞춘다 (§6.26)

④(답변)·⑤(검증)와 **인덱싱의 재작성 호출**(MD 교정·txt→md 구조화)은 프롬프트를 조립하기 **전에** 프로바이더의 컨텍스트 창에 맞춰 줄인다. `max_tokens` 가 상한이 아니라 **예약**이기 때문이다 — 서버가 `프롬프트 + max_tokens ≤ n_ctx` 를 검사하므로 쓸 수 있는 입력은 답변 자리를 먼저 잡아둔 나머지다.

```
입력 예산 = 컨텍스트 창 − 출력 예약 − 여유(창의 10%, 최소 256)
```

- **창을 모르면 아무것도 하지 않는다.** 추측한 숫자로 근거를 버리는 것이 초과보다 나쁘다. 창은 `app.llm.providers[N].context-size` 선언 또는 기동 시 탐지(`ContextWindowProbe`)로 얻고, 둘 다 없으면 "모름"이다.
- **버리는 순서는 문서 → 이력.** 검색 결과는 RRF 내림차순이라 뒤가 최저 관련도이고, 응답 참여도 측정이 검색 상위조차 답변에 기여하지 않는 경우가 흔하다고 말한다. 반면 이력이 사라지면 사용자가 즉시 체감한다. **최상위 문서는 예산을 넘어도 남긴다** — 다 버리면 프롬프트가 "문서를 찾을 수 없습니다"가 되어 검색이 성공했는데도 모른다고 답한다.
- **이력은 턴 경계에서만 자른다**(빈 줄 + `Q: `). 문자 인덱스로 자르면 반쪽 턴이 남아 모델을 더 헷갈리게 한다. 경계를 못 찾으면(§6.10 요약 경로) 줄 단위로 앞부터 덜어내되 **통째로 버리지는 않는다**.
- **④와 ⑤는 예산을 따로 잡는다.** ⑤는 같은 문서에 답변 전문과 응답 스키마가 얹히고 이력은 빠져 모양이 다르고, 출력 예약도 자체 상한 2,048 을 쓴다. ④가 들어갔다는 사실이 ⑤도 들어간다는 보장이 되지 못한다.
- **⑦(PROGRESSIVE 재답변)은 PREMIUM 의 창으로 다시 계산한다.** 창이 더 큰 프로바이더로 가는데 원래(로컬) 예산을 재사용하면, 불충분해서 다시 만드는 답변이 오히려 문서를 덜 받는다.
- **축소는 사용자에게 보인다** — 턴 단위 안내(`budgetNote`)가 개수를, 출처별 `미사용` 배지가 어느 것인지를 말한다. `answerShare=0`("읽고도 안 썼다")과 다른 사실이다.
- **그래도 넘치면 절반씩 줄여 다시 시도한다**(§6.26-9). 위 예산은 전부 `TokenEstimator` 의 **추정** 위에 서 있고, 창을 모르는 배포에서는 아예 돌지 않는다 — 그 두 경우에 초과가 나면 예전에는 사용자가 답변 대신 `RAG-LLM-003` 을 받았다. 이제 문서를 `app.llm.shrink-step` 개(기본 **1**)씩 덜어내며 최대 5회까지 다시 시도한다(기본 조합에서 topK=10 → 10→9→8→7→6→5). **반씩이 아니라 한두 개씩인 이유**: 사전 예산이 이미 창에 맞춰 놓은 뒤라 여기까지 오는 초과는 대개 아슬아슬해서, 반을 자르면 한 개면 됐을 자리에서 근거의 절반이 사라진다. 실패한 시도는 생성 전에 거절되므로 왕복이 싸다. **줄인 사실은 안내 문구에 반영되고**, 검증이 그렇게 줄어든 발췌로 낸 `grounded=false` 는 판정으로 삼지 않는다(축소 판정 가드).
- **인덱싱의 재작성 호출은 셈이 다르다.** 출력이 입력에 비례하므로(넣은 만큼 나온다) 예약을 먼저 빼면 순환이 된다 — `지시 프롬프트 + S + 1.5S + 여유 ≤ 창` 을 풀어 `S ≤ (창 − 지시 − 여유) / 2.5` 로 조각 크기를 정한다(`PromptBudget.rewriteInputChars()`). 답변 경로와 달리 **자르는 것이 아니라 조각을 더 잘게 나누는 것**이라 버려지는 내용이 없고, `max-tokens` 파생 상한보다 **작을 때만** 채택한다 — 창이 넉넉하다고 조각을 키우면 경계가 이동해 교정 결과 자체가 달라진다.

> **⑤의 발췌를 "답변에 실제로 쓰인 문서"로 좁히지 않는다.** 어느 문서가 쓰였는지는 그 호출이 끝나야 알 수 있고(`usedDocs` 는 출력, `AnswerAttribution` 은 FINALIZE), 설령 미리 안다 해도 답변을 닮은 문서만 골라 채점하게 된다. 근거는 답변이 인용한 문서에만 있지 않다 — 예전 `.limit(5)` 가 정확히 그 사고였다(#6~8 문서에만 있는 포트·경로를 정확히 인용한 답변이 근거 없음 판정).

### 4.1 `app.llm.max-tokens`(`LLM_MAX_TOKENS`) 크기 산정 — 로컬 LLM 컨텍스트 윈도우와의 관계

**`max_tokens`(completion 상한) ≠ 컨텍스트 윈도우(n_ctx, 입력+출력 합계).** `LLM_MAX_TOKENS`가 `OpenAiChatOptions.maxTokens()`로 들어가는 값은 LLM이 한 번에 생성할 수 있는 **출력** 토큰 상한일 뿐, 로컬 LLM 서버(예: llama-server)의 컨텍스트 크기(`--ctx-size`, 흔히 기본 8192)와는 별개다. 입력(system prompt + RAG 검색 결과 + 대화 히스토리 + 질문)이 이미 컨텍스트의 상당 부분을 차지하므로, `max_tokens`를 크게 잡아도 실제로 생성 가능한 토큰 수는 `n_ctx - 입력토큰수`로 물리적으로 제한된다 — 서버 구현에 따라 조용히 잘리거나, 입력이 이미 크면 "context length exceeded" 류의 에러가 난다. **컨텍스트 윈도우 자체는 로컬 서버 설정(`--ctx-size`)으로 조절 가능**하므로, 완성 상한을 늘리고 싶다면 `LLM_MAX_TOKENS`만 올리기보다 로컬 서버의 컨텍스트 크기를 함께(또는 우선) 늘리는 것이 근본적인 해법이다.

**스트리밍 답변 경로는 이 값 자체를 전송하지 않는다.** ④(ANSWER 답변 생성)와 ②(DIRECT_ANSWER)의 실제 사용자 대면 스트리밍 경로(`AnswerService`/`DirectAnswerService`가 `OpenAiApi.chatCompletionStream()`을 직접 호출하는 4-arg `ChatCompletionRequest(messages, model, temperature, stream)`, 또는 `ChatClient` 스트리밍 폴백)는 `maxTokens` 필드 자체가 없는 오버로드를 쓴다 — 즉 **사용자가 실제로 보는 채팅 답변 길이는 `LLM_MAX_TOKENS`와 무관**하며, 대신 SSE 타임아웃(`app.sse-idle-timeout-seconds`)이 폭주를 막는다. `LLM_MAX_TOKENS`가 실제로 completion 상한을 거는 곳은 **블로킹** LLM 호출뿐이다 — ①③⑤⑦ 및 인덱싱 계열(분류·쿼리확장·충분도/근거 통합평가·PROGRESSIVE 재답변·키워드추출·TXT구조화), Direct의 블로킹(비스트리밍) 모드.

**§6.18 이후, 이 값 하나가 서로 다른 3곳에 결합돼 있다**(그 위에 §6.26 이후로는 검증·인덱싱 호출의 출력 예약까지 여기서 파생된다 — 아래 예외 참고) — `AppProperties.llmSafe().maxTokens()`를 공유하므로 하나를 올리면 셋이 함께 커진다:

| 소비처 | 공식 | 2000 | 6000 | 10000(기본) |
|---|---|---|---|---|
| 블로킹 LLM completion 상한 | `LLM_MAX_TOKENS` 그대로 | 2000 | 6000 | 10000 |
| 대화 히스토리 문자 예산(`MemoryService`) | `LLM_MAX_TOKENS × 0.5` | 1000자 | 3000자 | **5000자** |
| MD 교정 섹션 크기(`MarkdownCorrectionService`, §6.3 6번) | `(LLM_MAX_TOKENS-500)/2` — **창을 모를 때** | 750자 | 2750자 | **4750자** |

> 마지막 행은 **상한**이다. 프로바이더 창을 알면(§4.0) 거기서 나온 값과 비교해 **작은 쪽**을 쓴다 — 예컨대 창 20,480 에서는 4,750자가 아니라 6,852자 계산값과 비교해 4,750자가 그대로 이기지만, 창 8,192 라면 2,429자로 내려간다. txt→md 구조화(`TextToMarkdownService`, 상수 6,000자)도 같은 규칙을 따른다.

> **예외 — 전체를 예약하지 않는 호출들**. `max_tokens` 는 상한이 아니라 **예약**이라(서버가 `프롬프트 + max_tokens ≤ n_ctx` 를 검사한다) 쓰지도 않을 큰 값을 실어 보내면 그만큼 입력 자리가 사라진다. 그래서 두 갈래가 이 값에서 **더 작은 값을 파생**시킨다:
> - **검증 호출**: `AnswerService.evalOptions()` 가 자체 상한 **2,048토큰**(`min(설정값, 2048)`). 반환값이 JSON 몇 필드뿐인데 전체가 예약되면, 좁은 컨텍스트에서 `n_ctx` 를 넘기는 것은 발췌가 아니라 그 예약이 된다.
> - **인덱싱 호출**: `IndexingOutputCap` 이 작업 크기에서 파생시킨다 — 재작성(MD 교정·txt→md)은 입력 추정 × 1.5, 고정 출력(키워드+맥락, 이미지 설명)은 `max-tokens` 의 비율. 예전에는 per-call 옵션에 온도만 실어 보내 프로바이더 빈에 구워진 값 **전체**가 예약됐고, 창 20,480 · `max-tokens=10000` 배포에서 MD 교정이 컨텍스트 초과로 실패한 것이 그 조합이었다(같은 프로퍼티가 아래 표의 섹션 크기까지 정하므로 **입력과 예약이 함께 커진다**).
>
> 둘 다 이 값에서 파생되고 이 값으로 잘리므로, `LLM_MAX_TOKENS` 를 내리면 전부 함께 내려간다 — 단일 손잡이는 유지된다.
>
> **스트리밍 답변**은 아예 보내지 않으므로 서버가 예약하지 않는다. 다만 입력 예산(§4.0)을 짤 때는 답변이 자랄 자리가 필요해 `ResponseMode.minChars()`(N 5,000)를 빼 둔다 — 블로킹의 `maxTokens`(N 7,000)를 그대로 빼던 것을 낮춘 값이다(§6.24 실측: 목표를 5,000자로 줘도 3,047자). (엄격한 서버는 거부, llama-server는 생성을 잘라 빈 응답 → 판정 없음). 컨텍스트별 권장 설정은 OPERATOR_MANUAL §8 「컨텍스트 윈도우(`n_ctx`)별 설정 산정」.

MD 교정 한 번의 LLM 호출은 `섹션(입력) + 시스템 프롬프트/지시문 + 교정 결과(출력, 대체로 입력과 비슷한 크기)`가 전부 **같은 컨텍스트 윈도우 안**에 들어가야 한다. 한글은 토큰당 문자 수가 영어보다 적어(문자당 토큰 소모가 더 큼) 위 문자 수가 실제로는 상당한 토큰량이 되므로, `LLM_MAX_TOKENS=12000`(섹션 5750자)은 `n_ctx=8192`인 로컬 모델에서 컨텍스트 초과 위험이 실질적이다. 대화 히스토리 예산도 다음 답변 생성 프롬프트(RAG 검색 결과 + 질문 + 시스템 프롬프트까지 함께 얹힘)에 그대로 들어가므로 값을 올릴수록 컨텍스트 압박이 커진다.

**권장**: 답변 길이 자체를 늘리려는 목적이라면 `LLM_MAX_TOKENS`는 적합한 손잡이가 아니다(위 스트리밍 경로 설명 참고). 로컬 배포에서 이 값을 정할 때는 컨텍스트 윈도우(`--ctx-size`, 기본 8192지만 모델이 허용하면 늘릴 수 있음)를 기준으로 위 표의 세 소비처가 합쳐도 여유가 남도록 마진을 두고(예: `n_ctx`의 절반 이하), 값을 8000~12000 등으로 올리고 싶다면 `LLM_MAX_TOKENS`를 올리기 전에 로컬 서버의 `--ctx-size`부터 그만큼(또는 그 이상) 늘려야 실제로 여유가 생긴다. 클라우드 프로바이더(Gemini/OpenAI 등)는 컨텍스트가 훨씬 크므로 이 값이 병목이 되지 않지만, `LlmConfig`가 이 값을 모든 프로바이더의 `defaultOptions`에 동일하게 굽기 때문에(뷰 전용, 재기동 필요) 가장 좁은 컨텍스트(보통 LOCAL)를 기준으로 잡아야 안전하다.

---

## 5. 재시도와 검증

```
ANSWER sufficient=false   AND retryCount < max  →  retryCount + retrievalRetries 증가 후 RETRIEVAL
CRITIC grounded=false     AND retryCount < max  →  retryCount 만 증가 후 ANSWER (재검색 없음)

responseMode=S            →  ANSWER 통과 시 CRITIC 생략 후 FINALIZE

PROGRESSIVE 모드 AND sufficient=false AND retryCount >= max
  →  PREMIUM 모델(⑦)로 단발 업그레이드 후 CRITIC 진행
```

**두 게이트는 다른 실패라 대응이 다릅니다** (§6.27). `sufficient=false`는 "질문에 답하지 못했다"이므로
재료를 바꿔야 하고, `grounded=false`는 "문서 밖으로 나갔다"이므로 재료는 그대로 두고 답변만 다시 써야
합니다 — 후자에서 재검색은 임베딩과 MultiQuery 확장 호출을 쓰고 사실상 같은 집합을 받아옵니다.

재시도가 실제로 다른 결과를 낼 수 있게 하는 것은 **`[직전 시도 메모]`** 입니다. 질문·시스템 프롬프트·
대화 이력이 그대로이고 일반/RAG 온도가 기본 `0.0`이라, 프롬프트가 달라지지 않으면 같은 답변이 그대로
재생성됩니다. 평가가 낸 반려 사유 한 문장(`evalReason`)이 답변 프롬프트에 들어가며, 추가 LLM 왕복은
없습니다. 지시("이 지적을 만족시켜라")가 아니라 관찰로 넣습니다 — 지적을 채우려고 지어내면 근거
지표가 오히려 나빠지기 때문입니다.

**재검색(`sufficient=false`)에서 달라지는 것 세 가지:**

| 축 | 동작 |
|---|---|
| 청크 교체 | 직전 시도에서 **근거로 안 쓰였고(`usedDocs`) + RRF 하위**인 청크를 최대 1/3 밀어냅니다(`RetrievalEviction`). 1순위는 항상 보존하고, `usedDocs`가 비었거나 검증 발췌가 잘린 시도에서는 아무것도 밀어내지 않습니다 — 그 경우의 "미사용"은 "안 쓰인 것"이 아니라 "모른다"/"보이지도 않은 것"이기 때문입니다 |
| 질의 축 추가 | 반려 사유 문장이 **검색 축 하나로** 더 들어갑니다. 원 질문 축은 그대로라, 사유가 엉뚱해도 그 축의 순위만 나빠집니다. LLM 왕복 0회 |
| 문서 수 | `topK + retrievalRetries` — 단, **검증 호출에 여유가 있을 때만** 늘립니다. 발췌가 잘리면 `unreliableNegative()`가 판정을 `null`로 떨어뜨려, 여유를 안 보면 재시도를 거듭할수록 판정이 사라집니다 |

> `retryCount`는 최초 RETRIEVAL 진입 시 증가하지 않습니다.  
> ANSWER 또는 CRITIC이 재시도를 결정할 때만 증가합니다(S 모드는 CRITIC 단계 자체가 없음).  
> `MAX_RETRY_COUNT=2`(기본)이면 최대 **2회 재검색**이 허용됩니다.

### 5.1 재검색 에스컬레이션 — 두 축

`SEARCH_RETRY_ESCALATE=true`(기본) 하나가 아래 둘을 **함께** 켜고 끕니다.

| 축 | 공식 | topK=10 기준 (최초 → 재검색1 → 재검색2) |
|---|---|---|
| 후보 풀 `candidateK` | `min(round(topK×(1+0.5×재검색횟수)), topK×3)` | 10 → 15 → 20 |
| **최종 컷 `effectiveTopK`** | `topK + 재검색횟수` (여유가 있을 때만) | 10 → **11** → **12** |
| **청크 교체** | 근거 미사용 ∩ RRF 하위, 최대 1/3 | — → 최대 3개 자리 교체 |

**세는 것은 재시도 횟수가 아니라 `retrievalRetries`(검색을 다시 한 횟수)입니다.** `grounded=false` 재시도는 검색을 건너뛰고 ANSWER로 바로 가므로(§5 표), `retryCount`를 쓰면 검색하지도 않은 만큼 에스컬레이션이 앞서 나갑니다.

후보 풀만 키우면 **최종 자리를 놓고 경쟁하는 문서**만 바뀌고 ANSWER 노드가 실제로 받는 문서 수는 매번 `topK` 그대로입니다. 재시도가 끌어올리려던 근거가 그 컷 바로 뒤(9~10위)에 있으면 재시도가 같은 이유로 다시 실패하고, 재시도 예산만 소모한 뒤 미검증 답변으로 끝납니다. 그래서 최종 컷도 재검색마다 한 개씩 넓히되 — 아래 조건이 붙습니다.

**최종 컷 +1은 검증 호출에 여유가 있을 때만 적용됩니다**(`RetrievalService.hasContextHeadroomFor()`). 기준이 답변 호출이 아니라 검증 호출인 이유는, 넘칠 때 나는 사고가 컨텍스트 초과가 아니라 **조용한 품질 저하**이기 때문입니다 — 발췌가 잘리면 `AnswerService.unreliableNegative()`가 근거 판정을 `null`(판정 없음)로 떨어뜨리므로, 여유를 보지 않으면 **재시도를 거듭할수록 판정을 잃습니다**. 직전에 반려된 답변(`state.answer()`)을 실측 재료로 쓰고, 창을 모르면 늘립니다(늘리는 쪽이 기존 동작이라 모르는 상태에서 동작을 바꾸지 않습니다).

최종 컷이 **가산(+1)**인 이유는 이 값이 답변·평가 프롬프트에 실제로 실려 나가는 양이기 때문입니다. 후보 풀처럼 배수로 키우면 재시도 2회에 문서 24개(기본 `CHUNK_SIZE=1500` 기준 약 36,000자)가 되어 로컬 모델 컨텍스트를 그대로 넘깁니다. 쿼리 확장 LLM 호출이 실패해 폴백 경로로 빠질 때도 같은 `effectiveTopK`로 자릅니다 — 그러지 않으면 하필 그 시도만 조용히 `topK`로 강등됩니다.

**후보 풀 배수는 ×2에서 ×1.5로 낮췄습니다** — 재시도가 이제 자리를 **비우기** 때문입니다(`RetrievalEviction`). topK의 1/3을 비우면 그 자리를 채울 만큼(≈×1.3)이면 되고, 풀을 키우는 것도 공짜가 아닙니다(융합·태그 필터·리랭크가 그 위에서 돕니다).

### 5.2 ⑤ 통합 평가가 보는 것

`sufficient`/`grounded`/`reason`/`envNote`는 **한 번의 LLM 호출**(⑤)로 함께 나오고, CRITIC 노드는 그 `grounded`를 읽어 재시도 여부만 결정합니다(별도 LLM 왕복 없음). 단 `responseMode=S`에서는 CRITIC이 실행되지 않아 `grounded`가 재시도 게이트로 사용되지 않습니다.

**증거 창은 답변 창과 같습니다.** 평가 프롬프트의 `[문서 발췌]`는 `retrievedDocs` **전부**(= 위 `effectiveTopK`)를 싣습니다. 예전에는 앞 5개만 실었는데, 답변 프롬프트는 `topK`(기본 10) 전체를 쓰므로 **6번째 이후 문서에만 있는 값을 정확히 인용한 답변이 근거 없음으로 판정**됐습니다. 경로·포트·상수처럼 한 청크에만 등장하는 단발성 사실이 이 불일치의 최대 피해자입니다(산문 주장은 여러 청크에 반복돼 앞 5개 안에서도 확인됨). 발췌 텍스트는 답변 프롬프트와 동일하게 `MarkdownNoiseNormalizer`를 거치므로 두 호출이 같은 **형태**의 값을 봅니다 — 평가만 원문 `**8080**`을 보고 답변은 정규화된 `8080`을 보던 불일치도 함께 제거됩니다.

발췌 총량 상한은 **32,000자**이며, 초과 시 문서를 **통째로** 하위 순위부터 제외합니다 — 중간을 자르면 검증 대상인 바로 그 값이 사라지기 때문입니다. 기본 설정(10 × 1500 = 15,000자)은 여유롭게 통과하므로 실제로는 과대 설정용 안전판이고, **32,000자에 실제로 닿으려면 64k급 컨텍스트가 필요**합니다(검증 호출은 여기에 답변 전문과 스키마까지 함께 싣습니다). 32k 이하 배포에서는 이 상수보다 `SEARCH_TOP_K × CHUNK_SIZE`가 먼저 한계를 정하므로, 조절 대상은 언제나 그쪽입니다 — OPERATOR_MANUAL §8의 산정표 참고. 제외가 발생하면 `[EVAL] 문서 발췌 32000자 상한으로 N개 중 M개만 검증에 사용` 경고가 남습니다.

### 5.3 환경 의존 값은 근거 실패 사유가 아니다 — `envNote`

경로·설치 위치·호스트/도메인/IP/포트/URL·환경변수 이름과 값·계정명은 **문서를 쓴 기계와 읽는 기계가 다르면 달라지는 것이 정상**입니다. 평가 프롬프트(`prompt.answer.eval`)는 이런 값이 문서와 다르거나 문서에 그대로 없다는 이유**만**으로 `grounded=false`를 내는 것을 금지하고, 대신 네 번째 필드 `envNote`에 "어떤 값이 환경에 따라 달라질 수 있는지"를 한 문장으로 받습니다.

- 예외는 **값**에만 적용됩니다. 절차·기능·동작 방식·인과관계·옵션의 의미가 문서와 다르면 환경 차이가 아니므로 여전히 `grounded=false`입니다.
- `evalReason`(실패 사유)과 달리 `envNote`는 **검증을 통과해도 유지**됩니다. 판정이 아니라 "이 경로는 본인 환경 기준으로 바꾸라"는 독자용 안내이기 때문입니다.
- 전달 경로: `AgentState.envNote` → `ChatResponse.env_note`(REST) / SSE `done.envNote`(스트리밍) → 답변 아래 `ℹ️ 환경에 따라 달라질 수 있는 값: …` 한 줄. 블로킹·스트리밍 두 경로가 같은 문구로 렌더합니다.
- 규칙이 프롬프트 문자열에만 존재해 코드로는 아무도 눈치채지 못하므로, `AnswerEvalPromptTest`가 실제 메시지 번들(한/영)에 예외 블록과 `envNote` 필드가 남아 있는지 검사합니다.
- 모델이 `envNote`를 주지 않거나 빈 문자열로 두면 그냥 표시되지 않습니다(기존 동작과 동일).

---

## 6. 문서 임포트 흐름

### 6.1. 진입점

| 방식 | 엔드포인트 | 내부 메서드 |
|------|-----------|-----------|
| 단일 업로드 | `POST /api/v1/documents` | `RagService.indexDocument()` |
| 디렉터리 동기화 | `POST /api/v1/documents/sync` | `RagService.syncDirectory()` |

### 6.2. 단일 파일 인덱싱

```
파일 수신
  │
  ├─ SHA-256 해시 → docId 생성 (filename_해시앞8자)
  │
  ├─ 파일 타입별 파싱  (DOCX·TXT·PPTX·PDF[비스캔] 는 모두 Markdown 으로 정규화 후 처리)
  │    PDF   → 스캔 감지(페이지 50% 이상이 50자 미만) 시 페이지 단위 + OCR 자동 적용 (MD 변환 없음)
  │            비스캔 시 PdfToMarkdownConverter 로 페이지별 [페이지: N] 마커(합성 헤딩 없음 — 마커가
  │            섹션 경계 겸함) + [이미지: ...] 인라인 마커 삽입 → LLM 포맷 교정 → 섹션 분할
  │    PPTX  → PptxToMarkdownConverter 로 슬라이드별 [페이지: N] 마커 + 제목 헤딩(##, 제목 없는 슬라이드는
  │            헤딩 없이 마커만) + [이미지: ...] 인라인 마커 삽입 (본문 불릿은 들여쓰기 레벨만 중첩 목록으로 반영, 소제목으로 승격하지 않음)
  │            → LLM 포맷 교정 → 섹션 분할
  │    DOCX  → DocxToMarkdownConverter 로 MD 변환 → LLM 포맷 교정 → 섹션 분할 (이미지 인라인)
  │    TXT   → 로컬 LLM 으로 구조화(제목/목록/표) + 문법 교정하여 MD 변환 → LLM 포맷 교정 → 섹션 분할
  │    MD    → 이미지/링크 마커 전처리 → 섹션 분할
  │
  ├─ 이미지 추출
  │    PDF/PPTX → PdfToMarkdownConverter/PptxToMarkdownConverter 가 각각 PdfImageExtractor/
  │               PptxImageExtractor 를 내부에서 호출해 data/images/{docId}/ 에 저장하고, MD 변환
  │               시점에 헤딩 바로 다음 위치에 [이미지: ...] 마커로 곧바로 삽입한다(DOCX와 동일 방식) —
  │               별도의 사후 메타데이터 첨부 단계 없이 loadFromMarkdown() 이 그 마커를 그대로 인식해
  │               image_paths 로 승격시킨다
  │    DOCX     → 파싱 단계에서 함께 처리 (본문에 [이미지: ...] 인라인 마커 삽입)
  │    TXT/MD   → 없음 (평문/마크다운)
  │    → chunk 메타데이터 image_paths 에 경로 기록
  │
  ├─ [체크포인트] 여기까지(MD 변환+교정, 이미지 추출) 성공 시 doc_registry에 partial row 저장
  │    (chunks=0, spring_doc_ids=[]) — 이후 청킹~레지스트리 저장 단계 중 실패해도 이 docId가
  │    레지스트리에 남아, 관리자 화면 "재인덱싱"(↺)으로 이미지 분석/MD 교정을 다시 거치지 않고
  │    저장된 MD 파일 기준으로 재시도 가능 (스캔 PDF는 MD 파일을 만들지 않아 해당 없음).
  │    existsBySha256AndVersion()은 chunks>0인 row만 "색인 완료"로 인정하므로, 이 partial row
  │    때문에 디렉터리 동기화가 미완료 문서를 다음 동기화에서 영구히 건너뛰지는 않는다
  │
  ├─ 청킹
  │    DOCX/TXT/MD → 챕터(헤딩) 단위 유지(mergeSectionsByChapter). minChunkSize 미만 섹션만
  │      다음 섹션과 병합하되 다음이 상위(부모) 헤딩이면 금지, 크기별 규칙으로 병합 방향 결정
  │      (합≤chunkSize 병합 / next 단독≤chunkSize 분리 / 초과분은 prepend 후 마지막 조각
  │      ≥minChunkSize×1.5일 때만 병합). 하위 챕터(레벨≥3) 청크 첫 조각엔 바로 위 부모 헤딩
  │      1줄을 브레드크럼으로 덧붙이고(prependParentBreadcrumb), 그래도 minChunkSize 미만인
  │      청크는 직전 청크로 뒤로 병합(backwardMergeShortChunks). 초과 섹션은 섹션 내부 슬라이딩 윈도우
  │    PPTX/PDF(비스캔) → (사전 패스) 연속 슬라이드의 ##+### 헤딩이 완전히 같으면 chunkSize
  │      이내에서 최대 2장까지 하나로 합침(mergeIdenticalHeadingSlides) — 두 번째부터는 중복
  │      헤딩 제거, 대신 [페이지: N] 마커 삽입. 이후 슬라이드/페이지 섹션 단위 유지(mergeShortSections),
  │      초과 시 섹션 내부 슬라이딩 윈도우. 그 외에는 서로 다른 슬라이드/페이지(page_or_slide)
  │      간 병합 금지 — "청크 1개 = 슬라이드/페이지 1개 = 정확한 인용" 보장을 유지하기 위함
  │    PDF(스캔) → 슬라이딩 윈도우만 적용 (chunkSize / chunkOverlap, 섹션 병합 없음 — 기존 동작 그대로)
  │
  ├─ 메타데이터 태깅
  │    doc_id, filename, version, doc_type, sha256, collected_at,
  │    chunk_index, owner_id, visibility, tags(선택), page_or_slide,
  │    source_type, image_paths, heading(MD/DOCX/PPTX/PDF[비스캔] 섹션 제목)
  │
  ├─ 기존 청크 삭제 (재인덱싱 시 동일 docId 덮어쓰기)
  │
  ├─ 키워드+맥락 추출 LLM (§10.1 Contextual Retrieval — 청크 N개(기본 2)를 번호 매긴 프롬프트로
  │    묶어 배치당 1콜, §10.8.2. N=1이면 청크당 1콜이던 이전 동작과 동일)
  │    → excerpt_keywords 메타데이터 추가
  │    → chunk_context 메타데이터 추가 ("{파일명} > {heading}" 구조적 맥락 + LLM 1~2문장,
  │      LLM 실패 또는 배치 응답 파싱 실패 시 해당 청크(들)만 구조적 맥락만으로 폴백(TF 추출) —
  │      임베딩/FTS 입력 전용, 영속 저장 안 함)
  │
  ├─ 임베딩 입력 구성 = chunk_context + 정규화(원문) (§10.1-보완 — 마크다운 장식 제거)
  │    청크당 1회만 계산해 재사용(§10.8.5) — 벡터 스토어 저장과 FTS 인덱싱이 같은 결과를 공유
  │    저장·표시 텍스트(원문)는 그대로 유지, 임베딩/FTS 입력에만 반영
  │
  ├─ 벡터 스토어 저장 (version별 — chroma 컬렉션 / sqlite-vec partition, content는 원문;
  │    sqlite-vec는 토큰 서브배치 단위로 임베딩 직후 즉시 삽입하는 스트리밍 구조(§10.9.3,
  │    문서 전체 임베딩을 힙에 모았다가 한 번에 삽입하지 않음 — 피크 메모리가 서브배치 크기로
  │    고정) — 서브배치별 벡터+청크 배치 삽입 2개는 여전히 하나의 트랜잭션으로 커밋(§10.8.3))
  │    + FTS 인덱스(chunk_fts)에도 동일 맥락+정규화 텍스트 반영 (Contextual BM25 시너지)
  │
  └─ 레지스트리 저장 (SQLite doc_registry 테이블 — memory.db 공유; 위 체크포인트에서 남긴
       partial row를 실제 chunk수/spring_doc_ids로 덮어씀)
```

> **임베딩 병렬화(§6.21 E1~E3)**: 위 "임베딩 입력 구성 → 벡터 스토어 저장"의 임베딩 호출은 다중 엔드포인트 로드밸런싱(E1, `EMBED_ADDITIONAL_BASE_URLS` — 같은 모델을 N개 서버에 두고 least-in-flight 분산)과 서브배치 병렬 임베딩(E2, `EMBED_MAX_CONCURRENT_BATCHES`, 기본 1=직렬)으로 처리량을 확장할 수 있다(opt-in). Chroma는 임베딩만 병렬화 후 1회 upsert, sqlite-vec는 병렬 임베딩 후 직렬 삽입(pool=1)이라 E2를 켜면 위 §10.9.3 스트리밍 메모리 상한을 속도와 맞바꾼다. 상세는 OPERATOR_MANUAL §3.2 "임베딩 병렬화".

### 6.3. DOCX → MD → 임베딩 DB 저장 상세 (이미지 포함)

아래는 DOCX 파일 1건이 들어와 임베딩 DB(Chroma 또는 sqlite-vec)에 저장될 때의 실제 처리 순서.

```
1) 입력 수신
  filePath(.docx), version, tags

2) docId 생성
  sha256(file) 계산 → docId = "{filename}_{sha256앞8자}"

3) 기존 아티팩트 정리(동일 docId)
  - 기존 벡터 청크 삭제
  - 기존 이미지/converted MD 삭제

4) DOCX → Markdown 변환
  DocumentLoaderService.convertDocxToMd()
    └─ DocxToMarkdownConverter.convert()
      - Heading 스타일 → Markdown heading(#/##/###)
      - 명시적 page break(w:br type=page) 추적
      - 각 헤딩 앞에 [헤딩페이지: N] 마커 삽입 (헤딩 시작 위치 보존)
      - 페이지 전환 시 [페이지: N] 앵커 마커 삽입 (비헤딩 구간 근사 페이지 보강)
      - 표 → pipe table
      - run 단위 bold/italic 반영
      - 내장 이미지 추출: data/images/{docId}/d{para}_img{n}.{ext}
      - 본문에는 [이미지: images/{docId}/{file}] 마커 삽입
      - EMF/WMF는 설정 시 PNG 변환, 실패/미설정 시 [이미지(변환불가): ...] 마커
      - 사진과 같은 문단의 레거시 VML 주석 도형(v:rect/v:oval/v:roundrect/v:line)은 사진 위에
        그려 하나의 합성 PNG로 저장 (app.docx-image.merge-annotated-shapes, 기본 true —
        DocxAnnotationShapeMerger; POI가 DOCX 도형 좌표를 노출하지 않아 같은-문단 근사 방식,
        합성 실패 시 원본 사진 폴백. 상세는 IMAGE_PROCESS.md §4.3)

5) 변환 산출물 저장
  - 원본 MD: data/converted/{docId}.md

6) Markdown 교정 [LLM]
  MarkdownCorrectionService.correct()
  - 전체 MD 1회 호출이 아니라, splitBySections()로 섹션 분할 후 병렬 교정
  - 분할 기준 (splitBySections, 모두 코드펜스 ```/~~~ 내부에서는 적용 안 함):
    a) H2/H3/H4 챕터 헤딩(줄이 "## "·"### "·"#### "로 시작) — 펜스 안의 "### Job ID : ..." 같은
       로그/배치 실행 결과 줄은 헤딩처럼 보여도 분할 트리거로 취급하지 않음
    b) 섹션 길이가 maxSectionChars 초과 시 강제 분할
       (maxSectionChars = max(500, (LLM_MAX_TOKENS-500)/2) → 기본 10,000토큰 기준 4,750자 —
        §6.18 이전에는 별도의 죽은 프로퍼티를 통해 기본값 8,000을 읽어 3,750자였음. 이제
        실제 LLM 응답 상한과 동일한 소스(app.llm.max-tokens)를 공유)
       — 펜스가 열려 있는 동안 초과가 감지되면 펜스는 자르지 않고, 펜스 시작 위치로 처리 분기:
         · 펜스가 이 섹션 안에서 MIN_SECTION_CHARS/2(250자) 이상 지난 뒤에 시작됐다면
           → 펜스 이전 내용까지만 즉시 flush하고, 펜스 전체(지금까지 쌓인 내용 포함)를
             통째로 다음 섹션으로 넘겨 그 섹션에서 계속 자라게 함
         · 펜스가 섹션 아주 초반(< 250자)에 시작됐다면 → 넘겨봤자 자투리 섹션만 남으므로
           넘기지 않고, 펜스가 닫힐 때까지 이 섹션에 그대로 누적(섹션이 한도를 넘긴 채로 flush됨)
    c) 문서 끝까지 펜스가 닫히지 않은 기형 입력은 안전하게 "```"를 붙여 마감
  - **작은 섹션 병합** (`mergeSmallSections`, 위 분할 직후 적용): 헤딩이 잦은 문서(짧은 소제목이
    몇 줄마다 나오는 DOCX/MD)에서 위 a)/b) 분할 결과를 그대로 교정에 넘기면 헤딩 하나당 LLM 호출
    하나가 나가 왕복 수가 과도하게 늘어난다(인덱싱 시간에 직결). 그래서 분할된 섹션들을 앞에서부터
    순서대로 `maxSectionChars` 예산 안에서 이어붙여 한 번의 교정 호출로 묶는다 — `splitByPages`가
    PPTX 슬라이드를 묶는 것과 같은 패턴. 각 섹션은 분할 단계에서 이미 펜스가 항상 닫힌 완전한
    조각이 보장되므로(헤딩 경계는 펜스 밖에서만 발동) 단순 문자열 이어붙이기만으로 안전하다. 이미
    예산을 넘긴 섹션(위 b)의 강제 분할 결과)은 그 자체로 한 다발이 되어 다른 섹션과 섞이지 않는다.
  - 섹션 경계 오버랩 (부자연스러운 경계에서만, 결정론적 제거): 대부분의 경계(깔끔한 ##/###/####
    헤딩 전환)에는 아무 것도 덧붙이지 않고 그대로 자른다. 다만 경계가 "부자연스러울" 때만
    (isUnnaturalBoundary) 인접 섹션의 실제 내용 몇 줄을 오버랩으로 함께 넘긴다 —
    ① 다음 섹션이 잘 만들어진 헤딩(## / ### / #### + 공백 + 텍스트)이 아닌 줄로 시작(크기 초과로
    헤딩이 아닌 지점에서 강제 분할된 경계, 또는 "# " H1·"#=====" 배너·"#########" 장식 해시처럼
    converter/코드 잡음으로 보이는 시작), ② 헤딩 레벨이 직전 헤딩보다 2단계 이상 급강하(## 다음
    #### 등)하는 경우. 이런 경계에서만 다음 섹션 앞 OVERLAP_LINES(5)줄을 이 섹션 끝에
    `<<<SECTION_END>>>` 마커와 함께 덧붙이고(tail 오버랩), 이전 섹션 끝 5줄을 이 섹션 앞에
    `<<<SECTION_START>>>` 마커와 함께 덧붙인다(head 오버랩) — 공백/빈 줄은 세지 않는다
    (leadingNonBlankLines()/trailingNonBlankLines()). 오버랩은 "읽기 전용 미리보기"가 아니라 교정
    대상이라, 경계를 넘어 이어지는(예: converter가 코드 안 "##" 줄을 헤딩으로 오인해 자른) 코드
    블록을 LLM이 양쪽 모두 올바르게 펜스로 감쌀 수 있다. LLM에는 "마커 줄은 그대로 두고, 마커를
    사이에 두고 양쪽 내용을 서로 합치지 말라"고만 지시하고, 교정 뒤 코드가 마커를 기준으로
    오버랩을 결정론적으로 잘라낸다(cutOverlap: head 오버랩은 `<<<SECTION_START>>>` 앞까지, tail
    오버랩은 `<<<SECTION_END>>>` 뒤까지 제거) — 그래서 같은 내용이 인접 두 섹션에 모두 남아
    중복되는 일이 없다. 이는 예전에 모든 경계에서 lookahead/lookbehind 미리보기를 LLM에게 "결과에
    넣지 말라"고 맡기고 `<<<RESULT_START>>>`/`<<<RESULT_END>>>`로 추출하던 방식(LLM이 미리보기를
    결과에 섞으면 중복 발생)을 코드 결정론으로 대체한 것이다. 코드가 넣은 마커가 응답에서 사라졌으면
    그 섹션은 오버랩 없이 재교정한다(중복 0 보장 폴백).
  - 섹션별 교정이 끝나 전체 MD가 재조립되면(`String.join("\n\n", corrected)`) 결정적(비-LLM) 후처리
    체인이 이어진다 — LLM 교정은 확률적이라 자주 흐트러뜨리는 포맷을 코드로 확실히 잡는 안전장치:
    ① `fixClosingFences(result)` — 닫는 코드펜스가 언어 태그를 달고 닫히는 경우(여는 ```sql … 닫는
    ```sql)를 순수 ```로 교정한다. fence 상태를 토글하며 **닫는** 펜스의 정보 문자열만 제거하고
    (여는 펜스는 유지), 다음 `normalizeCodeBlocks`의 fence 정규식이 정상 쌍을 보도록 그 **앞**에서
    실행한다. **펜스 치유**: 닫는 펜스 자체가 통째로 빠진 경우(LLM이 마감을 완전히 누락) — 펜스가
    열린 상태(`inFence=true`)로 2~7단계 챕터형 제목(`## `~`####### `)을 만나면 그 줄 바로 앞에
    합성 ```` ``` ```` 을 삽입해 닫는다(`looksLikeChapterHeadingNotComment()`). 방치하면 그 뒤로
    등장하는 모든 진짜 여는 펜스가 닫는 펜스로 오판돼 언어 태그가 연쇄적으로 벗겨지므로, 발견 즉시
    치유해 상태 꼬임을 끊는다. 다만 `### 주석 ###`·`### ###`처럼 제목 내용 자체가 `#`으로 끝나는
    줄은 일부 언어의 배너 주석으로 보고 치유 대상에서 제외한다(8단계 이상 `########`도 챕터 제목으로
    보지 않음).
    ② `addHeadingNumbers` 값과 무관하게 항상 `normalizeCodeBlocks(result, false)`가 모든 코드펜스
    (```)를 정리한다(`normalizeCodeContent()`) — 코드 블록 안의 빈 줄은 기본적으로 전부 제거하고,
    다음 두 경우에만 빈 줄 1개를 남긴다: ⓐ 여러 줄 주석(블록 주석/독스트링 오프너, 또는 연속 2줄
    이상의 라인 주석) 시작 직전, ⓑ 바로 위에 주석이 없는 함수·메서드·클래스 시그니처 시작 직전
    (제어자 키워드·`def`/`class`·`fun`/`func`/`fn`·셸 함수 형태를 인식하는 정규식 휴리스틱이며,
    `if`/`for` 같은 제어문은 대상에서 제외됨). 이 패스는 또한 `resolveCodeLanguage()`로 **잘못 붙은
    ```sql 태그를 Java 코드로 교정**하고, 라벨이 아예 없는 블록은 내용을 보고 언어를 추론해 채운다
    (아래 "언어 판정" 참조 — `inferLanguage=true`, 모든 문서에 적용). 언어에 무관하게 동작하며 LLM이
    섹션 교정 중 흐트러뜨린 코드 블록 포맷을 재조립 이후 결정론적으로 정리한다.
  - **코드 블록 언어 판정** (`resolveCodeLanguage`/`inferCodeLanguage`): Java 코드가 SQL로 오분류되던
    문제(예: `repository.delete(...)`·`jdbc.select(...)` 메서드 호출을 `\bdelete\b`/`\bselect\b`로
    SQL로 잡음)를 두 방향으로 고친다 — ⓐ **SQL 판정 엄격화**(`SQL_STATEMENT`): `SELECT … FROM`·
    `UPDATE … SET`은 줄 시작에 앵커하고, `INSERT INTO`·`DELETE FROM`·`CREATE TABLE` 등 Java 식별자가
    만들 수 없는 다단어 형태만 SQL로 인정(그래서 `.delete(` 같은 메서드 호출은 매칭 안 됨). ⓑ **Java
    적극 식별**(`JAVA_CODE_SIGNAL`, JVM 전용 신호 — `public/private class`·`@Override` 등 애노테이션·
    `System.out.`·`.println(`·`new Foo(`·`implements/extends`·제네릭 `List<` 등): 이 신호는 SQL
    스크립트엔 없어서 실제 SQL을 가로채지 않으므로 `inferCodeLanguage`에서 SQL보다 먼저 검사한다.
    라벨 없는 블록은 이 순서로 추론하고, 이미 `sql` 태그가 붙은 블록도 Java 신호가 있고 실제 SQL 문이
    없으면 `java`로 교정한다(이미 붙은 `python`·`java` 등 다른 태그나 실제 SQL의 `sql` 태그는 보존).
  - `addHeadingNumbers=true`(문서 업로드 화면 "소제목 숫자 생성" 체크박스)면 위 정리가 끝난 뒤
    `addHierarchicalHeadingNumbers()`로 H2~H6 헤딩에 계층적 번호를 매긴다(기존 번호 프리픽스는 먼저
    제거 후 현재 헤딩 순서로 재계산 — 그래서 아래 `reapplyHeadingNumbers()`로 재실행해도 매번 안전).
    이 패스는 펜스 내부를 건너뛰므로 코드 블록에는 영향이 없다 — **PPTX는 체크박스 상태와 무관하게
    이 옵션을 항상 무시한다**(§6.3-bis 2번)
  - **코드 블록 언어 추론은 이 체크박스와 무관하게 항상 실행된다**(`normalizeCodeBlocks(result, true)`).
    예전에는 추론이 위 `addHeadingNumbers` 게이트 안쪽 2차 패스(`secondPassHeadingAndCodePolish()`)에만
    들어 있어, 체크박스를 끄고 올린 DOCX/TXT/MD와 (항상 `addHeadingNumbers=false`로 강제되는) **PPTX
    전체**가 언어 태그 없는 펜스로 남았다 — 코드의 언어는 소제목 번호 옵션과 아무 상관이 없으므로
    분리했다. 이제 1차 정리 시점에 바로 추론까지 끝내고, 2차 패스는 헤딩 번호만 담당한다
  - 마지막으로 항상 `postProcessMarkdown(result, isPptx)`가 fence/table-aware 결정적 정리를 한 번 더
    한다(코드 블록 **내부**는 무변형). `isPptx`는 이 호출 시 이미 넘어온 `groupByPage`를 그대로
    재사용한다 — PPTX 전용이라는 의미가 같기 때문이다(§6.3-bis 2번). **PPTX일 때만**
    (`isPptx=true`) `applyPptxShapeFormatting()`이 아래 ①~④보다 먼저 실행된다 —
    `PptxToMarkdownConverter`와 위 섹션별 LLM 교정이 남기는 도형 그룹/이미지 앵커 서식 문제를
    정리하는 5개 규칙:
      a) 불릿(`- `) 사이 빈 줄 정규화(`normalizeBulletGaps`) — 빈 줄 1개는 삭제(우발적 잡음으로
         간주), 2개 이상은 1개로 축소(의도된 구분으로 간주). 불릿 뒤가 다른 불릿이 아니면(본문·
         헤딩·문서 끝 등) 빈 줄 개수를 그대로 둔다.
      b) `[도형 그룹]` 블록 안에서 완전히 같은 한 줄(숫자 1개 또는 단어 1개 — 공백 없는 단일
         토큰, 예: SmartArt에서 겹친 텍스트 런 때문에 중복되는 단계 번호·라벨)이 반복되면 첫
         등장만 남기고 이후 중복은 드롭(`dedupSingleTokenLinesInShapeGroups`, 블록 단위로 스코프).
         `[`로 시작하는 구조 마커 줄(`[이미지: ...]` 등 — 이미지 참조를 실수로도 지우지 않기 위해)과
         `{`/`}`를 포함한 줄(플레이스홀더 등)은 절대 건드리지 않는다.
      c) `[도형 그룹]` 여는 마커 바로 다음에 오는 이미지 앵커(`[이미지: ...]`/`[이미지 설명: ...]`)
         묶음 — 항상 그룹 내부 텍스트보다 먼저 나온다(`appendShapeGroup()`) — 앞뒤에 빈 줄을
         보장해 마커·그룹 내부 텍스트와 분리한다(`ensureImageAnchorBoundaryBlankLines`).
      d) 그룹 안팎 어디서든 이미지 앵커 단위(`[이미지: ...]` + 선택적 `[이미지 설명: ...]`)가
         2개 이상 빈 줄 없이 연속하면 그 사이에 빈 줄을 삽입한다(`ensureBlankBetweenConsecutiveImages`)
         — 이미지 하나뿐이면 손대지 않는다.
      e) `[도형 그룹]` 여는 마커 앞, `[/도형 그룹]` 닫는 마커 뒤에 빈 줄을 보장한다
         (`ensureBlankAroundShapeGroupMarkers`) — 이미 빈 줄이 있으면 중복 삽입하지 않는다.
    다섯 규칙 모두 fence-aware(코드 블록 내부는 절대 건드리지 않음)이고, 이미 조건이 맞으면
    아무것도 바꾸지 않는다 — 이 패스가 새로 넣은 빈 줄은 뒤이어 항상 실행되는 아래 ①~④가 함께
    정규화한다(중복 축소·말미 트림 포함): ① 남은 프롬프트 구분자 `[DOCUMENT]`/`[/DOCUMENT]` 줄
    제거, ② 내용 없는 `-` 한 줄 제거(수평선 `---`·`- 항목`·표 구분줄 `|---|`은 보존), ③ **코드
    블록과 GFM 표 앞뒤에 빈 줄 보장**(표 구분줄 `|---|` 기준으로 표 블록을 감지 — 앞뒤 빈 줄이 없어
    표/코드가 깨지던 문제), ④ 펜스 밖 연속 빈 줄을 1개로 축소.
  - **표 셀 안 이미지 설명은 `<br>`로 주입**: `addImageDescriptions=true`로 이미지 설명을 넣을 때
    (`injectDescriptionsForPattern`), 마커가 표 행 안(`looksLikeTableRow`)이면 설명을 개행이 아니라
    `<br>`로 붙인다 — 셀 안 개행(`[이미지: x]\n[이미지 설명: y]`)이 행을 두 줄로 쪼개 표 전체를 깨뜨리기
    때문. 이미지 설명 주입은 섹션 교정 **전**에 일어나므로, "표는 변경 금지" 지시를 받은 LLM이 `<br>`가
    든 행을 그대로 보존한다.
  - **진행 상황 보고(SSE `describing_images` 스테이지)**: `MarkdownCorrectionService.
    prewarmImageDescriptions()`가 distinct 이미지 개수를 파악한 직후 `onImageDescribed(0, total)`을
    한 번 호출해 총 개수를 알리고, 이후 이미지 1장의 Vision 분석이 끝날 때마다
    `onImageDescribed(done, total)`을 호출한다 — `DocumentIndexer`가 이를
    `IndexingProgressEvent(stage="describing_images", ...)`로 감싸 업로드 화면에 "이미지 분석 중
    (N/M)"을 실시간 표시한다([UI.md §3.2](UI.md#32-문서-관리-documentcontroller) 참고). 섹션 교정
    진행률(`onSectionDone`, `correcting` 스테이지)과는 별개 콜백이며 항상 먼저 끝난다 — 이 프리패스가
    오래 걸려도 화면이 직전 단계 메시지(예: "PPTX → Markdown 변환 중...")에 멈춰 있지 않게 하기 위함.
  - 교정본 MD: data/converted/{docId}_corrected.md
  - 이후 파이프라인은 교정본을 source로 사용

6-bis) 레지스트리 체크포인트
  MD 교정까지 성공한 시점에 doc_registry에 partial row 저장 (chunks=0, spring_doc_ids=[])
  - 이후 7)~13) 중 어디서 실패해도 이 docId가 레지스트리에 남아 있어, 관리자 화면의
    "재인덱싱"(↺)으로 4)~6)(이미지 분석 포함)을 다시 거치지 않고 저장된 MD 파일 기준으로
    재시도할 수 있다
  - 13)의 최종 레지스트리 저장이 같은 docId를 실제 chunk수/spring_doc_ids로 덮어쓴다
  - DocRegistry.existsBySha256AndVersion()은 chunks > 0인 row만 "이미 색인됨"으로 인정하므로,
    이 partial row 때문에 syncDirectory()가 미완료 문서를 다음 동기화에서 영구히 건너뛰지
    않는다

7) MD 섹션 로드
  DocumentLoaderService.loadFromMarkdown(sourceMd, skipChapterNumbers)
  - 섹션별 Document 생성
  - [헤딩페이지: N] 마커 파싱 후 섹션 메타데이터 heading_page/page_or_slide 로 저장
  - [페이지: N] 마커는 그 자체가 섹션 경계 — 만나면 현재 섹션을 flush하고 새 섹션을 시작하며,
    그 페이지 번호를 새 섹션의 page_or_slide 로 지정(PPTX 제목 슬라이드는 바로 뒤 ## 헤딩이
    이어지지만 마커가 이미 flush했으므로 빈 섹션이 생기지 않는다; 제목 없는 PPTX 슬라이드·비스캔
    PDF 페이지는 헤딩 없이 마커만으로 섹션이 나뉘고 page_or_slide 를 유지). 마커 줄 자체는
    본문(Document.getText())에 남지 않는다
  - 프롤로그(첫 헤딩 이전 구간)는 첫 헤딩의 [헤딩페이지: N]이 있으면 해당 값을 우선 상속
  - [이미지: ...] / [이미지(변환불가): ...] 마커 파싱
  - image_paths 메타데이터에 경로(쉼표 결합) 저장
  - **챕터 번호(chapter_no)**: H2~H6 ATX 헤딩을 만날 때마다 레벨별 계층 카운터를 증가시켜
    "1"·"1.1"·"1.5.3" 형태의 문자열을 계산해 그 헤딩 이후 섹션들에 저장한다(같은 레벨의 다음
    헤딩을 만나면 그 값을 하나 늘리고, 더 깊은 레벨의 카운터는 리셋 — MarkdownCorrectionService.
    addHierarchicalHeadingNumbers()와 동일한 방식이지만 헤딩 텍스트에 번호를 삽입하는 게 아니라
    별도 메타데이터로만 기록). H1은 챕터로 세지 않음(직전 값 유지). 코드펜스 안의 "###" 같은 줄은
    (섹션 분할과 마찬가지로) 카운터에 영향을 주지 않는다. 첫 H2~H6 헤딩 이전(프롤로그) 구간과
    헤딩이 전혀 없는 문서는 "0". `skipChapterNumbers=true`(DocumentIndexer가 PPTX·비스캔 PDF일 때
    전달)면 이 계산을 통째로 건너뛰어 항상 "0"으로 남는다 — 비스캔 PDF는 이제 합성 헤딩을 아예
    만들지 않으므로(페이지 구분은 `[페이지: N]` 마커가 담당) 계산할 헤딩 자체가 없고, PPTX의 `##`은
    실제 챕터 구조가 아니라 슬라이드 제목/부제목 라벨(§6.3-bis 2번)이라 챕터로 세면 안 되기 때문이다.
    (PPTX 제목을 챕터로 세면 슬라이드마다 "1"·"2"…가 붙어 페이지 번호를 다른 이름으로 중복시킬 뿐이다.)
    재인덱싱(↺)도 저장된 파일명 확장자(.pptx 또는 .pdf)로 skipChapterNumbers를 다시 판단해 동일하게
    적용 — 스캔 PDF는 애초에 MD 파일이 없어 재인덱싱 대상에서 제외됨.

8) 청킹
  splitDocuments()
  - DOCX는 섹션 유지 우선
  - 섹션이 chunkSize 초과 시 sliding window 분할

9) 메타데이터 태깅
  DocumentIndexer.tagMetadata()
  - doc_id, filename, version, doc_type, sha256, chunk_index, page_or_slide, chapter_no, tags,
    image_paths 등

10) 키워드+맥락 추출(enrich) [LLM] — §10.1 Contextual Retrieval
  KeywordExtractor.enrichParallel() — 청크를 app.indexing.keyword-batch-size(기본 2)개씩
  묶어 배치당 1회 LLM 호출(§10.8.2, enrichKeywordsBatch()); 나머지 1개짜리(마지막 배치 등)는
  기존 단일 청크 경로(enrichKeywords())를 그대로 사용:
  - excerpt_keywords 메타데이터 추가
  - chunk_context 메타데이터 추가 ("{filename} > {heading}" 구조적 맥락 + LLM 1~2문장 맥락;
    LLM 실패/타임아웃 또는 배치 응답에 결과 마커가 모두 없으면(파싱 실패) 해당 청크(들)만
    구조적 맥락만으로 폴백 — 사용량은 context: 라벨로 기록)

11) 임베딩 입력 구성 — §10.1-보완 임베딩 입력 정규화
  SearchTextBuilder.build() = chunk_context + MarkdownNoiseNormalizer.normalize(원문)
  - 마크다운 장식 줄(구분선 등) 제거, 강조 마커(**bold**/*italic*/<u>)만 제거하고 내용 보존
  - 코드펜스 내부·표 행은 무변형
  - 이 파생 텍스트는 임베딩·FTS 입력에만 쓰이고 영속 저장되지 않음(저장/표시는 원문 그대로)
  - SearchTextBuilder.precompute()가 청크당 1회만 계산해 임시 메타키(search_text)에 담아
    12)의 두 소비처(임베딩·FTS)에 공유 — 각자 다시 계산하지 않음(§10.8.5)

12) 임베딩 DB 저장
  a) Chroma 모드
    - `chromaApi.upsertEmbeddings()`로 수동 upsert(TokenCountBatchingStrategy 서브배치) —
      임베딩은 11)의 파생 텍스트, 저장 content/metadata는 원문(chunk_context/search_text 키 제외)
  b) sqlite-vec 모드
    - TokenCountBatchingStrategy 서브배치 단위로 임베딩 → 즉시 삽입을 반복하는 스트리밍
      구조(§10.9.3) — 문서 전체(예: 500+청크)의 임베딩을 모두 힙에 모은 뒤 한 번에 삽입하지
      않으므로 피크 메모리가 문서 크기가 아니라 서브배치 크기에 비례한다
    - vec_embeddings: spring_doc_id, version, embedding(11의 파생 텍스트로 계산)
    - vec_document_chunks: spring_doc_id, content(원문), metadata(JSON, chunk_context/search_text 제외), version, doc_id, created_at
    - 서브배치마다 두 배치 삽입을 하나의 트랜잭션으로 커밋(§10.8.3) — 중간 실패 시 함께
      롤백되어 vec_embeddings만 커밋되고 매칭되는 vec_document_chunks가 없는 상태가 생기지
      않음(트랜잭션 범위는 문서 전체가 아니라 서브배치 단위)
  + FTS 인덱스(chunk_fts)에도 doc_tags/keywords + content(11의 파생 텍스트, Contextual BM25 시너지) 반영

13) 레지스트리 저장 (최종)
  doc_registry에 docId/version/chunk수/spring_doc_ids 기록 — 6-bis)에서 남긴 partial row를
  실제 값으로 덮어씀
```

핵심 포인트:
- DOCX 이미지 파일은 별도 디렉터리에 저장되고, 청크 본문에는 마커로 남는다.
- 마커 경로는 `loadFromMarkdown()`에서 `image_paths` 메타데이터로 승격되어 임베딩 DB metadata(JSON/Map)에 함께 저장된다.
- 따라서 검색 결과 청크가 이미지 경로 컨텍스트를 유지한 채 반환된다.
- DOCX는 물리 페이지 전체 보전 대신, 헤딩 단위 페이지 위치를 보전한다.
- `page_or_slide`는 DOCX에서 헤딩 시작 페이지(명시적 page break 기준)를 우선 사용하고, 없으면 기존 청크 순번 fallback을 사용한다.
- **저장·표시 텍스트(원문) ≠ 임베딩·FTS·답변 프롬프트 입력(맥락+정규화)** — 3계층 분리가 §10.1/10.1-보완의 핵심 원칙이며, `AnswerService.buildAnswerPrompt()`도 정규화된(맥락 헤더 없는) 텍스트를 사용한다.

### 6.3-bis. PPTX/PDF(비스캔) → MD 변환 — DOCX와의 차이점

6.3절 DOCX 흐름과 4)~7) 단계는 거의 동일하되(변환 → 저장 → LLM 포맷 교정 → MD 섹션 로드 → 청킹 → 태깅 → 키워드+맥락 추출 → 임베딩 저장), 다음 지점만 다르다.

1. **변환기**: `PptxToMarkdownConverter`(PPTX) / `PdfToMarkdownConverter`(PDF, 스캔 아닌 경우만) — `DocxToMarkdownConverter`와 나란히 `service` 패키지에 위치. **각각 `PptxImageExtractor`/`PdfImageExtractor`를 생성자로 주입받아 이미지까지 직접 처리한다**(DOCX와 동일한 소유 구조 — 4번 참고). `convert()`가 맨 먼저 그 추출기로 슬라이드/페이지→경로 맵을 통째로 뽑아 두고, 슬라이드/페이지별 텍스트를 조립하면서 그 맵의 경로를 헤딩 바로 다음에 마커로 삽입한다.
2. **헤딩 생성 규칙**:
   - **PPTX**: 슬라이드 제목 placeholder(`TITLE`/`CENTERED_TITLE`)만 `##`로 승격한다. 세부 규칙은 아래 하위 항목 참고.
     - **헤딩·건너뛰기**: 제목이 없는 슬라이드는 **합성 헤딩 없이 `[페이지: N]` 마커만** 붙는다(예전의 `"{N}번 슬라이드"` 폴백 헤딩은 실제 구조가 아니라 `page_or_slide`로 이미 관리되는 번호를 본문에 노이즈로 남길 뿐이라 제거됨 — `[페이지: N]` 마커 자체가 섹션 경계 역할을 겸한다). 제목은 없지만 본문(불릿 등) 또는 이미지(`XSLFPictureShape`)가 있는 슬라이드는 그대로 유지한다 — 이미지만 있고 텍스트가 없는 슬라이드까지 건너뛰면 그 슬라이드에 대응하는 섹션 자체가 사라져 추출된 이미지의 `[이미지: ...]` 마커를 심을 자리가 없어지기(이미지가 고아가 됨) 때문. **제목·본문·이미지가 모두 없는 슬라이드(완전 공백 구분 슬라이드 등)만 마커·본문 전부 생략하고 통째로 건너뛴다** — 그렇지 않으면 마커만 있는 내용 없는 청크가 그대로 임베딩/검색 인덱스에 남아 노이즈가 된다. 건너뛴 슬라이드는 다음 슬라이드의 `[페이지: N]` 번호에 영향을 주지 않는다(실제 슬라이드 인덱스를 그대로 사용).
     - **본문 렌더링**: 본문 불릿은 들여쓰기 레벨(`XSLFTextParagraph.getIndentLevel()`)을 중첩 목록으로만 반영하고, 어떤 경우에도 소제목(`###` 이상)으로 승격하지 않는다 — 슬라이드 하나를 하나의 원자적 섹션으로 다루는 편이 PPTX의 실제 구조에 더 가깝고, 들여쓰기를 헤딩으로 승격하면 평범한 한 줄짜리 불릿 목록도 소제목이 되어 메타데이터가 산만해질 위험이 있기 때문(검토된 대안 및 채택 근거는 구현 당시 논의 참고). `FOOTER`/`SLIDE_NUMBER`/`DATETIME` placeholder는 매 슬라이드에 반복되는 노이즈(예: "대외비" 문구)라 본문에서 완전히 제외한다. 불릿은 자동 번호 목록(`getAutoNumberingScheme() != null`)이면 `"1. "`, 일반 불릿이면 `"- "`로 렌더링해 DOCX 변환기와 동일하게 순서형/비순서형을 구분한다. 하이퍼링크가 걸린 run은 `XSLFTextRun#getHyperlink()`를 읽어 `[텍스트](URL)`로 렌더링한다.
     - **그래픽 프레임(표/SmartArt/OLE/차트)**: `XSLFTable`·`XSLFDiagram`(SmartArt)·`XSLFObjectShape`(OLE)·차트 프레임은 모두 `XSLFGraphicFrame` 변형으로, 일반 `XSLFTextShape` 순회로는 절대 잡히지 않아 별도 분기로 처리한다 — SmartArt는 `getGroupShape()`(실제 렌더링된 도형 레이어)를 그룹 도형과 동일하게 재귀 추출해 박스 라벨 텍스트를 `appendShapeGroup()`이 `[다이어그램] ... [/다이어그램]` 마커로 감싸 본문에 남기고(일반 그룹 도형도 동일 함수로 `[도형 그룹] ... [/도형 그룹]`으로 감싸짐 — 아래 7번 참고), 차트는 시리즈/축 값 추출이 차트 종류마다 달라 안정적으로 뽑기 어려우므로 제목 텍스트만 `[차트: 제목]` 인라인 라벨로 추출하며, OLE는 POI로 일반화해 파싱할 텍스트가 없어 본문에는 아무것도 남기지 않는다(미리보기 이미지는 아래 4번 참고).
     - **마커 규칙**: 이 세 마커는 같은 도형에서 나온 여러 라벨을 한 블록으로 묶고 "도형에서 추출된 텍스트"임을 표시하기 위한 것으로, `[이미지: ...]`와 동일하게 `Document.getText()`에 그대로 남아 임베딩/FTS·`/admin` 표시·답변 프롬프트에 반영된다 — `#`가 아니라 `[`로 시작해 `splitMarkdownBySections()`의 섹션 경계로 오인되지 않고, 텍스트가 하나도 없는 도형(순수 장식용 그룹 등)은 마커 자체를 생략해 빈 블록을 남기지 않는다. 한 슬라이드에 같은 종류(그룹/다이어그램/차트)가 2개 이상이면(`slide.getShapes()` 기준 개수) 라벨에 발견 순서대로 순번이 붙어(`[도형 그룹 1]`/`[도형 그룹 2]`, `[다이어그램 1]`, `[차트 1: 제목]`) 서로 구분되고, 1개뿐이면 기존과 동일하게 번호 없이 렌더링된다(기존 단일-도형 출력과의 하위 호환). 그 도형이 소유한 이미지(아래 4번의 owner 추적)는 슬라이드 상단이 아니라 해당 마커 블록의 여는 마커 바로 다음에 `[이미지: ...]`로 인라인 삽입되어 어떤 이미지가 어떤 도형/차트에서 나왔는지 드러난다 — 소유 도형이 없는 일반 사진은 기존과 동일하게 슬라이드 상단에 모아 표시된다(§6.3-bis 4번 참고로, `PptxToMarkdownConverter` 클래스 상단 주석의 "이미지 마커는 항상 상단에 hoist" 설명은 그룹/다이어그램/차트가 소유하지 않은 이미지에만 해당하도록 갱신됨).
     - **도형 여러 줄 텍스트의 블록 처리(`appendShapeTextBlock()`)**: 그룹/다이어그램 안의 텍스트 도형은 **도형 하나가 곧 하나의 덩어리**다. 줄이 하나뿐이면 예전처럼 빈 줄 없이 촘촘하게 이어붙이지만(짧은 노드/단계 라벨이 대부분), **여러 줄이면** 인접한 다른 도형의 라벨과 뒤섞여 한 문단으로 읽히지 않도록 블록으로 묶는다 — ⓐ `looksLikeCodeBlock()`이 코드로 판정하면 앞뒤에 ```` ``` ```` 펜스를 두르고(펜스 안에는 강조 마커가 없는 원문 `rawParagraphText()`를 넣어 코드를 그대로 재현), ⓑ 코드가 아니면 앞뒤에 빈 줄만 넣어 하나의 문단 블록으로 분리한다(줄 사이는 기존처럼 한 줄 개행 유지). 펜스로 감싸면 `MarkdownNoiseNormalizer`가 안쪽을 건드리지 않고 `splitMarkdownBySections()`도 구조로 파싱하지 않아, 들여쓰기나 `---` 같은 줄이 장식으로 오인돼 지워지는 일이 없다(§6.3의 코드펜스 취급과 동일).
     - **변환기가 만든 펜스와 이후 후처리의 관계**: 변환기는 언어 태그 없는 맨 ```` ``` ```` 펜스를 내보내고, 그 뒤 `MarkdownCorrectionService.correct()`가 이어서 다듬는다 — ⓐ `normalizeCodeBlocks(md, false)` → `normalizeCodeContent()`가 **펜스 안 빈 줄을 원칙적으로 전부 제거**하되 여러 줄 주석 시작 앞과 함수/클래스 시그니처 앞의 빈 줄 하나는 남긴다(그래서 변환기가 중간 빈 줄을 보존해 넘기는 것이 의미가 있다 — 지워도 될 빈 줄만 정리되고 의미 있는 구분은 살아남는다), ⓑ 같은 패스가 라벨 없는 펜스의 **언어 태그를 내용 기준으로 추론해 채우고**(`inferLanguage=true` — 이 추론은 `addHeadingNumbers` 체크박스와 분리되어 있어 PPTX에도 적용된다, §6.3 참고), ⓒ `postProcessMarkdown(md, isPptx=true)`가 펜스 **앞뒤에 빈 줄을 보장**해 앞 문장과 붙어 렌더링이 깨지지 않게 한다.
     - **코드 판정(`looksLikeCodeBlock()`)**: 오탐(평문을 펜스로 감쌈)이 미탐보다 훨씬 해로우므로 보수적이다. **빈 줄은 분자(신호 적중 줄)에도 분모(전체 줄 수)에도 세지 않는다** — 코드 중간의 논리 구분용 빈 줄이 분모만 키워 적중률을 떨어뜨리면(예: 코드 3줄 + 빈 줄 2줄 → 3/5=60%로 아슬아슬) 멀쩡한 코드가 평문으로 새기 때문이며, 호출자가 빈 줄을 걸러 넘기든 그대로 넘기든 같은 결과가 나오도록 판정 함수 안에서 직접 거른다. **즉시 코드가 아니라고 확정**하는 경우 — ① 줄 하나라도 `-`/`*`/`+`/`•`/`1.` 같은 목록 표시로 시작(불릿이 섞여 있으면 코드가 아니다), PPTX 네이티브 불릿 문단(`para.isBullet()`)도 동일 취급, ② 본문에 이미 ```` ``` ````가 들어 있어 펜스를 두르면 마크다운이 깨지는 경우, ③ 빈 줄을 뺀 내용이 한 줄 이하인 경우. 그 외에는 줄별 코드 신호(`CODE_LINE_SIGNAL` — `;`/`{`/`}` 종결, `//`·`/*` 주석, `public`/`class`/`return`/`function`/`def` 등 키워드, `SELECT`/`FROM`/`WHERE` 등 SQL, `::`/`&&`/`==`/`<=` 등 연산자, XML/HTML 태그 한 줄, 대입문) 적중률이 `CODE_LINE_RATIO_PERCENT`(60%) 이상일 때만 코드로 본다 — 2줄짜리는 둘 다 맞아야 하고, 3줄이면 2줄이면 된다. 다이어그램 라벨에 흔한 화살표(`->`/`=>`)는 "요청 -> 응답" 같은 평범한 흐름 표기가 훨씬 흔해 오탐 위험이 커서 **일부러 신호에서 제외**했다.
     - **단독 텍스트 상자에도 동일 적용(`appendSlideBodyShape()`)**: 위 블록 처리는 그룹 안의 도형뿐 아니라 **그룹에 속하지 않은 슬라이드 본문 텍스트 상자**에도 똑같이 적용된다(코드는 그룹보다 단독 상자에 들어 있는 경우가 더 흔하다). 도형 하나의 문단을 먼저 모은 뒤(`BodyLine` 리스트) 한꺼번에 렌더링하는데, 헤딩 후보 승격·연속 중복 제거는 기존과 똑같이 문단 순서대로 진행하므로 판정 결과가 달라지지 않는다. 여러 줄이고 불릿이 없으며 코드로 판정되면 통째로 펜스로 감싸고(들여쓰기 보존 위해 `trim()` 대신 `stripTrailing()`한 원문 사용, 중간 빈 줄도 그대로 유지), 그 외에는 줄마다 기존 `appendBodyLine()`으로 넘겨 동작이 그대로다 — 코드가 아닐 때 그룹 경로처럼 별도 빈 줄 블록을 만들지 않는 이유는 `appendBodyLine()`이 이미 비불릿 문단마다 `\n\n`를 넣어 문단으로 갈라놓기 때문.
     - **중복 제거(도형/줄 단위)**: 그룹 내부에서는 서로 다른 도형의 텍스트(문단을 합친 전체 텍스트, `combineShapeText()`)가 강조 마커·공백 차이를 무시하고 완전히 같으면 그 도형을 통째로 스킵해 하나만 남기고(`appendGroupText()`가 그룹 하나당 독립된 판정 범위를 가짐 — 중첩 서브그룹까지 포함해 공유), 슬라이드 본문에서도 직전 줄과 내용이 같은 줄이 연속되면(같은 기준으로 정규화 비교) 하나만 남긴다(비연속 반복은 유지).
     - **중복/목차 슬라이드 제거(슬라이드 단위, `app.pptx-remove-duplicate-slides`/`PPTX_REMOVE_DUPLICATE_SLIDES`, 기본 `true`)**: Pass 2에서 슬라이드를 출력하기 전에, **이미지가 없는** 슬라이드에 한해 두 가지로 제거한다 — ① **완전 동일**: 제목 후보 + 본문을 강조 마커·공백 무시로 정규화한 지문(`slideFingerprint()`)이 앞선 슬라이드와 같으면 첫 등장만 남기고 드롭(섹션마다 반복되는 동일 목차·백업 슬라이드 등). ② **목차형**: 본문 불릿(선두 목록 마커 제거 후)이 덱 전체의 **다른 슬라이드 제목**들과 `TOC_MIN_MATCHED_HEADINGS`(3)개 이상 **그리고** 전체 줄의 `TOC_MATCH_RATIO`(60%) 이상 일치하면 항해용 목차/agenda 슬라이드로 보고 드롭(실제 내용은 각 섹션 슬라이드에 이미 있으므로 검색 인덱스에 남길 가치가 없다). 두 임계값을 모두 요구해 제목 몇 개를 우연히 언급하는 진짜 본문 슬라이드의 오탐을 줄인다. **이미지가 있는 슬라이드는 절대 드롭하지 않는다**(추출 이미지가 고아가 되고, 텍스트가 같아도 사진이 다르면 진짜 중복이 아님). 드롭된 슬라이드는 빈 슬라이드 스킵과 동일하게 뒤 슬라이드의 `[페이지: N]` 번호를 밀지 않는다(`slideNum = i+1`). 플래그를 `false`로 두면 모든 슬라이드를 그대로 유지한다. 오탐이 의심되면 이 플래그를 끄고 재인덱싱하면 된다.
     - **구분용 제목 슬라이드 제거(`app.pptx-drop-divider-slides`/`PPTX_DROP_DIVIDER_SLIDES`, 기본 `true`)**: 위 중복/목차 제거 다음 단계로, **본문·이미지 없이 제목(들)만** 있는 슬라이드(`isSectionDividerSlide()`)를 그 제목이 전부 '구분용'일 때만 드롭한다 — 내용 없는 섹션 표지 청크는 검색되어도 LLM에 줄 내용이 없고 인덱스 슬롯만 차지하기 때문. '구분용' 판정(`looksLikeSectionDivider()`)은 ⓐ 번호/라벨형 패턴(`SECTION_LABEL`: `3장`·`제1절`·`PART 2`·`STEP 3`·`II.`·`1)`·`부록 A` 등), ⓑ 구분 키워드(`DIVIDER_KEYWORDS`: `목차`·`개요`·`서론`·`결론`·`요약`·`agenda`·`overview`… — `개요`처럼 `요`로 끝나 서술어로 오인되기 쉬운 것을 명시적으로 포함), ⓒ 짧은 명사구(`DIVIDER_MAX_WORDS`(3)단어·`DIVIDER_MAX_CHARS`(12)자 이하이고 주어/목적어 조사 `은/는/이/가/을/를`(`CLAUSE_PARTICLE`)도 서술어 종결(`SENTENCE_ENDING`)도 없음) 중 하나. 서술어로 끝나거나 조사를 포함한 **문장형/키 메시지 제목**("고객 만족을 최우선으로 합니다", "우리의 목표는 성장")은 실제 내용으로 보고 **유지**한다(오탐 방지). 제목이 둘이면 **모두** 구분용일 때만 드롭한다. 본문이나 이미지가 있는 슬라이드는 (제목이 구분용처럼 보여도) 대상이 아니다. 드롭 시 뒤 슬라이드 번호는 밀리지 않으며, `DEBUG` 로그에 사유가 남는다.
     - **예고 제목 슬라이드 제거(`app.pptx-drop-redundant-title-slides`/`PPTX_DROP_REDUNDANT_TITLE_SLIDES`, 기본 `true`)**: 위 구분용 제목 제거와 같은 전제(이미지 없음, 본문 없음 — 표/그룹/다이어그램/차트 텍스트도 전부 본문으로 들어가므로 "본문 없음"이 곧 "추가 정보 없음") 아래, 그 제목(`headingCandidates`)이 **바로 다음 슬라이드**의 내용(제목+본문, 강조 마커 제거·공백 정규화 후 비교)에 부분 문자열로 그대로 포함되면 드롭한다(`isRedundantTitlePreviewSlide()`) — 다음 슬라이드가 같은 제목을 헤딩으로 다시 쓰며 실제 내용을 담는 흔한 "예고" 패턴으로, 앞 슬라이드는 실질적으로 빈 예고편이다. 구분용 제목 제거와 달리 제목의 형태(번호/키워드/명사구 등)는 보지 않고 **다음 슬라이드와의 실제 내용 일치**만 본다 — 문장형 제목이라도 다음 슬라이드에 그대로 반복되면 대상이다. 정규화된 제목이 1글자면(우연한 부분 일치 위험) 대상에서 제외한다. 덱의 마지막 슬라이드는 다음이 없어 항상 유지된다.
     - **마지막 종료 슬라이드 제거(`app.pptx-drop-ending-slide`/`PPTX_DROP_ENDING_SLIDE`, 기본 `true`)**: 덱의 **마지막 슬라이드에만** 적용 — 이미지가 없고, 슬라이드 전체 내용(제목+본문)을 공백·구두점 제거 + 소문자화로 정규화한 결과가 `끝`/`end`/`theend`/`감사합니다`/`thankyou`(`ENDING_MARKERS`) 중 하나를 **포함**하며, 그 표시를 뺀 나머지 글자 수가 `ENDING_SLIDE_MAX_EXTRA_CHARS`(10) 이하이면 드롭한다(`isEndingOnlySlide()`) — "감사합니다 여러분"처럼 짧은 서명이 덧붙어도 여전히 대상이지만, 이메일·전화번호 같은 연락처가 함께 있는 마무리 슬라이드는 나머지 글자 수가 10을 넘어 유지된다. 마지막이 아닌 슬라이드의 '끝'/'END'/'감사합니다'는 이 규칙의 대상이 아니다(중간에 나오는 정상적인 소제목일 수 있음).
     - **과도한 볼드 억제**: 슬라이드 하나의 최종 조립된 본문(본문+표+그룹 텍스트가 모두 합쳐진 뒤, 표 6번의 줄바꿈 수정도 반영된 뒤) 볼드(`**`/`***`) 스팬이 10개 이상이면 과도한 강조로 보고 전부 제거한다(`EXCESSIVE_BOLD_THRESHOLD`, 이탤릭은 대상 아님) — 슬라이드 전체가 볼드로 서식된 경우 등에 대응. 이와 별개로 도형 그룹(`appendShapeGroup()`)·표(`appendTable()`) 하나만 놓고도 같은 판정을 한 번 더 적용한다 — 그 블록 안의 볼드 스팬이 `BLOCK_BOLD_COUNT_THRESHOLD`(6)개 이상이거나 볼드로 덮인 글자 비율이 `BLOCK_BOLD_RATIO_THRESHOLD`(50%) 이상이면 그 블록만 볼드 마커를 전부 제거한다 — 볼드가 도형 그룹/표 하나에만 몰려 있어 슬라이드 전체 개수는 10 미만인 경우(예: 표 셀 6개만 전부 볼드)를 놓치지 않기 위한 블록 단위 보완 규칙이다.
     - **소제목 번호 매기기 강제 해제**: `addHeadingNumbers`(소제목 숫자 생성) 옵션은 체크박스 상태와 무관하게 PPTX 인덱싱 경로에서 항상 `false`로 강제된다(`DocumentIndexer`의 `.pptx` 분기가 `correctionService.correct()` 호출 시 요청값을 무시하고 고정값을 넘김) — PPTX의 `##`/`###` 헤딩은 슬라이드 제목/부제목 라벨(위에서 설명한 최대 2단계 calibration)이지 문서 목차 같은 계층 구조가 아니라서, 순차적으로 번호를 매겨도 실제 구조와 무관한 숫자만 붙고 이미 있는 `[페이지: N]` 마커와도 겹쳐 혼란만 준다.
     - **섹션 분할**: 같은 이유로 `MarkdownCorrectionService.correct()` 호출 시 섹션 분할 방식도 DOCX와 다르다: PPTX 인덱싱 경로는 `groupByPage=true`를 넘겨, 일반 헤딩 기준 분할(`splitBySections()`, §6.3 참고) 대신 `[페이지: N]` 마커를 경계로 쓰는 `splitByPages()`가 적용된다 — 슬라이드 하나가 `##`+`###` 헤딩을 모두 가진 경우에도 `###`가 별도 분할 트리거가 되지 않고, `[페이지: N]` 마커도 자기 슬라이드 섹션의 맨 앞에 온다(헤딩 기준 분할 시 이전 섹션 꼬리에 잘못 붙던 문제 해결). 다만 "슬라이드 하나 = 교정 호출 하나"는 아니다: 슬라이드는 자족적이라 페이지 경계가 항상 깔끔하므로, `splitByPages()`는 연속된 슬라이드를 문자 예산(`maxSectionChars`) 안에서 최대 `PPTX_MAX_BUNDLE_PAGES`(4)장까지 하나의 교정 호출로 묶는다 — 작은 슬라이드가 많을 때 LLM 왕복 횟수를 크게 줄인다. 반대로 슬라이드 하나가 `maxSectionChars`를 넘으면 묶을 수 없으므로 그 슬라이드만 `[도형 그룹]`/`[다이어그램]`/`[차트]` 블록 경계(`splitOversizedPage()`)로 쪼갠다(그룹 블록 하나는 통째로 유지, 블록 하나가 그래도 크면 문자 예산으로 강제 분할). 슬라이드 경계는 언제나 깔끔하므로 PPTX 경로에는 §6.3의 "부자연 경계 오버랩"이 쓰이지 않는다. DOCX·TXT·MD·PDF(비스캔)는 기존과 동일하게 `groupByPage=false`(일반 헤딩 기준 분할).
   - **PDF(비스캔)** — 페이지 텍스트만으로는 신뢰할 구조 신호가 없으므로 **합성 헤딩을 만들지 않고 `[페이지: N]` 마커만** 부여한다(제목·소제목 추론 없음 — 예전의 `"## N페이지"` 합성 헤딩은 실제 구조가 아니라 `page_or_slide`로 이미 관리되는 번호를 본문에 노이즈로 남길 뿐이라 제거됨). 텍스트도 이미지도 없는 페이지만 마커 생략하고 건너뛰되(텍스트는 없어도 이미지가 있으면 PPTX와 동일한 이유로 건너뛰지 않음), 다음 페이지 번호는 밀리지 않고 실제 PDF 페이지 인덱스를 그대로 유지한다.
3. **페이지/슬라이드 마커 = 섹션 경계**: 항상 제네릭 `[페이지: N]` 마커만 사용한다(DOCX 전용의 `[헤딩페이지: N]`은 쓰지 않음). 이 마커 자체가 슬라이드/페이지 단위 섹션 경계다 — `DocumentLoaderService.splitMarkdownBySections()`는 이 마커에서 새 섹션을 시작하고(제목이 없는 슬라이드/페이지도 마커만으로 섹션이 나뉘며 `page_or_slide`가 유지된다), `MarkdownCorrectionService.splitBySections()`(비스캔 PDF)/`splitByPages()`(PPTX)도 이 마커를 교정 섹션 경계로 쓴다. 제목이 있는 PPTX 슬라이드는 마커 바로 다음 줄에 실제 제목 `##`이 이어지는데, 마커가 이미 이전 섹션을 flush했으므로 빈 섹션이 생기지 않고 제목 섹션 하나로 합쳐진다. (`[페이지: N]`은 PPTX·PDF만 내보내며 DOCX/TXT/MD는 쓰지 않으므로 다른 형식에는 영향이 없다.) 완전히 비어 있는 슬라이드/페이지는 애초에 마커조차 내보내지 않고 건너뛴다(2번 항목 참고).
4. **이미지**: DOCX와 동일하게 본문에 `[이미지: ...]` 인라인 마커를 넣는다 — `[페이지: N]` 마커(그리고 제목 슬라이드는 그 뒤 헤딩) 바로 다음, 본문 텍스트보다 앞에 슬라이드/페이지별 이미지 경로를 마커로 삽입한다. 별도의 사후 메타데이터 첨부 단계는 없다 — `DocumentLoaderService.loadFromMarkdown()`이 이미 갖고 있던 `[이미지: ...]` 마커 파싱 로직이 이 마커도 그대로 인식해 `image_paths`로 승격시킨다(DOCX와 완전히 동일한 메커니즘 재사용). 이 덕분에 `addImageDescriptions`(이미지 설명 추가) 옵션도 이제 PPTX/PDF에 정상 적용된다 — [IMAGE_PROCESS.md §5](IMAGE_PROCESS.md#5-vision-설명-생성-l2) 참고.
   - **PPTX 전용 — 그리기 도구 도형 래스터라이즈 (`app.pptx-image.rasterize-shapes`, 기본 `false`)**: `PptxImageExtractor`는 실제 삽입 이미지(`XSLFPictureShape`)뿐 아니라, 텍스트 도형 순회에서 잡히지 않는 "그리기 도구" 요소도 PNG로 래스터라이즈할 수 있다 — 그룹 도형(`XSLFGroupShape`), 독립 커넥터(`XSLFConnectorShape`, 화살표/선), 텍스트 없는 일반/자유형 도형이 "시드"가 된다. **`rasterize-shapes=true`일 때만** 아무 앵커(사진/표/그룹)에도 안 겹친 "느슨한" 시드들끼리 각 바운딩박스를 `app.pptx-image.cluster-proximity-padding-pt`(기본 15pt)만큼 바깥으로 부풀린 뒤 교차 여부로 연결 요소를 구하는 union-find 클러스터링을 적용해 다이어그램 한 장으로 묶는다 — 커넥터는 보통 두 도형이 겹치지 않는 "틈"에 놓이므로 순수 bbox 교차만으로는 다이어그램을 못 묶기 때문. **`rasterize-shapes=false`(기본)이면 이 느슨한-도형 클러스터링을 하지 않는다** — 겹친 느슨한 도형이 한 덩어리로 뭉치지 않고, 아무것에도 안 겹친 단독 도형은 이미지로 아예 안 뽑힌다. 단 아래 앵커 기반 합성(그룹·SmartArt 각 한 장 / 표+겹친도형 / 사진+주석)은 이 플래그와 무관하게 항상 유지된다. 시드가 하나도 없는 클러스터(텍스트 도형끼리만 우연히 근접한 경우)는 다이어그램이 아니므로 버린다. 텍스트가 있는 도형(텍스트 상자 포함)은 시드 근처에 있을 때만 함께 묶이는 승객으로 참여하고 — 이때도 그 텍스트는 `PptxToMarkdownConverter`가 `[도형 그룹] ... [/도형 그룹]` 마커로 감싸 별도로 본문에 추출해 Vision 미사용 환경에서도 검색 가능하다(그룹 내부 텍스트는 `appendGroupText()`가 재귀적으로 추출하고 `appendShapeGroup()`이 그 결과를 마커로 감쌈) — 시드 없이 혼자 있으면 절대 래스터라이즈되지 않는다. 순수 텍스트 상자는 비어 있으면 대상에서 제외한다. 가로/세로 중 큰 쪽이 `app.pptx-image.min-shape-dimension-pt`(기본 30pt) 미만인 도형은 아이콘/구분선으로 보고 시드가 될 수 없다 — 두 값 모두 `AppProperties.pptxImageSafe()`로 설정 가능(패딩을 넓히면 더 먼 도형까지 묶이고, 임계값을 높이면 더 큰 도형도 아이콘 취급되어 제외된다). 클러스터가 25개 도형을 넘으면(너무 어수선한 슬라이드) 번들 대신 시드만 개별 래스터라이즈하는 것으로 폴백한다. 렌더링은 클러스터 전체를 감싸는 바운딩박스를 캔버스로 잡고 좌표축을 한 번만 이동/확대한 뒤 각 도형을 원래 순서(z-order)대로 그리는 방식(`DrawFactory`)이며, 실패는 EMF/WMF 변환과 동일하게 조용히 건너뛴다.
   - **PPTX 전용 — 이미지-도형 상관관계(owner 추적)**: `PptxImageExtractor.extractWithOwners()`는 추출/래스터라이즈된 이미지마다 그 이미지를 만든 최상위 도형의 `slide.getShapes()` 인덱스(0-based, z-order — 클러스터링에 쓰이는 것과 동일한 인덱스 공간)를 `ExtractedImage.ownerShapeIndices()`로 태깅해 반환한다. 일반 그룹은 자기 자신의 인덱스, SmartArt는 (클러스터링에 실제로 투입되는 내부 `getGroupShape()` 렌더 도형이 아니라) `slide.getShapes()`에 나타나는 바깥쪽 `XSLFDiagram` 프레임 자신의 인덱스, 차트 fallback 그림은 그 차트 프레임의 인덱스를 owner로 갖는다 — 그룹/다이어그램/차트가 아닌 커넥터·자유형 도형·사진 등은 owner가 없다(빈 Set). `PptxToMarkdownConverter`는 `inReadingOrder()`로 재정렬하기 전의 원본 `slide.getShapes()`로 동일한 인덱스 공간을 독립적으로 계산해 도형별 소유 이미지를 찾고, 위 2번 항목처럼 해당 마커 블록 안에 인라인으로 배치한다(그렇게 소비된 이미지는 상단 hoist 목록에서 제외된다). 드물게 인접한 두 그룹의 패딩된 바운딩박스가 겹쳐 하나의 클러스터로 합쳐지면 그 이미지의 owner가 2개 이상이 되어 두 그룹 블록 모두에 동일한 이미지 마커가 나타날 수 있다(의도된 동작 — 실제로 두 그룹이 하나의 이미지로 합쳐졌다는 사실을 그대로 반영). 기존 `extract()`/`extract(XMLSlideShow, ...)` API(경로 문자열 리스트만 반환)는 하위 호환을 위해 그대로 유지되며, 내부적으로 `extractWithOwners()`를 감싸 owner 정보만 제거한다.
   - **PPTX 전용 — 그래픽 프레임 변형(SmartArt·차트·OLE) 이미지 처리**: `XSLFTable`을 제외한 `XSLFGraphicFrame` 변형은 POI가 "라이브"로 그릴 수 없어(`DrawGraphicalFrame`은 내부적으로 프레임의 `mc:Fallback` 미리보기 그림만 그리고, 없으면 아무것도 그리지 않음) 그리기 도구 래스터라이즈와는 다른 경로를 탄다. **OLE 객체**(`XSLFObjectShape`)는 OOXML 스펙상 항상 자체 미리보기 그림을 내장하므로(`getPictureData()`) 일반 픽처와 동일하게 그대로 저장한다. **SmartArt**(`XSLFDiagram`)는 프레임 자체가 아니라 `getGroupShape()`(실제 렌더링된 박스/커넥터 도형 레이어)를 그룹 도형과 동일한 근접 클러스터링 파이프라인의 시드 하나로 투입해 래스터라이즈한다 — 이 그룹은 진짜 도형들로 구성돼 있어 POI가 정상적으로 그릴 수 있다. **차트**는 POI에 라이브 렌더링 경로가 전혀 없어 `getFallbackPicture()`(PowerPoint가 하위 호환용으로 남겨둔 `mc:Fallback` 미리보기)가 있을 때만 그대로 저장하고, 없으면 조용히 건너뛴다(제목 텍스트는 위 2번 항목처럼 본문에 남는다).
5. **청킹**: PPTX/PDF(비스캔)는 슬라이드/페이지 섹션 병합(`mergeShortSections`) 전략을 타며, 서로 다른 `page_or_slide`를 가진 인접 섹션끼리는 병합을 금지한다(`ChunkSplitter.isMergeForbiddenByPageMismatch()`) — "청크 1개 = 슬라이드/페이지 1개 = 정확한 인용" 보장을 유지한다. 단, 그 앞 사전 패스(`mergeIdenticalHeadingSlides`, PPTX 전용 — 자세히는 아래 챕터 청킹 주석 다음 문단 참고)가 연속 슬라이드의 `##`+`###` 헤딩이 완전히 같을 때만 이 경계를 넘어 병합한다. **DOCX/TXT/MD는 이와 별개로 챕터 기반 병합(`mergeSectionsByChapter`, §6.4 표 아래 주석)을 탄다** — `page_or_slide` 값이 없어 이 페이지-경계 금지 규칙은 어차피 no-op이고, 대신 minChunkSize·부모 헤딩 기준의 챕터 병합이 적용된다.
6. **표(테이블)**: PPTX의 `XSLFTable`은 나타나는 위치에 마크다운 파이프 표로 변환된다(`PptxToMarkdownConverter.appendTable()`) — DOCX와 달리 PPTX 표 모델은 병합된 셀도 행의 셀 목록에서 빠지지 않고 그대로 남아 각 행이 항상 같은 셀 수를 가지므로, DOCX처럼 gridSpan 기반으로 셀 목록을 재구성할 필요 없이 병합 연속 셀(`XSLFTableCell.isMerged()`)만 빈 칸으로 렌더링하면 된다. **표 위에 겹친 시드 도형(강조 원·화살표 등)이 있으면**, 위 MD 변환과 별개로 `PptxImageExtractor`가 표+도형을 하나의 합성 PNG로도 만든다(`rasterize-shapes`와 무관하게 항상 — 표는 이미지 추출 시 앵커로 취급, `DrawFactory`가 `DrawTableShape`로 표를 렌더링) — 표 셀을 짚는 markup의 시각 맥락을 보존하기 위함. 겹친 시드 도형이 없는 표는 이미지로 만들지 않는다(MD 파이프 표로만). 셀 안에 `<a:br/>`(Shift+Enter) 줄바꿈이 있으면 POI `XSLFTextRun.getRawText()`가 그 자리에 리터럴 `"\n"`을 반환해 파이프 표 행이 여러 줄로 쪼개지며 마크다운이 깨질 수 있었는데, `tableCellText()`가 셀 텍스트를 조립할 때 그 줄바꿈(및 주변 공백)을 공백 하나로 치환해 항상 한 줄로 유지되도록 수정됐다 — 이 수정은 최상위 표와 그룹 내부 표(`appendGroupText()`가 호출하는 `appendTable()`) 모두에 적용된다.
7. **스캔 판정**: `DocumentLoaderService.loadPdfPagesForConversion()`이 페이지 텍스트 추출과 스캔 판정(§6.6, 빈 페이지 50% 초과)을 함께 반환해, 스캔 PDF는 기존 `ocrWithPdfRenderer()` OCR 경로로, 비스캔 PDF는 위 MD 변환 경로로 분기한다 — 스캔 판정 로직 자체(임계값·휴리스틱)는 변경되지 않았다.
8. **MD 재인덱싱(↺)**: 위 변환기들도 `converted/{docId}.md`(+`_corrected.md`)를 남기므로 PPTX·비스캔 PDF도 DOCX·TXT와 동일하게 `/admin` 재인덱싱을 지원한다(스캔 PDF는 MD 파일이 없어 미지원).

### 6.4. 문서 타입별 처리 상세

| 타입 | 파싱/변환 | LLM 전처리 | 중간 산출물 (data/converted) | 청킹 | 이미지 | MD 재인덱싱(↺) |
|------|-----------|-----------|------------------------------|------|--------|----------------|
| **PDF(스캔)** | `PagePdfDocumentReader` 페이지 단위. 50% 이상 페이지가 50자 미만이면 스캔 판정 → Tesseract(kor+eng) OCR (`source_type=ocr`). **MD 변환 없음** | 없음 | 없음 | 슬라이딩 윈도우(섹션 병합 없음) | 페이지 이미지 추출 → `data/images/{docId}/` | 미지원 |
| **PDF(비스캔)** | `PdfToMarkdownConverter` 로 MD 변환 (페이지별 `[페이지: N]` 마커만 — 합성 헤딩 없음, `[이미지: ...]` 인라인, 텍스트·이미지 모두 없는 페이지는 건너뜀) | `MarkdownCorrectionService.correct()` — 섹션 병렬 **포맷 교정** (DOCX·TXT 와 동일 파이프라인, `splitBySections()`가 `[페이지: N]`도 경계로 사용, 페이지/이미지 마커 보존) | `{docId}.md`(원본) + `{docId}_corrected.md`(교정) | **`[페이지: N]`(페이지) 섹션 우선 유지**, 초과 시 섹션 내부 슬라이딩 윈도우 — 단, 서로 다른 페이지끼리는 병합되지 않음(§6.3-bis) | `PdfImageExtractor`를 변환기가 직접 호출해 추출 + 본문에 `[이미지: ...]` 인라인(DOCX와 동일) | **지원** |
| **PPTX** | `PptxToMarkdownConverter` 로 MD 변환 (슬라이드별 `[페이지: N]` + 제목 있으면 제목 헤딩 `##`(제목 없으면 마커만) + `[이미지: ...]` 인라인, 본문 불릿은 중첩 목록만) | `MarkdownCorrectionService.correct()` — 섹션 병렬 **포맷 교정** (DOCX·TXT 와 동일 파이프라인이되, 섹션 분할만 `splitByPages()`로 `[페이지: N]` 단위 + 연속 슬라이드 최대 4장 묶음, 초대형 슬라이드는 `[도형 그룹]` 등 블록 경계로 분할 — §6.3-bis 2번) | `{docId}.md`(원본) + `{docId}_corrected.md`(교정) | **`[페이지: N]`(슬라이드) 섹션 우선 유지**, 초과 시 섹션 내부 슬라이딩 윈도우 — 단, 서로 다른 슬라이드끼리는 병합되지 않음(§6.3-bis) | `PptxImageExtractor`를 변환기가 직접 호출해 추출 + 본문에 `[이미지: ...]` 인라인(DOCX와 동일) | **지원** |
| **DOCX** | `DocxToMarkdownConverter` 로 MD 변환 (제목 스타일 → `##/###`, `[헤딩페이지: N]`/`[페이지: N]` + 이미지 `[이미지: ...]` 인라인) | `MarkdownCorrectionService.correct()` — 섹션 병렬 **포맷 교정**(끊긴 문장 연결·오타·헤딩 정규화, 내용 불변, 페이지/이미지 마커 보존) | `{docId}.md`(원본) + `{docId}_corrected.md`(교정) | **챕터(헤딩) 섹션 병합**(minChunkSize 기반 + 부모 브레드크럼, 표 아래 주석), 초과 시 섹션 내부 슬라이딩 윈도우 | 변환 단계에서 인라인 처리 | **지원** |
| **TXT** | 평문 → `TextToMarkdownService.convert()` — 로컬 LLM 이 **구조화**(제목/목록/표 부여) + **문법 교정**(맞춤법·띄어쓰기·끊긴 문장), 내용 불변 → MD | 위 구조화에 이어 `MarkdownCorrectionService.correct()` **포맷 교정** 한 번 더 (DOCX 와 동일 파이프라인) | `{docId}.md`(구조화) + `{docId}_corrected.md`(교정) | 챕터(헤딩) 섹션 병합(표 아래 주석), 초과 시 슬라이딩 윈도우 | 없음 | **지원** |
| **MD** | 이미지/링크 마커 전처리 후 `#` 헤딩 기준 섹션 분할 | 없음 | 없음 | 챕터(헤딩) 섹션 병합(표 아래 주석), 초과 시 슬라이딩 윈도우 | `[이미지: ...]` 마커 → image_paths | 미지원 |

> **포맷 교정 중 표 보호(`MarkdownCorrectionService.correctSection()`)**: DOCX/TXT/PPTX/PDF(비스캔) 공통 — GFM 표(`markTableRows()`로 탐지)는 프롬프트에 원문 그대로 실어 보내지 않는다. 예전에는 "표는 변경 금지" 지시문 하나에만 의존했는데, 로컬 모델이 셀 안의 `:`를 `|`로 바꾸는 등 표를 훼손하는 사례가 있었다. 지금은 표 블록을 `[TABLE_PLACEHOLDER_N]` 자리표시자로 치환해 보내고(다른 대괄호 마커처럼 그대로 보존하도록 지시), 응답에서 원문으로 복원한다. 자리표시자가 응답에서 사라지면(모델이 지웠거나 표 형식으로 채워 넣으려 함) 그 결과를 신뢰하지 않고 위치를 추측하는 대신 **그 섹션 전체를 교정 없이 원본 그대로 반환**한다 — 오버랩 경계 마커(`<<<SECTION_START/END>>>`)가 유실됐을 때 오버랩 없이 재교정하는 것과 같은 방어 원칙.

> **DOCX·TXT·MD 챕터 청킹(`ChunkSplitter.mergeSectionsByChapter`)**: 챕터(헤딩) 하나가 기본 청크 단위다. 섹션이 `MIN_CHUNK_SIZE`(정규화 길이) 미만일 때만 다음 섹션과 병합하되, 다음이 **상위(부모) 헤딩**(`#` 개수가 더 적음)이면 병합하지 않는다. 크기별로 ① 합이 `CHUNK_SIZE` 이내면 병합, ② 다음 섹션 단독이 `CHUNK_SIZE` 이내면 분리, ③ 다음이 `CHUNK_SIZE` 초과면 앞에 붙여 슬라이딩 분할했을 때 마지막 조각이 `MIN_CHUNK_SIZE`×1.5 이상일 때만 병합한다. 앞으로 못 붙인 작은 섹션은 **직전 청크로 뒤로 병합**된다(`backwardMergeShortChunks`). 또한 하위 챕터(`###` 이상, 즉 `##` 최상위가 아님) 청크의 **첫 조각 맨 앞**에는 바로 위 부모 헤딩 한 줄을 브레드크럼으로 덧붙여 문맥을 준다 — 슬라이딩으로 쪼개진 꼬리 조각은 자기 헤딩 `(N)`만 갖고 부모는 붙지 않는다. PPTX/PDF(비스캔)는 이 전략 대신 슬라이드/페이지 경계를 지키는 `mergeShortSections`를 쓴다(§6.3-bis 5번).  
> **PPTX 동일 헤딩 슬라이드 병합(`ChunkSplitter.mergeIdenticalHeadingSlides`, `mergeShortSections` 앞에 실행)**: 연속된 슬라이드의 `##`+`###` 헤딩이 **둘 다 존재하고 완전히 같으면**(정규화 비교 — 좌우 공백/내부 연속 공백 차이만 무시), 합쳤을 때 정규화 길이가 `CHUNK_SIZE` 이내인 동안 슬라이드 단위 경계(`page_or_slide` 불일치 금지 규칙)를 넘어 하나의 청크로 합친다. 단, 한 그룹당 최대 `MAX_IDENTICAL_HEADING_MERGE_SLIDES`(기본값 **2**)장까지만 합쳐진다 — 3장 이상 연속으로 헤딩이 같아도 앞 2장만 합치고, 그다음 슬라이드는 (헤딩이 같더라도) 새 그룹으로 다시 시작한다(예: 4장이 모두 같으면 2장씩 두 청크가 된다). 헤딩이 다르거나 합친 크기가 `CHUNK_SIZE`를 넘으면 캡에 도달하기 전이라도 그 자리에서 체인이 끊긴다. 두 번째 슬라이드부터는 중복된 `##`/`###` 헤딩 줄이 제거되지만, 그 자리에 `[페이지: N]` 마커를 삽입해 어느 슬라이드의 내용이 이어지는지 구분할 수 있게 한다. 병합된 청크의 `page_or_slide` 메타데이터는 (다른 병합 규칙들과 동일하게) 첫 슬라이드 것만 유지된다 — 두 번째 이후 슬라이드의 정확한 페이지는 본문에 남은 `[페이지: N]` 마커로만 확인 가능하다. PDF(비스캔)는 헤딩 자체를 만들지 않으므로 이 규칙이 적용될 일이 없다.  
> **DOCX·TXT·PPTX·PDF(비스캔)의 LLM 전처리는 graceful**: LLM 사용 불가 시 원본(변환 전) 텍스트를 그대로 사용해 인덱싱은 계속된다.  
> **TXT 구조화 LLM 호출**: `TaskType.LIGHT_TEXT` · `RoutingMode.COST_FIRST`(로컬 프로바이더 우선). 큰 파일은 6,000자 블록으로 나눠 병렬 처리하며, 병렬도는 다른 인덱싱 LLM 호출과 동일하게 `app.indexing.max-concurrent-llm-calls`(`INDEXING_MAX_LLM`)를 `convert()`마다 다시 읽어 적용한다.  
> **PPTX/PDF(비스캔)도 이제 이미지를 `[이미지: ...]` 인라인 마커로 넣으므로**(DOCX와 동일 방식), 업로드 화면의 "이미지 설명 추가"(`addImageDescriptions`) 체크박스가 이 두 포맷에도 정상 적용된다 — [IMAGE_PROCESS.md §5](IMAGE_PROCESS.md#5-vision-설명-생성-l2) 참고.  
> **MD 재인덱싱(↺)**: `data/converted/{docId}[_corrected].md` 가 존재하는 DOCX·TXT·PPTX·PDF(비스캔) 만 지원(`AdminController` `/admin/documents/{docId}/reindex`). 재변환/재교정 없이 저장된 MD 를 다시 청킹·임베딩한다. 태그는 FTS 인덱스에서 복원. 스캔 PDF는 MD 파일 자체가 없어 미지원.  
> **청킹/임베딩 단계 실패 시 재시도**: MD 변환+교정(4~6, 이미지 분석 포함)이 끝난 시점에 `doc_registry`에 `chunks=0`짜리 partial row가 먼저 저장된다(§6.3 6-bis). 이후 청킹·키워드추출·임베딩 저장(7~12) 중 어디서 실패해도 이 docId가 레지스트리·`/admin` 문서 목록에 남아 있어, 위 "MD 재인덱싱(↺)"으로 이미지 분석/MD 교정을 다시 거치지 않고 재시도할 수 있다 — 이 체크포인트가 없던 예전에는 실패 시 레지스트리에 아무것도 남지 않아 재업로드로 처음부터 다시 거쳐야 했다. `DocRegistry.existsBySha256AndVersion()`이 `chunks > 0`인 row만 "색인 완료"로 인정하므로, 이 partial row 때문에 `syncDirectory()`가 미완료 문서를 다음 동기화에서 영구히 건너뛰지는 않는다.  
> **존재하지 않는 이미지 마커 정리**: MD 로드 직후, `[이미지: path]`/`[이미지(변환불가): path]` 마커가 가리키는 파일을 `data/images/`에서 실제로 찾아본다 — 수동 정리·이동 등으로 파일이 사라졌다면(`DocumentIndexer.removeMissingImageMarkers()`) 해당 마커만 제거하고 그 결과를 `mdPath`(사용 중인 `[_corrected].md`)에 다시 저장한 뒤 청킹을 진행한다. 존재하는 마커는 그대로 유지되며, 모든 마커가 유효하면 파일을 다시 쓰지 않는다. 인라인 마커(문장 중간의 DOCX 이미지)와 단독 줄 마커(PPTX/PDF) 모두 마커 부분만 제거되고 주변 텍스트는 보존된다.  
> **소제목 번호 재검증**: 이미지 마커 정리 다음 단계로, 로드한 MD에 이미 번호 매겨진 헤딩(`## 1. 제목`처럼 숫자 프리픽스가 붙은 H2~H6)이 하나라도 있으면 현재 헤딩 구조를 기준으로 전체 번호를 다시 계산해 `mdPath`에 반영한다(`DocumentIndexer.reapplyHeadingNumbersIfNeeded()` → `MarkdownCorrectionService.reapplyHeadingNumbers()`, LLM 호출 없이 순수 텍스트 재계산만 수행) — 청크 편집으로 코드 블록이 분리/병합되는 등 헤딩이 추가·삭제·이동해 번호가 어긋난 경우를 바로잡는다. 번호 매겨진 헤딩이 하나도 없는 문서(체크박스를 끄고 업로드했거나, 위에서 언급한 대로 항상 번호가 붙지 않는 PPTX)는 손대지 않는다 — PPTX는 파일명 확장자로 먼저 걸러 이 단계 자체를 건너뛴다. 재계산 결과가 기존 내용과 같으면(즉 번호가 이미 최신 상태면) 파일을 다시 쓰지 않는다.  
> **마크다운 후처리 재적용**: 소제목 번호 재검증 다음 단계로, `postProcessMarkdown()`(§6.3 6번 ①~④ — `[DOCUMENT]` 마커/내용 없는 `-` 줄 제거, 코드 블록·GFM 표 앞뒤 빈 줄 보장, 펜스 밖 연속 빈 줄을 1개로 축소)을 `DocumentIndexer.postProcessIfNeeded()` → `MarkdownCorrectionService.postProcess(md, isPptx)`로 다시 실행하고 변경이 있으면 `mdPath`에 반영한다. `isPptx`는 파일 확장자로 다시 판별한다(`filename.toLowerCase().endsWith(".pptx")` — 업로드 시의 `groupByPage`와 같은 신호). LLM 호출 없이 결정적으로 동작하며 모든 형식에 적용되고, **PPTX면 §6.3 6번의 `applyPptxShapeFormatting()`(도형 그룹/이미지 앵커 빈 줄·중복 정리 5규칙)도 업로드 때와 동일하게 다시 실행된다** — 그래서 저장된 PPTX MD 파일을 손으로 편집한 뒤 재인덱싱해도 최초 업로드와 같은 서식 보정을 다시 받는다. **`fixClosingFences()`/`normalizeCodeBlocks()`는 재인덱싱에 포함하지 않는다** — 저장된 MD를 운영자가 직접 편집한 뒤 재인덱싱하면, 코드 블록 안에 의도적으로 남긴 빈 줄이 `normalizeCodeContent()`에 의해(함수/클래스·여러 줄 주석 시작 직전이 아니면 전부 삭제) 지워지거나, 펜스 짝이 어긋난 입력에서 여는 펜스의 언어 태그가 잘못 벗겨질 수 있어 — 이 위험을 매 재인덱싱마다 자동으로 감수하기보다 필요할 때만(문서 재업로드) 감수하도록 의도적으로 남겨둔 것이다.

### 6.5. 디렉터리 동기화 — 3단계

```
Phase 1  변경 감지 (단일 스레드)
  SHA-256 계산 → 레지스트리 비교
  → 신규/변경/삭제 파일 목록 확정

Phase 2  병렬 인덱싱 (Virtual Thread)
  최대 maxConcurrentFiles(기본 1)개 파일 동시 처리
  LLM 키워드 추출은 maxConcurrentLlmCalls(기본 3) Semaphore 제한(배치당 1회 획득, §10.8.2)
    — 이 세마포어는 syncDirectory()가 1개만 만들어 모든 파일이 공유한다(파일당 1개가 아님).
      반면 MD 교정/TXT 구조화는 호출마다 자기 세마포어를 만들므로 파일 병렬 시 곱으로 늘어난다
      → 인덱싱 LLM 동시 호출 피크 ≈ maxConcurrentFiles × maxConcurrentLlmCalls
  Phase 1에서 이미 계산한 SHA-256을 그대로 전달받아 재사용 — 파일을 다시 읽어 재해싱하지
  않음(§10.8.4)
  변경 파일: 신규 인덱싱 성공 후 구 버전 삭제 (실패 시 구 버전 보존)

Phase 3  삭제 처리 (단일 스레드)
  디렉터리에서 제거된 파일 → 벡터 스토어 + 레지스트리 제거
  레지스트리 저장은 Phase 3 완료 후 1회만 실행
  → SyncResult(indexed, updated, deleted) 반환
```

### 6.6. OCR 자동 감지

```
PDF 페이지의 50% 이상이 50자 미만  →  스캔 문서로 판정
  → Tesseract(kor+eng)로 재처리
  → source_type = "ocr"
  → 답변 시 OCR 경고 문구 표시
```

> OCR은 `app.image-description.ocr-enabled=true` 설정 시에만 활성화 (기본 활성 — application.properties에서 기본값 true).  
> 이미지 Vision 설명 생성: [IMAGE_PROCESS.md](IMAGE_PROCESS.md)

---

## 7. 관련 문서

| 문서 | 내용 |
|------|------|
| [LLM_ROUTING.md](LLM_ROUTING.md) | 라우팅 모드, 프로바이더 설정, 회로 차단기, 동시성 게이트+백프레셔 |
| [IMAGE_PROCESS.md](IMAGE_PROCESS.md) | 이미지 추출, OCR, Vision LLM 설명 생성 |
| [OPERATOR_MANUAL.md](OPERATOR_MANUAL.md) | 환경변수, 배포, 시나리오별 설정 예제 |
| [PLAN.md §10.10](PLAN.md) | 큐레이션 Q&A(좋아요 기반 지식 승격) 설계·구현 전체 기록 |
| [UI.md](UI.md) | 화면 구성, HTMX 엔드포인트 |
