# RAG Agent — Spring AI / Java 21

A document-based knowledge Q&A agent built on Spring AI + Spring Boot 3.3 + Java 21.  
Provides both a REST API and a Web UI powered by Thymeleaf + HTMX.

## Getting Started

### Docker Compose (recommended)

```bash
cp .env.example .env   # configure environment variables
docker-compose up --build
```

### Local Build

```bash
# Build with tests
mvn clean package

# Build without tests (faster)
mvn clean package -DskipTests
```

The built JAR is generated at `target/rag-agent-*.jar`.

### Local Run

#### Development mode (run from source)

```bash
# 1. Start Chroma (separate terminal)
docker run --rm -p 8001:8000 \
  -v "$(pwd)/data/chroma:/chroma/chroma" \
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
  -v "$(pwd)/data/chroma:/chroma/chroma" \
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
  -v "$(pwd)/data/chroma:/chroma/chroma" \
  chromadb/chroma:latest

# 3. Load env vars and run
export $(grep -v '^#' .env | xargs)
mvn spring-boot:run

# Shutdown
container stop <CONTAINER_ID>
container system stop
```

Open: http://localhost:8080

See [USER_MANUAL.md](USER_MANUAL.md) for detailed usage instructions.

## Environment Variables

### Connection / Authentication

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `OPENAI_API_KEY` | ✅ | — | OpenAI or local LLM API key |
| `OPENAI_BASE_URL` | — | `https://api.openai.com` | OpenAI-compatible endpoint URL |
| `LLM_MODEL` | — | `gpt-4o` | Chat model name |
| `EMBED_MODEL` | — | `text-embedding-ada-002` | Embedding model name |
| `CHROMA_HOST` | — | `http://localhost` | Chroma server host (include protocol) |
| `CHROMA_PORT` | — | `8001` | Chroma server port |
| `DATA_DIR` | — | `./data` | Storage path for documents, registry, and SQLite DB |

### RAG Tuning

| Variable | Default | Recommended Range | Description |
|----------|---------|-------------------|-------------|
| `CHUNK_SIZE` | `800` | 300 ~ 2000 | Document chunk size (characters) |
| `CHUNK_OVERLAP` | `100` | 0 ~ CHUNK_SIZE × 0.25 | Overlap between chunks (characters) |
| `SEARCH_TOP_K` | `6` | 2 ~ 15 | Number of documents returned by vector search |
| `MAX_RETRY_COUNT` | `2` | 0 ~ 4 | Maximum re-retrieval attempts when evidence is insufficient |
| `MAX_CONVERSATION_CHARS` | `7000` | 1000 ~ 20000 | Maximum characters of conversation history injected as context |

> Per-format splitting strategy → [USER_MANUAL.md §7.1](USER_MANUAL.md#71-형식별-청크-분할-전략)

Local LLM (LM Studio, Ollama, etc.):
```env
OPENAI_BASE_URL=http://localhost:1234/v1
OPENAI_API_KEY=lm-studio
LLM_MODEL=google/gemma-4-e4b
EMBED_MODEL=text-embedding-nomic-embed-text-v1.5
```

## Project Structure

```
rag_java/
├── pom.xml                            # Spring Boot 3.3 + Spring AI 1.0.0
├── Dockerfile / docker-compose.yml
├── .env.example
└── src/main/
    ├── java/com/example/ragagent/
    │   ├── agent/
    │   │   ├── AgentState.java        # Immutable record — inter-node pipeline state
    │   │   └── AgentGraph.java        # Graph execution engine (switch expression)
    │   ├── config/
    │   │   ├── AppProperties.java     # @ConfigurationProperties
    │   │   └── WebConfig.java         # ChatClient bean + CORS + i18n (CookieLocaleResolver)
    │   ├── controller/
    │   │   ├── ApiController.java     # REST API (/api/*)
    │   │   └── WebController.java     # Web UI HTMX handler (/ui/*, /chat/*, /documents)
    │   ├── model/                     # Java 21 records
    │   │   └── ChatRequest/Response/DocumentInfo/SyncResult/ThreadMeta/ChatForm.java
    │   ├── repository/
    │   │   ├── MemoryRepository.java          # Conversation memory interface
    │   │   └── SqliteMemoryRepository.java    # SQLite WAL-based implementation
    │   └── service/
    │       ├── AgentService.java          # Agent pipeline entry point
    │       ├── ClassifierService.java     # Question type classification node
    │       ├── DirectAnswerService.java   # Direct response node for meta questions
    │       ├── RetrievalService.java      # Vector search node
    │       ├── AnswerService.java         # Answer generation + evidence sufficiency check
    │       ├── CriticService.java         # Evidence verification node
    │       ├── FinalizeService.java       # Conversation memory save node
    │       ├── MemoryService.java         # Multi-turn memory — SQLite persistence
    │       ├── RagService.java            # Document indexing + search
    │       ├── DocumentLoaderService.java # PDF/PPTX/DOCX/TXT/MD loader
    │       ├── ThreadMetaService.java     # Conversation thread metadata management
    │       └── VectorStoreRegistry.java   # Per-version ChromaVectorStore management
    └── resources/
        ├── application.properties
        ├── messages.properties            # UI strings — English (default)
        ├── messages_ko.properties         # UI strings — Korean
        ├── static/css/app.css
        └── templates/
            ├── layout/base.html           # Shared layout (Thymeleaf Layout Dialect)
            ├── chat.html                  # Chat page
            ├── documents.html             # Document management page
            └── fragments/
                ├── thread-list.html       # HTMX thread list fragment
                ├── thread-item.html       # HTMX thread item fragment
                ├── doc-table-body.html    # HTMX document table fragment
                ├── message-assistant.html # HTMX assistant bubble fragment
                ├── message-error.html     # HTMX error bubble fragment
                └── sync-result.html       # HTMX sync result toast fragment
```

## Agent Pipeline

```
User question
  └─▶ [Classifier]  → Classify question type (concept / usage / error / version / meta)
        ├─ meta  ──▶ [DirectAnswer] → [Finalize] → Response
        └─ other ──▶ [Retrieval]   (LLM generates optimal query → Chroma search)
                       └─▶ [Answer]   (Structured answer + sufficient self-evaluation)
                              ├─ Insufficient evidence ──▶ [Retrieval] (up to 2 retries)
                              └─ Sufficient           ──▶ [Critic]   (evidence verification)
                                                              ├─ Ungrounded ──▶ [Retrieval]
                                                              └─ Grounded   ──▶ [Finalize] → Response
```

## Features

- **Web UI** — Thymeleaf + HTMX chat and document management interface with KO/EN language switcher
- **Question classification + routing** — meta (greetings/small talk) answered directly without RAG; all others go through the full pipeline
- **Vector search** — LLM generates an optimized search query, then performs Chroma similarity search
- **ReAct re-retrieval** — automatic re-retrieval up to 2 times when evidence is insufficient
- **Critic verification** — LLM double-checks whether the generated answer is grounded in documents
- **Multi-turn conversation** — thread-based history persistence (SQLite WAL, survives restarts)
- **Document versioning** — separate Chroma collection per version (`manual_{version}`)
- **Incremental indexing** — SHA-256 change detection, `doc_registry.json` persistence
- **Multiple document formats** — PDF, PPTX, DOCX, TXT, MD
- **Java 21 Virtual Threads** — lightweight threads for LLM I/O requests

## Endpoints

### Web UI

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/` | Chat home (creates a new thread) |
| `GET` | `/chat/{threadId}` | Resume an existing thread |
| `GET` | `/documents` | Document management page |

### REST API

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/health` | Health check |
| `POST` | `/api/chat` | Ask a question → get an answer |
| `POST` | `/api/documents` | Upload and index a document |
| `POST` | `/api/documents/sync` | Incremental folder sync |
| `GET` | `/api/documents` | List indexed documents |
| `DELETE` | `/api/documents/{docId}` | Delete a document |
