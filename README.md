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

> **Vector store backend** — defaults to ChromaDB. Set `VECTORSTORE_TYPE=sqlite-vec` to store vectors in the SQLite file instead and **skip the "Start Chroma" step** below (requires an operator-provided `vec0` native extension — see [OPERATOR_MANUAL.md](OPERATOR_MANUAL.md)). For a fully offline, no-Docker setup (sqlite-vec + local llama-server), see [OPERATOR_MANUAL.md §4.5](OPERATOR_MANUAL.md#45-폐쇄망air-gapped--노-도커-실행).

#### Development mode (run from source)

```bash
# 1. Start Chroma (separate terminal)
docker run --rm -p 8001:8000 \
  -v "$(pwd)/data/chroma:/data" \
  chromadb/chroma:latest

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
  chromadb/chroma:latest

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
  chromadb/chroma:latest

# 3. Load env vars and run
export $(grep -v '^#' .env | xargs)
mvn spring-boot:run

# Shutdown
container stop <CONTAINER_ID>
container system stop
```

Open: http://localhost:8080

See [USER_MANUAL.md](USER_MANUAL.md) for usage instructions and [OPERATOR_MANUAL.md](OPERATOR_MANUAL.md) for deployment and LLM configuration.

## Environment Variables

### Connection / Authentication

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `SERVER_PORT` | — | `8080` | Port the application listens on. Change only on conflict with another local service |
| `LOCAL_LLM_URL` | — | `http://localhost:1234/v1` | LOCAL provider endpoint (also used as embedding fallback) |
| `LOCAL_LLM_KEY` | — | `lm-studio` | LOCAL provider API key. **Optional for local endpoints** (llama-server needs none) — the LOCAL provider is kept even when blank (`no-key` is substituted) |
| `LOCAL_LLM_MODEL` | — | `google/gemma-4-e4b` | LOCAL provider model name |
| `LLM_ROUTING_MODE` | — | `COST_FIRST` | Default routing mode (`app.llm.default-routing-mode`). Air-gapped / local-only: set `LOCAL_ONLY` to block all external provider calls |
| `OPENAI_API_KEY` | — | — | Required for OpenAI providers. Providers auto-disabled at startup if unset |
| `GEMINI_API_KEY` | — | — | Required for Gemini providers. Providers auto-disabled at startup if unset |
| `EMBED_BASE_URL` | — | `LOCAL_LLM_URL` | Embedding endpoint. Falls back to `LOCAL_LLM_URL` if unset |
| `EMBED_API_KEY` | — | `LOCAL_LLM_KEY` | Embedding API key. Falls back to `LOCAL_LLM_KEY` if unset |
| `EMBED_MODEL` | — | `text-embedding-nomic-embed-text-v1.5` | Embedding model name |
| `EMBED_DIMENSIONS` | sqlite-vec only | — | Embedding model's real output dimension (`app.embedding.dimensions`). Required for `sqlite-vec` (baked into the `vec0` DDL — must match the model: nomic=768, bge-m3=1024). Ignored by chroma |
| `EMBED_USAGE_FALLBACK_ENABLED` | — | `true` | When the embedding server doesn't report token usage, approximate input tokens as chars/4 for the `/llm-usage` dashboard instead of recording 0 |
| `EMBED_MAX_CHUNK_CHARS` | — | `0` (off) | Hard per-chunk character ceiling to fit the embedding server's batch/token limit. Set (e.g. `450`) when you hit `input (N tokens) is too large ... (batch size: 512)`; oversized chunks are force-split at line boundaries. Prefer raising the server batch (`llama-server -b/-ub`) first — see [OPERATOR_MANUAL §8](documents/OPERATOR_MANUAL.md#8-문제-해결) |
| `VECTORSTORE_TYPE` | — | `chroma` | Vector store backend — `chroma` or `sqlite-vec` |
| `SQLITE_VEC_EXTENSION_PATH` | — | — | sqlite-vec only — path to the operator-provided `vec0` loadable extension |
| `CHROMA_HOST` | — | `http://localhost` | Chroma server host (chroma backend) |
| `CHROMA_PORT` | — | `8001` | Chroma server port (chroma backend) |
| `DATA_DIR` | — | `./data` | Storage path for documents, registry, and SQLite DB |

### RAG Tuning

| Variable | Default | Recommended Range | Description |
|----------|---------|-------------------|-------------|
| `CHUNK_SIZE` | `800` | 300 ~ 2000 | Document chunk size (characters) |
| `CHUNK_OVERLAP` | `100` | 0 ~ CHUNK_SIZE × 0.25 | Overlap between chunks (characters, boundary context only) |
| `MIN_CHUNK_SIZE` | `100` | 50 ~ CHUNK_SIZE × 0.25 | Minimum chunk size threshold for tiny-chunk merge |
| `SEARCH_TOP_K` | `7` | 2 ~ 15 | Number of documents returned by vector search |
| `SEARCH_SIMILARITY_THRESHOLD` | `0.0` | 0.0 ~ 0.75 | Min cosine similarity to keep a chunk (`0.0` = accept all) |
| `SEARCH_MULTIQUERY_ENABLED` | `true` | true/false | Expand the query into sub-queries before search |
| `SEARCH_MULTIQUERY_MIN_LENGTH` | `0` | 0 ~ 20 | Skip expansion for queries shorter than this (`0` = always expand) |
| `SEARCH_HYBRID_ENABLED` | `false` | true/false | Add a BM25 (FTS5) keyword axis to RRF fusion (re-index required) |
| `SEARCH_RETRY_ESCALATE` | `true` | true/false | Grow candidate pool on each retry — `×(retryCount+1)`, capped `×3` |
| `SEARCH_RERANK_ENABLED` | `false` | true/false | LLM reranking stage after RRF (adds 1 LLM call/turn) |
| `SEARCH_CANDIDATE_MULTIPLIER` | `3` | 2 ~ 5 | Candidate pool size for reranking — `topK × N` |
| `SEARCH_RRF_KEYWORD_WEIGHT` | `1.0` | 0.5 ~ 3.0 | Weighted RRF (Phase 7-A) — BM25 keyword axis weight. Vector axes (1-3 MultiQuery variants) are always group-normalized to `1/axisCount`, so `1.0` is parity with the normalized vector group. No effect when `SEARCH_HYBRID_ENABLED=false` |
| `SEARCH_RRF_K` | `60` | 20 ~ 100 | Weighted RRF (Phase 7-A) — rank-fusion constant k (original paper default) |
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

> Per-format splitting strategy → [USER_MANUAL.md §4.1](USER_MANUAL.md#41-형식별-청크-분할-전략)

Local LLM (LM Studio, Ollama, etc.):
```env
EMBED_BASE_URL=http://localhost:1234/v1
EMBED_MODEL=text-embedding-nomic-embed-text-v1.5
LOCAL_LLM_URL=http://localhost:1234/v1
LOCAL_LLM_KEY=lm-studio
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
    │   │   ├── AdminController.java            # /admin, /admin/chunks; document re-index
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
    │   │   ├── LlmRouter.java         # Multi-provider routing: TaskType × RoutingMode
    │   │   ├── RoutingMode.java       # COST_FIRST|QUALITY_FIRST|PROGRESSIVE|DUAL|LOCAL_ONLY
    │   │   ├── CircuitBreaker.java    # In-memory per-provider circuit breaker (Retry-After aware)
    │   │   ├── TrackingEmbeddingModel.java  # EmbeddingModel decorator — records embedding token usage separately (embed:<model>)
    │   │   └── CachingEmbeddingModel.java   # EmbeddingModel decorator — Caffeine query-embedding cache (Phase 7-A), composed outside tracking
    │   ├── model/                     # Java 21 records
    │   │   ├── MetaKey.java           # Vector store metadata key constants
    │   │   └── ChatRequest/Response/SourceRef/DocumentInfo/SyncResult/ThreadMeta/ChatForm/LlmProviderReport/IndexingProgressEvent.java
    │   ├── security/
    │   │   ├── FileTypeDetector.java  # Magic-byte validation (PDF, DOCX/PPTX, TXT/MD)
    │   │   └── PromptInjectionGuard.java  # Input validation + API key masking
    │   ├── repository/
    │   │   ├── MemoryRepository.java              # Conversation memory interface (includes getTurns)
    │   │   ├── SqliteMemoryRepository.java        # SQLite WAL-based implementation
    │   │   ├── LlmUsageRepository.java            # LLM token usage SQLite repository
    │   │   └── ImageDescriptionRepository.java    # image_descriptions table CRUD (Vision cache)
    │   └── service/
    │       ├── AgentService.java              # Agent pipeline entry point
    │       ├── StreamingAgentService.java     # SSE streaming pipeline orchestrator
    │       ├── GraphListener.java             # Hook interface for node/token/source events
    │       ├── ClassifierService.java         # Question type classification node
    │       ├── DirectAnswerService.java       # Direct response node for meta questions
    │       ├── RetrievalService.java          # Vector search node + LazyVision augmentation
    │       ├── AnswerService.java             # Answer generation + streaming + evidence check
    │       ├── CriticService.java             # Evidence verification node
    │       ├── FinalizeService.java           # Conversation memory save node
    │       ├── MemoryService.java             # Multi-turn memory — SQLite persistence
    │       ├── RagService.java                # Document indexing + sync + image cleanup
    │       ├── AdminService.java              # Admin UI data (chunk browse/edit + vector store status) — chroma & sqlite-vec
    │       ├── IndexingProgressService.java   # SSE emitter registry for async upload/sync progress
    │       ├── MarkdownCorrectionService.java # Post-process LLM markdown output
    │       ├── DocumentLoaderService.java     # PDF/DOCX/TXT/MD loader + Markdown section parser; scanned PDF OCR
    │       ├── DocxToMarkdownConverter.java   # DOCX → Markdown with inline image extraction
    │       ├── PptxToMarkdownConverter.java   # PPTX → Markdown (title heading per slide, [페이지: N] marker, SmartArt/chart-title/hyperlink text)
    │       ├── PdfToMarkdownConverter.java    # Non-scanned PDF → Markdown (synthetic per-page heading, [페이지: N] marker)
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
            ├── llm-usage.html             # LLM usage statistics page
            └── fragments/
                ├── llm-usage-cards.html   # Provider cards (HTMX 30s auto-refresh)
                ├── thread-list.html       # HTMX thread list fragment
                ├── thread-item.html       # HTMX thread item fragment
                ├── doc-row.html           # HTMX document table row fragment
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
                              └─ Sufficient           ──▶ [Critic]   (evidence verification)
                                                              ├─ Ungrounded ──▶ [Retrieval]
                                                              └─ Grounded   ──▶ [Finalize] → Response
```

## Features

- **Authentication** — Spring Security form login with BCrypt(12) password hashing; account lockout after 5 failed attempts (15-min lock); `/login`, `/signup`, `/setup`; toggle off with `app.auth.enabled=false` for local no-login deployments
- **Web UI** — Thymeleaf + HTMX chat, document management, and LLM usage interface with KO/EN language switcher
- **SSE real-time streaming** — per-node stage badges (classifier → retrieval → answer → critic), token-level streaming via `chat-stream.js` (fetch + ReadableStream); DUAL mode streams both tabs simultaneously
- **Dark mode** — CSS variable–based light/dark toggle, auto-detects `prefers-color-scheme` with `localStorage` user override
- **Mobile & PWA** — responsive offcanvas thread drawer, `100dvh` bottom-pinned input, `table-responsive` overflow handling, iOS 16px no-zoom inputs; installable PWA (`manifest.webmanifest`, service worker with offline fallback that never caches authenticated/RAG/SSE responses, iOS "Add to Home Screen" hint); icon buttons carry i18n `aria-label`, 44px touch targets, `:focus-visible` outlines
- **Question classification + routing** — meta (greetings/small talk) answered directly without RAG; all others go through the full pipeline
- **Multi-LLM routing** — `LlmRouter` selects providers by `TaskType × RoutingMode`; COST_FIRST / QUALITY_FIRST / PROGRESSIVE / DUAL (parallel local + external) / LOCAL_ONLY
- **Circuit Breaker** — automatic provider blocking on HTTP 429/errors (Retry-After aware), priority-based failover, status visible in LLM usage dashboard
- **Vector search** — LLM generates an optimized search query (`MultiQueryExpander`, 3 parallel queries), then performs vector similarity search via the selected backend (ChromaDB or sqlite-vec)
- **Contextual Retrieval** — each chunk's embedding and lexical (`chunk_fts`) index include a prepended context header (`{filename} > {section heading}`, plus an optional LLM-generated 1-2 sentence summary from the same call that extracts keywords) so chunks that read ambiguously alone (tables, code fragments, pronoun-heavy text) are recalled more reliably; the header never appears in stored/displayed text, the source preview, or the answer prompt — only in the embedding/lexical-search input
- **Embedding input normalization** — decorative markdown (separator lines, bold/italic/underline markers) is stripped from the embedding, `chunk_fts`, and answer-prompt inputs (not from stored/displayed text), reducing noise in the search index and prompt token usage
- **ReAct re-retrieval** — automatic re-retrieval up to 2 times when evidence is insufficient
- **Critic verification** — LLM double-checks whether the generated answer is grounded in documents
- **PROGRESSIVE mode** — starts with COST_FIRST; if quality score < threshold, re-runs Answer with PREMIUM provider and marks response with upgrade badge
- **DUAL mode** — runs local and external LLM in parallel, displays results in side-by-side tabs
- **Rate limiting** — Bucket4j + Caffeine per-user token-bucket; 429 `RAG-RATE-001` + `Retry-After` header; configurable via `app.rate-limit.*`
- **Audit logging** — structured events written to rolling file via Logback; configurable via `app.audit.*`
- **Image processing pipeline** — PDF/PPTX/DOCX image extraction → stored under `data/images/{docId}/`; Lazy Vision description on first retrieval (cached in SQLite); image thumbnails shown in answer bubble
- **Image type classification** — pre-classifies images (diagram / screenshot / chart / photo / other) and uses type-specific Vision prompts for better descriptions
- **Scanned PDF OCR** — Tesseract OCR (kor+eng) for pages with insufficient text; activated via `app.image-description.ocr-enabled=true`
- **EMF/WMF conversion** — DOCX Windows Metafile images converted to PNG via Batik (EMF) or LibreOffice headless (WMF)
- **Multi-turn conversation** — thread-based history persistence (SQLite WAL, survives restarts)
- **Message bubble restore** — re-entering `/chat/{threadId}` server-renders all previous turn bubbles
- **Source hover preview** — `SourceRef` record with Bootstrap Popover shows a 200-char chunk text preview on hover
- **Code syntax highlighting** — highlight.js applied after DOMPurify sanitize, synced with dark mode
- **LLM usage dashboard** — per-provider daily/weekly/monthly token stats, Chart.js daily history chart, circuit breaker countdown; embedding usage tracked separately (`embed:<model>`, with an approximation fallback when the server omits usage); inactive providers with no history auto-hide, and orphaned records (removed from config) surface as admin-deletable cards
- **Document versioning** — per-version isolation (chroma: separate collection; sqlite-vec: `version` partition key)
- **Incremental indexing** — SHA-256 change detection, `doc_registry` SQLite table persistence (per-user)
- **Multiple document formats** — PDF, PPTX, DOCX, TXT, MD
- **Java 21 Virtual Threads** — lightweight threads for all LLM I/O and parallel indexing

## Endpoints

### Web UI

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/` | Chat home (creates a new thread) |
| `GET` | `/chat/{threadId}` | Resume an existing thread (restores previous message bubbles) |
| `GET` | `/documents` | Document management page |
| `GET` | `/llm-usage` | LLM usage statistics page |
| `GET/POST` | `/login` | Login page (auth mode only) |
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
