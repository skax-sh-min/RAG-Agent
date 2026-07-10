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
| **RETRIEVAL** | 쿼리 확장(조건부) → 1회 배치 임베딩(쿼리 임베딩 캐시 히트 시 스킵) → 벡터 스토어 배치 쿼리(chroma 단일 호출 / sqlite-vec 쿼리별) → 가중 RRF 병합(벡터축 그룹 정규화 + 키워드축 가중치) → 선택적 LLM 리랭킹(opt-in). 재시도 시 후보 풀 ×(retry+1) 에스컬레이션 | ③ 쿼리 확장, [리랭킹 활성 시 1콜] |
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
  ├─ 키워드+맥락 추출 LLM (한 번의 호출, §10.1 Contextual Retrieval)
  │    → excerpt_keywords 메타데이터 추가
  │    → chunk_context 메타데이터 추가 ("{파일명} > {heading}" 구조적 맥락 + LLM 1~2문장,
  │      LLM 실패 시 구조적 맥락만 — 임베딩/FTS 입력 전용, 영속 저장 안 함)
  │
  ├─ 임베딩 입력 구성 = chunk_context + 정규화(원문) (§10.1-보완 — 마크다운 장식 제거)
  │    저장·표시 텍스트(원문)는 그대로 유지, 임베딩/FTS 입력에만 반영
  │
  ├─ 벡터 스토어 저장 (version별 — chroma 컬렉션 / sqlite-vec partition, content는 원문)
  │    + FTS 인덱스(chunk_fts)에도 동일 맥락+정규화 텍스트 반영 (Contextual BM25 시너지)
  │
  └─ 레지스트리 저장 (SQLite doc_registry 테이블 — memory.db 공유)
```

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

  5) 변환 산출물 저장
    - 원본 MD: data/converted/{docId}.md

  6) Markdown 교정 [LLM]
    MarkdownCorrectionService.correct()
    - 전체 MD 1회 호출이 아니라, splitBySections()로 섹션 분할 후 병렬 교정
    - 분할 기준 (splitBySections, 모두 코드펜스 ```/~~~ 내부에서는 적용 안 함):
      a) H2/H3 헤딩(줄이 "## " 또는 "### "로 시작) — 펜스 안의 "### Job ID : ..." 같은
         로그/배치 실행 결과 줄은 헤딩처럼 보여도 분할 트리거로 취급하지 않음
      b) 섹션 길이가 maxSectionChars 초과 시 강제 분할
         (maxSectionChars = max(500, (LLM_MAX_TOKENS-500)/2) → 기본 8,000토큰 기준 3,750자)
         — 펜스가 열려 있는 동안 초과가 감지되면 펜스는 자르지 않고, 펜스 시작 위치로 처리 분기:
           · 펜스가 이 섹션 안에서 MIN_SECTION_CHARS/2(250자) 이상 지난 뒤에 시작됐다면
             → 펜스 이전 내용까지만 즉시 flush하고, 펜스 전체(지금까지 쌓인 내용 포함)를
               통째로 다음 섹션으로 넘겨 그 섹션에서 계속 자라게 함
           · 펜스가 섹션 아주 초반(< 250자)에 시작됐다면 → 넘겨봤자 자투리 섹션만 남으므로
             넘기지 않고, 펜스가 닫힐 때까지 이 섹션에 그대로 누적(섹션이 한도를 넘긴 채로 flush됨)
      c) 문서 끝까지 펜스가 닫히지 않은 기형 입력은 안전하게 "```"를 붙여 마감
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
    KeywordExtractor.enrichKeywords() — 한 번의 LLM 호출로:
    - excerpt_keywords 메타데이터 추가
    - chunk_context 메타데이터 추가 ("{filename} > {heading}" 구조적 맥락 + LLM 1~2문장 맥락;
      LLM 실패/타임아웃 시 구조적 맥락만 — 사용량은 context: 라벨로 기록)

  11) 임베딩 입력 구성 — §10.1-보완 임베딩 입력 정규화
    SearchTextBuilder.build() = chunk_context + MarkdownNoiseNormalizer.normalize(원문)
    - 마크다운 장식 줄(구분선 등) 제거, 강조 마커(**bold**/*italic*/<u>)만 제거하고 내용 보존
    - 코드펜스 내부·표 행은 무변형
    - 이 파생 텍스트는 임베딩·FTS 입력에만 쓰이고 영속 저장되지 않음(저장/표시는 원문 그대로)

  12) 임베딩 DB 저장
    a) Chroma 모드
      - `chromaApi.upsertEmbeddings()`로 수동 upsert(TokenCountBatchingStrategy 서브배치) —
        임베딩은 11)의 파생 텍스트, 저장 content/metadata는 원문(chunk_context 키 제외)
    b) sqlite-vec 모드
      - vec_embeddings: spring_doc_id, version, embedding(11의 파생 텍스트로 계산)
      - vec_document_chunks: spring_doc_id, content(원문), metadata(JSON, chunk_context 제외), version, doc_id, created_at
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
   - **PPTX** — 슬라이드 제목 placeholder(`TITLE`/`CENTERED_TITLE`)만 `##`로 승격한다. 제목이 없지만 본문(불릿 등) 또는 이미지(`XSLFPictureShape`)가 있는 슬라이드는 `"{N}번 슬라이드"`로 대체 헤딩을 붙인다 — 이미지만 있고 텍스트가 없는 슬라이드까지 건너뛰면 그 슬라이드에 대응하는 섹션 자체가 사라져 추출된 이미지의 `[이미지: ...]` 마커를 심을 자리가 없어지므로(이미지가 고아가 됨) 이미지가 있으면 건너뛰지 않는다. **제목·본문·이미지가 모두 없는 슬라이드(완전 공백 구분 슬라이드 등)만 마커·헤딩·본문 전부 생략하고 통째로 건너뛴다** — 그렇지 않으면 "## 139번 슬라이드"처럼 내용 없는 헤딩만 있는 청크가 그대로 임베딩/검색 인덱스에 남아 노이즈가 된다. 건너뛴 슬라이드는 다음 슬라이드의 `[페이지: N]` 번호에 영향을 주지 않는다(실제 슬라이드 인덱스를 그대로 사용). 본문 불릿은 들여쓰기 레벨(`XSLFTextParagraph.getIndentLevel()`)을 중첩 목록으로만 반영하고, 어떤 경우에도 소제목(`###` 이상)으로 승격하지 않는다 — 슬라이드 하나를 하나의 원자적 섹션으로 다루는 편이 PPTX의 실제 구조에 더 가깝고, 들여쓰기를 헤딩으로 승격하면 평범한 한 줄짜리 불릿 목록도 소제목이 되어 메타데이터가 산만해질 위험이 있기 때문 (검토된 대안 및 채택 근거는 구현 당시 논의 참고).
   - **PDF(비스캔)** — 페이지 텍스트만으로는 신뢰할 구조 신호가 없으므로 페이지마다 합성 헤딩 `"## N페이지"`만 부여한다(제목·소제목 추론 없음). 텍스트도 이미지도 없는 페이지만 마커·헤딩 모두 생략하고 건너뛰되(텍스트는 없어도 이미지가 있으면 PPTX와 동일한 이유로 건너뛰지 않음), 다음 페이지 번호는 밀리지 않고 실제 PDF 페이지 인덱스를 그대로 유지한다.
3. **페이지/슬라이드 마커**: 항상 제네릭 `[페이지: N]` 마커만 사용한다(DOCX 전용의 `[헤딩페이지: N]`은 쓰지 않음) — 실제로 내용을 내보내는 슬라이드/페이지의 헤딩 직전에 위치시켜 `splitMarkdownBySections()`가 그 헤딩의 `page_or_slide`로 정확히 귀속시킨다. **내용이 있는 슬라이드/페이지는 반드시 헤딩 하나씩을 가져야 하는 이유**: 헤딩이 없으면 섹션 경계가 전혀 생기지 않아 문서 전체가 헤딩 없는 섹션 1개로 뭉쳐버리고, 두 번째 슬라이드/페이지부터는 `page_or_slide` 값이 유실된다(완전히 비어 있는 슬라이드/페이지는 애초에 아무것도 내보내지 않고 건너뛴다 — 2번 항목 참고).
4. **이미지**: DOCX와 동일하게 본문에 `[이미지: ...]` 인라인 마커를 넣는다 — 헤딩 바로 다음(본문 텍스트보다 앞)에 슬라이드/페이지별 이미지 경로를 마커로 삽입한다. 별도의 사후 메타데이터 첨부 단계는 없다 — `DocumentLoaderService.loadFromMarkdown()`이 이미 갖고 있던 `[이미지: ...]` 마커 파싱 로직이 이 마커도 그대로 인식해 `image_paths`로 승격시킨다(DOCX와 완전히 동일한 메커니즘 재사용). 이 덕분에 `addImageDescriptions`(이미지 설명 추가) 옵션도 이제 PPTX/PDF에 정상 적용된다 — [IMAGE_PROCESS.md §5](IMAGE_PROCESS.md#5-vision-설명-생성-l2) 참고.
5. **청킹**: DOCX/TXT/MD와 같은 섹션 병합(`mergeShortSections`) 전략을 타지만, 서로 다른 `page_or_slide`를 가진 인접 섹션끼리는 병합을 금지한다(`ChunkSplitter.isMergeForbiddenByPageMismatch()`, 기존 헤딩-레벨-점프 금지 규칙과 나란히 적용) — "청크 1개 = 슬라이드/페이지 1개 = 정확한 인용" 보장을 DOCX보다 더 엄격하게 유지한다. 값이 없는 DOCX/TXT/MD는 항상 no-op.
6. **표(테이블)**: PPTX의 `XSLFTable`은 다루지 않는다 — `TableShape`이지 `TextShape`가 아니라서 기존 슬라이드 로더도 표 내용을 읽은 적이 없어 회귀는 아니다.
7. **스캔 판정**: `DocumentLoaderService.loadPdfPagesForConversion()`이 페이지 텍스트 추출과 스캔 판정(§6.6, 빈 페이지 50% 초과)을 함께 반환해, 스캔 PDF는 기존 `ocrWithPdfRenderer()` OCR 경로로, 비스캔 PDF는 위 MD 변환 경로로 분기한다 — 스캔 판정 로직 자체(임계값·휴리스틱)는 변경되지 않았다.
8. **MD 재인덱싱(↺)**: 위 변환기들도 `converted/{docId}.md`(+`_corrected.md`)를 남기므로 PPTX·비스캔 PDF도 DOCX·TXT와 동일하게 `/admin` 재인덱싱을 지원한다(스캔 PDF는 MD 파일이 없어 미지원).

### 6.4. 문서 타입별 처리 상세

| 타입 | 파싱/변환 | LLM 전처리 | 중간 산출물 (data/converted) | 청킹 | 이미지 | MD 재인덱싱(↺) |
|------|-----------|-----------|------------------------------|------|--------|----------------|
| **PDF(스캔)** | `PagePdfDocumentReader` 페이지 단위. 50% 이상 페이지가 50자 미만이면 스캔 판정 → Tesseract(kor+eng) OCR (`source_type=ocr`). **MD 변환 없음** | 없음 | 없음 | 슬라이딩 윈도우(섹션 병합 없음) | 페이지 이미지 추출 → `data/images/{docId}/` | 미지원 |
| **PDF(비스캔)** | `PdfToMarkdownConverter` 로 MD 변환 (페이지별 `[페이지: N]` + 합성 헤딩 `## N페이지` + `[이미지: ...]` 인라인, 텍스트·이미지 모두 없는 페이지는 건너뜀) | `MarkdownCorrectionService.correct()` — 섹션 병렬 **포맷 교정** (DOCX·TXT 와 동일 파이프라인, 페이지/이미지 마커 보존) | `{docId}.md`(원본) + `{docId}_corrected.md`(교정) | **헤딩(페이지) 섹션 우선 유지**, 초과 시 섹션 내부 슬라이딩 윈도우 — 단, 서로 다른 페이지끼리는 병합되지 않음(§6.3-bis) | `PdfImageExtractor`를 변환기가 직접 호출해 추출 + 본문에 `[이미지: ...]` 인라인(DOCX와 동일) | **지원** |
| **PPTX** | `PptxToMarkdownConverter` 로 MD 변환 (슬라이드별 `[페이지: N]` + 제목 헤딩 `##` + `[이미지: ...]` 인라인, 본문 불릿은 중첩 목록만) | `MarkdownCorrectionService.correct()` — 섹션 병렬 **포맷 교정** (DOCX·TXT 와 동일 파이프라인, 슬라이드/이미지 마커 보존) | `{docId}.md`(원본) + `{docId}_corrected.md`(교정) | **헤딩(슬라이드) 섹션 우선 유지**, 초과 시 섹션 내부 슬라이딩 윈도우 — 단, 서로 다른 슬라이드끼리는 병합되지 않음(§6.3-bis) | `PptxImageExtractor`를 변환기가 직접 호출해 추출 + 본문에 `[이미지: ...]` 인라인(DOCX와 동일) | **지원** |
| **DOCX** | `DocxToMarkdownConverter` 로 MD 변환 (제목 스타일 → `##/###`, `[헤딩페이지: N]`/`[페이지: N]` + 이미지 `[이미지: ...]` 인라인) | `MarkdownCorrectionService.correct()` — 섹션 병렬 **포맷 교정**(끊긴 문장 연결·오타·헤딩 정규화, 내용 불변, 페이지/이미지 마커 보존) | `{docId}.md`(원본) + `{docId}_corrected.md`(교정) | **헤딩 섹션 우선 유지**, 초과 시 섹션 내부 슬라이딩 윈도우 | 변환 단계에서 인라인 처리 | **지원** |
| **TXT** | 평문 → `TextToMarkdownService.convert()` — 로컬 LLM 이 **구조화**(제목/목록/표 부여) + **문법 교정**(맞춤법·띄어쓰기·끊긴 문장), 내용 불변 → MD | 위 구조화에 이어 `MarkdownCorrectionService.correct()` **포맷 교정** 한 번 더 (DOCX 와 동일 파이프라인) | `{docId}.md`(구조화) + `{docId}_corrected.md`(교정) | 섹션 단위, 초과 시 슬라이딩 윈도우 | 없음 | **지원** |
| **MD** | 이미지/링크 마커 전처리 후 `#` 헤딩 기준 섹션 분할 | 없음 | 없음 | 섹션 단위, 초과 시 슬라이딩 윈도우 | `[이미지: ...]` 마커 → image_paths | 미지원 |

> **DOCX·TXT·PPTX·PDF(비스캔)의 LLM 전처리는 graceful**: LLM 사용 불가 시 원본(변환 전) 텍스트를 그대로 사용해 인덱싱은 계속된다.  
> **TXT 구조화 LLM 호출**: `TaskType.LIGHT_TEXT` · `RoutingMode.COST_FIRST`(로컬 프로바이더 우선). 큰 파일은 6,000자 블록으로 나눠 병렬 처리.  
> **PPTX/PDF(비스캔)도 이제 이미지를 `[이미지: ...]` 인라인 마커로 넣으므로**(DOCX와 동일 방식), 업로드 화면의 "이미지 설명 추가"(`addImageDescriptions`) 체크박스가 이 두 포맷에도 정상 적용된다 — [IMAGE_PROCESS.md §5](IMAGE_PROCESS.md#5-vision-설명-생성-l2) 참고.  
> **MD 재인덱싱(↺)**: `data/converted/{docId}[_corrected].md` 가 존재하는 DOCX·TXT·PPTX·PDF(비스캔) 만 지원(`AdminController` `/admin/documents/{docId}/reindex`). 재변환/재교정 없이 저장된 MD 를 다시 청킹·임베딩한다. 태그는 FTS 인덱스에서 복원. 스캔 PDF는 MD 파일 자체가 없어 미지원.

### 6.5. 디렉터리 동기화 — 3단계

```
Phase 1  변경 감지 (단일 스레드)
  SHA-256 계산 → 레지스트리 비교
  → 신규/변경/삭제 파일 목록 확정

Phase 2  병렬 인덱싱 (Virtual Thread)
  최대 maxConcurrentFiles(기본 3)개 파일 동시 처리
  LLM 키워드 추출은 maxConcurrentLlmCalls(기본 4) Semaphore 제한
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
| [LLM_ROUTING.md](LLM_ROUTING.md) | 라우팅 모드, 프로바이더 설정, 회로 차단기 |
| [IMAGE_PROCESS.md](IMAGE_PROCESS.md) | 이미지 추출, OCR, Vision LLM 설명 생성 |
| [OPERATOR_MANUAL.md](OPERATOR_MANUAL.md) | 환경변수, 배포, 시나리오별 설정 예제 |
| [UI.md](UI.md) | 화면 구성, HTMX 엔드포인트 |
