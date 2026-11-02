-- Accounts without a password: created with "Get started" and signed into by scanning with a phone.
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

-- A browser asking to be signed in. It already holds its own key pair; an approved phone (scan,
-- or a number when the user typed a username) creates the browser's device, wraps the vault key
-- for it and releases a session that the browser collects once.
CREATE TABLE login_requests (
    id              UUID PRIMARY KEY,
    secret_hash     BYTEA NOT NULL,                     -- the browser polls with this secret
    user_id         UUID REFERENCES users (id) ON DELETE CASCADE,  -- set for "sign in with username", or on approval
    device_id       UUID NOT NULL,                      -- the browser's device id once approved
    name            TEXT NOT NULL,
    enc_public_key  TEXT NOT NULL,
    sign_public_key TEXT NOT NULL,
    challenge       BYTEA,                              -- number matching (username flow)
    status          TEXT NOT NULL DEFAULT 'pending',
    session_token   TEXT,                               -- handed to the browser once, then cleared
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX login_requests_user ON login_requests (user_id, status);
