-- SMS Bridge server schema v1.
-- The server never stores message content in plaintext: sync_events.ciphertext is encrypted on
-- the phone with the account vault key (Phase 6). Only routing/ordering metadata is clear.

CREATE TABLE users (
    id              UUID PRIMARY KEY,
    username        TEXT NOT NULL,
    password_hash   TEXT NOT NULL,              -- Argon2id, PHC string format
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX users_username_lower ON users (lower(username));

-- Browser/API login sessions. Only a SHA-256 of the token is stored.
CREATE TABLE user_sessions (
    token_hash      BYTEA PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    user_agent      TEXT
);
CREATE INDEX user_sessions_user ON user_sessions (user_id);
CREATE INDEX user_sessions_expiry ON user_sessions (expires_at);

CREATE TABLE devices (
    id              UUID PRIMARY KEY,           -- the phone's own SMS Bridge device id
    user_id         UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name            TEXT NOT NULL,
    platform        TEXT NOT NULL,
    public_key      TEXT,                       -- device identity key (Phase 6), base64
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMPTZ,
    revoked_at      TIMESTAMPTZ
);
CREATE INDEX devices_user ON devices (user_id);

-- One-time codes a logged-in user creates to register a phone without typing a password on it.
CREATE TABLE pairing_codes (
    code_hash       BYTEA PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    used_at         TIMESTAMPTZ
);

-- Bearer tokens for devices. Only a SHA-256 of the token is stored.
CREATE TABLE device_tokens (
    token_hash      BYTEA PRIMARY KEY,
    device_id       UUID NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at    TIMESTAMPTZ,
    revoked_at      TIMESTAMPTZ
);
CREATE INDEX device_tokens_device ON device_tokens (device_id);

CREATE TABLE revoked_devices (
    device_id       UUID PRIMARY KEY REFERENCES devices (id) ON DELETE CASCADE,
    revoked_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_by      UUID REFERENCES users (id) ON DELETE SET NULL,
    reason          TEXT
);

-- Encrypted, immutable sync events (used from Phase 6).
CREATE TABLE sync_events (
    event_id        UUID PRIMARY KEY,           -- generated on the phone; idempotency key
    server_sequence BIGSERIAL NOT NULL UNIQUE,  -- account-wide order assigned on receipt
    user_id         UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device_id       UUID NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    device_sequence BIGINT NOT NULL,
    event_type      TEXT NOT NULL,
    created_at_device TIMESTAMPTZ NOT NULL,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    ciphertext      BYTEA NOT NULL,
    UNIQUE (device_id, device_sequence)
);
CREATE INDEX sync_events_user_seq ON sync_events (user_id, server_sequence);

CREATE TABLE sync_checkpoints (
    device_id       UUID PRIMARY KEY REFERENCES devices (id) ON DELETE CASCADE,
    last_server_sequence BIGINT NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Metadata about encrypted backups stored on the server (content lives in object storage).
CREATE TABLE backups (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device_id       UUID REFERENCES devices (id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    size_bytes      BIGINT NOT NULL,
    sha256          TEXT NOT NULL,
    storage_key     TEXT NOT NULL,
    encrypted       BOOLEAN NOT NULL DEFAULT TRUE CHECK (encrypted)
);
CREATE INDEX backups_user ON backups (user_id, created_at DESC);

CREATE TABLE account_settings (
    user_id         UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    settings        JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Security-relevant actions. No message data, no tokens, no passwords.
CREATE TABLE audit_events (
    id              BIGSERIAL PRIMARY KEY,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    user_id         UUID,
    device_id       UUID,
    action          TEXT NOT NULL,
    outcome         TEXT NOT NULL,
    ip              TEXT
);
CREATE INDEX audit_events_user ON audit_events (user_id, occurred_at DESC);
