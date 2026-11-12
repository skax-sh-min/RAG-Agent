# 운영자 매뉴얼

RAG Agent 시스템 배포·설정·운영 가이드입니다. 화면에서 하는 관리 작업은 [관리자 매뉴얼](ADMIN_MANUAL.md), 채팅·문서 사용법은 [사용자 매뉴얼](USER_MANUAL.md)을 보십시오.

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
      - 4.1.3 [메모리(JVM 힙) 설정](#413-메모리jvm-힙-설정)
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
   - 6.2 [데이터 영속성](#62-데이터-영속성)
      - 6.2.1 [SQLite 파일별 테이블 구성](#621-sqlite-파일별-테이블-구성)
      - 6.2.2 [DB 백업과 복구](#622-db-백업과-복구)
   - 6.3 [성능](#63-성능)
   - 6.4 [검색 품질 평가 하네스 (개발자용)](#64-검색-품질-평가-하네스-개발자용)
   - 6.5 [청크 분할 전략 (크기 기준 병합 / 소제목 최대 분할)](#65-청크-분할-전략-크기-기준-병합--소제목-최대-분할)
7. [벡터 스토어 운영 참고](#7-벡터-스토어-운영-참고)
   - 7.1 [백엔드별 표시 차이 (레이아웃·기능은 동일)](#71-백엔드별-표시-차이-레이아웃기능은-동일)
   - 7.2 [키워드 축 가중치를 0.5로 두는 이유 (한/영 혼재 코퍼스)](#72-키워드-축-가중치를-05로-두는-이유-한영-혼재-코퍼스)
8. [문제 해결](#8-문제-해결)
9. [보안 설정](#9-보안-설정)
   - 9.1 [git 훅 설치](#91-git-훅-설치)
   - 9.2 [입력 검증 동작](#92-입력-검증-동작)
   - 9.3 [응답 크기 제한](#93-응답-크기-제한)
   - 9.4 [인증 토글 (no-auth 모드)](#94-인증-토글-no-auth-모드)
      - 9.4.1 [평문 no-auth 모드](#941-평문-no-auth-모드)
      - 9.4.2 [관리 전용 인증 (management-only)](#942-관리-전용-인증-management-only)
      - 9.4.3 [접속자별 채팅 개인화 (app.auth.guest-identity)](#943-접속자별-채팅-개인화-appauthguest-identity)
10. [운영 체크리스트](#10-운영-체크리스트)

---

## 1. 시스템 개요

**기술 스택**: Spring Boot 4.1.2 + Spring AI 2.0.2, Java 21 Virtual Threads  
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
├── pom.xml                     # Spring Boot 4.1.2 + Spring AI 2.0.2
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
│   └── memory.db               # 유일한 SQLite 파일(WAL) — 대화·계정·설정·레지스트리 + 키워드 색인(+ sqlite-vec 벡터)
│                               #   ※ 옛 SQLITE_VEC_DB_PATH 를 설정한 배포는 그 파일이 이 역할을 한다 (§6.2.1)
└── src/main/
    ├── java/com/example/ragagent/
    │   ├── agent/              # AgentGraph (상태 머신), AgentState (불변 레코드)
    │   ├── config/             # AppProperties, LlmConfig, WebConfig
    │   ├── controller/         # ChatController · DocumentController · OperationsController (REST + HTMX 혼재), AdminController, SettingsController, CuratedSubmissionController, ChunkReportController, AuthController, GlobalExceptionHandler
    │   ├── llm/                # LlmRouter, RoutingMode, CircuitBreaker, 컨텍스트 창/예산(ContextWindowProbe·PromptBudget·TokenEstimator)
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

> **백엔드 전환 = 재인덱싱**: chroma ↔ sqlite-vec 간 벡터는 공유되지 않습니다. 전환 후 문서 재업로드(또는 재동기화)로 재인덱싱해야 합니다 — 원본은 `data/documents/`에 보존됩니다. `/admin` 페이지는 **두 백엔드 모두** 상태 카드·청크 조회/편집/삭제를 제공합니다(관리자 매뉴얼 §4).
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
| `LOCAL_LLM_URL` | 이 provider 사용 시 ✅ | — (기본값 없음) | `providers[1]`(`local`, 로컬 LLM 1) 엔드포인트. **미설정·공백이면 이 provider가 통째로 비활성화된다** (`LlmConfig` G2 — 예전처럼 `http://localhost:1234/v1`로 조용히 폴백하지 않음). 값을 설정하면 기동 시 `GET {URL}/models`로 접속 가능·모델명 일치 여부를 검증한다(G3) — 실패하면 **애플리케이션이 시작되지 않는다**, [§5.2 프로바이더 활성화 게이트](#52-프로바이더-속성) 참고. 임베딩 설정(`EMBED_BASE_URLS`)의 폴백으로도 별도 사용됨(그쪽은 기존처럼 자체 기본값 보유, G3 대상 아님) |
| `LOCAL_LLM_KEY` | — | `no-key` | `providers[1]` API 키. **로컬 엔드포인트(llama-server 등)는 키가 불필요** — 비우거나 미설정해도(URL만 설정돼 있다면) LOCAL provider는 등록됨(미설정 시 내부적으로 `no-key` 치환, G1). 완전히 제외하려면 `LOCAL_LLM_URL`을 비우거나(G2) `application.properties`의 `providers[1]`를 주석 처리 |
| `LOCAL_LLM_MODEL` | — | `google/gemma-4-e4b` | `providers[1]` 모델 식별자. 사용 중인 로컬 모델명으로 변경 — 서버 `/v1/models` 의 id 를 그대로 적거나, **다른 모델과 겹치지 않는 그 일부**만 적어도 된다(예: LM Studio 가 `google/gemma-4-e4b` 로 보고하면 `gemma-4-e4b`; 대소문자 무시). G3 가 기동 시 서버의 전체 id 로 해석해 요청에 싣는다(INFO 로그 `is part of server model '…'; using the server's id`) |
| `LOCAL_LLM_TYPE` | — | `BOTH` | `providers[1]`(로컬 LLM 1) 작업 유형 (`app.llm.providers[1].type`) — `MICRO_TEXT`/`LIGHT_TEXT`/`TEXT`/`VISION`/`LIGHT_BOTH`/`BOTH` 중 하나. 기본 `BOTH`(모든 작업 처리). **텍스트 3종은 사다리라 `TEXT`는 채팅 답변뿐 아니라 `LIGHT_TEXT`·`MICRO_TEXT` 잡무까지 함께 받는다** — "채팅 전용으로 한정"하는 값이 아니다(§5.6 type 값 표). 이미지를 뺀 텍스트 전용 모델도 `BOTH`로 두는 것이 권장 — Vision 호출 1회만 실패한 뒤 기억되어 이후 이미지 작업에서 제외되고 텍스트 작업은 영향을 받지 않는다. 이미지 설명 자체를 끄려면 `IMAGE_DESCRIPTION_ENABLED=false`를 쓴다 |
| `LOCAL_LLM_URL_2` | 사용 시 ✅ | — (기본값 없음) | `providers[2]`(`local-2`, 로컬 LLM 2) 엔드포인트. `local`과 **동일한 role(LOCAL)·동일한 priority(1)**로 등록되어 두 번째 물리 서버로 로드밸런싱된다(least-in-flight — [§5.4 예제 5/7](#예제-5--로컬-llm-2대-로드밸런싱-처리량-확장) 참고). **미설정·공백이면 이 provider가 통째로 비활성화된다**(G2) — 2대째 로컬 서버가 없다면 그냥 비워두면 됨(회귀 0, `local` 단독으로 동작). 값을 설정하면 기동 시 접속 가능·모델명 일치 여부를 검증하며 실패 시 애플리케이션이 시작되지 않는다(G3) — 즉 "설정은 했지만 서버가 아직 안 떠 있다"는 이 변수를 비워두는 것과 결과가 다르다(전자는 기동 실패, 후자는 정상 기동) |
| `LOCAL_LLM_KEY_2` | — | `no-key` | `providers[2]` API 키. 로컬 엔드포인트는 키가 불필요 — 미설정 시 `no-key`가 치환되어 등록됨(`LOCAL_LLM_KEY`를 상속하지 않음). 모델명(`LOCAL_LLM_MODEL_2`)은 여전히 `LOCAL_LLM_MODEL`로 폴백 |
| `LOCAL_LLM_MODEL_2` | — | `LOCAL_LLM_MODEL` 폴백 | `providers[2]` 모델 식별자. 미설정 시 `LOCAL_LLM_MODEL`과 동일한 모델명을 사용(로컬 LLM 1과 동일 모델을 다른 서버에 복제하는 것이 일반적인 사용 사례) |
| `LOCAL_LLM_TYPE_2` | — | `BOTH` | `providers[2]`(로컬 LLM 2) 작업 유형 (`app.llm.providers[2].type`). 값 집합은 `LOCAL_LLM_TYPE`과 동일. 보통 로컬 LLM 1과 같은 `BOTH`를 사용 |
| `LOCAL_FAST_LLM_URL` | 사용 시 ✅ | — (기본값 없음) | §6.21 — `providers[0]`(`local-fast`, 소형 로컬 LLM 1) 엔드포인트. 잡무 전용 소형(~500MB) 모델을 `providers[1]`(`local`)과 **다른 포트/장비**에 띄우고 가리킨다.<br>**미설정·공백이면 이 provider가 통째로 비활성화된다**(G2) — 소형 모델 서버가 없다면 그냥 비워두면 됨(`MICRO_TEXT`는 `local`이 흡수. 단 **대화 요약만은 흡수하지 않고 생략**되어 채팅이 원본 history 폴백으로 동작한다 — 부가 기능인 요약이 답변용 모델의 동시성 슬롯을 잠식하지 않게 하려는 의도적 게이팅). 값을 설정하면 기동 시 접속 가능·모델명 일치 여부를 검증하며(G3, 기본 활성) 실패 시 애플리케이션이 시작되지 않는다 — "URL은 설정했지만 서버가 아직 안 떠 있어 매 요청마다 `local`로 런타임 폴백"되는 예전 동작은 `LLM_VERIFY_LOCAL_MODELS_ON_STARTUP=false`로 G3를 꺼야만 나온다 — 예제는 [§5.4 예제 6](#예제-6--소형경량-llm-분리로-잡무-오프로딩-plan-621) 참고 |
| `LOCAL_FAST_LLM_KEY` | — | `no-key` | `providers[0]` API 키. `LOCAL_LLM_KEY`와 마찬가지로 로컬 엔드포인트는 보통 불필요 — 비워도(URL만 설정돼 있다면) `no-key`가 치환되어 등록됨 |
| `LOCAL_FAST_LLM_MODEL` | — | `Qwen3.5-0.8B-Q4_K_M.gguf` | `providers[0]` 모델 식별자. 사용 중인 소형 모델명으로 변경 |
| `LLM_VERIFY_LOCAL_MODELS_ON_STARTUP` | — | `true` | (`app.llm.verify-local-models-on-startup`) — G3 토글. `true`면 `LOCAL_LLM_URL`/`LOCAL_LLM_URL_2`/`LOCAL_FAST_LLM_URL`이 설정된 각 provider에 대해 기동 시 `GET {URL}/models`를 호출해 접속 가능·모델명 일치(정확 일치, 또는 유일한 부분 일치 → 서버 id 사용)를 확인하고, 어느 모델에도 없거나 여러 모델에 걸리거나 서버가 안 뜨면 애플리케이션이 시작되지 않는다. **예외 하나**: `LOCAL_LLM_URL`·`LOCAL_LLM_URL_2` 처럼 같은 type·priority 로 묶인 복제본 중 **한 대만** 안 뜨면(연결 안 됨·응답 없음·5xx — 4xx 나 모델 목록이 아닌 응답은 설정 오류라 해당하지 않는다) 기동을 이어 가고 그 서버는 30초 차단된 채 시작한다(모델명은 검증하지 못해 설정값 그대로 — 서버가 다른 id 로 부르면 전체 id 로 적을 것). 둘 다 안 뜨거나, 닿은 서버에 모델이 없으면 여전히 기동 실패. 로컬 서버가 앱보다 늦게 뜨는 배포 순서 레이스가 있을 때만 `false`로 끌 것 — 그 경우 예전처럼 첫 채팅 요청이 실패한 뒤 다른 provider로 런타임 폴백된다(설정한 모델명이 그대로 실린다) |
| `LLM_ROUTING_MODE` | — | `COST_FIRST` | 기본 라우팅 모드 (`app.llm.default-routing-mode`) — `COST_FIRST`/`QUALITY_FIRST`/`PROGRESSIVE`/`LOCAL_ONLY`.<br>**폐쇄망·로컬 전용은 `LOCAL_ONLY`** 로 외부 프로바이더 호출을 원천 차단. `LOCAL_ONLY`로 설정하면 채팅 화면 사이드바의 라우팅 전략 드롭다운 자체가 사라진다(어떤 모드를 골라도 결과가 같으므로) — 상세는 [LLM_ROUTING.md §8](LLM_ROUTING.md#8-제약-및-주의사항) 참고 |
| `OPENAI_API_KEY` | — | — | OpenAI providers 사용 시 필요. 미설정 또는 빈 값이면 해당 providers 자동 비활성화. providers 설정에서 `${OPENAI_API_KEY}` 형태로 참조 |
| `OPENAI_BASE_URL` | — | `https://api.openai.com` | OpenAI 호환 엔드포인트 기본 URL. providers 설정에서 `${OPENAI_BASE_URL}` 형태로 참조. Azure OpenAI 등 호환 엔드포인트로 교체 가능 |
| `GEMINI_API_KEY1` | — | — | Gemini 1번 API 키 — `providers[3]`(gemini-flash-lite, NORMAL), `providers[6]`(gemma-4-31b, PREMIUM) 공유. 미설정 시 해당 providers 자동 비활성화. providers 설정에서 `${GEMINI_API_KEY1}` 형태로 참조 |
| `GEMINI_API_KEY2` | — | — | Gemini 2번 API 키 — `providers[4]`(gemini-flash, NORMAL), `providers[7]`(gemma-4-31b-2, PREMIUM) 공유. 미설정 시 해당 providers 자동 비활성화. providers 설정에서 `${GEMINI_API_KEY2}` 형태로 참조. `providers[6]`·`[7]`은 모델·role·priority(4)가 동일한 gemma-4-31b 2대로(이름은 `gemma-4-31b-1`/`-2` 로 **반드시 달라야** 한다 — 세마포어·서킷브레이커·토글이 이름을 키로 쓴다), 서로 다른 키를 씀으로써 PREMIUM 티어의 실질 처리량/쿼터를 두 배로 늘리는 로드밸런싱 쌍이다(§5.7 동일 우선순위 로드밸런싱) |
| `GEMINI_BASE_URL` | — | `https://generativelanguage.googleapis.com/v1beta/openai/` | Gemini API 엔드포인트 URL. 모든 Gemini providers가 `${GEMINI_BASE_URL}` 형태로 참조하므로 이 값 하나로 Gemini 전체 엔드포인트를 일괄 변경 가능 |
| `GEMINI_MODEL` | — | `gemini-3.5-flash-lite` | `providers[3]`(gemini-flash-lite)·`providers[4]`(gemini-flash)의 모델명 오버라이드 (`app.llm.providers[3]/[4].model`). 두 provider는 **기본값까지 같은 모델**(`gemini-3.5-flash-lite`)을 서로 다른 키(`GEMINI_API_KEY1`/`2`)로 부르는 로드밸런싱 쌍이라(둘 다 priority 2), 이 변수 하나로 둘을 함께 바꾼다. 서로 다른 모델을 쓰려면 이 변수 대신 `application.properties`에서 각 `providers[N].model` 줄을 직접 지정할 것 — 그러면 같은 priority 안에서 두 모델이 least-in-flight 로 번갈아 뽑힌다 |
| `EMBED_BASE_URLS` | — | `LOCAL_LLM_URL` 폴백 | 임베딩 전용 엔드포인트. **`;` 로 여러 주소**를 적을 수 있다(`http://gpu-a:1234/v1;http://gpu-b:1234/v1` — §6.21 E1: 같은 모델·차원의 복제본에 least-in-flight 로드밸런싱 + **장애 전환**, 닿지 않는 주소는 30초 건너뛰고 같은 호출을 다음 주소로. **모두 `EMBED_MODEL` 을 같은 차원으로 서빙해야 함** — 섞이면 벡터 인덱스 손상. 상세는 아래 "임베딩 병렬화"). 미설정 시 `LOCAL_LLM_URL` 사용. OpenAI 임베딩 사용 시 `https://api.openai.com` 등으로 독립 설정. 예전 이름 `EMBED_BASE_URL`(단일 주소)도 이 변수가 없을 때는 읽는다 |
| `EMBED_API_KEY` | — | `LOCAL_LLM_KEY` 폴백 | 임베딩 전용 API 키. 미설정 시 `LOCAL_LLM_KEY` 사용 |
| `EMBED_MODEL` | — | `text-embedding-nomic-embed-text-v1.5` | 임베딩 모델 식별자. **인덱싱 후 변경 금지** — 벡터 차원이 달라지면 기존 검색이 깨짐. 변경 시 전체 재인덱싱 필요 (chroma: 컬렉션 삭제 / sqlite-vec: `vec_embeddings` DROP — 차원이 DDL에 고정되며 `app.embedding.dimensions`도 함께 변경) |
| `EMBED_DIMENSIONS` | sqlite-vec 시 ✅ | — | **sqlite-vec 전용** — 임베딩 모델의 실제 출력 차원 (`app.embedding.dimensions`). vec0 테이블이 `FLOAT[dim]`이라 DDL에 고정 → 모델 실제 차원과 **정확히 일치**해야 함 (nomic-embed-text=768, bge-m3=1024, text-embedding-3-small=1536). chroma 모드에선 무시(빈값→null). sqlite-vec 모드에서 미설정 시 기동 실패(fail-fast) |
| `EMBED_MAX_CONCURRENT_BATCHES` | — | `1` | §6.21 E2 — 단일 문서 인덱싱 시 서브배치 병렬 임베딩 수(1=직렬, 기본 → 회귀 0). 대략 (엔드포인트 수 × 엔드포인트별 병렬)로 설정.<br>Chroma는 임베딩만 병렬(버퍼 후 1회 upsert), sqlite-vec는 병렬 임베딩 후 직렬 삽입(pool=1, §10.9.3 스트리밍 메모리 상한을 속도와 맞바꿈). 여러 파일 대량 인덱싱은 `INDEXING_MAX_FILES`로 이미 분산 |
| `VECTORSTORE_TYPE` | — | `chroma` | 벡터 스토어 백엔드 — `chroma` 또는 `sqlite-vec` (§3.1 "벡터 스토어 백엔드 선택" 참조) |
| `SQLITE_VEC_EXTENSION_PATH` | — | — | **sqlite-vec 전용** — 운영자가 제공하는 `vec0` 로더블 확장 절대경로 (suffix 생략 가능). 미설정 시 sqlite-vec 모드 기동 실패 |
| `SQLITE_VEC_ENTRYPOINT` | — | — | sqlite-vec 전용(선택) — `load_extension` 엔트리포인트 강제. 보통 불필요 |
| `CHROMA_HOST` | — | `http://localhost` | **chroma 전용** — Chroma 서버 호스트 (프로토콜 포함). Docker Compose 환경에서는 서비스명 `chroma`로 자동 지정됨 |
| `CHROMA_PORT` | — | `8001` | **chroma 전용** — Chroma 서버 포트. Docker Compose 환경에서는 `8000`으로 자동 지정됨 |
| `DATA_DIR` | — | `./data` | 문서 원본·이미지·변환 MD·SQLite DB 저장 루트 경로. Docker 실행 시 `/app/data`(볼륨 마운트 고정값)로 컨테이너 내부에 자동 설정됨 |
| `APP_TIME_ZONE` | — | `Asia/Seoul` | 사람이 읽는 서버의 시계 — 로그 시각(날짜·오프셋 포함, 예: `2026-10-14 13:52:03.905+09:00`)·감사 로그 파일 회전·정기 백업 시각(`DB_BACKUP_CRON`)·백업 파일 이름. DB·API 의 시각은 이 값과 무관하게 UTC 입니다. `TZ`/`-Duser.timezone`(JVM 시간대)과도 무관해서, TZ 설정 없는 컨테이너(UTC)에서도 이 시계로 찍고 돕니다. 틀린 값이면 기동이 멈춥니다. 재기동 필요 |
| `UPLOAD_MAX_TOTAL_SIZE` | — | `0` (무제한) | 배포 전체 저장 상한 (§6.15). `20GB`처럼 단위를 붙일 수 있고 단위 없는 숫자는 바이트. `DATA_DIR` 아래 `documents`+`converted`+`images` 합계로 검사하며, 초과 업로드는 413 `RAG-UP-002`. 재기동 필요 — 상세는 아래 "업로드 크기 제한" |
| `AUTH_ENABLED` | — | `true` | `false`로 설정하면 로그인 없이 실행 (no-auth 모드). 자세한 내용은 [§9.4](#94-인증-토글-no-auth-모드) 참조 |
| `DOMAIN` | — | `localhost` | Docker Compose의 `caddy` 컨테이너가 사용하는 도메인명. `localhost`이면 Caddy 로컬 CA 인증서 자동 생성. 운영 시 실제 도메인(예: `myrag.duckdns.org`)으로 변경 |
| `USE_CADDY_REVERSE_PROXY_HTTPS` | — | `true` | 세션 쿠키 `Secure` 플래그 제어 (`server.servlet.session.cookie.secure`). Caddy HTTPS 환경에서는 `true`(기본값). **HTTP 로컬 단독 실행 시 반드시 `false`로 변경** — `true` 상태에서 HTTP로 접근하면 쿠키가 전송되지 않아 로그인 불가 |

#### RAG 튜닝

| 변수 | 기본값 | 권장 범위 | 설명 |
|------|--------|----------|------|
| `CHUNK_SIZE` | `1500` | 300 ~ 2000 | 청크 크기 (문자 수). 작을수록 정밀, 클수록 문맥 풍부 |
| `CHUNK_OVERLAP` | `0` | 0 ~ CHUNK_SIZE × 0.25 | 청크 간 중복 (문자 수). 청크 경계 문맥 보완 전용. **기본값 0**은 관리자 매뉴얼 §3.3 문서 내보내기의 유일한 휴리스틱 단계(overlap 제거)를 애초에 불필요하게 만들고, 섹션 인식 분할이 이미 소제목·부모 헤딩 컨텍스트를 청크에 붙여 주므로 문자 단위 중복의 실익이 크지 않다는 판단 |
| `MIN_CHUNK_SIZE` | `500` | 50 ~ CHUNK_SIZE × 0.25 | 청크/섹션 병합 최소 길이 기준. DOCX·TXT·MD는 이 값 미만인 챕터(헤딩) 섹션을 다음 섹션과 병합하고(단 상위=부모 헤딩 방향은 금지), 슬라이딩 분할 뒤에도 이보다 작은 청크는 직전 청크로 뒤로 병합한다(§청킹 상세는 PIPELINE.md §6.4) |
| `CHUNK_SPLIT_GRANULAR` | `false` | true/false | 청크 분할 전략 (hot). `false`=크기 기준 병합(기본, 위 `MIN_CHUNK_SIZE` 적용). `true`=소제목 최대 분할 — `MIN_CHUNK_SIZE`를 무시하고 헤딩마다 분할하되 "제목+2문장 이내" 도입부만 하위 챕터와 통합, 표·코드 블록은 경계를 옮겨 보존, PPTX/PDF는 1슬라이드=1청크. **이미 인덱싱된 문서는 ↺ 재인덱싱해야 전환됨** — [§6.5](#65-청크-분할-전략-크기-기준-병합--소제목-최대-분할) |
| `SEARCH_TOP_K` | `10` | 2 ~ 15 | 벡터 검색 반환 문서 수. 높을수록 재현율↑, 토큰↑. **검증 호출에 실리는 발췌량(`topK × CHUNK_SIZE`)도 이 값이 정한다** — 그 호출이 이 앱 최대의 단일 요청이므로, n_ctx 가 좁으면 여기부터 낮춘다([§8 n_ctx 산정](#8-문제-해결)) |
| `SEARCH_SIMILARITY_THRESHOLD` | `0.3` | 0.0 ~ 0.75 | 청크 유지 최소 코사인 유사도. `0.0`=전체 수용. 기본 `0.3`은 **관련 있는 청크는 걸리지 않으면서 `topK`가 그만큼 요구했다는 이유만으로 딸려 오는 꼬리를 잘라내는** 수준의 바닥값이다. `VectorStoreProvider` 내부에 걸리므로 **벡터 축에만** 적용된다(큐레이션 축의 벡터 쪽도 함께 걸러지지만, BM25 축은 — 문서든 큐레이션이든 — 걸러지지 않는다) — 즉 이 값을 올리면 키워드 축의 상대 비중이 자동으로 올라간다. `SEARCH_RRF_KEYWORD_WEIGHT`와 **같은 단계에 함께 올리지 말 것**. 더 올릴 때(0.5~0.75)는 골든셋 recall 확인 후 적용. **통과한 청크가 `SEARCH_TOP_K` 의 70% 보다 적으면** 그 질의에서만 임계값을 **0.05 낮춰 한 번 더** 거른다(더 내려가지 않는다 · 이미 가져온 후보를 다시 거르므로 추가 조회 없음 · 큐레이션 축은 제외 — `LOGGING_LEVEL=DEBUG` 의 `[SEARCH] 유사도 임계값 … 로 낮춰` 줄) |
| `SEARCH_MULTIQUERY_ENABLED` | `true` | true/false | 검색 전 질의 다중 확장(LLM) 여부. `false`면 임계 경로 첫 LLM 콜 제거 |
| `SEARCH_MULTIQUERY_MIN_LENGTH` | `15` | 0 ~ 20 | 이 길이(trim) 미만 질의는 확장 생략. `0`=항상 확장. 짧은 키워드 질의 TTFT↓(§10.8.1) |
| `CHAT_MAX_QUESTION_CHARS` | `10000` | 500 ~ 100000 | 채팅 질문의 최대 글자 수(재기동). **실제 상한은 이것과 "이 모델의 창에 들어가는 길이" 중 작은 쪽**입니다(`QuestionLimit` — 입력 예산에서 고정 몫 2,000토큰과 문서 한 개를 뺀 값, 창을 모르면 이 값만). 500자를 넘는 질문은 검색에 전문이 아니라 짧은 검색 질의가 쓰입니다(관리자 매뉴얼 §9) |
| `SEARCH_HYBRID_ENABLED` | `true` | true/false | RRF에 BM25(FTS5) 키워드 축 추가(§10.7.2 — 이 플래그와 무관하게 `chunk_fts`는 항상 채워지므로 **활성화해도 기존 색인 문서 재인덱싱 불필요**, FTS5/하이브리드 검색 도입 이전에 색인된 아주 오래된 문서만 예외) |
| `SEARCH_RETRY_ESCALATE` | `true` | true/false | **재검색** 에스컬레이션 두 축을 한 플래그로 제어. ① 후보 풀 `candidateK = min(round(topK×(1+0.5×재검색횟수)), topK×3)` — 예전 ×2에서 낮췄다(재시도가 이제 자리를 비우므로). ② 최종 컷 `effectiveTopK = topK + 재검색횟수`, **단 검증 호출에 여유가 있을 때만** — 발췌가 잘리면 근거 판정이 `null`로 떨어져 재시도를 거듭할수록 판정을 잃는다. 세는 것은 재시도가 아니라 **재검색** 횟수다(근거 이탈 재시도는 검색을 건너뛴다). 이와 별개로 **응용(C) 모드는 최종 컷에 +3** 을 더한다(코드 상수 `ResponseMode.retrievalBoost`) — 컨텍스트 창을 알 때만, 검증 발췌 예산(발췌 글자 상한 32,000자 · 창에 들어가는 문서 수) 안에서만 들어가고 검색 뒤에는 꼬리부터 덜어낸다(창을 모르거나 작으면 0). N·S 는 0 재시도는 근거로 쓰이지 않은 하위 청크를 최대 1/3 **교체**하기도 한다 — PIPELINE.md §5.1 |
| `SEARCH_RERANK_ENABLED` | `false` | true/false | RRF 후 LLM 리랭킹 단계 (opt-in). **턴당 LLM 1콜 추가** → 정밀도↑/레이턴시 트레이드오프 |
| `SEARCH_CANDIDATE_MULTIPLIER` | `3` | 2 ~ 5 | LLM 리랭킹 전 후보 풀 크기. `topK × N`개 가져와 리랭킹 후 topK로 축소 |
| `RERANK_BASE_URLS` | _(비면 꺼짐)_ | URL | **교차 인코더 리랭커** 서버 주소 — **`;` 로 여러 주소**를 적으면(같은 리랭커의 복제본) 호출을 진행 중인 호출이 가장 적은 주소로 나누고, 닿지 못한 주소는 30초 건너뛰며 같은 호출이 다음 주소로 간다(예전 이름 `RERANK_BASE_URL` 도 이 변수가 없을 때는 읽는다)(`llama-server -m bge-reranker-v2-m3-Q8_0.gguf --reranking -b 2048 -ub 2048 …` — **`-ub 2048` 필수**: 기본 512 면 (질의+문서) 한 쌍이 512토큰을 넘는 호출이 500 으로 실패한다, 예: `http://localhost:28095`). 값이 있으면 `SEARCH_TOP_K × 2` 개 후보를 점수로 재순위하고 최종 컷은 `max(5, ⌈SEARCH_TOP_K × 2/3⌉)` — 둘 다 `SEARCH_TOP_K` 에서 파생하며 `/settings` 에 조회 전용으로 보인다(기본 10 이면 후보 20 · 최종 컷 7 — 컷이 5 로 너무 작아 2/3 로 올렸다). LLM 리랭킹(`SEARCH_RERANK_ENABLED`)을 대신한다. **재기동 필요.** 서버가 죽으면 리랭크를 건너뛰고 RRF 순서로 `SEARCH_TOP_K` 만큼 자른다. `/settings` LLM 라우팅 카드 하단의 리랭커 줄에 모델·주소와 접속 상태 배지가 보인다(관리자 매뉴얼 §2) |
| `RERANK_API_KEY` | _(비어 있음)_ | 비밀 | 선택. 리랭커로 `Authorization: Bearer <키>` 를 보낸다(`llama-server --api-key`, 원격 리랭크 API). 임베딩의 `EMBED_API_KEY` 와 같은 역할이며 비면 헤더를 싣지 않는다 |
| `RERANK_MODEL` | `bge-reranker-v2-m3` | 이름 | 재순위 요청의 `model` (llama-server 는 무시한다) |
| `RERANK_CONNECT_TIMEOUT_SECONDS` / `RERANK_TIMEOUT_SECONDS` / `RERANK_MAX_CONCURRENT` | `5` / `15` / `2` | 초 / 초 / 개 | 연결 타임아웃 / 한 호출의 읽기 타임아웃 / 동시에 나가는 호출 수 |
| `SEARCH_RERANK_MIN_SCORE` | `0.0` | -20 ~ 20 | **재검색 문턱**(핫, 교차 인코더가 켜져 있을 때만). 한 턴의 **첫** 검색에서 리랭크 1위 점수가 이 값 미만이면 LLM 이 새 검색어를 하나 만들어 다시 찾는다(검증 실패 재시도에서는 돌지 않는다). 점수는 모델의 원점수라 확률이 아니고 음수도 나오며, `bge-reranker-v2-m3` 으로 잰 값이라 모델을 바꾸면 다시 잰다. 재검색을 다 하고도 미만이면 답변 프롬프트의 `[경고]` 에 "관련도가 낮다"는 문장이 실린다. **짧은 후속 질문이 독립화(다시 쓰기)된 턴**은 첫 회차의 후보 풀에 사용자가 친 원문의 검색 후보도 합쳐 독립화한 질문 하나로 리랭크한다(LLM 호출 없이 검색 한 번 추가 — 독립화가 놓치는 청크를 원문이 건진다, SEARCH_EVALUATION §7.8) |
| `AGENT_ENABLED` | `false` | true/false | **채팅 Tool 선택**(관리자 매뉴얼 §10.1)을 켠다(재기동). 켜면 **표준(N) 모드 RAG 턴**마다 소형 LLM 호출이 **한 번** 늘고(분류와 병렬, 생각 끔·출력 예약 256, 라우팅은 분류기와 같은 COST_FIRST 고정) 모델이 `searchKnowledge`·`findRelatedDocuments`·`queryCatalog`·`analyzeImpact`(관리자는 `requestDocumentChange` 도)를 고른다. ‘고르지 않음·형식 오류·권한 없음·실패’는 전부 평소의 문서 검색으로 간다. **꺼져 있으면(기본) 추가 호출도 새 경로도 없어 채팅이 이전과 같다.** `/settings` 에는 없다. 켠 뒤에는 `/admin` 하단 **"Tool 호출 추적"** 카드(관리자 매뉴얼 §10.1)로 선택 분포·실행 성공률·지연·**폴백 비율**을 본다 |
| `MCP_SERVER_ENABLED` | `false` | true/false | **MCP 서버**(읽기 전용 Tool 공개, §MCP 서버) 켜기. 꺼져 있으면 엔드포인트·보안 체인·필터가 모두 없다. 재기동 필요 |
| `MCP_READ_API_KEY` / `MCP_ADMIN_API_KEY` | _(비어 있음)_ | 비밀, 16자 이상 | MCP API 키. **읽기 키**는 `getDocument` · `queryCatalog` 의 `reports.open`/`usage.summary` 를 뺀 Tool, **관리자 키**는 전부. 서로 달라야 하고, 짧거나 같으면 기동이 실패한다. 비운 키는 그 역할을 끄며 둘 다 비면 모든 요청이 401 |
| `RATE_LIMIT_MCP_PER_MINUTE` | `60` | 회 | MCP 엔드포인트 전용 IP 별 버킷. `/api/v1/**` 라서 두면 default(120)로 떨어지는 것을 막고, 키 추측 시도를 401 이전에 센다 |
| `SEARCH_MAX_ATTEMPTS` | `3` | 1 ~ 5 | 질문 하나가 돌릴 수 있는 **최대 검색 횟수**(첫 검색 포함, 핫). `1` = 다시 찾지 않는다. 검색이 하나 늘 때마다 LLM 한 번(새 검색어)과 리랭크 한 번이 더 들고, 점수가 낮은 턴에서만 돈다(독립화한 후속 질문의 원문 후보 풀 합치기는 이 횟수에 세지 않는다 — 첫 회차 안의 검색이다) |
| `SEARCH_TAG_CANDIDATE_MULTIPLIER` | `2` | 1 ~ 5 | 태그가 선택된 검색의 후보 풀 확대 배수. `candidateK = max(candidateK, topK × N)` — sqlite-vec에서 태그 엄격 필터 후 결과가 부족할 때 보정(§4.6) |
| `SEARCH_RRF_KEYWORD_WEIGHT` | `0.5` | 0.5 ~ 3.0 | 가중 RRF(Phase 7-A) — BM25 키워드 축 가중치. 벡터 축(MultiQuery 1~3개)은 항상 `1/축개수`로 그룹 정규화되므로 `1.0`이면 정규화된 벡터 그룹과 동일 비중이며, 기본값은 그 절반이다(근거는 [§7.2](#72-키워드-축-가중치를-05로-두는-이유-한영-혼재-코퍼스)). `SEARCH_HYBRID_ENABLED=false`면 키워드 축이 없어 무영향 |
| `SEARCH_RRF_K` | `60` | 20 ~ 100 | 가중 RRF(Phase 7-A) — RRF 순위융합 상수 k(원논문 기본값 60) |
| `SEARCH_CURATED_QA_ENABLED` | `true` | true/false | §10.10 — 큐레이션 Q&A를 RRF 축에 포함할지 여부. `false`면 해당 검색(벡터·BM25 **둘 다**)을 아예 실행하지 않음(비용 절감) |
| `SEARCH_CURATED_QA_WEIGHT` | `1.0` | 0.5 ~ 5.0 | §10.10 — 큐레이션 Q&A 축 가중치 (§10.11 에서 좋아요 축과 제안 축이 이 값 하나로 합쳐졌다 — 모든 항목이 같은 심사를 거치므로 신뢰 근거가 갈리지 않는다). 키워드축과 동일하게 그룹 정규화 없이 그대로 적용됨(벡터축 그룹은 항상 `1/축개수`). **기본 `1.0` = 정규화된 벡터 축 그룹과 동등** — 예전 기본값 `1.2`에서 질문과 크게 관련 없는 큐레이션 항목이 상위로 올라오는 것이 관측됐다. 이 축은 후보가 적어 웬만하면 자기 축에서 상위 랭크를 받는데, 거기에 가산점까지 주면 "이 축에 무엇이든 있으면 끌어올린다"에 가까워진다. **이 축은 이제 벡터와 BM25 를 함께 본다**(관리자 매뉴얼 §5.1) — 가중치는 여전히 하나지만 후보 풀에 등장할 확률 자체가 올라갔으므로, 큐레이션이 과하게 올라온다고 느껴지면 먼저 볼 값이 이것이다 |
| `SEARCH_CURATED_QA_MIN_SIMILARITY` | `0.5` | 0.0 ~ 1.0 | 큐레이션 축 벡터 절반의 최소 코사인 유사도 — `SEARCH_SIMILARITY_THRESHOLD` 위에 한 번 더 건다. 큐레이션 풀은 작아서 문턱만 넘으면 그 축의 1위가 되고, RRF 는 순위만 보므로 그 1위는 상위 문서와 비슷한 점수를 받는다. bge-m3 에서는 무관한 한국어 질문도 0.35~0.44 를 내서, 문서 임계값만으로는 큐레이션 1건이 골든셋 26문항 중 25문항의 상위 10개에 끼었다. 키워드로만 찾힌 큐레이션 항목도 질문의 내용어 둘(내용어가 더 적으면 전부)이 들어 있어야 받는다. `0` = 문서 임계값만. 핫 수정 가능 |
| `MAX_RETRY_COUNT` | `2` | 0 ~ 4 | 증거 부족 시 재검색 최대 횟수 |

> **검색이 0건이면 그 턴은 LLM 을 아예 부르지 않습니다.** 답변·검증 두 호출을 건너뛰고 `## 요약 / 문서에 관련 정보가 확인되지 않습니다.` 정형 응답을 그대로 내보냅니다(문구는 `chat.answer.no-documents` 메시지 키 — 번들에서 바꿀 수 있고 `## 요약` 헤더는 한/영 공통으로 두어야 합니다). 그 턴은 **`RS` + 미검증**으로 저장되며(대화 목록·`/admin` 에서 그 표기로 보입니다), 검색 튜닝(`SEARCH_TOP_K`·`SEARCH_SIMILARITY_THRESHOLD`·태그 스코프)이 지나치게 좁은지 판단하는 신호로 쓰기 좋습니다. 임계값을 올린 뒤 이 표기가 늘었다면 되돌릴 때입니다.

대화 컨텍스트 주입 길이는 `LLM_MAX_TOKENS × 0.5`(하한이 8,000 이라 최소 4,000자)로 자동 계산됩니다 — 기본값 기준 `12000 × 0.5 = 6000`자. 원문 그대로 보내는 폴백 경로(`MemoryService.getHistory()`)와 요약 캐시 경로(`ConversationSummarizerService.buildContext()`, §6.1) 모두 이 예산을 동일하게 지키도록 통일되어 있습니다.

#### 대화 메모리 / 요약 캐시 튜닝

| 변수 | 기본값 | 권장 범위 | 설명 |
|------|--------|----------|------|
| `MEMORY_FETCH_LIMIT_TURNS` | `10` | 5 ~ 200 | 폴백 경로(`SqliteMemoryRepository.getHistory()`)와 요약 대상 조회(`getRecentTurns()`, §6.1)에서 공통으로 적용되는 최근 turn 상한. **프롬프트에 원문으로 실리는 턴은 그 절반**(`HistoryPolicy.promptTurnCap()`, 기본 5턴) — 나머지는 요약이 담당합니다 |
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
| `MD_CORRECTION_DEFAULT_CODE_LANGUAGE` | `java` | `java` / `bash` / `sql` | MD 교정 LLM 이 **펜스 없는 코드·로그·CLI 출력**을 코드 블록으로 감싸면서 언어를 판단하지 못할 때 쓰라고 지시받는 언어 태그(`app.md-correction.default-code-language`, `MarkdownCorrectionService`). 이 셋 외의 값·빈 값은 `java` 로 떨어진다. 재기동 필요 |

#### 질의 경로 동시성 제어

인덱싱과 별개로, **채팅/질의 경로**(분류·답변·재검색 등)가 프로바이더별로 동시에 보내는 요청 수를 제어합니다. 상세 동작·적용 범위는 [§5.7](#57-동시성-제어-및-백프레셔)을 참고하세요.

| 변수 | 기본값 | 권장 범위 | 설명 |
|------|--------|----------|------|
| `LLM_DEFAULT_PROVIDER_CONCURRENCY` | `3` | 1 ~ 16 | 프로바이더별 동시 처리 상한 기본값(`app.llm.default-provider-concurrency`) — LLM 서버의 실제 `--parallel` 값과 일치시키는 것이 원칙. 개별 프로바이더는 `application.properties`의 `app.llm.providers[N].concurrency`로 오버라이드 가능 |
| `LLM_PERMIT_WAIT_TIMEOUT_SECONDS` | `60` | 5 ~ 120 | 동시성 슬롯 대기 상한(초, `app.llm.permit-wait-timeout-seconds`). 초과 시 `LLM_READ_TIMEOUT_SECONDS`(기본 600초)까지 기다리지 않고 즉시 HTTP 429 + `Retry-After` 응답 |

#### HTTP Timeout 튜닝

| 변수 | 기본값 | 권장 범위 | 설명 |
|------|--------|----------|------|
| `SSE_IDLE_TIMEOUT_SECONDS` | `300` | 30 ~ 1800 | 에이전트 그래프의 진행 신호(노드 전환·토큰·생각 중 신호·소스 준비)가 이 시간만큼 전혀 없으면 중단 (`app.sse-idle-timeout-seconds`). 매 신호마다 리셋되므로 느리지만 계속 응답 중인 로컬 LLM은 끊기지 않음. **분류·질의 확장·검증처럼 이벤트 없이 응답을 통째로 기다리는 블로킹 호출이 진행 중인 동안도 활동**으로 본다(`InFlightBlockingCalls` — 생각을 켜고 이 값을 낮춘 배포에서 멀쩡한 호출이 끊기던 문제). 진짜 멈춘 호출은 `LLM_READ_TIMEOUT_SECONDS` 가 끊는다 — 실제로 "멈춘" 요청을 감지하는 주 타임아웃 |
| `SSE_TIMEOUT_SECONDS` | `7200` | 600 ~ 14400 | 브라우저 ↔ 서버 SSE 연결의 절대 상한(활동 여부 무관, `app.sse-timeout-seconds`) — 응답이 영원히 끝나지 않는 극단적 상황에 대한 안전장치일 뿐, 평소엔 `SSE_IDLE_TIMEOUT_SECONDS`가 먼저 작동함 |
| `LLM_CONNECT_TIMEOUT_SECONDS` | `10` | 2 ~ 30 | LLM API 연결 타임아웃 (`app.llm.connect-timeout-seconds`). 이 시간 안에 TCP 연결을 못 맺으면 서버가 떠 있지 않은 것으로 보고 **그 프로바이더를 차단하고 다음 서버(예: `LOCAL_LLM_URL_2`)로 넘어간다**(블로킹 호출은 같은 호출 안에서, 채팅 답변 스트리밍은 첫 토큰 전이면). 꺼진 호스트를 빨리 포기하려면 LAN 서버 기준 3~5초로 낮춘다 |
| `LLM_READ_TIMEOUT_SECONDS` | `600` | 30 ~ 1800 | LLM API 응답 읽기 타임아웃 (`app.llm.read-timeout-seconds`). 읽기 타임아웃은 서버가 연결을 받았다는 뜻이라 **차단도 전환도 없이** 오류로 끝난다(느린 생성을 기다리다 끊은 서버를 막지 않으려는 의도) — 멈춘 서버가 있으면 이 값만큼 기다린다 |
| `EMBED_CONNECT_TIMEOUT_SECONDS` | `10` | 2 ~ 30 | 임베딩 API 연결 타임아웃 (`app.embedding.connect-timeout-seconds`) |
| `EMBED_READ_TIMEOUT_SECONDS` | `180` | 30 ~ 600 | 임베딩 API 응답 읽기 타임아웃 (`app.embedding.read-timeout-seconds`) |
| `CHROMA_CONNECT_TIMEOUT_SECONDS` | `5` | 1 ~ 15 | Chroma API 연결 타임아웃 (`app.chroma.connect-timeout-seconds`) |
| `CHROMA_READ_TIMEOUT_SECONDS` | `120` | 10 ~ 300 | Chroma API 응답 읽기 타임아웃 (`app.chroma.read-timeout-seconds`) |

#### 임베딩 사용량 추적

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `EMBED_USAGE_FALLBACK_ENABLED` | `true` | 임베딩 사용량은 `llm_usage`에 `embed`로 채팅과 분리 집계되어 `/llm-usage`에 별도 카드로 표시됩니다(§5.5, §10).<br>임베딩 서버가 응답에 토큰 사용량을 반환하지 않으면(로컬 llama-server 등 흔함) 입력 텍스트 길이 근사(chars/4)로 대체 기록합니다. `false`로 설정하면 근사 대신 `0`을 기록합니다. 근사 경로 진입 시 서버 로그에 경고가 **최초 1회만** 출력됩니다 |
| `EMBED_MAX_CHUNK_CHARS` | `0` (비활성) | 청크 1개의 **문자 수 하드 상한**. 임베딩 서버가 `input (N tokens) is too large ... (current batch size: 512)`처럼 배치/토큰 한계로 청크를 거부할 때 사용합니다.<br>이 값을 넘는 청크는 (의미 단위 청킹이 끝난 뒤) 줄 경계에서 **강제 재분할**되어 서버 한계를 넘지 않도록 보장합니다. 한국어·코드는 대략 1토큰/문자이므로 512토큰 배치라면 `~450` 정도가 안전. **먼저 서버 배치를 키우는 것을 권장**(아래 §8 참조)하고, 이건 최후의 안전장치로 사용하세요 |

#### 임베딩 병렬화 (§6.21 E1~E3)

임베딩 처리량을 여러 엔드포인트·병렬 서브배치로 확장한다. 셋 다 **기본 비활성(회귀 0)**인 opt-in이다.

- **E1 — 다중 엔드포인트 로드밸런싱** (`EMBED_BASE_URLS` 에 `;` 로 나열): 같은 임베딩 모델을 여러 서버/포트(예: N개 GPU)에 띄우고 `A;B;C` 로 나열하면 `LoadBalancingEmbeddingModel`이 요청마다 잔여 in-flight가 가장 적은(least-in-flight) 엔드포인트로 보낸다(LLM의 [§5.7](#57-동시성-제어-및-백프레셔) 로드밸런서와 동일 개념). **모든 엔드포인트는 `EMBED_MODEL`을 동일 차원으로 서빙해야 한다** — 다른 모델을 섞으면 벡터가 비교 불가라 인덱스가 깨진다.
  - **장애 전환**: 엔드포인트에 **닿지 못하면**(연결 거부·연결 타임아웃·DNS·경로 없음) 그 엔드포인트를 30초 후보에서 빼고 같은 호출을 다음 엔드포인트로 다시 보낸다(로그 `[EMBED-FAILOVER]`). 전부 막혔으면 가장 먼저 풀리는 하나만 한 번 시도한다 — 서버가 돌아오면 차단을 기다리지 않고 바로 낫는다. HTTP 5xx(예: llama.cpp 의 "input is too large")·읽기 타임아웃은 **넘기지 않는다** — 요청 자체의 문제라 다른 엔드포인트도 같은 이유로 거절하고, 넘기면 멀쩡한 서버를 차례로 막는다.
  - **채팅 로컬 서버를 두 대(`LOCAL_LLM_URL_2`)로 이중화했다면 여기에도 다른 서버를 적을 것** — 임베딩은 LLM 라우터 밖이라 채팅의 장애 전환을 받지 못한다. `EMBED_BASE_URLS` 이 비어 `LOCAL_LLM_URL` 로 떨어진 채 그 서버가 죽으면, 채팅 모델은 넘어가도 RAG 질문은 질문 임베딩에서 실패한다. 이 구성을 보면 기동 로그가 `[EMBED CONFIG]` 경고를 남긴다(앱이 `LOCAL_LLM_URL_2` 에서 자동으로 만들지 않는 이유: 두 서버가 같은 임베딩 모델·차원을 띄웠다는 보장이 없다).
- **E2 — 병렬 서브배치 임베딩** (`EMBED_MAX_CONCURRENT_BATCHES`, 기본 1): 한 문서를 인덱싱할 때 토큰 단위 서브배치들을 동시에 임베딩한다. E1과 결합하면 **단일 대용량 문서**도 여러 엔드포인트를 동시에 채운다. 대략 `(엔드포인트 수 × 엔드포인트별 --parallel)`로 설정.
  - **chroma**: 임베딩만 병렬화하고 원래대로 마지막에 1회 upsert → 메모리 프로파일 불변, 저위험.
  - **sqlite-vec**: 병렬 임베딩 후 삽입은 직렬(SQLite `pool=1`). §10.9.3 스트리밍 삽입(서브배치 단위 메모리 상한)을 포기하고 문서 전체 임베딩을 잠깐 메모리에 들고 있게 되는 속도↔메모리 트레이드오프를 아는 상태에서만 켠다(임베딩 벡터는 청크당 수 KB라 실무 부담은 작음).
  - **여러 파일 동시 인덱싱**은 E2 없이도 `INDEXING_MAX_FILES`가 파일 단위로 E1 엔드포인트에 분산하므로, E2는 주로 "큰 파일 하나"를 빠르게 처리할 때 이득이 크다.
- **E3 — 배치 토폴로지** (배포 권고, 코드 아님): 소형 LLM(§6.21)·임베딩 서버·대형 LLM을 **서로 다른 장비/포트**에 두면 co-located GPU/CPU 경합이 줄어 임베딩 스루풋이 간접적으로 오른다. 단일 장비라면 각 서버의 `--parallel`·배치 크기 합이 장비 용량을 넘지 않게 조정한다.

**설정 예 (임베딩 서버 2대)**:
```properties
# 같은 nomic-embed 모델을 2대(다른 포트/장비)에 서빙
app.embedding.base-url=http://gpu-a:1234/v1;http://gpu-b:1234/v1
app.embedding.model=text-embedding-nomic-embed-text-v1.5
# 단일 문서도 두 서버를 동시에 쓰도록 병렬 서브배치(2대 × 서버별 병렬 2 = 4)
app.embedding.max-concurrent-batches=4
```
환경변수로는 `EMBED_BASE_URLS=http://gpu-a:1234/v1;http://gpu-b:1234/v1`, `EMBED_MAX_CONCURRENT_BATCHES=4`.

> **`INDEXING_MAX_LLM`과의 관계**: 둘 다 인덱싱 파이프라인의 동시성 제한이지만 서로 다른 단계·리소스를 게이팅하는 완전히 독립적인 세마포어라 직접적인 종속 관계는 없다. `INDEXING_MAX_LLM`(`app.indexing.max-concurrent-llm-calls`)은 키워드/컨텍스트 추출·MD 교정·TXT 구조화·Vision 이미지 설명 등 **채팅형 LLM 호출**을 제한하며, 소비자마다 자기 세마포어를 만들어 씀(공유 풀 아님). `EMBED_MAX_CONCURRENT_BATCHES`는 **임베딩** 서브배치 호출만 제한한다. 같은 문서를 인덱싱하는 동안 키워드 추출은 `INDEXING_MAX_LLM`만큼, 그 문서의 임베딩 서브배치는 `EMBED_MAX_CONCURRENT_BATCHES`만큼 각자 병렬로 동작하며, 서로 다른 백엔드 서버(LLM 서버 vs 임베딩 서버)를 겨냥하므로 한쪽 값이 다른 쪽 상한이나 동작에 영향을 주지 않는다. 튜닝도 각각 독립적으로: `INDEXING_MAX_LLM`은 LLM 서버의 `--parallel`에, `EMBED_MAX_CONCURRENT_BATCHES`는 `(임베딩 엔드포인트 수 × 엔드포인트별 병렬)`에 맞춘다.

#### 쿼리 임베딩 캐시 (Phase 7-A)

| 변수 | 기본값 | 권장 범위 | 설명 |
|------|--------|----------|------|
| `SEARCH_QUERY_EMBED_CACHE_ENABLED` | `true` | true/false | 정규화된 질의 텍스트 → 임베딩 벡터를 Caffeine 인메모리 캐시에 저장해 반복·유사 질문의 임베딩 왕복을 생략합니다. 캐시 히트 시 `embed` usage도 기록되지 않습니다(실제 호출이 없었으므로) |
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
| `LLM_MAX_TOKENS` | `12000` | 8000 ~ 64000 | 단일 진실 소스(`app.llm.max-tokens`)로 통일되어, 이 값을 바꾸면 **아래 세 곳 모두**가 함께 움직입니다: (1) **블로킹 LLM 응답 토큰 상한** — 인덱싱·분류·키워드·Direct 블로킹 호출에 적용(스트리밍 채팅 답변은 의도적으로 미적용, SSE 타임아웃이 폭주 방지). (2) **대화 컨텍스트 문자 예산**(`MemoryService`, `×0.5`로 히스토리 예산 산출). (3) **MD 교정 섹션 크기**(`MarkdownCorrectionService`) — §6.26 이후로는 **상한**입니다. 프로바이더 창을 알면 거기서 나온 값과 비교해 작은 쪽을 씁니다.<br>세 곳 모두 `app.llm.max-tokens` 하나만 읽습니다.<br>**⚠️ 기본값 `12000`은 32k 이상 컨텍스트를 전제합니다** — 이 값은 출력 상한인 동시에 (2)를 통해 **입력**(히스토리 6,000자)도 키우므로 `n_ctx` 양쪽을 함께 압박합니다. 하한이 `8000`이라 **8k~10k 모델은 지원하지 않습니다**(§8 「검증 배지가 아예 사라짐」의 산정표).<br>**두 갈래는 이 값을 통째로 쓰지 않습니다** — `max_tokens`는 상한이 아니라 **예약**이라(서버가 `프롬프트 + max_tokens ≤ n_ctx`를 검사) 쓰지도 않을 큰 값은 그만큼 입력 자리를 없앱니다. ① **검증 호출**은 자체 상한 **2,048토큰**(`AnswerService`) — 응답이 JSON 몇 필드인데 전체가 예약되면 좁은 컨텍스트에서 `n_ctx`를 넘기는 것은 근거가 아니라 그 예약입니다. ② **인덱싱 호출**은 작업 크기에서 파생(`IndexingOutputCap`) — 재작성(MD 교정·txt→md)은 입력 추정 × 1.5, 고정 출력(키워드+맥락·이미지 설명)은 이 값의 비율. 예전에는 인덱싱이 전체를 예약해, 창 20,480 · 이 값 10000 배포에서 MD 교정이 컨텍스트 초과로 실패했습니다(같은 프로퍼티가 (3) 섹션 크기까지 정하므로 입력과 예약이 함께 커집니다). 둘 다 이 값에서 파생되고 이 값으로 잘리므로 **내리면 전부 함께 내려갑니다**.<br>**응답 모드(S/N)와의 관계**: (1)의 실제 상한은 이 값 그대로가 아니라 `ResponseMode`별 비율(S 15% / N·C 70%)과 글자수 하한(S 2,000 / N·C 5,000자) 중 **큰 값**이며, 다시 이 값 자체로 잘립니다(`min`). 전환점이 모드마다 달라 — S는 13,334, N·C는 7,143 — **기본값 12,000에서는 S가 하한(2,000), N·C가 비율(8,400)** 입니다. **어느 항이 적용 중인지는 `/settings` 생각 수준 카드의 답변 사이트 계산 근거에서 바로 확인할 수 있습니다**(`2,000 (최소 보장)` / `8,400 (상한의 70%)` — 하한이 8,000 이라 모든 모드의 요구가 그보다 작아 `설정 상한` 항은 나오지 않습니다). ⚠️ 이 값은 **폭주를 막는 안전판이지 목표 분량이 아닙니다** — 실제 분량은 모드별 시스템 프롬프트가 정하고(숫자를 말하는 건 S뿐 — RAG 답변 `prompt.answer.system.s`는 "1,000자 이내", 검색을 건너뛰는 Direct 답변 `prompt.direct.system.s`는 "1,500자 이내". Direct 쪽이 느슨한 이유는 인용할 문서 발췌가 없어 답변이 스스로 풀어 써야 하기 때문이며, 한/영 번들이 같은 숫자를 말하는지는 `ResponseModeSystemPromptTest`가 고정한다. N은 숫자 없음), 게다가 스트리밍 채팅 답변에는 적용되지 않습니다. 상세는 [PIPELINE §3.1](PIPELINE.md) |
| `LLM_TEMPERATURE` | `0.2` | 0.0 ~ 0.3 | **일반/RAG 대화형 호출**(분류·답변·근거평가·재순위)의 무작위성 제어(`app.llm.temperature`). `0.0`은 결정적 답변, 기본 `0.2`, 높을수록 다양·창의적. `[0.0, 0.3]`으로 clamp.<br>**핫 수정 가능** — `/settings`에서 재기동 없이 다음 호출부터 반영(`ClassifierService`/`AnswerService`/`RerankerService`가 매 호출 재조회). 프로바이더 빈 생성 시점에도 고정되는데, 이는 모델 주위에 자체 `ChatClient`를 구성해 호출별 오버라이드를 받을 수 없는 프레임워크 내부 호출(예: 멀티쿼리 확장)을 위한 기동 시점 폴백 값으로만 쓰입니다 — 그런 호출은 재기동해야 변경이 반영됩니다.<br>인덱싱/백그라운드 호출(키워드 추출·MD 교정 등)에는 적용되지 않습니다 — 아래 `LLM_INDEXING_TEMPERATURE`로 분리되어 있습니다 |
| `DIRECT_LLM_TEMPERATURE` | `0.5` | 0.0 ~ 1.0 | **Direct(meta) 응답 전용** temperature(`app.llm.direct-temperature`) — 인사·잡담 등 RAG를 안 쓰는 직접 응답은 약간의 다양성이 자연스러워 일반 temperature와 분리(§6.18). `[0.0, 1.0]`으로 clamp. **핫 수정 가능** — `/settings`에서 재기동 없이 다음 Direct 호출부터 반영(`DirectAnswerService`가 매 호출 재조회) |
| `LLM_INDEXING_TEMPERATURE` | `0.0` | 0.0 ~ 0.1 | **인덱싱/백그라운드 전용** temperature(`app.llm.indexing-temperature`) — 키워드 추출, MD 교정, txt→md 구조화, 이미지 설명/분류, 스레드 제목 생성, 대화 요약 등 모든 ungated 백그라운드 호출에 적용. 대화형이 아닌 추출/분류 작업이라 `LLM_TEMPERATURE`/`DIRECT_LLM_TEMPERATURE` 값과 무관하게 결정적으로 유지하려고 분리했습니다. `[0.0, 0.1]`으로 clamp — 출력이 파싱되는 추출 작업이라 표본추출의 다양성이 이득이 아니라 결함이고, 0에서 반복에 빠지는 로컬 모델을 깨울 만큼만 열어 둡니다. 환경변수로 더 큰 값을 줘도 이 상한에서 잘립니다. **핫 수정 가능** — `/settings`에서 재기동 없이 다음 호출부터 반영(각 서비스가 매 호출 재조회) |
| `CREATIVE_LLM_TEMPERATURE` | `0.7` | 0.0 ~ 1.0 | **응답 모드 C(응용) 전용** temperature(`app.llm.creative-temperature`) — 검색된 문서를 **재료로** 새 코드·설정을 만들어내는 모드라 표본추출의 다양성이 필요합니다. 일반 temperature와 분리한 이유는 그쪽 clamp 상한이 **0.3**이라 창의 생성이 원천 봉쇄되기 때문입니다(§6.24). `[0.0, 1.0]`으로 clamp. **핫 수정 가능** — `/settings`의 "LLM 튜닝"에서 재기동 없이 다음 호출부터 반영되며, 블로킹·스트리밍 **양쪽**에 적용됩니다. C 모드는 채팅 입력창의 **S/N/C** 토글에서 고를 수 있고, **Direct(잡담) 토글과 함께 쓸 수 없습니다** — Direct 를 켜면 C 버튼이 비활성화되고 선택 중이었다면 N 으로 되돌아갑니다(서버도 같은 규칙을 독립적으로 적용하므로 손으로 만든 요청도 N 으로 강등됩니다). 이 모드를 배포 전체에서 닫으려면 아래 `CREATIVE_MODE_ENABLED`를 쓰세요 |
| `CREATIVE_MODE_ENABLED` | `true` | true/false | **응답 모드 C(응용)를 아예 열지 말지**(`app.llm.creative-mode-enabled`). 위 `CREATIVE_LLM_TEMPERATURE`가 "C가 **어떻게** 답할까"라면 이 값은 "C를 **열어 둘까**"입니다 — C는 검색된 문서 밖의 내용(예제 코드·설정)을 생성하는 유일한 모드라, 배포처에 따라 처음부터 제공하지 않는 편이 맞을 수 있습니다.<br>`false`면 ① 채팅 입력창의 **C 버튼이 렌더되지 않고**(비활성 회색 버튼이 아니라 아예 사라집니다), ② 그럼에도 도착한 요청(REST `POST /api/v1/chat`, 손으로 만든 폼 POST)은 서버가 **N으로 강등**해 저장까지 N으로 남기며, ③ **이미 C로 답한 과거 턴의 `[C]` 표기와 검증 배지는 그대로 유지**됩니다(끄는 것은 "앞으로 고를 수 있는가"이지 "예전에 그렇게 답했는가"가 아닙니다).<br>**핫 수정 가능** — `/settings`의 "LLM 튜닝"에서 재기동 없이 다음 질문부터 반영됩니다 (열려 있던 채팅 화면은 새로고침해야 버튼이 사라집니다). 기본값은 `true`로, 이 스위치가 생기기 전과 동작이 같습니다 |
| `LLM_SHRINK_STEP` | `1` | 1 ~ 10 | **컨텍스트 초과로 프롬프트가 거절되면 재시도마다 문서를 이만큼씩 덜어냅니다**(`app.llm.shrink-step`, §6.26-9 — 이전 대화 단계는 한 번에 하나씩, 축소 사다리 §6.30). 사전 예산이 이미 창에 맞춰 놓은 뒤라 여기까지 오는 초과는 대개 아슬아슬해서, 기본값이 **1**입니다 — 한 개만 덜어내면 들어갈 자리에서 반을 자르면 근거의 절반이 사라집니다. 재시도는 5회까지이므로 **이 값 × 5 가 도달 가능한 최대 축소폭**이고, 거기서도 안 되면 `RAG-LLM-003`. 로그 `[SHRINK]` 가 한 턴에 여러 번 보이면 2~3 으로 올려 왕복을 줄일 수 있지만, 근본 해결은 `SEARCH_TOP_K` 를 낮추거나 서버 `--ctx-size` 를 키우는 쪽입니다. 핫 수정 가능 |

#### 로그 레벨

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `LOGGING_LEVEL` | `INFO` | 앱 전체 로그 레벨을 일괄 설정합니다. `com.example.ragagent`(앱 코드), `org.springframework.ai.openai`(Spring AI 내부), `reactor.netty.http.client`(HTTP 와이어 로그) 세 로거가 이 값을 공유합니다.<br>• `INFO` — 인덱싱 시작/완료, 동기화 결과, 프로바이더 등록 이벤트만 출력 (운영 환경 기본값)<br>• `DEBUG` — 에이전트 흐름(Classifier→Retrieval→Answer→Critic), 프로바이더 라우팅 결정, LLM 요청 curl 재현 명령 출력. 질문 1건마다 `[PROMPT]` 두 줄(**답변**·**검증**)이 구성 요소별 토큰·바이트로 찍혀 무엇이 창을 채웠는지 바로 보입니다 — 문서가 크면 `SEARCH_TOP_K`/`CHUNK_SIZE`, 이력이 크면 `SUMMARY_RECENT_RAW_TURNS`/`MEMORY_FETCH_LIMIT_TURNS` 를 조절합니다(검증 줄이 답변 줄보다 큰 것이 정상입니다 — 답변 전문과 응답 스키마가 더 얹히므로). **프롬프트 원문·검색 문서·대화 이력이 포함**되므로 운영 환경에서는 사용 금지<br>• `TRACE` — HTTP body 바이트 전체 출력 (SSE 청크 포함, 매우 방대). 짧은 디버깅 후 즉시 해제 권장<br>재시작 없이 레벨을 바꾸려면 Actuator 사용 → [§8 런타임 레벨 변경](#8-문제-해결) 참조 |
| `SPRING_SECURITY_LOGGING_LEVEL` | `WARN` | Spring Security 로그 레벨 (`logging.level.org.springframework.security`). `DEBUG`로 변경하면 인증 필터·세션 생성·권한 결정 과정이 상세하게 출력됨. 인증 이슈 디버깅 시에만 임시 사용 권장 |

#### 설정 예시

**로컬 LLM 전용 (LM Studio)**:
```env
LOCAL_LLM_URL=http://localhost:1234/v1
LOCAL_LLM_KEY=lm-studio
LOCAL_LLM_MODEL=google/gemma-4-e4b
EMBED_MODEL=text-embedding-nomic-embed-text-v1.5
# EMBED_BASE_URLS, EMBED_API_KEY 미설정 시 LOCAL_LLM_URL/KEY 자동 사용
```

**OpenAI 전용 (로컬 LLM 없음)**:
```env
OPENAI_API_KEY=sk-proj-...
EMBED_BASE_URLS=https://api.openai.com
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
> 판단합니다 — `supports()`에서 능력을 파생하므로 위 세 타입과 항상 일치합니다.

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
>
> **답변·검증 LLM 에는 이미지 파일 경로와 페이지 마커를 보내지 않습니다**: 청크 본문의 `[이미지: images/…/s4_img1.png]` 는
> 프롬프트에서 `[이미지]` 로 줄고(바로 뒤의 `[이미지 설명: …]` 은 그대로 남아 어느 이미지의 설명인지 이어집니다),
> `[페이지: N]` 만 있는 줄은 빠집니다(`MarkdownNoiseNormalizer.normalizeForPrompt()` — 답변 프롬프트 조립과 검증 발췌
> 두 자리에서만 씁니다). 경로·페이지 번호는 모델이 쓸 수 없는 인덱싱용 표지라 프롬프트 토큰만 차지하기 때문입니다.
> **저장된 청크 본문, 화면 표시, 임베딩·키워드 검색 입력, 청크 해시는 바뀌지 않으므로 재인덱싱이 필요 없고** 재사용 답변이
> "수정됨"으로 뒤집히지도 않습니다. 코드 블록 안의 같은 문자열과 문장 속 `[페이지: 3]` 언급은 건드리지 않습니다.

#### 소제목 숫자 생성 (`addHeadingNumbers`)

프로퍼티가 아니라 문서 업로드 화면의 "소제목 숫자 생성" 체크박스로 제어되는 요청 단위 옵션입니다. 켜면 LLM
섹션 교정이 모두 끝난 뒤 H2~H6 헤딩에 계층적 번호(`## 1.1 제목`처럼)를 매깁니다
(`MarkdownCorrectionService.addHierarchicalHeadingNumbers()`).

> **코드 블록 언어 태그는 이 옵션과 무관합니다**: 라벨 없는 코드 블록의 언어 추론은 체크박스를 꺼도, PPTX
> 에서도 항상 실행됩니다.

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
> PPTX)는 재인덱싱해도 새로 번호가 붙지 않습니다. 자세한 내용은 [관리자 매뉴얼 §4.6 주의사항](ADMIN_MANUAL.md#46-주의사항)과
> [PIPELINE.md §6.3](PIPELINE.md#63-docx--md--임베딩-db-저장-상세-이미지-포함) 참고.

#### LLM 교정 건너뛰기 (`skipLlmCorrection`, MD 전용)

업로드 화면의 "LLM 교정 건너뛰기" 체크박스(요청 파라미터 `skipLlmCorrection=true`, REST
`POST /api/v1/documents` 도 동일). **`.md` 파일에만 적용**되며 다른 형식에서는 무시됩니다 — DOCX/PPTX/PDF/TXT
의 마크다운은 변환기가 만든 것이라 LLM 교정을 거쳐야 쓸 만해지지만, `.md` 는 사람이 쓴 문서라 모델이
문장을 손대는 것을 원치 않는 경우가 있기 때문입니다. 화면에서는 선택된 파일에 `.md` 가 없으면 체크박스가
비활성화됩니다(`syncSkipLlmCheckbox()`).

켜면 **LLM 섹션 재작성만** 건너뜁니다. 결정적 정리 패스는 그대로 돕니다 — 닫는 펜스 언어 태그 제거·미닫힘
펜스 치유(`fixClosingFences`), 코드 블록 언어 태그 추론(`normalizeCodeBlocks`), "소제목 숫자 생성"이 켜져
있으면 번호 매기기, 빈 줄/마커 정리(`postProcessMarkdown`). "이미지 설명 추가"는 자기 체크박스를 그대로
따릅니다(Vision 호출은 이 옵션과 별개). 교정본은 평소처럼 `converted/{docId}_corrected.md` 에 저장됩니다.

> **코드 블록 문제는 인덱싱 전에 알려 줍니다** — `/admin` 재인덱싱의 사전 점검과 같은 규약. LLM 교정은
> 작성자가 잘못 쓴 펜스를 문맥을 보고 고쳐 주는 유일한 패스라, 이를 건너뛰면 그 결함이 결정적 패스로
> 그대로 넘어갑니다. 그래서 `.md` + 이 옵션이면 서버가 임시 저장본에서 `findFenceProblems()` 를 먼저 돌리고,
> 문제가 하나라도 있으면 **아무것도 저장하지 않은 채** `409` 로 목록(`status=preflight_warnings`, 줄 번호 +
> 종류 `tagged_closer`/`unclosed`/`mid_line` + 설명)을 돌려줍니다. 화면은 이를 확인 대화상자로 보여 주고
> [확인]이면 `force=true` 로 다시 보냅니다(REST 도 같은 파라미터). 진행하면 `unclosed`·`tagged_closer` 는
> `fixClosingFences` 가 늘 하던 대로 치유되고, `mid_line` 은 그대로 남으며 그 파일에는 언어 태그가 붙지
> 않습니다(`normalizeCodeBlocks` 가 펜스 짝을 확정할 수 없어 전체를 건너뜀). 진행한 사실은
> `[INDEX] {파일} — 코드 펜스 문제 N건을 안고 진행합니다` 경고 로그로 남습니다. 409 시점에는 파일이 디스크에
> 없으므로 다음 디렉터리 동기화가 그 파일을 기본 옵션으로 몰래 인덱싱하는 일도 없습니다.

#### LLM 응답 파라미터

> **temperature와 최대 출력 토큰**은 각각 `LLM_TEMPERATURE`, `LLM_MAX_TOKENS` 환경변수로 설정할 수 있습니다(§6.18로 실제 적용되도록 수정됨). **`LLM_MAX_TOKENS`도 §6.26 A6 이후 핫 수정 대상입니다**(범위 8,000~64,000) — 단순한 출력 상한이 아니라 대화 이력 예산(×0.5)·MD 교정 섹션 크기·인덱싱 출력 예약·컨텍스트 입력 예산이 전부 여기서 파생되므로, 컨텍스트 압박을 조정할 때 가장 크게 듣는 손잡이입니다. LLM temperature는 네 가지 모두 `/settings`에서 핫 수정 가능합니다 — 일반/RAG temperature(`LLM_TEMPERATURE`, 기본 0.2, 범위 **0.0~0.3**), Direct(잡담) 전용 `DIRECT_LLM_TEMPERATURE`(기본 0.5, 범위 **0.0~1.0**), 인덱싱/백그라운드 전용 `LLM_INDEXING_TEMPERATURE`(기본 0.0, 범위 **0.0~0.1**), 응답 모드 C(응용) 전용 `CREATIVE_LLM_TEMPERATURE`(기본 0.7, 범위 **0.0~1.0**). → [§3.2 LLM 응답 파라미터](#32-환경변수-전체-목록) 참조

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
> 그쪽이 원본보다 큽니다. SQLite DB 파일(`memory.db`)·감사 로그·Chroma 볼륨은 업로드가 만드는 것이 아니라 제외됩니다.
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
| `app.rate-limit.upload-per-minute` | `RATE_LIMIT_UPLOAD_PER_MINUTE` | `10` | **새 파일을 받는 쓰기 요청** — `POST /ui/documents/upload`, `POST /api/v1/documents`, 그리고 지식 제안 본문 이미지 `POST /curated/submissions/images`. 마지막 것이 여기 있는 이유는 인증 없이 부를 수 있는 유일한 바이너리 쓰기 경로라서입니다(저장 상한은 기본이 무제한입니다). 분당 10은 제안 하나에 담을 수 있는 이미지 수와 같은 값이라 정상적인 작성 한 번은 그대로 지나갑니다. 문서 화면 조회·목록 갱신·내보내기·태그 편집은 `default` 버킷을 씁니다 |
| `app.rate-limit.sync-per-minute` | `RATE_LIMIT_SYNC_PER_MINUTE` | `3` | `/documents/sync` 경로 — 분당 요청 수 |
| `app.rate-limit.image-per-minute` | `RATE_LIMIT_IMAGE_PER_MINUTE` | `300` | `/images/` 경로 — 분당 요청 수 |
| `app.rate-limit.default-per-minute` | `RATE_LIMIT_DEFAULT_PER_MINUTE` | `120` | 그 외 경로 기본값 |

초과 시 429 응답 + `Retry-After: {초}` 헤더 + `{"errorCode":"RAG-RATE-001","message":"..."}` body.

> **버킷을 나누는 기준(누가 한 요청인가)**: 로그인 사용자는 계정 id, 그 밖에는 클라이언트 IP입니다
> (IP 판정은 §9.4.3의 `TRUST_FORWARDED_FOR`를 따릅니다). **`AUTH_GUEST_IDENTITY=shared`의 공유 게스트는
> 사용자로 세지 않고 IP로 떨어뜨립니다** — 그 전략에서는 모든 방문자가 같은 게스트 principal을 받고
> 그것이 "인증됨"으로 잡혀 배포 전체가 버킷 하나를 나눠 쓰게 되기 때문입니다(방문자 한 명이 한도를
> 소진하면 나머지 전원이 429). `ip`/`cookie`/`hybrid`는 이미 방문자별 id를 주므로 그대로 사용자 키를 씁니다.

#### 감사 로그 (`app.audit.*`)

민감 작업을 `data/audit/audit.log`에 JSON Lines 형식으로 기록. 현재 기록되는 액션은 다음과 같습니다.

| 액션 | 언제 |
|---|---|
| `document.upload` / `.delete` / `.sync` / `.export` / `.tags_update` / `.display_name_update` | 문서 관리 |
| `thread.delete` / `thread.routing-mode` | 사용자가 자기 대화를 삭제·라우팅 모드 변경 (삭제 시 회수된 큐레이션 수 포함) |
| `turn.delete` / `turn.feedback` / `turn.image.exclude` / `turn.source.exclude` | 턴 단위 조작 |
| `curated.submission.create` / `.update` / `.withdraw` / `.approve` / `.reject` | 지식 제안 — 작성자의 등록·수정·철회(승인본 회수 건수 포함), 관리자의 승인·거부. §10.11 이전의 `curated.edit`(채팅 버블의 좋아요 편집)는 그 경로와 함께 사라졌다 |
| `chunk.report` / `chunk.report.resolve` / `chunk.report.reject` | 청크 오류 신고 접수 · 관리자 처리(닫은 건수 포함, 관리자 매뉴얼 §7) |
| `settings.update` / `settings.reset` / `settings.provider.toggle` / `settings.context-window.reprobe` | `/settings` 변경 · 컨텍스트 창 재탐지(§8) |
| `llm-usage.delete-orphan` | orphan 사용 기록 삭제 |
| **`admin.thread.delete`** | 관리자가 **다른 사용자의** 대화를 삭제 (소유자·턴 수·회수된 큐레이션 수, 관리자 매뉴얼 §4.8) |
| **`admin.thread.read`** | 관리자가 **다른 사용자의** 답변 전문을 열람 (소유자·턴 id, 관리자 매뉴얼 §4.8) — "누가 남의 대화를 읽었는가"를 확인하는 유일한 근거입니다 |

| 속성 | 환경변수 | 기본값 | 설명 |
|------|----------|--------|------|
| `app.audit.enabled` | `AUDIT_ENABLED` | `true` | `false`로 설정하면 감사 로그 미기록 |
| `app.audit.max-file-size` | `AUDIT_MAX_FILE_SIZE` | `10MB` | 롤링 전 최대 파일 크기 (Logback SizeAndTimeBasedRolling) |
| `app.audit.max-history-days` | `AUDIT_MAX_HISTORY_DAYS` | `7` | 압축 파일 보관 일수. 초과된 파일 자동 삭제 |
| `app.audit.total-size-cap` | `AUDIT_TOTAL_SIZE_CAP` | `100MB` | `data/audit/` 디렉터리 전체 상한. 초과 시 오래된 파일 삭제 |

> 크기·보관 3개 값은 `logback-spring.xml`이 `springProperty`로 읽어 `AUDIT_FILE` appender의 롤링 기준으로도
> 쓰이므로, 환경변수로 바꾸면 로거 쪽에도 그대로 반영됩니다.

> **`ip` 필드는 `TRUST_FORWARDED_FOR`를 따릅니다.** 그 값이 `false`(프록시 없는 배포의 정상 설정)면
> `X-Forwarded-For`를 무시하고 실제 remote address를 기록합니다. 프록시 뒤라면 §9.4.3대로
> `true`로 켜야 실제 클라이언트 IP가 남습니다. 요청 스레드 밖(가상 스레드에서 도는 비동기 작업)에서
> 기록되는 이벤트에는 `ip` 키가 아예 들어가지 않습니다.

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
| SQLite 세션 PRAGMA (`DataSourceConfig.SESSION_PRAGMAS`) | `journal_mode=WAL` · `busy_timeout=5000` · `synchronous=NORMAL` · `cache_size=-32768` · `mmap_size=268435456` | ⚠️ 조건부 | JDBC URL 파라미터로만 건다(`connection-init-sql`은 statement 하나만 실행되고 sqlite-vec에서는 그 자리를 `load_extension()`이 쓴다). **`mmap_size`는 DB 파일이 네트워크 공유(NFS/SMB)에 있으면 `0`으로 되돌릴 것** — 매핑된 페이지에서 I/O 오류가 나면 오류 코드가 아니라 SIGBUS로 프로세스가 죽는다. 로컬 디스크 전제로 켜 둔 값이다 |
| `spring.autoconfigure.exclude` | Chroma 자동구성 + OpenAI 모델 자동구성 6종 제외 | ❌ 변경 금지 | `ChromaConfig`/`VectorStoreRegistry`가 직접 Chroma 빈을 관리하고, 채팅·임베딩 빈은 `LlmConfig`/`EmbeddingBeanConfig`가 직접 만든다. OpenAI 자동구성을 되살리면 `LOCAL_LLM_KEY` 가 빈 로컬 전용 배포가 `OpenAI API key must be set` 로 기동 실패([§8](#8-문제-해결)) |

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

> ⚠️ **컨테이너는 UID 10001(비-root)로 실행됩니다.** 이 프로세스는 신뢰 경계 밖의 문서를 파싱하고
> (PDFBox·POI), OCR을 JNA로 같은 프로세스에 붙이며, sqlite-vec를 쓰면 네이티브 확장까지 로드합니다 —
> 그중 하나에서 코드 실행이 일어났을 때 컨테이너 안에서 root인지 아닌지가 그다음을 가릅니다.
> **예전 이미지(root 실행)로 만든 `./data`가 이미 있는 Linux 배포는 한 번만** 아래를 실행하세요.
> 하지 않으면 엔트리포인트가 쓰기 권한을 먼저 확인해 이 명령을 그대로 안내하고 종료합니다
> (SQLite 스택트레이스로 나오는 것보다 낫습니다). Docker Desktop(Windows/macOS)의 bind mount는
> 소유권을 강제하지 않으므로 해당 없습니다.
>
> ```bash
> sudo chown -R 10001:10001 ./data
> ```
>
> UID를 10001로 못 박은 이유는 `./data`가 bind mount라 소유권이 호스트를 따르기 때문입니다 —
> 이미지가 UID를 매번 다르게 잡으면 업그레이드마다 쓰기 권한이 깨집니다.

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

#### 4.1.3 메모리(JVM 힙) 설정

컨테이너 이미지는 `JAVA_OPTS="-XX:MaxRAMPercentage=70"`으로 뜹니다. JVM 기본값은 25%인데, 이 앱은 업로드 상한이 200MB이고 PDFBox/POI가 문서를 통째로 메모리에 올리며 마크다운 교정이 변환된 전문을 문자열로 다루기 때문에 그 기본값으로는 대형 문서 인덱싱에서 `OutOfMemoryError`가 납니다.

**70%가 무엇의 70%인지**는 컨테이너에 메모리 한도가 걸렸는지에 달려 있습니다.

| 상황 | 힙 상한 |
|------|---------|
| `mem_limit` 등으로 한도를 건 경우 | **그 한도**의 70% (예: `mem_limit: 4g` → 약 2.8GB) |
| 한도가 없는 경우(기본 `docker-compose.yml`) | **호스트 전체 메모리**의 70% |

호스트에서 LLM 서버(LM Studio·llama-server)를 함께 돌리는 배포라면 한도를 걸어 두는 편이 예측 가능합니다 — `docker-compose.yml`의 `app` 서비스 주석 참조.

나머지 30%는 여유분이 아니라 **실제로 쓰이는 몫**입니다: OCR(tess4j)은 별도 프로세스가 아니라 JNA로 같은 프로세스에 붙는 네이티브 메모리이고, sqlite-vec 확장도 마찬가지이며, 여기에 reactor-netty의 direct buffer와 가상 스레드 스택이 더해집니다. LibreOffice를 추가한 이미지라면 `soffice`가 자식 프로세스로 같은 한도를 나눠 씁니다. 이 몫을 힙으로 끌어다 쓰면 **애플리케이션 로그에 아무것도 남기지 않고 컨테이너가 OOM kill됩니다.**

**직접 지정하려면** `.env`에 `JAVA_OPTS`를 두세요(컨테이너 실행 시 준 값이 이미지 기본값을 이깁니다).

```env
JAVA_OPTS=-Xms512m -Xmx3g
```

> Docker를 쓰지 않는 실행 경로(`scripts/*_run_by_jar_*`, `mvn spring-boot:run`)에는 이 설정이 적용되지 않습니다 — 호스트 JVM의 기본값(물리 메모리의 25%)을 따르며, 필요하면 `java -Xmx3g -jar ...`로 직접 지정하세요.

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
export EMBED_BASE_URLS=http://127.0.0.1:8081/v1
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
- **옛 `SQLITE_VEC_DB_PATH` 를 설정한 배포라면 그 파일도 함께** — 예: `data/vector.db`(+`-wal`/`-shm`). ⚠️ 그 배포에서는 **대화·계정·설정·레지스트리·벡터가 전부 그 파일에 있으므로**, `memory.db` 만 지우면 아무것도 초기화되지 않습니다([§6.2.1](#621-sqlite-파일별-테이블-구성)). `scripts/*_reset_data.sh` 는 두 파일을 모두 지웁니다
- `data/documents/`
- `data/converted/`
- `data/images/`

chroma 백엔드 추가 삭제 대상:
- `data/chroma/` (로컬 Chroma 경로 사용 시)
- Docker named volume `chroma_data` (compose volume 사용 시)

```bash
# macOS / Linux
rm -f data/memory.db data/memory.db-wal data/memory.db-shm
rm -f data/vector.db data/vector.db-wal data/vector.db-shm   # 옛 SQLITE_VEC_DB_PATH 를 설정한 배포
rm -rf data/documents data/converted data/images data/chroma
mkdir -p data/documents data/converted data/images data/chroma data/audit
```

```powershell
# Windows PowerShell
Remove-Item data/memory.db,data/memory.db-wal,data/memory.db-shm -Force -ErrorAction SilentlyContinue
# 옛 SQLITE_VEC_DB_PATH 를 설정한 배포
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
| `app.embedding.*` | 문서 인덱싱·벡터 검색 임베딩 | `EMBED_BASE_URLS` / `EMBED_API_KEY` / `EMBED_MODEL` |
| LlmRouter (providers) | 질의응답·분류·검증 LLM 호출 | `application.properties` providers 블록 |
| Spring AI 전역 | auto-configured 빈 폴백 | `OPENAI_*` 환경변수 |

> 임베딩과 추론은 완전히 분리되어 있습니다. 로컬 임베딩 모델(Ollama 등)과 외부 추론 모델을 독립적으로 조합할 수 있습니다.

기본값으로 소형 로컬 LLM(`providers[0]`, MICRO_TEXT 전담) + 로컬 LLM 1·2(`providers[1]`·`[2]`, LOCAL — 2번은 `LOCAL_LLM_URL_2` 가 있을 때만 활성) + 외부 NORMAL 3종·PREMIUM 3종(`providers[3]`~`[8]`)이 함께 등록되어 있습니다(§5.4 예제 6 참고).  
Vision 전용 모델을 쓰려면 `application.properties`에 providers 블록(`[9]`, 주석 예시)을 추가/수정하세요.

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
| **G3** | `role=LOCAL`만 | `GET {base-url}/v1/models` 호출 성공 + 응답에 `model` 값이 포함(정확 일치, 또는 `model` 을 포함하는 id 가 **정확히 하나** — 그러면 그 서버 id 를 씀) | **애플리케이션 시작 자체가 실패**(Spring Boot가 비정상 종료) |

G1·G2는 "이 프로바이더를 조용히 빼고 계속 진행"이지만, **G3는 다르다** — LOCAL 프로바이더의 `base-url`이 설정돼 있는데 서버가 안 떠 있거나 모델명이 틀리면 앱이 아예 뜨지 않는다. 포트 오타나 모델명 오타를 배포 직후 채팅 중 발견하는 대신 기동 시점에 바로 잡기 위함이다. 모델명은 **서버 id 의 일부**여도 통과한다 — 같은 모델을 서버마다 다르게 부르기 때문이다(LM Studio `google/gemma-4-e4b`, llama-server 의 `-a` 별칭이나 파일명, Ollama 의 `:latest` 접미사). 그때는 설정값이 아니라 **서버의 전체 id 가 모든 요청에 실리고**, 기동 로그에 `configured model 'x' is part of server model 'y' … using the server's id` 로 남는다. 정확히 같은 id 가 있으면 언제나 그것이 이기고(다른 id 가 그 이름을 포함해도 모호해지지 않는다), 설정값이 **둘 이상의 id 에 걸리면** 없는 것과 같이 기동 실패다 — 후보를 로그에 나열하니 전체 id 로 고쳐 적으면 된다(앱이 대신 고르지 않는 이유: 어느 모델과 대화 중인지 로그를 뒤져야 알게 되면 G3 의 존재 이유가 사라진다). `app.llm.verify-local-models-on-startup`(`LLM_VERIFY_LOCAL_MODELS_ON_STARTUP`, 기본 `true`)로 끌 수 있다 — 로컬 서버가 앱보다 늦게 뜨는 배포 순서 레이스가 있는 환경 등 예외적인 경우에만 `false`로 설정할 것. `NORMAL`/`PREMIUM`(클라우드) 프로바이더는 G3 대상이 아니다.

**복제본 예외** — 같은 `type`·`priority` 의 LOCAL 프로바이더가 둘 이상이면(예: `local` + `local-2`, [§5.4 예제 5](#54-시나리오별-설정-예제)) 그중 **닿지 않는** 서버는 기동을 멈추지 않는다: 다른 복제본이 검증을 통과했으면 `[LLM STARTUP CHECK] provider=[x] is unreachable … but its replica(s) [y] answered` WARN 을 남기고 그 서버를 **30초 차단된 채** 등록한다 — 첫 요청부터 살아 있는 쪽으로 가고, 차단이 풀리면 평소처럼 다시 시도한다. 이중화의 목적이 "한 대가 없어도 돌아간다"인데 하필 기동 시점에 한 대가 꺼져 있으면 아무것도 뜨지 않던 것을 고친 것이다. 이 서버는 모델명을 해석하지 못했으므로 **설정값 그대로** 실리고(서버가 다른 id 로 부르면 돌아온 뒤 "없는 모델"이 될 수 있다 — 전체 id 로 적어 둘 것), 컨텍스트 창은 서버가 돌아온 뒤 컨텍스트 초과(자동 재탐지)나 `/settings` 의 창 재탐지 버튼이 채울 때까지 "모름"이다. 너그러운 것은 **장애 모양의 실패뿐**이다 — 연결이 안 되거나(거부·타임아웃·DNS) 응답이 없거나 5xx(예: 모델을 올리는 중인 llama-server 의 503). 서버가 **답을 했는데** 그것이 4xx(경로·인증 오류)이거나 모델 목록이 아닌 응답(다른 서비스의 HTML 등)이면, 또는 닿은 서버에 모델이 없거나 모호하면 복제본이 있어도 기동 실패다(장애가 아니라 설정 오류 — 로그는 `got no model list`). 다만 **주소·포트 오타는 서버가 꺼진 것과 구분할 수 없어**(둘 다 연결 거부) WARN 한 줄로 넘어가므로, 이중화 배포는 기동 로그의 `[LLM STARTUP CHECK]` 를 확인할 것. `priority` 가 다른 서버(예: 소형 모델 `local-fast`)는 복제본이 아니라 폴백이라 예외에 들지 않는다.

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

> 시작 로그에서 각 프로바이더의 stream 설정을 확인할 수 있습니다(`concurrency`·`ctx` 는 §5.7·§8 참고 — `ctx=?` 는 창을 모른다는 뜻):  
> `local(LOCAL/BOTH/p1/stream=false/concurrency=3/ctx=8192/probed) → http://localhost:1234/v1 [google/gemma-4-e4b]`

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
> `LIGHT_BOTH`는 반대로 양쪽에 다 쓰인다 — 프로바이더 type이자 "멀티모달이면 되고 대형일 필요는 없다"는 요청 타입이다. 이 요청은 `LIGHT_BOTH`·`BOTH`가 받고 텍스트 전용 3종과 `VISION` 전용은 거부한다(경량 텍스트 + 이미지를 함께 요구하므로). 다만 이 요청을 내던 이미지 유형 분류기는 2026-11-04 에 걷어내 **지금은 요청하는 호출부가 없다**(프로바이더 type 으로서는 그대로 유효하다).

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
| CuratedSubmissionService.enrich | `MICRO_TEXT` | 지식 제안의 **요약·키워드 자동 생성**(관리자 매뉴얼 §6.1) — 작성자가 폼의 "빈 칸 자동 생성" 버튼을 누르거나, 두 칸을 비운 채 등록할 때 돕니다. 인덱싱과 **같은** `KeywordExtractor` 를 쓰므로 사용량도 `context:` 범주로 함께 잡히고, TF 폴백까지 동일합니다. **비어 있는 칸이 없으면 호출 자체가 없습니다**(서버 판정). 게스트도 부를 수 있는 경로지만 `RateLimitFilter` 의 `default` 버킷(분당 120)이 빈도를 막습니다 — 배경 호출(`executeWithTracking`)이라 §5.7 의 채팅 동시성 게이트는 **타지 않습니다** |
| QuestionCondenser | `MICRO_TEXT` | 짧은 후속 질문의 독립화 (§10.12) — 확장이 생략되는 길이 구간에서만 돌아 한 턴의 질의 전처리 호출은 여전히 최대 1회 |
| AnswerService | `TEXT` | 답변 생성 + **충분도·근거 통합 평가**(별도 1콜) |
| CriticService | — | **LLM 호출 없음** — AnswerService의 통합 평가가 낸 `grounded`를 읽어 재시도 여부만 결정 (`responseMode=S`이면 이 단계 스킵) |
| DirectAnswerService | `TEXT` | meta 질문 직접 응답 (사용자 노출 — 답변과 같은 타입으로 묶어 큰 모델 유지) |
| VisionDescriptionService | `VISION` | 이미지 → 설명 생성 |
| KeywordExtractor | `MICRO_TEXT` | 청크 키워드+맥락(Contextual Retrieval, §10.1) 통합 추출 — `context:` 사용량 라벨. §6.21로 MICRO_TEXT 전환 |
| RerankerService | `TEXT` (ChatClient) | 검색 후보 LLM 리랭킹 — `SEARCH_RERANK_ENABLED=true`일 때만 동작 |

> **백그라운드 서비스(AgentGraph 밖)**: `ConversationSummarizerService`(대화 요약)·`ThreadMetaService`(제목 생성)도 `MICRO_TEXT`를 사용한다. `type=MICRO_TEXT` 소형 프로바이더 등록 시 `MICRO_TEXT` 경로 전부(키워드+맥락·요약·제목·쿼리확장·질의 독립화·큐레이션 질문 제안)가 소형으로 오프로딩되고, 분류·직답·답변은 큰 모델에 남는다(§5.4 예제 6, §6.21). 단 대화 요약만은 소형이 없으면 흡수되지 않고 LLM 호출이 생략된다(LLM_ROUTING.md §9).

---

### 5.3 라우팅 모드

`application.properties`의 `app.llm.default-routing-mode`로 기본값 설정.  
Web UI 채팅 화면 드롭다운에서 대화별로 변경 가능.

| 모드 | 동작 | 권장 상황 |
|------|------|----------|
| `COST_FIRST` | LOCAL → NORMAL → PREMIUM 순 시도 | **기본값**. 비용 절감 우선 |
| `QUALITY_FIRST` | PREMIUM → NORMAL → LOCAL 순 시도 | 최고 품질 응답 필요 시 |
| `PROGRESSIVE` | COST_FIRST로 먼저 답변 → 검증 미충족(`sufficient=false`) + 재시도 소진 시 PREMIUM으로 재실행 | 품질·비용 균형 |
| `LOCAL_ONLY` | LOCAL만 사용, 외부 API 미호출 | 오프라인 / 보안 환경 |

> **PROGRESSIVE 승격 조건**: 임계값 설정은 없습니다(`app.llm.progressive-threshold` 는 2026-09-01 제거 — 값을 읽는 코드가 없어 어떤 값을 넣어도 동작이 같았습니다). **PROGRESSIVE 승격 조건** — 점수도 임계값도 없다. `AnswerService.checkSufficiencyAndMaybeUpgrade()` 의 조건 셋이 전부다: 라우팅 모드가 `PROGRESSIVE` 이고, 검증이 `sufficient=false` 를 냈고(`needsRetry`), 재시도를 이미 다 썼을 때(`retryCount >= max-retry-count`).

---

### 5.4 시나리오별 설정 예제

#### 예제 1 — 로컬 LLM 전용 (LM Studio / Ollama)

`application.properties` 변경 없이 환경변수만 설정합니다.  
`providers[1]`(로컬 LLM 1)이 `LOCAL_LLM_*` 값을 사용합니다 — **`LOCAL_LLM_URL`은 반드시 설정해야 합니다**(비어 있으면 이 provider가 통째로 비활성화됨, 더 이상 `http://localhost:1234/v1`로 자동 폴백하지 않음). (`providers[0]`은 잡무 전담 소형 모델 — `LOCAL_FAST_LLM_*`, §5.4 예제 6. `LOCAL_FAST_LLM_URL`을 비워두면 그냥 비활성화되고 `local`이 잡무까지 흡수함)

`.env`:
```env
EMBED_BASE_URLS=http://localhost:1234/v1
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

로컬 LLM 없이 OpenAI를 NORMAL + PREMIUM으로 등록합니다. 아래처럼 `application.properties`의 providers 블록을 통째로 바꾸면 기본 파일의 LOCAL 프로바이더(`local-fast`/`local`/`local-2`)는 애초에 존재하지 않습니다. 기본 파일을 그대로 두는 경우에는 `LOCAL_LLM_URL`(및 `_2`, `LOCAL_FAST_LLM_URL`)을 **비워** 두면 그 프로바이더가 등록되지 않습니다(G2) — `LOCAL_LLM_KEY`를 비우는 것으로는 비활성화되지 않습니다(LOCAL 은 키 없이도 등록됩니다, G1).

`.env`:
```env
OPENAI_API_KEY=sk-proj-...
EMBED_BASE_URLS=https://api.openai.com
EMBED_API_KEY=sk-proj-...
EMBED_MODEL=text-embedding-3-large
# LOCAL_LLM_URL 은 설정하지 않는다 — 기본 providers 블록을 그대로 쓸 때 LOCAL 을 빼는 스위치는 URL 이다(G2)
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
EMBED_BASE_URLS=https://api.openai.com
EMBED_API_KEY=sk-proj-...
EMBED_MODEL=text-embedding-3-large
# 로컬 LLM 없음 — 아래 providers 블록에 LOCAL 항목이 없고, 기본 블록을 쓴다면 LOCAL_LLM_URL 을 비워 둔다(G2)
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
EMBED_BASE_URLS=https://api.openai.com
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
[답변·분류·직답·Rerank (TEXT)]             gemini-flash → openai-mini → gemini-pro → openai
                                           (각 단계에서 429/오류 시 다음 우선순위로 자동 전환)
```

- 분류·meta 직답은 `LIGHT_TEXT`가 아니라 **`TaskType.TEXT`** 라서(§5.6 노드별 TaskType 표) `LIGHT_BOTH` 로컬 모델이 받지 못하고 클라우드로 나갑니다. 로컬에 남기려면 `type`을 `BOTH`로 올려야 합니다.
- 이미지 설명은 로컬이 처리합니다 — `LIGHT_BOTH` 프로바이더가 `VISION` 요청도 받기 때문입니다.

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
- **한 대가 죽으면 나머지 한 대로 자동 전환됩니다** — 연결 거부(프로세스 종료)·연결 타임아웃(호스트 다운)·HTTP 5xx·429/503 이면 그 서버를 30초 차단하고 같은 요청을 다른 서버로 보냅니다(분류·검증·질의 확장 같은 블로킹 호출은 같은 호출 안에서, 채팅 답변은 **첫 토큰이 나가기 전이면** 스트림을 다시 엽니다). 다음 요청은 처음부터 살아 있는 서버로 갑니다. **전환되지 않는 것**: 읽기 타임아웃(연결은 받고 응답이 없는 멈춘 서버 — `LLM_READ_TIMEOUT_SECONDS` 까지 기다린 뒤 오류), 이미 토큰이 나간 답변, 사용자 중단. 기동 시에 한 대가 안 떠 있어도 앱은 뜹니다 — 그 서버는 30초 차단된 채 시작하고 요청은 살아 있는 쪽으로 갑니다(두 대 모두 안 닿으면 기동 실패, [§5.2 G3 의 복제본 예외](#프로바이더-활성화-게이트-llmconfig-g1g3) 참고). **임베딩은 이 라우팅 밖입니다** — 질문 임베딩과 인덱싱은 `EMBED_BASE_URLS`(`;` 로 여러 주소, 비우면 `LOCAL_LLM_URL`)로만 갑니다. 두 서버가 같은 임베딩 모델을 띄우고 있다면 **다른 서버를 `EMBED_BASE_URLS` 에도 `;` 로 적으세요** — 그 목록은 부하 분산과 함께 장애 전환(닿지 않는 엔드포인트를 30초 건너뜀)을 하므로, 그래야 한 대가 죽어도 RAG 질문이 검색 단계에서 실패하지 않습니다. 적지 않은 채 `LOCAL_LLM_URL` 쪽이 죽으면 채팅 모델은 넘어가도 RAG 질문은 질문 임베딩에서 오류가 납니다(기동 로그의 `[EMBED CONFIG]` 경고가 이 구성을 알려 줍니다).

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
- **더 공격적 오프로딩(A안)**: MD 서식 교정·TXT 구조화까지 소형으로 내리려면 `type=MICRO_TEXT` 대신 `type=LIGHT_TEXT`로 등록(`LIGHT_TEXT`가 `MICRO_TEXT`도 흡수). 구조 충실도가 떨어지면 청킹 품질에 직결되므로 채택 전 검색 품질 평가 하네스([§6.4](#64-검색-품질-평가-하네스-개발자용))로 회귀를 확인하세요.
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
- 모든 프로바이더 소진 시 → `LlmProviderExhaustedException` (503 응답, `RAG-LLM-001` — 차단 때문이면 `Retry-After` 동반. 컨텍스트 초과로 온 하위 타입 `LlmContextOverflowException` 만 500/`RAG-LLM-003`, [ERROR_CODES.md](ERROR_CODES.md))
- `/llm-usage` 대시보드에서 차단 중인 프로바이더를 빨간 카드 + MM:SS 카운트다운으로 확인 가능
- 임베딩 호출은 Circuit Breaker 대상이 아닙니다 — `/llm-usage`의 `embed` 카드는 항상 "정상" 배지로 표시되며 실패 시 재시도/차단 없이 즉시 예외가 전파됩니다(`EMBED_USAGE_FALLBACK_ENABLED`)
- API 키가 없는(비활성) 프로바이더는 **사용 이력이 없으면** `/llm-usage`의 카드·표·차트 어디에도 표시되지 않습니다. 과거에 사용된 적이 있으면 키를 제거한 뒤에도 이력 보존을 위해 계속 표시됩니다. 활성(키 설정됨) 프로바이더는 사용량이 0이어도 항상 표시됩니다.
- **Circuit Breaker ≠ 동시성 백프레셔(§5.7)**: 429/402/기타 오류로 인한 차단은 "프로바이더가 고장났다"는 신호로 취급해 일정 시간 우회합니다. 반면 동시성 게이트가 대기 상한을 넘겨 던지는 429(`LlmBackpressureException`)는 "프로바이더는 정상이지만 지금 자리가 없다"는 신호이므로 Circuit Breaker를 차단하지 않고, 다른 프로바이더로 자동 전환하지도 않습니다 — 요청을 보낸 쪽에 그대로 즉시 전파됩니다.

### 5.6 Orphan 프로바이더 사용 기록 정리

설정(`app.llm.providers`)에서 완전히 제거된 프로바이더의 기록은 `llm_usage`에 그대로 남아 orphan이 됩니다. `/llm-usage`에서 회색 **ORPHAN** 배지 카드로 노출되며, 카드 우측 상단 🗑 아이콘으로 정리할 수 있습니다. 임베딩 사용량은 모델명 없이 `embed` 한 이름으로 기록되므로 `EMBED_MODEL`을 바꿔도 orphan이 생기지 않고 같은 카드에 이어서 쌓입니다(예전에 `embed:<모델>`로 쌓인 기록은 V5 마이그레이션이 날짜별로 합쳐 `embed`로 옮깁니다).

- **삭제 대상 판별**: 현재 config에 없는 프로바이더 이름만 orphan으로 분류됩니다(임베딩 `embed`와 백그라운드 사용량은 제외). 활성 프로바이더·임베딩 카드에는 삭제 버튼 자체가 없고, API를 직접 호출해도 서버가 400으로 거부합니다.
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
- 임베딩 엔드포인트는 이 LLM 게이트와 별개의 `EmbeddingModel` 데코레이터 체인에서 따로 로드밸런싱됩니다(§6.21 E1, `LoadBalancingEmbeddingModel` — `EMBED_BASE_URLS` 의 `;` 목록). 설정은 [§3.2 "임베딩 병렬화"](#32-환경변수-전체-목록) 참고.

**헤더 LLM 동시성 표시기**: 웹 UI 우측 상단에 `LLM: {사용중}/{용량}`으로 실시간 포화도를 보여줍니다(`GET /api/v1/llm/concurrency`, ~3초마다 폴링). `사용중` 값이 `용량`에 도달하면(완전 포화) 숫자가 굵은 빨간색으로 강조됩니다 — 위 429/`[BACKPRESSURE]` 로그를 매번 찾아보지 않아도 한눈에 확인할 수 있는 보조 지표입니다.
- `role=LOCAL, priority=1`(주 응답용 로컬 티어 — MICRO_TEXT 전용 소형 오프로딩 모델은 제외) 프로바이더들의 `concurrency` 합계가 용량이고, 실제 게이트에서 점유 중인 슬롯 수가 사용중 값입니다.
- **임베딩 활동도 함께 반영됩니다**: 인덱싱·검색 임베딩 호출은 이 채팅 동시성 게이트를 전혀 거치지 않는 별도 `EmbeddingModel` 데코레이터 체인이라, 별도 in-flight 카운터(`EmbeddingConcurrencyTracker`)를 채팅 사용량에 합산합니다 — 그래서 임베딩만 바쁠 때도 지표가 0에 머무르지 않습니다. 합산값은 용량을 넘지 않게 잘립니다(임베딩 동시성은 `EMBED_MAX_CONCURRENT_BATCHES` 등 별도 한도라 합이 용량을 초과할 수 있기 때문).
- **서킷브레이커로 차단된 프로바이더는 용량에는 남고 그 전체가 "사용중"으로 집계됩니다** — 제외돼서 지표 자체가 사라지는 대신, 로컬 프로바이더가 하나뿐인 배포에서 그게 차단되면 예컨대 `3/3`(완전 포화)으로 표시됩니다.
- 로컬 티어 프로바이더가 하나도 등록/활성화돼 있지 않으면 지표 자체가 숨겨집니다.
- **서킷 브레이커가 막고 있는 동안은 `LLM: 차단 중 4s`** 로 바뀝니다(`blockedSeconds`). 툴팁이 `GET /api/v1/llm/ping` 을 가리킵니다 — 아래 "로컬 LLM 생사 확인".

---

## 6. 운영 팁

### 6.1 대화 메모리

`MemoryService`는 **SQLite**(`DATA_DIR/memory.db`)에 대화 이력을 영속합니다.

- WAL 모드로 읽기/쓰기 경합 최소화. SQLite pool size는 반드시 1 유지
- 스레드별 최근 `MEMORY_FETCH_LIMIT_TURNS`(기본 10)턴을 읽어오되, **프롬프트에 원문으로 들어가는 것은 그 절반**(기본 5턴)입니다 — 나머지는 요약이 담당합니다. 글자 상한은 별도로 `LLM_MAX_TOKENS × 0.5`(Direct 턴은 §10.13 의 예산 계산)입니다
- **이전 턴이 Direct(RAG 끔)였으면 그 답변은 요약이 아니라 본문 그대로** 다음 턴의 이력에 들어갑니다. 그 턴에는 근거로 삼을 검색 문서가 없었으므로 답변의 산문이 그 대화의 유일한 기록이기 때문입니다. 대신 검증 프롬프트에는 이력이 들어가지 않으므로, 모델이 그 내용에 기대어 답하면 **미검증 배지**가 뜰 수 있습니다(문서에 근거가 없다는 판정이며, 재시도는 답변만 다시 씁니다)
- `/chat/{threadId}` 재진입 시 모든 이전 turn을 시간순으로 불러와 메시지 버블 복원
- `MemoryRepository` 인터페이스로 추상화 — Redis 등으로 교체 시 구현체만 추가

**요약 캐시 (`ConversationSummarizerService`)**: 스레드 대화 이력을 미리 요약해 캐싱해두고, 실제 질의 시 원문 전체 대신 "요약 + 최근 N턴 원문"을 컨텍스트로 사용해 토큰을 절약합니다.

- **대부분의 경우 LLM을 쓰지 않습니다.** RAG 답변은 `prompt.answer.system`이 강제하는 5-섹션 형식상 이미 자기 요약(`## 요약`)을 첫 섹션으로 갖고 있어, 그것을 그대로 뽑아 쓰면 됩니다. 모든 턴이 그렇다면 **LLM 호출 0회**로 요약이 완성됩니다. LLM이 필요한 것은 그 섹션이 없는 답변뿐이고 — §10.13 이후 DN 답변도 요약을 낼 수 있어 그 빈도가 줄었지만, 요구가 **조건부**라 인사말·짧은 답변·요약이 군더더기인 답변에서는 여전히 없습니다 — 그때도 입력은 이미 다른 턴들이 요약으로 치환된 상태라 모델은 사실상 그 답변만 압축합니다.
- **소형 모델(`LOCAL_FAST_LLM_URL`)이 없으면 LLM 호출만 생략하고, 요약 조립은 그대로 합니다.** 요약 섹션이 없는 답변은 300자로 잘라 담습니다(`UNSUMMARIZED_ANSWER_CAP`, 상수 — 프로퍼티 아님). §10.13 이후 DN 답변도 요약을 낼 수 있게 됐지만 그 요구가 **조건부**라(요약이 도움이 될 때만) 이 절단은 계속 쓰입니다. 완성된 요약은 `SUMMARY_MAX_SUMMARY_CHARS`로 **앞에서부터** 잘리므로, 이 절단이 없으면 앞쪽의 긴 Direct 답변 하나가 예산을 독식해 정작 최신 턴이 밀려납니다. **요약 입력의 질문도 턴마다 500자로 잘립니다** — 질문 한도가 10,000자라 앞 턴의 붙여 넣은 로그 하나가 같은 방식으로 요약 예산을 독식했습니다(직전 턴들은 `[Recent]` 에 원문으로 실리므로 전문은 거기서 보입니다).
- 원문 history 폴백은 이제 **요약할 턴이 아예 없을 때만** 일어납니다(빈 스레드, 전부 DISLIKE 등).

- **트리거**: 주 트리거는 답변이 완료되고 턴이 저장된 직후(`precomputeAfterTurn()`)입니다 — 사용자가 다음 질문을 입력하기 전부터 백그라운드로 미리 갱신을 시작하므로, 응답을 읽는 동안이 곧 요약 준비 시간이 됩니다. 사용자가 질문창에 첫 글자를 입력할 때 발화되는 기존 트리거(`precompute()`)는 아직 한 번도 요약이 만들어지지 않은 스레드(예: 재시작 후 처음 열어본 오래된 대화)를 위한 콜드스타트 안전망으로 남아 있습니다.
- **요약과 `[Recent]`는 겹치지 않게 나뉩니다**: 요약 대상은 **최근 `SUMMARY_RECENT_RAW_TURNS`개를 뺀 이전 턴들**이고, 최근 창은 `[Recent]`가 담당합니다.
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
- **싫어요 제외 범위**: 싫어요가 눌린 턴은 이 경로의 **요약과 `[Recent]` 원문 구간 양쪽**에서 빠집니다(`dedupeTurns()`). `getRecentTurns()`의 SQL에는 feedback 조건이 없으므로 — 다른 호출자는 원본 행이 필요합니다 — 이 클래스에서 그 메서드를 쓰는 곳은 반드시 `dedupeTurns()`를 거쳐야 합니다.
- **싫어요 처리**: 답변 직후 트리거된 요약 생성이 진행되는 동안(LLM 호출이 아직 끝나지 않은 짧은 시간) 사용자가 방금 그 턴에 싫어요를 누르면, 완성된 요약이라도 캐싱하지 않고 버립니다 — 다음 질문은 자동으로 원문 폴백 경로를 쓰게 되며, 그 경로는 애초에 DISLIKE 턴을 제외합니다. 다음 정상 트리거 때는 dedupe 단계에서 자연스럽게 그 턴이 빠진 채로 다시 요약됩니다.
- **좋아요 처리**: LIKE 는 그 자체로는 아무것도 등록하지 않는다(§10.11) — 지식 제안 폼을 열어 줄 뿐이고, 검색 인덱스에 들어가는 것은 관리자가 승인한 뒤다. 저장된 LIKE 값을 읽는 곳은 채팅 버튼 상태뿐이다. 상세는 [관리자 매뉴얼 §5.1 큐레이션 Q&A](ADMIN_MANUAL.md#51-큐레이션-qa-공유-지식-축-1010--1011) 참고.
- 캐시 미스이거나 LOCAL 프로바이더가 없으면 자동으로 원문 폴백 경로(`MemoryService.getHistory()`) 사용 — best-effort, 실패해도 채팅이 막히지 않음
- 요약 경로와 폴백 경로는 **동일한 문자 예산과 동일한 렌더 규칙**을 씁니다(관리자 매뉴얼 §8 + §10.13, 규칙의 단일 출처는 `HistoryPolicy`) — 요약 캐시 유무에 따라 LLM에 전달되는 컨텍스트의 **양도 내용도** 달라지지 않습니다.
- **요약 대상 자체도 무제한이 아닙니다**: 요약을 만들 때 읽어오는 원문(`MemoryService.getRecentTurns()`)은 `getHistory()`와 동일하게 `MEMORY_FETCH_LIMIT_TURNS`(기본 10턴)로 상한이 걸려 있습니다. 대화가 길어져도 매번 LLM에 보내는 요약용 입력 크기가 무한정 커지지 않도록 하기 위함이며, 이 턴 수보다 오래된 내용은 이 요약에서도 함께 유실됩니다(스레드를 다시 열었을 때 전체 메시지 버블을 복원하는 `MemoryService.getTurns()`는 이 제한과 무관하게 항상 전체를 반환합니다)
- 캐시 크기·요약 길이·최근 턴 수·재계산 억제 창은 `MEMORY_FETCH_LIMIT_TURNS`/`SUMMARY_*` 환경변수로 조정 (위 "대화 메모리 / 요약 캐시 튜닝" 참조)

`conversation_turns` 테이블 확장 컬럼 (앱 시작 시 Flyway 마이그레이션이 자동으로 추가):

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `asked_at` | TEXT | 질문 발송 시각 (UTC, `yyyy-MM-dd HH:mm:ss`) |
| `input_tokens` | INTEGER | 해당 턴 입력 토큰 수 |
| `output_tokens` | INTEGER | 해당 턴 출력 토큰 수 |
| `elapsed_ms` | INTEGER | 에이전트 처리 소요 시간 (ms) |
| `provider` | TEXT | 최종 응답에 사용된 LLM 프로바이더명 |
| `llm_calls` | INTEGER | 해당 턴의 LLM 총 호출 횟수 |

### 6.2 데이터 영속성

| 데이터 | 저장 위치 | 비고 |
|--------|----------|------|
| 문서 원본 | `DATA_DIR/documents/` | Sync 대상 |
| 삭제된 문서 원본 | `DATA_DIR/documents/backup/` | 문서 삭제 시 원본이 여기로 이동(즉시 삭제 아님). 저장 상한 집계에서 **제외**되며 보존 정책 3규칙으로 정리됨 — §"업로드 크기 제한" 참조. `syncDirectory()` 의 비재귀 스캔 아래라 새 파일로 재검출되지 않음 |
| 추출된 이미지 | `DATA_DIR/images/{imageId}/` | `imageId`는 문서 SHA-256 앞 16자(문서명이 아닌 내용 기반 키) — 문서 삭제 시 함께 삭제되나, 내용이 동일한 다른 문서가 남아 있으면 보존 |
| 지식 제안 본문 이미지 | `DATA_DIR/images/submissions/` | 사용자가 업로드한 제안 본문 이미지(관리자 매뉴얼 §6.1). 파일명은 내용 SHA-256 앞 16자 + 확장자라 같은 그림은 한 벌만 저장되고 **여러 제안이 공유**할 수 있습니다 — 그래서 삭제는 참조 세기 방식입니다(반려·철회 시 + 기동 시 24시간 지난 미참조 파일 스윕). 디렉터리 이름이 문자열 `submissions`이므로 16자리 hex인 `{imageId}`와 절대 충돌하지 않습니다 |
| 변환 MD (원본) | `DATA_DIR/converted/{docId}.md` | DOCX·TXT·PPTX·PDF(비스캔)·MD 인덱싱 시 자동 생성(MD는 업로드한 파일 그대로, 스캔 PDF는 없음); 문서 삭제 시 함께 삭제 |
| 변환 MD (교정본) | `DATA_DIR/converted/{docId}_corrected.md` | LLM 포맷 교정 후 저장("LLM 교정 건너뛰기"로 올린 MD도 결정적 정리 결과가 여기 저장됨); 실제 인덱싱 소스; 수동 편집 후 벡터 스토어 관리 페이지에서 ↺ 재인덱싱 가능 |
| 인덱스 레지스트리 | `doc_registry` 테이블 — 앱의 유일한 SQLite 파일 `DATA_DIR/memory.db`([§6.2.1](#621-sqlite-파일별-테이블-구성)) | SHA-256 기반 변경 감지. 문서 저장소는 사용자별 격리 없이 공유됨(`DocRegistry.SHARED`) — `userId` 파라미터는 API 시그니처상 존재하나 실제로는 무시됨 |
| 벡터 임베딩 | chroma: Chroma 서버(로컬 `data/chroma/`, Docker Compose `chroma_data` 볼륨) / sqlite-vec: 같은 SQLite 파일 `DATA_DIR/memory.db` | 백엔드 전환 시 벡터 공유 안 됨(§3.1) |
| 대화 이력 + LLM 사용량 | 같은 SQLite 파일 `DATA_DIR/memory.db` | WAL 모드; 메시지 메타데이터(토큰·시간·프로바이더) 포함. 파일별 테이블 구성은 아래 [§6.2.1](#621-sqlite-파일별-테이블-구성) |
| 감사 로그 | `DATA_DIR/audit/audit.log` | JSON Lines; 롤링 압축본 `audit.YYYY-MM-DD.N.log.gz` 포함(날짜는 `APP_TIME_ZONE` 기준, 각 줄의 `ts` 는 UTC) |
| DB 백업 | `DATA_DIR/db-backups/memory-yyyyMMdd-HHmmss.db` (`DB_BACKUP_DIR` 로 변경) | `memory.db` 의 검사를 통과한 사본, 매일 03:30(`APP_TIME_ZONE`)·최근 7개 — [§6.2.2](#622-db-백업과-복구) |

> Docker Compose 사용 시 `./data` 디렉터리를 컨테이너에 바인드 마운트합니다.  
> 데이터 백업 시 `data/` 디렉터리와 Chroma 볼륨을 함께 보존하세요.
>
> ⚠️ **DB 파일만 골라 백업하려면 [§6.2.1](#621-sqlite-파일별-테이블-구성)을 먼저 읽으세요.** 옛 `SQLITE_VEC_DB_PATH`를 설정한 배포에서는 대화·계정·설정·벡터가 **전부 그 파일**(예: `data/vector.db`)에 있습니다 — 이름만 보고 `memory.db`를 복사하면 엉뚱한 파일을 백업하게 됩니다.

#### 6.2.1 SQLite 파일별 테이블 구성

DB 파일은 **하나**입니다 — `DATA_DIR/memory.db`. 운영 테이블(대화·계정·설정·레지스트리), 키워드 색인(`chunk_fts`, 두 백엔드 공통), sqlite-vec 백엔드의 벡터가 모두 이 파일, 한 커넥션 풀(pool=1)을 씁니다. Chroma 백엔드에서는 벡터가 Chroma 서버에 있으므로 이 파일에는 나머지만 남습니다.

> 이 절은 운영에 필요한 테이블 목록입니다. 테이블별 컬럼·인덱스·관계와 벡터 저장 구조(메타데이터 키 포함)는 [DATABASE.md](DATABASE.md)에 있습니다.

> ⚠️ **옛 `SQLITE_VEC_DB_PATH`(=`app.vectorstore.sqlite-vec.db-path`)를 설정한 배포** — 예전에는 벡터 테이블을 별도 파일(예: `./data/vector.db`)로 "분리"하는 스위치였지만, 실제로는 **운영 테이블까지 전부 그 파일로 갔습니다**(`memory.db`에는 아무것도 쌓이지 않았습니다). 그래서 sqlite-vec 백엔드에서 이 값이 있으면 앱은 지금도 **그 파일을 유일한 DB로** 엽니다 — 설정을 바꾸지 않아도 데이터가 그대로 보이고, 기동 로그의 `[DB]` 경고가 그 상태를 알려 줍니다. (Chroma 백엔드에서는 예전처럼 이 값을 무시합니다.)
>
> **이 값만 지우면 안 됩니다** — 앱이 `memory.db`를 열어 계정·대화·설정·문서 목록이 사라진 것처럼 보입니다(데이터는 그 파일에 그대로 있습니다). 파일 이름까지 정리하려면 아래 순서를 따르세요.

**파일 이름 정리 (선택 — 옛 `vector.db`를 `memory.db`로)**

1. 앱을 **정상 종료**합니다. `data/`에 `-wal`/`-shm` 파일이 남아 있지 않은지 확인하세요 — 남아 있다면 아직 실행 중이거나 비정상 종료된 것이니, 한 번 띄웠다가 정상 종료합니다.
2. 기존 `data/memory.db`를 다른 이름으로 치웁니다. 스위치를 **처음부터** 켠 배포라면 빈 테이블과 Flyway 이력뿐이지만, **도중에** 켠 배포라면 켜기 전의 데이터가 들어 있을 수 있으니 확인한 뒤 지우세요.
3. `data/vector.db`를 `data/memory.db`로 바꿉니다.
4. `.env`(또는 실행 옵션)에서 `SQLITE_VEC_DB_PATH`를 지우고 기동합니다. 기동 로그에 `[DB]` 경고가 나오지 않으면 끝입니다.

```bash
# macOS / Linux — 앱을 내린 뒤
mv data/memory.db data/memory.db.old
mv data/vector.db data/memory.db
```

```powershell
# Windows PowerShell — 앱을 내린 뒤
Rename-Item data/memory.db memory.db.old
Rename-Item data/vector.db memory.db
```

> 옛 벡터 DB 파일을 처음 여는 기동에서 Flyway가 그 파일에 이력 테이블을 만들고 `<< Flyway Baseline >>`(버전 3) 한 줄을 남긴 뒤 V4 를 적용합니다. V4 는 빠진 테이블·컬럼·인덱스만 더하고 기존 행은 건드리지 않습니다(`spring.flyway.baseline-version=3` — 이유는 아래 «Flyway»).

**운영 테이블** — 대화·계정·설정·레지스트리

| 테이블 | 내용 | 생성 주체 |
|---|---|---|
| `conversation_turns` | 대화 턴(질문·답변·토큰·프로바이더·피드백·응답모드·검색 스코프 태그·**검색 진단 수치**) | Flyway V1·V4 |
| `turn_image_ref` | 턴별 답변 썸네일 이미지 참조(개별 제외는 `status`) | Flyway V4 |
| `turn_source_ref` | 턴별 출처 청크 스냅샷 — 재사용 검증용 `chunk_hash` + 표시용 위치(`filename`/`page_or_slide`/`chapter_no`). 위치는 청크가 지워져 라이브 조인이 비었을 때만 쓰인다 | Flyway V4 |
| `thread_meta` | 대화 제목·버전·라우팅 모드·태그 | Flyway V1·V3 |
| `image_descriptions` | Vision 이미지 설명 캐시 | Flyway V1 |
| `doc_registry` | 인덱싱된 문서 레지스트리(SHA-256 변경 감지, `chunk_overlap`) | Flyway V4 |
| `llm_usage` | 프로바이더별 일자별 토큰 사용량 | Flyway V1 |
| `curated_qa` | 큐레이션 Q&A(좋아요 승격 + 승인된 지식 제안) | Flyway V4 |
| `curated_submission` | 지식 제안 게시판 | Flyway V4 |
| `chunk_report` | 청크 오류 신고 대기열(사유·코멘트 + 신고 시점 원문·질문 스냅샷, 관리자 매뉴얼 §7) | Flyway V4 |
| `agent_tool_call` | 채팅 Tool 선택·실행 관측(분포·성공률·지연, 본문 없음, 90일 보존, 관리자 매뉴얼 §10.1) | Flyway V14 |
| `change_job` | 영향도 분석·유지보수 작업(종류 `impact`·`maintenance`, 상태 `running`·`done`·`failed`·`interrupted`, 리포트 JSON, 관리자 매뉴얼 §10.1) | Flyway V12 |
| `change_proposal` | 유지보수 수정안 검토 대기열(청크별 before/after·해시·맥락/키워드, 상태 `pending`·`approved`·`rejected`·`applied`·`stale`·`failed`, `md_status`). **행을 만드는 것은 코퍼스를 바꾸지 않는다** | Flyway V13 |
| `settings_override` | `/settings` 핫 수정 오버라이드 | Flyway V4 |
| `users`, `persistent_logins` | 계정·자동 로그인 토큰 | Flyway V2 |
| `app_secret` | 게스트 식별 HMAC 키 등 서버 비밀값 | Flyway V4 |
| `flyway_schema_history` | 마이그레이션 이력 | Flyway |

**벡터·검색 색인 테이블** — 위 운영 테이블과 같은 파일

| 테이블 | 내용 | 생성 주체 |
|---|---|---|
| `vec_embeddings` | vec0 가상 테이블 — 임베딩 벡터(`FLOAT[app.embedding.dimensions]`) | `SqliteVecSchemaInitializer` (sqlite-vec 백엔드 전용) |
| `vec_document_chunks` | 청크 원문 + JSON 메타데이터. `spring_doc_id`로 위 테이블과 JOIN | `SqliteVecSchemaInitializer` (sqlite-vec 백엔드 전용) |
| `chunk_fts` | FTS5(trigram) 키워드 색인 — 하이브리드 검색의 BM25 축 | `KeywordSearchRepository` (**백엔드 무관, 항상 생성**) |
| `chunk_fts_key` | `chunk_fts`의 짝인 일반 테이블 — 청크 id → FTS 행(`rowid`), 위치, 본문 해시. FTS5는 청크 id 컬럼에 인덱스를 걸 수 없어서, 청크 id로 FTS 행을 찾는 조회는 전부 이 테이블을 거칩니다. `chunk_fts`와 한 트랜잭션에서 함께 쓰고 지웁니다 | `KeywordSearchRepository` (**백엔드 무관, 항상 생성**) |

> 위 두 표에 없는 이름이 파일 안에 보이면 대개 **SQLite가 자동 생성한 그림자 테이블**입니다 — `chunk_fts_data`/`_idx`/`_content`/`_docsize`/`_config`(FTS5), `vec_embeddings_*`(vec0), `sqlite_sequence`(AUTOINCREMENT). 직접 조회·수정하지 마세요.
>
> **왜 파일이 하나인가** — 예전 분리 스위치는 `DataSourceConfig`가 벡터용 `JdbcTemplate` 빈을 정의하는 바람에 Spring Boot의 기본 `JdbcTemplate`이 만들어지지 않아(`@ConditionalOnMissingBean(JdbcOperations.class)`), 운영 저장소까지 전부 벡터 파일에 쓰는 결과가 됐습니다. 분리의 목적(인덱싱 쓰기와 운영 쓰기의 락 분리)은 한 번도 이뤄진 적이 없고, 코드도 두 종류의 테이블을 한 쿼리로 조인하는 쪽으로 굳어졌습니다. 그래서 분리를 "제대로" 만드는 대신 파일을 하나로 합쳤습니다 — 실행 중 I/O는 원래도 한 파일·한 커넥션이었으므로 성능은 그대로입니다. 경위와 다시 나누려면 먼저 풀어야 할 것은 [PITFALLS § 벡터 스토어 백엔드와 vec/FTS DataSource](PITFALLS.md#벡터-스토어-백엔드와-vecfts-datasource).
>
> **Flyway** — 실데이터가 있는 그 파일에 적용되고 이력(`flyway_schema_history`)도 거기 남습니다. 옛 벡터 DB 파일처럼 테이블은 있는데 이력이 없는 파일은 첫 기동 때 버전 3으로 baseline 됩니다 — 그 파일의 테이블은 옛 버전 저장소들의 런타임 DDL로 만들어져 V1–V3의 내용이 이미 다 있고, baseline을 1로 두면 V2의 `CREATE TABLE users`가 `already exists`로 기동을 멈춥니다. baseline 뒤에는 V4 부터 평소처럼 적용됩니다. 신규 컬럼은 V5 이후의 Flyway 마이그레이션 파일로 추가하는 것이 규약입니다([DATABASE.md §2.1](DATABASE.md#21-운영-테이블의-스키마는-flyway-에만-있다--검색-색인은-예외)).
>
> ⚠️ **Chroma 배포는 저장소가 둘입니다** — 벡터는 Chroma 서버, 레지스트리는 이 SQLite 파일. 인덱싱은 벡터 → FTS → 레지스트리 순서로 쓰며 마지막 레지스트리 커밋이 "색인 완료"의 기준이므로, 백업 시 Chroma 볼륨과 `data/`를 **같은 시점에 함께** 보존하세요(한쪽만 되돌리면 레지스트리와 벡터가 어긋납니다).
>
> **스키마는 기동 시 자동 정비됩니다** — 운영 테이블은 전부 Flyway 마이그레이션이 만들고 고칩니다. 기동할 때 Flyway 가 아직 적용되지 않은 마이그레이션만 순서대로 적용하고, V4(`V4__Consolidate_runtime_schema`)는 옛 버전이 만든 DB 에 빠진 테이블·컬럼·인덱스만 더합니다. 따라서 **오래된 DB 파일을 가져다 놓고 앱을 재기동하면 자동으로 최신 스키마가 됩니다**(기존 행은 보존, 새 컬럼은 기본값 또는 `NULL`). 기동 로그의 `[FLYWAY] V4 — 테이블 생성 […], 컬럼 추가 […]` 한 줄이 무엇을 더했는지 알려 줍니다. 검색 색인(`chunk_fts`·`chunk_fts_key`·`vec_*`)은 Flyway 가 아니라 위 표의 컴포넌트가 만듭니다. 반대로 앱이 실행 중일 때 DB 파일을 교체하면 열려 있는 커넥션과 어긋나 손상될 수 있으니, 반드시 **앱을 내린 뒤** 교체하세요.
>
> **DB 의 시각은 전부 UTC 입니다** — 대화 목록·큐레이션·지식 제안·청크 신고의 시각은 예전에 **서버 로컬 시각**으로 저장됐습니다. 이 버전으로 처음 기동할 때 V9(`V9__Local_timestamps_to_utc`)가 그 값들을 **지금 JVM 의 시간대 기준으로** UTC 로 옮기고 `[V9] server-local timestamps -> UTC (zone=…): N converted, M left as-is` 한 줄을 남깁니다. 서버 시간대가 UTC 였다면(흔한 컨테이너) 바뀌는 값이 없습니다. **DB 를 시간대가 다른 서버로 옮길 계획이라면 옮기기 전 서버에서 이 버전을 한 번 기동해 V9 를 적용한 뒤 옮기세요** — 옮긴 뒤 처음 기동하면 옛 값이 새 서버의 시간대로 해석됩니다(값에 시간대 표시가 없어 사후에 바로잡을 수 없습니다). 화면은 시각을 보는 사람(브라우저)의 시간대로 표시하고, 마우스를 올리면 원래 UTC 값이 보입니다.

> **`doc_registry.chunk_overlap`**: 문서를 인덱싱(또는 ↺ 재인덱싱)한 시점에 실제로 적용된 `app.chunk-overlap` 값을 문서별로 함께 기록합니다 — 관리자 매뉴얼 §3.3 문서 내보내기가 이 값을 읽어 청크 재조립 시 overlap을 정확히 제거하는 데 씁니다. 이 컬럼이 추가되기 전에 인덱싱된 문서는 `NULL`로 남아 있다가, 기동 시 `ChunkOverlapBackfill`이 한 번 그 시점의 `app.chunk-overlap` 현재값으로 채웁니다(이미 값이 있는 행은 건드리지 않음 — 멱등). 운영자가 직접 조작할 일은 없는 내부 컬럼입니다.

#### 6.2.2 DB 백업과 복구

앱이 `memory.db` 의 사본을 **매일 03:30**(`app.time-zone`, 기본 KST) 에 떠서 `DATA_DIR/db-backups/` 에 **최근 7개**를 남깁니다. `/admin` 의 **"DB 백업"** 카드에서 설정·마지막 실행 결과·남아 있는 사본을 보고, **[지금 백업]** 으로 바로 뜰 수 있습니다(감사 로그 `admin.db-backup.run`).

| 설정 | 환경변수 | 기본값 | 설명 |
|---|---|---|---|
| `app.backup.enabled` | `DB_BACKUP_ENABLED` | `true` | 정기 백업. `false` 여도 `/admin` 의 수동 백업은 됩니다 |
| `app.backup.cron` | `DB_BACKUP_CRON` | `0 30 3 * * *` | Spring cron(초 분 시 일 월 요일). **`app.time-zone`(`APP_TIME_ZONE`, 기본 `Asia/Seoul`)의 시각**이고, 백업 파일 이름의 시각도 같은 시계입니다 — JVM·컨테이너의 시간대와 무관합니다. `/admin` 카드가 cron 옆에 시간대를 함께 보여 줍니다 |
| `app.backup.keep` | `DB_BACKUP_KEEP` | `7` | 남길 사본 수(최소 1). 넘으면 오래된 것부터 지움 |
| `app.backup.dir` | `DB_BACKUP_DIR` | (빈 값 = `DATA_DIR/db-backups`) | 사본 위치 |

모두 **재기동 필요**입니다.

**어떻게 뜨는가** — 앱의 커넥션 풀이 아닌 별도 연결에서 `VACUUM INTO` 를 실행합니다. 원본에 읽기 트랜잭션 하나만 쥐므로(WAL) **백업 중에도 채팅·인덱싱의 쓰기가 막히지 않고**, 아직 `memory.db-wal` 에만 있는 최근 기록까지 담긴 일관된 한 파일이 나옵니다(파일을 그냥 복사하면 그것을 놓칩니다). 뜬 사본을 열어 `PRAGMA quick_check` 가 `ok` 일 때만 `memory-yyyyMMdd-HHmmss.db` 이름을 붙이므로, 그 이름의 파일은 전부 검사를 통과한 완성본입니다(중간에 죽으면 `.partial` 이 남고 다음 실행이 지웁니다). 실측: 54 MB DB 에 약 1초.

**담기는 것** — 대화 기록·계정·설정 오버라이드·큐레이션 Q&A·지식 제안·문서 레지스트리·키워드 색인(`chunk_fts`)·**sqlite-vec 백엔드의 벡터**.
**담기지 않는 것** — 문서 원본·변환본·이미지(`documents/` · `converted/` · `images/` — 파일이라 따로 복사합니다), **Chroma 백엔드의 벡터**(Chroma 서버/볼륨에 있습니다 — Chroma 배포는 그 볼륨도 함께 백업해야 같은 시점으로 되돌릴 수 있습니다), 감사 로그.

> ⚠️ 기본 위치는 **같은 디스크**입니다. 잘못된 삭제·DB 손상에는 대비가 되지만 디스크 고장에는 아닙니다 — `DB_BACKUP_DIR` 을 다른 디스크로 두거나, `db-backups/` 를 주기적으로 다른 곳에 복사하세요. 사본은 일반 SQLite 파일(저널 모드 `delete`)이라 그대로 복사해도 됩니다.

**복구**

```bash
# 1) 앱을 멈춘다 (docker compose stop app / 서비스 중지)
# 2) 지금 DB 를 옆으로 치우고(혹시 모를 되돌리기용) WAL 조각을 지운다
mv data/memory.db data/memory.db.before-restore
rm -f data/memory.db-wal data/memory.db-shm
# 3) 사본을 memory.db 로 놓는다
cp data/db-backups/memory-20261013-033000.db data/memory.db
# 4) 앱을 띄운다 — Flyway 가 사본 이후에 추가된 마이그레이션만 적용한다
```

- **`memory.db-wal` · `-shm` 을 반드시 지웁니다.** 남아 있으면 SQLite 가 그 WAL 을 새 파일 위에 재생하려 해 DB 가 깨질 수 있습니다.
- 옛 `SQLITE_VEC_DB_PATH` 를 쓰는 배포는 `memory.db` 대신 **그 경로의 파일**이 대상입니다([§6.2.1](#621-sqlite-파일별-테이블-구성)).
- 사본 시점 이후에 올린 문서는 레지스트리에 없으므로 **디렉터리 동기화**(`/documents` 의 동기화)를 한 번 돌리면 다시 인덱싱됩니다. 사본 이후에 지운 문서는 반대로 목록에 남아 있다가 같은 동기화에서 정리됩니다.

### 6.3 성능

- **Java 21 Virtual Threads** (`spring.threads.virtual.enabled=true`) — LLM I/O 동시 요청을 효율적으로 처리
- **배치 멀티 쿼리 검색** — `RetrievalService`가 확장 질의를 1회 배치 임베딩 → 단일 쿼리(chroma) 또는 쿼리별 개별 조회(sqlite-vec) → 가중 RRF 융합(Phase 7-A — 벡터 축 그룹 정규화 + 키워드 축 가중치 외부화). 재시도 시 후보 풀 에스컬레이션, 선택적 LLM 리랭킹(opt-in)
- **쿼리 임베딩 캐시** — 반복·유사 질문은 Caffeine 캐시로 임베딩 재호출 없이 처리 (`SEARCH_QUERY_EMBED_CACHE_*`)
- **인덱싱-검색 캐시 분리** — 청크 임베딩(인덱싱)은 위 쿼리 임베딩 캐시를 우회해 대량 문서 인덱싱이 직전 검색 질의의 캐시 엔트리를 밀어내지 않는다(§10.9.4). 캐시 키는 질의 원문 대신 SHA-256 해시를 사용해 엔트리 크기가 고정됨
- **sqlite-vec 벡터 BLOB 직렬화** — 벡터를 JSON 텍스트 리터럴 대신 raw float32 BLOB로 삽입/KNN 질의에 바인딩해 vec0의 파싱 비용과 전송 크기를 줄인다(§10.9.2). 기존에 인덱싱된 데이터와 완전히 호환되어 재인덱싱 불필요
- **Chroma 배치 검색 응답 축소** — `RetrievalService`의 배치 멀티 쿼리 검색이 Chroma에 결과 재구성 시 실제로 쓰지 않는 임베딩 벡터 필드까지 요청하던 것을 메타데이터·문서·거리 3개 필드만 요청하도록 축소(§10.9.1) — 리랭킹 활성 시(질의 여러 개 × 후보 다수 × 임베딩 차원) 검색 1회당 전송·파싱·GC되는 데이터 크기가 눈에 띄게 줄어든다. sqlite-vec 백엔드는 원래 임베딩을 응답에 포함하지 않으므로 영향 없음
- **sqlite-vec 인덱싱 스트리밍 삽입** — `SqliteVecVectorStoreProvider.add()`가 문서 전체 청크의 임베딩을 힙에 모은 뒤 한 번에 삽입하던 것을, 토큰 서브배치(§10.8.2와 동일한 배치 단위) 하나가 임베딩되는 즉시 그 서브배치만 삽입하는 구조로 전환(§10.9.3) — 대용량 문서(500+청크)를 인덱싱할 때 피크 메모리가 문서 크기가 아니라 서브배치 크기에 비례하게 된다. 서브배치별 두 테이블 삽입은 여전히 하나의 트랜잭션으로 묶인다(§10.8.3)
- **Contextual Retrieval + 임베딩 입력 정규화** — 인덱싱 시 청크별로 `{파일명} > {섹션 제목}` 구조적 맥락 + LLM 생성 1~2문장을 임베딩·FTS 입력 앞에 결합(`KeywordExtractor`가 키워드 추출과 한 번에 처리, 사용량은 `context:` 라벨). 마크다운 장식(구분선·강조 마커)은 임베딩/FTS/답변 프롬프트 입력에서만 제거되고 저장·표시 원문은 그대로 유지된다. 설정 프로퍼티 없음(항상 적용) — 기존 문서는 재인덱싱해야 새 맥락/정규화가 반영됨
- **한국어 FTS 트라이그램 토크나이저** — `chunk_fts`가 `unicode61`(공백 구분 단어) 대신 `trigram`(3자 겹침 윈도우) 토크나이저를 사용해 활용형 종결어미가 붙은 한국어 단어(예: 질의 "인덱싱"이 본문 "인덱싱됩니다"에 매칭)와 코드/식별자 부분 문자열(예: "ERR45"가 "ERR4521"을 찾음)을 더 잘 찾는다. **자동 마이그레이션** — 기존 `unicode61` 테이블은 다음 재기동 시 자동으로 trigram으로 재구축되며(`doc_tags`/`content`/`keywords` 손실 없이 복사) 별도 재인덱싱·재동기화가 필요 없다. 트레이드오프: 2글자 이하 검색어(예: "오류", "문서")는 trigram 최소 매칭 단위(3자) 미만이라 진짜 BM25 순위 점수는 얻지 못한다 — §10.7.3에서 `content`/`keywords` `LIKE` 스캔으로 존재 여부 기반 신호(순위 없음, MATCH 결과보다 낮은 우선순위로 배치)를 보충해 완전히 탈락하지는 않는다(하이브리드 벡터 축은 애초에 무관하게 동작). **그 스캔은 짧은 어절이 MATCH 결과 본문에 없을 때만 돈다** — `LIKE` 는 인덱스를 못 써 비용이 코퍼스 크기에 비례하는데, 2음절 어절은 한국어 질문에 거의 항상 하나는 들어 있어 예전 게이트(MATCH 가 topK 를 못 채웠는가)로는 평범한 질의마다 전체 스캔이 한 번씩 돌았다 — `SEARCH_HYBRID_ENABLED=true`일 때만 체감. 설정 프로퍼티 없음(항상 적용)
- **병렬 인덱싱** — `RagService.syncDirectory()`에서 파일별·LLM 호출별 Semaphore 기반 병렬 처리

CPU/메모리 제약이 있는 환경에서는 `INDEXING_MAX_FILES`와 `INDEXING_MAX_LLM`을 줄이세요.

### 6.4 검색 품질 평가 하네스 (개발자용)

관리자 매뉴얼 §2의 검색 튜닝 값(유사도 임계값·RRF 가중치·재랭크 등)을 바꾼 뒤 "정말 좋아졌는지" 정량으로 확인하기 위한 하네스입니다. `RetrievalService.execute()`(MultiQuery+하이브리드 BM25+가중 RRF 전체 파이프라인)를 실제 임베딩·LLM 서버와 실제로 색인된 문서에 대해 그대로 실행해 recall@k·nDCG@k를 측정합니다.

```bash
# .env(또는 OS 환경변수)에 설정된 실제 LLM/임베딩 엔드포인트 + data/의 실제 색인을 사용
mvn test -Dtest=SearchQualityEvaluationTest -Dsearch-eval.enabled=true
```

- **기본적으로 skip됩니다** — `-Dsearch-eval.enabled=true`가 없으면 컨텍스트조차 띄우지 않고 즉시 skip되므로(`SqliteVecIntegrationTest`의 `sqlitevec.path` 게이팅과 동일한 패턴) 일반 빌드/CI에는 영향이 없습니다.
- **읽기 전용**입니다 — `search()`/`searchBatch()`만 호출하며 색인을 추가·삭제하지 않습니다. 실행 전 골든셋 대상 문서가 `version=latest`로 이미 색인되어 있어야 합니다.
- 골든셋은 `src/test/resources/search-eval/nexcore-gold.json`(질문 26건) — 정답은 chunk id가 아니라 색인 원문(교정본 MD)에서 그대로 가져온 고유 부분 문자열이라 재인덱싱으로 청크 경계가 바뀌어도 깨지지 않습니다. 다른 코퍼스로 검증하려면 같은 형식으로 새 JSON을 만들고 `GOLD_RESOURCE` 상수(`SearchQualityEvaluationTest.java`)를 바꾸면 됩니다.
- recall@k/nDCG@k 계산 자체(`SearchQualityMetrics`)는 순수 함수라 `SearchQualityMetricsTest`로 항상 검증되며 라이브 서버가 필요 없습니다.
- **2026-07-16 실측 baseline**(hybrid=true·rerank=false·multiquery=true·topK=7): mean recall@10=0.962, nDCG@10=0.810 — 검색 튜닝 변경 후 이 수치와 비교해 회귀 여부를 판단하세요.

### 6.5 청크 분할 전략 (크기 기준 병합 / 소제목 최대 분할)

`app.chunk-split-granular`(`CHUNK_SPLIT_GRANULAR`, 기본 `false`)로 청킹 방식을 고릅니다. **`/settings`에서 재기동 없이 전환**되며(관리자 매뉴얼 §2), **적용 시점은 다음 인덱싱 또는 ↺ 재인덱싱**입니다.

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

> **주의 1**: 최대 분할은 청크 수가 늘어나므로 **인덱싱 시간·임베딩 호출·키워드 추출 LLM 호출이 함께 증가**합니다(청크당 비용이 곱해짐). 대량 문서에 일괄 적용하기 전에 문서 1~2건으로 먼저 재인덱싱해 시간을 재보세요. 검색 품질 변화를 수치로 보려면 §6.4 평가 하네스를 사용하세요.
>
> **주의 2**: `SEARCH_TOP_K`(기본 10)는 그대로인데 청크가 잘게 쪼개지면 한 번에 들어오는 총 컨텍스트 양이 줄어듭니다. 최대 분할로 바꾼 뒤 답변이 얕아지면 `SEARCH_TOP_K`를 함께 올리세요 — 둘 다 `/settings` 핫 수정 대상입니다.
>
> **주의 3**: 문서 내보내기(관리자 매뉴얼 §3.3)의 재조립은 마커·헤딩 기반이라 두 전략 모두에서 동작하지만, 청크 수가 늘면 이어붙일 경계도 늘어납니다. `CHUNK_OVERLAP`이 `0`이면(기본) 유일한 휴리스틱 단계가 no-op이라 영향이 없습니다.

---

## 7. 벡터 스토어 운영 참고

벡터 스토어를 어떤 백엔드로 둘지, 검색 튜닝 값을 왜 그렇게 두었는지를 다룹니다. `/admin` 화면에서 청크를 조회·편집·재인덱싱하는 방법은 관리자 매뉴얼의 「벡터 스토어(청크) 관리」를 보십시오.

### 7.1 백엔드별 표시 차이 (레이아웃·기능은 동일)

| 항목 | chroma | sqlite-vec |
|------|--------|------------|
| 상태 카드 backend 배지 | `chroma` | `sqlite-vec` |
| 상태 카드 — 문서 수 | 미표시(컬렉션은 distinct 문서수 미추적) | 표시 |
| 상태 카드 — 컬렉션 수 | 표시 | 미표시 |
| 상태 카드 — `vec_version` / 차원 | 미표시 | 표시 |
| ChromaDB 연결 불가 경고 배너 | 연결 실패 시 표시 | 표시 안 함 |
| 목록 패널 라벨(문서 레지스트리 왼쪽) | "ChromaDB 컬렉션" | "버전 (sqlite-vec)" |
| 청크 데이터 소스 | `ChromaApi` | `vec_document_chunks` 테이블 |

> 공통: 상태 배지·청크 수·버전별 청크 수·청크 조회/편집/삭제·문서 레지스트리·MD 재인덱싱은 두 백엔드 동일.

### 7.2 키워드 축 가중치를 0.5로 두는 이유 (한/영 혼재 코퍼스)

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

**언제 다시 올리나**: 코퍼스가 **단일 언어**이고 질문도 문서와 같은 표기를 쓰며, 파일명·오류코드 같은 정확 매칭이 의미 검색보다 중요하다면 `1.0`으로 되돌릴 만합니다. `/settings`에서 핫 수정되므로 재기동 없이 바꿔 가며 [관리자 매뉴얼 §4.7](ADMIN_MANUAL.md#47-검색-진단-수치--검색-튜닝-근거-보기) 패널의 `axisRanks`로 확인하세요.

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
| `OpenAI API key must be set` (`openAiApi`/`OpenAiAudioSpeechModel` 등 빈 생성 실패) | 앱은 채팅·임베딩 모델과 SDK 클라이언트를 전부 직접 만들고(`LlmConfig`/`EmbeddingBeanConfig`) Spring AI의 OpenAI 자동설정 빈은 **하나도 쓰지 않는다**. 자동설정을 켜두면 각 autoconfig가 기동 시 `openAiApi` 빈을 무조건 만들고 `spring.ai.openai.api-key`에 `Assert.hasText`를 걸어, `LOCAL_LLM_KEY`가 비어 있으면 죽는다(채팅·임베딩 자동설정도 마찬가지 — 모델 빈은 `@ConditionalOnMissingBean`으로 스킵되지만 `openAiApi` 빈은 아님). `application.properties`의 `spring.autoconfigure.exclude`에 **OpenAI 모델 자동설정 6종**(`OpenAiChat`/`OpenAiEmbedding`/`OpenAiAudioSpeech`/`OpenAiAudioTranscription`/`OpenAiImage`/`OpenAiModeration`AutoConfiguration)이 모두 제외돼 있어야 한다. 이 줄이 지워졌는지 확인 |

---

### LLM 호출 오류 (500)

```bash
# 임베딩 서버 확인
curl ${EMBED_BASE_URLS:-$LOCAL_LLM_URL}/models -H "Authorization: Bearer ${EMBED_API_KEY:-$LOCAL_LLM_KEY}"

# LOCAL 프로바이더 확인 — 앱이 실제로 쓰는 base-url·모델 id 로, 세 단계를 따로 답한다 (아래 "로컬 LLM 생사 확인")
curl -f http://localhost:8080/api/v1/llm/ping?deep=true
```

#### 로컬 LLM 생사 확인 — `GET /api/v1/llm/ping`

"살아 있는가"의 답은 셋으로 갈립니다. 2026-09-21 의 GPU 소실(`decode() failed: vk::Queue::submit: ErrorDeviceLost`)에서 LM Studio 의 HTTP 서버는 끝까지 살아 있었고(500 과 빈 본문을 *돌려줬습니다*), 모델도 "로드됨"이었으며, 죽은 것은 추론 엔진뿐이었습니다 — `/v1/models` 로는 그 상태를 구분할 수 없습니다.

| 필드 | 무엇을 물었나 | 실패하면 |
|---|---|---|
| `reachable` / `latencyMs` | `GET {baseUrl}/models` 가 2xx JSON 을 주는가 | 프로세스가 없거나 포트·URL 이 다르다 — `error` 에 `Connection refused` 등 |
| `modelListed` | 설정된 모델 id 가 목록에 있는가(G3 와 같은 `ModelNameResolver` 규칙) | 모델명 오탈자, 다른 모델을 띄움. JIT 로딩이 켜진 LM Studio 는 다운로드된 모델을 전부 내므로 이것만으로 "로드됨"은 아님 |
| `modelState` | LM Studio `GET /api/v0/models` 의 `state`(`loaded`/`not-loaded`), llama.cpp 면 `GET /health` 의 `status`, 둘 다 없으면 `unknown` | `not-loaded` 면 LM Studio 에서 모델을 Load |
| `inference` (`?deep=true`) | `max_tokens=1` 채팅 완성 한 번 — 위 둘을 통과하고도 여기서 실패하면 **엔진이 죽은 것** | `error` 에 서버가 준 사유(`HTTP 500 ... ErrorDeviceLost`, `application/octet-stream` 파싱 실패 등). 모델 Eject → Load 또는 서버 재시작 |
| `circuitBlockedSeconds` | 앱의 서킷 브레이커가 이 프로바이더를 막고 있는 남은 초 | 핑은 브레이커를 **우회**하므로 차단 중에도 실제 상태를 알 수 있고, 핑 결과가 차단을 풀거나 만들지도 않습니다 |

- 전부 통과면 **200**, 하나라도 실패면 **503** — 모니터링 스크립트는 `curl -f` 로 씁니다. 로컬 티어(`role=LOCAL, priority=1`) 프로바이더가 없으면 `{"available": false}`(200).
- `deep=true` 는 실제 추론 1토큰이라 비용이 있지만 관리자 전용은 아닙니다 — 채팅이 게스트에게 열린 배포에서는 누구나 이미 전체 답변 생성을 시킬 수 있어 그보다 엄격히 막을 이유가 없습니다(속도 제한은 `default` 버킷 그대로). `timeoutSeconds`(기본 15, 최대 60)는 완성 호출의 읽기 타임아웃 — JIT 로딩 서버가 첫 요청에서 모델을 올리느라 넘길 수 있고, 그것도 "지금은 못 한다"는 참인 답입니다.
- `baseUrl` 은 내부 호스트라 `ROLE_ADMIN` 응답에만 실립니다.
- 헤더의 `LLM: 차단 중 4s` 표시(§5.7)와 짝입니다 — 표시기는 "앱이 지금 기다리는 중"을, 핑은 "서버가 실제로 어떤 상태인가"를 답합니다.

| 원인 | 조치 |
|------|------|
| `OPENAI_BASE_URL` 오탈자 | 끝에 `/v1` 포함 여부 확인 |
| API 키 만료/권한 없음 | 키 재발급 후 재시작 |
| LOCAL LLM 서버 미실행 | LM Studio / Ollama 실행 확인 |
| 로컬 LLM 없이 실행 | `LOCAL_LLM_URL`(및 `LOCAL_LLM_URL_2`·`LOCAL_FAST_LLM_URL`)을 **비워** LOCAL 을 등록 자체에서 빼고(G2 — 키를 비우는 것으로는 비활성화되지 않는다, G1) NORMAL/PREMIUM 등록 |
| 모든 프로바이더 소진 | `GET /api/v1/llm/ping?deep=true` 로 서버 상태부터(위 표); `/llm-usage`에서 차단 상태 확인; 차단은 시간이 지나면 자동 해제됩니다(폴백 있음 30초 또는 `circuit-breaker-minutes`, **폴백 없는 유일 프로바이더는 5초**). **이 메시지는 원인이 아니라 결과입니다** — LOCAL 프로바이더가 하나뿐인 배포에서는 그 하나가 한 번 실패하기만 해도 이 문구가 나옵니다. 진짜 원인은 바로 앞 로그 줄(`Provider [x] threw ...`)에 있습니다 |

---

### 서버 PC에서는 되는데 다른 PC에서 접속하면 채팅이 안 됨

서버를 띄운 PC(`http://localhost:8080`)에서는 정상인데, 같은 망의 다른 PC(`http://10.x.x.x:8080`)에서는 **전송 후 아무 반응이 없고 보낸 메시지조차 표시되지 않는** 경우입니다.

원인은 브라우저의 **secure context** 규칙입니다. `http://localhost`는 예외적으로 secure context로 취급되지만 `http://10.x.x.x`는 아니며, 이때 `crypto.randomUUID()`·`navigator.clipboard` 같은 API가 아예 존재하지 않습니다. **서버 문제가 아니므로 서버 로그에도 아무것도 남지 않습니다.**

먼저 브라우저 콘솔(F12)을 확인하세요:

| 콘솔 메시지 | 원인 | 조치 |
|---|---|---|
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
| 로그에 `[TIMEOUT:LLM_HTTP]` | `LLM_READ_TIMEOUT_SECONDS`(기본 600초) 초과 | 동일. 프로바이더 장애가 아니므로 Circuit Breaker는 차단하지 않음(다른 서버로 넘기지도 않음) |
| 로그에 `[CONNECT-TIMEOUT]` | `LLM_CONNECT_TIMEOUT_SECONDS`(기본 10초) 안에 연결을 못 맺음 — 호스트가 꺼졌거나 방화벽/네트워크 문제 | 그 서버를 차단하고 같은 호출 안에서 다음 서버로 넘어간 것이다. 서버 상태·주소(`LOCAL_LLM_URL`)를 확인 |
| 로그에 `[STREAM-FAILOVER]` | 채팅 답변 스트리밍이 첫 토큰 전에 서버 장애(연결 거부·연결 타임아웃·5xx·429/503)를 만나 다음 서버로 다시 열었다 | 사용자에게는 보이지 않는다. 반복되면 해당 서버(`provider=`)를 확인 |

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
| 로그에 `[EVAL] 발췌 한도(글자 … / 토큰 …)로 N개 중 M개만 검증에 사용 — 하위 순위 문서 제외` 경고 | `SEARCH_TOP_K` × `CHUNK_SIZE`가 글자 상한(32,000)이나 프로바이더 창에서 파생된 토큰 예산을 넘어 하위 문서가 검증에서 제외됨 | 둘 중 하나를 낮추세요. 이 상태에서는 답변이 본 문서 일부를 평가자가 못 봅니다 |

> 검증은 답변을 **버리지 않습니다** — 미검증 배지는 "직접 출처를 확인하라"는 표시이지 실패가 아닙니다. 재시도 자체를 줄이려면 `MAX_RETRY_COUNT`를 낮추세요(`0`이면 재시도 없이 첫 답변을 그대로 전달). 상한을 다 쓰지 않고 끝났다면 조기 종료입니다 — 같은 이유로, 같은 청크를 보고 두 번 반려되면 더 돌지 않습니다(로그 `[AgentGraph] retry stopped early … on the same N chunk(s)`). 그때 고칠 것은 재시도 횟수가 아닙니다: `reason=ANSWER_INSUFFICIENT` 면 검색이 다른 청크를 가져오게 하는 쪽(`SEARCH_TOP_K`·태그 범위·문서 자체), `reason=CRITIC_UNGROUNDED` 면 문서 밖으로 나가는 답변 쪽(위 표의 사유별 조치 — 답변 모델·응답 모드)입니다.

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
| **8k~10k** | — | — | — | **지원하지 않습니다**(2026-10-23 — `LLM_MAX_TOKENS` 하한 8,000. 창이 16K 미만이면 기동 시 WARN). 이 창에서는 하한이 창보다 작아 기동 검사(`max-tokens ≥ 창`이면 창의 절반)가 걸리지 않고, 옵션을 싣지 않는 질의 확장이 8,000 을 예약해 매 턴 넘치며, 스트리밍 답변의 입력 예산도 예전 권장값(`2000`) 대비 절반 아래로 줍니다. 프로바이더별 `max-tokens` 는 이력 예산·답변 예약을 바꾸지 못해 우회로가 아닙니다 |
| **16k** | `8000`(하한) | `6` | `1000` | 발췌 ~6,000자. 예전 권장값 `4000` 보다 이력 예산(4,000자)과 답변 예약이 커져 답변 쪽 문서 자리가 약 1,000토큰 줄었습니다 |
| **32k** | `8000` | `8~12` | `1500` | 기본값(topK **10** × 1500 = 15,000자)이 그대로 들어갑니다 — 4,900 + 15,000 + 2,048 ≈ 22,000 |
| **64k 이상** | `12000`(기본) | `12~15` | `1500~2000` | `MAX_EVAL_EXCERPT_CHARS`(32,000)가 비로소 의미를 갖는 구간 |

- **한글 ≈1토큰/자**는 보수적 계획값입니다. 코드·영문이 섞인 문서는 3~4자/토큰이라 실효 여유는 이보다 낫지만, 좁은 컨텍스트에서는 안전한 쪽으로 잡으세요.
- `MAX_EVAL_EXCERPT_CHARS`(코드 상수, 32,000)는 **병적인 설정을 막는 안전판이지 튜닝 손잡이가 아닙니다.** 실제 발췌량은 `SEARCH_TOP_K × CHUNK_SIZE`가 정하고, 이 상한에 닿았다는 것은 이미 하위 문서가 검증에서 빠지고 있다는 뜻입니다(위 경고 로그). 32k 이하 배포에서는 이 상수에 닿기 훨씬 전에 `n_ctx`가 먼저 터지므로, 조절해야 할 것은 언제나 `SEARCH_TOP_K`/`CHUNK_SIZE` 쪽입니다.
- 답변 프롬프트에도 이제 상한이 있습니다 — 아래 「앱이 창을 알면 스스로 줄인다」 참고. 두 설정은 여전히 답변 품질과 검증 정확도를 동시에 결정하지만, 넘칠 때 조용히 실패하는 대신 줄이고 알려 줍니다.

#### 앱이 창을 알면 스스로 줄인다 (§6.26)

위 표는 **앱이 컨텍스트 창을 모를 때**의 수동 산정입니다. 창을 알면 앱이 프롬프트를 미리 맞춰 줄이므로 표는 "이 값이면 축소 없이 들어간다"는 목표치가 됩니다.

창을 아는 방법은 둘입니다.

1. **선언** — `app.llm.providers[N].context-size=16384`. 항상 이쪽이 우선입니다.
2. **탐지** — 미설정이면 기동 시 LOCAL 프로바이더에게 물어봅니다. llama.cpp 는 `GET /props` 의 `default_generation_settings.n_ctx`, LM Studio 는 `GET /api/v0/models` 의 로드된 인스턴스 컨텍스트.

> ⚠️ **두 서버가 함께 노출하는 "모델 상한" 필드는 일부러 읽지 않습니다.** llama.cpp `/v1/models` 의 `meta.n_ctx_train` 과 LM Studio 최상위 `max_context_length` 는 **모델이 지원하는** 최대치라, `-c 8192` 로 띄운 서버도 131072 를 돌려줍니다. 그 값으로 예산을 짜면 컨텍스트 초과를 막으려던 코드가 오히려 초과를 부릅니다. 로드된 값을 못 찾으면 앱은 **추측하지 않고 "모름"으로 둡니다**.

**확인**: `/settings` 의 프로바이더 표 `컨텍스트` 열 — `8,192 (탐지됨)` / `16,384 (설정됨)` / `미탐지 (입력 제한 없음)`(모름). 기동 로그에도 `ctx=8192/probed` 형태로 찍힙니다. 모를 때 쓰는 **내부 기본 창은 없으므로 숫자를 지어 보이지 않습니다** — **미탐지이면 어떤 자동 보정도 돌지 않는다**(창 기준 사전 축소·출력 예약 보정이 꺼지고 설정된 `max-tokens` 그대로 나간다)는 뜻이므로 위 표대로 손으로 맞추거나 `context-size` 를 선언하세요.

#### 로컬 LLM을 재시작했더니 계속 "모든 프로바이더 사용 불가"

응답 중에 로컬 LLM을 재시작하면 진행 중이던 요청에 `400 - {"error":"terminated"}`가 돌아옵니다. 서버가 **내려가면서 그 요청을 끊었다**는 뜻이지 "서버가 아프다"가 아닙니다.

이 오류는 서버가 *내려가는 순간*에만 나오는데 차단은 서버가 *이미 올라온 뒤*까지 남으므로, 일반 실패로 보고 30초 차단하면 재시작한 뒤에도 요청이 계속 `All providers exhausted` 로 죽습니다 — 그래서 차단하지 않습니다.

지금은 두 가지가 다릅니다.

- **`{"error":"terminated"}`는 차단하지 않습니다** — `NOT blocking circuit breaker; the server is usually back within seconds.` 로그가 대신 남습니다.
- **폴백이 없는 유일 프로바이더는 일반 실패도 5초만 차단합니다**(연결 거부 등). 프로바이더가 하나면 차단은 우회가 아니라 전면 중단이기 때문입니다. 차단을 아예 없애지는 않았습니다 — 서버가 정말 죽어 있으면 모든 요청이 각자 연결 타임아웃을 무는 편이 더 나쁩니다.

사용자 화면에도 **언제부터 다시 되는지**가 표시됩니다 — `AI 서버가 일시적으로 응답하지 않습니다. 20초 후 다시 시도해 주세요.`(`error.llm.exhausted.retry`, 한/영). 차단이 원인일 때만 초가 붙고, 시도했다가 실패해 후보가 없어진 경우엔 예전의 일반 문구(`error.llm.exhausted`)로 나갑니다(기다린다고 풀리는 것이 아니므로). REST 호출에는 같은 값이 `Retry-After` 헤더로 나갑니다. **같은 프로바이더가 연속 3회 실패하면 문구가 바뀝니다** — `AI 서버가 연속 3회 응답하지 않습니다. 서버(모델) 상태를 확인해 주세요. (4초 후 재시도 가능)`(`error.llm.exhausted.repeated`). 5초 차단이 반복되는 죽은 서버(GPU 소실)에서 "잠시 후 다시"는 거짓이기 때문이며, 로그에는 `[REPEATED-FAILURE]` 가 남습니다. 횟수는 성공이 한 번 있어야 0 으로 돌아갑니다(차단이 풀려도 줄지 않음). 문장은 `LlmOutageMessages` 가 고르고, 예외 자체의 한국어 메시지(`(task=TEXT)` 꼬리 포함)는 화면에 나가지 않습니다.

**여전히 이 증상이 보인다면** 먼저 서버가 실제로 어떤 상태인지 묻고(`curl -f http://localhost:8080/api/v1/llm/ping?deep=true` — "LLM 호출 오류 (500)" 의 표), 로그에서 실제 예외를 확인하세요:

```bash
grep -E "threw |blocked for |NO-FALLBACK|REPEATED-FAILURE|LLM_PING" logs/rag-agent.log | tail -30
```

> **"4초 후 다시 시도" 가 거짓이 되는 경우** — 유일 프로바이더는 실패해도 5초만 차단하므로(위) 사용자 문구의 초는 그 잔여 시간입니다. GPU 소실처럼 서버 프로세스는 살았는데 엔진이 죽은 경우에는 5초가 지나도 같은 실패가 반복됩니다 — 그래서 **연속 3회째부터는 문구가 "서버(모델) 상태를 확인해 주세요" 로 바뀌고** 로그에 `[REPEATED-FAILURE]` 가 찍힙니다. 이때 `ping?deep=true` 가 `reachable=true, modelState=loaded, inference.ok=false` 를 내면 앱이 아니라 LLM 서버를 고칠 차례입니다(모델 Eject → Load, 런타임 Vulkan → CUDA, 컨텍스트·GPU 오프로드 축소, 드라이버).

`Provider [x] threw ...` 줄이 진짜 원인입니다. `blocked for 30s`가 보이면 폴백이 있는 구성이라는 뜻이고, `blocked for 5s`면 유일 프로바이더 경로입니다.

#### 창이 바뀌었는데 앱이 모를 때 — 컨텍스트 다시 탐지 (§6.26 A5)

탐지값은 **기동 시점의 관측**입니다. 다음 셋 중 하나면 앱이 들고 있는 숫자가 실제와 다릅니다.

1. 서버를 다른 `--ctx-size` / `-c` 로 다시 띄웠는데 앱은 재기동하지 않았다
2. LM Studio 에서 컨텍스트를 바꿔 모델을 다시 로드했다
3. **LM Studio 의 JIT 로딩** — 앱이 뜰 때는 로드된 인스턴스가 없어 탐지가 빈손으로 끝났고, 그 뒤 모델이 올라왔다 (`컨텍스트` 열이 `미탐지` 로 남아 있습니다)

**피해가 방향에 따라 다릅니다.** 창을 *줄인* 경우는 컨텍스트 초과로 요란하게 드러나고 축소 재시도가 받아냅니다. 문제는 창을 *키운* 경우입니다 — 앱은 옛 창 기준으로 계속 문서를 자르고, 사용자에게는 `컨텍스트 한도로 … 6개만 사용했습니다` 만 뜰 뿐 **왜 그런지는 아무 데도 안 나옵니다.** 창을 키웠는데 답변 품질이 그대로라면 이걸 의심하세요.

**해결**: `/settings` 의 **LLM 라우팅** 카드 제목 줄 오른쪽 끝의 **컨텍스트 다시 탐지** 버튼(관리자만 보입니다). 등록된 LOCAL 프로바이더에게 지금 다시 물어보고, 프로바이더마다 결과를 표 위에 남깁니다.

| 결과 | 뜻 |
|---|---|
| **갱신됨** | 값이 실제로 달라졌습니다. 다음 호출부터 새 예산이 적용됩니다 |
| **변화 없음** | 물어봤고 예전과 같았습니다 |
| **응답 없음 — 이전 값 유지** | 서버가 답하지 않았거나 로드된 컨텍스트를 찾지 못했습니다. **알던 값은 지우지 않습니다** — 되돌리면 그 순간부터 입력 예산이 통째로 꺼지기 때문입니다 |
| **선언됨 — 탐지 안 함** | `context-size` 가 선언된 프로바이더입니다. 선언이 항상 탐지를 이기므로 묻지 않습니다. 값을 바꾸려면 `context-size` 를 고치고 재기동하세요. (화면의 [편집]에서 넣은 수동 컨텍스트는 여기 해당하지 않습니다 — 탐지가 되면 **갱신됨**으로 탐지값이 덮고, 안 되면 **응답 없음 — 이전 값 유지**로 남습니다) |

> **출력 상한은 함께 따라옵니다** (§6.26 A6): 앱의 모든 블로킹 호출은 자기 옵션을 실어 보내고, 그 위에 씌워지는 프로바이더 상한이 호출마다 현재 창 · 현재 `LLM_MAX_TOKENS` 로 다시 계산됩니다. 재기동이 필요한 곳은 **옵션을 실어 보내지 않는** 프레임워크 내부 호출(쿼리 확장 등)이 쓰는 기동 시점 기본값 하나뿐이고, 그 값이 **좁아진 새 창을 넘게 될 때만** 결과 위에 재기동 경고가 뜹니다(예: `local: defaultOptions 8,000 ≥ 창 4,096`). 창이 넓어진 경우는 그 기본값이 작을 뿐 안전하므로 알리지 않습니다.

> **주기적 자동 재탐지는 일부러 넣지 않았습니다.** 예산이 스스로 움직이면 같은 질문이 시각에 따라 다른 양의 근거를 받아 재현이 안 됩니다(`[TOKEN_CAL]` 계수를 자동 보정하지 않는 것과 같은 이유). 창을 자주 바꾸는 배포라면 아예 `context-size` 로 못 박는 편이 낫습니다 — 선언된 값은 애초에 낡지 않습니다.
>
> **단, 컨텍스트 초과가 나면 그 프로바이더의 창을 한 번 자동으로 다시 잽니다.** 초과 자체가 "기록된 창이 실제보다 크다"는 서버의 증언이라 근거가 있기 때문입니다. 채팅 화면(스트리밍)과 블로킹 호출 모두에 걸리고(채팅 화면은 2026-10-19 부터), 버튼과 같은 규칙을 따릅니다 — `context-size` 선언 프로바이더는 묻지 않고(화면의 수동 컨텍스트는 탐지가 되면 덮입니다), 탐지에 실패하면 알던 값을 지우지 않으며, LOCAL 만 대상입니다. 초과는 연달아 오므로 프로바이더마다 1분에 한 번만 묻고, 실패한 요청을 더 늦추지 않도록 백그라운드에서 돕니다. **창이 커진 경우는 초과가 나지 않아 잡히지 않으므로** 그때는 위 버튼을 누르십시오.

#### 화면에서 프로바이더 고치기 — [편집] · [로컬 프로바이더 추가]

`/settings` 의 **LLM 라우팅** 표에서 관리자는 행마다 [편집], 제목 줄의 [로컬 프로바이더 추가]로 프로바이더를 고칠 수 있습니다. 고친 값은 DB(`llm_provider_override`)에 남고 `.env`/`application.properties` 위에 얹힙니다.

| 무엇을 | 언제 반영 | 비고 |
|---|---|---|
| **컨텍스트**(토큰) | **저장 즉시** | **자동 탐지가 안 될 때만** 넣을 수 있습니다(vLLM·Ollama 등, 원격 프로바이더, 탐지가 실패한 로컬 서버) — 탐지값이 있거나 `context-size` 가 선언됐으면 칸이 잠깁니다. "수동 입력"으로 표시되며, 나중에 탐지(기동·재탐지 버튼·컨텍스트 초과 뒤 자동 재탐지)가 성공하면 탐지값으로 바뀌고, 탐지가 실패하면 넣은 값이 유지됩니다. 비우면 (LOCAL 이면) 지금 다시 탐지 → 모름 순으로 돌아갑니다 |
| 접속 주소 · 모델 · type · role · 우선순위 | **재기동 뒤** | 그동안 이름 옆에 `재기동 후 적용: …` 배지. 라우터가 기동 때 프로바이더를 만들어 고정해 두기 때문입니다 |
| 추가 · 삭제 | **재기동 뒤** | 추가는 **LOCAL 만** 됩니다. 저장할 때 서버의 모델 목록을 확인하고, 모델이 없으면 저장하지 않습니다(닿지 않으면 저장하되 알려 줍니다) |

- **API 키는 화면에서 받지 않습니다** — `.env` 에서만 설정하세요. 그래서 키가 있는 프로바이더는 접속 주소의 **호스트·포트를 바꿀 수 없습니다**(키가 새 주소로 나갑니다). 같은 호스트 안의 경로만 바꿀 수 있습니다.
- **[되돌리기]** 는 화면에서 남긴 것을 지웁니다 — 컨텍스트는 즉시, 나머지는 재기동 뒤 설정 파일 값으로 돌아갑니다.
- **화면 값 때문에 기동이 멈추지는 않습니다.** 화면에서 고친 LOCAL 프로바이더가 기동 때 모델 검증에 실패하면, 그 실행은 설정 파일 값으로 뜨고(추가한 것이면 등록하지 않습니다) 로그에 `[LLM STARTUP CHECK] provider=[…] /settings 에서 고친 값(…)으로 검증에 실패했다` ERROR 가, 표에 `기동 검증 실패` 배지가 남습니다. 고친 값은 그대로 남아 있으니 고치거나 되돌리고 다시 기동하세요.
- 재기동 뒤 등록될 프로바이더가 하나도 남지 않는 편집(LOCAL_ONLY 배포에서는 LOCAL 이 하나도 남지 않는 편집)은 거절됩니다.
- 이름이 같은 프로바이더(같은 모델을 키만 달리 둔 짝)는 한 논리 모델이라 편집도 함께 받습니다.


창을 알 때 자동으로 도는 것:

- **기동 시 검사** — `max-tokens >= context-size` 면 입력에 남는 자리가 0 이하라 **모든 요청이 실패**합니다(설정만 보면 멀쩡해 보입니다). 창의 절반으로 낮추고 WARN 을 남깁니다.
- **최소 창 검사** — 알려진 창(선언·탐지)이 **16K**(`LLM_MAX_TOKENS` 하한 8,000 × 2) 미만이면 `[LLM CONFIG] provider=[…] context-size(…, 탐지) < 16000 — 지원하는 최소 컨텍스트(16K)보다 작습니다(기동 때 확인)` WARN 을 남깁니다. 기동 때만이 아니라 **재탐지 때도** 봅니다 — 위 [컨텍스트 다시 탐지] 버튼(결과 줄에 `16K 미만 — 지원 밖` 배지)과 컨텍스트 초과 뒤의 자동 재탐지(로그의 `(재탐지 때 확인)`·`(자동 재탐지 때 확인)`). 막지는 않지만 지원하지 않는 구성입니다(§8 산정표) — 서버를 16K 이상으로 띄우세요.
- **호출 전 축소** — `입력 예산 = 창 − 출력 예약 − 여유(창의 10%)`. 넘치면 **잃는 것이 작은 것부터** 한 조각씩 덜어냅니다(축소 사다리, PLAN §6.30): ① 직전 질문을 뺀 과거 질문을 500자로 → ② 이전 대화를 직전 1턴만 → ③ 검색 문서를 하위 순위부터 **`SEARCH_TOP_K` 의 절반까지** → ④ 직전 Direct 답변을 요약으로 → ⑤ 이전 대화 전부(후속 질문이면 다시 쓴 질문 한 줄로 대신) → ⑥ 문서를 1개까지. 들어가면 멈추고, 남는 자리에는 앞에서 뺀 것을 되돌려 채웁니다(문서는 순위가 높은 것부터, 대화는 최근 턴부터). 검색 결과가 하나도 없어지지는 않습니다(최상위 문서는 항상 남습니다).
- **사용자 안내** — 축소가 일어난 답변에는 `컨텍스트 한도로 검색된 문서 10개 중 6개만 사용했습니다.`(이전 대화를 줄였으면 `…, 이전 대화 일부를 제외했습니다` / `이전 대화를 제외했습니다` / `이전 대화 대신 다시 쓴 질문으로 답했습니다`)가 붙고, 프롬프트에 실리지 못한 출처에는 `미사용` 배지가 붙습니다. 출처 목록은 검색된 전부를 그대로 보여주므로, 이 표시가 없으면 모델이 읽지 않은 문서를 읽은 것처럼 보게 됩니다. ①(과거 질문 자르기)만 쓴 축소는 안내하지 않습니다.
- 로그: `[BUDGET] 컨텍스트 창 …토큰(출력 예약 …) → 입력 예산 …토큰 → 축소 사다리 칸 6까지: 문서 10→4개(바닥 5), 이력 5000→0자 (재시도 축소 단계 0)` — "칸"은 실제로 쓴 가장 깊은 단계입니다(창을 모르면 앞부분이 `창 모름`).
- **인덱싱(MD 교정·txt→md 구조화)도 같은 창을 봅니다** — 다만 잘라내는 대신 **조각을 더 잘게 나눕니다**(버려지는 내용 없음). 재작성은 출력이 입력에 비례하므로 `조각 ≤ (창 − 지시 프롬프트 − 여유) / 2.5` 로 잡고, `LLM_MAX_TOKENS` 파생 상한보다 작을 때만 적용합니다. 창을 모르면 예전 동작 그대로입니다. 창 20,480 · `LLM_MAX_TOKENS=10000` 에서 MD 교정이 `Context size has been exceeded` 로 실패하던 조합이 이 계산으로 막힙니다 — **창을 넓히지 않고도** 교정이 끝까지 돕니다.

> **축소가 잦다면** `SEARCH_TOP_K` → `CHUNK_SIZE` → 서버 `--ctx-size` 순으로 손보세요. 축소는 안전장치이지 정상 상태가 아닙니다 — 하위 문서가 매번 빠진다면 그 문서들은 애초에 검색될 필요가 없었거나, 창이 이 설정에 비해 좁은 것입니다.

- **그래도 넘치면 같은 사다리를 한 걸음씩 더 내려가며 다시 시도합니다** — 로그 `[SHRINK] ANSWER 컨텍스트 초과 — 한 걸음 더 줄여 다시 시도한다(…). 시도 1/7 (app.llm.shrink-step=1)`, 그 시도에서 무엇을 뺐는지는 바로 뒤의 `[BUDGET]` 줄에 나옵니다. 한 걸음은 이전 대화 단계 하나이거나 문서 `LLM_SHRINK_STEP` 개(기본 **1**, 범위 1~10, `/settings` 에서 핫 수정)입니다. 재시도 횟수는 "적용할 수 있는 이전 대화 단계 수 + 문서 걸음 5"까지이므로, 문서 쪽은 **`LLM_SHRINK_STEP × 5` 가 도달 가능한 최대 축소폭**입니다 — 거기서도 안 되면 `RAG-LLM-003` 이 나갑니다. 창을 모르는 배포에서도 이 재시도는 돕니다(사전 축소만 쉰다). 검증 호출의 재시도는 이전 대화가 없어 발췌만 덜어냅니다(5회까지). **검색을 건너뛰는 Direct 답변도 같은 이력 칸으로 다시 시도합니다** — 문서가 없으므로 과거 질문 500자 → 직전 1턴만 → 이력 전부 순으로 내려가고(앞과 같아지는 칸은 건너뜀), 로그는 `[SHRINK] DIRECT …`, 줄였으면 답변에 이력 축약 안내가 붙습니다. 블로킹·스트리밍 모두이고, 스트리밍은 토큰이 이미 나간 뒤에는 하지 않습니다. 컨텍스트 초과를 볼 때마다 창 재탐지가 한 번 걸립니다.
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
| **메모리 부족(OOM kill)** | `docker inspect <container> --format '{{.State.OOMKilled}}'`가 `true`면 이것입니다. **앱 로그에는 아무것도 남지 않습니다** — 커널이 프로세스를 즉시 죽이기 때문에 `OutOfMemoryError` 스택조차 없습니다. 대형 문서 인덱싱 중에 반복된다면 `mem_limit`을 올리거나(없다면 호스트 여유 메모리 확인), `INDEXING_MAX_FILES`/`INDEXING_MAX_LLM`을 줄여 동시 처리량을 낮추세요. 힙만 좁히려면 `.env`의 `JAVA_OPTS`로 `-Xmx`를 직접 지정합니다([§4.1.3](#413-메모리jvm-힙-설정)) |

---

### 인덱싱이 느림

- `INDEXING_MAX_FILES` / `INDEXING_MAX_LLM` 값 증가 (CPU·API 쿼터 여유 있는 경우)
- 키워드+맥락 추출(`KeywordExtractor`)이 청크당 LLM 호출 → 문서 수 많을수록 시간 증가 (의도된 동작)
- DOCX·TXT·PPTX·PDF(스캔 아님)는 LLM 포맷 교정(섹션당 1회 LLM 호출)이 추가되어 스캔 PDF(OCR만 수행, 포맷 교정 없음)보다 인덱싱 시간이 더 길 수 있습니다. 교정 실패 시 원본 MD로 fallback됩니다.
- 청크 하나하나가 `INDEXING_KEYWORD_TIMEOUT_SECONDS`(기본 600초)에 가깝게 걸리고 있다면, 로컬 LLM 서버가 그 시점에 실제로는 거의 응답하지 못하고(과부하·모델 교체·행) 매 호출이 타임아웃까지 채운 뒤 TF 폴백으로 넘어가고 있다는 신호입니다 — 문서 하나가 수 시간 걸렸다면 먼저 이것부터 의심하세요. `[TIMEOUT:INDEX_KEYWORD]`/`[TIMEOUT:INDEX_KEYWORD_BATCH]` 로그 빈도로 확인 가능(아래 "로컬 LLM 응답 타임아웃"의 로그 키 표 참고).

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

- **`[TIMEOUT:SSE_IDLE]`** — 에이전트 그래프가 `app.sse-idle-timeout-seconds`(기본 300초) 동안 노드 전환·토큰·생각 중 신호·소스 준비 신호를 전혀 못 받음(분류·확장·검증 같은 블로킹 LLM 호출이 응답을 기다리는 동안은 활동으로 보므로 세지 않습니다 — 그런 호출이 멈추면 `LLM_READ_TIMEOUT_SECONDS` 가 끊습니다). 로컬 LLM 서버(LM Studio 등)가 요청을 받고도 응답을 전혀 생성하지 못하는(멈춘) 경우가 전형적입니다. **응답이 느리더라도 토큰이 계속 나오고 있다면 이 타임아웃에 걸리지 않습니다** — 매 신호마다 리셋되기 때문입니다.
- **`[TIMEOUT:SSE]`** — `app.sse-timeout-seconds`(기본 7200초) 절대 상한 초과. 응답이 활동 중이어도(토큰이 계속 나와도) 총 소요 시간이 이 값을 넘으면 발생 — 극히 드묾, 안전장치 성격.

| 원인 | 확인 방법 | 조치 |
|------|----------|------|
| LLM 서버 미실행·모델 미로드 | LM Studio 상태 확인 | 모델 로드 완료 후 재시도 |
| `base-url`에 `/v1` 중복 | 시작 로그 `endpoint=...` 확인 | `base-url`에 `/v1` 포함 여부와 무관하게 내부 자동 처리됨. 앱 재시작 |

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

> **접근 권한**: `/actuator/**`(`/actuator/health` 제외)는 **전체 인증 모드와 관리 전용 인증 모드에서 `ROLE_ADMIN`**입니다 — 레벨을 TRACE/DEBUG로 올리는 순간 프롬프트 전문(검색된 문서 원문 포함)이 로그 파일에 쌓이므로 인가 관점에서 관리 행위입니다. 그 두 모드에서는 아래 `curl` 예시가 그대로는 동작하지 않으며, 관리자로 로그인한 세션 쿠키를 함께 보내야 합니다 (`curl -b` / 브라우저). 평문 no-auth 모드(§9.4.1)에서는 예시 그대로 동작합니다.

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
| 질문 길이 | 최대 10,000자(`CHAT_MAX_QUESTION_CHARS`) — 그리고 이 모델의 창에 들어가는 길이 | REST(`/api/v1/chat`)는 400 Bad Request, 채팅 화면(SSE)은 오류 말풍선 — 대화를 만들기 전에 거절한다. 창 기준 거절은 "이 모델에서는 약 N자까지"라고 알린다(500자 이하는 창을 따지지 않는다) |
| 파일 업로드 크기 | 최대 200 MB (기본) | 413 Payload Too Large |
| 파일 형식 불일치 (매직바이트) | 확장자와 실제 내용이 다른 경우 | 422 Unprocessable Entity |
| API 요청 빈도 | 경로별 분당 제한 | 429 Too Many Requests |
| `X-Trace-Id` 요청 헤더 | 영숫자와 `_`·`-`, 최대 64자 | 거부가 아니라 **서버가 새로 발급**(요청은 정상 처리) |

> **한 요청의 로그가 여러 스레드에 걸칩니다** — 채팅은 SSE 워커·사전 future·검색 축·Vision 호출이 전부 다른 (가상) 스레드인데, `MdcPropagation` 이 요청 스레드의 `traceId` 를 그 스레드들에 넘기므로 `grep <traceId> logs/rag-agent.log` 하나로 한 턴의 줄이 전부 잡힙니다. SSE 워커의 오류로 예외 핸들러가 다시 불릴 때(ASYNC 재디스패치)도 같은 id 입니다. 인덱싱도 같습니다 — 업로드·재인덱싱·디렉터리 동기화 요청의 id 가 파일별 작업과 그 안의 병렬 단계(키워드 추출·MD 교정·이미지 설명·임베딩 배치)까지 이어집니다. 요청 없이 스케줄러가 깨우는 SSE 하트비트만 id 가 없습니다.
>
> **`X-Trace-Id`를 검증하는 이유**: 이 값은 모든 로그 줄의 `[traceId]` 자리에 그대로 들어갑니다. 줄바꿈이
> 들어가면 로그 한 줄을 두 줄로 위조할 수 있고(가짜 ERROR 줄 주입), 수 KB짜리 값은 매 줄에 반복돼 로그
> 파일을 부풀립니다. 채팅과 지식 제안이 게스트 개방이라 누구나 보낼 수 있는 헤더입니다. UUID는
> 하이픈째로 통과하므로 기존 상관관계 id 연동은 그대로 동작합니다.

**크로스 오리진**: `/api/**`에 CORS 매핑이 없습니다. 인증 없는 두 모드에서 그 접두사는 `permitAll` +
CSRF 예외이므로, 와일드카드 오리진을 열어 두면 방문자가 연 아무 페이지나 그 브라우저를 통해
`POST /api/v1/chat`으로 검색 답변을, `GET /api/v1/documents`·`/api/v1/chunks/{id}`로 문서·청크 본문을
읽고 응답까지 가져갈 수 있습니다(폐쇄망이라도 내부 위키·CI 화면 하나의 XSS면 충분합니다). 이 앱의 API
소비자는 전부 같은 오리진이고 **curl 등 스크립트는 브라우저가 아니라 CORS와 무관**하므로, 자동화에는
영향이 없습니다. 외부 웹 클라이언트를 붙여야 한다면 와일드카드가 아니라 구체적인 오리진 목록으로
`WebConfig`에 다시 추가하세요.

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
| `/actuator/**` | 인증 없이 열림 — 폐쇄망 단일 운영자 전제라 `/admin`이 자동 인증되는 것과 같은 선상입니다. **외부에 노출되는 배포라면 이 모드를 쓰지 말고 §9.4.2를 사용하세요** |
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
| 문서 관리 쓰기(업로드, 업로드취소, 삭제, 태그 수정·편집, **내보내기**) | **로그인 필요** — 비로그인 시 `/login` 리다이렉트, `ROLE_ADMIN` 아닌 로그인은 403. 내보내기는 읽기 동작이지만 문서 전체 내용을 한 번에 반출하는 벌크 기능이라 이 그룹에 포함(관리자 매뉴얼 §3.3) |
| `/admin/**` | **로그인 필요** — 게스트/첫 관리자 자동 주입 없음(평문 no-auth와의 핵심 차이) |
| `/actuator/**` (`/actuator/health` 제외) | **`ROLE_ADMIN` 로그인 필요** — `POST /actuator/loggers/{name}`으로 TRACE를 켜면 `LlmCurlLogger`가 검색된 문서 본문이 실린 프롬프트 전문을 로그 파일에 남깁니다. 게스트에게 열린 배포이므로 로그 레벨 변경은 관리 행위로 묶습니다([런타임 레벨 변경](#런타임-레벨-변경-actuator) 참고 — 이 모드에서는 로그인 세션 없이 curl로 레벨을 바꿀 수 없습니다). `/actuator/health`는 모니터링용으로 계속 열려 있습니다 |
| `/api/v1/documents/**` REST **읽기**(`GET`) | 로그인 없이 조회 가능 + CSRF 예외 |
| `/api/v1/documents/**` REST **쓰기**(`POST` 업로드·`/sync`, `DELETE`) | **`ROLE_ADMIN` 로그인 필요** (403). CSRF 예외는 유지되므로 토큰은 필요 없고 **세션 쿠키만** 있으면 됩니다. 이 모드를 켜는 이유가 "문서 관리는 로그인해야 한다"이므로 REST 쓰기도 같은 게이트를 받습니다. curl 자동화는 아래 방법으로 세션을 얻어 그대로 동작합니다 |
| Web UI 게스트 화면 | 업로드 카드·삭제 버튼·Admin 내비가 숨겨짐(관리자로 로그인해야 노출) |
| 로그아웃 버튼 | 관리자로 로그인했을 때만 노출 |

**로그인 → 관리 흐름**:
1. `/setup`에서 관리자 계정 생성 (최초 1회, 평문 no-auth와 동일)
2. `/login`에서 방금 만든 이메일·비밀번호로 로그인
3. 로그인 세션이 유지되는 동안 `/documents`에서 업로드·삭제, `/admin`에서 청크 관리 가능
4. 다른 탭/시크릿 창은 여전히 게스트 — 관리 기능은 로그인한 브라우저 세션에서만 보임

**curl 자동화 (관리 전용 인증 모드)**: REST 쓰기가 `ROLE_ADMIN`을 요구하므로 세션 쿠키를 한 번 받아
재사용합니다. `/login`은 폼 로그인이라 CSRF 토큰이 필요하고, `/api/v1/**`은 CSRF 예외라 이후 호출에는
쿠키만 있으면 됩니다.

```bash
COOKIES=$(mktemp)

# 1) CSRF 토큰 받기 (쿠키에 XSRF-TOKEN 이 실린다)
curl -s -c "$COOKIES" http://localhost:8080/login > /dev/null
TOKEN=$(awk '$6=="XSRF-TOKEN"{print $7}' "$COOKIES")

# 2) 로그인 — 성공하면 같은 쿠키 파일에 JSESSIONID 가 들어온다
curl -s -b "$COOKIES" -c "$COOKIES" -X POST http://localhost:8080/login \
  -d "username=admin@example.com" -d "password=..." -d "_csrf=$TOKEN" > /dev/null

# 3) 이후 REST 쓰기는 쿠키만으로 (CSRF 토큰 불필요)
curl -s -b "$COOKIES" -X POST "http://localhost:8080/api/v1/documents/sync?version=latest"
```

> 읽기 전용 호출(`GET /api/v1/documents`, `POST /api/v1/chat`, `GET /api/v1/health` 등)은 예전처럼
> 로그인 없이 그대로 동작합니다 — 위 절차는 **쓰기**에만 필요합니다.

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
- 이 플래그를 읽는 소비자는 **셋**입니다: 속도 제한(`RateLimitFilter`), 방문자 식별(`GuestIdentityResolver`), 그리고 **감사 로그의 `ip` 필드**(`AuditLogger`). 세 번째는 뒤늦게 합류했습니다 — 그전까지 감사 로그만 이 플래그를 보지 않고 헤더를 무조건 믿었고, 하필 "누가 무엇을 했는가"를 남기는 것이 목적인 필드가 셋 중 가장 위조하기 쉬웠습니다.

**동작 세부**:
- 방문자 id는 `guest-<12자리 hex>` 형식입니다. IP 원문은 저장되지 않고, 서버가 최초 기동 시 생성해 `app_secret` 테이블에 보관하는 키로 HMAC 해싱됩니다(재기동해도 id가 바뀌지 않도록 영속화).
- 쿠키 이름은 `rag_visitor`(HttpOnly, SameSite=Lax, 1년). HTTPS 접속이면 `Secure`도 붙습니다.
- **문서는 여전히 공유**입니다(`DocRegistry.SHARED`). 개인화되는 것은 채팅 스레드·대화 이력·좋아요(큐레이션 Q&A) 소유권, 그리고 **지식 제안 게시판의 "내 제안" 목록·처리 알림**(관리자 매뉴얼 §6.1)뿐입니다.
- **관리자 매뉴얼 §6.1 지식 제안 게시판을 쓴다면 이 설정이 사실상 필수**입니다 — `shared`에서는 모든 방문자가 한 사람으로 취급되어 서로의 제안과 처리 알림이 섞여 보입니다. `hybrid`에서는 실제 로그인한 관리자가 이 화면을 열어도 게스트 id로 덮어써지지 않으므로(`NoAuthAutoLoginFilter.hasRealLogin()`), 관리자 본인 제안이 게스트 목록에 섞이지 않습니다.
- 오타 등 알 수 없는 값은 `shared`로 폴백합니다(설정 실수가 "반쪽만 분리된" 상태로 이어지지 않도록). 기동 로그의 `[GUEST_ID] 방문자 식별 전략: ...` 줄로 실제 적용값을 확인하세요.
- **기존 대화는 보이지 않게 됩니다.** 이 설정을 켜기 전에 쌓인 스레드는 예전 공용 게스트 id(`00000000-…-0001`)에 묶여 있어 새 방문자 id로는 조회되지 않습니다(삭제되지는 않음). 되돌리려면 `shared`로 다시 바꾸면 그대로 다시 보입니다.

> **전체 인증 모드의 문서 관리도 `ROLE_ADMIN`입니다.** `/signup`이 열려 있는 모드라 "로그인한 사용자
> 누구나"로 두면 가입만 하면 문서를 올리고 지우고 통째로 내보낼 수 있습니다 — 관리 전용 모드와
> **같은 목록**(`SecurityConfig.gateDocumentManagement`)을 씁니다. 그 짝으로 `/setup`은 이 모드에서도
> 열립니다: 관리자 계정을 만드는 유일한 경로이고(`/signup`은 `ROLE_USER`만 만듭니다), 관리자가 하나라도
> 생기면 스스로 닫힙니다. 배포 직후 가장 먼저 `/setup`으로 관리자를 만드세요.

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
- [ ] KO/EN 언어 전환 동작 확인 (`/settings` → 화면 표시 → 언어)

**보안**:
- [ ] 확장자 불일치 파일 업로드 → 422 응답 확인
- [ ] 2,001자 이상 질문 → 400 응답 확인
- [ ] (auth 모드) 미로그인 상태에서 `/` 접속 → `/login` 리다이렉트 확인
- [ ] (평문 no-auth 모드) `/admin` 경로에 대한 네트워크 접근 제한 적용 여부 확인
- [ ] (관리 전용 인증 모드) 게스트로 `/admin` 및 문서 업로드 시도 → `/login` 리다이렉트 확인, 게스트 화면에 업로드 카드 미노출 확인
- [ ] (관리 전용 인증 모드) 관리자 로그인 후 `/admin`·문서 업로드/삭제 정상 동작 + 다른 페이지 이동 후에도 로그인 상태 유지 확인
- [ ] (관리 전용 인증 모드) `curl -X POST ".../api/v1/documents/sync"` 무인증 호출 → **403** 확인(REST 쓰기도 관리 게이트를 받는다)
- [ ] (관리 전용 인증 모드) 로그인 세션 쿠키를 실은 같은 호출 → 200 확인(curl 자동화가 세션으로 계속 동작)
- [ ] (관리 전용 인증 모드) 무인증 `curl ".../api/v1/documents"`(GET) → 200 확인(읽기는 게스트 개방 유지)

**LLM 및 운영**:
- [ ] `/llm-usage` — 프로바이더 카드 정상(초록) 확인
- [ ] `/llm-usage` — 일별 차트 데이터 표시 확인
- [ ] `/llm-usage` — `embed` 카드가 채팅 프로바이더와 분리 표시되고 인덱싱/검색 후 토큰이 누적되는지 확인
- [ ] `/llm-usage` — 키 없는(비활성) 프로바이더 중 사용 이력 없는 항목이 카드·표·차트에서 숨겨지는지 확인
- [ ] `/llm-usage` — orphan 카드(있다면) 삭제 버튼 클릭 → 카드 사라짐 + `AuditLogger`에 `llm-usage.delete-orphan` 기록 확인, 활성 프로바이더는 삭제 버튼이 없는지 확인
- [ ] Circuit Breaker 차단 없음 확인
- [ ] 데이터 디렉터리(`data/`) 마운트 및 쓰기 권한 확인
- [ ] Chroma 볼륨 영속성 확인 (재시작 후 문서 목록 유지)
- [ ] `/admin` 접속 → 컬렉션 목록·청크 테이블 정상 표시 확인
- [ ] DOCX 업로드 후 `data/converted/{docId}_corrected.md` 생성 확인
- [ ] 벡터 스토어 관리 페이지 ↺ 버튼으로 MD 재인덱싱 성공 확인
- [ ] (운영 환경) `/admin` 경로에 대한 네트워크 접근 제한 적용 여부 확인

**지식 제안 게시판 (관리자 매뉴얼 §6.1, 사용하는 경우)**:
- [ ] 기존 DB 업그레이드라면 **적용 전 DB 파일 백업**(`data/memory.db` — 옛 `SQLITE_VEC_DB_PATH` 를 설정한 배포라면 그 파일, §6.2.1) + 첫 기동 로그의 `[CURATED] curated_qa 스키마 마이그레이션 완료` 확인
- [ ] (게스트 배포) `AUTH_GUEST_IDENTITY`가 `shared`가 아닌지 확인 — 기동 로그 `[GUEST_ID] 방문자 식별 전략: ...` 줄로 실제 적용값 확인
- [ ] `/curated/submissions`에서 제안 1건 등록 → 관리자 헤더 배지에 대기 건수 표시(최대 60초) 확인
- [ ] 게스트로 `GET /admin/submissions/pending-count` 호출 → 로그인 리다이렉트(또는 403) 확인
- [ ] `/admin` 지식 제안 검토 카드에서 **임베딩 실행** → 해당 내용으로 검색 시 답변 근거에 반영되는지 확인
- [ ] 거부 처리 → 작성자 화면에 사유 전문 표시 + 헤더 배지 갱신 확인
- [ ] `data/audit/audit.log`에 `curated.submission.approve`/`.reject` 기록 확인

**태그 기반 검색 적용 시 (프리릴리즈 정책)**:
- [ ] 적용 전 백업 여부 결정 및 수행 (선택)
- [ ] `data/memory.db`(+wal/shm) — 옛 `SQLITE_VEC_DB_PATH` 를 설정한 배포라면 그 파일도 —, `data/documents`, `data/converted`, `data/images` 수동 초기화 완료
- [ ] (chroma) `data/chroma` 또는 `chroma_data` 볼륨 초기화 완료
- [ ] 재기동 후 `/setup` 또는 로그인 경로 정상 확인
- [ ] 문서 재업로드/동기화 후 태그 엄격 필터 동작 확인

---

## MCP 서버

외부 LLM 클라이언트(Claude Code, MCP Inspector 등)가 앱의 Tool 여덟을 부를 수 있게 하는 기능이다. **읽기 전용 여섯**(`searchKnowledge` · `findRelatedDocuments` · `getChunkContext` · `queryCatalog` · `generateDiff` · `getDocument`(관리자 키만))과 **작업을 시작하는 둘** — `analyzeImpact`(읽기 키 가능 — 영향도 분석을 시작하고 작업 번호를 돌려준다)와 `requestDocumentChange`(**관리자 키만** — 수정안 대기열을 만들 뿐이다. `from`·`to` 를 주면 그대로, 지시문만 주면 LLM 이 뽑고, `semantic`·`caseVariants` 옵션을 받는다)다. **어떤 Tool 도 코퍼스를 직접 바꾸지 않는다** — 수정안은 관리자가 `/admin/change-jobs` 에서 승인한 뒤에만 적용된다(관리자 매뉴얼 §10.1). MCP 로 만든 작업도 같은 화면에서 검토한다([AGENT_MCP_DESIGN](AGENT_MCP_DESIGN.md) §3).

**켜기.** `MCP_SERVER_ENABLED=true` 와 키를 준다(키는 16자 이상, 읽기와 관리자가 달라야 한다). 재기동하면 `POST /api/v1/mcp`(STATELESS streamable HTTP)가 생긴다. 로그에 `[MCP] 서버가 켜져 있다 …` 가 한 줄 찍힌다. 키를 하나도 안 주면 켜져 있어도 모든 요청이 401 이다(`[MCP] … API 키가 없다` 경고).

**인증.** `Authorization: Bearer <키>` 또는 `X-API-Key: <키>`. 어느 **보안 모드**(전체 인증 · management-only · 평문 no-auth)에서도, 그리고 `/setup` 으로 관리자를 만들기 전에도 같다 — 이 경로는 모드별 체인이 아니라 전용 체인이 받는다. 세션·쿠키·CSRF 토큰은 쓰지 않는다. 키가 없거나 틀리면 401(`WWW-Authenticate: Bearer`), IP 별 분당 `RATE_LIMIT_MCP_PER_MINUTE` 회를 넘으면 429 다.

**권한.** 읽기 키로 `getDocument` 를 부르거나 `queryCatalog` 의 `reports.open`/`usage.summary` 를 부르면 `isError: true` 와 "권한이 없습니다" 가 돌아온다. **`tools/list` 는 호출자별로 거르지 못한다** — 읽기 키에게도 `getDocument` 가 목록에 보이고 호출할 때 거부된다.

**확인.** 앱을 띄운 뒤(키는 본인 값으로):

```bash
curl -s -X POST http://localhost:8080/api/v1/mcp \
  -H "Authorization: Bearer $MCP_READ_API_KEY" \
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

**주의.** 키는 평문 비밀이다 — `.env` 를 저장소에 올리지 말 것. 키를 바꾸려면 재기동한다(`/settings` 로는 바꿀 수 없다). 외부에 공개할 때는 HTTPS 로 종단하고(리버스 프록시) `RATE_LIMIT_MCP_PER_MINUTE` 를 환경에 맞춘다. 이 서버는 읽기 전용이지만 `searchKnowledge`·`getChunkContext` 는 **문서 본문**을 돌려주므로, 읽기 키를 가진 사람은 게스트가 채팅으로 볼 수 있는 범위와 같은 내용을 볼 수 있다고 생각하면 된다.
