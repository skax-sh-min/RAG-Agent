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
| **RETRIEVAL** | 쿼리 확장(조건부) → 1회 배치 임베딩 → 단일 Chroma 쿼리 → RRF 병합 → 선택적 LLM 리랭킹(opt-in). 재시도 시 후보 풀 ×(retry+1) 에스컬레이션 | ③ 쿼리 확장, [리랭킹 활성 시 1콜] |
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
> ③ (MultiQueryExpander)도 토큰 미누적.

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

### 진입점

| 방식 | 엔드포인트 | 내부 메서드 |
|------|-----------|-----------|
| 단일 업로드 | `POST /api/v1/documents` | `RagService.indexDocument()` |
| 디렉터리 동기화 | `POST /api/v1/documents/sync` | `RagService.syncDirectory()` |

### 단일 파일 인덱싱

```
파일 수신
  │
  ├─ SHA-256 해시 → docId 생성 (filename_해시앞8자)
  │
  ├─ 파일 타입별 파싱
  │    PDF   → 페이지 단위 (스캔 감지 시 OCR 자동 적용)
  │    PPTX  → 슬라이드 단위
  │    DOCX  → 제목 기준 섹션 단위 + 이미지 인라인 추출
  │    TXT/MD→ 섹션 분할
  │
  ├─ 이미지 추출
  │    PDF/PPTX → data/images/{docId}/ 에 별도 저장
  │    DOCX     → 파싱 단계에서 함께 처리
  │    → chunk 메타데이터 image_paths 에 경로 기록
  │
  ├─ 청킹
  │    PPTX    → 슬라이드 단위 유지 (분할 없음)
  │    DOCX/MD → 섹션 단위 유지, 초과 시 슬라이딩 윈도우
  │    PDF/TXT → 슬라이딩 윈도우 (chunkSize / chunkOverlap)
  │
  ├─ 메타데이터 태깅
  │    doc_id, filename, version, page_or_slide,
  │    source_type, sha256, collected_at, image_paths
  │
  ├─ 기존 청크 삭제 (재인덱싱 시 동일 docId 덮어쓰기)
  │
  ├─ 키워드 추출 LLM → excerpt_keywords 메타데이터 추가
  │
  ├─ ChromaDB 저장 (version별 컬렉션)
  │
  └─ 레지스트리 저장 (SQLite doc_registry 테이블 — memory.db 공유)
```

### 디렉터리 동기화 — 3단계

```
Phase 1  변경 감지 (단일 스레드)
  SHA-256 계산 → 레지스트리 비교
  → 신규/변경/삭제 파일 목록 확정

Phase 2  병렬 인덱싱 (Virtual Thread)
  최대 maxConcurrentFiles(기본 3)개 파일 동시 처리
  LLM 키워드 추출은 maxConcurrentLlmCalls(기본 4) Semaphore 제한
  변경 파일: 신규 인덱싱 성공 후 구 버전 삭제 (실패 시 구 버전 보존)

Phase 3  삭제 처리 (단일 스레드)
  디렉터리에서 제거된 파일 → ChromaDB + 레지스트리 제거
  레지스트리 저장은 Phase 3 완료 후 1회만 실행
  → SyncResult(indexed, updated, deleted) 반환
```

### OCR 자동 감지

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
| [LLM_ROUTING.md](LLM_ROUTING.md) | 라우팅 모드, 프로바이더 설정, 회로 차단기 |
| [IMAGE_PROCESS.md](IMAGE_PROCESS.md) | 이미지 추출, OCR, Vision LLM 설명 생성 |
| [OPERATOR_MANUAL.md](OPERATOR_MANUAL.md) | 환경변수, 배포, 시나리오별 설정 예제 |
| [UI.md](UI.md) | 화면 구성, HTMX 엔드포인트 |
