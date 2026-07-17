# RAG-Agent 온라인 확장 개발 계획

> Java 개발자 관점 · Spring Boot 3.5 + Spring AI 1.1 + Java 21 · 작성일 2026-05-11  
> **개발 기준 문서**: 이 파일(documents/PLAN.md)이 마스터. `documents/refactoring/18-extension-roadmap.md`는 각 항목의 기술 레퍼런스.

---

## 📊 전체 현황 대시보드

> 완료/미착수를 한눈에 보도록 상단 대시보드를 신설했다.

### ✅ 완료 — Phase 1 · 2 · 5 · 6 · 7 전체, Phase 3 대부분

| Phase | 완료 항목 | 상세 |
|---|---|---|
| **Phase 1** — 보안 기반 | Step 1.1~1.6 전체(Caddy·Flyway·Spring Security·멀티유저 격리·CSRF·로그인/회원가입 UI) + `app.auth.enabled` no-auth 토글 | §4 |
| **Phase 2** — 모바일 UI | 반응형 레이아웃(Offcanvas) · PWA(manifest/SW/오프라인) · 다크모드·접근성 | §5 |
| **Phase 3** — 운영 견고화 (12개 항목) | §6.1 Rate limit · §6.2 업로드 검증(매직바이트, 쿼터는 미착수) · §6.3 예외처리 · §6.4 감사로그 · §6.5 임베딩 사용량 분리 · §6.6 비활성 프로바이더 표시 · §6.7 orphan 기록 삭제 · §6.8 피드백 기반 컨텍스트 제외 · §6.9 요약 선계산 · §6.10 백그라운드 사용량 분리 · §6.11 컨텍스트 예산 정합성 · §6.12 다중 사용자 동시 LLM 처리(동시성 게이트+429 백프레셔+single-flight+서킷브레이커 완화+로드밸런싱) · §6.13 설정 페이지(LLM/RAG 조회+핫 수정 오버라이드 레이어) · §6.14 핵심 채팅 경로 추적 | §6 |
| **Phase 5** — Vector Store | Step 5.1~5.10 전체(Chroma↔sqlite-vec 런타임 전환, 관리자 페이지, 태그 검색, 운영/벡터 DB 분리) | §8 |
| **Phase 6** — 폐쇄망/노-도커 | G1~G5(키리스 LOCAL·차원 외부화·라우팅 외부화·런북·무외부호출 인수) | §9 |
| **Phase 7** — 검색 품질·성능 고도화 | §10.1~10.9 전체(17건) — 정확도(Contextual Retrieval·가중 RRF·쿼리 임베딩 캐시·한국어 FTS 트라이그램·리랭커 프리뷰 확장·하이브리드 기본화·2글자 LIKE 폴백·임계값 과조회 보정·recall@k/nDCG@k 평가 하네스) · 속도(MultiQuery 병렬화·키워드 배치화·SQLite 트랜잭션화·SHA-256 중복제거·파생텍스트 캐시) · 메모리(Chroma 미사용 임베딩 제외·벡터 BLOB 직렬화·sqlite-vec 스트리밍 삽입·인덱싱-검색 캐시 분리) | §10 |
| **§6.16.1** — 스트리밍/인덱싱 중단 버튼 | 채팅 SSE 중지(AbortController) + 업로드/동기화 취소(워커 스레드 interrupt, `.join()`→`.get()` 인터럽트 가능화) | §6.16 |
| **§6.17** — 문서관리·Admin 관리 전용 인증(B안) | `app.auth.management-only` 신규 서브모드, `SecurityConfig` 3번째 필터 체인(`IF_REQUIRED`+쿠키 CSRF), `/admin/**`+문서쓰기 5라우트 `hasRole("ADMIN")` 게이트, `NoAuthAutoLoginFilter` 실로그인 보존, 역할 기반 화면 분기(`isAdmin`) | §6.17 |

추가로 Phase 3 초기에 완료된 항목(문서화되지 않았던 픽스 포함): ChromaDB v2 API 컬렉션명→UUID 자동 변환, 문서 저장 경로 공유 구조 단순화(`DocRegistry.SHARED`), 인덱싱 SSE 진행 단계별 표시, 키워드 추출 타임아웃 시 CircuitBreaker 오동작 수정, DOCX 변환 전 구버전 아티팩트 삭제 순서 수정, `LOGGING_LEVEL`/`LLM_TEMPERATURE`/`LLM_MAX_TOKENS`/`SPRING_SECURITY_LOGGING_LEVEL` 환경변수 외부화, 의존성 최신 stable 일괄 업데이트(Spring Boot 3.5.15·Spring AI 1.1.8, 정확한 버전은 pom.xml 참조).

### 🔵 진행할 것 (우선순위 순)

> **2026-07-08 재우선순위화**: 현재 실배포 기준(폐쇄망·no-auth 단일 운영자)에서 가치가 없는 **멀티유저(`auth.enabled=true`) 전용 작업은 전부 후속으로 내렸다** — §6.19(보안 하드닝 3건 전부 auth 모드 전용), §6.20(사용자별 LLM 쿼터, "사용자별" 구분 자체가 다중 사용자 전제), §6.16.2(계정 잠금 피드백, no-auth엔 로그인이 없음), Phase 4 전체(OAuth2·Postgres·관리자 확장 모두 다중 사용자/스케일 트리거). §6.15(스토리지 쿼터)은 이름과 달리 설계상 (B) 전역 상한이 1차 권장이라 단일 운영자에도 그대로 적용되므로 즉시 그룹에 남겼다.

**🟢 지금 진행 (no-auth 단일 운영자 배포에도 바로 적용)**

| 순위 | 항목 | 현재 상태 |
|---|---|---|
| 1 | **§6.15 스토리지 쿼터**(전역 상한 B안, §6.2에서 이관) | 설계 완료, 구현 전. 설정 페이지(§6.13) 이후로 순위 하향 |
| 2 | 운영 준비 잔여 — SQLite 백업 자동화(Litestream/cron), Caddy 인증서 만료 모니터링 | 미착수 |
| 3 | §9.4 — CADDY 하위호환 별칭 | 선택, 낮은 우선순위 |
| 4 | Phase 2 남은 실기기 검증 2건 (키보드 하단 고정 · 홈 화면 standalone) | 좌우 스크롤·다크모드는 자동 검증 완료, 나머지는 실기기 필요 |
| 5 | **§6.18 Direct 메시지 전용 LLM Temperature 분리** | 미착수 (2026-07-09 요청, 낮은 우선순위). §6.13 설정 페이지 선행 완료 — 이제 진행 가능 |

> **§6.12 완료**: 다중 사용자 동시 LLM 요청 처리 — 채팅 경로 무제한 동시성(인덱싱만 세마포어 존재) → 슬롯 초과 시 429→서킷브레이커 전면차단·타임아웃 폭주 위험이었던 문제를 5단계로 해결. ① 프로바이더별 동시성 세마포어(`LlmRouter.acquirePermit`/`executeGated`, 채팅/질의 경로 전체 적용) ② 대기상한+429 백프레셔(`LlmBackpressureException`) ③ `CachingEmbeddingModel` in-flight single-flight(동일 텍스트 동시 요청 thundering herd 제거) ④ 폴백 없는 유일 프로바이더의 서킷브레이커 단축 차단(`blockForOverload`, 다중 분 단위 전면 다운 방지) ⑤ 동일 role·priority 프로바이더 로드밸런싱(least-in-flight, 처리량 수평 확장). 인덱싱/백그라운드 경로는 의도적으로 미적용(회귀 방지, 자체 세마포어 유지). 상세는 §6.12 본문 참조.
> **§6.13 완료**: `/settings` LLM/RAG 설정 조회 + 핫 수정 페이지. 상세는 §6.13 본문 참조.
> **§6.16.1 완료**: 채팅 스트리밍 중지 + 업로드/동기화 취소 버튼.
> **§6.17 완료**: 문서 관리·Admin 관리 전용 인증(B안) — `app.auth.management-only`. 상세는 아래 §6.17 본문 참조. (A) 전체 인증 모드는 §6.19와 함께 후속(멀티유저 활성화 시) 유지.
> **§6.18 추가 (낮은 우선순위)**: Direct(meta) 응답 전용 temperature를 RAG 응답과 분리해 0.0~0.2(기본 0.1) 범위로 화면에서 조정 가능하게. 조사 중 temperature가 현재 어디에서도 실제로 설정 가능하지 않다는(하드코딩) 선행 이슈를 발견 — 상세는 §6.18 본문 참조.
> **Phase 7 완료**: §10.1~10.9(17건) 전체 완료, §10.7.5 검색 품질 평가 하네스(recall@10=0.962 실측, 2026-07-16)로 마무리. 상세는 §10 본문 참조.

**🟣 후속 — 멀티유저(`auth.enabled=true`) 활성화 시에만 착수**

| 순위 | 항목 | 트리거 |
|---|---|---|
| 1 | **§6.19 보안 하드닝** — API CSRF/세션 혼용(6.19.1) · `/admin/**` ROLE_ADMIN 게이트(6.19.2) · XFF 무검증 rate limit(6.19.3) | **auth 모드 여는 시점에 반드시 선행**(게이트) — no-auth엔 노출면 없음 |
| 2 | **§6.20 사용자별 LLM 토큰 쿼터** | 실사용자가 여럿 생겨 사용량 격리가 필요해질 때 |
| 3 | **§6.16.2 계정 잠금 상태 피드백** | auth 모드 로그인 UX — no-auth엔 로그인 자체가 없음 |
| 4 | **Phase 4** (조건부) — §7.1 OAuth2 소셜 로그인 · §7.2 PostgreSQL 마이그레이션 · §7.3 관리자 페이지 확장 | §3 트리거 참조(가입 마찰·SQLite 한계 신호·다중 사용자 운영 관리 필요 시) |

> 검색 고도화 **Phase 7-D(인프라 투자: sqlite-vec 단일 스캔·cross-encoder 리랭커·시맨틱 응답 캐시)는 2026-07-08 재검토에서 범위 제외**했다(사유는 §10.5) — Phase 7의 유일한 미착수 항목이며, 그 외 §10.1~10.9는 전체 완료.

> 스키마 관리 실태: **Flyway(V1·V2 baseline) + 런타임 멱등 DDL 혼용**. `SqliteMemoryRepository`/`SqliteUserDetailsService`가 `CREATE TABLE IF NOT EXISTS` + `ALTER TABLE ADD COLUMN`으로 컬럼을 증분 추가한다(§13). 신규 컬럼은 새 Flyway 파일이 아니라 이 런타임 `ALTER TABLE` 패턴으로 추가한다.

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

> **2026-07-05 재배열**: 완료 항목을 앞쪽에(기반 운영 항목 → LLM 사용량 클러스터 → 대화/컨텍스트 클러스터), 미착수 항목을 뒤쪽에 우선순위 순으로 재배치했다. 번호가 이 문서 다른 곳(§7·§12·§13)에서도 참조되므로 교차 참조는 전부 새 번호로 갱신됨.

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

### 6.10 LLM 사용량 — 백그라운드(비-채팅) 사용량 분리 기록 ✅ 완료 (2026-07-05)

채팅 외 LLM 호출(요약·키워드추출·서식교정·TXT→MD·제목생성)을 `BackgroundUsage` 접두사(`summary:`/`keyword:`/`mdcorrect:`/`txt2md:`/`title:`)로 채팅과 분리 기록, `/llm-usage`에 `type=BACKGROUND` 카드로 노출. 조사 중 발견한 핵심 채팅 경로 자체의 추적 공백은 §6.14로 분리했다.

---

### 6.11 대화 컨텍스트 예산 정합성 + 설정 외부화 ✅ 완료 (2026-07-07)

§6.9 도입 후 요약 경로에는 없던 문자 예산 체크를 `MemoryService.maxConversationChars()`(단일 진실 원천, `max(1000, LLM_MAX_TOKENS × 0.75)`)로 통일하고, `FETCH_LIMIT` 등 하드코딩 5개 값을 `app.memory.*`/`app.summary.*` 프로퍼티로 외부화.

---

### 6.12 다중 사용자 동시 LLM 요청 처리 — 동시성 제어 + 처리량 확장 ✅ 완료

채팅 경로에 동시성 제한이 전혀 없어(세마포어는 인덱싱 경로에만 존재) 여러 사용자가 겹치면 폴백 없는 유일 프로바이더가 서킷브레이커로 전면 차단되던 문제를 5단계로 해결: ① 프로바이더별 동시성 세마포어(`LlmRouter.acquirePermit`/`executeGated`, `app.llm.providers[].concurrency`) ② 대기상한 초과 시 429 백프레셔(`LlmBackpressureException`, 서킷브레이커 없음) ③ `CachingEmbeddingModel` in-flight single-flight(동일 텍스트 중복요청 병합) ④ 폴백 없는 유일 프로바이더의 서킷브레이커 단축 차단(30초) ⑤ 동일 role·priority 프로바이더 간 least-in-flight 로드밸런싱. 인덱싱/백그라운드 경로는 기존 세마포어를 유지하며 미적용.

---

### 6.13 설정 페이지 — LLM/RAG 옵션 조회·부분 수정 ✅ 완료 (2026-07-14)

신규 `GET /settings`(게스트 조회 가능, 편집은 `isAdmin` 게이트)에서 LLM/임베딩/RAG 설정을 조회. 검색 튜닝 값(초기 7개 → 이후 topK·멀티쿼리 확장·하이브리드까지 10개)은 SQLite `settings_override` 테이블 기반 오버레이 레이어로 **재기동 없이 다음 검색부터 반영**(`RetrievalService`가 매 호출 `props.xxxSafe()`로 재조회하도록 변경). 이후 확장(Tier A/B)으로 인덱싱/청킹 값(청크 크기·오버랩·최소 크기·동시 파일 처리 수·동시 LLM 호출 수)도 핫 수정 대상이 되어, 소비처(`DocumentIndexer`·`MarkdownCorrectionService.correct()`·`LazyVisionService`)가 매 작업마다 재조회해 **다음 인덱싱/↺ 재인덱싱부터 반영**된다(`MarkdownCorrectionService`의 생성자 캐시를 제거해 세 소비처의 동작을 일치시킴). 빈 생성 시점에 고정되는 값(rerank-enabled·쿼리 임베딩 캐시 등)과 기본 라우팅 모드는 조회 전용. 수정은 `/admin/settings/update|reset`(§6.17 ROLE_ADMIN 상속) + 감사 로그 기록.

---

### 6.14 LLM 사용량 — 핵심 채팅 경로 추적 확장 ✅ 완료 (2026-07-06)

`AnswerService`/`DirectAnswerService`/`ClassifierService`/`RerankerService`/`VisionDescriptionService`/`ImageTypeClassifier`/`RetrievalService`의 `MultiQueryExpander` 7곳이 `LlmRouter`를 거치지 않아 실제 채팅 사용량이 `/llm-usage`에 전혀 안 잡히던 문제(사용자 실사용 중 보고)를 발견·수정 — 블로킹 호출은 `executeWithTracking()`으로, 스트리밍은 `LlmRouter.recordApproxUsage()`로, 프레임워크가 내부에서 자체 `ChatClient`를 구성해 가로챌 수 없는 `MultiQueryExpander`는 신규 `TrackingChatModel` 데코레이터로 해결.

---

### 6.15 사용자별 스토리지 쿼터 🔵 미착수 (§6.2에서 이관) — 지금 진행 (우선순위 3)

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

### 6.16 사용자 경험(UX) 개선 (2026-07-08 리뷰 도출) 🟡 일부 완료 (6.16.1)

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

### 6.18 Direct 메시지 전용 LLM Temperature 분리 🔵 미착수 — 지금 진행, 낮은 우선순위 (2026-07-09 요청)

**현재 상태 (코드 확인)**: temperature는 겉보기엔 `LLM_TEMPERATURE` 환경변수(`application.properties`의 `spring.ai.openai.chat.options.temperature=${LLM_TEMPERATURE:0.0}`, README.md/OPERATOR_MANUAL.md가 "0.0~2.0 조정 가능"이라 문서화)로 조정 가능해 보이지만, **실제로는 어디서도 이 값을 읽지 않는다** — `LlmRouter`가 실제로 선택하는 모든 provider `ChatModel`은 `LlmConfig.llmRouter()`(`config/LlmConfig.java:66-74`)가 기동 시점에 직접 생성하며, 그 `OpenAiChatOptions`에 `.temperature(0.0)`이 **하드코딩**되어 있다(CLAUDE.md의 "모든 LLM 프로바이더는 LlmRouter를 거쳐야 함" 원칙과 일치하는 구조지만, 결과적으로 `spring.ai.openai.*` 프로퍼티로 만들어지는 Spring AI 오토컨피규레이션 빈은 LlmRouter 경로에서 전혀 쓰이지 않아 `LLM_TEMPERATURE`가 죽은 설정이 됐다). `DirectAnswerService`(meta 질문 직접 응답, `service/DirectAnswerService.java:84-85` `buildPrompt()`)와 `AnswerService`(RAG 답변) 모두 이 하드코딩된 0.0을 그대로 물려받아 애초에 구분 자체가 없다.

**요청 배경**: meta 질문(인사·잡담 등, RAG 미사용 직접 응답)은 문서 근거가 없는 자유 응답이라 RAG 답변보다 약간의 다양성이 자연스러울 수 있어, Direct 경로만 별도로 0.0~0.2(기본 0.1) 범위에서 화면 조정 가능하게 하고 싶다는 요청.

**개선안**:
1. **선결 — temperature를 실제로 살아있는 설정으로 전환**: `LlmConfig.java:70`의 하드코딩된 `.temperature(0.0)`을 제거하고(provider별 `defaultOptions`는 유지하되 특정 고정값을 강제하지 않음), 실제 온도는 **호출 시점에 `Prompt`의 `ChatOptions`로 오버라이드**한다 — Spring AI는 `Prompt(messages, chatOptions)`에 실린 옵션이 모델 `defaultOptions`보다 우선 적용되므로, 같은 라우터/프로바이더 빈을 공유하면서도 호출부(Direct vs RAG)마다 다른 온도를 지정할 수 있다. §6.13의 "temperature/max-tokens 핫 수정 가능" 전제도 이 전환이 선행돼야 실제로 동작한다(지금은 빈 생성 시점에 고정이라 핫 리로드 자체가 물리적으로 불가능).
2. **신규 프로퍼티**: `app.llm.direct-temperature`(`AppProperties.LlmConfig`에 필드 추가, `DIRECT_LLM_TEMPERATURE` 환경변수) — 기본 `0.1`, `llmSafe()`에서 `[0.0, 0.2]`로 clamp. RAG 경로(`AnswerService`)는 선결 작업으로 "살아있게" 고친 뒤에도 기존 `LLM_TEMPERATURE`(기본 0.0)를 그대로 프로바이더 기본 온도로 계속 사용 — 이번 항목에서 RAG 쪽 값 자체는 새로 건드리지 않는다.
3. **적용 지점**: `DirectAnswerService`의 블로킹 경로(`buildPrompt()`가 만드는 `Prompt`)와 스트리밍 경로(`ChatClient.builder(provider.chatModel())...`) 양쪽에서 `OpenAiChatOptions.builder().temperature(directTemperature).build()`를 실어 보낸다.
4. **UI 노출**: 별도 화면을 새로 만들지 않고 §6.13 설정 페이지(신규 `/settings`)의 "핫 수정 가능" LLM 그룹에 슬라이더/숫자 입력(0.0~0.2, step 0.05, 기본 0.1)으로 포함한다 — §6.13이 이미 temperature를 핫 수정 대상으로 지목해뒀으므로 §6.13 구현 시 함께 추가하면 설정 저장·권한·감사 배관(§6.13 3)/4))을 중복 구축하지 않아도 된다. **§6.13 선행이 이 항목의 전제.**

**완료 기준**:
- Direct(meta) 응답과 RAG 응답이 서로 다른 temperature로 호출된다(`LoggingChatModel`의 curl 재현 로그로 확인 가능).
- `app.llm.direct-temperature`를 0.0~0.2 범위 밖 값으로 설정해도 clamp되어 기동/응답이 깨지지 않는다.
- §6.13 설정 페이지에서 값을 조정하면 재기동 없이 다음 Direct 호출부터 반영된다.
- "선결" 작업(온도 하드코딩 제거) 이후 `LLM_TEMPERATURE`가 RAG 경로에 처음으로 실제 적용되기 시작한다는 점을 동작 변경으로 명시 — 값 자체(기본 0.0)는 바뀌지 않으므로 즉시 체감 회귀는 없지만, 운영자가 과거에 설정해 둔 `LLM_TEMPERATURE`가 있다면 이번에 처음으로 실제 적용된다는 점을 릴리스 노트에 남긴다.

---

### 6.19 보안 하드닝 (2026-07-08 코드 리뷰 도출) 🔵 미착수 — 인증 활성 배포 시 우선순위(후속 1순위)

> **범위 주의**: 아래 3건은 **`app.auth.enabled=true`(멀티유저 인증) 배포에서만** 의미가 있다. 폐쇄망 단일 운영자(no-auth) 기본 배포에는 노출면이 없다 — 그래서 §6.20/6.15(쿼터)보다 뒤가 아니라 "인증 켜고 다중 사용자로 열 때 반드시 선행"으로 조건부 우선순위다. 각 항목은 실제 코드 위치와 재현 조건을 명시했다.

**6.19.1 `/api/v1/**` CSRF 비활성 + 세션 인증 혼용 (CSRF 노출)**
- **현재 코드**: `SecurityConfig`가 인증 모드에서 `csrf.ignoringRequestMatchers("/api/v1/**")`로 API 전체를 CSRF 예외 처리하지만, 같은 엔드포인트가 `anyRequest().authenticated()` — 즉 **세션 쿠키 인증**이다. `POST /api/v1/documents`는 `multipart/form-data`(CSRF "simple request")라 크로스사이트 폼으로 인증된 사용자의 브라우저에서 업로드를 유발할 수 있다(`DELETE`·JSON body 경로는 preflight로 상대적으로 안전).
- **개선안**: (A) API를 브라우저 세션이 아닌 **별도 인증(API 토큰/헤더)** 전용으로 못박고 CSRF 예외를 유지하거나, (B) 세션 인증을 계속 쓸 거면 `/api/v1/**`도 CSRF 토큰을 요구(현 HTMX/폼 경로 `/ui/**`는 이미 CSRF 적용). 최소 조치로 `POST /api/v1/documents`만이라도 CSRF 토큰 또는 커스텀 헤더 요구.
- **완료 기준**: 인증 모드에서 크로스사이트 폼 제출로 인증 사용자의 업로드/삭제가 트리거되지 않음(테스트로 재현→차단 확인). no-auth 모드 회귀 0.

**6.19.2 `/admin/**` 인가 공백 — 일반 인증 사용자도 관리 기능 접근**
- **현재 코드**: 인증 모드에서 ROLE_ADMIN을 요구하는 경로는 **`DELETE /admin/llm-usage/**` 하나뿐**(`SecurityConfig` 주석이 "나머지 `/admin/**`는 any authenticated user 유지"를 명시). 따라서 `/admin`(청크 브라우징), `DELETE /admin/chunks/{id}`, `POST /admin/chunks/{id}`(편집), `POST /admin/documents/{docId}/reindex`를 **로그인한 일반 사용자 누구나** 호출해 벡터 청크를 조회·수정·삭제·재인덱싱할 수 있다.
- **개선안**: `/admin/**` 전체를 `hasRole("ADMIN")`으로 게이트. no-auth 모드는 `NoAuthAutoLoginFilter`가 `/admin` 요청을 첫 ADMIN으로 자동 인증하므로 무영향. 단, `AdminController`/`AdminService` 테스트에 비관리자 403 케이스 추가 필요.
- **완료 기준**: 인증 모드에서 비-ADMIN 사용자의 `/admin/**` 접근이 403. no-auth 모드 관리자 자동 인증 회귀 0.

**6.19.3 Rate limit — `X-Forwarded-For` 무검증 신뢰 (per-IP 제한 우회)**
- **현재 코드**: `RateLimitFilter.clientKey()`가 익명 요청에서 `X-Forwarded-For`의 첫 값을 무조건 클라이언트 IP로 사용. 리버스 프록시가 이 헤더를 덮어쓰지 않으면 공격자가 매 요청 XFF를 바꿔 **로그인 per-IP 브루트포스 제한(분당 10회/IP)을 우회**할 수 있다.
- **개선안**: Tomcat `RemoteIpValve`(이미 `server.tomcat.remoteip.protocol-header` 설정 존재)로 신뢰 프록시 홉만 XFF를 해석하게 하고 `req.getRemoteAddr()`를 신뢰 원천으로 사용, 또는 `app.rate-limit.trust-forwarded-for`(기본 false) 플래그로 XFF 신뢰를 명시적 옵트인. 폐쇄망 직노출(프록시 없음) 시엔 XFF 신뢰 끄기가 안전 기본값.
- **완료 기준**: 프록시 없는 배포에서 XFF 조작으로 per-IP 제한이 뚫리지 않음. Caddy 배포에서 실제 클라이언트 IP 정상 인식(기능 회귀 0).

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

- **5.1** Chroma 호출을 `VectorStoreProvider` 인터페이스 뒤로 이전(동작 변화 없는 순수 리팩토링).
- **5.2** `load_extension()`으로 운영자 제공 `vec0` 바이너리 로드, `SqliteVecVerifier`가 기동 시 `vec_version()` 확인 후 fail-fast.
- **5.3** 차원이 DDL 상수라 Flyway 대신 동적 DDL. 벡터(`vec_embeddings`)/텍스트·메타(`vec_document_chunks`) 분리 후 `spring_doc_id` JOIN. 차원 미설정 시 fail-fast.
- **5.4** `SqliteVecVectorStoreProvider`: version을 partition key로 KNN 필터링, cosine→유사도 변환(Chroma와 동일 스케일). upsert 미지원이라 add=DELETE 후 INSERT.
- **5.5** `VectorStoreProviderConfig`가 `app.vectorstore.type`으로 택일(기본 chroma), Chroma 전용 빈은 `@ConditionalOnProperty` 가드.
- **5.6** `chroma` 서비스를 compose profile로 분리, sqlite-vec 모드는 무-Chroma 기동.
- **5.7** 백엔드 전환=재인덱싱(원본 보존, 무손실). `SqliteVecIntegrationTest`(vec0 없으면 skip)로 E2E 검증.
- **5.8** sqlite-vec 모드에서 비어있던 청크 브라우징을 `VectorStoreAdminView`로 해결(백엔드 공통 상태 카드 + CRUD 패리티).

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
| §10.7.5 검색 품질 평가 하네스 ✅ | 골든셋 26문항 + recall@k/nDCG@k | `evaluation/` (2026-07-16) |
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

청크 맥락 헤더 주입(§10.1, ROI 1순위 — 대명사·표·코드 조각처럼 단독으로 모호한 청크의 재현율 개선, 저장 텍스트는 원문 불변)과 임베딩 입력 정규화(§10.1-보완, 재인덱싱 필요), RRF 벡터/키워드축 가중 정규화(§10.2, BM25 구조적 저평가 해소), 반복 질의 재임베딩 제거(§10.3), 한국어 활용형·코드 부분열 매칭을 위한 trigram FTS 전환(§10.4, 2글자 단독어는 무손실 재구축이라도 놓침 — §10.7.3 LIKE 폴백으로 보완).

### 10.5 검토 후 제외 (Phase 7-D 취소)

원래 Phase 7-D(인프라 투자)로 묶였던 아래 3건은 재검토 결과 **범위 제외**한다 — 삭제가 아니라 판단 근거를 남겨, 아래 "재개 신호"가 실제로 관측되면 이 기록을 근거로 다시 꺼낸다.

| 제외 항목 | 원래 개선안 | 제외 사유 | 재개 신호 |
|---|---|---|---|
| **sqlite-vec 배치 검색 단일 스캔** | vec0 brute-force KNN(O(n))을 다중 쿼리 1회 스캔으로 최적화 | 현 코퍼스 규모에선 병목이 임베딩 배치 생성(1 HTTP)이고 JDBC 루프는 수 ms라 체감 이득이 작다(§11 "searchBatch N회" 리스크 항목과 동일 판단) | 대규모 코퍼스에서 검색 지연 실측 악화 |
| **Cross-Encoder 리랭커** | LLM 리랭커를 ONNX bge-reranker 등 로컬 cross-encoder로 교체 | ONNX 런타임/모델 도입 + 폐쇄망 모델 파일 조달 비용이 크고, 현 opt-in `Optional<RerankerService>` LLM 리랭커로 충분. **인터페이스 유지 구조라 필요 시 구현만 교체 가능**(지금 만들 이유는 없음) | LLM 리랭크 정확도/지연 불만이 실사용에서 반복 보고 |
| **시맨틱 응답 캐시** | 질문 임베딩 유사도 > 임계값이면 캐시 답변 반환 | stale 답변 위험 + 무효화 복잡도(재인덱싱·버전 변경·§6.8 DISLIKE 연동) 대비 이득이 불확실 | FAQ성 반복 트래픽이 지배적이고 지연이 문제화될 때 |

### 10.7 검색 정확도 마무리 (Phase 7-E) ✅ 완료

리랭커 프리뷰 확장(§10.7.1, 200→500자+구조적 헤더), 하이브리드 검색 기본 활성화(§10.7.2, `SEARCH_HYBRID_ENABLED` false→true — `chunk_fts`는 플래그와 무관하게 항상 채워지고 있었으므로 이미 지불한 인덱싱 비용 회수), 2글자 한국어 질의의 BM25 0건 반환을 `LIKE` 보조 스캔으로 완화(§10.7.3, `KeywordSearchRepository.search()` 내부 국한), 유사도 임계값 활성 시 후보 풀 과조회 보정(§10.7.4, `topK×2`)까지 거친 뒤, §10.7.5에서 실 코퍼스 골든셋(NEXCORE 문서 3종 기반 26문항) + recall@k/nDCG@k 평가 하네스(`src/test/.../evaluation/`, `SqliteVecIntegrationTest`와 동일하게 `-Dsearch-eval.enabled=true` 게이팅, 검색만 호출하는 읽기 전용)를 구축해 **2026-07-16 실측 baseline: mean recall@10=0.962(25/26), nDCG@10=0.810**을 확보했다 — §10.7.2·§10.7.3의 무측정 결정을 데이터로 재검증 완료. 유일한 미스는 `sample-02`("DM 간 호출 가능 여부"), 향후 검색 튜닝 변경의 회귀 비교 baseline으로 이 수치를 기준 삼는다.

### 10.8 검색·인덱싱 속도 개선 (Phase 7-E) ✅ 완료

MultiQuery 확장 최소 길이 상향(0→15, `app.search-multiquery-min-length`) + 원본 질의 검색을 확장 LLM 호출과 가상 스레드로 병렬 실행해 크리티컬 패스 단축(§10.8.1), 청크 N개(기본 4)를 번호 매긴 프롬프트로 묶어 배치당 1회 호출해 인덱싱 LLM 왕복을 `ceil(청크수/N)`로 감소(§10.8.2, `KeywordExtractor.enrichKeywordsBatch()`, 배치 실패 시 곧장 개별 TF 폴백), sqlite-vec의 `vec_embeddings`+`vec_document_chunks` 배치 삽입 2개를 `TransactionTemplate`으로 결합해 중간 실패 시 고아 행 방지(§10.8.3), 디렉터리 동기화의 SHA-256 이중 해싱을 `IndexRequest.precomputedSha256` 전달로 제거(§10.8.4), 임베딩+FTS 파생 텍스트를 청크당 1회만 계산해 `MetaKey.SEARCH_TEXT`로 공유(§10.8.5, 영속 전 제거).

### 10.9 메모리 최적화 (Phase 7-E) ✅ 완료

Chroma 배치 검색이 `mapPerQuery()`가 쓰지 않는 임베딩 필드까지 응답으로 받아오던 것을 `RESULT_INCLUDE` 상수로 축소(§10.9.1, 리랭크 활성 시 검색 1회당 ~1MB 절감; 벡터를 그대로 되돌려 쓰는 `updateTags()`는 영향 없음), sqlite-vec 벡터를 JSON 텍스트 리터럴(~15KB) 대신 little-endian float32 BLOB(~6KB, `toVectorBlob()`)로 직렬화(§10.9.2, 기존 데이터와 호환되어 백필 불필요 — 폐쇄망 vec0 빌드의 BLOB 미지원 가능성은 낮지만 백엔드 전환 시 문서 1건으로 우선 확인 권장, OPERATOR_MANUAL.md에 기록), 대형 문서 `add()`가 전체 임베딩을 힙에 모은 뒤 일괄 삽입하던 것을 토큰 서브배치 단위로 임베딩 직후 즉시 삽입하는 스트리밍 구조로 전환해 피크 메모리를 문서 크기 대신 서브배치 크기에 비례하게 함(§10.9.3, §10.8.3의 트랜잭션 결합은 서브배치 단위로 유지), 인덱싱 청크 임베딩이 `CachingEmbeddingModel.unwrapForIndexing()`으로 질의 캐시를 우회해 대량 인덱싱이 직전 검색 캐시를 밀어내지 않도록 함(§10.9.4, 캐시 키도 SHA-256 해시로 고정 크기화).

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

## 12. 의존성 변경 사항 (pom.xml)

> **2026-07-08 대폭 압축**: 완료된 Phase의 의존성 이력·설정 덤프는 **pom.xml/application.properties 자체가 단일 출처**라 여기 중복 유지는 드리프트 위험만 키운다. 코드에서 바로 확인 안 되는, 실제로 유용한 사실만 남긴다.

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
