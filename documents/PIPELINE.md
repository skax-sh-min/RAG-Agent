# Query & Indexing Pipeline — 질의·인덱싱 처리 흐름

RAG Agent의 두 가지 핵심 흐름(질의응답, 문서 임포트)을 코드 레벨에서 기술.

---

## 목차

1. [질의응답 흐름](#1-질의응답-흐름)
2. [AgentGraph 노드](#2-agentgraph-노드)
3. [모드별 동작](#3-모드별-동작)
4. [LLM 호출 요약](#4-llm-호출-요약)
5. [재시도 조건](#5-재시도-조건)
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
              └─ 일반 질문 ──→ RETRIEVAL → ANSWER → CRITIC → FINALIZE
                                            ↑          │
                                            └──────────┘
                                         부족/미근거 시 재시도 (최대 N회)
```

`AgentState`(불변 레코드)를 각 노드가 받아 새 인스턴스를 반환하며 상태를 전파.

---

## 2. AgentGraph 노드

| 노드 | 역할 | LLM 호출 |
|------|------|---------|
| **CLASSIFIER** | 질문 유형 판별 (concept / usage / error / version / meta) | ① — AgentService에서 선실행하므로 그래프 내에서는 스킵 |
| **DIRECT_ANSWER** | meta 질문 직접 응답 (벡터 검색 없음) | ② |
| **RETRIEVAL** | 쿼리 확장(조건부 — 15자 미만 질의는 생략, `app.search-multiquery-min-length`) → 확장 LLM 호출과 원본 질의 벡터 검색을 가상 스레드로 병렬 실행(§10.8.1, 원본 검색 지연이 확장 대기 뒤에 숨음) → 배치 임베딩(쿼리 임베딩 캐시 히트 시 스킵) → 벡터 스토어 배치 쿼리(chroma 단일 호출, 결과에 쓰지 않는 임베딩 필드는 요청 자체를 생략 §10.9.1 / sqlite-vec 쿼리별) → 가중 RRF 병합(벡터축 그룹 정규화 + 키워드축 가중치) → 선택적 LLM 리랭킹(opt-in). 재시도 시 후보 풀 ×(retry+1) 에스컬레이션 | ③ 쿼리 확장(조건부), [리랭킹 활성 시 1콜] |
| **ANSWER** | 문서 기반 답변 생성 + 충분도 검사 | ④ 답변, ⑤ 충분도 |
| **CRITIC** | 답변이 문서에 근거하는지 검증 | ⑥ |
| **FINALIZE** | 대화 히스토리 저장 (SQLite) | 없음 |

### 노드 전환 규칙

| 조건 | 전환 |
|------|------|
| questionType == "meta" | CLASSIFIER → DIRECT_ANSWER |
| 그 외 | CLASSIFIER → RETRIEVAL |
| ANSWER sufficient=false, retryCount < max | ANSWER → RETRIEVAL |
| ANSWER isDualMode | ANSWER → FINALIZE (CRITIC 건너뜀) |
| CRITIC grounded=false, retryCount < max | CRITIC → RETRIEVAL |
| 그 외 | CRITIC → FINALIZE |

---

## 3. 모드별 동작

### COST_FIRST (기본)
```
분류 → 검색 → 답변 → 충분도 검사
              └─ 부족 시 재검색 (retryCount < max)
         → CRITIC → 종료
```

### PROGRESSIVE
```
COST_FIRST와 동일하되,
재시도 소진 후에도 답변 불충분 → PREMIUM 모델로 자동 업그레이드 후 재답변
```

### DUAL
```
검색 → LOCAL + 외부 모델 동시 답변 생성
     → 외부 답변 채택 (LOCAL 답변은 비교용으로 함께 반환)
     → CRITIC 없이 종료
```

### meta 질문
```
분류에서 "meta" 감지 → 검색 없이 직접 답변 → 종료
```

> 라우팅 모드 상세: [LLM_ROUTING.md](LLM_ROUTING.md)

---

## 4. LLM 호출 요약

| # | 위치 | 목적 | 토큰 누적 |
|---|------|------|---------|
| ① | AgentService (사전 병렬) | 질문 유형 분류 | 없음 |
| ② | DIRECT_ANSWER | meta 직접 응답 | ✓ |
| ③ | RETRIEVAL | 쿼리 다양화 | 없음 |
| ④ | ANSWER | 답변 생성 | ✓ |
| ⑤ | ANSWER | 충분도 평가 | ✓ |
| ⑥ | CRITIC | 근거 검증 | ✓ |
| ⑦ | ANSWER (PROGRESSIVE) | PREMIUM 재답변 | ✓ |

> ①은 `AgentState`에 누적되지 않아 `llmCallCount`가 실제보다 1 낮게 표시됨 — 허용된 tradeoff.  
> ③ (MultiQueryExpander)도 토큰 미누적. 15자 미만 질의는 생략되며(기본값), 실행될 때도 원본 질의 검색과 병렬로 진행되어(§10.8.1) 검색 전체 지연에 그대로 더해지지 않는다.

> **동시성 게이트**: ①~⑦ 모두 프로바이더별 동시성 게이트(`LlmRouter.executeGated()`, 서버의 실제 `--parallel` 값에 맞춘 `Semaphore`)를 거친다 — 여러 사용자의 질문이 겹쳐도 앱이 한 프로바이더에 동시 전송하는 요청 수는 이 한도를 넘지 않는다. 대기가 상한(기본 20초)을 넘으면 즉시 HTTP 429로 응답하고 재검색/재시도로 넘어가지 않는다. 문서 인덱싱의 LLM 호출(키워드 추출, MD 포맷 교정 등)은 이 게이트 대상이 아니며 기존 `INDEXING_MAX_LLM` 세마포어만 적용된다 — 상세는 [LLM_ROUTING.md §6](LLM_ROUTING.md#6-동시성-게이트--백프레셔) 참고.

> **태스크별 모델 분리(§6.21)**: ③ 쿼리 다양화와 인덱싱 잡무(키워드+맥락 추출·대화 요약·제목 생성)는 `TaskType.MICRO_TEXT`로 라우팅된다 — `type=MICRO_TEXT` 소형 프로바이더를 등록하면 이 추론 불필요 잡무만 500MB급 소형 모델로 오프로딩되고, 분류(①, `LIGHT_TEXT`)·직답(②)·답변(④)·근거검증(⑥) 등 품질 민감·고추론 호출은 큰 모델(`type=BOTH`)이 전담한다. 소형 미등록 시 큰 모델이 흡수(회귀 0). 상세는 [LLM_ROUTING.md §9](LLM_ROUTING.md).

### 4.1 `app.llm.max-tokens`(`LLM_MAX_TOKENS`) 크기 산정 — 로컬 LLM 컨텍스트 윈도우와의 관계

**`max_tokens`(completion 상한) ≠ 컨텍스트 윈도우(n_ctx, 입력+출력 합계).** `LLM_MAX_TOKENS`가 `OpenAiChatOptions.maxTokens()`로 들어가는 값은 LLM이 한 번에 생성할 수 있는 **출력** 토큰 상한일 뿐, 로컬 LLM 서버(예: llama-server)의 컨텍스트 크기(`--ctx-size`, 흔히 기본 8192)와는 별개다. 입력(system prompt + RAG 검색 결과 + 대화 히스토리 + 질문)이 이미 컨텍스트의 상당 부분을 차지하므로, `max_tokens`를 크게 잡아도 실제로 생성 가능한 토큰 수는 `n_ctx - 입력토큰수`로 물리적으로 제한된다 — 서버 구현에 따라 조용히 잘리거나, 입력이 이미 크면 "context length exceeded" 류의 에러가 난다. **컨텍스트 윈도우 자체는 로컬 서버 설정(`--ctx-size`)으로 조절 가능**하므로, 완성 상한을 늘리고 싶다면 `LLM_MAX_TOKENS`만 올리기보다 로컬 서버의 컨텍스트 크기를 함께(또는 우선) 늘리는 것이 근본적인 해법이다.

**스트리밍 답변 경로는 이 값 자체를 전송하지 않는다.** ④(ANSWER 답변 생성)와 ②(DIRECT_ANSWER)의 실제 사용자 대면 스트리밍 경로(`AnswerService`/`DirectAnswerService`가 `OpenAiApi.chatCompletionStream()`을 직접 호출하는 4-arg `ChatCompletionRequest(messages, model, temperature, stream)`, 또는 `ChatClient` 스트리밍 폴백)는 `maxTokens` 필드 자체가 없는 오버로드를 쓴다 — 즉 **사용자가 실제로 보는 채팅 답변 길이는 `LLM_MAX_TOKENS`와 무관**하며, 대신 SSE 타임아웃(`app.sse-idle-timeout-seconds`)이 폭주를 막는다. `LLM_MAX_TOKENS`가 실제로 completion 상한을 거는 곳은 **블로킹** LLM 호출뿐이다 — ①③⑤⑥⑦ 및 인덱싱 계열(분류·쿼리확장·충분도평가·근거검증·PROGRESSIVE 재답변·키워드추출·TXT구조화), Direct의 블로킹(비스트리밍) 모드.

**§6.18 이후, 이 값 하나가 서로 다른 3곳에 결합돼 있다** — `AppProperties.llmSafe().maxTokens()`를 공유하므로 하나를 올리면 셋이 함께 커진다:

| 소비처 | 공식 | 6000(기본) | 8000 | 12000 |
|---|---|---|---|---|
| 블로킹 LLM completion 상한 | `LLM_MAX_TOKENS` 그대로 | 6000 | 8000 | 12000 |
| 대화 히스토리 문자 예산(`MemoryService`) | `LLM_MAX_TOKENS × 0.75` | 4500자 | 6000자 | **9000자** |
| MD 교정 섹션 크기(`MarkdownCorrectionService`, §6.3 6번) | `(LLM_MAX_TOKENS-500)/2` | 2750자 | 3750자 | **5750자** |

MD 교정 한 번의 LLM 호출은 `섹션(입력) + 시스템 프롬프트/지시문 + 교정 결과(출력, 대체로 입력과 비슷한 크기)`가 전부 **같은 컨텍스트 윈도우 안**에 들어가야 한다. 한글은 토큰당 문자 수가 영어보다 적어(문자당 토큰 소모가 더 큼) 위 문자 수가 실제로는 상당한 토큰량이 되므로, `LLM_MAX_TOKENS=12000`(섹션 5750자)은 `n_ctx=8192`인 로컬 모델에서 컨텍스트 초과 위험이 실질적이다. 대화 히스토리 예산도 다음 답변 생성 프롬프트(RAG 검색 결과 + 질문 + 시스템 프롬프트까지 함께 얹힘)에 그대로 들어가므로 값을 올릴수록 컨텍스트 압박이 커진다.

**권장**: 답변 길이 자체를 늘리려는 목적이라면 `LLM_MAX_TOKENS`는 적합한 손잡이가 아니다(위 스트리밍 경로 설명 참고). 로컬 배포에서 이 값을 정할 때는 컨텍스트 윈도우(`--ctx-size`, 기본 8192지만 모델이 허용하면 늘릴 수 있음)를 기준으로 위 표의 세 소비처가 합쳐도 여유가 남도록 마진을 두고(예: `n_ctx`의 절반 이하), 값을 8000~12000 등으로 올리고 싶다면 `LLM_MAX_TOKENS`를 올리기 전에 로컬 서버의 `--ctx-size`부터 그만큼(또는 그 이상) 늘려야 실제로 여유가 생긴다. 클라우드 프로바이더(Gemini/OpenAI 등)는 컨텍스트가 훨씬 크므로 이 값이 병목이 되지 않지만, `LlmConfig`가 이 값을 모든 프로바이더의 `defaultOptions`에 동일하게 굽기 때문에(뷰 전용, 재기동 필요) 가장 좁은 컨텍스트(보통 LOCAL)를 기준으로 잡아야 안전하다.

---

## 5. 재시도 조건

```
ANSWER sufficient=false   AND retryCount < max  →  retryCount 증가 후 RETRIEVAL 재시도
CRITIC grounded=false     AND retryCount < max  →  retryCount 증가 후 RETRIEVAL 재시도

PROGRESSIVE 모드 AND sufficient=false AND retryCount >= max
  →  PREMIUM 모델(⑦)로 단발 업그레이드 후 CRITIC 진행
```

> `retryCount`는 최초 RETRIEVAL 진입 시 증가하지 않습니다.  
> ANSWER 또는 CRITIC이 재시도를 결정할 때만 증가합니다.  
> `MAX_RETRY_COUNT=2`(기본)이면 최대 **2회 재검색**이 허용됩니다.  
> 재검색 시 후보 풀이 `min(topK×(retryCount+1), topK×3)`로 확대되어(`SEARCH_RETRY_ESCALATE=true`) 동일 검색 반복을 피합니다.

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
  │            비스캔 시 PdfToMarkdownConverter 로 페이지별 [페이지: N] 마커 + 합성 헤딩("N페이지") +
  │            [이미지: ...] 인라인 마커 삽입 → LLM 포맷 교정 → 섹션 분할
  │    PPTX  → PptxToMarkdownConverter 로 슬라이드별 [페이지: N] 마커 + 제목 헤딩(##, 제목 없으면 "N번 슬라이드") +
  │            [이미지: ...] 인라인 마커 삽입 (본문 불릿은 들여쓰기 레벨만 중첩 목록으로 반영, 소제목으로 승격하지 않음)
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
  ├─ 청킹
  │    DOCX/TXT/MD/PPTX/PDF(비스캔) → 섹션(헤딩) 단위 유지, 초과 시 섹션 내부 슬라이딩 윈도우
  │      단, PPTX/PDF(비스캔)는 서로 다른 슬라이드/페이지(page_or_slide) 간 병합은 금지 —
  │      "청크 1개 = 슬라이드/페이지 1개 = 정확한 인용" 보장을 유지하기 위함
  │    PDF(스캔) → 슬라이딩 윈도우만 적용 (chunkSize / chunkOverlap, 섹션 병합 없음 — 기존 동작 그대로)
  │
  ├─ 메타데이터 태깅
  │    doc_id, filename, version, doc_type, sha256, collected_at,
  │    chunk_index, owner_id, visibility, tags(선택), page_or_slide,
  │    source_type, image_paths, heading(MD/DOCX/PPTX/PDF[비스캔] 섹션 제목)
  │
  ├─ 기존 청크 삭제 (재인덱싱 시 동일 docId 덮어쓰기)
  │
  ├─ 키워드+맥락 추출 LLM (§10.1 Contextual Retrieval — 청크 N개(기본 4)를 번호 매긴 프롬프트로
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
  └─ 레지스트리 저장 (SQLite doc_registry 테이블 — memory.db 공유)
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
       (maxSectionChars = max(500, (LLM_MAX_TOKENS-500)/2) → 기본 6,000토큰 기준 2,750자 —
        §6.18 이전에는 별도의 죽은 프로퍼티를 통해 기본값 8,000을 읽어 3,750자였음. 이제
        실제 LLM 응답 상한과 동일한 소스(app.llm.max-tokens)를 공유)
       — 펜스가 열려 있는 동안 초과가 감지되면 펜스는 자르지 않고, 펜스 시작 위치로 처리 분기:
         · 펜스가 이 섹션 안에서 MIN_SECTION_CHARS/2(250자) 이상 지난 뒤에 시작됐다면
           → 펜스 이전 내용까지만 즉시 flush하고, 펜스 전체(지금까지 쌓인 내용 포함)를
             통째로 다음 섹션으로 넘겨 그 섹션에서 계속 자라게 함
         · 펜스가 섹션 아주 초반(< 250자)에 시작됐다면 → 넘겨봤자 자투리 섹션만 남으므로
           넘기지 않고, 펜스가 닫힐 때까지 이 섹션에 그대로 누적(섹션이 한도를 넘긴 채로 flush됨)
    c) 문서 끝까지 펜스가 닫히지 않은 기형 입력은 안전하게 "```"를 붙여 마감
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
  - `addHeadingNumbers=true`(문서 업로드 화면 "소제목 숫자 생성" 체크박스)면 섹션별 병렬 교정이 끝나
    전체 MD가 재조립된 뒤 2차 패스(`secondPassHeadingAndCodePolish()`)로 H2~H6 헤딩에 계층적 번호를
    매기고(`addHierarchicalHeadingNumbers()`, 기존 번호 프리픽스는 먼저 제거 후 현재 헤딩 순서로
    재계산 — 그래서 아래 `reapplyHeadingNumbers()`로 재실행해도 매번 안전) 라벨 없는 코드 블록의
    언어 태그를 재추론한다(`normalizeCodeBlocks(md, true)`) — **PPTX는 체크박스 상태와 무관하게 이
    옵션을 항상 무시한다**(§6.3-bis 2번)
  - 교정본 MD: data/converted/{docId}_corrected.md
  - 이후 파이프라인은 교정본을 source로 사용

7) MD 섹션 로드
  DocumentLoaderService.loadFromMarkdown(sourceMd)
  - 섹션별 Document 생성
  - [헤딩페이지: N] 마커 파싱 후 섹션 메타데이터 heading_page/page_or_slide 로 저장
  - [페이지: N] 앵커를 파싱해 비헤딩 섹션의 page_or_slide 근사값으로 사용
  - 프롤로그(첫 헤딩 이전 구간)는 첫 헤딩의 [헤딩페이지: N]이 있으면 해당 값을 우선 상속
  - [이미지: ...] / [이미지(변환불가): ...] 마커 파싱
  - image_paths 메타데이터에 경로(쉼표 결합) 저장

8) 청킹
  splitDocuments()
  - DOCX는 섹션 유지 우선
  - 섹션이 chunkSize 초과 시 sliding window 분할

9) 메타데이터 태깅
  DocumentIndexer.tagMetadata()
  - doc_id, filename, version, doc_type, sha256, chunk_index, page_or_slide, tags, image_paths 등

10) 키워드+맥락 추출(enrich) [LLM] — §10.1 Contextual Retrieval
  KeywordExtractor.enrichParallel() — 청크를 app.indexing.keyword-batch-size(기본 4)개씩
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

13) 레지스트리 저장
  doc_registry에 docId/version/chunk수/spring_doc_ids 기록
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
     - **헤딩·건너뛰기**: 제목이 없지만 본문(불릿 등) 또는 이미지(`XSLFPictureShape`)가 있는 슬라이드는 `"{N}번 슬라이드"`로 대체 헤딩을 붙인다 — 이미지만 있고 텍스트가 없는 슬라이드까지 건너뛰면 그 슬라이드에 대응하는 섹션 자체가 사라져 추출된 이미지의 `[이미지: ...]` 마커를 심을 자리가 없어지므로(이미지가 고아가 됨) 이미지가 있으면 건너뛰지 않는다. **제목·본문·이미지가 모두 없는 슬라이드(완전 공백 구분 슬라이드 등)만 마커·헤딩·본문 전부 생략하고 통째로 건너뛴다** — 그렇지 않으면 "## 139번 슬라이드"처럼 내용 없는 헤딩만 있는 청크가 그대로 임베딩/검색 인덱스에 남아 노이즈가 된다. 건너뛴 슬라이드는 다음 슬라이드의 `[페이지: N]` 번호에 영향을 주지 않는다(실제 슬라이드 인덱스를 그대로 사용).
     - **본문 렌더링**: 본문 불릿은 들여쓰기 레벨(`XSLFTextParagraph.getIndentLevel()`)을 중첩 목록으로만 반영하고, 어떤 경우에도 소제목(`###` 이상)으로 승격하지 않는다 — 슬라이드 하나를 하나의 원자적 섹션으로 다루는 편이 PPTX의 실제 구조에 더 가깝고, 들여쓰기를 헤딩으로 승격하면 평범한 한 줄짜리 불릿 목록도 소제목이 되어 메타데이터가 산만해질 위험이 있기 때문(검토된 대안 및 채택 근거는 구현 당시 논의 참고). `FOOTER`/`SLIDE_NUMBER`/`DATETIME` placeholder는 매 슬라이드에 반복되는 노이즈(예: "대외비" 문구)라 본문에서 완전히 제외한다. 불릿은 자동 번호 목록(`getAutoNumberingScheme() != null`)이면 `"1. "`, 일반 불릿이면 `"- "`로 렌더링해 DOCX 변환기와 동일하게 순서형/비순서형을 구분한다. 하이퍼링크가 걸린 run은 `XSLFTextRun#getHyperlink()`를 읽어 `[텍스트](URL)`로 렌더링한다.
     - **그래픽 프레임(표/SmartArt/OLE/차트)**: `XSLFTable`·`XSLFDiagram`(SmartArt)·`XSLFObjectShape`(OLE)·차트 프레임은 모두 `XSLFGraphicFrame` 변형으로, 일반 `XSLFTextShape` 순회로는 절대 잡히지 않아 별도 분기로 처리한다 — SmartArt는 `getGroupShape()`(실제 렌더링된 도형 레이어)를 그룹 도형과 동일하게 재귀 추출해 박스 라벨 텍스트를 `appendShapeGroup()`이 `[다이어그램] ... [/다이어그램]` 마커로 감싸 본문에 남기고(일반 그룹 도형도 동일 함수로 `[도형 그룹] ... [/도형 그룹]`으로 감싸짐 — 아래 7번 참고), 차트는 시리즈/축 값 추출이 차트 종류마다 달라 안정적으로 뽑기 어려우므로 제목 텍스트만 `[차트: 제목]` 인라인 라벨로 추출하며, OLE는 POI로 일반화해 파싱할 텍스트가 없어 본문에는 아무것도 남기지 않는다(미리보기 이미지는 아래 4번 참고).
     - **마커 규칙**: 이 세 마커는 같은 도형에서 나온 여러 라벨을 한 블록으로 묶고 "도형에서 추출된 텍스트"임을 표시하기 위한 것으로, `[이미지: ...]`와 동일하게 `Document.getText()`에 그대로 남아 임베딩/FTS·`/admin` 표시·답변 프롬프트에 반영된다 — `#`가 아니라 `[`로 시작해 `splitMarkdownBySections()`의 섹션 경계로 오인되지 않고, 텍스트가 하나도 없는 도형(순수 장식용 그룹 등)은 마커 자체를 생략해 빈 블록을 남기지 않는다. 한 슬라이드에 같은 종류(그룹/다이어그램/차트)가 2개 이상이면(`slide.getShapes()` 기준 개수) 라벨에 발견 순서대로 순번이 붙어(`[도형 그룹 1]`/`[도형 그룹 2]`, `[다이어그램 1]`, `[차트 1: 제목]`) 서로 구분되고, 1개뿐이면 기존과 동일하게 번호 없이 렌더링된다(기존 단일-도형 출력과의 하위 호환). 그 도형이 소유한 이미지(아래 4번의 owner 추적)는 슬라이드 상단이 아니라 해당 마커 블록의 여는 마커 바로 다음에 `[이미지: ...]`로 인라인 삽입되어 어떤 이미지가 어떤 도형/차트에서 나왔는지 드러난다 — 소유 도형이 없는 일반 사진은 기존과 동일하게 슬라이드 상단에 모아 표시된다(§6.3-bis 4번 참고로, `PptxToMarkdownConverter` 클래스 상단 주석의 "이미지 마커는 항상 상단에 hoist" 설명은 그룹/다이어그램/차트가 소유하지 않은 이미지에만 해당하도록 갱신됨).
     - **중복 제거**: 그룹 내부에서는 서로 다른 도형의 텍스트(문단을 합친 전체 텍스트, `combineShapeText()`)가 강조 마커·공백 차이를 무시하고 완전히 같으면 그 도형을 통째로 스킵해 하나만 남기고(`appendGroupText()`가 그룹 하나당 독립된 판정 범위를 가짐 — 중첩 서브그룹까지 포함해 공유), 슬라이드 본문에서도 직전 줄과 내용이 같은 줄이 연속되면(같은 기준으로 정규화 비교) 하나만 남긴다(비연속 반복은 유지).
     - **과도한 볼드 억제**: 슬라이드 하나의 최종 조립된 본문(본문+표+그룹 텍스트가 모두 합쳐진 뒤, 표 6번의 줄바꿈 수정도 반영된 뒤) 볼드(`**`/`***`) 스팬이 10개 이상이면 과도한 강조로 보고 전부 제거한다(`EXCESSIVE_BOLD_THRESHOLD`, 이탤릭은 대상 아님) — 슬라이드 전체가 볼드로 서식된 경우 등에 대응. 이와 별개로 도형 그룹(`appendShapeGroup()`)·표(`appendTable()`) 하나만 놓고도 같은 판정을 한 번 더 적용한다 — 그 블록 안의 볼드 스팬이 `BLOCK_BOLD_COUNT_THRESHOLD`(6)개 이상이거나 볼드로 덮인 글자 비율이 `BLOCK_BOLD_RATIO_THRESHOLD`(50%) 이상이면 그 블록만 볼드 마커를 전부 제거한다 — 볼드가 도형 그룹/표 하나에만 몰려 있어 슬라이드 전체 개수는 10 미만인 경우(예: 표 셀 6개만 전부 볼드)를 놓치지 않기 위한 블록 단위 보완 규칙이다.
     - **소제목 번호 매기기 강제 해제**: `addHeadingNumbers`(소제목 숫자 생성) 옵션은 체크박스 상태와 무관하게 PPTX 인덱싱 경로에서 항상 `false`로 강제된다(`DocumentIndexer`의 `.pptx` 분기가 `correctionService.correct()` 호출 시 요청값을 무시하고 고정값을 넘김) — PPTX의 `##`/`###` 헤딩은 슬라이드 제목/부제목 라벨(위에서 설명한 최대 2단계 calibration)이지 문서 목차 같은 계층 구조가 아니라서, 순차적으로 번호를 매겨도 실제 구조와 무관한 숫자만 붙고 이미 있는 `[페이지: N]` 마커와도 겹쳐 혼란만 준다.
     - **섹션 분할**: 같은 이유로 `MarkdownCorrectionService.correct()` 호출 시 섹션 분할 방식도 DOCX와 다르다: PPTX 인덱싱 경로는 `groupByPage=true`를 넘겨, 일반 헤딩 기준 분할(`splitBySections()`, §6.3 참고) 대신 `[페이지: N]` 마커를 경계로 쓰는 `splitByPages()`가 적용된다 — 슬라이드 하나가 `##`+`###` 헤딩을 모두 가진 경우에도 `###`가 별도 분할 트리거가 되지 않고, `[페이지: N]` 마커도 자기 슬라이드 섹션의 맨 앞에 온다(헤딩 기준 분할 시 이전 섹션 꼬리에 잘못 붙던 문제 해결). 다만 "슬라이드 하나 = 교정 호출 하나"는 아니다: 슬라이드는 자족적이라 페이지 경계가 항상 깔끔하므로, `splitByPages()`는 연속된 슬라이드를 문자 예산(`maxSectionChars`) 안에서 최대 `PPTX_MAX_BUNDLE_PAGES`(4)장까지 하나의 교정 호출로 묶는다 — 작은 슬라이드가 많을 때 LLM 왕복 횟수를 크게 줄인다. 반대로 슬라이드 하나가 `maxSectionChars`를 넘으면 묶을 수 없으므로 그 슬라이드만 `[도형 그룹]`/`[다이어그램]`/`[차트]` 블록 경계(`splitOversizedPage()`)로 쪼갠다(그룹 블록 하나는 통째로 유지, 블록 하나가 그래도 크면 문자 예산으로 강제 분할). 슬라이드 경계는 언제나 깔끔하므로 PPTX 경로에는 §6.3의 "부자연 경계 오버랩"이 쓰이지 않는다. DOCX·TXT·MD·PDF(비스캔)는 기존과 동일하게 `groupByPage=false`(일반 헤딩 기준 분할).
   - **PDF(비스캔)** — 페이지 텍스트만으로는 신뢰할 구조 신호가 없으므로 페이지마다 합성 헤딩 `"## N페이지"`만 부여한다(제목·소제목 추론 없음). 텍스트도 이미지도 없는 페이지만 마커·헤딩 모두 생략하고 건너뛰되(텍스트는 없어도 이미지가 있으면 PPTX와 동일한 이유로 건너뛰지 않음), 다음 페이지 번호는 밀리지 않고 실제 PDF 페이지 인덱스를 그대로 유지한다.
3. **페이지/슬라이드 마커**: 항상 제네릭 `[페이지: N]` 마커만 사용한다(DOCX 전용의 `[헤딩페이지: N]`은 쓰지 않음) — 실제로 내용을 내보내는 슬라이드/페이지의 헤딩 직전에 위치시켜 `splitMarkdownBySections()`가 그 헤딩의 `page_or_slide`로 정확히 귀속시킨다. **내용이 있는 슬라이드/페이지는 반드시 헤딩 하나씩을 가져야 하는 이유**: 헤딩이 없으면 섹션 경계가 전혀 생기지 않아 문서 전체가 헤딩 없는 섹션 1개로 뭉쳐버리고, 두 번째 슬라이드/페이지부터는 `page_or_slide` 값이 유실된다(완전히 비어 있는 슬라이드/페이지는 애초에 아무것도 내보내지 않고 건너뛴다 — 2번 항목 참고).
4. **이미지**: DOCX와 동일하게 본문에 `[이미지: ...]` 인라인 마커를 넣는다 — 헤딩 바로 다음(본문 텍스트보다 앞)에 슬라이드/페이지별 이미지 경로를 마커로 삽입한다. 별도의 사후 메타데이터 첨부 단계는 없다 — `DocumentLoaderService.loadFromMarkdown()`이 이미 갖고 있던 `[이미지: ...]` 마커 파싱 로직이 이 마커도 그대로 인식해 `image_paths`로 승격시킨다(DOCX와 완전히 동일한 메커니즘 재사용). 이 덕분에 `addImageDescriptions`(이미지 설명 추가) 옵션도 이제 PPTX/PDF에 정상 적용된다 — [IMAGE_PROCESS.md §5](IMAGE_PROCESS.md#5-vision-설명-생성-l2) 참고.
   - **PPTX 전용 — 그리기 도구 도형 래스터라이즈 (`app.pptx-image.rasterize-shapes`, 기본 `false`)**: `PptxImageExtractor`는 실제 삽입 이미지(`XSLFPictureShape`)뿐 아니라, 텍스트 도형 순회에서 잡히지 않는 "그리기 도구" 요소도 PNG로 래스터라이즈할 수 있다 — 그룹 도형(`XSLFGroupShape`), 독립 커넥터(`XSLFConnectorShape`, 화살표/선), 텍스트 없는 일반/자유형 도형이 "시드"가 된다. **`rasterize-shapes=true`일 때만** 아무 앵커(사진/표/그룹)에도 안 겹친 "느슨한" 시드들끼리 각 바운딩박스를 `app.pptx-image.cluster-proximity-padding-pt`(기본 15pt)만큼 바깥으로 부풀린 뒤 교차 여부로 연결 요소를 구하는 union-find 클러스터링을 적용해 다이어그램 한 장으로 묶는다 — 커넥터는 보통 두 도형이 겹치지 않는 "틈"에 놓이므로 순수 bbox 교차만으로는 다이어그램을 못 묶기 때문. **`rasterize-shapes=false`(기본)이면 이 느슨한-도형 클러스터링을 하지 않는다** — 겹친 느슨한 도형이 한 덩어리로 뭉치지 않고, 아무것에도 안 겹친 단독 도형은 이미지로 아예 안 뽑힌다. 단 아래 앵커 기반 합성(그룹·SmartArt 각 한 장 / 표+겹친도형 / 사진+주석)은 이 플래그와 무관하게 항상 유지된다. 시드가 하나도 없는 클러스터(텍스트 도형끼리만 우연히 근접한 경우)는 다이어그램이 아니므로 버린다. 텍스트가 있는 도형(텍스트 상자 포함)은 시드 근처에 있을 때만 함께 묶이는 승객으로 참여하고 — 이때도 그 텍스트는 `PptxToMarkdownConverter`가 `[도형 그룹] ... [/도형 그룹]` 마커로 감싸 별도로 본문에 추출해 Vision 미사용 환경에서도 검색 가능하다(그룹 내부 텍스트는 `appendGroupText()`가 재귀적으로 추출하고 `appendShapeGroup()`이 그 결과를 마커로 감쌈) — 시드 없이 혼자 있으면 절대 래스터라이즈되지 않는다. 순수 텍스트 상자는 비어 있으면 대상에서 제외한다. 가로/세로 중 큰 쪽이 `app.pptx-image.min-shape-dimension-pt`(기본 30pt) 미만인 도형은 아이콘/구분선으로 보고 시드가 될 수 없다 — 두 값 모두 `AppProperties.pptxImageSafe()`로 설정 가능(패딩을 넓히면 더 먼 도형까지 묶이고, 임계값을 높이면 더 큰 도형도 아이콘 취급되어 제외된다). 클러스터가 25개 도형을 넘으면(너무 어수선한 슬라이드) 번들 대신 시드만 개별 래스터라이즈하는 것으로 폴백한다. 렌더링은 클러스터 전체를 감싸는 바운딩박스를 캔버스로 잡고 좌표축을 한 번만 이동/확대한 뒤 각 도형을 원래 순서(z-order)대로 그리는 방식(`DrawFactory`)이며, 실패는 EMF/WMF 변환과 동일하게 조용히 건너뛴다.
   - **PPTX 전용 — 이미지-도형 상관관계(owner 추적)**: `PptxImageExtractor.extractWithOwners()`는 추출/래스터라이즈된 이미지마다 그 이미지를 만든 최상위 도형의 `slide.getShapes()` 인덱스(0-based, z-order — 클러스터링에 쓰이는 것과 동일한 인덱스 공간)를 `ExtractedImage.ownerShapeIndices()`로 태깅해 반환한다. 일반 그룹은 자기 자신의 인덱스, SmartArt는 (클러스터링에 실제로 투입되는 내부 `getGroupShape()` 렌더 도형이 아니라) `slide.getShapes()`에 나타나는 바깥쪽 `XSLFDiagram` 프레임 자신의 인덱스, 차트 fallback 그림은 그 차트 프레임의 인덱스를 owner로 갖는다 — 그룹/다이어그램/차트가 아닌 커넥터·자유형 도형·사진 등은 owner가 없다(빈 Set). `PptxToMarkdownConverter`는 `inReadingOrder()`로 재정렬하기 전의 원본 `slide.getShapes()`로 동일한 인덱스 공간을 독립적으로 계산해 도형별 소유 이미지를 찾고, 위 2번 항목처럼 해당 마커 블록 안에 인라인으로 배치한다(그렇게 소비된 이미지는 상단 hoist 목록에서 제외된다). 드물게 인접한 두 그룹의 패딩된 바운딩박스가 겹쳐 하나의 클러스터로 합쳐지면 그 이미지의 owner가 2개 이상이 되어 두 그룹 블록 모두에 동일한 이미지 마커가 나타날 수 있다(의도된 동작 — 실제로 두 그룹이 하나의 이미지로 합쳐졌다는 사실을 그대로 반영). 기존 `extract()`/`extract(XMLSlideShow, ...)` API(경로 문자열 리스트만 반환)는 하위 호환을 위해 그대로 유지되며, 내부적으로 `extractWithOwners()`를 감싸 owner 정보만 제거한다.
   - **PPTX 전용 — 그래픽 프레임 변형(SmartArt·차트·OLE) 이미지 처리**: `XSLFTable`을 제외한 `XSLFGraphicFrame` 변형은 POI가 "라이브"로 그릴 수 없어(`DrawGraphicalFrame`은 내부적으로 프레임의 `mc:Fallback` 미리보기 그림만 그리고, 없으면 아무것도 그리지 않음) 그리기 도구 래스터라이즈와는 다른 경로를 탄다. **OLE 객체**(`XSLFObjectShape`)는 OOXML 스펙상 항상 자체 미리보기 그림을 내장하므로(`getPictureData()`) 일반 픽처와 동일하게 그대로 저장한다. **SmartArt**(`XSLFDiagram`)는 프레임 자체가 아니라 `getGroupShape()`(실제 렌더링된 박스/커넥터 도형 레이어)를 그룹 도형과 동일한 근접 클러스터링 파이프라인의 시드 하나로 투입해 래스터라이즈한다 — 이 그룹은 진짜 도형들로 구성돼 있어 POI가 정상적으로 그릴 수 있다. **차트**는 POI에 라이브 렌더링 경로가 전혀 없어 `getFallbackPicture()`(PowerPoint가 하위 호환용으로 남겨둔 `mc:Fallback` 미리보기)가 있을 때만 그대로 저장하고, 없으면 조용히 건너뛴다(제목 텍스트는 위 2번 항목처럼 본문에 남는다).
5. **청킹**: DOCX/TXT/MD와 같은 섹션 병합(`mergeShortSections`) 전략을 타지만, 서로 다른 `page_or_slide`를 가진 인접 섹션끼리는 병합을 금지한다(`ChunkSplitter.isMergeForbiddenByPageMismatch()`, 기존 헤딩-레벨-점프 금지 규칙과 나란히 적용) — "청크 1개 = 슬라이드/페이지 1개 = 정확한 인용" 보장을 DOCX보다 더 엄격하게 유지한다. 값이 없는 DOCX/TXT/MD는 항상 no-op.
6. **표(테이블)**: PPTX의 `XSLFTable`은 나타나는 위치에 마크다운 파이프 표로 변환된다(`PptxToMarkdownConverter.appendTable()`) — DOCX와 달리 PPTX 표 모델은 병합된 셀도 행의 셀 목록에서 빠지지 않고 그대로 남아 각 행이 항상 같은 셀 수를 가지므로, DOCX처럼 gridSpan 기반으로 셀 목록을 재구성할 필요 없이 병합 연속 셀(`XSLFTableCell.isMerged()`)만 빈 칸으로 렌더링하면 된다. **표 위에 겹친 시드 도형(강조 원·화살표 등)이 있으면**, 위 MD 변환과 별개로 `PptxImageExtractor`가 표+도형을 하나의 합성 PNG로도 만든다(`rasterize-shapes`와 무관하게 항상 — 표는 이미지 추출 시 앵커로 취급, `DrawFactory`가 `DrawTableShape`로 표를 렌더링) — 표 셀을 짚는 markup의 시각 맥락을 보존하기 위함. 겹친 시드 도형이 없는 표는 이미지로 만들지 않는다(MD 파이프 표로만). 셀 안에 `<a:br/>`(Shift+Enter) 줄바꿈이 있으면 POI `XSLFTextRun.getRawText()`가 그 자리에 리터럴 `"\n"`을 반환해 파이프 표 행이 여러 줄로 쪼개지며 마크다운이 깨질 수 있었는데, `tableCellText()`가 셀 텍스트를 조립할 때 그 줄바꿈(및 주변 공백)을 공백 하나로 치환해 항상 한 줄로 유지되도록 수정됐다 — 이 수정은 최상위 표와 그룹 내부 표(`appendGroupText()`가 호출하는 `appendTable()`) 모두에 적용된다.
7. **스캔 판정**: `DocumentLoaderService.loadPdfPagesForConversion()`이 페이지 텍스트 추출과 스캔 판정(§6.6, 빈 페이지 50% 초과)을 함께 반환해, 스캔 PDF는 기존 `ocrWithPdfRenderer()` OCR 경로로, 비스캔 PDF는 위 MD 변환 경로로 분기한다 — 스캔 판정 로직 자체(임계값·휴리스틱)는 변경되지 않았다.
8. **MD 재인덱싱(↺)**: 위 변환기들도 `converted/{docId}.md`(+`_corrected.md`)를 남기므로 PPTX·비스캔 PDF도 DOCX·TXT와 동일하게 `/admin` 재인덱싱을 지원한다(스캔 PDF는 MD 파일이 없어 미지원).

### 6.4. 문서 타입별 처리 상세

| 타입 | 파싱/변환 | LLM 전처리 | 중간 산출물 (data/converted) | 청킹 | 이미지 | MD 재인덱싱(↺) |
|------|-----------|-----------|------------------------------|------|--------|----------------|
| **PDF(스캔)** | `PagePdfDocumentReader` 페이지 단위. 50% 이상 페이지가 50자 미만이면 스캔 판정 → Tesseract(kor+eng) OCR (`source_type=ocr`). **MD 변환 없음** | 없음 | 없음 | 슬라이딩 윈도우(섹션 병합 없음) | 페이지 이미지 추출 → `data/images/{docId}/` | 미지원 |
| **PDF(비스캔)** | `PdfToMarkdownConverter` 로 MD 변환 (페이지별 `[페이지: N]` + 합성 헤딩 `## N페이지` + `[이미지: ...]` 인라인, 텍스트·이미지 모두 없는 페이지는 건너뜀) | `MarkdownCorrectionService.correct()` — 섹션 병렬 **포맷 교정** (DOCX·TXT 와 동일 파이프라인, 페이지/이미지 마커 보존) | `{docId}.md`(원본) + `{docId}_corrected.md`(교정) | **헤딩(페이지) 섹션 우선 유지**, 초과 시 섹션 내부 슬라이딩 윈도우 — 단, 서로 다른 페이지끼리는 병합되지 않음(§6.3-bis) | `PdfImageExtractor`를 변환기가 직접 호출해 추출 + 본문에 `[이미지: ...]` 인라인(DOCX와 동일) | **지원** |
| **PPTX** | `PptxToMarkdownConverter` 로 MD 변환 (슬라이드별 `[페이지: N]` + 제목 헤딩 `##` + `[이미지: ...]` 인라인, 본문 불릿은 중첩 목록만) | `MarkdownCorrectionService.correct()` — 섹션 병렬 **포맷 교정** (DOCX·TXT 와 동일 파이프라인이되, 섹션 분할만 `splitByPages()`로 `[페이지: N]` 단위 + 연속 슬라이드 최대 4장 묶음, 초대형 슬라이드는 `[도형 그룹]` 등 블록 경계로 분할 — §6.3-bis 2번) | `{docId}.md`(원본) + `{docId}_corrected.md`(교정) | **헤딩(슬라이드) 섹션 우선 유지**, 초과 시 섹션 내부 슬라이딩 윈도우 — 단, 서로 다른 슬라이드끼리는 병합되지 않음(§6.3-bis) | `PptxImageExtractor`를 변환기가 직접 호출해 추출 + 본문에 `[이미지: ...]` 인라인(DOCX와 동일) | **지원** |
| **DOCX** | `DocxToMarkdownConverter` 로 MD 변환 (제목 스타일 → `##/###`, `[헤딩페이지: N]`/`[페이지: N]` + 이미지 `[이미지: ...]` 인라인) | `MarkdownCorrectionService.correct()` — 섹션 병렬 **포맷 교정**(끊긴 문장 연결·오타·헤딩 정규화, 내용 불변, 페이지/이미지 마커 보존) | `{docId}.md`(원본) + `{docId}_corrected.md`(교정) | **헤딩 섹션 우선 유지**, 초과 시 섹션 내부 슬라이딩 윈도우 | 변환 단계에서 인라인 처리 | **지원** |
| **TXT** | 평문 → `TextToMarkdownService.convert()` — 로컬 LLM 이 **구조화**(제목/목록/표 부여) + **문법 교정**(맞춤법·띄어쓰기·끊긴 문장), 내용 불변 → MD | 위 구조화에 이어 `MarkdownCorrectionService.correct()` **포맷 교정** 한 번 더 (DOCX 와 동일 파이프라인) | `{docId}.md`(구조화) + `{docId}_corrected.md`(교정) | 섹션 단위, 초과 시 슬라이딩 윈도우 | 없음 | **지원** |
| **MD** | 이미지/링크 마커 전처리 후 `#` 헤딩 기준 섹션 분할 | 없음 | 없음 | 섹션 단위, 초과 시 슬라이딩 윈도우 | `[이미지: ...]` 마커 → image_paths | 미지원 |

> **DOCX·TXT·PPTX·PDF(비스캔)의 LLM 전처리는 graceful**: LLM 사용 불가 시 원본(변환 전) 텍스트를 그대로 사용해 인덱싱은 계속된다.  
> **TXT 구조화 LLM 호출**: `TaskType.LIGHT_TEXT` · `RoutingMode.COST_FIRST`(로컬 프로바이더 우선). 큰 파일은 6,000자 블록으로 나눠 병렬 처리하며, 병렬도는 다른 인덱싱 LLM 호출과 동일하게 `app.indexing.max-concurrent-llm-calls`(`INDEXING_MAX_LLM`)를 `convert()`마다 다시 읽어 적용한다.  
> **PPTX/PDF(비스캔)도 이제 이미지를 `[이미지: ...]` 인라인 마커로 넣으므로**(DOCX와 동일 방식), 업로드 화면의 "이미지 설명 추가"(`addImageDescriptions`) 체크박스가 이 두 포맷에도 정상 적용된다 — [IMAGE_PROCESS.md §5](IMAGE_PROCESS.md#5-vision-설명-생성-l2) 참고.  
> **MD 재인덱싱(↺)**: `data/converted/{docId}[_corrected].md` 가 존재하는 DOCX·TXT·PPTX·PDF(비스캔) 만 지원(`AdminController` `/admin/documents/{docId}/reindex`). 재변환/재교정 없이 저장된 MD 를 다시 청킹·임베딩한다. 태그는 FTS 인덱스에서 복원. 스캔 PDF는 MD 파일 자체가 없어 미지원.  
> **존재하지 않는 이미지 마커 정리**: MD 로드 직후, `[이미지: path]`/`[이미지(변환불가): path]` 마커가 가리키는 파일을 `data/images/`에서 실제로 찾아본다 — 수동 정리·이동 등으로 파일이 사라졌다면(`DocumentIndexer.removeMissingImageMarkers()`) 해당 마커만 제거하고 그 결과를 `mdPath`(사용 중인 `[_corrected].md`)에 다시 저장한 뒤 청킹을 진행한다. 존재하는 마커는 그대로 유지되며, 모든 마커가 유효하면 파일을 다시 쓰지 않는다. 인라인 마커(문장 중간의 DOCX 이미지)와 단독 줄 마커(PPTX/PDF) 모두 마커 부분만 제거되고 주변 텍스트는 보존된다.  
> **소제목 번호 재검증**: 이미지 마커 정리 다음 단계로, 로드한 MD에 이미 번호 매겨진 헤딩(`## 1. 제목`처럼 숫자 프리픽스가 붙은 H2~H6)이 하나라도 있으면 현재 헤딩 구조를 기준으로 전체 번호를 다시 계산해 `mdPath`에 반영한다(`DocumentIndexer.reapplyHeadingNumbersIfNeeded()` → `MarkdownCorrectionService.reapplyHeadingNumbers()`, LLM 호출 없이 순수 텍스트 재계산만 수행) — 청크 편집으로 코드 블록이 분리/병합되는 등 헤딩이 추가·삭제·이동해 번호가 어긋난 경우를 바로잡는다. 번호 매겨진 헤딩이 하나도 없는 문서(체크박스를 끄고 업로드했거나, 위에서 언급한 대로 항상 번호가 붙지 않는 PPTX)는 손대지 않는다 — PPTX는 파일명 확장자로 먼저 걸러 이 단계 자체를 건너뛴다. 재계산 결과가 기존 내용과 같으면(즉 번호가 이미 최신 상태면) 파일을 다시 쓰지 않는다.

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
| [UI.md](UI.md) | 화면 구성, HTMX 엔드포인트 |
