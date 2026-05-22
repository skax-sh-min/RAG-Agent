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
```

빌드 완료 후 `target/rag-agent-*.jar` 파일이 생성됩니다.

### 로컬 실행

#### 개발 모드 (소스 직접 실행)

```bash
# 1. Chroma 서버 (별도 터미널)
docker run --rm -p 8001:8000 \
  -v "$(pwd)/data/chroma:/chroma/chroma" \
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
  -v "$(pwd)/data/chroma:/chroma/chroma" \
  chromadb/chroma:latest

# 2. 환경변수 로드 후 JAR 실행
export $(grep -v '^#' .env | xargs)
java -jar target/rag-agent-*.jar
```

접속: http://localhost:8080

자세한 사용법은 [USER_MANUAL.md](USER_MANUAL.md)를, 배포·LLM 설정은 [OPERATOR_MANUAL.md](OPERATOR_MANUAL.md)를 참고하세요.

## 환경 변수

### 연결 / 인증

| 변수 | 필수 | 기본값 | 설명 |
|------|------|--------|------|
| `LOCAL_LLM_URL` | — | `http://localhost:1234/v1` | LOCAL provider 엔드포인트 (임베딩 폴백으로도 사용) |
| `LOCAL_LLM_KEY` | — | `lm-studio` | LOCAL provider API 키. 비우면 LOCAL 비활성화 |
| `LOCAL_LLM_MODEL` | — | `google/gemma-4-e4b` | LOCAL provider 모델명 |
| `OPENAI_API_KEY` | — | — | OpenAI providers 사용 시 필요. 미설정 시 해당 providers 자동 비활성화 |
| `GEMINI_API_KEY` | — | — | Gemini providers 사용 시 필요. 미설정 시 해당 providers 자동 비활성화 |
| `EMBED_BASE_URL` | — | `LOCAL_LLM_URL` | 임베딩 전용 엔드포인트. 미설정 시 `LOCAL_LLM_URL` 사용 |
| `EMBED_API_KEY` | — | `LOCAL_LLM_KEY` | 임베딩 API 키. 미설정 시 `LOCAL_LLM_KEY` 사용 |
| `EMBED_MODEL` | — | `text-embedding-nomic-embed-text-v1.5` | 임베딩 모델명 |
| `CHROMA_HOST` | — | `http://localhost` | Chroma 서버 호스트 (프로토콜 포함) |
| `CHROMA_PORT` | — | `8001` | Chroma 서버 포트 |
| `DATA_DIR` | — | `./data` | 문서·레지스트리·SQLite DB 저장 경로 |

### RAG 튜닝

| 변수 | 기본값 | 권장 범위 | 설명 |
|------|--------|-----------|------|
| `CHUNK_SIZE` | `800` | 300 ~ 2000 | 문서 청크 크기 (문자 수) |
| `CHUNK_OVERLAP` | `100` | 0 ~ CHUNK_SIZE × 0.25 | 청크 간 중복 문자 수 |
| `SEARCH_TOP_K` | `7` | 2 ~ 15 | 벡터 검색 반환 문서 수 |
| `MAX_RETRY_COUNT` | `2` | 0 ~ 4 | 증거 부족 시 재검색 최대 횟수 |
| `MAX_CONVERSATION_CHARS` | `8000` | 1000 ~ 20000 | 멀티턴 대화 이력 최대 문자 수 |

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
    │   │   └── GlobalExceptionHandler.java     # RFC 9457 ProblemDetail; 400/413 처리
    │   ├── exception/                          # 도메인 예외 클래스
    │   ├── ingestion/
    │   │   ├── DocumentIndexer.java            # 핵심 인덱싱 로직; 3단계 동기화; DocRegistry SQLite
    │   │   ├── DocRegistry.java                # doc_registry SQLite 테이블 관리
    │   │   └── VectorStoreFacade.java          # ChromaVectorStore 추상화
    │   ├── ratelimit/
    │   │   └── RateLimitFilter.java            # Bucket4j + Caffeine 유저별 토큰버킷; 429 + RAG-RATE-001
    │   ├── llm/
    │   │   ├── LlmRouter.java             # 멀티 프로바이더 라우팅: TaskType × RoutingMode
    │   │   ├── RoutingMode.java           # COST_FIRST|QUALITY_FIRST|PROGRESSIVE|DUAL|LOCAL_ONLY
    │   │   └── CircuitBreaker.java        # LLM 프로바이더 인메모리 차단 관리 (Retry-After 지원)
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
    │       ├── AdminService.java              # Admin UI 데이터 조회 (청크, 컬렉션 통계)
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
    │       └── VectorStoreRegistry.java       # 버전별 ChromaVectorStore 관리
    └── resources/
        ├── application.properties
        ├── messages.properties            # UI 문자열 — English (기본)
        ├── messages_ko.properties         # UI 문자열 — 한국어
        ├── static/
        │   ├── css/
        │   │   ├── app.css                # 커스텀 스타일 (버블·애니메이션·업로드 진행바 등)
        │   │   └── theme.css              # 라이트/다크 CSS 변수 + Bootstrap 다크 모드 오버라이드
        │   └── js/
        │       └── chat-stream.js         # SSE 스트리밍 클라이언트 (fetch + ReadableStream)
        └── templates/
            ├── layout/base.html           # 공통 레이아웃 (Thymeleaf Layout Dialect)
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
                ├── message-error.html     # HTMX 에러 버블 fragment
                └── sync-result.html       # HTMX 동기화 결과 toast fragment
```

## 에이전트 파이프라인

```
질문 입력
  └─▶ [Classifier]  → 질문 유형 분류 (concept / usage / error / version / meta)
        ├─ meta  ──▶ [DirectAnswer] → [Finalize] → 응답
        └─ other ──▶ [Retrieval]   (LLM 최적 쿼리 생성 → Chroma 검색)
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
- **질문 분류 + 라우팅** — meta(인사·잡담)는 RAG 없이 직접 응답, 나머지는 풀 파이프라인
- **멀티 LLM 라우팅** — `LlmRouter`가 `TaskType × RoutingMode` 기준으로 프로바이더 선택: COST_FIRST / QUALITY_FIRST / PROGRESSIVE / DUAL (로컬+외부 병렬) / LOCAL_ONLY
- **Circuit Breaker** — HTTP 429/오류 시 프로바이더 자동 차단 (Retry-After 지원), 우선순위 기반 failover; LLM 사용량 대시보드에서 차단 상태 확인
- **벡터 검색** — `MultiQueryExpander`(3쿼리 병렬)로 최적 검색 후 Chroma 유사도 검색
- **ReAct 재검색** — 증거 부족 시 최대 2회 자동 재검색
- **Critic 검증** — 생성된 답변이 문서에 근거하는지 LLM이 이중 검증
- **PROGRESSIVE 모드** — COST_FIRST로 시작 → 품질 임계값 미달 시 PREMIUM 프로바이더로 재실행 + 업그레이드 배지 표시
- **DUAL 모드** — 로컬·외부 LLM 병렬 실행, 두 답변을 탭으로 비교
- **속도 제한** — Bucket4j + Caffeine 유저별 토큰버킷; 429 `RAG-RATE-001` + `Retry-After` 헤더; `app.rate-limit.*`로 설정
- **감사 로그** — Logback 롤링 파일에 구조화된 이벤트 기록; `app.audit.*`로 설정
- **이미지 처리 파이프라인** — PDF/PPTX/DOCX 이미지 추출 → `data/users/{userId}/images/{docId}/` 저장; 검색 시점 Lazy Vision 설명 생성 (SQLite 캐시); 답변 버블에 이미지 썸네일 표시
- **이미지 유형 분류** — diagram / screenshot / chart / photo / other 분류 후 유형별 전용 Vision 프롬프트 적용
- **스캔 PDF OCR** — Tesseract OCR (kor+eng)로 텍스트 없는 페이지 처리 (`app.image-description.ocr-enabled=true`)
- **EMF/WMF 변환** — DOCX Windows Metafile 이미지를 Batik(EMF) 또는 LibreOffice headless(WMF)로 PNG 변환
- **멀티턴 대화** — `thread_id` 기반 대화 이력 유지 (SQLite WAL, 재시작 후에도 영속)
- **메시지 버블 복원** — `/chat/{threadId}` 재진입 시 이전 turn 메시지 버블 서버 렌더링
- **출처 hover 미리보기** — `SourceRef` 구조체 기반 Bootstrap Popover, 출처 hover 시 청크 텍스트 200자 미리보기
- **코드 syntax highlight** — DOMPurify sanitize 후 highlight.js 적용, 다크 모드 연동
- **LLM 사용량 대시보드** — 프로바이더별 일간·주간·월간 토큰 사용량, Chart.js 일별 히스토리 차트, Circuit Breaker 카운트다운
- **문서 버전 관리** — 버전별 Chroma 컬렉션 분리 (`manual_{version}`)
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
