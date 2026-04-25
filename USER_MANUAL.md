# USER MANUAL

문서 기반 지식 Q&A 에이전트 (Spring AI / Java 21) 사용자 매뉴얼입니다.

## 1. 개요

이 프로젝트는 문서 기반 질의응답(RAG) 서비스입니다.  
**Web UI**와 **REST API** 두 가지 방식으로 사용할 수 있습니다.

- **Web UI** — 브라우저에서 `http://localhost:8080` 접속, KO/EN 언어 전환 지원
- **REST API** — `/api/*` 엔드포인트로 직접 호출

핵심 기능:
- **질문 유형 분류** — concept / usage / error / version / meta 5종 자동 분류
- **벡터 검색** — LLM이 최적 쿼리를 생성해 Chroma에서 유사 문서 검색
- **ReAct 재검색** — 증거 부족 시 자동 재검색 (최대 2회)
- **Critic 검증** — 답변이 문서에 근거하는지 이중 검증
- **멀티턴 대화** — 스레드 단위 대화 이력 유지 (SQLite 영속, 재시작 후에도 유지)
- **증분 인덱싱** — SHA-256 기반 변경 감지, `doc_registry.json` 영속
- **문서 버전 관리** — 버전별 독립된 Chroma 컬렉션

---

## 2. 폴더 구조

```
rag_java/
├── pom.xml
├── Dockerfile
├── docker-compose.yml
├── .env.example
├── README.md
├── USER_MANUAL.md
├── data/                          # 런타임 생성 (DATA_DIR)
│   ├── documents/                 # 업로드된 문서 원본 (Sync 대상)
│   ├── doc_registry.json          # 인덱싱 레지스트리
│   └── memory.db                  # 대화 이력 (SQLite)
└── src/main/
    ├── java/…                     # 애플리케이션 소스
    └── resources/
        ├── application.properties
        ├── messages.properties    # UI 문자열 — English (기본)
        ├── messages_ko.properties # UI 문자열 — 한국어
        ├── static/css/app.css
        └── templates/
            ├── layout/base.html   # 공통 레이아웃
            ├── chat.html          # 채팅 페이지
            ├── documents.html     # 문서 관리 페이지
            └── fragments/        # HTMX partial fragments
```

---

## 3. 사전 준비

### 3.1 필수 소프트웨어

| 소프트웨어 | 버전 | 역할 |
|-----------|------|------|
| Java JDK | 21 이상 | 애플리케이션 실행 |
| Maven | 3.9 이상 | 빌드 |
| Docker | 20 이상 | Chroma 벡터 DB 실행 (Linux / Windows / macOS) |
| Apple Container | 최신 | Chroma 벡터 DB 실행 (macOS Apple Silicon 대안) |

> **macOS Apple Silicon** 환경에서는 Docker Desktop 대신 [Apple Container](https://github.com/apple/container)를 사용할 수 있습니다.  
> 설치: GitHub Releases에서 패키지 다운로드.

### 3.2 환경변수 설정

`.env.example`을 복사해 `.env`를 만들고 값을 채웁니다.

**macOS / Linux**:
```bash
cp .env.example .env
```
**Windows CMD**:
```cmd
copy .env.example .env
```

변수별 설명 및 권장 범위는 [README.kr.md](README.kr.md)의 **환경 변수** 섹션을 참고하세요.

**로컬 LLM 예시 (LM Studio)**:
```env
OPENAI_BASE_URL=http://localhost:1234/v1
OPENAI_API_KEY=lm-studio
LLM_MODEL=google/gemma-4-e4b
EMBED_MODEL=text-embedding-nomic-embed-text-v1.5
CHROMA_HOST=http://localhost
CHROMA_PORT=8001
CHUNK_SIZE=800
CHUNK_OVERLAP=100
SEARCH_TOP_K=6
MAX_RETRY_COUNT=2
MAX_CONVERSATION_CHARS=7000
```

**표준 OpenAI 예시**:
```env
OPENAI_BASE_URL=https://api.openai.com
OPENAI_API_KEY=sk-proj-...
LLM_MODEL=gpt-4o
EMBED_MODEL=text-embedding-3-large
CHROMA_HOST=http://localhost
CHROMA_PORT=8001
CHUNK_SIZE=800
CHUNK_OVERLAP=100
SEARCH_TOP_K=6
MAX_RETRY_COUNT=2
MAX_CONVERSATION_CHARS=7000
```

---

## 4. 실행 방법

### 4.1 Docker Compose (권장)

```bash
# 1. 환경변수 설정
cp .env.example .env

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

### 4.2 로컬 실행

#### macOS — Docker 사용

```bash
# 1. Chroma 서버 실행 (별도 터미널, 데이터 로컬 저장)
docker run --rm -p 8001:8000 \
  -v "$(pwd)/data/chroma:/chroma/chroma" \
  chromadb/chroma:latest

# 2. 환경변수 로드
export $(grep -v '^#' .env | xargs)

# 3. 애플리케이션 실행
mvn spring-boot:run
```

#### macOS — Apple Container 사용 (Apple Silicon 권장)

Docker Desktop 없이 Apple의 네이티브 컨테이너 런타임을 사용합니다.

```bash
# 0. Apple Container 설치 (최초 1회)
# https://github.com/apple/container/releases 에서 최신 .pkg 다운로드 후 실행

# 1. container 시스템 시작 (설치 후 최초 1회 또는 재부팅 후)
container system start

# 2. Chroma 서버 실행 (별도 터미널, 데이터 로컬 저장)
container run --rm -p 8001:8000 \
  -v "$(pwd)/data/chroma:/chroma/chroma" \
  chromadb/chroma:latest

# 3. 환경변수 로드
export $(grep -v '^#' .env | xargs)

# 4. 애플리케이션 실행
mvn spring-boot:run
```

**종료 순서**:
```bash
# 1. 실행 중인 컨테이너 확인
container ls

# 2. Chroma 컨테이너 중지 (--rm 옵션으로 실행했으면 자동 삭제됨)
container stop <CONTAINER_ID>

# 3. container 시스템 종료
container system stop
```

> `container` CLI는 Docker CLI와 거의 동일한 문법을 사용합니다.  
> 이미지 관리: `container images`, `container ps`, `container stop` 등.

#### Ubuntu (Linux)

```bash
docker run --rm -p 8001:8000 \
  -v "$(pwd)/data/chroma:/chroma/chroma" \
  chromadb/chroma:latest
set -a && source .env && set +a
mvn spring-boot:run
```

#### Windows (CMD)

```cmd
REM 1. Chroma 서버 (별도 CMD 창, 데이터 로컬 저장)
docker run --rm -p 8001:8000 -v "%cd%\data\chroma:/chroma/chroma" chromadb/chroma:latest

REM 2. 환경변수 로드
for /f "usebackq tokens=1,* delims==" %A in (`findstr /v "^#" .env`) do SET %A=%B

REM 3. 실행
mvn spring-boot:run
```

> **Windows PowerShell**:
> ```powershell
> Get-Content .env | Where-Object { $_ -notmatch '^#' -and $_ -match '=' } |
>   ForEach-Object { $k,$v = $_ -split '=',2; [System.Environment]::SetEnvironmentVariable($k,$v,'Process') }
> mvn spring-boot:run
> ```

### 4.3 접속 확인

- **Web UI**: http://localhost:8080
- **API 헬스 체크**: http://localhost:8080/api/health → `{"status":"ok",...}`

---

## 5. Web UI 사용법

### 5.1 언어 전환

Navbar 우측 상단 **KO | EN** 링크를 클릭하면 즉시 언어가 전환됩니다.  
선택한 언어는 브라우저 쿠키(`lang`)에 저장되어 재방문 시에도 유지됩니다.

### 5.2 채팅

1. **`/` 또는 `/chat/{threadId}`** 접속
2. 사이드바 **New Chat** 버튼 → 새 스레드 시작
3. 좌측 상단 **version** 입력창에 검색할 문서 버전 입력 (기본: `latest`)
4. 하단 입력창에 질문 입력 후 **Enter** (줄바꿈: **Shift+Enter**)
5. 답변 버블에서 출처(Sources)·소요 시간·토큰 수 확인 가능

**스레드 관리**:
- 사이드바에서 이전 대화 클릭 → 이어서 질문 가능
- 연필 아이콘 → 대화 제목 인라인 편집
- 휴지통 아이콘 → 대화 삭제

### 5.3 문서 관리

`/documents` 접속 후:

| 기능 | 방법 |
|------|------|
| **업로드** | 드래그 앤 드롭 또는 영역 클릭 → 파일 선택 → Version 입력 → **Upload & Index** |
| **폴더 동기화** | **Sync Folder** 버튼 → `DATA_DIR/documents/` 폴더 자동 스캔 |
| **목록 새로고침** | **Refresh** 버튼 |
| **문서 삭제** | 행 우측 **Delete** 버튼 |

- 지원 형식: PDF, PPTX, DOCX, TXT, MD / 최대 100 MB
- 업로드 중 파일별 진행 바 + 전체 진행 표시
- Sync 결과(신규/업데이트/삭제 건수)는 우측 하단 toast로 표시

---

## 6. REST API 사용법

### 6.1 헬스 체크

```bash
curl http://localhost:8080/api/health
# {"status":"ok","service":"rag-agent","timestamp":"..."}
```

### 6.2 문서 업로드 및 인덱싱

지원 형식: **PDF, PPTX, DOCX, TXT, MD**

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

### 6.3 증분 동기화

`DATA_DIR/documents/` 폴더를 스캔해 신규·변경·삭제 파일을 자동 처리합니다.

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

### 6.4 인덱싱된 문서 목록 조회

```bash
curl http://localhost:8080/api/documents
```

### 6.5 문서 삭제

```bash
curl -X DELETE "http://localhost:8080/api/documents/manual.pdf_a1b2c3d4?version=latest"
# 응답: 200 OK
```

### 6.6 질의응답 (채팅)

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "question": "Spring Security에서 JWT 인증을 어떻게 설정하나요?",
    "version": "latest",
    "thread_id": "user-session-001"
  }'
```

| 필드 | 필수 | 기본값 | 설명 |
|------|------|--------|------|
| `question` | ✅ | — | 사용자 질문 |
| `version` | — | `latest` | 검색할 문서 버전 |
| `thread_id` | — | `default` | 멀티턴 대화 식별자 |

응답:
```json
{
  "answer": "## 요약\n...",
  "question_type": "usage",
  "sources": ["spring-security-guide.pdf | vlatest | p.12"]
}
```

#### 질문 유형 (`question_type`)

| 값 | 설명 |
|----|------|
| `concept` | 개념·이론 설명 |
| `usage` | 사용법·코드 예시 |
| `error` | 오류·트러블슈팅 |
| `version` | 버전·변경사항 |
| `meta` | 인사·잡담 (RAG 미사용) |

---

## 7. 문서 인덱싱 동작

```
파일 업로드 or Sync Folder
  └─▶ SHA-256 계산 → doc_registry.json 과 비교
        ├─ 신규 파일 → 로드 → 청크 분할 → 메타데이터 태깅 → Chroma 추가
        ├─ 변경 파일 → 기존 청크 삭제 → 재인덱싱
        └─ 삭제 파일 → Chroma에서 제거 → 레지스트리 삭제
```

### 7.1 형식별 청크 분할 전략

파일 형식에 따라 의미 단위를 최대한 보존하는 방식으로 분할합니다.

| 형식 | 로드 단위 | 분할 전략 |
|------|----------|----------|
| `.md` | `#` / `##` / `###` 헤더 단위 섹션 | 섹션이 `CHUNK_SIZE` 초과 시만 슬라이딩 윈도우 적용 |
| `.docx` | Word `Heading1` / `Heading2` 스타일 단위 섹션 | 섹션이 `CHUNK_SIZE` 초과 시만 슬라이딩 윈도우 적용 |
| `.pptx` | 슬라이드 1장 = 청크 1개 | 추가 분할 없음 |
| `.pdf` | 페이지 1장 = 문서 1개 | 슬라이딩 윈도우 (`CHUNK_SIZE` / `CHUNK_OVERLAP`) |
| `.txt` | 전체 파일 = 문서 1개 | 슬라이딩 윈도우 (`CHUNK_SIZE` / `CHUNK_OVERLAP`) |

> **슬라이딩 윈도우**: 청크 끝이 텍스트 중간이면 가장 가까운 줄바꿈(`\n`) 위치로 경계를 조정합니다.  
> `.docx` 헤딩이 없는 경우에는 전체 텍스트를 단일 문서로 처리한 뒤 슬라이딩 윈도우를 적용합니다.

### 7.2 메타데이터

- **공통**: `doc_id`, `filename`, `version`, `doc_type`, `source_type`, `sha256`, `collected_at`
- **페이지/슬라이드**: `page_or_slide` (PDF·PPTX)
- **섹션 기반** (MD·DOCX): `section` (섹션 번호), `heading` (해당 헤더 텍스트)
- **컬렉션 분리**: 버전별로 `manual_{version}` 컬렉션 사용 (예: `manual_latest`, `manual_1_0`)

---

## 8. 운영 팁

### 8.1 대화 메모리

`MemoryService`는 **SQLite**(`DATA_DIR/memory.db`)에 대화 이력을 영속합니다.

- WAL 모드로 읽기/쓰기 경합 최소화
- 스레드별 최근 50턴 이내에서 `MAX_CONVERSATION_CHARS`까지 컨텍스트 주입
- `MemoryRepository` 인터페이스로 추상화 — Redis 등으로 교체 시 구현체만 추가

### 8.2 문서 버전 관리

```bash
# 버전 1.0으로 업로드
curl -X POST http://localhost:8080/api/documents \
  -F "file=@v1.0-manual.pdf" -F "version=1.0"

# 버전 1.0 문서로 검색
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"question": "...", "version": "1.0"}'
```

Web UI에서는 채팅 사이드바 상단의 **version** 입력창에 버전을 입력합니다.

### 8.3 데이터 영속성

| 데이터 | 저장 위치 |
|--------|----------|
| 문서 원본 | `DATA_DIR/documents/` |
| 인덱스 레지스트리 | `DATA_DIR/doc_registry.json` |
| 벡터 임베딩 | Chroma 서버 (로컬 실행: `data/chroma/`, Docker Compose: `chroma_data` 볼륨) |
| 대화 이력 | `DATA_DIR/memory.db` (SQLite) |

### 8.4 성능

Java 21 Virtual Threads(`spring.threads.virtual.enabled=true`)가 활성화되어 LLM I/O 동시 요청을 효율적으로 처리합니다.

---

## 9. 문제 해결

### 애플리케이션이 시작되지 않음

```bash
mvn spring-boot:run 2>&1 | head -50
curl http://localhost:8001/api/v1/heartbeat
```

- **`OPENAI_API_KEY` 미설정** → `.env` 확인 및 환경변수 로드
- **Chroma 연결 실패** → `docker run --rm -p 8001:8000 -v "$(pwd)/data/chroma:/chroma/chroma" chromadb/chroma:latest` 실행 (Apple Container: `container run` 동일 옵션)
- **포트 충돌** → `lsof -i :8080` 으로 점유 프로세스 확인

### 질문에 답변이 없거나 "문서에서 확인되지 않음"

```bash
curl http://localhost:8080/api/documents
curl -X POST "http://localhost:8080/api/documents/sync?version=latest"
```

- 문서 미업로드 → `/documents` 페이지 또는 API로 업로드
- `version` 불일치 → 업로드·채팅의 `version` 값 통일
- 빈 문서 또는 스캔 PDF → 텍스트 레이어가 있는 PDF 사용

### LLM 호출 오류 (500)

```bash
curl $OPENAI_BASE_URL/models \
  -H "Authorization: Bearer $OPENAI_API_KEY"
```

- `OPENAI_BASE_URL` 오탈자 (끝에 `/v1` 포함 여부 확인)
- `OPENAI_API_KEY` 만료 또는 권한 없음
- 로컬 LLM 서버 미실행

### Docker Compose에서 app 컨테이너가 재시작 반복

```bash
docker-compose logs app
```

- `.env` 파일의 환경변수 누락 → 모든 필수 항목 입력
- Chroma 미준비 → `docker-compose up chroma` 먼저 실행

---

## 10. 빠른 점검 체크리스트

배포 후 순서대로 확인하세요.

- [ ] `GET /api/health` → `{"status":"ok"}` 응답
- [ ] Web UI `http://localhost:8080` 접속 확인
- [ ] 샘플 문서 1개 업로드 성공 (Web UI `/documents` 또는 `POST /api/documents`)
- [ ] 문서 목록에 업로드 문서 표시 확인
- [ ] 샘플 질문 응답 성공 + Sources 포함 확인
- [ ] 후속 질문 시 이전 맥락 반영 (멀티턴 확인)
- [ ] KO/EN 언어 전환 동작 확인

이상 없으면 서비스 정상입니다.
