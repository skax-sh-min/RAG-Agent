-- V4 이전의 런타임 DDL — 저장소 클래스들이 기동할 때마다 실행하던 문장을, 실행 순서 그대로 옮긴 기록이다.
--
-- V4(db.migration.V4__Consolidate_runtime_schema)가 이 DDL 을 Flyway 로 옮기면서 코드에서는 사라졌다.
-- 옛 배포의 DB 는 전부 이 문장들로 만들어졌으므로, 그 DB 를 재현해 V4 가 현재 스키마로 수렴시키는지
-- 확인하는 테스트(FlywaySchemaConvergenceTest)의 재료로 남긴다. **고치지 않는다** — 옛 코드의 사진이다.
--
-- 옛 코드는 ALTER TABLE ... ADD COLUMN 을 늘 실행하고 "duplicate column name" 을 삼켰으므로(일부는
-- PRAGMA table_info 로 먼저 확인했다 — 결과는 같다), 재생기도 그 오류만 무시한다.

-- SqliteMemoryRepository.init()
CREATE TABLE IF NOT EXISTS conversation_turns (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    thread_id  TEXT NOT NULL,
    question   TEXT NOT NULL,
    answer     TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_thread_id ON conversation_turns(thread_id);
ALTER TABLE conversation_turns ADD COLUMN asked_at TEXT;
ALTER TABLE conversation_turns ADD COLUMN input_tokens INTEGER DEFAULT 0;
ALTER TABLE conversation_turns ADD COLUMN output_tokens INTEGER DEFAULT 0;
ALTER TABLE conversation_turns ADD COLUMN elapsed_ms INTEGER DEFAULT 0;
ALTER TABLE conversation_turns ADD COLUMN provider TEXT;
ALTER TABLE conversation_turns ADD COLUMN llm_calls INTEGER DEFAULT 0;
ALTER TABLE conversation_turns ADD COLUMN user_id TEXT NOT NULL DEFAULT 'anonymous';
ALTER TABLE conversation_turns ADD COLUMN feedback TEXT;
ALTER TABLE conversation_turns ADD COLUMN response_mode TEXT;
ALTER TABLE conversation_turns ADD COLUMN selected_tags TEXT;
ALTER TABLE conversation_turns ADD COLUMN reused_from_turn_id INTEGER;
ALTER TABLE conversation_turns ADD COLUMN direct_mode INTEGER NOT NULL DEFAULT 0;
ALTER TABLE conversation_turns ADD COLUMN retrieval_metrics TEXT;
ALTER TABLE conversation_turns ADD COLUMN verification TEXT;
CREATE INDEX IF NOT EXISTS idx_turns_user_thread ON conversation_turns(user_id, thread_id);
CREATE INDEX IF NOT EXISTS idx_turns_reused_from ON conversation_turns(reused_from_turn_id);
CREATE TABLE IF NOT EXISTS turn_image_ref (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    turn_id    INTEGER NOT NULL,
    user_id    TEXT NOT NULL,
    thread_id  TEXT NOT NULL,
    image_ref  TEXT NOT NULL,
    status     TEXT NOT NULL DEFAULT 'active',
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_turn_image_turn ON turn_image_ref(turn_id);
CREATE INDEX IF NOT EXISTS idx_turn_image_user_thread ON turn_image_ref(user_id, thread_id);
CREATE TABLE IF NOT EXISTS image_descriptions (
    image_path  TEXT    PRIMARY KEY,
    description TEXT    NOT NULL,
    image_type  TEXT,
    provider    TEXT,
    created_at  TEXT    NOT NULL DEFAULT (datetime('now'))
);
ALTER TABLE image_descriptions ADD COLUMN user_id TEXT NOT NULL DEFAULT 'anonymous';
CREATE INDEX IF NOT EXISTS idx_img_user ON image_descriptions(user_id);

-- ThreadMetaRepository.init()
CREATE TABLE IF NOT EXISTS thread_meta (
    thread_id    TEXT PRIMARY KEY,
    title        TEXT NOT NULL DEFAULT '새 대화',
    version      TEXT NOT NULL DEFAULT 'latest',
    created_at   TEXT NOT NULL,
    updated_at   TEXT NOT NULL,
    routing_mode TEXT NOT NULL DEFAULT 'COST_FIRST',
    tags         TEXT NOT NULL DEFAULT ''
);
ALTER TABLE thread_meta ADD COLUMN routing_mode TEXT NOT NULL DEFAULT 'COST_FIRST';
ALTER TABLE thread_meta ADD COLUMN user_id TEXT NOT NULL DEFAULT 'anonymous';
CREATE INDEX IF NOT EXISTS idx_thread_meta_user ON thread_meta(user_id);
ALTER TABLE thread_meta ADD COLUMN tags TEXT NOT NULL DEFAULT '';

-- QuestionReuseRepository.init()
CREATE TABLE IF NOT EXISTS turn_source_ref (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    turn_id     INTEGER NOT NULL,
    user_id     TEXT NOT NULL,
    thread_id   TEXT NOT NULL,
    chunk_id    TEXT NOT NULL,
    doc_id      TEXT,
    chunk_hash  TEXT NOT NULL,
    status      TEXT NOT NULL DEFAULT 'active',
    created_at  TEXT NOT NULL DEFAULT (datetime('now'))
);
ALTER TABLE turn_source_ref ADD COLUMN answer_share REAL;
ALTER TABLE turn_source_ref ADD COLUMN invalidated_at TEXT;
ALTER TABLE turn_source_ref ADD COLUMN hidden_at TEXT;
ALTER TABLE turn_source_ref ADD COLUMN filename TEXT;
ALTER TABLE turn_source_ref ADD COLUMN page_or_slide TEXT;
ALTER TABLE turn_source_ref ADD COLUMN chapter_no TEXT;
CREATE INDEX IF NOT EXISTS idx_turn_source_turn ON turn_source_ref(turn_id);
CREATE INDEX IF NOT EXISTS idx_turn_source_chunk ON turn_source_ref(chunk_id);

-- LlmUsageRepository.init()
CREATE TABLE IF NOT EXISTS llm_usage (
    provider_name  TEXT    NOT NULL,
    usage_date     TEXT    NOT NULL,
    input_tokens   INTEGER NOT NULL DEFAULT 0,
    output_tokens  INTEGER NOT NULL DEFAULT 0,
    call_count     INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (provider_name, usage_date)
);
CREATE INDEX IF NOT EXISTS idx_llm_usage_date ON llm_usage(usage_date);
ALTER TABLE llm_usage ADD COLUMN user_id TEXT NOT NULL DEFAULT 'anonymous';

-- CuratedQaRepository.init() — 이 모양이면 migrateLegacySchema() 는 돌지 않는다(origin 이 이미 있다).
CREATE TABLE IF NOT EXISTS curated_qa (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    source_turn_id        INTEGER,
    source_user_id        TEXT NOT NULL,
    source_thread_id      TEXT NOT NULL,
    question              TEXT NOT NULL,
    answer                TEXT NOT NULL,
    status                TEXT NOT NULL DEFAULT 'active',
    source_doc_version    TEXT,
    created_at            TEXT NOT NULL,
    updated_at            TEXT NOT NULL,
    embed_status          TEXT NOT NULL DEFAULT 'ok',
    origin                TEXT NOT NULL DEFAULT 'like',
    source_submission_id  INTEGER,
    tags                  TEXT,
    chunk_count           INTEGER NOT NULL DEFAULT 1,
    summary               TEXT,
    keywords              TEXT
);
ALTER TABLE curated_qa ADD COLUMN embed_status TEXT NOT NULL DEFAULT 'ok';
ALTER TABLE curated_qa ADD COLUMN tags TEXT;
ALTER TABLE curated_qa ADD COLUMN chunk_count INTEGER NOT NULL DEFAULT 1;
ALTER TABLE curated_qa ADD COLUMN summary TEXT;
ALTER TABLE curated_qa ADD COLUMN keywords TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS idx_curated_qa_turn ON curated_qa(source_turn_id) WHERE source_turn_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_curated_qa_status ON curated_qa(status);
CREATE INDEX IF NOT EXISTS idx_curated_qa_submission ON curated_qa(source_submission_id) WHERE source_submission_id IS NOT NULL;

-- CuratedSubmissionRepository.init()
CREATE TABLE IF NOT EXISTS curated_submission (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    author_user_id    TEXT NOT NULL,
    title             TEXT NOT NULL,
    body              TEXT NOT NULL,
    status            TEXT NOT NULL DEFAULT 'pending',
    reviewer_user_id  TEXT,
    review_note       TEXT,
    curated_qa_id     INTEGER,
    created_at        TEXT NOT NULL,
    updated_at        TEXT NOT NULL,
    reviewed_at       TEXT,
    author_read_at    TEXT,
    tags              TEXT,
    source_turn_id    INTEGER,
    source_thread_id  TEXT,
    summary           TEXT,
    keywords          TEXT
);
ALTER TABLE curated_submission ADD COLUMN tags TEXT;
ALTER TABLE curated_submission ADD COLUMN source_turn_id INTEGER;
ALTER TABLE curated_submission ADD COLUMN source_thread_id TEXT;
ALTER TABLE curated_submission ADD COLUMN summary TEXT;
ALTER TABLE curated_submission ADD COLUMN keywords TEXT;
CREATE INDEX IF NOT EXISTS idx_curated_sub_turn ON curated_submission(source_turn_id) WHERE source_turn_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_curated_sub_status ON curated_submission(status, id DESC);
CREATE INDEX IF NOT EXISTS idx_curated_sub_author ON curated_submission(author_user_id, id DESC);

-- SettingsOverrideRepository.init()
CREATE TABLE IF NOT EXISTS settings_override (
    key        TEXT NOT NULL PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

-- AppSecretRepository.init()
CREATE TABLE IF NOT EXISTS app_secret (
    name       TEXT NOT NULL PRIMARY KEY,
    value      TEXT NOT NULL,
    created_at TEXT NOT NULL
);

-- DocRegistry.init()
CREATE TABLE IF NOT EXISTS doc_registry (
    doc_id         TEXT NOT NULL,
    user_id        TEXT NOT NULL DEFAULT 'anonymous',
    sha256         TEXT NOT NULL,
    version        TEXT NOT NULL,
    indexed_at     TEXT NOT NULL,
    chunks         INTEGER NOT NULL,
    spring_doc_ids TEXT NOT NULL,
    errors         TEXT NOT NULL,
    PRIMARY KEY (doc_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_doc_registry_user_version ON doc_registry(user_id, version);
CREATE INDEX IF NOT EXISTS idx_doc_registry_sha_version ON doc_registry(sha256, version, user_id);
ALTER TABLE doc_registry ADD COLUMN chunk_overlap INTEGER;
ALTER TABLE doc_registry ADD COLUMN display_name TEXT;
ALTER TABLE doc_registry ADD COLUMN tags TEXT;

-- ChunkReportRepository.init()
CREATE TABLE IF NOT EXISTS chunk_report (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    chunk_id         TEXT NOT NULL,
    doc_id           TEXT,
    version          TEXT,
    filename         TEXT,
    reporter_user_id TEXT NOT NULL,
    thread_id        TEXT,
    turn_id          INTEGER,
    question         TEXT,
    reason_code      TEXT NOT NULL,
    comment          TEXT NOT NULL,
    chunk_hash       TEXT,
    chunk_snapshot   TEXT,
    status           TEXT NOT NULL DEFAULT 'open',
    reviewer_user_id TEXT,
    review_note      TEXT,
    created_at       TEXT NOT NULL,
    reviewed_at      TEXT
);
CREATE INDEX IF NOT EXISTS idx_chunk_report_open ON chunk_report(status, chunk_id);
CREATE INDEX IF NOT EXISTS idx_chunk_report_chunk ON chunk_report(chunk_id, id DESC);
CREATE UNIQUE INDEX IF NOT EXISTS idx_chunk_report_dup ON chunk_report(chunk_id, reporter_user_id, thread_id) WHERE status = 'open';

-- SqliteUserDetailsService.ensureAuthSchema()
CREATE TABLE IF NOT EXISTS users (
    id            TEXT PRIMARY KEY,
    email         TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    display_name  TEXT,
    role          TEXT    NOT NULL DEFAULT 'USER',
    enabled       INTEGER NOT NULL DEFAULT 1,
    failed_count  INTEGER NOT NULL DEFAULT 0,
    locked_until  TEXT,
    created_at    TEXT NOT NULL,
    updated_at    TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE TABLE IF NOT EXISTS persistent_logins (
    username  TEXT NOT NULL,
    series    TEXT PRIMARY KEY,
    token     TEXT NOT NULL,
    last_used TEXT NOT NULL
);
