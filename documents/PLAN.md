# RAG-Agent 온라인 확장 개발 계획

> Java 개발자 관점 · Spring Boot 3.5 + Spring AI 1.1 + Java 21 · 작성일 2026-05-11  
> **개발 기준 문서**: 이 파일(documents/PLAN.md)이 마스터. `documents/refactoring/18-extension-roadmap.md`는 각 항목의 기술 레퍼런스.

---

## ⚡ 현재 진행 상황 (2026-07-01 기준)

### ✅ Phase 1 전체 완료

| 완료 항목 | 비고 |
|---|---|
| Step 1.1 — Caddy 리버스 프록시 + TLS | |
| Step 1.2 — Flyway 마이그레이션 도입 | |
| Step 1.3 — Spring Security 폼 로그인/회원가입 | |
| Step 1.4 — 멀티유저 데이터 격리 (SQLite `user_id` + Chroma 컬렉션) | |
| Step 1.5 — CSRF + HTMX fetch 통합 | |
| Step 1.6 — 로그인/회원가입 화면 | |
| `app.auth.enabled` 토글 — no-auth 모드 (guest/admin 자동 로그인 + 첫 실행 `/setup`) | |

### ✅ Phase 3 추가 완료

| 완료 항목 | 비고 |
|---|---|
| ChromaDB v2 API 대응 — 컬렉션명 → UUID 자동 변환 | 구버전 Chroma와의 호환 단절 수정 |
| 문서 저장 경로 공유 구조로 단순화 (`DocRegistry.SHARED`) | 멀티유저 격리 대신 공유 저장소 구조로 확정 |
| 인덱싱 SSE 진행 단계별 표시 (파일 타입별 step) | 페이지 복귀 시 진행 중 상태 복원 포함 |
| 키워드 추출 타임아웃 시 CircuitBreaker 오동작 수정 | 타임아웃을 에러로 오인해 프로바이더 차단하던 버그 수정 |
| DOCX 변환 전 구버전 아티팩트 삭제 순서 수정 | 변환 실패 시 구버전 파일이 남아있는 버그 수정 |
| `LOGGING_LEVEL`, `LLM_TEMPERATURE`, `LLM_MAX_TOKENS`, `SPRING_SECURITY_LOGGING_LEVEL` 환경변수 외부화 | `.env.example` + `OPERATOR_MANUAL.md` 반영 |
| 의존성 버전 최신 stable로 일괄 업데이트 | Spring Boot 3.5, Spring AI 1.1.4 등 |

### ✅ 보안 결함 수정 완료

| 항목 | 수정 내용 |
|---|---|
| B-27 — TOCTOU 회원가입 | `DataIntegrityViolationException` catch로 중복 이메일 경쟁 조건 처리 |
| B-28 — 세션 고정 | 회원가입 성공 시 `old.invalidate()` → `getSession(true)` 패턴으로 세션 재발급 |
| B-29 — BCrypt 72바이트 절삭 | `PASSWORD_PATTERN` 상한 `.{10,72}` 추가로 73자+ 비밀번호 등록 차단 |
| B-30 — 헬스체크 DB 조회 | `NoAuthAutoLoginFilter.isPassThrough()`에 `/api/v1/health`, `/actuator/**` 추가 |
| B-31 — ChromaDB 볼륨 경로 | `docker-compose.yml` 볼륨 마운트 `chroma_data:/data` 로 수정 (persist_path 일치) |
| 회귀 테스트 | `AuthControllerTest` 12개, `NoAuthAutoLoginFilterTest` 5개, `ChatResponseNullSafetyTest` +4개 추가 |

### 아직 미착수 (다음 목표)

- ~~**Phase 2**: 모바일 UI (Offcanvas, sticky 입력창, PWA)~~ → ✅ 완료 (2026-06-27, 오프캔버스 드로어·dvh sticky 입력·PWA(manifest/SW/오프라인)·iOS 16px·접근성)
- ~~**Phase 3 — Chat 피드백(좋아요/싫어요) 기반 컨텍스트 제외**~~ → ✅ 완료 (2026-07-02, §6.9 — `conversation_turns.feedback` 컬럼, `PATCH /ui/threads/{threadId}/turns/{turnId}/feedback`, DISLIKE는 `getHistory()`에서 하드 제외)
- ~~**Phase 3 — 입력 시작 시 로컬 요약 선계산 + 중복 제거 컨텍스트 압축**~~ → ✅ 완료 (2026-07-03, §6.10 — `ConversationSummarizerService`, `POST /ui/chat/summary/precompute`, LOCAL 전용 요약 + 캐시, 실패 시 `getHistory()` 자동 폴백)
- ~~**Phase 3 — LLM 사용량 임베딩 사용량 분리**~~ → ✅ 완료 (2026-07-04, §6.6 — `TrackingEmbeddingModel` 데코레이터가 `embed:<model>` 프로바이더로 별도 기록, `/llm-usage` 표·카드·차트 시각 분리, usage 미반환 시 chars/4 근사 폴백)
- **Phase 3 잔여** (미착수, §6.5·6.7·6.8·6.11 상세): 사용자별 LLM 토큰 쿼터(§6.5) · 사용자별 스토리지 쿼터(§6.11) · 비활성 프로바이더 조건부 표시(§6.7) · orphan 프로바이더 기록 삭제(§6.8)
- **Phase 4** (조건부, 미착수): OAuth2 소셜 로그인(§7.1) · PostgreSQL 마이그레이션(§7.2) · 관리자 페이지 확장(§7.3, ※ `/admin` 기본 골격은 Phase 5.8에서 이미 존재)
- ~~**Phase 5**: sqlite-vec 선택적 연동~~ → ✅ 완료 (Step 5.1~5.8, `app.vectorstore.type=chroma|sqlite-vec`)
- ~~**Phase 5 추가**: Step 5.9 태그 기반 검색 스코프 + Step 5.10 sqlite-vec 운영/벡터 DB 분리~~ → ✅ 완료 (2026-07-01, Step 5.9 태그 필터/제안/복원 + Step 5.10 `SQLITE_VEC_DB_PATH` 분리 스위치). vec0 라이브 부팅은 운영 인수
- ~~**Phase 6**: 폐쇄망/노-도커 — 키리스 LOCAL(G1)·차원 외부화(G2)·라우팅 외부화(G3)·런북(G4)·무-외부호출 인수(G5)~~ → ✅ G1~G5 완료 (2026-06-25). sqlite-vec 라이브 부팅(vec0 바이너리)만 운영 인수

> 스키마 관리 실태: **Flyway(V1·V2 baseline) + 런타임 멱등 DDL 혼용**. `SqliteMemoryRepository`/`SqliteUserDetailsService`가 `CREATE TABLE IF NOT EXISTS` + `ALTER TABLE ADD COLUMN`으로 컬럼을 증분 추가한다(§12). 신규 컬럼은 새 Flyway 파일이 아니라 이 런타임 `ALTER TABLE` 패턴으로 추가한다.

---

## 목차

1. [요약 (Executive Summary)](#1-요약-executive-summary)
2. [현재 구조 분석](#2-현재-구조-분석)
3. [핵심 기술 의사결정](#3-핵심-기술-의사결정)
4. [Phase 1 — 보안 기반 구축](#4-phase-1--보안-기반-구축-2주)
5. [Phase 2 — 모바일 UI](#5-phase-2--모바일-ui-개선-12주)
6. [Phase 3 — 운영 견고화](#6-phase-3--운영-견고화-1주)
7. [Phase 4 — 확장 (조건부)](#7-phase-4--확장-조건부)
8. [Phase 5 — Vector Store 선택적 연동](#8-phase-5--vector-store-선택적-연동)
9. [Phase 6 — 폐쇄망 / 노-도커 실행 지원](#9-phase-6--폐쇄망air-gapped--노-도커-실행-지원)
10. [리스크 및 이슈](#10-리스크-및-이슈)
11. [의존성 변경 사항](#11-의존성-변경-사항-pomxml)
12. [DB 스키마 변경](#12-db-스키마-변경-요약)
13. [최종 체크리스트](#13-최종-체크리스트)
14. [부록 — 결정 사항 한눈에 보기](#부록--결정-사항-한눈에-보기)

---

## 1. 요약 (Executive Summary)

**목표**: 로컬 단일 사용자 RAG 에이전트를 인증·HTTPS·멀티유저 격리·모바일 대응이 갖춰진 온라인 서비스로 확장한다.

**전제**: SQLite를 가능한 한 유지하고, 명확한 한계 신호가 발생할 때만 PostgreSQL로 전환한다.

| Phase | 핵심 산출물 | 우선순위 | 상태 |
|-------|-----------|---------|------|
| Phase 1 — 보안 기반 | Caddy/HTTPS, Spring Security, 멀티유저 격리, Flyway | **필수** | ✅ 완료 |
| Phase 2 — 모바일 UI | Offcanvas, 하단 고정 입력, PWA | **필수** | ✅ 완료 |
| Phase 3 — 운영 견고화 | Rate limit, 업로드 검증, 감사 로그 | 중요 | 🟡 일부 완료 |
| Phase 4 — 확장 | OAuth2, PostgreSQL 마이그레이션 | 조건부 | 🔵 미착수 |
| Phase 5 — Vector Store 선택 | sqlite-vec / ChromaDB 런타임 선택 | 중요 | ✅ 완료 (Step 5.1~5.10) |
| Phase 6 — 폐쇄망 / 노-도커 | sqlite-vec 단독·로컬 LLM·CDN 0 (키리스 LOCAL, 차원 외부화) | 중요 | 🟢 G1~G5 완료 |

---

## 2. 현재 구조 분석

### 2.1 강점 (유지할 자산)

- **JdbcTemplate 직접 사용** — Hibernate 없이 SQL 직조작. SQLite→Postgres 전환 시 ANSI SQL만 유지하면 매끄럽다.
- **Immutable Record 기반 상태** — `AgentState`, `ChatResponse` 등 동시성 안전.
- **Java 21 Virtual Threads** — 인증·세션 추가 후에도 I/O 블로킹 비용이 거의 없다.
- **HTMX + 서버 렌더링** — JS 프레임워크 없이 모바일/PWA 대응이 단순.
- **VectorStoreRegistry** — 컬렉션 키 기반 추상화. 멀티테넌시 전환에 자연스럽게 맞물린다.

### 2.2 약점 (Phase 1 착수 시점 기준 — Phase 1~3에서 거의 모두 해소됨)

| 항목 | 당시 문제 | 해소 현황 |
|------|------|-------------------|
| 인증 | 없음 | Spring Security 폼 로그인(Step 1.3), no-auth 모드 병행 |
| `threadId` 소유 개념 | UUID, 누구나 접근 | Repository 시그니처에 userId 강제(Step 1.4) |
| 파일 저장 경로 | 격리 없음 | 이후 공유 저장소로 의도적 단순화(`DocRegistry.SHARED`) |
| Chroma 컬렉션 | `manual_{version}` | 사용자별 네이밍(Step 1.4) → Phase 3에서 공유로 재단순화 |
| HTTPS | HTTP 8080 직접 노출 | Caddy 리버스 프록시(Step 1.1) |
| 마이그레이션 도구 | 없음 | Flyway 도입(Step 1.2) |
| 모바일 UI | 미구현 | Offcanvas/PWA(Phase 2) |
| Rate limit | 없음 | Bucket4j(§6.1) |

---

## 3. 핵심 기술 의사결정

### 3.1 인증 방식 — 세션 vs JWT

**세션 선택 이유**
- Spring Security 기본 동작과 정합
- 로그아웃·세션 만료 서버 제어 용이
- CSRF 토큰 통합이 자연스러움
- 구현 단순, 보안 사고 적음

**JWT 보류 이유**
- 다중 서버 / 모바일 네이티브 앱 생길 때 필요
- 토큰 폐기·갱신 로직 직접 구현 필요
- SPA가 아닌 HTMX 환경에서 이득 적음

> **결론**: `HttpSession + Spring Security 폼 로그인`으로 시작. Redis 세션 저장소는 다중 인스턴스가 필요해질 때 도입.

### 3.2 SSL/TLS 종료 위치

| 방식 | 장점 | 단점 | 채택 |
|------|------|------|------|
| Caddy 리버스 프록시 | Let's Encrypt 자동 갱신, HTTP/2, 설정 10줄 | 컨테이너 1개 추가 | **채택** |
| Spring Boot 직접 TLS | 외부 의존 없음 | 인증서 갱신 수동, keystore 관리 부담 | — |
| Cloudflare 종료 | DDoS 보호 추가 | 외부 종속, 트래픽 메타 노출 | 선택사항 |

Spring 측 부수 작업: `server.forward-headers-strategy=framework`, Cookie `Secure` + `SameSite=Lax` 강제.

### 3.3 SQLite 지속 전략

SQLite의 실질 한계는 **쓰기 직렬화**이며, 본 앱은 읽기 우세 워크로드라 잘 맞는다.

**마이그레이션 트리거 (이때 Postgres로 전환)**
- 동시 쓰기 락 대기 (`SQLITE_BUSY`) 빈도 1%/분 초과
- 다중 인스턴스 배포 필요 (스케일 아웃)
- 읽기 풀과 쓰기 풀 분리로도 해결 불가한 응답 지연
- 실시간 백업·복제가 비즈니스 요구 사항이 됨

> **대비 전략**: 모든 SQL을 ANSI 표준으로 작성, **Flyway 마이그레이션을 지금부터 도입**해 두면 SQLite/Postgres 양쪽에서 동일 스크립트가 동작한다. JdbcTemplate 그대로 사용.

### 3.4 멀티테넌시 모델

| 모델 | 특징 | 채택 |
|------|------|------|
| A. Row-level (`user_id` 컬럼) | SQLite 한 파일로 운영. Repository에서 항상 `WHERE user_id = ?` | **채택** |
| B. 사용자별 Chroma 컬렉션 | `{userId}_{version}` 네이밍. `VectorStoreRegistry` 시그니처 확장 | **채택** |
| C. Schema / DB per tenant | 운영 복잡도 폭증, B2B 엔터프라이즈 한정 | — |

**안전장치**: Repository 메서드 시그니처를 `getHistory(userId, threadId)`로 강제 변경해 **userId 누락 시 컴파일 에러**가 나도록 한다.

---

## 4. Phase 1 — 보안 기반 구축 ✅ 완료

> 상세 구현은 코드 / `db/migration` 참조. 아래는 단계 요약.

### Step 1.1 — Caddy 리버스 프록시 도입 ✅ 완료

Caddy(자동 TLS·HTTP/2)로 `app:8080` 프록시 + 보안 헤더(HSTS 등). Spring 측 `forward-headers-strategy=framework`, 세션 쿠키 `Secure`/`HttpOnly`/`SameSite=Lax`.

### Step 1.2 — Flyway 마이그레이션 도입 ✅ 완료

기존 스키마를 `V1__baseline.sql`로 캡처, `flyway-database-sqlite` 모듈 사용. SQLite 트랜잭션 DDL 제약으로 **마이그레이션 1개당 1 DDL** 원칙.

### Step 1.3 — Spring Security 도입 ✅ 완료

폼 로그인 + `BCryptPasswordEncoder(12)`(~200ms/해시) + CSP/세션 관리. `users`·`persistent_logins` 테이블(`V2`), JdbcTemplate 기반 `SqliteUserDetailsService`. `failed_count` UPDATE는 SQLite write 락 회피로 비동기 처리.

### Step 1.4 — 멀티유저 데이터 격리 ✅ 완료

주요 테이블에 `user_id` 컬럼 + 복합 인덱스(`V3`). Repository 시그니처를 `(userId, …)`로 강제(누락 시 컴파일 에러), 파일 경로·Chroma 컬렉션 사용자별 네이밍. ※ 이후 Phase 3에서 공유 저장소(`DocRegistry.SHARED`) 구조로 단순화됨.

### Step 1.5 — CSRF + HTMX 통합 ✅ 완료

`base.html`에 CSRF 메타 + `htmx:configRequest` 글로벌 헤더 주입, `chat-stream.js` fetch에도 동일 헤더 적용.

### Step 1.6 — 회원가입/로그인 화면 ✅ 완료

`/login`·`/signup` Thymeleaf 페이지, 비밀번호 정책(10자+영문/숫자/특수 각 1), 가입 직후 자동 로그인, 로그인 5회 실패 시 15분 잠금.

---

## 5. Phase 2 — 모바일 UI 개선 ✅ 완료

> 상세 UI 구조는 [UI.md](UI.md) §9(모바일/PWA/접근성) 참조. 아래는 단계 요약.

### 5.1 반응형 레이아웃

`chat.html` 사이드바를 `offcanvas-md`(≥md 고정 컬럼 / <md 햄버거 드로어 `#threadDrawer`)로 전환. 외곽 `.chat-shell`(`100dvh`) + `#chat-messages` flex(`min-height:0`)로 입력창 하단 고정. `documents.html` 테이블 `.table-responsive`(가로 스크롤 제거), `llm-usage.html` 차트 고정 높이 컨테이너 + `maintainAspectRatio:false`. 모바일 폼 컨트롤 `font-size:16px`(iOS 자동 확대 방지).

> ⚠️ Bootstrap `.offcanvas-md`는 ≥md에서 `background-color:transparent!important`를 강제하므로 데스크톱 사이드바 배경을 `var(--bg-elevated)`로 복구해야 한다(`app.css`).

### 5.2 PWA

`manifest.webmanifest`·`sw.js`(NETWORK-FIRST, GET 내비게이션만 가로채 오프라인 fallback — RAG/HTMX/SSE/인증 응답 비캐시)·`offline.html`·`icons/icon.svg`. `base.html`에 manifest/theme-color/apple meta + SW 등록 + iOS 설치 힌트(1회). `WebConfig` MIME 매핑, `SecurityConfig` permitAll에 PWA 경로 추가.

### 5.3 다크모드 & 접근성

다크모드 `prefers-color-scheme` 자동 감지(기존 유지). 아이콘 버튼 `aria-label`(i18n), 모바일 44px 터치 영역, `:focus-visible` 인디케이터.

### 5.4 검증

전체 286 tests BUILD SUCCESS, no-auth 부팅으로 `/`·`/documents`·`/llm-usage` 렌더 + PWA 자산 응답 확인.

---

## 6. Phase 3 — 운영 견고화 🟡 일부 완료

### 6.1 Rate Limiting — Bucket4j ✅ 완료 (리팩토링)

```xml
<dependency>
  <groupId>com.bucket4j</groupId>
  <artifactId>bucket4j_jdk17-core</artifactId>
  <version>8.10.1</version>
</dependency>
```

| 엔드포인트 | 제한 | 키 |
|-----------|------|-----|
| `POST /api/chat` | 분당 20회 | userId |
| `POST /api/documents` | 분당 5회 | userId |
| `POST /login` | 분당 10회 | IP |
| 전체 익명 | 분당 30회 | IP |

`OncePerRequestFilter` 구현 → `SecurityFilterChain` 앞단에 등록. 메모리 기반 버킷으로 시작, 다중 인스턴스 시 Redis로 이전.

### 6.2 파일 업로드 보안 강화 🟡 부분 완료 (리팩토링 03, 12)

**✅ 완료**
- 확장자 화이트리스트: `pdf, pptx, docx, txt, md`
- **매직바이트 검증** — `security/FileTypeDetector.matches(path, ext)`(Tika 아님, pom에 Tika 의존성 없음). 임시파일 기록 후 검증, 불일치 시 422
- 파일명 sanitize — `Path.normalize()` + 화이트리스트 정규식
- 경로 이탈 방지 — 공유 저장소 `data/documents/`(per-user 격리 폐기, `DocRegistry.SHARED`) 기준 `startsWith()` 검증

**🔵 미착수** — 사용자별 누적 용량 쿼터 → §6.11로 이관·구체화(현재 `storage_used_bytes` 컬럼·쿼터 로직·`app.upload-quota` 프로퍼티 모두 없음).

### 6.3 글로벌 예외 처리 ✅ 완료 (리팩토링 11)

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
  @ExceptionHandler(LlmProviderExhaustedException.class)
  public ResponseEntity<ErrorResponse> llmExhausted(...) { ... }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ErrorResponse> denied(...) { ... }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<ErrorResponse> tooBig(...) { ... }
}
```

### 6.4 감사 로그 ✅ 완료 (리팩토링 14 — Logback 파일 롤링)

SQLite `audit_log` 테이블 대신 Logback `SizeAndTimeBasedRollingPolicy`로 구현.
- `data/audit/audit.log` — NDJSON 포맷 (jq 분석 가능)
- 일별 로테이션 + 10MB 분할, gzip 압축, 7일 자동 삭제, 100MB 전체 상한
- `application.properties`로 모든 파라미터 조정 가능, `app.audit.enabled=false`로 즉시 비활성
- 이벤트 8개 기록: upload×2, delete×2, sync×2, routing-mode, thread-delete

### 6.5 사용자별 LLM 사용량 쿼터 🔵 미착수

**현재 코드 확인 (2026-07-02)**:
- ⚠️ 문서는 "`LlmUsageRepository`에 `user_id` 컬럼이 이미 추가됨"이라 했으나 **실제 `llm_usage`는 프로바이더 단위 집계**다. `LlmUsageRepository.record(String provider, long in, long out)` → `usage_date + provider_name` UPSERT이며 `user_id` 컬럼/차원이 **없다**. 사용자별 쿼터를 하려면 `user_id` 축을 새로 도입해야 한다(현재 `getByPeriod/getDaily/...`는 모두 provider 인자만 받음).
- 집계 조회는 provider별만 존재 → 사용자 단위 "오늘 전체 토큰 합" 쿼리가 없음.
- `AnswerService.execute(AgentState)`(진입점)와 `AgentService.chat()`에 쿼터 게이트가 없음. `ThreadContext.userId()`로 사용자 식별은 가능.

**설계 (권장: 사용량 테이블 분리, 스키마 변경 최소)**:
1. **집계 소스 결정** — 두 안 중 택1.
   - (A) `conversation_turns`에 이미 `input_tokens`/`output_tokens`/`user_id`가 있으므로 **채팅 토큰은 여기서 사용자별 일일 합계**를 구할 수 있음(`SELECT SUM(input_tokens+output_tokens) FROM conversation_turns WHERE user_id=? AND asked_at >= date('now')`). 별도 테이블 없이 채팅 쿼터 구현 가능 → **1차 권장**.
   - (B) `llm_usage`에 `user_id` 축 추가(PK 확장 = SQLite 테이블 재생성) — provider×user 교차 집계가 필요할 때만. 현 요구엔 과함.
2. `QuotaService.checkDailyTokenQuota(userId)` 신설 — (A) 쿼리로 일일 합계 조회 → `app.quota.daily-token-limit`(신규 프로퍼티, 0=무제한) 초과 시 `QuotaExceededException`(신규).
3. **게이트 위치** — `AgentService.chat()` 진입 직후(`PromptInjectionGuard.validate()` 다음). 스트리밍은 `StreamingAgentService.run()` 진입에도 동일 적용.
4. `GlobalExceptionHandler`에 `QuotaExceededException` → 429(RFC 9457 ProblemDetail, `RAG-QUOTA-001`) 매핑. 채팅 UI(HTMX)엔 한도 초과 알림 프래그먼트.
5. 프로퍼티: `app.quota.enabled`(기본 false), `app.quota.daily-token-limit`. `AppProperties`에 `QuotaConfig` record + `quotaSafe()` null 가드.

**완료 기준**:
- 사용자의 당일 누적 토큰이 한도 초과 시 채팅이 429로 차단되고 `Retry-After`(자정까지) 안내가 표시된다.
- `app.quota.enabled=false`(기본)이면 기존 동작 회귀 0.
- 쿼터 집계가 스트리밍/블로킹 경로 모두에서 일관 적용된다.

### 6.6 LLM 사용량 — 임베딩 사용량 분리 ✅ 완료

`TrackingEmbeddingModel implements EmbeddingModel`(`llm` 패키지) 데코레이터를 신설해 `EmbeddingBeanConfig`가 실제 `OpenAiEmbeddingModel`을 이 데코레이터로 감싼 뒤 동일하게 `@Primary`로 노출한다(주입 지점 무변경). `call(EmbeddingRequest)` 한 곳만 오버라이드해 `usageRepo.record("embed:" + model, inputTokens, 0)`으로 기록하고, `embed(String)`/`embed(List)`/`embedForResponse(List)`는 `EmbeddingModel` 인터페이스의 default 메서드가 전부 내부적으로 `this.call(...)`을 호출하므로 오버라이드 없이 자동으로 추적된다. `embed(Document)`는 인터페이스의 유일한 추상 메서드라 직접 구현이 강제되는데, `delegate.embed(document)`로 바로 위임하면 추적을 우회하므로 `this.embed(getEmbeddingContent(document))`(default 메서드 경유)로 구현해 동일하게 `call()`을 타도록 했다. `dimensions()`는 반대로 `delegate.dimensions()`에 직접 위임 — 실제 임베딩 호출이 아니므로 추적 대상이 아니고, delegate(OpenAiEmbeddingModel)가 캐싱하는 값을 그대로 보존한다.

`OperationsController`의 세 경로(`buildProviderReports()`, `/api/v1/llm/usage`, `/api/v1/llm/usage/history`)에 `embeddingProviderName()`(`"embed:" + props.embeddingSafe().model()`) 기반 항목을 하나씩 추가해 채팅 프로바이더 목록과 함께 반환한다. 임베딩 카드/행은 `type="EMBEDDING"`, `role=null`(역할 배지 없음), `blockedUntil=null`(circuit breaker 없음, 항상 "정상")로 표시되어 채팅 프로바이더와 시각적으로 분리된다. 차트는 동일 `<canvas>`를 유지하되 `embed:` 접두사 데이터셋만 별도 Chart.js `stack` 그룹으로 분리해 채팅 합계 스택에 섞이지 않고 날짜별로 별도 막대로 렌더된다.

**usage 미반환 폴백**: `EmbeddingResponseMetadata.getUsage()`가 `EmptyUsage`(promptTokens=0, null 아님)를 반환하는 로컬 서버 대응 — `promptTokens == null || <= 0`이면 `app.embedding.usage-fallback-enabled`(`EMBED_USAGE_FALLBACK_ENABLED`, 기본 `true`) 설정에 따라 입력 텍스트 길이 근사(chars/4, 배치 요청은 모든 텍스트 합산) 또는 0을 기록하고, 근사 경로는 최초 1회만 경고 로그를 남긴다(`AtomicBoolean`).

> **구현 메모 (2026-07-04)**:
> - **부수 수정**: `llm-usage.html`의 차트/표 JS가 `/api/llm/usage(...)`(버전 접두사 `/v1/` 누락)를 호출하던 기존 버그를 발견 — 실제 매핑은 `/api/v1/llm/usage(...)`라 이 화면의 일별 차트와 기간별 표가 항상 404로 완전히 비어 있었다(카드는 서버 렌더라 영향 없었음). 본 작업이 바로 이 두 화면을 다루므로 함께 수정.
> - **실사용 검증**: LM Studio(`text-embedding-nomic-embed-text-v1.5`, sqlite-vec 모드로 ChromaDB 의존 없이 확인) 대상 실제 채팅 질의 → 서버 로그에서 `TrackingEmbeddingModel`의 1회성 경고(`did not report token usage; approximating...`) 확인 → `GET /api/v1/llm/usage` 응답에서 `embed:text-embedding-nomic-embed-text-v1.5` 행에 `inputTokens=24, callCount=1`(멀티쿼리 확장으로 배치된 여러 질의 문자열 합산 기준) 누적을 직접 확인. `/llm-usage` 화면에서 카드·차트·표 3곳 모두 임베딩 항목이 채팅 프로바이더와 분리 표시됨을 스크린샷으로 확인.
> - **테스트**: `TrackingEmbeddingModelTest` 5건(실사용량 기록/근사 폴백/폴백 비활성 시 0/embed(Document) 경유 확인/dimensions() 직접 위임 확인) + `OperationsControllerUsageTest` 3건(표·차트 이력·카드 프래그먼트에 임베딩 행 존재 확인) 신규. 기존 `EmbeddingConfig` 6-인자 생성자를 직접 호출하던 `SqliteVecSchemaInitializerTest`/`AdminServiceTest` 2곳을 7-인자로 수정(신규 `usageFallbackEnabled` 필드). 전체 350 tests BUILD SUCCESS(회귀 0, sqlite-vec 통합 2개는 vec0 바이너리 없을 때만 skip).

**완료 기준 달성**:
- 인덱싱/검색 시 임베딩 토큰이 `llm_usage`에 `embed:<model>`로 누적된다(채팅 행과 분리) — 실사용 검증 완료.
- `/llm-usage` 화면에서 임베딩 사용량이 채팅 프로바이더와 시각적으로 분리되어 표시된다(표 행/카드 + 차트 stack 구분).
- usage 미반환 임베딩 서버에서도 기록이 깨지지 않는다(근사 또는 0 + 경고 로그 1회).
- 기존 채팅 사용량 표/차트 회귀 0.

### 6.7 LLM 사용량 — 비활성 프로바이더 조건부 표시 🔵 계획

> **현재 코드 확인 (2026-07-02)**: `OperationsController.buildProviderReports()` 존재, `LlmUsageRepository`에 `usedProviders()` 조회 없음(추가 필요). 아래 설계 유효.

**문제**: `OperationsController`의 세 경로(`/api/v1/llm/usage` 표, `/usage/history` 차트, `buildProviderReports()` 카드)는 모두 `props.llmSafe().providers()` **전체**를 순회한다. 그래서 API 키가 없어 **비활성(`LlmProviderReport.configured == false`)인 프로바이더도 항상** 사용량 0으로 표시되어, 카드·표·차트가 쓰지 않는 프로바이더로 지저분해진다.

**요구**: 비활성(=키 없음) 프로바이더는 **실제 사용 이력이 있을 때만** 노출한다. 활성 프로바이더는 사용량이 0이어도 항상 표시. (과거 키가 있어 사용하다 지금 비활성화된 경우엔 이력 보존을 위해 계속 표시.)

**선행 확인 (현재 코드 기준)**:
- "비활성" 판별은 이미 존재 — `LlmProviderReport.configured` = `apiKey` blank 여부. ※ Phase 6 G1로 LOCAL role은 키 없이도 런타임 등록되지만 UI는 config의 `apiKey` 기준이라 비활성으로 분류됨 → "사용량 있으면 표시" 규칙으로 자연히 노출되므로 별도 예외 불필요.
- "사용량 있음" 데이터: `llm_usage`에 해당 `provider_name` 행 존재 + (`call_count > 0` 또는 토큰 합 > 0).
- 필터는 **세 경로(카드/표/차트)에 일관 적용**해야 숨긴 프로바이더가 어디서도 안 보임.

**설계**:
1. `LlmUsageRepository`에 사용 이력 프로바이더 집합 조회 추가: `SELECT DISTINCT provider_name FROM llm_usage WHERE call_count > 0` → `Set<String> usedProviders()`. 단일 쿼리, 결과 작음(인덱스 불필요).
2. `OperationsController`에서 report 목록을 `configured == true || usedProviders.contains(name)`로 필터. 세 경로가 쓰는 **공통 헬퍼**로 적용해 표시 목록 일치 보장.
3. (선택) "사용량 있음" 기준 기간: 기본은 **누적(all-time) 존재**로 단순화(표시 기간과 무관하게 한 번이라도 쓴 비활성 프로바이더는 노출 — 이력 추적 목적). 더 엄격히 하려면 표시 기간 내 사용량으로 한정하는 옵션 플래그.

**완료 기준**:
- 키 없는 비활성 프로바이더 중 **사용 이력이 0인 것은 카드·표·차트 어디에도 표시되지 않는다.**
- 사용 이력이 있는 비활성 프로바이더는 계속 표시된다(과거 데이터 보존).
- 활성 프로바이더는 사용량 0이어도 항상 표시(회귀 없음).
- 세 경로(카드/표/차트)의 표시 목록이 일치한다.

### 6.8 LLM 사용량 — 설정에 없는(orphan) 프로바이더 기록 삭제 🔵 계획

> **현재 코드 확인 (2026-07-02)**: `LlmUsageRepository`에 `deleteByProvider()` 없음, `OperationsController`에 사용량 관련 `@DeleteMapping` 없음(현재 GET만: `/llm-usage`, `/ui/llm-usage/cards`, `/api/v1/llm/usage`, `/api/v1/llm/usage/history`). 신규 삭제 경로 필요. 권한은 DB `role`이 `ADMIN`(문자열, `ROLE_` 접두사 아님) 기준.

**배경**: §6.7로 "설정(`props.llmSafe().providers()`)에는 없지만 과거 사용 이력이 있어" 계속 노출되는 **orphan 카드**(예: 키를 빼거나 config에서 제거한 옛 모델, §6.6의 `embed:*`)가 생긴다. 운영자가 이런 카드의 누적 기록을 화면에서 직접 정리(DB 삭제)할 수 있어야 한다.

**선행 확인 (현재 코드 기준)**:
- `llm_usage`는 `provider_name` 키 → 특정 프로바이더 전체 행 삭제는 `DELETE FROM llm_usage WHERE provider_name = ?` 한 줄.
- "orphan" 판별 = `llm_usage`에 있으나 config 프로바이더 목록에 **없는** 이름 (§6.7 `usedProviders()` ∖ `props.llmSafe().providers()` 차집합).
- `/llm-usage`·`/api/v1/llm/*`는 현재 **GET만** 존재 → 삭제용 신규 엔드포인트 필요. 파괴적 작업이므로 권한·CSRF·서버측 가드 필수.

**설계**:
1. `LlmUsageRepository.deleteByProvider(String provider)` 추가 — `DELETE FROM llm_usage WHERE provider_name = ?`, 삭제 행수 반환.
2. 엔드포인트 `DELETE /ui/llm-usage/{provider}`(HTMX 카드 갱신) 또는 `/api/v1/llm/usage/{provider}`:
   - **안전 가드(필수)**: 대상이 config 프로바이더에 **존재하면 거부**(400/409) — 활성 프로바이더 이력은 화면에서 못 지움. orphan만 허용.
   - **권한**: 파괴적 작업이므로 `ROLE_ADMIN` 한정(no-auth 모드에선 admin 자동 인증 경로 적용). CSRF 토큰 필요(기존 htmx 자동 주입 재사용).
   - `AuditLogger`에 삭제 이벤트 기록(프로바이더명, 삭제 행수).
3. UI: **orphan 카드에만** 삭제 버튼(🗑) 노출 → `hx-delete` + `hx-confirm` → 성공 시 카드 fragment 새로고침. §6.7과 맞물려 삭제 후 "사용량 0 + 미설정" → 카드가 자동으로 사라진다.
4. (결정 필요) `embed:*`(§6.6) 임베딩 의사 프로바이더도 orphan으로 분류됨 → 동일하게 삭제 허용할지/제외할지 결정. 기본은 "허용하되 카드 라벨로 구분".

**완료 기준**:
- orphan 카드에서 삭제 시 해당 `provider_name`의 `llm_usage` 행이 모두 제거되고 카드가 사라진다.
- config에 존재하는 활성 프로바이더는 삭제 버튼이 없고, 강제 호출해도 서버가 거부한다.
- 삭제가 `AuditLogger`에 기록된다.
- 권한 없는 요청 / CSRF 누락은 거부된다(기존 동작 회귀 0).

### 6.9 Chat 응답 피드백(좋아요/싫어요) 기반 컨텍스트 제외 ✅ 완료

각 Assistant 응답에 👍/👎 토글을 추가하고, `DISLIKE` turn은 다음 프롬프트 컨텍스트에서 제외한다(`LIKE`는 저장만, 아직 미소비). `conversation_turns.feedback TEXT`(런타임 `ALTER TABLE`, `NULL|LIKE|DISLIKE`) + `PATCH /ui/threads/{threadId}/turns/{turnId}/feedback`(`OperationsController`, 소유권 확인 후 404/값 검증 400/성공 204). 제외 로직은 `getHistory()` SELECT에 `AND (feedback IS NULL OR feedback <> 'DISLIKE')` 한 줄. `ChatResponse.turnId` + `addTurn()`이 생성 id를 반환하도록 변경해 HTMX/DUAL/서버 복원/SSE 스트리밍 4개 렌더 경로 모두 같은 `.feedback-controls[data-turn-id] > .feedback-btn` 마크업 사용, 클릭 처리는 `chat.html`의 `#chat-messages` 위임 리스너 하나로 통일. `AuditLogger`에 `from`/`to` 기록. DUAL 모드는 외부 답변 turn 1개만 저장(로컬 답변엔 별도 피드백 없음 — 기존 정책과 동일). 테스트 +9(전체 335), LM Studio 연동 실사용 검증 완료.

### 6.10 입력 시작 시 로컬 요약 선계산 + 중복 제거 컨텍스트 압축 ✅ 완료

사용자가 입력을 시작하면(첫 글자 입력 즉시, 입력 세션당 1회) `POST /ui/chat/summary/precompute`가 가상 스레드로 발화되어, `ConversationSummarizerService`가 스레드 turn들을 정규화 기반 중복 제거(동일 질문은 최신 답변만 유지, DISLIKE는 §6.9와 동일하게 하드 제외)한 뒤 `TaskType.LIGHT_TEXT`+`RoutingMode.LOCAL_ONLY`로 LOCAL 프로바이더에 요약을 요청한다. 결과는 스레드별 LRU 캐시(최대 3개)에 저장되고, `AgentService`/`StreamingAgentService`는 캐시가 있으면 "요약 + 최근 2턴 원문"을 히스토리로 쓰고 없으면(미계산·실패·LOCAL 미가용 등 모든 경우) 기존 `getHistory()`로 조용히 폴백한다. 새 turn 저장 시 캐시를 무효화해 다음 입력에서 재생성되게 한다.

> **구현 메모 (2026-07-03)**:
> - **신규**: `ConversationSummarizerService`(`precompute`/`buildContext`/`invalidate`/`dedupe`). 캐시는 access-order `LinkedHashMap`+`removeEldestEntry`로 스레드 3개 LRU, 별도 맵으로 스레드당 15초 TTL 가드(프론트 디바운스와는 별개의 안전망).
> - **연동**: `AgentService`/`StreamingAgentService`에 `resolveHistory()` 헬퍼(`buildContext()` 우선, null이면 `getHistory()`) — directMode/일반 모드 양쪽에 적용. `addTurn()` 성공 직후 `invalidate(threadId)`.
> - **엔드포인트**: `ChatController.precomputeSummary()` — `Thread.ofVirtual().start(...)`로 즉시 202 반환(기존 `streamChat()`의 fire-and-forget 패턴 재사용), 실제 LLM 호출은 응답 이후 백그라운드 진행.
> - **프론트**: `#question-input`의 `input` 리스너가 입력 시작 즉시(0→비0 전환) 1회 트리거하고 `dataset.summaryPrecomputed`로 이번 세션 재호출 방지(요약 대상은 이미 저장된 turn뿐이라 타이핑 완료를 기다릴 이유가 없음). 메시지 전송 시(`chat.html`·`chat-stream.js` 양쪽 clear 경로) 플래그 초기화.
> - **프롬프트**: `prompt.summary.system` 신규 — 중복 제거·사실/결정 우선·제약/오류코드/버전 보존·추측 금지·3~5문장 평문 명시.
> - ⚠️ **원안 대비 단순화**: 앱 레벨 명시적 타임아웃(5~10초)은 별도 구현하지 않음 — 이미 fire-and-forget 백그라운드라 느려도 채팅 요청 자체를 막지 않으므로 LOCAL 프로바이더의 기존 HTTP client read-timeout을 그대로 상한으로 사용. 캐시 스키마도 원안의 `sourceTurnSeq`/`updatedAt` 대신 단순 `threadId→summary` 맵 + TTL 타임스탬프로 축소(무효화가 항상 전체 삭제라 부분 staleness 추적 불필요).
> - **검증**: `ConversationSummarizerServiceTest` 10건 + `AgentServiceTest` +3 + `ChatControllerHtmxTest` +1. 전체 354 tests BUILD SUCCESS(회귀 0). LM Studio 연동 실사용 확인: 질문1 전송 → 입력창 타이핑 시작 시 precompute 202 → 서버 로그에서 LOCAL 모델이 실제 요약 생성(`[SUMMARY] precomputed ... summaryChars=48`) 확인 → 질문2("방금 내가 뭘 물어봤지?")에서 `historyLen=108`(순수 원문 25자와 뚜렷이 구분되는 "요약+최근 턴" 길이)로 `buildContext()` 사용 확인 → 모델이 요약 기반으로 직전 질문을 정확히 회상하는 답변까지 브라우저에서 직접 확인.

### 6.11 사용자별 스토리지 쿼터 🔵 미착수 (§6.2에서 이관)

> **현재 코드 확인 (2026-07-02)**: `storage_used_bytes` 컬럼·쿼터 로직·프로퍼티 모두 없음. §6.2가 "완료"로 표기했으나 **미구현**. 저장은 공유 구조(`DocRegistry.SHARED`, `data/documents/`)라 "사용자별" 쿼터의 의미부터 재정의 필요.

**설계 결정 (선행)**:
- 저장소가 공유(per-user 격리 폐기)이므로 쿼터 축을 **택1**: (A) 사용자별 업로드 누적량(공유 저장이라도 업로더 기준 과금/제한) / (B) **전역 저장 상한**(단순, 공유 모델에 정합). 폐쇄망·단일 운영자 성격상 **(B) 전역 상한 1차 권장**, (A)는 멀티테넌트 과금 필요 시 후속.

**설계 (B 기준)**:
1. 프로퍼티 `app.upload.max-total-bytes`(0=무제한) + `AppProperties.uploadSafe()` null 가드.
2. `DocumentController` 업로드 진입 시 `data/documents/` 실제 사용량 합계(또는 `DocRegistry` 누적 크기) 조회 → 초과 시 `IllegalArgumentException`(→ `GlobalExceptionHandler` 400) 또는 신규 `QuotaExceededException`(→ 413/429).
3. 삭제 시 자연 감소(파일 삭제가 곧 사용량 반영) → 별도 카운터 불필요하면 컬럼 없이 디스크 walk로 충분(문서 수 적을 때). 정밀·고빈도면 `DocRegistry`에 누적 바이트 필드.

**설계 (A 기준 — 후속)**:
- `users`에 `storage_used_bytes` 컬럼을 **런타임 `ALTER TABLE`**(SqliteUserDetailsService의 기존 DDL 패턴)로 추가. 업로드 성공 시 `+= size`, 삭제 시 `-= size`. `app.upload.quota-mb-per-user` 초과 시 거부. ⚠️ 공유 저장소라 실제 디스크는 공유되므로 이는 "논리적" 쿼터임을 문서에 명시.

**완료 기준**:
- 상한 초과 업로드가 거부되고 명확한 코드/메시지를 반환한다.
- `app.upload.max-total-bytes=0`(기본)이면 회귀 0.
- 삭제 후 재업로드 가능(사용량 정확히 반영).

---

## 7. Phase 4 — 확장 (조건부) 🔵 미착수

### 7.1 OAuth2 소셜 로그인 (가입 마찰 문제 발생 시)

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-oauth2-client</artifactId>
</dependency>
```

Google/GitHub 제공자 등록. 가입 흐름은 **기존 폼 가입과 동등**하게 처리 (users 테이블 row 생성, `password_hash`는 NULL 허용).

### 7.2 PostgreSQL 마이그레이션 (SQLite 한계 신호 발생 시)

1. Flyway placeholder 도입: `${json_type}` → SQLite=`TEXT`, Postgres=`JSONB`
2. SQLite 데이터 덤프 → Postgres 임포트 스크립트 작성 (1회용)
3. 커넥션 풀: HikariCP 기본값 (max=10)으로 시작 → 부하 테스트 후 조정
4. Spring 프로파일 분리: `application-sqlite.properties` / `application-postgres.properties`

### 7.3 관리자 페이지 (부분 존재 → 확장)

> **현재 코드 확인 (2026-07-02)**: `/admin`·`/admin/chunks`는 **이미 존재**(`AdminController`/`AdminService`, Phase 5.8) — Vector Store 상태 카드 + 청크 브라우징/수정/삭제(두 백엔드). 아래는 **운영 관리 기능 확장**으로 범위 재정의.

- `ROLE_ADMIN` 전용 경로 확장(현재 청크 관리 위주 → 사용자/운영 관리 추가). ※ DB `role` 값은 `ADMIN` 문자열, 인증은 `NoAuthAutoLoginFilter`가 no-auth 모드에서 첫 `ADMIN` 사용자로 자동 인증.
- 사용자 목록·상태(잠금/활성), 전체 LLM 사용량(§6.6~6.8과 연계), 감사 로그(`data/audit/audit.log` NDJSON) 조회 뷰.
- 강제 로그아웃·계정 잠금/해제(`SqliteUserDetailsService`의 `locked_until`/`failed_count` 조작 재사용).

---

## 8. Phase 5 — Vector Store 선택적 연동 ✅ 완료 + 확장 계획

### 8.1 목표

`app.vectorstore.type=chroma`(기본, Docker 필요) ↔ `sqlite-vec`(인프라 0추가, 로컬·저사양·폐쇄망)를 **설정만으로 전환**. 두 백엔드 모두 `VectorStoreProvider` 뒤에 숨고 `VectorStoreFacade`가 횡단 관심사(SAFE_VERSION 검증·유사도 임계값)를 유지. 백엔드 전환 시 재인덱싱 필요(벡터 비공유).

### 8.2 작업 단계 로드맵

| 단계 | 작업 | 산출물 |
|------|------|--------|
| Step 5.1 ✅ | VectorStoreProvider 추상화 (Chroma 무행위 리팩토링) | `VectorStoreProvider`, `ChromaVectorStoreProvider` |
| Step 5.2 ✅ | sqlite-vec 네이티브 확장 로딩 | `DataSourceConfig.configureSqliteVec`, `SqliteVecVerifier` |
| Step 5.3 ✅ | sqlite-vec 스키마 초기화 | `SqliteVecSchemaInitializer` |
| Step 5.4 ✅ | SqliteVecVectorStoreProvider 구현 | `SqliteVecVectorStoreProvider` |
| Step 5.5 ✅ | 백엔드 선택 스위치 (조건부 빈) | `VectorStoreProviderConfig`, Chroma 빈 가드 |
| Step 5.6 ✅ | 설정 외부화 (.env / docker-compose) | properties, `.env.example`, compose profiles |
| Step 5.7 ✅ | 데이터 이전 + 통합 검증 | 재인덱싱 절차, 단위·통합 테스트 |
| Step 5.8 ✅ | 관리자 페이지 백엔드 가시성 보강 (sqlite-vec) | `VectorStoreAdminView`(신규), `AdminService`에 `JdbcTemplate`/`AppProperties`/`ObjectMapper` 주입, `/admin` 백엔드 공통 상태 카드 + 청크 브라우징 패리티, `AdminService`·`AdminController` 테스트 |
| Step 5.9 ✅ | 태그 기반 검색 스코프(엄격 필터 + sqlite 후보확대 보정) | 업로드 다중 태그·채팅 태그 선택·백엔드별 엄격 필터·프리릴리즈 수동 초기화 런북 |
| Step 5.10 ✅ | sqlite-vec 운영 DB 분리(최소 변경) | 운영 SQLite(`memory.db`)와 벡터 SQLite(`vector.db`)를 분리, DataSource/JdbcTemplate 2세트 구성, 무중단 롤백 스위치 |

### Step 5.1~5.8 — 백엔드 추상화 + sqlite-vec 구현 ✅ 완료

- **5.1** Chroma 결합을 `VectorStoreProvider`(search/searchBatch/add/deleteByDocIds) 인터페이스 뒤로 이전 — 동작 변화 없는 순수 리팩토링, 호출부 시그니처 불변.
- **5.2** sqlite-jdbc `load_extension()`으로 운영자 제공 `vec0` 바이너리 로드(신규 의존성 0, 공식 Maven 아티팩트 없음). `SqliteVecVerifier`가 기동 시 `vec_version()` 확인 후 fail-fast.
- **5.3** `vec0` 차원이 DDL 상수라 Flyway 대신 시작 시 동적 `IF NOT EXISTS` DDL. 벡터(`vec_embeddings`)와 텍스트·메타(`vec_document_chunks`)를 분리해 `spring_doc_id`로 JOIN. `app.embedding.dimensions` 미설정 시 fail-fast.
- **5.4** `SqliteVecVectorStoreProvider`: version을 vec0 partition key로 KNN 내부 필터링(한 쿼리로 topK), cosine 거리→유사도 변환(Chroma와 동일 스케일). vec0가 upsert 미지원이라 add는 DELETE 후 INSERT.
- **5.5** `VectorStoreProviderConfig`가 `app.vectorstore.type`으로 provider 택일(chroma 기본). Chroma 전용 빈은 `@ConditionalOnProperty`로 가드, 두 모드 모두 `VectorStoreProvider` 빈 정확히 1개.
- **5.6** `chroma` 서비스를 compose profile로 분리, `depends_on.required:false`로 sqlite-vec 모드에서 무-Chroma 기동.
- **5.7** 백엔드 전환 = 재인덱싱(원본 보존이라 무손실). `SqliteVecIntegrationTest`(vec0 바이너리 없으면 skip)로 add→search→delete E2E 검증.
- **5.8** `AdminService`가 `ChromaApi` 전용이라 sqlite-vec 모드에서 청크 브라우징이 비어 있던 문제를 `VectorStoreAdminView`로 해결 — 백엔드 공통 상태 카드 + 청크 CRUD 패리티(sqlite-vec은 `vec_document_chunks` 직접 조회, 단일 "active version" 개념이 없어 버전별 청크 수로 표시).

### Step 5.9 — 태그 기반 검색 스코프 ✅ 완료

업로드 시 다중 태그를 저장하고, 채팅에서 선택한 태그로 **엄격 AND 필터**를 적용(Chroma·sqlite-vec·하이브리드 BM25 공통). 핵심 설계: Spring AI/vec0 모두 배열 포함 연산자가 없어 provider 레이어 태그 push-down이 불가하므로, `RetrievalService.execute()`가 `mergeRrf()` 직후 **Java post-filter**(`TagUtils.parseTagList`로 String/Collection 방어)를 적용하고, 결과 부족 대비 `candidateK`를 선제 확대(`app.search-tag-candidate-multiplier`, 재호출 없음)한다. 태그 소스는 하이브리드 BM25용 `chunk_fts.doc_tags` 컬럼 하나 — 태그 제안 UI(`GET /api/v1/tags`)와 재인덱싱 시 태그 자동복원(`DocumentIndexer.restoreTags`, 동기화 갱신 경로에서 빈 태그로 소실되던 결함 수정) 모두 여기서 조회한다. 프리릴리즈 정책상 스키마 변경(FTS5 `doc_tags` 추가)은 마이그레이션 없이 수동 초기화(OPERATOR_MANUAL §4.6).

### Step 5.10 — sqlite-vec 운영 DB 분리 ✅ 완료

`app.vectorstore.sqlite-vec.db-path`(`SQLITE_VEC_DB_PATH`) 설정 시 벡터/FTS 테이블(`vec_embeddings`/`vec_document_chunks`/`chunk_fts`)을 별도 `vector.db`로 분리해 인덱싱 I/O와 운영 트랜잭션의 락 경합을 줄인다(기본값 빈 문자열 = 분리 비활성, 기존과 동일). `vectorJdbcTemplate` 빈을 **두 모드 모두 정의**(분리 시 `vector.db` / 그 외 `memory.db` 별칭)해 `KeywordSearchRepository` 등 소비자 코드가 무변경으로 양쪽에 대응, Chroma 경로는 완전 무영향. 파일 간 트랜잭션 원자성이 없으므로 인덱싱은 항상 *벡터/FTS 먼저 → 레지스트리 마지막* 순서로 커밋해 "레지스트리는 성공인데 벡터 없음" 상태를 방지(고아 벡터는 재인덱싱이 덮어쓰므로 허용). 전용 DataSource도 pool=1+WAL+busy_timeout을 동일 복제.

---

## 9. Phase 6 — 폐쇄망(Air-gapped) / 노-도커 실행 지원 🟢 G1~G5 완료 (sqlite-vec 라이브 부팅은 운영 인수)

> 폐쇄망/노-도커 런북은 [OPERATOR_MANUAL.md §4.5](OPERATOR_MANUAL.md) 참조. 아래는 요약.

### 9.1 목표

(A) Docker 없이 실행, (B) 폐쇄망에서 sqlite-vec 단독 + 로컬 LLM(llama-server) 운영. Phase 5로 ChromaDB(유일 필수 Docker)를 제거할 수 있게 됐고, 프론트 자산은 webjar/로컬 번들이라 CDN 의존 0.

### 9.2 현황 (Phase 5 시점 이미 충족)

sqlite-vec 단독·CDN 0·로컬 LLM 채팅·외부 프로바이더 빈 키 자동 비활성·`LOCAL_ONLY` 모드·로컬 임베딩 엔드포인트·HTTP 직노출 옵션·Vision 기본 `strip`.

### 9.3 해결한 공백/함정 (G1~G4)

- **G1** — `LlmConfig`가 빈 키 프로바이더를 드롭하던 문제: LOCAL role은 빈 키여도 등록(`"no-key"` 치환)하도록 변경 → 키리스 llama-server 채팅 가능.
- **G2** — `app.embedding.dimensions=${EMBED_DIMENSIONS:}`(빈값→null 안전) 외부화 + `.env.example` 차원표. sqlite-vec DDL 전용이라 모델 실제 출력 차원과 정확히 일치 필수(미설정 시 fail-fast).
- **G3** — `app.llm.default-routing-mode=${LLM_ROUTING_MODE:COST_FIRST}`로 외부화 → `LOCAL_ONLY`로 외부 호출 명시 차단.
- **G4** — `USE_CADDY_REVERSE_PROXY_HTTPS` 오타(CANDY) 정리.

### 9.4 작업 항목

| 단계 | 작업 | 상태 |
|---|---|---|
| 6.1 | LOCAL role 키리스 허용 (`LlmConfig`) | ✅ |
| 6.2 | `EMBED_DIMENSIONS` 외부화 + 차원표 | ✅ |
| 6.3 | `LLM_ROUTING_MODE` 외부화 | ✅ |
| 6.4 | 폐쇄망 런북 (OPERATOR_MANUAL §4.5 + README 환경변수표) | ✅ |
| 6.5 | 무-외부호출 기동 인수 | ✅ (sqlite-vec 라이브 부팅은 운영 인수) |
| 6.6 | (선택) `USE_CADDY_…` 하위호환 별칭 | 🔵 미착수 |

### 9.5 폐쇄망 운영 전제 (요약)

빌드 산출물(fat-jar + JRE 21 + 호스트 arch vec0 바이너리 + 필요 시 tessdata) 반입, OCR/Vision은 옵션, TLS는 HTTP 직노출 또는 사내 역프록시/사설 CA, `.env`는 노-도커 시 자동 로드 안 됨(셸 export/기동 인자). 상세는 OPERATOR_MANUAL §4.5.

### 9.6 인수 결과

G1~G4 코드/문서 완료. G5는 라우팅 계층 "외부 무선택"을 `LlmConfigTest.airGappedNeverRoutesToExternal`로 결정적 검증. 전체 286 tests BUILD SUCCESS(sqlite 통합 2개는 vec0 바이너리 없을 때 skip). vec0 필요한 sqlite-vec 라이브 부팅·실소켓 0 측정은 운영 인수.

---

## 10. 리스크 및 이슈

| 리스크 | 심각도 | 완화 방안 |
|--------|--------|----------|
| BCrypt 동시 해시로 CPU 포화 | 중 | cost=10~12 사이 부하 테스트 후 결정. Virtual Thread는 CPU-bound 작업엔 도움 안 됨에 주의 |
| SQLite write 락 — 로그인 실패 카운트 UPDATE와 채팅 INSERT 충돌 | 중 | `busy_timeout=5000` 설정. 잦은 쓰기 테이블은 별도 SQLite 파일로 분리 검토 |
| HTMX + CSRF 헤더 누락으로 모든 요청 403 | **고** | `htmx:configRequest` 전역 핸들러로 자동 주입. `chat-stream.js` fetch에도 적용 누락 없는지 e2e 점검 |
| IDOR — 타 사용자 `threadId`/`docId` 접근 | **고** | Repository 시그니처에 userId 강제. Controller 진입 시점에 소유권 검증 단위 테스트 추가 |
| Chroma 컬렉션명 길이 제한 (63자) | 중 | UUID 앞 8자 + version만 사용. 충돌 방지 위해 `users.id`를 UUIDv7로 발급 |
| Flyway SQLite 모듈 호환성 | 중 | Flyway 10.x 기준 `flyway-database-sqlite` 별도 의존성 필요. PoC 먼저 수행 |
| 세션 쿠키 도난 — XSS | **고** | CSP 엄격 적용, HttpOnly+Secure+SameSite=Lax, DOMPurify 유지 |
| 업로드 파일 path traversal | **고** | 저장 경로 `Path.normalize().startsWith(userBase)` 검증 |
| 모바일 키보드 가림 | 중 | `100dvh` 단위, `visualViewport` API로 입력창 위치 조정 |
| HTTPS 인증서 갱신 실패 | 중 | Caddy 자동 갱신 + 만료 30일 전 헬스체크 알림 |
| sqlite-vec — SQLite pool=1과 vec0 쓰기 충돌 | **고** | 기존 `busy_timeout=5000` 유효. vec0도 WAL 모드 하에서 동작하나 대규모 add() 시 write 홀딩 시간 측정 필요 |
| sqlite-vec — 차원수 불일치 | **고** | `vec_embeddings` 테이블 생성 시 `app.embedding.dimensions` 값으로 DDL 생성. 임베딩 모델 변경 시 DROP+재인덱싱 필수 — 자동 감지 불가 |
| sqlite-vec — 네이티브 바이너리 운영자 제공 | 중 | 공식 Java 아티팩트가 없어 운영자가 플랫폼별 `vec0` loadable을 배치하고 `SQLITE_VEC_EXTENSION_PATH`로 지정. Docker는 컨테이너 아키텍처(`linux/amd64` 등)에 맞는 바이너리 사용. 미설정/플랫폼 불일치 시 `SqliteVecVerifier`가 기동 시 fail-fast |
| sqlite-vec — searchBatch() N회 JDBC 쿼리 성능 | 낮 | 임베딩 배치 생성(1 HTTP 호출)이 병목. JDBC 쿼리 N번은 인메모리 SQLite 특성상 수 ms 수준으로 예상. 실측 후 CTE 방식 전환 검토 |
| sqlite-vec — Spring AI VectorStore 미지원 | 중 | 커스텀 `VectorStoreProvider` 구현으로 Spring AI 인터페이스 우회. Spring AI 공식 sqlite-vec 지원 시 마이그레이션 경로 단순화 |
| 태그 엄격 필터 — 결과 과소(recall 저하) | 중 | sqlite-vec 후보확대 보정(`candidateK` 단계 확대), AND 고정 1차 출시 후 OR 모드 후속 도입 |
| 프리릴리즈 수동 초기화 — 데이터 유실 위험 | 중 | 적용 전 운영자 체크리스트(백업 선택), 초기화 대상 경로 명시, 재업로드/동기화 검증 절차 문서화 |

---

## 11. 의존성 변경 사항 (pom.xml)

### 10.1 추가

```xml
<!-- Phase 1 -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
  <groupId>org.thymeleaf.extras</groupId>
  <artifactId>thymeleaf-extras-springsecurity6</artifactId>
</dependency>
<dependency>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-database-sqlite</artifactId>
</dependency>

<!-- Phase 3 -->
<dependency>
  <groupId>com.bucket4j</groupId>
  <artifactId>bucket4j_jdk17-core</artifactId>
  <version>8.10.1</version>
</dependency>

<!-- Phase 5 — sqlite-vec: 신규 의존성 없음.
     공식 Java Maven 아티팩트가 없어 기존 org.xerial:sqlite-jdbc의 load_extension 으로 로드하고,
     vec0 네이티브 바이너리는 운영자가 배치한다 (Step 5.2 참조). -->

<!-- 테스트 -->
<dependency>
  <groupId>org.springframework.security</groupId>
  <artifactId>spring-security-test</artifactId>
  <scope>test</scope>
</dependency>
```

### 10.2 application.properties 추가

```properties
# Security
server.servlet.session.cookie.secure=true
server.servlet.session.cookie.http-only=true
server.servlet.session.cookie.same-site=lax
server.servlet.session.timeout=8h
server.forward-headers-strategy=framework

# Flyway
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration
spring.flyway.baseline-on-migrate=true

# SQLite tuning
spring.datasource.hikari.connection-init-sql=PRAGMA busy_timeout=5000; PRAGMA journal_mode=WAL;

# Limits — ⚠️ 아래 app.security.* 는 계획일 뿐 실제 코드에 존재하지 않음 (2026-07-02 확인).
#   현재 값은 상수 하드코딩: BCrypt cost=12 → SecurityConfig.java,
#   로그인 잠금 5회/15분 → AuthEventListener.java (MAX_ATTEMPTS/LOCK_MINUTES).
#   업로드 쿼터(upload-quota-mb)는 미구현 (§6.11 참조).
#   외부화가 필요하면 AppProperties에 SecurityConfig record + securitySafe() 추가 후 주입 지점 교체.
# app.security.bcrypt-cost=12
# app.security.login-lock-attempts=5
# app.security.login-lock-minutes=15
# app.security.upload-quota-mb=500

# Vector Store (Phase 5)
app.vectorstore.type=${VECTORSTORE_TYPE:chroma}                          # chroma(기본) | sqlite-vec
app.vectorstore.sqlite-vec.extension-path=${SQLITE_VEC_EXTENSION_PATH:}  # sqlite-vec: vec0 바이너리 경로(운영자 제공)
app.vectorstore.sqlite-vec.entrypoint=${SQLITE_VEC_ENTRYPOINT:}          # sqlite-vec: 보통 불필요
```

---

## 12. DB 스키마 변경 요약

> ⚠️ **실제 스키마 관리 방식 (2026-07-02 확인)**: Flyway는 **`V1__baseline`+`V2__users` 두 개만** 존재한다. 나머지 컬럼/인덱스는 **런타임 멱등 DDL**(`SqliteMemoryRepository`·`SqliteUserDetailsService`의 `CREATE TABLE IF NOT EXISTS` + `ALTER TABLE ADD COLUMN`)로 관리된다. 아래 표에서 V3~V5는 **계획일 뿐 파일이 없으며**, 해당 변경은 런타임 DDL로 흡수되었거나(=user_id/토큰 컬럼) 미구현(=storage_used_bytes)이다.

| 마이그레이션/DDL | 상태 | 내용 |
|------------|------|------|
| `V1__baseline.sql` | ✅ 존재 | 기존 테이블 (`conversation_turns`, `llm_usage`, `thread_meta`, `image_descriptions`) 캡처 |
| `V2__users.sql` | ✅ 존재 | `users`(`role` TEXT DEFAULT 'USER'), `persistent_logins` 신규 테이블 |
| ~~`V3__user_scope.sql`~~ | ❌ 파일 없음 | `user_id` 컬럼·인덱스는 `SqliteMemoryRepository` 런타임 `ALTER TABLE`로 추가됨 |
| ~~`V4__audit_log.sql`~~ | ❌ 파일 없음 | 감사로그는 **테이블이 아니라 Logback 파일**(`data/audit/audit.log`, §6.4). 테이블 불필요 |
| ~~`V5__upload_quota.sql`~~ | ❌ 파일 없음 | `storage_used_bytes` **미구현**(§6.11) |
| (Phase 5) `SqliteVecSchemaInitializer` | ✅ 동적 DDL | `vec_embeddings`(vec0 — `version` partition key + `distance_metric=cosine`), `vec_document_chunks`, `chunk_fts`(FTS5, `doc_tags`) — Flyway 대신 앱 시작 시 동적 DDL(차원 파라미터화). `SQLITE_VEC_DB_PATH` 설정 시 `vector.db`로 분리(§5.10) |

> **신규 컬럼 추가 지침**: 피드백(§6.9)·스토리지(§6.11 A안) 등 신규 컬럼은 **새 Flyway 파일이 아니라** 기존 런타임 `ALTER TABLE ADD COLUMN` 패턴에 한 줄 추가하는 것이 현 코드와 정합적(멱등, 프리릴리즈 정책과도 부합).

---

## 13. 최종 체크리스트

### 12.1 Phase 1 완료 기준 ✅

- [x] HTTPS: Caddy 설정 완료 — 도메인 배포 시 Let's Encrypt 자동 발급, HTTP → HTTPS 자동 리다이렉트
- [x] 비로그인 사용자는 `/`, `/chat/**`, `/documents`, `/api/**` 접근 불가 (단 `/login`, `/signup`, `/api/health`는 허용)
- [x] 회원가입 → 자동 로그인 → 채팅 (SecurityContextHolder 수동 주입)
- [x] 멀티유저 데이터 격리 — SQLite `user_id` 컬럼 + Chroma `u_{userId8}_{version}` 컬렉션
- [x] Flyway 마이그레이션 (`V1__baseline`·`V2__users`) + 런타임 멱등 DDL로 `user_id`/토큰 컬럼 관리 (V3~V5는 미생성 — §12 참조)
- [x] 모든 HTMX 요청 + `chat-stream.js` fetch에 CSRF 토큰 자동 첨부
- [x] 로그인 5회 실패 시 15분 잠금
- [x] `app.auth.enabled=false` no-auth 모드 (guest 자동 로그인, 첫 실행 `/setup`)

### 12.2 Phase 2 완료 기준

- [ ] iPhone Safari / Android Chrome에서 좌우 스크롤 없음
- [ ] 채팅 입력창 키보드 출현 시에도 화면 하단 고정
- [ ] 홈 화면 추가 시 standalone 앱처럼 실행
- [ ] 다크모드 자동 전환 + 수동 토글 동작

### 12.3 Phase 3 완료 기준

- [x] Rate limit 초과 시 HTTP 429 + `Retry-After` 헤더 반환
- [x] 확장자 위조 파일 업로드 차단 (매직바이트 검증)
- [ ] 사용자 `storage_used_bytes` > 쿼터 시 업로드 거부
- [x] 주요 액션(로그인/업로드/삭제) `audit_log`에 기록
- [ ] 일일 LLM 토큰 한도 초과 시 채팅 차단

### 12.4 운영 준비 (Phase 1 종료 전)

- [ ] SQLite 백업: Litestream 도입 또는 cron `.backup` 명령 (`cp`는 위험)
- [x] `.env` 파일 git ignore 확인, API 키 로깅 마스킹 검증 (`PromptInjectionGuard.maskApiKey` + 테스트 완료)
- [x] 로그 레벨: `org.springframework.security`는 운영에서 WARN (`SPRING_SECURITY_LOGGING_LEVEL` 환경변수 기본값 `WARN` 적용)
- [ ] Caddy 인증서 만료 모니터링
- [x] 관리자 계정 1개 사전 생성 (no-auth 모드 최초 실행 시 `/setup` 페이지로 관리자 생성)

---

## 부록 — 결정 사항 한눈에 보기

| 주제 | 결정 | 대안 (보류) |
|------|------|-----------|
| 인증 방식 | HttpSession + Spring Security 폼 로그인 | JWT (다중 서버 시 도입) |
| 비밀번호 해시 | BCrypt cost=12 | Argon2 (CPU/메모리 변수 더 많음, 부하 테스트 후 결정) |
| TLS 종료 | Caddy 리버스 프록시 | Spring 직접 TLS, Cloudflare |
| DB | SQLite 유지 (WAL+busy_timeout) | PostgreSQL (한계 신호 발생 시) |
| 마이그레이션 | Flyway + ANSI SQL | Liquibase |
| 멀티테넌시 | Row-level + 사용자별 Chroma 컬렉션 | Schema/DB per tenant |
| 세션 저장소 | 인메모리 (단일 인스턴스) | Spring Session + Redis (스케일아웃 시) |
| CSRF | Spring Security 기본 + HTMX 자동 주입 | Double-submit cookie |
| Rate limit | Bucket4j 인메모리 | Redis 백엔드 (스케일아웃 시) |
| 모바일 | HTMX + Bootstrap Offcanvas + PWA | React Native 네이티브 앱 |
| 소셜 로그인 | Phase 4 (필요해질 때) | Phase 1 동시 — 복잡도 ↑ |
| Vector Store | `app.vectorstore.type` 런타임 선택 (기본 `chroma`) | Spring AI 공식 sqlite-vec 지원 시 단순화 가능 |
| sqlite-vec 스키마 | 앱 시작 시 동적 DDL (`IF NOT EXISTS`) — 차원수 파라미터화 | Flyway 마이그레이션 — vec0 DDL에 차원 하드코딩 필요해 부적합 |
| sqlite-vec searchBatch | 임베딩 배치(1 HTTP) + JDBC 루프(N 쿼리) | 단일 CTE 쿼리 — vec0 API 제약으로 현재 불가 |
