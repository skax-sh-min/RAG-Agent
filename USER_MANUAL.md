# USER MANUAL

Framework Manual Q&A Agent (Spring AI / Java 21) 사용자 매뉴얼입니다.

## 1. 개요

이 프로젝트는 문서 기반 질의응답(RAG) REST API 서비스입니다.  
클라이언트가 `/api/chat`으로 질문을 보내면, 서버가 업로드된 문서를 검색해 답변과 출처를 반환합니다.

핵심 기능:
- **질문 유형 분류** — concept / usage / error / version / meta 5종 자동 분류
- **벡터 검색** — LLM이 최적 쿼리를 생성해 Chroma에서 유사 문서 검색
- **ReAct 재검색** — 증거 부족 시 자동 재검색 (최대 2회)
- **Critic 검증** — 답변이 문서에 근거하는지 이중 검증
- **멀티턴 대화** — `thread_id`로 대화 이력 유지
- **증분 인덱싱** — SHA-256 기반 변경 감지, `doc_registry.json` 영속
- **문서 버전 관리** — 버전별 독립된 Chroma 컬렉션

---

## 2. 폴더 구조

```
rag_java/
├── pom.xml                  # Maven 빌드 설정
├── Dockerfile
├── docker-compose.yml
├── .env.example             # 환경변수 템플릿
├── README.md
├── USER_MANUAL.md
├── data/                    # 런타임 생성 (DATA_DIR)
│   ├── documents/           # 업로드된 문서 원본
│   └── doc_registry.json    # 인덱싱 레지스트리
└── src/
    └── main/
        ├── java/…           # 애플리케이션 소스
        └── resources/
            └── application.properties
```

---

## 3. 사전 준비

### 3.1 필수 소프트웨어

| 소프트웨어 | 버전 | 역할 |
|-----------|------|------|
| Java JDK | 21 이상 | 애플리케이션 실행 |
| Maven | 3.9 이상 | 빌드 |
| Docker | 20 이상 | Chroma 벡터 DB 실행 |

### 3.2 환경변수 설정

`.env.example`을 복사해 `.env`를 만들고 값을 채웁니다.

```bash
cp .env.example .env
```

| 변수 | 필수 | 예시 | 설명 |
|------|------|------|------|
| `OPENAI_API_KEY` | ✅ | `sk-...` 또는 `lm-studio` | API 키 |
| `OPENAI_BASE_URL` | ✅ | `http://localhost:1234/v1` | OpenAI 호환 엔드포인트 |
| `LLM_MODEL` | — | `gpt-4o` | 채팅 LLM 모델명 |
| `EMBED_MODEL` | — | `text-embedding-ada-002` | 임베딩 모델명 |
| `CHROMA_HOST` | — | `localhost` | Chroma 호스트 |
| `CHROMA_PORT` | — | `8001` | Chroma 포트 |
| `DATA_DIR` | — | `./data` | 문서·레지스트리 저장 경로 |

**로컬 LLM 예시 (LM Studio)**:
```env
OPENAI_BASE_URL=http://localhost:1234/v1
OPENAI_API_KEY=lm-studio
LLM_MODEL=google/gemma-3-27b-it
EMBED_MODEL=text-embedding-nomic-embed-text-v1.5
CHROMA_HOST=localhost
CHROMA_PORT=8001
```

**표준 OpenAI 예시**:
```env
OPENAI_BASE_URL=https://api.openai.com
OPENAI_API_KEY=sk-proj-...
LLM_MODEL=gpt-4o
EMBED_MODEL=text-embedding-3-large
CHROMA_HOST=localhost
CHROMA_PORT=8001
```

---

## 4. 실행 방법

### 4.1 Docker Compose (권장)

모든 서비스(Chroma + 애플리케이션)를 한 번에 실행합니다.

```bash
# 1. 환경변수 설정
cp .env.example .env
# .env 파일을 에디터로 열어 값 입력

# 2. 빌드 및 실행
docker-compose up --build -d

# 3. 상태 확인
docker-compose ps

# 4. 로그 확인
docker-compose logs -f app

# 5. 종료
docker-compose down
```

> **주의**: `docker-compose.yml`에서 `CHROMA_HOST=chroma`, `CHROMA_PORT=8000`으로 자동 설정됩니다.  
> `.env`의 `CHROMA_HOST/PORT`는 컨테이너 내부 설정이므로 그대로 두어도 됩니다.

### 4.2 로컬 실행

```bash
# 1. Chroma 서버 실행 (별도 터미널)
docker run --rm -p 8001:8000 chromadb/chroma:latest

# 2. 환경변수 로드
export $(grep -v '^#' .env | xargs)

# 3. 애플리케이션 빌드 및 실행
mvn spring-boot:run

# 또는 JAR 빌드 후 실행
mvn package -DskipTests
java -jar target/rag-agent-*.jar
```

### 4.3 접속 확인

```bash
curl http://localhost:8080/api/health
# 응답: {"status":"ok","service":"rag-agent","timestamp":"..."}
```

---

## 5. API 사용법

### 5.1 헬스 체크

```bash
GET /api/health
```

```bash
curl http://localhost:8080/api/health
```

응답:
```json
{
  "status": "ok",
  "service": "rag-agent",
  "timestamp": "2025-04-22T10:00:00Z"
}
```

---

### 5.2 문서 업로드 및 인덱싱

지원 형식: **PDF, PPTX, DOCX, TXT, MD**

```bash
POST /api/documents
Content-Type: multipart/form-data

파라미터:
  file     (필수) 업로드할 문서 파일
  version  (선택, 기본값: latest) 문서 버전 태그
```

```bash
curl -X POST http://localhost:8080/api/documents \
  -F "file=@/path/to/manual.pdf" \
  -F "version=latest"
```

응답:
```json
{
  "doc_id": "manual.pdf_a1b2c3d4",
  "filename": "manual.pdf",
  "version": "latest",
  "chunks": 42,
  "indexed_at": "2025-04-22T10:00:00Z",
  "sha256": "a1b2c3d4...",
  "errors": []
}
```

---

### 5.3 증분 동기화

`DATA_DIR/documents/` 폴더의 파일을 스캔해 새 파일·변경된 파일·삭제된 파일을 자동으로 인덱싱합니다.

```bash
POST /api/documents/sync?version=latest
```

```bash
curl -X POST "http://localhost:8080/api/documents/sync?version=latest"
```

응답:
```json
{
  "indexed": ["new_guide.pdf"],
  "updated": ["changed_manual.docx"],
  "deleted": ["old_spec.txt"]
}
```

---

### 5.4 인덱싱된 문서 목록 조회

```bash
GET /api/documents
```

```bash
curl http://localhost:8080/api/documents
```

응답:
```json
[
  {
    "doc_id": "manual.pdf_a1b2c3d4",
    "filename": "manual.pdf",
    "version": "latest",
    "chunks": 42,
    "indexed_at": "2025-04-22T10:00:00Z",
    "sha256": "a1b2c3d4...",
    "errors": []
  }
]
```

---

### 5.5 문서 삭제

```bash
DELETE /api/documents/{docId}?version=latest
```

```bash
curl -X DELETE "http://localhost:8080/api/documents/manual.pdf_a1b2c3d4?version=latest"
# 응답: 204 No Content
```

---

### 5.6 질의응답 (채팅)

```bash
POST /api/chat
Content-Type: application/json

{
  "question": "질문 내용",
  "version": "latest",
  "thread_id": "session-001"
}
```

| 필드 | 필수 | 기본값 | 설명 |
|------|------|--------|------|
| `question` | ✅ | — | 사용자 질문 |
| `version` | — | `latest` | 검색할 문서 버전 |
| `thread_id` | — | `default` | 멀티턴 대화 식별자 (세션별 고유 값 권장) |

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "question": "Spring Security에서 JWT 인증을 어떻게 설정하나요?",
    "version": "latest",
    "thread_id": "user-session-001"
  }'
```

응답:
```json
{
  "answer": "## 요약\nSpring Security에서 JWT 인증은 ...\n\n## 상세 설명\n...",
  "question_type": "usage",
  "sources": [
    "spring-security-guide.pdf | vlatest | p.12",
    "spring-security-guide.pdf | vlatest | p.13"
  ]
}
```

#### 질문 유형 (`question_type`)

| 값 | 설명 |
|----|------|
| `concept` | 개념·이론 설명 요청 |
| `usage` | 사용법·코드 예시 요청 |
| `error` | 오류·트러블슈팅 요청 |
| `version` | 버전·변경사항 요청 |
| `meta` | 인사·잡담 (RAG 미사용) |

#### 멀티턴 대화 예시

같은 `thread_id`로 연속 질문 시 이전 대화 맥락이 유지됩니다.

```bash
# 첫 번째 질문
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"question": "JPA란 무엇인가요?", "thread_id": "my-session"}'

# 후속 질문 (이전 대화 참조)
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"question": "그렇다면 N+1 문제는 어떻게 해결하나요?", "thread_id": "my-session"}'
```

새 대화를 시작하려면 `thread_id`를 새 값으로 바꾸면 됩니다.

---

## 6. 문서 인덱싱 동작

```
파일 업로드 or sync 요청
  └─▶ SHA-256 계산 → doc_registry.json 과 비교
        ├─ 신규 파일 → 로드 → 청크 분할 → 메타데이터 태깅 → Chroma 추가
        ├─ 변경 파일 → 기존 청크 삭제 → 재인덱싱
        └─ 삭제 파일 → Chroma에서 제거 → 레지스트리 삭제
```

- **청크 크기**: 800자, 오버랩 100자 (character 기준)
- **메타데이터**: `doc_id`, `filename`, `version`, `doc_type`, `source_type`, `page_or_slide`, `sha256`, `collected_at`
- **컬렉션 분리**: 버전별로 `manual_{version}` 컬렉션 사용 (예: `manual_latest`, `manual_1_0`)

---

## 7. 운영 팁

### 7.1 대화 메모리

`MemoryService`는 **프로세스 로컬 메모리**(`ConcurrentHashMap`)에 저장합니다.  
애플리케이션 재시작 시 대화 이력이 초기화됩니다.  
영속 메모리가 필요하면 Redis 또는 DB 기반으로 `MemoryService`를 교체하세요.

### 7.2 문서 버전 관리

업로드 시 `version` 파라미터를 통일하고, 채팅 요청에도 동일한 `version`을 사용하세요.

```bash
# 버전 1.0으로 업로드
curl -X POST http://localhost:8080/api/documents \
  -F "file=@v1.0-manual.pdf" -F "version=1.0"

# 버전 1.0 문서로 검색
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"question": "...", "version": "1.0"}'
```

### 7.3 데이터 영속성

| 데이터 | 저장 위치 |
|--------|----------|
| 문서 원본 | `DATA_DIR/documents/` |
| 인덱스 레지스트리 | `DATA_DIR/doc_registry.json` |
| 벡터 임베딩 | Chroma 서버 (Docker: `chroma_data` 볼륨) |
| 대화 이력 | 메모리 (재시작 시 초기화) |

### 7.4 성능

Java 21 Virtual Threads(`spring.threads.virtual.enabled=true`)가 활성화되어 있어  
다수의 LLM API 동시 요청을 효율적으로 처리합니다.

---

## 8. 문제 해결

### 애플리케이션이 시작되지 않음

```bash
# 로그 확인
mvn spring-boot:run 2>&1 | head -50

# Chroma 서버 연결 확인
curl http://localhost:8001/api/v1/heartbeat
```

원인 및 해결:
- **`OPENAI_API_KEY` 미설정** → `.env` 확인 및 환경변수 로드
- **Chroma 연결 실패** → `docker run --rm -p 8001:8000 chromadb/chroma:latest` 실행 확인
- **포트 충돌** → `lsof -i :8080` 으로 점유 프로세스 확인

### 질문에 답변이 없거나 "문서에서 확인되지 않음"

```bash
# 인덱싱된 문서 확인
curl http://localhost:8080/api/documents

# 동기화 재실행
curl -X POST "http://localhost:8080/api/documents/sync?version=latest"
```

원인 및 해결:
- 문서 미업로드 → `/api/documents` 로 업로드
- `version` 불일치 → 업로드·채팅 요청의 `version` 값 통일
- 빈 문서 또는 스캔 PDF → 텍스트 레이어가 있는 PDF 사용

### LLM 호출 오류 (500)

```bash
# API 키 및 엔드포인트 확인
curl $OPENAI_BASE_URL/models \
  -H "Authorization: Bearer $OPENAI_API_KEY"
```

원인 및 해결:
- `OPENAI_BASE_URL` 오탈자 (끝에 `/v1` 포함 여부 확인)
- `OPENAI_API_KEY` 만료 또는 권한 없음
- 로컬 LLM 서버 미실행

### Docker Compose에서 app 컨테이너가 재시작 반복

```bash
docker-compose logs app
```

원인 및 해결:
- `.env` 파일의 환경변수 누락 → 모든 필수 항목 입력
- Chroma 서버 아직 미준비 → `docker-compose up chroma` 먼저 실행 후 `docker-compose up app`

---

## 9. 빠른 점검 체크리스트

배포 후 순서대로 확인하세요.

- [ ] `GET /api/health` → `{"status":"ok"}` 응답
- [ ] 샘플 문서 1개 업로드 성공 (`POST /api/documents`)
- [ ] `GET /api/documents` → 업로드 문서 표시
- [ ] 샘플 질문 응답 성공 + `sources` 포함 (`POST /api/chat`)
- [ ] 후속 질문 시 이전 맥락 반영 (멀티턴 확인)

이상 없으면 서비스 정상입니다.
