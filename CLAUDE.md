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
- `AgentService.chat()` pre-runs history load + classify in parallel; AgentGraph skips CLASSIFIER when `questionType != null`

## Key Files

| Path | Role |
|------|------|
| `agent/AgentGraph.java` | State machine; skips CLASSIFIER when `questionType != null` |
| `agent/AgentState.java` | Immutable state record (`routingMode`, `dualLocalAnswer`, `premiumUpgraded`, `usedProvider`) |
| `controller/WebController.java` | HTMX fragment endpoints + page routes |
| `llm/CircuitBreaker.java` | In-memory per-provider block (Retry-After aware) |
| `llm/LlmRouter.java` | Provider selection by TaskType × RoutingMode; `route()`, `executeDual()`, `executeWithTracking()` |
| `repository/LlmUsageRepository.java` | Daily UPSERT token tracking in SQLite |
| `config/AppProperties.java` | `@ConfigurationProperties(prefix="app")`, `llmSafe()`, `indexingSafe()` null guards |
| `service/AnswerService.java` | 2-call pattern: answer + sufficiency; PROGRESSIVE upgrade; DUAL branch |
| `service/AgentService.java` | Entry point; parallel history + classify before graph via virtual threads |
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

## Running Locally

```bash
# Start ChromaDB
docker-compose up chroma

# Run app (LM Studio or OpenAI key)
OPENAI_BASE_URL=http://localhost:1234/v1 OPENAI_API_KEY=lm-studio ./mvnw spring-boot:run
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
