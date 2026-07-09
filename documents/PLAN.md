# RAG-Agent 온라인 확장 개발 계획

> Java 개발자 관점 · Spring Boot 3.5 + Spring AI 1.1 + Java 21 · 작성일 2026-05-11  
> **개발 기준 문서**: 이 파일(documents/PLAN.md)이 마스터. `documents/refactoring/18-extension-roadmap.md`는 각 항목의 기술 레퍼런스.

---

## 📊 전체 현황 대시보드

> 완료/미착수를 한눈에 보도록 상단 대시보드를 신설했다. 완료 항목의 서술은 아래 본문(§4~§9)에서 대폭 압축했지만 **항목 자체는 삭제하지 않았다** — 절 번호(§6.5, Step 5.3 등)는 교차 참조 보존을 위해 그대로 유지했으므로, 이 번호로 본문을 검색(Ctrl+F)하면 상세 구현 내용을 바로 찾을 수 있다. 2026-07-05 재배열 이력은 §6 도입부에 계속 남아 있다.

### ✅ 완료 — Phase 1 · 2 · 5 · 6 전체, Phase 3 대부분, Phase 7-A · 7-B

| Phase | 완료 항목 | 상세 |
|---|---|---|
| **Phase 1** — 보안 기반 | Step 1.1~1.6 전체(Caddy·Flyway·Spring Security·멀티유저 격리·CSRF·로그인/회원가입 UI) + `app.auth.enabled` no-auth 토글 | §4 |
| **Phase 2** — 모바일 UI | 반응형 레이아웃(Offcanvas) · PWA(manifest/SW/오프라인) · 다크모드·접근성 · 전체 286 tests 검증 | §5 |
| **Phase 3** — 운영 견고화 (12개 항목) | §6.1 Rate limit · §6.2 업로드 검증(매직바이트, 쿼터는 미착수) · §6.3 예외처리 · §6.4 감사로그 · §6.5 임베딩 사용량 분리 · §6.6 비활성 프로바이더 표시 · §6.7 orphan 기록 삭제 · §6.8 피드백 기반 컨텍스트 제외 · §6.9 요약 선계산 · §6.10 백그라운드 사용량 분리 · §6.11 컨텍스트 예산 정합성 · §6.14 핵심 채팅 경로 추적 | §6 |
| **Phase 5** — Vector Store | Step 5.1~5.10 전체(Chroma↔sqlite-vec 런타임 전환, 관리자 페이지, 태그 검색, 운영/벡터 DB 분리) | §8 |
| **Phase 6** — 폐쇄망/노-도커 | G1~G5(키리스 LOCAL·차원 외부화·라우팅 외부화·런북·무외부호출 인수) | §9 |
| **Phase 7-A** — 검색 빠른 승리 | §10.2 가중 RRF(벡터축 그룹 정규화 + 키워드축 가중치/k 외부화) · §10.3 쿼리 임베딩 캐시(Caffeine, cache→tracking→delegate 데코레이터) | §10 |
| **Phase 7-B** — Contextual Retrieval + 임베딩 입력 정규화 | §10.1 청크 맥락 헤더(구조적+LLM) 임베딩/FTS 입력에 prepend, `context:` 사용량 분리 · §10.1-보완 마크다운 장식 제거 정규화(임베딩/FTS/답변프롬프트 3곳 공유), 저장·표시 텍스트는 원문 불변 | §10 |
| **§6.16.1** — 스트리밍/인덱싱 중단 버튼 | 채팅 SSE 중지(AbortController) + 업로드/동기화 취소(워커 스레드 interrupt, `.join()`→`.get()` 인터럽트 가능화) | §6.16 |

추가로 Phase 3 초기에 완료된 항목(문서화되지 않았던 픽스 포함): ChromaDB v2 API 컬렉션명→UUID 자동 변환, 문서 저장 경로 공유 구조 단순화(`DocRegistry.SHARED`), 인덱싱 SSE 진행 단계별 표시, 키워드 추출 타임아웃 시 CircuitBreaker 오동작 수정, DOCX 변환 전 구버전 아티팩트 삭제 순서 수정, `LOGGING_LEVEL`/`LLM_TEMPERATURE`/`LLM_MAX_TOKENS`/`SPRING_SECURITY_LOGGING_LEVEL` 환경변수 외부화, 의존성 최신 stable 일괄 업데이트(Spring Boot 3.5.15·Spring AI 1.1.8, 정확한 버전은 pom.xml 참조).

### 🔵 진행할 것 (우선순위 순)

> **2026-07-08 재우선순위화**: 현재 실배포 기준(폐쇄망·no-auth 단일 운영자)에서 가치가 없는 **멀티유저(`auth.enabled=true`) 전용 작업은 전부 후속으로 내렸다** — §6.15(보안 하드닝 3건 전부 auth 모드 전용), §6.12(사용자별 LLM 쿼터, "사용자별" 구분 자체가 다중 사용자 전제), §6.16.2(계정 잠금 피드백, no-auth엔 로그인이 없음), Phase 4 전체(OAuth2·Postgres·관리자 확장 모두 다중 사용자/스케일 트리거). §6.13(스토리지 쿼터)은 이름과 달리 설계상 (B) 전역 상한이 1차 권장이라 단일 운영자에도 그대로 적용되므로 즉시 그룹에 남겼다.

**🟢 지금 진행 (no-auth 단일 운영자 배포에도 바로 적용)**

| 순위 | 항목 | 현재 상태 |
|---|---|---|
| 1 | **Phase 7-C** — §10.4 한국어 FTS 토크나이저 | 미착수 — §10.2·§10.1과 세트로 하이브리드 기본 활성화 재검토, Phase 7-B에 이어 진행 |
| 2 | **§6.17 문서관리·Admin 인증 필수화 + 역할 분기** | 미착수 (2026-07-09 요청, 중간 순위). 관리 전용 인증(B안)은 no-auth에서도 착수 가능 |
| 3 | **§6.18 설정 페이지** — LLM/RAG 옵션 조회·부분(핫) 수정 | 미착수 (2026-07-09 요청, 중간 순위). 수정 권한 게이트가 §6.17의 관리자 역할 분기에 의존 — §6.17 선행 필요 |
| 4 | **§6.13 스토리지 쿼터**(전역 상한 B안, §6.2에서 이관) | 설계 완료, 구현 전. 2026-07-09 설정 페이지(§6.18) 이후로 순위 하향 |
| 5 | 운영 준비 잔여 — SQLite 백업 자동화(Litestream/cron), Caddy 인증서 만료 모니터링 | 미착수 |
| 6 | §9.4 — CADDY 하위호환 별칭 | 선택, 낮은 우선순위 |
| 7 | Phase 2 남은 실기기 검증 2건 (키보드 하단 고정 · 홈 화면 standalone) | 좌우 스크롤·다크모드는 2026-07-08 자동 검증 완료, 나머지는 실기기 필요 |

> **Phase 7-A 완료 (2026-07-08)**: §10.2 가중 RRF + §10.3 쿼리 임베딩 캐시. 상세는 아래 §10.2·§10.3 본문 참조.
> **Phase 7-B 완료 (2026-07-09)**: §10.1 Contextual Retrieval + §10.1-보완 임베딩 입력 정규화. 상세는 아래 §10.1 본문 참조. 재인덱싱은 운영 단계에서 별도 수행.
> **§6.16.1 완료 (2026-07-08)**: 채팅 스트리밍 중지 + 업로드/동기화 취소 버튼. 상세는 아래 §6.16.1 본문 참조.
> **§6.17·§6.18 추가 (2026-07-09 요청, 중간 순위)**: 민감 페이지(문서 관리·Admin) 로그인 필수화 + 역할 기반 화면 분기(§6.17), LLM/RAG 설정 조회·부분 수정 페이지(§6.18).
> **2026-07-09 재우선순위화**: 검색 정확도(Phase 7-B·7-C)를 최상단으로 승격 — 코드 변경 범위가 이미 확정돼 있고(§10.1·§10.4 본문) 체감 ROI가 가장 크다. §6.17/§6.18 순서를 뒤집었다 — §6.18 본문("수정은 관리자 전용, §6.17 역할 분기와 연동")이 §6.17의 admin role 개념을 전제하므로 개발 순서상 §6.17이 선행돼야 한다. §6.13(스토리지 쿼터)은 설정 페이지(§6.18) 이후로 순위를 낮췄다 — 즉시 필요한 안전장치라기보단 운영 편의 항목이라는 재판단. Phase 7-B는 같은 날 구현 완료.

**🟣 후속 — 멀티유저(`auth.enabled=true`) 활성화 시에만 착수**

| 순위 | 항목 | 트리거 |
|---|---|---|
| 1 | **§6.15 보안 하드닝** — API CSRF/세션 혼용(6.15.1) · `/admin/**` ROLE_ADMIN 게이트(6.15.2) · XFF 무검증 rate limit(6.15.3) | **auth 모드 여는 시점에 반드시 선행**(게이트) — no-auth엔 노출면 없음 |
| 2 | **§6.12 사용자별 LLM 토큰 쿼터** | 실사용자가 여럿 생겨 사용량 격리가 필요해질 때 |
| 3 | **§6.16.2 계정 잠금 상태 피드백** | auth 모드 로그인 UX — no-auth엔 로그인 자체가 없음 |
| 4 | **Phase 4** (조건부) — §7.1 OAuth2 소셜 로그인 · §7.2 PostgreSQL 마이그레이션 · §7.3 관리자 페이지 확장 | §3 트리거 참조(가입 마찰·SQLite 한계 신호·다중 사용자 운영 관리 필요 시) |

> 검색 고도화 **Phase 7-D(인프라 투자: sqlite-vec 단일 스캔·cross-encoder 리랭커·시맨틱 응답 캐시)는 2026-07-08 재검토에서 범위 제외**했다(사유는 §10.5). Phase 7은 7-A~C(§10.1~10.4)만 유효.

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
| Phase 7 — 검색 품질·성능 고도화 | 가중 RRF·쿼리 임베딩 캐시(7-A) · Contextual Retrieval(7-B) · 한국어 FTS(7-C) | 중요 | 🟡 7-A·7-B 완료, 7-C 미착수 |

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

> **2026-07-05 재배열**: 완료 항목을 앞쪽에(기반 운영 항목 → LLM 사용량 클러스터 → 대화/컨텍스트 클러스터), 미착수 항목을 뒤쪽에 우선순위 순으로 재배치했다. 번호가 이 문서 다른 곳(§7·§12·§13)에서도 참조되므로 교차 참조는 전부 새 번호로 갱신됨.

### 6.1 Rate Limiting — Bucket4j ✅ 완료

`RateLimitFilter`(`OncePerRequestFilter`, `SecurityFilterChain` 앞단 등록)가 엔드포인트별 인메모리 버킷을 적용 — 채팅 분당 20회/userId, 업로드 분당 5회/userId, 로그인 분당 10회/IP, 전체 익명 분당 30회/IP. 다중 인스턴스 확장 시 Redis 백엔드로 전환 필요(부록 참조).

### 6.2 파일 업로드 보안 강화 🟡 부분 완료 (리팩토링 03, 12)

**✅ 완료**
- 확장자 화이트리스트: `pdf, pptx, docx, txt, md`
- **매직바이트 검증** — `security/FileTypeDetector.matches(path, ext)`(Tika 아님, pom에 Tika 의존성 없음). 임시파일 기록 후 검증, 불일치 시 422
- 파일명 sanitize — `Path.normalize()` + 화이트리스트 정규식
- 경로 이탈 방지 — 공유 저장소 `data/documents/`(per-user 격리 폐기, `DocRegistry.SHARED`) 기준 `startsWith()` 검증

**🔵 미착수** — 사용자별 누적 용량 쿼터 → §6.13으로 이관·구체화(현재 `storage_used_bytes` 컬럼·쿼터 로직·`app.upload-quota` 프로퍼티 모두 없음).

### 6.3 글로벌 예외 처리 ✅ 완료

`@RestControllerAdvice`(`GlobalExceptionHandler`) 기반 RFC 9457 ProblemDetail 응답 — `RagException` 서브클래스는 자체 `httpStatus()`로, `MaxUploadSizeExceededException`(413)·`IllegalArgumentException`(400)·미처리 예외(500, `RAG-INT-001`)는 개별 핸들러로 매핑. HTMX 요청엔 `HX-Reswap: none` 헤더 추가.

### 6.4 감사 로그 ✅ 완료 (리팩토링 14 — Logback 파일 롤링)

SQLite `audit_log` 테이블 대신 Logback `SizeAndTimeBasedRollingPolicy`로 구현.
- `data/audit/audit.log` — NDJSON 포맷 (jq 분석 가능)
- 일별 로테이션 + 10MB 분할, gzip 압축, 7일 자동 삭제, 100MB 전체 상한
- `application.properties`로 모든 파라미터 조정 가능, `app.audit.enabled=false`로 즉시 비활성
- 이벤트 8개 기록: upload×2, delete×2, sync×2, routing-mode, thread-delete

### 6.5 LLM 사용량 — 임베딩 사용량 분리 ✅ 완료

`TrackingEmbeddingModel`(`llm` 패키지)이 `EmbeddingModel`을 데코레이트해 `embed:<model>` 이름으로 채팅과 분리 기록(`embed()` 계열은 결국 `call()`을 거치므로 자동 추적, `dimensions()`만 미추적). usage 미반환 시 `app.embedding.usage-fallback-enabled`(기본 true)로 chars/4 근사 폴백. `/llm-usage` 카드·표·차트 3경로 모두 `type=EMBEDDING`으로 분리 표시(차트는 별도 Chart.js stack 그룹). 부수로 `/api/llm/usage` 경로 오타(항상 404였던 기존 버그) 발견·수정. 테스트 +8(전체 350), LM Studio 실사용 검증 완료(회귀 0).

### 6.6 LLM 사용량 — 비활성 프로바이더 조건부 표시 ✅ 완료

`LlmUsageRepository.usedProviders()` + `OperationsController.visibleChatProviders()` 공통 필터(`configured || 사용이력있음`)로 카드·표·차트 3경로를 통일 — 키 없는 프로바이더는 이력 없으면 숨기고 있으면(과거 사용 후 키 제거) 계속 표시, 활성 프로바이더는 항상 표시. §6.5의 `embed:*`는 이 필터 대상이 아니라 항상 표시. 테스트 +5(전체 355), 재기동 전/후 비교로 3곳 동시 숨김/노출을 실사용 검증(회귀 0).

### 6.7 LLM 사용량 — 설정에 없는(orphan) 프로바이더 기록 삭제 ✅ 완료

`OperationsController.orphanProviderNames()` = 사용 이력 있는 provider 전체 − 현재 config provider − 현재 임베딩 모델명(활성 `embed:<model>`은 보호, 옛 임베딩 모델명은 삭제 허용). 카드·표·차트 세 경로에 `type=ORPHAN`·`deletable=true`로 노출하고, `DELETE /admin/llm-usage/{provider:.+}`(관리자 전용, orphan 아니면 400 거부 + 감사로그)로 `LlmUsageRepository.deleteByProvider()` 삭제. `/admin/**` 경로라 `NoAuthAutoLoginFilter`의 기존 no-auth 관리자 인증을 그대로 상속. 테스트 +9(전체 364), 가짜 orphan 행 삽입 → 카드 노출 → 삭제 → DB 반영까지 브라우저 실사용 검증(회귀 0).

### 6.8 Chat 응답 피드백(좋아요/싫어요) 기반 컨텍스트 제외 ✅ 완료

Assistant 응답에 👍/👎 토글 추가, `conversation_turns.feedback`(런타임 컬럼, `NULL|LIKE|DISLIKE`) + `PATCH /ui/threads/{threadId}/turns/{turnId}/feedback`. `DISLIKE` turn은 `getHistory()` SELECT에서 하드 제외(`LIKE`는 저장만, 아직 미소비). HTMX/DUAL/서버복원/SSE 4개 렌더 경로가 공통 마크업 + `#chat-messages` 위임 리스너 하나로 클릭 처리 통일. DUAL 모드는 외부 답변 turn만 저장(로컬 답변엔 피드백 없음). 테스트 +9(전체 335), LM Studio 실사용 검증 완료.

### 6.9 입력 시작 시 로컬 요약 선계산 + 중복 제거 컨텍스트 압축 ✅ 완료

입력 시작 시(첫 글자, 세션당 1회) `POST /ui/chat/summary/precompute`가 가상 스레드로 발화 — `ConversationSummarizerService`가 turn을 정규화 중복제거(§6.8 DISLIKE 제외 동일 적용) 후 LOCAL 프로바이더로 요약, 스레드별 LRU 캐시(최대 3개, TTL 15초)에 저장. 캐시 있으면 "요약+최근 2턴", 없으면(미계산·실패·LOCAL 미가용) 기존 `getHistory()`로 조용히 폴백하고, `addTurn()` 직후 캐시를 무효화해 재생성한다. 테스트 +14(전체 354), LM Studio 실사용 검증(요약 생성 → 다음 질문 회상) 완료(회귀 0).

### 6.10 LLM 사용량 — 백그라운드(비-채팅) 사용량 분리 기록 ✅ 완료 (2026-07-05)

채팅 외 LLM 호출(요약·키워드추출·서식교정·TXT→MD·제목생성) 5곳 점검 결과, 4곳은 추적은 되나 채팅과 provider명이 섞여 구분 불가했고 대화 제목 생성(`ThreadMetaService`)은 `LlmRouter`를 안 거쳐 추적 자체가 안 되고 있었음. §6.5의 `embed:` 접두사 선례를 확장해 `BackgroundUsage` 클래스에 `summary:`/`keyword:`/`mdcorrect:`/`txt2md:`/`title:` 5개 접두사를 정의하고, `LlmRouter.executeWithTracking()`에 `usageLabelPrefix` 4-인자 오버로드를 추가(기존 3-인자는 유지). `ThreadMetaService`는 직접 `ChatClient` 의존을 `LlmRouter`로 교체해 신규 추적 편입. `OperationsController`가 `type=BACKGROUND` 카드로 노출(`deletable=false`, orphan 판정에서는 제외 — embed:와 동일한 함정 방지). 전체 436 tests BUILD SUCCESS, 임시 행 삽입으로 BACKGROUND 카드 렌더를 실사용 검증(회귀 0). 조사 중 발견한 더 큰 범위(핵심 채팅 경로 자체의 추적 공백)는 §6.14로 분리했다.

---

### 6.11 대화 컨텍스트 예산 정합성 + 설정 외부화 ✅ 완료 (2026-07-07)

**배경**: §6.9 도입 후 이전 대화를 프롬프트에 넣는 경로가 둘로 나뉘었는데, 문자 예산 체크가 폴백 경로(`MemoryService.getHistory()`)에만 있고 요약 경로(`ConversationSummarizerService.buildContext()`)엔 없어 — 최근 답변이 길면 요약 경로가 오히려 더 큰 컨텍스트를 보낼 수 있는 불일치가 있었다. 또한 `FETCH_LIMIT`·캐시 크기·TTL 등 5개 값이 하드코딩이라 배포 환경별 조정이 불가했다.

**구현**: 단일 진실 원천 `MemoryService.maxConversationChars()`(= `max(1000, LLM_MAX_TOKENS × 0.75)`)를 신설해 양쪽 경로가 동일 예산을 쓰도록 통일(요약 블록은 항상 보존, 최근 원문은 "최신 우선 채움"으로 남은 예산만 채움, 극단적으로 작은 예산은 최종 결과 하드 캡). `FETCH_LIMIT`(50) 등 5개 하드코딩 상수를 `app.memory.*`/`app.summary.*` 프로퍼티로 외부화 + `memorySafe()`/`summarySafe()` null 가드 추가(미설정 시 기존값과 동일). 전체 461 tests BUILD SUCCESS(+2 신규 예산 일관성 테스트, 회귀 0).

---

### 6.12 사용자별 LLM 사용량 쿼터 🔵 미착수 — 다음 우선순위 1순위

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

### 6.13 사용자별 스토리지 쿼터 🔵 미착수 (§6.2에서 이관) — 다음 우선순위 2순위

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

### 6.14 LLM 사용량 — 핵심 채팅 경로 추적 확장 ✅ 완료 (2026-07-06)

§6.10 조사 중 `AnswerService`/`DirectAnswerService`/`ClassifierService`/`RerankerService`/`VisionDescriptionService`/`ImageTypeClassifier`/`RetrievalService`의 `MultiQueryExpander` 7곳이 `LlmRouter`를 거치지 않는 직접 `ChatClient` 호출이라 실제 채팅 사용량이 `/llm-usage`에 전혀 안 잡히던(embed:만 증가) 문제를 사용자가 실사용 중 재현·보고(2026-07-06).

**1차(핵심 3곳, 당일 즉시 수정)**: `ClassifierService`/`AnswerService`/`DirectAnswerService`의 블로킹 호출을 `executeWithTracking()`(실사용량)으로, 스트리밍 분기는 신규 `LlmRouter.recordApproxUsage()`(§6.5와 동일한 chars/4 근사)로 교체. LM Studio로 Direct+RAG 질문(총 7회 호출) 전송 → `local` 프로바이더가 정상 누적됨을 실사용 검증.

**2차(잔여 4곳, 같은 날 마저 정리)**: `RerankerService`/`ImageTypeClassifier`/`VisionDescriptionService`는 동일하게 `executeWithTracking()`으로 교체. `MultiQueryExpander`(Spring AI 프레임워크 유틸, 내부에서 자체 `ChatClient` 구성이라 가로챌 수 없음)는 `TrackingEmbeddingModel`과 동일한 데코레이터 패턴의 신규 `TrackingChatModel`을 만들어 주입.

관련 테스트 9개 파일 갱신/신설(기존 테스트 전무했던 2곳 포함), 전체 466 tests BUILD SUCCESS(회귀 0).

---

### 6.15 보안 하드닝 (2026-07-08 코드 리뷰 도출) 🔵 미착수 — 인증 활성 배포 시 우선순위

> **범위 주의**: 아래 3건은 **`app.auth.enabled=true`(멀티유저 인증) 배포에서만** 의미가 있다. 폐쇄망 단일 운영자(no-auth) 기본 배포에는 노출면이 없다 — 그래서 §6.12/6.13(쿼터)보다 뒤가 아니라 "인증 켜고 다중 사용자로 열 때 반드시 선행"으로 조건부 우선순위다. 각 항목은 실제 코드 위치와 재현 조건을 명시했다.

**6.15.1 `/api/v1/**` CSRF 비활성 + 세션 인증 혼용 (CSRF 노출)**
- **현재 코드**: `SecurityConfig`가 인증 모드에서 `csrf.ignoringRequestMatchers("/api/v1/**")`로 API 전체를 CSRF 예외 처리하지만, 같은 엔드포인트가 `anyRequest().authenticated()` — 즉 **세션 쿠키 인증**이다. `POST /api/v1/documents`는 `multipart/form-data`(CSRF "simple request")라 크로스사이트 폼으로 인증된 사용자의 브라우저에서 업로드를 유발할 수 있다(`DELETE`·JSON body 경로는 preflight로 상대적으로 안전).
- **개선안**: (A) API를 브라우저 세션이 아닌 **별도 인증(API 토큰/헤더)** 전용으로 못박고 CSRF 예외를 유지하거나, (B) 세션 인증을 계속 쓸 거면 `/api/v1/**`도 CSRF 토큰을 요구(현 HTMX/폼 경로 `/ui/**`는 이미 CSRF 적용). 최소 조치로 `POST /api/v1/documents`만이라도 CSRF 토큰 또는 커스텀 헤더 요구.
- **완료 기준**: 인증 모드에서 크로스사이트 폼 제출로 인증 사용자의 업로드/삭제가 트리거되지 않음(테스트로 재현→차단 확인). no-auth 모드 회귀 0.

**6.15.2 `/admin/**` 인가 공백 — 일반 인증 사용자도 관리 기능 접근**
- **현재 코드**: 인증 모드에서 ROLE_ADMIN을 요구하는 경로는 **`DELETE /admin/llm-usage/**` 하나뿐**(`SecurityConfig` 주석이 "나머지 `/admin/**`는 any authenticated user 유지"를 명시). 따라서 `/admin`(청크 브라우징), `DELETE /admin/chunks/{id}`, `POST /admin/chunks/{id}`(편집), `POST /admin/documents/{docId}/reindex`를 **로그인한 일반 사용자 누구나** 호출해 벡터 청크를 조회·수정·삭제·재인덱싱할 수 있다.
- **개선안**: `/admin/**` 전체를 `hasRole("ADMIN")`으로 게이트. no-auth 모드는 `NoAuthAutoLoginFilter`가 `/admin` 요청을 첫 ADMIN으로 자동 인증하므로 무영향. 단, `AdminController`/`AdminService` 테스트에 비관리자 403 케이스 추가 필요.
- **완료 기준**: 인증 모드에서 비-ADMIN 사용자의 `/admin/**` 접근이 403. no-auth 모드 관리자 자동 인증 회귀 0.

**6.15.3 Rate limit — `X-Forwarded-For` 무검증 신뢰 (per-IP 제한 우회)**
- **현재 코드**: `RateLimitFilter.clientKey()`가 익명 요청에서 `X-Forwarded-For`의 첫 값을 무조건 클라이언트 IP로 사용. 리버스 프록시가 이 헤더를 덮어쓰지 않으면 공격자가 매 요청 XFF를 바꿔 **로그인 per-IP 브루트포스 제한(분당 10회/IP)을 우회**할 수 있다.
- **개선안**: Tomcat `RemoteIpValve`(이미 `server.tomcat.remoteip.protocol-header` 설정 존재)로 신뢰 프록시 홉만 XFF를 해석하게 하고 `req.getRemoteAddr()`를 신뢰 원천으로 사용, 또는 `app.rate-limit.trust-forwarded-for`(기본 false) 플래그로 XFF 신뢰를 명시적 옵트인. 폐쇄망 직노출(프록시 없음) 시엔 XFF 신뢰 끄기가 안전 기본값.
- **완료 기준**: 프록시 없는 배포에서 XFF 조작으로 per-IP 제한이 뚫리지 않음. Caddy 배포에서 실제 클라이언트 IP 정상 인식(기능 회귀 0).

---

### 6.16 사용자 경험(UX) 개선 (2026-07-08 리뷰 도출) 🟡 일부 완료 (6.16.1)

**6.16.1 스트리밍 답변·인덱싱 중단(취소) 컨트롤 부재 ✅ 완료 (2026-07-08)**
- **현재 상태**: 채팅 SSE 스트리밍(`chat-stream.js`)과 문서 인덱싱 진행(`documents.html`) 모두 시작 후 사용자가 멈출 방법이 없다 — 잘못 보낸 긴 질문이나 대용량 인덱싱을 끝까지 기다려야 하고, 서버 자원도 계속 소모된다. `StreamingAgentService`는 이미 `emitter.onError/onTimeout`에서 워커를 `interrupt()`하므로 클라이언트가 SSE를 닫으면 중단 훅은 존재한다.
- **개선안**: 스트리밍 답변 버블에 "중지" 버튼 → `EventSource.close()` + (필요 시) 서버에 취소 신호. 인덱싱은 업로드 행별 취소가 이상적이나, 최소한 진행 중 SSE 구독 해제 + 서버측 가상 스레드 `interrupt` 경로 정리. 부분 답변은 이미 에러 시 저장 로직(`StreamingAgentService`)이 있어 재사용 가능.
- **완료 기준**: 사용자가 스트리밍/인덱싱을 즉시 중단할 수 있고, 서버 워커도 실제로 종료된다(좀비 스레드 없음).

**구현**:
- **채팅 스트리밍**: `chat-stream.js`가 `fetch`+`ReadableStream`(EventSource 아님) 기반이라 원안의 `EventSource.close()` 대신 `AbortController`를 사용 — 전송 버튼이 스트리밍 중 "중지" 버튼으로 전환(아이콘·`aria-label`·`btn-danger` 스타일 교체)되고, 클릭 시 `abortController.abort()`. `AbortError`는 일반 오류와 분리해 `onAborted()`로 처리해 그때까지 스트리밍된 부분 답변은 마크다운 렌더링을 보존하고 "사용자가 중단함" 메타 텍스트만 추가. 서버 측 `emitter.onError` 훅이 이미 워커 가상 스레드를 `interrupt()`하므로 fetch abort만으로 서버 작업도 함께 종료됨(신규 서버 코드 불필요).
- **인덱싱 취소**: 신규 `IndexingCancelledException`(unchecked, `RagException` 계층 밖 — HTTP 응답으로 노출되지 않는 내부 제어 신호) + `IndexingProgressEvent.cancelled()`(신규 terminal stage). `IndexingProgressService`가 taskId→워커 `Thread` 맵을 추가로 관리(`registerWorker()`), 신규 `cancel(taskId)`가 워커를 `interrupt()`하고 즉시 terminal `cancelled` 이벤트를 publish해 클라이언트 SSE 구독이 워커의 실제 종료를 기다리지 않고 바로 끝남. 신규 엔드포인트 `POST /ui/documents/progress/{taskId}/cancel`.
  - **인터럽트가 실제로 먹히게 하는 수정**(이게 핵심 난이도) — `KeywordExtractor.enrichParallel()`과 `DocumentIndexer.syncDirectory()`의 병렬 대기가 각각 `CompletableFuture.join()`/`CompletableFuture.allOf().join()`을 쓰고 있었는데, `join()`은 **호출 스레드가 인터럽트되어도 반응하지 않는다**(non-interruptible) — 즉 워커를 `interrupt()`해도 이 두 지점에서는 조용히 무시되어 취소가 먹히지 않았다. `.get()`(interruptible)으로 교체하고, `InterruptedException` catch 시 각각의 내부 executor(`exec`/`filePool`)를 `shutdownNow()`(+`syncDirectory`는 `awaitTermination`까지)해 실제 in-flight 가상 스레드까지 인터럽트가 전파되도록 했다. `syncDirectory()`는 취소 시점까지 완료된 파일은 `docRegistry.save()`로 보존하고(3단계 삭제감지는 스킵) `IndexingCancelledException`을 던진다.
  - `KeywordExtractor.enrichKeywords()`의 기존 자체 타임아웃 메커니즘(`finally { Thread.interrupted(); }`으로 인터럽트 플래그를 지움)은 그대로 뒀다 — 취소로 인한 인터럽트가 이 지점에 도달해도 해당 청크는 TF 폴백으로 빠르게 완료되어 반환되므로(좀비 없이 스레드가 정상 종료), 별도 처리 없이도 "완료 기준"을 만족함을 테스트로 확인.
  - `documents.html`: 파일 목록 행의 4번째 `<td>`(기존 "제거" ✕ 버튼)를 업로드/인덱싱 중에는 취소 버튼으로 재활용(taskId 확정 전엔 `xhr.abort()`, 이후엔 `POST .../cancel`), 동기화 진행바 옆에도 취소 버튼 추가. `cancelled` stage는 오류(빨강)와 구분되는 중립(회색) 상태로 렌더링.
- 신규 테스트 8개(`KeywordExtractorTest` 인터럽트 케이스, `DocumentIndexerTest` sync 취소 시 부분 registry 보존 케이스, `IndexingProgressServiceTest` 3건) + 프리뷰 브라우저로 채팅 중지 버튼(실제 SSE 스트림 abort → "사용자가 중단함" 렌더 확인)과 문서 업로드 행 취소 버튼(DOM 와이어링) 실사용 검증. 전체 518 tests BUILD SUCCESS(회귀 0).

**6.16.2 계정 잠금 상태 피드백 부재**
- **현재 상태**: 로그인 5회 실패 시 15분 잠금(`AuthEventListener`)되지만, 잠긴 뒤에도 로그인 화면은 일반 "이메일 또는 비밀번호가 올바르지 않습니다" 문구만 보여준다(`formLogin.failureUrl("/login?error")` 고정). 사용자는 자신이 잠겼는지, 언제 풀리는지 알 수 없다(문구가 "5회 실패 시 15분 잠금"을 안내는 하지만 현재 잠금 상태/해제 시각은 아님).
- **개선안**: `LockedException` 분기를 별도 실패 핸들러로 잡아 "N분 후 해제" 메시지 전달(`locked_until` 조회). 잠금 남은 시간은 이미 `SqliteUserDetailsService`가 관리 중.
- **완료 기준**: 잠긴 계정 로그인 시 잠금 상태 + 해제 예정 시각이 구분되어 표시된다.

---

### 6.17 문서 관리·Admin 접근 인증 필수화 + 역할 기반 화면 분기 🔵 미착수 — 중간 순위 (2026-07-09 요청)

**현재 상태**: no-auth 기본 배포에서 `NoAuthAutoLoginFilter`가 모든 경로에 guest 신원을, `/admin/**`엔 첫 `ADMIN` 사용자를 자동 주입한다 — 즉 **문서 관리(`/documents`, `/ui/documents/**` 업로드·삭제·동기화)와 `/admin`(청크 열람·수정·삭제·재인덱싱)에 로그인 없이 접근**된다. 인증 모드(`auth.enabled=true`)에서도 `/admin/**`은 `DELETE /admin/llm-usage/**`만 `ROLE_ADMIN`이고 나머지는 "인증된 사용자 누구나"라(§6.15.2) 일반 사용자가 벡터 청크를 조작할 수 있다. 화면도 인증 여부(`GlobalModelAdvice.authEnabled`)만 구분할 뿐 **관리자/일반 역할에 따른 분기가 없다**.

**개선안 (2방향 — 배포 형태에 따라 택1)**:
- **(A) 전체 인증 모드**: `auth.enabled=true`로 열고 모든 경로를 로그인 뒤로. 이 경우 §6.15(하드닝 3건)이 **선행 게이트**이고, `/admin/**` 전체를 `hasRole("ADMIN")`으로 좁히는 §6.15.2가 이 절의 핵심 조각이 된다. → 후속(멀티유저) 성격.
- **(B) 관리 전용 인증 (single-operator에서도 즉시 적용 가능, 권장 1차)**: 채팅·조회는 no-auth로 열어두되 **`/documents`(쓰기)와 `/admin/**`만 로그인 요구**. 신규 플래그(예: `app.auth.management-only`)로 `NoAuthAutoLoginFilter`가 이 두 경로군만 pass-through에서 제외하고 로그인으로 리다이렉트, 나머지는 기존 guest 자동 인증 유지. 공용/외부 노출 채팅 데모에서 관리 기능만 잠그는 실용적 중간 지점.

**역할 기반 화면 분기**:
- `GlobalModelAdvice`에 현재 사용자 role(`isAdmin`) 모델 속성 추가(현 `authEnabled`와 동일하게 per-request, 목 NPE 안전).
- 관리자: 청크 편집/삭제/재인덱싱, 전체 LLM 사용량(§6.5~6.7), 감사 로그(§6.4) 뷰, 설정 수정(§6.18) 노출.
- 일반 사용자: 문서 목록·상태 조회 한정(업로드 허용/차단 정책 택1), `/admin` 미노출, 사용량은 본인 것만/미노출.
- 템플릿(`documents.html`/`admin.html`/`base.html` 내비)에 `th:if="${isAdmin}"` 게이트. **서버 측 인가(§6.15.2)가 1차 방어선**이고 화면 분기는 UX 보조임을 명시(클라이언트 숨김만으로 인가 대체 금지).

**§6.15.2·§7.3와의 관계**: 이 절이 "무엇을 잠그고 누가 무엇을 보는가"의 상위 방향이고, §6.15.2(`/admin/**` ROLE_ADMIN 인가)와 §7.3(관리자 페이지 기능 확장)이 그 구현 조각이다. 중복 구현 금지 — 인가 규칙은 §6.15.2, 기능 확장은 §7.3을 따른다.

**완료 기준**:
- 선택한 방향(A/B)에서 문서 관리·`/admin` 접근이 비로그인/비관리자에게 거부된다(서버 인가 테스트로 재현→차단).
- 관리자/일반 로그인 시 화면 구성 요소가 역할에 맞게 달라진다.
- no-auth 순수 채팅 배포(관리 인증 미사용)는 회귀 0.

---

### 6.18 설정 페이지 — LLM/RAG 옵션 조회·부분 수정 🔵 미착수 — 중간 순위 (2026-07-09 요청)

**현재 상태**: 모든 설정이 `application.properties` + 환경변수 → `AppProperties`(`@ConfigurationProperties(prefix="app")`) + `xxxSafe()` 접근자 경로로만 존재한다. 운영자가 **현재 유효 설정을 한 화면에서 확인하거나, 재기동 없이 조정할 방법이 없다**. `/llm-usage`(사용량)·`/admin`(벡터 스토어 상태)은 있지만 LLM 라우팅/RAG 검색 튜닝 값 자체를 보여주는 뷰는 없다.

**개선안**:

*1) 조회 뷰 (신규 `/settings` 또는 `/admin/settings`)* — 현재 유효값을 그룹별로 표시.
- LLM: 등록 프로바이더·role, 기본 라우팅 모드, temperature, max-tokens, circuit-breaker 상태.
- 임베딩: 모델명, 차원(`app.embedding.dimensions`).
- RAG 검색(모두 `app.search-*`): topK, hybrid-enabled, rerank-enabled, similarity-threshold, multiquery-enabled/min-length, RRF keyword-weight/k, candidate-multiplier, tag-candidate-multiplier, retry-escalate.
- 쿼터(§6.12/6.13 도입 시)·쿼리 임베딩 캐시(§10.3) 파라미터.

*2) 수정 가능 범위 — 런타임 안전 항목만*:
- **핫 수정 가능**(매 호출 `props.xxxSafe()`로 새로 읽는 값): 검색 튜닝 수치(similarity-threshold, RRF keyword-weight/k, candidate-multiplier, tag-candidate-multiplier, retry-escalate, multiquery-min-length), 기본 라우팅 모드, temperature/max-tokens. → 오버라이드 레이어만 있으면 재기동 없이 반영.
- **재기동 필요(수정 UI 잠금 + 값만 노출)**: `rerank-enabled`·`hybrid-enabled`·`vectorstore.type`(모두 `@ConditionalOnProperty` 빈 생성 시점에 결정 — 런타임 토글 불가), `embedding.dimensions`(sqlite-vec DDL 상수), `auth.enabled`(필터 체인 구성), DB 경로. 이들은 조회 + "재기동 필요" 배지만.

*3) 오버라이드 저장 방식*:
- SQLite **설정 오버라이드 테이블**(key→value, 런타임 멱등 DDL §13 패턴) + `xxxSafe()` 접근자가 **오버라이드 우선 → 없으면 프로퍼티 기본값** 순으로 조회하도록 소량 개편. 재기동 후에도 오버라이드 유지, 삭제 시 프로퍼티 기본값 복귀.
- 대안(부적합): Spring `@RefreshScope`/Environment 재바인딩은 `@ConfigurationProperties` 즉시 반영 보장이 약하고 프리릴리즈 규모엔 과함. env 재주입은 재기동 전제라 목적 미충족.

*4) 권한·감사*:
- 수정은 **관리자 전용**(§6.17 역할 분기와 연동), 조회 노출 범위는 정책 택1. 설정 변경은 §6.4 `AuditLogger`에 이벤트로 기록(변경 키·이전값·새값).

**완료 기준**:
- 운영자가 `/settings`에서 유효 LLM/RAG 설정을 확인하고, 핫 수정 가능 항목을 바꾸면 **재기동 없이** 다음 검색/응답에 반영된다.
- 재기동 필요 항목은 수정 UI가 막히고 사유가 표시된다.
- 오버라이드가 재기동 후에도 유지되고, 삭제 시 프로퍼티 기본값으로 정확히 복귀한다(회귀 0).
- 설정 변경이 감사 로그에 남는다.

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

G1~G4 코드/문서 완료. G5는 라우팅 계층 "외부 무선택"을 `LlmConfigTest.airGappedNeverRoutesToExternal`로 결정적 검증. 전체 286 tests BUILD SUCCESS(sqlite 통합 2개는 vec0 바이너리 없을 때 skip). vec0 필요한 sqlite-vec 라이브 부팅·실소켓 0 측정은 운영 인수.

---

## 10. Phase 7 — 검색 품질·성능 고도화 🟡 일부 완료 (7-A · 7-B)

> **배경 (2026-07-07 코드 확인)**: 검색 파이프라인(`RetrievalService`)은 이미 MultiQuery(원본+2) → 배치 임베딩 → 벡터 검색 → RRF 융합 → (옵션)하이브리드 BM25 → (옵션)LLM 리랭크 → 태그 필터로 잘 구성돼 있다. 아래는 "빠진 것"이 아니라 **현 구조 위에서 정확도/성능을 끌어올리는 증분 개선**이며, 자바 관점의 난이도·회귀 리스크와 함께 우선순위화했다. 자체 검색 품질 평가 세트가 없으므로 각 항목의 효과는 도입 후 정성/정량 측정으로 검증하는 것을 전제로 한다.

### 10.1 Contextual Retrieval — 청크 맥락 주입 (정확도 ROI 1순위) ✅ 완료 (2026-07-09)

**현재 상태**: `ChunkSplitter`(섹션 병합 + 슬라이딩 윈도우, 순수 텍스트 로직)는 각 청크의 자기 텍스트만 임베딩 대상으로 넘긴다. "이 청크가 어느 문서·상위 섹션의 무엇인지"가 벡터에 반영되지 않아, 대명사·표·코드 조각 청크의 검색 recall이 낮다.

**개선안 (2단계 — 무비용 하한선 + LLM 강화)**:
1. **구조적 맥락 (LLM 0, baseline)**: `ChunkSplitter`가 이미 섹션 인지 병합을 하므로 각 청크가 속한 `{문서명} > {상위 섹션 경로}` 헤더를 **결정적으로** 임베딩 입력 앞에 prepend한다. LLM 호출 0, 재인덱싱만으로 즉시 얻는 하한선이자 아래 2의 폴백.
2. **LLM 맥락 강화 (enrichment)**: 그 위에 청크별 1~2문장 맥락을 LLM으로 생성해 얹는다. 이미 `KeywordExtractor`가 청크별 LLM 호출(+TF 폴백)을 수행하므로 **같은 호출에서 keywords + context를 함께 추출**하면 인덱싱 왕복 추가가 없다. 생성 실패 시 1의 구조적 맥락으로 폴백. 백그라운드 사용량은 `BackgroundUsage`(§6.10)에 `context:` 접두사로 분리 기록.

**Contextual BM25 시너지**: Anthropic 원기법은 맥락 텍스트를 임베딩뿐 아니라 **BM25 인덱스에도** 넣어 lexical recall까지 올린다. 여기서도 맥락 텍스트를 `chunk_fts` content로 함께 넣으면 §10.4·§10.2와 맞물려 하이브리드 효과가 증폭된다. 화면 표시용 원문은 그대로 두고, 맥락은 **임베딩+FTS 입력에만** 확장한다.

**Spring AI 위치 확인 (Context7)**: Spring AI 1.1의 `RetrievalAugmentationAdvisor`/`RewriteQueryTransformer`/Query Expansion은 전부 **쿼리 측(pre-retrieval)** 증강이라 이 인덱스 측 기법과 층이 다르다 → `KeywordExtractor` 내 커스텀 구현이 정답(프레임워크가 대체 안 함).

**효과**: 공개 벤치 검색 실패율 대폭 감소(문헌상 ~35%, 맥락+Contextual BM25 결합 기준). 정확도 ROI 1순위.
**비용/리스크**: 인덱싱 1회성 LLM 토큰 증가(구조적 맥락만 쓰면 0), 검색 지연 0, 재인덱싱 필요.
**완료 기준**: 신규 청크의 임베딩·FTS 입력에 맥락이 포함되고, `context:` 사용량이 `/llm-usage`에 분리 집계되며, LLM 맥락 실패 시 구조적 맥락(1)만으로 폴백(회귀 0).

**구현**: `KeywordExtractor`의 기존 키워드 추출 LLM 호출을 확장해 "키워드: .../맥락: ..." 두 줄 형식으로 키워드+맥락을 한 번에 받는다(로컬 모델 호환을 위해 JSON이 아닌 평문 파싱 유지, 마커 없는 레거시 응답은 하위호환으로 전체를 키워드로 처리). 구조적 맥락(`{filename} > {heading}` — `MetaKey.HEADING`으로 기존 raw `"heading"` 문자열을 승격)은 항상 계산되고, LLM 맥락 문장이 있으면 그 위에 얹는다(합본은 `MetaKey.CHUNK_CONTEXT`에 저장 — transient, 영속 메타데이터에는 남지 않음). 사용량은 `BackgroundUsage.CONTEXT_PREFIX`(`context:`)로 통합 기록(`keyword:`는 과거 기록 인식용으로 유지). 신규 `SearchTextBuilder`(`CHUNK_CONTEXT + 정규화 본문`)를 임베딩(양쪽 `VectorStoreProvider`) + `chunk_fts.content` 세 곳에서 공유. Chroma는 `VectorStore.add()` 위임(임베딩=저장 텍스트 강제라 분리 불가)을 수동 임베딩 + `chromaApi.upsertEmbeddings()`로 교체하고 `TokenCountBatchingStrategy` 서브배치를 이식(저장 content/메타데이터는 원문 그대로, `CHUNK_CONTEXT`는 영속 전 제거); sqlite-vec은 기존 수동 임베딩 구조에 파생 텍스트만 교체. 전체 565 tests BUILD SUCCESS(회귀 0).

**10.1-보완 — 임베딩 입력 정규화 (마크다운 노이즈 제거, 2026-07-09 추가)**

> §10.1과 같은 재인덱싱 사이클에 묶어 구현한다(따로 하면 대용량 문서를 두 번 재인덱싱). §10.1이 임베딩 입력에 맥락을 **더하는** 것이라면, 이 항목은 노이즈를 **빼는** 것 — 둘 다 "저장용 원문 ≠ 임베딩 입력" 동일 원칙("화면 표시용 원문은 그대로, 임베딩+FTS 입력만 가공") 위의 작업이다.

- **배경**: 마크다운 장식 문자는 임베딩 좌표 계산에 기여하지 않으면서 토큰과 청크 크기 예산을 소모한다. 대상 두 부류 —
  - (a) **장식 줄**: 텍스트 없는 반복 기호 줄(`# ======`, `----------`, `###########` 등 구분선/ASCII 괘선) → 줄 전체 제거.
  - (b) **강조 마커**: bold/기울임/밑줄 표기 기호(`**굵게**`, `*기울임*`, `<u>밑줄</u>` 등) → **마커만 제거하고 안의 텍스트는 보존**(`**중요**` → `중요`).
  - 반대로 **구조 마크다운은 보존**: 제목 텍스트(`## 설정 방법`의 "설정 방법"), 리스트, 표, 그리고 **코드펜스 내부는 무조건 무변경**(코드 안의 `*`·`#`은 의미 있는 문자).
- **설계 (3계층 텍스트 분리)**:

  | 용도 | 텍스트 | 비고 |
  |---|---|---|
  | 저장·표시 (`vec_document_chunks.content`, 출처 아코디언, `/admin` 청크 뷰) | 원문 그대로 | 변경 없음 — 사람 가독성용 |
  | 임베딩 + FTS 입력 | 정규화 텍스트 | 장식 줄 제거 + 강조 마커 제거 + 공백 정리 |
  | 답변 프롬프트 [Retrieved Documents] (`AnswerService`) | 정규화 텍스트 | LLM에게 장식은 불필요 — 같은 topK로 컨텍스트 절약, 기존 20,000자 `truncate()` 캡은 최후 방어선으로 유지 |

- **청크 수 감소 메커니즘**: 분할 경계는 원문 기준으로 유지하되, 크기 예산(`chunkSize`/`maxChunkChars`) **측정만 정규화 길이로** 수행 — 장식이 예산을 소모하지 않아 청크당 실질 내용이 늘고 전체 청크 수가 줄어든다(원문↔정규화 오프셋 매핑 같은 복잡한 구조 불필요).
- **구현 위치**: 정규화 함수는 `ChunkSplitter` 옆 순수 유틸(빈 의존성 0)로 1개 신설, 임베딩·FTS·답변 프롬프트 3곳이 공유. 과잉 제거 방지(표 구분행 `|---|`·코드펜스 내부 보존)를 단위 테스트로 고정.
- **부수 효과**: 임베딩 입력 축소로 read-timeout 재발 확률 감소(§10.3 아래 배치 분할과 상승 작용), FTS BM25 통계의 장식 문자 오염 제거.
- **완료 기준**: 동일 문서 재인덱싱 시 청크 수가 감소하고, 출처 표시·관리자 뷰에는 원문이 그대로 보이며, 코드펜스 내부가 변형되지 않음(테스트로 고정). 답변 프롬프트 토큰이 동일 topK 대비 감소.

**구현**: 신규 순수 유틸 `MarkdownNoiseNormalizer`(코드펜스 내부·표 행 무변경, 장식 줄 전체 제거, 볼드/이탤릭/밑줄 마커만 제거하고 안쪽 텍스트 보존)를 임베딩+FTS 입력(`SearchTextBuilder` 경유)과 답변 프롬프트(`AnswerService.buildAnswerPrompt()` — 맥락 헤더 없이 정규화만) 두 곳에서 공유. 저장·표시 텍스트(`Document.getText()`, 벡터스토어 content, 출처 아코디언·`/admin` 뷰)는 원문 불변. 청크 크기 예산은 `ChunkSplitter.mergeShortSections()`(병합 임계값 비교 — 매 반복 재정규화 대신 누적 정규화-길이 카운터로 O(n²) 방지)와 `enforceMaxChars()`(오버플로 게이트만)에서 정규화 길이로 측정 — `slidingWindow()`류 원문 오프셋 기반 로직(코드펜스/표 경계 스냅 포함)은 그대로 raw 유지(오프셋 매핑 복잡도 회피, 스코프 축소 지점). 전체 565 tests BUILD SUCCESS(회귀 0).

### 10.2 가중 RRF (Weighted RRF) ✅ 완료 (2026-07-08)

**현재 상태**: `RetrievalService.mergeRrf()`는 모든 후보 리스트에 `1/(rank+1+60)` 동일 가중을 준다. MultiQuery로 벡터 축이 3개(원본+2), 하이브리드 시 BM25 축은 1개 → **정확 용어(에러코드·API명·제품코드) 매칭이 3개 벡터 축의 합의에 구조적으로 밀려 저평가**된다.

**개선안 (레버 2개 — 가중치 + 축 정규화)**:
1. **축별 가중치**: `score += weight * 1/(rank+1+k)`. 최소한 키워드(BM25) 축 가중치를 프로퍼티(`app.search-rrf-keyword-weight` 등, `props.searchXxxSafe()` 관례)로 노출하고, 상수 k(기본 60)도 외부화.
2. **축 그룹 정규화 (더 근본적)**: 진짜 왜곡은 개수 불균형(벡터 3 : BM25 1)이므로, 벡터 축들의 **합산 기여를 평균/상한으로 묶어** BM25가 표수로 밀리지 않게 한다. 가중치만으로 부족하면 이쪽이 정공법. 두 레버는 병행 가능.

**효과**: 하이브리드 검색 실효성 상승, 특히 완전 일치 용어 질의. §10.4와 세트일 때 체감이 크다.
**난이도**: 최저 — 한 메서드(`mergeRrf`) 시그니처에 가중치/그룹 정보 추가 + 프로퍼티 1~2개. `mergeRrf`는 이미 package-private로 단위 테스트됨.

**구현**: 두 레버 모두 적용. `mergeRrf(vectorRanked, keywordRanked, topK, k, keywordWeight)`로 시그니처를 바꿔 벡터 축과 키워드(BM25) 축을 분리해서 받고, 벡터 축은 항상 `1/axisCount`로 그룹 정규화(멀티쿼리 축이 1~3개로 변해도 상대 순위 불변 — 축이 1개뿐일 때는 전체에 동일 상수를 곱하는 것과 같아 순위가 그대로 보존됨), 키워드 축은 `app.search-rrf-keyword-weight`(기본 1.0 = 정규화된 벡터 그룹과 동일 비중)로 독립 조정한다. RRF 상수 k도 `app.search-rrf-k`(기본 60)로 외부화. 기존 2-인자 `mergeRrf(ranked, topK)`는 벡터 전용 오버로드로 유지해 하위 호환(기존 6개 단위테스트 무변경 통과) + 신규 가중 테스트 4개 추가. 하이브리드 기본 비활성(`app.search-hybrid-enabled=false`)이라 기본 배포는 회귀 0 — 정규화가 전체 벡터 축에 동일 상수를 곱하는 연산이라 순위에 영향이 없음을 수학적으로 보장.

### 10.3 쿼리 임베딩 캐시 (성능, 저비용) ✅ 완료 (2026-07-08)

**현재 상태**: 검색마다 원본+확장 쿼리(최대 3개)를 새로 임베딩한다(`RagService.searchBatch()` → provider가 매번 embed). 반복·유사 질문도 재임베딩해 지연·토큰을 낭비.

**개선안**: 정규화된 쿼리 텍스트 → `float[]` **Caffeine 캐시**. **Context7로 확인 — Spring AI 1.1엔 임베딩 결과 캐시가 없다**(유일한 "caching" 지원은 Bedrock 프롬프트 캐시로 이 목적과 무관). 따라서 기존 `TrackingEmbeddingModel`/`TrackingChatModel`(§6.5·§6.14)과 **동일한 데코레이터 패턴으로 `CachingEmbeddingModel`을 `EmbeddingModel` 앞단에 합성**한다(순서: cache → tracking → delegate, 캐시 히트 시 usage 기록 안 함). Caffeine은 이미 `RateLimitFilter`에서 사용 중이라 **신규 의존성 0**.
- 캐시 키 = `정규화 쿼리 + 모델명 + 차원` → 임베딩 모델/차원 변경 시 자동 무효화.
- 값 = `float[]`(차원×4바이트/엔트리로 메모리 산정), `maximumSize` + `expireAfterWrite` 상한.
- Caffeine은 스레드세이프라 가상 스레드와 무관.

**효과**: 반복 질의의 임베딩 왕복·토큰 절감(FAQ성 트래픽에서 큼).
**난이도**: 최저.

**구현**: 신규 `llm/CachingEmbeddingModel`(`EmbeddingModel` 데코레이터, `call(EmbeddingRequest)`만 오버라이드)을 `EmbeddingBeanConfig.embeddingModel()`에서 `TrackingEmbeddingModel` 바깥에 합성(cache → tracking → delegate) — 캐시 히트는 `delegate.call()` 자체를 안 타므로 usage 기록도 자동으로 스킵된다. 부분 히트(일부만 캐시)는 미스분만 모아 한 번에 delegate 배치 호출 후 원래 순서로 병합. 캐시 키는 **모델명만** 사용(정규화 쿼리 텍스트 + 모델명) — 원안의 "+차원"은 제외했다: `EmbeddingModel.dimensions()`가 `OpenAiEmbeddingModel`에서 최초 호출 시 실제 임베딩 서버에 프로브 요청을 보내고 나서야 캐싱되는 지연 호출이라, 빈 생성자에서 이를 즉시 호출하면 기동 시점에 임베딩 서버 의존성이 새로 생긴다(기존엔 없던 의존성) — 캐시가 부팅당 1회 생성되는 인메모리 인스턴스라 모델 변경은 재기동으로 이미 자연 무효화되므로 차원 없이도 충분. Caffeine은 `RateLimitFilter`에서 이미 사용 중이라 신규 의존성 0. `app.search-query-embed-cache-enabled`(기본 true)/`-max-size`(500)/`-ttl-seconds`(600)로 외부화. 인덱싱 호출(청크 텍스트, 대부분 캐시 미스)도 동일 캐시를 지나가지만 사이즈/TTL로 유계이므로 메모리 누수 없음(캐시 이득만 없을 뿐). 단위테스트 5개(캐시 히트 시 delegate 미호출·usage 미기록, 부분 히트 매핑, `embed(Document)` 경유, `dimensions()` 미개입) 추가. 전체 513 tests BUILD SUCCESS(회귀 0).

### 10.4 한국어 FTS 토크나이저 (하이브리드 정확도)

**현재 상태**: `KeywordSearchRepository`의 `chunk_fts`는 `tokenize='unicode61'` — 공백·구두점만 분리한다. **한국어 조사·복합어를 처리하지 못해** BM25 lexical recall이 영어 대비 크게 낮다. `toMatchQuery()`도 2자 이상 토큰을 OR로 결합할 뿐.

**개선안 (⚠️ 원안 정정 포함)**:
- **정정 (Context7 SQLite 문서 확인)**: 원안이 언급한 **nori/은전한닢은 Lucene/Elasticsearch 분석기라 SQLite FTS5에 직접 쓸 수 없다.** FTS5 내장 토크나이저는 `unicode61`/`ascii`/`porter`/`trigram` 뿐이고, 형태소 분석을 붙이려면 **커스텀 C FTS5 토크나이저**(`fts5_api` 등록, 예: mecab-ko 기반)를 loadable 확장으로 컴파일해야 한다 — vec0 바이너리와 똑같은 폐쇄망 바이너리 조달 부담이고 유지되는 기성품이 없다. 사실상 별도 과제/범위 밖.
- **현실안 = `trigram`**(SQLite 3.34+ 내장): 부분 문자열 매칭이라 조사 변형·복합어를 상당 부분 흡수하고 **외부 바이너리 0**. 트레이드오프 — 인덱스 ~3배, 최소 3자 매칭 granularity, 형태소 가중치 없음, `MATCH` 의미가 달라져 `toMatchQuery()` 조정 필요.
- (직교 튜닝) `unicode61`의 `remove_diacritics`·토큰 문자 커스터마이즈는 trigram 여부와 무관하게 별도로 손볼 수 있다.

**효과**: 하이브리드 한국어 정확 매칭 개선 → **§10.2(가중 RRF)·§10.1(Contextual BM25)과 세트로 하이브리드 기본 활성화(`app.search-hybrid-enabled`) 재검토 가능**.
**난이도**: 중(trigram, 스키마 재구축 + 재인덱싱 — 이미 `rebuildChunkFtsWithDocTags()`류 경로 존재) / 상(형태소 커스텀 토크나이저, 폐쇄망 바이너리 조달로 사실상 범위 밖).

### 10.5 검토 후 제외 (Phase 7-D 취소, 2026-07-08)

원래 Phase 7-D(인프라 투자)로 묶였던 아래 3건은 재검토 결과 **범위 제외**한다 — 삭제가 아니라 판단 근거를 남겨, 아래 "재개 신호"가 실제로 관측되면 이 기록을 근거로 다시 꺼낸다.

| 제외 항목 | 원래 개선안 | 제외 사유 | 재개 신호 |
|---|---|---|---|
| **sqlite-vec 배치 검색 단일 스캔** | vec0 brute-force KNN(O(n))을 다중 쿼리 1회 스캔으로 최적화 | 현 코퍼스 규모에선 병목이 임베딩 배치 생성(1 HTTP)이고 JDBC 루프는 수 ms라 체감 이득이 작다(§11 "searchBatch N회" 리스크 항목과 동일 판단) | 대규모 코퍼스에서 검색 지연 실측 악화 |
| **Cross-Encoder 리랭커** | LLM 리랭커를 ONNX bge-reranker 등 로컬 cross-encoder로 교체 | ONNX 런타임/모델 도입 + 폐쇄망 모델 파일 조달 비용이 크고, 현 opt-in `Optional<RerankerService>` LLM 리랭커로 충분. **인터페이스 유지 구조라 필요 시 구현만 교체 가능**(지금 만들 이유는 없음) | LLM 리랭크 정확도/지연 불만이 실사용에서 반복 보고 |
| **시맨틱 응답 캐시** | 질문 임베딩 유사도 > 임계값이면 캐시 답변 반환 | stale 답변 위험 + 무효화 복잡도(재인덱싱·버전 변경·§6.8 DISLIKE 연동) 대비 이득이 불확실 | FAQ성 반복 트래픽이 지배적이고 지연이 문제화될 때 |

### 10.6 우선순위 및 단계 계획

점수 = (Impact + 회귀리스크의 역) 관점으로 정리. 여기서 리스크는 "미조치 시 손해"가 아니라 **도입 시 회귀 리스크**(낮을수록 안전)로 해석한다.

| 항목 | Impact | 회귀리스크 | Effort | 성격 |
|------|:--:|:--:|:--:|------|
| 10.1 Contextual Retrieval | 5 | 2 | 3 | 정확도(ROI 1위) |
| 10.2 가중 RRF | 3 | 1 | 1 | 정확도(초저비용) |
| 10.3 쿼리 임베딩 캐시 | 3 | 1 | 1 | 성능(초저비용) |
| 10.4 한국어 FTS 토크나이저 | 4 | 2 | 3 | 정확도 |

**단계 계획**:
- **Phase 7-A (빠른 승리) ✅ 완료 (2026-07-08)**: §10.2 가중 RRF + §10.3 임베딩 캐시 — 코드 변경 작고 회귀 리스크 최소, 즉시 체감. 이후 큰 작업의 효과를 측정할 baseline 확보.
- **Phase 7-B (정확도 본편)**: §10.1 Contextual Retrieval — `KeywordExtractor` 파이프라인에 얹어(구조적 맥락 baseline → LLM 강화) 인덱싱 재구성. 정확도 ROI 1위.
- **Phase 7-C (한국어 최적화)**: §10.4 `trigram` FTS 토크나이저 + §10.2·§10.1과 묶어 하이브리드 기본 활성화 재검토.

**선결 과제(권장)**: 검색 품질을 정량 비교할 **평가 세트**(질문–정답 청크 쌍 소량 + recall@k/nDCG 측정 스크립트)가 있으면 §10.1·§10.2·§10.4의 효과 검증이 크게 쉬워진다. Phase 7-A와 병행 준비를 권장.

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
