# 운영자 매뉴얼

RAG Agent 시스템 배포·설정·운영 가이드입니다.

---

## 목차

1. [시스템 개요](#1-시스템-개요)
2. [폴더 구조](#2-폴더-구조)
3. [사전 준비](#3-사전-준비)
   - 3.1 [필수 소프트웨어](#31-필수-소프트웨어)
   - 3.2 [환경변수 전체 목록](#32-환경변수-전체-목록)
   - 3.3 [application.properties 전용 설정](#33-applicationproperties-전용-설정)
4. [실행 방법](#4-실행-방법)
   - 4.1 [Docker Compose (권장)](#41-docker-compose-권장)
      - 4.1.1 [ChromaDB 백엔드](#411-chromadb-백엔드)
      - 4.1.2 [sqlite-vec 백엔드](#412-sqlite-vec-백엔드)
   - 4.2 [로컬 실행](#42-로컬-실행)
      - 4.2.1 [ChromaDB 백엔드](#421-chromadb-백엔드)
      - 4.2.2 [sqlite-vec 백엔드](#422-sqlite-vec-백엔드)
   - 4.3 [접속 확인](#43-접속-확인)
   - 4.4 [HTTPS 설정 (Caddy 리버스 프록시)](#44-https-설정-caddy-리버스-프록시)
   - 4.5 [폐쇄망(Air-gapped) / 노-도커 실행](#45-폐쇄망air-gapped--노-도커-실행)
   - 4.6 [태그 기반 검색 적용 전 수동 초기화 (프리릴리즈)](#46-태그-기반-검색-적용-전-수동-초기화-프리릴리즈)
5. [LLM 프로바이더 설정](#5-llm-프로바이더-설정)
   - 5.1 [구조 개요](#51-구조-개요)
   - 5.2 [프로바이더 속성](#52-프로바이더-속성)
   - 5.3 [라우팅 모드](#53-라우팅-모드)
   - 5.4 [시나리오별 설정 예제](#54-시나리오별-설정-예제)
   - 5.5 [Circuit Breaker](#55-circuit-breaker)
   - 5.6 [Orphan 프로바이더 사용 기록 정리](#56-orphan-프로바이더-사용-기록-정리)
   - 5.7 [동시성 제어 및 백프레셔](#57-동시성-제어-및-백프레셔)
6. [운영 팁](#6-운영-팁)
   - 6.1 [대화 메모리](#61-대화-메모리)
   - 6.2 [문서 버전 관리](#62-문서-버전-관리)
   - 6.3 [데이터 영속성](#63-데이터-영속성)
   - 6.4 [성능](#64-성능)
   - 6.5 [설정 페이지 (/settings) — LLM/RAG 옵션 조회·핫 수정](#65-설정-페이지-settings--llmrag-옵션-조회핫-수정)
   - 6.6 [검색 품질 평가 하네스 (개발자용)](#66-검색-품질-평가-하네스-개발자용)
7. [벡터 스토어 관리](#7-벡터-스토어-관리)
8. [문제 해결](#8-문제-해결)
9. [보안 설정](#9-보안-설정)
   - 9.1 [git 훅 설치](#91-git-훅-설치)
   - 9.2 [입력 검증 동작](#92-입력-검증-동작)
   - 9.3 [응답 크기 제한](#93-응답-크기-제한)
   - 9.4 [인증 토글 (no-auth 모드)](#94-인증-토글-no-auth-모드)
      - 9.4.1 [평문 no-auth 모드](#941-평문-no-auth-모드)
      - 9.4.2 [관리 전용 인증 (management-only)](#942-관리-전용-인증-management-only)
10. [운영 체크리스트](#10-운영-체크리스트)

---

## 1. 시스템 개요

**기술 스택**: Spring Boot 3.5.15 + Spring AI 1.1.8, Java 21 Virtual Threads  
**벡터 DB**: ChromaDB(기본) 또는 sqlite-vec — `VECTORSTORE_TYPE`으로 선택 (§3.1 참조)  
**대화 저장**: SQLite WAL  
**프론트엔드**: Thymeleaf + HTMX (SSE 스트리밍)

**에이전트 파이프라인**:

```
사용자 질문
  └─▶ [Classifier]  → 질문 유형 분류 (concept / usage / error / version / meta)
        ├─ meta  ──▶ [DirectAnswer] → [Finalize] → 응답
        └─ other ──▶ [Retrieval]   (LLM이 최적 쿼리 생성 → 벡터 검색, chroma/sqlite-vec 백엔드 선택)
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
├── pom.xml                     # Spring Boot 3.5.15 + Spring AI 1.1.8
├── Dockerfile
├── docker-compose.yml
├── .env.example
├── scripts/
│   ├── install-hooks.sh        # 클론 후 1회 실행: sh scripts/install-hooks.sh
│   └── hooks/
│       └── pre-commit          # .env 우발 커밋 방지
├── data/                       # 런타임 생성 (DATA_DIR)
│   ├── documents/              # 업로드된 문서 원본 (Sync 대상, 공유)
│   ├── images/                 # 추출된 이미지 ({imageId}/ 하위 — 문서 SHA-256 기반 해시 키, docId와 별개, 공유)
│   ├── converted/              # DOCX → Markdown 변환 결과 ({docId}.md 원본, {docId}_corrected.md 교정본, 공유)
│   ├── chroma/                 # ChromaDB 벡터 데이터 (로컬 실행 시)
│   ├── audit/                  # 감사 로그 (audit.log + 롤링 압축본)
│   └── memory.db               # 대화 이력 + LLM 사용량 + 인덱스 레지스트리 (SQLite WAL)
└── src/main/
    ├── java/com/example/ragagent/
    │   ├── agent/              # AgentGraph (상태 머신), AgentState (불변 레코드)
    │   ├── config/             # AppProperties, LlmConfig, WebConfig
    │   ├── controller/         # ApiController (REST), WebController (HTMX), AdminController, GlobalExceptionHandler
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
| Docker | 20 이상 | **chroma 백엔드** 벡터 DB 실행 (sqlite-vec 백엔드는 불필요) |
| Apple Container | 최신 | Chroma 벡터 DB 실행 (macOS Apple Silicon 대안) |

> **macOS Apple Silicon**: Docker Desktop 대신 [Apple Container](https://github.com/apple/container)를 사용할 수 있습니다.  
> GitHub Releases에서 최신 `.pkg`를 다운로드해 설치하세요.

#### 벡터 스토어 백엔드 선택 (chroma / sqlite-vec)

`VECTORSTORE_TYPE` 환경변수로 벡터 스토어 백엔드를 선택합니다.

| 모드 | 기동 명령 | 비고 |
|------|----------|------|
| `chroma` (기본) | `docker compose --profile chroma up -d` | ChromaDB 컨테이너 필요 |
| `sqlite-vec` | `docker compose up -d` | 외부 컨테이너 없음 — SQLite 한 파일에 벡터 저장 |

> Docker Compose 2.20.2+ 필요 (`depends_on … required: false`). `.env`에 `COMPOSE_PROFILES=chroma`를 두면 `--profile` 없이도 chroma가 기동됩니다.

**sqlite-vec 모드 추가 준비** (공식 Java 라이브러리가 없어 네이티브 확장을 운영자가 제공):

1. 플랫폼용 `vec0` 로더블 확장을 [sqlite-vec 릴리스](https://github.com/asg017/sqlite-vec/releases)에서 받습니다. **컨테이너는 항상 Linux 바이너리** (`...-loadable-linux-{amd64|aarch64}`)가 필요합니다.
2. Docker: `docker-compose.yml`의 `app.volumes`에서 바이너리 마운트 주석을 해제하고 `SQLITE_VEC_EXTENSION_PATH=/opt/sqlite-vec/vec0`(suffix 생략)로 지정합니다. 로컬 실행 시엔 호스트 절대경로를 지정합니다.
3. `EMBED_*` 임베딩 모델의 **벡터 차원수**를 `app.embedding.dimensions`(예: 1536)로 반드시 설정합니다 — 미설정 시 기동이 실패합니다.

> **macOS 격리(quarantine) 주의**: 브라우저로 받은 `vec0.dylib`에는 macOS Gatekeeper가 `com.apple.quarantine` 속성을 붙여 서명되지 않은 바이너리의 `dlopen()` 로딩을 차단합니다. 기동 로그에는 `.../vec0.dylib.dylib`처럼 확장자가 중복된 경로 실패로 보이지만(SQLite가 첫 시도 실패 후 플랫폼 접미사를 다시 붙여 재시도하는 흔적일 뿐, 진짜 원인 아님) 근본 원인은 격리 플래그입니다. 아키텍처(`file`/`lipo -info`로 arm64/x86_64 확인)가 맞는데도 로딩이 실패하면 다음으로 해제하세요:
> ```bash
> xattr -d com.apple.quarantine <vec0.dylib 경로>
> ```

> **백엔드 전환 = 재인덱싱**: chroma ↔ sqlite-vec 간 벡터는 공유되지 않습니다. 전환 후 문서 재업로드(또는 재동기화)로 재인덱싱해야 합니다 — 원본은 `data/documents/`에 보존됩니다. `/admin` 페이지는 **두 백엔드 모두** 상태 카드·청크 조회/편집/삭제를 제공합니다(§7).
>
> **벡터 직렬화 형식(§10.9.2)**: 앱은 벡터를 raw float32 BLOB로 vec0에 바인딩합니다(과거 JSON 텍스트 리터럴 방식 대비 전송/파싱 비용 절감). BLOB는 sqlite-vec의 기본 이진 포맷이라 대부분의 `vec0` 빌드에서 문제없이 동작하지만, 폐쇄망에서 반입한 바이너리는 버전 편차가 있을 수 있으므로 sqlite-vec 백엔드를 처음 켜거나 `vec0` 바이너리를 교체한 직후에는 문서 1건을 인덱싱하고 검색까지 확인하세요. BLOB 바인딩이 지원되지 않는 빌드라면 인덱싱 시점에 vec0가 명확한 오류를 던지므로(데이터가 조용히 깨지지 않음) 바로 알 수 있습니다.

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
| `SERVER_PORT` | — | `8080` | 애플리케이션이 리스닝할 포트 (`server.port`). 다른 서비스와 충돌할 때만 변경 — Docker Compose 사용 시 `docker-compose.yml`의 포트 매핑(`127.0.0.1:8080:8080`)과 Caddy `reverse_proxy app:8080`도 함께 맞춰야 함 |
| `LOCAL_LLM_URL` | 이 provider 사용 시 ✅ | — (기본값 없음) | `providers[1]`(`local`, 로컬 LLM 1) 엔드포인트. **미설정·공백이면 이 provider가 통째로 비활성화된다** (`LlmConfig` G2 — 예전처럼 `http://localhost:1234/v1`로 조용히 폴백하지 않음). 값을 설정하면 기동 시 `GET {URL}/models`로 접속 가능·모델명 일치 여부를 검증한다(G3) — 실패하면 **애플리케이션이 시작되지 않는다**, [§5.2 프로바이더 활성화 게이트](#52-프로바이더-속성) 참고. 임베딩 설정(`EMBED_BASE_URL`)의 폴백으로도 별도 사용됨(그쪽은 기존처럼 자체 기본값 보유, G3 대상 아님) |
| `LOCAL_LLM_KEY` | — | `lm-studio` | `providers[1]` API 키. **로컬 엔드포인트(llama-server 등)는 키가 불필요** — 비우거나 미설정해도(URL만 설정돼 있다면) LOCAL provider는 등록됨(내부적으로 `no-key` 치환, G1). 완전히 제외하려면 `LOCAL_LLM_URL`을 비우거나(G2) `application.properties`의 `providers[1]`를 주석 처리 |
| `LOCAL_LLM_MODEL` | — | `google/gemma-4-e4b` | `providers[1]` 모델 식별자. 사용 중인 로컬 모델명으로 변경 |
| `LOCAL_LLM_URL_2` | 사용 시 ✅ | — (기본값 없음) | `providers[2]`(`local-2`, 로컬 LLM 2) 엔드포인트. `local`과 **동일한 role(LOCAL)·동일한 priority(1)**로 등록되어 두 번째 물리 서버로 로드밸런싱된다(least-in-flight — [§5.4 예제 5/7](#예제-5--로컬-llm-2대-로드밸런싱-처리량-확장) 참고). **미설정·공백이면 이 provider가 통째로 비활성화된다**(G2) — 2대째 로컬 서버가 없다면 그냥 비워두면 됨(회귀 0, `local` 단독으로 동작). 값을 설정하면 기동 시 접속 가능·모델명 일치 여부를 검증하며 실패 시 애플리케이션이 시작되지 않는다(G3) — 즉 "설정은 했지만 서버가 아직 안 떠 있다"는 이 변수를 비워두는 것과 결과가 다르다(전자는 기동 실패, 후자는 정상 기동) |
| `LOCAL_LLM_KEY_2` | — | `LOCAL_LLM_KEY` 폴백 | `providers[2]` API 키. 미설정 시 `LOCAL_LLM_KEY` 값을 그대로 사용 |
| `LOCAL_LLM_MODEL_2` | — | `LOCAL_LLM_MODEL` 폴백 | `providers[2]` 모델 식별자. 미설정 시 `LOCAL_LLM_MODEL`과 동일한 모델명을 사용(로컬 LLM 1과 동일 모델을 다른 서버에 복제하는 것이 일반적인 사용 사례) |
| `LOCAL_FAST_LLM_URL` | 사용 시 ✅ | — (기본값 없음) | §6.21 — `providers[0]`(`local-fast`, 소형 로컬 LLM 1) 엔드포인트. 잡무 전용 소형(~500MB) 모델을 `providers[1]`(`local`)과 **다른 포트/장비**에 띄우고 가리킨다.<br>**미설정·공백이면 이 provider가 통째로 비활성화된다**(G2) — 소형 모델 서버가 없다면 그냥 비워두면 됨(회귀 0, `MICRO_TEXT`는 `local`이 흡수). 값을 설정하면 기동 시 접속 가능·모델명 일치 여부를 검증하며(G3, 기본 활성) 실패 시 애플리케이션이 시작되지 않는다 — "URL은 설정했지만 서버가 아직 안 떠 있어 매 요청마다 `local`로 런타임 폴백"되는 예전 동작은 `LLM_VERIFY_LOCAL_MODELS_ON_STARTUP=false`로 G3를 꺼야만 나온다 — 예제는 [§5.4 예제 6](#예제-6--소형경량-llm-분리로-잡무-오프로딩-plan-621) 참고 |
| `LOCAL_FAST_LLM_KEY` | — | — | `providers[0]` API 키. `LOCAL_LLM_KEY`와 마찬가지로 로컬 엔드포인트는 보통 불필요 — 비워도(URL만 설정돼 있다면) `no-key`가 치환되어 등록됨 |
| `LOCAL_FAST_LLM_MODEL` | — | `Qwen3.5-0.8B-Q4_K_M.gguf` | `providers[0]` 모델 식별자. 사용 중인 소형 모델명으로 변경 |
| `LLM_VERIFY_LOCAL_MODELS_ON_STARTUP` | — | `true` | (`app.llm.verify-local-models-on-startup`) — G3 토글. `true`면 `LOCAL_LLM_URL`/`LOCAL_LLM_URL_2`/`LOCAL_FAST_LLM_URL`이 설정된 각 provider에 대해 기동 시 `GET {URL}/models`를 호출해 접속 가능·모델명 일치를 확인하고, 실패하면 애플리케이션이 시작되지 않는다. 로컬 서버가 앱보다 늦게 뜨는 배포 순서 레이스가 있을 때만 `false`로 끌 것 — 그 경우 예전처럼 첫 채팅 요청이 실패한 뒤 다른 provider로 런타임 폴백된다 |
| `LLM_ROUTING_MODE` | — | `COST_FIRST` | 기본 라우팅 모드 (`app.llm.default-routing-mode`) — `COST_FIRST`/`QUALITY_FIRST`/`PROGRESSIVE`/`DUAL`/`LOCAL_ONLY`.<br>**폐쇄망·로컬 전용은 `LOCAL_ONLY`** 로 외부 프로바이더 호출을 원천 차단. `LOCAL_ONLY`로 설정하면 채팅 화면 사이드바의 라우팅 전략 드롭다운 자체가 사라진다(어떤 모드를 골라도 결과가 같으므로) — 상세는 [LLM_ROUTING.md §8](LLM_ROUTING.md#8-제약-및-주의사항) 참고 |
| `OPENAI_API_KEY` | — | — | OpenAI providers 사용 시 필요. 미설정 또는 빈 값이면 해당 providers 자동 비활성화. providers 설정에서 `${OPENAI_API_KEY}` 형태로 참조 |
| `OPENAI_BASE_URL` | — | `https://api.openai.com` | OpenAI 호환 엔드포인트 기본 URL. providers 설정에서 `${OPENAI_BASE_URL}` 형태로 참조. Azure OpenAI 등 호환 엔드포인트로 교체 가능 |
| `GEMINI_API_KEY1` | — | — | Gemini 1번 API 키 — `providers[3]`(gemini-flash-lite, NORMAL), `providers[6]`(gemma-4-31b, PREMIUM) 공유. 미설정 시 해당 providers 자동 비활성화. providers 설정에서 `${GEMINI_API_KEY1}` 형태로 참조 |
| `GEMINI_API_KEY2` | — | — | Gemini 2번 API 키 — `providers[4]`(gemini-flash, NORMAL), `providers[7]`(gemma-4-31b, PREMIUM) 공유. 미설정 시 해당 providers 자동 비활성화. providers 설정에서 `${GEMINI_API_KEY2}` 형태로 참조. `providers[6]`·`[7]`은 이름·모델·priority(5)가 동일한 gemma-4-31b 2대로, 서로 다른 키를 씀으로써 PREMIUM 티어의 실질 처리량/쿼터를 두 배로 늘리는 로드밸런싱 쌍이다(§5.7 동일 우선순위 로드밸런싱) |
| `GEMINI_BASE_URL` | — | `https://generativelanguage.googleapis.com/v1beta/openai/` | Gemini API 엔드포인트 URL. 모든 Gemini providers가 `${GEMINI_BASE_URL}` 형태로 참조하므로 이 값 하나로 Gemini 전체 엔드포인트를 일괄 변경 가능 |
| `EMBED_BASE_URL` | — | `LOCAL_LLM_URL` 폴백 | 임베딩 전용 엔드포인트. 미설정 시 `LOCAL_LLM_URL` 사용. OpenAI 임베딩 사용 시 `https://api.openai.com` 등으로 독립 설정 |
| `EMBED_API_KEY` | — | `LOCAL_LLM_KEY` 폴백 | 임베딩 전용 API 키. 미설정 시 `LOCAL_LLM_KEY` 사용 |
| `EMBED_MODEL` | — | `text-embedding-nomic-embed-text-v1.5` | 임베딩 모델 식별자. **인덱싱 후 변경 금지** — 벡터 차원이 달라지면 기존 검색이 깨짐. 변경 시 전체 재인덱싱 필요 (chroma: 컬렉션 삭제 / sqlite-vec: `vec_embeddings` DROP — 차원이 DDL에 고정되며 `app.embedding.dimensions`도 함께 변경) |
| `EMBED_DIMENSIONS` | sqlite-vec 시 ✅ | — | **sqlite-vec 전용** — 임베딩 모델의 실제 출력 차원 (`app.embedding.dimensions`). vec0 테이블이 `FLOAT[dim]`이라 DDL에 고정 → 모델 실제 차원과 **정확히 일치**해야 함 (nomic-embed-text=768, bge-m3=1024, text-embedding-3-small=1536). chroma 모드에선 무시(빈값→null). sqlite-vec 모드에서 미설정 시 기동 실패(fail-fast) |
| `EMBED_ADDITIONAL_BASE_URLS` | — | — | §6.21 E1 — 추가 임베딩 엔드포인트(동일 모델·차원, 예: N개 GPU 복제본). 콤마 구분. 설정 시 `EMBED_BASE_URL`+이 목록에 걸쳐 least-in-flight 로드밸런싱. **모두 `EMBED_MODEL`을 같은 차원으로 서빙해야 함** (섞이면 벡터 인덱스 손상). 상세는 아래 "임베딩 병렬화" |
| `EMBED_MAX_CONCURRENT_BATCHES` | — | `1` | §6.21 E2 — 단일 문서 인덱싱 시 서브배치 병렬 임베딩 수(1=직렬, 기본 → 회귀 0). 대략 (엔드포인트 수 × 엔드포인트별 병렬)로 설정.<br>Chroma는 임베딩만 병렬(버퍼 후 1회 upsert), sqlite-vec는 병렬 임베딩 후 직렬 삽입(pool=1, §10.9.3 스트리밍 메모리 상한을 속도와 맞바꿈). 여러 파일 대량 인덱싱은 `INDEXING_MAX_FILES`로 이미 분산 |
| `VECTORSTORE_TYPE` | — | `chroma` | 벡터 스토어 백엔드 — `chroma` 또는 `sqlite-vec` (§3.1 "벡터 스토어 백엔드 선택" 참조) |
| `SQLITE_VEC_EXTENSION_PATH` | — | — | **sqlite-vec 전용** — 운영자가 제공하는 `vec0` 로더블 확장 절대경로 (suffix 생략 가능). 미설정 시 sqlite-vec 모드 기동 실패 |
| `SQLITE_VEC_ENTRYPOINT` | — | — | sqlite-vec 전용(선택) — `load_extension` 엔트리포인트 강제. 보통 불필요 |
| `CHROMA_HOST` | — | `http://localhost` | **chroma 전용** — Chroma 서버 호스트 (프로토콜 포함). Docker Compose 환경에서는 서비스명 `chroma`로 자동 지정됨 |
| `CHROMA_PORT` | — | `8001` | **chroma 전용** — Chroma 서버 포트. Docker Compose 환경에서는 `8000`으로 자동 지정됨 |
| `DATA_DIR` | — | `./data` | 문서 원본·이미지·변환 MD·SQLite DB 저장 루트 경로. Docker 실행 시 `/app/data`(볼륨 마운트 고정값)로 컨테이너 내부에 자동 설정됨 |
| `AUTH_ENABLED` | — | `true` | `false`로 설정하면 로그인 없이 실행 (no-auth 모드). 자세한 내용은 [§9.4](#94-인증-토글-no-auth-모드) 참조 |
| `DOMAIN` | — | `localhost` | Docker Compose의 `caddy` 컨테이너가 사용하는 도메인명. `localhost`이면 Caddy 로컬 CA 인증서 자동 생성. 운영 시 실제 도메인(예: `myrag.duckdns.org`)으로 변경 |
| `USE_CADDY_REVERSE_PROXY_HTTPS` | — | `true` | 세션 쿠키 `Secure` 플래그 제어 (`server.servlet.session.cookie.secure`). Caddy HTTPS 환경에서는 `true`(기본값). **HTTP 로컬 단독 실행 시 반드시 `false`로 변경** — `true` 상태에서 HTTP로 접근하면 쿠키가 전송되지 않아 로그인 불가 |

#### RAG 튜닝

| 변수 | 기본값 | 권장 범위 | 설명 |
|------|--------|----------|------|
| `CHUNK_SIZE` | `800` | 300 ~ 2000 | 청크 크기 (문자 수). 작을수록 정밀, 클수록 문맥 풍부 |
| `CHUNK_OVERLAP` | `100` | 0 ~ CHUNK_SIZE × 0.25 | 청크 간 중복 (문자 수). 청크 경계 문맥 보완 전용 |
| `MIN_CHUNK_SIZE` | `300` | 50 ~ CHUNK_SIZE × 0.25 | 너무 작은 청크를 인접 청크와 병합할 최소 길이 기준 |
| `SEARCH_TOP_K` | `7` | 2 ~ 15 | 벡터 검색 반환 문서 수. 높을수록 재현율↑, 토큰↑ |
| `SEARCH_SIMILARITY_THRESHOLD` | `0.0` | 0.0 ~ 0.75 | 청크 유지 최소 코사인 유사도. `0.0`=전체 수용. 운영 0.5~0.75 튜닝 시 골든셋 recall 확인 후 적용 |
| `SEARCH_MULTIQUERY_ENABLED` | `true` | true/false | 검색 전 질의 다중 확장(LLM) 여부. `false`면 임계 경로 첫 LLM 콜 제거 |
| `SEARCH_MULTIQUERY_MIN_LENGTH` | `15` | 0 ~ 20 | 이 길이(trim) 미만 질의는 확장 생략. `0`=항상 확장. 짧은 키워드 질의 TTFT↓(§10.8.1) |
| `SEARCH_HYBRID_ENABLED` | `true` | true/false | RRF에 BM25(FTS5) 키워드 축 추가(§10.7.2 — 이 플래그와 무관하게 `chunk_fts`는 항상 채워지므로 **활성화해도 기존 색인 문서 재인덱싱 불필요**, FTS5/하이브리드 검색 도입 이전에 색인된 아주 오래된 문서만 예외) |
| `SEARCH_RETRY_ESCALATE` | `true` | true/false | 재시도마다 후보 풀 확대. `candidateK = min(topK×(retryCount+1), topK×3)`. 동일 검색 반복 회피 |
| `SEARCH_RERANK_ENABLED` | `false` | true/false | RRF 후 LLM 리랭킹 단계 (opt-in). **턴당 LLM 1콜 추가** → 정밀도↑/레이턴시 트레이드오프 |
| `SEARCH_CANDIDATE_MULTIPLIER` | `3` | 2 ~ 5 | 리랭킹 전 후보 풀 크기. `topK × N`개 가져와 리랭킹 후 topK로 축소 |
| `SEARCH_TAG_CANDIDATE_MULTIPLIER` | `2` | 1 ~ 5 | 태그가 선택된 검색의 후보 풀 확대 배수. `candidateK = max(candidateK, topK × N)` — sqlite-vec에서 태그 엄격 필터 후 결과가 부족할 때 보정(§4.6) |
| `SEARCH_RRF_KEYWORD_WEIGHT` | `1.0` | 0.5 ~ 3.0 | 가중 RRF(Phase 7-A) — BM25 키워드 축 가중치. 벡터 축(MultiQuery 1~3개)은 항상 `1/축개수`로 그룹 정규화되므로 `1.0`이 정규화된 벡터 그룹과 동일 비중. `SEARCH_HYBRID_ENABLED=false`면 키워드 축이 없어 무영향 |
| `SEARCH_RRF_K` | `60` | 20 ~ 100 | 가중 RRF(Phase 7-A) — RRF 순위융합 상수 k(원논문 기본값 60) |
| `MAX_RETRY_COUNT` | `2` | 0 ~ 4 | 증거 부족 시 재검색 최대 횟수 |

대화 컨텍스트 주입 길이는 `LLM_MAX_TOKENS × 0.5`(최소 1,000자)로 자동 계산됩니다 — 기본값 기준 `6000 × 0.5 = 3000`자. 원문 그대로 보내는 폴백 경로(`MemoryService.getHistory()`)와 요약 캐시 경로(`ConversationSummarizerService.buildContext()`, §6.1) 모두 이 예산을 동일하게 지키도록 통일되어 있습니다.

#### 대화 메모리 / 요약 캐시 튜닝

| 변수 | 기본값 | 권장 범위 | 설명 |
|------|--------|----------|------|
| `MEMORY_FETCH_LIMIT_TURNS` | `10` | 5 ~ 200 | 폴백 경로(`SqliteMemoryRepository.getHistory()`)와 요약 대상 조회(`getRecentTurns()`, §6.1)에서 공통으로 적용되는 최근 turn 상한 |
| `SUMMARY_MAX_CACHED_THREADS` | `3` | 1 ~ 10 | 요약 사전계산 결과를 유지하는 LRU 캐시 크기(동시 활성 thread 수). 초과 시 가장 오래 미사용된 thread부터 축출 |
| `SUMMARY_MAX_SUMMARY_CHARS` | `2000` | 500 ~ 5000 | LLM이 생성한 요약 문자열의 상한 (초과 시 잘림) |
| `SUMMARY_RECENT_RAW_TURNS` | `2` | 1 ~ 5 | 요약 뒤에 원문 그대로 덧붙일 최근 turn 수 — 이 turn들도 위 문자 예산 안에서 최신 우선으로 채워지며, 예산 초과분은 잘리지 않고 통째로 드롭됨 |
| `SUMMARY_PRECOMPUTE_TTL_SECONDS` | `15` | 5 ~ 60 | 동일 thread에 대한 중복 요약 사전계산(precompute) 억제 창(초). 프런트엔드가 이미 debounce하므로 이 값은 안전장치 성격 |

> 모두 미설정 시 기존 하드코딩 값과 동일하게 동작합니다(회귀 없음). 로컬 소형 모델처럼 요약 생성이 느리거나 부정확한 환경에서는 `SUMMARY_MAX_CACHED_THREADS`/`SUMMARY_RECENT_RAW_TURNS`를 낮춰 컨텍스트 품질보다 속도를 우선할 수 있습니다.

#### 인덱싱 병렬 처리

| 변수 | 기본값 | 권장 범위 | 설명 |
|------|--------|----------|------|
| `INDEXING_MAX_FILES` | `1` | 1 ~ 8 | 파일 병렬 인덱싱 워커 수. **인덱싱 LLM 동시 호출 피크 ≈ `INDEXING_MAX_FILES` × `INDEXING_MAX_LLM`** 이므로, 기본값 `1`은 피크를 정확히 `INDEXING_MAX_LLM`으로 고정한다(§6.5 주석 참고). 올리면 처리량은 늘지만 피크가 곱으로 커진다 |
| `INDEXING_MAX_LLM` | `3` | 1 ~ 16 | 인덱싱 중 LLM 병렬 호출 수 — 키워드 추출뿐 아니라 MD 교정·TXT 구조화·지연 Vision 설명이 모두 사용. 로컬 LLM 서버의 `--parallel` 값에 맞춘다 |
| `INDEXING_KEYWORD_TIMEOUT_SECONDS` | `180` | 30 ~ 600 | 청크 키워드 추출 1회당(§10.8.2 배치 시 배치 1회당) 최대 대기 시간. 초과 시 TF fallback |
| `INDEXING_KEYWORD_BATCH_SIZE` | `4` | 1 ~ 8 | §10.8.2 — 청크 N개를 한 LLM 호출로 묶어 요청(왕복 ≈ ceil(청크수/N)). `1`=배치 없음(청크당 1콜, 이전 동작). 배치가 클수록 응답 길이도 늘어나므로 로컬 모델에서 타임아웃이 잦으면 `INDEXING_KEYWORD_TIMEOUT_SECONDS`를 함께 올리세요 |

#### 질의 경로 동시성 제어

인덱싱과 별개로, **채팅/질의 경로**(분류·답변·재검색 등)가 프로바이더별로 동시에 보내는 요청 수를 제어합니다. 상세 동작·적용 범위는 [§5.7](#57-동시성-제어-및-백프레셔)을 참고하세요.

| 변수 | 기본값 | 권장 범위 | 설명 |
|------|--------|----------|------|
| `LLM_DEFAULT_PROVIDER_CONCURRENCY` | `3` | 1 ~ 16 | 프로바이더별 동시 처리 상한 기본값(`app.llm.default-provider-concurrency`) — LLM 서버의 실제 `--parallel` 값과 일치시키는 것이 원칙. 개별 프로바이더는 `application.properties`의 `app.llm.providers[N].concurrency`로 오버라이드 가능 |
| `LLM_PERMIT_WAIT_TIMEOUT_SECONDS` | `20` | 5 ~ 60 | 동시성 슬롯 대기 상한(초, `app.llm.permit-wait-timeout-seconds`). 초과 시 `LLM_READ_TIMEOUT_SECONDS`(기본 180초)까지 기다리지 않고 즉시 HTTP 429 + `Retry-After` 응답 |

#### HTTP Timeout 튜닝

| 변수 | 기본값 | 권장 범위 | 설명 |
|------|--------|----------|------|
| `SSE_IDLE_TIMEOUT_SECONDS` | `120` | 30 ~ 600 | 에이전트 그래프의 진행 신호(노드 전환·토큰·소스 준비)가 이 시간만큼 전혀 없으면 중단 (`app.sse-idle-timeout-seconds`). 매 신호마다 리셋되므로 느리지만 계속 응답 중인 로컬 LLM은 끊기지 않음 — 실제로 "멈춘" 요청을 감지하는 주 타임아웃 |
| `SSE_TIMEOUT_SECONDS` | `3600` | 600 ~ 7200 | 브라우저 ↔ 서버 SSE 연결의 절대 상한(활동 여부 무관, `app.sse-timeout-seconds`) — 응답이 영원히 끝나지 않는 극단적 상황에 대한 안전장치일 뿐, 평소엔 `SSE_IDLE_TIMEOUT_SECONDS`가 먼저 작동함 |
| `LLM_CONNECT_TIMEOUT_SECONDS` | `10` | 2 ~ 30 | LLM API 연결 타임아웃 (`app.llm.connect-timeout-seconds`) |
| `LLM_READ_TIMEOUT_SECONDS` | `180` | 30 ~ 600 | LLM API 응답 읽기 타임아웃 (`app.llm.read-timeout-seconds`) |
| `EMBED_CONNECT_TIMEOUT_SECONDS` | `10` | 2 ~ 30 | 임베딩 API 연결 타임아웃 (`app.embedding.connect-timeout-seconds`) |
| `EMBED_READ_TIMEOUT_SECONDS` | `120` | 30 ~ 600 | 임베딩 API 응답 읽기 타임아웃 (`app.embedding.read-timeout-seconds`) |
| `CHROMA_CONNECT_TIMEOUT_SECONDS` | `5` | 1 ~ 15 | Chroma API 연결 타임아웃 (`app.chroma.connect-timeout-seconds`) |
| `CHROMA_READ_TIMEOUT_SECONDS` | `60` | 10 ~ 300 | Chroma API 응답 읽기 타임아웃 (`app.chroma.read-timeout-seconds`) |

#### 임베딩 사용량 추적

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `EMBED_USAGE_FALLBACK_ENABLED` | `true` | 임베딩 사용량은 `llm_usage`에 `embed:<model>`로 채팅과 분리 집계되어 `/llm-usage`에 별도 카드로 표시됩니다(§5.5, §10).<br>임베딩 서버가 응답에 토큰 사용량을 반환하지 않으면(로컬 llama-server 등 흔함) 입력 텍스트 길이 근사(chars/4)로 대체 기록합니다. `false`로 설정하면 근사 대신 `0`을 기록합니다. 근사 경로 진입 시 서버 로그에 경고가 **최초 1회만** 출력됩니다 |
| `EMBED_MAX_CHUNK_CHARS` | `0` (비활성) | 청크 1개의 **문자 수 하드 상한**. 임베딩 서버가 `input (N tokens) is too large ... (current batch size: 512)`처럼 배치/토큰 한계로 청크를 거부할 때 사용합니다.<br>이 값을 넘는 청크는 (의미 단위 청킹이 끝난 뒤) 줄 경계에서 **강제 재분할**되어 서버 한계를 넘지 않도록 보장합니다. 한국어·코드는 대략 1토큰/문자이므로 512토큰 배치라면 `~450` 정도가 안전. **먼저 서버 배치를 키우는 것을 권장**(아래 §8 참조)하고, 이건 최후의 안전장치로 사용하세요 |

#### 임베딩 병렬화 (§6.21 E1~E3)

임베딩 처리량을 여러 엔드포인트·병렬 서브배치로 확장한다. 셋 다 **기본 비활성(회귀 0)**인 opt-in이다.

- **E1 — 다중 엔드포인트 로드밸런싱** (`EMBED_ADDITIONAL_BASE_URLS`): 같은 임베딩 모델을 여러 서버/포트(예: N개 GPU)에 띄우고 콤마로 나열하면 `LoadBalancingEmbeddingModel`이 요청마다 잔여 in-flight가 가장 적은(least-in-flight) 엔드포인트로 보낸다(LLM의 [§5.7](#57-동시성-제어-및-백프레셔) 로드밸런서와 동일 개념). **모든 엔드포인트는 `EMBED_MODEL`을 동일 차원으로 서빙해야 한다** — 다른 모델을 섞으면 벡터가 비교 불가라 인덱스가 깨진다.
- **E2 — 병렬 서브배치 임베딩** (`EMBED_MAX_CONCURRENT_BATCHES`, 기본 1): 한 문서를 인덱싱할 때 토큰 단위 서브배치들을 동시에 임베딩한다. E1과 결합하면 **단일 대용량 문서**도 여러 엔드포인트를 동시에 채운다. 대략 `(엔드포인트 수 × 엔드포인트별 --parallel)`로 설정.
  - **chroma**: 임베딩만 병렬화하고 원래대로 마지막에 1회 upsert → 메모리 프로파일 불변, 저위험.
  - **sqlite-vec**: 병렬 임베딩 후 삽입은 직렬(SQLite `pool=1`). §10.9.3 스트리밍 삽입(서브배치 단위 메모리 상한)을 포기하고 문서 전체 임베딩을 잠깐 메모리에 들고 있게 되는 속도↔메모리 트레이드오프를 아는 상태에서만 켠다(임베딩 벡터는 청크당 수 KB라 실무 부담은 작음).
  - **여러 파일 동시 인덱싱**은 E2 없이도 `INDEXING_MAX_FILES`가 파일 단위로 E1 엔드포인트에 분산하므로, E2는 주로 "큰 파일 하나"를 빠르게 처리할 때 이득이 크다.
- **E3 — 배치 토폴로지** (배포 권고, 코드 아님): 소형 LLM(§6.21)·임베딩 서버·대형 LLM을 **서로 다른 장비/포트**에 두면 co-located GPU/CPU 경합이 줄어 임베딩 스루풋이 간접적으로 오른다. 단일 장비라면 각 서버의 `--parallel`·배치 크기 합이 장비 용량을 넘지 않게 조정한다.

**설정 예 (임베딩 서버 2대)**:
```properties
# 같은 nomic-embed 모델을 2대(다른 포트/장비)에 서빙
app.embedding.base-url=http://gpu-a:1234/v1
app.embedding.additional-base-urls=http://gpu-b:1234/v1
app.embedding.model=text-embedding-nomic-embed-text-v1.5
# 단일 문서도 두 서버를 동시에 쓰도록 병렬 서브배치(2대 × 서버별 병렬 2 = 4)
app.embedding.max-concurrent-batches=4
```
환경변수로는 `EMBED_ADDITIONAL_BASE_URLS=http://gpu-b:1234/v1`, `EMBED_MAX_CONCURRENT_BATCHES=4`.

> **`INDEXING_MAX_LLM`과의 관계**: 둘 다 인덱싱 파이프라인의 동시성 제한이지만 서로 다른 단계·리소스를 게이팅하는 완전히 독립적인 세마포어라 직접적인 종속 관계는 없다. `INDEXING_MAX_LLM`(`app.indexing.max-concurrent-llm-calls`)은 키워드/컨텍스트 추출·MD 교정·TXT 구조화·Vision 이미지 설명 등 **채팅형 LLM 호출**을 제한하며, 소비자마다 자기 세마포어를 만들어 씀(공유 풀 아님). `EMBED_MAX_CONCURRENT_BATCHES`는 **임베딩** 서브배치 호출만 제한한다. 같은 문서를 인덱싱하는 동안 키워드 추출은 `INDEXING_MAX_LLM`만큼, 그 문서의 임베딩 서브배치는 `EMBED_MAX_CONCURRENT_BATCHES`만큼 각자 병렬로 동작하며, 서로 다른 백엔드 서버(LLM 서버 vs 임베딩 서버)를 겨냥하므로 한쪽 값이 다른 쪽 상한이나 동작에 영향을 주지 않는다. 튜닝도 각각 독립적으로: `INDEXING_MAX_LLM`은 LLM 서버의 `--parallel`에, `EMBED_MAX_CONCURRENT_BATCHES`는 `(임베딩 엔드포인트 수 × 엔드포인트별 병렬)`에 맞춘다.

#### 쿼리 임베딩 캐시 (Phase 7-A)

| 변수 | 기본값 | 권장 범위 | 설명 |
|------|--------|----------|------|
| `SEARCH_QUERY_EMBED_CACHE_ENABLED` | `true` | true/false | 정규화된 질의 텍스트 → 임베딩 벡터를 Caffeine 인메모리 캐시에 저장해 반복·유사 질문의 임베딩 왕복을 생략합니다. 캐시 히트 시 `embed:<model>` usage도 기록되지 않습니다(실제 호출이 없었으므로) |
| `SEARCH_QUERY_EMBED_CACHE_MAX_SIZE` | `500` | 100 ~ 5000 | 캐시 최대 엔트리 수 |
| `SEARCH_QUERY_EMBED_CACHE_TTL_SECONDS` | `600` | 60 ~ 3600 | 캐시 엔트리 TTL(초, write 기준 만료) |

> 캐시는 인메모리 전용(재시작 시 초기화)입니다. `EMBED_MODEL`을 바꾸면 재시작 시 캐시가 자동으로 비워지므로 별도 무효화 절차는 불필요합니다.
>
> **인덱싱은 이 캐시를 우회합니다(§10.9.4)**: 청크 텍스트는 문서당 한 번만 쓰이고 사실상 재사용되지 않으므로, 인덱싱 경로(`add()`)는 캐시를 거치지 않고 임베딩 모델을 직접 호출합니다 — 대량 문서(500+ 청크)를 인덱싱해도 그 직전까지 캐시에 쌓여 있던 검색 질의 임베딩이 밀려나지 않습니다. 캐시는 오직 검색 질의(`search()`/`searchBatch()`)에만 적용됩니다. 캐시 키 자체도 질의 원문 대신 SHA-256 해시를 사용해 엔트리 크기가 질의 길이와 무관하게 고정됩니다.

#### PPTX 이미지 추출 튜닝

`PptxImageExtractor`가 슬라이드에서 그림/도형을 추출·래스터라이즈하는 방식을 제어합니다.

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `PPTX_IMAGE_MIN_SHAPE_DIMENSION_PT` | `30` | 도형의 가로/세로 중 큰 쪽이 이 값(포인트) 미만이면 아이콘/구분선으로 보고 래스터라이즈 대상에서 제외 |
| `PPTX_IMAGE_CLUSTER_PROXIMITY_PADDING_PT` | `5` | 근접 클러스터링 판정 시 각 도형 바운딩박스에 적용할 바깥쪽 패딩(포인트) — 커넥터가 도형 사이 '틈'에 있어도 하나로 묶이도록 함.<br>**`PPTX_IMAGE_RASTERIZE_SHAPES` 값과 무관하게 항상 적용됨** — `true`면 `rasterizeWithClustering()`의 느슨한 도형 근접 클러스터링에, `false`(기본)여도 `mergeAnnotatedPictures=true`일 때 사진/표 앵커에 겹친 loose seed를 찾는 `overlappingLooseSeeds()`가 동일하게 이 패딩을 사용함. 둘 다 꺼졌을 때만(`RASTERIZE_SHAPES=false` **and** `MERGE_ANNOTATED_PICTURES=false`) 미사용 |
| `PPTX_IMAGE_MERGE_ANNOTATED_PICTURES` | `true` | `true`(기본) — 사진 위/근처에 겹친 주석 도형(강조 원·화살표·말풍선)을 사진과 하나의 합성 PNG로 합침(앵커 기반, `RASTERIZE_SHAPES`와 **독립**). `false` — 사진은 항상 원본 그대로 추출(상세는 아래 참고) |
| `PPTX_IMAGE_RASTERIZE_SHAPES` | `false` | **"느슨한" 도형(사진/표/그룹 등 앵커에 안 겹친 선·화살표·텍스트없는 도형)끼리의 근접 클러스터링**을 제어. `false`(기본) — 클러스터링 안 함. `true` — 겹친 느슨한 도형들을 하나의 다이어그램 이미지로 병합(구 기본 동작). 그룹·SmartArt·표/사진 합성은 이 값과 무관하게 항상 유지(상세는 아래 참고) |
| `PPTX_REMOVE_DUPLICATE_SLIDES` | `true` | PPTX → MD 변환 시 **이미지 없는** 슬라이드에 한해 중복/목차형 슬라이드를 제거(`PptxToMarkdownConverter`). `true`(기본) — ① 정규화 텍스트가 앞선 슬라이드와 완전히 같으면 첫 등장만 남기고 드롭, ② 본문 불릿이 다른 슬라이드 제목들과 3개 이상·60% 이상 일치하면 목차/agenda 슬라이드로 보고 드롭. 이미지가 있는 슬라이드는 제거하지 않으며(이미지 고아 방지), `[페이지: N]` 번호는 밀리지 않음. `false` — 모든 슬라이드 유지. 변경 후 재인덱싱 필요 |
| `PPTX_DROP_DIVIDER_SLIDES` | `true` | PPTX → MD 변환 시 본문·이미지 없이 **구분용 제목만** 있는 섹션 구분 슬라이드를 제거(`PptxToMarkdownConverter`). `true`(기본) — 제목이 번호/라벨형(`3장`·`PART 2`·`제1절`·`STEP 3`·`II.`·`1)`·`부록 A`), 구분 키워드(`목차`·`개요`·`서론`·`결론`·`요약`·`agenda`·`overview`·`summary`…), 또는 짧은 명사구(≤3단어·≤12자, 조사·서술어 없음)일 때만 드롭. 문장형/키 메시지 제목(`… 합니다`·`… 다`·`… 요`·`.`로 끝나거나 `은/는/이/가/을/를` 조사 포함)은 유지. 본문·이미지가 있는 슬라이드는 영향 없음. `false` — 제목만 있는 슬라이드도 유지. 변경 후 재인덱싱 필요 |

> `PPTX_REMOVE_DUPLICATE_SLIDES`: 섹션마다 반복되는 동일 목차 슬라이드나 완전 동일한 백업 슬라이드가 검색 인덱스에 중복 청크로 남는 것을 막습니다. 목차형 판정(②)은 절대 개수(3개)와 비율(60%)을 모두 요구해 보수적이지만, 제목 여러 개를 나열하는 진짜 본문 슬라이드가 드물게 오탐될 수 있으니 그런 경우 `false`로 끄고 재인덱싱하세요. `DEBUG` 로그에 제거된 슬라이드 번호와 사유(중복/목차형)가 남습니다.
> `PPTX_DROP_DIVIDER_SLIDES`: "PART 2"·"3장 개요"·"결제 시스템"처럼 내용 없는 섹션 표지 슬라이드가 검색에 아무 답도 주지 못한 채 인덱스 슬롯만 차지하는 것을 막습니다. 문장형 제목만 있는 "키 메시지" 슬라이드(예: "고객 만족을 최우선으로 합니다")는 실제 내용으로 보고 유지하지만, 판정은 휴리스틱이라 드물게 어긋날 수 있으니 구분 표지가 검색에 필요하거나 키 메시지가 사라지면 `false`로 끄고 재인덱싱하세요. 제거된 슬라이드는 `DEBUG` 로그에 사유(구분용 제목)와 함께 남습니다.
> `PPTX_IMAGE_MERGE_ANNOTATED_PICTURES=false`는 화면 캡처 위의 강조 표시를 원본 사진과 분리해서 보관하고 싶을 때(예: 원본 사진을 다른 용도로 재사용) 사용합니다. 변경 후에는 기존 PPTX 문서를 재인덱싱해야 반영됩니다. (PPTX에서 실제로 Ctrl+G로 그룹핑된 사진+도형만 여전히 하나로 합쳐지는데, 이는 POI가 그룹을 통째로 그리는 자체 동작이라 옵션과 무관하게 항상 그렇게 동작합니다.)
> `PPTX_IMAGE_RASTERIZE_SHAPES`: 기본값 `false`에서는 겹친 도형들이 무의미하게 한 이미지로 뭉치던 동작이 사라집니다 — 대신 저자가 의도적으로 묶은 그룹(Ctrl+G)·SmartArt, 표 위 강조 도형, 사진 위 주석만 이미지로 남습니다. 다이어그램을 여러 도형으로(그룹핑 없이) 그린 슬라이드를 하나의 이미지로 캡처하고 싶으면 `true`로 켜세요. 표는 이 옵션과 무관하게 항상 MD 파이프 표로도 들어갑니다(표 위 겹친 도형이 있으면 그 표+도형 합성 이미지가 **추가로** 생성). 변경 후 재인덱싱 필요.

#### DOCX 이미지 추출 튜닝

`DocxAnnotationShapeMerger`가 DOCX 사진 위에 겹친 주석 도형을 사진과 합성하는 방식을 제어합니다.

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `DOCX_IMAGE_MERGE_ANNOTATED_SHAPES` | `false` | **[실험적 기능]** `false`(기본) — 항상 원본 사진만 그대로 추출(도형 무시). `true` — 사진과 **같은 문단**에 있는 레거시 VML 도형(`v:rect`/`v:oval`/`v:roundrect`/`v:line`)을 사진 위에 합성. 좌표 근사 방식이라 위치가 어긋날 수 있어 `false`가 더 안전함(상세는 아래 참고) |

> **PPTX와의 차이 — 근사 방식**: POI의 WordprocessingML 모델에는 도형 좌표 API도 렌더러도 없어(그림 위치 자체가 노출되지 않음) PPTX처럼 진짜 기하학적 겹침을 판정할 수 없습니다. 대신 "같은 문단에 사진과 도형이 함께 있으면 겹친 주석"으로 간주하는 근사 휴리스틱을 사용합니다 — 화면 캡처 위에 강조 원/화살표를 그리면 Word가 보통 같은 문단에 앵커하므로 실무에서는 대부분 맞아떨어집니다. 최신 Word의 "도형 삽입"(DrawingML `wps:wsp`)은 POI에 타입 바인딩이 아예 없어 지원되지 않고, 레거시 VML 형태만 인식합니다. 도형 위치(`style` 속성)를 해석할 수 없거나 사진이 EMF/WMF인데 PNG 변환이 비활성/실패한 경우에는 합성을 포기하고 원본 사진만 추출합니다(조용한 폴백). 한 문단에 사진이 여러 장이면 첫 사진에만 합성을 시도합니다. 변경 후에는 기존 DOCX 문서를 재인덱싱해야 반영됩니다.

#### PPTX 도형 텍스트 마커

`PptxToMarkdownConverter`가 그룹 도형·SmartArt·차트에서 뽑아낸 텍스트는 별도 설정 없이 항상 대괄호 마커로 감싸져 저장·검색됩니다 — `/admin` 청크 뷰나 검색 결과에서 아래 마커를 보게 되면 도형에서 추출된 텍스트라는 뜻입니다.

| 마커 | 도형 종류 | 설명 |
|------|----------|------|
| `[다이어그램]` 또는 `[다이어그램 N]` ... `[/다이어그램]`/`[/다이어그램 N]` | SmartArt(`XSLFDiagram`) | 박스·라벨 텍스트가 여는·닫는 마커 사이에 한 블록으로 묶여 저장됨 |
| `[도형 그룹]` 또는 `[도형 그룹 N]` ... `[/도형 그룹]`/`[/도형 그룹 N]` | 일반 그룹 도형(`XSLFGroupShape`) | 그룹 안의 텍스트 상자들이 한 블록으로 묶여 저장됨 |
| `[차트: 제목]` 또는 `[차트 N: 제목]` | 차트 프레임 | 차트 제목만 인라인 라벨로 저장됨(시리즈·축 값은 추출하지 않음) |

한 슬라이드에 같은 종류(그룹/다이어그램/차트)가 2개 이상이면 라벨에 순번(N)이 붙어 서로 구분됩니다(`[도형 그룹 1]`, `[도형 그룹 2]` 등) — 1개뿐이면 번호 없이 기존과 동일하게 표시됩니다. 텍스트가 하나도 없는 순수 장식 그룹은 마커 자체가 생성되지 않습니다. `[이미지: ...]`와 마찬가지로 이 마커는 저장·표시 텍스트에 그대로 남아 임베딩·FTS·답변 프롬프트에 반영되며, `#`가 아니라 `[`로 시작해 섹션 헤딩으로 오인되지 않습니다.

그 도형이 소유한 이미지(래스터라이즈된 PNG 또는 차트의 `mc:Fallback` 미리보기 그림)는 슬라이드 상단이 아니라 해당 마커 블록 안, 여는 마커 바로 다음에 `[이미지: ...]`로 삽입됩니다 — 어떤 이미지가 어떤 도형/차트에서 나왔는지 마커만으로 바로 알 수 있습니다. 소유 도형이 없는 일반 사진(그룹/다이어그램/차트에 속하지 않은 사진, OLE 미리보기 등)은 기존처럼 슬라이드 상단에 모아 표시됩니다. 다음 정리 동작도 별도 설정 없이 항상 적용됩니다:
- 같은 그룹 안에서 서로 다른 도형의 텍스트 내용이 완전히 같으면(강조 마커·공백 차이 무시) 하나만 남깁니다.
- 본문에서 직전 줄과 내용이 같은 줄이 연속되면 하나만 남깁니다(비연속 반복은 그대로 유지).
- 표 셀 안의 줄바꿈(Shift+Enter)은 공백으로 치환되어 파이프 표 행이 깨지지 않습니다.
- 슬라이드 하나의 최종 본문(본문+표+그룹 텍스트 통합 기준)에 볼드(`**`)가 10개 이상이면 과도한 강조로 보고 전부 제거합니다(이탤릭은 대상 아님).
- 이와 별개로 도형 그룹·표 하나만 놓고도 같은 판정을 한 번 더 적용합니다 — 그 블록 안의 볼드 스팬이 6개 이상이거나 볼드로 덮인 글자 비율이 50% 이상이면 그 블록만 볼드를 전부 제거합니다. 볼드가 도형 그룹/표 하나에만 몰려 있어 슬라이드 전체 개수는 10 미만인 경우(예: 표 셀 6개만 전부 볼드)를 놓치지 않기 위한 보완 규칙입니다.
- MD 교정(LLM 포맷 교정) 단계의 섹션 분할도 DOCX와 다릅니다 — 일반 헤딩(`##`/`###`/`####`) 기준이 아니라 `[페이지: N]` 마커 기준으로 나뉘어, 슬라이드 하나가 헤딩을 2개(`##`+`###`) 가져도 한 슬라이드가 쪼개지지 않습니다. 여기에 더해 작은 슬라이드는 최대 4장까지 하나의 교정 호출로 묶어 LLM 왕복을 줄이고, 반대로 한 슬라이드가 너무 크면 그 슬라이드만 `[도형 그룹]`/`[다이어그램]`/`[차트]` 블록 경계로 쪼갭니다.

기존에 인덱싱된 PPTX 문서에는 소급 적용되지 않으므로, 위 동작이 반영되길 원하면 재업로드하거나 `POST /api/v1/documents/sync`로 재동기화하세요. 상세 구현은 [PIPELINE.md §6.3-bis](PIPELINE.md#63-bis-pptxpdf비스캔--md-변환--docx와의-차이점) 참고.

#### LLM 응답 파라미터

| 변수 | 기본값 | 권장 범위 | 설명 |
|------|--------|----------|------|
| `LLM_MAX_TOKENS` | `6000` | 1000 ~ 32000 | 단일 진실 소스(`app.llm.max-tokens`)로 통일되어, 이 값을 바꾸면 **아래 세 곳 모두**가 함께 움직입니다: (1) **블로킹 LLM 응답 토큰 상한** — 인덱싱·분류·키워드·Direct 블로킹 호출에 적용(스트리밍 채팅 답변은 의도적으로 미적용, SSE 타임아웃이 폭주 방지). (2) **대화 컨텍스트 문자 예산**(`MemoryService`, `×0.5`로 히스토리 예산 산출). (3) **MD 교정 섹션 크기**(`MarkdownCorrectionService`).<br>§6.18 이전에는 (1)이 코드에 `6000`으로 하드코딩돼 이 환경변수와 무관하게 동작했고, (2)·(3)은 별도의 죽은 프로퍼티(`spring.ai.openai.chat.options.max-tokens`, 기본 `8000`)를 읽어 (1)과 다른 값을 썼습니다 — 이제 세 곳 모두 `app.llm.max-tokens` 하나만 읽습니다 |
| `LLM_TEMPERATURE` | `0.0` | 0.0 ~ 2.0 | 일반/RAG 및 Direct를 제외한 모든 LLM 호출의 무작위성 제어(`app.llm.temperature`). `0.0`은 결정적 답변, 높을수록 다양·창의적.<br>**§6.18 이전에는 코드에 `0.0`으로 하드코딩돼 이 값이 무시되는 죽은 설정이었으나, 이제 실제로 적용됩니다** — 과거에 이 값을 설정해 둔 운영자는 이번부터 처음으로 효과가 나타납니다(기본 `0.0`이면 체감 변화 없음). 프로바이더 빈 생성 시점에 고정되므로 변경하려면 재기동 필요 |
| `DIRECT_LLM_TEMPERATURE` | `0.1` | 0.0 ~ 0.2 | **Direct(meta) 응답 전용** temperature(`app.llm.direct-temperature`) — 인사·잡담 등 RAG를 안 쓰는 직접 응답은 약간의 다양성이 자연스러워 일반 temperature와 분리(§6.18). `[0.0, 0.2]`로 clamp. **핫 수정 가능** — `/settings`에서 재기동 없이 다음 Direct 호출부터 반영(`DirectAnswerService`가 매 호출 재조회) |

#### 로그 레벨

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `LOGGING_LEVEL` | `INFO` | 앱 전체 로그 레벨을 일괄 설정합니다. `com.example.ragagent`(앱 코드), `org.springframework.ai.openai`(Spring AI 내부), `reactor.netty.http.client`(HTTP 와이어 로그) 세 로거가 이 값을 공유합니다.<br>• `INFO` — 인덱싱 시작/완료, 동기화 결과, 프로바이더 등록 이벤트만 출력 (운영 환경 기본값)<br>• `DEBUG` — 에이전트 흐름(Classifier→Retrieval→Answer→Critic), 프로바이더 라우팅 결정, LLM 요청 curl 재현 명령 출력. **프롬프트 원문·검색 문서·대화 이력이 포함**되므로 운영 환경에서는 사용 금지<br>• `TRACE` — HTTP body 바이트 전체 출력 (SSE 청크 포함, 매우 방대). 짧은 디버깅 후 즉시 해제 권장<br>재시작 없이 레벨을 바꾸려면 Actuator 사용 → [§8 런타임 레벨 변경](#8-문제-해결) 참조 |
| `SPRING_SECURITY_LOGGING_LEVEL` | `WARN` | Spring Security 로그 레벨 (`logging.level.org.springframework.security`). `DEBUG`로 변경하면 인증 필터·세션 생성·권한 결정 과정이 상세하게 출력됨. 인증 이슈 디버깅 시에만 임시 사용 권장 |

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
# LOCAL_LLM_KEY를 비워도 LOCAL provider는 등록됩니다(로컬 엔드포인트는 키 불필요).
# 로컬 LLM이 없으면 외부 우선 라우팅으로 LOCAL을 후순위에 두세요
# (완전 제외는 application.properties의 providers[1] 주석 처리):
LLM_ROUTING_MODE=QUALITY_FIRST
```

> 멀티 프로바이더 구성은 [§5 LLM 프로바이더 설정](#5-llm-프로바이더-설정)을 참고하세요.

---

### 3.3 application.properties 전용 설정

환경변수로 주입할 수 없는 설정입니다. 변경이 필요하면 `src/main/resources/application.properties`를 직접 편집 후 재시작하세요.

> 환경변수로 제어 가능한 설정(RAG 튜닝, 타임아웃, 로그 레벨 등)은 [§3.2 환경변수 전체 목록](#32-환경변수-전체-목록)을 참조하세요.

#### 이미지 처리 (`app.image-description.*`)

| 속성 | 기본값 | 설명 |
|------|--------|------|
| `app.image-description.enabled` | `true` | 검색 시점 Lazy Vision(`LazyVisionService`) 활성화 여부. `false`이면 이미지 마커만 저장하고 검색 시 LLM 호출 없음 |
| `app.image-description.mode` | `strip` | `strip`: 이미지 마커를 텍스트에서 제거 / `describe`: Vision LLM으로 설명 생성 후 삽입 |
| `app.image-description.classify-type` | `true` | 이미지 설명 전 유형(사진/도표/스크린샷 등) 분류 여부. 분류 결과를 프롬프트에 주입 |
| `app.image-description.ocr-enabled` | `true` | 스캔 PDF 페이지에 대해 OCR 처리 활성화 여부 |
| `app.image-description.min-image-bytes` | `1000` | 이 크기 미만의 이미지는 아이콘·구분선으로 간주하고 설명 생성 건너뜀 (바이트) |
| `app.image-description.docx-emf-convert` | `true` | DOCX 내 EMF 벡터 이미지를 PNG로 변환 (Apache Batik — 추가 설치 불필요) |
| `app.image-description.docx-wmf-convert` | `false` | DOCX 내 WMF 벡터 이미지를 PNG로 변환 (LibreOffice headless 필요 — EMF보다 변환 품질이 낮아 기본 비활성) |

> **`mode=describe` 전제 조건**: `enabled=true` + Vision 모델 프로바이더 등록 (`type=VISION` 또는 `type=LIGHT_BOTH`).  
> 프로바이더가 없으면 `strip`으로 자동 fallback됩니다.

> **EMF/WMF 변환**: LibreOffice(`soffice`)가 PATH에 있어야 합니다. 없으면 변환이 건너뛰어지며 `[TIMEOUT:LIBREOFFICE]` 로그가 출력됩니다.

> **인덱싱 시점 즉시 설명 생성**은 프로퍼티가 아니라 문서 업로드 화면의 "이미지 설명 추가" 체크박스로 제어됩니다
> (DOCX·TXT·MD 한정). 여기 표의 설정들은 모두 검색 시점 Lazy Vision에 대한 것입니다. 자세한 내용은
> `documents/IMAGE_PROCESS.md` 5절·12절 참고.

#### 소제목 숫자 생성 (`addHeadingNumbers`)

프로퍼티가 아니라 문서 업로드 화면의 "소제목 숫자 생성" 체크박스로 제어되는 요청 단위 옵션입니다. 켜면 LLM
섹션 교정이 모두 끝난 뒤 2차 패스로 H2~H6 헤딩에 계층적 번호(`## 1.1 제목`처럼)를 매기고, 라벨 없는 코드
블록의 언어 태그를 재추론합니다(`MarkdownCorrectionService.addHierarchicalHeadingNumbers()`).

> **PPTX는 항상 무시됩니다**: 체크박스 상태와 무관하게 PPTX 업로드는 이 옵션이 절대 적용되지 않습니다.
> PPTX의 `##`/`###` 헤딩은 슬라이드 제목·부제목 라벨(최대 2단계, 슬라이드마다 계산)일 뿐 문서 목차 같은
> 계층 구조가 아니라서, 순번을 매기면 실제 구조와 무관한 숫자만 붙고 이미 있는 `[페이지: N]` 마커와도
> 겹쳐 혼란을 줍니다.

> **MD 재인덱싱 시 자동 재검증**: `/admin` ↺ 버튼으로 재인덱싱하면, 저장된 MD에 번호 매겨진 헤딩이 하나
> 라도 있을 때만 현재 헤딩 구조 기준으로 전체 번호를 다시 계산해 파일에도 반영합니다
> (`MarkdownCorrectionService.reapplyHeadingNumbers()`, LLM 호출 없음) — 코드 블록 편집 등으로 헤딩이
> 추가·삭제·이동돼 번호가 어긋난 경우를 바로잡습니다. 번호가 원래 없던 문서(체크 해제 상태로 업로드됐거나
> PPTX)는 재인덱싱해도 새로 번호가 붙지 않습니다. 자세한 내용은 [§7.3 주의사항](#73-주의사항)과
> [PIPELINE.md §6.3](PIPELINE.md#63-docx--md--임베딩-db-저장-상세-이미지-포함) 참고.

#### LLM 응답 파라미터

> **temperature와 최대 출력 토큰**은 각각 `LLM_TEMPERATURE`, `LLM_MAX_TOKENS` 환경변수로 설정할 수 있습니다(§6.18로 실제 적용되도록 수정됨). Direct(잡담) 응답만 별도 `DIRECT_LLM_TEMPERATURE`(기본 0.1)를 쓰며 `/settings`에서 핫 수정 가능합니다. → [§3.2 LLM 응답 파라미터](#32-환경변수-전체-목록) 참조

#### 업로드 크기 제한

| 속성 | 기본값 | 설명 |
|------|--------|------|
| `spring.servlet.multipart.max-file-size` | `200MB` | 단일 파일 최대 크기. 초과 시 413 응답 |
| `spring.servlet.multipart.max-request-size` | `200MB` | 멀티파트 요청 전체 최대 크기 |

#### Rate Limiting (`app.rate-limit.*`)

애플리케이션 레벨 Rate Limiter (Bucket4j + Caffeine). 사용자 인증 시 userId, 미인증 시 IP 기준으로 버킷 분리.

| 속성 | 기본값 | 설명 |
|------|--------|------|
| `app.rate-limit.enabled` | `true` | `false`로 설정하면 전체 비활성화 |
| `app.rate-limit.chat-per-minute` | `60` | `/chat` 경로 — 분당 요청 수 |
| `app.rate-limit.upload-per-minute` | `10` | `/documents` (업로드) 경로 — 분당 요청 수 |
| `app.rate-limit.sync-per-minute` | `3` | `/documents/sync` 경로 — 분당 요청 수 |
| `app.rate-limit.image-per-minute` | `300` | `/images/` 경로 — 분당 요청 수 |
| `app.rate-limit.default-per-minute` | `120` | 그 외 경로 기본값 |

초과 시 429 응답 + `Retry-After: {초}` 헤더 + `{"errorCode":"RAG-RATE-001","message":"..."}` body.

#### 감사 로그 (`app.audit.*`)

민감 작업(문서 업로드·삭제·동기화, 스레드 삭제 등)을 `data/audit/audit.log`에 JSON Lines 형식으로 기록.

| 속성 | 기본값 | 설명 |
|------|--------|------|
| `app.audit.enabled` | `true` | `false`로 설정하면 감사 로그 미기록 |
| `app.audit.max-file-size` | `10MB` | 롤링 전 최대 파일 크기 (Logback SizeAndTimeBasedRolling) |
| `app.audit.max-history-days` | `7` | 압축 파일 보관 일수. 초과된 파일 자동 삭제 |
| `app.audit.total-size-cap` | `100MB` | `data/audit/` 디렉터리 전체 상한. 초과 시 오래된 파일 삭제 |

#### 인증 (`app.auth.*`)

| 속성 | 기본값 | 설명 |
|------|--------|------|
| `app.auth.enabled` | `true` | `false`로 설정하면 로그인 없이 실행 (no-auth 모드). `AUTH_ENABLED` 환경변수로도 주입 가능. 자세한 내용은 [§9.4](#94-인증-토글-no-auth-모드) 참조 |
| `app.auth.management-only` | `false` | `app.auth.enabled=false`일 때만 의미 있는 서브모드. `true`이면 채팅·조회는 게스트에 열어두고 `/admin/**`·문서 관리 쓰기만 로그인을 요구한다. `AUTH_MANAGEMENT_ONLY` 환경변수로도 주입 가능. `app.auth.enabled=true`와 동시 설정 시 자동으로 `false`로 정규화됨. 자세한 내용은 [§9.4.2](#942-관리-전용-인증-management-only) 참조 |

#### 서버 및 기타

> `server.port`는 `SERVER_PORT` 환경변수로 주입 가능하므로 [§3.2 API 키 / 연결 정보](#32-환경변수-전체-목록)를 참조하세요. 아래 표는 환경변수로 주입할 수 없어 `application.properties` 직접 편집이 필요한 항목만 다룹니다.

| 속성 | 기본값 | 변경 가능 여부 | 설명 |
|------|--------|--------------|------|
| `spring.threads.virtual.enabled` | `true` | ⚠️ 변경 비권장 | Java 21 Virtual Thread 활성화. LLM I/O 동시성에 핵심적 |
| `spring.datasource.hikari.maximum-pool-size` | `1` | ❌ 변경 금지 | SQLite는 동시 쓰기 불가 — 반드시 1 유지 |
| `spring.autoconfigure.exclude` | Chroma 자동구성 제외 | ❌ 변경 금지 | `VectorStoreRegistry`가 직접 Chroma 빈을 관리. 제거 시 충돌 |

---

## 4. 실행 방법

### 4.1 Docker Compose (권장)

#### 4.1.1 ChromaDB 백엔드

```bash
# 1. 환경변수 설정
cp .env.example .env
# .env 파일 편집

# 2. Chroma 프로파일로 실행
docker compose --profile chroma up --build -d

# 3. 상태 확인
docker compose ps

# 4. 로그 확인
docker compose logs -f app

# 5. 종료
docker compose down
```

> `docker-compose.yml`에서 `CHROMA_HOST=chroma`, `CHROMA_PORT=8000`이 app 컨테이너에 자동 주입됩니다.
> Chroma healthcheck 통과 후 app 컨테이너가 시작됩니다.

#### 4.1.2 sqlite-vec 백엔드

```bash
# 1. sqlite-vec 예시 환경파일 복사
cp .env.example.sqlite .env
# .env 파일에서 SQLITE_VEC_EXTENSION_PATH / EMBED_DIMENSIONS 수정

# 2. 실행 (외부 Chroma 컨테이너 불필요)
docker compose up --build -d

# 3. 상태 확인
docker compose ps

# 4. 로그 확인 (vec0 로딩/검증 로그 확인)
docker compose logs -f app

# 5. 종료
docker compose down
```

> sqlite-vec 모드에서는 `SQLITE_VEC_EXTENSION_PATH`에 **컨테이너 내부에서 접근 가능한 경로**를 지정해야 합니다.
> Docker에서는 플랫폼에 맞는 vec0 바이너리를 볼륨으로 마운트하고 경로를 지정하세요.

---

### 4.2 로컬 실행

#### 4.2.1 ChromaDB 백엔드

**macOS — Docker**

```bash
# 1. Chroma 서버 실행 (별도 터미널)
docker run -d --name chroma-server -p 8001:8000 \
  -v "$(pwd)/data/chroma:/chroma/chroma" \
  chromadb/chroma:latest

# 2. 로그 확인
docker logs -f chroma-server
```

```bash
# 3. 환경변수 로드
export $(grep -v '^#' .env | xargs)

# 4. 애플리케이션 실행
mvn spring-boot:run
```

**macOS — Apple Container (Apple Silicon 권장)**

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

**Ubuntu (Linux)**

```bash
docker run -d --name chroma-server -p 8001:8000 \
  -v "$(pwd)/data/chroma:/chroma/chroma" \
  chromadb/chroma:latest &
set -a && source .env && set +a
mvn spring-boot:run
```

**Windows (CMD)**

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

#### 4.2.2 sqlite-vec 백엔드

> sqlite-vec는 Chroma 서버를 띄우지 않습니다. 핵심은 `SQLITE_VEC_EXTENSION_PATH`와 `EMBED_DIMENSIONS`를 정확히 맞추는 것입니다.

**macOS / Linux**

```bash
# 1. sqlite-vec 예시 환경파일 사용
cp .env.example.sqlite .env

# 2. 환경변수 로드
set -a && source .env && set +a

# 3. 앱 실행
mvn spring-boot:run
```

**Windows (CMD)**

```cmd
REM 1. sqlite-vec 예시 환경파일 사용
copy .env.example.sqlite .env

REM 2. 환경변수 로드
for /f "usebackq tokens=1,* delims==" %A in (`findstr /v "^#" .env`) do SET %A=%B

REM 3. 앱 실행
mvn spring-boot:run
```

**Windows PowerShell**

```powershell
Copy-Item .env.example.sqlite .env -Force
Get-Content .env | Where-Object { $_ -notmatch '^#' -and $_ -match '=' } |
  ForEach-Object { $k,$v = $_ -split '=',2; [System.Environment]::SetEnvironmentVariable($k,$v,'Process') }
mvn spring-boot:run
```

> Windows 로컬 예시 경로는 `.env.example.sqlite`의 `SQLITE_VEC_EXTENSION_PATH=./lib/win64/vec0.dll`를 참고하세요.
> 운영 환경에서는 실제 vec0 바이너리 위치로 변경해야 하며, 시작 로그에서 vec0 로딩 성공(`vec_version()`)을 확인하세요.
> macOS에서 `dlopen` 실패로 기동이 안 되면 §3.1의 "macOS 격리(quarantine) 주의" 참조.

---

### 4.3 접속 확인

- **Web UI**: `http://localhost:8080`
- **API 헬스 체크**: `http://localhost:8080/api/v1/health` → `{"status":"ok",...}`

---

### 4.4 HTTPS 설정 (Caddy 리버스 프록시)

이 프로젝트는 **Caddy**를 리버스 프록시로 사용해 HTTPS를 처리합니다.  
Caddy는 Let's Encrypt 인증서를 자동으로 발급·갱신하므로 별도의 certbot·cron 작업이 없습니다.

```
인터넷
  │  HTTPS :443
  ▼
[Caddy 컨테이너]   ← TLS 종료 (인증서 자동 관리)
  │  HTTP :8080 (내부 Docker 네트워크)
  ▼
[Spring Boot 컨테이너 (app)]
```

외부에 8080 포트는 노출되지 않습니다. Caddy만 80/443을 받습니다.

---

#### 전제: Spring Boot 측 설정 (이미 적용됨)

```properties
# application.properties — 이미 설정되어 있음
server.forward-headers-strategy=framework
server.tomcat.remoteip.protocol-header=X-Forwarded-Proto
server.servlet.session.cookie.secure=true
server.servlet.session.cookie.http-only=true
server.servlet.session.cookie.same-site=lax
```

Caddy → Spring Boot 간 `X-Forwarded-Proto: https` 헤더가 전달되고,  
Spring이 이를 인식해 `isSecure() = true`, JSESSIONID는 HTTPS 전용으로 전송됩니다.

---

#### 도메인 확보 방법 — 세 가지 선택지

도메인이 없어도 무료로 HTTPS를 적용할 수 있습니다. 상황에 맞는 방법을 선택하세요.

| 상황 | 추천 방법 |
|------|---------|
| 서버에 공인 IP가 있고 포트(80/443) 개방 가능 | **옵션 1**: DuckDNS 무료 서브도메인 |
| 방화벽 안 / 공인 IP 없음 / 포트포워딩 불가 | **옵션 2**: Cloudflare Tunnel |
| 나 혼자만 쓰는 로컬 환경 | **옵션 3**: HTTP 그대로 사용 |

---

#### 옵션 1 — DuckDNS 무료 서브도메인 (공인 IP 보유 시 권장)

**1단계 — DuckDNS 서브도메인 생성**

1. [duckdns.org](https://www.duckdns.org) 접속 → GitHub/Google 계정으로 로그인
2. 원하는 서브도메인 입력 (예: `myrag`) → **add domain** 클릭
3. **current ip** 란에 서버의 공인 IP 입력 → **update ip** 클릭
4. 발급된 도메인: `myrag.duckdns.org`

> 서버 IP가 유동 IP라면 아래 갱신 스크립트를 cron에 등록하세요.
> ```bash
> # /etc/cron.d/duckdns  (5분마다)
> */5 * * * * root curl -s "https://www.duckdns.org/update?domains=myrag&token=YOUR_TOKEN&ip=" > /dev/null
> ```
> 토큰은 DuckDNS 대시보드 상단에 표시됩니다.

**2단계 — Caddyfile 작성**

프로젝트 루트에 `Caddyfile` 생성:

```
myrag.duckdns.org {
    reverse_proxy app:8080
    encode gzip zstd

    header {
        Strict-Transport-Security "max-age=31536000; includeSubDomains"
        X-Content-Type-Options "nosniff"
        Referrer-Policy "strict-origin-when-cross-origin"
    }
}
```

**3단계 — docker-compose.yml에 Caddy 서비스 추가**

```yaml
services:
  caddy:
    image: caddy:2-alpine
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
      - "443:443/udp"   # HTTP/3
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy_data:/data       # 인증서 저장 (재시작 후에도 유지)
      - caddy_config:/config
    depends_on:
      - app

  app:
    # 기존 app 서비스 — ports 항목에서 "8080:8080" 제거 (외부 노출 불필요)
    ports: []

volumes:
  caddy_data:
  caddy_config:
```

> `app` 서비스의 `ports: ["8080:8080"]`은 **제거**하세요. 외부에서 직접 8080으로 접근을 차단해야 합니다.

**4단계 — 실행**

```bash
docker-compose up --build -d
```

Caddy가 시작되면서 Let's Encrypt에 인증서를 자동 요청합니다 (수십 초 소요).  
이후 `https://myrag.duckdns.org` 로 접속하면 됩니다.  
HTTP(`http://myrag.duckdns.org`)는 자동으로 HTTPS로 리다이렉트됩니다.

**인증서 갱신 확인**:
```bash
docker-compose logs caddy | grep -i "certificate\|renew\|tls"
```

---

#### 옵션 2 — Cloudflare Tunnel (도메인·포트포워딩 없이 인터넷 공개)

방화벽 안에 있거나 공인 IP가 없는 서버(집 PC, NAS, 사내 서버 등)에 적합합니다.  
Cloudflare 서버가 중간에서 연결을 중계하므로 서버에서 외부로 나가는 연결만 필요합니다.

**1단계 — Cloudflare 계정 및 Tunnel 생성**

1. [cloudflare.com](https://www.cloudflare.com) 무료 계정 생성
2. 대시보드 → **Zero Trust** → **Networks** → **Tunnels** → **Create a tunnel**
3. Tunnel 이름 입력 (예: `rag-agent`) → **Save tunnel**
4. **Docker** 탭 선택 → 표시된 토큰 복사 (`eyJ...` 형태)

**2단계 — docker-compose.yml에 cloudflared 추가**

```yaml
services:
  cloudflared:
    image: cloudflare/cloudflared:latest
    restart: unless-stopped
    command: tunnel --no-autoupdate run
    environment:
      - TUNNEL_TOKEN=${CLOUDFLARE_TUNNEL_TOKEN}
    depends_on:
      - app
```

`.env`에 토큰 추가:
```env
CLOUDFLARE_TUNNEL_TOKEN=eyJ...여기에_복사한_토큰_붙여넣기...
```

**3단계 — Tunnel 라우팅 설정**

Cloudflare 대시보드 → Tunnel 상세 → **Public Hostname** 탭:

| 항목 | 값 |
|------|-----|
| Subdomain | `rag` (원하는 값) |
| Domain | `*.trycloudflare.com` (무료) 또는 내 도메인 |
| Service | `http://app:8080` |

> **trycloudflare.com 주소**: 도메인 없이 `https://rag.trycloudflare.com` 형태로 즉시 사용 가능합니다.  
> 나중에 도메인을 구입해 Cloudflare에 연결하면 클릭 몇 번으로 도메인 교체 가능합니다.

**4단계 — 실행**

```bash
docker-compose up --build -d
```

Caddyfile 불필요. HTTPS 인증서는 Cloudflare가 자동 처리합니다.

**트래픽 흐름**:
```
브라우저 → Cloudflare 엣지 (HTTPS) → cloudflared 컨테이너 (암호화 터널) → app:8080
```

> **주의**: Cloudflare가 트래픽을 중계하므로 기밀 문서를 처리하는 환경이라면 옵션 1(자체 서버 직접 TLS)을 사용하세요.

---

#### 옵션 3 — 로컬 전용 (HTTPS 불필요)

자신만 사용하는 로컬 환경이라면 HTTP 그대로 사용해도 됩니다.  
세션 쿠키 탈취 위험이 없으므로 `cookie.secure` 설정만 조정합니다.

```properties
# application.properties
server.servlet.session.cookie.secure=false
```

Caddy 없이 `http://localhost:8080`으로 접속합니다.  
docker-compose.yml에서 `app` 서비스의 `ports: ["8080:8080"]`은 그대로 유지합니다.

---

#### 인증서 상태 확인 (옵션 1 사용 시)

```bash
# Caddy 로그에서 인증서 관련 이벤트 확인
docker-compose logs caddy | grep -iE "certificate|renew|tls|acme"

# Caddy 컨테이너 내부에서 인증서 만료일 확인
docker-compose exec caddy caddy trust
```

인증서는 `caddy_data` 볼륨에 저장됩니다. 볼륨을 삭제하면 인증서도 삭제되므로 주의하세요.

---

### 4.5 폐쇄망(Air-gapped) / 노-도커 실행

인터넷·Docker 없이 **sqlite-vec + 로컬 LLM(llama-server)** 만으로 운영하는 구성입니다. 현재 구현은 ChromaDB(유일한 필수 Docker 서비스) 없이도 동작하며(§3.1 "벡터 스토어 백엔드 선택"), 프론트엔드 자산은 jar에 번들된 webjar라 CDN 의존이 없습니다.

#### 1) (연결망에서) 산출물 준비

폐쇄망에는 빌드 도구를 들이지 않고 **산출물만 반입**합니다.

```bash
mvn clean package -DskipTests        # webjar·의존성 포함 실행 가능 jar 생성 → target/*.jar
```

반입 대상:
- `target/rag-agent-*.jar` (실행 가능 fat-jar)
- **JRE/JDK 21** (호스트에 미설치 시)
- **`vec0` 네이티브 확장** — 폐쇄망 호스트 OS/아키텍처(예: linux x86_64)용 loadable. <https://github.com/asg017/sqlite-vec/releases> 에서 사전 다운로드. **아키텍처 불일치 시 `SqliteVecVerifier`가 기동 시 fail-fast**
- (OCR 사용 시) **Tesseract + `kor`/`eng` tessdata**

#### 2) 로컬 LLM(llama-server) 기동

llama.cpp의 `llama-server`는 프로세스당 모델 1개를 제공하므로, **채팅용·임베딩용 2개 인스턴스**를 권장합니다.

```bash
llama-server -m chat-model.gguf   --port 8080               # 채팅
llama-server -m embed-model.gguf  --port 8081 --embeddings  # 임베딩 (/v1/embeddings)
```

#### 3) 환경변수 + 실행

> `.env`는 docker-compose 전용이라 **노-도커 실행 시 자동 로드되지 않습니다.** 셸에 export 하세요(`.env` 로드 방식 재사용 가능).

```bash
# 벡터 스토어: sqlite-vec (ChromaDB·Docker 불요)
export VECTORSTORE_TYPE=sqlite-vec
export SQLITE_VEC_EXTENSION_PATH=/opt/sqlite-vec/vec0     # 반입한 vec0 경로

# 로컬 LLM (llama-server) — LOCAL_LLM_KEY 불필요(비워도 등록됨)
export LOCAL_LLM_URL=http://127.0.0.1:8080/v1
export LOCAL_LLM_MODEL=<채팅 모델명>

# 임베딩 (별도 인스턴스)
export EMBED_BASE_URL=http://127.0.0.1:8081/v1
export EMBED_MODEL=<임베딩 모델명>
export EMBED_DIMENSIONS=768          # ★ sqlite-vec 필수: 임베딩 모델 실제 차원과 일치 (nomic=768, bge-m3=1024)

# 외부 호출 차단(권장) + 비-TLS HTTP 직노출
export LLM_ROUTING_MODE=LOCAL_ONLY
export USE_CADDY_REVERSE_PROXY_HTTPS=false

java -jar target/rag-agent-*.jar
```

#### 4) TLS / 리버스 프록시

Caddy는 Docker 컨테이너이자 Let's Encrypt(인터넷)에 의존하므로 폐쇄망·노-도커에 부적합합니다. 택일:

- **HTTP 직노출** — `USE_CADDY_REVERSE_PROXY_HTTPS=false`(세션 쿠키 `Secure` 해제, 안 그러면 HTTP에서 로그인 불가). 신뢰망 한정 권장.
- **사내 역프록시 / 사설 CA** — 조직 표준 프록시(nginx 등)에서 TLS 종료 후 `:8080`으로 포워딩. `server.forward-headers-strategy=framework`(기본)로 `X-Forwarded-*` 인식.

#### 5) 이미지 · OCR (로컬 모델 전제)

- **Vision 설명**: 기본 `app.image-description.mode=strip`(설명 생략, 모델 불요). 설명을 켜려면 로컬 vision 모델(예: llama-server에 llava 계열)을 VISION provider로 등록.
- **OCR**: 스캔 PDF 처리에만 사용. 네이티브 Tesseract + `kor`/`eng` tessdata 필요. 불요 시 `app.image-description.ocr-enabled=false`.

#### 6) 기동 확인 체크리스트

- [ ] 로그에 `Provider [local] disabled …` 경고 **없음** (있으면 LOCAL 미등록 — `LOCAL_LLM_URL` 확인)
- [ ] `SqliteVecVerifier`가 `vec_version()` 출력 (vec0 로드 성공)
- [ ] `EMBED_DIMENSIONS` 미설정 시 sqlite-vec 모드는 차원 필수 메시지로 기동 실패 → 값 설정
- [ ] 외부(인터넷) 소켓 시도 없음 — `LLM_ROUTING_MODE=LOCAL_ONLY` + 외부 프로바이더 키 전부 미설정
- [ ] `http://<host>:8080/api/v1/health` → `{"status":"ok"}`

> **데이터 이전**: 기존 Chroma 데이터를 sqlite-vec로 직접 복사하지 않습니다. 문서 원본이 `data/documents/`에 보존되므로 **전체 재인덱싱**(문서 재업로드 또는 `POST /api/v1/documents/sync`로 재동기화)으로 이전합니다.

### 4.6 태그 기반 검색 적용 전 수동 초기화 (프리릴리즈)

태그 기반 검색 스코프(엄격 필터 + sqlite 후보확대 보정) 적용 시점에는
정식 릴리즈 전 운영 정책에 따라 **DB 마이그레이션 없이 수동 초기화 후 재구성**을 기준으로 합니다.

#### 1) 작업 중지

먼저 앱/백엔드를 중지합니다.

```bash
# Docker Compose
docker compose down
```

```bash
# 로컬 실행 (포그라운드)
# 실행 중 터미널에서 Ctrl+C
```

#### 2) (선택) 백업

데이터 유실이 허용되지 않는 환경이면 초기화 전에 백업합니다.

```bash
# macOS / Linux
tar -czf backup-before-tag-scope-$(date +%Y%m%d-%H%M%S).tar.gz data
```

```powershell
# Windows PowerShell
$ts = Get-Date -Format "yyyyMMdd-HHmmss"
Compress-Archive -Path data -DestinationPath ("backup-before-tag-scope-" + $ts + ".zip")
```

#### 3) 수동 초기화 (기존 데이터 삭제)

공통 삭제 대상:
- `data/memory.db`
- `data/memory.db-wal`
- `data/memory.db-shm`
- `data/documents/`
- `data/converted/`
- `data/images/`

chroma 백엔드 추가 삭제 대상:
- `data/chroma/` (로컬 Chroma 경로 사용 시)
- Docker named volume `chroma_data` (compose volume 사용 시)

```bash
# macOS / Linux
rm -f data/memory.db data/memory.db-wal data/memory.db-shm
rm -rf data/documents data/converted data/images data/chroma
mkdir -p data/documents data/converted data/images data/chroma data/audit
```

```powershell
# Windows PowerShell
Remove-Item data/memory.db,data/memory.db-wal,data/memory.db-shm -Force -ErrorAction SilentlyContinue
Remove-Item data/documents,data/converted,data/images,data/chroma -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force data/documents,data/converted,data/images,data/chroma,data/audit | Out-Null
```

```bash
# Docker Compose에서 chroma named volume까지 초기화할 때만
docker volume rm rag-agent_chroma_data
```

> 볼륨 이름은 환경마다 다를 수 있습니다. `docker volume ls`로 실제 이름을 확인하세요.

#### 4) 재기동 + 초기 설정

```bash
# chroma 백엔드
docker compose --profile chroma up --build -d

# sqlite-vec 백엔드
docker compose up --build -d
```

- no-auth 모드(`AUTH_ENABLED=false`)면 `/setup`에서 관리자 계정을 다시 생성합니다.
- 문서를 재업로드하거나 `POST /api/v1/documents/sync`로 폴더를 재동기화해 전체 재인덱싱합니다.

#### 5) 태그 기반 검색 기능 검증 체크리스트

- [ ] 태그가 다른 문서 2개 이상 업로드 (예: `policy`, `billing`)
- [ ] 채팅에서 태그 미선택 질의 시 기존(version-only)과 동일 동작 확인
- [ ] 채팅에서 태그 선택 질의 시 선택 태그 문서만 Sources에 노출되는지 확인
- [ ] 존재하지 않는 태그 조합 질의 시 무필터 폴백 없이 결과 축소/부재가 유지되는지 확인
- [ ] sqlite-vec 모드에서 태그 필터 후 결과 부족 시 후보확대 보정 로그(`candidateK` 증가) 확인

> 현재 구현은 1차에서 AND 매칭을 기본으로 사용합니다. OR 매칭 전환은 후속 범위입니다.

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

기본값으로 소형 로컬 LLM(`providers[0]`, MICRO_TEXT 전담) + 로컬 LLM 1(`providers[1]`, LOCAL) + 외부 NORMAL/PREMIUM 5종(`providers[3]`~`[7]`)이 함께 등록되어 있습니다(§5.4 예제 6 참고).  
로컬 서버를 더 추가하거나(로컬 LLM 2, `providers[2]`) Vision 전용 모델을 쓰려면 `application.properties`에 providers 블록을 추가/수정하세요.

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
| `concurrency` | 정수 (예: `3`) | 이 프로바이더의 질의 경로 동시성 게이트 크기. 미설정 시 `LLM_DEFAULT_PROVIDER_CONCURRENCY`(기본 3) 사용. 서버의 실제 `--parallel` 값과 일치시킬 것 |

#### 프로바이더 활성화 게이트 (`LlmConfig`, G1~G3)

시작 시 각 `providers[N]` 항목은 세 단계 게이트를 통과해야 실제로 등록된다:

| 게이트 | 대상 | 조건 | 통과 못하면 |
|---|---|---|---|
| **G1** | 모든 역할 | `role=LOCAL`이면 `api-key`가 비어도 통과(로컬 엔드포인트는 키 불필요, `no-key`로 치환) — `NORMAL`/`PREMIUM`은 `api-key` 필수 | 시작 로그에 warn, 해당 프로바이더 미등록 |
| **G2** | 모든 역할 | `base-url`이 비어 있지 않아야 함 | 시작 로그에 warn, 해당 프로바이더 미등록 |
| **G3** | `role=LOCAL`만 | `GET {base-url}/v1/models` 호출 성공 + 응답에 `model` 값이 포함 | **애플리케이션 시작 자체가 실패**(Spring Boot가 비정상 종료) |

G1·G2는 "이 프로바이더를 조용히 빼고 계속 진행"이지만, **G3는 다르다** — LOCAL 프로바이더의 `base-url`이 설정돼 있는데 서버가 안 떠 있거나 모델명이 틀리면 앱이 아예 뜨지 않는다. 포트 오타나 모델명 오타를 배포 직후 채팅 중 발견하는 대신 기동 시점에 바로 잡기 위함이다. `app.llm.verify-local-models-on-startup`(`LLM_VERIFY_LOCAL_MODELS_ON_STARTUP`, 기본 `true`)로 끌 수 있다 — 로컬 서버가 앱보다 늦게 뜨는 배포 순서 레이스가 있는 환경 등 예외적인 경우에만 `false`로 설정할 것. `NORMAL`/`PREMIUM`(클라우드) 프로바이더는 G3 대상이 아니다.

#### stream 플래그

`stream` 속성은 서버 ↔ LLM API 구간의 호출 방식을 제어합니다. 브라우저 ↔ 서버 간 SSE 연결은 이 값과 무관하게 유지됩니다.

| 값 | 동작 | 적합한 상황 |
|----|------|------------|
| `true` (기본) | LLM 서버에 `stream: true`로 요청 — 토큰 생성 즉시 SSE로 전달 | 대부분의 클라우드 API, 표준 OpenAI 호환 서버 |
| `false` | LLM 서버에도 `stream: true`로 요청하되, 토큰을 내부 버퍼에 모아 완성 후 일괄 SSE 전달 — 브라우저에는 응답이 한 번에 표시됨 | `stream: false`(블로킹 API)를 지원하지 않는 로컬 LLM 서버 (LM Studio 등) |

> **주의**: 많은 로컬 LLM 서버(LM Studio 포함)는 `stream: false` 블로킹 모드를 제대로 처리하지 못하고 무한 대기합니다. 이 때문에 `stream=false`로 설정해도 내부적으로는 스트리밍 HTTP를 사용하며, 토큰을 모두 받은 뒤 일괄 전달하는 방식으로 동작합니다.

```properties
# 예시: local 프로바이더만 블로킹 방식으로 호출
app.llm.providers[1].stream=false
```

> 시작 로그에서 각 프로바이더의 stream 설정을 확인할 수 있습니다:  
> `local(LOCAL/BOTH/p1/stream=false) → http://localhost:1234/v1 [gemma-4-e4b]`

#### type 값

| type | 처리 가능 태스크 | 권장 모델 유형 |
|------|----------------|--------------|
| `MICRO_TEXT` | 키워드+맥락·요약·제목·쿼리 확장만 (추론 불필요) | 500MB급 소형 모델 (§6.21) |
| `LIGHT_TEXT` | 분류·직답 + `MICRO_TEXT` 잡무 | 텍스트 전용 소형~중형 모델 |
| `LIGHT_BOTH` | 분류·직답·`MICRO_TEXT` 잡무 + Vision | 범용 로컬 LLM |
| `TEXT` | 답변 생성·Critic·Rerank만 | 텍스트 전용 대형 모델 |
| `VISION` | 이미지 설명만 | Vision 전용 모델 |
| `BOTH` | 모든 태스크 | 외부 고성능 / 범용 대형 모델 |

#### role 값 (COST_FIRST 기준 시도 순서)

| role | 설명 | 순서 |
|------|------|------|
| `LOCAL` | 로컬 LLM (무료) | 1순위 |
| `NORMAL` | 저비용 외부 API | 2순위 |
| `PREMIUM` | 고추론 외부 API | 3순위 |

#### 에이전트 노드별 TaskType

| 노드 | TaskType | 설명 |
|------|----------|------|
| ClassifierService | `LIGHT_TEXT` | 질문 유형 분류 (품질 민감 — 큰 모델 유지) |
| RetrievalService | `MICRO_TEXT` | 쿼리 생성 (MultiQueryExpander) — §6.21 작업2로 MICRO_TEXT 전환 |
| AnswerService | `TEXT` | 답변 생성 |
| CriticService | `TEXT` | 근거 검증 |
| DirectAnswerService | `LIGHT_TEXT` | meta 질문 직접 응답 (사용자 노출 — 큰 모델 유지) |
| VisionDescriptionService | `VISION` | 이미지 → 설명 생성 |
| ImageTypeClassifier | `LIGHT_BOTH` | 이미지 유형 분류 |
| KeywordExtractor | `MICRO_TEXT` | 청크 키워드+맥락(Contextual Retrieval, §10.1) 통합 추출 — `context:` 사용량 라벨. §6.21로 MICRO_TEXT 전환 |
| RerankerService | `TEXT` (ChatClient) | 검색 후보 LLM 리랭킹 — `SEARCH_RERANK_ENABLED=true`일 때만 동작 |

> **백그라운드 서비스(AgentGraph 밖)**: `ConversationSummarizerService`(대화 요약)·`ThreadMetaService`(제목 생성)도 `MICRO_TEXT`를 사용한다. `type=MICRO_TEXT` 소형 프로바이더 등록 시 위 `MICRO_TEXT` 4개 경로(키워드·요약·제목·쿼리확장)가 소형으로 오프로딩되고, 분류·직답·답변은 큰 모델에 남는다(§5.4 예제 6, §6.21).

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
`providers[1]`(로컬 LLM 1)이 `LOCAL_LLM_*` 값을 사용합니다 — **`LOCAL_LLM_URL`은 반드시 설정해야 합니다**(비어 있으면 이 provider가 통째로 비활성화됨, 더 이상 `http://localhost:1234/v1`로 자동 폴백하지 않음). (`providers[0]`은 잡무 전담 소형 모델 — `LOCAL_FAST_LLM_*`, §5.4 예제 6. `LOCAL_FAST_LLM_URL`을 비워두면 그냥 비활성화되고 `local`이 잡무까지 흡수함)

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
app.llm.circuit-breaker-minutes=4
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

---

#### 예제 5 — 로컬 LLM 2대 로드밸런싱 (처리량 확장)

GPU가 2대 있어 로컬 LLM 서버를 2대 띄울 수 있을 때, 같은 `role`(LOCAL)·같은 `priority`로 등록하면 `LlmRouter`가 요청마다 잔여 permit이 더 많은(least-in-flight) 쪽으로 자동 분산합니다. 상세 동작은 [§5.7](#57-동시성-제어-및-백프레셔)을 참고하세요.

`application.properties`:
```properties
app.llm.default-routing-mode=COST_FIRST
app.llm.default-provider-concurrency=4

app.llm.providers[0].name=local-a
app.llm.providers[0].base-url=http://gpu-a:1234/v1
app.llm.providers[0].api-key=lm-studio
app.llm.providers[0].model=google/gemma-4-e4b
app.llm.providers[0].type=BOTH
app.llm.providers[0].role=LOCAL
app.llm.providers[0].priority=0
app.llm.providers[0].concurrency=4

app.llm.providers[1].name=local-b
app.llm.providers[1].base-url=http://gpu-b:1234/v1
app.llm.providers[1].api-key=lm-studio
app.llm.providers[1].model=google/gemma-4-e4b
app.llm.providers[1].type=BOTH
app.llm.providers[1].role=LOCAL
app.llm.providers[1].priority=0
app.llm.providers[1].concurrency=4
```

- `priority=0`으로 동일 — 이래야 같은 그룹으로 묶여 부하 분산 대상이 됩니다. `priority`를 다르게 주면 로드밸런싱이 아니라 일반 폴백(낮은 쪽 우선, §5.5)이 됩니다.
- 총 동시 처리량 = 2대 × `concurrency`(4) = 8 — "4명 동시 질문" 시나리오에도 여유가 생깁니다.
- 서버 사양이 다르면 `concurrency`도 각각 다르게(예: `local-a`는 4, `local-b`는 2) 그 서버의 실제 `--parallel` 값에 맞춰 설정하세요.

COST_FIRST 흐름:
```
[분류·키워드·쿼리] local(LIGHT_BOTH)
[답변·Critic]      gemini-flash → openai-mini → gemini-pro → openai
                   (각 단계에서 429/오류 시 다음 우선순위로 자동 전환)
```

---

#### 예제 6 — 소형(경량) LLM 분리로 잡무 오프로딩 (PLAN §6.21)

추론이 필요 없는 잡무(키워드+맥락 추출·대화 요약·제목 생성·MultiQuery 쿼리 확장 = `MICRO_TEXT`)를 500MB급 소형 모델로 내리고, 답변 생성(`TEXT`)과 품질 민감한 분류·직답(`LIGHT_TEXT`)은 큰 모델이 전담하게 하면 — 두 모델이 **서로 다른 동시성 슬롯(Semaphore)**을 쓰므로 인덱싱 잡무가 채팅 답변의 슬롯을 잠식하지 않습니다(대화 응답 지연 감소).

> 이 오프로딩은 `application.properties` 기본 설정에 이미 적용되어 있습니다(`providers[0]`=소형, `providers[1]`=큰 모델) — 아래는 처음부터 구성하는 방법을 보여주는 예제입니다.

소형 모델 서버를 큰 모델과 **다른 포트/장비**에 띄운 뒤(예: LM Studio 2번째 인스턴스에 `qwen2.5-0.5b-instruct`를 로드, 포트 1236), `application.properties`:
```properties
# 큰 모델 — 답변(TEXT)·분류·직답(LIGHT_TEXT)·Vision 전담. priority를 1로 올려 소형에 MICRO_TEXT 우선권을 넘긴다.
app.llm.providers[1].name=local
app.llm.providers[1].base-url=http://localhost:1234/v1
app.llm.providers[1].model=google/gemma-4-e4b
app.llm.providers[1].type=BOTH
app.llm.providers[1].role=LOCAL
app.llm.providers[1].priority=1
app.llm.providers[1].concurrency=3

# 소형 모델 — MICRO_TEXT(키워드·요약·제목·쿼리확장) 전담. priority 0으로 우선.
app.llm.providers[0].name=local-fast
app.llm.providers[0].base-url=http://localhost:1236/v1
app.llm.providers[0].model=qwen2.5-0.5b-instruct
app.llm.providers[0].type=MICRO_TEXT
app.llm.providers[0].role=LOCAL
app.llm.providers[0].priority=0
app.llm.providers[0].concurrency=4
```

라우팅 결과:
```
[키워드·요약·제목·쿼리확장] local-fast (MICRO_TEXT, priority 0)    ← 소형
[분류·meta 직답]            local        (LIGHT_TEXT→BOTH, p1)      ← 큰 모델(품질 유지)
[답변·Critic·Rerank]        local        (TEXT/BOTH, priority 1)    ← 큰 모델
[Vision·이미지 분류]        local        (소형은 이미지 미지원)      ← 큰 모델
소형 다운/차단 시           → MICRO_TEXT가 local(priority 1)로 자동 폴백
```

- ⚠️ **priority 필수**: 소형(0) < 큰 모델(1). 둘 다 0으로 두면 `MICRO_TEXT`가 두 모델 사이에 로드밸런싱되어 절반만 오프로딩됩니다.
- ⚠️ **인덱스 연속성**: `providers[N]`은 0부터 연속이어야 바인딩됩니다(파일 내 줄 순서 자체는 무관 — 사람이 읽기 편하도록만 맞춤). 현재 기본 파일은 `[0]`=소형·`[1]`=로컬 LLM 1·`[2]`=로컬 LLM 2·`[3]~[7]`=외부·`[8]`=Vision(선택) 순.
- **더 공격적 오프로딩(A안)**: 분류·직답까지 소형으로 내리려면 `type=MICRO_TEXT` 대신 `type=LIGHT_TEXT`로 등록(`LIGHT_TEXT`가 `MICRO_TEXT`도 흡수). 단 분류 오분류는 라우팅 정확도로, 직답은 사용자 노출로 이어지므로 채택 전 검색 품질 평가 하네스([§6.6](#66-검색-품질-평가-하네스-개발자용))로 분류 정확도 회귀를 확인하세요.
- 처리량을 더 늘리려면 소형·큰 모델 각각을 [예제 5](#예제-5--로컬-llm-2대-로드밸런싱-처리량-확장)처럼 같은 priority로 다중 등록해 로드밸런싱할 수 있습니다 — 구체적인 결합 설정은 아래 예제 7 참고.

---

#### 예제 7 — 소형·대형 두 티어를 각각 수평 확장 (예제 5 + 6 결합, PLAN §6.21)

동시 사용자가 늘어나 잡무(`MICRO_TEXT`)와 답변(`TEXT`) 양쪽 모두에서 처리량이 부족해지면, 예제 6의 2-티어 구조를 유지한 채 **각 티어를 독립적으로 여러 대** 등록합니다. `findFirst()`는 role+priority로 후보 그룹을 고른 뒤 그 그룹 안에서 least-in-flight로 분산하므로(§5.7), 소형 그룹과 큰 모델 그룹이 각자 별도로 로드밸런싱됩니다 — 서로 다른 티어끼리는 섞이지 않습니다(우선순위가 다르므로).

```properties
# 큰 모델 2대 — 둘 다 priority=1(소형에 MICRO_TEXT 우선권 양보), 같은 priority끼리 로드밸런싱
app.llm.providers[0].name=local-a
app.llm.providers[0].base-url=http://gpu-a:1234/v1
app.llm.providers[0].model=google/gemma-4-e4b
app.llm.providers[0].type=BOTH
app.llm.providers[0].role=LOCAL
app.llm.providers[0].priority=1
app.llm.providers[0].concurrency=3

app.llm.providers[7].name=local-b
app.llm.providers[7].base-url=http://gpu-b:1234/v1
app.llm.providers[7].model=google/gemma-4-e4b
app.llm.providers[7].type=BOTH
app.llm.providers[7].role=LOCAL
app.llm.providers[7].priority=1
app.llm.providers[7].concurrency=3

# 소형 모델 2대 — 둘 다 priority=0, 같은 priority끼리 로드밸런싱
app.llm.providers[6].name=local-fast-a
app.llm.providers[6].base-url=http://cpu-a:1236/v1
app.llm.providers[6].model=qwen2.5-0.5b-instruct
app.llm.providers[6].type=MICRO_TEXT
app.llm.providers[6].role=LOCAL
app.llm.providers[6].priority=0
app.llm.providers[6].concurrency=4

app.llm.providers[8].name=local-fast-b
app.llm.providers[8].base-url=http://cpu-b:1236/v1
app.llm.providers[8].model=qwen2.5-0.5b-instruct
app.llm.providers[8].type=MICRO_TEXT
app.llm.providers[8].role=LOCAL
app.llm.providers[8].priority=0
app.llm.providers[8].concurrency=4
```

라우팅 결과:
```
[키워드·요약·제목·쿼리확장] local-fast-a ∥ local-fast-b (MICRO_TEXT, priority 0, least-in-flight 분산)
[분류·meta 직답·답변·Critic] local-a ∥ local-b            (LIGHT_TEXT→BOTH / TEXT/BOTH, priority 1, least-in-flight 분산)
```

- 총 잡무 처리량 = 소형 대수 × concurrency(2×4=8), 총 답변 처리량 = 큰 모델 대수 × concurrency(2×3=6) — **두 숫자는 서로 독립**이라 티어별로 필요한 만큼만 대수를 늘리면 됩니다(예: 인덱싱이 병목이면 소형만 증설, 채팅이 병목이면 큰 모델만 증설).
- 인덱스는 활성 프로바이더 [0]~[5] 이후 연속이어야 합니다. 위 예시는 [6][7][8]을 사용 — `local-vision`(§3의 [6] 예시)도 함께 쓴다면 [9]로 밀어야 합니다.
- 한쪽 티어의 한 대가 다운돼도 같은 티어 안에서 나머지가 흡수하고, 그래도 전멸하면 상위 티어(큰 모델)로 자동 폴백합니다(예제 6의 폴백 규칙 그대로 유지).

---

### 5.5 Circuit Breaker

프로바이더에서 오류 발생 시 자동으로 일시 차단하고 다음 우선순위 프로바이더로 전환합니다.

| 오류 유형 | 차단 시간 | 비고 |
|----------|----------|------|
| HTTP 429 (Rate Limit) | `Retry-After` 헤더 값 | 헤더 없으면 아래 "폴백 없는 프로바이더 완화"에 따라 폴백 가능 여부로 분기 |
| HTTP 402 (결제 필요) | `Retry-After` 헤더 값 | 헤더 없으면 위와 동일 |
| HTTP 503 (Service Unavailable) | `Retry-After` 헤더 값 | 헤더 없으면 위와 동일 |
| 그 외 4xx/5xx, 네트워크 오류 | 30초 고정 | 변경 없음 |

**폴백 없는 프로바이더의 과부하성 오류(429/402/503) 완화**: `Retry-After` 헤더가 없을 때의 차단 시간은 폴백 가능 여부에 따라 달라집니다.

- **폴백 있음** (이 요청에서 시도 가능한 다른 프로바이더가 남아있는 경우) — 기존과 동일하게 `circuit-breaker-minutes` 전체를 차단합니다. 다음 프로바이더로 정상 전환되므로 문제가 없습니다.
- **폴백 없음**(전형적으로 단일 LOCAL 프로바이더만 등록된 배포) — 전체 시간 차단은 다음 요청부터 만료 시각까지 서비스 전체를 다운시킬 뿐이므로 **30초로 단축 차단**합니다. 동시성 게이트(§5.7)가 이미 프로바이더에 걸리는 부하를 억제하고 있어 짧게 재시도해도 안전합니다.
- 서버가 명시적으로 `Retry-After`를 보낸 경우는 폴백 유무와 무관하게 항상 그대로 존중됩니다.

- `app.llm.circuit-breaker-minutes=4` — 기본 차단 시간 (분)
- 차단 상태는 인메모리(`ConcurrentHashMap`) 유지 — 서버 재시작 시 초기화
- 모든 프로바이더 소진 시 → `LlmProviderExhaustedException` (500 응답)
- `/llm-usage` 대시보드에서 차단 중인 프로바이더를 빨간 카드 + MM:SS 카운트다운으로 확인 가능
- 임베딩 호출은 Circuit Breaker 대상이 아닙니다 — `/llm-usage`의 `embed:<model>` 카드는 항상 "정상" 배지로 표시되며 실패 시 재시도/차단 없이 즉시 예외가 전파됩니다(`EMBED_USAGE_FALLBACK_ENABLED`)
- API 키가 없는(비활성) 프로바이더는 **사용 이력이 없으면** `/llm-usage`의 카드·표·차트 어디에도 표시되지 않습니다. 과거에 사용된 적이 있으면 키를 제거한 뒤에도 이력 보존을 위해 계속 표시됩니다. 활성(키 설정됨) 프로바이더는 사용량이 0이어도 항상 표시됩니다.
- **Circuit Breaker ≠ 동시성 백프레셔(§5.7)**: 429/402/기타 오류로 인한 차단은 "프로바이더가 고장났다"는 신호로 취급해 일정 시간 우회합니다. 반면 동시성 게이트가 대기 상한을 넘겨 던지는 429(`LlmBackpressureException`)는 "프로바이더는 정상이지만 지금 자리가 없다"는 신호이므로 Circuit Breaker를 차단하지 않고, 다른 프로바이더로 자동 전환하지도 않습니다 — 요청을 보낸 쪽에 그대로 즉시 전파됩니다.

### 5.6 Orphan 프로바이더 사용 기록 정리

설정(`app.llm.providers`)에서 완전히 제거된 프로바이더나, `EMBED_MODEL`을 변경한 뒤 남은 이전 임베딩 모델의 `embed:<old-model>` 기록은 `llm_usage`에 그대로 남아 orphan이 됩니다. `/llm-usage`에서 회색 **ORPHAN** 배지 카드로 노출되며, 카드 우측 상단 🗑 아이콘으로 정리할 수 있습니다.

- **삭제 대상 판별**: 현재 config에 없는 프로바이더 이름, 또는 현재 활성 임베딩 모델이 아닌 `embed:*` 이름만 orphan으로 분류됩니다. 활성 프로바이더·현재 임베딩 모델 카드에는 삭제 버튼 자체가 없고, API를 직접 호출해도 서버가 400으로 거부합니다.
- **엔드포인트**: `DELETE /admin/llm-usage/{provider}` — `/admin/**` 경로 아래에 있어 `ROLE_ADMIN` 전용입니다. no-auth 모드에서는 `/admin/**`에 대한 기존 관리자 자동 인증이 그대로 적용되어 별도 로그인 없이 동작합니다. 인증 모드에서는 CSRF 토큰이 필요합니다(HTMX 버튼은 자동 첨부).
- **감사 로그**: 삭제 시 `AuditLogger`에 `llm-usage.delete-orphan` 이벤트(프로바이더명, 삭제 행 수)가 기록됩니다.
- **API 예시** (no-auth 모드 — CSRF 비활성화라 세션/토큰 불필요):
  ```bash
  curl -X DELETE http://localhost:8080/admin/llm-usage/old-model-name
  ```
  인증 모드에서는 세션 쿠키 + CSRF 토큰이 필요하므로 `/llm-usage` 화면의 삭제 버튼 사용을 권장합니다.
- 삭제 시 카드는 즉시 갱신되지만, `/llm-usage`의 일별 차트·기간별 표는 별도 fetch라 다음 로드/새로고침에 반영됩니다.

### 5.7 동시성 제어 및 백프레셔

여러 사용자의 질문이 거의 동시에 도착하면(예: 로컬 LLM 1대·동시 3건 처리 가능한데 사용자 4명이 거의 동시에 질문), 앱은 채팅/질의 경로에서 프로바이더별로 서버가 실제로 처리 가능한 동시 요청 수를 넘지 않도록 자체 제한합니다.

**동작 방식**:
1. 프로바이더마다 `concurrency`(§5.2, 미설정 시 `LLM_DEFAULT_PROVIDER_CONCURRENCY`) 크기의 슬롯 풀을 가짐.
2. 요청이 슬롯을 요청하면 최대 `LLM_PERMIT_WAIT_TIMEOUT_SECONDS`(기본 20초) 동안 대기.
3. 슬롯이 나면 즉시 LLM 호출 → 완료 후 슬롯 반환.
4. 대기 상한을 넘기면 HTTP 429 + `Retry-After` 헤더로 즉시 응답(`RAG-LLM-002`) — Circuit Breaker 차단이나 다른 프로바이더로의 자동 전환은 하지 않습니다(§5.5 참고). SSE 스트리밍 응답에서는 "현재 요청이 몰려 있습니다. 잠시 후 다시 시도해 주세요." 메시지로 우아하게 종료됩니다.

**적용 범위**: 분류(Classifier)·답변 생성(블로킹+스트리밍+PROGRESSIVE 업그레이드+충분도 평가)·DUAL(양쪽 프로바이더)·DirectAnswer·리랭킹(opt-in)·MultiQuery 확장까지 채팅/질의 경로 전체에 적용됩니다. **인덱싱/백그라운드 LLM 호출(키워드 추출, MD 포맷 교정, Vision 설명, TXT 구조화, 대화 요약 사전계산, 스레드 제목 생성)은 이 게이트의 대상이 아닙니다** — 이미 `INDEXING_MAX_LLM`으로 자체 동시성을 제어하고 있고, 마감시한 있는 동기 HTTP 호출자가 없기 때문입니다.

**튜닝 가이드**:
- **기본 원칙**: `LLM_DEFAULT_PROVIDER_CONCURRENCY`(또는 프로바이더별 `concurrency`)는 그 LLM 서버의 실제 `--parallel`(또는 동급) 설정값과 일치시키세요. 너무 크게 잡으면 앱이 서버가 처리 못 할 요청까지 통과시켜 결국 서버 쪽에서 429/타임아웃이 발생하고, 너무 작게 잡으면 서버 여유 용량을 못 씁니다.
- 429가 자주 발생한다면: ① `LLM_PERMIT_WAIT_TIMEOUT_SECONDS`를 늘려 더 오래 대기하게 하거나, ② LLM 서버의 `--parallel` 값과 `concurrency` 설정을 함께 늘리거나(서버 리소스가 허용하는 한도 내에서), ③ 동일 role·동일 priority로 프로바이더를 추가 등록해 부하를 분산하세요 — 아래 "동일 우선순위 로드밸런싱" 참고.
- 로그로 확인: `[BACKPRESSURE] provider=... concurrency slot wait exceeded Ns, rejecting with 429` 로그 라인이 반복되면 해당 프로바이더가 지속적으로 포화 상태라는 신호입니다.
- 인덱싱 중에도 같은 물리 서버를 채팅이 함께 쓰는 구성이라면, 인덱싱 트래픽도 결국 이 게이트 뒤의 같은 서버 용량을 공유하게 되므로 대량 동기화 작업은 사용자 트래픽이 적은 시간대에 실행하는 것을 권장합니다.

**동일 우선순위 로드밸런싱(처리량 확장)**: 같은 `role`·같은 `priority`로 프로바이더를 여러 대 등록하면(설정 예시는 [§5.4 예제 5](#54-시나리오별-설정-예제) 참고), 요청 시점마다 그중 **잔여 permit이 가장 많은(least-in-flight) 프로바이더**가 자동 선택됩니다 — 위 세마포어 게이트를 그대로 재사용하므로 별도 설정이 필요 없습니다.
- `priority`가 다르면 부하와 무관하게 낮은 `priority`가 항상 우선합니다 — 로드밸런싱은 **동일 priority 그룹 내부에서만** 일어나고, 서로 다른 priority 간 자동 전환은 여전히 프로바이더 실패(§5.5 Circuit Breaker) 시에만 일어납니다.
- 총 동시 처리량 = 등록 대수 × per-provider `concurrency`(예: LOCAL 2대 × 3 = 6).
- `/llm-usage`에서 프로바이더별 사용량이 실제로 분산되는지 확인할 수 있습니다.
- 임베딩 프로바이더는 아직 이 로드밸런싱 대상이 아닙니다(라우팅 지점이 다른 `EmbeddingModel` 데코레이터 체인) — 향후 과제로 남아 있습니다.

---

## 6. 운영 팁

### 6.1 대화 메모리

`MemoryService`는 **SQLite**(`DATA_DIR/memory.db`)에 대화 이력을 영속합니다.

- WAL 모드로 읽기/쓰기 경합 최소화. SQLite pool size는 반드시 1 유지
- 스레드별 최근 `MEMORY_FETCH_LIMIT_TURNS`(기본 10)턴 이내에서 `LLM_MAX_TOKENS × 0.5`까지 LLM 컨텍스트 주입
- `/chat/{threadId}` 재진입 시 모든 이전 turn을 시간순으로 불러와 메시지 버블 복원
- `MemoryRepository` 인터페이스로 추상화 — Redis 등으로 교체 시 구현체만 추가

**요약 캐시 (`ConversationSummarizerService`)**: 스레드 대화 이력을 LOCAL 프로바이더로 미리 요약해 캐싱해두고, 실제 질의 시 원문 전체 대신 "요약 + 최근 N턴 원문"을 컨텍스트로 사용해 토큰을 절약합니다.

- **트리거**: 주 트리거는 답변이 완료되고 턴이 저장된 직후(`precomputeAfterTurn()`)입니다 — 사용자가 다음 질문을 입력하기 전부터 백그라운드로 미리 갱신을 시작하므로, 응답을 읽는 동안이 곧 요약 준비 시간이 됩니다. 사용자가 질문창에 첫 글자를 입력할 때 발화되는 기존 트리거(`precompute()`)는 아직 한 번도 요약이 만들어지지 않은 스레드(예: 재시작 후 처음 열어본 오래된 대화)를 위한 콜드스타트 안전망으로 남아 있습니다.
- **싫어요 처리**: 답변 직후 트리거된 요약 생성이 진행되는 동안(LLM 호출이 아직 끝나지 않은 짧은 시간) 사용자가 방금 그 턴에 싫어요를 누르면, 완성된 요약이라도 캐싱하지 않고 버립니다 — 다음 질문은 자동으로 원문 폴백 경로를 쓰게 되며, 그 경로는 애초에 DISLIKE 턴을 제외합니다. 다음 정상 트리거 때는 dedupe 단계에서 자연스럽게 그 턴이 빠진 채로 다시 요약됩니다.
- 캐시 미스이거나 LOCAL 프로바이더가 없으면 자동으로 원문 폴백 경로(`MemoryService.getHistory()`) 사용 — best-effort, 실패해도 채팅이 막히지 않음
- 요약 경로와 폴백 경로는 **동일한 문자 예산**을 지키도록 통일되어 있어(위 참조), 요약 캐시 유무에 따라 LLM에 전달되는 컨텍스트 양이 달라지지 않음
- **요약 대상 자체도 무제한이 아닙니다**: 요약을 만들 때 읽어오는 원문(`MemoryService.getRecentTurns()`)은 `getHistory()`와 동일하게 `MEMORY_FETCH_LIMIT_TURNS`(기본 10턴)로 상한이 걸려 있습니다. 대화가 길어져도 매번 LLM에 보내는 요약용 입력 크기가 무한정 커지지 않도록 하기 위함이며, 이 턴 수보다 오래된 내용은 이 요약에서도 함께 유실됩니다(스레드를 다시 열었을 때 전체 메시지 버블을 복원하는 `MemoryService.getTurns()`는 이 제한과 무관하게 항상 전체를 반환합니다)
- 캐시 크기·요약 길이·최근 턴 수·재계산 억제 창은 `MEMORY_FETCH_LIMIT_TURNS`/`SUMMARY_*` 환경변수로 조정 (위 "대화 메모리 / 요약 캐시 튜닝" 참조)

`conversation_turns` 테이블 확장 컬럼 (앱 시작 시 `ALTER TABLE`로 자동 마이그레이션):

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `asked_at` | TEXT | 질문 발송 시각 (UTC, `yyyy-MM-dd HH:mm:ss`) |
| `input_tokens` | INTEGER | 해당 턴 입력 토큰 수 |
| `output_tokens` | INTEGER | 해당 턴 출력 토큰 수 |
| `elapsed_ms` | INTEGER | 에이전트 처리 소요 시간 (ms) |
| `provider` | TEXT | 최종 응답에 사용된 LLM 프로바이더명 |
| `llm_calls` | INTEGER | 해당 턴의 LLM 총 호출 횟수 |

---

### 6.2 문서 버전 관리

```bash
# 버전 1.0으로 업로드
curl -X POST http://localhost:8080/api/v1/documents \
  -F "file=@v1.0-manual.pdf" -F "version=1.0"

# 버전 1.0 문서로 검색
curl -X POST http://localhost:8080/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{"question": "...", "version": "1.0"}'
```

- 버전별로 격리 저장 — chroma: `manual_{version}` 컬렉션 분리 (예: `manual_latest`, `manual_1_0`) / sqlite-vec: `version` 파티션 키
- Web UI에서는 채팅 사이드바 상단의 **version** 입력창에 버전 입력

---

### 6.3 데이터 영속성

| 데이터 | 저장 위치 | 비고 |
|--------|----------|------|
| 문서 원본 | `DATA_DIR/documents/` | Sync 대상 |
| 추출된 이미지 | `DATA_DIR/images/{imageId}/` | `imageId`는 문서 SHA-256 앞 16자(문서명이 아닌 내용 기반 키) — 문서 삭제 시 함께 삭제되나, 내용이 동일한 다른 문서가 남아 있으면 보존 |
| DOCX 변환 MD (원본) | `DATA_DIR/converted/{docId}.md` | DOCX 인덱싱 시 자동 생성; 문서 삭제 시 함께 삭제 |
| DOCX 변환 MD (교정본) | `DATA_DIR/converted/{docId}_corrected.md` | LLM 포맷 교정 후 저장; 실제 인덱싱 소스; 수동 편집 후 벡터 스토어 관리 페이지에서 ↺ 재인덱싱 가능 |
| 인덱스 레지스트리 | `DATA_DIR/memory.db` (SQLite `doc_registry` 테이블) | SHA-256 기반 변경 감지. 문서 저장소는 사용자별 격리 없이 공유됨(`DocRegistry.SHARED`) — `userId` 파라미터는 API 시그니처상 존재하나 실제로는 무시됨 |
| 벡터 임베딩 | chroma: Chroma 서버(로컬 `data/chroma/`, Docker Compose `chroma_data` 볼륨) / sqlite-vec: `DATA_DIR/memory.db`(기본) 또는 `app.vectorstore.sqlite-vec.db-path` 설정 시 별도 `vector.db` | 백엔드 전환 시 벡터 공유 안 됨(§3.1) |
| 대화 이력 + LLM 사용량 | `DATA_DIR/memory.db` (SQLite) | WAL 모드; 메시지 메타데이터(토큰·시간·프로바이더) 포함 |
| 감사 로그 | `DATA_DIR/audit/audit.log` | JSON Lines; 롤링 압축본 `audit.YYYY-MM-DD.N.log.gz` 포함 |

> Docker Compose 사용 시 `./data` 디렉터리를 컨테이너에 바인드 마운트합니다.  
> 데이터 백업 시 `data/` 디렉터리와 Chroma 볼륨을 함께 보존하세요.

---

### 6.4 성능

- **Java 21 Virtual Threads** (`spring.threads.virtual.enabled=true`) — LLM I/O 동시 요청을 효율적으로 처리
- **배치 멀티 쿼리 검색** — `RetrievalService`가 확장 질의를 1회 배치 임베딩 → 단일 쿼리(chroma) 또는 쿼리별 개별 조회(sqlite-vec) → 가중 RRF 융합(Phase 7-A — 벡터 축 그룹 정규화 + 키워드 축 가중치 외부화). 재시도 시 후보 풀 에스컬레이션, 선택적 LLM 리랭킹(opt-in)
- **쿼리 임베딩 캐시** — 반복·유사 질문은 Caffeine 캐시로 임베딩 재호출 없이 처리 (`SEARCH_QUERY_EMBED_CACHE_*`)
- **인덱싱-검색 캐시 분리** — 청크 임베딩(인덱싱)은 위 쿼리 임베딩 캐시를 우회해 대량 문서 인덱싱이 직전 검색 질의의 캐시 엔트리를 밀어내지 않는다(§10.9.4). 캐시 키는 질의 원문 대신 SHA-256 해시를 사용해 엔트리 크기가 고정됨
- **sqlite-vec 벡터 BLOB 직렬화** — 벡터를 JSON 텍스트 리터럴 대신 raw float32 BLOB로 삽입/KNN 질의에 바인딩해 vec0의 파싱 비용과 전송 크기를 줄인다(§10.9.2). 기존에 인덱싱된 데이터와 완전히 호환되어 재인덱싱 불필요
- **Chroma 배치 검색 응답 축소** — `RetrievalService`의 배치 멀티 쿼리 검색이 Chroma에 결과 재구성 시 실제로 쓰지 않는 임베딩 벡터 필드까지 요청하던 것을 메타데이터·문서·거리 3개 필드만 요청하도록 축소(§10.9.1) — 리랭킹 활성 시(질의 여러 개 × 후보 다수 × 임베딩 차원) 검색 1회당 전송·파싱·GC되는 데이터 크기가 눈에 띄게 줄어든다. sqlite-vec 백엔드는 원래 임베딩을 응답에 포함하지 않으므로 영향 없음
- **sqlite-vec 인덱싱 스트리밍 삽입** — `SqliteVecVectorStoreProvider.add()`가 문서 전체 청크의 임베딩을 힙에 모은 뒤 한 번에 삽입하던 것을, 토큰 서브배치(§10.8.2와 동일한 배치 단위) 하나가 임베딩되는 즉시 그 서브배치만 삽입하는 구조로 전환(§10.9.3) — 대용량 문서(500+청크)를 인덱싱할 때 피크 메모리가 문서 크기가 아니라 서브배치 크기에 비례하게 된다. 서브배치별 두 테이블 삽입은 여전히 하나의 트랜잭션으로 묶인다(§10.8.3)
- **Contextual Retrieval + 임베딩 입력 정규화** — 인덱싱 시 청크별로 `{파일명} > {섹션 제목}` 구조적 맥락 + LLM 생성 1~2문장을 임베딩·FTS 입력 앞에 결합(`KeywordExtractor`가 키워드 추출과 한 번에 처리, 사용량은 `context:` 라벨). 마크다운 장식(구분선·강조 마커)은 임베딩/FTS/답변 프롬프트 입력에서만 제거되고 저장·표시 원문은 그대로 유지된다. 설정 프로퍼티 없음(항상 적용) — 기존 문서는 재인덱싱해야 새 맥락/정규화가 반영됨
- **한국어 FTS 트라이그램 토크나이저** — `chunk_fts`가 `unicode61`(공백 구분 단어) 대신 `trigram`(3자 겹침 윈도우) 토크나이저를 사용해 활용형 종결어미가 붙은 한국어 단어(예: 질의 "인덱싱"이 본문 "인덱싱됩니다"에 매칭)와 코드/식별자 부분 문자열(예: "ERR45"가 "ERR4521"을 찾음)을 더 잘 찾는다. **자동 마이그레이션** — 기존 `unicode61` 테이블은 다음 재기동 시 자동으로 trigram으로 재구축되며(`doc_tags`/`content`/`keywords` 손실 없이 복사) 별도 재인덱싱·재동기화가 필요 없다. 트레이드오프: 2글자 이하 검색어(예: "오류", "문서")는 trigram 최소 매칭 단위(3자) 미만이라 진짜 BM25 순위 점수는 얻지 못한다 — §10.7.3에서 `content`/`keywords` `LIKE` 스캔으로 존재 여부 기반 신호(순위 없음, MATCH 결과보다 낮은 우선순위로 배치)를 보충해 완전히 탈락하지는 않는다(하이브리드 벡터 축은 애초에 무관하게 동작) — `SEARCH_HYBRID_ENABLED=true`일 때만 체감. 설정 프로퍼티 없음(항상 적용)
- **병렬 인덱싱** — `RagService.syncDirectory()`에서 파일별·LLM 호출별 Semaphore 기반 병렬 처리
- **DUAL 모드** — LOCAL + 외부를 Virtual Thread로 병렬 실행

CPU/메모리 제약이 있는 환경에서는 `INDEXING_MAX_FILES`와 `INDEXING_MAX_LLM`을 줄이세요.

---

### 6.5 설정 페이지 (`/settings`) — LLM/RAG 옵션 조회·핫 수정

`/settings`는 현재 **유효** LLM/RAG 설정을 한 화면에서 보여주고, 일부 검색 튜닝 값은 **재기동 없이** 조정할 수 있게 합니다. `application.properties`/환경변수를 고치고 재기동하지 않아도 검색 동작을 실시간으로 미세조정할 수 있습니다.

**조회 항목 (그룹별)**:
- **LLM 라우팅**: 등록 프로바이더·역할(role)·우선순위·모델·API 키 설정 여부·서킷브레이커 상태·**활성화 여부**(아래 참조), 기본 라우팅 모드, 일반 temperature·max-tokens(조회 전용, 실제 config 값 표시).
- **임베딩 / 벡터 스토어**: 임베딩 모델·차원, 벡터 스토어 백엔드(chroma/sqlite-vec).
- **검색 튜닝 / 인덱싱 / 캐시**: 아래 핫 수정 항목 + 조회 전용 항목.

**프로바이더 활성화/비활성화 토글 (재기동 시 초기화)**:

`LLM 라우팅` 표의 각 행에는 활성/비활성 배지와(관리자에게만) **활성화**/**비활성화** 버튼이 있습니다 — `POST /admin/settings/provider/toggle`(`name`, `enabled`). 클릭 즉시 `LlmRouter.findFirst()`가 해당 이름의 프로바이더를 후보에서 제외/재포함하며, ChatModel·동시성 게이트 등 빈(bean) 자체는 건드리지 않으므로 재기동이 필요 없습니다.

- **`app.llm.providers[N]`을 주석 처리/삭제하는 것과는 다른, 별개의 메커니즘**입니다 — `ProviderToggle`이라는 프로세스 메모리 상의 집합(`CircuitBreaker`와 동일한 패턴)일 뿐이며, **`settings_override` 테이블(SQLite)에 저장되지 않습니다.** 즉 **재기동하면 모든 프로바이더가 다시 활성 상태로 돌아갑니다** — 설정 파일/환경변수가 여전히 최종 권위를 가집니다.
- 이름(`name`)이 같은 프로바이더가 여러 대 등록돼 있으면(§5.4 예제 5/7의 로드밸런싱 쌍, 또는 §5.4 예제 뒤쪽의 PREMIUM Gemini 키 쌍처럼) **같은 이름을 공유하는 모든 인스턴스가 함께** 켜지고 꺼집니다 — 토글은 provider 설정 하나가 아니라 "이름"을 키로 삼기 때문입니다.
- **마지막으로 남은 활성 프로바이더는 비활성화할 수 없습니다** — 그 요청은 400(`IllegalArgumentException`)으로 거부되어 라우팅이 완전히 막히는 상황을 방지합니다.
- 모든 토글은 감사 로그(`settings.provider.toggle`, `{"enabled":"true|false"}`)에 남습니다.
- 임시로 문제가 있는 프로바이더(예: 응답이 계속 이상하거나 비용이 우려될 때)를 재기동 없이 즉시 빼고 싶을 때 사용하세요. **영구적으로** 빼려면 `application.properties`에서 해당 `providers[N].*` 블록을 주석 처리하거나 env var를 비우세요(§5.4).

**시작 시 오버라이드 불일치 경고**:

`SettingsService.init()`은 시작 시 각 핫 수정 키의 **effective 값**을 오버라이드 바인딩 전/후로 비교해, `settings_override`에 저장된 값이 현재 `application.properties`/환경변수 값과 실제로 다르면(클램핑 이후 값 기준) WARN 로그를 남깁니다:

```
[SETTINGS] '{키}' — a persisted /settings override ({override값}) is overriding the
env-var/application.properties value ({설정값}); the override wins. Reset it in
/settings to fall back to the configured value.
```

배포에서 `SEARCH_TOP_K=10`처럼 환경변수를 새로 설정했는데 실제로는 예전에 `/settings`에서 저장해 둔 오버라이드(예: 7)가 여전히 이기고 있어 "왜 안 바뀌지?"로 헤매는 상황을 시작 로그만 보고 바로 알 수 있게 하기 위함입니다. 오버라이드가 아예 없거나 값이 설정값과 동일하면(우연히 같은 숫자로 클램핑된 경우 포함) 조용히 넘어갑니다 — 항상 남는 로그가 아니라 실제로 "이길 때"만 경고합니다.

**핫 수정 가능 — 검색 (재기동 불필요, 다음 검색부터 반영)** — 값을 바꾸면 `settings_override` 테이블(`memory.db`)에 저장되고, 다음 검색부터 즉시 적용됩니다:

| 항목 | 키 | 범위 |
|------|----|------|
| 유사도 임계값 | `app.search-similarity-threshold` | 0.0 ~ 1.0 |
| RRF 키워드 가중치 | `app.search-rrf-keyword-weight` | 0.0 ~ 10.0 |
| RRF 상수 k | `app.search-rrf-k` | 1 ~ 1000 |
| 후보 배수(리랭크) | `app.search-candidate-multiplier` | 1 ~ 20 |
| 태그 후보 배수 | `app.search-tag-candidate-multiplier` | 1 ~ 20 |
| 멀티쿼리 최소 길이 | `app.search-multiquery-min-length` | 0 ~ 1000 |
| 재시도 시 후보 확대 | `app.search-retry-escalate` | true/false |
| topK (검색 상위 K) | `app.search-top-k` | 1 ~ 50 |
| 멀티쿼리 확장 | `app.search-multiquery-enabled` | true/false |
| 하이브리드 검색 | `app.search-hybrid-enabled` | true/false |

**핫 수정 가능 — 인덱싱/청킹 (재기동 불필요, 다음 인덱싱/↺ 재인덱싱부터 반영)** — 검색 튜닝과 달리 즉시가 아니라 **다음 인덱싱**부터 적용되며, 이미 색인된 청크를 소급 재분할하지는 않습니다(값을 바꾼 뒤 재업로드하거나 `/admin` ↺ 재인덱싱을 눌러야 반영):

| 항목 | 키 | 범위 |
|------|----|------|
| 청크 크기(자) | `app.chunk-size` | 100 ~ 8000 |
| 청크 오버랩(자) | `app.chunk-overlap` | 0 ~ 2000 |
| 최소 청크 크기(자) | `app.min-chunk-size` | 0 ~ 4000 |
| 동시 파일 처리 수 | `app.indexing.max-concurrent-files` | 1 ~ 32 |
| 동시 LLM 호출 수 | `app.indexing.max-concurrent-llm-calls` (`INDEXING_MAX_LLM`) | 1 ~ 32 |

> **`INDEXING_MAX_LLM`의 적용 범위**: 이 값은 키워드+맥락 추출 전용이 아니라 **인덱싱 계열 LLM 호출의 공통 병렬도**입니다 — 키워드 추출(`DocumentIndexer`), MD 포맷 교정(`MarkdownCorrectionService`), 인덱싱 중 이미지 설명("이미지 설명 추가" 체크 시, `MarkdownCorrectionService`가 문서 내 이미지를 이 값만큼 병렬 분석 — 예전엔 순차라 사실상 `INDEXING_MAX_FILES`에 매여 있었음), TXT 구조화(`TextToMarkdownService`), 지연 Vision 설명(`LazyVisionService`)이 모두 이 값을 씁니다. 다만 이 값은 "앱 전체 동시 LLM 호출 N개"라는 **전역 예산이 아닙니다**. 소비처마다 규칙이 다릅니다:
>
> - **키워드 추출**: `syncDirectory()`가 세마포어를 **1개만 만들어 모든 파일이 공유** → 파일 수와 무관하게 총 `INDEXING_MAX_LLM`개. (파일당 1개씩 배분되는 게 아니라 티켓을 나눠 씁니다)
> - **MD 교정 / TXT 구조화**: 호출마다 **자기 세마포어를 새로 생성** → 파일 병렬 시 곱으로 증가.
>
> 같은 파일 안에서는 구조화/교정 → 청킹 → 키워드 추출이 순차 단계라 겹치지 않지만, 파일끼리는 단계가 동기화되지 않아(A는 키워드, B는 교정) 겹칩니다. 결과적으로 **인덱싱 LLM 동시 호출 피크 ≈ `INDEXING_MAX_FILES` × `INDEXING_MAX_LLM`** 입니다 — 예: 3 × 4 = 최대 12. 그래서 기본값을 `INDEXING_MAX_FILES=1`로 두어 피크를 정확히 `INDEXING_MAX_LLM`(기본 3)으로 고정했습니다. 로컬 LLM 서버의 `--parallel` 한도에 맞추려면 `INDEXING_MAX_LLM`을 그 값으로 두고 `INDEXING_MAX_FILES=1`을 유지하세요. 처리량을 위해 `INDEXING_MAX_FILES`를 올린다면 곱이 `--parallel`을 넘지 않는지 확인하세요.

**핫 수정 가능 — LLM (재기동 불필요, 다음 LLM 호출부터 반영)** — §6.18:

| 항목 | 키 | 범위 |
|------|----|------|
| Direct(잡담) 응답 temperature | `app.llm.direct-temperature` (`DIRECT_LLM_TEMPERATURE`) | 0.0 ~ 0.2 |

- **"기본값" 버튼**으로 오버라이드를 삭제하면 `application.properties`/환경변수 값으로 정확히 복귀합니다(오버라이드가 있으면 항상 프로퍼티보다 우선).
- 오버라이드는 **재기동 후에도 유지**됩니다(테이블에 영속). 배포 기본값 자체를 바꾸려면 여전히 환경변수/`application.properties`를 수정하세요 — 오버라이드는 그 위에 얹히는 런타임 조정 레이어입니다.

**조회 전용(재기동 필요)**: `rerank-enabled`(빈 생성 시점 `@ConditionalOnProperty`로 결정)·쿼리 임베딩 캐시(빈 생성 시점 결정), 임베딩 차원·벡터 스토어 백엔드(DDL/빈 구성). **일반/RAG temperature**(`LLM_TEMPERATURE`)와 **max-tokens**(`LLM_MAX_TOKENS`)는 §6.18로 이제 실제 config 값을 그대로 반영해 표시되지만, 프로바이더 빈 생성 시점에 고정되므로 조회 전용(변경 시 재기동)입니다 — 호출별로 다르게 줄 수 있는 Direct temperature만 핫 수정 대상입니다. 기본 라우팅 모드도 조회 전용입니다(대화별 라우팅은 채팅 화면에서 설정).

**권한**: 조회는 누구나 가능하지만, **수정은 관리자만** 가능합니다(관리 전용 인증 모드 `AUTH_MANAGEMENT_ONLY=true`에서 `/setup` 관리자 로그인 필요 — §9 참조). 수정 UI(입력/버튼)는 비관리자에게 숨겨지며, 서버도 `/admin/settings/**` 경로로 이중 방어합니다. 모든 변경은 감사 로그(`settings.update`/`settings.reset`, 변경 키·이전값·새값)에 남습니다.

---

### 6.6 검색 품질 평가 하네스 (개발자용)

§6.5의 검색 튜닝 값(유사도 임계값·RRF 가중치·재랭크 등)을 바꾼 뒤 "정말 좋아졌는지" 정량으로 확인하기 위한 하네스입니다. `RetrievalService.execute()`(MultiQuery+하이브리드 BM25+가중 RRF 전체 파이프라인)를 실제 임베딩·LLM 서버와 실제로 색인된 문서에 대해 그대로 실행해 recall@k·nDCG@k를 측정합니다.

```bash
# .env(또는 OS 환경변수)에 설정된 실제 LLM/임베딩 엔드포인트 + data/의 실제 색인을 사용
mvn test -Dtest=SearchQualityEvaluationTest -Dsearch-eval.enabled=true
```

- **기본적으로 skip됩니다** — `-Dsearch-eval.enabled=true`가 없으면 컨텍스트조차 띄우지 않고 즉시 skip되므로(`SqliteVecIntegrationTest`의 `sqlitevec.path` 게이팅과 동일한 패턴) 일반 빌드/CI에는 영향이 없습니다.
- **읽기 전용**입니다 — `search()`/`searchBatch()`만 호출하며 색인을 추가·삭제하지 않습니다. 실행 전 골든셋 대상 문서가 `version=latest`로 이미 색인되어 있어야 합니다.
- 골든셋은 `src/test/resources/search-eval/nexcore-gold.json`(질문 26건) — 정답은 chunk id가 아니라 색인 원문(교정본 MD)에서 그대로 가져온 고유 부분 문자열이라 재인덱싱으로 청크 경계가 바뀌어도 깨지지 않습니다. 다른 코퍼스로 검증하려면 같은 형식으로 새 JSON을 만들고 `GOLD_RESOURCE` 상수(`SearchQualityEvaluationTest.java`)를 바꾸면 됩니다.
- recall@k/nDCG@k 계산 자체(`SearchQualityMetrics`)는 순수 함수라 `SearchQualityMetricsTest`로 항상 검증되며 라이브 서버가 필요 없습니다.
- **2026-07-16 실측 baseline**(hybrid=true·rerank=false·multiquery=true·topK=7): mean recall@10=0.962, nDCG@10=0.810 — 검색 튜닝 변경 후 이 수치와 비교해 회귀 여부를 판단하세요.

---

## 7. 벡터 스토어 관리

`/admin` 페이지(네비게이션 라벨: **벡터 스토어 관리**)의 접근 제어는 인증 모드에 따라 다릅니다.  
전체 인증 모드(`app.auth.enabled=true`)에서는 로그인된 사용자만 접근 가능합니다.  
평문 no-auth 모드(`app.auth.enabled=false`, `app.auth.management-only=false`)에서는 `/admin/**` 경로에 자동으로 관리자 계정이 주입됩니다.  
관리 전용 인증 모드(`app.auth.management-only=true`)에서는 실제 로그인(`/login`)이 필요합니다 — 자세한 내용은 [§9.4.2](#942-관리-전용-인증-management-only) 참조.

### 7.1 주요 기능

`/admin`은 **chroma·sqlite-vec 두 백엔드 모두** 동일한 레이아웃으로 동작하며, 표시 지표·라벨만 백엔드에 맞게 바뀝니다.

| 기능 | 설명 |
|------|------|
| Vector Store 상태 카드 | 백엔드 종류·정상 여부·청크 수. chroma=컬렉션 수 / sqlite-vec=문서 수·`vec_version`·임베딩 차원 |
| 컬렉션·버전 목록 | chroma=컬렉션별 / sqlite-vec=버전별 청크 수 표시 (클릭 시 청크 조회) |
| 청크 조회 | 컬렉션(또는 버전)·문서(docId)별 청크 페이지네이션 (50건 단위) — ID·텍스트 미리보기·크기·파일명·페이지/슬라이드·**챕터**·키워드·작업 컬럼. 챕터 컬럼은 `MetaKey.CHAPTER_NO`(H2~H6 헤딩 기반 계층 번호)를 보여주며 "0"(실제 챕터 없음)이면 빈 칸으로 표시 |
| 청크 편집 | 텍스트·메타데이터 수정 (원본 임베딩 유지 — 벡터 재계산 안 함) |
| 청크 삭제 | 개별 청크 즉시 제거. sqlite-vec는 `vec_document_chunks`+`vec_embeddings` 두 테이블 동기 삭제 |
| 문서 레지스트리 | 인덱싱된 전체 문서 목록 + 문서별 청크 바로 조회 (백엔드 무관, SQLite `doc_registry` 기반) |
| MD 재인덱싱 (↺ 버튼) | `{docId}_corrected.md`(없으면 `{docId}.md`)를 읽어 청크 재생성·재인덱싱 — DOCX·TXT·PPTX·PDF(스캔 아님) 지원, 원본 재업로드 불필요 (스캔 PDF는 MD 파일이 없어 미지원) |

### 7.2 MD 재인덱싱 흐름

1. `data/converted/{docId}_corrected.md` 파일을 텍스트 에디터로 직접 수정
2. 벡터 스토어 관리 페이지 문서 레지스트리에서 해당 문서의 ↺ 버튼 클릭
3. 결정적(비-LLM) MD 정리 — 존재하지 않는 이미지 마커 제거 → 소제목 번호 재검증 → 마크다운 후처리 (§7.3 참고, 변경 있으면 MD 파일에도 반영)
4. 정리된 MD 기준으로 청크 분할 → 키워드 추출(LLM) → 활성 백엔드에 재등록
5. 신규 청크 저장이 끝난 뒤에야 기존 벡터 청크 삭제 — 활성 백엔드(chroma 또는 sqlite-vec) (MD 파일·이미지 보존, 저장 실패 시 기존 데이터 보존)

> **API 직접 호출**: `POST /admin/documents/{docId}/reindex`

### 7.3 주의사항

- **임베딩 미갱신 (청크 편집)**: 청크 텍스트를 편집 패널에서 수정해도 벡터 임베딩은 재계산되지 않습니다. 임베딩까지 갱신하려면 MD 파일 수정 후 ↺ 재인덱싱을 사용하세요.
- **MD 재인덱싱 대상**: DOCX·TXT·PPTX·PDF(스캔 아님) 업로드 시 생성된 `_corrected.md` 파일이 없으면 `{docId}.md` 원본으로 fallback됩니다. 스캔 PDF처럼 MD 파일 자체가 없는 문서는 재인덱싱 불가 (에러 메시지 표시).
- **소제목 번호 재검증**: 재인덱싱 시 저장된 MD에 이미 번호 매겨진 헤딩이 있으면 현재 헤딩 구조 기준으로 다시 계산해 파일에도 반영합니다(PPTX 제외 — [§3.3 소제목 숫자 생성](#33-applicationproperties-전용-설정) 참고). 번호가 원래 없던 문서에는 새로 번호를 붙이지 않습니다.
- **마크다운 후처리 재적용**: 재인덱싱 시 결정적(비-LLM) 정리도 다시 적용됩니다 — `[DOCUMENT]` 마커·내용 없는 `-` 줄 제거, 코드 블록·표 앞뒤 빈 줄 보장, 연속 빈 줄을 1개로 축소(모든 형식 대상, PPTX 포함). 코드펜스 언어 보정(`fixClosingFences`/`normalizeCodeBlocks`)은 재인덱싱에 **포함되지 않습니다** — MD를 직접 편집한 뒤 재인덱싱하면 코드 블록 안의 의도된 빈 줄이 지워지거나 펜스 태그가 잘못 벗겨질 위험이 있어, 매번 감수하지 않고 필요할 때(재업로드)만 적용되도록 남겨둔 설계입니다. 상세는 [PIPELINE.md §6.4](PIPELINE.md#64-문서-타입별-처리-상세) 참고.
- **청크 단독 삭제 vs. 문서 삭제**: 청크를 개별 삭제해도 SQLite `doc_registry` 테이블의 레지스트리 항목은 남습니다. 문서 전체 제거는 Documents 페이지 또는 `DELETE /api/v1/documents/{docId}`를 사용하세요.
- **접근 제어**: `app.auth.enabled=true`(기본)이면 `/admin`도 로그인 필요. 평문 no-auth 모드에서는 누구나 `/admin`에 접근 가능하므로 내부망 또는 리버스 프록시 수준에서 경로를 제한하거나, [§9.4.2 관리 전용 인증](#942-관리-전용-인증-management-only)으로 전환해 애플리케이션 레벨에서 잠그는 것을 권장합니다.

### 7.4 백엔드별 표시 차이 (레이아웃·기능은 동일)

| 항목 | chroma | sqlite-vec |
|------|--------|------------|
| 상태 카드 backend 배지 | `chroma` | `sqlite-vec` |
| 상태 카드 — 문서 수 | 미표시(컬렉션은 distinct 문서수 미추적) | 표시 |
| 상태 카드 — 컬렉션 수 | 표시 | 미표시 |
| 상태 카드 — `vec_version` / 차원 | 미표시 | 표시 |
| ChromaDB 연결 불가 경고 배너 | 연결 실패 시 표시 | 표시 안 함 |
| 좌측 패널 라벨 | "ChromaDB 컬렉션" | "버전 (sqlite-vec)" |
| 청크 데이터 소스 | `ChromaApi` | `vec_document_chunks` 테이블 |

> 공통: 상태 배지·청크 수·버전별 청크 수·청크 조회/편집/삭제·문서 레지스트리·MD 재인덱싱은 두 백엔드 동일.

---

## 8. 문제 해결

### 애플리케이션이 시작되지 않음

```bash
# 시작 로그 확인
mvn spring-boot:run 2>&1 | head -80

# 헬스 체크
curl http://localhost:8080/api/v1/health

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

### 다수 사용자 동시 요청 시 429 응답 ("현재 요청이 몰려 있습니다")

500(장애)이 아니라 429(용량 초과) 응답이고, `/llm-usage`에는 해당 프로바이더가 차단(빨간 카드)으로 표시되지 않는다면 동시성 게이트가 정상 동작 중인 것입니다 — Circuit Breaker와는 별개입니다(§5.5·§5.7 참고).

```bash
# 로그에서 백프레셔 발생 빈도 확인
grep "\[BACKPRESSURE\]" logs/*.log | tail -20
```

| 상황 | 조치 |
|------|------|
| 가끔 1~2회 발생, 재시도하면 바로 성공 | 정상 동작 — 순간적인 동시 접속 피크. 조치 불필요 |
| 특정 프로바이더에서 지속 반복 | `LLM_DEFAULT_PROVIDER_CONCURRENCY`(또는 해당 프로바이더의 `concurrency`)가 실제 서버 `--parallel`보다 낮게 설정됐는지 확인 후 상향 — 단, 서버가 실제로 그만큼 처리 가능한 경우에만 |
| 사용자 대기 시간이 너무 김 | `LLM_PERMIT_WAIT_TIMEOUT_SECONDS`(기본 20초)를 늘려 대기 상한을 확대 — 단, `LLM_READ_TIMEOUT_SECONDS`(기본 180초)보다는 충분히 짧게 유지 |
| 물리 서버 자체가 상시 포화 | 동일 role·동일 priority로 프로바이더를 추가 등록하면(§5.4 예제 5) 잔여 permit이 가장 많은 쪽으로 자동 분산됩니다. 그래도 부족하면 NORMAL/PREMIUM 외부 fallback을 함께 구성해 부하를 분산하세요 |

상세 동작 원리는 [§5.7 동시성 제어 및 백프레셔](#57-동시성-제어-및-백프레셔)를 참고하세요.

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
- 키워드+맥락 추출(`KeywordExtractor`)이 청크당 LLM 호출 → 문서 수 많을수록 시간 증가 (의도된 동작)
- DOCX·TXT·PPTX·PDF(스캔 아님)는 LLM 포맷 교정(섹션당 1회 LLM 호출)이 추가되어 스캔 PDF(OCR만 수행, 포맷 교정 없음)보다 인덱싱 시간이 더 길 수 있습니다. 교정 실패 시 원본 MD로 fallback됩니다.

---

### 이미지 썸네일 링크가 클릭 시 "연결할 수 없음"으로 뜸

`GET /api/v1/images/{docId}/{filename}`(경로 세그먼트 이름은 `docId`이지만 실제 값은 `imageId` — 문서 SHA-256 기반 해시 키)은 경로 순회(path traversal) 방지를 위해 두 세그먼트 중 하나에라도 `..`가 포함되면 400을 반환합니다. **PPTX에서 추출된 이미지 파일명이 `img1..png`처럼 점이 두 개 겹친 형태**라면 아래 원인입니다.

| 원인 | 조치 |
|------|------|
| Apache POI `PictureType.extension`이 이미 `.png`처럼 점을 포함하는데, 파일명 조립 시 점을 한 번 더 붙여 `img1..png`가 됨 (해당 버전 이전 `PptxImageExtractor` 버그) | 코드는 이미 수정됨. **이 버그가 있던 버전으로 인덱싱된 PPTX 문서**는 `data/images/{imageId}/`(구버전은 `data/images/{docId}/`)에 이미 잘못된 파일명으로 저장돼 있고, 벡터 스토어에 저장된 청크 내용에도 잘못된 경로 문자열이 그대로 박혀 있으므로 **파일만 이름 변경해서는 해결되지 않습니다** — 해당 문서를 삭제 후 재업로드하거나 `POST /api/v1/documents/sync`로 재동기화하세요 |
| 확인 방법 | `find data/images -name "*..*"`로 이중 점 파일명이 있는지 검사 |

---

### 이미지 파일이 `data/images/`에서 사라진 경우 (수동 정리·백업 복원 누락 등)

MD 재인덱싱(`/admin` ↺ 버튼, `AdminController.reindex()` → `DocumentIndexer.reindexFromMd()`)은 로드 직후 `[이미지: path]`/`[이미지(변환불가): path]` 마커가 가리키는 파일이 `data/images/`에 실제로 존재하는지 확인합니다. 없으면 그 마커만 제거한 뒤 청킹·인덱싱을 진행하고, 정리된 결과를 MD 파일(`{docId}[_corrected].md`)에 다시 저장합니다 — 다음 재인덱싱부터는 같은 마커를 다시 걸러낼 필요가 없습니다. 존재하는 이미지 마커는 영향받지 않습니다.

- 일반 업로드/동기화(`index()`)는 대상이 아닙니다 — 그 경로는 변환과 이미지 추출이 같은 호출 안에서 함께 일어나므로 마커와 파일이 어긋날 여지가 없습니다.
- 이미지가 사라진 원인 자체(디스크 정리 스크립트, 백업 복원 누락 등)는 운영자가 조사해야 합니다 — 이 동작은 인덱스가 죽은 링크로 오염되는 것만 막을 뿐, 사라진 이미지 파일을 복구하지 않습니다.
- 상세 구현은 [PIPELINE.md §6.4](PIPELINE.md#64-문서-타입별-처리-상세) "존재하지 않는 이미지 마커 정리" 참고.

---

### 임베딩 서버 배치/토큰 초과 (`input (N tokens) is too large to process`)

인덱싱 중 임베딩 서버(llama-server 등)에서 아래 같은 에러가 나면, **한 청크의 토큰 수가 서버의 물리 배치(physical batch) 한계를 초과**한 것입니다.

```
srv send_error: ... error: input (706 tokens) is too large to process. increase the physical batch size (current batch size: 512)
```

근본 원인: `CHUNK_SIZE`는 **문자 수**인데 임베딩 한계는 **토큰 수**입니다. 한국어·마크다운·코드는 대략 1토큰/문자에 가까워서 `CHUNK_SIZE=700`이면 700자 청크가 ~700토큰이 되어 512 배치를 넘습니다. 표·코드블록을 통째로 유지하거나 작은 청크가 병합되면 청크가 더 커질 수도 있습니다.

| 조치 | 방법 | 비고 |
|------|------|------|
| **① 임베딩 서버 배치 확대 (1순위 권장)** | llama-server 임베딩 인스턴스를 `-b 2048 -ub 2048`(또는 그 이상)로 재기동 | bge-m3는 8192 토큰까지 지원하므로 안전. 청크 품질 손실 없이 에러 제거. `-ub`(=`--ubatch-size`)가 핵심 |
| **② 청크 하드 상한 설정 (앱 측 안전장치)** | `.env`에 `EMBED_MAX_CHUNK_CHARS=450`(512토큰 배치 기준) 설정 후 재인덱싱 | 이 값을 넘는 청크는 줄 경계에서 강제 재분할됨. 서버를 못 건드릴 때 사용. 값이 작을수록 청크가 잘게 나뉘어 검색 문맥이 짧아지는 트레이드오프 |
| **③ CHUNK_SIZE 축소** | `CHUNK_SIZE`를 700 → 450~500으로 | ②와 목적은 같지만 의미 단위 청킹 자체가 작아짐. ②가 더 정밀(소프트 목표 크기와 하드 상한을 분리) |

> ①로 근본 해결이 안 되는 폐쇄망·고정 서버 환경에서는 ②를 상한(guardrail)으로 두는 조합을 권장합니다. `EMBED_MAX_CHUNK_CHARS`는 의미 단위 청킹(헤딩 재삽입·작은 청크 병합 포함)이 모두 끝난 **마지막 단계**에서 적용되므로 어떤 청크도 이 값을 넘지 않는 것이 보장됩니다.

---

### 로컬 LLM 응답 타임아웃 (`SSE worker cancelled`)

두 가지 다른 원인이 같은 로그 패턴(`SSE worker cancelled`)으로 나타납니다.

- **`[TIMEOUT:SSE_IDLE]`** — 에이전트 그래프가 `app.sse-idle-timeout-seconds`(기본 120초) 동안 노드 전환·토큰·소스 준비 신호를 전혀 못 받음. 로컬 LLM 서버(LM Studio 등)가 요청을 받고도 응답을 전혀 생성하지 못하는(멈춘) 경우가 전형적입니다. **응답이 느리더라도 토큰이 계속 나오고 있다면 이 타임아웃에 걸리지 않습니다** — 매 신호마다 리셋되기 때문입니다.
- **`[TIMEOUT:SSE]`** — `app.sse-timeout-seconds`(기본 3600초) 절대 상한 초과. 응답이 활동 중이어도(토큰이 계속 나와도) 총 소요 시간이 이 값을 넘으면 발생 — 극히 드묾, 안전장치 성격.

| 원인 | 확인 방법 | 조치 |
|------|----------|------|
| LLM 서버 미실행·모델 미로드 | LM Studio 상태 확인 | 모델 로드 완료 후 재시도 |
| `base-url`에 `/v1` 중복 | 시작 로그 `endpoint=...` 확인 | `base-url`에 `/v1` 포함 여부와 무관하게 내부 자동 처리됨. 앱 재시작 |
| 구버전 앱에서 `stream=false` 설정 | — | 최신 버전은 내부적으로 스트리밍 방식으로 대체함. 앱 재시작 |

타임아웃이 반복되면 아래 순서로 조정하세요.

1. `[TIMEOUT:SSE_IDLE]`이 반복되면 `SSE_IDLE_TIMEOUT_SECONDS` 증가 (기본 120, 예: 120 → 300) — LLM이 첫 토큰을 내기까지 오래 걸리는 환경(느린 하드웨어, 큰 모델)에 해당
2. `[TIMEOUT:SSE]`가 발생하면 `SSE_TIMEOUT_SECONDS` 증가 (기본 3600, 예: 3600 → 7200)
3. 인덱싱 중 키워드 추출이 자주 timeout이면 `INDEXING_KEYWORD_TIMEOUT_SECONDS` 증가 (§10.8.2로 `INDEXING_KEYWORD_BATCH_SIZE`를 올린 경우 배치 1회의 응답 길이도 함께 늘어나므로 우선 검토 — 안 되면 배치 크기를 낮추는 것도 방법)
4. 외부 LLM이 느린 경우 `LLM_READ_TIMEOUT_SECONDS` 증가
5. 임베딩 단계 지연 시 `EMBED_READ_TIMEOUT_SECONDS` 증가
6. Chroma 지연 시 `CHROMA_READ_TIMEOUT_SECONDS` 증가

타임아웃 원인은 로그 키로 즉시 구분할 수 있습니다.

| 로그 키 | 의미 |
|--------|------|
| `[TIMEOUT:SSE]` | SSE 연결 자체 timeout (브라우저 ↔ 서버) |
| `[TIMEOUT:ASYNC_MVC]` | Spring MVC async 요청 timeout |
| `[TIMEOUT:LLM_HTTP]` | LLM API 호출 중 connect/read timeout 계열 예외 |
| `[TIMEOUT:INDEX_KEYWORD]` / `[TIMEOUT:INDEX_KEYWORD_BATCH]` | 인덱싱 중 청크 키워드 추출 timeout(설정된 초 초과)으로 TF fallback. **주의**: LLM 호출 자체 실패(프로바이더 소진 등)는 이 태그가 아니라 `[ENRICH]`/`[ENRICH-BATCH]` (DEBUG)로 별도 기록 — timeout이 아님 |
| `[TIMEOUT:LIBREOFFICE]` | DOCX WMF 변환(soffice) timeout |

---

### LLM 요청/응답 디버깅

#### 로거별 레벨과 출력 내용

각 로거를 어떤 레벨로 설정할 때 무엇이 출력되는지 정리합니다.

| 로거 | 레벨 | 출력 내용 |
|------|------|----------|
| `com.example.ragagent` | `INFO` | 인덱싱 시작/완료, 동기화 결과, 프로바이더 등록 목록 |
| `com.example.ragagent` | `DEBUG` | ↑ + 에이전트 노드 흐름, 프로바이더 라우팅 결정, **curl 재현 명령** (시스템 프롬프트·검색 문서·사용자 질문 전체 포함) |
| `org.springframework.ai.openai` | `DEBUG` | Spring AI가 직렬화한 `ChatCompletionRequest` JSON (모델명·메시지 배열·temperature 등) 및 응답 메타데이터 |
| `reactor.netty.http.client` | `DEBUG` | HTTP 연결 이벤트(CONNECT/DISCONNECT), **요청·응답 헤더** (URI·상태코드 포함) — body 내용은 포함되지 않음 |
| `reactor.netty.http.client` | `TRACE` | ↑ + **실제 HTTP body 바이트** (요청 JSON, SSE 응답 청크 전체) — 스트리밍 중 매우 방대 |

> `com.example.ragagent=DEBUG` 활성화 시 `LoggingChatModel`이 LLM에 전송한 프롬프트 전체(RAG 검색 문서 원문·대화 이력 포함)를 curl 명령 형식으로 출력합니다. 민감 정보가 포함될 수 있으므로 운영 로그 취급에 주의하세요.

---

#### 런타임 레벨 변경 (Actuator)

재시작 없이 `/actuator/loggers` 엔드포인트로 레벨을 변경합니다.  
변경은 **JVM 프로세스 내에서만 유효**하며, 재시작 시 `application.properties` 값으로 초기화됩니다.

```bash
# 형식: POST /actuator/loggers/{logger-name}
#   Body: {"configuredLevel": "TRACE"|"DEBUG"|"INFO"|"WARN"|"ERROR"|null}
#   null = 부모 로거에서 상속 (설정 해제)
#   성공 응답: HTTP 204 No Content (응답 바디 없음)

# ── 시나리오 1: 프로바이더 라우팅 + curl 재현 로그 ──────────────────────
curl -X POST http://localhost:8080/actuator/loggers/com.example.ragagent.llm \
  -H "Content-Type: application/json" \
  -d '{"configuredLevel":"DEBUG"}'

# ── 시나리오 2: 인덱싱 청크별 진행 상황 ────────────────────────────────
curl -X POST http://localhost:8080/actuator/loggers/com.example.ragagent.service.RagService \
  -H "Content-Type: application/json" \
  -d '{"configuredLevel":"DEBUG"}'

# ── 시나리오 3: 에이전트 그래프 흐름 (Classifier→Retrieval→Answer→Critic) ──
curl -X POST http://localhost:8080/actuator/loggers/com.example.ragagent.agent \
  -H "Content-Type: application/json" \
  -d '{"configuredLevel":"DEBUG"}'

# ── 시나리오 4: HTTP 요청/응답 헤더 확인 (URI·상태코드, body 제외) ────────
curl -X POST http://localhost:8080/actuator/loggers/reactor.netty.http.client \
  -H "Content-Type: application/json" \
  -d '{"configuredLevel":"DEBUG"}'

# ── 시나리오 5: HTTP body까지 확인 (SSE 청크 포함, 매우 방대) ────────────
curl -X POST http://localhost:8080/actuator/loggers/reactor.netty.http.client \
  -H "Content-Type: application/json" \
  -d '{"configuredLevel":"TRACE"}'

# ── 레벨 되돌리기 ───────────────────────────────────────────────────────
curl -X POST http://localhost:8080/actuator/loggers/com.example.ragagent.llm \
  -H "Content-Type: application/json" \
  -d '{"configuredLevel":"INFO"}'

curl -X POST http://localhost:8080/actuator/loggers/reactor.netty.http.client \
  -H "Content-Type: application/json" \
  -d '{"configuredLevel":"INFO"}'

# ── 현재 레벨 확인 ──────────────────────────────────────────────────────
curl http://localhost:8080/actuator/loggers/com.example.ragagent
```

**Windows CMD**:
```cmd
curl -X POST http://localhost:8080/actuator/loggers/com.example.ragagent.llm -H "Content-Type: application/json" -d "{\"configuredLevel\":\"DEBUG\"}"
```

> **팁**: `-i` 플래그를 추가하면 204 상태코드로 성공 여부를 확인할 수 있습니다.

---

#### 권장 운영 설정 (`application.properties`)

```properties
# 앱 네임스페이스 — INFO: 인덱싱·프로바이더 등록 이벤트만 출력
# curl 재현 로그(프롬프트 전체 포함) 비활성화
logging.level.com.example.ragagent=INFO

# Spring AI 내부 — WARN: 정상 동작 시 출력 없음
logging.level.org.springframework.ai.openai=WARN

# Reactor Netty 와이어 로그 — 기본 off; 필요 시 Actuator로 활성화
# logging.level.reactor.netty.http.client=DEBUG
```

개발·디버깅 중에는 필요한 로거만 Actuator로 선택적으로 활성화하는 방식을 권장합니다.

---

## 9. 보안 설정

### 9.1 git 훅 설치

`.env` 파일이 실수로 커밋되지 않도록 pre-commit 훅을 설치하세요.

```bash
sh scripts/install-hooks.sh
```

팀원 각자가 클론 후 1회 실행합니다.

### 9.2 입력 검증 동작

| 항목 | 제한 | 응답 |
|------|------|------|
| 질문 길이 | 최대 2,000자 | 400 Bad Request |
| 파일 업로드 크기 | 최대 200 MB (기본) | 413 Payload Too Large |
| 파일 형식 불일치 (매직바이트) | 확장자와 실제 내용이 다른 경우 | 422 Unprocessable Entity |
| API 요청 빈도 | 경로별 분당 제한 | 429 Too Many Requests |

업로드 허용 형식과 매직바이트 매핑:

| 확장자 | 검증 기준 |
|--------|----------|
| `.pdf` | `%PDF` 서명 (4바이트) |
| `.docx`, `.pptx` | ZIP/PK 서명 `50 4B 03 04` (4바이트) |
| `.txt`, `.md` | 첫 8바이트에 NUL 문자 없음 |

### 9.3 응답 크기 제한

LLM 응답이 20,000자를 초과하면 자동으로 잘리고 말줄임 메시지가 추가됩니다.

### 9.4 인증 토글 (no-auth 모드)

`app.auth.enabled=false`로 설정하면 로그인 없이 사용할 수 있습니다 (로컬·단일 사용자 환경에 적합). 여기에는 두 서브모드가 있습니다 — **평문 no-auth**(기본, 아래 §9.4.1)는 모든 경로가 열려 있고, **관리 전용 인증**(§6.17 B안, 아래 §9.4.2)은 채팅·조회는 열어두되 문서 관리 쓰기와 `/admin`만 로그인을 요구합니다.

| 모드 | `app.auth.enabled` | `app.auth.management-only` | 요약 |
|------|--------------------|-----------------------------|------|
| 전체 인증 | `true` | (무의미 — `authSafe()`가 자동으로 `false` 정규화) | 모든 경로 로그인 필요 |
| 평문 no-auth (기본) | `false` | `false` | 모든 경로 게스트 자동 인증, `/admin`도 자동 |
| 관리 전용 인증 | `false` | `true` | 채팅·조회는 게스트 자동 인증, 문서 관리 쓰기·`/admin`만 로그인 필요 |

#### 9.4.1 평문 no-auth 모드

**동작**:

| 항목 | 동작 |
|------|------|
| CSRF 보호 | 비활성화 |
| 세션 관리 | STATELESS (세션 불필요) |
| 첫 접속 (admin DB 없음) | `/setup` 페이지로 리다이렉트 |
| `/setup` | 관리자 이메일·비밀번호 입력 → DB에 `ROLE_ADMIN` 계정 생성 |
| `/admin/**` 접근 | DB 첫 번째 `ROLE_ADMIN` 계정으로 자동 인증 |
| 그 외 모든 경로 | 고정 guest 계정 자동 인증 (userId = `00000000-0000-0000-0000-000000000001`, `ROLE_USER`) |
| 로그아웃 버튼 | Navbar에서 숨겨짐 |

**설정 예시**:
```env
# .env (권장 — 재빌드 없이 전환 가능)
AUTH_ENABLED=false
```
```properties
# application.properties (직접 편집 방식)
app.auth.enabled=false
```

> **주의**: 이 모드에서는 모든 사용자가 guest 파티션을 공유하고 `/admin`도 자동 인증됩니다. 문서 관리·`/admin`만이라도 잠그려면 아래 §9.4.2를 사용하세요.

#### 9.4.2 관리 전용 인증 (management-only)

`app.auth.enabled=false` + `app.auth.management-only=true`. 공용/외부 노출 채팅 데모처럼 **채팅·문서 조회는 로그인 없이 열어두되, 문서 업로드·삭제·동기화(웹 UI)와 `/admin/**`만 실제 로그인을 요구**하고 싶을 때 사용합니다. 재빌드 없이 기존 no-auth 배포에 바로 얹을 수 있는 서브모드입니다(§6.17 B안).

**설정 예시**:
```env
# .env
AUTH_ENABLED=false
AUTH_MANAGEMENT_ONLY=true
```

**동작**:

| 항목 | 동작 |
|------|------|
| CSRF 보호 | **활성화**(`CookieCsrfTokenRepository`) — 평문 no-auth와 달리 로그인 세션을 지켜야 하므로 `formLogin()`을 쓸 수 있게 CSRF도 함께 켠다 |
| 세션 관리 | `IF_REQUIRED` — 실제 로그인이 발생할 때만 세션 생성(게스트 트래픽은 세션 비용 없음) |
| 첫 접속 (admin DB 없음) | `/setup` 페이지로 리다이렉트 (평문 no-auth와 동일) |
| `/setup` | 관리자 이메일·비밀번호 입력 → DB에 `ROLE_ADMIN` 계정 생성. **생성 직후 자동 로그인은 되지 않음** — `/login`으로 별도 로그인 필요 |
| 채팅(`/`, `/chat/**`) | 로그인 없이 게스트 자동 인증 (평문 no-auth와 동일) |
| `GET /documents`, `GET /ui/documents/list`, `GET /api/v1/documents` | 로그인 없이 조회 가능 |
| 문서 관리 쓰기(업로드, 업로드취소, 삭제, 태그 수정·편집) | **로그인 필요** — 비로그인 시 `/login` 리다이렉트, `ROLE_ADMIN` 아닌 로그인은 403 |
| `/admin/**` | **로그인 필요** — 게스트/첫 관리자 자동 주입 없음(평문 no-auth와의 핵심 차이) |
| `/api/v1/documents/**` REST 엔드포인트 | **의도적으로 그대로 열어둠 + CSRF 예외** — `POST /api/v1/documents/sync` 등 curl 자동화([§6.2](#62-문서-버전-관리) 참조)가 그대로 인증 없이 동작 |
| Web UI 게스트 화면 | 업로드 카드·삭제 버튼·Admin 내비가 숨겨짐(관리자로 로그인해야 노출) |
| 로그아웃 버튼 | 관리자로 로그인했을 때만 노출 |

**로그인 → 관리 흐름**:
1. `/setup`에서 관리자 계정 생성 (최초 1회, 평문 no-auth와 동일)
2. `/login`에서 방금 만든 이메일·비밀번호로 로그인
3. 로그인 세션이 유지되는 동안 `/documents`에서 업로드·삭제, `/admin`에서 청크 관리 가능
4. 다른 탭/시크릿 창은 여전히 게스트 — 관리 기능은 로그인한 브라우저 세션에서만 보임

> **주의**: 평문 no-auth와 마찬가지로 채팅·문서 조회는 guest 파티션을 공유합니다. 이 모드는 "누가 관리할 수 있는가"만 잠그며 사용자별 데이터 격리는 제공하지 않습니다 — 멀티유저 격리가 필요하면 전체 인증 모드(`app.auth.enabled=true`)를 사용하세요.

**인증 재활성화 (전체 인증 모드로 전환)**:
1. `app.auth.enabled=true`로 변경 후 재시작 (`app.auth.management-only`는 자동으로 무시됨)
2. `/setup`(또는 관리 전용 인증에서 이미 만든) 계정의 이메일·비밀번호로 `/login` 접속
3. 대화 이력(스레드)은 userId 기반으로 그대로 유지됩니다. 문서는 애초에 사용자별 격리 없이 공유 저장소(`DocRegistry.SHARED`)이므로, 전체 인증 모드로 전환한 뒤에도 모든 로그인 계정이 동일한 문서 목록을 봅니다 — 계정별로 문서가 분리되지 않습니다

---

## 10. 운영 체크리스트

배포 후 순서대로 확인하세요.

**초기 설정**:
- [ ] `sh scripts/install-hooks.sh` — pre-commit 훅 설치 (팀원 각자 1회)
- [ ] 인증 모드 설정 확인 — `.env`의 `AUTH_ENABLED` 또는 `application.properties`의 `app.auth.enabled` (기본 `true` = 로그인 필요 / `false` = no-auth 모드)
- [ ] (no-auth 모드) 문서 관리·`/admin`도 로그인 없이 열어둘지, 관리 전용으로 잠글지 결정 — 잠그려면 `AUTH_MANAGEMENT_ONLY=true` (§9.4.2)
- [ ] (no-auth 모드) 첫 접속 시 `/setup` 페이지에서 admin 계정 생성 완료 확인
- [ ] (관리 전용 인증 모드) `/setup` 계정 생성 후 `/login`으로 별도 로그인 확인(자동 로그인되지 않음)
- [ ] (auth 모드) `/signup`에서 첫 계정 생성 후 `/login` 접속 확인

**HTTPS (인터넷 공개 배포 시)**:
- [ ] HTTPS 방식 선택: DuckDNS(공인 IP 보유) / Cloudflare Tunnel(IP 없음) / HTTP 로컬 전용
- [ ] (DuckDNS) 서브도메인 생성 + 서버 IP 등록 완료
- [ ] (DuckDNS) `Caddyfile`에 실제 도메인 입력, `docker-compose.yml`에 Caddy 서비스 추가
- [ ] (DuckDNS) `app` 서비스의 `ports: ["8080:8080"]` 제거 (외부 직접 접근 차단)
- [ ] (DuckDNS) `docker-compose up` 후 `https://도메인` HTTPS 접속 확인
- [ ] (DuckDNS) HTTP → HTTPS 자동 리다이렉트 확인
- [ ] (DuckDNS) 유동 IP 환경이면 cron DuckDNS 갱신 스크립트 등록 확인
- [ ] (Cloudflare Tunnel) `.env`에 `CLOUDFLARE_TUNNEL_TOKEN` 추가, docker-compose에 cloudflared 서비스 추가
- [ ] (Cloudflare Tunnel) 대시보드 Public Hostname → `http://app:8080` 라우팅 설정 확인

**기본 동작**:
- [ ] `GET /api/v1/health` → `{"status":"ok"}` 응답
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
- [ ] (auth 모드) 미로그인 상태에서 `/` 접속 → `/login` 리다이렉트 확인
- [ ] (평문 no-auth 모드) `/admin` 경로에 대한 네트워크 접근 제한 적용 여부 확인
- [ ] (관리 전용 인증 모드) 게스트로 `/admin` 및 문서 업로드 시도 → `/login` 리다이렉트 확인, 게스트 화면에 업로드 카드 미노출 확인
- [ ] (관리 전용 인증 모드) 관리자 로그인 후 `/admin`·문서 업로드/삭제 정상 동작 + 다른 페이지 이동 후에도 로그인 상태 유지 확인
- [ ] (관리 전용 인증 모드) `curl -X POST ".../api/v1/documents/sync"` 무인증 호출 정상 동작 확인(curl 자동화 보존)

**LLM 및 운영**:
- [ ] `/llm-usage` — 프로바이더 카드 정상(초록) 확인
- [ ] `/llm-usage` — 일별 차트 데이터 표시 확인
- [ ] `/llm-usage` — `embed:<model>` 카드가 채팅 프로바이더와 분리 표시되고 인덱싱/검색 후 토큰이 누적되는지 확인
- [ ] `/llm-usage` — 키 없는(비활성) 프로바이더 중 사용 이력 없는 항목이 카드·표·차트에서 숨겨지는지 확인
- [ ] `/llm-usage` — orphan 카드(있다면) 삭제 버튼 클릭 → 카드 사라짐 + `AuditLogger`에 `llm-usage.delete-orphan` 기록 확인, 활성 프로바이더는 삭제 버튼이 없는지 확인
- [ ] Circuit Breaker 차단 없음 확인
- [ ] 데이터 디렉터리(`data/`) 마운트 및 쓰기 권한 확인
- [ ] Chroma 볼륨 영속성 확인 (재시작 후 문서 목록 유지)
- [ ] `/admin` 접속 → 컬렉션 목록·청크 테이블 정상 표시 확인
- [ ] DOCX 업로드 후 `data/converted/{docId}_corrected.md` 생성 확인
- [ ] 벡터 스토어 관리 페이지 ↺ 버튼으로 MD 재인덱싱 성공 확인
- [ ] (운영 환경) `/admin` 경로에 대한 네트워크 접근 제한 적용 여부 확인

**태그 기반 검색 적용 시 (프리릴리즈 정책)**:
- [ ] 적용 전 백업 여부 결정 및 수행 (선택)
- [ ] `data/memory.db`(+wal/shm), `data/documents`, `data/converted`, `data/images` 수동 초기화 완료
- [ ] (chroma) `data/chroma` 또는 `chroma_data` 볼륨 초기화 완료
- [ ] 재기동 후 `/setup` 또는 로그인 경로 정상 확인
- [ ] 문서 재업로드/동기화 후 태그 엄격 필터 동작 확인
