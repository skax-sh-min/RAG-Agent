# 데이터베이스 · 벡터 저장소 레퍼런스

> **개발자용 레퍼런스입니다.** SQLite 테이블·컬럼·인덱스, 테이블 사이의 관계, 그리고 벡터 저장소(sqlite-vec / Chroma)가 청크를 어떻게 담는지를 정리합니다.
> 운영자가 알아야 할 것(파일 위치·백업·옛 `vector.db` 이름 정리)은 [OPERATOR_MANUAL §6.3.1](OPERATOR_MANUAL.md#631-sqlite-파일별-테이블-구성), 스키마를 바꾸는 규약은 [PLAN §13](PLAN.md#13-db-스키마-변경-요약), 각 규칙이 왜 그런지는 [PITFALLS](PITFALLS.md)에 있습니다.
>
> **기준**: 2026-09-29, 실제 `data/memory.db`(sqlite-vec 배포)의 `PRAGMA table_info`와 코드의 DDL을 대조해 작성했습니다. 스키마의 **정본은 코드**입니다(§2.1) — 이 문서와 코드가 다르면 코드가 맞고, 이 문서를 고쳐야 합니다(§9).

## 목차

1. [한눈에 보기](#1-한눈에-보기)
2. [공통 규약](#2-공통-규약)
3. [테이블 사이의 관계](#3-테이블-사이의-관계)
4. [대화 테이블](#4-대화-테이블)
5. [문서·큐레이션 테이블](#5-문서큐레이션-테이블)
6. [운영·계정·시스템 테이블](#6-운영계정시스템-테이블)
7. [키워드 검색 색인 — 두 백엔드 공통](#7-키워드-검색-색인--두-백엔드-공통)
8. [벡터 저장소](#8-벡터-저장소)
9. [실제 스키마 확인하기 · 이 문서 갱신](#9-실제-스키마-확인하기--이-문서-갱신)

---

## 1. 한눈에 보기

DB 파일은 **하나**입니다 — `{DATA_DIR}/memory.db` (SQLite, WAL, 커넥션 풀 1). 벡터 백엔드(`app.vectorstore.type`)가 `sqlite-vec`이면 벡터까지 이 파일에 있고, `chroma`이면 벡터만 Chroma 서버에 있습니다.

| 테이블 | 한 행 = | 분류 | 만드는 곳 |
|---|---|---|---|
| `conversation_turns` | 질문·답변 한 턴 | 대화 | `SqliteMemoryRepository` (Flyway V1 + 런타임 `ALTER`) |
| `thread_meta` | 대화 하나 | 대화 | `ThreadMetaRepository` (Flyway V1·V3) |
| `turn_source_ref` | 한 턴의 출처 청크 하나 | 대화 | `QuestionReuseRepository` |
| `turn_image_ref` | 한 턴의 답변 썸네일 하나 | 대화 | `SqliteMemoryRepository` |
| `image_descriptions` | 이미지 하나의 Vision 설명(캐시) | 대화 | `SqliteMemoryRepository` (Flyway V1) |
| `doc_registry` | 인덱싱된 문서 하나 | 문서 | `DocRegistry` |
| `curated_qa` | 큐레이션 Q&A 한 건 (벡터는 N개) | 큐레이션 | `CuratedQaRepository` |
| `curated_submission` | 지식 제안 한 건 | 큐레이션 | `CuratedSubmissionRepository` |
| `chunk_report` | 청크 오류 신고 한 건 | 큐레이션 | `ChunkReportRepository` |
| `llm_usage` | (프로바이더, 날짜)별 사용량 | 운영 | `LlmUsageRepository` (Flyway V1) |
| `settings_override` | `/settings` 오버라이드 키 하나 | 운영 | `SettingsOverrideRepository` |
| `app_secret` | 서버 비밀값 하나 | 운영 | `AppSecretRepository` |
| `users` | 계정 하나 | 계정 | Flyway V2 / `SqliteUserDetailsService` |
| `persistent_logins` | remember-me 토큰 (현재 미사용) | 계정 | Flyway V2 / `SqliteUserDetailsService` |
| `flyway_schema_history` | 마이그레이션 한 건 | 시스템 | Flyway |
| `chunk_fts` | 청크 하나 (FTS5 키워드 색인) | 검색 색인 | `KeywordSearchRepository` — **두 백엔드 공통** |
| `chunk_fts_key` | 청크 하나 (`chunk_fts`를 id로 찾는 색인) | 검색 색인 | `KeywordSearchRepository` — **두 백엔드 공통** |
| `vec_document_chunks` | 청크 하나 (원문 + 메타데이터) | 벡터 | `SqliteVecSchemaInitializer` — sqlite-vec 전용 |
| `vec_embeddings` | 청크 하나 (벡터, vec0 가상 테이블) | 벡터 | `SqliteVecSchemaInitializer` — sqlite-vec 전용 |

> 이 밖의 이름은 SQLite가 자동으로 만든 **그림자 테이블**입니다 — `chunk_fts_config`/`_content`/`_data`/`_docsize`/`_idx`(FTS5), `vec_embeddings_chunks`/`_info`/`_rowids`/`_vector_chunks00`(vec0), `sqlite_sequence`(AUTOINCREMENT). 직접 조회·수정하지 마세요. 뷰와 트리거는 없습니다.

---

## 2. 공통 규약

### 2.1 스키마는 코드가 만든다 — Flyway 파일만 보면 안 된다

- Flyway 마이그레이션은 `V1__baseline.sql`(대화·이미지 캐시·사용량·대화 메타) · `V2__users.sql`(계정) · `V3__thread_tags.sql`(`thread_meta.tags`) 셋뿐이고, 테이블의 **처음 모양**만 담습니다.
- 나머지 테이블과 그 뒤에 생긴 컬럼은 각 저장소 클래스가 기동할 때(`@PostConstruct`) 만듭니다 — `CREATE TABLE IF NOT EXISTS` 다음에 방어적 `ALTER TABLE … ADD COLUMN`(이미 있으면 오류를 삼킴). 예를 들어 `conversation_turns`는 V1에 컬럼 12개로 정의돼 있지만 실제로는 19개입니다.
- 벡터 테이블은 `SqliteVecSchemaInitializer`가 `ApplicationReadyEvent` 때 만듭니다(벡터 차원이 설정값이라 정적 SQL로 쓸 수 없음). FTS 테이블은 `KeywordSearchRepository`가 만듭니다.
- 테이블을 **다시 짓는** 경우가 둘 있습니다: `curated_qa`(옛 스키마면 `source_turn_id`를 nullable로 바꾸려고 `curated_qa_new`로 복사 후 이름 변경, 한 트랜잭션), `chunk_fts`(옛 스키마면 trigram 토크나이저·`doc_tags`·`chapter`를 갖춘 테이블로 재생성, rowid 보존).
- Flyway는 `spring.flyway.baseline-version=3`입니다 — 이력 없이 런타임 DDL로 만들어진 파일을 버전 3으로 baseline합니다(이유: [PITFALLS § 벡터 스토어 백엔드와 vec/FTS DataSource](PITFALLS.md#벡터-스토어-백엔드와-vecfts-datasource)).
- **새 컬럼은 런타임 `ALTER` 패턴으로 추가합니다**([PLAN §13](PLAN.md#13-db-스키마-변경-요약)) — 그리고 이 문서의 표도 함께 고칩니다.

### 2.2 외래 키가 없다

어느 테이블에도 `FOREIGN KEY`가 없습니다. 테이블 사이의 연결은 전부 **값이 같은 컬럼**(§3)이고, 연쇄 삭제도 코드가 합니다. 예를 들어 턴 삭제는 `turn_source_ref` → `turn_image_ref` → `conversation_turns` 순서로 지웁니다 — 부모를 마지막에 지우므로 중간에 실패해도 부모 없는 자식 행만 남고, 모든 조회가 부모에서 출발하므로 화면에는 드러나지 않습니다.

큐레이션 행(`curated_qa`)은 턴·대화 id의 **사본**을 들고 있어 구조적으로는 대화 삭제를 견디지만, 대화를 지우면 서비스가 그 행을 회수합니다(`CuratedQaService.onThreadDeleted`).

### 2.3 식별자

| 이름 | 형식 | 비고 |
|---|---|---|
| 사용자 id (`user_id` 등) | full-auth: `users.id`(UUID) · no-auth 공유 게스트: `00000000-0000-0000-0000-000000000001` · 방문자별 게스트(`guest-identity`=`ip`/`cookie`/`hybrid`): `guest-`로 시작 | 컬럼 기본값 `'anonymous'`는 그 컬럼이 생기기 전 행과, 쓰는 코드가 없는 컬럼에만 남습니다 |
| 대화 id (`thread_id`) | UUID | `ChatController`가 새 대화에 발급 |
| 문서 id (`doc_id`) | `{파일명}_{원본 SHA-256 앞 8자}` | `DocumentIndexer`가 만듭니다. 파일명은 여기서 꺼냅니다(`DocRegistry.filenameFromDocId`) — `doc_registry`에 파일명 컬럼은 없습니다. 큐레이션은 `curated:{curated_qa.id}` |
| 청크 id (`spring_doc_id`, `chunk_id`) | 문서 청크: UUID · 큐레이션: `curated-{id}`, 둘째 청크부터 `curated-{id}-{n}` | 문서 청크 id는 **인덱싱할 때마다 새로 발급**됩니다 — 재인덱싱하면 옛 id를 가리키던 출처는 "삭제됨"이 됩니다 |
| 문서 버전 (`version`) | 기본 `latest` | 예약값 `curated` = 큐레이션 축. 벡터 파티션과 Chroma 컬렉션을 가르는 키입니다 |
| 이미지 id (`imageId`) | 원본 SHA-256 앞 16자 | 이미지 경로 `images/{imageId}/{파일}` ([IMAGE_PROCESS](IMAGE_PROCESS.md) §3.3) |
| 문서 소유자 키 | 항상 `shared` (`DocRegistry.SHARED`) | 문서 저장소는 사용자별로 나뉘지 않습니다 |

### 2.4 시각 형식 — 테이블마다 다르다

| 형식 | 시간대 | 컬럼 |
|---|---|---|
| `YYYY-MM-DD HH:MM:SS` (SQLite `datetime('now')`) | UTC | `conversation_turns.created_at`, `turn_source_ref.created_at`·`invalidated_at`·`hidden_at`, `turn_image_ref.created_at`, `image_descriptions.created_at` |
| `YYYY-MM-DD HH:MM:SS` (Java 포맷) | UTC | `conversation_turns.asked_at` |
| `YYYY-MM-DD HH:MM:SS` (Java `LocalDateTime.now()`) | **서버 로컬 시각** | `thread_meta.created_at`·`updated_at`, `curated_qa`·`curated_submission`·`chunk_report`의 모든 시각 |
| ISO-8601 `…Z` (`Instant`) | UTC | `doc_registry.indexed_at`, `vec_document_chunks.created_at`, `settings_override.updated_at`, `app_secret.created_at`, 메타데이터 `collected_at`·`edited_at` |
| ISO-8601, `Z` 없음 (`LocalDateTime.now(UTC)`) | UTC | `users.created_at`·`updated_at`·`locked_until` |
| `YYYY-MM-DD` | UTC 날짜 | `llm_usage.usage_date` |

> ⚠️ 겉모양이 같아도 시간대가 다릅니다. `thread_meta.updated_at`(로컬)과 `conversation_turns.created_at`(UTC)은 KST 서버에서 9시간 차이가 납니다 — 두 테이블의 시각을 SQL로 직접 비교하지 마세요.

### 2.5 JSON·CSV를 담는 컬럼

- **CSV(쉼표로 이은 문자열)** — 태그와 이미지 경로: `conversation_turns.selected_tags`, `thread_meta.tags`, `doc_registry.tags`, `curated_qa.tags`, `curated_submission.tags`, `chunk_fts.doc_tags`, 메타데이터 `tags`·`image_paths`. 두 벡터 백엔드가 같은 모양으로 담을 수 있도록 목록을 문자열로 이어 저장합니다.
- **JSON** — `doc_registry.spring_doc_ids`·`errors`(문자열 배열), `conversation_turns.retrieval_metrics`(객체 배열)·`verification`(객체), `vec_document_chunks.metadata`(객체).

---

## 3. 테이블 사이의 관계

외래 키가 없으므로(§2.2) 아래 연결은 전부 코드의 조인·조회 규약입니다.

| 이쪽 | 저쪽 | 쓰는 곳 / 의미 |
|---|---|---|
| `conversation_turns.thread_id` | `thread_meta.thread_id` | 대화 목록·턴 조회 |
| `conversation_turns.reused_from_turn_id` | `conversation_turns.id` | 재사용 턴 → 원본 턴. 답변을 읽을 때 원본이 이깁니다(`REUSE_SOURCE_JOIN` — 원본이 다른 사용자의 턴인 것이 정상이라 **사용자 조건이 없음**) |
| `turn_source_ref.turn_id`, `turn_image_ref.turn_id` | `conversation_turns.id` | 턴의 출처·썸네일 |
| `turn_source_ref.chunk_id` | `chunk_fts_key.spring_doc_id` → `chunk_fts.rowid`(`fts_rowid`), `vec_document_chunks.spring_doc_id` | 대화를 다시 열 때 출처의 위치·원문(`QuestionReuseRepository.findSourcePreviewRows`). **운영 테이블과 검색 테이블을 한 쿼리로 조인합니다** — DB 파일을 다시 둘로 나눌 수 없는 이유입니다 |
| `turn_source_ref.chunk_hash` | `chunk_fts_key.content_hash` | 같은 해시 함수 — 값이 다르면 그 청크는 턴 저장 뒤 수정된 것입니다 |
| `doc_registry.doc_id` | `chunk_fts_key.doc_id`, `vec_document_chunks.doc_id`, 메타데이터 `doc_id` | 문서 ↔ 청크 |
| `doc_registry.spring_doc_ids`(JSON) | 청크 id | 문서 삭제·재인덱싱이 지울 벡터·FTS 행 목록 |
| `vec_document_chunks.spring_doc_id` | `vec_embeddings.spring_doc_id` | 원문·메타데이터 ↔ 벡터 (sqlite-vec) |
| `curated_qa.source_submission_id` | `curated_submission.id` | 제안 하나 → 큐레이션 행 N개. 제안의 상태(청크 수·등록 완료/회수됨·임베딩 실패)는 **이 컬럼으로만** 셉니다 |
| `curated_submission.curated_qa_id` | `curated_qa.id` | 승인으로 만들어진 **첫** 행 (관리자 편집 패널용 포인터) |
| `curated_qa.source_turn_id`, `curated_submission.source_turn_id` | `conversation_turns.id` | 좋아요 출신 제안의 원본 턴 |
| `curated_qa.id` | 청크 id `curated-{id}[-n]`, 메타데이터 `doc_id` = `curated:{id}` | 큐레이션 행 ↔ 벡터·FTS 행 (버전 `curated`) |
| `chunk_report.chunk_id` | 청크 id | 신고된 청크 (위치·원문은 `chunk_fts_key`·`vec_document_chunks`에서 읽음) |
| `chunk_report.turn_id` / `thread_id` | `conversation_turns.id` / `thread_meta.thread_id` | 신고가 나온 대화 |
| `settings_override.key` | `SettingsKeys` 상수 | 오버라이드 대상 설정 |

---

## 4. 대화 테이블

### 4.1 `conversation_turns` — 질문·답변 한 턴

| 컬럼 | 타입 | 제약·기본값 | 의미 |
|---|---|---|---|
| `id` | INTEGER | PK, AUTOINCREMENT | 턴 id (화면·API의 `turnId`) |
| `thread_id` | TEXT | NOT NULL | 대화 id |
| `question` | TEXT | NOT NULL | 질문 원문 |
| `answer` | TEXT | NOT NULL | 답변 원문(마크다운). 재사용 턴은 읽을 때 원본 턴의 답변이 우선합니다 — 원본이 지워졌으면 이 값, 이것도 비었으면 `참조 원문 삭제됨` |
| `created_at` | TEXT | NOT NULL, `datetime('now')` | 행 저장 시각 (UTC) |
| `asked_at` | TEXT | | 질문 시각 (UTC) |
| `input_tokens`, `output_tokens` | INTEGER | 기본 0 | 이 턴에서 쓴 LLM 토큰 합 |
| `elapsed_ms` | INTEGER | 기본 0 | 답변 생성에 걸린 시간 (ms) |
| `provider` | TEXT | | 답변을 낸 프로바이더 이름 (예: `local`) |
| `llm_calls` | INTEGER | 기본 0 | LLM 호출 수 — 사전 분류(`classifyOnly`)가 빠져 1 적게 나옵니다(알려진 누락) |
| `user_id` | TEXT | NOT NULL, `'anonymous'` | 턴 주인 |
| `feedback` | TEXT | | `LIKE` / `DISLIKE` / NULL. `DISLIKE` 턴은 이력 프롬프트에서 빠집니다 |
| `response_mode` | TEXT | | 응답 모드 `S`/`N`/`C` — 요청값이 아니라 그래프 결과(`result.responseMode()`)입니다(검색 0건이면 `S`로 바뀜) |
| `selected_tags` | TEXT | | 이 턴에서 켠 검색 스코프 태그 (CSV, 빈 문자열 = 없음) |
| `reused_from_turn_id` | INTEGER | | 재사용 턴이면 원본 턴 id. 재사용 턴은 원본의 모드·`direct_mode`·`selected_tags`를 복사해 저장합니다 |
| `direct_mode` | INTEGER | NOT NULL, 0 | 1 = Direct 턴 (검색 없음) |
| `retrieval_metrics` | TEXT | | 출처별 검색 진단 JSON 배열 — `label`, `preview`, `chunk_id`, `doc_id`, `page_or_slide`, `similarity`, `retrieval_share`, `axis_ranks`, `answer_share`, `stale`, `prompt_excluded`. `/admin` 검색 진단 패널이 읽습니다 |
| `verification` | TEXT | | 답변 검증 스냅샷 JSON — `grounded`, `generative`, `evalReason`, `envNote`, `inventedSymbols`, `budgetNote`, `condensedQuestion`, `empty`. 다시 연 대화의 검증 배지 재료입니다. NULL = 검증 기록 없음(컬럼 이전 턴, meta·Direct·`S` 턴) |

인덱스: `idx_thread_id(thread_id)`, `idx_turns_user_thread(user_id, thread_id)`, `idx_turns_reused_from(reused_from_turn_id)`

### 4.2 `thread_meta` — 대화 하나

| 컬럼 | 타입 | 제약·기본값 | 의미 |
|---|---|---|---|
| `thread_id` | TEXT | PK | 대화 id (UUID) |
| `title` | TEXT | NOT NULL, `'새 대화'` | 제목 — LLM이 생성하거나(사용량 `title:`) 사용자가 고칩니다 |
| `version` | TEXT | NOT NULL, `'latest'` | 이 대화가 검색하는 문서 버전 |
| `created_at`, `updated_at` | TEXT | NOT NULL | **서버 로컬 시각**. 대화 목록은 `updated_at DESC` |
| `routing_mode` | TEXT | NOT NULL, `'COST_FIRST'` | `RoutingMode` — `COST_FIRST` / `QUALITY_FIRST` / `PROGRESSIVE` / `LOCAL_ONLY` |
| `tags` | TEXT | NOT NULL, `''` | 가장 최근 메시지에서 고른 태그 스냅샷 (CSV) — 사이드바 표시용 (V3) |
| `user_id` | TEXT | NOT NULL, `'anonymous'` | 대화 주인 |

인덱스: `idx_thread_meta_user(user_id)`

### 4.3 `turn_source_ref` — 한 턴의 출처 청크 하나

턴을 저장할 때 출처 청크마다 한 행을 남깁니다. 답변 재사용 검증(그 청크가 아직 그대로인가)과, 대화를 다시 열 때 출처를 그리는 데 씁니다.

| 컬럼 | 타입 | 제약·기본값 | 의미 |
|---|---|---|---|
| `id` | INTEGER | PK, AUTOINCREMENT | |
| `turn_id` | INTEGER | NOT NULL | → `conversation_turns.id` |
| `user_id`, `thread_id` | TEXT | NOT NULL | 턴 주인·대화 (삭제 범위) |
| `chunk_id` | TEXT | NOT NULL | 청크 id |
| `doc_id` | TEXT | | 문서 id — 위치 스냅샷도 없는 옛 행은 라벨의 파일명을 여기서 꺼냅니다 |
| `chunk_hash` | TEXT | NOT NULL | 턴 저장 시점 청크의 해시 (`chunk_fts_key.content_hash`와 같은 함수). 재사용 검증이 지금 해시와 비교합니다 |
| `status` | TEXT | NOT NULL, `'active'` | `active` / `deleted` / `modified` (옛 값 `inactive`도 무효로 읽힘). `modified`는 `active` 행만 바꾸고 `deleted`는 무조건 덮어씁니다 |
| `created_at` | TEXT | NOT NULL, `datetime('now')` | |
| `answer_share` | REAL | | 응답 참여도 0~1 (`AnswerAttribution`). NULL = 측정 못 함 — 그 경우 재사용 검증은 출처 전체를 봅니다 |
| `invalidated_at` | TEXT | | `status`가 `active`를 벗어난 시각 |
| `hidden_at` | TEXT | | 사용자가 "현재 대화에서 이 청크 제거"로 숨긴 시각 — **표시 전용**이라 재사용 검증은 이 값을 보지 않습니다 |
| `filename`, `page_or_slide`, `chapter_no` | TEXT | | 턴 저장 시점의 위치 스냅샷 — 청크가 사라져 라이브 조인이 비었을 때만 씁니다 |

인덱스: `idx_turn_source_turn(turn_id)`, `idx_turn_source_chunk(chunk_id)`

대화를 다시 열 때 `hidden_at`이 있는 행과 "`deleted`이면서 `answer_share`를 재서 0인" 행은 출처 목록에서 빠집니다. 재사용 턴을 만들 때 `cloneTurnSourceRefs()`가 원본 행을 위치 스냅샷까지 복사합니다. 상세: [PITFALLS § QuestionReuseRepository](PITFALLS.md#repositoryquestionreuserepositoryjava).

### 4.4 `turn_image_ref` — 한 턴의 답변 썸네일 하나

| 컬럼 | 타입 | 제약·기본값 | 의미 |
|---|---|---|---|
| `id` | INTEGER | PK, AUTOINCREMENT | |
| `turn_id` | INTEGER | NOT NULL | → `conversation_turns.id` |
| `user_id`, `thread_id` | TEXT | NOT NULL | |
| `image_ref` | TEXT | NOT NULL | `DATA_DIR` 기준 상대 경로 — `images/{imageId}/{파일}`(문서 이미지) 또는 `images/submissions/{해시}.{확장자}`(지식 제안 본문 이미지) |
| `status` | TEXT | NOT NULL, `'active'` | `active` / `inactive` (사용자가 이 턴에서 제외) |
| `created_at` | TEXT | NOT NULL, `datetime('now')` | |

인덱스: `idx_turn_image_turn(turn_id)`, `idx_turn_image_user_thread(user_id, thread_id)`

### 4.5 `image_descriptions` — Vision 이미지 설명 캐시

검색 시점에 이미지를 설명할 때(`LazyVisionService`) 결과를 캐시합니다. 키가 내용 기반 경로라서, 내용이 같은 이미지는 문서가 달라도 캐시를 공유합니다. 상세: [IMAGE_PROCESS](IMAGE_PROCESS.md) §12.3.

| 컬럼 | 타입 | 제약·기본값 | 의미 |
|---|---|---|---|
| `image_path` | TEXT | PK | `images/{imageId}/{파일}` 상대 경로 |
| `description` | TEXT | NOT NULL | 설명 텍스트 |
| `image_type` | TEXT | | `diagram` / `screenshot` / `photo` / `chart` / `other` |
| `provider` | TEXT | | 설명을 만든 프로바이더 (모니터링용) |
| `created_at` | TEXT | NOT NULL, `datetime('now')` | |
| `user_id` | TEXT | NOT NULL, `'anonymous'` | 읽는 코드 없음 (초기 설계의 잔재) |

인덱스: `idx_img_user(user_id)`

---

## 5. 문서·큐레이션 테이블

### 5.1 `doc_registry` — 인덱싱된 문서 하나

| 컬럼 | 타입 | 제약·기본값 | 의미 |
|---|---|---|---|
| `doc_id` | TEXT | PK ①, NOT NULL | `{파일명}_{원본 SHA-256 앞 8자}` |
| `user_id` | TEXT | PK ②, NOT NULL, `'anonymous'` | 항상 `shared` |
| `sha256` | TEXT | NOT NULL | 원본 파일 SHA-256 — 디렉터리 동기화의 변경 감지 기준 |
| `version` | TEXT | NOT NULL | 문서 버전 |
| `indexed_at` | TEXT | NOT NULL | 인덱싱 시각 (ISO-8601 UTC) |
| `chunks` | INTEGER | NOT NULL | 청크 수. **0 = 부분 저장 행**(변환은 됐지만 청크·임베딩 전에 실패) — 동기화가 "색인됨"으로 치지 않아 다음 동기화에서 다시 시도합니다 |
| `spring_doc_ids` | TEXT | NOT NULL | 이 문서의 청크 id JSON 배열 — 삭제·재인덱싱이 지울 벡터·FTS 행 목록 |
| `errors` | TEXT | NOT NULL | 인덱싱 경고·오류 JSON 배열 |
| `chunk_overlap` | INTEGER | | 인덱싱 당시 `app.chunk-overlap` — 문서 내보내기가 청크를 재조립할 때 씁니다. NULL = 컬럼 이전 행 (기동 시 `ChunkOverlapBackfill`이 채움) |
| `display_name` | TEXT | | 화면용 별칭. NULL = 실제 파일명을 표시 |
| `tags` | TEXT | | 검색 스코프 태그 CSV — **태그의 권위 있는 출처**(문서 목록·태그 제안 UI가 읽음). NULL = 아직 백필 안 됨, 빈 문자열 = 태그 없음 |

인덱스: PK(`doc_id`, `user_id`), `idx_doc_registry_user_version(user_id, version)`, `idx_doc_registry_sha_version(sha256, version, user_id)`

> **파일명 컬럼은 없습니다** — `doc_id`의 앞부분이 파일명입니다(`DocRegistry.filenameFromDocId`). 이를 모르고 `filename`을 조회한 코드가 대화 열기를 500으로 만든 적이 있습니다(2026-09-28).
>
> **`put()`의 UPSERT는 `tags`를 건드리지 않습니다** — 재인덱싱이 태그를 보존하는 근거이고, 그래서 인덱싱 경로가 `updateTags()`를 따로 부릅니다. 태그를 쓰는 경로는 세 곳(`doc_registry.tags` · 벡터 메타데이터 `tags` · `chunk_fts.doc_tags`)을 함께 고쳐야 합니다.

### 5.2 `curated_qa` — 큐레이션 Q&A 한 건

관리자가 승인한 지식 제안입니다. 검색 코퍼스로 들어가는 유일한 문이 이 승인입니다([PITFALLS § CuratedQaService](PITFALLS.md#servicecuratedqaservicejava)).

| 컬럼 | 타입 | 제약·기본값 | 의미 |
|---|---|---|---|
| `id` | INTEGER | PK, AUTOINCREMENT | 청크 id `curated-{id}`(둘째 청크부터 `-{n}`)와 `doc_id` `curated:{id}`의 원천 |
| `source_turn_id` | INTEGER | 부분 UNIQUE | 좋아요 출신이면 원본 턴 id, 직접 작성한 제안이면 NULL |
| `source_user_id` | TEXT | NOT NULL | 원본 턴의 사용자 / 제안 저자 |
| `source_thread_id` | TEXT | NOT NULL | 원본 대화 id — 직접 작성한 제안은 빈 문자열 |
| `question`, `answer` | TEXT | NOT NULL | 턴과 독립된 **사본** — 고쳐도 원본 턴은 그대로입니다 |
| `status` | TEXT | NOT NULL, `'active'` | `active`(검색에 기여) / `inactive`(내려감) |
| `source_doc_version` | TEXT | | 원본 턴의 문서 버전 (직접 작성은 NULL) |
| `created_at`, `updated_at` | TEXT | NOT NULL | 서버 로컬 시각 |
| `embed_status` | TEXT | NOT NULL, `'ok'` | `ok` / `failed` (임베딩 실패 — 제안 화면의 "임베딩 실패") |
| `origin` | TEXT | NOT NULL, `'like'` | `like`(좋아요 출신) / `manual`(직접 작성) — 벡터 메타데이터 `curated_origin`으로도 실립니다 |
| `source_submission_id` | INTEGER | | → `curated_submission.id`. **반드시 채웁니다** — 비면 제안이 현실과 조용히 끊깁니다(청크 0개인 "등록 완료"로 보임) |
| `tags` | TEXT | | 검색 스코프 태그 CSV — 비어 있으면 어떤 태그 선택에서도 탈락하지 않습니다 |
| `chunk_count` | INTEGER | NOT NULL, 1 | 이 행이 가진 벡터 수 (긴 답변은 나눠 임베딩) |
| `summary`, `keywords` | TEXT | | 요약·키워드 — 벡터 메타데이터 `chunk_context`·`excerpt_keywords`의 **단일 출처**(재임베딩할 때마다 여기서 다시 씀). NULL = 필드 이전 행, 빈 문자열 = 저자가 비워 둠 |

인덱스: `idx_curated_qa_turn` UNIQUE(`source_turn_id`) WHERE `source_turn_id IS NOT NULL`, `idx_curated_qa_status(status)`, `idx_curated_qa_submission(source_submission_id)` WHERE `source_submission_id IS NOT NULL`

> `deactivate(turnId)`는 직접 작성 행(`source_turn_id`가 NULL)에 조용히 아무 일도 하지 않습니다 — 행 id로 내리는 `deactivateById()`를 쓰세요.

### 5.3 `curated_submission` — 지식 제안 한 건

| 컬럼 | 타입 | 제약·기본값 | 의미 |
|---|---|---|---|
| `id` | INTEGER | PK, AUTOINCREMENT | |
| `author_user_id` | TEXT | NOT NULL | 저자 |
| `title`, `body` | TEXT | NOT NULL | 제목·본문 (본문에 이미지 마커가 들어갈 수 있음) |
| `status` | TEXT | NOT NULL, `'pending'` | `pending` / `approved` / `rejected` / `withdrawn`. 화면의 "회수됨"(`revoked`)은 저장값이 아니라 연결된 `curated_qa` 행들로 계산합니다 |
| `reviewer_user_id`, `review_note`, `reviewed_at` | TEXT | | 검토자·검토 메모·검토 시각 |
| `curated_qa_id` | INTEGER | | 승인으로 만들어진 첫 `curated_qa` 행 |
| `created_at`, `updated_at` | TEXT | NOT NULL | 서버 로컬 시각 |
| `author_read_at` | TEXT | | 저자가 검토 결과를 확인한 시각 — NULL이면 미확인 알림 배지에 잡힙니다(승인·반려할 때 NULL로 되돌림) |
| `tags` | TEXT | | 저자가 고른 태그 CSV |
| `source_turn_id` | INTEGER | | 좋아요 출신 제안의 원본 턴 (직접 작성은 NULL) |
| `source_thread_id` | TEXT | | 좋아요 출신 제안의 원본 대화 |
| `summary`, `keywords` | TEXT | | 저자가 쓰거나 "빈 칸 자동 생성"으로 채운 요약·키워드 — 승인 시 `curated_qa`로 복사 |

인덱스: `idx_curated_sub_turn(source_turn_id)` WHERE `source_turn_id IS NOT NULL`(UNIQUE 아님 — 반려·철회된 제안이 같은 턴에 남는 것이 정상), `idx_curated_sub_status(status, id DESC)`, `idx_curated_sub_author(author_user_id, id DESC)`

### 5.4 `chunk_report` — 청크 오류 신고 한 건

**대기열일 뿐입니다** — 신고는 검색·재사용·벡터 어디에도 영향이 없고, 반영은 관리자가 청크를 실제로 고칠 때 일어납니다([PITFALLS § ChunkReportService](PITFALLS.md#servicechunkreportservicejava)).

| 컬럼 | 타입 | 제약·기본값 | 의미 |
|---|---|---|---|
| `id` | INTEGER | PK, AUTOINCREMENT | |
| `chunk_id` | TEXT | NOT NULL | 신고된 청크 id |
| `doc_id`, `version`, `filename` | TEXT | | 신고 시점 청크 위치 |
| `reporter_user_id` | TEXT | NOT NULL | 신고자 |
| `thread_id`, `turn_id`, `question` | | | 신고가 나온 대화·턴과 그 질문 스냅샷 |
| `reason_code` | TEXT | NOT NULL | `WRONG`(사실이 틀림) / `OUTDATED`(오래됨) / `BROKEN`(깨진 텍스트·표·이미지) / `OTHER` |
| `comment` | TEXT | NOT NULL | 신고 코멘트 (필수) |
| `chunk_hash`, `chunk_snapshot` | TEXT | | 신고 **시점**의 청크 해시·텍스트 — 관리자 화면이 지금 내용과 비교합니다(`ChunkDiff`) |
| `status` | TEXT | NOT NULL, `'open'` | `open` / `resolved` / `rejected` |
| `reviewer_user_id`, `review_note`, `reviewed_at` | TEXT | | 처리 정보 |
| `created_at` | TEXT | NOT NULL | 서버 로컬 시각 |

인덱스: `idx_chunk_report_open(status, chunk_id)`, `idx_chunk_report_chunk(chunk_id, id DESC)`, `idx_chunk_report_dup` UNIQUE(`chunk_id`, `reporter_user_id`, `thread_id`) WHERE `status = 'open'`

> 중복 방지 키에 `thread_id`가 들어가는 이유: 공유 게스트 전략에서는 모든 방문자의 사용자 id가 같아서, (청크, 신고자)로만 막으면 청크당 한 명만 신고할 수 있게 됩니다. 처리된 신고는 재신고를 막지 않습니다(부분 인덱스).

---

## 6. 운영·계정·시스템 테이블

### 6.1 `llm_usage` — (프로바이더, 날짜)별 토큰 사용량

호출할 때마다 그날 행에 더합니다(UPSERT).

| 컬럼 | 타입 | 제약·기본값 | 의미 |
|---|---|---|---|
| `provider_name` | TEXT | PK ①, NOT NULL | 채팅 호출은 프로바이더 이름 그대로 (예: `local`). 나머지는 **용도 접두사 + 프로바이더 이름**입니다 — 아래 표 |
| `usage_date` | TEXT | PK ②, NOT NULL | UTC 날짜 |
| `input_tokens`, `output_tokens`, `call_count` | INTEGER | NOT NULL, 0 | 그날 누적값 |
| `user_id` | TEXT | NOT NULL, `'anonymous'` | 쓰는 코드 없음 (사용자별 할당량을 대비해 추가됨) |

인덱스: PK(`provider_name`, `usage_date`), `idx_llm_usage_date(usage_date)`

| 접두사 | 용도 |
|---|---|
| `embed:` + 모델 이름 | 임베딩 (`TrackingEmbeddingModel`) |
| `summary:` | 대화 요약 |
| `context:` | 인덱싱 키워드+맥락 추출 (예전 행은 `keyword:`) |
| `mdcorrect:` | MD 포맷 교정 |
| `txt2md:` | TXT → MD 구조화 |
| `title:` | 대화 제목 생성 |
| `image:` | 인덱싱 시점 이미지 설명 (검색 시점 Vision은 접두사 없이 프로바이더 이름으로 기록) |
| `question:` | 큐레이션 질문 구체화 제안 |

접두사 목록의 출처는 `BackgroundUsage`입니다.

### 6.2 `settings_override` — `/settings` 핫 편집 값

| 컬럼 | 타입 | 제약·기본값 | 의미 |
|---|---|---|---|
| `key` | TEXT | PK | 설정 키 (`SettingsKeys` — 예: `search-top-k`, `llm.temperature`, `ui.retrieval-metrics-enabled`) |
| `value` | TEXT | NOT NULL | 값 (문자열) |
| `updated_at` | TEXT | NOT NULL | ISO-8601 UTC |

> 행을 지우면 그 키는 `application.properties` 기본값으로 돌아갑니다. 앱은 기동할 때 이 테이블을 메모리 캐시에 올리므로, **실행 중에 SQL로 고친 값은 재기동 전까지 반영되지 않습니다** — `/settings` 화면을 쓰세요.

### 6.3 `app_secret` — 서버 비밀값

| 컬럼 | 타입 | 제약·기본값 | 의미 |
|---|---|---|---|
| `name` | TEXT | PK | 현재 `guest-identity-hmac` 하나 |
| `value` | TEXT | NOT NULL | 256비트 난수 (hex) — **민감 정보**. 로그나 문서로 옮기지 마세요 |
| `created_at` | TEXT | NOT NULL | ISO-8601 UTC |

> 방문자별 게스트 식별(`guest-identity` = `ip`/`cookie`/`hybrid`)이 쓰는 HMAC 키입니다. 앱은 처음 쓸 때 이 값을 메모리에 올려 두므로, 행을 지우면 **다음 기동 때** 새 키가 만들어져 모든 방문자의 id가 바뀌고 그 방문자들의 대화 이력이 고아가 됩니다. `/settings`에 노출되지 않도록 `settings_override`와 분리돼 있습니다.

### 6.4 `users` · `persistent_logins` — 계정

**`users`**

| 컬럼 | 타입 | 제약·기본값 | 의미 |
|---|---|---|---|
| `id` | TEXT | PK | UUID |
| `email` | TEXT | UNIQUE, NOT NULL | 소문자로 저장 |
| `password_hash` | TEXT | NOT NULL | BCrypt (cost 12) |
| `display_name` | TEXT | | |
| `role` | TEXT | NOT NULL, `'USER'` | `USER` / `ADMIN` — `/signup`은 `USER`만, `/setup`만 `ADMIN`을 만듭니다 |
| `enabled` | INTEGER | NOT NULL, 1 | |
| `failed_count` | INTEGER | NOT NULL, 0 | 연속 로그인 실패 수 |
| `locked_until` | TEXT | | 잠금 해제 시각 (UTC) — 5회 실패 시 15분 잠금 |
| `created_at`, `updated_at` | TEXT | NOT NULL | UTC |

인덱스: `idx_users_email(email)`, `email` UNIQUE 자동 인덱스

**`persistent_logins`** — Spring Security remember-me(`JdbcTokenRepositoryImpl`)의 표준 스키마입니다(`username`, `series` PK, `token`, `last_used`). **현재 remember-me를 켜는 코드가 없어 항상 비어 있습니다.**

### 6.5 `flyway_schema_history`

Flyway 표준 테이블입니다 — `installed_rank`(PK), `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`. 새로 만든 DB에는 V1~V3 적용 기록이, 런타임 DDL로 만들어진 옛 파일에는 `<< Flyway Baseline >>`(버전 3) 한 줄이 남습니다(§2.1). 앱 코드는 이 테이블을 읽지 않습니다.

---

## 7. 키워드 검색 색인 — 두 백엔드 공통

하이브리드 검색의 BM25 축입니다. 벡터 백엔드와 상관없이 **항상** 이 SQLite 파일에 만들어집니다. SQLite 빌드에 FTS5가 없으면 조용히 꺼집니다(하이브리드 검색 비활성).

### 7.1 `chunk_fts` — FTS5 가상 테이블

```sql
CREATE VIRTUAL TABLE chunk_fts USING fts5(
    spring_doc_id UNINDEXED,
    doc_id        UNINDEXED,
    version       UNINDEXED,
    filename      UNINDEXED,
    page          UNINDEXED,
    chapter       UNINDEXED,
    chunk_index   UNINDEXED,
    doc_tags      UNINDEXED,
    content,
    keywords,
    tokenize = 'trigram'
)
```

| 컬럼 | 색인 | 내용 |
|---|---|---|
| `spring_doc_id` | UNINDEXED | 청크 id |
| `doc_id` | UNINDEXED | 문서 id |
| `version` | UNINDEXED | 문서 버전 — 검색 조건 `version = ?` |
| `filename`, `page`, `chapter`, `chunk_index` | UNINDEXED | 위치 — 메타데이터 `filename`·`page_or_slide`·`chapter_no`·`chunk_index`의 사본 |
| `doc_tags` | UNINDEXED | 태그 CSV — 키워드 축 결과에 태그를 동행시키는 **사본**입니다(권위 있는 출처는 `doc_registry.tags`) |
| `content` | 색인 | **파생 검색 텍스트** = `chunk_context` + 정규화된 원문(`SearchTextBuilder.build()`). 원문이 아닙니다 |
| `keywords` | 색인 | 메타데이터 `excerpt_keywords` |

- 검색: `WHERE chunk_fts MATCH ? AND version = ? ORDER BY bm25(chunk_fts) LIMIT ?`
- `trigram` 토크나이저는 3글자 창으로 색인해 부분 문자열로 찾습니다("인덱싱"으로 "인덱싱됩니다"를 찾음). **3글자보다 짧은 검색어는 버려집니다** — 2글자 한국어 질의는 이 축에 기여하지 않습니다([PITFALLS](PITFALLS.md#chunk_fts-의-trigram-토크나이저는-3글자-이상이어야-토큰이-나온다)).
- ⚠️ **UNINDEXED 컬럼으로 거는 `WHERE`는 코퍼스 전체 스캔입니다** — FTS5는 `MATCH`와 `rowid`만 인덱스로 풉니다. 청크 id나 문서 id로 행을 찾을 때는 반드시 `chunk_fts_key`를 먼저 거칩니다.
- 큐레이션 행도 여기 있습니다(`version = 'curated'`). 큐레이션의 FTS 검색 텍스트에는 요약이 앞에 붙습니다 — 벡터 입력에는 붙지 않습니다.

### 7.2 `chunk_fts_key` — `chunk_fts`를 id로 찾는 색인

| 컬럼 | 타입 | 제약·기본값 | 의미 |
|---|---|---|---|
| `spring_doc_id` | TEXT | PK | 청크 id |
| `fts_rowid` | INTEGER | NOT NULL | → `chunk_fts.rowid` |
| `doc_id` | TEXT | | 문서 id |
| `version`, `filename`, `page`, `chapter` | TEXT | | 위치 사본 |
| `content_hash` | TEXT | NOT NULL | 파생 검색 텍스트의 SHA-256 — `turn_source_ref.chunk_hash`와 같은 함수(`KeywordSearchRepository.contentHash`). 이 함수를 바꾸면 저장된 스냅샷이 전부 "수정됨"으로 읽히므로 마이그레이션이 함께 가야 합니다 |

인덱스: PK(`spring_doc_id`), `idx_chunk_fts_key_doc(doc_id)`

- `chunk_fts`와 **한 트랜잭션**에서 함께 쓰고 함께 지웁니다 — 한쪽만 남으면 id 조회가 조용히 비어 옵니다. rowid는 앱이 직접 정합니다(`MAX(rowid)+1`부터, pool=1이라 안전).
- 기동할 때 두 테이블의 행 수를 맞춥니다 — 키가 모자라면 `chunk_fts`를 rowid 순으로 훑어 채우고, 남으면 FTS 행이 사라진 키를 지웁니다.

---

## 8. 벡터 저장소

### 8.1 공통 모델

청크 하나 = **id + 원문 + 메타데이터 + 벡터**입니다. 백엔드(`app.vectorstore.type`)에 따라 담는 곳만 다르고, 둘 다 `VectorStoreProvider` 뒤에 있어 나머지 코드는 차이를 모릅니다.

| | sqlite-vec | Chroma |
|---|---|---|
| 벡터 | `vec_embeddings` (vec0) | 컬렉션 레코드의 embedding |
| 원문 | `vec_document_chunks.content` | 레코드의 document |
| 메타데이터 | `vec_document_chunks.metadata` (JSON) | 레코드의 metadata |
| 버전 구분 | vec0 파티션 키 `version` | 버전마다 별도 컬렉션 |
| 거리 | cosine (`distance_metric=cosine`) | cosine (`hnsw:space=cosine` — Spring AI `ChromaApi`가 컬렉션을 만들 때 지정) |

두 백엔드 공통 규칙:

- **저장하는 텍스트 ≠ 임베딩하는 텍스트**(§10.1 Contextual Retrieval). 저장·표시되는 것은 원문이고, 임베딩과 FTS에 들어가는 것은 `chunk_context` + 마크다운 장식을 걷어낸 원문(`SearchTextBuilder.build()`)입니다. 이 파생 텍스트는 저장하지 않습니다 — 계산 결과를 잠시 담는 메타데이터 키 `search_text`는 저장하기 전에 지웁니다.
- 유사도 = `1 − distance`입니다. `app.search-similarity-threshold`(기본 0.3)보다 낮은 결과는 버리고, 임계값이 0보다 크면 KNN을 topK×2로 넉넉히 가져온 뒤 거릅니다.
- 버전 `curated`는 큐레이션 축 전용 네임스페이스입니다 — 문서 검색(`latest` 등)에 섞이지 않고, 큐레이션 축이 따로 묻습니다.
- 인덱싱 중의 임베딩은 검색어 캐시를 거치지 않고(검색어 캐시를 밀어내지 않도록), 토큰 수 기준 서브배치로 나눠 보냅니다.
- **백엔드를 바꾸면 전체 재인덱싱이 필요합니다** — 두 저장소는 벡터를 공유하지 않습니다. 임베딩 모델(=차원)을 바꿀 때도 마찬가지입니다([OPERATOR_MANUAL](OPERATOR_MANUAL.md) 환경 변수 표의 `EMBED_MODEL`·`EMBED_DIMENSIONS`).

### 8.2 sqlite-vec

```sql
CREATE VIRTUAL TABLE vec_embeddings USING vec0(
    spring_doc_id TEXT PRIMARY KEY,
    version TEXT partition key,
    embedding FLOAT[<app.embedding.dimensions>] distance_metric=cosine
);

CREATE TABLE vec_document_chunks (
    spring_doc_id TEXT PRIMARY KEY,
    content       TEXT NOT NULL,
    metadata      TEXT NOT NULL,
    version       TEXT NOT NULL,
    doc_id        TEXT NOT NULL,
    user_scope    TEXT NOT NULL DEFAULT 'shared',
    created_at    TEXT NOT NULL
);
```

**`vec_embeddings`** — vec0 가상 테이블

| 컬럼 | 선언 | 의미 |
|---|---|---|
| `spring_doc_id` | `TEXT PRIMARY KEY` | 청크 id |
| `version` | `partition key` | 문서 버전 — KNN이 이 파티션 안에서만 돌기 때문에 버전 필터에 조인이나 과다 조회가 필요 없습니다 |
| `embedding` | `FLOAT[n] distance_metric=cosine` | 벡터. `n` = `app.embedding.dimensions` |

- **차원은 테이블을 만들 때 고정됩니다.** DDL이 `IF NOT EXISTS`라 설정값을 바꿔도 기존 테이블은 그대로입니다 — 모델·차원을 바꾸려면 `vec_embeddings`를 지우고 전체 재인덱싱해야 합니다.
- 벡터는 little-endian float32 BLOB으로 넣습니다(`SqliteVecVectorStoreProvider.toVectorBlob`).
- vec0는 `INSERT OR REPLACE`를 지원하지 않습니다 — 같은 id를 다시 넣을 때는 먼저 지웁니다(`add()`가 항상 그렇게 함).
- **vec0 확장을 로드한 커넥션에서만 읽힙니다.** 앱은 커넥션마다 확장을 로드하고, 기동할 때 `vec_version()`으로 확인합니다(`SqliteVecVerifier`). 일반 `sqlite3` 클라이언트로 열면 `no such module: vec0` 오류가 납니다 — 텍스트와 메타데이터는 `vec_document_chunks`에서 보면 됩니다.

**`vec_document_chunks`** — 청크 원문 + 메타데이터

| 컬럼 | 타입 | 제약·기본값 | 의미 |
|---|---|---|---|
| `spring_doc_id` | TEXT | PK | 청크 id (= `vec_embeddings.spring_doc_id`) |
| `content` | TEXT | NOT NULL | **원문** 청크 텍스트 — 출처 미리보기·원문 보기·`/admin` 청크 화면이 이것을 보여 줍니다 |
| `metadata` | TEXT | NOT NULL | 메타데이터 JSON (§8.4) |
| `version` | TEXT | NOT NULL | 문서 버전 |
| `doc_id` | TEXT | NOT NULL | 문서 id |
| `user_scope` | TEXT | NOT NULL, `'shared'` | 항상 `shared` |
| `created_at` | TEXT | NOT NULL | 저장 시각 (ISO-8601 UTC) |

인덱스: PK(`spring_doc_id`), `idx_vec_chunks_version(version)`, `idx_vec_chunks_docid(doc_id)`

검색 쿼리 — vec0 KNN을 먼저 돌리고, 조인은 텍스트와 메타데이터를 붙이는 데만 씁니다:

```sql
SELECT c.spring_doc_id, c.content, c.metadata, knn.distance
FROM (
    SELECT spring_doc_id, distance FROM vec_embeddings
    WHERE embedding MATCH ? AND k = ? AND version = ?
) knn
JOIN vec_document_chunks c ON c.spring_doc_id = knn.spring_doc_id
ORDER BY knn.distance
```

- 쓰기는 서브배치마다 `vec_embeddings` + `vec_document_chunks`를 **한 트랜잭션**에 넣습니다 — 중간에 실패해도 짝 없는 벡터가 남지 않습니다.
- 태그 변경은 `metadata` JSON의 `tags`만 고쳐 씁니다(재임베딩 없음).

### 8.3 Chroma

- 서버 주소는 `spring.ai.vectorstore.chroma.client.host`/`port`(기본 `http://localhost:8001`)이고, 기본 tenant·database를 씁니다.
- **컬렉션 하나 = (소유자, 버전)** — 이름은 `u_{소유자 id 앞 8자}_{버전}`입니다(하이픈 제거, 영숫자 외 문자는 `_`, 최대 63자). 소유자가 항상 `shared`이므로 실제 이름은 `u_shared_latest`, 큐레이션은 `u_shared_curated`입니다(`VectorStoreRegistry`).
- 레코드 하나 = id(청크 id) + embedding(파생 검색 텍스트의 벡터) + document(원문) + metadata(§8.4의 키, `search_text` 제외).
- 쓰기는 Spring AI의 `VectorStore.add()`가 아니라, 앱이 직접 임베딩한 뒤 `upsertEmbeddings`로 넣습니다 — `add()`는 저장하는 텍스트를 그대로 임베딩해서 "저장 ≠ 임베딩"(§8.1)을 깨뜨립니다.
- 검색은 질의 하나면 Spring AI의 `similaritySearch`로, 여러 개(MultiQuery 변형)면 `queryCollection` 한 번으로 보냅니다. 일괄 검색은 메타데이터·document·distance만 받습니다(embedding은 받지 않음).
- 태그 변경은 기존 레코드를 가져와 메타데이터만 고쳐 같은 벡터로 다시 upsert합니다(재임베딩 없음).
- Chroma 배포에서도 SQLite 파일에는 `chunk_fts`·`chunk_fts_key`가 있습니다. 대신 **원문은 SQLite에 없어서**, 다시 연 대화의 출처 미리보기나 청크 신고 비교처럼 SQLite에서 청크 텍스트를 읽는 곳은 FTS의 파생 검색 텍스트로 대신합니다.
- 백업할 때는 Chroma 볼륨과 `data/`를 **같은 시점에** 함께 보존하세요 — 인덱싱은 벡터 → FTS → 레지스트리 순으로 쓰고, 마지막 레지스트리 커밋이 "색인 완료"의 기준입니다.

### 8.4 청크 메타데이터 키

`MetaKey` 상수가 키 이름의 단일 출처입니다(코드에서 문자열을 직접 쓰지 않음). sqlite-vec는 `vec_document_chunks.metadata` JSON에, Chroma는 레코드 metadata에 같은 키를 담습니다.

| 키 | 타입 | 문서 청크 | 큐레이션 청크 | 설명 |
|---|---|---|---|---|
| `doc_id` | 문자열 | `{파일명}_{sha8}` | `curated:{id}` | 문서 id — 레지스트리·FTS와 연결 |
| `filename` | 문자열 | 원본 파일명 | `curated_qa` | 출처 라벨 |
| `version` | 문자열 | 문서 버전 | `curated` | |
| `doc_type` | 문자열 | 파일명으로 추정 — `guide`(이름에 guide) / `education`(edu·lesson) / `manual` | `curated_qa` | **`curated_qa`가 큐레이션 판정 키**입니다 — 출처 라벨("💬 큐레이션 Q&A")과 태그 필터 면제가 이 값을 봅니다 |
| `source_type` | 문자열 | `file` / `ocr`(스캔 PDF) | `curated_qa` | |
| `sha256` | 문자열 | 원본 파일 SHA-256 | — | |
| `collected_at` | 문자열 | 인덱싱 시각 (ISO-8601 UTC) | — | |
| `chunk_index` | 정수 | 문서 안 순번 (0부터) | 분할 순번 | 페이지와 별개인 안정적 청크 순번 |
| `page_or_slide` | 정수 | 페이지·슬라이드 번호 (없으면 청크 순번+1) | `1` | 출처 라벨 `p.N` |
| `chapter_no` | 문자열 | `1.5.3` 형식 (H2~H6 기준), 헤딩 이전 구간·PPTX는 `0` | — | 출처 라벨 `ch N` (`0`이면 페이지로 대신) |
| `heading` | 문자열 | 청크가 속한 섹션 제목 | — | `chunk_context`의 구조적 맥락 재료 |
| `heading_page` | 정수 | DOCX 헤딩이 시작하는 페이지 | — | |
| `section` | 정수 | 로더가 매긴 섹션 순번 | — | `MetaKey`에 없는 키입니다 — 청크 편집은 저장본 위에 병합하므로 보존됩니다 |
| `image_paths` | 문자열 (CSV) | 청크 본문에 남은 이미지 마커의 경로 | 본문 이미지 마커의 경로 | 답변 말풍선 썸네일 |
| `tags` | 문자열 (CSV) | 문서 태그 | 제안 태그 | 태그 필터(`filterByTags`). 태그가 없으면 키 자체가 없습니다 |
| `excerpt_keywords` | 문자열 | LLM이 추출한 키워드 | `curated_qa.keywords` | FTS `keywords` 컬럼. `/admin`에서 편집 가능 |
| `chunk_context` | 문자열 | 맥락 헤더 — `{파일명} > {heading}` + LLM이 쓴 1~2문장 | `curated_qa.summary` | 임베딩·FTS 입력 앞에 붙습니다. **영속 저장**되며 `/admin`에서 편집 가능 |
| `edited_at` | 문자열 | 관리자가 `/admin`에서 손으로 고친 시각 (ISO-8601 UTC) | — | 문서를 재인덱싱하면 MD에서 청크를 다시 만들므로 편집과 함께 사라집니다 — 재인덱싱 사전 확인의 "편집된 청크 N개" 경고 근거 |
| `curated_origin` | 문자열 | — | `like` / `manual` | 감사·통계용 (검색 분기에는 쓰지 않음) |
| `owner_id` | 문자열 | 인덱싱 요청의 소유자 | — | 미래 확장용 — 읽는 코드 없음 |
| `visibility` | 문자열 | 기본 `private` | — | 미래 확장용 — 읽는 코드 없음 |
| `search_text` | — | — | — | **임시 키** — 파생 검색 텍스트를 한 번만 계산해 담아 두는 자리. 저장하기 전에 반드시 지웁니다 |

`MetaKey.TENANT_ID`는 상수만 있고 쓰는 코드가 없습니다.

### 8.5 청크의 생애 — 쓰기·검색·수정·삭제

1. **인덱싱**(`DocumentIndexer`) — 문서를 읽어 청크로 나누고(`ChunkSplitter`) → 메타데이터를 붙이고 → `KeywordExtractor`가 `excerpt_keywords`·`chunk_context`를 더하고 → 벡터 저장 → `chunk_fts` + `chunk_fts_key` → `doc_registry.put()`(+ `updateTags()`). 레지스트리 커밋이 마지막이라 "색인 완료"의 기준입니다.
2. **검색**(`RetrievalService`) — 벡터 축(원문 질의 + MultiQuery 변형, 버전 파티션·컬렉션), 키워드 축(`chunk_fts`, 같은 버전), 큐레이션 축(버전 `curated`의 벡터 + BM25)을 RRF로 합친 뒤 → 태그 필터 → (리랭크) → topK.
3. **턴 저장**(`TurnPersistence`) — 출처마다 `turn_source_ref` 한 행(청크 id, `chunk_fts_key.content_hash`, 응답 참여도, 위치 스냅샷)을 남기고, 턴에 `retrieval_metrics`·`verification`을 기록합니다.
4. **대화를 다시 열 때** — `turn_source_ref` → `chunk_fts_key` → `chunk_fts`(rowid) + `vec_document_chunks`로 지금의 위치·원문을 읽습니다. 청크가 없으면 위치 스냅샷을, 그것도 없으면 `doc_id`의 파일명 부분으로 라벨을 만듭니다.
5. **청크 편집**(`/admin`) — 저장은 본문과 `excerpt_keywords`·`chunk_context`만 저장본 위에 병합하고 `edited_at`을 찍습니다. 벡터는 그대로이고, 청크 재인덱싱(↺)을 눌러야 **같은 청크 id로** 다시 임베딩되고 FTS도 다시 색인됩니다. 청크를 수정·삭제하는 경로는 모두 `QuestionReuseService`에 알려, 그 청크를 출처로 가진 `turn_source_ref`가 `modified`·`deleted`가 됩니다.
6. **태그 변경**(`RagService.updateDocumentTags`) — `doc_registry.tags` · 벡터 메타데이터 `tags` · `chunk_fts.doc_tags` 세 곳을 함께 고칩니다(재임베딩 없음).
7. **문서 재인덱싱·삭제** — `doc_registry.spring_doc_ids`로 옛 벡터·FTS 행을 지웁니다. 재인덱싱은 **새 청크 id**로 다시 만들므로, 옛 청크를 출처로 가진 턴은 `deleted`가 됩니다.

---

## 9. 실제 스키마 확인하기 · 이 문서 갱신

실제 DB의 테이블과 컬럼은 읽기 전용으로 열어 확인합니다. 앱이 실행 중이어도 WAL 모드라 읽기는 안전합니다. 쓰기는 앱을 내린 뒤에 하세요 — 앱은 커넥션 하나로 쓰고, 설정처럼 메모리에 올려 둔 값은 재기동 전까지 바뀌지 않습니다.

```bash
python - <<'EOF'
import sqlite3
con = sqlite3.connect("file:data/memory.db?mode=ro", uri=True)
for (name,) in con.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").fetchall():
    try:
        print(name, [r[1] for r in con.execute(f'PRAGMA table_info("{name}")')])
    except sqlite3.OperationalError as e:
        print(name, f"({e})")
EOF
```

`vec_embeddings`는 vec0 확장이 없으면 조회할 수 없어 오류만 찍힙니다(§8.2).

**이 문서를 고쳐야 할 때** — 테이블이나 컬럼을 추가했을 때(런타임 `ALTER`), 메타데이터 키를 추가했을 때, 벡터·FTS 스키마를 바꿨을 때. 위 스크립트로 실제 DB와 대조한 뒤 해당 표를 고치고, 맨 위의 기준 날짜를 갱신하세요.
