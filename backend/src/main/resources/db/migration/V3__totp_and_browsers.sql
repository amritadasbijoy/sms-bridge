-- Phase 7: two-factor authentication (TOTP, RFC 6238) for new browsers.

ALTER TABLE users ADD COLUMN totp_secret TEXT;            -- base32; set during setup, active once enabled
ALTER TABLE users ADD COLUMN totp_enabled_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN totp_last_step BIGINT;       -- replay protection: each code works once

-- Browsers that passed 2FA. Only a SHA-256 of the cookie token is stored.
CREATE TABLE trusted_browsers (
    token_hash      BYTEA PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    user_agent      TEXT
);
CREATE INDEX trusted_browsers_user ON trusted_browsers (user_id);
