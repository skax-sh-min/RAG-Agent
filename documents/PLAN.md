# RAG-Agent 온라인 확장 개발 계획

> Java 개발자 관점 · Spring Boot 3.5 + Spring AI 1.1 + Java 21 · 작성일 2026-05-11  
> **개발 기준 문서**: 이 파일(documents/PLAN.md)이 마스터. `documents/refactoring/18-extension-roadmap.md`는 각 항목의 기술 레퍼런스.

---

## 📊 전체 현황 대시보드

> 완료/미착수를 한눈에 보도록 상단 대시보드를 신설했다.

### ✅ 완료 — Phase 1 · 2 · 5 · 6 전체, Phase 3 · 7 대부분

| Phase | 완료 항목 | 상세 |
|---|---|---|
| **Phase 1** — 보안 기반 | Step 1.1~1.6 전체(Caddy·Flyway·Spring Security·멀티유저 격리·CSRF·로그인/회원가입 UI) + `app.auth.enabled` no-auth 토글 | §4 |
| **Phase 2** — 모바일 UI | 반응형 레이아웃(Offcanvas) · PWA(manifest/SW/오프라인) · 다크모드·접근성 · 전체 286 tests 검증 | §5 |
| **Phase 3** — 운영 견고화 (12개 항목) | §6.1 Rate limit · §6.2 업로드 검증(매직바이트, 쿼터는 미착수) · §6.3 예외처리 · §6.4 감사로그 · §6.5 임베딩 사용량 분리 · §6.6 비활성 프로바이더 표시 · §6.7 orphan 기록 삭제 · §6.8 피드백 기반 컨텍스트 제외 · §6.9 요약 선계산 · §6.10 백그라운드 사용량 분리 · §6.11 컨텍스트 예산 정합성 · §6.12 다중 사용자 동시 LLM 처리(동시성 게이트+429 백프레셔+single-flight+서킷브레이커 완화+로드밸런싱) · §6.13 설정 페이지(LLM/RAG 조회+핫 수정 오버라이드 레이어) · §6.14 핵심 채팅 경로 추적 | §6 |
| **Phase 5** — Vector Store | Step 5.1~5.10 전체(Chroma↔sqlite-vec 런타임 전환, 관리자 페이지, 태그 검색, 운영/벡터 DB 분리) | §8 |
| **Phase 6** — 폐쇄망/노-도커 | G1~G5(키리스 LOCAL·차원 외부화·라우팅 외부화·런북·무외부호출 인수) | §9 |
| **Phase 7-A** — 검색 빠른 승리 | §10.2 가중 RRF(벡터축 그룹 정규화 + 키워드축 가중치/k 외부화) · §10.3 쿼리 임베딩 캐시(Caffeine, cache→tracking→delegate 데코레이터) | §10 |
| **Phase 7-B** — Contextual Retrieval + 임베딩 입력 정규화 | §10.1 청크 맥락 헤더(구조적+LLM) 임베딩/FTS 입력에 prepend, `context:` 사용량 분리 · §10.1-보완 마크다운 장식 제거 정규화(임베딩/FTS/답변프롬프트 3곳 공유), 저장·표시 텍스트는 원문 불변 | §10 |
| **Phase 7-C** — 한국어 FTS 토크나이저 | §10.4 `chunk_fts`를 `unicode61`→`trigram` 전환(무손실 자동 재구축), 어간-활용형/코드 부분열 매칭 개선. 2글자 단어는 단독 검색 시 실제 BM25 순위를 얻지 못한다는 실측 트레이드오프 확인(벡터 축은 무관) — §10.7.3에서 LIKE 존재-신호 폴백으로 완화 | §10 |
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
| 6 | **Phase 7-E 검색·인덱싱 성능/메모리 최적화 제안**(§10.7~10.10) | §10.7.1~10.7.4·§10.8 전체(10.8.1~10.8.5)·§10.9.2·§10.9.4(2026-07-15) 완료. 나머지 §10.9.1(즉효 저리스크, Chroma 응답에서 미사용 임베딩 제외)·§10.9.3(스트리밍 삽입) 미착수 |

> **Phase 7-A 완료**: §10.2 가중 RRF + §10.3 쿼리 임베딩 캐시. 상세는 아래 §10.2·§10.3 본문 참조.
> **Phase 7-B 완료**: §10.1 Contextual Retrieval + §10.1-보완 임베딩 입력 정규화. 상세는 아래 §10.1 본문 참조. 재인덱싱은 운영 단계에서 별도 수행.
> **Phase 7-C 완료**: §10.4 한국어 FTS 트라이그램 토크나이저. 상세는 아래 §10.4 본문 참조. Phase 7-A~C(§10.1~10.4) 전체 완료 — 하이브리드 기본 활성화(`app.search-hybrid-enabled=true`) 전환 여부는 별도 후속 판단으로 남김.
> **Phase 7-E 제안 추가**: 검색·인덱싱 파이프라인 재검토로 정확도·속도·메모리 최적화 13건 도출, 일부 착수. 상세는 §10.7~10.10 참조.
> **§6.12 완료**: 다중 사용자 동시 LLM 요청 처리 — 채팅 경로 무제한 동시성(인덱싱만 세마포어 존재) → 슬롯 초과 시 429→서킷브레이커 전면차단·타임아웃 폭주 위험이었던 문제를 5단계로 해결. ① 프로바이더별 동시성 세마포어(`LlmRouter.acquirePermit`/`executeGated`, 채팅/질의 경로 전체 적용) ② 대기상한+429 백프레셔(`LlmBackpressureException`) ③ `CachingEmbeddingModel` in-flight single-flight(동일 텍스트 동시 요청 thundering herd 제거) ④ 폴백 없는 유일 프로바이더의 서킷브레이커 단축 차단(`blockForOverload`, 다중 분 단위 전면 다운 방지) ⑤ 동일 role·priority 프로바이더 로드밸런싱(least-in-flight, 처리량 수평 확장). 인덱싱/백그라운드 경로는 의도적으로 미적용(회귀 방지, 자체 세마포어 유지). 상세는 §6.12 본문 참조.
> **§6.13 완료**: `/settings` LLM/RAG 설정 조회 + 핫 수정 페이지. 상세는 §6.13 본문 참조.
> **§6.16.1 완료**: 채팅 스트리밍 중지 + 업로드/동기화 취소 버튼.
> **§6.17 완료**: 문서 관리·Admin 관리 전용 인증(B안) — `app.auth.management-only`. 상세는 아래 §6.17 본문 참조. (A) 전체 인증 모드는 §6.19와 함께 후속(멀티유저 활성화 시) 유지.
> **§6.18 추가 (낮은 우선순위)**: Direct(meta) 응답 전용 temperature를 RAG 응답과 분리해 0.0~0.2(기본 0.1) 범위로 화면에서 조정 가능하게. 조사 중 temperature가 현재 어디에서도 실제로 설정 가능하지 않다는(하드코딩) 선행 이슈를 발견 — 상세는 §6.18 본문 참조.
> **§10.7.1 완료**: 리랭커 프리뷰를 200→500자로 확장 + 구조적 컨텍스트 헤더 추가. 상세는 아래 §10.7.1 본문 참조.
> **§10.7.2 완료**: `SEARCH_HYBRID_ENABLED` 기본값을 false→true로 전환(평가 하네스 부재로 무측정 트라이얼, 사용자 승인 후 진행). 상세는 아래 §10.7.2 본문 참조.
> **§10.7.3 완료**: 3자 미만(trigram 최소 단위 미만) 질의어에 `LIKE` 보조 스캔 추가 — "오류"·"문서" 같은 2글자 단독 질의의 BM25측 0건 반환 해소. 상세는 아래 §10.7.3 본문 참조.
> **§10.7.4 완료**: 유사도 임계값(`similarity-threshold>0`) 활성 시 Chroma/sqlite-vec KNN 조회 k를 topK의 2배로 과조회 후 topK로 재절단 — 필터 후 후보 풀이 topK 미만으로 조용히 축소되던 문제 해소. 상세는 아래 §10.7.4 본문 참조.
> **§10.8.1 완료**: MultiQuery 확장 최소 길이 기본값 0→15 상향 + 원본 질의 검색을 확장 LLM 호출과 병렬 실행(가상 스레드) — 확장 대기시간에 원본 검색 지연이 가려짐. 상세는 아래 §10.8.1 본문 참조.
> **§10.8.2 완료**: 청크 N개(기본 4)를 번호가 매겨진 하나의 LLM 호출로 묶어 키워드/맥락을 추출(`app.indexing.keyword-batch-size`) — 왕복 횟수가 `ceil(청크수/N)`로 감소, 파싱/호출 실패 시 청크별 재시도 없이 곧장 TF 폴백. 상세는 아래 §10.8.2 본문 참조.
> **§10.8.3 완료**: `SqliteVecVectorStoreProvider.add()`의 `vec_embeddings`+`vec_document_chunks` 배치 삽입 2개를 하나의 트랜잭션으로 결합(실 DataSource에서만 활성). 부수로 중간 실패 시 부분 커밋(고아 행) 가능성도 제거. 상세는 아래 §10.8.3 본문 참조.
> **§10.8.4 완료**: `syncDirectory()` 1단계에서 계산한 sha256을 `IndexRequest`에 실어 2단계 `index()`에 전달 — 동기화 대상 파일마다 있던 이중 해싱 제거. 상세는 아래 §10.8.4 본문 참조.
> **§10.8.5 완료**: `SearchTextBuilder.precompute()`로 임베딩+FTS 파생 텍스트를 청크당 1회만 계산해 transient 메타키(`SEARCH_TEXT`)에 저장, `build()`가 있으면 재사용 — 벡터 스토어/FTS 호출부는 무수정. 상세는 아래 §10.8.5 본문 참조.
> **§10.9.2 완료**: sqlite-vec 벡터 직렬화를 JSON 텍스트에서 little-endian float32 BLOB(`toVectorBlob()`)로 전환 — 삽입/KNN 질의 양쪽 적용, 기존 데이터와 호환(백필 불필요). 상세는 아래 §10.9.2 본문 참조.
> **§10.9.4 완료**: `CachingEmbeddingModel.unwrapForIndexing()`으로 인덱싱 경로(`add()`)가 질의 임베딩 캐시를 우회 — 대량 청크 인덱싱이 더 이상 직전 검색 질의의 캐시를 밀어내지 않음. 캐시 키도 원문 대신 SHA-256 해시로 축소. 상세는 아래 §10.9.4 본문 참조.

**🟣 후속 — 멀티유저(`auth.enabled=true`) 활성화 시에만 착수**

| 순위 | 항목 | 트리거 |
|---|---|---|
| 1 | **§6.19 보안 하드닝** — API CSRF/세션 혼용(6.19.1) · `/admin/**` ROLE_ADMIN 게이트(6.19.2) · XFF 무검증 rate limit(6.19.3) | **auth 모드 여는 시점에 반드시 선행**(게이트) — no-auth엔 노출면 없음 |
| 2 | **§6.20 사용자별 LLM 토큰 쿼터** | 실사용자가 여럿 생겨 사용량 격리가 필요해질 때 |
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

**🔵 미착수** — 사용자별 누적 용량 쿼터 → §6.15로 이관·구체화(현재 `storage_used_bytes` 컬럼·쿼터 로직·`app.upload-quota` 프로퍼티 모두 없음).

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

### 6.12 다중 사용자 동시 LLM 요청 처리 — 동시성 제어 + 처리량 확장 ✅ 완료

**배경**: 채팅(질의) 경로에 앱 레벨 동시성 제한이 전혀 없었다(세마포어는 인덱싱 경로에만 존재). 질문 1개당 LLM 호출이 5회(CLASSIFIER·MultiQuery·ANSWER×2·CRITIC)라, 여러 사용자가 위상이 겹치게 도착하면 매 단계가 동시 슬롯을 초과 — 폴백 없는 유일 프로바이더는 429/503 시 서킷브레이커가 전면 차단(수 분)돼 채팅 전체가 다운됐고, 큐 대기가 180초 read-timeout을 넘기면 타임아웃, 동일 질문 동시 요청도 인플라이트 병합이 없어 중복 계산(thundering herd)됐다.

**개선안 (전체 완료)**:
1. **프로바이더별 동시성 세마포어** — `LlmRouter.acquirePermit()`/`executeGated()`를 채팅·질의 경로 전체(CLASSIFIER·ANSWER·DUAL·DirectAnswer·재랭킹·MultiQuery — 후자는 신규 `ConcurrencyLimitingChatModel` 데코레이터로 래핑)에 적용, 크기는 `app.llm.providers[].concurrency`(기본 3, 서버 실제 `--parallel`에 맞춤). 인덱싱/백그라운드 경로는 기존 세마포어를 그대로 두고 이중 게이팅 안 함.
2. **대기 상한 + 429 백프레셔** — `app.llm.permit-wait-timeout-seconds`(기본 20초) 대기 후 실패 시 신규 `LlmBackpressureException`(429, `RAG-LLM-002`)을 즉시 반환(서킷브레이커·재시도 없음, `Retry-After` 자동 부착). SSE는 에러 이벤트로 우아하게 스트림 종료.
3. **임베딩 in-flight single-flight** — `CachingEmbeddingModel`이 `ConcurrentHashMap<key, CompletableFuture>`로 동일 텍스트의 동시 요청을 병합(owner 1건만 실제 호출, 나머지는 join). 완전 동일 텍스트만 병합되며 근사 질문은 여전히 미스(§10.5 영역).
4. **서킷브레이커 연쇄 방지** — 폴백 있는 프로바이더는 기존과 동일하게 정상 차단, **폴백 없는 유일 프로바이더**는 429/503(명시적 `Retry-After` 없을 때) 차단을 30초로 단축해 전면 다운을 완화.
5. **동일 우선순위 로드밸런싱** — 같은 role·priority로 프로바이더를 추가 등록하면 `findFirst()`가 least-in-flight(잔여 permit 최다)로 자동 분산 — 코드 변경 없이 `application.properties`만으로 수평 확장.

**완료 기준 — 충족**: 초과 부하는 대기 상한 내 순차 처리 후 429(Retry-After)로 전환, 동일 질문 중복 계산 제거(`CachingEmbeddingModelTest` 동시성 테스트), 유일 프로바이더 장애 시 전면 다운 완화(`LlmRouterTest`), 동일 role 프로바이더 2대 등록 시 실제 분산 확인. 전체 689 tests(회귀 0). 신규 `LlmBackpressureException`/`ConcurrencyLimitingChatModel`, `LlmRouterTest`/`CachingEmbeddingModelTest`/`ConcurrencyLimitingChatModelTest`에 테스트 추가.

**비고**: 근사(동일하지 않은) 질문의 중복 계산까지 없애려면 §10.5(시맨틱 응답 캐시, 현재 보류)가 별도 후보다.

---

### 6.13 설정 페이지 — LLM/RAG 옵션 조회·부분 수정 ✅ 완료 (2026-07-14)

**배경**: 모든 설정이 `application.properties`+환경변수로만 존재해, 운영자가 현재 유효값을 한 화면에서 확인하거나 재기동 없이 조정할 방법이 없었다.

**구현**: 신규 `GET /settings`(게스트 조회 가능, 편집 컨트롤은 `isAdmin` 게이트) — LLM 프로바이더·라우팅, 임베딩, RAG 검색(`app.search-*`) 튜닝값을 그룹별로 표시. 검색 튜닝 7개 값(similarity-threshold, RRF weight/k, candidate-multiplier, tag-candidate-multiplier, retry-escalate, multiquery-min-length)과 기본 라우팅 모드는 SQLite `settings_override` 테이블(오버라이드 우선 → 없으면 프로퍼티 기본값) 기반 오버레이 레이어(`AppProperties`의 정적 `OverrideSource`)로 **재기동 없이 즉시 반영** — `RetrievalService`가 생성자 캐싱을 버리고 매 호출 `props.xxxSafe()`로 재조회하도록 변경해야 실제로 반영됨. `rerank-enabled`/`hybrid-enabled`/`vectorstore.type`/`embedding.dimensions`/`auth.enabled` 등 빈 생성 시점에 고정되는 값은 "재기동 필요" 배지로 조회만 노출. temperature/max-tokens는 §6.18 선행 필요라 조회 전용(당시 `LlmConfig`가 빈 생성 시점에 하드코딩 중이었음). 수정 `POST /admin/settings/update|reset`은 `/admin/**` 기존 인가(§6.17 ROLE_ADMIN)를 그대로 상속하고, 변경은 `AuditLogger`에 기록.

**완료 기준 — 충족**: 핫 수정 항목이 재기동 없이 다음 요청부터 반영, 재기동 필요 항목은 수정 UI 잠금, 오버라이드가 재기동 후에도 유지되고(`settings_override`) 삭제 시 기본값 복귀, 변경 이력이 감사 로그에 남음(회귀 0). 신규 테스트 19개(`AppPropertiesOverrideTest`/`SettingsServiceTest`/`SettingsControllerTest`/`SettingsControllerRenderTest`), 전체 708 tests BUILD SUCCESS.

---

### 6.14 LLM 사용량 — 핵심 채팅 경로 추적 확장 ✅ 완료 (2026-07-06)

§6.10 조사 중 `AnswerService`/`DirectAnswerService`/`ClassifierService`/`RerankerService`/`VisionDescriptionService`/`ImageTypeClassifier`/`RetrievalService`의 `MultiQueryExpander` 7곳이 `LlmRouter`를 거치지 않는 직접 `ChatClient` 호출이라 실제 채팅 사용량이 `/llm-usage`에 전혀 안 잡히던(embed:만 증가) 문제를 사용자가 실사용 중 재현·보고(2026-07-06).

**1차(핵심 3곳, 당일 즉시 수정)**: `ClassifierService`/`AnswerService`/`DirectAnswerService`의 블로킹 호출을 `executeWithTracking()`(실사용량)으로, 스트리밍 분기는 신규 `LlmRouter.recordApproxUsage()`(§6.5와 동일한 chars/4 근사)로 교체. LM Studio로 Direct+RAG 질문(총 7회 호출) 전송 → `local` 프로바이더가 정상 누적됨을 실사용 검증.

**2차(잔여 4곳, 같은 날 마저 정리)**: `RerankerService`/`ImageTypeClassifier`/`VisionDescriptionService`는 동일하게 `executeWithTracking()`으로 교체. `MultiQueryExpander`(Spring AI 프레임워크 유틸, 내부에서 자체 `ChatClient` 구성이라 가로챌 수 없음)는 `TrackingEmbeddingModel`과 동일한 데코레이터 패턴의 신규 `TrackingChatModel`을 만들어 주입.

관련 테스트 9개 파일 갱신/신설(기존 테스트 전무했던 2곳 포함), 전체 466 tests BUILD SUCCESS(회귀 0).

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
- **현재 상태**: 채팅 SSE 스트리밍(`chat-stream.js`)과 문서 인덱싱 진행(`documents.html`) 모두 시작 후 사용자가 멈출 방법이 없다 — 잘못 보낸 긴 질문이나 대용량 인덱싱을 끝까지 기다려야 하고, 서버 자원도 계속 소모된다. `StreamingAgentService`는 이미 `emitter.onError/onTimeout`에서 워커를 `interrupt()`하므로 클라이언트가 SSE를 닫으면 중단 훅은 존재한다.
- **개선안**: 스트리밍 답변 버블에 "중지" 버튼 → `EventSource.close()` + (필요 시) 서버에 취소 신호. 인덱싱은 업로드 행별 취소가 이상적이나, 최소한 진행 중 SSE 구독 해제 + 서버측 가상 스레드 `interrupt` 경로 정리. 부분 답변은 이미 에러 시 저장 로직(`StreamingAgentService`)이 있어 재사용 가능.
- **완료 기준**: 사용자가 스트리밍/인덱싱을 즉시 중단할 수 있고, 서버 워커도 실제로 종료된다(좀비 스레드 없음).

**구현**: 채팅은 `chat-stream.js`(fetch+ReadableStream)의 `AbortController`로 중지 — 전송 버튼이 스트리밍 중 "중지" 버튼으로 전환, 중단 시점까지 답변은 보존하고 "사용자가 중단함" 표시만 추가(서버는 기존 `emitter.onError` 훅이 워커를 함께 종료). 인덱싱은 신규 `IndexingCancelledException` + `POST /ui/documents/progress/{taskId}/cancel`(taskId→워커 `Thread` 맵 관리, `interrupt()` 후 즉시 terminal `cancelled` 이벤트 publish). **핵심 난이도**: `KeywordExtractor.enrichParallel()`/`DocumentIndexer.syncDirectory()`의 병렬 대기가 non-interruptible `CompletableFuture.join()`을 쓰고 있어 `interrupt()`가 조용히 무시되던 버그를 `.get()`(interruptible)으로 교체 + `InterruptedException` catch 시 executor `shutdownNow()`로 실제 취소되도록 수정. 취소 시점까지 완료된 파일은 레지스트리에 보존. `documents.html`은 업로드 행 취소 버튼(taskId 확정 전 `xhr.abort()`, 이후 `POST .../cancel`)을 추가.

신규 테스트 8개 + 브라우저 실사용 검증(중지·취소 버튼 동작 확인). 전체 518 tests BUILD SUCCESS(회귀 0).

**6.16.2 계정 잠금 상태 피드백 부재**
- **현재 상태**: 로그인 5회 실패 시 15분 잠금(`AuthEventListener`)되지만, 잠긴 뒤에도 로그인 화면은 일반 "이메일 또는 비밀번호가 올바르지 않습니다" 문구만 보여준다(`formLogin.failureUrl("/login?error")` 고정). 사용자는 자신이 잠겼는지, 언제 풀리는지 알 수 없다(문구가 "5회 실패 시 15분 잠금"을 안내는 하지만 현재 잠금 상태/해제 시각은 아님).
- **개선안**: `LockedException` 분기를 별도 실패 핸들러로 잡아 "N분 후 해제" 메시지 전달(`locked_until` 조회). 잠금 남은 시간은 이미 `SqliteUserDetailsService`가 관리 중.
- **완료 기준**: 잠긴 계정 로그인 시 잠금 상태 + 해제 예정 시각이 구분되어 표시된다.

---

### 6.17 문서 관리·Admin 접근 인증 필수화 + 역할 기반 화면 분기 ✅ 완료 (B안)

**배경**: no-auth 기본 배포에서 `/documents` 쓰기와 `/admin`(청크 열람·수정·삭제)이 로그인 없이 열려 있었다.

**채택안 — (B) 관리 전용 인증**: 채팅·조회는 no-auth로 열어두고 `/documents`(쓰기)·`/admin/**`만 로그인 요구. single-operator 배포에도 즉시 적용 가능하다는 게 (A) 전체 인증 모드(§6.19 하드닝 선행 필요, 멀티유저 전용) 대비 채택 이유.

**구현**: `app.auth.management-only=true`(`enabled=true`면 자동 `false` 정규화) 신규 서브모드. `SecurityConfig` 3번째 필터 체인(`IF_REQUIRED` 세션 + 쿠키 CSRF — STATELESS로는 `formLogin()` 세션 지속이 불가능해 분리) + `/admin/**`·문서쓰기 5라우트를 `.hasRole("ADMIN")`으로 게이트(`.authenticated()`가 아닌 이유: `GUEST_PRINCIPAL`이 실제로는 인증된 `ROLE_USER`라 우회 가능해서, 필터 게이트 목록과 독립된 이중 방어). `NoAuthAutoLoginFilter`는 실로그인이 있으면 어떤 경로에서도 GUEST로 덮어쓰지 않음. `GlobalModelAdvice`의 `isAdmin`이 템플릿 역할 분기(업로드 카드·삭제 버튼·Admin 내비)를 구동 — management-only 모드에서만 실제 역할 반영, 나머지 모드는 무회귀. CSRF 활성화 부수 영향으로 `chat.html`/`auth/setup.html`/업로드 XHR 3곳 수정. `/api/v1/documents/**`는 curl 자동화 보존을 위해 의도적으로 게이트·CSRF 예외.

**완료 기준 — 충족**: 비로그인/비관리자 거부(`ManagementOnlyAuthorizationTest`, 세션 왕복 e2e), 관리자 로그인 시 UI 노출 + 게스트는 조회만(브라우저 검증), `management-only=false` 기본값은 기존과 100% 동일(전체 658 tests, 회귀 0). (A) 전체 인증 모드는 §6.19와 함께 후속.

**§6.19.2·§7.3와의 관계**: 이 절이 "무엇을 잠그고 누가 무엇을 보는가"의 상위 방향이고, §6.19.2(`/admin/**` ROLE_ADMIN 인가, (A) 전용)와 §7.3(관리자 페이지 기능 확장)이 구현 조각이다. B안은 §6.19.2 없이 자체 게이트로 완료됐으며, §6.19.2는 (A)를 열 때만 별도 필요.

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

G1~G4 코드/문서 완료. G5는 라우팅 계층 "외부 무선택"을 `LlmConfigTest.airGappedNeverRoutesToExternal`로 결정적 검증. 전체 286 tests BUILD SUCCESS(sqlite 통합 2개는 vec0 바이너리 없을 때 skip). vec0 필요한 sqlite-vec 라이브 부팅·실소켓 0 측정은 운영 인수.

---

## 10. Phase 7 — 검색 품질·성능 고도화 ✅ 완료 (7-A · 7-B · 7-C)

> **배경 (2026-07-07 코드 확인)**: 검색 파이프라인(`RetrievalService`)은 이미 MultiQuery(원본+2) → 배치 임베딩 → 벡터 검색 → RRF 융합 → (옵션)하이브리드 BM25 → (옵션)LLM 리랭크 → 태그 필터로 잘 구성돼 있다. 아래는 "빠진 것"이 아니라 **현 구조 위에서 정확도/성능을 끌어올리는 증분 개선**이며, 자바 관점의 난이도·회귀 리스크와 함께 우선순위화했다. 자체 검색 품질 평가 세트가 없으므로 각 항목의 효과는 도입 후 정성/정량 측정으로 검증하는 것을 전제로 한다.

### 10.1 Contextual Retrieval — 청크 맥락 주입 (정확도 ROI 1순위) ✅ 완료

**배경**: 청크가 자기 텍스트만 임베딩되어 "이 청크가 어느 문서·섹션의 무엇인지"가 벡터에 반영되지 않아 대명사·표·코드 조각의 recall이 낮았다. Spring AI 1.1의 쿼리 측 증강(`RetrievalAugmentationAdvisor` 등)은 인덱스 측 기법과 층이 달라 대체가 안 됨 — 커스텀 구현 필요.

**구현**: `KeywordExtractor`의 기존 키워드 추출 LLM 호출을 확장해 같은 호출에서 키워드+맥락(1~2문장)을 함께 받는다(왕복 추가 없음, `context:` 사용량 라벨로 분리 기록). 구조적 맥락(`{파일명} > {heading}`)은 LLM 없이 항상 결정적으로 계산되고, LLM 맥락은 그 위에 얹히며 실패 시 구조적 맥락으로 폴백. 맥락은 임베딩뿐 아니라 `chunk_fts`(BM25)에도 반영해 Contextual BM25 시너지를 얻는다(§10.2·§10.4와 결합 시 효과 증폭). 신규 `SearchTextBuilder`(`CHUNK_CONTEXT`+정규화 본문)를 임베딩·FTS 두 경로가 공유하며, `MetaKey.CHUNK_CONTEXT`는 검색 전용이라 영속 메타데이터·저장 텍스트에는 남지 않는다. Chroma는 `VectorStore.add()`(임베딩=저장 텍스트 강제)를 수동 임베딩+`upsertEmbeddings()`로 교체.

**10.1-보완 — 임베딩 입력 정규화**: 같은 재인덱싱 사이클에 묶어, 마크다운 장식 줄(구분선 등)은 통째로 제거하고 강조 마커(`**굵게**` 등)는 마커만 벗기고 내용은 보존 — 코드펜스·표는 무변형. **3계층 분리**: 저장·표시 텍스트(원문 그대로) ≠ 임베딩+FTS 입력(맥락+정규화) ≠ 답변 프롬프트 입력(정규화, 맥락 헤더는 제외). 청크 분할 경계는 원문 기준, 크기 예산 측정만 정규화 길이로 수행해 청크 수가 자연히 감소. 신규 순수 유틸 `MarkdownNoiseNormalizer`가 임베딩·FTS·답변 프롬프트 3곳에서 공유된다.

**효과**: 정확도 ROI 1순위(공개 벤치 기준 검색 실패율 대폭 감소). **완료 기준 — 충족**: 신규 청크에 맥락 반영, `context:` 사용량 분리 집계, LLM 실패 시 구조적 맥락 폴백, 코드펜스 무변형(테스트로 고정), 청크 수 감소·원문 표시 불변 확인. 전체 565 tests BUILD SUCCESS(회귀 0). 기존 문서는 재인덱싱 필요.

### 10.2 가중 RRF (Weighted RRF) ✅ 완료

**배경**: `mergeRrf()`가 모든 후보에 동일 가중을 줘서, MultiQuery로 벡터 축이 3개인 반면 BM25 축은 1개라 정확 용어(에러코드·API명 등) 매칭이 구조적으로 저평가됐다.

**구현**: 벡터 축은 축 개수(1~3)와 무관하게 항상 `1/axisCount`로 그룹 정규화하고, 키워드(BM25) 축 가중치(`app.search-rrf-keyword-weight`, 기본 1.0)와 RRF 상수 k(`app.search-rrf-k`, 기본 60)를 외부화. 기존 2-인자 `mergeRrf()`는 벡터 전용 오버로드로 하위호환 유지. 하이브리드 기본 비활성이라 기본 배포는 회귀 0(정규화가 전체 벡터 축에 동일 상수를 곱하는 연산이라 순위 불변임을 수학적으로 보장).

**효과**: 하이브리드 검색의 완전 일치 용어 질의 정확도 상승(§10.4와 세트일 때 체감 큼). 신규 테스트 4개.

### 10.3 쿼리 임베딩 캐시 (성능, 저비용) ✅ 완료

**배경**: 검색마다 원본+확장 쿼리(최대 3개)를 매번 재임베딩해 반복·유사 질문에도 지연·토큰을 낭비했다. Spring AI 1.1엔 임베딩 캐시가 없어 직접 구현.

**구현**: 신규 `CachingEmbeddingModel`(`EmbeddingModel` 데코레이터, cache→tracking→delegate 합성, Caffeine — 신규 의존성 0, 기존 `RateLimitFilter`에서 이미 사용 중)이 정규화 쿼리+모델명을 키로 캐시. 캐시 히트는 delegate 호출 자체를 안 타 usage도 자동 미기록. 부분 히트는 미스분만 배치 재호출 후 병합. `app.search-query-embed-cache-enabled`(기본 true)/`-max-size`(500)/`-ttl-seconds`(600)로 외부화. 인덱싱 청크도 같은 캐시를 지나가지만 사이즈/TTL로 유계라 메모리 누수는 없음(캐시 이득만 없음).

**효과**: 반복 질의의 임베딩 왕복·토큰 절감(FAQ성 트래픽에서 큼). 신규 테스트 5개, 전체 513 tests BUILD SUCCESS(회귀 0).

### 10.4 한국어 FTS 토크나이저 (하이브리드 정확도) ✅ 완료

**배경**: `chunk_fts`가 `unicode61`(공백/구두점 분리)이라 한국어 조사·복합어 변형을 못 잡아 BM25 recall이 낮았다. 형태소 분석기(nori 등)는 Lucene/ES 전용이라 SQLite FTS5에 직접 못 쓰고 커스텀 C 확장은 폐쇄망 바이너리 조달 부담이 커 범위 밖 — 대신 SQLite 내장 `trigram`(부분 문자열 매칭, 외부 바이너리 0)을 채택.

**구현**: `chunk_fts` 토크나이저를 `unicode61`→`trigram`으로 전환. 기존 테이블은 `sqlite_master.sql` 원문에 `'trigram'` 포함 여부로 감지해 미감지 시 `INSERT...SELECT`로 무손실 자동 재구축(대상 테이블 토크나이저로 재토큰화되므로 `doc_tags`/`content`/`keywords` 손실 없음). `toMatchQuery()`의 최소 토큰 길이를 2→3자로 조정.

**실측 트레이드오프**: 어간이 3자 이상이면 활용형(예: "인덱싱"→"인덱싱됩니다")과 코드 부분열(예: "ERR45"→"ERR4521")까지 잘 찾지만, 2글자 한국어 단어("문서", "오류" 등)는 trigram이 생성되지 않아 단독 질의 시 진짜 BM25 순위 점수는 얻지 못한다(벡터 축은 무관하게 동작). §10.7.3에서 `LIKE` 폴백으로 존재 여부 기반 신호(순위 없음, MATCH 결과 뒤에 배치)를 보충해 0건 반환은 해소했다. 신규 테스트 3개(어간-활용형 매칭·3자 미만 드롭·무손실 마이그레이션), 전체 568 tests BUILD SUCCESS(회귀 0).

### 10.5 검토 후 제외 (Phase 7-D 취소)

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
- **Phase 7-A (빠른 승리) ✅ 완료**: §10.2 가중 RRF + §10.3 임베딩 캐시 — 코드 변경 작고 회귀 리스크 최소, 즉시 체감. 이후 큰 작업의 효과를 측정할 baseline 확보.
- **Phase 7-B (정확도 본편) ✅ 완료**: §10.1 Contextual Retrieval — `KeywordExtractor` 파이프라인에 얹어(구조적 맥락 baseline → LLM 강화) 인덱싱 재구성. 정확도 ROI 1위.
- **Phase 7-C (한국어 최적화) ✅ 완료**: §10.4 `trigram` FTS 토크나이저. 하이브리드 기본 활성화(`app.search-hybrid-enabled`) 전환은 별도 후속 판단으로 남김.

**선결 과제(권장)**: 검색 품질을 정량 비교할 **평가 세트**(질문–정답 청크 쌍 소량 + recall@k/nDCG 측정 스크립트)가 있으면 §10.1·§10.2·§10.4의 효과 검증이 크게 쉬워진다. Phase 7-A와 병행 준비를 권장.

### 10.7 Phase 7-E 제안 — 검색 정확도 개선 🟡 일부 완료 (10.7.1~10.7.4, 코드 리뷰)

**배경**: 검색·인덱싱 파이프라인 전체(`RetrievalService`, `ChromaVectorStoreProvider`/`SqliteVecVectorStoreProvider`, `KeywordSearchRepository`, `RerankerService`)를 다시 정독하며 도출한 후속 제안. Phase 7-A~C(§10.1~10.4)가 이미 반영된 상태 위에서의 증분 개선이며, 10.7.1~10.7.4는 완료, 10.7.5만 미착수다.

**10.7.1 리랭커 입력이 청크 앞 200자로 제한됨 ✅ 완료 (2026-07-14)**

**배경**: `RerankerService.formatDocList()`가 `text.substring(0, 200)`만 LLM에 전달해, 800자 청크의 핵심 내용이 뒤쪽에 있으면 리랭킹이 오히려 순위를 망칠 수 있었다.

**구현**: 프리뷰 길이를 200→500자로 확장하고, 청크 앞에 `(파일명 > 헤딩)` 구조적 컨텍스트 헤더를 붙였다. 원래 개선안은 `MetaKey.CHUNK_CONTEXT`(§10.1의 LLM 1~2문장 요약)를 프리뷰 앞에 붙이는 것이었으나 구현 중 확인 결과 이 필드는 **검색 결과 Document에 존재하지 않는다** — transient라 두 벡터 스토어 프로바이더(`ChromaVectorStoreProvider`/`SqliteVecVectorStoreProvider`) 모두 `.add()` 시점에 제거하고 절대 영속화하지 않기 때문(§10.1 3계층 분리 원칙). 대신 이미 영속되는 `MetaKey.FILENAME`+`MetaKey.HEADING`으로 §10.1의 결정적 baseline 로직(`KeywordExtractor.buildStructuralContext()`, 이번에 `public`으로 공개해 재사용)을 그대로 재현 — 재인덱싱 없이 기존 인덱싱 문서에도 즉시 적용된다는 게 원래 개선안 대비 장점.

**완료 기준 — 충족**: 리랭크 활성 상태에서 프리뷰에 구조적 컨텍스트 헤더가 포함되고(`RerankerServiceTest` 신규 5개), 프리뷰 길이가 500자로 확장되며, 기존 리랭크 단위테스트가 회귀 없이 통과. 전체 703 tests(회귀 0).

**10.7.2 하이브리드 검색 기본값(off)이 이미 지불한 인덱싱 비용을 낭비 ✅ 완료 (2026-07-14)**

**배경**: `chunk_fts`는 `app.search-hybrid-enabled` 값과 무관하게 모든 인덱싱에서 채워진다(`DocumentIndexer.index()`/`reindexFromMd()` 양쪽 모두 `keywordRepo.indexChunks(enriched)`를 조건 없이 호출). 즉 인덱싱 비용은 항상 내면서 검색 시 기본값(false)이라 혜택은 못 받는 상태였다.

**구현**: `SEARCH_HYBRID_ENABLED` 기본값을 `false`→`true`로 전환(`application.properties` + `docker-compose.yml`의 두 독립된 기본값 소스 모두). 원래 개선안은 §10.6 "선결 과제"(질문–정답 청크 쌍 + recall@k/nDCG 평가 하네스, §10.7.5로 별도 분리됨)로 측정 후 결정하는 것이었으나, 그 하네스가 아직 없어 **정량 검증 없이 결정**했다 — 이는 개선안 자체가 명시한 폴백 경로("데이터가 없다면 우선 시범 적용해볼 가치가 있음")이며, 사용자 확인 후 진행. 근거: 인덱싱 비용은 이미 상시 지불 중이라 하이브리드 활성화의 한계비용은 검색 시점 BM25 조회뿐이고, 하이브리드의 주요 리스크(BM25 축이 벡터 축 대비 과대표됨·한국어 형태소 불일치)는 §10.2 가중 RRF(벡터축 그룹 정규화)·§10.4 trigram 토크나이저가 이미 완화해 둔 상태. 부수로 기존 `SEARCH_HYBRID_ENABLED` 문서 곳곳의 "활성화 시 재인덱싱 필요"라는 오래된 서술(OPERATOR_MANUAL.md 등)도 함께 정정 — `chunk_fts`가 플래그 무관하게 항상 채워지므로 실제로는 재인덱싱 불필요(FTS5/하이브리드 검색 도입 이전의 아주 오래된 문서만 예외).

**완료 기준 — 재정의**: 원래 기준("평가 세트 기준 recall 유의미 개선 시 전환")은 §10.7.5 부재로 충족 불가 — 대신 "리스크 완화책(§10.2·§10.4)이 이미 적용된 상태에서 사용자 승인을 받아 트라이얼로 전환, 근거를 문서에 기록"으로 완료 처리. 전체 703 tests 회귀 0(모든 소비 테스트가 `AppProperties`를 목으로 명시적 스텁하므로 프로퍼티 기본값 변경과 무관). **§10.7.5(평가 하네스) 완료 시 이 결정을 데이터로 재검증 권장.**

**10.7.3 한국어 2글자 질의어가 BM25 축에서 완전히 탈락 ✅ 완료 (2026-07-14)**

**배경**: `KeywordSearchRepository.toMatchQuery()`가 3자 미만 토큰을 드롭 — trigram 토크나이저의 알려진 트레이드오프(§10.4 실측 정정에 이미 기록됨)이지만 보완책이 없어 "오류"·"문서" 같은 2글자 단독 질의는 BM25 축 후보가 0건이었다.

**구현**: 3자 미만 질의어를 `toMatchQuery()`와 동일한 토큰화 로직을 공유하는 신규 `shortTerms()`로 추출하고, `KeywordSearchRepository.search()`가 정상 MATCH 결과에 `content`/`keywords` 대상 `LIKE '%용어%'` 보조 스캔 결과를 이어붙이는 방식으로 편입(개선안이 제시한 "별도 축" 대신 "BM25 축의 폴백" 쪽을 선택 — `RetrievalService`의 RRF 융합 로직·시그니처는 무변경, `KeywordSearchRepository` 내부에만 국한된 최소 침습). LIKE 결과는 BM25 순위가 없어 MATCH 결과 뒤에 배치되므로 RRF 내에서 자연히 더 낮은 순위를 받고, `spring_doc_id` 기준으로 MATCH 결과와 중복 제거하며, 결합 결과는 topK를 넘지 않는다. 행 매퍼를 `CHUNK_ROW_MAPPER` 상수로 공유해 MATCH/LIKE 두 조회가 동일 로직을 재사용한다.

**완료 기준 — 충족**: "오류", "문서" 같은 2글자 질의가 최소 1건 이상 반환(`KeywordSearchRepositoryTest` 신규 7개 — 단독 숏텀·혼합 길이·중복 제거·버전 필터·topK 상한·`shortTerms()` 단위테스트). 전체 710 tests 회귀 0. §10.4 실측 트레이드오프 서술(본문·OPERATOR_MANUAL.md)도 "BM25 축 기여 0" → "순위 점수는 없지만 존재 신호는 얻음"으로 함께 정정.

**10.7.4 유사도 임계값 적용 시 후보 풀이 topK 미만으로 조용히 축소 ✅ 완료 (2026-07-14)**

**배경**: `SqliteVecVectorStoreProvider.searchByEmbedding()`와 `ChromaVectorStoreProvider.mapPerQuery()` 모두 정확히 `k=topK`로 KNN 조회한 뒤 `similarityThreshold`로 필터링 — `app.search-similarity-threshold > 0`으로 튜닝하는 순간 후보 풀이 topK보다 작아졌다. 기본값(0.0)에서는 무해.

**구현**: 두 프로바이더 모두 임계값이 0보다 클 때만 조회 `k`(Chroma는 `n_results`, sqlite-vec는 `vec_embeddings`의 `k = ?` KNN 파티션 파라미터)를 `topK × 2.0`으로 과조회한 뒤, 필터링 결과를 다시 `topK`로 잘라낸다(`ChromaVectorStoreProvider.mapPerQuery()`에 `topK` 파라미터 추가, `SqliteVecVectorStoreProvider.searchByEmbedding()`은 `.limit(topK)` 추가). 결과가 이미 거리순 정렬이므로 "임계값 통과한 것 중 앞에서부터 topK"가 곧 "임계값 이상 중 가장 가까운 topK"와 동일. 임계값이 0.0(기본)이면 `fetchK == topK`라 과조회 자체가 발생하지 않아 완전히 무해.

**완료 기준 — 충족**: `similarity-threshold > 0`일 때 조회 `k`가 topK의 2배로 증가함을 확인(`ChromaVectorStoreProviderTest`/`SqliteVecVectorStoreProviderTest` 각 2개), threshold=0.0에서는 과조회 없음(무회귀) 확인, 필터 통과 결과가 topK를 초과해도 잘려나감을 확인(각 1개). 신규 테스트 6개, 전체 716 tests 회귀 0. sqlite-vec 쪽은 vec0 네이티브 바이너리가 필요해 실제 KNN 스캔 자체는 (기존 관례대로) 별도 통합 테스트(`SqliteVecIntegrationTest`, 이 환경엔 vec0 없어 skip) 영역 — 이번 유닛테스트는 파라미터 계산·후처리 캡 로직만 검증.

**10.7.5 검색 품질 평가 하네스 부재 (§10.6 선결 과제 재확인)**
- §10.6에 이미 "선결 과제(권장)"로 기록돼 있으나 여전히 미착수. 10.7.1~10.7.4는 모두 코드 레벨 완료 기준(단위테스트로 검증 가능)이라 이 하네스와 무관하게 완료됐지만, 10.7.2(무측정 기본값 전환)·10.7.3(LIKE 폴백이 실제 recall에 얼마나 기여하는지)의 "정말 정확도가 좋아졌는가"는 여전히 미검증 — 이를 데이터로 재검증하려면 질문–정답 청크 쌍 20~50개 + recall@k/nDCG 스크립트가 사실상 선행 조건. 신규 제안 중 우선순위가 가장 높다(§10.10 참조).

### 10.8 Phase 7-E 제안 — 검색·인덱싱 속도 개선 ✅ 완료 (2026-07-13 코드 리뷰 → 2026-07-15 10.8.1~10.8.5 전체 완료)

**10.8.1 [검색] MultiQuery 확장 LLM 호출이 모든 질문의 크리티컬 패스에 있음 ✅ 완료 (2026-07-15)**

**배경**: `RetrievalService.shouldExpand()`(`RetrievalService.java:187`)의 최소 길이 기준이 `app.search-multiquery-min-length` 기본값 0(`application.properties:137`)이라 한 글자짜리 질문도 확장 LLM 왕복을 먼저 거쳤고, 확장이 필요한 질문이라도 원본 질의 검색까지 그 왕복 뒤로 직렬화돼 있었다.

**구현**: 제안된 두 안(a/b)을 병행 적용. (a) `SEARCH_MULTIQUERY_MIN_LENGTH` 기본값을 0→15로 상향(`application.properties`+`docker-compose.yml` 두 기본값 소스 모두, §10.7.2와 동일 패턴) — 15자 미만 키워드형 질의는 확장 자체를 생략. (b) `RetrievalService.execute()`를 재구성해 원본 질의 벡터 검색(`ragService.search()`)과 BM25 키워드 축(하이브리드 활성 시)을 가상 스레드(`Executors.newVirtualThreadPerTaskExecutor()`)로 즉시 시작하고, `multiQueryExpander.expand()`는 호출 스레드에서 그대로 블로킹 — 확장 완료 후에는 변형 질의(원본 제외)만 `searchBatch()`로 조회해 RRF 병합에 합류시킨다. 원본 질의 검색이 확장 LLM 왕복 뒤가 아니라 그 **동안** 끝나므로, 전체 검색 지연이 "확장 시간 + 전체 배치 검색 시간"에서 "확장 시간 + 변형 검색 시간"으로 줄어든다(원본 검색 지연이 확장 대기 뒤에 숨음). `shouldExpand()=false` 경로(기존 동작)와 하이브리드 비활성 경로는 무변경.

**완료 기준 — 충족**: (a) 프로퍼티 기본값 변경으로 검증(짧은 키워드 질의는 확장 생략, 기존 `RetrievalServiceExpansionGateTest` 회귀 0 — 모두 `AppProperties` 목으로 `minLength`를 명시 스텁하므로 기본값 변경과 무관). (b) 신규 `RetrievalServiceMultiQueryParallelTest` — 확장 LLM 호출에 인위적 300ms 지연을 주입해 원본 질의 검색(`ragService.search()`)이 그 지연이 끝나기 전에 이미 호출됐음을 타이밍으로 확인, `searchBatch()`에는 변형 질의만(원본 제외) 전달됨을 검증. 전체 729 tests 회귀 0.

**10.8.2 [인덱싱] 청크당 LLM 1회 호출이 인덱싱 시간의 지배 항목 ✅ 완료 (2026-07-15)**

**배경**: `KeywordExtractor.enrichKeywords()`(`KeywordExtractor.java:97`)가 청크마다 독립 LLM 호출이며 동시성 상한은 `app.indexing.max-concurrent-llm-calls`(기본 4)뿐. 300청크 문서면 LLM 왕복 300회를 동시성 4로 처리.

**구현**: 청크 N개를 번호가 매겨진 `[DOCUMENT n]` 블록으로 묶어 한 번에 보내고, 응답을 `"[결과 n]"` 마커 기준으로 구간 분리해 기존 `parseEnrichment()`(키워드/맥락 정규식)를 구간별로 그대로 재사용(`KeywordExtractor.enrichKeywordsBatch()`, `splitBatchSections()`). 배치 크기는 신규 `app.indexing.keyword-batch-size`(env `INDEXING_KEYWORD_BATCH_SIZE`, 기본 4)로 조정 가능 — `AppProperties.IndexingConfig`에 필드 추가, `indexingSafe()`가 0 이하일 때 4로 폴백. `enrichParallel()`이 청크를 배치 단위로 나눠 세마포어를 **배치당 1회**(청크당 1회가 아님) 획득하도록 재구성했고, 배치 크기 1(스텁 안 된 테스트 목의 기본값이자 명시적 opt-out)은 기존 단일 청크 경로(`enrichKeywords()`)를 그대로 타 무변경 동작을 보존한다. 배치 호출이 실패(LLM 예외 또는 응답에 N개 결과 마커가 모두 없음)하면 청크별 LLM 재시도 없이 배치 전체가 곧바로 개별 TF 폴백(`tfFallback()`, 기존 단일 경로의 폴백 로직을 추출해 공유)으로 전환 — 이미 실패/타임아웃한 호출을 청크 수만큼 재시도하면 배치가 절감하려던 왕복 시간을 그대로 다시 소모하기 때문.

**완료 기준 — 충족**: 배치 크기 N=2, 청크 5개 → LLM 왕복 3회(`ceil(5/2)`)임을 `enrichParallel_batchSizeAboveOne_reducesRoundTrips`로 검증. 파싱 실패/LLM 예외 시 배치 전체가 개별 TF 폴백되고 청크별 재시도가 없음(`executeWithTracking` 호출 1회로 고정)을 별도 테스트 2개로 검증. 기존 `enrichParallel`/`enrichKeywords` 테스트는 배치크기가 스텁되지 않은 목에서 자동으로 1(무배치)로 클램프되어 무변경 통과. 전체 742 tests 회귀 0.

**10.8.3 [인덱싱] SQLite 배치 삽입이 명시적 트랜잭션 밖에서 실행됨 ✅ 완료 (2026-07-15)**

**배경**: `SqliteVecVectorStoreProvider.add()`의 `jdbc.batchUpdate(INSERT_EMBEDDING, ...)`(`SqliteVecVectorStoreProvider.java:146`)와 `KeywordSearchRepository.indexChunks()`의 `jdbc.batchUpdate(...)`(`KeywordSearchRepository.java:156`)가 각각 autocommit 상태로 실행 — WAL 모드라 파국적이진 않지만 행 수만큼 커밋 오버헤드가 붙는다.

**구현**: 개선안이 명시한 범위대로 `add()` 내부의 `vec_embeddings`+`vec_document_chunks` 배치 2개만 `TransactionTemplate`으로 묶었다(`KeywordSearchRepository.indexChunks()`는 별도 저장소·별도 쓰기 단계라 범위 밖 — "vectors→FTS→registry 순서" 제약 유지). `TransactionTemplate`/`DataSourceTransactionManager`는 `jdbc.getDataSource()`에서 지연 생성 후 캐시 — 실제 Spring 배선(`vectorJdbcTemplate`)에서는 항상 실제 `DataSource`를 갖고 있어 매번 트랜잭션으로 묶이지만, 완전히 mock된 `JdbcTemplate`(단위테스트 더블, `getDataSource()`가 기본 null)에서는 트랜잭션 래핑을 건너뛰고 기존처럼 순차 호출 — 기존 `SqliteVecVectorStoreProviderTest`의 mock 기반 테스트 18개가 무수정으로 계속 통과한다. 부수 효과: 두 삽입 중간에 실패해도 트랜잭션이 함께 롤백되어, 이전에는 가능했던 "`vec_embeddings`행은 커밋됐는데 매칭되는 `vec_document_chunks`행이 없는" 상태가 더 이상 발생하지 않는다.

**완료 기준 — 재정의**: 원래 기준("벤치마크로 대량 인덱싱 시간 단축 확인")은 로컬에 벤치마크 인프라가 없어 대체 — 대신 실제 SQLite `DataSource`(임시 파일)를 주입하고 `TransactionSynchronizationManager.isActualTransactionActive()`로 두 `batchUpdate` 호출이 실제로 하나의 트랜잭션 안에서 실행됨을 신규 테스트로 직접 검증, `add()` 반환 후 트랜잭션이 닫혀 있음도 확인. `DataSource`가 없는 기존 mock 경로(트랜잭션 스킵)도 명시적 테스트로 커버. 전체 742 tests 회귀 0(기존 18개 + 신규 2개 = 20개, `SqliteVecVectorStoreProviderTest`).

**10.8.4 [인덱싱] 디렉터리 동기화가 같은 파일을 두 번 SHA-256 해싱 ✅ 완료 (2026-07-15)**

**배경**: `DocumentIndexer.syncDirectory()` 1단계(`DocumentIndexer.java:365`)에서 계산한 sha256을 버리고 `index()`(`DocumentIndexer.java:101`)가 파일 전체를 다시 읽어 재해싱한다.

**구현**: `IndexRequest`에 `precomputedSha256`(nullable) 필드를 추가하고 `parallel(...)`의 6-인자 오버로드로 노출(기존 5-인자 `parallel()`/`single()`은 `null`을 넘겨 하위호환 유지 — 재계산 동작 무변경). `syncDirectory()` 1단계의 `FileEntry` 레코드가 이미 계산한 sha256을 함께 실어 2단계 `index(IndexRequest.parallel(...))` 호출에 전달하고, `index()`는 `req.precomputedSha256() != null`이면 파일을 다시 읽지 않고 그 값을 그대로 사용한다.

**완료 기준 — 재정의**: 원래 기준("`computeSha256()` 호출 횟수를 단위테스트로 검증")은 해당 메서드가 `private`이라 직접적 호출-횟수 계측이 불가 — 대신 **의도적으로 실제 파일 내용과 다른** sha256 값을 `IndexRequest.parallel(..., precomputedSha256)`에 실어 전달하고, `index()`가 파일을 재해싱하지 않고 그 값을 그대로 신뢰해 결과 `DocumentInfo.sha256()`에 반영함을 확인하는 테스트로 대체 — 재해싱했다면 실제 파일 해시가 나와야 하므로, 이는 메커니즘이 실제로 우회 없이 작동함을 호출 횟수 계측보다 더 직접적으로 증명한다. `precomputedSha256`이 `null`일 때 기존과 동일하게 파일에서 재계산되는 회귀 테스트도 함께 추가. 전체 742 tests 회귀 0.

**10.8.5 [인덱싱] 파생 텍스트(정규화) 중복 계산 ✅ 완료 (2026-07-15)**

**배경**: `SearchTextBuilder.build()`가 임베딩 경로(`ChromaVectorStoreProvider.java:131` 등)와 FTS 경로(`KeywordSearchRepository.java:171`)에서 청크당 각각 호출된다.

**구현**: 신규 transient 메타키 `MetaKey.SEARCH_TEXT`(§10.1 `CHUNK_CONTEXT`와 동일한 "영속 전 제거" 규율)를 도입. `SearchTextBuilder.precompute(Document)`가 계산 결과를 이 키에 저장한 새 `Document`를 반환하고, 기존 `build()`는 이 값이 이미 있으면 재계산 없이 그대로 반환하는 단축 경로를 추가(값이 없는 기존 호출부는 동작 무변경 — 하위호환). `DocumentIndexer.index()`/`reindexFromMd()`가 `enrichParallel()` 직후 `precompute()`를 **한 번** 적용한 뒤 그 결과를 `vectorStore.add()`와 `keywordRepo.indexChunks()` 양쪽에 그대로 전달 — 두 `VectorStoreProvider` 구현체의 `add()` 내부 호출부(`SearchTextBuilder.build(d)`)는 코드 변경 없이 자동으로 단축 경로를 타게 된다. `ChromaVectorStoreProvider`/`SqliteVecVectorStoreProvider` 양쪽 모두 영속 직전 메타데이터에서 `SEARCH_TEXT`를 제거(`CHUNK_CONTEXT`와 동일 처리)해 Chroma 메타데이터·`vec_document_chunks.metadata` JSON에 흘러들지 않도록 했다.

**완료 기준 — 충족**: `precompute()`가 `SEARCH_TEXT`를 저장하고 `build()`가 그 값을 그대로 반환함(재계산 없음)을 신규 테스트로 검증, 원본 텍스트/다른 메타데이터 보존 확인, 사전계산값이 공백일 때 무시하고 재계산하는 방어 로직도 검증. 전체 742 tests 회귀 0.

### 10.9 Phase 7-E 제안 — 메모리 최적화 🟡 일부 완료 (10.9.2·10.9.4, 2026-07-13 코드 리뷰 → 2026-07-15 완료)

**10.9.1 Chroma 배치 검색이 쓰지 않는 임베딩까지 응답으로 받아옴 (확인 완료 — Spring AI 1.1.8 소스 대조)**
- 현재: `ChromaVectorStoreProvider.searchBatch()`(`ChromaVectorStoreProvider.java:104`)가 `ChromaApi.QueryRequest.Include.all`을 사용하는데, Spring AI 1.1.8 소스(`spring-ai-chroma-store-1.1.8-sources.jar`) 확인 결과 `Include.all = {METADATAS, DOCUMENTS, DISTANCES, EMBEDDINGS}` — `mapPerQuery()`(`ChromaVectorStoreProvider.java:236`)는 임베딩을 전혀 사용하지 않는다. 리랭크 활성 시 질의 3개 × 후보 21개 × 1536차원이면 검색 1회당 약 1MB의 무의미한 부동소수점 JSON을 전송·파싱·GC하는 셈.
- 개선안: `List.of(Include.METADATAS, Include.DOCUMENTS, Include.DISTANCES)`로 축소.
- 완료 기준: 검색 응답에서 임베딩 필드가 요청되지 않음(네트워크 요청 바디로 확인), 기존 검색 단위테스트 회귀 0. 이번 목록에서 가장 즉효가 확실한 수정.

**10.9.2 sqlite-vec 벡터가 텍스트 리터럴로 직렬화됨 ✅ 완료 (2026-07-15)**

**배경**: `SqliteVecVectorStoreProvider.toVectorLiteral()`이 1536차원 벡터를 `[0.123,...]` 문자열(~15KB, float32 BLOB 6KB의 2.5배)로 직렬화 — 삽입·검색마다 vec0가 JSON 파싱을 수행.

**구현**: `toVectorLiteral()`을 제거하고 `toVectorBlob()`(little-endian float32 raw blob, `ByteBuffer`)로 교체. `add()`의 `INSERT_EMBEDDING` 배치(`PreparedStatement.setBytes()`)와 `searchByEmbedding()`의 KNN 질의 파라미터(`JdbcTemplate`이 `byte[]` varargs를 자동으로 `setBytes()`로 바인딩 — 커스텀 `PreparedStatementSetter` 불필요) 양쪽 모두 적용. vec0는 입력 시점의 SQLite 값 타입(TEXT vs BLOB)으로 포맷을 자동 판별하고 내부적으로는 항상 자체 이진 표현으로 저장하므로, 기존에 JSON 텍스트로 삽입된 행과 새로 BLOB로 삽입되는 행이 같은 테이블에 섞여도 호환 문제가 없다 — 백필/마이그레이션 불필요.

**완료 기준 — 충족**: `toVectorBlob()`의 바이트 단위 라운드트립(디코드 후 원래 float 값과 일치)과 길이(4×차원) 검증 신규 테스트, `INSERT_EMBEDDING` 파라미터를 검증하던 기존 테스트(`addMapsEmbeddingsToCorrectDocIdAcrossBatches`)를 `setString`→`setBytes` 기대값으로 갱신. 전체 747 tests 회귀 0.

**리스크 — 문서화**: 원안이 지적한 "폐쇄망 vec0 빌드의 BLOB 바인딩 지원 여부"는 실제로는 거의 발생하지 않을 것으로 판단 — BLOB는 sqlite-vec의 근본 이진 포맷이고 JSON 텍스트는 그 위의 편의 계층이라 "JSON은 되는데 BLOB는 안 되는" 조합은 현실적으로 드물다. 그럼에도 실패 시 vec0가 명확한 오류를 던지므로(조용한 데이터 손상이 아님) 조기 발견 가능 — sqlite-vec 백엔드로 전환/업그레이드 시 문서 1건 인덱싱+검색으로 우선 확인 권장(OPERATOR_MANUAL.md에 기록).

**10.9.3 대형 문서 add()가 전체 임베딩을 힙에 모은 뒤 일괄 삽입**
- 현재: `SqliteVecVectorStoreProvider.add()`(`SqliteVecVectorStoreProvider.java:132`)가 `embeddingByDocId` 맵에 문서 전체 임베딩과 `chunkRows` 전체를 쌓은 뒤 마지막에 일괄 `batchUpdate`. 500청크×1536차원 ≈ 3MB로 당장 위험하진 않으나, 대용량 문서가 커질수록 피크 메모리가 문서 크기에 비례해 증가.
- 개선안: 토큰 서브배치(`batchingStrategy.batch()`) 단위로 삽입까지 완료하는 스트리밍 구조로 전환 — 피크 메모리가 서브배치 크기로 고정되고, §10.8.3의 트랜잭션과 배치 단위를 맞추면 자연스럽게 결합된다.
- 완료 기준: 서브배치 완료마다 해당 배치의 삽입이 끝나고(전체 완료를 기다리지 않음), 진행률 콜백(`onProgress`) 정밀도가 유지되거나 개선.

**10.9.4 인덱싱 청크가 질의 임베딩 캐시를 밀어냄 ✅ 완료 (2026-07-15)**

**배경**: `CachingEmbeddingModel`(Javadoc이 스스로 트레이드오프를 인정)의 캐시(기본 max 500, §10.3)를 인덱싱 청크와 검색 질의가 공유한다. 문서 하나(500+청크) 인덱싱 직후 질의 캐시가 사실상 전멸하고, 그동안 캐시는 청크 원문(~800자) × 500개를 키로 점유한다.

**구현**: 제안된 두 안(a/b) 모두 적용. (a) `CachingEmbeddingModel.unwrapForIndexing(EmbeddingModel)` 신설 — `CachingEmbeddingModel` 인스턴스면 내부 delegate(캐시 미적용 원본)를 반환하고, 아니면 그대로 반환. `ChromaVectorStoreProvider`/`SqliteVecVectorStoreProvider` 생성자가 주입받은 `embeddingModel`과 별도로 `indexingEmbeddingModel = unwrapForIndexing(embeddingModel)`을 계산해 두고 `add()` 경로의 임베딩 호출에만 사용 — `search()`/`searchBatch()`는 기존 `embeddingModel`(캐시 적용)을 그대로 사용해 질의 캐시 혜택은 무변경. sqlite-vec는 `embedBatchWithFallback`/`embedSingleWithFallback`이 사용할 `EmbeddingModel`을 파라미터로 받도록 리팩터링해 동일 폴백 로직을 캐시/비캐시 두 모델에 재사용한다. (b) `CachingEmbeddingModel.cacheKey()`가 원문 대신 SHA-256 해시를 캐시/in-flight 맵 키로 사용 — 맵 엔트리 크기가 질의 길이와 무관하게 고정된다.

**완료 기준 — 충족**: `CachingEmbeddingModel`을 실제로 주입한 `SqliteVecVectorStoreProvider.add()` 호출 전후로 delegate 호출 횟수를 비교해 인덱싱 직후에도 직전 검색 질의가 캐시 히트로 유지됨을 신규 통합 테스트로 확인. 인덱싱 임베딩 호출이 캐시 계층을 완전히 우회함(`delegate.call()` 미호출, `delegate.embed()` 직접 호출)을 Chroma·sqlite-vec 양쪽에 대해 검증, `searchBatch()`는 캐시가 여전히 적용됨을 별도 테스트로 확인(무회귀). `unwrapForIndexing()` 자체의 단위테스트 2개(래핑/비래핑)도 추가. 기존 §10.3 `CachingEmbeddingModelTest` 8개 회귀 0(캐시 키 해시화는 동작 기반 테스트라 영향 없음). 전체 747 tests 회귀 0.

### 10.10 Phase 7-E 제안 우선순위

| 순위 | 항목 | Impact | 회귀리스크 | Effort |
|:--:|---|:--:|:--:|:--:|
| 1 | 10.9.1 Chroma `Include`에서 `EMBEDDINGS` 제외 | 3 | 1 | 1 |
| 2 | 10.8.1 MultiQuery 최소 길이 상향/병렬화 (완료 — §10.8.1 참조) | 4 | 1~2 | 1~2 |
| 3 | 10.8.2 키워드 추출 청크 배칭 (완료 — §10.8.2 참조) | 4 | 2 | 3 |
| 4 | 10.8.3 + 10.9.3 트랜잭션 묶기 + 스트리밍 삽입 (10.8.3만 완료 — §10.8.3 참조; 10.9.3은 미착수) | 3 | 2 | 2 |
| 5 | 10.8.4 sha256 중복 제거 (완료 — §10.8.4 참조; 10.7.1도 완료 — §10.7.1 참조) | 2 | 1 | 1 |
| 6 | 10.7.5 검색 품질 평가 하네스 → 10.7.2/10.7.3 재검증 | 4(선행조건) | 0 | 2 |
| 후속 | 10.9.2 sqlite-vec BLOB 벡터 (완료 — §10.9.2 참조), 10.9.4 캐시 분리 (완료 — §10.9.4 참조; 10.8.5도 완료 — §10.8.5 참조) | 2 | 1~2 | 2 |

**진행 순서 제안**: 즉효·저리스크(10.9.1, 미착수)부터 적용 → 체감 지연이 큰 검색 경로(10.8.1, 완료) → 인덱싱 시간 지배 항목(10.8.2, 완료) → 나머지는 평가 하네스(10.7.5) 확보 후 데이터 기반으로 순서 재조정. §10.9.2·§10.9.4(메모리 최적화)는 우선순위상 후순위였으나 사용자 요청으로 조기 완료.

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
