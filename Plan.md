# RAG-Agent 온라인 확장 개발 계획

> Java 개발자 관점 · Spring Boot 3.5 + Spring AI 1.1.4 + Java 21 · 작성일 2026-05-11  
> **업데이트**: 2026-06-23 — Phase 5 sqlite-vec 연동을 단계별 작업(Step 5.1~5.7)으로 분해  
> **개발 기준 문서**: 이 파일(Plan.md)이 마스터. `documents/refactoring/18-extension-roadmap.md`는 각 항목의 기술 레퍼런스.

---

## ⚡ 현재 진행 상황 (2026-06-14 기준)

### ✅ Phase 1 전체 완료

| 완료 항목 | 완료일 |
|---|---|
| Step 1.1 — Caddy 리버스 프록시 + TLS | 2026-05-17 |
| Step 1.2 — Flyway 마이그레이션 도입 | 2026-05-17 |
| Step 1.3 — Spring Security 폼 로그인/회원가입 | 2026-05-18 |
| Step 1.4 — 멀티유저 데이터 격리 (SQLite `user_id` + Chroma 컬렉션) | 2026-05-18 |
| Step 1.5 — CSRF + HTMX fetch 통합 | 2026-05-18 |
| Step 1.6 — 로그인/회원가입 화면 | 2026-05-18 |
| `app.auth.enabled` 토글 — no-auth 모드 (guest/admin 자동 로그인 + 첫 실행 `/setup`) | 2026-05-19 |

### ✅ Phase 3 추가 완료 (2026-05-20 ~ 2026-06-03)

| 완료 항목 | 비고 |
|---|---|
| ChromaDB v2 API 대응 — 컬렉션명 → UUID 자동 변환 | 구버전 Chroma와의 호환 단절 수정 |
| 문서 저장 경로 공유 구조로 단순화 (`DocRegistry.SHARED`) | 멀티유저 격리 대신 공유 저장소 구조로 확정 |
| 인덱싱 SSE 진행 단계별 표시 (파일 타입별 step) | 페이지 복귀 시 진행 중 상태 복원 포함 |
| 키워드 추출 타임아웃 시 CircuitBreaker 오동작 수정 | 타임아웃을 에러로 오인해 프로바이더 차단하던 버그 수정 |
| DOCX 변환 전 구버전 아티팩트 삭제 순서 수정 | 변환 실패 시 구버전 파일이 남아있는 버그 수정 |
| `LOGGING_LEVEL`, `LLM_TEMPERATURE`, `LLM_MAX_TOKENS`, `SPRING_SECURITY_LOGGING_LEVEL` 환경변수 외부화 | `.env.example` + `OPERATOR_MANUAL.md` 반영 |
| 의존성 버전 최신 stable로 일괄 업데이트 | Spring Boot 3.5, Spring AI 1.1.4 등 |

### ✅ 보안 결함 수정 완료 (2026-06-14)

| 항목 | 수정 내용 |
|---|---|
| B-27 — TOCTOU 회원가입 | `DataIntegrityViolationException` catch로 중복 이메일 경쟁 조건 처리 |
| B-28 — 세션 고정 | 회원가입 성공 시 `old.invalidate()` → `getSession(true)` 패턴으로 세션 재발급 |
| B-29 — BCrypt 72바이트 절삭 | `PASSWORD_PATTERN` 상한 `.{10,72}` 추가로 73자+ 비밀번호 등록 차단 |
| B-30 — 헬스체크 DB 조회 | `NoAuthAutoLoginFilter.isPassThrough()`에 `/api/v1/health`, `/actuator/**` 추가 |
| B-31 — ChromaDB 볼륨 경로 | `docker-compose.yml` 볼륨 마운트 `chroma_data:/data` 로 수정 (persist_path 일치) |
| 회귀 테스트 | `AuthControllerTest` 12개, `NoAuthAutoLoginFilterTest` 5개, `ChatResponseNullSafetyTest` +4개 추가 |

### 아직 미착수 (다음 목표)

- **Phase 2**: 모바일 UI (Offcanvas, sticky 입력창, PWA)
- **Phase 3 잔여**: 사용자별 LLM 쿼터 (Phase 3.5), 사용자별 스토리지 쿼터
- **Phase 4**: OAuth2 소셜 로그인, PostgreSQL 마이그레이션 (조건부)
- **Phase 5**: sqlite-vec 선택적 연동 (`app.vectorstore.type=sqlite-vec|chroma`)

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
9. [리스크 및 이슈](#9-리스크-및-이슈)
10. [의존성 변경 사항](#10-의존성-변경-사항-pomxml)
11. [DB 스키마 변경](#11-db-스키마-변경-요약)
12. [최종 체크리스트](#12-최종-체크리스트)
13. [부록 — 결정 사항 한눈에 보기](#부록--결정-사항-한눈에-보기)

---

## 1. 요약 (Executive Summary)

**목표**: 로컬 단일 사용자 RAG 에이전트를 인증·HTTPS·멀티유저 격리·모바일 대응이 갖춰진 온라인 서비스로 확장한다.

**전제**: SQLite를 가능한 한 유지하고, 명확한 한계 신호가 발생할 때만 PostgreSQL로 전환한다.

| Phase | 핵심 산출물 | 우선순위 | 상태 |
|-------|-----------|---------|------|
| Phase 1 — 보안 기반 | Caddy/HTTPS, Spring Security, 멀티유저 격리, Flyway | **필수** | ✅ 완료 |
| Phase 2 — 모바일 UI | Offcanvas, 하단 고정 입력, PWA | **필수** | 🔵 미착수 |
| Phase 3 — 운영 견고화 | Rate limit, 업로드 검증, 감사 로그 | 중요 | 🟡 일부 완료 |
| Phase 4 — 확장 | OAuth2, PostgreSQL 마이그레이션 | 조건부 | 🔵 미착수 |
| Phase 5 — Vector Store 선택 | sqlite-vec / ChromaDB 런타임 선택 | 중요 | 🔵 미착수 |

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

### Step 1.1 — Caddy 리버스 프록시 도입 ✅ 완료 (2026-05-17)

```yaml
# docker-compose.yml 추가
caddy:
  image: caddy:2-alpine
  restart: unless-stopped
  ports: ["80:80", "443:443"]
  volumes:
    - ./Caddyfile:/etc/caddy/Caddyfile:ro
    - caddy_data:/data
    - caddy_config:/config
  depends_on: [app]
```

```
# Caddyfile
your-domain.com {
  reverse_proxy app:8080
  encode gzip zstd
  header {
    Strict-Transport-Security "max-age=31536000; includeSubDomains"
    X-Content-Type-Options "nosniff"
    Referrer-Policy "strict-origin-when-cross-origin"
  }
}
```

**Spring 측 변경**:

```properties
# application.properties
server.forward-headers-strategy=framework
server.tomcat.remoteip.protocol-header=X-Forwarded-Proto
server.servlet.session.cookie.secure=true
server.servlet.session.cookie.http-only=true
server.servlet.session.cookie.same-site=lax
```

### Step 1.2 — Flyway 마이그레이션 도입 ✅ 완료 (2026-05-17)

```xml
<!-- pom.xml -->
<dependency>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-core</artifactId>
</dependency>
```

기존 테이블(`conversation_turns`, `llm_usage` 등)을 `V1__baseline.sql`로 캡처한다. SQLite는 일부 ALTER가 제한적이므로 컬럼 추가는 **"테이블 재생성 + INSERT SELECT"** 패턴 또는 `ALTER TABLE ADD COLUMN`으로 작성한다.

> **SQLite Flyway 주의점**: `flyway-database-sqlite` 별도 모듈 필요 (Flyway 10+). 트랜잭션 내 DDL은 부분적으로만 지원되므로 마이그레이션 1개당 1 DDL 권장.

### Step 1.3 — Spring Security 도입 ✅ 완료 (2026-05-18)

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
  <groupId>org.thymeleaf.extras</groupId>
  <artifactId>thymeleaf-extras-springsecurity6</artifactId>
</dependency>
```

#### SecurityConfig (핵심 구조)

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
      .authorizeHttpRequests(auth -> auth
        .requestMatchers("/login", "/signup", "/css/**", "/js/**", "/api/health").permitAll()
        .anyRequest().authenticated())
      .formLogin(form -> form
        .loginPage("/login")
        .defaultSuccessUrl("/", true))
      .logout(out -> out.logoutSuccessUrl("/login?logout"))
      .sessionManagement(s -> s
        .sessionFixation().migrateSession()
        .maximumSessions(3))
      .headers(h -> h
        .contentSecurityPolicy(csp -> csp.policyDirectives(
          "default-src 'self'; img-src 'self' data:; "
          + "style-src 'self' 'unsafe-inline'; "
          + "script-src 'self' 'unsafe-inline'")));
    return http.build();
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);  // cost=12: ~200ms/해시
  }
}
```

#### users 테이블 (Flyway `V2__users.sql`)

```sql
CREATE TABLE users (
  id            TEXT PRIMARY KEY,           -- UUID
  email         TEXT UNIQUE NOT NULL,
  password_hash TEXT NOT NULL,              -- BCrypt
  display_name  TEXT,
  role          TEXT NOT NULL DEFAULT 'USER',
  enabled       INTEGER NOT NULL DEFAULT 1,
  failed_count  INTEGER NOT NULL DEFAULT 0,
  locked_until  TEXT,
  created_at    TEXT NOT NULL,
  updated_at    TEXT NOT NULL
);
CREATE INDEX idx_users_email ON users(email);

CREATE TABLE persistent_logins (         -- Remember-Me 토큰 DB 저장
  username  TEXT NOT NULL,
  series    TEXT PRIMARY KEY,
  token     TEXT NOT NULL,
  last_used TEXT NOT NULL
);
```

#### UserDetailsService 구현

JdbcTemplate 기반 `SqliteUserDetailsService implements UserDetailsService` 작성. 기존 Repository 패턴과 동일하게 처리.

**BCrypt cost=12 선택 이유**
- 해시당 ~200ms — 무차별 대입 충분히 억제
- Virtual Thread 환경에서 응답 영향 최소

**주의사항**
- 로그인 부하 테스트 필수 (동시 100명 = 동시 100코어 사용 가능)
- SQLite write 락과 만나지 않도록 `failed_count` UPDATE를 비동기로 처리

### Step 1.4 — 멀티유저 데이터 격리 ✅ 완료 (2026-05-18)

#### 스키마 변경 (Flyway `V3__user_scope.sql`)

```sql
ALTER TABLE conversation_turns       ADD COLUMN user_id TEXT;
ALTER TABLE llm_usage                ADD COLUMN user_id TEXT;
ALTER TABLE thread_meta              ADD COLUMN user_id TEXT;
ALTER TABLE image_descriptions       ADD COLUMN user_id TEXT;

CREATE INDEX idx_conv_user_thread ON conversation_turns(user_id, thread_id);
CREATE INDEX idx_usage_user_date  ON llm_usage(user_id, usage_date);
CREATE INDEX idx_thread_user      ON thread_meta(user_id, updated_at);
```

#### CurrentUser 헬퍼

```java
public final class CurrentUser {
  private CurrentUser() {}
  public static String id() {
    Authentication a = SecurityContextHolder.getContext().getAuthentication();
    if (a == null || !a.isAuthenticated())
      throw new AccessDeniedException("not authenticated");
    return ((AppUserDetails) a.getPrincipal()).getId();
  }
}
```

#### Repository 시그니처 강제

```java
// Before
public String getHistory(String threadId, int maxChars) { ... }

// After — userId 누락 시 컴파일 에러
public String getHistory(String userId, String threadId, int maxChars) {
  return jdbc.queryForObject(
    "SELECT ... FROM conversation_turns "
    + "WHERE user_id = ? AND thread_id = ? "
    + "ORDER BY id DESC LIMIT ?", ...);
}
```

#### 파일 저장 경로 격리

```java
// RagService
Path userDir = dataDir.resolve("users").resolve(userId);
Path imagesDir = userDir.resolve("images").resolve(docId);
Path registryFile = userDir.resolve("doc_registry.json");
```

#### Chroma 컬렉션 네이밍

```java
// VectorStoreRegistry
String collectionName = "u_" + userId.replace("-", "") + "_" + version;
// Chroma 컬렉션명 규칙: [a-zA-Z0-9._-], 3~63자 — UUID 앞 6자만 사용해도 충분
```

### Step 1.5 — CSRF + HTMX 통합 ✅ 완료 (2026-05-18)

HTMX는 기본적으로 CSRF 토큰을 자동 전송하지 않는다. `base.html`에 메타 + 글로벌 설정 추가:

```html
<meta name="_csrf" th:content="${_csrf.token}"/>
<meta name="_csrf_header" th:content="${_csrf.headerName}"/>

<script>
document.body.addEventListener('htmx:configRequest', (e) => {
  const token  = document.querySelector('meta[name="_csrf"]').content;
  const header = document.querySelector('meta[name="_csrf_header"]').content;
  e.detail.headers[header] = token;
});
</script>
```

`chat-stream.js`의 `fetch()` 호출도 동일 헤더 추가 필요.

### Step 1.6 — 회원가입/로그인 화면 ✅ 완료 (2026-05-18)

- `/signup`, `/login` Thymeleaf 페이지 (`base.html` 레이아웃 재사용)
- 비밀번호 정책: 최소 10자, 영문+숫자+특수문자 1개씩
- 가입 직후 자동 로그인 (`SecurityContextHolder` 수동 주입)
- 로그인 실패 카운트 → **5회** 초과 시 **15분** 잠금

---

## 5. Phase 2 — 모바일 UI 개선 🔵 미착수

### 5.1 분석 — 현재 모바일 갭

| 페이지 | 현재 문제 | 개선 |
|--------|----------|------|
| chat.html | 사이드바가 좁은 화면에서 본문을 잠식 | Offcanvas drawer + 햄버거 토글 |
| chat 입력창 | 스크롤 시 떠다님 | `position: sticky; bottom: 0` + `100dvh` |
| documents.html | 가로 스크롤 발생 | md 미만에서 카드 레이아웃 전환 |
| llm-usage.html | Chart.js 차트가 넘침 | `maintainAspectRatio: false` + 컨테이너 높이 지정 |
| 입력 폰트 | iOS에서 자동 확대 | 모든 input `font-size: 16px` 이상 |

### 5.2 chat.html Offcanvas 패턴

```html
<!-- 모바일: drawer, 데스크톱: 고정 사이드바 -->
<aside class="thread-list offcanvas-md offcanvas-start" id="threadDrawer">
  <div th:replace="~{fragments/thread-list :: list}"></div>
</aside>

<button class="btn d-md-none" data-bs-toggle="offcanvas"
        data-bs-target="#threadDrawer">☰</button>
```

### 5.3 PWA 적용

- `/manifest.webmanifest` — 앱 이름, 아이콘 (192/512px), `display: standalone`
- 서비스 워커는 **오프라인 fallback 페이지만** 캐시 (RAG 응답 캐시 X — 보안상 위험)
- iOS Safari "홈 화면에 추가" 안내 토스트 (1회만)

> **주의**: 서비스 워커가 인증 쿠키 흐름을 가로채면 HTMX 응답이 깨질 수 있다. **Network-First** 전략 + 캐시 화이트리스트로 시작.

### 5.4 다크모드 & 접근성

- `prefers-color-scheme` 자동 감지 (이미 구현됨 — 유지)
- 버튼 최소 터치 영역 44×44px
- `aria-label` 누락된 아이콘 버튼 보완
- 포커스 인디케이터 가시성 점검

---

## 6. Phase 3 — 운영 견고화 🟡 일부 완료

### 6.1 Rate Limiting — Bucket4j ✅ 완료 (리팩토링 13)

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

### 6.2 파일 업로드 보안 강화 ✅ 완료 (리팩토링 03, 12)

- 확장자 화이트리스트: `pdf, pptx, docx, txt, md`
- Apache Tika MIME 검증 (확장자 위조 차단)
- 사용자별 누적 용량 쿼터 (기본 500MB)
- 파일명 sanitize — `Path.normalize()` + 화이트리스트 정규식
- 업로드 경로 `data/users/{userId}/` 밖으로 나갈 수 없도록 `startsWith()` 검증

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

### 6.5 사용자별 LLM 사용량 쿼터 🔵 미착수 (user_id 컬럼 슬롯은 준비됨)

`LlmUsageRepository`에 `user_id` 컬럼이 이미 추가됨 (Phase 1.4). `AnswerService` 진입 시 일일 토큰 합계 조회 → 한도 초과면 `QuotaExceededException`.

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

### 7.3 관리자 페이지

- `ROLE_ADMIN` 전용 `/admin/**`
- 사용자 목록·상태, 전체 LLM 사용량, 감사 로그 조회
- 강제 로그아웃·계정 잠금 기능

---

## 8. Phase 5 — Vector Store 선택적 연동 🔵 미착수

### 8.1 동기 및 목표

| 항목 | ChromaDB | sqlite-vec |
|------|----------|------------|
| 인프라 | Docker 컨테이너 필수 | SQLite 확장 (인프라 0추가) |
| 배포 환경 | 서버·클라우드 운영 | 로컬·임베디드·저사양 VPS |
| 스케일 | 수백만 벡터 이상 | ~수십만 벡터 (동일 SQLite DB 풀 공유) |
| 운영 복잡도 | 높음 (Chroma 별도 관리) | 낮음 (SQLite 한 파일) |
| 배치 검색 | Chroma HTTP API 단일 호출 | JDBC PreparedStatement 루프 / CTE |

**목표**: `app.vectorstore.type=chroma`(기본) 또는 `sqlite-vec`를 설정만으로 전환 가능하게 한다. 코드 변경 없이 docker-compose 프로파일 + `.env`만 수정하면 전환되어야 한다.

### 8.2 작업 단계 로드맵

| 단계 | 작업 | 선행 의존 | 산출물 | 리스크 |
|------|------|----------|--------|--------|
| Step 5.1 ✅ | VectorStoreProvider 추상화 (Chroma 무행위 리팩토링) | — | `VectorStoreProvider`, `ChromaVectorStoreProvider` | 낮 |
| Step 5.2 ✅ | sqlite-vec 네이티브 확장 로딩 (운영자 제공 경로) | — (병행 가능) | `DataSourceConfig.configureSqliteVec`, `SqliteVecVerifier` | 중 |
| Step 5.3 ✅ | sqlite-vec 스키마 초기화 | 5.2 | `SqliteVecSchemaInitializer` | 중 |
| Step 5.4 ✅ | SqliteVecVectorStoreProvider 구현 | 5.1, 5.3 | `SqliteVecVectorStoreProvider` | 중 |
| Step 5.5 ✅ | 백엔드 선택 스위치 (조건부 빈) | 5.1, 5.4 | `VectorStoreProviderConfig`, Chroma 빈 가드 | 중 |
| Step 5.6 ✅ | 설정 외부화 (.env / docker-compose) | 5.5 | properties, `.env.example`, compose profiles | 낮 |
| Step 5.7 | 데이터 이전 + 통합 검증 | 5.6 | 재인덱싱 절차, 단위·통합 테스트 | 낮 |

> **머지 전략**: Step 5.1은 동작 변화가 없는 순수 리팩토링이므로 **독립 PR로 먼저 머지**해 회귀를 차단한다. 기본값이 `chroma`라 Step 5.2~5.7은 운영 영향 없이 점진적으로 머지할 수 있고, 마지막에 `app.vectorstore.type=sqlite-vec`로 전환해 활성화한다.

### Step 5.1 — VectorStoreProvider 추상화 계층 도입 ✅ 완료 (2026-06-23)

**목표**: Chroma 결합을 인터페이스 뒤로 숨긴다. **동작 변화 없는 순수 리팩토링** — 기존 테스트로 회귀를 검증한다.

현재 `VectorStoreRegistry`는 `ChromaVectorStore`를, `VectorStoreFacade`는 `ChromaApi`를 직접 의존한다. 두 레이어를 Chroma-불가지론 방식으로 재편한다.

```
app.vectorstore.type
  ├── "chroma"      → ChromaVectorStoreProvider  (기존 로직 이전)
  └── "sqlite-vec"  → SqliteVecVectorStoreProvider (Step 5.4에서 추가)

VectorStoreFacade  ──▶ VectorStoreProvider (인터페이스)
   (SAFE_VERSION 검증 등 횡단 관심사 유지)
        search() / searchBatch() / add() / deleteByDocIds()
```

#### 공통 인터페이스

```java
public interface VectorStoreProvider {
    /** 단일 쿼리 ANN 검색 */
    List<Document> search(String userId, String query, String version, int topK);

    /** 배치 멀티쿼리 검색 (RRF 입력용) */
    List<List<Document>> searchBatch(String userId, List<String> queries, String version, int topK);

    void add(String userId, String version, List<Document> docs);
    void deleteByDocIds(String userId, String version, List<String> springDocIds);
}
```

**작업**
1. `VectorStoreProvider` 인터페이스 정의 (위 4개 메서드)
2. `VectorStoreFacade` 내 Chroma 로직(`ChromaApi`, `ChromaApiConstants`, `collectionIdCache`, `searchBatch`의 `QueryResponse` 매핑)과 `VectorStoreRegistry`를 `ChromaVectorStoreProvider`로 이전
3. `VectorStoreFacade`는 `VectorStoreProvider`만 주입 — `SAFE_VERSION` 검증·유사도 임계값 등 횡단 관심사는 facade에 유지, I/O는 provider에 위임
4. 호출부(`RetrievalService`, `DocumentIndexer`) 시그니처는 그대로 — 변경 전파 없음

**완료 기준**
- [x] `VectorStoreFacade`가 `ChromaApi` / `ChromaVectorStore`를 직접 참조하지 않음 (import: `Document`, `VectorStoreProvider`만)
- [x] `VectorStoreRegistryTest` 수정 없이 통과 (8 tests). `DocumentIndexerTest`도 `mock(VectorStoreFacade.class)` 그대로 통과 (4 tests)
- [x] `VectorStoreFacadeTest`의 Chroma 내부 검증(R-1/S-3, 5 tests)은 `ChromaVectorStoreProviderTest`로 이전 — 커버리지 동일. facade에는 위임·버전검증 테스트 신규 추가 (6 tests) → 회귀 0
- [x] `chroma` 단일 모드에서 검색·인덱싱 동작 불변 (로직 그대로 이전, 전체 테스트 BUILD SUCCESS)

> **구현 메모**: `safe()`(SAFE_VERSION) 검증은 facade에 유지하고 provider는 검증된 버전을 받는다. `ChromaVectorStoreProvider`는 `@Component` 단일 빈으로 facade에 주입 — 백엔드 선택 스위치(`@ConditionalOnProperty`)는 Step 5.5에서 도입.

### Step 5.2 — sqlite-vec 네이티브 확장 로딩 (운영자 제공 경로) ✅ 완료 (2026-06-23)

**목표**: sqlite-vec `vec0` 네이티브 확장을 런타임에 로드한다. (Step 5.1과 병행 가능)

> ⚠️ **계획 정정**: 당초 `io.github.sqlite-vec:sqlite-vec-java` fat-jar를 가정했으나 **그런 공식 Maven 아티팩트는 존재하지 않는다** (검증 완료 — [asg017/sqlite-vec #90 "Java support"](https://github.com/asg017/sqlite-vec/issues/90)는 미해결 요청). 따라서 **새 의존성 0**으로, 이미 있는 xerial sqlite-jdbc의 `enable_load_extension` + `load_extension()`을 쓴다. 네이티브 바이너리는 **운영자가 직접 배치**(공식 릴리스의 플랫폼별 loadable)하고 경로만 설정한다.

**구현**
- `DataSourceConfig.configureSqliteVec(HikariConfig, type, path, entrypoint)` — `type=sqlite-vec`일 때만:
  1. `config.addDataSourceProperty("enable_load_extension", "true")` (xerial 기본 off → 보안상 sqlite-vec 모드에서만 on)
  2. `config.setConnectionInitSql("SELECT load_extension('<path>')")` — pool=1 커넥션(및 재생성 시)마다 로드. connectionInitSql은 단일 statement만 실행되므로 load_extension 한 문장만 둔다.
  3. 경로 누락 시 기동 실패(`IllegalStateException`), 경로·엔트리포인트의 작은따옴표 차단(SQL 주입/깨짐 방지)
- `SqliteVecVerifier` (`@ConditionalOnProperty type=sqlite-vec`) — `ApplicationReadyEvent`에서 `SELECT vec_version()`로 로드 확인, 실패 시 운영자 안내와 함께 fail-fast.

> **왜 `DataSourceConfig`에서 프로그램적으로?** DataSource를 수동 `@Bean`으로 만들기 때문에 `spring.datasource.hikari.*`(connectionInitSql 등) 바인딩이 적용되지 않는다 → 로딩을 코드로 설정해야 한다.

**설정** (Step 5.6에서 .env/compose로 외부화)
- `app.vectorstore.type=${VECTORSTORE_TYPE:chroma}`
- `app.vectorstore.sqlite-vec.extension-path=${SQLITE_VEC_EXTENSION_PATH:}` — vec0 바이너리 절대경로 (suffix `.dylib/.so/.dll` 생략 가능)
- `app.vectorstore.sqlite-vec.entrypoint=${SQLITE_VEC_ENTRYPOINT:}` — 보통 불필요(아래 PoC 결과)

**완료 기준**
- [x] `type=sqlite-vec` + 경로 지정 시 `SELECT vec_version()` 정상 반환 — **PoC 검증**: v0.1.9 macOS arm64 loadable을 실제 xerial→Hikari→connectionInitSql 경로로 로드해 `vec_version()=v0.1.9` 확인. 엔트리포인트 **미지정**과 `sqlite3_vec_init` **명시** 모두 성공 → 운영자가 엔트리포인트를 설정할 필요 없음 (파일명 `vec0`만으로 SQLite가 정상 로드)
- [x] `type=chroma`(기본)에서는 `SqliteVecVerifier` 빈 미생성 + DataSource 변경 없음(no-op)
- [x] 단위 테스트: `DataSourceConfigTest`(7) + `SqliteVecVerifierTest`(3). 전체 스위트 260 tests BUILD SUCCESS (회귀 0)

### Step 5.3 — sqlite-vec 스키마 초기화 (동적 DDL) ✅ 완료 (2026-06-24)

**목표**: `vec0` 가상 테이블과 메타 테이블을 앱 시작 시 생성한다. (선행: Step 5.2)

`vec0`의 차원수는 임베딩 모델에 묶여 있어 **DDL에 상수로 박혀야** 한다. 차원이 가변이라 Flyway 정적 마이그레이션과 맞지 않으므로, `IF NOT EXISTS` 동적 DDL을 시작 시 실행한다(Flyway `db/migration`에는 넣지 않는다).

```java
// SqliteVecSchemaInitializer.java
@Component
@ConditionalOnProperty(name = "app.vectorstore.type", havingValue = "sqlite-vec")
public class SqliteVecSchemaInitializer {

    @EventListener(ApplicationReadyEvent.class)
    void init() {
        Integer dim = props.embeddingSafe().dimensions();
        if (dim == null || dim <= 0)
            throw new IllegalStateException("sqlite-vec 모드는 app.embedding.dimensions 설정이 필수입니다");
        jdbc.execute("""
            CREATE VIRTUAL TABLE IF NOT EXISTS vec_embeddings
            USING vec0(
                spring_doc_id TEXT PRIMARY KEY,
                embedding FLOAT[%d]
            )
        """.formatted(dim));

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS vec_document_chunks (
                spring_doc_id TEXT PRIMARY KEY,
                content       TEXT NOT NULL,
                metadata      TEXT NOT NULL,  -- JSON
                version       TEXT NOT NULL,
                doc_id        TEXT NOT NULL,
                user_scope    TEXT NOT NULL DEFAULT 'shared',
                created_at    TEXT NOT NULL
            )
        """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_vec_chunks_version ON vec_document_chunks(version)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_vec_chunks_docid   ON vec_document_chunks(doc_id)");
    }
}
```

> **설계 결정**: 벡터(숫자)는 `vec_embeddings`, 텍스트·메타(JSON)는 `vec_document_chunks`로 분리하고 `spring_doc_id`로 JOIN한다. `user_scope`는 현재 공유 스토리지(`DocRegistry.SHARED`) 고정이라 기본값 `'shared'`.

**완료 기준**
- [x] `type=sqlite-vec` 첫 기동 시 두 테이블 + 인덱스 생성, 재기동 시 멱등(`IF NOT EXISTS`) — **PoC 검증**: 실제 v0.1.9 바이너리로 `vec0` 가상 테이블(`FLOAT[8]`) + `vec_document_chunks` + 인덱스 2개 생성, `init()` 2회 멱등, 8차원 벡터 insert 라운드트립 확인
- [x] `app.embedding.dimensions` 미설정/0/음수 시 `resolveDimension`이 명확한 오류로 기동 실패 (DDL 한 줄도 미실행)
- [x] `type=chroma`(기본)에서는 빈 미생성. 단위+조건부 테스트 7개, 전체 스위트 267 tests BUILD SUCCESS (회귀 0)

> **구현 메모**: `resolveDimension`/`embeddingTableDdl`/DDL 상수를 `static`으로 분리해 단위 테스트로 SQL 내용·차원 박힘·멱등(`IF NOT EXISTS`)을 검증. vec0 실제 동작은 네이티브 확장이 필요해 PoC로 검증(커밋 제외). `init()`은 `ApplicationReadyEvent`에서 실행 — 이 시점엔 `DataSourceConfig`가 커넥션에 vec0를 이미 로드한 상태.

### Step 5.4 — SqliteVecVectorStoreProvider 구현 ✅ 완료 (2026-06-24)

**목표**: `VectorStoreProvider`의 sqlite-vec 구현체를 완성한다. (선행: Step 5.1 인터페이스, Step 5.3 스키마)

> ⚠️ **구현 정정 (PoC로 확정)**: 아래 스니펫은 초기 스케치이며, 실제 구현은 다음을 따른다.
> - **벡터 직렬화**: BLOB(`SqliteVecUtils.toBytes`)이 아니라 **JSON 텍스트 리터럴** `[v0,v1,...]` — vec0가 `?` 바인딩으로 직접 수용 (`toVectorLiteral`).
> - **version 필터**: JOIN 후처리가 아니라 **vec0 partition key**로 KNN 내부 필터 → `WHERE embedding MATCH ? AND k = ? AND version = ?` 한 쿼리로 정확히 topK. JOIN은 `vec_document_chunks`에서 content/metadata 조회 전용 (Step 5.3 스키마에 `version TEXT partition key` + `distance_metric=cosine` 추가).
> - **거리지표**: cosine → `similarity = 1 - distance` 가 Chroma 경로와 동일 (동일 벡터 dist 0, 직교 1).
> - **add 멱등**: vec0는 `INSERT OR REPLACE` 미지원(UNIQUE 실패) → **기존 spring_doc_id DELETE 후 INSERT**.
> - **임베딩**: `embeddingModel.embed(List<String>)`(텍스트 추출) 사용 — `embed(List<Document>)` 아님. 빈 배선(@Bean)은 Step 5.5.

#### add()

```java
@Override
public void add(String userId, String version, List<Document> docs) {
    // 1. 임베딩 일괄 생성 (기존 EmbeddingModel 재사용)
    List<float[]> embeddings = embeddingModel.embed(docs);

    jdbc.batchUpdate(
        "INSERT OR REPLACE INTO vec_embeddings(spring_doc_id, embedding) VALUES (?, ?)",
        new BatchPreparedStatementSetter() {
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ps.setString(1, docs.get(i).getId());
                ps.setBytes(2, SqliteVecUtils.toBytes(embeddings.get(i)));  // float[] → byte[]
            }
            public int getBatchSize() { return docs.size(); }
        });

    // metadata upsert
    String now = Instant.now().toString();
    jdbc.batchUpdate(
        "INSERT OR REPLACE INTO vec_document_chunks " +
        "(spring_doc_id, content, metadata, version, doc_id, created_at) VALUES (?,?,?,?,?,?)",
        docs.stream().map(d -> new Object[]{
            d.getId(), d.getText(), toJson(d.getMetadata()),
            version, d.getMetadata().getOrDefault(MetaKey.DOC_ID, ""), now
        }).toList());
}
```

#### search()

```java
@Override
public List<Document> search(String userId, String query, String version, int topK) {
    float[] qEmbedding = embeddingModel.embed(query);
    byte[] qBytes = SqliteVecUtils.toBytes(qEmbedding);

    return jdbc.query("""
        SELECT c.spring_doc_id, c.content, c.metadata,
               vec_distance_cosine(e.embedding, ?) AS distance
        FROM vec_embeddings e
        JOIN vec_document_chunks c ON e.spring_doc_id = c.spring_doc_id
        WHERE c.version = ?
          AND e.embedding MATCH ?
          AND k = ?
        ORDER BY distance
        """,
        (rs, i) -> {
            double similarity = 1.0 - rs.getDouble("distance");
            if (similarity < similarityThreshold) return null;
            return buildDocument(rs, similarity);
        },
        qBytes, version, qBytes, topK
    ).stream().filter(Objects::nonNull).toList();
}
```

> **sqlite-vec KNN 문법**: `embedding MATCH ?` + `k = ?` 가 vec0 ANN 검색 트리거. `vec_distance_cosine()`는 후처리 정렬/필터링에 활용.

#### searchBatch()

Chroma처럼 단일 HTTP 호출로 배치 처리는 불가. 임베딩 배치 생성 → 루프 쿼리 방식으로 구현:

```java
@Override
public List<List<Document>> searchBatch(String userId, List<String> queries, String version, int topK) {
    if (queries == null || queries.isEmpty()) return List.of();
    List<float[]> embeddings = embeddingModel.embed(queries);  // 배치 임베딩 (1 HTTP 호출)
    return IntStream.range(0, queries.size())
        .mapToObj(i -> searchByEmbedding(embeddings.get(i), version, topK))
        .toList();
}
```

**성능 trade-off**: ChromaDB 배치 검색은 단일 HTTP 왕복이지만, sqlite-vec는 N번의 JDBC 쿼리. 임베딩 생성은 동일하게 배치 처리. 실측 기준 topK=10, N=3 쿼리 시 SQLite는 인메모리 연산이라 수 ms 수준 예상.

#### delete()

```java
@Override
public void deleteByDocIds(String userId, String version, List<String> springDocIds) {
    if (springDocIds == null || springDocIds.isEmpty()) return;
    String placeholders = springDocIds.stream().map(id -> "?").collect(joining(","));
    jdbc.update("DELETE FROM vec_document_chunks WHERE spring_doc_id IN (" + placeholders + ")",
                springDocIds.toArray());
    jdbc.update("DELETE FROM vec_embeddings WHERE spring_doc_id IN (" + placeholders + ")",
                springDocIds.toArray());
}
```

**완료 기준**
- [x] `add` → `search` 라운드트립으로 방금 넣은 청크가 topK에 포함 — **PoC**: apple 쿼리 → `[d1(score 1.0), d2(score 0.0)]`, 메타데이터 복원 확인
- [x] `version` 필터가 교차 버전 누출 없이 동작 — **PoC**: v2의 d3(apple 동일 벡터)가 v1 검색에 누출 안 됨
- [x] `deleteByDocIds` 후 두 테이블에서 동시 삭제 (고아 임베딩 없음) — **PoC**: d1 삭제 후 두 테이블 count 0
- [x] 유사도 임계값(`app.search-similarity-threshold`) 동작이 Chroma 경로와 일치 — **PoC**: threshold 0.5에서 sim 0.0 결과 제외
- [x] 멱등 재인덱싱(같은 id 재-add) UNIQUE 에러 없음. 단위 5 + 전체 272 tests BUILD SUCCESS

### Step 5.5 — 백엔드 선택 스위치 (조건부 빈 등록) ✅ 완료 (2026-06-24)

**목표**: `app.vectorstore.type` 하나로 두 provider 중 하나만 활성화한다. (선행: Step 5.1, Step 5.4)

> ⚠️ **구현 정정**: Plan이 가드 대상에서 누락한 빈이 있었다 — `VectorStoreRegistry`(@Service, ChromaApi 의존)와 **`AdminService`(ChromaApi 강결합)**. ChromaApi를 가드하면 둘 다 깨지므로: `ChromaVectorStoreProvider`는 `@Component` 제거 후 `VectorStoreProviderConfig`의 `@Bean`(chroma)으로 이동, `VectorStoreRegistry`도 chroma 조건부화, **`AdminService`의 `ChromaApi`는 `Optional<ChromaApi>`로 변경**(sqlite-vec 모드에선 `available=false`/빈 결과로 우아하게 강등 — `/admin`은 깨지지 않음).

```java
// VectorStoreProviderConfig.java
@Configuration
public class VectorStoreProviderConfig {

    @Bean
    @ConditionalOnProperty(name = "app.vectorstore.type", havingValue = "sqlite-vec")
    VectorStoreProvider sqliteVecProvider(JdbcTemplate jdbc, EmbeddingModel em, AppProperties props) {
        return new SqliteVecVectorStoreProvider(jdbc, em, props);
    }

    @Bean
    @ConditionalOnProperty(name = "app.vectorstore.type", havingValue = "chroma", matchIfMissing = true)
    VectorStoreProvider chromaProvider(VectorStoreRegistry registry, ChromaApi api,
                                       EmbeddingModel em, ObjectMapper om, AppProperties props) {
        return new ChromaVectorStoreProvider(registry, api, em, om, props);
    }
}
```

**작업** (실제 수행)
1. `AppProperties`에 `VectorStoreConfig(String type)` 레코드 + `vectorStoreSafe()`(기본 `"chroma"`) 추가
2. `VectorStoreProviderConfig`로 provider 택일 `@Bean` 등록 (sqlite-vec / chroma `matchIfMissing=true`), 생성자에서 활성 백엔드 로그
3. `ChromaVectorStoreProvider` `@Component` 제거 → 위 `@Bean`(chroma)으로만 등록
4. Chroma 전용 빈 가드 `@ConditionalOnProperty(havingValue="chroma", matchIfMissing=true)`: `ChromaConfig`(ChromaApi), `VectorStoreRegistry`, `ChromaHealthChecker`, `VectorStoreWarmup`
5. **`AdminService` → `Optional<ChromaApi>`** + 메서드별 null 가드 (Plan 누락분; sqlite-vec 모드 `/admin` 강등)
6. CLAUDE.md 제약 확인 — `spring.autoconfigure.exclude` 유지(무해), ChromaConfig 수동 빈 관리는 조건부화 후에도 chroma 모드 동일 → 상충 없음

> sqlite-vec 모드의 chunk 브라우징/편집(`/admin`)은 **미지원**(빈 목록). sqlite-vec용 admin은 별도 작업으로 남김.

**완료 기준**
- [x] `type=chroma`/미설정 시 chroma provider만, `type=sqlite-vec` 시 sqlite provider만 — `VectorStoreProviderConfigTest`(ApplicationContextRunner, 3 케이스, 각 `VectorStoreProvider` 1개)
- [x] sqlite-vec 모드에서 `ChromaApi` 빈 미생성 (ChromaDB 없이 기동) — `ChromaConfig` 조건부 테스트. `VectorStoreRegistry`·`VectorStoreWarmup`·`ChromaHealthChecker`도 동일 어노테이션
- [x] 두 모드 모두 `VectorStoreProvider` 빈이 정확히 1개
- [x] `AdminService` Optional.empty 우아한 강등 (`AdminServiceTest` 3). 전체 280 tests BUILD SUCCESS (회귀 0)

### Step 5.6 — 설정 외부화 (.env / docker-compose) ✅ 완료 (2026-06-24)

**목표**: 코드 변경 없이 `.env` + compose 프로파일만으로 백엔드를 전환한다. (선행: Step 5.5)

> ⚠️ **구현 정정**: Plan이 `chroma`에 `profiles`만 추가하면 된다고 했으나, `app`이 `chroma`를 `depends_on: condition: service_healthy`로 강하게 의존해 sqlite-vec 모드(`docker compose up`)에서 비활성 서비스 의존으로 기동이 막힌다. → `depends_on`에 **`required: false`**(Compose 2.20.2+)를 추가해, chroma 프로파일이 꺼지면 의존을 무시하고 app만 뜨게 했다. `app` environment에 `VECTORSTORE_TYPE`/`SQLITE_VEC_EXTENSION_PATH`/`SQLITE_VEC_ENTRYPOINT`를 추가하고, sqlite-vec용 vec0 바이너리 볼륨 마운트 예시를 주석으로 제공한다.

```properties
# application.properties — 백엔드 선택 (기본: chroma)
# 가능한 값: chroma | sqlite-vec
app.vectorstore.type=${VECTORSTORE_TYPE:chroma}
# 유사도 임계값 등 검색 튜닝(app.search-*)은 두 백엔드가 동일 키 공유
```

```bash
# .env.example
# VECTORSTORE_TYPE=chroma       # ChromaDB (기본, Docker 필요)
# VECTORSTORE_TYPE=sqlite-vec   # sqlite-vec (외부 의존 없음)
```

`chroma` 서비스를 compose 프로파일로 묶어 sqlite-vec 모드에서는 띄우지 않는다.

```yaml
services:
  chroma:
    image: chromadb/chroma:latest
    profiles: ["chroma"]          # ← chroma 프로파일에서만 기동
    restart: unless-stopped
    ports: ["8001:8001"]
    volumes:
      - chroma_data:/data
```

```bash
# ChromaDB 모드
VECTORSTORE_TYPE=chroma docker compose --profile chroma up

# sqlite-vec 모드 (Chroma 컨테이너 없음)
VECTORSTORE_TYPE=sqlite-vec docker compose up
```

**완료 기준**
- [x] `.env`의 `VECTORSTORE_TYPE`만 바꿔 재기동하면 백엔드 전환 (조건부 빈 — Step 5.5, application.properties `app.vectorstore.type=${VECTORSTORE_TYPE:chroma}` — Step 5.2)
- [x] `docker compose up`(프로파일 없이) 시 chroma 컨테이너 미기동, 앱은 sqlite-vec로 정상 — `chroma profiles: ["chroma"]` + `app depends_on … required: false`로 구성. (이 환경엔 docker 미설치로 실행 실측은 Step 5.7/운영 환경에서)
- [x] `OPERATOR_MANUAL.md`에 두 모드 운영법 반영 (§3.1 "벡터 스토어 백엔드 선택" — 기동 명령·sqlite-vec 바이너리 배치·재인덱싱·`/admin` 제약)

> **참고**: `.env.example`·`application.properties`의 `VECTORSTORE_TYPE`/`SQLITE_VEC_*`는 Step 5.2에서 이미 추가됨. Step 5.6은 compose 프로파일(+`required: false`)과 운영 문서가 핵심.

### Step 5.7 — 데이터 이전 및 통합 검증

**목표**: 기존 Chroma 데이터를 sqlite-vec로 옮기고 E2E로 검증한다. (선행: Step 5.6)

#### 데이터 이전 — 전체 재인덱싱

Chroma 벡터를 sqlite-vec로 직접 덤프하는 것은 내부 포맷 의존성이 커 비권장. 문서 원본이 `data/documents/`에 영구 보관되므로 **재인덱싱**을 표준 이전 경로로 삼는다(데이터 손실 없음).

1. `VECTORSTORE_TYPE=sqlite-vec`로 앱 재시작
2. 관리자 페이지 `/admin` → "전체 문서 재동기화" 실행
3. `data/documents/` 기반으로 sqlite-vec에 임베딩 재생성

#### 테스트

- **단위** `SqliteVecVectorStoreProviderTest` — `add`/`search`/`searchBatch`/`deleteByDocIds` (인메모리 SQLite + `SQLiteVec.load()`)
- **통합** `@SpringBootTest`(sqlite-vec 프로파일) — 업로드 → 검색 → 삭제 E2E

**완료 기준 (Phase 5 인수)**
- [ ] `type=sqlite-vec` 재시작 시 ChromaDB 없이 앱 정상 동작
- [ ] `type=chroma`(기본) 재시작 시 기존 동작 회귀 0
- [ ] `add`/`search`/`searchBatch`/`deleteByDocIds` 단위 테스트 통과
- [ ] 업로드→검색→삭제 통합 테스트 통과
- [ ] `docker compose up`(chroma 프로파일 없이) 정상 기동
- [ ] sqlite-vec 모드에서 `ChromaHealthChecker`·`VectorStoreWarmup` 빈 미생성 확인
- [ ] 재인덱싱 후 동일 쿼리 결과가 Chroma 경로와 정성적으로 일치

---

## 9. 리스크 및 이슈

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

---

## 10. 의존성 변경 사항 (pom.xml)

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

# Limits
app.security.bcrypt-cost=12
app.security.login-lock-attempts=5
app.security.login-lock-minutes=15
app.security.upload-quota-mb=500

# Vector Store (Phase 5)
app.vectorstore.type=${VECTORSTORE_TYPE:chroma}                          # chroma(기본) | sqlite-vec
app.vectorstore.sqlite-vec.extension-path=${SQLITE_VEC_EXTENSION_PATH:}  # sqlite-vec: vec0 바이너리 경로(운영자 제공)
app.vectorstore.sqlite-vec.entrypoint=${SQLITE_VEC_ENTRYPOINT:}          # sqlite-vec: 보통 불필요
```

---

## 11. DB 스키마 변경 요약

| 마이그레이션 | 내용 |
|------------|------|
| `V1__baseline.sql` | 기존 4개 테이블 (`conversation_turns`, `llm_usage`, `thread_meta`, `image_descriptions`) 캡처 |
| `V2__users.sql` | `users`, `persistent_logins` 신규 테이블 |
| `V3__user_scope.sql` | 기존 4개 테이블에 `user_id` 컬럼 + 인덱스 추가 |
| `V4__audit_log.sql` | `audit_log` 신규 테이블 |
| `V5__upload_quota.sql` | `users.storage_used_bytes` 컬럼 추가 |
| (Phase 5) `SqliteVecSchemaInitializer` | `vec_embeddings` (vec0 가상 테이블 — `version` partition key + `distance_metric=cosine`), `vec_document_chunks` — Flyway 대신 앱 시작 시 동적 DDL (차원수 파라미터화) |

> **기존 데이터 처리**: `V3` 적용 시 기존 row의 `user_id`는 NULL이 된다. 로컬 단일 사용자 데이터는 **최초 관리자 계정 생성 후 일괄 UPDATE**하는 별도 스크립트로 처리. 운영 데이터 보존이 불필요하면 마이그레이션 전 DROP도 옵션.

---

## 12. 최종 체크리스트

### 12.1 Phase 1 완료 기준 ✅

- [x] HTTPS: Caddy 설정 완료 — 도메인 배포 시 Let's Encrypt 자동 발급, HTTP → HTTPS 자동 리다이렉트
- [x] 비로그인 사용자는 `/`, `/chat/**`, `/documents`, `/api/**` 접근 불가 (단 `/login`, `/signup`, `/api/health`는 허용)
- [x] 회원가입 → 자동 로그인 → 채팅 (SecurityContextHolder 수동 주입)
- [x] 멀티유저 데이터 격리 — SQLite `user_id` 컬럼 + Chroma `u_{userId8}_{version}` 컬렉션
- [x] Flyway 마이그레이션 (`V1__baseline` ~ `V3__user_scope`)
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
