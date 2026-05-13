# 사용자 매뉴얼

문서 기반 지식 Q&A 에이전트 사용 가이드입니다.

---

## 목차

1. [개요](#1-개요)
2. [Web UI 사용법](#2-web-ui-사용법)
   - 2.1 [언어 전환](#21-언어-전환)
   - 2.2 [채팅](#22-채팅)
   - 2.3 [문서 관리](#23-문서-관리)
   - 2.4 [LLM 사용량 통계](#24-llm-사용량-통계)
3. [REST API 사용법](#3-rest-api-사용법)
   - 3.1 [헬스 체크](#31-헬스-체크)
   - 3.2 [문서 업로드 및 인덱싱](#32-문서-업로드-및-인덱싱)
   - 3.3 [증분 동기화](#33-증분-동기화)
   - 3.4 [문서 목록 조회](#34-문서-목록-조회)
   - 3.5 [문서 삭제](#35-문서-삭제)
   - 3.6 [질의응답 (채팅)](#36-질의응답-채팅)
   - 3.7 [LLM 사용량 조회](#37-llm-사용량-조회)
4. [문서 인덱싱 동작](#4-문서-인덱싱-동작)
   - 4.1 [형식별 청크 분할 전략](#41-형식별-청크-분할-전략)
   - 4.2 [메타데이터](#42-메타데이터)
5. [문제 해결](#5-문제-해결)
6. [빠른 시작 체크리스트](#6-빠른-시작-체크리스트)

---

## 1. 개요

문서를 업로드하면 내용을 이해하고 질문에 답변하는 RAG(Retrieval-Augmented Generation) 서비스입니다.  
**Web UI**(`http://localhost:8080`)와 **REST API** 두 가지 방식으로 사용할 수 있습니다.

**주요 기능**:

| 기능 | 설명 |
|------|------|
| 질문 유형 분류 | concept / usage / error / version / meta 5종 자동 분류 |
| 멀티 쿼리 검색 | 원본 질문 + LLM 생성 변형 쿼리 2개(총 3개) 병렬 검색 후 중복 제거 |
| ReAct 재검색 | 증거 부족 시 자동 재검색 (최대 2회) |
| Critic 검증 | 답변이 문서에 근거하는지 이중 검증 |
| SSE 실시간 스트리밍 | 처리 단계 배지 + 토큰 실시간 출력 |
| 멀티턴 대화 | 스레드 단위 대화 이력 유지 (재시작 후에도 유지) |
| 문서 버전 관리 | 버전별 독립된 검색 컬렉션 |
| 이미지 처리 | PDF·PPTX·DOCX 이미지 추출 → 답변에 썸네일 표시; 스캔 PDF OCR 지원 |
| 다크 모드 | 시스템 설정 자동 감지 + 사용자 override |
| 출처 hover 미리보기 | 출처 항목에 마우스를 올리면 관련 문서 청크 200자 미리보기 |

> 시스템 설치·배포·LLM 설정은 [OPERATOR_MANUAL.md](OPERATOR_MANUAL.md)를 참고하세요.

---

## 2. Web UI 사용법

### 2.1 언어 전환

Navbar 우측 상단 **KO | EN** 링크를 클릭하면 즉시 언어가 전환됩니다.  
선택한 언어는 브라우저 쿠키(`lang`)에 저장되어 재방문 시에도 유지됩니다.

---

### 2.2 채팅

1. **`http://localhost:8080`** 접속 (새 스레드 자동 생성)
2. 이전 대화를 이어하려면 사이드바에서 해당 스레드 클릭
3. 좌측 상단 **version** 입력창에 검색할 문서 버전 입력 (기본: `latest`)
4. 하단 입력창에 질문 입력 후 **Enter** (줄바꿈: **Shift+Enter**)

**답변 실시간 표시**:
- 상단 배지에서 현재 처리 단계 확인 (질문 분류 → 문서 검색 → 답변 생성 → 검증)
- 토큰이 생성되는 즉시 화면에 출력
- 답변 아래 출처(Sources) — 마우스를 올리면 관련 문서 청크 미리보기 팝오버 표시
- 검색된 이미지가 있으면 썸네일 그리드로 표시 (클릭 시 원본 확인)
- 소요 시간·토큰 수·사용 LLM 메타정보 확인 가능

**스레드 관리**:

| 동작 | 방법 |
|------|------|
| 새 대화 시작 | 사이드바 **New Chat** 버튼 |
| 이전 대화 재개 | 사이드바에서 스레드 클릭 → 이전 메시지 버블 복원 |
| 대화 제목 변경 | 스레드 옆 연필 아이콘 클릭 → 인라인 편집 |
| 대화 삭제 | 스레드 옆 휴지통 아이콘 |

> **라우팅 모드** (채팅 화면 드롭다운): `COST_FIRST` · `QUALITY_FIRST` · `PROGRESSIVE` · `DUAL` · `LOCAL_ONLY`  
> 각 모드의 동작 방식은 [OPERATOR_MANUAL.md §5.3](OPERATOR_MANUAL.md)을 참고하세요.

> **메시지 복원 제한**: 이전 turn의 토큰 수·출처 메타데이터는 DB에 저장되지 않으므로 새 turn에서만 표시됩니다.

---

### 2.3 문서 관리

`/documents` 접속 후:

| 기능 | 방법 |
|------|------|
| **업로드** | 드래그 앤 드롭 또는 영역 클릭 → 파일 선택 → Version 입력 → **Upload & Index** |
| **폴더 동기화** | **Sync Folder** 버튼 → `data/documents/` 폴더 자동 스캔 (신규·변경·삭제 처리) |
| **목록 새로고침** | **Refresh** 버튼 |
| **문서 삭제** | 행 우측 **Delete** 버튼 |

**지원 형식**: PDF, PPTX, DOCX, TXT, MD / 최대 100 MB

업로드 시 참고 사항:
- 같은 파일을 다시 올리면 SHA-256 기반 변경 감지로 변경된 경우에만 재인덱싱
- 파일 내용이 확장자와 일치하지 않으면 업로드가 거부됩니다 (예: .txt로 이름 바꾼 실행파일)
- 이미지 포함 문서(PDF·PPTX·DOCX)는 이미지도 함께 추출 (운영자 설정 필요)
- 스캔 PDF는 OCR로 텍스트 추출 (운영자 설정 필요)
- Sync 결과(신규/업데이트/삭제 건수)는 우측 하단 toast로 표시

---

### 2.4 LLM 사용량 통계

`/llm-usage` 접속 후:

| 영역 | 설명 |
|------|------|
| **프로바이더 카드** | 설정된 LLM 프로바이더별 상태 — 정상(초록) / 차단 중(빨강 + 남은 시간 카운트다운) |
| **일별 차트** | 누적 막대 차트 — 7일 / 30일 / 90일 기간 선택, 프로바이더별 색상 구분 |
| **기간별 테이블** | 오늘 / 이번 주 / 이번 달 탭 — 프로바이더별 입력/출력/합계 토큰 및 호출 수 |

카드 영역은 30초마다 자동 갱신됩니다.

---

## 3. REST API 사용법

기본 URL: `http://localhost:8080`

### 3.1 헬스 체크

```bash
curl http://localhost:8080/api/health
# {"status":"ok","service":"rag-agent","timestamp":"..."}
```

---

### 3.2 문서 업로드 및 인덱싱

지원 형식: **PDF, PPTX, DOCX, TXT, MD**

```bash
curl -X POST http://localhost:8080/api/documents \
  -F "file=@/path/to/manual.pdf" \
  -F "version=latest"
```

응답:
```json
{
  "doc_id": "manual.pdf_a1b2c3d4",
  "filename": "manual.pdf",
  "version": "latest",
  "chunks": 42,
  "indexed_at": "2025-04-22T10:00:00Z",
  "sha256": "a1b2c3d4...",
  "errors": []
}
```

---

### 3.3 증분 동기화

`data/documents/` 폴더를 스캔해 신규·변경·삭제 파일을 자동 처리합니다.

```bash
curl -X POST "http://localhost:8080/api/documents/sync?version=latest"
```

응답:
```json
{
  "indexed": ["new_guide.pdf"],
  "updated": ["changed_manual.docx"],
  "deleted": ["old_spec.txt"]
}
```

---

### 3.4 문서 목록 조회

```bash
curl http://localhost:8080/api/documents
```

---

### 3.5 문서 삭제

```bash
curl -X DELETE "http://localhost:8080/api/documents/manual.pdf_a1b2c3d4?version=latest"
# 응답: 200 OK
```

---

### 3.6 질의응답 (채팅)

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "question": "Spring Security에서 JWT 인증을 어떻게 설정하나요?",
    "version": "latest",
    "thread_id": "user-session-001"
  }'
```

**요청 필드**:

| 필드 | 필수 | 기본값 | 설명 |
|------|------|--------|------|
| `question` | ✅ | — | 사용자 질문 (최대 2,000자) |
| `version` | — | `latest` | 검색할 문서 버전 |
| `thread_id` | — | `default` | 멀티턴 대화 식별자 |

**응답**:
```json
{
  "answer": "## 요약\n...",
  "question_type": "usage",
  "sources": [
    {
      "label": "spring-security-guide.pdf | vlatest | p.12",
      "preview": "Spring Security에서 JWT 인증 필터를 구성하려면...",
      "doc_id": "spring-security-guide.pdf_a1b2c3d4",
      "page_or_slide": 12
    }
  ],
  "total_input_tokens": 1248,
  "total_output_tokens": 512,
  "llm_call_count": 4,
  "elapsed_seconds": 3.2
}
```

**질문 유형 (`question_type`)**:

| 값 | 설명 |
|----|------|
| `concept` | 개념·이론 설명 |
| `usage` | 사용법·코드 예시 |
| `error` | 오류·트러블슈팅 |
| `version` | 버전·변경사항 |
| `meta` | 인사·잡담 (RAG 미사용, 직접 응답) |

---

### 3.7 LLM 사용량 조회

```bash
# 일간·주간·월간 집계 + Circuit Breaker 상태
curl http://localhost:8080/api/llm/usage
```

응답:
```json
[
  {
    "provider": "local",
    "type": "LIGHT_BOTH",
    "model": "gemma-4-27b-it",
    "daily":   { "inputTokens": 1200, "outputTokens": 340, "callCount": 5 },
    "weekly":  { "inputTokens": 8400, "outputTokens": 2100, "callCount": 32 },
    "monthly": { "inputTokens": 32000, "outputTokens": 8500, "callCount": 120 },
    "blockedUntil": null
  }
]
```

```bash
# 일별 히스토리 (days=7|30|90)
curl "http://localhost:8080/api/llm/usage/history?days=30"
```

---

## 4. 문서 인덱싱 동작

```
파일 업로드 or Sync Folder
  └─▶ SHA-256 계산 → doc_registry.json 과 비교
        ├─ 신규 파일 → 로드 → 청크 분할 → 키워드 추출 (LLM) → Chroma 추가
        ├─ 변경 파일 → 기존 청크 삭제 → 재인덱싱
        └─ 삭제 파일 → Chroma에서 제거
```

> **키워드 추출**: 청크당 LLM을 호출해 핵심 키워드 5개를 `excerpt_keywords` 메타데이터로 저장합니다.  
> 검색 품질이 높아지는 대신 인덱싱 시간이 늘어납니다.

### 4.1 형식별 청크 분할 전략

| 형식 | 로드 단위 | 분할 전략 | 이미지 처리 |
|------|----------|----------|------------|
| `.md` | `#` / `##` / `###` 헤더 단위 섹션 | 섹션이 `CHUNK_SIZE` 초과 시만 슬라이딩 윈도우 | URL 이미지 → alt 텍스트 유지, 로컬 이미지 → `[이미지: path]` 마커 |
| `.docx` | Word Heading1/2 스타일 단위 섹션 | 섹션이 `CHUNK_SIZE` 초과 시만 슬라이딩 윈도우 | 인라인 이미지 추출 + EMF/WMF → PNG 변환 (설정 시) |
| `.pptx` | 슬라이드 1장 = 청크 1개 | 추가 분할 없음 | 슬라이드 이미지 추출 |
| `.pdf` | 페이지 1장 = 문서 1개 | 슬라이딩 윈도우 (`CHUNK_SIZE` / `CHUNK_OVERLAP`) | 임베드 이미지 추출; 스캔 PDF는 OCR 처리 (설정 시) |
| `.txt` | 전체 파일 = 문서 1개 | 슬라이딩 윈도우 (`CHUNK_SIZE` / `CHUNK_OVERLAP`) | — |

> **슬라이딩 윈도우**: 청크 경계가 텍스트 중간이면 가장 가까운 줄바꿈(`\n`)으로 경계를 조정합니다.  
> `.docx` 헤딩이 없는 경우 전체 텍스트를 단일 문서로 처리한 뒤 슬라이딩 윈도우를 적용합니다.

### 4.2 메타데이터

| 메타데이터 | 적용 형식 | 설명 |
|-----------|----------|------|
| `doc_id`, `filename`, `version`, `sha256` | 전체 | 공통 식별 정보 |
| `page_or_slide` | PDF, PPTX | 페이지/슬라이드 번호 |
| `section`, `heading` | MD, DOCX | 섹션 번호·헤더 텍스트 |
| `excerpt_keywords` | 전체 | LLM이 추출한 핵심 키워드 5개 |

버전별로 `manual_{version}` 컬렉션 분리 (예: `manual_latest`, `manual_1_0`).

---

## 5. 문제 해결

### 답변이 없거나 "문서에서 확인되지 않음"

```bash
# 인덱싱된 문서 확인
curl http://localhost:8080/api/documents

# 폴더 동기화 실행
curl -X POST "http://localhost:8080/api/documents/sync?version=latest"
```

- 문서 미업로드 → `/documents` 페이지 또는 API로 업로드
- `version` 불일치 → 업로드와 채팅의 `version` 값을 일치시킴
- 스캔 PDF → 텍스트 레이어가 있는 PDF 사용하거나 운영자에게 OCR 활성화 요청

### 답변 품질이 낮음

- 검색 문서 수(`SEARCH_TOP_K`) 또는 청크 크기(`CHUNK_SIZE`) 조정 필요 → 운영자 문의
- 라우팅 모드를 `QUALITY_FIRST`로 변경 (채팅 화면 드롭다운)

### 이미지가 답변에 표시되지 않음

이미지 처리는 운영자 설정(`app.image-description.enabled=true`)이 필요합니다.  
운영자 문의 또는 [OPERATOR_MANUAL.md](OPERATOR_MANUAL.md)를 참고하세요.

### 업로드 실패 (422)

파일 내용이 확장자와 일치하지 않을 때 발생합니다. 원본 파일을 다시 확인하거나 올바른 형식으로 저장 후 재시도하세요.

### 질문 오류 (400)

질문이 2,000자를 초과하거나 비어 있을 때 발생합니다. 질문을 간결하게 줄인 후 재시도하세요.

### 연결 오류 / 서비스 미응답

```bash
curl http://localhost:8080/api/health
```

응답이 없으면 운영자에게 서비스 상태를 확인 요청하세요.

---

## 6. 빠른 시작 체크리스트

서비스 첫 사용 시 순서대로 확인하세요.

- [ ] `GET /api/health` → `{"status":"ok"}` 응답 확인
- [ ] `http://localhost:8080` 접속 확인
- [ ] 샘플 문서 1개 업로드 성공 (`/documents` 또는 `POST /api/documents`)
- [ ] 문서 목록에 업로드 문서 표시 확인
- [ ] 업로드 문서 관련 질문 응답 성공 + Sources 포함 확인
- [ ] 후속 질문 시 이전 맥락 반영 확인 (멀티턴)
- [ ] 대화 재진입 시 이전 메시지 버블 복원 확인 (`/chat/{threadId}`)
- [ ] KO/EN 언어 전환 동작 확인
