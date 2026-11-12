# RAG-Agent 온라인 확장 개발 계획

> Java 개발자 관점 · Spring Boot 4.1 + Spring AI 2.0 + Java 21 · 작성일 2026-05-11  
> **개발 기준 문서**: 이 파일(documents/PLAN.md)이 마스터. 살아 있는 동작 명세는 코드와 [CLAUDE.md](../CLAUDE.md), 결정 근거·실패 기록은 [PITFALLS.md](PITFALLS.md), 생각 수준 실측 결과는 [THINKING_EVALUATION.md](THINKING_EVALUATION.md), Agent·MCP 전환 설계는 [AGENT_MCP_DESIGN.md](AGENT_MCP_DESIGN.md).

---

## 📊 전체 현황 대시보드

> 완료/미착수를 한눈에 보도록 상단 대시보드를 신설했다.
>
> **문서 분리 (2026-09-02 적용)**: 완료 절의 «유지해야 할 결정»·«계획이 틀렸던 지점»과 해소된 이슈는 [PITFALLS.md](PITFALLS.md) 로 옮겼다. 이 파일은 **앞으로 할 일과 이미 한 일의 요약**을 담고, **왜 그렇게 했고 무엇이 틀렸는지**는 그쪽이다 — 계획을 볼 때와 코드를 고칠 때 필요한 것이 다르기 때문이다.

> **완료 항목 압축 원칙 (2026-08-22 적용)**: ✅ 완료된 항목은 **① 무엇을 왜 그렇게 했는가 ② 앞으로도 지켜야 할 결정·함정 ③ 남은 열린 항목**만 남기고, 구현 과정 서술·테스트 통계·CLAUDE.md에 이미 있는 메커니즘 설명은 걷어낸다. 살아 있는 동작 명세의 단일 출처는 **코드와 CLAUDE.md**이고, 이 문서는 "왜 그렇게 결정했는가"의 기록이다 — 양쪽에 같은 설명을 두면 드리프트만 생긴다(§12가 의존성에 대해 같은 판단을 이미 적용했다).

### ✅ 완료 — Phase 1·2·5·6·7 전체, Phase 3 전체(§6.16.2·6.19.1·6.20 제외)

> **최근 완료 요약 (최신순)** — 날짜별 한두 문장이다. 구현 설명·측정·열린 항목은 각 § 절과 [PITFALLS.md](PITFALLS.md), 측정 보고서에 있다. 압축 전 전문은 커밋 `d1f7290`(2026-10-26 판) · 이 파일을 한 번 더 줄인 2026-11-11 이전 판본은 그 직전 커밋의 이 대시보드에 있다.
>
> - **2026-11-06~11 §6.33 Agent·MCP 전환(0~5단계 구현)** — 0단계(블로킹 호출 `model` 누락 → `ModelPinningChatModel`) · 1a 검색 개선(`HybridSearchService` 추출 · 교차 인코더 리랭커 · 재검색 루프 · 독립화한 후속 질문의 원문 후보 풀 합치기) · 1b 읽기 Tool 계층 · 2 MCP 서버 · 3 Tool 선택(프롬프트 JSON, `TOOL` 노드, 기본 꺼짐) · 4 영향도 분석(인용은 코드가 검증, 단어 경계 가드)·유지보수(수정안 → 문서 단위 승인 → DB 백업·임베딩 먼저·변환 MD 되쓰기) · 5 `agent_tool_call`(V14)과 `/admin` "Tool 호출 추적" 패널. 11-11 실측: 영향도 판정 회수 24/24 · 후속 질문 recall@5 0.857 · Tool 선택 47/49(비관리자). 남은 것은 §6.33 절.
> - **2026-11-04 §6.32 긴 질문 검색 · 그림 설명 · 청크 그림 편집** — 질문 한도 10,000자(`QuestionLimit` 가 창 기준으로도 거절) · 500자 초과 질문의 2단계 검색 질의 · 프롬프트 전용 이미지 마커 축약 · 그림 설명 `형식: 한두 문장` · `/admin` 청크 그림 편집 · `/settings`·`/admin` 화면 정리.
> - **2026-10-26~27 §6.30 프롬프트 축소 사다리**(`PromptLadder` + 검증 실패 재시도 A~D) · **§6.31 이력 제외 체크박스** — 측정은 [PROMPT_LADDER_EVALUATION.md](PROMPT_LADDER_EVALUATION.md).
> - **2026-09-01 ~ 10-23 (한 줄 요약)** — `/settings` 프로바이더 편집(`ProviderCatalog`, V10) · 시각의 단일 규칙(DB UTC · `app.time-zone` · 화면은 브라우저 변환) · 후속 질문 독립화 재측정(그대로 둠) · 유사도 임계값 폴백 · SQLite 정기 백업(`DatabaseBackupService`) · 로컬 LLM 이중화·장애 전환(`StreamFailover`) · 프로바이더 상태 열 · §6.29 생각 수준 제어 · §6.28 장애 가시성 · §10.15 질문 추천 · §6.19.4 폐쇄망 하드닝 · §6.26 컨텍스트 초과 대응 · §6.15 저장 상한 · §6.27 재시도 개선 · §10.11 지식 제안 경유 · §10.12 독립화 · §10.13 Direct 이력 확대 · §10.14 청크 오류 신고. **운영 결정 두 건**: 출하값을 `temperature` 0.2 · `direct-temperature` 0.5 · `max-tokens` 12,000 · 생각 수준 전부 끔으로 바꿨고(10-07, 커밋 `390a8a4`), `max-tokens` 허용 범위를 지원하는 최소 창 16K 기준으로 줄였다(10-23, PITFALLS §6.26 A6). 생각 토큰 예산은 구현하지 않기로 했다(10-12).

| Phase | 완료 항목 | 상세 |
|---|---|---|
| **Phase 1** — 보안 기반 | Step 1.1~1.6 전체(Caddy·Flyway·Spring Security·멀티유저 격리·CSRF·로그인/회원가입 UI) + `app.auth.enabled` no-auth 토글 | §4 |
| **Phase 2** — 모바일 UI | 반응형 레이아웃(Offcanvas) · PWA(manifest/SW/오프라인) · 다크모드·접근성 | §5 |
| **Phase 3** — 운영 견고화 | §6.1 Rate limit · §6.2 업로드 검증(매직바이트) · §6.15 전역 저장 상한 · §6.3 예외처리 · §6.4 감사로그 · §6.5 임베딩 사용량 분리 · §6.6 비활성 프로바이더 표시 · §6.7 orphan 기록 삭제 · §6.8 피드백 기반 컨텍스트 제외 · §6.9 요약 선계산 · §6.10 백그라운드 사용량 분리 · §6.11 컨텍스트 예산 정합성 · §6.12 다중 사용자 동시 LLM 처리(동시성 게이트+백프레셔+로드밸런싱) · §6.13 설정 페이지(핫 수정 오버라이드) · §6.14 핵심 채팅 경로 추적 · §6.16.1 스트리밍/인덱싱 중단 버튼 · §6.17 관리 전용 인증(B안) · §6.18 Direct temperature 분리 · §6.19.2 `/admin`·actuator RBAC · §6.19.3 XFF 신뢰 옵트인 · §6.19.4 폐쇄망 실사 하드닝 · §6.21 소형 LLM 분리+멀티 LLM 처리량 확장 · §6.22 접속자별 채팅 개인화(no-auth) · §6.23 청크 변경 시 답변 재사용 무효화·대화 기록 표시 · §6.24 응답 모드 재설계(S/N/C — L 제거·모드별 전용 프롬프트·오염 방지 3건, `4-c`만 미착수) · §6.25 관리자 대화 목록·삭제·검색 진단 연결 | §6 |
| **Phase 5** — Vector Store | Step 5.1~5.10 전체(Chroma↔sqlite-vec 런타임 전환, 관리자 페이지, 태그 검색, 운영/벡터 DB 분리 — 5.10 은 2026-09-28 단일 파일로 환원) | §8 |
| **Phase 6** — 폐쇄망/노-도커 | G1~G5(키리스 LOCAL·차원 외부화·라우팅 외부화·런북·무외부호출 인수) | §9 |
| **Phase 7** — 검색 품질·성능 고도화 | §10.1~10.9 전체(17건) — 정확도·속도·메모리 개선 + recall@k/nDCG@k 평가 하네스(baseline recall@10=0.962). **+ §10.10** — 좋아요 기반 큐레이션 Q&A 지식화(스냅샷·임베딩·검색 융합·관리 UI) 완료 | §10 |

추가로 Phase 3 초기에 완료된 항목(문서화되지 않았던 픽스 포함): ChromaDB v2 API 컬렉션명→UUID 자동 변환, 문서 저장 경로 공유 구조 단순화(`DocRegistry.SHARED`), 인덱싱 SSE 진행 단계별 표시, 키워드 추출 타임아웃 시 CircuitBreaker 오동작 수정, DOCX 변환 전 구버전 아티팩트 삭제 순서 수정, 환경변수 외부화 4건, 의존성 최신 stable 일괄 업데이트(정확한 버전은 pom.xml 참조).

### 🔵 진행할 것 (우선순위 순)

> **재우선순위화**: 실배포 기준(폐쇄망·no-auth 단일 운영자)에서 가치가 없는 **멀티유저(`auth.enabled=true`) 전용 작업**(§6.19·§6.20·§6.16.2·Phase 4)은 전부 후속으로 내렸다(사유는 아래 후속 표의 트리거 열 참조). ⚠️ **§6.19는 이 판단이 절반만 맞았다** — 2026-09-07 실사에서 no-auth·management-only 조합에서만 성립하는 노출면이 여럿 나와 §6.19.4로 처리했다. 남은 것은 §6.19.1(API CSRF)뿐이고 그것은 여전히 인증 모드 전용이다. §6.15(스토리지 쿼터)는 전역 상한이라 단일 운영자에도 적용되므로 이 그룹에 남겨 두었고, 2026-09-01 완료됐다. **§6.25(관리자 대화 목록)도 이름은 §7.3(Phase 4) 계열이지만 같은 이유로 지금 진행 그룹이다** — `app.auth.guest-identity`가 `shared`가 아니면 no-auth 단일 운영자 배포에도 방문자별 대화가 쌓이고, 그중 Step 1은 인증 모드와 무관한 검색 오염 버그 수정이다.

**🟢 지금 진행 (no-auth 단일 운영자 배포에도 바로 적용)**

> 끝난 항목(§10.11~10.14 · §6.29~6.32 · §6.33 0단계 등)의 취소선 행은 2026-11-11 에 걷었다 — 위 요약과 각 절을 본다. 이 표에는 **남은 일만** 둔다.

| 순위 | 항목 | 현재 상태 |
|---|---|---|
| 1 | **§6.33 Agent·MCP 전환** 마무리 | 🟡 0~5단계 구현·종단 확인 완료. **남은 것**: `AGENT_ENABLED=true` 로 켠 실사용 2주 관측(폴백 비율·턴 지연) → 기본값 판단 · 백업에서 되돌리기 리허설 · 실제 질문 로그로의 Tool 선택 재측정 · MCP Inspector·Claude Code 연결 확인 · 영향도 실모델 실측의 가짜 인용 검증. [AGENT_MCP_DESIGN.md](AGENT_MCP_DESIGN.md) |
| 2 | 운영 준비 잔여 — Caddy 인증서 만료 모니터링 | 미착수. (SQLite 정기 백업은 2026-10-13 완료 — 앱 안의 `DatabaseBackupService`, 담지 않는 것은 문서 파일·Chroma 벡터, OPERATOR_MANUAL §6.3.2) |
| ~~3~~ | ~~질문 한도 10,000자의 열린 전제 둘~~ | ✅ 2026-11-12 둘 다 완료 — ③ 요약 입력의 과거 질문 500자 상한(`buildSummaryInput`) · ④ Direct 경로의 축소 재시도(`DirectAnswerService.withHistoryShrink`). §6.30 연관 작업·§6.32 |
| 4 | §6.24 `4-c` — 검색 부스트 상향 | 부스트 기본값이 0이라 미착수. 올리기 전에 확인할 것은 `MAX_EVAL_EXCERPT_CHARS` 상수가 아니라 **대상 프로바이더의 실제 창**이다(검증 발췌 상한이 창에서 파생된다 — `evalExcerptTokenBudget`) |
| 5 | §6.29 열린 항목 — 측정 못 한 사이트 넷(`SUMMARY`·`IMAGE_DESCRIBE`·`MD_CORRECT_VISION` 등) · 유휴 워치독이 블로킹 호출을 보지 못함 | §6.29 열린 항목 참고 |
| 6 | §9.4 — CADDY 하위호환 별칭 | 선택, 낮은 우선순위 |
| 7 | Phase 2 남은 실기기 검증 2건 (키보드 하단 고정 · 홈 화면 standalone) | 좌우 스크롤·다크모드는 자동 검증 완료, 나머지는 실기기 필요 |

**🟣 후속 — 멀티유저(`auth.enabled=true`) 활성화 시에만 착수**

| 순위 | 항목 | 트리거 |
|---|---|---|
| 1 | **§6.19 보안 하드닝** — API CSRF/세션 혼용(6.19.1) · **로그인 전용 rate-limit 버킷**(§6.1 참조 — 문서에만 있고 코드엔 없던 항목) ※ **6.19.2(`/admin/**` + actuator ROLE_ADMIN 게이트)는 2026-09-06 완료, 6.19.3(XFF)은 §6.22와 함께 완료** | **auth 모드 여는 시점에 반드시 선행**(게이트) — no-auth엔 노출면 없음 |
| 2 | **§6.20 사용자별 LLM 토큰 쿼터** | 실사용자가 여럿 생겨 사용량 격리가 필요해질 때 |
| 3 | **§6.16.2 계정 잠금 상태 피드백** | auth 모드 로그인 UX — no-auth엔 로그인 자체가 없음 |
| 4 | **Phase 4** (조건부) — §7.1 OAuth2 소셜 로그인 · §7.2 PostgreSQL 마이그레이션 · §7.3 관리자 페이지 확장 | §3 트리거 참조(가입 마찰·SQLite 한계 신호·다중 사용자 운영 관리 필요 시) |

> 검색 고도화 **Phase 7-D**(sqlite-vec 단일 스캔·cross-encoder 리랭커·시맨틱 응답 캐시)는 재검토에서 범위 제외(사유·재개 신호는 §10.5) — Phase 7의 유일한 미착수였던 항목. 이 중 cross-encoder 리랭커는 제외 사유가 사라져 2026-11-05 §6.33 으로 되살렸다.
>
> 스키마 관리: **운영 테이블은 Flyway 로 일원화**(2026-09-29, V4) — 신규 테이블·컬럼·인덱스는 `V5` 이후의 SQL 마이그레이션 파일로 추가한다. 저장소의 런타임 DDL 은 없어졌다([DATABASE.md](DATABASE.md) §2.1).

---

## 목차

1. [요약 (Executive Summary)](#1-요약-executive-summary)
2. [현재 구조 분석](#2-현재-구조-분석)
3. [핵심 기술 의사결정](#3-핵심-기술-의사결정)
4. [Phase 1 — 보안 기반 구축](#4-phase-1--보안-기반-구축--완료)
5. [Phase 2 — 모바일 UI](#5-phase-2--모바일-ui-개선--완료)
6. [Phase 3 — 운영 견고화](#6-phase-3--운영-견고화--일부-완료)
7. [Phase 4 — 확장 (조건부)](#7-phase-4--확장-조건부--미착수)
8. [Phase 5 — Vector Store 선택적 연동](#8-phase-5--vector-store-선택적-연동--완료--확장-계획)
9. [Phase 6 — 폐쇄망 / 노-도커 실행 지원](#9-phase-6--폐쇄망air-gapped--노-도커-실행-지원--g1g5-완료-sqlite-vec-라이브-부팅은-운영-인수)
10. [Phase 7 — 검색 품질·성능 고도화](#10-phase-7--검색-품질성능-고도화--완료)
11. [리스크 및 이슈](#11-리스크-및-이슈)
12. [의존성 · DB 스키마](#12-의존성--db-스키마--단일-출처는-다른-곳이다)
13. [부록 — 결정 사항 한눈에 보기](#부록--결정-사항-한눈에-보기)

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
| Phase 7 — 검색 품질·성능 고도화 | 가중 RRF·쿼리 임베딩 캐시(7-A) · Contextual Retrieval(7-B) · 한국어 FTS(7-C) · 정확도 마무리·속도·메모리(7-E) · 큐레이션 Q&A·지식 제안·질의 독립화·청크 신고(§10.10~10.14) | 중요 | ✅ 완료 (7-D 만 범위 제외, §10.5) |

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

Caddy 리버스 프록시(자동 TLS, 보안 헤더, `forward-headers-strategy=framework`, 쿠키 `Secure`/`HttpOnly`/`SameSite=Lax`) · Flyway(`V1__baseline.sql` — SQLite 트랜잭션 DDL 제약으로 마이그레이션 1개당 1 DDL) · Spring Security 폼 로그인(`BCrypt(12)`, `users`·`persistent_logins`, 로그인 5회 실패 시 15분 잠금) · 멀티유저 격리(`user_id` 컬럼 + Repository 시그니처 강제 — 이후 문서 저장은 공유 `DocRegistry.SHARED` 로 단순화) · CSRF + HTMX(`base.html` 의 `htmx:configRequest` 글로벌 헤더, `chat-stream.js` fetch 에도) · `/login`·`/signup`(비밀번호 10자+영문/숫자/특수, 가입 직후 자동 로그인). 상세는 코드와 `db/migration`.

---

## 5. Phase 2 — 모바일 UI 개선 ✅ 완료

반응형 레이아웃(`chat.html` 사이드바 `offcanvas-md`, `.chat-shell`(`100dvh`)로 입력창 하단 고정, 모바일 폼 `font-size:16px`) · PWA(`manifest.webmanifest`·`sw.js` NETWORK-FIRST — GET 내비게이션만 가로채 오프라인 fallback, RAG/HTMX/SSE/인증 응답은 캐시하지 않는다) · 다크모드 자동 감지 · 접근성(`aria-label`, 44px 터치 영역, `:focus-visible`). 상세 UI 구조는 [UI.md](UI.md) §9. ⚠️ Bootstrap `.offcanvas-md` 는 ≥md 에서 `background-color:transparent!important` 를 강제하므로 데스크톱 사이드바 배경을 `var(--bg-elevated)` 로 복구해야 한다(`app.css`).

---

## 6. Phase 3 — 운영 견고화 🟡 일부 완료

> **재배열**: 완료 항목을 앞쪽에(기반 운영 항목 → LLM 사용량 클러스터 → 대화/컨텍스트 클러스터), 미착수 항목을 뒤쪽에 우선순위 순으로 재배치했다. 번호가 이 문서 다른 곳(§7·§12·§13)에서도 참조되므로 교차 참조는 전부 새 번호로 갱신됨.

### 6.1 Rate Limiting — Bucket4j ✅ 완료

`RateLimitFilter` 가 엔드포인트별 인메모리 버킷을 적용한다(인증 시 userId, 아니면 IP — `ClientIpResolver`). 값과 환경변수는 OPERATOR_MANUAL "Rate Limiting" 이 단일 출처다(여기에 숫자를 복제하면 드리프트만 생긴다). **업로드 버킷은 쓰기 요청만 센다**(예전엔 경로에 `/documents` 만 들어가면 페이지 로드·목록 갱신·내보내기까지 같은 10/분을 먹어 파일 10개 업로드가 마지막에서 429 였다). 🔵 **미착수 — 로그인 전용 버킷**: "로그인 분당 10회/IP"는 문서에만 있고 `policyFor()` 에 login 분기는 존재한 적이 없다(`/login` 은 `default` 120/분). 계정 잠금은 **계정 단위**라 아이디를 바꿔 가는 시도는 못 막는다 — 노출면이 `auth.enabled=true` 배포뿐이라 **로그인을 실제로 여는 시점에 §6.19 와 함께** 넣는다. 다중 인스턴스는 Redis 백엔드(부록).

### 6.2 파일 업로드 보안 ✅ 완료

확장자 화이트리스트(`pdf, pptx, docx, txt, md`) · **매직바이트 검증**(`FileTypeDetector.matches(path, ext)` — Tika 아님, 임시파일 기록 후 검증, 불일치 422) · 파일명 sanitize · 경로 이탈 방지(`startsWith()`). 누적 용량은 §6.15 의 **전역 저장 상한**으로 재설계됐다.

### 6.3 ~ 6.11 운영 기반·LLM 사용량·대화 컨텍스트 ✅ 완료 (한 줄 요약)

- **6.3 글로벌 예외 처리** — `GlobalExceptionHandler`(RFC 9457 ProblemDetail, `RagException` 은 자체 `httpStatus()`, HTMX 에는 `HX-Reswap: none`).
- **6.4 감사 로그** — SQLite 테이블 대신 Logback `SizeAndTimeBasedRollingPolicy`(`data/audit/audit.log` NDJSON, 일별 + 10MB 분할, gzip, 7일, 100MB 상한, `app.audit.enabled`). 이벤트 목록의 단일 출처는 OPERATOR_MANUAL §3.3.
- **6.5~6.7 사용량 표시** — 임베딩은 `TrackingEmbeddingModel` 이 채팅과 분리 기록(2026-09-30 이름을 모델명 없는 `embed` 하나로, 옛 행은 Flyway V5 가 합침) · 키 없는 프로바이더는 이력이 없으면 숨김(`usedProviders()`) · 설정에서 사라진 프로바이더의 기록은 `type=ORPHAN` 으로 노출하고 `DELETE /admin/llm-usage/{provider:.+}` 로 삭제.
- **6.8 피드백** — 👍/👎(`conversation_turns.feedback`). `DISLIKE` 턴은 `getHistory()` 에서 하드 제외, `LIKE` 는 §10.10~10.11 에서 지식 제안 폼을 여는 신호로만 쓰인다(Direct 턴의 재사용 자격으로 읽던 규칙은 근거 청크가 없어 2026-09-21 제거).
- **6.9 요약 선계산** — 입력 시작 시 `ConversationSummarizerService` 가 이전 대화를 미리 요약(스레드별 LRU 3개, TTL 15초; 없으면 `getHistory()` 로 폴백). RAG 답변은 이미 `## 요약` 이 있어 그것을 쓰고 LLM 재요약을 생략하며, 요약 섹션이 없는 답변(Direct/meta)만 소형 모델(`MICRO_TEXT`)이 등록됐을 때 1회 요약한다(부가 기능이 답변용 로컬 모델의 슬롯을 잠식하지 않게). 소형 미설정이면 그 답변만 `UNSUMMARIZED_ANSWER_CAP`(300자)로 자른다 — 앞에서부터 자르는 `truncate()` 라 앞쪽의 긴 Direct 답변 하나가 최신 턴을 밀어내기 때문. **같은 이유로 과거 질문도 모든 경로에서 500자로 자른다**(`buildSummaryInput`, `HistoryPolicy.PAST_QUESTION_CAP` — 질문 한도가 10,000자라 긴 질문 하나가 요약 예산을 다 먹었다, 2026-11-12; 질문 전문은 `[Recent]` 에서 본다).
- **6.10 백그라운드 사용량** — 채팅 외 호출(요약·키워드·서식교정·TXT→MD·제목)을 `BackgroundUsage` 접두사로 분리 기록, `/llm-usage` 에 `type=BACKGROUND` 카드.
- **6.11 컨텍스트 예산** — `MemoryService.maxConversationChars()`(`max(1000, LLM_MAX_TOKENS × 0.5)`)를 단일 진실 원천으로 통일하고 하드코딩 5개를 `app.memory.*`/`app.summary.*` 로 외부화.

---

### 6.12 다중 사용자 동시 LLM 요청 처리 — 동시성 제어 + 처리량 확장 ✅ 완료

채팅 경로에 동시성 제한이 없어(세마포어는 인덱싱뿐) 여러 사용자가 겹치면 폴백 없는 유일 프로바이더가 서킷브레이커로 전면 차단되던 문제를 해결: ① 프로바이더별 동시성 세마포어(`LlmRouter.executeGated`, `app.llm.providers[].concurrency`) ② 대기상한 초과 시 429 백프레셔(`LlmBackpressureException`, 브레이커 없음) ③ `CachingEmbeddingModel` single-flight ④ 폴백 없는 유일 프로바이더의 단축 차단(30초) ⑤ 동일 role·priority 프로바이더 간 least-in-flight 로드밸런싱. **⑥ 장애 전환(2026-10-08)** — 연결 타임아웃은 차단 + 같은 호출 안에서 다음 서버로, 채팅 답변 스트리밍은 첫 토큰 전 서버 장애에 한해 `StreamFailover`/`LlmRouter.failOver()` 로 다시 열고, 질의 확장은 `RoutedChatModel` 로 호출마다 라우팅(읽기 타임아웃은 그대로). **⑦ 이중화 마무리(2026-10-12)** — 임베딩은 `LoadBalancingEmbeddingModel` 이 자기 목록(`EMBED_BASE_URLS` 의 `;` 목록) 안에서 **닿지 않는 엔드포인트만** 30초 건너뛰고(5xx·읽기 타임아웃은 요청 모양 문제라 넘기지 않는다), 기동 검증(G3)은 같은 type·priority 복제본이 통과하면 닿지 않는/5xx 서버를 30초 차단된 채 등록하고 기동한다(설정 오류는 여전히 실패). 남은 한계는 멈춘 서버(읽기 타임아웃) 하나이며 근거는 PITFALLS `llm/StreamFailover.java`.

---

### 6.13 설정 페이지 — LLM/RAG 옵션 조회·부분 수정 ✅ 완료

`GET /settings`(게스트 조회 가능, 편집은 `isAdmin` 게이트)에서 LLM/임베딩/RAG 설정을 조회하고, `settings_override` 테이블 기반 오버레이 레이어로 **재기동 없이** 반영한다 — 검색 값은 다음 검색부터, 인덱싱/청킹 값은 다음 인덱싱/↺ 재인덱싱부터. 핵심 규칙은 **소비처가 값을 필드에 캐시하지 않고 매 호출 `props.xxxSafe()`로 재조회**하는 것(`MarkdownCorrectionService`의 생성자 캐시를 제거해 세 소비처의 동작을 일치시켰다). 빈 생성 시점에 고정되는 값(rerank-enabled 등)과 기본 라우팅 모드는 조회 전용. 수정은 `/admin/settings/update|reset`(§6.17 ROLE_ADMIN 상속) + 감사 로그. **핫 키 추가 절차와 전체 키 목록은 CLAUDE.md §6.13 항목이 단일 출처.**

---

### 6.14 LLM 사용량 — 핵심 채팅 경로 추적 확장 ✅ 완료

`AnswerService`·`DirectAnswerService`·`ClassifierService`·`RerankerService`·`VisionDescriptionService`·`RetrievalService` 의 `MultiQueryExpander` 등이 `LlmRouter` 를 거치지 않아 실제 채팅 사용량이 `/llm-usage` 에 안 잡히던 문제를 수정 — 블로킹은 `executeWithTracking()`, 스트리밍은 `LlmRouter.recordApproxUsage()`, `MultiQueryExpander` 는 한때 `TrackingChatModel` 데코레이터였다가 2026-10-08 부터 호출마다 라우터를 지나는 `RoutedChatModel` 로 대체됐다(§6.12 ⑥).

---

### 6.15 스토리지 쿼터 — 전역 저장 상한 ✅ 완료 2026-09-01

**유일한 설계 결정은 축을 사용자별에서 전역으로 바꾼 것이다** — 저장소가 공유 구조(`DocRegistry.SHARED`)이고 기본 배포가 no-auth 단일 운영자라 "이 사용자가 N바이트를 썼다"를 붙일 데가 없다(사용자별은 멀티테넌트 과금이 필요해질 때). `app.upload.max-total-size`(`0` = 무제한이 기본)를 문서 업로드 2곳과 `CuratedImageStore.store()` 가 쓰기 **전에** `StorageQuotaService.checkCanAccept()` 로 확인하고, 넘으면 413 + `RAG-UP-002`(`Retry-After` 없음 — 기다려도 자리가 생기지 않는다). 측정은 `documents/`·`converted/`·`images/` 의 디스크 walk(소프트 캡)이고, 삭제한 문서의 원본이 옮겨지는 `documents/backup/` 은 측정에서 빼는 대신 `DocumentBackupCleaner` 가 묶는다(최신본 1개 → 30일 → 2GB). 함정은 PITFALLS [`StorageQuotaService`](PITFALLS.md#servicestoragequotaservicejava)·[`DocumentBackupCleaner`](PITFALLS.md#servicedocumentbackupcleanerjava). **열린 항목**: 업로드 검사 경로에는 캐시가 없다(느려지면 다시 본다) · 백업 정리는 삭제 직후와 기동 시에만 돈다 · 상한 근접 사전 경고가 없다(거부가 첫 신호).

---

### 6.16 사용자 경험(UX) 개선 🟡 일부 완료 (6.16.1)

**6.16.1 스트리밍 답변·인덱싱 중단 컨트롤 ✅ 완료** — 채팅은 `chat-stream.js` 의 `AbortController`(전송 → 중지 버튼, 중단 시점까지 답변 보존), 인덱싱은 `IndexingCancelledException` + `POST /ui/documents/progress/{taskId}/cancel`(워커 `interrupt()`; non-interruptible `join()` 을 `.get()` 으로 바꿔 실제로 취소되게 했다 — 완료된 파일은 레지스트리에 보존).

**6.16.2 계정 잠금 상태 피드백 부재**
- **현재 상태**: 로그인 5회 실패 시 15분 잠금(`AuthEventListener`)되지만, 잠긴 뒤에도 로그인 화면은 일반 "이메일 또는 비밀번호가 올바르지 않습니다" 문구만 보여준다(`formLogin.failureUrl("/login?error")` 고정). 사용자는 자신이 잠겼는지, 언제 풀리는지 알 수 없다(문구가 "5회 실패 시 15분 잠금"을 안내는 하지만 현재 잠금 상태/해제 시각은 아님).
- **개선안**: `LockedException` 분기를 별도 실패 핸들러로 잡아 "N분 후 해제" 메시지 전달(`locked_until` 조회). 잠금 남은 시간은 이미 `SqliteUserDetailsService`가 관리 중.
- **완료 기준**: 잠긴 계정 로그인 시 잠금 상태 + 해제 예정 시각이 구분되어 표시된다.

---

### 6.17 문서 관리·Admin 접근 인증 필수화 + 역할 기반 화면 분기 ✅ 완료 (B안)

no-auth 기본 배포에서 `/documents` 쓰기와 `/admin/**`이 로그인 없이 열려 있던 문제를, 채팅·조회는 no-auth로 두고 `/documents`(쓰기)·`/admin/**`만 로그인 요구하는 (B) 관리 전용 인증으로 해결 — `app.auth.management-only=true` 신규 서브모드, `SecurityConfig` 3번째 필터 체인(`IF_REQUIRED`+쿠키 CSRF), `hasRole("ADMIN")` 게이트, `GlobalModelAdvice.isAdmin`으로 화면 분기. (A) 전체 인증 모드는 §6.19와 함께 후속(멀티유저 활성화 시).

---

### 6.18 Direct 메시지 전용 LLM Temperature 분리 ✅ 완료

라우터 경로가 Spring AI 오토컨피규레이션을 우회해 `LLM_TEMPERATURE` 등이 **전부 죽은 설정**이던 것을 `app.llm.temperature`(일반/RAG)·`app.llm.direct-temperature`(Direct 전용)·`app.llm.max-tokens` 로 바로잡았다(하드코딩 4곳 제거). 이후 세 temperature 모두 핫(§6.13), creative-temperature 가 넷째(§6.24), `max-tokens` 가 마지막 핫 편집 대상(§6.26 A6)이 됐다 — 현행 clamp·소비처는 CLAUDE.md §6.13 항목.

---

### 6.19 보안 하드닝 🟡 §6.19.2·6.19.3·6.19.4 완료 · §6.19.1만 남음

> **범위 판단의 교훈**: 처음엔 세 건 모두 `app.auth.enabled=true` 전용으로 보고 조건부로 뒀지만 그 전제가 절반만 맞았다 — §6.19.3(XFF)은 §6.22 에서 IP 가 식별자가 되면서 no-auth 배포의 선행 필수가 됐고, 2026-09-07 실사에서는 **no-auth·management-only 조합에서만 성립하는** 노출면이 여럿 나왔다(§6.19.4). **"어느 모드에서 성립하는가"를 항목마다 다시 묻는 편이 낫다.**

**6.19.1 `/api/v1/**` CSRF 비활성 + 세션 인증 혼용** 🟡 노출면 축소, 원 항목 미해결 — `SecurityConfig` 가 인증 모드에서 API 전체를 CSRF 예외로 두는데 같은 엔드포인트가 **세션 쿠키 인증**이라, `multipart/form-data` POST(CSRF "simple request")인 `POST /api/v1/documents` 는 크로스사이트 폼으로 인증 사용자의 브라우저에서 업로드를 유발할 수 있다. 2026-09-07 에 ① `/api/**` CORS 와일드카드 제거 ② 문서 쓰기 3종 `ROLE_ADMIN` 게이트로 좁혔지만 관리자의 브라우저는 여전히 CSRF 대상이다. 개선안은 (A) API 를 세션이 아닌 별도 인증(토큰/헤더) 전용으로 못박거나 (B) `/api/v1/**` 도 CSRF 토큰을 요구 — 자동화가 이미 로그인 세션을 쓰므로 (B)가 자연스럽다. **완료 기준**: 인증 모드에서 크로스사이트 폼 제출로 인증 사용자의 업로드/삭제가 트리거되지 않음(테스트로 재현 → 차단), no-auth 회귀 0.

**6.19.2 `/admin/**` 인가 공백** ✅ 2026-09-06 — 인증 모드에서 ROLE_ADMIN 을 요구하던 경로가 `DELETE /admin/llm-usage/**` 하나뿐이라 일반 사용자가 청크 편집·전 사용자 대화 열람/삭제·런타임 설정·지식 제안 승인(§10.11 검색 코퍼스의 유일한 문)까지 할 수 있었다. `/admin/**` 와 `/actuator/**`(health 제외 — `POST /actuator/loggers` 로 TRACE 를 켜면 `LlmCurlLogger` 가 검색 문서가 실린 프롬프트 전문을 남긴다)를 두 모드 모두 `hasRole("ADMIN")` 으로 묶었다(`FullAuthAuthorizationTest`·`ManagementOnlyAuthorizationTest`).

**6.19.3 `X-Forwarded-For` 무검증 신뢰** ✅ (§6.22 와 함께) — 판정을 `ClientIpResolver` 하나로 모으고 **최상위** `app.trust-forwarded-for`(기본 `false`) 옵트인일 때만 신뢰한다. §6.22 에서 IP 가 **식별자**가 되는 순간 XFF 위조가 남의 대화 목록 열람이 되어 no-auth 의 선행 필수가 됐다. 2026-09-07 에 셋째 소비자 `AuditLogger` 가 플래그를 보지 않고 헤더를 믿던 것을 고쳤다 — **판정을 한 곳에 모으는 것만으로는 부족하고 새 소비자가 그것을 실제로 부르는지까지 봐야 한다.** ⚠️ 프록시 뒤 배포는 `TRUST_FORWARDED_FOR=true` 필수(끄면 전 방문자가 프록시 주소 하나로 합쳐진다).

**6.19.4 폐쇄망 배포 실사 하드닝** ✅ 2026-09-07 — 실제 운영 설정(`AUTH_ENABLED=false`·`AUTH_MANAGEMENT_ONLY=true`·`hybrid`·`TRUST_FORWARDED_FOR=false`) 기준으로 **"지금 걸리는가"와 "설정을 바꾸면 걸리는가"를 나눠 둘 다 고쳤다**: CORS 와일드카드 제거 · management-only 의 REST 문서 쓰기 게이트 · full-auth 의 문서 관리 RBAC(`gateDocumentManagement()` 하나를 두 모드가 공유) + `/setup` 모드 무관 개방 · 공유 게스트의 레이트리밋 키를 IP 로 · 게스트 개방 이미지 업로드를 `upload` 버킷으로 + 고아 정리 6시간 주기 · 감사 로그 IP·`X-Trace-Id` 모양 검사·REST 호출의 세션 생성 중단 · 컨테이너 비-root(UID 10001 — 기존 Linux 배포는 `chown -R 10001:10001 ./data` 1회).

**유지해야 할 결정**: 문서 관리 게이트 목록은 두 모드가 공유하는 하나다 · REST 쓰기 3종은 `NoAuthAutoLoginFilter.isGatedManagementPath()` 에 일부러 넣지 않았다(302 가 아니라 API 호출자에게 맞는 403) · `UPLOAD_MAX_TOTAL_SIZE` 기본 무제한은 그대로다(바꾸면 기존 배포가 조용히 업로드를 거부한다). 동작 명세는 CLAUDE.md `SecurityConfig`·`RateLimitFilter`.

---

### 6.22 접속자별 채팅 개인화 (no-auth) ✅ 완료

no-auth 배포에서 모든 방문자가 게스트 id 하나를 공유해 스레드 목록·이력이 섞이던 문제를 `NoAuthAutoLoginFilter` 가 주입하는 게스트 principal 의 id **한 곳만** 방문자별로 파생해 해결했다(저장 계층은 `user_id` 축으로 이미 격리돼 있어 변경 0). `app.auth.guest-identity` = `shared`(기본)/`ip`/`cookie`/`hybrid`(권장), id 는 `guest-<12 hex>` = HMAC-SHA256(`app_secret` 의 영속 시크릿, 방문자 키). 메커니즘은 CLAUDE.md `GuestIdentityResolver`. **유지할 사실**: 인증을 켜면 `GuestIdentityResolver` 가 컨텍스트에서 사라진다 · 문서 저장은 공유 유지 · 켜기 전에 쌓인 스레드는 옛 공용 id 에 묶여 조회되지 않는다(삭제는 아니며 `shared` 로 되돌리면 복귀 — 채택 시 운영자 고지, OPERATOR_MANUAL §9.4.3).

---

### 6.23 청크 변경 시 답변 재사용 무효화 + 대화 기록 표시 ✅ 완료

> **결정 근거와 틀렸던 지점** → [PITFALLS.md](PITFALLS.md#623-청크-변경-시-답변-재사용-무효화--대화-기록-표시--완료)

재사용(`/api/v1/questions/reuse`)이 근거 청크가 삭제·수정된 답변을 사실처럼 되돌려주던 문제를 **스냅샷 대조**로 해결했다 — 턴 저장 시 `(chunk_id, sha256(chunk_fts.content), answer_share)` 를 `turn_source_ref` 에 남기고 재사용 직전 대조하며, 삭제/재인덱싱 경로는 즉시 무효화 표시를 따로 남겨 이중으로 막는다. 대화 기록의 **삭제됨/수정됨** 배지는 `SourceRef.staleBadge()` 한 곳이 정한다. 상세는 CLAUDE.md `QuestionReuseService`/`QuestionReuseRepository`/`SourceRef`.

---

### 6.24 응답 모드 재설계 — S/N/C ✅ 완료 2026-08-24

> **결정 근거와 틀렸던 지점** → [PITFALLS.md](PITFALLS.md#624-응답-모드-재설계--snc-l-제거--모드별-전용-시스템-프롬프트--완료-2026-08-24--설계-확정-2026-08-22-분량-지침settings-배선-보강-2026-08-23-완료-후-보정-2026-08-25)

길이 축(S/M/L)에 "문서를 **재료로** 코드·설정을 생성"이라는 **다른 축**(근거 엄격도)을 얹으려다 **M 과 L 이 구분되지 않음**을 확인하고 재설계했다. 이유는 튜닝이 아니라 구조다 — 채팅의 유일한 전송 경로인 스트리밍에는 `maxTokens` 가 붙지 않고, `RetrievalService` 가 모드를 몰라 두 모드의 재료(topK)가 같으며, "약 N자" 문구는 모델을 움직이지 못했다(실측 M 3,047자 / L 3,187자). L 제거·M→N 개명 후 모드마다 **시스템 프롬프트를 통째로** 주고 응용 모드 **C** 를 신설했다. Phase 0~4 완료, `4-c`(검색 부스트 상향)만 미착수. 동작 명세는 CLAUDE.md Response mode 항목과 PIPELINE §3.1.

---

### 6.25 관리자 — 전체 대화 목록 조회·삭제 + 검색 진단 연결 ✅ 완료 2026-08-28

관리자가 `/admin` 에서 **전 사용자의 대화 목록**(제목·최종 활동·턴 수·재사용 수·피드백)을 보고, 삭제하고, 그 대화의 검색 진단 수치로 내려간다(§7.3 의 "대화 관리" 선반영 — 계정 관리·감사 로그 뷰는 §7.3 에 남는다). **새 스키마 0개**: `ThreadAdminRepository`/`ThreadAdminService` · `fragments/admin-threads.html` · `fragments/admin-source-table.html`(진단 패널과 공유) · `AdminController` 의 `/admin/threads*`. **Step 1**(선행 버그 픽스): 대화를 통째로 지우면 좋아요로 승격된 `curated_qa` 행과 벡터가 남아 검색에 계속 기여했다 — `CuratedQaService.onThreadDeleted()` 를 사용자·관리자 경로가 함께 부른다.

**소스 주석이 가리키는 결정**: **결정 3** — 답변 원문은 목록과 다른 쿼리(`findTurnContent()`)로만 나가고 내용이 실제로 나갈 때 `admin.thread.read` 감사를 남긴다(목록의 `TurnRow` 에는 `answer` 필드가 아예 없다 — 감추는 것을 템플릿이 아니라 구조로 강제한다, **Step 6**). **결정 4** — 대화 삭제는 큐레이션을 먼저 회수한다(수동 제안 행은 `source_turn_id IS NOT NULL` 로 배제). 그 밖의 결정·함정은 PITFALLS [`ThreadAdminRepository`](PITFALLS.md#repositorythreadadminrepositoryjava--servicethreadadminservicejava)·[대화 삭제](PITFALLS.md#대화). 재현 환경 함정: `spring-boot:run` 은 템플릿 사본을 캐시하므로 프래그먼트 수정은 `mvn compile` + 재시작이 필요하고, `-DDATA_DIR` 에 git-bash 경로(`/c/Users/...`)를 넘기면 엉뚱한 곳에 빈 DB 가 생긴다.

**남은 열린 항목**: `curated_qa.source_thread_id` 인덱스(대화 목록에 큐레이션 수 열을 넣고 싶어질 때) · 전사 트랜스크립트 검색/뷰어(필요해지면 §7.3). (저장 시각의 존 통일은 2026-10-14, 고아 `turn_source_ref` 는 10-12 에 해소.)

---

### 6.27 검증 실패 재시도 개선 ✅ 완료 2026-09-02

**문제**: 재시도가 같은 입력의 반복이었다 — 두 게이트(`sufficient=false`/`grounded=false`)가 같은 노드로 갔고, 돌아가서 달라지는 것은 문서 1개뿐이었으며 온도는 낮게 clamp 돼 같은 입력이면 거의 같은 답이 나왔다. 반려 사유(`evalReason`)는 계산돼 로그·SSE·DB 로 나가면서 정작 모델에게만 가지 않았다. **고친 것**(근거는 PITFALLS [`AgentGraph`](PITFALLS.md#agentagentgraphjava)·[`RetrievalEviction`](PITFALLS.md#serviceretrievalevictionjava)·[`RetrievalService`](PITFALLS.md#serviceretrievalservicejava)): 재시도 프롬프트의 `[직전 시도 메모]`(추가 왕복 0, **지시가 아니라 관찰로** 써야 지적을 채우려고 지어내지 않는다) · **게이트별 분기**(`sufficient=false` → RETRIEVAL, `grounded=false` → ANSWER) · escalation 입력을 `retrievalRetries` 로 · **덧붙이는 대신 밀어낸다**(`RetrievalEviction` — 평가가 근거로 안 썼고 RRF 하위 절반일 때만, 빈 `usedDocIndices` 는 "모른다", 1순위 보존) · 후보 풀 ×2 → ×1.5 · 문서 +1 은 **검증 호출**의 컨텍스트 여유가 있을 때만. **조기 종료**(2026-10-12): 같은 게이트로 **같은 청크 집합**을 보고 두 번 반려되면 상한이 남아도 멈춘다(`AgentState.repeatsLastRejection` — `evalReason` 은 매번 다른 자유 문장이라 비교하지 않는다; PROGRESSIVE 는 곧바로 업그레이드). `EVAL_OVERHEAD_TOKENS`(1,500)는 측정이 아니라 허용치다.

---

### 6.28 로컬 LLM 장애 가시성 — 생사 핑 · 소진 문구 · traceId 전파 ✅ 완료 2026-09-22

> **결정 근거와 틀렸던 지점** → [PITFALLS.md](PITFALLS.md#llmllmpingjava) · [연속 실패와 소진 문구](PITFALLS.md#llmcircuitbreakerjava--연속-실패와-소진-문구) · [MdcPropagation](PITFALLS.md#webmdcpropagationjava)

**문제.** 2026-09-21 로컬 LLM 의 GPU 가 소실됐는데(`ErrorDeviceLost`) 서버 프로세스와 `/v1/models` 는 멀쩡하고 모델은 "로드됨"이라 앱은 알 방법이 없었다 — 사용자는 5초 차단마다 거짓 "4초 후 다시 시도"를(그것도 사전 분류 future 의 `CompletionException` 이 전용 catch 를 지나쳐 **클래스 이름이 붙은 raw 문구**로) 받았고 로그는 모든 줄이 `[-]` 라 한 사고를 trace id 로 묶을 수 없었다. **구현**: `LlmPing`(`GET /api/v1/llm/ping` 200/503 — 닿는가 / 로드됐는가 / `deep=true` 1토큰 추론이 되는가) · `blockedSeconds` 와 헤더의 `LLM: 차단 중 Ns` · `CircuitBreaker` 연속 실패 횟수 → 3회부터 "서버(모델) 상태를 확인하라"(`LlmOutageMessages`) · `AsyncExceptions.unwrap` · `MdcPropagation` + `TraceIdFilter` 의 ASYNC 재디스패치.

**지켜야 할 결정**: "살아 있는가"는 세 답이다(엔진 사망은 1토큰 추론만 가른다 · 핑은 진단이라 브레이커를 만지지 않는다) · 소진 문구는 예외 메시지가 아니라 예외가 나르는 사실(`repeated()`·`retryAfterSeconds()`)로 고른다(연속 실패 횟수는 성공이 있어야만 0) · future 로 LLM 을 부르는 자리는 `CompletionException` 을 풀어야 한다 · **새로 실행기나 `Thread.ofVirtual()` 을 여는 자리는 `MdcPropagation` 으로 감싼다**(`SseHeartbeat` 만 예외). **열린 항목**: 핑은 요청이 있어야 돈다(주기적 자가 점검은 넣지 않았다) · 연속 실패 문턱 3 은 상수다.

---

### 6.26 컨텍스트 초과 대응 — 창 인지 + 입력 예산 ✅ 완료 2026-09-01

> **결정 근거와 틀렸던 지점** → [PITFALLS.md](PITFALLS.md#626-컨텍스트-초과-대응--창-인지--입력-예산--완료-2026-09-01)

실배포 로그에서 시작했다: `Context size has been exceeded.` → `CircuitBreaker` 가 유일한 LOCAL 프로바이더를 30초 차단 → 그동안 들어온 **작아서 통과했을 요청까지** `All providers exhausted` 로 함께 실패. 원인은 프롬프트 크기인데 증상은 프로바이더 장애였다. 11단계로 풀었다 — 1 **차단하지 않기**(`isContextOverflow` — 결정적 오류라 기다려도 같다; `isTimeoutLike`·`isVisionUnsupported` 에 이은 세 번째 "프로바이더가 아픈 게 아니다") · 2 오류 구분(`LlmContextOverflowException`, `RAG-LLM-003`) · 3 프로바이더별 `max-tokens` · 4 컨텍스트 창 학습(`context-size` 선언 또는 `ContextWindowProbe`) · 5 토큰 추정 통일(`TokenEstimator`) · 6 입력 예산 사전 축소(`PromptBudget` + `AnswerService.fitToBudget()`) · 7 축소 사실 노출(`budgetNote` + 출처별 `미사용`) · 8 인덱싱 입력 예산(`PromptBudget.rewriteInputChars()` — 정작 사고가 났던 경로는 답변이 아니라 **MD 교정**이었다).

- **9 축소 후 재시도(A4 · 소스의 `§6.26-9`, `withShrinkRetry()`)** — 1~8 이 모두 **추정** 위에 서 있다는 사실을 받아내는 마지막 층. 채팅 답변 스트리밍에서는 2026-10-02 에야 실제로 발동하기 시작했다(§6.29 3단계, `LlmErrorText`).
- **10 창 재탐지(A5, `reprobeContextWindows()`)** — 4 가 기동 시 1회라 값이 낡는다. 고치는 시점을 **사람이 정하게** 한 이유는 예산이 스스로 움직이면 재현이 안 되기 때문이다. 2026-09-28 에 **컨텍스트 초과 시 자동 재탐지**(`ContextWindowRefresher` — 초과는 "기록된 창이 틀렸다"는 서버의 증언), 2026-10-19 부터 채팅 스트리밍의 초과도 재탐지를 건다. 커진 창은 초과가 나지 않으므로 여전히 버튼의 몫이다.
- **11 `max-tokens` 핫 편집(A6, `MaxTokensCappingChatModel` 이 `IntSupplier` 로)** — 페이지에 마지막 남은 조회 전용 값이자 이 §의 거의 모든 계산이 파생되는 값이었다.

**자리가 `AnswerService` 인 이유**: 라우터는 호출을 불투명한 클로저로 받아 프롬프트 안을 못 보므로 무엇을 버릴지 아는 곳은 프롬프트를 조립하는 여기뿐이다. 라우터의 순회("다른 곳에 물어본다")와 층이 달라 방해하지 않는다("더 작게 물어본다"). 답변(블로킹·스트리밍·PROGRESSIVE)과 **검증** 양쪽에 걸린다.

---

### 6.20 사용자별 LLM 사용량 쿼터 🔵 미착수 — 멀티유저 활성화 시 후속

`llm_usage.user_id` 컬럼은 있지만(Flyway V1) 모든 쓰기·조회가 이 컬럼을 보지 않아 **죽은 컬럼**이고, `AgentService.chat()`/`StreamingAgentService.run()` 진입에 쿼터 게이트가 없다. **권장 설계**: (A) 채팅 토큰은 `conversation_turns` 의 `input_tokens`/`output_tokens`/`user_id` 로 사용자별 일일 합을 구한다(별도 테이블 없이 — `llm_usage` 에 user 축을 더하는 (B)는 SQLite 테이블 재생성이 필요해 과하다) → `QuotaService.checkDailyTokenQuota(userId)` 가 `app.quota.daily-token-limit`(0 = 무제한, `app.quota.enabled` 기본 false) 초과 시 `QuotaExceededException` → `GlobalExceptionHandler` 가 429(`RAG-QUOTA-001`, `Retry-After` 자정까지)로 매핑하고 두 진입점의 `PromptInjectionGuard.validate()` 다음에서 막는다. `AppProperties` 에 `QuotaConfig` + `quotaSafe()` 가드. **완료 기준**: 한도 초과 시 스트리밍/블로킹 모두 429 · 꺼짐(기본)이면 회귀 0. §6.22 로 no-auth 에서도 `conversation_turns` 기반 집계가 의미를 갖게 됐다.

---

### 6.21 소형(경량) LLM 분리 — 태스크별 모델 라우팅 + 멀티 LLM 처리량 확장 ✅ 완료

추론이 필요 없는 고빈도 백그라운드 호출(키워드+맥락 추출·대화 요약·제목 생성·MultiQuery 확장)을 신규 `TaskType.MICRO_TEXT`(`LIGHT_TEXT`/`BOTH`에 폴백)로 분리해 500MB급 소형 로컬 모델로 오프로딩 — 분류·직답은 품질 유지를 위해 큰 모델에 남김. 임베딩은 `LoadBalancingEmbeddingModel`(다중 엔드포인트 least-in-flight)+서브배치 병렬화로 처리량 확장, 대화 응답 쪽은 §6.12 재사용. **전부 opt-in(미등록 시 기존 동작 그대로) → 회귀 0**. 설정·런북: [LLM_ROUTING.md §9](LLM_ROUTING.md) · [OPERATOR_MANUAL §3.2·§5.4](OPERATOR_MANUAL.md).

**주의**: 임베딩 다중 엔드포인트는 **동일 모델·차원이어야 한다**(섞으면 인덱스 손상). 소형+대형 co-located 시 VRAM/RAM 합산 확인.

**실측 게이트 — 부분 검증으로 종료**: 라이브 듀얼티어로 §10.7.5 하네스 실행 시 색인된 골든셋 6건 전부 recall@10=1.0 — 소형 모델 라우팅이 검색 품질을 회귀시키지 않음을 확인. 전체 baseline(0.962) 정식 대조는 코퍼스에 arch/sample 문서가 미색인이라 보류(§6.21과 무관한 코퍼스 갭) — 재색인 후 `mvn test -Dtest=SearchQualityEvaluationTest -Dsearch-eval.enabled=true`로 재현 가능.

### 6.29 호출 지점별 생각(추론) 수준 제어 ✅ 완료 2026-10-07 (1~6단계) — 설계 2026-10-02

**목표**: LLM 호출 지점(사이트) 21곳(처음 22곳 — `IMAGE_TYPE` 은 2026-11-04 에 제거)마다 모델의 생각을 **끔/낮게/중간/높게** 중 하나로 정하고 `/settings`(관리자 전용)에서 핫 편집한다 — 채팅 답변·응답 검증·분류/독립화/확장/리랭크·답변 뒤 보강·인덱싱·관리자 도구 전부.

**결과** — 6단계 실측(llama.cpp b11433 + gemma-4-E2B, 같은 입력을 끔/낮게로 번갈아 부름): 생각이 이득을 준 곳은 **응답 검증**(근거 없는 답을 잡는 비율 6% → 44%)과 **응용(C) 답변의 코드**뿐이었고, 나머지는 같은 품질에 짧은 호출이 10~40배, 채팅 답변은 첫 글자가 11~34초 느렸다. 측정은 그 셋(`EVAL`·`EVAL_CREATIVE`·`ANSWER_RAG_C`)만 낮게를 권했지만 **출하값은 속도를 택해 전부 끔**이다(운영 결정 2026-10-07 — `application.properties`·`ThinkingSite.shippedDefault`, `ThinkingSiteTest` 가 고정). 수치·하네스·재현은 [THINKING_EVALUATION.md](THINKING_EVALUATION.md).

**먼저 정해 둔 사실**: 주력 llama.cpp + gemma-4·Qwen3 는 `chat_template_kwargs.enable_thinking` 으로 **켬/끔만** 받는다(`reasoning_effort` 는 무시 — 낮게·중간·높게가 켬으로 접힌다). 단계가 의미를 갖는 것은 gpt-oss 와 원격 추론 모델뿐이고, LM Studio 는 문서화된 스위치가 없다. 받을 수 없는 단계는 접어서 보내고 화면에 접혔다고 보인다 — 받은 것처럼 보이는 손잡이는 만들지 않는다(§10.10 큐레이션 거짓 손잡이 선례).

*동작 명세는 CLAUDE.md(`ThinkingSite`·`ThinkingControlChatModel`·`ThinkingBudget`·`AnswerStreamer`·`ThinkingPreviewService`), 깨지기 쉬운 곳은 PITFALLS("생각 수준 실측과 출하 기본값" 외), 운영은 OPERATOR_MANUAL. 설계 원문(목업·배지 조건표·사이트별 표)은 커밋 `02f308b`·`c9a9ec4`.*

**결정 (요약 — 번호와 (a)~(i) 라벨은 소스 주석이 가리키는 자리라 바꾸지 않는다)**

- **① 세 축 분리** — 호출부는 사이트만 표시, 수준은 설정(`ThinkingLevel` 넷뿐 — "서버 기본값" 수준은 앱이 모르는 값이라 없다), 수준 → 필드 변환은 프로바이더가 정해진 뒤 데코레이터(`ThinkingDialect`, 재시작 필요).
- **② 출하값** — 21곳 전부 끔(측정은 `EVAL`·`EVAL_CREATIVE`·`ANSWER_RAG_C` 만 낮게를 권했으나 속도를 택했다).
- **③ 변환** — `AUTO`·`NONE`·`TEMPLATE_KWARGS`(켬/끔만, 세 단계가 접힘)·`TEMPLATE_KWARGS_EFFORT`(gpt-oss)·`OPENAI_EFFORT`. 접힘 여부는 `dialect.effective(level)` 하나가 화면과 실제 전송을 함께 계산한다.
- **④ 출력 예약(가장 위험한 부분)** — 생각 토큰도 `max_tokens` 를 먹는다. `예약 = base + clamp(C − base, 0, h)`, `C = min(프로바이더 max-tokens, 창 × 25%)`, `h` = 1,024·2,048·4,096(켬일 때만) — **더하기만 하고 기본 예약은 깎지 않는다**. `ThinkingBudget.compute()` 하나를 요청 · 입력 예산 · 미리보기가 함께 부른다.
- **⑤ 스트리밍** — 체인을 우회하는 채팅 답변은 `AnswerStreamer` 하나가 같은 일을 한다(생각 델타는 활동으로만 · 첫 청크 전의 거부만 재시도 · curl 로그). 다른 곳에서 `provider.rawModel()` 을 부르면 빌드가 막는다.
- **⑥ 의도 전달** — `ThinkingControl.mark(builder, site)` 가 내부 키를 싣고 체인 맨 바깥이 **항상** 걷어낸다. 서버가 필드를 거부하면 (프로바이더, 필드)로 기억하고 빼서 한 번 다시 보낸다(차단 없음).
- **⑦ `/settings` 카드** — **관리자 전용**(읽기까지) · 수준별 예산 미리보기. 숫자는 런타임 함수를 그대로 부른다(`ThinkingPreviewParityTest`).
- **⑧ 관측** — `ThinkingObservations` 가 (사이트, 프로바이더, 수준)별 최근 50회를 숫자로만 메모리에 둔다. **관측만 하고 예산을 바꾸지 않는다**(같은 요청이 표본 수에 따라 다른 예산을 받으면 재현이 안 된다).

**단계 — ✅ 전부 완료**: 1 골격·출하값·관측 · 2 블로킹 호출부 배선 + 라우팅의 단일 출처(`ThinkingSiteConventionTest`) · 3 스트리밍 `AnswerStreamer`(**채팅 스트리밍의 축소 재시도가 실서버에서 한 번도 발동하지 않았던 것**을 `LlmErrorText` 로 고침) · 4 `ThinkingBudget`(Direct 블로킹 경로의 이력 안전망이 스트리밍 예약을 빼던 어긋남도 고침) · 5 `/settings` 미리보기 · 6 실측 → 출하값.

**열린 항목**: (a) 요청 단위 생각 토큰 예산은 **구현하지 않기로 결정**(10-12 측정: 예산 256·512·1,024 에서 걸러야 할 답 19건 중 0·2·5건 — 줄인 만큼 검증의 탐지가 사라진다, [§3.7](THINKING_EVALUATION.md). 다시 열 신호는 수준을 올린 배포에서 `finish=length` 로 빈 검증·답변이 관측될 때, 용도는 안전 상한) · (b) `SUBMISSION_ENRICH` 를 `KEYWORD_CONTEXT` 에서 분리할지 · (c) 응답 모드별 수준을 메시지마다 고르게 할지 — 하지 않는 쪽이 기본 · (d) 출력 상한을 정하지 않던 호출부 — **10-12 해소**(분류·리랭크·제목·이미지 둘은 자기 상한 2,048, 요약은 `max-summary-chars` + 1,024; 남은 것은 프롬프트를 스스로 만드는 질의 확장 하나, PITFALLS "짧은 호출의 출력 상한") · (e) **유휴 워치독은 블로킹 호출을 보지 못한다** — 분류·확장·검증은 리스너 이벤트 없이 도는 블로킹 호출이라 생각을 켜고 `app.sse-idle-timeout-seconds` 를 크게 낮춘 배포에서 끊긴다(기본 300초에서는 문제없다). 고칠 방향은 블로킹 호출 진행 중을 활동으로 보거나 인터럽트로 끊긴 호출을 차단에서 빼는 것 · (f) 측정 못 한 넷(`SUMMARY`·`IMAGE_DESCRIBE`·`MD_CORRECT_VISION` 등)은 소형 모델 계층·비전 모델이 있는 배포에서 재고 · (g) 더 큰 모델·원격 추론 모델에서 하네스를 다시 돌려 출하값과 상수 재확인 · (h) 카드의 "⚠ 타임아웃 넘음"은 읽기 타임아웃만 본다 · (i) 카드의 작은 한계 — 저장하면 펼친 [자세히] 가 접히고, 라우팅 모드별 줄은 저장된 수준으로만 계산한다.

---

### 6.30 프롬프트 축소 사다리 — 바닥값이 있는 단계형 (RAG 답변) ✅ 완료 2026-10-26 (설계 2026-10-21)

> 설계 전문은 커밋 `d1f7290` 의 이 절에 있다. 결정의 이유와 함정은 [PITFALLS](PITFALLS.md#servicepromptladderjava), 측정은 [PROMPT_LADDER_EVALUATION.md](PROMPT_LADDER_EVALUATION.md). 소스 주석이 가리키는 라벨(칸 1~7 · 재시도 A~D)은 그대로 둔다.

**문제와 목표.** 예전 규칙은 하나였다 — 넘치면 하위 문서를 먼저 버리고 그래도 넘치면 이력을 오래된 턴부터 버린다. 긴 독립 질문에는 이력이 쓸모없고 짧은 후속 질문에는 직전 턴이 지시어 풀이의 재료라 턴마다 나은 쪽이 다른데 언제나 같은 쪽을 골랐고, 사전 예산(`fitToBudget`)과 초과 후 재시도가 서로 다른 두 벌의 규칙이었다. **잃는 것이 작은 조각부터 자르고, 문서에 top-k 의 50% 바닥을 둔다**(보장이 아니라 순서). 사전 예산과 축소 재시도는 같은 사다리 하나(`PromptLadder` — 순수 클래스)를 공유하고, `/settings` 미리보기도 같은 `Plan` 을 부른다(`ThinkingPreviewParityTest`).

**사다리.** 넘칠 때만 한 칸씩 내려가고 칸마다 다시 잰 뒤, 멈춘 칸에서 앞 칸들을 **거꾸로 되돌려 채운다**. ① 과거 질문 500자 자르기 → ② 직전 1턴만 → ③ 문서를 **top-k 의 50%(올림, 최소 1) 바닥**까지 → ④ 직전 Direct 답변을 요약으로 → ⑤ 이력 전부(후속 질문이면 다시 쓴 질문 한 줄) → ⑥ 문서 1개 → ⑦ 최상위만으로 보내고 서버가 거절하면 축소 재시도. 절대 줄이지 않는 것: 시스템 프롬프트 · `[질문]` 원문 · `[경고]` · `[직전 시도 메모]`.

- **불변식**: 바닥 아래에서는 이력 때문에 문서가 빠지지 않는다 · 안내(`budgetNote`)는 실제로 보낸 시도의 결과로 한 번만 세운다 · 50%·500자는 상수.
- **범위**: RAG 답변 프롬프트(블로킹·스트리밍·PROGRESSIVE 2차)만. Direct·`meta` 는 예전대로, 검증 호출은 발췌 꼬리만 덜고 사다리와 묶지 않는다.
- **설계와 다르게 정한 것**: 턴으로 나뉘지 않는 이력은 칸 5 전에 줄 단위로 줄인다 · 축소 재시도 상한 = 이력 칸 수 + 문서 걸음 5 · D 의 "모름" 기본값은 측정으로 바꿨다.

**검증 실패 재시도와의 관계.** 원칙: **재시도의 판단은 모델이 실제로 본 것으로 한다**("보낸 집합" = 평가받은 답변을 만든 시도의 프롬프트에 실린 문서). A. 교체는 보낸 집합 안에서만 고른다(`selectAmongSent`) · B. 조기 종료도 보낸 집합으로 비교한다(`repeatsLastRejection`, PROGRESSIVE 업그레이드가 같은 술어) · C. 재시도는 이력을 직전 시도보다 더 줄이지 않는다 · D. 새로 들어온 문서를 나중에 뺀다(`retryRemovalOrder` — 검증이 쓴 문서를 보고하지 않았으면 직전에 실린 문서를 지킨다. 반대로 두면 바닥 아래 재시도가 상·하위 문서를 번갈아 싣는 왕복을 했다, 12K 긴 질문 재시도 11 → 19) · **E(문서를 줄인 채 나온 불충분 판정의 즉시 업그레이드)는 측정에서 그런 재시도의 약 3분의 1 이 근거 있음으로 끝나 넣지 않았다.**

**측정 (2026-10-26).** 창 16K·12K 를 선언하고 43사례(긴 독립 질문 13 · ① 14 · ② 8 · ③ 8, top-K 15)로 비교했다. 완료 기준을 전부 만족: 긴 질문은 첫 시도의 문서가 16K +1.0 · 12K +1.2개, 최종 근거 있음 두 창 모두 +1, 재시도·LLM 호출이 줄었다(16K 58 → 54 · 12K 48 → 44). **남은 것**: 서버 쪽 초과(`[SHRINK]`)를 좁게 띄운 실제 서버로 한 번 · 칸 1 과 칸 4 의 세부 손실 사례. (바닥 아래에서 같은 집합으로 반려되면 하위 문서로 한 번 갈아타는 재시도는 3승 2패·LLM 호출 +20 이라 넣지 않았다.)

**대가(요약).** 2턴 이상 전에 붙인 재료는 좁은 창에서 칸 1 에서 잘린다 · 칸 4 요약은 세부를 잃는다 · 되돌려 채우기는 독립 질문에도 이력을 다시 싣는다 · 독립화가 빗나가면 칸 5 가 틀린 지시어 풀이를 싣는다(`[질문]` 원문은 그대로).

**연관 작업 — 질문 한도(같은 분석에서 나옴).** 한도는 5,000자가 아니라 **10,000자**로 올랐다(§6.32): ① 상수 분리(채팅 전용 상수 + 창 기준 한도 `QuestionLimit`)와 ② 질문 임베딩 상한(500자 초과는 앞 300 + 뒤 200자)은 해결. **열려 있던 둘은 2026-11-12 에 닫혔다**: ③ 이력 속 과거 질문 — `buildSummaryInput()` 이 질문을 모든 경로에서 500자로 자른다(앞쪽 턴의 긴 질문 하나가 요약 전부를 차지해 최신 턴의 요약을 밀어냈다 — 완성된 요약을 앞에서부터 자르는 `truncate()` 때문). ④ Direct 경로의 축소 재시도는 **2026-11-12 해결** — 사전 예산이 빗나가 서버가 거절하면 이력 사다리(과거 질문 500자 → 직전 1턴 → 이력 전부, 앞 걸음과 같아지는 칸은 건너뜀)로 다시 보낸다(블로킹·스트리밍 모두, 스트리밍은 토큰이 나간 뒤에는 안 한다). 예산 영향: 16K 창에서 한글 5,000자 질문은 답변 문서 1개(이 사다리로 2개)·검증 발췌 2개 — 검증이 줄어든 발췌로 판정해 "판정 없음"이 는다. 32K 이상이면 문서 10개 전부가 들어간다.

### 6.31 이력 제외 체크박스 — 이전 대화 대신 문서를 더 (RAG 답변) ✅ 완료 2026-10-27 (설계 2026-10-23)

> 설계 전문은 커밋 `d1f7290` 의 이 절에 있다. 결정과 함정은 [PITFALLS](PITFALLS.md#이력-제외-631), 측정은 [PROMPT_LADDER_EVALUATION.md §8](PROMPT_LADDER_EVALUATION.md), 사용법은 USER_MANUAL §2.2.

**무엇.** 채팅 입력줄 `응답옵션` 왼쪽의 체크박스(기본 꺼짐). 켜면 그 메시지의 RAG 답변 프롬프트에서 `[이전 대화]` 를 통째로 빼고 그 자리만큼 검색 문서를 더 싣는다 — §6.30 이 자동 규칙이라면 이것은 같은 선택(이력이냐 문서냐)의 수동 쪽이다("사다리를 칸 5 에서 시작"과 같다). Direct 에서는 비활성(서버도 `ChatForm.excludesHistory()`·`ChatRequest` 가드로 막는다), `meta` 로 판정된 턴은 무시한다. 켜도 그 턴의 질문·답변은 이후 이력에 평소처럼 들어간다(싫어요와 다르다).

**결정.** 검색이 최종 컷에 **`이력 상한 ÷ chunk-size`**(기본 +4)를 더하는 것은 이력을 최대 그만큼 빼고 같은 양의 문서를 넣는 **교환**이라 답변 프롬프트의 최악 크기가 같기 때문이다. 검증 프롬프트에는 이력이 없어 교환이 없으므로 **검증 쪽 상한 둘**(검색 전 발췌 글자 상한 32,000자 · 검색 뒤 최종 컷의 실제 문서 크기로 잰 발췌 예산)이 부스트를 줄인다. 설계와 다르게 정한 것: 검증 상한을 검색 **뒤**에 잰다 · 이력 로딩은 건너뛰지 않는다(`meta` 턴이 이력으로 답해야 하고 분류와 병렬) · 사다리에는 칸 5 의 결과가 아니라 **빈 이력**을 넘긴다(되돌려 채우기가 다시 싣지 않게) · 턴 저장은 켠 턴에만 사후 UPDATE(Flyway V11) · 체크 상태는 저장하지 않는다. 꺼진 상태의 프롬프트는 `AnswerPromptGoldenTest` 로 기능 이전과 바이트 단위로 같게 고정했다.

**측정 (2026-10-27).** 긴 독립 질문 13사례(다른 주제 이력 4턴 · top-K 15)를 16K·32K 에서 켬/끔 비교: keyHit 은 두 창 모두 5 → 5 — 완료 기준 충족. 32K: 근거 있음 10 → 11, LLM 호출 44 → 42, 대가는 입력 토큰 +13% · 답변 +20% · 소요 +5%. 16K: 첫 시도 부스트는 13건 모두 0(검증 예산에 자리가 없다)이지만 근거 있음 8 → 10 · 재시도 14 → 8. 효과는 창이 넓을수록 크다. **남은 것**: 64K 는 재지 못했다(서버 `-c 32768`) · 후속 질문에서 잘못 켰을 때의 손해는 재지 않았다 · 16K 첫 시도 부스트를 살리려면 답변 크기 추정을 실제 통계로 바꿔야 하는데 검증 발췌가 잘릴 위험이 있어 두었다.

### 6.32 긴 질문 검색 · 그림 설명 · 청크 그림 편집 ✅ 완료 2026-11-04

세 가지가 한 날 연달아 들어갔다. 공통점은 **"저장된 원문"과 "검색·LLM 에 쓰이는 형태"를 갈라 두는 것**이다 — 질문(전문 ↔ 검색 질의), 청크 본문(저장 ↔ 프롬프트용 `normalizeForPrompt`), 그림(마커 ↔ 설명).

**① 질문 한도와 긴 질문의 검색** — 한도 2,000 → **10,000자**(`app.chat.max-question-chars`, 500~100,000). 사다리는 현재 질문을 줄이지 못하는 고정비라 창이 작으면 한도 안의 질문도 예산을 넘는다 → `QuestionLimit` 이 **창 기준 상한**(입력 예산 − 고정 2,000토큰 − 문서 한 개)을 함께 걸어 대화를 만들기 전에 "이 모델에서는 약 N자까지"로 거절한다(창을 모르거나 500자 이하면 안 건다). 검색 질의는 길이로 갈린다 — **500자 초과**: 벡터 검색문은 앞 300+뒤 200자, BM25 는 질문 전체에서 뽑은 낱말 20개(지시 구간의 식별자 우선), LLM 호출 없음 · **1,000자 초과**: `QuestionCondenser.distill()` 이 `검색문:`/`키워드:` 두 줄을 한 번에 만들어 벡터 검색문을 바꾸고 키워드는 결정적 추출과 합집합(30개). 한 턴의 질의 전처리 LLM 호출은 여전히 최대 하나다. **검색에만** 쓰고 답변 프롬프트의 `[현재 질문]` 은 전문이다. `wasCondensed()` 는 긴 질문 경로에서 거짓이다(이력과 무관한 요약을 "이전 대화에 비추어 다시 쓴 질문"이라 부르면 거짓). 임계값·개수는 코드 상수.

**② 프롬프트 전용 축약** — `MarkdownNoiseNormalizer.normalizeForPrompt()` 가 답변·검증 프롬프트 세 자리에서만 `[이미지: 경로]` → `[이미지]`(뒤따르는 `[이미지 설명: …]` 은 유지)와 `[페이지: N]` 단독 줄을 뺀다. 임베딩·FTS 입력과 `chunk_fts_key.content_hash` 는 `normalize()` 그대로여야 한다 — 바꾸면 전체 재인덱싱이 필요하고 재사용 스냅샷이 전부 "수정됨"이 된다.

**③ 그림** — 설명 프롬프트를 `VisionDescriptionService.CAPTION_PROMPT` 하나(`형식: 한두 문장`, 일반 서술 금지)로 단일화하고 출력은 `cleanCaption()` 을 지난다(옛 프롬프트는 소형 모델이 어느 이미지에나 맞는 문장을 쓰게 했다 — 코퍼스 설명 10개 중 8개). **청크 편집의 그림 관리**: 그림의 자리는 본문 속 `[이미지: …]` 마커라 추가·교체·삭제는 **텍스트 변환**이고, 규칙은 서버의 순수 클래스 `ChunkImageEditor`(JS 테스트 하네스가 없다)에 있으며 **서버가 저장하지 않는다**(저장은 청크 「저장」 하나). 올린 파일은 `images/chunks/`(`images/submissions/` 는 `sweepOrphans` 가 24시간 뒤 지운다). 화면 정리(`/settings` 화면 표시 카드·`/admin` 배치·컨텍스트 창 미탐지 문구 — 앱에는 모를 때 쓰는 기본 창이 없어 숫자를 지어 보이지 않는다)도 같은 날이다.

**열린 항목**
- ~~**이력 속 긴 질문**(③)~~ · ~~Direct 경로의 축소 재시도 없음(④)~~ — 둘 다 2026-11-12 해결(과거 질문 500자 상한 · 이력 사다리 재시도).
- **긴 질문 검색 품질은 측정하지 않았다** — 골든셋이 없다. 500/1,000자 경계·낱말 20개·앞 300+뒤 200자는 설계 가정이다.
- **이미지 유형 분류기는 제거했다**(2026-11-04) — 다섯 유형이 같은 프롬프트를 가리켜 분류가 설명을 바꾸지 못하는데 이미지당 호출을 한 번 더 냈다. `ImageTypeClassifier`·`ThinkingSite.IMAGE_TYPE` 등을 코드째 걷어냈고 `image_descriptions.image_type` 컬럼만 남았다([IMAGE_PROCESS.md §13](IMAGE_PROCESS.md)).
- 새 설명 프롬프트의 실제 출력 품질과, 이미 인덱싱된 문서의 옛 설명(재인덱싱·「설명 생성」을 해야 바뀐다) · 청크 편집에서 마커를 지워도 `images/chunks/` 파일은 남는다(고아 정리 없음).

### 6.33 Agent·MCP 전환 — 검색 개선 · Tool · 고정 워크플로 🟡 구현 완료(0~5단계, 2026-11-06~11) — 실사용 2주 관측·기본값 판단이 남음

**무엇을 왜.** 지금 `AgentGraph` 는 고정 순서로 돌고 LLM 이 도구를 고르지 않는다. LLM 이 Tool 을 골라 문서 검색·카탈로그 조회로 답하고, 그 위에서 영향도 분석과 문서 유지보수까지 지원하는 구조로 옮긴다. 이 절은 결정과 진행을 추적한다. 구조·Tool 명세·시퀀스·클래스·리스크·실측 표는 [AGENT_MCP_DESIGN.md](AGENT_MCP_DESIGN.md) 에 있다.

**결정**

| 항목 | 결정 | 근거 |
|---|---|---|
| 검색 | 후보 `search-top-k × 2`(기본 20) → 교차 인코더 재순위화 → 상위 `max(5, ⌈top-k ÷ 2⌉)`(기본 5). 편집하는 설정은 `search-top-k` 하나이고 나머지 둘은 `/settings` 에서 조회 전용 | 일회성 측정에서 골든셋 recall 0.96 → 1.00, 후속 질문 0.67 → 0.76, LLM 에 넣는 청크는 절반 |
| 재검색 | 리랭커 1위 점수가 0 미만이면 LLM 이 새 검색어를 만들어 다시 찾는다. 최대 3회 | 후속 질문 recall 0.90. 로컬 모델은 재검색을 스스로 결정하지 못했고(무관한 결과 12건 중 0건), 충분 여부 판단도 치우쳤다 |
| 적용 범위 | 새 응답 모드 없이 S/N/C 공통 경로. `/settings` 로 끈다 | 추가 비용이 작고 재검색은 필요한 턴에서만 돈다. C 는 근거 엄격도 축이라 바꾸면 응용 기능이 사라진다 |
| 리랭커 | `llama-server --reranking` + `bge-reranker-v2-m3` | 후보 20개에 약 3초(CPU)이고 점수를 준다. 기존 LLM 리랭커는 p50 15.7초에 점수가 없다 |
| Tool 선택 | 턴당 1회, 분류와 병렬. 호출 없음·실패는 `RETRIEVAL` | E2B 49건 중 44건, 형식 실패 0건. Agent 가 검색을 없앨 수 없게 한다 |
| 답변 | 기존 `ANSWER → CRITIC` 유지 | 스트리밍·사다리·검증·참여도를 건드리지 않는다 |
| MCP | 내부는 같은 JVM 직접 호출. 같은 Tool 을 MCP Server(`STATELESS`, `/api/v1/mcp`, API 키)로 외부 공개. MCP Client 는 외부 서버가 생길 때 | HTTP 왕복과 신원 재전달이 없다. pool=1 과 메모리 브레이커 때문에 별도 프로세스는 같은 DB 파일을 쓸 수 없다 |
| DB 조회 | 앱 내부 DB 의 미리 정한 조회만(`queryCatalog`) | 업무 DB 접근 코드가 없다. LLM 이 쓴 SQL 은 실행하지 않는다 |
| 영향도·유지보수 | 고정 워크플로. LLM 에게 쓰기 Tool 을 주지 않는다 | 소형 모델에게 긴 계획을 맡기지 않는다. 문서 본문의 프롬프트 인젝션 피해가 읽기로 제한된다 |
| 코퍼스 반영 | 관리자 승인 후, 청크 + 변환 MD. 원본 DOCX/PDF 는 고치지 않고 리포트에 밝힌다 | §10.11 의 "코퍼스로 들어가는 문은 관리자 승인 하나"를 지킨다 |

**단계** (세부 상태·측정은 [AGENT_MCP_DESIGN.md](AGENT_MCP_DESIGN.md) · [SEARCH_EVALUATION.md](SEARCH_EVALUATION.md) §7 · [AGENT_EVALUATION.md](AGENT_EVALUATION.md))

| 단계 | 내용 | 상태 |
|---|---|---|
| 0 | 선결 결함(블로킹 호출의 `model` 누락) · 검색 기준선 | ✅ 2026-11-06 — `ModelPinningChatModel`, 기준선은 SEARCH_EVALUATION §7 |
| 1a | `HybridSearchService` 추출 · 교차 인코더 리랭커(`CrossEncoderReranker`) · 재검색 루프(`SearchLoopService`·`SearchQueryRewriter`) · 하네스 확장 | ✅ 2026-11-06~11 — 골든셋 recall@5 1.000, 여러 청크 coverage 0.903. **후속 질문 recall@5 는 독립화 가드 셋·원문 시험(11-06, 0.762~0.810)에 이어 독립화한 질문의 첫 회차 후보 풀에 원문 후보를 합쳐(11-11) 0.857(18/21)로 목표 0.85 를 충족했다 — 단 여유 한 건**(§7.8). 앞 질문을 이어 붙인 검색어(concat)까지 풀에 합치는 것은 지연 +60% 에 이득이 반 건 안팎이라 **넣지 않았다**(§7.8.1). 재검색의 순효과는 표본으로 증명되지 않았다 |
| 1b | Tool 계층(`ToolCatalog`·`ToolGuard`·읽기 Tool 여섯) | ✅ 2026-11-06 — 권한·경계 테스트 50건. 경계는 `ToolBoundaryConventionTest` 가 빌드로 고정 |
| 2 | MCP Server(전용 보안 체인 · API 키 · `mcp` 레이트리밋 버킷, 기본 꺼짐) | ✅ 구현 2026-11-06 — 네 모드 보안 테스트 · 직접 만든 JSON-RPC 로 `initialize`/`tools/list`/`tools/call` 왕복. **MCP Inspector·Claude Code 로의 연결은 아직 확인하지 않았다** |
| 3 | Tool 선택(`ToolPlanService`·`ToolDispatchService`·`TOOL` 노드, 기본 꺼짐) | ✅ 2026-11-10 — 사전 평가에서 네이티브 Tool Calling 은 정확도 0.20·형식 실패 0.59 로 못 쓰고 **프롬프트 JSON 0.939** → 프롬프트 JSON, 새 사이트 없이 `CONDENSE` 재사용. 앱 경로 49건(11-11, 선택지 다섯·관리자 여부별): **비관리자 47/49 · 관리자 46/49**, 문서 질문의 잘못된 우회 0/26, 영향도 5/5. 비관리자의 수정 요청이 `analyzeImpact` 로 새던 문제(3/5)는 프롬프트 규칙 + 코드 가드(`asksAboutImpact`)로 고쳤다 |
| 4 | 영향도·유지보수 워크플로(`change_job`·`change_proposal`·`/admin` 대기열) | ✅ 2026-11-10 구현·종단 확인 — 아래 요약. 영향도 판정 실측(11-11): 심은 언급 회수 24/24, 지나가는 언급의 오탐은 단어 경계 가드로 6/15 → 0/5 |
| 5 | Tool 호출 추적 패널(`agent_tool_call` V14 · `/admin` "Tool 호출 추적") · 매뉴얼 · 단계적 전환 | 🟡 패널·관측 테이블·매뉴얼 구현 2026-11-11(실화면 확인) — **남은 것: 실사용 2주 관측 · 기본값 판단 · 백업에서 되돌리기 리허설** |

**4단계 요약 (2026-11-10 구현 · 세부는 CLAUDE.md 표 · [PITFALLS.md](PITFALLS.md) · 매뉴얼 [OPERATOR §6.14](OPERATOR_MANUAL.md))**

- **4a 영향도**: Flyway V12 `change_job` · `findMentions`(전수 조회, 3글자 미만은 `tooShort`) · `ImpactAnalysisWorkflow`(저장 원문 → 청크별 LLM 판정 → **인용 검증은 코드** — 근거가 본문에 없으면 `unverified`; 상한 40 · 사용자당 동시 1건 · 채팅에 양보 · 기동 시 `interrupted`) · `analyzeImpact` Tool(PUBLIC, 시작만) · 리포트 `/ui/impact-jobs/{id}`(요청자·관리자만).
- **4b 유지보수**: Flyway V13 `change_proposal` · `MaintenanceWorkflow`(결정적 치환 — 본문 + `chunk_context`·`excerpt_keywords` + 불변식 검사: 이미지·페이지 마커, 코드 펜스) · `/admin/change-jobs`(Diff 검토 · **문서 단위 승인·거절**) · `requestDocumentChange` Tool(ADMIN). 수정안을 만드는 것은 코퍼스를 바꾸지 않는다(쓰는 곳은 승인 뒤 `ChangeApplyService` 하나).
- **적용 순서**(`ChangeApplyService`): 문서 잠금 → **DB 백업**(실패하면 쓰지 않고 `approved` 로 남김) → 청크마다 `AdminService.applyChunkEdit`(해시 대조 → `stale` · **임베딩 먼저** → 실패하면 `failed`·청크 불변 · id 유지 · 재사용 `modified`) → `MarkdownWriteBack`(발생 수 대조, 아니면 `MD_CONFLICT`·`MD_MISSING`, 의미 변경이 있으면 `MD_PARTIAL`). 원본 DOCX/PDF 는 고치지 않는다.
- **선택 기능**(화면에서만, 기본 꺼짐): `semantic`(글자 치환 뒤 LLM 이 연관 변경 제안 — 가드: 되살아남·새 말 감소·마커/펜스·길이 0.6~1.4·바뀐 줄 ≤50%·순삭제 금지) · `caseVariants`(대소문자 변형을 그 모양에 맞춰 치환; 꺼져도 변형 청크 수는 항상 보고). 치환 규칙은 `TermReplacement` 하나.
- **채팅에서 관리자 호출**: 로그인한 ADMIN 의 "A 를 B 로" → **먼저 되묻고**(`PendingChangeConfirmations` — 사용자·대화당 하나 · 5분 · 한 번), **바로 다음 메시지가 정확히 긍정일 때만** 시작. 옵션은 채팅에서 켜지 않는다. 관리자 여부는 요청 스레드에서(`ThreadContext.admin`) — plain no-auth 채팅은 늘 게스트.
- **재인덱싱과의 동시 실행 잠금**: `DocumentWriteLocks`(문서 단위 · 기다리지 않고 거절 · 비재진입)를 `ChangeApplyService` 와 `DocumentIndexer.reindexFromMd` 가 공유. 단위 테스트만(실서버 확인 없음).
- **확인한 것**: 실제 코퍼스 복사본(2,388청크)·실제 앱·실제 임베딩 서버로 종단 확인 — 승인 전 불변 · 적용 · `stale` · `MD_CONFLICT` · 거절 · 재인덱싱 뒤 유지(유지보수 41, 의미 변경 14, 대소문자 26, 채팅 관리자 19/20 — 실패 1건은 하네스 텍스트 검사 약점)([scripts/maintenance_e2e](../scripts/maintenance_e2e/README.md)). 이 확인이 잡은 결함: `heading` 메타데이터에 옛 말 잔존(적용 전용 키로 치환) · sqlite-vec `add()` 가 임베딩 전에 옛 행을 지워 실패 시 청크 소실(삭제를 삽입과 같은 트랜잭션·임베딩 뒤로).
- **소형 모델의 한계**(E2B): 의미 변경 수확이 작다(10청크 중 1개 통과, 전부 검토 필요) · 대상 없는 짧은 수정 명령은 `NONE`(→검색)으로 두는 일이 많다(두 시험 세트 4/6, 오탐 0/7) — 놓치면 검색 답변이 나오고 화면을 쓰면 된다.
- **남은 것**: 더 큰 모델에서의 의미 변경 품질 · 의미 연관 후보(하이브리드 검색) · 영향도 실모델 실측에서 **가짜 인용 걸러냄은 모델이 지어낸 적이 없어 미확인**이고 실제 코퍼스 라벨 세트도 없다 · 임베딩 실패 주입의 종단 확인(단위 테스트만).

2·3·4 는 1b 뒤에 병행할 수 있다. 다만 3 의 워크플로 Tool 은 4 가 끝나야 켠다. 1a 는 MCP 와 무관하게 채팅 품질을 올리는 단계라 먼저 효과가 난다.

**선결 결함 (해결)** — 블로킹 호출의 `model` 누락: 옵션에 `.model()` 을 싣지 않는 호출부의 값이 `gpt-5-mini` 였다(`OpenAiChatModel` 은 호출자 옵션이 있으면 기본 옵션을 병합하지 않는다 — 모델 이름을 무시하는 서버에서는 증상이 안 보인다). `ModelPinningChatModel` 로 고쳤다.

**하지 않기로 한 것**
- **LLM 자율 Tool 루프** — E2B·e4b 가 재검색을 결정하지 못했고 코드 주도가 같은 효과를 냈다. 재개 신호: Tool 을 스스로 이어 부르는 모델이 배포에 들어오고, 그 루프가 코드 주도보다 앞서는 것이 측정될 때.
- **LM Studio 의 생각 끄기**(운영 결정, 2026-11-05) — llama-server 와 달리 LM Studio 는 `chat_template_kwargs.enable_thinking` 을 오류 없이 무시해 끔이 듣지 않는다. 고치지 않고 알려진 제약으로 두며 `application.properties` 의 `thinking-dialect` 설명에 주석으로 남겼다. 그 환경의 호출은 생각을 해서 측정에서 Tool 선택이 호출당 약 13초였다. `reasoning_effort: none` 을 보내면 약 2.6초였다는 측정은 설계서 부록 A.2 에 있다.
- `AgentGraph` 의 ReAct 교체 · 자유 SQL Tool · 같은 JVM 에서 MCP Client 로 자기 서버 호출 · MCP Server 별도 프로세스 · 채팅 스트리밍 경로에 tools 싣기 · C 모드 변경.

**열린 항목**
- 부록 A 의 일회성 실측(큐레이션 축 제외)은 1a 뒤 하네스로 다시 쟀다. 그래도 청크 5개가 비교형·종합형 질문에 모자랄 수 있다 — 모자라면 컷을 6~7 로 올린다(핫 편집).
- 리랭크 뒤에는 큐레이션의 RRF 가중치가 사라진다(`curated-related.json` 으로 확인). 문턱 0.0 은 모델 원점수라 모델을 바꾸면 다시 잰다.
- Chroma 백엔드: `AdminService.collectionFor()` 의 `manual_` 과 `VectorStoreRegistry` 의 컬렉션 이름이 달라 보인다(미확인) — 유지보수를 Chroma 에서 지원하기 전에 확인한다.
- 추적 패널의 기록은 질문 본문을 담지 않는다. 기본값을 켜기 전 2주 관측의 판정 기준은 **폴백 비율**(계획 호출 중 Tool 이 안 정해진 비율)과 턴 지연이다.

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

### 8.2 작업 단계 — 전부 완료

- **Step 5.1~5.8** — `VectorStoreProvider` 추상화(Chroma 무행위 리팩토링) · sqlite-vec 확장 로딩(`DataSourceConfig`, `SqliteVecVerifier`) · 스키마 초기화(`SqliteVecSchemaInitializer`, 차원 미설정 시 fail-fast) · `SqliteVecVectorStoreProvider`(upsert 미지원이라 add = DELETE 후 INSERT) · 조건부 빈 스위치 · 설정 외부화(`.env`·compose profiles) · 데이터 이전(백엔드 전환은 항상 재인덱싱 — 원본 보존·무손실) · `/admin` 백엔드 공통 상태 카드와 청크 브라우징 패리티.
- **Step 5.9 태그 기반 검색 스코프** — 업로드 다중 태그 + 채팅의 태그 선택으로 **엄격 AND 필터**(Chroma·sqlite-vec·하이브리드 공통). provider 가 배열 포함 연산자를 지원하지 않아 `RetrievalService` 가 융합 직후 Java post-filter 로 걸고 결과 부족에 대비해 `candidateK` 를 선제 확대한다(`app.search-tag-candidate-multiplier`). 태그의 **권위 있는 출처는 `doc_registry.tags`** 다(Key Constraints 참고).
- **Step 5.10 sqlite-vec 운영 DB 분리 → ↩ 2026-09-28 단일 파일로 환원.** `SQLITE_VEC_DB_PATH` 로 벡터/FTS 를 별도 `vector.db` 로 분리하려 했으나 **한 번도 실효된 적이 없다** — `vectorJdbcTemplate` 빈이 정의되는 순간 Boot 의 `JdbcTemplate` 자동설정이 물러나 운영 저장소까지 전부 `vector.db` 에 썼고(2026-09-04 확인) `memory.db` 는 Flyway 만 닿는 빈 파일이었다. 게다가 `QuestionReuseRepository.findSourcePreviewRows()` 가 운영 테이블과 FTS/벡터 테이블을 SQL 하나로 조인하게 되어, 배선만 고치면 기존 배포는 데이터가 사라진 것처럼 보였다. 측정된 적 없는 락 분리를 위해 그 조인 해소 + 이전을 하는 대신 **DataSource 를 하나로 합쳤다**(`DataSourceConfig`): 기본 `{DATA_DIR}/memory.db` 하나, 옛 스위치 값은 sqlite-vec 에서 **그 한 파일의 경로**로만 읽는다(데이터가 전부 거기 있으므로 이전 불필요). 이력 없는 옛 파일은 `spring.flyway.baseline-version=3`(`FlywayBaselineTest`). 경위·함정은 [PITFALLS](PITFALLS.md#벡터-스토어-백엔드와-vecfts-datasource), 운영자 절차는 OPERATOR_MANUAL §6.3.1, 재개 신호는 부록 결정표.

---

## 9. Phase 6 — 폐쇄망(Air-gapped) / 노-도커 실행 지원 🟢 G1~G5 완료 (sqlite-vec 라이브 부팅·실소켓 0 측정은 운영 인수)

> 런북은 [OPERATOR_MANUAL.md §4.5](OPERATOR_MANUAL.md). 목표는 (A) Docker 없이 실행 (B) 폐쇄망에서 sqlite-vec 단독 + 로컬 LLM(llama-server) 운영 — Phase 5 로 ChromaDB(유일 필수 Docker)를 뺄 수 있고 프론트 자산은 webjar/로컬 번들이라 CDN 의존 0.

해결한 공백: **G1** `LlmConfig` 가 빈 키 프로바이더를 드롭하던 것 — LOCAL role 은 빈 키여도 등록(`"no-key"` 치환) · **G2** `EMBED_DIMENSIONS` 외부화(sqlite-vec DDL 전용이라 모델의 실제 출력 차원과 정확히 일치해야 하며 미설정 시 fail-fast) · **G3** `LLM_ROUTING_MODE` 외부화(`LOCAL_ONLY` 로 외부 호출 명시 차단) · **G4** `USE_CADDY_REVERSE_PROXY_HTTPS` 오타 정리 · **G5** 라우팅 계층의 외부 무선택. 운영 전제: 빌드 산출물(fat-jar + JRE 21 + 호스트 arch `vec0` + 필요 시 tessdata) 반입, OCR/Vision 은 옵션, TLS 는 HTTP 직노출 또는 사내 역프록시/사설 CA, `.env` 는 노-도커 시 자동 로드되지 않는다(셸 export/기동 인자). 🔵 미착수: `USE_CADDY_…` 하위호환 별칭(선택, 낮은 우선순위).

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

세부 산출물은 위 표 참고. §10.1 저장 텍스트는 원문 불변(맥락 헤더는 임베딩/FTS 입력에만), §10.1-보완은 재인덱싱 필요, §10.4 trigram 전환은 2글자 단독어를 여전히 놓쳐 §10.7.3 LIKE 폴백으로 보완했다.

### 10.5 검토 후 제외 (Phase 7-D 취소)

원래 Phase 7-D(인프라 투자)로 묶였던 아래 3건은 재검토 결과 **범위 제외**한다 — 삭제가 아니라 판단 근거를 남겨, 아래 "재개 신호"가 실제로 관측되면 이 기록을 근거로 다시 꺼낸다.

| 제외 항목 | 원래 개선안 | 제외 사유 | 재개 신호 |
|---|---|---|---|
| **sqlite-vec 배치 검색 단일 스캔** | vec0 brute-force KNN(O(n))을 다중 쿼리 1회 스캔으로 최적화 | 현 코퍼스 규모에선 병목이 임베딩 배치 생성(1 HTTP)이고 JDBC 루프는 수 ms라 체감 이득이 작다(JDBC 루프 N회는 수 ms — 실측 후 CTE 전환을 검토한다는 같은 판단) | 대규모 코퍼스에서 검색 지연 실측 악화 |
| **Cross-Encoder 리랭커** | LLM 리랭커를 ONNX bge-reranker 등 로컬 cross-encoder로 교체 | ONNX 런타임/모델 도입 + 폐쇄망 모델 파일 조달 비용이 크고, 현 opt-in `Optional<RerankerService>` LLM 리랭커로 충분. **인터페이스 유지 구조라 필요 시 구현만 교체 가능**(지금 만들 이유는 없음) | LLM 리랭크 정확도/지연 불만이 실사용에서 반복 보고. **2026-11-05 재개(§6.33)** — 임베딩과 같은 `llama-server --reranking` 이면 ONNX 가 필요 없고 GGUF 도 이미 있어 제외 사유가 사라졌다. LLM 리랭커는 p50 15.7초에 점수가 없어 재검색 판단에 쓸 수 없다 |
| **시맨틱 응답 캐시** | 질문 임베딩 유사도 > 임계값이면 캐시 답변 반환 | stale 답변 위험 + 무효화 복잡도(재인덱싱·버전 변경·§6.8 DISLIKE 연동) 대비 이득이 불확실 | FAQ성 반복 트래픽이 지배적이고 지연이 문제화될 때 |

> **§10.10과의 관계**: 아래 §10.10(좋아요 기반 큐레이션 Q&A)이 "시맨틱 응답 캐시"의 stale 위험을 우회한 변형이다 — 캐시처럼 답변을 verbatim 반환하지 않고 인간이 편집·검수한 Q&A를 **가중 RRF 축의 근거**로만 주입하므로 무효화 복잡도가 낮다. 순수 자동 캐시(위 항목)는 여전히 재개 신호 대기.

### 10.7 ~ 10.9 검색 정확도 마무리 · 속도 · 메모리 (Phase 7-E) ✅ 완료

세부 산출물은 위 표 참고. 보완 메모: §10.8.2 배치 실패 시 곧장 개별 TF 폴백 · §10.8.3 트랜잭션 결합으로 고아 `vec_embeddings` 행 방지 · §10.9.2 BLOB 직렬화는 기존 데이터와 호환(백필 불필요, 백엔드 전환 시 문서 1건으로 먼저 확인 권장) · §10.9.4 캐시 키는 SHA-256 해시로 고정 크기화.

§10.7.5 의 실 코퍼스 골든셋(26문항) + recall@k/nDCG@k 하네스(`-Dsearch-eval.enabled=true`)로 **2026-07-16 baseline recall@10 0.962 / nDCG@10 0.810**(유일한 미스 `sample-02`)을 확보해 §10.7.2·§10.7.3 의 무측정 결정을 데이터로 재검증했다. **재측정**([SEARCH_EVALUATION.md](SEARCH_EVALUATION.md)): 2026-10-12 코퍼스가 23종·2,388청크로 커진 뒤에도 0.962(운영 설정 + 질의 확장은 1.000) — 그 전에 골든셋 정답 문자열 6개를 바로잡았고(고치기 전엔 0.923) 하네스가 매번 코퍼스 점검(정답이 코퍼스에 있는 케이스 수 = recall 상한)을 찍는다 · 10-13 키워드 검색어 개선(조사·어미를 뗀 어간을 원래 형태 옆에 더함 — 3글자 이상 토큰의 26% 가 0건이었다) 뒤 두 구성 모두 **1.000**(§3.5). **질의 확장(MultiQuery)은 켠 채 유지**(2026-10-12) — 이 코퍼스에서 되찾는 것은 1건뿐이지만 대가(검색당 약 1.1초 + LLM 1회)가 답변 생성(12~50초)에 비해 작다. 코퍼스가 더 커지면 확장의 몫이 커질 수 있다.

---

### 10.10 좋아요 기반 큐레이션 Q&A 지식화 ✅ 완료 (유입 경로는 §10.11 로 대체됨)

좋아요한 답변을 `curated_qa` 로 스냅샷 → 예약 네임스페이스 `"curated"` 에 임베딩 → **가중 RRF 축**으로 융합 → 관리자(`/admin`)가 편집·삭제. §10.5 에서 제외한 "시맨틱 응답 캐시"의 **인간 검수 가능 버전**이다 — verbatim 반환이 아니라 검색 축의 근거로만 주입하므로 stale 위험·무효화 복잡도가 낮다. 구현은 CLAUDE.md `CuratedQaService`/`CuratedQaRepository`, 운영은 [OPERATOR_MANUAL §6.7·§7.5](OPERATOR_MANUAL.md), UI 는 [UI.md](UI.md).

**확정된 정책**: 가시성 = 전체 공유, 답변은 **LLM 근거로 주입**(오답 증폭 방지) · 편집은 §10.11 에서 바뀌었다(본인은 **지식 제안 페이지**에서만, 관리자는 `/admin` 에서 전부) · **대화 삭제 시 큐레이션도 함께 회수**(§6.25 — 최초 정책은 반대였다; 대화가 사라진 뒤에도 그 답변이 검색 근거로 남는 쪽이 더 혼란스럽고 턴 삭제는 처음부터 회수하고 있었다. `CuratedQaService.onThreadDeleted()` 를 사용자·관리자 경로가 함께 부르고 `origin='manual'` 제안은 제외) · **문서 삭제와는 연동 없음**.

**열린 항목 (b)** — doc_id 인용 추적 + 문서 삭제 시 재검토 플래그: 지금은 `turn_source_ref`(§6.23)가 좋아요 출신 행의 출처 청크·`doc_id` 를 갖지만(손으로 쓴 제안에는 출처 문서가 없다) 연동은 미착수 — stale 인용이 실사용에서 보고되면 착수. (a) 큐레이션 축 문턱 별도화(`search-curated-qa-min-similarity` 기본 0.5 + 키워드 전용 히트는 내용어 둘 — `gateCurated()`, 2026-10-13) · (c) BM25 축 편입(2026-09-07 — `indexFts()`, `"curated"` 네임스페이스로 한 번 더 묻는다, CLAUDE.md Key Constraints) · (d) 골든셋 재측정(2026-10-12, 0.962 그대로) · (e) 프라이버시 트레이드오프는 §6.25 회수 정책으로 해소.

---

> **§10.11 · §10.12 · §10.13 구현 순서 — `10.11 → 10.13 → 10.12`** (셋 다 완료). §10.13 이 Direct 답변(문서에 근거하지 않은 텍스트)을 이력에 많이 남기고 §10.12 가 그 이력을 재료로 검색어를 만들어, §10.12 를 먼저 만들면 깨끗한 환경에서 통과시킨 뒤 §10.13 이 조건을 바꿔 오염이 나중에 드러난다. §10.11 이 먼저인 이유는 가장 위험한 구멍(무검토 유입)을 막는 사고 예방이라서다.

### 10.11 좋아요 → 지식 제안 경유 (§10.10 유입 경로 대체) ✅ 완료 (2026-09-02)

**무엇을 왜.** 검색 코퍼스로 들어가는 문이 둘인데 경비가 정반대였다 — 제안은 관리자 승인 필수, 좋아요는 게이트 없이 3초 뒤 반영. 가장 날카로운 모양이 **문서를 하나도 안 본 Direct 답변이 좋아요 한 번에 전체 검색 지식이 되는 것**이었다. `direct_mode` 가드를 박는 대신 **문을 하나로 모았다** — 좋아요는 제안 폼을 열어 줄 뿐이고 `curated_qa` 에 쓰는 경로는 관리자 승인 둘(`createFromSubmission`/`createFromLikedTurn`)뿐이다.

**유지해야 할 결정**: **채팅 = 감상, 제안 페이지 = 지식 관리**(채팅은 큐레이션 상태를 표시하지 않는다 — 좋아요 확인의 "예"는 좋아요 + 프리필 이동, "아니오"는 좋아요 취소) · **저장 모양이 출처마다 다르다**(손으로 쓴 제안 = 승인 시 N개 행, 좋아요 출신 = turn 키 행 하나 → 임베딩 시점에 벡터 N개 — 좋아요 출신을 `insertManual` 로 태우면 `UNIQUE(source_turn_id)`·삭제 회수·재승인이 함께 죽는다) · 승인 시 `source_submission_id` 필수(제안의 상태가 전부 그 컬럼으로 세어진다) · **수정은 등록본을 내리지 않고 재승인이 교체**한다(정책 3 — 제안은 이전 행을 먼저 내리고 새 id, 좋아요 출신은 제자리 upsert: 먼저 내리면 백그라운드 벡터 삭제가 방금 쓴 벡터를 지운다) · 좋아요 취소는 회수하지 않고 턴/대화 삭제는 **무조건** 회수한다 · 좋아요 출신은 pending 상한에서 제외한다. 정책 변경 3건 적용: ① 큐레이션 검색축 통합 ② C 개방 + S 사유 교체(`allowsCuration()` → `allowsSubmission()`) ③ 수정 중에도 등록본 유지. **계획이 틀렸던 지점**: `source_submission_id` 가 빠진 증상은 "승인 직후 회수됨"이 아니라 **청크 0개짜리 '등록 완료'** 였다(더 눈에 덜 띈다) · `chunkCount()` 를 행 수에서 `SUM(chunk_count)` 로 바꿨다.

**열린 항목**: §10.10 (b)(문서 삭제 연동)만 남았다. 기존 `origin='like'` 활성 행은 마이그레이션하지 않았다(이미 `/admin` 에서 보이고 회수되며 합쳐진 축의 단일 가중치로 검색된다).

---

### 10.12 짧은 후속 질문의 독립화 (condense) ✅ 완료 (2026-09-03)

**무엇을 왜.** 검색 축 셋(벡터 · BM25 · 큐레이션)이 전부 **질문 원문**을 보는데 이력은 답변 단계에서만 쓰였다 — `"그 설정은 어디에 있어?"` 는 검색어로 의미가 없다. `QuestionCondenser` 가 질문을 자립 문장으로 다시 쓰고, 그 한 값을 **검색 축 셋 · 리랭커 · 분류기**가 모두 쓴다(LLM 호출 순증 0). 결정 근거·함정은 PITFALLS [`QuestionCondenser`](PITFALLS.md#servicequestioncondenserjava).

**유지해야 할 결정**: 재작성은 검색에만 쓰고 답변 프롬프트의 `[현재 질문]` 은 **원문**이다(재작성이 빗나가도 검색만 틀리게 하는 격리) · 재료는 이전 **질문**들뿐(답변은 무겁고, 검색이 자기 답변을 다시 찾고, Direct 답변의 지어낸 용어가 검색어가 된다) · **두 게이트는 여집합**이고 확장 게이트는 **원문 길이**로 잰다(재작성 결과로 재면 한 턴에 전처리 호출이 둘이 된다) · 게이트는 길이만 본다(긴 질문의 분류는 즉시 출발해 이력 로딩과의 병렬성이 유지된다) · 실패하면 원문으로 검색한다 · 재작성을 진단 패널에 보이고 `VerificationSnapshot` 에 저장한다 · 출력 상한 `MAX_OUTPUT_TOKENS`(256) · on/off 키는 `app.search-multiquery-enabled` 를 공유한다. **계획이 틀렸던 지점**: 이전 턴이 Direct 일 때의 오염은 턴 필터가 아니라 **재료에서 답변을 뺀 것**으로 닫혔다.

**측정과 결정(2026-10-13, [SEARCH_EVALUATION.md §3.4](SEARCH_EVALUATION.md))**: 같은 주제 14 + 주제 전환 7문항에서 독립화는 원문보다 1~2건 낫고 주제 전환에서 손해가 없다 — 10-12 의 반대 결론은 하네스가 같은 스레드에 앞 질문을 쌓던 결함 위의 것이라 철회했다. 게이트 길이(10·12·15)는 출하값 15 가 가장 좋거나 같다. **게이트 아래의 대안은 그대로 두기로 결정**(앞 질문 이어 붙이기는 주제 전환에서 무너지고, 원문과 함께 검색은 비기면서 깊은 순위를 잃고, 되풀이 가드는 결과를 바꾼 케이스가 없다). 후속 개선은 §6.33 1a(독립화 가드 셋 · 원문 후보 풀 합치기). 다시 열 신호: 생성 모델을 바꿀 때 · 실사용에서 재작성이 앞 질문을 되풀이해 엉뚱한 문서가 검색될 때.

---

### 10.13 Direct 턴의 이력 확대 — 문서 자리를 이력에 돌려준다 ✅ 완료 (2026-09-03)

**무엇을 왜.** Direct 답변의 프롬프트에는 `[검색된 문서]` 블록이 없다. 기본 설정에서 그 자리는 `topK 10 × chunk-size 1,500` ≈ 15,000자인데 이력 상한은 모드와 무관하게 고정(당시 5,000자)이라 이력만으로 답해야 하는 모드가 이력을 가장 적게 받았다. 규칙과 렌더는 `HistoryPolicy` 하나 — 근거·함정은 PITFALLS [`HistoryPolicy`](PITFALLS.md#servicehistorypolicyjava).

**유지해야 할 결정**: 규칙은 "Direct 라서"가 아니라 **"문서 자리가 비어서"** 다(`이력 상한 = 입력 예산 − 문서가 차지할 자리`) · 1차 적용이 Direct 뿐인 이유는 모드가 아니라 **타이밍**이다(이력은 검색보다 먼저 로딩되고 Direct 만 문서 0 이 확정 — `meta` 분류는 Direct 로 세지 않는다) · 두 이력 경로가 같은 규칙을 읽는다(갈리면 요약 캐시 TTL 을 기점으로 맥락이 바뀐다) · 렌더는 두 축의 조합(지금 묻는 턴이 *얼마나*, 이전 턴의 모드가 *무엇을*) · 캡 제거와 예산 인상은 한 커밋 · 1단계(DN 도 `## 요약` 을 낸다)는 강제하지 않고 형식 미준수는 전문 폴백으로 수렴한다. **계획이 틀렸던 지점**: 좁은 창에서는 고정값보다 **작게** 나오고 그것이 옳다(Direct 경로에 예산 가드가 없어 고정값이 이미 창을 넘기고 있었다) · `ResponseModeBranchConventionTest` 는 텍스트 검사라 주석의 금지 패턴에도 빌드가 깨진다. **이후(2026-09-04)**: 이전 턴이 **Direct 였으면** RAG 로 물어도 전문을 싣는다(그 턴에는 복제될 문서가 없었다) · 프롬프트에 싣는 턴 수 상한 `HistoryPolicy.promptTurnCap()`(가져오는 창의 절반, 기본 5).

**열린 항목**: (a) RAG 턴에도 같은 계산을 적용할지(로딩 시점엔 `topK × chunk-size` 로 과대 추정할 수밖에 없다) · (b) RAG 턴에 대한 두 경로의 분량 차이 — 재현성 문제가 보고되면 착수 · (c) 넓어진 사실을 보일지(줄어든 쪽만 `budgetNote` 로 말한다).

---

### 10.14 청크 오류 신고 — 사용자 → 관리자 ✅ 완료 (2026-09-04)

> **결정 근거와 틀렸던 지점** → [PITFALLS.md](PITFALLS.md#servicechunkreportservicejava)

**무엇을 왜.** 출처 배지로 원문까지 연 사용자가 **청크가 틀렸거나 오래됐다는 것을 발견해도 말할 곳이 없었다** — 👍/👎 는 답변 평가라 청크를 담지 못하고, "현재 대화에서 이 청크 제거"는 표시 전용 동작이라 틀린 청크가 그 대화에서만 치워지고 다음 사람에게 똑같이 검색됐다. §10.11 이 코퍼스에 **넣는** 문을 관리자 승인 하나로 모았다면 이것은 **"고치자"의 문**을 같은 모양(사용자 제출 → 관리자 판단)으로 낸다. 구현: `ChunkReportRepository`(`chunk_report`)·`ChunkReportService`·`ChunkReportController`(`POST /ui/chunk-reports`)·`AdminController` 의 `/admin/chunk-reports/**` 넷 · 채팅 원문 모달의 🚩 버튼 · `/admin` 의 청크 단위 대기열 · 헤더 배지.

**유지해야 할 결정**: 신고는 **순수 대기열**이다(검색·재사용·벡터/FTS 어디도 바꾸지 않고 반영은 관리자가 청크를 실제로 고칠 때) · 신고자는 고칠 수 없다(사유 `WRONG`·`OUTDATED`·`BROKEN`·`OTHER` + 필수 코멘트 500자) · 관리자는 **청크 단위**로 본다(배지도 열린 신고를 가진 청크 수) · 중복 방지 키는 **(청크, 신고자, 대화)**(`guest-identity=shared` 면 전 방문자가 같은 id 라 청크당 한 명만 신고할 수 있게 되기 때문) · "수정됨/삭제됨"은 저장하지 않고 조회 시점에 파생(`chunk_hash` 대조) · 관리자 조치는 판정만 · 신고자 알림은 범위 밖. **두 동작의 경계는 라벨이 말한다** — *제거*(「이 답변과 상관없는 청크」, 참여도 0%일 때만 노출)와 *신고*(「내용이 틀렸거나 업데이트가 필요함」, 항상 노출); 사유 코드에 "질문과 무관"을 두면 표시 전용 동작이 관리자 대기열로 흘러든다. **계획대로 되지 않은 지점**: `fragments/message-assistant.html` 만 `data-chunk-id` 를 빠뜨려 논스트리밍 폴백에서 출처를 눌러도 모달이 안 열렸다(`ChatControllerHtmxTest` 가 고정) · `AdminController` 생성자에 서비스를 더하면 기존 WebMvc 테스트 둘의 컨텍스트가 안 뜬다 · `vec_document_chunks` 가 없는 배포에서는 쿼리 자체가 예외가 된다(테이블 존재 확인 후 조회).

**열린 항목**: (a) 문서 단위 묶음 보기(한 문서에 신고가 흩어지면 "다시 인덱싱"이 진짜 조치일 수 있다 — 그런 패턴이 보일 때) · (b) 신고를 검색 품질 지표로 쓸 것인가(골든셋의 "사람이 표시한 오답 청크" 라벨) · (c) 개별 신고 반려(그룹 단위 동선이 기본이어야 한다).

---

### 10.15 질문 추천 개선 ✅ 완료 (2026-09-21)

> **결정 근거와 틀렸던 지점** → [PITFALLS.md](PITFALLS.md#servicequestionreuseservicejava) · [QuestionKeywords](PITFALLS.md#servicequestionkeywordsjava) · [QuestionReuseRepository](PITFALLS.md#repositoryquestionreuserepositoryjava)

§6.23 의 추천·재사용에 남아 있던 헛클릭과 낭비를 걷어냈다. ① 재사용 턴이 자리표시자로 저장돼 표기·프리필·이력 렌더가 원본과 어긋났다 → 원본 턴의 모양을 복사. ② 좋아요한 Direct 턴은 근거 청크가 없어 검증을 통과한 적이 없었다 → 후보에서 제외. ③ 검증에 떨어질 것이 확정된 행이 `LIMIT` 창을 차지하고 키 입력마다 쿼리 둘로 재확인됐다 → 활성 출처가 없는 턴은 SQL 이 미리 빼고(`HAS_ACTIVE_SOURCE_PREDICATE`) 해시 대조에서만 드러나는 실패는 10분 부정 캐시. ④ 문장 통째 부분 일치는 어순·조사가 달라지면 못 찾았다 → `QuestionKeywords` 가 의문·기능어·조사·어미를 걷어낸 내용어(최대 6개)를 전부(AND) 부분 일치. 상한 12 → 20.

**지켜야 할 결정**: 접미사 절단은 언제나 접두사를 남긴다(한 글자 조사는 2음절 어간이 남을 때만 — `경로·결과·추가·정의` 보호, 어미 뒤에 한 음절만 남으면 토큰을 버린다) · 부정 캐시는 TTL 이지 영구 표시가 아니다(재승인이면 같은 해시가 돌아온다) · 사전 필터는 `validateTurn()` 보다 엄격하지 않아야 하고 재사용 조회에는 걸지 않는다 · **범위는 `shared` 고정이며 HTTP 로 받지 않는다**(`scope` 파라미터를 선언만 하고 읽지 않아 `?scope=me` 가 동작한다고 믿게 했다 — "내 질문만"이 성립하려면 화면 토글과 함께 기본 배포의 공유 게스트 id 부터 풀어야 한다; 출처 구분은 항목마다 붙는 `origin` 이 한다. `Scope.ME`/`meOnly` 축은 2026-10-12 에 걷어냈다). **열린 항목**: AND 매칭이라 키워드 하나가 저장 질문에 없으면 못 찾는다(용언 활용·동의어 — 완화는 실사용 관찰 뒤) · 불용어·조사·어미 목록은 `QuestionKeywords` 상수 하나다.

---

## 11. 리스크 및 이슈

> 해소·완화가 끝난 리스크(BCrypt 부하, HTMX + CSRF 헤더, IDOR, Chroma 컬렉션명 길이, Flyway SQLite 호환성, 세션 쿠키/XSS, path traversal, sqlite-vec 차원·바이너리·`searchBatch`·Spring AI 미지원, 태그 필터 과소, 프리릴리즈 초기화)는 걷었다 — 근거는 각 §과 PITFALLS. 아래는 **아직 살아 있는** 것만.

| 리스크 | 심각도 | 현황·완화 |
|--------|--------|----------|
| SQLite write 락 — 로그인 실패 카운트 UPDATE 와 채팅 INSERT 충돌 · vec0 대규모 `add()` 의 write 홀딩 | 중 | `busy_timeout=5000`(JDBC URL 파라미터, CLAUDE.md Key Constraints), 풀=1. 대규모 인덱싱 중 채팅 지연이 **실측**되면 분리를 재검토(부록 결정표) |
| 모바일 키보드 가림 | 중 | `100dvh` 로 레이아웃 고정(`app.css`). ⚠️ `visualViewport` 기반 입력창 보정은 **미구현** — 소프트 키보드 출현 시 하단 고정은 실기기 검증 필요(대시보드 "Phase 2 남은 실기기 검증 2건") |
| HTTPS 인증서 갱신 실패 | 중 | Caddy 자동 갱신. 만료 30일 전 알림 모니터링은 **미착수**(대시보드 운영 준비 잔여) |

---

## 11.1 Spring AI 2.0 업그레이드 & Chroma 버전 정책 ✅ 업그레이드 완료 (Chroma 1.1.x 검증만 남음)

**Chroma v2 API 는 이미 적용된 상태다.** Spring AI 의 `ChromaApi` 는 v2 경로만 호출하므로 이 앱은 v1 전용 Chroma 서버(0.5.x 이하)에서는 동작하지 않는다. 남아 있던 v1 잔재는 배포 설정이었고 조치 완료: 헬스체크 `/api/v2/heartbeat`, 이미지 태그 `1.0.21` 고정.

| 항목 | 내용 | 상태 |
|------|------|------|
| Chroma 태그 고정 | `:latest` → `1.0.21`, 헬스체크 v2 경로 | ✅ 완료 |
| Chroma 1.1.x 검증 | `ChromaApi` 가 쓰는 v2 엔드포인트 스키마가 그대로인지 실기동 확인 후 태그 상향 | 🔜 |
| Spring AI 2.0 업그레이드 | Spring Boot 4.1 · Spring AI 2.0. OpenAI 모듈이 SDK 기반이라 LLM 계층을 다시 썼다 — 결정 근거·실측은 [PITFALLS](PITFALLS.md#spring-boot-4--spring-ai-20-마이그레이션-2026-11-05) | ✅ 2026-11-05 — 전체 테스트 + sqlite-vec/로컬 LLM 실기동. **Chroma 백엔드 실기동은 미확인**(`ChromaApi`/`ChromaVectorStore` 빌더 시그니처와 `upsertEmbeddings()`·`QueryRequest.Include` 를 직접 호출하는 `ChromaConfig`·`VectorStoreRegistry`·`ChromaVectorStoreProvider` 가 대상) |

> 자동설정 exclude 와 Jackson 3·`OpenAiChatOptions` 불변 등 2.0 의 주의점은 CLAUDE.md Conventions·Key Constraints 에 있다.

---

## 12. 의존성 · DB 스키마 — 단일 출처는 다른 곳이다

의존성 이력과 설정 덤프는 **pom.xml/application.properties 가 단일 출처**이고, DB 스키마(테이블·컬럼·Flyway 규약·baseline)는 **[DATABASE.md](DATABASE.md)** 가 단일 출처다(§2.1 이 "새 테이블·컬럼·인덱스는 `V5` 이후 SQL 마이그레이션으로"를 다룬다). 이 파일에 복제해 두면 드리프트만 생겨 걷었다. 코드에서 바로 확인 안 되는 사실만 남긴다:

- **`flyway-database-sqlite` 는 불필요** — SQLite 마이그레이션 지원은 `flyway-core` 에 내장돼 있다.
- **`app.security.*` 외부화는 미착수** — BCrypt cost(12)는 `SecurityConfig.java`, 로그인 잠금(5회/15분)은 `AuthEventListener.java`(`MAX_ATTEMPTS`/`LOCK_MINUTES`)에 상수로 있다. 외부화하려면 `AppProperties` 에 `SecurityConfig` record + `securitySafe()` 가드를 추가하고 두 지점을 프로퍼티 참조로 바꾼다.
- 운영 테이블에 새 테이블·컬럼을 더하면 DATABASE.md 의 표도 함께 고친다.

---

## 부록 — 결정 사항 한눈에 보기

| 주제 | 결정 | 대안 (보류) |
|------|------|-----------|
| 인증 방식 | HttpSession + Spring Security 폼 로그인 | JWT (다중 서버 시 도입) |
| 비밀번호 해시 | BCrypt cost=12 | Argon2 (CPU/메모리 변수 더 많음, 부하 테스트 후 결정) |
| TLS 종료 | Caddy 리버스 프록시 | Spring 직접 TLS, Cloudflare |
| DB | SQLite 유지 (WAL+busy_timeout) | PostgreSQL (한계 신호 발생 시) |
| SQLite 파일 구성 | 단일 파일 `memory.db` (2026-09-28, Step 5.10 환원) | 운영/벡터 파일 분리 — Step 5.10 이 시도했으나 배선 사고로 실효된 적 없음. 재개 신호: 대량 인덱싱 중 채팅 지연이 **실측**될 때. 선행: `QuestionReuseRepository.findSourcePreviewRows()` 교차 조인 해소(ATTACH/2단계 조회) + 기존 배포 운영 테이블 이전 |
| 마이그레이션 | Flyway + ANSI SQL | Liquibase |
| 운영 테이블 스키마 | Flyway 일원화 (2026-09-29) — V4(Java)가 옛 런타임 DDL 상태들을 한 모양으로 모으고, 이후 변경은 V5+ SQL 파일 ([DATABASE.md](DATABASE.md) §2.1) | 저장소별 런타임 멱등 DDL(`CREATE TABLE IF NOT EXISTS` + 방어적 `ALTER`) — 스키마가 V1–V3 와 저장소 11곳에 흩어지고 저장소 테스트가 손으로 만든 축소 스키마로 돌았다 |
| 멀티테넌시 | Row-level + 사용자별 Chroma 컬렉션 | Schema/DB per tenant |
| 세션 저장소 | 인메모리 (단일 인스턴스) | Spring Session + Redis (스케일아웃 시) |
| CSRF | Spring Security 기본 + HTMX 자동 주입 | Double-submit cookie |
| Rate limit | Bucket4j 인메모리 | Redis 백엔드 (스케일아웃 시) |
| 모바일 | HTMX + Bootstrap Offcanvas + PWA | React Native 네이티브 앱 |
| 소셜 로그인 | Phase 4 (필요해질 때) | Phase 1 동시 — 복잡도 ↑ |
| Vector Store | `app.vectorstore.type` 런타임 선택 (기본 `chroma`) | Spring AI 공식 sqlite-vec 지원 시 단순화 가능 |
| sqlite-vec 스키마 | 앱 시작 시 동적 DDL (`IF NOT EXISTS`) — 차원수 파라미터화 | Flyway 마이그레이션 — vec0 DDL에 차원 하드코딩 필요해 부적합 |
| sqlite-vec searchBatch | 임베딩 배치(1 HTTP) + JDBC 루프(N 쿼리) | 단일 CTE 쿼리 — vec0 API 제약으로 현재 불가 |
| Agent 구조 (§6.33) | 턴당 Tool 선택 1회 + 기존 `ANSWER → CRITIC` 유지, 영향도·유지보수는 고정 워크플로 | `AgentGraph` 를 자유 ReAct 로 교체 — 스트리밍·사다리·검증을 다시 만들어야 한다 |
| 재검색 판단 (§6.33) | 리랭커 점수로 코드가 정하고 LLM 은 새 검색어만 만든다 | LLM 자율 Tool 루프 — 재개 신호: Tool 을 이어 부르는 모델이 측정으로 앞설 때 |
| 리랭커 (§6.33) | `llama-server --reranking` 교차 인코더 | 기존 LLM 리랭커 — 느리고 점수가 없다 |
| MCP 배치 (§6.33) | 내부는 직접 호출, 같은 Tool 을 같은 프로세스의 MCP Server 로 공개 | 내부도 MCP 경유 · 별도 프로세스 — HTTP 왕복과 신원 재전달, SQLite pool=1 |
