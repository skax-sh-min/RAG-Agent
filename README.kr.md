# RAG Agent — Spring AI / Java 21

Spring AI + Spring Boot 3.5 + Java 21 기반의 문서 기반 지식 Q&A 에이전트입니다.  
REST API와 Thymeleaf + HTMX 기반 Web UI를 모두 제공합니다.

## 실행 방법

### Docker Compose (권장)

```bash
cp .env.example .env   # 환경변수 설정
docker-compose up --build
```

### 로컬 빌드

```bash
# git 훅 설치 (클론 후 1회 실행)
sh scripts/install-hooks.sh

# 테스트 포함 빌드
mvn clean package

# 테스트 생략 빌드 (빠름)
mvn clean package -DskipTests

# Exploded 빌드 — fat JAR로 묶지 않고 계층화된 디렉터리로 추출
mvn clean package -DskipTests
java -Djarmode=tools -jar target/rag-agent-*.jar extract --destination target/extracted
```

빌드 완료 후 `target/rag-agent-*.jar` 파일이 생성됩니다.

> **Exploded 실행** — 위 `extract` 단계 완료 후, 풀어진 레이아웃에서 바로 실행합니다. JVM이 런타임에 JAR을 해제할 필요가 없어 기동이 빠릅니다:
> ```bash
> java -jar target/extracted/rag-agent-*.jar
> ```
> `--destination target/extracted`는 최초 1회만 실행하면 되며, 이후에는 `java -jar` 명령만 사용합니다.

### 로컬 실행

> **벡터 스토어 백엔드** — 기본은 ChromaDB. `VECTORSTORE_TYPE=sqlite-vec`로 설정하면 벡터를 SQLite 파일에 저장하고 아래 **"Chroma 서버" 단계를 생략**할 수 있습니다 (운영자가 제공하는 `vec0` 네이티브 확장 필요 — [OPERATOR_MANUAL.md](OPERATOR_MANUAL.md) 참조). 인터넷·Docker 없이 sqlite-vec + 로컬 llama-server로만 돌리는 폐쇄망 구성은 [OPERATOR_MANUAL.md §4.5](OPERATOR_MANUAL.md#45-폐쇄망air-gapped--노-도커-실행) 참조.

#### 개발 모드 (소스 직접 실행)

```bash
# 1. Chroma 서버 (별도 터미널)
docker run --rm -p 8001:8000 \
  -v "$(pwd)/data/chroma:/data" \
  chromadb/chroma:latest

# 2. 환경변수 설정
cp .env.example .env

# 3. 애플리케이션 실행
mvn spring-boot:run
```

#### JAR 실행 (빌드 후)

```bash
# 1. Chroma 서버 (별도 터미널)
docker run --rm -p 8001:8000 \
  -v "$(pwd)/data/chroma:/data" \
  chromadb/chroma:latest

# 2. 환경변수 로드 후 JAR 실행
export $(grep -v '^#' .env | xargs)
java -jar target/rag-agent-*.jar
```

#### macOS — Apple Container (Apple Silicon 대안)

```bash
# 0. 설치 (최초 1회): https://github.com/apple/container/releases 에서 .pkg 다운로드

# 1. 컨테이너 시스템 시작 (설치 후 또는 재부팅 후 1회)
container system start

# 2. Chroma 시작 (별도 터미널)
container run --rm -p 8001:8000 \
  -v "$(pwd)/data/chroma:/data" \
  chromadb/chroma:latest

# 3. 환경변수 로드 후 실행
export $(grep -v '^#' .env | xargs)
mvn spring-boot:run

# 종료
container stop <CONTAINER_ID>
container system stop
```

> 편의 스크립트 `scripts/macos_run_by_apple_container.sh`를 사용하면 위 단계를 자동으로 수행합니다.

접속: http://localhost:8080

자세한 사용법은 [USER_MANUAL.md](USER_MANUAL.md)를, 배포·LLM 설정은 [OPERATOR_MANUAL.md](OPERATOR_MANUAL.md)를 참고하세요.

## 환경 변수

### 연결 / 인증

| 변수 | 필수 | 기본값 | 설명 |
|------|------|--------|------|
| `SERVER_PORT` | — | `8080` | 애플리케이션이 리스닝할 포트. 다른 로컬 서비스와 충돌할 때만 변경 |
| `LOCAL_LLM_URL` | — | `http://localhost:1234/v1` | LOCAL provider 엔드포인트 (임베딩 폴백으로도 사용) |
| `LOCAL_LLM_KEY` | — | `lm-studio` | LOCAL provider API 키. **로컬 엔드포인트(llama-server)는 키 불필요** — 비워도 LOCAL provider는 등록됨(`no-key` 치환) |
| `LOCAL_LLM_MODEL` | — | `google/gemma-4-e4b` | LOCAL provider 모델명 |
| `LLM_ROUTING_MODE` | — | `COST_FIRST` | 기본 라우팅 모드 (`app.llm.default-routing-mode`). 폐쇄망/로컬 전용은 `LOCAL_ONLY`로 외부 프로바이더 호출 차단 |
| `OPENAI_API_KEY` | — | — | OpenAI providers 사용 시 필요. 미설정 시 해당 providers 자동 비활성화 |
| `GEMINI_API_KEY` | — | — | Gemini providers 사용 시 필요. 미설정 시 해당 providers 자동 비활성화 |
| `EMBED_BASE_URL` | — | `LOCAL_LLM_URL` | 임베딩 전용 엔드포인트. 미설정 시 `LOCAL_LLM_URL` 사용 |
| `EMBED_API_KEY` | — | `LOCAL_LLM_KEY` | 임베딩 API 키. 미설정 시 `LOCAL_LLM_KEY` 사용 |
| `EMBED_MODEL` | — | `text-embedding-nomic-embed-text-v1.5` | 임베딩 모델명 |
| `EMBED_DIMENSIONS` | sqlite-vec 시 | — | 임베딩 모델의 실제 출력 차원 (`app.embedding.dimensions`). `sqlite-vec` 필수 (vec0 DDL에 고정 — 모델 실제 차원과 일치: nomic=768, bge-m3=1024). chroma는 무시 |
| `EMBED_USAGE_FALLBACK_ENABLED` | — | `true` | 임베딩 서버가 토큰 사용량을 반환하지 않을 때 `/llm-usage` 대시보드에 0 대신 입력 텍스트 길이 근사(chars/4)로 기록 |
| `EMBED_MAX_CHUNK_CHARS` | — | `0` (비활성) | 임베딩 서버 배치/토큰 한계에 맞추는 청크 문자 수 하드 상한. `input (N tokens) is too large ... (batch size: 512)` 에러 시 설정(예: `450`). 초과 청크는 줄 경계에서 강제 재분할. 먼저 서버 배치를 키우는 것(`llama-server -b/-ub`)을 권장 — [OPERATOR_MANUAL §8](documents/OPERATOR_MANUAL.md#8-문제-해결) 참조 |
| `VECTORSTORE_TYPE` | — | `chroma` | 벡터 스토어 백엔드 — `chroma` 또는 `sqlite-vec` |
| `SQLITE_VEC_EXTENSION_PATH` | — | — | sqlite-vec 전용 — 운영자가 제공하는 `vec0` 로더블 확장 경로 |
| `CHROMA_HOST` | — | `http://localhost` | Chroma 서버 호스트 (chroma 백엔드) |
| `CHROMA_PORT` | — | `8001` | Chroma 서버 포트 (chroma 백엔드) |
| `DATA_DIR` | — | `./data` | 문서·레지스트리·SQLite DB 저장 경로 |

### RAG 튜닝

| 변수 | 기본값 | 권장 범위 | 설명 |
|------|--------|-----------|------|
| `CHUNK_SIZE` | `800` | 300 ~ 2000 | 문서 청크 크기 (문자 수) |
| `CHUNK_OVERLAP` | `100` | 0 ~ CHUNK_SIZE × 0.25 | 청크 경계 문맥 보완용 중복 문자 수 |
| `MIN_CHUNK_SIZE` | `100` | 50 ~ CHUNK_SIZE × 0.25 | 너무 작은 청크를 인접 청크와 병합할 최소 길이 기준 |
| `SEARCH_TOP_K` | `7` | 2 ~ 15 | 벡터 검색 반환 문서 수 |
| `SEARCH_SIMILARITY_THRESHOLD` | `0.0` | 0.0 ~ 0.75 | 청크 유지 최소 코사인 유사도 (`0.0`=전체 수용) |
| `SEARCH_MULTIQUERY_ENABLED` | `true` | true/false | 검색 전 질의 다중 확장 여부 |
| `SEARCH_MULTIQUERY_MIN_LENGTH` | `0` | 0 ~ 20 | 이 길이 미만 질의는 확장 생략 (`0`=항상 확장) |
| `SEARCH_HYBRID_ENABLED` | `false` | true/false | RRF에 BM25(FTS5) 키워드 축 추가 (재인덱싱 필요) |
| `SEARCH_RETRY_ESCALATE` | `true` | true/false | 재시도마다 후보 풀 확대 — `×(retryCount+1)`, 상한 `×3` |
| `SEARCH_RERANK_ENABLED` | `false` | true/false | RRF 후 LLM 리랭킹 단계 (턴당 LLM 1콜 추가) |
| `SEARCH_CANDIDATE_MULTIPLIER` | `3` | 2 ~ 5 | 리랭킹 후보 풀 크기 — `topK × N` |
| `SEARCH_RRF_KEYWORD_WEIGHT` | `1.0` | 0.5 ~ 3.0 | 가중 RRF(Phase 7-A) — BM25 키워드 축 가중치. 벡터 축(MultiQuery 1~3개)은 항상 `1/축개수`로 그룹 정규화되므로 `1.0`이 정규화된 벡터 그룹과 동일 비중. `SEARCH_HYBRID_ENABLED=false`면 무영향 |
| `SEARCH_RRF_K` | `60` | 20 ~ 100 | 가중 RRF(Phase 7-A) — RRF 순위융합 상수 k(원논문 기본값 60) |
| `SEARCH_QUERY_EMBED_CACHE_ENABLED` | `true` | true/false | 쿼리 임베딩 캐시(Phase 7-A) — 정규화된 질의 → 벡터를 Caffeine 인메모리 캐시에 저장해 반복·유사 질문의 임베딩 왕복을 생략. 캐시 히트 시 `embed:<model>` usage도 기록 안 됨 |
| `SEARCH_QUERY_EMBED_CACHE_MAX_SIZE` | `500` | 100 ~ 5000 | 쿼리 임베딩 캐시 최대 엔트리 수 |
| `SEARCH_QUERY_EMBED_CACHE_TTL_SECONDS` | `600` | 60 ~ 3600 | 쿼리 임베딩 캐시 TTL(초, write 기준 만료) |
| `MAX_RETRY_COUNT` | `2` | 0 ~ 4 | 증거 부족 시 재검색 최대 횟수 |

### 대화 메모리 / 요약 캐시

대화 이력 주입 길이는 `LLM_MAX_TOKENS × 0.75`(최소 1,000자)로 자동 계산됩니다. 원문 그대로 보내는 폴백 경로와 아래 요약 캐시 경로 모두 이 예산을 그대로 지키므로, 두 경로 사이를 오가도 LLM에 전달되는 컨텍스트 양은 항상 동일하게 유지됩니다.

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `MEMORY_FETCH_LIMIT_TURNS` | `50` | 폴백 경로에서 문자 예산 적용 전 조회할 최근 turn 상한 |
| `SUMMARY_MAX_CACHED_THREADS` | `3` | 요약 캐시(LRU)가 동시에 유지하는 최대 thread 수 |
| `SUMMARY_MAX_SUMMARY_CHARS` | `2000` | 생성된 요약 문자열의 상한 (초과 시 잘림) |
| `SUMMARY_RECENT_RAW_TURNS` | `2` | 요약 뒤에 원문 그대로 덧붙일 최근 turn 수 (이 turn들도 예산 안에서 최신 우선으로 채워짐) |
| `SUMMARY_PRECOMPUTE_TTL_SECONDS` | `15` | 동일 thread에 대한 중복 요약 사전계산(precompute) 억제 창(초) |

> 형식별 분할 전략 상세 → [USER_MANUAL.md §4.1](USER_MANUAL.md#41-형식별-청크-분할-전략)

로컬 LLM (LM Studio, Ollama 등) 사용 시 — `.env`만 설정하면 됩니다:
```env
LOCAL_LLM_URL=http://localhost:1234/v1
LOCAL_LLM_KEY=lm-studio
LOCAL_LLM_MODEL=google/gemma-4-e4b
EMBED_MODEL=text-embedding-nomic-embed-text-v1.5
```

## 구성

```
rag_java/
├── pom.xml                            # Spring Boot 3.5 + Spring AI 1.1.4
├── Dockerfile / docker-compose.yml
├── .env.example
├── scripts/
│   ├── install-hooks.sh               # 클론 후 1회 실행으로 git 훅 활성화
│   └── hooks/
│       └── pre-commit                 # .env 우발 커밋 방지
└── src/main/
    ├── java/com/example/ragagent/
    │   ├── agent/
    │   │   ├── AgentState.java        # 불변 record — 노드 간 파이프라인 상태
    │   │   └── AgentGraph.java        # 그래프 실행 엔진 (switch expression)
    │   ├── config/
    │   │   ├── AppProperties.java     # @ConfigurationProperties (LlmConfig 포함)
    │   │   └── WebConfig.java         # ChatClient 빈 + CORS + i18n (CookieLocaleResolver)
    │   ├── audit/
    │   │   └── AuditLogger.java                # 감사 이벤트 → Logback AUDIT_FILE appender
    │   ├── context/
    │   │   ├── ThreadContext.java              # 요청별 record (threadId, userId, locale)
    │   │   └── ThreadContextResolver.java      # HandlerMethodArgumentResolver
    │   ├── controller/
    │   │   ├── ChatController.java             # REST POST /api/v1/chat; HTMX /ui/chat, /ui/chat/stream, 스레드 관리
    │   │   ├── DocumentController.java         # REST /api/v1/documents, /api/v1/images; 비동기 업로드 (202+taskId)
    │   │   ├── OperationsController.java       # REST GET /api/v1/health, /api/v1/llm/usage; HTMX 스레드 목록 + LLM 카드
    │   │   ├── AdminController.java            # /admin, /admin/chunks; 문서 재인덱스
    │   │   ├── AuthController.java             # /login, /signup, /setup 페이지 컨트롤러; 회원가입 후 자동 로그인
    │   │   ├── GlobalExceptionHandler.java     # RFC 9457 ProblemDetail; 400/413 처리
    │   │   └── GlobalModelAdvice.java          # @ControllerAdvice; authEnabled 모델 속성 전체 뷰 주입
    │   ├── exception/                          # 도메인 예외 클래스
    │   ├── ingestion/
    │   │   ├── DocumentIndexer.java            # 핵심 인덱싱 로직; 3단계 동기화; DocRegistry SQLite
    │   │   ├── DocRegistry.java                # doc_registry SQLite 테이블 관리
    │   │   ├── VectorStoreFacade.java          # VectorStoreProvider 위임 (백엔드 불가지론)
    │   │   └── VectorStoreProvider.java        # chroma | sqlite-vec (app.vectorstore.type)
    │   ├── ratelimit/
    │   │   └── RateLimitFilter.java            # Bucket4j + Caffeine 유저별 토큰버킷; 429 + RAG-RATE-001
    │   ├── llm/
    │   │   ├── LlmRouter.java             # 멀티 프로바이더 라우팅: TaskType × RoutingMode
    │   │   ├── RoutingMode.java           # COST_FIRST|QUALITY_FIRST|PROGRESSIVE|DUAL|LOCAL_ONLY
    │   │   ├── CircuitBreaker.java        # LLM 프로바이더 인메모리 차단 관리 (Retry-After 지원)
    │   │   ├── TrackingEmbeddingModel.java  # EmbeddingModel 데코레이터 — 임베딩 토큰 사용량을 채팅과 분리 기록 (embed:<model>)
    │   │   └── CachingEmbeddingModel.java   # EmbeddingModel 데코레이터 — Caffeine 쿼리 임베딩 캐시(Phase 7-A), tracking 바깥쪽에 합성
    │   ├── model/                         # Java 21 record
    │   │   ├── MetaKey.java               # 벡터 스토어 메타데이터 키 상수
    │   │   └── ChatRequest/Response/SourceRef/DocumentInfo/SyncResult/ThreadMeta/ChatForm/LlmProviderReport/IndexingProgressEvent.java
    │   ├── security/
    │   │   ├── FileTypeDetector.java      # 매직바이트 검증 (PDF, DOCX/PPTX, TXT/MD)
    │   │   └── PromptInjectionGuard.java  # 입력 검증 + API 키 마스킹
    │   ├── repository/
    │   │   ├── MemoryRepository.java              # 대화 메모리 추상 인터페이스 (getTurns 포함)
    │   │   ├── SqliteMemoryRepository.java        # SQLite WAL 기반 구현
    │   │   ├── LlmUsageRepository.java            # LLM 토큰 사용량 SQLite 저장소
    │   │   └── ImageDescriptionRepository.java    # image_descriptions 테이블 CRUD (Vision 캐시)
    │   └── service/
    │       ├── AgentService.java              # 에이전트 파이프라인 진입점
    │       ├── StreamingAgentService.java     # SSE 스트리밍 파이프라인 오케스트레이터
    │       ├── GraphListener.java             # 노드/토큰/출처 이벤트 hook 인터페이스
    │       ├── ClassifierService.java         # 질문 유형 분류 노드
    │       ├── DirectAnswerService.java       # meta 질문 직접 응답 노드
    │       ├── RetrievalService.java          # 벡터 검색 노드 + LazyVision 보강
    │       ├── AnswerService.java             # 답변 생성 + 스트리밍 + 증거 충분성 검증
    │       ├── CriticService.java             # 근거 검증 노드
    │       ├── FinalizeService.java           # 대화 메모리 저장 노드
    │       ├── MemoryService.java             # 멀티턴 메모리 — SQLite 영속
    │       ├── RagService.java                # 문서 인덱싱 + 동기화 + 이미지 정리
    │       ├── AdminService.java              # Admin UI 데이터 (청크 조회/편집 + 벡터 스토어 상태) — chroma·sqlite-vec
    │       ├── IndexingProgressService.java   # 비동기 업로드/동기화 SSE 진행 이벤트 관리
    │       ├── MarkdownCorrectionService.java # LLM 마크다운 출력 후처리
    │       ├── DocumentLoaderService.java     # PDF/PPTX/DOCX/TXT/MD 로더; 스캔 PDF OCR
    │       ├── DocxToMarkdownConverter.java   # DOCX → Markdown + 인라인 이미지 추출
    │       ├── ImageExtractorService.java     # 이미지 추출 오케스트레이터 (PDF/PPTX/DOCX)
    │       ├── PdfImageExtractor.java         # PDFBox PDImageXObject 기반 PDF 이미지 추출
    │       ├── PptxImageExtractor.java        # POI XSLFPictureShape 기반 PPTX 이미지 추출
    │       ├── VisionDescriptionService.java  # 이미지 → 한국어 설명 (Vision LLM)
    │       ├── LazyVisionService.java         # 검색 시점 Vision 설명 생성 + SQLite 캐시
    │       ├── ImageTypeClassifier.java       # 이미지 유형 분류 → 전용 프롬프트 선택
    │       ├── OcrService.java                # Tesseract OCR — 스캔 PDF (kor+eng)
    │       ├── EmfToPngConverter.java         # Batik WMFTranscoder→SVG→PNGTranscoder 파이프라인
    │       ├── LibreOfficeConverter.java      # LibreOffice headless WMF→PNG (20s 타임아웃)
    │       ├── ThreadMetaService.java         # 대화 스레드 메타 관리
    │       └── VectorStoreRegistry.java       # 버전별 ChromaVectorStore 관리 (chroma 백엔드)
    └── resources/
        ├── application.properties
        ├── messages.properties            # UI 문자열 — English (기본)
        ├── messages_ko.properties         # UI 문자열 — 한국어
        ├── static/
        │   ├── css/
        │   │   ├── app.css                # 커스텀 스타일 (버블·애니메이션·반응형 오프캔버스/dvh/16px/44px)
        │   │   └── theme.css              # 라이트/다크 CSS 변수 + Bootstrap 다크 모드 오버라이드
        │   ├── manifest.webmanifest       # PWA 매니페스트 (이름·아이콘·standalone)
        │   ├── sw.js                      # 서비스 워커 (NETWORK-FIRST, 오프라인 fallback 전용)
        │   ├── offline.html               # 오프라인 fallback 페이지 (자체 완결 정적 HTML)
        │   ├── icons/icon.svg             # 앱 아이콘 (SVG, any maskable)
        │   └── js/
        │       └── chat-stream.js         # SSE 스트리밍 클라이언트 (fetch + ReadableStream)
        └── templates/
            ├── layout/base.html           # 공통 레이아웃 (Thymeleaf Layout Dialect; PWA meta + SW 등록)
            ├── chat.html                  # 채팅 페이지 (이전 turn 서버 렌더 포함)
            ├── documents.html             # 문서 관리 페이지
            ├── llm-usage.html             # LLM 사용량 통계 페이지
            └── fragments/
                ├── llm-usage-cards.html   # 프로바이더 카드 (HTMX 30초 자동 갱신)
                ├── thread-list.html       # HTMX 스레드 목록 fragment
                ├── thread-item.html       # HTMX 스레드 아이템 fragment
                ├── doc-row.html           # HTMX 문서 테이블 행 fragment
                ├── doc-table-body.html    # HTMX 문서 테이블 tbody fragment
                ├── message-user.html      # 사용자 메시지 버블 fragment
                ├── message-assistant.html # HTMX 답변 버블 (출처 hover preview 포함)
                └── message-error.html     # HTMX 에러 버블 fragment
```

## 에이전트 파이프라인

```
질문 입력
  └─▶ [Classifier]  → 질문 유형 분류 (concept / usage / error / version / meta)
        ├─ meta  ──▶ [DirectAnswer] → [Finalize] → 응답
        └─ other ──▶ [Retrieval]   (LLM 최적 쿼리 생성 → 벡터 검색)
                       └─▶ [Answer]   (구조화 답변 + sufficient 자기평가)
                              ├─ 증거 부족 ──▶ [Retrieval] (최대 2회)
                              └─ 충분    ──▶ [Critic]   (근거 검증)
                                              ├─ 미근거  ──▶ [Retrieval]
                                              └─ 근거 OK ──▶ [Finalize] → 응답
```

## 주요 기능

- **인증** — Spring Security 폼 로그인, BCrypt(12) 비밀번호 해싱, 5회 실패 시 15분 계정 잠금, `/login`·`/signup`·`/setup`. `app.auth.enabled=false`로 로컬 no-login 배포 가능
- **Web UI** — Thymeleaf + HTMX 기반 채팅·문서 관리·LLM 사용량 화면, KO/EN 언어 전환
- **SSE 실시간 스트리밍** — 노드별 단계 배지(classifier→retrieval→answer→critic) + 토큰 실시간 표시; DUAL 모드는 두 탭 동시 스트리밍 (`chat-stream.js`, fetch + ReadableStream)
- **다크 모드** — CSS 변수 기반 라이트/다크 전환, `prefers-color-scheme` 자동 감지 + `localStorage` 사용자 override
- **모바일 & PWA** — 반응형 오프캔버스 대화 드로어, `100dvh` 하단 고정 입력창, `table-responsive` 가로 넘침 처리, iOS 16px 자동 확대 방지; 설치형 PWA(`manifest.webmanifest`, 인증/RAG/SSE 응답을 캐시하지 않는 오프라인 fallback 서비스 워커, iOS "홈 화면에 추가" 힌트); 아이콘 버튼 i18n `aria-label`·44px 터치 영역·`:focus-visible` 표시
- **질문 분류 + 라우팅** — meta(인사·잡담)는 RAG 없이 직접 응답, 나머지는 풀 파이프라인
- **멀티 LLM 라우팅** — `LlmRouter`가 `TaskType × RoutingMode` 기준으로 프로바이더 선택: COST_FIRST / QUALITY_FIRST / PROGRESSIVE / DUAL (로컬+외부 병렬) / LOCAL_ONLY
- **Circuit Breaker** — HTTP 429/오류 시 프로바이더 자동 차단 (Retry-After 지원), 우선순위 기반 failover; LLM 사용량 대시보드에서 차단 상태 확인
- **벡터 검색** — `MultiQueryExpander`(3쿼리 병렬)로 최적 검색 후 선택된 백엔드(ChromaDB 또는 sqlite-vec)로 유사도 검색
- **ReAct 재검색** — 증거 부족 시 최대 2회 자동 재검색
- **Critic 검증** — 생성된 답변이 문서에 근거하는지 LLM이 이중 검증
- **PROGRESSIVE 모드** — COST_FIRST로 시작 → 품질 임계값 미달 시 PREMIUM 프로바이더로 재실행 + 업그레이드 배지 표시
- **DUAL 모드** — 로컬·외부 LLM 병렬 실행, 두 답변을 탭으로 비교
- **속도 제한** — Bucket4j + Caffeine 유저별 토큰버킷; 429 `RAG-RATE-001` + `Retry-After` 헤더; `app.rate-limit.*`로 설정
- **감사 로그** — Logback 롤링 파일에 구조화된 이벤트 기록; `app.audit.*`로 설정
- **이미지 처리 파이프라인** — PDF/PPTX/DOCX 이미지 추출 → `data/images/{docId}/` 저장; 검색 시점 Lazy Vision 설명 생성 (SQLite 캐시); 답변 버블에 이미지 썸네일 표시
- **이미지 유형 분류** — diagram / screenshot / chart / photo / other 분류 후 유형별 전용 Vision 프롬프트 적용
- **스캔 PDF OCR** — Tesseract OCR (kor+eng)로 텍스트 없는 페이지 처리 (`app.image-description.ocr-enabled=true`)
- **EMF/WMF 변환** — DOCX Windows Metafile 이미지를 Batik(EMF) 또는 LibreOffice headless(WMF)로 PNG 변환
- **멀티턴 대화** — `thread_id` 기반 대화 이력 유지 (SQLite WAL, 재시작 후에도 영속)
- **메시지 버블 복원** — `/chat/{threadId}` 재진입 시 이전 turn 메시지 버블 서버 렌더링
- **출처 hover 미리보기** — `SourceRef` 구조체 기반 Bootstrap Popover, 출처 hover 시 청크 텍스트 200자 미리보기
- **코드 syntax highlight** — DOMPurify sanitize 후 highlight.js 적용, 다크 모드 연동
- **LLM 사용량 대시보드** — 프로바이더별 일간·주간·월간 토큰 사용량, Chart.js 일별 히스토리 차트, Circuit Breaker 카운트다운; 임베딩 사용량은 채팅과 분리 집계(`embed:<model>`, usage 미반환 서버는 근사치 폴백); 사용 이력 없는 비활성 프로바이더는 자동 숨김, 설정에서 제거된 orphan 기록은 관리자가 카드에서 삭제 가능
- **문서 버전 관리** — 버전별 격리 (chroma: 컬렉션 분리 / sqlite-vec: `version` partition key)
- **증분 인덱싱** — SHA-256 기반 변경 감지, `doc_registry` SQLite 테이블 영속 (유저별)
- **다양한 문서 형식** — PDF, PPTX, DOCX, TXT, MD
- **Java 21 Virtual Threads** — LLM I/O 및 병렬 인덱싱 전체에 경량 스레드 적용

## 엔드포인트

### Web UI

| Method | Path | 설명 |
|--------|------|------|
| `GET` | `/` | 채팅 홈 (새 스레드 생성) |
| `GET` | `/chat/{threadId}` | 기존 스레드 채팅 화면 (이전 메시지 버블 복원) |
| `GET` | `/documents` | 문서 관리 화면 |
| `GET` | `/llm-usage` | LLM 사용량 통계 페이지 |

### REST API

| Method | Path | 설명 |
|--------|------|------|
| `GET` | `/api/v1/health` | 헬스 체크 |
| `POST` | `/api/v1/chat` | 질문 → 답변 |
| `POST` | `/api/v1/documents` | 문서 업로드 + 인덱싱 |
| `POST` | `/api/v1/documents/sync` | 증분 동기화 |
| `GET` | `/api/v1/documents` | 인덱싱된 문서 목록 |
| `DELETE` | `/api/v1/documents/{docId}` | 문서 삭제 |
| `GET` | `/api/v1/images/{docId}/{filename}` | 추출된 이미지 파일 서빙 |
| `GET` | `/api/v1/llm/usage` | 프로바이더별 토큰 사용량 + Circuit Breaker 상태 |
| `GET` | `/api/v1/llm/usage/history` | 일별 토큰 히스토리 (`?days=7\|30\|90`) |
