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
│   ├── layout/base.html                   # navbar, dark mode toggle, scripts, showToast(),
│   │                                      #   renderMarkdownWithImageMarkers() — [이미지: …] 마커를
│   │                                      #   <img>로 치환해 marked→DOMPurify 렌더하는 전역 유틸(§3.5-bis)
│   ├── chat.html                          # 채팅 페이지 (이전 turn 서버 렌더 포함)
│   ├── documents.html                     # 문서 관리 페이지
│   ├── admin.html                         # 벡터 스토어 관리 (청크 브라우저 + 큐레이션 Q&A + 청크 추가 제안)
│   ├── curated-submissions.html           # 청크 추가 게시판 (등록 폼 + 이미지 업로드 + "내 제안" 목록)
│   ├── llm-usage.html                     # LLM 사용량 통계 페이지
│   ├── settings.html                      # LLM/RAG 설정 조회·핫 수정 페이지
│   └── fragments/
│       ├── message-user.html              # 사용자 메시지 버블
│       ├── message-assistant.html         # 어시스턴트 버블 (메타데이터 포함)
│       ├── message-error.html             # 오류 버블
│       ├── thread-list.html               # 대화 목록 사이드바
│       ├── thread-item.html               # 대화 목록 항목 1건
│       ├── doc-table-body.html            # 문서 목록 tbody (새로고침용)
│       ├── admin-chunks.html              # 청크 테이블 (컬렉션/문서 필터 + 페이지네이션)
│       ├── admin-curated.html             # 큐레이션 Q&A 패널 (펼칠 때 지연 로딩)
│       ├── admin-submissions.html         # 청크 추가 제안 검토 패널 (지연 로딩, 상태 필터)
│       ├── llm-usage-cards.html           # 프로바이더 + 임베딩(EMBEDDING) + orphan(ORPHAN, 삭제 가능) 상태 카드 (30초 자동 갱신)
│       ├── settings-item.html             # 설정 항목 1행(조회 또는 편집 입력 + 저장/기본값 버튼) — HTMX 부분 갱신 대상
│       └── settings-providers.html        # LLM providers 표(활성화 배지 + 관리자 활성/비활성 버튼) — settings.html에 인라인 포함 + 토글 응답 시 테이블 전체 교체
└── static/
    ├── css/app.css                        # 버블·배지·타이핑·반응형(오프캔버스/dvh/16px/44px)
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
| POST | `/ui/chat` | `fragments/message-assistant` | 질문 전송 (동기 fallback) |
| POST | `/ui/chat/stream` | `text/event-stream` (SseEmitter) | SSE 스트리밍 응답 — `chat-stream.js`가 사용 |
| POST | `/ui/chat/stream/skip-images` | `204` | 현재 스트리밍 중인 턴의 쿼리 시점 이미지 분석(Lazy Vision) 대기를 건너뜀(`threadId` 파라미터) — 턴 전체를 끊는 `/ui/chat/stream`의 abort/중지와는 별개, 아래 §8 참고 |
| GET | `/api/v1/questions/suggest` | JSON 배열 | 질문 입력 중 추천 목록 조회 (`q`, `limit`; 서버는 항상 shared 기준 처리) |
| POST | `/api/v1/questions/reuse` | JSON | 추천 항목 재사용 시도. 반환 직전 출처 청크 유효성 재검증 후 성공 시 `reused=true`, 실패 시 `fallback=true`로 일반 질의 전환 신호 반환 |
| POST | `/ui/chat/new` | redirect `/chat/{newId}` | 새 대화 생성 |
| PATCH | `/ui/threads/{threadId}/title` | `fragments/thread-item` | 대화 제목 수정 |
| PATCH | `/ui/threads/{threadId}/routing-mode` | `204` | 대화별 라우팅 모드 저장 |
| DELETE | `/ui/threads/{threadId}` | `200` | 대화 삭제 |
| DELETE | `/ui/threads/{threadId}/turns/{turnId}` | `204` | 턴 하나(질문+답변) 삭제 — 싫어요 직후 확인 대화상자에서만 호출된다(§ 피드백). 소유권은 피드백 엔드포인트와 같은 `getFeedback(userId, threadId, turnId)` 스코핑이라 남의 턴·없는 턴 모두 `404` |
| GET | `/ui/threads` | `fragments/thread-list` | 대화 목록 새로고침 |

### 3.2 문서 관리 (DocumentController)

| Method | Path | 반환 | 설명 |
|--------|------|------|------|
| GET | `/documents` | `documents.html` | 문서 관리 페이지 |
| POST | `/ui/documents/upload` | 202 `{"taskId":"..."}` | 파일 업로드 수신 → 비동기 인덱싱 시작 |
| GET | `/ui/documents/progress/{taskId}` | `text/event-stream` (SSE) | 인덱싱 진행 이벤트 (`stage`, `done`, `error`) |
| GET | `/ui/documents/progress/{taskId}/status` | JSON (`IndexingProgressEvent`) | 1회성 상태 조회 — SSE를 새로 열지 않고도 마지막 상태(`buffers`에 있으면 그 이벤트, 워커만 등록돼 있으면 `running`, 둘 다 없으면 `unknown`)를 즉시 확인 |
| DELETE | `/ui/documents/{docId}` | `200` | 문서 삭제 |
| GET | `/ui/documents/{docId}/export` | 바이너리(MD/TXT/DOCX, MD+이미지는 ZIP) | 현재 색인된 청크로 문서를 재구성해 다운로드(§ 문서 내보내기, [OPERATOR_MANUAL.md §6.8](OPERATOR_MANUAL.md#68-문서-내보내기) 참고) — 관리자 전용 |
| GET | `/ui/documents/list` | `fragments/doc-table-body` | 문서 목록 새로고침 |

> **관리 전용 인증 모드**(`app.auth.management-only=true`, §6.17 B안)에서는 `POST /ui/documents/upload`, `POST /ui/documents/progress/*/cancel`, `DELETE /ui/documents/{docId}`, `PATCH /ui/documents/{id}/tags`, `GET /ui/documents/{id}/tags/edit`, `GET /ui/documents/{id}/export`가 `hasRole("ADMIN")`로 게이트된다 — 비로그인은 `/login` 리다이렉트, 관리자 아닌 로그인은 403. `GET /documents`·`GET /ui/documents/list`·태그 조회는 게스트에게 그대로 열려 있다. 자세한 내용은 [OPERATOR_MANUAL.md §9.4.2](OPERATOR_MANUAL.md#942-관리-전용-인증-management-only) 참고. 내보내기는 읽기 동작이지만 문서 전체를 한 번에 반출하는 벌크 기능이라 이 그룹에 포함됐다.
>
> **인덱싱 진행 스테이지**(`stage` 값): `loading` → `structuring`(TXT만) → `describing_images`(Vision 이미지 분석, "이미지 설명 추가" 체크 시만 — "이미지 분석 중 (N/M)") → `correcting`(DOCX/TXT/MD/PPTX/PDF[비스캔]) → `chunking` → `enriching` → `storing` → `done`/`error`/`cancelled`. 각 이벤트는 `stage`와 함께 `done`/`total`/`filename`/`message`를 실어 나르며, `documents.html`의 `stageHtml`/`STAGE_LABELS`가 단계별 진행률 바와 오류 로그 라벨을 렌더링한다. 상세는 [PIPELINE.md §6.3](PIPELINE.md#63-docx--md--임베딩-db-저장-상세-이미지-포함) 참고.
>
> **연결 재접속 처리**(`IndexingProgressService`): 인덱싱은 (특히 로컬 LLM이 느릴 때) 수 시간까지 걸릴 수 있어, 그 사이 브라우저의 SSE 연결이 끊겼다 재접속하는 일이 드물지 않다. 마지막으로 알려진 상태(진행 중 이벤트 이력 포함)는 **4시간**(코드 상수, 프로퍼티화되어 있지 않음) 보관되므로 재접속 시점에도 실제 결과(`done`/`error`/`cancelled`)를 그대로 재생해 받을 수 있다. 그보다 오래 끊겨 있었다면(4시간 초과) 서버는 조용히 연결만 열어두는 대신 **`unknown`** 종결 이벤트를 즉시 보낸다 — `documents.html`은 이를 실패가 아닌 "상태 확인 불가"(⚠️, 문서 목록에서 직접 확인 권장)로 표시하고, 순차 업로드 루프도 다음 파일로 넘어간다. 이 처리 이전에는 만료된 taskId로 재접속하면 heartbeat만 오갈 뿐 아무 이벤트도 오지 않는 "좀비 연결" 상태로 남아, 화면이 영구히 "처리 중"에 멈추고 이후 파일 업로드까지 함께 멈추는 문제가 있었다(순차 업로드 루프가 그 `await`에서 빠져나오지 못함).
>
> **"소제목 숫자 생성" 체크박스 자동 기본값**(`documents.html`, 순수 클라이언트 로직, 서버 API 변경 없음): 파일을 선택할 때마다(드래그앤드롭·파일 선택창 둘 다 `handleFiles()` 경유) `syncHeadingNumbersCheckbox()`가 재계산한다 — 선택된 파일에 PPTX가 하나라도 있으면 체크 해제, 없으면(PDF 포함) 체크. 파일을 지울 때(`removeFile()`)도 남은 구성 기준으로 다시 계산된다. 이 기본값은 `DocumentIndexer`의 `.pptx` 분기가 체크박스 상태와 무관하게 항상 `addHeadingNumbers=false`를 넘기는 서버 동작(§3.3 [OPERATOR_MANUAL.md](OPERATOR_MANUAL.md#소제목-숫자-생성-addheadingnumbers) 참고)을 UI에 미리 반영한 것이며, PDF는 `PdfToMarkdownConverter`가 헤딩 자체를 만들지 않아 체크해도 무해하므로 자동 해제 대상에서 제외된다. PPTX와 다른 형식이 같은 배치에 섞이면 업로드가 배치 전체에 값 하나만 전송하는 구조라 `warnIfPptxMixed()`가 토스트 경고를 띄운다(업로드 자체는 막지 않음). 둘 다 기본값 제안일 뿐이라 체크박스는 언제든 수동으로 바꿀 수 있다.
>
> **문서 내보내기 다이얼로그**(`documents.html`, `openExportDialog()`/`runExport()`): 행의 **내보내기** 버튼(`fragments/doc-table-body.html`, `th:if="${isAdmin}"`)을 누르면 형식(MD/TXT/DOCX 라디오, 기본 MD)과 옵션(이미지 설명 포함 — 기본 켬, 소제목 번호·목차 추가 — 기본 끔) 선택 모달이 뜬다. 파일명이 `.pptx`로 끝나면 소제목 번호·목차 체크박스가 `disabled`되고 이유가 `form-text`로 표시된다(서버의 PPTX 제외 규칙을 UI에 미리 반영 — §3.3의 소제목 번호 체크박스 자동 해제와 같은 패턴). **내보내기** 클릭 시 `fetch()`로 `GET /ui/documents/{docId}/export`를 호출해 응답을 `Blob`으로 받아 `<a download>`로 저장한다 — `location.href` 이동 대신 `fetch`를 쓴 이유는 실패 시(예: `IllegalArgumentException` → 400) 브라우저가 오류 페이지로 넘어가는 대신 `ProblemDetail` JSON의 `detail`을 읽어 토스트로 보여주기 위해서다. 다운로드 파일명은 `Content-Disposition` 헤더에서 RFC 5987 `filename*=UTF-8''...` 형식을 우선 파싱하고(한글 파일명 대응), 없으면 `filename="..."` 폴백을 쓴다.

### 3.3 운영 / LLM 사용량 (OperationsController)

| Method | Path | 반환 | 설명 |
|--------|------|------|------|
| GET | `/llm-usage` | `llm-usage.html` | LLM 사용량 페이지 |
| GET | `/ui/llm-usage/cards` | `fragments/llm-usage-cards` | 카드 HTMX 자동 갱신(30초). 채팅 프로바이더 + 임베딩(`embed:<model>`, `EMBEDDING` 배지) + orphan(설정에 없는 이름, `ORPHAN` 배지 + 삭제 버튼) 카드 포함 |
| DELETE | `/admin/llm-usage/{provider}` | `fragments/llm-usage-cards` | orphan 프로바이더의 누적 사용 기록 삭제. `/admin/**` 경로 아래 있어 `ROLE_ADMIN` 전용(no-auth 모드는 관리자 자동 인증 상속) — 컨트롤러는 `OperationsController` 소속, 경로만 admin 네임스페이스 |
| GET | `/ui/threads/{threadId}/turns/{turnId}/curated` | JSON `{"answer":"..."}` | §10.10 — 본인 좋아요 답변의 현재 큐레이션 텍스트 조회(채팅 인라인 편집창 채우기용). 소유권은 기존 피드백 엔드포인트와 동일하게 `(userId, threadId)` 스코프로 검증 |
| PATCH | `/ui/threads/{threadId}/turns/{turnId}/curated` | `204` | §10.10 — 본인 좋아요 답변의 큐레이션 텍스트 수정(`answer` 폼 파라미터) → 저장 즉시 백그라운드 재임베딩. 관리자 권한 불필요 — thread 자체가 사용자별로 격리되어 있어 본인 turn만 접근 가능 |
| GET | `/api/v1/llm/concurrency` | JSON `{"available":true,"inUse":N,"capacity":N}` 또는 `{"available":false}` | 헤더의 **LLM 동시성** 표시가 폴링하는 REST 엔드포인트. `role=LOCAL, priority=1`(우선 처리 계층 — MICRO_TEXT 전용 `priority=0` 소형 모델은 제외)이면서 현재 가용한(등록됨+서킷브레이커 미차단+런타임 비활성화 안 됨) 프로바이더들의 concurrency 합계가 `capacity`, 실제 사용 중인 permit 수가 `inUse`. 그런 프로바이더가 하나도 없으면 `available=false`만 반환(다른 필드 생략) — 로컬 LLM이 없는 배포에서는 지표 자체가 무의미하므로 |

REST API: `GET /api/v1/llm/usage`, `GET /api/v1/llm/usage/history?days=N` — 둘 다 임베딩·orphan 항목 포함(상세는 [OPERATOR_MANUAL.md](OPERATOR_MANUAL.md) 참고)

> **네비게이션 바 상태 표시**(`layout/base.html`, 모든 페이지 공통 헤더): 우측 상단에 **API 상태**(`#api-status`)와 그 옆 **LLM 동시성**(`#llm-concurrency`, `LLM: {inUse}/{capacity}`) 두 지표가 나란히 표시된다. API 상태는 페이지 로드 시 `GET /api/v1/health`를 딱 1회만 확인하고 이후 재확인하지 않는 반면, LLM 동시성은 `setInterval`로 위 엔드포인트를 **~3초마다** 재조회해 값을 갱신한다 — 이 문서의 다른 폴링(HTMX `hx-trigger="every Ns"`)과 달리 재사용할 만한 3초 간격 폴링이 기존에 없어 `base.html` 자체의 순수 JS `fetch`+`setInterval`로 새로 구현했다. 응답이 `available:false`면 `.d-none`으로 엘리먼트 자체를 감춘다(값이 없는데 `0/0`처럼 표시되는 것을 방지) — `fetch` 실패 시에도 동일하게 숨김 처리된다.
>
> **헤더 알림 배지 2종**(`layout/base.html`, 청크 추가 게시판): 네비의 **지식 제안** 링크 옆에 작성자 본인의 미확인 처리 건수(`#my-submission-badge` ← `GET /curated/submissions/unread-count`), **관리자** 링크 옆에 검토 대기 건수(`#pending-submission-badge` ← `GET /admin/submissions/pending-count`)가 빨간 배지로 붙는다. 둘 다 **60초 폴링**이며(위 LLM 동시성 지표의 3초와 달리 게시글은 초 단위 신선도가 불필요) 0건이면 `.d-none`으로 감춘다. 관리자 배지는 `isAdmin`일 때만 렌더링되고, 폴링 스크립트는 **엘리먼트가 있을 때만** 시작하므로 비관리자 브라우저에서는 요청 자체가 나가지 않는다. 로그인 직후 첫 폴링이 바로 실행되므로 "관리자가 로그인하면 알림"이 함께 충족된다.
>
> **`inUse`가 채팅 요청만이 아니라 임베딩 활동·서킷브레이커 차단까지 반영한다**: `inUse`는 채팅 동시성 게이트 사용량 + `EmbeddingConcurrencyTracker`(인덱싱·검색 임베딩 in-flight 카운터, 채팅 게이트와 완전히 별개의 `EmbeddingModel` 데코레이터 체인이라 이게 없으면 임베딩 중에도 항상 0으로 보였다)를 합산하고, `capacity`를 넘지 않게 clamp된 값이다(임베딩 동시성은 `EMBED_MAX_CONCURRENT_BATCHES` 등 별도 한도라 합산 결과가 capacity를 초과할 수 있음). 서킷브레이커로 차단된 로컬 프로바이더는 `capacity`에는 그대로 남되 전체 용량이 `inUse`로 집계된다(제외되는 게 아니라 "완전 포화"로 표시됨). `inUse`가 `capacity`에 도달하면(즉 값이 같아지면) 헤더 스크립트가 숫자에 Bootstrap `text-danger`+`fw-bold`를 토글해 굵은 빨간 글씨로 강조한다.

### 3.3-bis 채팅 입력 추천 UI

`chat.html` 입력 영역에 `#question-suggest-box`가 표시되며 추천 범위는 항상 shared로 동작한다.

- 입력값이 2글자 이상이면 220ms 디바운스로 `/api/v1/questions/suggest`를 호출한다.
- 추천 클릭 시 `/api/v1/questions/reuse`를 호출하며 `turnId`, `threadId`, `version`을 보낸다(서버는 shared 기준 처리).
- 재사용 성공(`reused=true`)이면 페이지 새로고침 없이 사용자 버블 + 어시스턴트 버블을 즉시 렌더링하고 provider 배지에 `db-reuse`를 표시한다.
- 재사용 실패(`fallback=true`)면 토스트 안내 후 질문 입력창에 질문을 채워 일반 질의를 바로 전송한다.
- Direct 모드 질문은 `feedback='LIKE'`인 항목만 추천/재사용 후보가 된다.
- `Esc`, 전송(Enter), blur 시 추천 목록을 닫는다.

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
| POST | `/admin/documents/{docId}/reindex` | 202 `{"taskId":"..."}` | 저장된 MD로 재인덱싱 (DOCX/TXT/MD/PPTX/PDF 전용, 스캔 PDF 제외 — 스캔 PDF는 MD 변환 없이 OCR로 바로 인덱싱되어 재사용할 MD 파일이 없다). 진행률은 업로드와 **같은** `GET /ui/documents/progress/{taskId}` SSE를 그대로 쓴다(위 §3.2 재접속 처리도 동일 적용) — `admin.html`의 `subscribeReindexProgress()`는 연결 오류 시 즉시 포기하지 않고 최대 5회까지 재시도하며, `unknown` 종결 이벤트를 받으면 "새로고침하여 확인" 경고로 표시한다 |
| GET | `/admin/curated` | `fragments/admin-curated :: panel` | §10.10 — 큐레이션 Q&A 패널 지연 로딩 프래그먼트. `offset`(기본 0)·`limit`(기본 20, 20/50/100) 쿼리 파라미터로 페이지네이션. `/admin` 페이지 자체는 이 데이터를 조회하지 않고, 카드를 처음 펼칠 때만(`<details>` `toggle` 이벤트) HTMX로 호출되며, 이후 페이지 이동·페이지당 건수 변경은 `loadCurated()`(페이지 레벨 JS, plain fetch)가 같은 엔드포인트를 다시 호출함 |
| GET | `/admin/curated/{id}/detail` | JSON | §10.10 — 큐레이션 Q&A 항목의 질문·답변 조회 (편집 패널) |
| POST | `/admin/curated/{id}` | `200` | §10.10 — 큐레이션 Q&A 답변 수정 → 재임베딩. 좋아요를 누른 사용자와 무관하게 관리자가 어떤 항목이든 편집 가능 |
| DELETE | `/admin/curated/{id}` | `200` | §10.10 — 큐레이션 Q&A 강제 삭제(비활성화+de-index). 좋아요 주체의 동의 없이도 관리자가 제거 가능(모더레이션). 사용자 제안에서 온 행이면 **같은 제안의 모든 청크가 함께** 내려간다(전부/전무) |
| GET | `/admin/retrieval-metrics` | `fragments/admin-retrieval-metrics :: panel` | 3단계 — 턴별 검색 진단 수치 패널 지연 로딩(`offset`/`limit`, 기본 20). **읽기 전용**이며 사용자 스코프가 아니다(배포 전체의 검색 동작을 보는 운영자 뷰, `/admin/**`의 ROLE_ADMIN 게이트 상속) |
| GET | `/admin/submissions` | `fragments/admin-submissions :: panel` | 청크 추가 제안 검토 패널 지연 로딩. `status`(기본 `pending`, `all`=전체)·`offset`·`limit` 파라미터. 큐레이션 패널과 동일한 `<details>` + `toggle once` 패턴 |
| GET | `/admin/submissions/pending-count` | JSON `{"count":N}` | 검토 대기 건수 — 헤더 배지·카드 pill이 60초마다 폴링. **`/api/v1/**`이 아니라 `/admin/**` 아래**에 둔 이유는 아래 참고 |
| GET | `/admin/submissions/{id}/detail` | JSON | 제안 전문(제목·본문·태그·작성자·상태·예상 청크 수) — 검토 오프캔버스 채우기용 |
| POST | `/admin/submissions/{id}/approve` | `200 {"curatedId":N}` / `400` / `409` | 임베딩 실행. body의 `title`/`body`/`tags`는 관리자 수정본(생략 시 작성자 원문 유지). 이미 처리된 제안이면 409, 이미지 개수 상한 초과면 400(메시지 포함 — UI가 서버 문구를 그대로 토스트로 띄운다). **본문에 이미지가 있으면 이 요청이 이미지 수만큼의 Vision 호출을 동기로 기다린다**(설명이 임베딩되는 텍스트의 일부여야 해서 배경으로 미룰 수 없다) — 그동안 `임베딩 실행` 버튼은 잠기고 스피너로 바뀐다 |
| POST | `/admin/submissions/{id}/reject` | `200` / `409` | 거부 — body의 `reason` 필수(작성자에게 전문 노출) |

> 상태 카드는 `AdminService.vectorStoreView()` → `VectorStoreAdminView`. 백엔드별 표시 차이는 [OPERATOR_MANUAL.md §7.4](OPERATOR_MANUAL.md) 참고.
>
> **큐레이션 Q&A 카드**(`/admin` 하단, §10.10): 기본적으로 접힌 `<details>` 카드이며, 처음 펼칠 때만(`hx-trigger="toggle[this.open] once"` → `GET /admin/curated`) 좋아요로 승격된 질문·답변을 최신순으로 조회해 표시한다 — `AdminController.adminPage()`는 더 이상 `curatedQaService.listActive()`를 즉시 호출하지 않으므로 `/admin` 페이지 로드 자체는 이 조회를 하지 않는다. 페이지당 건수는 20/50/100 중 선택(기본 20 — `AdminController.curatedPanel()`의 `limit` 기본값), 이전/다음 버튼으로 페이지 이동한다(`CuratedQaRepository.findAllActive(offset, limit)`) — 이전의 고정 상한 50건·페이지네이션 없음 방식에서, 큐레이션 항목이 계속 쌓여도 패널이 무거워지지 않도록 청크 목록과 동일한 페이지네이션 UI로 전환됐다. 편집(연필 아이콘)은 저장 시 자동 재임베딩되는 점이 위 청크 편집과 다르다 — 청크 편집은 원본 벡터를 그대로 유지하지만, 큐레이션 Q&A 편집은 검색 정확도가 목적이라 항상 재임베딩된다. **편집 오프캔버스는 청크 편집과 동일한 조건·동일한 렌더러로 좌측 미리보기 컬럼을 띄운다**(`window.innerWidth >= EDIT_BASE_WIDTH * 2`일 때만, `renderChunkPreview()` → `renderMarkdownWithImageMarkers()`, 입력 200ms 디바운스) — 큐레이션 답변은 표·코드블록·이미지 마커를 포함한 마크다운이 그대로 검색 근거가 되므로 원문만 보고 고치면 서식이 깨진 것을 알아채기 어렵다. 좁은 화면은 기존 단일 컬럼 그대로다. 질문 앞에 노란 ⚠ 배지가 보이면 `embed_status='failed'`(전체+핵심 섹션 재시도 모두 실패, `CuratedQaService.tryEmbedWithFallback()`) — 해당 항목은 검색에 전혀 반영되지 않고 있다는 뜻이며, 답변을 편집해 저장하면 재시도된다. 채팅 화면에서도 본인 소유 turn에 한해 같은 배지(`"임베딩 실패"` 텍스트)가 좋아요/편집 아이콘 옆에 뜬다(백그라운드 임베딩이 몇 초 뒤 실패하는 구조라 실시간 토스트는 없고, 다음 페이지 로드 시 표시). 상세는 [OPERATOR_MANUAL.md §7.5](OPERATOR_MANUAL.md#75-큐레이션-qa-관리-1010) 참고.

> **검색 진단 수치 카드**(`/admin` 최하단, 3단계): 같은 `<details>` 지연 로딩 구조(`hx-trigger="toggle[this.open] once"` → `GET /admin/retrieval-metrics`). 턴 한 줄에 **시각·질문·응답모드·최고 유사도·사용/검색 개수**를 보여주고, **상세**를 누르면 그 턴의 출처별 4개 수치(유사도·검색기여·축별 순위·응답참여)가 펼쳐진다. 채팅의 배지는 그 순간만 보이므로, `SEARCH_RRF_KEYWORD_WEIGHT`·`SEARCH_SIMILARITY_THRESHOLD` 같은 값을 조정하려면 **여러 턴에 걸친 경향**을 봐야 한다는 것이 이 패널의 존재 이유다.
> - **사용/검색** 열(`3/8`)이 이 패널에서 가장 볼 만한 수치다 — 검색된 출처의 절반 이상이 답변에 반영되지 않으면 ⚠가 붙는다(topK 과다 또는 프롬프트 문제 의심).
> - 값이 `-`인 칸은 0이 아니라 **측정 안 됨**이다(벡터 축에 없던 청크, 확장 실패 폴백 턴 등).
> - 페이지네이션 JS(`loadRetrievalMetrics()`/`applyMetricsLimitFilter()`)는 큐레이션 패널과 **같은 이유로** `admin.html` 페이지 레벨에 있다 — 재조회가 plain `innerHTML` 삽입이라 프래그먼트 내장 `<script>`는 실행되지 않는다.
> - 기록은 `conversation_turns.retrieval_metrics`(JSON blob, 방어적 `ALTER TABLE`)에서 읽으며, **수치를 가진 턴만** 목록에 오른다(meta/Direct/DB 재사용 턴은 빈 행으로 끼지 않는다). 읽기는 모르는 필드를 무시하도록 명시적으로 관대하게 파싱한다 — 이 행들은 자신을 쓴 코드보다 오래 살아남으므로, `SourceRef`에 필드가 하나 추가됐다고 과거 기록 전체가 안 읽히면 안 된다.

> **카드 순서**: `/admin` 하단은 **청크 추가 제안 → 큐레이션 Q&A → 검색 진단 수치** 순이다. 마지막은 조치 대기열도, 반영 확인용도 아닌 순수 분석 뷰라 가장 아래다. 앞쪽은 관리자의 조치를 기다리는 대기열(검토 대기 pill이 붙는다)이고, 뒤쪽은 이미 반영된 것을 확인·회수하는 용도라 열어볼 일이 드물어 최하단에 둔다.

> **청크 추가 제안 카드**(`/admin` 하단, 큐레이션 Q&A 카드 바로 위): 같은 `<details>` 지연 로딩 구조(`hx-trigger="toggle[this.open] once"` → `GET /admin/submissions`)이며, 카드 제목 옆에 검토 대기 건수 pill(`#submission-pending-pill`)이 붙는다(0건이면 `.d-none`). 기본 필터는 `pending` — 상태 드롭다운으로 등록 완료/반려/철회됨/전체 전환. 행의 아이콘을 누르면 검토 오프캔버스(`#submissionReviewOffcanvas`)가 열려 제목·태그·본문을 **전문 그대로** 보여주고 수정한 뒤 **임베딩 실행**/**거부**할 수 있다 — 승인된 본문이 곧 답변 프롬프트의 검색 컨텍스트가 되므로 본문을 잘라 보여주지 않고, 일괄·자동 승인 버튼도 없다([OPERATOR_MANUAL.md §7.6](OPERATOR_MANUAL.md#76-청크-추가-제안-검토-69) 참고). 본문 영역은 **원문/미리보기 탭**으로 전환되며 미리보기는 `marked` → `DOMPurify.sanitize()`를 거친다(사용자가 작성한 마크다운을 관리자 화면에서 렌더하므로 sanitize가 필수). 오프캔버스 상단에는 **승인 시 몇 개 청크로 나뉘는지**(승인 후에는 실제 생성 개수)가 표시된다 — 본문 길이 제한이 없어진 대신 `ChunkSplitter`가 분할하기 때문. 페이지 레벨 JS(`loadSubmissions()`/`openSubmissionReview()`/`approveSubmission()`/`rejectSubmission()`)는 큐레이션 패널과 같은 이유로 `admin.html`에 둔다.
>
> **`pending-count`가 `/api/v1/**`이 아닌 이유**: 관리 전용 인증 모드(§6.17)에서 `/api/v1/**`은 CSRF 예외 + 게스트 개방이라 거기 두면 검토 대기 건수가 누구에게나 노출된다. `/admin/**` 아래 두면 `ROLE_ADMIN` 게이트를 그대로 상속한다.
>
> **청크 목록 컬럼**(`fragments/admin-chunks :: table`): ID·텍스트 미리보기·크기·파일명·페이지/슬라이드·챕터·키워드·작업. **챕터** 열은 `MetaKey.CHAPTER_NO`(H2~H6 헤딩 기반 계층 번호, 예: `1.5.3`)를 보여주며, "0"(프롤로그·PPTX·스캔 PDF — 실제 챕터 없음)이면 빈 칸으로 표시된다 — [§4 출처 Hover 미리보기](#출처-hover-미리보기)의 인용 라벨 로직과 동일한 컨벤션.
>
> **청크 목록 페이지네이션·정렬**: 페이지당 건수는 20/50/100 중 선택(기본 20 — `AdminController.chunks()`의 `limit` 기본값), 필터 폼의 드롭다운 변경 시 `offset=0`으로 다시 조회한다. 정렬은 `doc_id` → `MetaKey.CHUNK_INDEX`(인덱싱 시 부여되는 0-based 문서 내 위치) 순 — 두 백엔드 모두 문서 원본 순서 그대로 표시된다(청크 id 순서 아님). sqlite-vec는 `ORDER BY doc_id, json_extract(metadata,'$.chunk_index'), spring_doc_id`로 DB에서 정렬하고, Chroma는 `get()`에 서버 측 정렬이 없어 매치 전체(최대 `AdminService.CHUNK_FETCH_CAP`=10,000건)를 가져와 애플리케이션에서 정렬 후 페이지네이션한다.
>
> **청크 필터/페이지네이션 JS는 `admin.html`(페이지 레벨)에 있다, `fragments/admin-chunks.html`이 아니라**: 예전엔 프래그먼트 자체의 `<script>`에 `applyDocFilter`/`applyLimitFilter`/`loadChunks`를 정의했는데, `loadChunks()`가 다음 페이지 응답을 `#chunk-panel.innerHTML = html`로 삽입한다 — 브라우저는 `innerHTML` 대입으로 들어온 `<script>`를 실행하지 않으므로, 문서 레지스트리의 **청크 보기**(`loadChunksByDoc()`, 이것도 plain `innerHTML`)로 패널에 먼저 진입하면 이 함수들이 아예 정의되지 않아 다음 버튼·페이지당 건수 변경이 조용히 무반응이었다(왼쪽 컬렉션 버튼의 `hx-swap`은 스크립트를 실행하므로 그 경로로 먼저 들어오면 우연히 동작했다 — 진입 경로에 따라 동작 여부가 갈리는 버그). 지금은 세 함수 모두 상시 로드되는 `admin.html` 스크립트에 있어 진입 경로와 무관하게 항상 정의돼 있고, 현재 컬렉션 값은 Thymeleaf 인라인 JS 대신 청크 카드 루트의 `data-collection` 속성에서 읽는다(`currentChunkCollection()`) — DOM 속성은 삽입 방식과 무관하게 항상 반영되기 때문. 큐레이션 Q&A 패널의 `loadCurated()`/`applyCuratedLimitFilter()`도 같은 이유로 처음부터 `admin.html`에 둔다.
>
> **재인덱싱 시 수행 작업**(`DocumentIndexer.reindexFromMd()`) — 챕터 번호 재계산뿐 아니라 전체 파이프라인을 다시 돈다: 존재하지 않는 이미지 참조(`[이미지: ...]`) 제거 → 소제목 번호 재계산(PPTX 제외) → 마크다운 후처리(`MarkdownCorrectionService.postProcess()` — 빈 줄 정리·`[DOCUMENT]` 마커/내용 없는 `-` 제거·펜스·표 앞뒤 빈 줄 보장, LLM 미사용) → 전체 재청킹 → 태그 보존 → LLM 키워드+컨텍스트 재추출(§10.1) → 재임베딩 및 벡터 스토어 저장 → FTS 재인덱싱 → 기존 청크 삭제(신규 저장 이후, 실패 시 기존 데이터 보존). 즉 원본 MD가 수정된 이후 상태를 기준으로 사실상 전체를 다시 인덱싱한다.
>
> `fixClosingFences`/`normalizeCodeBlocks`(코드 블록 언어 보정)는 의도적으로 재인덱싱에 포함하지 않는다 — 저장된 MD를 운영자가 직접 편집한 뒤 재인덱싱하면 코드 블록 내부의 의도된 빈 줄을 지우거나(`normalizeCodeContent`는 함수/클래스 시작·여러 줄 주석 시작 직전이 아닌 빈 줄은 삭제) 펜스 짝이 어긋난 입력에서 여는 펜스의 언어 태그를 잘못 벗길 수 있어, 매 재인덱싱마다 부작용으로 감수하기보다 필요할 때만 문서를 재업로드하도록 남겨둔 것이다.
>
> **청크 편집 오프캔버스 — 넓은 화면 미리보기**(`admin.html`, `openChunkEdit()`/`renderChunkPreview()`): 오프캔버스를 여는 시점의 `window.innerWidth`가 기본 폭(520px)의 2배 이상이면 오프캔버스 폭을 최대 1300px까지 넓히고 `flex-row`로 좌(미리보기)·우(편집 입력) 2컬럼 배치한다. 좁으면 기존과 동일한 단일 컬럼(`flex-column`, 520px). 미리보기는 `chat-stream.js`가 이미 쓰는 marked.js → DOMPurify → hljs.js 파이프라인을 그대로 재사용하되, 앞단에서 `[이미지: images/{id}/{file}]` 마커를 `<img src="/api/v1/images/...">`로 치환한다(인라인 렌더링이 안 되는 확장자나 `[이미지(변환불가): ...]`는 텍스트 placeholder로 대체). 텍스트 입력창을 편집하면 200ms debounce로 왼쪽 미리보기가 다시 렌더링된다. 폭 판정은 오프캔버스를 여는 시점 1회뿐이라, 열어둔 채로 창 크기를 바꿔도 레이아웃은 즉시 바뀌지 않는다(닫았다 다시 열면 재판정). 새 API 호출은 없다 — 이미 받아온 `GET /admin/chunks/{chunkId}/detail` 응답을 그대로 클라이언트에서 렌더링한다.

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
  - **검색 튜닝**(다음 검색부터 반영) — 유사도 임계값·RRF 가중치/k·후보 배수·태그 후보 배수·멀티쿼리 최소 길이·재시도 시 후보 확대·topK·멀티쿼리 확장·하이브리드 검색·**큐레이션 Q&A 사용 여부·큐레이션 가중치(좋아요)·지식 제안 가중치**
  - **인덱싱/청킹**(다음 인덱싱/↺ 재인덱싱부터 반영) — 청크 크기·오버랩·최소 크기·**청크 분할 전략(`chunk-split-granular`)**·동시 파일 처리 수·동시 LLM 호출 수
  - **LLM**(다음 LLM 호출부터 반영, §6.18) — **temperature 3종 전부**: 일반/RAG(`llm.temperature`, clamp `[0.0, 0.3]`)·Direct 응답(`llm.direct-temperature`, `[0.0, 1.0]`)·인덱싱/백그라운드(`llm.indexing-temperature`, `[0.0, 1.0]`). 각각 자기 계열의 다음 호출부터 반영된다
  - **UI**(다음 페이지 렌더부터 반영) — 출처 미리보기 표시(`ui.source-preview-enabled`, 기본 ON). 끄면 채팅 출처 배지의 팝오버 미리보기(§4)가 사라진다. **출처 검색 수치 표시**(`ui.retrieval-metrics-enabled`, 기본 **OFF**) — 켜면 출처마다 `유사도 0.72 · 응답 31%`가 붙는다(§4 "출처 검색 진단 수치")
  - 그 외 키(조회 전용: `rerank-enabled`·쿼리 임베딩 캐시 등)는 400(`IllegalArgumentException`)으로 거부된다.
- 값 검증 실패(범위 초과, 타입 불일치)도 400 — `GlobalExceptionHandler`가 처리.
- 재기동이 필요한 값(rerank 활성화, 벡터 스토어 백엔드, 임베딩 설정, `max-tokens` 등)과 기본 라우팅 모드는 조회 전용으로만 노출된다. `max-tokens`는 프로바이더 빈 생성 시점에 `OpenAiChatOptions`로 구워지므로 핫 수정 대상이 아니다(§6.18 이후 실제 config 값을 반영해 표시만 한다).
- **프로바이더 활성/비활성**(`/admin/settings/provider/toggle`)은 위 `key`/`value` 오버라이드 메커니즘과 별개다 — `settings_override`에 저장되지 않는 메모리 전용(`ProviderToggle`) 토글이라 **재기동하면 초기화**된다. LLM 라우팅 표의 각 행에서 관리자에게만 활성화/비활성화 버튼이 보인다. 표 본문 행 사이의 구분선은 CSS로 숨겨져 있다(`app.css`의 `#llm-providers tbody > tr > *`) — 헤더 밑줄만 남아 열 제목과 데이터를 구분한다.
- **LOCAL_ONLY 배포에서는 NORMAL/PREMIUM 프로바이더가 표에서 통째로 숨겨진다**: `app.llm.default-routing-mode=LOCAL_ONLY`일 때 `SettingsService.visibleProviders()`가 `role != LOCAL`인 프로바이더를 필터링한다(`providerRows()`/`setProviderEnabled()` 둘 다 동일하게 적용) — NORMAL/PREMIUM 항목이 향후 모드 전환에 대비해 `application.properties`에 여전히 남아있을 수 있다. 숨겨진 프로바이더는 토글 엔드포인트로도 조작할 수 없다(이름이 "알 수 없는 프로바이더"로 거부됨) — 표시 범위와 조작 가능 범위가 항상 일치한다. (안내 배너·토글 휘발성 안내 문구는 UX 정리 차원에서 제거됨 — 동작 자체는 그대로.)
- LLM 라우팅 카드는 `<hr>`로 두 구역을 나눈다: 위쪽은 라우팅 모드·temperature·max-tokens(LLM 자체), 아래쪽은 임베딩(모델·**접속 주소**(`settings.embeddingBaseUrl`, `app.embedding.base-url`/`EMBED_BASE_URL` 조회 전용)·차원). 임베딩 접속 주소는 채팅 LLM과 별도 엔드포인트일 수 있어(§6.21 로드밸런싱 등) 조회 전용으로만 노출된다.
- 상세는 [OPERATOR_MANUAL.md §6.5](OPERATOR_MANUAL.md#65-설정-페이지-settings--llmrag-옵션-조회핫-수정) 참고.

### 3.5-bis 청크 추가 게시판 (CuratedSubmissionController)

사용자가 검색에 넣고 싶은 내용을 직접 등록하는 게시판. **모든 인증 모드에서 게스트에게 열려 있다** —
등록이 만드는 것은 검색에 영향을 주지 않는 `pending` 행 하나뿐이고, 실제 색인은 관리자 승인(§3.4)을 거친다.
모든 조회·쓰기가 `CurrentUser.userId()` 스코프이며, no-auth 모드에서 "내 제안"이 방문자별로 갈리려면
`app.auth.guest-identity`가 기본값 `shared`가 아니어야 한다([OPERATOR_MANUAL.md §9.4.3](OPERATOR_MANUAL.md#943-접속자별-채팅-개인화-appauthguest-identity)).

| Method | Path | 반환 | 설명 |
|--------|------|------|------|
| GET | `/curated/submissions` | `curated-submissions.html` | 등록 폼 + "내 제안" 목록. **페이지를 여는 것 자체가 읽음 처리**(`markAllReadForAuthor`)라 헤더 배지가 사라진다 |
| POST | `/curated/submissions` | redirect + flash | 등록(`title`/`body`/`tags`). HTMX가 아니라 **평범한 폼 POST + 플래시 리다이렉트** — HTML 폼이므로 검증 실패가 `GlobalExceptionHandler`의 JSON으로 나가면 안 된다. 실패 시 입력 초안(`draftTitle`/`draftBody`/`draftTags`)을 되돌려준다 |
| POST | `/curated/submissions/{id}/withdraw` | redirect + flash | 작성자 본인의 `pending` 제안 철회 |
| POST | `/curated/submissions/images` | JSON `{"path","marker"}` / `400` / `422` | 본문 이미지 업로드(multipart `file`). 위 등록 POST와 달리 **진짜 API 호출**(폼 JS의 `fetch`)이라 오류를 JSON으로 돌려주는 것이 맞다 — 크기/빈 파일은 `GlobalExceptionHandler` 경유 400, 확장자·매직바이트 불일치는 422 |
| GET | `/curated/submissions/unread-count` | JSON `{"count":N}` | 헤더 배지 폴링(60초) — 처리됐지만 아직 확인하지 않은 내 제안 수. **읽음 처리는 하지 않는다**(폴링이 지우면 보기도 전에 사라짐) |

**폼 구성**(`curated-submissions.html`):

- **제목** — `curated_qa.question` 컬럼에 그대로 저장되어 임베딩 입력의 앞부분이 된다. 질문형 제목일수록 검색이 잘 걸린다.
- **태그**(선택) — 자유 입력 + 아래 **기존 태그** 칩 클릭 추가. `documents.html` 업로드 태그와 동일한 패턴이며, 목록은 `GET /api/v1/tags?includeCurated=true`로 **문서 태그 ∪ 큐레이션 태그**를 받는다 — 큐레이션 항목은 `chunk_fts`에 색인되지 않아(벡터 축 전용) 합집합이 아니면 제안에서만 쓴 태그가 다음 사람에게 안 보이고 표기가 갈린다. 비워 두면 모든 태그 스코프에서 검색된다(§4 "큐레이션 태그 스코프" 참고).
- **본문** — **길이 제한 없음**. 오른쪽 위 **작성/미리보기** 탭으로 전환하며, 미리보기는 `renderMarkdownWithImageMarkers()` → `marked` → `DOMPurify.sanitize()`(관리자 검토 화면과 동일 파이프라인). 입력창 아래에 글자 수와 **예상 청크 수**(`chunkSize`로 나눈 클라이언트 추정치 — 정확한 값은 소제목 위치에 따라 달라지므로 관리자 검토 화면이 서버 계산으로 보여준다)가 표시된다.
- **이미지 추가** — 본문 라벨 옆 버튼. 파일을 고르면 즉시 `POST /curated/submissions/images`로 올라가고, 응답의 `[이미지: images/submissions/{해시}.png]` 마커가 **textarea의 커서 위치**에 삽입된다(앞뒤 빈 줄 포함, 삽입 후 `input` 이벤트를 발생시켜 글자 수·예상 청크 수를 갱신). **마커의 위치가 곧 이미지의 위치**이므로 그 뒤의 이동·복사·삭제는 전부 평범한 텍스트 편집이고, 승인 시 본문이 청크로 나뉠 때 이미지가 자기가 설명하는 문단을 따라간다. png·jpg·gif·webp / 파일당 5MB / 본문당 10장. CSRF 토큰은 폼의 히든 인풋에서 읽어 `FormData`에 실으므로 세 인증 모드에서 분기가 필요 없다(no-auth에서는 값이 비어 생략).
- **내 제안 목록** — 상태 뱃지(검토 대기/등록 완료/반려/철회함/회수됨), 반려 사유 **전문**, 임베딩 실패 경고, 태그 뱃지, 등록된 청크 수. 상태는 전부/전무로 파생된다(청크가 하나라도 살아 있으면 등록 완료).

> **이미지 마커 렌더링은 전역 1벌**(`layout/base.html`의 `renderMarkdownWithImageMarkers()`) — `/admin` 청크 뷰(`renderChunkPreview()`)·제안 작성 미리보기·관리자 검토 미리보기가 모두 같은 본문 형식을 그린다. `CHUNK_IMAGE_MARKER`/`PREVIEWABLE_IMAGE_EXT`는 이제 전역 상수이므로 **페이지 스크립트에서 다시 선언하면 안 된다**(최상위 `const` 중복 = 그 페이지 스크립트 전체가 죽는 `SyntaxError`).

---

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

단일 행 구조 — `fragments/thread-list.html`(전체 목록, `GET /ui/threads`)과 `fragments/thread-item.html`
(항목 1건, `PATCH /ui/threads/{id}/title` 응답)은 제목/날짜 한 줄만 렌더링한다.

```
{제목, 왼쪽 정렬}                              {날짜, 오른쪽 정렬}
```

- 버전과 태그는 사이드바 목록에서 표시하지 않는다.
- 제목(`text-truncate small`)과 날짜(`.thread-date.text-muted`, `font-size:0.72rem`)는 기존 스타일 유지.
- **제목의 `[버전]` 접두사 제거**: `ThreadMetaService`는 여전히 `title` 컬럼에 `"[{version}] {summary}"`를
  그대로 저장한다(하위 호환, DB 마이그레이션 없음) — 화면에는 `ThreadMeta.displayTitle()`이 선행
  `[..]` 브래킷을 정규식으로 잘라낸 값을 쓴다. 버전은 사이드바에 따로 표시하지 않는다.
- **태그는 스레드에 스냅샷 저장되지만 사이드바에 표시하지는 않는다**: `thread_meta.tags`(`V3__thread_tags.sql`)는
  그 스레드에서 **가장 최근에 보낸 메시지의 태그 선택**을 담는다 — `ChatController`가 `/ui/chat`·`/ui/chat/stream`
  양쪽에서 매 전송마다 `ThreadMetaService.updateTags()`를 호출해 무조건 덮어쓴다(제목 생성과 달리 "커스텀이면
  건너뛰기" 가드 없음). 렌더링용 헬퍼 `ThreadMeta.tagsDisplay()`(CSV → `"tag1, tag2"`)는 남아 있으나 현재 어느
  템플릿에서도 호출하지 않는다 — 2행 표시를 되돌리려면 이 헬퍼를 다시 쓰면 된다.

---

## 4. 라우팅 전략 UI

### 사이드바 드롭다운

| 선택지 | RoutingMode | 비고 |
|--------|------------|------|
| 비용 우선 | COST_FIRST | 기본값 (`app.llm.default-routing-mode`) |
| 품질 우선 | QUALITY_FIRST | |
| 단계적 | PROGRESSIVE | 품질 미달 시 PREMIUM 자동 전환 |
| 로컬 전용 | LOCAL_ONLY | LOCAL 미연결 시 오류 발생 경고 |

변경 시 `PATCH /ui/threads/{threadId}/routing-mode` → `thread_meta.routing_mode` 저장.

> **드롭다운 전체 숨김**: 위 표는 LOCAL_ONLY 옵션이 `disabled`되는 경우고, `app.llm.default-routing-mode`(`LLM_ROUTING_MODE`)가 `LOCAL_ONLY`면 드롭다운 컨테이너 자체가 렌더링되지 않는다 — 배포에 LOCAL 프로바이더만 있어 어떤 모드를 골라도 결과가 동일하기 때문. 판단 기준은 대화별 `routingMode`가 아니라 **배포 전체의 기본 모드**다(`ChatController`의 `localOnlyDeployment` 모델 속성, `chat.html` `th:if="${!localOnlyDeployment}"`).

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
태그일수록 오히려 더 봐야 하기 때문이다. 청크 추가 게시판(§3.5-bis)은 여기에 더해
`includeCurated=true`를 붙여 **문서 태그 ∪ 큐레이션 태그**를 받는다(큐레이션 항목은 `chunk_fts`에
색인되지 않아 기본 호출로는 잡히지 않는다).

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

| 선택지 | ResponseMode | 답변 성격 | 토큰 비율(`LLM_MAX_TOKENS` 대비) | 글자수 하한 |
|--------|-------------|----------|------------------------------|------------|
| S | `S` | 요약적이고 간단하게 | 15% | 2,000자 |
| M | `M` | 쉽고 자세하게 | 40% (기본값) | 5,000자 |
| L | `L` | 원문 최대한 살려 최대한 많이 | 70% | 10,000자 |

**목표 분량은 두 값(비율/하한) 중 큰 쪽**(`ResponseMode.maxTokens()` = `max(256, max(round(설정값×비율),
하한))`) — `LLM_MAX_TOKENS`를 낮게 잡은 배포에서도 S/M/L이 같은 값으로 뭉개지지 않도록 하한이 바닥을
잡아준다(전형적인 설정 범위에서는 사실상 이 하한이 실제 분량을 결정하고, 비율 항은 나중에 훨씬 큰
컨텍스트 모델을 쓰게 될 때의 안전판이다). 이 하나의 숫자(글자 수로 취급 — 한글은 1토큰≈1글자라 별도
환산 없음)가 **블로킹 호출의 `ChatOptions.maxTokens`**로도, **프롬프트 지시문(`prompt.answer.style.{s,m,l}`)에
들어가는 "약 N자 이내로" 목표치**로도 그대로 재사용된다. 다만 이는 프롬프트로 LLM에 전달하는 "목표"일 뿐
서버가 답변을 그 글자 수에서 강제로 잘라내지는 않는다 — `maxTokens`는 **블로킹 호출에만** 붙으므로(스트리밍은
기존 설계대로 토큰 상한이 없음 — CLAUDE.md 참고) 스트리밍 채팅에서는 프롬프트 지시문이 유일한 조절 수단이다.
`AnswerService.truncate()`의 20,000자 컷은 모드와 무관한 절대 상한으로 그대로 유지된다.

> **L은 RAG 전용**: `L`(원문 최대)은 검색된 문서 컨텍스트를 최대한 살리는 모드라 Direct(RAG 미사용) 모드에서는
> 의미가 없다 — RAG/Direct 토글(`#direct-mode-toggle`)이 켜지면 `#response-mode-l`이 `disabled`되어
> Bootstrap의 `.btn-check:disabled` 스타일로 흐리게 표시된다. 이 시점에 `L`이 선택돼 있었다면 자동으로
> `M`으로 되돌리고(`localStorage`도 갱신) 다시 RAG로 돌아가도 `L`을 자동 재선택하지는 않는다.

### 질문 내비게이션 (현재 질문 풍선 · 전체 질문 보기)

답변이 수십 줄인 것이 보통이라, 조금만 스크롤하면 그 답변이 **어떤 질문에 대한 것인지** 화면에서 사라진다. 두 가지로 보완한다.

| 요소 | 위치 | 동작 |
|------|------|------|
| `#current-question-float` | 대화 영역 **우측 상단** (`.chat-messages-wrap` 기준 absolute) | 지금 읽고 있는 턴의 질문을 사용자 버블과 같은 색 풍선으로 최대 2줄까지 표시. 클릭(또는 Enter/Space)하면 그 질문 위치로 이동 |
| `#question-nav-btn` / `#question-nav-panel` | **'맨 아래로' 버튼 바로 위** | 이 대화의 질문 전체 목록(번호 · 질문 2줄 · 시각)을 카드 풍선으로 열고, 항목을 누르면 그 질문으로 이동 후 닫힘. 바깥 클릭 · `Esc` · X 로도 닫힌다 |

- **질문 원본은 `.user-turn[data-question]` 하나에서만 읽는다.** 서버 렌더 경로(`chat.html`의 `turns` 루프)와 스트리밍 경로(`chat-stream.js`의 `appendUserBubble()`)가 같은 표식을 심는다 — 한쪽만 고치면 새로 보낸 질문이 목록에서 빠진다.
- 풍선 표시 규칙은 `qnavUpdateFloat()` 하나에 있다: 목록 상단에서 `QNAV_ANCHOR_PX`(48px) 아래를 기준선으로 잡아 그 위로 시작점이 올라간 **마지막** 턴이 "지금 읽는 턴"이고, 그 질문 버블이 아직 화면에 남아 있으면 풍선은 숨는다(같은 문구를 두 번 보여줄 이유가 없다). 기준선을 상단 딱 그 지점이 아니라 조금 아래로 잡는 이유는 목록에서 이동했을 때 질문이 상단 12px 아래에 놓이기 때문 — 상단 기준이면 방금 이동해 온 질문이 '다음 턴'으로 분류돼 풍선이 바로 앞 질문을 가리킨다.
- 목록은 **열 때마다 DOM에서 다시 만든다**(별도 상태 없음). 버블 추가는 `#chat-messages`의 `MutationObserver`(`childList`, `subtree` 없음 — 스트리밍 토큰은 답변 버블 *안*에서 일어나므로 잡히지 않는다)로 감지해 버튼 노출/목록을 갱신한다.
- `.chat-messages-wrap`은 순전히 위치 기준용 래퍼다. 풍선을 스크롤 컨테이너(`#chat-messages`) 안에 두면 내용과 함께 흘러가고, 바깥 채팅 영역에 두면 상단바·이어가기 배너 위에 얹힌다.
- 두 버튼의 `bottom` 값은 서로 물려 있다 — `.scroll-to-bottom-btn`(7.125rem) + 높이 2.5rem + 간격 0.425rem = `.question-nav-btn`(10.05rem). 하나를 옮기면 나머지도 함께 옮겨야 한다.

### 응답 메타데이터 (어시스턴트 버블 하단)

```
🤖 gemini-flash  ·  ⏱ 4.2초  ·  📥 1,284 tokens in  ·  📤 512 tokens out  ·  🔄 LLM 4회
```

PROGRESSIVE 업그레이드 시 `🔝 고추론 재분석 → {premiumProvider}` 배지 추가.

### 출처 Hover 미리보기

**출처 라벨 형식**: `RetrievalService.formatSource()`가 청크 메타데이터의 `chapter_no`(H2~H6 헤딩 기반 계층 번호, 예: `1.5.3`)가 "0"이 아니면 `"파일명 | 1.5.3"`, 아니면(프롤로그·PPTX·비스캔 PDF — 이 세 경우는 chapter_no가 항상 "0") `page_or_slide`로 폴백해 `"파일명 | p.12"`로 표시한다 — 문서 버전은 라벨에 포함되지 않는다. **큐레이션 Q&A**(§10.10, 좋아요로 승격된 답변)가 출처로 포함된 경우엔 파일명·페이지가 없으므로 `"💬 큐레이션 Q&A"` 고정 라벨로 표시된다.

출처 목록 항목에 Bootstrap Popover (`hover focus` 트리거). `SourceRef.preview`에 청크 텍스트 앞 600자 포함.

### 출처 검색 진단 수치 (1단계)

`ui.retrieval-metrics-enabled`(기본 **OFF**, §3.5)를 켜면 출처마다 수치가 붙는다 — **"왜 이 청크가 검색됐는가"를 설명하는 값이지, 답변에 얼마나 쓰였는지가 아니다**(그건 아래 `answerShare`).

> **채팅에 실제로 보이는 것은 아래 표의 전부가 아니다.** 4개 필드는 REST/SSE 페이로드와 `/admin` 패널에 모두 실리지만, 채팅 출처 줄에는 **근거 품질 한 칸 + 응답 참여도**만 렌더한다:
>
> ```
> 유사도 0.72 · 응답 31%      ← 유사도가 있으면 유사도
> bm25:1 · 응답 12%           ← 없으면 축 표기로 대체
> ```
>
> - **`retrievalShare`는 채팅에서 뺐다** — 순위 기반 RRF라 한 턴 안에서는 값이 평평해(1위 13% vs 8위 12%) 출처끼리 비교하는 용도로는 변별력이 거의 없다. 여러 턴의 경향으로 읽어야 의미가 생기므로 `/admin` 패널의 몫이다.
> - **유사도와 축별 순위는 비는 조건이 서로 배타적**이라 한 칸을 폴백 체인으로 채운다 — 순수 BM25 히트는 키워드 축 문서가 SQL 행이라 거리값이 없고(유사도 null), 쿼리 확장 실패 폴백 턴은 RRF를 건너뛰어 축 순위가 없다(유사도는 `Document.getScore()`로 살아 있음). 둘 다 비는 경우는 실질적으로 없다.
> - 축 표기로 대체됐다는 사실 자체가 **"의미가 아니라 단어로 걸린 청크"**라는 신호가 된다.
> - 폴백 체인은 **세 렌더 경로**(`chat-stream.js`의 `renderSourceMetrics()`, `message-assistant.html`, 그리고 `chat.html`의 이전 turn 복원 마크업)에 같은 규칙으로 구현돼 있다 — `/chat/{threadId}`를 다시 열면 이전 turn은 프래그먼트가 아니라 `chat.html` 자체 마크업으로 그려지므로, 여기를 빠뜨리면 새로고침하는 순간 수치가 사라진다.
> - **복원 값의 출처는 `conversation_turns.retrieval_metrics`**(3단계 영속). 출처 목록 자체는 현재 청크 기준으로 재구성한 쪽(`QuestionReuseService.sourceRefsForTurn()`)이 권위이고 — 라벨·미리보기·삭제된 청크 placeholder는 저장 blob이 알 수 없다 — `RetrievalMetricsService.enrich()`가 **수치만 `chunkId`로 병합**한다. 저장 기록이 없는 출처·턴은 그대로 통과하므로 목록에서 사라지지 않는다. DB 재사용 turn은 검색을 돌리지 않아 기록 자체가 없다.

| 값 | `SourceRef` 필드 | 의미 |
|---|---|---|
| `유사도 0.72` | `similarity` | 질의와의 코사인 유사도(`1 - distance`). 벡터 축에 없던 청크는 **null**(0.0 아님) |
| `검색 18%` | `retrievalShare` | 가중 RRF 점수 ÷ 최종 컷 전체 합. 후보 풀이 아니라 **답변 노드가 실제로 받은 문서들** 기준으로 정규화 |
| `vec:2, bm25:5` | `axisRanks` | 축별 순위. 여러 MultiQuery 벡터 축에 걸치면 **최선 순위** |
| `응답 31%` | `answerShare` | **응답 참여도(2단계)** — 답변 텍스트 중 이 청크에서 온 것으로 추정되는 비율. 아래 별도 설명 |

- **`retrievalShare`는 순위 기반이라 의도적으로 평평하다** — RRF가 `w/(rank+1+k)`이고 k 기본값이 60이라 1위(0.0164)와 8위(0.0145)의 차이가 작다. 변별력은 `similarity` 쪽에서 봐야 하며, 실제 진단에 가장 쓸모 있는 건 `axisRanks`다("벡터 12위인데 BM25 1위라 올라옴" → `SEARCH_RRF_KEYWORD_WEIGHT` 조정 근거).
- **null은 "측정 안 됨"이지 0이 아니다.** BM25/큐레이션 축에만 걸린 청크는 유사도가 없고, 쿼리 확장 LLM 호출이 실패해 폴백 경로로 빠진 턴은 RRF 자체를 건너뛰어 share·순위가 전부 없다. 세 표시 지점 모두 해당 항목만 생략한다.
- 수치는 `RetrievalService.mergeRrfScored()`가 융합 부산물로 계산하며 **LLM·임베딩 추가 호출이 0회**다. 기존 `mergeRrf()` 오버로드는 이 메서드에 위임 후 메트릭을 버리므로 랭킹 동작은 완전히 동일하다(`RetrievalServiceRrfTest`가 두 경로의 문서 순서 일치를 검증).
- 메트릭은 `Document` 메타데이터가 아니라 `docKey` 기준 **사이드 맵**으로 나른다 — 검색 결과 문서는 답변 프롬프트와 (큐레이션·관리자 경로에서는) 벡터 스토어로 다시 흘러가므로, `MetaKey.SEARCH_TEXT`처럼 "영속 전 제거해야 하는 임시 키"를 하나 더 만들지 않기 위함이다. 태그 필터·리랭크가 문서 목록을 재구성해도 살아남는다는 이점도 있다.
- 전달: REST `ChatResponse.sources[]`와 SSE `sources` 이벤트는 토글과 무관하게 **항상** 필드를 싣는다(`similarity`/`retrieval_share`/`axis_ranks`/`answer_share`) — 토글은 렌더링만 막는다. 서버 렌더 경로(`message-assistant.html`)는 값을 `.source-metrics.d-none`으로 항상 그려두고 `window.isRetrievalMetricsEnabled()`가 `d-none`을 벗기며, 스트리밍 경로(`chat-stream.js`)는 애초에 켜졌을 때만 만든다.

#### 응답 참여도 (2단계) — `answerShare`

앞의 세 값과 달리 **검색 시점에는 존재할 수 없는 값**이다(답변이 끝나야 나온다). 그래서 SSE는 2단계로 전달한다:

```
sources 이벤트 (RETRIEVAL 직후) → 유사도 · 검색기여도 · 축별 순위
done 이벤트    (답변 완료 후)   → attribution {chunkId: 0.0~1.0} → 배지 사후 갱신
```

`chat-stream.js`의 `applyAttribution()`이 `data-chunk-id`로 배지를 찾아 `· 응답 31%`를 덧붙인다. 재시도가 있었으면 `sources`가 다시 와서 배지가 새로 그려지므로 항상 현재 DOM 기준으로 찾고, `data-has-attribution`으로 멱등성을 보장한다. 블로킹/REST 경로는 한 번에 다 나오므로 분기가 없다.

**계산은 `AnswerAttribution`**(Spring 의존 없는 순수 클래스, `ChunkReassembler` 선례)이 하며, `FinalizeService`가 그래프 끝에서 딱 한 번 호출한다 — ANSWER 노드는 재시도·PROGRESSIVE 업그레이드로 여러 번 돌 수 있어서, 버려질 답변에 대해 계산하면 낭비다. **추가 LLM·임베딩 호출 0회**(순수 CPU).

**알고리즘**: 답변을 문장 단위로 쪼개 각 문장을 가장 닮은 청크에 배정하고, 청크별 배정 글자수 비율을 몫으로 낸다.
- **문자 4-gram 매칭** — 한국어는 공백 토큰화가 조사/어미를 잘못 끊는다("포트를" vs "포트는"은 토큰이 다르다). 4-gram은 어간 위를 미끄러지므로 활용형이 달라도 잡힌다.
- **희소도 가중(`1/문서빈도`)** — 이게 없으면 같은 문서에서 온 청크들이 머리말·제품명·breadcrumb 같은 보일러플레이트만으로 서로 비슷해져, 몫이 "가장 긴 청크"로 쏠린다. 모든 청크에 있는 4-gram은 귀속 정보가 0이고, 한 청크에만 있는 4-gram이 실제로 출처를 식별한다.
- **매칭되지 않은 문장은 분모에서 빠진다** — 모델이 자기 말로 쓴 연결 문장·요약 문장을 억지로 가까운 청크에 밀어 넣지 않는다. 마크다운 헤딩 줄(`## 요약`)도 매 턴 동일한 골격이라 제외한다.

**인용 신호(`usedDocs`)**: 평가 LLM 호출(⑤)이 이미 답변과 모든 발췌를 함께 들고 있으므로, "실제로 근거로 쓴 `[Dn]` 번호"를 같은 호출에서 함께 받는다(**추가 왕복 0회**). 발췌 블록에 `[D1]`, `[D2]` … 번호가 붙는 이유가 이것이다. 이 값은 **후보를 좁히기만 하고 몫을 만들어내지는 않는다** — 모델이 인용한 문서라도 답변이 그 내용을 닮지 않았으면 몫은 0이다. 범위 밖 번호는 무시하고, 목록이 비면 전체 문서가 후보가 된다(`method`: `citation+lexical` / `lexical` / `none`).

> ⚠️ **이 값은 추정이지 측정이 아니다.** 진짜 인과적 기여도는 청크를 하나씩 빼고 답변을 다시 생성해야(leave-one-out) 알 수 있고, 그건 턴당 LLM 호출이 topK배라 오프라인 평가 하네스(OPERATOR_MANUAL §6.6) 영역이다. 또한 `responseMode=S`는 평가 호출 자체가 없어 인용 신호 없이 어휘 기반으로만 계산된다.

**팝오버 크기 (`app.css`, ≥768px 전용)**: Bootstrap 기본값(`max-width: 276px`, `font-size: 0.875rem`)은 미리보기가 세로로 길게 줄바꿈되어 가독성이 떨어졌다 — `max-width: 620px`(약 2배), `font-size: 0.8rem`으로 넓히고 살짝 줄여 같은 600자가 더 적은 줄로 읽기 좋게 표시된다. `@media (min-width: 768px)` 블록 안에 있어 모바일(<768px)은 Bootstrap 기본값 그대로 — 좁은 화면에서 팝오버를 더 넓히면 화면 밖으로 넘칠 여지가 있기 때문.

### 좋아요 피드백 & 큐레이션 Q&A 편집 (§10.10)

어시스턴트 버블 하단(피드백 컨트롤 영역, `.feedback-controls`)에 👍/👎 버튼과 함께 표시된다.

```
👍  👎  ✏(좋아요 상태일 때만)
```

| 동작 | 트리거 | 서버 반영 |
|------|--------|----------|
| 좋아요 | 👍 클릭(재클릭 시 취소) | `PATCH /ui/threads/{id}/turns/{turnId}/feedback` → 즉시 큐레이션 스냅샷 생성 + 3초 후 배경 임베딩 |
| 싫어요 | 👎 클릭 | 동일 엔드포인트 — 다음 대화 컨텍스트에서 해당 turn 제외(§6.8). **세 경로 모두에서 빠진다**: 원본 폴백(`getHistory()`의 SQL `feedback <> 'DISLIKE'`), 요약(`dedupeTurns()`), 그리고 요약 경로가 덧붙이는 `[Recent]` 원문 구간. 마지막 것은 `buildContext()`가 `getRecentTurns()`를 그대로 쓰다가 누락됐던 자리 — 그 SQL에는 feedback 조건이 없어서, 싫어요 답변이 요약에서만 빠지고 원문으로는 다시 들어갔다 |

**싫어요를 누르면 삭제 여부를 묻는다.** 피드백은 먼저 저장되고, 그 직후 `confirm.delete.turn`("이 질문과 답변을 대화에서 삭제할까요?") 확인 대화상자가 뜬다 — 여기서 취소해도 싫어요는 그대로 남는다(그 턴은 이미 컨텍스트에서 빠진 상태이고, 삭제는 그보다 강한 선택지일 뿐이다). '예'면 `DELETE /ui/threads/{threadId}/turns/{turnId}`를 호출하고 질문·답변 버블 한 쌍을 DOM에서 걷어낸다.

- **묻는 시점은 `NONE → DISLIKE` 전이뿐이다.** 싫어요를 다시 눌러 해제하거나(`DISLIKE → NONE`) 좋아요를 누를 때는 묻지 않는다.
- 삭제 범위는 **그 턴 하나**다(대화 전체가 아니다 — 사이드바 휴지통의 `confirm.delete.thread`와 구분할 것). `conversation_turns` + `turn_source_ref` + `turn_image_ref`에서 그 `turn_id`만 지우며, 이는 `clearHistory()`가 스레드 단위로 지우는 것과 같은 테이블 집합이다.
- 그 턴을 재사용한 **다른 턴의 `reused_from_turn_id`는 일부러 그대로 둔다.** 그 컬럼을 읽는 모든 SQL이 LEFT JOIN + `"참조 원문 삭제됨"` 폴백이라 이미 사라진 원본을 견디게 되어 있다.
- DOM 제거는 피드백 컨트롤에서 `#chat-messages`의 **직계 자식**까지 거슬러 올라가 답변 행을 찾고 그 앞 형제를 질문 행으로 삼는다 — 서버 복원·스트리밍 두 경로가 모두 '질문 행 다음 답변 행'을 직계 자식으로 붙이기 때문에 이 규칙 하나로 양쪽이 처리된다. 제거 후에는 질문 내비게이션의 `MutationObserver`가 목록을 자동으로 갱신한다.
| 큐레이션 답변 편집 | 좋아요 상태일 때만 노출되는 연필(✏) 아이콘 | `GET`/`PATCH /ui/threads/{id}/turns/{turnId}/curated` → 우측 오프캔버스에서 답변 텍스트 수정, 저장 시 자동 재임베딩 |

- 편집 아이콘은 **본인이 좋아요한 turn에서만** 보인다 — 채팅창은 항상 본인 스레드만 렌더링하므로 별도 권한 UI 분기가 없다.
- **L(원문 최대) 모드 답변은 좋아요를 눌러도 재임베딩되지 않는다** — `curated_qa` 행(좋아요 취소·편집·관리자 목록용)은 그대로 생성되지만, 이미 인덱싱된 원본 문서 내용과 사실상 동일해 배경 임베딩 스레드 자체가 시작되지 않는다(`CuratedQaService.onLike()`). UI 동작(👍 토글, 편집 아이콘 노출)은 다른 모드와 동일하다.
- 좋아요/취소 클릭 시 JS가 서버 응답에 따라 편집 아이콘의 표시 여부도 함께 갱신한다(새로고침 불필요).
- 관리자용 전체 큐레이션 Q&A 관리(모든 사용자 대상)는 `/admin` 페이지에 별도로 있다 — [§3.4](#34-벡터-스토어-관리-admincontroller) 및 [OPERATOR_MANUAL.md §7.5](OPERATOR_MANUAL.md#75-큐레이션-qa-관리-1010) 참고.
- 동작 원리(디바운스, 재임베딩, 문서 재인덱싱/대화 삭제와의 관계)는 [OPERATOR_MANUAL.md §6.7](OPERATOR_MANUAL.md#67-큐레이션-qa-좋아요-기반-지식-승격-1010) 참고.

**큐레이션 태그 스코프**: 좋아요를 누른 시점에 **그 질문이 검색된 태그 스코프**(입력 바의 태그 칩 선택값)가 `curated_qa.tags`로 승계된다 — 그 태그로 좁혀 얻은 답변이므로 이후 같은 스코프 검색에서 살아남아야 하기 때문. `RetrievalService.filterByTags()`가 벡터·키워드·큐레이션이 합쳐진 후보 풀 **전체**에 걸리므로, 태그 메타데이터가 없던 이전에는 사용자가 태그 칩을 하나라도 켜는 순간 좋아요한 답변이 전부 결과에서 빠졌다. 태그 없이(= `All` 칩) 물은 질문은 스코프가 비어 승계되고, **스코프를 알 수 없는 큐레이션 항목은 어느 스코프에도 속하지 않는 대신 모든 스코프를 통과**한다(문서 청크는 엄격 AND 그대로 — 태그 없는 문서는 여전히 탈락). 사용자 제안(§3.5-bis)의 태그도 같은 컬럼·같은 판정을 쓴다.

---

## 5. HTMX 인터랙션 흐름

```
[채팅 전송]  chat-stream.js가 form submit을 capture 단계에서 가로챔
              → fetch POST /ui/chat/stream (text/event-stream)
              → ReadableStream으로 SSE 이벤트 파싱
              → stage 이벤트: 단계 배지 교체 (classifier/retrieval/answer/critic/upgrade)
              → sources 이벤트: Bootstrap Popover 출처 배지 삽입
              → token 이벤트: 텍스트 실시간 누적
              → done 이벤트: marked.js 마크다운 렌더 + 메타데이터 footer 표시
                             + htmx.trigger(body, "refreshThreadList")
              → error 이벤트: 오류 버블 교체
              hx-post="/ui/chat" 속성은 JS 비활성 시 fallback으로 유지
              → message-assistant fragment 반환
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

[청크 추가 제안 카드] <details id="submission-card"> 첫 펼침 → hx-trigger="toggle[this.open] once"
              → GET /admin/submissions (기본 status=pending) → #submission-body 삽입
[제안 검토/승인]  행 아이콘 → openSubmissionReview(id) → GET /admin/submissions/{id}/detail
              → 오프캔버스 렌더(원문/미리보기 탭 — 미리보기는 renderMarkdownWithImageMarkers()라
                본문 이미지가 실제 그림으로 보인다. 승인은 그 그림까지 검색에 넣는 허가다)
              → 임베딩 실행 시 버튼 잠금 + 스피너(이미지가 있으면 Vision 호출을 기다린다)
              → POST /admin/submissions/{id}/approve|reject → loadSubmissions()로 목록 재조회

[큐레이션 Q&A 카드] <details id="curated-qa-card"> — 제안 카드 아래, 페이지 최하단.
              첫 펼침(브라우저 native toggle 이벤트, this.open=true)
              → hx-trigger="toggle[this.open] once" → GET /admin/curated
              → hx-target="#curated-qa-body" → fragments/admin-curated::panel 삽입
              (htmx 트리거는 최초 1회만 — 접었다 다시 펴도 재조회하지 않음, 새로고침 시 초기화)
[큐레이션 Q&A 페이지네이션] 이전/다음 버튼, 페이지당 건수 드롭다운 → loadCurated(offset, limit)
              (페이지 레벨 JS, htmx 아닌 plain fetch) → GET /admin/curated?offset=&limit=
              → #curated-qa-body.innerHTML 교체 (몇 번이든 재호출 가능, 위 최초-1회 제약과 무관)
[큐레이션 편집]   ✏ 버튼 → openCuratedEdit(id) → GET /admin/curated/{id}/detail
              → 넓은 화면이면 좌측 미리보기 + 우측 편집 컬럼(청크 편집과 같은 EDIT_BASE_WIDTH*2
                기준·같은 renderChunkPreview), 좁으면 단일 컬럼
              → 저장 → POST /admin/curated/{id} → 백그라운드 재임베딩
[큐레이션 삭제]   🗑 버튼 → deleteCuratedInline(id, btn) → DELETE /admin/curated/{id}
              → 성공 시 해당 <tr> 제거

[헤더 알림 배지]  setInterval 60초 → GET /curated/submissions/unread-count (전체 사용자)
              + GET /admin/submissions/pending-count (isAdmin 일 때만 엘리먼트가 존재 → 폴링도 그때만)
```

---

## 6. 세션 관리

| 데이터 | 저장 위치 | 생명주기 |
|--------|----------|---------|
| `threadId` (현재 선택) | HTTP 세션 (`HttpSession`) | 브라우저 세션 |
| 대화 이력 텍스트 | SQLite `conversation_turns` | 영속 |
| 대화 제목·버전·라우팅 모드·태그 | SQLite `thread_meta` | 영속 |
| turn별 응답 모드·검색 스코프 태그 | SQLite `conversation_turns` (`response_mode`, `selected_tags`) | 영속 — 좋아요 승격 시 재사용(§4) |
| 선택 테마 (light/dark) | `localStorage` | 브라우저 영속 |

대화 제목은 첫 질문 직후 비동기(Virtual Thread)로 LLM 요약 생성 후 업데이트.

---

## 7. 에러 처리

| 상황 | 처리 방식 |
|------|----------|
| 채팅 API 오류 | `message-error.html` fragment (빨간 버블) |
| 파일 업로드 실패 | 파일 행 상태 ❌ + 오류 토스트 |
| 문서 삭제 실패 | `htmx:responseError` → 오류 토스트 |
| 문서 내보내기 실패 | `fetch` 응답이 실패(`!res.ok`)면 `ProblemDetail`(JSON)의 `detail`을 파싱해 오류 토스트로 표시(§3.2 문서 내보내기 다이얼로그 참고) — 저장 다이얼로그가 뜨지 않고 조용히 실패하는 대신 사유가 보임 |
| LOCAL_ONLY, LOCAL 미연결 | 빨간 버블 + `LlmProviderExhaustedException` 메시지 |
| 동시 사용자 급증으로 프로바이더 용량 초과 (§6.12, 429) | 빨간 버블 + "현재 요청이 몰려 있습니다. 잠시 후 다시 시도해 주세요." — 서킷브레이커 전면차단이 아니라 일시적 대기 상한 초과이므로 잠시 후 재시도하면 대개 성공 |
| 제안 등록 검증 실패 | 폼 POST → 플래시 리다이렉트로 페이지 상단 빨간 안내 + 입력 초안 복원(제목 200자 초과·태그 정책 위반·대기 20건 초과). 본문 길이는 애초에 제한이 없다 |
| 빈 질문 전송 | 클라이언트 validation, API 호출 차단 |
| 대용량 파일 | 업로드 전 200 MB 초과 감지 → 즉시 오류 표시 |

---

## 8. SSE 스트리밍

`POST /ui/chat/stream` → `SseEmitter(props.sseTimeoutMs())` → Virtual Thread에서 `StreamingAgentService.run()` 실행.

> **타임아웃은 두 겹이다**: `SseEmitter`에 들어가는 값은 활동 여부와 무관한 절대 상한
> `app.sse-timeout-seconds`(`SSE_TIMEOUT_SECONDS`, 기본 **7200초** — 폭주 생성에 대한 백스톱일 뿐)이고,
> 실제로 "멈춘 응답"을 끊는 건 `StreamingAgentService`가 직접 감시하는 무활동 타임아웃
> `app.sse-idle-timeout-seconds`(`SSE_IDLE_TIMEOUT_SECONDS`, 기본 **300초**)다 — 노드 전환·토큰·
> sources 이벤트가 올 때마다 리셋되므로 느리지만 계속 생성 중인 로컬 LLM 응답은 잘리지 않는다.

```
stage(classifier) → stage(retrieval) → sources → stage(answer) → token × N
→ stage(critic) → [stage(answer) × retry] → done
```

### SSE 이벤트 스펙

| event | data | 설명 |
|-------|------|------|
| `stage` | `{"id":"retrieval","text":"관련 문서 검색 중..."}` | 노드 진입 시 배지 교체 |
| `sources` | `[{"label":"...","preview":"..."}]` JSON 배열 | RETRIEVAL 완료 후 출처 배지 삽입 |
| `token` | `{"text":"텍스트 조각"}` | ANSWER 스트리밍 토큰 |
| `done` | 메타데이터 JSON (`usedProvider`, `inputTokens`, `elapsedMs`, `grounded`, `evalReason` 등) | 완료 시 마크다운 렌더 |
| `retry` | `{"reason":"answer\|critic","retryCount":1,"detail":"...","text":"..."}` | 검증 미통과 → 이전 답변을 미검증 블록으로 접고 재시도 안내 |
| `error` | `{"message":"오류 설명"}` | 오류 버블로 교체 |

> **검증 미통과 사유(`detail` / `evalReason`)**: `AnswerService.evaluate()`의 평가 LLM 호출이 `sufficient`/`grounded`와 함께 `reason`(한 문장)을 돌려주고, 그 값이 `AgentState.evalReason` → `GraphListener.onRetry(reason, retryCount, detail)` → `retry` 이벤트의 `detail`, 그리고 최종 `done` 이벤트의 `evalReason`으로 흐른다. 두 게이트가 모두 통과하면 `null`이라 UI가 알아서 생략한다. 표시 지점은 세 곳 — 재시도 안내 줄 아래("사유: …"), 접힌 미검증 블록의 요약줄, 그리고 재시도를 다 쓰고도 통과하지 못한 최종 답변의 메타데이터 줄(배지 `title` + 경고 한 줄). **툴팁만으로 끝내지 않는 이유**는 모바일에서 hover가 없기 때문이다. 블로킹/REST 경로도 같은 값을 `ChatResponse.grounded`/`eval_reason`으로 내보내고 `fragments/message-assistant.html`이 동일하게 렌더한다.

> **이미지 분석 진행 표시도 `stage` 이벤트를 재사용한다**: RETRIEVAL 중 쿼리 시점 Lazy Vision이 실행되면
> `{"id":"image_analysis","text":"이미지 분석 중 (2/5)"}`가 여러 번 발행된다 — 새 이벤트 타입을 만들지 않고
> 기존 `onStage()` 핸들러가 그대로 텍스트만 갱신하도록 재사용했다(단, `id`가 `"retrieval"`이 아니므로
> `onStage()`의 출처/이미지 패널 초기화 분기는 타지 않는다). 이 단계에서만 `chat-stream.js`가 배지 옆에
> **건너뛰기** 버튼(`#stream-skip-images-{bubbleId}`)을 노출하며, 클릭 시 `POST /ui/chat/stream/skip-images`를
> 호출한다(§3.1). 이미지 분석이 끝나거나 다음 단계(`answer` 등)로 넘어가면 버튼은 자동으로 다시 숨겨진다.

### 컴포넌트

| 파일 | 역할 |
|------|------|
| `service/StreamingAgentService.java` | SSE 파이프라인 오케스트레이터; `AgentGraph.runStreaming()` 호출; 턴 시작/종료에 `ChatImageAnalysisSkipRegistry.begin()`/`end()` |
| `service/GraphListener.java` | 노드/토큰/출처 이벤트 hook 인터페이스 (`NOOP` 상수로 동기 경로 오버헤드 0); `onImageAnalysisProgress(done, total)` — 쿼리 시점 Lazy Vision 진행 |
| `agent/AgentGraph.java` | `runStreaming(state, listener)` 메서드 — `AnswerService.executeStreaming()` 호출 |
| `service/RetrievalService.java` | `execute(state, listener)` — `LazyVisionService.describeIfNeeded()`에 진행 콜백 + 건너뛰기 신호(`BooleanSupplier`) 전달; 이미 설명이 임베드된 이미지는 애초에 제외(`hasEmbeddedDescription()` — 줄바꿈 형태와 **표 셀의 `<br>` 형태를 모두** 인식한다. 표 안에서는 개행이 행을 깨뜨려 두 주입 지점(`MarkdownCorrectionService`·`CuratedImageStore`)이 `<br>`를 쓰기 때문이며, 이걸 놓치면 표에 들어간 이미지는 매 턴 캐시 미스로 잡혀 같은 설명이 한 번 더 덧붙는다) |
| `service/LazyVisionService.java` | 쿼리 시점 이미지 설명 캐시 조회 + 미스만 Vision 호출; `describeIfNeeded(paths, onProgress, skipRequested)` — 건너뛰기 시 대기만 중단, 이미 제출된 백그라운드 작업은 계속 진행돼 캐시에 저장됨 |
| `service/ChatImageAnalysisSkipRegistry.java` | threadId별 건너뛰기 플래그(`ConcurrentHashMap<String, AtomicBoolean>`) — `ChatController`의 `/ui/chat/stream/skip-images`가 설정, `RetrievalService`가 폴링 |
| `service/AnswerService.java` | `executeStreaming(state, listener)` — `ChatClient.stream()` Flux 구독 → `listener.onToken()` |
| `static/js/chat-stream.js` | 클라이언트 SSE 파서; form submit capture, 버블 DOM 생성, 이벤트별 핸들러; 이미지 분석 건너뛰기 버튼 |

### PROGRESSIVE / 재시도 처리

- **PROGRESSIVE 업그레이드**: `listener.onUpgrade(provider)` → `stage(id=upgrade)` 이벤트, 콘텐츠 div 초기화 후 premium 답변 재채움
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
| 출처 미리보기 팝오버 | `@media (min-width:768px)`에서 팝오버 폭 확대(`max-width:620px`, `<768px`는 기본값 유지) — 배경·수치는 [§4 출처 Hover 미리보기](#출처-hover-미리보기) 참고 |

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
