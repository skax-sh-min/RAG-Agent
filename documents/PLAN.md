# RAG-Agent 온라인 확장 개발 계획

> Java 개발자 관점 · Spring Boot 3.5 + Spring AI 1.1 + Java 21 · 작성일 2026-05-11  
> **개발 기준 문서**: 이 파일(documents/PLAN.md)이 마스터. `documents/refactoring/18-extension-roadmap.md`는 각 항목의 기술 레퍼런스.

---

## 📊 전체 현황 대시보드

> 완료/미착수를 한눈에 보도록 상단 대시보드를 신설했다.
>
> **문서 분리 (2026-09-02 적용)**: 완료 절의 «유지해야 할 결정»·«계획이 틀렸던 지점»과 해소된 이슈는 [PITFALLS.md](PITFALLS.md) 로 옮겼다. 이 파일은 **앞으로 할 일과 이미 한 일의 요약**을 담고, **왜 그렇게 했고 무엇이 틀렸는지**는 그쪽이다 — 계획을 볼 때와 코드를 고칠 때 필요한 것이 다르기 때문이다.

> **완료 항목 압축 원칙 (2026-08-22 적용)**: ✅ 완료된 항목은 **① 무엇을 왜 그렇게 했는가 ② 앞으로도 지켜야 할 결정·함정 ③ 남은 열린 항목**만 남기고, 구현 과정 서술·테스트 통계·CLAUDE.md에 이미 있는 메커니즘 설명은 걷어낸다. 살아 있는 동작 명세의 단일 출처는 **코드와 CLAUDE.md**이고, 이 문서는 "왜 그렇게 결정했는가"의 기록이다 — 양쪽에 같은 설명을 두면 드리프트만 생긴다(§12가 의존성에 대해 같은 판단을 이미 적용했다).

### ✅ 완료 — Phase 1·2·5·6·7 전체, Phase 3 전체(§6.16.2·6.19·6.20 제외)

> **2026-09-03 추가 완료**: **§10.13 Direct 턴의 이력 확대**(문서 자리가 비면 그만큼 이력에 돌려준다 — DN 답변의 `## 요약` 유도 · 두 이력 경로의 규칙 통일 · Direct 경로의 예산 안전망) · **§10.12 짧은 후속 질문의 독립화**(재작성 한 번이 검색 축 셋 + 분류기를 함께 고친다 — LLM 호출 순증 0, 재료를 이전 '질문'으로 좁혀 열린 항목 (b) 해소).
>
> **2026-09-02 추가 완료**: **§10.11 좋아요 → 지식 제안 경유**(무검토 유입 경로 제거 — 검색 코퍼스로 들어가는 문이 관리자 승인 하나로 모였다) · §6.27 검증 실패 재시도 개선(피드백 루프 · 게이트별 분기 · 청크 교체 · 여유 기반 증가) · QA 픽스 6건(저장 상한이 삭제로 회수되지 않던 문제, 다중 파일 업로드 429, 업로드 거부 사유 유실, 임시 파일 누수 등).
>
> **2026-09-01 추가 완료**: §6.26 컨텍스트 초과 대응(창 인지 + 입력 예산, 11단계 — 인덱싱 재작성 예산 · 선형 축소 재시도 · 창 재탐지 버튼 · max-tokens 핫 편집까지 · **남은 이슈 없음**) · §6.24 의 남은 이슈였던 토큰 추정 4배 불일치 해소 · C(응용) 모드 운영자 스위치(`app.llm.creative-mode-enabled`) · `/settings` 허용 범위 툴팁과 인덱싱 3종 범위 조정 · `/admin` 문서 레지스트리 페이지네이션 · §6.15 전역 저장 상한(`app.upload.max-total-size`).

| Phase | 완료 항목 | 상세 |
|---|---|---|
| **Phase 1** — 보안 기반 | Step 1.1~1.6 전체(Caddy·Flyway·Spring Security·멀티유저 격리·CSRF·로그인/회원가입 UI) + `app.auth.enabled` no-auth 토글 | §4 |
| **Phase 2** — 모바일 UI | 반응형 레이아웃(Offcanvas) · PWA(manifest/SW/오프라인) · 다크모드·접근성 | §5 |
| **Phase 3** — 운영 견고화 | §6.1 Rate limit · §6.2 업로드 검증(매직바이트) · §6.15 전역 저장 상한 · §6.3 예외처리 · §6.4 감사로그 · §6.5 임베딩 사용량 분리 · §6.6 비활성 프로바이더 표시 · §6.7 orphan 기록 삭제 · §6.8 피드백 기반 컨텍스트 제외 · §6.9 요약 선계산 · §6.10 백그라운드 사용량 분리 · §6.11 컨텍스트 예산 정합성 · §6.12 다중 사용자 동시 LLM 처리(동시성 게이트+백프레셔+로드밸런싱) · §6.13 설정 페이지(핫 수정 오버라이드) · §6.14 핵심 채팅 경로 추적 · §6.16.1 스트리밍/인덱싱 중단 버튼 · §6.17 관리 전용 인증(B안) · §6.18 Direct temperature 분리 · §6.19.3 XFF 신뢰 옵트인 · §6.21 소형 LLM 분리+멀티 LLM 처리량 확장 · §6.22 접속자별 채팅 개인화(no-auth) · §6.23 청크 변경 시 답변 재사용 무효화·대화 기록 표시 · §6.24 응답 모드 재설계(S/N/C — L 제거·모드별 전용 프롬프트·오염 방지 3건, `4-c`만 미착수) · §6.25 관리자 대화 목록·삭제·검색 진단 연결 | §6 |
| **Phase 5** — Vector Store | Step 5.1~5.10 전체(Chroma↔sqlite-vec 런타임 전환, 관리자 페이지, 태그 검색, 운영/벡터 DB 분리) | §8 |
| **Phase 6** — 폐쇄망/노-도커 | G1~G5(키리스 LOCAL·차원 외부화·라우팅 외부화·런북·무외부호출 인수) | §9 |
| **Phase 7** — 검색 품질·성능 고도화 | §10.1~10.9 전체(17건) — 정확도·속도·메모리 개선 + recall@k/nDCG@k 평가 하네스(baseline recall@10=0.962). **+ §10.10** — 좋아요 기반 큐레이션 Q&A 지식화(스냅샷·임베딩·검색 융합·관리 UI) 완료 | §10 |

추가로 Phase 3 초기에 완료된 항목(문서화되지 않았던 픽스 포함): ChromaDB v2 API 컬렉션명→UUID 자동 변환, 문서 저장 경로 공유 구조 단순화(`DocRegistry.SHARED`), 인덱싱 SSE 진행 단계별 표시, 키워드 추출 타임아웃 시 CircuitBreaker 오동작 수정, DOCX 변환 전 구버전 아티팩트 삭제 순서 수정, 환경변수 외부화 4건, 의존성 최신 stable 일괄 업데이트(정확한 버전은 pom.xml 참조).

### 🔵 진행할 것 (우선순위 순)

> **재우선순위화**: 실배포 기준(폐쇄망·no-auth 단일 운영자)에서 가치가 없는 **멀티유저(`auth.enabled=true`) 전용 작업**(§6.19·§6.20·§6.16.2·Phase 4)은 전부 후속으로 내렸다(사유는 아래 후속 표의 트리거 열 참조). §6.15(스토리지 쿼터)는 전역 상한이라 단일 운영자에도 적용되므로 이 그룹에 남겨 두었고, 2026-09-01 완료됐다. **§6.25(관리자 대화 목록)도 이름은 §7.3(Phase 4) 계열이지만 같은 이유로 지금 진행 그룹이다** — `app.auth.guest-identity`가 `shared`가 아니면 no-auth 단일 운영자 배포에도 방문자별 대화가 쌓이고, 그중 Step 1은 인증 모드와 무관한 검색 오염 버그 수정이다.

**🟢 지금 진행 (no-auth 단일 운영자 배포에도 바로 적용)**

| 순위 | 항목 | 현재 상태 |
|---|---|---|
| ~~1~~ | ~~**§10.11 좋아요 → 지식 제안 경유**~~ | ✅ 2026-09-02 완료 (5단계 + 정책 3건 전부) |
| ~~1~~ | ~~**§10.12 짧은 후속 질문의 독립화**~~ | ✅ 2026-09-03 완료 (재작성 → 검색 축 셋 + 분류기, 진단 한 줄, 열린 항목 (b) 해소) |
| ~~2~~ | ~~**§10.13 Direct 턴의 이력 확대**~~ | ✅ 2026-09-03 완료 (1단계 프롬프트 + 예산/렌더 규칙 + Direct 안전망) |
| ~~3~~ | ~~**§10.14 청크 오류 신고 (사용자 → 관리자)**~~ | ✅ 2026-09-04 완료 (0~5단계 전부 — 렌더러 통일 · 대기열 · 청크 단위 관리자 처리 · 배지) |
| 4 | 운영 준비 잔여 — SQLite 백업 자동화(Litestream/cron), Caddy 인증서 만료 모니터링 | 미착수 |
| 5 | §6.24 `4-c` — 검색 부스트 상향 | 부스트 기본값이 0이라 미착수. **§6.26 이후 전제가 바뀌었다** — 검증 발췌 상한이 이제 창에서 파생되므로(`evalExcerptTokenBudget`), 부스트를 올리기 전에 확인할 것은 `MAX_EVAL_EXCERPT_CHARS` 상수가 아니라 **대상 프로바이더의 실제 창**이다 |
| 6 | §9.4 — CADDY 하위호환 별칭 | 선택, 낮은 우선순위 |
| 7 | Phase 2 남은 실기기 검증 2건 (키보드 하단 고정 · 홈 화면 standalone) | 좌우 스크롤·다크모드는 자동 검증 완료, 나머지는 실기기 필요 |

**🟣 후속 — 멀티유저(`auth.enabled=true`) 활성화 시에만 착수**

| 순위 | 항목 | 트리거 |
|---|---|---|
| 1 | **§6.19 보안 하드닝** — API CSRF/세션 혼용(6.19.1) · `/admin/**` ROLE_ADMIN 게이트(6.19.2) · **로그인 전용 rate-limit 버킷**(§6.1 참조 — 문서에만 있고 코드엔 없던 항목) ※ **6.19.3(XFF)은 §6.22와 함께 완료** | **auth 모드 여는 시점에 반드시 선행**(게이트) — no-auth엔 노출면 없음 |
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

`RateLimitFilter`(`OncePerRequestFilter`, `SecurityFilterChain` 앞단 등록)가 엔드포인트별 인메모리 버킷을 적용 — 인증 시 userId, 미인증 시 IP(`ClientIpResolver`) 기준. **현행 기본값**(2026-09-01 실측, `application.properties` 기준): 채팅 60/분, 업로드 10/분, 동기화 3/분, 이미지 서빙 300/분, 그 외 120/분. 정확한 값과 환경변수는 OPERATOR_MANUAL §"Rate Limiting"이 단일 출처다 — 여기 숫자를 복제해 두면 드리프트만 생긴다.

**업로드 버킷은 쓰기 요청만 센다**(2026-09-01 수정): 예전에는 경로에 `/documents` 가 들어가면 전부 업로드 버킷이라 `/documents` 페이지 로드·`/ui/documents/list` 갱신·내보내기·태그 편집이 같은 10/분을 갉아먹었고, 파일 10개를 한 번에 올리는 정상 사용이 마지막 파일에서 429 로 죽었다(페이지 진입 1 + 파일 10 = 11). 지금은 메서드가 쓰기이고 경로가 실제 업로드 엔드포인트일 때만 그 버킷을 쓴다.

**🔵 미착수 — 로그인 전용 버킷**: 이 문서는 오랫동안 "로그인 분당 10회/IP"를 적어 뒀지만 `policyFor()` 에 login 분기는 **존재한 적이 없다** — `/login` 은 `default`(120/분)로 흐른다. 계정 잠금(5회/15분, `AuthEventListener`)이 있으나 그건 **계정 단위**라 아이디를 바꿔 가며 시도하는 패턴은 걸리지 않는다. 노출면이 `app.auth.enabled=true` 배포에만 있으므로 **로그인 기능을 실제로 여는 시점에 §6.19(보안 하드닝)와 함께** 넣는다. 지금 문서는 실제 동작에 맞춰 두었다.

다중 인스턴스 확장 시 Redis 백엔드로 전환 필요(부록 참조).

### 6.2 파일 업로드 보안 강화 🟡 부분 완료 (리팩토링 03, 12)

**✅ 완료**
- 확장자 화이트리스트: `pdf, pptx, docx, txt, md`
- **매직바이트 검증** — `security/FileTypeDetector.matches(path, ext)`(Tika 아님, pom에 Tika 의존성 없음). 임시파일 기록 후 검증, 불일치 시 422
- 파일명 sanitize — `Path.normalize()` + 화이트리스트 정규식
- 경로 이탈 방지 — 공유 저장소 `data/documents/`(per-user 격리 폐기, `DocRegistry.SHARED`) 기준 `startsWith()` 검증

**✅ 완료(이관분)** — 누적 용량 쿼터는 §6.15에서 **전역 저장 상한**으로 재설계돼 완료됐다(사용자별 축이 아닌 이유는 그쪽 참조).

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

### 6.15 스토리지 쿼터 — 전역 저장 상한 ✅ 완료 2026-09-01 (§6.2에서 이관, B안)

**축을 (A) 사용자별에서 (B) 전역으로 바꾼 것이 이 항목의 유일한 설계 결정이다.** §6.2가 "사용자별 누적 용량"으로 적어 뒀지만 그 사이 저장소가 공유 구조(`DocRegistry.SHARED`, 단일 `data/documents/`)로 재단순화됐고 기본 배포는 no-auth 단일 운영자다 — "이 사용자가 N바이트를 썼다"를 붙일 데가 없다. 저장 모델과 맞는 쿼터 축은 디스크 자체다. (A)는 멀티테넌트 과금이 필요해질 때의 후속으로 남긴다(`users.storage_used_bytes` 런타임 `ALTER TABLE` + 업로드/삭제 시 증감; 공유 디스크 위의 "논리적" 쿼터라는 점을 그때 문서에 명시할 것).

**앞으로도 지켜야 할 결정**

- **세는 대상이 `documents/` 하나가 아니다** — `documents/` + `converted/` + `images/` 세 트리다. 설계 초안은 `data/documents/` 합계였는데, PPTX·스캔 PDF는 변환 MD와 추출 이미지가 원본보다 크다. 원본만 세는 상한은 업로드가 실제로 먹는 양의 대부분을 못 본다. `data/` 아래 나머지(SQLite 파일, 감사 로그, Chroma 볼륨)는 업로드가 만드는 것이 아니라 제외.
- **소프트 캡인 것이 의도다** — 검사 시점에 알 수 있는 크기는 들어오는 파일뿐이고 파생 산출물은 아직 없다. 그래서 한 건은 상한을 넘겨 끝날 수 있고, 대신 **그다음 업로드가 거부된다**(그때는 초과분이 디스크에 있어 측정된다). 하드 캡으로 만들려면 인덱싱 중간에 중단하거나(반쪽 문서가 남는다) 팽창 배수를 추측해야 하는데, 추측이 빗나가면 들어갔을 파일을 거부한다.
- **카운터 컬럼이 아니라 디스크 walk** — 삭제가 곧 자리 확보가 되고, `/admin` 삭제·디렉터리 동기화·수동 `rm` 중 어느 것도 쿼터의 존재를 알 필요가 없다. 상한 미설정이면 walk 자체를 건너뛰므로 **기본 배포는 비용 0**(회귀 0의 실제 근거가 여기다).
- **단 하나의 예외가 `documents/backup/` 이고, 그것이 이 항목에서 가장 중요한 결정이다** (2026-09-01 QA에서 발견·수정). 문서를 삭제하면 원본은 지워지지 않고 그리로 **옮겨진다**(`RagService.archiveSourceFile`). 그걸 계속 세면 상한에 걸린 사용자가 문서를 지워도 사용량이 거의 안 줄고, 오류 메시지가 제시하는 유일한 해결책("문서를 지우세요")이 성립하지 않는다 — 게다가 그 바이트는 어느 화면에서도 회수할 수 없다. 그래서 **측정에서 빼되, 뺀 만큼 `DocumentBackupCleaner` 가 그 폴더를 묶는다**. 둘은 한 결정의 두 쪽이라 한쪽만 바꾸면 안 된다: 정리 없이 제외하면 디스크가 상한 밖에서 조용히 차고, 정리하면서 계속 세면 원래 문제가 그대로다.
- **백업 보존은 세 규칙**(순서대로 적용, 뒤 규칙은 앞 규칙이 남긴 것만 본다): ① 같은 원본 파일명은 **최신 1개만** — 업로드/삭제 반복을 실제로 묶는 것은 이 규칙이고 나머지 둘은 시간·용량 백스톱이다 ② `app.upload.backup-retention-days`(기본 **30**) 초과분 삭제 ③ 그래도 `app.upload.backup-max-size`(기본 **2GB**)를 넘으면 오래된 것부터. **미설정이 '무제한'이 아니라 기본값인 유일한 설정**이다 — 상한 쪽은 없는 상태가 안전한 기본이지만, 백업은 사용자가 보지도 지우지도 못하는 파일이라 무제한이 곧 "아무도 모르게 차오름"이다. **이 앱이 만든 이름(`{원본}_{yyyyMMdd_HHmmss}{확장자}`)만 지운다** — 운영자가 그 폴더에 둔 파일은 용량 계산에는 들어가지만 삭제 대상이 아니고, 그래서 상한까지 못 내려가는 경우가 생기면 지우는 대신 로그로 알린다.
- **검사는 필터가 아니라 진입점마다** — 바이트를 받는 새 경로는 쓰기 전에 `StorageQuotaService.checkCanAccept()` 를 직접 불러야 하고, 안 부르면 조용히 면제된다. 지금 부르는 곳은 문서 업로드 2곳(`/ui/documents/upload`, `/api/v1/documents` — 둘 다 `stageToTemp()` **앞**이라 거부된 업로드는 한 바이트도 안 쓴다)과 `CuratedImageStore.store()`다. 마지막 것을 포함한 이유는 그 클래스 주석이 이미 못 박고 있다 — 인증 없는 호출자가 디스크에 바이너리를 쓰는 유일한 자리라, 빼 두면 그게 상한을 우회하는 가장 뻔한 길이 된다. 반대로 `/api/v1/documents/sync` 는 **일부러 검사하지 않는다**: 그 바이트는 이미 디스크에 있고 받아들이는 것이 없다.
- **프로퍼티 이름이 설계안과 다르다** — `app.upload.max-total-bytes` 대신 `app.upload.max-total-size`(`DataSize`, 접미사 없으면 바이트). 상한이 GB 단위라 `20GB` 로 쓸 수 있어야 하고, `int` 바이트로는 2GB 를 넘길 수도 없다. `0`(기본) = 무제한이고 음수는 `uploadSafe()` 가 0으로 정규화한다 — 설정 실수가 "예상보다 빡빡한 상한"으로 굳어 업로드를 막는 쪽보다 낫다.
- **413 + `RAG-UP-002`, `Retry-After` 없음** — `MaxUploadSizeExceededException`(RAG-UP-003)과 상태 코드는 같지만 고칠 것이 다르다("이 파일이 크다" vs "더 넣을 자리가 없다"). 429 를 쓰지 않은 것도 같은 이유다: 기다린다고 자리가 생기지 않고 삭제만이 자리를 만든다. `/documents` 업로드 XHR 은 이제 ProblemDetail 의 `detail` 을 먼저 읽어 "서버 오류 (413)" 대신 그 문구를 띄운다.
- **`/settings` 는 조회 전용 2행(사용량·상한)** — 편집을 안 여는 이유는 `max-tokens` 와 같고(배포 정책, 재기동 필요), 더해서 핫 키의 수치 종류가 `int` 라 GB 단위를 담지 못한다. 상한만 적으면 운영자가 어디쯤 와 있는지 알 방법이 "업로드가 거부되는 순간"뿐이라 **사용량을 함께** 낸다.

**남은 열린 항목**

- **업로드 검사 경로에는 캐시가 없다** — 상한이 설정된 배포는 업로드마다 세 트리를 걷는다. 일부러 그렇게 뒀다: 다중 파일 업로드는 연달아 도착해서 낡은 값 하나가 여러 건을 통과시킨다. 화면 쪽(`/settings`)만 30초 TTL로 memoize 했고 그건 정확도가 아니라 **노출면** 때문이다(그 페이지는 모든 인증 모드에서 게스트 개방이라, 렌더가 곧 무제한 디렉터리 walk 트리거가 되어선 안 된다). 이미지 수십만 개 규모에서 업로드 검사 자체가 느려지면 그때 다시 볼 것.
- 백업 정리는 **삭제 직후와 기동 시**에만 돈다. 문서를 오래 지우지 않는 배포에서는 30일 규칙이 최대 그만큼 늦게 반영된다(용량 규칙은 다음 삭제에서 따라잡는다). 주기 실행이 필요해지면 그때 스케줄러를 붙일 것 — 지금 붙이면 아무 일도 없는 배포에서 매일 디렉터리를 걷는다.
- 상한에 근접했을 때의 **사전 경고가 없다** — 거부가 첫 신호다. `/documents` 상단에 사용률 배지를 두는 편이 낫지만 그건 UI 작업이라 별도.

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

> **결정 근거와 틀렸던 지점** → [PITFALLS.md](PITFALLS.md#623-청크-변경-시-답변-재사용-무효화--대화-기록-표시--완료)

답변 재사용(`/api/v1/questions/reuse`)이 근거 청크가 그 사이 삭제·수정된 답변을 사실처럼 되돌려주던 문제를 **스냅샷 대조**로 해결 — 턴 저장 시 `(chunk_id, sha256(chunk_fts.content), answer_share)`를 `turn_source_ref`에 남기고 재사용 직전 대조하며, 삭제/재인덱싱 경로는 별도로 즉시 무효화 표시를 남겨 이중으로 막는다. 대화 기록에는 `SourceRef.staleBadge()` 한 곳이 정한 규칙으로 **삭제됨/수정됨** 배지를 띄운다. 메커니즘·테이블·배지 규칙 상세는 CLAUDE.md의 `QuestionReuseService`/`QuestionReuseRepository`/`SourceRef` 항목.

---

### 6.24 응답 모드 재설계 — S/N/C (L 제거 · 모드별 전용 시스템 프롬프트) ✅ 완료 2026-08-24 — 설계 확정 2026-08-22 (분량 지침·`/settings` 배선 보강 2026-08-23, 완료 후 보정 2026-08-25)

> **결정 근거와 틀렸던 지점** → [PITFALLS.md](PITFALLS.md#624-응답-모드-재설계--snc-l-제거--모드별-전용-시스템-프롬프트--완료-2026-08-24--설계-확정-2026-08-22-분량-지침settings-배선-보강-2026-08-23-완료-후-보정-2026-08-25)

길이 축(S/M/L)에 "문서를 **재료로** 코드·설정을 생성"이라는 **다른 축**(근거 엄격도)을 얹으려다, 기존 축부터 검토해 **M과 L이 구분되지 않음**을 확인하고 재설계했다. M/L이 같았던 이유는 튜닝이 아니라 구조다 — 채팅의 유일한 전송 경로인 스트리밍에는 `maxTokens`가 붙지 않고, `RetrievalService`가 모드를 몰라 두 모드의 재료(topK)가 동일하며, 남은 차이인 "약 N자" 문구는 모델을 움직이지 못했다(실측 M 3,047자 / L 3,187자, **둘 다 M의 목표 5,000자 미달**). 그래서 L 제거·M→N 개명 후, 모드마다 **시스템 프롬프트를 통째로** 주고(공용 프롬프트를 사용자 메시지로 뒤집던 층은 삭제) 응용 모드 **C**를 신설했다. Phase 0(`0-a`~`0-e`) · 1(`1-a`~`1-d`) · 2(`2-a`~`2-d`) · 3(`3-a`~`3-c`) · 4(`4-a`·`4-b`) 완료, `4-c`만 미착수(아래). 모드별 동작 명세는 CLAUDE.md의 Response mode 항목과 PIPELINE §3.1.

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

### 6.27 검증 실패 재시도 개선 ✅ 완료 2026-09-02

**문제**: 재시도가 사실상 같은 입력의 반복이었다. 두 게이트(`sufficient=false`/`grounded=false`)가 **같은 노드**로 갔고, 돌아가서 달라지는 것은 문서 **1개**뿐이었으며(`topK + retryCount` — ×2/×3 은 후보 풀이지 최종 컷이 아니다), 질문·시스템 프롬프트·대화 이력은 그대로에 일반/RAG 온도는 `[0, 0.3]` clamp 에 기본 `0.0` 이다. 같은 입력을 결정적으로 다시 넣고 같은 평가자에게 같은 질문을 하는 셈이라 같은 판정이 나오기 쉬웠다. 그런데 **왜 반려됐는지는 이미 계산돼 있었다** — `evalReason` 은 로그·SSE·DB 로 나가고 재시도 state 까지 살아남는데, 정작 그것을 볼 필요가 있는 모델에게만 가지 않았다.

**앞으로도 지켜야 할 결정**

- **재시도 프롬프트에 `[직전 시도 메모]` 를 넣는다** (`AnswerService.appendRetryFeedback`). 추가 왕복 0회. **지시가 아니라 관찰로** 쓴다 — "이 지적을 만족시켜라"로 쓰면 모델이 지적을 채우려고 문서에 없는 내용을 지어내 고치려던 지표가 더 나빠진다. 시스템 프롬프트 6개(모드 3 × 로케일 2)를 건드리는 대신 "답변에 언급하지 마라" 제약을 **같은 블록 안에** 둔다 — 블록이 있을 때만 그 제약도 존재하면 된다.
- **두 게이트는 다른 노드로 간다**. `sufficient=false`(질문에 답하지 못함) → RETRIEVAL, `grounded=false`(문서 밖으로 나감) → **ANSWER**. 후자에서 재검색은 임베딩 + MultiQuery 확장 호출을 쓰고 사실상 같은 집합(+1)을 받아오는 낭비다. **이 분기는 위 피드백이 선행돼야만 성립한다** — 프롬프트가 그대로면 온도 0에서 같은 답변이 그대로 재생성된다. 두 변경은 독립이 아니라 순서가 있다.
- **escalation 의 입력은 `retryCount` 가 아니라 신규 `retrievalRetries`** — 검색을 건너뛴 재시도가 검색 escalation 을 앞당기면 안 된다.
- **덧붙이는 대신 밀어낸다** (`RetrievalEviction`). 최종 컷이 재시도당 1개만 늘어나는 구조에서는 새 근거가 이미 자리를 차지한 죽은 무게와 경쟁한다. **신호 둘이 동의할 때만** 민다(평가가 근거로 안 썼다 + RRF 하위 절반) — `usedDocs` 는 방금 반려된 시도의 자기 보고라 그것만으로는 부족하다. 가드 셋이 이 클래스의 본체이며 각각 실제 사고 모양이 있다: 빈 `usedDocIndices` 는 "전부 미사용"이 아니라 **"모른다"**, 발췌가 잘린 시도의 뒤쪽은 "안 쓰인 것"이 아니라 **"보이지도 않은 것"**, 1순위는 언제나 보존. `grounded=false` 에는 적용하지 않는다(근거를 빼는 것이 방향상 반대).
- **후보 풀 배수 ×2 → ×1.5**. 교체가 컷의 1/3 을 비워 주므로 그 자리를 채울 만큼(≈×1.3)이면 되고, 풀을 키우는 것도 공짜가 아니다.
- **문서 +1 은 컨텍스트 여유가 있을 때만**. 기준은 답변 호출이 아니라 **검증 호출**이다 — 넘칠 때 나는 사고가 초과가 아니라 조용한 품질 저하라서(`unreliableNegative()` 가 `grounded=null` 로 떨어뜨린다). 즉 예전 escalation 은 **재시도를 거듭할수록 판정을 잃는** 구조였다. 창을 모르면 늘린다 — 늘리는 쪽이 기존 동작이고, "창을 모르면 아무것도 하지 않는다"는 추측으로 *줄이지* 말라는 뜻이다.
- 부수 효과로 **§6.24 `4-c`(검색 부스트) 의 전제 하나가 사라졌다** — 개수 고정 재시도는 검증 창 압박을 키우지 않는다.

**남은 열린 항목**

- **조기 종료 미구현**: 같은 사유로 두 번 반려돼도 `max-retry-count` 까지 전부 소진한다. `evalReason` 비교 한 줄로 최악 비용의 1/3 을 품질 손실 없이 줄일 수 있다.
- 효과 측정은 `[AgentGraph] retry #N ... detail=` 로그로 한다(도입 전후 "재시도 후 통과율"). 별도 지표는 만들지 않았다.
- `EVAL_OVERHEAD_TOKENS`(1,500)는 측정이 아니라 허용치다. 정확히 재려면 모드별 시스템 프롬프트와 스키마 문자열을 `RetrievalService` 로 끌고 와야 하는데, 그 둘은 문서 하나 크기에도 못 미치면서 계산만 두 곳으로 갈라 놓는다. 과대 추정은 "늘리지 않음"으로 떨어져 안전한 방향이다.

---

### 6.26 컨텍스트 초과 대응 — 창 인지 + 입력 예산 ✅ 완료 2026-09-01

> **결정 근거와 틀렸던 지점** → [PITFALLS.md](PITFALLS.md#626-컨텍스트-초과-대응--창-인지--입력-예산--완료-2026-09-01)

실배포 로그에서 시작했다: `Context size has been exceeded.` → `CircuitBreaker` 가 유일한 LOCAL 프로바이더를 30초 차단 → 그동안 들어온 **작아서 통과했을 요청까지** `All providers exhausted` 로 함께 실패. 원인은 프롬프트 크기인데 증상은 프로바이더 장애로 나타났고, 사용자에게는 "모든 AI 프로바이더를 사용할 수 없습니다"가 갔다. 아무도 고칠 곳을 찾을 수 없는 조합이다.

**11단계로 나눠 진행했다** — 각 단계가 단독으로 가치가 있고 뒤 단계의 전제가 된다.

1. **차단하지 않기** (`isContextOverflow`) — 결정적 오류라 기다려도 같은 요청은 똑같이 실패한다. `isTimeoutLike`(클라이언트 측)·`isVisionUnsupported`(모델 성질)에 이은 **세 번째** "프로바이더가 아픈 게 아니다" 분류다.
2. **오류 구분** (`LlmContextOverflowException`, `RAG-LLM-003`) — 프롬프트 크기 문제로 안내한다.
3. **프로바이더별 `max-tokens`** — 창 크기가 모델마다 다르니 상한도 하나일 수 없다.
4. **컨텍스트 창 학습** (`context-size` 선언 또는 `ContextWindowProbe` 탐지).
5. **토큰 추정 통일** (`TokenEstimator`) — §6.24 남은 이슈였고, 여기서 선결 조건이 됐다.
6. **입력 예산 사전 축소** (`PromptBudget` + `AnswerService.fitToBudget()`).
7. **축소 사실 노출** (`AgentState.budgetNote` + 출처별 `미사용` 표시).
8. **인덱싱 입력 예산** (`PromptBudget.rewriteInputChars()`) — 정작 사고가 났던 경로. 6번은 답변·검증만 덮었는데 실배포에서 초과가 난 것은 **MD 교정**이었다.
9. **축소 후 재시도** (`AnswerService.withShrinkRetry()`) — 1~8번이 모두 **추정** 위에 서 있다는 사실을 받아내는 마지막 층.
10. **창 재탐지 버튼** (`SettingsService.reprobeContextWindows()`) — 4번이 기동 시 1회라 값이 낡는다. 고치는 시점을 **사람이 정하게** 한 이유는 예산이 스스로 움직이면 재현이 안 되기 때문이다.
11. **`max-tokens` 핫 편집** (`MaxTokensCappingChatModel` 이 `IntSupplier` 로) — 페이지에 마지막 남은 조회 전용 값이었고, 하필 이 §의 거의 모든 계산이 파생되는 값이었다.

**남은 이슈**

- **자리가 `AnswerService` 인 이유**: 라우터는 호출을 불투명한 클로저로 받아 프롬프트 안을 못 보므로 무엇을 버릴지 아는 곳은 프롬프트를 조립하는 여기뿐이다. 라우터의 프로바이더 순회와는 층이 달라 서로 방해하지 않는다 — 저쪽이 "다른 곳에 물어본다"면 이쪽은 "더 작게 물어본다"다. 답변(블로킹·스트리밍·PROGRESSIVE)과 **검증** 양쪽에 걸린다.

> 해소된 이슈 7건의 경위와 근거 → [PITFALLS.md](PITFALLS.md#626-컨텍스트-초과-대응--창-인지--입력-예산--완료-2026-09-01)
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

### 10.10 좋아요 기반 큐레이션 Q&A 지식화 ✅ ①②③④ 전체 완료 (유입 경로는 §10.11 로 대체됨)

Phase 7의 원래 17건 완료 **이후** 추가된 설계. 좋아요(👍)한 답변을 `curated_qa`로 스냅샷 → 예약 네임스페이스 `"curated"`에 임베딩 → 검색 시 **가중 RRF 축**으로 융합 → 본인(채팅 인라인) 또는 관리자(`/admin` 카드)가 편집·삭제. §10.5에서 제외한 "시맨틱 응답 캐시"의 **인간 검수 가능 버전** — verbatim 반환이 아니라 검색 축의 근거로만 주입하므로 stale 위험·무효화 복잡도가 낮다. 신규 프로바이더 메서드 없이 기존 인프라(§10.1 임베딩≠저장, §10.8.5 `SEARCH_TEXT` 오버라이드, `/admin/chunks` 편집 패턴, 소유권 체크)를 재사용. 구현 상세는 CLAUDE.md `CuratedQaService`/`CuratedQaRepository` 항목, 운영은 [OPERATOR_MANUAL §6.7·§7.5](OPERATOR_MANUAL.md), UI는 [UI.md](UI.md).

**확정된 정책**
- 가시성=전체 공유. 답변은 verbatim 반환이 아닌 **LLM 근거로 주입**(오답 증폭 방지, `/admin`에서 가중치·편집·삭제로 통제)
- ~~편집 권한=본인 OR 관리자~~ — §10.11 에서 바뀌었다: 본인 편집은 **지식 제안 페이지**에서만 하고(채팅 인라인 연필 제거), 관리자는 `/admin` 에서 모든 항목을 편집한다
- **대화 삭제 시 큐레이션도 함께 회수**(§6.25에서 정책 변경) — 최초 정책은 반대였다("유지, 캐스케이드 안 함": 개인 대화 정리가 공유 검색 품질을 조용히 떨어뜨리는 것을 막기 위함). 바뀐 이유는 대화가 사라진 뒤에도 그 답변이 검색 근거로 남는 쪽이 더 혼란스럽고, 턴 단위 삭제(`deleteTurn`→`onUnlike`)는 처음부터 회수하고 있어 두 경로의 동작이 갈려 있었기 때문. 구현은 `CuratedQaService.onThreadDeleted()`이고 **사용자 경로와 관리자 경로가 함께** 호출한다. 승인된 청크 추가 제안(`origin='manual'`)은 대화 소속이 아니므로 제외된다. 열린 항목 (e)(프라이버시 트레이드오프)는 이 변경으로 해소됐다
- **문서 삭제와는 연동 없음** — `conversation_turns`가 턴별 출처 문서를 저장하지 않아 구조적으로 불가(열린 항목 (b))

**열린 항목**: (a) 큐레이션 축 similarity threshold 별도화 · (b) doc_id 인용 추적 + 문서 삭제 시 재검토 플래그(스키마 확장 필요, stale 인용이 실사용에서 보고되면 착수) · (c) BM25 축 편입 여부(정확도 실측 후) · (d) 회귀 검증은 §10.7.5 골든셋(recall@10=0.962) 재측정 · ~~(e) thread 삭제 시 큐레이션 유지의 프라이버시 트레이드오프~~ — §6.25에서 회수로 정책을 바꿔 해소.

---

> **§10.11 · §10.12 · §10.13 구현 순서 — `10.11 → 10.13 → 10.12`** (셋 다 완료)
>
> 셋은 독립적으로 보이지만 **§10.13 이 §10.12 의 입력을 바꾼다.** §10.13 은 Direct 답변(문서에 근거하지 않은 텍스트)을 이력에 훨씬 많이 남기고, §10.12 는 그 이력을 재료로 검색어를 만든다. §10.12 를 먼저 만들면 **깨끗한 환경에서 통과시킨 뒤 §10.13 이 조건을 바꿔 놓는다** — 오염(§10.12 열린 항목 (b))이 나중에 드러난다. 순서를 뒤집으면 독립화를 만들 때 그 조건에서 바로 검증하게 된다.
>
> §10.11 이 먼저인 이유는 다르다. 나머지 둘과 얽히지 않으면서 **가장 위험한 구멍**(무검토 유입 — 문서를 하나도 안 본 Direct 답변이 좋아요 한 번에 전체 검색 지식이 된다)을 막는다. 기능 개선이 아니라 사고 예방이라 앞에 둔다.
>
> **§10.13 의 1단계(`DN` 도 `## 요약` 을 내게 한다)는 프롬프트 한 곳 수정이라 언제든 단독으로 나갈 수 있다** — 그 자체로 지금의 요약 경로에서 LLM 호출과 300자 절단을 없앤다. 나머지를 미루더라도 이것만 먼저 넣는 선택지가 있다.

### 10.11 좋아요 → 지식 제안 경유 (§10.10 유입 경로 대체) ✅ 완료 (2026-09-02)

**무엇을 왜.** 검색 코퍼스로 들어가는 문이 둘인데 경비가 정반대였다 — 제안은 관리자 승인 필수, 좋아요는 게이트 없이 3초 뒤 반영. 그 구멍의 가장 날카로운 모양이 **문서를 하나도 안 본 Direct 답변이 좋아요 한 번에 전체 검색 지식이 되는 것**이었다(`onLike()` 가 `allowsCuration()` 만 보고 `direct_mode` 를 안 봤다). 해결은 `direct_mode` 가드를 박는 것이 아니라 **문을 하나로 모으는 것** — 판단이 필요한 일에 판단을 넣는다. 이제 좋아요는 제안 폼을 열어 줄 뿐이고, `curated_qa` 에 쓰는 경로는 관리자 승인 둘(`createFromSubmission`/`createFromLikedTurn`)뿐이다.

**유지해야 할 결정**

- **채팅 = 감상, 제안 페이지 = 지식 관리.** 채팅은 큐레이션 상태를 일절 표시하지 않는다(연필·임베딩 실패 배지 제거). 좋아요 클릭 → 확인 → **예**: 좋아요 기록 + 프리필 이동 / **아니오**: 좋아요 취소. 중간 상태("좋아요는 남기되 제안은 안 함")를 만들지 않는 것이 이 화면의 계약이다.
- **저장 모양이 출처마다 다르다.** 손으로 쓴 제안은 승인 시 미리 나뉜 N개 행, 좋아요 출신은 turn 을 키로 하는 행 하나 → 임베딩 시점에 벡터 N개. `UNIQUE(source_turn_id)`·대화/턴 삭제 회수·재승인이 전부 그 키를 타므로, 좋아요 출신을 `insertManual` 로 태우면 셋이 조용히 함께 죽는다.
- **승인 시 `source_submission_id` 를 반드시 싣는다.** 제안의 상태가 전부 그 컬럼으로만 세어진다.
- **수정은 등록본을 내리지 않는다**(정책 3). 재승인이 교체하며, 교체 방식은 출처별로 다르다 — 손으로 쓴 제안은 이전 행을 먼저 내리고(승인마다 새 id), 좋아요 출신은 제자리 upsert(먼저 내리면 백그라운드 벡터 삭제가 방금 쓴 벡터를 지운다. 같은 id).
- **좋아요 취소는 회수하지 않는다.** 심사를 거친 지식을 내리는 것은 저자(제안 페이지)나 관리자의 일이다. 반면 **턴/대화 삭제는 회수한다** — 그리고 그 회수는 이제 무조건이다(`LIKE` 를 먼저 확인하면 나중에 마음이 바뀐 저자의 엔트리를 전부 놓친다).
- 좋아요 출신은 pending 상한에서 제외한다(버튼 한 번에는 오류를 띄울 자리가 없다). 남용은 턴당 살아 있는 제안 1건 규칙이 막는다.

**정책 변경 3건 — 전부 적용됨**: ① 큐레이션 검색축 통합(`app.search-submission-weight` 제거, `CURATED_ORIGIN` 은 감사·통계용으로 유지) ② C 개방 + S 사유 교체(`allowsCuration()` → `allowsSubmission()`, `feedback.like.disabled.c` 제거) ③ 수정 중에도 등록본 유지.

**계획이 틀렸던 지점**

- **함정 ②의 증상이 달랐다.** "승인 직후 회수됨으로 표시된다"고 적었지만, 연결이 없으면 활성 행이 아니라 **전체 행이 0**으로 세어져 `displayStatus()` 의 회수 분기(`total>0 && active==0`)에 아예 닿지 않는다. 실제로는 **청크 0개짜리 '등록 완료'** 로 뜨고, 관리자가 그 지식을 실제로 내려도 계속 그렇게 뜬다 — 상태가 현실과 끊기는 쪽이라 눈에 덜 띈다.
- **중복 제안 방지(열린 항목 (a))는 3단계가 아니라 프리필과 같은 코드 경로였다** — 1단계에서 함께 넣었다. 따로 만들면 "이미 있는데 두 번째 초안이 열리는" 중간 상태가 생긴다.
- **연필 제거가 서버 코드까지 죽였다** — `updateAnswerForTurn`/`findActiveByTurn`/`findFailedTurnIds` 와 `/ui/threads/{id}/turns/{id}/curated` 두 엔드포인트가 그 버튼 전용이었다. 함께 지웠다.
- **`chunkCount()` 의 뜻을 고쳐야 했다.** 행 수를 세고 있었는데 좋아요 출신은 행 하나가 벡터 N개다 — `SUM(chunk_count)` 로 바꿨다(손으로 쓴 제안에서는 값이 같다).

**열린 항목**

- §10.10 의 열린 항목 (a)(큐레이션 축 similarity threshold 별도화) · (b)(doc_id 인용 추적) · (c)(BM25 축 편입) · (d)(골든셋 재측정)는 그대로 유효하다. 특히 **(d)** — 검색축을 합쳤으므로 recall@10=0.962 재측정이 이 변경의 회귀 검증이다.
- **기존 `origin='like'` 활성 행은 마이그레이션하지 않았다.** 이미 `/admin` 에서 보이고 회수되며, 축이 합쳐져 단일 가중치로 검색된다.

### 10.12 짧은 후속 질문의 독립화 (condense) ✅ 완료 (2026-09-03)

**무엇을 왜.** 검색 축 셋(벡터 · BM25/FTS · 큐레이션)이 전부 **질문 원문**을 보는데 이력은 답변 단계에서만 쓰인다 — `"그 설정은 어디에 있어?"` 는 검색어로 의미가 없고, 사용자에게는 "문서에 있는데 못 찾는다"로 보인다. 하필 그런 질문이 확장도 못 받았다(`app.search-multiquery-min-length` 미만이면 MultiQuery 를 통째로 건너뛴다) — **확장도 맥락도 없는** 조합이 정확히 그 구간에 몰려 있었다. 해결은 질문 자체를 다시 쓰는 것이고, 그 한 값을 **검색 축 셋 · 리랭커 · 분류기가 모두** 쓴다. 구현은 `QuestionCondenser`, 결정 근거·함정은 [PITFALLS.md](PITFALLS.md#servicequestioncondenserjava).

**유지해야 할 결정**

- **재작성은 검색에만 쓰고 답변 프롬프트의 `[현재 질문]` 은 원문을 유지한다.** 사용자가 실제로 쓴 말이 답변의 어조와 초점을 정하고, 재작성이 빗나가도 **검색만 틀리고 답변까지 함께 틀어지지는 않게** 하는 격리다.
- **재료는 이전 *질문*들뿐, 답변은 넣지 않는다.** 답변을 넣으면 ① `MICRO_TEXT` 계층에 무겁고 ② 검색이 자기 답변을 다시 찾는 순환이 생기며 ③ §10.13 이후 캡 없이 이력에 들어가는 Direct 답변의 지어낸 용어가 검색어가 된다.
- **두 게이트는 여집합이어야 한다.** 확장 게이트는 반드시 **원문 길이**로 잰다 — 독립화된 질의는 원문보다 길어지는 것이 정상이라 재작성 결과로 재면 확장까지 함께 돌아 한 턴에 질의 전처리 호출이 둘이 된다. "호출 순증 0"이 성립하는 근거가 이것 하나다.
- **게이트는 순수해야 한다(길이만 본다).** 그래야 긴 질문에서 분류가 즉시 출발해 이력 로딩과의 병렬성이 유지되고, 직렬화되는 것은 독립화가 필요한 짧은 질문뿐이다.
- **실패하면 원문으로 검색한다.** 재료 없음·원문 그대로 반환·호출/파싱 실패가 모두 같은 갈래로 수렴한다.
- **재작성을 화면에 보인다**(`ui.retrieval-metrics-enabled`, 렌더러 셋 + `VerificationSnapshot` 에 저장). 질문 버블에는 원문이 그대로 남으므로, 잘못된 재작성은 "나쁜 검색어"가 아니라 **"엉뚱한 답변"** 으로만 보인다 — 새로고침 후에도 남아야 원인을 짚을 수 있어 `budgetNote` 와 같은 자리에 저장한다.

**계획이 틀렸던(또는 덜 말한) 지점**

- **열린 항목 (b)는 필터가 아니라 재료 선택으로 닫혔다.** 계획은 "재료를 검색을 거친 턴으로 제한하거나 진단 패널에 출처 턴을 보여주라"고 했고, `direct_mode`/`response_mode` 컬럼을 읽는 방법까지 적어 두었다. 실제로는 **답변을 재료에서 뺀 것만으로 오염 경로 자체가 사라진다** — 사용자가 쓴 질문에는 모델이 지어낸 용어가 없으므로 턴별 필터가 필요 없다. 계획이 "답변까지 포함해 재작성하기"를 이미 기각해 두고도 그 기각이 (b)를 함께 푼다는 것을 알아채지 못했다.
- **분류기 문제는 병렬성과 맞바꾸지 않아도 됐다.** "분류가 이력을 기다리게 된다"가 이력을 분류기에 넘기지 않은 이유였는데, 게이트가 이력 없이 판단되면 긴 질문은 **이미 완료된 future** 라 오늘의 병렬성이 그대로다.
- **출력 상한을 계획이 말하지 않았다.** 한 줄짜리 응답에 프로바이더의 `app.llm.max-tokens` 전체가 예약되면 좁은 창에서 `n_ctx` 를 넘기는 것은 프롬프트가 아니라 그 예약이다(§6.26) — `MAX_OUTPUT_TOKENS`(256)로 따로 조인다.
- **on/off 키를 새로 만들지 않았다.** `app.search-multiquery-enabled` 를 공유한다 — 같은 성질의 질의 전처리 호출이고, 여집합 관계가 스위치까지 공유해야 깨지지 않는다.

**열린 항목**

- **(a) 길이 임계값을 그대로 쓸 것인가** — 그대로 열려 있다. `search-multiquery-min-length` 는 "확장할 가치가 있는가"를 재는 값이지 "맥락 의존적인가"를 재는 값이 아니다. 지금은 두 판정이 같은 방향이라 재활용하지만, 오탐이 잦으면 별도 임계값이 필요하다 — 그때 **핫 편집 키를 하나 더 늘릴지**를 함께 판단할 것.
- ~~(b) 이전 턴이 Direct 였을 때의 오염~~ — 재료를 이전 **질문**으로 좁혀 구조적으로 해소(위 참조).
- ~~(c) 재작성 실패 시의 폴백~~ — 설계대로 원문 검색. 구현 완료.
- **(d) 정확도 실측이 아직 없다** — §10.7.5 골든셋(recall@10=0.962)은 단발 질문 세트라 이 변경이 노리는 **후속 질문**을 재지 못한다. §10.10 열린 항목 (d)의 재측정과 함께, 후속 질문이 포함된 세트를 만들지 판단할 것.

---

### 10.13 Direct 턴의 이력 확대 — 문서 자리를 이력에 돌려준다 ✅ 완료 (2026-09-03)

**무엇을 왜.** Direct 답변의 프롬프트에는 `[검색된 문서]` 블록이 통째로 없다. 기본 설정에서 그 자리는 `topK 10 × chunk-size 1,500` ≈ 15,000자인데 이력 상한은 모드와 무관하게 5,000자 고정이었다 — 창의 큰 부분이 놀고, 정작 이력만으로 답해야 하는 모드가 이력을 가장 적게 받았다.

**유지해야 할 결정**

- **규칙은 "Direct 라서"가 아니라 "문서 자리가 비어서"다**: `이력 상한 = 입력 예산 − 문서가 차지할 자리`. 모드별 상수를 두 개 만들면 나중에 `topK` 를 낮춘 배포나 검색 결과가 적게 나온 턴으로의 일반화가 막힌다.
- **1차 적용이 Direct 뿐인 이유는 모드가 아니라 타이밍이다.** 이력은 검색보다 먼저 로딩되고(`AgentService.chat()` 이 이력 로딩과 분류를 병렬로 돌린다) 그 시점에 문서가 몇 개 올지 모르는데, Direct 만 검색이 아예 안 돌아 0 이 확정이다. 같은 이유로 **`meta` 분류는 Direct 로 세지 않는다** — 그 판정은 아직 병렬 스레드에서 계산 중이다.
- **두 이력 경로가 같은 규칙을 읽어야 한다**(`HistoryPolicy`). 요약 경로와 폴백 경로가 갈리면 같은 스레드가 캐시 TTL 을 기점으로 다른 맥락을 본다.
- **렌더는 두 축의 조합이다**: 지금 묻는 턴이 Direct 인가가 *얼마나*를, 이전 턴의 모드가 *무엇을* 정한다. DN 의 순효과는 캡 제거뿐이고 그것이 노림수다.
- **캡 제거와 예산 인상은 한 커밋이어야 한다** — 캡만 빼면 N 답변 하나가 5,000자를 거의 다 먹어 오히려 보는 턴 수가 준다.
- **1단계(DN 도 `## 요약` 을 낸다)는 강제하지 않는다** — 같은 프롬프트를 meta 답변이 쓰므로 인사말에 헤더가 붙으면 안 되고, 형식 미준수와 짧은 답변이 "뺄 게 없으니 전문"이라는 같은 폴백으로 안전하게 수렴한다. **조건은 분량이 아니라 쓸모다**("핵심을 먼저 요약하는 것이 도움이 될 때") — PLAN 초안은 "분량 조건부"였는데, 길이만 재면 코드 한 덩어리나 단계별 절차처럼 그 자체가 개요인 답변에도 요약이 붙는다. 대신 요약이 붙는 빈도가 줄어 `fullyPreSummarized` 의 LLM 호출 절감(1단계의 부수 이득)도 함께 줄어드는 맞바꿈이며, 폴백이 옳으므로 안전하다.

**계획이 틀렸던(또는 덜 말한) 지점**

- **"확대"만이 아니었다.** 규칙을 정직하게 적용하면 좁은 창에서는 오늘의 고정 5,000자보다 **작게** 나온다. 그리고 그것이 옳다 — Direct 경로에는 예산 가드가 없어서 그 5,000자가 이미 창을 넘기고 있었다. 함정 ③(안전망 없음)은 "상한을 올리면 위험해진다"로 적혀 있었지만, 실은 **올리기 전에도 이미 위험했다**.
- **함정 ②의 해석.** "두 경로를 같은 규칙으로 맞춘다"는 이 작업이 <b>새로 만든</b> 규칙에 대한 것으로 읽었다 — 본문이 "이 렌더링 규칙 전체가 '지금 묻는 턴이 Direct 일 때'로 스코프된다"고 못 박고 있어서다. **RAG 턴에 대한 기존 불일치**(요약 경로는 `## 요약`만, 폴백 경로는 전문)는 그대로 남았고, 그것을 없애려면 폴백 경로를 좁혀야 하는데 그건 "RAG 로 물을 때는 지금 동작 그대로"와 정면으로 부딪힌다. 별도 판단이 필요하다(아래 열린 항목).
- **`ResponseModeBranchConventionTest` 는 텍스트 검사다.** 주석에 모드 값 비교 패턴을 적는 것만으로 빌드가 깨진다 — 설계 의도를 주석으로 설명하려다 걸렸다.
- **`trimHistory` 는 옮겨야 했다.** Direct 경로도 같은 절단이 필요해지면서 `AnswerService` 의 private static 두 개가 `HistoryPolicy` 로 이동했다(구현은 하나로 유지).

**열린 항목**

- **(a) RAG 턴에도 같은 계산을 적용할 것인가** — 그대로 열려 있다. 로딩 시점에는 `topK × chunk-size` 상한으로 추정할 수밖에 없고 실제 검색 결과는 그보다 적은 것이 보통이라, 과대 추정하면 이력이 줄어 지금보다 나빠진다.
- **(b) RAG 턴에 대한 두 경로의 기존 불일치** — 위 참조. 맞추려면 폴백 경로가 RAG 턴을 `## 요약`만으로 좁혀야 하는데, 그것은 요약 캐시가 아직 없는 **짧은 스레드**(1~2턴)에서 맥락을 잃는다. 실사용에서 재현성 문제가 보고되면 착수.
- **(c) 넓어진 사실을 사용자에게 보일 것인가** — **줄어든 쪽만 말하도록 했다**(`budgetNote`, RAG 축소와 같은 자리·같은 문구). "더 많이 기억한다"는 안내는 노이즈에 가깝다고 보고 넣지 않았지만, 한 스레드에 RAG·Direct 턴이 섞이면 *"아까는 기억했는데 왜 지금은 못 하지?"* 가 여전히 나온다. §10.12(짧은 후속 질문의 독립화)의 진단 패널과 함께 판단하는 편이 낫다.

**이후 변경 (2026-09-04)** — 규칙 둘을 더했다. 구현·함정은 [PITFALLS.md](PITFALLS.md#servicehistorypolicyjava).

- **이전 턴이 Direct 였으면 RAG 로 물어도 전문을 싣는다.** 요약으로 줄이는 근거가 "RAG 턴은 검색을 다시 하므로 이전 답변 전문은 `[검색된 문서]` 의 복제"인데, Direct 턴에는 복제될 문서가 **애초에 없었다** — 사용자가 Direct 를 고른 것 자체가 "문서 밖 이야기를 하자"는 의도이고, 줄이면 그 의도가 다음 턴에서 사라진다. 조건을 "DN 이면"이 아니라 **"Direct 였으면"** 으로 잡아 값이 아니라 성질로 분기하는 규칙을 유지했다(DS 는 요약이 곧 답변 전부라 결과가 같고, C 는 Direct 와 배타). **감수한 대가**: 검증 프롬프트에는 이력이 들어가지 않으므로(`AnswerService.buildEvalPrompt`) 모델이 그 산문에 기대면 `grounded=false` 가 날 수 있다 — 답변 시스템 프롬프트가 "[검색된 문서]에 포함된 내용만 근거로"라고 못 박고 있고, 실패해도 재시도가 답변만 다시 쓰며(§6.27) 최종적으로는 미검증 배지로 정직하게 드러난다.
- **프롬프트에 싣는 턴 수 상한**(`HistoryPolicy.promptTurnCap()` = `app.memory.fetch-limit-turns` 의 절반, 기본 5) — RAG·Direct 양쪽, 두 경로 모두. 가져오는 창을 그대로 줄이지 않는 이유는 요약 경로가 **가져온 것 전부**를 요약 재료로 쓰기 때문이다(창을 줄이면 요약이 볼 과거까지 함께 줄어 "오래된 것은 압축, 최근 것은 원문"이라는 구조가 무너진다). 글자 예산과도 독립이다 — 예산은 "창에 들어가는가", 이 상한은 "얼마나 오래된 이야기까지 원문으로 되살릴 것인가"를 정한다.
- **위 열린 항목 (b)의 전제가 이미 틀려 있었다.** "폴백 경로는 전문"이라고 적혀 있지만 §10.13 구현에서 `getHistory()` 도 `renderAnswer` 를 타므로 렌더 규칙은 이미 같았다(`DirectHistoryFallbackTest` 가 그것을 고정한다). 실제로 남아 있던 차이는 **분량**(폴백 10턴 vs 요약 경로 2턴)이었고, 이번 턴 상한이 그 격차를 5 대 2로 좁혔다. 같은 이유로 `ConversationSummarizerService.precompute()` 의 "폴백으로 떨어지면 답변 전문이 되돌아온다"는 주석도 함께 고쳤다.

---

### 10.14 청크 오류 신고 — 사용자 → 관리자 ✅ 완료 (2026-09-04)

**무엇을 왜.** 답변의 출처 배지를 눌러 원문(`#chunkFullTextModal`)까지 연 사용자가 **그 청크의 내용이 틀렸거나 오래됐다는 것을 발견해도 말할 곳이 없다**. 지금 그 화면에 있는 두 동작은 서로 다른 질문에 답한다 — 👍/👎 는 **답변**에 대한 평가라 어느 청크가 문제인지 담지 못하고(`conversation_turns.feedback` 한 칸), "현재 대화에서 이 청크 제거"는 **청크 자체는 멀쩡한데 이 답변과 무관할 때** 쓰는 표시 전용 동작이라 서버도 `turn_source_ref.hidden_at` 만 세운다(문서·벡터·재사용 판정 전부 그대로). 그래서 **내용이 틀린** 청크는 그 대화에서만 조용히 치워지고 다음 사람에게 똑같이 검색된다. §10.11 이 "코퍼스에 **넣는** 문"을 관리자 승인 하나로 모았다면, 이 항목은 아직 없는 **"고치자"의 문**을 같은 모양(사용자 제출 → 관리자 판단)으로 하나 낸다.

**구현 결과 (0~5단계 전부).** `ChunkReportRepository`(`chunk_report`, 운영 DB) · `ChunkReportService` · `ChunkReportController`(`POST /ui/chunk-reports`) · `AdminController` 의 `/admin/chunk-reports/**` 넷과, 채팅 원문 보기 모달의 🚩 버튼 + 신고 폼 · `/admin` 의 청크 단위 대기열 카드/오프캔버스 · 헤더 배지. 결정 근거와 함정은 [PITFALLS.md](PITFALLS.md#servicechunkreportservicejava).

**계획대로 되지 않은 지점**

- **중복 방지 키가 설계 중에 바뀌었다.** 처음에는 (청크, 신고자)였는데, 그러면 `guest-identity=shared` 배포에서 전 방문자가 `GUEST_ID` 하나라 **청크당 한 명만** 신고할 수 있게 되어 "여러 명이 신고한다"는 전제와 정면으로 부딪힌다 — `thread_id` 를 키에 넣어 해결했다. 사용자당 건수 상한을 두지 않기로 한 결정(⑥)도 같은 뿌리다.
- **0단계는 예상대로 진짜 버그였다.** `fragments/message-assistant.html` 만 출처를 `<ul>/<li>` 로 그리며 `data-chunk-id` 를 빠뜨려, 논스트리밍 폴백 경로에서는 출처를 눌러도 모달 자체가 열리지 않았다(제거·신고가 전부 그 안에 있으므로 함께 죽는다). `ChatControllerHtmxTest` 가 그 속성을 고정한다.
- **생성자 주입 하나가 기존 테스트 둘을 깨뜨린다.** `AdminController` 에 서비스를 하나 더 넣으면 `AdminControllerWebMvcTest`·`ManagementOnlyAuthorizationTest` 의 컨텍스트가 뜨지 않는다(`@MockitoBean` 누락). 컨트롤러에 협력자를 추가할 때 함께 고쳐야 하는 자리다.
- **"현재 내용"이 백엔드마다 다른 텍스트라는 것을 계획이 말하지 않았다.** 함정 ⑤로 적어 둔 것은 표시 라벨 문제였는데, 실제로는 `vec_document_chunks` 가 **없는 배포에서 쿼리 자체가 예외**가 되는 문제이기도 했다(테이블 존재 확인 후 조회).

**두 동작의 경계 — 화면에서 말해야 한다.** 같은 모달 헤더에 나란히 서므로 라벨만으로 갈라져야 한다: *제거* 는 「이 답변과 상관없는 청크」(대화 표시에서만 사라짐, 참여도 0%일 때만 노출), *신고* 는 「내용이 틀렸거나 업데이트가 필요함」(관리자에게 전달, 참여도와 무관하게 항상 노출). 사유 코드에 "질문과 무관"을 두지 않는 이유가 이것이다 — 그건 이미 제거 버튼의 몫이고, 목록에 넣는 순간 표시 전용 동작이 관리자 대기열로 잘못 흘러든다.

**전제 — 현재 코드 확인 (2026-09-04)**

- 버튼이 붙을 자리는 이미 있다: `chat.html` 의 `#chunkFullTextModal` 헤더(`#chunk-exclude-btn` 옆).
- 모달을 여는 클릭 위임은 `badge.dataset.chunkId` 가 없으면 **아무 일도 하지 않는다**. 출처 렌더러 넷 중 `fragments/message-assistant.html`(논스트리밍 폴백)만 `.source-item` 래퍼와 `data-chunk-id`/`data-turn-id`/`data-share` 를 싣지 않아 **그 경로에서는 원문 보기 자체가 안 열린다** — 신고 이전에 이미 있는 버그이고, 0단계가 그것을 먼저 맞춘다.
- 모달의 `activeChunkContext` 는 **제거 가능할 때만** 채워진다(`turnId && chunkId && 응답 참여도 0%`). 신고는 참여도와 무관해야 하므로 이 변수를 재사용하면 *답변에 기여한 청크일수록 신고가 안 되는* 정확히 반대 게이트가 생긴다.
- 청크 → 문서·컬렉션 해석 경로가 이미 있다: `chunk_fts(spring_doc_id, doc_id, version, filename, …)` 한 행이면 `AdminService.collectionFor(version)` 을 거쳐 `/admin` 의 `openChunkEdit(chunkId, collection)` 로 그대로 연결된다.
- "신고 이후 청크가 바뀌었나"는 `QuestionReuseService.currentChunkHash(chunkId)` 대조로 알 수 있다(§6.23 이 쓰는 그 해시).
- 관리자 대기열의 형태는 지식 제안이 정립해 뒀다: `/admin` 의 `<details hx-get=… hx-trigger="toggle[this.open] once">` 지연 로딩 카드 + `base.html` `pollBadge` 의 60초 배지.

**설계 결정 (유지해야 할 것)**

1. **신고는 아무것도 바꾸지 않는다.** 청크·벡터·FTS·재사용 판정 어디에도 쓰이지 않는 순수 대기열이고, 반영은 관리자가 청크를 실제로 고칠 때 비로소 일어난다. "N건 이상이면 자동 비활성화"는 기각 — 검색 코퍼스에서 **나가는** 문도 사람이 지켜야 §10.11 이 좁혀 놓은 불변식과 방향이 맞는다(들어오는 문만 지키고 나가는 문을 자동화하면 신고가 곧 삭제 버튼이 된다).
2. **신고자는 고칠 수 없다.** 제출물은 사유 코드 + 코멘트뿐이고 수정본 텍스트를 받지 않는다. 사용자가 고친 문장을 코퍼스에 넣고 싶다면 그 경로는 이미 지식 제안(§10.11)이고, 입구가 둘이 되면 승인 절차도 둘로 갈린다.
3. **코멘트는 필수다(짧게, 500자).** 사유 코드만으로는 관리자가 청크의 **어디를** 고쳐야 하는지 알 수 없어 결국 다시 물어야 한다 — 대기열에 "무엇이 틀렸는지 모르는 신고"가 쌓이는 것이 이 기능의 가장 흔한 실패 모양이다. 사유 코드는 4종으로 고정한다: `WRONG`(사실이 틀림) · `OUTDATED`(오래됨·업데이트 필요) · `BROKEN`(깨진 텍스트/표/이미지 — 변환 오류) · `OTHER`(기타).
4. **관리자는 신고가 아니라 *청크* 단위로 본다.** 한 청크에 여러 사람이 신고하는 것이 정상이므로 목록 1행 = 청크 1개(열린 신고 N건, 최근 신고 시각, 문서명)이고, 펼치면 **그 청크의 코멘트 N개가 한 화면에** 나온다(각각 사유 코드·코멘트·신고 시각·당시 질문). 조치(처리 완료/반려)도 그룹 단위 — 관리자가 고치는 대상은 청크 하나이지 신고 3건이 아니다. 같은 `review_note`/`reviewer`/`reviewed_at` 이 그 그룹의 모든 행에 찍힌다.
5. **중복 방지 키는 (청크, 신고자, 대화)다.** `WHERE status = open` 조건의 부분 UNIQUE 인덱스. **(청크, 신고자)로 잠그면 안 된다** — no-auth 기본값(`app.auth.guest-identity=shared`)에서는 전 방문자의 `userId` 가 상수 `GUEST_ID` 라 배포 전체에서 한 사람만 신고할 수 있게 되어 ④("여러 명이 신고한다")와 정면으로 부딪힌다. `thread_id` 는 방문자·대화 단위로 갈라지므로 남의 신고를 막지 않으면서 같은 대화에서의 중복 클릭만 막는다. 제출 후 버튼은 "신고됨"으로 바뀐다.
6. **사용자당 건수 상한은 두지 않는다.** 지식 제안의 `MAX_PENDING_PER_USER=20` 을 그대로 옮기면 같은 이유(⑤)로 `shared` 모드 배포 전체가 20건에서 멈춘다. 남용은 ⑤ + `RateLimitFilter` 의 `default` 버킷으로 충분하다 — 따라서 신고 엔드포인트 경로에 `/chat`·`/documents` 문자열이 들어가면 안 된다(`policyFor()` 는 경로 부분 문자열로 버킷을 고른다).
7. **상태는 저장하지 않고 조회 시점에 파생한다** (`CuratedSubmissionRepository.STATUS_REVOKED` 선례). 신고 시점의 `chunk_hash` 를 스냅샷하고 관리자 화면에서 현재 해시와 대조해 `신고 이후 수정됨`/`삭제됨` 을 만든다. 청크를 건드리는 경로마다(편집·단일 재인덱싱·삭제·문서 재인덱싱·문서 삭제) 신고 테이블로 훅을 거는 대안은 새 경로가 생길 때 조용히 빠진다 — CLAUDE.md 「§ 청크 변경 표시/차단」이 관리 중인 그 목록에 하나를 더 얹지 않는다.
8. **본문·질문 스냅샷을 함께 저장한다.** 관리자가 볼 때쯤 청크는 재인덱싱으로 사라졌을 수 있고, 그러면 "무엇이 틀렸다는 것인지"가 통째로 없어진다. 질문도 같은 이유로 복사한다 — 대화 삭제(§6.25)를 견뎌야 하므로 FK 가 아니라 복사본으로 잇는다(`curated_qa` 와 같은 성격). 본문 스냅샷은 **행마다** 남긴다: 3주 전 신고와 어제 신고가 서로 다른 원문을 가리킬 수 있고, 그 차이 자체가 "이미 한 번 고쳐졌다"는 정보다.
9. **관리자 조치는 판정만 남긴다**(처리 완료 / 반려 + 사유). 실제 수정은 기존 청크 편집 오프캔버스가 한다 — 신고 패널이 자체 편집기를 갖는 순간 편집 경로가 둘이 되어 `MetaKey.EDITED_AT` 스탬프·재인덱싱 사전 경고(`countEditedChunks`)·`QuestionReuseService` 통지가 한쪽에만 붙는다.
10. **신고자에게 되돌려 주는 알림은 범위 밖이다.** 그래서 `reporter_read_at` 같은 읽음 컬럼도 두지 않는다 — 쓰지 않을 컬럼을 미리 만들면 "알림이 있는 줄 알았다"는 오해만 남는다. 필요해지면 그때 `curated_submission.author_read_at` 선례를 그대로 따르면 된다.

**작업 단계**

- **0단계 (선행, 독립 커밋 가능)** — `fragments/message-assistant.html` 의 출처 `<li>` 를 `.source-item` 래퍼 + `data-turn-id`/`data-share`/`data-chunk-id` 로 맞춰 렌더러 넷을 통일한다. 이것만으로 폴백 경로의 원문 보기가 살아난다.
- **1단계 저장** — `ChunkReportRepository`(런타임 멱등 DDL, **한정자 없는 `JdbcTemplate`** — `@Qualifier("vectorJdbcTemplate")` 아니다. 그 빈이 실제로 어느 파일을 가리키는지는 배포 설정에 달렸다: `SQLITE_VEC_DB_PATH` 를 켜면 다른 운영 테이블과 함께 벡터 DB 파일에 생긴다 — 아래 «계획이 몰랐던 것» 참조). `chunk_report(id, chunk_id, doc_id, version, reporter_user_id, thread_id, turn_id, question, reason_code, comment, chunk_hash, chunk_snapshot, status, reviewer_user_id, review_note, created_at, reviewed_at)` + `idx_chunk_report_open(status, chunk_id)` · `idx_chunk_report_chunk(chunk_id)` · (청크, 신고자, 대화) 부분 UNIQUE 인덱스(열린 신고에만).
- **2단계 서비스** — `ChunkReportService`: `report()` / `openGroups(offset, limit)`(청크별 집계) / `group(chunkId)`(그 청크의 열린 신고 전부 + 현재 청크 상태) / `resolveChunk(chunkId, reviewer, note)` / `rejectChunk(chunkId, reviewer, reason)`. 그룹 전이는 `chunk_id` + 열린 상태를 조건으로 하는 `UPDATE` 한 문장이라 자연히 compare-and-set 이고, 관리자가 보던 사이에 들어온 신고까지 함께 닫히는 것이 맞다(같은 청크에 대한 같은 조치다). `AuditLogger` 이벤트 `chunk.report` · `chunk.report.resolve` · `chunk.report.reject`(건수 포함).
- **3단계 사용자 화면** — 모달 헤더에 🚩 「내용 오류 신고」(항상 노출) → 사유 라디오 4종 + 코멘트(필수, 500자) → `POST /ui/chunk-reports`. 중복은 "이미 신고하셨습니다"로 구분해 보여준다. 제거 버튼에는 "이 답변과 상관없는 청크일 때" 힌트를 붙여 둘을 갈라 놓는다. i18n 키는 `chat.chunk.report.*`(ko/en 양쪽).
- **4단계 관리자 화면** — `/admin` 에 지연 로딩 카드(`GET /admin/chunk-reports` → `fragments/admin-chunk-reports`, **청크별 1행 + 신고 건수 배지**), 그룹 상세 오프캔버스(`GET /admin/chunk-reports/chunks/{chunkId}` — 신고 N건의 사유·코멘트·시각·당시 질문 + 최초/최근 본문 스냅샷 + 현재 청크 내용 + 변경 여부 + `openChunkEdit(chunkId, collection)` 바로가기), `POST …/chunks/{chunkId}/resolve`·`…/reject`, `GET /admin/chunk-reports/open-count`(= **열린 신고가 있는 청크 수**) + 헤더 배지.
- **5단계 문서·테스트** — CLAUDE.md Key Files 3행 + 불변식 1줄, [PITFALLS.md](PITFALLS.md) 항목, USER_MANUAL(신고 방법과 "제거"와의 차이)·OPERATOR_MANUAL(처리 절차). 테스트: `ChunkReportServiceTest`(검증·중복 키·그룹 집계·그룹 전이), `AdminChunkReportControllerTest`(`@WebMvcTest` → `@ResourceLock("global-state")` 필수), `ChatControllerHtmxTest` 확장(신고 POST 200/중복 거부/게스트 허용).

**완료 기준**

- 스트리밍 · 논스트리밍 폴백 · 기록 복원 · DB 재사용 네 경로 모두에서 출처 → 원문 보기 → 신고가 동작한다.
- **한 청크에 서로 다른 사용자 3명이 신고하면 관리자 목록에 청크 1행(건수 3)으로 뜨고, 펼치면 코멘트 3개가 한 화면에 보인다.** `guest-identity=shared` 에서도 그렇다(대화가 다르면 다른 신고로 들어온다).
- 같은 대화에서 같은 청크를 두 번 신고하면 두 번째는 거부되고 대기열 행이 늘지 않는다.
- 그룹 「처리 완료」 한 번으로 그 청크의 열린 신고가 전부 닫히고, 배지 수(열린 청크 수)가 1 줄어든다.
- 신고 후 관리자가 그 청크를 고치면 상세에 "신고 이후 수정됨", 삭제하면 "삭제됨"이 뜨고 **본문 스냅샷은 남는다**.
- 신고가 검색 결과·재사용 판정·`turn_source_ref` 에 아무 영향이 없다(회귀 0).

**함정**

1. `activeChunkContext` 재사용 — 위 전제 참조. 신고용 컨텍스트를 따로 둔다(참여도 게이트를 물려받으면 안 된다).
2. 엔드포인트를 `/api/v1/**` 아래 두면 **CSRF 예외 + management-only 게스트 개방**이 함께 붙는다(`AdminController` 가 같은 이유로 `/admin/**` 을 골랐다). 사용자 쓰기는 `/ui/**`, 관리자 조치는 `/admin/**`.
3. **배지·집계 단위를 헷갈리면 안 된다** — 대기열의 단위는 신고 건수가 아니라 **열린 신고를 가진 청크 수**다. 건수로 세면 한 청크에 10명이 신고했을 때 "밀린 일이 10건"으로 보이지만 관리자가 할 일은 하나다.
4. 큐레이션 청크는 `chunk_fts` 에 색인되지 않는다 — Chroma 백엔드에서는 `findChunkFullText()` 가 이미 null(원문 보기 404)이다. 신고 자체는 되지만 관리자 바로가기는 청크 편집이 아니라 **큐레이션 패널**로 보내야 한다(version 이 `curated`).
5. `chunk_fts.content` 는 원문이 아니라 파생 검색 텍스트다(§10.1). "현재 내용"으로 보여줄 때 sqlite-vec 는 `vec_document_chunks.content`(원문), Chroma 는 FTS 파생 텍스트가 나오므로 스냅샷과 나란히 놓는 화면에 **어느 쪽인지 라벨이 필요하다**.
6. 배지 폴링을 하나 더 얹는다 — `pollBadge` 는 배지 엘리먼트가 없으면 요청 자체를 보내지 않으므로 비관리자에겐 무해하다(`pending-submission-badge` 선례).

**열린 항목**

- **(a) 문서 단위 묶음 보기** — 한 문서의 여러 청크에 신고가 흩어지면 "이 문서를 다시 인덱싱해야 한다"가 진짜 조치일 수 있다. 청크별 그룹이 먼저이고, 문서 단위 집계는 실제로 그런 패턴이 보일 때 얹는다.
- **(b) 신고를 검색 품질 지표로 쓸 것인가** — §10.7.5 골든셋과 연결하면 "사람이 표시한 오답 청크"라는 라벨이 생긴다.
- **(c) 개별 신고 반려** — 지금은 조치가 그룹 단위다. 한 청크의 신고 3건 중 하나만 틀린 지적인 경우가 반복되면 행 단위 반려를 얹을지 판단한다(그때도 기본 동선은 그룹이어야 한다).
- ~~(d) 신고자에게 처리 결과 알림~~ — **범위 제외**(설계 결정 ⑩).

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

> ⚠️ **분리 배포에서 Flyway는 실데이터에 닿지 않는다** (2026-09-04 확인): `SQLITE_VEC_DB_PATH` 를 설정하면 운영 테이블까지 벡터 DB 파일에 만들어지는데(원인은 [PITFALLS](PITFALLS.md#벡터-스토어-백엔드와-vecfts-datasource) 의 `JdbcTemplate` 자동설정 백오프), Flyway 는 `@Primary` DataSource(`memory.db`)에만 적용되고 이력도 거기 남는다. 즉 **새 `V4__*.sql` 을 추가하면 빈 파일에만 적용되고 "성공"으로 보고된다.** 아래 지침(런타임 `ALTER` 패턴)은 이제 취향이 아니라 **그 배포에서 유일하게 동작하는 방법**이다.
>
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
