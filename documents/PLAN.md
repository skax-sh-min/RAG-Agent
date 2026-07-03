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
- **Phase 3 잔여** (미착수, §6.5~6.8·6.10~6.11 상세): 사용자별 LLM 토큰 쿼터(§6.5) · 사용자별 스토리지 쿼터(§6.11) · 임베딩 사용량 분리(§6.6) · 비활성 프로바이더 조건부 표시(§6.7) · orphan 프로바이더 기록 삭제(§6.8) · 대화 요약 선계산(§6.10)
- **Phase 4** (조건부, 미착수): OAuth2 소셜 로그인(§7.1) · PostgreSQL 마이그레이션(§7.2) · 관리자 페이지 확장(§7.3, ※ `/admin` 기본 골격은 Phase 5.8에서 이미 존재)
- ~~**Phase 5**: sqlite-vec 선택적 연동~~ → ✅ 완료 (Step 5.1~5.8, `app.vectorstore.type=chroma|sqlite-vec`)
- ~~**Phase 5 추가**: Step 5.9 태그 기반 검색 스코프 + Step 5.10 sqlite-vec 운영/벡터 DB 분리~~ → ✅ 완료 (2026-07-01, Step 5.9 태그 필터/제안/복원 + Step 5.10 `SQLITE_VEC_DB_PATH` 분리 스위치). vec0 라이브 부팅은 운영 인수
- ~~**Phase 6**: 폐쇄망/노-도커 — 키리스 LOCAL(G1)·차원 외부화(G2)·라우팅 외부화(G3)·런북(G4)·무-외부호출 인수(G5)~~ → ✅ G1~G5 완료 (2026-06-25). sqlite-vec 라이브 부팅(vec0 바이너리)만 운영 인수

### ⚠️ 코드 대조 정정 (2026-07-02)

실제 소스와 대조한 결과 아래 항목은 문서 표기와 어긋나 있어 정정한다(상세는 각 절).

| 문서 표기 | 실제 코드 | 정정 위치 |
|---|---|---|
| §6.2 "Apache Tika MIME 검증" | Tika 의존성 없음. `FileTypeDetector` **매직바이트** 검증 | §6.2 |
| §6.2 "사용자별 누적 용량 쿼터(기본 500MB)" ✅ | **미구현**. 저장은 공유(`DocRegistry.SHARED`), `storage_used_bytes` 컬럼·쿼터 로직 없음 | §6.2, §6.11 |
| §6.2 "업로드 경로 `data/users/{userId}/`" | 공유 경로 `data/documents/`(per-user 격리 폐기, Phase 3에서 단순화) | §6.2 |
| §10.2 `app.security.bcrypt-cost/login-lock/upload-quota` 프로퍼티 | **존재하지 않음**. BCrypt cost=12는 `SecurityConfig`, 잠금(5회/15분)은 `AuthEventListener` 상수 하드코딩 | §10.2 |
| §12 마이그레이션 `V3__user_scope`/`V4__audit_log`/`V5__upload_quota` | **파일 없음**. Flyway는 `V1__baseline`+`V2__users`만. `user_id`·토큰 컬럼은 `SqliteMemoryRepository` 런타임 `ALTER TABLE`, 감사로그는 Logback 파일(테이블 아님), 스토리지 컬럼 없음 | §12 |

> 스키마 관리 실태: **Flyway(V1·V2 baseline) + 런타임 멱등 DDL 혼용**. `SqliteMemoryRepository`/`SqliteUserDetailsService`가 `CREATE TABLE IF NOT EXISTS` + `ALTER TABLE ADD COLUMN`으로 컬럼을 증분 추가한다. 따라서 신규 컬럼(피드백·스토리지)은 새 Flyway 마이그레이션이 아니라 **기존 런타임 `ALTER TABLE` 패턴**으로 추가하는 것이 현 코드와 정합적이다.

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

### 2.2 약점 (개선이 필요한 부분)

| 항목 | 현재 | 온라인 전환 시 문제 |
|------|------|-------------------|
| 인증 | 없음 | 모든 데이터 무제한 접근 |
| `threadId` 소유 개념 | UUID, 누구나 접근 | IDOR 취약점 |
| 파일 저장 경로 | `data/` 단일 | 사용자 간 격리 없음 |
| Chroma 컬렉션 | `manual_{version}` | 모든 사용자 벡터 혼재 |
| HTTPS | HTTP 8080 직접 노출 | 세션 쿠키 평문 전송 |
| 마이그레이션 도구 | 없음 (SQL 직접 실행 가정) | 스키마 변경 추적 불가 |
| 모바일 UI | viewport meta만 존재 | 오프캔버스/하단 입력 미구현 |
| Rate limit | 없음 | LLM 비용 폭주 위험 |

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

### 6.6 LLM 사용량 — 임베딩 사용량 분리 🔵 계획

> **현재 코드 확인 (2026-07-02)**: `EmbeddingBeanConfig.embeddingModel()`은 순수 `@Primary OpenAiEmbeddingModel`(데코레이터 없음) → 임베딩 토큰 추적 0. `TrackingEmbeddingModel` 미존재. `LlmUsageRepository.record()`는 채팅 경로만. 아래 설계 유효.

**문제**: 현재 `llm_usage`에는 **채팅(ChatModel) 호출만** 기록된다. `LlmRouter.executeWithTracking()` → `usageRepo.record(provider, in, out)` 경로만 집계하고, 임베딩은 `@Primary EmbeddingModel`(OpenAiEmbeddingModel) 빈을 통해 `LlmRouter`를 **우회**하므로 토큰 사용량이 전혀 추적되지 않는다. 인덱싱·검색마다 임베딩 API를 호출하지만 사용량/비용 통계에 보이지 않아 운영자가 임베딩 비용을 파악할 수 없다.

**선행 확인 (현재 코드 기준)**:
- 임베딩 호출 지점: `SqliteVecVectorStoreProvider`(search/searchBatch/add), `ChromaVectorStoreProvider.searchBatch`, 그리고 Chroma 인덱싱은 `ChromaVectorStore.add()`가 **주입된 EmbeddingModel 빈으로 내부 임베딩**.
  → `@Primary EmbeddingModel` 빈 **한 곳을 데코레이터로 감싸면** 모든 임베딩 호출(검색·인덱싱, 두 백엔드 공통)을 단일 지점에서 가로챌 수 있다.
- `llm_usage`는 `provider_name` 키 기반(`record/getByPeriod/getDailyHistory` 모두 이름 인자) → 임베딩을 별도 이름으로 기록하면 공존 가능. 단 UI(`OperationsController`)는 `props.llmSafe().providers()`(채팅 프로바이더)만 순회하므로 **임베딩 행은 자동 표시되지 않음** → UI에 명시적 추가 필요.
- 임베딩은 입력 토큰만 존재(출력 0). 토큰 수는 `EmbeddingResponse.getMetadata().getUsage()`에서 추출(OpenAI 호환 서버 제공). 로컬 llama-server 등 usage 미반환 시 fallback(텍스트 길이 기반 근사 또는 0) 필요.

**설계 (권장: 예약 프로바이더 식별자 — 스키마 변경 없음)**:
1. `TrackingEmbeddingModel implements EmbeddingModel` 데코레이터 — 실제 모델에 위임 후 응답 usage를 `usageRepo.record("embed:" + model, inputTokens, 0)`으로 기록. `EmbeddingBeanConfig`에서 실제 모델을 이 데코레이터로 래핑해 `@Primary`로 노출(주입 지점 변경 없음).
   - `embed:` 접두사로 채팅 프로바이더 이름과 충돌 방지.
   - usage 미제공 시 입력 텍스트 길이 근사(예: chars/4) 또는 0 기록 — 설정 플래그로 선택, 경고 로그 1회.
2. `OperationsController` / `llm-usage.html` 확장:
   - 사용량 표·카드에 **임베딩을 별도 행/카드로 분리**(type=`EMBEDDING`, 출력 토큰·circuit breaker 없음).
   - 차트는 임베딩을 **별도 데이터셋(고유 색)** 또는 별도 미니 차트로 — 채팅 합계에 섞이지 않게.
   - `/api/v1/llm/usage`·`/usage/history`가 `embed:*` 행도 포함하도록 조회 경로 추가(채팅 프로바이더 순회와 분리).

**대안 (보류)**: `llm_usage`에 `kind`('chat'|'embedding') 컬럼 + PK 확장. 같은 모델명을 채팅/임베딩 양쪽으로 쓸 때 유리하나 SQLite PK 변경(테이블 재생성) 필요 → 현 요구엔 과함.

**완료 기준**:
- 인덱싱/검색 시 임베딩 토큰이 `llm_usage`에 `embed:<model>`로 누적된다(채팅 행과 분리).
- `/llm-usage` 화면에서 임베딩 사용량이 채팅 프로바이더와 **시각적으로 분리**되어 표시된다(표 행/카드 + 차트 구분).
- usage 미반환 임베딩 서버에서도 기록이 깨지지 않는다(근사 또는 0 + 경고 로그).
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

> **현재 코드 확인 (2026-07-02)**: `conversation_turns`에 `feedback` 컬럼 없음(현 컬럼: question·answer·asked_at·input_tokens·output_tokens·elapsed_ms·provider·llm_calls·user_id, PK `id`). ⚠️ 문서가 지칭한 `ConversationRepository`는 **존재하지 않음** — 실제 접근 계층은 `repository/SqliteMemoryRepository`(+ `service/MemoryService`)다. 컬럼 추가는 새 Flyway가 아니라 `SqliteMemoryRepository`의 기존 런타임 `ALTER TABLE ADD COLUMN` 목록에 한 줄 추가하는 방식이 정합적. **turnId = `conversation_turns.id`(PK)**.

**목표**:
- 각 Assistant 응답에 대해 사용자가 `좋아요/싫어요`를 남길 수 있게 한다.
- `싫어요`로 표시된 응답은 다음 질문의 프롬프트 컨텍스트 구성에서 제외한다.

**요구사항 해석**:
- 피드백은 turn 단위로 저장한다(질문 단위 아님).
- `좋아요`는 가산점(향후 활용)으로 저장하되, 1차에서는 컨텍스트 포함/제외 결정에 직접 사용하지 않는다.
- `싫어요`는 hard exclusion 규칙으로 적용한다.

**설계(최소 침습)**:
1. **데이터 모델 확장**
  - `SqliteMemoryRepository`의 런타임 DDL 목록에 `ALTER TABLE conversation_turns ADD COLUMN feedback TEXT`(값: `NULL | LIKE | DISLIKE`) 추가. 기존 행은 `NULL`(안전).
2. **저장/조회 경로**
  - `SqliteMemoryRepository.updateFeedback(userId, threadId, turnId, feedback)` 추가(소유권 위해 `WHERE user_id=? AND thread_id=? AND id=?`). `MemoryService`에 위임 메서드.
  - ⚠️ **핵심**: `getHistory(userId, threadId, maxChars)`는 현재 `question, answer`를 이어붙인 **단일 String**을 반환한다. `DISLIKE` 제외는 이 SELECT에 `AND (feedback IS NULL OR feedback <> 'DISLIKE')`를 추가하면 곧바로 컨텍스트에서 빠진다. UI 복원용 조회(`id ASC` 전체 turn)는 feedback 값을 포함해 반환(버튼 상태 표시).
3. **UI/HTMX**
  - 채팅 버블(`message-assistant.html`) 하단에 👍/👎 토글. 클릭 시 `PATCH /ui/threads/{threadId}/turns/{turnId}/feedback`(신규, `OperationsController` 또는 신규 컨트롤러). 재클릭 시 해제(`NULL`). CSRF는 기존 htmx 자동 주입 재사용.
4. **프롬프트 빌드 규칙**
  - `getHistory()`의 SELECT 필터로 `DISLIKE` turn 제외(2번). Assistant 응답에만 적용, 사용자 질문 텍스트는 유지.
5. **관찰성**
  - `AuditLogger`에 feedback 변경 이벤트 기록(`threadId`, `turnId`, `from`, `to`).

**예외/정책**:
- 동일 turn에 대해 마지막 선택만 유효(멱등 업데이트).
- no-auth 모드에서도 현재 thread 소유 컨텍스트 내에서만 변경 허용.
- 삭제된 turn/타 thread turn에 대한 피드백 변경은 404/403 처리.

**완료 기준**:
- [x] 채팅 UI에서 turn별 👍/👎 선택/해제가 가능하다.
- [x] `👎`가 붙은 Assistant turn은 다음 요청 프롬프트 컨텍스트에서 제외된다.
- [x] 기존 대화 저장/복원 동작 회귀가 없다.
- [x] 피드백 변경 이력이 감사 로그에 남는다.

> **구현 메모 (2026-07-02)**:
> - **모델/저장**: `MemoryRepository`/`SqliteMemoryRepository`에 `feedback TEXT` 컬럼(런타임 `ALTER TABLE`), `Turn` 레코드에 `id`+`feedback` 필드 추가. `addTurn()`을 `void`→`long`(생성 turn id 반환, `GeneratedKeyHolder`)으로 변경. `getFeedback()`(소유권 확인 + 감사로그 "from", `Optional<FeedbackRow>`로 "찾음+feedback=null" vs "못 찾음" 구분) + `updateFeedback()` 신규.
> - **컨텍스트 제외**: `getHistory()` SELECT에 `AND (feedback IS NULL OR feedback <> 'DISLIKE')` 추가 — 설계대로 SQL 필터 한 줄로 처리.
> - **응답 경로**: `ChatResponse`에 `turnId`(nullable Long) 필드 추가. `AgentService.chat()`/`StreamingAgentService.run()` 양쪽 모두 `addTurn()` 반환값을 캡처해 전달(스트리밍은 SSE `done` 이벤트 payload에 `turnId` 포함).
> - **엔드포인트**: `OperationsController`에 `PATCH /ui/threads/{threadId}/turns/{turnId}/feedback`(`@RequestParam feedback=LIKE|DISLIKE|NONE`, `ResponseEntity<Void>` — 성공 204 / 미소유·미존재 404 / 잘못된 값 400). 감사로그에 `turnId`+`from`+`to` 기록.
> - **UI**: `message-assistant.html`·`message-assistant-dual.html`(HTMX 응답)·`chat.html`(서버 렌더 복원 turn)·`chat-stream.js`(SSE 스트리밍 버블) 4곳 모두 동일한 `.feedback-controls[data-turn-id][data-thread-id] > .feedback-btn[data-feedback]` 마크업. 클릭 처리는 `chat.html`에 `#chat-messages` 위임(delegated) 리스너 **하나**로 통일 — 3개 렌더 경로(서버 렌더/HTMX swap/JS로 직접 append되는 스트리밍 버블) 모두 같은 컨테이너 안에서 발생하므로 개별 스크립트 중복 없이 한 곳에서 처리.
> - **검증**: `SqliteMemoryRepositoryTest` +5(생성 id 반환, DISLIKE 제외, LIKE 유지, 소유권 검증, 갱신 반영), `OperationsControllerHtmxTest` +4(LIKE/해제/404/400). 전체 335 tests BUILD SUCCESS(회귀 0). LM Studio 연동 실사용 확인: Direct 모드로 스트리밍 응답 생성 → 👎 클릭(DB `feedback=DISLIKE` 반영, 감사로그 `from:NONE,to:DISLIKE`) → 페이지 새로고침 후 서버 렌더 경로에서도 빨간 버튼 상태 유지 → 👍 클릭으로 전환(감사로그 `from:DISLIKE,to:LIKE`) 확인.
> - ⚠️ **범위 밖(비적용)**: LIKE는 저장만 되고 현재 어떤 로직도 소비하지 않음(설계대로 "향후 활용"). DUAL 모드는 `result.answer()`(외부 답변) 기준으로 turn 1개만 저장되므로 로컬 답변에는 별도 피드백이 없음(기존 turn 저장 정책과 동일 — 신규 결함 아님).

### 6.10 입력 시작 시 로컬 요약 선계산 + 중복 제거 컨텍스트 압축 🔵 계획

> **현재 코드 확인 (2026-07-02)**: `ConversationSummarizerService` 미존재. `AgentService.chat()`는 이미 진입 시 `memoryService.getHistory()`를 `CompletableFuture`로 **classify와 병렬 프리페치**(현 `AgentService.java:48,54`)하므로 선계산 트리거를 얹을 자연스러운 지점이 있다. `getHistory()`는 단일 String 반환 → 요약 결과도 String이라 프롬프트 조립부 교체가 단순. `LlmRouter.route(TaskType.LIGHT_TEXT|…, RoutingMode.LOCAL_ONLY)`로 로컬 요약 라우팅 가능.

**목표**:
- 사용자가 질문 입력을 시작하면 이전 대화를 중복 제거 + 요약해 미리 준비한다.
- 실제 질문 전송 시 준비된 요약을 프롬프트에 포함해 토큰 효율과 응답 품질을 개선한다.

**핵심 아이디어**:
- 요약 생성 시점: `질문 입력 시작(on input)` 이벤트에서 비동기 트리거.
- 요약 실행 주체: `LOCAL` provider(또는 local-only task type) 우선.
- 질문 전송 시점에는 요약 재사용(없거나 오래됐으면 기존 경로 fallback).

**설계(단계적)**:
1. **요약 파이프라인 추가**
  - `ConversationSummarizerService` 신설: thread history -> dedupe -> summary.
  - dedupe는 경량 규칙 기반(정규화 후 exact/near-exact 제거) + 핵심 슬롯(결정/제약/오류코드/버전) 보존.
2. **캐시/저장 전략**
  - 스레드별 요약 캐시(`threadId`, `summaryText`, `sourceTurnSeq`, `updatedAt`).
  - 히스토리 변경(새 turn 저장) 시 캐시 무효화.
3. **프론트 트리거**
  - `chat.html` 입력창 첫 입력 시 `POST /ui/chat/summary/precompute?threadId=...` 비동기 호출(디바운스 적용).
  - 사용자 타이핑 중 중복 호출 방지(예: 10~20초 TTL).
4. **질문 처리 통합**
  - `AgentService.chat()` 시작 시 "요약 사용 가능"이면 요약 + 최근 N턴(짧은 raw) 결합.
  - 요약 없음/실패/타임아웃이면 기존 히스토리 경로로 자동 폴백.
5. **프롬프트 정책**
  - 요약 프롬프트에 "중복 제거, 사실/결정 우선, 금지사항 보존, 추측 금지" 명시.
  - 최종 질문 프롬프트에는 `Conversation Summary` 블록을 별도 섹션으로 삽입.

**운영/안전장치**:
- 요약 호출 타임아웃 짧게(예: 5~10초) 설정해 UX 지연 방지.
- local provider unavailable 시 즉시 포기하고 본질 경로 유지(서비스 연속성 우선).
- 요약 길이 상한(예: 1~2k chars)으로 비용 상한 고정.
- 새 대화 버튼을 누르거나 이전 대화로 변경 할 수 있으므로 요약 캐시를 threadId 기준으로 관리하고, threadId 변경 시 메모리에 임시 보관 (최대 3개). 

**검증 계획**:
- 단위: dedupe 규칙, 요약 캐시 무효화, fallback 경로.
- 통합: 입력 시작 시 선계산 트리거, 질문 전송 시 요약 재사용 여부.
- 회귀: 요약 실패 시 기존 답변 품질/지연 악화 없음.

**완료 기준**:
- 사용자가 입력을 시작하면 백그라운드 요약이 선계산된다.
- 질문 전송 시 요약이 프롬프트에 포함되어 전달된다.
- 중복 대화가 요약에서 제거되고 핵심 맥락은 유지된다.
- 로컬 요약 실패 시 기존 대화 경로로 안전하게 폴백한다.

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

### Step 5.1 — VectorStoreProvider 추상화 계층 도입 ✅ 완료

Chroma 결합을 `VectorStoreProvider`(search/searchBatch/add/deleteByDocIds) 인터페이스 뒤로 이전한 **동작 변화 없는 순수 리팩토링**. `VectorStoreFacade`는 provider만 주입받고 SAFE_VERSION 검증은 facade에 유지. 호출부(`RetrievalService`·`DocumentIndexer`) 시그니처 불변.

### Step 5.2 — sqlite-vec 네이티브 확장 로딩 (운영자 제공 경로) ✅ 완료

새 의존성 0 — xerial sqlite-jdbc의 `enable_load_extension`+`load_extension()`으로 운영자 제공 `vec0` 바이너리를 로드(`DataSourceConfig`, sqlite-vec 모드에서만, 작은따옴표 차단). `SqliteVecVerifier`가 기동 시 `vec_version()`로 확인·fail-fast. ※ 공식 Maven fat-jar는 존재하지 않음(확인됨). 엔트리포인트는 보통 불필요.

### Step 5.3 — sqlite-vec 스키마 초기화 (동적 DDL) ✅ 완료

`vec0` 차원이 DDL 상수라 Flyway 대신 시작 시 `IF NOT EXISTS` 동적 DDL 실행. 벡터(`vec_embeddings`)와 텍스트·메타(`vec_document_chunks`, `user_scope` 기본 `'shared'`)를 분리해 `spring_doc_id`로 JOIN. `app.embedding.dimensions` 미설정/0/음수 시 fail-fast(DDL 미실행).

### Step 5.4 — SqliteVecVectorStoreProvider 구현 ✅ 완료

`VectorStoreProvider`의 sqlite-vec 구현체. 벡터는 JSON 텍스트 리터럴(`[v0,v1,...]`)로 `?` 바인딩, version은 vec0 partition key로 KNN 내부 필터(`WHERE embedding MATCH ? AND k=? AND version=?` 한 쿼리로 정확히 topK), cosine 거리→`1-distance` 유사도(Chroma 경로와 동일). add 멱등은 vec0가 `INSERT OR REPLACE` 미지원이라 DELETE 후 INSERT, delete는 두 테이블 동시 삭제. searchBatch는 임베딩 1회 배치 + N 루프 쿼리(SQLite 인메모리라 수 ms).

### Step 5.5 — 백엔드 선택 스위치 (조건부 빈 등록) ✅ 완료

`VectorStoreProviderConfig`가 `@ConditionalOnProperty(app.vectorstore.type)`로 provider 택일(chroma `matchIfMissing=true`). Chroma 전용 빈(`ChromaConfig`/`VectorStoreRegistry`/`ChromaHealthChecker`/`VectorStoreWarmup`) 가드. ⚠️ Plan이 누락했던 `AdminService`는 `Optional<ChromaApi>`로 변경해 sqlite-vec 모드에서 `/admin` chunk 브라우징을 우아하게 강등(당시 미지원 → **Step 5.8에서 두 백엔드 모두 브라우징 지원으로 보강**). 두 모드 모두 `VectorStoreProvider` 빈 정확히 1개.

### Step 5.6 — 설정 외부화 (.env / docker-compose) ✅ 완료

`chroma` 서비스를 compose `profiles: ["chroma"]`로 분리. ⚠️ `app`의 `depends_on`에 **`required: false`**(Compose 2.20.2+)를 더해 sqlite-vec 모드(`docker compose up`)에서 무-Chroma 기동. `VECTORSTORE_TYPE`/`SQLITE_VEC_*` env 외부화(Step 5.2에서 추가), OPERATOR_MANUAL §3.1에 두 모드 운영법 반영.

### Step 5.7 — 데이터 이전 및 통합 검증 ✅ 완료

이전 경로 = **재인덱싱**(`data/documents/` 원본 보존이라 무손실): `VECTORSTORE_TYPE=sqlite-vec` 재시작 → `/admin` 전체 재동기화. `SqliteVecIntegrationTest`(실 vec0 v0.1.9, 바이너리 없으면 skip)로 add→search→searchBatch→delete E2E + 무-Chroma 컨텍스트 로드 검증. ※ docker 무설치 환경이라 `docker compose up` 실측·운영 데이터 정성 비교는 운영 인수.

### Step 5.8 — 관리자 페이지 백엔드 가시성 보강 (sqlite-vec) ✅ 완료

기존 `/admin`은 `AdminService`가 `ChromaApi`에만 의존해 sqlite-vec 모드에서 상태·청크 브라우징이 빈 목록으로 강등됐다. 두 백엔드 공통으로 보강:

- **상태 카드**: `VectorStoreAdminView`(record) 신규. `AdminService`에 `JdbcTemplate`·`AppProperties`·`ObjectMapper` 주입 + `vectorStoreView()` — 활성 백엔드를 `props.vectorStoreSafe().type()`로 판별(chroma=컬렉션 집계·문서수 unknown(-1) / sqlite-vec=`vec_document_chunks` COUNT·DISTINCT doc_id·`GROUP BY version`·`vec_version()`·차원). `AdminController.adminPage`에 `vectorStore` 모델 속성, `admin.html`에 "Vector Store 상태" 카드(공통), Chroma 불가 배너는 `isChroma()` 가드.
- **청크 브라우징 패리티**: `listCollections`/`getChunks`/`countChunks`/`getChunk`/`deleteChunk`/`updateChunk`를 `isSqliteVec()` 분기로 확장 — sqlite-vec에선 "collection"=version으로 해석해 `vec_document_chunks`(content·metadata JSON)를 조회. 삭제는 `vec_document_chunks`+`vec_embeddings` 두 테이블 동기, 수정은 content/metadata만(벡터 보존, Chroma와 동일 정책). `admin.html` 좌측 패널 백엔드 공통화(라벨·collection 식별자 `IS_SQLITE_VEC` 분기).
- **검증**: `AdminServiceTest` 7건(백엔드별 집계·`listCollections`·두 테이블 삭제) + `AdminControllerWebMvcTest` 2건(`/admin` chroma·sqlite-vec 실제 렌더 — 컨트롤러 배선 + Thymeleaf 조건부 회귀 보호). 전체 299 tests BUILD SUCCESS(회귀 0, sqlite 통합 2건 skip).

> ⚠️ sqlite-vec엔 단일 "active version" 개념이 없어(버전 = vec0 partition key) 상태를 버전별 청크 수로 표현. `vec_document_chunks`/`vec_version()`은 sqlite-vec 모드에서만 존재하므로 sqlite-vec 쿼리는 백엔드 분기 안에서만 실행(+ try/catch 안전 강등).

### Step 5.9 — 태그 기반 검색 스코프 (엄격 필터 + sqlite 후보확대 보정) ✅ 완료

**목표**: 문서 등록 시 다중 태그를 저장하고, 채팅 시 선택한 태그에 해당하는 문서만 검색 대상에 포함한다. 기본 원칙은 **엄격 필터(strict filter)**이며, 벡터 검색 + 하이브리드 키워드 검색(BM25) 모두 동일한 태그 조건을 적용한다. sqlite-vec은 결과 부족을 막기 위해 **후보확대 보정(candidate expansion)**을 함께 적용한다.

**적용 범위**:
- 업로드 UI/API: 다중 태그 입력/검증/저장
- 채팅 UI/API: 다중 태그 선택 전달
- 인덱싱: 청크 메타데이터에 태그 저장
- 검색: Chroma/sqlite-vec + 하이브리드(BM25) 모두 태그 엄격 필터 적용
- 운영: 프리릴리즈 기준 데이터 마이그레이션 없이 수동 초기화 후 재구성

**전제 (프리릴리즈 데이터 정책)**:
- 정식 릴리즈 전이므로 DB/레지스트리 마이그레이션 스크립트는 작성하지 않는다.
- 기존 데이터는 수동 삭제 후 신규 인덱싱으로 전환한다.
- 초기화 대상: `data/memory.db`(+ `data/memory.db-wal`, `data/memory.db-shm`), `data/documents/`, `data/converted/`, `data/images/`, `data/chroma/`(chroma 사용 시), `data/audit/`(선택).
- 초기화 후 `/setup`(no-auth) 또는 관리자 계정 생성 → 문서 재업로드/재동기화.

**설계 결정**:
- 태그 매칭 기본: `AND` (선택 태그 모두 포함된 청크만 허용)
- 옵션: `OR` 모드(설정/파라미터) 추가 가능하되 1차는 `AND` 고정으로 출시
- 태그 미선택 시 기존과 동일하게 version-only 검색
- 태그 정규화: 소문자, trim, 중복 제거, 공백 태그 제거, 최대 10개/태그당 32자
- 후보확대 책임: `RetrievalService` 단일 레이어에서 `candidateK`를 계산/확대하고, provider는 전달된 `candidateK` 범위 내 조회만 수행
- **태그 필터 레이어**: `VectorStoreProvider`/`VectorStoreFacade`/`RagService` 인터페이스는 **변경하지 않는다**. Spring AI `FilterExpressionBuilder`는 배열 포함(containment) 연산자 미제공, vec0 KNN은 파티션 키(version)만 지원 — 두 백엔드 모두 provider 레이어에서 태그 push-down이 불가하다. 태그는 검색 결과 metadata에 이미 포함돼 반환되므로 `RetrievalService.execute()` 내 `mergeRrf()` 직후·최종 cut 직전에 Java post-filter를 적용한다.
- **메타데이터 타입 방어**: `image_paths`와 동일하게, Chroma는 `List<String>` metadata를 버전에 따라 `String` 또는 `Collection<?>` 중 하나로 반환한다. `RetrievalService`에 `parseTagList(Object raw)` 유틸리티 메서드(String/Collection/null 방어 처리)를 추가하고 필터 로직 전반에서 사용한다.
- **후보확대 전략**: 태그 선택 시 **선제 확대(fetch-more-upfront)** — 임베딩은 provider 내부에서 계산되므로 "provider 재호출"은 LLM API 재호출을 의미한다. 재호출 없이 처음부터 더 큰 `candidateK`로 요청하고, 그래도 topK 미달이면 가능한 결과만 반환한다. 새 속성 `app.search-tag-candidate-multiplier`(기본값 2)로 배수 제어.

**세부 작업**:
1. 도메인/모델 확장
  - `MetaKey`에 `TAGS` 상수 추가.
  - `ChatForm`, `ChatRequest`, `AgentState`에 `selectedTags` 필드 추가.
  - 인덱싱 요청 모델(`IndexRequest`)에 `tags` 추가.
  - 인덱싱 경로 시그니처 확장: `RagService.indexDocument()`에 `List<String> tags` 추가 → `IndexRequest.single()` factory method에 tags 전달.
  - ⚠️ `VectorStoreProvider`/`VectorStoreFacade`/`RagService` search 시그니처 및 `KeywordSearchRepository.search()` 시그니처는 **변경하지 않는다**(태그 필터는 RetrievalService post-filter로 단일화 — 설계 결정 참조).

2. 문서 업로드(다중 태그 입력)
  - `documents.html` 업로드 카드에 태그 입력 추가(쉼표 구분 또는 chips).
  - `/ui/documents/upload`, `/api/v1/documents`에 `tags` 파라미터 추가.
  - 서버측 검증 실패 시 400(형식 오류)/422(정책 위반)로 일관 처리.

3. 인덱싱 메타데이터 저장
  - `DocumentIndexer.tagMetadata(...)`에서 청크 metadata에 `tags` 저장.
  - Chroma/sqlite-vec 모두 동일한 metadata 구조를 사용(backend-neutral).
  - `DocRegistry`는 마이그레이션 없이 유지(태그는 벡터 청크 metadata 기준).

4. 채팅 태그 선택 UI/전달
  - `chat.html` 입력 바에 태그 선택 UI 추가.
  - 선택 태그를 hidden input으로 `/ui/chat`, `/ui/chat/stream`, `/api/v1/chat`에 전달.
  - 스레드 단위 마지막 선택값 유지 여부는 1차에서 비적용(요청 단위 처리).

5. 검색 레이어 적용 (엄격 필터 — Java post-filter)
  - `RetrievalService.execute(state)`: `state.selectedTags()`를 읽어 `mergeRrf()` 직후·최종 cut 직전에 `parseTagList()` 기반 AND 필터 적용. provider/facade/ragService 시그니처는 변경하지 않는다.
  - Chroma: 검색 결과 metadata에 tags가 반환됨(`List<String>` 또는 `String` — `parseTagList()`로 방어 처리). Java post-filter.
  - sqlite-vec: version partition KNN 후 JOIN된 `vec_document_chunks.metadata` JSON에 tags 포함됨. Java post-filter.
  - 하이브리드(BM25): `KeywordSearchRepository`의 FTS5 스키마에 `doc_tags UNINDEXED` 컬럼 추가(프리릴리즈 리셋으로 스키마 재생성). `indexChunks()`에서 쉼표 결합 문자열로 저장, `search()` 반환 metadata에 `MetaKey.TAGS` 포함 → RetrievalService에서 동일 `parseTagList()` 필터 적용(SQL-level WHERE 불필요).
  - `RetrievalService.execute()` catch 블록 fallback(`ragService.search()` 직접 경로)에도 동일 post-filter 적용(누락 시 fallback에서 태그 우회 발생).

6. 후보확대 보정 (전 백엔드 공통)
  - `RetrievalService`에서 `selectedTags`가 비어있지 않으면 `candidateK` 계산 직후 `app.search-tag-candidate-multiplier`(기본값 2) 적용: `candidateK = max(candidateK, defaultTopK * tagCandidateMultiplier)`. provider 호출은 이 확대된 값으로 **한 번만** 수행한다(선제 확대, 재호출 없음).
  - provider 내부에 별도 재조회 루프를 구현하지 않는다(이중 확대 방지). Chroma·sqlite-vec 모두 동일 규칙.
  - 후보 확대 후에도 post-filter 결과가 topK 미달이면 가능한 결과만 반환(무필터 폴백 금지).
  - 로그에 `selectedTags 수`, `candidateK`, `post-filter 후 결과 수`를 남겨 운영 튜닝 근거 확보.

7. 테스트
  - 단위: 태그 정규화/검증, AND 필터 일치, 빈 태그 경로 회귀.
  - `parseTagList()` 단위: `String`, `Collection<String>`, `null`, JSON 배열 문자열 입력 모두 방어 처리 검증.
  - `RetrievalService` catch 블록 fallback 경로에서 태그 필터가 적용됨을 검증.
  - 백엔드별 통합: Chroma/sqlite-vec에서 태그 미선택/선택 시 검색 결과 검증.
  - 하이브리드 통합: BM25(`doc_tags` 컬럼 포함 FTS5)에서 태그 미선택/선택 시 동일 필터 의미 검증.
  - 웹 계층: 업로드/채팅 폼 파라미터 바인딩 + 유효성 실패 코드 검증.
  - 회귀: 기존 version-only 검색, rerank/hybrid on/off, `selectedTags=[]` 시 동작 불변 확인.

8. 운영 절차 문서화 (프리릴리즈)
  - `OPERATOR_MANUAL.md`에 "Step 5.9 적용 전 수동 초기화 절차" 추가.
  - "초기화 → 재기동 → 재업로드/동기화 → 태그 필터 검증" 체크리스트 포함.
  - 데이터 보존 미보장(프리릴리즈) 고지 문구 명시.

**완료 기준**:
- [x] 문서 업로드에서 다중 태그를 저장할 수 있다. (`/ui/documents/upload`·`/api/v1/documents` `tags` 파라미터 → 청크 metadata `tags`)
- [x] 채팅에서 태그 선택 시 선택 태그 문서만 검색 근거로 사용된다(엄격 AND 필터).
- [x] 하이브리드(BM25) 축에서도 태그 엄격 필터가 적용된다. (`chunk_fts.doc_tags` 동행 → 동일 post-filter)
- [x] sqlite-vec에서 태그 필터로 인한 결과 부족 시 후보확대 보정이 동작한다. (전 백엔드 공통 `app.search-tag-candidate-multiplier`)
- [x] 태그 미선택 요청은 기존 결과와 의미적으로 동일하다. (`selectedTags=[]` pass-through, 회귀 테스트)
- [~] Chroma/sqlite-vec 두 백엔드에서 테스트가 통과한다. — 필터는 RetrievalService post-filter라 **백엔드 불가지론**(반환 Document metadata 기준)이며 단위 테스트로 검증. 라이브 백엔드 통합(Chroma 서버/vec0 바이너리)은 운영 인수.
- [x] 마이그레이션 없이 수동 초기화 절차가 운영 문서에 반영되어 있다. (OPERATOR_MANUAL §4.6)

> **구현 메모 (2026-06-30)**:
> - **모델/상태**: `MetaKey.TAGS`, `TagUtils`(정규화·검증·`parseTagList` 방어·AND 매칭) 신규. `ChatForm.tags`/`ChatRequest.selectedTags`/`AgentState.selectedTags`/`IndexRequest.tags` 추가. `VectorStoreProvider`/`Facade`/`RagService.search*`/`KeywordSearchRepository.search()` **시그니처 불변**(설계대로).
> - **인덱싱**: `DocumentIndexer.tagMetadata`가 청크 metadata에 `tags`(쉼표 결합, image_paths 컨벤션) 저장. 두 백엔드 공통 metadata.
> - **검색**: `RetrievalService.execute()`가 `mergeRrf()` 직후·cut 직전에 `filterByTags()`(AND, `TagUtils.parseTagList` 방어) 적용. catch fallback 경로도 동일 필터. 태그 선택 시 `candidateK = max(candidateK, topK × tagMultiplier)` 선제 확대(재호출 없음). BM25는 `doc_tags UNINDEXED` 컬럼으로 태그를 결과 metadata에 동행시켜 동일 post-filter 적용.
> - **UI/검증**: `documents.html`·`chat.html` 태그 입력(+i18n), 업로드 FormData·채팅 폼 전달. 업로드는 `TagUtils.parseCsv` 정책 검증(위반 시 400). 단위 테스트 `TagUtilsTest`(7) + `RetrievalServiceTagFilterTest`(4). 전체 310 tests BUILD SUCCESS(회귀 0, sqlite 통합 2 skip).
> - ⚠️ **정책 코드 단일화**: 원안은 형식 400/정책 422 구분이었으나 `IllegalArgumentException`(GlobalExceptionHandler→400)으로 통일. 422 세분화는 후속.
> - ⚠️ **프리릴리즈**: `chunk_fts`에 `doc_tags` 컬럼 추가 = FTS5 스키마 변경. 기존 DB는 마이그레이션 없이 수동 초기화 후 재인덱싱(OPERATOR_MANUAL §4.6).

**후속 — 재인덱싱 태그 복원 ✅ 완료**:
- 문제: 재인덱싱(↺, `reindexFromMd`)·디렉터리 동기화 갱신(`syncDirectory`→`index(parallel)`) 경로는 태그 입력 UI가 없어 `tagMetadata(..., List.of())`로 **빈 태그**를 넘겨, 재동기화 시 기존 태그가 소실(칩·검색 스코프 붕괴)됐다. 데이터 유실성 회귀로 판단해 후속이 아닌 결함으로 처리.
- 수정: `DocumentIndexer.restoreTags(priorDocId)` 신규 — 태그는 `chunk_fts.doc_tags`에 이미 있으므로 `KeywordSearchRepository.tagsByDocIds`로 복원(FTS 불가/무이력 시 빈 리스트, no-throw). 삭제로 FTS 행이 지워지기 **전에** 읽는다.
  - `index()`: `req.tags()`가 비고 **동기화/병렬 경로(`parallelGate != null`)일 때만** 복원(소스 = `staleDocId` 우선, 없으면 `docId`). 대화형 single 업로드는 명시적 태그를 존중 — 빈 태그는 "의도적 clear"로 보고 복원하지 않음.
  - `reindexFromMd()`: 동일 `docId`에서 복원.
- 테스트: `DocumentIndexerTest` +2(동기화 갱신 시 staleDocId→신규 docId 태그 복원 / single 재업로드는 자동복원 안 함). 전체 313 tests BUILD SUCCESS.

**후속 — 태그 제안 UI ✅ 완료**:
- 등록된 태그를 칩으로 노출해 클릭 선택. **소스 = `chunk_fts.doc_tags`**(매 인덱싱마다 채워지고 hybrid 설정·백엔드와 무관) → `KeywordSearchRepository.distinctTags(version)`(정렬·중복 제거, 버전 선택 스코프) → `RagService.listTags` → `GET /api/v1/tags?version=`.
- 업로드(`documents.html`): 전체 태그 칩 → 클릭 시 입력칸에 추가(업로드 후 갱신). 채팅(`chat.html`): **버전 스코프** 태그 칩 → 클릭 토글로 검색 범위 좁힘(버전 변경 시 재로딩). 입력값↔칩 활성 상태 동기화.
- 테스트: `KeywordSearchRepositoryTest.distinctTags`(실 FTS5).

### Step 5.10 — sqlite-vec 운영 DB 분리(최소 변경 설계안) ✅ 완료

**배경**:
- 현재 sqlite-vec 모드에서도 운영 데이터와 벡터/FTS 데이터가 동일 `memory.db`를 공유한다.
- 인덱싱(write-heavy)과 운영성 트랜잭션(대화/사용량/인증)이 같은 WAL/체크포인트 경로에서 경합해, 락 대기·지연·복구 범위가 함께 커진다.

**목표**:
- 운영 DB(`memory.db`)와 벡터 DB(`vector.db`)를 분리해 장애 격리·성능 간섭 완화·백업/복구 유연성을 확보한다.
- Chroma 경로에는 영향 없이, sqlite-vec 경로에만 최소한의 코드 변경으로 적용한다.

**설계 원칙(최소 변경)**:
1. 분리 대상은 sqlite-vec 관련 테이블(`vec_embeddings`, `vec_document_chunks`, `chunk_fts`)로 한정한다.
2. 운영성 테이블(대화/인증/사용량/메타)은 기존 `memory.db` 유지한다.
3. 서비스/도메인 시그니처는 유지하고, 주입 계층(DataSource/JdbcTemplate)만 분기한다.
4. 실패 시 즉시 원복 가능한 feature switch를 둔다.
5. **쓰기 순서 고정(파일 간 원자성 부재 대응)**: 인덱싱은 항상 *벡터/FTS(vector.db) 먼저 → `DocRegistry.save()`(memory.db) 마지막* 순서로 커밋한다. 크래시 시 부분 실패가 "재인덱싱이 덮어쓸 고아 벡터"로 남게 하고, "레지스트리는 성공인데 벡터 없음"(=검색 시 조용한 빈 결과)은 만들지 않는다. ※ 현재 `index()`/`reindexFromMd()`가 이미 vectorStore.add → keywordRepo.indexChunks → docRegistry.put/save 순서이므로 **순서 재배치 불필요**, DB 분리 후에도 이 순서를 회귀 없이 유지하는 것이 핵심.

**구현 범위**:
1. **설정 추가**
  - `app.vectorstore.sqlite-vec.db-path` (기본: `${DATA_DIR}/vector.db`)
  - `.env.example.sqlite`에 `SQLITE_VEC_DB_PATH` 추가
2. **빈 분리**
  - `@Primary` 운영 `DataSource`/`JdbcTemplate`는 기존 `memory.db` 유지
  - sqlite-vec 전용 `DataSource`/`JdbcTemplate`를 별도 이름으로 추가(`vectorDataSource`, `vectorJdbcTemplate`)
  - sqlite-vec extension load(`load_extension`)는 전용 DataSource에만 적용
  - ⚠️ **PRAGMA/풀 설정 복제 필수**: 전용 `vectorDataSource`도 운영 DataSource와 동일하게 **pool=1(CLAUDE.md 제약) + WAL + `busy_timeout`**를 복제한다. `DataSourceConfig.configureSqliteVec()`가 이미 `connectionInitSql`로 `load_extension`을 걸므로, 벡터 DataSource에는 여기에 WAL/busy_timeout PRAGMA까지 함께 적용(단일 statement 제약에 주의 — 필요 시 `connection-init-sql` 대신 URL 파라미터/별도 초기화). 운영 DataSource에서 sqlite-vec 확장 로딩은 **제거**(더 이상 memory.db에 vec0 불필요).
3. **주입 전환(최소 세트)**
  - `SqliteVecSchemaInitializer`, `SqliteVecVerifier`, `SqliteVecVectorStoreProvider`를 `vectorJdbcTemplate`로 전환. 이들은 이미 sqlite-vec 조건부 빈이라 chroma 모드엔 존재하지 않음 → 무영향.
  - ⚠️ **`KeywordSearchRepository`의 이중 백엔드 처리(핵심 갭)**: `chunk_fts`(하이브리드 BM25 + Step 5.9 태그 제안/복원)는 **두 백엔드 모두** 사용한다. chroma 모드엔 `vector.db`/`vectorJdbcTemplate`가 없으므로 "항상 vectorJdbcTemplate 주입"은 성립하지 않는다. 해결: **`vectorJdbcTemplate` 빈을 두 모드 모두에서 정의**하되, sqlite-vec 모드 → `vector.db`, chroma 모드 → 운영 `memory.db`(사실상 `@Primary` 별칭)를 가리키게 한다. `KeywordSearchRepository`는 `@Qualifier("vectorJdbcTemplate")`로 고정 주입 → chroma 모드에선 `chunk_fts`가 그대로 `memory.db`에 남아 회귀 0, sqlite-vec 모드에선 `vector.db`로 이동.
  - 운영성 Repository/Service는 기존 `@Primary JdbcTemplate` 유지.
4. **운영 가드**
  - sqlite-vec 모드에서 `vector.db` 경로 미설정/생성 실패 시 fail-fast
  - `/admin` 상태 카드에 운영 DB/벡터 DB 경로를 분리 표기(오인 방지)
  - 스키마 초기화 시 memory.db에 남은 구(舊) `vec_*`/`chunk_fts` 잔존 테이블은 프리릴리즈 수동 초기화로 정리(자동 이관 없음).

**장점**:
- 인덱싱 I/O와 운영 트랜잭션 분리로 락 경합 및 지연 전파 감소
- 백업 정책 분리(운영 DB 고주기, 벡터 DB 저주기/재생성 전제)
- 벡터 DB 재구축/초기화 시 운영 데이터 영향 최소화

**트레이드오프/주의점**:
- DB 파일 2개(+ `-wal`, `-shm`) 운영 복잡도 증가
- 파일 간 트랜잭션 원자성은 보장되지 않음 → **보상 로직 대신 쓰기 순서(설계 원칙 5)로 대응**: 벡터/FTS 먼저 커밋, 레지스트리 마지막. 고아 벡터는 재인덱싱이 덮어쓰므로 허용, 역방향(레지스트리 성공+벡터 없음)은 금지.
- 백업/복구 시 두 파일의 정합성 시점 불일치 가능 → 런북에 백업 순서(벡터 DB 먼저 스냅샷 후 운영 DB) 및 복구 후 재인덱싱 옵션 명시.
- 배포/복구 런북 업데이트 필수(백업 순서, 점검 포인트)

**단계적 적용 순서(권장)**:
1. 설정/빈 추가 + 기존 경로 유지(기본 동작 불변)
2. sqlite-vec 구성요소만 `vectorJdbcTemplate`로 전환
3. 통합 테스트(인덱싱/검색/삭제/태그 제안) + 부하 점검
4. 운영 인수 시 `vector.db` 분리 활성화

**완료 기준**:
- sqlite-vec 모드에서 `memory.db`는 운영 테이블만, `vector.db`는 벡터/FTS 테이블만 보유
- 기존 API/화면 동작 회귀 0(채팅/문서관리/태그 제안/태그 복원)
- **chroma 모드에서 `chunk_fts`가 `memory.db`에 그대로 남고 하이브리드/태그 제안이 회귀 0**(`vectorJdbcTemplate` = 운영 템플릿 별칭)
- 벡터 DB 삭제/재생성 후 운영 데이터 보존 + 재인덱싱으로 복구 가능(쓰기 순서 원칙으로 "레지스트리 성공+벡터 없음" 상태 미발생)
- 전용 `vectorDataSource`가 pool=1/WAL/busy_timeout을 복제하고, 운영 DataSource에서 vec0 로딩이 제거됨
- `VECTORSTORE_TYPE=chroma` 경로 무영향

> **구현 메모 (2026-07-01)**:
> - **feature switch**: `app.vectorstore.sqlite-vec.db-path`(`SQLITE_VEC_DB_PATH`). 빈값(기본) → 기존과 동일(벡터/FTS 테이블이 memory.db). 값 지정(+ `type=sqlite-vec`) → 분리 활성. 즉시 원복 = 값 비우기. 원안은 기본 `${DATA_DIR}/vector.db`였으나 "기본 동작 불변 + opt-in 롤아웃" 원칙에 맞춰 **기본 빈값**으로 조정.
> - **DataSourceConfig**: `dataSource()`에 `@Primary` + poolName `memory-db`, 분리 시 memory.db에서 vec0 로딩 제거. `vectorDataSource`(분리 시에만, `@ConditionalOnExpression`) = vector.db + pool=1 + vec0. `vectorJdbcTemplate` 빈은 **두 모드 모두 정의**(분리 시 vector.db / 그 외 memory.db 별칭) — 상호배타 `@ConditionalOnExpression`로 정확히 1개. 커넥션 안 여는 정적 빌더 `buildVectorHikariConfig` 분리(단위 테스트).
> - **주입 전환(`@Qualifier("vectorJdbcTemplate")`)**: `SqliteVecSchemaInitializer`·`SqliteVecVerifier`·`SqliteVecVectorStoreProvider`(via `VectorStoreProviderConfig`)·`KeywordSearchRepository`(chunk_fts) + ⚠️ **`AdminService`(플랜 누락분 — `vec_document_chunks`/`vec_version()` 조회)**. chroma 모드에선 모두 memory.db 별칭이라 회귀 0.
> - **PRAGMA 복제**: `SqliteVecSchemaInitializer.init()`이 DDL 전에 `PRAGMA journal_mode=WAL`+`busy_timeout=5000` 적용(전용 vector.db 커넥션은 `SqliteMemoryRepository`와 별도라 자체 설정 필요). 차원 fail-fast는 PRAGMA/DDL **이전**에 수행(오설정 시 무-실행 계약 유지).
> - **쓰기 순서(원자성)**: 현재 `index()`/`reindexFromMd()`가 이미 벡터→FTS→레지스트리 순서라 재배치 불필요. 분리 후에도 이 순서 유지가 "레지스트리 성공+벡터 없음" 방지의 핵심.
> - **/admin**: `VectorStoreAdminView`에 `operationalDbPath`/`vectorDbPath` + `isDbSeparated()` 추가, `admin.html`에 "운영 DB/벡터 DB" 경로·"분리됨" 배지 표기(오인 방지).
> - **검증**: `DataSourceConfigTest` +3(정적 빌더 pool=1/vec0 로드 / 기본 모드 `vectorJdbcTemplate`=운영 별칭·`vectorDataSource` 부재 회귀 가드 / **실 다운스트림 소비자 `KeywordSearchRepository`가 `@Qualifier` 배선으로 memory.db에 chunk_fts 생성** — 기본 경로 E2E). 컨텍스트 러너 기반 3개 sqlite-vec 테스트는 신규 빈 이름(`vectorJdbcTemplate`)으로 갱신. `SqliteVecSeparateDbIntegrationTest` 신규(3, `-Dsqlitevec.path` 게이트) — **벡터/FTS 테이블이 vector.db에만 있고 memory.db엔 없음**(물리적 분리)·전용 DataSource 배선·add→search→delete E2E. 전체 **319 tests BUILD SUCCESS**(회귀 0, vec0 라이브 통합 5 skip).
> - ⚠️ **운영 인수**: 실제 vec0 바이너리로 분리 부팅(`SqliteVecSeparateDbIntegrationTest`가 2파일 생성·물리 분리·E2E를 자동 검증하지만 vec0 미보유 환경에선 skip)·WAL 확인·백업 순서 런북은 운영 인수 대상.
> - ⚠️ **알려진 한계(비회귀)**: `busy_timeout`은 커넥션 단위 설정이라 Hikari 커넥션 재생성(기본 maxLifetime) 시 초기화됨 — pool=1·단일 라이터라 영향 미미하고 memory.db의 기존 동작과 동일. WAL은 파일 단위라 재생성과 무관하게 유지.

**범위 제외 (후속)**:
- 자유 입력 자동완성(typeahead) — 현재는 칩 선택만
- 문서별 태그 수정 UI(인덱싱 후 편집)
- AND/OR 사용자 전환 토글
- 기존 데이터 자동 마이그레이션

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
