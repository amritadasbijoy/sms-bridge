-- Phase 6: account vault (wrapped keys only) and signed, encrypted sync events.
-- See docs/sync-design.md. The server never holds an unwrapped vault key.

ALTER TABLE devices ADD COLUMN enc_public_key  TEXT;   -- X25519, raw 32 bytes, base64
ALTER TABLE devices ADD COLUMN sign_public_key TEXT;   -- Ed25519, raw 32 bytes, base64
ALTER TABLE devices ADD COLUMN vault_trusted_at TIMESTAMPTZ;

CREATE TABLE vaults (
    user_id         UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    current_version INT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per (key version, recipient). recipient = 'recovery' or a device id.
CREATE TABLE vault_keys (
    user_id         UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    key_version     INT NOT NULL,
    recipient       TEXT NOT NULL,
    wrapped_key     BYTEA NOT NULL,
    created_by      UUID REFERENCES devices (id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, key_version, recipient)
);

-- Event type is inside the ciphertext (decision 2): the server stores 'opaque'.
ALTER TABLE sync_events ALTER COLUMN event_type SET DEFAULT 'opaque';
ALTER TABLE sync_events ALTER COLUMN created_at_device DROP NOT NULL;
ALTER TABLE sync_events ADD COLUMN key_version INT NOT NULL DEFAULT 1;
ALTER TABLE sync_events ADD COLUMN signature BYTEA NOT NULL DEFAULT '\x'::bytea;
ALTER TABLE sync_events ALTER COLUMN signature DROP DEFAULT;
