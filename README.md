# RAG Agent — Spring AI / Java 21

Spring AI + Spring Boot 3.3 + Java 21 기반의 문서 기반 지식 Q&A 에이전트입니다.

## 구성

```
rag_java/
├── pom.xml                            # Spring Boot 3.3 + Spring AI 1.0.0
├── Dockerfile / docker-compose.yml
├── .env.example
└── src/main/java/com/example/ragagent/
    ├── agent/
    │   ├── AgentState.java            # 불변 record — 노드 간 파이프라인 상태
    │   └── AgentGraph.java            # 그래프 실행 엔진 (switch expression)
    ├── config/
    │   ├── AppProperties.java         # @ConfigurationProperties
    │   └── WebConfig.java             # ChatClient 빈 + CORS
    ├── controller/
    │   └── ApiController.java         # REST API
    ├── model/                         # Java 21 record
    │   └── ChatRequest/Response/DocumentInfo/SyncResult.java
    ├── repository/
    │   ├── MemoryRepository.java      # 대화 메모리 추상 인터페이스
    │   └── SqliteMemoryRepository.java # SQLite WAL 기반 구현 (Redis 교체 가능)
    └── service/
        ├── AgentService.java          # 에이전트 파이프라인 진입점
        ├── ClassifierService.java     # 질문 유형 분류 노드
        ├── DirectAnswerService.java   # meta 질문 직접 응답 노드
        ├── RetrievalService.java      # 벡터 검색 노드
        ├── AnswerService.java         # 답변 생성(Call 1) + 증거 충분성 검증(Call 2)
        ├── CriticService.java         # 근거 검증 노드
        ├── FinalizeService.java       # 대화 메모리 저장 노드
        ├── MemoryService.java         # 멀티턴 메모리 — SQLite 영속
        ├── RagService.java            # 문서 인덱싱 + 검색
        ├── DocumentLoaderService.java # PDF/PPTX/DOCX/TXT/MD 로더
        └── VectorStoreRegistry.java   # 버전별 ChromaVectorStore 관리
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

- **질문 분류 + 라우팅** — meta(인사·잡담)는 RAG 없이 직접 응답, 나머지는 풀 파이프라인
- **벡터 검색** — LLM이 최적 검색 쿼리를 생성한 뒤 Chroma 유사도 검색
- **ReAct 재검색** — 증거 부족 시 최대 2회 자동 재검색
- **Critic 검증** — 생성된 답변이 문서에 근거하는지 LLM이 이중 검증
- **멀티턴 대화** — `thread_id` 기반 대화 이력 유지 (SQLite WAL, 재시작 후에도 영속)
- **문서 버전 관리** — 버전별 Chroma 컬렉션 분리 (`manual_{version}`)
- **증분 인덱싱** — SHA-256 기반 변경 감지, `doc_registry.json` 영속
- **다양한 문서 형식** — PDF, PPTX, DOCX, TXT, MD
- **Java 21 Virtual Threads** — LLM I/O 요청에 경량 스레드 적용

## API 엔드포인트

| Method | Path | 설명 |
|--------|------|------|
| `GET` | `/api/health` | 헬스 체크 |
| `POST` | `/api/chat` | 질문 → 답변 |
| `POST` | `/api/documents` | 문서 업로드 + 인덱싱 |
| `POST` | `/api/documents/sync` | 증분 동기화 |
| `GET` | `/api/documents` | 인덱싱된 문서 목록 |
| `DELETE` | `/api/documents/{docId}` | 문서 삭제 |

## 실행 방법

### Docker Compose (권장)

```bash
cp .env.example .env   # 환경변수 설정
docker-compose up --build
```

### 로컬 실행

```bash
# 1. Chroma 서버
docker run --rm -p 8001:8000 chromadb/chroma:latest

# 2. 환경변수 설정
cp .env.example .env

# 3. 애플리케이션 실행
mvn spring-boot:run
```

접속: http://localhost:8080/api/health

자세한 사용법은 [USER_MANUAL.md](USER_MANUAL.md)를 참고하세요.

## 환경 변수

| 변수 | 필수 | 기본값 | 설명 |
|------|------|--------|------|
| `OPENAI_API_KEY` | ✅ | — | OpenAI 또는 로컬 LLM API 키 |
| `OPENAI_BASE_URL` | ✅ | `https://api.openai.com` | OpenAI 호환 엔드포인트 URL |
| `LLM_MODEL` | — | `gpt-4o` | 채팅 모델명 |
| `EMBED_MODEL` | — | `text-embedding-ada-002` | 임베딩 모델명 |
| `CHROMA_HOST` | — | `localhost` | Chroma 서버 호스트 |
| `CHROMA_PORT` | — | `8001` | Chroma 서버 포트 |
| `DATA_DIR` | — | `./data` | 문서·레지스트리·SQLite DB 저장 경로 |

로컬 LLM (LM Studio, Ollama 등) 사용 시:
```env
OPENAI_BASE_URL=http://localhost:1234/v1
OPENAI_API_KEY=lm-studio
LLM_MODEL=google/gemma-3-27b-it
EMBED_MODEL=text-embedding-nomic-embed-text-v1.5
```
