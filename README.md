# RAG Agent — Spring AI / Java 21

A document-based knowledge Q&A agent built on Spring AI + Spring Boot 3.5 + Java 21.  
Provides both a REST API and a Web UI powered by Thymeleaf + HTMX.

## Getting Started

### Docker Compose (recommended)

```bash
cp .env.example .env   # configure environment variables
docker-compose up --build
```

### Local Build

```bash
# Install git hooks (run once after cloning)
sh scripts/install-hooks.sh

# Build with tests
mvn clean package

# Build without tests (faster)
mvn clean package -DskipTests

# Exploded build — extract layers without bundling into a fat JAR
mvn clean package -DskipTests
java -Djarmode=tools -jar target/rag-agent-*.jar extract --destination target/extracted
```

The built JAR is generated at `target/rag-agent-*.jar`.

> **Exploded (layered) run** — after the `extract` step above, run the application directly from the unpacked layout. Avoids fat-JAR overhead; JVM loads classes and dependencies without unpacking at runtime:
> ```bash
> java -jar target/extracted/rag-agent-*.jar
> ```
> Use `--destination target/extracted` only once; subsequent runs can go straight to the `java -jar` line.

### Local Run

> **Vector store backend** — defaults to ChromaDB. Set `VECTORSTORE_TYPE=sqlite-vec` to store vectors in the SQLite file instead and **skip the "Start Chroma" step** below (requires an operator-provided `vec0` native extension — see [OPERATOR_MANUAL.md](documents/OPERATOR_MANUAL.md)). For a fully offline, no-Docker setup (sqlite-vec + local llama-server), see [OPERATOR_MANUAL.md §4.5](documents/OPERATOR_MANUAL.md#45-폐쇄망air-gapped--노-도커-실행).

> **Chroma version — v2 API required.** Spring AI 1.1.8's `ChromaApi` calls only `/api/v2/tenants/{tenant}/databases/{database}/…`, and tenants/databases don't exist in Chroma's v1 API, so a v1-era server (0.5.x and earlier) is **not** compatible. The commands below and `docker-compose.yml` pin `chromadb/chroma:1.0.21` rather than `:latest`, since Chroma has changed its HTTP API across major versions before. Bump the pin deliberately, not implicitly.

#### Development mode (run from source)

```bash
# 1. Start Chroma (separate terminal)
docker run --rm -p 8001:8000 \
  -v "$(pwd)/data/chroma:/data" \
  chromadb/chroma:1.0.21

# 2. Configure environment variables
cp .env.example .env

# 3. Run the application
mvn spring-boot:run
```

#### JAR execution (after build)

```bash
# 1. Start Chroma (separate terminal)
docker run --rm -p 8001:8000 \
  -v "$(pwd)/data/chroma:/data" \
  chromadb/chroma:1.0.21

# 2. Load env vars and run JAR
export $(grep -v '^#' .env | xargs)
java -jar target/rag-agent-*.jar
```

#### macOS — Apple Container (Apple Silicon alternative)

```bash
# 0. Install (one-time): download .pkg from https://github.com/apple/container/releases

# 1. Start the container system (once after install or reboot)
container system start

# 2. Start Chroma (separate terminal)
container run --rm -p 8001:8000 \
  -v "$(pwd)/data/chroma:/data" \
  chromadb/chroma:1.0.21

# 3. Load env vars and run
export $(grep -v '^#' .env | xargs)
mvn spring-boot:run

# Shutdown
container stop <CONTAINER_ID>
container system stop
```

Open: http://localhost:8080

See [USER_MANUAL.md](documents/USER_MANUAL.md) for usage instructions and [OPERATOR_MANUAL.md](documents/OPERATOR_MANUAL.md) for deployment and LLM configuration.

## Environment Variables

### Connection / Authentication

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `SERVER_PORT` | — | `8080` | Port the application listens on. Change only on conflict with another local service |
| `LOCAL_LLM_URL` | to use this provider ✅ | none | `providers[1]` (`local`) endpoint (also used as embedding fallback, which is not gated by G3 below). **If unset or blank, this provider is disabled entirely** — no longer silently falls back to `http://localhost:1234/v1`. If set, startup calls `GET {URL}/models` to confirm it's reachable and the configured model is in the response — **the app refuses to start** if that check fails (G3, see [OPERATOR_MANUAL.md §5.2](documents/OPERATOR_MANUAL.md#52-프로바이더-속성)) |
| `LOCAL_LLM_KEY` | — | `no-key` | `providers[1]` API key. **Optional for local endpoints** (llama-server needs none) — as long as `LOCAL_LLM_URL` is set, the provider is kept even when the key is blank (`no-key` is substituted) |
| `LOCAL_LLM_MODEL` | — | `google/gemma-4-e4b` | `providers[1]` model name |
| `LOCAL_LLM_TYPE` | — | `BOTH` | `providers[1]` task type (`app.llm.providers[1].type`): `MICRO_TEXT`/`LIGHT_TEXT`/`TEXT`/`VISION`/`LIGHT_BOTH`/`BOTH`. `BOTH` handles everything; set e.g. `TEXT` to limit the local model to chat answers |
| `LOCAL_LLM_URL_2` | to use this provider ✅ | none | `providers[2]` (`local-2`) endpoint — a second local LLM instance registered with the same role/priority as `providers[1]` (`local`), so requests are load-balanced least-in-flight across the two (see [OPERATOR_MANUAL.md §5.4 Example 5/7](documents/OPERATOR_MANUAL.md)). **If unset or blank, this provider is disabled entirely** (zero regression — `local` alone handles everything, same as before this tier existed). **If set, startup verifies it via `GET {URL}/models` (G3) and the app refuses to start if that fails** — "set but nothing's listening yet" no longer degrades gracefully to a runtime fallback unless `LLM_VERIFY_LOCAL_MODELS_ON_STARTUP=false` |
| `LOCAL_LLM_KEY_2` | — | `no-key` | `providers[2]` API key (local endpoints ignore it; `no-key` substituted when blank — no longer inherits `LOCAL_LLM_KEY`) |
| `LOCAL_LLM_MODEL_2` | — | falls back to `LOCAL_LLM_MODEL` | `providers[2]` model name — usually the same model as `providers[1]`, replicated on a second server |
| `LOCAL_LLM_TYPE_2` | — | `BOTH` | `providers[2]` task type (`app.llm.providers[2].type`). Same value set as `LOCAL_LLM_TYPE`; usually `BOTH` |
| `LOCAL_FAST_LLM_URL` | to use this provider ✅ | none | §6.21 task-tier offload — `providers[0]` (`local-fast`) endpoint. **If unset or blank, this provider is disabled entirely** — `MICRO_TEXT` chores are then absorbed by `local`, except the conversation summary, which is skipped instead (chat falls back to raw history). **If set, startup verifies it via `GET {URL}/models` (G3) and the app refuses to start if that fails** — see [OPERATOR_MANUAL.md §5.4 Example 6](documents/OPERATOR_MANUAL.md) |
| `LOCAL_FAST_LLM_KEY` | — | — | `providers[0]` API key. Optional for local endpoints, same as `LOCAL_LLM_KEY` |
| `LOCAL_FAST_LLM_MODEL` | — | `Qwen3.5-0.8B-Q4_K_M.gguf` | `providers[0]` model name |
| `LLM_VERIFY_LOCAL_MODELS_ON_STARTUP` | — | `true` | (`app.llm.verify-local-models-on-startup`) — the G3 toggle. When `true`, every registered LOCAL-role provider (any of the three above with a URL set) is checked at startup via `GET {url}/models`; a mismatch or connection failure aborts startup entirely. Set `false` only if your local server reliably starts *after* this app in your deployment order — with it off, an unreachable/mismatched local provider degrades to the old runtime fallback (one failed call, then routed elsewhere) instead of blocking startup |
| `LLM_ROUTING_MODE` | — | `COST_FIRST` | Default routing mode (`app.llm.default-routing-mode`). Air-gapped / local-only: set `LOCAL_ONLY` to block all external provider calls — this also hides the routing-strategy dropdown in the chat sidebar entirely, since every mode would resolve to the same provider |
| `LLM_DEFAULT_PROVIDER_CONCURRENCY` | — | `3` | Query-path per-provider concurrency gate (`app.llm.default-provider-concurrency`) — the app never sends more concurrent requests to one provider than this (match the LLM server's real `--parallel` value). Per-provider override: `app.llm.providers[N].concurrency` |
| `LLM_PERMIT_WAIT_TIMEOUT_SECONDS` | — | `60` | Max wait for a concurrency slot before failing fast with HTTP 429 + `Retry-After` (`app.llm.permit-wait-timeout-seconds`) instead of hanging until the read timeout. Indexing/background LLM calls are not subject to this cap |
| `LLM_TEMPERATURE` | — | `0.0` | General/RAG answer temperature (`app.llm.temperature`), baked into each provider's `OpenAiChatOptions` at bean creation — **view-only in `/settings`**, restart to change |
| `LLM_MAX_TOKENS` | — | `6000` | Completion-length cap for **blocking** LLM calls only (classification, keyword extraction, MD correction, sufficiency/critic evaluation, TXT structuring, etc.) — streaming chat/Direct answers are uncapped by design (bounded by SSE timeouts instead). Also sizes the conversation-history budget and MD-correction section-splitting budget (same value, shared across all three). **Not the model's context window** — size it with headroom under your LLM server's actual context size; see [PIPELINE.md §4.1](documents/PIPELINE.md#41-appllmmax-tokensllm_max_tokens-크기-산정--로컬-llm-컨텍스트-윈도우와의-관계) |
| `DIRECT_LLM_TEMPERATURE` | — | `0.1` | Temperature for meta/Direct answers only (`app.llm.direct-temperature`), separate from `LLM_TEMPERATURE`, clamped to `[0.0, 1.0]`. **Hot-editable via `/settings`** — applies to the next Direct call without a restart |
| `LLM_INDEXING_TEMPERATURE` | — | `0.0` | Temperature for every ungated background/indexing call (`app.llm.indexing-temperature`) — keyword extraction, MD correction, TXT structuring, vision image description/classification, thread-title generation, conversation summarization. Separate from `LLM_TEMPERATURE`/`DIRECT_LLM_TEMPERATURE` so these extraction-style calls stay deterministic regardless of what those are set to. Clamped to `[0.0, 1.0]`. **Hot-editable via `/settings`** — applies to the next call without a restart |
| `OPENAI_API_KEY` | — | — | Required for OpenAI providers. Providers auto-disabled at startup if unset |
| `GEMINI_API_KEY1` / `GEMINI_API_KEY2` | — | — | Required for Gemini providers (one key per NORMAL/PREMIUM pair — see [OPERATOR_MANUAL.md §5](documents/OPERATOR_MANUAL.md#5-llm-프로바이더-설정)). Providers auto-disabled at startup if unset |
| `GEMINI_MODEL` | — | per-provider | Overrides the model for both Gemini NORMAL-tier providers (`providers[3]` gemini-flash-lite, `providers[4]` gemini-flash). ⚠ Both read this one var, so setting it collapses them to the same model — leave unset to keep their distinct defaults |
| `EMBED_BASE_URL` | — | `LOCAL_LLM_URL` | Embedding endpoint. Falls back to `LOCAL_LLM_URL` if unset |
| `EMBED_API_KEY` | — | `LOCAL_LLM_KEY` | Embedding API key. Falls back to `LOCAL_LLM_KEY` if unset |
| `EMBED_MODEL` | — | `text-embedding-nomic-embed-text-v1.5` | Embedding model name |
| `EMBED_DIMENSIONS` | sqlite-vec only | — | Embedding model's real output dimension (`app.embedding.dimensions`). Required for `sqlite-vec` (baked into the `vec0` DDL — must match the model: nomic=768, bge-m3=1024). Ignored by chroma |
| `EMBED_USAGE_FALLBACK_ENABLED` | — | `true` | When the embedding server doesn't report token usage, approximate input tokens as chars/4 for the `/llm-usage` dashboard instead of recording 0 |
| `EMBED_MAX_CHUNK_CHARS` | — | `0` (off) | Hard per-chunk character ceiling to fit the embedding server's batch/token limit. Set (e.g. `450`) when you hit `input (N tokens) is too large ... (batch size: 512)`; oversized chunks are force-split at line boundaries. Prefer raising the server batch (`llama-server -b/-ub`) first — see [OPERATOR_MANUAL §8](documents/OPERATOR_MANUAL.md#8-문제-해결) |
| `EMBED_ADDITIONAL_BASE_URLS` | — | — | §6.21 E1 — extra embedding endpoints (same model + dimension, e.g. N GPU replicas), comma-separated. When set, embed calls are load-balanced least-in-flight across `EMBED_BASE_URL` + these — see [OPERATOR_MANUAL §3.2](documents/OPERATOR_MANUAL.md) |
| `EMBED_MAX_CONCURRENT_BATCHES` | — | `1` | §6.21 E2 — parallel sub-batch embeds within one document's indexing (`1` = serial, default → zero regression). Set to ~(endpoints × per-endpoint parallel) to fill the E1 endpoints from a single large file |
| `VECTORSTORE_TYPE` | — | `chroma` | Vector store backend — `chroma` or `sqlite-vec` |
| `SQLITE_VEC_EXTENSION_PATH` | — | — | sqlite-vec only — path to the operator-provided `vec0` loadable extension |
| `CHROMA_HOST` | — | `http://localhost` | Chroma server host (chroma backend) |
| `CHROMA_PORT` | — | `8001` | Chroma server port (chroma backend) |
| `DATA_DIR` | — | `./data` | Storage path for documents, registry, and SQLite DB |

### Image Processing / Rate Limiting / Audit

| Variable | Default | Description |
|----------|---------|-------------|
| `IMAGE_DESCRIPTION_ENABLED` | `true` | `LazyVisionService` on/off (`app.image-description.enabled`). A `@ConditionalOnProperty` bean gate — **restart required**; `false` stores image markers without ever calling Vision at query time. Also gates the approval-time description of 지식 제안 body images — with it off, those images still display but contribute nothing to search |
| `IMAGE_OCR_ENABLED` | `true` | Tesseract OCR for scanned PDF pages (`OcrService`, same structural bean gate) |
| `IMAGE_OCR_TESSDATA_PATH` | (blank) | Absolute path to the Tesseract `tessdata` directory. Blank → falls back to `TESSDATA_PREFIX` or the system default path |
| `IMAGE_CLASSIFY_TYPE` | `true` | Classify image type (diagram/screenshot/chart/photo) before describing, to pick a type-specific Vision prompt |
| `DOCX_EMF_CONVERT` | `true` | Rasterize DOCX EMF vector images to PNG via Batik (no extra install) |
| `DOCX_WMF_CONVERT` | `false` | Rasterize DOCX WMF images via LibreOffice headless (needs `soffice` on PATH — hence off by default). When off, the image is kept as a `[이미지(변환불가): …]` marker |
| `RATE_LIMIT_ENABLED` | `true` | Master switch for the per-user token bucket (`app.rate-limit.*`) |
| `RATE_LIMIT_CHAT_PER_MINUTE` | `60` | `/chat` requests per minute per user |
| `RATE_LIMIT_UPLOAD_PER_MINUTE` | `10` | Document upload requests per minute |
| `RATE_LIMIT_SYNC_PER_MINUTE` | `3` | Folder-sync requests per minute |
| `RATE_LIMIT_IMAGE_PER_MINUTE` | `300` | `/images/` requests per minute |
| `RATE_LIMIT_DEFAULT_PER_MINUTE` | `120` | Default for every other path |
| `AUDIT_ENABLED` | `true` | Write audit events to `data/audit/audit.log` (`app.audit.*`) |
| `AUDIT_MAX_FILE_SIZE` | `10MB` | Rolling size threshold — also the Logback `AUDIT_FILE` appender's rollover trigger |
| `AUDIT_MAX_HISTORY_DAYS` | `7` | Retention for compressed audit files |
| `AUDIT_TOTAL_SIZE_CAP` | `100MB` | Total size cap for `data/audit/` |

> `app.image-description.mode` and `app.image-description.min-image-bytes` still bind but **nothing reads them** — the strip/describe decision now lives in the upload-time "이미지 설명 추가" checkbox plus `LazyVisionService`'s query-time cache. They have no env var on purpose.

### RAG Tuning

| Variable | Default | Recommended Range | Description |
|----------|---------|-------------------|-------------|
| `CHUNK_SIZE` | `1500` | 300 ~ 2000 | Document chunk size (characters) |
| `CHUNK_OVERLAP` | `0` | 0 ~ CHUNK_SIZE × 0.25 | Overlap between chunks (characters, boundary context only). Defaults to `0` — section-aware splitting already carries heading/breadcrumb context into each chunk, and `0` keeps document export exact (see the Document export feature below) |
| `MIN_CHUNK_SIZE` | `500` | 50 ~ CHUNK_SIZE × 0.25 | Minimum chunk size threshold for tiny-chunk merge (ignored entirely when `CHUNK_SPLIT_GRANULAR=true`) |
| `CHUNK_SPLIT_GRANULAR` | `false` | true/false | Chunking strategy. `false` = size-driven merge (bundles short chapters up toward `CHUNK_SIZE`). `true` = **split at every heading**, ignoring `MIN_CHUNK_SIZE`, with one exception: a heading plus ≤2 content units (a lead-in) folds into the deeper chapters below it. Also keeps tables/code fences whole by moving the boundary up to ±50% of `CHUNK_SIZE` (the default path can only move it by `CHUNK_OVERLAP`, which is now 0), and stops merging PPTX/PDF slides across slide boundaries (1 slide = 1 chunk — sections *within* one slide are still joined, so a title-only slide heading never becomes its own chunk). Hot-editable, but **existing documents keep their chunks until re-indexed** — flip it and hit ↺ on one document to compare the two side by side. See [OPERATOR_MANUAL.md §6.10](documents/OPERATOR_MANUAL.md#610-청크-분할-전략-크기-기준-병합--소제목-최대-분할) |
| `SEARCH_TOP_K` | `8` | 2 ~ 15 | Number of documents returned by vector search |
| `SEARCH_SIMILARITY_THRESHOLD` | `0.0` | 0.0 ~ 0.75 | Min cosine similarity to keep a chunk (`0.0` = accept all) |
| `SEARCH_MULTIQUERY_ENABLED` | `true` | true/false | Expand the query into sub-queries before search |
| `SEARCH_MULTIQUERY_MIN_LENGTH` | `15` | 0 ~ 20 | Skip expansion for queries shorter than this (`0` = always expand). When expansion does run, the original-question search executes in parallel with it instead of waiting behind it |
| `SEARCH_HYBRID_ENABLED` | `true` | true/false | Add a BM25 (FTS5) keyword axis to RRF fusion (§10.7.2 — the FTS index is populated at indexing time regardless of this flag, so no re-index is needed to benefit) |
| `SEARCH_RETRY_ESCALATE` | `true` | true/false | Escalate on each retry — one flag, two axes: candidate pool `×(retryCount+1)` capped `×3`, **and** the final cut `topK + retryCount`. Growing the pool alone only changes which documents compete for the same topK slots, so evidence sitting just past the cut stays out on every attempt |
| `SEARCH_RERANK_ENABLED` | `false` | true/false | LLM reranking stage after RRF (adds 1 LLM call/turn) |
| `SEARCH_CANDIDATE_MULTIPLIER` | `3` | 2 ~ 5 | Candidate pool size for reranking — `topK × N` |
| `SEARCH_TAG_CANDIDATE_MULTIPLIER` | `2` | 1 ~ 5 | Candidate pool expansion when tags are selected — `candidateK = max(candidateK, topK × N)` |
| `SEARCH_RRF_KEYWORD_WEIGHT` | `1.0` | 0.5 ~ 3.0 | Weighted RRF (Phase 7-A) — BM25 keyword axis weight. Vector axes (1-3 MultiQuery variants) are always group-normalized to `1/axisCount`, so `1.0` is parity with the normalized vector group. No effect when `SEARCH_HYBRID_ENABLED=false` |
| `SEARCH_RRF_K` | `60` | 20 ~ 100 | Weighted RRF (Phase 7-A) — rank-fusion constant k (original paper default) |
| `SEARCH_CURATED_QA_ENABLED` | `true` | true/false | §10.10 — include the curated-Q&A axis (liked chat answers, embedded under the reserved `"curated"` version namespace) in RRF fusion. `false` skips that search entirely |
| `SEARCH_CURATED_QA_WEIGHT` | `1.2` | 0.5 ~ 5.0 | §10.10 — **👍-promoted** curated axis weight, applied flat like the keyword axis (not group-normalized with the vector axes) — above `1.0` so a verified answer tends to surface without dominating outright |
| `SEARCH_SUBMISSION_WEIGHT` | `1.5` | 0.5 ~ 5.0 | Weight of the **지식 제안** axis (approved user submissions). Both curated origins share one vector namespace and one search; they are split into two RRF axes by `MetaKey.CURATED_ORIGIN` so a hand-written, admin-reviewed entry can outrank a 👍 without raising both together |
| `SEARCH_QUERY_EMBED_CACHE_ENABLED` | `true` | true/false | Query embedding cache (Phase 7-A) — caches normalized-query → vector (Caffeine, in-memory) so repeated/similar questions skip the embedding round-trip; a cache hit also records no `embed:<model>` usage |
| `SEARCH_QUERY_EMBED_CACHE_MAX_SIZE` | `500` | 100 ~ 5000 | Query embedding cache entry cap |
| `SEARCH_QUERY_EMBED_CACHE_TTL_SECONDS` | `600` | 60 ~ 3600 | Query embedding cache TTL (seconds, write-based expiry) |
| `MAX_RETRY_COUNT` | `2` | 0 ~ 4 | Maximum re-retrieval attempts when evidence is insufficient |

### Conversation Memory / Summary Cache

Conversation history budget is auto-derived as `LLM_MAX_TOKENS × 0.75` (floor 1,000 chars). Both the raw-history fallback path and the precomputed-summary path (below) honor this exact same ceiling, so switching between them never changes how much context reaches the LLM.

| Variable | Default | Description |
|----------|---------|-------------|
| `MEMORY_FETCH_LIMIT_TURNS` | `50` | Max recent turns fetched (fallback path) before the char budget above trims them newest-first |
| `SUMMARY_MAX_CACHED_THREADS` | `3` | Number of threads kept warm in the precomputed-summary LRU cache |
| `SUMMARY_MAX_SUMMARY_CHARS` | `2000` | Hard cap on the generated summary string |
| `SUMMARY_RECENT_RAW_TURNS` | `2` | Verbatim recent turns appended after the summary (also budget-trimmed newest-first) |
| `SUMMARY_PRECOMPUTE_TTL_SECONDS` | `15` | Suppression window for duplicate summary-precompute triggers on the same thread |

> Per-format splitting strategy → [USER_MANUAL.md §4.1](documents/USER_MANUAL.md#41-형식별-청크-분할-전략)

Local LLM (LM Studio, Ollama, etc.):
```env
EMBED_BASE_URL=http://localhost:1234/v1
EMBED_MODEL=text-embedding-nomic-embed-text-v1.5
LOCAL_LLM_URL=http://localhost:1234/v1
# LOCAL_LLM_KEY is optional for local endpoints (no-key substituted when blank)
LOCAL_LLM_KEY=
LOCAL_LLM_MODEL=google/gemma-4-e4b
```

See [OPERATOR_MANUAL.md §5](documents/OPERATOR_MANUAL.md#5-llm-프로바이더-설정) for multi-provider configuration (Gemini, OpenAI, local + external hybrid).

## Project Structure

```
rag_java/
├── pom.xml                            # Spring Boot 3.5.15 + Spring AI 1.1.8
├── Dockerfile / docker-compose.yml
├── .env.example
├── scripts/
│   ├── install-hooks.sh               # Run once after cloning to activate git hooks
│   └── hooks/
│       └── pre-commit                 # Blocks accidental .env commits
└── src/main/
    ├── java/com/example/ragagent/
    │   ├── agent/
    │   │   ├── AgentState.java        # Immutable record — inter-node pipeline state
    │   │   └── AgentGraph.java        # Graph execution engine (switch expression)
    │   ├── config/
    │   │   ├── AppProperties.java     # @ConfigurationProperties (includes LlmConfig)
    │   │   └── WebConfig.java         # ChatClient bean + CORS + i18n (CookieLocaleResolver)
    │   ├── audit/
    │   │   └── AuditLogger.java                # Structured audit events → Logback AUDIT_FILE appender
    │   ├── context/
    │   │   ├── ThreadContext.java              # Per-request record (threadId, userId, locale)
    │   │   └── ThreadContextResolver.java      # HandlerMethodArgumentResolver for ThreadContext
    │   ├── controller/
    │   │   ├── ChatController.java             # REST POST /api/v1/chat; HTMX /ui/chat, /ui/chat/stream, thread management
    │   │   ├── DocumentController.java         # REST /api/v1/documents, /api/v1/images; async upload (202+taskId)
    │   │   ├── OperationsController.java       # REST GET /api/v1/health, /api/v1/llm/usage; HTMX thread list + LLM cards
    │   │   ├── AdminController.java            # /admin, /admin/chunks; document re-index; curated-Q&A + submission review
    │   │   ├── CuratedSubmissionController.java # /curated/submissions — user-submitted chunk board (post, withdraw, body-image upload, unread badge)
    │   │   ├── SettingsController.java         # /settings view + /admin/settings/update|reset
    │   │   ├── AuthController.java             # /login, /signup, /setup page controllers; auto-login after signup
    │   │   ├── GlobalExceptionHandler.java     # RFC 9457 ProblemDetail; 400/413 handling
    │   │   └── GlobalModelAdvice.java          # @ControllerAdvice; injects authEnabled model attr into all views
    │   ├── exception/                          # Domain exception classes
    │   ├── ingestion/
    │   │   ├── DocumentIndexer.java            # Core indexing logic; 3-phase sync; DocRegistry SQLite
    │   │   ├── DocRegistry.java                # doc_registry SQLite table management
    │   │   ├── VectorStoreFacade.java          # Backend-agnostic facade over VectorStoreProvider
    │   │   └── VectorStoreProvider.java        # chroma | sqlite-vec (app.vectorstore.type)
    │   ├── ratelimit/
    │   │   └── RateLimitFilter.java            # Bucket4j + Caffeine per-user token-bucket; 429 + RAG-RATE-001
    │   ├── llm/
    │   │   ├── LlmRouter.java         # Multi-provider routing: TaskType × RoutingMode; executeGated()/acquirePermit() — per-provider concurrency gate + 429 backpressure for the chat/query path
    │   │   ├── ConcurrencyLimitingChatModel.java  # ChatModel decorator — applies the concurrency gate to framework-internal callers (MultiQueryExpander) that bypass executeGated()
    │   │   ├── RoutingMode.java       # COST_FIRST|QUALITY_FIRST|PROGRESSIVE|LOCAL_ONLY
    │   │   ├── CircuitBreaker.java    # In-memory per-provider circuit breaker (Retry-After aware)
    │   │   ├── TrackingEmbeddingModel.java  # EmbeddingModel decorator — records embedding token usage separately (embed:<model>)
    │   │   ├── CachingEmbeddingModel.java   # EmbeddingModel decorator — Caffeine query-embedding cache (Phase 7-A) + in-flight single-flight dedup (ConcurrentHashMap<key,CompletableFuture>), composed outside tracking
    │   │   └── LoadBalancingEmbeddingModel.java  # EmbeddingModel decorator — least-in-flight across multiple embedding endpoints (§6.21 E1)
    │   ├── model/                     # Java 21 records
    │   │   ├── MetaKey.java           # Vector store metadata key constants
    │   │   └── ChatRequest/Response/SourceRef/DocumentInfo/SyncResult/ThreadMeta/ChatForm/LlmProviderReport/IndexingProgressEvent.java
    │   ├── security/
    │   │   ├── FileTypeDetector.java  # Magic-byte validation (PDF, DOCX/PPTX, TXT/MD, PNG/JPG/GIF/WebP)
    │   │   └── PromptInjectionGuard.java  # Input validation + API key masking
    │   ├── repository/
    │   │   ├── MemoryRepository.java              # Conversation memory interface (includes getTurns)
    │   │   ├── SqliteMemoryRepository.java        # SQLite WAL-based implementation
    │   │   ├── LlmUsageRepository.java            # LLM token usage SQLite repository
    │   │   ├── CuratedQaRepository.java           # curated_qa — liked answers + approved user submissions (origin=like|manual)
    │   │   ├── CuratedSubmissionRepository.java   # curated_submission — the proposal board (pending/approved/rejected)
    │   │   └── ImageDescriptionRepository.java    # image_descriptions table CRUD (Vision cache)
    │   └── service/
    │       ├── AgentService.java              # Agent pipeline entry point
    │       ├── StreamingAgentService.java     # SSE streaming pipeline orchestrator
    │       ├── GraphListener.java             # Hook interface for node/token/source events
    │       ├── ClassifierService.java         # Question type classification node
    │       ├── DirectAnswerService.java       # Direct response node for meta questions
    │       ├── RetrievalService.java          # Vector search node + LazyVision augmentation
    │       ├── AnswerService.java             # Answer generation + streaming + one combined sufficiency/grounding evaluation call
    │       ├── CriticService.java             # Evidence verification node (no LLM call — consumes the evaluation's grounded flag)
    │       ├── FinalizeService.java           # Conversation memory save node
    │       ├── MemoryService.java             # Multi-turn memory — SQLite persistence
    │       ├── RagService.java                # Document indexing + sync + image cleanup
    │       ├── AdminService.java              # Admin UI data (chunk browse/edit + vector store status) — chroma & sqlite-vec
    │       ├── CuratedQaService.java          # Curated-Q&A axis: like promotion + admin-approved submissions, embed/de-index
    │       ├── CuratedSubmissionService.java  # Proposal board: validation + tags, split-on-approve (1:N), reject, notification counts
    │       ├── CuratedImageStore.java         # Proposal body images: upload (allowlist/size/magic-byte/content-hash name), [이미지: …] marker bookkeeping, approval-time Vision description, reference-counted cleanup + startup orphan sweep
    │       ├── SettingsService.java           # runtime settings-override layer (AppProperties.OverrideSource) + /settings view/validation/audit
    │       ├── IndexingProgressService.java   # SSE emitter registry for async upload/sync progress; retains outcomes for hours so a reconnect after a long disconnect still learns the real result instead of hanging
    │       ├── MarkdownCorrectionService.java # Post-process LLM markdown output
    │       ├── DocumentLoaderService.java     # PDF/DOCX/TXT/MD loader + Markdown section parser; scanned PDF OCR
    │       ├── DocxToMarkdownConverter.java   # DOCX → Markdown with inline image extraction
    │       ├── PptxToMarkdownConverter.java   # PPTX → Markdown ([페이지: N] per-slide marker = section boundary; real title → ## heading; SmartArt/chart/hyperlink text; duplicate/TOC/divider-slide removal)
    │       ├── PdfToMarkdownConverter.java    # Non-scanned PDF → Markdown ([페이지: N] per-page marker = section boundary; no synthetic heading)
    │       ├── ImageExtractorService.java     # Scanned-PDF-only image extraction orchestrator (other formats extract inline in their own converter)
    │       ├── PdfImageExtractor.java         # PDFBox PDImageXObject-based PDF image extractor
    │       ├── PptxImageExtractor.java        # POI XSLFPictureShape-based PPTX image extractor + drawing-tool rasterization + SmartArt/chart/OLE graphic frames
    │       ├── VisionDescriptionService.java  # Image → Korean description via LLM (Vision task)
    │       ├── LazyVisionService.java         # On-demand Vision description + SQLite cache
    │       ├── ImageTypeClassifier.java       # Image type classification for prompt selection
    │       ├── OcrService.java                # Tesseract OCR for scanned PDFs (kor+eng)
    │       ├── EmfToPngConverter.java         # Batik WMFTranscoder→SVG→PNGTranscoder pipeline
    │       ├── LibreOfficeConverter.java      # LibreOffice headless WMF→PNG (20s timeout)
    │       ├── ThreadMetaService.java         # Conversation thread metadata management
    │       └── VectorStoreRegistry.java       # Per-version ChromaVectorStore management (chroma backend)
    └── resources/
        ├── application.properties
        ├── messages.properties            # UI strings — English (default)
        ├── messages_ko.properties         # UI strings — Korean
        ├── static/
        │   ├── css/
        │   │   ├── app.css                # Custom styles (bubbles, animations, responsive offcanvas/dvh/16px/44px)
        │   │   └── theme.css              # Light/dark CSS variables + Bootstrap dark mode overrides
        │   ├── manifest.webmanifest       # PWA manifest (name, icon, standalone)
        │   ├── sw.js                      # Service worker (NETWORK-FIRST, offline fallback only)
        │   ├── offline.html               # Offline fallback page (self-contained static HTML)
        │   ├── icons/icon.svg             # App icon (SVG, any maskable)
        │   └── js/
        │       └── chat-stream.js         # SSE streaming client (fetch + ReadableStream)
        └── templates/
            ├── layout/base.html           # Shared layout (Thymeleaf Layout Dialect; PWA meta + SW register)
            ├── chat.html                  # Chat page (server-renders previous turns)
            ├── documents.html             # Document management page
            ├── curated-submissions.html   # Knowledge-proposal board (post form + "my proposals" status list)
            ├── llm-usage.html             # LLM usage statistics page
            └── fragments/
                ├── admin-curated.html     # Admin curated-Q&A panel (lazy-loaded on expand)
                ├── admin-submissions.html # Admin proposal-review panel (lazy-loaded, status-filtered)
                ├── llm-usage-cards.html   # Provider cards (HTMX 30s auto-refresh)
                ├── thread-list.html       # HTMX thread list fragment
                ├── thread-item.html       # HTMX thread item fragment
                ├── doc-table-body.html    # HTMX document table tbody fragment
                ├── message-user.html      # User message bubble fragment
                ├── message-assistant.html # HTMX assistant bubble (includes source hover preview)
                └── message-error.html     # HTMX error bubble fragment
```

## Agent Pipeline

```
User question
  └─▶ [Classifier]  → Classify question type (concept / usage / error / version / meta)
        ├─ meta  ──▶ [DirectAnswer] → [Finalize] → Response
        └─ other ──▶ [Retrieval]   (LLM generates optimal query → vector search)
                       └─▶ [Answer]   (Structured answer + sufficient self-evaluation)
                              ├─ Insufficient evidence ──▶ [Retrieval] (up to 2 retries)
                              └─ Sufficient           ──▶ [Finalize] (when response mode = S)
                                                     └─▶ [Critic]   (when response mode != S)
                                                             ├─ Ungrounded ──▶ [Retrieval]
                                                             └─ Grounded   ──▶ [Finalize] → Response
```

## Features

- **Authentication** — Spring Security form login with BCrypt(12) password hashing; account lockout after 5 failed attempts (15-min lock); `/login`, `/signup`, `/setup`; toggle off with `app.auth.enabled=false` for local no-login deployments; `app.auth.management-only=true` keeps chat/browsing guest-open while requiring login for document management and `/admin` — see [OPERATOR_MANUAL.md §9.4.2](documents/OPERATOR_MANUAL.md#942-관리-전용-인증-management-only)
- **Web UI** — Thymeleaf + HTMX chat, document management, and LLM usage interface with KO/EN language switcher
- **SSE real-time streaming** — per-node stage badges (classifier → retrieval → answer → critic), token-level streaming via `chat-stream.js` (fetch + ReadableStream). In response mode S, the critic stage is skipped
- **Dark mode** — CSS variable–based light/dark toggle, auto-detects `prefers-color-scheme` with `localStorage` user override
- **Mobile & PWA** — responsive offcanvas thread drawer, `100dvh` bottom-pinned input, `table-responsive` overflow handling, iOS 16px no-zoom inputs; installable PWA (`manifest.webmanifest`, service worker with offline fallback that never caches authenticated/RAG/SSE responses, iOS "Add to Home Screen" hint); icon buttons carry i18n `aria-label`, 44px touch targets, `:focus-visible` outlines
- **Question classification + routing** — meta (greetings/small talk) answered directly without RAG; all others go through the full pipeline
- **Multi-LLM routing** — `LlmRouter` selects providers by `TaskType × RoutingMode`; COST_FIRST / QUALITY_FIRST / PROGRESSIVE / LOCAL_ONLY
- **Circuit Breaker** — automatic provider blocking on HTTP 429/errors (Retry-After aware), priority-based failover, status visible in LLM usage dashboard
- **Per-provider concurrency gate + backpressure** — the chat/query path never sends more concurrent requests to a provider than it can serve (sized to the LLM server's `--parallel`); a request that waits past `LLM_PERMIT_WAIT_TIMEOUT_SECONDS` (default 60s) fails fast with HTTP 429 + `Retry-After` instead of hanging until the 600s read timeout. Indexing/background LLM calls are unaffected (they keep their own semaphore)
- **In-flight single-flight (embeddings)** — concurrent requests for the exact same (post-normalization) text — e.g. several users asking the same question at nearly the same moment — collapse into one delegate call; the rest share that result instead of each recomputing it (`CachingEmbeddingModel`)
- **Overload-aware circuit breaking** — a 429/402/503 with no fallback provider available (e.g. a lone LOCAL deployment) triggers a short 30s block instead of the full multi-minute default, so a transient capacity blip doesn't take chat down for everyone; falls back to normal blocking + auto-failover whenever another provider can pick up the slack
- **Same-priority load balancing** — registering multiple providers at the same role + priority (e.g. two LOCAL servers) automatically distributes requests to whichever has more free concurrency-gate capacity (least-in-flight), for horizontal throughput scaling — no code changes, just deployment config
- **Task-tier model routing (small-LLM offload)** — reasoning-free chores (keyword+context extraction, conversation summary, thread title, MultiQuery query expansion) route to `TaskType.MICRO_TEXT`; register a dedicated small (~500MB) local model at `type=MICRO_TEXT` and those chores offload to it while the main model stays dedicated to answers and the quality-sensitive classify / meta-direct-answer. Falls back to the main model when no small model is registered (zero regression) — see [LLM_ROUTING.md §9](documents/LLM_ROUTING.md)
- **Embedding load balancing + parallel sub-batch embedding** — multiple embedding endpoints (`EMBED_ADDITIONAL_BASE_URLS`, same model/dimension) are balanced least-in-flight; indexing can embed a document's sub-batches in parallel (`EMBED_MAX_CONCURRENT_BATCHES`) to fill them. Both opt-in (default single-endpoint, serial) — see [OPERATOR_MANUAL §3.2](documents/OPERATOR_MANUAL.md)
- **Settings page (`/settings`)** — view the effective LLM/RAG configuration (providers, routing, embedding, search tuning) in one place; three families of values are **hot-editable without a restart** (persisted in `settings_override`, revert to the property default on delete): search tuning (similarity threshold, RRF weight/k, candidate multipliers, multi-query min length/enabled, retry escalation, topK, hybrid search — apply on the next search), indexing/chunking (chunk size/overlap/min, **chunking strategy**, concurrent file/LLM-call limits — apply on the next indexing or ↺ re-index), and Direct-answer temperature (apply on the next Direct call). Editing is admin-only and audited; restart-required values (rerank-enabled, general temperature/max-tokens, embedding config, etc.) are shown read-only
- **Vector search** — LLM generates an optimized search query (`MultiQueryExpander`, 3 parallel queries; skipped for short keyword-ish questions), then performs vector similarity search via the selected backend (ChromaDB or sqlite-vec); the original-question search runs in parallel with query expansion instead of waiting behind it. Batched Chroma search requests only the metadata/document/distance fields it actually reads, not the (unused) embedding vectors, keeping large-candidate-pool responses lean
- **Contextual Retrieval** — each chunk's embedding and lexical (`chunk_fts`) index include a prepended context header (`{filename} > {section heading}`, plus an optional LLM-generated 1-2 sentence summary from the same call that extracts keywords) so chunks that read ambiguously alone (tables, code fragments, pronoun-heavy text) are recalled more reliably; the header never appears in stored/displayed text, the source preview, or the answer prompt — only in the embedding/lexical-search input
- **Embedding input normalization** — decorative markdown (separator lines, bold/italic/underline markers) is stripped from the embedding, `chunk_fts`, and answer-prompt inputs (not from stored/displayed text), reducing noise in the search index and prompt token usage
- **Response length modes (S/M/L)** — a per-message chat toggle picks how much detail the answer includes; each mode's target is the larger of a ratio of `LLM_MAX_TOKENS` (15%/40%/70%) and a fixed character floor (2,000/5,000/10,000), so S and M stay clearly distinct even on a small configured ceiling. The same number is applied both as `maxTokens` on blocking calls and as a "~N characters" target in the style-instruction prompt — streaming answers rely on the prompt alone, since they have no per-call token cap by design. `L` (verbatim-max) only makes sense with retrieved context and is disabled while Direct mode is on. `S` prioritizes speed/compactness and skips the CRITIC stage
- **Question suggestion/reuse** — while typing in chat, suggestions appear after 2+ characters. Only previously asked questions with length **<= 50 chars** are eligible for the suggestion list, and selecting one performs a final chunk-hash validation before reuse; if validation fails, the same question is automatically sent as a normal RAG query
- **Curated Q&A from likes (§10.10)** — liking a chat answer promotes it into a separately embedded, shared knowledge axis (reserved vector-store version namespace, survives document re-indexing) that future searches fuse in via a weighted RRF axis (`SEARCH_CURATED_QA_ENABLED`/`SEARCH_CURATED_QA_WEIGHT`, hot-editable via `/settings`); the answer is injected as grounding evidence, not returned verbatim, so the LLM still reconciles against current documents. The turn's own asker can edit it inline in the chat bubble (re-embeds automatically); admins moderate every user's curated entries from a dedicated `/admin` card at the bottom of the page (edit — with the same wide-screen live preview the chunk editor has — or force-remove, independent of the original like). Liking an **L**-mode answer skips the embed entirely — its content already mirrors indexed source material closely enough that re-embedding it would be redundant (the like itself is still recorded). A promoted answer inherits the search-scope tags its question was asked under (persisted per turn), so it survives a tag-scoped search; one whose scope is unknown is treated as belonging to every scope rather than none — without that, ticking any tag chip used to drop every curated entry from the results
- **User-submitted chunks (청크 추가 게시판)** — users post a proposal (title + body) at `/curated/submissions`; an admin reviews it from a dedicated `/admin` card and either runs the embedding or rejects it with a mandatory reason. Approved proposals land in the same curated-Q&A axis as liked answers (so `SEARCH_CURATED_QA_*` applies unchanged); the submission board itself is a separate table, keeping `curated_qa.status='active'` meaning exactly "contributing to search". **Admin approval is the only gate between user-authored text and the `[검색된 문서]` block of an answer prompt** — the review panel always shows the body in full, and there is deliberately no bulk/auto-approve path. Notifications are 60s header-badge polls in both directions: pending count for the admin (first poll fires right after login), review outcome for the author (cleared by opening "내 제안"). **There is no body length limit**: approval runs the body through `ChunkSplitter` and creates N curated rows, the same way a document is indexed (so `CHUNK_SPLIT_GRANULAR` and the table/code-block boundary protection apply) — which removes the "too long to embed" failure mode structurally instead of rejecting input. The submission is then 등록 완료 while any of its chunks lives and 회수됨 once none do; removing one chunk takes down the whole submission, so a partially-registered proposal never appears. Body is authored and previewed as Markdown (rendered through DOMPurify on both the form and the admin review panel), and optional tags scope the entry to matching tag selections — an untagged one stays visible in every scope. See [OPERATOR_MANUAL.md §6.9](documents/OPERATOR_MANUAL.md#69-청크-추가-게시판-사용자-제안--관리자-임베딩)
- **Images in proposals, positioned by an inline marker** — the form's **이미지 추가** button uploads the file immediately and splices a `[이미지: images/submissions/{sha16}.png]` marker in at the caret. **The marker's position is the image's position**, so everything after that is ordinary text editing and an image travels with the paragraph it illustrates when approval splits the body into chunks. Reusing the marker the document pipeline already emits is what makes the rest work unchanged: `/api/v1/images/**` already serves the path, and `RetrievalService` already turns `image_paths` metadata into answer thumbnails, so a curated chunk gets the same thumbnails a document chunk does. At approval — **before** splitting, so a marker can never be cut away from its description — each image is described via `LazyVisionService` and a `[이미지 설명: ...]` line is injected into the body, which is what makes the picture's *content* searchable (it also lands in the shared description cache, so query-time Lazy Vision never re-analyzes it). The approve request waits on those Vision calls synchronously and the admin button locks with a spinner meanwhile. Since the board is guest-open in every auth mode, this is the one place an unauthenticated caller writes a binary to disk: extension allowlist → 5MB → magic bytes → content-hash filename (the client picks no part of the path), max 10 per body. Cleanup is reference-counted rather than ownership-based — filenames are content hashes, so two proposals can share one file — on reject/withdraw plus a startup sweep for drafts that were never posted
- **ReAct re-retrieval** — automatic re-retrieval up to 2 times when evidence is insufficient. Each retry widens both the candidate pool (`×(retryCount+1)`) **and** the final cut (`topK + retryCount`) — growing only the pool would keep handing the answer node the same number of documents, so evidence sitting just past the cut would stay out on every attempt and burn the whole retry budget
- **Critic verification** — one evaluation call judges sufficiency and groundedness together (the CRITIC node consumes that flag rather than making a second round-trip), against **the same documents the answer was written from** — the evidence window used to be the top 5 while the answer used all `SEARCH_TOP_K`, so an answer correctly citing a value found only in document #6-8 was judged as unfounded. Both prompts also see the same normalized form of each value. In response mode S, this CRITIC stage is skipped and retries are driven only by the ANSWER sufficiency check
- **Environment-dependent values are not a grounding failure** — paths, hosts, IPs, ports, URLs, environment-variable values and account names legitimately differ between the machine a document was written on and the reader's. The evaluator is forbidden from failing `grounded` on those alone and instead returns a one-line `envNote`, surfaced under the answer as "ℹ️ 환경에 따라 달라질 수 있는 값: …" — kept even on a **passing** turn, since it is advice for the reader ("substitute your own path"), not a verdict. A mismatch in the procedure or behavior itself is not an environment difference and still fails verification
- **PROGRESSIVE mode** — starts with COST_FIRST; if quality score < threshold, re-runs Answer with PREMIUM provider and marks response with upgrade badge
- **Per-visitor chat in no-auth mode** — `app.auth.guest-identity` (`shared`/`ip`/`cookie`/`hybrid`) gives each visitor their own sidebar threads and history instead of one shared guest, with no storage change: every table is already `user_id`-keyed, so only the id the auth filter injects had to become per-visitor. `hybrid` (recommended) uses a long-lived `rag_visitor` cookie when present and otherwise derives the id from the client IP and stores it as that cookie — surviving both a DHCP lease change and a cookie wipe. Ids are `guest-<hex>` HMACs over a persisted server key, so raw IPs never land in the DB and guest rows stay identifiable for a later hand-off to real accounts. Uploaded documents stay shared. Defaults to `shared` (zero regression)
- **Trusted client IP resolution** — `app.trust-forwarded-for` (default `false`) gates whether `X-Forwarded-For` is believed, for both rate limiting and guest identity. Off, a forged header can't refill an attacker's rate-limit bucket or impersonate another visitor; on (required behind Caddy/nginx), real client IPs are recovered instead of every visitor collapsing into the proxy address
- **Rate limiting** — Bucket4j + Caffeine per-user token-bucket; 429 `RAG-RATE-001` + `Retry-After` header; configurable via `app.rate-limit.*`
- **Audit logging** — structured events written to rolling file via Logback; configurable via `app.audit.*`
- **Image processing pipeline** — PDF/PPTX/DOCX image extraction → stored under `data/images/{imageId}/` (a content-hash key derived from the document's SHA-256, distinct from the document's own `docId`, so long filenames aren't repeated per image); PPTX pictures with annotation shapes (highlight circle, arrow, callout) drawn on them are composited into one image (`app.pptx-image.merge-annotated-pictures`), as are tables with an overlapping annotation shape (table also kept as a markdown table) and real Ctrl+G groups / SmartArt; loose overlapping shapes are only merged into one diagram image when `app.pptx-image.rasterize-shapes=true` (default off). DOCX pictures likewise merge with legacy-VML annotation shapes (rect/oval/line) found in the same paragraph (`app.docx-image.merge-annotated-shapes` — a proximity approximation, since POI exposes no shape coordinates for DOCX); Lazy Vision description on first retrieval (cached in SQLite); image thumbnails shown in answer bubble
- **Chat image persistence + per-image exclusion** — answer-related images are persisted per turn and restored when reopening `/chat/{threadId}`, so they no longer disappear after refresh. Clicking a thumbnail opens a zoom modal; choosing **Exclude from conversation** hides only that specific image from that specific turn, without deleting the source file or document index
- **Chat-time image analysis progress + skip** — when a search result includes an image whose description isn't already embedded in the chunk text, Lazy Vision analyzes it before the answer is generated; the header badge shows "이미지 분석 중 (2/5)" and counts up as each analysis completes, with a **건너뛰기** (skip) link that stops the *wait* only — the analysis keeps running in the background and lands in the SQLite cache for the next turn that needs it, so nothing is wasted. A chunk indexed with "이미지 설명 추가" (its `[이미지 설명: ...]` already embedded) is never re-analyzed at query time
- **Image type classification** — pre-classifies images (diagram / screenshot / chart / photo / other) and uses type-specific Vision prompts for better descriptions
- **Scanned PDF OCR** — Tesseract OCR (kor+eng) for pages with insufficient text; activated via `app.image-description.ocr-enabled=true`
- **EMF/WMF conversion** — DOCX Windows Metafile images converted to PNG via Batik (EMF) or LibreOffice headless (WMF)
- **Multi-turn conversation** — thread-based history persistence (SQLite WAL, survives restarts)
- **Message bubble restore** — re-entering `/chat/{threadId}` server-renders all previous turn bubbles
- **Source hover preview** — `SourceRef` record with Bootstrap Popover shows a 600-char chunk text preview on hover. This is rendered consistently for new streaming answers, reused answers (`db-reuse`), and restored history when reopening `/chat/{threadId}`; on non-mobile screens the popover is roughly 2x wider with a slightly smaller font so the excerpt reads with less wrapping
- **Editor live preview** — on wide desktop screens, the `/admin` chunk-edit **and curated-Q&A-edit** offcanvases split into a live Markdown preview (rendering images and tables) alongside the text editor, updating as you type; narrow screens keep the existing single-column editor. Both use the same width threshold and the same renderer, so a curated answer's tables, code blocks and image markers are checked the same way a chunk's are — which matters because that markdown goes straight into an answer prompt as grounding evidence
- **Smart heading-number default** — the upload "generate heading numbers" checkbox auto-unchecks whenever a PPTX is selected (the option is never applied to PPTX server-side; PDF is unaffected and stays checked) and warns when PPTX is mixed with other formats in one upload, since the option applies per-batch, not per-file
- **Document export (MD/TXT/DOCX)** — the document list's per-row **Export** button (admin-only) rebuilds a document from its currently indexed chunks (not the saved converted MD), so `/admin` chunk edits are reflected; `ChunkReassembler` undoes the retrieval-oriented duplication `ChunkSplitter` introduces (reinjected subheadings, parent-chapter breadcrumbs, split code-fence markers, repeated table headers, sliding-window overlap) before rendering, so the result reads like the original document rather than concatenated search chunks — validated against a real 335-chunk document at 0.001% character-count deviation from the source. MD downloads bundle images as a ZIP when present (an image file that no longer exists degrades to a `(이미지 없음: …)` note rather than a broken link). DOCX embeds images via POI wherever they sit — a marker after a bullet or mid-sentence gets its own centered picture paragraph, one inside a table cell is embedded in place at column width — and renders fenced code blocks as a bordered 1×1 table, left-aligned and monospaced, with `//`, `#` and `/* … */` comments colored (string literals are tracked, so `"http://…"` stays uncolored). Each document's actual `CHUNK_OVERLAP` at index time is recorded in `doc_registry` (backfilled at startup for older rows) so later retuning the setting can't corrupt an older document's export. PPTX export isn't supported yet
- **Code syntax highlighting** — highlight.js applied after DOMPurify sanitize, synced with dark mode
- **LLM usage dashboard** — per-provider daily/weekly/monthly token stats, Chart.js daily history chart, circuit breaker countdown; embedding usage tracked separately (`embed:<model>`, with an approximation fallback when the server omits usage); inactive providers with no history auto-hide, and orphaned records (removed from config) surface as admin-deletable cards
- **Document versioning** — per-version isolation (chroma: separate collection; sqlite-vec: `version` partition key)
- **Incremental indexing** — SHA-256 change detection, `doc_registry` SQLite table persistence (per-user). On sqlite-vec, embeddings are inserted per token sub-batch as soon as each one is embedded rather than buffered for the whole document, so peak memory during a large-document index scales with sub-batch size, not document size
- **Batched keyword extraction** — chunks are bundled N-at-a-time (default 2, `INDEXING_KEYWORD_BATCH_SIZE`) into one LLM call during indexing instead of one call per chunk, cutting round-trips roughly N-fold; falls back to per-chunk TF extraction if a batch call or its parsing fails
- **Multiple document formats** — PDF, PPTX, DOCX, TXT, MD
- **PPTX/PDF → Markdown conversion cleanup** — non-scanned PDF and PPTX convert to Markdown where a `[페이지: N]` marker (not a synthetic heading) is the per-page/slide section boundary; PPTX additionally drops image-less duplicate slides, agenda/table-of-contents slides (bullets that mostly match other slides' titles), and title-only section-divider slides — numbered/keyword/short-noun-phrase titles like "PART 2"/"목차"/"결제 시스템", while sentence-like key-message titles are kept (`app.pptx-remove-duplicate-slides`, `app.pptx-drop-divider-slides`, both default on) — so content-free slides stay out of the search index
- **Java 21 Virtual Threads** — lightweight threads for all LLM I/O and parallel indexing

## Endpoints

### Web UI

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/` | Chat home (creates a new thread) |
| `GET` | `/chat/{threadId}` | Resume an existing thread (restores previous message bubbles) |
| `GET` | `/documents` | Document management page |
| `GET/POST` | `/curated/submissions` | Knowledge-proposal board — post a chunk, track its review outcome |
| `POST` | `/curated/submissions/images` | Upload a proposal body image → returns the `[이미지: …]` marker to splice in at the caret |
| `GET` | `/llm-usage` | LLM usage statistics page |
| `PATCH` | `/ui/threads/{threadId}/turns/{turnId}/images/exclude` | Exclude one thumbnail image from the current conversation record only |
| `GET/POST` | `/login` | Login page (auth mode, or no-auth management-only submode) |
| `GET/POST` | `/signup` | Sign-up page (auth mode only) |
| `GET/POST` | `/setup` | First-run admin setup (no-auth mode only; redirects once admin exists) |

### REST API

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/health` | Health check |
| `POST` | `/api/v1/chat` | Ask a question → get an answer |
| `POST` | `/api/v1/documents` | Upload and index a document |
| `POST` | `/api/v1/documents/sync` | Incremental folder sync |
| `GET` | `/api/v1/documents` | List indexed documents |
| `DELETE` | `/api/v1/documents/{docId}` | Delete a document |
| `GET` | `/api/v1/images/{docId}/{filename}` | Serve an extracted image file |
| `GET` | `/api/v1/llm/usage` | Per-provider token usage + Circuit Breaker status |
| `GET` | `/api/v1/llm/usage/history` | Daily token history (`?days=7\|30\|90`) |
