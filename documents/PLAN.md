# RAG-Agent 온라인 확장 개발 계획

> Java 개발자 관점 · Spring Boot 3.5 + Spring AI 1.1 + Java 21 · 작성일 2026-05-11  
> **개발 기준 문서**: 이 파일(documents/PLAN.md)이 마스터. `documents/refactoring/18-extension-roadmap.md`는 각 항목의 기술 레퍼런스.

---

## 📊 전체 현황 대시보드

> 완료/미착수를 한눈에 보도록 상단 대시보드를 신설했다.
>
> **완료 항목 압축 원칙 (2026-08-22 적용)**: ✅ 완료된 항목은 **① 무엇을 왜 그렇게 했는가 ② 앞으로도 지켜야 할 결정·함정 ③ 남은 열린 항목**만 남기고, 구현 과정 서술·테스트 통계·CLAUDE.md에 이미 있는 메커니즘 설명은 걷어낸다. 살아 있는 동작 명세의 단일 출처는 **코드와 CLAUDE.md**이고, 이 문서는 "왜 그렇게 결정했는가"의 기록이다 — 양쪽에 같은 설명을 두면 드리프트만 생긴다(§12가 의존성에 대해 같은 판단을 이미 적용했다).

### ✅ 완료 — Phase 1·2·5·6·7 전체, Phase 3 전체(§6.15·6.16.2·6.19·6.20 제외)

| Phase | 완료 항목 | 상세 |
|---|---|---|
| **Phase 1** — 보안 기반 | Step 1.1~1.6 전체(Caddy·Flyway·Spring Security·멀티유저 격리·CSRF·로그인/회원가입 UI) + `app.auth.enabled` no-auth 토글 | §4 |
| **Phase 2** — 모바일 UI | 반응형 레이아웃(Offcanvas) · PWA(manifest/SW/오프라인) · 다크모드·접근성 | §5 |
| **Phase 3** — 운영 견고화 | §6.1 Rate limit · §6.2 업로드 검증(매직바이트, 쿼터는 §6.15로 이관) · §6.3 예외처리 · §6.4 감사로그 · §6.5 임베딩 사용량 분리 · §6.6 비활성 프로바이더 표시 · §6.7 orphan 기록 삭제 · §6.8 피드백 기반 컨텍스트 제외 · §6.9 요약 선계산 · §6.10 백그라운드 사용량 분리 · §6.11 컨텍스트 예산 정합성 · §6.12 다중 사용자 동시 LLM 처리(동시성 게이트+백프레셔+로드밸런싱) · §6.13 설정 페이지(핫 수정 오버라이드) · §6.14 핵심 채팅 경로 추적 · §6.16.1 스트리밍/인덱싱 중단 버튼 · §6.17 관리 전용 인증(B안) · §6.18 Direct temperature 분리 · §6.19.3 XFF 신뢰 옵트인 · §6.21 소형 LLM 분리+멀티 LLM 처리량 확장 · §6.22 접속자별 채팅 개인화(no-auth) · §6.23 청크 변경 시 답변 재사용 무효화·대화 기록 표시 · §6.24 응답 모드 재설계(S/N/C — L 제거·모드별 전용 프롬프트·오염 방지 3건, `4-c`만 미착수) · §6.25 관리자 대화 목록·삭제·검색 진단 연결 | §6 |
| **Phase 5** — Vector Store | Step 5.1~5.10 전체(Chroma↔sqlite-vec 런타임 전환, 관리자 페이지, 태그 검색, 운영/벡터 DB 분리) | §8 |
| **Phase 6** — 폐쇄망/노-도커 | G1~G5(키리스 LOCAL·차원 외부화·라우팅 외부화·런북·무외부호출 인수) | §9 |
| **Phase 7** — 검색 품질·성능 고도화 | §10.1~10.9 전체(17건) — 정확도·속도·메모리 개선 + recall@k/nDCG@k 평가 하네스(baseline recall@10=0.962). **+ §10.10** — 좋아요 기반 큐레이션 Q&A 지식화(스냅샷·임베딩·검색 융합·관리 UI) 완료 | §10 |

추가로 Phase 3 초기에 완료된 항목(문서화되지 않았던 픽스 포함): ChromaDB v2 API 컬렉션명→UUID 자동 변환, 문서 저장 경로 공유 구조 단순화(`DocRegistry.SHARED`), 인덱싱 SSE 진행 단계별 표시, 키워드 추출 타임아웃 시 CircuitBreaker 오동작 수정, DOCX 변환 전 구버전 아티팩트 삭제 순서 수정, 환경변수 외부화 4건, 의존성 최신 stable 일괄 업데이트(정확한 버전은 pom.xml 참조).

### 🔵 진행할 것 (우선순위 순)

> **재우선순위화**: 실배포 기준(폐쇄망·no-auth 단일 운영자)에서 가치가 없는 **멀티유저(`auth.enabled=true`) 전용 작업**(§6.19·§6.20·§6.16.2·Phase 4)은 전부 후속으로 내렸다(사유는 아래 후속 표의 트리거 열 참조). §6.15(스토리지 쿼터)만 설계상 전역 상한이 1차 권장이라 단일 운영자에도 적용되므로 지금 진행 그룹에 남겼다. **§6.25(관리자 대화 목록)도 이름은 §7.3(Phase 4) 계열이지만 같은 이유로 지금 진행 그룹이다** — `app.auth.guest-identity`가 `shared`가 아니면 no-auth 단일 운영자 배포에도 방문자별 대화가 쌓이고, 그중 Step 1은 인증 모드와 무관한 검색 오염 버그 수정이다.

**🟢 지금 진행 (no-auth 단일 운영자 배포에도 바로 적용)**

| 순위 | 항목 | 현재 상태 |
|---|---|---|
| 1 | **§6.15 스토리지 쿼터**(전역 상한 B안, §6.2에서 이관) | 설계 완료, 구현 전 |
| 2 | 운영 준비 잔여 — SQLite 백업 자동화(Litestream/cron), Caddy 인증서 만료 모니터링 | 미착수 |
| 3 | §6.24 `4-c` — 검색 부스트 상향 | 부스트 기본값이 0이라 미착수. 올리려면 `MAX_EVAL_EXCERPT_CHARS` 상향이 **같은 변경에** 선행돼야 한다(§6.24) |
| 4 | §9.4 — CADDY 하위호환 별칭 | 선택, 낮은 우선순위 |
| 5 | Phase 2 남은 실기기 검증 2건 (키보드 하단 고정 · 홈 화면 standalone) | 좌우 스크롤·다크모드는 자동 검증 완료, 나머지는 실기기 필요 |

**🟣 후속 — 멀티유저(`auth.enabled=true`) 활성화 시에만 착수**

| 순위 | 항목 | 트리거 |
|---|---|---|
| 1 | **§6.19 보안 하드닝** — API CSRF/세션 혼용(6.19.1) · `/admin/**` ROLE_ADMIN 게이트(6.19.2) ※ **6.19.3(XFF)은 §6.22와 함께 완료** | **auth 모드 여는 시점에 반드시 선행**(게이트) — no-auth엔 노출면 없음 |
| 2 | **§6.20 사용자별 LLM 토큰 쿼터** | 실사용자가 여럿 생겨 사용량 격리가 필요해질 때 |
| 3 | **§6.16.2 계정 잠금 상태 피드백** | auth 모드 로그인 UX — no-auth엔 로그인 자체가 없음 |
| 4 | **Phase 4** (조건부) — §7.1 OAuth2 소셜 로그인 · §7.2 PostgreSQL 마이그레이션 · §7.3 관리자 페이지 확장 | §3 트리거 참조(가입 마찰·SQLite 한계 신호·다중 사용자 운영 관리 필요 시) |

> 검색 고도화 **Phase 7-D**(sqlite-vec 단일 스캔·cross-encoder 리랭커·시맨틱 응답 캐시)는 재검토에서 범위 제외(사유·재개 신호는 §10.5) — Phase 7의 유일한 미착수였던 항목.
>
> 스키마 관리: **Flyway(V1·V2 baseline) + 런타임 멱등 DDL 혼용** — 신규 컬럼은 새 Flyway 파일이 아니라 런타임 `ALTER TABLE ADD COLUMN` 패턴으로 추가한다(§13).

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
10. [Phase 7 — 검색 품질·성능 고도화](#10-phase-7--검색-품질성능-고도화)
11. [리스크 및 이슈](#11-리스크-및-이슈)
12. [의존성 변경 사항](#12-의존성-변경-사항-pomxml)
13. [DB 스키마 변경](#13-db-스키마-변경-요약)
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
| Phase 7 — 검색 품질·성능 고도화 | 가중 RRF·쿼리 임베딩 캐시(7-A) · Contextual Retrieval(7-B) · 한국어 FTS(7-C) · 성능/메모리 최적화 제안(7-E) | 중요 | 🟡 7-A·7-B·7-C 완료, 7-E 제안 검토중 |

---

## 2. 현재 구조 분석

**강점** — JdbcTemplate 직접 사용(Postgres 전환 시 ANSI SQL만 유지하면 매끄러움) · Immutable Record 상태(`AgentState`/`ChatResponse` 등, 동시성 안전) · Java 21 Virtual Threads · HTMX 서버 렌더링(모바일/PWA 대응 단순) · `VectorStoreRegistry`(컬렉션 키 추상화, 멀티테넌시 전환에 자연스럽게 맞물림).

**Phase 1 착수 시점의 약점** — Phase 1~3에서 전부 해소됨: 인증 없음(→Spring Security 폼 로그인 + no-auth 병행, Step 1.3), `threadId` 소유 개념 없음(→Repository에 userId 강제, Step 1.4), 파일 저장 격리 없음(→이후 공유 저장소로 재단순화, `DocRegistry.SHARED`), HTTPS 미적용(→Caddy, Step 1.1), 마이그레이션 도구 없음(→Flyway, Step 1.2), 모바일 UI 없음(→Offcanvas/PWA, Phase 2), Rate limit 없음(→Bucket4j, §6.1).

---

## 3. 핵심 기술 의사결정

**3.1 인증 — 세션 vs JWT**: `HttpSession + Spring Security` 폼 로그인 채택 — Spring Security 기본 동작과 정합되고 로그아웃·CSRF 통합이 자연스러움. JWT는 다중 서버·네이티브 앱이 필요해질 때 보류(토큰 폐기/갱신을 직접 구현해야 하고 HTMX엔 이득이 적음). Redis 세션 저장소는 다중 인스턴스 필요 시 후속 도입.

**3.2 TLS 종료 위치**: Caddy 리버스 프록시 채택 — Let's Encrypt 자동 갱신 + 설정 최소(Spring 직접 TLS는 인증서 수동 갱신 부담, Cloudflare는 외부 종속). Spring 측은 `server.forward-headers-strategy=framework` + 쿠키 `Secure`+`SameSite=Lax` 강제.

**3.3 SQLite 지속 전략**: 읽기 우세 워크로드라 SQLite 유지, 모든 SQL을 ANSI 표준 + Flyway로 작성해 Postgres 전환을 대비만 해둔다(JdbcTemplate 그대로 사용 가능). **전환 트리거**: `SQLITE_BUSY` 빈도 1%/분 초과, 다중 인스턴스 배포 필요, 응답 지연을 풀 분리로도 해결 불가, 실시간 백업·복제가 요구사항이 됨.

**3.4 멀티테넌시 모델**: Row-level(`user_id` 컬럼) + 사용자별 Chroma 컬렉션 채택(Schema/DB per tenant는 B2B 전용이라 과함) — SQLite 한 파일로 운영 가능. Repository 시그니처를 `getHistory(userId, threadId)`로 강제해 **userId 누락 시 컴파일 에러**가 나도록 안전장치를 걸었다.

> 각 결정의 대안·트레이드오프 비교표는 [부록 — 결정 사항 한눈에 보기](#부록--결정-사항-한눈에-보기) 참고.

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

---

## 6. Phase 3 — 운영 견고화 🟡 일부 완료

> **재배열**: 완료 항목을 앞쪽에(기반 운영 항목 → LLM 사용량 클러스터 → 대화/컨텍스트 클러스터), 미착수 항목을 뒤쪽에 우선순위 순으로 재배치했다. 번호가 이 문서 다른 곳(§7·§12·§13)에서도 참조되므로 교차 참조는 전부 새 번호로 갱신됨.

### 6.1 Rate Limiting — Bucket4j ✅ 완료

`RateLimitFilter`(`OncePerRequestFilter`, `SecurityFilterChain` 앞단 등록)가 엔드포인트별 인메모리 버킷을 적용 — 채팅 분당 20회/userId, 업로드 분당 5회/userId, 로그인 분당 10회/IP, 전체 익명 분당 30회/IP. 다중 인스턴스 확장 시 Redis 백엔드로 전환 필요(부록 참조).

### 6.2 파일 업로드 보안 강화 🟡 부분 완료 (리팩토링 03, 12)

**✅ 완료**
- 확장자 화이트리스트: `pdf, pptx, docx, txt, md`
- **매직바이트 검증** — `security/FileTypeDetector.matches(path, ext)`(Tika 아님, pom에 Tika 의존성 없음). 임시파일 기록 후 검증, 불일치 시 422
- 파일명 sanitize — `Path.normalize()` + 화이트리스트 정규식
- 경로 이탈 방지 — 공유 저장소 `data/documents/`(per-user 격리 폐기, `DocRegistry.SHARED`) 기준 `startsWith()` 검증

**🔵 미착수** — 사용자별 누적 용량 쿼터 → §6.15로 이관·구체화(현재 `storage_used_bytes` 컬럼·쿼터 로직·`app.upload-quota` 프로퍼티 모두 없음).

### 6.3 글로벌 예외 처리 ✅ 완료

`@RestControllerAdvice`(`GlobalExceptionHandler`) 기반 RFC 9457 ProblemDetail 응답 — `RagException` 서브클래스는 자체 `httpStatus()`로, `MaxUploadSizeExceededException`(413)·`IllegalArgumentException`(400)·미처리 예외(500, `RAG-INT-001`)는 개별 핸들러로 매핑. HTMX 요청엔 `HX-Reswap: none` 헤더 추가.

### 6.4 감사 로그 ✅ 완료 (Logback 파일 롤링)

SQLite `audit_log` 테이블 대신 Logback `SizeAndTimeBasedRollingPolicy`로 구현.
- `data/audit/audit.log` — NDJSON 포맷 (jq 분석 가능)
- 일별 로테이션 + 10MB 분할, gzip 압축, 7일 자동 삭제, 100MB 전체 상한
- `application.properties`로 모든 파라미터 조정 가능, `app.audit.enabled=false`로 즉시 비활성
- 이벤트 8개 기록: upload×2, delete×2, sync×2, routing-mode, thread-delete

### 6.5 LLM 사용량 — 임베딩 사용량 분리 ✅ 완료

`TrackingEmbeddingModel`이 `EmbeddingModel`을 데코레이트해 `embed:<model>` 이름으로 채팅과 분리 기록, `/llm-usage` 카드·표·차트 3경로 모두 `type=EMBEDDING`으로 표시. 부수로 `/api/llm/usage` 경로 오타(항상 404) 발견·수정.

### 6.6 LLM 사용량 — 비활성 프로바이더 조건부 표시 ✅ 완료

`LlmUsageRepository.usedProviders()` + `OperationsController.visibleChatProviders()` 공통 필터(`configured || 사용이력있음`)로 카드·표·차트 3경로를 통일 — 키 없는 프로바이더는 이력 없으면 숨기고, 활성 프로바이더는 항상 표시.

### 6.7 LLM 사용량 — 설정에 없는(orphan) 프로바이더 기록 삭제 ✅ 완료

설정에서 제거된 프로바이더의 과거 사용 기록을 `type=ORPHAN`으로 노출하고, `DELETE /admin/llm-usage/{provider:.+}`(관리자 전용, orphan 아니면 400 거부)로 삭제.

### 6.8 Chat 응답 피드백(좋아요/싫어요) 기반 컨텍스트 제외 ✅ 완료

Assistant 응답에 👍/👎 토글 추가(`conversation_turns.feedback`, `PATCH /ui/threads/{threadId}/turns/{turnId}/feedback`). `DISLIKE` turn은 `getHistory()`에서 하드 제외되어 다음 컨텍스트에서 빠진다(`LIKE`는 저장만, 아직 미소비).

### 6.9 입력 시작 시 로컬 요약 선계산 + 중복 제거 컨텍스트 압축 ✅ 완료

입력 시작 시(첫 글자) `ConversationSummarizerService`가 이전 대화를 LOCAL 프로바이더로 미리 요약해 스레드별 LRU 캐시(최대 3개, TTL 15초)에 저장 — 캐시 있으면 "요약+최근 2턴", 없으면 기존 `getHistory()`로 조용히 폴백.

**이후 개선 — 답변의 `## 요약` 재사용 + LLM 요약 게이팅**: RAG 답변은 이미 `## 요약` 섹션을 갖고 있으므로 그대로 쓰고 LLM 재요약을 생략한다(`CuratedTextUtils.extractSummarySection()`). 전부 있으면 **LLM 호출 0회**, 일부에 없으면(Direct/meta 답변) 축약 입력에 대해 1회 호출하되 **MICRO_TEXT 전담 소형 모델이 등록됐을 때만** — 부가 기능인 대화 요약이 답변 생성용 `priority=1` 로컬 모델의 슬롯을 잠식하지 않게 하려는 것. 소형 미설정 시(기본값이 없어 오히려 흔한 경로)에도 요약을 포기하지 않고, 요약 섹션이 없는 답변만 `UNSUMMARIZED_ANSWER_CAP`(300자)으로 자른다 — `truncate()`가 **앞에서부터** 자르므로 앞쪽의 긴 Direct 답변 하나가 예산을 독식하면 최신 턴이 통째로 밀려나기 때문.

### 6.10 LLM 사용량 — 백그라운드(비-채팅) 사용량 분리 기록 ✅ 완료

채팅 외 LLM 호출(요약·키워드추출·서식교정·TXT→MD·제목생성)을 `BackgroundUsage` 접두사(`summary:`/`keyword:`/`mdcorrect:`/`txt2md:`/`title:`)로 채팅과 분리 기록, `/llm-usage`에 `type=BACKGROUND` 카드로 노출. 조사 중 발견한 핵심 채팅 경로 자체의 추적 공백은 §6.14로 분리했다.

---

### 6.11 대화 컨텍스트 예산 정합성 + 설정 외부화 ✅ 완료

§6.9 도입 후 요약 경로에는 없던 문자 예산 체크를 `MemoryService.maxConversationChars()`(단일 진실 원천, `max(1000, LLM_MAX_TOKENS × 0.5)` — 도입 당시 0.75였으나 요약 입력 상한을 조이면서 0.5로 낮췄다)로 통일하고, `FETCH_LIMIT` 등 하드코딩 5개 값을 `app.memory.*`/`app.summary.*` 프로퍼티로 외부화.

---

### 6.12 다중 사용자 동시 LLM 요청 처리 — 동시성 제어 + 처리량 확장 ✅ 완료

채팅 경로에 동시성 제한이 전혀 없어(세마포어는 인덱싱 경로에만 존재) 여러 사용자가 겹치면 폴백 없는 유일 프로바이더가 서킷브레이커로 전면 차단되던 문제를 5단계로 해결: ① 프로바이더별 동시성 세마포어(`LlmRouter.acquirePermit`/`executeGated`, `app.llm.providers[].concurrency`) ② 대기상한 초과 시 429 백프레셔(`LlmBackpressureException`, 서킷브레이커 없음) ③ `CachingEmbeddingModel` in-flight single-flight(동일 텍스트 중복요청 병합) ④ 폴백 없는 유일 프로바이더의 서킷브레이커 단축 차단(30초) ⑤ 동일 role·priority 프로바이더 간 least-in-flight 로드밸런싱. 인덱싱/백그라운드 경로는 기존 세마포어를 유지하며 미적용.

---

### 6.13 설정 페이지 — LLM/RAG 옵션 조회·부분 수정 ✅ 완료

`GET /settings`(게스트 조회 가능, 편집은 `isAdmin` 게이트)에서 LLM/임베딩/RAG 설정을 조회하고, `settings_override` 테이블 기반 오버레이 레이어로 **재기동 없이** 반영한다 — 검색 값은 다음 검색부터, 인덱싱/청킹 값은 다음 인덱싱/↺ 재인덱싱부터. 핵심 규칙은 **소비처가 값을 필드에 캐시하지 않고 매 호출 `props.xxxSafe()`로 재조회**하는 것(`MarkdownCorrectionService`의 생성자 캐시를 제거해 세 소비처의 동작을 일치시켰다). 빈 생성 시점에 고정되는 값(rerank-enabled 등)과 기본 라우팅 모드는 조회 전용. 수정은 `/admin/settings/update|reset`(§6.17 ROLE_ADMIN 상속) + 감사 로그. **핫 키 추가 절차와 전체 키 목록은 CLAUDE.md §6.13 항목이 단일 출처.**

---

### 6.14 LLM 사용량 — 핵심 채팅 경로 추적 확장 ✅ 완료

`AnswerService`/`DirectAnswerService`/`ClassifierService`/`RerankerService`/`VisionDescriptionService`/`ImageTypeClassifier`/`RetrievalService`의 `MultiQueryExpander` 7곳이 `LlmRouter`를 거치지 않아 실제 채팅 사용량이 `/llm-usage`에 전혀 안 잡히던 문제(사용자 실사용 중 보고)를 발견·수정 — 블로킹 호출은 `executeWithTracking()`으로, 스트리밍은 `LlmRouter.recordApproxUsage()`로, 프레임워크가 내부에서 자체 `ChatClient`를 구성해 가로챌 수 없는 `MultiQueryExpander`는 신규 `TrackingChatModel` 데코레이터로 해결.

---

### 6.15 사용자별 스토리지 쿼터 🔵 미착수 (§6.2에서 이관) — 지금 진행 (우선순위 1)

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

### 6.16 사용자 경험(UX) 개선 🟡 일부 완료 (6.16.1)

**6.16.1 스트리밍 답변·인덱싱 중단(취소) 컨트롤 부재 ✅ 완료**

채팅은 `chat-stream.js`의 `AbortController`로 중지(전송 버튼→중지 버튼 전환, 중단 시점까지 답변 보존). 인덱싱은 신규 `IndexingCancelledException` + `POST /ui/documents/progress/{taskId}/cancel`(워커 스레드 `interrupt()`)로 취소 — `KeywordExtractor.enrichParallel()`/`DocumentIndexer.syncDirectory()`가 non-interruptible `CompletableFuture.join()`을 쓰던 버그를 `.get()`으로 교체해 실제 취소되도록 수정. 취소 시점까지 완료된 파일은 레지스트리에 보존.

**6.16.2 계정 잠금 상태 피드백 부재**
- **현재 상태**: 로그인 5회 실패 시 15분 잠금(`AuthEventListener`)되지만, 잠긴 뒤에도 로그인 화면은 일반 "이메일 또는 비밀번호가 올바르지 않습니다" 문구만 보여준다(`formLogin.failureUrl("/login?error")` 고정). 사용자는 자신이 잠겼는지, 언제 풀리는지 알 수 없다(문구가 "5회 실패 시 15분 잠금"을 안내는 하지만 현재 잠금 상태/해제 시각은 아님).
- **개선안**: `LockedException` 분기를 별도 실패 핸들러로 잡아 "N분 후 해제" 메시지 전달(`locked_until` 조회). 잠금 남은 시간은 이미 `SqliteUserDetailsService`가 관리 중.
- **완료 기준**: 잠긴 계정 로그인 시 잠금 상태 + 해제 예정 시각이 구분되어 표시된다.

---

### 6.17 문서 관리·Admin 접근 인증 필수화 + 역할 기반 화면 분기 ✅ 완료 (B안)

no-auth 기본 배포에서 `/documents` 쓰기와 `/admin/**`이 로그인 없이 열려 있던 문제를, 채팅·조회는 no-auth로 두고 `/documents`(쓰기)·`/admin/**`만 로그인 요구하는 (B) 관리 전용 인증으로 해결 — `app.auth.management-only=true` 신규 서브모드, `SecurityConfig` 3번째 필터 체인(`IF_REQUIRED`+쿠키 CSRF), `hasRole("ADMIN")` 게이트, `GlobalModelAdvice.isAdmin`으로 화면 분기. (A) 전체 인증 모드는 §6.19와 함께 후속(멀티유저 활성화 시).

---

### 6.18 Direct 메시지 전용 LLM Temperature 분리 ✅ 완료

라우터 경로가 Spring AI 오토컨피규레이션을 우회해 `LLM_TEMPERATURE` 등 기존 환경변수가 **전부 죽은 설정**이던 문제 — 하드코딩 4곳을 제거하고 `app.llm.temperature`(일반/RAG)·`app.llm.direct-temperature`(Direct 전용)·`app.llm.max-tokens`로 전환. direct-temperature만 매 호출 재조회해 핫 수정(블로킹은 `Prompt`, 스트리밍은 `ChatCompletionRequest`에 주입). 이후 §6.13 확장으로 세 temperature가 모두 핫이 됐고 max-tokens만 조회 전용으로 남았다(현행 clamp·소비처는 CLAUDE.md §6.13 항목 참조).

**동작 변경(주의)**: `MemoryService`·`MarkdownCorrectionService`가 읽던 죽은 `spring.ai.openai.chat.options.max-tokens`(기본 8000)를 `props.llmSafe().maxTokens()`(6000)로 통일하면서 **대화 히스토리 예산 6000→4500자, MD 교정 섹션 크기 3750→2750자**로 기본값이 줄었다. 과거 분량을 유지하려면 `LLM_MAX_TOKENS`를 올려야 한다.

---

### 6.19 보안 하드닝 🔵 미착수 — 인증 활성 배포 시 우선순위(후속 1순위)

> **범위 주의**: 아래 3건은 **`app.auth.enabled=true`(멀티유저 인증) 배포에서만** 의미가 있다. 폐쇄망 단일 운영자(no-auth) 기본 배포에는 노출면이 없다 — 그래서 §6.20/6.15(쿼터)보다 뒤가 아니라 "인증 켜고 다중 사용자로 열 때 반드시 선행"으로 조건부 우선순위다. 각 항목은 실제 코드 위치와 재현 조건을 명시했다.

**6.19.1 `/api/v1/**` CSRF 비활성 + 세션 인증 혼용 (CSRF 노출)**
- **현재 코드**: `SecurityConfig`가 인증 모드에서 `csrf.ignoringRequestMatchers("/api/v1/**")`로 API 전체를 CSRF 예외 처리하지만, 같은 엔드포인트가 `anyRequest().authenticated()` — 즉 **세션 쿠키 인증**이다. `POST /api/v1/documents`는 `multipart/form-data`(CSRF "simple request")라 크로스사이트 폼으로 인증된 사용자의 브라우저에서 업로드를 유발할 수 있다(`DELETE`·JSON body 경로는 preflight로 상대적으로 안전).
- **개선안**: (A) API를 브라우저 세션이 아닌 **별도 인증(API 토큰/헤더)** 전용으로 못박고 CSRF 예외를 유지하거나, (B) 세션 인증을 계속 쓸 거면 `/api/v1/**`도 CSRF 토큰을 요구(현 HTMX/폼 경로 `/ui/**`는 이미 CSRF 적용). 최소 조치로 `POST /api/v1/documents`만이라도 CSRF 토큰 또는 커스텀 헤더 요구.
- **완료 기준**: 인증 모드에서 크로스사이트 폼 제출로 인증 사용자의 업로드/삭제가 트리거되지 않음(테스트로 재현→차단 확인). no-auth 모드 회귀 0.

**6.19.2 `/admin/**` 인가 공백 — 일반 인증 사용자도 관리 기능 접근**
- **현재 코드**: 인증 모드에서 ROLE_ADMIN을 요구하는 경로는 **`DELETE /admin/llm-usage/**` 하나뿐**(`SecurityConfig` 주석이 "나머지 `/admin/**`는 any authenticated user 유지"를 명시). 따라서 `/admin`(청크 브라우징), `DELETE /admin/chunks/{id}`, `POST /admin/chunks/{id}`(편집), `POST /admin/documents/{docId}/reindex`를 **로그인한 일반 사용자 누구나** 호출해 벡터 청크를 조회·수정·삭제·재인덱싱할 수 있다.
- **개선안**: `/admin/**` 전체를 `hasRole("ADMIN")`으로 게이트. no-auth 모드는 `NoAuthAutoLoginFilter`가 `/admin` 요청을 첫 ADMIN으로 자동 인증하므로 무영향. 단, `AdminController`/`AdminService` 테스트에 비관리자 403 케이스 추가 필요.
- **완료 기준**: 인증 모드에서 비-ADMIN 사용자의 `/admin/**` 접근이 403. no-auth 모드 관리자 자동 인증 회귀 0.

**6.19.3 Rate limit — `X-Forwarded-For` 무검증 신뢰** ✅ **완료** (§6.22와 함께 선행 처리)
- XFF 판정을 `ClientIpResolver`(신규) 한 곳으로 일원화하고 **최상위** `app.trust-forwarded-for`(기본 `false`) 옵트인일 때만 신뢰. 프로퍼티를 rate-limit 하위가 아니라 최상위에 둔 이유는 §6.22(방문자 식별)가 같은 판정을 쓰기 때문 — 두 소비자가 한 곳을 공유한다.
- **승격 사유(기록용)**: 원래 멀티유저 후속이었으나, §6.22에서 IP가 **식별자**가 되는 순간 XFF 위조가 속도 제한 우회를 넘어 **남의 대화 목록 열람**이 되어 no-auth 배포에서도 선행 필수가 됐다.
- ⚠️ **프록시 뒤 배포는 `TRUST_FORWARDED_FOR=true`가 필수** — 끄면 전 방문자가 프록시 주소 하나로 합쳐진다.

---

### 6.22 접속자별 채팅 개인화 (no-auth) ✅ 완료

no-auth 배포에서 모든 방문자가 고정 게스트 id 하나를 공유해 스레드 목록·대화 이력이 섞이던 문제를, `NoAuthAutoLoginFilter`가 주입하는 게스트 principal의 id **한 곳만** 방문자별로 파생해 해결(저장 계층은 Step 1.4에서 이미 `user_id` 축으로 격리돼 있었고 상수를 먹고 있었을 뿐 → 저장·서비스 계층 변경 0). `app.auth.guest-identity` = `shared`(기본, 회귀 0)/`ip`/`cookie`/`hybrid`(권장), id는 `guest-<12 hex>` = HMAC-SHA256(`app_secret` 테이블의 영속 시크릿, 방문자 키). 메커니즘 상세는 CLAUDE.md `GuestIdentityResolver` 항목.

**유지해야 할 사실**
- 인증을 켜면 `GuestIdentityResolver`가 `@ConditionalOnProperty(app.auth.enabled=false)`로 컨텍스트에서 사라진다 — 파생 게스트 id와 실제 로그인 id가 동시에 살아 있는 경로가 없다. 전환 시 기존 게스트 스레드는 `guest-%` 접두사로 일괄 삭제하거나 실계정에 귀속 가능
- 문서 저장은 공유 유지(`DocRegistry.SHARED`) — 개인화 대상은 채팅 스레드·이력·좋아요 소유권뿐
- 부수 효과: §6.20 (A)안(`conversation_turns` 기반 집계)이 no-auth에서도 의미를 갖게 됐고, 큐레이션 "본인만 편집"(`source_user_id`)이 비로소 실동작
- ⚠️ 이 설정을 켜기 전 쌓인 스레드는 옛 공용 id에 묶여 조회되지 않는다(삭제는 아니며 `shared`로 되돌리면 복귀). 채택 시 운영자 고지 — OPERATOR_MANUAL §9.4.3

---

### 6.23 청크 변경 시 답변 재사용 무효화 + 대화 기록 표시 ✅ 완료

답변 재사용(`/api/v1/questions/reuse`)이 근거 청크가 그 사이 삭제·수정된 답변을 사실처럼 되돌려주던 문제를 **스냅샷 대조**로 해결 — 턴 저장 시 `(chunk_id, sha256(chunk_fts.content), answer_share)`를 `turn_source_ref`에 남기고 재사용 직전 대조하며, 삭제/재인덱싱 경로는 별도로 즉시 무효화 표시를 남겨 이중으로 막는다. 대화 기록에는 `SourceRef.staleBadge()` 한 곳이 정한 규칙으로 **삭제됨/수정됨** 배지를 띄운다. 메커니즘·테이블·배지 규칙 상세는 CLAUDE.md의 `QuestionReuseService`/`QuestionReuseRepository`/`SourceRef` 항목.

**유지해야 할 결정**
- **검증 범위 = 응답 지분(`answer_share > 0`)이 있는 청크만.** 한 글자도 반영 안 된 청크의 수정으로 멀쩡한 답변을 폐기하면 문서를 손볼 때마다 재사용이 통째로 무력화된다. 단 지분을 모르면(구 데이터, 귀속 `Method.NONE`) 전체를 검증한다 — **차단의 폴백은 항상 엄격한 쪽**
- **의도한 비대칭**: 구 데이터는 "배지 없이 재사용만 막히는" 상태가 된다. 차단의 폴백은 안전한 쪽, 표시의 폴백은 조용한 쪽
- **`updateChunk()`가 유일한 해시 사각지대** — 재임베딩 없이 저장 텍스트만 고쳐 `chunk_fts`를 건드리지 않으므로 원문이 바뀌어도 해시가 그대로다. 명시적 통지(`invalidateChunk`)로만 잡힌다. **청크를 바꾸는 새 경로를 추가하면 반드시 통지를 함께 붙일 것**

---

### 6.24 응답 모드 재설계 — S/N/C (L 제거 · 모드별 전용 시스템 프롬프트) ✅ 완료 2026-08-24 — 설계 확정 2026-08-22 (분량 지침·`/settings` 배선 보강 2026-08-23, 완료 후 보정 2026-08-25)

길이 축(S/M/L)에 "문서를 **재료로** 코드·설정을 생성"이라는 **다른 축**(근거 엄격도)을 얹으려다, 기존 축부터 검토해 **M과 L이 구분되지 않음**을 확인하고 재설계했다. M/L이 같았던 이유는 튜닝이 아니라 구조다 — 채팅의 유일한 전송 경로인 스트리밍에는 `maxTokens`가 붙지 않고, `RetrievalService`가 모드를 몰라 두 모드의 재료(topK)가 동일하며, 남은 차이인 "약 N자" 문구는 모델을 움직이지 못했다(실측 M 3,047자 / L 3,187자, **둘 다 M의 목표 5,000자 미달**). 그래서 L 제거·M→N 개명 후, 모드마다 **시스템 프롬프트를 통째로** 주고(공용 프롬프트를 사용자 메시지로 뒤집던 층은 삭제) 응용 모드 **C**를 신설했다. Phase 0(`0-a`~`0-e`) · 1(`1-a`~`1-d`) · 2(`2-a`~`2-d`) · 3(`3-a`~`3-c`) · 4(`4-a`·`4-b`) 완료, `4-c`만 미착수(아래). 모드별 동작 명세는 CLAUDE.md의 Response mode 항목과 PIPELINE §3.1.

**유지해야 할 결정**
- **상한(cap)과 목표(target)는 다르게 작동한다.** 짧은 출력에 건 상한은 모델이 스스로 멈추는 지점보다 **앞**이라 구속력이 있고, 긴 출력에 건 목표는 그 **뒤**라 아무 일도 하지 않는다. 그래서 숫자를 말하는 건 S뿐이고 N·C는 "무엇을 더 다룰지"를 지시한다. 긴 답변 수요가 재발해도 레버는 숫자가 아니라 섹션별 분할 생성(호출 3배) 또는 문서 내보내기다
- **S의 숫자는 모드가 아니라 경로별이다** — RAG `prompt.answer.system.s`는 1,000자, Direct `prompt.direct.system.s`는 1,500자(인용할 발췌가 없어 스스로 풀어 써야 한다). 고정해야 할 불변식은 숫자 자체가 아니라 **한/영 번들이 같은 숫자를 말하는 것**이며(언어에 따라 답변 길이가 달라진 사고가 실제로 났다) `ResponseModeSystemPromptTest`가 이를 잡는다
- **`maxTokens()`는 폭주 방지 안전판이지 분량 통제 수단이 아니다.** `min(configured, …)` 클램프로 설정 상한을 넘지 않게 하되(구 L의 잠복 버그), 비율항은 유지한다(제거하면 16,000 환경에서 N이 6,400→5,000으로 회귀). 어느 항이 이겼는지는 `/settings` 응답 예산 행이 보여준다. 프롬프트 상한보다 **넉넉히 위**여야 하므로 S의 프롬프트 상한을 올리면 `minChars`도 함께 올린다. S 가드는 **구조만** 본다 — 글자 수로 자르는 로직을 넣지 말 것
- **C의 오염 방지 3건이 공개의 전제였다**(다크 런치 이유). 특히 큐레이션 제외(`allowsCuration()`)가 **가장 위험한 단일 지점** — C 답변이 `curated_qa`에 들어가면 가중 RRF 축으로 다시 검색돼 모델이 지어낸 코드가 다음 턴의 "문서"가 되고, 되돌리려면 벡터를 찾아 지워야 한다. 재사용 제외(`allowsReuse()`)와 펜스 절단 복구가 나머지 둘
- **C는 검증을 끄지 않고 바꿔 낀다.** `grounded`는 창의 답변에서 정의상 거짓이라 그대로 두면 재시도로 정상 턴의 3배를 태우고, 통째로 끄면 API 발명이 무방비가 된다. 같은 호출이 이미 답변과 발췌를 들고 있어 **추가 왕복 0회**로 `apiGrounded`/`inventedSymbols`를 묻는다. 배지는 초록 `검증됨`이 아니라 파랑 `생성` — **다른 질문을 통과한 것**이므로 같은 배지를 쓰면 독자가 후자를 전자로 읽는다
- **분기는 값 비교가 아니라 성질 질의로**(`ResponseModeBranchConventionTest`). 이 그물은 **문자열 안을 못 본다** — 3-b의 제외 규칙이 SQL 리터럴 `<> 'S'`로 두 쿼리에 박혀 있었고, 이제 제외 목록을 enum에서 만들어 공유한다. **허용(IN)이 아니라 제외(NOT IN)** 여야 `parse()`의 관대함과 같은 방향으로 떨어진다

**계획이 틀렸던 지점 (같은 실수 방지)**
- **서버 가드 위치**: `ChatController.normalizeResponseMode()`는 라디오→폼 매핑만 하고 **SSE 경로를 지나가지 않는다**. 채팅의 유일한 전송 경로가 스트리밍이므로 거기 두면 아무것도 막지 못한다 → 두 진입점 값 객체(`ChatRequest` 컴팩트 생성자 · `ChatForm.responseModeOrDefault()`)로 옮겼다
- **"히스토리"가 히스토리가 아니었다**: `message-assistant.html`은 no-JS 폴백이고 새로고침 후 기록은 `chat.html`의 자체 루프다. 그 루프는 검증 배지를 **아예 렌더하지 않았다**(저장이 없어서) — 즉 "새로고침 전후 동일" 기준은 S/N에 대해서도 이미 거짓이었다. 검증 결과를 저장하고, **렌더러가 셋**(HTMX 폴백·기록 루프·스트리밍 JS)이라 배지 규칙을 `VerificationSnapshot` 한 곳으로 모았다
- **3-c의 절반이 틀렸다**: 절단은 뒤에서 덜어낼 뿐이라 없던 줄 중간 펜스를 만들 수 없다 — 실제 결함은 홀수 펜스 하나뿐이었다. 처음 쓴 테스트 2개가 이미 결함 있는 입력을 먹여 통과해 버렸다 → **새 테스트는 반드시 수정 전 코드에서 실패시켜 볼 것**
- **순서 제약**: 0-c(스타일 지시문 층 제거)는 1-a/1-b와 **한 커밋**이어야 한다 — 그 층이 S/N의 분량 지시를 나르는 유일한 통로라, 대체 프롬프트 없이 걷어내면 두 모드가 공용 5섹션 형식으로 붕괴한다

**완료 후 보정 (2026-08-25)**: ① 검증 응답을 읽지 못하면 `grounded=true`로 위조하던 폴백을 **판정 없음**(`grounded=null`, 배지 없음·재시도 없음)으로 교체 — 검증한 적 없는 답변에 `검증됨`/`생성` 배지가 붙고 `sufficient=false` 재시도까지 사라지던 경로였다(운영 진단은 OPERATOR_MANUAL §8). ② 중지 버튼이 화면만 멈추고 LLM 스트림은 계속 소비하던 것을 실제 취소로 수정(`CancellableTokenStream`). ③ Direct S 상한 1,000→1,500자.

**미착수 — 4-c (검색 부스트 상향)**: `retrievalBoost()`는 `RetrievalService`에 배선돼 있으나 **모든 모드에서 0**이고 `app.search-creative-top-k-boost` 외부화도 하지 않았다. **노브와 `MAX_EVAL_EXCERPT_CHARS`(20,000) 상향은 한 변경에 함께 들어가야 한다** — 지금 `/settings`에 노브만 내면 부스트를 1만 올려도 상한을 넘어 하위 순위 문서가 검증 창에서 빠지고, `AnswerService`가 `.limit(5)`를 없애며 고쳤던 오탐(문서 #10~12에만 있는 값을 정확히 인용한 답변이 `grounded=false`)이 되살아난다. 부스트 근거 자체도 약하다 — 실사용 topK가 이미 10~12라 +4면 ~40,000토큰으로 32K 모델에서 앞부분이 **조용히** 잘린다(창의 모드에서 환각 금지 조항이 잘리는 것이 최악).

**남은 이슈**: (a) `/llm-usage` 토큰 과소 보고 — `ResponseMode`는 "한글 1토큰≈1글자", `LlmRouter.approxTokens()`는 chars/4로 **같은 코드베이스에 4배 차이 나는 두 가정이 공존**한다(스트리밍 한국어 사용량이 ~4배 적게 기록됨). (b) C 턴은 `AnswerAttribution` 지분이 0에 수렴해 `/admin` 진단 패널의 "답변에 실제 쓰인 출처 수"가 항상 0으로 보인다 — 창의 eval이 `usedDocs`를 묻지 않으므로 구조적이며, "해당 없음" 표기는 `/admin` 쪽 작업이라 범위 밖. (c) 긴 답변 수요는 없는 것으로 확인(운영자: "M 수준으로 충분").
---

### 6.25 관리자 — 전체 대화 목록 조회·삭제 + 검색 진단 연결 ✅ 완료 2026-08-28

관리자가 `/admin`에서 **전 사용자의 대화 목록**(제목·최종 활동·턴 수·재사용 수·피드백)을 보고, 대화를 삭제하고, 그 대화의 검색 진단 수치로 바로 내려갈 수 있게 했다. §7.3(관리자 페이지 확장)의 "대화 관리" 부분을 선반영한 것 — 남은 사용자 계정 관리·감사 로그 뷰는 §7.3에 그대로 있다.

**새 스키마 0개.** 요구 데이터가 전부 `thread_meta`/`conversation_turns`에 이미 있었고, 빠진 것은 그것을 전 사용자 단위로 묶어 읽는 계층과 화면뿐이었다. 구성: `ThreadAdminRepository`/`ThreadAdminService`(조회·삭제), `fragments/admin-threads.html`(패널·드릴다운), `fragments/admin-source-table.html`(출처 표 — 진단 패널과 공유), `AdminController`의 `/admin/threads*` 라우트. 동작 명세는 CLAUDE.md 해당 항목이 단일 출처다.

**선행 버그 픽스(Step 1, 단독 가치)**: 대화를 통째로 지울 때 좋아요로 승격된 `curated_qa` 행과 벡터가 살아남아 검색에 계속 기여하고 있었다. 턴 단위 `deleteTurn`은 `onUnlike()`로 막고 있었지만 `deleteThread`에는 그 장치가 아예 없었다. `CuratedQaService.onThreadDeleted()`를 만들어 **사용자 경로와 관리자 경로 양쪽**이 호출한다.

#### 지켜야 할 결정·함정

- **재사용 카운터는 두 개이고 방향이 반대다.** `reusedIn`(이 대화가 과거 답변을 재사용한 횟수) vs `reusedOut`(이 대화의 답변이 재사용된 횟수). 삭제 버튼 옆에 있어야 하는 건 후자다 — 지우면 그 턴들이 전부 `"참조 원문 삭제됨"` 폴백이 된다. `reusedOut`은 **조인이 아니라 상관 서브쿼리**여야 한다(두 번 조인하면 그룹 행이 곱해져 다른 카운터가 전부 부풀어 오른다).
- **시각은 두 계열로 저장된다.** `conversation_turns.asked_at`은 UTC(`Instant.now()`), `thread_meta.updated_at`은 시스템 로컬(`LocalDateTime.now()`) — 실데이터로 정확히 9시간 어긋남을 확인했고, 진단 패널은 그때까지 UTC를 로컬인 양 찍고 있었다. 표시만 `KstDateFormat.utcStampToKst()`로 통일했다. `toKst()`와 합치지 않은 이유는 **입력 형식이 달라서**다(ISO instant vs 존 없는 DB 스탬프) — 합치면 "존 없는 값은 UTC"라는 규칙이 형식 추측에 묻힌다. **저장 존 통일은 하지 않았다**(아래 열린 항목).
- **삭제는 thread id 만 받는다.** `thread_meta.thread_id`가 PK라 소유자는 서버가 찾는다 — `userId`를 받으면 "누구 대화를 지울지 지정하는 파라미터"를 노출하는 셈이다. 확인 대화상자의 숫자도 렌더된 행이 아니라 **클릭 시점 재조회**(`delete-preview`)에서 온다: 패널이 몇 분 전 것일 수 있고, 낡은 숫자로 되돌릴 수 없는 작업을 승인하게 두는 것이 그 절차가 막으려는 실패다. **벌크 삭제는 없다.**
- **큐레이션 회수에서 제안(manual) 행은 `source_turn_id IS NOT NULL`로 배제한다.** 지금 수동 행이 `source_thread_id=''`라는 사실에 기대면, 그 값이 나중에 진짜 id로 바뀌는 순간 대화 삭제가 제안의 일부 청크를 조용히 내린다(제안은 전부/전무 단위). 실데이터 삭제 검증에서 like 행은 `inactive`+벡터 제거, manual 행은 `active` 유지로 이 가드가 실제로 하는 일을 확인했다.
- **답변 원문은 목록과 다른 쿼리로만 나간다.** 목록의 `TurnRow`에는 `answer` 필드가 아예 없다 — 감추는 것을 템플릿이 아니라 구조로 강제해야 나중에 템플릿을 고치다 유출시킬 수 없다. 목록 쿼리에 컬럼을 더했다면 **목록을 여는 것 자체가 기록 없는 열람**이 됐을 것이다. 열람은 `admin.thread.read` 감사를 남기고, **내용이 실제로 나갈 때만** 남긴다(없는 턴은 404이고 열람이 아니다). 오프캔버스는 마크다운 렌더 없이 `<pre>`+`textContent`.
- **`/api/v1/**` 아래 두면 안 된다** — CSRF 면제 + management-only에서 게스트 개방이라 전 사용자 대화 제목이 그대로 열린다. `/ui/threads`(사용자 사이드바)와도 다른 엔드포인트다.
- **크로스 유저 조회를 `ThreadMetaRepository`에 섞지 말 것** — 그 클래스는 모든 메서드가 `userId` 스코프라는 게 불변식이다(`findRecentRetrievalMetrics`가 "Deliberately not user-scoped"를 주석으로 못 박은 선례를 따라 별도 클래스).
- **진단 패널의 사용자/대화 필터는 배타다**(한 대화는 소유자가 한 명). 서버가 `threadId` 우선으로 `userId`를 떨구고 **클라이언트도 같은 규칙**을 갖는다 — 한쪽만 두면 URL을 직접 친 경우와 버튼으로 들어온 경우가 갈린다. 목록을 거르면 **개수도 같은 필터로** 걸러야 한다(페이지네이션이 크기 기반이라 개수만 어긋나도 아무것도 안 깨지고, 그래서 발견이 늦는다).
- **C 턴의 "사용/검색"은 `해당 없음`이다** — 창의 평가기가 `usedDocs`를 묻지 않아 지분이 구조적으로 0이고, `0/8`로 그리면 영원히 쫓게 될 검색 버그로 읽힌다(§6.24 남은 이슈 b를 여기서 해소).
- **레이아웃 함정**: 다른 열이 전부 고정 폭이면 그 합계가 좁은 창의 테이블 폭을 다 먹고, 유연 열이 `td { max-width:0 }`(말줄임 관용구) 때문에 몇 px로 눌린다(제목이 한 글자만 남았다). `th`의 `min-width`로는 못 고치고 **테이블 자체에 `min-width`**를 줘 `.table-responsive`가 넘침을 흡수하게 해야 한다.
- **`ResponseModeBranchConventionTest`는 텍스트 스캔이라 주석까지 본다** — "이렇게 쓰지 말라"고 설명하려고 javadoc에 적은 금지 형태가 그대로 빌드를 깼다. 가드를 완화하지 말고 문구를 고칠 것.
- **고아 턴 카운트는 기능이 아니라 정합성 표시**(`/admin/registry/reconcile-chunks` 계열). 실배포에서 "요약의 재사용 건수는 2인데 모든 행이 0"을 설명해 준 값이다 — 두 재사용 턴이 전부 `thread_meta` 없는 대화에 속해 있었다. 그래서 진단 목록 조인도 **LEFT** 여야 한다(INNER면 목록에서만 빠지고 배지는 그대로라 조용히 어긋난다).

#### 검증 방법 (재현용)

실데이터 사본으로 앱을 띄워 확인했다. 그 과정에서 걸린 환경 함정 둘: `spring-boot:run`은 `target/classes`의 템플릿 **사본**을 쓰고 Thymeleaf가 캐시하므로 프래그먼트 수정은 `mvn compile`+재시작 없이 반영되지 않는다. 그리고 `-DDATA_DIR`에 git-bash 경로(`/c/Users/...`)를 넘기면 Java가 `C:\c\Users\...`로 해석해 **엉뚱한 위치에 빈 DB를 만든다**(화면에는 "데이터 없음"으로만 보인다).

#### 남은 열린 항목 (§6.25 범위 밖)

- **`thread_meta.updated_at`의 저장 존 통일** — 지금은 시스템 로컬이라 서버가 KST가 아닌 곳에서 돌면 두 시각 열이 다시 갈린다. 어느 존으로 쓰였는지 사후에 알 수 없어 표시 계층에서 고칠 수 없다.
- `curated_qa.source_thread_id` 인덱스 — 대화 목록에 큐레이션 수 열을 넣고 싶어질 때 필요(지금은 삭제 확인에서만 1회 조회).
- 전사 트랜스크립트 검색/뷰어 — 필요해지면 §7.3.
- **고아 `turn_source_ref`** — 턴이 사라졌는데 남은 출처 스냅샷 행이 실배포에 54건 있다(읽는 경로가 전부 `conversation_turns`에서 출발해 무해하지만, 누적된다). 정리 스크립트나 `reconcile` 확장이 필요하면 별도 항목.

---

### 6.20 사용자별 LLM 사용량 쿼터 🔵 미착수 — 멀티유저 활성화 시 후속 (우선순위 2)

**현재 코드 확인 (2026-07-04 재확인)**:
- `llm_usage.user_id` 컬럼 자체는 **이미 존재**한다(`LlmUsageRepository.init()`의 런타임 `ALTER TABLE ... DEFAULT 'anonymous'`, EDIT.md #6에서 발견). 하지만 `record(String provider, long in, long out)`에 `userId` 파라미터가 없고 `getByPeriod/getDaily/usedProviders/deleteByProvider` 등 모든 조회 메서드도 이 컬럼을 참조하지 않아 **모든 행이 영구히 'anonymous'로 고정** — 사실상 죽은 컬럼이다. **실제 `llm_usage`는 여전히 프로바이더 단위 집계**이며, 사용자별 쿼터를 하려면 이 컬럼을 실제로 채우거나(아래 B안) conversation_turns 기반(A안, 권장)으로 별도 집계해야 한다.
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

---

### 6.21 소형(경량) LLM 분리 — 태스크별 모델 라우팅 + 멀티 LLM 처리량 확장 ✅ 완료

추론이 필요 없는 고빈도 백그라운드 호출(키워드+맥락 추출·대화 요약·제목 생성·MultiQuery 확장)을 신규 `TaskType.MICRO_TEXT`(`LIGHT_TEXT`/`BOTH`에 폴백)로 분리해 500MB급 소형 로컬 모델로 오프로딩 — 분류·직답은 품질 유지를 위해 큰 모델에 남김. 임베딩은 `LoadBalancingEmbeddingModel`(다중 엔드포인트 least-in-flight)+서브배치 병렬화로 처리량 확장, 대화 응답 쪽은 §6.12 재사용. **전부 opt-in(미등록 시 기존 동작 그대로) → 회귀 0**. 설정·런북: [LLM_ROUTING.md §9](LLM_ROUTING.md) · [OPERATOR_MANUAL §3.2·§5.4](OPERATOR_MANUAL.md).

**주의**: 임베딩 다중 엔드포인트는 **동일 모델·차원이어야 한다**(섞으면 인덱스 손상). 소형+대형 co-located 시 VRAM/RAM 합산 확인.

**실측 게이트 — 부분 검증으로 종료**: 라이브 듀얼티어로 §10.7.5 하네스 실행 시 색인된 골든셋 6건 전부 recall@10=1.0 — 소형 모델 라우팅이 검색 품질을 회귀시키지 않음을 확인. 전체 baseline(0.962) 정식 대조는 코퍼스에 arch/sample 문서가 미색인이라 보류(§6.21과 무관한 코퍼스 갭) — 재색인 후 `mvn test -Dtest=SearchQualityEvaluationTest -Dsearch-eval.enabled=true`로 재현 가능.

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

> **일부 선반영 → §6.25**: 이 절의 "사용자/운영 관리" 중 **대화 관리**(전 사용자 대화 목록 조회·삭제 + 검색 진단 연결)는 no-auth 단일 운영자에서도 값어치가 있어 Phase 3 §6.25로 분리해 먼저 진행한다. 여기 남는 것은 **사용자 계정 관리**(목록·잠금 해제·강제 로그아웃)와 감사 로그 뷰 — 전부 `auth.enabled=true`가 전제다.

- `ROLE_ADMIN` 전용 경로 확장(현재 청크 관리 위주 → 사용자/운영 관리 추가). ※ DB `role` 값은 `ADMIN` 문자열, 인증은 `NoAuthAutoLoginFilter`가 no-auth 모드에서 첫 `ADMIN` 사용자로 자동 인증.
- 사용자 목록·상태(잠금/활성), 전체 LLM 사용량(§6.5~6.7과 연계), 감사 로그(`data/audit/audit.log` NDJSON) 조회 뷰.
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

세부 산출물은 위 로드맵 표 참고. 보완 메모: 5.3 차원 미설정 시 fail-fast. 5.4 upsert 미지원이라 add=DELETE 후 INSERT. 5.7 백엔드 전환은 항상 재인덱싱 필요(원본 보존, 무손실).

### Step 5.9 — 태그 기반 검색 스코프 ✅ 완료

업로드 시 다중 태그 저장 + 채팅에서 선택한 태그로 **엄격 AND 필터**(Chroma·sqlite-vec·하이브리드 공통). provider가 배열 포함 연산자를 지원하지 않아 `RetrievalService.execute()`가 `mergeRrf()` 직후 Java post-filter로 적용하고, 결과 부족 대비 `candidateK`를 선제 확대(`app.search-tag-candidate-multiplier`). 태그 소스는 `chunk_fts.doc_tags` 단일 컬럼(태그 제안 UI + 재인덱싱 자동복원 공용). 스키마 변경은 프리릴리즈 정책상 마이그레이션 없이 수동 초기화(OPERATOR_MANUAL §4.6).

### Step 5.10 — sqlite-vec 운영 DB 분리 ✅ 완료

`SQLITE_VEC_DB_PATH` 설정 시 벡터/FTS 테이블을 별도 `vector.db`로 분리해 인덱싱 I/O와 운영 트랜잭션 락 경합을 감소(기본 비활성, 기존과 동일 동작). `vectorJdbcTemplate` 빈이 두 모드 모두 대응(분리 시 `vector.db`/아니면 `memory.db` 별칭)해 소비자 코드는 무변경, Chroma 경로는 완전 무영향. 파일 간 트랜잭션 원자성이 없어 인덱싱은 항상 벡터/FTS 먼저 → 레지스트리 마지막 순서로 커밋(고아 벡터는 재인덱싱이 덮어씀). 전용 DataSource도 pool=1+WAL+busy_timeout 동일 적용.

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

G1~G4 코드/문서 완료, G5(라우팅 계층의 외부 무선택)도 완료. vec0 필요한 sqlite-vec 라이브 부팅·실소켓 0 측정은 운영 인수로 남김.

---

## 10. Phase 7 — 검색 품질·성능 고도화 ✅ 완료

> 검색 파이프라인(`RetrievalService`: MultiQuery 확장 → 배치 임베딩 → 벡터 검색 → 가중 RRF 융합 → 하이브리드 BM25 → (opt-in) LLM 리랭크 → 태그 필터) 위에서 정확도·속도·메모리를 끌어올리는 증분 개선 17건. §10.7.5에서 구축한 recall@k/nDCG@k 평가 하네스로 실측 baseline(recall@10=0.962)까지 확보해 전체 종료.

| 항목 | 개선 | 핵심 산출물 |
|------|------|------|
| §10.1 Contextual Retrieval ✅ | 청크 임베딩·FTS 입력에 구조적+LLM 맥락 헤더 주입 | `SearchTextBuilder`, `KeywordExtractor` |
| §10.1-보완 임베딩 입력 정규화 ✅ | 마크다운 장식 제거, 저장/임베딩/프롬프트 3계층 분리 | `MarkdownNoiseNormalizer` |
| §10.2 가중 RRF ✅ | 벡터축 그룹 정규화 + 키워드축 가중치 외부화 | `RetrievalService.mergeRrf()` |
| §10.3 쿼리 임베딩 캐시 ✅ | Caffeine 캐시 데코레이터 | `CachingEmbeddingModel` |
| §10.4 한국어 FTS 트라이그램 ✅ | `unicode61`→`trigram` 무손실 자동 재구축 | `chunk_fts` |
| §10.7.1 리랭커 프리뷰 확장 ✅ | 200→500자 + 구조적 컨텍스트 헤더 | `RerankerService` |
| §10.7.2 하이브리드 기본 활성화 ✅ | `SEARCH_HYBRID_ENABLED` false→true | — |
| §10.7.3 2글자 질의 LIKE 폴백 ✅ | trigram 미만 질의 존재-신호 보강 | `KeywordSearchRepository` |
| §10.7.4 유사도 임계값 과조회 ✅ | `topK×2` 조회 후 재절단 | Chroma/sqlite-vec 양쪽 |
| §10.7.5 검색 품질 평가 하네스 ✅ | 골든셋 26문항 + recall@k/nDCG@k | `evaluation/` |
| §10.8.1 MultiQuery 지연 병렬화 ✅ | min-length 상향 + 원본검색 병렬 실행 | `RetrievalService.execute()` |
| §10.8.2 키워드 추출 배치화 ✅ | 청크 N개를 LLM 1콜로 | `KeywordExtractor` |
| §10.8.3 SQLite 배치삽입 트랜잭션화 ✅ | 배치 2개 → 트랜잭션 1개 | `SqliteVecVectorStoreProvider` |
| §10.8.4 SHA-256 중복 해싱 제거 ✅ | `precomputedSha256` 전달 | `IndexRequest` |
| §10.8.5 파생 텍스트 중복계산 제거 ✅ | `SEARCH_TEXT` 1회 계산 후 공유 | `SearchTextBuilder` |
| §10.9.1 Chroma 미사용 임베딩 제외 ✅ | 검색 응답 Include 필드 축소 | `ChromaVectorStoreProvider` |
| §10.9.2 벡터 BLOB 직렬화 ✅ | JSON 텍스트 → little-endian float32 BLOB | `toVectorBlob()` |
| §10.9.3 sqlite-vec 스트리밍 삽입 ✅ | 서브배치 임베딩 직후 즉시 삽입 | `SqliteVecVectorStoreProvider.add()` |
| §10.9.4 인덱싱-검색 캐시 분리 ✅ | `unwrapForIndexing()` + SHA-256 캐시 키 | `CachingEmbeddingModel` |

### 10.1~10.4 — Phase 7-A·B·C 정확도 기반 개선 ✅ 완료

세부 산출물은 위 표 참고. 보완 메모: §10.1 저장 텍스트는 원문 불변(맥락 헤더는 임베딩/FTS 입력에만). §10.1-보완은 재인덱싱 필요. §10.4 trigram 전환은 2글자 단독어를 여전히 놓쳐 §10.7.3 LIKE 폴백으로 별도 보완했다.

### 10.5 검토 후 제외 (Phase 7-D 취소)

원래 Phase 7-D(인프라 투자)로 묶였던 아래 3건은 재검토 결과 **범위 제외**한다 — 삭제가 아니라 판단 근거를 남겨, 아래 "재개 신호"가 실제로 관측되면 이 기록을 근거로 다시 꺼낸다.

| 제외 항목 | 원래 개선안 | 제외 사유 | 재개 신호 |
|---|---|---|---|
| **sqlite-vec 배치 검색 단일 스캔** | vec0 brute-force KNN(O(n))을 다중 쿼리 1회 스캔으로 최적화 | 현 코퍼스 규모에선 병목이 임베딩 배치 생성(1 HTTP)이고 JDBC 루프는 수 ms라 체감 이득이 작다(§11 "searchBatch N회" 리스크 항목과 동일 판단) | 대규모 코퍼스에서 검색 지연 실측 악화 |
| **Cross-Encoder 리랭커** | LLM 리랭커를 ONNX bge-reranker 등 로컬 cross-encoder로 교체 | ONNX 런타임/모델 도입 + 폐쇄망 모델 파일 조달 비용이 크고, 현 opt-in `Optional<RerankerService>` LLM 리랭커로 충분. **인터페이스 유지 구조라 필요 시 구현만 교체 가능**(지금 만들 이유는 없음) | LLM 리랭크 정확도/지연 불만이 실사용에서 반복 보고 |
| **시맨틱 응답 캐시** | 질문 임베딩 유사도 > 임계값이면 캐시 답변 반환 | stale 답변 위험 + 무효화 복잡도(재인덱싱·버전 변경·§6.8 DISLIKE 연동) 대비 이득이 불확실 | FAQ성 반복 트래픽이 지배적이고 지연이 문제화될 때 |

> **§10.10과의 관계**: 아래 §10.10(좋아요 기반 큐레이션 Q&A)이 "시맨틱 응답 캐시"의 stale 위험을 우회한 변형이다 — 캐시처럼 답변을 verbatim 반환하지 않고 인간이 편집·검수한 Q&A를 **가중 RRF 축의 근거**로만 주입하므로 무효화 복잡도가 낮다. 순수 자동 캐시(위 항목)는 여전히 재개 신호 대기.

### 10.7 검색 정확도 마무리 (Phase 7-E) ✅ 완료

세부 산출물은 위 표 참고. §10.7.5에서 실 코퍼스 골든셋(NEXCORE 문서 3종, 26문항) + recall@k/nDCG@k 평가 하네스(`src/test/.../evaluation/`, `-Dsearch-eval.enabled=true` 게이팅)로 **2026-07-16 실측 baseline: mean recall@10=0.962(25/26), nDCG@10=0.810**을 확보 — §10.7.2·§10.7.3의 무측정 결정을 데이터로 재검증했다. 유일한 미스는 `sample-02`("DM 간 호출 가능 여부"); 이 수치를 향후 검색 튜닝 변경의 회귀 비교 baseline으로 삼는다.

### 10.8 검색·인덱싱 속도 개선 (Phase 7-E) ✅ 완료

세부 산출물은 위 표 참고. 보완 메모: §10.8.2 배치 실패 시 곧장 개별 TF 폴백. §10.8.3 트랜잭션 결합으로 고아 vec_embeddings 행 방지.

### 10.9 메모리 최적화 (Phase 7-E) ✅ 완료

세부 산출물은 위 표 참고. 보완 메모: §10.9.1 벡터를 그대로 되돌려 쓰는 `updateTags()`는 영향 없음. §10.9.2 BLOB 직렬화는 기존 데이터와 호환(백필 불필요) — 폐쇄망 vec0 빌드의 BLOB 미지원 가능성은 낮지만 백엔드 전환 시 문서 1건으로 우선 확인 권장(OPERATOR_MANUAL.md 기록). §10.9.4 캐시 키는 SHA-256 해시로 고정 크기화.

### 10.10 좋아요 기반 큐레이션 Q&A 지식화 ✅ ①②③④ 전체 완료

Phase 7의 원래 17건 완료 **이후** 추가된 설계. 좋아요(👍)한 답변을 `curated_qa`로 스냅샷 → 예약 네임스페이스 `"curated"`에 임베딩 → 검색 시 **가중 RRF 축**으로 융합 → 본인(채팅 인라인) 또는 관리자(`/admin` 카드)가 편집·삭제. §10.5에서 제외한 "시맨틱 응답 캐시"의 **인간 검수 가능 버전** — verbatim 반환이 아니라 검색 축의 근거로만 주입하므로 stale 위험·무효화 복잡도가 낮다. 신규 프로바이더 메서드 없이 기존 인프라(§10.1 임베딩≠저장, §10.8.5 `SEARCH_TEXT` 오버라이드, `/admin/chunks` 편집 패턴, 소유권 체크)를 재사용. 구현 상세는 CLAUDE.md `CuratedQaService`/`CuratedQaRepository` 항목, 운영은 [OPERATOR_MANUAL §6.7·§7.5](OPERATOR_MANUAL.md), UI는 [UI.md](UI.md).

**확정된 정책**
- 가시성=전체 공유. 답변은 verbatim 반환이 아닌 **LLM 근거로 주입**(오답 증폭 방지, `/admin`에서 가중치·편집·삭제로 통제)
- 편집 권한=본인 OR 관리자
- **대화 삭제 시 큐레이션도 함께 회수**(§6.25에서 정책 변경) — 최초 정책은 반대였다("유지, 캐스케이드 안 함": 개인 대화 정리가 공유 검색 품질을 조용히 떨어뜨리는 것을 막기 위함). 바뀐 이유는 대화가 사라진 뒤에도 그 답변이 검색 근거로 남는 쪽이 더 혼란스럽고, 턴 단위 삭제(`deleteTurn`→`onUnlike`)는 처음부터 회수하고 있어 두 경로의 동작이 갈려 있었기 때문. 구현은 `CuratedQaService.onThreadDeleted()`이고 **사용자 경로와 관리자 경로가 함께** 호출한다. 승인된 청크 추가 제안(`origin='manual'`)은 대화 소속이 아니므로 제외된다. 열린 항목 (e)(프라이버시 트레이드오프)는 이 변경으로 해소됐다
- **문서 삭제와는 연동 없음** — `conversation_turns`가 턴별 출처 문서를 저장하지 않아 구조적으로 불가(열린 항목 (b))

**열린 항목**: (a) 큐레이션 축 similarity threshold 별도화 · (b) doc_id 인용 추적 + 문서 삭제 시 재검토 플래그(스키마 확장 필요, stale 인용이 실사용에서 보고되면 착수) · (c) BM25 축 편입 여부(정확도 실측 후) · (d) 회귀 검증은 §10.7.5 골든셋(recall@10=0.962) 재측정 · ~~(e) thread 삭제 시 큐레이션 유지의 프라이버시 트레이드오프~~ — §6.25에서 회수로 정책을 바꿔 해소.

---

## 11. 리스크 및 이슈

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
| 모바일 키보드 가림 | 중 | `100dvh` 단위로 레이아웃 고정(`app.css`). ⚠️ `visualViewport` API 기반 입력창 위치 보정은 **미구현** — 소프트 키보드 출현 시 하단 고정 여부는 실기기 검증 필요(상단 대시보드 "Phase 2 남은 실기기 검증 2건" 참조) |
| HTTPS 인증서 갱신 실패 | 중 | Caddy 자동 갱신 + 만료 30일 전 헬스체크 알림 |
| sqlite-vec — SQLite pool=1과 vec0 쓰기 충돌 | **고** | 기존 `busy_timeout=5000` 유효. vec0도 WAL 모드 하에서 동작하나 대규모 add() 시 write 홀딩 시간 측정 필요 |
| sqlite-vec — 차원수 불일치 | **고** | `vec_embeddings` 테이블 생성 시 `app.embedding.dimensions` 값으로 DDL 생성. 임베딩 모델 변경 시 DROP+재인덱싱 필수 — 자동 감지 불가 |
| sqlite-vec — 네이티브 바이너리 운영자 제공 | 중 | 공식 Java 아티팩트가 없어 운영자가 플랫폼별 `vec0` loadable을 배치하고 `SQLITE_VEC_EXTENSION_PATH`로 지정. Docker는 컨테이너 아키텍처(`linux/amd64` 등)에 맞는 바이너리 사용. 미설정/플랫폼 불일치 시 `SqliteVecVerifier`가 기동 시 fail-fast |
| sqlite-vec — searchBatch() N회 JDBC 쿼리 성능 | 낮 | 임베딩 배치 생성(1 HTTP 호출)이 병목. JDBC 쿼리 N번은 인메모리 SQLite 특성상 수 ms 수준으로 예상. 실측 후 CTE 방식 전환 검토 |
| sqlite-vec — Spring AI VectorStore 미지원 | 중 | 커스텀 `VectorStoreProvider` 구현으로 Spring AI 인터페이스 우회. Spring AI 공식 sqlite-vec 지원 시 마이그레이션 경로 단순화 |
| 태그 엄격 필터 — 결과 과소(recall 저하) | 중 | sqlite-vec 후보확대 보정(`candidateK` 단계 확대), AND 고정 1차 출시 후 OR 모드 후속 도입 |
| 프리릴리즈 수동 초기화 — 데이터 유실 위험 | 중 | 적용 전 운영자 체크리스트(백업 선택), 초기화 대상 경로 명시, 재업로드/동기화 검증 절차 문서화 |

---

## 11.1 예정 — Spring AI 2.0 업그레이드 & Chroma 버전 정책 🔜 미착수

### 배경 (현황 정정)

**Chroma v2 API는 이미 적용된 상태다.** Spring AI 1.1.8의 `ChromaApi`는 v2 경로만 호출하므로 이 앱은 **v1 전용 Chroma 서버(0.5.x 이하)에서는 동작하지 않는다** — "앞으로 v2로 올린다"가 아니라 "이미 v2만 쓴다"가 정확한 현황이다. 남아 있던 v1 잔재는 코드가 아니라 배포 설정이었고 둘 다 조치 완료: 헬스체크 경로 `/api/v1/heartbeat`→`/api/v2/heartbeat`(404로 컨테이너가 영영 `unhealthy`가 되어 `depends_on: service_healthy`가 통과되지 않던 문제), 이미지 태그 `:latest`→`1.0.21` 고정(README 예시 3곳 포함).

### 예정 작업

| 항목 | 내용 | 상태 |
|------|------|------|
| Chroma 태그 고정 | `:latest` → `1.0.21`, 헬스체크 v2 경로 | ✅ 완료 |
| Chroma 1.1.x 검증 | Docker Hub에 `1.1.0` 계열 존재. `ChromaApi`가 쓰는 v2 엔드포인트 스키마가 그대로인지 실기동 확인 후 태그 상향 | 🔜 |
| Spring AI 2.0 업그레이드 | `spring-ai.version` 1.1.8 → 2.0.x. 로컬 m2에 `spring-ai-chroma-store:2.0.0`이 이미 받아져 있어 API 차이 비교는 즉시 가능 | 🔜 |

### Spring AI 2.0 업그레이드 시 점검 포인트

이 프로젝트는 Spring AI의 자동설정을 대부분 **끄고** 직접 빈을 만들기 때문에, 업그레이드 리스크가 일반적인 경우와 다르다:

1. **`spring.autoconfigure.exclude` 클래스명 7종** — Chroma 1종 + OpenAI 모델 6종의 FQCN이 2.0에서 바뀌면 exclude가 조용히 무효가 되고, `LOCAL_LLM_KEY`가 빈 로컬 구성에서 `OpenAI API key must be set`로 기동이 깨진다(현재도 이 실패 클래스를 막으려고 넣은 설정).
2. **`ChromaApi`/`ChromaVectorStore` 빌더 시그니처** — `ChromaConfig`·`VectorStoreRegistry`·`ChromaVectorStoreProvider`가 직접 호출한다. 특히 `upsertEmbeddings()`(§10.1 embed≠store 분리를 위해 `VectorStore.add()`를 우회하는 지점)와 `QueryRequest.Include` 필드 선택.
3. **`OpenAiApi.builder()` / `OpenAiChatOptions`** — `LlmConfig`가 프로바이더마다 직접 조립. `ChatCompletionRequest` 직접 조립(`DirectAnswerService`의 스트리밍 우회 경로)도 동일.
4. **`MultiQueryExpander` / `spring-ai-rag`** — 프롬프트 템플릿 주입 방식(`promptTemplate`, `numberOfQueries`, `includeOriginal`) 변경 여부.
5. **`EmbeddingModel` 데코레이터 체인** — `Tracking`/`Caching`/`LoadBalancing` 3중 래핑이 인터페이스 변경에 직접 노출된다.
6. **`TokenCountBatchingStrategy`** — Chroma 경로의 서브배치 분할에 사용.

> 업그레이드는 별도 브랜치에서 `mvn -o test`(현재 1,232개) 전체 통과 + Chroma/sqlite-vec 양쪽 실기동 확인을 완료 조건으로 한다.

---

## 12. 의존성 변경 사항 (pom.xml)

> **압축**: 완료된 Phase의 의존성 이력·설정 덤프는 **pom.xml/application.properties 자체가 단일 출처**라 여기 중복 유지는 드리프트 위험만 키운다. 코드에서 바로 확인 안 되는, 실제로 유용한 사실만 남긴다.

- **`flyway-database-sqlite`는 불필요로 판명** — SQLite 마이그레이션 지원은 `flyway-core`에 이미 내장되어 있다. 다시 검토할 필요 없음.
- **`app.security.*` 외부화는 미착수** — BCrypt cost(12)는 `SecurityConfig.java`에, 로그인 잠금(5회/15분)은 `AuthEventListener.java`(`MAX_ATTEMPTS`/`LOCK_MINUTES`)에 상수로 하드코딩되어 있다. 외부화 필요 시 `AppProperties`에 `SecurityConfig` record + `securitySafe()` 가드를 추가하고 이 두 지점을 프로퍼티 참조로 교체.
- 그 외 전체 의존성/프로퍼티 목록은 pom.xml·application.properties 참조.

---

## 13. DB 스키마 변경 요약

> **신규 컬럼 추가 지침**: Flyway는 `V1__baseline`+`V2__users` 두 개만 존재하고, 그 이후 컬럼/인덱스(`user_id`, 피드백 컬럼 등)는 전부 **런타임 멱등 DDL**(`SqliteMemoryRepository`/`SqliteUserDetailsService`의 `CREATE TABLE IF NOT EXISTS` + `ALTER TABLE ADD COLUMN`)로 추가돼 왔다. **신규 컬럼은 새 Flyway 파일이 아니라 이 런타임 `ALTER TABLE ADD COLUMN` 패턴에 한 줄 추가**하는 것이 현재 코드와 정합적이다(멱등, 프리릴리즈 정책과도 부합). sqlite-vec 쪽 스키마(`vec_embeddings`/`vec_document_chunks`/`chunk_fts`)도 동일하게 Flyway가 아니라 `SqliteVecSchemaInitializer`의 동적 DDL(차원 파라미터화)로 관리된다.

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
