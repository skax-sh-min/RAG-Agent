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
      - 6.3.1 [SQLite 파일별 테이블 구성](#631-sqlite-파일별-테이블-구성)
   - 6.4 [성능](#64-성능)
   - 6.5 [설정 페이지 (/settings) — LLM/RAG 옵션 조회·핫 수정](#65-설정-페이지-settings--llmrag-옵션-조회핫-수정)
   - 6.6 [검색 품질 평가 하네스 (개발자용)](#66-검색-품질-평가-하네스-개발자용)
   - 6.7 [큐레이션 Q&A (좋아요 기반 지식 승격, §10.10)](#67-큐레이션-qa-공유-지식-축-1010--1011)
   - 6.8 [문서 내보내기](#68-문서-내보내기)
   - 6.9 [지식 제안 게시판 (사용자 제안 → 관리자 임베딩)](#69-지식-제안-게시판-사용자-제안--관리자-임베딩)
   - 6.10 [청크 분할 전략 (크기 기준 병합 / 소제목 최대 분할)](#610-청크-분할-전략-크기-기준-병합--소제목-최대-분할)
  - 6.11 [중복 질문 재사용 (추천·검증·무효화)](#611-중복-질문-재사용-추천검증무효화)
  - 6.12 [청크 오류 신고 처리 (사용자 신고 → 관리자 확인·수정)](#612-청크-오류-신고-처리-사용자-신고--관리자-확인수정)
7. [벡터 스토어 관리](#7-벡터-스토어-관리)
8. [문제 해결](#8-문제-해결)
9. [보안 설정](#9-보안-설정)
   - 9.1 [git 훅 설치](#91-git-훅-설치)
   - 9.2 [입력 검증 동작](#92-입력-검증-동작)
   - 9.3 [응답 크기 제한](#93-응답-크기-제한)
   - 9.4 [인증 토글 (no-auth 모드)](#94-인증-토글-no-auth-모드)
      - 9.4.1 [평문 no-auth 모드](#941-평문-no-auth-모드)
      - 9.4.2 [관리 전용 인증 (management-only)](#942-관리-전용-인증-management-only)
      - 9.4.3 [접속자별 채팅 개인화 (`app.auth.guest-identity`)](#943-접속자별-채팅-개인화-appauthguest-identity)
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
          └─ 충분      ──▶ [Finalize] (responseMode=S)
               └─▶ [Critic]   (responseMode!=S, 근거 검증)
                 ├─ 미근거 ──▶ [Retrieval]
                 └─ 근거   ──▶ [Finalize] → 응답
```

> 응답 모드가 `S`인 turn은 Answer 뒤 Critic을 건너뛰고 바로 Finalize로 종료합니다. `C`(응용)는 반대로 검증을 **끄지 않고 바꿔 낍니다** — "답변이 문서에 근거하는가" 대신 "문서 유래라고 제시한 이름이 실재하는가"를 묻고, 그래서 통과 배지가 초록 `검증됨` 이 아니라 파랑 `생성` 입니다. 문서에 없는 이름을 문서에 있는 것처럼 쓴 경우 `문서 밖 이름 N` 경고가 **통과한 답변에도** 함께 붙습니다(그 자체로 재시도를 걸지는 않습니다).

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
│                               #   ※ SQLITE_VEC_DB_PATH 를 켜면 이 내용도 그 벡터 DB 파일로 간다 (§6.3.1)
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
| `LOCAL_LLM_KEY` | — | `no-key` | `providers[1]` API 키. **로컬 엔드포인트(llama-server 등)는 키가 불필요** — 비우거나 미설정해도(URL만 설정돼 있다면) LOCAL provider는 등록됨(미설정 시 내부적으로 `no-key` 치환, G1). 완전히 제외하려면 `LOCAL_LLM_URL`을 비우거나(G2) `application.properties`의 `providers[1]`를 주석 처리 |
| `LOCAL_LLM_MODEL` | — | `google/gemma-4-e4b` | `providers[1]` 모델 식별자. 사용 중인 로컬 모델명으로 변경 |
| `LOCAL_LLM_TYPE` | — | `BOTH` | `providers[1]`(로컬 LLM 1) 작업 유형 (`app.llm.providers[1].type`) — `MICRO_TEXT`/`LIGHT_TEXT`/`TEXT`/`VISION`/`LIGHT_BOTH`/`BOTH` 중 하나. 기본 `BOTH`(모든 작업 처리). **텍스트 3종은 사다리라 `TEXT`는 채팅 답변뿐 아니라 `LIGHT_TEXT`·`MICRO_TEXT` 잡무까지 함께 받는다** — "채팅 전용으로 한정"하는 값이 아니다(§5.6 type 값 표). 이미지를 뺀 텍스트 전용 모델도 `BOTH`로 두는 것이 권장 — Vision 호출 1회만 실패한 뒤 기억되어 이후 이미지 작업에서 제외되고 텍스트 작업은 영향을 받지 않는다. 이미지 설명 자체를 끄려면 `IMAGE_DESCRIPTION_ENABLED=false`를 쓴다 |
| `LOCAL_LLM_URL_2` | 사용 시 ✅ | — (기본값 없음) | `providers[2]`(`local-2`, 로컬 LLM 2) 엔드포인트. `local`과 **동일한 role(LOCAL)·동일한 priority(1)**로 등록되어 두 번째 물리 서버로 로드밸런싱된다(least-in-flight — [§5.4 예제 5/7](#예제-5--로컬-llm-2대-로드밸런싱-처리량-확장) 참고). **미설정·공백이면 이 provider가 통째로 비활성화된다**(G2) — 2대째 로컬 서버가 없다면 그냥 비워두면 됨(회귀 0, `local` 단독으로 동작). 값을 설정하면 기동 시 접속 가능·모델명 일치 여부를 검증하며 실패 시 애플리케이션이 시작되지 않는다(G3) — 즉 "설정은 했지만 서버가 아직 안 떠 있다"는 이 변수를 비워두는 것과 결과가 다르다(전자는 기동 실패, 후자는 정상 기동) |
| `LOCAL_LLM_KEY_2` | — | `no-key` | `providers[2]` API 키. 로컬 엔드포인트는 키가 불필요 — 미설정 시 `no-key`가 치환되어 등록됨(`LOCAL_LLM_KEY`를 상속하지 않음). 모델명(`LOCAL_LLM_MODEL_2`)은 여전히 `LOCAL_LLM_MODEL`로 폴백 |
| `LOCAL_LLM_MODEL_2` | — | `LOCAL_LLM_MODEL` 폴백 | `providers[2]` 모델 식별자. 미설정 시 `LOCAL_LLM_MODEL`과 동일한 모델명을 사용(로컬 LLM 1과 동일 모델을 다른 서버에 복제하는 것이 일반적인 사용 사례) |
| `LOCAL_LLM_TYPE_2` | — | `BOTH` | `providers[2]`(로컬 LLM 2) 작업 유형 (`app.llm.providers[2].type`). 값 집합은 `LOCAL_LLM_TYPE`과 동일. 보통 로컬 LLM 1과 같은 `BOTH`를 사용 |
| `LOCAL_FAST_LLM_URL` | 사용 시 ✅ | — (기본값 없음) | §6.21 — `providers[0]`(`local-fast`, 소형 로컬 LLM 1) 엔드포인트. 잡무 전용 소형(~500MB) 모델을 `providers[1]`(`local`)과 **다른 포트/장비**에 띄우고 가리킨다.<br>**미설정·공백이면 이 provider가 통째로 비활성화된다**(G2) — 소형 모델 서버가 없다면 그냥 비워두면 됨(`MICRO_TEXT`는 `local`이 흡수. 단 **대화 요약만은 흡수하지 않고 생략**되어 채팅이 원본 history 폴백으로 동작한다 — 부가 기능인 요약이 답변용 모델의 동시성 슬롯을 잠식하지 않게 하려는 의도적 게이팅). 값을 설정하면 기동 시 접속 가능·모델명 일치 여부를 검증하며(G3, 기본 활성) 실패 시 애플리케이션이 시작되지 않는다 — "URL은 설정했지만 서버가 아직 안 떠 있어 매 요청마다 `local`로 런타임 폴백"되는 예전 동작은 `LLM_VERIFY_LOCAL_MODELS_ON_STARTUP=false`로 G3를 꺼야만 나온다 — 예제는 [§5.4 예제 6](#예제-6--소형경량-llm-분리로-잡무-오프로딩-plan-621) 참고 |
| `LOCAL_FAST_LLM_KEY` | — | — | `providers[0]` API 키. `LOCAL_LLM_KEY`와 마찬가지로 로컬 엔드포인트는 보통 불필요 — 비워도(URL만 설정돼 있다면) `no-key`가 치환되어 등록됨 |
| `LOCAL_FAST_LLM_MODEL` | — | `Qwen3.5-0.8B-Q4_K_M.gguf` | `providers[0]` 모델 식별자. 사용 중인 소형 모델명으로 변경 |
| `LLM_VERIFY_LOCAL_MODELS_ON_STARTUP` | — | `true` | (`app.llm.verify-local-models-on-startup`) — G3 토글. `true`면 `LOCAL_LLM_URL`/`LOCAL_LLM_URL_2`/`LOCAL_FAST_LLM_URL`이 설정된 각 provider에 대해 기동 시 `GET {URL}/models`를 호출해 접속 가능·모델명 일치를 확인하고, 실패하면 애플리케이션이 시작되지 않는다. 로컬 서버가 앱보다 늦게 뜨는 배포 순서 레이스가 있을 때만 `false`로 끌 것 — 그 경우 예전처럼 첫 채팅 요청이 실패한 뒤 다른 provider로 런타임 폴백된다 |
| `LLM_ROUTING_MODE` | — | `COST_FIRST` | 기본 라우팅 모드 (`app.llm.default-routing-mode`) — `COST_FIRST`/`QUALITY_FIRST`/`PROGRESSIVE`/`LOCAL_ONLY`.<br>**폐쇄망·로컬 전용은 `LOCAL_ONLY`** 로 외부 프로바이더 호출을 원천 차단. `LOCAL_ONLY`로 설정하면 채팅 화면 사이드바의 라우팅 전략 드롭다운 자체가 사라진다(어떤 모드를 골라도 결과가 같으므로) — 상세는 [LLM_ROUTING.md §8](LLM_ROUTING.md#8-제약-및-주의사항) 참고 |
| `OPENAI_API_KEY` | — | — | OpenAI providers 사용 시 필요. 미설정 또는 빈 값이면 해당 providers 자동 비활성화. providers 설정에서 `${OPENAI_API_KEY}` 형태로 참조 |
| `OPENAI_BASE_URL` | — | `https://api.openai.com` | OpenAI 호환 엔드포인트 기본 URL. providers 설정에서 `${OPENAI_BASE_URL}` 형태로 참조. Azure OpenAI 등 호환 엔드포인트로 교체 가능 |
| `GEMINI_API_KEY1` | — | — | Gemini 1번 API 키 — `providers[3]`(gemini-flash-lite, NORMAL), `providers[6]`(gemma-4-31b, PREMIUM) 공유. 미설정 시 해당 providers 자동 비활성화. providers 설정에서 `${GEMINI_API_KEY1}` 형태로 참조 |
| `GEMINI_API_KEY2` | — | — | Gemini 2번 API 키 — `providers[4]`(gemini-flash, NORMAL), `providers[7]`(gemma-4-31b, PREMIUM) 공유. 미설정 시 해당 providers 자동 비활성화. providers 설정에서 `${GEMINI_API_KEY2}` 형태로 참조. `providers[6]`·`[7]`은 이름·모델·priority(5)가 동일한 gemma-4-31b 2대로, 서로 다른 키를 씀으로써 PREMIUM 티어의 실질 처리량/쿼터를 두 배로 늘리는 로드밸런싱 쌍이다(§5.7 동일 우선순위 로드밸런싱) |
| `GEMINI_BASE_URL` | — | `https://generativelanguage.googleapis.com/v1beta/openai/` | Gemini API 엔드포인트 URL. 모든 Gemini providers가 `${GEMINI_BASE_URL}` 형태로 참조하므로 이 값 하나로 Gemini 전체 엔드포인트를 일괄 변경 가능 |
| `GEMINI_MODEL` | — | provider별 상이 | `providers[3]`(gemini-flash-lite)·`providers[4]`(gemini-flash)의 모델명 오버라이드 (`app.llm.providers[3]/[4].model`). 미설정 시 각자의 기본값(`gemini-3.1-flash-lite`/`gemini-2.5-flash`) 사용. **주의**: 두 provider가 같은 변수를 참조하므로, 설정하면 둘 다 같은 모델이 되어 NORMAL 티어의 2모델 폴백이 하나로 합쳐진다 — 서로 다른 모델을 유지하려면 이 변수 대신 `application.properties`에서 각 `providers[N].model` 줄을 직접 지정할 것 |
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
| `UPLOAD_MAX_TOTAL_SIZE` | — | `0` (무제한) | 배포 전체 저장 상한 (§6.15). `20GB`처럼 단위를 붙일 수 있고 단위 없는 숫자는 바이트. `DATA_DIR` 아래 `documents`+`converted`+`images` 합계로 검사하며, 초과 업로드는 413 `RAG-UP-002`. 재기동 필요 — 상세는 아래 "업로드 크기 제한" |
| `AUTH_ENABLED` | — | `true` | `false`로 설정하면 로그인 없이 실행 (no-auth 모드). 자세한 내용은 [§9.4](#94-인증-토글-no-auth-모드) 참조 |
| `DOMAIN` | — | `localhost` | Docker Compose의 `caddy` 컨테이너가 사용하는 도메인명. `localhost`이면 Caddy 로컬 CA 인증서 자동 생성. 운영 시 실제 도메인(예: `myrag.duckdns.org`)으로 변경 |
| `USE_CADDY_REVERSE_PROXY_HTTPS` | — | `true` | 세션 쿠키 `Secure` 플래그 제어 (`server.servlet.session.cookie.secure`). Caddy HTTPS 환경에서는 `true`(기본값). **HTTP 로컬 단독 실행 시 반드시 `false`로 변경** — `true` 상태에서 HTTP로 접근하면 쿠키가 전송되지 않아 로그인 불가 |

#### RAG 튜닝

| 변수 | 기본값 | 권장 범위 | 설명 |
|------|--------|----------|------|
| `CHUNK_SIZE` | `1500` | 300 ~ 2000 | 청크 크기 (문자 수). 작을수록 정밀, 클수록 문맥 풍부 |
| `CHUNK_OVERLAP` | `0` | 0 ~ CHUNK_SIZE × 0.25 | 청크 간 중복 (문자 수). 청크 경계 문맥 보완 전용. **기본값 0**은 §6.8 문서 내보내기의 유일한 휴리스틱 단계(overlap 제거)를 애초에 불필요하게 만들고, 섹션 인식 분할이 이미 소제목·부모 헤딩 컨텍스트를 청크에 붙여 주므로 문자 단위 중복의 실익이 크지 않다는 판단 |
| `MIN_CHUNK_SIZE` | `500` | 50 ~ CHUNK_SIZE × 0.25 | 청크/섹션 병합 최소 길이 기준. DOCX·TXT·MD는 이 값 미만인 챕터(헤딩) 섹션을 다음 섹션과 병합하고(단 상위=부모 헤딩 방향은 금지), 슬라이딩 분할 뒤에도 이보다 작은 청크는 직전 청크로 뒤로 병합한다(§청킹 상세는 PIPELINE.md §6.4) |
| `CHUNK_SPLIT_GRANULAR` | `false` | true/false | 청크 분할 전략 (hot). `false`=크기 기준 병합(기본, 위 `MIN_CHUNK_SIZE` 적용). `true`=소제목 최대 분할 — `MIN_CHUNK_SIZE`를 무시하고 헤딩마다 분할하되 "제목+2문장 이내" 도입부만 하위 챕터와 통합, 표·코드 블록은 경계를 옮겨 보존, PPTX/PDF는 1슬라이드=1청크. **이미 인덱싱된 문서는 ↺ 재인덱싱해야 전환됨** — [§6.10](#610-청크-분할-전략-크기-기준-병합--소제목-최대-분할) |
| `SEARCH_TOP_K` | `10` | 2 ~ 15 | 벡터 검색 반환 문서 수. 높을수록 재현율↑, 토큰↑. **검증 호출에 실리는 발췌량(`topK × CHUNK_SIZE`)도 이 값이 정한다** — 그 호출이 이 앱 최대의 단일 요청이므로, n_ctx 가 좁으면 여기부터 낮춘다([§8 n_ctx 산정](#8-문제-해결)) |
| `SEARCH_SIMILARITY_THRESHOLD` | `0.3` | 0.0 ~ 0.75 | 청크 유지 최소 코사인 유사도. `0.0`=전체 수용. 기본 `0.3`은 **관련 있는 청크는 걸리지 않으면서 `topK`가 그만큼 요구했다는 이유만으로 딸려 오는 꼬리를 잘라내는** 수준의 바닥값이다. `VectorStoreProvider` 내부에 걸리므로 **벡터 축에만** 적용된다(큐레이션 축도 벡터 검색이라 함께 걸러지지만, BM25 축은 걸러지지 않는다) — 즉 이 값을 올리면 키워드 축의 상대 비중이 자동으로 올라간다. `SEARCH_RRF_KEYWORD_WEIGHT`와 **같은 단계에 함께 올리지 말 것**. 더 올릴 때(0.5~0.75)는 골든셋 recall 확인 후 적용 |
| `SEARCH_MULTIQUERY_ENABLED` | `true` | true/false | 검색 전 질의 다중 확장(LLM) 여부. `false`면 임계 경로 첫 LLM 콜 제거 |
| `SEARCH_MULTIQUERY_MIN_LENGTH` | `15` | 0 ~ 20 | 이 길이(trim) 미만 질의는 확장 생략. `0`=항상 확장. 짧은 키워드 질의 TTFT↓(§10.8.1) |
| `SEARCH_HYBRID_ENABLED` | `true` | true/false | RRF에 BM25(FTS5) 키워드 축 추가(§10.7.2 — 이 플래그와 무관하게 `chunk_fts`는 항상 채워지므로 **활성화해도 기존 색인 문서 재인덱싱 불필요**, FTS5/하이브리드 검색 도입 이전에 색인된 아주 오래된 문서만 예외) |
| `SEARCH_RETRY_ESCALATE` | `true` | true/false | **재검색** 에스컬레이션 두 축을 한 플래그로 제어. ① 후보 풀 `candidateK = min(round(topK×(1+0.5×재검색횟수)), topK×3)` — 예전 ×2에서 낮췄다(재시도가 이제 자리를 비우므로). ② 최종 컷 `effectiveTopK = topK + 재검색횟수`, **단 검증 호출에 여유가 있을 때만** — 발췌가 잘리면 근거 판정이 `null`로 떨어져 재시도를 거듭할수록 판정을 잃는다. 세는 것은 재시도가 아니라 **재검색** 횟수다(근거 이탈 재시도는 검색을 건너뛴다). 재시도는 근거로 쓰이지 않은 하위 청크를 최대 1/3 **교체**하기도 한다 — PIPELINE.md §5.1 |
| `SEARCH_RERANK_ENABLED` | `false` | true/false | RRF 후 LLM 리랭킹 단계 (opt-in). **턴당 LLM 1콜 추가** → 정밀도↑/레이턴시 트레이드오프 |
| `SEARCH_CANDIDATE_MULTIPLIER` | `3` | 2 ~ 5 | 리랭킹 전 후보 풀 크기. `topK × N`개 가져와 리랭킹 후 topK로 축소 |
| `SEARCH_TAG_CANDIDATE_MULTIPLIER` | `2` | 1 ~ 5 | 태그가 선택된 검색의 후보 풀 확대 배수. `candidateK = max(candidateK, topK × N)` — sqlite-vec에서 태그 엄격 필터 후 결과가 부족할 때 보정(§4.6) |
| `SEARCH_RRF_KEYWORD_WEIGHT` | `0.5` | 0.5 ~ 3.0 | 가중 RRF(Phase 7-A) — BM25 키워드 축 가중치. 벡터 축(MultiQuery 1~3개)은 항상 `1/축개수`로 그룹 정규화되므로 `1.0`이면 정규화된 벡터 그룹과 동일 비중이며, 기본값은 그 절반이다(근거는 [§7.8](#78-키워드-축-가중치를-05로-두는-이유-한영-혼재-코퍼스)). `SEARCH_HYBRID_ENABLED=false`면 키워드 축이 없어 무영향 |
| `SEARCH_RRF_K` | `60` | 20 ~ 100 | 가중 RRF(Phase 7-A) — RRF 순위융합 상수 k(원논문 기본값 60) |
| `SEARCH_CURATED_QA_ENABLED` | `true` | true/false | §10.10 — 큐레이션 Q&A를 RRF 축에 포함할지 여부. `false`면 해당 검색을 아예 실행하지 않음(비용 절감) |
| `SEARCH_CURATED_QA_WEIGHT` | `1.0` | 0.5 ~ 5.0 | §10.10 — 큐레이션 Q&A 축 가중치 (§10.11 에서 좋아요 축과 제안 축이 이 값 하나로 합쳐졌다 — 모든 항목이 같은 심사를 거치므로 신뢰 근거가 갈리지 않는다). 키워드축과 동일하게 그룹 정규화 없이 그대로 적용됨(벡터축 그룹은 항상 `1/축개수`). **기본 `1.0` = 정규화된 벡터 축 그룹과 동등** — 예전 기본값 `1.2`에서 질문과 크게 관련 없는 큐레이션 항목이 상위로 올라오는 것이 관측됐다. 이 축은 후보가 적어 웬만하면 자기 축에서 상위 랭크를 받는데, 거기에 가산점까지 주면 "이 축에 무엇이든 있으면 끌어올린다"에 가까워진다 |
| `MAX_RETRY_COUNT` | `2` | 0 ~ 4 | 증거 부족 시 재검색 최대 횟수 |

대화 컨텍스트 주입 길이는 `LLM_MAX_TOKENS × 0.5`(최소 1,000자)로 자동 계산됩니다 — 기본값 기준 `10000 × 0.5 = 5000`자. 원문 그대로 보내는 폴백 경로(`MemoryService.getHistory()`)와 요약 캐시 경로(`ConversationSummarizerService.buildContext()`, §6.1) 모두 이 예산을 동일하게 지키도록 통일되어 있습니다.

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
| `INDEXING_MAX_FILES` | `1` | 1 ~ 4 (`/settings` 상한) | 파일 병렬 인덱싱 워커 수. **인덱싱 LLM 동시 호출 피크 ≈ `INDEXING_MAX_FILES` × `INDEXING_MAX_LLM`** 이므로, 기본값 `1`은 피크를 정확히 `INDEXING_MAX_LLM`으로 고정한다(§6.5 주석 참고). 올리면 처리량은 늘지만 피크가 곱으로 커진다 |
| `INDEXING_MAX_LLM` | `3` | 1 ~ 8 (`/settings` 상한) | 인덱싱 중 LLM 병렬 호출 수 — 키워드 추출뿐 아니라 MD 교정·TXT 구조화·지연 Vision 설명이 모두 사용. 로컬 LLM 서버의 `--parallel` 값에 맞춘다 |
| `INDEXING_KEYWORD_TIMEOUT_SECONDS` | `600` | 30 ~ 1800 | 청크 키워드 추출 1회당(§10.8.2 배치 시 배치 1회당) 최대 대기 시간. 초과 시 TF fallback |
| `INDEXING_KEYWORD_BATCH_SIZE` | `2` | 1 ~ 8 | §10.8.2 — 청크 N개를 한 LLM 호출로 묶어 요청(왕복 ≈ ceil(청크수/N)). `1`=배치 없음(청크당 1콜, 이전 동작). 배치가 클수록 응답 길이도 늘어나므로 로컬 모델에서 타임아웃이 잦으면 `INDEXING_KEYWORD_TIMEOUT_SECONDS`를 함께 올리세요 |

#### 질의 경로 동시성 제어

인덱싱과 별개로, **채팅/질의 경로**(분류·답변·재검색 등)가 프로바이더별로 동시에 보내는 요청 수를 제어합니다. 상세 동작·적용 범위는 [§5.7](#57-동시성-제어-및-백프레셔)을 참고하세요.

| 변수 | 기본값 | 권장 범위 | 설명 |
|------|--------|----------|------|
| `LLM_DEFAULT_PROVIDER_CONCURRENCY` | `3` | 1 ~ 16 | 프로바이더별 동시 처리 상한 기본값(`app.llm.default-provider-concurrency`) — LLM 서버의 실제 `--parallel` 값과 일치시키는 것이 원칙. 개별 프로바이더는 `application.properties`의 `app.llm.providers[N].concurrency`로 오버라이드 가능 |
| `LLM_PERMIT_WAIT_TIMEOUT_SECONDS` | `60` | 5 ~ 120 | 동시성 슬롯 대기 상한(초, `app.llm.permit-wait-timeout-seconds`). 초과 시 `LLM_READ_TIMEOUT_SECONDS`(기본 600초)까지 기다리지 않고 즉시 HTTP 429 + `Retry-After` 응답 |

#### HTTP Timeout 튜닝

| 변수 | 기본값 | 권장 범위 | 설명 |
|------|--------|----------|------|
| `SSE_IDLE_TIMEOUT_SECONDS` | `300` | 30 ~ 1800 | 에이전트 그래프의 진행 신호(노드 전환·토큰·소스 준비)가 이 시간만큼 전혀 없으면 중단 (`app.sse-idle-timeout-seconds`). 매 신호마다 리셋되므로 느리지만 계속 응답 중인 로컬 LLM은 끊기지 않음 — 실제로 "멈춘" 요청을 감지하는 주 타임아웃 |
| `SSE_TIMEOUT_SECONDS` | `7200` | 600 ~ 14400 | 브라우저 ↔ 서버 SSE 연결의 절대 상한(활동 여부 무관, `app.sse-timeout-seconds`) — 응답이 영원히 끝나지 않는 극단적 상황에 대한 안전장치일 뿐, 평소엔 `SSE_IDLE_TIMEOUT_SECONDS`가 먼저 작동함 |
| `LLM_CONNECT_TIMEOUT_SECONDS` | `10` | 2 ~ 30 | LLM API 연결 타임아웃 (`app.llm.connect-timeout-seconds`) |
| `LLM_READ_TIMEOUT_SECONDS` | `600` | 30 ~ 1800 | LLM API 응답 읽기 타임아웃 (`app.llm.read-timeout-seconds`) |
| `EMBED_CONNECT_TIMEOUT_SECONDS` | `10` | 2 ~ 30 | 임베딩 API 연결 타임아웃 (`app.embedding.connect-timeout-seconds`) |
| `EMBED_READ_TIMEOUT_SECONDS` | `180` | 30 ~ 600 | 임베딩 API 응답 읽기 타임아웃 (`app.embedding.read-timeout-seconds`) |
| `CHROMA_CONNECT_TIMEOUT_SECONDS` | `5` | 1 ~ 15 | Chroma API 연결 타임아웃 (`app.chroma.connect-timeout-seconds`) |
| `CHROMA_READ_TIMEOUT_SECONDS` | `120` | 10 ~ 300 | Chroma API 응답 읽기 타임아웃 (`app.chroma.read-timeout-seconds`) |

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
| `PPTX_DROP_REDUNDANT_TITLE_SLIDES` | `true` | PPTX → MD 변환 시 본문·이미지 없이 **제목 한 줄만** 있는 슬라이드 중, 그 제목이 **바로 다음 슬라이드**의 제목+본문에 그대로 포함되는 "예고 제목" 슬라이드를 제거(`PptxToMarkdownConverter`). `true`(기본) — 제목의 형태(번호/키워드/명사구 등)는 보지 않고 다음 슬라이드와의 실제 내용 일치만 판정 — 문장형 제목도 다음 슬라이드에 반복되면 대상. 정규화된 제목이 1글자면 대상 제외. 덱의 마지막 슬라이드(다음이 없음)는 항상 유지. `false` — 그런 슬라이드도 유지. 변경 후 재인덱싱 필요 |
| `PPTX_DROP_ENDING_SLIDE` | `true` | PPTX → MD 변환 시 **덱의 마지막 슬라이드에만** 적용 — 이미지 없이 내용 전체(제목+본문)를 공백·구두점 제거 + 소문자화로 정규화한 결과가 `끝`/`end`/`the end`/`감사합니다`/`thank you` 중 하나를 포함하고, 그 표시를 뺀 나머지 글자가 10자 이하이면 제거(`PptxToMarkdownConverter`). `true`(기본) — 짧은 서명 정도는 함께 있어도 제거되지만, 연락처처럼 10자를 넘는 내용이 있으면 유지. 마지막이 아닌 슬라이드의 '끝'/'END'/'감사합니다'는 영향 없음. `false` — 마지막 슬라이드도 유지. 변경 후 재인덱싱 필요 |

> `PPTX_REMOVE_DUPLICATE_SLIDES`: 섹션마다 반복되는 동일 목차 슬라이드나 완전 동일한 백업 슬라이드가 검색 인덱스에 중복 청크로 남는 것을 막습니다. 목차형 판정(②)은 절대 개수(3개)와 비율(60%)을 모두 요구해 보수적이지만, 제목 여러 개를 나열하는 진짜 본문 슬라이드가 드물게 오탐될 수 있으니 그런 경우 `false`로 끄고 재인덱싱하세요. `DEBUG` 로그에 제거된 슬라이드 번호와 사유(중복/목차형)가 남습니다.
> `PPTX_DROP_DIVIDER_SLIDES`: "PART 2"·"3장 개요"·"결제 시스템"처럼 내용 없는 섹션 표지 슬라이드가 검색에 아무 답도 주지 못한 채 인덱스 슬롯만 차지하는 것을 막습니다. 문장형 제목만 있는 "키 메시지" 슬라이드(예: "고객 만족을 최우선으로 합니다")는 실제 내용으로 보고 유지하지만, 판정은 휴리스틱이라 드물게 어긋날 수 있으니 구분 표지가 검색에 필요하거나 키 메시지가 사라지면 `false`로 끄고 재인덱싱하세요. 제거된 슬라이드는 `DEBUG` 로그에 사유(구분용 제목)와 함께 남습니다.
> `PPTX_DROP_REDUNDANT_TITLE_SLIDES`: 저자가 챕터를 "예고 제목 슬라이드 + 같은 제목으로 본문을 채운 슬라이드" 2장으로 나눠 만드는 경우, 앞의 빈 예고 슬라이드가 실질적 내용 없이 인덱스 슬롯만 차지하는 것을 막습니다. `PPTX_DROP_DIVIDER_SLIDES`와 달리 제목의 생김새가 아니라 **다음 슬라이드와의 실제 텍스트 일치**를 보므로, 겹치는 짧은 제목이 우연히 다음 슬라이드에도 등장하면 드물게 오탐될 수 있습니다 — 그런 경우 `false`로 끄고 재인덱싱하세요. 제거된 슬라이드는 `DEBUG` 로그에 사유(예고 제목 슬라이드)와 함께 남습니다.
> `PPTX_DROP_ENDING_SLIDE`: "끝"/"END"/"The End"/"감사합니다"/"Thank you"만 있거나 짧은 서명 정도만 덧붙은 마무리 슬라이드가 검색에 아무 내용도 주지 못한 채 인덱스 슬롯만 차지하는 것을 막습니다. 마지막 슬라이드에만 적용되므로 중간에 등장하는 정상적인 "끝"·"감사합니다" 소제목에는 영향이 없습니다. 이메일·전화번호 같은 연락처가 함께 있는 마무리 슬라이드는(나머지 글자가 10자 초과) 자동으로 유지됩니다. 제거된 슬라이드는 `DEBUG` 로그에 사유(종료 표시)와 함께 남습니다.
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
| `LLM_MAX_TOKENS` | `10000` | 1000 ~ 32000 | 단일 진실 소스(`app.llm.max-tokens`)로 통일되어, 이 값을 바꾸면 **아래 세 곳 모두**가 함께 움직입니다: (1) **블로킹 LLM 응답 토큰 상한** — 인덱싱·분류·키워드·Direct 블로킹 호출에 적용(스트리밍 채팅 답변은 의도적으로 미적용, SSE 타임아웃이 폭주 방지). (2) **대화 컨텍스트 문자 예산**(`MemoryService`, `×0.5`로 히스토리 예산 산출). (3) **MD 교정 섹션 크기**(`MarkdownCorrectionService`) — §6.26 이후로는 **상한**입니다. 프로바이더 창을 알면 거기서 나온 값과 비교해 작은 쪽을 씁니다.<br>§6.18 이전에는 (1)이 코드에 `6000`으로 하드코딩돼 이 환경변수와 무관하게 동작했고, (2)·(3)은 별도의 죽은 프로퍼티(`spring.ai.openai.chat.options.max-tokens`, 기본 `8000`)를 읽어 (1)과 다른 값을 썼습니다 — 이제 세 곳 모두 `app.llm.max-tokens` 하나만 읽습니다.<br>**⚠️ 기본값 `10000`은 32k 이상 컨텍스트를 전제합니다** — 이 값은 출력 상한인 동시에 (2)를 통해 **입력**(히스토리 5,000자)도 키우므로 `n_ctx` 양쪽을 함께 압박합니다. 8k~10k 모델이면 `2000` 근처로 낮추세요(§8 「검증 배지가 아예 사라짐」의 산정표).<br>**두 갈래는 이 값을 통째로 쓰지 않습니다** — `max_tokens`는 상한이 아니라 **예약**이라(서버가 `프롬프트 + max_tokens ≤ n_ctx`를 검사) 쓰지도 않을 큰 값은 그만큼 입력 자리를 없앱니다. ① **검증 호출**은 자체 상한 **2,048토큰**(`AnswerService`) — 응답이 JSON 몇 필드인데 전체가 예약되면 좁은 컨텍스트에서 `n_ctx`를 넘기는 것은 근거가 아니라 그 예약입니다. ② **인덱싱 호출**은 작업 크기에서 파생(`IndexingOutputCap`) — 재작성(MD 교정·txt→md)은 입력 추정 × 1.5, 고정 출력(키워드+맥락·이미지 설명)은 이 값의 비율. 예전에는 인덱싱이 전체를 예약해, 창 20,480 · 이 값 10000 배포에서 MD 교정이 컨텍스트 초과로 실패했습니다(같은 프로퍼티가 (3) 섹션 크기까지 정하므로 입력과 예약이 함께 커집니다). 둘 다 이 값에서 파생되고 이 값으로 잘리므로 **내리면 전부 함께 내려갑니다**.<br>**응답 모드(S/N)와의 관계**: (1)의 실제 상한은 이 값 그대로가 아니라 `ResponseMode`별 비율(S 15% / N·C 70%)과 글자수 하한(S 2,000 / N·C 5,000자) 중 **큰 값**이며, 다시 이 값 자체로 잘립니다(`min`). 전환점이 모드마다 달라 — S는 13,334, N·C는 7,143 — **기본값 10,000에서는 S가 하한(2,000), N·C가 비율(7,000)** 입니다. **어느 항이 적용 중인지는 `/settings`의 "응답 예산" 행에서 바로 확인할 수 있습니다**(`2,000 (최소 보장)` / `8,400 (상한의 70%)` / `3,000 (설정 상한)`). ⚠️ 이 값은 **폭주를 막는 안전판이지 목표 분량이 아닙니다** — 실제 분량은 모드별 시스템 프롬프트가 정하고(숫자를 말하는 건 S뿐 — RAG 답변 `prompt.answer.system.s`는 "1,000자 이내", 검색을 건너뛰는 Direct 답변 `prompt.direct.system.s`는 "1,500자 이내". Direct 쪽이 느슨한 이유는 인용할 문서 발췌가 없어 답변이 스스로 풀어 써야 하기 때문이며, 한/영 번들이 같은 숫자를 말하는지는 `ResponseModeSystemPromptTest`가 고정한다. N은 숫자 없음), 게다가 스트리밍 채팅 답변에는 적용되지 않습니다. 상세는 [PIPELINE §3.1](PIPELINE.md) |
| `LLM_TEMPERATURE` | `0.0` | 0.0 ~ 0.3 | **일반/RAG 대화형 호출**(분류·답변·근거평가·재순위)의 무작위성 제어(`app.llm.temperature`). `0.0`은 결정적 답변, 높을수록 다양·창의적. `[0.0, 0.3]`으로 clamp.<br>**핫 수정 가능** — `/settings`에서 재기동 없이 다음 호출부터 반영(`ClassifierService`/`AnswerService`/`RerankerService`가 매 호출 재조회). 프로바이더 빈 생성 시점에도 고정되는데, 이는 모델 주위에 자체 `ChatClient`를 구성해 호출별 오버라이드를 받을 수 없는 프레임워크 내부 호출(예: 멀티쿼리 확장)을 위한 기동 시점 폴백 값으로만 쓰입니다 — 그런 호출은 재기동해야 변경이 반영됩니다.<br>인덱싱/백그라운드 호출(키워드 추출·MD 교정 등)에는 적용되지 않습니다 — 아래 `LLM_INDEXING_TEMPERATURE`로 분리되어 있습니다 |
| `DIRECT_LLM_TEMPERATURE` | `0.1` | 0.0 ~ 1.0 | **Direct(meta) 응답 전용** temperature(`app.llm.direct-temperature`) — 인사·잡담 등 RAG를 안 쓰는 직접 응답은 약간의 다양성이 자연스러워 일반 temperature와 분리(§6.18). `[0.0, 1.0]`으로 clamp. **핫 수정 가능** — `/settings`에서 재기동 없이 다음 Direct 호출부터 반영(`DirectAnswerService`가 매 호출 재조회) |
| `LLM_INDEXING_TEMPERATURE` | `0.0` | 0.0 ~ 0.1 | **인덱싱/백그라운드 전용** temperature(`app.llm.indexing-temperature`) — 키워드 추출, MD 교정, txt→md 구조화, 이미지 설명/분류, 스레드 제목 생성, 대화 요약 등 모든 ungated 백그라운드 호출에 적용. 대화형이 아닌 추출/분류 작업이라 `LLM_TEMPERATURE`/`DIRECT_LLM_TEMPERATURE` 값과 무관하게 결정적으로 유지하려고 분리했습니다. `[0.0, 0.1]`으로 clamp — 출력이 파싱되는 추출 작업이라 표본추출의 다양성이 이득이 아니라 결함이고, 0에서 반복에 빠지는 로컬 모델을 깨울 만큼만 열어 둡니다. 환경변수로 더 큰 값을 줘도 이 상한에서 잘립니다. **핫 수정 가능** — `/settings`에서 재기동 없이 다음 호출부터 반영(각 서비스가 매 호출 재조회) |
| `CREATIVE_LLM_TEMPERATURE` | `0.7` | 0.0 ~ 1.0 | **응답 모드 C(응용) 전용** temperature(`app.llm.creative-temperature`) — 검색된 문서를 **재료로** 새 코드·설정을 만들어내는 모드라 표본추출의 다양성이 필요합니다. 일반 temperature와 분리한 이유는 그쪽 clamp 상한이 **0.3**이라 창의 생성이 원천 봉쇄되기 때문입니다(§6.24). `[0.0, 1.0]`으로 clamp. **핫 수정 가능** — `/settings`의 "LLM 튜닝"에서 재기동 없이 다음 호출부터 반영되며, 블로킹·스트리밍 **양쪽**에 적용됩니다. C 모드는 채팅 입력창의 **S/N/C** 토글에서 고를 수 있고, **Direct(잡담) 토글과 함께 쓸 수 없습니다** — Direct 를 켜면 C 버튼이 비활성화되고 선택 중이었다면 N 으로 되돌아갑니다(서버도 같은 규칙을 독립적으로 적용하므로 손으로 만든 요청도 N 으로 강등됩니다). 이 모드를 배포 전체에서 닫으려면 아래 `CREATIVE_MODE_ENABLED`를 쓰세요 |
| `CREATIVE_MODE_ENABLED` | `true` | true/false | **응답 모드 C(응용)를 아예 열지 말지**(`app.llm.creative-mode-enabled`). 위 `CREATIVE_LLM_TEMPERATURE`가 "C가 **어떻게** 답할까"라면 이 값은 "C를 **열어 둘까**"입니다 — C는 검색된 문서 밖의 내용(예제 코드·설정)을 생성하는 유일한 모드라, 배포처에 따라 처음부터 제공하지 않는 편이 맞을 수 있습니다.<br>`false`면 ① 채팅 입력창의 **C 버튼이 렌더되지 않고**(비활성 회색 버튼이 아니라 아예 사라집니다), ② 그럼에도 도착한 요청(REST `POST /api/v1/chat`, 손으로 만든 폼 POST)은 서버가 **N으로 강등**해 저장까지 N으로 남기며, ③ **이미 C로 답한 과거 턴의 `[C]` 표기와 검증 배지는 그대로 유지**됩니다(끄는 것은 "앞으로 고를 수 있는가"이지 "예전에 그렇게 답했는가"가 아닙니다).<br>**핫 수정 가능** — `/settings`의 "LLM 튜닝"에서 재기동 없이 다음 질문부터 반영됩니다 (열려 있던 채팅 화면은 새로고침해야 버튼이 사라집니다). 기본값은 `true`로, 이 스위치가 생기기 전과 동작이 같습니다 |
| `LLM_SHRINK_STEP` | `1` | 1 ~ 10 | **컨텍스트 초과로 프롬프트가 거절되면 재시도마다 문서를 이만큼씩 덜어냅니다**(`app.llm.shrink-step`, §6.26-9). 사전 예산이 이미 창에 맞춰 놓은 뒤라 여기까지 오는 초과는 대개 아슬아슬해서, 기본값이 **1**입니다 — 한 개만 덜어내면 들어갈 자리에서 반을 자르면 근거의 절반이 사라집니다. 재시도는 5회까지이므로 **이 값 × 5 가 도달 가능한 최대 축소폭**이고, 거기서도 안 되면 `RAG-LLM-003`. 로그 `[SHRINK]` 가 한 턴에 여러 번 보이면 2~3 으로 올려 왕복을 줄일 수 있지만, 근본 해결은 `SEARCH_TOP_K` 를 낮추거나 서버 `--ctx-size` 를 키우는 쪽입니다. 핫 수정 가능 |

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

| 속성 | 환경변수 | 기본값 | 설명 |
|------|----------|--------|------|
| `app.image-description.enabled` | `IMAGE_DESCRIPTION_ENABLED` | `true` | 검색 시점 Lazy Vision(`LazyVisionService`) 활성화 여부. `false`이면 이미지 마커만 저장하고 검색 시 LLM 호출 없음. `@ConditionalOnProperty` 빈 게이트라 **재시작 필요** |
| `app.image-description.classify-type` | `IMAGE_CLASSIFY_TYPE` | `true` | 이미지 설명 전 유형(사진/도표/스크린샷 등) 분류 여부. 분류 결과를 프롬프트에 주입 |
| `app.image-description.ocr-enabled` | `IMAGE_OCR_ENABLED` | `true` | 스캔 PDF 페이지에 대해 OCR 처리 활성화 여부. `OcrService`의 빈 게이트라 **재시작 필요** |
| `app.image-description.tessdata-path` | `IMAGE_OCR_TESSDATA_PATH` | (빈 값) | Tesseract `tessdata` 디렉터리 절대경로. 비우면 `TESSDATA_PREFIX` 환경변수 또는 시스템 기본 경로를 따름 |
| `app.image-description.docx-emf-convert` | `DOCX_EMF_CONVERT` | `true` | DOCX 내 EMF 벡터 이미지를 PNG로 변환 (Apache Batik — 추가 설치 불필요) |
| `app.image-description.docx-wmf-convert` | `DOCX_WMF_CONVERT` | `false` | DOCX 내 WMF 벡터 이미지를 PNG로 변환 (LibreOffice headless 필요 — EMF보다 변환 품질이 낮아 기본 비활성) |

> **`mode` / `min-image-bytes`는 현재 코드에서 읽지 않습니다.** `ImageDescriptionProperties`에 바인딩만 되어 있고
> 소비처가 없어(strip/describe 판단은 업로드 시 "이미지 설명 추가" 체크박스와 `LazyVisionService`의 질의 시점
> 캐시로 옮겨감) 값을 바꿔도 동작이 달라지지 않습니다. 바인딩 호환을 위해 남겨둔 것이라 환경변수도 두지 않았습니다.

> **이미지 설명 전제 조건**: `enabled=true` + Vision 모델 프로바이더 등록 (`type=VISION`·`type=LIGHT_BOTH`·`type=BOTH` — `supports(VISION)`이 참인 세 가지).
> 프로바이더가 없으면 설명 없이 마커만 남습니다.
>
> 화면의 "이미지 설명 추가" 체크박스 활성 여부는 `DocumentController`가 `LlmRouter.hasEnabledProviderFor(VISION)`으로
> 판단합니다 — `supports()`에서 능력을 파생하므로 위 세 타입과 항상 일치합니다. 예전에는 타입 이름 목록
> (`BOTH`, `VISION`) 검사여서 `LIGHT_BOTH`만 등록한 배포는 실제로는 동작하는데도 체크박스가 비활성이었습니다.

> **EMF/WMF 변환**: LibreOffice(`soffice`)가 PATH에 있어야 합니다. 없으면 변환이 건너뛰어지며 `[TIMEOUT:LIBREOFFICE]` 로그가 출력됩니다.

> **인덱싱 시점 즉시 설명 생성**은 프로퍼티가 아니라 문서 업로드 화면의 "이미지 설명 추가" 체크박스로 제어됩니다
> (DOCX·TXT·MD·PPTX·PDF(스캔 아님) 전부 — PPTX/PDF도 `[이미지: ...]` 인라인 마커 방식이 DOCX와 동일해
> 이 체크박스가 그대로 적용됩니다). 여기 표의 설정들은 모두 검색 시점 Lazy Vision에 대한 것입니다. 자세한
> 내용은 `documents/IMAGE_PROCESS.md` 5절·12절 참고.
>
> **인덱싱 화면 진행 표시**: 이 체크박스를 켜면 이미지 분석(Vision LLM)이 섹션 포맷 교정보다 먼저 실행되는데,
> 이미지 수가 많으면 시간이 걸릴 수 있어 `/documents` 업로드 화면에 "이미지 분석 중 (N/M)" 진행률이 별도
> 단계로 표시됩니다(SSE `describing_images` 스테이지) — 직전 단계 메시지(예: "PPTX → Markdown 변환 중...")에
> 멈춰 있는 것처럼 보이지 않도록 함. 상세는 [PIPELINE.md §6.3](PIPELINE.md#63-docx--md--임베딩-db-저장-상세-이미지-포함), UI는 [UI.md §3.2](UI.md#32-문서-관리-documentcontroller) 참고.
>
> **채팅 화면의 쿼리 시점 Lazy Vision — 진행 표시 + 건너뛰기**: 인덱싱 시점과 별개로, 검색된 청크가 아직
> 설명 없는 이미지를 참조하면 답변 생성 전에 `LazyVisionService`가 그 이미지를 분석합니다(`RetrievalService`).
> 이 대기 동안 채팅 화면 상단 배지에 "이미지 분석 중 (N/M)"이 표시되고(`GraphListener.onImageAnalysisProgress()`
> → `stage` SSE 이벤트를 `id="image_analysis"`로 재사용), 사용자가 **건너뛰기**를 클릭하면
> `POST /ui/chat/stream/skip-images`(`threadId` 파라미터)가 `ChatImageAnalysisSkipRegistry`에 신호를 보냅니다.
> `LazyVisionService`는 이미 시작된 Vision 호출을 취소하지 않고 **대기만 멈춥니다** — 나머지는 백그라운드에서
> 계속 진행돼 `image_descriptions` 캐시에 정상 저장되므로, 다음 검색에서 같은 이미지를 다시 만나면 즉시
> 캐시 히트로 처리됩니다. 인덱싱 진행 표시(위 항목)와는 SSE 채널·스테이지 id가 다른 완전히 별개의 경로입니다.
>
> **이미 인덱싱 시점에 설명이 박힌 이미지는 재분석하지 않습니다**: 업로드 시 "이미지 설명 추가"를 체크해
> 청크 텍스트에 `[이미지: ...]` 바로 뒤에 `[이미지 설명: ...]`이 이미 삽입돼 있으면, `RetrievalService`가
> 그 이미지를 Lazy Vision 대상에서 아예 제외합니다(`RetrievalService.hasEmbeddedDescription()`). 이 설명은
> `MarkdownCorrectionService`가 만드는 순간부터 청크 텍스트에만 존재하고 `image_descriptions` 테이블에는
> 저장되지 않으므로, 이 필터가 없으면 매 턴 이 이미지를 캐시 미스로 오인해 불필요하게 재분석했을 것입니다.

#### 소제목 숫자 생성 (`addHeadingNumbers`)

프로퍼티가 아니라 문서 업로드 화면의 "소제목 숫자 생성" 체크박스로 제어되는 요청 단위 옵션입니다. 켜면 LLM
섹션 교정이 모두 끝난 뒤 H2~H6 헤딩에 계층적 번호(`## 1.1 제목`처럼)를 매깁니다
(`MarkdownCorrectionService.addHierarchicalHeadingNumbers()`).

> **코드 블록 언어 태그는 이 옵션과 무관합니다**: 라벨 없는 코드 블록의 언어 추론은 체크박스를 꺼도, PPTX
> 에서도 항상 실행됩니다. 예전에는 이 옵션 안쪽에 묶여 있어 체크박스를 끄면 언어 태그가 붙지 않았으나,
> 코드의 언어는 소제목 번호와 무관하므로 분리했습니다.

> **PPTX는 항상 무시됩니다**: 체크박스 상태와 무관하게 PPTX 업로드는 이 옵션이 절대 적용되지 않습니다.
> PPTX의 `##`/`###` 헤딩은 슬라이드 제목·부제목 라벨(최대 2단계, 슬라이드마다 계산)일 뿐 문서 목차 같은
> 계층 구조가 아니라서, 순번을 매기면 실제 구조와 무관한 숫자만 붙고 이미 있는 `[페이지: N]` 마커와도
> 겹쳐 혼란을 줍니다.
>
> **업로드 화면의 자동 기본값**: 위 동작을 UI에도 그대로 반영해, `/documents` 업로드 화면은 선택된 파일에
> PPTX가 하나라도 있으면 체크박스를 자동으로 해제합니다(순수 클라이언트 로직, 서버 검증과 무관). PDF는
> 자동 해제 대상이 **아닙니다** — `PdfToMarkdownConverter`가 H2~H6 헤딩 자체를 만들지 않아 체크해도
> 사실상 무해하기 때문입니다. PPTX와 다른 형식이 같은 배치에 섞이면 업로드가 배치 전체에 값 하나만
> 전달하는 구조라 화면에 경고 토스트가 뜹니다(업로드 자체는 막지 않음). 이 기본값은 어디까지나 제안이라
> 사용자가 업로드 직전 자유롭게 재설정할 수 있습니다 — 클라이언트 구현 상세는
> [UI.md §3.2](UI.md#32-문서-관리-documentcontroller) 참고.

> **MD 재인덱싱 시 자동 재검증**: `/admin` ↺ 버튼으로 재인덱싱하면, 저장된 MD에 번호 매겨진 헤딩이 하나
> 라도 있을 때만 현재 헤딩 구조 기준으로 전체 번호를 다시 계산해 파일에도 반영합니다
> (`MarkdownCorrectionService.reapplyHeadingNumbers()`, LLM 호출 없음) — 코드 블록 편집 등으로 헤딩이
> 추가·삭제·이동돼 번호가 어긋난 경우를 바로잡습니다. 번호가 원래 없던 문서(체크 해제 상태로 업로드됐거나
> PPTX)는 재인덱싱해도 새로 번호가 붙지 않습니다. 자세한 내용은 [§7.3 주의사항](#73-주의사항)과
> [PIPELINE.md §6.3](PIPELINE.md#63-docx--md--임베딩-db-저장-상세-이미지-포함) 참고.

#### LLM 응답 파라미터

> **temperature와 최대 출력 토큰**은 각각 `LLM_TEMPERATURE`, `LLM_MAX_TOKENS` 환경변수로 설정할 수 있습니다(§6.18로 실제 적용되도록 수정됨). **`LLM_MAX_TOKENS`도 §6.26 A6 이후 핫 수정 대상입니다**(범위 1,000~32,000) — 단순한 출력 상한이 아니라 대화 이력 예산(×0.5)·MD 교정 섹션 크기·인덱싱 출력 예약·컨텍스트 입력 예산이 전부 여기서 파생되므로, 컨텍스트 압박을 조정할 때 가장 크게 듣는 손잡이입니다. LLM temperature는 네 가지 모두 `/settings`에서 핫 수정 가능합니다 — 일반/RAG temperature(`LLM_TEMPERATURE`, 기본 0.0, 범위 **0.0~0.3**), Direct(잡담) 전용 `DIRECT_LLM_TEMPERATURE`(기본 0.1, 범위 **0.0~1.0**), 인덱싱/백그라운드 전용 `LLM_INDEXING_TEMPERATURE`(기본 0.0, 범위 **0.0~0.1**), 응답 모드 C(응용) 전용 `CREATIVE_LLM_TEMPERATURE`(기본 0.7, 범위 **0.0~1.0**). → [§3.2 LLM 응답 파라미터](#32-환경변수-전체-목록) 참조

#### 업로드 크기 제한

| 속성 | 환경변수 | 기본값 | 설명 |
|------|----------|--------|------|
| `spring.servlet.multipart.max-file-size` | — | `200MB` | 단일 파일 최대 크기. 초과 시 413 (`RAG-UP-003`) |
| `spring.servlet.multipart.max-request-size` | — | `200MB` | 멀티파트 요청 전체 최대 크기 |
| `app.upload.max-total-size` | `UPLOAD_MAX_TOTAL_SIZE` | `0` (무제한) | **배포 전체 저장 상한** (§6.15). 초과 시 413 (`RAG-UP-002`) |
| `app.upload.backup-retention-days` | `BACKUP_RETENTION_DAYS` | `30` | `documents/backup/` 보관 일수. `0` = 기간 규칙 해제 |
| `app.upload.backup-max-size` | `BACKUP_MAX_SIZE` | `2GB` | `documents/backup/` 총 용량 상한. 초과 시 오래된 것부터 삭제. `0` = 용량 규칙 해제 |

> **두 상한은 다른 축입니다.** 위의 per-file 상한은 "이 파일 하나가 너무 크다", `max-total-size`는 "더 넣을
> 자리가 없다"입니다. 후자는 `20GB`처럼 단위를 붙여 쓸 수 있고, 단위가 없으면 바이트로 읽습니다. `0`(기본)은
> 무제한이며, 이때는 사용량을 재려고 디스크를 걷는 일조차 하지 않습니다.
>
> **세는 대상**: `{app.data-dir}` 아래 `documents/` + `converted/` + `images/` 세 트리입니다.
> 업로드 원본만이 아니라 인덱싱이 만들어 내는 변환 마크다운과 추출 이미지까지 포함하며, PPTX·스캔 PDF는 보통
> 그쪽이 원본보다 큽니다. `memory.db`/`vector.db`·감사 로그·Chroma 볼륨은 업로드가 만드는 것이 아니라 제외됩니다.
>
> **`documents/backup/` 은 제외됩니다.** 문서를 삭제하면 원본이 지워지는 게 아니라 그 폴더로 **옮겨지므로**,
> 그것까지 세면 "문서를 지워 자리를 만드세요"라는 이 상한의 유일한 해결책이 성립하지 않습니다(사용량이 거의
> 안 줄고, 그 바이트는 어느 화면에서도 회수할 수 없습니다). 대신 아래 **백업 보존 정책**이 그 폴더를 묶습니다.
>
> **소프트 상한입니다.** 업로드를 받을 시점에 알 수 있는 크기는 올라오는 파일뿐이므로(파생 산출물은 아직 없음)
> 한 건이 상한을 조금 넘겨 끝날 수 있고, 대신 **그다음 업로드가 거부**됩니다. 문서를 지우면 즉시 자리가 납니다
> — 별도 카운터 없이 실제 디스크를 재기 때문에 `/admin` 삭제·디렉터리 동기화·수동 삭제가 모두 그대로 반영됩니다.

##### 백업 보존 정책 (`documents/backup/`)

문서 삭제 시 원본은 `{app.data-dir}/documents/backup/{원본이름}_{yyyyMMdd_HHmmss}{확장자}` 로 보관됩니다
(실수로 지운 문서를 되살리기 위한 것). 이 폴더는 저장 상한에서 빠지는 대신 **세 규칙으로 정리**됩니다 —
문서 **삭제 직후**와 **앱 기동 시**에 적용됩니다.

| 순서 | 규칙 | 설정 |
|---|---|---|
| ① | 같은 원본 파일명의 백업은 **최신 1개만** 남긴다 | 항상 적용 (끌 수 없음) |
| ② | ①이 남긴 것 중 **보관 일수**를 넘긴 것 삭제 | `BACKUP_RETENTION_DAYS` (기본 30, `0`=해제) |
| ③ | 그래도 폴더가 **용량 상한**을 넘으면 오래된 것부터 삭제 | `BACKUP_MAX_SIZE` (기본 `2GB`, `0`=해제) |

> **이 앱이 만든 파일만 지웁니다.** 삭제 대상은 위 이름 규칙에 맞는 파일뿐이며, 운영자가 그 폴더에 직접 둔
> 파일은 ③의 용량 계산에는 포함되지만 절대 지우지 않습니다. 그래서 외부 파일 때문에 상한까지 못 내려가는
> 경우가 생기면 삭제 대신 로그(`[BACKUP] 상한(...)까지 줄이지 못했다`)로 알립니다.
>
> **정리 시점 주의**: 삭제 직후와 기동 시에만 돕니다. 문서를 한동안 지우지 않는 배포에서는 ②의 30일이 최대
> 그만큼 늦게 반영될 수 있습니다(다음 삭제 때 따라잡습니다). 즉시 정리하려면 앱을 재기동하세요.
>
> **적용 범위**: 문서 업로드 2경로(`POST /ui/documents/upload`, `POST /api/v1/documents`)와 지식 제안 본문
> 이미지 업로드(`POST /curated/submissions/images`). `POST /api/v1/documents/sync`는 이미 디스크에 있는 파일을
> 인덱싱하는 것이라 검사하지 않습니다. 현재 사용량과 상한은 `/settings`의 **저장 용량** 카드에서 볼 수 있으며,
> 상한 변경은 재기동이 필요합니다(핫 수정 불가).

#### Rate Limiting (`app.rate-limit.*`)

애플리케이션 레벨 Rate Limiter (Bucket4j + Caffeine). 사용자 인증 시 userId, 미인증 시 IP 기준으로 버킷 분리.

| 속성 | 환경변수 | 기본값 | 설명 |
|------|----------|--------|------|
| `app.rate-limit.enabled` | `RATE_LIMIT_ENABLED` | `true` | `false`로 설정하면 전체 비활성화 |
| `app.rate-limit.chat-per-minute` | `RATE_LIMIT_CHAT_PER_MINUTE` | `60` | `/chat` 경로 — 분당 요청 수 |
| `app.rate-limit.upload-per-minute` | `RATE_LIMIT_UPLOAD_PER_MINUTE` | `10` | 문서 **업로드 쓰기 요청**만 — `POST /ui/documents/upload`, `POST /api/v1/documents`. 문서 화면 조회·목록 갱신·내보내기·태그 편집은 `default` 버킷을 씁니다(예전에는 전부 이 버킷이라 다중 파일 업로드가 429로 죽었습니다) |
| `app.rate-limit.sync-per-minute` | `RATE_LIMIT_SYNC_PER_MINUTE` | `3` | `/documents/sync` 경로 — 분당 요청 수 |
| `app.rate-limit.image-per-minute` | `RATE_LIMIT_IMAGE_PER_MINUTE` | `300` | `/images/` 경로 — 분당 요청 수 |
| `app.rate-limit.default-per-minute` | `RATE_LIMIT_DEFAULT_PER_MINUTE` | `120` | 그 외 경로 기본값 |

초과 시 429 응답 + `Retry-After: {초}` 헤더 + `{"errorCode":"RAG-RATE-001","message":"..."}` body.

#### 감사 로그 (`app.audit.*`)

민감 작업을 `data/audit/audit.log`에 JSON Lines 형식으로 기록. 현재 기록되는 액션은 다음과 같습니다.

| 액션 | 언제 |
|---|---|
| `document.upload` / `.delete` / `.sync` / `.export` / `.tags_update` / `.display_name_update` | 문서 관리 |
| `thread.delete` / `thread.routing-mode` | 사용자가 자기 대화를 삭제·라우팅 모드 변경 (삭제 시 회수된 큐레이션 수 포함) |
| `turn.delete` / `turn.feedback` / `turn.image.exclude` / `turn.source.exclude` | 턴 단위 조작 |
| `curated.edit` / `curated.submission.create` / `.approve` / `.reject` | 큐레이션·지식 제안 |
| `settings.update` / `settings.reset` / `settings.provider.toggle` | `/settings` 변경 |
| `llm-usage.delete-orphan` | orphan 사용 기록 삭제 |
| **`admin.thread.delete`** | 관리자가 **다른 사용자의** 대화를 삭제 (소유자·턴 수·회수된 큐레이션 수, §7.9) |
| **`admin.thread.read`** | 관리자가 **다른 사용자의** 답변 전문을 열람 (소유자·턴 id, §7.9) — "누가 남의 대화를 읽었는가"를 확인하는 유일한 근거입니다 |

| 속성 | 환경변수 | 기본값 | 설명 |
|------|----------|--------|------|
| `app.audit.enabled` | `AUDIT_ENABLED` | `true` | `false`로 설정하면 감사 로그 미기록 |
| `app.audit.max-file-size` | `AUDIT_MAX_FILE_SIZE` | `10MB` | 롤링 전 최대 파일 크기 (Logback SizeAndTimeBasedRolling) |
| `app.audit.max-history-days` | `AUDIT_MAX_HISTORY_DAYS` | `7` | 압축 파일 보관 일수. 초과된 파일 자동 삭제 |
| `app.audit.total-size-cap` | `AUDIT_TOTAL_SIZE_CAP` | `100MB` | `data/audit/` 디렉터리 전체 상한. 초과 시 오래된 파일 삭제 |

> 크기·보관 3개 값은 `logback-spring.xml`이 `springProperty`로 읽어 `AUDIT_FILE` appender의 롤링 기준으로도
> 쓰이므로, 환경변수로 바꾸면 로거 쪽에도 그대로 반영됩니다.

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
  chromadb/chroma:1.0.21

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
  chromadb/chroma:1.0.21

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
  chromadb/chroma:1.0.21 &
set -a && source .env && set +a
mvn spring-boot:run
```

**Windows (CMD)**

```cmd
REM 1. Chroma 서버 (별도 CMD 창)
docker run -d --name chroma-server -p 8001:8000 -v "%cd%\data\chroma:/chroma/chroma" chromadb/chroma:1.0.21

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

TLS를 앞단에서 종료하는 모든 구성(Caddy·nginx 등)에서는 앱 쪽에 **`TRUST_FORWARDED_FOR=true`가 필요합니다.** 켜지 않으면 앱이 보는 클라이언트 IP가 전부 프록시 주소가 되어, per-IP 속도 제한과 방문자 식별(`AUTH_GUEST_IDENTITY`)이 모든 사용자를 한 명으로 취급합니다 — [§9.4.3](#943-접속자별-채팅-개인화-appauthguest-identity) 참조.

> **인터넷이 없는 환경**에서는 Let's Encrypt(ACME) 자동 발급만 불가능하며, Caddy 자체는 사내 CA 인증서나 내장 로컬 CA(`tls internal`)로 정상 동작합니다 — [§4.5-4](#45-폐쇄망air-gapped--노-도커-실행) 참조.

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
export USE_CADDY_REVERSE_PROXY_HTTPS=false   # ★ HTTP로 열 때 필수 — 아래 4) 참조
                                             #   (true면 다른 PC에서 세션 쿠키가 폐기됨)
# TRUST_FORWARDED_FOR는 프록시 없는 직노출이므로 기본값 false 유지 — 켜면 헤더 위조로
# per-IP 제한 우회·타 방문자 신원 가로채기가 가능해집니다.

java -jar target/rag-agent-*.jar
```

#### 4) TLS / 리버스 프록시

폐쇄망에서 **불가능한 것은 Let's Encrypt(ACME) 자동 발급뿐**입니다 — 도메인 소유를 인터넷으로 검증해야 하기 때문입니다. Caddy 자체는 단일 정적 바이너리라 Docker 없이 반입할 수 있고, 인증서만 다른 방법으로 조달하면 오프라인에서 정상 동작합니다.

| 방식 | 인터넷 | 클라이언트 경고 | 비고 |
|---|---|---|---|
| Let's Encrypt (기본 `Caddyfile`) | **필요** | 없음 | ❌ 폐쇄망 불가 — 기동 시 ACME 실패 |
| **사내 CA 발급 인증서** | 불필요 | 없음(이미 신뢰) | ✅ 사내 PKI가 있으면 최선 |
| Caddy 내장 CA (`tls internal`) | 불필요 | root CA 배포 후 없음 | ✅ 사내 PKI가 없을 때 |
| HTTP 직노출 | — | — | 신뢰망 한정. 아래 제약 참조 |

기본 [`Caddyfile`](../Caddyfile)은 `{$DOMAIN:localhost}` 한 줄이라 실도메인을 넣으면 ACME를 시도합니다. 폐쇄망에서는 `tls` 지시자를 명시해 이를 우회합니다.

```caddyfile
# 사내 CA 발급 인증서 (권장 — 클라이언트가 이미 루트를 신뢰하므로 배포할 것이 없음)
rag.내부도메인 {
    tls /etc/caddy/cert.pem /etc/caddy/key.pem
    reverse_proxy 127.0.0.1:8080
}

# 사내 PKI가 없을 때 — Caddy 내장 로컬 CA (완전 오프라인)
rag.내부도메인 {
    tls internal
    reverse_proxy 127.0.0.1:8080
}
```

`tls internal` 사용 시 Caddy가 생성한 루트(`/data/caddy/pki/authorities/local/root.crt`, 노-도커는 `$XDG_DATA_HOME/caddy/pki/...`)를 **각 클라이언트 PC 신뢰 저장소에 1회 설치**해야 경고가 사라집니다.

**앱 쪽에 함께 필요한 설정** (TLS를 앞단에서 종료하는 모든 구성 공통 — nginx 등 조직 표준 프록시도 동일):

```bash
export USE_CADDY_REVERSE_PROXY_HTTPS=true   # 세션 쿠키 Secure 활성화
export TRUST_FORWARDED_FOR=true             # ★ 프록시 뒤에서는 필수 — §9.4.3
```

`TRUST_FORWARDED_FOR`는 **TLS 옵션이 아니라 그 결과로 따라오는 설정**입니다. 프록시를 앞에 두면 앱이 보는 IP가 전부 프록시 주소가 되므로, 이 값을 켜지 않으면 per-IP 속도 제한과 방문자 식별(`AUTH_GUEST_IDENTITY`)이 **모든 사용자를 한 명으로 취급**합니다. 반대로 프록시 없이 직노출하는 구성에서는 반드시 `false`여야 합니다(헤더 위조로 우회·가로채기 가능). `server.forward-headers-strategy=framework`는 기본값이라 `X-Forwarded-*` 인식은 그대로 동작합니다.

> **주의 1 — IP 직접 접속.** `https://10.x.x.x` 형태로 쓰려면 인증서에 IP SAN이 필요합니다. **호스트명 + 내부 DNS(또는 각 PC의 `hosts` 파일)** 방식이 훨씬 간단합니다.
>
> **주의 2 — HSTS.** 기본 `Caddyfile`은 `Strict-Transport-Security max-age=31536000`을 내보냅니다. 한 번 HTTPS로 접속한 호스트명은 브라우저가 1년간 HTTP 접속을 거부하므로, 자체 서명으로 시험할 때는 임시 호스트명을 쓰고 되돌릴 때 브라우저별 HSTS 항목을 삭제하세요.

**HTTP 직노출을 선택하는 경우** — `USE_CADDY_REVERSE_PROXY_HTTPS=false`가 **반드시** 필요합니다. 세션 쿠키의 `Secure` 플래그가 남아 있으면 브라우저가 쿠키를 저장하지 않아 로그인이 되지 않고, no-auth 모드에서도 요청마다 세션이 새로 생겨 `threadId`가 계속 바뀝니다. 단, 이 문제는 **`http://localhost`에서는 드러나지 않습니다** — 브라우저가 localhost만 secure context로 취급하기 때문에, 서버 호스트에서 직접 열어보면 멀쩡하고 다른 PC에서만 깨집니다.

같은 이유로 평문 HTTP LAN 접속에서는 브라우저의 secure-context 전용 기능이 비활성화됩니다 — **PWA 설치·서비스워커가 동작하지 않습니다**(채팅·문서 관리 등 핵심 기능은 정상). PWA가 필요하면 위 TLS 구성 중 하나를 택하세요.

##### 전체 예시 — 폐쇄망 HTTPS + 접속자별 채팅 분리

여러 사람이 각자의 대화 목록을 갖도록 하려면 **TLS 설정만으로는 부족합니다.** 실제 분리 스위치는 `AUTH_GUEST_IDENTITY`이고, 기본값 `shared`는 전원이 하나의 게스트를 공유합니다. 아래는 두 요구사항을 함께 만족하는 완전한 설정입니다.

```bash
# ── 방문자별 채팅 분리 ─────────────────────────────────────────────
export AUTH_ENABLED=false            # 필수: 이 값이 false여야 게스트 식별이 동작
export AUTH_GUEST_IDENTITY=hybrid    # ★ 실제 분리 스위치 (기본 shared = 전원 공유)
#export AUTH_MANAGEMENT_ONLY=true    # 선택: 채팅은 열되 /admin·문서 관리만 로그인 요구

# ── TLS를 Caddy에서 종료하는 구성 ──────────────────────────────────
export USE_CADDY_REVERSE_PROXY_HTTPS=true   # 세션 쿠키 Secure 활성화
export TRUST_FORWARDED_FOR=true             # ★ 프록시 뒤에서는 필수 (아래 설명)

# ── 폐쇄망 공통 (위 3) 항목과 동일) ────────────────────────────────
export LLM_ROUTING_MODE=LOCAL_ONLY
export VECTORSTORE_TYPE=sqlite-vec
```

**두 옵션이 함께 필요한 이유**: `AUTH_GUEST_IDENTITY=hybrid`는 쿠키가 없는 첫 방문자를 **IP로 식별**합니다. 그런데 Caddy 뒤에서는 앱이 보는 IP가 전부 Caddy 주소이므로, `TRUST_FORWARDED_FOR=true`가 없으면 **모든 신규 방문자가 같은 id를 받아** 분리가 무너집니다. 반대로 프록시가 없는 직노출 구성이라면 이 값은 `false`여야 하며, 그때는 `getRemoteAddr()`가 이미 실제 클라이언트 IP이므로 분리가 정상 동작합니다.

**`ip`가 아니라 `hybrid`를 권하는 이유** — 사내망에서 순수 IP 식별은 두 지점에서 깨집니다.

| 상황 | `ip` | `hybrid` |
|---|---|---|
| DHCP 임대 갱신으로 IP 변경 | ❌ 이력 유실 | ✅ 쿠키가 이력 유지 |
| NAT 뒤 여러 PC가 같은 IP | ❌ 한 명으로 뭉침 | ✅ 쿠키로 분리 |
| 사용자가 쿠키 삭제 | — | ✅ 같은 IP면 원래 id로 복구 |

**적용 후 확인** — 기동 로그에 다음 줄이 있어야 합니다:

```
[GUEST_ID] 방문자 식별 전략: hybrid
```

`shared (전 방문자가 하나의 게스트를 공유)`로 찍히면 값이 반영되지 않은 것입니다(오타는 조용히 `shared`로 폴백하므로 이 줄로 확인하세요).

> **분리 범위**: 채팅 스레드 목록·대화 이력·좋아요 소유권만 방문자별로 나뉩니다. **업로드된 문서는 설계상 전원 공유**(`DocRegistry.SHARED`)이며, 문서까지 사용자별로 격리하려면 정식 인증(`AUTH_ENABLED=true`)이 필요합니다.
>
> **기존 대화 주의**: 이 설정을 켜기 전에 쌓인 스레드는 예전 공용 게스트 id에 묶여 있어 **더 이상 보이지 않습니다**(삭제되지는 않으며 `shared`로 되돌리면 복구). 운영 중 전환이라면 사용자 공지가 필요합니다.

자세한 전략별 비교는 [§9.4.3](#943-접속자별-채팅-개인화-appauthguest-identity)을 참조하세요.

#### 5) 이미지 · OCR (로컬 모델 전제)

- **Vision 설명**: `IMAGE_DESCRIPTION_ENABLED=false`면 Vision 호출이 아예 없습니다(모델 불요). 켜 두더라도 실제 설명은 업로드 시 "이미지 설명 추가"를 체크한 문서에서만 생성되므로, 설명이 필요하면 로컬 vision 모델(예: llama-server에 llava 계열)을 VISION provider로 등록하세요. (`app.image-description.mode`는 **읽지 않는 값**입니다 — §3.2 참고.)
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
- `data/memory.db` (+`-wal`/`-shm`)
- **`SQLITE_VEC_DB_PATH` 를 설정했다면 그 파일도 함께** — 예: `data/vector.db`(+`-wal`/`-shm`). ⚠️ 그 배포에서는 **대화·계정·설정·레지스트리가 전부 그 파일에 있으므로**, `memory.db` 만 지우면 아무것도 초기화되지 않습니다([§6.3.1](#631-sqlite-파일별-테이블-구성))
- `data/documents/`
- `data/converted/`
- `data/images/`

chroma 백엔드 추가 삭제 대상:
- `data/chroma/` (로컬 Chroma 경로 사용 시)
- Docker named volume `chroma_data` (compose volume 사용 시)

```bash
# macOS / Linux
rm -f data/memory.db data/memory.db-wal data/memory.db-shm
rm -f data/vector.db data/vector.db-wal data/vector.db-shm   # SQLITE_VEC_DB_PATH 를 켠 경우
rm -rf data/documents data/converted data/images data/chroma
mkdir -p data/documents data/converted data/images data/chroma data/audit
```

```powershell
# Windows PowerShell
Remove-Item data/memory.db,data/memory.db-wal,data/memory.db-shm -Force -ErrorAction SilentlyContinue
# SQLITE_VEC_DB_PATH 를 켠 경우
Remove-Item data/vector.db,data/vector.db-wal,data/vector.db-shm -Force -ErrorAction SilentlyContinue
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

텍스트 태스크 3종은 **사다리**(`MICRO_TEXT` ⊂ `LIGHT_TEXT` ⊂ `TEXT`)라 무거운 타입이 가벼운 작업을 함께 받는다. **Vision은 사다리와 무관한 별도 축**이라 어떤 텍스트 타입도 흡수하지 않는다(mmproj 없는 모델로 이미지가 흘러가면 안 되므로).

| type | 처리 가능 태스크 | 권장 모델 유형 |
|------|----------------|--------------|
| `MICRO_TEXT` | 키워드+맥락·요약·제목·쿼리 확장·질의 독립화만 (추론 불필요) | 500MB급 소형 모델 (§6.21) |
| `LIGHT_TEXT` | MD 서식 교정·TXT 구조화 + `MICRO_TEXT` 잡무 | 텍스트 전용 소형~중형 모델 |
| `LIGHT_BOTH` | `LIGHT_TEXT` 태스크 + Vision | 범용 로컬 LLM |
| `TEXT` | 답변 생성·Rerank·분류·meta 직답 **+ `LIGHT_TEXT`·`MICRO_TEXT` 잡무 전부** | 텍스트 전용 대형 모델 |
| `VISION` | 이미지 설명만 | Vision 전용 모델 |
| `BOTH` | 모든 태스크 | 외부 고성능 / 범용 대형 모델 |

> **`BOTH`는 태스크로 요청되는 일이 없다** — 프로바이더 설정값과 `LlmRouter.hasEnabledProviderFor()`류의 판정에만 쓰인다. 표의 `BOTH` 행(프로바이더 능력)과 태스크로서의 `BOTH`는 다른 것이다.
> `LIGHT_BOTH`는 반대로 양쪽에 다 쓰인다 — 프로바이더 type이자, `ImageTypeClassifier`(이미지 유형 분류)가 "멀티모달이면 되고 대형일 필요는 없다"는 뜻으로 내는 요청이다. 이 요청은 `LIGHT_BOTH`·`BOTH`가 받고 텍스트 전용 3종과 `VISION` 전용은 거부한다(경량 텍스트 + 이미지를 함께 요구하므로).

#### role 값 (COST_FIRST 기준 시도 순서)

| role | 설명 | 순서 |
|------|------|------|
| `LOCAL` | 로컬 LLM (무료) | 1순위 |
| `NORMAL` | 저비용 외부 API | 2순위 |
| `PREMIUM` | 고추론 외부 API | 3순위 |

#### 에이전트 노드별 TaskType

| 노드 | TaskType | 설명 |
|------|----------|------|
| ClassifierService | `TEXT` | 질문 유형 분류 (품질 민감 — 답변과 같은 타입으로 묶어 큰 모델 유지) |
| RetrievalService | `MICRO_TEXT` | 쿼리 생성 (MultiQueryExpander) — §6.21 작업2로 MICRO_TEXT 전환 |
| CuratedQuestionSuggester | `MICRO_TEXT` | 큐레이션 Q&A 질문 구체화 제안 — 관리자가 `/admin` 편집에서 버튼을 눌러야만 돕니다. 배경 호출이라 동시성 게이트를 타지 않고, 사용량은 `/llm-usage` 에 `question:` 범주로 잡힙니다 |
| QuestionCondenser | `MICRO_TEXT` | 짧은 후속 질문의 독립화 (§10.12) — 확장이 생략되는 길이 구간에서만 돌아 한 턴의 질의 전처리 호출은 여전히 최대 1회 |
| AnswerService | `TEXT` | 답변 생성 + **충분도·근거 통합 평가**(별도 1콜) |
| CriticService | — | **LLM 호출 없음** — AnswerService의 통합 평가가 낸 `grounded`를 읽어 재시도 여부만 결정 (`responseMode=S`이면 이 단계 스킵) |
| DirectAnswerService | `TEXT` | meta 질문 직접 응답 (사용자 노출 — 답변과 같은 타입으로 묶어 큰 모델 유지) |
| VisionDescriptionService | `VISION` | 이미지 → 설명 생성 |
| ImageTypeClassifier | `LIGHT_BOTH` | 이미지 유형 분류 — 멀티모달(`LIGHT_BOTH`/`BOTH`)만 후보. `VISION` 전용 모델은 텍스트를 못 하므로 제외 |
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
| `LOCAL_ONLY` | LOCAL만 사용, 외부 API 미호출 | 오프라인 / 보안 환경 |

> **PROGRESSIVE 승격 조건**: 임계값 설정은 없습니다(`app.llm.progressive-threshold` 는 2026-09-01 제거 — 값을 읽는 코드가 없어 어떤 값을 넣어도 동작이 같았습니다). **PROGRESSIVE 승격 조건** — 점수도 임계값도 없다. `AnswerService.checkSufficiencyAndMaybeUpgrade()` 의 조건 셋이 전부다: 라우팅 모드가 `PROGRESSIVE` 이고, 검증이 `sufficient=false` 를 냈고(`needsRetry`), 재시도를 이미 다 썼을 때(`retryCount >= max-retry-count`).

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

# LOCAL — 무료, 분류·키워드·경량 태스크 처리
# base-url에 폴백 기본값을 두지 않는다 — LOCAL_LLM_URL이 비면 이 프로바이더는 기동 시 제외된다(LlmConfig G2)
app.llm.providers[0].name=local
app.llm.providers[0].base-url=${LOCAL_LLM_URL:}
app.llm.providers[0].api-key=${LOCAL_LLM_KEY:no-key}
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

COST_FIRST 흐름 — `local`이 `LIGHT_BOTH`라 **`TEXT` 태스크는 로컬에 남지 않는다**:
```
[키워드·요약·제목·쿼리확장 (MICRO_TEXT)]  local
[MD 교정·TXT 구조화 (LIGHT_TEXT)]         local
[이미지 설명 (VISION)]                     local
[이미지 유형 분류 (LIGHT_BOTH)]            local
[답변·분류·직답·Rerank (TEXT)]             gemini-flash → openai-mini → gemini-pro → openai
                                           (각 단계에서 429/오류 시 다음 우선순위로 자동 전환)
```

- 분류·meta 직답은 `LIGHT_TEXT`가 아니라 **`TaskType.TEXT`** 라서(§5.6 노드별 TaskType 표) `LIGHT_BOTH` 로컬 모델이 받지 못하고 클라우드로 나갑니다. 로컬에 남기려면 `type`을 `BOTH`로 올려야 합니다.
- 이미지 작업(설명·유형 분류)은 둘 다 로컬이 처리합니다 — `LIGHT_BOTH`가 `VISION`과 `LIGHT_BOTH` 요청을 모두 받기 때문입니다.

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
[전 태스크] local-a ∥ local-b (둘 다 BOTH, priority 0 동일 → least-in-flight 분산)
```

---

#### 예제 6 — 소형(경량) LLM 분리로 잡무 오프로딩 (PLAN §6.21)

추론이 필요 없는 잡무(키워드+맥락 추출·대화 요약·제목 생성·MultiQuery 쿼리 확장 = `MICRO_TEXT`)를 500MB급 소형 모델로 내리고, 답변 생성과 품질 민감한 분류·직답(셋 다 `TEXT`)은 큰 모델이 전담하게 하면 — 두 모델이 **서로 다른 동시성 슬롯(Semaphore)**을 쓰므로 인덱싱 잡무가 채팅 답변의 슬롯을 잠식하지 않습니다(대화 응답 지연 감소).

> 이 오프로딩은 `application.properties` 기본 설정에 이미 적용되어 있습니다(`providers[0]`=소형, `providers[1]`=큰 모델) — 아래는 처음부터 구성하는 방법을 보여주는 예제입니다.

소형 모델 서버를 큰 모델과 **다른 포트/장비**에 띄운 뒤(예: LM Studio 2번째 인스턴스에 `qwen2.5-0.5b-instruct`를 로드, 포트 1236), `application.properties`:
```properties
# 큰 모델 — 답변·분류·직답(전부 TEXT)·MD 교정/TXT 구조화(LIGHT_TEXT)·Vision 전담.
# priority를 1로 올려 소형에 MICRO_TEXT 우선권을 넘긴다.
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
[분류·meta 직답]            local        (TEXT/BOTH, priority 1)    ← 큰 모델(품질 유지)
[답변·Critic·Rerank]        local        (TEXT/BOTH, priority 1)    ← 큰 모델
[MD 교정·TXT 구조화]        local        (LIGHT_TEXT→BOTH, p1)      ← 큰 모델
[Vision·이미지 분류]        local        (소형은 이미지 미지원)      ← 큰 모델
소형 다운/차단 시           → MICRO_TEXT가 local(priority 1)로 자동 폴백
```

- ⚠️ **priority 필수**: 소형(0) < 큰 모델(1). 둘 다 0으로 두면 `MICRO_TEXT`가 두 모델 사이에 로드밸런싱되어 절반만 오프로딩됩니다.
- ⚠️ **인덱스 연속성**: `providers[N]`은 0부터 연속이어야 바인딩됩니다(파일 내 줄 순서 자체는 무관 — 사람이 읽기 편하도록만 맞춤). 현재 기본 파일은 `[0]`=소형·`[1]`=로컬 LLM 1·`[2]`=로컬 LLM 2·`[3]~[8]`=외부(NORMAL 3 + PREMIUM 3)·`[9]`=Vision(선택, 주석 처리) 순.
- **더 공격적 오프로딩(A안)**: MD 서식 교정·TXT 구조화까지 소형으로 내리려면 `type=MICRO_TEXT` 대신 `type=LIGHT_TEXT`로 등록(`LIGHT_TEXT`가 `MICRO_TEXT`도 흡수). 구조 충실도가 떨어지면 청킹 품질에 직결되므로 채택 전 검색 품질 평가 하네스([§6.6](#66-검색-품질-평가-하네스-개발자용))로 회귀를 확인하세요.
  - ⚠️ **분류·직답은 A안으로도 내려가지 않습니다** — 둘 다 답변과 같은 `TaskType.TEXT`라서, 소형을 `LIGHT_TEXT`로 올려도 후보에 들지 않습니다. 소형을 `TEXT`/`BOTH`로 등록하면 답변 생성까지 함께 소형으로 넘어갑니다(같은 타입이라 분리 불가).
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
[분류·meta 직답·답변·Critic] local-a ∥ local-b            (전부 TEXT → BOTH, priority 1, least-in-flight 분산)
```

- 총 잡무 처리량 = 소형 대수 × concurrency(2×4=8), 총 답변 처리량 = 큰 모델 대수 × concurrency(2×3=6) — **두 숫자는 서로 독립**이라 티어별로 필요한 만큼만 대수를 늘리면 됩니다(예: 인덱싱이 병목이면 소형만 증설, 채팅이 병목이면 큰 모델만 증설).
- 인덱스는 활성 프로바이더 다음 번호부터 연속이어야 합니다. 위 예시의 [6][7][8]은 **외부 프로바이더를 쓰지 않는 배포 기준**이며, 기본 파일 그대로(활성 [0]~[8] = 로컬 3 + 외부 6)라면 증설분은 [9]부터 시작하고 `local-vision` 예시는 그만큼 더 밀어야 합니다.
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
2. 요청이 슬롯을 요청하면 최대 `LLM_PERMIT_WAIT_TIMEOUT_SECONDS`(기본 60초) 동안 대기.
3. 슬롯이 나면 즉시 LLM 호출 → 완료 후 슬롯 반환.
4. 대기 상한을 넘기면 HTTP 429 + `Retry-After` 헤더로 즉시 응답(`RAG-LLM-002`) — Circuit Breaker 차단이나 다른 프로바이더로의 자동 전환은 하지 않습니다(§5.5 참고). SSE 스트리밍 응답에서는 "현재 요청이 몰려 있습니다. 잠시 후 다시 시도해 주세요." 메시지로 우아하게 종료됩니다.

**적용 범위**: 분류(Classifier)·답변 생성(블로킹+스트리밍+PROGRESSIVE 업그레이드+충분도 평가)·DirectAnswer·리랭킹(opt-in)·MultiQuery 확장까지 채팅/질의 경로 전체에 적용됩니다. **인덱싱/백그라운드 LLM 호출(키워드 추출, MD 포맷 교정, Vision 설명, TXT 구조화, 대화 요약 사전계산, 스레드 제목 생성)은 이 게이트의 대상이 아닙니다** — 이미 `INDEXING_MAX_LLM`으로 자체 동시성을 제어하고 있고, 마감시한 있는 동기 HTTP 호출자가 없기 때문입니다.

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

**헤더 LLM 동시성 표시기**: 웹 UI 우측 상단에 `LLM: {사용중}/{용량}`으로 실시간 포화도를 보여줍니다(`GET /api/v1/llm/concurrency`, ~3초마다 폴링). `사용중` 값이 `용량`에 도달하면(완전 포화) 숫자가 굵은 빨간색으로 강조됩니다 — 위 429/`[BACKPRESSURE]` 로그를 매번 찾아보지 않아도 한눈에 확인할 수 있는 보조 지표입니다.
- `role=LOCAL, priority=1`(주 응답용 로컬 티어 — MICRO_TEXT 전용 소형 오프로딩 모델은 제외) 프로바이더들의 `concurrency` 합계가 용량이고, 실제 게이트에서 점유 중인 슬롯 수가 사용중 값입니다.
- **임베딩 활동도 함께 반영됩니다**: 인덱싱·검색 임베딩 호출은 이 채팅 동시성 게이트를 전혀 거치지 않는 별도 `EmbeddingModel` 데코레이터 체인이라, 별도 in-flight 카운터(`EmbeddingConcurrencyTracker`)를 채팅 사용량에 합산합니다 — 그래서 임베딩만 바쁠 때도 지표가 0에 머무르지 않습니다. 합산값은 용량을 넘지 않게 잘립니다(임베딩 동시성은 `EMBED_MAX_CONCURRENT_BATCHES` 등 별도 한도라 합이 용량을 초과할 수 있기 때문).
- **서킷브레이커로 차단된 프로바이더는 용량에는 남고 그 전체가 "사용중"으로 집계됩니다** — 제외돼서 지표 자체가 사라지는 대신, 로컬 프로바이더가 하나뿐인 배포에서 그게 차단되면 예컨대 `3/3`(완전 포화)으로 표시됩니다.
- 로컬 티어 프로바이더가 하나도 등록/활성화돼 있지 않으면 지표 자체가 숨겨집니다.

---

## 6. 운영 팁

### 6.1 대화 메모리

`MemoryService`는 **SQLite**(`DATA_DIR/memory.db`)에 대화 이력을 영속합니다.

- WAL 모드로 읽기/쓰기 경합 최소화. SQLite pool size는 반드시 1 유지
- 스레드별 최근 `MEMORY_FETCH_LIMIT_TURNS`(기본 10)턴 이내에서 `LLM_MAX_TOKENS × 0.5`까지 LLM 컨텍스트 주입
- `/chat/{threadId}` 재진입 시 모든 이전 turn을 시간순으로 불러와 메시지 버블 복원
- `MemoryRepository` 인터페이스로 추상화 — Redis 등으로 교체 시 구현체만 추가

**요약 캐시 (`ConversationSummarizerService`)**: 스레드 대화 이력을 미리 요약해 캐싱해두고, 실제 질의 시 원문 전체 대신 "요약 + 최근 N턴 원문"을 컨텍스트로 사용해 토큰을 절약합니다.

- **대부분의 경우 LLM을 쓰지 않습니다.** RAG 답변은 `prompt.answer.system`이 강제하는 5-섹션 형식상 이미 자기 요약(`## 요약`)을 첫 섹션으로 갖고 있어, 그것을 그대로 뽑아 쓰면 됩니다. 모든 턴이 그렇다면 **LLM 호출 0회**로 요약이 완성됩니다. LLM이 필요한 것은 그 섹션이 없는 답변뿐이고 — §10.13 이후 DN 답변도 요약을 낼 수 있어 그 빈도가 줄었지만, 요구가 **조건부**라 인사말·짧은 답변·요약이 군더더기인 답변에서는 여전히 없습니다 — 그때도 입력은 이미 다른 턴들이 요약으로 치환된 상태라 모델은 사실상 그 답변만 압축합니다.
- **소형 모델(`LOCAL_FAST_LLM_URL`)이 없으면 LLM 호출만 생략하고, 요약 조립은 그대로 합니다.** 요약 섹션이 없는 답변은 300자로 잘라 담습니다(`UNSUMMARIZED_ANSWER_CAP`, 상수 — 프로퍼티 아님). §10.13 이후 DN 답변도 요약을 낼 수 있게 됐지만 그 요구가 **조건부**라(요약이 도움이 될 때만) 이 절단은 계속 쓰입니다. 완성된 요약은 `SUMMARY_MAX_SUMMARY_CHARS`로 **앞에서부터** 잘리므로, 이 절단이 없으면 앞쪽의 긴 Direct 답변 하나가 예산을 독식해 정작 최신 턴이 밀려납니다.
- 원문 history 폴백은 이제 **요약할 턴이 아예 없을 때만** 일어납니다(빈 스레드, 전부 DISLIKE 등).

- **트리거**: 주 트리거는 답변이 완료되고 턴이 저장된 직후(`precomputeAfterTurn()`)입니다 — 사용자가 다음 질문을 입력하기 전부터 백그라운드로 미리 갱신을 시작하므로, 응답을 읽는 동안이 곧 요약 준비 시간이 됩니다. 사용자가 질문창에 첫 글자를 입력할 때 발화되는 기존 트리거(`precompute()`)는 아직 한 번도 요약이 만들어지지 않은 스레드(예: 재시작 후 처음 열어본 오래된 대화)를 위한 콜드스타트 안전망으로 남아 있습니다.
- **요약과 `[Recent]`는 겹치지 않게 나뉩니다**: 요약 대상은 **최근 `SUMMARY_RECENT_RAW_TURNS`개를 뺀 이전 턴들**이고, 최근 창은 `[Recent]`가 담당합니다. 예전에는 요약이 전체 턴을 대상으로 만들어져 최근 턴이 양쪽에 중복으로 들어갔습니다(아래 렌더링 변경 이후로는 RAG 턴의 경우 **문자 그대로 동일한** 중복이라 3턴 대화 기준 문맥의 약 40%가 낭비였습니다).
  - 따라서 **요약은 3턴째부터 생깁니다**(기본값 기준). 1~2턴 스레드는 `[Conversation Summary]` 섹션 없이 `[Recent]`만으로 문맥이 구성되고 **LLM 요약 호출도 0회**입니다 — 압축할 이전 대화가 애초에 없기 때문입니다.
  - 이때 요약 캐시에는 **빈 문자열**이 저장됩니다. "요약 실패"(캐시 없음 → 원본 폴백)와 "요약할 이전 턴이 없음"을 구분하기 위한 것으로, 후자를 캐시 없음으로 두면 `getHistory()` 폴백으로 떨어져 방금 줄인 답변 전문이 그대로 되돌아옵니다.
- **`[Recent]` 구간의 답변 렌더링** (`SUMMARY_RECENT_RAW_TURNS`개, 기본 2): **지금 무엇을 묻는지**와 **이전 턴이 어떤 모드였는지**, 두 축이 함께 정합니다(§10.13). 아래는 **RAG로 물을 때**이고, Direct로 물을 때는 그 아래 항목을 보세요.
  - **RAG 답변**(`## 요약` 섹션 있음) → **요약만**. 원문은 문서 청크를 다시 풀어 쓴 것인데 그 청크는 **다음 턴이 어차피 재검색**하므로(매 턴 검색), 전문을 되먹이면 `[검색된 문서]`를 수천 자짜리로 중복시키는 셈입니다. 게다가 그 중복본은 원본이 아니라 모델이 만든 미검증 산문이라, 이전 턴의 오류가 문맥에 고착될 위험도 있습니다. 2,700자짜리 답변이 ~250자로 줄어듭니다.
  - **요약 섹션이 없는 답변** → **원문을 남기되 1,200자에서 절단**. 근거 문서가 없어 그 답변 자체가 유일한 기록이므로 지울 수 없고, 길이만 제한합니다. 상한은 `HistoryPolicy.RECENT_ANSWER_CAP` 상수 — **프로퍼티가 아닙니다**. §10.13 이 DN 답변에도 `## 요약`을 요구하면서 이 캡에 걸리는 빈도가 줄었지만 — 요구가 **조건부**라 요약이 없는 긴 DN 답변은 여전히 나옵니다 — 그 경우의 안전판으로 남아 있습니다.
  - 판별은 `## 요약` 헤딩 유무입니다(`conversation_turns`에는 `direct_mode` 컬럼이 있으나, 렌더링은 답변 본문 형식을 직접 기준으로 삼습니다). `prompt.answer.system`은 그 섹션을 강제하고, `prompt.direct.system.n`은 §10.13 이후 **조건부**로 요구합니다("핵심을 먼저 요약하는 것이 도움이 될 때" — 분량이 아니라 쓸모가 기준이라, 코드 한 덩어리나 단계별 절차처럼 그 자체가 개요인 답변에는 붙지 않습니다) — 형식을 어긴 답변은 절단 쪽으로 떨어져 안전한 방향입니다.

- **Direct로 물을 때는 이력을 훨씬 많이 받습니다** (§10.13). Direct 답변의 프롬프트에는 `[검색된 문서]` 블록이 **통째로 없는데**(기본 설정에서 그 자리는 `SEARCH_TOP_K × CHUNK_SIZE` ≈ 15,000자) 이력 상한은 모드와 무관하게 `LLM_MAX_TOKENS/2`로 고정이었습니다 — 창의 큰 부분이 놀고, 정작 이력만으로 답해야 하는 모드가 이력을 가장 적게 받았습니다. 규칙은 "Direct라서"가 아니라 **"문서 자리가 비어서"**입니다: `이력 상한 = 입력 예산 − 문서가 차지할 자리`.
  - **넓히기만 하는 것이 아닙니다.** 창이 좁은 배포에서는 고정 5,000자보다 작게 나올 수 있고, 그것이 옳습니다 — Direct 경로에는 예산 가드가 없어서 그 5,000자가 이미 창을 넘기고 있었습니다. 줄여야 했다면 답변 아래에 `컨텍스트 한도로 이전 대화 일부를 제외했습니다.`가 함께 뜹니다.
  - **창을 모르는 프로바이더면 아무것도 하지 않습니다**(고정값 그대로). 추측한 숫자로 대화 맥락을 늘리거나 버리지 않습니다 — `/settings`의 컨텍스트 창 재탐지(§6.26)를 돌리면 이 계산이 켜집니다.
  - 이때 이전 턴의 렌더도 달라집니다: **S 턴은 `## 요약`**(그게 답변 전부), **N·C 턴은 `## 요약`·`## 참고`를 뺀 본문**, **DN 턴은 전문**(뺄 섹션이 없고 캡도 걸리지 않음). C의 `## 검증되지 않은 부분`은 남습니다 — 다음 턴이 무엇이 미확인인지 아는 것은 쓸모가 있습니다.
  - **RAG로 물을 때는 지금 동작 그대로입니다.** 이력은 검색보다 **먼저** 로딩되므로 그 시점에 문서가 몇 개 올지 모르고, Direct만 검색이 아예 돌지 않아 0이 확정입니다.
  - 같은 이전 턴이 **다음에 무엇을 묻느냐에 따라 다르게 렌더됩니다**(`RN → Direct`면 본문 통째로, `RN → RAG`면 요약 두세 문장). 이번 턴에 무엇이 함께 들어가야 하는지가 다르기 때문입니다.
  - 이전에는 양쪽 다 **무제한 원문**이었습니다. RAG 답변이 2~3천 자인데 이력 예산이 3,000자(`LLM_MAX_TOKENS/2`)라, 한 건이 예산을 독식해 `SUMMARY_RECENT_RAW_TURNS=2`가 사실상 1로 동작했습니다.
- **싫어요 제외 범위**: 싫어요가 눌린 턴은 이 경로의 **요약과 `[Recent]` 원문 구간 양쪽**에서 빠집니다(`dedupeTurns()`). `getRecentTurns()`의 SQL에는 feedback 조건이 없으므로 — 다른 호출자는 원본 행이 필요합니다 — 이 클래스에서 그 메서드를 쓰는 곳은 반드시 `dedupeTurns()`를 거쳐야 합니다. 예전에는 `buildContext()`가 이를 건너뛰어, 싫어요 답변이 요약에서만 빠지고 `[Recent]`에는 **전문 그대로** 다시 들어갔습니다(원본 폴백 경로와도 어긋났고, RAG 답변은 2~3천 자라 그 한 건이 문자 예산을 독식해 정상 턴을 밀어냈습니다).
- **싫어요 처리**: 답변 직후 트리거된 요약 생성이 진행되는 동안(LLM 호출이 아직 끝나지 않은 짧은 시간) 사용자가 방금 그 턴에 싫어요를 누르면, 완성된 요약이라도 캐싱하지 않고 버립니다 — 다음 질문은 자동으로 원문 폴백 경로를 쓰게 되며, 그 경로는 애초에 DISLIKE 턴을 제외합니다. 다음 정상 트리거 때는 dedupe 단계에서 자연스럽게 그 턴이 빠진 채로 다시 요약됩니다.
- **좋아요 처리**: LIKE 는 그 자체로는 아무것도 등록하지 않는다(§10.11) — 지식 제안 폼을 열어 줄 뿐이고, 검색 인덱스에 들어가는 것은 관리자가 승인한 뒤다. 저장된 LIKE 값을 읽는 곳은 둘: 재사용 필터(Direct 턴은 좋아요가 있어야 재사용된다)와 채팅 버튼 상태. 상세는 [§6.7 큐레이션 Q&A](#67-큐레이션-qa-공유-지식-축-1010--1011) 참고.
- 캐시 미스이거나 LOCAL 프로바이더가 없으면 자동으로 원문 폴백 경로(`MemoryService.getHistory()`) 사용 — best-effort, 실패해도 채팅이 막히지 않음
- 요약 경로와 폴백 경로는 **동일한 문자 예산과 동일한 렌더 규칙**을 씁니다(§6.11 + §10.13, 규칙의 단일 출처는 `HistoryPolicy`) — 요약 캐시 유무에 따라 LLM에 전달되는 컨텍스트의 **양도 내용도** 달라지지 않습니다. 예전에는 폴백 경로가 답변을 전문 그대로 넣어, 캐시 TTL이 지나는 순간 같은 스레드의 맥락이 갑자기 달라졌습니다
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
| 삭제된 문서 원본 | `DATA_DIR/documents/backup/` | 문서 삭제 시 원본이 여기로 이동(즉시 삭제 아님). 저장 상한 집계에서 **제외**되며 보존 정책 3규칙으로 정리됨 — §"업로드 크기 제한" 참조. `syncDirectory()` 의 비재귀 스캔 아래라 새 파일로 재검출되지 않음 |
| 추출된 이미지 | `DATA_DIR/images/{imageId}/` | `imageId`는 문서 SHA-256 앞 16자(문서명이 아닌 내용 기반 키) — 문서 삭제 시 함께 삭제되나, 내용이 동일한 다른 문서가 남아 있으면 보존 |
| 지식 제안 본문 이미지 | `DATA_DIR/images/submissions/` | 사용자가 업로드한 제안 본문 이미지(§6.9). 파일명은 내용 SHA-256 앞 16자 + 확장자라 같은 그림은 한 벌만 저장되고 **여러 제안이 공유**할 수 있습니다 — 그래서 삭제는 참조 세기 방식입니다(반려·철회 시 + 기동 시 24시간 지난 미참조 파일 스윕). 디렉터리 이름이 문자열 `submissions`이므로 16자리 hex인 `{imageId}`와 절대 충돌하지 않습니다 |
| DOCX 변환 MD (원본) | `DATA_DIR/converted/{docId}.md` | DOCX 인덱싱 시 자동 생성; 문서 삭제 시 함께 삭제 |
| DOCX 변환 MD (교정본) | `DATA_DIR/converted/{docId}_corrected.md` | LLM 포맷 교정 후 저장; 실제 인덱싱 소스; 수동 편집 후 벡터 스토어 관리 페이지에서 ↺ 재인덱싱 가능 |
| 인덱스 레지스트리 | `doc_registry` 테이블 — `SQLITE_VEC_DB_PATH`를 **비웠으면** `DATA_DIR/memory.db`, **설정했으면 그 벡터 DB 파일**([§6.3.1](#631-sqlite-파일별-테이블-구성)) | SHA-256 기반 변경 감지. 문서 저장소는 사용자별 격리 없이 공유됨(`DocRegistry.SHARED`) — `userId` 파라미터는 API 시그니처상 존재하나 실제로는 무시됨 |
| 벡터 임베딩 | chroma: Chroma 서버(로컬 `data/chroma/`, Docker Compose `chroma_data` 볼륨) / sqlite-vec: `DATA_DIR/memory.db`(기본) 또는 `app.vectorstore.sqlite-vec.db-path` 설정 시 별도 `vector.db` | 백엔드 전환 시 벡터 공유 안 됨(§3.1) |
| 대화 이력 + LLM 사용량 | 인덱스 레지스트리와 **같은 파일** (위 행 참조 — `memory.db` 또는 벡터 DB 파일) | WAL 모드; 메시지 메타데이터(토큰·시간·프로바이더) 포함. 파일별 테이블 구성은 아래 [§6.3.1](#631-sqlite-파일별-테이블-구성) |
| 감사 로그 | `DATA_DIR/audit/audit.log` | JSON Lines; 롤링 압축본 `audit.YYYY-MM-DD.N.log.gz` 포함 |

> Docker Compose 사용 시 `./data` 디렉터리를 컨테이너에 바인드 마운트합니다.  
> 데이터 백업 시 `data/` 디렉터리와 Chroma 볼륨을 함께 보존하세요.
>
> ⚠️ **DB 파일만 골라 백업하려면 [§6.3.1](#631-sqlite-파일별-테이블-구성)을 먼저 읽으세요.** `SQLITE_VEC_DB_PATH`를 설정한 배포에서는 대화·계정·설정까지 **전부 그 벡터 DB 파일에 있고 `memory.db`는 빈 껍데기**입니다 — 이름만 보고 `memory.db`를 복사하면 0행짜리 파일을 백업하게 됩니다.

#### 6.3.1 SQLite 파일별 테이블 구성

DB 파일은 **최대 두 개**입니다. `SQLITE_VEC_DB_PATH`(=`app.vectorstore.sqlite-vec.db-path`)를 **비워 두면 파일은 `memory.db` 하나뿐**이고, 값을 넣으면(예: `./data/vector.db`) 두 번째 파일이 생깁니다. Chroma 백엔드에서는 벡터가 Chroma 서버에 있으므로 그 파일에는 FTS 색인만 남습니다.

> ⚠️ **분리를 켜면 벡터뿐 아니라 운영 테이블까지 그 파일로 갑니다** — 이름과 달리 `memory.db`에는 아무것도 쌓이지 않습니다. 이유는 아래 «어느 파일에 들어가는가»에 있습니다. 스위치를 켠 배포에서 **실데이터가 있는 파일은 벡터 DB 하나뿐**이라고 생각하는 편이 맞습니다.

**운영 테이블** — 대화·계정·설정·레지스트리. 분리 **off**면 `memory.db`, **on**이면 벡터 DB 파일에 만들어집니다.

| 테이블 | 내용 | 생성 주체 |
|---|---|---|
| `conversation_turns` | 대화 턴(질문·답변·토큰·프로바이더·피드백·응답모드·검색 스코프 태그·**검색 진단 수치**) | `SqliteMemoryRepository` (Flyway V1 + 방어적 `ALTER`) |
| `turn_image_ref` | 턴별 답변 썸네일 이미지 참조(개별 제외는 `status`) | `SqliteMemoryRepository` |
| `turn_source_ref` | 턴별 출처 청크 스냅샷(재사용 검증용 `chunk_hash`) | `QuestionReuseRepository` |
| `thread_meta` | 대화 제목·버전·라우팅 모드·태그 | Flyway V1·V3 |
| `image_descriptions` | Vision 이미지 설명 캐시 | Flyway V1 |
| `doc_registry` | 인덱싱된 문서 레지스트리(SHA-256 변경 감지, `chunk_overlap`) | `DocRegistry` |
| `llm_usage` | 프로바이더별 일자별 토큰 사용량 | Flyway V1 |
| `curated_qa` | 큐레이션 Q&A(좋아요 승격 + 승인된 지식 제안) | `CuratedQaRepository` |
| `curated_submission` | 지식 제안 게시판 | `CuratedSubmissionRepository` |
| `chunk_report` | 청크 오류 신고 대기열(사유·코멘트 + 신고 시점 원문·질문 스냅샷, §6.12) | `ChunkReportRepository` |
| `settings_override` | `/settings` 핫 수정 오버라이드 | `SettingsOverrideRepository` |
| `users`, `persistent_logins` | 계정·자동 로그인 토큰 | Flyway V2 / `SqliteUserDetailsService` |
| `app_secret` | 게스트 식별 HMAC 키 등 서버 비밀값 | `AppSecretRepository` |
| `flyway_schema_history` | 마이그레이션 이력 | Flyway |

**벡터·검색 색인 테이블** — 분리 **on**이면 벡터 DB 파일, **off**면 위 운영 테이블과 같은 `memory.db`

| 테이블 | 내용 | 생성 주체 |
|---|---|---|
| `vec_embeddings` | vec0 가상 테이블 — 임베딩 벡터(`FLOAT[app.embedding.dimensions]`) | `SqliteVecSchemaInitializer` (sqlite-vec 백엔드 전용) |
| `vec_document_chunks` | 청크 원문 + JSON 메타데이터. `spring_doc_id`로 위 테이블과 JOIN | `SqliteVecSchemaInitializer` (sqlite-vec 백엔드 전용) |
| `chunk_fts` | FTS5(trigram) 키워드 색인 — 하이브리드 검색의 BM25 축 | `KeywordSearchRepository` (**백엔드 무관, 항상 생성**) |

> 위 두 표에 없는 이름이 파일 안에 보이면 대개 **SQLite가 자동 생성한 그림자 테이블**입니다 — `chunk_fts_data`/`_idx`/`_content`/`_docsize`/`_config`(FTS5), `vec_embeddings_*`(vec0), `sqlite_sequence`(AUTOINCREMENT). 직접 조회·수정하지 마세요.
>
> **어느 파일에 들어가는가 — 코드가 주입받는 `JdbcTemplate`이 정합니다.** 그런데 **분리를 켜면 그 템플릿이 하나뿐**입니다: `DataSourceConfig`가 `vectorJdbcTemplate` 빈을 직접 정의하는 순간 Spring Boot의 `JdbcTemplateAutoConfiguration`(`@ConditionalOnMissingBean(JdbcOperations.class)`)이 통째로 물러나, 컨텍스트에 `memory.db`용 `JdbcTemplate`이 아예 만들어지지 않습니다. 그래서 `@Qualifier` 없이 `JdbcTemplate`을 받는 저장소(`SqliteMemoryRepository`·`SqliteUserDetailsService`·`ThreadMetaRepository`·`CuratedQaRepository`·`CuratedSubmissionRepository`·`ChunkReportRepository`·`LlmUsageRepository`·`SettingsOverrideRepository`·`AppSecretRepository`·`DocRegistry`…)도 **전부 벡터 DB 파일에 씁니다**. `QuestionReuseRepository`는 두 템플릿을 모두 주입받지만, 분리가 켜져 있으면 `turn_source_ref` 쓰기도 결국 같은 파일로 갑니다. 분리가 **꺼져 있으면** 그 한 빈이 운영 DataSource를 감싸므로 전부 `memory.db`이고, 이 사실은 겉으로 드러나지 않습니다. (동작 고정: `DataSourceJdbcTemplateWiringTest`, `-Dsqlitevec.path` 필요)
>
> **그럼 `memory.db`는 지워도 되나?** 분리를 켠 배포에서 그 파일은 Flyway가 만든 **빈 테이블 + 마이그레이션 이력**뿐이라 데이터 손실 없이 지울 수 있지만, **얻는 것도 없습니다** — 앱은 기동할 때마다 `@Primary` DataSource로 그 파일을 다시 만들고 Flyway를 다시 적용합니다(빈 파일이므로 무해). 백업 대상에서 빼는 것은 괜찮고, 파일 자체를 없애려면 배선을 바꿔야 합니다.
>
> ⚠️ **Flyway는 실데이터에 닿지 않습니다.** Flyway는 `@Primary` DataSource(=`memory.db`)에 적용되고 이력(`flyway_schema_history`)도 거기 남는데, 분리를 켜면 실제 테이블은 벡터 DB 파일에 각 저장소의 런타임 DDL(`CREATE TABLE IF NOT EXISTS` + 방어적 `ALTER`)로 만들어집니다. 그래서 **새 `V4__*.sql`을 추가하면 빈 파일에만 적용되고 "성공"으로 보고됩니다.** 신규 컬럼은 Flyway가 아니라 런타임 `ALTER` 패턴으로 추가한다는 규약([PLAN §13](PLAN.md#13-db-스키마-변경-요약))을 지키는 한 문제가 되지 않습니다 — 그 규약을 어기지 마세요.
>
> ⚠️ **두 파일은 트랜잭션이 분리돼 있습니다.** 인덱싱은 벡터 → FTS → 레지스트리 순서로 쓰며 마지막 레지스트리 커밋이 "색인 완료"의 기준입니다. 두 파일을 쓰는 배포라면(분리 off·Chroma 조합 등) 백업 시 **같은 시점에 함께** 보존하세요(한쪽만 되돌리면 레지스트리와 벡터가 어긋납니다).
>
> **스키마는 기동 시 자동 정비됩니다** — 위 «생성 주체»에 Flyway로 적힌 테이블도 각 저장소가 런타임 `CREATE TABLE IF NOT EXISTS`를 함께 갖고 있고, Flyway 이후의 컬럼은 `@PostConstruct`의 `ALTER TABLE`(이미 있으면 조용히 무시)로 추가됩니다. Flyway가 닿지 않는 벡터 DB 파일에서도 같은 스키마가 만들어지는 것이 이 때문입니다. 따라서 **오래된 DB 파일을 가져다 놓고 앱을 재기동하면 자동으로 최신 스키마가 됩니다**(기존 행은 보존, 새 컬럼은 `NULL`). 반대로 앱이 실행 중일 때 DB 파일을 교체하면 열려 있는 커넥션과 어긋나 손상될 수 있으니, 반드시 **앱을 내린 뒤** 교체하세요.

> **`doc_registry.chunk_overlap`**: 문서를 인덱싱(또는 ↺ 재인덱싱)한 시점에 실제로 적용된 `app.chunk-overlap` 값을 문서별로 함께 기록합니다 — §6.8 문서 내보내기가 이 값을 읽어 청크 재조립 시 overlap을 정확히 제거하는 데 씁니다. 이 컬럼이 추가되기 전에 인덱싱된 문서는 `NULL`로 남아 있다가, 기동 시 `ChunkOverlapBackfill`이 한 번 그 시점의 `app.chunk-overlap` 현재값으로 채웁니다(이미 값이 있는 행은 건드리지 않음 — 멱등). 운영자가 직접 조작할 일은 없는 내부 컬럼입니다.

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

CPU/메모리 제약이 있는 환경에서는 `INDEXING_MAX_FILES`와 `INDEXING_MAX_LLM`을 줄이세요.

---

### 6.5 설정 페이지 (`/settings`) — LLM/RAG 옵션 조회·핫 수정

`/settings`는 현재 **유효** LLM/RAG 설정을 한 화면에서 보여주고, 일부 검색 튜닝 값은 **재기동 없이** 조정할 수 있게 합니다. `application.properties`/환경변수를 고치고 재기동하지 않아도 검색 동작을 실시간으로 미세조정할 수 있습니다.

**조회 항목 (그룹별)**:
- **LLM 라우팅**: 등록 프로바이더·역할(role)·우선순위·모델·API 키 설정 여부·서킷브레이커 상태·**활성화 여부**(아래 참조), 기본 라우팅 모드. 일반/RAG temperature와 **max-tokens**는 아래 "LLM 튜닝" 핫 수정 그룹으로 옮겨졌습니다. **`app.llm.default-routing-mode`(`LLM_ROUTING_MODE`)가 `LOCAL_ONLY`인 배포에서는 이 표에 `LOCAL` 역할 프로바이더만 표시됩니다** — 이 모드에서는 라우팅이 NORMAL/PREMIUM을 절대 선택하지 않으므로, `application.properties`에 여전히 등록돼 있더라도(예: 나중에 모드를 바꿀 때를 대비해 남겨둔 설정) 표에서는 숨겨져 혼동을 줄입니다. 표 위에 이 사실을 알리는 안내 문구가 함께 표시됩니다. 숨겨진 프로바이더는 `POST /admin/settings/provider/toggle`로도 조작할 수 없습니다(알 수 없는 프로바이더로 거부).
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
| ↳ **이 값은 경계다** — 길이가 이 값 **이상**이면 멀티쿼리 확장, **미만**이면 §10.12 질의 독립화(짧은 후속 질문 재작성)가 돈다. 둘은 여집합이라 한 턴의 질의 전처리 LLM 호출은 어느 쪽이든 최대 1회다. 0 으로 두면 확장만 돌고 독립화는 영영 돌지 않는다 | | |
| 재시도 시 후보 확대 | `app.search-retry-escalate` | true/false |
| topK (검색 상위 K) | `app.search-top-k` | 1 ~ 50 |
| 멀티쿼리 확장 (+ §10.12 질의 독립화) | `app.search-multiquery-enabled` | true/false |
| 하이브리드 검색 | `app.search-hybrid-enabled` | true/false |
| 큐레이션 Q&A 검색 반영 (§10.10) | `app.search-curated-qa-enabled` | true/false |
| 큐레이션 Q&A 가중치 (§10.10) | `app.search-curated-qa-weight` | 0.0 ~ 10.0 |

**핫 수정 가능 — 인덱싱/청킹 (재기동 불필요, 다음 인덱싱/↺ 재인덱싱부터 반영)** — 검색 튜닝과 달리 즉시가 아니라 **다음 인덱싱**부터 적용되며, 이미 색인된 청크를 소급 재분할하지는 않습니다(값을 바꾼 뒤 재업로드하거나 `/admin` ↺ 재인덱싱을 눌러야 반영):

| 항목 | 키 | 범위 |
|------|----|------|
| 청크 크기(자) | `app.chunk-size` | 100 ~ 8000 |
| 청크 오버랩(자) | `app.chunk-overlap` | 0 ~ 2000 |
| 최소 청크 크기(자) | `app.min-chunk-size` | 0 ~ 4000 |
| 동시 파일 처리 수 | `app.indexing.max-concurrent-files` | 1 ~ 4 |
| 동시 LLM 호출 수 | `app.indexing.max-concurrent-llm-calls` (`INDEXING_MAX_LLM`) | 1 ~ 8 |

> **프로바이더별로 따로 줄 수 있는 값 2종**(환경변수 없음, `application.properties` 직접 편집, 재기동 필요):
> `app.llm.providers[N].max-tokens` — 그 프로바이더만의 출력 상한(미설정 시 전역 `app.llm.max-tokens`).
> `app.llm.providers[N].context-size` — 그 프로바이더의 총 컨텍스트 창(미설정 시 기동 탐지, 실패하면 "모름").
> 창 크기는 모델마다 다르므로 8k 로컬과 128k 클라우드가 하나의 상한을 공유할 수 없습니다 — 특히 좁은 모델에서는 **출력 예약 자체가** `n_ctx` 를 넘기는 원인입니다.

> **`INDEXING_MAX_LLM`의 적용 범위**: 이 값은 키워드+맥락 추출 전용이 아니라 **인덱싱 계열 LLM 호출의 공통 병렬도**입니다 — 키워드 추출(`DocumentIndexer`), MD 포맷 교정(`MarkdownCorrectionService`), 인덱싱 중 이미지 설명("이미지 설명 추가" 체크 시, `MarkdownCorrectionService`가 문서 내 이미지를 이 값만큼 병렬 분석 — 예전엔 순차라 사실상 `INDEXING_MAX_FILES`에 매여 있었음), TXT 구조화(`TextToMarkdownService`), 지연 Vision 설명(`LazyVisionService`)이 모두 이 값을 씁니다. 다만 이 값은 "앱 전체 동시 LLM 호출 N개"라는 **전역 예산이 아닙니다**. 소비처마다 규칙이 다릅니다:
>
> - **키워드 추출**: `syncDirectory()`가 세마포어를 **1개만 만들어 모든 파일이 공유** → 파일 수와 무관하게 총 `INDEXING_MAX_LLM`개. (파일당 1개씩 배분되는 게 아니라 티켓을 나눠 씁니다)
> - **MD 교정 / TXT 구조화**: 호출마다 **자기 세마포어를 새로 생성** → 파일 병렬 시 곱으로 증가.
>
> 같은 파일 안에서는 구조화/교정 → 청킹 → 키워드 추출이 순차 단계라 겹치지 않지만, 파일끼리는 단계가 동기화되지 않아(A는 키워드, B는 교정) 겹칩니다. 결과적으로 **인덱싱 LLM 동시 호출 피크 ≈ `INDEXING_MAX_FILES` × `INDEXING_MAX_LLM`** 입니다 — 예: 3 × 4 = 최대 12. 그래서 기본값을 `INDEXING_MAX_FILES=1`로 두어 피크를 정확히 `INDEXING_MAX_LLM`(기본 3)으로 고정했습니다. 로컬 LLM 서버의 `--parallel` 한도에 맞추려면 `INDEXING_MAX_LLM`을 그 값으로 두고 `INDEXING_MAX_FILES=1`을 유지하세요. 처리량을 위해 `INDEXING_MAX_FILES`를 올린다면 곱이 `--parallel`을 넘지 않는지 확인하세요.

**핫 수정 가능 — LLM (재기동 불필요, 다음 LLM 호출부터 반영)** — §6.18:

| 항목 | 키 | 범위 |
|------|----|------|
| 일반/RAG temperature | `app.llm.temperature` (`LLM_TEMPERATURE`) | 0.0 ~ 0.3 |
| Direct(잡담) 응답 temperature | `app.llm.direct-temperature` (`DIRECT_LLM_TEMPERATURE`) | 0.0 ~ 1.0 |
| 인덱싱/백그라운드 temperature | `app.llm.indexing-temperature` (`LLM_INDEXING_TEMPERATURE`) | 0.0 ~ 0.1 |
| C(응용) 응답 temperature | `app.llm.creative-temperature` (`CREATIVE_LLM_TEMPERATURE`) | 0.0 ~ 1.0 |
| C(응용) 응답 모드 사용 | `app.llm.creative-mode-enabled` (`CREATIVE_MODE_ENABLED`) | true/false (기본 ON) |
| 컨텍스트 초과 재시도당 제외할 문서 수 | `app.llm.shrink-step` (`LLM_SHRINK_STEP`) | 1 ~ 10 (기본 1) |
| 블로킹 호출 출력 상한 | `app.llm.max-tokens` (`LLM_MAX_TOKENS`) | 1000 ~ 32000 |

**핫 수정 가능 — UI (재기동 불필요, 다음 화면 렌더부터 반영)**:

| 항목 | 키 | 범위 |
|------|----|------|
| 출처 미리보기 표시 | `ui.source-preview-enabled` | true/false (기본 ON) |
| 출처 검색 수치 표시 | `ui.retrieval-metrics-enabled` | true/false (기본 **OFF**) |

- `ui.source-preview-enabled` 기본값은 `true`(ON)이며, 관리자 설정으로 시스템 전체에 적용됩니다. `false`면 채팅의 출처 배지 팝오버 미리보기를 초기화하지 않습니다.
- `ui.retrieval-metrics-enabled`를 켜면 채팅 출처마다 `유사도 0.72 · 응답 31%`가 함께 표시됩니다(유사도가 없는 순수 BM25 히트는 `bm25:1 · 응답 12%`처럼 축 표기로 대체 — 유사도와 축 순위는 비는 조건이 배타적이라 한 칸을 번갈아 채웁니다). **검색기여(`검색 %`)와 전체 축 순위는 채팅에 표시하지 않고 [§7.7 진단 패널](#77-검색-진단-수치--검색-튜닝-근거-보기)에만 둡니다** — 순위 기반이라 한 턴 안에서는 평평해 여러 턴의 경향으로 봐야 의미가 생기기 때문입니다 — 검색 튜닝(§6.5의 RRF 가중치·유사도 임계값·하이브리드 검색)의 효과를 실제 질의에서 눈으로 확인하기 위한 값입니다. 계산은 RRF 융합의 부산물이라 **LLM·임베딩 추가 호출이 없고**, 끄고 켜는 것으로 검색 결과가 달라지지 않습니다(표시만 제어).
  - **읽는 법**: `유사도`는 질의와의 코사인 거리라 절대값으로 읽히고 턴을 넘어 비교됩니다 — 채팅에 이 값을 남긴 이유입니다. 축 표기(`bm25:1`)로 대체돼 있으면 벡터 축이 이 청크를 못 찾았다는 뜻이니, 단어만 겹친 근거일 수 있어 한 번 더 확인하세요. `/admin` 패널에만 있는 `검색 %`·전체 축 순위의 정의와 해석은 [§7.7 검색기여 읽는 법](#77-검색-진단-수치--검색-튜닝-근거-보기) 참고.
  - **값이 안 보이는 경우**는 오류가 아닙니다 — 벡터 축에 걸리지 않은 청크는 유사도가 없고(BM25·큐레이션 전용 히트), 쿼리 확장 LLM 호출이 실패해 폴백 경로로 처리된 턴은 RRF를 건너뛰어 `검색 %`·순위가 없습니다.
  - **`응답 %`(응답 참여도)는 앞의 셋과 성격이 다릅니다** — 답변 텍스트 중 그 청크에서 온 것으로 추정되는 비율이며, 답변이 끝난 뒤에야 계산돼 배지에 사후로 붙습니다. 역시 LLM 추가 호출은 없습니다(평가 호출에 필드 하나를 얹고, 나머지는 문자 n-gram 계산).
  - **`응답 %`는 추정이지 측정이 아닙니다.** 진짜 인과적 기여도는 청크를 빼고 답변을 다시 생성해야(leave-one-out) 알 수 있으며 턴당 LLM 호출이 topK배로 늘어 실시간에는 넣지 않았습니다. 또 `응답 모드 S`는 평가 호출 자체를 건너뛰므로 인용 신호 없이 어휘 유사도만으로 계산됩니다.
  - **유사도가 높은데 `응답 %`가 없는 출처는 정상입니다** — 질문과 비슷하지만 답변에 쓰이지 않은 문서이고, 이 **불일치가 오히려 가장 유용한 신호**입니다. "검색은 잘 되는데 답변이 안 쓴다"가 반복되면 프롬프트/topK를, "유사도는 낮은데 답변이 많이 썼다"가 반복되면 임베딩 모델을 의심하십시오.
  - **"반복되면"을 확인하는 곳이 `/admin` 최하단의 검색 진단 수치 카드입니다** — 채팅 배지는 그 순간만 보이지만, 위 튜닝 값들은 여러 턴의 경향을 보고 정해야 합니다. 이 표시 토글(`ui.retrieval-metrics-enabled`)과 무관하게 수치는 항상 기록되므로, 토글을 꺼둔 채로도 패널은 채워집니다.
- 사용자 로컬 토글(localStorage)은 사용하지 않고, 서버 모델값으로 일관 적용합니다.

- **"기본값" 버튼**으로 오버라이드를 삭제하면 `application.properties`/환경변수 값으로 정확히 복귀합니다(오버라이드가 있으면 항상 프로퍼티보다 우선).
- 오버라이드는 **재기동 후에도 유지**됩니다(테이블에 영속). 배포 기본값 자체를 바꾸려면 여전히 환경변수/`application.properties`를 수정하세요 — 오버라이드는 그 위에 얹히는 런타임 조정 레이어입니다.

**조회 전용(재기동 필요)**: `rerank-enabled`(빈 생성 시점 `@ConditionalOnProperty`로 결정)·쿼리 임베딩 캐시(빈 생성 시점 결정), 임베딩 차원·벡터 스토어 백엔드(DDL/빈 구성). 기본 라우팅 모드도 조회 전용입니다(대화별 라우팅은 채팅 화면에서 설정). LLM temperature 3종(일반/RAG·Direct·인덱싱/백그라운드)은 모두 위 "핫 수정 가능 — LLM" 표로 옮겨져 있습니다 — 단, 일반/RAG temperature는 프레임워크 내부 호출(예: 멀티쿼리 확장)에는 여전히 기동 시점에 고정된 값이 적용됩니다.

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

### 6.7 큐레이션 Q&A (공유 지식 축, §10.10 · §10.11)

관리자가 승인한 **지식 제안**이 별도로 임베딩되어, 이후 유사한 질문의 검색 결과에 **큐레이션 Q&A 축**으로 반영됩니다. `documents/PLAN.md` §10.10·§10.11에 전체 설계·구현 기록이 있으며, 여기서는 운영 관점만 요약합니다.

> **2026-09-02 변경 (§10.11) — 좋아요가 더 이상 직접 등록하지 않습니다.** 예전에는 👍 를 누르면 그 자리에서 `curated_qa` 행이 만들어지고 3초 뒤 임베딩되어 전체 사용자의 검색에 들어갔습니다. 검색 코퍼스로 들어가는 문이 둘인데 한쪽만 관리자 승인을 요구하는 상태였고, 문서를 하나도 안 본 Direct 답변이 클릭 한 번에 공유 지식이 될 수 있었습니다. 지금은 👍 가 [§6.9 지식 제안](#69-지식-제안-게시판-사용자-제안--관리자-임베딩) 폼을 그 답변으로 채워 열어 줄 뿐이고, **등록은 관리자 승인에서만** 일어납니다. 검토 화면에는 그 답변의 `[RN]`/`[DN]` 표기와 원 대화 링크가 함께 뜹니다 — `D` 는 검색을 거치지 않은 답변이라는 뜻이므로 반려 판단의 재료입니다.

**동작 원리**:
- 관리자가 **임베딩 실행**을 누르면 `curated_qa` 테이블(운영 테이블 — 파일은 §6.3.1)에 검토된 텍스트가 저장되고, 백그라운드 스레드가 곧바로 임베딩합니다(디바운스 없음 — 승인은 취소와 경합할 수 없는 명시적 동작입니다).
- **응답 모드가 `S`(간단히)였던 turn은 좋아요를 눌러도 제안을 만들 수 없습니다** — 버튼이 비활성으로 뜨고 사유가 툴팁에 붙습니다. S 는 **의도적으로 축약된 답변**이기 때문입니다: 1,000자 상한의 `## 요약` 한 섹션이고, 프롬프트가 배경·이유·전제·예외를 빼라고 지시합니다 — 지금 짧게 보려고 고른 형식이지 오래 남길 지식의 원본이 아닙니다. `C`(응용)는 §10.11 에서 **열렸습니다** — 예전에 막았던 이유(모델이 지어낸 코드가 다음 턴의 "문서"가 되는 되먹임)가 치명적이었던 것은 게이트가 없었기 때문이고, 사람이 편집하고 관리자가 승인하는 지금은 C 답변도 다른 제안과 같은 심사를 받습니다. 판정은 `ResponseMode.allowsSubmission()`이고 기준값은 `conversation_turns.response_mode`(turn 생성 시 저장)입니다. **싫어요는 모드와 무관하게 그대로 동작합니다**(다음 대화 컨텍스트에서 제외).<br>※ 옛 `L`(원문 최대) 모드의 임베딩 스킵은 §6.24에서 제거됐습니다. 응답 모드와 `LLM_MAX_TOKENS`의 관계는 [§3.2 `LLM_MAX_TOKENS`](#32-환경변수-전체-목록), 설계 전체는 [PIPELINE §3.1](PIPELINE.md)을 참고하세요.
- 임베딩은 예약된 벡터 스토어 버전 네임스페이스 `"curated"`에 저장됩니다 — 실제 문서 버전과 완전히 분리되어 있어 **문서를 재인덱싱해도 큐레이션 지식은 사라지지 않습니다.**
- 검색 시 이 축은 기존 벡터/키워드(BM25) 축과 함께 가중 RRF로 융합됩니다(§6.5 "큐레이션 Q&A 검색 반영/가중치" 참고) — 큐레이션 답변이 검색되면 **정답을 그대로 반환하는 것이 아니라 LLM이 참고할 근거로 주입**되므로, 현재 문서 내용과 다르면 LLM이 최신 문서를 우선하도록 설계되어 있습니다.
- 답변 텍스트 끝에 LLM이 자동으로 붙이는 "## 참고"(출처 인용) 섹션과 맨 앞 "## 요약" 섹션은 검색 색인에 들어가지 않습니다 — 파일명·페이지 번호가 질문 의도와 무관한 노이즈이고, 요약은 "## 상세 설명"과 중복이기 때문입니다. **원문 전체는 `curated_qa.answer`에 그대로 보존**되어 채팅 버블과 관리자 편집기에서 그대로 보입니다.
- **긴 답변은 여러 청크로 나뉘어 임베딩됩니다**(§6.9 제안 게시판과 같은 `ChunkSplitter`, §6.10의 분할 전략까지 동일 적용). 다만 게시판과 달리 **DB 행은 turn 당 하나로 유지**되고 벡터만 여러 개가 됩니다 — 대화·턴 삭제 회수와 재승인이 전부 turn 단위라 행을 쪼개면 그 동작들이 깨지기 때문입니다. 몇 개로 나뉘었는지는 `curated_qa.chunk_count`에 기록되어 회수·강제 삭제·재임베딩이 모든 벡터를 찾습니다.
- 재임베딩(편집 저장)은 **새 벡터를 먼저 쓴 뒤** 남은 이전 벡터를 지웁니다 — 답변이 짧아져 청크 수가 줄어도, 재임베딩이 실패하면 기존 벡터가 검색에 그대로 남아 있도록 하기 위해서입니다.
- **검색 가중치는 하나입니다**: 두 경로 모두 같은 `"curated"` 네임스페이스에 저장되고 한 번의 검색으로 나오며, `SEARCH_CURATED_QA_WEIGHT`(기본 **1.0**, `/settings`에서 핫 수정) 하나가 전체를 다룹니다. 예전에는 `curated_origin`으로 갈라 좋아요 축과 제안 축에 각각 가중치를 줬는데, 그 구분의 근거가 "앱이 만든 무검토 출력" 대 "사람이 쓴 텍스트"였습니다 — 이제 **모든 항목이 사람 편집 + 관리자 승인을 거치므로** 그 차이가 사라져 `SEARCH_SUBMISSION_WEIGHT`는 제거됐습니다. `curated_origin` 자체는 감사·통계용으로 계속 기록됩니다.
- **긴 항목일수록 청크가 많아 그 축에서 차지하는 후보 수가 늘어납니다** — 한 답변이 상위권을 여러 칸 차지할 수 있습니다. 가중치를 올리기 전에 이 점을 함께 고려하세요.
- **임베딩 청크 크기는 문서보다 크게 시작합니다**: `CHUNK_SIZE`의 **2배 → 1.5배 → 1배** 순으로 시도하고, 임베딩 서버가 거부할 때만 줄입니다. 큐레이션 텍스트는 하나의 이어진 논증이라 문서용 크기로 자르면 근거로서 읽히지 않기 때문이며, 줄이는 것은 오직 거부에 대한 대응입니다. 세 크기가 모두 실패하면 기존처럼 핵심 섹션만으로 한 번 더 시도합니다. `EMBED_MAX_CHUNK_CHARS`가 설정돼 있으면 어느 배수에서든 그 상한이 먼저 적용됩니다.

**대화 삭제와의 관계**: 대화(thread)를 통째로 삭제하면 그 대화에서 승격된 큐레이션 Q&A도 **함께 회수됩니다**(행 비활성화 + 벡터 삭제). 사용자가 채팅창에서 지우든 관리자가 §7.9에서 지우든 동일하며, 관리자 경로에서는 확인 대화상자에 회수될 건수가 미리 표시되고 삭제 후 감사 로그에도 남습니다.

> ⚠️ **정책이 한 번 바뀌었습니다.** 최초 §10.10 정책은 반대였습니다 — "대화를 지워도 큐레이션은 유지"(개인 대화 정리가 공유 검색 품질을 조용히 떨어뜨리는 것을 막기 위함). §6.25에서 회수로 바뀐 이유는 (1) 대화가 사라진 뒤에도 그 답변이 검색 근거로 남는 쪽이 실제로는 더 혼란스러웠고, (2) **턴 단위 삭제는 처음부터 회수하고 있어**(싫어요 → 턴 삭제 시, 지금의 `onTurnDeleted`) 두 경로의 동작이 갈려 있었기 때문입니다. 이전 버전에서 대화를 지웠다면 그때 승격된 항목은 여전히 살아 있을 수 있으니, 정리하려면 §7.5의 큐레이션 카드에서 확인하세요.
>
> 승인된 **지식 제안**(`origin='manual'`, §6.9)은 특정 대화에 속하지 않으므로 이 회수의 대상이 아닙니다 — 대화를 아무리 지워도 남습니다. 회수는 §7.5에서 강제 삭제하거나 제안 자체를 회수해야 합니다.

**편집 권한**:
| 주체 | 방법 | 범위 |
|---|---|---|
| 본인(좋아요를 누른 사용자) | 채팅 버블의 👍 옆 편집(연필) 아이콘 — 좋아요 상태일 때만 노출 | 본인이 좋아요한 답변만(스레드 자체가 사용자별로 격리되어 있어 타인 것은 애초에 보이지 않음) |
| 관리자 | `/admin` **큐레이션 Q&A** 카드 (§7.5) | 전체 사용자의 큐레이션 항목 |

두 경로 모두 저장 시 자동으로 재임베딩됩니다. 좋아요 취소는 곧 삭제와 같습니다(본인 경로) — 관리자는 좋아요 주체와 무관하게 강제 삭제할 수 있습니다(§7.5).

**설정**: `SEARCH_CURATED_QA_ENABLED`/`SEARCH_CURATED_QA_WEIGHT`(§3.2) — 둘 다 `/settings`에서 재기동 없이 핫 수정 가능합니다(§6.5).

---

### 6.8 문서 내보내기

`/documents` 문서 목록 각 행의 **내보내기** 버튼(관리자 전용)으로 인덱싱된 문서를 MD·TXT·DOCX 파일로 다시 뽑아낼 수 있습니다. `GET /ui/documents/{docId}/export`는 `/admin/**`과 별개로 `SecurityConfig`/`NoAuthAutoLoginFilter` 양쪽에 직접 `hasRole("ADMIN")`로 게이팅됩니다(§9.4.2 관리 전용 인증 모드의 문서 관리 게이트 목록에도 포함) — 문서 전체 내용을 한 번에 반출하는 벌크 기능이라 게스트에게 열린 조회 경로(`/documents`, `/ui/documents/list`)와는 다르게 취급합니다.

**소스는 저장된 원본 MD가 아니라 현재 색인된 청크입니다** — `/admin`에서 청크를 편집·삭제했다면 그 내용이 그대로 반영됩니다. 그 대신 검색을 위해 `ChunkSplitter`가 일부러 벌여 놓은 청크 간 중복을 다시 걷어내야 하는데, `ChunkReassembler`(`src/main/java/.../export/ChunkReassembler.java`)가 다섯 가지를 역순으로 제거합니다: 재주입된 소제목(`## 소제목 (2)`), 부모 챕터 breadcrumb 복제, 코드펜스 이어짐 마커(`[코드 이어짐: ...]`), 표 헤더 복제, 슬라이딩 윈도우 overlap. 앞의 넷은 마커·구조를 정확히 식별해 제거하는 결정적 로직이고, **overlap 제거만 유일한 휴리스틱**입니다(직전 청크 끝부분과 글자 단위로 일치하는 가장 긴 접두사를 찾되, 16자 미만의 우연한 일치는 무시). 실제 335청크 문서로 검증한 결과 원본 대비 글자 수 오차 0.001%, 헤딩 총 개수·중복 헤딩 개수 완전 일치.

> **overlap 값은 문서별로 실제 인덱싱 당시 값을 씁니다, 현재 설정값이 아니라**: `app.chunk-overlap`은 §6.5 설정 페이지에서 재기동 없이 바뀌는 핫 설정이라, 내보내기 시점의 현재값을 그대로 쓰면 인덱싱 이후 값이 바뀐 문서에서 overlap 제거가 엉뚱한 지점을 잘라내거나 놓칠 수 있습니다. 그래서 인덱싱/재인덱싱이 청크를 실제로 자른 overlap 값을 `doc_registry.chunk_overlap`에 함께 기록해 두고(§6.3), 내보내기는 그 저장된 값을 우선 사용합니다 — 레지스트리에 값이 아예 없을 때만(예: 이 컬럼 자체가 없던 아주 오래된 배포에서 백필도 아직 안 된 순간) 현재 설정값으로 폴백합니다. `app.chunk-overlap` 기본값이 `0`으로 바뀌었으므로(§3.2), 새로 인덱싱하는 문서는 애초에 이 휴리스틱이 개입할 일이 없는 상태로 시작합니다.

**옵션**:

| 옵션 | 기본값 | 설명 |
|---|---|---|
| 형식 | MD | MD / TXT / DOCX 중 선택. **PPTX는 지원하지 않습니다** — 재조립된 청크에서 슬라이드 경계·레이아웃을 되짚어 재구성할 뾰족한 방법이 없어 향후 과제로 남겨둠 |
| 이미지 설명 포함 | 켜짐 | Vision LLM이 생성한 `[이미지 설명: ...]` 마커를 MD는 인용문, DOCX는 이탤릭 캡션, TXT는 괄호 텍스트로 포함할지 여부 |
| 소제목 번호·목차 추가 | 꺼짐 | H2~H6에 계층 번호(`1`, `1.1`)를 매기고 문서 맨 앞에 목차를 붙임. 이미 번호가 있어도 먼저 벗겨낸 뒤 다시 매기므로 멱등. **PPTX 원본 문서에는 적용되지 않음**(체크박스가 비활성화되고 이유가 표시됨) — §3.3 "소제목 숫자 생성"과 같은 이유로, PPTX 슬라이드 제목은 챕터 구조가 아니기 때문 |

**형식별 처리**:

| 형식 | 이미지 마커 처리 | 산출물 |
|---|---|---|
| MD | `![파일명](images/파일명)` 링크로 치환 | 문서에 이미지가 하나라도 있으면 `{파일명}.md` + `images/`를 담은 ZIP, 없으면 `.md` 파일 그대로 |
| TXT | `(이미지: 파일명)` 텍스트로 치환 | 마크다운 문법(헤딩·강조·펜스·링크)을 모두 걷어낸 평문 `.txt` |
| DOCX | POI(`XWPFDocument`)로 실제 이미지를 임베드 — 단, 표 셀 안에 있는 인라인 마커는 파일명 텍스트로 대체(줄바꿈을 넣으면 그 표 행이 깨지므로) | 헤딩·표·펜스 코드블록·굵게/기울임/인라인코드를 그대로 렌더링한 `.docx` — `MarkdownCorrectionService.postProcess()`로 빈 줄·마커 정리까지 거친 뒤 렌더링 |

파일명은 원본 업로드 파일명(확장자 제외, 경로에 위험한 문자는 `_`로 치환)을 그대로 사용합니다. 해당 문서에 청크가 하나도 없으면 400을 반환합니다.

---

### 6.9 지식 제안 게시판 (사용자 제안 → 관리자 임베딩)

사용자가 문서에 없는 내용을 **게시판 형식으로 등록**하고, 관리자가 검토해 **임베딩 실행**하거나 **거부**하는 경로입니다. **§10.11 이후 검색 코퍼스로 들어가는 유일한 문**이며, 채팅의 좋아요도 여기를 거칩니다(좋아요를 누르면 그 답변으로 채워진 제안 폼이 열립니다). 승인된 제안은 §6.7 큐레이션 Q&A와 **같은 저장소·같은 검색 축**(예약 version 네임스페이스 `"curated"`)으로 들어갑니다 — 검색·가중치 설정(`SEARCH_CURATED_QA_ENABLED`/`SEARCH_CURATED_QA_WEIGHT`)이 그대로 적용되고, 별도로 켜고 끄는 스위치는 없습니다.

**⚠️ 보안: 관리자 승인이 유일한 방어선입니다.** (§10.11 이전에는 좋아요가 이 문장을 조용히 거짓으로 만들고 있었습니다 — 지금은 사실입니다.)

여기 등록된 본문은 승인 즉시 검색 대상이 되어, 검색되면 답변 프롬프트의 `[검색된 문서]` 블록에 그대로 주입됩니다. `PromptInjectionGuard.wrap()`은 사용자 *질문*을 감싸는 장치일 뿐 **검색 결과는 감싸지 않으므로**, 프롬프트 인젝션에 대한 실질적인 관문은 승인 단계 하나뿐입니다. 그래서:

- 검토 화면은 본문을 **잘라내지 않고 전문**으로 보여줍니다.
- **일괄 승인·자동 승인 경로는 의도적으로 구현하지 않았습니다.** 추가하지 마세요.
- 게스트에게 열린 배포(§9.4)라면 등록은 누구나 할 수 있지만 승인은 `ROLE_ADMIN`만 가능합니다(아래 접근 제어 참고).

**저장 구조**:

| 테이블 | 위치 | 역할 |
|---|---|---|
| `curated_submission` | 운영 DB(§6.3.1) | 게시글 자체 — 제목·본문·상태(`pending`/`approved`/`rejected`/`withdrawn`)·거부 사유·검토자·작성자 확인 시각·출처 턴(`source_turn_id`/`source_thread_id`, 좋아요 출신일 때만) |
| `curated_qa` | 운영 DB(§6.3.1) | 승인된 제안의 실제 색인 항목(`origin` 으로 출처 구분 — 감사·통계용이며 검색 가중치는 하나입니다). 손으로 쓴 제안은 승인 시 **여러 행**(청크)이 되고, 좋아요 출신은 **turn 을 키로 하는 행 하나**가 임베딩 시점에 벡터 여러 개로 나뉩니다. 어느 쪽이든 `source_submission_id`로 묶입니다 |

두 테이블을 분리한 이유: `curated_qa.status='active'`는 "지금 검색에 기여 중"이라는 뜻으로 검색·모더레이션 코드 전반이 이 의미에 의존하고 있어, 검토 대기 상태를 여기 섞으면 그 불변식이 깨집니다.

> **첫 기동 시 자동 스키마 마이그레이션**: 사용자 제안은 대화 turn이 없으므로 `curated_qa.source_turn_id`가 nullable이어야 하는데, SQLite는 이를 `ALTER`로 바꿀 수 없습니다. 그래서 기존 DB에서 이 기능이 처음 올라올 때 `CuratedQaRepository`가 **테이블을 1회 재생성**합니다(단일 트랜잭션 — 중간 실패 시 롤백, 기존 행은 `origin='like'`로 그대로 보존). 로그의 `[CURATED] curated_qa 스키마 마이그레이션 완료` 줄로 확인할 수 있으며, 두 번째 기동부터는 실행되지 않습니다. 기존 좋아요 항목의 검색 반영은 영향받지 않지만, 큰 변경이므로 **적용 전 DB 파일 백업을 권장**합니다 — `data/memory.db`, 그리고 `SQLITE_VEC_DB_PATH` 를 켰다면 실데이터가 있는 그 벡터 DB 파일([§6.3.1](#631-sqlite-파일별-테이블-구성)).

**입력 제한** (`CuratedSubmissionService` — 이미지 3종만 `CuratedImageStore`):

| 항목 | 값 | 비고 |
|---|---|---|
| 제목 최대 길이 | 200자 | `curated_qa.question` 컬럼에 그대로 저장됨 — 임베딩 입력의 앞부분이라 질문형 제목일수록 검색이 잘 됨 |
| 본문 최대 길이 | **없음** | 승인 시 `ChunkSplitter`로 분할되므로 상한을 두지 않는다(아래 참고) |
| 태그 | 최대 10개 · 각 32자 | `TagUtils.normalize()` 정책 공용. 관리자가 검토 화면에서 수정 가능 |
| 사용자당 검토 대기 상한 | 20건 | 초과 시 등록 거부 |
| 본문 이미지 개수 | 10장 (`CuratedImageStore.MAX_IMAGES_PER_SUBMISSION`) | 등록 시점과 승인 시점 **양쪽**에서 검사. 승인 1회가 이미지 수만큼의 Vision 호출을 부르므로, 본문 길이와 달리 분할로 흡수되지 않는 비용이다 |
| 이미지 파일 크기 | 5MB (`MAX_IMAGE_BYTES`) | 서블릿 multipart 상한(200MB)보다 훨씬 낮게 잡아 400으로 깔끔히 거부되게 함 |
| 이미지 형식 | png · jpg · jpeg · gif · webp | `DocumentController.getImage()`가 `image/*`로 인라인 제공하는 확장자와 동일 집합. 그 외(SVG 등)는 422 |

**본문 분할 (제안 1건 = 청크 N개)**: 승인 시 본문이 `ChunkSplitter`를 그대로 통과해 여러 `curated_qa` 행으로 등록됩니다 — 문서 인덱싱과 **같은 기계**라 `CHUNK_SIZE`·`EMBED_MAX_CHUNK_CHARS`는 물론 §6.10의 **청크 분할 전략(`CHUNK_SPLIT_GRANULAR`)과 표·코드 블록 보호까지 그대로 적용**됩니다. 이것이 길이 상한을 없앨 수 있는 이유입니다: 예전에는 긴 본문이 통째로 임베딩 API에 가서 실패했고, §6.7의 "입력이 너무 큼" 축소 재시도는 답변의 `## 상세 설명` 같은 섹션 구조에 의존하므로 손으로 쓴 제안에는 **폴백이 없었습니다**. 이제 모든 청크가 문서 파이프라인과 동일한 크기 한계 안에 들어옵니다.

- **제목은 모든 청크에 반복 부여**됩니다 — 임베딩 입력이 `제목 + 본문`이라, 2번째 청크부터 제목이 없으면 질문형 질의와의 매칭이 급격히 나빠집니다(문서 인덱싱의 소제목 재주입과 같은 이유).
- **태그도 모든 청크에 동일**하게 부여됩니다(제안 하나 = 한 스코프).
- 관리자 검토 화면에 **승인 시 몇 개 청크가 되는지**가 미리 표시되고, 승인 후에는 실제 생성된 개수가 표시됩니다.
- ⚠️ `MIN_CHUNK_SIZE`를 `CHUNK_SIZE`에 가깝게 잡으면 잘린 조각이 다시 병합되어 **분할이 사실상 사라집니다**(기본 1500/500 비율에서는 문제 없음).

**본문 이미지** (`CuratedImageStore`):

작성자가 본문에 이미지를 넣을 수 있습니다. 파일은 `{DATA_DIR}/images/submissions/{sha16}.{ext}`에 저장되고, 본문에는 **문서 파이프라인이 쓰는 것과 같은 `[이미지: images/submissions/…]` 마커**가 들어갑니다. 표준 마크다운(`![]()`)이 아니라 이 마커를 재사용한 덕분에 하위 경로가 전부 그대로 동작합니다 — `GET /api/v1/images/{docId}/{filename}`이 이미 이 경로를 서빙하고(디렉터리 이름 `submissions`는 16자리 hex인 문서 imageId와 절대 충돌하지 않습니다), `/admin` 청크 뷰가 이미 렌더하며, `RetrievalService`가 이미 `image_paths` 메타데이터를 답변 썸네일로 바꿉니다.

- **마커의 위치 = 이미지의 위치.** 그래서 승인 시 본문이 청크로 나뉘면 이미지가 자기가 설명하는 문단을 따라갑니다(`CuratedQaService.buildDocument()`가 **분할 후** 각 청크에 남은 마커로 `MetaKey.IMAGE_PATHS`를 계산 — 이미지 3장짜리 제안이 2청크가 되면 각 청크는 자기 몫만 갖습니다).
- **승인 시 Vision 설명이 본문에 주입됩니다.** `approve()`가 `ChunkSplitter`를 돌리기 **전에** `LazyVisionService`로 각 이미지의 설명을 만들어 `[이미지 설명: ...]` 줄을 마커 바로 뒤에 넣습니다. 순서가 중요한 이유는 두 가지입니다 — (1) 설명이 임베딩되는 텍스트의 일부여야 그림 내용으로 검색이 걸리고, (2) 나중에 주입하면 마커와 설명이 서로 다른 청크로 갈라질 수 있습니다. 주입된 본문은 `curated_submission.body`에도 되저장되어 작성자가 "실제로 색인된 내용"을 봅니다.
  - `IMAGE_DESCRIPTION_ENABLED=false`면 이 단계는 **통째로 건너뜁니다**(이미지는 표시만 되고 검색에는 기여하지 않음). Vision 호출이 실패해도 승인은 실패하지 않고 설명 없이 진행됩니다.
  - 설명은 `image_descriptions` 캐시에도 저장되고, 주입된 줄을 `RetrievalService.hasEmbeddedDescription()`이 인식하므로 **질의 시점에 다시 분석되지 않습니다**(§6.7 Lazy Vision과 중복 호출 없음).
  - ⚠️ **승인 요청이 이미지 수만큼의 Vision 호출을 동기로 기다립니다.** 로컬 Vision 모델이 느리면 이미지 5장짜리 제안의 승인이 수십 초 걸릴 수 있습니다(관리 UI는 버튼을 잠그고 스피너를 표시). 이 호출은 §6.12 채팅 동시성 게이트를 타지 않는 `executeWithTracking()` 경로이므로 채팅 슬롯을 잠식하지는 않지만, 로컬 LLM 서버 자원은 공유합니다. 상한(10장)을 낮추려면 `CuratedImageStore.MAX_IMAGES_PER_SUBMISSION` 상수를 바꿔야 합니다 — 프로퍼티로 외부화되어 있지 않습니다.
- **보안**: 게시판은 모든 인증 모드에서 게스트에게 열려 있어, 여기가 **미인증 사용자가 디스크에 바이너리를 쓰는 유일한 지점**입니다. 방어는 4겹입니다 — 확장자 허용목록 → 5MB 상한 → 매직바이트 검증(`FileTypeDetector`) → **내용 해시 파일명**(클라이언트가 경로의 어느 조각도 정하지 못하고, 같은 그림을 다시 올려도 바이트가 중복되지 않음). 본문에 손으로 적은 경로는 `images/{디렉터리}/{파일}.{확장자}` 형태이면서 실제로 존재할 때만 Vision 대상이 됩니다(`..` 차단이 아니라 형태 허용목록 — `LazyVisionService`는 자체 경로 봉쇄가 없습니다).
- **정리**: 파일명이 내용 해시라 두 제안이 같은 그림을 공유할 수 있으므로, 삭제는 **참조 세기** 방식입니다. `releaseImages()`(반려·철회 시)와 `sweepOrphans()`(기동 시 `ApplicationReadyEvent`, **24시간 유예**)가 살아 있는 제안(`pending`/`approved`)·활성 `curated_qa` 어디에서도 참조하지 않는 파일만 지웁니다. 유예 시간이 있는 이유는 폼이 **등록 전에** 이미지를 먼저 올리기 때문입니다(작성자가 미리보기로 확인할 수 있게) — 열어둔 작성 화면의 이미지를 발밑에서 지우지 않기 위함입니다. 상시 실행되는 스케줄러는 없으므로, 장기 무재기동 서버에서는 등록되지 않은 초안 이미지가 누적될 수 있습니다(디스크 사용량 점검 시 `{DATA_DIR}/images/submissions/` 확인).
- **업로드 자체는 감사 로그에 남지 않습니다** — 실제로 검색에 들어가는 시점인 `curated.submission.create`/`.approve`가 본문(마커 포함)을 기록합니다. 업로드 요청은 `[SUBMISSION] 본문 이미지 저장 {파일명}` INFO 로그로만 확인할 수 있습니다.

**전부/전무 (1:N 상태 파생)**: 제안은 청크가 여러 개여도 **하나라도 살아 있으면 등록 완료**, **전부 내려가면 회수됨**입니다. §7.5 큐레이션 탭에서 어느 청크 하나를 삭제하면 `CuratedQaService.forceRemoveBySubmission()`이 **같은 제안의 나머지 청크도 함께** 내립니다 — 작성자에게 "반쪽만 등록된" 상태를 보이지 않기 위한 의도적 설계이며, "N개 중 M개" 부분 표시는 제공하지 않습니다. 임베딩 실패 배지는 반대로 **하나라도 실패하면** 표시됩니다.

**접근 제어**:

| 경로 | 접근 |
|---|---|
| `GET/POST /curated/submissions`, `POST /{id}/withdraw`, `GET /unread-count` | 모든 인증 모드에서 열림(게스트 포함) — 등록은 검색에 즉시 영향을 주지 않는 `pending` 행 하나를 만들 뿐 |
| `POST /curated/submissions/images` | 위와 동일하게 열림 — 다만 **디스크에 파일을 쓰는** 유일한 게스트 개방 쓰기 경로다. 위 "본문 이미지"의 4겹 검증 + `RATE_LIMIT_DEFAULT_PER_MINUTE`(기본 120/분)이 방어선이며, 외부 노출 배포라면 프록시 단에서 이 경로만 따로 조이는 것도 검토할 만하다 |
| `GET /admin/submissions*`, `POST /admin/submissions/{id}/approve\|reject` | `/admin/**` 게이팅 상속 — 관리 전용 인증 모드(§9.4.2)에서는 `ROLE_ADMIN` 로그인 필수 |

> 대기 건수 조회(`GET /admin/submissions/pending-count`)를 `/api/v1/**`이 아니라 `/admin/**` 아래 둔 것은 의도적입니다 — `/api/v1/**`은 관리 전용 인증 모드에서 CSRF 예외 + 게스트 개방이라 거기 두면 대기 건수가 누구에게나 노출됩니다.

**알림 (관리자 / 작성자)**:

- 상단 네비게이션의 **관리자** 메뉴 옆에 검토 대기 건수, **지식 제안** 메뉴 옆에 작성자 본인의 미확인 처리 건수가 빨간 배지로 표시됩니다. **60초 간격 폴링**이며(헤더의 LLM 동시성 지표는 3초 — 게시글은 초 단위 신선도가 불필요), 0건이면 배지를 감춥니다.
- 관리자 배지는 `isAdmin`일 때만 렌더링되고, 엘리먼트가 없으면 폴링 요청 자체가 나가지 않습니다.
- 로그인 직후 첫 폴링이 바로 실행되므로 **"관리자가 로그인하면 알림"** 요구가 함께 충족됩니다. 다만 관리 전용 인증 모드에서 관리자가 평소 로그아웃 상태라면, 로그인하기 전까지는 제안이 대기만 합니다 — 메일·웹훅 같은 외부 알림 채널은 제공하지 않습니다.
- 작성자 배지는 **내 제안** 목록 페이지를 여는 순간 읽음 처리됩니다(배지 폴링 자체는 읽음 처리하지 않습니다 — 보기도 전에 사라지면 안 되므로).

**게스트 배포에서의 전제** (`app.auth.enabled=false`):

"내 제안"은 방문자를 구분할 수 있어야 성립합니다. `app.auth.guest-identity`가 기본값 `shared`면 **모든 방문자가 한 사람으로 취급되어 서로의 제안이 함께 보입니다.** 이 기능을 쓰려면 `hybrid`(권장) 등으로 바꾸거나 인증을 켜세요 — §9.4.3 참고. 반대로 스팸 상한(20건)도 그 방문자 id 기준이므로, 쿠키를 지우거나 다른 네트워크에서 접속하면 새 버킷이 됩니다. 외부에 노출된 배포라면 별도의 IP 기준 제한을 앞단(프록시)에 두는 것을 검토하세요.

**감사 로그** (§9, `data/audit/audit.log`):

| action | 시점 |
|---|---|
| `curated.submission.create` | 사용자가 제안 등록 (제목·정규화 후 글자 수 포함) |
| `curated.submission.approve` | 관리자가 임베딩 실행 (생성된 `curatedId`·작성자 포함) |
| `curated.submission.reject` | 관리자가 거부 (사유 포함) |

관리 UI 조작 방법은 §7.6, 사용자 화면은 [USER_MANUAL.md §2.6](USER_MANUAL.md#26-지식-제안-청크-직접-추가) 참고.

---

### 6.10 청크 분할 전략 (크기 기준 병합 / 소제목 최대 분할)

`app.chunk-split-granular`(`CHUNK_SPLIT_GRANULAR`, 기본 `false`)로 청킹 방식을 고릅니다. **`/settings`에서 재기동 없이 전환**되며(§6.5), **적용 시점은 다음 인덱싱 또는 ↺ 재인덱싱**입니다.

| | `false` — 크기 기준 병합 (기본) | `true` — 소제목 최대 분할 |
|---|---|---|
| `MIN_CHUNK_SIZE` | 이 값보다 짧은 챕터를 인접 챕터와 묶어 청크를 채움 | **아예 보지 않음** — 짧은 소절도 독립 청크 |
| 병합 규칙 | 전방 챕터 병합 + 남은 작은 청크의 후방 병합 | 도입부 예외 하나뿐(아래) |
| PPTX/PDF | 짧은 슬라이드 병합 + 동일 헤딩 슬라이드 2장 병합 | 슬라이드를 **넘는** 병합 없음 — **1슬라이드 = 1청크** (슬라이드 내부는 합침, 아래 참고) |
| 표·코드 블록 | 경계 이동 폭이 `CHUNK_OVERLAP`에 묶임 | `CHUNK_SIZE`의 ±50%까지 이동해 통째로 유지 |
| 결과 | 청크 수 적음, 문맥 넓음 | 청크 수 많음, 조준 정확 |

**도입부 예외** — 최대 분할의 유일한 병합 규칙입니다. **제목 + 본문 2단위 이내**인 섹션("이 장에서는 …를 다룹니다" 같은 도입부)은 그것만 검색되면 답이 될 수 없으므로, 바로 아래 **하위** 챕터와 합쳐집니다.

- "2단위"는 `max(문장 종결부호 수, 빈 줄 아닌 줄 수)`입니다 — 종결부호 없는 3줄짜리 불릿 목록이 "2문장"으로 통과하는 것을 막기 위한 하한입니다.
- 본문에 **표 행이나 코드 펜스가 있으면 길이와 무관하게 도입부가 아닙니다**(그건 내용이고, 최대 분할은 바로 그걸 독립 청크로 남기려는 전략이므로).
- **하위 헤딩으로만** 내려갑니다. 형제·상위 헤딩을 만나면 멈추고, 한 단계 합칠 때마다 다시 판정하므로 `## A` → `### A-1` → `#### A-1-1` 같은 헤딩 사다리는 "아직 도입부인 동안만" 연쇄로 접힙니다.
- `CHUNK_SIZE`나 슬라이드 경계를 넘지 않습니다.

**PPTX/PDF에서 "최대 분할"의 의미** — 소제목 분할은 **적용되지 않습니다**. 이 형식들의 `##`/`###`는 실제 챕터 구조가 아니라 한 슬라이드의 제목/부제이고, 게다가 로더가 헤딩에서도 섹션을 끊기 때문에 제목 있는 슬라이드는 `"## 제목"` 섹션과 `"### 소제목 + 본문"` 섹션 두 개로 도착합니다. 이를 그대로 분할하면 **본문 없는 제목만의 청크**가 생기므로, 최대 분할에서도 같은 `page_or_slide` 안의 섹션은 항상 하나로 합칩니다(`joinSectionsWithinPage`). 최대 분할이 끄는 것은 **슬라이드를 넘는** 병합(짧은 슬라이드 묶기·동일 헤딩 2장 묶기)뿐이고, 그 결과가 "1슬라이드 = 1청크"입니다. 슬라이드 하나가 `CHUNK_SIZE`를 넘으면 두 전략 모두 그 안에서 슬라이딩 분할되는 것도 동일합니다(`page_or_slide`는 조각마다 유지되므로 인용 정확도는 그대로).

> **참고 — `CHUNK_OVERLAP=0`에서의 텍스트 유실 수정**: 슬라이딩 분할 뒤 `MIN_CHUNK_SIZE`보다 작은 꼬리 조각을 앞 청크에 붙일 때, 예전에는 이음매에서 **최대 `MIN_CHUNK_SIZE`(기본 500자)** 까지 일치하는 접미/접두사를 찾아 "중복"으로 보고 지웠습니다. 이 중복 제거는 `CHUNK_OVERLAP > 0`이라 조각이 실제로 겹쳐 잘렸을 때를 위한 것인데, `CHUNK_OVERLAP`의 기본값이 `0`으로 바뀌면서 조각이 서로 겹치지 않게 됐고, 그래서 반복적인 내용(같은 형식의 표 행·목록 항목 등)에서 **우연히 일치한 구간을 진짜 중복으로 오인해 본문이 사라지는** 경우가 있었습니다. 이제 실제 `CHUNK_OVERLAP` 값만큼만 중복을 제거하므로 `0`이면 그대로 이어 붙입니다. 영향받은 문서는 **↺ 재인덱싱하면 복구**됩니다(원본 MD는 손상되지 않았고, 벡터 스토어의 청크 텍스트만 짧게 저장돼 있었습니다). `CHUNK_OVERLAP`을 `0`이 아닌 값으로 쓰고 있었다면 해당 없습니다.

**표·코드 블록 보호**: 두 전략 모두 경계가 블록 안에 떨어지면 옮기려 시도하지만, 그 허용 폭이 다릅니다. 기본 전략은 `CHUNK_OVERLAP`을 그대로 쓰는데 **이 값의 기본이 `0`이라(§3.2 — 문서 내보내기 정확도 때문) 사실상 보호가 꺼져 있고**, 경계에 걸친 표·코드는 잘린 뒤 헤더 재주입/펜스 복구 마커로 수습됩니다. 최대 분할은 `CHUNK_SIZE`의 절반을 허용 폭으로 써서, 블록을 끝내기 위해 청크가 ~1.5×`CHUNK_SIZE`까지 커지거나 블록 앞에서 일찍 끊어 다음 청크가 블록으로 시작하게 합니다. 그보다 큰 블록은 여전히 잘립니다(복구 마커는 동일하게 붙음).

**두 전략을 비교하는 방법** — 문서마다 인덱싱 당시 전략이 그대로 남으므로 한 컬렉션에 두 전략이 공존할 수 있습니다:

1. `/settings`에서 **소제목 최대 분할**을 켠다 (재기동 불필요)
2. `/admin` 문서 레지스트리에서 비교할 문서의 **↺ 재인덱싱** 실행 — 그 문서만 새 전략으로 다시 잘림
3. 채팅으로 같은 질문을 던져 출처·답변 품질 비교
4. 되돌리려면 설정을 끄고 다시 ↺ 재인덱싱

> **주의 1**: 최대 분할은 청크 수가 늘어나므로 **인덱싱 시간·임베딩 호출·키워드 추출 LLM 호출이 함께 증가**합니다(청크당 비용이 곱해짐). 대량 문서에 일괄 적용하기 전에 문서 1~2건으로 먼저 재인덱싱해 시간을 재보세요. 검색 품질 변화를 수치로 보려면 §6.6 평가 하네스를 사용하세요.
>
> **주의 2**: `SEARCH_TOP_K`(기본 10)는 그대로인데 청크가 잘게 쪼개지면 한 번에 들어오는 총 컨텍스트 양이 줄어듭니다. 최대 분할로 바꾼 뒤 답변이 얕아지면 `SEARCH_TOP_K`를 함께 올리세요 — 둘 다 `/settings` 핫 수정 대상입니다.
>
> **주의 3**: 문서 내보내기(§6.8)의 재조립은 마커·헤딩 기반이라 두 전략 모두에서 동작하지만, 청크 수가 늘면 이어붙일 경계도 늘어납니다. `CHUNK_OVERLAP`이 `0`이면(기본) 유일한 휴리스틱 단계가 no-op이라 영향이 없습니다.

---

### 6.11 중복 질문 재사용 (추천·검증·무효화)

중복 질문이 많은 환경에서 응답 지연과 LLM 호출을 줄이기 위해, 채팅 입력 시 기존 질문 추천과 답변 재사용 기능을 제공합니다.

#### 동작 요약

- 추천 조회: `GET /api/v1/questions/suggest?q=...&limit=...` (서버는 항상 shared 기준 처리)
- 재사용 시도: `POST /api/v1/questions/reuse`
- 재사용 성공: 기존 답변을 새 turn으로 저장(`provider=db-reuse`, `reused_from_turn_id` 참조 저장)
- 재사용 실패: `fallback=true`와 사유를 반환, 클라이언트가 일반 질의 파이프라인으로 즉시 전환

추천 결과 품질을 위해 다음 필터를 함께 적용합니다.

- `direct_mode=1`인 질문은 `feedback='LIKE'`일 때만 추천/재사용 후보에 포함
- 질문 정규화(공백/대소문자) 기반 중복 제거
- **응답 모드가 `S`(간단히) 또는 `C`(응용)였던 turn은 후보에서 제외**됩니다. `C`는 "문서에서 찾아 달라"가 아니라 "만들어 달라"는 요청이라, 저장된 코드를 그대로 돌려주면 사용자가 요청한 바로 그 일을 하지 않는 셈이 됩니다(근거 청크가 그대로여도 마찬가지). 판정은 `ResponseMode.allowsReuse()`이고 기준값은 `conversation_turns.response_mode`입니다 — 값이 비어 있거나 옛 `M`/`L`이거나 알 수 없는 값이면 `N`으로 간주되어 **후보에 남습니다**

#### API 오류/폴백 응답 요약

| API | HTTP | 형태 | 운영 관점 처리 |
|---|---|---|---|
| `/api/v1/questions/suggest` | 200 | `[]` (빈 배열) | 입력 길이(2글자 미만) 또는 후보 없음. 장애가 아니라 정상 케이스 |
| `/api/v1/questions/reuse` | 200 | `{ "reused": false, "fallback": true, ... }` | 기능 실패가 아니라 검증 실패 분기. 클라이언트는 일반 질의 전환을 수행해야 정상 |
| 공통(예외 발생) | 4xx/5xx | RFC 9457 ProblemDetail + `errorCode` | [ERROR_CODES.md](ERROR_CODES.md) 기준으로 알람/대응 (`RAG-VAL-001`, `RAG-INT-001` 등) |

> `fallback=true`는 서버 에러가 아니라 데이터 신선도 보호(삭제/변경 감지) 정책이 작동한 결과입니다. 모니터링에서 오류율로 집계하지 않는 것을 권장합니다.

#### 저장 구조

- 테이블: `turn_source_ref`
- 저장 시점: 일반/스트리밍 답변이 turn으로 저장된 직후
- 내용: `turn_id`, `chunk_id`, `chunk_hash`, `status(active|inactive)` 등
- 목적: "당시 답변이 어떤 청크 집합을 근거로 했는지"를 고정 스냅샷으로 보존

`db-reuse` 저장은 답변 본문을 중복 저장하지 않고 참조로 기록합니다.

- `conversation_turns.reused_from_turn_id`: 원본 turn id 참조
- `conversation_turns.answer`: 빈 문자열 저장(중복 데이터 절감)
- 조회 시 `COALESCE(NULLIF(src.answer,''), NULLIF(t.answer,''), '참조 원문 삭제됨')`로 원본 답변 복원

원본 turn이 이후 삭제되면(예: 스레드 삭제), 답변 본문은 `'참조 원문 삭제됨'`으로 표시됩니다. 출처 미리보기도 원본 turn 기준으로 조회되며, 원본이 없으면 `'참조 원문 삭제됨'` 안내 항목(미리보기: "원본 대화가 삭제되어 출처 미리보기를 표시할 수 없습니다.")이 반환됩니다.

또한 스레드 삭제 시(`clearHistory`) `conversation_turns`/`turn_image_ref`와 함께 해당 스레드의 `turn_source_ref`도 삭제되어 orphan 참조가 남지 않도록 정리됩니다.

#### 유효성 검증 규칙

- 추천 항목 클릭 시 즉시 반환하지 않고, **반환 직전** `chunk_fts` 기준 현재 해시와 비교
- 실패 조건:
  - 청크가 사라짐(문서/청크 삭제, 재인덱싱 교체)
  - 청크 텍스트 해시 불일치(내용 변경)
- 실패하면 재사용을 중단하고 일반 질의로 전환

#### 무효화 전파 경로

- 문서 삭제(`deleteDocument`/`deleteArtifacts`) 시 해당 문서의 기존 `spring_doc_id` 전부 비활성화
- 동기화 삭제(sync step3)도 동일 경로를 타므로 자동 반영
- 문서 재인덱싱(`reindexFromMd`)은 old chunk 삭제 시점에 old id 묶음을 일괄 비활성화
- 관리자 청크 삭제(`/admin/chunks/{chunkId}`) 시 해당 id 비활성화
- 관리자 단건 청크 재인덱싱은 재인덱싱 전/후 해시 비교 후 **변경된 경우에만** 비활성화

#### 추천 품질 필터

- 지시어 위주 질문(예: "이거", "그거")은 추천에서 제외
- 단, 오류코드/파일명/경로/API명 등 구체 신호가 있으면 제외하지 않음

---

### 6.12 청크 오류 신고 처리 (사용자 신고 → 관리자 확인·수정)

채팅에서 출처 원문을 연 사용자가 **그 청크의 내용이 틀렸거나 오래됐다**고 알리는 경로입니다(§10.14). §6.9 지식 제안이 "코퍼스에 **넣는** 문"이라면 이쪽은 "**고치는** 문"입니다.

**신고는 아무것도 바꾸지 않습니다.** `chunk_report` 행은 검색·재사용 판정·벡터/FTS 어디에서도 읽히지 않습니다 — 반영은 관리자가 청크를 실제로 고칠 때 일어납니다. 자동 비활성화 임계값 같은 것은 **의도적으로 없습니다**(신고가 곧 삭제 버튼이 되면 §6.9 가 지키는 "코퍼스는 사람이 지킨다"와 방향이 어긋납니다).

**처리 절차** — `/admin` → **청크 오류 신고** 카드(펼칠 때 조회):

1. 목록의 **한 행은 신고 1건이 아니라 청크 1개**입니다(배지 숫자가 그 청크에 모인 신고 수). 헤더 배지와 카드 pill 도 **열린 신고를 가진 청크 수**를 셉니다 — 관리자가 할 일의 개수이기 때문입니다.
2. 행의 🚩 버튼을 누르면 그 청크의 신고가 **전부 한 화면에** 열립니다: 사유·코멘트·신고 시각·당시 질문, 그리고 **신고 시점 원문 스냅샷 ↔ 현재 내용** 비교와 "신고 이후 수정됨/삭제됨" 표시.
3. **수정은 「청크 편집 열기」** 로 기존 청크 편집 오프캔버스에서 합니다(§7.1) — 신고 패널에는 편집기가 없습니다. 편집 후 「이 청크만 재인덱싱」까지 해야 검색에 반영됩니다(§7.2-bis). 큐레이션 Q&A 청크는 §6.7 카드에서 고칩니다.
4. 조치는 **청크 단위**입니다: 「처리 완료」 또는 「반려」(사유 필수) 한 번으로 그 청크의 열린 신고가 전부 닫히며, 보던 사이에 도착한 신고도 함께 닫힙니다(같은 청크에 대한 같은 조치이므로).

**운영 시 알아둘 것**

- **신고자에게 결과를 알리는 알림은 없습니다.** 반려 사유는 감사 로그와 이력에만 남습니다.
- **`/settings` 의 출처 미리보기를 끄면 신고 경로도 함께 닫힙니다** — 신고 버튼이 출처 원문 팝업 안에 있고, 그 토글이 팝업 자체를 열지 않게 하기 때문입니다. 이미 사라진 청크(원문을 불러오지 못한 경우)에서도 버튼이 숨겨집니다.
- **중복 방지 키는 (청크, 신고자, 대화)** 입니다. `AUTH_GUEST_IDENTITY=shared` 배포에서는 모든 방문자의 `userId` 가 같으므로, 대화를 키에 넣지 않으면 청크당 한 명만 신고할 수 있게 됩니다. 처리 완료된 뒤에는 같은 사람이 다시 신고할 수 있습니다(고쳤는데 또 틀릴 수 있으므로).
- **사용자당 건수 상한은 없습니다.** 남용은 위 중복 방지와 `RATE_LIMIT` 기본 버킷이 막습니다.
- **"현재 내용"이 원문이 아닐 수 있습니다.** sqlite-vec 백엔드에서는 청크 원문을, 그렇지 않은 배포에서는 검색용 파생 텍스트(맥락 헤더 + 정규화 본문)를 보여주며 화면에 `(검색용 파생 텍스트)` 라고 표시됩니다 — 스냅샷과 글자가 달라 보여도 그 자체가 수정의 증거는 아닙니다.
- **감사 로그**: `chunk.report`(접수) · `chunk.report.resolve` · `chunk.report.reject`(닫은 건수 포함).
- **저장 위치**: `chunk_report`(신고 내용 + 신고 시점 원문·질문 스냅샷) — 다른 운영 테이블과 같은 파일입니다([§6.3.1](#631-sqlite-파일별-테이블-구성): 분리를 켠 배포에서는 벡터 DB 파일). 문서나 대화가 지워져도 스냅샷은 남습니다 — 지워진 뒤에는 "무엇이 틀렸다는 것인지"를 그 복사본으로만 알 수 있기 때문입니다.

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
| 청크 조회 | 컬렉션(또는 버전)·문서(docId)별 청크 페이지네이션 — 페이지당 20/50/100건 선택 가능(기본 20건, `AdminController.chunks()`의 `limit` 기본값). ID·텍스트 미리보기·크기·파일명·페이지/슬라이드·**챕터**·키워드·작업 컬럼. 챕터 컬럼은 `MetaKey.CHAPTER_NO`(H2~H6 헤딩 기반 계층 번호)를 보여주며 "0"(실제 챕터 없음)이면 빈 칸으로 표시 |
| 청크 편집 | 텍스트·메타데이터 수정 (원본 임베딩 유지 — 벡터 재계산 안 함) |
| 청크 재인덱싱 | 편집 패널의 **이 청크만 재인덱싱** 버튼(`AdminService.reindexChunk()`) — 저장된 텍스트 기준으로 그 청크만 재임베딩 + FTS 재색인(id 보존, upsert). "키워드 재생성" 체크 시 `KeywordExtractor`를 그 청크에만 다시 실행(LLM 1회) |
| 청크 삭제 | 개별 청크 즉시 제거. sqlite-vec는 `vec_document_chunks`+`vec_embeddings` 두 테이블 동기 삭제 |
| 문서 레지스트리 | 인덱싱된 전체 문서 목록 + 문서별 청크 바로 조회 (백엔드 무관, SQLite `doc_registry` 기반) |
| MD 재인덱싱 (↺ 버튼) | `{docId}_corrected.md`(없으면 `{docId}.md`)를 읽어 청크 재생성·재인덱싱 — DOCX·TXT·PPTX·PDF(스캔 아님) 지원, 원본 재업로드 불필요 (스캔 PDF는 MD 파일이 없어 미지원) |

> **청크 정렬**: 두 백엔드 모두 `doc_id` → `chunk_index`(인덱싱 시 각 청크에 부여되는 0-based 문서 내 위치, `MetaKey.CHUNK_INDEX`) 순으로 정렬됩니다 — 청크 id가 아니라 문서 원본 내용 순서 그대로 표시됩니다. sqlite-vec는 `ORDER BY doc_id, CAST(json_extract(metadata, '$.chunk_index') AS INTEGER), spring_doc_id`로 DB에서 직접 정렬합니다. Chroma의 `get()` API는 서버 측 ORDER BY를 지원하지 않으므로, 매치되는 청크 전체를 최대 `AdminService.CHUNK_FETCH_CAP`(10,000건)까지 가져온 뒤 애플리케이션(Java)에서 정렬·페이지네이션합니다 — 컬렉션(또는 docId 필터 결과)이 이 상한을 넘으면 뒤쪽 청크는 조회되지 않습니다.

> **넓은 화면 미리보기**: 청크 편집 오프캔버스를 열 때 창 폭이 충분히 넓으면(오프캔버스 기본 폭의 2배 이상) 왼쪽에 마크다운·표·이미지 미리보기, 오른쪽에 기존 편집 입력창을 나란히 표시합니다(`admin.html`, `renderChunkPreview()`). 별도 API 호출 없이 이미 받아온 `GET /admin/chunks/{chunkId}/detail` 응답을 클라이언트에서 marked.js + DOMPurify + hljs로 렌더링하는 순수 프런트엔드 기능이라 서버 부하는 없습니다. 판정은 오프캔버스를 여는 시점 1회이며, 좁은 화면(모바일 등)에서는 기존과 동일하게 편집 입력창만 표시됩니다 — 상세는 [UI.md §3.4](UI.md#34-벡터-스토어-관리-admincontroller) 참고.

### 7.2 MD 재인덱싱 흐름

1. `data/converted/{docId}_corrected.md` 파일을 텍스트 에디터로 직접 수정
2. 벡터 스토어 관리 페이지 문서 레지스트리에서 해당 문서의 ↺ 버튼 클릭
3. 결정적(비-LLM) MD 정리 — 존재하지 않는 이미지 마커 제거 → 소제목 번호 재검증 → 마크다운 후처리 (§7.3 참고, 변경 있으면 MD 파일에도 반영)
4. 정리된 MD 기준으로 청크 분할 → 키워드 추출(LLM) → 활성 백엔드에 재등록
5. 신규 청크 저장이 끝난 뒤에야 기존 벡터 청크 삭제 — 활성 백엔드(chroma 또는 sqlite-vec) (MD 파일·이미지 보존, 저장 실패 시 기존 데이터 보존)

> **API 직접 호출**: `POST /admin/documents/{docId}/reindex`

### 7.2-bis 청크 단위 재인덱싱 (`POST /admin/chunks/{chunkId}/reindex`)

문서 전체 재인덱싱 없이 **청크 하나만** 재임베딩·FTS 재색인합니다. Body: `{"regenerateKeywords": true|false}`(생략 시 `false`).

- **동작**: 벡터 스토어 id를 그대로 유지한 채 upsert합니다(Chroma: `upsertEmbeddings`가 같은 id를 덮어씀 / sqlite-vec: `add()`가 같은 id를 delete-then-insert) — 새 청크가 생기는 게 아니라 기존 청크가 그 자리에서 갱신됩니다.
- **`regenerateKeywords=false`(기본)**: 현재 저장된 `excerpt_keywords` 등 메타데이터를 그대로 두고, 현재 텍스트로만 재임베딩 + FTS 재색인합니다. LLM 호출 없음, 즉시 처리. 단, `chunk_context`(LLM이 생성한 1~2문장 맥락)는 §10.1 설계상 애초에 영속 저장되지 않으므로 "그대로 유지"할 방법이 없고, 이 경로에서는 구조적 맥락(`"{파일명} > {헤딩}"`)만으로 임베딩/FTS 입력이 구성됩니다.
- **`regenerateKeywords=true`**: 이 청크에 한해 `KeywordExtractor`를 다시 실행해(LLM 1회, TF 타임아웃 폴백 동일 적용) `excerpt_keywords`/`chunk_context`를 재생성한 뒤 그 결과로 재임베딩·재색인합니다. 문서 전체 ↺ 재인덱싱과 동일한 품질을 청크 단위로 얻을 수 있습니다.
- **동기 처리**: 청크 1개 단위라 문서 재인덱싱(SSE 진행률 추적)과 달리 응답이 올 때까지 대기합니다.
- 존재하지 않는 청크이거나 임베딩 API 호출이 실패하면 404를 반환하고, 이 경우 FTS 재색인은 시도하지 않습니다(부분 반영 방지).

### 7.3 주의사항

- **임베딩 미갱신 (청크 편집만)**: 청크 텍스트를 편집 패널의 "저장" 버튼으로만 수정하면 벡터 임베딩과 FTS 키워드 인덱스가 재계산되지 않습니다. 검색에도 반영하려면 위 §7.2-bis "이 청크만 재인덱싱" 버튼을 사용하거나, 문서 전체를 갱신하려면 MD 파일 수정 후 ↺ 재인덱싱을 사용하세요.
- **MD 재인덱싱 대상**: DOCX·TXT·PPTX·PDF(스캔 아님) 업로드 시 생성된 `_corrected.md` 파일이 없으면 `{docId}.md` 원본으로 fallback됩니다. 스캔 PDF처럼 MD 파일 자체가 없는 문서는 재인덱싱 불가 (에러 메시지 표시).
- **소제목 번호 재검증**: 재인덱싱 시 저장된 MD에 이미 번호 매겨진 헤딩이 있으면 현재 헤딩 구조 기준으로 다시 계산해 파일에도 반영합니다(PPTX 제외 — [§3.3 소제목 숫자 생성](#33-applicationproperties-전용-설정) 참고). 번호가 원래 없던 문서에는 새로 번호를 붙이지 않습니다.
- **마크다운 후처리 재적용**: 재인덱싱 시 결정적(비-LLM) 정리도 다시 적용됩니다 — `[DOCUMENT]` 마커·내용 없는 `-` 줄 제거, 코드 블록·표 앞뒤 빈 줄 보장, 연속 빈 줄을 1개로 축소(모든 형식 대상, PPTX 포함). 코드펜스 언어 보정(`fixClosingFences`/`normalizeCodeBlocks`)은 재인덱싱에 **포함되지 않습니다** — MD를 직접 편집한 뒤 재인덱싱하면 코드 블록 안의 의도된 빈 줄이 지워지거나 펜스 태그가 잘못 벗겨질 위험이 있어, 매번 감수하지 않고 필요할 때(재업로드)만 적용되도록 남겨둔 설계입니다. 상세는 [PIPELINE.md §6.4](PIPELINE.md#64-문서-타입별-처리-상세) 참고.
- **청크 단독 삭제 vs. 문서 삭제**: 청크를 개별 삭제해도 SQLite `doc_registry` 테이블의 레지스트리 항목은 남습니다. 문서 전체 제거는 Documents 페이지 또는 `DELETE /api/v1/documents/{docId}`를 사용하세요.
- **인덱싱 중 청킹/임베딩 실패 시 partial 항목**: MD 변환+교정(이미지 분석 포함)까지는 성공했지만 이후 청킹·키워드추출·임베딩 저장 단계에서 실패한 문서는 청크 수 `0`인 상태로 문서 레지스트리·`/admin` 목록에 나타납니다. 이 상태에서 ↺ 버튼을 누르면 이미지 분석/MD 교정을 다시 거치지 않고 저장된 MD 파일 기준으로 재시도됩니다(§7.2). 청크 수 `0`인 상태로는 태그 편집이 "문서의 색인 데이터를 찾을 수 없습니다" 에러로 거부되므로, 우선 ↺로 인덱싱을 완료한 뒤 태그를 편집하세요. 이 partial 항목은 `POST /api/v1/documents/sync` 재동기화 대상에서도 "이미 색인됨"으로 취급되지 않으므로 다음 동기화에서 정상적으로 다시 시도됩니다.
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

### 7.5 큐레이션 Q&A 관리 (§10.10)

`/admin` 페이지 **최하단**에 **큐레이션 Q&A** 카드가 있습니다 — 좋아요로 승격된 질문·답변 목록(질문·답변 미리보기·등록일)을 최신순으로 보여줍니다. 페이지당 20/50/100건 중 선택, 이전/다음으로 이동합니다.

카드 순서는 **지식 제안 검토(§7.6) → 큐레이션 Q&A**입니다. 앞쪽은 관리자의 조치를 기다리는 대기열이고 이 카드는 이미 반영된 것을 확인·회수하는 용도라 열어볼 일이 드물어 맨 아래에 둡니다.

카드는 `<details>` 요소로 구현되어 기본적으로 접혀 있으며, 펼칠 때만(`GET /admin/curated`, HTMX `toggle[this.open] once` 트리거) 목록을 서버에서 조회합니다 — `AdminController.adminPage()`는 더 이상 `curatedQaService.listActive()`를 즉시 호출하지 않으므로, `/admin` 페이지를 열기만 해서는 이 DB 조회가 발생하지 않습니다. 카드를 한 번 펼치면 그 세션에서는 다시 접었다 펴도 재조회되지 않습니다(새로고침하면 다시 접힌 상태로 초기화).

| 기능 | 방법 |
|------|------|
| 편집 | 행의 연필 아이콘 → 오프캔버스에서 답변 텍스트 수정 후 저장 — **저장 시 자동으로 재임베딩됨**(청크 편집과 달리 임베딩이 갱신됨에 유의) |
| 강제 삭제 | 행의 휴지통 아이콘 → 즉시 비활성화 + 벡터 스토어에서 제거. **좋아요를 누른 사용자의 동의와 무관하게** 관리자가 제거할 수 있는 모더레이션 경로입니다 |

편집 오프캔버스는 **넓은 화면(뷰포트 폭이 기준값의 2배 이상)에서 좌측에 라이브 미리보기 컬럼**을 함께 띄웁니다 — 청크 편집(§7.3)과 완전히 같은 조건·같은 렌더러라 표·코드블록·이미지 마커가 실제로 어떻게 보이는지 입력하는 즉시 확인할 수 있습니다. 큐레이션 답변의 마크다운은 그대로 답변 프롬프트의 검색 근거로 들어가므로, 서식이 깨진 채 저장되는 것을 막는 것이 목적입니다. 좁은 화면·모바일은 기존 단일 컬럼 그대로입니다.

- API: `GET /admin/curated/{id}/detail`(조회), `POST /admin/curated/{id}`(수정, body `{"answer":"..."}`), `DELETE /admin/curated/{id}`(강제 삭제) — 접근 제어는 `/admin/**`와 동일(§7 상단 참고).
- 강제 삭제는 좋아요 취소(사용자 경로)와 **같은 내부 매커니즘**(비활성화+de-index)을 쓰지만, 소유권 검증을 거치지 않는 별도 인가 경로입니다.
- 동작 원리·좋아요 승격 흐름 전체는 [§6.7](#67-큐레이션-qa-공유-지식-축-1010--1011) 참고.
- 사용자가 직접 등록한 제안이 승인되면 이 카드에 **함께** 나타납니다(내부적으로 `origin='manual'`) — 편집·강제 삭제 방법은 좋아요 항목과 동일합니다. 여기서 강제 삭제하면 §7.6 목록에서는 "회수됨"으로 표시되며, 같은 제안의 나머지 청크도 함께 내려갑니다(전부/전무).

---

### 7.6 지식 제안 검토 (§6.9)

`/admin` 페이지 하단 **지식 제안 검토** 카드입니다. 큐레이션 Q&A 카드와 같은 `<details>` 지연 로딩 구조로, 펼칠 때만(`GET /admin/submissions`) 목록을 조회합니다. 카드 제목 옆에는 검토 대기 건수가 빨간 배지로 표시됩니다(대기 0건이면 감춰짐).

기본 필터는 **검토 대기**(`status=pending`) — 요청 그대로 "아직 등록되지 않은 제안"만 보여줍니다. 드롭다운에서 등록 완료/반려/철회됨/전체로 바꿀 수 있습니다.

| 기능 | 방법 |
|------|------|
| 내용 확인 | 행의 아이콘 → 오른쪽 오프캔버스에 제목·본문 **전문** 표시(작성자 id 포함). **미리보기** 탭으로 넘기면 본문 이미지가 실제 그림으로 렌더됨 |
| 수정 후 등록 | 같은 패널에서 제목·본문을 고친 뒤 **임베딩 실행** — 고친 내용이 `curated_qa`와 게시글 양쪽에 저장되어 작성자에게도 색인된 내용이 그대로 보임 |
| 거부 | **거부** → 사유 입력(필수, 작성자에게 전문 노출) |

- API: `GET /admin/submissions?status=&offset=&limit=`(목록 프래그먼트), `GET /admin/submissions/{id}/detail`, `POST /admin/submissions/{id}/approve`(body `{"title":"...","body":"..."}`, 생략 시 작성자 원문 사용), `POST /admin/submissions/{id}/reject`(body `{"reason":"..."}`), `GET /admin/submissions/pending-count`.
- **임베딩 실행 성공 = "색인 항목 생성됨"까지**입니다. 실제 임베딩 호출은 백그라운드 가상 스레드에서 진행되므로 응답 시점에는 성공 여부를 알 수 없습니다 — 실패하면 목록의 제목 앞에 ⚠ 배지가 붙고, 작성자 화면에도 안내가 표시됩니다. 복구는 §7.5 큐레이션 Q&A 카드에서 본문을 줄여 저장하면 됩니다(저장 시 자동 재임베딩).
- **예외: 본문 이미지의 Vision 설명 생성은 이 요청 안에서 동기로 끝납니다**(§6.9 "본문 이미지"). 임베딩과 달리 배경으로 미룰 수 없어서 — 설명이 임베딩되는 텍스트의 일부여야 그림 내용이 검색에 걸립니다. 그래서 이미지가 있는 제안은 **임베딩 실행 응답 자체가 느립니다**(로컬 Vision 모델 기준 장당 수 초). 버튼이 잠기고 "등록 중..." 스피너로 바뀌며, 그동안 창을 닫아도 서버 작업은 끝나지만 결과 토스트는 못 봅니다 — 목록을 새로 고쳐 상태를 확인하세요.
- 승인/거부는 `status='pending'`을 조건으로 하는 compare-and-set이라, 관리자 두 명이 동시에 눌러도 색인 항목은 하나만 생성됩니다(진 쪽은 409 + "이미 처리된 제안입니다" 안내, 방금 만든 항목은 자동 회수). 버튼 잠금은 같은 사람이 느린 승인 중에 두 번 눌러 이 경합을 자초하지 않게 하는 1차 방어입니다.
- 이미지 개수 상한(§6.9, 기본 10장)을 넘은 본문은 **400 + 사유 메시지**로 거부되고 그 문구가 그대로 토스트에 표시됩니다 — 본문에서 `[이미지: ...]` 줄을 줄인 뒤 다시 실행하세요.
- 이미 처리된 제안은 읽기 전용으로 열립니다 — 등록 후 마음이 바뀌면 §7.5에서 삭제하세요.
- ⚠️ **검토 시 이미지도 함께 확인하세요.** 승인은 글뿐 아니라 그 이미지, 그리고 이미지에서 자동 생성된 설명 문장까지 검색 근거로 나가는 것을 허가하는 행위입니다.

### 7.7 검색 진단 수치 — 검색 튜닝 근거 보기

`/admin` 최하단의 **검색 진단 수치** 카드(펼칠 때만 조회)에서 최근 질의들이 실제로 어떻게 검색됐는지 turn 단위로 확인합니다. **읽기 전용**이며, 여기서 얻은 판단으로 조정할 값은 전부 [§6.5 설정 페이지](#65-설정-페이지-settings--llmrag-옵션-조회핫-수정)에 있습니다.

| 열 | 의미 |
|---|---|
| 최고 유사도 | 그 턴에서 가장 가까웠던 청크의 코사인 유사도 — "검색이 근접한 걸 찾긴 했는가" |
| 사용/검색 | 답변에 실제로 반영된 출처 수 / 검색된 출처 수. 절반 이상이 미반영이면 ⚠ |
| 상세 | 출처별 유사도·검색기여·축별 순위·응답참여 4개 수치 |

**읽는 순서**:

1. **최고 유사도가 지속적으로 낮다**(예: 0.3 이하) → 검색이 애초에 못 찾고 있습니다. 임베딩 모델·청크 크기·`SEARCH_SIMILARITY_THRESHOLD`를 보세요.
2. **사용/검색에 ⚠가 잦다**(`2/8` 같은) → 검색은 되는데 답변이 안 씁니다. `SEARCH_TOP_K`가 과한지, 또는 프롬프트가 문서를 충분히 활용하지 않는지 의심합니다. 컨텍스트만 낭비하고 있는 상태입니다.
3. **축별 순위가 한쪽으로 쏠린다** → `vec:12, bm25:1`처럼 키워드 축이 계속 끌어올리고 있다면 `SEARCH_RRF_KEYWORD_WEIGHT`를, 반대로 BM25가 전혀 기여하지 않으면 `SEARCH_HYBRID_ENABLED`와 2글자 질의 문제(§8 참고)를 확인하세요.

#### 검색기여 읽는 법

**정의**: 그 청크의 가중 RRF 점수를, **답변 노드에 실제로 전달된 문서들의 점수 합**으로 나눈 비율입니다. 후보 풀이 아니라 최종 컷 기준이라, 한 턴의 출처를 모두 더하면 항상 100%가 됩니다.

```
rrfScore(문서) = Σ  가중치 ÷ (그 축에서의 순위 + k)      ← k = SEARCH_RRF_K, 기본 60
                 축

검색기여 = rrfScore ÷ Σ(최종 컷 문서들의 rrfScore)
```

가중치는 벡터축 `1/축개수`(그룹 정규화), BM25 `SEARCH_RRF_KEYWORD_WEIGHT`(0.5), 큐레이션 `SEARCH_CURATED_QA_WEIGHT`(1.0)입니다 — 큐레이션 축은 벡터 축 그룹과 **동등**에서 시작합니다.

**① 값이 다 비슷한 것이 정상입니다.** k=60이 순위 차이를 크게 눌러 줍니다. 벡터 축 하나에만 걸린 문서 8개라면:

| 순위 | rrfScore | 검색기여 |
|---|---|---|
| 1위 | 1/61 = 0.01639 | **13.2%** |
| 2위 | 1/62 = 0.01613 | 13.0% |
| … | … | … |
| 8위 | 1/68 = 0.01471 | **11.8%** |

1위와 8위가 13.2% 대 11.8%입니다. 이는 RRF의 설계 의도입니다 — 축마다 점수 체계가 달라(코사인 거리 vs BM25 점수) 직접 비교가 불가능하므로 순위만 쓰고, k로 상위권 쏠림을 완화합니다. **따라서 "얼마나 가까운가"를 알고 싶으면 이 값이 아니라 `유사도`를 보셔야 합니다.**

**② 값이 벌어지는 이유는 순위가 아니라 축 간 합의입니다.** 여러 축이 같은 문서를 지목하면 점수가 더해집니다. 짧은 질의(멀티쿼리 확장 생략)에 하이브리드 검색이 켜진 경우의 예:

| 문서 | 걸린 축 | rrfScore | 검색기여 |
|---|---|---|---|
| A | `vec:1, bm25:2` | 1/61 + 1/62 | **33.7%** |
| D | `bm25:1` | 1/61 | 17.0% |
| B | `vec:2` | 1/62 | 16.7% |
| C | `vec:3` | 1/63 | 16.4% |
| E | `vec:4` | 1/64 | 16.2% |

A가 나머지의 약 2배입니다. 즉 **검색기여가 눈에 띄게 높다 = 의미 검색과 키워드 검색이 같은 문서를 지목했다**는 뜻이고, 반대로 **전부 11~13%대로 고르다 = 벡터 축 하나만 일하고 있다**는 신호입니다(하이브리드가 꺼졌거나, 2글자 질의라 BM25가 기여하지 못하는 경우 — §8 참고).

**③ 멀티쿼리 변형 여러 개에 걸려도 배수가 되지는 않습니다.** 벡터 축은 `1/축개수`로 그룹 정규화되므로, 3개 확장 질의 모두에서 1위인 문서는 `3 × (1/3) ÷ 61 = 1/61`로 단일 축 1위와 같은 점수입니다. 이는 의도된 것으로, 그러지 않으면 멀티쿼리 축이 2~3표를 갖게 되어 BM25 축이 구조적으로 밀립니다. **가산이 실제로 일어나는 것은 서로 다른 종류의 축(벡터 그룹 / BM25 / 큐레이션 / 지식 제안) 사이에서입니다.**

> 값이 `-`인 칸은 0이 아니라 **측정 안 됨**입니다(벡터 축에 걸리지 않은 청크, 쿼리 확장 실패로 폴백된 턴 등).
>
> 이 패널은 **표시 토글(`ui.retrieval-metrics-enabled`)과 무관하게** 채워집니다 — 수치 기록은 항상 이뤄지고 토글은 채팅 화면 표시만 제어합니다. 기록이 없는 턴(meta·Direct·DB 재사용)은 애초에 목록에 오르지 않습니다.
>
> ⚠️ 이 패널은 **모든 사용자의 질문**을 보여줍니다(배포 전체의 검색 동작을 보는 운영자 뷰). `/admin/**`의 관리자 게이트가 유일한 접근 통제입니다.

---

### 7.8 키워드 축 가중치를 0.5로 두는 이유 (한/영 혼재 코퍼스)

`SEARCH_RRF_KEYWORD_WEIGHT`의 기본값은 `0.5`입니다 — 정규화된 벡터 그룹과 동등 비중인 `1.0`의 절반입니다. BM25 축을 끄자는 뜻이 아니라, **BM25가 할 수 있는 일과 없는 일이 비대칭**이기 때문입니다.

**BM25 축이 구조적으로 할 수 없는 일**: `chunk_fts`는 `tokenize='trigram'`이라 같은 문자 체계 안에서만 동작하는 리터럴 매처입니다. 한글 질문과 영문으로 쓰인 청크는 공유 trigram이 **0개**라, 그 청크는 답을 담고 있어도 이 축에서 **아예 검출되지 않습니다**(측정 확인). 반대 방향(영문 질문 → 한글 청크)도 같습니다.

**그런데도 순위에는 개입합니다**: "풀", "크기", "설정" 같은 흔한 토큰 하나만 겹쳐도 무관한 청크가 이 축에서 1~2위를 차지합니다.

```
질문: 디비 커넥션 풀 크기 기본값이 얼마이고 어디서 바꾸나요?  (정답은 영문 청크에만 존재)
  벡터축 정답 순위: [3, 4, 1]      ← 영문 확장 변형 축에서는 1위
  BM25축 정답 순위: 미검출          ← 한글 질문 → 영문 청크는 trigram 0개
  BM25축 상위:      캐시풀, 스레드풀   ← "풀"·"크기"만 겹친 무관 청크
```

의미 검색이 못 미치는 자리를 메우라고 둔 축이 **한/영이 갈리는 순간 정확히 반대로 작동**하므로, 정규화된 벡터 그룹과 동등한 표를 주지 않는 것이 기본값의 취지입니다. 정확 매칭 가치(파일명, 오류코드, API명, 설정 키처럼 임베딩이 약한 문자열)는 절반 가중치로도 그대로 남습니다.

#### 다만 이 손잡이의 실제 영향력은 작습니다 (실측)

한/영 혼재 코퍼스 2종·질문 5개에서 가중치를 바꿔 가며 정답 청크의 최종 순위를 측정한 결과, **1.0에서 0.3까지 낮춰도 순위가 하나도 바뀌지 않았습니다.**

| 코퍼스 / 질문 | w=1.0 | w=0.7 | w=0.5 | w=0.3 |
|---|---|---|---|---|
| B — 디비 커넥션 풀 크기 기본값…? | 3위 | 3위 | 3위 | 3위 |
| B — 디비 연결 개수 제한을 늘리려면? | 2위 | 2위 | 2위 | 2위 |
| A — 디비 접속 설정은 어떻게? | 4위 | 4위 | 4위 | 4위 |
| A — 디비 커넥션 풀 크기는 어디서? | 1위 | 1위 | 1위 | 1위 |
| A — DB 접속 정보는 어느 파일에? | 2위 | 2위 | 2위 | 2위 |

이유는 RRF 자체에 있습니다. 기여도가 `w/(rank+1+k)`이고 `k=60`이라 **어느 축이든 1위가 줄 수 있는 최대치가 `w/61`**입니다. `w`를 1.0에서 0.5로 내려도 청크 하나의 총점은 최대 **0.008** 움직이는데, 두 축 모두에 걸린 청크(≈0.033)와 한 축에만 걸린 청크(≈0.016)의 간격이 그보다 크기 때문에 순서가 뒤집히지 않습니다.

더 결정적인 것은, 목차·변경이력처럼 질문 단어를 글자 그대로 잔뜩 담은 청크를 넣어 BM25 오탐을 일부러 만든 코퍼스에서도 결과가 같았다는 점입니다 — 그 청크들은 **벡터 축에서도 1~2위**였습니다. 즉 키워드 스터핑 청크를 끌어올리는 주범은 BM25가 아니라 임베딩 모델 쪽입니다.

> **검색 결과가 "너무 많다"고 느껴질 때 이 손잡이를 먼저 잡지 마세요.** 위 실측대로 이 값은 최종 순위를 거의 바꾸지 못합니다. 실제로 결과 수를 정하는 것은 `SEARCH_TOP_K`이고, 관련 없는 꼬리를 잘라내는 것은 `SEARCH_SIMILARITY_THRESHOLD`(기본 0.3)입니다. 게다가 그 임계값은 **벡터 축에만** 걸리므로 올릴수록 BM25 축의 상대 비중이 자동으로 커집니다 — 임계값과 이 가중치를 **같은 단계에 함께 올리면** 두 변화가 서로를 상쇄하거나 증폭해 무엇이 효과였는지 알 수 없게 됩니다. 한 번에 하나씩 바꾸세요.
>
> **그래서 이 값은 "안전한 기본값"이지 "한/영 문제의 해결책"이 아닙니다.** 한/영 표기 차이를 실제로 줄이는 수단은 (1) `prompt.retrieval.expansion`의 표기 변형 규칙 — 확장 변형은 **벡터 축에만** 투입되고 BM25 축(`RetrievalService.execute()`의 `keywordF`)은 여전히 원본 질문만 검색한다, (2) 확장 변형을 BM25 축에도 투입(코드 변경 — 실측 1건에서 2위→1위), (3) 색인 시 핵심 용어 한/영 병기(`KeywordExtractor`, 전체 재인덱싱 필요) 쪽입니다.

**언제 다시 올리나**: 코퍼스가 **단일 언어**이고 질문도 문서와 같은 표기를 쓰며, 파일명·오류코드 같은 정확 매칭이 의미 검색보다 중요하다면 `1.0`으로 되돌릴 만합니다. `/settings`에서 핫 수정되므로 재기동 없이 바꿔 가며 [§7.7](#77-검색-진단-수치--검색-튜닝-근거-보기) 패널의 `axisRanks`로 확인하세요.

---

### 7.9 대화 목록 — 전 사용자 대화 조회·삭제 (§6.25)

`/admin` 하단의 **대화 목록** 카드(검색 진단 수치 카드 바로 위)는 배포 전체의 대화를 한 화면에서 보여줍니다. 다른 패널과 같은 지연 로딩이라 카드를 펼칠 때만 조회합니다.

**열 읽는 법**

| 열 | 의미 |
|---|---|
| 최종 활동 | `thread_meta.updated_at`. 제목 자동 생성·수동 변경도 이 값을 갱신하므로 엄밀한 "마지막 질문 시각"은 아닙니다(정확한 값은 상세의 턴 시각) |
| 진단 | 검색이 실제로 돌아 진단 수치가 남은 턴 수. 턴 수와 크게 벌어지면 ⚠ — 재사용·Direct 위주의 대화라는 뜻입니다 |
| 재사용함 | 이 대화의 턴이 **과거 답변을 재사용한** 횟수 |
| 재사용됨 | **이 대화의 답변이** 다른 턴에서 재사용된 횟수. 0보다 크면 ◆ — 삭제 시 그 턴들이 전부 "참조 원문 삭제됨"이 됩니다. **삭제를 판단할 때 봐야 할 값은 이쪽입니다** |

**요약 스트립**은 배포 전체 기준이라 아래 목록을 걸러도 움직이지 않습니다. 여기 `⚠ 소속 대화 없는 턴 N건`이 뜨면 `thread_meta` 행 없이 남은 턴이 있다는 뜻입니다 — 목록 쿼리가 구조적으로 볼 수 없는 상태라 숫자로만 알립니다. 정상 운영에서는 0이어야 하며, 0이 아니면 과거의 삭제나 쓰기가 중간에 끊겼을 가능성이 있습니다(기능에는 영향이 없고, 검색 진단 수치 패널에는 그 턴들도 계속 나타납니다).

> **`AUTH_GUEST_IDENTITY=shared`(기본)면 목록이 사용자 한 명으로 뭉칩니다** — 전 방문자가 하나의 게스트 id를 공유하기 때문이며, 패널이 그 이유를 배너로 밝힙니다. 방문자별로 나누려면 `hybrid`를 권장합니다(§9.4.3).

**대화 삭제**

- 삭제는 되돌릴 수 없고 **다른 사용자의 대화에까지 닿습니다.** 확인 대화상자의 숫자는 화면의 행이 아니라 **클릭 시점에 서버에서 다시 읽습니다** — 패널을 열어둔 지 오래됐을 수 있기 때문입니다.
- 지워지는 것: `conversation_turns`·`turn_source_ref`·`turn_image_ref`·`thread_meta`, 그리고 **그 대화에서 좋아요로 승격된 큐레이션 Q&A 행과 벡터**. 마지막 항목이 없으면 대화가 사라진 뒤에도 그 답변이 검색 근거로 계속 쓰입니다(§6.7). 승인된 **지식 제안**(`origin='manual'`)은 대화에 속하지 않으므로 영향받지 않습니다.
- 그 대화의 **검색 진단 수치도 함께 사라집니다** — 튜닝의 관측 표본이 그만큼 줄어듭니다. 확인 문구에 함께 표시됩니다.
- 감사 로그에 `admin.thread.delete`(소유자·턴 수·회수된 큐레이션 수)가 남습니다.
- **일괄 삭제 기능은 없습니다**(의도적).

**답변 원문 열람 — 기록이 남습니다**

- 목록과 상세에는 질문 미리보기까지만 나오고 **답변 전문은 실리지 않습니다**(응답 데이터에 필드 자체가 없습니다).
- 상세의 **원문** 버튼을 눌러야 질문·답변 전문이 열리며, **그 호출은 `admin.thread.read` 감사 이벤트**(읽은 관리자·소유자·턴 id)를 남깁니다. 존재하지 않는 턴은 404이고 열람으로 기록되지 않습니다.
- 즉 "관리자가 남의 대화를 읽었는가"는 `data/audit/audit.log`에서 `admin.thread.read`로 확인할 수 있습니다.

**검색 진단 수치와의 연결**: 행의 **진단** 버튼은 진단 패널을 그 대화로 좁혀 엽니다(진단이 0건이면 비활성). 반대로 진단 패널의 `대화` 열을 누르면 그 대화로 좁혀집니다. 상세의 턴별 **출처** 버튼은 진단 패널의 **상세**와 같은 표를 보여줍니다.

---

## 8. 문제 해결

### 애플리케이션이 시작되지 않음

```bash
# 시작 로그 확인
mvn spring-boot:run 2>&1 | head -80

# 헬스 체크
curl http://localhost:8080/api/v1/health

# Chroma 연결 확인
curl http://localhost:8001/api/v2/heartbeat   # v1 경로는 1.x 서버에서 404 (docker-compose 헬스체크와 동일)
```

| 원인 | 조치 |
|------|------|
| 환경변수 미로드 | `export $(grep -v '^#' .env | xargs)` 재실행 |
| Chroma 연결 실패 | Chroma 컨테이너 실행 확인 (`docker ps` 또는 `container ls`) |
| 포트 충돌 | `lsof -i :8080`으로 점유 프로세스 확인 후 종료 |
| JDK 버전 | `java -version` → 21 이상인지 확인 |
| `OpenAI API key must be set` (`openAiApi`/`OpenAiAudioSpeechModel` 등 빈 생성 실패) | 앱은 채팅·임베딩·`OpenAiApi`를 전부 직접 만들고(`LlmConfig`/`EmbeddingBeanConfig`) Spring AI의 OpenAI 자동설정 빈은 **하나도 쓰지 않는다**. 자동설정을 켜두면 각 autoconfig가 기동 시 `openAiApi` 빈을 무조건 만들고 `spring.ai.openai.api-key`에 `Assert.hasText`를 걸어, `LOCAL_LLM_KEY`가 비어 있으면 죽는다(채팅·임베딩 자동설정도 마찬가지 — 모델 빈은 `@ConditionalOnMissingBean`으로 스킵되지만 `openAiApi` 빈은 아님). `application.properties`의 `spring.autoconfigure.exclude`에 **OpenAI 모델 자동설정 6종**(`OpenAiChat`/`OpenAiEmbedding`/`OpenAiAudioSpeech`/`OpenAiAudioTranscription`/`OpenAiImage`/`OpenAiModeration`AutoConfiguration)이 모두 제외돼 있어야 한다. 이 줄이 지워졌는지 확인 |

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
| 모든 프로바이더 소진 | `/llm-usage`에서 차단 상태 확인; 차단은 시간이 지나면 자동 해제됩니다(폴백 있음 30초 또는 `circuit-breaker-minutes`, **폴백 없는 유일 프로바이더는 5초**). **이 메시지는 원인이 아니라 결과입니다** — LOCAL 프로바이더가 하나뿐인 배포에서는 그 하나가 한 번 실패하기만 해도 이 문구가 나옵니다. 진짜 원인은 바로 앞 로그 줄(`Provider [x] threw ...`)에 있습니다 |

---

### 서버 PC에서는 되는데 다른 PC에서 접속하면 채팅이 안 됨

서버를 띄운 PC(`http://localhost:8080`)에서는 정상인데, 같은 망의 다른 PC(`http://10.x.x.x:8080`)에서는 **전송 후 아무 반응이 없고 보낸 메시지조차 표시되지 않는** 경우입니다.

원인은 브라우저의 **secure context** 규칙입니다. `http://localhost`는 예외적으로 secure context로 취급되지만 `http://10.x.x.x`는 아니며, 이때 `crypto.randomUUID()`·`navigator.clipboard` 같은 API가 아예 존재하지 않습니다. **서버 문제가 아니므로 서버 로그에도 아무것도 남지 않습니다.**

먼저 브라우저 콘솔(F12)을 확인하세요:

| 콘솔 메시지 | 원인 | 조치 |
|---|---|---|
| `crypto.randomUUID is not a function` | 구버전 `chat-stream.js` | **수정 완료** — 최신 버전으로 갱신. 브라우저 강력 새로고침(Ctrl+F5)으로 캐시된 구버전 JS 제거 |
| `navigator.clipboard` 관련 오류 | 구버전 `chat.html` | 동일 — 스레드 ID 복사 버튼에만 영향 |
| 오류 없음 · 응답만 안 옴 | 서버/LLM 문제 | 아래 "채팅 응답이 오지 않음" 참조 |

> **로그가 없다고 요청이 안 온 것은 아닙니다.** 정상 처리된 채팅은 INFO 레벨에서 아무 로그도 남기지 않습니다(성공 경로에 `log.info`가 없음). 요청 도달 여부를 확인하려면 DEBUG를 켜세요:
> ```bash
> curl -X POST http://localhost:8080/actuator/loggers/com.example.ragagent -H "Content-Type: application/json" -d '{"configuredLevel":"DEBUG"}'
> ```

**함께 확인할 것** — 위 증상이 해결돼도 평문 HTTP LAN 접속에는 다음 제약이 남습니다:

- `USE_CADDY_REVERSE_PROXY_HTTPS=true`(기본값!)이면 세션 쿠키에 `Secure`가 붙어 **다른 PC의 브라우저가 쿠키를 버립니다** → 요청마다 세션이 새로 생겨 `threadId`가 계속 바뀌고 대화 맥락이 끊깁니다. 평문 HTTP로 운영한다면 반드시 `false`로 두세요. (localhost에서는 이 문제가 드러나지 않습니다.)
- PWA 설치·서비스워커가 동작하지 않습니다. 필요하면 HTTPS를 적용하세요(§4.4, 폐쇄망은 §4.5-4).

---

### 채팅 응답이 오지 않음 (요청은 도달, 답변만 안 옴)

버블은 정상적으로 생기는데 답변 토큰이 오지 않는 경우로, 위 항목(요청 자체가 안 나감)과는 다릅니다. 대부분 **로컬 LLM이 느리거나 멈춘 것**입니다.

```bash
# LLM이 실제로 응답하는지 · 얼마나 걸리는지 직접 측정
time curl -m 30 -X POST $LOCAL_LLM_URL/chat/completions -H "Content-Type: application/json" \
  -d '{"model":"'"$LOCAL_LLM_MODEL"'","messages":[{"role":"user","content":"hi"}],"max_tokens":50}'
```

| 관측 | 원인 | 조치 |
|---|---|---|
| 위 curl이 수십 초~타임아웃 | LLM 서버가 CPU 추론 중이거나 VRAM 부족으로 스왑 | GPU offload 설정 확인. 모델을 더 작은 양자화로 교체 |
| 첫 요청만 매우 느리고 이후 정상 | 최초 요청이 모델 로딩(JIT)을 유발 | 기동 후 워밍업 요청을 한 번 보내두기 |
| 토큰은 오는데 매우 느림(예: 1 tok/s) | 추론 속도 자체가 느림 | 한국어는 대략 1토큰≈1글자라 "약 2,000자" 응답에 30분 이상 걸릴 수 있음 → 응답 모드를 S(간단히)로, 또는 더 빠른 모델 사용 |
| 로그에 `[TIMEOUT:SSE_IDLE]` | 300초 동안 진행이 없어 유휴 타임아웃 | 위 원인 해소. 느린 모델이 불가피하면 `SSE_IDLE_TIMEOUT_SECONDS` 상향 |
| 로그에 `[TIMEOUT:LLM_HTTP]` | `LLM_READ_TIMEOUT_SECONDS`(기본 600초) 초과 | 동일. 프로바이더 장애가 아니므로 Circuit Breaker는 차단하지 않음 |

---

### 답변 내용은 맞는데 계속 **미검증** 배지가 붙음 / 재시도만 반복

답변 자체는 문서와 일치하는데 근거 검증(`grounded`)이 계속 실패하는 경우입니다. 재시도까지 소진하면 미검증 배지가 붙은 채 전달됩니다.

**먼저 사유부터 확인하세요.** 검증 판정은 조용히 일어나지 않습니다.

```bash
docker compose logs app | grep -E "EVAL\] 검증 미통과|CRITIC_UNGROUNDED" | tail -20
```

`reason=`에 평가 LLM이 쓴 한 문장이 들어 있고, 이 값은 화면의 미검증 배지 옆에도 그대로 노출됩니다.

| 사유 유형 | 원인 | 조치 |
|------|------|------|
| 경로·포트·주소·환경변수 값이 문서와 다르다는 사유 | 평가 프롬프트의 **환경 의존 값 예외**가 동작하지 않음 — 이 값들은 문서와 달라도 `grounded=false` 사유가 될 수 없고 `envNote`(화면의 `ℹ️ 환경에 따라 달라질 수 있는 값`)로만 안내되어야 합니다 | 모델이 지시를 따르지 못하는 경우가 대부분입니다. `messages_ko.properties`의 `prompt.answer.eval`에 `[환경 의존 값 예외]` 블록이 남아 있는지 먼저 확인하고(테스트 `AnswerEvalPromptTest`가 이를 검사), 그다음 더 큰 로컬 모델로 교체 검토 (PIPELINE.md §5.3) |
| "문서에 없음" 계열인데 실제로는 문서에 있음 | 근거 청크가 검색 결과 하위 순위라 재시도해도 계속 컷 밖에 머무름 | `SEARCH_TOP_K` 상향(2~3 정도), 또는 `SEARCH_RETRY_ESCALATE=true` 확인 — 재검색마다 최종 컷이 `topK + 재검색횟수`로 넓어지고, 근거로 쓰이지 않은 하위 청크가 밀려나 자리를 내줍니다(§5.1). 둘 다 `/settings`에서 재기동 없이 조정 가능. 로그의 `[RETRIEVAL] 컨텍스트 여유 부족` 이 보이면 컷 확대가 생략된 것이므로 `LLM_MAX_TOKENS`를 내리거나 서버 `--ctx-size`를 올리세요 |
| 사유가 매번 비어 있음 | 평가 LLM이 `reason` 필드만 못 채움 (소형 모델에서 흔함) | 판정 자체는 유효하므로 답변이 막히지는 않습니다. 검증 품질이 중요하면 평가 호출이 타는 `TEXT` 프로바이더를 더 큰 모델로 |
| **배지가 아예 없음** (검증됨/생성도, 미검증도 아님) | 평가 응답이 비었거나 JSON 파싱에 실패해 **판정 없음**으로 내려감 | 실패가 아니라 "검증을 못 했다"는 표시입니다. 아래 *검증 배지가 사라짐* 항목으로 |
| 로그에 `[EVAL] 문서 발췌 32000자 상한으로 …` 경고 | `SEARCH_TOP_K` × `CHUNK_SIZE`가 과도해 하위 문서가 검증에서 제외됨 | 둘 중 하나를 낮추세요. 이 상태에서는 답변이 본 문서 일부를 평가자가 못 봅니다 |

> 검증은 답변을 **버리지 않습니다** — 미검증 배지는 "직접 출처를 확인하라"는 표시이지 실패가 아닙니다. 재시도 자체를 줄이려면 `MAX_RETRY_COUNT`를 낮추세요(`0`이면 재시도 없이 첫 답변을 그대로 전달).

---

### 검증 배지가 아예 사라짐 (검증됨/생성도, 미검증도 아님)

검증 **결과를 읽지 못한** turn입니다. 평가 호출이 빈 응답을 주거나 반환 JSON 파싱이 실패하면 `grounded=null`(판정 없음)로 내려가고, 그 turn은 배지 없이 저장·표시되며 재시도도 걸리지 않습니다.

**이것이 의도된 동작입니다.** 예전 폴백은 같은 자리에 `grounded=true`를 써넣었고, 그 값이 그대로 저장되어 **검증한 적 없는 답변에 초록 `검증됨`(N)/파랑 `생성`(C) 배지**가 붙었습니다. 같은 사고에서 `sufficient=false`로 걸렸어야 할 재시도도 함께 사라져, C 모드에서 "만들다 만" 답변이 그대로 전달되는 경로가 되기도 했습니다. 배지를 지우는 쪽이 fail-safe이고, 배지를 위조하는 쪽은 fail-safe가 아닙니다.

```bash
docker compose logs app | grep -E "판정 없음으로 기록한다" | tail -20
```

두 갈래가 잡힙니다.

- `검증기가 빈 응답을 반환했다` — 평가 호출이 **아무것도** 돌려주지 않은 경우. `inputTokens`/`outputTokens`가 함께 찍히며, 이 둘이 **원인을 가르는 유일한 단서**입니다(아래 표).
- `검증 응답을 읽지 못했다` / `창의 검증 응답을 읽지 못했다` — 응답은 왔지만 JSON 파싱이 실패한 경우. 뒤에 예외 메시지가 붙습니다. 대부분 모델이 스키마를 못 지킨 것이므로 아래 표의 `outputTokens>0` 행과 같은 조치를 적용하세요.

| 로그 | 의미 | 조치 |
|------|------|------|
| `outputTokens=0` | 모델이 아무것도 내지 못함. 검증 호출은 질문 + 답변 전문 + 문서 발췌 + 응답 스키마가 한 번에 들어가는 **이 앱 최대의 단일 요청**이라 컨텍스트 초과가 유력 | LLM 서버의 실제 컨텍스트 크기를 확인하고(`--ctx-size`), `SEARCH_TOP_K` 또는 `CHUNK_SIZE`를 낮춰 발췌량을 줄이세요. `LLM_MAX_TOKENS` 산정은 [PIPELINE §4.1](PIPELINE.md) |
| `outputTokens>0` | 모델이 냈지만 content가 아닌 곳(reasoning 필드 등)으로 나옴 | 평가 호출이 타는 `TEXT` 프로바이더를 JSON 출력이 안정적인 모델로 교체 — reasoning 모드가 있는 모델이라면 끄고 재시도 |

> 간헐적으로 한두 turn에만 나타난다면 그대로 두어도 됩니다(그 답변만 배지 없이 전달). 매 turn 발생한다면 검증이 사실상 꺼져 있는 상태이므로 위 표대로 조치하세요.

#### 컨텍스트 윈도우(`n_ctx`)별 설정 산정

`outputTokens=0`이 반복된다면 대개 **검증 호출이 컨텍스트를 넘긴 것**입니다. 이 호출은 질문 + **답변 전문** + 문서 발췌 + 응답 스키마가 한 번에 들어가는 **이 앱 최대의 단일 요청**이라, 컨텍스트 산정은 답변 호출이 아니라 이쪽 기준으로 해야 합니다.

발췌를 뺀 고정비는 대략 **4,900토큰**입니다(한글 ≈1토큰/자 기준: eval 시스템 프롬프트 ~1,570자 + 응답 스키마 ~250 + 질문 + 답변 ~3,000자). 여기에 발췌와 출력 예약(2,048)이 더해집니다.

```
필요 n_ctx ≈ 4,900 + (SEARCH_TOP_K × CHUNK_SIZE) + 2,048
```

| `n_ctx` | `LLM_MAX_TOKENS` | `SEARCH_TOP_K` | `CHUNK_SIZE` | 비고 |
|---|---|---|---|---|
| **8k~10k** | `2000` | `4` | `600~800` | 빡빡합니다. 발췌 여유가 ~3,000자뿐이라 재시도까지 감안하면 topK 4가 실질 상한이고, S 모드를 기본으로 쓰는 편이 맞습니다. 이 구간에서는 재검색의 문서 +1이 여유 부족으로 자주 생략되고(로그 `[RETRIEVAL] 컨텍스트 여유 부족`) 대신 청크 교체만 적용됩니다 — 의도된 동작입니다 |
| **16k** | `4000` | `6` | `1000` | 발췌 ~6,000자 |
| **32k** | `6000~8000` | `8~12` | `1500` | 기본값(topK **10** × 1500 = 15,000자)이 그대로 들어갑니다 — 4,900 + 15,000 + 2,048 ≈ 22,000 |
| **64k 이상** | `10000`(기본) | `12~15` | `1500~2000` | `MAX_EVAL_EXCERPT_CHARS`(32,000)가 비로소 의미를 갖는 구간 |

- **한글 ≈1토큰/자**는 보수적 계획값입니다. 코드·영문이 섞인 문서는 3~4자/토큰이라 실효 여유는 이보다 낫지만, 좁은 컨텍스트에서는 안전한 쪽으로 잡으세요.
- `MAX_EVAL_EXCERPT_CHARS`(코드 상수, 32,000)는 **병적인 설정을 막는 안전판이지 튜닝 손잡이가 아닙니다.** 실제 발췌량은 `SEARCH_TOP_K × CHUNK_SIZE`가 정하고, 이 상한에 닿았다는 것은 이미 하위 문서가 검증에서 빠지고 있다는 뜻입니다(위 경고 로그). 32k 이하 배포에서는 이 상수에 닿기 훨씬 전에 `n_ctx`가 먼저 터지므로, 조절해야 할 것은 언제나 `SEARCH_TOP_K`/`CHUNK_SIZE` 쪽입니다.
- 답변 프롬프트에도 이제 상한이 있습니다 — 아래 「앱이 창을 알면 스스로 줄인다」 참고. 두 설정은 여전히 답변 품질과 검증 정확도를 동시에 결정하지만, 넘칠 때 조용히 실패하는 대신 줄이고 알려 줍니다.

#### 앱이 창을 알면 스스로 줄인다 (§6.26)

위 표는 **앱이 컨텍스트 창을 모를 때**의 수동 산정입니다. 창을 알면 앱이 프롬프트를 미리 맞춰 줄이므로 표는 "이 값이면 축소 없이 들어간다"는 목표치가 됩니다.

창을 아는 방법은 둘입니다.

1. **선언** — `app.llm.providers[N].context-size=8192`. 항상 이쪽이 우선입니다.
2. **탐지** — 미설정이면 기동 시 LOCAL 프로바이더에게 물어봅니다. llama.cpp 는 `GET /props` 의 `default_generation_settings.n_ctx`, LM Studio 는 `GET /api/v0/models` 의 로드된 인스턴스 컨텍스트.

> ⚠️ **두 서버가 함께 노출하는 "모델 상한" 필드는 일부러 읽지 않습니다.** llama.cpp `/v1/models` 의 `meta.n_ctx_train` 과 LM Studio 최상위 `max_context_length` 는 **모델이 지원하는** 최대치라, `-c 8192` 로 띄운 서버도 131072 를 돌려줍니다. 그 값으로 예산을 짜면 컨텍스트 초과를 막으려던 코드가 오히려 초과를 부릅니다. 로드된 값을 못 찾으면 앱은 **추측하지 않고 "모름"으로 둡니다**.

**확인**: `/settings` 의 프로바이더 표 `컨텍스트` 열 — `8,192 (탐지됨)` / `16,384 (설정됨)` / `-`(모름). 기동 로그에도 `ctx=8192/probed` 형태로 찍힙니다. **`-` 이면 어떤 자동 보정도 돌지 않는다**는 뜻이므로 위 표대로 손으로 맞추거나 `context-size` 를 선언하세요.

#### 로컬 LLM을 재시작했더니 계속 "모든 프로바이더 사용 불가"

응답 중에 로컬 LLM을 재시작하면 진행 중이던 요청에 `400 - {"error":"terminated"}`가 돌아옵니다. 서버가 **내려가면서 그 요청을 끊었다**는 뜻이지 "서버가 아프다"가 아닙니다.

예전에는 이것도 일반 실패로 보고 프로바이더를 **30초 차단**했습니다. 문제는 이 오류가 서버가 *내려가는 순간*에만 나오는데 차단은 서버가 *이미 올라온 뒤*까지 남는다는 점입니다 — 실측 로그에서 차단 1회에 그 창 안의 재시도 3번이 전부 `All providers exhausted`로 죽었고, 운영자에게는 "완전히 재시작했는데도 계속 안 된다"로 보였습니다.

지금은 두 가지가 다릅니다.

- **`{"error":"terminated"}`는 차단하지 않습니다** — `NOT blocking circuit breaker; the server is usually back within seconds.` 로그가 대신 남습니다.
- **폴백이 없는 유일 프로바이더는 일반 실패도 5초만 차단합니다**(연결 거부 등). 프로바이더가 하나면 차단은 우회가 아니라 전면 중단이기 때문입니다. 차단을 아예 없애지는 않았습니다 — 서버가 정말 죽어 있으면 모든 요청이 각자 연결 타임아웃을 무는 편이 더 나쁩니다.

사용자 화면에도 **언제부터 다시 되는지**가 표시됩니다 — `AI 서버가 일시적으로 응답하지 않아 20초 후 다시 시도할 수 있습니다. (task=TEXT)`. 차단이 원인일 때만 초가 붙고, 시도했다가 실패해 후보가 없어진 경우엔 `잠시 후 다시 시도해 주세요.` 로 나갑니다(기다린다고 풀리는 것이 아니므로). REST 호출에는 같은 값이 `Retry-After` 헤더로 나갑니다.

**여전히 이 증상이 보인다면** 로그에서 실제 예외를 확인하세요:

```bash
grep -E "threw |blocked for |NO-FALLBACK" logs/rag-agent.log | tail -30
```

`Provider [x] threw ...` 줄이 진짜 원인입니다. `blocked for 30s`가 보이면 폴백이 있는 구성이라는 뜻이고, `blocked for 5s`면 유일 프로바이더 경로입니다.

#### 창이 바뀌었는데 앱이 모를 때 — 컨텍스트 다시 탐지 (§6.26 A5)

탐지값은 **기동 시점의 관측**입니다. 다음 셋 중 하나면 앱이 들고 있는 숫자가 실제와 다릅니다.

1. 서버를 다른 `--ctx-size` / `-c` 로 다시 띄웠는데 앱은 재기동하지 않았다
2. LM Studio 에서 컨텍스트를 바꿔 모델을 다시 로드했다
3. **LM Studio 의 JIT 로딩** — 앱이 뜰 때는 로드된 인스턴스가 없어 탐지가 빈손으로 끝났고, 그 뒤 모델이 올라왔다 (`컨텍스트` 열이 `-` 로 남아 있습니다)

**피해가 방향에 따라 다릅니다.** 창을 *줄인* 경우는 컨텍스트 초과로 요란하게 드러나고 축소 재시도가 받아냅니다. 문제는 창을 *키운* 경우입니다 — 앱은 옛 창 기준으로 계속 문서를 자르고, 사용자에게는 `컨텍스트 한도로 … 6개만 사용했습니다` 만 뜰 뿐 **왜 그런지는 아무 데도 안 나옵니다.** 창을 키웠는데 답변 품질이 그대로라면 이걸 의심하세요.

**해결**: `/settings` 프로바이더 표 위의 **컨텍스트 다시 탐지** 버튼(관리자만 보입니다). 등록된 LOCAL 프로바이더에게 지금 다시 물어보고, 프로바이더마다 결과를 표 위에 남깁니다.

| 결과 | 뜻 |
|---|---|
| **갱신됨** | 값이 실제로 달라졌습니다. 다음 호출부터 새 예산이 적용됩니다 |
| **변화 없음** | 물어봤고 예전과 같았습니다 |
| **응답 없음 — 이전 값 유지** | 서버가 답하지 않았거나 로드된 컨텍스트를 찾지 못했습니다. **알던 값은 지우지 않습니다** — 되돌리면 그 순간부터 입력 예산이 통째로 꺼지기 때문입니다 |
| **선언됨 — 탐지 안 함** | `context-size` 가 선언된 프로바이더입니다. 선언이 항상 탐지를 이기므로 묻지 않습니다. 값을 바꾸려면 `context-size` 를 고치고 재기동하세요 |

> **출력 상한은 함께 따라옵니다** (§6.26 A6): 앱의 모든 블로킹 호출은 자기 옵션을 실어 보내고, 그 위에 씌워지는 프로바이더 상한이 호출마다 현재 창 · 현재 `LLM_MAX_TOKENS` 로 다시 계산됩니다. 재기동이 필요한 곳은 **옵션을 실어 보내지 않는** 프레임워크 내부 호출(쿼리 확장 등)이 쓰는 기동 시점 기본값 하나뿐이고, 그 값이 **좁아진 새 창을 넘게 될 때만** 결과 위에 재기동 경고가 뜹니다(예: `local: defaultOptions 6,000 ≥ 창 4,096`). 창이 넓어진 경우는 그 기본값이 작을 뿐 안전하므로 알리지 않습니다.

> **주기적 자동 재탐지는 일부러 넣지 않았습니다.** 예산이 스스로 움직이면 같은 질문이 시각에 따라 다른 양의 근거를 받아 재현이 안 됩니다(`[TOKEN_CAL]` 계수를 자동 보정하지 않는 것과 같은 이유). 창을 자주 바꾸는 배포라면 아예 `context-size` 로 못 박는 편이 낫습니다 — 선언된 값은 애초에 낡지 않습니다.


창을 알 때 자동으로 도는 것:

- **기동 시 검사** — `max-tokens >= context-size` 면 입력에 남는 자리가 0 이하라 **모든 요청이 실패**합니다(설정만 보면 멀쩡해 보입니다). 창의 절반으로 낮추고 WARN 을 남깁니다.
- **호출 전 축소** — `입력 예산 = 창 − 출력 예약 − 여유(창의 10%)`. 넘치면 **문서를 하위 순위부터**, 그래도 넘치면 **이전 대화를 오래된 턴부터** 덜어냅니다. 검색 결과가 하나도 없어지지는 않습니다(최상위 문서는 항상 남습니다).
- **사용자 안내** — 축소가 일어난 답변에는 `컨텍스트 한도로 검색된 문서 10개 중 6개만 사용했습니다.` 가 붙고, 프롬프트에 실리지 못한 출처에는 `미사용` 배지가 붙습니다. 출처 목록은 검색된 전부를 그대로 보여주므로, 이 표시가 없으면 모델이 읽지 않은 문서를 읽은 것처럼 보게 됩니다.
- 로그: `[BUDGET] 컨텍스트 창 …토큰(출력 예약 …) → 입력 예산 …토큰에 맞춰 축소: 문서 10→6개, 이력 5000→800자`
- **인덱싱(MD 교정·txt→md 구조화)도 같은 창을 봅니다** — 다만 잘라내는 대신 **조각을 더 잘게 나눕니다**(버려지는 내용 없음). 재작성은 출력이 입력에 비례하므로 `조각 ≤ (창 − 지시 프롬프트 − 여유) / 2.5` 로 잡고, `LLM_MAX_TOKENS` 파생 상한보다 작을 때만 적용합니다. 창을 모르면 예전 동작 그대로입니다. 창 20,480 · `LLM_MAX_TOKENS=10000` 에서 MD 교정이 `Context size has been exceeded` 로 실패하던 조합이 이 계산으로 막힙니다 — **창을 넓히지 않고도** 교정이 끝까지 돕니다.

> **축소가 잦다면** `SEARCH_TOP_K` → `CHUNK_SIZE` → 서버 `--ctx-size` 순으로 손보세요. 축소는 안전장치이지 정상 상태가 아닙니다 — 하위 문서가 매번 빠진다면 그 문서들은 애초에 검색될 필요가 없었거나, 창이 이 설정에 비해 좁은 것입니다.

- **그래도 넘치면 문서를 조금씩 덜어내며 다시 시도합니다** — 로그 `[SHRINK] ANSWER 컨텍스트 초과 — 프롬프트를 줄여 다시 시도한다. 시도 1/5, 문서 10→9개(app.llm.shrink-step=1)`. 한 번에 덜어낼 개수가 `LLM_SHRINK_STEP`(기본 **1**, 범위 1~10, `/settings` 에서 핫 수정)이고 재시도는 5회까지이므로, **`LLM_SHRINK_STEP × 5` 가 도달 가능한 최대 축소폭**입니다 — 거기서도 안 되면 `RAG-LLM-003` 이 나갑니다.
  - 기본값이 1인 이유는 사전 예산이 이미 창에 맞춰 놓은 뒤라 여기까지 오는 초과가 대개 아슬아슬하기 때문입니다. 한 개만 덜어내면 들어갈 자리에서 반을 자르면 근거의 절반이 사라집니다. 실패한 시도는 서버가 생성 전에 거절하므로 왕복 자체는 쌉니다.
  - **이 로그가 한 턴에 여러 번 보이면** 예산 계산이 실제와 어긋나 있다는 뜻입니다. `[TOKEN_CAL]` 계수를 함께 확인하고, `LLM_SHRINK_STEP` 을 2~3 으로 올려 왕복을 줄이거나 — 근본적으로는 `SEARCH_TOP_K` 를 낮추거나 서버 `--ctx-size` 를 키우십시오.

> **컨텍스트 초과가 그래도 나면** 프로바이더는 더 이상 차단되지 않습니다(§6.26). 오류도 "모든 프로바이더 사용 불가"가 아니라 `RAG-LLM-003` 으로 구분되어, 사용자에게는 질문을 좁히거나 관리자에게 `search-top-k` 조정을 요청하라고 안내합니다.

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
| 사용자 대기 시간이 너무 김 | `LLM_PERMIT_WAIT_TIMEOUT_SECONDS`(기본 60초)를 늘려 대기 상한을 확대 — 단, `LLM_READ_TIMEOUT_SECONDS`(기본 600초)보다는 충분히 짧게 유지 |
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
- 청크 하나하나가 `INDEXING_KEYWORD_TIMEOUT_SECONDS`(기본 600초)에 가깝게 걸리고 있다면, 로컬 LLM 서버가 그 시점에 실제로는 거의 응답하지 못하고(과부하·모델 교체·행) 매 호출이 타임아웃까지 채운 뒤 TF 폴백으로 넘어가고 있다는 신호입니다 — 문서 하나가 수 시간 걸렸다면 먼저 이것부터 의심하세요. `[TIMEOUT:INDEX_KEYWORD]`/`[TIMEOUT:INDEX_KEYWORD_BATCH]` 로그 빈도로 확인 가능(아래 "로컬 LLM 응답 타임아웃"의 로그 키 표 참고).

---

### 업로드/재인덱싱 진행률이 "처리 중"에서 멈춘 것처럼 보임 (실제로는 서버에서 계속/이미 완료됨)

인덱싱이 위 항목처럼 오래 걸리는 동안(수 시간) 브라우저의 SSE 연결이 끊겼다 재접속하는 일이 드물지 않습니다(절전, 네트워크 전환, VPN 재연결 등). 이때 서버(`IndexingProgressService`)가 좀비 연결이 되지 않도록 아래처럼 동작합니다.

| 재접속 시점 | 동작 |
|---|---|
| 작업이 아직 실행 중 | 지금까지의 진행 이력을 재생하고 계속 실시간 추적 |
| 작업이 끝난 지 4시간 이내 (`IndexingProgressService.BUFFER_RETENTION`, 코드 상수 — 프로퍼티화되어 있지 않음) | 마지막 상태(`done`/`error`/`cancelled`)를 즉시 재생 후 종료 |
| 작업이 끝난 지 4시간 초과, 또는 애초에 존재한 적 없는 taskId | `unknown` 종결 이벤트를 즉시 보내고 종료 — 화면에는 실패가 아니라 "⚠️ 상태 확인 불가, 문서 목록에서 확인" 경고로 표시됨 |

`GET /ui/documents/progress/{taskId}/status`로 SSE 없이 1회성 상태 조회도 가능합니다(`{"stage":"running"|"done"|"error"|"cancelled"|"unknown", ...}`) — 진단용으로 유용합니다.

이 개선 이전에는 작업 완료 **60초**만 지나도(그때는 4시간이 아니라 60초짜리 이벤트 버퍼였음) 재접속 시 서버가 heartbeat만 보내고 실제 이벤트는 영원히 안 오는 좀비 연결 상태가 됐습니다 — 화면은 "처리 중"에 영구히 멈추고, 문서관리 페이지는 파일을 **순차** 업로드하므로 그 파일 이후 나머지 파일은 아예 업로드조차 시작되지 않았습니다. 여러 파일을 올렸는데 1개만 완료되고 나머지가 조용히 멈춰 있었다면 이 문제였을 가능성이 큽니다 — 문서관리 페이지를 새로고침하면 실제로는 완료된 문서가 정상적으로 보입니다.

`/admin`의 **↺ MD 재인덱싱**도 같은 `IndexingProgressService`/같은 SSE 엔드포인트를 쓰므로 동일하게 적용됩니다. 다만 `admin.html`의 재인덱싱 진행률 표시는 연결 오류 시 곧바로 포기하지 않고 최대 5회까지 재시도합니다(업로드 화면과 동일한 재시도 정책).

---

### 인덱싱 도중 청킹/임베딩 단계에서 에러 (MD 변환·교정은 이미 끝난 상태)

업로드 또는 동기화 로그에 `[INDEX]` 진행 메시지(로딩/교정)까지는 찍혔는데 그 이후(청킹·키워드추출·벡터 저장)에서 예외가 나는 경우입니다.

| 확인 사항 | 설명 |
|-----------|------|
| 문서가 `/admin` 목록에 보이는지 | MD 변환+교정(이미지 분석 포함)까지 성공했다면 청크 수 `0`인 상태로 문서 레지스트리에 나타납니다 — 재업로드하지 말고 해당 문서의 ↺(재인덱싱) 버튼을 눌러 저장된 MD 파일 기준으로 재시도하세요. 이미지 분석/MD 교정은 다시 실행되지 않습니다 |
| 문서가 목록에 아예 안 보이는지 | MD 변환/교정 자체가 실패했다는 뜻입니다(체크포인트 저장 이전 단계) — 이 경우엔 재업로드가 필요합니다 |
| 반복 실패 원인 | 청킹 이후 단계 실패는 대개 임베딩 서버 연결 문제(`임베딩/LLM 서버 연결 실패` 로그) 또는 [임베딩 서버 배치/토큰 초과](#임베딩-서버-배치토큰-초과-input-n-tokens-is-too-large-to-process) — 해당 절 조치 후 ↺ 재시도 |

> 상세 동작: [PIPELINE.md §6.3 6-bis 레지스트리 체크포인트](PIPELINE.md#63-docx--md--임베딩-db-저장-상세-이미지-포함).

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

### 지식 제안의 **임베딩 실행**이 매우 느림 / 응답이 없음 (§6.9)

본문에 이미지가 있는 제안입니다. 승인 요청이 이미지 수만큼의 Vision 호출을 **동기로** 기다립니다 — 설명이 임베딩되는 텍스트의 일부여야 그림 내용이 검색에 걸리므로 배경으로 미룰 수 없는 구조입니다(§6.9 "본문 이미지").

| 확인 | 방법 |
|------|------|
| 실제로 Vision을 돌고 있는지 | 로그의 `[VISION] 이미지 분석 요청: images/submissions/...` 줄 — 이미지 장수만큼 나옵니다 |
| Vision 프로바이더가 있는지 | 없으면 `No vision provider available` warn 후 `[이미지 설명 불가: ...]`가 설명으로 들어갑니다(승인은 정상 완료) |
| 아예 건너뛰게 하려면 | `IMAGE_DESCRIPTION_ENABLED=false` — 이미지는 표시만 되고 검색에는 기여하지 않습니다(재기동 필요) |
| 장수를 줄이려면 | `CuratedImageStore.MAX_IMAGES_PER_SUBMISSION`(기본 10) 상수 수정 — **프로퍼티로 외부화되어 있지 않습니다** |

Vision 호출이 실패해도 승인은 실패하지 않고 설명 없이 진행됩니다(`[SUBMISSION] 이미지 설명 생성 실패 — 설명 없이 진행` warn). 이 호출은 §6.12 채팅 동시성 게이트를 타지 않으므로 채팅 슬롯을 잠식하지는 않지만, 로컬 LLM 서버 자원은 공유합니다.

---

### `data/images/submissions/`가 계속 커짐

제안 폼은 **등록 전에** 이미지를 먼저 업로드합니다(작성자가 미리보기로 확인할 수 있게). 등록하지 않고 창을 닫은 초안의 이미지는 그대로 남습니다.

- 회수는 **기동 시 1회**(`CuratedImageStore.sweepOrphans()`, `ApplicationReadyEvent`)만 돕니다 — 상시 스케줄러가 없으므로 **장기 무재기동 서버에서는 누적**됩니다. 재기동하면 24시간 지난 미참조 파일이 정리되고 `[SUBMISSION] 참조되지 않는 본문 이미지 N건 정리` 로그가 남습니다.
- 24시간 유예는 열어둔 작성 화면의 이미지를 발밑에서 지우지 않기 위한 것입니다.
- 살아 있는 제안(`pending`/`approved`)이나 활성 `curated_qa`가 참조하는 파일은 절대 지워지지 않습니다. 파일명이 내용 해시라 **여러 제안이 같은 파일을 공유**할 수 있어, 삭제는 항상 참조 세기를 거칩니다.
- 수동 정리가 필요하면 앱을 내린 뒤 `data/images/submissions/`에서 오래된 파일을 지우세요 — 다만 승인된 제안이 참조하던 파일을 지우면 답변 썸네일이 404가 됩니다(본문 텍스트와 설명은 그대로 남으므로 검색 자체는 계속 동작).

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

- **`[TIMEOUT:SSE_IDLE]`** — 에이전트 그래프가 `app.sse-idle-timeout-seconds`(기본 300초) 동안 노드 전환·토큰·소스 준비 신호를 전혀 못 받음. 로컬 LLM 서버(LM Studio 등)가 요청을 받고도 응답을 전혀 생성하지 못하는(멈춘) 경우가 전형적입니다. **응답이 느리더라도 토큰이 계속 나오고 있다면 이 타임아웃에 걸리지 않습니다** — 매 신호마다 리셋되기 때문입니다.
- **`[TIMEOUT:SSE]`** — `app.sse-timeout-seconds`(기본 7200초) 절대 상한 초과. 응답이 활동 중이어도(토큰이 계속 나와도) 총 소요 시간이 이 값을 넘으면 발생 — 극히 드묾, 안전장치 성격.

| 원인 | 확인 방법 | 조치 |
|------|----------|------|
| LLM 서버 미실행·모델 미로드 | LM Studio 상태 확인 | 모델 로드 완료 후 재시도 |
| `base-url`에 `/v1` 중복 | 시작 로그 `endpoint=...` 확인 | `base-url`에 `/v1` 포함 여부와 무관하게 내부 자동 처리됨. 앱 재시작 |
| 구버전 앱에서 `stream=false` 설정 | — | 최신 버전은 내부적으로 스트리밍 방식으로 대체함. 앱 재시작 |

타임아웃이 반복되면 아래 순서로 조정하세요.

1. `[TIMEOUT:SSE_IDLE]`이 반복되면 `SSE_IDLE_TIMEOUT_SECONDS` 증가 (기본 300, 예: 300 → 600) — LLM이 첫 토큰을 내기까지 오래 걸리는 환경(느린 하드웨어, 큰 모델)에 해당
2. `[TIMEOUT:SSE]`가 발생하면 `SSE_TIMEOUT_SECONDS` 증가 (기본 7200, 예: 7200 → 14400)
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
| 문서 관리 쓰기(업로드, 업로드취소, 삭제, 태그 수정·편집, **내보내기**) | **로그인 필요** — 비로그인 시 `/login` 리다이렉트, `ROLE_ADMIN` 아닌 로그인은 403. 내보내기는 읽기 동작이지만 문서 전체 내용을 한 번에 반출하는 벌크 기능이라 이 그룹에 포함(§6.8) |
| `/admin/**` | **로그인 필요** — 게스트/첫 관리자 자동 주입 없음(평문 no-auth와의 핵심 차이) |
| `/api/v1/documents/**` REST 엔드포인트 | **의도적으로 그대로 열어둠 + CSRF 예외** — `POST /api/v1/documents/sync` 등 curl 자동화([§6.2](#62-문서-버전-관리) 참조)가 그대로 인증 없이 동작 |
| Web UI 게스트 화면 | 업로드 카드·삭제 버튼·Admin 내비가 숨겨짐(관리자로 로그인해야 노출) |
| 로그아웃 버튼 | 관리자로 로그인했을 때만 노출 |

**로그인 → 관리 흐름**:
1. `/setup`에서 관리자 계정 생성 (최초 1회, 평문 no-auth와 동일)
2. `/login`에서 방금 만든 이메일·비밀번호로 로그인
3. 로그인 세션이 유지되는 동안 `/documents`에서 업로드·삭제, `/admin`에서 청크 관리 가능
4. 다른 탭/시크릿 창은 여전히 게스트 — 관리 기능은 로그인한 브라우저 세션에서만 보임

> **주의**: 이 모드는 "누가 관리할 수 있는가"만 잠급니다. 채팅 개인화가 필요하면 아래 §9.4.3을, 계정 기반의 진짜 격리가 필요하면 전체 인증 모드(`app.auth.enabled=true`)를 사용하세요.

#### 9.4.3 접속자별 채팅 개인화 (`app.auth.guest-identity`)

`app.auth.enabled=false`일 때만 의미 있습니다. 기본값에서는 **모든 방문자가 하나의 게스트 계정을 공유**하므로 사이드바 스레드 목록·대화 이력이 전부 섞여 보입니다. 이 값을 바꾸면 방문자를 구분해 각자의 채팅 화면을 갖게 할 수 있습니다.

| 값 | 방식 | NAT(사무실 공인 IP 공유) | DHCP 갱신 | 쿠키 차단 |
|---|---|---|---|---|
| `shared` (기본) | 고정 게스트 1개 | — | — | ✅ |
| `ip` | 접속 IP의 HMAC 해시 | ❌ 전원 뭉침 | ❌ 이력 유실 | ✅ |
| `cookie` | 장수 HttpOnly 서명 쿠키 | ✅ | ✅ | ❌ 매번 새 방문자 |
| **`hybrid`** (권장) | 쿠키 우선, 없으면 IP로 유도해 쿠키에 저장 | ✅ | ✅ | ✅ IP로 폴백 |

```bash
AUTH_GUEST_IDENTITY=hybrid
TRUST_FORWARDED_FOR=true   # 리버스 프록시(Caddy) 뒤라면 필수 — 아래 참조
```

**⚠️ `TRUST_FORWARDED_FOR`를 반드시 함께 맞추세요** (`ip`/`hybrid` 사용 시):

- **프록시 뒤(Caddy 등) → `true` 필수**. 끄면 모든 요청이 프록시 IP 하나로 보여 **개인화가 무효**가 되고, per-IP 속도 제한도 전원이 한 버킷을 공유합니다.
- **프록시 없이 직접 노출 → `false` 유지**. `X-Forwarded-For`는 클라이언트가 임의로 넣을 수 있는 헤더라, 신뢰하면 공격자가 매 요청 헤더만 바꿔 속도 제한을 무한 우회하거나 **다른 방문자의 게스트 신원을 가로채 대화 목록을 열람**할 수 있습니다(PLAN §6.19.3).

**동작 세부**:
- 방문자 id는 `guest-<12자리 hex>` 형식입니다. IP 원문은 저장되지 않고, 서버가 최초 기동 시 생성해 `app_secret` 테이블에 보관하는 키로 HMAC 해싱됩니다(재기동해도 id가 바뀌지 않도록 영속화).
- 쿠키 이름은 `rag_visitor`(HttpOnly, SameSite=Lax, 1년). HTTPS 접속이면 `Secure`도 붙습니다.
- **문서는 여전히 공유**입니다(`DocRegistry.SHARED`). 개인화되는 것은 채팅 스레드·대화 이력·좋아요(큐레이션 Q&A) 소유권, 그리고 **지식 제안 게시판의 "내 제안" 목록·처리 알림**(§6.9)뿐입니다.
- **§6.9 지식 제안 게시판을 쓴다면 이 설정이 사실상 필수**입니다 — `shared`에서는 모든 방문자가 한 사람으로 취급되어 서로의 제안과 처리 알림이 섞여 보입니다. `hybrid`에서는 실제 로그인한 관리자가 이 화면을 열어도 게스트 id로 덮어써지지 않으므로(`NoAuthAutoLoginFilter.hasRealLogin()`), 관리자 본인 제안이 게스트 목록에 섞이지 않습니다.
- 오타 등 알 수 없는 값은 `shared`로 폴백합니다(설정 실수가 "반쪽만 분리된" 상태로 이어지지 않도록). 기동 로그의 `[GUEST_ID] 방문자 식별 전략: ...` 줄로 실제 적용값을 확인하세요.
- **기존 대화는 보이지 않게 됩니다.** 이 설정을 켜기 전에 쌓인 스레드는 예전 공용 게스트 id(`00000000-…-0001`)에 묶여 있어 새 방문자 id로는 조회되지 않습니다(삭제되지는 않음). 되돌리려면 `shared`로 다시 바꾸면 그대로 다시 보입니다.

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
- [ ] (no-auth 모드) 여러 사람이 접속한다면 채팅을 방문자별로 나눌지 결정 — 나누려면 `AUTH_GUEST_IDENTITY=hybrid` (§9.4.3)
- [ ] 리버스 프록시(Caddy) 뒤라면 `TRUST_FORWARDED_FOR=true`, 직접 노출이면 `false` 확인 (§9.4.3) — 잘못 설정하면 속도 제한·방문자 식별이 모두 어긋남
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

**지식 제안 게시판 (§6.9, 사용하는 경우)**:
- [ ] 기존 DB 업그레이드라면 **적용 전 DB 파일 백업**(`data/memory.db` + 분리를 켰다면 벡터 DB 파일 — §6.3.1) + 첫 기동 로그의 `[CURATED] curated_qa 스키마 마이그레이션 완료` 확인
- [ ] (게스트 배포) `AUTH_GUEST_IDENTITY`가 `shared`가 아닌지 확인 — 기동 로그 `[GUEST_ID] 방문자 식별 전략: ...` 줄로 실제 적용값 확인
- [ ] `/curated/submissions`에서 제안 1건 등록 → 관리자 헤더 배지에 대기 건수 표시(최대 60초) 확인
- [ ] 게스트로 `GET /admin/submissions/pending-count` 호출 → 로그인 리다이렉트(또는 403) 확인
- [ ] `/admin` 지식 제안 검토 카드에서 **임베딩 실행** → 해당 내용으로 검색 시 답변 근거에 반영되는지 확인
- [ ] 거부 처리 → 작성자 화면에 사유 전문 표시 + 헤더 배지 갱신 확인
- [ ] `data/audit/audit.log`에 `curated.submission.approve`/`.reject` 기록 확인

**태그 기반 검색 적용 시 (프리릴리즈 정책)**:
- [ ] 적용 전 백업 여부 결정 및 수행 (선택)
- [ ] `data/memory.db`(+wal/shm) — 분리를 켰다면 벡터 DB 파일도 함께 —, `data/documents`, `data/converted`, `data/images` 수동 초기화 완료
- [ ] (chroma) `data/chroma` 또는 `chroma_data` 볼륨 초기화 완료
- [ ] 재기동 후 `/setup` 또는 로그인 경로 정상 확인
- [ ] 문서 재업로드/동기화 후 태그 엄격 필터 동작 확인
