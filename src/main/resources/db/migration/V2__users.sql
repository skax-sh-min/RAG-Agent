-- V2__users.sql
-- Creates the users table and persistent_logins for session-based authentication.

CREATE TABLE users (
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
CREATE INDEX idx_users_email ON users(email);

-- Stores Spring Security Remember-Me tokens (JdbcTokenRepositoryImpl)
CREATE TABLE persistent_logins (
    username  TEXT NOT NULL,
    series    TEXT PRIMARY KEY,
    token     TEXT NOT NULL,
    last_used TEXT NOT NULL
);
