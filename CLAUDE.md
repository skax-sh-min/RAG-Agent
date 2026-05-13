# RAG Agent — Project Guide

## Stack

- **Backend**: Spring Boot 3 + Spring AI, Java 21 (virtual threads on), SQLite (WAL, pool=1)
- **Frontend**: Thymeleaf + HTMX, Bootstrap 5, no JS framework
- **Vector DB**: ChromaDB (per-version collections via `VectorStoreRegistry`)
- **LLM**: OpenAI-compatible endpoint (Spring AI `ChatClient`); local LLM-Studio or remote

## Architecture

```
AgentGraph (state machine) → nodes: CLASSIFIER → RETRIEVAL → ANSWER → CRITIC → FINALIZE
AgentState: immutable record, each node returns new instance via withXxx()
```

Flow:
- `meta` question → DIRECT_ANSWER (skip retrieval)
- others → RETRIEVAL → ANSWER (sufficiency check) → CRITIC → FINALIZE, retry loop up to `maxRetryCount`
- DUAL mode → ANSWER runs LOCAL + external in parallel → FINALIZE (CRITIC bypassed)
- `directMode=true` → AgentGraph skips CLASSIFIER/RETRIEVAL/CRITIC entirely
- `AgentService.chat()` pre-runs history load + classify in parallel; AgentGraph skips CLASSIFIER when `questionType != null`
- SSE streaming → `StreamingAgentService.run()` drives `AgentGraph.runStreaming()` with `SseGraphListener`

## Key Files

| Path | Role |
|------|------|
| `agent/AgentGraph.java` | State machine; skips CLASSIFIER when `questionType != null`; `run()` (blocking) + `runStreaming()` |
| `agent/AgentState.java` | Immutable state record (`routingMode`, `dualLocalAnswer`, `premiumUpgraded`, `usedProvider`, `grounded`, `directMode`) |
| `controller/WebController.java` | HTMX fragment endpoints + page routes |
| `controller/ApiController.java` | REST API; magic-byte upload validation; `getImage()` with Cache-Control + X-Robots-Tag |
| `controller/GlobalExceptionHandler.java` | `@RestControllerAdvice`; RFC 9457 ProblemDetail; handles `IllegalArgumentException` → 400, `MaxUploadSizeExceededException` → 413 |
| `llm/CircuitBreaker.java` | In-memory per-provider block (Retry-After aware) |
| `llm/LlmRouter.java` | Provider selection by TaskType × RoutingMode; `route()`, `executeDual()`, `executeWithTracking()` |
| `repository/LlmUsageRepository.java` | Daily UPSERT token tracking in SQLite |
| `config/AppProperties.java` | `@ConfigurationProperties(prefix="app")`, `llmSafe()`, `indexingSafe()` null guards |
| `model/MetaKey.java` | Vector store metadata key constants — always use these, never raw strings |
| `security/FileTypeDetector.java` | Magic-byte validation for uploads (PDF, DOCX/PPTX, TXT/MD) |
| `security/PromptInjectionGuard.java` | `validate()` length/blank check (MAX=2000); `wrap()` for delimiter isolation; `maskApiKey()` for safe logging |
| `service/AnswerService.java` | 2-call pattern: answer + sufficiency; PROGRESSIVE upgrade; DUAL branch; `truncate()` caps at 20,000 chars |
| `service/AgentService.java` | Entry point; `PromptInjectionGuard.validate()` at entry; parallel history + classify before graph |
| `service/StreamingAgentService.java` | SSE pipeline; Virtual Thread worker; heartbeat every 15 s; partial answer persisted on error (B-13) |
| `service/ClassifierService.java` | `classifyOnly(String)` (no token accumulation) + `execute(AgentState)` |
| `service/RetrievalService.java` | Parallel MultiQuery search (`CompletableFuture` + virtual thread executor) |
| `service/RagService.java` | 3-phase `syncDirectory()`: detect → parallel index → delete; `enrichParallel()` with Semaphore |
| `service/VisionDescriptionService.java` | Image bytes → Korean description via `LlmRouter.route(VISION, COST_FIRST)` |

## Conventions

- **Records everywhere**: `AgentState`, `ThreadMeta`, `ChatResponse`, `SourceRef`, `LlmProvider` — all immutable records
- **No Spring Data JPA**: raw `JdbcTemplate` for all DB access (SQLite incompatibility)
- **HTMX fragments**: endpoints return `"fragments/xxx :: selector"` strings
- **ChromaDB auto-config excluded**: `spring.autoconfigure.exclude=...ChromaVectorStoreAutoConfiguration` — `ChromaConfig` manages beans manually
- **Null-safe config**: always use `props.llmSafe()` / `props.indexingSafe()` — never access `props.llm()` or `props.indexing()` directly
- **Korean prompts**: all LLM system/user prompts are Korean
- **MetaKey constants**: all vector store metadata access goes through `MetaKey.*` — never inline strings
- **Upload validation**: call `FileTypeDetector.matches(path, ext)` after writing to temp file; return 422 on mismatch
- **Input validation**: `PromptInjectionGuard.validate()` must be the first call in any public chat entry point

## Running Locally

```bash
# Start ChromaDB
docker-compose up chroma

# Run app (LM Studio or LLM key)
./mvnw spring-boot:run
```

## Key Constraints

- `spring.autoconfigure.exclude` for Chroma must stay — do not remove
- SQLite pool size must stay at 1
- All new LLM providers must go through `LlmRouter`, not direct `ChatClient` injection
- DUAL mode requires LOCAL provider registered; throw `LlmProviderExhaustedException` otherwise
- `ProviderRole`: LOCAL / NORMAL / PREMIUM (orthogonal to `TaskType`)
- `classifyOnly()` does not accumulate tokens into `AgentState` → `llmCallCount` under-reported by 1 (accepted trade-off)
- `syncDirectory()` calls `saveRegistry()` once after all parallel work — never call it from parallel threads
- `indexDocumentParallel()` is for bulk sync only; `indexDocument()` is for single-file upload and calls `saveRegistry()` itself
- `PromptInjectionGuard.wrap()` is implemented but not yet wired into prompts — deferred to 05-prompt-externalization.md
