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

### 3.3 운영 / LLM 사용량 (OperationsController)

| Method | Path | 반환 | 설명 |
|--------|------|------|------|
| GET | `/llm-usage` | `llm-usage.html` | LLM 사용량 페이지 |
| GET | `/ui/llm-usage/cards` | `fragments/llm-usage-cards` | 카드 HTMX 자동 갱신(30초). 채팅 프로바이더 + 임베딩(`embed:<model>`, `EMBEDDING` 배지) + orphan(설정에 없는 이름, `ORPHAN` 배지 + 삭제 버튼) 카드 포함 |
| DELETE | `/admin/llm-usage/{provider}` | `fragments/llm-usage-cards` | orphan 프로바이더의 누적 사용 기록 삭제. `/admin/**` 경로 아래 있어 `ROLE_ADMIN` 전용(no-auth 모드는 관리자 자동 인증 상속) — 컨트롤러는 `OperationsController` 소속, 경로만 admin 네임스페이스 |

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

> 상태 카드는 `AdminService.vectorStoreView()` → `VectorStoreAdminView`. 백엔드별 표시 차이는 [OPERATOR_MANUAL.md §7.4](OPERATOR_MANUAL.md) 참고.

> **청크 목록 컬럼**(`fragments/admin-chunks :: table`): ID·텍스트 미리보기·크기·파일명·페이지/슬라이드·챕터·키워드·작업. **챕터** 열은 `MetaKey.CHAPTER_NO`(H2~H6 헤딩 기반 계층 번호, 예: `1.5.3`)를 보여주며, "0"(프롤로그·PPTX·스캔 PDF — 실제 챕터 없음)이면 빈 칸으로 표시된다 — [§4 출처 Hover 미리보기](#출처-hover-미리보기)의 인용 라벨 로직과 동일한 컨벤션.
>
> **재인덱싱 시 수행 작업**(`DocumentIndexer.reindexFromMd()`) — 챕터 번호 재계산뿐 아니라 전체 파이프라인을 다시 돈다: 존재하지 않는 이미지 참조(`[이미지: ...]`) 제거 → 소제목 번호 재계산(PPTX 제외) → 전체 재청킹 → 태그 보존 → LLM 키워드+컨텍스트 재추출(§10.1) → 재임베딩 및 벡터 스토어 저장 → FTS 재인덱싱 → 기존 청크 삭제(신규 저장 이후, 실패 시 기존 데이터 보존). 즉 원본 MD가 수정된 이후 상태를 기준으로 사실상 전체를 다시 인덱싱한다.

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
- **프로바이더 활성/비활성**(`/admin/settings/provider/toggle`)은 위 `key`/`value` 오버라이드 메커니즘과 별개다 — `settings_override`에 저장되지 않는 메모리 전용(`ProviderToggle`) 토글이라 **재기동하면 초기화**된다. LLM 라우팅 표의 각 행에서 관리자에게만 활성화/비활성화 버튼이 보인다.
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

**출처 라벨 형식**: `RetrievalService.formatSource()`가 청크 메타데이터의 `chapter_no`(H2~H6 헤딩 기반 계층 번호, 예: `1.5.3`)가 "0"이 아니면 `"파일명 | 1.5.3"`, 아니면(프롤로그·PPTX·비스캔 PDF — 이 세 경우는 chapter_no가 항상 "0") `page_or_slide`로 폴백해 `"파일명 | p.12"`로 표시한다 — 문서 버전은 라벨에 포함되지 않는다.

출처 목록 항목에 Bootstrap Popover (`hover focus` 트리거). `SourceRef.preview`에 청크 텍스트 앞 500자 포함.

**팝오버 크기 (`app.css`, ≥768px 전용)**: Bootstrap 기본값(`max-width: 276px`, `font-size: 0.875rem`)은 500자 미리보기가 세로로 길게 줄바꿈되어 가독성이 떨어졌다 — `max-width: 560px`(약 2배), `font-size: 0.8rem`으로 넓히고 살짝 줄여 같은 500자가 더 적은 줄로 읽기 좋게 표시된다. `@media (min-width: 768px)` 블록 안에 있어 모바일(<768px)은 Bootstrap 기본값 그대로 — 좁은 화면에서 팝오버를 더 넓히면 화면 밖으로 넘칠 여지가 있기 때문.

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
              → progress 이벤트로 파일별 상태 갱신 → done/error 이벤트 후 목록 자동 새로고침

[문서 삭제]  hx-delete → hx-swap="outerHTML swap:0.3s" (페이드아웃)
[LLM 카드]   hx-trigger="load, every 30s" → 30초마다 자동 갱신
[LLM orphan 삭제] 🗑 버튼(orphan 카드에만 노출) → hx-confirm 확인 → hx-delete="/admin/llm-usage/{provider}"
              → hx-target="#llm-cards-target" → 응답으로 받은 fragments/llm-usage-cards로 즉시 교체
              (카드만 즉시 반영; 차트·기간별 표는 별도 vanilla JS fetch라 다음 로드/새로고침에 반영)
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
