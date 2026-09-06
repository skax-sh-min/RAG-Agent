# RAG Agent — Project Guide

> **이 파일은 자주 바뀌지 않는 것만 담는다** — 스택, 구조, 파일의 역할, 규약, 하드 제약.
> 각 항목의 **결정 근거 · 함정 · 실패 기록**은 [documents/PITFALLS.md](documents/PITFALLS.md) 에 있고,
> `↗` 링크가 해당 항목으로 바로 간다. **그 파일들을 고치기 전에는 링크를 따라가 읽을 것** —
> 대부분이 실제로 한 번 깨졌던 것의 기록이라, 불필요해 보이는 코드가 왜 거기 있는지를 설명한다.

## Stack

- **Backend**: Spring Boot 3 + Spring AI, Java 21 (virtual threads on), SQLite (WAL, pool=1)
- **Frontend**: Thymeleaf + HTMX, Bootstrap 5, no JS framework
- **Vector DB**: `app.vectorstore.type` selects the backend (Phase 5) — `chroma` (default; per-version collections via `VectorStoreRegistry`) or `sqlite-vec` (vec0 virtual table in the SQLite file). Both behind `VectorStoreProvider`, injected into `VectorStoreFacade`.
- **LLM**: OpenAI-compatible endpoint (Spring AI `ChatClient`); local LLM-Studio or remote

## Architecture

```
AgentGraph (state machine) → nodes: CLASSIFIER → RETRIEVAL → ANSWER → CRITIC → FINALIZE
AgentState: immutable record, each node returns new instance via state.toBuilder().xxx().build()
```

Flow:
- `meta` question → DIRECT_ANSWER (skip retrieval)
- others → RETRIEVAL → ANSWER (sufficiency check) → CRITIC → FINALIZE, retry loop up to `maxRetryCount`
- `directMode=true` → AgentGraph skips CLASSIFIER/RETRIEVAL/CRITIC entirely
- `AgentService.chat()` pre-runs history load + classify in parallel; AgentGraph skips CLASSIFIER when `questionType != null`
- SSE streaming → `StreamingAgentService.run()` drives `AgentGraph.runStreaming()` with `SseGraphListener`

## Key Files

> 역할 한 줄만 적는다. `↗` 가 붙은 항목은 [PITFALLS.md](documents/PITFALLS.md) 에 결정 근거와 깨지는 지점이 있다.

| Path | Role |
|------|------|
| `agent/AgentGraph.java` | State machine; skips CLASSIFIER when `questionType != null` [↗](documents/PITFALLS.md#agentagentgraphjava) |
| `agent/AgentState.java` | 불변 상태 레코드. 컴포넌트를 추가하면 `AgentState.of()` 의 위치 기반 생성자도 함께 고쳐야 한다 [↗](documents/PITFALLS.md#agentagentstatejava) |
| `controller/ChatController.java` | REST `POST /api/v1/chat`; HTMX `/ui/chat`, `/ui/chat/stream`, `/ui/chat/new`; thread title/routing-mode/delete; `POST /ui/chat/stream/skip-images` — forwards `threadId` to `ChatImageAnalysisSkipRegistry.requestSkip()`, `204` |
| `controller/DocumentController.java` | REST `/api/v1/documents`, `/api/v1/images`; HTMX async upload (202+taskId), SSE progress; magic-byte validation |
| `controller/OperationsController.java` | REST `GET /api/v1/health`, `/api/v1/llm/usage`, `/api/v1/llm/concurrency` (header's `LLM: inUse/capacity` indicator, polled ~3s — `{"available":false}` when no LOCAL priority=1 provider exists); HTMX thread list, LLM usage cards; page routes |
| `controller/AdminController.java` | `/admin`, `/admin/chunks`; document re-index endpoint (async, SSE-tracked) [↗](documents/PITFALLS.md#controlleradmincontrollerjava) |
| `service/AdminService.java` | `/admin` chunk browse/edit/delete (chroma + sqlite-vec). **청크 조회는 두 갈래다** — 페이지 렌더는 `getChunks(collection, docId, offset, limit)`, 전체가 필요한 경로(내보내기·재인덱싱 사전 확인)만 `getAllChunks()`. Chroma 의 `get()` 에는 서버 측 ORDER BY 가 없어 순서를 이쪽에서 정해야 하므로 `getChunks()` 는 **두 번 읽는다**: 1단계는 메타데이터만(정렬 기준 `doc_id`/`chunk_index` 가 전부 거기 있다), 2단계는 그 페이지에 실제로 보이는 것만 본문과 함께. 한 번에 본문까지 받아 자바에서 자르면 페이지를 넘길 때마다 청크 전량(캡 10,000 × 기본 1,500자)을 전송·파싱해 20개만 쓰고 버린다. **페이지 렌더에서 `getAllChunks()` 를 부르면 그 상태로 되돌아간다**; `updateChunk()` = metadata/text only, embedding untouched (메타데이터 JSON 은 화면에서 **읽기 전용** — 편집이 조용히 무효가 되거나 재인덱싱과 만나 청크를 고아로 만든다 [↗](documents/PITFALLS.md#청크-편집의-메타데이터json-는-읽기-전용이다)); `reindexChunk()` = actually re-embeds (id-preserving upsert) + re-indexes FTS for one chunk, optional `regenerateKeywords` re-runs `KeywordExtractor` for that chunk only (one LLM call) |
| `controller/SettingsController.java` | §6.13 — `GET /settings` (read view, guest-open, edit gated by `isAdmin`) [↗](documents/PITFALLS.md#controllersettingscontrollerjava) |
| `service/SettingsService.java` | §6.13 설정 오버라이드 계층 + 프로바이더 표/토글. `LOCAL_ONLY` 에서는 LOCAL 역할만 노출 [↗](documents/PITFALLS.md#servicesettingsservicejava) |
| `controller/OperationsController.java` (턴 삭제) | `DELETE /ui/threads/{threadId}/turns/{turnId}` [↗](documents/PITFALLS.md#controlleroperationscontrollerjava-턴-삭제) |
| `controller/GlobalExceptionHandler.java` | `@RestControllerAdvice`; RFC 9457 ProblemDetail; handles `IllegalArgumentException` → 400, `MaxUploadSizeExceededException` → 413 |
| `controller/AuthController.java` | `/login`, `/signup`, `/setup` page controllers; auto-login after signup; `/setup` guarded to no-auth mode only |
| `controller/GlobalModelAdvice.java` | `@ControllerAdvice`; injects `authEnabled`/`managementOnly`/`isAdmin` model attrs into all views; null-safe for `@WebMvcTest` mocks |
| `security/SecurityConfig.java` | Three-way conditional filter chain: full-auth (form login, CSRF, sessions) vs. plain no-auth (STATELESS, CSRF off, `NoAuthAutoLoginFilter`) vs. management-only (§6.17 B안 — IF_REQUIRED sessions, cookie-based CSRF, `/admin/**` + document-write UI gated `hasRole("ADMIN")`). **`/admin/**` 은 full-auth 모드에서도 `hasRole("ADMIN")` 이다** (§6.19.2) — `/signup` 이 permitAll 이라 `.authenticated()` 로는 가입만 하면 관리자가 됐다. `/actuator/**`(health 제외)도 두 모드에서 같은 게이트를 받는다 — `POST /actuator/loggers/{name}` 로 TRACE 를 켜면 `LlmCurlLogger` 가 검색 문서 본문이 실린 프롬프트 전문을 로그 파일에 남기기 때문. 평문 no-auth 는 폐쇄망 단일 운영자 전제라 예외 |
| `security/NoAuthAutoLoginFilter.java` | `@ConditionalOnProperty(name="app.auth.enabled", havingValue="false")`; auto-injects guest/admin identity; redirects to `/setup` until admin exists. The guest principal's id comes from `GuestIdentityResolver` (constant `GUEST_ID` under the default `shared` strategy, per-visitor otherwise) |
| `security/GuestIdentityResolver.java` | no-auth 모드의 방문자별 `userId` (`app.auth.guest-identity` = `shared`/`ip`/`cookie`/`hybrid`) [↗](documents/PITFALLS.md#securityguestidentityresolverjava) |
| `security/ClientIpResolver.java` | PLAN §6.19.3 — the single place deciding "this request's client IP" for both `RateLimitFilter` and `GuestIdentityResolver` [↗](documents/PITFALLS.md#securityclientipresolverjava) |
| `security/SqliteUserDetailsService.java` | `loadUserByUsername`, `createUser`, `createAdminUser`, `findFirstAdmin()`, `emailExists`, lock management |
| `llm/CircuitBreaker.java` | In-memory per-provider block (Retry-After aware) |
| `llm/IndexingOutputCap.java` | 인덱싱/백그라운드 호출의 **출력 예약**을 작업 크기에 맞춰 좁힌다 [↗](documents/PITFALLS.md#llmindexingoutputcapjava) |
| `llm/PromptBudget.java` | 입력 토큰 예산 = `창 − 출력 예약 − 여유(창의 10%, 최소 256)` [↗](documents/PITFALLS.md#llmpromptbudgetjava) |
| `llm/TokenEstimateCalibration.java` | `TokenEstimator` 의 추정을 **서버가 실제로 센 토큰 수**와 대조하는 관측 계층 [↗](documents/PITFALLS.md#llmtokenestimatecalibrationjava) |
| `llm/PromptSizeLog.java` | 프롬프트를 **구성 요소별로** 재서 한 줄로 찍는 디버그 로그 포매터(순수 클래스). `AnswerService` 가 답변·검증 두 호출에 각각 `[PROMPT]` 한 줄씩 남긴다 — 토큰(추정)과 바이트(실제)를 함께 찍는 이유는 창을 넘겼을 때 필요한 답이 "합계"가 아니라 **무엇이 부풀렸는가**이기 때문. `log.isDebugEnabled()` 안에서만 돈다(`LOGGING_LEVEL=DEBUG`) |
| `llm/TokenEstimator.java` | 실제 토큰 수를 알 수 없을 때 쓰는 **단 하나의** 추정 가정: `CJK 글자수 × 1 + 나머지 글자수 / 4` [↗](documents/PITFALLS.md#llmtokenestimatorjava) |
| `llm/ProviderContextWindows.java` | 프로바이더별 컨텍스트 창(토큰)을 기록하는 이름-키 레지스트리(기동 시 1회 + `/settings` 의 재탐지 버튼, §6.26 A5) (`ProviderToggle` 선례 — `LlmProvider` 레코드는 40곳에서 생성되고, 이건 프로바이더를 식별하는 값이 아니라 그에 관해 관측된 값이라 레코드가 나를 이유가 없다). 출처는 `context-size` 선언 또는 `ContextWindowProbe` 탐지이며, **둘 다 없으면 항목 자체가 없다** — 0 이나 기본값이 아니라 "모름"을 값으로 표현해야 추측한 숫자로 입력 예산을 짜는 일이 없다 |
| `service/SettingsService.reprobeContextWindows()` | §6.26 A5 — 기동 시 한 번 탐지한 창은 낡는다(서버를 다른 `-c` 로 재시작, LM Studio 의 JIT 로딩) [↗](documents/PITFALLS.md#servicesettingsservicereprobecontextwindows) |
| `llm/ContextWindowProbe.java` | 로컬 서버에 실제 컨텍스트 창을 물어본다. **OpenAI 호환 `/v1/models` 에는 컨텍스트 필드가 없어** 서버별 경로를 쓴다 [↗](documents/PITFALLS.md#llmcontextwindowprobejava) |
| `llm/MaxTokensCappingChatModel.java` | 프로바이더별 `max-tokens` 상한을 호출자 옵션 위에 씌우는 데코레이터 [↗](documents/PITFALLS.md#llmmaxtokenscappingchatmodeljava) |
| `llm/LlmRouter.java` | Provider selection by TaskType × RoutingMode [↗](documents/PITFALLS.md#llmllmrouterjava) |
| `llm/EmbeddingConcurrencyTracker.java` | Plain `AtomicInteger` in-flight counter for genuine outbound embedding calls (never negative, `get()` floors at 0) [↗](documents/PITFALLS.md#llmembeddingconcurrencytrackerjava) |
| `llm/ConcurrencyLimitingChatModel.java` | `ChatModel` decorator (mirrors `TrackingChatModel`) — applies `LlmRouter.acquirePermit()` around `.call()` for framework-internal callers (e.g. `RetrievalService`'s `MultiQueryExpander` model) that bypass `executeGated()` |
| `repository/LlmUsageRepository.java` | Daily UPSERT token tracking in SQLite |
| `config/AppProperties.java` | `@ConfigurationProperties(prefix="app")`, `llmSafe()`, `indexingSafe()`, `imageDescriptionSafe()` null guards |
| `audit/AuditLogger.java` | Writes structured audit events to rolling file via Logback AUDIT_FILE appender |
| `context/ThreadContext.java` | Per-request record (`threadId`, `userId`, `locale`); resolved by `ThreadContextResolver` (`HandlerMethodArgumentResolver`) |
| `ingestion/DocumentIndexer.java` | Core indexing orchestration (previously in `RagService`); 3-phase sync, parallel index with Semaphore, `DocRegistry` SQLite persistence; delegates chunking to `ChunkSplitter` and keyword enrichment to `KeywordExtractor` |
| `ingestion/ChunkSplitter.java` | Pure chunk-splitting/merging algorithm [↗](documents/PITFALLS.md#ingestionchunksplitterjava) |
| `ingestion/KeywordExtractor.java` | 청크당 키워드 **+ 맥락** 추출을 LLM 한 번으로 (§10.1 Contextual Retrieval) [↗](documents/PITFALLS.md#ingestionkeywordextractorjava) |
| `service/MarkdownCorrectionService.java` / `service/TextToMarkdownService.java` (입력 크기) | LLM 에 넘기는 한 조각의 크기는 **두 값 중 작은 쪽**이다 [↗](documents/PITFALLS.md#servicemarkdowncorrectionservicejava--servicetexttomarkdownservicejava-입력-크기) |
| `ingestion/MarkdownNoiseNormalizer.java` | Pure text util (no `@Component`) — strips decorative markdown lines and emphasis markers for embedding/FTS/answer-prompt input only; stored/displayed text (`Document.getText()`) is never touched |
| `ingestion/SearchTextBuilder.java` | Builds the embedding/FTS derived text = `MetaKey.CHUNK_CONTEXT` + `MarkdownNoiseNormalizer.normalize(doc.getText())`; shared by both `VectorStoreProvider` impls and `KeywordSearchRepository.indexChunks()` |
| `ingestion/DocRegistry.java` (태그) | 문서의 검색 스코프 태그(`doc_registry.tags`, CSV)의 **권위 있는 출처**. `tagsByDocIds()`/`distinctTags()`/`distinctTagsExcludingCommon()` 가 목록·제안 UI 를 먹인다 — 행 수가 **문서 수**이고 PK 인덱스를 탄다. 예전 출처였던 `chunk_fts.doc_tags` 는 FTS5 의 `UNINDEXED` 컬럼이라 `WHERE doc_id IN (...)` 이 **코퍼스 전체 스캔**(본문 포함)이었고, 그 조회를 `RagService.listDocuments()` 가 불러 문서 목록·관리자 화면·`/admin/chunks` 페이지 넘김마다 돌았다. `put()` 의 UPSERT 는 `tags` 를 건드리지 않으므로 **재인덱싱이 태그를 보존**하고, 그 대가로 인덱싱 경로가 `updateTags()` 를 명시적으로 불러야 한다(빼먹으면 새 문서의 `tags` 가 NULL 로 남아 `DocTagsBackfill` 이 옛 행으로 오인한다). NULL = 아직 백필 안 됨 / 빈 문자열 = 태그 없음 |
| `ingestion/DocTagsBackfill.java` | 위 이관의 일회성 백필(`ApplicationReadyEvent`, `ChunkOverlapBackfill` 선례). `tags IS NULL` 인 행만 대상이라 멱등이며, **`chunk_fts` 의 태그 컬럼을 읽는 마지막 코드**다 |
| `ingestion/KeywordSearchRepository.java` | SQLite FTS5 `chunk_fts` index (BM25 keyword axis for hybrid search) [↗](documents/PITFALLS.md#ingestionkeywordsearchrepositoryjava) |
| `ratelimit/RateLimitFilter.java` | Bucket4j + Caffeine per-user token-bucket; returns 429 + `RAG-RATE-001` + `Retry-After` header |
| `service/IndexingProgressService.java` | SSE emitter registry for async upload/sync progress; event buffer prevents race condition; terminal stages: `done`, `error`, `sync_done` |
| `model/MetaKey.java` | Vector store metadata key constants — always use these, never raw strings |
| `model/SourceRef.java` | 출처 1건. 검색 진단 수치·응답 참여도·청크 변경 상태를 함께 나르며 전부 nullable(`null` = 측정 안 됨) [↗](documents/PITFALLS.md#modelsourcerefjava) |
| `service/QuestionReuseService.java` | § 질문 재사용 — 과거 턴의 답변을 LLM 호출 없이 재사용(`/api/v1/questions/reuse`)하고, 그 답변이 아직 유효한지 판정한다 [↗](documents/PITFALLS.md#servicequestionreuseservicejava) |
| `repository/QuestionReuseRepository.java` | `turn_source_ref` 테이블(런타임 멱등 DDL + 방어적 `ALTER TABLE`로 `answer_share`/`invalidated_at` 추가) [↗](documents/PITFALLS.md#repositoryquestionreuserepositoryjava) |
| `security/FileTypeDetector.java` | Magic-byte validation for uploads (PDF, DOCX/PPTX, TXT/MD) |
| `service/DocumentBackupCleaner.java` | §6.15 — `data/documents/backup/` 보존 정책 (최신본 유지 → 보존일 → 용량, 순서대로) [↗](documents/PITFALLS.md#servicedocumentbackupcleanerjava) |
| `service/StorageQuotaService.java` | §6.15 — the deployment-wide storage cap (`app.upload.max-total-size`, **0 = unlimited, the default**) [↗](documents/PITFALLS.md#servicestoragequotaservicejava) |
| `security/PromptInjectionGuard.java` | `validate()` length/blank check (MAX=2000); `wrap()` for delimiter isolation; `maskApiKey()` for safe logging |
| `service/RetrievalMetricsService.java` | 3단계 — `/admin` 검색 진단 패널의 읽기 계층 [↗](documents/PITFALLS.md#serviceretrievalmetricsservicejava) |
| `repository/ThreadAdminRepository.java` / `service/ThreadAdminService.java` | §6.25 — `/admin` 대화 목록(전 사용자)의 조회·삭제 계층 [↗](documents/PITFALLS.md#repositorythreadadminrepositoryjava--servicethreadadminservicejava) |
| `service/AnswerAttribution.java` | 2단계 응답 참여도 — 답변 문장을 가장 닮은 청크에 배정하고 글자수 비율을 몫으로 낸다 [↗](documents/PITFALLS.md#serviceanswerattributionjava) |
| `service/FinalizeService.java` | 대화 저장 노드(실제 저장은 AgentService/StreamingAgentService) + **응답 참여도 계산 지점**. 그래프에서 답변과 검색 문서가 동시에 확정되는 유일한 곳 — ANSWER는 재시도·PROGRESSIVE로 여러 번 도므로 버려질 답변에 대해 계산하면 낭비다. 참여도는 진단값이라 계산 실패가 완성된 답변의 전달을 막아선 안 되고, 예외는 "참여도 없음"으로 degrade된다 |
| `service/HistoryPolicy.java` | §10.13 — `[이전 대화]` 에 **얼마나** 넣고 각 턴을 **무엇으로** 렌더할지 정하는 순수 클래스. 이력 경로 둘(요약 `[Recent]` · 폴백 `getHistory()`)이 읽는 단일 출처. `promptTurnCap()` = 프롬프트에 싣는 턴 수 상한(가져오는 창의 절반, 기본 5) [↗](documents/PITFALLS.md#servicehistorypolicyjava) |
| `service/AnswerService.java` (축소 재시도) | **사전 예산이 빗나가면 문서를 `app.llm.shrink-step` 개씩 덜어내 다시 시도한다** (`withShrinkRetry()`, 상한 5회) [↗](documents/PITFALLS.md#serviceanswerservicejava-축소-재시도) |
| `service/AnswerService.java` | **입력 예산 사전 축소** — `buildAnswerPrompt()` 가 조립 전에 `fitToBudget()` 을 거친다(모든 답변 경로가 지나는 유일한 지점). 프로바이더는 `llmRouter.findProviderName()` 으로 먼저 묻는다: 실제 호출 사이에 답이 달라질 수 있지만 대체되는 것은 보통 창이 더 큰 다른 역할이라 "덜 잘랐어야 했는데 더 잘랐다" 쪽이고, 같은 우선순위 형제는 같은 모델이라 창이 같다 — 초과를 부르는 방향이 아니다. **문서를 먼저, 그 다음 이력**을 버린다(검색 하위 문서는 답변에 기여하지 않는 일이 흔하지만 이력이 사라지면 사용자가 즉시 체감한다). 이력은 `"

" + "Q: "` 경계에서만 자른다 — 문자 인덱스로 자르면 반쪽 턴이 남아 모델을 더 헷갈리게 한다. 창을 모르면 **아무것도 하지 않는다**. 출력 예약은 스트리밍에도 적용된다(캡을 안 붙여도 답변이 자랄 자리는 필요하다). 2-call pattern: answer + sufficiency (the eval call returns `sufficient`/`grounded` **and a one-sentence `reason`**, kept as `AgentState.evalReason` only when a gate failed — so a verification failure is explainable instead of a bare boolean; advisory only, never affects routing, and a model that omits it degrades to the old behavior). **환경 의존 값 예외** — 경로·호스트·IP·포트·URL·환경변수 값·계정명은 문서를 쓴 기계와 읽는 기계가 다르면 달라지는 게 정상이라, `prompt.answer.eval`이 **그것만으로는 `grounded=false`를 내지 못하게 금지**하고 대신 네 번째 필드 `envNote`로 받는다(절차·동작·인과관계가 문서와 다르면 여전히 `grounded=false`). `evalReason`과 반대로 **검증을 통과해도 유지**된다 — 판정이 아니라 "이 경로는 본인 환경 기준으로 바꾸라"는 독자용 안내이기 때문. **5번째 필드 `usedDocs`**(2단계) — 답변이 실제로 근거로 삼은 `[Dn]` 발췌 번호. 같은 호출이 이미 답변과 전 발췌를 들고 있어 **추가 왕복 0회**이고, `buildEvalExcerpts()`가 발췌마다 `[D1]`,`[D2]`… 번호를 붙이는 이유가 이것이다(크기 상한은 꼬리부터 자르므로 포함된 문서의 번호는 절대 밀리지 않는다). 판정에 전혀 쓰이지 않는 advisory이며 `AnswerAttribution`의 후보 축소 신호로만 쓰인다. `AgentState.envNote` → `ChatResponse.env_note` / SSE `done.envNote` → `message-assistant.html`·`chat-stream.js`가 같은 문구로 렌더. 규칙이 프롬프트에만 있어 코드로는 아무도 눈치채지 못하므로 `AnswerEvalPromptTest`가 실제 번들(한/영)을 검사한다. **평가는 `retrievedDocs` 전체를 본다** (`buildEvalExcerpts()`) — 예전엔 `.limit(5)`였는데 답변 프롬프트는 topK(기본 10) 전체를 쓰므로, 6~8번째 문서에만 있는 값(경로·포트·상수처럼 한 청크에만 나오는 사실)을 정확히 인용한 답변이 근거 없음 판정을 받았다. **재시도만으로는 못 고친다** — `RetrievalService`의 최종 컷은 재시도당 한 개씩만 늘어나므로(`topK + retryCount`) 검증 창을 답변 창에 맞추는 건 여전히 이 메서드의 몫이다. 발췌는 답변 프롬프트와 같은 `MarkdownNoiseNormalizer.normalize()`를 거쳐 두 호출이 같은 **형태**의 값을 본다(평가만 raw `**8080**`을 보던 불일치 제거). `MAX_EVAL_EXCERPT_CHARS`(32,000, 기본 설정 8×1500=12,000은 여유롭게 통과)는 과대 설정용 안전판일 뿐이며, 초과 시 문서를 **통째로** 하위 순위부터 제외한다 — 중간을 자르면 검증 대상인 그 값이 사라지기 때문. **크기 산정의 기준은 답변 호출이 아니라 이 검증 호출이다** — 질문+답변 전문+발췌+스키마가 한 번에 들어가는 이 앱 최대의 단일 요청이라, 32,000자에 실제로 닿으려면 64k급 `n_ctx`가 필요하고 32k 이하에서는 `SEARCH_TOP_K × CHUNK_SIZE`가 먼저 한계를 정한다(OPERATOR_MANUAL §8 산정표). 같은 이유로 `evalOptions()`는 `MAX_EVAL_OUTPUT_TOKENS`(2,048, `min(설정값, …)`)로 **출력도 따로 제한한다** — 상한을 안 걸면 프로바이더에 구워진 `app.llm.max-tokens` 전체가 JSON 몇 필드짜리 응답을 위해 예약되고, 좁은 컨텍스트에서 `n_ctx`를 넘기는 것은 발췌가 아니라 그 예약이다(빈 응답 → 판정 없음). 값이 넉넉한 이유는 content로 짧은 추론을 흘리는 모델도 JSON까지 도달해야 하기 때문 — 잘린 응답은 파싱 실패로 판정을 통째로 잃는다; PROGRESSIVE upgrade; per-turn `ResponseMode` (S/N/C) picks the system prompt, the temperature (`answerTemperature()` — creative vs general, applied on the blocking **and** the `streamDirect()` streaming path), and the blocking calls' `maxTokens`. **C swaps the evaluator rather than skipping it** — `evaluate()` delegates to `evaluateCreative()` when `usesCreativeEval()`, which reads `prompt.answer.eval.creative` into a separate `CreativeEvalOutput(sufficient, apiGrounded, inventedSymbols, envNote)`; `apiGrounded` is stored in `grounded` so `CriticService` needs no change, `inventedSymbols` never gates a retry, and both evaluators share `buildEvalPrompt()`/`buildEvalExcerpts()` so they always judge the same evidence; `truncate()` caps at 20,000 chars (absolute, mode-independent) — it cuts back to the last line break and **closes an open code fence before appending the notice**, since an odd fence count makes `MarkdownCorrectionService.normalizeCodeBlocks()` skip language tagging and code cleanup for the *entire* document that answer later reaches via export/re-index. It counts fences with `MarkdownCorrectionService.fenceLineCount()` (package-private, static) rather than its own copy, so the repair and the guard can't drift apart. Truncation cannot create a *mid-line* fence — that needs content and ``` on one line, and truncation only removes from the tail |
| `service/DirectAnswerService.java` | meta/directMode answers, no retrieval; blocking + streaming (raw `OpenAiApi.chatCompletionStream()` bypass to avoid `OpenAiChatModel`'s buffering); §6.18 — attaches `directTemperature` per call via `Prompt`/`ChatCompletionRequest` options, hot-editable |
| `service/CancellableTokenStream.java` | 두 스트리밍 경로(`AnswerService.streamDirect()`, `DirectAnswerService.callOrStream()`)가 LLM 토큰을 소비하는 **유일한** 방법 [↗](documents/PITFALLS.md#servicecancellabletokenstreamjava) |
| `service/AgentService.java` | Entry point; `PromptInjectionGuard.validate()` at entry; parallel history + classify before graph |
| `service/StreamingAgentService.java` | SSE pipeline; Virtual Thread worker; heartbeat every 15 s; partial answer persisted on error); calls `ChatImageAnalysisSkipRegistry.begin(threadId)` at the start of `run()` and `.end(threadId)` in its `finally`, so a leftover skip click from a prior turn can never bleed into the next one on the same thread |
| `service/SseHeartbeat.java` | 두 SSE 경로(`StreamingAgentService` 15초 · `IndexingProgressService` 25초)가 하트비트를 보내는 **유일한** 방법. 스케줄러 스레드는 깨우기만 하고 쓰기는 가상 스레드에서 한다 — `emitter.send()` 는 블로킹 소켓 쓰기라, 스케줄러 태스크 안에서 직접 보내면 **느린 클라이언트 한 명이 그 스레드를 붙잡아** 다른 모든 대화의 하트비트와 `StreamingAgentService` 의 **유휴 워치독까지** 함께 밀린다(= `app.sse-idle-timeout-seconds` 가 설계대로 동작하지 않는다). 앞 하트비트가 아직 안 나갔으면 이번 tick 은 건너뛴다 — `ResponseBodyEmitter` 의 `writeLock` 때문에 답변 토큰이 락을 쥔 동안 하트비트 스레드가 쌓일 수 있고, 하트비트는 누적이 의미 없는 신호다. 순수 클래스(전송 방법·실행 위치를 생성자로 받는다) |
| `service/GraphListener.java` | Hook interface for node/token/source/upgrade/retry/verifying events, injected into `AgentGraph.runStreaming()` [↗](documents/PITFALLS.md#servicegraphlistenerjava) |
| `service/LazyVisionService.java` | Query-time image description: `image_descriptions` cache lookup, Vision-calls only misses [↗](documents/PITFALLS.md#servicelazyvisionservicejava) |
| `service/ChatImageAnalysisSkipRegistry.java` | `Map<threadId, AtomicBoolean>` — the "건너뛰기" (skip) signal for the chat SSE path, distinct from the full-turn abort (`ChatController.streamChat()`'s `SseEmitter.onError`/`onTimeout`/`onCompletion` → `worker.interrupt()`). Written by `POST /ui/chat/stream/skip-images`, polled by `RetrievalService` |
| `service/ClassifierService.java` | `classifyOnly(String)` (no token accumulation) + `execute(AgentState)` |
| `service/RetrievalService.java` | Batch MultiQuery search → RRF fusion [↗](documents/PITFALLS.md#serviceretrievalservicejava) |
| `service/QuestionCondenser.java` | §10.12 — 맥락에 기댄 **짧은 후속 질문**을 자립적인 검색어로 다시 쓴다(condense). 게이트는 `shouldExpand()` 의 여집합이라 한 턴에 질의 전처리 LLM 호출은 여전히 최대 하나. 재료는 이전 **질문**들뿐이고 답변은 넣지 않는다 [↗](documents/PITFALLS.md#servicequestioncondenserjava) |
| `service/RetrievalEviction.java` | § 재시도 개선 — 검증 실패 재시도에서 **어떤 청크를 밀어낼지** 정하는 순수 클래스(`AnswerAttribution`/`ChunkReassembler` 선례) [↗](documents/PITFALLS.md#serviceretrievalevictionjava) |
| `service/RerankerService.java` | LLM reranking (opt-in, `@ConditionalOnProperty app.search-rerank-enabled`); one LLM call reorders the candidate pool by relevance then cuts to topK; `parseRanking()` parses a JSON index array with range/dup filtering; falls back to original RRF order on parse failure |
| `service/RagService.java` | 3-phase `syncDirectory()`: detect → parallel index → delete; `enrichParallel()` with Semaphore |
| `service/SettingsService.java` | §6.13 — implements `AppProperties.OverrideSource`, binds it at `@PostConstruct`; catalog (hot-editable vs restart-required), `update()`/`reset()` with type+range validation + `AuditLogger`, `buildView()`; in-memory override cache (read hot-path never hits SQLite); backed by `SettingsOverrideRepository` (`settings_override` in memory.db) |
| `service/VisionDescriptionService.java` | Image bytes → Korean description via `LlmRouter.route(VISION, COST_FIRST)` |
| `service/DocumentExportService.java` | § 문서 내보내기 — 청크 재조립 → 결정적 후처리 → 포맷별 렌더(MD/TXT/DOCX) [↗](documents/PITFALLS.md#servicedocumentexportservicejava) |
| `export/ChunkReassembler.java` | § 문서 내보내기 — `ChunkSplitter` 의 검색용 중복을 역순으로 되돌리는 순수 클래스 [↗](documents/PITFALLS.md#exportchunkreassemblerjava) |
| `export/ExportPreprocessor.java` | § 문서 내보내기 — 인덱싱 마커를 독자용 마크다운으로 재작성(이미지·페이지·도형 마커) [↗](documents/PITFALLS.md#exportexportpreprocessorjava) |
| `export/DocxRenderer.java` | § 문서 내보내기 — `ExportPreprocessor` 가 내는 것만 다루는 줄 단위 POI 렌더러 [↗](documents/PITFALLS.md#exportdocxrendererjava) |
| `export/PlainTextRenderer.java` | § 문서 내보내기 — TXT format: strips markdown syntax, keeps code-fence contents. Blockquote markers must strip **before** heading markers — a Vision `[이미지 설명: ...]` can itself contain a markdown heading (`"> ### 옵션 1"`), so heading-first stripping would leave a stranded `###` |
| `export/ExportFormat.java` | § 문서 내보내기 — `MD`/`TXT`/`DOCX` enum + content types; `parse()` rejects unknown/blank input (400 via `GlobalExceptionHandler`). No `PPTX` — rebuilding slides from reassembled prose needs slide-boundary/layout rules the chunk data doesn't carry |
| `ingestion/ChunkOverlapBackfill.java` | `ApplicationReadyEvent` — `doc_registry.chunk_overlap` 이 `NULL` 인 기존 문서를 채운다 [↗](documents/PITFALLS.md#ingestionchunkoverlapbackfilljava) |
| `service/CuratedQaService.java` | §10.10 — 공유 큐레이션 Q&A 축(예약 네임스페이스 `"curated"`). §10.11 이후 여기에 쓰는 경로는 **관리자 승인 둘뿐**(`createFromSubmission`/`createFromLikedTurn`) — 좋아요는 아무것도 만들지 않는다 [↗](documents/PITFALLS.md#servicecuratedqaservicejava) |
| `service/CuratedQuestionSuggester.java` | 큐레이션 Q&A 의 **질문**을 본문에서 더 구체적으로 다시 쓰자고 **제안만** 한다(`/admin` 편집의 "본문으로 구체화"). 저장은 관리자가 [적용]→[저장] 둘을 눌러야 일어난다 [↗](documents/PITFALLS.md#servicecuratedquestionsuggesterjava) |
| `service/CuratedSubmissionService.java` | 지식 제안 게시판 — 사용자가 제안(직접 작성 또는 좋아요한 답변 프리필), 저자가 수정·철회, 관리자가 임베딩 실행/거부. **검색 코퍼스로 들어가는 유일한 문**(§10.11) [↗](documents/PITFALLS.md#servicecuratedsubmissionservicejava) |
| `repository/CuratedSubmissionRepository.java` | 지식 제안 게시판 테이블·쿼리. 상태 전이는 전부 compare-and-set [↗](documents/PITFALLS.md#repositorycuratedsubmissionrepositoryjava) |
| `controller/CuratedSubmissionController.java` | 지식 제안 게시판(사용자) — `/curated/submissions` 페이지·제출·수정·철회·이미지 업로드. `?fromThread=&fromTurn=` 로 좋아요한 답변을 프리필(본문은 서버가 턴에서 읽는다) [↗](documents/PITFALLS.md#controllercuratedsubmissioncontrollerjava) |
| `service/CuratedImageStore.java` | 지식 제안 본문 이미지 — upload, marker bookkeeping, approval-time Vision description, cleanup [↗](documents/PITFALLS.md#servicecuratedimagestorejava) |
| `repository/ChunkReportRepository.java` | §10.14 청크 오류 신고 대기열(`chunk_report` — 한정자 없는 `JdbcTemplate`, 즉 `curated_submission` 과 같은 파일) + 신고 시점 청크 위치·원문 조회(`@Qualifier("vectorJdbcTemplate")` — `QuestionReuseRepository` 처럼 두 `JdbcTemplate` 을 든다). 중복 방지 키는 (청크, 신고자, **대화**) [↗](documents/PITFALLS.md#servicechunkreportservicejava) |
| `service/ChunkReportService.java` | §10.14 — 신고 접수(사유 4종 + 코멘트 필수, 문서·원문·해시·질문은 서버가 직접 스냅샷)와 **청크 단위** 관리자 조회·처리. 신고는 검색 코퍼스를 바꾸지 않는다 [↗](documents/PITFALLS.md#servicechunkreportservicejava) |
| `controller/ChunkReportController.java` | `POST /ui/chunk-reports` — 사용자 신고 접수(게스트 개방·CSRF 필요, 중복은 409 로 구분). 관리자 조회·처리는 `AdminController` 의 `/admin/chunk-reports/**` [↗](documents/PITFALLS.md#servicechunkreportservicejava) |

## Conventions

- **Records everywhere**: `AgentState`, `ThreadMeta`, `ChatResponse`, `SourceRef`, `LlmProvider` — all immutable records
- **No Spring Data JPA**: raw `JdbcTemplate` for all DB access (SQLite incompatibility)
- **HTMX fragments**: endpoints return `"fragments/xxx :: selector"` strings
- **Thymeleaf JS inlining**: any `<script>` using the `/*[[${x}]]*/ default` pattern **must** carry `th:inline="javascript"`. Without it Thymeleaf falls back to text mode, which evaluates the expression but writes it *inside the comment* and leaves the literal default in force — `const X = /*true*/ false;`. That is valid JS, so there is no error anywhere: the page silently runs on defaults forever. `chat.html` shipped without it, which pinned `SOURCE_PREVIEW_ENABLED` to its `true` fallback (the `/settings` source-preview toggle did nothing in chat), froze every `#{...}` message constant on the page at its hardcoded Korean default (EN locale unaffected by the switcher), and made `ui.retrieval-metrics-enabled` unturnable-on. Grep for `/*[[` when adding a script block
- **마크다운 렌더는 `marked` + `DOMPurify` 가 **둘 다** 있을 때만 한다**: 하나라도 없으면 렌더를 포기하고 평문으로 떨어진다(`escHtml(...)` 또는 `el.textContent`). 예전 코드는 marked 미로드만 그렇게 처리하고 `typeof DOMPurify !== 'undefined' ? DOMPurify.sanitize(html) : html` 로 **살균기가 없으면 raw HTML 을 innerHTML 에 넣었다** — 렌더 대상이 업로드 문서 본문·LLM 출력·사용자가 쓴 지식 제안(전부 신뢰 경계 밖)이고 CSP 가 `script-src 'unsafe-inline'` 이라 마지막 방어선이 DOMPurify 하나뿐이다. 렌더 지점은 4개 파일 7곳(`chat-stream.js` 2 · `chat.html` 3 · `fragments/message-assistant.html` 1 · `layout/base.html` 1) — 새 렌더러를 추가하면 이 게이트를 같이 가져갈 것
- **Spring AI auto-config excluded**: `spring.autoconfigure.exclude` drops `ChromaVectorStoreAutoConfiguration` (`ChromaConfig` manages beans manually) **and all six OpenAI model auto-configs** — `OpenAiChat`/`OpenAiEmbedding`/`OpenAiAudioSpeech`/`OpenAiAudioTranscription`/`OpenAiImage`/`OpenAiModeration`AutoConfiguration. The app builds its own chat (`LlmConfig.llmRouter`/`primaryChatModel`), embedding (`EmbeddingBeanConfig`) and per-provider `OpenAiApi` (`OpenAiApi.builder()`) beans, so it never uses a Spring AI OpenAI autoconfig bean. Left active, each of these eagerly creates an `openAiApi` bean that runs `Assert.hasText(spring.ai.openai.api-key)` and crashes startup with `OpenAI API key must be set` when `LOCAL_LLM_KEY` is blank (the normal local-only setup) — this hits the chat/embedding ones too (their *model* bean is skipped via `@ConditionalOnMissingBean`, but their `openAiApi` bean is not)
- **Null-safe config**: always use `props.llmSafe()` / `props.indexingSafe()` / `props.authSafe()` / `props.imageDescriptionSafe()` / `props.memorySafe()` / `props.summarySafe()` — never access the raw getters directly (`AppPropertiesSafeAccessorTest` enforces this for every `xxxSafe()`)
- **Korean prompts**: all LLM system/user prompts are Korean
- **MetaKey constants**: all vector store metadata access goes through `MetaKey.*` — never inline strings
- **Embedding/FTS input ≠ stored text (§10.1 Contextual Retrieval)**: `Document.getText()` (Chroma document, `vec_document_chunks.content`, source accordion, `/admin` chunk view) is always the untouched original. `SearchTextBuilder.build()` derives the embedding + `chunk_fts.content` text (`MetaKey.CHUNK_CONTEXT` + `MarkdownNoiseNormalizer.normalize()`); `AnswerService.buildAnswerPrompt()` calls `MarkdownNoiseNormalizer.normalize()` directly (no context header) for the `[검색된 문서]` block
- **Upload validation**: call `FileTypeDetector.matches(path, ext)` after writing to temp file; return 422 on mismatch
- **Rate-limit buckets** are method-aware for documents: `RateLimitFilter.policyFor()` puts only *write* requests to the two real upload endpoints in the `upload` bucket (10/min). Matching `/documents` on the path alone put the page load, the `/ui/documents/list` refresh, exports and tag edits in there too, so a normal ten-file upload spent eleven tokens and the last file came back 429. A new document endpoint that is not an upload must not land in that bucket.
- **Storage quota** (§6.15): every new path that accepts bytes from a client must call `StorageQuotaService.checkCanAccept(size, filename)` *before* it writes anything — the cap is enforced per ingress, not by a filter, so a new upload endpoint is silently exempt until it does. Directory sync (`/api/v1/documents/sync`) deliberately does **not** check: those bytes are already on disk, so nothing is being accepted. Rejection is `StorageQuotaExceededException` → 413 + `RAG-UP-002`, with no `Retry-After` (waiting frees nothing — only a deletion does)
- **Input validation**: `PromptInjectionGuard.validate()` must be the first call in any public chat entry point
- **AgentState mutation**: use `state.toBuilder().xxx().build()` — the old `state.withXxx()` methods were removed (were deprecated since 0.2.0)
- **Parallel tests**: `src/test/resources/junit-platform.properties` runs test CLASSES concurrently (methods within a class stay serial). Any test that loads a Spring context (`@WebMvcTest`/`@SpringBootTest` — Spring Boot re-inits Logback on context start + `@MockitoBean` reset isn't concurrency-safe), attaches a Logback appender (`ListAppender` log capture), or mutates `AppProperties`' static override source **must** carry `@ResourceLock("global-state")` so it doesn't run concurrently with the others. `ParallelIsolationConventionTest` fails the build if one is missing the lock

## Running Locally

```bash
# Start ChromaDB
docker-compose up chroma

# Run app (LM Studio or LLM key)
./mvn spring-boot:run
```

## Key Constraints

> 규칙만 적는다. 배경과 사고 기록은 `↗`.

- `spring.autoconfigure.exclude` must keep the Chroma exclusion and all six OpenAI model exclusions (chat/embedding/audio-speech/audio-transcription/image/moderation) [↗](documents/PITFALLS.md#springautoconfigureexclude-must-keep-the-chroma-ex)
- **태그를 쓰는 경로는 세 곳을 함께 갱신한다**: `doc_registry.tags`(목록·제안 UI 가 읽는 **출처**) · 벡터 스토어 메타데이터(`MetaKey.TAGS`, 검색 필터) · `chunk_fts.doc_tags`(키워드 축 결과에 태그를 동행시키는 사본). 쓰는 경로는 둘뿐이다 — 인덱싱(`DocumentIndexer`)과 `RagService.updateDocumentTags()`. 하나만 쓰면 화면의 태그와 검색 필터가 조용히 어긋난다. **읽기는 반대로 `doc_registry` 한 곳에서만** 한다(`KeywordSearchRepository.tagsByDocIds()` 는 백필 전용으로 남아 있다 — 새 호출자를 붙이면 코퍼스 전체 스캔이 되살아난다)
- **문서 파싱에서 이미지 한 장을 통째로 메모리에 올리는 자리는 전부 상한을 갖는다** — 크기가 문서에서 오기 때문이다. `PdfImageExtractor`(내장 이미지, `MAX_IMAGE_PIXELS` 5천만: 디코딩 **전에** `/Width`·`/Height` 로 판정)와 `DocumentLoaderService`(스캔 PDF OCR 렌더, `MAX_OCR_RENDER_PIXELS` 4천만: 페이지 크기에 맞춰 DPI 를 낮춘다 — 300 고정이면 A0 한 장이 약 560MB). 둘 다 판정이 순수 메서드로 나와 있다(`withinDecodeLimit`/`ocrRenderDpi`) — "거대한 할당이 **일어나지 않는다**"는 통합 테스트로 확인할 수 없기 때문이다(딕셔너리만 부풀린 PDF 로는 상한이 없어도 같은 결과가 나오고, 진짜 큰 이미지를 만드는 테스트는 자기가 먼저 수백 MB 를 쓴다)
- SQLite pool size must stay at 1
- **SQLite 세션 PRAGMA(`journal_mode`/`busy_timeout`/`synchronous`)는 `DataSourceConfig.sqliteUrl()` 의 JDBC URL 파라미터로만 건다.** `connection-init-sql` 로 옮기면 **조용히 무효가 된다** — statement 하나만 실행되고(sqlite-vec 백엔드에서는 그 자리를 `load_extension()` 이 이미 쓴다), 세미콜론으로 이어 붙이면 드라이버가 **첫 문장만** 실행한다. 실제로 `spring.datasource.hikari.connection-init-sql` 에 있던 `busy_timeout=5000` 은 한 번도 적용된 적이 없었다(드라이버 기본값 3000 이 걸려 있었다). 게다가 `DataSourceConfig` 가 `HikariConfig` 를 직접 만들므로 `spring.datasource.hikari.*` 자체가 바인딩되지 않는다. `synchronous=NORMAL` 은 WAL 권장값이며 이 앱이 턴 하나에 연속 여러 번 쓰기 때문에 필요하다(전원 손실 시 마지막 트랜잭션을 잃을 수 있으나 DB 는 깨지지 않는다). `DataSourceConfigTest` 가 **진짜 커넥션을 열어 되물어본다** — 설정 문자열만 읽어서는 이 함정이 드러나지 않기 때문
- All new LLM providers must go through `LlmRouter`, not direct `ChatClient` injection
- `ProviderRole`: LOCAL / NORMAL / PREMIUM (orthogonal to `TaskType`)
- `classifyOnly()` does not accumulate tokens into `AgentState` → `llmCallCount` under-reported by 1 (accepted trade-off)
- `DocumentIndexer.syncDirectory()` calls `saveRegistry()` once after all parallel work — never call it from parallel threads
- `DocumentIndexer.index()`/`reindexFromMd()` read `props.chunkOverlapSafe()` exactly once per call, into a local `usedOverlap`, reused both for `chunkSplitter.splitDocume [↗](documents/PITFALLS.md#documentindexerindex)
- `DocumentIndexer.index(IndexRequest)` is the single entry point for both bulk sync (`IndexRequest.parallel(...)`) and interactive single-file upload (`IndexRequest.singl [↗](documents/PITFALLS.md#documentindexerindex)
- Document storage is shared (no per-user isolation): `data/documents/`, `data/images/{docId}/`, `data/converted/{docId}.md`; DocRegistry and Chroma collection use `DocRegistry.SHARED` as the owner key
- PPTX/비스캔 PDF → MD: 섹션 경계는 `[페이지: N]` 마커이지 합성 헤딩이 아니다. 여러 줄 도형 텍스트는 코드 펜스 또는 블록 분리로, 도형 그룹의 맨숫자 배지 라벨은 버린다 [↗](documents/PITFALLS.md#pptx--스캔본-아닌-pdf-의-마크다운-변환)
- **코드 펜스 짝 맞춤은 파이프라인 불변식이다** — LLM 이후의 모든 패스가 펜스 줄이 1-2, 3-4… 로 짝지어짐을 가정한다. 펜스를 건드리는 패스를 새로 넣으면 이 불변식을 반드시 유지할 것. 표 본문은 LLM 에 보내지 않는다(자리표시자 치환) [↗](documents/PITFALLS.md#markdowncorrectionservice--코드-펜스-짝-불변식과-표-보호)
- 응답 모드 **S/N/C**(기본 `N`) — 메시지별 '답변의 성격' 축, 라우팅 모드와 직교. **값이 아니라 성질로 분기한다**(`skipsVerification()`/`allowsDirect()`/`allowsSubmission()`/… — `ResponseModeBranchConventionTest` 가 main 의 `== ResponseMode.X` 를 빌드 실패로 막는다). C 와 Direct 는 배타이며 RAG↔Direct 토글은 모드를 `N` 으로 되돌린다 [↗](documents/PITFALLS.md#응답-모드-s--n--c)
- Direct 턴의 이력 상한은 **문서 자리가 비어서** 넓어진다(§10.13): `이력 상한 = 입력 예산 − 문서가 차지할 자리`. 규칙·렌더 모두 `HistoryPolicy` 하나에 있고 **두 이력 경로가 같이 읽어야 한다** — 한쪽만 고치면 요약 캐시 TTL 이 지나는 순간 맥락이 달라진다. 창을 모르면 아무것도 하지 않고, 줄였으면 `budgetNote` 로 말한다. 그와 **별개로** 프롬프트에 싣는 턴 수는 `promptTurnCap()`(가져오는 창의 절반, 기본 5턴)이 양쪽 모드·양쪽 경로에 건다. 이전 턴이 **Direct 였으면 RAG 로 물어도 전문**을 싣는다(그 턴에는 복제할 문서가 없었다 — 검증은 이력을 보지 않으므로 `grounded=false` 위험을 감수한 결정) [↗](documents/PITFALLS.md#servicehistorypolicyjava)
- 검증 판정 셋: 프롬프트를 창에 맞춰 줄였으면 **사용자에게 말한다**(`budgetNote`); 줄인 근거로 나온 `grounded=false` 는 **판정으로 삼지 않는다**(`unreliableNegative()`); 판정을 **읽지 못하면 '통과'가 아니라 '판정 없음'**이다(`withoutVerdict()` — `grounded=true` 로 위조하지 않는다) [↗](documents/PITFALLS.md#검증-판정의-신뢰도--축소실패-시-판정-없음)
- 질문 버블 표기는 **두 글자**(`[RS]`) — 앞이 검색 축(`R` RAG / `D` Direct), 뒤가 성격(`S`/`N`/`C`). 렌더러가 넷이고 규칙의 출처는 둘(서버 `Turn.responseModeLabel()`, 클라이언트 `base.html` 의 `bubbleModeLabel()`). **`data-question` 에는 절대 섞지 않는다** [↗](documents/PITFALLS.md#질문-버블의-두-글자-표기)
- Chat search-scope tags have **no text input** — the user toggles the tag chips under the input bar, which write a comma-joined value into the `#chat-tags-input` **hidden** field (still `name="tags"`, so both the HTMX post and the SSE `FormData(form)` pick it up unchanged)
- `thread_meta.tags` (migration `V3__thread_tags.sql`) snapshots the tag selection of the **most recently sent message** in a thread [↗](documents/PITFALLS.md#thread_metatags)
- Rate limiting: `RateLimitFilter` uses Bucket4j + Caffeine per-user token-bucket; `app.rate-limit.enabled` (default `true`)
- Audit logging: `AuditLogger` writes to Logback AUDIT_FILE appender; `app.audit.enabled` (default `true`)
- 검색 튜닝 프로퍼티는 전부 `app.search-*` 이고 `props.searchXxxSafe()` 로만 읽는다. `rerank-enabled` 만 핫이 아니다(구조적 빈) [↗](documents/PITFALLS.md#검색-튜닝-프로퍼티는-전부-appsearch--이고-propssearchxxxsafe)
- §10.12 독립화된 질문은 **검색 축 셋 + 리랭커 + 분류기**가 쓰고(`AgentState.effectiveSearchQuestion()`), 답변 프롬프트의 `[현재 질문]` 은 **언제나 원문**(`state.question()`)이다 — 재작성이 빗나가도 검색만 틀리게 하는 격리다. 확장 게이트는 **원문 길이**로 재야 두 게이트가 여집합으로 남는다 [↗](documents/PITFALLS.md#servicequestioncondenserjava)
- 큐레이션 Q&A 편집에는 `excerpt_keywords`/`chunk_context` 필드를 두지 않는다 — 이 축에서는 **읽히는 코드 경로가 없다**(FTS 미색인 + `SEARCH_TEXT` 오버라이드). 그 역할은 **질문**이 한다(검색 텍스트 = 질문 + 본문, 질문은 모든 청크에 반복 부여) [↗](documents/PITFALLS.md#servicecuratedquestionsuggesterjava)
- `RerankerService` is a `@ConditionalOnProperty` bean injected as `Optional<RerankerService>`; when `rerank-enabled=false` no bean exists and `RetrievalService` still works — never assume the Optional is present
- `PromptInjectionGuard.wrap()` delimits the raw user question at every prompt-construction site (`AnswerService.buildAnswerPrompt()`/`evaluate()`, `ClassifierService`, `D [↗](documents/PITFALLS.md#promptinjectionguardwrap)
- `app.auth.enabled=false` → CSRF disabled, `SessionCreationPolicy.STATELESS`, `NoAuthAutoLoginFilter` active [↗](documents/PITFALLS.md#appauthenabledfalse--csrf-disabled-sessioncreat)
- `AppProperties.AuthConfig` carries **two constructors** (canonical 3-arg + a 2-arg test convenience), so the canonical one **must** keep `@ConstructorBinding` [↗](documents/PITFALLS.md#apppropertiesauthconfig-carries-two-constructors)
- `app.auth.guest-identity` unknown/blank → normalized to `shared` in `authSafe()` (a typo degrades to the pre-existing single-guest behavior, never to a half-applied spli [↗](documents/PITFALLS.md#appauthguest-identity-unknownblank--normalized-t)
- `app.auth.management-only=true`(no-auth 모드에서만 유효) — 채팅·열람은 게스트 개방, `/admin/**` 과 문서 쓰기 라우트는 실제 로그인 요구. 게이트는 항상 `.hasRole("ADMIN")` 이지 `.authenticated()` 가 아니다 [↗](documents/PITFALLS.md#appauthmanagement-onlytrue)
- `GlobalModelAdvice.authEnabled()` is computed per-request (not in constructor) to avoid NPE when `AppProperties` is mocked in `@WebMvcTest`
- 벡터 백엔드는 `app.vectorstore.type=chroma|sqlite-vec`. Chroma 전용 빈은 전부 `@ConditionalOnProperty`. **vec/FTS 테이블을 만지는 컴포넌트는 `@Qualifier("vectorJdbcTemplate")` 를 주입해야 한다**. 백엔드 전환은 전체 재인덱싱이 필요하다 [↗](documents/PITFALLS.md#벡터-스토어-백엔드와-vecfts-datasource)
- **`SQLITE_VEC_DB_PATH` 를 켜면 한정자 없는 `JdbcTemplate` 도 그 벡터 파일을 가리킨다** — 앱이 `vectorJdbcTemplate` 빈을 정의해 Spring Boot 의 `JdbcTemplate` 자동설정이 물러나므로 컨텍스트에 템플릿이 하나뿐이다. 그래서 대화·계정·설정·레지스트리까지 벡터 DB 파일에 쌓이고 `memory.db` 는 빈 껍데기가 되며, **Flyway 마이그레이션은 실데이터에 닿지 않는다**(신규 컬럼은 런타임 `ALTER` 패턴으로만 추가할 것) [↗](documents/PITFALLS.md#벡터-스토어-백엔드와-vecfts-datasource)
- 지식 제안: `curated_qa.source_turn_id` 가 nullable 이 되면서 `idx_curated_qa_turn` 이 **부분 UNIQUE** 인덱스가 됐다. 따라서 **`deactivate(turnId)` 는 manual 행에 조용히 no-op** 이다 — 새 비활성화 경로는 `deactivateById()` 를 써야 한다 [↗](documents/PITFALLS.md#지식-제안-등록-당시-이름은-청크-추가)
- **검색 코퍼스로 들어가는 문은 하나다** (§10.11): 지식 제안의 관리자 승인. 좋아요는 그 폼을 열어 줄 뿐 아무것도 만들지 않는다 — `curated_qa` 에 쓰는 경로를 새로 만들면 그 불변식이 깨진다. 저장 모양은 출처마다 다르다: 손으로 쓴 제안은 승인 시 **미리 나뉜 N개 행**, 좋아요 출신은 **turn 을 키로 하는 행 하나 → 임베딩 시점에 벡터 N개**(`UNIQUE(source_turn_id)`·대화/턴 삭제 회수·재승인이 전부 그 키를 탄다) [↗](documents/PITFALLS.md#servicecuratedqaservicejava)
- 승인 시 `source_submission_id` 를 반드시 실어야 한다 — 제안의 상태(청크 수·등록 완료/회수됨·임베딩 실패)가 **전부 그 컬럼으로만** 세어지므로, 빠지면 오류도 로그도 없이 제안이 현실과 끊긴다(청크 0개인 '등록 완료'로 뜨고, 관리자가 실제로 내려도 계속 그렇게 뜬다) [↗](documents/PITFALLS.md#servicecuratedqaservicejava)
- **대화(스레드) 삭제도 큐레이션을 회수해야 한다** (§6.25): `curated_qa` 행은 turn/thread id의 **복사본**으로만 연결돼 있어(FK가 아니다 — 그래서 이 테이블이 대화 삭제를 견디도록 설계됐다) 대화를 지워도 행과 벡터가 남아 검색에 계속 기여한다 [↗](documents/PITFALLS.md#대화)
- Header badges (지식 제안 알림) poll every **60 s** from `layout/base.html`, not 3 s like the LLM indicator [↗](documents/PITFALLS.md#header-badges)
- 정적 자산(`/css/**`, `/js/**`)은 **내용 해시 URL**로 나간다 (`spring.web.resources.chain.strategy.content.*`) [↗](documents/PITFALLS.md#정적-자산)
- `WebConfig`'s `requestURI` interceptor skips `redirect:` views: `RedirectView` appends simple model attributes to the target URL, so without the guard every `return "redirect:/x"` became `/x?requestURI=%2Fold%2Fpath`. Only the rendered layout reads that attribute
- § 청크 변경 표시/차단: 청크를 삭제·수정하는 **모든** 경로가 `QuestionReuseService`에 사유와 함께 통지해야 한다 [↗](documents/PITFALLS.md#-청크-변경-표시차단)
- 청크 오류 신고(§10.14)는 **대기열일 뿐이다** — 검색·재사용·벡터/FTS 어디에도 영향이 없고 반영은 관리자가 청크를 실제로 고칠 때 일어난다(자동 조치를 넣지 말 것). 중복 방지 키는 (청크, 신고자, **대화**)이고, 대기열·배지의 단위는 신고 건수가 아니라 **열린 신고를 가진 청크 수**다. 채팅의 "현재 대화에서 이 청크 제거"(청크는 맞지만 이 답변과 무관 · 표시 전용)와 성격이 다르며, 그쪽의 `activeChunkContext`(참여도 0%일 때만 채워진다)를 신고가 재사용해서는 안 된다 [↗](documents/PITFALLS.md#servicechunkreportservicejava)
- 큐레이션 태그 스코프: `filterByTags()`는 벡터·키워드·큐레이션이 합쳐진 **병합 후보 풀 전체**에 걸리는데 `CuratedQaService.buildDocument()`가 `MetaKey.TAGS`를 안 실어서, 태그 칩을 하나라도 켜면 좋아요 답변·승인된 제안이 **전부 탈락**했다( [↗](documents/PITFALLS.md#큐레이션-태그-스코프)
- 지식 제안 본문 이미지: `CuratedQaService.buildDocument()` sets `MetaKey.IMAGE_PATHS` from the markers **still present in that chunk's stored text**, computed after the split [↗](documents/PITFALLS.md#지식-제안-본문-이미지)
- `RetrievalService.hasEmbeddedDescription()` must accept **both** injection forms [↗](documents/PITFALLS.md#retrievalservicehasembeddeddescription)
- `MetaKey.CHUNK_CONTEXT` 는 일반 영속 메타데이터 키다(왕복함). 임시라서 저장 전 제거해야 하는 것은 `MetaKey.SEARCH_TEXT` 하나뿐 [↗](documents/PITFALLS.md#metakeychunk_context-는-일반-영속-메타데이터-키다)
- `AdminService.reindexChunk()` re-embeds by constructing the `Document` with the SAME id as the existing chunk (`chunkId`, not a fresh one) before calling `VectorStoreFac [↗](documents/PITFALLS.md#adminservicereindexchunk)
- `ChromaVectorStoreProvider.add()` does **not** delegate to Spring AI's `VectorStore.add()` (that API embeds and stores the exact same string, which breaks the §10.1 embe [↗](documents/PITFALLS.md#chromavectorstoreprovideradd)
- `chunk_fts` 의 `trigram` 토크나이저는 3글자 이상이어야 토큰이 나온다 — 2글자 질의(한국어에 흔함)는 BM25 축 기여가 0이다. 버그가 아니다 [↗](documents/PITFALLS.md#chunk_fts-의-trigram-토크나이저는-3글자-이상이어야-토큰이-나온다)
- §6.12: 동시성 게이트(`executeGated()`)는 **대화형 경로에만** 건다(인덱싱은 자체 세마포어). 임베딩은 텍스트 단위 in-flight 단일 비행. **차단하면 안 되는 실패가 넷 있다** — 클라이언트 타임아웃 · mmproj 미지원 · 컨텍스트 초과 · 서버가 요청을 끊음. 폴백이 없는 프로바이더는 짧게만 차단한다 [↗](documents/PITFALLS.md#612-동시성-게이트--단일-비행--과부하-차단--부하-분산)
- §6.13 핫 편집 계층: `/settings` 의 값은 **search**(다음 검색) · **indexing**(다음 인덱싱) · **LLM**(다음 호출) · **UI**(다음 렌더) 네 그룹. 핫 소비자는 **매 호출 `props.xxxSafe()` 를 다시 읽어야 한다**(필드에 캐시 금지). 새 키 추가 = `SettingsKeys` + 해당 `xxx_HOT_SPECS` + `xxxSafe()` 의 오버라이드 분기, 셋 다 [↗](documents/PITFALLS.md#613-설정-오버라이드-계층-핫-편집)
