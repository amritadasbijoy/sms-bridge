-- Invite codes: an existing user lets one more person create an account (closed registration).
-- Only a SHA-256 of the code is stored. Browser devices reuse `devices` with platform 'web'.

CREATE TABLE invites (
    code_hash       BYTEA PRIMARY KEY,
    created_by      UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    used_by         UUID REFERENCES users (id) ON DELETE SET NULL,
    used_at         TIMESTAMPTZ
);
CREATE INDEX invites_created_by ON invites (created_by);
