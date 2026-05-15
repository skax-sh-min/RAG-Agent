# 운영자 매뉴얼

RAG Agent 시스템 배포·설정·운영 가이드입니다.

---

## 목차

1. [시스템 개요](#1-시스템-개요)
2. [폴더 구조](#2-폴더-구조)
3. [사전 준비](#3-사전-준비)
   - 3.1 [필수 소프트웨어](#31-필수-소프트웨어)
   - 3.2 [환경변수 전체 목록](#32-환경변수-전체-목록)
4. [실행 방법](#4-실행-방법)
   - 4.1 [Docker Compose (권장)](#41-docker-compose-권장)
   - 4.2 [로컬 실행](#42-로컬-실행)
   - 4.3 [접속 확인](#43-접속-확인)
5. [LLM 프로바이더 설정](#5-llm-프로바이더-설정)
   - 5.1 [구조 개요](#51-구조-개요)
   - 5.2 [프로바이더 속성](#52-프로바이더-속성)
   - 5.3 [라우팅 모드](#53-라우팅-모드)
   - 5.4 [시나리오별 설정 예제](#54-시나리오별-설정-예제)
   - 5.5 [Circuit Breaker](#55-circuit-breaker)
6. [운영 팁](#6-운영-팁)
   - 6.1 [대화 메모리](#61-대화-메모리)
   - 6.2 [문서 버전 관리](#62-문서-버전-관리)
   - 6.3 [데이터 영속성](#63-데이터-영속성)
   - 6.4 [성능](#64-성능)
7. [문제 해결](#7-문제-해결)
8. [보안 설정](#8-보안-설정)
   - 8.1 [git 훅 설치](#81-git-훅-설치)
   - 8.2 [입력 검증 동작](#82-입력-검증-동작)
   - 8.3 [응답 크기 제한](#83-응답-크기-제한)
9. [운영 체크리스트](#9-운영-체크리스트)

---

## 1. 시스템 개요

**기술 스택**: Spring Boot 3.5 + Spring AI 1.1.4, Java 21 Virtual Threads  
**벡터 DB**: ChromaDB (버전별 컬렉션)  
**대화 저장**: SQLite WAL  
**프론트엔드**: Thymeleaf + HTMX (SSE 스트리밍)

**에이전트 파이프라인**:

```
사용자 질문
  └─▶ [Classifier]  → 질문 유형 분류 (concept / usage / error / version / meta)
        ├─ meta  ──▶ [DirectAnswer] → [Finalize] → 응답
        └─ other ──▶ [Retrieval]   (LLM이 최적 쿼리 생성 → Chroma 검색)
                       └─▶ [Answer]   (구조화 답변 + 충분성 자체 평가)
                              ├─ 증거 부족 ──▶ [Retrieval] (최대 2회 재시도)
                              └─ 충분      ──▶ [Critic]   (근거 검증)
                                                    ├─ 미근거 ──▶ [Retrieval]
                                                    └─ 근거   ──▶ [Finalize] → 응답
```

---

## 2. 폴더 구조

```
rag_java/
├── pom.xml                     # Spring Boot 3.5 + Spring AI 1.1.4
├── Dockerfile
├── docker-compose.yml
├── .env.example
├── scripts/
│   ├── install-hooks.sh        # 클론 후 1회 실행: sh scripts/install-hooks.sh
│   └── hooks/
│       └── pre-commit          # .env 우발 커밋 방지
├── data/                       # 런타임 생성 (DATA_DIR)
│   ├── documents/              # 업로드된 문서 원본 (Sync 대상)
│   ├── images/                 # 추출된 이미지 ({docId}/ 하위)
│   ├── doc_registry.json       # 인덱싱 레지스트리 (SHA-256 기반)
│   └── memory.db               # 대화 이력 + LLM 사용량 (SQLite WAL)
└── src/main/
    ├── java/com/example/ragagent/
    │   ├── agent/              # AgentGraph (상태 머신), AgentState (불변 레코드)
    │   ├── config/             # AppProperties, LlmConfig, WebConfig
    │   ├── controller/         # ApiController (REST), WebController (HTMX), GlobalExceptionHandler
    │   ├── llm/                # LlmRouter, RoutingMode, CircuitBreaker
    │   ├── model/              # Java 21 레코드 (MetaKey 상수, ChatRequest/Response 등)
    │   ├── repository/         # SQLite CRUD (MemoryRepository, LlmUsageRepository 등)
    │   ├── security/           # FileTypeDetector (매직바이트), PromptInjectionGuard
    │   └── service/            # 에이전트 노드 서비스 + 문서 처리 파이프라인
    └── resources/
        ├── application.properties
        ├── messages.properties        # UI 문자열 — English (기본)
        ├── messages_ko.properties     # UI 문자열 — 한국어
        ├── static/
        │   ├── css/app.css, theme.css
        │   └── js/chat-stream.js      # SSE 스트리밍 클라이언트
        └── templates/                 # Thymeleaf 템플릿 + HTMX fragments
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

> **macOS Apple Silicon**: Docker Desktop 대신 [Apple Container](https://github.com/apple/container)를 사용할 수 있습니다.  
> GitHub Releases에서 최신 `.pkg`를 다운로드해 설치하세요.

---

### 3.2 환경변수 전체 목록

`.env.example`을 복사해 `.env`를 만들고 값을 채웁니다.

```bash
# macOS / Linux
cp .env.example .env

# Windows CMD
copy .env.example .env
```

#### API 키 / 연결 정보

| 변수 | 필수 | 기본값 | 설명 |
|------|------|--------|------|
| `LOCAL_LLM_URL` | — | `http://localhost:1234/v1` | `providers[0]` LOCAL 엔드포인트. 임베딩 폴백으로도 사용 |
| `LOCAL_LLM_KEY` | — | `lm-studio` | `providers[0]` API 키. **비우면 LOCAL 비활성화** |
| `LOCAL_LLM_MODEL` | — | `google/gemma-4-e4b` | `providers[0]` 모델명 |
| `OPENAI_API_KEY` | — | — | OpenAI providers 사용 시 필요. 미설정 시 해당 providers 자동 비활성화 |
| `GEMINI_API_KEY` | — | — | Gemini providers 사용 시 필요. 미설정 시 해당 providers 자동 비활성화 |
| `EMBED_BASE_URL` | — | `LOCAL_LLM_URL` | 임베딩 전용 엔드포인트. 미설정 시 `LOCAL_LLM_URL` 사용 |
| `EMBED_API_KEY` | — | `LOCAL_LLM_KEY` | 임베딩 전용 API 키. 미설정 시 `LOCAL_LLM_KEY` 사용 |
| `EMBED_MODEL` | — | `text-embedding-nomic-embed-text-v1.5` | 임베딩 모델명 |
| `CHROMA_HOST` | — | `http://localhost` | Chroma 서버 호스트 (프로토콜 포함) |
| `CHROMA_PORT` | — | `8001` | Chroma 서버 포트 |
| `DATA_DIR` | — | `./data` | 문서·레지스트리·SQLite DB 저장 경로 |

#### RAG 튜닝

| 변수 | 기본값 | 권장 범위 | 설명 |
|------|--------|----------|------|
| `CHUNK_SIZE` | `800` | 300 ~ 2000 | 청크 크기 (문자 수). 작을수록 정밀, 클수록 문맥 풍부 |
| `CHUNK_OVERLAP` | `100` | 0 ~ CHUNK_SIZE × 0.25 | 청크 간 중복 (문자 수). 청크 경계 문맥 보완 |
| `SEARCH_TOP_K` | `7` | 2 ~ 15 | 벡터 검색 반환 문서 수. 높을수록 재현율↑, 토큰↑ |
| `MAX_RETRY_COUNT` | `2` | 0 ~ 4 | 증거 부족 시 재검색 최대 횟수 |
| `MAX_CONVERSATION_CHARS` | `8000` | 1000 ~ 20000 | 멀티턴 컨텍스트 주입 최대 문자 수 |

#### 인덱싱 병렬 처리

| 변수 | 기본값 | 권장 범위 | 설명 |
|------|--------|----------|------|
| `INDEXING_MAX_FILES` | `3` | 1 ~ 8 | 파일 병렬 인덱싱 워커 수 |
| `INDEXING_MAX_LLM` | `4` | 1 ~ 16 | 인덱싱 중 LLM 병렬 호출 수 (키워드 추출) |

#### 설정 예시

**로컬 LLM 전용 (LM Studio)**:
```env
LOCAL_LLM_URL=http://localhost:1234/v1
LOCAL_LLM_KEY=lm-studio
LOCAL_LLM_MODEL=google/gemma-4-e4b
EMBED_MODEL=text-embedding-nomic-embed-text-v1.5
# EMBED_BASE_URL, EMBED_API_KEY 미설정 시 LOCAL_LLM_URL/KEY 자동 사용
```

**OpenAI 전용 (로컬 LLM 없음)**:
```env
OPENAI_API_KEY=sk-proj-...
EMBED_BASE_URL=https://api.openai.com
EMBED_MODEL=text-embedding-3-large
LOCAL_LLM_KEY=                     # 비워서 LOCAL providers[0] 비활성화
```

> 멀티 프로바이더 구성은 [§5 LLM 프로바이더 설정](#5-llm-프로바이더-설정)을 참고하세요.

---

## 4. 실행 방법

### 4.1 Docker Compose (권장)

```bash
# 1. 환경변수 설정
cp .env.example .env
# .env 파일 편집

# 2. 빌드 및 실행
docker-compose up --build -d

# 3. 상태 확인
docker-compose ps

# 4. 로그 확인
docker-compose logs -f app

# 5. 종료
docker-compose down
```

> `docker-compose.yml`에서 `CHROMA_HOST=chroma`, `CHROMA_PORT=8000`으로 자동 설정됩니다.  
> Chroma healthcheck 통과 후 app 컨테이너가 시작됩니다.

---

### 4.2 로컬 실행

#### macOS — Docker

```bash
# 1. Chroma 서버 실행 (별도 터미널)
docker run -d --name chroma-server -p 8001:8000 \
  -v "$(pwd)/data/chroma:/chroma/chroma" \
  chromadb/chroma:latest

# 2. 로그 확인
docker logs -f chroma-server
```
```bash
# 2. 환경변수 로드
export $(grep -v '^#' .env | xargs)

# 3. 애플리케이션 실행
mvn spring-boot:run
```

#### macOS — Apple Container (Apple Silicon 권장)

```bash
# 0. Apple Container 설치 (최초 1회)
# https://github.com/apple/container/releases 에서 .pkg 다운로드

# 1. container 시스템 시작 (설치 후 최초 1회 또는 재부팅 후)
container system start

# 2. Chroma 서버 실행 (별도 터미널)
container run -d --name chroma-server -p 8001:8000 \
  -v "$(pwd)/data/chroma:/chroma/chroma" \
  chromadb/chroma:latest

# 3. 환경변수 로드 및 실행
export $(grep -v '^#' .env | xargs)
mvn spring-boot:run

# 4. 종료
container stop <CONTAINER_ID>
container system stop
```

#### Ubuntu (Linux)

```bash
docker run -d --name chroma-server -p 8001:8000 \
  -v "$(pwd)/data/chroma:/chroma/chroma" \
  chromadb/chroma:latest &
set -a && source .env && set +a
mvn spring-boot:run
```

#### Windows (CMD)

```cmd
REM 1. Chroma 서버 (별도 CMD 창)
docker run -d --name chroma-server -p 8001:8000 -v "%cd%\data\chroma:/chroma/chroma" chromadb/chroma:latest

REM 2. 환경변수 로드
for /f "usebackq tokens=1,* delims==" %A in (`findstr /v "^#" .env`) do SET %A=%B

REM 3. 실행
mvn spring-boot:run
```

**Windows PowerShell**:
```powershell
Get-Content .env | Where-Object { $_ -notmatch '^#' -and $_ -match '=' } |
  ForEach-Object { $k,$v = $_ -split '=',2; [System.Environment]::SetEnvironmentVariable($k,$v,'Process') }
mvn spring-boot:run
```

---

### 4.3 접속 확인

- **Web UI**: `http://localhost:8080`
- **API 헬스 체크**: `http://localhost:8080/api/health` → `{"status":"ok",...}`

---

## 5. LLM 프로바이더 설정

### 5.1 구조 개요

LLM 호출은 두 레이어가 담당합니다.

| 레이어 | 용도 | 제어 방법 |
|--------|------|----------|
| `app.embedding.*` | 문서 인덱싱·벡터 검색 임베딩 | `EMBED_BASE_URL` / `EMBED_API_KEY` / `EMBED_MODEL` |
| LlmRouter (providers) | 질의응답·분류·검증 LLM 호출 | `application.properties` providers 블록 |
| Spring AI 전역 | auto-configured 빈 폴백 | `OPENAI_*` 환경변수 |

> 임베딩과 추론은 완전히 분리되어 있습니다. 로컬 임베딩 모델(Ollama 등)과 외부 추론 모델을 독립적으로 조합할 수 있습니다.

기본값으로 `providers[0]` (LOCAL) 하나만 등록되어 있습니다.  
멀티 프로바이더를 사용하려면 `application.properties`에 providers 블록을 추가하세요.

모든 프로바이더는 **OpenAI 호환 REST API**를 통해 호출됩니다.  
Gemini도 `https://generativelanguage.googleapis.com/v1beta/openai/` 엔드포인트를 통해 동일한 방식으로 사용합니다.

---

### 5.2 프로바이더 속성

| 속성 | 값 예시 | 설명 |
|------|---------|------|
| `name` | `local`, `gemini-flash` | 대시보드·로그 식별자 |
| `base-url` | `https://api.openai.com` | OpenAI 호환 엔드포인트 |
| `api-key` | `${OPENAI_API_KEY:}` | API 키. **비워두면(`=`) 해당 프로바이더 비활성화** — 시작 시 warn 로그 출력 |
| `model` | `gpt-4o`, `gemini-2.5-flash` | API에 전달되는 모델 식별자 |
| `role` | `LOCAL` \| `NORMAL` \| `PREMIUM` | 라우팅 우선순위 그룹 |
| `type` | `LIGHT_BOTH` \| `BOTH` \| … | 처리 가능한 태스크 유형 (아래 표 참조) |
| `priority` | 정수 (낮을수록 우선) | 같은 role 내 우선순위 |
| `stream` | `true` (기본) \| `false` | LLM API 호출 방식. 미설정 시 `true`. 상세는 아래 참조 |

#### stream 플래그

`stream` 속성은 서버 ↔ LLM API 구간의 호출 방식을 제어합니다. 브라우저 ↔ 서버 간 SSE 연결은 이 값과 무관하게 유지됩니다.

| 값 | 동작 | 적합한 상황 |
|----|------|------------|
| `true` (기본) | LLM 서버에 `stream: true`로 요청 — 토큰 생성 즉시 SSE로 전달 | 대부분의 클라우드 API, 표준 OpenAI 호환 서버 |
| `false` | LLM 서버에도 `stream: true`로 요청하되, 토큰을 내부 버퍼에 모아 완성 후 일괄 SSE 전달 — 브라우저에는 응답이 한 번에 표시됨 | `stream: false`(블로킹 API)를 지원하지 않는 로컬 LLM 서버 (LM Studio 등) |

> **주의**: 많은 로컬 LLM 서버(LM Studio 포함)는 `stream: false` 블로킹 모드를 제대로 처리하지 못하고 무한 대기합니다. 이 때문에 `stream=false`로 설정해도 내부적으로는 스트리밍 HTTP를 사용하며, 토큰을 모두 받은 뒤 일괄 전달하는 방식으로 동작합니다.

```properties
# 예시: local 프로바이더만 블로킹 방식으로 호출
app.llm.providers[0].stream=false
```

> 시작 로그에서 각 프로바이더의 stream 설정을 확인할 수 있습니다:  
> `local(LOCAL/BOTH/p0/stream=false) → http://localhost:1234/v1 [gemma-4-e4b]`

#### type 값

| type | 처리 가능 태스크 | 권장 모델 유형 |
|------|----------------|--------------|
| `LIGHT_BOTH` | 분류·키워드·쿼리 확장 + Vision | 범용 로컬 LLM |
| `BOTH` | 모든 태스크 (LIGHT_TEXT + TEXT + Vision) | 외부 고성능 모델 |
| `LIGHT_TEXT` | 분류·키워드·쿼리 확장만 | 텍스트 전용 소형 모델 |
| `TEXT` | 답변 생성·Critic만 | 텍스트 전용 대형 모델 |
| `VISION` | 이미지 설명만 | Vision 전용 모델 |

#### role 값 (COST_FIRST 기준 시도 순서)

| role | 설명 | 순서 |
|------|------|------|
| `LOCAL` | 로컬 LLM (무료) | 1순위 |
| `NORMAL` | 저비용 외부 API | 2순위 |
| `PREMIUM` | 고추론 외부 API | 3순위 |

#### 에이전트 노드별 TaskType

| 노드 | TaskType | 설명 |
|------|----------|------|
| ClassifierService | `LIGHT_TEXT` | 질문 유형 분류 |
| RetrievalService | `LIGHT_TEXT` | 쿼리 생성 (MultiQueryExpander) |
| AnswerService | `TEXT` | 답변 생성 |
| CriticService | `TEXT` | 근거 검증 |
| DirectAnswerService | `LIGHT_TEXT` | meta 질문 직접 응답 |
| VisionDescriptionService | `VISION` | 이미지 → 설명 생성 |
| ImageTypeClassifier | `LIGHT_BOTH` | 이미지 유형 분류 |
| KeywordMetadataEnricher | `LIGHT_TEXT` | 청크 키워드 추출 |

---

### 5.3 라우팅 모드

`application.properties`의 `app.llm.default-routing-mode`로 기본값 설정.  
Web UI 채팅 화면 드롭다운에서 대화별로 변경 가능.

| 모드 | 동작 | 권장 상황 |
|------|------|----------|
| `COST_FIRST` | LOCAL → NORMAL → PREMIUM 순 시도 | **기본값**. 비용 절감 우선 |
| `QUALITY_FIRST` | PREMIUM → NORMAL → LOCAL 순 시도 | 최고 품질 응답 필요 시 |
| `PROGRESSIVE` | COST_FIRST로 먼저 답변 → 품질 점수 미달 시 PREMIUM으로 재실행 | 품질·비용 균형 |
| `DUAL` | LOCAL + 외부를 **동시 병렬** 실행 → 두 결과를 탭으로 비교 | 로컬 vs 외부 품질 비교 |
| `LOCAL_ONLY` | LOCAL만 사용, 외부 API 미호출 | 오프라인 / 보안 환경 |

> **DUAL 전제 조건**: LOCAL role 프로바이더 등록 필수. 미등록 시 즉시 오류.  
> **PROGRESSIVE 임계값**: `app.llm.progressive-threshold=0.6` (기본). 현재 sufficient=true(1.0) / false(0.0) 이진값.

---

### 5.4 시나리오별 설정 예제

#### 예제 1 — 로컬 LLM 전용 (LM Studio / Ollama)

`application.properties` 변경 없이 환경변수만 설정합니다.  
기본 `providers[0]`이 `LOCAL_LLM_*` 값을 사용합니다.

`.env`:
```env
EMBED_BASE_URL=http://localhost:1234/v1
EMBED_MODEL=nomic-embed-text
LOCAL_LLM_URL=http://localhost:1234/v1
LOCAL_LLM_KEY=lm-studio
LOCAL_LLM_MODEL=gemma-4-27b-it
```

외부 API 호출을 완전히 차단하려면 `application.properties`에 추가:
```properties
app.llm.default-routing-mode=LOCAL_ONLY
```

---

#### 예제 2 — OpenAI 전용 (로컬 LLM 없음)

`LOCAL_LLM_KEY`를 비워 `providers[0]`을 비활성화하고, OpenAI를 NORMAL + PREMIUM으로 등록합니다.

`.env`:
```env
OPENAI_API_KEY=sk-proj-...
EMBED_BASE_URL=https://api.openai.com
EMBED_MODEL=text-embedding-3-large
LOCAL_LLM_KEY=                     # 비워서 LOCAL 비활성화
```

`application.properties`:
```properties
app.llm.default-routing-mode=COST_FIRST

app.llm.providers[0].name=openai-mini
app.llm.providers[0].base-url=https://api.openai.com
app.llm.providers[0].api-key=${OPENAI_API_KEY}
app.llm.providers[0].model=gpt-4o-mini
app.llm.providers[0].type=BOTH
app.llm.providers[0].role=NORMAL
app.llm.providers[0].priority=0

app.llm.providers[1].name=openai
app.llm.providers[1].base-url=https://api.openai.com
app.llm.providers[1].api-key=${OPENAI_API_KEY}
app.llm.providers[1].model=gpt-4o
app.llm.providers[1].type=BOTH
app.llm.providers[1].role=PREMIUM
app.llm.providers[1].priority=1
```

COST_FIRST 흐름: `gpt-4o-mini(NORMAL)` → (429/오류 시) `gpt-4o(PREMIUM)`

---

#### 예제 3 — Gemini Flash + OpenAI GPT-4o 혼합

`.env`:
```env
OPENAI_API_KEY=sk-proj-...
GEMINI_API_KEY=AIza...
EMBED_BASE_URL=https://api.openai.com
EMBED_MODEL=text-embedding-3-large
LOCAL_LLM_KEY=                     # 로컬 없으면 비활성화
```

`application.properties`:
```properties
app.llm.default-routing-mode=COST_FIRST

app.llm.providers[0].name=gemini-flash
app.llm.providers[0].base-url=https://generativelanguage.googleapis.com/v1beta/openai/
app.llm.providers[0].api-key=${GEMINI_API_KEY}
app.llm.providers[0].model=gemini-2.5-flash
app.llm.providers[0].type=BOTH
app.llm.providers[0].role=NORMAL
app.llm.providers[0].priority=0

app.llm.providers[1].name=openai
app.llm.providers[1].base-url=https://api.openai.com
app.llm.providers[1].api-key=${OPENAI_API_KEY}
app.llm.providers[1].model=gpt-4o
app.llm.providers[1].type=BOTH
app.llm.providers[1].role=PREMIUM
app.llm.providers[1].priority=1
```

COST_FIRST 흐름: `gemini-flash` → (429 시) `gpt-4o`

> **주의**: Flash와 Pro가 같은 Gemini API 키를 공유하므로, Flash 429 발생 시 Pro도 동시 차단될 수 있습니다.  
> OpenAI를 PREMIUM fallback으로 유지하는 이유입니다.

---

#### 예제 4 — 로컬 + Gemini + OpenAI 풀 구성

`.env`:
```env
OPENAI_API_KEY=sk-proj-...
GEMINI_API_KEY=AIza...
EMBED_BASE_URL=https://api.openai.com
EMBED_MODEL=text-embedding-3-large
LOCAL_LLM_URL=http://localhost:1234/v1
LOCAL_LLM_KEY=lm-studio
LOCAL_LLM_MODEL=gemma-4-27b-it
```

`application.properties`:
```properties
app.llm.default-routing-mode=COST_FIRST
app.llm.circuit-breaker-minutes=2
app.llm.progressive-threshold=0.6

# LOCAL — 무료, 분류·키워드·경량 태스크 처리
app.llm.providers[0].name=local
app.llm.providers[0].base-url=${LOCAL_LLM_URL:http://localhost:1234/v1}
app.llm.providers[0].api-key=${LOCAL_LLM_KEY:lm-studio}
app.llm.providers[0].model=${LOCAL_LLM_MODEL:gemma-4-27b-it}
app.llm.providers[0].type=LIGHT_BOTH
app.llm.providers[0].role=LOCAL
app.llm.providers[0].priority=0
# app.llm.providers[0].stream=false  # 로컬 LLM이 SSE 미지원 시 비활성화

# NORMAL — 저비용 외부 (Gemini Flash 우선, OpenAI Mini fallback)
app.llm.providers[1].name=gemini-flash
app.llm.providers[1].base-url=https://generativelanguage.googleapis.com/v1beta/openai/
app.llm.providers[1].api-key=${GEMINI_API_KEY}
app.llm.providers[1].model=gemini-2.5-flash
app.llm.providers[1].type=BOTH
app.llm.providers[1].role=NORMAL
app.llm.providers[1].priority=1

app.llm.providers[2].name=openai-mini
app.llm.providers[2].base-url=https://api.openai.com
app.llm.providers[2].api-key=${OPENAI_API_KEY}
app.llm.providers[2].model=gpt-4o-mini
app.llm.providers[2].type=BOTH
app.llm.providers[2].role=NORMAL
app.llm.providers[2].priority=2

# PREMIUM — 고추론 (Gemini Pro 우선, OpenAI GPT-4o fallback)
app.llm.providers[3].name=gemini-pro
app.llm.providers[3].base-url=https://generativelanguage.googleapis.com/v1beta/openai/
app.llm.providers[3].api-key=${GEMINI_API_KEY}
app.llm.providers[3].model=gemini-2.5-pro
app.llm.providers[3].type=BOTH
app.llm.providers[3].role=PREMIUM
app.llm.providers[3].priority=3

app.llm.providers[4].name=openai
app.llm.providers[4].base-url=https://api.openai.com
app.llm.providers[4].api-key=${OPENAI_API_KEY}
app.llm.providers[4].model=gpt-4o
app.llm.providers[4].type=BOTH
app.llm.providers[4].role=PREMIUM
app.llm.providers[4].priority=4
```

COST_FIRST 흐름:
```
[분류·키워드·쿼리] local(LIGHT_BOTH)
[답변·Critic]      gemini-flash → openai-mini → gemini-pro → openai
                   (각 단계에서 429/오류 시 다음 우선순위로 자동 전환)
```

---

### 5.5 Circuit Breaker

프로바이더에서 오류 발생 시 자동으로 일시 차단하고 다음 우선순위 프로바이더로 전환합니다.

| 오류 유형 | 차단 시간 | 비고 |
|----------|----------|------|
| HTTP 429 (Rate Limit) | `Retry-After` 헤더 값 | 헤더 없으면 `circuit-breaker-minutes` 적용 |
| HTTP 402 (결제 필요) | `Retry-After` 헤더 값 | |
| 기타 오류 (5xx, 네트워크) | 30초 고정 | |

- `app.llm.circuit-breaker-minutes=2` — 기본 차단 시간 (분)
- 차단 상태는 인메모리(`ConcurrentHashMap`) 유지 — 서버 재시작 시 초기화
- 모든 프로바이더 소진 시 → `LlmProviderExhaustedException` (500 응답)
- `/llm-usage` 대시보드에서 차단 중인 프로바이더를 빨간 카드 + MM:SS 카운트다운으로 확인 가능

---

## 6. 운영 팁

### 6.1 대화 메모리

`MemoryService`는 **SQLite**(`DATA_DIR/memory.db`)에 대화 이력을 영속합니다.

- WAL 모드로 읽기/쓰기 경합 최소화. SQLite pool size는 반드시 1 유지
- 스레드별 최근 50턴 이내에서 `MAX_CONVERSATION_CHARS`까지 LLM 컨텍스트 주입
- `/chat/{threadId}` 재진입 시 모든 이전 turn을 시간순으로 불러와 메시지 버블 복원
- `MemoryRepository` 인터페이스로 추상화 — Redis 등으로 교체 시 구현체만 추가

---

### 6.2 문서 버전 관리

```bash
# 버전 1.0으로 업로드
curl -X POST http://localhost:8080/api/documents \
  -F "file=@v1.0-manual.pdf" -F "version=1.0"

# 버전 1.0 문서로 검색
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"question": "...", "version": "1.0"}'
```

- 버전별로 `manual_{version}` Chroma 컬렉션 분리 (예: `manual_latest`, `manual_1_0`)
- Web UI에서는 채팅 사이드바 상단의 **version** 입력창에 버전 입력

---

### 6.3 데이터 영속성

| 데이터 | 저장 위치 | 비고 |
|--------|----------|------|
| 문서 원본 | `DATA_DIR/documents/` | Sync 대상 |
| 추출된 이미지 | `DATA_DIR/images/{docId}/` | 문서 삭제 시 함께 삭제 |
| 인덱스 레지스트리 | `DATA_DIR/doc_registry.json` | SHA-256 변경 감지 기준 |
| 벡터 임베딩 | Chroma 서버 | 로컬: `data/chroma/`, Docker Compose: `chroma_data` 볼륨 |
| 대화 이력 + LLM 사용량 | `DATA_DIR/memory.db` (SQLite) | WAL 모드 |

> Docker Compose 사용 시 `./data` 디렉터리를 컨테이너에 바인드 마운트합니다.  
> 데이터 백업 시 `data/` 디렉터리와 Chroma 볼륨을 함께 보존하세요.

---

### 6.4 성능

- **Java 21 Virtual Threads** (`spring.threads.virtual.enabled=true`) — LLM I/O 동시 요청을 효율적으로 처리
- **병렬 멀티 쿼리** — `RetrievalService`에서 3개 쿼리를 `CompletableFuture`로 병렬 실행
- **병렬 인덱싱** — `RagService.syncDirectory()`에서 파일별·LLM 호출별 Semaphore 기반 병렬 처리
- **DUAL 모드** — LOCAL + 외부를 Virtual Thread로 병렬 실행

CPU/메모리 제약이 있는 환경에서는 `INDEXING_MAX_FILES`와 `INDEXING_MAX_LLM`을 줄이세요.

---

## 7. 문제 해결

### 애플리케이션이 시작되지 않음

```bash
# 시작 로그 확인
mvn spring-boot:run 2>&1 | head -80

# Chroma 연결 확인
curl http://localhost:8001/api/v1/heartbeat
```

| 원인 | 조치 |
|------|------|
| 환경변수 미로드 | `export $(grep -v '^#' .env | xargs)` 재실행 |
| Chroma 연결 실패 | Chroma 컨테이너 실행 확인 (`docker ps` 또는 `container ls`) |
| 포트 충돌 | `lsof -i :8080`으로 점유 프로세스 확인 후 종료 |
| JDK 버전 | `java -version` → 21 이상인지 확인 |

---

### LLM 호출 오류 (500)

```bash
# 임베딩 서버 확인
curl ${EMBED_BASE_URL:-$LOCAL_LLM_URL}/models -H "Authorization: Bearer ${EMBED_API_KEY:-$LOCAL_LLM_KEY}"

# LOCAL 프로바이더 확인
curl $LOCAL_LLM_URL/models -H "Authorization: Bearer $LOCAL_LLM_KEY"
```

| 원인 | 조치 |
|------|------|
| `OPENAI_BASE_URL` 오탈자 | 끝에 `/v1` 포함 여부 확인 |
| API 키 만료/권한 없음 | 키 재발급 후 재시작 |
| LOCAL LLM 서버 미실행 | LM Studio / Ollama 실행 확인 |
| 로컬 LLM 없이 실행 | `LOCAL_LLM_KEY=` (빈 값)으로 LOCAL 비활성화 후 NORMAL/PREMIUM 등록 |
| 모든 프로바이더 소진 | `/llm-usage`에서 차단 상태 확인; circuit-breaker-minutes 경과 후 자동 해제 |

---

### Docker Compose에서 app 컨테이너가 재시작 반복

```bash
docker-compose logs app
```

| 원인 | 조치 |
|------|------|
| `.env` 환경변수 누락 | 필수 항목 모두 입력 확인 |
| Chroma 미준비 | healthcheck 설정 확인; `docker-compose up chroma` 먼저 실행 |
| 포트 충돌 | `docker-compose.yml`의 포트 매핑 변경 |

---

### 인덱싱이 느림

- `INDEXING_MAX_FILES` / `INDEXING_MAX_LLM` 값 증가 (CPU·API 쿼터 여유 있는 경우)
- 키워드 추출(`KeywordMetadataEnricher`)이 청크당 LLM 호출 → 문서 수 많을수록 시간 증가 (의도된 동작)

---

### 로컬 LLM 응답 타임아웃 (`SSE worker cancelled`)

로컬 LLM 서버(LM Studio 등)에 요청이 도달하지 않거나 응답이 없어 `app.sse-timeout-seconds` 경과 후 연결이 끊기는 경우입니다.

| 원인 | 확인 방법 | 조치 |
|------|----------|------|
| LLM 서버 미실행·모델 미로드 | LM Studio 상태 확인 | 모델 로드 완료 후 재시도 |
| `base-url`에 `/v1` 중복 | 시작 로그 `endpoint=...` 확인 | `base-url`에 `/v1` 포함 여부와 무관하게 내부 자동 처리됨. 앱 재시작 |
| 구버전 앱에서 `stream=false` 설정 | — | 최신 버전은 내부적으로 스트리밍 방식으로 대체함. 앱 재시작 |

---

### LLM 요청/응답 디버깅

애플리케이션이 LLM 서버로 보내는 실제 HTTP 요청(헤더·바디)을 확인하려면 Reactor Netty 와이어 로그를 Actuator로 런타임에 켭니다.

```bash
# 켜기 (스트리밍 응답의 모든 청크 포함 — 매우 시끄러움)
curl -X POST http://localhost:8080/actuator/loggers/reactor.netty.http.client \
  -H "Content-Type: application/json" \
  -d '{"configuredLevel":"DEBUG"}'

# 끄기
curl -X POST http://localhost:8080/actuator/loggers/reactor.netty.http.client \
  -H "Content-Type: application/json" \
  -d '{"configuredLevel":"INFO"}'

# 현재 레벨 확인 (GET, 204 대신 JSON 반환)
curl http://localhost:8080/actuator/loggers/reactor.netty.http.client
```

**Windows CMD**:
```cmd
curl -X POST http://localhost:8080/actuator/loggers/reactor.netty.http.client -H "Content-Type: application/json" -d "{\"configuredLevel\":\"DEBUG\"}"
```

Spring AI OpenAI 내부 로그(직렬화된 `ChatCompletionRequest` 포함)는 이미 `TRACE`로 활성화되어 있습니다. 레벨을 되돌리려면:
```bash
curl -X POST http://localhost:8080/actuator/loggers/org.springframework.ai.openai \
  -H "Content-Type: application/json" \
  -d '{"configuredLevel":"INFO"}'
```

> Actuator POST 성공 응답은 **HTTP 204 No Content** (응답 바디 없음)입니다. `-i` 플래그를 추가하면 상태 코드를 확인할 수 있습니다.

---

## 8. 보안 설정

### 8.1 git 훅 설치

`.env` 파일이 실수로 커밋되지 않도록 pre-commit 훅을 설치하세요.

```bash
sh scripts/install-hooks.sh
```

팀원 각자가 클론 후 1회 실행합니다.

### 8.2 입력 검증 동작

| 항목 | 제한 | 응답 |
|------|------|------|
| 질문 길이 | 최대 2,000자 | 400 Bad Request |
| 파일 업로드 크기 | 최대 100 MB (기본) | 413 Payload Too Large |
| 파일 형식 불일치 (매직바이트) | 확장자와 실제 내용이 다른 경우 | 422 Unprocessable Entity |

업로드 허용 형식과 매직바이트 매핑:

| 확장자 | 검증 기준 |
|--------|----------|
| `.pdf` | `%PDF` 서명 (4바이트) |
| `.docx`, `.pptx` | ZIP/PK 서명 `50 4B 03 04` (4바이트) |
| `.txt`, `.md` | 첫 8바이트에 NUL 문자 없음 |

### 8.3 응답 크기 제한

LLM 응답이 20,000자를 초과하면 자동으로 잘리고 말줄임 메시지가 추가됩니다.

---

## 9. 운영 체크리스트

배포 후 순서대로 확인하세요.

**초기 설정**:
- [ ] `sh scripts/install-hooks.sh` — pre-commit 훅 설치 (팀원 각자 1회)

**기본 동작**:
- [ ] `GET /api/health` → `{"status":"ok"}` 응답
- [ ] Web UI `http://localhost:8080` 접속 확인
- [ ] 샘플 문서 1개 업로드 성공
- [ ] 문서 목록에 업로드 문서 표시 확인
- [ ] 샘플 질문 응답 성공 + Sources 포함 확인
- [ ] 후속 질문 시 이전 맥락 반영 (멀티턴)
- [ ] 대화 재진입 시 이전 메시지 버블 복원 (`/chat/{threadId}`)
- [ ] KO/EN 언어 전환 동작 확인

**보안**:
- [ ] 확장자 불일치 파일 업로드 → 422 응답 확인
- [ ] 2,001자 이상 질문 → 400 응답 확인

**LLM 및 운영**:
- [ ] `/llm-usage` — 프로바이더 카드 정상(초록) 확인
- [ ] `/llm-usage` — 일별 차트 데이터 표시 확인
- [ ] Circuit Breaker 차단 없음 확인
- [ ] 데이터 디렉터리(`data/`) 마운트 및 쓰기 권한 확인
- [ ] Chroma 볼륨 영속성 확인 (재시작 후 문서 목록 유지)
