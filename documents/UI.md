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
│   └── fragments/
│       ├── message-user.html              # 사용자 메시지 버블
│       ├── message-assistant.html         # 어시스턴트 버블 (메타데이터 포함)
│       ├── message-assistant-dual.html    # DUAL 모드 탭 버블
│       ├── message-error.html             # 오류 버블
│       ├── thread-list.html               # 대화 목록 사이드바
│       ├── thread-item.html               # 대화 목록 항목 1건
│       ├── doc-table-body.html            # 문서 목록 tbody (새로고침용)
│       ├── sync-result.html               # 동기화 결과 토스트
│       └── llm-usage-cards.html           # 프로바이더 상태 카드 (30초 자동 갱신)
└── static/
    ├── css/app.css                        # 버블·배지·DUAL 탭·타이핑 인디케이터 스타일
    ├── css/theme.css                      # light/dark CSS 변수
    └── js/command-palette.js             # Cmd+K 인터랙션
```

---

## 3. 라우팅 (WebController 엔드포인트)

| Method | Path | 반환 | 설명 |
|--------|------|------|------|
| GET | `/` | `chat.html` | 새 대화 |
| GET | `/chat/{threadId}` | `chat.html` | 기존 대화 이어하기 (이전 turn 서버 렌더) |
| POST | `/ui/chat` | `fragments/message-assistant` 또는 `message-assistant-dual` | 질문 전송 |
| POST | `/ui/chat/new` | redirect `/chat/{newId}` | 새 대화 생성 |
| PATCH | `/ui/threads/{threadId}/title` | `fragments/thread-item` | 대화 제목 수정 |
| PATCH | `/ui/threads/{threadId}/routing-mode` | `204` | 대화별 라우팅 모드 저장 |
| DELETE | `/ui/threads/{threadId}` | `200` | 대화 삭제 |
| GET | `/ui/threads` | `fragments/thread-list` | 대화 목록 새로고침 |
| POST | `/ui/documents/upload` | JSON (`DocumentInfo`) | 파일 업로드 |
| POST | `/ui/documents/sync` | `fragments/sync-result` | 폴더 동기화 |
| DELETE | `/ui/documents/{docId}` | `200` | 문서 삭제 |
| GET | `/ui/documents/list` | `fragments/doc-table-body` | 문서 목록 새로고침 |
| GET | `/llm-usage` | `llm-usage.html` | LLM 사용량 페이지 |
| GET | `/ui/llm-usage/cards` | `fragments/llm-usage-cards` | 카드 HTMX 자동 갱신 |

REST API (`ApiController`): `GET /api/llm/usage`, `GET /api/llm/usage/history?days=N`

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

출처 목록 항목에 Bootstrap Popover (`hover focus` 트리거). `SourceRef.preview`에 청크 텍스트 앞 200자 포함.

---

## 5. HTMX 인터랙션 흐름

```
[채팅 전송]  hx-post="/ui/chat" → hx-swap="beforeend" #chat-messages
              → DUAL: message-assistant-dual / 단일: message-assistant
              → HX-Trigger: "refreshThreadList" (사이드바 자동 갱신)

[제목 수정]  더블클릭 → 인라인 input → 포커스 아웃/Enter
              → hx-patch="/ui/threads/{id}/title" → fragments/thread-item

[대화 목록]  항목 클릭 → GET /chat/{threadId} → 전체 페이지 전환

[파일 업로드] Fetch API + XHR onprogress → 파일별 상태 표시
              → 업로드 완료 후 인덱싱 중 → 완료 시 목록 자동 새로고침

[동기화]     hx-post="/ui/documents/sync" → fragments/sync-result → 토스트
[문서 삭제]  hx-delete → hx-swap="outerHTML swap:0.3s" (페이드아웃)
[LLM 카드]   hx-trigger="load, every 30s" → 30초마다 자동 갱신
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
| 동기화 실패 | 오류 토스트 |
| 문서 삭제 실패 | `htmx:responseError` → 오류 토스트 |
| DUAL, LOCAL 미연결 | 드롭다운 `disabled` + 툴팁 |
| LOCAL_ONLY, LOCAL 미연결 | 빨간 버블 + `LlmProviderExhaustedException` 메시지 |
| 빈 질문 전송 | 클라이언트 validation, API 호출 차단 |
| 대용량 파일 | 업로드 전 100 MB 초과 감지 → 즉시 오류 표시 |

---

## 8. 미구현 — SSE 스트리밍

현재 `AgentGraph`는 동기 멀티 노드 구조(CLASSIFIER→RETRIEVAL→ANSWER→CRITIC→FINALIZE)이므로
Answer 노드만 스트리밍해도 후속 Critic이 완성 텍스트를 필요로 합니다.
구현 옵션:
- CRITIC 비활성 단순 스트리밍 모드 (품질 체크 포기)
- 노드별 SSE 이벤트 전송 (클라이언트에서 단계별 진행 표시)
