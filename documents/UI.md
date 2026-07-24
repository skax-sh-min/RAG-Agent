# UI — 채팅 에이전트 Thymeleaf UI 참조 문서

> 구현 완료. 이 문서는 현재 UI 구조와 운영 참조 가이드입니다.

---

## 1. 기술 스택

| 레이어 | 기술 | 설명 |
|--------|------|------|
| 템플릿 | Thymeleaf + Layout Dialect | `layout/base.html` 공통 레이아웃 |
| CSS | Bootstrap 5 (WebJars 5.3.3) | CDN 없이 번들 |
| 아이콘 | Bootstrap Icons (WebJars 1.11.3) | |
| 동적 갱신 | HTMX 2.0.4 | JS 없이 서버 fragment 교체 |
| 마크다운 | marked.js 9.1.4 + DOMPurify 3.1.6 | XSS sanitize 후 렌더 |
| 코드 하이라이트 | highlight.js 11.11.1 | `sanitize → hljs.highlightElement()` |
| 차트 | Chart.js 4.4.3 | LLM 사용량 일별 히스토리 stacked bar |

---

## 2. 파일 구조

```
src/main/resources/
├── templates/
│   ├── layout/base.html                   # navbar, dark mode toggle, scripts
│   ├── chat.html                          # 채팅 페이지 (이전 turn 서버 렌더 포함)
│   ├── documents.html                     # 문서 관리 페이지
│   ├── llm-usage.html                     # LLM 사용량 통계 페이지
│   ├── settings.html                      # LLM/RAG 설정 조회·핫 수정 페이지
│   └── fragments/
│       ├── message-user.html              # 사용자 메시지 버블
│       ├── message-assistant.html         # 어시스턴트 버블 (메타데이터 포함)
│       ├── message-assistant-dual.html    # DUAL 모드 탭 버블
│       ├── message-error.html             # 오류 버블
│       ├── thread-list.html               # 대화 목록 사이드바
│       ├── thread-item.html               # 대화 목록 항목 1건
│       ├── doc-table-body.html            # 문서 목록 tbody (새로고침용)
│       ├── llm-usage-cards.html           # 프로바이더 + 임베딩(EMBEDDING) + orphan(ORPHAN, 삭제 가능) 상태 카드 (30초 자동 갱신)
│       ├── settings-item.html             # 설정 항목 1행(조회 또는 편집 입력 + 저장/기본값 버튼) — HTMX 부분 갱신 대상
│       └── settings-providers.html        # LLM providers 표(활성화 배지 + 관리자 활성/비활성 버튼) — settings.html에 인라인 포함 + 토글 응답 시 테이블 전체 교체
└── static/
    ├── css/app.css                        # 버블·배지·DUAL 탭·타이핑·반응형(오프캔버스/dvh/16px/44px)
    ├── css/theme.css                      # light/dark CSS 변수
    ├── manifest.webmanifest               # PWA 매니페스트 (이름·아이콘·standalone)
    ├── sw.js                              # 서비스 워커 (NETWORK-FIRST, 오프라인 fallback 전용)
    ├── offline.html                       # 오프라인 fallback 페이지 (자체 완결 정적 HTML)
    ├── icons/icon.svg                     # 앱 아이콘 (SVG, any maskable)
    └── js/
        └── chat-stream.js                # SSE 스트리밍 클라이언트 (fetch + ReadableStream)
```

---

## 3. 라우팅 (컨트롤러 엔드포인트)

### 3.1 채팅 / 스레드 (ChatController)

| Method | Path | 반환 | 설명 |
|--------|------|------|------|
| GET | `/` | `chat.html` | 새 대화 |
| GET | `/chat/{threadId}` | `chat.html` | 기존 대화 이어하기 (이전 turn 서버 렌더) |
| POST | `/ui/chat` | `fragments/message-assistant` 또는 `message-assistant-dual` | 질문 전송 (동기 fallback) |
| POST | `/ui/chat/stream` | `text/event-stream` (SseEmitter) | SSE 스트리밍 응답 — `chat-stream.js`가 사용 |
| POST | `/ui/chat/new` | redirect `/chat/{newId}` | 새 대화 생성 |
| PATCH | `/ui/threads/{threadId}/title` | `fragments/thread-item` | 대화 제목 수정 |
| PATCH | `/ui/threads/{threadId}/routing-mode` | `204` | 대화별 라우팅 모드 저장 |
| DELETE | `/ui/threads/{threadId}` | `200` | 대화 삭제 |
| GET | `/ui/threads` | `fragments/thread-list` | 대화 목록 새로고침 |

### 3.2 문서 관리 (DocumentController)

| Method | Path | 반환 | 설명 |
|--------|------|------|------|
| GET | `/documents` | `documents.html` | 문서 관리 페이지 |
| POST | `/ui/documents/upload` | 202 `{"taskId":"..."}` | 파일 업로드 수신 → 비동기 인덱싱 시작 |
| GET | `/ui/documents/progress/{taskId}` | `text/event-stream` (SSE) | 인덱싱 진행 이벤트 (`stage`, `done`, `error`) |
| DELETE | `/ui/documents/{docId}` | `200` | 문서 삭제 |
| GET | `/ui/documents/list` | `fragments/doc-table-body` | 문서 목록 새로고침 |

> **관리 전용 인증 모드**(`app.auth.management-only=true`, §6.17 B안)에서는 `POST /ui/documents/upload`, `POST /ui/documents/progress/*/cancel`, `DELETE /ui/documents/{docId}`, `PATCH /ui/documents/{id}/tags`, `GET /ui/documents/{id}/tags/edit`가 `hasRole("ADMIN")`로 게이트된다 — 비로그인은 `/login` 리다이렉트, 관리자 아닌 로그인은 403. `GET /documents`·`GET /ui/documents/list`·태그 조회는 게스트에게 그대로 열려 있다. 자세한 내용은 [OPERATOR_MANUAL.md §9.4.2](OPERATOR_MANUAL.md#942-관리-전용-인증-management-only) 참고.
>
> **인덱싱 진행 스테이지**(`stage` 값): `loading` → `structuring`(TXT만) → `describing_images`(Vision 이미지 분석, "이미지 설명 추가" 체크 시만 — "이미지 분석 중 (N/M)") → `correcting`(DOCX/TXT/MD/PPTX/PDF[비스캔]) → `chunking` → `enriching` → `storing` → `done`/`error`/`cancelled`. 각 이벤트는 `stage`와 함께 `done`/`total`/`filename`/`message`를 실어 나르며, `documents.html`의 `stageHtml`/`STAGE_LABELS`가 단계별 진행률 바와 오류 로그 라벨을 렌더링한다. 상세는 [PIPELINE.md §6.3](PIPELINE.md#63-docx--md--임베딩-db-저장-상세-이미지-포함) 참고.

### 3.3 운영 / LLM 사용량 (OperationsController)

| Method | Path | 반환 | 설명 |
|--------|------|------|------|
| GET | `/llm-usage` | `llm-usage.html` | LLM 사용량 페이지 |
| GET | `/ui/llm-usage/cards` | `fragments/llm-usage-cards` | 카드 HTMX 자동 갱신(30초). 채팅 프로바이더 + 임베딩(`embed:<model>`, `EMBEDDING` 배지) + orphan(설정에 없는 이름, `ORPHAN` 배지 + 삭제 버튼) 카드 포함 |
| DELETE | `/admin/llm-usage/{provider}` | `fragments/llm-usage-cards` | orphan 프로바이더의 누적 사용 기록 삭제. `/admin/**` 경로 아래 있어 `ROLE_ADMIN` 전용(no-auth 모드는 관리자 자동 인증 상속) — 컨트롤러는 `OperationsController` 소속, 경로만 admin 네임스페이스 |
| GET | `/ui/threads/{threadId}/turns/{turnId}/curated` | JSON `{"answer":"..."}` | §10.10 — 본인 좋아요 답변의 현재 큐레이션 텍스트 조회(채팅 인라인 편집창 채우기용). 소유권은 기존 피드백 엔드포인트와 동일하게 `(userId, threadId)` 스코프로 검증 |
| PATCH | `/ui/threads/{threadId}/turns/{turnId}/curated` | `204` | §10.10 — 본인 좋아요 답변의 큐레이션 텍스트 수정(`answer` 폼 파라미터) → 저장 즉시 백그라운드 재임베딩. 관리자 권한 불필요 — thread 자체가 사용자별로 격리되어 있어 본인 turn만 접근 가능 |

REST API: `GET /api/v1/llm/usage`, `GET /api/v1/llm/usage/history?days=N` — 둘 다 임베딩·orphan 항목 포함(상세는 [OPERATOR_MANUAL.md](OPERATOR_MANUAL.md) 참고)

### 3.4 벡터 스토어 관리 (AdminController)

접근 제어는 인증 모드에 따라 다르다:
- 전체 인증(`app.auth.enabled=true`) — 로그인 필요
- 평문 no-auth(`app.auth.enabled=false`, `app.auth.management-only=false`) — `/admin/**`에 관리자 자동 주입
- 관리 전용 인증(`app.auth.management-only=true`, §6.17 B안) — 게스트 자동 주입 없이 실제 로그인 필요

**chroma·sqlite-vec 두 백엔드 모두** 동작하며, sqlite-vec에선 "collection" 식별자가 version 문자열이다.

| Method | Path | 반환 | 설명 |
|--------|------|------|------|
| GET | `/admin` | `admin.html` | Vector Store 상태 카드 + 컬렉션/버전 목록 + 문서 레지스트리 |
| GET | `/admin/chunks` | `fragments/admin-chunks :: table` | 컬렉션(또는 버전)·docId별 청크 페이지네이션 |
| GET | `/admin/chunks/{chunkId}/detail` | JSON | 청크 텍스트·메타데이터 (편집 패널) |
| POST | `/admin/chunks/{chunkId}` | `200` | 청크 텍스트·메타데이터 수정 (벡터 보존) |
| DELETE | `/admin/chunks/{chunkId}` | `200` | 청크 삭제 (sqlite-vec는 두 테이블 동기 삭제) |
| POST | `/admin/documents/{docId}/reindex` | JSON | 저장된 MD로 재인덱싱 (DOCX/TXT/MD/PPTX/PDF 전용, 스캔 PDF 제외 — 스캔 PDF는 MD 변환 없이 OCR로 바로 인덱싱되어 재사용할 MD 파일이 없다) |
| GET | `/admin/curated` | `fragments/admin-curated :: panel` | §10.10 — 큐레이션 Q&A 패널 지연 로딩 프래그먼트. `offset`(기본 0)·`limit`(기본 20, 20/50/100) 쿼리 파라미터로 페이지네이션. `/admin` 페이지 자체는 이 데이터를 조회하지 않고, 카드를 처음 펼칠 때만(`<details>` `toggle` 이벤트) HTMX로 호출되며, 이후 페이지 이동·페이지당 건수 변경은 `loadCurated()`(페이지 레벨 JS, plain fetch)가 같은 엔드포인트를 다시 호출함 |
| GET | `/admin/curated/{id}/detail` | JSON | §10.10 — 큐레이션 Q&A 항목의 질문·답변 조회 (편집 패널) |
| POST | `/admin/curated/{id}` | `200` | §10.10 — 큐레이션 Q&A 답변 수정 → 재임베딩. 좋아요를 누른 사용자와 무관하게 관리자가 어떤 항목이든 편집 가능 |
| DELETE | `/admin/curated/{id}` | `200` | §10.10 — 큐레이션 Q&A 강제 삭제(비활성화+de-index). 좋아요 주체의 동의 없이도 관리자가 제거 가능(모더레이션) |

> 상태 카드는 `AdminService.vectorStoreView()` → `VectorStoreAdminView`. 백엔드별 표시 차이는 [OPERATOR_MANUAL.md §7.4](OPERATOR_MANUAL.md) 참고.
>
> **큐레이션 Q&A 카드**(`/admin` 하단, §10.10): 기본적으로 접힌 `<details>` 카드이며, 처음 펼칠 때만(`hx-trigger="toggle[this.open] once"` → `GET /admin/curated`) 좋아요로 승격된 질문·답변을 최신순으로 조회해 표시한다 — `AdminController.adminPage()`는 더 이상 `curatedQaService.listActive()`를 즉시 호출하지 않으므로 `/admin` 페이지 로드 자체는 이 조회를 하지 않는다. 페이지당 건수는 20/50/100 중 선택(기본 20 — `AdminController.curatedPanel()`의 `limit` 기본값), 이전/다음 버튼으로 페이지 이동한다(`CuratedQaRepository.findAllActive(offset, limit)`) — 이전의 고정 상한 50건·페이지네이션 없음 방식에서, 큐레이션 항목이 계속 쌓여도 패널이 무거워지지 않도록 청크 목록과 동일한 페이지네이션 UI로 전환됐다. 편집(연필 아이콘)은 저장 시 자동 재임베딩되는 점이 위 청크 편집과 다르다 — 청크 편집은 원본 벡터를 그대로 유지하지만, 큐레이션 Q&A 편집은 검색 정확도가 목적이라 항상 재임베딩된다. 상세는 [OPERATOR_MANUAL.md §7.5](OPERATOR_MANUAL.md#75-큐레이션-qa-관리-1010) 참고.

> **청크 목록 컬럼**(`fragments/admin-chunks :: table`): ID·텍스트 미리보기·크기·파일명·페이지/슬라이드·챕터·키워드·작업. **챕터** 열은 `MetaKey.CHAPTER_NO`(H2~H6 헤딩 기반 계층 번호, 예: `1.5.3`)를 보여주며, "0"(프롤로그·PPTX·스캔 PDF — 실제 챕터 없음)이면 빈 칸으로 표시된다 — [§4 출처 Hover 미리보기](#출처-hover-미리보기)의 인용 라벨 로직과 동일한 컨벤션.
>
> **청크 목록 페이지네이션·정렬**: 페이지당 건수는 20/50/100 중 선택(기본 20 — `AdminController.chunks()`의 `limit` 기본값), 필터 폼의 드롭다운 변경 시 `offset=0`으로 다시 조회한다. 정렬은 `doc_id` → `MetaKey.CHUNK_INDEX`(인덱싱 시 부여되는 0-based 문서 내 위치) 순 — 두 백엔드 모두 문서 원본 순서 그대로 표시된다(청크 id 순서 아님). sqlite-vec는 `ORDER BY doc_id, json_extract(metadata,'$.chunk_index'), spring_doc_id`로 DB에서 정렬하고, Chroma는 `get()`에 서버 측 정렬이 없어 매치 전체(최대 `AdminService.CHUNK_FETCH_CAP`=10,000건)를 가져와 애플리케이션에서 정렬 후 페이지네이션한다.
>
> **청크 필터/페이지네이션 JS는 `admin.html`(페이지 레벨)에 있다, `fragments/admin-chunks.html`이 아니라**: 예전엔 프래그먼트 자체의 `<script>`에 `applyDocFilter`/`applyLimitFilter`/`loadChunks`를 정의했는데, `loadChunks()`가 다음 페이지 응답을 `#chunk-panel.innerHTML = html`로 삽입한다 — 브라우저는 `innerHTML` 대입으로 들어온 `<script>`를 실행하지 않으므로, 문서 레지스트리의 **청크 보기**(`loadChunksByDoc()`, 이것도 plain `innerHTML`)로 패널에 먼저 진입하면 이 함수들이 아예 정의되지 않아 다음 버튼·페이지당 건수 변경이 조용히 무반응이었다(왼쪽 컬렉션 버튼의 `hx-swap`은 스크립트를 실행하므로 그 경로로 먼저 들어오면 우연히 동작했다 — 진입 경로에 따라 동작 여부가 갈리는 버그). 지금은 세 함수 모두 상시 로드되는 `admin.html` 스크립트에 있어 진입 경로와 무관하게 항상 정의돼 있고, 현재 컬렉션 값은 Thymeleaf 인라인 JS 대신 청크 카드 루트의 `data-collection` 속성에서 읽는다(`currentChunkCollection()`) — DOM 속성은 삽입 방식과 무관하게 항상 반영되기 때문. 큐레이션 Q&A 패널의 `loadCurated()`/`applyCuratedLimitFilter()`도 같은 이유로 처음부터 `admin.html`에 둔다.
>
> **재인덱싱 시 수행 작업**(`DocumentIndexer.reindexFromMd()`) — 챕터 번호 재계산뿐 아니라 전체 파이프라인을 다시 돈다: 존재하지 않는 이미지 참조(`[이미지: ...]`) 제거 → 소제목 번호 재계산(PPTX 제외) → 마크다운 후처리(`MarkdownCorrectionService.postProcess()` — 빈 줄 정리·`[DOCUMENT]` 마커/내용 없는 `-` 제거·펜스·표 앞뒤 빈 줄 보장, LLM 미사용) → 전체 재청킹 → 태그 보존 → LLM 키워드+컨텍스트 재추출(§10.1) → 재임베딩 및 벡터 스토어 저장 → FTS 재인덱싱 → 기존 청크 삭제(신규 저장 이후, 실패 시 기존 데이터 보존). 즉 원본 MD가 수정된 이후 상태를 기준으로 사실상 전체를 다시 인덱싱한다.
>
> `fixClosingFences`/`normalizeCodeBlocks`(코드 블록 언어 보정)는 의도적으로 재인덱싱에 포함하지 않는다 — 저장된 MD를 운영자가 직접 편집한 뒤 재인덱싱하면 코드 블록 내부의 의도된 빈 줄을 지우거나(`normalizeCodeContent`는 함수/클래스 시작·여러 줄 주석 시작 직전이 아닌 빈 줄은 삭제) 펜스 짝이 어긋난 입력에서 여는 펜스의 언어 태그를 잘못 벗길 수 있어, 매 재인덱싱마다 부작용으로 감수하기보다 필요할 때만 문서를 재업로드하도록 남겨둔 것이다.

### 3.5 설정 관리 (SettingsController)

조회(`GET /settings`)는 게스트에게도 열려 있다 — API 키 자체는 절대 노출하지 않고 "설정됨/없음" 배지만 보여준다.

수정 엔드포인트(`/admin/settings/**`)는 `/admin/**`과 동일한 인가를 상속한다:
- 평문 no-auth — 관리자 자동 주입
- 관리 전용 인증(`app.auth.management-only=true`) — 실제 로그인 필요
- 전체 인증 — 로그인 필요

화면에서도 편집 입력·저장/기본값 버튼은 `isAdmin`일 때만 렌더되고(그 외는 값만 표시), 서버 인가가 1차 방어선이다.

| Method | Path | 반환 | 설명 |
|--------|------|------|------|
| GET | `/settings` | `settings.html` | LLM/RAG 유효 설정 조회 페이지(프로바이더, 임베딩, 벡터 스토어, 검색·인덱싱 튜닝) |
| POST | `/admin/settings/update` | `fragments/settings-item :: item` | 핫 수정 가능 항목 하나에 오버라이드 저장(`key`, `value`) — 재기동 없이 다음 검색부터 반영, 감사 로그 기록 |
| POST | `/admin/settings/reset` | `fragments/settings-item :: item` | 오버라이드 삭제 → 프로퍼티 기본값으로 복귀, 감사 로그 기록 |
| POST | `/admin/settings/provider/toggle` | `fragments/settings-providers :: providers` | LLM 프로바이더 활성/비활성 토글(`name`, `enabled`) — `ProviderToggle`(메모리 전용, `settings_override`와 무관)이라 **재기동 시 초기화**됨. 이름이 같은 프로바이더는 함께 토글되고, 마지막 활성 프로바이더는 비활성화 거부(400). 감사 로그 기록 |

- 핫 수정 가능 항목만 `key`를 받아 수정할 수 있다:
  - **검색 튜닝**(다음 검색부터 반영) — 유사도 임계값·RRF 가중치/k·후보 배수·태그 후보 배수·멀티쿼리 최소 길이·재시도 시 후보 확대·topK·멀티쿼리 확장·하이브리드 검색
  - **인덱싱/청킹**(다음 인덱싱/↺ 재인덱싱부터 반영) — 청크 크기·오버랩·최소 크기·동시 파일 처리 수·동시 LLM 호출 수
  - **LLM**(다음 LLM 호출부터 반영, §6.18) — Direct 응답 temperature
  - 그 외 키(조회 전용: `rerank-enabled`·쿼리 임베딩 캐시 등)는 400(`IllegalArgumentException`)으로 거부된다.
- 값 검증 실패(범위 초과, 타입 불일치)도 400 — `GlobalExceptionHandler`가 처리.
- 재기동이 필요한 값(rerank/hybrid 활성화, 벡터 스토어 백엔드, 임베딩 차원, 일반 temperature·max-tokens 등)과 기본 라우팅 모드는 조회 전용으로만 노출된다(§6.18로 일반 temperature·max-tokens는 실제 config 값을 반영해 표시).
- **프로바이더 활성/비활성**(`/admin/settings/provider/toggle`)은 위 `key`/`value` 오버라이드 메커니즘과 별개다 — `settings_override`에 저장되지 않는 메모리 전용(`ProviderToggle`) 토글이라 **재기동하면 초기화**된다. LLM 라우팅 표의 각 행에서 관리자에게만 활성화/비활성화 버튼이 보인다. 표 본문 행 사이의 구분선은 CSS로 숨겨져 있다(`app.css`의 `#llm-providers tbody > tr > *`) — 헤더 밑줄만 남아 열 제목과 데이터를 구분한다.
- LLM 라우팅 카드는 `<hr>`로 두 구역을 나눈다: 위쪽은 라우팅 모드·temperature·max-tokens(LLM 자체), 아래쪽은 임베딩(모델·**접속 주소**(`settings.embeddingBaseUrl`, `app.embedding.base-url`/`EMBED_BASE_URL` 조회 전용)·차원). 임베딩 접속 주소는 채팅 LLM과 별도 엔드포인트일 수 있어(§6.21 로드밸런싱 등) 조회 전용으로만 노출된다.
- 상세는 [OPERATOR_MANUAL.md §6.5](OPERATOR_MANUAL.md#65-설정-페이지-settings--llmrag-옵션-조회핫-수정) 참고.

### 3.6 인증 (AuthController)

| Method | Path | 반환 | 설명 |
|--------|------|------|------|
| GET | `/login` | `auth/login.html` | 로그인 페이지 — `app.auth.enabled=true`(전체 인증) 또는 `app.auth.management-only=true`(관리 전용 인증, §6.17 B안)일 때 활성. 그 외(평문 no-auth)는 `redirect:/` |
| GET | `/signup` | `auth/signup.html` | 회원가입 페이지 |
| POST | `/signup` | redirect `/` 또는 오류 | 회원가입 처리 (성공 시 자동 로그인) |
| GET | `/setup` | `auth/setup.html` | 관리자 계정 초기 생성 페이지 (`app.auth.enabled=false` + DB에 admin 없을 때만 — management-only 서브모드 포함) |
| POST | `/setup` | redirect `/` 또는 오류 | 관리자 계정 생성 처리. management-only 모드에서는 생성 후 자동 로그인되지 않음 — `/login`에서 별도 로그인 필요 |

---

## 3.9 사이드바 대화 목록 항목 (thread-list.html / thread-item.html)

두 줄 구조 — `fragments/thread-list.html`(전체 목록, `GET /ui/threads`)과 `fragments/thread-item.html`
(항목 1건, `PATCH /ui/threads/{id}/title` 응답)이 **동일한 마크업**을 각자 들고 있어(부분 갱신 대상이 다름)
수정 시 항상 함께 바꿔야 한다:

```
{제목, 왼쪽 정렬}                              {날짜, 오른쪽 정렬}
[{버전}] {선택된 태그, 쉼표 구분 — 없으면 생략}
```

- **1행**: `justify-content-between` flex — 제목(`text-truncate small`, 기존 스타일 그대로)과 날짜
  (`.thread-date.text-muted`, `font-size:0.72rem`, 기존 스타일 그대로)를 양끝 정렬.
- **2행**: 버전 `[latest]` + 태그(있으면), 둘 다 1행의 날짜와 같은 폰트(`text-muted`, `0.72rem`)로 통일.
  태그가 없으면 두 번째 `<span>`이 아예 렌더링되지 않고 버전만 보인다(`th:if="${!thread.tagsDisplay().isEmpty()}"`).
- **제목의 `[버전]` 접두사 제거**: `ThreadMetaService`는 여전히 `title` 컬럼에 `"[{version}] {summary}"`를
  그대로 저장한다(하위 호환, DB 마이그레이션 없음) — 화면에는 `ThreadMeta.displayTitle()`이 선행
  `[..]` 브래킷을 정규식으로 잘라낸 값을 쓴다. 버전은 `ThreadMeta.version` 필드를 2행에서 직접 표시하므로
  중복되지 않는다.
- **태그는 스레드에 스냅샷 저장**: `thread_meta.tags`(`V3__thread_tags.sql`)는 그 스레드에서 **가장 최근에
  보낸 메시지의 태그 선택**을 담는다 — `ChatController`가 `/ui/chat`·`/ui/chat/stream` 양쪽에서 매 전송마다
  `ThreadMetaService.updateTags()`를 호출해 무조건 덮어쓴다(제목 생성과 달리 "커스텀이면 건너뛰기" 가드 없음).
  `ThreadMeta.tagsDisplay()`가 CSV를 `"tag1, tag2"`로 재조인해 보여준다.

---

## 4. 라우팅 전략 UI

### 사이드바 드롭다운

| 선택지 | RoutingMode | 비고 |
|--------|------------|------|
| 비용 우선 | COST_FIRST | 기본값 (`app.llm.default-routing-mode`) |
| 품질 우선 | QUALITY_FIRST | |
| 단계적 | PROGRESSIVE | 품질 미달 시 PREMIUM 자동 전환 |
| 병렬 비교 | DUAL | LOCAL 미연결 시 `disabled` |
| 로컬 전용 | LOCAL_ONLY | LOCAL 미연결 시 오류 발생 경고 |

변경 시 `PATCH /ui/threads/{threadId}/routing-mode` → `thread_meta.routing_mode` 저장.

> **드롭다운 전체 숨김**: 위 표는 DUAL/LOCAL_ONLY 개별 옵션이 `disabled`되는 경우고, `app.llm.default-routing-mode`(`LLM_ROUTING_MODE`)가 `LOCAL_ONLY`면 드롭다운 컨테이너 자체가 렌더링되지 않는다 — 배포에 LOCAL 프로바이더만 있어 어떤 모드를 골라도 결과가 동일하기 때문. 판단 기준은 대화별 `routingMode`가 아니라 **배포 전체의 기본 모드**다(`ChatController`의 `localOnlyDeployment` 모델 속성, `chat.html` `th:if="${!localOnlyDeployment}"`).

### 입력 바 상단 행 (버전 · 태그 칩 · 응답 모드)

한 행에 좌측부터 **버전 셀렉터 → 태그 칩 → (여백) → 응답 모드 토글**을 배치한다(`flex-grow-1` 스페이서로
응답 모드를 행 오른쪽 끝에 붙임).

> **태그 입력창은 없다**: 검색 스코프 태그는 이 행의 **칩을 클릭해서만** 토글한다(별도 텍스트 입력창 없음).
> 칩 토글 결과는 hidden 필드 `#chat-tags-input`(`name="tags"`)에 쉼표로 모여 전송되므로 서버 계약은 그대로다.

**태그 칩 목록의 `All`**: 칩 목록 맨 앞에 항상 `All` 칩이 붙는다. `All`을 클릭하면 태그 선택을 모두
비운다 — 태그가 하나도 선택되지 않은 기존 "미선택 = 전체 문서 검색" 의미론을 그대로 재사용하는 UI일 뿐,
서버에 별도의 "all" 태그가 존재하는 게 아니다. `chatSyncTagChips()`가 현재 선택 개수(0 = `All` 활성,
1개 이상 = `All` 비활성)로 매번 다시 계산하므로, 새 대화의 기본 상태(hidden 필드가 비어 있음)에서 `All`이
자동으로 활성 표시되고, 다른 태그를 하나라도 고르면 `All`이 자동으로 꺼진다 — 별도의 "기본 선택" 로직은
없다. 목록 자체는 `GET /api/v1/tags?excludeCommon=true`로 받아온다 — 스코프(버전) 내 **모든 문서에 공통인
태그**는 어차피 아무것도 좁히지 못하는 필터라 애초에 응답에서 빠진다(`KeywordSearchRepository
.distinctTagsExcludingCommon()` — doc_id별 태그 집합의 교집합을 계산해 제외; 태그가 하나도 없는 문서가
스코프에 있으면 교집합이 비어 아무것도 제외되지 않는다). 문서 업로드/편집 화면(`documents.html`)의 태그
제안 입력은 이 필터를 타지 않는 `excludeCommon` 없는 기본 호출을 그대로 쓴다 — 태그를 붙이는 쪽은 흔한
태그일수록 오히려 더 봐야 하기 때문이다.

**응답 모드 토글 (S/M/L)**: 콤보박스가 아니라 `.btn-check` 기반 3버튼 토글 그룹(`#response-mode-group` 안의
`#response-mode-s/m/l`) — 왼쪽에 `응답옵션`(`chat.response.group.label`) 라벨이 붙는다. 버튼 폭은 기본
`btn-group-sm`의 약 1.5배(`.response-mode-btn { min-width: 2.6rem }`, `app.css`)로 넓히고 `font-weight:700`으로
굵게 표시한다. 버튼에는 `S`/`M`/`L` 글자만 표시하고, 마우스를 올리면 상세 설명이 뜬다 — 단 네이티브 `title`
속성이 아니라 **Bootstrap Tooltip**(`data-bs-toggle="tooltip"` + `new bootstrap.Tooltip(el, {delay:{show:100,
hide:0}})`, 하단 스크립트에서 초기화)이다. 네이티브 title 툴팁은 브라우저 기본 지연(~1.5초)이 있어 체감상
너무 늦게 뜨는 문제가 있었고, `bootstrap.bundle.min.js`가 `layout/base.html`에 이미 로드돼 있어(Popper 포함)
추가 의존성 없이 전환 가능했다. 선택은 hidden 필드 `#form-response-mode`(`name="responseMode"`)에 동기화되고
`localStorage['chatResponseMode']`에 저장되어 재방문 시 복원된다 — HTMX 폼 전송과 SSE(`new FormData(form)`)
둘 다 이 hidden 필드를 그대로 집어간다.

| 선택지 | ResponseMode | 답변 성격 | 토큰 상한(`LLM_MAX_TOKENS` 대비) |
|--------|-------------|----------|------------------------------|
| S | `S` | 요약적이고 간단하게 | 15% |
| M | `M` | 쉽고 자세하게 | 40% (기본값) |
| L | `L` | 원문 최대한 살려 최대한 많이 | 90% |

**글자수 상한은 두지 않는다** — 답변 성격은 프롬프트 지시문(`prompt.answer.style.{s,m,l}`)이, 분량은 모드별
토큰 상한이 맡는다. 토큰 상한은 **블로킹 호출에만** 붙으므로(스트리밍은 기존 설계대로 무제한 — CLAUDE.md
참고) 스트리밍 채팅에서는 지시문이 유일한 조절 수단이다. `AnswerService.truncate()`의 20,000자 컷은 모드와
무관한 절대 상한으로 그대로 유지된다.

> **L은 RAG 전용**: `L`(원문 최대)은 검색된 문서 컨텍스트를 최대한 살리는 모드라 Direct(RAG 미사용) 모드에서는
> 의미가 없다 — RAG/Direct 토글(`#direct-mode-toggle`)이 켜지면 `#response-mode-l`이 `disabled`되어
> Bootstrap의 `.btn-check:disabled` 스타일로 흐리게 표시된다. 이 시점에 `L`이 선택돼 있었다면 자동으로
> `M`으로 되돌리고(`localStorage`도 갱신) 다시 RAG로 돌아가도 `L`을 자동 재선택하지는 않는다.

### 응답 메타데이터 (어시스턴트 버블 하단)

```
🤖 gemini-flash  ·  ⏱ 4.2초  ·  📥 1,284 tokens in  ·  📤 512 tokens out  ·  🔄 LLM 4회
```

PROGRESSIVE 업그레이드 시 `🔝 고추론 재분석 → {premiumProvider}` 배지 추가.

### DUAL 모드 탭 버블

```
[로컬 답변  local]  [외부 답변  gemini-flash]
────────────────────────────────────────────
(활성 탭 내용 + 출처 accordion + 메타데이터)
```

- `tabId` = UUID 앞 8자리 (다중 버블 충돌 방지, WebController에서 생성)
- 기본 탭: 로컬 답변 활성 / Bootstrap Tabs (서버 재요청 없음)
- LOCAL 미등록 시 드롭다운 `disabled` + "로컬 LLM이 필요합니다" 툴팁

### 출처 Hover 미리보기

**출처 라벨 형식**: `RetrievalService.formatSource()`가 청크 메타데이터의 `chapter_no`(H2~H6 헤딩 기반 계층 번호, 예: `1.5.3`)가 "0"이 아니면 `"파일명 | 1.5.3"`, 아니면(프롤로그·PPTX·비스캔 PDF — 이 세 경우는 chapter_no가 항상 "0") `page_or_slide`로 폴백해 `"파일명 | p.12"`로 표시한다 — 문서 버전은 라벨에 포함되지 않는다. **큐레이션 Q&A**(§10.10, 좋아요로 승격된 답변)가 출처로 포함된 경우엔 파일명·페이지가 없으므로 `"💬 큐레이션 Q&A"` 고정 라벨로 표시된다.

출처 목록 항목에 Bootstrap Popover (`hover focus` 트리거). `SourceRef.preview`에 청크 텍스트 앞 500자 포함.

**팝오버 크기 (`app.css`, ≥768px 전용)**: Bootstrap 기본값(`max-width: 276px`, `font-size: 0.875rem`)은 500자 미리보기가 세로로 길게 줄바꿈되어 가독성이 떨어졌다 — `max-width: 560px`(약 2배), `font-size: 0.8rem`으로 넓히고 살짝 줄여 같은 500자가 더 적은 줄로 읽기 좋게 표시된다. `@media (min-width: 768px)` 블록 안에 있어 모바일(<768px)은 Bootstrap 기본값 그대로 — 좁은 화면에서 팝오버를 더 넓히면 화면 밖으로 넘칠 여지가 있기 때문.

### 좋아요 피드백 & 큐레이션 Q&A 편집 (§10.10)

어시스턴트 버블 하단(피드백 컨트롤 영역, `.feedback-controls`)에 👍/👎 버튼과 함께 표시된다.

```
👍  👎  ✏(좋아요 상태일 때만)
```

| 동작 | 트리거 | 서버 반영 |
|------|--------|----------|
| 좋아요 | 👍 클릭(재클릭 시 취소) | `PATCH /ui/threads/{id}/turns/{turnId}/feedback` → 즉시 큐레이션 스냅샷 생성 + 3초 후 배경 임베딩 |
| 싫어요 | 👎 클릭 | 동일 엔드포인트 — 다음 대화 컨텍스트에서 해당 turn 제외(§6.8) |
| 큐레이션 답변 편집 | 좋아요 상태일 때만 노출되는 연필(✏) 아이콘 | `GET`/`PATCH /ui/threads/{id}/turns/{turnId}/curated` → 우측 오프캔버스에서 답변 텍스트 수정, 저장 시 자동 재임베딩 |

- 편집 아이콘은 **본인이 좋아요한 turn에서만** 보인다 — 채팅창은 항상 본인 스레드만 렌더링하므로 별도 권한 UI 분기가 없다.
- 좋아요/취소 클릭 시 JS가 서버 응답에 따라 편집 아이콘의 표시 여부도 함께 갱신한다(새로고침 불필요).
- 관리자용 전체 큐레이션 Q&A 관리(모든 사용자 대상)는 `/admin` 페이지에 별도로 있다 — [§3.4](#34-벡터-스토어-관리-admincontroller) 및 [OPERATOR_MANUAL.md §7.5](OPERATOR_MANUAL.md#75-큐레이션-qa-관리-1010) 참고.
- 동작 원리(디바운스, 재임베딩, 문서 재인덱싱/대화 삭제와의 관계)는 [OPERATOR_MANUAL.md §6.7](OPERATOR_MANUAL.md#67-큐레이션-qa-좋아요-기반-지식-승격-1010) 참고.

---

## 5. HTMX 인터랙션 흐름

```
[채팅 전송]  chat-stream.js가 form submit을 capture 단계에서 가로챔
              → fetch POST /ui/chat/stream (text/event-stream)
              → ReadableStream으로 SSE 이벤트 파싱
              → stage 이벤트: 단계 배지 교체 (classifier/retrieval/answer/critic/upgrade)
              → sources 이벤트: Bootstrap Popover 출처 배지 삽입
              → token 이벤트: 텍스트 실시간 누적 (DUAL: tab 라우팅)
              → done 이벤트: marked.js 마크다운 렌더 + 메타데이터 footer 표시
                             + htmx.trigger(body, "refreshThreadList")
              → error 이벤트: 오류 버블 교체
              hx-post="/ui/chat" 속성은 JS 비활성 시 fallback으로 유지
              → DUAL: message-assistant-dual / 단일: message-assistant
              → HX-Trigger: "refreshThreadList" (사이드바 자동 갱신)

[제목 수정]  더블클릭 → 인라인 input → 포커스 아웃/Enter
              → hx-patch="/ui/threads/{id}/title" → fragments/thread-item

[대화 목록]  항목 클릭 → GET /chat/{threadId} → 전체 페이지 전환

[파일 업로드] Fetch API → POST /ui/documents/upload → 202 {taskId}
              → GET /ui/documents/progress/{taskId} SSE 구독
              → progress 이벤트로 파일별 상태 갱신(로드→구조화→이미지 분석→교정→청킹→키워드→저장,
                단계별 진행률 바 + "이미지 분석 중 (N/M)" 등 메시지) → done/error 이벤트 후 목록 자동 새로고침

[문서 삭제]  hx-delete → hx-swap="outerHTML swap:0.3s" (페이드아웃)
[LLM 카드]   hx-trigger="load, every 30s" → 30초마다 자동 갱신
[LLM orphan 삭제] 🗑 버튼(orphan 카드에만 노출) → hx-confirm 확인 → hx-delete="/admin/llm-usage/{provider}"
              → hx-target="#llm-cards-target" → 응답으로 받은 fragments/llm-usage-cards로 즉시 교체
              (카드만 즉시 반영; 차트·기간별 표는 별도 vanilla JS fetch라 다음 로드/새로고침에 반영)

[큐레이션 Q&A 카드] <details id="curated-qa-card"> 첫 펼침(브라우저 native toggle 이벤트, this.open=true)
              → hx-trigger="toggle[this.open] once" → GET /admin/curated
              → hx-target="#curated-qa-body" → fragments/admin-curated::panel 삽입
              (htmx 트리거는 최초 1회만 — 접었다 다시 펴도 재조회하지 않음, 새로고침 시 초기화)
[큐레이션 Q&A 페이지네이션] 이전/다음 버튼, 페이지당 건수 드롭다운 → loadCurated(offset, limit)
              (페이지 레벨 JS, htmx 아닌 plain fetch) → GET /admin/curated?offset=&limit=
              → #curated-qa-body.innerHTML 교체 (몇 번이든 재호출 가능, 위 최초-1회 제약과 무관)
```

---

## 6. 세션 관리

| 데이터 | 저장 위치 | 생명주기 |
|--------|----------|---------|
| `threadId` (현재 선택) | HTTP 세션 (`HttpSession`) | 브라우저 세션 |
| 대화 이력 텍스트 | SQLite `conversation_turns` | 영속 |
| 대화 제목·버전·라우팅 모드 | SQLite `thread_meta` | 영속 |
| 선택 테마 (light/dark) | `localStorage` | 브라우저 영속 |

대화 제목은 첫 질문 직후 비동기(Virtual Thread)로 LLM 요약 생성 후 업데이트.

---

## 7. 에러 처리

| 상황 | 처리 방식 |
|------|----------|
| 채팅 API 오류 | `message-error.html` fragment (빨간 버블) |
| 파일 업로드 실패 | 파일 행 상태 ❌ + 오류 토스트 |
| 문서 삭제 실패 | `htmx:responseError` → 오류 토스트 |
| DUAL, LOCAL 미연결 | 드롭다운 `disabled` + 툴팁 |
| LOCAL_ONLY, LOCAL 미연결 | 빨간 버블 + `LlmProviderExhaustedException` 메시지 |
| 동시 사용자 급증으로 프로바이더 용량 초과 (§6.12, 429) | 빨간 버블 + "현재 요청이 몰려 있습니다. 잠시 후 다시 시도해 주세요." — 서킷브레이커 전면차단이 아니라 일시적 대기 상한 초과이므로 잠시 후 재시도하면 대개 성공 |
| 빈 질문 전송 | 클라이언트 validation, API 호출 차단 |
| 대용량 파일 | 업로드 전 200 MB 초과 감지 → 즉시 오류 표시 |

---

## 8. SSE 스트리밍

`POST /ui/chat/stream` → `SseEmitter(180s)` → Virtual Thread에서 `StreamingAgentService.run()` 실행.

```
stage(classifier) → stage(retrieval) → sources → stage(answer) → token × N
→ stage(critic) → [stage(answer) × retry] → done
```

### SSE 이벤트 스펙

| event | data | 설명 |
|-------|------|------|
| `stage` | `{"id":"retrieval","text":"관련 문서 검색 중..."}` | 노드 진입 시 배지 교체 |
| `sources` | `[{"label":"...","preview":"..."}]` JSON 배열 | RETRIEVAL 완료 후 출처 배지 삽입 |
| `token` | `{"tab":null,"text":"텍스트 조각"}` | ANSWER 스트리밍 토큰; DUAL은 tab="local"/"external" |
| `done` | 메타데이터 JSON (`usedProvider`, `inputTokens`, `elapsedMs` 등) | 완료 시 마크다운 렌더 |
| `error` | `{"message":"오류 설명"}` | 오류 버블로 교체 |

### 컴포넌트

| 파일 | 역할 |
|------|------|
| `service/StreamingAgentService.java` | SSE 파이프라인 오케스트레이터; `AgentGraph.runStreaming()` 호출 |
| `service/GraphListener.java` | 노드/토큰/출처 이벤트 hook 인터페이스 (`NOOP` 상수로 동기 경로 오버헤드 0) |
| `agent/AgentGraph.java` | `runStreaming(state, listener)` 메서드 — `AnswerService.executeStreaming()` 호출 |
| `service/AnswerService.java` | `executeStreaming(state, listener)` — `ChatClient.stream()` Flux 구독 → `listener.onToken()` |
| `static/js/chat-stream.js` | 클라이언트 SSE 파서; form submit capture, 버블 DOM 생성, 이벤트별 핸들러 |

### PROGRESSIVE / DUAL / 재시도 처리

- **PROGRESSIVE 업그레이드**: `listener.onUpgrade(provider)` → `stage(id=upgrade)` 이벤트, 콘텐츠 div 초기화 후 premium 답변 재채움
- **DUAL 모드**: `onToken(tab, text)` → `stream-ext-{id}` / `stream-loc-{id}` 탭 div로 분리 라우팅
- **재시도**: ANSWER 노드 2회 이상 진입 → 콘텐츠 div 초기화 → 새 답변으로 채움

---

## 9. 모바일 / PWA / 접근성

### 9.1 반응형 레이아웃

| 영역 | 구현 |
|------|------|
| 사이드바 | `chat.html` 사이드바에 `offcanvas-md offcanvas-start` — **≥md**: 고정 컬럼(280px), **<md**: 햄버거(`#threadDrawer`)로 여는 슬라이드 드로어. 드로어 헤더 닫기 버튼은 `d-md-none` |
| 입력창 고정 | 외곽 `.chat-shell`(`height: calc(100dvh - 56px)`) + `#chat-messages`를 `flex:1 1 auto; min-height:0; overflow-y:auto`로 두어 메시지만 스크롤되고 입력창은 하단 고정 |
| 테이블 넘침 | `documents.html` 두 테이블을 `.table-responsive`로 래핑(가로 페이지 스크롤 제거) |
| 차트 넘침 | `llm-usage.html` 차트를 `height:280px` 컨테이너 + Chart.js `maintainAspectRatio:false` |
| iOS 자동 확대 | `@media (max-width:767.98px)`에서 모든 폼 컨트롤 `font-size:16px` |
| 출처 미리보기 팝오버 | `@media (min-width:768px)`에서 팝오버 폭 확대(`max-width:560px`, `<768px`는 기본값 유지) — 배경·수치는 [§4 출처 Hover 미리보기](#출처-hover-미리보기) 참고 |

> ⚠️ Bootstrap `.offcanvas-md`는 ≥md에서 `background-color:transparent!important`를 강제한다. 데스크톱 사이드바 배경은 `app.css`에서 `.sidebar.offcanvas-md { background-color: var(--bg-elevated) !important }`로 복구(라이트/다크 변수 일치).

### 9.2 PWA

| 파일 | 역할 |
|------|------|
| `static/manifest.webmanifest` | 앱 이름·아이콘·`display:standalone`·theme/background color. `WebConfig`에서 `.webmanifest` → `application/manifest+json` MIME 매핑 |
| `static/sw.js` | **NETWORK-FIRST**. GET 내비게이션만 가로채 네트워크 실패 시 `offline.html` 제공. RAG 답변·HTMX 프래그먼트·SSE·인증 응답은 **캐시하지 않음**(프라이버시/쿠키 안전). 폼 POST(로그인 등)는 미가로챔 |
| `static/offline.html` | 자체 완결 오프라인 fallback(다크모드 대응, 다시시도 버튼) |
| `static/icons/icon.svg` | 앱 아이콘 (SVG `any maskable`) |

- `base.html` `<head>`에 manifest/theme-color/apple-touch-icon meta, 본문 하단에 SW 등록 + iOS Safari "홈 화면에 추가" 1회 힌트 토스트(`localStorage` 플래그).
- `SecurityConfig` permitAll에 `/manifest.webmanifest`, `/sw.js`, `/offline.html`, `/icons/**` 추가(auth 모드).

### 9.3 접근성

- 아이콘 전용 버튼(햄버거·드로어 닫기·전송·테마·로그아웃·navbar 토글)에 i18n `aria-label`(`th:attr="aria-label=#{...}"`).
- 모바일 `pointer:coarse`에서 아이콘 버튼 최소 44×44px 터치 영역.
- `:focus-visible` 키보드 포커스 인디케이터, `prefers-color-scheme` 자동 감지(기존 유지).
