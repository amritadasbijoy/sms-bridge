-- Simple mode (0.10.0): custom phone names, typed sign-in codes, auto-unlink, server retention and
-- an optional password that opens the vault (a virtual device whose X25519 key is derived from the
-- password in the browser; the server only holds its public key and wrapped vault keys).

ALTER TABLE devices ADD COLUMN label TEXT;                -- name chosen by the user ("Office phone")
ALTER TABLE devices ADD COLUMN expires_at TIMESTAMPTZ;    -- a browser is unlinked automatically after this

ALTER TABLE login_requests ADD COLUMN short_code TEXT;    -- 8 digits typed into the phone instead of scanning
CREATE INDEX login_requests_code ON login_requests (short_code) WHERE status = 'pending';

-- Wrapped vault keys for the password live in vault_keys with recipient 'password'.
CREATE TABLE password_vault (
    user_id         UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    salt            BYTEA NOT NULL,
    iterations      INT NOT NULL,
    public_key      TEXT NOT NULL,                        -- X25519, raw 32 bytes, base64
    verifier_hash   BYTEA NOT NULL,                       -- SHA-256 of a value only the password can derive
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Server copy kept for this many days (null: forever, the default so existing accounts keep everything).
-- Set by the phone's "Share last …" choice; phones upload only that range too.
ALTER TABLE users ADD COLUMN retention_days INT;
